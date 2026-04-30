package com.quant.platform.backtest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.backtest.domain.BacktestReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * 蒙特卡洛模拟服务
 * <p>
 * 方法：Bootstrap 重采样
 * - 从历史日收益率序列中随机有放回地抽取 T 个日收益率，合成一条模拟路径
 * - 重复 N 次，生成 N 条模拟净值曲线
 * - 统计置信区间（5% / 25% / 50% / 75% / 95% 分位数）
 * - 统计关键风险指标的分布（最大回撤、年化收益率）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonteCarloService {

    private final BacktestService backtestService;
    private final ObjectMapper objectMapper;

    private static final int DEFAULT_SIMULATIONS = 500;
    private static final int DEFAULT_HORIZON_DAYS = 252; // 1年

    /**
     * 基于已完成回测的历史日收益率进行蒙特卡洛模拟
     *
     * @param taskId      已完成的回测任务ID
     * @param simulations 模拟路径数量（默认500）
     * @param horizonDays 预测期交易日数（默认252）
     * @return 模拟结果：置信区间 + 风险指标分布
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> simulate(Long taskId, int simulations, int horizonDays) {
        if (simulations <= 0) simulations = DEFAULT_SIMULATIONS;
        if (horizonDays <= 0) horizonDays = DEFAULT_HORIZON_DAYS;
        int sims = Math.min(simulations, 2000); // 上限2000条，避免内存溢出
        int T = horizonDays;

        BacktestReport report = backtestService.getReport(taskId);

        // ── 1. 解析历史日收益率序列 ─────────────────────────────────────
        List<Map<String, Object>> equityCurve;
        try {
            equityCurve = objectMapper.readValue(
                    report.getEquityCurveJson() != null ? report.getEquityCurveJson() : "[]",
                    List.class);
        } catch (Exception e) {
            throw new IllegalStateException("无法解析净值曲线数据: " + e.getMessage());
        }

        if (equityCurve.size() < 20) {
            throw new IllegalStateException("历史数据不足（需至少20个交易日），当前仅有 " + equityCurve.size() + " 个");
        }

        // 计算历史日收益率
        double[] dailyReturns = new double[equityCurve.size() - 1];
        for (int i = 1; i < equityCurve.size(); i++) {
            double prev = toDouble(equityCurve.get(i - 1).get("value"));
            double curr = toDouble(equityCurve.get(i).get("value"));
            dailyReturns[i - 1] = prev > 0 ? (curr - prev) / prev : 0.0;
        }

        int histLen = dailyReturns.length;
        double histMean = mean(dailyReturns);
        double histStd = std(dailyReturns, histMean);

        // ── 2. Bootstrap 模拟 ───────────────────────────────────────────
        Random rng = new Random(42); // 固定种子保证可重现
        double[][] paths = new double[sims][T]; // paths[sim][day] = 净值（初始=1.0）

        double[] maxDrawdowns = new double[sims];
        double[] annualReturns = new double[sims];
        double[] finalValues = new double[sims];
        double[] sharpeRatios = new double[sims];

        for (int s = 0; s < sims; s++) {
            double nav = 1.0;
            double peak = 1.0;
            double maxDD = 0.0;
            paths[s][0] = 1.0;

            for (int d = 1; d < T; d++) {
                // 随机抽取历史日收益率（有放回）
                double r = dailyReturns[rng.nextInt(histLen)];
                nav *= (1.0 + r);
                nav = Math.max(nav, 0.001); // 防止净值为负
                paths[s][d] = nav;
                if (nav > peak) peak = nav;
                double dd = (peak - nav) / peak;
                if (dd > maxDD) maxDD = dd;
            }

            double finalNav = paths[s][T - 1];
            double annRet = Math.pow(finalNav, 252.0 / T) - 1;

            // 简化夏普：用模拟路径的收益序列
            double[] simDailyRet = new double[T - 1];
            for (int d = 1; d < T; d++) {
                double prev = paths[s][d - 1];
                simDailyRet[d - 1] = prev > 0 ? (paths[s][d] - prev) / prev : 0;
            }
            double simMean = mean(simDailyRet);
            double simStd = std(simDailyRet, simMean);
            double sharpe = simStd > 0 ? (simMean * 252 - 0.03) / (simStd * Math.sqrt(252)) : 0;

            maxDrawdowns[s] = maxDD;
            annualReturns[s] = annRet;
            finalValues[s] = finalNav;
            sharpeRatios[s] = sharpe;
        }

        // ── 3. 计算分位数置信区间（用于净值区间图） ─────────────────────
        int[] quantileIndices = new int[T];
        double[][] quantiles = computePathQuantiles(paths, sims, T);
        // quantiles[q][day]，q: 0=5%, 1=25%, 2=50%, 3=75%, 4=95%

        // 降采样到 ≤252 个数据点（保持可视化性能）
        int step = Math.max(1, T / 252);
        List<Map<String, Object>> confidenceBand = new ArrayList<>();
        for (int d = 0; d < T; d += step) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("day", d);
            point.put("p5", round4(quantiles[0][d]));
            point.put("p25", round4(quantiles[1][d]));
            point.put("p50", round4(quantiles[2][d]));
            point.put("p75", round4(quantiles[3][d]));
            point.put("p95", round4(quantiles[4][d]));
            confidenceBand.add(point);
        }

        // ── 4. 指标分布统计 ──────────────────────────────────────────────
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", taskId);
        result.put("simulations", sims);
        result.put("horizonDays", T);
        result.put("confidenceBand", confidenceBand);
        result.put("historicalMeanReturn", round4(histMean * 252));
        result.put("historicalVolatility", round4(histStd * Math.sqrt(252)));

        // 最终净值分布
        Arrays.sort(finalValues);
        result.put("finalValueP5", round4(percentile(finalValues, 0.05)));
        result.put("finalValueP50", round4(percentile(finalValues, 0.50)));
        result.put("finalValueP95", round4(percentile(finalValues, 0.95)));
        result.put("finalValueMean", round4(mean(finalValues)));

        // 年化收益率分布
        Arrays.sort(annualReturns);
        result.put("annualReturnP5", round4(percentile(annualReturns, 0.05)));
        result.put("annualReturnP50", round4(percentile(annualReturns, 0.50)));
        result.put("annualReturnP95", round4(percentile(annualReturns, 0.95)));
        result.put("annualReturnMean", round4(mean(annualReturns)));

        // 最大回撤分布
        Arrays.sort(maxDrawdowns);
        result.put("maxDrawdownP5", round4(percentile(maxDrawdowns, 0.05)));
        result.put("maxDrawdownP50", round4(percentile(maxDrawdowns, 0.50)));
        result.put("maxDrawdownP95", round4(percentile(maxDrawdowns, 0.95)));
        result.put("maxDrawdownMean", round4(mean(maxDrawdowns)));

        // 正收益概率
        long profitCount = Arrays.stream(finalValues).filter(v -> v > 1.0).count();
        result.put("profitProbability", round4((double) profitCount / sims));

        // VaR / CVaR（95% 置信水平）
        double var95 = 1.0 - percentile(finalValues, 0.05); // 损失
        double cvar95 = 1.0 - meanBelow(finalValues, percentile(finalValues, 0.05));
        result.put("var95", round4(var95));
        result.put("cvar95", round4(cvar95));

        // 夏普比率分布
        Arrays.sort(sharpeRatios);
        result.put("sharpeP50", round4(percentile(sharpeRatios, 0.50)));
        result.put("sharpeMean", round4(mean(sharpeRatios)));

        // 直方图数据（年化收益率分布，用于前端柱状图）
        result.put("annualReturnHistogram", buildHistogram(annualReturns, 20));
        result.put("maxDrawdownHistogram", buildHistogram(maxDrawdowns, 20));

        return result;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 私有工具方法
    // ──────────────────────────────────────────────────────────────────────────

    private double[][] computePathQuantiles(double[][] paths, int sims, int T) {
        double[][] qs = new double[5][T];
        double[] probs = {0.05, 0.25, 0.50, 0.75, 0.95};
        double[] dayVals = new double[sims];

        for (int d = 0; d < T; d++) {
            for (int s = 0; s < sims; s++) dayVals[s] = paths[s][d];
            Arrays.sort(dayVals);
            for (int q = 0; q < 5; q++) {
                qs[q][d] = percentile(dayVals, probs[q]);
            }
        }
        return qs;
    }

    private double percentile(double[] sorted, double p) {
        int n = sorted.length;
        double idx = p * (n - 1);
        int lo = (int) idx;
        int hi = Math.min(lo + 1, n - 1);
        return sorted[lo] + (idx - lo) * (sorted[hi] - sorted[lo]);
    }

    private double meanBelow(double[] sorted, double threshold) {
        double sum = 0;
        int count = 0;
        for (double v : sorted) {
            if (v <= threshold) {
                sum += v;
                count++;
            }
        }
        return count > 0 ? sum / count : threshold;
    }

    private double mean(double[] arr) {
        if (arr.length == 0) return 0;
        double s = 0;
        for (double v : arr) s += v;
        return s / arr.length;
    }

    private double std(double[] arr, double mean) {
        if (arr.length < 2) return 0;
        double s = 0;
        for (double v : arr) s += (v - mean) * (v - mean);
        return Math.sqrt(s / (arr.length - 1));
    }

    private List<Map<String, Object>> buildHistogram(double[] sorted, int bins) {
        double min = sorted[0];
        double max = sorted[sorted.length - 1];
        double binWidth = (max - min) / bins;
        if (binWidth == 0) binWidth = 1e-6;
        int[] counts = new int[bins];
        for (double v : sorted) {
            int idx = (int) ((v - min) / binWidth);
            if (idx >= bins) idx = bins - 1;
            counts[idx]++;
        }
        List<Map<String, Object>> hist = new ArrayList<>();
        for (int i = 0; i < bins; i++) {
            Map<String, Object> bar = new LinkedHashMap<>();
            bar.put("range", round4(min + i * binWidth) + " ~ " + round4(min + (i + 1) * binWidth));
            bar.put("low", round4(min + i * binWidth));
            bar.put("high", round4(min + (i + 1) * binWidth));
            bar.put("count", counts[i]);
            bar.put("freq", round4((double) counts[i] / sorted.length));
            hist.add(bar);
        }
        return hist;
    }

    private double toDouble(Object v) {
        if (v == null) return 1.0;
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(v.toString());
        } catch (Exception e) {
            return 1.0;
        }
    }

    private double round4(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0;
        return new BigDecimal(v).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }
}
