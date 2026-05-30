package com.quant.platform.backtest.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.backtest.domain.EquityCurve;
import com.quant.platform.backtest.domain.RebalanceRecord;
import com.quant.platform.backtest.domain.RollingScreenTask;
import com.quant.platform.backtest.mapper.EquityCurveMapper;
import com.quant.platform.backtest.service.RollingScreenService;
import com.quant.platform.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 滚动选股回测 API
 * <p>
 * 路由前缀: /rolling-screen
 * <p>
 * 核心流程：前端提交选股配置+回测参数 → 创建任务 → 异步执行 → WebSocket 推进度 → 完成后查看报告
 */
@RestController
@RequestMapping("/rolling-screen")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "滚动选股回测", description = "基于因子选股的滚动回测（方案二）")
public class RollingScreenController {

    private final RollingScreenService rollingScreenService;
    private final EquityCurveMapper equityCurveMapper;
    private final ObjectMapper objectMapper;

    /**
     * 创建并启动滚动选股回测任务。
     * <p>
     * POST /rolling-screen/run
     * Body: RollingScreenTask JSON（含 screenConfigJson + 回测参数）
     *
     * @param task 前端提交的任务配置
     * @return 已入库的任务（含 ID）
     */
    @PostMapping("/run")
    @Operation(summary = "创建并启动滚动选股回测")
    public ApiResponse<RollingScreenTask> run(@RequestBody RollingScreenTask task) {
        return ApiResponse.success("回测任务已提交，正在后台执行", rollingScreenService.createAndRun(task));
    }

    /**
     * 分页查询任务列表。
     * <p>
     * GET /rolling-screen/tasks?status=COMPLETED&page=0&size=20
     *
     * @param status 状态过滤（可选）
     * @param page   页码（0-based）
     * @param size   每页条数
     * @return 分页结果
     */
    @GetMapping("/tasks")
    @Operation(summary = "查询回测任务列表")
    public ApiResponse<IPage<RollingScreenTask>> listTasks(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(rollingScreenService.listTasks(status, page, size));
    }

    /**
     * 获取任务详情。
     * <p>
     * GET /rolling-screen/tasks/{id}
     *
     * @param taskId 任务 ID
     * @return 任务实体（含状态/进度/摘要指标）
     */
    @GetMapping("/tasks/{id}")
    @Operation(summary = "获取任务详情")
    public ApiResponse<RollingScreenTask> getTask(@PathVariable("id") Long taskId) {
        return ApiResponse.success(rollingScreenService.getTask(taskId));
    }

    /**
     * 获取任务的调仓记录列表。
     * <p>
     * GET /rolling-screen/tasks/{id}/records
     *
     * @param taskId 任务 ID
     * @return 调仓记录（含持仓快照、买卖明细、组合快照）
     */
    @GetMapping("/tasks/{id}/records")
    @Operation(summary = "获取调仓记录")
    public ApiResponse<List<RebalanceRecord>> getRecords(@PathVariable("id") Long taskId) {
        return ApiResponse.success(rollingScreenService.getRecords(taskId));
    }

    /**
     * 获取净值曲线数据（用于前端图表渲染）。
     * <p>
     * GET /rolling-screen/tasks/{id}/curve
     *
     * @param taskId 任务 ID
     * @return 包含曲线列表 + 任务摘要指标的 Map
     */
    @GetMapping("/tasks/{id}/curve")
    @Operation(summary = "获取净值曲线数据")
    public ApiResponse<Map<String, Object>> getCurveData(@PathVariable("id") Long taskId) {
        RollingScreenTask task = rollingScreenService.getTask(taskId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", taskId);
        result.put("status", task.getStatus());
        result.put("progress", task.getProgress());
        result.put("finalNav", task.getFinalNav());
        result.put("totalReturn", task.getTotalReturn());
        result.put("annualReturn", task.getAnnualReturn());
        result.put("maxDrawdown", task.getMaxDrawdown());
        result.put("sharpeRatio", task.getSharpeRatio());
        result.put("benchmarkReturn", task.getBenchmarkReturn());
        result.put("totalTrades", task.getTotalTrades());
        result.put("winRate", task.getWinRate());

        // 从 equity_curve 表查曲线数据
        List<EquityCurve> curveList = equityCurveMapper.findByTaskId(taskId);
        result.put("equityCurve", curveList);
        result.put("curvePoints", curveList.size());

        return ApiResponse.success(result);
    }

    /**
     * 取消回测任务。
     * <p>
     * PUT /rolling-screen/tasks/{id}/cancel
     * 支持取消 PENDING 和 RUNNING 状态的任务。
     *
     * @param taskId 任务 ID
     * @return 更新后的任务
     */
    @PutMapping("/tasks/{id}/cancel")
    @Operation(summary = "取消回测任务")
    public ApiResponse<RollingScreenTask> cancel(@PathVariable("id") Long taskId) {
        return ApiResponse.success("任务已取消", rollingScreenService.cancelTask(taskId));
    }

    /**
     * 删除任务及其级联的调仓记录。
     * <p>
     * DELETE /rolling-screen/tasks/{id}
     * 仅已结束状态可删除。
     *
     * @param taskId 任务 ID
     */
    @DeleteMapping("/tasks/{id}")
    @Operation(summary = "删除回测任务")
    public ApiResponse<Void> delete(@PathVariable("id") Long taskId) {
        rollingScreenService.deleteTask(taskId);
        return ApiResponse.ok();
    }

    /**
     * 重跑滚动选股回测任务：清空旧结果并重新执行。
     * <p>
     * POST /rolling-screen/tasks/{id}/rerun
     * 仅 COMPLETED / FAILED / CANCELLED 状态可重跑。
     *
     * @param taskId 任务 ID
     * @return 重置后的任务
     */
    @PostMapping("/tasks/{id}/rerun")
    @Operation(summary = "重跑回测任务（清空旧结果并重新执行）")
    public ApiResponse<RollingScreenTask> rerun(@PathVariable("id") Long taskId) {
        return ApiResponse.success("已重新提交回测任务", rollingScreenService.rerunTask(taskId));
    }
}
