package com.quant.platform.strategy.paper;

import com.quant.platform.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 模拟盘交易 API
 */
@RestController
@RequestMapping("/paper-trading")
@RequiredArgsConstructor
@Tag(name = "模拟盘交易", description = "策略模拟盘：信号生成、持仓管理、净值追踪")
public class PaperTradingController {

    private final PaperTradingService paperTradingService;
    private final PositionAlertService positionAlertService;

    @PostMapping("/create")
    @Operation(summary = "创建模拟盘")
    public ApiResponse<PaperTrading> create(
            @RequestParam Long strategyId,
            @RequestParam(required = false) String strategyCode,
            @RequestParam(defaultValue = "1000000") BigDecimal initialCapital) {
        return ApiResponse.success("模拟盘创建成功",
            paperTradingService.createPaperTrading(strategyId, strategyCode, initialCapital));
    }

    @GetMapping("/list")
    @Operation(summary = "获取所有模拟盘")
    public ApiResponse<List<PaperTrading>> list() {
        return ApiResponse.success(paperTradingService.listAll());
    }

    @GetMapping("/{paperId}")
    @Operation(summary = "获取模拟盘详情（持仓+净值）")
    public ApiResponse<Map<String, Object>> getDetail(@PathVariable Long paperId) {
        return ApiResponse.success(paperTradingService.getDetail(paperId));
    }

    @PostMapping("/{paperId}/generate-signals")
    @Operation(summary = "生成交易信号")
    public ApiResponse<List<PaperSignal>> generateSignals(@PathVariable Long paperId) {
        return ApiResponse.success("信号生成成功", paperTradingService.generateSignals(paperId));
    }

    @PostMapping("/signals/{signalId}/execute")
    @Operation(summary = "执行信号（买入/卖出）")
    public ApiResponse<PaperPosition> executeSignal(@PathVariable Long signalId) {
        return ApiResponse.success("信号执行成功", paperTradingService.executeSignal(signalId));
    }

    @GetMapping("/{paperId}/signals")
    @Operation(summary = "获取信号列表")
    public ApiResponse<List<PaperSignal>> getSignals(@PathVariable Long paperId) {
        return ApiResponse.success(paperTradingService.getSignals(paperId));
    }

    @PatchMapping("/{paperId}/status")
    @Operation(summary = "更新模拟盘状态（RUNNING/PAUSED/STOPPED）")
    public ApiResponse<PaperTrading> updateStatus(
            @PathVariable Long paperId,
            @RequestParam String status) {
        return ApiResponse.success(paperTradingService.updateStatus(paperId, status));
    }

    @PostMapping("/{paperId}/execute-all-signals")
    @Operation(summary = "批量执行所有待处理信号")
    public ApiResponse<List<PaperPosition>> executeAllSignals(@PathVariable Long paperId) {
        return ApiResponse.success("批量执行完成",
            paperTradingService.executeAllSignals(paperId));
    }

    @PostMapping("/{paperId}/process-dividends")
    @Operation(summary = "处理分红送股（按除权除息日结算）")
    public ApiResponse<Void> processDividends(@PathVariable Long paperId) {
        paperTradingService.processDividends(paperId);
        return ApiResponse.success("分红处理完成", null);
    }

    @DeleteMapping("/{paperId}")
    @Operation(summary = "删除模拟盘（及关联的持仓、信号、净值）")
    public ApiResponse<Void> delete(@PathVariable Long paperId) {
        paperTradingService.deletePaperTrading(paperId);
        return ApiResponse.success("模拟盘已删除", null);
    }

    // ─── 持仓预警 ────────────────────────────────────────────────────

    @GetMapping("/{paperId}/alerts")
    @Operation(summary = "获取持仓预警列表")
    public ApiResponse<List<PositionAlert>> getAlerts(
            @PathVariable Long paperId,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(positionAlertService.getAlerts(paperId, limit));
    }

    @GetMapping("/{paperId}/alerts/unread-count")
    @Operation(summary = "获取未读预警数量")
    public ApiResponse<Long> getUnreadCount(@PathVariable Long paperId) {
        return ApiResponse.success(positionAlertService.getUnreadCount(paperId));
    }

    @PostMapping("/{paperId}/alerts/read-all")
    @Operation(summary = "标记所有预警为已读")
    public ApiResponse<Integer> markAllRead(@PathVariable Long paperId) {
        return ApiResponse.success("已标记全部已读", positionAlertService.markAllRead(paperId));
    }

    @PostMapping("/alerts/{alertId}/read")
    @Operation(summary = "标记单条预警为已读")
    public ApiResponse<Void> markRead(@PathVariable Long alertId) {
        positionAlertService.markRead(alertId);
        return ApiResponse.success("已标记已读", null);
    }

    @DeleteMapping("/alerts/{alertId}")
    @Operation(summary = "删除单条预警")
    public ApiResponse<Void> deleteAlert(@PathVariable Long alertId) {
        positionAlertService.deleteAlert(alertId);
        return ApiResponse.success("预警已删除", null);
    }

    @DeleteMapping("/{paperId}/alerts")
    @Operation(summary = "清空模拟盘所有预警")
    public ApiResponse<Integer> clearAlerts(@PathVariable Long paperId) {
        int count = positionAlertService.clearAlerts(paperId);
        return ApiResponse.success("已清空 " + count + " 条预警", count);
    }

    @PostMapping("/{paperId}/scan-alerts")
    @Operation(summary = "手动触发持仓预警扫描")
    public ApiResponse<Integer> scanAlerts(@PathVariable Long paperId) {
        int count = positionAlertService.scanAlerts(paperId);
        return ApiResponse.success("扫描完成", count);
    }
}
