package com.quant.platform.recommendation.service;

import com.quant.platform.market.domain.MarketDailyBar;
import com.quant.platform.market.service.MarketDataService;
import com.quant.platform.recommendation.domain.StockRecommendation;
import com.quant.platform.stock.analysis.domain.AnalysisOverview;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 买入价 / 止损止盈 / 建议仓位计算器。
 * <p>
 * 从 {@link RecommendationService} 抽取（God Class 拆分 Phase 3），方法体与常量逐字迁移，
 * 计算公式、四舍五入方式、日志文案均未改变。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PricePlanCalculator {

    /**
     * 优化②：熊市降仓系数。BEAR regime 下建议仓位 ×0.6，降低下行暴露
     * （评估发现 BEAR 即使高置信仍日亏、胜率偏低，老策略无有效防御；×0.4 过度减仓被回测证伪）
     */
    private static final double BEAR_POSITION_FACTOR = 0.6;
    /**
     * 优化③：高 beta 限仓。688(科创板)/300(创业板) 为 20% 涨跌停、高波动个股，
     * 仓位 ×0.7 且硬上限 5%（评估发现 final≥0.9 顶端档集中这些高波动成长股次日大幅回撤）
     */
    private static final double HIGH_BETA_POSITION_FACTOR = 0.7;
    private static final double HIGH_BETA_POSITION_CAP = 0.05;

    private final MarketDataService marketDataService;

    /**
     * 计算推荐买入价
     * <p>
     * 基于MA20作为动态支撑位：
     * - 若MA20可获取且 < closePrice，返回MA20（回踩支撑买入）
     * - 若MA20可获取且 >= closePrice，返回closePrice×0.95（保守折扣）
     * - 若MA20无法获取，返回closePrice×0.95
     */
    Double calcSuggestedBuyPrice(String stockCode, LocalDate date) {
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
     * 计算价格计划：止损价、止盈价、目标价、建议仓位 (#5 + #9)
     * <p>
     * 止损价：基于 ATR 2倍宽度，回退 buyPrice×0.92，下限 buyPrice×0.88
     * 止盈价：盈亏比 R:R = 1:2，即 buyPrice + 2 × (buyPrice - stopLoss)
     * 目标价：盈亏比 R:R = 1:3，即 buyPrice + 3 × (buyPrice - stopLoss)
     * 建议仓位：riskScore(0-15) + liquidityScore(0-10) 映射，范围 2.1%~10%
     *   - basePct = 0.03 + (riskScore/15) × 0.07  → 3%~10%（风险越低仓位越高）
     *   - liquidityFactor = 0.7 + 0.3 × (liquidityScore/10)  → 0.7~1.0（流动性补偿）
     *   - finalPct = min(0.10, basePct × liquidityFactor)
     */
    void calcPricePlan(StockRecommendation rec, AnalysisOverview overview) {
        Double buyPrice = rec.getSuggestedBuyPrice();
        if (buyPrice == null || buyPrice <= 0) return;

        Double atr = (overview != null) ? overview.getAtr() : null;

        // ── 止损价 ──
        double stopLoss;
        if (atr != null && atr > 0) {
            stopLoss = buyPrice - 2.0 * atr;
        } else {
            stopLoss = buyPrice * 0.92; // 回退8%止损
        }
        // 止损下限：不低于买入价的-12%（避免异常ATR导致止损过远）
        stopLoss = Math.max(stopLoss, buyPrice * 0.88);
        rec.setSuggestedStopLoss(Math.round(stopLoss * 100.0) / 100.0);

        // ── 止盈价 (R:R = 1:2) ──
        double risk = buyPrice - stopLoss;
        double takeProfit = buyPrice + 2.0 * risk;
        rec.setSuggestedTakeProfit(Math.round(takeProfit * 100.0) / 100.0);

        // ── 目标价 (R:R = 1:3) ──
        double targetPrice = buyPrice + 3.0 * risk;
        rec.setSuggestedTargetPrice(Math.round(targetPrice * 100.0) / 100.0);

        // ── 建议仓位 ──
        Integer riskScore = rec.getRiskScore();
        Integer liquidityScore = rec.getLiquidityScore();
        int rs = (riskScore != null) ? riskScore : 7;       // 默认中等风险
        int ls = (liquidityScore != null) ? liquidityScore : 5; // 默认中等流动性
        double basePct = 0.03 + (rs / 15.0) * 0.07;          // 3%~10%
        double liquidityFactor = 0.7 + 0.3 * (ls / 10.0);    // 0.7~1.0
        double positionPct = Math.min(0.10, basePct * liquidityFactor);

        // 优化②：熊市降仓（BEAR regime 下仓位 ×0.6，降低下行暴露）
        if ("BEAR".equals(rec.getRegime())) {
            positionPct *= BEAR_POSITION_FACTOR;
        }

        // 优化③：高 beta 限仓（688 科创板 / 300 创业板 高波动 → 仓位 ×0.7 且硬上限 5%）
        String code = rec.getStockCode();
        if (code != null && (code.startsWith("688") || code.startsWith("300"))) {
            positionPct *= HIGH_BETA_POSITION_FACTOR;
            positionPct = Math.min(positionPct, HIGH_BETA_POSITION_CAP);
        }

        rec.setSuggestedPositionPct(Math.round(positionPct * 10000.0) / 10000.0);

        log.debug("[PricePlan] code={} buy={} stop={} takeProfit={} target={} pos={}%",
                rec.getStockCode(), buyPrice, stopLoss, takeProfit, targetPrice,
                String.format("%.1f", positionPct * 100));
    }
}
