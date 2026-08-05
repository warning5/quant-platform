package com.quant.platform.strategy.paper;

import com.quant.platform.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
@cn.dev33.satoken.annotation.SaCheckPermission("strategy:view")
public class PaperTradingController {

    private final PaperTradingService paperTradingService;
    private final PositionAlertService positionAlertService;
    private final ExecutionQualityService executionQualityService;
    private final PaperRebalanceService paperRebalanceService;

    @cn.dev33.satoken.annotation.SaCheckPermission(value = {"strategy:view", "strategy:edit"}, mode = cn.dev33.satoken.annotation.SaMode.AND)
    @PostMapping("/create")
    @Operation(summary = "创建模拟盘（支持单策略或多策略组合，可选从回测导入参数）")
    public ApiResponse<PaperTrading> create(
            @RequestParam(required = false) Long strategyId,
            @RequestParam(required = false) String strategyCode,
            @RequestParam(defaultValue = "1000000") BigDecimal initialCapital,
            @RequestParam(required = false) String strategyConfigJson,
            @Parameter(description = "回测任务ID（可选，传入后自动从回测报告导入推荐风控参数）") @RequestParam(required = false) Long backtestId) {
        return ApiResponse.success("模拟盘创建成功",
            paperTradingService.createPaperTrading(strategyId, strategyCode, initialCapital, strategyConfigJson, backtestId));
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

    @cn.dev33.satoken.annotation.SaCheckPermission(value = {"strategy:view", "strategy:edit"}, mode = cn.dev33.satoken.annotation.SaMode.AND)
    @PostMapping("/{paperId}/generate-signals")
    @Operation(summary = "生成交易信号")
    public ApiResponse<List<PaperSignal>> generateSignals(@PathVariable Long paperId) {
        return ApiResponse.success("信号生成成功", paperTradingService.generateSignals(paperId));
    }

    /** Fix #2：一键买入（从推荐页快速建仓） */
    @cn.dev33.satoken.annotation.SaCheckPermission(value = {"strategy:view", "strategy:edit"}, mode = cn.dev33.satoken.annotation.SaMode.AND)
    @PostMapping("/{paperId}/quick-buy")
    @Operation(summary = "一键买入（推荐页→模拟盘）")
    public ApiResponse<PaperPosition> quickBuy(
            @PathVariable Long paperId,
            @Parameter(description = "股票代码") @RequestParam String code,
            @Parameter(description = "股票名称（可选）") @RequestParam(required = false) String name,
            @Parameter(description = "建议买入价（可选，不传则自动获取）") @RequestParam(required = false) BigDecimal price) {
        return ApiResponse.success("买入成功",
                paperTradingService.quickBuy(paperId, code,
                        name, price != null ? price : null));
    }

    @cn.dev33.satoken.annotation.SaCheckPermission(value = {"strategy:view", "strategy:edit"}, mode = cn.dev33.satoken.annotation.SaMode.AND)
    @PostMapping("/signals/{signalId}/execute")
    @Operation(summary = "执行信号（买入/卖出）")
    public ApiResponse<PaperPosition> executeSignal(@PathVariable Long signalId) {
        return ApiResponse.success("信号执行成功", paperTradingService.executeSignal(signalId));
    }

    /** 【缺陷1修复】创建条件单（限价单/止损单/追踪止损单） */
    @cn.dev33.satoken.annotation.SaCheckPermission(value = {"strategy:view", "strategy:edit"}, mode = cn.dev33.satoken.annotation.SaMode.AND)
    @PostMapping("/{paperId}/conditional-order")
    @Operation(summary = "创建条件单（限价/止损/追踪止损）")
    public ApiResponse<PaperSignal> createConditionalOrder(
            @PathVariable Long paperId,
            @Parameter(description = "股票代码") @RequestParam String code,
            @Parameter(description = "信号方向：BUY/SELL") @RequestParam String direction,
            @Parameter(description = "订单类型：LIMIT/STOP/STOP_LIMIT/TRAILING_STOP") @RequestParam String orderType,
            @Parameter(description = "触发价格（限价单/止损单）") @RequestParam(required = false) BigDecimal triggerPrice,
            @Parameter(description = "限价（止损限价单）") @RequestParam(required = false) BigDecimal limitPrice,
            @Parameter(description = "追踪止损回撤比例（小数，如0.05=5%）") @RequestParam(required = false) BigDecimal trailPct,
            @Parameter(description = "追踪止损回撤金额（元）") @RequestParam(required = false) BigDecimal trailAmount,
            @Parameter(description = "信号价格") @RequestParam(required = false) BigDecimal signalPrice,
            @Parameter(description = "信号原因") @RequestParam(required = false) String reason) {
        return ApiResponse.success("条件单创建成功",
            paperTradingService.createConditionalOrder(paperId, code, direction, orderType,
                triggerPrice, limitPrice, trailPct, trailAmount, signalPrice, reason));
    }

    /** 【缺陷1修复】检查并执行所有待触发的条件单 */
    @cn.dev33.satoken.annotation.SaCheckPermission(value = {"strategy:view", "strategy:edit"}, mode = cn.dev33.satoken.annotation.SaMode.AND)
    @PostMapping("/{paperId}/check-conditional-orders")
    @Operation(summary = "检查条件单触发状态并执行")
    public ApiResponse<Integer> checkConditionalOrders(@PathVariable Long paperId) {
        return ApiResponse.success("条件单检查完成",
            paperTradingService.checkAndExecuteConditionalOrders(paperId));
    }

    @GetMapping("/{paperId}/signals")
    @Operation(summary = "获取信号列表")
    public ApiResponse<List<PaperSignal>> getSignals(@PathVariable Long paperId) {
        return ApiResponse.success(paperTradingService.getSignals(paperId));
    }

    @cn.dev33.satoken.annotation.SaCheckPermission(value = {"strategy:view", "strategy:edit"}, mode = cn.dev33.satoken.annotation.SaMode.AND)
    @PatchMapping("/{paperId}/status")
    @Operation(summary = "更新模拟盘状态（RUNNING/PAUSED/STOPPED）")
    public ApiResponse<PaperTrading> updateStatus(
            @PathVariable Long paperId,
            @RequestParam String status) {
        return ApiResponse.success(paperTradingService.updateStatus(paperId, status));
    }

    @cn.dev33.satoken.annotation.SaCheckPermission(value = {"strategy:view", "strategy:edit"}, mode = cn.dev33.satoken.annotation.SaMode.AND)
    @PostMapping("/{paperId}/execute-all-signals")
    @Operation(summary = "批量执行所有待处理信号")
    public ApiResponse<List<PaperPosition>> executeAllSignals(@PathVariable Long paperId) {
        return ApiResponse.success("批量执行完成",
            paperTradingService.executeAllSignals(paperId));
    }

    @cn.dev33.satoken.annotation.SaCheckPermission(value = {"strategy:view", "strategy:edit"}, mode = cn.dev33.satoken.annotation.SaMode.AND)
    @PostMapping("/{paperId}/process-dividends")
    @Operation(summary = "处理分红送股（按除权除息日结算）")
    public ApiResponse<Void> processDividends(@PathVariable Long paperId) {
        paperTradingService.processDividends(paperId);
        return ApiResponse.success("分红处理完成", null);
    }

    @cn.dev33.satoken.annotation.SaCheckPermission(value = {"strategy:view", "strategy:delete"}, mode = cn.dev33.satoken.annotation.SaMode.AND)
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

    @cn.dev33.satoken.annotation.SaCheckPermission(value = {"strategy:view", "strategy:edit"}, mode = cn.dev33.satoken.annotation.SaMode.AND)
    @PostMapping("/{paperId}/alerts/read-all")
    @Operation(summary = "标记所有预警为已读")
    public ApiResponse<Integer> markAllRead(@PathVariable Long paperId) {
        return ApiResponse.success("已标记全部已读", positionAlertService.markAllRead(paperId));
    }

    @cn.dev33.satoken.annotation.SaCheckPermission(value = {"strategy:view", "strategy:edit"}, mode = cn.dev33.satoken.annotation.SaMode.AND)
    @PostMapping("/alerts/{alertId}/read")
    @Operation(summary = "标记单条预警为已读")
    public ApiResponse<Void> markRead(@PathVariable Long alertId) {
        positionAlertService.markRead(alertId);
        return ApiResponse.success("已标记已读", null);
    }

    @cn.dev33.satoken.annotation.SaCheckPermission(value = {"strategy:view", "strategy:delete"}, mode = cn.dev33.satoken.annotation.SaMode.AND)
    @DeleteMapping("/alerts/{alertId}")
    @Operation(summary = "删除单条预警")
    public ApiResponse<Void> deleteAlert(@PathVariable Long alertId) {
        positionAlertService.deleteAlert(alertId);
        return ApiResponse.success("预警已删除", null);
    }

    @cn.dev33.satoken.annotation.SaCheckPermission(value = {"strategy:view", "strategy:delete"}, mode = cn.dev33.satoken.annotation.SaMode.AND)
    @DeleteMapping("/{paperId}/alerts")
    @Operation(summary = "清空模拟盘所有预警")
    public ApiResponse<Integer> clearAlerts(@PathVariable Long paperId) {
        int count = positionAlertService.clearAlerts(paperId);
        return ApiResponse.success("已清空 " + count + " 条预警", count);
    }

    @cn.dev33.satoken.annotation.SaCheckPermission(value = {"strategy:view", "strategy:edit"}, mode = cn.dev33.satoken.annotation.SaMode.AND)
    @PostMapping("/{paperId}/scan-alerts")
    @Operation(summary = "手动触发持仓预警扫描")
    public ApiResponse<Integer> scanAlerts(@PathVariable Long paperId) {
        int count = positionAlertService.scanAlerts(paperId);
        return ApiResponse.success("扫描完成", count);
    }

    // ─── 风控配置 ────────────────────────────────────────────────────

    @GetMapping("/{paperId}/risk-config")
    @Operation(summary = "获取风控配置")
    public ApiResponse<PaperRiskConfig> getRiskConfig(@PathVariable Long paperId) {
        return ApiResponse.success(paperTradingService.getRiskConfig(paperId));
    }

    @cn.dev33.satoken.annotation.SaCheckPermission(value = {"strategy:view", "strategy:edit"}, mode = cn.dev33.satoken.annotation.SaMode.AND)
    @PutMapping("/{paperId}/risk-config")
    @Operation(summary = "更新风控配置")
    public ApiResponse<PaperRiskConfig> updateRiskConfig(
            @PathVariable Long paperId,
            @RequestParam(required = false) BigDecimal stopLossPct,
            @RequestParam(required = false) BigDecimal takeProfitPct,
            @RequestParam(required = false) BigDecimal trailingAtr,
            @RequestParam(required = false) BigDecimal maxPositionPct,
            @RequestParam(required = false) BigDecimal maxIndustryPct,
            @RequestParam(required = false) BigDecimal maxDrawdownPct,
            @RequestParam(required = false) Integer timingEnabled,
            @RequestParam(required = false) String benchmarkCode,
            @RequestParam(required = false) String allocationMode,
            @RequestParam(required = false) BigDecimal slippagePct,
            @RequestParam(required = false) String slippageModel,
            @RequestParam(required = false) BigDecimal cashBufferPct,
            @RequestParam(required = false) String rebalanceFreq,
            @RequestParam(required = false) BigDecimal rebalanceThreshold,
            @RequestParam(required = false) Integer autoBlockEnabled,
            @RequestParam(required = false) Integer twapThreshold) {
        return ApiResponse.success("风控配置已更新",
            paperTradingService.updateRiskConfig(paperId, stopLossPct, takeProfitPct,
                trailingAtr, maxPositionPct, maxIndustryPct, maxDrawdownPct,
                timingEnabled, benchmarkCode, allocationMode,
                slippagePct, slippageModel, cashBufferPct, rebalanceFreq, rebalanceThreshold,
                autoBlockEnabled, twapThreshold));
    }

    @cn.dev33.satoken.annotation.SaCheckPermission(value = {"strategy:view", "strategy:edit"}, mode = cn.dev33.satoken.annotation.SaMode.AND)
    @PostMapping("/{paperId}/cash-flow/deposit")
    @Operation(summary = "追加入金")
    public ApiResponse<PaperCashFlow> deposit(
            @PathVariable Long paperId,
            @RequestParam BigDecimal amount,
            @RequestParam(required = false) String note) {
        return ApiResponse.success("入金成功", paperTradingService.deposit(paperId, amount, note));
    }

    @cn.dev33.satoken.annotation.SaCheckPermission(value = {"strategy:view", "strategy:edit"}, mode = cn.dev33.satoken.annotation.SaMode.AND)
    @PostMapping("/{paperId}/cash-flow/withdraw")
    @Operation(summary = "提取出金")
    public ApiResponse<PaperCashFlow> withdraw(
            @PathVariable Long paperId,
            @RequestParam BigDecimal amount,
            @RequestParam(required = false) String note) {
        return ApiResponse.success("出金成功", paperTradingService.withdraw(paperId, amount, note));
    }

    @GetMapping("/{paperId}/cash-flow")
    @Operation(summary = "查询现金流记录")
    public ApiResponse<List<PaperCashFlow>> getCashFlows(@PathVariable Long paperId) {
        return ApiResponse.success(paperTradingService.getCashFlows(paperId));
    }

    // ─── 执行质量分析 ──────────────────────────────────────────────
    @GetMapping("/{paperId}/execution-quality")
    @Operation(summary = "获取执行质量分析")
    public ApiResponse<Map<String, Object>> getExecutionQuality(@PathVariable Long paperId) {
        return ApiResponse.success(executionQualityService.getQualityReport(paperId));
    }

    // ─── 多策略组合（Route B）────────────────────────────────────────────

    @GetMapping("/combo/{comboId}/detail")
    @Operation(summary = "组合详情：总览/子策略贡献/相关性矩阵/分散化比率")
    public ApiResponse<Map<String, Object>> getComboDetail(@PathVariable Long comboId) {
        return ApiResponse.success(paperTradingService.getComboDetail(comboId));
    }

    @GetMapping("/combo/{comboId}/sub-strategies")
    @Operation(summary = "组合子策略列表（各自P&L/持仓/状态/权重/贡献）")
    public ApiResponse<List<Map<String, Object>>> getComboSubStrategies(@PathVariable Long comboId) {
        return ApiResponse.success(paperTradingService.getComboSubStrategies(comboId));
    }

    @GetMapping("/combo/{comboId}/nav")
    @Operation(summary = "组合与每子账户净值曲线（前端多线对比）")
    public ApiResponse<Map<String, Object>> getComboNav(@PathVariable Long comboId) {
        return ApiResponse.success(paperTradingService.getComboNav(comboId));
    }

    @cn.dev33.satoken.annotation.SaCheckPermission(value = {"strategy:view", "strategy:edit"}, mode = cn.dev33.satoken.annotation.SaMode.AND)
    @PostMapping("/combo/{comboId}/sub/{strategyId}/pause")
    @Operation(summary = "暂停某子策略（资金留在该子账户，不再交易）")
    public ApiResponse<PaperTrading> pauseSubStrategy(
            @PathVariable Long comboId, @PathVariable Long strategyId) {
        return ApiResponse.success("子策略已暂停",
            paperTradingService.pauseSubStrategy(comboId, strategyId));
    }

    @cn.dev33.satoken.annotation.SaCheckPermission(value = {"strategy:view", "strategy:edit"}, mode = cn.dev33.satoken.annotation.SaMode.AND)
    @PostMapping("/combo/{comboId}/sub/{strategyId}/resume")
    @Operation(summary = "恢复某子策略")
    public ApiResponse<PaperTrading> resumeSubStrategy(
            @PathVariable Long comboId, @PathVariable Long strategyId) {
        return ApiResponse.success("子策略已恢复",
            paperTradingService.resumeSubStrategy(comboId, strategyId));
    }

    @cn.dev33.satoken.annotation.SaCheckPermission(value = {"strategy:view", "strategy:edit"}, mode = cn.dev33.satoken.annotation.SaMode.AND)
    @PostMapping("/combo/{comboId}/rebalance")
    @Operation(summary = "手动触发组合再平衡（遍历子账户，强制旋转并记录日志）")
    public ApiResponse<Integer> manualRebalanceCombo(@PathVariable Long comboId) {
        return ApiResponse.success("再平衡完成",
            paperRebalanceService.manualRebalanceCombo(comboId));
    }

    @cn.dev33.satoken.annotation.SaCheckPermission(value = {"strategy:view", "strategy:edit"}, mode = cn.dev33.satoken.annotation.SaMode.AND)
    @PostMapping("/combo/{comboId}/sub/{strategyId}/adjust-weight")
    @Operation(summary = "调整某子策略权重（重归一化组合配置并触发该子账户再平衡）")
    public ApiResponse<Object> adjustSubStrategyWeight(
            @PathVariable Long comboId,
            @PathVariable Long strategyId,
            @RequestParam BigDecimal newWeight) {
        return ApiResponse.success("权重已调整并触发再平衡",
            paperRebalanceService.adjustSubStrategyWeight(comboId, strategyId, newWeight));
    }

    @cn.dev33.satoken.annotation.SaCheckPermission(value = {"strategy:view", "strategy:edit"}, mode = cn.dev33.satoken.annotation.SaMode.AND)
    @PostMapping("/combo/{comboId}/aggregate")
    @Operation(summary = "手动触发组合净值聚合（运维/调试用，重算组合根净值）")
    public ApiResponse<String> aggregateCombo(@PathVariable Long comboId) {
        paperTradingService.aggregateCombo(comboId);
        return ApiResponse.success("组合净值已聚合");
    }

    @GetMapping("/combo/{comboId}/rebalance-log")
    @Operation(summary = "组合再平衡历史（含所有子账户日志）")
    public ApiResponse<List<Map<String, Object>>> getComboRebalanceLog(@PathVariable Long comboId) {
        return ApiResponse.success(paperTradingService.getComboRebalanceLogs(comboId));
    }

    @GetMapping("/combo/{comboId}/signals")
    @Operation(summary = "组合信号流水（聚合所有子账户信号，按日期倒序）")
    public ApiResponse<List<Map<String, Object>>> getComboSignals(@PathVariable Long comboId) {
        return ApiResponse.success(paperTradingService.getComboSignals(comboId));
    }
}
