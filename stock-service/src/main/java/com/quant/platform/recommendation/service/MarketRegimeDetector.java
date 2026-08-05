package com.quant.platform.recommendation.service;

import com.quant.platform.factor.regime.MarketRegimeCalendarService;
import com.quant.platform.market.domain.MarketDailyBar;
import com.quant.platform.market.service.MarketDataService;
import com.quant.platform.stock.service.ClickHouseStockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 市场环境（Regime）识别器。
 * <p>
 * 从 {@link RecommendationService} 抽取（God Class 拆分 Phase 3），方法体逐字迁移，
 * 仅将 {@code calcATR}/{@code calcPercentile} 改为调用 {@link RecommendationMath} 的同名静态方法
 * （二者在拆分前后实现完全一致），其余计算逻辑、日志文案、落库副作用均未改变。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketRegimeDetector {

    /**
     * ATR 计算周期
     */
    private static final int ATR_PERIOD = 20;
    /**
     * ATR 历史分位数回溯天数
     */
    private static final int ATR_LOOKBACK_DAYS = 250;
    /**
     * 沪深300指数代码
     */
    private static final String SSE300_CODE = "000300";

    private final MarketDataService marketDataService;
    private final ClickHouseStockService clickHouseStockService;
    private final javax.sql.DataSource dataSource;

    /** regime 日历服务（可选；detectRegime 结果落库，供 ICW 按 regime 取 IC 历史）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MarketRegimeCalendarService regimeCalendarService;

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
    RegimeInfo detectRegime(LocalDate date) {
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
        MarketDailyBar latestBar = bars.getLast();
        info.indexClose = latestBar.getClose().doubleValue();
        info.indexChangePct = latestBar.getPctChg() != null ? latestBar.getPctChg().doubleValue() : null;
        double ma20 = RecommendationMath.avg(closes, 20);
        double ma60 = RecommendationMath.avg(closes, 60);
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
        double currentATR = RecommendationMath.calcATR(highs, lows, closes, ATR_PERIOD);
        info.atrValue = currentATR;

        // 历史 ATR20 序列（滚动计算）
        List<Double> atrHistory = new ArrayList<>();
        for (int i = 60; i <= closes.size() - ATR_PERIOD; i++) {
            atrHistory.add(RecommendationMath.calcATR(
                    highs.subList(0, i + ATR_PERIOD),
                    lows.subList(0, i + ATR_PERIOD),
                    closes.subList(0, i + ATR_PERIOD),
                    ATR_PERIOD));
        }
        if (!atrHistory.isEmpty()) {
            atrHistory.add(currentATR);
            double atrPercentile = RecommendationMath.calcPercentile(currentATR, atrHistory);
            info.atrPercentile = atrPercentile; // 0~1, 越高波动越大
            info.volatilityRegime = atrPercentile > 0.7 ? "HIGH" : atrPercentile < 0.3 ? "LOW" : "MEDIUM";
        }

        // ── 维度3: 市场宽度 ──
        try {
            Map<String, Object> overview = clickHouseStockService.getOverviewStats(date);
            if (overview != null) {
                long riseCount = RecommendationMath.toLong(overview.get("riseCount"));
                long fallCount = RecommendationMath.toLong(overview.get("fallCount"));
                long totalCount = riseCount + fallCount + RecommendationMath.toLong(overview.get("flatCount"));
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
                double currentYield = bondYields.getLast();
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

        // 落库 regime 到日历，供 ICW 按 regime 取 IC 历史（避免跨体制 IC 互相污染）
        if (regimeCalendarService != null) {
            try {
                regimeCalendarService.upsert(date, info.regime);
            } catch (Exception ignore) {
                log.error("[MarketRegimeDetector] 捕获到未处理异常", ignore);
                // 落库失败不影响主流程
            }
        }

        return info;
    }

    /** 公开暴露 regime 名称，供 MarketRegimeCalendarService 的 detector 回调使用（无副作用） */
    public String detectRegimeName(LocalDate date) {
        return detectRegime(date).regime;
    }

    /**
     * 优化④：判断最近 consecutiveDays 个交易日(含 date 当日)是否全部为 BEAR regime。
     * 复用 detectRegime 逐日回看，全部 BEAR 才返回 true，用于触发暂停生成开关。
     * 注意：回看依赖沪深300历史K线，若某日数据缺失 detectRegime 会返回 SIDEWAYS（保守不触发）。
     *
     * @param date            当前选股日期
     * @param consecutiveDays 连续天数阈值（含当日）
     * @return 连续全部 BEAR 则为 true
     */
    boolean isConsecutiveBear(LocalDate date, int consecutiveDays) {
        for (int k = 0; k < consecutiveDays; k++) {
            LocalDate d = date.minusDays(k);
            try {
                RegimeInfo r = detectRegime(d);
                if (!"BEAR".equals(r.regime)) {
                    return false;
                }
            } catch (Exception e) {
                // 单日 regime 计算异常，保守视为非 BEAR，不触发暂停
                log.debug("[Recommendation] 连续BEAR回看 {} 失败: {}", d, e.getMessage());
                return false;
            }
        }
        return true;
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
}
