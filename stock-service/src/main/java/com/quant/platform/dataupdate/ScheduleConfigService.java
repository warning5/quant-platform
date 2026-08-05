package com.quant.platform.dataupdate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.CompletableFuture;
import com.quant.platform.common.enums.JobStatus;
/**
 * 定时任务配置业务逻辑层
 * 承接原 ScheduleConfigController 中直接内联的 JdbcTemplate 访问、内联 SQL、
 * 私有方法（insertIfNotExists / buildRequestFromKey / hasCycle / toIntBool）与跨服务编排。
 * Controller 仅保留参数接收、权限注解与响应包装。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleConfigService {

    private final JdbcTemplate jdbcTemplate;
    private final DataUpdateService dataUpdateService;
    private final ScheduleService scheduleService;
    private final ObjectMapper objectMapper;

    /** 是否已执行过 SENTIMENT 拆分迁移（仅执行一次） */
    private volatile boolean sentimentMigrated = false;

    /**
     * 一次性迁移：将旧的 SENTIMENT 拆分为 SENTIMENT_MF + SENTIMENT_OTHER
     * 在 getAllConfigs 首次调用时触发，仅执行一次
     */
    private synchronized void migrateSentimentIfNeeded() {
        if (sentimentMigrated) return;
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM data_schedule_config WHERE task_key = 'SENTIMENT'",
                Integer.class
            );
            if (count == null || count == 0) {
                sentimentMigrated = true;
                return;
            }

            log.info("[ScheduleConfig] 检测到旧 SENTIMENT 记录，开始拆分迁移...");

            Map<String, Object> oldRow = jdbcTemplate.queryForMap(
                "SELECT * FROM data_schedule_config WHERE task_key = 'SENTIMENT'"
            );
            int enabled = ((Number) oldRow.getOrDefault("enabled", 0)).intValue();
            String cron = (String) oldRow.getOrDefault("cron_expression", "0 * * * *");
            int useGlobal = ((Number) oldRow.getOrDefault("use_global_cron", 1)).intValue();
            String extraConfig = (String) oldRow.get("extra_config");

            insertIfNotExists("SENTIMENT_MF", "情绪数据-资金流向", enabled, cron, useGlobal, extraConfig);
            insertIfNotExists("SENTIMENT_OTHER", "情绪数据-其它", enabled, cron, useGlobal, extraConfig);

            jdbcTemplate.update("DELETE FROM data_schedule_config WHERE task_key = 'SENTIMENT'");

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

    public List<Map<String, Object>> getAllConfigs() {
        migrateSentimentIfNeeded();
        List<Map<String, Object>> list = jdbcTemplate.queryForList(
            "SELECT id, task_key, task_name, category, enabled, cron_expression, " +
            "use_global_cron, extra_config, last_run_time, last_run_status, " +
            "last_run_duration_sec, next_run_time, updated_at " +
            "FROM data_schedule_config ORDER BY id"
        );

        // 为 SENTIMENT 任务注入 sub_items（情绪数据子项列表），前端动态展示
        for (Map<String, Object> row : list) {
            String taskKey = (String) row.get("task_key");
            if ("SENTIMENT_MF".equals(taskKey) || "SENTIMENT_OTHER".equals(taskKey)) {
                try {
                    String extraConfigJson = (String) row.get("extra_config");
                    DataUpdateRequest req = buildRequestFromKey(taskKey, extraConfigJson);
                    row.put("sub_items", req.getSentimentSubItems());
                } catch (Exception e) {
                    log.warn("[ScheduleConfig] 构建 {} 的 sub_items 失败: {}", taskKey, e.getMessage());
                }
            }
        }

        return list;
    }

    public Map<String, Object> getGlobalConfig() {
        return jdbcTemplate.queryForRowSet(
            "SELECT * FROM data_schedule_config WHERE task_key = 'GLOBAL'"
        ).next() ? jdbcTemplate.queryForMap(
            "SELECT * FROM data_schedule_config WHERE task_key = 'GLOBAL'"
        ) : null;
    }

    /**
     * 更新全局或单项配置；返回更新后的记录；记录不存在则新建
     */
    public ApiResponse<Map<String, Object>> updateConfig(String taskKey, Map<String, Object> body) {
        StringBuilder setSql = new StringBuilder();
        List<Object> args = new ArrayList<>();

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
        if (body.containsKey("task_name")) {
            setSql.append("task_name=?, ");
            args.add(body.get("task_name").toString());
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

        scheduleService.refreshFromDb();

        Map<String, Object> updated = jdbcTemplate.queryForMap(
            "SELECT * FROM data_schedule_config WHERE task_key = ?", taskKey
        );
        return ApiResponse.success(updated);
    }

    /**
     * 批量更新配置（用于前端一次性保存多个任务的开关/cron等）
     */
    public ApiResponse<Boolean> batchUpdate(List<Map<String, Object>> items) {
        for (Map<String, Object> item : items) {
            String key = (String) item.get("taskKey");
            if (key == null || "GLOBAL".equals(key)) continue; // GLOBAL 单独处理

            List<Object> args = new ArrayList<>();
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
     * 手动触发单个数据更新任务（异步执行，避免 HTTP 阻塞）
     */
    public ApiResponse<Map<String, Object>> triggerTask(String taskKey) {
        try {
            String upper = taskKey.toUpperCase();
            CompletableFuture.runAsync(() -> {
                try {
                    scheduleService.executeTaskManual(upper);
                } catch (Throwable t) {
                    log.error("[ScheduleConfig] 异步执行 {} 失败: {}", upper, t.getMessage(), t);
                    try {
                        jdbcTemplate.update(
                            "UPDATE data_schedule_config SET last_run_time = ?, last_run_status = 'FAILED' WHERE task_key = ?",
                            LocalDateTime.now(), upper);
                    } catch (Exception ignored) {
                        log.error("[ScheduleConfigService] 捕获到未处理异常", ignored);
                    }
                }
            });

            jdbcTemplate.update(
                "UPDATE data_schedule_config SET last_run_time = ?, last_run_status = 'RUNNING' WHERE task_key = ?",
                LocalDateTime.now(), taskKey
            );
            log.info("[ScheduleConfig] 手动触发(异步): {}", taskKey);
            Map<String, Object> result = new java.util.HashMap<>();
            result.put("taskKey", taskKey);
            result.put("status", JobStatus.RUNNING.name());
            result.put("message", "任务已异步提交执行");
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("[ScheduleConfig] 触发失败: {}", taskKey, e);
            return ApiResponse.error("触发失败: " + e.getMessage());
        }
    }

    /**
     * 根据任务key构建默认的DataUpdateRequest（支持 extra_config 中的增量/日期配置）
     */
    public DataUpdateRequest buildRequestFromKey(String taskKey, String extraConfigJson) {
        DataUpdateRequest req = new DataUpdateRequest();
        req.setTaskKey(taskKey);
        String upper = taskKey.toUpperCase();

        boolean incremental = false;
        String dateMode = "today";
        String customStartDate = null;
        String customEndDate = null;
        String moneyflowSource = null;

        if (extraConfigJson != null && !extraConfigJson.isEmpty() && !extraConfigJson.equals("null")) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> ec = objectMapper.readValue(extraConfigJson, Map.class);
                if (ec != null) {
                    incremental = !Boolean.FALSE.equals(ec.get("incremental"));
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

        LocalDate today = LocalDate.now();
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
            case "DAILY" -> req.setUpdateType("DAILY");
            case "INDEX" -> req.setUpdateType("INDEX");
            case "DIVIDEND" -> req.setUpdateType("DIVIDEND");
            case "FINANCIAL" -> req.setUpdateType("FINANCIAL");
            case "BIDASK" -> req.setUpdateType("BIDASK");
            case "SENTIMENT" -> req.setUpdateType("SENTIMENT");
            case "SENTIMENT_MF" -> {
                req.setUpdateType("SENTIMENT");
                req.setMoneyflowSource(moneyflowSource != null ? moneyflowSource : "WESTOCK");
                req.setFetchLhb(false); req.setFetchMargin(false); req.setFetchSurvey(false);
                req.setFetchBlockTrade(false); req.setFetchActivity(false); req.setFetchZtPool(false);
                req.setFetchNotice(false); req.setFetchFundHolder(false); req.setFetchShareholder(false);
                req.setFetchNews(false); req.setFetchMoneyflow(true);
                req.setFetchBondYield(false); req.setFetchShenwanIndex(false); req.setFetchConsensusEstimate(false);
                req.setFetchEarningsReport(false); req.setFetchQvix(false);
            }
            case "SENTIMENT_OTHER" -> {
                req.setUpdateType("SENTIMENT");
                req.setFetchMoneyflow(false);
                req.setFetchLhb(true); req.setFetchMargin(true); req.setFetchSurvey(true);
                req.setFetchBlockTrade(true); req.setFetchActivity(true); req.setFetchZtPool(true);
                req.setFetchNotice(true); req.setFetchFundHolder(true); req.setFetchShareholder(true);
                req.setFetchNews(true);
                req.setFetchBondYield(true); req.setFetchShenwanIndex(true); req.setFetchConsensusEstimate(true);
                req.setFetchEarningsReport(true); req.setFetchQvix(true);
            }
            case "RESEARCH" -> req.setUpdateType("RESEARCH");
            case "QFQ_REFRESH" -> req.setUpdateType("QFQ_REFRESH");
            case "FACTOR_COMPUTE" -> req.setUpdateType("FACTOR_COMPUTE");
            case "DATA_FRESHNESS" -> { /* 质量检查: 已在 ScheduleService 中特殊处理 */ }
            case "PRICE_ANOMALY" -> { /* 质量检查: 已在 ScheduleService 中特殊处理 */ }
            case "RECOMMENDATION_TRACK" -> { /* P1-4: 已在 ScheduleService 中特殊处理 */ }
            case "DAILY_RECOMMENDATION" -> { /* Phase 2: 已在 ScheduleService 中特殊处理 */ }
            case "FACTOR_HEALTH_CHECK" -> { /* P3-11: 已在 ScheduleService 中特殊处理 */ }
            default -> throw new IllegalArgumentException("未知的任务类型: " + taskKey);
        }

        return req;
    }

    /**
     * 取消正在执行的任务（按任务类型）
     */
    public ApiResponse<Boolean> cancelTask(String taskKey) {
        DataUpdateRequest req = buildRequestFromKey(taskKey, null);
        boolean processCancelled = dataUpdateService.cancelByUpdateType(req.getUpdateType());
        jdbcTemplate.update(
            "UPDATE data_schedule_config SET last_run_status = 'CANCELLED', updated_at = ? WHERE task_key = ?",
            LocalDateTime.now(), taskKey
        );
        log.info("[ScheduleConfig] 取消任务: {} (进程取消={})", taskKey, processCancelled);
        return ApiResponse.success(true);
    }

    public List<Map<String, Object>> getHistory() {
        return jdbcTemplate.queryForList(
            "SELECT task_key, task_name, last_run_time, last_run_status, last_run_duration_sec, updated_at " +
            "FROM data_schedule_config " +
            "WHERE last_run_time IS NOT NULL " +
            "ORDER BY last_run_time DESC LIMIT 50"
        );
    }

    /**
     * 删除自定义定时配置（不允许删除预定义的系统任务）
     */
    public ApiResponse<Boolean> deleteConfig(String taskKey) {
        String[] systemKeys = {"GLOBAL", "DAILY", "INDEX", "DIVIDEND", "QFQ_REFRESH", "FINANCIAL", "BIDASK",
            "SENTIMENT_MF", "SENTIMENT_OTHER", "RESEARCH", "DATA_FRESHNESS", "PRICE_ANOMALY",
            "FACTOR_NULL_CHECK", "FINANCIAL_ANOMALY", "RECOMMENDATION_TRACK", "DAILY_RECOMMENDATION", "FACTOR_HEALTH_CHECK"};
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

    public List<Map<String, Object>> getDependencies() {
        return jdbcTemplate.queryForList(
            "SELECT d.id, d.upstream_key, d.downstream_key, d.delay_seconds, d.require_all_upstreams, " +
            "u.task_name AS upstream_name, v.task_name AS downstream_name, " +
            "d.created_at, d.updated_at " +
            "FROM data_task_dependency d " +
            "LEFT JOIN data_schedule_config u ON u.task_key = d.upstream_key " +
            "LEFT JOIN data_schedule_config v ON v.task_key = d.downstream_key " +
            "ORDER BY d.upstream_key, d.downstream_key"
        );
    }

    public List<Map<String, Object>> getTaskKeys() {
        return jdbcTemplate.queryForList(
            "SELECT task_key AS value, CONCAT(task_key, ' - ', task_name) AS label FROM data_schedule_config ORDER BY task_key"
        );
    }

    /**
     * 新增任务依赖关系（含循环依赖校验）
     */
    public ApiResponse<?> addDependency(Map<String, Object> body) {
        String upstream = (String) body.get("upstreamKey");
        String downstream = (String) body.get("downstreamKey");
        Integer delaySeconds = body.get("delaySeconds") != null
            ? ((Number) body.get("delaySeconds")).intValue() : 300;
        Integer requireAll = body.get("requireAllUpstreams") != null
            ? (((Number) body.get("requireAllUpstreams")).intValue() != 0 ? 1 : 0) : 0;

        if (upstream == null || downstream == null || upstream.isBlank() || downstream.isBlank()) {
            return ApiResponse.error("上游和下游任务不能为空");
        }
        if (upstream.equals(downstream)) {
            return ApiResponse.error("不能建立任务对自身的依赖");
        }

        Integer exists = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM data_task_dependency WHERE upstream_key = ? AND downstream_key = ?",
            Integer.class, upstream, downstream);
        if (exists != null && exists > 0) {
            return ApiResponse.error("该依赖关系已存在");
        }

        if (hasCycle(upstream, downstream)) {
            return ApiResponse.error("禁止创建循环依赖：添加后会导致 " + upstream + " → ... → " + downstream + " → " + upstream + " 闭环");
        }

        jdbcTemplate.update(
            "INSERT INTO data_task_dependency (upstream_key, downstream_key, delay_seconds, require_all_upstreams) VALUES (?, ?, ?, ?)",
            upstream, downstream, delaySeconds, requireAll);
        log.info("[ScheduleConfig] 新增依赖: {} → {} (延迟{}秒, requireAll={})", upstream, downstream, delaySeconds, requireAll);
        scheduleService.refreshDependencyChain();
        return ApiResponse.success(true);
    }

    public ApiResponse<?> deleteDependency(Long id) {
        int rows = jdbcTemplate.update("DELETE FROM data_task_dependency WHERE id = ?", id);
        if (rows == 0) return ApiResponse.error("依赖关系不存在");
        log.info("[ScheduleConfig] 删除依赖关系 id={}", id);
        scheduleService.refreshDependencyChain();
        return ApiResponse.success(true);
    }

    /**
     * DFS 检测循环依赖：从 startKey 出发能否通过已有依赖到达 targetKey
     */
    private boolean hasCycle(String startKey, String targetKey) {
        Set<String> visited = new HashSet<>();
        Stack<String> stack = new Stack<>();
        stack.push(targetKey);
        while (!stack.isEmpty()) {
            String cur = stack.pop();
            if (cur.equals(startKey)) return true;
            if (visited.contains(cur)) continue;
            visited.add(cur);
            List<String> deps = jdbcTemplate.queryForList(
                "SELECT downstream_key FROM data_task_dependency WHERE upstream_key = ?",
                String.class, cur);
            for (String dep : deps) {
                if (!visited.contains(dep)) stack.push(dep);
            }
        }
        return false;
    }

    /**
     * 将前端传来的值转为 0/1 整数
     */
    private static int toIntBool(Object val) {
        if (val == null) return 0;
        if (val instanceof Boolean) return ((Boolean) val) ? 1 : 0;
        if (val instanceof Number) return ((Number) val).intValue() != 0 ? 1 : 0;
        String s = val.toString().trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s) ? 1 : 0;
    }
}
