package com.quant.platform.dataupdate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import com.quant.platform.recommendation.service.RecommendationService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * 定时调度服务
 * - 启动时从 DB 加载所有 enabled=1 的配置，按 cron 注册调度任务
 * - 支持运行时动态增/删/改调度任务（DB 变动时调用 refresh()）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleService implements SchedulingConfigurer {

    private final JdbcTemplate jdbcTemplate;
    private final DataUpdateService dataUpdateService;
    private final TaskScheduler taskScheduler;
    private final RecommendationService recommendationService;

    // taskKey → ScheduledFuture（用于动态取消）
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    /**
     * Spring 启动后回调：注册所有 enabled 的任务
     */
    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        // 在 Spring 的调度器初始化后刷新
        refreshFromDb();
    }

    /**
     * 从 DB 重新加载并注册所有 enabled=1 且 cron_expression 非空的配置
     * - 全局暂停（GLOBAL.enabled=0）时，不注册任何任务
     * - use_global_cron=1 的任务使用 GLOBAL 的 cron_expression
     */
    public synchronized void refreshFromDb() {
        // 先取消所有现存的调度
        cancelAll();

        // 读取全局配置
        Integer globalEnabled = jdbcTemplate.queryForObject(
            "SELECT enabled FROM data_schedule_config WHERE task_key = 'GLOBAL'", Integer.class);
        if (globalEnabled == null || globalEnabled == 0) {
            log.info("[ScheduleService] 全局调度已暂停（GLOBAL.enabled=0），不注册任何任务");
            return;
        }

        String globalCron = null;
        try {
            globalCron = jdbcTemplate.queryForObject(
                "SELECT cron_expression FROM data_schedule_config WHERE task_key = 'GLOBAL'", String.class);
        } catch (Exception ignored) {}

        List<Map<String, Object>> configs = jdbcTemplate.queryForList(
            "SELECT task_key, cron_expression, use_global_cron FROM data_schedule_config " +
            "WHERE enabled = 1 AND task_key <> 'GLOBAL'"
        );

        for (Map<String, Object> row : configs) {
            String taskKey = (String) row.get("task_key");
            Integer useGlobal = row.get("use_global_cron") != null
                ? ((Number) row.get("use_global_cron")).intValue() : 1;

            String cron;
            if (useGlobal == 1 && globalCron != null && !globalCron.isEmpty()) {
                cron = globalCron;
            } else {
                cron = (String) row.get("cron_expression");
            }

            if (cron == null || cron.isEmpty()) {
                log.info("[ScheduleService] 跳过 {}：无有效 cron 表达式", taskKey);
                continue;
            }

            try {
                registerTask(taskKey, cron);
            } catch (Exception e) {
                log.error("[ScheduleService] 注册任务失败: {} ({})", taskKey, cron, e);
            }
        }
        log.info("[ScheduleService] 刷新完成，当前调度任务数: {}", scheduledTasks.size());
    }

    /**
     * 注册单个任务的 cron 调度
     * cron 格式：秒 分 时 日 月 周（6字段，Spring 标准）
     */
    private void registerTask(String taskKey, String cronExpression) {
        // 验证 cron 格式
        try {
            new CronTrigger(cronExpression);
        } catch (Exception e) {
            log.error("[ScheduleService] 无效 cron 表达式: {} ({})", taskKey, cronExpression);
            return;
        }

        ScheduledFuture<?> future = taskScheduler.schedule(
            () -> executeTask(taskKey),
            new CronTrigger(cronExpression)
        );

        scheduledTasks.put(taskKey, future);
        log.info("[ScheduleService] 注册调度: {} cron={}", taskKey, cronExpression);
    }

    /**
     * 取消单个任务的调度
     */
    public synchronized void cancelTask(String taskKey) {
        ScheduledFuture<?> future = scheduledTasks.remove(taskKey);
        if (future != null) {
            future.cancel(false);
            log.info("[ScheduleService] 取消调度: {}", taskKey);
        }
    }

    /**
     * 取消所有调度
     */
    private synchronized void cancelAll() {
        for (Map.Entry<String, ScheduledFuture<?>> entry : scheduledTasks.entrySet()) {
            try {
                entry.getValue().cancel(false);
            } catch (Exception ignored) {}
        }
        scheduledTasks.clear();
        log.info("[ScheduleService] 已取消所有调度任务");
    }

    /**
     * 执行任务：构建 DataUpdateRequest → 提交给 DataUpdateService（支持并发）
     */
    private void executeTask(String taskKey) {
        try {
            // P1-4: 推荐追踪任务（每个交易日15:30自动执行）
            if ("RECOMMENDATION_TRACK".equals(taskKey)) {
                int updated = recommendationService.trackRecommendationPerformance();
                log.info("[ScheduleService] 推荐追踪完成: 更新{}条", updated);
                return;
            }

            // 读取任务的 extra_config
            Map<String, Object> configRow = null;
            try {
                configRow = jdbcTemplate.queryForMap(
                    "SELECT extra_config FROM data_schedule_config WHERE task_key = ?", taskKey);
            } catch (Exception ignored) {}

            String extraConfigJson = configRow != null ? (String) configRow.get("extra_config") : null;
            DataUpdateRequest request = buildRequestFromKey(taskKey, extraConfigJson);
            // 使用 submitTaskConcurrent 绕过单任务限制，支持多任务并发
            dataUpdateService.submitTaskConcurrent(request);

            jdbcTemplate.update(
                "UPDATE data_schedule_config SET last_run_time = ?, last_run_status = 'RUNNING' " +
                "WHERE task_key = ?",
                LocalDateTime.now(), taskKey
            );
            log.info("[ScheduleService] 定时执行: {}", taskKey);
        } catch (Exception e) {
            jdbcTemplate.update(
                "UPDATE data_schedule_config SET last_run_time = ?, last_run_status = 'FAILED' " +
                "WHERE task_key = ?",
                LocalDateTime.now(), taskKey
            );
            log.error("[ScheduleService] 定时执行失败: {}", taskKey, e);
        }
    }

    /**
     * 根据 taskKey 构建 DataUpdateRequest，解析 extra_config 中的增量/日期配置
     */
    private DataUpdateRequest buildRequestFromKey(String taskKey, String extraConfigJson) {
        DataUpdateRequest req = new DataUpdateRequest();
        String upper = taskKey.toUpperCase();

        // 解析 extra_config
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
                log.warn("[ScheduleService] 解析 extra_config 失败: {}", e.getMessage());
            }
        }

        // 日期解析：根据 dateMode 动态计算
        LocalDate today = LocalDate.now();
        if (customStartDate != null && customEndDate != null) {
            req.setStartDate(customStartDate);
            req.setEndDate(customEndDate);
        } else {
            switch (dateMode) {
                case "today" -> {
                    req.setStartDate(today.toString());
                    req.setEndDate(today.toString());
                }
                case "recent_1" -> {
                    req.setStartDate(today.minusDays(1).toString());
                    req.setEndDate(today.minusDays(1).toString());
                }
                case "recent_3" -> {
                    req.setStartDate(today.minusDays(3).toString());
                    req.setEndDate(today.minusDays(1).toString());
                }
                default -> {
                    // 默认当天
                    req.setStartDate(today.toString());
                    req.setEndDate(today.toString());
                }
            }
        }

        // 增量模式：不使用 force；全量模式：使用 force
        req.setForce(!incremental);
        if (incremental) req.setResume(true);

        return switch (upper) {
            case "DAILY"         -> { req.setUpdateType("DAILY");         yield req; }
            case "INDEX"         -> { req.setUpdateType("INDEX");         yield req; }
            case "DIVIDEND"      -> { req.setUpdateType("DIVIDEND");      yield req; }
            case "FINANCIAL"     -> { req.setUpdateType("FINANCIAL");     yield req; }
            case "BIDASK"        -> { req.setUpdateType("BIDASK");        yield req; }
            case "SENTIMENT"     -> { req.setUpdateType("SENTIMENT");     yield req; }
            case "SENTIMENT_MF"  -> {
                req.setUpdateType("SENTIMENT");
                // 从 extra_config 读取 moneyflowSource，默认 WESTOCK
                req.setMoneyflowSource(moneyflowSource != null ? moneyflowSource : "WESTOCK");
                // 资金流向只跑资金相关，关掉其它
                req.setFetchLhb(false);
                req.setFetchMargin(false);
                req.setFetchSurvey(false);
                req.setFetchBlockTrade(false);
                req.setFetchActivity(false);
                req.setFetchZtPool(false);
                req.setFetchNotice(false);
                req.setFetchFundHolder(false);
                req.setFetchShareholder(false);
                req.setFetchNews(false);
                req.setFetchMoneyflow(true);
                yield req;
            }
            case "SENTIMENT_OTHER" -> {
                req.setUpdateType("SENTIMENT");
                // 其它情绪数据：关掉资金流向，开启其余
                req.setFetchMoneyflow(false);
                req.setFetchLhb(true);
                req.setFetchMargin(true);
                req.setFetchSurvey(true);
                req.setFetchBlockTrade(true);
                req.setFetchActivity(true);
                req.setFetchZtPool(true);
                req.setFetchNotice(true);
                req.setFetchFundHolder(true);
                req.setFetchShareholder(true);
                req.setFetchNews(true);
                yield req;
            }
            case "RESEARCH"      -> { req.setUpdateType("RESEARCH");      yield req; }
            case "RECOMMENDATION_TRACK" -> { /* P1-4: 已在executeTask中特殊处理 */ yield null; }
            default -> throw new IllegalArgumentException("未知任务类型: " + taskKey);
        };
    }
}
