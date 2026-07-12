package com.quant.platform.factor.engine;

import com.quant.platform.common.utils.LimitUpUtils;
import com.quant.platform.market.domain.MarketDailyBar;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 内置因子实现集合（ACTIVE 状态）
 * 2026-07-10: 废弃9个冗余因子（VOL60/MA5/RSI14/MACD/VAL_PB/VAL_PB_PERCENTILE/
 *   VAL_DIVIDEND_YIELD/PRICE_52W_HIGH_PCT/VOLUME_SURPRISE），新增2个因子（AMIHUD_ILLIQUIDITY/INDUSTRY_REL_MOM）
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
                                List<MarketDailyBar> history, Map<String, Object> context) {
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
     * 20日动量因子 (MOM20)
     */
    public static class Momentum20Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() { return "MOM20"; }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 21) return null;
            var latest = history.getLast();
            var past = history.get(history.size() - 21);
            if (past.getClose().compareTo(BigDecimal.ZERO) == 0) return null;
            return latest.getClose().subtract(past.getClose())
                    .divide(past.getClose(), 8, RoundingMode.HALF_UP);
        }
    }

    // ====================================================================
    // 波动率因子
    // ====================================================================

    /**
     * 20日历史波动率 (VOL20) = 20日对数收益率标准差 × sqrt(252)
     */
    public static class Volatility20Calculator implements FactorCalculator {
        @Override
        public String getFactorCode() { return "VOL20"; }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                List<MarketDailyBar> history, Map<String, Object> context) {
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
     * 量比因子 (VOLUME_RATIO) - 近5日均量/前20日均量
     * 注：计划被 AMIHUD_ILLIQUIDITY 替代，暂时保留
     */
    public static class VolumeRatioCalculator2 implements FactorCalculator {
        @Override
        public String getFactorCode() { return "VOLUME_RATIO"; }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                List<MarketDailyBar> history, Map<String, Object> context) {
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
                                List<MarketDailyBar> history, Map<String, Object> context) {
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

    // ====================================================================
    // Amihud 非流动性因子 (P4 新增)
    // ====================================================================

    /**
     * Amihud 非流动性指标 (AMIHUD_ILLIQUIDITY)
     * = 20日均值(|日收益率| / 日成交额(万元))
     * 越大表示流动性越差（小成交额下的大波动），方向为负IC（低流动性=超额收益）
     * 参考文献Amihud (2002): Illiquidity and stock returns
     */
    public static class AmihudIlliquidityCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() { return "AMIHUD_ILLIQUIDITY"; }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 21) return null;
            var window = history.subList(history.size() - 21, history.size());
            double sumAmihud = 0;
            int count = 0;
            for (int i = 1; i < window.size(); i++) {
                var prev = window.get(i - 1);
                var curr = window.get(i);
                if (prev.getClose() == null || prev.getClose().compareTo(BigDecimal.ZERO) == 0) continue;
                if (curr.getClose() == null) continue;
                if (curr.getAmount() == null || curr.getAmount().doubleValue() <= 0) continue;

                double dailyReturn = Math.abs(
                        (curr.getClose().doubleValue() - prev.getClose().doubleValue())
                        / prev.getClose().doubleValue()
                );
                // amount 单位：万元，Amihud = |return| / amount (万元)
                // 结果量级约 1e-6 ~ 1e-4，乘 1e6 使数值更友好
                double amihud = dailyReturn / curr.getAmount().doubleValue();
                sumAmihud += amihud;
                count++;
            }
            if (count < 10) return null;
            // 乘 1e6 使数值在 0~100 范围（更易比较和排名）
            return BigDecimal.valueOf(sumAmihud / count * 1e6)
                    .setScale(8, RoundingMode.HALF_UP);
        }
    }

    // ====================================================================
    // 行业相对动量因子 (P5 新增)
    // ====================================================================

    /**
     * 行业相对动量 (INDUSTRY_REL_MOM)
     * = 个股20日动量 - 所属行业20日平均动量
     * 通过 context 接收行业信息（FactorComputeEngine 预计算并传入）
     * context keys: "industry" (String), "industryAvgMom20" (Double)
     * 正值=跑赢行业，负值=跑输行业，剥离行业beta后提取个股Alpha
     */
    public static class IndustryRelMomCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() { return "INDUSTRY_REL_MOM"; }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                List<MarketDailyBar> history, Map<String, Object> context) {
            // 计算个股20日动量
            if (history.size() < 21) return null;
            var latest = history.getLast();
            var past = history.get(history.size() - 21);
            if (past.getClose().compareTo(BigDecimal.ZERO) == 0) return null;
            double stockMom = latest.getClose().subtract(past.getClose())
                    .divide(past.getClose(), 8, RoundingMode.HALF_UP).doubleValue();

            // 从 context 获取行业平均动量
            Object avgObj = context.get("industryAvgMom20");
            if (avgObj == null) return null;
            double industryAvgMom;
            if (avgObj instanceof Number) {
                industryAvgMom = ((Number) avgObj).doubleValue();
            } else {
                return null;
            }

            double relMom = stockMom - industryAvgMom;
            return BigDecimal.valueOf(relMom).setScale(8, RoundingMode.HALF_UP);
        }
    }

    // ====================================================================
    // 情绪因子 (SENTIMENT)
    // ====================================================================

    /**
     * 近20日涨停次数 (LIMIT_UP_COUNT)
     * 涨停阈值按板块区分：科创板/创业板20%，北交所30%，主板9.8%，ST股5%
     * ST股直接排除不计入
     */
    public static class LimitUpCountCalculator implements FactorCalculator {
        private static volatile Set<String> stStockCodes = Collections.emptySet();

        public static void initStStockCodes(Set<String> codes) {
            stStockCodes = Collections.unmodifiableSet(new HashSet<>(codes));
        }

        public static void clearStStockCodes() { stStockCodes = Collections.emptySet(); }

        @Override
        public String getFactorCode() { return "LIMIT_UP_COUNT"; }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 21) return null;

            String codeOnly = symbol.replaceAll("\\..*$", "");
            if (!stStockCodes.isEmpty() && stStockCodes.contains(codeOnly)) {
                return BigDecimal.valueOf(0).setScale(8, RoundingMode.HALF_UP);
            }

            var window = history.subList(history.size() - 21, history.size());
            int count = 0;
            for (var b : window) {
                if (b.getPctChg() == null) continue;
                double pct = b.getPctChg().doubleValue();
                double threshold = LimitUpUtils.getLimitUpThreshold(symbol, b.getTradeDate(), false);
                if (pct >= threshold) count++;
            }
            return BigDecimal.valueOf(count).setScale(8, RoundingMode.HALF_UP);
        }
    }

    // ====================================================================
    // 估值因子
    // ====================================================================

    /**
     * PE历史百分位 (VAL_PE_PERCENTILE)
     * = 当前 PE_TTM 在近750交易日PE序列中的百分位排名（0~100）
     */
    public static class PePercentileCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() { return "VAL_PE_PERCENTILE"; }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                List<MarketDailyBar> history, Map<String, Object> context) {
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
                    if (pe > 0 && pe < 50000) peValues.add(pe);
                }
            }
            if (peValues.size() < 30) return null;
            long lowerCount = peValues.stream().filter(v -> v < currentPe).count();
            return BigDecimal.valueOf((double) lowerCount / peValues.size() * 100.0)
                    .setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 市盈率TTM（日频） (VAL_PE_TTM)
     * 直接取 stock_daily.pe_ttm 字段
     */
    public static class PeTtmCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() { return "VAL_PE_TTM"; }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.isEmpty()) return null;
            var last = history.getLast();
            if (last.getPeTtm() == null || last.getPeTtm().compareTo(BigDecimal.ZERO) <= 0) return null;
            return last.getPeTtm().setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 股息率（日频） (VAL_DIVIDEND_YIELD)
     * = 近12月每股派息 / 收盘价 × 100
     */
    public static class DividendYieldCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() { return "VAL_DIVIDEND_YIELD"; }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.isEmpty()) return null;
            var last = history.getLast();
            if (last.getDividendPerShare12m() == null
                    || last.getDividendPerShare12m().compareTo(BigDecimal.ZERO) <= 0) return null;
            if (last.getClose() == null || last.getClose().compareTo(BigDecimal.ZERO) <= 0) return null;
            double dps = last.getDividendPerShare12m().doubleValue();
            double price = last.getClose().doubleValue();
            double result = dps / price * 100.0;
            if (Double.isNaN(result) || Double.isInfinite(result)) return null;
            return BigDecimal.valueOf(result).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 自由现金流收益率（日频） (VAL_FCF_YIELD)
     * = FCF（元）/ 总市值（万元×10000）× 100
     */
    public static class FcfYieldCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() { return "VAL_FCF_YIELD"; }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.isEmpty()) return null;
            var last = history.getLast();
            if (last.getFcf() == null) return null;
            if (last.getMarketCap() == null || last.getMarketCap().compareTo(BigDecimal.ZERO) <= 0) return null;
            double fcf = last.getFcf().doubleValue();
            double mcapYuan = last.getMarketCap().doubleValue() * 10000.0;
            double result = fcf / mcapYuan * 100.0;
            if (Double.isNaN(result) || Double.isInfinite(result)) return null;
            return BigDecimal.valueOf(result).setScale(8, RoundingMode.HALF_UP);
        }
    }

    // ====================================================================
    // 技术因子
    // ====================================================================

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
                                List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 21) return null;
            var window = history.subList(history.size() - 21, history.size());
            double sumTr = 0;
            for (int i = 1; i < window.size(); i++) {
                var curr = window.get(i);
                var prev = window.get(i - 1);
                double hl = curr.getHigh().subtract(curr.getLow()).abs().doubleValue();
                double hp = curr.getHigh().subtract(prev.getClose() != null ? prev.getClose() : prev.getOpen()).abs().doubleValue();
                double lp = curr.getLow().subtract(prev.getClose() != null ? prev.getClose() : prev.getOpen()).abs().doubleValue();
                sumTr += Math.max(hl, Math.max(hp, lp));
            }
            int trCount = window.size() - 1;
            return BigDecimal.valueOf(sumTr / trCount)
                    .setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 抛物线转向指标 (SAR)
     * 简化版：用最近5日的极值点判断趋势方向
     * 正值=上升趋势，负值=下降趋势
     */
    public static class SarCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() { return "SAR"; }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 6) return null;
            int n = history.size();
            var last = history.get(n - 1);

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
            double range = recentHigh - recentLow;
            if (range == 0) return BigDecimal.ZERO;
            double sarValue = ((close - recentLow) / range - 0.5) * 100;
            return BigDecimal.valueOf(sarValue).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 市值因子 (SIZE) = log(总市值)
     */
    public static class SizeCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() { return "SIZE"; }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.isEmpty()) return null;
            var last = history.getLast();
            if (last.getMarketCap() == null || last.getMarketCap().compareTo(BigDecimal.ZERO) <= 0)
                return null;
            double mc = last.getMarketCap().doubleValue();
            if (mc <= 0) return null;
            return BigDecimal.valueOf(Math.log(mc)).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 市净率（日频） (VAL_PB)
     * 直接取 stock_daily.pb 字段
     * PB越低=估值越低（value factor），IC=-0.041（反转方向）
     */
    public static class ValPbCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() { return "VAL_PB"; }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.isEmpty()) return null;
            var last = history.getLast();
            if (last.getPb() == null || last.getPb().compareTo(BigDecimal.ZERO) <= 0) return null;
            return last.getPb().setScale(8, RoundingMode.HALF_UP);
        }
    }

    // ====================================================================
    // 反转因子 (REVERSAL_5D) — 2026-07-12 新增
    // IC回测: IR=0.32 (5日前瞻), 覆盖全市场, = -MOM5
    // ====================================================================

    /**
     * 5日反转因子 (REVERSAL_5D) = -MOM5 = (过去收盘 - 当前收盘) / 过去收盘
     * 正值=超跌（适合反弹选股），负值=超涨
     */
    public static class Reversal5DCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() { return "REVERSAL_5D"; }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 6) return null;
            MarketDailyBar latest = history.getLast();
            MarketDailyBar past = history.get(history.size() - 6);
            if (past.getClose() == null || past.getClose().compareTo(BigDecimal.ZERO) == 0) return null;
            if (latest.getClose() == null) return null;
            return past.getClose().subtract(latest.getClose())
                    .divide(past.getClose(), 8, RoundingMode.HALF_UP);
        }
    }

    // ====================================================================
    // Beta因子 (BETA_60D) — 2026-07-12 新增
    // IC回测: IR=0.36 (20日前瞻), 覆盖全市场, 低Beta溢价效应
    // 需要context中提供 "indexReturns" (double[], 按日序排列的指数对数收益率)
    // ====================================================================

    /**
     * 60日Beta因子 (BETA_60D) = Cov(个股收益, 指数收益) / Var(指数收益)
     * 使用上证指数(000001)作为市场基准，60个交易日滚动窗口
     */
    public static class Beta60DCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() { return "BETA_60D"; }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                List<MarketDailyBar> history, Map<String, Object> context) {
            if (history.size() < 61) return null;
            // 从context获取指数日收益率序列
            Object idxObj = context.get("indexReturns");
            if (idxObj == null) return null;
            double[] indexReturns;
            if (idxObj instanceof double[]) {
                indexReturns = (double[]) idxObj;
            } else if (idxObj instanceof List) {
                List<?> list = (List<?>) idxObj;
                indexReturns = new double[list.size()];
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i) instanceof Number) {
                        indexReturns[i] = ((Number) list.get(i)).doubleValue();
                    } else {
                        return null;
                    }
                }
            } else {
                return null;
            }
            if (indexReturns.length < 60) return null;

            // 计算个股最近60日对数收益率
            var window = history.subList(history.size() - 61, history.size());
            double[] stockReturns = new double[60];
            for (int i = 0; i < 60; i++) {
                double prev = window.get(i).getClose().doubleValue();
                double curr = window.get(i + 1).getClose().doubleValue();
                if (prev <= 0 || curr <= 0) return null;
                stockReturns[i] = Math.log(curr / prev);
            }

            // 取最近60日指数收益率（与个股窗口对齐）
            int idxStart = indexReturns.length - 60;
            double stockMean = 0, idxMean = 0;
            for (int i = 0; i < 60; i++) {
                stockMean += stockReturns[i];
                idxMean += indexReturns[idxStart + i];
            }
            stockMean /= 60;
            idxMean /= 60;

            double cov = 0, idxVar = 0;
            for (int i = 0; i < 60; i++) {
                double sDev = stockReturns[i] - stockMean;
                double iDev = indexReturns[idxStart + i] - idxMean;
                cov += sDev * iDev;
                idxVar += iDev * iDev;
            }
            cov /= 59;
            idxVar /= 59;

            if (idxVar == 0) return null;
            double beta = cov / idxVar;
            return BigDecimal.valueOf(beta).setScale(8, RoundingMode.HALF_UP);
        }
    }

    // ====================================================================
    // 融资买入比因子 (MARGIN_BUY_RATIO) — 2026-07-12 新增
    // IC回测: IR=-0.36 (20日前瞻), 反转信号(高融资买入→后续下跌)
    // 需要context中提供 "marginBuyRatioMap" (Map<String,Double>, code→ratio)
    // ratio = margin_buy / margin_balance (融资买入强度)
    // ====================================================================

    /**
     * 融资买入比因子 (MARGIN_BUY_RATIO) = margin_buy / margin_balance
     * 数据来源: MySQL stock_sentiment_margin_detail 表
     * 高值=融资买入活跃（反转信号，后续倾向下跌）
     */
    public static class MarginBuyRatioCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() { return "MARGIN_BUY_RATIO"; }

        @Override
        @SuppressWarnings("unchecked")
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                List<MarketDailyBar> history, Map<String, Object> context) {
            Object mapObj = context.get("marginBuyRatioMap");
            if (mapObj == null || !(mapObj instanceof Map)) return null;

            Map<String, Double> ratioMap = (Map<String, Double>) mapObj;
            String code = symbol.replaceAll("\\..*$", "");
            Double ratio = ratioMap.get(code);
            if (ratio == null || ratio.isNaN() || ratio.isInfinite()) return null;

            return BigDecimal.valueOf(ratio).setScale(8, RoundingMode.HALF_UP);
        }
    }
}
