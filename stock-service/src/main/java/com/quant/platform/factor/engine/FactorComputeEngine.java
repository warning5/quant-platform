package com.quant.platform.factor.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.config.ClickHouseConfig;
import com.quant.platform.factor.domain.FactorDefinition;
import com.quant.platform.factor.domain.FactorTestReport;
import com.quant.platform.factor.domain.FactorValue;
import com.quant.platform.factor.mapper.FactorTestReportMapper;
import com.quant.platform.factor.mapper.FactorValueMapper;
import com.quant.platform.factor.service.ClickHouseFactorValueService;
import com.quant.platform.financial.entity.StockFinancialIndicator;
import com.quant.platform.financial.mapper.StockFinancialIndicatorMapper;
import com.quant.platform.market.domain.MarketDailyBar;
import com.quant.platform.market.service.MarketDataService;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import com.quant.platform.common.enums.JobStatus;
/**
 * 因子计算调度引擎
 * 负责调度因子计算、IC分析和分组回测
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactorComputeEngine {

    // ===== 拆分出的专责协作者 =====
    /** 进度推送 + 运行中因子状态 + ETA 文案 */
    private final FactorProgressService progressService;
    /** 因子值批量落库（ClickHouse） */
    private final FactorPersistenceService factorPersistenceService;
    /** 横截面归一化（Z-Score + 百分位排名） */
    private final FactorNormalizationService factorNormalizationService;
    /** 因子计算所需的横截面 context 构建 */
    private final FactorContextBuilder factorContextBuilder;
    /** 财务因子（季频报告期口径）计算 */
    private final FactorFinancialComputeService factorFinancialService;
    /** 因子测试（IC 分析 + 分组回测） */
    private final FactorTestEngine factorTestEngine;

    private final MarketDataService marketDataService;
    private final FactorValueMapper factorValueMapper;
    private final ClickHouseFactorValueService clickHouseFactorValueService;
    private final FactorTestReportMapper testReportMapper;
    private final ScriptedFactorEngine scriptedEngine;
    private final ObjectMapper objectMapper;
    private final StockFinancialIndicatorMapper financialIndicatorMapper;
    private final com.quant.platform.factor.service.FactorMetaCacheService factorMetaCache;
    private final com.quant.platform.stock.mapper.StockInfoMapper stockInfoMapper;
    private final Map<String, FactorCalculator> builtinCalculators = new HashMap<>();
    org.springframework.jdbc.core.JdbcTemplate clickHouseJdbcTemplate;
    /**
     * 写入阶段起始时间（用于计算速度）
     */
    @Resource
    private ClickHouseConfig clickHouseConfig;
    // 自注入，用于内部调用时走代理（解决 @Transactional 自调用失效）
    @Lazy
    @Resource
    private FactorComputeEngine self;
    /**
     * MySQL JdbcTemplate（用于查询融资融券等MySQL数据）
     */
    @Autowired
    @Qualifier("jdbcTemplate")
    private org.springframework.jdbc.core.JdbcTemplate mysqlJdbcTemplate;

    {
        // 注册内置因子（15个ACTIVE：9个原有 + 2个新增 + 4个保留）
        // 动量
        registerBuiltin(new BuiltinFactors.Momentum5Calculator());
        registerBuiltin(new BuiltinFactors.Momentum20Calculator());
        registerBuiltin(new BuiltinFactors.Momentum60Calculator());
        // 波动率
        registerBuiltin(new BuiltinFactors.Volatility20Calculator());
        // 流动性/换手率
        registerBuiltin(new BuiltinFactors.VolumeRatioCalculator2());
        registerBuiltin(new BuiltinFactors.TurnoverAnomalyCalculator());
        // 新增因子（P4/P5）
        registerBuiltin(new BuiltinFactors.AmihudIlliquidityCalculator());
        registerBuiltin(new BuiltinFactors.IndustryRelMomCalculator());
        // 情绪
        registerBuiltin(new BuiltinFactors.LimitUpCountCalculator());
        // 估值
        registerBuiltin(new BuiltinFactors.PePercentileCalculator());
        registerBuiltin(new BuiltinFactors.PeTtmCalculator());
        registerBuiltin(new BuiltinFactors.ValPbCalculator());
        registerBuiltin(new BuiltinFactors.FcfYieldCalculator());
        // 技术
        registerBuiltin(new BuiltinFactors.Atr20Calculator());
        registerBuiltin(new BuiltinFactors.SarCalculator());
        // 市值
        registerBuiltin(new BuiltinFactors.SizeCalculator());
        // 2026-07-12 新增因子（IC回测验证有效）
        registerBuiltin(new BuiltinFactors.Reversal5DCalculator());     // IR=0.32
        registerBuiltin(new BuiltinFactors.Beta60DCalculator());        // IR=0.36
        registerBuiltin(new BuiltinFactors.MarginBuyRatioCalculator()); // IR=-0.36
        // 2026-07-12 恢复注册（E策略需要VAL_DIVIDEND_YIELD）
        registerBuiltin(new BuiltinFactors.DividendYieldCalculator());
        // 2026-07-25 新增 alpha 因子（基于 MySQL 现有表，补全 ICW 信号源）
        registerBuiltin(new BuiltinFactors.EarningsSurpriseCalculator());    // 业绩超预期
        registerBuiltin(new BuiltinFactors.LhbInstNetCalculator());          // 龙虎榜机构净买
        registerBuiltin(new BuiltinFactors.InstitutionResearchCalculator()); // 机构调研热度

        // P1-5: 形态伪因子注册（6个：综合强度 + 5个形态类型）
        registerBuiltin(new BuiltinFactors.PatternStrengthCalculator());
        registerBuiltin(new BuiltinFactors.PatternBottomReversalCalculator());
        registerBuiltin(new BuiltinFactors.PatternMainTrendCalculator());
        registerBuiltin(new BuiltinFactors.PatternBreakoutCalculator());
        registerBuiltin(new BuiltinFactors.PatternSmallSwingCalculator());
        registerBuiltin(new BuiltinFactors.PatternBottomConfirmedCalculator());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setClickHouseJdbcTemplate(
            @org.springframework.beans.factory.annotation.Qualifier("clickHouseJdbcTemplate")
            org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        this.clickHouseJdbcTemplate = jdbcTemplate;
    }

    /**
     * 启动时加载ST股票名单到 LimitUpCountCalculator
     */
    @jakarta.annotation.PostConstruct
    private void loadStStockCodes() {
        try {
            Set<String> stCodes = new HashSet<>(mysqlJdbcTemplate.queryForList(
                    "SELECT DISTINCT code FROM stock_info WHERE name LIKE '%ST%'", String.class));
            BuiltinFactors.LimitUpCountCalculator.initStStockCodes(stCodes);
            log.info("Loaded {} ST stock codes into LimitUpCountCalculator filter", stCodes.size());
        } catch (Exception e) {
            log.warn("Failed to load ST stock codes, ST filter disabled: {}", e.getMessage());
        }
    }

    private void registerBuiltin(FactorCalculator calc) {
        builtinCalculators.put(calc.getFactorCode(), calc);
    }

    /**
     * 计算因子值（时间区间 × 股票池）
     */
    @Async("backtestTaskExecutor")
    public void computeFactor(FactorDefinition factor, LocalDate startDate, LocalDate endDate, List<String> symbols) {
        self.computeFactorSync(factor, startDate, endDate, symbols);
        progressService.sendProgress(factor.getFactorCode(), JobStatus.DONE.name(), 100, "因子计算完成");
    }

    /**
     * 计算因子值（使用预加载的K线数据，批量计算时共享）
     */
    @Async("backtestTaskExecutor")
    public void computeFactorWithBars(FactorDefinition factor, LocalDate startDate, LocalDate endDate,
                                      List<String> symbols, Map<String, List<MarketDailyBar>> preloadedBars) {
        String code = factor.getFactorCode();
        progressService.markRunning(code);
        try {
            if (factorFinancialService.isFinancialFactor(code)) {
                factorFinancialService.computeFinancialFactorSync(code, startDate, endDate, symbols);
                progressService.sendProgress(code, JobStatus.DONE.name(), 100, "财务因子计算完成");
                return;
            }
            self.doComputeFactorSync(factor, startDate, endDate, symbols, preloadedBars);
            progressService.sendProgress(code, JobStatus.DONE.name(), 100, "因子计算完成");
        } finally {
            progressService.unmarkRunning(code);
        }
    }

    /**
     * 同步计算因子值（供 runFactorTest 内部调用）
     * 优化：多线程并行（按日期分片）+ 批量写入（每批500条）
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public void computeFactorSync(FactorDefinition factor, LocalDate startDate, LocalDate endDate, List<String> symbols) {
        progressService.markRunning(factor.getFactorCode());
        try {
            if (factorFinancialService.isFinancialFactor(factor.getFactorCode())) {
                factorFinancialService.computeFinancialFactorSync(factor.getFactorCode(), startDate, endDate, symbols);
                return;
            }
            // 自行预加载
            LocalDate histStart = startDate.minusDays(400);
            progressService.sendProgress(factor.getFactorCode(), "COMPUTING", 0, String.format("预加载K线数据 %s ~ %s ...", histStart, endDate));
            long preloadStart = System.currentTimeMillis();
            Map<String, List<MarketDailyBar>> allBarsData = marketDataService.getBarsBatch(symbols, histStart, endDate, false);
            long preloadMs = System.currentTimeMillis() - preloadStart;
            log.info("[{}] 预加载K线完成: {} 只股票, {} ~ {}, 耗时 {}ms",
                    factor.getFactorCode(), allBarsData.size(), histStart, endDate, preloadMs);

            doComputeFactorSync(factor, startDate, endDate, symbols, allBarsData);
        } finally {
            progressService.unmarkRunning(factor.getFactorCode());
        }
    }

    /**
     * 全量计算核心逻辑（从 computeFactorSync 抽取）
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public void doComputeFactorSync(FactorDefinition factor, LocalDate startDate, LocalDate endDate,
                                    List<String> symbols, Map<String, List<MarketDailyBar>> allBarsData) {
        try {
            if (factorFinancialService.isFinancialFactor(factor.getFactorCode())) {
                factorFinancialService.computeFinancialFactorSync(factor.getFactorCode(), startDate, endDate, symbols);
                return;
            }

            log.info("[全量] 跳过删除，直接覆盖写入: factor={}, {}~{}", factor.getFactorCode(), startDate, endDate);

            // BETA_60D: 预加载上证指数K线（一次性查询，所有日期共享）
            final Map<String, List<MarketDailyBar>> effectiveBarsData;
            if ("BETA_60D".equals(factor.getFactorCode())) {
                LocalDate idxStart = startDate.minusDays(400);
                List<MarketDailyBar> indexBars = marketDataService.getBarsInRange("000001.SH", idxStart, endDate);
                Map<String, List<MarketDailyBar>> withIndex = new HashMap<>(allBarsData);
                withIndex.put("INDEX_000001", indexBars);
                effectiveBarsData = withIndex;
                log.info("[BETA_60D] 预加载上证指数K线: {} 条, {} ~ {}", indexBars.size(), idxStart, endDate);
            } else {
                effectiveBarsData = allBarsData;
            }

            List<LocalDate> tradingDates = marketDataService.getTradingDates(startDate, endDate);
            int totalDates = tradingDates.size();
            int totalStocks = symbols.size();
            long totalTasks = (long) totalDates * totalStocks;

            progressService.sendProgress(factor.getFactorCode(), "COMPUTING", 0, String.format("开始计算 [%s]，共 %d 交易日 × %d 只股票 = %,d 条", factor.getFactorCode(), totalDates, totalStocks, totalTasks));

            // ── 并行参数 ──
            int maxInternalThreads = Math.max(1, 30 / Math.max(progressService.runningCount(), 1) - 2);
            int threads = Math.min(Math.max(Runtime.getRuntime().availableProcessors(), 2), Math.min(8, maxInternalThreads));
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            log.info("[{}] [sync] Using {} threads (runningFactors={})", factor.getFactorCode(), threads, progressService.runningCount());

            AtomicInteger datesCompleted = new AtomicInteger(0);
            AtomicLong startTimeMs = new AtomicLong(System.currentTimeMillis());
            int lastPushedPct = -1;

            java.util.concurrent.ExecutorCompletionService<List<FactorValue>> completionService =
                    new java.util.concurrent.ExecutorCompletionService<>(pool);
            for (LocalDate date : tradingDates) {
                completionService.submit(() -> computeOneDateFromMemory(factor, date, symbols, effectiveBarsData));
            }
            pool.shutdown();

            List<FactorValue> writeBuffer = new ArrayList<>(5000);
            long collectStart = System.currentTimeMillis();
            long firstWriteMs = 0;
            AtomicLong rowsCollected = new AtomicLong(0);
            AtomicLong rowsWritten = new AtomicLong(0);

            for (int i = 0; i < totalDates; i++) {
                List<FactorValue> dayValues;
                try {
                    dayValues = completionService.take().get(3, java.util.concurrent.TimeUnit.MINUTES);
                } catch (java.util.concurrent.TimeoutException e) {
                    log.error("[{}] 第 {} 个任务超时 3 min，跳过", factor.getFactorCode(), i);
                    dayValues = null;
                } catch (Exception e) {
                    log.warn("[{}] 第 {} 个任务失败: {}", factor.getFactorCode(), i, e.getMessage());
                    dayValues = null;
                }

                if (dayValues != null && !dayValues.isEmpty()) {
                    writeBuffer.addAll(dayValues);
                    rowsCollected.addAndGet(dayValues.size());
                }

                datesCompleted.incrementAndGet();

                if (writeBuffer.size() >= 5000) {
                    if (firstWriteMs == 0) firstWriteMs = System.currentTimeMillis();
                    factorPersistenceService.batchSaveWithRetry(new ArrayList<>(writeBuffer), factor.getFactorCode());
                    rowsWritten.addAndGet(writeBuffer.size());
                    writeBuffer.clear();
                }

                int pct = Math.min((int) ((double) datesCompleted.get() / totalDates * 60), 60);
                if (pct > lastPushedPct || datesCompleted.get() % 10 == 0) {
                    lastPushedPct = pct;
                    long elapsed = System.currentTimeMillis() - startTimeMs.get();
                    double speed = elapsed > 0 ? (double) datesCompleted.get() / elapsed * 1000 : 0;
                    int remaining = totalDates - datesCompleted.get();
                    long etaSec = speed > 0 ? (long) (remaining / speed) : 0;
                    progressService.sendProgress(factor.getFactorCode(), "COMPUTING", pct,
                            String.format("计算中 %d/%d 交易日 (%d%%) | 已处理 %,d 行 | 已写入 %,d 行 | 速度 %.1f 日/s | 剩余约 %s",
                                    datesCompleted.get(), totalDates, pct,
                                    rowsCollected.get(),
                                    rowsWritten.get(), speed, progressService.formatEta(etaSec)),
                            etaSec);
                }
            }

            if (!writeBuffer.isEmpty()) {
                if (firstWriteMs == 0) firstWriteMs = System.currentTimeMillis();
                factorPersistenceService.batchSaveWithRetry(new ArrayList<>(writeBuffer), factor.getFactorCode());
                rowsWritten.addAndGet(writeBuffer.size());
                writeBuffer.clear();
            }

            long totalMs = System.currentTimeMillis() - collectStart;
            log.info("[{}] 完成: {} 个交易日, 已写入 {} 行, 总耗时 {}ms (收集+写入流水线)", factor.getFactorCode(), totalDates, rowsWritten.get(), totalMs);

            progressService.sendProgress(factor.getFactorCode(), "COMPUTING", 90,
                    String.format("全部写入完成，共 %,d 行，总耗时 %.1f 秒。开始归一化 %d 个交易日...",
                            rowsWritten.get(), totalMs / 1000.0, totalDates));
            factorNormalizationService.normalizeFactorValues(factor.getFactorCode(), tradingDates);
            progressService.sendProgress(factor.getFactorCode(), JobStatus.DONE.name(), 100, String.format("归一化完成，共处理 %d 个交易日，写入 %,d 条因子值", totalDates, rowsWritten.get()));

            log.info("[{}] computation done: {} dates, {} rows", factor.getFactorCode(), totalDates, rowsWritten.get());
        } catch (Exception e) {
            log.error("[{}] 全量计算异常: {}", factor.getFactorCode(), e.getMessage(), e);
            progressService.sendProgress(factor.getFactorCode(), "ERROR", -1, "全量计算异常: " + e.getMessage());
            throw e;
        }
    }

    /**
     * 查询指定因子已有数据的最新日期（用于增量续算）
     * 优先从 ClickHouse 读取
     *
     * @return 最新日期，无数据时返回 null
     */
    public LocalDate findLatestDate(String factorCode) {
        // 优先从 ClickHouse 读取（直接用 MAX(calc_date) 查询，避免全量扫描）
        if (clickHouseConfig.isEnabled()) {
            try {
                LocalDate latest = clickHouseFactorValueService.getLatestDate(factorCode);
                if (latest != null) {
                    return latest;
                }
            } catch (Exception e) {
                log.warn("[ClickHouse] findLatestDate 查询失败，回退 MySQL: {}", e.getMessage());
            }
        }

        // MySQL 回退（仅在 ClickHouse 不可用时触发）
        LambdaQueryWrapper<FactorValue> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FactorValue::getFactorCode, factorCode).orderByDesc(FactorValue::getCalcDate).last("LIMIT 1");
        FactorValue latest = factorValueMapper.selectOne(wrapper);
        return latest != null ? latest.getCalcDate() : null;
    }

    /**
     * 增量计算因子值（不清除旧数据，跳过已有日期，只算新日期）
     * 各自预加载K线（单因子调用路径）
     */
    @Async("backtestTaskExecutor")
    public void computeFactorIncremental(FactorDefinition factor, LocalDate startDate, LocalDate endDate, List<String> symbols) {
        String code = factor.getFactorCode();
        progressService.markRunning(code);

        try {
            // 财务因子走专门的增量计算逻辑（基于财报报告期，而非交易日）
            if (factorFinancialService.isFinancialFactor(code)) {
                factorFinancialService.computeFinancialFactorIncremental(code, startDate, endDate, symbols);
                return;
            }

            // 查已有日期，过滤出新日期
            List<LocalDate> tradingDates = marketDataService.getTradingDates(startDate, endDate);
            Set<LocalDate> existingDates = queryExistingDates(code, tradingDates);
            List<LocalDate> newDates = tradingDates.stream().filter(d -> !existingDates.contains(d)).toList();

            if (newDates.isEmpty()) {
                progressService.sendProgress(code, JobStatus.DONE.name(), 100, "增量计算：无新日期需要计算（已有数据到 " + (existingDates.isEmpty() ? "无" : Collections.max(existingDates)) + "）");
                return;
            }

            log.info("[{}] incremental: total {} dates, {} new (skipping {} existing)", code, tradingDates.size(), newDates.size(), existingDates.size());

            // 自行预加载K线
            LocalDate histStart = newDates.getFirst().minusDays(400);
            progressService.sendProgress(code, "COMPUTING", 0, String.format("[增量] 预加载K线数据 %s ~ %s ...", histStart, endDate));
            long preloadStart = System.currentTimeMillis();
            Map<String, List<MarketDailyBar>> allBarsData = marketDataService.getBarsBatch(symbols, histStart, endDate, false);
            long preloadMs = System.currentTimeMillis() - preloadStart;
            log.info("[{}] [增量] 预加载K线完成: {} 只股票, 耗时 {}ms", code, allBarsData.size(), preloadMs);

            doComputeIncremental(factor, newDates, existingDates, symbols, allBarsData);
        } finally {
            progressService.unmarkRunning(code);
        }
    }

    /**
     * 增量计算因子值（使用预加载的K线数据，批量计算时共享）
     * 由 FactorService.triggerBatchCompute 调用，避免每个因子各自预加载
     */
    @Async("backtestTaskExecutor")
    public void computeFactorIncrementalWithBars(FactorDefinition factor, LocalDate startDate, LocalDate endDate,
                                                 List<String> symbols, Map<String, List<MarketDailyBar>> preloadedBars) {
        String code = factor.getFactorCode();
        progressService.markRunning(code);

        try {
            // 财务因子不走预加载路径，回退到自行处理
            if (factorFinancialService.isFinancialFactor(code)) {
                factorFinancialService.computeFinancialFactorIncremental(code, startDate, endDate, symbols);
                return;
            }

            // 查已有日期，过滤出新日期
            List<LocalDate> tradingDates = marketDataService.getTradingDates(startDate, endDate);
            Set<LocalDate> existingDates = queryExistingDates(code, tradingDates);
            List<LocalDate> newDates = tradingDates.stream().filter(d -> !existingDates.contains(d)).toList();

            if (newDates.isEmpty()) {
                progressService.sendProgress(code, JobStatus.DONE.name(), 100, "增量计算：无新日期需要计算（已有数据到 " + (existingDates.isEmpty() ? "无" : Collections.max(existingDates)) + "）");
                return;
            }

            log.info("[{}] incremental[共享预加载]: total {} dates, {} new (skipping {} existing)", code, tradingDates.size(), newDates.size(), existingDates.size());
            progressService.sendProgress(code, "COMPUTING", 0, "[增量] 使用共享K线数据，跳过预加载");

            doComputeIncremental(factor, newDates, existingDates, symbols, preloadedBars);
        } finally {
            progressService.unmarkRunning(code);
        }
    }

    /**
     * 查询指定因子在给定交易日范围内已存在的日期集合
     */
    private Set<LocalDate> queryExistingDates(String factorCode, List<LocalDate> tradingDates) {
        if (tradingDates.isEmpty()) {
            return Collections.emptySet();
        }
        Set<LocalDate> dates;
        if (clickHouseConfig.isEnabled()) {
            try {
                List<FactorValue> values = clickHouseFactorValueService.findByFactorCodeAndDateRange(factorCode, tradingDates.getFirst(), tradingDates.getLast());
                dates = values.stream().map(FactorValue::getCalcDate).collect(Collectors.toSet());
            } catch (Exception e) {
                log.warn("[ClickHouse] 增量计算已有日期查询失败，回退 MySQL: {}", e.getMessage());
                dates = queryExistingDatesFromMySQL(factorCode, tradingDates);
            }
        } else {
            dates = queryExistingDatesFromMySQL(factorCode, tradingDates);
        }
        return dates;
    }

    private Set<LocalDate> queryExistingDatesFromMySQL(String factorCode, List<LocalDate> tradingDates) {
        LambdaQueryWrapper<FactorValue> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FactorValue::getFactorCode, factorCode)
                .ge(FactorValue::getCalcDate, tradingDates.getFirst())
                .le(FactorValue::getCalcDate, tradingDates.getLast())
                .select(FactorValue::getCalcDate)
                .groupBy(FactorValue::getCalcDate);
        return new HashSet<>(factorValueMapper.selectList(wrapper).stream().map(FactorValue::getCalcDate).toList());
    }

    /**
     * 增量计算核心逻辑（从 computeFactorIncremental 抽取）
     * 负责并行计算每个交易日的因子值 + 流水线写入 + 归一化
     */
    private void doComputeIncremental(FactorDefinition factor, List<LocalDate> newDates, Set<LocalDate> existingDates,
                                      List<String> symbols, Map<String, List<MarketDailyBar>> allBarsData) {
        String code = factor.getFactorCode();
        int totalDates = newDates.size();
        int totalStocks = symbols.size();
        long totalTasks = (long) totalDates * totalStocks;

        try {
            // 诊断：检查 allBarsData 是否为空
            long totalBars = allBarsData.values().stream().mapToLong(List::size).sum();
            long nonEmptySymbols = allBarsData.values().stream().filter(v -> !v.isEmpty()).count();
            log.info("[{}] [诊断] symbols={}, allBarsData entries={}, nonEmpty={}, totalBars={}", code, symbols.size(), allBarsData.size(), nonEmptySymbols, totalBars);
            if (allBarsData.isEmpty() || totalBars == 0) {
                log.warn("[{}] allBarsData 为空！symbols前5={}", code, symbols.subList(0, Math.min(5, symbols.size())));
            }

            // BETA_60D: 预加载上证指数K线
            final Map<String, List<MarketDailyBar>> effectiveBarsData;
            if ("BETA_60D".equals(code) && newDates != null && !newDates.isEmpty()) {
                LocalDate idxStart = newDates.get(0).minusDays(400);
                LocalDate idxEnd = newDates.get(newDates.size() - 1);
                List<MarketDailyBar> indexBars = marketDataService.getBarsInRange("000001.SH", idxStart, idxEnd);
                Map<String, List<MarketDailyBar>> withIndex = new HashMap<>(allBarsData);
                withIndex.put("INDEX_000001", indexBars);
                effectiveBarsData = withIndex;
                log.info("[BETA_60D] [增量] 预加载上证指数K线: {} 条", indexBars.size());
            } else {
                effectiveBarsData = allBarsData;
            }

            // ── 并行参数 ──
            // 预加载后不再需要DB连接，可以更激进地使用线程
            int maxInternalThreads = Math.max(1, 30 / Math.max(progressService.runningCount(), 1) - 2);
            int threads = Math.min(Math.max(Runtime.getRuntime().availableProcessors(), 2), Math.min(8, maxInternalThreads));
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            log.info("[{}] [增量] Using {} threads (runningFactors={}, newDates={})", code, threads, progressService.runningCount(), totalDates);

            progressService.sendProgress(code, "COMPUTING", 1, String.format("[增量] 开始计算 [%s]，新增 %d 交易日 × %d 只股票 = %,d 条（跳过 %d 已有）", code, totalDates, totalStocks, totalTasks, existingDates.size()));

            AtomicInteger datesCompleted = new AtomicInteger(0);
            AtomicLong startTimeMs = new AtomicLong(System.currentTimeMillis());
            int lastPushedPct = -1;

            // ── 提交每个交易日为一个任务（使用预加载的K线数据） ──
            // 使用 CompletionService：哪个日期算完先收哪个，不按提交顺序阻塞
            java.util.concurrent.ExecutorCompletionService<List<FactorValue>> completionService =
                    new java.util.concurrent.ExecutorCompletionService<>(pool);
            for (LocalDate date : newDates) {
                completionService.submit(() -> computeOneDateFromMemory(factor, date, symbols, effectiveBarsData));
            }
            pool.shutdown();

            // ── 收一个写一个（流水线，不积攒全部结果）──
            List<FactorValue> writeBuffer = new ArrayList<>(5000);
            long collectStart = System.currentTimeMillis();
            long firstWriteMs = 0;
            AtomicLong rowsCollected = new AtomicLong(0); // 已收集总行数（含缓冲区）
            AtomicLong rowsWritten = new AtomicLong(0);    // 实际已写入CH的行数

            for (int i = 0; i < totalDates; i++) {
                List<FactorValue> dayValues;
                try {
                    dayValues = completionService.take().get(3, java.util.concurrent.TimeUnit.MINUTES);
                } catch (java.util.concurrent.TimeoutException e) {
                    log.error("[{}] 第 {} 个任务超时 3 min，跳过", code, i);
                    dayValues = null;
                } catch (Exception e) {
                    log.warn("[{}] 第 {} 个任务失败: {}", code, i, e.getMessage());
                    dayValues = null;
                }

                if (dayValues != null && !dayValues.isEmpty()) {
                    writeBuffer.addAll(dayValues);
                    rowsCollected.addAndGet(dayValues.size());
                }

                datesCompleted.incrementAndGet();

                // 缓冲区达阈值，立即写入（不等待全部收完）
                if (writeBuffer.size() >= 5000) {
                    if (firstWriteMs == 0) firstWriteMs = System.currentTimeMillis();
                    factorPersistenceService.batchSaveWithRetry(new ArrayList<>(writeBuffer), code);
                    rowsWritten.addAndGet(writeBuffer.size());
                    writeBuffer.clear();
                }

                // 进度推送（收集阶段占 0~60%，含部分写入）
                int pct = Math.min((int) ((double) datesCompleted.get() / totalDates * 60), 60);
                if (pct > lastPushedPct || datesCompleted.get() % 10 == 0) {
                    lastPushedPct = pct;
                    long elapsed = System.currentTimeMillis() - startTimeMs.get();
                    double speed = elapsed > 0 ? (double) datesCompleted.get() / elapsed * 1000 : 0;
                    int remaining = totalDates - datesCompleted.get();
                    long etaSec = speed > 0 ? (long) (remaining / speed) : 0;
                    progressService.sendProgress(code, "COMPUTING", pct,
                            String.format("[增量] 计算中 %d/%d 交易日 (%d%%) | 已处理 %,d 行 | 已写入 %,d 行 | 速度 %.1f 日/s | 剩余约 %s",
                                    datesCompleted.get(), totalDates, pct,
                                    rowsCollected.get(),
                                    rowsWritten.get(), speed, progressService.formatEta(etaSec)),
                            etaSec);
                }
            }

            // ── 收尾：写入剩余缓冲 ──
            if (!writeBuffer.isEmpty()) {
                if (firstWriteMs == 0) firstWriteMs = System.currentTimeMillis();
                factorPersistenceService.batchSaveWithRetry(new ArrayList<>(writeBuffer), code);
                rowsWritten.addAndGet(writeBuffer.size());
                writeBuffer.clear();
            }

            long totalMs = System.currentTimeMillis() - collectStart;
            log.info("[{}] [增量] 完成: {} 个交易日, 已写入 {} 行, 总耗时 {}ms (收集+写入流水线)", code, totalDates, rowsWritten.get(), totalMs);

            progressService.sendProgress(code, "COMPUTING", 90,
                    String.format("[增量] 全部写入完成，共 %,d 行，总耗时 %.1f 秒。开始归一化 %d 个新日期...",
                            rowsWritten.get(), totalMs / 1000.0, newDates.size()));

            // ── 归一化（只对新日期做） ──
            factorNormalizationService.normalizeFactorValues(code, newDates);
            progressService.sendProgress(code, JobStatus.DONE.name(), 100, String.format("[增量] 全部完成，新增 %,d 条", rowsWritten.get()));

            log.info("[{}] incremental done: {} new dates, {} rows", code, totalDates, rowsWritten.get());
        } catch (Exception e) {
            log.error("[{}] 增量计算异常: {}", code, e.getMessage(), e);
            progressService.sendProgress(code, "ERROR", -1, "增量计算异常: " + e.getMessage());
            throw e;
        }
    }

    /**
     * 计算单个交易日所有股票的因子值（在线程池中执行）
     */
    private List<FactorValue> computeOneDate(FactorDefinition factor, LocalDate date, List<String> symbols) {
        String factorCode = factor.getFactorCode();

        // 财务因子走单独的计算路径（基于财务报表数据，非行情K线）
        if (factorFinancialService.isFinancialFactor(factorCode)) {
            return factorFinancialService.computeOneDateFinancial(factorCode, date, symbols);
        }

        LocalDate histStart = date.minusDays(400); // 预留足够历史窗口
        LocalDateTime now = LocalDateTime.now();

        // 批量查询：一次 DB 调用替代 N 次单只查询（修复 5490 只股票串行查询卡死问题）
        Map<String, List<MarketDailyBar>> batchData = marketDataService.getBarsBatch(symbols, histStart, date);

        // 特殊因子需要预构建context
        Map<String, Object> context = new HashMap<>();
        if ("BETA_60D".equals(factorCode)) {
            context.putAll(factorContextBuilder.buildIndexReturnsContext(date, null));
            if (context.isEmpty()) return List.of();
        } else if ("MARGIN_BUY_RATIO".equals(factorCode)) {
            context.putAll(factorContextBuilder.buildMarginContext(date));
            if (context.isEmpty()) return List.of();
        } else if ("EARNINGS_SURPRISE".equals(factorCode)) {
            context.putAll(factorContextBuilder.buildEarningsContext(date));
            if (context.isEmpty()) return List.of();
        } else if ("LHB_INST_NET".equals(factorCode)) {
            context.putAll(factorContextBuilder.buildLhbContext(date));
            if (context.isEmpty()) return List.of();
        } else if ("INST_RESEARCH".equals(factorCode)) {
            context.putAll(factorContextBuilder.buildResearchContext(date));
            if (context.isEmpty()) return List.of();
        }

        List<FactorValue> results = new ArrayList<>(symbols.size());
        for (String symbol : symbols) {
            try {
                List<MarketDailyBar> history = batchData.getOrDefault(symbol, List.of());
                BigDecimal value = computeSingleValue(factor, symbol, date, history, context);
                if (value != null) {
                    String code = factorContextBuilder.parseCode(symbol);
                    FactorValue fv = FactorValue.builder().factorCode(factor.getFactorCode()).symbol(code).calcDate(date).factorVal(value).createdAt(now).build();
                    results.add(fv);
                }
            } catch (Exception ignored) {
                log.error("[FactorComputeEngine] 捕获到未处理异常", ignored);
            }
        }
        return results;
    }

    /**
     * 计算单个交易日所有股票的因子值（使用预加载的K线数据，不再查DB）
     * 优化：线程安全（只读），多线程可并行执行；用二分查找截取历史K线替代 stream filter
     */
    private List<FactorValue> computeOneDateFromMemory(FactorDefinition factor, LocalDate date,
                                                       List<String> symbols,
                                                       Map<String, List<MarketDailyBar>> allBarsData) {
        String factorCode = factor.getFactorCode();

        if (factorFinancialService.isFinancialFactor(factorCode)) {
            return factorFinancialService.computeOneDateFinancial(factorCode, date, symbols);
        }

        // 特殊因子需要预构建context
        Map<String, Object> context = new HashMap<>();
        if ("INDUSTRY_REL_MOM".equals(factorCode)) {
            context.putAll(factorContextBuilder.buildIndustryMomContext(date, symbols, allBarsData));
            if (context.isEmpty()) {
                log.debug("[INDUSTRY_REL_MOM] 无法构建行业context: date={}", date);
                return List.of();
            }
        } else if ("BETA_60D".equals(factorCode)) {
            context.putAll(factorContextBuilder.buildIndexReturnsContext(date, allBarsData));
            if (context.isEmpty()) {
                log.debug("[BETA_60D] 无法构建指数收益context: date={}", date);
                return List.of();
            }
        } else if ("MARGIN_BUY_RATIO".equals(factorCode)) {
            context.putAll(factorContextBuilder.buildMarginContext(date));
            if (context.isEmpty()) {
                log.debug("[MARGIN_BUY_RATIO] 无融资融券数据: date={}", date);
                return List.of();
            }
        } else if ("EARNINGS_SURPRISE".equals(factorCode)) {
            context.putAll(factorContextBuilder.buildEarningsContext(date));
            if (context.isEmpty()) return List.of();
        } else if ("LHB_INST_NET".equals(factorCode)) {
            context.putAll(factorContextBuilder.buildLhbContext(date));
            if (context.isEmpty()) return List.of();
        } else if ("INST_RESEARCH".equals(factorCode)) {
            context.putAll(factorContextBuilder.buildResearchContext(date));
            if (context.isEmpty()) return List.of();
        }

        LocalDateTime now = LocalDateTime.now();
        List<FactorValue> results = new ArrayList<>(symbols.size());

        int emptyCount = 0;
        for (String symbol : symbols) {
            try {
                List<MarketDailyBar> allBars = allBarsData.getOrDefault(symbol, List.of());
                if (allBars.isEmpty()) {
                    emptyCount++;
                    continue;
                }

                int lo = 0, hi = allBars.size();
                while (lo < hi) {
                    int mid = (lo + hi) >>> 1;
                    if (allBars.get(mid).getTradeDate().isAfter(date)) {
                        hi = mid;
                    } else {
                        lo = mid + 1;
                    }
                }
                if (lo == 0) continue;

                List<MarketDailyBar> history = allBars.subList(0, lo);
                // INDUSTRY_REL_MOM: per-stock context with its industry average
                Map<String, Object> stockContext = context;
                if ("INDUSTRY_REL_MOM".equals(factorCode) && !context.isEmpty()) {
                    String code = factorContextBuilder.parseCode(symbol);
                    String industry = (String) context.getOrDefault("industry_" + code, "");
                    Object avgMomObj = context.get("industryAvgMom_" + industry);
                    stockContext = Map.of("industry", industry,
                            "industryAvgMom20", avgMomObj != null ? avgMomObj : 0.0);
                }

                BigDecimal value = computeSingleValue(factor, symbol, date, history, stockContext);
                if (value != null) {
                    String code = factorContextBuilder.parseCode(symbol);
                    FactorValue fv = FactorValue.builder()
                            .factorCode(factor.getFactorCode())
                            .symbol(code)
                            .calcDate(date)
                            .factorVal(value)
                            .createdAt(now)
                            .build();
                    results.add(fv);
                }
            } catch (Exception ignored) {
                log.error("[FactorComputeEngine] 捕获到未处理异常", ignored);
            }
        }
        if (results.isEmpty() && emptyCount == symbols.size()) {
            log.warn("[{}] [诊断] computeOneDateFromMemory: date={}, 所有{}只bars为空, allBarsData keys前5={}",
                    factorCode, date, emptyCount,
                    allBarsData.keySet().stream().limit(5).collect(java.util.stream.Collectors.joining(",")));
        }
        return results;
    }

    /**
     * 计算单个因子值（带context参数）
     */
    private BigDecimal computeSingleValue(FactorDefinition factor, String symbol, LocalDate calcDate,
                                          List<MarketDailyBar> history, Map<String, Object> context) {
        if (factor.getFactorType() == FactorDefinition.FactorType.BUILTIN
                || factor.getFactorType() == FactorDefinition.FactorType.PATTERN) {
            FactorCalculator calc = builtinCalculators.get(factor.getFactorCode());
            if (calc != null) {
                return calc.calculate(symbol, calcDate, history, context);
            }
        } else if (factor.getFactorType() == FactorDefinition.FactorType.SCRIPTED && factor.getScriptCode() != null) {
            return scriptedEngine.calculate(factor.getScriptCode(), factor.getFactorCode(), symbol, calcDate, history, context);
        }
        return null;
    }

    /**
     * 执行因子测试（IC分析 + 分组回测）
     * 产出：IC序列、分组收益、分组净值曲线、多空净值、单调性、IR、统计显著性
     * <p>实现已下沉至 {@link FactorTestEngine}，此处仅保留 {@code @Async} 异步语义与对外签名。</p>
     */
    @Async("backtestTaskExecutor")
    public void runFactorTest(FactorTestReport report, FactorDefinition factor) {
        factorTestEngine.runFactorTest(report, factor);
    }

    /**
     * 返回当前正在计算的因子代码集合（供 Controller 暴露给前端）
     */
    public Set<String> getRunningFactorCodes() {
        return progressService.getRunningFactorCodes();
    }
}
