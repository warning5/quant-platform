package com.quant.platform.dataupdate;

import com.quant.platform.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import com.quant.platform.common.enums.JobStatus;

/**
 * 数据更新管理 API（瘦控制器）
 * 任务编排/覆盖率等逻辑在 DataUpdateService；缺失/研报/交易日统计在 DataCoverageService；
 * 退市生命周期在 DataLifecycleService。本类只负责参数接收、权限校验与响应包装。
 */
@Slf4j
@RestController
@RequestMapping("/data-update")
@RequiredArgsConstructor
@Tag(name = "数据更新", description = "股票数据更新管理接口（调用 Python 脚本采集数据）")
@cn.dev33.satoken.annotation.SaCheckPermission("data:view")
public class DataUpdateController {

    private final DataUpdateService dataUpdateService;
    private final DataCoverageService dataCoverageService;
    private final DataLifecycleService dataLifecycleService;

    @GetMapping("/default-dates")
    @Operation(summary = "获取默认更新日期范围")
    public ApiResponse<Map<String, String>> getDefaultDates() {
        LocalDate today = LocalDate.now();
        int days = dataUpdateService.getDefaultStartDays();
        LocalDate from = today.minusDays(days);
        return ApiResponse.success(Map.of(
                "startDate", from.toString(),
                "endDate", today.toString(),
                "days", String.valueOf(days)
        ));
    }

    @cn.dev33.satoken.annotation.SaCheckPermission(value = {"data:view", "data:edit"}, mode = cn.dev33.satoken.annotation.SaMode.AND)
    @PostMapping("/start")
    @Operation(summary = "启动数据更新任务")
    public ApiResponse<Map<String, Object>> startTask(@RequestBody DataUpdateRequest request) {
        log.info("启动数据更新任务: source={}, market={}, startDate={}, endDate={}, moneyflowSource={}",
                request.getSource(), request.getMarket(), request.getStartDate(), request.getEndDate(), request.getMoneyflowSource());
        DataUpdateTask task = dataUpdateService.submitTask(request);
        return ApiResponse.success("任务已启动", Map.of(
                "taskId", task.getTaskId(),
                "status", task.getStatus()
        ));
    }

    @GetMapping("/status/{taskId}")
    @Operation(summary = "查询任务状态")
    public ApiResponse<DataUpdateTask> getTaskStatus(@PathVariable String taskId) {
        DataUpdateTask task = dataUpdateService.getTaskStatus(taskId);
        if (task == null) {
            return ApiResponse.error("任务不存在: " + taskId);
        }
        return ApiResponse.success(task);
    }

    @GetMapping("/current")
    @Operation(summary = "获取当前正在运行的任务")
    public ApiResponse<DataUpdateTask> getCurrentTask() {
        DataUpdateTask task = dataUpdateService.getCurrentTask();
        if (task == null) {
            DataUpdateTask idle = new DataUpdateTask();
            idle.setTaskId("IDLE");
            idle.setStatus(JobStatus.IDLE);
            idle.setCurrentStep("暂无任务");
            return ApiResponse.success(idle);
        }
        return ApiResponse.success(task);
    }

    @GetMapping("/recent-tasks")
    @Operation(summary = "获取各类型最近的任务（用于页面刷新后恢复状态）")
    public ApiResponse<List<DataUpdateTask>> getRecentTasks() {
        return ApiResponse.success(dataUpdateService.getRecentTasks());
    }

    @GetMapping("/scheduled-running")
    @Operation(summary = "检测 DB 中孤儿 RUNNING 定时任务（页面刷新后恢复状态用）")
    public ApiResponse<List<Map<String, Object>>> getScheduledRunningTasks() {
        return ApiResponse.success(dataUpdateService.getScheduledRunningTasks());
    }

    @cn.dev33.satoken.annotation.SaCheckPermission(value = {"data:view", "data:edit"}, mode = cn.dev33.satoken.annotation.SaMode.AND)
    @PostMapping("/cancel-orphan/{taskKey}")
    @Operation(summary = "清理孤儿 RUNNING 任务的 DB 状态")
    public ApiResponse<Map<String, Object>> cancelOrphanTask(@PathVariable String taskKey) {
        boolean ok = dataUpdateService.cancelOrphanTask(taskKey);
        return ApiResponse.success(ok ? "已清理孤儿任务状态" : "清理失败", Map.of("cleaned", ok));
    }

    @cn.dev33.satoken.annotation.SaCheckPermission(value = {"data:view", "data:edit"}, mode = cn.dev33.satoken.annotation.SaMode.AND)
    @PostMapping("/cancel/{taskId}")
    @Operation(summary = "取消任务")
    public ApiResponse<Map<String, Object>> cancelTask(@PathVariable String taskId) {
        boolean ok = dataUpdateService.cancelTask(taskId);
        return ApiResponse.success(ok ? "任务已取消" : "取消失败", Map.of("cancelled", ok));
    }

    @GetMapping("/logs/{taskId}")
    @Operation(summary = "获取任务历史日志")
    public ApiResponse<List<Map<String, Object>>> getTaskLogs(@PathVariable String taskId) {
        return ApiResponse.success(dataUpdateService.getTaskLogs(taskId));
    }

    @GetMapping("/coverage")
    @Operation(summary = "数据覆盖率概览")
    public ApiResponse<Map<String, Object>> getCoverage() {
        return ApiResponse.success(dataUpdateService.getDataCoverage());
    }

    @GetMapping("/coverage/index")
    @Operation(summary = "指数数据覆盖率")
    public ApiResponse<Map<String, Object>> getIndexCoverage() {
        return ApiResponse.success(dataUpdateService.getIndexCoverage());
    }

    @GetMapping("/missing-indices")
    @Operation(summary = "查询指定日期缺失数据的指数")
    public ApiResponse<List<Map<String, Object>>> getMissingIndices(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.success(dataUpdateService.getMissingIndices(date));
    }

    @GetMapping("/coverage/dividend")
    @Operation(summary = "分红数据覆盖率")
    public ApiResponse<Map<String, Object>> getDividendCoverage() {
        return ApiResponse.success(dataUpdateService.getDividendCoverage());
    }

    @GetMapping("/coverage/bidask")
    @Operation(summary = "内外盘数据覆盖率")
    public ApiResponse<Map<String, Object>> getBidaskCoverage() {
        return ApiResponse.success(dataUpdateService.getBidaskCoverage());
    }

    @GetMapping("/missing-dividend-stats")
    @Operation(summary = "分红数据完整性统计")
    public ApiResponse<Map<String, Object>> getMissingDividendStats() {
        return ApiResponse.success(dataUpdateService.getMissingDividendStats());
    }

    @GetMapping("/missing-dividend-stocks")
    @Operation(summary = "查询缺少分红数据的股票")
    public ApiResponse<List<Map<String, Object>>> getMissingDividendStocks(
            @RequestParam(defaultValue = "ALL") String market,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        return ApiResponse.success(dataUpdateService.getMissingDividendStocks(market, page, pageSize));
    }

    @GetMapping("/missing-stocks")
    @Operation(summary = "查询指定日期缺失数据的股票")
    public ApiResponse<List<Map<String, Object>>> getMissingStocks(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "ALL") String market) {
        return ApiResponse.success(dataCoverageService.getMissingStocks(date, market));
    }

    @GetMapping("/missing-stocks-range")
    @Operation(summary = "股票日线完整性校验(日期跨度) — 按市场分组列出区间内未完整更新的股票")
    public ApiResponse<Map<String, Object>> getMissingStocksRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "ALL") String market) {
        if (startDate.isAfter(endDate)) {
            return ApiResponse.error("startDate 不能晚于 endDate");
        }
        return ApiResponse.success(dataCoverageService.getMissingStocksRange(startDate, endDate, market));
    }

    @GetMapping("/missing-stats")
    @Operation(summary = "各市场数据缺失统计")
    public ApiResponse<Map<String, Object>> getMissingStats(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.success(dataCoverageService.getMissingStats(date));
    }

    @GetMapping({"/trading-dates", "/trading-dates/all"})
    @Operation(summary = "获取有数据的交易日列表")
    public ApiResponse<List<String>> getTradingDates(
            @RequestParam(defaultValue = "30") int limit) {
        return ApiResponse.success(dataCoverageService.getTradingDates(limit));
    }

    @GetMapping("/research/coverage")
    @Operation(summary = "研报数据覆盖率概览")
    public ApiResponse<Map<String, Object>> getResearchCoverage() {
        return ApiResponse.success(dataCoverageService.getResearchCoverage());
    }

    @GetMapping("/research/validate")
    @Operation(summary = "研报数据校验")
    public ApiResponse<Map<String, Object>> validateResearch() {
        return ApiResponse.success(dataCoverageService.validateResearch());
    }

    @GetMapping("/research/validate-range")
    @Operation(summary = "研报数据按日期区间校验（统计区间内每天研报数量）")
    public ApiResponse<Map<String, Object>> validateResearchRange(
            @RequestParam String startDate, @RequestParam String endDate) {
        return ApiResponse.success(dataCoverageService.validateResearchRange(startDate, endDate));
    }

    @GetMapping("/delisted/list")
    @Operation(summary = "查询退市股票列表（ClickHouse 检测最近无交易数据）")
    public ApiResponse<List<Map<String, Object>>> listDelistedStocks(
            @RequestParam(defaultValue = "30") int inactiveDays) {
        return dataLifecycleService.listDelistedStocks(inactiveDays);
    }

    @cn.dev33.satoken.annotation.SaCheckPermission(value = {"data:view", "data:edit"}, mode = cn.dev33.satoken.annotation.SaMode.AND)
    @PostMapping("/delisted/mark")
    @Operation(summary = "标记退市股票（更新 delist_date 而非删除）")
    public ApiResponse<Map<String, Object>> markDelistedStocks() {
        return dataLifecycleService.markDelistedStocks();
    }

    @GetMapping("/freshness")
    @Operation(summary = "数据新鲜度检查 — 检查各核心数据表的最新日期，超阈值告警")
    public ApiResponse<Map<String, Object>> checkFreshness() {
        return ApiResponse.success(dataUpdateService.checkDataFreshness());
    }

    @GetMapping("/price-anomalies")
    @Operation(summary = "价格异常检测 — 查询近 N 天内单日涨跌幅绝对值 >50% 的记录")
    public ApiResponse<Map<String, Object>> checkPriceAnomalies(
            @RequestParam(defaultValue = "7") int days) {
        return ApiResponse.success(dataUpdateService.checkPriceAnomalies(days));
    }

    @cn.dev33.satoken.annotation.SaCheckPermission(value = {"data:view", "data:edit"}, mode = cn.dev33.satoken.annotation.SaMode.AND)
    @PostMapping("/delisted/clean")
    @Operation(summary = "清理退市股票数据（物理删除，慎用）")
    public ApiResponse<Map<String, Object>> cleanDelistedStocks(@RequestBody List<String> codes) {
        return dataLifecycleService.cleanDelistedStocks(codes);
    }
}
