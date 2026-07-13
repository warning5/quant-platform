package com.quant.platform.dataupdate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import com.quant.platform.notification.NotificationService;
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
    private final ApplicationContext applicationContext;
    // 懒加载注入，避免循环依赖
    private NotificationService notificationService;
    private DataQualityService dataQualityService;

    // taskKey → ScheduledFuture（用于动态取消）
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();
    // taskKey → 失败重试计数（key = taskKey + ":" + scheduledTime.toLocalDate()，同一天只重试一次）
    private final Map<String, Boolean> retryTracker = new ConcurrentHashMap<>();

    /** 今日已完成任务集合（用于依赖编排：避免重复触发） */
    private final Map<String, Boolean> todayCompleted = new ConcurrentHashMap<>();

    /** 手动触发的任务集合（key = taskKey + ":" + date），用于绕过下游 todayCompleted 去重 */
    private final Map<String, Boolean> manualTriggeredToday = new ConcurrentHashMap<>();

    /** 内存缓存：从 DB 加载的依赖链（upstream → List<{downstream, delayMs}>），定时刷新 */
    private volatile Map<String, List<Map<String, Object>>> dependencyChainCache = Map.of();

    private NotificationService getNotificationService() {
        if (notificationService == null) {
            notificationService = applicationContext.getBean(NotificationService.class);
        }
        return notificationService;
    }

    private DataQualityService getDataQualityService() {
        if (dataQualityService == null) {
            dataQualityService = applicationContext.getBean(DataQualityService.class);
        }
        return dataQualityService;
    }

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

        // 刷新依赖链缓存
        loadDependencyChain();
    }

    /**
     * 从 DB 加载任务依赖链到内存缓存，供 triggerDependents 使用
     */
    private void loadDependencyChain() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT upstream_key, downstream_key, delay_seconds FROM data_task_dependency"
            );
            Map<String, List<Map<String, Object>>> chain = new java.util.HashMap<>();
            for (Map<String, Object> row : rows) {
                String upstream = (String) row.get("upstream_key");
                chain.computeIfAbsent(upstream, k -> new java.util.ArrayList<>()).add(row);
            }
            dependencyChainCache = chain;
            log.info("[ScheduleService] 依赖链已刷新，共 {} 条依赖关系", rows.size());
        } catch (Exception e) {
            log.warn("[ScheduleService] 加载依赖链失败，将使用空依赖: {}", e.getMessage());
            dependencyChainCache = Map.of();
        }
    }

    /** 供 Controller 调用，新增/删除依赖后刷新缓存（不重新注册 cron） */
    public void refreshDependencyChain() {
        loadDependencyChain();
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
     * 手动触发任务：标记为手动触发，完成后绕过下游 todayCompleted 去重
     */
    public void executeTaskManual(String taskKey) {
        String manualKey = taskKey + ":" + LocalDate.now();
        manualTriggeredToday.put(manualKey, true);
        log.info("[ScheduleService] 手动触发任务: {} (将绕过下游去重)", taskKey);
        executeTask(taskKey);
    }

    /**
     * 执行任务：构建 DataUpdateRequest → 提交给 DataUpdateService（支持并发）
     * 完成后自动触发依赖链下游任务
     */
    public void executeTask(String taskKey) {
        try {
            // 质量检查任务：数据新鲜度
            if ("DATA_FRESHNESS".equals(taskKey)) {
                Map<String, Object> result = getDataQualityService().checkDataFreshness();
                log.info("[ScheduleService] 数据新鲜度检查完成: hasWarning={}", result.get("hasWarning"));
                updateTaskStatus(taskKey, "SUCCESS");
                // 质量检查结果通过 DataQualityService 内部推送
                triggerDependents(taskKey);
                return;
            }

            // 质量检查任务：价格异常检测
            if ("PRICE_ANOMALY".equals(taskKey)) {
                Map<String, Object> result = getDataQualityService().checkPriceAnomalies(7);
                log.info("[ScheduleService] 价格异常检测完成: count={}", result.get("anomalyCount"));
                updateTaskStatus(taskKey, "SUCCESS");
                triggerDependents(taskKey);
                return;
            }

            // 质量检查任务：因子NULL检测
            if ("FACTOR_NULL_CHECK".equals(taskKey)) {
                Map<String, Object> result = getDataQualityService().checkFactorNullRatio();
                log.info("[ScheduleService] 因子NULL检测完成: nullFactorCount={}", result.get("nullFactorCount"));
                updateTaskStatus(taskKey, "SUCCESS");
                triggerDependents(taskKey);
                return;
            }

            // 质量检查任务：财务突变检测
            if ("FINANCIAL_ANOMALY".equals(taskKey)) {
                Map<String, Object> result = getDataQualityService().checkFinancialAnomalies();
                log.info("[ScheduleService] 财务突变检测完成: count={}", result.get("anomalyCount"));
                updateTaskStatus(taskKey, "SUCCESS");
                triggerDependents(taskKey);
                return;
            }

            // P1-4: 推荐追踪任务
            if ("RECOMMENDATION_TRACK".equals(taskKey)) {
                int updated = recommendationService.trackRecommendationPerformance();
                log.info("[ScheduleService] 推荐追踪完成: 更新{}条", updated);
                updateTaskStatus(taskKey, "SUCCESS");
                triggerDependents(taskKey);
                return;
            }

            // Phase 2: 每日自动推荐任务
            if ("DAILY_RECOMMENDATION".equals(taskKey)) {
                executeDailyRecommendation();
                // executeDailyRecommendation 内部已更新状态
                triggerDependents(taskKey);
                return;
            }

            // 普通数据更新任务
            Map<String, Object> configRow = null;
            try {
                configRow = jdbcTemplate.queryForMap(
                    "SELECT extra_config FROM data_schedule_config WHERE task_key = ?", taskKey);
            } catch (Exception ignored) {}

            String extraConfigJson = configRow != null ? (String) configRow.get("extra_config") : null;
            DataUpdateRequest request = buildRequestFromKey(taskKey, extraConfigJson);
            dataUpdateService.submitTaskConcurrent(request);

            jdbcTemplate.update(
                "UPDATE data_schedule_config SET last_run_time = ?, last_run_status = 'RUNNING' " +
                "WHERE task_key = ?",
                LocalDateTime.now(), taskKey
            );
            log.info("[ScheduleService] 定时执行: {}", taskKey);
        } catch (Throwable t) {
            jdbcTemplate.update(
                "UPDATE data_schedule_config SET last_run_time = ?, last_run_status = 'FAILED' " +
                "WHERE task_key = ?",
                LocalDateTime.now(), taskKey
            );
            log.error("[ScheduleService] 定时执行失败: {}", taskKey, t);

            // P1-2.2: 失败后5分钟自动重试一次（同一天同一任务只重试一次）
            String retryKey = taskKey + ":" + LocalDate.now();
            if (retryTracker.putIfAbsent(retryKey, true) == null) {
                log.info("[ScheduleService] 5分钟后自动重试: {}", taskKey);
                taskScheduler.schedule(
                    () -> {
                        try {
                            log.info("[ScheduleService] 重试执行: {}", taskKey);
                            executeTask(taskKey);
                        } catch (Exception retryEx) {
                            log.error("[ScheduleService] 重试仍然失败: {}", taskKey, retryEx);
                        }
                    },
                    new java.util.Date(System.currentTimeMillis() + 5 * 60 * 1000)
                );
            } else {
                log.info("[ScheduleService] 今日已重试过，不再重试: {}", taskKey);
            }

            // 10: 失败告警推送通知
            sendFailureAlert(taskKey, t);
        }
    }

    /** 更新任务状态 */
    private void updateTaskStatus(String taskKey, String status) {
        jdbcTemplate.update(
            "UPDATE data_schedule_config SET last_run_time = ?, last_run_status = ?, last_run_duration_sec = 0 WHERE task_key = ?",
            LocalDateTime.now(), status, taskKey
        );
    }

    /** 10: 失败时推送告警通知 */
    private void sendFailureAlert(String taskKey, Throwable t) {
        try {
            String todayStr = LocalDate.now().toString();
            String alertKey = "FAIL_" + taskKey + "_" + todayStr;
            // 同一天同一任务只发一次告警
            if (retryTracker.putIfAbsent(alertKey, true) != null) return;

            String msg = String.format(
                "## 调度任务失败\n\n- 任务: %s\n- 时间: %s\n- 错误: %s\n\n系统将在5分钟后自动重试一次。",
                taskKey, LocalDateTime.now(), t.getMessage());
            getNotificationService().sendAlert(msg);
            log.info("[ScheduleService] 已发送失败告警: {}", taskKey);
        } catch (Exception ex) {
            log.warn("[ScheduleService] 告警推送失败: {}", ex.getMessage());
        }
    }

    /** 9: 依赖编排 — 触发下游任务 */
    private void triggerDependents(String taskKey) {
        // 清除当前任务的今日完成标记（允许下次重新触发）
        String doneKey = "DONE_" + taskKey + "_" + LocalDate.now();
        todayCompleted.put(doneKey, true);

        // 检查上游是否为手动触发（手动触发绕过下游去重）
        String manualKey = taskKey + ":" + LocalDate.now();
        boolean upstreamManual = manualTriggeredToday.containsKey(manualKey);

        // 从 DB 读取依赖链
        Map<String, List<Map<String, Object>>> chain = dependencyChainCache;
        List<Map<String, Object>> dependents = chain.get(taskKey);
        if (dependents == null || dependents.isEmpty()) return;

        for (Map<String, Object> dep : dependents) {
            String depKey = (String) dep.get("downstream_key");
            int delaySeconds = dep.get("delay_seconds") != null
                ? ((Number) dep.get("delay_seconds")).intValue() : 300;
            long delayMs = delaySeconds * 1000L;

            String depDoneKey = "DONE_" + depKey + "_" + LocalDate.now();
            if (todayCompleted.containsKey(depDoneKey)) {
                if (upstreamManual) {
                    // 手动触发：清除下游完成标记，允许重新执行
                    todayCompleted.remove(depDoneKey);
                    log.info("[ScheduleService] 手动触发：清除下游 {} 的今日完成标记，允许重新触发", depKey);
                } else {
                    log.info("[ScheduleService] 依赖任务 {} 今日已执行，跳过触发", depKey);
                    continue;
                }
            }

            // 检查该任务是否已启用
            try {
                Integer enabled = jdbcTemplate.queryForObject(
                    "SELECT enabled FROM data_schedule_config WHERE task_key = ?", Integer.class, depKey);
                if (enabled == null || enabled == 0) {
                    log.info("[ScheduleService] 依赖任务 {} 已禁用，跳过触发", depKey);
                    continue;
                }
            } catch (Exception ignored) {}

            final String fDepKey = depKey;
            log.info("[ScheduleService] {} 完成，{}ms 后触发下游: {}", taskKey, delayMs, depKey);
            taskScheduler.schedule(
                () -> {
                    try {
                        log.info("[ScheduleService] 依赖触发: {} (from {})", fDepKey, taskKey);
                        executeTask(fDepKey);
                    } catch (Exception ex) {
                        log.error("[ScheduleService] 依赖触发失败: {} (from {})", fDepKey, taskKey, ex);
                    }
                },
                new java.util.Date(System.currentTimeMillis() + delayMs)
            );
        }
    }

    /** 数据更新任务完成事件监听：实际完成后触发下游依赖 */
    @EventListener
    public void onDataUpdateCompleted(DataUpdateCompletedEvent event) {
        if (!event.isSuccess()) {
            log.warn("[ScheduleService] 任务 {} 执行失败，不触发下游依赖", event.getTaskKey());
            return;
        }
        log.info("[ScheduleService] 收到任务完成事件: {}, 耗时{}s", event.getTaskKey(), event.getDurationSeconds());
        triggerDependents(event.getTaskKey());
    }

    /**
     * 根据 taskKey 构建 DataUpdateRequest，解析 extra_config 中的增量/日期配置
     */
    private DataUpdateRequest buildRequestFromKey(String taskKey, String extraConfigJson) {
        DataUpdateRequest req = new DataUpdateRequest();
        req.setTaskKey(taskKey);  // 保存原始调度任务 key，用于依赖链事件触发
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
                    // 默认增量模式（未配 incremental 时为 true）；显式配 false 才走全量
                    incremental = !Boolean.FALSE.equals(ec.get("incremental"));
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
            case "FACTOR_COMPUTE" -> { req.setUpdateType("FACTOR_COMPUTE"); yield req; }
            case "RESEARCH"      -> { req.setUpdateType("RESEARCH");      yield req; }
            case "DATA_FRESHNESS"   -> { /* 质量检查: 已在executeTask中特殊处理 */ yield null; }
            case "PRICE_ANOMALY"    -> { /* 质量检查: 已在executeTask中特殊处理 */ yield null; }
            case "RECOMMENDATION_TRACK" -> { /* P1-4: 已在executeTask中特殊处理 */ yield null; }
            case "DAILY_RECOMMENDATION" -> { /* Phase 2: 已在executeTask中特殊处理 */ yield null; }
            default -> throw new IllegalArgumentException("未知任务类型: " + taskKey);
        };
    }

    /**
     * Phase 2: 每日自动推荐执行
     * 1. 读取 extra_config 中的 strategyIds
     * 2. 调用 RecommendationService 生成推荐（因子配置从数据库策略读取）
     * 3. 调用通知服务推送结果
     */
    private void executeDailyRecommendation() {
        long startTime = System.currentTimeMillis();
        log.info("[ScheduleService] 开始执行每日自动推荐");

        // 读取推荐配置
        Integer topN = 15;                       // 默认推荐15只
        java.util.List<String> weightModes = new java.util.ArrayList<>();  // 多权重模式
        String dateMode = "today";               // 日期模式
        List<Long> strategyIds = new java.util.ArrayList<>();
        boolean enableConfidenceControl = true;
        String customStartDate = null;           // custom 模式开始日期
        String customEndDate = null;             // custom 模式结束日期

        try {
            Map<String, Object> configRow = null;
            try {
                configRow = jdbcTemplate.queryForMap(
                    "SELECT extra_config FROM data_schedule_config WHERE task_key = 'DAILY_RECOMMENDATION'");
            } catch (Exception ignored) {}

            if (configRow != null && configRow.get("extra_config") != null) {
                String extraConfigJson = (String) configRow.get("extra_config");
                if (extraConfigJson != null && !extraConfigJson.isEmpty() && !extraConfigJson.equals("null")) {
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> ec = mapper.readValue(extraConfigJson, Map.class);
                    if (ec.get("topN") != null) topN = Integer.parseInt(ec.get("topN").toString());
                    if (ec.get("enableConfidenceControl") != null) enableConfidenceControl = Boolean.parseBoolean(ec.get("enableConfidenceControl").toString());
                    if (ec.get("dateMode") != null) dateMode = ec.get("dateMode").toString();
                    if (ec.get("startDate") != null) customStartDate = ec.get("startDate").toString();
                    if (ec.get("endDate") != null) customEndDate = ec.get("endDate").toString();
                    // 支持多策略: strategyIds 数组优先，向后兼容 strategyId
                    if (ec.get("strategyIds") instanceof java.util.List<?> list) {
                        for (Object id : list) {
                            if (id != null) strategyIds.add(Long.parseLong(id.toString()));
                        }
                    } else if (ec.get("strategyId") != null) {
                        strategyIds.add(Long.parseLong(ec.get("strategyId").toString()));
                    }
                    // 权重模式: weightModes 数组优先，向后兼容 weightMode
                    if (ec.get("weightModes") instanceof java.util.List<?> wmList) {
                        for (Object wm : wmList) {
                            if (wm != null) weightModes.add(wm.toString());
                        }
                    } else if (ec.get("weightMode") != null) {
                        weightModes.add(ec.get("weightMode").toString());
                    }
                    if (weightModes.isEmpty()) weightModes.add("ICW");  // 默认 ICW
                }
            }
        } catch (Exception e) {
            log.warn("[ScheduleService] 解析DAILY_RECOMMENDATION配置失败，使用默认值: {}", e.getMessage());
        }
        if (weightModes.isEmpty()) weightModes.add("ICW");

        // 根据 dateMode 构建待执行日期列表
        List<LocalDate> runDates = new java.util.ArrayList<>();
        try {
            switch (dateMode == null ? "today" : dateMode.toLowerCase()) {
                case "today":
                    runDates.add(LocalDate.now());
                    break;
                case "recent_1":
                    runDates.add(LocalDate.now().minusDays(1));
                    break;
                case "recent_3":
                    // 最近3个交易日（日历日倒推，跳过周末）
                    for (int i = 1; i <= 7 && runDates.size() < 3; i++) {
                        LocalDate d = LocalDate.now().minusDays(i);
                        if (!isWeekend(d)) runDates.add(d);
                    }
                    break;
                case "custom":
                    if (customStartDate != null && customEndDate != null) {
                        LocalDate sd = LocalDate.parse(customStartDate);
                        LocalDate ed = LocalDate.parse(customEndDate);
                        for (LocalDate d = sd; !d.isAfter(ed); d = d.plusDays(1)) {
                            if (!isWeekend(d)) runDates.add(d);
                        }
                    } else {
                        log.warn("[ScheduleService] dateMode=custom 但 startDate/endDate 为空，回退到 today");
                        runDates.add(LocalDate.now());
                    }
                    break;
                default:
                    runDates.add(LocalDate.now());
                    break;
            }
        } catch (Exception e) {
            log.warn("[ScheduleService] 解析日期失败，使用当天: {}", e.getMessage());
            runDates.add(LocalDate.now());
        }

        log.info("[ScheduleService] 推荐参数: runDates={}, dateMode={}, topN={}, strategyIds={}, weightModes={}",
                runDates, dateMode, topN, strategyIds, weightModes);

        // 逐日期 × 策略 × 权重模式 三层循环，每种组合分别生成独立快照
        java.util.List<com.quant.platform.recommendation.domain.StockRecommendation> allRecommendations = new java.util.ArrayList<>();
        boolean allSuccess = true;

        // 如果 extra_config 未指定 strategyIds，则自动查询所有 ACTIVE 策略
        if (strategyIds.isEmpty()) {
            try {
                List<Long> activeIds = jdbcTemplate.queryForList(
                    "SELECT id FROM strategy_definition WHERE status = 'ACTIVE' ORDER BY id", Long.class);
                strategyIds.addAll(activeIds);
                log.info("[ScheduleService] extra_config 未配置 strategyIds，自动发现 {} 个 ACTIVE 策略: {}",
                    strategyIds.size(), strategyIds);
            } catch (Exception e) {
                log.error("[ScheduleService] 查询 ACTIVE 策略失败: {}", e.getMessage());
            }
        }
        if (strategyIds.isEmpty()) {
            log.warn("[ScheduleService] DAILY_RECOMMENDATION 无可用策略（无配置且无ACTIVE策略），跳过执行");
            jdbcTemplate.update(
                "UPDATE data_schedule_config SET last_run_time = ?, last_run_status = 'SKIPPED' " +
                "WHERE task_key = 'DAILY_RECOMMENDATION'", LocalDateTime.now());
            return;
        }

        for (LocalDate runDate : runDates) {
            log.info("[ScheduleService] 开始处理日期: {} (共{}个日期)", runDate, runDates.size());
            for (Long strategyId : strategyIds) {
                for (String wm : weightModes) {
                    try {
                        List<com.quant.platform.recommendation.domain.StockRecommendation> recommendations =
                            recommendationService.generateRecommendations(
                                runDate, topN, strategyId, wm,
                                new java.util.ArrayList<>(), enableConfidenceControl);

                        log.info("[ScheduleService] 日期[{}] 策略[{}] 模式[{}] 推荐完成: 推荐数量={}",
                                runDate, strategyId, wm, recommendations.size());
                        allRecommendations.addAll(recommendations);

                    } catch (Throwable t) {
                        log.error("[ScheduleService] 日期[{}] 策略[{}] 模式[{}] 推荐执行失败", runDate, strategyId, wm, t);
                        allSuccess = false;
                    }
                }
            }
        }

        // 合并推送通知
        if (!allRecommendations.isEmpty()) {
            try {
                com.quant.platform.notification.NotificationService notificationService =
                    applicationContext.getBean(com.quant.platform.notification.NotificationService.class);
                notificationService.sendDailyRecommendation(allRecommendations,
                    strategyIds.size() > 1 ? "multi_strategy" : "strategy#" + strategyIds.getFirst());
            } catch (Exception e) {
                log.warn("[ScheduleService] 推送通知失败（NotificationService可能未配置）: {}", e.getMessage());
            }
        }

        // 更新调度状态
        long durationSec = (System.currentTimeMillis() - startTime) / 1000;
        jdbcTemplate.update(
            "UPDATE data_schedule_config SET last_run_time = ?, last_run_status = ?, last_run_duration_sec = ? " +
            "WHERE task_key = 'DAILY_RECOMMENDATION'",
            LocalDateTime.now(), allSuccess ? "SUCCESS" : "PARTIAL", durationSec);
        log.info("[ScheduleService] 每日自动推荐完成: 策略数={}, 推荐总数={}, 耗时={}s, 状态={}",
            strategyIds.size(), allRecommendations.size(), durationSec, allSuccess ? "SUCCESS" : "PARTIAL");
    }

    /** 判断是否周末（周六=6, 周日=7） */
    private static boolean isWeekend(LocalDate date) {
        return date.getDayOfWeek().getValue() >= 6;
    }
}
