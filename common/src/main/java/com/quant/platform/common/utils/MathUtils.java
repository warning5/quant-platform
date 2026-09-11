package com.quant.platform.common.utils;

import java.util.List;

/**
 * 通用数学工具。
 */
public final class MathUtils {

    private MathUtils() {
    }

    /**
     * 皮尔逊相关系数（等长数组）。分母为 0（常数序列）时返回 NaN。
     */
    public static double pearson(double[] x, double[] y) {
        int n = x.length;
        if (n == 0) return Double.NaN;
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0, sumY2 = 0;
        for (int i = 0; i < n; i++) {
            double xi = x[i], yi = y[i];
            sumX += xi;
            sumY += yi;
            sumXY += xi * yi;
            sumX2 += xi * xi;
            sumY2 += yi * yi;
        }
        double denom = Math.sqrt((n * sumX2 - sumX * sumX) * (n * sumY2 - sumY * sumY));
        if (denom == 0) return Double.NaN;
        return (n * sumXY - sumX * sumY) / denom;
    }

    /**
     * 皮尔逊相关系数（等长 List）。分母为 0（常数序列）时返回 NaN。
     */
    public static double pearson(List<Double> x, List<Double> y) {
        int n = x.size();
        if (n == 0) return Double.NaN;
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0, sumY2 = 0;
        for (int i = 0; i < n; i++) {
            double xi = x.get(i), yi = y.get(i);
            sumX += xi;
            sumY += yi;
            sumXY += xi * yi;
            sumX2 += xi * xi;
            sumY2 += yi * yi;
        }
        double denom = Math.sqrt((n * sumX2 - sumX * sumX) * (n * sumY2 - sumY * sumY));
        if (denom == 0) return Double.NaN;
        return (n * sumXY - sumX * sumY) / denom;
    }
}
