package com.quant.platform.dataupdate;

import com.quant.platform.common.dto.ApiResponse;
import com.quant.platform.config.ClickHouseConfig;
import com.quant.platform.stock.service.ClickHouseStockService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final JdbcTemplate jdbcTemplate;
    private final com.quant.platform.stock.mapper.StockInfoMapper stockInfoMapper;
    private final ClickHouseStockService clickHouseStockService;
    private final ClickHouseConfig clickHouseConfig;

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

        // clickhouse.enabled = true  → 通过 ClickHouseStockService 查 ClickHouse
        // clickhouse.enabled = false → 通过 JdbcTemplate 直接查 MySQL
        // stock_info.code 和 stock_daily.code 均为纯数字（无 sh./sz./bj. 前缀）

        Map<String, Object> result = new LinkedHashMap<>();
        int totalMissing = 0;

        // 1. 各市场 stock_info 股票总数（MySQL 始终走这里）
        //    注意：仅统计 查询日 ≥ 上市日 的股票（查询日前尚未上市的不计入）
        Map<String, Long> totalByMarket = new HashMap<>();
        for (String market : Arrays.asList("SH", "SZ", "BJ")) {
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.quant.platform.stock.entity.StockInfo> w =
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
            w.eq(com.quant.platform.stock.entity.StockInfo::getMarket, market);
            w.le(com.quant.platform.stock.entity.StockInfo::getListDate, date); // 上市日在查询日之前或当天
            Long total = stockInfoMapper.selectCount(w);
            totalByMarket.put(market, total != null ? total : 0L);
        }

        if (clickHouseConfig.isEnabled()) {
            // ── ClickHouse 路径 ──────────────────────────────────────────
            // ClickHouse 无 stock_info，所以：① 一次查出该日全部已有 codes
            //                                   ② 用 codeToMarket / codeToListDate 缓存映射区分市场和上市日
            Set<String> existingCodes = new HashSet<>();
            String chSql = "SELECT DISTINCT code FROM stock.stock_daily WHERE trade_date = '" + date + "'";
            List<Map<String, Object>> rows = clickHouseStockService.queryForList(chSql);
            for (Map<String, Object> row : rows) {
                Object codeVal = row.get("code");
                if (codeVal != null) existingCodes.add(codeVal.toString());
            }

            // 懒加载 code → market 映射 + code → listDate 映射（仅 ClickHouse 路径需要）
            if (codeToMarket == null) {
                codeToMarket = new HashMap<>();
                codeToListDate = new HashMap<>();
                com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.quant.platform.stock.entity.StockInfo> w =
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
                w.select(com.quant.platform.stock.entity.StockInfo::getCode,
                        com.quant.platform.stock.entity.StockInfo::getMarket,
                        com.quant.platform.stock.entity.StockInfo::getListDate);
                for (com.quant.platform.stock.entity.StockInfo s : stockInfoMapper.selectList(w)) {
                    if (s.getCode() != null && s.getMarket() != null) {
                        codeToMarket.put(s.getCode(), s.getMarket());
                        codeToListDate.put(s.getCode(), s.getListDate());
                    }
                }
            }

            // 用 codeToMarket 将 existingCodes 按市场分组
            // 额外过滤：仅计入 查询日 ≥ 上市日 的股票（查询日前尚未上市的不计入）
            Map<String, Integer> existingByMarket = new HashMap<>();
            for (String m : Arrays.asList("SH", "SZ", "BJ")) existingByMarket.put(m, 0);
            for (String code : existingCodes) {
                LocalDate listDate = codeToListDate.get(code);
                if (listDate != null && date.isBefore(listDate)) {
                    continue; // 尚未上市，不计入
                }
                String m = codeToMarket.get(code);
                if (m != null && existingByMarket.containsKey(m)) {
                    existingByMarket.put(m, existingByMarket.get(m) + 1);
                }
            }

            for (String market : Arrays.asList("SH", "SZ", "BJ")) {
                long total = totalByMarket.getOrDefault(market, 0L);
                long existing = existingByMarket.getOrDefault(market, 0);
                long missing = Math.max(0, total - existing);
                result.put(market, missing);
                totalMissing += (int) missing;
            }
        } else {
            // ── MySQL 路径 ────────────────────────────────────────────────
            // 已有数据统计：JOIN stock_info 并过滤上市日 ≤ 查询日
            for (String market : Arrays.asList("SH", "SZ", "BJ")) {
                long total = totalByMarket.getOrDefault(market, 0L);
                String mysqlSql =
                        "SELECT COUNT(DISTINCT sd.code) FROM stock_daily sd " +
                                "JOIN stock_info si ON sd.code = si.code " +
                                "WHERE sd.trade_date = ? " +
                                "  AND si.market = ?" +
                                "  AND si.list_date IS NOT NULL AND si.list_date <= ?";
                Long existing = jdbcTemplate.queryForObject(mysqlSql, Long.class,
                        java.sql.Date.valueOf(date), market, java.sql.Date.valueOf(date));
                long missing = Math.max(0, total - existing);
                result.put(market, (int) missing);
                totalMissing += (int) missing;
            }
        }

        result.put("total", totalMissing);
        result.put("date", date.toString());

        return ApiResponse.success(result);
    }

    @GetMapping({"/trading-dates", "/trading-dates/all"})
    @Operation(summary = "获取有数据的交易日列表")
    public ApiResponse<List<String>> getTradingDates(
            @RequestParam(defaultValue = "30") int limit) {
        // /trading-dates/all 路径时，limit 参数忽略，返回全部交易日（上限 5000）
        return ApiResponse.success(getRecentTradingDates(limit > 100 ? limit : 9999));
    }

    // ==================== 私有方法 ====================

    /**
     * 查询缺失股票列表
     * 只匹配 stock_info 中的 code，排除指数（指数不在 stock_info 中）
     * 过滤规则：仅报告 查询日 ≥ 上市日 的股票（查询日前尚未上市的股票不视为缺失）
     */
    private List<Map<String, Object>> queryMissingStocks(LocalDate date, String market) {
        // clickhouse.enabled = true  → ClickHouse 查已有 codes
        // clickhouse.enabled = false → MySQL 直接查已有 codes

        // 1. stock_info 目标市场全部股票（MySQL，始终查询）
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.quant.platform.stock.entity.StockInfo> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        if (!"ALL".equalsIgnoreCase(market)) {
            wrapper.eq(com.quant.platform.stock.entity.StockInfo::getMarket, market);
        }
        wrapper.select(
                com.quant.platform.stock.entity.StockInfo::getCode,
                com.quant.platform.stock.entity.StockInfo::getName,
                com.quant.platform.stock.entity.StockInfo::getMarket,
                com.quant.platform.stock.entity.StockInfo::getListDate);
        List<com.quant.platform.stock.entity.StockInfo> allStocks = stockInfoMapper.selectList(wrapper);

        // 2. 获取该日 stock_daily 中已有数据的 codes
        Set<String> existingCodes = new HashSet<>();
        if (clickHouseConfig.isEnabled()) {
            String chSql = "SELECT DISTINCT code FROM stock.stock_daily WHERE trade_date = '" + date + "'";
            List<Map<String, Object>> rows = clickHouseStockService.queryForList(chSql);
            for (Map<String, Object> row : rows) {
                Object codeVal = row.get("code");
                if (codeVal != null) existingCodes.add(codeVal.toString());
            }
        } else {
            String mysqlSql;
            if ("ALL".equalsIgnoreCase(market)) {
                mysqlSql = "SELECT DISTINCT sd.code FROM stock_daily sd WHERE sd.trade_date = ?";
                jdbcTemplate.query(mysqlSql,
                        (java.sql.ResultSet rs) -> existingCodes.add(rs.getString("code")),
                        java.sql.Date.valueOf(date));
            } else {
                mysqlSql = "SELECT DISTINCT sd.code FROM stock_daily sd " +
                        "JOIN stock_info si ON sd.code = si.code " +
                        "WHERE sd.trade_date = ? AND si.market = ?";
                jdbcTemplate.query(mysqlSql,
                        (java.sql.ResultSet rs) -> existingCodes.add(rs.getString("code")),
                        java.sql.Date.valueOf(date), market);
            }
        }

        // 3. 差集 = stock_info 中有但 stock_daily 该日没有的
        //    额外过滤：查询日早于上市日 → 该股票当时尚未上市，不视为缺失
        List<Map<String, Object>> missing = new ArrayList<>();
        for (com.quant.platform.stock.entity.StockInfo stock : allStocks) {
            // 关键过滤：查询日在上市日之前 → 不应显示为缺失
            if (stock.getListDate() != null && date.isBefore(stock.getListDate())) {
                continue; // 尚未上市，跳过
            }
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

    private List<String> getRecentTradingDates(int limit) {
        return clickHouseStockService.getRecentTradingDates(limit);
    }

    // ── 辅助字段 ───────────────────────────────────────────────────

    /**
     * stock_info 全量 code → market 映射（ClickHouse 路径懒加载缓存）
     */
    private volatile Map<String, String> codeToMarket;

    /**
     * stock_info 全量 code → listDate 映射（ClickHouse 路径懒加载缓存）。
     * 用于在 getMissingStats 中过滤掉查询日尚未上市的股票。
     */
    private volatile Map<String, LocalDate> codeToListDate;
    @GetMapping("/research/coverage")
    @Operation(summary = "研报数据覆盖率概览")
    public ApiResponse<Map<String, Object>> getResearchCoverage() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Integer totalCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM stock_research_report", Integer.class);
            result.put("totalCount", totalCount != null ? totalCount : 0);

            Integer stockCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(DISTINCT code) FROM stock_research_report", Integer.class);
            result.put("stockCount", stockCount != null ? stockCount : 0);

            String latestDate = jdbcTemplate.queryForObject(
                    "SELECT MAX(report_date) FROM stock_research_report", String.class);
            result.put("latestDate", latestDate != null ? latestDate : "");

            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("获取研报覆盖率失败", e);
            return ApiResponse.error("获取研报覆盖率失败: " + e.getMessage());
        }
    }

    @GetMapping("/research/validate")
    @Operation(summary = "研报数据校验")
    public ApiResponse<Map<String, Object>> validateResearch() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            // 研报总数
            Integer totalReports = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM stock_research_report", Integer.class);
            result.put("totalReports", totalReports != null ? totalReports : 0);

            // === 空值检查 ===
            List<String> warnings = new ArrayList<>();

            // 检查 rating 为空
            Integer nullRating = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM stock_research_report WHERE rating IS NULL OR rating = ''", Integer.class);
            if (nullRating != null && nullRating > 0) {
                warnings.add("rating 为空: " + nullRating + " 条");
            }

            // 检查 report_title 为空
            Integer nullTitle = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM stock_research_report WHERE report_title IS NULL OR report_title = ''", Integer.class);
            if (nullTitle != null && nullTitle > 0) {
                warnings.add("report_title 为空: " + nullTitle + " 条");
            }

            // 检查 7 天内新增数据
            Integer recentCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM stock_research_report WHERE report_date >= DATE_SUB(CURDATE(), INTERVAL 7 DAY)", Integer.class);
            result.put("recentReports", recentCount != null ? recentCount : 0);

            result.put("warnings", warnings);
            result.put("status", warnings.isEmpty() ? "OK" : "WARNING");

            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("研报数据校验失败", e);
            return ApiResponse.error("研报数据校验失败: " + e.getMessage());
        }
    }
}
