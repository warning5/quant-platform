package com.quant.platform.stock.analysis.engine;

import com.quant.platform.factor.engine.TechnicalIndicatorUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 卖出信号引擎
 * 与 TradingSignalEngine 对称，检测7种技术面卖点信号
 * 灵感来自《稳中求胜：散户波段交易战法》卖点研判体系
 *
 * 卖点类型：
 * 1. 二度冲高不破前高 — 顶背离信号
 * 2. 放量滞涨 — 量价背离
 * 3. 长上影线 — 上方压力大
 * 4. KDJ超买死叉 — 短期超买
 * 5. MACD顶背离 — 趋势走弱
 * 6. 跌破MA20/MA60 — 均线破位
 * 7. 跌破布林中轨 — 中期支撑失效
 */
@Slf4j
@Component
public class SellSignalEngine {

    /**
     * 卖出建议级别
     */
    public enum SellAction {
        HOLD("持有", 0),
        REDUCE("减仓", 1),
        SELL("卖出", 2);

        private final String name;
        private final int level;
        SellAction(String name, int level) { this.name = name; this.level = level; }
        public String getName() { return name; }
        public int getLevel() { return level; }
    }

    /**
     * 卖出信号检测结果
     */
    @Data
    public static class SellSignalResult {
        private SellAction action;
        private int score;                // 卖出信号强度 0~100
        private List<SellSignalItem> signals;
        private String summary;

        public SellSignalResult() {
            this.action = SellAction.HOLD;
            this.score = 0;
            this.signals = new ArrayList<>();
        }

        public void addSignal(String name, String desc, int weight) {
            this.signals.add(new SellSignalItem(name, desc, weight));
            this.score += weight;
        }

        public void finalizeAction() {
            if (score >= 60) {
                this.action = SellAction.SELL;
            } else if (score >= 30) {
                this.action = SellAction.REDUCE;
            } else {
                this.action = SellAction.HOLD;
            }
            this.summary = String.format("卖出信号强度%d分，建议%s", score, action.getName());
        }
    }

    @Data
    public static class SellSignalItem {
        private String name;
        private String description;
        private int weight;

        public SellSignalItem(String name, String description, int weight) {
            this.name = name;
            this.description = description;
            this.weight = weight;
        }
    }

    /**
     * 检测全部卖点信号
     *
     * @param close  收盘价数组（时间正序）
     * @param high   最高价数组
     * @param low    最低价数组
     * @param open   开盘价数组
     * @param volume 成交量数组
     * @return 卖出信号结果
     */
    public SellSignalResult checkSellSignals(double[] close, double[] high, double[] low, double[] open, double[] volume) {
        SellSignalResult result = new SellSignalResult();
        int n = close.length;

        if (n < 30) {
            result.finalizeAction();
            return result;
        }

        try {
            // 1. 二度冲高不破前高
            checkDoubleTop(close, high, result);

            // 2. 放量滞涨
            checkVolumeStagnation(close, open, volume, result);

            // 3. 长上影线
            checkLongUpperShadow(high, low, open, close, result);

            // 4. KDJ超买死叉
            checkKdjDeathCross(high, low, close, result);

            // 5. MACD顶背离
            checkMacdTopDivergence(close, result);

            // 6. 跌破MA20/MA60
            checkMaBreakdown(close, result);

            // 7. 跌破布林中轨
            checkBollMiddleBreak(close, result);

        } catch (Exception e) {
            log.warn("[SellSignalEngine] 卖点检测异常: {}", e.getMessage());
        }

        result.finalizeAction();
        return result;
    }

    /**
     * 1. 二度冲高不破前高
     * 最新价格接近前期高点但未能突破，且开始回落
     */
    private void checkDoubleTop(double[] close, double[] high, SellSignalResult result) {
        int n = close.length;
        int lookback = 20;
        if (n < lookback + 5) return;

        double recentHigh = TechnicalIndicatorUtils.recentHigh(high, lookback);
        double currentPrice = close[n - 1];
        double prevClose = close[n - 2];

        // 当前价格接近前高（95%以上）但未突破，且开始回落
        if (currentPrice >= recentHigh * 0.95 && currentPrice < recentHigh && currentPrice < prevClose) {
            result.addSignal("二度冲高不破前高",
                    String.format("当前价%.2f接近前高%.2f但未突破，开始回落", currentPrice, recentHigh), 20);
        }
    }

    /**
     * 2. 放量滞涨
     * 成交量放大但价格涨幅很小，说明上方抛压重
     */
    private void checkVolumeStagnation(double[] close, double[] open, double[] volume, SellSignalResult result) {
        if (TechnicalIndicatorUtils.isVolumeStagnation(close, open, volume, 10, 1.5)) {
            result.addSignal("放量滞涨", "成交量放大但涨幅不足1%，上方抛压沉重", 15);
        }
    }

    /**
     * 3. 长上影线
     * 上影线长度超过实体2倍，说明上方压力大
     */
    private void checkLongUpperShadow(double[] high, double[] low, double[] open, double[] close, SellSignalResult result) {
        if (TechnicalIndicatorUtils.isLongUpperShadow(high, low, open, close)) {
            result.addSignal("长上影线", "上影线超过实体2倍，上方抛压明显", 15);
        }
    }

    /**
     * 4. KDJ超买死叉
     * K线从超买区下穿D线，短期见顶信号
     */
    private void checkKdjDeathCross(double[] high, double[] low, double[] close, SellSignalResult result) {
        double[][] kdj = TechnicalIndicatorUtils.kdj(high, low, close);
        if (TechnicalIndicatorUtils.isKdjHighDeathCross(kdj[0], kdj[1])) {
            double k = kdj[0][close.length - 1];
            result.addSignal("KDJ超买死叉",
                    String.format("K=%.0f从超买区下穿D线，短期见顶信号", k), 20);
        }
    }

    /**
     * 5. MACD顶背离
     * 价格创新高但DIF未创新高，趋势走弱
     */
    private void checkMacdTopDivergence(double[] close, SellSignalResult result) {
        double[][] macd = TechnicalIndicatorUtils.macd(close);
        if (TechnicalIndicatorUtils.isMacdTopDivergence(close, macd[0], 30)) {
            result.addSignal("MACD顶背离", "价格创新高但MACD未创新高，上涨动能减弱", 25);
        }
    }

    /**
     * 6. 跌破MA20/MA60
     * 跌破MA20为短期减仓信号，跌破MA60为中期卖出信号
     */
    private void checkMaBreakdown(double[] close, SellSignalResult result) {
        int n = close.length;
        if (n < 60) return;

        double[] ma20 = TechnicalIndicatorUtils.sma(close, 20);
        double[] ma60 = TechnicalIndicatorUtils.sma(close, 60);
        double price = close[n - 1];
        double prevPrice = close[n - 2];

        // 跌破MA60 — 中期卖出信号
        if (prevPrice >= ma60[n - 2] && price < ma60[n - 1]) {
            result.addSignal("跌破MA60", String.format("收盘价%.2f跌破MA60=%.2f，中期趋势转弱", price, ma60[n - 1]), 25);
        }
        // 跌破MA20 — 短期减仓信号
        else if (prevPrice >= ma20[n - 2] && price < ma20[n - 1]) {
            result.addSignal("跌破MA20", String.format("收盘价%.2f跌破MA20=%.2f，短期支撑失效", price, ma20[n - 1]), 15);
        }
    }

    /**
     * 7. 跌破布林中轨
     * 价格从布林中轨上方跌至下方，中期支撑失效
     */
    private void checkBollMiddleBreak(double[] close, SellSignalResult result) {
        int n = close.length;
        if (n < 21) return;

        double[][] boll = TechnicalIndicatorUtils.boll(close, 20, 2.0);
        double mid = boll[0][n - 1];
        double prevMid = boll[0][n - 2];

        if (!Double.isNaN(mid) && !Double.isNaN(prevMid)) {
            if (close[n - 2] >= prevMid && close[n - 1] < mid) {
                result.addSignal("跌破布林中轨",
                        String.format("收盘价%.2f跌破布林中轨=%.2f，中期支撑失效", close[n - 1], mid), 15);
            }
        }
    }

    /**
     * 快速判断：是否有任何卖出信号（用于回测高频调用）
     */
    public boolean hasSellSignal(double[] close, double[] high, double[] low, double[] open, double[] volume) {
        SellSignalResult result = checkSellSignals(close, high, low, open, volume);
        return result.getAction() != SellAction.HOLD;
    }

    /**
     * 获取卖出信号级别（用于模拟盘/回测快速判断）
     */
    public SellAction getSellAction(double[] close, double[] high, double[] low, double[] open, double[] volume) {
        return checkSellSignals(close, high, low, open, volume).getAction();
    }
}
