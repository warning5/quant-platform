package com.quant.platform.factor.engine;

import com.quant.platform.market.domain.MarketDailyBar;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

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
     * 60日动量因子 (MOM60) = (当前收盘 - 60日前收盘) / 60日前收盘
     */
    public static class Momentum60Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() { return "MOM60"; }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                List<MarketDailyBar> history, java.util.Map<String, Object> context) {
            if (history.size() < 61) return null;
            var latest = history.getLast();
            var past = history.get(history.size() - 61);
            if (past.getClose() == null || past.getClose().compareTo(BigDecimal.ZERO) == 0) return null;
            return latest.getClose().subtract(past.getClose())
                    .divide(past.getClose(), 8, RoundingMode.HALF_UP);
        }
    }

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
     * 60日历史波动率 (VOL60) = 60日对数收益率标准差 × sqrt(252)
     */
    public static class Volatility60Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() { return "VOL60"; }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                List<MarketDailyBar> history, java.util.Map<String, Object> context) {
            if (history.size() < 61) return null;
            var window = history.subList(history.size() - 61, history.size());
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
            return BigDecimal.valueOf(Math.sqrt(variance) * Math.sqrt(252))
                    .setScale(8, RoundingMode.HALF_UP);
        }
    }

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

    // ====================================================================
    // 估值分位因子（依赖 MarketDailyBar.peTtm / pb 字段）
    // ====================================================================

    /**
     * PE历史百分位 (VAL_PE_PERCENTILE)
     * = 当前 PE_TTM 在近750交易日（约3年）PE序列中的百分位排名（0~100）
     * 越低表示估值相对历史越便宜，亏损股（PE<=0）返回 null
     */
    public static class PePercentileCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() { return "VAL_PE_PERCENTILE"; }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                List<MarketDailyBar> history, java.util.Map<String, Object> context) {
            if (history.size() < 60) return null;
            var last = history.getLast();
            if (last.getPeTtm() == null) return null;
            double currentPe = last.getPeTtm().doubleValue();
            if (currentPe <= 0 || currentPe >= 10000) return null;

            int lookback = Math.min(750, history.size());
            var window = history.subList(history.size() - lookback, history.size());
            List<Double> peValues = new ArrayList<>();
            for (var bar : window) {
                if (bar.getPeTtm() != null) {
                    double pe = bar.getPeTtm().doubleValue();
                    if (pe > 0 && pe < 10000) peValues.add(pe);
                }
            }
            if (peValues.size() < 30) return null;
            long lowerCount = peValues.stream().filter(v -> v < currentPe).count();
            return BigDecimal.valueOf((double) lowerCount / peValues.size() * 100.0)
                    .setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * PB历史百分位 (VAL_PB_PERCENTILE)
     * = 当前 PB 在近750交易日 PB 序列中的百分位排名（0~100）
     */
    public static class PbPercentileCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() { return "VAL_PB_PERCENTILE"; }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                List<MarketDailyBar> history, java.util.Map<String, Object> context) {
            if (history.size() < 60) return null;
            var last = history.getLast();
            if (last.getPb() == null) return null;
            double currentPb = last.getPb().doubleValue();
            if (currentPb <= 0 || currentPb >= 10000) return null;

            int lookback = Math.min(750, history.size());
            var window = history.subList(history.size() - lookback, history.size());
            List<Double> pbValues = new ArrayList<>();
            for (var bar : window) {
                if (bar.getPb() != null) {
                    double pb = bar.getPb().doubleValue();
                    if (pb > 0 && pb < 10000) pbValues.add(pb);
                }
            }
            if (pbValues.size() < 30) return null;
            long lowerCount = pbValues.stream().filter(v -> v < currentPb).count();
            return BigDecimal.valueOf((double) lowerCount / pbValues.size() * 100.0)
                    .setScale(8, RoundingMode.HALF_UP);
        }
    }

    // ====================================================================
    // 日频估值因子（依赖 dividendPerShare12m / fcf / marketCap）
    // ====================================================================

    /**
     * 市盈率TTM（日频） (VAL_PE_TTM)
     * 直接取 ClickHouse stock_daily.pe_ttm 字段，每天随股价更新
     */
    public static class PeTtmCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() { return "VAL_PE_TTM"; }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                List<MarketDailyBar> history, java.util.Map<String, Object> context) {
            if (history.isEmpty()) return null;
            var last = history.getLast();
            if (last.getPeTtm() == null || last.getPeTtm().compareTo(BigDecimal.ZERO) <= 0) return null;
            return last.getPeTtm().setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 市净率（日频） (VAL_PB)
     * 直接取 ClickHouse stock_daily.pb 字段，每天随股价更新
     */
    public static class PbCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() { return "VAL_PB"; }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                List<MarketDailyBar> history, java.util.Map<String, Object> context) {
            if (history.isEmpty()) return null;
            var last = history.getLast();
            if (last.getPb() == null || last.getPb().compareTo(BigDecimal.ZERO) <= 0) return null;
            return last.getPb().setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 股息率（日频） (VAL_DIVIDEND_YIELD)
     * = 近12月每股派息 / 当前收盘价 × 100
     * 分红相对稳定，股价每天变化，故日频计算更准确
     */
    public static class DividendYieldCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() { return "VAL_DIVIDEND_YIELD"; }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                List<MarketDailyBar> history, java.util.Map<String, Object> context) {
            if (history.isEmpty()) return null;
            var last = history.getLast();
            if (last.getDividendPerShare12m() == null
                    || last.getDividendPerShare12m().compareTo(BigDecimal.ZERO) <= 0) return null;
            if (last.getClose() == null || last.getClose().compareTo(BigDecimal.ZERO) <= 0) return null;
            double dps = last.getDividendPerShare12m().doubleValue();
            double close = last.getClose().doubleValue();
            return BigDecimal.valueOf(dps / close * 100.0).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 自由现金流收益率（日频） (VAL_FCF_YIELD)
     * = FCF（元）/ 总市值（万元×10000）× 100
     * FCF季频更新，市值每天变化，故日频计算更准确
     */
    public static class FcfYieldCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() { return "VAL_FCF_YIELD"; }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                List<MarketDailyBar> history, java.util.Map<String, Object> context) {
            if (history.isEmpty()) return null;
            var last = history.getLast();
            if (last.getFcf() == null) return null;
            if (last.getMarketCap() == null || last.getMarketCap().compareTo(BigDecimal.ZERO) <= 0) return null;
            double fcf = last.getFcf().doubleValue();
            // marketCap 单位：万元，转元需 ×10000
            double mcapYuan = last.getMarketCap().doubleValue() * 10000.0;
            double result = fcf / mcapYuan * 100.0;
            if (Double.isNaN(result) || Double.isInfinite(result)) return null;
            return BigDecimal.valueOf(result).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 20日平均真实波幅 (ATR20)
     * TR = max(high-low, |high-preClose|, |low-preClose|)
     * ATR = 20日简单移动平均
     */
    public static class Atr20Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() { return "ATR20"; }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                List<MarketDailyBar> history, java.util.Map<String, Object> context) {
            if (history.size() < 21) return null;
            // 取最近21根K线（含当日），计算20个TR值
            double sumTr = 0;
            for (int i = 1; i < history.size(); i++) {
                var curr = history.get(i);
                var prev = history.get(i - 1);
                double hl = curr.getHigh().subtract(curr.getLow()).abs().doubleValue();
                double hp = curr.getHigh().subtract(prev.getClose() != null ? prev.getClose() : prev.getOpen()).abs().doubleValue();
                double lp = curr.getLow().subtract(prev.getClose() != null ? prev.getClose() : prev.getOpen()).abs().doubleValue();
                sumTr += Math.max(hl, Math.max(hp, lp));
            }
            return BigDecimal.valueOf(sumTr / (history.size() - 1))
                    .setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 抛物线转向指标 (SAR)
     * 简化版：用最近5日的极值点判断趋势方向
     * 正值=上升趋势，负值=下降趋势，绝对值=SAR价格参考
     */
    public static class SarCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() { return "SAR"; }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                List<MarketDailyBar> history, java.util.Map<String, Object> context) {
            if (history.size() < 6) return null;
            int n = history.size();
            var last = history.get(n - 1);

            // 用最近5日高低点判断短期趋势
            double recentHigh = Double.MIN_VALUE, recentLow = Double.MAX_VALUE;
            for (int i = Math.max(0, n - 5); i < n; i++) {
                var bar = history.get(i);
                if (bar.getHigh() != null && bar.getHigh().doubleValue() > recentHigh)
                    recentHigh = bar.getHigh().doubleValue();
                if (bar.getLow() != null && bar.getLow().doubleValue() > 0 && bar.getLow().doubleValue() < recentLow)
                    recentLow = bar.getLow().doubleValue();
            }

            if (recentLow >= Double.MAX_VALUE || recentHigh <= Double.MIN_VALUE) return null;

            double close = last.getClose() != null ? last.getClose().doubleValue() : 0;
            // 趋势强度 = (close - 最近低点) / (最近高点 - 最近低点) * 100 - 50
            // 正值偏多，负值偏空
            double range = recentHigh - recentLow;
            if (range == 0) return BigDecimal.ZERO;
            double sarValue = ((close - recentLow) / range - 0.5) * 100;
            return BigDecimal.valueOf(sarValue).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 距52周高点回撤百分比 (PRICE_52W_HIGH_PCT)
     * = (当前收盘价 - 近252日最高价) / 近252日最高价 × 100
     * 结果为负数，越低(绝对值越大)说明回撤越深
     */
    public static class Price52wHighPctCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() { return "PRICE_52W_HIGH_PCT"; }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                List<MarketDailyBar> history, java.util.Map<String, Object> context) {
            if (history.size() < 60) return null;
            int lookback = Math.min(252, history.size());
            var window = history.subList(history.size() - lookback, history.size());
            double high52w = window.stream()
                    .filter(b -> b.getHigh() != null)
                    .mapToDouble(b -> b.getHigh().doubleValue())
                    .max().orElse(0);
            if (high52w <= 0) return null;
            var last = history.getLast();
            if (last.getClose() == null) return null;
            double pct = (last.getClose().doubleValue() - high52w) / high52w * 100.0;
            return BigDecimal.valueOf(pct).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 市值因子 (SIZE) = log(总市值)
     * 与 recompute_factors.py 的 calc_size 保持一致
     */
    public static class SizeCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() { return "SIZE"; }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                List<MarketDailyBar> history, java.util.Map<String, Object> context) {
            if (history.isEmpty()) return null;
            var last = history.getLast();
            if (last.getMarketCap() == null || last.getMarketCap().compareTo(BigDecimal.ZERO) <= 0)
                return null;
            double mc = last.getMarketCap().doubleValue();
            if (mc <= 0) return null;
            return BigDecimal.valueOf(Math.log(mc)).setScale(8, RoundingMode.HALF_UP);
        }
    }
}
