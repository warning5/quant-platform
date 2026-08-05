package com.quant.platform.factor.service;

import com.quant.platform.factor.regime.MarketRegimeCalendarService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.math3.distribution.TDistribution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 因子 IC/IR 计算的纯函数与工具方法（无状态、无注入依赖）。
 * 由 FactorAnalysisService 拆分而来，方法体逐字搬运，行为零变化。
 */
public final class FactorIcMath {

    private FactorIcMath() {}

    /** factorCode 白名单正则（防御 SQL 注入） */
    private static final java.util.regex.Pattern FACTOR_CODE_PATTERN =
            java.util.regex.Pattern.compile("[a-zA-Z0-9_\\-]+");



    public static void checkFactorCode(String factorCode) {
        if (factorCode == null || !FACTOR_CODE_PATTERN.matcher(factorCode).matches()) {
            throw new IllegalArgumentException("Invalid factorCode: " + factorCode);
        }
    }


    public static void checkFactorCodes(List<String> factorCodes) {
        if (factorCodes == null) return;
        for (String fc : factorCodes) {
            checkFactorCode(fc);
        }
    }


    /** 工具：double[] → List<Double> */
    public static List<Double> toList(double[] arr) {
        List<Double> out = new ArrayList<>(arr.length);
        for (double v : arr) out.add(v);
        return out;
    }


    /** 根据 correlationType 选择 IC 计算方法 */
    public static double computeIc(List<Double> x, List<Double> y, String correlationType) {
        return "pearson".equals(correlationType) ? calcPearsonCorrelation(x, y) : calcSpearmanCorrelation(x, y);
    }


    /**
     * 计算 Spearman 秩相关系数
     */
    public static double calcSpearmanCorrelation(List<Double> x, List<Double> y) {
        int n = x.size();
        if (n != y.size() || n < 3) return Double.NaN;

        double[] rankX = calcRank(x);
        double[] rankY = calcRank(y);

        // Pearson correlation on ranks
        double meanRX = Arrays.stream(rankX).average().orElse(0);
        double meanRY = Arrays.stream(rankY).average().orElse(0);

        double cov = 0, varX = 0, varY = 0;
        for (int i = 0; i < n; i++) {
            double dx = rankX[i] - meanRX;
            double dy = rankY[i] - meanRY;
            cov += dx * dy;
            varX += dx * dx;
            varY += dy * dy;
        }

        if (varX == 0 || varY == 0) return Double.NaN;
        return cov / Math.sqrt(varX * varY);
    }


    /**
     * Pearson 相关系数 —— 对量值敏感，适合行业中性化后的分析
     */
    public static double calcPearsonCorrelation(List<Double> x, List<Double> y) {
        int n = x.size();
        if (n != y.size() || n < 3) return Double.NaN;

        double meanX = 0, meanY = 0;
        for (int i = 0; i < n; i++) {
            meanX += x.get(i);
            meanY += y.get(i);
        }
        meanX /= n;
        meanY /= n;

        double cov = 0, varX = 0, varY = 0;
        for (int i = 0; i < n; i++) {
            double dx = x.get(i) - meanX;
            double dy = y.get(i) - meanY;
            cov += dx * dy;
            varX += dx * dx;
            varY += dy * dy;
        }

        if (varX == 0 || varY == 0) return Double.NaN;
        return cov / Math.sqrt(varX * varY);
    }


    /**
     * 计算排名，正确处理并列值（平均排名法）
     * 例如 [10, 20, 20, 30] → [1, 2.5, 2.5, 4]
     */
    public static double[] calcRank(List<Double> values) {
        int n = values.size();
        Integer[] indices = new Integer[n];
        for (int i = 0; i < n; i++) indices[i] = i;

        Arrays.sort(indices, Comparator.comparingDouble(values::get));

        double[] ranks = new double[n];
        int i = 0;
        while (i < n) {
            int j = i;
            // 找并列值组团
            while (j + 1 < n
                    && Double.compare(values.get(indices[j + 1]), values.get(indices[j])) == 0) {
                j++;
            }
            // 平均排名: (起始排名 + 结束排名) / 2 = (i+1 + j+1) / 2
            double avgRank = (i + j + 2) / 2.0;
            for (int k = i; k <= j; k++) {
                ranks[indices[k]] = avgRank;
            }
            i = j + 1;
        }
        return ranks;
    }


    public static double calcStd(List<Double> values) {
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double sumSq = 0;
        for (double v : values) {
            sumSq += (v - mean) * (v - mean);
        }
        return Math.sqrt(sumSq / (values.size() - 1));
    }


    /** IC 有效性评估 */
    public static String assessIcIr(double icMean, double ir) {
        if (Math.abs(icMean) >= 0.05 && Math.abs(ir) >= 0.5) return "有效因子";
        if (Math.abs(icMean) >= 0.03 && Math.abs(ir) >= 0.3) return "弱有效";
        return "无效因子";
    }


    /** 构建复合因子 IC 结果
     *  @param filteredFactors 实际参与组合的因子列表（已通过预筛选），含 code/ic/weight/sign */
    public static Map<String, Object> buildCompositeResult(String code, String name, List<Double> icSeries,
            int k, String correlationType, List<Map<String, Object>> filteredFactors) {
        double icMean = icSeries.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double icStd = calcStd(icSeries);
        double ir = icStd > 0 ? icMean / icStd : 0;
        long icPos = icSeries.stream().filter(v -> v > 0).count();
        double wr = 100.0 * icPos / icSeries.size();
        int n = icSeries.size();
        double tStat = icStd > 0 ? icMean / (icStd / Math.sqrt(n)) : 0;
        double pValue = 1.0;
        if (n > 1 && icStd > 0) {
            try {
                TDistribution tDist = new TDistribution(n - 1);
                pValue = 2.0 * (1.0 - tDist.cumulativeProbability(Math.abs(tStat)));
            } catch (Exception ignored) {
            }
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("factorCode", code);
        r.put("factorName", name);
        r.put("composite", true);
        r.put("compositeSize", k);
        r.put("forwardDays", 5);
        r.put("icMean", Math.round(icMean * 10000.0) / 10000.0);
        r.put("icStd", Math.round(icStd * 10000.0) / 10000.0);
        r.put("ir", Math.round(ir * 100.0) / 100.0);
        r.put("tStat", Math.round(tStat * 100.0) / 100.0);
        r.put("pValue", Math.round(pValue * 10000.0) / 10000.0);
        r.put("icWinRate", Math.round(wr * 10.0) / 10.0);
        r.put("sampleDays", icSeries.size());
        r.put("assessment", assessIcIr(icMean, ir));
        r.put("correlationType", correlationType);
        // 实际参与组合的因子详情（含IC、权重、方向）
        r.put("filteredFactors", filteredFactors);
        return r;
    }


    public static Map<String, Object> emptyIcResult(String error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("icMean", 0);
        m.put("ir", 0);
        m.put("sampleDays", 0);
        m.put("error", error);
        return m;
    }


    /**
     * 衰减加权均值：Σ(IC_t × 2^(-t/halflife)) / Σ(2^(-t/halflife))
     * t=0 是最新（最后一个），t=N-1 是最远（第一个）
     */
    public static double decayWeightedMean(List<Double> values, int halflifeDays) {
        if (values == null || values.isEmpty()) return 0;
        int n = values.size();
        double sumWeighted = 0, sumWeights = 0;
        for (int i = 0; i < n; i++) {
            // offset: 0=latest, n-1=oldest
            int offset = n - 1 - i;
            double weight = Math.pow(2, -offset / (double) halflifeDays);
            sumWeighted += values.get(i) * weight;
            sumWeights += weight;
        }
        return sumWeights > 0 ? sumWeighted / sumWeights : 0;
    }
}
