package com.quant.platform.factor.engine;

import java.util.Arrays;

/**
 * 技术指标计算工具类
 * 纯静态方法，无状态，线程安全
 * 输入为原始价格数组（按时间正序），输出为指标值数组
 */
public final class TechnicalIndicatorUtils {

    private TechnicalIndicatorUtils() {}

    // ═══════════════════════════════════════════════════════════
    // 移动平均线
    // ═══════════════════════════════════════════════════════════

    public static double[] sma(double[] close, int period) {
        double[] result = new double[close.length];
        for (int i = 0; i < close.length; i++) {
            if (i < period - 1) { result[i] = Double.NaN; continue; }
            double sum = 0;
            for (int j = i - period + 1; j <= i; j++) sum += close[j];
            result[i] = sum / period;
        }
        return result;
    }

    public static double[] ema(double[] close, int period) {
        double[] result = new double[close.length];
        double multiplier = 2.0 / (period + 1);
        double prev = close[0];
        result[0] = close[0];
        for (int i = 1; i < close.length; i++) {
            result[i] = close[i] * multiplier + prev * (1 - multiplier);
            prev = result[i];
        }
        return result;
    }

    // ═══════════════════════════════════════════════════════════
    // MACD
    // ═══════════════════════════════════════════════════════════

    public static double[][] macd(double[] close, int fast, int slow, int signal) {
        double[] emaFast = ema(close, fast);
        double[] emaSlow = ema(close, slow);
        double[] dif = new double[close.length];
        for (int i = 0; i < close.length; i++) {
            dif[i] = emaFast[i] - emaSlow[i];
        }
        double[] dea = ema(dif, signal);
        double[] hist = new double[close.length];
        for (int i = 0; i < close.length; i++) {
            hist[i] = (dif[i] - dea[i]) * 2;
        }
        return new double[][] { dif, dea, hist };
    }

    public static double[][] macd(double[] close) {
        return macd(close, 12, 26, 9);
    }

    /**
     * 判断MACD金叉：DIF上穿DEA
     * @return true 如果最后一天DIF从下方穿越DEA
     */
    public static boolean isMacdGoldenCross(double[] dif, double[] dea) {
        int n = dif.length;
        if (n < 2) return false;
        return dif[n - 1] > dea[n - 1] && dif[n - 2] <= dea[n - 2];
    }

    /**
     * 判断MACD死叉：DIF下穿DEA
     */
    public static boolean isMacdDeathCross(double[] dif, double[] dea) {
        int n = dif.length;
        if (n < 2) return false;
        return dif[n - 1] < dea[n - 1] && dif[n - 2] >= dea[n - 2];
    }

    /**
     * 判断MACD顶背离：价格创新高但DIF未创新高
     * @param close 收盘价数组
     * @param dif MACD DIF线
     * @param lookback 回看周期（寻找前一个高点）
     * @return true 如果存在顶背离
     */
    public static boolean isMacdTopDivergence(double[] close, double[] dif, int lookback) {
        int n = close.length;
        if (n < lookback + 2) return false;
        double currentPrice = close[n - 1];
        double currentDif = dif[n - 1];
        int prevHighIdx = -1;
        for (int i = n - 2; i >= n - lookback; i--) {
            if (close[i] > currentPrice) {
                prevHighIdx = i;
                break;
            }
        }
        if (prevHighIdx < 0) return false;
        return close[prevHighIdx] < currentPrice && dif[prevHighIdx] > currentDif;
    }

    /**
     * 判断MACD底背离：价格创新低但DIF未创新低
     */
    public static boolean isMacdBottomDivergence(double[] close, double[] dif, int lookback) {
        int n = close.length;
        if (n < lookback + 2) return false;
        double currentPrice = close[n - 1];
        double currentDif = dif[n - 1];
        int prevLowIdx = -1;
        for (int i = n - 2; i >= n - lookback; i--) {
            if (close[i] < currentPrice) {
                prevLowIdx = i;
                break;
            }
        }
        if (prevLowIdx < 0) return false;
        return close[prevLowIdx] > currentPrice && dif[prevLowIdx] < currentDif;
    }

    // ═══════════════════════════════════════════════════════════
    // KDJ
    // ═══════════════════════════════════════════════════════════

    public static double[][] kdj(double[] high, double[] low, double[] close, int period) {
        int n = close.length;
        double[] k = new double[n];
        double[] d = new double[n];
        double[] j = new double[n];
        k[0] = 50; d[0] = 50; j[0] = 50;
        for (int i = 1; i < n; i++) {
            int start = Math.max(0, i - period + 1);
            double hh = Double.MIN_VALUE, ll = Double.MAX_VALUE;
            for (int s = start; s <= i; s++) {
                hh = Math.max(hh, high[s]);
                ll = Math.min(ll, low[s]);
            }
            double rsv = (hh == ll) ? 50 : (close[i] - ll) / (hh - ll) * 100;
            k[i] = 2.0 / 3.0 * k[i - 1] + 1.0 / 3.0 * rsv;
            d[i] = 2.0 / 3.0 * d[i - 1] + 1.0 / 3.0 * k[i];
            j[i] = 3 * k[i] - 2 * d[i];
        }
        return new double[][] { k, d, j };
    }

    public static double[][] kdj(double[] high, double[] low, double[] close) {
        return kdj(high, low, close, 9);
    }

    /**
     * KDJ低位金叉：K从下方上穿D且K<30
     */
    public static boolean isKdjLowGoldenCross(double[] k, double[] d) {
        int n = k.length;
        if (n < 2) return false;
        return k[n - 1] > d[n - 1] && k[n - 2] <= d[n - 2] && k[n - 1] < 50;
    }

    /**
     * KDJ高位死叉：K从上方下穿D且K>70
     */
    public static boolean isKdjHighDeathCross(double[] k, double[] d) {
        int n = k.length;
        if (n < 2) return false;
        return k[n - 1] < d[n - 1] && k[n - 2] >= d[n - 2] && k[n - 1] > 50;
    }

    // ═══════════════════════════════════════════════════════════
    // 布林带
    // ═══════════════════════════════════════════════════════════

    public static double[][] boll(double[] close, int period, double multiplier) {
        int n = close.length;
        double[] mid = sma(close, period);
        double[] upper = new double[n];
        double[] lower = new double[n];
        for (int i = 0; i < n; i++) {
            if (i < period - 1) { upper[i] = Double.NaN; lower[i] = Double.NaN; continue; }
            double sum = 0;
            for (int j = i - period + 1; j <= i; j++) {
                sum += Math.pow(close[j] - mid[i], 2);
            }
            double std = Math.sqrt(sum / period);
            upper[i] = mid[i] + multiplier * std;
            lower[i] = mid[i] - multiplier * std;
        }
        return new double[][] { mid, upper, lower };
    }

    public static double[][] boll(double[] close) {
        return boll(close, 20, 2.0);
    }

    /**
     * 布林带收窄程度（带宽 / 中轨）
     */
    public static double bollBandwidth(double[] close, int period) {
        double[][] b = boll(close, period, 2.0);
        int n = close.length;
        if (n < 1 || Double.isNaN(b[0][n - 1]) || b[0][n - 1] == 0) return Double.MAX_VALUE;
        return (b[1][n - 1] - b[2][n - 1]) / b[0][n - 1];
    }

    /**
     * 布林带是否处于收窄状态（带宽 < 0.1 视为收窄）
     */
    public static boolean isBollSqueeze(double[] close, int period) {
        return bollBandwidth(close, period) < 0.1;
    }

    // ═══════════════════════════════════════════════════════════
    // RSI
    // ═══════════════════════════════════════════════════════════

    public static double[] rsi(double[] close, int period) {
        int n = close.length;
        double[] result = new double[n];
        result[0] = 50;
        double avgGain = 0, avgLoss = 0;
        for (int i = 1; i <= Math.min(period, n - 1); i++) {
            double diff = close[i] - close[i - 1];
            if (diff > 0) avgGain += diff; else avgLoss += -diff;
        }
        avgGain /= period;
        avgLoss /= period;
        result[period] = avgLoss == 0 ? 100 : 100 - 100 / (1 + avgGain / avgLoss);
        for (int i = period + 1; i < n; i++) {
            double diff = close[i] - close[i - 1];
            double gain = diff > 0 ? diff : 0;
            double loss = diff < 0 ? -diff : 0;
            avgGain = (avgGain * (period - 1) + gain) / period;
            avgLoss = (avgLoss * (period - 1) + loss) / period;
            result[i] = avgLoss == 0 ? 100 : 100 - 100 / (1 + avgGain / avgLoss);
        }
        return result;
    }

    public static double[] rsi(double[] close) {
        return rsi(close, 14);
    }

    // ═══════════════════════════════════════════════════════════
    // ATR (Average True Range)
    // ═══════════════════════════════════════════════════════════

    public static double[] atr(double[] high, double[] low, double[] close, int period) {
        int n = close.length;
        double[] tr = new double[n];
        tr[0] = high[0] - low[0];
        for (int i = 1; i < n; i++) {
            tr[i] = Math.max(
                high[i] - low[i],
                Math.max(Math.abs(high[i] - close[i - 1]), Math.abs(low[i] - close[i - 1]))
            );
        }
        double[] result = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += tr[i];
            if (i >= period) sum -= tr[i - period];
            result[i] = i >= period - 1 ? sum / period : tr[i];
        }
        return result;
    }

    public static double[] atr(double[] high, double[] low, double[] close) {
        return atr(high, low, close, 14);
    }

    // ═══════════════════════════════════════════════════════════
    // 均线多头排列
    // ═══════════════════════════════════════════════════════════

    /**
     * 均线多头排列：MA5 > MA10 > MA20 > MA60
     */
    public static boolean isMaBullishAlignment(double[] close) {
        int n = close.length;
        if (n < 60) return false;
        double[] ma5 = sma(close, 5);
        double[] ma10 = sma(close, 10);
        double[] ma20 = sma(close, 20);
        double[] ma60 = sma(close, 60);
        return ma5[n - 1] > ma10[n - 1] && ma10[n - 1] > ma20[n - 1] && ma20[n - 1] > ma60[n - 1];
    }

    /**
     * 均线空头排列：MA5 < MA10 < MA20 < MA60
     */
    public static boolean isMaBearishAlignment(double[] close) {
        int n = close.length;
        if (n < 60) return false;
        double[] ma5 = sma(close, 5);
        double[] ma10 = sma(close, 10);
        double[] ma20 = sma(close, 20);
        double[] ma60 = sma(close, 60);
        return ma5[n - 1] < ma10[n - 1] && ma10[n - 1] < ma20[n - 1] && ma20[n - 1] < ma60[n - 1];
    }

    /**
     * 均线金叉：短周期均线上穿长周期均线
     */
    public static boolean isMaGoldenCross(double[] close, int shortPeriod, int longPeriod) {
        int n = close.length;
        if (n < longPeriod + 1) return false;
        double[] maShort = sma(close, shortPeriod);
        double[] maLong = sma(close, longPeriod);
        return maShort[n - 1] > maLong[n - 1] && maShort[n - 2] <= maLong[n - 2];
    }

    /**
     * 均线死叉：短周期均线下穿长周期均线
     */
    public static boolean isMaDeathCross(double[] close, int shortPeriod, int longPeriod) {
        int n = close.length;
        if (n < longPeriod + 1) return false;
        double[] maShort = sma(close, shortPeriod);
        double[] maLong = sma(close, longPeriod);
        return maShort[n - 1] < maLong[n - 1] && maShort[n - 2] >= maLong[n - 2];
    }

    // ═══════════════════════════════════════════════════════════
    // 量价分析
    // ═══════════════════════════════════════════════════════════

    /**
     * 计算平均成交量
     */
    public static double avgVolume(double[] volume, int period) {
        int n = volume.length;
        if (n < period) period = n;
        double sum = 0;
        for (int i = n - period; i < n; i++) sum += volume[i];
        return sum / period;
    }

    /**
     * 放量：最新成交量 > N日均量的 ratio 倍
     */
    public static boolean isVolumeSurge(double[] volume, int period, double ratio) {
        int n = volume.length;
        if (n < period + 1) return false;
        double avg = avgVolume(Arrays.copyOf(volume, n - 1), period);
        return avg > 0 && volume[n - 1] > avg * ratio;
    }

    /**
     * 缩量：最新成交量 < N日均量的 ratio 倍
     */
    public static boolean isVolumeShrink(double[] volume, int period, double ratio) {
        int n = volume.length;
        if (n < period + 1) return false;
        double avg = avgVolume(Arrays.copyOf(volume, n - 1), period);
        return avg > 0 && volume[n - 1] < avg * ratio;
    }

    /**
     * 放量上涨：成交量放大 + 收盘价高于开盘价
     */
    public static boolean isVolumePriceUp(double[] close, double[] open, double[] volume, int period, double ratio) {
        int n = close.length;
        if (n < 1) return false;
        return isVolumeSurge(volume, period, ratio) && close[n - 1] > open[n - 1];
    }

    /**
     * 放量滞涨：成交量放大但涨幅很小（<1%）
     */
    public static boolean isVolumeStagnation(double[] close, double[] open, double[] volume, int period, double ratio) {
        int n = close.length;
        if (n < 1) return false;
        double priceChange = Math.abs(close[n - 1] - open[n - 1]) / open[n - 1];
        return isVolumeSurge(volume, period, ratio) && priceChange < 0.01;
    }

    // ═══════════════════════════════════════════════════════════
    // K线形态
    // ═══════════════════════════════════════════════════════════

    /**
     * 长上影线：上影线长度 > 实体长度的2倍 且 上影线 > 总振幅的40%
     */
    public static boolean isLongUpperShadow(double[] high, double[] low, double[] open, double[] close) {
        int n = close.length;
        if (n < 1) return false;
        double h = high[n - 1], l = low[n - 1], o = open[n - 1], c = close[n - 1];
        double body = Math.abs(c - o);
        double upperShadow = h - Math.max(o, c);
        double totalRange = h - l;
        if (totalRange <= 0) return false;
        return upperShadow > body * 2 && upperShadow / totalRange > 0.4;
    }

    /**
     * 长下影线：下影线长度 > 实体长度的2倍 且 下影线 > 总振幅的40%
     */
    public static boolean isLongLowerShadow(double[] high, double[] low, double[] open, double[] close) {
        int n = close.length;
        if (n < 1) return false;
        double h = high[n - 1], l = low[n - 1], o = open[n - 1], c = close[n - 1];
        double body = Math.abs(c - o);
        double lowerShadow = Math.min(o, c) - l;
        double totalRange = h - l;
        if (totalRange <= 0) return false;
        return lowerShadow > body * 2 && lowerShadow / totalRange > 0.4;
    }

    /**
     * 突破前期高点：最新收盘价突破 lookback 日内最高价
     */
    public static boolean isBreakoutHigh(double[] close, double[] high, int lookback) {
        int n = close.length;
        if (n < lookback + 1) return false;
        double prevHigh = Double.MIN_VALUE;
        for (int i = n - 2; i >= n - 1 - lookback; i--) {
            prevHigh = Math.max(prevHigh, high[i]);
        }
        return close[n - 1] > prevHigh;
    }

    /**
     * 跌破前期低点
     */
    public static boolean isBreakdownLow(double[] close, double[] low, int lookback) {
        int n = close.length;
        if (n < lookback + 1) return false;
        double prevLow = Double.MAX_VALUE;
        for (int i = n - 2; i >= n - 1 - lookback; i--) {
            prevLow = Math.min(prevLow, low[i]);
        }
        return close[n - 1] < prevLow;
    }

    /**
     * 计算N日内最高价
     */
    public static double recentHigh(double[] high, int lookback) {
        int n = high.length;
        double max = Double.MIN_VALUE;
        for (int i = Math.max(0, n - lookback); i < n; i++) max = Math.max(max, high[i]);
        return max;
    }

    /**
     * 计算N日内最低价
     */
    public static double recentLow(double[] low, int lookback) {
        int n = low.length;
        double min = Double.MAX_VALUE;
        for (int i = Math.max(0, n - lookback); i < n; i++) min = Math.min(min, low[i]);
        return min;
    }

    /**
     * 计算最大回撤
     */
    public static double maxDrawdown(double[] close) {
        double peak = close[0];
        double maxDD = 0;
        for (double price : close) {
            if (price > peak) peak = price;
            double dd = (peak - price) / peak;
            if (dd > maxDD) maxDD = dd;
        }
        return maxDD;
    }

    /**
     * 计算波动率（N日收益率标准差年化）
     */
    public static double volatility(double[] close, int period) {
        int n = close.length;
        if (n < period + 1) return 0;
        double[] returns = new double[period];
        for (int i = 0; i < period; i++) {
            returns[i] = (close[n - 1 - i] - close[n - 2 - i]) / close[n - 2 - i];
        }
        double mean = Arrays.stream(returns).average().orElse(0);
        double variance = Arrays.stream(returns).map(r -> Math.pow(r - mean, 2)).average().orElse(0);
        return Math.sqrt(variance) * Math.sqrt(250);
    }
}
