package com.quant.platform.dataupdate;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quant.platform.stock.entity.StockInfo;
import com.quant.platform.stock.mapper.StockInfoMapper;
import com.quant.platform.stock.service.ClickHouseStockService;
import com.quant.platform.calendar.service.TradeCalendarService;
import com.quant.platform.factor.domain.FactorDefinition;
import com.quant.platform.factor.mapper.FactorDefinitionMapper;
import com.quant.platform.factor.service.FactorService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数据更新服务
 * 通过 ProcessBuilder 调用 Python 脚本，解析 stdout 实时推送进度
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataUpdateService {

    /**
     * 进度解析正则
     */
    private static final Pattern PROGRESS_PATTERN = Pattern.compile(
            "\\[(\\d+)/(\\d+)]");
    private static final Pattern RECORD_PATTERN = Pattern.compile(
            "(?:成功记录|成功[^0-9]{0,3}|已处理|写入|新增|更新)[^0-9]*(\\d[\\d,]*)");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final SimpMessagingTemplate messagingTemplate;
    private final StockInfoMapper stockInfoMapper;
    private final ClickHouseStockService clickHouseStockService;
    private final JdbcTemplate jdbcTemplate;
    private final FactorService factorService;

    @Autowired
    private FactorDefinitionMapper factorDefinitionMapper;

    @Autowired(required = false)
    private ApplicationEventPublisher eventPublisher;

    @Autowired(required = false)
    private TradeCalendarService tradeCalendarService;

    /**
     * 正在运行的任务
     */
    private final Map<String, DataUpdateTask> activeTasks = new ConcurrentHashMap<>();
    /**
     * 各类型最近完成的任务（页面刷新后恢复状态用）
     */
    private final ConcurrentHashMap<String, DataUpdateTask> recentFinishedTasks = new ConcurrentHashMap<>();
    /**
     * 各任务最近 500 条日志缓存（taskId -> 日志列表），供前端断线后补拉
     */
    private final ConcurrentHashMap<String, java.util.List<Map<String, Object>>> taskLogCache = new ConcurrentHashMap<>();
    /**
     * taskId -> updateType 映射（即使任务从 activeTasks 移除后仍可查到，确保日志分流正确）
     */
    private final ConcurrentHashMap<String, String> taskUpdateTypes = new ConcurrentHashMap<>();
    @Value("${quant.data-update.python-path:python}")
    private String pythonPath;
    @Value("${quant.data-update.script-dir:scripts}")
    private String scriptDir;
    /**
     * -- GETTER --
     * 获取当前活跃任务
     */
    @Getter
    @Value("${quant.data-update.default-start-days:3}")
    private int defaultStartDays;

    /** 脚本目录（绝对路径，启动时从相对路径解析） */
    private String resolvedScriptDir;

    /**
     * 初始化：将相对路径解析为绝对路径并验证
     */
    @PostConstruct
    public void init() {
        File dir = new File(scriptDir);
        if (!dir.isAbsolute()) {
            // 相对于项目根目录 (Claw/) 解析
            Path absolute = Paths.get(System.getProperty("user.dir"), scriptDir).toAbsolutePath().normalize();
            dir = absolute.toFile();
        }

        if (dir.exists() && dir.isDirectory()) {
            resolvedScriptDir = dir.getAbsolutePath();
            log.info("[DataUpdate] 脚本目录: {}", resolvedScriptDir);
            // 检查关键脚本是否存在
            String[] scripts = {"update_stock_data.py", "update_stock_daily_baostock.py",
                    "update_bj_stock_daily_qq.py", "update_index_daily_baostock.py",
                    "update_dividend_baostock.py", "update_research_report.py"};
            for (String s : scripts) {
                File f = new File(resolvedScriptDir, s);
                log.info("[DataUpdate]   {} : {}", s, f.exists() ? "OK" : "MISSING");
            }
        } else {
            // 尝试从 Claw 工作目录解析
            Path fallback = Paths.get(System.getProperty("user.dir"), "..", "..", "update_data").toAbsolutePath().normalize();
            if (fallback.toFile().exists()) {
                resolvedScriptDir = fallback.toString();
                log.warn("[DataUpdate] script-dir '{}' 解析失败，使用回退路径: {}", scriptDir, resolvedScriptDir);
            } else {
                log.error("[DataUpdate] 脚本目录不存在: {} (也尝试过 {})", dir.getAbsolutePath(), fallback);
                log.error("[DataUpdate] 请在 application.yml 中设置 quant.data-update.script-dir 为绝对路径");
            resolvedScriptDir = null;
        }

        // ★ 清理上次异常退出（重启/崩溃）遗留的 RUNNING 状态
        try {
            int cleaned = jdbcTemplate.update(
                "UPDATE data_schedule_config SET last_run_status = 'INTERRUPTED', updated_at = ? " +
                "WHERE last_run_status = 'RUNNING'",
                LocalDateTime.now()
            );
            if (cleaned > 0) {
                log.info("[DataUpdate] 启动时清理了 {} 条残留 RUNNING 任务状态 → INTERRUPTED", cleaned);
            }
        } catch (Exception e) {
            log.warn("[DataUpdate] 清理残留 RUNNING 状态失败: {}", e.getMessage());
        }
    }
    }

    /**
     * 提交数据更新任务（有单任务互斥锁，用于数据更新UI页面）
     */
    public synchronized DataUpdateTask submitTask(DataUpdateRequest request) {
        // 检查是否有任务正在运行
        if (activeTasks.values().stream().anyMatch(DataUpdateTask::isRunning)) {
            throw new IllegalStateException("已有任务正在运行，请等待完成或取消");
        }

        return doSubmit(request);
    }

    /**
     * 提交数据更新任务（无单任务限制，支持并发，用于定时调度）
     * 定时任务场景下多个任务可同时执行
     */
    public synchronized DataUpdateTask submitTaskConcurrent(DataUpdateRequest request) {
        return doSubmit(request);
    }

    /**
     * 内部统一提交逻辑
     */
    private DataUpdateTask doSubmit(DataUpdateRequest request) {
        String taskId = "TASK-" + System.currentTimeMillis();
        DataUpdateTask task = new DataUpdateTask();
        task.setTaskId(taskId);
        task.setRequest(request);
        task.setStatus("RUNNING");
        task.setStartTime(LocalDateTime.now());
        task.setCurrentStep("准备启动...");
        activeTasks.put(taskId, task);
        // 记录 updateType 映射（即使任务从 activeTasks 移除后仍可查到，确保日志分流正确）
        if (request.getUpdateType() != null) {
            taskUpdateTypes.put(taskId, request.getUpdateType());
        }

        // 在新线程中执行
        Thread worker = new Thread(() -> executeTask(taskId, request), "data-update-" + taskId);
        worker.setDaemon(true);
        worker.start();

        return task;
    }

    /**
     * 获取任务状态
     */
    public DataUpdateTask getTaskStatus(String taskId) {
        return activeTasks.get(taskId);
    }

    public DataUpdateTask getCurrentTask() {
        return activeTasks.values().stream()
                .filter(DataUpdateTask::isRunning)
                .findFirst()
                .orElse(null);
    }

    /**
     * 获取各类型最近的任务（页面刷新后恢复状态用）
     */
    public List<DataUpdateTask> getRecentTasks() {
        List<DataUpdateTask> result = new ArrayList<>();
        // 先收集当前运行中的任务
        for (DataUpdateTask t : activeTasks.values()) {
            if (t.getRequest() != null) {
                result.add(t);
            }
        }
        // 再收集最近完成的（补充）
        for (DataUpdateTask t : recentFinishedTasks.values()) {
            String ut = t.getRequest() != null ? t.getRequest().getUpdateType() : null;
            // 只在当前没有运行中任务时才添加已完成任务
            boolean hasRunning = result.stream()
                    .anyMatch(r -> r.getRequest() != null &&
                            ut != null && ut.equals(r.getRequest().getUpdateType()));
            if (!hasRunning) {
                result.add(t);
            }
        }
        return result;
    }

    /**
     * 检测 DB 中的孤儿 RUNNING 定时任务（进程已死但 DB 状态未清理）
     * 用于 DataUpdate 页面刷新后恢复状态显示
     */
    public List<Map<String, Object>> getScheduledRunningTasks() {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT task_key, task_name, last_run_time, last_run_status, extra_config " +
                "FROM data_schedule_config WHERE last_run_status = 'RUNNING'"
            );
            for (Map<String, Object> row : rows) {
                String taskKey = (String) row.get("task_key");
                // 如果内存中已有该类型的运行中任务，说明不是孤儿，跳过
                // ★ 同时匹配 taskKey（SENTIMENT_MF）和 updateType（SENTIMENT），确保子任务也能匹配
                boolean hasInMemory = activeTasks.values().stream()
                    .anyMatch(t -> t.getRequest() != null &&
                        (taskKey.equals(t.getRequest().getTaskKey()) || taskKey.equals(t.getRequest().getUpdateType())));
                if (hasInMemory) {
                    continue;
                }
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("taskKey", taskKey);
                info.put("name", row.get("task_name"));
                info.put("lastRunTime", row.get("last_run_time").toString());
                info.put("status", "RUNNING");
                info.put("isOrphan", true);  // 标记为孤儿（DB 说在跑但内存中没有）
                result.add(info);
            }
        } catch (Exception e) {
            log.warn("[DataUpdate] 查询孤儿 RUNNING 任务失败: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 取消任务
     */
    public synchronized boolean cancelTask(String taskId) {
        DataUpdateTask task = activeTasks.get(taskId);
        if (task == null || !task.isRunning()) return false;

        task.setStatus("CANCELLED");
        task.setEndTime(LocalDateTime.now());
        task.setCurrentStep("用户取消");

        // 使用任务自身持有的进程引用（不再依赖共享的 currentProcess 变量，
        // 解决多任务并发时共享变量被覆盖导致杀错进程的 bug）
        Process targetProcess = task.getProcess();
        long targetPid = task.getProcessPid();
        if (targetProcess != null && targetProcess.isAlive() && targetPid > 0) {
            try {
                boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
                if (isWindows) {
                    new ProcessBuilder("taskkill", "/F", "/T", "/PID", String.valueOf(targetPid)).start();
                } else {
                    Runtime.getRuntime().exec(new String[]{"kill", "-TERM", "-" + targetPid});
                }
            } catch (IOException ignored) {
            }
            // 等待进程真正退出（最多 3 秒）
            try {
                targetProcess.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        }

        // 从活跃任务中移除，并保存到最近完成
        String ut = task.getRequest() != null ? task.getRequest().getUpdateType() : null;
        if (ut != null && !ut.isEmpty()) {
            recentFinishedTasks.put(ut, task);
        }
        activeTasks.remove(taskId);

        // ★ 直接更新 DB 状态为 CANCELLED（不依赖后台线程 finally 块，
        // 避免 finally 被异常路径覆盖或线程卡住导致 DB 永远停留在 RUNNING）
        String dbKey = resolveDbTaskKey(task.getRequest());
        if (dbKey != null && !dbKey.isEmpty()) {
            try {
                int rows = jdbcTemplate.update(
                    "UPDATE data_schedule_config SET last_run_status='CANCELLED', updated_at=? WHERE task_key=?",
                    LocalDateTime.now(), dbKey
                );
                log.info("[DataUpdate] ★ cancelTask 直接回写 DB: task_key={}, rows={}", dbKey, rows);
            } catch (Exception dbEx) {
                log.error("[DataUpdate] ★★ cancelTask 回写 DB 失败!! task_key={}, error: {}", dbKey, dbEx.getMessage());
            }
        }

        broadcastStatus(task);
        return true;
    }

    /**
     * 清理孤儿 RUNNING 任务（DB 状态卡在 RUNNING 但内存中无对应任务）
     * 前端传入 taskKey（如 DAILY、FINANCIAL），直接更新 DB
     */
    public boolean cancelOrphanTask(String taskKey) {
        try {
            int rows = jdbcTemplate.update(
                "UPDATE data_schedule_config SET last_run_status='INTERRUPTED', updated_at=? " +
                "WHERE task_key=? AND last_run_status='RUNNING'",
                LocalDateTime.now(), taskKey
            );
            if (rows > 0) {
                log.info("[DataUpdate] ★ 清理孤儿任务 DB 状态: task_key={}, rows={}", taskKey, rows);
                return true;
            } else {
                log.warn("[DataUpdate] 清理孤儿任务未匹配（可能已被其他请求清理）: task_key={}", taskKey);
                return false;
            }
        } catch (Exception e) {
            log.error("[DataUpdate] ★★ 清理孤儿任务失败!! task_key={}, error: {}", taskKey, e.getMessage());
            return false;
        }
    }

    /**
     * 根据 updateType 取消正在运行的任务
     */
    public synchronized boolean cancelByUpdateType(String updateType) {
        for (DataUpdateTask task : activeTasks.values()) {
            if (task.isRunning() && task.getRequest() != null
                && updateType.equals(task.getRequest().getUpdateType())) {
                return cancelTask(task.getTaskId());
            }
        }
        return false;
    }

    /**
     * 执行任务
     */
    private void executeTask(String taskId, DataUpdateRequest request) {
        DataUpdateTask task = activeTasks.get(taskId);
        try {
            String updateType = request.getUpdateType();
            List<String> cmd = buildCommand(request);

            if ("INDEX".equals(updateType)) {
                // 指数日线：单次执行 update_index_daily_baostock.py
                task.setTotalStocks(10); // 10个指数
                task.setCurrentStep("指数日线");
                broadcastStatus(task);
                boolean indexOk = runSingleScript(taskId, task, cmd, "指数日线");
                if (!"CANCELLED".equals(task.getStatus())) {
                    task.setStatus(indexOk ? "SUCCESS" : "FAILED");
                    task.setProgress(100);
                    task.setCurrentStep(indexOk ? "更新完成" : "更新失败");
                }
            } else if ("DIVIDEND".equals(updateType)) {
                // 分红除权：单次执行 update_dividend_baostock.py
                task.setTotalStocks(estimateTotalStocks(request));
                task.setCurrentStep("分红除权");
                broadcastStatus(task);
                boolean divOk = runSingleScript(taskId, task, cmd, "分红除权");
                if (!"CANCELLED".equals(task.getStatus())) {
                    task.setStatus(divOk ? "SUCCESS" : "FAILED");
                    task.setProgress(100);
                    task.setCurrentStep(divOk ? "更新完成" : "更新失败");
                }
            } else if ("FINANCIAL".equals(updateType)) {
                // 财务数据：执行 update_financial_data.py
                task.setTotalStocks(1);
                task.setCurrentStep("财务数据");
                broadcastStatus(task);
                String singleCode = request.getSingleCode();
                if (singleCode != null && !singleCode.isEmpty()) {
                    // 单只股票：先 ths，再 sina
                    List<String> thsCmd = new ArrayList<>();
                    thsCmd.add(pythonPath);
                    thsCmd.add("-u");
                    thsCmd.add("update_financial_data.py");
                    thsCmd.add("--step");
                    thsCmd.add("ths");
                    thsCmd.add("--code");
                    thsCmd.add(singleCode);
                    if (request.isForce()) thsCmd.add("--force");
                    task.setCurrentStep("财务数据 · 同花顺摘要");
                    broadcastStatus(task);
                    boolean thsOk = runSingleScript(taskId, task, thsCmd, "财务-同花顺");

                    List<String> sinaCmd = new ArrayList<>();
                    sinaCmd.add(pythonPath);
                    sinaCmd.add("-u");
                    sinaCmd.add("update_financial_data.py");
                    sinaCmd.add("--step");
                    sinaCmd.add("sina");
                    sinaCmd.add("--code");
                    sinaCmd.add(singleCode);
                    if (request.isForce()) sinaCmd.add("--force");
                    task.setCurrentStep("财务数据 · 新浪三大表");
                    broadcastStatus(task);
                    boolean sinaOk = runSingleScript(taskId, task, sinaCmd, "财务-新浪");

                    if (!"CANCELLED".equals(task.getStatus())) {
                        task.setStatus(thsOk && sinaOk ? "SUCCESS" : "FAILED");
                        task.setProgress(100);
                        task.setCurrentStep(thsOk && sinaOk ? "采集完成" : "部分失败");
                    }
                } else {
                    boolean finOk = runSingleScript(taskId, task, cmd, "财务数据");
                    if (!"CANCELLED".equals(task.getStatus())) {
                        task.setStatus(finOk ? "SUCCESS" : "FAILED");
                        task.setProgress(100);
                        task.setCurrentStep(finOk ? "采集完成" : "采集失败");
                    }
                }
            } else if ("SENTIMENT".equals(updateType)) {
                // 情绪数据：执行 update_sentiment_data.py
                task.setTotalStocks(1);
                task.setCurrentStep("情绪数据");
                broadcastStatus(task);
                boolean senOk = runSingleScript(taskId, task, cmd, "情绪数据");

                // 串行执行国债收益率脚本
                if (senOk && request.isFetchBondYield() && !"CANCELLED".equals(task.getStatus())) {
                    List<String> bondCmd = new ArrayList<>();
                    bondCmd.add(pythonPath);
                    bondCmd.add("-u");
                    bondCmd.add("update_bond_yield.py");
                    if (request.isForce()) bondCmd.add("--force");
                    task.setCurrentStep("情绪数据 · 国债收益率");
                    broadcastStatus(task);
                    boolean bondOk = runSingleScript(taskId, task, bondCmd, "国债收益率");
                    if (!bondOk) {
                        broadcastLog(taskId, "[WARN] 国债收益率采集失败，继续执行后续任务...");
                        senOk = false; // 标记部分失败
                    }
                }

                // 串行执行申万行业指数脚本
                if (request.isFetchShenwanIndex() && !"CANCELLED".equals(task.getStatus())) {
                    List<String> swCmd = new ArrayList<>();
                    swCmd.add(pythonPath);
                    swCmd.add("-u");
                    swCmd.add("update_shenwan_index.py");
                    // 传递日期范围
                    String startDate = request.getStartDate();
                    String endDate = request.getEndDate();
                    if (startDate != null && !startDate.isEmpty()) {
                        swCmd.add("--start-date");
                        swCmd.add(startDate);
                    }
                    if (endDate != null && !endDate.isEmpty()) {
                        swCmd.add("--end-date");
                        swCmd.add(endDate);
                    }
                    if (request.isForce()) swCmd.add("--force");
                    task.setCurrentStep("情绪数据 · 申万行业指数");
                    broadcastStatus(task);
                    boolean swOk = runSingleScript(taskId, task, swCmd, "申万行业指数");
                    if (!swOk) {
                        broadcastLog(taskId, "[WARN] 申万行业指数采集失败");
                        senOk = false; // 标记部分失败
                    }
                }

                // 串行执行一致预期脚本（同花顺）
                if (request.isFetchConsensusEstimate() && !"CANCELLED".equals(task.getStatus())) {
                    List<String> ceCmd = new ArrayList<>();
                    ceCmd.add(pythonPath);
                    ceCmd.add("-u");
                    ceCmd.add("update_consensus_estimate.py");
                    task.setCurrentStep("情绪数据 · 一致预期");
                    broadcastStatus(task);
                    boolean ceOk = runSingleScript(taskId, task, ceCmd, "一致预期");
                    if (!ceOk) {
                        broadcastLog(taskId, "[WARN] 一致预期采集失败，继续执行后续任务...");
                        senOk = false;
                    }
                }

                // 串行执行业绩快报脚本（东方财富）
                if (request.isFetchEarningsReport() && !"CANCELLED".equals(task.getStatus())) {
                    List<String> erCmd = new ArrayList<>();
                    erCmd.add(pythonPath);
                    erCmd.add("-u");
                    erCmd.add("update_earnings_report.py");
                    task.setCurrentStep("情绪数据 · 业绩快报");
                    broadcastStatus(task);
                    boolean erOk = runSingleScript(taskId, task, erCmd, "业绩快报");
                    if (!erOk) {
                        broadcastLog(taskId, "[WARN] 业绩快报采集失败");
                        senOk = false;
                    }
                }

                // 串行执行 QVIX 中国恐慌指数采集脚本
                if (request.isFetchQvix() && !"CANCELLED".equals(task.getStatus())) {
                    List<String> qvixCmd = new ArrayList<>();
                    qvixCmd.add(pythonPath);
                    qvixCmd.add("-u");
                    qvixCmd.add("collect_qvix.py");
                    task.setCurrentStep("情绪数据 · QVIX恐慌指数");
                    broadcastStatus(task);
                    boolean qvixOk = runSingleScript(taskId, task, qvixCmd, "QVIX恐慌指数");
                    if (!qvixOk) {
                        broadcastLog(taskId, "[WARN] QVIX采集失败，不影响其他数据");
                        // QVIX 失败不影响整体状态（辅助数据）
                    }
                }

                if (!"CANCELLED".equals(task.getStatus())) {
                    task.setStatus(senOk ? "SUCCESS" : "FAILED");
                    task.setProgress(100);
                    task.setCurrentStep(senOk ? "采集完成" : "部分采集失败");
                }
            } else if ("BIDASK".equals(updateType)) {
                // 内外盘数据：执行 update_stock_data.py --bidask-only
                task.setTotalStocks(5248);
                task.setCurrentStep("内外盘数据");
                broadcastStatus(task);
                boolean bidaskOk = runSingleScript(taskId, task, cmd, "内外盘数据");
                if (!"CANCELLED".equals(task.getStatus())) {
                    task.setStatus(bidaskOk ? "SUCCESS" : "FAILED");
                    task.setProgress(100);
                    task.setCurrentStep(bidaskOk ? "采集完成" : "采集失败");
                    // 成功后从数据库查询日期维度统计
                    if (bidaskOk) {
                        LocalDate endDate = (request.getEndDate() != null && !request.getEndDate().isEmpty())
                            ? LocalDate.parse(request.getEndDate()) : LocalDate.now();
                        LocalDate startDate = (request.getStartDate() != null && !request.getStartDate().isEmpty())
                            ? LocalDate.parse(request.getStartDate()) : endDate;
                        task.setBidAskStats(loadBidAskStats(startDate, endDate));
                        broadcastStatus(task);
                    }
                }
            } else if ("FACTOR_COMPUTE".equals(updateType)) {
                // 因子计算：直接调 Java 端 FactorService（进程内，无需启 Python 子进程）
                // FactorService 内部已实现：全量K线预加载、增量模式、WebSocket 实时广播
                task.setCurrentStep("因子计算");
                broadcastStatus(task);
                try {
                    LocalDate endDate = (request.getEndDate() != null && !request.getEndDate().isEmpty())
                        ? LocalDate.parse(request.getEndDate()) : LocalDate.now();
                    LocalDate startDate = (request.getStartDate() != null && !request.getStartDate().isEmpty())
                        ? LocalDate.parse(request.getStartDate()) : endDate.minusDays(defaultStartDays);
                    List<FactorDefinition> activeFactors =
                        factorDefinitionMapper.selectList(new LambdaQueryWrapper<FactorDefinition>()
                            .eq(FactorDefinition::getStatus,
                                FactorDefinition.FactorStatus.ACTIVE)
                            .orderByAsc(FactorDefinition::getFactorCode));
                    List<String> factorCodes = activeFactors.stream()
                        .map(FactorDefinition::getFactorCode)
                        .collect(java.util.stream.Collectors.toList());
                    task.setTotalStocks(factorCodes.size());
                    broadcastStatus(task);
                    java.util.Map<String, Object> result = factorService.triggerBatchCompute(
                        factorCodes, startDate, endDate, true, false);
                    // triggerBatchCompute 返回 submitted/skipped 为 List<String>（因子代码列表），取 size
                    Object subObj = result.getOrDefault("submitted", java.util.Collections.emptyList());
                    long submitted = subObj instanceof Number ? ((Number) subObj).longValue() : subObj instanceof List<?> l ? l.size() : 0;
                    Object skipObj = result.getOrDefault("skipped", java.util.Collections.emptyList());
                    long skipped = skipObj instanceof Number ? ((Number) skipObj).longValue() : skipObj instanceof List<?> l ? l.size() : 0;
                    task.setCurrentStep(String.format("计算完成（提交 %d, 跳过 %d）", submitted, skipped));
                    task.setStatus("SUCCESS");
                } catch (Exception e) {
                    log.error("[因子计算] 失败", e);
                    task.setCurrentStep("计算失败: " + e.getMessage());
                    task.setStatus("FAILED");
                }
                task.setProgress(100);
                task.setCurrentStep("计算完成");
            } else if (cmd == null) {
                // ALL → 依次执行 SH、SZ、BJ
                executeAllMarkets(taskId, request);
            } else if (cmd.size() >= 3 && "update_stock_info_daily.py".equals(cmd.getLast())) {
                // infoOnly 模式：只执行 stock_info 脚本
                task.setTotalStocks(5500);
                task.setCurrentStep("股票信息");
                broadcastStatus(task);
                boolean ok = runSingleScript(taskId, task, cmd, "股票信息");
                if (!"CANCELLED".equals(task.getStatus())) {
                    task.setStatus(ok ? "SUCCESS" : "FAILED");
                    task.setProgress(100);
                    task.setCurrentStep(ok ? "更新完成" : "更新失败");
                }
            } else {
                task.setTotalStocks(estimateTotalStocks(request));
                // 根据请求参数推断市场名称
                String marketLabel = null;
                String m = request.getMarket();
                if ("SH".equals(m)) marketLabel = "沪市";
                else if ("SZ".equals(m)) marketLabel = "深市";
                else if ("BJ".equals(m)) marketLabel = "北交所";
                else if ("BAOSTOCK".equals(request.getSource())) marketLabel = "沪深";
                else if ("TENCENT".equals(request.getSource())) marketLabel = "北交所";
                task.setCurrentStep(marketLabel != null ? marketLabel : "启动中...");
                broadcastStatus(task);
                log.info("[DataUpdate] 启动任务 {}: {}", taskId, cmd);
                boolean ok = runSingleScript(taskId, task, cmd, marketLabel);

                // 不再使用 akshare 作为备用数据源
                if (!"CANCELLED".equals(task.getStatus())) {
                    task.setStatus(ok ? "SUCCESS" : "FAILED");
                    task.setProgress(100);
                    task.setCurrentStep(ok ? "更新完成" : "更新失败");
                }
            }
        } catch (Exception e) {
            log.error("[DataUpdate] 任务 {} 异常", taskId, e);
            // 不覆盖 CANCELLED 状态（用户已手动取消时保留取消状态）
            if (!"CANCELLED".equals(task.getStatus())) {
                task.setStatus("FAILED");
                task.setError(e.getMessage());
                task.setCurrentStep("执行异常: " + e.getMessage());
            }
            broadcastLog(taskId, "[ERROR] " + e.getMessage());
        } finally {
            task.setEndTime(LocalDateTime.now());
            // 保存到最近完成的任务（按 updateType 分组）
            String ut = request.getUpdateType();
            if (ut != null && !ut.isEmpty()) {
                recentFinishedTasks.put(ut, task);
            }
            // 从活跃任务中移除（避免阻止新任务启动）
            activeTasks.remove(taskId);
            // 延迟清理 taskUpdateTypes（5分钟后移除，确保任务结束后残留日志仍能正确分流）
            final String taskIdFinal = taskId;
            new java.util.Timer().schedule(new java.util.TimerTask() {
                @Override public void run() { taskUpdateTypes.remove(taskIdFinal); }
            }, 5 * 60 * 1000L);
            // 清理进程引用
            task.setProcess(null);
            task.setProcessPid(-1);
            broadcastStatus(task);
            log.info("[DataUpdate] 任务 {} 结束, 状态: {}", taskId, task.getStatus());

            // ★ 回写 data_schedule_config 的 last_run_status（让定时任务页面能正确显示最终状态）
            // 使用 taskKey（如 SENTIMENT_MF）而非 updateType（如 SENTIMENT）匹配 DB 行
            String dbKey = resolveDbTaskKey(request);
            long durationSec = 0;
            try {
                String finalStatus = task.getStatus();
                durationSec = java.time.Duration.between(task.getStartTime(), task.getEndTime()).getSeconds();
                // 直接按 task_key 更新，不依赖 RUNNING 条件（更健壮）
                int rows = jdbcTemplate.update(
                    "UPDATE data_schedule_config SET last_run_status=?, last_run_duration_sec=?, updated_at=? " +
                    "WHERE task_key=?",
                    finalStatus, durationSec, LocalDateTime.now(), dbKey
                );
                if (rows > 0) {
                    log.info("[DataUpdate] ★ 回写 DB: task_key={}, status={}, 耗时{}s", dbKey, finalStatus, durationSec);
                } else {
                    log.warn("[DataUpdate] 回写 DB 未匹配任何行: task_key={}", dbKey);
                }
            } catch (Exception dbEx) {
                log.error("[DataUpdate] ★★ 回写 schedule_config 失败!! task_key={}, error: {}", dbKey, dbEx.getMessage());
            }

            // ★ 发布任务完成事件，供依赖调度使用
            if (eventPublisher != null && ut != null && !ut.isEmpty()) {
                boolean taskOk = "SUCCESS".equals(task.getStatus());
                // 使用原始调度任务 key，确保 SENTIMENT_MF/SENTIMENT_OTHER 等子任务能正确触发依赖链
                String eventKey = request.getTaskKey() != null && !request.getTaskKey().isEmpty()
                        ? request.getTaskKey() : ut;
                eventPublisher.publishEvent(new DataUpdateCompletedEvent(this, eventKey, taskOk, durationSec));
            }
        }
    }

    /**
     * 解析 DB 回写的 task_key：优先使用 request.taskKey（如 SENTIMENT_MF），
     * 无值时 fallback 到 updateType（如 SENTIMENT）。
     * 确保 DB data_schedule_config 行的 task_key 正确匹配。
     */
    private String resolveDbTaskKey(DataUpdateRequest request) {
        if (request == null) return null;
        String taskKey = request.getTaskKey();
        if (taskKey != null && !taskKey.isEmpty()) return taskKey;
        return request.getUpdateType();
    }

    /**
     * 依次执行 SH → SZ → BJ
     */
    private void executeAllMarkets(String taskId, DataUpdateRequest request) throws IOException, InterruptedException {
        DataUpdateTask task = activeTasks.get(taskId);
        broadcastLog(taskId, "========== 开始全部市场更新 ==========");
        // totalStocks 由各市场脚本日志动态更新，不再预估值
        task.setProcessedStocks(0);
        task.setTotalStocks(0);
        boolean allSuccess = true;

        // infoOnly 模式：只更新 stock_info，跳过所有日线脚本
        if (request.isInfoOnly()) {
            broadcastLog(taskId, "[INFO] 仅更新 stock_info，跳过日线行情...");
            boolean ok = runUpdateStockInfo(taskId, task);
            if (!"CANCELLED".equals(task.getStatus())) {
                task.setStatus(ok ? "SUCCESS" : "FAILED");
                task.setProgress(100);
                task.setCurrentStep(ok ? "全部完成" : "部分失败");
            }
            return;
        }

        // 根据数据源决定更新哪些市场
        List<String[]> marketScripts = new ArrayList<>();
        String pool = request.getStockPool();
        boolean hasPoolFilter = pool != null && !"ALL".equals(pool);

        if (!"BJ".equals(request.getMarket())) {
            // BAOSTOCK 或 ALL → 串行更新沪深（Baostock不支持多并发连接）
            marketScripts.add(new String[]{"沪深", "update_stock_daily_baostock.py", "--workers", "1"});
        }
        // 指定股票池时只更新池内股票池（SH/SZ），跳过北交所
        if (!"BAOSTOCK".equals(request.getSource()) && !hasPoolFilter) {
            // 非 BAOSTOCK 独占且未指定股票池 → 更新北交所
            marketScripts.add(new String[]{"北交所", "update_bj_stock_daily_qq.py"});
        }

        // 所有市场顺序执行
        for (String[] ms : marketScripts) {
            if ("CANCELLED".equals(task.getStatus())) break;
            task.setCurrentStep(ms[0]);
            task.setProcessedStocks(0);
            task.setTotalStocks(0);
            task.setProgress(0);
            broadcastStatus(task);
            broadcastLog(taskId, "\n========== " + ms[0] + " ==========");

            List<String> scriptCmd = new ArrayList<>();
            scriptCmd.add(pythonPath);
            scriptCmd.add("-u");
            scriptCmd.add(ms[1]);
            // 添加所有额外参数（--market SH, --workers 4 等）
            for (int i = 2; i < ms.length; i++) {
                scriptCmd.add(ms[i]);
            }
            addCommonArgs(scriptCmd, request);
            boolean ok = runSingleScript(taskId, task, scriptCmd, ms[0]);

            // 不再使用 akshare 作为备用数据源
            if (!ok) allSuccess = false;
        }

        // ─── Part 1.5: 更新指数日线（仅非 dailyOnly 时）────────────
        if (!request.isDailyOnly() && !"CANCELLED".equals(task.getStatus())) {
            broadcastLog(taskId, "\n========== 指数日线 ==========");
            task.setCurrentStep("指数日线");
            task.setProcessedStocks(0);
            task.setTotalStocks(10); // 10个指数
            task.setProgress(0);
            broadcastStatus(task);

            List<String> indexCmd = new ArrayList<>();
            indexCmd.add(pythonPath);
            indexCmd.add("-u");
            indexCmd.add("update_index_daily_baostock.py");
            // 透传日期参数
            String idxStart = request.getStartDate();
            String idxEnd = request.getEndDate();
            // 指数默认用更长的历史起始
            if ((idxStart == null || idxStart.isEmpty()) && (idxEnd == null || idxEnd.isEmpty())) {
                java.time.LocalDate today = java.time.LocalDate.now();
                idxStart = "2018-01-02";
                idxEnd = today.toString();
            }
            if (idxStart != null && !idxStart.isEmpty()) {
                indexCmd.add("--start-date");
                indexCmd.add(idxStart);
            }
            if (idxEnd != null && !idxEnd.isEmpty()) {
                indexCmd.add("--end-date");
                indexCmd.add(idxEnd);
            }
            if (request.isForce()) indexCmd.add("--force");

            boolean indexOk = runSingleScript(taskId, task, indexCmd, "指数日线");
            if (!indexOk) allSuccess = false;
        }

        // ─── 自动执行 OPTIMIZE TABLE FINAL 去重 ─────────────────────
        if (!"CANCELLED".equals(task.getStatus())) {
            optimizeClickHouseTable(taskId);
        }

        broadcastLog(taskId, "\n========== 全部完成 ==========");
        if (!"CANCELLED".equals(task.getStatus())) {
            task.setStatus(allSuccess ? "SUCCESS" : "FAILED");
            task.setProgress(100);
            task.setCurrentStep(allSuccess ? "全部完成" : "部分失败");
        }
    }

    /**
     * 执行 ClickHouse OPTIMIZE TABLE FINAL 去重
     * 通过 Python clickhouse_connect 库执行（比 curl 更可靠，正确传递 receive_timeout/max_execution_time）
     */
    private void optimizeClickHouseTable(String taskId) {
        broadcastLog(taskId, "\n[OPTIMIZE] 开始合并去重（可能需要几分钟）...");
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(pythonPath);
            cmd.add("-u");
            cmd.add("-c");
            cmd.add("from field_completer import run_optimize_stock_daily; run_optimize_stock_daily()");
            
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(scriptDir));
            pb.redirectErrorStream(true);
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            pb.environment().put("DB_BACKEND", "clickhouse");
            Process proc = pb.start();
            
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    broadcastLog(taskId, line);
                }
            }
            
            int exitCode = proc.waitFor();
            if (exitCode == 0) {
                broadcastLog(taskId, "[OPTIMIZE] ✅ 合并去重完成");
                log.info("[DataUpdate] OPTIMIZE TABLE stock_daily FINAL 完成");
            } else {
                broadcastLog(taskId, "[OPTIMIZE] ⚠️ 合并失败，退出码: " + exitCode);
                broadcastLog(taskId, "[OPTIMIZE] 数据写入不受影响，可稍后手动执行");
            }
        } catch (Exception e) {
            broadcastLog(taskId, "[OPTIMIZE] ⚠️ 合并失败: " + e.getMessage());
            log.error("[DataUpdate] OPTIMIZE 失败: {}", e.getMessage());
        }
    }

    /**
     * 执行单个脚本，返回是否成功
     */
    private boolean runSingleScript(String taskId, DataUpdateTask task, List<String> cmd, String marketLabel) throws IOException, InterruptedException {
        // 立即推送启动信息，让用户看到反馈
        String prefix = marketLabel != null ? marketLabel : task.getCurrentStep();
        broadcastLog(taskId, "[" + prefix + "] [CMD] " + String.join(" ", cmd));
        broadcastLog(taskId, "[" + prefix + "] 正在初始化脚本，请稍候...");
        if (prefix != null && !prefix.isEmpty()) {
            task.setCurrentStep(prefix + " · 启动中...");
            broadcastStatus(task);
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        File workDir = resolvedScriptDir != null ? new File(resolvedScriptDir) : new File(scriptDir);
        if (!workDir.exists() || !workDir.isDirectory()) {
            throw new IOException("脚本目录不存在: " + workDir.getAbsolutePath());
        }
        pb.directory(workDir);
        pb.redirectErrorStream(true);
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        pb.environment().put("DB_BACKEND", "clickhouse");
        Process process = pb.start();
        // 存储到任务对象自身（不再使用共享变量 currentProcess/currentProcessPid，
        // 避免多任务并发时互相覆盖）
        task.setProcess(process);
        task.setProcessPid(process.pid());
        // 进程已启动，不再覆盖 currentStep，保留市场前缀直到 parseProgress 更新
        log.info("[DataUpdate] 进程已启动, PID={}, cmd={}", process.pid(), cmd);

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if ("CANCELLED".equals(task.getStatus())) break;
                broadcastLog(taskId, line.trim());
                parseProgress(line, task);
            }
        }

        int exitCode = process.waitFor();
        task.setProcess(null);
        task.setProcessPid(-1);
        if ("CANCELLED".equals(task.getStatus())) return false;
        if (exitCode == 0) {
            broadcastLog(taskId, "[OK] 脚本执行成功");
            return true;
        } else {
            broadcastLog(taskId, "[FAIL] 脚本退出码: " + exitCode);
            task.setError("脚本退出码: " + exitCode);
            return false;
        }
    }

    /**
     * 执行 stock_info 更新脚本，完成后自动标记退市股票
     */
    private boolean runUpdateStockInfo(String taskId, DataUpdateTask task) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(pythonPath);
        cmd.add("-u");
        cmd.add("update_stock_info_daily.py");
        boolean ok = runSingleScript(taskId, task, cmd, "股票信息");
        if (ok && !"CANCELLED".equals(task.getStatus())) {
            autoMarkDelistedStocks(taskId);
        }
        return ok;
    }

    /**
     * 自动检测并标记退市股票（在 stock_info 更新后调用）
     */
    private void autoMarkDelistedStocks(String taskId) {
        try {
            broadcastLog(taskId, "[INFO] 自动检测退市股票...");
            List<String> cmd = new ArrayList<>();
            cmd.add(pythonPath);
            cmd.add("-u");
            cmd.add("find_delisted_stocks.py");
            cmd.add("60");
            cmd.add("--mark-only");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new java.io.File(resolvedScriptDir));
            pb.redirectErrorStream(false);
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            Process p = pb.start();

            StringBuilder stdout = new StringBuilder();
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    stdout.append(line).append("\n");
                }
            }
            StringBuilder stderr = new StringBuilder();
            try (java.io.BufferedReader er = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getErrorStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = er.readLine()) != null) {
                    stderr.append(line).append("\n");
                }
            }
            int rc = p.waitFor();
            if (rc == 0) {
                String logMsg = stderr.toString().trim();
                if (!logMsg.isEmpty()) {
                    for (String line : logMsg.split("\n")) {
                        broadcastLog(taskId, "[INFO] " + line);
                    }
                }
                broadcastLog(taskId, "[INFO] 退市检测完成");
            } else {
                broadcastLog(taskId, "[WARN] 退市检测失败: " + stderr.toString().trim());
            }
        } catch (Exception e) {
            log.warn("[DataUpdate] 自动标记退市股票失败: {}", e.getMessage());
            broadcastLog(taskId, "[WARN] 自动标记退市股票失败: " + e.getMessage());
        }
    }

    /**
     * 向命令添加日期/选项等公共参数
     */
    private void addCommonArgs(List<String> cmd, DataUpdateRequest request) {
        // 日期参数：没选时默认最近 defaultStartDays 天
        String startDate = request.getStartDate();
        String endDate = request.getEndDate();
        if ((startDate == null || startDate.isEmpty()) && (endDate == null || endDate.isEmpty())) {
            java.time.LocalDate today = java.time.LocalDate.now();
            java.time.LocalDate from = today.minusDays(defaultStartDays);
            startDate = from.toString();
            endDate = today.toString();
        }
        if (startDate != null && !startDate.isEmpty()) {
            cmd.add("--start-date");
            cmd.add(startDate);
        }
        if (endDate != null && !endDate.isEmpty()) {
            cmd.add("--end-date");
            cmd.add(endDate);
        }
        if (request.isDailyOnly()) cmd.add("--daily-only");
        if (request.isInfoOnly()) cmd.add("--info-only");
        if (request.isResume()) cmd.add("--resume");
        if (request.isForce()) cmd.add("--force");
        if (request.getLimit() != null && request.getLimit() > 0) {
            cmd.add("--limit");
            cmd.add(request.getLimit().toString());
        }
        if (request.getBatchSize() != null && request.getBatchSize() > 0) {
            cmd.add("--batch-size");
            cmd.add(request.getBatchSize().toString());
        }
        if (request.getDelay() != null && request.getDelay() > 0) {
            cmd.add("--delay");
            cmd.add(request.getDelay().toString());
        }
        // 股票池筛选
        String pool = request.getStockPool();
        if (pool != null && !"ALL".equals(pool)) {
            cmd.add("--pool");
            cmd.add(pool);
        }
    }

    /**
     * 构建命令行
     */
    private List<String> buildCommand(DataUpdateRequest request) {
        // infoOnly 模式：只执行 stock_info 更新脚本
        if (request.isInfoOnly()) {
            List<String> cmd = new ArrayList<>();
            cmd.add(pythonPath);
            cmd.add("-u");
            cmd.add("update_stock_info_daily.py");
            return cmd;
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(pythonPath);
        cmd.add("-u");  // 强制 unbuffered stdout，解决管道模式下行缓冲失效问题

        String updateType = request.getUpdateType();

        // 指数日线（只传日期和 code 参数，不支持 resume/limit 等）
        if ("INDEX".equals(updateType)) {
            cmd.add("update_index_daily_baostock.py");
            // 日期参数
            String startDate = request.getStartDate();
            String endDate = request.getEndDate();
            if ((startDate == null || startDate.isEmpty()) && (endDate == null || endDate.isEmpty())) {
                java.time.LocalDate today = java.time.LocalDate.now();
                java.time.LocalDate from = today.minusDays(defaultStartDays);
                startDate = from.toString();
                endDate = today.toString();
            }
            if (startDate != null && !startDate.isEmpty()) {
                cmd.add("--start-date");
                cmd.add(startDate);
            }
            if (endDate != null && !endDate.isEmpty()) {
                cmd.add("--end-date");
                cmd.add(endDate);
            }
            // force 参数
            if (request.isForce()) {
                cmd.add("--force");
            }
            return cmd;
        }

        // 分红除权
        if ("DIVIDEND".equals(updateType)) {
            cmd.add("update_dividend_baostock.py");
            if (request.isResume()) cmd.add("--resume");
            // update_dividend_baostock.py 不支持 --force 参数
            // 全量/增量通过 --resume 区分：有 --resume 跳过已有数据，无则全量重新采集
            if (request.getLimit() != null && request.getLimit() > 0) {
                cmd.add("--limit");
                cmd.add(request.getLimit().toString());
            }
            return cmd;
        }

        // 内外盘数据（调用 update_stock_data.py --bidask-only）
        if ("BIDASK".equals(updateType)) {
            cmd.add("update_stock_data.py");
            cmd.add("--bidask-only");
            // 单只股票
            String singleCode = request.getSingleCode();
            if (singleCode != null && !singleCode.isEmpty()) {
                cmd.add("--code");
                cmd.add(singleCode);
            }
            // 日期参数透传
            String startDate = request.getStartDate();
            String endDate = request.getEndDate();
            if (startDate != null && !startDate.isEmpty()) {
                cmd.add("--start-date");
                cmd.add(startDate);
            }
            if (endDate != null && !endDate.isEmpty()) {
                cmd.add("--end-date");
                cmd.add(endDate);
            }
            return cmd;
        }

        // 财务数据
        if ("FINANCIAL".equals(updateType)) {
            cmd.add("update_financial_data.py");
            // 单只股票模式：只跑 ths + sina 两个步骤（跳过 yjbb 批量步骤）
            String singleCode = request.getSingleCode();
            if (singleCode != null && !singleCode.isEmpty()) {
                // 不在这里返回，继续构建命令，由 executeTask 分两阶段执行
                cmd.add("--step");
                cmd.add("ths");
                cmd.add("--code");
                cmd.add(singleCode);
            } else {
                if (request.getYearStart() != null) {
                    cmd.add("--year-start");
                    cmd.add(request.getYearStart().toString());
                }
                if (request.getYearEnd() != null) {
                    cmd.add("--year-end");
                    cmd.add(request.getYearEnd().toString());
                }
            }
            if (request.isForce()) {
                cmd.add("--force");
            }
            return cmd;
        }

        // 情绪数据
        if ("SENTIMENT".equals(updateType)) {
            // WESTOCK 模式：只用 westock-data 跑资金流向，跳过其他所有子模块
            if ("WESTOCK".equalsIgnoreCase(request.getMoneyflowSource())) {
                cmd.add("update_sentiment_data.py");
                cmd.add("--moneyflow-westock");
                String startDate = request.getStartDate();
                String endDate = request.getEndDate();
                if ((startDate == null || startDate.isEmpty()) && (endDate == null || endDate.isEmpty())) {
                    java.time.LocalDate today = java.time.LocalDate.now();
                    java.time.LocalDate from = today.minusDays(3);
                    startDate = from.toString();
                    endDate = today.toString();
                }
                if (startDate != null && !startDate.isEmpty()) {
                    cmd.add("--start-date");
                    cmd.add(startDate);
                }
                if (endDate != null && !endDate.isEmpty()) {
                    cmd.add("--end-date");
                    cmd.add(endDate);
                }
                if (request.getSentimentCodes() != null && !request.getSentimentCodes().isEmpty()) {
                    cmd.add("--codes");
                    cmd.add(request.getSentimentCodes());
                }
                log.info("[DataUpdate] WESTOCK 模式：仅更新资金流向，日期 {} ~ {}", startDate, endDate);
                return cmd;
            }
            // EM（东方财富）模式：跑东财实时/历史资金流向，跳过其他所有子模块
            if ("EM".equalsIgnoreCase(request.getMoneyflowSource())) {
                cmd.add("update_sentiment_data.py");
                boolean isHist = "hist".equalsIgnoreCase(request.getEmMoneyflowMode());
                cmd.add(isHist ? "--em-moneyflow-hist" : "--em-moneyflow");
                if (request.getSentimentCodes() != null && !request.getSentimentCodes().isEmpty()) {
                    cmd.add("--codes");
                    cmd.add(request.getSentimentCodes());
                }
                if (request.isForce()) cmd.add("--force");
                log.info("[DataUpdate] EM（东方财富）模式：{}，codes={}", isHist ? "历史120天" : "实时全市场", request.getSentimentCodes());
                return cmd;
            }
            // AKSHARE 模式（默认）：原有逻辑
            cmd.add("update_sentiment_data.py");
            String startDate = request.getStartDate();
            String endDate = request.getEndDate();
            if (startDate != null && !startDate.isEmpty()) {
                cmd.add("--start-date");
                cmd.add(startDate);
            }
            if (endDate != null && !endDate.isEmpty()) {
                cmd.add("--end-date");
                cmd.add(endDate);
            }
            if (request.getSentimentCodes() != null && !request.getSentimentCodes().isEmpty()) {
                cmd.add("--codes");
                cmd.add(request.getSentimentCodes());
            }
            // 未勾选的数据源传 --skip-xxx 参数
            if (!request.isFetchLhb()) {
                cmd.add("--skip-lhb");
                cmd.add("--skip-lhb-inst");
            }
            if (!request.isFetchMargin()) {
                cmd.add("--skip-margin");
                cmd.add("--skip-margin-detail");
            }
            if (!request.isFetchSurvey()) cmd.add("--skip-survey");
            if (!request.isFetchBlockTrade()) cmd.add("--skip-block");
            if (!request.isFetchActivity()) cmd.add("--skip-activity");
            if (!request.isFetchZtPool()) cmd.add("--skip-zt");
            if (!request.isFetchMoneyflow()) cmd.add("--skip-moneyflow");
            if (!request.isFetchNotice()) cmd.add("--skip-notice");
            if (request.isFetchFundHolder()) cmd.add("--fund-holder");
            if (request.isFetchShareholder()) cmd.add("--shareholder");
            if (request.isFetchNews()) cmd.add("--news");
            // 注意：--bond-yield 和 --shenwan-index 不是 update_sentiment_data.py 的参数，
            // 这两个脚本在 executeTask 中作为独立任务串行执行
            if (request.isForce()) cmd.add("--force");
            return cmd;
        }

        // 研报数据
        if ("RESEARCH".equals(updateType)) {
            cmd.add("update_research_report.py");
            if (request.isForce()) {
                cmd.add("--all");
            }
            // 日期范围
            String startDate = request.getStartDate();
            String endDate = request.getEndDate();
            if (startDate != null && !startDate.isEmpty()) {
                cmd.add("--start-date");
                cmd.add(startDate);
            }
            if (endDate != null && !endDate.isEmpty()) {
                cmd.add("--end-date");
                cmd.add(endDate);
            }
            String singleCode = request.getSingleCode(); // 新增字段：单只股票代码
            if (singleCode != null && !singleCode.isEmpty()) {
                cmd.add(singleCode);
            }
            return cmd;
        }

        // 股票日线（原有逻辑）
        if ("TENCENT".equals(request.getSource()) || "BJ".equals(request.getMarket())) {
            cmd.add("update_bj_stock_daily_qq.py");
        } else if ("BAOSTOCK".equals(request.getSource())) {
            // BAOSTOCK 数据源覆盖 SH + SZ，返回 null 走 executeAllMarkets 分别调用
            return null;
        } else if ("SH".equals(request.getMarket())) {
            cmd.add("update_stock_daily_baostock.py");
            cmd.add("--market");
            cmd.add("SH");
        } else if ("SZ".equals(request.getMarket())) {
            cmd.add("update_stock_daily_baostock.py");
            cmd.add("--market");
            cmd.add("SZ");
        } else {
            return null; // ALL → executeAllMarkets
        }

        addCommonArgs(cmd, request);
        return cmd;
    }

    /**
     * 估算总股票数
     */
    private int estimateTotalStocks(DataUpdateRequest request) {
        LambdaQueryWrapper<StockInfo> wrapper = new LambdaQueryWrapper<>();

        // 分红除权只覆盖 SH+SZ
        if ("DIVIDEND".equals(request.getUpdateType())) {
            wrapper.in(StockInfo::getMarket, "SH", "SZ");
        } else if ("BAOSTOCK".equals(request.getSource())) {
            wrapper.in(StockInfo::getMarket, "SH", "SZ");
        } else if ("TENCENT".equals(request.getSource()) || "BJ".equals(request.getMarket())) {
            wrapper.eq(StockInfo::getMarket, "BJ");
        } else if (!"ALL".equals(request.getMarket())) {
            wrapper.eq(StockInfo::getMarket, request.getMarket());
        }

        if (request.isExcludeSt()) {
            wrapper.and(w -> w.eq(StockInfo::getIsSt, 0).or().isNull(StockInfo::getIsSt));
        }

        // 股票池筛选
        if (!"ALL".equals(request.getStockPool())) {
            applyStockPool(wrapper, request.getStockPool());
        }

        return Math.max(stockInfoMapper.selectCount(wrapper).intValue(), 1);
    }

    /**
     * 股票池筛选（基于代码前缀）
     */
    private void applyStockPool(LambdaQueryWrapper<StockInfo> wrapper, String pool) {
        switch (pool) {
            case "SH300":
                // 沪深300: 大盘股，简化用市值前300
                wrapper.orderByDesc(StockInfo::getTotalMarketCap).last("LIMIT 300");
                break;
            case "SZ50":
                wrapper.likeRight(StockInfo::getCode, "000").or().likeRight(StockInfo::getCode, "60")
                        .orderByDesc(StockInfo::getTotalMarketCap).last("LIMIT 50");
                break;
            case "ZZ500":
                wrapper.orderByDesc(StockInfo::getTotalMarketCap).last("LIMIT 800");
                break;
            case "ZZ1000":
                wrapper.orderByDesc(StockInfo::getTotalMarketCap).last("LIMIT 1000");
                break;
            case "STAR50":
                wrapper.likeRight(StockInfo::getCode, "688");
                break;
            default:
                break;
        }
    }

    /**
     * 解析进度
     */
    // 匹配 "失败" 或 "error" 行中可能包含的股票代码和日期
    private static final Pattern FAILED_PATTERN = Pattern.compile(
            // 匹配 [代码] 名称 ... 失败 或 代码 名称 失败 (日期)
            // baostock 输出格式示例: "[1/100] 600519.SH 贵州茅台 - 请求失败"
            "([\\d]{6}\\.[A-Z]{2})\\s+(\\S+)\\s+.*?(失败|error|Error|ERROR|fail|Fail|FAIL)"
    );

    private void parseProgress(String line, DataUpdateTask task) {
        // 解析 [当前/总数] 进度
        Matcher m = PROGRESS_PATTERN.matcher(line);
        if (m.find()) {
            try {
                int current = Integer.parseInt(m.group(1));
                int total = Integer.parseInt(m.group(2));
                task.setProcessedStocks(current);
                task.setTotalStocks(total);
                task.setProgress((int) ((double) current / total * 100));
                // 保留市场前缀
                String stepCur = task.getCurrentStep();
                String mPrefix = "";
                int dotIdx = stepCur.indexOf(" · ");
                if (dotIdx > 0) mPrefix = stepCur.substring(0, dotIdx + 3);
                task.setCurrentStep(mPrefix + "处理股票 " + current + "/" + total);
            } catch (NumberFormatException ignored) {
            }
        }

        // 解析记录数
        Matcher rm = RECORD_PATTERN.matcher(line);
        if (rm.find()) {
            try {
                String numStr = rm.group(1).replace(",", "");
                task.setProcessedRecords(Long.parseLong(numStr));
            } catch (NumberFormatException ignored) {
            }
        }

        // 检测当前步骤（保留 "情绪数据 · " 前缀）
        String stepCur = task.getCurrentStep();
        String mPrefix = "";
        int dotIdx = stepCur.indexOf(" · ");
        if (dotIdx > 0) mPrefix = stepCur.substring(0, dotIdx + 3);

        // 情绪数据子步骤识别（优先匹配，因为情绪数据是单脚本多步骤）
        if (line.contains("[INFO] 龙虎榜") || line.contains("龙虎榜详情:") || line.contains("龙虎榜机构:")) {
            task.setCurrentStep(mPrefix + "处理中 · 龙虎榜");
        } else if (line.contains("[INFO] 融资融券") || line.contains("融资融券汇总:")
                   || line.contains("融资融券个股:")) {
            task.setCurrentStep(mPrefix + "处理中 · 融资融券");
        } else if (line.contains("[INFO] 机构调研") || line.contains("机构调研:")) {
            task.setCurrentStep(mPrefix + "处理中 · 机构调研");
        } else if (line.contains("[INFO] 大宗交易") || line.contains("大宗交易:")) {
            task.setCurrentStep(mPrefix + "处理中 · 大宗交易");
        } else if (line.contains("[INFO] 市场活跃度") || line.contains("市场活跃度:")) {
            task.setCurrentStep(mPrefix + "处理中 · 市场活跃度");
        } else if (line.contains("[INFO] 涨停") || line.contains("涨停强势池:")
                   || line.contains("跌停池:") || line.contains("炸板池:")) {
            task.setCurrentStep(mPrefix + "处理中 · 涨跌停池");
        } else if (line.contains("[INFO] 资金流向") || line.contains("资金流向(全市场):")
                   || line.contains("资金流向:")) {
            task.setCurrentStep(mPrefix + "处理中 · 资金流向");
        } else if (line.contains("[INFO] 公告") || line.contains("公告:")) {
            task.setCurrentStep(mPrefix + "处理中 · 公告");
        } else if (line.contains("日期范围模式") || line.contains("市场情绪数据采集")) {
            // 保持当前前缀，不覆盖
        } else if (line.contains("全部日期采集完成")) {
            task.setCurrentStep(mPrefix + "完成");
        } else if (line.contains("日线") || line.contains("daily")) {
            task.setCurrentStep(mPrefix + "更新日线行情");
        } else if (line.contains("stock_info") || line.contains("信息")) {
            task.setCurrentStep(mPrefix + "更新股票信息");
        } else if (line.contains("完成") || line.contains("SUCCESS")) {
            task.setCurrentStep(mPrefix + "完成");
        }

        // 解析字段变更统计（[FIELD_CHANGES] 名称:15 | 总市值:3210 | ...）
        if (line.contains("[FIELD_CHANGES]")) {
            try {
                String data = line.substring(line.indexOf("[FIELD_CHANGES]") + 15).trim();
                Map<String, Integer> changes = new java.util.LinkedHashMap<>();
                for (String part : data.split("\\|")) {
                    part = part.trim();
                    int colonIdx = part.lastIndexOf(':');
                    if (colonIdx > 0) {
                        String field = part.substring(0, colonIdx).trim();
                        int count = Integer.parseInt(part.substring(colonIdx + 1).trim());
                        changes.put(field, count);
                    }
                }
                if (!changes.isEmpty()) {
                    task.setFieldChanges(changes);
                }
            } catch (Exception e) {
                log.warn("[DataUpdate] 解析FIELD_CHANGES失败: {}", e.getMessage());
            }
        }

        // 检测失败股票并记录
        String lineLower = line.toLowerCase();
        if (lineLower.contains("失败") || lineLower.contains("error") || lineLower.contains("fail")) {
            Matcher fm = FAILED_PATTERN.matcher(line);
            if (fm.find()) {
                String code = fm.group(1);
                String name = fm.group(2);
                String date = extractDateFromLine(line);
                task.getFailedStocks().add(code + "|" + name + "|" + (date != null ? date : "未知"));
                if (task.getFailedStocks().size() > 500) {
                    task.getFailedStocks().subList(0, 250).clear();
                }
            }
        }

        broadcastStatus(task);
    }

    /**
     * 从日志行中提取日期 (格式: YYYY-MM-DD)
     */
    private String extractDateFromLine(String line) {
        java.util.regex.Matcher dm = java.util.regex.Pattern.compile("(\\d{4}-\\d{2}-\\d{2})").matcher(line);
        return dm.find() ? dm.group(1) : null;
    }

    /**
     * 广播任务状态
     */
    private void broadcastStatus(DataUpdateTask task) {
        try {
            Map<String, Object> msg = new HashMap<>();
            msg.put("type", "DATA_UPDATE_STATUS");
            msg.put("taskId", task.getTaskId());
            msg.put("status", task.getStatus());
            msg.put("progress", task.getProgress());
            msg.put("currentStep", task.getCurrentStep());
            msg.put("processedStocks", task.getProcessedStocks());
            msg.put("totalStocks", task.getTotalStocks());
            msg.put("processedRecords", task.getProcessedRecords());
            msg.put("failedStocks", task.getFailedStocks());
            msg.put("startTime", task.getStartTime() != null ? task.getStartTime().toString() : null);
            msg.put("endTime", task.getEndTime() != null ? task.getEndTime().toString() : null);
            msg.put("error", task.getError());
            msg.put("fieldChanges", task.getFieldChanges());
            msg.put("bidAskStats", task.getBidAskStats());
            if (task.getRequest() != null) {
                DataUpdateRequest req = task.getRequest();
                msg.put("updateType", req.getUpdateType());
                msg.put("source", req.getSource());
                msg.put("market", req.getMarket());
                msg.put("startDate", req.getStartDate());
                msg.put("endDate", req.getEndDate());
                msg.put("resume", req.isResume());
                msg.put("excludeSt", req.isExcludeSt());
                msg.put("dailyOnly", req.isDailyOnly());
                msg.put("infoOnly", req.isInfoOnly());
                msg.put("force", req.isForce());
                msg.put("yearStart", req.getYearStart());
                msg.put("yearEnd", req.getYearEnd());
                msg.put("stockPool", req.getStockPool());
                msg.put("fetchLhb", req.isFetchLhb());
                msg.put("fetchMargin", req.isFetchMargin());
                msg.put("fetchSurvey", req.isFetchSurvey());
                msg.put("fetchBlockTrade", req.isFetchBlockTrade());
                msg.put("fetchActivity", req.isFetchActivity());
                msg.put("fetchZtPool", req.isFetchZtPool());
                msg.put("fetchMoneyflow", req.isFetchMoneyflow());
                msg.put("fetchNotice", req.isFetchNotice());
                msg.put("fetchFundHolder", req.isFetchFundHolder());
                msg.put("fetchShareholder", req.isFetchShareholder());
                msg.put("fetchNews", req.isFetchNews());
                msg.put("fetchBondYield", req.isFetchBondYield());
                msg.put("fetchShenwanIndex", req.isFetchShenwanIndex());
                msg.put("fetchConsensusEstimate", req.isFetchConsensusEstimate());
                msg.put("fetchEarningsReport", req.isFetchEarningsReport());
                msg.put("moneyflowSource", req.getMoneyflowSource());
                msg.put("emMoneyflowMode", req.getEmMoneyflowMode());
            }
            messagingTemplate.convertAndSend("/topic/data-update/status", msg);
        } catch (Exception e) {
            log.warn("[DataUpdate] 广播状态失败: {}", e.getMessage());
        }
    }

    /**
     * 广播日志行
     */
    private void broadcastLog(String taskId, String line) {
        try {
            Map<String, Object> msg = new HashMap<>();
            msg.put("type", "DATA_UPDATE_LOG");
            msg.put("taskId", taskId);
            msg.put("line", line);
            msg.put("time", LocalDateTime.now().format(TIME_FMT));
            // 附带 updateType 让前端按类型分流日志
            // 优先从 activeTasks 取，取不到则从 taskUpdateTypes 兜底（任务结束后仍可分流）
            DataUpdateTask task = activeTasks.get(taskId);
            if (task != null && task.getRequest() != null) {
                msg.put("updateType", task.getRequest().getUpdateType());
            } else {
                String ut = taskUpdateTypes.get(taskId);
                if (ut != null) {
                    msg.put("updateType", ut);
                }
            }
            // 写入缓存（最多保留 500 条）
            taskLogCache.compute(taskId, (k, list) -> {
                if (list == null) list = new java.util.ArrayList<>();
                list.add(Map.copyOf(msg));
                if (list.size() > 500) list = new java.util.ArrayList<>(list.subList(list.size() - 500, list.size()));
                return list;
            });
            messagingTemplate.convertAndSend("/topic/data-update/log", msg);
        } catch (Exception e) {
            log.warn("[DataUpdate] 广播日志失败: {}", e.getMessage());
        }
    }

    /**
     * 获取指定任务的历史日志（供前端断线重连后补拉）
     */
    public java.util.List<Map<String, Object>> getTaskLogs(String taskId) {
        return taskLogCache.getOrDefault(taskId, java.util.Collections.emptyList());
    }

    // ==================== 数据完整性检查 ====================

    /**
     * 获取数据完整性概览
     * 优化：合并多个串行SQL为单次ClickHouse查询
     */
    public Map<String, Object> getDataCoverage() {
        Map<String, Object> result = new LinkedHashMap<>();

        // 总体统计（MySQL）
        // 排除已退市股票
        long totalStocks = stockInfoMapper.selectCount(
                new LambdaQueryWrapper<StockInfo>().isNull(StockInfo::getDelistDate));

        // ── ClickHouse 合并查询：一次SQL查出所有指标（排除指数） ─────────────
        String mergedSql = """
                 SELECT\s
                     COUNT(*) as total_records,
                     MIN(trade_date) as earliest_date,
                     MAX(trade_date) as latest_date,
                     -- 各市场记录数
                     countIf(code LIKE '6%' OR code LIKE '688%' OR code LIKE '689%') as sh_records,
                     countIf(code LIKE '0%' OR code LIKE '3%') as sz_records,
                     countIf(code LIKE '92%') as bj_records,
                     -- 各市场最新交易日
                     maxIf(trade_date, code LIKE '6%' OR code LIKE '688%' OR code LIKE '689%') as sh_latest,
                     maxIf(trade_date, code LIKE '0%' OR code LIKE '3%') as sz_latest,
                     maxIf(trade_date, code LIKE '92%') as bj_latest
                 FROM stock_daily FINAL
                 WHERE code NOT LIKE 'sh.%' AND code NOT LIKE 'sz.%'
                \s""";

        Map<String, Object> chData;
        boolean chOk = false;
        String chError = null;
        try {
            List<Map<String, Object>> rows = clickHouseStockService.queryForList(mergedSql);
            if (!rows.isEmpty()) {
                chData = rows.getFirst();
                chOk = true;
            } else {
                chData = Map.of();
                chError = "ClickHouse 查询返回空结果";
            }
        } catch (Exception e) {
            chError = e.getMessage();
            log.warn("[DataCoverage] ClickHouse合并查询失败: {}", chError);
            chData = Map.of();
        }

        long totalDailyRecords = toLong(chData.get("total_records"));
        String latestTradeDate = toDateStr(chData.get("latest_date"));
        String earliestTradeDate = toDateStr(chData.get("earliest_date"));

        // 概览
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("totalStocks", totalStocks);
        overview.put("totalDailyRecords", totalDailyRecords);
        overview.put("latestTradeDate", latestTradeDate);
        overview.put("earliestTradeDate", earliestTradeDate);

        // 各市场详细统计（MySQL查股票数，ClickHouse查日线数）
        List<Map<String, Object>> marketCoverage = new ArrayList<>();
        String[] markets = {"SH", "SZ", "BJ"};
        String[] chRecordKeys = {"sh_records", "sz_records", "bj_records"};
        String[] chDateKeys = {"sh_latest", "sz_latest", "bj_latest"};
        String[][] marketPatterns = {
                {"6", "688", "689"},
                {"0", "3"},
                {"92"}
        };

        for (int i = 0; i < markets.length; i++) {
            String market = markets[i];
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("market", market);

            // stock_info 中该市场股票总数（MySQL）
            LambdaQueryWrapper<StockInfo> iw = new LambdaQueryWrapper<>();
            iw.eq(StockInfo::getMarket, market)
              .isNull(StockInfo::getDelistDate);  // 排除已退市
            long infoCount = stockInfoMapper.selectCount(iw);
            m.put("infoCount", infoCount);

            // ClickHouse 数据
            long dailyCount = toLong(chData.get(chRecordKeys[i]));
            m.put("dailyRecords", dailyCount);

            String latestDateStr = toDateStr(chData.get(chDateKeys[i]));
            m.put("latestDate", latestDateStr);

            // 最新交易日覆盖股票数
            if (latestDateStr != null && !latestDateStr.equals("无数据")) {
                LocalDate latestDate = LocalDate.parse(latestDateStr);
                long latestDayCount = clickHouseStockService.getDistinctCodeCount(latestDate, marketPatterns[i]);
                m.put("latestDayCount", latestDayCount);
            } else {
                m.put("latestDayCount", 0);
            }

            marketCoverage.add(m);
        }

        result.put("overview", overview);
        result.put("markets", marketCoverage);
        if (!chOk) {
            result.put("warning", "ClickHouse 查询失败，数据可能不完整: " + (chError != null ? chError : "未知错误"));
        }

        log.info("[DataCoverage] 数据概览: {}只股票, {}条日线记录, 最新 {}, 最早 {}, 市场数 {}",
                totalStocks, totalDailyRecords, latestTradeDate, earliestTradeDate, marketCoverage.size());
        return result;
    }

    // ── 辅助方法 ────────────────────────────────────────────────────

    private long toLong(Object obj) {
        if (obj == null) return 0L;
        if (obj instanceof Number) return ((Number) obj).longValue();
        try {
            return Long.parseLong(obj.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private String toDateStr(Object obj) {
        if (obj == null) return "无数据";
        String str = obj.toString();
        return str.isEmpty() ? "无数据" : str;
    }

    /**
     * 获取指数数据覆盖率
     */
    public Map<String, Object> getIndexCoverage() {
        Map<String, Object> result = new LinkedHashMap<>();

        // 各指数数据统计（已迁移到 index_daily 表，code 为纯数字）
        String indexSql = """
                SELECT code, name,
                       COUNT(*) as record_count,
                       MIN(trade_date) as min_date,
                       MAX(trade_date) as max_date
                FROM index_daily
                GROUP BY code, name
                ORDER BY code
                """;
        List<Map<String, Object>> indices = clickHouseStockService.queryForList(indexSql);
        result.put("indices", indices);

        // 总记录数
        String totalSql = """
                SELECT COUNT(*) as cnt FROM index_daily FINAL
                """;
        Object totalObj = clickHouseStockService.queryForObject(totalSql);
        long totalRecords = totalObj != null ? ((Number) totalObj).longValue() : 0;
        result.put("totalRecords", totalRecords);
        result.put("indexCount", indices.size());

        // 最新交易日（指数数据的最大 trade_date）
        String latestSql = """
                SELECT MAX(trade_date) FROM index_daily
                """;
        Object latest = clickHouseStockService.queryForObject(latestSql);
        result.put("latestTradeDate", latest != null ? latest.toString() : null);

        return result;
    }

    /**
     * 查询指定日期缺失数据的指数
     */
    public List<Map<String, Object>> getMissingIndices(LocalDate date) {
        // 全部 10 个指数，index_daily 中 code 为纯数字格式
        List<Map<String, String>> allIndices = List.of(
                Map.of("code", "000001", "name", "上证指数"),
                Map.of("code", "000016", "name", "上证50"),
                Map.of("code", "000022", "name", "中证红利"),
                Map.of("code", "000300", "name", "沪深300"),
                Map.of("code", "000688", "name", "科创50"),
                Map.of("code", "000852", "name", "中证1000"),
                Map.of("code", "000905", "name", "中证500"),
                Map.of("code", "399001", "name", "深证成指"),
                Map.of("code", "399006", "name", "创业板指"),
                Map.of("code", "399303", "name", "国证2000")
        );

        // 查该日期有数据的指数 code（index_daily 表）
        Set<String> existingCodes = new HashSet<>();
        String sql = """
                SELECT DISTINCT code FROM index_daily
                WHERE trade_date = ?
                """;
        List<Map<String, Object>> rows = clickHouseStockService.queryForList(sql, date.toString());
        for (Map<String, Object> row : rows) {
            existingCodes.add(String.valueOf(row.get("code")));
        }

        List<Map<String, Object>> missing = new ArrayList<>();
        for (Map<String, String> idx : allIndices) {
            if (!existingCodes.contains(idx.get("code"))) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("code", idx.get("code"));
                m.put("name", idx.get("name"));
                missing.add(m);
            }
        }
        return missing;
    }

    /**
     * 获取分红数据覆盖率
     */
    public Map<String, Object> getDividendCoverage() {
        Map<String, Object> result = new LinkedHashMap<>();

        // 总记录数
        long totalRecords = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_dividend", Long.class);
        result.put("totalRecords", totalRecords);

        // 覆盖股票数
        long distinctCodes = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT code) FROM stock_dividend", Long.class);
        result.put("coveredStocks", distinctCodes);

        // 沪深股票总数
        long totalShSz = stockInfoMapper.selectCount(
                new LambdaQueryWrapper<StockInfo>().in(StockInfo::getMarket, "SH", "SZ"));
        result.put("totalShSzStocks", totalShSz);
        result.put("coverageRate", totalShSz > 0
                ? Math.round((double) distinctCodes / totalShSz * 10000.0) / 100.0 : 0);

        // 时间范围
        Map<String, Object> dateRange = jdbcTemplate.queryForMap(
                "SELECT MIN(ex_dividend_date) as min_date, MAX(ex_dividend_date) as max_date FROM stock_dividend");
        result.put("minDate", dateRange.get("min_date"));
        result.put("maxDate", dateRange.get("max_date"));

        return result;
    }

    /**
     * 内外盘数据概览
     */
    public Map<String, Object> getBidaskCoverage() {
        Map<String, Object> result = new LinkedHashMap<>();

        // 总记录数
        long totalRecords = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_bid_ask", Long.class);
        result.put("totalRecords", totalRecords);

        // 覆盖股票数
        long distinctCodes = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT code) FROM stock_bid_ask", Long.class);
        result.put("coveredStocks", distinctCodes);

        // 时间范围
        Map<String, Object> dateRange = jdbcTemplate.queryForMap(
                "SELECT MIN(trade_date) as min_date, MAX(trade_date) as max_date FROM stock_bid_ask");
        result.put("minDate", dateRange.get("min_date"));
        result.put("maxDate", dateRange.get("max_date"));

        // 各市场统计
        List<Map<String, Object>> marketStats = jdbcTemplate.queryForList(
                "SELECT " +
                "  CASE " +
                "    WHEN LEFT(code, 1) = '6' THEN 'SH' " +
                "    WHEN LEFT(code, 1) IN ('0','3') THEN 'SZ' " +
                "    ELSE 'BJ' " +
                "  END as market, " +
                "  COUNT(*) as record_count, " +
                "  COUNT(DISTINCT code) as stock_count " +
                "FROM stock_bid_ask " +
                "GROUP BY market " +
                "ORDER BY market"
        );
        result.put("marketStats", marketStats);

        return result;
    }

    /**
     * 分红数据完整性统计
     */
    public Map<String, Object> getMissingDividendStats() {
        Map<String, Object> result = new LinkedHashMap<>();

        // 沪深各市场缺少分红数据的股票数
        for (String market : Arrays.asList("SH", "SZ")) {
            long total = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM stock_info WHERE market = '" + market + "'", Long.class);
            long covered = jdbcTemplate.queryForObject(
                    "SELECT COUNT(DISTINCT sd.code) FROM stock_dividend sd " +
                            "INNER JOIN stock_info si ON sd.code COLLATE utf8mb4_unicode_ci = si.code WHERE si.market = '" + market + "'", Long.class);
            result.put(market, total - covered);
        }
        long totalSh = (long) result.getOrDefault("SH", 0L);
        long totalSz = (long) result.getOrDefault("SZ", 0L);
        result.put("total", totalSh + totalSz);
        result.put("coveredStocks", jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT code) FROM stock_dividend", Long.class));

        return result;
    }

    /**
     * 查询缺少分红数据的股票列表
     */
    public List<Map<String, Object>> getMissingDividendStocks(String market, int page, int pageSize) {
        String marketCondition = "ALL".equals(market) ? "" : " AND si.market = '" + market + "'";
        int offset = (page - 1) * pageSize;
        String sql = "SELECT si.code, si.name, si.market " +
                "FROM stock_info si " +
                "LEFT JOIN stock_dividend sd ON si.code = sd.code " +
                "WHERE si.market IN ('SH', 'SZ') AND sd.id IS NULL" +
                marketCondition + " " +
                "ORDER BY si.code " +
                "LIMIT " + pageSize + " OFFSET " + offset;
        return jdbcTemplate.queryForList(sql);
    }

    /**
     * 查询指定日期缺失的股票（在 stock_info 中但不在 stock_daily 指定日期中的股票）
     */
    public List<Map<String, Object>> getMissingStocks(String date, String market) {
        // 使用 SQL 直接查询缺失股票
        String marketCondition = "ALL".equals(market) ? "" : " AND si.market = '" + market + "'";
        String sql = """
                SELECT si.code, si.name, si.market
                FROM stock_info si
                WHERE si.market IN ('SH', 'SZ', 'BJ')
                AND si.delist_date IS NULL
                %s
                AND si.code NOT IN (
                    SELECT code FROM stock_daily WHERE trade_date = '%s'
                )
                ORDER BY si.code
                """.formatted(marketCondition, date);

        return jdbcTemplate.queryForList(sql);
    }

    /**
     * 从数据库查询指定日期范围的内外盘数据统计（按日期拆分）
     * 周末/节假日标记为 holiday=true，前端据此不显示失败数和成功率
     * @return Map: {"2026-06-10":{"total":N,"success":N,"failed":N,"rate":"xx.x%"}, ...}
     */
    public Map<String, Map<String, Object>> loadBidAskStats(LocalDate startDate, LocalDate endDate) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        if (startDate == null) startDate = endDate;

        // 目标总数（stock_info 中所有市场，排除 ST/退市）
        String totalSql = """
            SELECT COUNT(*) FROM stock_info si
            WHERE LENGTH(si.code)=6
            AND (si.name NOT LIKE '%退%' AND si.name NOT LIKE '%ST%')
            """;
        int total = jdbcTemplate.queryForObject(totalSql, Integer.class);

        // 查询日期范围内每一天的成功数（和 stock_info 目标范围保持一致）
        String dailySql = """
            SELECT b.trade_date, COUNT(*) as cnt
            FROM stock_bid_ask b
            INNER JOIN stock_info si ON si.code COLLATE utf8mb4_unicode_ci = b.code
            WHERE b.trade_date >= ? AND b.trade_date <= ?
              AND LENGTH(si.code)=6
              AND (si.name NOT LIKE '%退%' AND si.name NOT LIKE '%ST%')
            GROUP BY b.trade_date
            ORDER BY b.trade_date
            """;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(dailySql, startDate.toString(), endDate.toString());
        Map<String, Integer> dateSuccessMap = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String d = row.get("trade_date").toString();
            Integer cnt = ((Number) row.get("cnt")).intValue();
            dateSuccessMap.put(d, cnt);
        }

        // 遍历日期范围，生成每一天的统计
        for (LocalDate d = startDate; !d.isAfter(endDate); d = d.plusDays(1)) {
            String dateStr = d.toString();

            Map<String, Object> stats = new LinkedHashMap<>();

            // 使用交易日历判断（若服务不可用则默认为交易日，避免阻断统计）
            boolean isTrading = tradeCalendarService == null
                || tradeCalendarService.isTradingDay(d);

            if (!isTrading) {
                // 非交易日：标记为假日，不显示失败数和成功率
                boolean isWeekend = d.getDayOfWeek() == java.time.DayOfWeek.SATURDAY
                    || d.getDayOfWeek() == java.time.DayOfWeek.SUNDAY;
                stats.put("total", total);
                stats.put("success", 0);
                stats.put("failed", null);
                stats.put("rate", null);
                stats.put("holiday", true);
                stats.put("label", isWeekend ? "周末" : "节假日");
            } else {
                int success = dateSuccessMap.getOrDefault(dateStr, 0);
                stats.put("total", total);
                stats.put("success", success);
                stats.put("failed", total - success);
                stats.put("rate", total > 0 ? String.format("%.1f%%", 100.0 * success / total) : "N/A");
                stats.put("holiday", false);
                stats.put("label", null);
            }
            result.put(dateStr, stats);
        }
        return result;
    }

    /**
     * P1-2.3: 数据新鲜度检查
     * 检查各核心数据表的最新日期，超阈值记录告警。
     * 由 ScheduleService 定时调用或通过 REST API 手动触发。
     *
     * @return 新鲜度报告（各表最新日期 + 是否过期）
     */
    public Map<String, Object> checkDataFreshness() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("checkTime", LocalDateTime.now().toString());

        // 确定"最新交易日"作为基准
        LocalDate today = LocalDate.now();
        LocalDate latestTradeDate = today;
        if (tradeCalendarService != null) {
            // 回退到最近交易日
            for (int i = 0; i < 10; i++) {
                LocalDate d = today.minusDays(i);
                if (tradeCalendarService.isTradingDay(d)) {
                    latestTradeDate = d;
                    break;
                }
            }
        }
        report.put("latestTradeDate", latestTradeDate.toString());

        // ── 1. stock_daily (ClickHouse) ──
        try {
            Object sdMax = clickHouseStockService.queryForObject(
                "SELECT max(trade_date) FROM stock_daily WHERE code NOT LIKE '399%' AND code NOT LIKE '000%' AND length(code)=6");
            LocalDate sdDate = sdMax != null ? LocalDate.parse(sdMax.toString()) : null;
            long sdDays = sdDate != null ? tradeCalendarService.countTradingDays(sdDate, latestTradeDate) : 999;
            Map<String, Object> sdStatus = new LinkedHashMap<>();
            sdStatus.put("latestDate", sdDate != null ? sdDate.toString() : "N/A");
            sdStatus.put("daysBehind", sdDays);
            sdStatus.put("stale", sdDays > 2);
            if (sdDays > 2) {
                log.warn("[数据新鲜度] stock_daily 落后 {} 天（最新={}，基准={}）",
                    sdDays, sdDate, latestTradeDate);
            }
            report.put("stockDaily", sdStatus);
        } catch (Exception e) {
            log.warn("[数据新鲜度] stock_daily 查询失败: {}", e.getMessage());
            report.put("stockDaily", Map.of("error", e.getMessage()));
        }

        // ── 2. factor_value (ClickHouse) ──
        try {
            Object fvMax = clickHouseStockService.queryForObject(
                "SELECT max(calc_date) FROM factor_value");
            LocalDate fvDate = fvMax != null ? LocalDate.parse(fvMax.toString()) : null;
            long fvDays = fvDate != null ? tradeCalendarService.countTradingDays(fvDate, latestTradeDate) : 999;
            Map<String, Object> fvStatus = new LinkedHashMap<>();
            fvStatus.put("latestDate", fvDate != null ? fvDate.toString() : "N/A");
            fvStatus.put("daysBehind", fvDays);
            fvStatus.put("stale", fvDays > 1);
            if (fvDays > 1) {
                log.warn("[数据新鲜度] factor_value 落后 {} 天（最新={}，基准={}）",
                    fvDays, fvDate, latestTradeDate);
            }
            report.put("factorValue", fvStatus);
        } catch (Exception e) {
            log.warn("[数据新鲜度] factor_value 查询失败: {}", e.getMessage());
            report.put("factorValue", Map.of("error", e.getMessage()));
        }

        // ── 3. stock_financial_indicator (MySQL) ──
        try {
            Object fiMax = jdbcTemplate.queryForObject(
                "SELECT max(report_date) FROM stock_financial_indicator WHERE report_type IN (1,2,4)", String.class);
            LocalDate fiDate = null;
            if (fiMax != null) {
                String fiStr = fiMax.toString().trim();
                try {
                    fiDate = LocalDate.parse(fiStr);
                } catch (Exception parseEx) {
                    // report_date 可能存储为 yyyyMMdd 格式（如 20260331）
                    fiDate = LocalDate.parse(fiStr, DateTimeFormatter.ofPattern("yyyyMMdd"));
                }
            }
            // 财务数据按季度判断：计算距离最近季末的天数
            long fiStale = 999;
            if (fiDate != null) {
                // 最近季末日期（3/31, 6/30, 9/30, 12/31）
                int year = today.getYear();
                int month = today.getMonthValue();
                LocalDate lastQuarterEnd;
                if (month <= 3) lastQuarterEnd = LocalDate.of(year - 1, 12, 31);
                else if (month <= 6) lastQuarterEnd = LocalDate.of(year, 3, 31);
                else if (month <= 9) lastQuarterEnd = LocalDate.of(year, 6, 30);
                else lastQuarterEnd = LocalDate.of(year, 9, 30);
                fiStale = lastQuarterEnd.toEpochDay() - fiDate.toEpochDay();
                if (fiStale < 0) fiStale = 0; // 数据比预期新，没问题
            }
            Map<String, Object> fiStatus = new LinkedHashMap<>();
            fiStatus.put("latestReportDate", fiDate != null ? fiDate.toString() : "N/A");
            fiStatus.put("quartersBehind", fiStale > 90 ? fiStale / 90 : 0);
            fiStatus.put("stale", fiStale > 90);
            if (fiStale > 90) {
                log.warn("[数据新鲜度] stock_financial_indicator 落后约 {} 天（最新报告期={}）",
                    fiStale, fiDate);
            }
            report.put("financialIndicator", fiStatus);
        } catch (Exception e) {
            log.warn("[数据新鲜度] financial_indicator 查询失败: {}", e.getMessage());
            report.put("financialIndicator", Map.of("error", e.getMessage()));
        }

        report.put("hasWarning", report.values().stream().anyMatch(v -> {
            if (v instanceof Map<?,?> m) {
                Object stale = m.get("stale");
                return stale instanceof Boolean b && b;
            }
            return false;
        }));

        return report;
    }

    /**
     * 价格异常检测：查询近 N 天内单日涨跌幅绝对值 > 50% 的记录。
     * 可能原因：①除权复权误差  ②ST摘帽/退市整理板  ③首日上市  ④数据源错误
     *
     * @param days 回溯天数（默认7）
     * @return 异常记录列表 + 汇总信息
     */
    public Map<String, Object> checkPriceAnomalies(int days) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checkTime", LocalDateTime.now().toString());
        result.put("lookbackDays", days);

        try {
            String sql = String.format(
                "SELECT code, name, trade_date, close_price, pre_close, change_percent " +
                "FROM stock_daily " +
                "WHERE trade_date >= today() - %d " +
                "  AND pre_close > 0 " +
                "  AND abs(change_percent) > 50 " +
                "ORDER BY trade_date DESC, abs(change_percent) DESC " +
                "LIMIT 100", days);

            List<Map<String, Object>> anomalies = new ArrayList<>();
            List<Map<String, Object>> rows = clickHouseStockService.queryForList(sql);
            for (Map<String, Object> row : rows) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("code", row.get("code"));
                item.put("name", row.get("name"));
                item.put("tradeDate", row.get("trade_date") != null ? row.get("trade_date").toString() : null);
                item.put("closePrice", row.get("close_price"));
                item.put("preClose", row.get("pre_close"));
                item.put("changePct", row.get("change_percent"));
                anomalies.add(item);
            }

            result.put("anomalyCount", anomalies.size());
            result.put("anomalies", anomalies);
            result.put("hasAnomaly", !anomalies.isEmpty());

            if (!anomalies.isEmpty()) {
                log.warn("[价格异常检测] 近{}天发现 {} 条涨跌幅 >50% 记录，建议人工复核", days, anomalies.size());
            }
        } catch (Exception e) {
            log.warn("[价格异常检测] 查询失败: {}", e.getMessage());
            result.put("error", e.getMessage());
        }

        return result;
    }

    @PreDestroy
    public void cleanup() {
        // 关闭时杀掉所有仍在运行的子进程（不再依赖共享的 currentProcess 变量）
        for (DataUpdateTask task : activeTasks.values()) {
            Process p = task.getProcess();
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }
}
