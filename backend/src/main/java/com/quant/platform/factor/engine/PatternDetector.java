package com.quant.platform.factor.engine;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import java.util.ArrayList;
import java.util.List;

/**
 * 形态检测引擎
 * 基于《稳中求胜》5大起涨形态，使用标准技术指标（MACD/BOLL/KDJ/均线/量价）实现
 * 不依赖缠论，纯技术面形态识别
 */
@Slf4j
public final class PatternDetector {

    private PatternDetector() {}

    /**
     * 形态类型
     */
    public enum PatternType {
        BOTTOM_REVERSAL("底部反转", "双底/W底结构 + MACD底背离 + 放量突破"),
        MAIN_TREND("主升浪", "突破平台整理区 + 均线多头排列 + 放量"),
        BREAKOUT("变盘突破", "布林带收窄后突破 + 量能配合"),
        SMALL_SWING("小波段", "均线金叉 + 量价配合 + 短期趋势确立"),
        BOTTOM_CONFIRMED("底部探明", "长下影线探底 + 缩量回调 + MACD底背离");

        private final String name;
        private final String desc;
        PatternType(String name, String desc) { this.name = name; this.desc = desc; }
        public String getName() { return name; }
        public String getDesc() { return desc; }
    }

    /**
     * 形态检测结果
     */
    @Data
    public static class PatternResult {
        private PatternType patternType;
        private boolean detected;
        private double score;          // 0~100 形态强度
        private String description;
        private List<String> signals;  // 触发的信号列表

        public PatternResult(PatternType type) {
            this.patternType = type;
            this.signals = new ArrayList<>();
        }

        public static PatternResult notDetected(PatternType type) {
            PatternResult r = new PatternResult(type);
            r.detected = false;
            r.score = 0;
            r.description = "未检测到" + type.getName() + "形态";
            return r;
        }
    }

    /**
     * 执行全部形态检测，返回所有命中的形态
     */
    public static List<PatternResult> detectAll(double[] high, double[] low, double[] open, double[] close, double[] volume) {
        List<PatternResult> results = new ArrayList<>();
        for (PatternType type : PatternType.values()) {
            PatternResult r = detect(type, high, low, open, close, volume);
            if (r.isDetected()) results.add(r);
        }
        return results;
    }

    /**
     * 检测指定形态
     */
    public static PatternResult detect(PatternType type, double[] high, double[] low, double[] open, double[] close, double[] volume) {
        try {
            return switch (type) {
                case BOTTOM_REVERSAL -> detectBottomReversal(high, low, open, close, volume);
                case MAIN_TREND -> detectMainTrend(high, low, open, close, volume);
                case BREAKOUT -> detectBreakout(high, low, open, close, volume);
                case SMALL_SWING -> detectSmallSwing(high, low, open, close, volume);
                case BOTTOM_CONFIRMED -> detectBottomConfirmed(high, low, open, close, volume);
            };
        } catch (Exception e) {
            log.warn("[PatternDetector] {} 检测异常: {}", type.getName(), e.getMessage());
            return PatternResult.notDetected(type);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 1. 底部反转：双底/W底 + MACD底背离 + 放量突破
    // ═══════════════════════════════════════════════════════════

    private static PatternResult detectBottomReversal(double[] high, double[] low, double[] open, double[] close, double[] volume) {
        PatternResult result = new PatternResult(PatternType.BOTTOM_REVERSAL);
        int n = close.length;
        if (n < 60) return PatternResult.notDetected(PatternType.BOTTOM_REVERSAL);

        double[][] macdData = TechnicalIndicatorUtils.macd(close);
        double[] dif = macdData[0];
        int score = 0;

        // 1. MACD底背离
        boolean macdDivergence = TechnicalIndicatorUtils.isMacdBottomDivergence(close, dif, 30);
        if (macdDivergence) { score += 30; result.getSignals().add("MACD底背离"); }

        // 2. 近期双底结构：30日内有两个低点，差距 < 3%
        int lookback = Math.min(30, n - 1);
        int low1Idx = -1, low2Idx = -1;
        double low1 = Double.MAX_VALUE, low2 = Double.MAX_VALUE;
        for (int i = n - lookback; i < n - 1; i++) {
            if (low[i] < low1) { low2 = low1; low2Idx = low1Idx; low1 = low[i]; low1Idx = i; }
            else if (low[i] < low2) { low2 = low[i]; low2Idx = i; }
        }
        boolean doubleBottom = low1Idx >= 0 && low2Idx >= 0
                && Math.abs(low1 - low2) / Math.min(low1, low2) < 0.03
                && Math.abs(low1Idx - low2Idx) >= 5;
        if (doubleBottom) { score += 25; result.getSignals().add("双底结构"); }

        // 3. 放量突破：最新日放量且收盘价高于双底之间的高点
        boolean volumeSurge = TechnicalIndicatorUtils.isVolumeSurge(volume, 10, 1.5);
        double betweenHigh = Double.MIN_VALUE;
        if (low1Idx >= 0 && low2Idx >= 0) {
            int start = Math.min(low1Idx, low2Idx);
            int end = Math.max(low1Idx, low2Idx);
            for (int i = start; i <= end; i++) betweenHigh = Math.max(betweenHigh, high[i]);
        }
        boolean breakout = volumeSurge && close[n - 1] > betweenHigh;
        if (breakout) { score += 25; result.getSignals().add("放量突破颈线"); }

        // 4. KDJ低位金叉
        double[][] kdjData = TechnicalIndicatorUtils.kdj(high, low, close);
        boolean kdjCross = TechnicalIndicatorUtils.isKdjLowGoldenCross(kdjData[0], kdjData[1]);
        if (kdjCross) { score += 20; result.getSignals().add("KDJ低位金叉"); }

        result.setScore(score);
        result.setDetected(score >= 50);
        result.setDescription(score >= 50 ? "底部反转形态成立" : "底部反转形态未成立");
        return result;
    }

    // ═══════════════════════════════════════════════════════════
    // 2. 主升浪：突破平台整理 + 均线多头排列 + 放量
    // ═══════════════════════════════════════════════════════════

    private static PatternResult detectMainTrend(double[] high, double[] low, double[] open, double[] close, double[] volume) {
        PatternResult result = new PatternResult(PatternType.MAIN_TREND);
        int n = close.length;
        if (n < 60) return PatternResult.notDetected(PatternType.MAIN_TREND);

        int score = 0;

        // 1. 均线多头排列 MA5 > MA10 > MA20 > MA60
        boolean maBull = TechnicalIndicatorUtils.isMaBullishAlignment(close);
        if (maBull) { score += 30; result.getSignals().add("均线多头排列"); }

        // 2. 突破20日平台高点
        boolean breakout = TechnicalIndicatorUtils.isBreakoutHigh(close, high, 20);
        if (breakout) { score += 25; result.getSignals().add("突破20日平台"); }

        // 3. 放量
        boolean volumeSurge = TechnicalIndicatorUtils.isVolumeSurge(volume, 10, 1.5);
        if (volumeSurge) { score += 20; result.getSignals().add("放量配合"); }

        // 4. MACD金叉或在0轴上方
        double[][] macdData = TechnicalIndicatorUtils.macd(close);
        boolean macdBull = TechnicalIndicatorUtils.isMacdGoldenCross(macdData[0], macdData[1]) || macdData[0][n - 1] > 0;
        if (macdBull) { score += 25; result.getSignals().add("MACD多头信号"); }

        result.setScore(score);
        result.setDetected(score >= 60);
        result.setDescription(score >= 60 ? "主升浪形态成立" : "主升浪形态未成立");
        return result;
    }

    // ═══════════════════════════════════════════════════════════
    // 3. 变盘突破：布林带收窄后突破 + 量能配合
    // ═══════════════════════════════════════════════════════════

    private static PatternResult detectBreakout(double[] high, double[] low, double[] open, double[] close, double[] volume) {
        PatternResult result = new PatternResult(PatternType.BREAKOUT);
        int n = close.length;
        if (n < 30) return PatternResult.notDetected(PatternType.BREAKOUT);

        int score = 0;

        // 1. 布林带收窄
        boolean squeeze = TechnicalIndicatorUtils.isBollSqueeze(close, 20);
        if (squeeze) { score += 30; result.getSignals().add("布林带收窄"); }

        // 2. 突破布林上轨
        double[][] boll = TechnicalIndicatorUtils.boll(close, 20, 2.0);
        boolean breakUpper = close[n - 1] > boll[1][n - 1];
        if (breakUpper) { score += 25; result.getSignals().add("突破布林上轨"); }

        // 3. 放量配合
        boolean volumeSurge = TechnicalIndicatorUtils.isVolumeSurge(volume, 10, 1.5);
        if (volumeSurge) { score += 25; result.getSignals().add("放量突破"); }

        // 4. MA5上穿MA10
        boolean maCross = TechnicalIndicatorUtils.isMaGoldenCross(close, 5, 10);
        if (maCross) { score += 20; result.getSignals().add("MA5金叉MA10"); }

        result.setScore(score);
        result.setDetected(score >= 50);
        result.setDescription(score >= 50 ? "变盘突破形态成立" : "变盘突破形态未成立");
        return result;
    }

    // ═══════════════════════════════════════════════════════════
    // 4. 小波段：均线金叉 + 量价配合 + 短期趋势确立
    // ═══════════════════════════════════════════════════════════

    private static PatternResult detectSmallSwing(double[] high, double[] low, double[] open, double[] close, double[] volume) {
        PatternResult result = new PatternResult(PatternType.SMALL_SWING);
        int n = close.length;
        if (n < 30) return PatternResult.notDetected(PatternType.SMALL_SWING);

        int score = 0;

        // 1. MA5金叉MA10 或 MA10金叉MA20
        boolean maCross510 = TechnicalIndicatorUtils.isMaGoldenCross(close, 5, 10);
        boolean maCross1020 = TechnicalIndicatorUtils.isMaGoldenCross(close, 10, 20);
        if (maCross510) { score += 25; result.getSignals().add("MA5金叉MA10"); }
        if (maCross1020) { score += 20; result.getSignals().add("MA10金叉MA20"); }

        // 2. 放量上涨
        boolean volPriceUp = TechnicalIndicatorUtils.isVolumePriceUp(close, open, volume, 10, 1.3);
        if (volPriceUp) { score += 25; result.getSignals().add("放量上涨"); }

        // 3. MACD金叉
        double[][] macdData = TechnicalIndicatorUtils.macd(close);
        boolean macdCross = TechnicalIndicatorUtils.isMacdGoldenCross(macdData[0], macdData[1]);
        if (macdCross) { score += 20; result.getSignals().add("MACD金叉"); }

        // 4. 价格在MA20上方
        double[] ma20 = TechnicalIndicatorUtils.sma(close, 20);
        boolean aboveMa20 = close[n - 1] > ma20[n - 1];
        if (aboveMa20) { score += 10; result.getSignals().add("站上MA20"); }

        result.setScore(score);
        result.setDetected(score >= 50);
        result.setDescription(score >= 50 ? "小波段形态成立" : "小波段形态未成立");
        return result;
    }

    // ═══════════════════════════════════════════════════════════
    // 5. 底部探明：长下影线探底 + 缩量回调 + MACD底背离
    // ═══════════════════════════════════════════════════════════

    private static PatternResult detectBottomConfirmed(double[] high, double[] low, double[] open, double[] close, double[] volume) {
        PatternResult result = new PatternResult(PatternType.BOTTOM_CONFIRMED);
        int n = close.length;
        if (n < 30) return PatternResult.notDetected(PatternType.BOTTOM_CONFIRMED);

        int score = 0;

        // 1. 长下影线
        boolean longLower = TechnicalIndicatorUtils.isLongLowerShadow(high, low, open, close);
        if (longLower) { score += 30; result.getSignals().add("长下影线探底"); }

        // 2. 缩量（抛压减轻）
        boolean shrink = TechnicalIndicatorUtils.isVolumeShrink(volume, 10, 0.7);
        if (shrink) { score += 20; result.getSignals().add("缩量回调"); }

        // 3. MACD底背离
        double[][] macdData = TechnicalIndicatorUtils.macd(close);
        double[] dif = macdData[0];
        boolean macdDivergence = TechnicalIndicatorUtils.isMacdBottomDivergence(close, dif, 20);
        if (macdDivergence) { score += 25; result.getSignals().add("MACD底背离"); }

        // 4. RSI超卖回升（RSI < 30 后回升）
        double[] rsi = TechnicalIndicatorUtils.rsi(close, 14);
        boolean rsiRecover = rsi[n - 1] > rsi[n - 2] && rsi[n - 2] < 35;
        if (rsiRecover) { score += 25; result.getSignals().add("RSI超卖回升"); }

        result.setScore(score);
        result.setDetected(score >= 50);
        result.setDescription(score >= 50 ? "底部探明形态成立" : "底部探明形态未成立");
        return result;
    }

    /**
     * 获取最强形态（得分最高的命中形态）
     */
    public static PatternResult getStrongestPattern(double[] high, double[] low, double[] open, double[] close, double[] volume) {
        List<PatternResult> detected = detectAll(high, low, open, close, volume);
        return detected.stream()
                .max((a, b) -> Double.compare(a.getScore(), b.getScore()))
                .orElse(null);
    }
}
