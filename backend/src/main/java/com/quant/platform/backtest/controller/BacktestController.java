package com.quant.platform.backtest.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.backtest.domain.BacktestReport;
import com.quant.platform.backtest.domain.BacktestTask;
import com.quant.platform.backtest.domain.EquityCurve;
import com.quant.platform.backtest.domain.ParamOptimizeReport;
import com.quant.platform.backtest.domain.RebalanceRecord;
import com.quant.platform.backtest.mapper.EquityCurveMapper;
import com.quant.platform.backtest.mapper.ParamOptimizeReportMapper;
import com.quant.platform.backtest.mapper.RebalanceRecordMapper;
import com.quant.platform.backtest.service.*;
import com.quant.platform.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
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
    private final FactorStyleAttributionService factorStyleAttributionService;
    private final CompareService compareService;
    private final MonteCarloService monteCarloService;
    private final ParamOptimizeService paramOptimizeService;
    private final ParamOptimizeReportMapper paramOptimizeReportMapper;
    private final TradeAnalysisService tradeAnalysisService;
    private final EquityCurveMapper equityCurveMapper;
    private final RebalanceRecordMapper rebalanceRecordMapper;
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
            @RequestParam(required = false) String signalSource,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
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

    @PostMapping("/{taskId}/rerun")
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
     * 如果报告已生成，返回完整数据；否则返回空数组
     */
    @GetMapping("/{taskId}/curve")
    @Operation(summary = "获取回测净值曲线数据")
    @SneakyThrows
    public ApiResponse<Map<String, Object>> getCurveData(@PathVariable Long taskId) {
        BacktestTask task = backtestService.getTask(taskId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", taskId);
        result.put("status", task.getStatus().name());
        result.put("progress", task.getProgress());

        boolean isScreen = "SCREEN".equalsIgnoreCase(task.getSignalSource());

        if (task.getStatus() == BacktestTask.BacktestStatus.COMPLETED) {
            if (isScreen) {
                // SCREEN 模式：从 equity_curve 表读取
                List<EquityCurve> curves = backtestService.getEquityCurves(taskId);
                List<Map<String, Object>> equityCurve = curves.stream().map(ec -> {
                    Map<String, Object> pt = new LinkedHashMap<>();
                    pt.put("tradeDate", ec.getTradeDate().toString());
                    pt.put("nav", ec.getNav());
                    return pt;
                }).toList();
                result.put("equityCurve", equityCurve);
            } else {
                // STRATEGY 模式：从报告 JSON 读取
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
        BacktestTask task = getAnyTask(taskId);
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
    @Operation(summary = "因子风格归因分析")
    public ApiResponse<Map<String, Object>> getFactorAttribution(@PathVariable Long taskId) {
        BacktestTask task = getAnyTask(taskId);
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
     * 策略特征检测 + 归因方案推荐
     * <p>
     * 新逻辑：Brinson 和因子归因都跑一遍，选解释力(explanationRatio)高的作为推荐。
     * 如果两者解释力都低 (<0.15)，返回 "UNCLEAR" 并在 reason 中说明。
     * 特征检测数据（换手率/持仓天数/行业集中度）保留供前端展示参考。
     */
    @GetMapping("/{taskId}/attribution-strategy")
    @Operation(summary = "归因方案推荐（比较两种归因模型后推荐）")
    public ApiResponse<Map<String, Object>> getAttributionStrategy(@PathVariable Long taskId) {
        BacktestTask task = getAnyTask(taskId);
        BacktestReport report = backtestService.getReport(taskId);

        String positionJson = report.getPositionHistoryJson();
        String equityJson    = report.getEquityCurveJson();
        String benchJson     = report.getBenchmarkCurveJson();

        // 1. 特征检测（保留，供前端参考）
        FactorStyleAttributionService.StrategyCharacteristics chars
                = factorStyleAttributionService.detectCharacteristics(task, positionJson);

        // 2. 尝试 Brinson 归因
        Map<String, Object> brinsonResult = null;
        double brinsonExplanatioRatio = -1;
        try {
            brinsonResult = brinsonAttributionService.computeBrinson(
                    task, positionJson, equityJson, benchJson);
            Object er = brinsonResult.get("explanationRatio");
            if (er instanceof Number) brinsonExplanatioRatio = ((Number) er).doubleValue();
        } catch (Exception e) {
            log.warn("[AttributionStrategy] Brinson归因失败: {}", e.getMessage());
        }

        // 3. 尝试因子风格归因
        Map<String, Object> factorResult = null;
        double factorExplanationRatio = -1;
        try {
            factorResult = factorStyleAttributionService.compute(
                    task, positionJson, equityJson, benchJson);
            Object er = factorResult.get("summary") instanceof Map
                    ? ((Map<String, Object>) factorResult.get("summary")).get("explanationRatio")
                    : null;
            if (er instanceof Number) factorExplanationRatio = ((Number) er).doubleValue();
        } catch (Exception e) {
            log.warn("[AttributionStrategy] 因子归因失败: {}", e.getMessage());
        }

        // 4. 比较解释力，选高的
        String recommendedModel;
        String reason;
        Map<String, Object> bestResult;

        boolean brinsonOk  = brinsonExplanatioRatio >= 0.15;
        boolean factorOk    = factorExplanationRatio >= 0.15;

        if (!brinsonOk && !factorOk) {
            recommendedModel = "UNCLEAR";
            reason = String.format(
                    "Brinson解释力=%.1f%%, 因子归因解释力=%.1f%%, 两种模型均适用性不足。" +
                    "策略收益可能来自：个股alpha、择时能力、或交易成本侵蚀。建议检查策略逻辑。",
                    brinsonExplanatioRatio * 100, factorExplanationRatio * 100);
            bestResult = null;
        } else if (brinsonOk && !factorOk) {
            recommendedModel = "BRINSON";
            reason = String.format("Brinson解释力=%.1f%%, 因子归因解释力=%.1f%% — 行业配置是主要收益来源",
                    brinsonExplanatioRatio * 100, factorExplanationRatio * 100);
            bestResult = brinsonResult;
        } else if (!brinsonOk) {
            recommendedModel = "FACTOR";
            reason = String.format("因子归因解释力=%.1f%%, Brinson解释力=%.1f%% — 风格因子暴露是主要收益来源",
                    factorExplanationRatio * 100, brinsonExplanatioRatio * 100);
            bestResult = factorResult;
        } else {
            // 两个都 ok，选解释力高的
            if (factorExplanationRatio >= brinsonExplanatioRatio) {
                recommendedModel = "FACTOR";
                reason = String.format("因子归因解释力=%.1f%% ≥ Brinson解释力=%.1f%% — 推荐因子风格归因",
                        factorExplanationRatio * 100, brinsonExplanatioRatio * 100);
                bestResult = factorResult;
            } else {
                recommendedModel = "BRINSON";
                reason = String.format("Brinson解释力=%.1f%% ≥ 因子归因解释力=%.1f%% — 推荐Brinson行业归因",
                        brinsonExplanatioRatio * 100, factorExplanationRatio * 100);
                bestResult = brinsonResult;
            }
        }

        // 5. 组装返回
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", taskId);
        result.put("recommendedModel", recommendedModel);
        result.put("reason", reason);
        result.put("availableModels", List.of("BRINSON", "FACTOR"));

        // 特征数据（供前端参考）
        result.put("avgDailyTurnover",   chars.avgDailyTurnover());
        result.put("avgHoldingDays",     chars.avgHoldingDays());
        result.put("industryConcentration", chars.industryConcentration());

        // 两个模型的解释力（前端可展示对比）
        Map<String, Object> modelComparison = new LinkedHashMap<>();
        Map<String, Object> bCompariso = new LinkedHashMap<>();
        bCompariso.put("explanationRatio", round2(brinsonExplanatioRatio));
        bCompariso.put("available", brinsonResult != null);
        if (brinsonResult != null) {
            bCompariso.put("totalExcessReturn",
                    brinsonResult.getOrDefault("totalExcessReturn", 0));
        }
        modelComparison.put("BRINSON", bCompariso);

        Map<String, Object> fCompariso = new LinkedHashMap<>();
        fCompariso.put("explanationRatio", round2(factorExplanationRatio));
        fCompariso.put("available", factorResult != null);
        if (factorResult != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> summary = (Map<String, Object>) factorResult.get("summary");
            fCompariso.put("totalExcessReturn",
                    summary != null ? summary.getOrDefault("totalExcessReturn", 0) : 0);
        }
        modelComparison.put("FACTOR", fCompariso);
        result.put("modelComparison", modelComparison);

        // 如果推荐明确，附上归因结果供前端直接展示
        if (bestResult != null && !"UNCLEAR".equals(recommendedModel)) {
            result.put("attributionResult", bestResult);
        }

        log.info("[AttributionStrategy] taskId={}, recommended={}, Brinson ER={}, Factor ER={}",
                taskId, recommendedModel,
                round2(brinsonExplanatioRatio), round2(factorExplanationRatio));
        return ApiResponse.success(result);
    }

    private static double round2(double v) {
        return Math.round(v * 10000.0) / 10000.0;
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

    /**
     * 获取任务（统一从 backtest_task 表查询）。
     */
    private BacktestTask getAnyTask(Long taskId) {
        return backtestService.getTask(taskId);
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
