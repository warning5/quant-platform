package com.quant.platform.dataupdate;

import com.quant.platform.common.dto.ApiResponse;
import com.quant.platform.stock.mapper.StockDailyMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

/**
 * 数据更新管理 API
 */
@Slf4j
@RestController
@RequestMapping("/data-update")
@RequiredArgsConstructor
@Tag(name = "数据更新", description = "股票数据更新管理接口（调用 Python 脚本采集数据）")
public class DataUpdateController {

    private final DataUpdateService dataUpdateService;
    private final StockDailyMapper stockDailyMapper;
    private final com.quant.platform.stock.mapper.StockInfoMapper stockInfoMapper;

    @GetMapping("/default-dates")
    @Operation(summary = "获取默认更新日期范围")
    public ApiResponse<Map<String, String>> getDefaultDates() {
        java.time.LocalDate today = java.time.LocalDate.now();
        int days = dataUpdateService.getDefaultStartDays();
        java.time.LocalDate from = today.minusDays(days);
        return ApiResponse.success(Map.of(
                "startDate", from.toString(),
                "endDate", today.toString(),
                "days", String.valueOf(days)
        ));
    }

    @PostMapping("/start")
    @Operation(summary = "启动数据更新任务")
    public ApiResponse<Map<String, Object>> startTask(@RequestBody DataUpdateRequest request) {
        log.info("启动数据更新任务: source={}, market={}, startDate={}, endDate={}",
                request.getSource(), request.getMarket(), request.getStartDate(), request.getEndDate());

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
            // 返回一个空闲状态
            DataUpdateTask idle = new DataUpdateTask();
            idle.setTaskId("IDLE");
            idle.setStatus("IDLE");
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

    @PostMapping("/cancel/{taskId}")
    @Operation(summary = "取消任务")
    public ApiResponse<Map<String, Object>> cancelTask(@PathVariable String taskId) {
        boolean ok = dataUpdateService.cancelTask(taskId);
        return ApiResponse.success(ok ? "任务已取消" : "取消失败", Map.of("cancelled", ok));
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

        // 直接用 SQL 查询：在 stock_info 中但不在 stock_daily (指定日期) 中的股票
        List<Map<String, Object>> result = queryMissingStocks(date, market);
        return ApiResponse.success(result);
    }

    @GetMapping("/missing-stats")
    @Operation(summary = "各市场数据缺失统计")
    public ApiResponse<Map<String, Object>> getMissingStats(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        Map<String, Object> result = new LinkedHashMap<>();
        int totalMissing = 0;

        for (String market : Arrays.asList("SH", "SZ", "BJ")) {
            int count = queryMissingCount(date, market);
            result.put(market, count);
            totalMissing += count;
        }
        result.put("total", totalMissing);
        result.put("date", date.toString());

        return ApiResponse.success(result);
    }

    @GetMapping("/trading-dates")
    @Operation(summary = "获取有数据的交易日列表")
    public ApiResponse<List<String>> getTradingDates(
            @RequestParam(defaultValue = "30") int limit) {
        return ApiResponse.success(getRecentTradingDates(limit));
    }

    // ==================== 私有方法 ====================

    /**
     * 查询缺失股票列表
     * 只匹配 stock_info 中的 code，排除指数（指数不在 stock_info 中）
     */
    private List<Map<String, Object>> queryMissingStocks(LocalDate date, String market) {
        List<Map<String, Object>> missing = new ArrayList<>();

        // 先查 stock_info 中该市场所有股票的 codes（排除指数）
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.quant.platform.stock.entity.StockInfo> infoWrapper =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        if (market != null && !"ALL".equals(market)) {
            infoWrapper.eq(com.quant.platform.stock.entity.StockInfo::getMarket, market);
        }
        infoWrapper.select(com.quant.platform.stock.entity.StockInfo::getCode,
                  com.quant.platform.stock.entity.StockInfo::getName,
                  com.quant.platform.stock.entity.StockInfo::getMarket);
        List<com.quant.platform.stock.entity.StockInfo> allStocks = stockInfoMapper.selectList(infoWrapper);

        if (allStocks.isEmpty()) return missing;

        // 收集 stock_info 中的 codes
        Set<String> infoCodes = new HashSet<>();
        for (com.quant.platform.stock.entity.StockInfo s : allStocks) {
            infoCodes.add(s.getCode());
        }

        // 查 stock_daily 中该日有数据的 codes（仅 stock_info 中的 code）
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.quant.platform.stock.entity.StockDaily> dw =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        dw.eq(com.quant.platform.stock.entity.StockDaily::getTradeDate, date)
          .in(com.quant.platform.stock.entity.StockDaily::getCode, infoCodes)
          .select(com.quant.platform.stock.entity.StockDaily::getCode);
        List<com.quant.platform.stock.entity.StockDaily> existing = stockDailyMapper.selectList(dw);
        Set<String> existingCodes = new HashSet<>();
        for (com.quant.platform.stock.entity.StockDaily d : existing) {
            existingCodes.add(d.getCode());
        }

        // 差集 = stock_info 中有但 stock_daily 该日没有的
        for (com.quant.platform.stock.entity.StockInfo stock : allStocks) {
            if (!existingCodes.contains(stock.getCode())) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("code", stock.getCode());
                m.put("name", stock.getName() != null ? stock.getName() : "");
                m.put("market", stock.getMarket() != null ? stock.getMarket() : "");
                missing.add(m);
            }
        }

        return missing;
    }

    private int queryMissingCount(LocalDate date, String market) {
        return queryMissingStocks(date, market).size();
    }

    private List<String> getRecentTradingDates(int limit) {
        // 用 SQL 获取最近有数据的交易日
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.quant.platform.stock.entity.StockDaily> w =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        w.groupBy(com.quant.platform.stock.entity.StockDaily::getTradeDate)
         .orderByDesc(com.quant.platform.stock.entity.StockDaily::getTradeDate)
         .last("LIMIT " + Math.min(limit, 100))
         .select(com.quant.platform.stock.entity.StockDaily::getTradeDate);

        List<com.quant.platform.stock.entity.StockDaily> list = stockDailyMapper.selectList(w);
        List<String> dates = new ArrayList<>();
        for (com.quant.platform.stock.entity.StockDaily d : list) {
            dates.add(d.getTradeDate().toString());
        }
        return dates;
    }
}
