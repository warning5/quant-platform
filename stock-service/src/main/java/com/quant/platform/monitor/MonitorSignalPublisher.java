package com.quant.platform.monitor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.quant.platform.notification.NotificationService;
import com.quant.platform.strategy.paper.PaperTradingService;
import com.quant.platform.stock.analysis.engine.SellSignalEngine;
import com.quant.platform.monitor.IntradayMonitorService.TargetPriceInfo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * MonitorSignalPublisher —— 由 IntradayMonitorService 零行为变化拆分而来。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonitorSignalPublisher {

    private final JdbcTemplate jdbcTemplate;
    private final NotificationService notificationService;
    private final PaperTradingService paperTradingService;
    private final MonitorQuoteClient quoteClient;

    /** SSE连接列表 */
    private final CopyOnWriteArrayList<SseEmitter> sseEmitters = new CopyOnWriteArrayList<>();

    /** 信号历史记录（最近50条，供/status接口返回） */
    private final CopyOnWriteArrayList<Map<String, Object>> signalHistory = new CopyOnWriteArrayList<>();

    // ── 信号推送 ──

    public void pushBuySignal(String stockCode, TargetPriceInfo target, double currentPrice,
                               EntrySignalAnalyzer.BreakoutSignal signal) {
        String msg = signal.toPushMessage();
        if (target.getStopLoss() != null) {
            msg += String.format(" 止损%.2f", target.getStopLoss().doubleValue());
        }
        if (target.getTargetPrice() != null) {
            msg += String.format(" 目标%.2f", target.getTargetPrice().doubleValue());
        }

        log.info("[IntradayMonitor] {}", msg);

        try {
            notificationService.sendAlert(msg);
        } catch (Exception e) {
            log.warn("[IntradayMonitor] 推送买入信号失败: {}", e.getMessage());
        }

        // SSE广播买入信号
        Map<String, Object> sseEvent = new HashMap<>();
        sseEvent.put("type", "signal");
        sseEvent.put("signalType", "BUY");
        sseEvent.put("stockCode", stockCode);
        sseEvent.put("stockName", target.getStockName());
        sseEvent.put("currentPrice", currentPrice);
        sseEvent.put("score", signal.getTotalScore());
        sseEvent.put("message", msg);
        sseEvent.put("time", LocalDateTime.now().toString());
        broadcastSse(sseEvent);
        recordSignalHistory(sseEvent);
    }

    public void pushWatchSignal(String stockCode, TargetPriceInfo target, double currentPrice,
                                  EntrySignalAnalyzer.BreakoutSignal signal) {
        String msg = String.format("观察中: %s(%s) 现价%.2f 在区间[%.2f~%.2f] 评分%d/100",
                target.getStockName(), stockCode, currentPrice,
                target.getBuyPriceLow().doubleValue(), target.getBuyPriceHigh().doubleValue(),
                signal.getTotalScore());

        log.info("[IntradayMonitor] {}", msg);

        // SSE广播观察信号
        Map<String, Object> sseEvent = new HashMap<>();
        sseEvent.put("type", "signal");
        sseEvent.put("signalType", "WATCH");
        sseEvent.put("stockCode", stockCode);
        sseEvent.put("stockName", target.getStockName());
        sseEvent.put("currentPrice", currentPrice);
        sseEvent.put("score", signal.getTotalScore());
        sseEvent.put("message", msg);
        sseEvent.put("time", LocalDateTime.now().toString());
        broadcastSse(sseEvent);
        recordSignalHistory(sseEvent);
    }

    public void pushStopLossSignal(String stockCode, TargetPriceInfo target, double currentPrice) {
        String msg = String.format("止损警告: %s(%s) 当前价 %.2f 已跌破止损价 %.2f",
                target.getStockName(), stockCode, currentPrice, target.getStopLoss().doubleValue());
        log.warn("[IntradayMonitor] {}", msg);

        try {
            notificationService.sendAlert(msg);
        } catch (Exception e) {
            log.warn("[IntradayMonitor] 推送止损信号失败: {}", e.getMessage());
        }

        // SSE广播止损信号
        Map<String, Object> sseEvent = new HashMap<>();
        sseEvent.put("type", "signal");
        sseEvent.put("signalType", "STOP");
        sseEvent.put("stockCode", stockCode);
        sseEvent.put("stockName", target.getStockName());
        sseEvent.put("currentPrice", currentPrice);
        sseEvent.put("stopLoss", target.getStopLoss().doubleValue());
        sseEvent.put("message", msg);
        sseEvent.put("time", LocalDateTime.now().toString());
        broadcastSse(sseEvent);
        recordSignalHistory(sseEvent);

        // ── 实时风控：自动执行模拟盘止损卖出 ──
        try {
            List<Map<String, Object>> paperPositions = jdbcTemplate.query(
                "SELECT pt.id as paper_id, pp.code " +
                "FROM paper_trading pt JOIN paper_position pp ON pt.id = pp.paper_id " +
                "WHERE pt.status = 'RUNNING' AND pp.code = ?",
                (rs, rowNum) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("paperId", rs.getLong("paper_id"));
                    m.put("code", rs.getString("code"));
                    return m;
                }, stockCode);
            for (Map<String, Object> pos : paperPositions) {
                Long paperId = (Long) pos.get("paperId");
                paperTradingService.autoSellByStopLoss(paperId, stockCode, "盘中止损触发");
            }
            if (!paperPositions.isEmpty()) {
                log.info("[IntradayMonitor] 自动止损卖出: {} 在 {} 个模拟盘中执行", stockCode, paperPositions.size());
            }
        } catch (Exception e) {
            log.warn("[IntradayMonitor] 自动止损执行失败: {} - {}", stockCode, e.getMessage());
        }
    }

    /**
     * 推送技术面卖点信号
     * 由 SellSignalEngine 检测7种卖点：MACD顶背离/KDJ死叉/放量滞涨/长上影/跌破均线/布林中轨等
     */
    public void pushSellSignal(String stockCode, String stockName, SellSignalEngine.SellSignalResult sellResult, double currentPrice) {
        SellSignalEngine.SellAction action = sellResult.getAction();
        if (action == SellSignalEngine.SellAction.HOLD) return;

        String actionText = action == SellSignalEngine.SellAction.SELL ? "卖出" : "减仓";
        StringBuilder sb = new StringBuilder();
        for (SellSignalEngine.SellSignalItem item : sellResult.getSignals()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(item.getName());
        }
        String msg = String.format("卖点提示: %s(%s) 当前价 %.2f, 建议%s, 信号: %s",
                stockName, stockCode, currentPrice, actionText, sb);
        log.info("[IntradayMonitor] {}", msg);

        try {
            notificationService.sendAlert(msg);
        } catch (Exception e) {
            log.warn("[IntradayMonitor] 推送卖点信号失败: {}", e.getMessage());
        }

        Map<String, Object> sseEvent = new HashMap<>();
        sseEvent.put("type", "signal");
        sseEvent.put("signalType", "SELL_SIGNAL");
        sseEvent.put("stockCode", stockCode);
        sseEvent.put("stockName", stockName);
        sseEvent.put("currentPrice", currentPrice);
        sseEvent.put("sellAction", action.getName());
        sseEvent.put("sellScore", sellResult.getScore());
        sseEvent.put("sellSignals", sellResult.getSignals());
        sseEvent.put("message", msg);
        sseEvent.put("time", LocalDateTime.now().toString());
        broadcastSse(sseEvent);
        recordSignalHistory(sseEvent);

        // SELL 级别自动执行模拟盘减仓
        if (action == SellSignalEngine.SellAction.SELL) {
            try {
                List<Map<String, Object>> paperPositions = jdbcTemplate.query(
                    "SELECT pt.id as paper_id, pp.code " +
                    "FROM paper_trading pt JOIN paper_position pp ON pt.id = pp.paper_id " +
                    "WHERE pt.status = 'RUNNING' AND pp.code = ?",
                    (rs, rowNum) -> {
                        Map<String, Object> m = new HashMap<>();
                        m.put("paperId", rs.getLong("paper_id"));
                        m.put("code", rs.getString("code"));
                        return m;
                    }, stockCode);
                for (Map<String, Object> pos : paperPositions) {
                    Long paperId = (Long) pos.get("paperId");
                    paperTradingService.autoSellByStopLoss(paperId, stockCode, "技术面卖点: " + sb);
                }
                if (!paperPositions.isEmpty()) {
                    log.info("[IntradayMonitor] 卖点自动卖出: {} 在 {} 个模拟盘中执行", stockCode, paperPositions.size());
                }
            } catch (Exception e) {
                log.warn("[IntradayMonitor] 卖点自动卖出失败: {} - {}", stockCode, e.getMessage());
            }
        }
    }

    /**
     * 记录信号到历史（保留最近50条）
     */
    private void recordSignalHistory(Map<String, Object> sseEvent) {
        signalHistory.add(0, new HashMap<>(sseEvent)); // 最新的在前面
        while (signalHistory.size() > 50) {
            signalHistory.remove(signalHistory.size() - 1);
        }
    }

    /** 获取信号历史记录（供/status接口返回） */
    public List<Map<String, Object>> getSignalHistory() {
        return Collections.unmodifiableList(new ArrayList<>(signalHistory));
    }

    /** 清除信号历史（内存 + 前端状态） */
    public void clearSignalHistory() {
        signalHistory.clear();
        log.info("[IntradayMonitor] 信号历史已清除");
    }

    /**
     * SSE广播：实时价格更新（每次轮询后推送）
     */
    public void broadcastPriceUpdate(Map<String, Double> prices) {
        if (sseEmitters.isEmpty()) return;

        Map<String, Object> priceEvent = new HashMap<>();
        priceEvent.put("type", "price");
        priceEvent.put("prices", prices);
        priceEvent.put("changePct", new HashMap<>(quoteClient.latestChangePctRef()));
        priceEvent.put("time", LocalDateTime.now().toString());
        broadcastSse(priceEvent);
    }

    public void broadcastSse(Map<String, Object> event) {
        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : sseEmitters) {
            try {
                emitter.send(SseEmitter.event().name("monitor").data(event));
            } catch (IOException e) {
                dead.add(emitter);
            }
        }
        if (!dead.isEmpty()) {
            sseEmitters.removeAll(dead);
            log.debug("[IntradayMonitor] 清理 {} 个断开的SSE连接", dead.size());
        }
    }

    /** 可变引用：供 IntradayMonitorService 按原语义直接操作 SSE 连接池 */
    CopyOnWriteArrayList<SseEmitter> sseEmittersRef() {
        return sseEmitters;
    }
}
