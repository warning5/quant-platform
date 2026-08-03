package com.quant.platform.backtest.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.quant.platform.common.ratelimit.RateLimit;
import com.quant.platform.backtest.domain.BacktestReport;
import com.quant.platform.backtest.domain.BacktestTask;
import com.quant.platform.backtest.domain.RebalanceRecord;
import com.quant.platform.backtest.dto.BacktestRecommendedConfig;
import com.quant.platform.backtest.service.*;
import com.quant.platform.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 回测管理API
 */
@RestController
@RequestMapping("/backtests")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "回测管理", description = "策略回测任务和报告管理接口")
@cn.dev33.satoken.annotation.SaCheckPermission("strategy:view")
public class BacktestController {

    private final BacktestService backtestService;
    private final BrinsonAttributionService brinsonAttributionService;
    private final FactorStyleAttributionService factorStyleAttributionService;
    private final CompareService compareService;
    private final MonteCarloService monteCarloService;
    private final ParamOptimizeService paramOptimizeService;
    private final TradeAnalysisService tradeAnalysisService;
    private final BacktestReportService backtestReportService;

    @cn.dev33.satoken.annotation.SaCheckPermission(value = {"strategy:view", "strategy:edit"}, mode = cn.dev33.satoken.annotation.SaMode.AND)
    @PostMapping
    @RateLimit(capacity = 5, duration = 1)
    @Operation(summary = "创建并启动回测任务")
    public ApiResponse<BacktestTask> create(@Valid @RequestBody BacktestTask task) {
        return ApiResponse.success("回测任务已提交", backtestService.createAndRun(task));
    }

    @GetMapping
    @Operation(summary = "查询回测任务列表")
    public ApiResponse<IPage<BacktestTask>> list(
            @RequestParam(required = false) String strategyCode,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String signalSource,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        size = Math.min(size, 100);
        return ApiResponse.success(backtestService.listTasks(strategyCode, status, signalSource, page, size));
    }

    @GetMapping("/{taskId}")
    @Operation(summary = "获取回测任务详情")
    public ApiResponse<BacktestTask> getTask(@PathVariable Long taskId) {
        return ApiResponse.success(backtestService.getTask(taskId));
    }

    @GetMapping("/{taskId}/report")
    @Operation(summary = "获取回测报告")
    public ApiResponse<BacktestReport> getReport(@PathVariable Long taskId) {
        return ApiResponse.success(backtestService.getReport(taskId));
    }

    @GetMapping("/{taskId}/recommended-config")
    @Operation(summary = "获取回测推荐的模拟盘参数（根据回测表现自适应计算）")
    public ApiResponse<BacktestRecommendedConfig> getRecommendedConfig(@PathVariable Long taskId) {
        return ApiResponse.success(backtestService.calculateRecommendedConfig(taskId));
    }

    @GetMapping("/reports/{reportId}")
    @Operation(summary = "获取报告详情（按reportId）")
    public ApiResponse<BacktestReport> getReportById(@PathVariable Long reportId) {
        return ApiResponse.success(backtestService.getReportById(reportId));
    }

    @cn.dev33.satoken.annotation.SaCheckPermission(value = {"strategy:view", "strategy:edit"}, mode = cn.dev33.satoken.annotation.SaMode.AND)
    @PostMapping("/{taskId}/cancel")
    @Operation(summary = "取消回测任务")
    public ApiResponse<BacktestTask> cancel(@PathVariable Long taskId) {
        return ApiResponse.success("任务已取消", backtestService.cancelTask(taskId));
    }

    @cn.dev33.satoken.annotation.SaCheckPermission(value = {"strategy:view", "strategy:delete"}, mode = cn.dev33.satoken.annotation.SaMode.AND)
    @DeleteMapping("/{taskId}")
    @Operation(summary = "删除回测任务")
    public ApiResponse<Void> delete(@PathVariable Long taskId) {
        backtestService.deleteTask(taskId);
        return ApiResponse.ok();
    }

    @cn.dev33.satoken.annotation.SaCheckPermission(value = {"strategy:view", "strategy:edit"}, mode = cn.dev33.satoken.annotation.SaMode.AND)
    @PostMapping("/{taskId}/rerun")
    @RateLimit(capacity = 5, duration = 1)
    @Operation(summary = "重跑回测任务（清空旧结果并重新执行）")
    public ApiResponse<BacktestTask> rerun(@PathVariable Long taskId) {
        return ApiResponse.success("已重新提交回测任务", backtestService.rerunTask(taskId));
    }

    /**
     * 获取调仓记录（从 rebalance_record 表，SCREEN 模式使用）
     */
    @GetMapping("/{taskId}/records")
    @Operation(summary = "获取回测调仓记录")
    public ApiResponse<List<RebalanceRecord>> getRecords(@PathVariable Long taskId) {
        return ApiResponse.success(backtestService.getRebalanceRecords(taskId));
    }

    /**
     * 获取回测实时/历史净值曲线数据（用于前端执行中页面展示）
     */
    @GetMapping("/{taskId}/curve")
    @Operation(summary = "获取回测净值曲线数据")
    public ApiResponse<Map<String, Object>> getCurveData(@PathVariable Long taskId) {
        return ApiResponse.success(backtestReportService.getCurveData(taskId));
    }

    /**
     * Brinson 归因分析
     * 将组合超额收益分解为配置效应、选股效应、交互效应
     */
    @GetMapping("/{taskId}/attribution")
    @RateLimit(capacity = 20, duration = 1)
    @Operation(summary = "Brinson归因分析")
    public ApiResponse<Map<String, Object>> getAttribution(@PathVariable Long taskId) {
        BacktestTask task = backtestService.getTask(taskId);
        BacktestReport report = backtestService.getReport(taskId);
        Map<String, Object> result = brinsonAttributionService.computeBrinson(
                task,
                report.getPositionHistoryJson(),
                report.getEquityCurveJson(),
                report.getBenchmarkCurveJson()
        );
        return ApiResponse.success(result);
    }

    /**
     * 因子风格归因分析
     * 将策略超额收益对动量/波动率/市值/换手率因子做多元回归
     */
    @GetMapping("/{taskId}/factor-attribution")
    @RateLimit(capacity = 20, duration = 1)
    @Operation(summary = "因子风格归因分析")
    public ApiResponse<Map<String, Object>> getFactorAttribution(@PathVariable Long taskId) {
        BacktestTask task = backtestService.getTask(taskId);
        BacktestReport report = backtestService.getReport(taskId);
        Map<String, Object> result = factorStyleAttributionService.compute(
                task,
                report.getPositionHistoryJson(),
                report.getEquityCurveJson(),
                report.getBenchmarkCurveJson()
        );
        return ApiResponse.success(result);
    }

    /**
     * FF3 三因子归因分析 (A4+A5)
     * 用标准 Fama-French 三因子（MKT/SMB/HML）回归组合超额收益，输出风格暴露报告。
     */
    @GetMapping("/{taskId}/factor-attribution/ff3")
    @RateLimit(capacity = 20, duration = 1)
    @Operation(summary = "FF3 三因子风格归因")
    public ApiResponse<Map<String, Object>> getFF3Attribution(@PathVariable Long taskId) {
        BacktestTask task = backtestService.getTask(taskId);
        BacktestReport report = backtestService.getReport(taskId);
        Map<String, Object> result = factorStyleAttributionService.computeFF3(
                task,
                report.getPositionHistoryJson(),
                report.getEquityCurveJson(),
                report.getBenchmarkCurveJson()
        );
        return ApiResponse.success(result);
    }

    /**
     * Alpha 滚动窗口监控 (M1+M2)
     * 60/120/252天滚动 Alpha 序列 + 衰减预警。
     */
    @GetMapping("/{taskId}/monitor/alpha-rolling")
    @RateLimit(capacity = 20, duration = 1)
    @Operation(summary = "Alpha 滚动窗口监控")
    public ApiResponse<FactorStyleAttributionService.AlphaMonitorResult> getAlphaRolling(
            @PathVariable Long taskId) {
        BacktestTask task = backtestService.getTask(taskId);
        BacktestReport report = backtestService.getReport(taskId);
        FactorStyleAttributionService.AlphaMonitorResult result
                = factorStyleAttributionService.computeRollingAlpha(
                task, report.getEquityCurveJson(), report.getBenchmarkCurveJson());
        return ApiResponse.success(result);
    }

    /**
     * FF3 风格β滚动监控 (M3+M4)
     * 60/120/252天滚动 SMB/HML beta 序列 + 漂移预警。
     */
    @GetMapping("/{taskId}/monitor/style-rolling")
    @RateLimit(capacity = 20, duration = 1)
    @Operation(summary = "FF3 风格β滚动监控")
    public ApiResponse<FactorStyleAttributionService.StyleMonitorResult> getStyleRolling(
            @PathVariable Long taskId) {
        BacktestTask task = backtestService.getTask(taskId);
        BacktestReport report = backtestService.getReport(taskId);
        FactorStyleAttributionService.StyleMonitorResult result
                = factorStyleAttributionService.computeRollingStyleBeta(
                task, report.getEquityCurveJson(), report.getBenchmarkCurveJson());
        return ApiResponse.success(result);
    }

    /**
     * 策略特征检测 + 归因方案推荐
     */
    @GetMapping("/{taskId}/attribution-strategy")
    @RateLimit(capacity = 10, duration = 1)
    @Operation(summary = "归因方案推荐（比较两种归因模型后推荐）")
    public ApiResponse<Map<String, Object>> getAttributionStrategy(@PathVariable Long taskId) {
        return ApiResponse.success(backtestReportService.getAttributionStrategy(taskId));
    }

    /**
     * 交易级分析（含 P1 持仓周期分析 + P2 关键交易分析）
     */
    @GetMapping("/{taskId}/trade-analysis")
    @Operation(summary = "交易级分析（持仓周期 + 关键交易）")
    public ApiResponse<Map<String, Object>> getTradeAnalysis(@PathVariable Long taskId) {
        BacktestReport report = backtestService.getReport(taskId);
        Map<String, Object> analysis = tradeAnalysisService.analyze(report.getTradeLogJson());
        return ApiResponse.success(analysis);
    }

    // ═══════════════════════ P1 功能 API ═══════════════════════════════════

    /**
     * 多策略对比 —— 批量查询已完成回测的指标和净值曲线
     * POST /backtests/compare
     * Body: { "taskIds": [1, 2, 3] }
     */
    @cn.dev33.satoken.annotation.SaCheckPermission(value = {"strategy:view", "strategy:edit"}, mode = cn.dev33.satoken.annotation.SaMode.AND)
    @PostMapping("/compare")
    @RateLimit(capacity = 10, duration = 1)
    @Operation(summary = "多策略对比（P1）")
    public ApiResponse<Map<String, Object>> compare(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Integer> ids = (List<Integer>) body.get("taskIds");
        if (ids == null || ids.size() < 2) {
            return ApiResponse.error("请至少选择2个回测任务进行对比");
        }
        List<Long> taskIds = ids.stream().map(Long::valueOf).toList();
        return ApiResponse.success(compareService.compare(taskIds));
    }

    /**
     * 蒙特卡洛模拟 —— 基于历史日收益率 Bootstrap
     * GET /backtests/{taskId}/montecarlo?simulations=500&horizonDays=252
     */
    @GetMapping("/{taskId}/montecarlo")
    @RateLimit(capacity = 5, duration = 1)
    @Operation(summary = "蒙特卡洛模拟（P1）")
    public ApiResponse<Map<String, Object>> monteCarlo(
            @PathVariable Long taskId,
            @RequestParam(defaultValue = "500") int simulations,
            @RequestParam(defaultValue = "252") int horizonDays) {
        return ApiResponse.success(monteCarloService.simulate(taskId, simulations, horizonDays));
    }

    /**
     * 提交参数优化任务（异步网格搜索）
     * POST /backtests/param-optimize/submit
     */
    @cn.dev33.satoken.annotation.SaCheckPermission(value = {"strategy:view", "strategy:edit"}, mode = cn.dev33.satoken.annotation.SaMode.AND)
    @PostMapping("/param-optimize/submit")
    @RateLimit(capacity = 3, duration = 1)
    @Operation(summary = "提交参数优化任务（P1）")
    public ApiResponse<Map<String, Object>> submitParamOptimize(
            @RequestBody ParamOptimizeService.OptimizeRequest req) {
        log.info("[BacktestController] submitParamOptimize() called");
        String jobId = paramOptimizeService.submit(req);
        log.info("[BacktestController] submitParamOptimize() got jobId={}, returning response", jobId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobId", jobId);
        result.put("message", "参数优化任务已提交，请轮询 /param-optimize/{jobId} 获取进度");
        return ApiResponse.success("参数优化已启动", result);
    }

    /**
     * 查询参数优化任务状态和结果
     * GET /backtests/param-optimize/{jobId}
     */
    @GetMapping("/param-optimize/{jobId}")
    @Operation(summary = "查询参数优化进度/结果（P1）")
    public ApiResponse<Map<String, Object>> getParamOptimizeResult(@PathVariable String jobId) {
        return backtestReportService.getParamOptimizeResult(jobId);
    }

    /**
     * 查询运行中的优化任务
     * GET /backtests/param-optimize/running
     */
    @GetMapping("/param-optimize/running")
    @Operation(summary = "查询运行中的优化任务")
    public ApiResponse<List<Map<String, Object>>> getRunningOptimizeJobs() {
        List<ParamOptimizeService.OptimizeJob> running = paramOptimizeService.findRunningJobs();
        List<Map<String, Object>> data = running.stream().map(job -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("jobId", job.jobId);
            m.put("status", job.status);
            m.put("total", job.total);
            m.put("done", job.done.get());
            m.put("progress", job.total > 0 ? (int) (100.0 * job.done.get() / job.total) : 0);
            return m;
        }).toList();
        return ApiResponse.success(data);
    }

    /**
     * 查询优化任务列表（历史记录）
     * GET /backtests/param-optimize/list?strategyId=xxx
     */
    @GetMapping("/param-optimize/list")
    @Operation(summary = "查询优化任务列表（历史记录）")
    public ApiResponse<List<Map<String, Object>>> listParamOptimizeJobs(
            @RequestParam(required = false) Long strategyId) {
        return ApiResponse.success(backtestReportService.listParamOptimizeJobs(strategyId));
    }

    /**
     * 删除优化任务
     * DELETE /backtests/param-optimize/{jobId}
     */
    @cn.dev33.satoken.annotation.SaCheckPermission(value = {"strategy:view", "strategy:delete"}, mode = cn.dev33.satoken.annotation.SaMode.AND)
    @DeleteMapping("/param-optimize/{jobId}")
    @Operation(summary = "删除优化任务")
    public ApiResponse<Void> deleteParamOptimizeJob(@PathVariable String jobId) {
        backtestReportService.deleteParamOptimizeJob(jobId);
        return ApiResponse.success(null);
    }

    // ═══════════════════════ P2-3 Walk-Forward 验证 ═══════════════════════════════════

    /**
     * Walk-Forward 滚动窗口验证
     * POST /backtests/walk-forward
     */
    @cn.dev33.satoken.annotation.SaCheckPermission(value = {"strategy:view", "strategy:edit"}, mode = cn.dev33.satoken.annotation.SaMode.AND)
    @PostMapping("/walk-forward")
    @RateLimit(capacity = 3, duration = 1)
    @Operation(summary = "Walk-Forward 滚动窗口验证")
    public ApiResponse<Map<String, Object>> runWalkForward(@RequestBody Map<String, Object> params) {
        return backtestReportService.runWalkForward(params);
    }
}
