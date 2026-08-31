package com.quant.platform.stock.analysis.service;

import com.quant.platform.calendar.service.TradeCalendarService;
import com.quant.platform.stock.analysis.mapper.BidAskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
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
 *
 * ⚠️ 历史窗口口径：内外盘依赖腾讯实时盘口快照，历史未采集的交易日无法回溯补采。
 * 因此取"近N日"必须按【交易日历】取，而非按记录数 LIMIT——否则缺失日会被静默跳过，
 * 前端趋势图会把跨了 8 个自然日的 5 条记录画成"连续5日"，断档完全不可见。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BidAskService {

    private final BidAskMapper bidAskMapper;

    private final TradeCalendarService tradeCalendarService;

    /**
     * 获取完整内外盘分析数据（供前端展示）
     */
    public Map<String, Object> getBidAskAnalysis(String code) {
        Map<String, Object> result = new LinkedHashMap<>();

        // 1. 最新数据
        Map<String, Object> latest = bidAskMapper.selectLatestBidAsk(code);
        result.put("latest", latest);

        // 2. 近5个交易日历史（用于趋势判断）
        //    按交易日历取，缺失日显式补空，避免断档被静默跳过
        String anchorDate = latest != null && latest.get("trade_date") != null
                ? String.valueOf(latest.get("trade_date")) : null;
        HistoryWindow win = loadHistoryWindow(code, 5, anchorDate);
        List<Map<String, Object>> history5d = win.rows;
        result.put("history5d", history5d);
        result.put("historyAxis", win.axis);
        result.put("missingDays", win.missingDays);
        result.put("windowNote", "按交易日历取最近5个交易日；缺失日（源不可回溯）显式留空，不参与均值计算。");

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
            String trendLabel = switch (trend) {
                case "BUYER_STRONG" -> "强势买方主导（主动买盘压倒性）";
                case "BUYER_SLIGHT" -> "买方略强（主动买盘为主）";
                case "SELLER_SLIGHT" -> "卖方略强（主动卖盘为主）";
                case "SELLER_STRONG" -> "强势卖方主导（主动卖盘压倒性）";
                default -> "多空均衡";
            };
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
        String signalAnchor = latest.get("trade_date") != null
                ? String.valueOf(latest.get("trade_date")) : null;
        List<Map<String, Object>> hist = loadHistoryWindow(code, 3, signalAnchor).rows;
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
     * 历史窗口结果
     *
     * @param rows        有数据的行（倒序），供均值计算使用，保持原有语义
     * @param axis        按交易日历补齐后的完整轴（倒序），缺失日 ratio=null、missing=true
     * @param missingDays 窗口内缺失的交易日（升序）
     */
    private record HistoryWindow(List<Map<String, Object>> rows,
                                 List<Map<String, Object>> axis,
                                 List<String> missingDays) {
    }

    /**
     * 按交易日历取最近 N 个交易日的内外盘窗口。
     *
     * <p>锚点用【库中最新数据日期】而非"今天"：若当日数据尚未采集（如 cron 未跑），
     * 从今天起算会凭空多出一个必然缺失的交易日，产生误报。
     *
     * @param code       股票代码
     * @param days       需要的交易日个数
     * @param anchorDate 锚点日期（yyyy-MM-dd），为空则回退到按记录数取
     */
    private HistoryWindow loadHistoryWindow(String code, int days, String anchorDate) {
        List<LocalDate> tradingDays = resolveTradingDays(anchorDate, days);

        // 交易日历不可用或无锚点 → 兜底：按记录数取（旧行为）
        if (tradingDays.isEmpty()) {
            List<Map<String, Object>> rows = bidAskMapper.selectBidAskHistory(code, days);
            List<Map<String, Object>> axis = new ArrayList<>();
            for (Map<String, Object> r : rows) {
                Map<String, Object> slot = new LinkedHashMap<>(r);
                slot.put("missing", false);
                axis.add(slot);
            }
            return new HistoryWindow(rows, axis, Collections.emptyList());
        }

        List<Map<String, Object>> rows = bidAskMapper.selectBidAskByDates(code, tradingDays);
        Map<String, Map<String, Object>> byDate = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            byDate.put(String.valueOf(r.get("trade_date")), r);
        }

        List<Map<String, Object>> axis = new ArrayList<>(tradingDays.size());
        List<String> missingDays = new ArrayList<>();
        // 倒序输出（最近交易日在前）
        for (int i = tradingDays.size() - 1; i >= 0; i--) {
            String ds = tradingDays.get(i).toString();
            Map<String, Object> exist = byDate.get(ds);
            Map<String, Object> slot = new LinkedHashMap<>();
            slot.put("trade_date", ds);
            if (exist != null) {
                slot.put("ratio", exist.get("ratio"));
                slot.put("outer_vol", exist.get("outer_vol"));
                slot.put("inner_vol", exist.get("inner_vol"));
                slot.put("change_pct", exist.get("change_pct"));
                slot.put("missing", false);
            } else {
                slot.put("ratio", null);
                slot.put("outer_vol", null);
                slot.put("inner_vol", null);
                slot.put("change_pct", null);
                slot.put("missing", true);
                missingDays.add(ds);
            }
            axis.add(slot);
        }
        // missingDays 按升序展示更直观
        Collections.sort(missingDays);
        return new HistoryWindow(rows, axis, missingDays);
    }

    /**
     * 从锚点日期往前取 N 个交易日（含锚点）。日历异常时返回空列表，由调用方兜底。
     */
    private List<LocalDate> resolveTradingDays(String anchorDate, int days) {
        if (anchorDate == null || days <= 0) {
            return Collections.emptyList();
        }
        LocalDate anchor;
        try {
            anchor = LocalDate.parse(anchorDate.length() > 10 ? anchorDate.substring(0, 10) : anchorDate);
        } catch (Exception e) {
            log.warn("[BidAskService] 解析锚点日期失败: {}", anchorDate);
            return Collections.emptyList();
        }
        try {
            // 往前留足自然日（覆盖周末 + 最长约 15 天的春节/国庆长假）
            LocalDate start = anchor.minusDays(days * 3L + 20L);
            List<LocalDate> all = tradeCalendarService.getTradingDaysBetween(start, anchor);
            if (all.isEmpty()) {
                return Collections.emptyList();
            }
            if (all.size() <= days) {
                return all;
            }
            return new ArrayList<>(all.subList(all.size() - days, all.size()));
        } catch (Exception e) {
            log.warn("[BidAskService] 获取交易日历失败，回退按记录数取: {}", e.getMessage());
            return Collections.emptyList();
        }
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
                } catch (NumberFormatException ignored) {
                    log.warn("[BidAskService] 解析 ratio 失败: {}", r);
                }
            }
        }
        return count > 0 ? sum / count : 1.0;
    }
}
