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
     * P1: 默认IR预筛选阈值
     * IR = |IC均值| / IC标准差，衡量因子信号的稳定性
     * 0.1: 剔除IC波动过大（不稳定）的噪声因子，保留信号稳定的因子
     * 比IC绝对值阈值更合理：IC高但波动大的因子（如VOL20 IC=0.063但IR=0.19）仍保留，
     * 而IC低且稳定的因子（如VAL_FCF_YIELD IC=0.032但IR=1.16）也不会被误杀
     */
    private static final double DEFAULT_IR_THRESHOLD = 0.1;
    /**
     * P2: 默认半衰期（交易日）
     */
    private static final int DEFAULT_HALFLIFE_DAYS = 20;
    /**
     * P3: ICW模式单因子权重上限（占比）
     * 防止强IC因子（如SIZE IC=0.052）主导排名导致策略趋同
     * 超出部分按比例重新分配给其他因子
     */
    private static final double MAX_ICW_WEIGHT_PCT = 0.35;

    /**
     * P1-4: 噪声因子|IC|阈值
     * |IC| < 此值的因子直接剔除而非反转——噪声因子的反转≠有效信号
     * 典型噪声因子: MOM5(IC=-0.03), VOLUME_RATIO(IC=-0.033) 等
     */
    private static final double NOISE_FACTOR_IC_THRESHOLD = 0.015;
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
    /**
     * 优化X：强制保留因子白名单。这些因子即使与簇内其他因子高相关（触发拥挤度剔除），
     * 也不被剔除，确保其权重（尤其是高IC真alpha如EARNINGS_SURPRISE）在组合中生效。
     * 背景：EARNINGS_SURPRISE 与 SIZE 相关性高(corr≥0.84)被拥挤度剔除，且无IC历史时
     * 不在 icMap 中永不当代表，导致在ICW管线被CROWDING_DROPPED，权重完全失效。
     */
    private static final Set<String> FORCE_KEEP_FACTORS = Set.of("EARNINGS_SURPRISE");
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
                                 RecommendationTracker recommendationTracker) {
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

        // P1-6: 并行执行个股深度分析，限制并发线程数避免CH连接池耗尽
        java.util.concurrent.ExecutorService analysisExecutor =
                java.util.concurrent.Executors.newFixedThreadPool(
                        Math.min(ANALYSIS_PARALLELISM, analysisCount));

        List<java.util.concurrent.CompletableFuture<StockRecommendation>> futures = new ArrayList<>();
        for (int i = 0; i < analysisCount; i++) {
            ScreenResult.StockScore stock = candidates.get(i);
            String industry = codeToIndustry.getOrDefault(RecommendationMath.stripSuffix(stock.getSymbol()), "UNKNOWN");
            IndustryMomentum im = industryMomentumMap.get(industry);
            final int idx = i;

            java.util.concurrent.CompletableFuture<StockRecommendation> future =
                    java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                        try {
                            StockRecommendation rec = analyzeAndFuse(stock, regime, actualDate, im, strategyId);
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
        analysisExecutor.shutdown();

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
        double techPct = RecommendationMath.safeDiv(rec.getTechnicalScore(), 30.0);    // 技术面满分30
        double moneyPct = RecommendationMath.safeDiv(rec.getCapitalScore(), 25.0);    // 资金面满分25
        double eventPct = RecommendationMath.safeDiv(rec.getEventScore(), 25.0);      // 事件面满分25
        double fundPct = RecommendationMath.safeDiv(rec.getFundamentalScore(), 29.0); // 基本面满分29

        // P1-2: 风险和流动性评分归一化
        double riskPct = RecommendationMath.safeDiv(rec.getRiskScore(), 15.0);       // 风险满分15
        double liqPct = RecommendationMath.safeDiv(rec.getLiquidityScore(), 10.0);   // 流动性满分10

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
        queryService.fillIndustryAndMarketCap(recs);
    }

    /**
     * 根据行业名查找所属相关组
     * P2-8: 优先使用动态行业相关分组，回退到静态INDUSTRY_CORR_GROUPS
     */
    private String getCorrGroup(String industry) {
        // P2-8: 优先使用动态分组
        try {
            List<List<String>> dynamicGroups = dynamicIndustryCorrService.getDynamicCorrGroups();
            for (List<String> group : dynamicGroups) {
                if (group.contains(industry)) return group.getFirst();
            }
        } catch (Exception e) {
            log.debug("[Recommendation] P2-8 动态行业分组获取失败, 回退到静态: {}", e.getMessage());
        }
        // 回退到静态分组
        for (List<String> group : INDUSTRY_CORR_GROUPS) {
            if (group.contains(industry)) return group.getFirst();
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
                // P2-8: 优先查动态分组，回退到静态
                List<List<String>> allGroups = null;
                try { allGroups = dynamicIndustryCorrService.getDynamicCorrGroups(); } catch (Exception ignored) {}
                if (allGroups == null) allGroups = INDUSTRY_CORR_GROUPS;
                for (List<String> g : allGroups) {
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
                    """, lookbackStart, targetDate);
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
            log.info("[Recommendation] {}", sb);

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

            // P2-1后：使用momentumScore重新校准fusionBonus
            // 牛市：高动量行业给奖励（动量延续）；熊市/回调：反转——高动量行业惩罚(追高易补跌)，低动量奖励(均值回归)
            boolean bearMarket = regime != null && "BEAR".equals(regime.regime);
            for (IndustryMomentum im : result.values()) {
                if (bearMarket) {
                    // 熊市反转逻辑：低动量奖励、高动量惩罚
                    if (im.momentumScore > 0.7) im.fusionBonus = -0.06;
                    else if (im.momentumScore > 0.55) im.fusionBonus = -0.03;
                    else if (im.momentumScore > 0.45) im.fusionBonus = 0.0;
                    else if (im.momentumScore > 0.3) im.fusionBonus = 0.03;
                    else im.fusionBonus = 0.06;
                } else {
                    if (im.momentumScore > 0.7) im.fusionBonus = 0.06;
                    else if (im.momentumScore > 0.55) im.fusionBonus = 0.03;
                    else if (im.momentumScore > 0.45) im.fusionBonus = 0.0;
                    else if (im.momentumScore > 0.3) im.fusionBonus = -0.03;
                    else im.fusionBonus = -0.06;
                }
            }
            log.info("[Recommendation] P2-1 行业动量增强完成，fusionBonus已按momentumScore校准 (bear={})", bearMarket);

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
            double latestClose = closes.getLast();
            double ma20 = RecommendationMath.avg(closes, 20);
            double ma60 = RecommendationMath.avg(closes, 60);
            // 引入0.5%缓冲带，避免单日噪声导致Regime频繁切换
            double buffer = latestClose * 0.005;
            boolean bullishTrend = latestClose > ma20 + buffer && ma20 > ma60 + buffer;
            boolean bearishTrend = latestClose < ma20 - buffer && ma20 < ma60 - buffer;

            // ── 维度2: ATR 波动率 ──
            double atr20 = RecommendationMath.calcATR(highs, lows, closes, 20);
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
                .map(s -> RecommendationMath.stripSuffix(s.getSymbol()))
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

    /**
     * P1+P2: 动态调整因子权重（基于IC历史表现 + 衰减加权 + 预筛选 + 方向对齐）
     * <p>
     * 规则：
     * - 使用 FactorAnalysisService.quickFactorIcSnapshot() 计算衰减加权IC
     * - 预筛选：IR &lt; irThreshold 的因子被剔除（信号不稳定）
     * - 方向对齐：负IC因子自动反转direction，使用|IC|参与加权
     * - 权重分配（由 weightMode 决定）：
     * EQW  = 等权分配
     * ICW  = 按|IC|比例分配
     * OPT  = 按 1/σ²(IC) 分配（稳定性越高权重越大）
     * STATIC = 不调整（由调用方处理，不会进入此方法）
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

        // 当前 regime：用于白名单 regime 守卫（SIDEWAYS 下不再强制保留 EARNINGS_SURPRISE），
        // 与 ICW 过滤使用同一 regime 来源，保持一致
        String currentRegime = (regimeCalendarService != null && date != null)
                ? regimeCalendarService.getRegime(date) : "SIDEWAYS";

        // Resolve reference date
        LocalDate refDate = date != null ? date : LocalDate.now();
        LocalDate effectiveIcDate = factorIcService.getLatestCommonIcDate(factorCodes);
        if (effectiveIcDate != null && effectiveIcDate.isBefore(refDate)) {
            refDate = effectiveIcDate;
        }

        // P1+P2: 使用衰减加权IC快照
        // P2: 动态半衰期（基于市场波动率分位数自适应调整）
        int halflife = computeAdaptiveHalflife(refDate);
        Map<String, com.quant.platform.factor.service.FactorAnalysisService.FactorIcSnapshot> snapshots =
                factorAnalysisService.quickFactorIcSnapshot(
                        factorCodes, refDate, 60, DEFAULT_IR_THRESHOLD, halflife);

        // P3: 因子拥挤度检测与去重
        Set<String> crowdingDropped = applyCrowdingFilter(factorCodes, refDate, snapshots);
        // P4: 财务因子季频IC校正（返回校正数量，snapshots 被原地修改）
        int quarterlyCorrected = applyQuarterlyIcCorrection(factorCodes, refDate, snapshots);
        // P5: IC季度一致性校验（方向不稳定因子降权或剔除）
        int consistencyDropped = applyIcConsistencyCheck(factorCodes, refDate, snapshots);

        log.info("[DynamicWeight] IC快照完成: mode={}, {}个因子, IR阈值={}, 半衰={}天, 保留{}个 (拥挤度剔除{}, 一致性剔除{})",
                weightMode, factorCodes.size(), DEFAULT_IR_THRESHOLD, halflife,
                snapshots.values().stream().filter(s -> "KEPT".equals(s.status)).count(),
                crowdingDropped.size(), consistencyDropped);

        // 筛选保留的因子
        List<FactorAnalysisService.FactorIcSnapshot> keptSnapshots = snapshots.values().stream()
                .filter(s -> "KEPT".equals(s.status))
                .toList();

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

            // 优化X：白名单因子强制保留，权重=配置权重，不受拥挤度/噪声/无IC等任何剔除影响
            // 白名单 regime 守卫：SIDEWAYS 体制下不再强制保留，交由 regime-aware ICW 决定权重。
            // 否则 EARNINGS_SURPRISE 在震荡市 ~0/反向 IC 被强制 35% 权重，正是 SIDEWAYS 退步根因。
            if (FORCE_KEEP_FACTORS.contains(fc) && !"SIDEWAYS".equals(currentRegime)) {
                adjustedFw.setWeight(originalWeight);
                adjustedFw.setDirection(originalDirection);
                diag.action = "FORCE_KEEP";
                diag.adjustedWeight = originalWeight;
                diag.icMean = snap != null ? snap.icMean : 0;
                diag.reason = "白名单强制保留（权重=配置权重" + originalWeight + "）";
                log.info("[DynamicWeight] 因子 {} 白名单强制保留, 权重={}", fc, originalWeight);
                adjusted.add(adjustedFw);
                diagnostics.add(diag);
                keptCount++;
                continue;
            }

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
                // IR < 阈值，剔除（信号不稳定）
                adjustedFw.setWeight(0.0);
                adjustedFw.setDirection(originalDirection);
                diag.action = "DROPPED";
                diag.icMean = snap.icMean;
                diag.adjustedWeight = 0;
                diag.reason = String.format("IR=%.4f < 阈值%.2f，信号不稳定剔除（IC=%.4f, IC_std=%.4f, 半衰=%d天）",
                        snap.ir, DEFAULT_IR_THRESHOLD, snap.icMean, snap.icStd, halflife);
                log.info("[DynamicWeight] 因子 {} IR={} < {}, 剔除 (IC={}, std={})",
                        fc, String.format("%.4f", snap.ir), DEFAULT_IR_THRESHOLD,
                        String.format("%.4f", snap.icMean), String.format("%.4f", snap.icStd));
                droppedCount++;
            } else if ("CROWDING_DROPPED".equals(snap.status) || "CONSISTENCY_DROPPED".equals(snap.status)) {
                // P3: 因子拥挤度剔除 / P5: IC一致性剔除
                adjustedFw.setWeight(0.0);
                adjustedFw.setDirection(originalDirection);
                diag.action = snap.status;
                diag.icMean = snap.icMean;
                diag.adjustedWeight = 0;
                diag.reason = snap.assessment != null ? snap.assessment : "因子被剔除";
                log.info("[DynamicWeight] 因子 {} {}: {}", fc, snap.status, diag.reason);
                droppedCount++;
            } else {
                // KEPT: 方向对齐 + |IC|加权
                double absIc = snap.absIc();

                // P1-4: 噪声因子剔除 —— |IC| < 阈值直接丢弃，不反转
                if (absIc < NOISE_FACTOR_IC_THRESHOLD) {
                    adjustedFw.setWeight(0.0);
                    adjustedFw.setDirection(originalDirection);
                    diag.action = "NOISE_DROPPED";
                    diag.icMean = snap.icMean;
                    diag.adjustedWeight = 0;
                    diag.reason = String.format(
                            "|IC|=%.4f < 噪声阈值%.3f，信号过弱剔除（IC=%.4f, IR=%.4f, 半衰=%d天）",
                            absIc, NOISE_FACTOR_IC_THRESHOLD, snap.icMean, snap.ir, halflife);
                    log.info("[DynamicWeight] 因子 {} |IC|={} < {}, 噪声剔除 (IC={}, IR={})",
                            fc, String.format("%.4f", absIc), NOISE_FACTOR_IC_THRESHOLD,
                            String.format("%.4f", snap.icMean), String.format("%.4f", snap.ir));
                    droppedCount++;
                    diagnostics.add(diag);
                    adjusted.add(adjustedFw);
                    continue;
                }

                // 方向对齐：负IC → 反转direction
                int alignedDirection = snap.icSign < 0 ? -originalDirection : originalDirection;
                adjustedFw.setDirection(alignedDirection);

                // 权重按 weightMode 分配
                double newWeight;
                String action = switch (weightMode) {
                    case "EQW" -> {
                        // 等权：保留的因子平均分配
                        newWeight = originalWeight / Math.max(keptSnapshots.size(), 1);
                        yield "KEPT_EQW";
                    }
                    case "OPT" -> {
                        // 逆方差：按 1/σ²(IC) 分配（稳定性越高权重越大）
                        if (optSum > 1e-9) {
                            newWeight = originalWeight * (1.0 / Math.max(snap.icStd * snap.icStd, 1e-9) / optSum);
                        } else {
                            newWeight = originalWeight / Math.max(keptSnapshots.size(), 1);
                        }
                        yield "KEPT_OPT";
                    }
                    default -> {
                        // ICW: |IC|加权
                        if (sumAbsIc > 1e-9) {
                            newWeight = originalWeight * (absIc / sumAbsIc);
                        } else {
                            newWeight = originalWeight / Math.max(keptSnapshots.size(), 1);
                        }
                        yield "KEPT_ICW";
                    }
                };
                adjustedFw.setWeight(newWeight);
                diag.action = action;

                diag.icMean = snap.icMean;
                diag.adjustedWeight = newWeight;
                diag.reason = String.format(
                        "%s: IC=%.4f (半衰%d天), |IC|=%.4f, 方向%s, 新权重=%.4f",
                        weightMode, snap.icMean, halflife, absIc,
                        snap.icSign < 0 ? "↓取反(对齐)" : "↑正向",
                        newWeight);
                log.info("[DynamicWeight] 因子 {} {} IC={} (|IC|={}) 方向={} 权重: {}->{}",
                        fc, weightMode, snap.icMean, absIc,
                        snap.icSign < 0 ? "取反" : "正向",
                        originalWeight, newWeight);
                keptCount++;
            }

            diagnostics.add(diag);
            adjusted.add(adjustedFw);
        }

        // P6: ICW权重上限——防止单因子主导排名导致策略趋同
        if ("ICW".equals(weightMode)) {
            applyWeightCap(adjusted, MAX_ICW_WEIGHT_PCT);
        }

        log.info("[DynamicWeight] 完成: mode={}, 保留{}/剔除{}/无数据{}, |IC|和={}, 半衰={}天",
                weightMode, keptCount, droppedCount, noDataCount, sumAbsIc, halflife);

        return adjusted;
    }

    /**
     * P6: ICW权重上限——迭代式cap & redistribute
     * 1. 计算各因子权重占比（相对于有效因子权重总和）
     * 2. 超过maxPct的因子截断为maxPct，溢出部分按比例分配给未截断因子
     * 3. 重复直到所有因子占比≤maxPct（最多N-1轮）
     */
    private void applyWeightCap(List<ScreenRequest.FactorWeight> adjusted, double maxPct) {
        List<ScreenRequest.FactorWeight> active = new ArrayList<>();
        for (ScreenRequest.FactorWeight fw : adjusted) {
            if (fw.getWeight() > 0) active.add(fw);
        }
        if (active.size() <= 1) return;

        double totalWeight = active.stream().mapToDouble(ScreenRequest.FactorWeight::getWeight).sum();
        if (totalWeight <= 0) return;

        Set<String> capped = new HashSet<>();
        for (int iter = 0; iter < active.size() - 1; iter++) {
            // 1. 找出超出上限的因子
            List<ScreenRequest.FactorWeight> overLimit = new ArrayList<>();
            double uncappedSum = 0;
            for (ScreenRequest.FactorWeight fw : active) {
                if (capped.contains(fw.getFactorCode())) continue;
                double pct = fw.getWeight() / totalWeight;
                if (pct > maxPct) {
                    overLimit.add(fw);
                } else {
                    uncappedSum += fw.getWeight();
                }
            }
            if (overLimit.isEmpty()) break;

            // 2. 截断超限因子
            for (ScreenRequest.FactorWeight fw : overLimit) {
                double oldW = fw.getWeight();
                double oldPct = oldW / totalWeight;
                fw.setWeight(totalWeight * maxPct);
                capped.add(fw.getFactorCode());
                log.info("[WeightCap] 因子 {} 权重 {}->{} (占比 {}->{})",
                        fw.getFactorCode(),
                        String.format("%.4f", oldW),
                        String.format("%.4f", fw.getWeight()),
                        String.format("%.0f%%", oldPct * 100),
                        String.format("%.0f%%", maxPct * 100));
            }

            // 3. 将溢出权重按比例分配给未截断因子
            double cappedTotal = 0;
            for (ScreenRequest.FactorWeight fw : active) {
                if (capped.contains(fw.getFactorCode())) {
                    cappedTotal += fw.getWeight();
                }
            }
            double uncappedTarget = totalWeight - cappedTotal;
            if (uncappedSum > 0 && uncappedTarget > 0) {
                double scale = uncappedTarget / uncappedSum;
                for (ScreenRequest.FactorWeight fw : active) {
                    if (!capped.contains(fw.getFactorCode())) {
                        double oldW = fw.getWeight();
                        fw.setWeight(oldW * scale);
                        if (Math.abs(fw.getWeight() - oldW) > 0.001) {
                            log.debug("[WeightCap] 因子 {} 重分配 {}->{}",
                                    fw.getFactorCode(),
                                    String.format("%.4f", oldW),
                                    String.format("%.4f", fw.getWeight()));
                        }
                    }
                }
            }
        }
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
                if (filterValue instanceof Number) {
                    fw.setFilterValue(((Number) filterValue).doubleValue());
                }
                result.add(fw);
            }

            // P3-11: 过滤 DEGRADED 因子（降级因子不参与选股/推荐）
            Set<String> degradedCodes = new HashSet<>();
            List<com.quant.platform.factor.domain.FactorDefinition> degradedFactors = factorDefinitionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.quant.platform.factor.domain.FactorDefinition>()
                    .eq(com.quant.platform.factor.domain.FactorDefinition::getStatus,
                        com.quant.platform.factor.domain.FactorDefinition.FactorStatus.DEGRADED));
            for (com.quant.platform.factor.domain.FactorDefinition df : degradedFactors) {
                degradedCodes.add(df.getFactorCode());
            }
            if (!degradedCodes.isEmpty()) {
                int before = result.size();
                result.removeIf(fw -> fw.getFactorCode() != null && degradedCodes.contains(fw.getFactorCode()));
                log.warn("[Recommendation] P3-11 过滤DEGRADED因子: {} → {} (排除: {})",
                    before, result.size(), degradedCodes);
            }

            log.info("[Recommendation] 从策略[{}]加载因子配置: {}个因子(已排除DEGRADED)", strategy.getStrategyName(), result.size());
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
     * 从策略 filterConfigJson 获取行业白名单（includeIndustries）
     * 配置白名单后，只有属于白名单行业的股票才能进入候选池
     */
    @SuppressWarnings("unchecked")
    private List<String> getIncludeIndustries(Long strategyId) {
        if (strategyId == null) {
            return List.of();
        }
        StrategyDefinition strategy = strategyDefinitionMapper.selectById(strategyId);
        if (strategy == null || strategy.getFilterConfigJson() == null || strategy.getFilterConfigJson().isEmpty()) {
            return List.of();
        }
        try {
            Map<String, Object> filterConfig = objectMapper.readValue(strategy.getFilterConfigJson(), Map.class);
            Object include = filterConfig.get("includeIndustries");
            if (include instanceof List && !((List<?>) include).isEmpty()) {
                List<String> result = ((List<?>) include).stream()
                        .map(Object::toString)
                        .collect(Collectors.toList());
                log.info("[Recommendation] 从策略[{}]加载行业白名单: {}个", strategy.getStrategyName(), result.size());
                return result;
            }
        } catch (Exception e) {
            log.warn("[Recommendation] 策略行业白名单解析失败 strategyId={}", strategyId, e);
        }
        return List.of();
    }

    /**
     * 从策略 filterConfigJson 获取概念板块名称列表（conceptNames）
     * 配置后，从 stock_concept 表加载对应概念成分股作为候选池白名单
     */
    @SuppressWarnings("unchecked")
    private List<String> getConceptNames(Long strategyId) {
        if (strategyId == null) {
            return List.of();
        }
        StrategyDefinition strategy = strategyDefinitionMapper.selectById(strategyId);
        if (strategy == null || strategy.getFilterConfigJson() == null || strategy.getFilterConfigJson().isEmpty()) {
            return List.of();
        }
        try {
            Map<String, Object> filterConfig = objectMapper.readValue(strategy.getFilterConfigJson(), Map.class);
            Object concepts = filterConfig.get("conceptNames");
            if (concepts instanceof List && !((List<?>) concepts).isEmpty()) {
                List<String> result = ((List<?>) concepts).stream()
                        .map(Object::toString)
                        .collect(Collectors.toList());
                log.info("[Recommendation] 从策略[{}]加载概念板块: {}", strategy.getStrategyName(), result);
                return result;
            }
        } catch (Exception e) {
            log.warn("[Recommendation] 策略概念板块配置解析失败 strategyId={}", strategyId, e);
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
        String pureCode = RecommendationMath.stripSuffix(stock.getSymbol());
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
            rec.setActionTag(RecommendationMath.mapActionTag(overview.getAction()));
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

        // #5+#9: 计算价格计划（止损/止盈/目标价/仓位），依赖 riskScore+liquidityScore
        calcPricePlan(rec, overview);

        // 新闻事件加分：估值修复/事件驱动策略，如果近30天有利好事件(增持/回购/业绩预增)，额外加分
        String strategyCode = "";
        if (strategyId != null) {
            try {
                StrategyDefinition strat = strategyDefinitionMapper.selectById(strategyId);
                strategyCode = strat != null ? strat.getStrategyCode() : "";
                boolean useEventBoost = "VALUATION_RECOVERY_LLM".equals(strategyCode)
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
                    String qvixNote;
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
                    rec.setBuyReason(existing + " | " + qvixNote);
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
            double atrPct = overview.getAtr() / currentPrice.doubleValue();
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
     * P2: 动态半衰期计算
     * 基于沪深300收益率波动率分位数，调用 FactorAnalysisService.adaptiveHalflife()
     */
    private int computeAdaptiveHalflife(LocalDate refDate) {
        try {
            List<com.quant.platform.market.domain.MarketDailyBar> hist =
                    marketDataService.getBarsInRange(SSE300_CODE, refDate.minusDays(60), refDate);
            if (hist == null || hist.size() < 20) {
                log.warn("[DynamicWeight] P2 沪深300历史数据不足({}), 使用默认半衰期{}天",
                        hist == null ? "null" : hist.size(), DEFAULT_HALFLIFE_DAYS);
                return DEFAULT_HALFLIFE_DAYS;
            }
            // 计算20日收益率(seq: close[t]/close[t-1] - 1)
            double[] returns = new double[hist.size() - 1];
            for (int i = 1; i < hist.size(); i++) {
                double prev = hist.get(i - 1).getClose().doubleValue();
                double curr = hist.get(i).getClose().doubleValue();
                returns[i - 1] = (prev > 0) ? (curr / prev - 1) : 0;
            }
            double vol = RecommendationMath.std(returns);
            // 波动率分位数估算（假设市场波动率中值~12%，范围5%~25%）
            double volatilityPercentile = Math.max(0, Math.min(1, (vol - 0.05) / 0.20 + 0.375));
            int halflife = com.quant.platform.factor.service.FactorAnalysisService.adaptiveHalflife(volatilityPercentile);
            log.info("[DynamicWeight] P2 动态半衰期: 20日波动率={}, 分位数~{}, 半衰期={}天",
                    vol, volatilityPercentile, halflife);
            return halflife;
        } catch (Exception e) {
            log.warn("[DynamicWeight] P2 动态半衰期计算失败: {}, 使用默认值", e.getMessage());
            return DEFAULT_HALFLIFE_DAYS;
        }
    }

    /**
     * P3: 因子拥挤度过滤
     * 调用 FactorCorrelationService.detectCrowding()，将冗余因子的 status 设为 CROWDING_DROPPED
     */
    private Set<String> applyCrowdingFilter(
            List<String> factorCodes, LocalDate refDate,
            Map<String, com.quant.platform.factor.service.FactorAnalysisService.FactorIcSnapshot> snapshots) {
        Set<String> dropped = new HashSet<>();
        try {
            LocalDate startDate = refDate.minusDays(60);
            // 构建 icSnapshot Map（只传 KEPT 因子）
            Map<String, Double> icMap = new HashMap<>();
            for (Map.Entry<String, com.quant.platform.factor.service.FactorAnalysisService.FactorIcSnapshot> e : snapshots.entrySet()) {
                if ("KEPT".equals(e.getValue().status)) {
                    icMap.put(e.getKey(), e.getValue().icMean);
                }
            }
            List<com.quant.platform.factor.service.FactorCorrelationService.FactorCluster> clusters =
                    factorCorrelationService.detectCrowding(factorCodes, startDate, refDate, 0.70, icMap);
            for (com.quant.platform.factor.service.FactorCorrelationService.FactorCluster cluster : clusters) {
                for (String redundant : cluster.redundantFactors) {
                    // 优化X：白名单因子强制保留，跳过拥挤度剔除
                    if (FORCE_KEEP_FACTORS.contains(redundant)) {
                        log.info("[DynamicWeight] 因子 {} 在强制保留白名单，跳过拥挤度剔除 (簇代表={})", redundant, cluster.representative);
                        continue;
                    }
                    com.quant.platform.factor.service.FactorAnalysisService.FactorIcSnapshot snap = snapshots.get(redundant);
                    if (snap != null && "KEPT".equals(snap.status)) {
                        snap.status = "CROWDING_DROPPED";
                        snap.assessment = "拥挤度剔除: 与" + cluster.representative + "相关性过高(corr≥" + String.format("%.2f", cluster.maxCorrelation) + ")";
                        dropped.add(redundant);
                    }
                }
            }
            log.info("[DynamicWeight] P3 拥挤度过滤: {}个簇, 剔除{}个冗余因子", clusters.size(), dropped.size());
        } catch (Exception e) {
            log.warn("[DynamicWeight] P3 拥挤度过滤失败: {}", e.getMessage());
        }
        return dropped;
    }

    // ==================== P2/P3/P4 辅助方法 ====================

    /**
     * P4: 财务因子季频IC校正
     * 对 FIN_* 前缀的因子，用季频IC替换日频IC（更符合财务数据公告节奏）
     *
     * @return 被校正的因子数量
     */
    private int applyQuarterlyIcCorrection(
            List<String> factorCodes, LocalDate refDate,
            Map<String, com.quant.platform.factor.service.FactorAnalysisService.FactorIcSnapshot> snapshots) {
        int corrected = 0;
        for (String fc : factorCodes) {
            if (!factorMetaCache.isFinancial(fc)) continue;
            try {
                com.quant.platform.factor.service.QuarterlyFactorAnalysisService.QuarterlyIcResult qr =
                        quarterlyFactorAnalysisService.computeQuarterlyIc(fc, refDate.minusMonths(18), refDate, 5, true);
                if (qr != null && qr.quarterCount >= 3) {
                    com.quant.platform.factor.service.FactorAnalysisService.FactorIcSnapshot snap = snapshots.get(fc);
                    if (snap != null && Math.abs(qr.icMean) > Math.abs(snap.icMean) * 0.5) {
                        // 用季频IC替换（要求季频IC信号不能太弱）
                        double oldIc = snap.icMean;
                        snap.icMean = qr.icMean;
                        snap.icStd = qr.icStd;
                        snap.assessment = (snap.assessment != null ? snap.assessment + "; " : "") + "季频IC校正(" + String.format("%.4f", oldIc) + "→" + String.format("%.4f", qr.icMean) + ")";
                        corrected++;
                        log.info("[DynamicWeight] P4 季频IC校正: {} 日频IC={} → 季频IC={} ({}个季度)",
                                fc, oldIc, qr.icMean, qr.quarterCount);
                    }
                }
            } catch (Exception e) {
                log.debug("[DynamicWeight] P4 季频IC校正跳过: {} error={}", fc, e.getMessage());
            }
        }
        if (corrected > 0) {
            log.info("[DynamicWeight] P4 季频IC校正完成: {}/{}个财务因子已校正", corrected, factorCodes.stream().filter(fc -> factorMetaCache.isFinancial(fc)).count());
        }
        return corrected;
    }

    /**
     * P5: IC季度一致性校验
     * 检查因子近4个季度IC方向是否一致：
     *   - IC正占比 < 25%（4季度中≤1个正）→ 剔除（IC方向不稳定，无预测价值）
     *   - IC正占比 25-50% → 降权50%（方向不稳定但保留弱信号）
     *   - IC正占比 >= 50% → 正常保留
     *
     * @return 被剔除的因子数量
     */
    private int applyIcConsistencyCheck(
            List<String> factorCodes, LocalDate refDate,
            Map<String, com.quant.platform.factor.service.FactorAnalysisService.FactorIcSnapshot> snapshots) {
        int dropped = 0;
        int penalized = 0;
        for (String fc : factorCodes) {
            com.quant.platform.factor.service.FactorAnalysisService.FactorIcSnapshot snap = snapshots.get(fc);
            if (snap == null || !"KEPT".equals(snap.status)) continue;
            try {
                // 查询近15个月的IC记录（覆盖4-5个季度）
                LocalDate startDate = refDate.minusMonths(15);
                com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.quant.platform.factor.ic.domain.FactorIcRecord> wrapper =
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
                wrapper.eq(com.quant.platform.factor.ic.domain.FactorIcRecord::getFactorCode, fc)
                       .eq(com.quant.platform.factor.ic.domain.FactorIcRecord::getForwardDays, 5)
                       .ge(com.quant.platform.factor.ic.domain.FactorIcRecord::getTradeDate, startDate)
                       .le(com.quant.platform.factor.ic.domain.FactorIcRecord::getTradeDate, refDate)
                       .orderByDesc(com.quant.platform.factor.ic.domain.FactorIcRecord::getTradeDate);
                List<com.quant.platform.factor.ic.domain.FactorIcRecord> records =
                        factorIcRecordMapper.selectList(wrapper);
                if (records == null || records.size() < 4) continue; // 数据不足跳过

                // 按季度分组，取每季度平均IC
                Map<String, List<Double>> quarterlyIc = new LinkedHashMap<>();
                for (var r : records) {
                    if (r.getTradeDate() == null || r.getIcValue() == null) continue;
                    String q = r.getTradeDate().getYear() + "-Q" + ((r.getTradeDate().getMonthValue() - 1) / 3 + 1);
                    quarterlyIc.computeIfAbsent(q, k -> new ArrayList<>()).add(r.getIcValue());
                }
                if (quarterlyIc.size() < 2) continue;

                long positiveQuarters = quarterlyIc.values().stream()
                        .mapToDouble(qs -> qs.stream().mapToDouble(d -> d).average().orElse(0))
                        .filter(avg -> avg > 0)
                        .count();
                int totalQuarters = quarterlyIc.size();
                double positiveRatio = (double) positiveQuarters / totalQuarters;

                if (positiveRatio < 0.25) {
                    // 方向极不稳定，剔除
                    snap.status = "CONSISTENCY_DROPPED";
                    snap.assessment = String.format("IC季度一致性剔除: %d/%d季度IC为正(占比%.0f%%), 方向不稳定",
                            positiveQuarters, totalQuarters, positiveRatio * 100);
                    dropped++;
                    log.info("[DynamicWeight] P5 一致性剔除: {} {}/{}季度正({:.0f}%)", fc, positiveQuarters, totalQuarters, positiveRatio * 100);
                } else if (positiveRatio < 0.50) {
                    // 方向不稳定，降权50%
                    String oldAssessment = snap.assessment;
                    snap.assessment = (oldAssessment != null ? oldAssessment + "; " : "") +
                            String.format("IC一致性降权50%%: %d/%d季度正", positiveQuarters, totalQuarters);
                    // 通过降低icMean来间接降权（ICW模式下权重∝|IC|）
                    snap.icMean *= 0.5;
                    penalized++;
                    log.info("[DynamicWeight] P5 一致性降权50%%: {} {}/{}季度正", fc, positiveQuarters, totalQuarters);
                }
            } catch (Exception e) {
                log.debug("[DynamicWeight] P5 一致性校验跳过: {} error={}", fc, e.getMessage());
            }
        }
        if (dropped > 0 || penalized > 0) {
            log.info("[DynamicWeight] P5 IC一致性校验: 剔除{}个, 降权{}个", dropped, penalized);
        }
        return dropped;
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
