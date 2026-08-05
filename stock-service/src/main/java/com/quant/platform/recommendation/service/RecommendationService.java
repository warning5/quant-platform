package com.quant.platform.recommendation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.factor.ic.service.FactorIcService;
import com.quant.platform.factor.regime.MarketRegimeCalendarService;
import com.quant.platform.factor.service.FactorAnalysisService;
import com.quant.platform.factor.service.FactorCorrelationService;
import com.quant.platform.factor.service.QuarterlyFactorAnalysisService;
import com.quant.platform.market.domain.MarketDailyBar;
import com.quant.platform.market.service.MarketDataService;
import com.quant.platform.recommendation.domain.StockRecommendation;
import com.quant.platform.recommendation.mapper.RecommendationMapper;
import com.quant.platform.screen.dto.ScreenRequest;
import com.quant.platform.screen.dto.ScreenResult;
import com.quant.platform.stock.analysis.domain.AnalysisOverview;
import com.quant.platform.stock.analysis.domain.ScoreDetail;
import com.quant.platform.stock.analysis.service.AnalysisService;
import com.quant.platform.stock.analysis.service.EventSignalService;
import com.quant.platform.stock.analysis.service.NewsEventParser;
import com.quant.platform.stock.analysis.mapper.StockAnalysisMapper;
import com.quant.platform.stock.entity.StockDaily;
import com.quant.platform.stock.entity.StockInfo;
import com.quant.platform.stock.mapper.StockInfoMapper;
import com.quant.platform.stock.service.ClickHouseStockService;
import com.quant.platform.strategy.domain.StrategyDefinition;
import com.quant.platform.strategy.mapper.StrategyDefinitionMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import java.math.BigDecimal;
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
     * 个股深度分析取 Top N（精筛）
     */
    private static final int ANALYSIS_TOP_N = 20;

    /**
     * P1-6: 个股深度分析并行线程数
     * 限制并发以避免 ClickHouse 连接池耗尽
     */
    private static final int ANALYSIS_PARALLELISM = 5;

    /**
     * 高置信门槛：回测验证(7633条历史) final_score∈[0.7,0.9] 档次日超额 +0.71%/胜率57.5%，
     * 而 0.5~0.7 档为噪声/负收益区(占推荐61%)。仅发出达门槛的推荐，砍掉拖后腿的平庸票。
     */
    private static final double HIGH_CONVICTION_FINAL_SCORE = 0.70;
    /** 高置信档不足时保底保留的 topN（避免低信号期策略无票） */
    private static final int MIN_HIGH_CONVICTION_PICKS = 5;
    /**
     * 优化④：连续 BEAR 暂停生成（离散开关）。
     * 回测发现 BEAR 是小样本噪声区（胜率<50%、日亏），激进降仓/提门槛均被证伪。
     * 改用更干净的离散防御：最近 N 个交易日(含当日) detectRegime 全部判为 BEAR 时，
     * 直接暂停当日生成（return 空列表），规避下行，而非在噪声中调参。
     */
    private static final int CONSECUTIVE_BEAR_STOP_DAYS = 3;
    private final AnalysisService analysisService;
    private final MarketDataService marketDataService;
    private final ClickHouseStockService clickHouseStockService;
    private final StockInfoMapper stockInfoMapper;
    private final RecommendationMapper recommendationMapper;
    private final StrategyDefinitionMapper strategyDefinitionMapper;
    private final ObjectMapper objectMapper;
    private final FactorIcService factorIcService;
    private final FactorAnalysisService factorAnalysisService;
    private final StockBlacklistService stockBlacklistService;
    private final StrategyConfidenceService strategyConfidenceService;
    private final NewsEventParser newsEventParser;
    private final EventSignalService eventSignalService;
    private final com.quant.platform.market.MarketSentimentService marketSentimentService;
    private final FactorCorrelationService factorCorrelationService;
    private final QuarterlyFactorAnalysisService quarterlyFactorAnalysisService;
    private final StockAnalysisMapper stockAnalysisMapper;
    private final com.quant.platform.factor.ic.mapper.FactorIcRecordMapper factorIcRecordMapper;
    private final com.quant.platform.factor.service.FactorMetaCacheService factorMetaCache;
    private final com.quant.platform.factor.mapper.FactorDefinitionMapper factorDefinitionMapper;
    private final com.quant.platform.factor.dynamic.DynamicIndustryCorrelationService dynamicIndustryCorrService;

    /** regime 日历服务（可选；用于 ICW 按 regime 分别取 IC 历史）。字段注入，避免改动构造函数。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MarketRegimeCalendarService regimeCalendarService;

    private final RecommendationQueryService queryService;

    // ── God Class 拆分 Phase 3 抽出的专责协作者（本类相关方法退化为薄委托）──
    private final MarketRegimeDetector marketRegimeDetector;
    private final CandidateScreener candidateScreener;
    private final PricePlanCalculator pricePlanCalculator;
    private final RecommendationTracker recommendationTracker;

    // Phase4 拆出的专责组件
    private final IndustryRotationService industryRotationService;
    private final FactorWeightResolver factorWeightResolver;
    private final StockScoreFuser stockScoreFuser;

    /** 共享个股深度分析线程池（单例 bean，替代每次调用 newFixedThreadPool） */
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.beans.factory.annotation.Qualifier("recommendationAnalysisExecutor")
    private java.util.concurrent.ExecutorService analysisExecutor;

    @PostConstruct
    public void initRegimeCalendar() {
        if (regimeCalendarService != null) {
            regimeCalendarService.setDetector(this::detectRegimeName);
            log.info("[Recommendation] regime日历 detector 已注入（detectRegime 落库生效）");
        }
    }

    public RecommendationService(AnalysisService analysisService,
                                 MarketDataService marketDataService,
                                 ClickHouseStockService clickHouseStockService,
                                 StockInfoMapper stockInfoMapper,
                                 RecommendationMapper recommendationMapper,
                                 StrategyDefinitionMapper strategyDefinitionMapper,
                                 ObjectMapper objectMapper,
                                 FactorIcService factorIcService,
                                 FactorAnalysisService factorAnalysisService,
                                 StockBlacklistService stockBlacklistService,
                                 StrategyConfidenceService strategyConfidenceService,
                                 NewsEventParser newsEventParser,
                                 EventSignalService eventSignalService,
                                 com.quant.platform.market.MarketSentimentService marketSentimentService,
                                 FactorCorrelationService factorCorrelationService,
                                 QuarterlyFactorAnalysisService quarterlyFactorAnalysisService,
                                 StockAnalysisMapper stockAnalysisMapper,
                                 com.quant.platform.factor.ic.mapper.FactorIcRecordMapper factorIcRecordMapper,
                                 com.quant.platform.factor.service.FactorMetaCacheService factorMetaCache,
                                 com.quant.platform.factor.mapper.FactorDefinitionMapper factorDefinitionMapper,
                                 com.quant.platform.factor.dynamic.DynamicIndustryCorrelationService dynamicIndustryCorrService,
                                 RecommendationQueryService queryService,
                                 MarketRegimeDetector marketRegimeDetector,
                                 CandidateScreener candidateScreener,
                                 PricePlanCalculator pricePlanCalculator,
                                 RecommendationTracker recommendationTracker,
                                 IndustryRotationService industryRotationService,
                                 FactorWeightResolver factorWeightResolver,
                                 StockScoreFuser stockScoreFuser) {
        this.analysisService = analysisService;
        this.marketDataService = marketDataService;
        this.clickHouseStockService = clickHouseStockService;
        this.stockInfoMapper = stockInfoMapper;
        this.recommendationMapper = recommendationMapper;
        this.strategyDefinitionMapper = strategyDefinitionMapper;
        this.objectMapper = objectMapper;
        this.factorIcService = factorIcService;
        this.factorAnalysisService = factorAnalysisService;
        this.stockBlacklistService = stockBlacklistService;
        this.strategyConfidenceService = strategyConfidenceService;
        this.newsEventParser = newsEventParser;
        this.eventSignalService = eventSignalService;
        this.marketSentimentService = marketSentimentService;
        this.factorCorrelationService = factorCorrelationService;
        this.quarterlyFactorAnalysisService = quarterlyFactorAnalysisService;
        this.stockAnalysisMapper = stockAnalysisMapper;
        this.factorIcRecordMapper = factorIcRecordMapper;
        this.factorMetaCache = factorMetaCache;
        this.factorDefinitionMapper = factorDefinitionMapper;
        this.dynamicIndustryCorrService = dynamicIndustryCorrService;
        this.queryService = queryService;
        this.marketRegimeDetector = marketRegimeDetector;
        this.candidateScreener = candidateScreener;
        this.pricePlanCalculator = pricePlanCalculator;
        this.recommendationTracker = recommendationTracker;
        this.industryRotationService = industryRotationService;
        this.factorWeightResolver = factorWeightResolver;
        this.stockScoreFuser = stockScoreFuser;
    }

    /**
     * 生成推荐列表
     * <p>
     * P1: 默认启用IC动态权重（不再需要weightMode="IC"）
     * 自动预筛选+方向对齐+衰减加权
     *
     * @param date        推荐日期（null 则使用最新可用日期）
     * @param topN        最终推荐数量（默认20）
     * @param diagnostics 输出参数，因子诊断信息（调用方传入空List来收集）
     * @return 推荐结果列表
     */
    public List<StockRecommendation> generateRecommendations(LocalDate date, Integer topN,
                                                             Long strategyId, String weightMode, List<FactorDiagnostic> diagnostics,
                                                             boolean enableConfidenceControl) {
        return generateRecommendations(date, topN, strategyId, weightMode, diagnostics, enableConfidenceControl, null);
    }

    /**
     * 生成推荐列表（支持高级选项覆盖）
     *
     * @param advancedOptions 高级选股选项（null=使用推荐管线默认行为）
     */
    public List<StockRecommendation> generateRecommendations(LocalDate date, Integer topN,
                                                             Long strategyId, String weightMode, List<FactorDiagnostic> diagnostics,
                                                             boolean enableConfidenceControl,
                                                             AdvancedScreenOptions advancedOptions) {
        // date=null 时 StockScreenService.screen() 会自动取最新日期
        if (topN == null || topN <= 0) {
            topN = ANALYSIS_TOP_N;
        }
        // P1: 默认启用IC动态权重（STATIC模式手动关闭）
        // weightMode 优先级：请求参数 > 策略factorConfigJson > 默认ICW
        String effectiveWeightMode = resolveWeightMode(strategyId, weightMode);
        boolean useDynamicIc = !"STATIC".equalsIgnoreCase(effectiveWeightMode);

        log.info("[Recommendation] 开始生成推荐列表: date={}, topN={}, strategyId={}, weightMode={} (resolved={}), confidenceControl={}, hasAdvanced={}",
                date, topN, strategyId, weightMode, effectiveWeightMode, enableConfidenceControl,
                advancedOptions != null);

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
        // P2修复: 记录命中率调整结果，与置信度调整取max而非叠加
        int hitRateAdjustedTopN = topN; // 命中率调整后的topN
        if (strategyId != null && topN > 10) {
            StockRecommendation latestRec = recommendationMapper.findLatest();
            if (latestRec != null && latestRec.getStrategyId() != null && latestRec.getStrategyId().equals(strategyId)) {
                LocalDate prevDate = latestRec.getRecommendDate();
                if (prevDate != null) {
                    Map<String, Object> hitStats = getHitRate(latestRec.getStrategyId(), prevDate);
                    Double hitRate = (Double) hitStats.get("hitRate");
                    if (hitRate != null && hitRate < 0.4) {
                        hitRateAdjustedTopN = Math.max(10, topN - 5);
                        log.info("[Recommendation] 上期命中率{}%偏低({}), 建议缩减topN: {} -> {}",
                                hitRate * 100, prevDate, topN, hitRateAdjustedTopN);
                    }
                }
            }
        }

        // Step 0.5: 策略置信度检查（方案C - Layer 1: 策略级风控）
        // 在黑名单过滤(Layer 2)之前执行，如果置信度过低直接降topN或建议暂停
        // 仅在启用置信度控制时生效
        if (enableConfidenceControl && strategyId != null) {
            try {
                // P1修复: 按权重模式查询置信度
                var confidenceOpt = strategyConfidenceService.getLatestConfidence(strategyId, effectiveWeightMode);
                if (confidenceOpt.isPresent()) {
                    var conf = confidenceOpt.get();
                    String level = conf.getLevel();
                    Integer score = conf.getScore();
                    log.info("[Recommendation] 策略置信度: strategyId={}, mode={}, level={}, score={}, hitRate={}%, avgReturn={}%",
                            strategyId, effectiveWeightMode, level,
                            score != null ? score : "N/A",
                            conf.getHitRateValue() != null ? conf.getHitRateValue().doubleValue() * 100 : 0,
                            conf.getAvgReturnValue() != null ? conf.getAvgReturnValue().doubleValue() : 0);

                    // 根据置信度调整 topN
                    int confidenceAdjustedTopN = strategyConfidenceService.getAdjustedTopN(topN, conf);

                    // P2修复: 命中率调整和置信度调整取max（较温和的那个），而非叠加
                    // 原来是先执行命中率调整(topN-5)，再在已缩减的topN上执行置信度调整(topN/3)，导致过度缩减
                    int finalTopN = Math.max(hitRateAdjustedTopN, confidenceAdjustedTopN);

                    if (finalTopN < topN) {
                        int originalTopN = topN;
                        topN = finalTopN;
                        log.info("[Recommendation] topN调整: {} -> {} (命中率建议={}, 置信度建议={}, 取max; level={}, score={})",
                                originalTopN, topN, hitRateAdjustedTopN, confidenceAdjustedTopN, level, score);
                    }
                } else {
                    // 无置信度记录时，仅应用命中率调整
                    if (hitRateAdjustedTopN < topN) {
                        int originalTopN = topN;
                        topN = hitRateAdjustedTopN;
                        log.info("[Recommendation] topN调整(仅命中率): {} -> {}", originalTopN, topN);
                    }
                }
            } catch (Exception e) {
                // P3修复: 异常时降级为保守topN而非放行全量推荐
                log.error("[Recommendation] 置信度查询异常，降级为保守topN: error={}", e.getMessage());
                topN = Math.max(5, topN / 2);
                log.info("[Recommendation] 异常降级topN: {}", topN);
            }
        } else {
            // 未启用置信度控制时，仅应用命中率调整
            if (hitRateAdjustedTopN < topN) {
                int originalTopN = topN;
                topN = hitRateAdjustedTopN;
                log.info("[Recommendation] topN调整(仅命中率, 置信度未启用): {} -> {}", originalTopN, topN);
            }
        }

        // Step 1: 多因子选股（广筛 Top 50）
        // date=null 时 StockScreenService.screen() 内部自动 resolveLatestDate()
        ScreenResult screenResult = screenStocks(date, strategyId, useDynamicIc, effectiveWeightMode, diagnostics, advancedOptions);
        List<ScreenResult.StockScore> candidates = screenResult.getStocks();
        if (candidates == null || candidates.isEmpty()) {
            log.warn("[Recommendation] 因子选股结果为空，无法生成推荐");
            return List.of();
        }

        // Step 1.3: 行业白名单 + 概念股过滤（从策略 filterConfigJson 读取）
        // 只保留属于白名单行业或概念成分股的候选（优先级：概念股 > 行业白名单 > 全市场）
        List<String> includeIndustries = getIncludeIndustries(strategyId);
        List<String> conceptNames = getConceptNames(strategyId);
        if (!includeIndustries.isEmpty() || !conceptNames.isEmpty()) {
            List<String> candidateCodes = candidates.stream()
                    .map(s -> RecommendationMath.stripSuffix(s.getSymbol()))
                    .collect(Collectors.toList());
            List<StockInfo> infos = stockInfoMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StockInfo>()
                            .in(StockInfo::getCode, candidateCodes)
                            .select(StockInfo::getCode, StockInfo::getIndustry, StockInfo::getName));
            Map<String, StockInfo> codeInfoMap = infos.stream()
                    .filter(i -> i.getCode() != null)
                    .collect(Collectors.toMap(StockInfo::getCode, i -> i, (a, b) -> a));

            // 构建概念股代码集合（从 stock_concept 表加载）
            Set<String> conceptCodes = new HashSet<>();
            if (!conceptNames.isEmpty()) {
                for (String conceptName : conceptNames) {
                    List<Map<String, Object>> rows = stockAnalysisMapper.selectConceptStocksByName(conceptName);
                    for (Map<String, Object> row : rows) {
                        Object codeObj = row.get("code");
                        if (codeObj != null) conceptCodes.add(codeObj.toString());
                    }
                }
                log.info("[Recommendation] 概念股过滤: 概念={}, 加载成分股数={}", conceptNames, conceptCodes.size());
            }

            int before = candidates.size();
            candidates = candidates.stream()
                    .filter(s -> {
                        String pureCode = RecommendationMath.stripSuffix(s.getSymbol());
                        StockInfo info = codeInfoMap.get(pureCode);
                        String ind = info != null && info.getIndustry() != null ? info.getIndustry() : "";

                        // 优先级1: 概念股白名单（如果配置了概念名，只要在概念成分股内就保留）
                        if (!conceptNames.isEmpty() && conceptCodes.contains(pureCode)) {
                            return true;
                        }
                        // 优先级2: 行业白名单（行业关键词子串匹配）
                        if (!includeIndustries.isEmpty()) {
                            return includeIndustries.stream().anyMatch(ind::contains);
                        }
                        // 没有配置任何白名单 → 全市场保留
                        return true;
                    })
                    .collect(Collectors.toList());

            log.info("[Recommendation] 行业/概念白名单过滤 [strategyId={}]: 行业白名单={}, 概念={}, 过滤前={}, 过滤后={}",
                    strategyId, includeIndustries.size(), conceptNames, before, candidates.size());
            if (candidates.isEmpty()) {
                log.warn("[Recommendation] 白名单过滤后候选池为空，跳过生成");
                return List.of();
            }
        }

        // Step 1.5: 行业排除过滤（从策略 filterConfigJson 读取）
        List<String> excludeIndustries = getExcludeIndustries(strategyId);
        if (!excludeIndustries.isEmpty()) {
            Set<String> excludeSet = new HashSet<>(excludeIndustries);
            List<String> candidateCodes = candidates.stream()
                    .map(s -> RecommendationMath.stripSuffix(s.getSymbol()))
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
                        String pureCode = RecommendationMath.stripSuffix(s.getSymbol());
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
                        StockInfo info = codeInfoMap.get(RecommendationMath.stripSuffix(s.getSymbol()));
                        String ind = info != null && info.getIndustry() != null ? info.getIndustry() : "无行业";
                        String name = info != null && info.getName() != null ? info.getName() : s.getName();
                        return name + "(" + ind + ")";
                    })
                    .collect(Collectors.toList());

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
                // 构建纯代码集合（去除.SZ/.SH/.BJ后缀），兼容历史数据
                Set<String> pureBlacklistCodes = new HashSet<>();
                for (String code : blacklistCodes) {
                    pureBlacklistCodes.add(RecommendationMath.stripSuffix(code));
                }
                int beforeBl = candidates.size();
                List<String> filteredStocks = new ArrayList<>();
                candidates = candidates.stream()
                        .filter(s -> {
                            String pureCode = RecommendationMath.stripSuffix(s.getSymbol());
                            if (pureBlacklistCodes.contains(pureCode)) {
                                filteredStocks.add(s.getName() + "(" + pureCode + ")");
                                return false;
                            }
                            return true;
                        })
                        .collect(Collectors.toList());

                log.info("[Recommendation] 黑名单过滤 [strategyId={}]: 黑名单股票数={}, 过滤前={}, 过滤后={}, 被过滤={}",
                        strategyId, pureBlacklistCodes.size(), beforeBl, candidates.size(), filteredStocks);
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

        // 优化④：连续 BEAR 暂停生成（离散防御）
        // 回测证明 BEAR 是小样本噪声区（胜率<50%、日亏），激进降仓/提门槛均被证伪。
        // 改为最近 N(含当日) 个交易日全部 BEAR 时直接暂停，规避下行。
        if (isConsecutiveBear(actualDate, CONSECUTIVE_BEAR_STOP_DAYS)) {
            log.warn("[Recommendation] 优化④触发: 连续{}日BEAR, 暂停策略[{}] {} 生成, 规避下行",
                    CONSECUTIVE_BEAR_STOP_DAYS, strategyId, actualDate);
            return List.of();
        }

        // Step 2.5: 行业动量计算 (Phase A+C)
        Map<String, IndustryMomentum> industryMomentumMap = computeIndustryMomentum(regime, actualDate);

        // Step 2.6: 预查行业映射(避免 N+1 查询)
        Map<String, String> codeToIndustry = buildCodeToIndustryMap(candidates);
        log.info("[Recommendation] 行业映射: {}只候选股", codeToIndustry.size());

        // Step 3: 对 Top N 做个股深度分析（P1-6: 并行化）

        // 【因子分位排名归一化】对最终候选集的 compositeScore 做批次内 percentile-rank，
        // 强制拉开因子分区分度。原 compositeScore 为全通过池 rank 加权后的绝对分，
        // TopN 内仍挤在 0.86+，导致 rank_num 排序近似随机、与次日收益无单调关系。
        // 改为候选批次内的分位排名后，factor_score 均匀分布于 (0,1)，与 rank_num 严格单调对应，
        // 排序区分度复活，并经由 fuseScore 传导到 final_score。
        if (candidates.size() > 1) {
            List<Double> compSorted = candidates.stream()
                    .map(ScreenResult.StockScore::getCompositeScore)
                    .sorted().toList();
            Map<Double, Double> compToRank = new HashMap<>();
            int cn = compSorted.size();
            for (int i = 0; i < cn; i++) {
                // 重复值取首次出现分位，保证映射稳定
                compToRank.putIfAbsent(compSorted.get(i), (i + 0.5) / cn);
            }
            for (ScreenResult.StockScore s : candidates) {
                Double r = compToRank.get(s.getCompositeScore());
                s.setCompositeScore(r != null ? r : 0.5);
            }
            log.info("[Recommendation] 因子分位归一化完成: 候选{}只, factor_score区间[{},{}]",
                    cn,
                    String.format("%.4f", candidates.get(0).getCompositeScore()),
                    String.format("%.4f", candidates.get(candidates.size() - 1).getCompositeScore()));
        }

        int analysisCount = Math.min(topN, candidates.size());
        List<StockRecommendation> recommendations = new ArrayList<>();

        // P1-6: 并行执行个股深度分析，使用共享单例线程池 analysisExecutor，限制并发避免CH连接池耗尽

        List<java.util.concurrent.CompletableFuture<StockRecommendation>> futures = new ArrayList<>();
        for (int i = 0; i < analysisCount; i++) {
            ScreenResult.StockScore stock = candidates.get(i);
            String industry = codeToIndustry.getOrDefault(RecommendationMath.stripSuffix(stock.getSymbol()), "UNKNOWN");
            IndustryMomentum im = industryMomentumMap.get(industry);
            final int idx = i;

            java.util.concurrent.CompletableFuture<StockRecommendation> future =
                    java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                        try {
                            StockRecommendation rec = stockScoreFuser.analyzeAndFuse(stock, regime, actualDate, im, strategyId);
                            log.info("[Recommendation] 分析进度: {}/{} code={} name={} factorScore={} analysisScore={} finalScore={} tech={} money={} senti={} fund={} risk={} liq={}",
                                    idx + 1, analysisCount, rec.getStockCode(), rec.getStockName(),
                                    String.format("%.4f", rec.getFactorScore()),
                                    rec.getAnalysisScore(),
                                    String.format("%.4f", rec.getFinalScore()),
                                    rec.getTechnicalScore(), rec.getCapitalScore(), rec.getEventScore(), rec.getFundamentalScore(),
                                    rec.getRiskScore(), rec.getLiquidityScore());
                            return rec;
                        } catch (Exception e) {
                            log.warn("[Recommendation] 个股分析失败: code={} error={}", stock.getSymbol(), e.getMessage());
                            return null;
                        }
                    }, analysisExecutor);
            futures.add(future);
        }

        // 等待所有分析完成并收集结果
        for (java.util.concurrent.CompletableFuture<StockRecommendation> future : futures) {
            try {
                StockRecommendation rec = future.get(60, java.util.concurrent.TimeUnit.SECONDS);
                if (rec != null) {
                    recommendations.add(rec);
                }
            } catch (Exception e) {
                log.warn("[Recommendation] 并行分析获取结果失败: {}", e.getMessage());
            }
        }

        // Step 3.5: 批量填充 industry 和 marketCap（从 stock_info）
        fillIndustryAndMarketCap(recommendations);

        // Step 4: 排序 & 赋排名
        recommendations.sort((a, b) -> Double.compare(b.getFinalScore(), a.getFinalScore()));

        // Step 5: 行业分散化(动态限制, Phase A+C)
        recommendations = diversify(recommendations, industryMomentumMap);

        // Step 5.5: 过滤 SELL 推荐并截断到 topN
        int beforeFilter = recommendations.size();
        recommendations = recommendations.stream()
                .filter(r -> !"SELL".equals(r.getActionTag()))
                .collect(Collectors.toList());
        int sellFiltered = beforeFilter - recommendations.size();

        // Step 5.6: 高置信过滤（回测验证：仅 final_score>=门槛的档位次日真能跑赢，
        // 0.5~0.7 噪声/负收益区占推荐61%应剔除）
        List<StockRecommendation> highConv = recommendations.stream()
                .filter(r -> r.getFinalScore() != null && r.getFinalScore() >= HIGH_CONVICTION_FINAL_SCORE)
                .collect(Collectors.toList());
        if (highConv.size() >= MIN_HIGH_CONVICTION_PICKS) {
            recommendations = highConv;
            log.info("[Recommendation] 高置信过滤生效: 保留{}条 (final_score>={})",
                    recommendations.size(), HIGH_CONVICTION_FINAL_SCORE);
        } else {
            log.warn("[Recommendation] 高置信档不足({}/{}), 保底保留按final_score排序的top{}",
                    highConv.size(), MIN_HIGH_CONVICTION_PICKS, MIN_HIGH_CONVICTION_PICKS);
            recommendations = recommendations.stream()
                    .sorted((a, b) -> Double.compare(b.getFinalScore() == null ? 0 : b.getFinalScore(),
                            a.getFinalScore() == null ? 0 : a.getFinalScore()))
                    .limit(MIN_HIGH_CONVICTION_PICKS)
                    .collect(Collectors.toList());
        }

        // 截断到 topN（diversify 可能保留了超过 topN 的条目）
        if (recommendations.size() > topN) {
            recommendations = recommendations.subList(0, topN);
        }

        long buyCount = recommendations.stream().filter(r -> "BUY".equals(r.getActionTag())).count();
        if (buyCount == 0 && !recommendations.isEmpty()) {
            log.warn("[Recommendation] 策略[{}] 日期[{}] 无BUY推荐, 全部为HOLD, 共{}条 (过滤{}条SELL)",
                    strategyId, actualDate, recommendations.size(), sellFiltered);
        }
        if (sellFiltered > 0) {
            log.info("[Recommendation] 过滤{}条SELL推荐, 剩余{}条 (BUY={}, HOLD={})",
                    sellFiltered, recommendations.size(), buyCount, recommendations.size() - buyCount);
        }

        for (int i = 0; i < recommendations.size(); i++) {
            recommendations.get(i).setRankNum(i + 1);
        }

        // Step 6: 写入数据库（按 (strategy_id, recommend_date, weight_mode) 去重，先删旧写新）
        for (StockRecommendation rec : recommendations) {
            rec.setStrategyId(strategyId);
            rec.setRecommendDate(actualDate);
            rec.setWeightMode(effectiveWeightMode);
        }
        // 仅当指定了策略时才做清理（避免误删），按模式精准删除不影响其他模式的快照
        if (strategyId != null) {
            recommendationMapper.deleteByStrategyAndDateAndMode(strategyId, actualDate, effectiveWeightMode);
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
        return queryService.getLatestRecommendations();
    }

    /**
     * 获取指定策略+日期的推荐列表（不过滤模式，合并所有模式快照）
     */
    public List<StockRecommendation> getRecommendationsByStrategyAndDate(Long strategyId, LocalDate recommendDate) {
        return queryService.getRecommendationsByStrategyAndDate(strategyId, recommendDate);
    }

    /**
     * 获取指定策略+日期的推荐列表（按权重模式过滤）
     * @param weightMode 权重模式，null/空/ALL=不过滤
     */
    public List<StockRecommendation> getRecommendationsByStrategyAndDate(Long strategyId, LocalDate recommendDate, String weightMode) {
        return queryService.getRecommendationsByStrategyAndDate(strategyId, recommendDate, weightMode);
    }

    /**
     * 读侧补充：从 stock_info 填充 industry/marketCap，并修复旧数据的 actionTag 和 buyReason
     * <p>
     * 因为生成时的 fillIndustryAndMarketCap 只在新批次生成时执行，
     * 旧批次读出来后需要同样处理才能保证前端展示正确。
     */
    private List<StockRecommendation> enrichFromStockInfo(List<StockRecommendation> recs) {
        return queryService.enrichFromStockInfo(recs);
    }

    // ── 私有方法 ──

    /**
     * 追踪推荐表现（Phase 3.2）：次日 / 一周 / 一月收益 + 超额收益，并联动黑名单与策略置信度。
     * <p>
     * God Class 拆分 Phase 3：实现已迁移至 {@link RecommendationTracker}，对外方法签名不变。
     *
     * @return 更新的记录数
     */
    public int trackRecommendationPerformance() {
        return recommendationTracker.trackRecommendationPerformance();
    }

    /**
     * 获取推荐命中率统计
     *
     * @param strategyId    策略ID
     * @param recommendDate 推荐日期
     * @return { total, positive, hitRate, avgReturn }
     */
    public Map<String, Object> getHitRate(Long strategyId, LocalDate recommendDate) {
        return queryService.getHitRate(strategyId, recommendDate);
    }

    /**
     * 获取指定策略+日期的所有模式列表
     */
    public List<String> getModesByStrategyAndDate(Long strategyId, LocalDate recommendDate) {
        return queryService.getModesByStrategyAndDate(strategyId, recommendDate);
    }

    /**
     * 获取最近的策略+日期组合列表（含权重模式）
     */
    public List<Map<String, Object>> getStrategyDateCombos(int limit) {
        return queryService.getStrategyDateCombos(limit);
    }

    /**
     * 获取指定策略在最近 days 天内有推荐数据的日期列表（倒序）
     */
    public List<String> getDatesByStrategy(Long strategyId, int days) {
        return queryService.getDatesByStrategy(strategyId, days);
    }

    /**
     * 获取所有有推荐记录的策略列表（id + name）
     */
    public List<Map<String, Object>> strategiesWithData() {
        return queryService.strategiesWithData();
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
        return queryService.getBatchHistory(limit, strategyId);
    }

    /**
     * 获取指定策略+日期的最佳/最差股票（用于推荐复盘）
     * 按次日收益率排序，分别取 top3 / bottom3
     * 含深度归因分析：行业分布对比、市值中位数对比、因子/分析得分对比
     *
     * @return { best3: [...], worst3: [...], analysis: { industryDiff, marketCapDiff, scoreDiff, failurePatterns } }
     */
    public Map<String, Object> getBatchTopBottom(Long strategyId, LocalDate recommendDate) {
        return queryService.getBatchTopBottom(strategyId, recommendDate);
    }

    /**
     * 多维市场环境识别 (Phase 2)。
     * <p>
     * God Class 拆分 Phase 3：实现已整体迁移至 {@link MarketRegimeDetector}（方法体逐字搬运），
     * 此处仅保留薄委托，调用方与行为均不变。
     */
    private RegimeInfo detectRegime(LocalDate date) {
        return marketRegimeDetector.detectRegime(date);
    }

    /** 公开暴露 regime 名称，供 MarketRegimeCalendarService 的 detector 回调使用（无副作用） */
    public String detectRegimeName(LocalDate date) {
        return marketRegimeDetector.detectRegimeName(date);
    }

    /**
     * 优化④：判断最近 consecutiveDays 个交易日(含 date 当日)是否全部为 BEAR regime。
     * <p>
     * God Class 拆分 Phase 3：实现已迁移至 {@link MarketRegimeDetector}。
     */
    private boolean isConsecutiveBear(LocalDate date, int consecutiveDays) {
        return marketRegimeDetector.isConsecutiveBear(date, consecutiveDays);
    }


    /**
     * 计算推荐买入价（MA20 动态支撑位）。
     * <p>
     * God Class 拆分 Phase 3：实现已迁移至 {@link PricePlanCalculator}。
     */
    private Double calcSuggestedBuyPrice(String stockCode, LocalDate date) {
        return pricePlanCalculator.calcSuggestedBuyPrice(stockCode, date);
    }

    /**
     * 计算价格计划：止损价、止盈价、目标价、建议仓位 (#5 + #9)。
     * <p>
     * God Class 拆分 Phase 3：实现已迁移至 {@link PricePlanCalculator}。
     */
    private void calcPricePlan(StockRecommendation rec, AnalysisOverview overview) {
        pricePlanCalculator.calcPricePlan(rec, overview);
    }

    /**
     * 多因子选股
     *
     * @param strategyId 策略ID（必须）
     */
    private ScreenResult screenStocks(LocalDate date, Long strategyId,
                                      boolean useDynamicIc, String effectiveWeightMode,
                                      List<FactorDiagnostic> diagnostics) {
        return screenStocks(date, strategyId, useDynamicIc, effectiveWeightMode, diagnostics, null);
    }

    /**
     * 多因子选股（支持高级选项覆盖）
     * <p>
     * God Class 拆分 Phase 3：选股实现已迁移至 {@link CandidateScreener}；因子配置解析
     * （getFactorConfig / applyDynamicFactorWeights）仍属本类职责，故以 Supplier 形式传入，
     * 由 CandidateScreener 仅在非 PATTERN 分支求值，保持原有短路顺序不变。
     *
     * @param strategyId      策略ID（必须）
     * @param advancedOptions 高级选项（中性化/正交化/极值/标准化/均线），null 则使用默认
     */
    private ScreenResult screenStocks(LocalDate date, Long strategyId,
                                      boolean useDynamicIc, String effectiveWeightMode,
                                      List<FactorDiagnostic> diagnostics,
                                      AdvancedScreenOptions advancedOptions) {
        return candidateScreener.screenStocks(date, strategyId, effectiveWeightMode, advancedOptions, () -> {
            // 从策略因子配置获取因子列表
            List<ScreenRequest.FactorWeight> factors = getFactorConfig(strategyId);
            // 动态调整因子权重（基于IC），同时收集诊断信息
            if (useDynamicIc) {
                factors = applyDynamicFactorWeights(factors, date, effectiveWeightMode, diagnostics);
            }
            return factors;
        });
    }

    /**
     * 高级选股选项（仅手动触发推荐时由前端传入）
     */
    @Data
    public static class AdvancedScreenOptions {
        /** 中性化方法：NONE / INDUSTRY / MARKET_CAP / BOTH */
        private String neutralizationMethod;
        /** 正交化方法：NONE / SCHMIDT */
        private String orthogonalizationMethod;
        /** 极值处理方法：NONE / MAD / SIGMA3 / PERCENTILE */
        private String globalOutlierMethod;
        /** 标准化方法：NONE / ZSCORE / MINMAX / RANK */
        private String globalNormalizeMethod;
        /** 均线过滤（多头排列） */
        private ScreenRequest.MaPositionFilter maPositionFilter;
    }


    // ==================== P2/P3/P4 辅助方法 ====================


    /** 委托 -> IndustryRotationService（Phase4 拆出） */
    private Map<String, String> buildCodeToIndustryMap(List<ScreenResult.StockScore> candidates) {
        return industryRotationService.buildCodeToIndustryMap(candidates);
    }
    /** 委托 -> IndustryRotationService（Phase4 拆出） */
    private Map<String, IndustryMomentum> computeIndustryMomentum(RegimeInfo regime, LocalDate date) {
        return industryRotationService.computeIndustryMomentum(regime, date);
    }
    /** 委托 -> IndustryRotationService（Phase4 拆出） */
    private List<StockRecommendation> diversify(List<StockRecommendation> recommendations, Map<String, IndustryMomentum> industryMomentumMap) {
        return industryRotationService.diversify(recommendations, industryMomentumMap);
    }
    /** 委托 -> IndustryRotationService（Phase4 拆出） */
    private void fillIndustryAndMarketCap(List<StockRecommendation> recs) {
        industryRotationService.fillIndustryAndMarketCap(recs);
    }
    /** 委托 -> CandidateScreener（Phase4 拆出） */
    private List<String> getConceptNames(Long strategyId) {
        return candidateScreener.getConceptNames(strategyId);
    }
    /** 委托 -> CandidateScreener（Phase4 拆出） */
    private List<String> getExcludeIndustries(Long strategyId) {
        return candidateScreener.getExcludeIndustries(strategyId);
    }
    /** 委托 -> FactorWeightResolver（Phase4 拆出） */
    private List<ScreenRequest.FactorWeight> getFactorConfig(Long strategyId) {
        return factorWeightResolver.getFactorConfig(strategyId);
    }
    /** 委托 -> CandidateScreener（Phase4 拆出） */
    private List<String> getIncludeIndustries(Long strategyId) {
        return candidateScreener.getIncludeIndustries(strategyId);
    }
    /** 委托 -> FactorWeightResolver（Phase4 拆出） */
    private String resolveWeightMode(Long strategyId, String requestWeightMode) {
        return factorWeightResolver.resolveWeightMode(strategyId, requestWeightMode);
    }
    /** 委托 -> FactorWeightResolver（Phase4 拆出） */
    private List<ScreenRequest.FactorWeight> applyDynamicFactorWeights(
            List<ScreenRequest.FactorWeight> factors, LocalDate date,
            String weightMode, List<FactorDiagnostic> diagnostics) {
        return factorWeightResolver.applyDynamicFactorWeights(factors, date, weightMode, diagnostics);
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

}
