package com.quant.platform.recommendation.service;

import java.util.Arrays;
import java.util.List;

/**
 * 推荐服务的纯静态数学/字符串工具。
 * 从 {@link RecommendationService} 抽取（God Class 拆分 Phase 1），方法体保持逐字一致，仅变更归属类。
 * 所有方法为无状态纯函数，调用方须使用 {@code RecommendationMath.xxx(...)} 全限定调用。
 */
public final class RecommendationMath {

    private RecommendationMath() {
    }

    /**
     * 去掉股票代码后缀: "600027.SH" → "600027"
     */
    public static String stripSuffix(String code) {
        if (code == null) return null;
        int dot = code.indexOf('.');
        return dot > 0 ? code.substring(0, dot) : code;
    }

    /**
     * 将 TradingSignalEngine 的 5 值 action 映射为前端 3 值 actionTag
     * STRONG_BUY→BUY, BUY→BUY, HOLD→HOLD, REDUCE→HOLD, CLEAR→SELL
     */
    public static String mapActionTag(String action) {
        if (action == null) return null;
        return switch (action) {
            case "STRONG_BUY" -> "BUY";
            case "BUY" -> "BUY";
            case "HOLD" -> "HOLD";
            case "REDUCE" -> "HOLD";  // 减仓但仍在持有，前端归为 HOLD
            case "CLEAR" -> "SELL";   // 清仓映射为卖出
            default -> "HOLD";        // 未知归为持有
        };
    }

    public static double safeDiv(Integer numerator, double denominator) {
        if (numerator == null || denominator == 0) return 0.0;
        return Math.min(1.0, numerator / denominator);
    }

    public static long toLong(Object obj) {
        if (obj == null) return 0;
        if (obj instanceof Number) return ((Number) obj).longValue();
        try {
            return Long.parseLong(obj.toString());
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 计算数组标准差
     */
    public static double std(double[] values) {
        if (values.length == 0) return 0;
        double mean = Arrays.stream(values).average().orElse(0);
        double variance = Arrays.stream(values).map(x -> (x - mean) * (x - mean)).average().orElse(0);
        return Math.sqrt(variance);
    }

    /**
     * 计算中位数
     */
    public static Double median(List<Double> values) {
        if (values.isEmpty()) return null;
        List<Double> sorted = new java.util.ArrayList<>(values);
        java.util.Collections.sort(sorted);
        int n = sorted.size();
        if (n % 2 == 1) return sorted.get(n / 2);
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    /**
     * 计算最近 N 天的均值
     */
    public static double avg(List<Double> values, int n) {
        int size = values.size();
        if (size < n) return 0;
        double sum = 0;
        for (int i = size - n; i < size; i++) {
            sum += values.get(i);
        }
        return sum / n;
    }

    /**
     * 计算 ATR (Average True Range)
     */
    public static double calcATR(List<Double> highs, List<Double> lows, List<Double> closes, int period) {
        int size = closes.size();
        if (size < period + 1) return 0;

        double atr;
        // 初始值用第一个真实波幅
        double prevClose = closes.get(size - period - 1);
        atr = Math.max(
                Math.abs(highs.get(size - period) - lows.get(size - period)),
                Math.max(
                        Math.abs(highs.get(size - period) - prevClose),
                        Math.abs(lows.get(size - period) - prevClose)));

        for (int i = size - period + 1; i < size; i++) {
            double prevC = closes.get(i - 1);
            double tr = Math.max(
                    Math.abs(highs.get(i) - lows.get(i)),
                    Math.max(
                            Math.abs(highs.get(i) - prevC),
                            Math.abs(lows.get(i) - prevC)));
            atr = (atr * (period - 1) + tr) / period; // EMA 平滑
        }
        return atr;
    }

    /**
     * 计算当前值在历史序列中的分位数
     *
     * @return 0~1, 越大说明当前值相对历史越高
     */
    public static double calcPercentile(double value, List<Double> history) {
        if (history == null || history.isEmpty()) return 0.5;
        long countBelow = history.stream().filter(v -> v < value).count();
        return (double) countBelow / history.size();
    }
}
