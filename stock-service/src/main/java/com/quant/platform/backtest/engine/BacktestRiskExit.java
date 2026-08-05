package com.quant.platform.backtest.engine;

import com.quant.platform.market.domain.MarketDailyBar;
import com.quant.platform.market.service.MarketDataService;
import com.quant.platform.stock.analysis.engine.SellSignalEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 回测风控退出器 —— 调仓周期之外的「被动卖出」判定与执行。
 *
 * <p>God Class 拆分 Phase 5：承载原 {@code BacktestEngine#executeBacktest} 日循环中两段
 * 风控退出逻辑（止损止盈、技术面卖点信号）。方法体逐字搬运，仅有两处必要的机械替换：</p>
 * <ul>
 *   <li>{@code cash} → {@code cashRef[0]}：现金是基本类型局部变量，跨方法回写需引用传递。
 *       仍是逐笔 {@code +=} 累加，浮点累加顺序与原实现完全一致（不做先汇总再累加的改写，
 *       否则浮点非结合律会导致末位差异）。项目内 {@code processDividendEvents} 的
 *       {@code divCashRef} 已是同一模式。</li>
 *   <li>{@code totalTrades++} → {@code tradeCountRef[0]++}：同上，int 累加无精度问题。</li>
 * </ul>
 *
 * <p>参数表照搬原方法的局部变量，未做参数对象收敛——收敛会改写方法体、令逐字比对失效；
 * 待后续 BacktestContext 化时统一处理。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BacktestRiskExit {

    private final MarketDataService marketDataService;

    @Autowired(required = false)
    private SellSignalEngine sellSignalEngine;

    /**
     * 止损止盈检查与执行（参数优化时启用；stopLossPct / stopProfitPct 均为 0 时整段跳过）。
     *
     * @param cashRef        现金引用（逐笔回写，保持累加顺序）
     * @param tradeCountRef  成交笔数引用
     */
    void applyStopLossTakeProfit(double[] cashRef,
                                 int[] tradeCountRef,
                                 Map<String, Double> positions,
                                 Map<String, Double> positionCosts,
                                 Map<String, MarketDailyBar> barMap,
                                 Map<String, Double> adjFactors,
                                 List<Map<String, Object>> tradeLog,
                                 LocalDate today,
                                 List<LocalDate> tradingDates,
                                 int di,
                                 Map<String, MarketDailyBar> nextDayBarMap,
                                 String orderType,
                                 double slippage,
                                 String slippageModel,
                                 double commission,
                                 double stampTaxRate,
                                 double minCommission,
                                 double transferFeeRate,
                                 boolean limitFilter,
                                 boolean suspendFilter,
                                 double stopLossPct,
                                 double stopProfitPct) {
        if ((stopLossPct > 0 || stopProfitPct > 0) && !positions.isEmpty()) {
            List<String> toSell = new ArrayList<>();
            for (Map.Entry<String, Double> pos : positions.entrySet()) {
                String symbol = pos.getKey();
                MarketDailyBar bar = barMap.get(symbol);
                if (bar == null) continue;

                double cost = positionCosts.getOrDefault(symbol, 0.0);
                if (cost <= 0) continue;

                double shares = pos.getValue();
                double adj = adjFactors.getOrDefault(symbol, 1.0);
                double currentValue = shares * bar.getClose().doubleValue() * adj;
                double returnPct = (currentValue - cost) / cost;

                // 止损：亏损超过阈值
                if (stopLossPct > 0 && returnPct <= -stopLossPct) {
                    log.debug("[{}] {} 触发止损: 收益率={}, 阈值={}", today, symbol, returnPct, -stopLossPct);
                    toSell.add(symbol);
                }
                // 止盈：盈利超过阈值
                else if (stopProfitPct > 0 && returnPct >= stopProfitPct) {
                    log.debug("[{}] {} 触发止盈: 收益率={}, 阈值={}", today, symbol, returnPct, stopProfitPct);
                    toSell.add(symbol);
                }
            }

            // 执行止损止盈卖出
            if (!toSell.isEmpty()) {
                for (String symbol : toSell) {
                    MarketDailyBar bar = barMap.get(symbol);
                    if (bar == null) continue;

                    // 停牌/涨跌停过滤
                    if (suspendFilter && BacktestUtils.isSuspended(bar)) {
                        log.debug("[{}] {} 停牌，跳过止损止盈", today, symbol);
                        continue;
                    }
                    if (limitFilter && BacktestUtils.isLimitDown(bar)) {
                        log.debug("[{}] {} 跌停，跳过止损止盈", today, symbol);
                        continue;
                    }

                    double shares = positions.get(symbol);
                    double cost = positionCosts.get(symbol);
                    double execPrice = BacktestUtils.getExecutionPrice(bar, tradingDates, di, orderType, nextDayBarMap);
                    double closePrice = bar.getClose().doubleValue();
                    double amount = shares * closePrice;
                    double dayAmount = bar.getAmount() != null ? bar.getAmount().doubleValue() * 1000 : 0;
                    double price = BacktestUtils.applySlippage(execPrice, false, slippage, amount, dayAmount, slippageModel);
                    double fee = BacktestUtils.calcFee(amount, true, commission, stampTaxRate, minCommission, symbol, transferFeeRate);

                    Map<String, Object> trade = new HashMap<>();
                    trade.put("date", today.toString());
                    trade.put("symbol", symbol);
                    trade.put("name", bar.getName());
                    trade.put("action", "STOP_LOSS_SELL");  // STOP_LOSS 或 STOP_PROFIT
                    trade.put("price", BacktestUtils.round(price, 4));
                    trade.put("amount", BacktestUtils.round(shares, 2));
                    trade.put("total", BacktestUtils.round(amount - fee, 2));
                    trade.put("commission", BacktestUtils.round(fee, 2));
                    trade.put("fee", BacktestUtils.round(fee, 2));
                    trade.put("returnPct", BacktestUtils.round(BacktestUtils.returnPct(amount, cost), 4));
                    tradeLog.add(trade);

                    cashRef[0] += (shares * price) - fee;
                    tradeCountRef[0]++;
                    positions.remove(symbol);
                    positionCosts.remove(symbol);
                }
            }
        }
    }

    /**
     * 技术面卖点信号检查与执行（{@code SellSignalEngine} 不可用或开关关闭时整段跳过）。
     *
     * @param cashRef       现金引用（逐笔回写，保持累加顺序）
     * @param tradeCountRef 成交笔数引用
     */
    void applySellSignals(double[] cashRef,
                          int[] tradeCountRef,
                          Map<String, Double> positions,
                          Map<String, Double> positionCosts,
                          Map<String, MarketDailyBar> barMap,
                          List<Map<String, Object>> tradeLog,
                          LocalDate today,
                          List<LocalDate> tradingDates,
                          int di,
                          Map<String, MarketDailyBar> nextDayBarMap,
                          String orderType,
                          double slippage,
                          String slippageModel,
                          double commission,
                          double stampTaxRate,
                          double minCommission,
                          double transferFeeRate,
                          boolean limitFilter,
                          boolean suspendFilter,
                          boolean sellSignalEnabled) {
        if (sellSignalEngine != null && sellSignalEnabled && !positions.isEmpty()) {
            List<String> toSellBySignal = new ArrayList<>();
            for (String symbol : positions.keySet()) {
                try {
                    List<MarketDailyBar> histBars = marketDataService.getBarsBySymbol(
                            symbol, today.minusDays(90), today);
                    if (histBars == null || histBars.size() < 30) continue;
                    int n = histBars.size();
                    double[] hClose = new double[n], hHigh = new double[n], hLow = new double[n], hOpen = new double[n], hVol = new double[n];
                    for (int i = 0; i < n; i++) {
                        MarketDailyBar b = histBars.get(i);
                        hClose[i] = b.getClose().doubleValue();
                        hHigh[i] = b.getHigh().doubleValue();
                        hLow[i] = b.getLow().doubleValue();
                        hOpen[i] = b.getOpen().doubleValue();
                        hVol[i] = b.getVol() != null ? b.getVol().doubleValue() : 0;
                    }
                    SellSignalEngine.SellAction action = sellSignalEngine.getSellAction(hClose, hHigh, hLow, hOpen, hVol);
                    if (action == SellSignalEngine.SellAction.SELL) {
                        toSellBySignal.add(symbol);
                    }
                } catch (Exception e) {
                    log.error("[BacktestRiskExit] 捕获到未处理异常", e);
                    // 卖点检测失败不影响回测主流程
                }
            }
            for (String symbol : toSellBySignal) {
                MarketDailyBar bar = barMap.get(symbol);
                if (bar == null) continue;
                if (suspendFilter && BacktestUtils.isSuspended(bar)) continue;
                if (limitFilter && BacktestUtils.isLimitDown(bar)) continue;
                double shares = positions.get(symbol);
                double cost = positionCosts.getOrDefault(symbol, 0.0);
                double execPrice = BacktestUtils.getExecutionPrice(bar, tradingDates, di, orderType, nextDayBarMap);
                double closePrice = bar.getClose().doubleValue();
                double amount = shares * closePrice;
                double dayAmount = bar.getAmount() != null ? bar.getAmount().doubleValue() * 1000 : 0;
                double price = BacktestUtils.applySlippage(execPrice, false, slippage, amount, dayAmount, slippageModel);
                double fee = BacktestUtils.calcFee(amount, true, commission, stampTaxRate, minCommission, symbol, transferFeeRate);
                Map<String, Object> trade = new HashMap<>();
                trade.put("date", today.toString());
                trade.put("symbol", symbol);
                trade.put("name", bar.getName());
                trade.put("action", "SELL_SIGNAL");
                trade.put("price", BacktestUtils.round(price, 4));
                trade.put("amount", BacktestUtils.round(shares, 2));
                trade.put("total", BacktestUtils.round(amount - fee, 2));
                trade.put("commission", BacktestUtils.round(fee, 2));
                trade.put("fee", BacktestUtils.round(fee, 2));
                trade.put("returnPct", cost > 0 ? BacktestUtils.round(BacktestUtils.returnPct(amount, cost), 4) : 0);
                tradeLog.add(trade);
                cashRef[0] += (shares * price) - fee;
                tradeCountRef[0]++;
                positions.remove(symbol);
                positionCosts.remove(symbol);
            }
        }
    }
}
