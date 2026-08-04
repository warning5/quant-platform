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
     */
    @Async("backtestTaskExecutor")
    public void runFactorTest(FactorTestReport report, FactorDefinition factor) {
        log.info("Running factor test for [{}], report id: {}, pool={}, freq={}", factor.getFactorCode(), report.getId(), report.getStockPool(), report.getRebalanceFreq());

        try {
            report.setStatus(FactorTestReport.TestStatus.RUNNING);
            testReportMapper.updateById(report);

            // ── 自动检查并计算因子值（无数据时同步计算，在异步线程内不影响HTTP） ──
            // 优先从 ClickHouse 读取
            long valueCount = 0;
            if (clickHouseConfig.isEnabled()) {
                try {
                    List<FactorValue> values = clickHouseFactorValueService.findByFactorCodeAndDateRange(factor.getFactorCode(), report.getStartDate(), report.getEndDate());
                    valueCount = values.size();
                } catch (Exception e) {
                    log.warn("[ClickHouse] 因子值数量检查失败，回退 MySQL: {}", e.getMessage());
                }
            }
            if (valueCount == 0) {
                valueCount = factorValueMapper.selectCount(new LambdaQueryWrapper<FactorValue>().eq(FactorValue::getFactorCode, factor.getFactorCode()).ge(FactorValue::getCalcDate, report.getStartDate()).le(FactorValue::getCalcDate, report.getEndDate()));
            }
            if (valueCount == 0) {
                log.info("Factor [{}] has no values, computing before test...", factor.getFactorCode());
                progressService.sendProgress(factor.getFactorCode(), "TEST_START", 1, "因子值不存在，正在自动计算...");
                try {
                    List<String> symbols = marketDataService.getAllSymbols();
                    self.computeFactorSync(factor, report.getStartDate(), report.getEndDate(), symbols);
                    progressService.sendProgress(factor.getFactorCode(), "TEST_START", 5, "因子值计算完成，开始检测");
                } catch (Exception e) {
                    log.error("Auto compute failed for [{}]", factor.getFactorCode(), e);
                    report.setStatus(FactorTestReport.TestStatus.FAILED);
                    report.setErrorMessage("因子值自动计算失败: " + e.getMessage());
                    testReportMapper.updateById(report);
                    progressService.sendProgress(factor.getFactorCode(), JobStatus.FAILED.name(), 0, "因子值计算失败: " + e.getMessage());
                    return;
                }
            }

            progressService.sendProgress(factor.getFactorCode(), "TEST_START", 6, "开始因子检测");

            List<LocalDate> allDates = marketDataService.getTradingDates(report.getStartDate(), report.getEndDate());
            if (allDates.size() < 2) throw new RuntimeException("测试日期不足");
            progressService.sendProgress(factor.getFactorCode(), "TEST_START", 3, "获取交易日期完成，共" + allDates.size() + "个交易日");

            // ── 调仓频率过滤：仅保留符合调仓周期的日期 ──────────────
            List<LocalDate> dates = filterByRebalanceFreq(allDates, report.getRebalanceFreq());
            if (dates.size() < 2) throw new RuntimeException("调仓周期过滤后日期不足");
            progressService.sendProgress(factor.getFactorCode(), "TEST_START", 5, "调仓频率过滤完成，有效交易日" + dates.size() + "个");

            // ── 股票池白名单 ────────────────────────────────────────
            Set<String> poolSymbols = getStockPoolSymbols(report.getStockPool());
            String poolDesc = poolSymbols.isEmpty() ? "全A（不限制）" : poolSymbols.size() + "只股票";
            progressService.sendProgress(factor.getFactorCode(), "TEST_START", 6, "股票池加载完成，" + poolDesc);

            final int GROUP_COUNT = 5;

            // ── 预查因子值有数据的日期（避免逐日查询空数据浪费时间） ───
            Set<LocalDate> validDates = new HashSet<>();
            if (clickHouseConfig.isEnabled()) {
                try {
                    List<LocalDate> datesWithData = clickHouseFactorValueService.findDistinctDatesByFactorCode(
                            factor.getFactorCode(), report.getStartDate(), report.getEndDate());
                    validDates.addAll(datesWithData);
                } catch (Exception e) {
                    log.warn("[ClickHouse] 预查因子值日期失败: {}", e.getMessage());
                }
            }
            long datesWithFactor = dates.stream().filter(validDates::contains).count();
            long datesWithoutFactor = dates.size() - datesWithFactor;
            progressService.sendProgress(factor.getFactorCode(), "TEST_START", 7, String.format(
                    "因子值日期扫描完成：有数据 %d 天，无数据 %d 天（将跳过）", datesWithFactor, datesWithoutFactor));

            // 如果有效日期不足，提前结束
            if (datesWithFactor < 1) {
                report.setStatus(FactorTestReport.TestStatus.COMPLETED);
                report.setIcMean(bd(0));
                report.setIcStd(bd(0));
                report.setIcir(bd(0));
                report.setIcPositiveRate(bd(0));
                report.setTopGroupReturn(bd(0));
                report.setBottomGroupReturn(bd(0));
                report.setLongShortReturn(bd(0));
                report.setMonotonicity(bd(0));
                report.setGroupCount(GROUP_COUNT);
                report.setErrorMessage("检测区间内无因子值数据，无法进行检测");
                testReportMapper.updateById(report);
                progressService.sendProgress(factor.getFactorCode(), JobStatus.COMPLETED.name(), 100,
                        "检测完成：检测区间内无因子值数据，请先计算因子值或调整检测日期范围");
                return;
            }

            // ── IC 序列 ──────────────────────────────────────────
            List<Double> icList = new ArrayList<>();
            List<Double> rankIcList = new ArrayList<>();
            List<Map<String, Object>> icSeriesData = new ArrayList<>();

            // ── 分组累计收益（每日收益累加，用于计算年化） ──────────
            double[] groupTotalReturns = new double[GROUP_COUNT];
            List<double[]> groupDailyReturnsList = new ArrayList<>();

            // ── 净值序列 ─────────────────────────────────────────
            double[] groupNavs = new double[GROUP_COUNT];
            Arrays.fill(groupNavs, 1.0);
            double benchmarkNav = 1.0;

            List<Map<String, Object>> groupNavData = new ArrayList<>();
            List<Map<String, Object>> longShortNavData = new ArrayList<>();

            // ── 多空净值 ─────────────────────────────────────────
            double lsTopNav = 1.0;
            double lsBottomNav = 1.0;
            double lsNetNav = 1.0;

            // ── 用于计算主动指标 ─────────────────────────────────
            List<Double> topGroupDailyList = new ArrayList<>();  // 多头组日收益
            List<Double> benchmarkDailyList = new ArrayList<>();  // 基准日收益
            List<Double> topActiveReturnList = new ArrayList<>();  // 多头超额日收益

            int totalDays = dates.size() - 1;
            int processed = 0;
            int skippedNoData = 0;
            int skippedNoReturn = 0;

            // 多 lag IC 累积（用于衰减分析）
            int[] DECAY_LAGS = {1, 5, 10, 20};
            @SuppressWarnings("unchecked")
            List<Double>[] icListLag = new List[DECAY_LAGS.length];
            for (int i = 0; i < DECAY_LAGS.length; i++) {
                icListLag[i] = new ArrayList<>();
            }

            // ── 因子换手率追踪（#6 修复） ─────────────────────────────
            List<Set<String>> topGroupHistory = new ArrayList<>();      // 每期Top组股票集合
            List<double[]> factorCrossSectionHistory = new ArrayList<>(); // 每期截面因子值(用于自相关)

            for (int di = 0; di < dates.size() - 1; di++) {
                LocalDate calcDate = dates.get(di);
                // ── 快速跳过：预查已确定无因子值的日期 ──
                if (!validDates.contains(calcDate)) {
                    skippedNoData++;
                    appendNavPoint(groupNavData, calcDate, groupNavs, benchmarkNav);
                    appendLsNavPoint(longShortNavData, calcDate, lsTopNav, lsBottomNav, lsNetNav);
                    double[] zeros = new double[GROUP_COUNT];
                    groupDailyReturnsList.add(zeros);
                    topGroupDailyList.add(0.0);
                    benchmarkDailyList.add(0.0);
                    topActiveReturnList.add(0.0);
                    processed++;
                    continue;
                }

                // 从 ClickHouse 读取因子值（不降级 MySQL）
                List<FactorValue> factorValues = List.of();
                if (clickHouseConfig.isEnabled()) {
                    try {
                        factorValues = clickHouseFactorValueService.findByFactorCodeAndDate(factor.getFactorCode(), calcDate);
                    } catch (Exception e) {
                        log.warn("[ClickHouse] 因子值查询失败: {}", e.getMessage());
                    }
                } else {
                    LambdaQueryWrapper<FactorValue> wrapper = new LambdaQueryWrapper<>();
                    wrapper.eq(FactorValue::getFactorCode, factor.getFactorCode()).eq(FactorValue::getCalcDate, calcDate).orderByAsc(FactorValue::getSymbol);
                    factorValues = factorValueMapper.selectList(wrapper);
                }

                // 按股票池过滤
                if (!poolSymbols.isEmpty()) {
                    factorValues = factorValues.stream().filter(fv -> poolSymbols.contains(fv.getSymbol())).toList();
                }

                if (factorValues.size() < GROUP_COUNT * 2) {
                    skippedNoData++;
                    appendNavPoint(groupNavData, calcDate, groupNavs, benchmarkNav);
                    appendLsNavPoint(longShortNavData, calcDate, lsTopNav, lsBottomNav, lsNetNav);
                    double[] zeros = new double[GROUP_COUNT];
                    groupDailyReturnsList.add(zeros);
                    topGroupDailyList.add(0.0);
                    benchmarkDailyList.add(0.0);
                    topActiveReturnList.add(0.0);
                    processed++;
                    continue;
                }

                // 分批获取当期+多期前向行情（lag=1/5/10/20，用于IC衰减分析）
                List<String> symbols = factorValues.stream().map(FactorValue::getSymbol).toList();
                final int BATCH = 1000;
                Map<String, List<MarketDailyBar>> currBars = new HashMap<>();
                // lag → symbol → forwardReturn
                Map<Integer, Map<String, Double>> lagReturns = new LinkedHashMap<>();

                // 当期价格
                for (int b = 0; b < symbols.size(); b += BATCH) {
                    List<String> batch = symbols.subList(b, Math.min(b + BATCH, symbols.size()));
                    currBars.putAll(marketDataService.getBarsBatch(batch, calcDate, calcDate));
                }
                // 各 lag 的前向收益
                for (int lag : DECAY_LAGS) {
                    int fwdIdx = di + lag;
                    if (fwdIdx >= dates.size()) continue;
                    LocalDate fwdDate = dates.get(fwdIdx);
                    Map<String, List<MarketDailyBar>> fwdBars = new HashMap<>();
                    for (int b = 0; b < symbols.size(); b += BATCH) {
                        List<String> batch = symbols.subList(b, Math.min(b + BATCH, symbols.size()));
                        fwdBars.putAll(marketDataService.getBarsBatch(batch, fwdDate, fwdDate));
                    }
                    Map<String, Double> retMap = new HashMap<>();
                    for (FactorValue fv : factorValues) {
                        String sym = fv.getSymbol();
                        List<MarketDailyBar> curr = currBars.get(sym);
                        List<MarketDailyBar> fwd = fwdBars.get(sym);
                        if (curr != null && !curr.isEmpty() && fwd != null && !fwd.isEmpty()) {
                            double r = fwd.getFirst().getClose().doubleValue()
                                    / curr.getFirst().getClose().doubleValue() - 1;
                            retMap.put(sym, r);
                        }
                    }
                    lagReturns.put(lag, retMap);
                }

                // 用 lag=1 的收益作为下期收益（原有逻辑保持不变）
                Map<String, Double> nextReturns = lagReturns.get(1);
                if (nextReturns == null) {
                    skippedNoReturn++;
                    appendNavPoint(groupNavData, calcDate, groupNavs, benchmarkNav);
                    appendLsNavPoint(longShortNavData, calcDate, lsTopNav, lsBottomNav, lsNetNav);
                    double[] zeros = new double[GROUP_COUNT];
                    groupDailyReturnsList.add(zeros);
                    topGroupDailyList.add(0.0);
                    benchmarkDailyList.add(0.0);
                    topActiveReturnList.add(0.0);
                    processed++;
                    continue;
                }

                List<FactorValue> valid = factorValues.stream()
                        .filter(fv -> nextReturns.containsKey(fv.getSymbol())).toList();

                if (valid.size() < GROUP_COUNT * 2) {
                    skippedNoReturn++;
                    appendNavPoint(groupNavData, calcDate, groupNavs, benchmarkNav);
                    appendLsNavPoint(longShortNavData, calcDate, lsTopNav, lsBottomNav, lsNetNav);
                    double[] zeros = new double[GROUP_COUNT];
                    groupDailyReturnsList.add(zeros);
                    topGroupDailyList.add(0.0);
                    benchmarkDailyList.add(0.0);
                    topActiveReturnList.add(0.0);
                    processed++;
                    continue;
                }

                // ── IC 计算（lag=1 原有逻辑） ───────────────────────────────
                double[] fValues = valid.stream().mapToDouble(fv -> fv.getFactorVal().doubleValue()).toArray();
                double[] returns = valid.stream().mapToDouble(fv -> nextReturns.get(fv.getSymbol())).toArray();
                double[] rankVals = valid.stream().mapToDouble(fv -> fv.getRankValue() == null ? 0 : fv.getRankValue().doubleValue()).toArray();

                double ic = pearsonCorr(fValues, returns);
                double rankIc = pearsonCorr(rankVals, returns);

                if (!Double.isNaN(ic)) icList.add(ic);
                if (!Double.isNaN(rankIc)) rankIcList.add(rankIc);

                // ── 多 lag IC 累积（用于衰减分析） ──────────────────────────
                for (int li = 0; li < DECAY_LAGS.length; li++) {
                    int lag = DECAY_LAGS[li];
                    Map<String, Double> lr = lagReturns.get(lag);
                    if (lr == null) continue;
                    List<FactorValue> vLag = factorValues.stream()
                            .filter(fv -> lr.containsKey(fv.getSymbol())).toList();
                    if (vLag.size() < GROUP_COUNT * 2) continue;
                    double[] fLag = vLag.stream().mapToDouble(fv -> fv.getFactorVal().doubleValue()).toArray();
                    double[] rLag = vLag.stream().mapToDouble(fv -> lr.get(fv.getSymbol())).toArray();
                    double icLag = pearsonCorr(fLag, rLag);
                    if (!Double.isNaN(icLag)) icListLag[li].add(icLag);
                }

                Map<String, Object> icPoint = new HashMap<>();
                icPoint.put("date", calcDate.toString());
                icPoint.put("ic", Double.isNaN(ic) ? 0 : round4(ic));
                icPoint.put("rankIc", Double.isNaN(rankIc) ? 0 : round4(rankIc));
                icSeriesData.add(icPoint);

                // ── 分组收益 ─────────────────────────────────────
                List<FactorValue> sortedByFactor = valid.stream().sorted(Comparator.comparingDouble(fv -> fv.getFactorVal().doubleValue())).toList();
                int groupSize = sortedByFactor.size() / GROUP_COUNT;

                double[] todayGroupRet = new double[GROUP_COUNT];
                double benchmarkRet = valid.stream().mapToDouble(fv -> nextReturns.getOrDefault(fv.getSymbol(), 0.0)).average().orElse(0);

                for (int g = 0; g < GROUP_COUNT; g++) {
                    int from = g * groupSize;
                    int to = (g == GROUP_COUNT - 1) ? sortedByFactor.size() : (g + 1) * groupSize;
                    if (from >= to) continue;
                    double gRet = sortedByFactor.subList(from, to).stream().mapToDouble(fv -> nextReturns.getOrDefault(fv.getSymbol(), 0.0)).average().orElse(0);
                    todayGroupRet[g] = gRet;
                    groupTotalReturns[g] += gRet;
                }
                groupDailyReturnsList.add(todayGroupRet.clone());

                // 记录多头组 + 基准日收益（用于主动指标）
                double topRet = todayGroupRet[GROUP_COUNT - 1];
                topGroupDailyList.add(topRet);
                benchmarkDailyList.add(benchmarkRet);
                topActiveReturnList.add(topRet - benchmarkRet);

                // ── 记录 Top 组股票 + 截面因子值（用于换手率计算） ──
                if (groupSize > 0) {
                    int topFrom = (GROUP_COUNT - 1) * groupSize;
                    Set<String> topSymbols = new java.util.LinkedHashSet<>();
                    for (int k = topFrom; k < sortedByFactor.size(); k++) {
                        topSymbols.add(sortedByFactor.get(k).getSymbol());
                    }
                    topGroupHistory.add(topSymbols);

                    // 保存截面因子值（用于自相关换手率 corr(f_t, f_{t-1})）
                    double[] crossSection = valid.stream()
                            .mapToDouble(fv -> fv.getFactorVal().doubleValue())
                            .toArray();
                    factorCrossSectionHistory.add(crossSection);
                }

                // ── 更新净值 ─────────────────────────────────────
                for (int g = 0; g < GROUP_COUNT; g++) {
                    groupNavs[g] *= (1 + todayGroupRet[g]);
                }
                benchmarkNav *= (1 + benchmarkRet);

                double bottomRet = todayGroupRet[0];
                lsTopNav *= (1 + topRet - benchmarkRet);
                lsBottomNav *= (1 + bottomRet - benchmarkRet);
                lsNetNav *= (1 + topRet - bottomRet);

                appendNavPoint(groupNavData, calcDate, groupNavs, benchmarkNav);
                appendLsNavPoint(longShortNavData, calcDate, lsTopNav, lsBottomNav, lsNetNav);

                processed++;
                int pct = 10 + (int) ((double) processed / totalDays * 85);

                // 每期推送详细日志（IC + 分组收益 + 股票数）
                StringBuilder detail = new StringBuilder();
                detail.append(String.format("[%s] 股票:%d | IC:%.4f | RankIC:%.4f | 分组收益:", calcDate, valid.size(), Double.isNaN(ic) ? 0 : ic, Double.isNaN(rankIc) ? 0 : rankIc));
                for (int g = 0; g < GROUP_COUNT; g++) {
                    detail.append(String.format(" G%d:%.2f%%", g + 1, todayGroupRet[g] * 100));
                }
                detail.append(String.format(" | 基准:%.2f%%", benchmarkRet * 100));
                progressService.sendProgress(factor.getFactorCode(), "TESTING", pct, detail.toString());
            }

            // ── 跳过日期汇总日志 ──
            if (skippedNoData > 0 || skippedNoReturn > 0) {
                progressService.sendProgress(factor.getFactorCode(), "TESTING", 93, String.format(
                        "跳过汇总：无因子值 %d 天 | 无下期行情 %d 天 | 有效计算 %d 天",
                        skippedNoData, skippedNoReturn, icList.size()));
            }

            progressService.sendProgress(factor.getFactorCode(), "TESTING", 95, "回测计算完成，开始计算统计指标");

            // ── 因子换手率计算（#6 新增）────────────────────────────
            double avgTurnover = 0;
            double autoCorr1 = 0;
            int turnoverCount = 0;

            if (topGroupHistory.size() >= 2) {
                // 截面换手率：相邻两期 Top 组新增股票比例
                double sumTurnover = 0;
                for (int t = 1; t < topGroupHistory.size(); t++) {
                    Set<String> prevTop = topGroupHistory.get(t - 1);
                    Set<String> currTop = topGroupHistory.get(t);
                    // 新增 = 当前有但前期没有的
                    int newStocks = 0;
                    for (String s : currTop) {
                        if (!prevTop.contains(s)) newStocks++;
                    }
                    // 换手率 = (新增 + 离开) / Top组大小（近似用新增×2/大小）
                    int topSize = Math.max(prevTop.size(), currTop.size());
                    if (topSize > 0) {
                        sumTurnover += (double) newStocks / topSize;
                        turnoverCount++;
                    }
                }
                avgTurnover = turnoverCount > 0 ? sumTurnover / turnoverCount : 0;
            }

            if (factorCrossSectionHistory.size() >= 2) {
                // 自相关换手率：corr(截面_t, 截面_{t-1})，衡量因子值稳定性
                int nPeriods = factorCrossSectionHistory.size();
                double sumCorr = 0;
                int corrCount = 0;
                for (int t = 1; t < nPeriods; t++) {
                    double[] prev = factorCrossSectionHistory.get(t - 1);
                    double[] curr = factorCrossSectionHistory.get(t);
                    int minN = Math.min(prev.length, curr.length);
                    if (minN < 10) continue;  // 至少10只股票才算自相关
                    double c = pearsonCorr(
                            Arrays.copyOf(curr, minN),
                            Arrays.copyOf(prev, minN));
                    if (!Double.isNaN(c)) {
                        sumCorr += c;
                        corrCount++;
                    }
                }
                autoCorr1 = corrCount > 0 ? sumCorr / corrCount : 0;
            }

            log.info("[换手率] factor={} | Top组截面换手率={} | 自相关={} | 计算期数={}",
                    factor.getFactorCode(), avgTurnover, autoCorr1, turnoverCount);

            // ── 汇总：IC 统计 ────────────────────────────────────
            progressService.sendProgress(factor.getFactorCode(), "TESTING", 96, "计算IC统计指标");
            if (!icList.isEmpty()) {
                double icMean = avg(icList);
                double icStdV = std(icList);
                double icir = icStdV == 0 ? 0 : icMean / icStdV;
                long posC = icList.stream().filter(v -> v > 0).count();

                report.setIcMean(bd(icMean));
                report.setIcStd(bd(icStdV));
                report.setIcir(bd(icir));
                report.setIcPositiveRate(bd((double) posC / icList.size()));

                int n = icList.size();
                double tStat = icStdV == 0 ? 0 : icMean / (icStdV / Math.sqrt(n));
                double pValue = tStatToPValue(tStat, n - 1);
                report.setIcTStat(bd(tStat));
                report.setIcPValue(bd(pValue));
                progressService.sendProgress(factor.getFactorCode(), "TESTING", 96, String.format("IC统计完成，样本数%d | IC均值:%.4f | ICIR:%.4f | 正IC率:%.1f%% | t统计:%.2f | p值:%.4f", icList.size(), icMean, icir, (double) posC / icList.size() * 100, tStat, pValue));
            }

            if (!rankIcList.isEmpty()) {
                double rIcMean = avg(rankIcList);
                double rIcStd = std(rankIcList);
                report.setRankIcMean(bd(rIcMean));
                report.setRankIcir(bd(rIcStd == 0 ? 0 : rIcMean / rIcStd));
            }

            // ── 汇总：分组收益 ───────────────────────────────────
            report.setGroupCount(GROUP_COUNT);
            int tradingDays = Math.max(processed, 1);
            // 用复利净值计算年化收益：annualReturn = NAV^(periodsPerYear/tradingDays) - 1
            // 这比简单累加×年化因子更准确，且对 tradingDays 的微小差异不敏感
            double periodsPerYear = getPeriodsPerYear(report.getRebalanceFreq());
            double years = (double) tradingDays / periodsPerYear;

            double[] annualReturns = new double[GROUP_COUNT];
            for (int g = 0; g < GROUP_COUNT; g++) {
                double nav = groupNavs[g];
                // 复利年化：(nav)^(1/years) - 1；years<=0时退化为0
                annualReturns[g] = years <= 0 ? 0 : Math.pow(nav, 1.0 / years) - 1;
            }
            report.setTopGroupReturn(bd(annualReturns[GROUP_COUNT - 1]));
            report.setBottomGroupReturn(bd(annualReturns[0]));
            report.setLongShortReturn(bd(annualReturns[GROUP_COUNT - 1] - annualReturns[0]));

            // 单调性
            double[] groupRanks = {1, 2, 3, 4, 5};
            double mono = pearsonCorr(groupRanks, annualReturns);
            report.setMonotonicity(bd(mono));

            // ── 汇总：主动指标 ────────────────────────────────────
            if (!topActiveReturnList.isEmpty()) {
                // 主动年化波动率
                double activeVol = std(topActiveReturnList) * Math.sqrt(periodsPerYear);
                // 相对基准胜率：多头日收益 > 基准日收益的比例
                long winDays = 0;
                for (int i = 0; i < topGroupDailyList.size(); i++) {
                    if (topGroupDailyList.get(i) > benchmarkDailyList.get(i)) winDays++;
                }
                double winRate = (double) winDays / topGroupDailyList.size();
                report.setActiveVolatility(bd(activeVol));
                report.setWinRateVsBenchmark(bd(winRate));
                progressService.sendProgress(factor.getFactorCode(), "TESTING", 97, "主动指标计算完成");
            }

            // ── 汇总：最佳夏普 + 各组详细指标 ──────────────────────
            progressService.sendProgress(factor.getFactorCode(), "TESTING", 97, "计算分组收益和夏普比");
            double bestSharpe = Double.NEGATIVE_INFINITY;
            List<Map<String, Object>> groupReturnData = new ArrayList<>();
            for (int g = 0; g < GROUP_COUNT; g++) {
                Map<String, Object> gr = new HashMap<>();
                gr.put("group", "分组" + (g + 1));
                gr.put("annualReturn", round4(annualReturns[g]));

                if (!groupDailyReturnsList.isEmpty()) {
                    final int gIdx = g;
                    List<Double> gDaily = groupDailyReturnsList.stream().map(arr -> arr[gIdx]).collect(Collectors.toList());
                    double vol = std(gDaily) * Math.sqrt(periodsPerYear);
                    double sharpe = vol == 0 ? 0 : annualReturns[g] / vol;
                    if (sharpe > bestSharpe) bestSharpe = sharpe;
                    double maxDd = calcMaxDrawdown(gDaily);
                    // 胜率：日收益 > 0 的比例
                    long winDays = gDaily.stream().filter(r -> r > 0).count();
                    double winRate = (double) winDays / gDaily.size();
                    // Calmar比率：年化收益 / 最大回撤
                    double calmar = maxDd == 0 ? 0 : annualReturns[g] / maxDd;
                    // 超额收益：该组年化 - 基准年化
                    double benchmarkAnnual = years <= 0 ? 0 : Math.pow(benchmarkNav, 1.0 / years) - 1;
                    double excessReturn = annualReturns[g] - benchmarkAnnual;
                    gr.put("volatility", round4(vol));
                    gr.put("sharpe", round4(sharpe));
                    gr.put("maxDrawdown", round4(maxDd));
                    gr.put("winRate", round4(winRate));
                    gr.put("calmar", round4(calmar));
                    gr.put("excessReturn", round4(excessReturn));
                }
                groupReturnData.add(gr);
            }
            if (bestSharpe != Double.NEGATIVE_INFINITY) {
                report.setBestSharpe(bd(bestSharpe));
            }

            // 分组 IR + 多空显著性
            if (groupDailyReturnsList.size() > 1) {
                List<Double> lsDailyList = groupDailyReturnsList.stream().map(arr -> arr[GROUP_COUNT - 1] - arr[0]).collect(Collectors.toList());
                double lsAvg = avg(lsDailyList);
                double lsStd = std(lsDailyList);
                double groupIr = lsStd == 0 ? 0 : lsAvg / lsStd * Math.sqrt(periodsPerYear);
                report.setGroupIr(bd(groupIr));

                int n2 = lsDailyList.size();
                double tStat2 = lsStd == 0 ? 0 : lsAvg / (lsStd / Math.sqrt(n2));
                report.setLsPValue(bd(tStatToPValue(tStat2, n2 - 1)));
            }

            // 分组收益汇总日志
            StringBuilder groupSummary = new StringBuilder("分组年化收益：");
            for (int g = 0; g < GROUP_COUNT; g++) {
                groupSummary.append(String.format(" G%d:%.2f%%", g + 1, annualReturns[g] * 100));
            }
            groupSummary.append(String.format(" | 多空:%.2f%% | 单调性:%.4f", (annualReturns[GROUP_COUNT - 1] - annualReturns[0]) * 100, report.getMonotonicity() != null ? report.getMonotonicity().doubleValue() : 0));
            progressService.sendProgress(factor.getFactorCode(), "TESTING", 97, groupSummary.toString());

            report.setIcSeriesJson(objectMapper.writeValueAsString(icSeriesData));
            report.setGroupReturnsJson(objectMapper.writeValueAsString(groupReturnData));
            report.setGroupNavJson(objectMapper.writeValueAsString(groupNavData));
            report.setLongShortNavJson(objectMapper.writeValueAsString(longShortNavData));

            // ── 因子衰减分析(因子有效期) ──────────────────────────────────────────
            progressService.sendProgress(factor.getFactorCode(), "TESTING", 98, "计算因子衰减分析");
            Map<String, Object> decayAnalysis = computeFactorDecayAnalysis(icListLag, DECAY_LAGS);
            report.setDecayPeriods((BigDecimal) decayAnalysis.get("decayPeriods"));
            report.setHalfLifePeriods((BigDecimal) decayAnalysis.get("halfLifePeriods"));
            report.setDecayCoefficient((BigDecimal) decayAnalysis.get("decayCoefficient"));
            report.setDecayRSquared((BigDecimal) decayAnalysis.get("decayRSquared"));
            report.setDecaySeriesJson(objectMapper.writeValueAsString(decayAnalysis.get("decaySeries")));

            // 因子换手率（#6 新增）
            report.setTurnoverRate(bd(avgTurnover));
            report.setFactorAutoCorr(bd(autoCorr1));

            report.setStatus(FactorTestReport.TestStatus.COMPLETED);
            report.setCompletedAt(java.time.LocalDateTime.now());
            testReportMapper.updateById(report);

            progressService.sendProgress(factor.getFactorCode(), JobStatus.TEST_DONE.name(), 100, "因子测试完成，reportId=" + report.getId());
            log.info("Factor test [{}] completed, IC={}, mono={}", factor.getFactorCode(), report.getIcMean(), report.getMonotonicity());

        } catch (Exception e) {
            log.error("Factor test failed for [{}]", factor.getFactorCode(), e);
            report.setStatus(FactorTestReport.TestStatus.FAILED);
            String errMsg = e.getMessage();
            if (errMsg != null && errMsg.length() > 200) errMsg = errMsg.substring(0, 200) + "...";
            report.setErrorMessage(errMsg);
            testReportMapper.updateById(report);
            progressService.sendProgress(factor.getFactorCode(), "TEST_FAILED", 0, "因子检测失败: " + errMsg);
        }
    }

    /**
     * 根据调仓频率过滤日期列表（DAILY=每日, WEEKLY=每周第一个交易日, MONTHLY=每月第一个交易日）
     */
    private List<LocalDate> filterByRebalanceFreq(List<LocalDate> allDates, String freq) {
        if (freq == null || "DAILY".equalsIgnoreCase(freq)) return allDates;
        List<LocalDate> result = new ArrayList<>();
        if ("WEEKLY".equalsIgnoreCase(freq)) {
            // 每周取第一个交易日（按 ISO 周区分）
            java.time.temporal.WeekFields wf = java.time.temporal.WeekFields.ISO;
            Integer lastWeek = null;
            for (LocalDate d : allDates) {
                int week = d.get(wf.weekOfWeekBasedYear());
                if (!Integer.valueOf(week).equals(lastWeek)) {
                    result.add(d);
                    lastWeek = week;
                }
            }
        } else if ("MONTHLY".equalsIgnoreCase(freq)) {
            // 每月取第一个交易日
            Integer lastMonth = null;
            for (LocalDate d : allDates) {
                if (!Integer.valueOf(d.getMonthValue()).equals(lastMonth)) {
                    result.add(d);
                    lastMonth = d.getMonthValue();
                }
            }
        }
        return result.size() >= 2 ? result : allDates;
    }

    /**
     * 根据股票池代码返回白名单 symbol 集合（空集合 = 不限制）
     * 真实生产中应查数据库或调指数成分接口，此处用代码前缀模拟
     */
    private Set<String> getStockPoolSymbols(String stockPool) {
        if (stockPool == null || "ALL_A".equalsIgnoreCase(stockPool)) return java.util.Collections.emptySet();
        // 获取全部股票后按股池规则截取（演示：CSI300取前300，CSI500取301-800，CSI1000取801-1800，CSI800取前800）
        List<String> allSymbols = marketDataService.getAllSymbols();
        if (allSymbols.isEmpty()) return java.util.Collections.emptySet();
        List<String> sorted = new ArrayList<>(allSymbols);
        java.util.Collections.sort(sorted);
        return switch (stockPool.toUpperCase()) {
            case "CSI300" -> new HashSet<>(sorted.subList(0, Math.min(300, sorted.size())));
            case "CSI500" -> new HashSet<>(sorted.subList(Math.min(300, sorted.size()), Math.min(800, sorted.size())));
            case "CSI800" -> new HashSet<>(sorted.subList(0, Math.min(800, sorted.size())));
            case "CSI1000" ->
                    new HashSet<>(sorted.subList(Math.min(800, sorted.size()), Math.min(1800, sorted.size())));
            default -> Collections.emptySet();
        };
    }

    /**
     * 根据调仓频率和有效期数计算年化因子（每年有多少个调仓期）
     */
    private double getAnnualFactor(String freq, int periods) {
        if (freq == null || "DAILY".equalsIgnoreCase(freq)) return 252.0 / periods;
        if ("WEEKLY".equalsIgnoreCase(freq)) return 52.0 / periods;
        if ("MONTHLY".equalsIgnoreCase(freq)) return 12.0 / periods;
        return 252.0 / periods;
    }

    /**
     * 每年对应的调仓期数（用于年化波动率/IR等计算）
     */
    private double getPeriodsPerYear(String freq) {
        if (freq == null || "DAILY".equalsIgnoreCase(freq)) return 252.0;
        if ("WEEKLY".equalsIgnoreCase(freq)) return 52.0;
        if ("MONTHLY".equalsIgnoreCase(freq)) return 12.0;
        return 252.0;
    }

    // ── 私有工具方法 ───────────────────────────────────────────────

    private void appendNavPoint(List<Map<String, Object>> navData, LocalDate date, double[] groupNavs, double benchmarkNav) {
        Map<String, Object> pt = new LinkedHashMap<>();
        pt.put("date", date.toString());
        for (int g = 0; g < groupNavs.length; g++) {
            pt.put("g" + (g + 1), round4(groupNavs[g]));
        }
        pt.put("benchmark", round4(benchmarkNav));
        navData.add(pt);
    }

    private void appendLsNavPoint(List<Map<String, Object>> lsData, LocalDate date, double top, double bottom, double net) {
        Map<String, Object> pt = new LinkedHashMap<>();
        pt.put("date", date.toString());
        pt.put("top", round4(top));
        pt.put("bottom", round4(bottom));
        pt.put("net", round4(net));
        lsData.add(pt);
    }

    /**
     * Pearson 相关系数，den==0 返回 NaN
     */
    private double pearsonCorr(double[] x, double[] y) {
        int n = x.length;
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0, sumY2 = 0;
        for (int i = 0; i < n; i++) {
            sumX += x[i];
            sumY += y[i];
            sumXY += x[i] * y[i];
            sumX2 += x[i] * x[i];
            sumY2 += y[i] * y[i];
        }
        double num = n * sumXY - sumX * sumY;
        double den = Math.sqrt((n * sumX2 - sumX * sumX) * (n * sumY2 - sumY * sumY));
        return den == 0 ? Double.NaN : num / den;
    }

    /**
     * t统计量 → 双尾 p值（用正态近似 df > 30 时误差小）
     */
    private double tStatToPValue(double t, int df) {
        if (df <= 0) return 1.0;
        // 使用正态近似：p ≈ 2 * (1 - Φ(|t|))
        double abst = Math.abs(t);
        // Abramowitz and Stegun 近似
        double p = 2.0 * normalCdfComplement(abst);
        return Math.max(0, Math.min(1, p));
    }

    /**
     * 标准正态分布右尾概率 P(Z > x)
     */
    private double normalCdfComplement(double x) {
        // 使用 Horner 近似（精度 ~1e-7）
        double t = 1.0 / (1.0 + 0.2316419 * x);
        double poly = t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))));
        double phi = Math.exp(-0.5 * x * x) / Math.sqrt(2 * Math.PI);
        return phi * poly;
    }

    /**
     * 最大回撤（基于日收益序列）
     */
    private double calcMaxDrawdown(List<Double> dailyReturns) {
        double nav = 1.0, peak = 1.0, maxDd = 0;
        for (double r : dailyReturns) {
            nav *= (1 + r);
            peak = Math.max(peak, nav);
            maxDd = Math.max(maxDd, (peak - nav) / peak);
        }
        return maxDd;
    }

    private double avg(List<Double> list) {
        return list.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private double std(List<Double> list) {
        double mean = avg(list);
        double var = list.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0);
        return Math.sqrt(var);
    }

    private double round4(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0;
        return Math.round(v * 10000.0) / 10000.0;
    }

    private BigDecimal bd(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return BigDecimal.ZERO;
        return BigDecimal.valueOf(v).setScale(6, RoundingMode.HALF_UP);
    }



    /**
     * 返回当前正在计算的因子代码集合（供 Controller 暴露给前端）
     */
    public Set<String> getRunningFactorCodes() {
        return progressService.getRunningFactorCodes();
    }

    /**
     * 因子衰减分析（正确实现）
     * 对同一批因子值，分别与 lag=1/5/10/20 日前收益计算 IC，
     * 观察 |IC| 随前瞻天数衰减的规律。
     */
    private Map<String, Object> computeFactorDecayAnalysis(List<Double>[] icListLag, int[] lags) {
        List<Map<String, Object>> decaySeries = new ArrayList<>();
        double initialICAbs = 0;

        for (int i = 0; i < lags.length; i++) {
            List<Double> icValues = icListLag[i];
            if (icValues == null || icValues.isEmpty()) continue;
            double icMean = avg(icValues);
            double icAbs = Math.abs(icMean);
            if (i == 0) initialICAbs = icAbs;

            Map<String, Object> point = new LinkedHashMap<>();
            point.put("period", lags[i]);
            point.put("ic", round4(icMean));
            point.put("absIc", round4(icAbs));
            decaySeries.add(point);
        }

        if (decaySeries.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("decayPeriods", BigDecimal.ZERO);
            empty.put("halfLifePeriods", BigDecimal.ZERO);
            empty.put("decayCoefficient", BigDecimal.ZERO);
            empty.put("decayRSquared", BigDecimal.ZERO);
            empty.put("decaySeries", new ArrayList<>());
            return empty;
        }

        // 计算因子有效期: |IC| 首次低于阈值0.02的期数
        double decayPeriods = 0;
        final double IC_THRESHOLD = 0.02;
        for (Map<String, Object> point : decaySeries) {
            double absIC = ((Number) point.get("absIc")).doubleValue();
            if (absIC < IC_THRESHOLD) {
                decayPeriods = ((Number) point.get("period")).doubleValue();
                break;
            }
        }
        if (decayPeriods == 0) {
            int lastIdx = decaySeries.size() - 1;
            decayPeriods = ((Number) decaySeries.get(lastIdx).get("period")).doubleValue();
        }

        // 计算半衰期
        double halfLifePeriods = 0;
        double halfICThreshold = initialICAbs * 0.5;
        for (Map<String, Object> point : decaySeries) {
            double absIC = ((Number) point.get("absIc")).doubleValue();
            if (absIC < halfICThreshold) {
                halfLifePeriods = ((Number) point.get("period")).doubleValue();
                break;
            }
        }

        // 拟合指数衰减: |IC(t)| = |IC(0)| * exp(-λt)
        double decayCoefficient = 0;
        double decayRSquared = 0;
        try {
            List<Double> periods = new ArrayList<>();
            List<Double> absICs = new ArrayList<>();
            for (Map<String, Object> point : decaySeries) {
                int period = ((Number) point.get("period")).intValue();
                double absIC = ((Number) point.get("absIc")).doubleValue();
                if (period > 0 && absIC > 0) {
                    periods.add((double) period);
                    absICs.add(absIC);
                }
            }

            if (periods.size() >= 3) {
                // ln(|IC|) = ln(|IC(0)|) - λt
                List<Double> logICs = absICs.stream().map(Math::log).toList();
                double sumT = 0, sumLogIC = 0, sumTLogIC = 0, sumT2 = 0;
                int n = periods.size();
                for (int i = 0; i < n; i++) {
                    double t = periods.get(i);
                    double logIC = logICs.get(i);
                    sumT += t;
                    sumLogIC += logIC;
                    sumTLogIC += t * logIC;
                    sumT2 += t * t;
                }
                double slope = (n * sumTLogIC - sumT * sumLogIC) / (n * sumT2 - sumT * sumT);
                double intercept = (sumLogIC - slope * sumT) / n;
                decayCoefficient = -slope;

                // R²
                double meanLogIC = sumLogIC / n;
                double ssRes = 0, ssTot = 0;
                for (int i = 0; i < n; i++) {
                    double predicted = intercept + slope * periods.get(i);
                    double actual = logICs.get(i);
                    ssRes += Math.pow(actual - predicted, 2);
                    ssTot += Math.pow(actual - meanLogIC, 2);
                }
                decayRSquared = ssTot == 0 ? 0 : 1 - ssRes / ssTot;
            }
        } catch (Exception e) {
            log.warn("衰减曲线拟合失败: {}", e.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("decayPeriods", bd(decayPeriods));
        result.put("halfLifePeriods", bd(halfLifePeriods));
        result.put("decayCoefficient", bd(decayCoefficient));
        result.put("decayRSquared", bd(decayRSquared));
        result.put("decaySeries", decaySeries);
        return result;
    }
}
