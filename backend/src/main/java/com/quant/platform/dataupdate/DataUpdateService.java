package com.quant.platform.dataupdate;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quant.platform.stock.entity.StockInfo;
import com.quant.platform.stock.mapper.StockInfoMapper;
import com.quant.platform.stock.service.ClickHouseStockService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
    /**
     * 正在运行的任务
     */
    private final Map<String, DataUpdateTask> activeTasks = new ConcurrentHashMap<>();
    /**
     * 各类型最近完成的任务（页面刷新后恢复状态用）
     */
    private final ConcurrentHashMap<String, DataUpdateTask> recentFinishedTasks = new ConcurrentHashMap<>();
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
    private Process currentProcess;
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
        }
    }

    /**
     * 提交数据更新任务
     */
    public synchronized DataUpdateTask submitTask(DataUpdateRequest request) {
        // 检查是否有任务正在运行
        if (activeTasks.values().stream().anyMatch(DataUpdateTask::isRunning)) {
            throw new IllegalStateException("已有任务正在运行，请等待完成或取消");
        }

        String taskId = "TASK-" + System.currentTimeMillis();
        DataUpdateTask task = new DataUpdateTask();
        task.setTaskId(taskId);
        task.setRequest(request);
        task.setStatus("RUNNING");
        task.setStartTime(LocalDateTime.now());
        task.setCurrentStep("准备启动...");
        activeTasks.put(taskId, task);

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
     * 取消任务
     */
    public synchronized boolean cancelTask(String taskId) {
        DataUpdateTask task = activeTasks.get(taskId);
        if (task == null || !task.isRunning()) return false;

        task.setStatus("CANCELLED");
        task.setEndTime(LocalDateTime.now());
        task.setCurrentStep("用户取消");

        if (currentProcess != null && currentProcess.isAlive()) {
            currentProcess.destroy();
            try {
                currentProcess.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            if (currentProcess.isAlive()) {
                currentProcess.destroyForcibly();
            }
        }

        // 从活跃任务中移除，并保存到最近完成
        String ut = task.getRequest() != null ? task.getRequest().getUpdateType() : null;
        if (ut != null && !ut.isEmpty()) {
            recentFinishedTasks.put(ut, task);
        }
        activeTasks.remove(taskId);

        broadcastStatus(task);
        return true;
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
                task.setTotalStocks(5500); // 约5500只股票
                task.setCurrentStep("财务数据");
                broadcastStatus(task);
                boolean finOk = runSingleScript(taskId, task, cmd, "财务数据");
                if (!"CANCELLED".equals(task.getStatus())) {
                    task.setStatus(finOk ? "SUCCESS" : "FAILED");
                    task.setProgress(100);
                    task.setCurrentStep(finOk ? "采集完成" : "采集失败");
                }
            } else if ("SENTIMENT".equals(updateType)) {
                // 情绪数据：执行 update_sentiment_data.py
                task.setTotalStocks(1);
                task.setCurrentStep("情绪数据");
                broadcastStatus(task);
                boolean senOk = runSingleScript(taskId, task, cmd, "情绪数据");
                if (!"CANCELLED".equals(task.getStatus())) {
                    task.setStatus(senOk ? "SUCCESS" : "FAILED");
                    task.setProgress(100);
                    task.setCurrentStep(senOk ? "采集完成" : "采集失败");
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

                // 单个市场执行失败时，尝试备用数据源（SH/SZ 支持 akshare 备用）
                if (!ok && !"CANCELLED".equals(task.getStatus()) && ("SH".equals(m) || "SZ".equals(m))) {
                    broadcastLog(taskId, "\n[WARN] Baostock 更新失败，尝试使用 akshare 作为备用数据源...");
                    List<String> backupCmd = new ArrayList<>();
                    backupCmd.add(pythonPath);
                    backupCmd.add("-u");
                    backupCmd.add("update_stock_daily_akshare.py");
                    backupCmd.add("--market");
                    backupCmd.add(m);
                    addCommonArgs(backupCmd, request);
                    ok = runSingleScript(taskId, task, backupCmd, marketLabel + " (备用)");
                }

                if (!"CANCELLED".equals(task.getStatus())) {
                    task.setStatus(ok ? "SUCCESS" : "FAILED");
                    task.setProgress(100);
                    task.setCurrentStep(ok ? "更新完成" : "更新失败");
                }
            }
        } catch (Exception e) {
            log.error("[DataUpdate] 任务 {} 异常", taskId, e);
            task.setStatus("FAILED");
            task.setError(e.getMessage());
            task.setCurrentStep("执行异常: " + e.getMessage());
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
            currentProcess = null;
            broadcastStatus(task);
            log.info("[DataUpdate] 任务 {} 结束, 状态: {}", taskId, task.getStatus());
        }
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
            // BAOSTOCK 或 ALL → 更新沪市和深市，支持备用数据源
            marketScripts.add(new String[]{"沪市", "update_stock_daily_baostock.py", "update_stock_daily_akshare.py", "--market", "SH"});
            marketScripts.add(new String[]{"深市", "update_stock_daily_baostock.py", "update_stock_daily_akshare.py", "--market", "SZ"});
        }
        // 指定股票池时只更新池内股票池（SH/SZ），跳过北交所
        if (!"BAOSTOCK".equals(request.getSource()) && !hasPoolFilter) {
            // 非 BAOSTOCK 独占且未指定股票池 → 更新北交所
            marketScripts.add(new String[]{"北交所", "update_bj_stock_daily_qq.py"});
        }

        for (String[] ms : marketScripts) {
            if ("CANCELLED".equals(task.getStatus())) break;
            task.setCurrentStep(ms[0]);
            // 每个市场开始时重置进度，totalStocks 由脚本日志动态更新
            task.setProcessedStocks(0);
            task.setTotalStocks(0);
            task.setProgress(0);
            broadcastStatus(task);
            broadcastLog(taskId, "\n========== " + ms[0] + " ==========");

            // 尝试主数据源
            List<String> scriptCmd = new ArrayList<>();
            scriptCmd.add(pythonPath);
            scriptCmd.add("-u");  // 强制 unbuffered stdout
            scriptCmd.add(ms[1]);  // 主脚本 (Baostock)
            // 找到 --market 参数的位置
            int marketArgIndex = -1;
            for (int i = 2; i < ms.length; i++) {
                if ("--market".equals(ms[i])) {
                    marketArgIndex = i;
                    break;
                }
            }
            if (marketArgIndex > 0) {
                scriptCmd.add(ms[marketArgIndex]);      // --market
                scriptCmd.add(ms[marketArgIndex + 1]);  // SH/SZ
            }
            addCommonArgs(scriptCmd, request);
            boolean ok = runSingleScript(taskId, task, scriptCmd, ms[0]);

            // 主数据源失败且存在备用数据源时，尝试备用
            if (!ok && ms.length > 2 && ms[2].endsWith("akshare.py")) {
                broadcastLog(taskId, "\n[WARN] Baostock 更新失败，尝试使用 akshare 作为备用数据源...");
                List<String> backupCmd = new ArrayList<>();
                backupCmd.add(pythonPath);
                backupCmd.add("-u");
                backupCmd.add(ms[2]);  // 备用脚本 (akshare)
                if (marketArgIndex > 0) {
                    backupCmd.add(ms[marketArgIndex]);      // --market
                    backupCmd.add(ms[marketArgIndex + 1]);  // SH/SZ
                }
                addCommonArgs(backupCmd, request);
                ok = runSingleScript(taskId, task, backupCmd, ms[0] + " (备用)");
            }

            if (!ok) allSuccess = false;
        }

        broadcastLog(taskId, "\n========== 全部完成 ==========");
        if (!"CANCELLED".equals(task.getStatus())) {
            task.setStatus(allSuccess ? "SUCCESS" : "FAILED");
            task.setProgress(100);
            task.setCurrentStep(allSuccess ? "全部完成" : "部分失败");
        }
    }

    /**
     * 执行单个脚本，返回是否成功
     */
    private boolean runSingleScript(String taskId, DataUpdateTask task, List<String> cmd, String marketLabel) throws IOException, InterruptedException {
        // 立即推送启动信息，让用户看到反馈
        broadcastLog(taskId, "[CMD] " + String.join(" ", cmd));
        broadcastLog(taskId, "[启动中] 正在初始化脚本，请稍候...");

        // 使用传入的市场名称或当前 currentStep 作为市场前缀
        String prefix = marketLabel != null ? marketLabel : task.getCurrentStep();
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
        currentProcess = process;
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
        currentProcess = null;
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
     * 执行 stock_info 更新脚本
     */
    private boolean runUpdateStockInfo(String taskId, DataUpdateTask task) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(pythonPath);
        cmd.add("-u");
        cmd.add("update_stock_info_daily.py");
        return runSingleScript(taskId, task, cmd, "股票信息");
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
            if (request.isForce()) cmd.add("--force");
            if (request.getLimit() != null && request.getLimit() > 0) {
                cmd.add("--limit");
                cmd.add(request.getLimit().toString());
            }
            return cmd;
        }

        // 财务数据
        if ("FINANCIAL".equals(updateType)) {
            cmd.add("update_financial_data.py");
            if (request.getYearStart() != null) {
                cmd.add("--year-start");
                cmd.add(request.getYearStart().toString());
            }
            if (request.getYearEnd() != null) {
                cmd.add("--year-end");
                cmd.add(request.getYearEnd().toString());
            }
            if (request.isForce()) {
                cmd.add("--force");
            }
            return cmd;
        }

        // 情绪数据
        if ("SENTIMENT".equals(updateType)) {
            // NEODATA 模式：只用 NeoData 跑资金流向，跳过其他所有子模块
            if ("NEODATA".equalsIgnoreCase(request.getMoneyflowSource())) {
                cmd.add("update_sentiment_data.py");
                cmd.add("--moneyflow-neodata");
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
                log.info("[DataUpdate] NEODATA 模式：仅更新资金流向，日期 {} ~ {}", startDate, endDate);
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
                msg.put("moneyflowSource", req.getMoneyflowSource());
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
            DataUpdateTask task = activeTasks.get(taskId);
            if (task != null && task.getRequest() != null) {
                msg.put("updateType", task.getRequest().getUpdateType());
            }
            messagingTemplate.convertAndSend("/topic/data-update/log", msg);
        } catch (Exception e) {
            log.warn("[DataUpdate] 广播日志失败: {}", e.getMessage());
        }
    }

    // ==================== 数据完整性检查 ====================

    /**
     * 获取数据完整性概览
     * 优化：合并多个串行SQL为单次ClickHouse查询
     */
    public Map<String, Object> getDataCoverage() {
        Map<String, Object> result = new LinkedHashMap<>();

        // 总体统计（MySQL）
        long totalStocks = stockInfoMapper.selectCount(null);

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
            iw.eq(StockInfo::getMarket, market);
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
                String patternSql = buildCodePatternSql(marketPatterns[i]);
                long latestDayCount = clickHouseStockService.getDistinctCodeCount(latestDate, patternSql);
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

    private String buildCodePatternSql(String[] prefixes) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < prefixes.length; i++) {
            if (i > 0) sb.append(" OR ");
            sb.append("code LIKE '").append(prefixes[i]).append("%'");
        }
        sb.append(")");
        return sb.toString();
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
                            "INNER JOIN stock_info si ON sd.code = si.code WHERE si.market = '" + market + "'", Long.class);
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
                %s
                AND si.code NOT IN (
                    SELECT code FROM stock_daily WHERE trade_date = '%s'
                )
                ORDER BY si.code
                """.formatted(marketCondition, date);

        return jdbcTemplate.queryForList(sql);
    }

    @PreDestroy
    public void cleanup() {
        if (currentProcess != null && currentProcess.isAlive()) {
            currentProcess.destroyForcibly();
        }
    }
}
