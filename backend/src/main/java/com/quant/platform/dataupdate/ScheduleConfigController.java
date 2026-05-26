package com.quant.platform.dataupdate;

import com.quant.platform.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 定时任务配置管理
 * - 全局 cron 配置
 * - 各数据项独立 cron（可覆盖全局）
 * - 手动触发
 */
@Slf4j
@RestController
@RequestMapping("/schedule-config")
@RequiredArgsConstructor
public class ScheduleConfigController {

    private final JdbcTemplate jdbcTemplate;
    private final DataUpdateService dataUpdateService;
    private final ScheduleService scheduleService;

    /** 是否已执行过 SENTIMENT 拆分迁移 */
    private volatile boolean sentimentMigrated = false;

    /**
     * 一次性迁移：将旧的 SENTIMENT 拆分为 SENTIMENT_MF + SENTIMENT_OTHER
     * 在 getAllConfigs 首次调用时触发，仅执行一次
     */
    private synchronized void migrateSentimentIfNeeded() {
        if (sentimentMigrated) return;
        try {
            // 检查旧 SENTIMENT 是否存在
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM data_schedule_config WHERE task_key = 'SENTIMENT'",
                Integer.class
            );
            if (count == null || count == 0) {
                // 旧记录不存在，检查新记录是否已存在（可能已经迁移过了）
                sentimentMigrated = true;
                return;
            }

            log.info("[ScheduleConfig] 检测到旧 SENTIMENT 记录，开始拆分迁移...");

            // 读取旧配置
            Map<String, Object> oldRow = jdbcTemplate.queryForMap(
                "SELECT * FROM data_schedule_config WHERE task_key = 'SENTIMENT'"
            );
            int enabled = ((Number) oldRow.getOrDefault("enabled", 0)).intValue();
            String cron = (String) oldRow.getOrDefault("cron_expression", "0 * * * *");
            int useGlobal = ((Number) oldRow.getOrDefault("use_global_cron", 1)).intValue();
            String extraConfig = (String) oldRow.get("extra_config");

            // 插入 SENTIMENT_MF
            insertIfNotExists("SENTIMENT_MF", "情绪数据-资金流向", enabled, cron, useGlobal, extraConfig);
            // 插入 SENTIMENT_OTHER
            insertIfNotExists("SENTIMENT_OTHER", "情绪数据-其它", enabled, cron, useGlobal, extraConfig);

            // 删除旧 SENTIMENT
            jdbcTemplate.update("DELETE FROM data_schedule_config WHERE task_key = 'SENTIMENT'");

            // 从 systemKeys 保护列表中移除旧 SENTIMENT（后续不再保护它）
            log.info("[ScheduleConfig] SENTIMENT 拆分迁移完成 → SENTIMENT_MF + SENTIMENT_OTHER");
        } catch (Exception e) {
            log.warn("[ScheduleConfig] SENTIMENT 迁移失败（可忽略，可能已迁移）: {}", e.getMessage());
        }
        sentimentMigrated = true;
    }

    private void insertIfNotExists(String taskKey, String taskName, int enabled,
                                     String cron, int useGlobal, String extraConfig) {
        Integer exists = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM data_schedule_config WHERE task_key = ?", Integer.class, taskKey);
        if (exists != null && exists > 0) return;
        jdbcTemplate.update(
            "INSERT INTO data_schedule_config (task_key, task_name, category, enabled, cron_expression, use_global_cron, extra_config) " +
            "VALUES (?, ?, 'DATA', ?, ?, ?, ?)",
            taskKey, taskName, enabled, cron, useGlobal, extraConfig
        );
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> getAllConfigs() {
        migrateSentimentIfNeeded();
        List<Map<String, Object>> list = jdbcTemplate.queryForList(
            "SELECT id, task_key, task_name, category, enabled, cron_expression, " +
            "use_global_cron, extra_config, last_run_time, last_run_status, " +
            "last_run_duration_sec, next_run_time, updated_at " +
            "FROM data_schedule_config ORDER BY id"
        );
        return ApiResponse.success(list);
    }

    @GetMapping("/global")
    public ApiResponse<Map<String, Object>> getGlobalConfig() {
        Map<String, Object> row = jdbcTemplate.queryForRowSet(
            "SELECT * FROM data_schedule_config WHERE task_key = 'GLOBAL'"
        ).next() ? jdbcTemplate.queryForMap(
            "SELECT * FROM data_schedule_config WHERE task_key = 'GLOBAL'"
        ) : null;
        return ApiResponse.success(row);
    }

    /**
     * 更新全局或单项配置
     */
    @PutMapping("/{taskKey}")
    public ApiResponse<Map<String, Object>> updateConfig(
            @PathVariable String taskKey,
            @RequestBody Map<String, Object> body) {
        // 支持更新的字段
        StringBuilder setSql = new StringBuilder();
        List<Object> args = new java.util.ArrayList<>();

        log.info("[ScheduleConfig] updateConfig({}) body keys: {}", taskKey, body.keySet());

        if (body.containsKey("enabled")) {
            setSql.append("enabled=?, ");
            args.add(toIntBool(body.get("enabled")));
        }
        if (body.containsKey("cron_expression")) {
            setSql.append("cron_expression=?, ");
            args.add(body.get("cron_expression"));
        }
        if (body.containsKey("use_global_cron")) {
            setSql.append("use_global_cron=?, ");
            args.add(toIntBool(body.get("use_global_cron")));
        }
        if (body.containsKey("extra_config")) {
            setSql.append("extra_config=?, ");
            args.add(body.get("extra_config") == null ? null : body.get("extra_config").toString());
        }

        if (setSql.length() == 0) {
            return ApiResponse.error("没有可更新的字段");
        }
        setSql.setLength(setSql.length() - 2); // 去掉最后的 ", "
        args.add(taskKey);

        int rows = jdbcTemplate.update(
            "UPDATE data_schedule_config SET " + setSql + " WHERE task_key = ?",
            args.toArray()
        );

        // 如果记录不存在（新建），执行 INSERT
        if (rows == 0) {
            String name = body.get("task_name") != null ? body.get("task_name").toString() : taskKey;
            Integer enabled = body.containsKey("enabled") ?
                toIntBool(body.get("enabled")) : 1;
            String cron = body.get("cron_expression") != null ? body.get("cron_expression").toString() : null;
            Integer useGlobal = body.containsKey("use_global_cron") ?
                toIntBool(body.get("use_global_cron")) : 1;
            String extra = body.get("extra_config") != null ? body.get("extra_config").toString() : null;

            jdbcTemplate.update(
                "INSERT INTO data_schedule_config (task_key, task_name, category, enabled, cron_expression, use_global_cron, extra_config) " +
                "VALUES (?,?,?,?,?,?,?) " +
                "ON DUPLICATE KEY UPDATE task_name=VALUES(task_name)",
                taskKey, name, "CUSTOM", enabled, cron, useGlobal, extra
            );
            log.info("[ScheduleConfig] 新建任务配置: {} ({})", taskKey, name);
        } else {
            log.info("[ScheduleConfig] 更新任务配置: {} -> {}", taskKey, body.keySet());
        }

        // 刷新调度器
        scheduleService.refreshFromDb();

        // 返回最新记录
        Map<String, Object> updated = jdbcTemplate.queryForMap(
            "SELECT * FROM data_schedule_config WHERE task_key = ?", taskKey
        );
        return ApiResponse.success(updated);
    }

    /**
     * 批量更新配置（用于前端一次性保存多个任务的开关/cron等）
     */
    @PutMapping("/batch")
    public ApiResponse<Boolean> batchUpdate(@RequestBody List<Map<String, Object>> items) {
        for (Map<String, Object> item : items) {
            String key = (String) item.get("taskKey");
            if (key == null || "GLOBAL".equals(key)) continue; // GLOBAL 单独处理

            List<Object> args = new java.util.ArrayList<>();
            StringBuilder sql = new StringBuilder("UPDATE data_schedule_config SET ");

            if (item.containsKey("enabled")) {
                sql.append("enabled=?, ");
                args.add(toIntBool(item.get("enabled")));
            }
            if (item.containsKey("cron_expression") && item.get("cron_expression") != null) {
                sql.append("cron_expression=?, use_global_cron=0, ");
                args.add(item.get("cron_expression"));
            } else if (item.containsKey("use_global_cron")) {
                sql.append("use_global_cron=?, ");
                args.add(toIntBool(item.get("use_global_cron")));
            }

            if (args.isEmpty()) continue;
            sql.setLength(sql.length() - 2);
            sql.append(" WHERE task_key=?");
            args.add(key);

            jdbcTemplate.update(sql.toString(), args.toArray());
        }
        log.info("[ScheduleConfig] 批量更新 {} 条配置", items.size());
        scheduleService.refreshFromDb();
        return ApiResponse.success(true);
    }

    /**
     * 手动触发单个数据更新任务（支持并发）
     */
    @PostMapping("/trigger/{taskKey}")
    public ApiResponse<Map<String, Object>> triggerTask(@PathVariable String taskKey) {
        try {
            // 读取任务的 extra_config
            Map<String, Object> configRow = null;
            try {
                configRow = jdbcTemplate.queryForMap(
                    "SELECT extra_config FROM data_schedule_config WHERE task_key = ?", taskKey);
            } catch (Exception ignored) {}

            String extraConfigJson = configRow != null ? (String) configRow.get("extra_config") : null;
            DataUpdateRequest request = buildRequestFromKey(taskKey, extraConfigJson);
            // 使用并发提交，不限制单任务
            DataUpdateTask task = dataUpdateService.submitTaskConcurrent(request);

            // 记录触发时间
            jdbcTemplate.update(
                "UPDATE data_schedule_config SET last_run_time = ?, last_run_status = 'RUNNING' WHERE task_key = ?",
                LocalDateTime.now(), taskKey
            );

            log.info("[ScheduleConfig] 手动触发: {}", taskKey);
            Map<String, Object> result = new java.util.HashMap<>();
            result.put("taskId", task.getTaskId());
            result.put("taskKey", taskKey);
            result.put("status", task.getStatus());
            result.put("message", "任务已提交执行");
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("[ScheduleConfig] 触发失败: {}", taskKey, e);
            return ApiResponse.error("触发失败: " + e.getMessage());
        }
    }

    /**
     * 根据任务key构建默认的DataUpdateRequest（支持 extra_config 中的增量/日期配置）
     */
    private DataUpdateRequest buildRequestFromKey(String taskKey, String extraConfigJson) {
        DataUpdateRequest req = new DataUpdateRequest();
        String upper = taskKey.toUpperCase();

        // 解析 extra_config（与 ScheduleService 保持一致）
        boolean incremental = false;
        String dateMode = "today";
        String customStartDate = null;
        String customEndDate = null;
        String moneyflowSource = null;  // 从 extra_config 读取，SENTIMENT_MF 用

        if (extraConfigJson != null && !extraConfigJson.isEmpty() && !extraConfigJson.equals("null")) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
                @SuppressWarnings("unchecked")
                Map<String, Object> ec = mapper.readValue(extraConfigJson, Map.class);
                if (ec != null) {
                    incremental = Boolean.TRUE.equals(ec.get("incremental"));
                    dateMode = ec.get("dateMode") != null ? ec.get("dateMode").toString() : "today";
                    customStartDate = ec.get("startDate") != null ? ec.get("startDate").toString() : null;
                    customEndDate = ec.get("endDate") != null ? ec.get("endDate").toString() : null;
                    moneyflowSource = ec.get("moneyflowSource") != null
                        ? ec.get("moneyflowSource").toString() : null;
                }
            } catch (Exception e) {
                log.warn("[ScheduleConfig] 解析 extra_config 失败: {}", e.getMessage());
            }
        }

        // 日期解析
        LocalDate today = java.time.LocalDate.now();
        if (customStartDate != null && customEndDate != null) {
            req.setStartDate(customStartDate);
            req.setEndDate(customEndDate);
        } else {
            switch (dateMode) {
                case "today" -> { req.setStartDate(today.toString()); req.setEndDate(today.toString()); }
                case "recent_1" -> { req.setStartDate(today.minusDays(1).toString()); req.setEndDate(today.minusDays(1).toString()); }
                case "recent_3" -> { req.setStartDate(today.minusDays(3).toString()); req.setEndDate(today.minusDays(1).toString()); }
                default -> { req.setStartDate(today.toString()); req.setEndDate(today.toString()); }
            }
        }

        req.setForce(!incremental);
        if (incremental) req.setResume(true);

        switch (upper) {
            case "DAILY":          req.setUpdateType("DAILY"); break;
            case "INDEX":          req.setUpdateType("INDEX"); break;
            case "DIVIDEND":       req.setUpdateType("DIVIDEND"); break;
            case "FINANCIAL":      req.setUpdateType("FINANCIAL"); break;
            case "BIDASK":         req.setUpdateType("BIDASK"); break;
            case "SENTIMENT":      req.setUpdateType("SENTIMENT"); break;
            case "SENTIMENT_MF":
                req.setUpdateType("SENTIMENT");
                // 从 extra_config 读取 moneyflowSource，默认 EM
                req.setMoneyflowSource(moneyflowSource != null ? moneyflowSource : "EM");
                req.setFetchLhb(false); req.setFetchMargin(false); req.setFetchSurvey(false);
                req.setFetchBlockTrade(false); req.setFetchActivity(false); req.setFetchZtPool(false);
                req.setFetchNotice(false); req.setFetchFundHolder(false); req.setFetchShareholder(false);
                req.setFetchNews(false); req.setFetchMoneyflow(true);
                break;
            case "SENTIMENT_OTHER":
                req.setUpdateType("SENTIMENT");
                req.setFetchMoneyflow(false);
                break;
            case "RESEARCH":       req.setUpdateType("RESEARCH"); break;
            default: throw new IllegalArgumentException("未知的任务类型: " + taskKey);
        }

        return req;
    }

    /**
     * 取消正在执行的任务（按任务类型）
     */
    @PostMapping("/cancel/{taskKey}")
    public ApiResponse<Boolean> cancelTask(@PathVariable String taskKey) {
        DataUpdateRequest req = buildRequestFromKey(taskKey, null);
        // 尝试取消实际进程（可能已不存在）
        boolean processCancelled = dataUpdateService.cancelByUpdateType(req.getUpdateType());
        // ★ 无论进程是否存活，都强制更新 DB 状态为 CANCELLED
        jdbcTemplate.update(
            "UPDATE data_schedule_config SET last_run_status = 'CANCELLED', updated_at = ? WHERE task_key = ?",
            LocalDateTime.now(), taskKey
        );
        log.info("[ScheduleConfig] 取消任务: {} (进程取消={})", taskKey, processCancelled);
        return ApiResponse.success(true);
    }

    /**
    @GetMapping("/history")
    public ApiResponse<List<Map<String, Object>>> getHistory() {
        List<Map<String, Object>> history = jdbcTemplate.queryForList(
            "SELECT task_key, task_name, last_run_time, last_run_status, last_run_duration_sec, updated_at " +
            "FROM data_schedule_config " +
            "WHERE last_run_time IS NOT NULL " +
            "ORDER BY last_run_time DESC LIMIT 50"
        );
        return ApiResponse.success(history);
    }

    /**
     * 删除自定义定时配置（不允许删除预定义的 GLOBAL/DAILY/INDEX 等系统任务）
     */
    @DeleteMapping("/{taskKey}")
    public ApiResponse<Boolean> deleteConfig(@PathVariable String taskKey) {
        String[] systemKeys = {"GLOBAL", "DAILY", "INDEX", "DIVIDEND", "FINANCIAL", "BIDASK", "SENTIMENT_MF", "SENTIMENT_OTHER", "RESEARCH"};
        for (String sk : systemKeys) {
            if (sk.equalsIgnoreCase(taskKey)) {
                return ApiResponse.error("不允许删除系统预定义任务: " + taskKey);
            }
        }
        int rows = jdbcTemplate.update(
            "DELETE FROM data_schedule_config WHERE task_key = ?", taskKey
        );
        if (rows == 0) {
            return ApiResponse.error("任务不存在: " + taskKey);
        }
        log.info("[ScheduleConfig] 删除任务配置: {}", taskKey);
        scheduleService.refreshFromDb();
        return ApiResponse.success(true);
    }

    /**
     * 将前端传来的值转为 0/1 整数
     * 兼容 Boolean(true/false)、Integer(1/0)、String("true"/"false") 等格式
     */
    private static int toIntBool(Object val) {
        if (val == null) return 0;
        if (val instanceof Boolean) return ((Boolean) val) ? 1 : 0;
        if (val instanceof Number) return ((Number) val).intValue() != 0 ? 1 : 0;
        String s = val.toString().trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s) ? 1 : 0;
    }
}
