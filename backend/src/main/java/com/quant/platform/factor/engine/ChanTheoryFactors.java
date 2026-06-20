package com.quant.platform.factor.engine;

import com.quant.platform.factor.engine.chan.BuySellPoint;
import com.quant.platform.factor.engine.chan.ChanTheoryCalculator;
import com.quant.platform.factor.engine.chan.ChanTheoryResult;
import com.quant.platform.factor.engine.chan.Hub;
import com.quant.platform.market.domain.MarketDailyBar;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 缠论因子实现集合
 * 将缠论结构化数据转化为可量化的因子值
 */
public class ChanTheoryFactors {

    /**
     * CHAN_TREND — 走势类型因子
     * 值: 1=上涨趋势, 0=盘整, -1=下跌趋势
     * 用途: 大级别趋势判断,用于择时或过滤信号
     */
    public static class TrendTypeCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "CHAN_TREND";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history == null || history.size() < 30) return null;

            ChanTheoryResult result = ChanTheoryCalculator.calculate(history);
            if (result.getTrends().isEmpty()) return null;

            var lastTrend = result.lastTrend();
            return BigDecimal.valueOf(lastTrend.getTrendType().getValue())
                    .setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * CHAN_BUY_SELL — 买卖点信号因子
     * 值: 正值=买点(1/2/3对应一买二买三买), 负值=卖点(-1/-2/-3), 0=无信号
     * 用途: 直接作为交易信号源
     */
    public static class BuySellSignalCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "CHAN_BUY_SELL";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history == null || history.size() < 30) return null;

            ChanTheoryResult result = ChanTheoryCalculator.calculate(history);
            List<BuySellPoint> points = result.getBuySellPoints();
            if (points.isEmpty()) return null;

            // 返回最近一个买卖点的信号值
            BuySellPoint lastPoint = points.getLast();
            return BigDecimal.valueOf(lastPoint.getBuySellType().getValue())
                    .setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * CHAN_HUB_POS — 相对中枢位置因子
     * 值: 当前价格相对最后一个中枢的归一化位置
     * >1.0: 在中枢上方（强势）
     * [0,1]: 在中枢区间内（震荡）
     * <0: 在中枢下方（弱势）
     * 用途: 判断当前价格在中枢中的相对强弱
     */
    public static class HubPositionCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "CHAN_HUB_POS";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history == null || history.size() < 30) return null;

            ChanTheoryResult result = ChanTheoryCalculator.calculate(history);
            Hub hub = result.lastHub();
            if (hub == null) return null;

            double price = history.getLast().getClose().doubleValue();
            double hubHigh = hub.getHigh();
            double hubLow = hub.getLow();
            double range = hubHigh - hubLow;

            // 归一化: (price - ZD) / (ZG - ZD)
            double pos;
            if (range <= 1e-10) {
                pos = 0; // 中枢退化为一点
            } else {
                pos = (price - hubLow) / range;
            }

            return BigDecimal.valueOf(pos).setScale(6, RoundingMode.HALF_UP);
        }
    }

    /**
     * CHAN_PEN_COUNT — 笔数量因子
     * 值: 近期K线中识别出的笔数量（整数）
     * 用途: 衡量市场活跃度/波动率，笔多=震荡频繁，笔少=趋势明确
     */
    public static class PenCountCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "CHAN_PEN_COUNT";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history == null || history.size() < 30) return null;

            ChanTheoryResult result = ChanTheoryCalculator.calculate(history);
            List<com.quant.platform.factor.engine.chan.Pen> pens = result.getPens();
            if (pens == null || pens.isEmpty()) return null;

            return BigDecimal.valueOf(pens.size());
        }
    }

    /**
     * CHAN_PEN_DIR — 最后一笔方向因子
     * 值: +1=上升笔(底到顶), -1=下降笔(顶到底)
     * 用途: 判断当前短期方向，配合中枢位置使用
     */
    public static class PenDirCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() {
            return "CHAN_PEN_DIR";
        }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history == null || history.size() < 30) return null;

            ChanTheoryResult result = ChanTheoryCalculator.calculate(history);
            com.quant.platform.factor.engine.chan.Pen lastPen = result.lastPen();
            if (lastPen == null) return null;

            return BigDecimal.valueOf(lastPen.getDirection().getValue())
                    .setScale(0, RoundingMode.HALF_UP);
        }
    }

}
