package com.quant.platform.dataupdate;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quant.platform.calendar.service.TradeCalendarService;
import com.quant.platform.common.enums.JobStatus;
import com.quant.platform.factor.domain.FactorDefinition;
import com.quant.platform.factor.mapper.FactorDefinitionMapper;
import com.quant.platform.factor.service.FactorService;
import com.quant.platform.stock.mapper.StockInfoMapper;
import com.quant.platform.stock.service.ClickHouseStockService;
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
import java.nio.file.Files;
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
 * 数据更新任务执行服务
 * 承载任务运行时状态（activeTasks / 日志缓存）、任务提交与取消、Python 子进程调度与进度解析。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataUpdateExecutionService {

    /**
     * 进度解析正则
     */
    private static final Pattern PROGRESS_PATTERN = Pattern.compile(
            "\\[(\\d+)/(\\d+)]");
    private static final Pattern RECORD_PATTERN = Pattern.compile(
            "(?:成功记录|成功[^0-9]{0,3}|已处理|写入|新增|更新)[^0-9]*(\\d[\\d,]*)");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final SimpMessagingTemplate messagingTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final FactorService factorService;

    @Autowired
    private FactorDefinitionMapper factorDefinitionMapper;

    @Autowired(required = false)
    private ApplicationEventPublisher eventPublisher;

    @Autowired(required = false)
    private TradeCalendarService tradeCalendarService;

    /** 执行历史服务（写入 task_run_history，供监控页展示） */
    @Autowired(required = false)
    private TaskRunHistoryService taskRunHistoryService;

    /** 单脚本最大执行时长（分钟），超时则强杀进程并标记失败 */
    @Value("${quant.data-update.script-timeout-minutes:120}")
    private int scriptTimeoutMinutes;

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
    @Getter
    private String resolvedScriptDir;

    // 匹配 "失败" 或 "error" 行中可能包含的股票代码和日期
    private static final Pattern FAILED_PATTERN = Pattern.compile(
            // 匹配 [代码] 名称 ... 失败 或 代码 名称 失败 (日期)
            // baostock 输出格式示例: "[1/100] 600519.SH 贵州茅台 - 请求失败"
            "([\\d]{6}\\.[A-Z]{2})\\s+(\\S+)\\s+.*?(失败|error|Error|ERROR|fail|Fail|FAIL)"
    );

    // ─── 数据库凭据由 db_config.py 从 .env 文件读取，Java 不再传递 ───

    private final DataUpdateScriptService scriptService;

    private final DataUpdateCoverageService dataUpdateCoverageService;

    /**
     * 初始化：解析脚本目录（优先外部路径，其次从 classpath 提取）
     */
    @PostConstruct
    public void init() {
        // 1. 尝试外部脚本目录（application.yml 配置的 script-dir）
        //    仅当目录存在且包含 db_config.py 时才使用，避免空目录命中
        File dir = new File(scriptDir);
        if (!dir.isAbsolute()) {
            Path absolute = Paths.get(System.getProperty("user.dir"), scriptDir).toAbsolutePath().normalize();
            dir = absolute.toFile();
        }
        if (dir.exists() && dir.isDirectory() && new File(dir, "db_config.py").exists()) {
            resolvedScriptDir = dir.getAbsolutePath();
            log.info("[DataUpdate] 脚本目录(外部): {}", resolvedScriptDir);
            verifyScripts();
            cleanupStaleTasks();
            return;
        }

        // 2. 从 classpath 提取脚本（jar 部署模式）
        try {
            resolvedScriptDir = extractScriptsFromClasspath();
            log.info("[DataUpdate] 脚本目录(classpath): {}", resolvedScriptDir);
            verifyScripts();
        } catch (Exception e) {
            log.error("[DataUpdate] 无法加载脚本目录: {}", e.getMessage());
            resolvedScriptDir = null;
        }
        cleanupStaleTasks();
    }

    /**
     * 从 classpath:scripts/ 加载 Python 脚本。
     * IDE 模式：classpath 指向 src/main/resources/scripts/，直接使用。
     * Jar 模式：classpath 指向 jar 内部，提取到 ~/.quant-platform/scripts/。
     */
    private String extractScriptsFromClasspath() throws IOException {
        var resolver = new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
        var resources = resolver.getResources("classpath:scripts/*");

        // IDE 模式：resource.getFile() 可用，直接返回所在目录
        if (resources.length > 0) {
            try {
                java.io.File file = resources[0].getFile();
                if (file.isFile()) {
                    String dir = file.getParent();
                    log.info("[DataUpdate] classpath scripts 直接使用: {}", dir);
                    return dir;
                }
            } catch (IOException ignored) {
                // Jar 模式：getFile() 失败，走提取逻辑
            }
        }

        // Jar 模式：提取到 ~/.quant-platform/scripts/
        Path targetDir = Paths.get(System.getProperty("user.home"), ".quant-platform", "scripts");
        Files.createDirectories(targetDir);

        int count = 0;
        for (var resource : resources) {
            String filename = resource.getFilename();
            if (filename == null || filename.isEmpty() || filename.endsWith("/")) continue;
            Path target = targetDir.resolve(filename);
            try (var is = resource.getInputStream()) {
                Files.copy(is, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                count++;
            }
        }

        // 同时提取 .env 到 ~/.quant-platform/.env（db_config.py 第二候选路径）
        try {
            var envResource = new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                    .getResource("classpath:.env");
            if (envResource.exists()) {
                Path envTarget = Paths.get(System.getProperty("user.home"), ".quant-platform", ".env");
                try (var is = envResource.getInputStream()) {
                    Files.copy(is, envTarget, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                log.info("[DataUpdate] 从 classpath 提取 .env 到 {}", envTarget);
            }
        } catch (IOException e) {
            log.warn("[DataUpdate] 提取 .env 失败（Python 将依赖环境变量或磁盘 .env）: {}", e.getMessage());
        }

        log.info("[DataUpdate] 从 classpath 提取了 {} 个脚本到 {}", count, targetDir);
        return targetDir.toAbsolutePath().toString();
    }

    /**
     * 解析后的脚本目录（jar 模式为 ~/.quant-platform/scripts，IDE 模式为 classpath 目录）。
     * 供盘中资金流等模块复用同一脚本目录，避免重复解析。
     */
    public String getResolvedScriptDir() {
        return resolvedScriptDir != null ? resolvedScriptDir : scriptDir;
    }

    private void verifyScripts() {
        String[] scripts = {"update_stock_data.py", "update_stock_daily_baostock.py",
                "update_bj_stock_daily_qq.py", "update_index_daily_baostock.py",
                "update_dividend_baostock.py", "update_research_report.py"};
        for (String s : scripts) {
            File f = new File(resolvedScriptDir, s);
            log.info("[DataUpdate]   {} : {}", s, f.exists() ? "OK" : "MISSING");
        }
    }

    private void cleanupStaleTasks() {
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
        task.setStatus(JobStatus.RUNNING);
        task.setStartTime(LocalDateTime.now());
        task.setCurrentStep("准备启动...");
        activeTasks.put(taskId, task);
        // 记录 updateType 映射（即使任务从 activeTasks 移除后仍可查到，确保日志分流正确）
        if (request.getUpdateType() != null) {
            taskUpdateTypes.put(taskId, request.getUpdateType());
        }

        // ★ 写入执行历史（RUNNING），并记录 historyId 供结束时回填
        if (taskRunHistoryService != null) {
            try {
                String taskName = null;
                try {
                    taskName = jdbcTemplate.queryForObject(
                        "SELECT task_name FROM data_schedule_config WHERE task_key = ?",
                        String.class, request.getTaskKey() != null ? request.getTaskKey() : request.getUpdateType());
                } catch (Exception ignored) {
                    log.error("[DataUpdateExecutionService] 捕获到未处理异常", ignored);
                }
                long hid = taskRunHistoryService.insertRunning(
                    request.getTaskKey() != null ? request.getTaskKey() : request.getUpdateType(),
                    taskName, request.getTriggerType(), request.getUpdateType(), null);
                task.setHistoryId(hid);
            } catch (Exception e) {
                log.warn("[DataUpdate] 写入执行历史失败(不影响主流程): {}", e.getMessage());
            }
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
                info.put("status", JobStatus.RUNNING.name());
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

        task.setStatus(JobStatus.CANCELLED);
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
            } catch (IOException e) {
                log.warn("[DataUpdateExecutionService] 终止子进程失败: {}", e.getMessage(), e);
            }
            // 等待进程真正退出（最多 3 秒）
            try {
                targetProcess.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[DataUpdateExecutionService] 等待子进程退出被中断: {}", e.getMessage());
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
            List<String> cmd = scriptService.buildCommand(request);

            if ("INDEX".equals(updateType)) {
                // 指数日线：单次执行 update_index_daily_baostock.py
                task.setTotalStocks(10); // 10个指数
                task.setCurrentStep("指数日线");
                broadcastStatus(task);
                boolean indexOk = runSingleScript(taskId, task, cmd, "指数日线");
                if (JobStatus.CANCELLED != task.getStatus()) {
                    task.setStatus(indexOk ? JobStatus.SUCCESS : JobStatus.FAILED);
                    task.setProgress(100);
                    task.setCurrentStep(indexOk ? "更新完成" : "更新失败");
                }
            } else if ("DIVIDEND".equals(updateType)) {
                // 分红除权：单次执行 update_dividend_baostock.py
                task.setTotalStocks(scriptService.estimateTotalStocks(request));
                task.setCurrentStep("分红除权");
                broadcastStatus(task);
                boolean divOk = runSingleScript(taskId, task, cmd, "分红除权");
                if (JobStatus.CANCELLED != task.getStatus()) {
                    task.setStatus(divOk ? JobStatus.SUCCESS : JobStatus.FAILED);
                    task.setProgress(100);
                    task.setCurrentStep(divOk ? "更新完成" : "更新失败");
                }
            } else if ("QFQ_REFRESH".equals(updateType)) {
                // 前复权因子刷新：单次执行 refresh_qfq_history.py
                task.setTotalStocks(1);
                task.setCurrentStep("前复权因子刷新");
                broadcastStatus(task);
                boolean qfqOk = runSingleScript(taskId, task, cmd, "前复权刷新");
                if (JobStatus.CANCELLED != task.getStatus()) {
                    task.setStatus(qfqOk ? JobStatus.SUCCESS : JobStatus.FAILED);
                    task.setProgress(100);
                    task.setCurrentStep(qfqOk ? "更新完成" : "更新失败");
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

                    if (JobStatus.CANCELLED != task.getStatus()) {
                        task.setStatus(thsOk && sinaOk ? JobStatus.SUCCESS : JobStatus.FAILED);
                        task.setProgress(100);
                        task.setCurrentStep(thsOk && sinaOk ? "采集完成" : "部分失败");
                    }
                } else {
                    boolean finOk = runSingleScript(taskId, task, cmd, "财务数据");
                    if (JobStatus.CANCELLED != task.getStatus()) {
                        task.setStatus(finOk ? JobStatus.SUCCESS : JobStatus.FAILED);
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
                if (senOk && request.isFetchBondYield() && JobStatus.CANCELLED != task.getStatus()) {
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
                if (request.isFetchShenwanIndex() && JobStatus.CANCELLED != task.getStatus()) {
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
                if (request.isFetchConsensusEstimate() && JobStatus.CANCELLED != task.getStatus()) {
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
                if (request.isFetchEarningsReport() && JobStatus.CANCELLED != task.getStatus()) {
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
                if (request.isFetchQvix() && JobStatus.CANCELLED != task.getStatus()) {
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

                if (JobStatus.CANCELLED != task.getStatus()) {
                    task.setStatus(senOk ? JobStatus.SUCCESS : JobStatus.FAILED);
                    task.setProgress(100);
                    task.setCurrentStep(senOk ? "采集完成" : "部分采集失败");
                }
            } else if ("BIDASK".equals(updateType)) {
                // 内外盘数据：执行 update_stock_data.py --bidask-only
                task.setTotalStocks(5248);
                task.setCurrentStep("内外盘数据");
                broadcastStatus(task);
                boolean bidaskOk = runSingleScript(taskId, task, cmd, "内外盘数据");
                if (JobStatus.CANCELLED != task.getStatus()) {
                    task.setStatus(bidaskOk ? JobStatus.SUCCESS : JobStatus.FAILED);
                    task.setProgress(100);
                    task.setCurrentStep(bidaskOk ? "采集完成" : "采集失败");
                    // 成功后从数据库查询日期维度统计
                    if (bidaskOk) {
                        LocalDate endDate = (request.getEndDate() != null && !request.getEndDate().isEmpty())
                            ? LocalDate.parse(request.getEndDate()) : LocalDate.now();
                        LocalDate startDate = (request.getStartDate() != null && !request.getStartDate().isEmpty())
                            ? LocalDate.parse(request.getStartDate()) : endDate;
                        task.setBidAskStats(dataUpdateCoverageService.loadBidAskStats(startDate, endDate));
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
                    task.setStatus(JobStatus.SUCCESS);
                } catch (Exception e) {
                    log.error("[因子计算] 失败", e);
                    task.setCurrentStep("计算失败: " + e.getMessage());
                    task.setStatus(JobStatus.FAILED);
                }
                task.setProgress(100);
                task.setCurrentStep("计算完成");
                // P3-12: 发布因子计算完成事件
                if (eventPublisher != null) {
                    boolean computeOk = JobStatus.SUCCESS == task.getStatus();
                    LocalDate computeDate = (request.getEndDate() != null && !request.getEndDate().isEmpty())
                        ? LocalDate.parse(request.getEndDate()) : LocalDate.now();
                    int factorCnt = computeOk ? task.getTotalStocks() : 0;
                    eventPublisher.publishEvent(new com.quant.platform.common.event.FactorComputeCompletedEvent(
                        this, computeDate, factorCnt, computeOk));
                    log.info("[DataUpdate] ★ 发布 FactorComputeCompletedEvent: date={}, count={}, success={}",
                        computeDate, factorCnt, computeOk);
                }
            } else if (cmd == null) {
                // ALL → 依次执行 SH、SZ、BJ
                executeAllMarkets(taskId, request);
            } else if (cmd.size() >= 3 && "update_stock_info_daily.py".equals(cmd.getLast())) {
                // infoOnly 模式：只执行 stock_info 脚本
                task.setTotalStocks(5500);
                task.setCurrentStep("股票信息");
                broadcastStatus(task);
                boolean ok = runSingleScript(taskId, task, cmd, "股票信息");
                if (JobStatus.CANCELLED != task.getStatus()) {
                    task.setStatus(ok ? JobStatus.SUCCESS : JobStatus.FAILED);
                    task.setProgress(100);
                    task.setCurrentStep(ok ? "更新完成" : "更新失败");
                }
            } else {
                task.setTotalStocks(scriptService.estimateTotalStocks(request));
                // 根据请求参数推断市场名称
                String marketLabel = null;
                String m = request.getMarket();
                if ("SH".equals(m)) marketLabel = "沪市";
                else if ("SZ".equals(m)) marketLabel = "深市";
                else if ("BJ".equals(m)) marketLabel = "北交所";
                else if ("BAOSTOCK".equals(request.getSource())) marketLabel = "沪深";
                else if ("TENCENT_ALL".equals(request.getSource())) marketLabel = "全市场(腾讯)";
                else if ("TENCENT".equals(request.getSource())) marketLabel = "北交所";
                task.setCurrentStep(marketLabel != null ? marketLabel : "启动中...");
                broadcastStatus(task);
                log.info("[DataUpdate] 启动任务 {}: {}", taskId, cmd);
                boolean ok = runSingleScript(taskId, task, cmd, marketLabel);

                // 不再使用 akshare 作为备用数据源
                if (JobStatus.CANCELLED != task.getStatus()) {
                    task.setStatus(ok ? JobStatus.SUCCESS : JobStatus.FAILED);
                    task.setProgress(100);
                    task.setCurrentStep(ok ? "更新完成" : "更新失败");
                }
            }
        } catch (Exception e) {
            log.error("[DataUpdate] 任务 {} 异常", taskId, e);
            // 不覆盖 CANCELLED 状态（用户已手动取消时保留取消状态）
            if (JobStatus.CANCELLED != task.getStatus()) {
                task.setStatus(JobStatus.FAILED);
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
                JobStatus finalStatus = task.getStatus();
                durationSec = java.time.Duration.between(task.getStartTime(), task.getEndTime()).getSeconds();
                // 直接按 task_key 更新，不依赖 RUNNING 条件（更健壮）
                int rows = jdbcTemplate.update(
                    "UPDATE data_schedule_config SET last_run_status=?, last_run_duration_sec=?, updated_at=? " +
                    "WHERE task_key=?",
                    finalStatus.name(), durationSec, LocalDateTime.now(), dbKey
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
                boolean taskOk = JobStatus.SUCCESS == task.getStatus();
                // 使用原始调度任务 key，确保 SENTIMENT_MF/SENTIMENT_OTHER 等子任务能正确触发依赖链
                String eventKey = request.getTaskKey() != null && !request.getTaskKey().isEmpty()
                        ? request.getTaskKey() : ut;
                eventPublisher.publishEvent(new DataUpdateCompletedEvent(this, eventKey, taskOk, durationSec));
            }

            // ★ 回填执行历史记录（状态/耗时/错误信息）
            if (taskRunHistoryService != null && task.getHistoryId() != null && task.getHistoryId() > 0) {
                try {
                    taskRunHistoryService.finish(task.getHistoryId(), task.getStatus(),
                        null, task.getError());
                    log.info("[DataUpdate] ★ 回写执行历史: historyId={}, status={}", task.getHistoryId(), task.getStatus());
                } catch (Exception histEx) {
                    log.warn("[DataUpdate] 回写执行历史失败: {}", histEx.getMessage());
                }
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
            if (JobStatus.CANCELLED != task.getStatus()) {
                task.setStatus(ok ? JobStatus.SUCCESS : JobStatus.FAILED);
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
        boolean baostockDegraded = false; // 是否已降级到腾讯
        for (String[] ms : marketScripts) {
            if (JobStatus.CANCELLED == task.getStatus()) break;
            // 已降级到腾讯全市场时跳过北交所（已包含在腾讯全量中）
            if (baostockDegraded) break;

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
            scriptService.addCommonArgs(scriptCmd, request);
            boolean ok = runSingleScript(taskId, task, scriptCmd, ms[0]);

            if (!ok) {
                allSuccess = false;
                // Baostock 不可用时自动降级到腾讯全市场
                if ("沪深".equals(ms[0]) && isBaostockBlocked(task)) {
                    baostockDegraded = true;
                    String err = task.getLastScriptError();
                    broadcastLog(taskId, "[WARN] Baostock 不可用 (" + err + ")");
                    broadcastLog(taskId, "[WARN] 自动降级到腾讯接口（覆盖沪深+北交所全市场）...");

                    List<String> tencentCmd = new ArrayList<>();
                    tencentCmd.add(pythonPath);
                    tencentCmd.add("-u");
                    tencentCmd.add("update_stock_daily.py");
                    tencentCmd.add("--source");
                    tencentCmd.add("tencent");
                    scriptService.addCommonArgs(tencentCmd, request);

                    task.setCurrentStep("全市场(腾讯降级)");
                    task.setProcessedStocks(0);
                    task.setTotalStocks(0);
                    task.setProgress(0);
                    broadcastStatus(task);
                    broadcastLog(taskId, "\n========== 全市场(腾讯降级) ==========");

                    boolean tencentOk = runSingleScript(taskId, task, tencentCmd, "全市场(腾讯降级)");
                    if (tencentOk) {
                        allSuccess = true;
                        broadcastLog(taskId, "[OK] 腾讯降级成功，全市场数据已更新");
                    } else {
                        broadcastLog(taskId, "[FAIL] 腾讯降级也失败，请检查网络");
                    }
                }
            }
        }

        // ─── Part 1.5: 更新指数日线（仅非 dailyOnly 时）────────────
        if (!request.isDailyOnly() && JobStatus.CANCELLED != task.getStatus()) {
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
        if (JobStatus.CANCELLED != task.getStatus()) {
            optimizeClickHouseTable(taskId);
        }

        broadcastLog(taskId, "\n========== 全部完成 ==========");
        if (JobStatus.CANCELLED != task.getStatus()) {
            task.setStatus(allSuccess ? JobStatus.SUCCESS : JobStatus.FAILED);
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
            pb.directory(new File(resolvedScriptDir));
            pb.redirectErrorStream(true);
            scriptService.configurePythonEnv(pb);
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
        scriptService.configurePythonEnv(pb);
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
                if (JobStatus.CANCELLED == task.getStatus()) break;
                String trimmed = line.trim();
                broadcastLog(taskId, trimmed);
                parseProgress(line, task);
                // 捕获 Baostock 不可用的错误模式（用于自动降级判断）
                if (trimmed.contains("黑名单") || trimmed.contains("登录失败")
                        || trimmed.contains("login fail") || trimmed.contains("blacklist")
                        || trimmed.contains("ERROR") && trimmed.contains("Baostock")) {
                    task.setLastScriptError(trimmed);
                }
            }
        }

        boolean finished;
        int exitCode;
        try {
            finished = process.waitFor(scriptTimeoutMinutes, java.util.concurrent.TimeUnit.MINUTES);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            finished = false;
            exitCode = -1;
        }
        task.setProcess(null);
        task.setProcessPid(-1);
        if (JobStatus.CANCELLED == task.getStatus()) return false;
        if (!finished) {
            // 超时：强杀进程，避免永久挂起导致状态一直 RUNNING
            try {
                process.destroyForcibly();
                broadcastLog(taskId, "[TIMEOUT] 脚本执行超过 " + scriptTimeoutMinutes +
                    " 分钟，已强制终止");
                task.setError("脚本执行超时(>" + scriptTimeoutMinutes + "分钟)已终止");
            } catch (Exception ex) {
                log.warn("[DataUpdate] 强杀超时进程失败: {}", ex.getMessage());
            }
            return false;
        }
        exitCode = process.exitValue();
        if (exitCode == 0) {
            broadcastLog(taskId, "[OK] 脚本执行成功");
            task.setLastScriptError(null); // 成功则清除错误
            return true;
        } else {
            broadcastLog(taskId, "[FAIL] 脚本退出码: " + exitCode);
            task.setError("脚本退出码: " + exitCode);
            return false;
        }
    }

    /**
     * 判断脚本失败是否因 Baostock 不可用（黑名单/登录失败）
     */
    private boolean isBaostockBlocked(DataUpdateTask task) {
        String err = task.getLastScriptError();
        if (err == null || err.isEmpty()) return false;
        return err.contains("黑名单") || err.contains("登录失败")
                || err.contains("login fail") || err.contains("blacklist");
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
        if (ok && JobStatus.CANCELLED != task.getStatus()) {
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
            scriptService.configurePythonEnv(pb);
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
                log.warn("[DataUpdateExecutionService] 解析进度文本失败", ignored);
            }
        }

        // 解析记录数
        Matcher rm = RECORD_PATTERN.matcher(line);
        if (rm.find()) {
            try {
                String numStr = rm.group(1).replace(",", "");
                task.setProcessedRecords(Long.parseLong(numStr));
            } catch (NumberFormatException ignored) {
                log.warn("[DataUpdateExecutionService] 解析记录数失败", ignored);
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
                String date = scriptService.extractDateFromLine(line);
                task.getFailedStocks().add(code + "|" + name + "|" + (date != null ? date : "未知"));
                if (task.getFailedStocks().size() > 500) {
                    task.getFailedStocks().subList(0, 250).clear();
                }
            }
        }

        broadcastStatus(task);
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
                msg.put("fetchQvix", req.isFetchQvix());
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
