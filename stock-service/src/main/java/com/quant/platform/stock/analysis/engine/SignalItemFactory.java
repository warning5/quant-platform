package com.quant.platform.stock.analysis.engine;

import com.quant.platform.stock.analysis.domain.ScoreDetail;

import java.math.BigDecimal;

/**
 * 评分明细项工厂
 * 自 TradingSignalEngine 逐字抽出的无状态构造/格式化 helper（consumer 用 import static 引入）。
 */
public final class SignalItemFactory {

    private SignalItemFactory() {
    }

    public static ScoreDetail.ScoreItem buildItem(String label, String value, int score, int maxScore, String desc) {
        return buildItem(label, value, score, maxScore, desc, false, null);
    }
    
    public static ScoreDetail.ScoreItem buildItem(String label, String value, int score, int maxScore, String desc, boolean infoOnly) {
        return buildItem(label, value, score, maxScore, desc, infoOnly, null);
    }
    
    public static ScoreDetail.ScoreItem buildItem(String label, String value, int score, int maxScore, String desc, boolean infoOnly, String color) {
        ScoreDetail.ScoreItem item = new ScoreDetail.ScoreItem();
        item.setLabel(label);
        item.setValue(value);
        item.setScore(score);
        item.setMaxScore(maxScore);
        item.setDesc(desc);
        item.setInfoOnly(infoOnly);
        item.setColor(color);
        return item;
    }

    /**
     * 格式化资金流向金额为可读字符串
     * >1亿 显示"X.XX亿", >1万 显示"X.XX万", 否则显示具体数值
     */
    public static String formatMoneyFlow(BigDecimal amount) {
        double v = amount.doubleValue();
        double absV = Math.abs(v);
        String sign = v >= 0 ? "+" : "";
        if (absV >= 1e8) {
            return sign + String.format("%.2f亿", v / 1e8);
        } else if (absV >= 1e4) {
            return sign + String.format("%.2f万", v / 1e4);
        } else {
            return sign + String.format("%.0f元", v);
        }
    }
}
