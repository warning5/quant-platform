package com.quant.platform.backtest.service;

import lombok.extern.slf4j.Slf4j;
import static com.quant.platform.backtest.service.OlsRegressionCalculator.*;
import static com.quant.platform.backtest.service.FactorStyleAttributionService.*;
import java.util.*;
import java.time.LocalDate;

@Slf4j
public final class RollingWindowMonitor {

    private RollingWindowMonitor() {}

    public static AlphaMonitorResult computeRollingAlphaForWindows(
            List<DailyExcess> excesses, Map<LocalDate, double[]> factorMap,
            int factorCount, int[] windows) {

        List<AlphaWindowPoint>[] results = new List[windows.length];
        for (int w = 0; w < windows.length; w++) {
            results[w] = new ArrayList<>();
            int win = windows[w];

            for (int start = 0; start + win <= excesses.size(); start++) {
                int end = start + win;
                List<DailyExcess> winExcess = new ArrayList<>();
                List<double[]> winFactors = new ArrayList<>();

                for (int i = start; i < end; i++) {
                    DailyExcess de = excesses.get(i);
                    double[] frow = factorMap.get(de.date());
                    if (frow == null) break;
                    winExcess.add(de);
                    winFactors.add(frow);
                }
                if (winExcess.size() < Math.max(20, win / 2)) continue; // 数据充足度检查

                RegressionResult reg = runOLS(winExcess, winFactors, winExcess.size(), factorCount);
                results[w].add(new AlphaWindowPoint(
                        excesses.get(end - 1).date(), round4(reg.alpha()),
                        round4(reg.alpha() * 252), round4(reg.rSquared()), win));
            }
        }

        // 衰减检测 (基于252天窗口，若数据不足则退而求其次)
        List<AlphaWindowPoint> primaryWindow = results[2].size() >= 10 ? results[2]
                : results[1].size() >= 10 ? results[1] : results[0];
        return detectAlphaDecay(primaryWindow, results[0], results[1], results[2]);
    }

    /** 检测 Alpha 衰减 —— 统一口径 + 趋势方向 */
    public static AlphaMonitorResult detectAlphaDecay(
            List<AlphaWindowPoint> primary, List<AlphaWindowPoint> r60,
            List<AlphaWindowPoint> r120, List<AlphaWindowPoint> r252) {

        // 数据不足：不计算衰减
        if (primary.size() < 20) {
            return new AlphaMonitorResult(r60, r120, r252,
                    false,
                    "数据不足（需 ≥20 个滚动窗口才能启动衰减分析）",
                    0, 0, 0, 0);
        }

        // 统一口径：都用平均值
        double historicalMean = primary.stream().mapToDouble(p -> p.alpha()).average().orElse(0);
        int recentN = Math.max(5, primary.size() / 4);
        double recentMean = primary.subList(primary.size() - recentN, primary.size())
                .stream().mapToDouble(p -> p.alpha()).average().orElse(0);

        // 计算近期斜率（最近5个点，简单线性回归）
        int slopeN = Math.min(5, primary.size());
        double slope = computeSlope(primary, slopeN);

        // 趋势判断
        boolean decayAlert = (slope < 0 && recentMean < historicalMean);
        String decayWarning;
        if (decayAlert) {
            decayWarning = String.format(
                    "⚠ Alpha 有下行趋势：近 %d 期均值 %.4f%% 低于历史均值 %.4f%%（近期斜率 %.6f），建议排查策略有效性。",
                    recentN, recentMean * 100, historicalMean * 100, slope);
        } else if (slope < 0) {
            decayWarning = String.format(
                    "Alpha 近期斜率 %.6f 为负，但均值尚未明显低于历史水平，需持续观察。",
                    slope);
        } else {
            decayWarning = "Alpha 保持稳定，未检测到显著衰减趋势。";
        }

        double decayRatio = Math.abs(historicalMean) > 1e-8
                ? (recentMean - historicalMean) / Math.abs(historicalMean) : 0;

        return new AlphaMonitorResult(r60, r120, r252, decayAlert, decayWarning,
                round4(historicalMean), round4(recentMean), round4(slope), round4(decayRatio));
    }

    /** 检测风格漂移 */
    public static StyleMonitorResult detectStyleDrift(
            List<StyleBetaPoint> r60, List<StyleBetaPoint> r120, List<StyleBetaPoint> r252) {

        List<StyleBetaPoint> primary = r252.size() >= 10 ? r252 : r120.size() >= 10 ? r120 : r60;

        boolean smbDrift = false, hmlDrift = false;
        double smbHistMean = 0, smbRecentMean = 0, hmlHistMean = 0, hmlRecentMean = 0;
        double smbStd = 0, hmlStd = 0;
        String driftWarning = "风格暴露稳定，未检测到显著漂移";

        if (primary.size() >= 10) {
            // SMB
            double[] smbSeries = primary.stream().mapToDouble(p -> p.smbBeta()).toArray();
            final double smbMean = Arrays.stream(smbSeries).average().orElse(0);
            smbHistMean = smbMean;
            smbStd = Math.sqrt(Arrays.stream(smbSeries)
                    .map(x -> (x - smbMean) * (x - smbMean)).average().orElse(0));

            int sN = Math.max(3, primary.size() / 4);
            smbRecentMean = Arrays.stream(smbSeries, smbSeries.length - sN, smbSeries.length)
                    .average().orElse(0);

            if (smbStd > 1e-8 && Math.abs(smbRecentMean - smbMean) > STYLE_DRIFT_STD * smbStd) {
                smbDrift = true;
            }

            // HML
            double[] hmlSeries = primary.stream().mapToDouble(p -> p.hmlBeta()).toArray();
            final double hmlMean = Arrays.stream(hmlSeries).average().orElse(0);
            hmlHistMean = hmlMean;
            hmlStd = Math.sqrt(Arrays.stream(hmlSeries)
                    .map(x -> (x - hmlMean) * (x - hmlMean)).average().orElse(0));

            int hN = Math.max(3, primary.size() / 4);
            hmlRecentMean = Arrays.stream(hmlSeries, hmlSeries.length - hN, hmlSeries.length)
                    .average().orElse(0);

            if (hmlStd > 1e-8 && Math.abs(hmlRecentMean - hmlHistMean) > STYLE_DRIFT_STD * hmlStd) {
                hmlDrift = true;
            }

            if (smbDrift || hmlDrift) {
                StringBuilder sb = new StringBuilder("⚠ 风格漂移预警：");
                if (smbDrift)
                    sb.append(String.format("规模暴露偏移 (%.2f→%.2f)", smbHistMean, smbRecentMean));
                if (smbDrift && hmlDrift) sb.append("; ");
                if (hmlDrift)
                    sb.append(String.format("价值暴露偏移 (%.2f→%.2f)", hmlHistMean, hmlRecentMean));
                sb.append("。请检查策略是否出现风格切换。");
                driftWarning = sb.toString();
            }
        }

        return new StyleMonitorResult(r60, r120, r252, smbDrift, hmlDrift,
                driftWarning, round4(smbHistMean), round4(smbRecentMean),
                round4(hmlHistMean), round4(hmlRecentMean),
                round4(smbStd), round4(hmlStd));
    }
}
