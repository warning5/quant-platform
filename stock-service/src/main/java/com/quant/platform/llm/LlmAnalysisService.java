package com.quant.platform.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.factor.service.ClickHouseFactorValueService;
import com.quant.platform.llm.domain.LlmAnalysis;
import com.quant.platform.llm.domain.LlmAnalysisMapper;
import com.quant.platform.recommendation.domain.StockRecommendation;
import com.quant.platform.recommendation.service.RecommendationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * LLM 推理管线
 * 流程: 取候选股 → 组装Prompt → 调用LLM → 解析结果 → 持久化
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmAnalysisService {

    private final LlmService llmService;
    private final LlmAnalysisMapper llmAnalysisMapper;
    private final RecommendationService recommendationService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ClickHouseFactorValueService clickHouseFactorValueService;

    /** 系统Prompt */
    private static final String SYSTEM_PROMPT = """
            你是A股价值投资分析师，擅长从基本面、技术面、市场面三个维度综合评估股票的投资价值。

            你的分析原则：
            1. 估值安全第一：PE/PB历史分位低于30%才考虑，分位越低越安全
            2. 质地为本：关注ROE、盈利质量、现金流，排除财务造假风险
            3. 回撤是机会：52周回撤越深，安全边际越高（但需排除基本面恶化的情况）
            4. 现金流为王：自由现金流收益率高说明公司真能赚钱
            5. 技术面辅助：RSI超卖区+低波动，说明市场情绪稳定

            请严格按照指定的JSON格式输出，不要添加多余解释。
            """;

    /** 用户Prompt模板 */
    private static final String USER_PROMPT_TEMPLATE = """
            请分析以下股票，给出投资建议。

            【股票信息】
            代码: %s  名称: %s  行业: %s

            【基本面】
            PE(TTM): %s  PE 3年分位: %s%%
            自由现金流收益率: %s%%
            ROE: %s%%  净利润增长: %s%%  负债率: %s%%
            毛利率: %s%%  经营现金流/净利润: %s%%
            盈利质量得分: %s  营收质量得分: %s

            【技术面】
            20日动量: %s  20日波动率: %s
            行业相对动量: %s  Amihud非流动性: %s

            【综合评分】
            规则引擎综合得分: %s/100

            请输出JSON（不要用markdown包裹）：
            {
              "recommendation": "BUY或WATCH或SKIP",
              "buy_price_range": {"low": 买入价下限, "high": 买入价上限},
              "stop_loss": 止损价,
              "target_price": 目标价,
              "risk_level": "LOW或MEDIUM或HIGH",
              "logic": "3~5句话的投资逻辑，说清楚为什么推荐/不推荐",
              "position_advice": "仓位建议，如1~3成",
              "catalysts": ["催化剂1", "催化剂2"],
              "risks": ["风险1", "风险2"],
              "time_horizon": "投资周期，如1~3个月"
            }

            【重要约束 - 价格字段规则】
            ★ BUY和WATCH类型：buy_price_range、stop_loss、target_price 为必填项！
              - BUY: 建议买入区间（低于当前合理估值的建仓价），止损-8%%以内，目标+20%%以上
              - WATCH: 观望参考价（如基本面改善值得介入的价位），止损-10%%以内，目标+15%%左右
              - 所有价格保留1~2位小数，使用正数。禁止填写0、null、或省略！
            ★ SKIP类型：buy_price_range、stop_loss、target_price 填 null 即可
              - 因为不推荐买入，无需给出价格建议
            """;

    /**
     * 对推荐候选股执行LLM推理
     */
    public List<LlmAnalysis> analyzeRecommendations(List<StockRecommendation> recommendations) {
        if (!llmService.isEnabled()) {
            log.warn("[LlmAnalysisService] LLM未启用，跳过推理");
            return Collections.emptyList();
        }
        if (recommendations == null || recommendations.isEmpty()) {
            return Collections.emptyList();
        }

        // 先清除今天的旧记录，防止历史旧记录（如用其他策略运行的结果）污染前端展示
        int deleted = llmAnalysisMapper.deleteByAnalysisDate(LocalDate.now());
        log.info("[LlmAnalysisService] 清除今日旧LLM分析记录: {} 条", deleted);

        log.info("[LlmAnalysisService] 开始LLM推理: 候选股数量={}", recommendations.size());

        Map<String, String> prompts = new LinkedHashMap<>();
        for (StockRecommendation rec : recommendations) {
            prompts.put(rec.getStockCode(), buildUserPrompt(rec));
        }

        Map<String, JsonNode> results = llmService.batchChatAsJson(SYSTEM_PROMPT, prompts);

        List<LlmAnalysis> analyses = new ArrayList<>();
        for (StockRecommendation rec : recommendations) {
            JsonNode jsonNode = results.get(rec.getStockCode());
            if (jsonNode != null) {
                try {
                    LlmAnalysis analysis = parseAnalysis(rec, jsonNode);
                    llmAnalysisMapper.insert(analysis);
                    analyses.add(analysis);
                } catch (Exception e) {
                    log.warn("[LlmAnalysisService] 解析失败: code={}", rec.getStockCode());
                }
            }
        }

        log.info("[LlmAnalysisService] LLM推理完成: 成功={}/{}", analyses.size(), recommendations.size());
        return analyses;
    }

    private String buildUserPrompt(StockRecommendation rec) {
        Map<String, Double> factorValues = getLatestFactorValues(rec.getStockCode());
        String industry = getIndustry(rec.getStockCode());
        Map<String, Object> financialData = getFinancialData(rec.getStockCode());

        String prompt = String.format(USER_PROMPT_TEMPLATE,
                rec.getStockCode(), rec.getStockName(), industry,
                fmt(factorValues.get("VAL_PE_TTM")),
                fmt(factorValues.get("VAL_PE_PERCENTILE")),
                fmt(factorValues.get("VAL_FCF_YIELD")),
                fmt(financialData.get("roe")), fmt(financialData.get("netProfitYoy")),
                fmt(financialData.get("debtToAsset")),
                fmt(financialData.get("grossMargin")), fmt(financialData.get("ocfRatio")),
                fmt(factorValues.get("FIN_EARNINGS_QUALITY")), fmt(factorValues.get("FIN_REVENUE_QUALITY")),
                fmt(factorValues.get("MOM20")),
                fmt(factorValues.get("VOL20")),
                fmt(factorValues.get("INDUSTRY_REL_MOM")), fmt(factorValues.get("AMIHUD_ILLIQUIDITY")),
                String.valueOf(rec.getFinalScore() != null ? Math.round(rec.getFinalScore() * 100) : "N/A")
        );

        log.info("[LlmAnalysisService] {}({}) Prompt摘要: PE={}, PB={}, ROE={}, 因子数={}",
                rec.getStockName(), rec.getStockCode(),
                factorValues.getOrDefault("VAL_PE_TTM", -1.0),
                financialData.get("roe"),
                factorValues.size());
        return prompt;
    }

    private LlmAnalysis parseAnalysis(StockRecommendation rec, JsonNode json) {
        LlmAnalysis analysis = LlmAnalysis.builder()
                .stockCode(rec.getStockCode())
                .stockName(rec.getStockName())
                .analysisDate(LocalDate.now())
                .model(llmService.getDefaultModel())
                .recommendation(json.path("recommendation").asText("WATCH"))
                .riskLevel(json.path("risk_level").asText("MEDIUM"))
                .logic(json.path("logic").asText(""))
                .positionAdvice(json.path("position_advice").asText(""))
                .timeHorizon(json.path("time_horizon").asText(""))
                .createdAt(LocalDateTime.now())
                .build();

        // 获取当前价格作为fallback基准
        double currentPrice = fetchCurrentPrice(rec.getStockCode());
        String recType = json.path("recommendation").asText("WATCH");

        // ===== 价格字段解析策略 =====
        // BUY/WATCH: LLM应给出完整价格，缺失时用当前价兜底计算
        // SKIP: 不建议买入，价格字段留null（前端显示"不适用"），避免误导
        boolean isSkip = "SKIP".equals(recType);

        if (!isSkip) {
            // --- BUY / WATCH: 价格字段必填 ---
            // 买入价区间
            JsonNode buyRange = json.path("buy_price_range");
            if (!buyRange.isMissingNode()) {
                double low = parsePriceWithFallback(buyRange.path("low"), currentPrice * 0.95, currentPrice);
                double high = parsePriceWithFallback(buyRange.path("high"), currentPrice * 1.02, currentPrice);
                // 防止 low >= high
                if (low >= high && currentPrice > 0) {
                    low = round2(currentPrice * 0.95);
                    high = round2(currentPrice * 1.02);
                }
                analysis.setBuyPriceLow(BigDecimal.valueOf(low));
                analysis.setBuyPriceHigh(BigDecimal.valueOf(high));
            } else if (currentPrice > 0) {
                // 整个buy_price_range字段缺失
                analysis.setBuyPriceLow(BigDecimal.valueOf(round2(currentPrice * 0.95)));
                analysis.setBuyPriceHigh(BigDecimal.valueOf(round2(currentPrice * 1.02)));
            }

            // 止损价（BUY -8%, WATCH -10%）
            double stopLossPct = "BUY".equals(recType) ? 0.92 : 0.90;
            double stopLoss = parsePriceWithFallback(json.path("stop_loss"), currentPrice * stopLossPct, currentPrice);
            if (stopLoss > 0) analysis.setStopLoss(BigDecimal.valueOf(stopLoss));

            // 目标价（BUY +25%, WATCH +15%）
            double targetPct = "BUY".equals(recType) ? 1.25 : 1.15;
            double targetPrice = parsePriceWithFallback(json.path("target_price"), currentPrice * targetPct, currentPrice);
            if (targetPrice > 0) analysis.setTargetPrice(BigDecimal.valueOf(targetPrice));
        }
        // SKIP: buyPriceLow/High/stopLoss/targetPrice 保持 null → 前端显示"不适用"

        List<String> catalysts = new ArrayList<>();
        JsonNode cn = json.path("catalysts");
        if (cn.isArray()) for (JsonNode c : cn) catalysts.add(c.asText());
        analysis.setCatalysts(String.join(";", catalysts));

        List<String> risks = new ArrayList<>();
        JsonNode rn = json.path("risks");
        if (rn.isArray()) for (JsonNode r : rn) risks.add(r.asText());
        analysis.setRisks(String.join(";", risks));

        analysis.setRawResponse(json.toString());
        return analysis;
    }

    /**
     * 解析价格节点，处理0/null/缺失情况
     *
     * @param node JSON价格节点
     * @param defaultValue 当前价格为基准计算的默认值
     * @param currentPrice 当前股价（用于判断值是否合理）
     * @return 有效价格，或defaultValue
     */
    private double parsePriceWithFallback(JsonNode node, double defaultValue, double currentPrice) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return round2(defaultValue);
        }
        try {
            double val = node.decimalValue().doubleValue();
            // LLM返回0或负数视为无效，使用默认值
            if (val <= 0) {
                return round2(defaultValue);
            }
            // 价格异常高（>当前价10倍）也视为异常
            if (currentPrice > 0 && val > currentPrice * 10) {
                log.warn("[LlmAnalysisService] 价格异常偏高: value={}, currentPrice={}", val, currentPrice);
                return round2(defaultValue);
            }
            return round2(val);
        } catch (Exception e) {
            return round2(defaultValue);
        }
    }

    /** 获取股票最新收盘价（用于price fallback计算） */
    private double fetchCurrentPrice(String stockCode) {
        String pureCode = stripSuffix(stockCode);
        try {
            Map<String, Double> dailyData = clickHouseFactorValueService.findStockDailyLatest(pureCode);
            if (dailyData != null && dailyData.containsKey("close_price")) {
                return dailyData.get("close_price");
            }
        } catch (Exception e) {
            log.debug("[LlmAnalysisService] 获取{}当前价失败: {}", stockCode, e.getMessage());
        }
        return 0;
    }

    private static double round2(double val) {
        return Math.round(val * 100.0) / 100.0;
    }

    /**
     * 获取股票最新因子值
     * 三级fallback链路：
     *   1. CH factor_value 纯代码格式（94+技术因子）
     *   2. CH factor_value 带后缀格式（3个Python特殊因子：PE分位/PB分位/52周回撤）
     *   3. CH stock_daily 补齐基础估值（PE/PB/价格）— 5490股全覆盖
     *
     * @param stockCode 股票代码，可能带后缀（如 600027.SH）或不带（如 000526）
     * @return Map<factorCode, Double> 因子实际值（非排名）
     */
    private Map<String, Double> getLatestFactorValues(String stockCode) {
        Map<String, Double> result = new HashMap<>();

        // 1. 优先从 ClickHouse 查纯代码格式（94+ 技术因子）
        try {
            Map<String, Map<String, Double>> chFactors = clickHouseFactorValueService.findLatestBySymbol(stockCode);
            if (chFactors != null && !chFactors.isEmpty()) {
                for (Map.Entry<String, Map<String, Double>> entry : chFactors.entrySet()) {
                    String factorCode = entry.getKey();
                    Map<String, Double> vals = entry.getValue();
                    Double factorVal = vals.get("factorVal");
                    if (factorVal != null) {
                        result.put(factorCode, factorVal);
                    } else {
                        Double rankValue = vals.get("rankValue");
                        if (rankValue != null) {
                            result.put(factorCode, rankValue);
                        }
                    }
                }
                log.info("[LlmAnalysisService] {} CH因子命中(纯代码): {} 个因子", stockCode, result.size());
            }
        } catch (Exception e) {
            log.warn("[LlmAnalysisService] {} ClickHouse因子查询失败: {}", stockCode, e.getMessage());
        }

        // 2. 查带后缀格式（补充 Python 特殊因子：VAL_PE_PERCENTILE/AMIHUD_ILLIQUIDITY/INDUSTRY_REL_MOM）
        //    需要显式拼接后缀查询，获取存储在后缀格式中的因子
        String pureCode = stripSuffix(stockCode);
        if (!stockCode.contains(".")) {
            String suffixedCode = lookupSuffixedCode(pureCode);
            if (suffixedCode != null && !suffixedCode.equals(stockCode)) {
                try {
                    Map<String, Map<String, Double>> suffixFactors = clickHouseFactorValueService.findLatestBySymbol(suffixedCode);
                    if (suffixFactors != null) {
                        int added = 0;
                        for (Map.Entry<String, Map<String, Double>> entry : suffixFactors.entrySet()) {
                            // 只补充纯代码查询中缺失的因子（避免覆盖已有值）
                            if (!result.containsKey(entry.getKey())) {
                                Double fv = entry.getValue().get("factorVal");
                                if (fv != null) {
                                    result.put(entry.getKey(), fv);
                                    added++;
                                }
                            }
                        }
                        if (added > 0) {
                            log.info("[LlmAnalysisService] {} 后缀({})补齐: {} 个新因子", stockCode, suffixedCode, added);
                        }
                    }
                } catch (Exception e) {
                    log.warn("[LlmAnalysisService] {} 后缀因子查询失败: {}", suffixedCode, e.getMessage());
                }
            }
        }

        // 3. CH stock_daily 回退：补充缺失的基础估值指标（PE/价格）
        try {
            boolean needsDailyFallback = !result.containsKey("VAL_PE_TTM");
            if (needsDailyFallback) {
                Map<String, Double> dailyData = clickHouseFactorValueService.findStockDailyLatest(pureCode);
                if (!dailyData.isEmpty()) {
                    if (!result.containsKey("VAL_PE_TTM") && dailyData.containsKey("pe_ttm"))
                        result.put("VAL_PE_TTM", dailyData.get("pe_ttm"));
                    log.info("[LlmAnalysisService] {} CH stock_daily 补齐: {}", stockCode, dailyData.keySet());
                }
            }
        } catch (Exception e) {
            log.warn("[LlmAnalysisService] {} CH stock_daily 查询失败: {}", stockCode, e.getMessage());
        }

        return result;
    }

    /** 查询纯代码对应的带后缀代码（如 920906 → 920906.BJ） */
    private String lookupSuffixedCode(String pureCode) {
        try {
            return jdbcTemplate.queryForObject(
                "SELECT CONCAT(code, '.', market) FROM stock_info WHERE code = ? LIMIT 1",
                String.class, pureCode);
        } catch (Exception e) {
            log.debug("[LlmAnalysisService] 无法查找后缀: code={}", pureCode);
            return null;
        }
    }

    /** 去掉股票代码后缀: "600027.SH" → "600027" */
    private static String stripSuffix(String code) {
        if (code == null) return null;
        int dot = code.indexOf('.');
        return dot > 0 ? code.substring(0, dot) : code;
    }

    private String getIndustry(String stockCode) {
        String pureCode = stripSuffix(stockCode);
        try {
            return jdbcTemplate.queryForObject("SELECT industry FROM stock_info WHERE code = ? LIMIT 1", String.class, pureCode);
        } catch (Exception e) { return "未知"; }
    }

    private Map<String, Object> getFinancialData(String stockCode) {
        Map<String, Object> result = new HashMap<>();
        String pureCode = stripSuffix(stockCode);
        try {
            // 注意：stock_financial_indicator 列名是 code（不是 stock_code）
            // 经营现金流用 net_operate_cf，净利润比率用 operating_cf_to_np
            jdbcTemplate.query(
                "SELECT roe, net_profit_yoy, debt_to_asset_ratio, gross_profit_margin, "
                + "net_operate_cf, operating_cf_to_np "
                + "FROM stock_financial_indicator WHERE code = ? ORDER BY end_date DESC LIMIT 1",
                rs -> {
                    result.put("roe", rs.getObject("roe"));
                    result.put("netProfitYoy", rs.getObject("net_profit_yoy"));
                    result.put("debtToAsset", rs.getObject("debt_to_asset_ratio"));
                    result.put("grossMargin", rs.getObject("gross_profit_margin"));
                    // 经营现金流/净利润：表中有现成的 operating_cf_to_np 字段
                    result.put("ocfRatio", rs.getObject("operating_cf_to_np"));
                }, pureCode);
        } catch (Exception ignored) {}
        return result;
    }

    private String fmt(Double value) { return value != null ? String.format("%.2f", value) : "N/A"; }
    private String fmt(Object value) {
        if (value == null) return "N/A";
        if (value instanceof Number) return String.format("%.2f", ((Number) value).doubleValue());
        return value.toString();
    }

    public List<LlmAnalysis> getLatestAnalyses() {
        return llmAnalysisMapper.findByDate(LocalDate.now());
    }

    public LlmAnalysis getLatestAnalysis(String stockCode) {
        return llmAnalysisMapper.findLatestByStockCode(stockCode);
    }

    /**
     * 对单只股票执行LLM推理（供个股分析页"LLM深度解读"按钮调用）
     * 1. 查 stock_info 获取名称
     * 2. 查因子值 + 财务数据 组装 Prompt
     * 3. 调用 LLM → 解析 → 持久化
     */
    public LlmAnalysis analyzeSingleStock(String stockCode) {
        if (!llmService.isEnabled()) {
            throw new IllegalStateException("LLM未启用或API Key未配置，请在 application.yml 或环境变量中配置 LLM_API_KEY");
        }
        String pureCode = stripSuffix(stockCode);
        log.info("[LlmAnalysisService] 单股LLM分析: code={}", pureCode);

        // 1. 获取股票名称
        String stockName;
        try {
            stockName = jdbcTemplate.queryForObject(
                    "SELECT name FROM stock_info WHERE code = ? LIMIT 1", String.class, pureCode);
        } catch (Exception e) {
            stockName = pureCode;
        }

        // 2. 构造最小 StockRecommendation 用于复用 buildUserPrompt/parseAnalysis
        StockRecommendation rec = new StockRecommendation();
        rec.setStockCode(pureCode);
        rec.setStockName(stockName);
        rec.setFinalScore(0.0); // 单股分析无推荐评分

        // 3. 构建 Prompt
        String userPrompt = buildUserPrompt(rec);

        // 4. 调用 LLM
        JsonNode jsonNode = llmService.chatAsJson(SYSTEM_PROMPT, userPrompt);
        if (jsonNode == null) {
            throw new RuntimeException("LLM调用失败，未返回有效JSON，请检查后端日志中 [LlmService] 相关错误");
        }

        // 5. 解析结果
        LlmAnalysis analysis;
        try {
            analysis = parseAnalysis(rec, jsonNode);
            // 删除该股票今天的旧记录，再插入新的
            llmAnalysisMapper.deleteByStockCodeAndDate(pureCode, LocalDate.now());
            llmAnalysisMapper.insert(analysis);
            log.info("[LlmAnalysisService] 单股LLM分析完成: code={}, recommendation={}, risk={}",
                    pureCode, analysis.getRecommendation(), analysis.getRiskLevel());
        } catch (Exception e) {
            throw new RuntimeException("LLM分析结果解析失败: " + e.getMessage(), e);
        }
        return analysis;
    }
}
