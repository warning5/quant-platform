package com.quant.platform.recommendation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.factor.ic.service.FactorIcService;
import com.quant.platform.factor.service.FactorAnalysisService;
import com.quant.platform.market.domain.MarketDailyBar;
import com.quant.platform.market.service.MarketDataService;
import com.quant.platform.recommendation.domain.StockRecommendation;
import com.quant.platform.recommendation.mapper.RecommendationMapper;
import com.quant.platform.screen.dto.ScreenRequest;
import com.quant.platform.screen.dto.ScreenResult;
import com.quant.platform.screen.service.StockScreenService;
import com.quant.platform.stock.analysis.domain.AnalysisOverview;
import com.quant.platform.stock.analysis.domain.ScoreDetail;
import com.quant.platform.stock.analysis.service.AnalysisService;
import com.quant.platform.stock.analysis.service.EventSignalService;
import com.quant.platform.stock.analysis.service.NewsEventParser;
import com.quant.platform.stock.entity.StockDaily;
import com.quant.platform.stock.entity.StockInfo;
import com.quant.platform.stock.mapper.StockInfoMapper;
import com.quant.platform.stock.service.ClickHouseStockService;
import com.quant.platform.stock.service.DividendService;
import com.quant.platform.strategy.domain.StrategyDefinition;
import com.quant.platform.strategy.mapper.StrategyDefinitionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 智能推荐服务
 * Phase 1: 因子选股 → 个股深度分析 → 等权融合 → 排序输出
 * Phase 2: 多维市场环境识别 → Regime-Adaptive 动态权重 → 行业分散化
 * 管线流程:
 * 1. Market Regime Detection (指数趋势 + ATR波动率 + 市场宽度 → BULL/BEAR/SIDEWAYS)
 * 2. Multi-Factor Screening (StockScreenService, Top 50)
 * 3. Individual Stock Analysis (AnalysisService, Top N 深度分析)
 * 4. Score Fusion (Regime-Adaptive 动态权重)
 * 5. Industry Diversification (同行业上限 + 相关性去重)
 * 6. Persist & Return
 */
@Slf4j
@Service
public class RecommendationService {

    /**
     * 因子选股取 Top N（广筛）
     */
    private static final int SCREEN_TOP_N = 50;
    /**
     * 个股深度分析取 Top N（精筛）
     */
    private static final int ANALYSIS_TOP_N = 20;
    /**
     * 同行业最多推荐 N 只
     */
    private static final int MAX_SAME_INDUSTRY = 3;
    /**
     * 申万一级行业 → 指数代码映射（从 stock_info.industry 到 index_daily.code）
     */
    private static final Map<String, String> INDUSTRY_TO_SW_CODE = Map.ofEntries(
            Map.entry("农林牧渔", "801010"), Map.entry("基础化工", "801030"),
            Map.entry("钢铁", "801040"), Map.entry("有色金属", "801050"),
            Map.entry("电子", "801080"), Map.entry("家用电器", "801110"),
            Map.entry("食品饮料", "801120"), Map.entry("纺织服饰", "801130"),
            Map.entry("轻工制造", "801140"), Map.entry("医药生物", "801150"),
            Map.entry("公用事业", "801160"), Map.entry("交通运输", "801170"),
            Map.entry("房地产", "801180"), Map.entry("商贸零售", "801200"),
            Map.entry("社会服务", "801210"), Map.entry("综合", "801230"),
            Map.entry("建筑材料", "801710"), Map.entry("建筑装饰", "801720"),
            Map.entry("电力设备", "801250"), Map.entry("国防军工", "801260"),
            Map.entry("计算机", "801270"), Map.entry("传媒", "801280"),
            Map.entry("通信", "801300"), Map.entry("汽车", "801880"),
            Map.entry("机械设备", "801890"),
            // 金融/资源/环保/消费
            Map.entry("银行", "801780"), Map.entry("非银金融", "801790"),
            Map.entry("煤炭", "801950"), Map.entry("石油石化", "801960"),
            Map.entry("环保", "801970"), Map.entry("美容护理", "801980")
    );
    /**
     * 二级行业名称 → 归约到一级行业的映射（解决 IND_CORR_GROUPS 含二级行业的问题）
     */
    private static final Map<String, String> SW2_TO_SW1 = Map.ofEntries(
            Map.entry("房地产开发", "房地产"),
            Map.entry("房地产服务", "房地产"),
            Map.entry("建筑材料", "建筑材料"),
            Map.entry("建筑装饰", "建筑装饰"),
            Map.entry("证券", "非银金融"),
            Map.entry("保险", "非银金融"),
            Map.entry("信托", "非银金融"),
            Map.entry("期货", "非银金融"),
            Map.entry("银行", "银行"),
            Map.entry("煤炭", "煤炭"),
            Map.entry("石油石化", "石油石化"),
            Map.entry("电力设备", "电力设备"),
            Map.entry("食品饮料", "食品饮料"),
            Map.entry("农林牧渔", "农林牧渔"),
            Map.entry("纺织服饰", "纺织服饰"),
            Map.entry("计算机", "计算机"),
            Map.entry("通信", "通信"),
            Map.entry("传媒", "传媒"),
            Map.entry("汽车", "汽车"),
            Map.entry("机械设备", "机械设备"),
            Map.entry("医药生物", "医药生物"),
            Map.entry("公用事业", "公用事业"),
            Map.entry("国防军工", "国防军工"),
            Map.entry("电子", "电子")
    );
    /**
     * ATR 计算周期
     */
    private static final int ATR_PERIOD = 20;
    /**
     * ATR 历史分位数回溯天数
     */
    private static final int ATR_LOOKBACK_DAYS = 250;
    /** P1: 默认IC预筛选阈值 */
    private static final double DEFAULT_IC_THRESHOLD = 0.03;
    /** P2: 默认半衰期（交易日） */
    private static final int DEFAULT_HALFLIFE_DAYS = 20;
    /**
     * 沪深300指数代码
     */
    private static final String SSE300_CODE = "000300";
    /**
     * 高相关行业分组（组内股票走势相关系数 > 0.7）
     * 同组内的行业共享分散化名额
     */
    private static final List<List<String>> INDUSTRY_CORR_GROUPS = List.of(
            List.of("银行", "非银金融"),           // 金融板块
            List.of("房地产开发", "房地产服务", "建筑装饰", "建筑材料"),  // 地产链
            List.of("煤炭", "石油石化", "电力设备"),  // 能源链
            List.of("食品饮料", "农林牧渔", "纺织服饰"),  // 消费链
            List.of("计算机", "通信", "传媒"),       // TMT
            List.of("汽车", "机械设备"),           // 制造链
            List.of("医药生物", "公用事业"),        // 防御板块
            List.of("电子", "国防军工")            // 科技制造
    );
    private final StockScreenService stockScreenService;
    private final AnalysisService analysisService;
    private final MarketDataService marketDataService;
    private final ClickHouseStockService clickHouseStockService;
    private final StockInfoMapper stockInfoMapper;
    private final RecommendationMapper recommendationMapper;
    private final StrategyDefinitionMapper strategyDefinitionMapper;
    private final ObjectMapper objectMapper;
    private final FactorIcService factorIcService;
    private final FactorAnalysisService factorAnalysisService;
    private final DividendService dividendService;
    private final StockBlacklistService stockBlacklistService;
    private final StrategyConfidenceService strategyConfidenceService;
    private final NewsEventParser newsEventParser;
    private final EventSignalService eventSignalService;
    private final com.quant.platform.market.MarketSentimentService marketSentimentService;
    private final javax.sql.DataSource dataSource;

    public RecommendationService(StockScreenService stockScreenService,
                                 AnalysisService analysisService,
                                 MarketDataService marketDataService,
                                 ClickHouseStockService clickHouseStockService,
                                 StockInfoMapper stockInfoMapper,
                                 RecommendationMapper recommendationMapper,
                                 StrategyDefinitionMapper strategyDefinitionMapper,
                                 ObjectMapper objectMapper,
                                 FactorIcService factorIcService,
                                 FactorAnalysisService factorAnalysisService,
                                 DividendService dividendService,
                                 StockBlacklistService stockBlacklistService,
                                 StrategyConfidenceService strategyConfidenceService,
                                 NewsEventParser newsEventParser,
                                 EventSignalService eventSignalService,
                                 com.quant.platform.market.MarketSentimentService marketSentimentService,
                                 javax.sql.DataSource dataSource) {
        this.stockScreenService = stockScreenService;
        this.analysisService = analysisService;
        this.marketDataService = marketDataService;
        this.clickHouseStockService = clickHouseStockService;
        this.stockInfoMapper = stockInfoMapper;
        this.recommendationMapper = recommendationMapper;
        this.strategyDefinitionMapper = strategyDefinitionMapper;
        this.objectMapper = objectMapper;
        this.factorIcService = factorIcService;
        this.factorAnalysisService = factorAnalysisService;
        this.dividendService = dividendService;
        this.stockBlacklistService = stockBlacklistService;
        this.strategyConfidenceService = strategyConfidenceService;
        this.newsEventParser = newsEventParser;
        this.eventSignalService = eventSignalService;
        this.marketSentimentService = marketSentimentService;
        this.dataSource = dataSource;
    }

    /**
     * 去掉股票代码后缀: "600027.SH" → "600027"
     */
    private static String stripSuffix(String code) {
        if (code == null) return null;
        int dot = code.indexOf('.');
        return dot > 0 ? code.substring(0, dot) : code;
    }

    /**
     * 将 TradingSignalEngine 的 5 值 action 映射为前端 3 值 actionTag
     * STRONG_BUY→BUY, BUY→BUY, HOLD→HOLD, REDUCE→HOLD, CLEAR→SELL
     */
    private static String mapActionTag(String action) {
        if (action == null) return null;
        return switch (action) {
            case "STRONG_BUY" -> "BUY";
            case "BUY" -> "BUY";
            case "HOLD" -> "HOLD";
            case "REDUCE" -> "HOLD";  // 减仓但仍在持有，前端归为 HOLD
            case "CLEAR" -> "SELL";   // 清仓映射为卖出
            default -> "HOLD";        // 未知归为持有
        };
    }

    private static double safeDiv(Integer numerator, double denominator) {
        if (numerator == null || denominator == 0) return 0.0;
        return Math.min(1.0, numerator / denominator);
    }

    private static long toLong(Object obj) {
        if (obj == null) return 0;
        if (obj instanceof Number) return ((Number) obj).longValue();
        try {
            return Long.parseLong(obj.toString());
        } catch (Exception e) {
            return 0;
        }
    }

    private static ScreenRequest.FactorWeight newFactor(String code, int direction, double weight) {
        ScreenRequest.FactorWeight fw = new ScreenRequest.FactorWeight();
        fw.setFactorCode(code);
        fw.setDirection(direction);
        fw.setWeight(weight);
        return fw;
    }

    /**
     * 生成推荐列表
     *
     * P1: 默认启用IC动态权重（不再需要weightMode="IC"）
     *     自动预筛选+方向对齐+衰减加权
     *
     * @param date        推荐日期（null 则使用最新可用日期）
     * @param topN        最终推荐数量（默认20）
     * @param diagnostics 输出参数，因子诊断信息（调用方传入空List来收集）
     * @return 推荐结果列表
     */
    public List<StockRecommendation> generateRecommendations(LocalDate date, Integer topN,
                                                             Long strategyId, String weightMode, List<FactorDiagnostic> diagnostics,
                                                             boolean enableConfidenceControl) {
        // date=null 时 StockScreenService.screen() 会自动取最新日期
        if (topN == null || topN <= 0) {
            topN = ANALYSIS_TOP_N;
        }
        // P1: 默认启用IC动态权重（STATIC模式手动关闭）
        // weightMode 优先级：请求参数 > 策略factorConfigJson > 默认ICW
        String effectiveWeightMode = resolveWeightMode(strategyId, weightMode);
        boolean useDynamicIc = !"STATIC".equalsIgnoreCase(effectiveWeightMode);

        log.info("[Recommendation] 开始生成推荐列表: date={}, topN={}, strategyId={}, weightMode={} (resolved={}), confidenceControl={}",
                date, topN, strategyId, weightMode, effectiveWeightMode, enableConfidenceControl);

        // 诊断：加载策略详情
        if (strategyId != null) {
            StrategyDefinition dbStrategy = strategyDefinitionMapper.selectById(strategyId);
            log.info("[Recommendation] 策略详情: id={}, strategyCode={}, strategyName={}, filterConfigJson={}",
                    dbStrategy != null ? dbStrategy.getId() : null,
                    dbStrategy != null ? dbStrategy.getStrategyCode() : "null",
                    dbStrategy != null ? dbStrategy.getStrategyName() : "null",
                    dbStrategy != null && dbStrategy.getFilterConfigJson() != null ? "present" : "null");
        }

        // P1-4: 检查上期推荐命中率，动态调整 topN（仅当指定了 strategyId 时）
        if (strategyId != null && topN > 10) {
            StockRecommendation latestRec = recommendationMapper.findLatest();
            if (latestRec != null && latestRec.getStrategyId() != null && latestRec.getStrategyId().equals(strategyId)) {
                LocalDate prevDate = latestRec.getRecommendDate();
                if (prevDate != null) {
                    Map<String, Object> hitStats = getHitRate(latestRec.getStrategyId(), prevDate);
                    Double hitRate = (Double) hitStats.get("hitRate");
                    if (hitRate != null && hitRate < 0.4) {
                        int originalTopN = topN;
                        topN = Math.max(10, topN - 5);
                        log.info("[Recommendation] 上期命中率{}%偏低({}), 缩减topN: {} -> {}",
                                hitRate * 100, prevDate, originalTopN, topN);
                    }
                }
            }
        }

        // Step 0.5: 策略置信度检查（方案C - Layer 1: 策略级风控）
        // 在黑名单过滤(Layer 2)之前执行，如果置信度过低直接降topN或建议暂停
        // 仅在启用置信度控制时生效
        if (enableConfidenceControl && strategyId != null) {
            try {
                var confidenceOpt = strategyConfidenceService.getLatestConfidence(strategyId);
                if (confidenceOpt.isPresent()) {
                    var conf = confidenceOpt.get();
                    String level = conf.getLevel();
                    Integer score = conf.getScore();
                    log.info("[Recommendation] 策略置信度: strategyId={}, level={}, score={}, hitRate={}%, avgReturn={}%",
                            strategyId, level,
                            score != null ? score : "N/A",
                            conf.getHitRateValue() != null ? conf.getHitRateValue().doubleValue() * 100 : 0,
                            conf.getAvgReturnValue() != null ? conf.getAvgReturnValue().doubleValue() : 0);

                    // 根据置信度调整 topN
                    int adjustedTopN = strategyConfidenceService.getAdjustedTopN(topN, conf);
                    if (adjustedTopN == -1) {
                        log.warn("[Recommendation] 策略置信度过低(SUSPENDED), 建议暂停使用该策略: strategyId={}, score={}", strategyId, score);
                        // 不阻止生成，但大幅缩减
                        topN = Math.max(3, topN / 3);
                    } else if (adjustedTopN < topN) {
                        int originalTopN = topN;
                        topN = adjustedTopN;
                        log.info("[Recommendation] 置信度调整topN: {} -> {} (level={}, score={})", originalTopN, topN, level, score);
                    }
                }
            } catch (Exception e) {
                log.warn("[Recommendation] 置信度查询异常，跳过调整: error={}", e.getMessage());
            }
        }

        // Step 1: 多因子选股（广筛 Top 50）
        // date=null 时 StockScreenService.screen() 内部自动 resolveLatestDate()
        ScreenResult screenResult = screenStocks(date, strategyId, useDynamicIc, effectiveWeightMode, diagnostics);
        List<ScreenResult.StockScore> candidates = screenResult.getStocks();
        if (candidates == null || candidates.isEmpty()) {
            log.warn("[Recommendation] 因子选股结果为空，无法生成推荐");
            return List.of();
        }

        // Step 1.5: 行业排除过滤（从策略 filterConfigJson 读取）
        List<String> excludeIndustries = getExcludeIndustries(strategyId);
        if (!excludeIndustries.isEmpty()) {
            Set<String> excludeSet = new HashSet<>(excludeIndustries);
            List<String> candidateCodes = candidates.stream()
                    .map(s -> stripSuffix(s.getSymbol()))
                    .collect(Collectors.toList());
            List<StockInfo> infos = stockInfoMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StockInfo>()
                            .in(StockInfo::getCode, candidateCodes)
                            .select(StockInfo::getCode, StockInfo::getIndustry, StockInfo::getName));
            Map<String, StockInfo> codeInfoMap = infos.stream()
                    .filter(i -> i.getCode() != null)
                    .collect(Collectors.toMap(StockInfo::getCode, i -> i, (a, b) -> a));

            // 收集被排除的股票及其行业（用于日志诊断）
            List<String> excludedStocks = new ArrayList<>();
            int before = candidates.size();
            candidates = candidates.stream()
                    .filter(s -> {
                        String pureCode = stripSuffix(s.getSymbol());
                        StockInfo info = codeInfoMap.get(pureCode);
                        String ind = info != null && info.getIndustry() != null ? info.getIndustry() : "";
                        String name = info != null && info.getName() != null ? info.getName() : s.getName();

                        // 检查是否应排除
                        boolean excluded = false;
                        String reason = "";

                        // 1. 无行业信息且配置了行业排除 → 排除（安全起见）
                        if (ind.isEmpty()) {
                            excluded = true;
                            reason = "无行业信息";
                        }
                        // 2. 行业关键词匹配
                        if (!excluded) {
                            excluded = excludeSet.stream().anyMatch(ind::contains);
                            if (excluded) reason = "匹配排除关键词";
                        }

                        if (excluded) {
                            excludedStocks.add(name + "(" + pureCode + ")[" + ind + "]-" + reason);
                        }
                        return !excluded;
                    })
                    .collect(Collectors.toList());

            // 输出所有通过过滤的股票及其行业（用于诊断）
            List<String> keptSamples = candidates.stream()
                    .limit(10)
                    .map(s -> {
                        StockInfo info = codeInfoMap.get(stripSuffix(s.getSymbol()));
                        String ind = info != null && info.getIndustry() != null ? info.getIndustry() : "无行业";
                        String name = info != null && info.getName() != null ? info.getName() : s.getName();
                        return name + "(" + ind + ")";
                    })
                    .collect(Collectors.toList());

            // 调试用：输出问题股票的实际 industry 值
            for (String debugCode : List.of("002033", "601000", "600258", "601008")) {
                StockInfo dinfo = codeInfoMap.get(debugCode);
                if (dinfo != null) {
                    log.info("[Recommendation] 调试-行业值: code={}, name={}, industry='{}'",
                            debugCode, dinfo.getName(), dinfo.getIndustry());
                }
            }

            log.info("[Recommendation] 行业排除过滤 [strategyId={}]: 排除关键词数={}, 过滤前={}, 过滤后={}",
                    strategyId, excludeSet.size(), before, candidates.size());
            log.info("[Recommendation] 被排除股票: {}", excludedStocks);
            log.info("[Recommendation] 通过过滤的股票样本(前10): {}", keptSamples);
            if (candidates.isEmpty()) {
                log.warn("[Recommendation] 行业排除后候选池为空，跳过生成");
                return List.of();
            }
        }

        // Step 1.7: 黑名单过滤（方案B - 个股级风控）
        // 在行业排除之后、市场环境识别之前执行
        if (strategyId != null) {
            Set<String> blacklistCodes = stockBlacklistService.getActiveBlacklistCodes(strategyId);
            if (!blacklistCodes.isEmpty()) {
                int beforeBl = candidates.size();
                List<String> filteredStocks = new ArrayList<>();
                candidates = candidates.stream()
                        .filter(s -> {
                            String pureCode = stripSuffix(s.getSymbol());
                            if (blacklistCodes.contains(pureCode)) {
                                filteredStocks.add(s.getName() + "(" + pureCode + ")");
                                return false;
                            }
                            return true;
                        })
                        .collect(Collectors.toList());

                log.info("[Recommendation] 黑名单过滤 [strategyId={}]: 黑名单股票数={}, 过滤前={}, 过滤后={}, 被过滤={}",
                        strategyId, blacklistCodes.size(), beforeBl, candidates.size(), filteredStocks);
                if (candidates.isEmpty()) {
                    log.warn("[Recommendation] 黑名单过滤后候选池为空，跳过生成（建议先清理黑名单）");
                }
            }
        }

        // 用选股实际日期作为推荐日期（date=null 时这是真实最新日期）
        LocalDate actualDate = screenResult.getScreenDate();
        log.info("[Recommendation] 因子选股完成: actualDate={}, 候选数={}", actualDate, candidates.size());

        // Step 2: 市场环境识别（用实际日期）
        RegimeInfo regime = detectRegime(actualDate);
        log.info("[Recommendation] 市场环境: regime={}, indexClose={}, MA20={}, MA60={}",
                regime.regime, regime.indexClose, regime.indexMa20, regime.indexMa60);

        // Step 2.5: 行业动量计算 (Phase A+C)
        Map<String, IndustryMomentum> industryMomentumMap = computeIndustryMomentum(regime, actualDate);

        // Step 2.6: 预查行业映射(避免 N+1 查询)
        Map<String, String> codeToIndustry = buildCodeToIndustryMap(candidates);
        log.info("[Recommendation] 行业映射: {}只候选股", codeToIndustry.size());

        // Step 3: 对 Top N 做个股深度分析
        int analysisCount = Math.min(topN, candidates.size());
        List<StockRecommendation> recommendations = new ArrayList<>();

        for (int i = 0; i < analysisCount; i++) {
            ScreenResult.StockScore stock = candidates.get(i);
            String pureCode = stripSuffix(stock.getSymbol());
            String industry = codeToIndustry.getOrDefault(pureCode, "UNKNOWN");
            IndustryMomentum im = industryMomentumMap.get(industry);
            try {
                StockRecommendation rec = analyzeAndFuse(stock, regime, actualDate, im, strategyId);
                recommendations.add(rec);
                log.info("[Recommendation] 分析进度: {}/{} code={} name={} factorScore={} analysisScore={} finalScore={} tech={} money={} senti={} fund={} risk={} liq={}",
                        i + 1, analysisCount, rec.getStockCode(), rec.getStockName(),
                        String.format("%.4f", rec.getFactorScore()),
                        rec.getAnalysisScore(),
                        String.format("%.4f", rec.getFinalScore()),
                        rec.getTechnicalScore(), rec.getCapitalScore(), rec.getEventScore(), rec.getFundamentalScore(),
                        rec.getRiskScore(), rec.getLiquidityScore());
            } catch (Exception e) {
                log.warn("[Recommendation] 个股分析失败: code={} error={}", stock.getSymbol(), e.getMessage());
            }
        }

        // Step 3.5: 批量填充 industry 和 marketCap（从 stock_info）
        fillIndustryAndMarketCap(recommendations);

        // Step 4: 排序 & 赋排名
        recommendations.sort((a, b) -> Double.compare(b.getFinalScore(), a.getFinalScore()));

        // Step 5: 行业分散化(动态限制, Phase A+C)
        recommendations = diversify(recommendations, industryMomentumMap);

        for (int i = 0; i < recommendations.size(); i++) {
            recommendations.get(i).setRankNum(i + 1);
        }

        // Step 6: 写入数据库（按 strategy_id + recommend_date 去重，先删旧写新）
        for (StockRecommendation rec : recommendations) {
            rec.setStrategyId(strategyId);
            rec.setRecommendDate(actualDate);
        }
        // 仅当指定了策略时才做清理（避免误删）
        if (strategyId != null) {
            recommendationMapper.deleteByStrategyAndDate(strategyId, actualDate);
        }
        for (StockRecommendation rec : recommendations) {
            try {
                recommendationMapper.insert(rec);
            } catch (Exception e) {
                log.warn("[Recommendation] 写入失败: code={} strategyId={} date={} error={}",
                        rec.getStockCode(), strategyId, actualDate, e.getMessage());
            }
        }

        log.info("[Recommendation] 推荐列表生成完成: strategyId={} date={} count={}", strategyId, actualDate, recommendations.size());
        return recommendations;
    }

    /**
     * 获取最新推荐列表
     */
    public List<StockRecommendation> getLatestRecommendations() {
        StockRecommendation latest = recommendationMapper.findLatest();
        if (latest == null) {
            return List.of();
        }
        List<StockRecommendation> recs = recommendationMapper.findByStrategyAndDate(
                latest.getStrategyId(), latest.getRecommendDate());
        return enrichFromStockInfo(recs);
    }

    /**
     * 获取指定策略+日期的推荐列表
     */
    public List<StockRecommendation> getRecommendationsByStrategyAndDate(Long strategyId, LocalDate recommendDate) {
        return enrichFromStockInfo(recommendationMapper.findByStrategyAndDate(strategyId, recommendDate));
    }

    /**
     * 读侧补充：从 stock_info 填充 industry/marketCap，并修复旧数据的 actionTag 和 buyReason
     * <p>
     * 因为生成时的 fillIndustryAndMarketCap 只在新批次生成时执行，
     * 旧批次读出来后需要同样处理才能保证前端展示正确。
     */
    private List<StockRecommendation> enrichFromStockInfo(List<StockRecommendation> recs) {
        if (recs == null || recs.isEmpty()) return recs;
        fillIndustryAndMarketCap(recs);
        for (StockRecommendation rec : recs) {
            // 修复旧数据的 actionTag（5值→3值）
            if (rec.getActionTag() != null) {
                rec.setActionTag(mapActionTag(rec.getActionTag()));
            }
            // 修复旧数据的 buyReason: 替换 [null(code)] → [name(code)]
            if (rec.getBuyReason() != null && rec.getBuyReason().contains("null(")) {
                String name = rec.getStockName() != null ? rec.getStockName() : rec.getStockCode();
                rec.setBuyReason(rec.getBuyReason().replace("null", name));
            }
            // 修复旧数据的 eventScore: 旧代码维度名写错未捕获 → 回算
            // 分析总分 = technical + capital + event + fundamental，反推即可
            if (rec.getEventScore() == null
                    && rec.getTechnicalScore() != null
                    && rec.getCapitalScore() != null
                    && rec.getFundamentalScore() != null
                    && rec.getAnalysisScore() != null) {
                int eventScore = rec.getAnalysisScore()
                        - rec.getTechnicalScore()
                        - rec.getCapitalScore()
                        - rec.getFundamentalScore();
                rec.setEventScore(Math.max(0, eventScore));
            }
            // 修复旧数据的因子权重: 旧批次 Phase 2 动态权重未实现 → 根据 regime 回填
            if (rec.getFactorWeight() == null && rec.getRegime() != null) {
                double wFactor, wAnalysis;
                switch (rec.getRegime()) {
                    case "BULL" -> {
                        wFactor = 0.6;
                        wAnalysis = 0.4;
                    }
                    case "BEAR" -> {
                        wFactor = 0.4;
                        wAnalysis = 0.6;
                    }
                    default -> {
                        wFactor = 0.5;
                        wAnalysis = 0.5;
                    }
                }
                rec.setFactorWeight(wFactor);
                rec.setAnalysisWeight(wAnalysis);
            }
        }
        return recs;
    }

    /**
     * 追踪推荐表现（Phase 3.2）
     * 对未追踪或需要更新的推荐批次，计算:
     * - 次日收益率
     * - 一周收益率
     * - 一月收益率
     *
     * @return 更新的记录数
     */
    public int trackRecommendationPerformance() {
        // 找到所有需要更新的策略+日期组合（最近5组未完全追踪的）
        List<Map<String, Object>> recentCombos = recommendationMapper.findRecentStrategyDates(5);
        int totalUpdated = 0;

        LocalDate today = LocalDate.now();

        for (Map<String, Object> combo : recentCombos) {
            Object sidObj = combo.get("strategy_id");
            if (sidObj == null) continue; // 跳过 strategy_id 为空的脏数据
            Long sid = ((Number) sidObj).longValue();
            java.sql.Date sqlDate = (java.sql.Date) combo.get("recommend_date");
            if (sqlDate == null) continue;
            LocalDate recDate = sqlDate.toLocalDate();

            List<StockRecommendation> recs = recommendationMapper.findByStrategyAndDate(sid, recDate);
            if (recs.isEmpty()) continue;

            int daysSince = (int) java.time.temporal.ChronoUnit.DAYS.between(recDate, today);
            if (daysSince <= 0) continue; // 推荐当天或未来，不追踪

            for (StockRecommendation rec : recs) {
                try {
                    boolean updated = false;

                    // 次日收益率（总是重新计算，覆盖旧值，以支持除权修正/数据修正）
                    if (daysSince >= 1) {
                        Double val = calcForwardReturn(rec.getStockCode(), recDate, 1);
                        if (val != null) {
                            rec.setNextDayReturn(val);
                            updated = true;
                        }
                    }

                    // 一周收益率
                    if (daysSince >= 5) {
                        Double val = calcForwardReturn(rec.getStockCode(), recDate, 5);
                        if (val != null) {
                            rec.setNextWeekReturn(val);
                            updated = true;
                        }
                    }

                    // 一月收益率
                    if (daysSince >= 22) {
                        Double val = calcForwardReturn(rec.getStockCode(), recDate, 22);
                        if (val != null) {
                            rec.setNextMonthReturn(val);
                            updated = true;
                        }
                    }

                    if (updated) {
                        rec.setTrackingUpdatedAt(java.time.LocalDateTime.now());
                        recommendationMapper.updateById(rec);
                        totalUpdated++;
                    }
                } catch (Exception e) {
                    log.warn("[Recommendation] 追踪失败: code={} strategyId={} date={} error={}",
                            rec.getStockCode(), sid, recDate, e.getMessage());
                }
            }
        }

        log.info("[Recommendation] 表现追踪完成: 更新{}条记录", totalUpdated);

        // 追踪完成后，自动评估并更新黑名单（方案B）
        for (Map<String, Object> combo : recentCombos) {
            Object sidObj = combo.get("strategy_id");
            Object dateObj = combo.get("recommend_date");
            if (sidObj == null || dateObj == null) continue;
            Long sid = ((Number) sidObj).longValue();
            LocalDate recDate = ((java.sql.Date) dateObj).toLocalDate();
            try {
                stockBlacklistService.evaluateAndBlacklist(sid, recDate);
            } catch (Exception e) {
                log.warn("[Recommendation] 黑名单自动评估异常: strategyId={} date={} error={}", sid, recDate, e.getMessage());
            }
        }

        // 追踪完成后，自动更新策略置信度（方案C）
        Set<Long> strategyIds = recentCombos.stream()
                .filter(c -> c.get("strategy_id") != null)
                .map(c -> ((Number) c.get("strategy_id")).longValue())
                .collect(java.util.stream.Collectors.toSet());
        for (Long sid : strategyIds) {
            try {
                strategyConfidenceService.calculateAndSave(sid);
            } catch (Exception e) {
                log.warn("[Recommendation] 置信度自动计算异常: strategyId={} error={}", sid, e.getMessage());
            }
        }

        return totalUpdated;
    }

    // ── 私有方法 ──

    /**
     * 获取推荐命中率统计
     *
     * @param strategyId    策略ID
     * @param recommendDate 推荐日期
     * @return { total, positive, hitRate, avgReturn }
     */
    public Map<String, Object> getHitRate(Long strategyId, LocalDate recommendDate) {
        List<StockRecommendation> recs = recommendationMapper.findByStrategyAndDate(strategyId, recommendDate);
        Map<String, Object> stats = new HashMap<>();
        stats.put("strategyId", strategyId);
        stats.put("recommendDate", recommendDate.toString());
        stats.put("total", recs.size());

        if (recs.isEmpty()) return stats;

        // 用 nextDayReturn 计算命中率（至少有次日数据的）
        long positive = 0;
        long tracked = 0;
        double sumReturn = 0;

        for (StockRecommendation rec : recs) {
            if (rec.getNextDayReturn() != null) {
                tracked++;
                if (rec.getNextDayReturn() > 0) positive++;
                sumReturn += rec.getNextDayReturn();
            }
        }

        stats.put("tracked", tracked);
        stats.put("positive", positive);
        stats.put("hitRate", tracked > 0 ? (double) positive / tracked : 0);
        stats.put("avgReturn", tracked > 0 ? sumReturn / tracked : 0);

        return stats;
    }

    /**
     * 获取最近的策略+日期组合列表
     */
    public List<Map<String, Object>> getStrategyDateCombos(int limit) {
        return recommendationMapper.findRecentStrategyDates(limit);
    }

    /**
     * 获取所有有推荐记录的策略列表（id + name）
     */
    public List<Map<String, Object>> strategiesWithData() {
        List<Long> ids = recommendationMapper.findDistinctStrategyIds();
        List<Map<String, Object>> result = new java.util.ArrayList<>(ids.size());
        for (Long sid : ids) {
            StrategyDefinition s = strategyDefinitionMapper.selectById(sid);
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", sid);
            m.put("strategyName", s != null ? s.getStrategyName() : "策略" + sid);
            result.add(m);
        }
        return result;
    }

    /**
     * 获取批次历史表现汇总（含质量标签，按策略隔离）
     * 用于前端表现追踪面板：命中趋势图 + 平均收益率统计
     *
     * @param limit      返回最近N条策略+日期组合
     * @param strategyId 可选，指定时只返回该策略的数据
     * @return [{ strategyId, recommendDate, total, hitRate, avgDayReturn, avgWeekReturn, avgMonthReturn, qualityTag, tracked }]
     */
    public List<Map<String, Object>> getBatchHistory(int limit, Long strategyId) {
        List<Map<String, Object>> rawCombos;
        if (strategyId != null) {
            // 按策略筛选：获取该策略的日期列表
            List<LocalDate> dates = recommendationMapper.findDatesByStrategyId(strategyId, limit);
            rawCombos = new ArrayList<>();
            for (LocalDate d : dates) {
                Map<String, Object> m = new HashMap<>();
                m.put("strategy_id", strategyId);
                m.put("recommend_date", java.sql.Date.valueOf(d));
                rawCombos.add(m);
            }
        } else {
            rawCombos = recommendationMapper.findRecentStrategyDates(limit);
        }

        // 先收集所有组合的 hitRate，用于滚动5期均值计算
        List<Double> hitRates = new ArrayList<>();
        List<Long> trackedCounts = new ArrayList<>();
        List<Map<String, Object>> rawEntries = new ArrayList<>();

        for (Map<String, Object> combo : rawCombos) {
            Object sidObj = combo.get("strategy_id");
            if (sidObj == null) {
                continue;
            }
            Long sid = ((Number) sidObj).longValue();
            java.sql.Date sqlDate = (java.sql.Date) combo.get("recommend_date");
            if (sqlDate == null) {
                continue;
            }
            LocalDate recDate = sqlDate.toLocalDate();

            Map<String, Object> stats = getHitRate(sid, recDate);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("strategyId", sid);
            entry.put("recommendDate", recDate.toString());
            entry.put("total", stats.get("total"));
            entry.put("tracked", stats.get("tracked"));

            Double hitRate = (Double) stats.get("hitRate");
            Double avgDayReturn = (Double) stats.get("avgReturn");
            entry.put("hitRate", hitRate);
            entry.put("avgDayReturn", avgDayReturn);

            // 计算一周/一月平均收益
            List<StockRecommendation> recs = recommendationMapper.findByStrategyAndDate(sid, recDate);
            double sumWeek = 0, sumMonth = 0;
            long weekTracked = 0, monthTracked = 0;
            for (StockRecommendation rec : recs) {
                if (rec.getNextWeekReturn() != null) {
                    sumWeek += rec.getNextWeekReturn();
                    weekTracked++;
                }
                if (rec.getNextMonthReturn() != null) {
                    sumMonth += rec.getNextMonthReturn();
                    monthTracked++;
                }
            }
            entry.put("avgWeekReturn", weekTracked > 0 ? sumWeek / weekTracked : null);
            entry.put("avgMonthReturn", monthTracked > 0 ? sumMonth / monthTracked : null);

            hitRates.add(hitRate);
            trackedCounts.add((Long) stats.get("tracked"));
            rawEntries.add(entry);
        }

        // 质量标签: 基于近5期滚动平均命中率判定
        // rawEntries 按日期 DESC 排序（最新在前）
        for (int i = 0; i < rawEntries.size(); i++) {
            Map<String, Object> entry = rawEntries.get(i);
            // 计算当前及之后4期（共5期）滚动均值
            double rollingSum = 0;
            long rollingTracked = 0;
            for (int j = i; j < rawEntries.size() && j <= i + 4; j++) {
                if (trackedCounts.get(j) > 0) {
                    rollingSum += hitRates.get(j) != null ? hitRates.get(j) : 0;
                    rollingTracked++;
                }
            }
            double rollingAvg = rollingTracked > 0 ? rollingSum / rollingTracked : 0;

            String qualityTag;
            if (rollingTracked == 0) {
                qualityTag = "UNTRAINED";
            } else if (rollingAvg >= 0.6) {
                qualityTag = "HIGH_QUALITY";
            } else if (rollingAvg >= 0.4) {
                qualityTag = "NORMAL";
            } else {
                qualityTag = "LOW_QUALITY";
            }
            entry.put("qualityTag", qualityTag);
            entry.put("rollingAvgHitRate", rollingTracked > 0 ? rollingAvg : null);
        }

        // 按日期 ASC 排序返回（图表从左到右时间递增）
        List<Map<String, Object>> result = new ArrayList<>(rawEntries);
        result.sort((a, b) -> ((String) a.get("recommendDate")).compareTo((String) b.get("recommendDate")));
        return result;
    }

    /**
     * 获取指定策略+日期的最佳/最差股票（用于推荐复盘）
     * 按次日收益率排序，分别取 top3 / bottom3
     * 含深度归因分析：行业分布对比、市值中位数对比、因子/分析得分对比
     *
     * @return { best3: [...], worst3: [...], analysis: { industryDiff, marketCapDiff, scoreDiff, failurePatterns } }
     */
    public Map<String, Object> getBatchTopBottom(Long strategyId, LocalDate recommendDate) {
        List<StockRecommendation> recs = recommendationMapper.findByStrategyAndDate(strategyId, recommendDate);
        Map<String, Object> result = new HashMap<>();

        // 只取有次日收益的
        List<StockRecommendation> tracked = recs.stream()
                .filter(r -> r.getNextDayReturn() != null)
                .collect(java.util.stream.Collectors.toList());

        // 最佳3只（次日收益最高）
        List<StockRecommendation> best3 = tracked.stream()
                .sorted(java.util.Comparator.comparingDouble(StockRecommendation::getNextDayReturn).reversed())
                .limit(3)
                .collect(java.util.stream.Collectors.toList());

        // 最差3只（次日收益最低）
        List<StockRecommendation> worst3 = tracked.stream()
                .sorted(java.util.Comparator.comparingDouble(StockRecommendation::getNextDayReturn))
                .limit(3)
                .collect(java.util.stream.Collectors.toList());

        result.put("best3", best3);
        result.put("worst3", worst3);

        // ── 深度归因分析 ──
        Map<String, Object> analysis = new LinkedHashMap<>();

        // 1) 行业分布对比
        Map<String, Object> industryDiff = new LinkedHashMap<>();
        Map<String, Long> bestIndustries = best3.stream()
                .filter(r -> r.getIndustry() != null)
                .collect(java.util.stream.Collectors.groupingBy(StockRecommendation::getIndustry, java.util.stream.Collectors.counting()));
        Map<String, Long> worstIndustries = worst3.stream()
                .filter(r -> r.getIndustry() != null)
                .collect(java.util.stream.Collectors.groupingBy(StockRecommendation::getIndustry, java.util.stream.Collectors.counting()));
        industryDiff.put("bestIndustries", bestIndustries);
        industryDiff.put("worstIndustries", worstIndustries);
        // 找出仅在 worst 中出现的行业（可能为弱势行业）
        Set<String> worstOnlyIndustries = new java.util.LinkedHashSet<>(worstIndustries.keySet());
        worstOnlyIndustries.removeAll(bestIndustries.keySet());
        industryDiff.put("worstOnlyIndustries", worstOnlyIndustries);
        analysis.put("industryDiff", industryDiff);

        // 2) 市值中位数对比
        Map<String, Object> marketCapDiff = new LinkedHashMap<>();
        Double bestMedianCap = median(best3.stream()
                .filter(r -> r.getMarketCap() != null)
                .map(StockRecommendation::getMarketCap)
                .collect(java.util.stream.Collectors.toList()));
        Double worstMedianCap = median(worst3.stream()
                .filter(r -> r.getMarketCap() != null)
                .map(StockRecommendation::getMarketCap)
                .collect(java.util.stream.Collectors.toList()));
        marketCapDiff.put("bestMedianCap", bestMedianCap);
        marketCapDiff.put("worstMedianCap", worstMedianCap);
        marketCapDiff.put("hint", bestMedianCap != null && worstMedianCap != null
                ? (bestMedianCap > worstMedianCap ? "大盘股表现优于小盘股" : "小盘股表现优于大盘股")
                : null);
        analysis.put("marketCapDiff", marketCapDiff);

        // 3) 因子得分 vs 分析得分对比
        Map<String, Object> scoreDiff = new LinkedHashMap<>();
        double bestAvgFactor = best3.stream()
                .filter(r -> r.getFactorScore() != null)
                .mapToDouble(StockRecommendation::getFactorScore).average().orElse(0);
        double worstAvgFactor = worst3.stream()
                .filter(r -> r.getFactorScore() != null)
                .mapToDouble(StockRecommendation::getFactorScore).average().orElse(0);
        double bestAvgAnalysis = best3.stream()
                .filter(r -> r.getAnalysisScorePct() != null)
                .mapToDouble(StockRecommendation::getAnalysisScorePct).average().orElse(0);
        double worstAvgAnalysis = worst3.stream()
                .filter(r -> r.getAnalysisScorePct() != null)
                .mapToDouble(StockRecommendation::getAnalysisScorePct).average().orElse(0);
        scoreDiff.put("bestAvgFactorScore", bestAvgFactor);
        scoreDiff.put("worstAvgFactorScore", worstAvgFactor);
        scoreDiff.put("bestAvgAnalysisPct", bestAvgAnalysis);
        scoreDiff.put("worstAvgAnalysisPct", worstAvgAnalysis);
        // 分析差距来源
        double factorGap = bestAvgFactor - worstAvgFactor;
        double analysisGap = bestAvgAnalysis - worstAvgAnalysis;
        if (Math.abs(factorGap) > Math.abs(analysisGap)) {
            scoreDiff.put("dominantGap", "FACTOR");
            scoreDiff.put("hint", factorGap > 0
                    ? "因子得分差距更大，因子筛选效果显著"
                    : "因子得分差距更大，但方向反转，需检查因子有效性");
        } else {
            scoreDiff.put("dominantGap", "ANALYSIS");
            scoreDiff.put("hint", analysisGap > 0
                    ? "分析得分差距更大，深度分析筛选效果好"
                    : "分析得分差距更大，但方向反转，需检查分析模型");
        }
        analysis.put("scoreDiff", scoreDiff);

        // 4) 失败模式识别
        List<String> failurePatterns = new java.util.ArrayList<>();
        if (!worst3.isEmpty()) {
            // 检查是否集中在弱势行业
            long weakMomentumCount = worst3.stream()
                    .filter(r -> r.getIndustryMomentum() != null && r.getIndustryMomentum() < -0.3)
                    .count();
            if (weakMomentumCount > 0) {
                failurePatterns.add(String.format("弱势行业占比 %d/%d（行业动量 < -0.3），行业环境拖累明显", weakMomentumCount, worst3.size()));
            }
            // 检查是否风险评分偏高
            double worstAvgRisk = worst3.stream()
                    .filter(r -> r.getRiskScore() != null)
                    .mapToInt(StockRecommendation::getRiskScore).average().orElse(0);
            double bestAvgRisk = best3.stream()
                    .filter(r -> r.getRiskScore() != null)
                    .mapToInt(StockRecommendation::getRiskScore).average().orElse(0);
            if (worstAvgRisk > bestAvgRisk + 2) {
                failurePatterns.add(String.format("最差组平均风险评分 %.1f 显著高于最佳组 %.1f，风险控制不足", worstAvgRisk, bestAvgRisk));
            }
            // 检查是否流动性评分偏低
            double worstAvgLiquidity = worst3.stream()
                    .filter(r -> r.getLiquidityScore() != null)
                    .mapToInt(StockRecommendation::getLiquidityScore).average().orElse(0);
            double bestAvgLiquidity = best3.stream()
                    .filter(r -> r.getLiquidityScore() != null)
                    .mapToInt(StockRecommendation::getLiquidityScore).average().orElse(0);
            if (worstAvgLiquidity < bestAvgLiquidity - 2) {
                failurePatterns.add(String.format("最差组平均流动性评分 %.1f 低于最佳组 %.1f，流动性风险较高", worstAvgLiquidity, bestAvgLiquidity));
            }
            // 检查同行业集中度
            if (worstIndustries.size() == 1 && worst3.size() >= 2) {
                failurePatterns.add(String.format("最差组全部来自「%s」行业，行业集中风险极高", worstIndustries.keySet().iterator().next()));
            }
            if (failurePatterns.isEmpty()) {
                failurePatterns.add("无明显共性失败模式，可能受个股特有事件或市场随机波动影响");
            }
        }
        analysis.put("failurePatterns", failurePatterns);

        result.put("analysis", analysis);
        return result;
    }

    /**
     * 计算中位数
     */
    private Double median(List<Double> values) {
        if (values.isEmpty()) return null;
        List<Double> sorted = new java.util.ArrayList<>(values);
        java.util.Collections.sort(sorted);
        int n = sorted.size();
        if (n % 2 == 1) return sorted.get(n / 2);
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    /**
     * 多维市场环境识别 (Phase 2)
     * 三个维度综合判断:
     * 1. 指数趋势: 沪深300 MA20/MA60 排列
     * 2. 波动率体制: ATR20 分位数 (高波动=Risk-off, 低波动=Risk-on)
     * 3. 市场宽度: 涨跌家数比 (扩散好=Risk-on, 极端分化=Risk-off)
     * 综合打分:
     * BULL:   trend=BULL 且 (波动率低 或 宽度好) → 动量/成长友好
     * BEAR:   trend=BEAR 且 (波动率高 或 宽度差) → 防御/价值优先
     * SIDEWAYS: 其他情况
     */
    private RegimeInfo detectRegime(LocalDate date) {
        LocalDate startDate = date.minusDays(Math.max(250, ATR_LOOKBACK_DAYS + 30));
        List<MarketDailyBar> bars = marketDataService.getBarsInRange(SSE300_CODE, startDate, date);
        RegimeInfo info = new RegimeInfo();

        if (bars == null || bars.size() < 60) {
            log.warn("[Recommendation] 沪深300数据不足({}条)，无法判断市场环境", bars != null ? bars.size() : 0);
            info.regime = "SIDEWAYS";
            return info;
        }

        List<Double> closes = bars.stream()
                .map(b -> b.getClose().doubleValue())
                .collect(Collectors.toList());

        // ── 维度1: 指数趋势 ──
        MarketDailyBar latestBar = bars.get(bars.size() - 1);
        info.indexClose = latestBar.getClose().doubleValue();
        info.indexChangePct = latestBar.getPctChg() != null ? latestBar.getPctChg().doubleValue() : null;
        double ma20 = avg(closes, 20);
        double ma60 = avg(closes, 60);
        info.indexMa20 = ma20;
        info.indexMa60 = ma60;

        // 引入0.5%缓冲带，避免单日噪声导致Regime频繁切换
        double trendBuffer = info.indexClose * 0.005;
        boolean bullishTrend = info.indexClose > ma20 + trendBuffer && ma20 > ma60 + trendBuffer;
        boolean bearishTrend = info.indexClose < ma20 - trendBuffer && ma20 < ma60 - trendBuffer;

        // ── 维度2: 波动率体制 (ATR20 分位数) ──
        List<Double> highs = bars.stream().map(b -> b.getHigh().doubleValue()).collect(Collectors.toList());
        List<Double> lows = bars.stream().map(b -> b.getLow().doubleValue()).collect(Collectors.toList());

        // 当前 ATR20
        double currentATR = calcATR(highs, lows, closes, ATR_PERIOD);
        info.atrValue = currentATR;

        // 历史 ATR20 序列（滚动计算）
        List<Double> atrHistory = new ArrayList<>();
        for (int i = 60; i <= closes.size() - ATR_PERIOD; i++) {
            atrHistory.add(calcATR(
                    highs.subList(0, i + ATR_PERIOD),
                    lows.subList(0, i + ATR_PERIOD),
                    closes.subList(0, i + ATR_PERIOD),
                    ATR_PERIOD));
        }
        if (!atrHistory.isEmpty()) {
            atrHistory.add(currentATR);
            double atrPercentile = calcPercentile(currentATR, atrHistory);
            info.atrPercentile = atrPercentile; // 0~1, 越高波动越大
            info.volatilityRegime = atrPercentile > 0.7 ? "HIGH" : atrPercentile < 0.3 ? "LOW" : "MEDIUM";
        }

        // ── 维度3: 市场宽度 ──
        try {
            Map<String, Object> overview = clickHouseStockService.getOverviewStats(date);
            if (overview != null) {
                long riseCount = toLong(overview.get("riseCount"));
                long fallCount = toLong(overview.get("fallCount"));
                long totalCount = riseCount + fallCount + toLong(overview.get("flatCount"));
                info.riseCount = riseCount;
                info.fallCount = fallCount;
                if (totalCount > 0) {
                    info.breadthRatio = (double) riseCount / totalCount; // 0~1
                    // 宽度判断: 涨家>60%=好, <40%=差
                    info.breadthQuality = info.breadthRatio > 0.6 ? "GOOD"
                            : info.breadthRatio < 0.4 ? "POOR" : "NEUTRAL";
                }
            }
        } catch (Exception e) {
            log.warn("[Recommendation] 市场宽度获取失败: {}", e.getMessage());
        }

        // ── 综合判断 ──
        if (bullishTrend) {
            // 牛市趋势中，高波动或差宽度降级为 SIDEWAYS
            boolean confirmed = "LOW".equals(info.volatilityRegime) || "GOOD".equals(info.breadthQuality);
            info.regime = confirmed ? "BULL" : "SIDEWAYS";
        } else if (bearishTrend) {
            // 熊市趋势中，高波动或差宽度确认 BEAR
            boolean confirmed = "HIGH".equals(info.volatilityRegime) || "POOR".equals(info.breadthQuality);
            info.regime = confirmed ? "BEAR" : "SIDEWAYS";
        } else {
            info.regime = "SIDEWAYS";
        }

        // ── 维度4: 大小盘风格 (P1-1) ──
        // 用沪深300(大盘) vs 中证1000(小盘) 近20日涨幅差判断
        String zz1000Code = "000852"; // 中证1000
        List<MarketDailyBar> zz1000Bars = marketDataService.getBarsInRange(zz1000Code,
                date.minusDays(30), date);
        if (zz1000Bars != null && zz1000Bars.size() >= 20) {
            double zz1000Return = calcRecentReturn(zz1000Bars, 20);
            double hs300Return = calcRecentReturn(bars, 20);
            info.sizeSpread = hs300Return - zz1000Return;
            info.sizeRegime = info.sizeSpread > 0.02 ? "LARGE"
                    : info.sizeSpread < -0.02 ? "SMALL" : "NEUTRAL";
        }

        // ── 维度5: 价值/成长风格 (P1-1) ──
        // 用国证价值(399371) vs 国证成长(399370) 近20日涨幅差判断
        String valueIdx = "399371", growthIdx = "399370";
        List<MarketDailyBar> valueBars = marketDataService.getBarsInRange(valueIdx,
                date.minusDays(30), date);
        List<MarketDailyBar> growthBars = marketDataService.getBarsInRange(growthIdx,
                date.minusDays(30), date);
        if (valueBars != null && growthBars != null
                && valueBars.size() >= 20 && growthBars.size() >= 20) {
            double valueReturn = calcRecentReturn(valueBars, 20);
            double growthReturn = calcRecentReturn(growthBars, 20);
            info.valueGrowthSpread = valueReturn - growthReturn;
            info.styleRegime = info.valueGrowthSpread > 0.02 ? "VALUE"
                    : info.valueGrowthSpread < -0.02 ? "GROWTH" : "NEUTRAL";
        }

        // ── 维度6: 利率/流动性环境 (P2-2) ──
        try {
            List<Double> bondYields = loadBondYield10y(date, 25);
            if (bondYields != null && bondYields.size() >= 20) {
                double currentYield = bondYields.get(bondYields.size() - 1);
                double yieldMa20 = bondYields.stream()
                        .mapToDouble(Double::doubleValue)
                        .limit(Math.max(1, bondYields.size() - 1))
                        .average().orElse(currentYield);
                info.bondYield10y = currentYield;
                info.bondYieldMa20 = yieldMa20;
                // 利率趋势：当前值 vs MA20
                double yieldDiff = currentYield - yieldMa20;
                info.rateRegime = yieldDiff > 0.05 ? "UP"
                        : yieldDiff < -0.05 ? "DOWN" : "NEUTRAL";
            }
            // 利差（10年-2年）
            Double spread = loadYieldCurveSpread(date);
            if (spread != null) {
                info.yieldCurveSpread = spread;
            }
        } catch (Exception e) {
            log.warn("[Recommendation] 利率环境检测失败: {}", e.getMessage());
        }

        log.info("[Recommendation] Regime详情: regime={} trend={} vol={}({}%) breadth={}({}%) ATR={} style={} size={} rate={}",
                info.regime,
                bullishTrend ? "BULL_TREND" : bearishTrend ? "BEAR_TREND" : "MIXED",
                info.volatilityRegime, info.atrPercentile != null ? info.atrPercentile * 100 : 0,
                info.breadthQuality, info.breadthRatio != null ? info.breadthRatio * 100 : 0,
                info.atrValue,
                info.styleRegime, info.sizeRegime, info.rateRegime);

        return info;
    }

    /**
     * 计算最近N日的收益率
     */
    private double calcRecentReturn(List<MarketDailyBar> bars, int days) {
        if (bars.size() < days + 1) return 0;
        double latest = bars.get(bars.size() - 1).getClose().doubleValue();
        double past = bars.get(bars.size() - 1 - days).getClose().doubleValue();
        return past > 0 ? (latest - past) / past : 0;
    }

    /**
     * 加载10年期国债收益率历史序列（P2-2）
     */
    private List<Double> loadBondYield10y(LocalDate date, int days) {
        try {
            LocalDate startDate = date.minusDays(days + 10);
            List<Double> yields = new ArrayList<>();
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT yield_10y FROM macro_bond_yield WHERE trade_date <= ? AND trade_date >= ? ORDER BY trade_date")) {
                ps.setString(1, date.toString());
                ps.setString(2, startDate.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        double y = rs.getDouble("yield_10y");
                        if (y > 0) yields.add(y);
                    }
                }
            }
            return yields;
        } catch (Exception e) {
            log.debug("[Recommendation] 加载国债收益率失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 加载最新利差（10年-2年）（P2-2）
     */
    private Double loadYieldCurveSpread(LocalDate date) {
        try {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT yield_spread FROM macro_bond_yield WHERE trade_date <= ? ORDER BY trade_date DESC LIMIT 1")) {
                ps.setString(1, date.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        double spread = rs.getDouble("yield_spread");
                        return spread != 0 ? spread : null;
                    }
                }
            }
            return null;
        } catch (Exception e) {
            log.debug("[Recommendation] 加载利差失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 计算 ATR (Average True Range)
     */
    private double calcATR(List<Double> highs, List<Double> lows, List<Double> closes, int period) {
        int size = closes.size();
        if (size < period + 1) return 0;

        double atr = 0;
        // 初始值用第一个真实波幅
        double prevClose = closes.get(size - period - 1);
        double tr0 = Math.max(
                Math.abs(highs.get(size - period) - lows.get(size - period)),
                Math.max(
                        Math.abs(highs.get(size - period) - prevClose),
                        Math.abs(lows.get(size - period) - prevClose)));
        atr = tr0;

        for (int i = size - period + 1; i < size; i++) {
            double prevC = closes.get(i - 1);
            double tr = Math.max(
                    Math.abs(highs.get(i) - lows.get(i)),
                    Math.max(
                            Math.abs(highs.get(i) - prevC),
                            Math.abs(lows.get(i) - prevC)));
            atr = (atr * (period - 1) + tr) / period; // EMA 平滑
        }
        return atr;
    }

    /**
     * 计算当前值在历史序列中的分位数
     *
     * @return 0~1, 越大说明当前值相对历史越高
     */
    private double calcPercentile(double value, List<Double> history) {
        if (history == null || history.isEmpty()) return 0.5;
        long countBelow = history.stream().filter(v -> v < value).count();
        return (double) countBelow / history.size();
    }

    /**
     * Regime-Adaptive 动态权重融合 (Phase 2, Phase C 升级)
     * <p>
     * 不同市场环境下，因子得分和分析得分的权重不同:
     * - BULL:   因子0.6 + 分析0.4 (动量因子在牛市更有效)
     * - BEAR:   因子0.4 + 分析0.6 (个股基本面在熊市更抗跌)
     * - SIDEWAYS: 因子0.5 + 分析0.5 (均衡)
     * <p>
     * Phase C 升级: 叠加行业轮动信号加分/扣分(±0.06)
     *
     * @param im 行业动量, 可为 null(无行业轮动信号时跳过)
     */
    private double fuseScore(StockRecommendation rec, RegimeInfo regime, IndustryMomentum im) {
        double factorPart = rec.getFactorScore() != null ? rec.getFactorScore() : 0.0;
        // 尺度校验：factorScore应已归一化到0~1，若异常则截断，确保融合公平
        factorPart = Math.max(0.0, Math.min(1.0, factorPart));

        // 分析得分各维度归一化后加权
        double techPct = safeDiv(rec.getTechnicalScore(), 30.0);    // 技术面满分30
        double moneyPct = safeDiv(rec.getCapitalScore(), 25.0);    // 资金面满分25
        double eventPct = safeDiv(rec.getEventScore(), 25.0);      // 事件面满分25
        double fundPct = safeDiv(rec.getFundamentalScore(), 29.0); // 基本面满分29

        // P1-2: 风险和流动性评分归一化
        double riskPct = safeDiv(rec.getRiskScore(), 15.0);       // 风险满分15
        double liqPct = safeDiv(rec.getLiquidityScore(), 10.0);   // 流动性满分10

        // Regime-Adaptive 总权重
        double wFactor, wAnalysis;
        switch (regime.regime) {
            case "BULL" -> {
                wFactor = 0.6;
                wAnalysis = 0.4;
            }
            case "BEAR" -> {
                wFactor = 0.4;
                wAnalysis = 0.6;
            }
            default -> {
                wFactor = 0.5;
                wAnalysis = 0.5;
            }
        }

        // P1-1: 小盘风格占优时，微调因子得分权重
        if ("SMALL".equals(regime.sizeRegime)) {
            wFactor = Math.min(0.7, wFactor + 0.05);
            wAnalysis = 1.0 - wFactor;
        }

        // P1-2: 融合风险和流动性评分到最终得分
        // 分析总分从 109 分制调整为 134 分制（+风险15+流动性10）
        double adjustedAnalysisPart;
        switch (regime.regime) {
            case "BULL" -> adjustedAnalysisPart = 0.30 * techPct + 0.25 * moneyPct
                    + 0.10 * eventPct + 0.15 * fundPct
                    + 0.10 * riskPct + 0.10 * liqPct;
            case "BEAR" -> adjustedAnalysisPart = 0.15 * techPct + 0.10 * moneyPct
                    + 0.10 * eventPct + 0.35 * fundPct
                    + 0.20 * riskPct + 0.10 * liqPct;
            default -> adjustedAnalysisPart = 0.25 * techPct + 0.20 * moneyPct
                    + 0.10 * eventPct + 0.20 * fundPct
                    + 0.15 * riskPct + 0.10 * liqPct;
        }

        // P2-2: 利率环境影响权重
        if ("DOWN".equals(regime.rateRegime)) {
            // 利率下行 → 成长风格友好 → 提高技术面/资金面权重
            adjustedAnalysisPart = adjustedAnalysisPart * 0.92 + techPct * 0.04 + moneyPct * 0.04;
        } else if ("UP".equals(regime.rateRegime)) {
            // 利率上行 → 价值风格友好 → 提高基本面权重，提高风险权重
            adjustedAnalysisPart = adjustedAnalysisPart * 0.92 + fundPct * 0.05 + riskPct * 0.03;
        }

        double finalScore = wFactor * factorPart + wAnalysis * adjustedAnalysisPart;

        // Phase C: 行业轮动信号加分/扣分
        if (im != null) {
            // P2-1: 动量增强 - fusionBonus 结合动量趋势调整
            double bonus = im.fusionBonus;
            if ("ACCELERATING".equals(im.momentumTrend)) {
                bonus *= 1.5; // 动量加速时，行业信号加成放大
            } else if ("DECELERATING".equals(im.momentumTrend)) {
                bonus *= 0.5; // 动量减速时，行业信号加成缩小
            }
            finalScore += bonus;
            rec.setIndustryMomentum(im.relativeStrength);
        }

        rec.setFactorWeight(wFactor);
        rec.setAnalysisWeight(wAnalysis);
        return Math.round(finalScore * 10000.0) / 10000.0;
    }

    /**
     * 批量填充 industry 和 marketCap（从 stock_info 表）
     * stockCode 格式: "600027.SH" → 去后缀查 stock_info.code = "600027"
     */
    private void fillIndustryAndMarketCap(List<StockRecommendation> recs) {
        Set<String> pureCodes = recs.stream()
                .map(r -> stripSuffix(r.getStockCode()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (pureCodes.isEmpty()) return;

        // 批量查 stock_info（IN 查询，一次性）
        List<StockInfo> infos = stockInfoMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StockInfo>()
                        .in(StockInfo::getCode, pureCodes));

        Map<String, StockInfo> infoMap = infos.stream()
                .collect(Collectors.toMap(StockInfo::getCode, i -> i, (a, b) -> a));

        for (StockRecommendation rec : recs) {
            String pureCode = stripSuffix(rec.getStockCode());
            StockInfo info = pureCode != null ? infoMap.get(pureCode) : null;
            if (info != null) {
                rec.setIndustry(info.getIndustry());
                if (info.getTotalMarketCap() != null) {
                    rec.setMarketCap(info.getTotalMarketCap().doubleValue());
                }
            }
        }
    }

    /**
     * 根据行业名查找所属相关组
     */
    private String getCorrGroup(String industry) {
        for (List<String> group : INDUSTRY_CORR_GROUPS) {
            if (group.contains(industry)) return group.get(0); // 用组内第一个行业作为组key
        }
        return industry; // 不在任何组中，独立计算
    }

    /**
     * 行业分散化 (Phase 2.4, Phase A+C 升级 + P1-3)
     * <p>
     * 对排序后的推荐列表做行业去重:
     * 1. 根据行业动量动态调整同类上限(强势行业放宽,弱势行业收紧)
     * 2. 引入行业相关性分组，高相关行业共享分散化名额
     * 3. 超出部分延后处理（保留但降权标记）
     * 4. 重新排名
     *
     * @param industryMomentumMap 行业动量映射(用于动态上限)
     */
    private List<StockRecommendation> diversify(List<StockRecommendation> recommendations,
                                                Map<String, IndustryMomentum> industryMomentumMap) {
        Map<String, Integer> groupCount = new LinkedHashMap<>();  // P1-3: 按相关组计数
        List<StockRecommendation> diversified = new ArrayList<>();
        List<StockRecommendation> excess = new ArrayList<>();

        for (StockRecommendation rec : recommendations) {
            String industry = rec.getIndustry() != null ? rec.getIndustry() : "UNKNOWN";
            String group = getCorrGroup(industry);  // P1-3: 获取所属相关组
            rec.setCorrGroup(group);  // 瞬态字段，供前端展示
            int count = groupCount.getOrDefault(group, 0);

            // 动态上限: 优先使用行业动量中的限制, 回退到默认3
            int limit = MAX_SAME_INDUSTRY;
            if (industryMomentumMap != null) {
                IndustryMomentum im = industryMomentumMap.get(industry);
                if (im != null) {
                    limit = im.industryDiversifyLimit;
                }
            }

            if (count < limit) {
                diversified.add(rec);
                groupCount.put(group, count + 1);  // P1-3: 按组计数
            } else {
                rec.setDiversificationDemoted(true);  // 标记降权
                excess.add(rec);
            }
        }

        // 超额股票追加到末尾
        diversified.addAll(excess);

        // 重新排名
        for (int i = 0; i < diversified.size(); i++) {
            diversified.get(i).setRankNum(i + 1);
        }

        int removed = excess.size();
        if (removed > 0) {
            log.info("[Recommendation] 行业分散化(动态+相关性分组): 移动{}只超额股票到末尾", removed);
            // 打印各组限制
            Map<String, Integer> finalCnt = new LinkedHashMap<>();
            for (StockRecommendation r : diversified) {
                String ind = r.getIndustry() != null ? r.getIndustry() : "UNKNOWN";
                String group = getCorrGroup(ind);
                finalCnt.merge(group, 1, Integer::sum);
            }
            finalCnt.forEach((grp, cnt) -> {
                // 找到该组的代表行业
                String repIndustry = grp;
                for (List<String> g : INDUSTRY_CORR_GROUPS) {
                    if (g.getFirst().equals(grp)) {
                        repIndustry = String.join(",", g);
                        break;
                    }
                }
                IndustryMomentum im = industryMomentumMap != null ? industryMomentumMap.get(grp) : null;
                int limit = im != null ? im.industryDiversifyLimit : MAX_SAME_INDUSTRY;
                log.info("  组[{}]: 入选{}只, 上限={}, 代表行业={}",
                        grp, cnt, limit, repIndustry);
            });
        }

        return diversified;
    }

    /**
     * 计算行业动量 (Phase A+C)
     * <p>
     * 复用 AnalysisService.getSectorRanking() 的行业涨跌幅数据,
     * 结合沪深300涨跌幅计算相对强度, 用于:
     * 方案A: 动态行业分散化限制
     * 方案C: 因子融合加分
     *
     * @param regime 市场环境(含沪深300涨跌幅)
     * @return 行业 → IndustryMomentum 映射
     */
    @SuppressWarnings("unchecked")
    private Map<String, IndustryMomentum> computeIndustryMomentum(RegimeInfo regime, LocalDate date) {
        Map<String, IndustryMomentum> result = new LinkedHashMap<>();
        try {
            // ⚠️ 统一使用 MySQL stock_info 作为行业数据源（与 buildCodeToIndustryMap 保持一致）
            // 避免 CH stock_info 与 MySQL stock_info 行业名称不一致导致匹配失败
            // 优先使用指定日期，若为 null 则取最新交易日
            String targetDate;
            if (date != null) {
                targetDate = date.toString();
            } else {
                targetDate = clickHouseStockService.queryForString(
                        "SELECT MAX(trade_date) FROM stock.stock_daily FINAL");
            }
            log.info("[Recommendation] 行业动量: 使用日期={}", targetDate);
            if (targetDate == null || targetDate.isEmpty()) {
                log.warn("[Recommendation] 无法获取交易日，跳过行业动量计算");
                return result;
            }

            // Step 1: 从 ClickHouse 获取当日所有股票的涨跌幅
            // P2-1: 同时获取近20日涨跌幅用于行业动量计算
            LocalDate lookbackStart = date.minusDays(25);
            String sql = String.format("""
                    SELECT code, change_percent, trade_date
                    FROM stock.stock_daily FINAL
                    WHERE trade_date >= '%s' AND trade_date <= '%s'
                    """, lookbackStart.toString(), targetDate);
            List<Map<String, Object>> rows = clickHouseStockService.queryForList(sql);
            log.info("[Recommendation] 行业动量: CH stock_daily 返回 {} 行(含20日回溯)", rows != null ? rows.size() : -1);
            if (rows == null || rows.isEmpty()) {
                log.warn("[Recommendation] 行业排行数据为空");
                return result;
            }

            // Step 2: 从 MySQL 获取全量股票行业映射（与 buildCodeToIndustryMap 同源）
            List<StockInfo> allStockInfos = stockInfoMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StockInfo>()
                            .isNotNull(StockInfo::getIndustry)
                            .ne(StockInfo::getIndustry, ""));
            Map<String, String> codeToIndustry = allStockInfos.stream()
                    .filter(i -> i.getCode() != null && i.getIndustry() != null)
                    .collect(Collectors.toMap(StockInfo::getCode, StockInfo::getIndustry, (a, b) -> a));
            log.info("[Recommendation] 行业动量: MySQL stock_info 返回 {} 条行业映射", codeToIndustry.size());

            // Step 3: 按行业汇总涨跌幅（分离当日/近5日数据，解决单日噪声）
            Map<String, List<Double>> industryDailyChanges = new LinkedHashMap<>();  // 目标日期
            Map<String, List<Double>> industryRecentChanges = new LinkedHashMap<>(); // 近5日（平滑排名）

            for (Map<String, Object> row : rows) {
                String code = (String) row.get("code");
                Object chgObj = row.get("change_percent");
                Object tdObj = row.get("trade_date");
                if (code == null || chgObj == null || tdObj == null) continue;
                String industry = codeToIndustry.get(code);
                if (industry == null) continue;
                double chg = chgObj instanceof Number ? ((Number) chgObj).doubleValue() : 0;
                String td = tdObj.toString();

                // 近5日数据用于平滑行业排名（避免单日极端值导致排名跳变）
                try {
                    long daysDiff = java.time.temporal.ChronoUnit.DAYS.between(
                            LocalDate.parse(td), LocalDate.parse(targetDate));
                    if (daysDiff >= 0 && daysDiff <= 4) {
                        industryRecentChanges.computeIfAbsent(industry, k -> new ArrayList<>()).add(chg);
                    }
                } catch (Exception ignored) {
                }

                // 仅目标日期用于精确当日数据
                if (td.equals(targetDate)) {
                    industryDailyChanges.computeIfAbsent(industry, k -> new ArrayList<>()).add(chg);
                }
            }

            if (industryRecentChanges.isEmpty()) {
                log.warn("[Recommendation] 行业涨跌幅汇总为空");
                return result;
            }

            // Step 4: 计算各行业平均涨跌幅（使用近5日平滑，避免单日噪声导致排名跳变）
            List<Double> allChangePcts = new ArrayList<>();
            List<Map<String, Object>> industryList = new ArrayList<>();
            for (Map.Entry<String, List<Double>> entry : industryRecentChanges.entrySet()) {
                String industry = entry.getKey();
                List<Double> changes = entry.getValue();
                if (changes.isEmpty()) continue;
                double avgChg = changes.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("industry", industry);
                m.put("avgChangePct", avgChg);
                m.put("sampleCount", changes.size());
                allChangePcts.add(avgChg);
                industryList.add(m);
            }

            if (allChangePcts.isEmpty()) {
                log.warn("[Recommendation] 行业涨跌幅全部为空");
                return result;
            }

            // 打印前 3 个行业用于调试
            for (int i = 0; i < Math.min(3, industryList.size()); i++) {
                Map<String, Object> m = industryList.get(i);
                log.info("[Recommendation]   raw[{}] = {} avgChangePct={} sampleCount={}",
                        i, m.get("industry"), m.get("avgChangePct"), m.get("sampleCount"));
            }

            // 计算 z-score
            double mean = allChangePcts.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double variance = allChangePcts.stream()
                    .mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(1.0);
            double std = Math.sqrt(variance);
            if (std < 0.001) std = 0.5;

            double indexPct = regime.indexChangePct != null ? regime.indexChangePct : 0;

            for (Map<String, Object> m : industryList) {
                String industry = (String) m.get("industry");
                if (industry == null || industry.isEmpty()) continue;

                double avgChg = m.get("avgChangePct") instanceof Number
                        ? ((Number) m.get("avgChangePct")).doubleValue() : 0;
                double zScore = (avgChg - mean) / std;
                double marketRelStrength = avgChg - indexPct;

                IndustryMomentum im = new IndustryMomentum();
                im.industry = industry;
                im.avgChangePct = avgChg;
                im.relativeStrength = Math.max(-3.0, Math.min(3.0, zScore));

                // 方案A: 动态行业分散化上限
                if (zScore > 0.6) im.industryDiversifyLimit = 6;
                else if (zScore > 0.3) im.industryDiversifyLimit = 4;
                else if (zScore > -0.3) im.industryDiversifyLimit = 3;
                else if (zScore > -0.6) im.industryDiversifyLimit = 2;
                else im.industryDiversifyLimit = 1;

                // 方案C: 因子融合加分
                if (marketRelStrength > 0.5) im.fusionBonus = 0.06;
                else if (marketRelStrength > 0.2) im.fusionBonus = 0.03;
                else if (marketRelStrength > -0.2) im.fusionBonus = 0.0;
                else if (marketRelStrength > -0.5) im.fusionBonus = -0.03;
                else im.fusionBonus = -0.06;

                // Phase A: industry-level Regime
                im.industryRegime = detectIndustryRegime(industry, date, im);
                if ("BULL".equals(im.industryRegime)) {
                    im.industryDiversifyLimit = Math.min(6, im.industryDiversifyLimit + 1);
                } else if ("BEAR".equals(im.industryRegime)) {
                    im.industryDiversifyLimit = Math.max(1, im.industryDiversifyLimit - 1);
                }

                result.put(industry, im);
            }

            log.info("[Recommendation] 行业动量计算完成: {}个行业, 指数涨跌={}%, 均值={}%, 标准差={}%",
                    result.size(), String.format("%.2f", indexPct),
                    String.format("%.2f", mean), String.format("%.2f", std));

            // Top/Bottom 5
            List<IndustryMomentum> sorted = new ArrayList<>(result.values());
            sorted.sort((a, b) -> Double.compare(b.relativeStrength, a.relativeStrength));
            StringBuilder sb = new StringBuilder("强势行业: ");
            for (int i = 0; i < Math.min(5, sorted.size()); i++) {
                IndustryMomentum im = sorted.get(i);
                sb.append(String.format("%s=%.2f%%(limit=%d) ", im.industry, im.avgChangePct, im.industryDiversifyLimit));
            }
            sb.append("| 弱势行业: ");
            for (int i = Math.max(0, sorted.size() - 5); i < sorted.size(); i++) {
                IndustryMomentum im = sorted.get(i);
                sb.append(String.format("%s=%.2f%%(limit=%d) ", im.industry, im.avgChangePct, im.industryDiversifyLimit));
            }
            log.info("[Recommendation] {}", sb.toString());

            // ── P2-1: 行业20日动量增强 ──
            // 用已获取的20日数据计算每个行业的累计动量和动量趋势
            Map<String, List<Double>> industryDailyAvg = new LinkedHashMap<>();
            Map<String, Object> dateObj2 = rows.stream().findFirst().orElse(null);
            if (dateObj2 != null && dateObj2.containsKey("trade_date")) {
                // 按日期×行业汇总平均涨跌幅
                Map<String, Map<String, List<Double>>> dateIndustryChanges = new LinkedHashMap<>();
                for (Map<String, Object> row : rows) {
                    String code = (String) row.get("code");
                    Object chgObj = row.get("change_percent");
                    Object tdObj = row.get("trade_date");
                    if (code == null || chgObj == null || tdObj == null) continue;
                    String industry = codeToIndustry.get(code);
                    if (industry == null) continue;
                    String td = tdObj.toString();
                    double chg = chgObj instanceof Number ? ((Number) chgObj).doubleValue() : 0;
                    dateIndustryChanges
                            .computeIfAbsent(td, k -> new LinkedHashMap<>())
                            .computeIfAbsent(industry, k -> new ArrayList<>())
                            .add(chg);
                }
                // 计算每个行业每天的均值
                for (Map.Entry<String, Map<String, List<Double>>> dateEntry : dateIndustryChanges.entrySet()) {
                    for (Map.Entry<String, List<Double>> indEntry : dateEntry.getValue().entrySet()) {
                        double dailyAvg = indEntry.getValue().stream().mapToDouble(Double::doubleValue).average().orElse(0);
                        industryDailyAvg
                                .computeIfAbsent(indEntry.getKey(), k -> new ArrayList<>())
                                .add(dailyAvg);
                    }
                }
            }

            // 计算动量评分和趋势
            for (Map.Entry<String, IndustryMomentum> entry : result.entrySet()) {
                String industry = entry.getKey();
                IndustryMomentum im = entry.getValue();
                List<Double> dailyAvgs = industryDailyAvg.get(industry);

                if (dailyAvgs != null && dailyAvgs.size() >= 5) {
                    // 20日动量：累计涨跌幅
                    double cumReturn = 1.0;
                    for (double d : dailyAvgs) {
                        cumReturn *= (1 + d / 100.0);
                    }
                    im.momentum20d = (cumReturn - 1.0) * 100.0;

                    // 动量趋势：比较前10日和后10日
                    int half = dailyAvgs.size() / 2;
                    double firstHalf = dailyAvgs.subList(0, half).stream().mapToDouble(Double::doubleValue).average().orElse(0);
                    double secondHalf = dailyAvgs.subList(half, dailyAvgs.size()).stream().mapToDouble(Double::doubleValue).average().orElse(0);
                    double diff = secondHalf - firstHalf;
                    im.momentumTrend = diff > 0.1 ? "ACCELERATING"
                            : diff < -0.1 ? "DECELERATING" : "FLAT";

                    // 动量综合评分（0~1）：结合当日z-score和20日动量
                    double zScoreNorm = (im.relativeStrength + 3.0) / 6.0; // 归一化到0~1
                    double momentumNorm = Math.max(0, Math.min(1, (im.momentum20d + 10) / 20.0)); // 归一化
                    im.momentumScore = 0.4 * zScoreNorm + 0.6 * momentumNorm;
                } else {
                    im.momentum20d = im.avgChangePct;
                    im.momentumTrend = "FLAT";
                    im.momentumScore = (im.relativeStrength + 3.0) / 6.0;
                }
            }

            // P2-1后：使用momentumScore重新校准fusionBonus（解决尺度不匹配，基于20日综合动量而非单日相对强度）
            for (IndustryMomentum im : result.values()) {
                if (im.momentumScore > 0.7) im.fusionBonus = 0.06;
                else if (im.momentumScore > 0.55) im.fusionBonus = 0.03;
                else if (im.momentumScore > 0.45) im.fusionBonus = 0.0;
                else if (im.momentumScore > 0.3) im.fusionBonus = -0.03;
                else im.fusionBonus = -0.06;
            }
            log.info("[Recommendation] P2-1 行业动量增强完成，fusionBonus已按momentumScore校准");

        } catch (Exception e) {
            log.error("[Recommendation] 行业动量计算异常: {}", e.getMessage(), e);
        }
        return result;
    }

    /**
     * 检测单个行业的 Regime（三维：趋势 + ATR波动率 + 简化宽度）
     * <p>
     * 使用申万一级行业指数 K 线数据（index_daily 表），计算与市场级 detectRegime()
     * 相同三个维度的行业市场环境：
     * 1. 趋势：行业指数 close > MA20 > MA60 → 牛市；close < MA20 < MA60 → 熊市
     * 2. 波动率: ATR(20) / close 历史分位数 → HIGH/MEDIUM/LOW
     * 3. 行业宽度（简化）：行业内上涨股票占比 > 60% = GOOD, < 40% = POOR
     *
     * @param industryName 行业名（stock_info.industry 值）
     * @param date         评估日期
     * @param im           行业动量数据（含 avgChangePct 等信息）
     * @return Regime 字符串: BULL / BEAR / SIDEWAYS
     */
    private String detectIndustryRegime(String industryName, LocalDate date, IndustryMomentum im) {
        // 1. 查找申万代码（优先直接匹配；二级行业通过 SW2_TO_SW1 归约到一级）
        String swCode = INDUSTRY_TO_SW_CODE.get(industryName);
        if (swCode == null) {
            // 二级行业 → 归约到一级
            String sw1 = SW2_TO_SW1.get(industryName);
            if (sw1 != null) {
                swCode = INDUSTRY_TO_SW_CODE.get(sw1);
            }
        }
        if (swCode == null) {
            log.debug("[Recommendation] 行业[{}]无申万代码映射，默认 SIDEWAYS", industryName);
            return "SIDEWAYS";
        }

        // 2. 获取行业指数 K 线（最近 250 天）
        LocalDate startDate = date.minusDays(250);
        try {
            List<StockDaily> bars = clickHouseStockService.getIndexDaily(swCode, startDate, date);
            if (bars == null || bars.size() < 60) {
                log.debug("[Recommendation] 行业[{}]({}) 数据不足({}条)，默认 SIDEWAYS",
                        industryName, swCode, bars != null ? bars.size() : 0);
                return "SIDEWAYS";
            }

            // 提取 close / high / low 序列
            List<Double> closes = bars.stream()
                    .map(b -> b.getClosePrice().doubleValue())
                    .collect(Collectors.toList());
            List<Double> highs = bars.stream()
                    .map(b -> b.getHighPrice().doubleValue())
                    .collect(Collectors.toList());
            List<Double> lows = bars.stream()
                    .map(b -> b.getLowPrice().doubleValue())
                    .collect(Collectors.toList());

            // ── 维度1: 趋势 ──
            double latestClose = closes.get(closes.size() - 1);
            double ma20 = avg(closes, 20);
            double ma60 = avg(closes, 60);
            // 引入0.5%缓冲带，避免单日噪声导致Regime频繁切换
            double buffer = latestClose * 0.005;
            boolean bullishTrend = latestClose > ma20 + buffer && ma20 > ma60 + buffer;
            boolean bearishTrend = latestClose < ma20 - buffer && ma20 < ma60 - buffer;

            // ── 维度2: ATR 波动率 ──
            double atr20 = calcATR(highs, lows, closes, 20);
            // 计算 ATR 相对值: ATR / close * 100 (%)
            double atrPct = atr20 / latestClose * 100;
            String volRegime;
            if (atrPct > 3.0) {
                volRegime = "HIGH";
            } else if (atrPct < 1.5) {
                volRegime = "LOW";
            } else {
                volRegime = "MEDIUM";
            }

            // ── 维度3: 行业宽度（简化：用行业涨跌幅方向作代理） ──
            // 行业 avgChangePct > 0 视为行业宽度好
            String breadthQuality = "NEUTRAL";
            if (im != null && im.avgChangePct > 0.3) {
                breadthQuality = "GOOD";
            } else if (im != null && im.avgChangePct < -0.3) {
                breadthQuality = "POOR";
            }

            // ── 综合判断 ──
            if (bullishTrend) {
                boolean confirmed = "LOW".equals(volRegime) || "GOOD".equals(breadthQuality);
                return confirmed ? "BULL" : "SIDEWAYS";
            } else if (bearishTrend) {
                boolean confirmed = "HIGH".equals(volRegime) || "POOR".equals(breadthQuality);
                return confirmed ? "BEAR" : "SIDEWAYS";
            } else {
                return "SIDEWAYS";
            }
        } catch (Exception e) {
            log.warn("[Recommendation] 行业[{}]({}) Regime检测失败: {}", industryName, swCode, e.getMessage());
            return "SIDEWAYS";
        }
    }

    /**
     * 批量查询股票行业映射 (Phase A+C 辅助)
     */
    private Map<String, String> buildCodeToIndustryMap(List<ScreenResult.StockScore> candidates) {
        Set<String> pureCodes = candidates.stream()
                .map(s -> stripSuffix(s.getSymbol()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (pureCodes.isEmpty()) return Map.of();

        List<StockInfo> infos = stockInfoMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StockInfo>()
                        .in(StockInfo::getCode, pureCodes));
        return infos.stream()
                .filter(i -> i.getCode() != null && i.getIndustry() != null)
                .collect(Collectors.toMap(StockInfo::getCode, StockInfo::getIndustry, (a, b) -> a));
    }

    /**
     * 计算未来N日收益率 (Phase 3.2)
     * <p>
     * 通过 MarketDataService 获取目标日和基准日收盘价
     * stockCode 格式可能是 "600027.SH" 或纯代码
     */
    private Double calcForwardReturn(String stockCode, LocalDate baseDate, int forwardDays) {
        try {
            // 先取 baseDate 前后足够多的行情，找到 baseDate 对应的交易日及之后第 forwardDays 个交易日
            LocalDate searchStart = baseDate.minusDays(5); // 确保包含baseDate当天
            LocalDate searchEnd = baseDate.plusDays(forwardDays * 2 + 10); // 多取一些确保有足够交易日
            List<MarketDailyBar> bars = marketDataService.getBarsInRange(stockCode, searchStart, searchEnd);
            if (bars == null || bars.isEmpty()) return null;

            // 找到 >= baseDate 的第一个交易日（作为基准日）
            int baseIdx = -1;
            for (int i = 0; i < bars.size(); i++) {
                if (!bars.get(i).getTradeDate().isBefore(baseDate)) {
                    baseIdx = i;
                    break;
                }
            }
            if (baseIdx < 0) return null;

            // 找到基准日之后第 forwardDays 个交易日
            int targetIdx = baseIdx + forwardDays;
            if (targetIdx >= bars.size()) return null; // 数据不足，无法计算

            double baseClose = bars.get(baseIdx).getClose().doubleValue();
            double targetClose = bars.get(targetIdx).getClose().doubleValue();
            if (baseClose <= 0 || targetClose <= 0) return null;

            // 前复权调整：用累积复权因子消除除权除息的价格跳空
            LocalDate targetDate = bars.get(targetIdx).getTradeDate();
            double baseAdj = dividendService.getCumulativeAdjFactor(stockCode, baseDate).doubleValue();
            double targetAdj = dividendService.getCumulativeAdjFactor(stockCode, targetDate).doubleValue();
            if (baseAdj <= 0 || targetAdj <= 0) {
                // 复权因子异常，回退到不复权计算
                return Math.round((targetClose / baseClose - 1.0) * 10000.0) / 100.0;
            }

            double adjBaseClose = baseClose * baseAdj;
            double adjTargetClose = targetClose * targetAdj;
            return Math.round((adjTargetClose / adjBaseClose - 1.0) * 10000.0) / 100.0; // 百分比，保留2位
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 计算推荐买入价
     * <p>
     * 基于MA20作为动态支撑位：
     * - 若MA20可获取且 < closePrice，返回MA20（回踩支撑买入）
     * - 若MA20可获取且 >= closePrice，返回closePrice×0.95（保守折扣）
     * - 若MA20无法获取，返回closePrice×0.95
     */
    private Double calcSuggestedBuyPrice(String stockCode, LocalDate date) {
        try {
            LocalDate startDate = date.minusDays(40); // 多取一些保证20个交易日
            List<MarketDailyBar> bars = marketDataService.getBarsInRange(stockCode, startDate, date);
            if (bars == null || bars.isEmpty()) {
                log.warn("[calcSuggestedBuyPrice] {} getBarsInRange返回空, date={}, startDate={}", stockCode, date, startDate);
                return null;
            }

            // 计算MA20：取最近20根K线的收盘均值
            int count = Math.min(20, bars.size());
            double sum = 0;
            for (int i = bars.size() - count; i < bars.size(); i++) {
                sum += bars.get(i).getClose().doubleValue();
            }
            double ma20 = sum / count;
            double closePrice = bars.get(bars.size() - 1).getClose().doubleValue();

            // MA20作为支撑位：如果MA20低于现价，推荐在MA20附近买入
            if (ma20 < closePrice) {
                return Math.round(ma20 * 100.0) / 100.0;
            }
            // 否则保守给5%折扣
            return Math.round(closePrice * 0.95 * 100.0) / 100.0;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 多因子选股
     *
     * @param strategyId 策略ID（必须）
     */
    private ScreenResult screenStocks(LocalDate date, Long strategyId,
                                      boolean useDynamicIc, String effectiveWeightMode,
                                      List<FactorDiagnostic> diagnostics) {
        // 从策略因子配置获取因子列表
        List<ScreenRequest.FactorWeight> factors = getFactorConfig(strategyId);

        // 动态调整因子权重（基于IC），同时收集诊断信息
        if (useDynamicIc) {
            factors = applyDynamicFactorWeights(factors, date, effectiveWeightMode, diagnostics);
        }

        ScreenRequest req = new ScreenRequest();
        req.setScreenDate(date);
        req.setFactors(factors);
        req.setStrategyId(strategyId);
        req.setTopN(SCREEN_TOP_N);
        req.setDirection("LONG");
        req.setExcludeSt(true);
        req.setGlobalOutlierMethod("MAD");
        req.setGlobalNormalizeMethod("ZSCORE");
        // 智能推荐使用IC加权或等权
        String screenWeightMode = switch (effectiveWeightMode) {
            case "EQW", "STATIC" -> "EQUAL";
            default -> "IC";
        };
        req.setWeightMode(screenWeightMode);
        return stockScreenService.screen(req);
    }

    /**
     * P1+P2: 动态调整因子权重（基于IC历史表现 + 衰减加权 + 预筛选 + 方向对齐）
     * <p>
     * 规则：
     * - 使用 FactorAnalysisService.quickFactorIcSnapshot() 计算衰减加权IC
     * - 预筛选：|IC| &lt; icThreshold 的因子被剔除
     * - 方向对齐：负IC因子自动反转direction，使用|IC|参与加权
     * - 权重分配（由 weightMode 决定）：
     *   EQW  = 等权分配
     *   ICW  = 按|IC|比例分配
     *   OPT  = 按 1/σ²(IC) 分配（稳定性越高权重越大）
     *   STATIC = 不调整（由调用方处理，不会进入此方法）
     *
     * @param factors     原始因子配置
     * @param date        选股日期
     * @param weightMode  权重模式（EQW/ICW/OPT）
     * @param diagnostics 输出参数，因子诊断信息
     * @return 调整后的因子配置
     */
    private List<ScreenRequest.FactorWeight> applyDynamicFactorWeights(
            List<ScreenRequest.FactorWeight> factors, LocalDate date,
            String weightMode, List<FactorDiagnostic> diagnostics) {
        if (factors == null || factors.isEmpty()) return factors;

        List<String> factorCodes = factors.stream()
                .map(ScreenRequest.FactorWeight::getFactorCode)
                .collect(Collectors.toList());

        // Resolve reference date
        LocalDate refDate = date != null ? date : LocalDate.now();
        LocalDate effectiveIcDate = factorIcService.getLatestCommonIcDate(factorCodes);
        if (effectiveIcDate != null && effectiveIcDate.isBefore(refDate)) {
            refDate = effectiveIcDate;
        }

        // P1+P2: 使用衰减加权IC快照
        // 动态半衰期：直接使用默认20天（后续可基于波动率动态调整）
        int halflife = DEFAULT_HALFLIFE_DAYS;
        Map<String, FactorAnalysisService.FactorIcSnapshot> snapshots =
                factorAnalysisService.quickFactorIcSnapshot(
                        factorCodes, refDate, 60, DEFAULT_IC_THRESHOLD, halflife);

        log.info("[DynamicWeight] IC快照完成: mode={}, {}个因子, 阈值={}, 半衰={}天, 保留{}个",
                weightMode, factorCodes.size(), DEFAULT_IC_THRESHOLD, halflife,
                snapshots.values().stream().filter(s -> "KEPT".equals(s.status)).count());

        // 筛选保留的因子
        List<FactorAnalysisService.FactorIcSnapshot> keptSnapshots = snapshots.values().stream()
                .filter(s -> "KEPT".equals(s.status))
                .collect(Collectors.toList());

        // 计算|IC|总和（用于ICW权重分配）
        double sumAbsIc = keptSnapshots.stream()
                .mapToDouble(FactorAnalysisService.FactorIcSnapshot::absIc)
                .sum();

        // 计算逆方差总和（用于OPT权重分配）
        double optSum = keptSnapshots.stream()
                .mapToDouble(s -> 1.0 / Math.max(s.icStd * s.icStd, 1e-9))
                .sum();

        // 构建原始因子查找表
        Map<String, ScreenRequest.FactorWeight> originalMap = new LinkedHashMap<>();
        for (ScreenRequest.FactorWeight fw : factors) {
            originalMap.put(fw.getFactorCode(), fw);
        }

        List<ScreenRequest.FactorWeight> adjusted = new ArrayList<>();
        int keptCount = 0, droppedCount = 0, noDataCount = 0;

        for (ScreenRequest.FactorWeight fw : factors) {
            String fc = fw.getFactorCode();
            FactorAnalysisService.FactorIcSnapshot snap = snapshots.get(fc);
            double originalWeight = fw.getWeight();
            int originalDirection = fw.getDirection();

            ScreenRequest.FactorWeight adjustedFw = new ScreenRequest.FactorWeight();
            adjustedFw.setFactorCode(fc);
            adjustedFw.setFilterOp(fw.getFilterOp());
            adjustedFw.setFilterValue(fw.getFilterValue());

            FactorDiagnostic diag = new FactorDiagnostic();
            diag.factorCode = fc;
            diag.originalWeight = originalWeight;

            if (snap == null || "NO_DATA".equals(snap.status)) {
                // 无IC数据，保持原样
                adjustedFw.setWeight(originalWeight);
                adjustedFw.setDirection(originalDirection);
                diag.action = "NO_DATA";
                diag.adjustedWeight = originalWeight;
                diag.icMean = 0;
                diag.reason = "无IC历史数据，保持原始配置";
                log.warn("[DynamicWeight] 因子 {} 无IC历史数据", fc);
                noDataCount++;
            } else if ("DROPPED".equals(snap.status)) {
                // |IC| < 阈值，剔除
                adjustedFw.setWeight(0.0);
                adjustedFw.setDirection(originalDirection);
                diag.action = "DROPPED";
                diag.icMean = snap.icMean;
                diag.adjustedWeight = 0;
                diag.reason = String.format("|IC|=%.4f < 阈值%.2f，预筛选剔除（衰减加权IC=%.4f，半衰=%d天）",
                        Math.abs(snap.icMean), DEFAULT_IC_THRESHOLD, snap.icMean, halflife);
                log.info("[DynamicWeight] 因子 {} |IC|={:.4f} < {}, 剔除", fc, Math.abs(snap.icMean), DEFAULT_IC_THRESHOLD);
                droppedCount++;
            } else {
                // KEPT: 方向对齐 + |IC|加权
                // 方向对齐：负IC → 反转direction
                int alignedDirection = snap.icSign < 0 ? -originalDirection : originalDirection;
                adjustedFw.setDirection(alignedDirection);

                // 权重按 weightMode 分配
                double absIc = snap.absIc();
                double newWeight;
                String action;
                switch (weightMode) {
                    case "EQW":
                        // 等权：保留的因子平均分配
                        newWeight = originalWeight / Math.max(keptSnapshots.size(), 1);
                        action = "KEPT_EQW";
                        break;
                    case "OPT":
                        // 逆方差：按 1/σ²(IC) 分配（稳定性越高权重越大）
                        if (optSum > 1e-9) {
                            newWeight = originalWeight * (1.0 / Math.max(snap.icStd * snap.icStd, 1e-9) / optSum);
                        } else {
                            newWeight = originalWeight / Math.max(keptSnapshots.size(), 1);
                        }
                        action = "KEPT_OPT";
                        break;
                    default:
                        // ICW: |IC|加权
                        if (sumAbsIc > 1e-9) {
                            newWeight = originalWeight * (absIc / sumAbsIc);
                        } else {
                            newWeight = originalWeight / Math.max(keptSnapshots.size(), 1);
                        }
                        action = "KEPT_ICW";
                        break;
                }
                adjustedFw.setWeight(newWeight);
                diag.action = action;

                diag.icMean = snap.icMean;
                diag.adjustedWeight = newWeight;
                diag.reason = String.format(
                        "%s: IC=%.4f (半衰%d天), |IC|=%.4f, 方向%s, 新权重=%.4f",
                        weightMode, snap.icMean, halflife, absIc,
                        snap.icSign < 0 ? "↓取反(对齐)" : "↑正向",
                        newWeight);
                log.info("[DynamicWeight] 因子 {} {} IC={:.4f} (|IC|={:.4f}) 方向={} 权重: {}->{}",
                        fc, weightMode, snap.icMean, absIc,
                        snap.icSign < 0 ? "取反" : "正向",
                        originalWeight, newWeight);
                keptCount++;
            }

            diagnostics.add(diag);
            adjusted.add(adjustedFw);
        }

        log.info("[DynamicWeight] 完成: mode={}, 保留{}/剔除{}/无数据{}, |IC|和={:.4f}, 半衰={}天",
                weightMode, keptCount, droppedCount, noDataCount, sumAbsIc, halflife);

        return adjusted;
    }

    /**
     * 解析策略级 weightMode（优先级：请求参数 > 策略配置 > 默认ICW）
     * 支持：EQW(等权) / ICW(IC加权) / OPT(逆方差) / STATIC(原始配置不调整)
     */
    private String resolveWeightMode(Long strategyId, String requestWeightMode) {
        // 1. 请求显式指定 → 用请求的
        if (requestWeightMode != null && !requestWeightMode.isEmpty()) {
            return requestWeightMode.toUpperCase();
        }
        // 2. 策略配置了 → 用策略的
        if (strategyId != null) {
            try {
                StrategyDefinition strategy = strategyDefinitionMapper.selectById(strategyId);
                if (strategy != null && strategy.getFactorConfigJson() != null) {
                    Object raw = objectMapper.readValue(strategy.getFactorConfigJson(), Object.class);
                    if (raw instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> map = (Map<String, Object>) raw;
                        Object wm = map.get("weightMode");
                        if (wm != null && !wm.toString().isEmpty()) {
                            return wm.toString().toUpperCase();
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[Recommendation] 解析策略weightMode失败: strategyId={} error={}", strategyId, e.getMessage());
            }
        }
        // 3. 默认 ICW
        return "ICW";
    }

    /**
     * 从策略 factorConfigJson 获取因子配置（全部走数据库，无硬编码兜底）
     */
    private List<ScreenRequest.FactorWeight> getFactorConfig(Long strategyId) {
        if (strategyId == null) {
            throw new IllegalArgumentException("strategyId 不能为空，因子配置必须从数据库策略中获取");
        }
        StrategyDefinition strategy = strategyDefinitionMapper.selectById(strategyId);
        if (strategy == null) {
            throw new IllegalArgumentException("策略不存在: strategyId=" + strategyId);
        }
        if (strategy.getFactorConfigJson() == null || strategy.getFactorConfigJson().isEmpty()) {
            throw new IllegalStateException("策略[" + strategy.getStrategyName() + "]未配置因子权重(factorConfigJson为空)，请在策略管理中配置");
        }
        try {
            Object raw = objectMapper.readValue(strategy.getFactorConfigJson(), Object.class);
            List<Map<String, Object>> factorConfigs;
            if (raw instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> list = (List<Map<String, Object>>) raw;
                factorConfigs = list;
            } else if (raw instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) raw;
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> factors = (List<Map<String, Object>>) map.get("factors");
                factorConfigs = factors != null ? factors : List.of();
            } else {
                factorConfigs = List.of();
            }

            List<ScreenRequest.FactorWeight> result = new ArrayList<>();
            for (Map<String, Object> cfg : factorConfigs) {
                ScreenRequest.FactorWeight fw = new ScreenRequest.FactorWeight();
                Object code = cfg.get("factorCode");
                if (code == null) code = cfg.get("code");
                fw.setFactorCode(code != null ? code.toString() : null);
                Object dir = cfg.get("direction");
                if (dir == null) dir = cfg.get("dir");
                fw.setDirection(dir instanceof Number ? ((Number) dir).intValue() : 1);
                Object weight = cfg.get("weight");
                fw.setWeight(weight instanceof Number ? ((Number) weight).doubleValue() : 1.0);
                Object filterOp = cfg.get("filterOp");
                if (filterOp != null) fw.setFilterOp(filterOp.toString());
                Object filterValue = cfg.get("filterValue");
                if (filterValue != null) fw.setFilterValue(((Number) filterValue).doubleValue());
                result.add(fw);
            }
            log.info("[Recommendation] 从策略[{}]加载因子配置: {}个因子", strategy.getStrategyName(), result.size());
            return result;
        } catch (IllegalArgumentException e) {
            throw e; // 直接抛出业务异常
        } catch (Exception e) {
            throw new IllegalStateException("策略因子配置解析失败 strategyId=" + strategyId + ": " + e.getMessage(), e);
        }
    }

    /**
     * 从策略 filterConfigJson 获取行业排除列表（全部走数据库，无硬编码兜底）
     */
    @SuppressWarnings("unchecked")
    private List<String> getExcludeIndustries(Long strategyId) {
        if (strategyId == null) {
            return List.of(); // 无策略时不排除
        }
        StrategyDefinition strategy = strategyDefinitionMapper.selectById(strategyId);
        if (strategy == null || strategy.getFilterConfigJson() == null || strategy.getFilterConfigJson().isEmpty()) {
            return List.of();
        }
        try {
            Map<String, Object> filterConfig = objectMapper.readValue(strategy.getFilterConfigJson(), Map.class);
            Object exclude = filterConfig.get("excludeIndustries");
            if (exclude instanceof List && !((List<?>) exclude).isEmpty()) {
                List<String> result = ((List<?>) exclude).stream()
                        .map(Object::toString)
                        .collect(Collectors.toList());
                log.info("[Recommendation] 从策略[{}]加载行业排除: {}个", strategy.getStrategyName(), result.size());
                return result;
            }
        } catch (Exception e) {
            log.warn("[Recommendation] 策略过滤配置解析失败 strategyId={}", strategyId, e);
        }
        return List.of();
    }

    /**
     * 对单只股票做深度分析并融合评分
     *
     * @param im 行业动量(Phase A+C), 可为 null
     */
    private StockRecommendation analyzeAndFuse(ScreenResult.StockScore stock, RegimeInfo regime, LocalDate date,
                                               IndustryMomentum im, Long strategyId) {
        StockRecommendation rec = new StockRecommendation();

        // 基本信息
        rec.setStockCode(stock.getSymbol());
        rec.setStockName(stock.getName());
        rec.setRecommendDate(date);
        rec.setFactorScore(stock.getCompositeScore());
        rec.setClosePrice(stock.getCurrentPrice() != null ? stock.getCurrentPrice().doubleValue() : null);

        // 推荐买入价（基于MA20支撑位）
        rec.setSuggestedBuyPrice(calcSuggestedBuyPrice(stock.getSymbol(), date));

        // 市场环境
        rec.setRegime(regime.regime);
        rec.setIndexClose(regime.indexClose);
        rec.setIndexMa20(regime.indexMa20);
        rec.setIndexMa60(regime.indexMa60);

        // 因子明细 JSON
        try {
            if (stock.getFactorRanks() != null && !stock.getFactorRanks().isEmpty()) {
                rec.setFactorRanksJson(objectMapper.writeValueAsString(stock.getFactorRanks()));
            }
        } catch (Exception ignored) {
        }

        // 个股深度分析：getOverview 内部用 selectStockInfo(code) 查 stock_info 取 name，
        // stock_info.code 是纯代码（无后缀），故必须去后缀传入
        String pureCode = stripSuffix(stock.getSymbol());
        AnalysisOverview overview = analysisService.getOverview(pureCode);
        if (overview != null) {
            // 回填 stock name（getOverview 内部可能查不到 name，用 stock 的 name 兜底）
            if (overview.getName() == null && stock.getName() != null) {
                overview.setName(stock.getName());
            }
            // 只有 overview.name 非空才覆盖，避免 null 覆盖已有的 stock.getName()
            if (overview.getName() != null) {
                rec.setStockName(overview.getName());
            }
            rec.setAnalysisScore(overview.getTotalScore());
            // actionTag 映射：TradingSignalEngine 输出 5 种 (STRONG_BUY/BUY/HOLD/REDUCE/CLEAR)
            // 前端只认 3 种 (BUY/HOLD/SELL)，需要做转换
            rec.setActionTag(mapActionTag(overview.getAction()));
            // buyReason: getOverview 内部 buildConclusion 已正确生成（含 name）
            rec.setBuyReason(overview.getConclusion());

            // 从 scoreDetails 提取各维度得分
            // 维度名: tech=技术面, money=资金面, sentiment=事件面, fundamental=基本面
            if (overview.getScoreDetails() != null) {
                for (ScoreDetail detail : overview.getScoreDetails()) {
                    switch (detail.getDimension()) {
                        case "tech" -> rec.setTechnicalScore(detail.getScore());
                        case "money" -> rec.setCapitalScore(detail.getScore());
                        case "sentiment" -> rec.setEventScore(detail.getScore());
                        case "fundamental" -> rec.setFundamentalScore(detail.getScore());
                    }
                }
            }

            // 归一化到 0~1（134分满分：技术30+资金25+事件25+基本面29+风险15+流动性10）
            rec.setAnalysisScorePct(overview.getTotalScore() != null
                    ? overview.getTotalScore() / 134.0 : 0.0);
        } else {
            rec.setAnalysisScore(0);
            rec.setAnalysisScorePct(0.0);
        }

        // P1-2: 计算风险和流动性评分
        calculateRiskAndLiquidityScore(rec, overview, stock.getCurrentPrice());

        // 新闻事件加分：估值修复/事件驱动策略，如果近30天有利好事件(增持/回购/业绩预增)，额外加分
        String strategyCode = "";
        if (strategyId != null) {
            try {
                StrategyDefinition strat = strategyDefinitionMapper.selectById(strategyId);
                strategyCode = strat != null ? strat.getStrategyCode() : "";
                boolean useEventBoost = "VALUATION_RECOVERY_LLM".equals(strategyCode)
                        || "EVENT_DRIVEN_DOWNGRADED".equals(strategyCode)
                        || "MARKET_SENTIMENT".equals(strategyCode);
                if (useEventBoost) {
                    // A. 新闻事件加分
                    double eventSentiment = newsEventParser.getEventSentimentScore(pureCode, 30);
                    List<String> bullishEvents = newsEventParser.getRecentBullishEvents(pureCode, 30);
                    if (eventSentiment > 0.3 || !bullishEvents.isEmpty()) {
                        int currentEvent = rec.getEventScore() != null ? rec.getEventScore() : 0;
                        int bonus = Math.min(8, bullishEvents.size() * 3);
                        rec.setEventScore(Math.min(25, currentEvent + bonus));
                        if (!bullishEvents.isEmpty()) {
                            String existing = rec.getBuyReason() != null ? rec.getBuyReason() : "";
                            rec.setBuyReason(existing + " | 近期利好事件: " + String.join(",", bullishEvents));
                        }
                        log.info("[Recommendation] 新闻事件加分: strategy={}, code={}, bonus=+{}, events={}",
                                strategyCode, pureCode, bonus, bullishEvents);
                    } else if (eventSentiment < -0.3) {
                        int currentEvent = rec.getEventScore() != null ? rec.getEventScore() : 0;
                        rec.setEventScore(Math.max(0, currentEvent - 5));
                    }

                    // B. 超预期信号加分（仅事件驱动策略）
                    if ("EVENT_DRIVEN_DOWNGRADED".equals(strategyCode)) {
                        EventSignalService.EventSignal earnSignal = eventSignalService.getEventSignal(pureCode);
                        if ("EARN_BEAT".equals(earnSignal.getSignalType())) {
                            // 超预期 → 大幅加分
                            int currentEvent = rec.getEventScore() != null ? rec.getEventScore() : 0;
                            int earnBonus = (int) (earnSignal.getBullishScore() * 10);
                            rec.setEventScore(Math.min(25, currentEvent + earnBonus));
                            String existing = rec.getBuyReason() != null ? rec.getBuyReason() : "";
                            rec.setBuyReason(existing + " | " + earnSignal.getSignalDescription());
                            log.info("[Recommendation] 超预期加分: code={}, signal={}, bonus=+{}",
                                    pureCode, earnSignal.getSignalType(), earnBonus);
                        } else if ("EARN_MISS".equals(earnSignal.getSignalType())) {
                            // 不及预期 → 扣分
                            int currentEvent = rec.getEventScore() != null ? rec.getEventScore() : 0;
                            int earnPenalty = (int) (Math.abs(earnSignal.getBullishScore()) * 8);
                            rec.setEventScore(Math.max(0, currentEvent - earnPenalty));
                            log.info("[Recommendation] 不及预期扣分: code={}, signal={}, penalty=-{}",
                                    pureCode, earnSignal.getSignalType(), earnPenalty);
                        } else if ("EARN_NO_CONSENSUS".equals(earnSignal.getSignalType())
                                && earnSignal.getBullishScore() > 0.4) {
                            // 无一致预期但高增长 → 小幅加分
                            int currentEvent = rec.getEventScore() != null ? rec.getEventScore() : 0;
                            rec.setEventScore(Math.min(25, currentEvent + 3));
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("[Recommendation] 新闻事件加分查询异常: code={}, error={}", pureCode, e.getMessage());
            }
        }

        // 融合评分 (Regime-Adaptive + 行业轮动)
        rec.setFinalScore(fuseScore(rec, regime, im));

        // QVIX 市场恐慌指数调整（仅市场情绪策略）
        if (strategyId != null && "MARKET_SENTIMENT".equals(strategyCode)) {
            try {
                var qvix = marketSentimentService.getLatestQvix();
                if (qvix != null) {
                    double qvixVal = qvix.getValue().doubleValue();
                    double multiplier = 1.0;
                    String qvixNote = "";
                    if (qvixVal >= 35) {
                        // 市场恐慌 → 高动量股票风险加大，降分
                        multiplier = 0.85;
                        qvixNote = "QVIX=" + String.format("%.1f", qvixVal) + "(恐慌)";
                    } else if (qvixVal >= 25) {
                        // 市场担忧 → 微降
                        multiplier = 0.92;
                        qvixNote = "QVIX=" + String.format("%.1f", qvixVal) + "(担忧)";
                    } else if (qvixVal < 15) {
                        // 市场平静 → 动量策略效果好，微增
                        multiplier = 1.08;
                        qvixNote = "QVIX=" + String.format("%.1f", qvixVal) + "(平静)";
                    } else {
                        qvixNote = "QVIX=" + String.format("%.1f", qvixVal) + "(正常)";
                    }
                    double adjusted = rec.getFinalScore() * multiplier;
                    rec.setFinalScore(Math.round(adjusted * 10000.0) / 10000.0);
                    String existing = rec.getBuyReason() != null ? rec.getBuyReason() : "";
                    if (!qvixNote.isEmpty()) {
                        rec.setBuyReason(existing + " | " + qvixNote);
                    }
                    log.info("[Recommendation] QVIX调整: code={}, QVIX={}, multiplier={}, score={}",
                            pureCode, String.format("%.1f", qvixVal), String.format("%.2f", multiplier), rec.getFinalScore());
                }
            } catch (Exception e) {
                log.debug("[Recommendation] QVIX调整异常: code={}, error={}", pureCode, e.getMessage());
            }
        }

        // Phase A: 行业 Regime
        if (im != null && im.industryRegime != null) {
            rec.setIndustryRegime(im.industryRegime);
        }

        return rec;
    }

    /**
     * 计算风险和流动性评分 (P1-2)
     * <p>
     * 风险评分（0-15分）：
     * - 最大回撤（0-5分）
     * - 20日波动率（0-5分）
     * - ATR/价格比（0-5分）
     * <p>
     * 流动性评分（0-10分）：
     * - 20日均成交额（0-5分）
     * - 换手率适中度（0-5分）
     */
    private void calculateRiskAndLiquidityScore(StockRecommendation rec, AnalysisOverview overview, BigDecimal currentPrice) {
        if (overview == null) return;

        // ── 风险评分（0-15分）──
        int riskScore = 0;

        // a) 最大回撤扣分（0-5分）
        if (overview.getMaxDrawdown() != null) {
            double dd = overview.getMaxDrawdown();
            if (dd < -0.10) riskScore += 0;      // 回撤>10%，0分
            else if (dd < -0.05) riskScore += 2;  // 回撤5-10%，2分
            else if (dd < -0.02) riskScore += 4;  // 回撤2-5%，4分
            else riskScore += 5;                   // 回撤<2%，满分
        }

        // b) 波动率扣分（0-5分）
        if (overview.getVolatility20d() != null) {
            double vol = overview.getVolatility20d();
            if (vol > 0.40) riskScore += 0;       // 波动率>40%，0分
            else if (vol > 0.30) riskScore += 2;   // 波动率30-40%，2分
            else if (vol > 0.20) riskScore += 4;   // 波动率20-30%，4分
            else riskScore += 5;                    // 波动率<20%，满分
        }

        // c) ATR/价格比扣分（0-5分，低波动=高分）
        if (overview.getAtr() != null && currentPrice != null && currentPrice.doubleValue() > 0) {
            double atrPct = overview.getAtr().doubleValue() / currentPrice.doubleValue();
            if (atrPct > 0.04) riskScore += 0;      // ATR/价格>4%，0分
            else if (atrPct > 0.03) riskScore += 2;  // 3-4%，2分
            else if (atrPct > 0.02) riskScore += 4;  // 2-3%，4分
            else riskScore += 5;                      // <2%，满分
        }

        rec.setRiskScore(riskScore);

        // ── 流动性评分（0-10分）──
        int liquidityScore = 0;

        // a) 日均成交额（0-5分）
        if (overview.getAvgAmount20d() != null) {
            double avgAmt = overview.getAvgAmount20d();
            if (avgAmt > 5e9) liquidityScore += 5;       // >50亿，5分
            else if (avgAmt > 1e9) liquidityScore += 4;   // >10亿，4分
            else if (avgAmt > 3e8) liquidityScore += 3;   // >3亿，3分
            else if (avgAmt > 1e8) liquidityScore += 2;   // >1亿，2分
            else liquidityScore += 1;                      // <1亿，1分
        }

        // b) 换手率适中度（0-5分，过高过低都扣分）
        if (overview.getTurnoverRate20d() != null) {
            double turn = overview.getTurnoverRate20d();
            if (turn >= 1.0 && turn <= 5.0) liquidityScore += 5;  // 适中，5分
            else if (turn >= 0.5 && turn <= 8.0) liquidityScore += 3; // 略偏，3分
            else liquidityScore += 1;                              // 过低或过高，1分
        }

        rec.setLiquidityScore(liquidityScore);

        log.debug("[RiskLiquidity] code={} riskScore={}/15 liquidityScore={}/10",
                rec.getStockCode(), riskScore, liquidityScore);
    }

    /**
     * 计算最近 N 天的均值
     */
    private double avg(List<Double> values, int n) {
        int size = values.size();
        if (size < n) return 0;
        double sum = 0;
        for (int i = size - n; i < size; i++) {
            sum += values.get(i);
        }
        return sum / n;
    }

    /**
     * 因子动态权重诊断信息
     */
    public static class FactorDiagnostic {
        public String factorCode;
        public String action;       // KEPT: 保留参与加权, DROPPED: IC≤0权重置零, REVERSED: IC为负方向反转, NO_DATA: 无IC数据
        public double icMean;       // 近60日IC均值
        public double originalWeight;
        public double adjustedWeight;
        public String reason;       // 简要中文说明
    }

    /**
     * 市场环境信息 (Phase 2 多维)
     */
    static class RegimeInfo {
        String regime; // BULL, BEAR, SIDEWAYS

        // 指数趋势
        double indexClose;
        double indexMa20;
        double indexMa60;
        Double indexChangePct; // 沪深300当日涨跌幅%

        // 波动率
        double atrValue;
        Double atrPercentile;  // 0~1
        String volatilityRegime; // LOW, MEDIUM, HIGH

        // 市场宽度
        Long riseCount;
        Long fallCount;
        Double breadthRatio;    // 0~1
        String breadthQuality;  // GOOD, NEUTRAL, POOR

        // 风格维度 (P1-1)
        String styleRegime;      // GROWTH, VALUE, NEUTRAL
        String sizeRegime;       // LARGE, SMALL, NEUTRAL
        Double sizeSpread;       // 大盘-小盘相对强度
        Double valueGrowthSpread; // 价值-成长相对强度

        // 利率/流动性环境 (P2-2)
        String rateRegime;       // UP, DOWN, NEUTRAL
        Double bondYield10y;     // 10年期国债收益率(%)
        Double bondYieldMa20;     // 10年国债20日均线(%)
        Double yieldCurveSpread;  // 10年-2年利差(%)
    }

    /**
     * 行业动量信息 (Phase A+C)
     * <p>
     * 从 getSectorRanking() 获取行业涨跌幅，计算:
     * - relativeStrength: 相对沪深300的强度(标准化z-score, 越大越强势)
     * - momentumRank: 行业内排名百分位(0~1, 越大越靠前)
     */
    static class IndustryMomentum {
        String industry;
        double avgChangePct;       // 行业当日平均涨跌幅%
        double relativeStrength;   // 相对沪深300强度(z-score标准化, -3~3)
        int industryDiversifyLimit; // 该行业分散化上限(根据强度动态调整: 1~6)
        double fusionBonus;        // 因子融合加分(-0.06~+0.06)
        String industryRegime;     // 分行业Regime: BULL/BEAR/SIDEWAYS (Phase A 完整版)

        // P2-1: 行业动量增强
        double momentum20d;        // 行业近20日动量（累计涨跌幅%）
        double momentumScore;      // 动量综合评分（0~1）
        String momentumTrend;      // 动量趋势: ACCELERATING / DECELERATING / FLAT
    }
}
