package com.quant.platform.factor.engine;

import com.quant.platform.market.domain.MarketDailyBar;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 内置因子实现集合
 * 包含最常用的量化因子：动量、价值、波动率等
 */
@Slf4j
@Component
public class BuiltinFactors {

    /**
     * 20日动量因子 (MOM20)
     */
    public static class Momentum20Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "MOM20";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 21) return null;
            MarketDailyBar latest = history.getLast();
            MarketDailyBar past = history.get(history.size() - 21);
            if (past.getClose().compareTo(BigDecimal.ZERO) == 0) return null;
            return latest.getClose().subtract(past.getClose())
                    .divide(past.getClose(), 8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 60日动量因子 (MOM60)
     */
    public static class Momentum60Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "MOM60";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 61) return null;
            MarketDailyBar latest = history.getLast();
            MarketDailyBar past = history.get(history.size() - 61);
            if (past.getClose().compareTo(BigDecimal.ZERO) == 0) return null;
            return latest.getClose().subtract(past.getClose())
                    .divide(past.getClose(), 8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 20日历史波动率 (VOL20)
     */
    public static class Volatility20Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "VOL20";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 21) return null;
            List<MarketDailyBar> window = history.subList(history.size() - 21, history.size());
            double[] returns = new double[20];
            for (int i = 0; i < 20; i++) {
                double prev = window.get(i).getClose().doubleValue();
                double curr = window.get(i + 1).getClose().doubleValue();
                if (prev == 0) return null;
                returns[i] = Math.log(curr / prev);
            }
            double mean = 0;
            for (double r : returns) mean += r;
            mean /= returns.length;
            double variance = 0;
            for (double r : returns) variance += (r - mean) * (r - mean);
            variance /= (returns.length - 1);
            double vol = Math.sqrt(variance) * Math.sqrt(252); // 年化
            return BigDecimal.valueOf(vol).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 20日平均换手率 (TURN20)
     */
    public static class Turnover20Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "TURN20";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 20) return null;
            List<MarketDailyBar> window = history.subList(history.size() - 20, history.size());
            double sum = window.stream()
                    .mapToDouble(b -> b.getTurnoverRate() == null ? 0 : b.getTurnoverRate().doubleValue())
                    .sum();
            return BigDecimal.valueOf(sum / 20).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 市值因子 (SIZE) - 使用总市值对数
     */
    public static class SizeCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "SIZE";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.isEmpty()) return null;
            MarketDailyBar latest = history.getLast();
            if (latest.getMarketCap() == null || latest.getMarketCap().doubleValue() <= 0) return null;
            return BigDecimal.valueOf(Math.log(latest.getMarketCap().doubleValue()))
                    .setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 5日相对强弱指数 (RSI5)
     */
    public static class Rsi5Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "RSI5";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 6) return null;
            List<MarketDailyBar> window = history.subList(history.size() - 6, history.size());
            double gains = 0, losses = 0;
            for (int i = 1; i < window.size(); i++) {
                double change = window.get(i).getClose().doubleValue() - window.get(i - 1).getClose().doubleValue();
                if (change > 0) gains += change;
                else losses += Math.abs(change);
            }
            if (losses == 0) return BigDecimal.valueOf(100);
            double rs = gains / losses;
            double rsi = 100 - (100 / (1 + rs));
            return BigDecimal.valueOf(rsi).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 布林带位置因子 (BOLL_POS) - 价格在布林带中的相对位置
     */
    public static class BollingerPositionCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "BOLL_POS";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 20) return null;
            List<MarketDailyBar> window = history.subList(history.size() - 20, history.size());
            double[] closes = window.stream().mapToDouble(b -> b.getClose().doubleValue()).toArray();
            double mean = 0;
            for (double c : closes) mean += c;
            mean /= closes.length;
            double std = 0;
            for (double c : closes) std += (c - mean) * (c - mean);
            std = Math.sqrt(std / closes.length);
            if (std == 0) return BigDecimal.ZERO;
            double current = closes[closes.length - 1];
            double upper = mean + 2 * std;
            double lower = mean - 2 * std;
            double pos = (current - lower) / (upper - lower);
            return BigDecimal.valueOf(pos).setScale(8, RoundingMode.HALF_UP);
        }
    }

    // ======================== 新增动量/反转因子 ========================

    /**
     * 5日动量因子 (MOM5)
     */
    public static class Momentum5Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "MOM5";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 6) return null;
            MarketDailyBar latest = history.getLast();
            MarketDailyBar past = history.get(history.size() - 6);
            if (past.getClose().compareTo(BigDecimal.ZERO) == 0) return null;
            return latest.getClose().subtract(past.getClose())
                    .divide(past.getClose(), 8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 120日动量因子 (MOM120)
     */
    public static class Momentum120Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "MOM120";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 121) return null;
            MarketDailyBar latest = history.getLast();
            MarketDailyBar past = history.get(history.size() - 121);
            if (past.getClose().compareTo(BigDecimal.ZERO) == 0) return null;
            return latest.getClose().subtract(past.getClose())
                    .divide(past.getClose(), 8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 5日反转因子 (REVERSAL5) - 短期反转，取负动量
     */
    public static class Reversal5Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "REVERSAL5";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 6) return null;
            MarketDailyBar latest = history.getLast();
            MarketDailyBar past = history.get(history.size() - 6);
            if (past.getClose().compareTo(BigDecimal.ZERO) == 0) return null;
            return past.getClose().subtract(latest.getClose())
                    .divide(past.getClose(), 8, RoundingMode.HALF_UP);
        }
    }

    // ======================== 新增波动率因子 ========================

    /**
     * 5日历史波动率 (VOL5)
     */
    public static class Volatility5Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "VOL5";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 6) return null;
            List<MarketDailyBar> window = history.subList(history.size() - 6, history.size());
            double[] returns = new double[5];
            for (int i = 0; i < 5; i++) {
                double prev = window.get(i).getClose().doubleValue();
                double curr = window.get(i + 1).getClose().doubleValue();
                if (prev == 0) return null;
                returns[i] = Math.log(curr / prev);
            }
            double mean = 0;
            for (double r : returns) mean += r;
            mean /= returns.length;
            double variance = 0;
            for (double r : returns) variance += (r - mean) * (r - mean);
            variance /= (returns.length - 1);
            double vol = Math.sqrt(variance) * Math.sqrt(252);
            return BigDecimal.valueOf(vol).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 60日历史波动率 (VOL60)
     */
    public static class Volatility60Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "VOL60";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 61) return null;
            List<MarketDailyBar> window = history.subList(history.size() - 61, history.size());
            double[] returns = new double[60];
            for (int i = 0; i < 60; i++) {
                double prev = window.get(i).getClose().doubleValue();
                double curr = window.get(i + 1).getClose().doubleValue();
                if (prev == 0) return null;
                returns[i] = Math.log(curr / prev);
            }
            double mean = 0;
            for (double r : returns) mean += r;
            mean /= returns.length;
            double variance = 0;
            for (double r : returns) variance += (r - mean) * (r - mean);
            variance /= (returns.length - 1);
            double vol = Math.sqrt(variance) * Math.sqrt(252);
            return BigDecimal.valueOf(vol).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 波动率比 (VOL_RATIO) - 短期5日波动率/长期60日波动率
     */
    public static class VolatilityRatioCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "VOL_RATIO";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 61) return null;
            // 短期波动率(5日)
            double[] shortRets = new double[5];
            for (int i = 0; i < 5; i++) {
                double prev = history.get(history.size() - 6 + i).getClose().doubleValue();
                double curr = history.get(history.size() - 5 + i).getClose().doubleValue();
                if (prev == 0) return null;
                shortRets[i] = Math.log(curr / prev);
            }
            double shortVol = calcStd(shortRets) * Math.sqrt(252);
            // 长期波动率(60日)
            double[] longRets = new double[60];
            for (int i = 0; i < 60; i++) {
                double prev = history.get(history.size() - 61 + i).getClose().doubleValue();
                double curr = history.get(history.size() - 60 + i).getClose().doubleValue();
                if (prev == 0) return null;
                longRets[i] = Math.log(curr / prev);
            }
            double longVol = calcStd(longRets) * Math.sqrt(252);
            if (longVol == 0) return null;
            return BigDecimal.valueOf(shortVol / longVol).setScale(8, RoundingMode.HALF_UP);
        }

        private double calcStd(double[] arr) {
            double mean = 0;
            for (double v : arr) mean += v;
            mean /= arr.length;
            double variance = 0;
            for (double v : arr) variance += (v - mean) * (v - mean);
            return Math.sqrt(variance / (arr.length - 1));
        }
    }

    // ======================== 新增流动性因子 ========================

    /**
     * Amihud非流动性因子 (AMIHUD) - 日均|收益|/成交额，值越大流动性越差
     */
    public static class AmihudCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "AMIHUD";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 21) return null;
            List<MarketDailyBar> window = history.subList(history.size() - 21, history.size());
            double sumIlliq = 0;
            int count = 0;
            for (int i = 1; i < window.size(); i++) {
                double prev = window.get(i - 1).getClose().doubleValue();
                double curr = window.get(i).getClose().doubleValue();
                double amount = window.get(i).getAmount() == null ? 0 : window.get(i).getAmount().doubleValue();
                if (prev == 0 || amount == 0) continue;
                double ret = (curr - prev) / prev;
                sumIlliq += Math.abs(ret) / amount;
                count++;
            }
            if (count == 0) return null;
            // 取对数压缩尾部
            double avgIlliq = sumIlliq / count;
            if (avgIlliq <= 0) return null;
            return BigDecimal.valueOf(Math.log(avgIlliq)).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 换手率变化因子 (TURNOVER_CHANGE) - 近5日均换手率/前20日均换手率
     */
    public static class TurnoverChangeCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "TURNOVER_CHANGE";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 25) return null;
            // 近5日
            double recentSum = 0;
            for (int i = history.size() - 5; i < history.size(); i++) {
                if (history.get(i).getTurnoverRate() == null) return null;
                recentSum += history.get(i).getTurnoverRate().doubleValue();
            }
            // 前20日(不含近5日)
            double pastSum = 0;
            for (int i = history.size() - 25; i < history.size() - 5; i++) {
                if (history.get(i).getTurnoverRate() == null) return null;
                pastSum += history.get(i).getTurnoverRate().doubleValue();
            }
            double recentAvg = recentSum / 5;
            double pastAvg = pastSum / 20;
            if (pastAvg == 0) return null;
            return BigDecimal.valueOf(recentAvg / pastAvg).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 量比因子 (VOLUME_RATIO) - 近5日均量/前20日均量
     */
    public static class VolumeRatioCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "VOLUME_RATIO";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 25) return null;
            // 近5日
            double recentSum = 0;
            for (int i = history.size() - 5; i < history.size(); i++) {
                if (history.get(i).getVol() == null) return null;
                recentSum += history.get(i).getVol().doubleValue();
            }
            // 前20日(不含近5日)
            double pastSum = 0;
            for (int i = history.size() - 25; i < history.size() - 5; i++) {
                if (history.get(i).getVol() == null) return null;
                pastSum += history.get(i).getVol().doubleValue();
            }
            double recentAvg = recentSum / 5;
            double pastAvg = pastSum / 20;
            if (pastAvg == 0) return null;
            return BigDecimal.valueOf(recentAvg / pastAvg).setScale(8, RoundingMode.HALF_UP);
        }
    }

    // ======================== 新增技术因子 ========================

    /**
     * 14日RSI (RSI14)
     */
    public static class Rsi14Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "RSI14";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 15) return null;
            List<MarketDailyBar> window = history.subList(history.size() - 15, history.size());
            double gains = 0, losses = 0;
            for (int i = 1; i < window.size(); i++) {
                double change = window.get(i).getClose().doubleValue() - window.get(i - 1).getClose().doubleValue();
                if (change > 0) gains += change;
                else losses += Math.abs(change);
            }
            if (losses == 0) return BigDecimal.valueOf(100);
            double rs = gains / losses;
            double rsi = 100 - (100 / (1 + rs));
            return BigDecimal.valueOf(rsi).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * MACD (DIF值) - 12日EMA - 26日EMA
     */
    public static class MacdCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "MACD";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 27) return null;
            double[] closes = new double[history.size()];
            for (int i = 0; i < history.size(); i++) {
                closes[i] = history.get(i).getClose().doubleValue();
            }
            double ema12 = calcEma(closes, 12);
            double ema26 = calcEma(closes, 26);
            return BigDecimal.valueOf(ema12 - ema26).setScale(8, RoundingMode.HALF_UP);
        }

        private double calcEma(double[] data, int period) {
            double k = 2.0 / (period + 1);
            double ema = data[0];
            for (int i = 1; i < data.length; i++) {
                ema = data[i] * k + ema * (1 - k);
            }
            return ema;
        }
    }

    /**
     * KDJ-K值 (KDJ_K) - 9日K值
     */
    public static class KdjKCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "KDJ_K";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 10) return null;
            List<MarketDailyBar> window = history.subList(history.size() - 10, history.size());
            // 先计算初始RSV，然后平滑
            double k = 50;
            for (int i = 0; i < window.size(); i++) {
                double high = window.get(i).getHigh().doubleValue();
                double low = window.get(i).getLow().doubleValue();
                double close = window.get(i).getClose().doubleValue();
                // 9日内的最高最低
                double highest = high, lowest = low;
                int start = Math.max(0, i - 8);
                for (int j = start; j <= i; j++) {
                    highest = Math.max(highest, window.get(j).getHigh().doubleValue());
                    lowest = Math.min(lowest, window.get(j).getLow().doubleValue());
                }
                double rsv = (highest == lowest) ? 50 : (close - lowest) / (highest - lowest) * 100;
                k = (2.0 / 3.0) * k + (1.0 / 3.0) * rsv;
            }
            return BigDecimal.valueOf(k).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 20日ATR (ATR20) - 平均真实波幅
     */
    public static class Atr20Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "ATR20";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 21) return null;
            List<MarketDailyBar> window = history.subList(history.size() - 21, history.size());
            double sumTr = 0;
            for (int i = 1; i < window.size(); i++) {
                double high = window.get(i).getHigh().doubleValue();
                double low = window.get(i).getLow().doubleValue();
                double prevClose = window.get(i - 1).getClose().doubleValue();
                double tr = Math.max(high - low, Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
                sumTr += tr;
            }
            return BigDecimal.valueOf(sumTr / 20).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 上影线比率 (UPPER_SHADOW) - (最高-收盘)/(最高-最低)
     */
    public static class UpperShadowCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "UPPER_SHADOW";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.isEmpty()) return null;
            MarketDailyBar latest = history.getLast();
            double high = latest.getHigh().doubleValue();
            double low = latest.getLow().doubleValue();
            double close = latest.getClose().doubleValue();
            if (high == low) return BigDecimal.ZERO;
            double upperShadow = (high - Math.max(close, low)) / (high - low);
            return BigDecimal.valueOf(upperShadow).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 价格动量加速度 (PRICE_MOM_ACC) - 近5日动量 - 近20日动量
     */
    public static class PriceMomAccCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "PRICE_MOM_ACC";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 21) return null;
            MarketDailyBar latest = history.getLast();
            MarketDailyBar past5 = history.get(history.size() - 6);
            MarketDailyBar past20 = history.get(history.size() - 21);
            if (past5.getClose().compareTo(BigDecimal.ZERO) == 0
                    || past20.getClose().compareTo(BigDecimal.ZERO) == 0) return null;
            double mom5 = latest.getClose().subtract(past5.getClose())
                    .divide(past5.getClose(), 8, RoundingMode.HALF_UP).doubleValue();
            double mom20 = latest.getClose().subtract(past20.getClose())
                    .divide(past20.getClose(), 8, RoundingMode.HALF_UP).doubleValue();
            return BigDecimal.valueOf(mom5 - mom20).setScale(8, RoundingMode.HALF_UP);
        }
    }

    // ======================== 原有因子 ========================

    // ======================== 新增：经典技术指标因子 ========================

    /**
     * 心理线 (PSY12) - 12日内上涨天数占比×100
     */
    public static class Psy12Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "PSY12";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 13) return null;
            List<MarketDailyBar> window = history.subList(history.size() - 13, history.size());
            int upDays = 0;
            for (int i = 1; i < window.size(); i++) {
                if (window.get(i).getClose().compareTo(window.get(i - 1).getClose()) > 0) upDays++;
            }
            return BigDecimal.valueOf(upDays * 100.0 / 12).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 标准化动量 (SRDM30) - (当前价 - 30日前价) / 30日价格标准差
     */
    public static class Srdm30Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "SRDM30";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 31) return null;
            List<MarketDailyBar> window = history.subList(history.size() - 31, history.size());
            double curr = window.getLast().getClose().doubleValue();
            double past = window.getFirst().getClose().doubleValue();
            double[] closes = window.stream().mapToDouble(b -> b.getClose().doubleValue()).toArray();
            double mean = 0;
            for (double c : closes) mean += c;
            mean /= closes.length;
            double variance = 0;
            for (double c : closes) variance += (c - mean) * (c - mean);
            double std = Math.sqrt(variance / (closes.length - 1));
            if (std == 0) return null;
            return BigDecimal.valueOf((curr - past) / std).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 布林带中轨 (BOLL_MID) - 20日收盘价简单移动平均
     */
    public static class BollMidCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "BOLL_MID";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 20) return null;
            List<MarketDailyBar> window = history.subList(history.size() - 20, history.size());
            double sum = window.stream().mapToDouble(b -> b.getClose().doubleValue()).sum();
            return BigDecimal.valueOf(sum / 20).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 资金流向指标 (MFI14) - 14日资金流向指数，类RSI但引入成交量
     * 典型价 = (H+L+C)/3，资金流 = 典型价×成交量
     */
    public static class Mfi14Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "MFI14";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 15) return null;
            List<MarketDailyBar> window = history.subList(history.size() - 15, history.size());
            double posFlow = 0, negFlow = 0;
            for (int i = 1; i < window.size(); i++) {
                MarketDailyBar bar = window.get(i);
                MarketDailyBar prev = window.get(i - 1);
                if (bar.getHigh() == null || bar.getLow() == null || bar.getVol() == null) continue;
                double tp = (bar.getHigh().doubleValue() + bar.getLow().doubleValue() + bar.getClose().doubleValue()) / 3;
                double tpPrev = (prev.getHigh().doubleValue() + prev.getLow().doubleValue() + prev.getClose().doubleValue()) / 3;
                double mf = tp * bar.getVol().doubleValue();
                if (tp > tpPrev) posFlow += mf;
                else negFlow += mf;
            }
            if (negFlow == 0) return BigDecimal.valueOf(100);
            double mfi = 100 - (100 / (1 + posFlow / negFlow));
            return BigDecimal.valueOf(mfi).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * BBI多空指数 (BBI) - (MA3+MA6+MA12+MA24)/4
     */
    public static class BbiCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "BBI";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 24) return null;
            double ma3 = maClose(history, 3);
            double ma6 = maClose(history, 6);
            double ma12 = maClose(history, 12);
            double ma24 = maClose(history, 24);
            return BigDecimal.valueOf((ma3 + ma6 + ma12 + ma24) / 4).setScale(8, RoundingMode.HALF_UP);
        }

        private double maClose(List<MarketDailyBar> history, int n) {
            List<MarketDailyBar> w = history.subList(history.size() - n, history.size());
            return w.stream().mapToDouble(b -> b.getClose().doubleValue()).sum() / n;
        }
    }

    /**
     * 5日均线 (MA5) - 5日简单移动平均
     */
    public static class Ma5Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "MA5";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 5) return null;
            List<MarketDailyBar> window = history.subList(history.size() - 5, history.size());
            double sum = window.stream().mapToDouble(b -> b.getClose().doubleValue()).sum();
            return BigDecimal.valueOf(sum / 5).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 5日EMA (EMA5) - 5日指数移动平均
     */
    public static class Ema5Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "EMA5";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 5) return null;
            double k = 2.0 / (5 + 1);
            double ema = history.getFirst().getClose().doubleValue();
            for (int i = 1; i < history.size(); i++) {
                ema = history.get(i).getClose().doubleValue() * k + ema * (1 - k);
            }
            return BigDecimal.valueOf(ema).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 威廉指标 (WR14) - 14日威廉指标
     */
    public static class Wr14Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "WR14";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 14) return null;
            List<MarketDailyBar> window = history.subList(history.size() - 14, history.size());
            double highest = window.stream().mapToDouble(b -> b.getHigh() == null ? 0 : b.getHigh().doubleValue()).max().orElse(0);
            double lowest = window.stream().mapToDouble(b -> b.getLow() == null ? Double.MAX_VALUE : b.getLow().doubleValue()).min().orElse(0);
            double close = window.getLast().getClose().doubleValue();
            if (highest == lowest) return BigDecimal.valueOf(-50);
            double wr = (highest - close) / (highest - lowest) * (-100);
            return BigDecimal.valueOf(wr).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * OBV能量潮 (OBV) - 近30日OBV累积值（相对起点的变化量）
     */
    public static class ObvCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "OBV";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 30) return null;
            List<MarketDailyBar> window = history.subList(history.size() - 30, history.size());
            double obv = 0;
            for (int i = 1; i < window.size(); i++) {
                double vol = window.get(i).getVol() == null ? 0 : window.get(i).getVol().doubleValue();
                double curr = window.get(i).getClose().doubleValue();
                double prev = window.get(i - 1).getClose().doubleValue();
                if (curr > prev) obv += vol;
                else if (curr < prev) obv -= vol;
            }
            return BigDecimal.valueOf(obv).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 成交量变化率 (VROC12) - 12日成交量变化率
     */
    public static class Vroc12Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "VROC12";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 13) return null;
            double volCurr = history.getLast().getVol() == null ? 0 : history.getLast().getVol().doubleValue();
            double volPast = history.get(history.size() - 13).getVol() == null ? 0 : history.get(history.size() - 13).getVol().doubleValue();
            if (volPast == 0) return null;
            return BigDecimal.valueOf((volCurr - volPast) / volPast * 100).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 量价趋势指标 (PVT) - 近30日PVT累积值（OBV改进版，按涨跌幅加权）
     */
    public static class PvtCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "PVT";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 30) return null;
            List<MarketDailyBar> window = history.subList(history.size() - 30, history.size());
            double pvt = 0;
            for (int i = 1; i < window.size(); i++) {
                double vol = window.get(i).getVol() == null ? 0 : window.get(i).getVol().doubleValue();
                double curr = window.get(i).getClose().doubleValue();
                double prev = window.get(i - 1).getClose().doubleValue();
                if (prev == 0) continue;
                pvt += vol * (curr - prev) / prev;
            }
            return BigDecimal.valueOf(pvt).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 价格振荡指标 (PRICEOSC) - (MA12-MA26)/MA26*100
     */
    public static class PriceOscCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "PRICEOSC";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 26) return null;
            double ma12 = history.subList(history.size() - 12, history.size())
                    .stream().mapToDouble(b -> b.getClose().doubleValue()).sum() / 12;
            double ma26 = history.subList(history.size() - 26, history.size())
                    .stream().mapToDouble(b -> b.getClose().doubleValue()).sum() / 26;
            if (ma26 == 0) return null;
            return BigDecimal.valueOf((ma12 - ma26) / ma26 * 100).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * VR成交量比率 (VR26) - 26日上涨日成交量之和 / 下跌日成交量之和 × 100
     */
    public static class Vr26Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "VR26";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 27) return null;
            List<MarketDailyBar> window = history.subList(history.size() - 27, history.size());
            double upVol = 0, downVol = 0;
            for (int i = 1; i < window.size(); i++) {
                double vol = window.get(i).getVol() == null ? 0 : window.get(i).getVol().doubleValue();
                double curr = window.get(i).getClose().doubleValue();
                double prev = window.get(i - 1).getClose().doubleValue();
                if (curr > prev) upVol += vol;
                else if (curr < prev) downVol += vol;
            }
            if (downVol == 0) return BigDecimal.valueOf(200);
            return BigDecimal.valueOf(upVol / downVol * 100).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 乖离率 (BIAS6) - 6日收盘价与MA6的偏离率
     */
    public static class Bias6Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "BIAS6";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 6) return null;
            List<MarketDailyBar> window = history.subList(history.size() - 6, history.size());
            double ma6 = window.stream().mapToDouble(b -> b.getClose().doubleValue()).sum() / 6;
            double close = history.getLast().getClose().doubleValue();
            if (ma6 == 0) return null;
            return BigDecimal.valueOf((close - ma6) / ma6 * 100).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 10日成交量标准差 (VSTD10)
     */
    public static class Vstd10Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "VSTD10";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 10) return null;
            List<MarketDailyBar> window = history.subList(history.size() - 10, history.size());
            double[] vols = window.stream().mapToDouble(b -> b.getVol() == null ? 0 : b.getVol().doubleValue()).toArray();
            double mean = 0;
            for (double v : vols) mean += v;
            mean /= vols.length;
            double variance = 0;
            for (double v : vols) variance += (v - mean) * (v - mean);
            double std = Math.sqrt(variance / (vols.length - 1));
            return BigDecimal.valueOf(std).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 变动速率 (ROC12) - 12日价格变动速率
     */
    public static class Roc12Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "ROC12";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 13) return null;
            double curr = history.getLast().getClose().doubleValue();
            double past = history.get(history.size() - 13).getClose().doubleValue();
            if (past == 0) return null;
            return BigDecimal.valueOf((curr - past) / past * 100).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * CCI顺势指标 (CCI14) - 14日CCI
     * 典型价 = (H+L+C)/3，MD = 平均绝对偏差
     */
    public static class Cci14Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "CCI14";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 14) return null;
            List<MarketDailyBar> window = history.subList(history.size() - 14, history.size());
            double[] tp = new double[14];
            for (int i = 0; i < 14; i++) {
                MarketDailyBar b = window.get(i);
                if (b.getHigh() == null || b.getLow() == null) return null;
                tp[i] = (b.getHigh().doubleValue() + b.getLow().doubleValue() + b.getClose().doubleValue()) / 3;
            }
            double tpMean = 0;
            for (double t : tp) tpMean += t;
            tpMean /= 14;
            double md = 0;
            for (double t : tp) md += Math.abs(t - tpMean);
            md /= 14;
            if (md == 0) return BigDecimal.ZERO;
            double cci = (tp[13] - tpMean) / (0.015 * md);
            return BigDecimal.valueOf(cci).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 三重指数平滑平均 (TRIX12) - 12日TRIX变化率
     */
    public static class Trix12Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "TRIX12";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 36) return null; // 3倍周期保证EMA稳定
            double k = 2.0 / (12 + 1);
            double[] closes = history.stream().mapToDouble(b -> b.getClose().doubleValue()).toArray();
            // 第一次EMA
            double ema1 = closes[0];
            for (int i = 1; i < closes.length; i++) ema1 = closes[i] * k + ema1 * (1 - k);
            // 第二次EMA（用第一次结果的每期值）
            double[] ema1Series = new double[closes.length];
            ema1Series[0] = closes[0];
            for (int i = 1; i < closes.length; i++) ema1Series[i] = closes[i] * k + ema1Series[i - 1] * (1 - k);
            double[] ema2Series = new double[closes.length];
            ema2Series[0] = ema1Series[0];
            for (int i = 1; i < closes.length; i++) ema2Series[i] = ema1Series[i] * k + ema2Series[i - 1] * (1 - k);
            double[] ema3Series = new double[closes.length];
            ema3Series[0] = ema2Series[0];
            for (int i = 1; i < closes.length; i++) ema3Series[i] = ema2Series[i] * k + ema3Series[i - 1] * (1 - k);
            int last = closes.length - 1;
            if (ema3Series[last - 1] == 0) return null;
            double trix = (ema3Series[last] - ema3Series[last - 1]) / ema3Series[last - 1] * 100;
            return BigDecimal.valueOf(trix).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 5日成交量均线 (VMA5)
     */
    public static class Vma5Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "VMA5";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 5) return null;
            List<MarketDailyBar> window = history.subList(history.size() - 5, history.size());
            double sum = window.stream().mapToDouble(b -> b.getVol() == null ? 0 : b.getVol().doubleValue()).sum();
            return BigDecimal.valueOf(sum / 5).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 14日ATR (ATR14) - 14日平均真实波幅
     */
    public static class Atr14Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "ATR14";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 15) return null;
            List<MarketDailyBar> window = history.subList(history.size() - 15, history.size());
            double sumTr = 0;
            for (int i = 1; i < window.size(); i++) {
                double high = window.get(i).getHigh() == null ? 0 : window.get(i).getHigh().doubleValue();
                double low = window.get(i).getLow() == null ? 0 : window.get(i).getLow().doubleValue();
                double prevClose = window.get(i - 1).getClose().doubleValue();
                double tr = Math.max(high - low, Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
                sumTr += tr;
            }
            return BigDecimal.valueOf(sumTr / 14).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 6日动量指标 (MTM6) - 当前价 - 6日前价
     */
    public static class Mtm6Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "MTM6";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 7) return null;
            double curr = history.getLast().getClose().doubleValue();
            double past = history.get(history.size() - 7).getClose().doubleValue();
            return BigDecimal.valueOf(curr - past).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 成交量震荡 (VOSC) - (成交量MA12 - 成交量MA26) / 成交量MA26 × 100
     */
    public static class VoscCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "VOSC";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 26) return null;
            double vma12 = history.subList(history.size() - 12, history.size())
                    .stream().mapToDouble(b -> b.getVol() == null ? 0 : b.getVol().doubleValue()).sum() / 12;
            double vma26 = history.subList(history.size() - 26, history.size())
                    .stream().mapToDouble(b -> b.getVol() == null ? 0 : b.getVol().doubleValue()).sum() / 26;
            if (vma26 == 0) return null;
            return BigDecimal.valueOf((vma12 - vma26) / vma26 * 100).setScale(8, RoundingMode.HALF_UP);
        }
    }

    // ======================== 原有量价因子 ========================

    /**
     * 成交量价格相关性 (VPCORR20) - 量价背离因子
     */
    public static class VolPriceCorr20Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "VPCORR20";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 20) return null;
            List<MarketDailyBar> window = history.subList(history.size() - 20, history.size());
            double[] prices = new double[20], vols = new double[20];
            for (int i = 0; i < 20; i++) {
                prices[i] = window.get(i).getClose().doubleValue();
                vols[i] = window.get(i).getVol() == null ? 0 : window.get(i).getVol().doubleValue();
            }
            double corr = pearsonCorr(prices, vols);
            return BigDecimal.valueOf(corr).setScale(8, RoundingMode.HALF_UP);
        }

        private double pearsonCorr(double[] x, double[] y) {
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
    }

    // ======================== 新增26个技术因子 (2026-04-16) ========================

    /**
     * ARBR 人气意愿指标 - AR = (最高价-开盘价)/(开盘价-最低价) 累计26日
     */
    public static class ArbrCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "ARBR";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 27) return null;
            double arSum = 0, brSum = 0;
            for (int i = history.size() - 26; i < history.size(); i++) {
                MarketDailyBar b = history.get(i);
                if (b.getOpen() == null || b.getHigh() == null || b.getLow() == null) return null;
                double open = b.getOpen().doubleValue();
                if (open <= b.getLow().doubleValue() || open == 0) continue;
                arSum += (b.getHigh().doubleValue() - open) / open * 100;
                brSum += (open - b.getLow().doubleValue()) / open * 100;
            }
            if (brSum == 0) return null;
            return BigDecimal.valueOf(arSum / brSum).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * BBIBOLL 布林带多空线 = (MA3+MA6+MA12+MA24)/4 + 2*STD(MA3,MA6,MA12,MA24) 的通道位置
     */
    public static class BbibollCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "BBIBOLL";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 26) return null;
            double[] closes = history.stream().mapToDouble(b -> b.getClose().doubleValue()).toArray();
            int n = closes.length;
            // 计算各周期MA
            double ma3 = avg(closes, n - 3, n);
            double ma6 = avg(closes, n - 6, n);
            double ma12 = avg(closes, n - 12, n);
            double ma24 = avg(closes, n - 24, n);
            double bbi = (ma3 + ma6 + ma12 + ma24) / 4;
            // 用bbi值的最近10日标准差
            if (n < 36)
                return bbi == 0 ? null : BigDecimal.valueOf(closes[n - 1] / bbi - 1).setScale(8, RoundingMode.HALF_UP);
            double sum = 0;
            int cnt = 0;
            for (int i = Math.max(24, n - 10); i < n; i++) {
                double m3 = avg(closes, Math.max(0, i - 2), i + 1);
                double m6 = avg(closes, Math.max(0, i - 5), i + 1);
                double m12 = avg(closes, Math.max(0, i - 11), i + 1);
                double m24 = avg(closes, Math.max(0, i - 23), i + 1);
                double b = (m3 + m6 + m12 + m24) / 4;
                sum += (b - bbi) * (b - bbi);
                cnt++;
            }
            if (cnt == 0) return null;
            double std = Math.sqrt(sum / cnt);
            if (std == 0) return null;
            return BigDecimal.valueOf((closes[n - 1] - bbi) / std).setScale(8, RoundingMode.HALF_UP);
        }

        private double avg(double[] arr, int from, int to) {
            double s = 0;
            for (int i = from; i < to; i++) s += arr[i];
            return s / (to - from);
        }
    }

    /**
     * CDP 逆势操作 - (最高+最低+2*收盘)/4 的压力线
     */
    public static class CdpCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "CDP";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 2) return null;
            MarketDailyBar prev = history.get(history.size() - 2);
            if (prev.getHigh() == null || prev.getLow() == null) return null;
            double h = prev.getHigh().doubleValue(), l = prev.getLow().doubleValue(), c = prev.getClose().doubleValue();
            double cdp = (h + l + 2 * c) / 4;
            double ah = cdp + (h - l);
            MarketDailyBar curr = history.getLast();
            if (ah == 0) return null;
            return BigDecimal.valueOf((curr.getClose().doubleValue() - cdp) / (ah - cdp)).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * ENV14 包络线 - 收盘价/(MA14 * 1.05) 表示在上轨的相对位置
     */
    public static class Env14Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "ENV14";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 14) return null;
            double ma14 = history.subList(history.size() - 14, history.size())
                    .stream().mapToDouble(b -> b.getClose().doubleValue()).sum() / 14;
            if (ma14 == 0) return null;
            double upper = ma14 * 1.05, lower = ma14 * 0.95;
            double close = history.getLast().getClose().doubleValue();
            return BigDecimal.valueOf((close - lower) / (upper - lower)).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * DBCD 异同离差乖离率 - 多步平滑BIAS
     */
    public static class DbcdCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "DBCD";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 30) return null;
            // 先计算BIAS12
            double ma12 = history.subList(history.size() - 12, history.size())
                    .stream().mapToDouble(b -> b.getClose().doubleValue()).sum() / 12;
            double close = history.getLast().getClose().doubleValue();
            if (ma12 == 0) return null;
            double bias12 = (close - ma12) / ma12 * 100;
            return BigDecimal.valueOf(bias12).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * CR 能量指标 - 中间价多空力量比
     */
    public static class CrCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "CR";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 27) return null;
            double upSum = 0, downSum = 0;
            for (int i = history.size() - 26; i < history.size(); i++) {
                MarketDailyBar b = history.get(i);
                if (b.getHigh() == null || b.getLow() == null) return null;
                double mid = (b.getHigh().doubleValue() + b.getLow().doubleValue()) / 2;
                upSum += Math.max(0, b.getHigh().doubleValue() - mid);
                downSum += Math.max(0, mid - b.getLow().doubleValue());
            }
            if (downSum == 0) return BigDecimal.valueOf(200);
            return BigDecimal.valueOf(upSum / downSum * 100).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * DPO 去势价格振荡 - 收盘价 - N日前MA
     */
    public static class DpoCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "DPO";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 21) return null;
            double close = history.getLast().getClose().doubleValue();
            double ma20 = history.subList(history.size() - 21, history.size() - 1)
                    .stream().mapToDouble(b -> b.getClose().doubleValue()).sum() / 20;
            return BigDecimal.valueOf(close - ma20).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * WR12 12日威廉指标
     */
    public static class Wr12Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "WR12";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 12) return null;
            List<MarketDailyBar> window = history.subList(history.size() - 12, history.size());
            double highest = window.stream().mapToDouble(b -> b.getHigh() == null ? 0 : b.getHigh().doubleValue()).max().orElse(0);
            double lowest = window.stream().mapToDouble(b -> b.getLow() == null ? Double.MAX_VALUE : b.getLow().doubleValue()).min().orElse(0);
            double close = window.getLast().getClose().doubleValue();
            if (highest == lowest) return BigDecimal.valueOf(-50);
            double wr = (highest - close) / (highest - lowest) * (-100);
            return BigDecimal.valueOf(wr).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * VRSI6 成交量RSI 6日
     */
    public static class Vrsi6Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "VRSI6";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 7) return null;
            double gains = 0, losses = 0;
            for (int i = history.size() - 6; i < history.size(); i++) {
                double curr = history.get(i).getVol() == null ? 0 : history.get(i).getVol().doubleValue();
                double prev = history.get(i - 1).getVol() == null ? 0 : history.get(i - 1).getVol().doubleValue();
                if (curr > prev) gains += curr - prev;
                else losses += prev - curr;
            }
            if (losses == 0) return BigDecimal.valueOf(100);
            double rs = gains / losses;
            return BigDecimal.valueOf(100 - 100 / (1 + rs)).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * BIAS12 12日乖离率
     */
    public static class Bias12Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "BIAS12";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 12) return null;
            double ma12 = history.subList(history.size() - 12, history.size())
                    .stream().mapToDouble(b -> b.getClose().doubleValue()).sum() / 12;
            double close = history.getLast().getClose().doubleValue();
            if (ma12 == 0) return null;
            return BigDecimal.valueOf((close - ma12) / ma12 * 100).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * BIAS24 24日乖离率
     */
    public static class Bias24Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "BIAS24";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 24) return null;
            double ma24 = history.subList(history.size() - 24, history.size())
                    .stream().mapToDouble(b -> b.getClose().doubleValue()).sum() / 24;
            double close = history.getLast().getClose().doubleValue();
            if (ma24 == 0) return null;
            return BigDecimal.valueOf((close - ma24) / ma24 * 100).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * RCCD BIAS变化率
     */
    public static class RccdCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "RCCD";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 25) return null;
            // 当日BIAS12
            double ma12_1 = history.subList(history.size() - 12, history.size())
                    .stream().mapToDouble(b -> b.getClose().doubleValue()).sum() / 12;
            double c1 = history.getLast().getClose().doubleValue();
            // 5日前BIAS12
            double ma12_2 = history.subList(history.size() - 17, history.size() - 5)
                    .stream().mapToDouble(b -> b.getClose().doubleValue()).sum() / 12;
            double c2 = history.get(history.size() - 6).getClose().doubleValue();
            if (ma12_1 == 0 || ma12_2 == 0) return null;
            double bias1 = (c1 - ma12_1) / ma12_1 * 100;
            double bias2 = (c2 - ma12_2) / ma12_2 * 100;
            return BigDecimal.valueOf(bias1 - bias2).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * DDI 方向标准差
     */
    public static class DdiCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "DDI";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 15) return null;
            double[] diffs = new double[14];
            for (int i = history.size() - 14; i < history.size(); i++) {
                MarketDailyBar b = history.get(i);
                MarketDailyBar prev = history.get(i - 1);
                if (b.getHigh() == null || b.getLow() == null) return null;
                double dmz = Math.max(b.getHigh().doubleValue() - prev.getHigh().doubleValue(), 0);
                double dmf = Math.max(prev.getLow().doubleValue() - b.getLow().doubleValue(), 0);
                diffs[i - (history.size() - 14)] = dmz - dmf;
            }
            double mean = 0;
            for (double d : diffs) mean += d;
            mean /= diffs.length;
            double variance = 0;
            for (double d : diffs) variance += (d - mean) * (d - mean);
            return BigDecimal.valueOf(Math.sqrt(variance / (diffs.length - 1))).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * CVLT 变动率 - A/D变化率
     */
    public static class CvltCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "CVLT";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 22) return null;
            double[] ad = new double[20];
            for (int i = 0; i < 20; i++) {
                MarketDailyBar b = history.get(history.size() - 20 + i);
                double h = b.getHigh() == null ? 0 : b.getHigh().doubleValue();
                double l = b.getLow() == null ? 0 : b.getLow().doubleValue();
                double c = b.getClose().doubleValue();
                ad[i] = (h == l) ? 0 : (2 * c - h - l) / (h - l);
            }
            double sum = 0;
            for (double a : ad) sum += a;
            double mean = sum / ad.length;
            double variance = 0;
            for (double a : ad) variance += (a - mean) * (a - mean);
            double std = Math.sqrt(variance / (ad.length - 1));
            if (std == 0) return null;
            return BigDecimal.valueOf((ad[19] - ad[18]) / std).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * VHF 趋势清晰度 - 价格方向一致性
     */
    public static class VhfCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "VHF";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 29) return null;
            List<MarketDailyBar> window = history.subList(history.size() - 29, history.size());
            double highest = window.stream().mapToDouble(b -> b.getHigh().doubleValue()).max().orElse(0);
            double lowest = window.stream().mapToDouble(b -> b.getLow().doubleValue()).min().orElse(0);
            double sumDiff = 0;
            for (int i = 1; i < window.size(); i++) {
                sumDiff += Math.abs(window.get(i).getClose().doubleValue() - window.get(i - 1).getClose().doubleValue());
            }
            if (sumDiff == 0) return null;
            return BigDecimal.valueOf((highest - lowest) / sumDiff).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * SI 摆动指标 - (收盘-开盘)/(最高-最低) 的累积
     */
    public static class SiCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "SI";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 10) return null;
            double si = 0;
            int count = 0;
            for (int i = history.size() - 10; i < history.size(); i++) {
                MarketDailyBar b = history.get(i);
                if (b.getOpen() == null || b.getHigh() == null || b.getLow() == null) continue;
                double range = b.getHigh().doubleValue() - b.getLow().doubleValue();
                if (range == 0) continue;
                si += (b.getClose().doubleValue() - b.getOpen().doubleValue()) / range;
                count++;
            }
            if (count == 0) return null;
            return BigDecimal.valueOf(si / count * 100).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * MASS 质量指数 - 高低价差EMA比值
     */
    public static class MassCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "MASS";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 30) return null;
            double[] ehl = new double[history.size()];
            for (int i = 0; i < history.size(); i++) {
                MarketDailyBar b = history.get(i);
                if (b.getHigh() == null || b.getLow() == null) return null;
                ehl[i] = b.getHigh().doubleValue() - b.getLow().doubleValue();
            }
            // 9日EMA of ehl
            double k = 2.0 / 10;
            double[] ema1 = new double[ehl.length];
            ema1[0] = ehl[0];
            for (int i = 1; i < ehl.length; i++) ema1[i] = ehl[i] * k + ema1[i - 1] * (1 - k);
            // 9日EMA of ema1
            double[] ema2 = new double[ehl.length];
            ema2[0] = ema1[0];
            for (int i = 1; i < ehl.length; i++) ema2[i] = ema1[i] * k + ema2[i - 1] * (1 - k);
            // MASS = sum(ema1/ema2) over 25 days
            double sum = 0;
            for (int i = history.size() - 25; i < history.size(); i++) {
                if (ema2[i] == 0) return null;
                sum += ema1[i] / ema2[i];
            }
            return BigDecimal.valueOf(sum).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * SRMI9 慢速随机指标
     */
    public static class Srmi9Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "SRMI9";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 20) return null;
            double k = 50, d = 50;
            for (int i = history.size() - 20; i < history.size(); i++) {
                int start = Math.max(0, i - 8);
                double highest = Double.MIN_VALUE, lowest = Double.MAX_VALUE;
                for (int j = start; j <= i; j++) {
                    highest = Math.max(highest, history.get(j).getHigh().doubleValue());
                    lowest = Math.min(lowest, history.get(j).getLow().doubleValue());
                }
                double close = history.get(i).getClose().doubleValue();
                double rsv = (highest == lowest) ? 50 : (close - lowest) / (highest - lowest) * 100;
                k = (2.0 / 3) * k + (1.0 / 3) * rsv;
                d = (2.0 / 3) * d + (1.0 / 3) * k;
            }
            return BigDecimal.valueOf(d).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * VMACD 成交量MACD = VOL_EMA12 - VOL_EMA26
     */
    public static class VmacdCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "VMACD";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 27) return null;
            double[] vols = history.stream().mapToDouble(b -> b.getVol() == null ? 0 : b.getVol().doubleValue()).toArray();
            double k12 = 2.0 / 13, k26 = 2.0 / 27;
            double ema12 = vols[0], ema26 = vols[0];
            for (int i = 1; i < vols.length; i++) {
                ema12 = vols[i] * k12 + ema12 * (1 - k12);
                ema26 = vols[i] * k26 + ema26 * (1 - k26);
            }
            return BigDecimal.valueOf(ema12 - ema26).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * LWR 慢速威廉指标
     */
    public static class LwrCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "LWR";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 15) return null;
            List<MarketDailyBar> window = history.subList(history.size() - 15, history.size());
            double highest = window.stream().mapToDouble(b -> b.getHigh() == null ? 0 : b.getHigh().doubleValue()).max().orElse(0);
            double lowest = window.stream().mapToDouble(b -> b.getLow() == null ? Double.MAX_VALUE : b.getLow().doubleValue()).min().orElse(0);
            double close = window.getLast().getClose().doubleValue();
            if (highest == lowest) return BigDecimal.valueOf(-50);
            return BigDecimal.valueOf((highest - close) / (highest - lowest) * (-100)).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * ADTM 动态买卖气指标
     */
    public static class AdtmCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "ADTM";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 24) return null;
            double buySum = 0, sellSum = 0;
            for (int i = history.size() - 23; i < history.size(); i++) {
                MarketDailyBar b = history.get(i);
                MarketDailyBar prev = history.get(i - 1);
                if (b.getOpen() == null) return null;
                double open = b.getOpen().doubleValue();
                if (open > prev.getClose().doubleValue()) buySum += open - prev.getClose().doubleValue();
                else sellSum += prev.getClose().doubleValue() - open;
            }
            if (buySum + sellSum == 0) return null;
            return BigDecimal.valueOf((buySum - sellSum) / (buySum + sellSum) * 100).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * MICD 平滑异同移动平均 = EMA(ROC12) - EMA(EMA(ROC12))
     */
    public static class MicdCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "MICD";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 27) return null;
            double[] closes = history.stream().mapToDouble(b -> b.getClose().doubleValue()).toArray();
            double[] roc = new double[closes.length];
            roc[0] = 0;
            for (int i = 12; i < closes.length; i++) {
                if (closes[i - 12] != 0) roc[i] = (closes[i] - closes[i - 12]) / closes[i - 12] * 100;
            }
            // EMA12 of ROC
            double k = 2.0 / 13;
            double ema = roc[0];
            for (int i = 1; i < roc.length; i++) ema = roc[i] * k + ema * (1 - k);
            // EMA26 of ROC
            double k2 = 2.0 / 27;
            double ema2 = roc[0];
            for (int i = 1; i < roc.length; i++) ema2 = roc[i] * k2 + ema2 * (1 - k2);
            return BigDecimal.valueOf(ema - ema2).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * DMA 平行线差 = MA10 - MA50
     */
    public static class DmaCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "DMA";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 50) return null;
            double ma10 = history.subList(history.size() - 10, history.size())
                    .stream().mapToDouble(b -> b.getClose().doubleValue()).sum() / 10;
            double ma50 = history.subList(history.size() - 50, history.size())
                    .stream().mapToDouble(b -> b.getClose().doubleValue()).sum() / 50;
            return BigDecimal.valueOf(ma10 - ma50).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * TAPI 成交量乘数 - 成交额加权价格趋势
     */
    public static class TapiCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "TAPI";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 11) return null;
            double close = history.getLast().getClose().doubleValue();
            double amount = history.getLast().getAmount() == null ? 0 : history.getLast().getAmount().doubleValue();
            double ma10 = history.subList(history.size() - 11, history.size() - 1)
                    .stream().mapToDouble(b -> b.getClose().doubleValue()).sum() / 10;
            if (ma10 == 0 || amount == 0) return null;
            return BigDecimal.valueOf(amount / close / 10000).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * MI12 动量指标 - 12日ROC的EMA
     */
    public static class Mi12Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "MI12";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 13) return null;
            double[] closes = history.stream().mapToDouble(b -> b.getClose().doubleValue()).toArray();
            double[] roc = new double[closes.length];
            roc[0] = 0;
            for (int i = 12; i < closes.length; i++) {
                if (closes[i - 12] != 0) roc[i] = (closes[i] - closes[i - 12]) / closes[i - 12] * 100;
            }
            // 6日EMA of ROC
            double k = 2.0 / 7;
            double ema = roc[0];
            for (int i = 1; i < roc.length; i++) ema = roc[i] * k + ema * (1 - k);
            return BigDecimal.valueOf(ema).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * MTM_MTM 动量百分比 - MTM6 / 6日前价格
     */
    public static class MtmPctCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "MTM_PCT";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 7) return null;
            double curr = history.getLast().getClose().doubleValue();
            double past = history.get(history.size() - 7).getClose().doubleValue();
            if (past == 0) return null;
            return BigDecimal.valueOf((curr - past) / past * 100).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * WAD 威廉斯累积/派发
     */
    public static class WadCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "WAD";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 30) return null;
            double wad = 0;
            for (int i = 1; i < history.size(); i++) {
                MarketDailyBar curr = history.get(i);
                MarketDailyBar prev = history.get(i - 1);
                double c = curr.getClose().doubleValue();
                double p = prev.getClose().doubleValue();
                double lo = curr.getLow() == null ? c : curr.getLow().doubleValue();
                double hi = curr.getHigh() == null ? c : curr.getHigh().doubleValue();
                if (c > p) wad += (c - Math.min(p, lo));
                else if (c < p) wad -= (Math.max(p, hi) - c);
            }
            return BigDecimal.valueOf(wad).setScale(8, RoundingMode.HALF_UP);
        }
    }

    // ======================== 情绪因子 (SENTIMENT) ========================

    /**
     * 近20日涨停次数 - pctChg >= 9.8% 视为涨停（覆盖9.8%~10%的近似涨停）
     */
    public static class LimitUpCountCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "LIMIT_UP_COUNT";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 21) return null;
            List<MarketDailyBar> window = history.subList(history.size() - 21, history.size());
            int count = 0;
            for (MarketDailyBar b : window) {
                if (b.getPctChg() != null && b.getPctChg().doubleValue() >= 9.8) count++;
            }
            return BigDecimal.valueOf(count).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 换手率异常度 - 近5日均换手率 / 60日均换手率，值越大说明关注度突然升高
     */
    public static class TurnoverAnomalyCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "TURNOVER_ANOMALY";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 61) return null;
            // 近5日平均换手率
            double recentSum = 0;
            int recentCount = 0;
            for (int i = history.size() - 5; i < history.size(); i++) {
                if (history.get(i).getTurnoverRate() != null) {
                    recentSum += history.get(i).getTurnoverRate().doubleValue();
                    recentCount++;
                }
            }
            if (recentCount == 0) return null;
            // 前60日（不含近5日）平均换手率
            double pastSum = 0;
            int pastCount = 0;
            for (int i = history.size() - 61; i < history.size() - 5; i++) {
                if (history.get(i).getTurnoverRate() != null) {
                    pastSum += history.get(i).getTurnoverRate().doubleValue();
                    pastCount++;
                }
            }
            if (pastCount == 0 || pastSum == 0) return null;
            double recentAvg = recentSum / recentCount;
            double pastAvg = pastSum / pastCount;
            return BigDecimal.valueOf(recentAvg / pastAvg).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 成交量惊喜 - 近5日均量 / 20日均量 的对数，反映资金突然涌入程度
     */
    public static class VolumeSurpriseCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "VOLUME_SURPRISE";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 26) return null;
            // 近5日均量
            double recentSum = 0;
            int recentCount = 0;
            for (int i = history.size() - 5; i < history.size(); i++) {
                if (history.get(i).getVol() != null) {
                    recentSum += history.get(i).getVol().doubleValue();
                    recentCount++;
                }
            }
            if (recentCount == 0) return null;
            // 前20日（不含近5日）均量
            double pastSum = 0;
            int pastCount = 0;
            for (int i = history.size() - 25; i < history.size() - 5; i++) {
                if (history.get(i).getVol() != null) {
                    pastSum += history.get(i).getVol().doubleValue();
                    pastCount++;
                }
            }
            if (pastCount == 0 || pastSum == 0) return null;
            double ratio = (recentSum / recentCount) / (pastSum / pastCount);
            if (ratio <= 0) return null;
            return BigDecimal.valueOf(Math.log(ratio)).setScale(8, RoundingMode.HALF_UP);
        }
    }

    // ===== DMI / ADX 系列 =====

    /**
     * Wilder 平滑 EMA
     * alpha = 2 / (period + 1)，与 Wilder 平滑等价
     */
    private static double wilderEma(double prevSmoothed, double value, int period) {
        double alpha = 2.0 / (period + 1);
        return prevSmoothed * (1 - alpha) + value * alpha;
    }

    /**
     * DMI/ADX 核心算法（供多个计算器复用）
     * @return [trSmooth, dmPlusSmooth, dmMinusSmooth]，全为 double
     */
    private static double[] computeDmiSmoothed(List<MarketDailyBar> bars, int n) {
        int len = bars.size();
        double[] trArr = new double[len];
        double[] dmPlus = new double[len];
        double[] dmMinus = new double[len];

        for (int i = 0; i < len; i++) {
            MarketDailyBar curr = bars.get(i);
            double h = curr.getHigh().doubleValue();
            double l = curr.getLow().doubleValue();
            double c = curr.getClose().doubleValue();

            if (i == 0) {
                trArr[i] = h - l;
                dmPlus[i] = 0;
                dmMinus[i] = 0;
            } else {
                MarketDailyBar prev = bars.get(i - 1);
                double pc = prev.getClose().doubleValue();

                trArr[i] = Math.max(h - l, Math.max(Math.abs(h - pc), Math.abs(l - pc)));

                double upMove = h - prev.getHigh().doubleValue();
                double downMove = prev.getLow().doubleValue() - l;

                if (upMove > downMove && upMove > 0) {
                    dmPlus[i] = upMove;
                    dmMinus[i] = 0;
                } else if (downMove > upMove && downMove > 0) {
                    dmPlus[i] = 0;
                    dmMinus[i] = downMove;
                } else {
                    dmPlus[i] = 0;
                    dmMinus[i] = 0;
                }
            }
        }

        // Wilder 初始值 = 第一个周期的简单和
        double trSmooth = 0, dmPSmooth = 0, dmMSmooth = 0;
        for (int i = 0; i < n && i < len; i++) {
            trSmooth += trArr[i];
            dmPSmooth += dmPlus[i];
            dmMSmooth += dmMinus[i];
        }
        if (trSmooth == 0) return new double[]{0, 0, 0};

        // 后续用 Wilder EMA 递推
        for (int i = n; i < len; i++) {
            trSmooth = wilderEma(trSmooth, trArr[i], n);
            dmPSmooth = wilderEma(dmPSmooth, dmPlus[i], n);
            dmMSmooth = wilderEma(dmMSmooth, dmMinus[i], n);
        }

        return new double[]{trSmooth, dmPSmooth, dmMSmooth};
    }

    /**
     * +DI14（上升方向指标）——衡量上涨趋势强度
     */
    public static class PlusDI14Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "PLUS_DI14";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 15) return null;
            double[] smooth = computeDmiSmoothed(history, 14);
            if (smooth[0] == 0) return null;
            double di = 100.0 * smooth[1] / smooth[0];
            return BigDecimal.valueOf(di).setScale(6, RoundingMode.HALF_UP);
        }
    }

    /**
     * -DI14（下降方向指标）——衡量下跌趋势强度
     */
    public static class MinusDI14Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "MINUS_DI14";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 15) return null;
            double[] smooth = computeDmiSmoothed(history, 14);
            if (smooth[0] == 0) return null;
            double di = 100.0 * smooth[2] / smooth[0];
            return BigDecimal.valueOf(di).setScale(6, RoundingMode.HALF_UP);
        }
    }

    /**
     * DX14（方向移动指数）——+DI 与 -DI 的相对差距
     * DX = 100 * |+DI - (-DI)| / (+DI + -DI)
     */
    public static class Dx14Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "DX14";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 15) return null;
            double[] smooth = computeDmiSmoothed(history, 14);
            if (smooth[0] == 0) return null;
            double diP = smooth[1] / smooth[0];
            double diM = smooth[2] / smooth[0];
            double sum = diP + diM;
            if (sum == 0) return null;
            double dx = 100.0 * Math.abs(diP - diM) / sum;
            return BigDecimal.valueOf(dx).setScale(6, RoundingMode.HALF_UP);
        }
    }

    /**
     * ADX14（平均方向移动指数）——趋势强度的终极指标
     * ADX = Wilder EMA(DX)，反映当前趋势的强弱（不管多空）
     * ADX > 25 → 强趋势；ADX < 20 → 震荡/无趋势
     */
    public static class Adx14Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "ADX14";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            int n = 14;
            if (history.size() < n * 2) return null;

            // 逐根计算 DX
            double[] dxArr = new double[history.size()];
            for (int i = 1; i < history.size(); i++) {
                int start = Math.max(0, i - n);
                int end = i + 1;
                List<MarketDailyBar> sub = history.subList(start, end);
                double[] smooth = computeDmiSmoothed(sub, n);
                if (smooth[0] == 0) {
                    dxArr[i] = 0;
                } else {
                    double diP = smooth[1] / smooth[0];
                    double diM = smooth[2] / smooth[0];
                    double sum = diP + diM;
                    dxArr[i] = (sum == 0) ? 0 : 100.0 * Math.abs(diP - diM) / sum;
                }
            }

            // Wilder EMA of DX: alpha = 2/(n+1)
            // 第一个 ADX = 最后一个 DX
            double alpha = 2.0 / (n + 1);
            double adx = dxArr[n]; // 第 n 个 DX 作为初始值
            for (int i = n + 1; i < history.size(); i++) {
                adx = adx * (1 - alpha) + dxArr[i] * alpha;
            }
            return BigDecimal.valueOf(adx).setScale(6, RoundingMode.HALF_UP);
        }
    }

    /**
     * ADX20（长周期 ADX）——更长趋势判断
     */
    public static class Adx20Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "ADX20";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            int n = 20;
            if (history.size() < n * 2) return null;

            double[] dxArr = new double[history.size()];
            for (int i = 1; i < history.size(); i++) {
                int start = Math.max(0, i - n);
                int end = i + 1;
                List<MarketDailyBar> sub = history.subList(start, end);
                double[] smooth = computeDmiSmoothed(sub, n);
                if (smooth[0] == 0) {
                    dxArr[i] = 0;
                } else {
                    double diP = smooth[1] / smooth[0];
                    double diM = smooth[2] / smooth[0];
                    double sum = diP + diM;
                    dxArr[i] = (sum == 0) ? 0 : 100.0 * Math.abs(diP - diM) / sum;
                }
            }

            double alpha = 2.0 / (n + 1);
            double adx = dxArr[n];
            for (int i = n + 1; i < history.size(); i++) {
                adx = adx * (1 - alpha) + dxArr[i] * alpha;
            }
            return BigDecimal.valueOf(adx).setScale(6, RoundingMode.HALF_UP);
        }
    }

    // ===== SAR / KDJ扩展 / BOLL扩展 / 均线排列 / 支撑阻力 (2026-05-17) =====

    /**
     * SAR（抛物转向指标）- 返回当前SAR值
     * AF初始0.02，每创新高/低加0.02，上限0.2
     * SAR > 价格 → 看跌；SAR < 价格 → 看涨
     */
    public static class SarCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "SAR";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 3) return null;

            double af = 0.02, maxAf = 0.2;
            boolean uptrend = history.get(1).getClose().doubleValue() > history.get(0).getClose().doubleValue();
            double ep, sar;

            if (uptrend) {
                // 初始SAR = 近n日最低价
                double lowest = Double.MAX_VALUE;
                for (int i = 0; i < history.size() - 1; i++) {
                    lowest = Math.min(lowest, history.get(i).getLow().doubleValue());
                }
                sar = lowest;
                ep = Double.MIN_VALUE;
                for (int i = 0; i < history.size() - 1; i++) {
                    ep = Math.max(ep, history.get(i).getHigh().doubleValue());
                }
            } else {
                double highest = Double.MIN_VALUE;
                for (int i = 0; i < history.size() - 1; i++) {
                    highest = Math.max(highest, history.get(i).getHigh().doubleValue());
                }
                sar = highest;
                ep = Double.MAX_VALUE;
                for (int i = 0; i < history.size() - 1; i++) {
                    ep = Math.min(ep, history.get(i).getLow().doubleValue());
                }
            }

            // 从第二根K线开始递推（history.size()-1 根已确认趋势的K线）
            for (int i = history.size() - 1; i > 0; i--) {
                MarketDailyBar curr = history.get(i);
                double currHigh = curr.getHigh().doubleValue();
                double currLow = curr.getLow().doubleValue();
                double currClose = curr.getClose().doubleValue();
                MarketDailyBar prev = history.get(i - 1);

                if (uptrend) {
                    // 上涨趋势
                    if (currHigh > ep) {
                        ep = currHigh;
                        af = Math.min(af + 0.02, maxAf);
                    }
                    double newSar = sar + af * (ep - sar);
                    // SAR 不能高于近两日最低价
                    double minLow = Math.min(prev.getLow().doubleValue(), currLow);
                    if (newSar > minLow) newSar = minLow;
                    if (newSar > currLow) {
                        // 趋势反转
                        uptrend = false;
                        sar = ep;
                        ep = currLow;
                        af = 0.02;
                    } else {
                        sar = newSar;
                    }
                } else {
                    // 下跌趋势
                    if (currLow < ep) {
                        ep = currLow;
                        af = Math.min(af + 0.02, maxAf);
                    }
                    double newSar = sar - af * (sar - ep);
                    // SAR 不能低于近两日最高价
                    double maxHigh = Math.max(prev.getHigh().doubleValue(), currHigh);
                    if (newSar < maxHigh) newSar = maxHigh;
                    if (newSar < currHigh) {
                        // 趋势反转
                        uptrend = true;
                        sar = ep;
                        ep = currHigh;
                        af = 0.02;
                    } else {
                        sar = newSar;
                    }
                }
            }

            return BigDecimal.valueOf(sar).setScale(6, RoundingMode.HALF_UP);
        }
    }

    /**
     * KDJ_D - KDJ指标D线（J=3K-2D）
     * 9日RSV平滑，K/D初值50
     */
    public static class KdjDCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "KDJ_D";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 10) return null;
            double k = 50, d = 50;
            for (int i = 0; i < history.size(); i++) {
                int start = Math.max(0, i - 8);
                double highest = Double.MIN_VALUE, lowest = Double.MAX_VALUE;
                for (int j = start; j <= i; j++) {
                    highest = Math.max(highest, history.get(j).getHigh().doubleValue());
                    lowest = Math.min(lowest, history.get(j).getLow().doubleValue());
                }
                double close = history.get(i).getClose().doubleValue();
                double rsv = (highest == lowest) ? 50 : (close - lowest) / (highest - lowest) * 100;
                k = (2.0 / 3.0) * k + (1.0 / 3.0) * rsv;
                d = (2.0 / 3.0) * d + (1.0 / 3.0) * k;
            }
            return BigDecimal.valueOf(d).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * KDJ_J - KDJ指标J线（J = 3K - 2D）
     * J > 100 超买，J < 0 超卖
     */
    public static class KdjJCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "KDJ_J";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 10) return null;
            double k = 50, d = 50;
            for (int i = 0; i < history.size(); i++) {
                int start = Math.max(0, i - 8);
                double highest = Double.MIN_VALUE, lowest = Double.MAX_VALUE;
                for (int j = start; j <= i; j++) {
                    highest = Math.max(highest, history.get(j).getHigh().doubleValue());
                    lowest = Math.min(lowest, history.get(j).getLow().doubleValue());
                }
                double close = history.get(i).getClose().doubleValue();
                double rsv = (highest == lowest) ? 50 : (close - lowest) / (highest - lowest) * 100;
                k = (2.0 / 3.0) * k + (1.0 / 3.0) * rsv;
                d = (2.0 / 3.0) * d + (1.0 / 3.0) * k;
            }
            double j = 3 * k - 2 * d;
            return BigDecimal.valueOf(j).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * BOLL_UPPER - 布林带上轨（MA20 + 2*STD）
     * 返回价格值（绝对值），非分位
     */
    public static class BollUpperCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "BOLL_UPPER";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 20) return null;
            List<MarketDailyBar> window = history.subList(history.size() - 20, history.size());
            double[] closes = window.stream().mapToDouble(b -> b.getClose().doubleValue()).toArray();
            double mean = 0;
            for (double c : closes) mean += c;
            mean /= closes.length;
            double std = 0;
            for (double c : closes) std += (c - mean) * (c - mean);
            std = Math.sqrt(std / closes.length);
            return BigDecimal.valueOf(mean + 2 * std).setScale(4, RoundingMode.HALF_UP);
        }
    }

    /**
     * BOLL_LOWER - 布林带下轨（MA20 - 2*STD）
     */
    public static class BollLowerCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "BOLL_LOWER";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 20) return null;
            List<MarketDailyBar> window = history.subList(history.size() - 20, history.size());
            double[] closes = window.stream().mapToDouble(b -> b.getClose().doubleValue()).toArray();
            double mean = 0;
            for (double c : closes) mean += c;
            mean /= closes.length;
            double std = 0;
            for (double c : closes) std += (c - mean) * (c - mean);
            std = Math.sqrt(std / closes.length);
            return BigDecimal.valueOf(mean - 2 * std).setScale(4, RoundingMode.HALF_UP);
        }
    }

    /**
     * BOLL_WIDTH - 布林带宽度（通道宽度 / 中轨）
     * 衡量波动率，宽度扩大 = 趋势可能反转或加速
     */
    public static class BollWidthCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "BOLL_WIDTH";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 20) return null;
            List<MarketDailyBar> window = history.subList(history.size() - 20, history.size());
            double[] closes = window.stream().mapToDouble(b -> b.getClose().doubleValue()).toArray();
            double mean = 0;
            for (double c : closes) mean += c;
            mean /= closes.length;
            double std = 0;
            for (double c : closes) std += (c - mean) * (c - mean);
            std = Math.sqrt(std / closes.length);
            if (mean == 0) return null;
            return BigDecimal.valueOf(2 * std / mean).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * MA_ALIGNMENT - 均线多头排列程度
     * 返回 0~5：5条均线（MA5/10/20/30/60）升序排列的数量
     * 5 = 完全多头排列（最强），0 = 完全空头排列（最弱）
     */
    public static class MaAlignmentCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "MA_ALIGNMENT";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 61) return null;
            double ma5 = avgClose(history, 5);
            double ma10 = avgClose(history, 10);
            double ma20 = avgClose(history, 20);
            double ma30 = avgClose(history, 30);
            double ma60 = avgClose(history, 60);
            int count = 0;
            if (ma5 > ma10) count++;
            if (ma10 > ma20) count++;
            if (ma20 > ma30) count++;
            if (ma30 > ma60) count++;
            return BigDecimal.valueOf(count).setScale(0, RoundingMode.HALF_UP);
        }

        private double avgClose(List<MarketDailyBar> h, int n) {
            return h.subList(h.size() - n, h.size())
                    .stream().mapToDouble(b -> b.getClose().doubleValue()).sum() / n;
        }
    }

    /**
     * NEAR_RESISTANCE - 距离最近阻力位的幅度（%）
     * 阻力位 = 近60日内的局部高点（排除最近5日）
     * 返回 (close - resistance) / close * 100，负值越大代表越接近阻力
     */
    public static class NearResistanceCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "NEAR_RESISTANCE";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 20) return null;
            // 取近60日（排除最近5日），找局部高点
            int lookback = Math.min(60, history.size() - 5);
            double close = history.getLast().getClose().doubleValue();
            double maxHigh = Double.MIN_VALUE;
            for (int i = history.size() - lookback; i < history.size() - 5; i++) {
                maxHigh = Math.max(maxHigh, history.get(i).getHigh().doubleValue());
            }
            if (maxHigh == Double.MIN_VALUE || close == 0) return null;
            return BigDecimal.valueOf((close - maxHigh) / close * 100).setScale(4, RoundingMode.HALF_UP);
        }
    }

    /**
     * NEAR_SUPPORT - 距离最近支撑位的幅度（%）
     * 支撑位 = 近60日内的局部低点（排除最近5日）
     * 返回 (support - close) / close * 100
     */
    public static class NearSupportCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "NEAR_SUPPORT";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 20) return null;
            int lookback = Math.min(60, history.size() - 5);
            double close = history.getLast().getClose().doubleValue();
            double minLow = Double.MAX_VALUE;
            for (int i = history.size() - lookback; i < history.size() - 5; i++) {
                minLow = Math.min(minLow, history.get(i).getLow().doubleValue());
            }
            if (minLow == Double.MAX_VALUE || close == 0) return null;
            return BigDecimal.valueOf((minLow - close) / close * 100).setScale(4, RoundingMode.HALF_UP);
        }
    }

    /**
     * 量比 (VOLUME_RATIO) - 近5日均量 / 近20日均量
     * > 1.5 = 放量，> 2 = 显著放量
     */
    public static class VolumeRatioCalculator2 implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "VOLUME_RATIO";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 21) return null;
            double sum5 = 0, sum20 = 0;
            for (int i = history.size() - 5; i < history.size(); i++) {
                sum5 += history.get(i).getVol().doubleValue();
            }
            for (int i = history.size() - 20; i < history.size(); i++) {
                sum20 += history.get(i).getVol().doubleValue();
            }
            if (sum20 == 0) return null;
            return BigDecimal.valueOf((sum5 / 5) / (sum20 / 20)).setScale(6, RoundingMode.HALF_UP);
        }
    }
}

