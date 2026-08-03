package com.quant.platform.factor.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TechnicalIndicatorUtils 单元测试
 * 纯逻辑测试，不依赖任何外部服务
 */
@DisplayName("TechnicalIndicatorUtils 技术指标计算")
class TechnicalIndicatorUtilsTest {

    private static final double DELTA = 1e-9;

    // ==================== SMA ====================

    @Test
    @DisplayName("SMA - 正常计算")
    void testSma() {
        double[] close = {10, 20, 30, 40, 50};
        double[] sma = TechnicalIndicatorUtils.sma(close, 3);
        // SMA(3): [NaN, NaN, 20, 30, 40]
        assertEquals(Double.NaN, sma[0], "前2个应为NaN");
        assertEquals(Double.NaN, sma[1], "前2个应为NaN");
        assertEquals(20.0, sma[2], DELTA);
        assertEquals(30.0, sma[3], DELTA);
        assertEquals(40.0, sma[4], DELTA);
    }

    @Test
    @DisplayName("SMA - period=1 等于原值")
    void testSmaPeriod1() {
        double[] close = {5, 10, 15};
        double[] sma = TechnicalIndicatorUtils.sma(close, 1);
        assertArrayEquals(close, sma, DELTA);
    }

    // ==================== EMA ====================

    @Test
    @DisplayName("EMA - 首值等于close[0]")
    void testEmaFirstValue() {
        double[] close = {100, 110, 105};
        double[] ema = TechnicalIndicatorUtils.ema(close, 12);
        assertEquals(100.0, ema[0], DELTA);
    }

    @Test
    @DisplayName("EMA - 单调序列 EMA 在 close 和 prev 之间")
    void testEmaMonotonic() {
        double[] close = {100, 110, 120, 130, 140};
        double[] ema = TechnicalIndicatorUtils.ema(close, 5);
        double multiplier = 2.0 / 6;
        double expected1 = 110 * multiplier + 100 * (1 - multiplier);
        assertEquals(expected1, ema[1], DELTA);
    }

    // ==================== MACD ====================

    @Test
    @DisplayName("MACD - 返回三个数组 DIF/DEA/HIST")
    void testMacdShape() {
        double[] close = new double[30];
        for (int i = 0; i < 30; i++) close[i] = 100 + i;
        double[][] macd = TechnicalIndicatorUtils.macd(close);
        assertEquals(3, macd.length, "应有 DIF/DEA/HIST 三个数组");
        assertEquals(30, macd[0].length, "DIF 长度应等于 close 长度");
    }

    @Test
    @DisplayName("MACD 金叉检测 - DIF 从下方上穿 DEA")
    void testMacdGoldenCross() {
        double[] dif = {1, 2, 3};
        double[] dea = {2, 2, 2};
        assertTrue(TechnicalIndicatorUtils.isMacdGoldenCross(dif, dea));
    }

    @Test
    @DisplayName("MACD 金叉检测 - 不满足时返回 false")
    void testMacdNoGoldenCross() {
        double[] dif = {3, 2, 1};
        double[] dea = {2, 2, 2};
        assertFalse(TechnicalIndicatorUtils.isMacdGoldenCross(dif, dea));
    }

    @Test
    @DisplayName("MACD 死叉检测 - DIF 从上方下穿 DEA")
    void testMacdDeathCross() {
        double[] dif = {3, 2, 1};
        double[] dea = {2, 2, 2};
        assertTrue(TechnicalIndicatorUtils.isMacdDeathCross(dif, dea));
    }

    @Test
    @DisplayName("MACD 金叉 - 数组不足返回 false")
    void testMacdCrossShortArray() {
        double[] dif = {1};
        double[] dea = {2};
        assertFalse(TechnicalIndicatorUtils.isMacdGoldenCross(dif, dea));
        assertFalse(TechnicalIndicatorUtils.isMacdDeathCross(dif, dea));
    }

    // ==================== RSI ====================

    @Test
    @DisplayName("RSI - 单调上升趋近100")
    void testRsiUptrend() {
        double[] close = new double[20];
        for (int i = 0; i < 20; i++) close[i] = 100 + i * 2;
        double[] rsi = TechnicalIndicatorUtils.rsi(close, 14);
        assertTrue(rsi[14] > 95, "单调上升 RSI 应接近100: " + rsi[14]);
        assertTrue(rsi[19] > 95, "单调上升 RSI 应接近100: " + rsi[19]);
    }

    @Test
    @DisplayName("RSI - 单调下降趋近0")
    void testRsiDowntrend() {
        double[] close = new double[20];
        for (int i = 0; i < 20; i++) close[i] = 200 - i * 2;
        double[] rsi = TechnicalIndicatorUtils.rsi(close, 14);
        assertTrue(rsi[14] < 5, "单调下降 RSI 应接近0: " + rsi[14]);
    }

    // ==================== ATR ====================

    @Test
    @DisplayName("ATR - 恒定振幅等于该振幅")
    void testAtrConstantRange() {
        double[] high = {110, 110, 110, 110, 110};
        double[] low = {100, 100, 100, 100, 100};
        double[] close = {105, 105, 105, 105, 105};
        double[] atr = TechnicalIndicatorUtils.atr(high, low, close, 3);
        assertEquals(10.0, atr[2], DELTA, "恒定振幅10, ATR应=10");
        assertEquals(10.0, atr[4], DELTA, "恒定振幅10, ATR应=10");
    }

    // ==================== maxDrawdown ====================

    @Test
    @DisplayName("maxDrawdown - 简单回撤")
    void testMaxDrawdown() {
        double[] close = {100, 120, 80, 90, 110};
        // peak=120, trough=80, DD = (120-80)/120 = 33.33%
        double dd = TechnicalIndicatorUtils.maxDrawdown(close);
        assertEquals(1.0 / 3.0, dd, 0.001, "最大回撤应为 33.33%");
    }

    @Test
    @DisplayName("maxDrawdown - 无回撤返回0")
    void testMaxDrawdownNoDrawdown() {
        double[] close = {10, 20, 30, 40, 50};
        assertEquals(0.0, TechnicalIndicatorUtils.maxDrawdown(close), DELTA);
    }

    // ==================== volatility ====================

    @Test
    @DisplayName("volatility - 数据不足返回0")
    void testVolatilityInsufficientData() {
        double[] close = {100, 101};
        assertEquals(0.0, TechnicalIndicatorUtils.volatility(close, 14), DELTA);
    }

    @Test
    @DisplayName("volatility - 正常计算为正数")
    void testVolatilityPositive() {
        double[] close = new double[30];
        for (int i = 0; i < 30; i++) close[i] = 100 + Math.sin(i) * 5;
        double vol = TechnicalIndicatorUtils.volatility(close, 14);
        assertTrue(vol > 0, "有波动的序列 volatility 应 > 0");
    }

    // ==================== 均线排列 ====================

    @Test
    @DisplayName("均线多头排列 - 持续上升")
    void testMaBullishAlignment() {
        double[] close = new double[65];
        for (int i = 0; i < 65; i++) close[i] = 50 + i;
        assertTrue(TechnicalIndicatorUtils.isMaBullishAlignment(close));
    }

    @Test
    @DisplayName("均线空头排列 - 持续下降")
    void testMaBearishAlignment() {
        double[] close = new double[65];
        for (int i = 0; i < 65; i++) close[i] = 200 - i;
        assertTrue(TechnicalIndicatorUtils.isMaBearishAlignment(close));
    }

    @Test
    @DisplayName("均线排列 - 数据不足返回false")
    void testMaAlignmentShortData() {
        double[] close = {1, 2, 3};
        assertFalse(TechnicalIndicatorUtils.isMaBullishAlignment(close));
        assertFalse(TechnicalIndicatorUtils.isMaBearishAlignment(close));
    }

    // ==================== 量价分析 ====================

    @Test
    @DisplayName("放量检测 - 最新量大于均量2倍")
    void testVolumeSurge() {
        double[] vol = {1000, 1000, 1000, 1000, 1000, 3000};
        assertTrue(TechnicalIndicatorUtils.isVolumeSurge(vol, 5, 2.0));
    }

    @Test
    @DisplayName("缩量检测 - 最新量小于均量0.5倍")
    void testVolumeShrink() {
        double[] vol = {1000, 1000, 1000, 1000, 1000, 400};
        assertTrue(TechnicalIndicatorUtils.isVolumeShrink(vol, 5, 0.5));
    }

    @Test
    @DisplayName("放量上涨 - 量放大且收阳")
    void testVolumePriceUp() {
        double[] close = {10, 10, 10, 10, 10, 12};
        double[] open = {10, 10, 10, 10, 10, 10};
        double[] vol = {1000, 1000, 1000, 1000, 1000, 3000};
        assertTrue(TechnicalIndicatorUtils.isVolumePriceUp(close, open, vol, 5, 2.0));
    }

    // ==================== K线形态 ====================

    @Test
    @DisplayName("长上影线")
    void testLongUpperShadow() {
        // 实体=1, 上影=5, 下影=0.5, 总振幅=6
        double[] high = {106};
        double[] low = {100};
        double[] open = {100};
        double[] close = {101};
        assertTrue(TechnicalIndicatorUtils.isLongUpperShadow(high, low, open, close));
    }

    @Test
    @DisplayName("长下影线")
    void testLongLowerShadow() {
        // 实体=1, 下影=5, 上影=0.5
        double[] high = {106};
        double[] low = {100};
        double[] open = {106};
        double[] close = {105};
        assertTrue(TechnicalIndicatorUtils.isLongLowerShadow(high, low, open, close));
    }

    @Test
    @DisplayName("突破前期高点")
    void testBreakoutHigh() {
        double[] close = {10, 11, 12, 13, 14, 20};
        double[] high = {11, 12, 13, 14, 15, 20};
        assertTrue(TechnicalIndicatorUtils.isBreakoutHigh(close, high, 5));
    }

    @Test
    @DisplayName("跌破前期低点")
    void testBreakdownLow() {
        double[] close = {20, 18, 16, 14, 12, 5};
        double[] low = {19, 17, 15, 13, 11, 5};
        assertTrue(TechnicalIndicatorUtils.isBreakdownLow(close, low, 5));
    }

    // ==================== recentHigh / recentLow ====================

    @Test
    @DisplayName("recentHigh - 取N日最高")
    void testRecentHigh() {
        double[] high = {10, 20, 15, 30, 25};
        assertEquals(30.0, TechnicalIndicatorUtils.recentHigh(high, 3), DELTA);
    }

    @Test
    @DisplayName("recentLow - 取N日最低")
    void testRecentLow() {
        double[] low = {10, 5, 15, 3, 8};
        assertEquals(3.0, TechnicalIndicatorUtils.recentLow(low, 3), DELTA);
    }

    // ==================== 布林带 ====================

    @Test
    @DisplayName("布林带 - 上轨 > 中轨 > 下轨")
    void testBollOrder() {
        double[] close = new double[25];
        for (int i = 0; i < 25; i++) close[i] = 100 + Math.sin(i) * 3;
        double[][] boll = TechnicalIndicatorUtils.boll(close, 20, 2.0);
        int last = close.length - 1;
        assertTrue(boll[1][last] > boll[0][last], "上轨应 > 中轨");
        assertTrue(boll[0][last] > boll[2][last], "中轨应 > 下轨");
    }

    @Test
    @DisplayName("布林带收窄 - 恒定值带宽趋近0")
    void testBollSqueeze() {
        double[] close = new double[25];
        for (int i = 0; i < 25; i++) close[i] = 100;
        assertTrue(TechnicalIndicatorUtils.isBollSqueeze(close, 20),
                "恒定价格布林带应收窄");
    }
}
