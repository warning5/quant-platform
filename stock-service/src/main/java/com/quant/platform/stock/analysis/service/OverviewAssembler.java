package com.quant.platform.stock.analysis.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.factor.engine.chan.ChanTheoryCalculator;
import com.quant.platform.factor.engine.chan.ChanTheoryResult;
import com.quant.platform.market.domain.MarketDailyBar;
import com.quant.platform.stock.analysis.domain.*;
import com.quant.platform.stock.analysis.engine.TradingSignalEngine;
import com.quant.platform.stock.analysis.mapper.AnalysisChMapper;
import com.quant.platform.stock.analysis.mapper.BidAskMapper;
import com.quant.platform.stock.analysis.mapper.NewsMapper;
import com.quant.platform.stock.analysis.mapper.StockAnalysisMapper;
import com.quant.platform.stock.service.ClickHouseStockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
@Service
@RequiredArgsConstructor
public class OverviewAssembler {
    public String buildConclusion(AnalysisOverview o, TradingSignal signal) {
        StringBuilder sb = new StringBuilder();
        String displayName = o.getName() != null ? o.getName() : o.getCode();
        sb.append("【").append(displayName).append("(").append(o.getCode()).append(")】");

        // 操作建议
        sb.append("综合评分").append(signal.getTotalScore()).append("分，");
        sb.append("建议【").append(signal.getActionName()).append("】");
        sb.append("，建议仓位").append(signal.getPosition()).append("%。");

        // 四维度：输出关键判断依据，而非笼统的"强/弱"
        if (o.getScoreDetails() != null) {
            for (var d : o.getScoreDetails()) {
                String reason = buildDimensionReason(d, o);
                sb.append(d.getDimensionName()).append("：").append(reason).append("；");
            }
        }

        // 风险提示
        if (o.getRisks() != null && !o.getRisks().isEmpty()) {
            sb.append("注意：").append(o.getRisks());
        }
        return sb.toString();
    }

    public String buildDimensionReason(ScoreDetail d, AnalysisOverview o) {
        double pct = d.getMaxScore() > 0 ? (double) d.getScore() / d.getMaxScore() : 0;
        String level;
        if (pct >= 0.8) level = "强";
        else if (pct >= 0.6) level = "较强";
        else if (pct >= 0.4) level = "一般";
        else if (pct >= 0.2) level = "较弱";
        else level = "弱";

        // 取各维度最有价值的判断依据（优先取有分数的item，最多2个）
        List<String> parts = new ArrayList<>();
        if (d.getItems() != null) {
            for (var item : d.getItems()) {
                if (item.getScore() > 0 && item.getValue() != null
                        && !item.getValue().equals("-") && !item.getValue().equals("暂无数据")) {
                    parts.add(mapChinese(item.getLabel()) + mapChinese(item.getValue()));
                }
                if (parts.size() >= 2) break;
            }
            // 如果没找到有分的，取前2个非空的
            if (parts.isEmpty()) {
                for (var item : d.getItems()) {
                    if (item.getValue() != null && !item.getValue().equals("-") && !item.getValue().equals("暂无数据")) {
                        parts.add(mapChinese(item.getLabel()) + mapChinese(item.getValue()));
                    }
                    if (parts.size() >= 2) break;
                }
            }
        }
        return level + (parts.isEmpty() ? "" : "（" + String.join("，", parts) + "）");
    }

    public String mapChinese(String v) {
        if (v == null) return "";
        return switch (v) {
            case "BUY" -> "买入";
            case "SELL" -> "卖出";
            case "HOLD" -> "持有";
            case "BULLISH" -> "牛市";
            case "SIDEWAYS" -> "横盘";
            case "BEARISH" -> "熊市";
            case "是" -> "是";
            case "否" -> "否";
            default -> v;
        };
    }

    public String buildExecutionPlan(TradingSignal signal, BigDecimal currentPrice,
                                       String targetPrice, String stopLossPrice, String targetPrice2,
                                       String riskLevel, String confidenceLevel) {
        if (signal == null || signal.getAction() == null) return null;
        String action = signal.getAction();

        // 根据风险等级确定批次比例
        int b1, b2, b3;
        if ("高".equals(riskLevel)) {
            b1 = 50; b2 = 30; b3 = 20; // 高风险：首批更大（买入更谨慎/卖出更果断）
        } else if ("低".equals(riskLevel)) {
            b1 = 30; b2 = 35; b3 = 35; // 低风险：首批更小（分批更均匀）
        } else {
            b1 = 40; b2 = 30; b3 = 30; // 中风险：默认比例
        }
        // 信心低 → 首批再缩小10%
        if ("低".equals(confidenceLevel)) {
            b1 = Math.max(20, b1 - 10);
            b2 = b2 + 5;
            b3 = b3 + 5;
        }

        if ("CLEAR".equals(action) || "REDUCE".equals(action)) {
            // 卖出执行方案：风险越高首批越大
            int sellB1 = "高".equals(riskLevel) ? 70 : "低".equals(riskLevel) ? 50 : 60;
            int sellB2 = 100 - sellB1 - 15;
            int sellB3 = 15;
            StringBuilder sb = new StringBuilder();
            sb.append("第一批").append(sellB1).append("%立即卖出");
            if (targetPrice != null) sb.append("；第二批").append(sellB2)
                    .append("%反弹至").append(targetPrice).append("卖出");
            if (targetPrice2 != null) sb.append("，跌破").append(targetPrice2).append("清仓剩余");
            else sb.append("；第三批").append(sellB3).append("%止损位")
                    .append(stopLossPrice != null ? stopLossPrice : "自定").append("清仓");
            return sb.toString();
        } else if ("BUY".equals(action) || "STRONG_BUY".equals(action)) {
            StringBuilder sb = new StringBuilder();
            sb.append("第一批").append(b1).append("%当前价建仓");
            if (stopLossPrice != null) sb.append("；第二批").append(b2)
                    .append("%回调至").append(stopLossPrice).append("加仓");
            if (targetPrice2 != null) sb.append("；第三批").append(b3)
                    .append("%突破").append(targetPrice2).append("追击");
            return sb.toString();
        }
        return "暂无明显买卖信号，建议观望";
    }

    public String calcSuggestedPositionPct(TradingSignal signal, String confidenceLevel,
                                            FundamentalSignal fundamental, boolean isBlueChip) {
        if (signal == null) return null;
        String action = signal.getAction();
        if (action == null) return null;
        double baseLow, baseHigh;
        switch (action) {
            case "STRONG_BUY": baseLow = 8; baseHigh = 10; break;
            case "BUY":        baseLow = 5; baseHigh = 8;  break;
            case "HOLD":       baseLow = 3; baseHigh = 5;  break;
            case "REDUCE":     baseLow = 0; baseHigh = 3;  break;
            case "CLEAR":      return "0%";
            default:           return null;
        }
        double confidenceCoef = "高".equals(confidenceLevel) ? 1.0
                : "中".equals(confidenceLevel) ? 0.85 : 0.6;
        double blueChipCoef = isBlueChip ? 1.1 : 1.0;
        if (fundamental != null && fundamental.getDebtRatio() != null
                && fundamental.getDebtRatio().compareTo(BigDecimal.valueOf(80)) > 0) {
            blueChipCoef *= 0.8;
        }
        double low = baseLow * confidenceCoef * blueChipCoef;
        double high = baseHigh * confidenceCoef * blueChipCoef;
        if (high > 15) high = 15;
        if (low > high) low = high;
        if (low < 0) low = 0;
        return String.format("%.0f-%.0f%%", low, high);
    }

    public String calcReducePriceRange(BigDecimal currentPrice, BigDecimal resistancePrice, TradingSignal signal) {
        if (currentPrice == null || signal == null) return null;
        String action = signal.getAction();
        if ("CLEAR".equals(action) || "REDUCE".equals(action)) {
            return "建议立即减仓";
        }
        BigDecimal anchor = resistancePrice != null ? resistancePrice : currentPrice;
        BigDecimal low = anchor.multiply(BigDecimal.valueOf(0.99)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal high = anchor.multiply(BigDecimal.valueOf(1.02)).setScale(2, RoundingMode.HALF_UP);
        return low + "-" + high;
    }

    public String calcRiskLevel(TradingSignal signal, FundamentalSignal fundamental,
                                 MoneyFlowSignal money, BigDecimal currentPrice) {
        if (signal == null) return "中";
        int risk = 0;
        String action = signal.getAction();
        if ("CLEAR".equals(action) || "REDUCE".equals(action)) risk += 3;
        else if ("HOLD".equals(action)) risk += 1;
        if (fundamental != null) {
            if (fundamental.getPeTtm() != null) {
                double pe = fundamental.getPeTtm().doubleValue();
                if (pe > 100) risk += 3;
                else if (pe > 50) risk += 2;
                else if (pe > 30) risk += 1;
            }
            if (fundamental.getDebtRatio() != null
                    && fundamental.getDebtRatio().compareTo(BigDecimal.valueOf(70)) > 0) {
                risk += 2;
            }
        }
        if (money != null && money.getNetMain() != null
                && money.getNetMain().doubleValue() < 0) {
            risk += 1;
        }
        if (risk <= 3) return "低";
        if (risk <= 6) return "中";
        return "高";
    }

    public String calcConfidenceLevel(FundamentalSignal fundamental, ResearchSignal research) {
        int score = 0;
        // 有研报覆盖（reportCount是int原始类型，无法判空）
        if (research != null && research.getReportCount() > 0) score += 3;
        // PE/PB/ROE 数据完整
        if (fundamental != null) {
            if (fundamental.getPeTtm() != null && fundamental.getPeTtm().doubleValue() > 0) score += 2;
            if (fundamental.getRoe() != null && fundamental.getRoe().doubleValue() > 0) score += 2;
            if (fundamental.getRevenueYoy() != null) score += 1;
        }
        if (score >= 6) return "高";
        if (score >= 3) return "中";
        return "低";
    }

    public int calcNewsScore(int positive, int negative, int tagged, double sentimentBias) {
        int score = 0;
        if (positive + negative > 0) score += 1;  // 有新闻
        if (positive > negative) score += 3;       // 利好偏多
        else if (positive > 0 && negative == 0) score += 2;  // 纯利好
        if (tagged > 0) score += 2;                // 有重大事件标签
        if (sentimentBias > 0.5) score += 2;       // 强烈利好偏向
        else if (sentimentBias < -0.5) score -= 1; // 强烈风险偏向
        return Math.max(0, Math.min(10, score));
    }

}
