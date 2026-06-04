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
    /** ATR 计算周期 */
    private static final int ATR_PERIOD = 20;
    /** ATR 历史分位数回溯天数 */
    private static final int ATR_LOOKBACK_DAYS = 250;

    /** 默认因子配置（Phase 2: 含质量因子） */
    private static final List<ScreenRequest.FactorWeight> DEFAULT_FACTORS = List.of(
            newFactor("MOM20", 1, 1.0),
            newFactor("VOL20", -1, 0.8),
            newFactor("VAL_PE_TTM", -1, 0.7),
            newFactor("VAL_PB", -1, 0.6),
            newFactor("VAL_DIVIDEND_YIELD", 1, 0.5),
            newFactor("RSI14", 1, 0.4),
            newFactor("MACD_DIF", 1, 0.3),
            newFactor("QUAL_EARNINGS", 1, 0.6),       // Phase 2.3: 盈利质量
            newFactor("QUAL_HEALTH", 1, 0.5),          // Phase 2.3: 财务健康
            newFactor("QUAL_REVENUE_STABILITY", 1, 0.4) // Phase 2.3: 营收稳定
    );

    /** 沪深300指数代码 */
    private static final String SSE300_CODE = "000300";

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
    public List<StockRecommendation> generateRecommendations(LocalDate date, Integer topN) {
        // date=null 时 StockScreenService.screen() 会自动取最新日期
        if (topN == null || topN <= 0) {
            topN = ANALYSIS_TOP_N;
        }

        log.info("[Recommendation] 开始生成推荐列表: date={}, topN={}", date, topN);

        // Step 1: 多因子选股（广筛 Top 50）
        // date=null 时 StockScreenService.screen() 内部自动 resolveLatestDate()
        ScreenResult screenResult = screenStocks(date);
        List<ScreenResult.StockScore> candidates = screenResult.getStocks();
        if (candidates == null || candidates.isEmpty()) {
            log.warn("[Recommendation] 因子选股结果为空，无法生成推荐");
            return List.of();
        }

        // 用选股实际日期作为推荐日期（date=null 时这是真实最新日期）
        LocalDate actualDate = screenResult.getScreenDate();
        log.info("[Recommendation] 因子选股完成: actualDate={}, 候选数={}", actualDate, candidates.size());

        // Step 2: 市场环境识别（用实际日期）
        RegimeInfo regime = detectRegime(actualDate);
        log.info("[Recommendation] 市场环境: regime={}, indexClose={}, MA20={}, MA60={}",
                regime.regime, regime.indexClose, regime.indexMa20, regime.indexMa60);

        // Step 3: 对 Top N 做个股深度分析
        int analysisCount = Math.min(topN, candidates.size());
        List<StockRecommendation> recommendations = new ArrayList<>();

        for (int i = 0; i < analysisCount; i++) {
            ScreenResult.StockScore stock = candidates.get(i);
            try {
                StockRecommendation rec = analyzeAndFuse(stock, regime, actualDate);
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

        // Step 5: 行业分散化
        recommendations = diversify(recommendations);

        for (int i = 0; i < recommendations.size(); i++) {
            recommendations.get(i).setRankNum(i + 1);
        }

        // Step 6: 写入数据库
        String batchId = actualDate.format(DateTimeFormatter.ISO_LOCAL_DATE);
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
     * Regime-Adaptive 动态权重融合 (Phase 2)
     *
     * 不同市场环境下，因子得分和分析得分的权重不同:
     * - BULL:   因子0.6 + 分析0.4 (动量因子在牛市更有效)
     * - BEAR:   因子0.4 + 分析0.6 (个股基本面在熊市更抗跌)
     * - SIDEWAYS: 因子0.5 + 分析0.5 (均衡)
     *
     * 同时对分析得分各维度做 regime 调节:
     * - BULL:   technicalScore 权重↑, capitalScore↑
     * - BEAR:   fundamentalScore 权重↑, technicalScore↓
     * - SIDEWAYS: 各维度均衡
     */
    private double fuseScore(StockRecommendation rec, RegimeInfo regime) {
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
     * 行业分散化 (Phase 2.4)
     *
     * 对排序后的推荐列表做行业去重:
     * 1. 同行业最多 MAX_SAME_INDUSTRY 只
     * 2. 超出部分延后处理（保留但降权标记）
     * 3. 重新排名
     */
    private List<StockRecommendation> diversify(List<StockRecommendation> recommendations) {
        Map<String, Integer> industryCount = new LinkedHashMap<>();
        List<StockRecommendation> diversified = new ArrayList<>();
        List<StockRecommendation> excess = new ArrayList<>();

        for (StockRecommendation rec : recommendations) {
            String industry = rec.getIndustry() != null ? rec.getIndustry() : "UNKNOWN";
            int count = industryCount.getOrDefault(industry, 0);

            if (count < MAX_SAME_INDUSTRY) {
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
            log.info("[Recommendation] 行业分散化: 移动{}只超额股票到末尾", removed);
        }

        return diversified;
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
     * 多因子选股
     */
    private ScreenResult screenStocks(LocalDate date) {
        ScreenRequest req = new ScreenRequest();
        req.setScreenDate(date);
        req.setFactors(DEFAULT_FACTORS);
        req.setTopN(SCREEN_TOP_N);
        req.setDirection("LONG");
        req.setExcludeSt(true);
        req.setGlobalOutlierMethod("MAD");
        req.setGlobalNormalizeMethod("ZSCORE");
        return stockScreenService.screen(req);
    }

    /**
     * 对单只股票做深度分析并融合评分
     */
    private StockRecommendation analyzeAndFuse(ScreenResult.StockScore stock, RegimeInfo regime, LocalDate date) {
        StockRecommendation rec = new StockRecommendation();

        // 基本信息
        rec.setStockCode(stock.getSymbol());
        rec.setStockName(stock.getName());
        rec.setRecommendDate(date);
        rec.setFactorScore(stock.getCompositeScore());
        rec.setClosePrice(stock.getCurrentPrice() != null ? stock.getCurrentPrice().doubleValue() : null);

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

        // 融合评分 (Regime-Adaptive)
        rec.setFinalScore(fuseScore(rec, regime));

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
}
