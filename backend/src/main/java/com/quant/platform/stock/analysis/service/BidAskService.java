package com.quant.platform.stock.analysis.service;

import com.quant.platform.stock.analysis.mapper.BidAskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内外盘比分析服务
 * 数据来源: stock_bid_ask（每日收盘快照，由 update_bid_ask.py 采集）
 *
 * 外盘: 以卖出价成交的成交量（主动买盘）→ 推动上涨
 * 内盘: 以买入价成交的成交量（主动卖盘）→ 推动下跌
 *
 * 内外盘比 = 外盘 / 内盘
 *   > 1.2 : 强势买方主导（+3分）
 *   1.0~1.2: 买方略强（+2分）
 *   0.8~1.0: 卖方略强（+1分）
 *   < 0.8 : 强势卖方主导（+0分）
 *
 * 趋势判断（3日均值）: 持续>1 表明资金持续流入
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BidAskService {

    private final BidAskMapper bidAskMapper;

    /**
     * 获取完整内外盘分析数据（供前端展示）
     */
    public Map<String, Object> getBidAskAnalysis(String code) {
        Map<String, Object> result = new LinkedHashMap<>();

        // 1. 最新数据
        Map<String, Object> latest = bidAskMapper.selectLatestBidAsk(code);
        result.put("latest", latest);

        // 2. 近5日历史（用于趋势判断）
        List<Map<String, Object>> history5d = bidAskMapper.selectBidAskHistory(code, 5);
        result.put("history5d", history5d);

        // 3. 计算统计指标
        if (latest != null && !latest.isEmpty()) {
            BigDecimal ratio = latest.get("ratio") != null
                    ? new BigDecimal(latest.get("ratio").toString()) : null;
            result.put("ratio", ratio);

            // 趋势判断（近5日均值）
            double avgRatio = calcAvgRatio(history5d);
            result.put("avgRatio5d", avgRatio);

            // 趋势方向
            String trend;
            if (avgRatio > 1.2) {
                trend = "BUYER_STRONG";
            } else if (avgRatio > 1.05) {
                trend = "BUYER_SLIGHT";
            } else if (avgRatio >= 0.95) {
                trend = "BALANCED";
            } else if (avgRatio >= 0.8) {
                trend = "SELLER_SLIGHT";
            } else {
                trend = "SELLER_STRONG";
            }
            result.put("trend", trend);

            // 趋势说明
            String trendLabel;
            switch (trend) {
                case "BUYER_STRONG":   trendLabel = "强势买方主导（主动买盘压倒性）"; break;
                case "BUYER_SLIGHT":   trendLabel = "买方略强（主动买盘为主）"; break;
                case "SELLER_SLIGHT":  trendLabel = "卖方略强（主动卖盘为主）"; break;
                case "SELLER_STRONG":  trendLabel = "强势卖方主导（主动卖盘压倒性）"; break;
                default:               trendLabel = "多空均衡"; break;
            }
            result.put("trendLabel", trendLabel);

            // 评分（满分3分）
            int score = calcScore(ratio);
            result.put("score", score);

        } else {
            result.put("ratio", null);
            result.put("avgRatio5d", 0.0);
            result.put("trend", "NO_DATA");
            result.put("trendLabel", "暂无数据");
            result.put("score", 0);
        }

        return result;
    }

    /**
     * 获取当日/最新内外盘比（供评分引擎使用）
     * 返回: ratio（BigDecimal）/ trend（String）/ score（Integer）
     */
    public Map<String, Object> getBidAskSignal(String code) {
        Map<String, Object> latest = bidAskMapper.selectLatestBidAsk(code);
        if (latest == null || latest.isEmpty()) {
            return Map.of("ratio", null, "trend", "NO_DATA", "score", 0,
                    "outerVol", null, "innerVol", null);
        }

        BigDecimal ratio = latest.get("ratio") != null
                ? new BigDecimal(latest.get("ratio").toString()) : null;
        List<Map<String, Object>> hist = bidAskMapper.selectBidAskHistory(code, 3);
        double avgRatio = calcAvgRatio(hist);

        String trend;
        if (avgRatio > 1.2) trend = "BUYER_STRONG";
        else if (avgRatio > 1.05) trend = "BUYER_SLIGHT";
        else if (avgRatio >= 0.95) trend = "BALANCED";
        else if (avgRatio >= 0.8) trend = "SELLER_SLIGHT";
        else if (ratio != null) trend = "SELLER_STRONG";
        else trend = "NO_DATA";

        int score = ratio != null ? calcScore(ratio) : 0;

        Map<String, Object> signal = new LinkedHashMap<>();
        signal.put("ratio", ratio);
        signal.put("avgRatio3d", avgRatio);
        signal.put("trend", trend);
        signal.put("score", score);
        signal.put("outerVol", latest.get("outer_vol"));
        signal.put("innerVol", latest.get("inner_vol"));
        signal.put("latestPrice", latest.get("latest_price"));
        signal.put("tradeDate", latest.get("trade_date"));

        return signal;
    }

    /**
     * 计算评分（0-3分）
     */
    private int calcScore(BigDecimal ratio) {
        if (ratio == null) return 0;
        double r = ratio.doubleValue();
        if (r > 1.5) return 3;       // 极度强势买方
        if (r > 1.2) return 3;      // 强势买方
        if (r > 1.0) return 2;      // 买方略强
        if (r >= 0.85) return 1;    // 卖方略强
        return 0;                    // 卖方主导
    }

    /**
     * 计算历史均比
     */
    private double calcAvgRatio(List<Map<String, Object>> history) {
        if (history == null || history.isEmpty()) return 1.0;
        double sum = 0;
        int count = 0;
        for (Map<String, Object> row : history) {
            Object r = row.get("ratio");
            if (r != null) {
                try {
                    sum += new BigDecimal(r.toString()).doubleValue();
                    count++;
                } catch (NumberFormatException ignored) {}
            }
        }
        return count > 0 ? sum / count : 1.0;
    }
}
