package com.quant.platform.screen.service;

import com.quant.platform.factor.domain.FactorDefinition.FactorCategory;
import com.quant.platform.factor.domain.FactorValue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 选股统计计算服务
 * 去极值（MAD/3σ/百分位截断）、标准化（Z-Score/MinMax/百分位排名）、
 * 相关性与偏度等纯函数，无外部依赖、无状态。
 */
@Slf4j
@Service
public class ScreenMathService {

    /**
     * 阈值从 factor_definition.cv_threshold 读取（数据驱动）。
     * 若该因子未设置 cv_threshold，则按 category 推导默认值：
     * - MOMENTUM / 含 CORR/VPCORR 的技术因子：宽松(3.0)
     * - VOLATILITY / LIQUIDITY / VOLUME_PRICE：中等(2.0)
     * - 其他（TECHNICAL/FINANCIAL/VALUE/SENTIMENT/CHANTHEORY）：严格(0.5)
     */
    public static final double DEFAULT_CV_THRESHOLD = 0.5;

    /**
     * 筛选条件判断
     */
    public boolean passFilter(double value, String op, Double threshold) {
        if (op == null || "NONE".equals(op) || threshold == null) return true;
        return switch (op.toUpperCase()) {
            case "GT" -> value > threshold;
            case "GTE" -> value >= threshold;
            case "LT" -> value < threshold;
            case "LTE" -> value <= threshold;
            case "EQ" -> Math.abs(value - threshold) < 1e-10;
            default -> true;
        };
    }

    /**
     * 根据 FactorCategory 推导 CV 阈值默认值
     */
    public static double getCategoryBasedCV(FactorCategory category) {
        return switch (category) {
            case MOMENTUM -> 3.0;
            case VOLATILITY, LIQUIDITY, VOLUME_PRICE -> 2.0;
            default -> DEFAULT_CV_THRESHOLD;
        };
    }

    /**
     * 统一 symbol 格式：去掉 .SZ/.SH/.BJ 等交易所后缀
     * 解决 CH factor_value 中 5月12日前后 symbol 格式不一致的问题
     */
    public static String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isEmpty()) return symbol;
        int dot = symbol.lastIndexOf('.');
        if (dot > 0) {
            String suffix = symbol.substring(dot + 1).toUpperCase();
            if (suffix.equals("SZ") || suffix.equals("SH") || suffix.equals("BJ")) {
                return symbol.substring(0, dot);
            }
        }
        return symbol;
    }

    /**
     * 归一化 symbol：去掉 .SH/.SZ/.BJ 等交易所后缀，返回纯代码。
     * 用于统一 factor_value.symbol（可能带后缀）和 candidates（纯代码）的格式。
     */
    public static String normalizeFactorSymbol(String symbol) {
        if (symbol == null) return null;
        int dot = symbol.lastIndexOf('.');
        return dot > 0 ? symbol.substring(0, dot) : symbol;
    }

    /**
     * 极值处理
     */
    public List<Double> applyOutlierProcessing(List<Double> values, String method) {
        if (values == null || values.isEmpty()) return values;
        if (method == null || "NONE".equalsIgnoreCase(method)) return values;

        List<Double> sorted = values.stream().sorted().toList();
        return switch (method.toUpperCase()) {
            case "MAD" -> applyMAD(values);
            case "SIGMA3", "3SIGMA" -> applySigma3(values);
            case "PERCENTILE" -> applyPercentileClip(values, 0.01, 0.99);
            default -> values;
        };
    }

    /**
     * MAD（中位数去极值法）
     * 中位数 ± 5*MAD 范围外的值截断
     */
    public List<Double> applyMAD(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        int n = sorted.size();
        double median = n % 2 == 0
                ? (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0
                : sorted.get(n / 2);

        List<Double> absDeviations = values.stream()
                .map(v -> Math.abs(v - median))
                .sorted()
                .toList();
        double mad = n % 2 == 0
                ? (absDeviations.get(n / 2 - 1) + absDeviations.get(n / 2)) / 2.0
                : absDeviations.get(n / 2);

        double lower = median - 5 * mad;
        double upper = median + 5 * mad;

        return values.stream()
                .map(v -> Math.max(lower, Math.min(upper, v)))
                .collect(Collectors.toList());
    }

    /**
     * 3σ 法
     * 均值 ± 3*标准差 范围外的值截断
     */
    public List<Double> applySigma3(List<Double> values) {
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double variance = values.stream()
                .mapToDouble(v -> (v - mean) * (v - mean))
                .average()
                .orElse(0.0);
        double std = Math.sqrt(variance);

        double lower = mean - 3 * std;
        double upper = mean + 3 * std;

        return values.stream()
                .map(v -> Math.max(lower, Math.min(upper, v)))
                .collect(Collectors.toList());
    }

    /**
     * 百分位截断
     */
    public List<Double> applyPercentileClip(List<Double> values, double lowerP, double upperP) {
        List<Double> sorted = values.stream().sorted().toList();
        int n = sorted.size();
        int lowerIdx = (int) (n * lowerP);
        int upperIdx = (int) (n * upperP);
        double lower = sorted.get(Math.max(0, lowerIdx));
        double upper = sorted.get(Math.min(n - 1, upperIdx));

        return values.stream()
                .map(v -> Math.max(lower, Math.min(upper, v)))
                .collect(Collectors.toList());
    }

    /**
     * 计算偏度（P1-6 因子分布诊断）
     */
    public double calcSkewness(List<Double> values) {
        if (values == null || values.size() < 3) return 0;
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = values.stream().mapToDouble(v -> Math.pow(v - mean, 2)).average().orElse(0);
        double std = Math.sqrt(variance);
        if (std == 0) return 0;
        double n = values.size();
        return values.stream().mapToDouble(v -> Math.pow((v - mean) / std, 3)).average().orElse(0) * n / (n - 1) / (n - 2);
    }

    /**
     * 标准化处理
     */
    public List<Double> applyNormalization(List<Double> values, String method) {
        if (values == null || values.isEmpty()) return values;
        if (method == null || "NONE".equalsIgnoreCase(method)) return values;

        return switch (method.toUpperCase()) {
            case "ZSCORE" -> applyZScore(values);
            case "MINMAX" -> applyMinMax(values);
            case "RANK", "PERCENTRANK" -> applyPercentRank(values);
            default -> values;
        };
    }

    /**
     * Z-Score 标准化
     * (x - mean) / std
     */
    public List<Double> applyZScore(List<Double> values) {
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double variance = values.stream()
                .mapToDouble(v -> (v - mean) * (v - mean))
                .average()
                .orElse(0.0);
        double std = Math.sqrt(variance);
        if (std < 1e-10) return values.stream().map(v -> 0.0).collect(Collectors.toList());

        return values.stream()
                .map(v -> (v - mean) / std)
                .collect(Collectors.toList());
    }

    /**
     * Min-Max 归一化到 [0, 1]
     */
    public List<Double> applyMinMax(List<Double> values) {
        double min = values.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
        double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
        double range = max - min;
        if (range < 1e-10) return values.stream().map(v -> 0.5).collect(Collectors.toList());

        return values.stream()
                .map(v -> (v - min) / range)
                .collect(Collectors.toList());
    }

    /**
     * 百分位排名（0-1）
     */
    public List<Double> applyPercentRank(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        int n = sorted.size();
        return values.stream()
                .map(v -> {
                    int rank = 0;
                    for (int i = 0; i < n; i++) {
                        if (sorted.get(i) < v) rank++;
                    }
                    return n <= 1 ? 0.5 : (double) rank / (n - 1);
                })
                .collect(Collectors.toList());
    }

    public BigDecimal toBD(Object val) {
        return switch (val) {
            case BigDecimal bigDecimal -> bigDecimal;
            case Number number -> BigDecimal.valueOf(number.doubleValue()).setScale(2, RoundingMode.HALF_UP);
            case null, default -> null;
        };
    }

    public double dotProduct(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) sum += a[i] * b[i];
        return sum;
    }

    public double standardDeviation(double[] values) {
        double mean = 0;
        for (double v : values) mean += v;
        mean /= values.length;
        double var = 0;
        for (double v : values) var += (v - mean) * (v - mean);
        return Math.sqrt(var / values.length);
    }

    public double avgCorrelation(double[][] matrix) {
        int n = matrix.length;
        if (n < 2) return 0;
        double totalCorr = 0;
        int pairs = 0;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                totalCorr += Math.abs(pearsonCorrelation(matrix[i], matrix[j]));
                pairs++;
            }
        }
        return pairs > 0 ? totalCorr / pairs : 0;
    }

    public double pearsonCorrelation(double[] x, double[] y) {
        int n = x.length;
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0, sumY2 = 0;
        for (int i = 0; i < n; i++) {
            sumX += x[i];
            sumY += y[i];
            sumXY += x[i] * y[i];
            sumX2 += x[i] * x[i];
            sumY2 += y[i] * y[i];
        }
        double num = n * sumXY - sumX * sumY;
        double den = Math.sqrt((n * sumX2 - sumX * sumX) * (n * sumY2 - sumY * sumY));
        return den == 0 ? 0 : num / den;
    }

    /**
     * 因子值比较
     */
    public boolean compareFactorValue(double actual, String op, double threshold) {
        return switch (op.toUpperCase()) {
            case "GT" -> actual > threshold;
            case "GTE" -> actual >= threshold;
            case "LT" -> actual < threshold;
            case "LTE" -> actual <= threshold;
            case "EQ" -> Math.abs(actual - threshold) < 1e-8;
            default -> true; // UNKNOWN op 不过滤
        };
    }

}
