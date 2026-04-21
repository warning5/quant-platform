package com.quant.platform.backtest.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.backtest.domain.BacktestReport;
import com.quant.platform.backtest.domain.BacktestTask;
import com.quant.platform.backtest.domain.ParamOptimizeReport;
import com.quant.platform.backtest.mapper.ParamOptimizeReportMapper;
import com.quant.platform.backtest.service.BacktestService;
import com.quant.platform.backtest.service.BrinsonAttributionService;
import com.quant.platform.backtest.service.CompareService;
import com.quant.platform.backtest.service.MonteCarloService;
import com.quant.platform.backtest.service.ParamOptimizeService;
import com.quant.platform.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.extern.slf4j.Slf4j;
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
public class BacktestController {

    private final BacktestService backtestService;
    private final BrinsonAttributionService brinsonAttributionService;
    private final CompareService compareService;
    private final MonteCarloService monteCarloService;
    private final ParamOptimizeService paramOptimizeService;
    private final ParamOptimizeReportMapper paramOptimizeReportMapper;
    private final ObjectMapper objectMapper;

    @PostMapping
    @Operation(summary = "创建并启动回测任务")
    public ApiResponse<BacktestTask> create(@RequestBody BacktestTask task) {
        return ApiResponse.success("回测任务已提交", backtestService.createAndRun(task));
    }

    @GetMapping
    @Operation(summary = "查询回测任务列表")
    public ApiResponse<IPage<BacktestTask>> list(
            @RequestParam(required = false) String strategyCode,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(backtestService.listTasks(strategyCode, status, page, size));
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

    @GetMapping("/reports/{reportId}")
    @Operation(summary = "获取报告详情（按reportId）")
    public ApiResponse<BacktestReport> getReportById(@PathVariable Long reportId) {
        return ApiResponse.success(backtestService.getReportById(reportId));
    }

    @PostMapping("/{taskId}/cancel")
    @Operation(summary = "取消回测任务")
    public ApiResponse<BacktestTask> cancel(@PathVariable Long taskId) {
        return ApiResponse.success("任务已取消", backtestService.cancelTask(taskId));
    }

    @DeleteMapping("/{taskId}")
    @Operation(summary = "删除回测任务")
    public ApiResponse<Void> delete(@PathVariable Long taskId) {
        backtestService.deleteTask(taskId);
        return ApiResponse.ok();
    }

    /**
     * 获取回测实时/历史净值曲线数据（用于前端执行中页面展示）
     * 如果报告已生成，返回完整数据；否则返回空数组
     */
    @GetMapping("/{taskId}/curve")
    @Operation(summary = "获取回测净值曲线数据")
    @SneakyThrows
    public ApiResponse<Map<String, Object>> getCurveData(@PathVariable Long taskId) {
        BacktestTask task = backtestService.getTask(taskId);
        Map<String, Object> result = new HashMap<>();
        result.put("taskId", taskId);
        result.put("status", task.getStatus().name());
        result.put("progress", task.getProgress());

        // 如果报告已生成，返回完整曲线数据
        if (task.getStatus() == BacktestTask.BacktestStatus.COMPLETED) {
            try {
                BacktestReport report = backtestService.getReport(taskId);
                List<Map<String, Object>> stratCurve = parseJsonList(report.getEquityCurveJson());
                List<Map<String, Object>> bmCurve = parseJsonList(report.getBenchmarkCurveJson());
                result.put("stratCurve", stratCurve);
                result.put("bmCurve", bmCurve);
            } catch (Exception e) {
                // 报告可能还没生成，忽略错误
            }
        }
        return ApiResponse.success(result);
    }

    /**
     * Brinson 归因分析
     * 将组合超额收益分解为配置效应、选股效应、交互效应
     */
    @GetMapping("/{taskId}/attribution")
    @Operation(summary = "Brinson归因分析")
    public ApiResponse<Map<String, Object>> getAttribution(@PathVariable Long taskId) {
        BacktestTask task = backtestService.getTask(taskId);
        BacktestReport report = backtestService.getReport(taskId);
        Map<String, Object> result = brinsonAttributionService.computeBrinson(
                taskId,
                report.getPositionHistoryJson(),
                report.getEquityCurveJson(),
                report.getBenchmarkCurveJson(),
                task.getBenchmarkCode()
        );
        return ApiResponse.success(result);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseJsonList(String json) throws Exception {
        if (json == null || json.isEmpty()) return Collections.emptyList();
        return objectMapper.readValue(json, List.class);
    }

    // ═══════════════════════ P1 功能 API ═══════════════════════════════════

    /**
     * 多策略对比 —— 批量查询已完成回测的指标和净值曲线
     * POST /backtests/compare
     * Body: { "taskIds": [1, 2, 3] }
     */
    @PostMapping("/compare")
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
    @PostMapping("/param-optimize/submit")
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
        ParamOptimizeService.OptimizeJob job = paramOptimizeService.getJob(jobId);
        if (job == null) {
            return ApiResponse.error("优化任务不存在: " + jobId);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobId", job.jobId);
        result.put("status", job.status);
        result.put("total", job.total);
        result.put("done", job.done.get());
        result.put("progress", job.total > 0 ? (int) (100.0 * job.done.get() / job.total) : 0);
        // 从DB恢复的记录包含 paramGrid 等信息
        ParamOptimizeReport report = paramOptimizeService.getOptimizeReport(jobId);
        if (report != null) {
            result.put("paramGrid", report.getParamGridJson());
            result.put("startDate", report.getStartDate());
            result.put("endDate", report.getEndDate());
            result.put("objective", report.getObjective());
        }
        // 运行中（COMPLETED/RUNNING/PENDING）都返回已完成的 results，前端实时展示最新结果
        if ("COMPLETED".equals(job.status) || "RUNNING".equals(job.status)) {
            result.put("results", new ArrayList<>(job.results));
            result.put("bestResult", job.bestResult);
            // DEBUG: 打印 bestResult 的所有参数字段
            if (job.bestResult != null) {
                log.info("[ParamOptimize] bestResult fields: {}", job.bestResult.keySet());
                log.info("[ParamOptimize] bestResult maxPositionCount={}, stopLossPct={}, stopProfitPct={}",
                        job.bestResult.get("maxPositionCount"),
                        job.bestResult.get("stopLossPct"),
                        job.bestResult.get("stopProfitPct"));
            } else {
                log.warn("[ParamOptimize] bestResult is null for jobId={}, status={}, results.size={}, done={}, total={}",
                        jobId, job.status, job.results.size(), job.done.get(), job.total);
            }
            // 运行中任务 endMs=0，使用当前时间计算已耗时
            long elapsed = job.endMs > 0 ? job.endMs - job.startMs
                                         : System.currentTimeMillis() - job.startMs;
            result.put("elapsedMs", elapsed);
        }
        if (job.errorMessage != null) result.put("errorMessage", job.errorMessage);
        return ApiResponse.success(result);
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
        List<ParamOptimizeReport> reports;
        if (strategyId != null) {
            reports = paramOptimizeReportMapper.findByStrategyId(strategyId);
        } else {
            reports = paramOptimizeReportMapper.findRecent(50);
        }
        List<Map<String, Object>> data = reports.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("jobId", r.getJobId());
            m.put("strategyId", r.getStrategyId());
            m.put("strategyCode", r.getStrategyCode());
            m.put("taskName", r.getTaskName());
            m.put("startDate", r.getStartDate());
            m.put("endDate", r.getEndDate());
            m.put("objective", r.getObjective());
            m.put("paramGrid", r.getParamGridJson());
            m.put("status", r.getStatus());
            m.put("total", r.getTotal());
            m.put("done", r.getDone());
            m.put("progress", r.getProgress());
            m.put("bestScore", r.getBestScore());
            m.put("bestAnnualReturn", r.getBestAnnualReturn());
            m.put("bestMaxDrawdown", r.getBestMaxDrawdown());
            m.put("elapsedMs", r.getElapsedMs());
            m.put("errorMessage", r.getErrorMessage());
            m.put("createdAt", r.getCreatedAt());
            return m;
        }).toList();
        return ApiResponse.success(data);
    }

    /**
     * 删除优化任务
     * DELETE /backtests/param-optimize/{jobId}
     */
    @DeleteMapping("/param-optimize/{jobId}")
    @Operation(summary = "删除优化任务")
    public ApiResponse<Void> deleteParamOptimizeJob(@PathVariable String jobId) {
        paramOptimizeReportMapper.deleteByJobId(jobId);
        return ApiResponse.success(null);
    }
}
