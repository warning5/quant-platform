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
 * 仅保留 ACTIVE 状态的因子（37个中的Java实现部分）
 * 废弃的110个因子已删除，如需恢复可从Git历史找回
 */
@Slf4j
@Component
public class BuiltinFactors {

    // ====================================================================
    // 动量因子
    // ====================================================================

    /**
     * 5日动量因子 (MOM5)
     */
    public static class Momentum5Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() { return "MOM5"; }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                List<MarketDailyBar> history, java.util.Map<String, Object> context) {
            if (history.size() < 6) return null;
            MarketDailyBar latest = history.getLast();
            MarketDailyBar past = history.get(history.size() - 6);
            if (past.getClose().compareTo(BigDecimal.ZERO) == 0) return null;
            return latest.getClose().subtract(past.getClose())
                    .divide(past.getClose(), 8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 20日动量因子 (MOM20)
     */
    public static class Momentum20Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() { return "MOM20"; }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                List<MarketDailyBar> history, java.util.Map<String, Object> context) {
            if (history.size() < 21) return null;
            var latest = history.getLast();
            var past = history.get(history.size() - 21);
            if (past.getClose().compareTo(BigDecimal.ZERO) == 0) return null;
            return latest.getClose().subtract(past.getClose())
                    .divide(past.getClose(), 8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 6日动量指标 (MTM6) - 当前价 - 6日前价
     */
    public static class Mtm6Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() { return "MTM6"; }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                List<MarketDailyBar> history, java.util.Map<String, Object> context) {
            if (history.size() < 7) return null;
            double curr = history.getLast().getClose().doubleValue();
            double past = history.get(history.size() - 7).getClose().doubleValue();
            return BigDecimal.valueOf(curr - past).setScale(8, RoundingMode.HALF_UP);
        }
    }

    // ====================================================================
    // 波动率因子
    // ====================================================================

    /**
     * 20日历史波动率 (VOL20)
     */
    public static class Volatility20Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() { return "VOL20"; }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                List<MarketDailyBar> history, java.util.Map<String, Object> context) {
            if (history.size() < 21) return null;
            var window = history.subList(history.size() - 21, history.size());
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
            double vol = Math.sqrt(variance) * Math.sqrt(252);
            return BigDecimal.valueOf(vol).setScale(8, RoundingMode.HALF_UP);
        }
    }

    // ====================================================================
    // 流动性 / 换手率因子
    // ====================================================================

    /**
     * 20日平均换手率 (TURN20)
     */
    public static class Turnover20Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() { return "TURN20"; }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                List<MarketDailyBar> history, java.util.Map<String, Object> context) {
            if (history.size() < 20) return null;
            var window = history.subList(history.size() - 20, history.size());
            double sum = window.stream()
                    .mapToDouble(b -> b.getTurnoverRate() == null ? 0 : b.getTurnoverRate().doubleValue())
                    .sum();
            return BigDecimal.valueOf(sum / 20).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 量比因子 (VOLUME_RATIO) - 近5日均量/前20日均量
     * 注：返回因子代码 "VOLUME_RATIO"
     */
    public static class VolumeRatioCalculator2 implements FactorCalculator {
        @Override
        public String getFactorCode() { return "VOLUME_RATIO"; }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                List<MarketDailyBar> history, java.util.Map<String, Object> context) {
            if (history.size() < 25) return null;
            double recentSum = 0;
            for (int i = history.size() - 5; i < history.size(); i++) {
                if (history.get(i).getVol() == null) return null;
                recentSum += history.get(i).getVol().doubleValue();
            }
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

    /**
     * 换手率异常度 (TURNOVER_ANOMALY) - 近5日均换手率/60日均换手率
     */
    public static class TurnoverAnomalyCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() { return "TURNOVER_ANOMALY"; }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                List<MarketDailyBar> history, java.util.Map<String, Object> context) {
            if (history.size() < 61) return null;
            double recentSum = 0;
            int recentCount = 0;
            for (int i = history.size() - 5; i < history.size(); i++) {
                if (history.get(i).getTurnoverRate() != null) {
                    recentSum += history.get(i).getTurnoverRate().doubleValue();
                    recentCount++;
                }
            }
            if (recentCount == 0) return null;
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
     * 成交量惊喜 (VOLUME_SURPRISE) - 近5日均量/20日均量的对数
     */
    public static class VolumeSurpriseCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() { return "VOLUME_SURPRISE"; }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                List<MarketDailyBar> history, java.util.Map<String, Object> context) {
            if (history.size() < 26) return null;
            double recentSum = 0;
            int recentCount = 0;
            for (int i = history.size() - 5; i < history.size(); i++) {
                if (history.get(i).getVol() != null) {
                    recentSum += history.get(i).getVol().doubleValue();
                    recentCount++;
                }
            }
            if (recentCount == 0) return null;
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

    // ====================================================================
    // 市值 / 技术因子
    // ====================================================================

    /**
     * 5日均线 (MA5)
     */
    public static class Ma5Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() { return "MA5"; }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                List<MarketDailyBar> history, java.util.Map<String, Object> context) {
            if (history.size() < 5) return null;
            var window = history.subList(history.size() - 5, history.size());
            double sum = window.stream().mapToDouble(b -> b.getClose().doubleValue()).sum();
            return BigDecimal.valueOf(sum / 5).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 14日RSI (RSI14)
     */
    public static class Rsi14Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() { return "RSI14"; }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                List<MarketDailyBar> history, java.util.Map<String, Object> context) {
            if (history.size() < 15) return null;
            var window = history.subList(history.size() - 15, history.size());
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
        public String getFactorCode() { return "MACD"; }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                List<MarketDailyBar> history, java.util.Map<String, Object> context) {
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

    // ====================================================================
    // 情绪因子 (SENTIMENT)
    // ====================================================================

    /**
     * 近20日涨停次数 - pctChg >= 9.8% 视为涨停
     */
    public static class LimitUpCountCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() { return "LIMIT_UP_COUNT"; }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                List<MarketDailyBar> history, java.util.Map<String, Object> context) {
            if (history.size() < 21) return null;
            var window = history.subList(history.size() - 21, history.size());
            int count = 0;
            for (var b : window) {
                if (b.getPctChg() != null && b.getPctChg().doubleValue() >= 9.8) count++;
            }
            return BigDecimal.valueOf(count).setScale(8, RoundingMode.HALF_UP);
        }
    }
}
