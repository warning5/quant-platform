package com.quant.platform.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.llm.domain.LlmAnalysis;
import com.quant.platform.llm.domain.LlmAnalysisMapper;
import com.quant.platform.recommendation.domain.StockRecommendation;
import com.quant.platform.recommendation.service.RecommendationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

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
            PE(TTM): %s  PB: %s  股息率: %s%%
            PE 3年分位: %s%%  PB 3年分位: %s%%
            自由现金流收益率: %s%%
            ROE: %s%%  净利润增长: %s%%  负债率: %s%%
            毛利率: %s%%  经营现金流/净利润: %s%%
            盈利质量得分: %s  营收质量得分: %s

            【技术面】
            RSI14: %s  20日动量: %s  20日波动率: %s
            距52周高点回撤: %s%%  20日换手率: %s%%
            MACD信号: %s

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

        return String.format(USER_PROMPT_TEMPLATE,
                rec.getStockCode(), rec.getStockName(), industry,
                fmt(factorValues.get("VAL_PE_TTM")), fmt(factorValues.get("VAL_PB")),
                fmt(factorValues.get("VAL_DIVIDEND_YIELD")),
                fmt(factorValues.get("VAL_PE_PERCENTILE")), fmt(factorValues.get("VAL_PB_PERCENTILE")),
                fmt(factorValues.get("VAL_FCF_YIELD")),
                fmt(financialData.get("roe")), fmt(financialData.get("netProfitYoy")),
                fmt(financialData.get("debtToAsset")),
                fmt(financialData.get("grossMargin")), fmt(financialData.get("ocfRatio")),
                fmt(factorValues.get("FIN_EARNINGS_QUALITY")), fmt(factorValues.get("FIN_REVENUE_QUALITY")),
                fmt(factorValues.get("RSI14")), fmt(factorValues.get("MOM20")),
                fmt(factorValues.get("VOL20")), fmt(factorValues.get("PRICE_52W_HIGH_PCT")),
                fmt(factorValues.get("TURN20")), fmt(factorValues.get("MACD")),
                String.valueOf(rec.getFinalScore() != null ? Math.round(rec.getFinalScore() * 100) : "N/A")
        );
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

        JsonNode buyRange = json.path("buy_price_range");
        if (!buyRange.isMissingNode()) {
            analysis.setBuyPriceLow(buyRange.path("low").decimalValue());
            analysis.setBuyPriceHigh(buyRange.path("high").decimalValue());
        }

        if (!json.path("stop_loss").isMissingNode()) analysis.setStopLoss(json.path("stop_loss").decimalValue());
        if (!json.path("target_price").isMissingNode()) analysis.setTargetPrice(json.path("target_price").decimalValue());

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

    private Map<String, Double> getLatestFactorValues(String stockCode) {
        Map<String, Double> result = new HashMap<>();
        try {
            jdbcTemplate.query(
                "SELECT factor_code, factor_value FROM factor_value WHERE stock_code = ? AND trade_date = (SELECT MAX(trade_date) FROM factor_value WHERE stock_code = ?)",
                rs -> { result.put(rs.getString("factor_code"), rs.getDouble("factor_value")); },
                stockCode, stockCode);
        } catch (Exception ignored) {}
        return result;
    }

    private String getIndustry(String stockCode) {
        try {
            return jdbcTemplate.queryForObject("SELECT industry FROM stock_info WHERE stock_code = ? LIMIT 1", String.class, stockCode);
        } catch (Exception e) { return "未知"; }
    }

    private Map<String, Object> getFinancialData(String stockCode) {
        Map<String, Object> result = new HashMap<>();
        try {
            jdbcTemplate.query(
                "SELECT roe, net_profit_yoy, debt_to_asset_ratio, gross_profit_margin FROM stock_financial_indicator WHERE stock_code = ? ORDER BY end_date DESC LIMIT 1",
                rs -> {
                    result.put("roe", rs.getObject("roe"));
                    result.put("netProfitYoy", rs.getObject("net_profit_yoy"));
                    result.put("debtToAsset", rs.getObject("debt_to_asset_ratio"));
                    result.put("grossMargin", rs.getObject("gross_profit_margin"));
                }, stockCode);
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
}
