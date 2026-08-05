package com.quant.platform.backtest.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.backtest.domain.BacktestReport;
import com.quant.platform.backtest.domain.BacktestTask;
import com.quant.platform.backtest.domain.EquityCurve;
import com.quant.platform.backtest.mapper.EquityCurveMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 回测绩效报告构建器 + 逐日净值落库。
 *
 * <p>从 {@code BacktestEngine} 中抽取的「结果产出」职责（God Class 拆分 Phase 2）。
 * 方法体逐字迁移，行为与原实现完全一致，仅变更归属类。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BacktestReportBuilder {

    private final ObjectMapper objectMapper;

    /**
     * 逐日净值写入
     */
    @Autowired(required = false)
    private EquityCurveMapper equityCurveMapper;

    /**
     * 构建绩效报告
     */
    public BacktestReport buildReport(BacktestTask task, BacktestEngine.BacktestResult result) throws Exception {
        int tradingDays = result.tradingDays();
        double years = tradingDays > 0 ? tradingDays / 252.0 : 1.0;

        double totalReturn = result.totalReturn();
        double annualReturn = years > 0 ? Math.pow(1 + totalReturn, 1.0 / years) - 1 : 0;

        // ── 基准收益 ──────────────────────────────────────────────────
        double benchmarkTotalReturn = result.benchmarkTotalReturn();
        double benchmarkAnnualReturn = years > 0 ? Math.pow(1 + benchmarkTotalReturn, 1.0 / years) - 1 : 0;
        double excessAnnualReturn = annualReturn - benchmarkAnnualReturn;

        // ── 从策略净值曲线计算日收益序列 ─────────────────────────────
        List<Double> dailyReturns = new ArrayList<>();
        List<Map<String, Object>> curve = result.equityCurve();
        for (int i = 1; i < curve.size(); i++) {
            double prev = ((Number) curve.get(i - 1).get("value")).doubleValue();
            double curr = ((Number) curve.get(i).get("value")).doubleValue();
            if (prev > 0) dailyReturns.add(curr / prev - 1);
        }

        double meanRet = dailyReturns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = dailyReturns.stream().mapToDouble(r -> (r - meanRet) * (r - meanRet)).average().orElse(0);
        double volatility = Math.sqrt(variance) * Math.sqrt(252);

        double riskFreeRate = 0.03;
        double sharpeRatio = volatility > 0 ? (annualReturn - riskFreeRate) / volatility : 0;
        // 限制异常值（理论上夏普比率不太可能超过 100）
        sharpeRatio = Math.max(-100, Math.min(100, sharpeRatio));

        // Sortino（只考虑下行波动）
        double downside = Math.sqrt(dailyReturns.stream()
                .mapToDouble(r -> r < 0 ? r * r : 0)
                .average().orElse(0)) * Math.sqrt(252);
        double sortinoRatio = downside > 0 ? (annualReturn - riskFreeRate) / downside : 0;
        sortinoRatio = Math.max(-100, Math.min(100, sortinoRatio));

        double calmarRatio = result.maxDrawdown() > 0 ? annualReturn / result.maxDrawdown() : 0;
        calmarRatio = Math.max(-100, Math.min(100, calmarRatio));

        // ── 基准相关指标（Alpha、Beta、Tracking Error、Information Ratio）────────────────────
        double informationRatio = 0.0, alpha = 0.0, beta = 0.0, trackingError = 0.0;
        List<Map<String, Object>> bmCurve = result.benchmarkCurve();
        List<Double> stratRets = new ArrayList<>();
        List<Double> bmRets = new ArrayList<>();

        // 超额收益序列（在 if 外定义，供后续 Alpha 分析使用）
        List<Double> excessReturns = new ArrayList<>();

        if (!bmCurve.isEmpty()) {
            // 建立基准日期→净值 map
            Map<String, Double> bmMap = new HashMap<>();
            for (Map<String, Object> bm : bmCurve) {
                bmMap.put((String) bm.get("date"), ((Number) bm.get("value")).doubleValue());
            }
            for (int i = 1; i < curve.size(); i++) {
                String date = (String) curve.get(i).get("date");
                String prevDate = (String) curve.get(i - 1).get("date");
                Double bmCurr = bmMap.get(date);
                Double bmPrev = bmMap.get(prevDate);
                if (bmCurr != null && bmPrev != null && bmPrev > 0) {
                    double stratRet = ((Number) curve.get(i).get("value")).doubleValue()
                            / ((Number) curve.get(i - 1).get("value")).doubleValue() - 1;
                    double bmRet = bmCurr / bmPrev - 1;
                    stratRets.add(stratRet);
                    bmRets.add(bmRet);
                    excessReturns.add(stratRet - bmRet);
                }
            }

            int n = excessReturns.size();
            if (n > 1) {
                // 信息比率
                double exMean = excessReturns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                double exVar = excessReturns.stream().mapToDouble(r -> (r - exMean) * (r - exMean)).average().orElse(0);
                double exStd = Math.sqrt(exVar) * Math.sqrt(252);
                informationRatio = exStd > 0 ? (exMean * 252) / exStd : 0;
                trackingError = exStd;  // 年化跟踪误差

                // Beta 和 Alpha（CAPM）
                double stratMean = stratRets.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                double bmMean = bmRets.stream().mapToDouble(Double::doubleValue).average().orElse(0);

                double cov = 0, bmVar = 0;
                for (int i = 0; i < n; i++) {
                    cov += (stratRets.get(i) - stratMean) * (bmRets.get(i) - bmMean);
                    bmVar += (bmRets.get(i) - bmMean) * (bmRets.get(i) - bmMean);
                }
                cov /= n;
                bmVar /= n;

                beta = bmVar > 0 ? cov / bmVar : 1.0;
                // Alpha = 策略平均收益 - Beta * 基准平均收益（日频，年化）
                alpha = (stratMean - beta * bmMean) * 252;
            }
        }

        // ── 胜率 & 盈亏比（从配对交易统计）─────────────────────────
        double winRate = 0.5, avgWin = 0.01, avgLoss = -0.008, plRatio = 1.25;
        List<Map<String, Object>> allTrades = result.tradeLog();
        Map<String, Double> buyPrices = new HashMap<>();
        List<Double> tradeRets = new ArrayList<>();
        for (Map<String, Object> t : allTrades) {
            String sym = (String) t.get("symbol");
            String action = (String) t.get("action");
            double price = ((Number) t.get("price")).doubleValue();
            if ("BUY".equals(action)) {
                buyPrices.put(sym, price);
            } else if ("SELL".equals(action) && buyPrices.containsKey(sym)) {
                double bp = buyPrices.remove(sym);
                if (bp > 0) tradeRets.add((price - bp) / bp);
            }
        }
        if (!tradeRets.isEmpty()) {
            long wins = tradeRets.stream().filter(r -> r > 0).count();
            long loses = tradeRets.stream().filter(r -> r < 0).count();
            winRate = (double) wins / tradeRets.size();
            avgWin = tradeRets.stream().filter(r -> r > 0).mapToDouble(Double::doubleValue).average().orElse(0.01);
            avgLoss = tradeRets.stream().filter(r -> r < 0).mapToDouble(Double::doubleValue).average().orElse(-0.008);
            plRatio = loses > 0 && avgLoss != 0 ? Math.abs(avgWin / avgLoss) : 1.25;
        }

        // ── 已实现收益曲线（按交易配对，逐日累计）────────────────────────────
        // BUY 时记录成本；SELL/STOP_LOSS_SELL 时计算已实现PnL并按日期汇总
        List<Map<String, Object>> realizedCurve = new ArrayList<>();
        {
            double initialCapitalLocal = result.initialCapital();
            Map<String, Double> buyCostMap = new HashMap<>();   // symbol -> 买入成本（含手续费）
            Map<String, Double> buySharesMap = new HashMap<>();  // symbol -> 持有股数
            Map<String, Double> dailyRealizedPnl = new TreeMap<>();  // date -> 当日新增已实现PnL
            for (Map<String, Object> t : allTrades) {
                String sym = (String) t.get("symbol");
                String action = (String) t.get("action");
                double tTotal = t.get("total") != null ? ((Number) t.get("total")).doubleValue() : 0;
                double tFee = t.get("fee") != null ? ((Number) t.get("fee")).doubleValue() : 0;
                String tDate = (String) t.get("date");
                if ("BUY".equals(action)) {
                    // 买入成本 = 金额 + 手续费
                    buyCostMap.merge(sym, tTotal + tFee, Double::sum);
                    double shares = t.get("amount") != null ? ((Number) t.get("amount")).doubleValue() : 0;
                    buySharesMap.merge(sym, shares, Double::sum);
                } else if (("SELL".equals(action) || "STOP_LOSS_SELL".equals(action))
                        && buyCostMap.containsKey(sym)) {
                    // 已实现 = 卖出金额(扣费后) - 对应成本
                    double proceeds = tTotal - tFee;
                    double cost = buyCostMap.remove(sym);
                    buySharesMap.remove(sym);
                    double pnl = proceeds - cost;
                    dailyRealizedPnl.merge(tDate, pnl, Double::sum);
                }
            }
            // 对 equityCurve 的每个日期，前向填充已实现PnL累计值 → 净值
            double cumPnl = 0;
            for (Map<String, Object> ep : result.equityCurve()) {
                String d = (String) ep.get("date");
                if (dailyRealizedPnl.containsKey(d)) {
                    cumPnl += dailyRealizedPnl.get(d);
                }
                Map<String, Object> rp = new HashMap<>();
                rp.put("date", d);
                // 已实现净值 = 1 + 累计已实现PnL / 初始资金
                rp.put("value", round(1.0 + cumPnl / initialCapitalLocal, 6));
                realizedCurve.add(rp);
            }
        }

        // ── 超额收益分析（参考 baostock 用户案例的 Alpha 分析表）────────────────
        double excessMean = 0, excessStd = 0, excessWinRate = 0.5, excessMaxDrawdown = 0, alphaContribution = 0;
        if (!excessReturns.isEmpty()) {
            // 超额收益均值（年化）
            excessMean = excessReturns.stream().mapToDouble(Double::doubleValue).average().orElse(0) * 252;
            // 超额收益标准差（年化）
            double exVar2 = excessReturns.stream().mapToDouble(r -> r * r).average().orElse(0)
                    - Math.pow(excessReturns.stream().mapToDouble(Double::doubleValue).average().orElse(0), 2);
            excessStd = Math.sqrt(Math.max(exVar2, 0)) * Math.sqrt(252);
            // 超额胜率：跑赢大盘的天数占比
            long exWins = excessReturns.stream().filter(r -> r > 0).count();
            excessWinRate = (double) exWins / excessReturns.size();
            // 超额收益最大回撤（复利累计超额曲线，避免算术累加放大偏差）
            double cumExcess = 1.0, peakExcess = 1.0;
            for (double er : excessReturns) {
                cumExcess *= (1 + er);
                if (cumExcess > peakExcess) peakExcess = cumExcess;
                double dd = 1 - cumExcess / peakExcess;
                if (dd > excessMaxDrawdown) excessMaxDrawdown = dd;
            }
            // Alpha贡献占比 = |alpha| / (|alpha| + |beta * benchmark_return|)
            // 这样与市场贡献之和为100%，避免CAPM残差导致>100%无意义值
            double absAlpha = Math.abs(alpha);
            double absMarket = Math.abs(beta * benchmarkAnnualReturn);
            double denom = absAlpha + absMarket;
            alphaContribution = denom > 1e-10 ? absAlpha / denom : 0;
        }

        return BacktestReport.builder()
                .taskId(task.getId())
                .strategyCode(task.getStrategyCode())
                .totalReturn(bd(totalReturn))
                .annualReturn(bd(annualReturn))
                .benchmarkReturn(bd(benchmarkTotalReturn))
                .benchmarkAnnualReturn(bd(benchmarkAnnualReturn))
                .excessReturn(bd(excessAnnualReturn))
                .volatility(bd(volatility))
                .sharpeRatio(bd(sharpeRatio))
                .sortinoRatio(bd(sortinoRatio))
                .calmarRatio(bd(calmarRatio))
                .maxDrawdown(bd(result.maxDrawdown()))
                .maxDrawdownDuration(result.maxDrawdownDuration())
                .informationRatio(bd(informationRatio))
                .alpha(bd(alpha))
                .beta(bd(beta))
                .trackingError(bd(trackingError))
                .downsideRisk(bd(downside))
                .totalTrades(result.totalTrades())
                .winRate(bd(winRate))
                .avgWinReturn(bd(avgWin))
                .avgLossReturn(bd(avgLoss))
                .profitLossRatio(bd(plRatio))
                .excessMean(bd(excessMean))
                .excessStd(bd(excessStd))
                .excessWinRate(bd(excessWinRate))
                .excessMaxDrawdown(bd(excessMaxDrawdown))
                .alphaContribution(bd(alphaContribution))
                .equityCurveJson(objectMapper.writeValueAsString(result.equityCurve()))
                .benchmarkCurveJson(objectMapper.writeValueAsString(result.benchmarkCurve()))
                .drawdownSeriesJson(objectMapper.writeValueAsString(result.equityCurve().stream()
                        .map(p -> Map.of("date", p.get("date"), "drawdown", p.get("drawdown")))
                        .collect(Collectors.toList())))
                .monthlyReturnsJson(objectMapper.writeValueAsString(result.monthlyReturns()))
                .positionHistoryJson(objectMapper.writeValueAsString(result.positionHistory()))
                .tradeLogJson(objectMapper.writeValueAsString(result.tradeLog().stream()
                        .limit(500)
                        .collect(Collectors.toList())))
                .realizedCurveJson(objectMapper.writeValueAsString(realizedCurve))
                .build();
    }

    /**
     * 写入逐日净值到 equity_curve 表
     */
    public void writeEquityCurveToDB(Long taskId, List<Map<String, Object>> equityCurve, double initialCapital) {
        if (equityCurveMapper == null || equityCurve == null || equityCurve.isEmpty()) return;
        try {
            // 先清理旧数据
            try {
                equityCurveMapper.deleteByTaskId(taskId);
            } catch (Exception ignored) {
                log.error("[BacktestReportBuilder] 捕获到未处理异常", ignored);
            }
            double prevNav = 0;
            for (Map<String, Object> point : equityCurve) {
                LocalDate date = LocalDate.parse((String) point.get("date"));
                double nav = ((Number) point.get("value")).doubleValue();
                double portfolioValue = nav * initialCapital;
                EquityCurve ec = EquityCurve.builder()
                        .taskId(taskId)
                        .tradeDate(date)
                        .portfolioValue(BigDecimal.valueOf(portfolioValue))
                        .nav(BigDecimal.valueOf(nav))
                        .returnPct(BigDecimal.valueOf(prevNav > 0 ? (nav - prevNav) / prevNav : 0))
                        .build();
                prevNav = nav;
                try {
                    equityCurveMapper.insertOne(ec);
                } catch (Exception e) {
                    log.error("[BacktestReportBuilder] 捕获到未处理异常", e);
                    // 逐条插入容错
                }
            }
        } catch (Exception e) {
            log.warn("写入 equity_curve 失败: {}", e.getMessage());
        }
    }

    private BigDecimal bd(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return BigDecimal.ZERO;
        return BigDecimal.valueOf(v).setScale(6, RoundingMode.HALF_UP);
    }

    /** 委托 {@link BacktestUtils#round}，保持全局舍入语义单一来源。 */
    private double round(double v, int scale) {
        return BacktestUtils.round(v, scale);
    }
}
