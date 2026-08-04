package com.quant.platform.backtest.engine;

import com.quant.platform.common.enums.JobStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 回测进度推送器（WebSocket）。
 *
 * <p>从 {@code BacktestEngine} 中抽取的「进度通知」职责（God Class 拆分 Phase 2）。
 * 方法体逐字迁移，行为与原实现完全一致，仅变更归属类。</p>
 */
@Slf4j
@Component
public class BacktestProgressNotifier {

    @Autowired(required = false)
    private SimpMessagingTemplate messagingTemplate;

    public void sendProgress(Long taskId, String stage, int pct, String message) {
        try {
            if (messagingTemplate != null) {
                messagingTemplate.convertAndSend("/topic/backtest/" + taskId,
                        Map.of("taskId", taskId, "stage", stage, "progress", pct, "message", message));
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 回测进行中：携带当天净值数据点（用于前端实时绘图）
     * 消息格式：{ taskId, stage:"RUNNING", progress, date, stratValue, bmValue }
     */
    public void sendProgressWithCurve(Long taskId, int pct, String date,
                                      double stratValue, double bmValue) {
        try {
            if (messagingTemplate != null) {
                Map<String, Object> msg = new HashMap<>();
                msg.put("taskId", taskId);
                msg.put("stage", JobStatus.RUNNING.name());
                msg.put("progress", pct);
                msg.put("message", "回测进行中 " + date);
                msg.put("date", date);
                msg.put("stratValue", stratValue);
                msg.put("bmValue", bmValue);
                messagingTemplate.convertAndSend("/topic/backtest/" + taskId, msg);
            }
        } catch (Exception ignored) {
        }
    }
}
