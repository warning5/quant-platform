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
     * CHAN_PEN_DIR — 当前笔方向因子
     * 值: +1=上升笔, -1=下降笔, 0=无有效笔
     * 用途: 判断当前处于上升还是下降趋势中,可用于动量/反转策略
     */
    public static class PenDirectionCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() { return "CHAN_PEN_DIR"; }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history == null || history.size() < 20) return null;

            ChanTheoryResult result = ChanTheoryCalculator.calculate(history);
            if (result.getPens().isEmpty()) return null;

            // 取最后一笔的方向
            var lastPen = result.lastPen();
            return BigDecimal.valueOf(lastPen.getDirection().getValue())
                    .setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * CHAN_TREND — 走势类型因子
     * 值: 1=上涨趋势, 0=盘整, -1=下跌趋势
     * 用途: 大级别趋势判断,用于择时或过滤信号
     */
    public static class TrendTypeCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() { return "CHAN_TREND"; }

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
        public String getFactorCode() { return "CHAN_BUY_SELL"; }

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
     *   >1.0: 在中枢上方（强势）
     *   [0,1]: 在中枢区间内（震荡）
     *   <0: 在中枢下方（弱势）
     * 用途: 判断当前价格在中枢中的相对强弱
     */
    public static class HubPositionCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() { return "CHAN_HUB_POS"; }

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
     * 值: 最近N根K线内识别出的笔数,反映波动活跃度
     */
    public static class PenCountCalculator implements FactorCalculator {
        @Override
        public String getFactorCode() { return "CHAN_PEN_COUNT"; }

        @Override
        public BigDecimal calculate(String symbol, LocalDate calcDate,
                                    List<MarketDailyBar> history, Map<String, Object> context) {
            if (history == null || history.size() < 10) return null;

            ChanTheoryResult result = ChanTheoryCalculator.calculate(history);
            int penCount = result.getPens().size();

            return BigDecimal.valueOf(penCount)
                    .setScale(4, RoundingMode.HALF_UP);
        }
    }
}
