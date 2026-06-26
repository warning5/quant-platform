package com.quant.platform.dataupdate;

import com.quant.platform.common.dto.ApiResponse;
import com.quant.platform.stock.service.ClickHouseStockService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
    private final com.quant.platform.config.ClickHouseConfig clickHouseConfig;

    @Value("${quant.data-update.python-path:python}")
    private String pythonPath;

    @Value("${quant.data-update.script-dir:scripts}")
    private String scriptDir;

    private String resolvedScriptDir;

    @PostConstruct
    public void init() {
        java.io.File dir = new java.io.File(scriptDir);
        if (!dir.isAbsolute()) {
            dir = java.nio.file.Paths.get(System.getProperty("user.dir"), scriptDir).toFile();
        }
        if (dir.exists() && dir.isDirectory()) {
            resolvedScriptDir = dir.getAbsolutePath();
            log.info("[DataUpdate] 脚本目录: {}", resolvedScriptDir);
        } else {
            log.error("[DataUpdate] 脚本目录不存在: {}", dir.getAbsolutePath());
            resolvedScriptDir = null;
        }
    }

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

    @GetMapping("/logs/{taskId}")
    @Operation(summary = "获取任务历史日志")
    public ApiResponse<java.util.List<Map<String, Object>>> getTaskLogs(@PathVariable String taskId) {
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
        //    注意：仅统计 查询日 ≥ 上市日 且 未退市 的股票
        Map<String, Long> totalByMarket = new HashMap<>();
        for (String market : Arrays.asList("SH", "SZ", "BJ")) {
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.quant.platform.stock.entity.StockInfo> w =
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
            w.eq(com.quant.platform.stock.entity.StockInfo::getMarket, market);
            w.le(com.quant.platform.stock.entity.StockInfo::getListDate, date); // 上市日在查询日之前或当天
            w.isNull(com.quant.platform.stock.entity.StockInfo::getDelistDate);   // 排除已退市
            Long total = stockInfoMapper.selectCount(w);
            totalByMarket.put(market, total != null ? total : 0L);
        }

        if (clickHouseConfig.isEnabled()) {
            // ── ClickHouse 路径 ──────────────────────────────────────────
            // ClickHouse 无 stock_info，所以：① 一次查出该日全部已有 codes
            //                                   ② 用 codeToMarket / codeToListDate 缓存映射区分市场和上市日
            Set<String> existingCodes = new HashSet<>();
            String chSql = "SELECT DISTINCT code FROM stock.stock_daily FINAL WHERE trade_date = '" + date + "'";
            List<Map<String, Object>> rows = clickHouseStockService.queryForList(chSql);
            for (Map<String, Object> row : rows) {
                Object codeVal = row.get("code");
                if (codeVal != null) existingCodes.add(codeVal.toString());
            }

            // 懒加载 code → market 映射 + code → listDate 映射（仅 ClickHouse 路径需要）
            // 排除已退市股票，避免虚增缺失统计
            if (codeToMarket == null) {
                codeToMarket = new HashMap<>();
                codeToListDate = new HashMap<>();
                com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.quant.platform.stock.entity.StockInfo> w =
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
                w.select(com.quant.platform.stock.entity.StockInfo::getCode,
                        com.quant.platform.stock.entity.StockInfo::getMarket,
                        com.quant.platform.stock.entity.StockInfo::getListDate);
                w.isNull(com.quant.platform.stock.entity.StockInfo::getDelistDate); // 排除已退市
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
                                "  AND si.list_date IS NOT NULL AND si.list_date <= ?" +
                                "  AND si.delist_date IS NULL";
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
     * 过滤规则：
     *   ① 查询日 ≥ 上市日（查询日前尚未上市的股票不视为缺失）
     *   ② 排除已退市股票（delist_date IS NULL）
     */
    private List<Map<String, Object>> queryMissingStocks(LocalDate date, String market) {
        // clickhouse.enabled = true  → ClickHouse 查已有 codes
        // clickhouse.enabled = false → MySQL 直接查已有 codes

        // 1. stock_info 目标市场全部股票（MySQL，始终查询），排除已退市
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.quant.platform.stock.entity.StockInfo> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        if (!"ALL".equalsIgnoreCase(market)) {
            wrapper.eq(com.quant.platform.stock.entity.StockInfo::getMarket, market);
        }
        wrapper.isNull(com.quant.platform.stock.entity.StockInfo::getDelistDate); // 排除已退市
        wrapper.select(
                com.quant.platform.stock.entity.StockInfo::getCode,
                com.quant.platform.stock.entity.StockInfo::getName,
                com.quant.platform.stock.entity.StockInfo::getMarket,
                com.quant.platform.stock.entity.StockInfo::getListDate,
                com.quant.platform.stock.entity.StockInfo::getDelistDate);
        List<com.quant.platform.stock.entity.StockInfo> allStocks = stockInfoMapper.selectList(wrapper);

        // 2. 获取该日 stock_daily 中已有数据的 codes
        Set<String> existingCodes = new HashSet<>();
        if (clickHouseConfig.isEnabled()) {
            String chSql = "SELECT DISTINCT code FROM stock.stock_daily FINAL WHERE trade_date = '" + date + "'";
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

    // ==================== 退市股票清理 ====================

    /**
     * 调用 find_delisted_stocks.py 脚本获取退市股票列表
     * @param inactiveDays 停牌天数阈值（默认30）
     */
    private String runFindDelistedScript(int inactiveDays) throws Exception {
        if (resolvedScriptDir == null) {
            throw new IllegalStateException("脚本目录未配置，请在 application.yml 中设置 quant.data-update.script-dir");
        }
        java.io.File script = new java.io.File(resolvedScriptDir, "find_delisted_stocks.py");
        if (!script.exists()) {
            throw new java.io.FileNotFoundException("脚本不存在: " + script.getAbsolutePath());
        }

        ProcessBuilder pb = new ProcessBuilder(pythonPath, script.getAbsolutePath(), String.valueOf(inactiveDays));
        pb.directory(new java.io.File(resolvedScriptDir));
        pb.redirectErrorStream(false);
        // 强制 Python 使用 UTF-8 输出，避免 Windows 默认 GBK 导致中文乱码
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        Process p = pb.start();

        // 读取 stdout（JSON 输出）
        String json;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            json = sb.toString().trim();
        }

        int rc = p.waitFor();
        if (rc != 0) {
            String err;
            try (BufferedReader er = new BufferedReader(
                    new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                err = String.join("\n", er.lines().toArray(String[]::new));
            }
            log.error("[find_delisted] 脚本执行失败 (rc={}): {}", rc, err);
            throw new RuntimeException("脚本执行失败: " + err);
        }

        if (json.isEmpty()) {
            return "[]";
        }
        return json;
    }

    @GetMapping("/delisted/list")
    @Operation(summary = "查询退市股票列表（ClickHouse 检测最近无交易数据）")
    public ApiResponse<List<Map<String, Object>>> listDelistedStocks(
            @RequestParam(defaultValue = "30") int inactiveDays) {
        try {
            // 直接调用脚本，不使用缓存（脚本执行速度快，且数据会频繁变化）
            String json = runFindDelistedScript(inactiveDays);
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            List<Map<String, Object>> stocks = mapper.readValue(json,
                    mapper.getTypeFactory().constructCollectionType(List.class, Map.class));
            log.info("[退市查询] 脚本执行完毕 ({} 只)", stocks.size());
            return ApiResponse.success(stocks);
        } catch (Exception e) {
            log.error("查询退市股票列表失败", e);
            return ApiResponse.error("查询失败: " + e.getMessage());
        }
    }

    @PostMapping("/delisted/mark")
    @Operation(summary = "标记退市股票（更新 delist_date 而非删除）")
    public ApiResponse<Map<String, Object>> markDelistedStocks() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String json = runFindDelistedScript(60);
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            List<Map<String, Object>> stocks = mapper.readValue(json,
                    mapper.getTypeFactory().constructCollectionType(List.class, Map.class));
            // 统计有日期可标记的候选（实际标记数由脚本内部决定）
            long markable = stocks.stream().filter(s -> {
                Object out = s.get("out_date");
                Object max = s.get("max_date");
                return (out != null && !out.toString().isEmpty()) || (max != null && !max.toString().isEmpty());
            }).count();
            result.put("markedCount", markable);
            result.put("candidateCount", stocks.size());
            result.put("stocks", stocks);
            log.info("[退市标记] 完成: 候选 {} 只, 可标记 {} 只", stocks.size(), markable);
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("退市标记失败", e);
            return ApiResponse.error("标记失败: " + e.getMessage());
        }
    }

    @GetMapping("/freshness")
    @Operation(summary = "数据新鲜度检查 — 检查各核心数据表的最新日期，超阈值告警")
    public ApiResponse<Map<String, Object>> checkFreshness() {
        try {
            Map<String, Object> report = dataUpdateService.checkDataFreshness();
            return ApiResponse.success(report);
        } catch (Exception e) {
            log.error("数据新鲜度检查失败", e);
            return ApiResponse.error("检查失败: " + e.getMessage());
        }
    }

    @GetMapping("/price-anomalies")
    @Operation(summary = "价格异常检测 — 查询近 N 天内单日涨跌幅绝对值 >50% 的记录")
    public ApiResponse<Map<String, Object>> checkPriceAnomalies(
            @RequestParam(defaultValue = "7") int days) {
        try {
            Map<String, Object> report = dataUpdateService.checkPriceAnomalies(days);
            return ApiResponse.success(report);
        } catch (Exception e) {
            log.error("价格异常检测失败", e);
            return ApiResponse.error("检测失败: " + e.getMessage());
        }
    }

    @PostMapping("/delisted/clean")
    @Operation(summary = "清理退市股票数据（物理删除，慎用）")
    public ApiResponse<Map<String, Object>> cleanDelistedStocks(@RequestBody List<String> codes) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            if (codes == null || codes.isEmpty()) {
                return ApiResponse.error("请选择要清理的股票");
            }

            result.put("cleanedCodes", codes);
            result.put("codeCount", codes.size());
            int totalDeleted = 0;

            // 1. MySQL stock_info 删除
            try {
                String placeholders = String.join(",", codes.stream().map(c -> "?").toArray(String[]::new));
                int deleted = jdbcTemplate.update(
                        "DELETE FROM stock_info WHERE code IN (" + placeholders + ")", codes.toArray());
                result.put("stockInfoDeleted", deleted);
                totalDeleted += deleted;
            } catch (Exception e) {
                log.error("删除 MySQL stock_info 失败", e);
                result.put("stockInfoError", e.getMessage());
            }

            // stock_daily 和 moneyflow 用 code 字段，factor_value 用 symbol 字段
            Map<String, String> codeColumns = new LinkedHashMap<>();
            codeColumns.put("stock_daily", "code");
            codeColumns.put("factor_value", "symbol");
            codeColumns.put("stock_sentiment_moneyflow", "code");

            for (Map.Entry<String, String> entry : codeColumns.entrySet()) {
                String table = entry.getKey();
                String col = entry.getValue();
                try {
                    // 先查删除前数量
                    String ph = String.join(",", codes.stream().map(c -> "?").toArray(String[]::new));
                    Object bc = clickHouseStockService.queryForObject(
                            "SELECT count() FROM stock." + table + " FINAL WHERE " + col + " IN (" + ph + ")",
                            codes.toArray());
                    long beforeCount = bc != null ? ((Number) bc).longValue() : 0L;
                    result.put(table + "_before", beforeCount);

                    if (beforeCount > 0) {
                        String inClause = String.join(",", codes.stream().map(c -> "'" + c + "'").toArray(String[]::new));
                        String deleteSql = "ALTER TABLE stock." + table + " DELETE WHERE " + col + " IN (" + inClause + ")";
                        clickHouseStockService.executeDdl(deleteSql);
                        result.put(table + "_deleted", beforeCount);
                        totalDeleted += (int) beforeCount;
                    } else {
                        result.put(table + "_deleted", 0);
                    }
                } catch (Exception e) {
                    log.error("删除 CH {} 失败", table, e);
                    result.put(table + "_error", e.getMessage());
                }
            }

            result.put("totalDeleted", totalDeleted);
            log.info("[退市清理] 完成: codes={}, 总删除 {}", codes, totalDeleted);
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("退市清理失败", e);
            return ApiResponse.error("清理失败: " + e.getMessage());
        }
    }
}
