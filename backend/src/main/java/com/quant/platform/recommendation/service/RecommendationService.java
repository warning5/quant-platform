package com.quant.platform.recommendation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.quant.platform.stock.entity.StockInfo;
import com.quant.platform.stock.entity.StockDaily;
import com.quant.platform.stock.mapper.StockInfoMapper;
import com.quant.platform.stock.service.ClickHouseStockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 智能推荐服务
 *
 * Phase 1: 因子选股 → 个股深度分析 → 等权融合 → 排序输出
 * Phase 2: 多维市场环境识别 → Regime-Adaptive 动态权重 → 行业分散化
 *
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

    private final StockScreenService stockScreenService;
    private final AnalysisService analysisService;
    private final MarketDataService marketDataService;
    private final ClickHouseStockService clickHouseStockService;
    private final StockInfoMapper stockInfoMapper;
    private final RecommendationMapper recommendationMapper;
    private final ObjectMapper objectMapper;

    /** 因子选股取 Top N（广筛） */
    private static final int SCREEN_TOP_N = 50;
    /** 个股深度分析取 Top N（精筛） */
    private static final int ANALYSIS_TOP_N = 20;
    /** 同行业最多推荐 N 只 */
    private static final int MAX_SAME_INDUSTRY = 3;
    /** 申万一级行业 → 指数代码映射（从 stock_info.industry 到 index_daily.code） */
    private static final Map<String, String> INDUSTRY_TO_SW_CODE = Map.ofEntries(
            Map.entry("农林牧渔", "801010"), Map.entry("基础化工", "801030"),
            Map.entry("钢铁", "801040"),     Map.entry("有色金属", "801050"),
            Map.entry("电子", "801080"),     Map.entry("家用电器", "801110"),
            Map.entry("食品饮料", "801120"), Map.entry("纺织服饰", "801130"),
            Map.entry("轻工制造", "801140"), Map.entry("医药生物", "801150"),
            Map.entry("公用事业", "801160"), Map.entry("交通运输", "801170"),
            Map.entry("房地产", "801180"),   Map.entry("商贸零售", "801200"),
            Map.entry("社会服务", "801210"), Map.entry("综合", "801230"),
            Map.entry("建筑材料", "801710"), Map.entry("建筑装饰", "801720"),
            Map.entry("电力设备", "801250"), Map.entry("国防军工", "801260"),
            Map.entry("计算机", "801270"),   Map.entry("传媒", "801280"),
            Map.entry("通信", "801300"),     Map.entry("汽车", "801880"),
            Map.entry("机械设备", "801890"),
            // 金融/资源/环保/消费
            Map.entry("银行", "801780"),     Map.entry("非银金融", "801790"),
            Map.entry("煤炭", "801950"),     Map.entry("石油石化", "801960"),
            Map.entry("环保", "801970"),     Map.entry("美容护理", "801980")
    );

    /** ATR 计算周期 */
    private static final int ATR_PERIOD = 20;
    /** ATR 历史分位数回溯天数 */
    private static final int ATR_LOOKBACK_DAYS = 250;

    /** 默认因子配置（12个，全部来自 factor_definition 表已有因子） */
    private static final List<ScreenRequest.FactorWeight> DEFAULT_FACTORS = List.of(
            newFactor("MOM20", 1, 1.0),
            newFactor("VOL20", -1, 0.8),
            newFactor("VAL_PE_TTM", -1, 0.7),
            newFactor("VAL_PB", -1, 0.6),
            newFactor("VAL_DIVIDEND_YIELD", 1, 0.5),
            newFactor("RSI14", 1, 0.4),
            newFactor("MACD", 1, 0.3),
            newFactor("TURN20", -1, 0.5),                  // 流动性
            newFactor("FIN_EARNINGS_QUALITY", 1, 0.6),     // 盈利质量（经营现金流/净利润）
            newFactor("FIN_DEBT_TO_ASSET", -1, 0.5),       // 财务健康（资产负债率，越低越健康）
            newFactor("FIN_REVENUE_QUALITY", 1, 0.4),      // 营收质量
            newFactor("FIN_NET_PROFIT_YOY", 1, 0.5)        // 成长: 净利润同比增长率
    );

    /** 沪深300指数代码 */
    private static final String SSE300_CODE = "000300";
    
    /** 因子组合配置枚举 */
    public enum FactorProfile {
        EXISTING,     // 现有：偏价值+低波动
        NORMAL,        // 常规：平衡
        NEW_QUALITY,   // 新质生产力：高成长+高盈利质量
        HOT,           // 热点：高动量+高波动
        COMPREHENSIVE  // 综合：均衡
    }
    
    /** 现有因子配置（12个，偏价值和低波动） */
    private static final List<ScreenRequest.FactorWeight> EXISTING_FACTORS = List.of(
            newFactor("MOM20", 1, 1.0),
            newFactor("VOL20", -1, 0.8),
            newFactor("VAL_PE_TTM", -1, 0.7),
            newFactor("VAL_PB", -1, 0.6),
            newFactor("VAL_DIVIDEND_YIELD", 1, 0.5),
            newFactor("RSI14", 1, 0.4),
            newFactor("MACD", 1, 0.3),
            newFactor("TURN20", -1, 0.5),
            newFactor("FIN_EARNINGS_QUALITY", 1, 0.6),
            newFactor("FIN_DEBT_TO_ASSET", -1, 0.5),
            newFactor("FIN_REVENUE_QUALITY", 1, 0.4),
            newFactor("FIN_NET_PROFIT_YOY", 1, 0.5)
    );
    
    /** 常规因子配置（平衡价值与成长） */
    private static final List<ScreenRequest.FactorWeight> NORMAL_FACTORS = List.of(
            newFactor("MOM20", 1, 1.0),
            newFactor("VOL20", -1, 0.6),
            newFactor("VAL_PE_TTM", -1, 0.5),
            newFactor("VAL_PB", -1, 0.4),
            newFactor("VAL_DIVIDEND_YIELD", 1, 0.4),
            newFactor("RSI14", 1, 0.5),
            newFactor("MACD", 1, 0.4),
            newFactor("TURN20", -1, 0.4),
            newFactor("FIN_EARNINGS_QUALITY", 1, 0.5),
            newFactor("FIN_DEBT_TO_ASSET", -1, 0.4),
            newFactor("FIN_REVENUE_QUALITY", 1, 0.5),
            newFactor("FIN_NET_PROFIT_YOY", 1, 0.6),
            newFactor("FIN_REVENUE_TTM_YOY", 1, 0.5)
    );
    
    /** 新质生产力因子配置（高成长+高盈利质量，降低估值权重） */
    private static final List<ScreenRequest.FactorWeight> NEW_QUALITY_FACTORS = List.of(
            newFactor("MOM20", 1, 0.8),
            newFactor("VOL20", -1, 0.5),
            newFactor("VAL_PE_TTM", -1, 0.3),  // 降低PE权重
            newFactor("VAL_PB", -1, 0.2),      // 降低PB权重
            newFactor("VAL_DIVIDEND_YIELD", 1, 0.2),  // 降低分红权重
            newFactor("RSI14", 1, 0.6),
            newFactor("MACD", 1, 0.5),
            newFactor("FIN_EARNINGS_QUALITY", 1, 0.8),  // 提高盈利质量
            newFactor("FIN_DEBT_TO_ASSET", -1, 0.4),
            newFactor("FIN_REVENUE_QUALITY", 1, 0.7),   // 提高营收质量
            newFactor("FIN_NET_PROFIT_YOY", 1, 0.8),   // 提高净利润增长
            newFactor("FIN_REVENUE_TTM_YOY", 1, 0.9)  // 新增：营收同比增长
    );
    
    /** 热点因子配置（高动量+高波动+高换手，适合追涨） */
    private static final List<ScreenRequest.FactorWeight> HOT_FACTORS = List.of(
            newFactor("MOM20", 1, 1.2),
            newFactor("MOM5", 1, 0.8),       // 短期动量
            newFactor("VOL20", 1, 0.6),       // 反向：高波动优先
            newFactor("TURN20", 1, 0.8),      // 反向：高换手优先
            newFactor("RSI14", 1, 0.6),
            newFactor("MACD", 1, 0.5),
            newFactor("MTM6", 1, 0.7),        // 动量指标
            newFactor("FIN_NET_PROFIT_YOY", 1, 0.6),
            newFactor("FIN_REVENUE_TTM_YOY", 1, 0.5)
    );
    
    /** 综合因子配置（均衡配置） */
    private static final List<ScreenRequest.FactorWeight> COMPREHENSIVE_FACTORS = List.of(
            newFactor("MOM20", 1, 0.9),
            newFactor("VOL20", -1, 0.5),
            newFactor("VAL_PE_TTM", -1, 0.4),
            newFactor("VAL_PB", -1, 0.4),
            newFactor("VAL_DIVIDEND_YIELD", 1, 0.4),
            newFactor("RSI14", 1, 0.5),
            newFactor("MACD", 1, 0.4),
            newFactor("TURN20", -1, 0.4),
            newFactor("FIN_EARNINGS_QUALITY", 1, 0.6),
            newFactor("FIN_DEBT_TO_ASSET", -1, 0.5),
            newFactor("FIN_REVENUE_QUALITY", 1, 0.5),
            newFactor("FIN_NET_PROFIT_YOY", 1, 0.7),
            newFactor("FIN_REVENUE_TTM_YOY", 1, 0.6)
    );
    
    /** 因子组合配置映射 */
    private static final Map<String, List<ScreenRequest.FactorWeight>> PROFILE_FACTORS_MAP = Map.of(
            "EXISTING", EXISTING_FACTORS,
            "NORMAL", NORMAL_FACTORS,
            "NEW_QUALITY", NEW_QUALITY_FACTORS,
            "HOT", HOT_FACTORS,
            "COMPREHENSIVE", COMPREHENSIVE_FACTORS
    );

    /**
     * 各组合的行业排除配置（从 stock_info.industry 字段匹配）
     * NEW_QUALITY 排除传统金融、资源、公用事业等与新质生产力定位不符的行业
     */
    private static final Map<String, List<String>> PROFILE_EXCLUDE_INDUSTRY_MAP = Map.of(
            "EXISTING", List.of(),
            "NORMAL", List.of(),
            "NEW_QUALITY", List.of(
                    "证券", "银行", "保险", "信托", "期货",
                    "房地产开发", "房地产服务",
                    "电力", "燃气", "水务", "供热",
                    "钢铁", "煤炭开采", "焦炭", "石油石化", "油气开采",
                    "港口", "航运", "高速公路", "铁路公路", "机场",
                    "造纸", "纺织制造", "服装家纺",
                    "基础建设", "房屋建设",
                    "种植业", "渔业", "畜牧业",
                    "贵金属", "工业金属", "能源金属"
            ),
            "HOT", List.of(
                    "银行", "保险", "信托", "期货"
            ),
            "COMPREHENSIVE", List.of()
    );

    public RecommendationService(StockScreenService stockScreenService,
                                 AnalysisService analysisService,
                                 MarketDataService marketDataService,
                                 ClickHouseStockService clickHouseStockService,
                                 StockInfoMapper stockInfoMapper,
                                 RecommendationMapper recommendationMapper,
                                 ObjectMapper objectMapper) {
        this.stockScreenService = stockScreenService;
        this.analysisService = analysisService;
        this.marketDataService = marketDataService;
        this.clickHouseStockService = clickHouseStockService;
        this.stockInfoMapper = stockInfoMapper;
        this.recommendationMapper = recommendationMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 生成推荐列表
     *
     * @param date  推荐日期（null 则使用最新可用日期）
     * @param topN  最终推荐数量（默认20）
     * @return 推荐结果列表
     */
    public List<StockRecommendation> generateRecommendations(LocalDate date, Integer topN, String factorProfile) {
        // date=null 时 StockScreenService.screen() 会自动取最新日期
        if (topN == null || topN <= 0) {
            topN = ANALYSIS_TOP_N;
        }

        log.info("[Recommendation] 开始生成推荐列表: date={}, topN={}, factorProfile={}", date, topN, factorProfile);

        // Step 1: 多因子选股（广筛 Top 50）
        // date=null 时 StockScreenService.screen() 内部自动 resolveLatestDate()
        ScreenResult screenResult = screenStocks(date, factorProfile);
        List<ScreenResult.StockScore> candidates = screenResult.getStocks();
        if (candidates == null || candidates.isEmpty()) {
            log.warn("[Recommendation] 因子选股结果为空，无法生成推荐");
            return List.of();
        }

        // Step 1.5: 行业排除过滤（根据组合配置）
        List<String> excludeIndustries = PROFILE_EXCLUDE_INDUSTRY_MAP.getOrDefault(
                factorProfile != null ? factorProfile : "EXISTING", List.of());
        if (!excludeIndustries.isEmpty()) {
            Set<String> excludeSet = new HashSet<>(excludeIndustries);
            List<String> candidateCodes = candidates.stream()
                    .map(s -> stripSuffix(s.getSymbol()))
                    .collect(Collectors.toList());
            List<StockInfo> infos = stockInfoMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StockInfo>()
                            .in(StockInfo::getCode, candidateCodes)
                            .select(StockInfo::getCode, StockInfo::getIndustry));
            Map<String, String> codeIndustryMap = infos.stream()
                    .filter(i -> i.getCode() != null)
                    .collect(Collectors.toMap(StockInfo::getCode, i -> i.getIndustry() != null ? i.getIndustry() : "", (a, b) -> a));
            int before = candidates.size();
            candidates = candidates.stream()
                    .filter(s -> !excludeSet.contains(codeIndustryMap.getOrDefault(stripSuffix(s.getSymbol()), "")))
                    .collect(Collectors.toList());
            log.info("[Recommendation] 行业排除过滤 [{}]: 排除行业={}, 过滤前={}, 过滤后={}",
                    factorProfile, excludeSet, before, candidates.size());
            if (candidates.isEmpty()) {
                log.warn("[Recommendation] 行业排除后候选池为空，跳过生成");
                return List.of();
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
                StockRecommendation rec = analyzeAndFuse(stock, regime, actualDate, im);
                recommendations.add(rec);
                log.info("[Recommendation] 分析进度: {}/{} code={} name={} factorScore={} analysisScore={} finalScore={}",
                        i + 1, analysisCount, rec.getStockCode(), rec.getStockName(),
                        String.format("%.4f", rec.getFactorScore()),
                        rec.getAnalysisScore(),
                        String.format("%.4f", rec.getFinalScore()));
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

        // Step 6: 写入数据库（先生成 batchId，再删旧写新，避免唯一键冲突）
        String batchId = actualDate.format(DateTimeFormatter.ISO_LOCAL_DATE);
        recommendationMapper.deleteByBatchId(batchId);
        for (StockRecommendation rec : recommendations) {
            rec.setBatchId(batchId);
            try {
                recommendationMapper.insert(rec);
            } catch (Exception e) {
                log.warn("[Recommendation] 写入失败: code={} batchId={} error={}",
                        rec.getStockCode(), batchId, e.getMessage());
            }
        }

        log.info("[Recommendation] 推荐列表生成完成: batchId={} count={}", batchId, recommendations.size());
        return recommendations;
    }

    /**
     * 获取最新推荐列表
     */
    public List<StockRecommendation> getLatestRecommendations() {
        String latestBatch = recommendationMapper.findLatestBatchId();
        if (latestBatch == null) {
            return List.of();
        }
        return enrichFromStockInfo(recommendationMapper.findByBatchId(latestBatch));
    }

    /**
     * 获取指定批次推荐列表
     */
    public List<StockRecommendation> getRecommendationsByBatch(String batchId) {
        return enrichFromStockInfo(recommendationMapper.findByBatchId(batchId));
    }

    /**
     * 读侧补充：从 stock_info 填充 industry/marketCap，并修复旧数据的 actionTag 和 buyReason
     *
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
                    case "BULL" -> { wFactor = 0.6; wAnalysis = 0.4; }
                    case "BEAR" -> { wFactor = 0.4; wAnalysis = 0.6; }
                    default ->    { wFactor = 0.5; wAnalysis = 0.5; }
                }
                rec.setFactorWeight(wFactor);
                rec.setAnalysisWeight(wAnalysis);
            }
        }
        return recs;
    }

    /**
     * 追踪推荐表现（Phase 3.2）
     *
     * 对未追踪或需要更新的推荐批次，计算:
     * - 次日收益率
     * - 一周收益率
     * - 一月收益率
     *
     * @return 更新的记录数
     */
    public int trackRecommendationPerformance() {
        // 找到所有需要更新的批次（最新3个未完全追踪的批次）
        List<String> batchIds = recommendationMapper.findRecentBatchIds(5);
        int totalUpdated = 0;

        LocalDate today = LocalDate.now();

        for (String batchId : batchIds) {
            List<StockRecommendation> recs = recommendationMapper.findByBatchId(batchId);
            if (recs.isEmpty()) continue;

            LocalDate recDate = recs.get(0).getRecommendDate();
            int daysSince = (int) java.time.temporal.ChronoUnit.DAYS.between(recDate, today);
            if (daysSince <= 0) continue; // 推荐当天或未来，不追踪

            for (StockRecommendation rec : recs) {
                try {
                    boolean updated = false;

                    // 次日收益率
                    if (rec.getNextDayReturn() == null && daysSince >= 1) {
                        rec.setNextDayReturn(calcForwardReturn(rec.getStockCode(), recDate, 1));
                        updated = true;
                    }

                    // 一周收益率
                    if (rec.getNextWeekReturn() == null && daysSince >= 5) {
                        rec.setNextWeekReturn(calcForwardReturn(rec.getStockCode(), recDate, 5));
                        updated = true;
                    }

                    // 一月收益率
                    if (rec.getNextMonthReturn() == null && daysSince >= 22) {
                        rec.setNextMonthReturn(calcForwardReturn(rec.getStockCode(), recDate, 22));
                        updated = true;
                    }

                    if (updated) {
                        rec.setTrackingUpdatedAt(java.time.LocalDateTime.now());
                        recommendationMapper.updateById(rec);
                        totalUpdated++;
                    }
                } catch (Exception e) {
                    log.warn("[Recommendation] 追踪失败: code={} batchId={} error={}", rec.getStockCode(), batchId, e.getMessage());
                }
            }
        }

        log.info("[Recommendation] 表现追踪完成: 更新{}条记录", totalUpdated);
        return totalUpdated;
    }

    /**
     * 获取推荐命中率统计
     *
     * @param batchId 批次ID
     * @return { total, positive, hitRate, avgReturn }
     */
    public Map<String, Object> getHitRate(String batchId) {
        List<StockRecommendation> recs = recommendationMapper.findByBatchId(batchId);
        Map<String, Object> stats = new HashMap<>();
        stats.put("batchId", batchId);
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
     * 获取所有批次ID
     */
    public List<String> getBatchIds(int limit) {
        return recommendationMapper.findRecentBatchIds(limit);
    }

    // ── 私有方法 ──

    /**
     * 多维市场环境识别 (Phase 2)
     *
     * 三个维度综合判断:
     * 1. 指数趋势: 沪深300 MA20/MA60 排列
     * 2. 波动率体制: ATR20 分位数 (高波动=Risk-off, 低波动=Risk-on)
     * 3. 市场宽度: 涨跌家数比 (扩散好=Risk-on, 极端分化=Risk-off)
     *
     * 综合打分:
     *   BULL:   trend=BULL 且 (波动率低 或 宽度好) → 动量/成长友好
     *   BEAR:   trend=BEAR 且 (波动率高 或 宽度差) → 防御/价值优先
     *   SIDEWAYS: 其他情况
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

        boolean bullishTrend = info.indexClose > ma20 && ma20 > ma60;
        boolean bearishTrend = info.indexClose < ma20 && ma20 < ma60;

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

        log.info("[Recommendation] Regime详情: regime={} trend={} vol={}({:.0f}%) breadth={}({:.0f}%) ATR={:.2f}",
                info.regime,
                bullishTrend ? "BULL_TREND" : bearishTrend ? "BEAR_TREND" : "MIXED",
                info.volatilityRegime, info.atrPercentile != null ? info.atrPercentile * 100 : 0,
                info.breadthQuality, info.breadthRatio != null ? info.breadthRatio * 100 : 0,
                info.atrValue);

        return info;
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
     * @return 0~1, 越大说明当前值相对历史越高
     */
    private double calcPercentile(double value, List<Double> history) {
        if (history == null || history.isEmpty()) return 0.5;
        long countBelow = history.stream().filter(v -> v < value).count();
        return (double) countBelow / history.size();
    }

    /**
     * Regime-Adaptive 动态权重融合 (Phase 2, Phase C 升级)
     *
     * 不同市场环境下，因子得分和分析得分的权重不同:
     * - BULL:   因子0.6 + 分析0.4 (动量因子在牛市更有效)
     * - BEAR:   因子0.4 + 分析0.6 (个股基本面在熊市更抗跌)
     * - SIDEWAYS: 因子0.5 + 分析0.5 (均衡)
     *
     * Phase C 升级: 叠加行业轮动信号加分/扣分(±0.06)
     *
     * @param im 行业动量, 可为 null(无行业轮动信号时跳过)
     */
    private double fuseScore(StockRecommendation rec, RegimeInfo regime, IndustryMomentum im) {
        double factorPart = rec.getFactorScore() != null ? rec.getFactorScore() : 0.0;

        // 分析得分各维度归一化后加权
        double techPct = safeDiv(rec.getTechnicalScore(), 30.0);    // 技术面满分30
        double moneyPct = safeDiv(rec.getCapitalScore(), 25.0);    // 资金面满分25
        double eventPct = safeDiv(rec.getEventScore(), 25.0);      // 事件面满分25
        double fundPct = safeDiv(rec.getFundamentalScore(), 29.0); // 基本面满分29

        double analysisPart;
        switch (regime.regime) {
            case "BULL" -> analysisPart = 0.40 * techPct + 0.30 * moneyPct + 0.10 * eventPct + 0.20 * fundPct;
            case "BEAR" -> analysisPart = 0.20 * techPct + 0.15 * moneyPct + 0.15 * eventPct + 0.50 * fundPct;
            default -> analysisPart = 0.30 * techPct + 0.25 * moneyPct + 0.15 * eventPct + 0.30 * fundPct;
        }

        // Regime-Adaptive 总权重
        double wFactor, wAnalysis;
        switch (regime.regime) {
            case "BULL" -> { wFactor = 0.6; wAnalysis = 0.4; }
            case "BEAR" -> { wFactor = 0.4; wAnalysis = 0.6; }
            default -> { wFactor = 0.5; wAnalysis = 0.5; }
        }

        double finalScore = wFactor * factorPart + wAnalysis * analysisPart;

        // Phase C: 行业轮动信号加分/扣分
        if (im != null) {
            finalScore += im.fusionBonus;
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

    /** 去掉股票代码后缀: "600027.SH" → "600027" */
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

    /**
     * 行业分散化 (Phase 2.4, Phase A+C 升级)
     *
     * 对排序后的推荐列表做行业去重:
     * 1. 根据行业动量动态调整同类上限(强势行业放宽,弱势行业收紧)
     * 2. 超出部分延后处理（保留但降权标记）
     * 3. 重新排名
     *
     * @param industryMomentumMap 行业动量映射(用于动态上限)
     */
    private List<StockRecommendation> diversify(List<StockRecommendation> recommendations,
                                                  Map<String, IndustryMomentum> industryMomentumMap) {
        Map<String, Integer> industryCount = new LinkedHashMap<>();
        List<StockRecommendation> diversified = new ArrayList<>();
        List<StockRecommendation> excess = new ArrayList<>();

        for (StockRecommendation rec : recommendations) {
            String industry = rec.getIndustry() != null ? rec.getIndustry() : "UNKNOWN";
            int count = industryCount.getOrDefault(industry, 0);

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
                industryCount.put(industry, count + 1);
            } else {
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
            log.info("[Recommendation] 行业分散化(动态): 移动{}只超额股票到末尾", removed);
            // 打印各行业限制
            Map<String, Integer> finalCnt = new LinkedHashMap<>();
            for (StockRecommendation r : diversified) {
                String ind = r.getIndustry() != null ? r.getIndustry() : "UNKNOWN";
                finalCnt.merge(ind, 1, Integer::sum);
            }
            finalCnt.forEach((ind, cnt) -> {
                IndustryMomentum im = industryMomentumMap != null ? industryMomentumMap.get(ind) : null;
                int limit = im != null ? im.industryDiversifyLimit : MAX_SAME_INDUSTRY;
                log.info("  行业[{}]: 入选{}只, 上限={}, 强度={}, Regime={}",
                        ind, cnt, limit,
                        im != null ? String.format("%.2f", im.relativeStrength) : "N/A",
                        im != null ? im.industryRegime : "N/A");
            });
        }

        return diversified;
    }

    /**
     * 计算行业动量 (Phase A+C)
     *
     * 复用 AnalysisService.getSectorRanking() 的行业涨跌幅数据,
     * 结合沪深300涨跌幅计算相对强度, 用于:
     *   方案A: 动态行业分散化限制
     *   方案C: 因子融合加分
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
            String sql = String.format("""
                    SELECT code, change_percent
                    FROM stock.stock_daily FINAL
                    WHERE trade_date = '%s'
                    """, targetDate);
            List<Map<String, Object>> rows = clickHouseStockService.queryForList(sql);
            log.info("[Recommendation] 行业动量: CH stock_daily 返回 {} 行", rows != null ? rows.size() : -1);
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

            // Step 3: 按行业汇总涨跌幅
            Map<String, List<Double>> industryChanges = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                String code = (String) row.get("code");
                Object chgObj = row.get("change_percent");
                if (code == null || chgObj == null) continue;
                String industry = codeToIndustry.get(code);
                if (industry == null) continue;
                double chg = chgObj instanceof Number ? ((Number) chgObj).doubleValue() : 0;
                industryChanges.computeIfAbsent(industry, k -> new ArrayList<>()).add(chg);
            }

            if (industryChanges.isEmpty()) {
                log.warn("[Recommendation] 行业涨跌幅汇总为空");
                return result;
            }

            // Step 4: 计算各行业平均涨跌幅
            List<Double> allChangePcts = new ArrayList<>();
            List<Map<String, Object>> industryList = new ArrayList<>();
            for (Map.Entry<String, List<Double>> entry : industryChanges.entrySet()) {
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

        } catch (Exception e) {
            log.error("[Recommendation] 行业动量计算异常: {}", e.getMessage(), e);
        }
        return result;
    }

    /**
     * 检测单个行业的 Regime（三维：趋势 + ATR波动率 + 简化宽度）
     *
     * 使用申万一级行业指数 K 线数据（index_daily 表），计算与市场级 detectRegime()
     * 相同三个维度的行业市场环境：
     *   1. 趋势：行业指数 close > MA20 > MA60 → 牛市；close < MA20 < MA60 → 熊市
     *   2. 波动率: ATR(20) / close 历史分位数 → HIGH/MEDIUM/LOW
     *   3. 行业宽度（简化）：行业内上涨股票占比 > 60% = GOOD, < 40% = POOR
     *
     * @param industryName 行业名（stock_info.industry 值）
     * @param date         评估日期
     * @param im           行业动量数据（含 avgChangePct 等信息）
     * @return Regime 字符串: BULL / BEAR / SIDEWAYS
     */
    private String detectIndustryRegime(String industryName, LocalDate date, IndustryMomentum im) {
        // 1. 查找申万代码
        String swCode = INDUSTRY_TO_SW_CODE.get(industryName);
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
            boolean bullishTrend = latestClose > ma20 && ma20 > ma60;
            boolean bearishTrend = latestClose < ma20 && ma20 < ma60;

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
     *
     * 通过 MarketDataService 获取目标日和基准日收盘价
     * stockCode 格式可能是 "600027.SH" 或纯代码
     */
    private Double calcForwardReturn(String stockCode, LocalDate baseDate, int forwardDays) {
        try {
            LocalDate targetDate = baseDate.plusDays(forwardDays);
            List<MarketDailyBar> bars = marketDataService.getBarsInRange(stockCode, baseDate, targetDate);
            if (bars == null || bars.size() < 2) return null;

            double baseClose = bars.get(0).getClose().doubleValue();
            if (baseClose <= 0) return null;

            // 找到最接近 targetDate 的交易日
            double targetClose = bars.get(bars.size() - 1).getClose().doubleValue();
            if (targetClose <= 0) return null;

            return Math.round((targetClose / baseClose - 1.0) * 10000.0) / 100.0; // 百分比，保留2位
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 计算推荐买入价
     *
     * 基于MA20作为动态支撑位：
     * - 若MA20可获取且 < closePrice，返回MA20（回踩支撑买入）
     * - 若MA20可获取且 >= closePrice，返回closePrice×0.95（保守折扣）
     * - 若MA20无法获取，返回closePrice×0.95
     */
    private Double calcSuggestedBuyPrice(String stockCode, LocalDate date) {
        try {
            LocalDate startDate = date.minusDays(40); // 多取一些保证20个交易日
            List<MarketDailyBar> bars = marketDataService.getBarsInRange(stockCode, startDate, date);
            if (bars == null || bars.isEmpty()) return null;

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
     * @param factorProfile 因子组合配置名称
     */
    private ScreenResult screenStocks(LocalDate date, String factorProfile) {
        // 根据因子组合配置选择因子
        List<ScreenRequest.FactorWeight> factors = getFactorConfig(factorProfile);
        
        ScreenRequest req = new ScreenRequest();
        req.setScreenDate(date);
        req.setFactors(factors);
        req.setTopN(SCREEN_TOP_N);
        req.setDirection("LONG");
        req.setExcludeSt(true);
        req.setGlobalOutlierMethod("MAD");
        req.setGlobalNormalizeMethod("ZSCORE");
        return stockScreenService.screen(req);
    }
    
    /**
     * 根据因子组合名称获取对应的因子配置
     */
    private List<ScreenRequest.FactorWeight> getFactorConfig(String factorProfile) {
        if (factorProfile == null || factorProfile.isEmpty()) {
            return EXISTING_FACTORS;  // 默认使用现有配置
        }
        
        return PROFILE_FACTORS_MAP.getOrDefault(factorProfile.toUpperCase(), EXISTING_FACTORS);
    }

    /**
     * 对单只股票做深度分析并融合评分
     *
     * @param im 行业动量(Phase A+C), 可为 null
     */
    private StockRecommendation analyzeAndFuse(ScreenResult.StockScore stock, RegimeInfo regime, LocalDate date,
                                                IndustryMomentum im) {
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

            // 归一化到 0~1（109分满分）
            rec.setAnalysisScorePct(overview.getTotalScore() != null
                    ? overview.getTotalScore() / 109.0 : 0.0);
        } else {
            rec.setAnalysisScore(0);
            rec.setAnalysisScorePct(0.0);
        }

        // 融合评分 (Regime-Adaptive + 行业轮动)
        rec.setFinalScore(fuseScore(rec, regime, im));

        // Phase A: 行业 Regime
        if (im != null && im.industryRegime != null) {
            rec.setIndustryRegime(im.industryRegime);
        }

        return rec;
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

    private static double safeDiv(Integer numerator, double denominator) {
        if (numerator == null || denominator == 0) return 0.0;
        return Math.min(1.0, numerator / denominator);
    }

    private static long toLong(Object obj) {
        if (obj == null) return 0;
        if (obj instanceof Number) return ((Number) obj).longValue();
        try { return Long.parseLong(obj.toString()); } catch (Exception e) { return 0; }
    }

    private static ScreenRequest.FactorWeight newFactor(String code, int direction, double weight) {
        ScreenRequest.FactorWeight fw = new ScreenRequest.FactorWeight();
        fw.setFactorCode(code);
        fw.setDirection(direction);
        fw.setWeight(weight);
        return fw;
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
    }

    /**
     * 行业动量信息 (Phase A+C)
     *
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
    }
}
