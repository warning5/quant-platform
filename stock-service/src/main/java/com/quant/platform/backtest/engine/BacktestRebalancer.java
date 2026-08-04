package com.quant.platform.backtest.engine;

import com.quant.platform.market.domain.MarketDailyBar;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * 回测调仓执行器。
 *
 * <p>God Class 拆分 Phase 5：承载原 {@code BacktestEngine} 中「调仓触发判断 → 生成买卖成交明细 →
 * 重算现金」这一条调仓链路。无实例状态，方法体逐字搬运，行为零变化；
 * {@code BacktestEngine} 保留同名薄委托。</p>
 */
@Slf4j
@Component
public class BacktestRebalancer {

    List<Map<String, Object>> rebalance(Map<String, Double> oldPositions,
                                                Map<String, Double> targetWeights,
                                                Map<String, MarketDailyBar> barMap,
                                                double portfolioValue,
                                                double commission, double slippage,
                                                LocalDate date,
                                                Map<String, Double> positionValues,
                                                String slippageModel,
                                                double stampTaxRate,
                                                double minCommission,
                                                boolean limitFilter,
                                                boolean suspendFilter,
                                                double transferFeeRate,
                                                String orderType,
                                                List<LocalDate> tradingDates,
                                                int di,
                                                Map<String, MarketDailyBar> nextDayBarMap,
                                                Map<String, Double> positionCosts,
                                                double buyScale) {
        List<Map<String, Object>> trades = new ArrayList<>();

        // 记录卖出
        for (String symbol : new HashSet<>(oldPositions.keySet())) {
            if (!targetWeights.containsKey(symbol)) {
                MarketDailyBar bar = barMap.get(symbol);
                if (bar == null) continue;

                // 停牌过滤
                if (suspendFilter && BacktestUtils.isSuspended(bar)) {
                    log.debug("[{}] {} 停牌，跳过卖出", date, symbol);
                    continue;
                }
                // 跌停过滤
                if (limitFilter && BacktestUtils.isLimitDown(bar)) {
                    log.debug("[{}] {} 跌停，跳过卖出", date, symbol);
                    continue;
                }

                double execPrice = BacktestUtils.getExecutionPrice(bar, tradingDates, di, orderType, nextDayBarMap);
                double closePrice = bar.getClose().doubleValue();
                double shares = oldPositions.getOrDefault(symbol, 0.0);
                double amount = shares * closePrice;
                double dayAmount = bar.getAmount() != null ? bar.getAmount().doubleValue() * 1000 : 0;
                double price = BacktestUtils.applySlippage(execPrice, false, slippage, amount, dayAmount, slippageModel);
                double fee = BacktestUtils.calcFee(amount, true, commission, stampTaxRate, minCommission, symbol, transferFeeRate);

                Map<String, Object> trade = new HashMap<>();
                trade.put("date", date.toString());
                trade.put("symbol", symbol);
                trade.put("name", bar.getName());
                trade.put("action", "SELL");
                trade.put("price", BacktestUtils.round(price, 4));
                trade.put("amount", BacktestUtils.round(shares, 2));
                trade.put("total", BacktestUtils.round(amount, 2));
                trade.put("commission", BacktestUtils.round(fee, 2));
                trade.put("fee", BacktestUtils.round(fee, 2));
                trades.add(trade);
            }
        }

        // 记录买入
        for (Map.Entry<String, Double> entry : targetWeights.entrySet()) {
            if (!oldPositions.containsKey(entry.getKey())) {
                MarketDailyBar bar = barMap.get(entry.getKey());
                if (bar == null) continue;

                // 停牌过滤
                if (suspendFilter && BacktestUtils.isSuspended(bar)) {
                    log.debug("[{}] {} 停牌，跳过买入", date, entry.getKey());
                    continue;
                }
                // 涨停过滤
                if (limitFilter && BacktestUtils.isLimitUp(bar)) {
                    log.debug("[{}] {} 涨停，跳过买入", date, entry.getKey());
                    continue;
                }

                double execPrice = BacktestUtils.getExecutionPrice(bar, tradingDates, di, orderType, nextDayBarMap);
                double closePrice = bar.getClose().doubleValue();
                double amount = portfolioValue * entry.getValue() * buyScale;
                double dayAmount = bar.getAmount() != null ? bar.getAmount().doubleValue() * 1000 : 0;
                amount = BacktestUtils.scaleAmountToCapacity(amount, bar); // 容量约束：买入不超日成交额8%
                double price = BacktestUtils.applySlippage(execPrice, true, slippage, amount, dayAmount, slippageModel);
                double fee = BacktestUtils.calcFee(amount, false, commission, stampTaxRate, minCommission, entry.getKey(), transferFeeRate);

                // 记录买入成本（用于止损止盈计算）
                double totalCost = amount + fee;
                positionCosts.merge(entry.getKey(), totalCost, Double::sum);

                Map<String, Object> trade = new HashMap<>();
                trade.put("date", date.toString());
                trade.put("symbol", entry.getKey());
                trade.put("name", bar.getName());
                trade.put("action", "BUY");
                trade.put("price", BacktestUtils.round(price, 4));
                trade.put("amount", BacktestUtils.round(amount / price, 2));
                trade.put("total", BacktestUtils.round(amount, 2));
                trade.put("commission", BacktestUtils.round(fee, 2));
                trade.put("fee", BacktestUtils.round(fee, 2));
                trades.add(trade);
            }
        }

        return trades;
    }

    /**
     * 重新计算现金
     */
    double recalcCash(Map<String, Double> oldPositions,
                              Map<String, Double> targetWeights,
                              Map<String, MarketDailyBar> barMap,
                              double portfolioValue, double commission, double slippage,
                              String slippageModel,
                              double stampTaxRate,
                              double minCommission,
                              boolean limitFilter,
                              boolean suspendFilter,
                              double transferFeeRate,
                              double buyScale) {
        double totalFee = 0;

        // 买入费用（应用缩放）
        for (Map.Entry<String, Double> entry : targetWeights.entrySet()) {
            if (oldPositions.containsKey(entry.getKey())) continue;
            MarketDailyBar bar = barMap.get(entry.getKey());
            if (bar == null) continue;
            if (suspendFilter && BacktestUtils.isSuspended(bar)) continue;
            if (limitFilter && BacktestUtils.isLimitUp(bar)) continue;

            double amount = portfolioValue * entry.getValue() * buyScale;
            totalFee += BacktestUtils.calcFee(amount, false, commission, stampTaxRate, minCommission, entry.getKey(), transferFeeRate);
        }

        // 卖出费用
        for (String symbol : oldPositions.keySet()) {
            if (targetWeights.containsKey(symbol)) continue;
            MarketDailyBar bar = barMap.get(symbol);
            if (bar == null) continue;
            if (suspendFilter && BacktestUtils.isSuspended(bar)) continue;
            if (limitFilter && BacktestUtils.isLimitDown(bar)) continue;

            double amount = oldPositions.get(symbol) * bar.getClose().doubleValue();
            totalFee += BacktestUtils.calcFee(amount, true, commission, stampTaxRate, minCommission, symbol, transferFeeRate);
        }

        // 实际投入金额（扣除被过滤掉的买入，应用缩放）
        double investedValue = 0;
        for (Map.Entry<String, Double> entry : targetWeights.entrySet()) {
            if (oldPositions.containsKey(entry.getKey())) continue;
            MarketDailyBar bar = barMap.get(entry.getKey());
            if (bar == null) continue;
            if (suspendFilter && BacktestUtils.isSuspended(bar)) continue;
            if (limitFilter && BacktestUtils.isLimitUp(bar)) continue;
            investedValue += portfolioValue * entry.getValue() * buyScale;
        }

        // 扣除保留持仓的市值：这些持仓没卖，不能当现金用
        double keptValue = 0;
        for (String sym : oldPositions.keySet()) {
            if (!targetWeights.containsKey(sym)) continue;
            MarketDailyBar bar = barMap.get(sym);
            if (bar == null) continue;
            keptValue += oldPositions.get(sym) * bar.getClose().doubleValue();
        }

        double cash = portfolioValue - keptValue - investedValue - totalFee;
        if (cash < -0.01) {
            log.warn("Residual negative cash after rebalance: {} (scale={}, portfolioValue={}, keptValue={}, invested={}, fee={})",
                    String.format("%.2f", cash), String.format("%.4f", buyScale),
                    String.format("%.2f", portfolioValue), String.format("%.2f", keptValue),
                    String.format("%.2f", investedValue), String.format("%.2f", totalFee));
        }
        return Math.max(0, cash);
    }

    /**
     * 再平衡触发判断：支持日历频率+偏离阈值+波动率自适应+混合
     */
    boolean shouldRebalance(LocalDate today, LocalDate lastDate, String freq,
                                    double currentDeviation, double volatilityLevel,
                                    double threshold) {
        if (lastDate == null) return true;

        // 日历频率基础判断
        boolean calendarTrigger = switch (freq.toUpperCase()) {
            case "DAILY" -> true;
            case "WEEKLY" -> today.getYear() != lastDate.getYear() ||
                    today.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR) !=
                            lastDate.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            case "MONTHLY" -> today.getYear() != lastDate.getYear() ||
                    today.getMonthValue() != lastDate.getMonthValue();
            case "QUARTERLY" -> today.getYear() != lastDate.getYear() ||
                    (today.getMonthValue() - 1) / 3 != (lastDate.getMonthValue() - 1) / 3;
            default -> today.getMonthValue() != lastDate.getMonthValue();
        };

        // 根据触发模式组合
        // THRESHOLD: 仅偏离触发
        // VOL_ADAPTIVE: 日历触发（高波动时周频，低波动时月频）
        // HYBRID: 日历+偏离双重触发
        if ("THRESHOLD".equalsIgnoreCase(freq)) {
            return currentDeviation > threshold;
        } else if ("VOL_ADAPTIVE".equalsIgnoreCase(freq)) {
            // 高波动(volatility>0.03日波动≈年化48%) → 周频
            // 低波动(volatility<=0.02日波动≈年化32%) → 月频
            // 中间 → 两周频
            String adaptedFreq = volatilityLevel > 0.03 ? "WEEKLY" :
                    volatilityLevel <= 0.02 ? "MONTHLY" : "WEEKLY";
            return shouldRebalance(today, lastDate, adaptedFreq, 0, 0, threshold);
        } else if ("HYBRID".equalsIgnoreCase(freq)) {
            return calendarTrigger || currentDeviation > threshold;
        } else {
            return calendarTrigger;
        }
    }

    /**
     * 简化版：仅日历频率触发
     */
    boolean shouldRebalance(LocalDate today, LocalDate lastDate, String freq) {
        return shouldRebalance(today, lastDate, freq, 0, 0, 0);
    }
}
