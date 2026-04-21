package com.quant.platform.dataupdate;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quant.platform.stock.entity.StockDaily;
import com.quant.platform.stock.entity.StockInfo;
import com.quant.platform.stock.mapper.StockDailyMapper;
import com.quant.platform.stock.mapper.StockInfoMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
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
    private final StockDailyMapper stockDailyMapper;
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
    @Value("${quant.data-update.script-dir:../update_data}")
    private String scriptDir;
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
                    "update_dividend_baostock.py"};
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

    /**
     * 获取当前活跃任务
     */
    public int getDefaultStartDays() {
        return defaultStartDays;
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
            } else if (cmd == null) {
                // ALL → 依次执行 SH、SZ、BJ
                executeAllMarkets(taskId, request);
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
        task.setTotalStocks(estimateTotalStocks(request));
        boolean allSuccess = true;

        // 根据数据源决定更新哪些市场
        List<String[]> marketScripts = new ArrayList<>();
        if (!"BJ".equals(request.getMarket())) {
            // BAOSTOCK 或 ALL → 更新沪市和深市
            marketScripts.add(new String[]{"沪市", "update_stock_daily_baostock.py", "--market", "SH"});
            marketScripts.add(new String[]{"深市", "update_stock_daily_baostock.py", "--market", "SZ"});
        }
        if (!"BAOSTOCK".equals(request.getSource())) {
            // 非 BAOSTOCK 独占 → 更新北交所
            marketScripts.add(new String[]{"北交所", "update_bj_stock_daily_qq.py"});
        }

        for (String[] ms : marketScripts) {
            if ("CANCELLED".equals(task.getStatus())) break;
            task.setCurrentStep(ms[0]);
            broadcastStatus(task);
            broadcastLog(taskId, "\n========== " + ms[0] + " ==========");
            List<String> scriptCmd = new ArrayList<>();
            scriptCmd.add(pythonPath);
            scriptCmd.add("-u");  // 强制 unbuffered stdout
            scriptCmd.add(ms[1]);
            scriptCmd.addAll(Arrays.asList(ms).subList(2, ms.length));
            addCommonArgs(scriptCmd, request);
            boolean ok = runSingleScript(taskId, task, scriptCmd, ms[0]);
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

        // 保存市场前缀，parseProgress 中会保留
        String marketPrefix = prefix;

        ProcessBuilder pb = new ProcessBuilder(cmd);
        File workDir = resolvedScriptDir != null ? new File(resolvedScriptDir) : new File(scriptDir);
        if (!workDir.exists() || !workDir.isDirectory()) {
            throw new IOException("脚本目录不存在: " + workDir.getAbsolutePath());
        }
        pb.directory(workDir);
        pb.redirectErrorStream(true);
        pb.environment().put("PYTHONIOENCODING", "utf-8");
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
    }

    /**
     * 构建命令行
     */
    private List<String> buildCommand(DataUpdateRequest request) {
        List<String> cmd = new ArrayList<>();
        cmd.add(pythonPath);
        cmd.add("-u");  // 强制 unbuffered stdout，解决管道模式下行缓冲失效问题

        String updateType = request.getUpdateType();

        // 指数日线
        if ("INDEX".equals(updateType)) {
            cmd.add("update_index_daily_baostock.py");
            addCommonArgs(cmd, request);
            return cmd;
        }

        // 分红除权
        if ("DIVIDEND".equals(updateType)) {
            cmd.add("update_dividend_baostock.py");
            if (request.isResume()) cmd.add("--resume");
            if (request.getLimit() != null && request.getLimit() > 0) {
                cmd.add("--limit");
                cmd.add(request.getLimit().toString());
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
                task.setTotalStocks(Math.max(total, task.getTotalStocks()));
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

        // 检测当前步骤（保留 "SH 沪市 (Baostock) · " 前缀）
        String stepCur = task.getCurrentStep();
        String mPrefix = "";
        int dotIdx = stepCur.indexOf(" · ");
        if (dotIdx > 0) mPrefix = stepCur.substring(0, dotIdx + 3);
        if (line.contains("日线") || line.contains("daily")) {
            task.setCurrentStep(mPrefix + "更新日线行情");
        } else if (line.contains("stock_info") || line.contains("信息")) {
            task.setCurrentStep(mPrefix + "更新股票信息");
        } else if (line.contains("完成") || line.contains("SUCCESS")) {
            task.setCurrentStep(mPrefix + "完成");
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
            if (task.getRequest() != null) {
                msg.put("updateType", task.getRequest().getUpdateType());
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
     */
    public Map<String, Object> getDataCoverage() {
        Map<String, Object> result = new LinkedHashMap<>();

        // 总体统计
        long totalStocks = stockInfoMapper.selectCount(null);
        long totalDailyRecords = stockDailyMapper.selectCount(null);

        // 最新交易日
        LambdaQueryWrapper<StockDaily> latestW = new LambdaQueryWrapper<>();
        latestW.orderByDesc(StockDaily::getTradeDate).last("LIMIT 1")
                .select(StockDaily::getTradeDate);
        StockDaily latestRecord = stockDailyMapper.selectOne(latestW);
        String latestTradeDate = latestRecord != null ? latestRecord.getTradeDate().toString() : "无数据";

        // 最早交易日
        LambdaQueryWrapper<StockDaily> earliestW = new LambdaQueryWrapper<>();
        earliestW.orderByAsc(StockDaily::getTradeDate).last("LIMIT 1")
                .select(StockDaily::getTradeDate);
        StockDaily earliestRecord = stockDailyMapper.selectOne(earliestW);
        String earliestTradeDate = earliestRecord != null ? earliestRecord.getTradeDate().toString() : "无数据";

        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("totalStocks", totalStocks);
        overview.put("totalDailyRecords", totalDailyRecords);
        overview.put("latestTradeDate", latestTradeDate);
        overview.put("earliestTradeDate", earliestTradeDate);

        // 各市场详细统计
        List<Map<String, Object>> marketCoverage = new ArrayList<>();
        for (String market : Arrays.asList("SH", "SZ", "BJ")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("market", market);

            // stock_info 中该市场股票总数
            LambdaQueryWrapper<StockInfo> iw = new LambdaQueryWrapper<>();
            iw.eq(StockInfo::getMarket, market);
            long infoCount = stockInfoMapper.selectCount(iw);
            m.put("infoCount", infoCount);

            // stock_daily 中该市场记录数（通过代码前缀判断市场）
            LambdaQueryWrapper<StockDaily> dw = new LambdaQueryWrapper<>();
            switch (market) {
                case "SH" -> dw.likeRight(StockDaily::getCode, "6");
                case "SZ" ->
                        dw.and(w -> w.likeRight(StockDaily::getCode, "0").or().likeRight(StockDaily::getCode, "3"));
                case "BJ" -> dw.likeRight(StockDaily::getCode, "9");
            }
            long dailyCount = stockDailyMapper.selectCount(dw);
            m.put("dailyRecords", dailyCount);

            // 该市场最新交易日
            LambdaQueryWrapper<StockDaily> mLW = new LambdaQueryWrapper<>();
            switch (market) {
                case "SH" -> mLW.likeRight(StockDaily::getCode, "6");
                case "SZ" ->
                        mLW.and(w -> w.likeRight(StockDaily::getCode, "0").or().likeRight(StockDaily::getCode, "3"));
                case "BJ" -> mLW.likeRight(StockDaily::getCode, "9");
            }
            mLW.orderByDesc(StockDaily::getTradeDate).last("LIMIT 1")
                    .select(StockDaily::getTradeDate);
            StockDaily mLatest = stockDailyMapper.selectOne(mLW);
            m.put("latestDate", mLatest != null ? mLatest.getTradeDate().toString() : "无数据");

            // 该市场最新交易日的股票数
            if (mLatest != null) {
                LambdaQueryWrapper<StockDaily> mCLW = new LambdaQueryWrapper<>();
                mCLW.eq(StockDaily::getTradeDate, mLatest.getTradeDate());
                switch (market) {
                    case "SH" -> mCLW.likeRight(StockDaily::getCode, "6");
                    case "SZ" ->
                            mCLW.and(w -> w.likeRight(StockDaily::getCode, "0").or().likeRight(StockDaily::getCode, "3"));
                    case "BJ" -> mCLW.likeRight(StockDaily::getCode, "9");
                }
                long latestDayCount = stockDailyMapper.selectCount(mCLW);
                m.put("latestDayCount", latestDayCount);
            } else {
                m.put("latestDayCount", 0);
            }

            marketCoverage.add(m);
        }
        result.put("overview", overview);
        result.put("markets", marketCoverage);

        return result;
    }

    /**
     * 获取指数数据覆盖率
     */
    public Map<String, Object> getIndexCoverage() {
        Map<String, Object> result = new LinkedHashMap<>();

        // 各指数数据统计
        String indexSql = """
            SELECT code, name,
                   COUNT(*) as record_count,
                   MIN(trade_date) as min_date,
                   MAX(trade_date) as max_date
            FROM stock_daily
            WHERE code IN ('000001','000016','000022','000300','000688','000852','000905','399001','399006','399303')
              AND name IN ('上证指数','上证50','中证红利','沪深300','科创50','中证1000','中证500','深证成指','创业板指','国证2000')
            GROUP BY code, name
            ORDER BY code
            """;
        List<Map<String, Object>> indices = jdbcTemplate.queryForList(indexSql);
        result.put("indices", indices);

        // 总记录数
        String totalSql = """
            SELECT COUNT(*) as cnt FROM stock_daily
            WHERE code IN ('000001','000016','000022','000300','000688','000852','000905','399001','399006','399303')
              AND name IN ('上证指数','上证50','中证红利','沪深300','科创50','中证1000','中证500','深证成指','创业板指','国证2000')
            """;
        long totalRecords = jdbcTemplate.queryForObject(totalSql, Long.class);
        result.put("totalRecords", totalRecords);
        result.put("indexCount", indices.size());

        // 最新交易日（指数数据的最大 trade_date）
        String latestSql = """
            SELECT MAX(trade_date) FROM stock_daily
            WHERE code IN ('000001','000016','000022','000300','000688','000852','000905','399001','399006','399303')
              AND name IN ('上证指数','上证50','中证红利','沪深300','科创50','中证1000','中证500','深证成指','创业板指','国证2000')
            """;
        Object latest = jdbcTemplate.queryForObject(latestSql, Object.class);
        result.put("latestTradeDate", latest != null ? latest.toString() : null);

        return result;
    }

    /**
     * 查询指定日期缺失数据的指数
     */
    public List<Map<String, Object>> getMissingIndices(LocalDate date) {
        // 全部 10 个指数
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

        // 查该日期有数据的指数 code
        Set<String> existingCodes = new HashSet<>();
        String sql = """
            SELECT DISTINCT code FROM stock_daily
            WHERE trade_date = ?
              AND code IN ('000001','000016','000022','000300','000688','000852','000905','399001','399006','399303')
              AND name IN ('上证指数','上证50','中证红利','沪深300','科创50','中证1000','中证500','深证成指','创业板指','国证2000')
            """;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, date.toString());
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
     * 查询指定日期缺失的股票
     */
    public List<Map<String, Object>> getMissingStocks(String date, String market) {
        // 使用 SQL 直接查询：在 stock_info 中但不在 stock_daily 指定日期中的股票
        // 这里简化处理，通过 Java 层查询
        List<StockInfo> allStocks;
        LambdaQueryWrapper<StockInfo> wrapper = new LambdaQueryWrapper<>();
        if (market != null && !"ALL".equals(market)) {
            wrapper.eq(StockInfo::getMarket, market);
        }
        allStocks = stockInfoMapper.selectList(wrapper);

        // 查询该日期有数据的股票代码
        Set<String> existingCodes = new HashSet<>();
        // 通过 StockDailyMapper 查询（简化版，实际应用中可以用自定义 SQL）
        // 这里返回空列表，前端会通过 API 获取实际数据
        List<Map<String, Object>> missing = new ArrayList<>();

        for (StockInfo stock : allStocks) {
            missing.add(Map.of(
                    "code", stock.getCode(),
                    "name", stock.getName() != null ? stock.getName() : "",
                    "market", stock.getMarket() != null ? stock.getMarket() : ""
            ));
        }

        return missing;
    }

    @PreDestroy
    public void cleanup() {
        if (currentProcess != null && currentProcess.isAlive()) {
            currentProcess.destroyForcibly();
        }
    }
}
