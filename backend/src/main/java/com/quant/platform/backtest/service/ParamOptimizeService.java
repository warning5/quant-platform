package com.quant.platform.backtest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.backtest.domain.BacktestReport;
import com.quant.platform.backtest.domain.BacktestTask;
import com.quant.platform.backtest.domain.ParamOptimizeReport;
import com.quant.platform.backtest.engine.BacktestEngine;
import com.quant.platform.backtest.mapper.BacktestReportMapper;
import com.quant.platform.backtest.mapper.BacktestTaskMapper;
import com.quant.platform.backtest.mapper.ParamOptimizeReportMapper;
import com.quant.platform.strategy.domain.StrategyDefinition;
import com.quant.platform.strategy.service.StrategyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 参数优化服务 —— 网格搜索（Grid Search）
 * <p>
 * 支持的可变参数：
 * maxPositionCount (int), stopLossPct (double), stopProfitPct (double),
 * rebalanceFrequency (string: DAILY/WEEKLY/MONTHLY)
 * <p>
 * 目标函数：可选 sharpeRatio / annualReturn / calmarRatio
 */
@Slf4j
@Service
public class ParamOptimizeService {

    private final BacktestEngine backtestEngine;
    private final BacktestTaskMapper taskMapper;
    private final BacktestReportMapper reportMapper;
    private final ParamOptimizeReportMapper optimizeReportMapper;
    private final StrategyService strategyService;
    private final ObjectMapper objectMapper;

    /**
     * 注入 Spring 管理的异步执行器（与 @Async 使用同一个线程池）
     */
    private final ExecutorService backtestExecutor;

    @Autowired
    public ParamOptimizeService(
            BacktestEngine backtestEngine,
            BacktestTaskMapper taskMapper,
            BacktestReportMapper reportMapper,
            ParamOptimizeReportMapper optimizeReportMapper,
            StrategyService strategyService,
            ObjectMapper objectMapper,
            @Qualifier("backtestTaskExecutorService") ExecutorService backtestExecutor) {
        this.backtestEngine = backtestEngine;
        this.taskMapper = taskMapper;
        this.reportMapper = reportMapper;
        this.optimizeReportMapper = optimizeReportMapper;
        this.strategyService = strategyService;
        this.objectMapper = objectMapper;
        this.backtestExecutor = backtestExecutor;
    }

    // 内存存储优化任务状态（轻量级，不写DB）
    private final ConcurrentHashMap<String, OptimizeJob> jobs = new ConcurrentHashMap<>();

    /**
     * 参数网格定义
     */
    public record ParamRange(String name, List<Object> values) {
    }

    /**
     * 参数优化请求
     */
    public static class OptimizeRequest {
        public Long strategyId;
        public String startDate;
        public String endDate;
        public BigDecimal initialCapital;
        public String benchmarkCode;
        /**
         * 目标函数: sharpeRatio / annualReturn / calmarRatio
         */
        public String objective = "sharpeRatio";
        /**
         * 参数网格：[{name, values}]
         */
        public List<Map<String, Object>> paramGrid;
        /**
         * 最大并行任务数
         */
        public int maxConcurrent = 3;
    }

    /**
     * 优化任务状态
     */
    public static class OptimizeJob {
        public String jobId;
        public String status; // RUNNING / COMPLETED / FAILED
        public int total;
        public final AtomicInteger done = new AtomicInteger(0);
        public final List<Map<String, Object>> results = Collections.synchronizedList(new ArrayList<>());
        public Map<String, Object> bestResult;
        public String errorMessage;
        public long startMs;
        public long endMs;
    }

    /**
     * 网格搜索主体（由 submit() 通过 CompletableFuture.runAsync 异步调用）
     */
    public void runGridSearch(String jobId, OptimizeRequest req, StrategyDefinition strategy) {
        OptimizeJob job = jobs.get(jobId);
        job.status = "RUNNING";
        job.startMs = System.currentTimeMillis();

        try {
            // 先持久化到数据库（在异步线程中执行，有独立事务）
            log.info("ParamOptimize [{}] saving initial record to DB", jobId);
            try {
                // 保存参数网格定义
                String paramGridJson = req.paramGrid != null ? objectMapper.writeValueAsString(req.paramGrid) : null;
                ParamOptimizeReport report = ParamOptimizeReport.builder()
                        .jobId(jobId)
                        .strategyId(req.strategyId)
                        .strategyCode(strategy.getStrategyCode())
                        .taskName("参数优化-" + jobId)
                        .startDate(req.startDate)
                        .endDate(req.endDate)
                        .objective(req.objective)
                        .paramGridJson(paramGridJson)
                        .status("RUNNING")
                        .total(0)
                        .done(0)
                        .progress(0)
                        .build();
                optimizeReportMapper.insert(report);
                log.info("ParamOptimize [{}] DB INSERT SUCCESS, id={}", jobId, report.getId());
            } catch (Exception e) {
                log.error("ParamOptimize [{}] DB INSERT FAILED: {}", jobId, e.getMessage(), e);
                // 不阻止任务继续运行
            }

            List<Map<String, Object>> grid = buildGrid(req.paramGrid);
            job.total = grid.size();
            log.info("ParamOptimize [{}] started: strategy={}, grid_size={}",
                    jobId, strategy.getStrategyCode(), grid.size());

            // 使用限流信号量控制并发
            Semaphore sem = new Semaphore(Math.max(1, req.maxConcurrent));
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (Map<String, Object> params : grid) {
                sem.acquire();
                CompletableFuture<Void> f = CompletableFuture.runAsync(() -> {
                    try {
                        Map<String, Object> result = runSingleBacktest(strategy, req, params);
                        job.results.add(result);
                    } catch (Exception e) {
                        log.warn("ParamOptimize single run failed: {}", e.getMessage());
                        Map<String, Object> errResult = new HashMap<>(params);
                        errResult.put("error", e.getMessage());
                        errResult.put("score", null);
                        job.results.add(errResult);
                    } finally {
                        int done = job.done.incrementAndGet();
                        sem.release();
                        // 每完成10个或全部完成时保存到DB
                        if (done % 10 == 0 || done == job.total) {
                            updateDbReport(jobId);
                        }
                    }
                });
                futures.add(f);
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // 找出最优参数组合
            job.bestResult = job.results.stream()
                    .filter(r -> r.get("score") != null)
                    .max(Comparator.comparingDouble(r -> toDouble(r.get("score"))))
                    .orElse(null);

            // 诊断日志：记录结果统计
            long successCount = job.results.stream().filter(r -> r.get("score") != null).count();
            long errorCount = job.results.stream().filter(r -> r.get("error") != null).count();
            log.info("[ParamOptimize] Job {} completed: total={}, success={}, error={}, bestResult={}",
                    jobId, job.results.size(), successCount, errorCount,
                    job.bestResult != null ? "found" : "null");

            // 按 score 降序排列
            job.results.sort((a, b) -> {
                double sa = a.get("score") != null ? toDouble(a.get("score")) : Double.NEGATIVE_INFINITY;
                double sb = b.get("score") != null ? toDouble(b.get("score")) : Double.NEGATIVE_INFINITY;
                return Double.compare(sb, sa);
            });

            job.status = "COMPLETED";
            job.endMs = System.currentTimeMillis();
            log.info("ParamOptimize [{}] done in {}ms, best={}", jobId,
                    job.endMs - job.startMs, job.bestResult);

            // 保存最终结果到DB
            updateDbReport(jobId);

        } catch (Exception e) {
            log.error("ParamOptimize [{}] failed", jobId, e);
            job.status = "FAILED";
            job.errorMessage = e.getMessage();
            // 保存失败状态到DB
            updateDbReport(jobId);
        }
    }

    /**
     * 提交优化任务，返回 jobId
     */
    public String submit(OptimizeRequest req) {
        log.info("[ParamOptimize] submit() 开始, strategyId={}", req.strategyId);
        String jobId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        log.info("[ParamOptimize] submit() jobId={}", jobId);
        OptimizeJob job = new OptimizeJob();
        job.jobId = jobId;
        job.status = "RUNNING";
        jobs.put(jobId, job);
        log.info("[ParamOptimize] submit() 策略验证前, strategyId={}", req.strategyId);

        // 先验证策略存在（同步执行）
        StrategyDefinition strategy = strategyService.getById(req.strategyId);
        log.info("[ParamOptimize] submit() 策略查询完成, strategy={}", strategy != null ? strategy.getStrategyName() : "null");
        if (strategy == null) {
            throw new IllegalArgumentException("策略不存在: strategyId=" + req.strategyId);
        }

        log.info("[ParamOptimize] submit() 启动异步任务, jobId={}", jobId);
        // 通过 CompletableFuture.runAsync 异步执行（绕过 Spring 同类内部调用的 @Async 失效问题）
        CompletableFuture.runAsync(() -> runGridSearch(jobId, req, strategy), backtestExecutor);
        log.info("[ParamOptimize] submit() 返回 jobId={}", jobId);
        return jobId;
    }

    /**
     * 查询任务状态（优先从内存，内存没有则从DB恢复）
     */
    public OptimizeJob getJob(String jobId) {
        OptimizeJob job = jobs.get(jobId);
        if (job == null) {
            // 尝试从数据库恢复
            job = restoreFromDb(jobId);
        }
        return job;
    }

    /**
     * 从数据库恢复任务状态
     */
    private OptimizeJob restoreFromDb(String jobId) {
        ParamOptimizeReport report = optimizeReportMapper.findByJobId(jobId);
        if (report == null) return null;

        OptimizeJob job = new OptimizeJob();
        job.jobId = jobId;
        job.status = report.getStatus();
        job.total = report.getTotal() != null ? report.getTotal() : 0;
        job.startMs = System.currentTimeMillis() - (report.getElapsedMs() != null ? report.getElapsedMs() : 0);
        job.endMs = report.getElapsedMs() != null ? System.currentTimeMillis() : 0;

        // 恢复 results
        if (report.getResultsJson() != null) {
            try {
                List<Map<String, Object>> results = objectMapper.readValue(
                        report.getResultsJson(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class)
                );
                job.results.addAll(results);
            } catch (Exception e) {
                log.warn("Failed to parse results from DB: {}", e.getMessage());
            }
        }

        // 恢复 bestResult
        if (report.getBestParamsJson() != null) {
            try {
                job.bestResult = objectMapper.readValue(report.getBestParamsJson(), Map.class);
            } catch (Exception e) {
                log.warn("Failed to parse best params from DB: {}", e.getMessage());
            }
        }

        if (job.status.equals("COMPLETED") || job.status.equals("FAILED")) {
            jobs.put(jobId, job);
        }

        return job;
    }

    /**
     * 更新数据库中的任务状态（异步保存进度）
     */
    public void updateDbReport(String jobId) {
        OptimizeJob job = jobs.get(jobId);
        if (job == null) return;

        try {
            ParamOptimizeReport report = optimizeReportMapper.findByJobId(jobId);
            if (report == null) {
                // 如果找不到记录，说明初始保存失败了，创建一个新记录
                log.warn("Report not found for jobId={}, creating new record", jobId);
                report = new ParamOptimizeReport();
                report.setJobId(jobId);
                report.setStrategyId(0L); // 占位，后续可以从 job 中获取
                report.setStatus(job.status);
                report.setTotal(job.total);
                report.setDone(job.done.get());
                report.setProgress(job.total > 0 ? (int) (100.0 * job.done.get() / job.total) : 0);
                report.setCreatedAt(java.time.LocalDateTime.now());
                optimizeReportMapper.insert(report);
                return;
            }

            report.setStatus(job.status);
            report.setTotal(job.total);
            report.setDone(job.done.get());
            report.setProgress(job.total > 0 ? (int) (100.0 * job.done.get() / job.total) : 0);

            if (job.bestResult != null) {
                report.setBestParamsJson(objectMapper.writeValueAsString(job.bestResult));
                report.setBestScore(toBigDecimal(job.bestResult.get("score")));
                report.setBestAnnualReturn(toBigDecimal(job.bestResult.get("annualReturn")));
                report.setBestMaxDrawdown(toBigDecimal(job.bestResult.get("maxDrawdown")));
            }

            if (!job.results.isEmpty()) {
                report.setResultsJson(objectMapper.writeValueAsString(new ArrayList<>(job.results)));
            }

            if (job.errorMessage != null) {
                report.setErrorMessage(job.errorMessage);
            }

            if (job.endMs > 0) {
                report.setElapsedMs(job.endMs - job.startMs);
            }

            report.setUpdatedAt(java.time.LocalDateTime.now());
            optimizeReportMapper.updateById(report);
        } catch (Exception e) {
            log.warn("Failed to update param optimize report: {}", e.getMessage());
        }
    }

    /**
     * 查询运行中的优化任务（status = RUNNING 或 PENDING）
     */
    public List<OptimizeJob> findRunningJobs() {
        return jobs.values().stream()
                .filter(j -> "RUNNING".equals(j.status) || "PENDING".equals(j.status))
                .toList();
    }

    /**
     * 获取优化报告记录
     */
    public ParamOptimizeReport getOptimizeReport(String jobId) {
        return optimizeReportMapper.findByJobId(jobId);
    }

    // ─── 内部工具方法 ─────────────────────────────────────────────────────────

    /**
     * 运行单次回测，返回参数 + 关键指标
     */
    private Map<String, Object> runSingleBacktest(StrategyDefinition baseSt,
                                                  OptimizeRequest req,
                                                  Map<String, Object> params) throws Exception {
        // 构造回测任务
        BacktestTask task = BacktestTask.builder()
                .strategyId(baseSt.getId())
                .strategyCode(baseSt.getStrategyCode())
                .taskName("ParamOpt-" + paramsLabel(params))
                .startDate(LocalDate.parse(req.startDate))
                .endDate(LocalDate.parse(req.endDate))
                .initialCapital(req.initialCapital != null ? req.initialCapital : BigDecimal.valueOf(1_000_000))
                .commissionRate(BigDecimal.valueOf(0.0003))
                .slippageRate(BigDecimal.valueOf(0.001))
                .slippageModel("FIXED")
                .benchmarkCode(req.benchmarkCode != null ? req.benchmarkCode : "000300.SH")
                .limitFilter(true)
                .suspendFilter(true)
                .stampTaxRate(BigDecimal.valueOf(0.0005))
                .minCommission(BigDecimal.valueOf(5))
                .dividendReinvest(true)
                .transferFeeRate(BigDecimal.valueOf(0.00002))
                .orderType("CLOSE")
                .status(BacktestTask.BacktestStatus.PENDING)
                .progress(0)
                .build();

        // 覆盖可变参数
        applyParams(task, baseSt, params);

        // 直接写 DB，同步跑引擎
        taskMapper.insert(task);
        if (task.getId() == null) {
            throw new IllegalStateException("taskMapper.insert() 失败：task.getId() 为 null！");
        }
        log.info("[ParamOpt-runSingle] task inserted, id={}, strategy={}", task.getId(), task.getStrategyCode());

        // 注意：同步执行（不调用 @Async 方法，直接内部调用底层）
        try {
            backtestEngine.runBacktestSync(task.getId());
        } catch (Exception e) {
            log.error("[ParamOpt-runSingle] runBacktestSync failed for taskId={}: {}", task.getId(), e.getMessage(), e);
            throw e;
        }

        // 读取报告
        BacktestReport report = reportMapper.findByTaskId(task.getId());
        if (report == null) {
            throw new IllegalStateException("回测报告未生成 taskId=" + task.getId());
        }
        log.info("[ParamOpt-runSingle] report generated, reportId={}, taskId={}", report.getId(), task.getId());


        Map<String, Object> result = new LinkedHashMap<>(params);
        result.put("taskId", task.getId());

        // 注入所有 paramGrid 参数名（当前组合没用到的参数也要留 null，确保 bestResult 包含全部网格参数）
        if (req.paramGrid != null) {
            for (Map<String, Object> pg : req.paramGrid) {
                String name = (String) pg.get("name");
                if (name != null && !result.containsKey(name)) {
                    result.put(name, null);
                }
            }
        }
        result.put("totalReturn", report.getTotalReturn());
        result.put("annualReturn", report.getAnnualReturn());
        result.put("sharpeRatio", report.getSharpeRatio());
        result.put("calmarRatio", report.getCalmarRatio());
        result.put("maxDrawdown", report.getMaxDrawdown());
        result.put("volatility", report.getVolatility());
        result.put("winRate", report.getWinRate());
        result.put("profitFactor", report.getProfitLossRatio());

        // 目标函数得分
        double score = switch (req.objective) {
            case "annualReturn" -> toDouble(report.getAnnualReturn());
            case "calmarRatio" -> toDouble(report.getCalmarRatio());
            default -> toDouble(report.getSharpeRatio());
        };
        result.put("score", score);
        result.put("objective", req.objective);

        return result;
    }

    /**
     * 将参数应用到回测任务（动态参数覆盖策略定义中的值）
     */
    private void applyParams(BacktestTask task, StrategyDefinition baseSt,
                             Map<String, Object> params) throws Exception {
        // 遍历优化参数，正确设置到 BacktestTask
        for (Map.Entry<String, Object> e : params.entrySet()) {
            switch (e.getKey()) {
                case "stopLossPct" -> task.setStopLossPct(toBigDecimal(e.getValue()));
                case "stopProfitPct" -> task.setStopProfitPct(toBigDecimal(e.getValue()));
                case "initialCapital" -> task.setInitialCapital(toBigDecimal(e.getValue()));
                case "orderType" -> task.setOrderType(e.getValue().toString());
                case "benchmarkCode" -> task.setBenchmarkCode(e.getValue().toString());
                case "maxPositionCount" -> task.setMaxPositionCount(toInteger(e.getValue()));
                default -> {
                    // 其他参数暂不处理，后续可扩展
                }
            }
        }
    }

    /**
     * 笛卡尔积展开参数网格
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildGrid(List<Map<String, Object>> paramGrid) {
        List<Map<String, Object>> result = new ArrayList<>();
        result.add(new LinkedHashMap<>());

        if (paramGrid == null) return result;

        for (Map<String, Object> paramDef : paramGrid) {
            String name = (String) paramDef.get("name");
            List<Object> values = (List<Object>) paramDef.get("values");
            if (values == null || values.isEmpty()) continue;

            List<Map<String, Object>> expanded = new ArrayList<>();
            for (Map<String, Object> existing : result) {
                for (Object v : values) {
                    Map<String, Object> newEntry = new LinkedHashMap<>(existing);
                    newEntry.put(name, v);
                    expanded.add(newEntry);
                }
            }
            result = expanded;
        }
        return result;
    }

    private String paramsLabel(Map<String, Object> params) {
        StringBuilder sb = new StringBuilder();
        params.forEach((k, v) -> sb.append(k, 0, Math.min(3, k.length()))
                .append("=").append(v).append(";"));
        return sb.toString();
    }

    private double toDouble(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(v.toString());
        } catch (Exception e) {
            return 0;
        }
    }

    private BigDecimal toBigDecimal(Object v) {
        switch (v) {
            case null -> {
                return BigDecimal.ZERO;
            }
            case BigDecimal bd -> {
                return bd;
            }
            case Double d -> {
                if (Double.isNaN(d) || Double.isInfinite(d)) return BigDecimal.ZERO;
                return BigDecimal.valueOf(d);
            }
            case Float f -> {
                if (Float.isNaN(f) || Float.isInfinite(f)) return BigDecimal.ZERO;
                return BigDecimal.valueOf(f);
            }
            default -> {
            }
        }
        try {
            return new BigDecimal(v.toString());
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private Integer toInteger(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(v.toString().replaceAll("\\.0+$", ""));
        } catch (Exception e) {
            return null;
        }
    }
}
