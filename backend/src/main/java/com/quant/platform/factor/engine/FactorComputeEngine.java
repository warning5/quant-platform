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

/**
 * 因子计算调度引擎
 * 负责调度因子计算、IC分析和分组回测
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactorComputeEngine {

    private final MarketDataService marketDataService;
    private final FactorValueMapper factorValueMapper;
    private final ClickHouseFactorValueService clickHouseFactorValueService;
    private final FactorTestReportMapper testReportMapper;
    private final ScriptedFactorEngine scriptedEngine;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;
    private final StockFinancialIndicatorMapper financialIndicatorMapper;
    private final Map<String, FactorCalculator> builtinCalculators = new HashMap<>();
    private final Map<String, FinancialFactorCalculator> financialCalculators = new HashMap<>();
    // 跟踪正在计算的因子代码（供前端查询当前运行状态）
    private final java.util.Set<String> runningFactors =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    /**
     * 写入阶段起始时间（用于计算速度）
     */
    private final java.util.concurrent.atomic.AtomicLong writeStartGlobal = new java.util.concurrent.atomic.AtomicLong();
    @org.springframework.beans.factory.annotation.Autowired
    private FinancialFactors financialFactorsBean;
    @Resource
    private ClickHouseConfig clickHouseConfig;
    // 自注入，用于内部调用时走代理（解决 @Transactional 自调用失效）
    @Lazy
    @Resource
    private FactorComputeEngine self;

    {
        // 注册内置因子
        registerBuiltin(new BuiltinFactors.Momentum5Calculator());
        registerBuiltin(new BuiltinFactors.Momentum20Calculator());
        registerBuiltin(new BuiltinFactors.Momentum60Calculator());
        registerBuiltin(new BuiltinFactors.Momentum120Calculator());
        registerBuiltin(new BuiltinFactors.Reversal5Calculator());
        registerBuiltin(new BuiltinFactors.Volatility5Calculator());
        registerBuiltin(new BuiltinFactors.Volatility20Calculator());
        registerBuiltin(new BuiltinFactors.Volatility60Calculator());
        registerBuiltin(new BuiltinFactors.VolatilityRatioCalculator());
        registerBuiltin(new BuiltinFactors.AmihudCalculator());
        registerBuiltin(new BuiltinFactors.TurnoverChangeCalculator());
        registerBuiltin(new BuiltinFactors.VolumeRatioCalculator());
        registerBuiltin(new BuiltinFactors.Turnover20Calculator());
        registerBuiltin(new BuiltinFactors.SizeCalculator());
        registerBuiltin(new BuiltinFactors.Rsi5Calculator());
        registerBuiltin(new BuiltinFactors.Rsi14Calculator());
        registerBuiltin(new BuiltinFactors.MacdCalculator());
        registerBuiltin(new BuiltinFactors.KdjKCalculator());
        registerBuiltin(new BuiltinFactors.Atr20Calculator());
        registerBuiltin(new BuiltinFactors.UpperShadowCalculator());
        registerBuiltin(new BuiltinFactors.BollingerPositionCalculator());
        registerBuiltin(new BuiltinFactors.VolPriceCorr20Calculator());
        registerBuiltin(new BuiltinFactors.PriceMomAccCalculator());
        // 新增经典技术指标
        registerBuiltin(new BuiltinFactors.Psy12Calculator());
        registerBuiltin(new BuiltinFactors.Srdm30Calculator());
        registerBuiltin(new BuiltinFactors.BollMidCalculator());
        registerBuiltin(new BuiltinFactors.Mfi14Calculator());
        registerBuiltin(new BuiltinFactors.BbiCalculator());
        registerBuiltin(new BuiltinFactors.Ma5Calculator());
        registerBuiltin(new BuiltinFactors.Ema5Calculator());
        registerBuiltin(new BuiltinFactors.Wr14Calculator());
        registerBuiltin(new BuiltinFactors.ObvCalculator());
        registerBuiltin(new BuiltinFactors.Vroc12Calculator());
        registerBuiltin(new BuiltinFactors.PvtCalculator());
        registerBuiltin(new BuiltinFactors.PriceOscCalculator());
        registerBuiltin(new BuiltinFactors.Vr26Calculator());
        registerBuiltin(new BuiltinFactors.Bias6Calculator());
        registerBuiltin(new BuiltinFactors.Vstd10Calculator());
        registerBuiltin(new BuiltinFactors.Roc12Calculator());
        registerBuiltin(new BuiltinFactors.Cci14Calculator());
        registerBuiltin(new BuiltinFactors.Trix12Calculator());
        registerBuiltin(new BuiltinFactors.Vma5Calculator());
        registerBuiltin(new BuiltinFactors.Atr14Calculator());
        registerBuiltin(new BuiltinFactors.Mtm6Calculator());
        registerBuiltin(new BuiltinFactors.VoscCalculator());
        // 新增26个技术因子 (2026-04-16)
        registerBuiltin(new BuiltinFactors.ArbrCalculator());
        registerBuiltin(new BuiltinFactors.BbibollCalculator());
        registerBuiltin(new BuiltinFactors.CdpCalculator());
        registerBuiltin(new BuiltinFactors.Env14Calculator());
        registerBuiltin(new BuiltinFactors.DbcdCalculator());
        registerBuiltin(new BuiltinFactors.CrCalculator());
        registerBuiltin(new BuiltinFactors.DpoCalculator());
        registerBuiltin(new BuiltinFactors.Wr12Calculator());
        registerBuiltin(new BuiltinFactors.Vrsi6Calculator());
        registerBuiltin(new BuiltinFactors.Bias12Calculator());
        registerBuiltin(new BuiltinFactors.Bias24Calculator());
        registerBuiltin(new BuiltinFactors.RccdCalculator());
        registerBuiltin(new BuiltinFactors.DdiCalculator());
        registerBuiltin(new BuiltinFactors.CvltCalculator());
        registerBuiltin(new BuiltinFactors.VhfCalculator());
        registerBuiltin(new BuiltinFactors.SiCalculator());
        registerBuiltin(new BuiltinFactors.MassCalculator());
        registerBuiltin(new BuiltinFactors.Srmi9Calculator());
        registerBuiltin(new BuiltinFactors.VmacdCalculator());
        registerBuiltin(new BuiltinFactors.LwrCalculator());
        registerBuiltin(new BuiltinFactors.AdtmCalculator());
        registerBuiltin(new BuiltinFactors.MicdCalculator());
        registerBuiltin(new BuiltinFactors.DmaCalculator());
        registerBuiltin(new BuiltinFactors.TapiCalculator());
        registerBuiltin(new BuiltinFactors.Mi12Calculator());
        registerBuiltin(new BuiltinFactors.MtmPctCalculator());
        registerBuiltin(new BuiltinFactors.WadCalculator());
        // 新增情绪因子 (2026-04-16)
        registerBuiltin(new BuiltinFactors.LimitUpCountCalculator());
        registerBuiltin(new BuiltinFactors.TurnoverAnomalyCalculator());
        registerBuiltin(new BuiltinFactors.VolumeSurpriseCalculator());

        // 注册 DMI/ADX 趋势因子 (2026-05-17)
        registerBuiltin(new BuiltinFactors.PlusDI14Calculator());
        registerBuiltin(new BuiltinFactors.MinusDI14Calculator());
        registerBuiltin(new BuiltinFactors.Dx14Calculator());
        registerBuiltin(new BuiltinFactors.Adx14Calculator());
        registerBuiltin(new BuiltinFactors.Adx20Calculator());

        // 注册 SAR/KDJ扩展/BOLL扩展/均线排列/支撑阻力/量比 (2026-05-17)
        registerBuiltin(new BuiltinFactors.SarCalculator());
        registerBuiltin(new BuiltinFactors.KdjDCalculator());
        registerBuiltin(new BuiltinFactors.KdjJCalculator());
        registerBuiltin(new BuiltinFactors.BollUpperCalculator());
        registerBuiltin(new BuiltinFactors.BollLowerCalculator());
        registerBuiltin(new BuiltinFactors.BollWidthCalculator());
        registerBuiltin(new BuiltinFactors.MaAlignmentCalculator());
        registerBuiltin(new BuiltinFactors.NearResistanceCalculator());
        registerBuiltin(new BuiltinFactors.NearSupportCalculator());
        registerBuiltin(new BuiltinFactors.VolumeRatioCalculator2());
        // 新增 Phase5 因子 (2026-06-03)
        registerBuiltin(new BuiltinFactors.RsrsCalculator());
        registerBuiltin(new BuiltinFactors.AcOscillatorCalculator());
        registerBuiltin(new BuiltinFactors.UpDnVolCalculator());

        // 注册缠论因子 (2026-04-29)
        registerBuiltin(new ChanTheoryFactors.PenDirectionCalculator());
        registerBuiltin(new ChanTheoryFactors.TrendTypeCalculator());
        registerBuiltin(new ChanTheoryFactors.BuySellSignalCalculator());
        registerBuiltin(new ChanTheoryFactors.HubPositionCalculator());
        registerBuiltin(new ChanTheoryFactors.PenCountCalculator());

        // 注册财务因子（使用 FinancialFactorCalculator 接口）
        registerFinancial(new FinancialFactors.GrossProfitMarginCalc());
        registerFinancial(new FinancialFactors.NetProfitMarginCalc());
        registerFinancial(new FinancialFactors.RoeCalc());
        registerFinancial(new FinancialFactors.RoaCalc());
        registerFinancial(new FinancialFactors.TotalCostRatioCalc());
        registerFinancial(new FinancialFactors.PeriodExpenseRatioCalc());
        registerFinancial(new FinancialFactors.EbitMarginCalc());
        registerFinancial(new FinancialFactors.RevenueYoyCalc());
        registerFinancial(new FinancialFactors.NetProfitYoyCalc());
        registerFinancial(new FinancialFactors.OperatingProfitYoyCalc());
        registerFinancial(new FinancialFactors.TotalAssetsYoyCalc());
        registerFinancial(new FinancialFactors.EpsBasicYoyCalc());
        registerFinancial(new FinancialFactors.CurrentRatioCalc());
        registerFinancial(new FinancialFactors.QuickRatioCalc());
        registerFinancial(new FinancialFactors.DebtToAssetRatioCalc());
        registerFinancial(new FinancialFactors.DebtToEquityRatioCalc());
        registerFinancial(new FinancialFactors.ArTurnoverCalc());
        registerFinancial(new FinancialFactors.ArTurnoverDaysCalc());
        registerFinancial(new FinancialFactors.TotalAssetsTurnoverCalc());
        registerFinancial(new FinancialFactors.InventoryTurnoverCalc());
        registerFinancial(new FinancialFactors.InventoryTurnoverDaysCalc());
        registerFinancial(new FinancialFactors.OperatingCfToNpCalc());
        registerFinancial(new FinancialFactors.OperatingCfPerShareCalc());
        registerFinancial(new FinancialFactors.OperatingCfToRevenueCalc());
        registerFinancial(new FinancialFactors.BpsCalc());
        registerFinancial(new FinancialFactors.RoeStabilityCalc());
        registerFinancial(new FinancialFactors.EarningsQualitySimpleCalc());
        registerFinancial(new FinancialFactors.GrossMarginQualityCalc());
        registerFinancial(new FinancialFactors.OperatingLeverageCalc());
        registerFinancial(new FinancialFactors.CashFlowQualityCalc());
        registerFinancial(new FinancialFactors.EarningsYieldCalc());
        registerFinancial(new FinancialFactors.BookValueCalc());
        registerFinancial(new FinancialFactors.RevenueQualityCalc());
        // 自由现金流因子
        registerFinancial(new FinancialFactors.FreeCashFlowCalc());
        registerFinancial(new FinancialFactors.FreeCashFlowToOpCfCalc());
        registerFinancial(new FinancialFactors.FreeCashFlowToNpCalc());
        // 需联查原始表的财务因子（非静态内部类，延迟到@PostConstruct注册以确保bean已注入）
        // RoicCalc / InterestCoverageCalc / OperatingCfToDebtCalc 在 registerDeferred() 中注册
        log.info("Registered {} financial factor calculators (static)", financialCalculators.size());
    }

    @jakarta.annotation.PostConstruct
    private void registerDeferred() {
        // 非静态内部类需要外部类实例（Spring Bean），必须在注入完成后注册
        registerFinancial(financialFactorsBean.new RoicCalc());
        registerFinancial(financialFactorsBean.new InterestCoverageCalc());
        registerFinancial(financialFactorsBean.new OperatingCfToDebtCalc());
        // 估值因子 (2026-06-02)
        registerFinancial(financialFactorsBean.new PeTtmCalc());
        registerFinancial(financialFactorsBean.new PsTtmCalc());
        registerFinancial(financialFactorsBean.new PbCalc());
        registerFinancial(financialFactorsBean.new DividendYieldCalc());
        registerFinancial(financialFactorsBean.new FcfYieldCalc());
        // 质量因子 (Phase 2.3)
        registerFinancial(financialFactorsBean.new EarningsQualityCalc());
        registerFinancial(financialFactorsBean.new FinancialHealthCalc());
        registerFinancial(financialFactorsBean.new RevenueStabilityCalc());
        // TTM 扩展因子 (2026-06-07)
        registerFinancial(new FinancialFactors.RoeTtmCalc());
        registerFinancial(new FinancialFactors.RevenueTtmYoyCalc());
        registerFinancial(new FinancialFactors.NetProfitTtmYoyCalc());
        log.info("Registered {} financial factor calculators (total, after deferred)", financialCalculators.size());
    }

    private void registerBuiltin(FactorCalculator calc) {
        builtinCalculators.put(calc.getFactorCode(), calc);
    }

    private void registerFinancial(FinancialFactorCalculator calc) {
        financialCalculators.put(calc.getFactorCode(), calc);
    }

    /**
     * 判断是否为财务因子：按 FIN_ 前缀或已注册的财务计算器
     * 优先用前缀判断，确保新增 FIN_ 因子无需修改代码也能正确识别
     */
    private boolean isFinancialFactor(String code) {
        if (code == null) return false;
        if (code.startsWith("FIN_")) return true;
        if (code.startsWith("VAL_") || code.startsWith("QUAL_")) return true;
        return financialCalculators.containsKey(code);
    }

    /**
     * 计算因子值（时间区间 × 股票池）
     */
    @Async("backtestTaskExecutor")
    public void computeFactor(FactorDefinition factor, LocalDate startDate, LocalDate endDate, List<String> symbols) {
        self.computeFactorSync(factor, startDate, endDate, symbols);
        sendProgress(factor.getFactorCode(), "DONE", 100, "因子计算完成");
    }

    /**
     * 计算因子值（使用预加载的K线数据，批量计算时共享）
     */
    @Async("backtestTaskExecutor")
    public void computeFactorWithBars(FactorDefinition factor, LocalDate startDate, LocalDate endDate,
                                      List<String> symbols, Map<String, List<MarketDailyBar>> preloadedBars) {
        String code = factor.getFactorCode();
        runningFactors.add(code);
        try {
            if (isFinancialFactor(code)) {
                computeFinancialFactorSync(code, startDate, endDate, symbols);
                sendProgress(code, "DONE", 100, "财务因子计算完成");
                return;
            }
            self.doComputeFactorSync(factor, startDate, endDate, symbols, preloadedBars);
            sendProgress(code, "DONE", 100, "因子计算完成");
        } finally {
            runningFactors.remove(code);
        }
    }

    /**
     * 同步计算因子值（供 runFactorTest 内部调用）
     * 优化：多线程并行（按日期分片）+ 批量写入（每批500条）
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public void computeFactorSync(FactorDefinition factor, LocalDate startDate, LocalDate endDate, List<String> symbols) {
        runningFactors.add(factor.getFactorCode());
        try {
            if (isFinancialFactor(factor.getFactorCode())) {
                computeFinancialFactorSync(factor.getFactorCode(), startDate, endDate, symbols);
                return;
            }
            // 自行预加载
            LocalDate histStart = startDate.minusDays(400);
            sendProgress(factor.getFactorCode(), "COMPUTING", 0, String.format("预加载K线数据 %s ~ %s ...", histStart, endDate));
            long preloadStart = System.currentTimeMillis();
            Map<String, List<MarketDailyBar>> allBarsData = marketDataService.getBarsBatch(symbols, histStart, endDate, false);
            long preloadMs = System.currentTimeMillis() - preloadStart;
            log.info("[{}] 预加载K线完成: {} 只股票, {} ~ {}, 耗时 {}ms",
                    factor.getFactorCode(), allBarsData.size(), histStart, endDate, preloadMs);

            doComputeFactorSync(factor, startDate, endDate, symbols, allBarsData);
        } finally {
            runningFactors.remove(factor.getFactorCode());
        }
    }

    /**
     * 全量计算核心逻辑（从 computeFactorSync 抽取）
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public void doComputeFactorSync(FactorDefinition factor, LocalDate startDate, LocalDate endDate,
                                    List<String> symbols, Map<String, List<MarketDailyBar>> allBarsData) {
        try {
            if (isFinancialFactor(factor.getFactorCode())) {
                computeFinancialFactorSync(factor.getFactorCode(), startDate, endDate, symbols);
                return;
            }

            log.info("[全量] 跳过删除，直接覆盖写入: factor={}, {}~{}", factor.getFactorCode(), startDate, endDate);

            List<LocalDate> tradingDates = marketDataService.getTradingDates(startDate, endDate);
            int totalDates = tradingDates.size();
            int totalStocks = symbols.size();
            long totalTasks = (long) totalDates * totalStocks;

            sendProgress(factor.getFactorCode(), "COMPUTING", 0, String.format("开始计算 [%s]，共 %d 交易日 × %d 只股票 = %,d 条", factor.getFactorCode(), totalDates, totalStocks, totalTasks));

            // ── 并行参数 ──
            int maxInternalThreads = Math.max(1, 30 / Math.max(runningFactors.size(), 1) - 2);
            int threads = Math.min(Math.max(Runtime.getRuntime().availableProcessors(), 2), Math.min(8, maxInternalThreads));
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            log.info("[{}] [sync] Using {} threads (runningFactors={})", factor.getFactorCode(), threads, runningFactors.size());

            AtomicInteger datesCompleted = new AtomicInteger(0);
            AtomicLong rowsInserted = new AtomicLong(0);
            AtomicLong startTimeMs = new AtomicLong(System.currentTimeMillis());
            int lastPushedPct = -1;

            java.util.concurrent.ExecutorCompletionService<List<FactorValue>> completionService =
                    new java.util.concurrent.ExecutorCompletionService<>(pool);
            for (LocalDate date : tradingDates) {
                completionService.submit(() -> computeOneDateFromMemory(factor, date, symbols, allBarsData));
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
                    batchSaveWithRetry(new ArrayList<>(writeBuffer), factor.getFactorCode());
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
                    sendProgress(factor.getFactorCode(), "COMPUTING", pct,
                            String.format("计算中 %d/%d 交易日 (%d%%) | 已处理 %,d 行 | 已写入 %,d 行 | 速度 %.1f 日/s | 剩余约 %s",
                                    datesCompleted.get(), totalDates, pct,
                                    rowsCollected.get(),
                                    rowsWritten.get(), speed, formatEta(etaSec)),
                            etaSec);
                }
            }

            if (!writeBuffer.isEmpty()) {
                if (firstWriteMs == 0) firstWriteMs = System.currentTimeMillis();
                batchSaveWithRetry(new ArrayList<>(writeBuffer), factor.getFactorCode());
                rowsWritten.addAndGet(writeBuffer.size());
                writeBuffer.clear();
            }

            long totalMs = System.currentTimeMillis() - collectStart;
            log.info("[{}] 完成: {} 个交易日, 已写入 {} 行, 总耗时 {}ms (收集+写入流水线)", factor.getFactorCode(), totalDates, rowsWritten.get(), totalMs);

            sendProgress(factor.getFactorCode(), "COMPUTING", 90,
                    String.format("全部写入完成，共 %,d 行，总耗时 %.1f 秒。开始归一化 %d 个交易日...",
                            rowsWritten.get(), totalMs / 1000.0, totalDates));
            normalizeFactorValues(factor.getFactorCode(), tradingDates);
            sendProgress(factor.getFactorCode(), "DONE", 100, String.format("归一化完成，共处理 %d 个交易日，写入 %,d 条因子值", totalDates, rowsWritten.get()));

            log.info("[{}] computation done: {} dates, {} rows", factor.getFactorCode(), totalDates, rowsWritten.get());
        } catch (Exception e) {
            log.error("[{}] 全量计算异常: {}", factor.getFactorCode(), e.getMessage(), e);
            sendProgress(factor.getFactorCode(), "ERROR", -1, "全量计算异常: " + e.getMessage());
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
        // 优先从 ClickHouse 读取
        if (clickHouseConfig.isEnabled()) {
            try {
                List<FactorValue> values = clickHouseFactorValueService.findByFactorCodeAndDateRange(factorCode, LocalDate.of(2000, 1, 1), LocalDate.now());
                if (!values.isEmpty()) {
                    return values.stream().map(FactorValue::getCalcDate).max(LocalDate::compareTo).orElse(null);
                }
            } catch (Exception e) {
                log.warn("[ClickHouse] findLatestDate 查询失败，回退 MySQL: {}", e.getMessage());
            }
        }

        // MySQL 回退
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
        runningFactors.add(code);

        try {
            // 财务因子走专门的增量计算逻辑（基于财报报告期，而非交易日）
            if (isFinancialFactor(code)) {
                computeFinancialFactorIncremental(code, startDate, endDate, symbols);
                return;
            }

            // 查已有日期，过滤出新日期
            List<LocalDate> tradingDates = marketDataService.getTradingDates(startDate, endDate);
            Set<LocalDate> existingDates = queryExistingDates(code, tradingDates);
            List<LocalDate> newDates = tradingDates.stream().filter(d -> !existingDates.contains(d)).toList();

            if (newDates.isEmpty()) {
                sendProgress(code, "DONE", 100, "增量计算：无新日期需要计算（已有数据到 " + (existingDates.isEmpty() ? "无" : Collections.max(existingDates)) + "）");
                return;
            }

            log.info("[{}] incremental: total {} dates, {} new (skipping {} existing)", code, tradingDates.size(), newDates.size(), existingDates.size());

            // 自行预加载K线
            LocalDate histStart = newDates.getFirst().minusDays(400);
            sendProgress(code, "COMPUTING", 0, String.format("[增量] 预加载K线数据 %s ~ %s ...", histStart, endDate));
            long preloadStart = System.currentTimeMillis();
            Map<String, List<MarketDailyBar>> allBarsData = marketDataService.getBarsBatch(symbols, histStart, endDate, false);
            long preloadMs = System.currentTimeMillis() - preloadStart;
            log.info("[{}] [增量] 预加载K线完成: {} 只股票, 耗时 {}ms", code, allBarsData.size(), preloadMs);

            doComputeIncremental(factor, newDates, existingDates, symbols, allBarsData);
        } finally {
            runningFactors.remove(code);
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
        runningFactors.add(code);

        try {
            // 财务因子不走预加载路径，回退到自行处理
            if (isFinancialFactor(code)) {
                computeFinancialFactorIncremental(code, startDate, endDate, symbols);
                return;
            }

            // 查已有日期，过滤出新日期
            List<LocalDate> tradingDates = marketDataService.getTradingDates(startDate, endDate);
            Set<LocalDate> existingDates = queryExistingDates(code, tradingDates);
            List<LocalDate> newDates = tradingDates.stream().filter(d -> !existingDates.contains(d)).toList();

            if (newDates.isEmpty()) {
                sendProgress(code, "DONE", 100, "增量计算：无新日期需要计算（已有数据到 " + (existingDates.isEmpty() ? "无" : Collections.max(existingDates)) + "）");
                return;
            }

            log.info("[{}] incremental[共享预加载]: total {} dates, {} new (skipping {} existing)", code, tradingDates.size(), newDates.size(), existingDates.size());
            sendProgress(code, "COMPUTING", 0, "[增量] 使用共享K线数据，跳过预加载");

            doComputeIncremental(factor, newDates, existingDates, symbols, preloadedBars);
        } finally {
            runningFactors.remove(code);
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

            // ── 并行参数 ──
            // 预加载后不再需要DB连接，可以更激进地使用线程
            int maxInternalThreads = Math.max(1, 30 / Math.max(runningFactors.size(), 1) - 2);
            int threads = Math.min(Math.max(Runtime.getRuntime().availableProcessors(), 2), Math.min(8, maxInternalThreads));
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            log.info("[{}] [增量] Using {} threads (runningFactors={}, newDates={})", code, threads, runningFactors.size(), totalDates);

            sendProgress(code, "COMPUTING", 1, String.format("[增量] 开始计算 [%s]，新增 %d 交易日 × %d 只股票 = %,d 条（跳过 %d 已有）", code, totalDates, totalStocks, totalTasks, existingDates.size()));

            AtomicInteger datesCompleted = new AtomicInteger(0);
            AtomicLong startTimeMs = new AtomicLong(System.currentTimeMillis());
            int lastPushedPct = -1;

            // ── 提交每个交易日为一个任务（使用预加载的K线数据） ──
            // 使用 CompletionService：哪个日期算完先收哪个，不按提交顺序阻塞
            java.util.concurrent.ExecutorCompletionService<List<FactorValue>> completionService =
                    new java.util.concurrent.ExecutorCompletionService<>(pool);
            for (LocalDate date : newDates) {
                completionService.submit(() -> computeOneDateFromMemory(factor, date, symbols, allBarsData));
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
                    batchSaveWithRetry(new ArrayList<>(writeBuffer), code);
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
                    sendProgress(code, "COMPUTING", pct,
                            String.format("[增量] 计算中 %d/%d 交易日 (%d%%) | 已处理 %,d 行 | 已写入 %,d 行 | 速度 %.1f 日/s | 剩余约 %s",
                                    datesCompleted.get(), totalDates, pct,
                                    rowsCollected.get(),
                                    rowsWritten.get(), speed, formatEta(etaSec)),
                            etaSec);
                }
            }

            // ── 收尾：写入剩余缓冲 ──
            if (!writeBuffer.isEmpty()) {
                if (firstWriteMs == 0) firstWriteMs = System.currentTimeMillis();
                batchSaveWithRetry(new ArrayList<>(writeBuffer), code);
                rowsWritten.addAndGet(writeBuffer.size());
                writeBuffer.clear();
            }

            long totalMs = System.currentTimeMillis() - collectStart;
            log.info("[{}] [增量] 完成: {} 个交易日, 已写入 {} 行, 总耗时 {}ms (收集+写入流水线)", code, totalDates, rowsWritten.get(), totalMs);

            sendProgress(code, "COMPUTING", 90,
                    String.format("[增量] 全部写入完成，共 %,d 行，总耗时 %.1f 秒。开始归一化 %d 个新日期...",
                            rowsWritten.get(), totalMs / 1000.0, newDates.size()));

            // ── 归一化（只对新日期做） ──
            normalizeFactorValues(code, newDates);
            sendProgress(code, "DONE", 100, String.format("[增量] 全部完成，新增 %,d 条", rowsWritten.get()));

            log.info("[{}] incremental done: {} new dates, {} rows", code, totalDates, rowsWritten.get());
        } catch (Exception e) {
            log.error("[{}] 增量计算异常: {}", code, e.getMessage(), e);
            sendProgress(code, "ERROR", -1, "增量计算异常: " + e.getMessage());
            throw e;
        }
    }

    /**
     * 财务因子增量计算（基于财报报告期，而非交易日）
     * 财务数据每年只有 4 份报告（一季报、半年报、三季报、年报），
     * 因此只需要在财报的 end_date 上计算，而不是按每个交易日计算。
     */
    private void computeFinancialFactorIncremental(String factorCode, LocalDate startDate, LocalDate endDate, List<String> symbols) {
        FinancialFactorCalculator calculator = financialCalculators.get(factorCode);
        if (calculator == null) {
            log.error("[{}] 财务因子计算器未找到", factorCode);
            return;
        }

        // 获取所有财报报告期（end_date），按日期范围过滤
        LambdaQueryWrapper<StockFinancialIndicator> wrapper = new LambdaQueryWrapper<>();
        wrapper.ge(StockFinancialIndicator::getEndDate, startDate)
                .le(StockFinancialIndicator::getEndDate, endDate)
                .select(StockFinancialIndicator::getEndDate)
                .groupBy(StockFinancialIndicator::getEndDate)
                .orderByAsc(StockFinancialIndicator::getEndDate);
        List<LocalDate> reportDates = financialIndicatorMapper.selectList(wrapper)
                .stream()
                .map(StockFinancialIndicator::getEndDate)
                .distinct()
                .sorted()
                .toList();

        if (reportDates.isEmpty()) {
            sendProgress(factorCode, "DONE", 100, "财务因子计算：无财报数据（" + startDate + " ~ " + endDate + "）");
            return;
        }

        // 获取已有数据的日期（用于跳过）
        Set<LocalDate> existingDates;
        try {
            List<FactorValue> existingValues = clickHouseFactorValueService.findByFactorCodeAndDateRange(
                    factorCode, reportDates.getFirst(), reportDates.getLast());
            existingDates = existingValues.stream()
                    .map(FactorValue::getCalcDate)
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            log.warn("[{}] 查询已有数据失败，使用空集合: {}", factorCode, e.getMessage());
            existingDates = Collections.emptySet();
        }

        final Set<LocalDate> existingDatesFinal = existingDates;
        List<LocalDate> newDates = reportDates.stream()
                .filter(d -> !existingDatesFinal.contains(d))
                .toList();

        if (newDates.isEmpty()) {
            sendProgress(factorCode, "DONE", 100, "财务因子：无新报告期需要计算（已有数据到 " + (existingDatesFinal.isEmpty() ? "无" : Collections.max(existingDatesFinal)) + "）");
            return;
        }

        log.info("[{}] financial incremental: total {} report dates, {} new (skipping {} existing)",
                factorCode, reportDates.size(), newDates.size(), existingDates.size());

        int totalDates = newDates.size();
        int totalStocks = symbols.size();
        long totalTasks = (long) totalDates * totalStocks;

        sendProgress(factorCode, "COMPUTING", 0, String.format("[财务] 开始计算 [%s]，新增 %d 个报告期 × %d 只股票 = %,d 条", factorCode, totalDates, totalStocks, totalTasks));

        AtomicLong rowsInserted = new AtomicLong(0);
        long startTimeMs = System.currentTimeMillis();

        List<FactorValue> writeBuffer = new ArrayList<>(2000);
        final int BATCH_SIZE = 500;

        for (int i = 0; i < newDates.size(); i++) {
            LocalDate reportDate = newDates.get(i);
            List<FactorValue> dayValues = computeOneDateFinancialForReportDate(factorCode, reportDate, symbols, calculator);
            if (!dayValues.isEmpty()) {
                writeBuffer.addAll(dayValues);
                rowsInserted.addAndGet(dayValues.size());
            }

            if (writeBuffer.size() >= BATCH_SIZE || i == newDates.size() - 1) {
                if (!writeBuffer.isEmpty()) {
                    batchSaveWithRetry(writeBuffer, factorCode);
                    writeBuffer.clear();
                }
            }

            // 发送进度
            int pct = (int) ((double) (i + 1) / totalDates * 90);
            long elapsed = System.currentTimeMillis() - startTimeMs;
            double speed = elapsed > 0 ? (double) (i + 1) / elapsed * 1000 : 0;
            int remaining = totalDates - i - 1;
            long etaSec = speed > 0 ? (long) (remaining / speed) : 0;
            sendProgress(factorCode, "COMPUTING", pct, String.format("[财务] %d/%d 报告期 (%d%%) | 已写 %,d 行 | 剩余约 %s", i + 1, totalDates, pct, rowsInserted.get(), formatEta(etaSec)), etaSec);
        }

        // 归一化
        sendProgress(factorCode, "COMPUTING", 91, String.format("财务因子写入完成，%,d 条。开始归一化...", rowsInserted.get()));
        normalizeFactorValues(factorCode, newDates);
        sendProgress(factorCode, "DONE", 100, String.format("[财务] 全部完成，新增 %,d 条", rowsInserted.get()));
        log.info("[{}] financial incremental done: {} new dates, {} rows", factorCode, totalDates, rowsInserted.get());
    }

    /**
     * 为单个财报报告期计算财务因子（对应一个日期，多只股票）
     * 优化：一次批量查询所有股票在该日期的最新财报，替代逐只N+1查询
     */
    private List<FactorValue> computeOneDateFinancialForReportDate(String factorCode, LocalDate reportDate, List<String> symbols, FinancialFactorCalculator calculator) {
        List<FactorValue> results = new ArrayList<>(symbols.size());
        LocalDateTime now = LocalDateTime.now();
        java.util.concurrent.atomic.AtomicInteger failCount = new java.util.concurrent.atomic.AtomicInteger(0);
        final boolean[] logged = {false};

        // 批量预加载：一次查询所有股票 end_date <= reportDate 的最新一期财报
        Map<String, StockFinancialIndicator> indicatorMap = batchLoadLatestFinancials(symbols, reportDate);

        for (String symbol : symbols) {
            String code = symbol.contains(".") ? symbol.substring(0, symbol.indexOf('.')) : symbol;
            try {
                StockFinancialIndicator indicator = indicatorMap.get(code);
                if (indicator == null) continue;

                BigDecimal value = calculator.calculate(code, indicator);
                if (value != null) {
                    FactorValue fv = FactorValue.builder()
                            .factorCode(factorCode)
                            .symbol(code)
                            .calcDate(reportDate)  // 使用财报报告期作为 calcDate
                            .factorVal(value)
                            .createdAt(now)
                            .build();
                    results.add(fv);
                }
            } catch (Exception e) {
                failCount.incrementAndGet();
                if (!logged[0]) {
                    logged[0] = true;
                    log.warn("[{}] computeOneDateFinancial {} {}: 首个异常 code={}, ex={}, msg={}", factorCode, reportDate, e.getClass().getSimpleName(), code, e.getMessage());
                }
            }
        }
        if (failCount.get() > 0) {
            log.info("[{}] computeOneDateFinancial {} 完成: 成功={}, 失败={}", factorCode, reportDate, results.size(), failCount.get());
        }
        return results;
    }

    /**
     * 批量加载所有股票在指定日期或之前的最新一期财报指标
     * 使用子查询 GROUP BY code 获取最新 end_date，再联查完整记录
     * 替代原来逐只股票 N 次 SELECT ... LIMIT 1 的 N+1 问题
     */
    private Map<String, StockFinancialIndicator> batchLoadLatestFinancials(List<String> symbols, LocalDate beforeDate) {
        List<String> codes = symbols.stream()
                .map(s -> s.contains(".") ? s.substring(0, s.indexOf('.')) : s)
                .distinct()
                .toList();

        if (codes.isEmpty()) return Map.of();

        // 分批查询（MySQL IN 子句不宜过长，每批 500）
        Map<String, StockFinancialIndicator> result = new java.util.HashMap<>();
        final int BATCH = 500;
        for (int i = 0; i < codes.size(); i += BATCH) {
            List<String> batch = codes.subList(i, Math.min(i + BATCH, codes.size()));
            String inClause = batch.stream().map(c -> "'" + c + "'").collect(Collectors.joining(","));

            // 批量查出所有符合条件的数据，Java 端 groupBy 取最新一条
            // （避免 GROUP BY + SELECT 非聚合字段触发 only_full_group_by 报错）
            List<StockFinancialIndicator> allIndicators = financialIndicatorMapper.selectList(
                    new LambdaQueryWrapper<StockFinancialIndicator>()
                            .in(StockFinancialIndicator::getCode, batch)
                            .le(StockFinancialIndicator::getEndDate, beforeDate)
                            .orderByDesc(StockFinancialIndicator::getEndDate));

            // 按 code 分组取最新一条
            allIndicators.stream()
                    .collect(Collectors.groupingBy(
                            StockFinancialIndicator::getCode,
                            Collectors.collectingAndThen(
                                    Collectors.maxBy(Comparator.comparing(StockFinancialIndicator::getEndDate)),
                                    opt -> opt.orElse(null))))
                    .forEach((code, ind) -> {
                        if (ind != null) result.put(code, ind);
                    });
        }
        return result;
    }

    /**
     * （基于财报报告期，而非交易日）
     */
    private void computeFinancialFactorSync(String factorCode, LocalDate startDate, LocalDate endDate, List<String> symbols) {
        FinancialFactorCalculator calculator = financialCalculators.get(factorCode);
        if (calculator == null) {
            log.error("[{}] 财务因子计算器未找到", factorCode);
            return;
        }

        // 获取所有财报报告期（end_date），按日期范围过滤
        LambdaQueryWrapper<StockFinancialIndicator> wrapper = new LambdaQueryWrapper<>();
        wrapper.ge(StockFinancialIndicator::getEndDate, startDate)
                .le(StockFinancialIndicator::getEndDate, endDate)
                .select(StockFinancialIndicator::getEndDate)
                .groupBy(StockFinancialIndicator::getEndDate)
                .orderByAsc(StockFinancialIndicator::getEndDate);
        List<LocalDate> reportDates = financialIndicatorMapper.selectList(wrapper)
                .stream()
                .map(StockFinancialIndicator::getEndDate)
                .distinct()
                .sorted()
                .toList();

        if (reportDates.isEmpty()) {
            sendProgress(factorCode, "DONE", 100, "财务因子：无财报数据（" + startDate + " ~ " + endDate + "）");
            return;
        }

        int totalDates = reportDates.size();
        int totalStocks = symbols.size();
        long totalTasks = (long) totalDates * totalStocks;

        // 不再 ALTER TABLE DELETE，直接 INSERT 覆盖（ReplacingMergeTree 按 update_time 去重）。
        log.info("[财务全量] 跳过删除，直接覆盖写入: factor={}, {}~{}", factorCode, startDate, endDate);

        sendProgress(factorCode, "COMPUTING", 0, String.format("[财务全量] 开始计算 [%s]，共 %d 个报告期 × %d 只股票 = %,d 条", factorCode, totalDates, totalStocks, totalTasks));

        AtomicLong rowsInserted = new AtomicLong(0);
        long startTimeMs = System.currentTimeMillis();

        List<FactorValue> writeBuffer = new ArrayList<>(2000);
        final int BATCH_SIZE = 500;

        for (int i = 0; i < reportDates.size(); i++) {
            LocalDate reportDate = reportDates.get(i);
            List<FactorValue> dayValues = computeOneDateFinancialForReportDate(factorCode, reportDate, symbols, calculator);
            if (!dayValues.isEmpty()) {
                writeBuffer.addAll(dayValues);
                rowsInserted.addAndGet(dayValues.size());
            }

            if (writeBuffer.size() >= BATCH_SIZE || i == reportDates.size() - 1) {
                if (!writeBuffer.isEmpty()) {
                    batchSaveWithRetry(writeBuffer, factorCode);
                    writeBuffer.clear();
                }
            }

            // 发送进度
            int pct = (int) ((double) (i + 1) / totalDates * 90);
            long elapsed = System.currentTimeMillis() - startTimeMs;
            double speed = elapsed > 0 ? (double) (i + 1) / elapsed * 1000 : 0;
            int remaining = totalDates - i - 1;
            long etaSec = speed > 0 ? (long) (remaining / speed) : 0;
            sendProgress(factorCode, "COMPUTING", pct, String.format("[财务全量] %d/%d 报告期 (%d%%) | 已写 %,d 行 | 剩余约 %s", i + 1, totalDates, pct, rowsInserted.get(), formatEta(etaSec)), etaSec);
        }

        // 归一化
        sendProgress(factorCode, "COMPUTING", 91, String.format("财务因子写入完成，%,d 条。开始归一化...", rowsInserted.get()));
        normalizeFactorValues(factorCode, reportDates);
        sendProgress(factorCode, "DONE", 100, String.format("[财务全量] 全部完成，共 %,d 条", rowsInserted.get()));
        log.info("[{}] financial sync done: {} dates, {} rows", factorCode, totalDates, rowsInserted.get());
    }

    /**
     * 计算单个交易日所有股票的因子值（在线程池中执行）
     */
    private List<FactorValue> computeOneDate(FactorDefinition factor, LocalDate date, List<String> symbols) {
        String factorCode = factor.getFactorCode();

        // 财务因子走单独的计算路径（基于财务报表数据，非行情K线）
        if (isFinancialFactor(factorCode)) {
            return computeOneDateFinancial(factorCode, date, symbols);
        }

        LocalDate histStart = date.minusDays(400); // 预留足够历史窗口
        LocalDateTime now = LocalDateTime.now();

        // 批量查询：一次 DB 调用替代 N 次单只查询（修复 5490 只股票串行查询卡死问题）
        Map<String, List<MarketDailyBar>> batchData = marketDataService.getBarsBatch(symbols, histStart, date);

        List<FactorValue> results = new ArrayList<>(symbols.size());
        for (String symbol : symbols) {
            try {
                List<MarketDailyBar> history = batchData.getOrDefault(symbol, List.of());
                BigDecimal value = computeSingleValue(factor, symbol, date, history);
                if (value != null) {
                    String code = parseCode(symbol);
                    FactorValue fv = FactorValue.builder().factorCode(factor.getFactorCode()).symbol(code).calcDate(date).factorVal(value).createdAt(now).build();
                    results.add(fv);
                }
            } catch (Exception e) {
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

        if (isFinancialFactor(factorCode)) {
            // 财务因子仍走DB查询（每个日期取最新财报）
            return computeOneDateFinancial(factorCode, date, symbols);
        }

        LocalDateTime now = LocalDateTime.now();
        List<FactorValue> results = new ArrayList<>(symbols.size());

        // 诊断：记录有多少 symbol 的 bars 非空
        int emptyCount = 0;
        for (String symbol : symbols) {
            try {
                List<MarketDailyBar> allBars = allBarsData.getOrDefault(symbol, List.of());
                if (allBars.isEmpty()) {
                    emptyCount++;
                    continue;
                }

                // 二分查找：找到 tradeDate > date 的第一个位置（不创建新列表）
                int lo = 0, hi = allBars.size();
                while (lo < hi) {
                    int mid = (lo + hi) >>> 1;
                    if (allBars.get(mid).getTradeDate().compareTo(date) > 0) {
                        hi = mid;
                    } else {
                        lo = mid + 1;
                    }
                }
                // lo = 第一个 tradeDate > date 的位置，即 endIdx
                if (lo == 0) continue; // date 之前没有任何数据

                List<MarketDailyBar> history = allBars.subList(0, lo);
                BigDecimal value = computeSingleValue(factor, symbol, date, history);
                if (value != null) {
                    String code = parseCode(symbol);
                    FactorValue fv = FactorValue.builder()
                            .factorCode(factor.getFactorCode())
                            .symbol(code)
                            .calcDate(date)
                            .factorVal(value)
                            .createdAt(now)
                            .build();
                    results.add(fv);
                }
            } catch (Exception e) {
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
     * 从 symbol（如 600619.SH）中提取纯代码（如 600619）
     */
    private String parseCode(String symbol) {
        int dot = symbol.lastIndexOf('.');
        return dot > 0 ? symbol.substring(0, dot) : symbol;
    }

    /**
     * 财务因子计算：每个交易日使用该股票最新的年报财务指标
     * 优化：批量预加载替代逐只N+1查询
     */
    private List<FactorValue> computeOneDateFinancial(String factorCode, LocalDate date, List<String> symbols) {
        FinancialFactorCalculator calculator = financialCalculators.get(factorCode);
        List<FactorValue> results = new ArrayList<>(symbols.size());
        LocalDateTime now = LocalDateTime.now();

        // 批量预加载：一次查询所有股票的最新财报
        Map<String, StockFinancialIndicator> indicatorMap = batchLoadLatestFinancials(symbols, date);

        for (String symbol : symbols) {
            try {
                String code = symbol.contains(".") ? symbol.substring(0, symbol.indexOf('.')) : symbol;
                StockFinancialIndicator indicator = indicatorMap.get(code);
                if (indicator == null) continue;

                BigDecimal value = calculator.calculate(symbol, indicator);
                if (value != null) {
                    FactorValue fv = FactorValue.builder().factorCode(factorCode).symbol(code).calcDate(date).factorVal(value).createdAt(now).build();
                    results.add(fv);
                }
            } catch (Exception e) {
            }
        }
        return results;
    }

    /**
     * 格式化剩余时间：秒->分:秒 或 时:分
     */
    private String formatEta(long seconds) {
        if (seconds <= 0) return "计算中";
        if (seconds < 60) return seconds + "秒";
        if (seconds < 3600) return (seconds / 60) + "分" + (seconds % 60) + "秒";
        return (seconds / 3600) + "时" + (seconds % 3600 / 60) + "分";
    }

    /**
     * 在独立事务中删除旧数据，避免长事务
     */
    public void deleteExistingValues(String factorCode, LocalDate startDate, LocalDate endDate) {
        // 删 CH 旧数据（MySQL factor_value 已是空表，写入全走 CH）
        clickHouseFactorValueService.deleteByFactorCodeAndDateRange(
                factorCode, startDate.toString(), endDate.toString());
    }

    /**
     * 批量保存，带死锁重试机制
     */
    private void batchSaveWithRetry(List<FactorValue> values, String factorCode) {
        int maxRetries = 3;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                batchSave(values);
                return;
            } catch (org.springframework.dao.PessimisticLockingFailureException e) {
                log.warn("Deadlock on batch insert for [{}], attempt {}/{}", factorCode, attempt, maxRetries);
                if (attempt == maxRetries) {
                    throw e;
                }
                try {
                    Thread.sleep(500L * attempt);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    private void batchSave(List<FactorValue> values) {
        if (values == null || values.isEmpty()) return;
        LocalDateTime now = LocalDateTime.now();
        for (FactorValue value : values) {
            if (value.getCreatedAt() == null) {
                value.setCreatedAt(now);
            }
        }
        // 统一走 HTTP 快速路径
        clickHouseFactorValueService.httpBatchInsert(values);
    }

    /**
     * 批量写入因子值（HTTP POST JSONEachRow，绕过 JDBC，速度更快）
     */
    private void batchSaveWithProgress(List<FactorValue> values, String factorCode) {
        if (values == null || values.isEmpty()) return;
        LocalDateTime now = LocalDateTime.now();
        for (FactorValue value : values) {
            if (value.getCreatedAt() == null) {
                value.setCreatedAt(now);
            }
        }
        sendProgress(factorCode, "COMPUTING", 65,
                String.format("开始写入 ClickHouse（HTTP）%,d 行...", values.size()), null);
        long start = System.currentTimeMillis();
        try {
            clickHouseFactorValueService.httpBatchInsert(values);
        } catch (Exception e) {
            log.error("[{}] HTTP写入失败，回退JDBC: {}", factorCode, e.getMessage());
            // 回退到 JDBC 方式
            batchSave(values);
        }
        long ms = System.currentTimeMillis() - start;
        double speed = ms > 0 ? (double) values.size() / ms * 1000 : 0;
        sendProgress(factorCode, "COMPUTING", 90,
                String.format("写入完成，%,d 行，耗时 %.1f 秒，速度 %.0f 行/s", values.size(), ms / 1000.0, speed), null);
    }

    /**
     * 计算单个因子值
     */
    private BigDecimal computeSingleValue(FactorDefinition factor, String symbol, LocalDate calcDate, List<MarketDailyBar> history) {
        if (factor.getFactorType() == FactorDefinition.FactorType.BUILTIN) {
            FactorCalculator calc = builtinCalculators.get(factor.getFactorCode());
            if (calc != null) {
                return calc.calculate(symbol, calcDate, history, Map.of());
            }
        } else if (factor.getFactorType() == FactorDefinition.FactorType.SCRIPTED && factor.getScriptCode() != null) {
            return scriptedEngine.calculate(factor.getScriptCode(), factor.getFactorCode(), symbol, calcDate, history, Map.of());
        }
        return null;
    }

    /**
     * 对因子值做横截面归一化（Z-Score + 百分位排名）
     * 优化：ClickHouse 窗口函数一次性算完所有日期，INSERT 覆盖（ReplacingMergeTree 去重）
     * 性能：从 ~178万次 UPDATE 优化为 2次SQL，提速 10x+
     */
    private void normalizeFactorValues(String factorCode, List<LocalDate> dates) {
        if (clickHouseConfig.isEnabled()) {
            try {
                long normStart = System.currentTimeMillis();
                long rowCount = clickHouseFactorValueService.batchNormalize(factorCode, dates);
                long elapsed = System.currentTimeMillis() - normStart;
                double speed = elapsed > 0 ? (double) dates.size() / elapsed * 1000 : 0;
                log.info("[{}] 归一化完成(CH): {} 日期, {} 行, 耗时 {}ms, 速度 {} 日/s",
                        factorCode, dates.size(), rowCount, elapsed, speed);

                // 归一化写入后触发 OPTIMIZE，合并旧行（z_score/rank_value=NULL），避免查询读到脏数据
                if (rowCount > 0) {
                    try {
                        long optStart = System.currentTimeMillis();
                        clickHouseFactorValueService.optimizeFactorValue();
                        long optMs = System.currentTimeMillis() - optStart;
                        log.info("[{}] OPTIMIZE factor_value 完成, 耗时 {}ms", factorCode, optMs);
                    } catch (Exception optEx) {
                        log.warn("[{}] OPTIMIZE 失败（不影响结果，下次查询带FINAL仍正确）: {}", factorCode, optEx.getMessage());
                    }
                }

                sendProgress(factorCode, "COMPUTING", 99, String.format(
                        "归一化完成 | %d 日期 × 均值 %d 只/日 ≈ %,d 行 | 耗时 %.1f 秒",
                        dates.size(), rowCount > 0 && dates.size() > 0 ? rowCount / dates.size() : 0,
                        rowCount, elapsed / 1000.0));
                return;
            } catch (Exception e) {
                log.warn("[{}] CH归一化失败，回退Java内存计算: {}", factorCode, e.getMessage());
            }
        }

        // 回退：Java 内存归一化（CH 不可用时）
        normalizeFactorValuesFallback(factorCode, dates);
    }

    /**
     * Java 内存归一化（回退方案）
     */
    private void normalizeFactorValuesFallback(String factorCode, List<LocalDate> dates) {
        int totalDates = dates.size();
        long normStart = System.currentTimeMillis();

        for (int di = 0; di < totalDates; di++) {
            LocalDate date = dates.get(di);
            List<FactorValue> values;
            if (clickHouseConfig.isEnabled()) {
                try {
                    values = clickHouseFactorValueService.findByFactorCodeAndDate(factorCode, date);
                    if (values.isEmpty()) {
                        LambdaQueryWrapper<FactorValue> wrapper = new LambdaQueryWrapper<>();
                        wrapper.eq(FactorValue::getFactorCode, factorCode).eq(FactorValue::getCalcDate, date).orderByAsc(FactorValue::getSymbol);
                        values = factorValueMapper.selectList(wrapper);
                    }
                } catch (Exception e) {
                    LambdaQueryWrapper<FactorValue> wrapper = new LambdaQueryWrapper<>();
                    wrapper.eq(FactorValue::getFactorCode, factorCode).eq(FactorValue::getCalcDate, date).orderByAsc(FactorValue::getSymbol);
                    values = factorValueMapper.selectList(wrapper);
                }
            } else {
                LambdaQueryWrapper<FactorValue> wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(FactorValue::getFactorCode, factorCode).eq(FactorValue::getCalcDate, date).orderByAsc(FactorValue::getSymbol);
                values = factorValueMapper.selectList(wrapper);
            }
            if (values.isEmpty()) continue;

            double[] raw = values.stream().mapToDouble(v -> v.getFactorVal().doubleValue()).toArray();

            int n = raw.length;
            double mean = Arrays.stream(raw).average().orElse(0);
            double std = Math.sqrt(Arrays.stream(raw).map(v -> (v - mean) * (v - mean)).average().orElse(1));

            double[] sorted = raw.clone();
            Arrays.sort(sorted);
            double[] pctRanks = new double[n];
            for (int i = 0; i < n; i++) {
                int lo = lowerBound(sorted, raw[i]);
                int hi = upperBound(sorted, raw[i]);
                double avgRank = lo + (hi - lo) / 2.0;
                pctRanks[i] = n <= 1 ? 0.5 : avgRank / (n - 1);
            }

            for (int i = 0; i < values.size(); i++) {
                FactorValue fv = values.get(i);
                double zScore = std == 0 ? 0 : (raw[i] - mean) / std;
                fv.setZScore(BigDecimal.valueOf(zScore).setScale(6, RoundingMode.HALF_UP));
                fv.setRankValue(BigDecimal.valueOf(pctRanks[i]).setScale(6, RoundingMode.HALF_UP));
            }
            int batchSize = 500;
            for (int i = 0; i < values.size(); i += batchSize) {
                List<FactorValue> sub = values.subList(i, Math.min(i + batchSize, values.size()));
                for (FactorValue fv : sub) factorValueMapper.updateById(fv);
            }

            if ((di + 1) % Math.max(1, totalDates / 20) == 0 || di == totalDates - 1) {
                long elapsed = System.currentTimeMillis() - normStart;
                double speed = elapsed > 0 ? (di + 1.0) / elapsed * 1000 : 0;
                int remaining = totalDates - di - 1;
                long etaSec = speed > 0 ? (long) (remaining / speed) : 0;
                int pct = 91 + (int) ((double) (di + 1) / totalDates * 9);
                sendProgress(factorCode, "COMPUTING", Math.min(pct, 99), String.format("归一化 %d/%d (%s) | 本日 %d 只 | 速度 %.1f 日/s | 剩余约 %s", di + 1, totalDates, date, n, speed, formatEta(etaSec)), etaSec);
            }
        }
    }

    /**
     * 二分查找第一个 >= target 的下标（0-based）
     */
    private int lowerBound(double[] sorted, double target) {
        int lo = 0, hi = sorted.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted[mid] < target) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    /**
     * 二分查找第一个 > target 的下标（0-based）
     */
    private int upperBound(double[] sorted, double target) {
        int lo = 0, hi = sorted.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted[mid] <= target) lo = mid + 1;
            else hi = mid;
        }
        return lo;
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
                sendProgress(factor.getFactorCode(), "TEST_START", 1, "因子值不存在，正在自动计算...");
                try {
                    List<String> symbols = marketDataService.getAllSymbols();
                    self.computeFactorSync(factor, report.getStartDate(), report.getEndDate(), symbols);
                    sendProgress(factor.getFactorCode(), "TEST_START", 5, "因子值计算完成，开始检测");
                } catch (Exception e) {
                    log.error("Auto compute failed for [{}]", factor.getFactorCode(), e);
                    report.setStatus(FactorTestReport.TestStatus.FAILED);
                    report.setErrorMessage("因子值自动计算失败: " + e.getMessage());
                    testReportMapper.updateById(report);
                    sendProgress(factor.getFactorCode(), "FAILED", 0, "因子值计算失败: " + e.getMessage());
                    return;
                }
            }

            sendProgress(factor.getFactorCode(), "TEST_START", 6, "开始因子检测");

            List<LocalDate> allDates = marketDataService.getTradingDates(report.getStartDate(), report.getEndDate());
            if (allDates.size() < 2) throw new RuntimeException("测试日期不足");
            sendProgress(factor.getFactorCode(), "TEST_START", 3, "获取交易日期完成，共" + allDates.size() + "个交易日");

            // ── 调仓频率过滤：仅保留符合调仓周期的日期 ──────────────
            List<LocalDate> dates = filterByRebalanceFreq(allDates, report.getRebalanceFreq());
            if (dates.size() < 2) throw new RuntimeException("调仓周期过滤后日期不足");
            sendProgress(factor.getFactorCode(), "TEST_START", 5, "调仓频率过滤完成，有效交易日" + dates.size() + "个");

            // ── 股票池白名单 ────────────────────────────────────────
            Set<String> poolSymbols = getStockPoolSymbols(report.getStockPool());
            String poolDesc = poolSymbols.isEmpty() ? "全A（不限制）" : poolSymbols.size() + "只股票";
            sendProgress(factor.getFactorCode(), "TEST_START", 6, "股票池加载完成，" + poolDesc);

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
            sendProgress(factor.getFactorCode(), "TEST_START", 7, String.format(
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
                sendProgress(factor.getFactorCode(), "COMPLETED", 100,
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

            for (int di = 0; di < dates.size() - 1; di++) {
                LocalDate calcDate = dates.get(di);
                LocalDate nextDate = dates.get(di + 1);

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

                // 分批获取当期+下期行情（避免 CH 内存爆掉）
                List<String> symbols = factorValues.stream().map(FactorValue::getSymbol).toList();
                final int BATCH = 1000;
                Map<String, List<MarketDailyBar>> currBars = new HashMap<>();
                Map<String, List<MarketDailyBar>> nextBars = new HashMap<>();
                for (int b = 0; b < symbols.size(); b += BATCH) {
                    List<String> batch = symbols.subList(b, Math.min(b + BATCH, symbols.size()));
                    currBars.putAll(marketDataService.getBarsBatch(batch, calcDate, calcDate));
                    nextBars.putAll(marketDataService.getBarsBatch(batch, nextDate, nextDate));
                }
                // 获取下期收益
                Map<String, Double> nextReturns = new HashMap<>();
                for (FactorValue fv : factorValues) {
                    String sym = fv.getSymbol();
                    List<MarketDailyBar> curr = currBars.get(sym);
                    List<MarketDailyBar> next = nextBars.get(sym);
                    if (curr != null && !curr.isEmpty() && next != null && !next.isEmpty()) {
                        double r = next.getFirst().getClose().doubleValue() / curr.getFirst().getClose().doubleValue() - 1;
                        nextReturns.put(sym, r);
                    }
                }

                List<FactorValue> valid = factorValues.stream().filter(fv -> nextReturns.containsKey(fv.getSymbol())).toList();

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

                // ── IC 计算 ──────────────────────────────────────
                double[] fValues = valid.stream().mapToDouble(fv -> fv.getFactorVal().doubleValue()).toArray();
                double[] returns = valid.stream().mapToDouble(fv -> nextReturns.get(fv.getSymbol())).toArray();
                double[] rankVals = valid.stream().mapToDouble(fv -> fv.getRankValue() == null ? 0 : fv.getRankValue().doubleValue()).toArray();

                double ic = pearsonCorr(fValues, returns);
                double rankIc = pearsonCorr(rankVals, returns);

                if (!Double.isNaN(ic)) icList.add(ic);
                if (!Double.isNaN(rankIc)) rankIcList.add(rankIc);

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
                sendProgress(factor.getFactorCode(), "TESTING", pct, detail.toString());
            }

            // ── 跳过日期汇总日志 ──
            if (skippedNoData > 0 || skippedNoReturn > 0) {
                sendProgress(factor.getFactorCode(), "TESTING", 93, String.format(
                        "跳过汇总：无因子值 %d 天 | 无下期行情 %d 天 | 有效计算 %d 天",
                        skippedNoData, skippedNoReturn, icList.size()));
            }

            sendProgress(factor.getFactorCode(), "TESTING", 95, "回测计算完成，开始计算统计指标");

            // ── 汇总：IC 统计 ────────────────────────────────────
            sendProgress(factor.getFactorCode(), "TESTING", 96, "计算IC统计指标");
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
                sendProgress(factor.getFactorCode(), "TESTING", 96, String.format("IC统计完成，样本数%d | IC均值:%.4f | ICIR:%.4f | 正IC率:%.1f%% | t统计:%.2f | p值:%.4f", icList.size(), icMean, icir, (double) posC / icList.size() * 100, tStat, pValue));
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
            // 年化因子（每年有多少个调仓期）
            double annualFactor = getAnnualFactor(report.getRebalanceFreq(), tradingDays);

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
                // 主动年化收益（多头组超额日均收益 × 每年调仓期数）
                double activeAnnual = avg(topActiveReturnList) * periodsPerYear;
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
                sendProgress(factor.getFactorCode(), "TESTING", 97, "主动指标计算完成");
            }

            // ── 汇总：最佳夏普 + 各组详细指标 ──────────────────────
            sendProgress(factor.getFactorCode(), "TESTING", 97, "计算分组收益和夏普比");
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
                    double benchmarkNavFinal = benchmarkNav;
                    double benchmarkAnnual = years <= 0 ? 0 : Math.pow(benchmarkNavFinal, 1.0 / years) - 1;
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
            sendProgress(factor.getFactorCode(), "TESTING", 97, groupSummary.toString());

            report.setIcSeriesJson(objectMapper.writeValueAsString(icSeriesData));
            report.setGroupReturnsJson(objectMapper.writeValueAsString(groupReturnData));
            report.setGroupNavJson(objectMapper.writeValueAsString(groupNavData));
            report.setLongShortNavJson(objectMapper.writeValueAsString(longShortNavData));

            // ── 因子衰减分析(因子有效期) ──────────────────────────────────────────
            sendProgress(factor.getFactorCode(), "TESTING", 98, "计算因子衰减分析");
            Map<String, Object> decayAnalysis = computeFactorDecayAnalysis(icSeriesData);
            report.setDecayPeriods((BigDecimal) decayAnalysis.get("decayPeriods"));
            report.setHalfLifePeriods((BigDecimal) decayAnalysis.get("halfLifePeriods"));
            report.setDecayCoefficient((BigDecimal) decayAnalysis.get("decayCoefficient"));
            report.setDecayRSquared((BigDecimal) decayAnalysis.get("decayRSquared"));
            report.setDecaySeriesJson(objectMapper.writeValueAsString(decayAnalysis.get("decaySeries")));

            report.setStatus(FactorTestReport.TestStatus.COMPLETED);
            report.setCompletedAt(java.time.LocalDateTime.now());
            testReportMapper.updateById(report);

            sendProgress(factor.getFactorCode(), "TEST_DONE", 100, "因子测试完成，reportId=" + report.getId());
            log.info("Factor test [{}] completed, IC={}, mono={}", factor.getFactorCode(), report.getIcMean(), report.getMonotonicity());

        } catch (Exception e) {
            log.error("Factor test failed for [{}]", factor.getFactorCode(), e);
            report.setStatus(FactorTestReport.TestStatus.FAILED);
            String errMsg = e.getMessage();
            if (errMsg != null && errMsg.length() > 200) errMsg = errMsg.substring(0, 200) + "...";
            report.setErrorMessage(errMsg);
            testReportMapper.updateById(report);
            sendProgress(factor.getFactorCode(), "TEST_FAILED", 0, "因子检测失败: " + errMsg);
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

    private void sendProgress(String factorCode, String stage, int pct, String message) {
        sendProgress(factorCode, stage, pct, message, null);
    }

    private void sendProgress(String factorCode, String stage, int pct, String message, Long etaSec) {
        // 维护 runningFactors 集合
        if ("COMPUTING".equals(stage)) {
            runningFactors.add(factorCode);
        } else if ("DONE".equals(stage) || "FAILED".equals(stage) || "TEST_DONE".equals(stage)) {
            runningFactors.remove(factorCode);
        }
        try {
            Map<String, Object> msg = new java.util.HashMap<>();
            msg.put("factorCode", factorCode);
            msg.put("stage", stage);
            msg.put("progress", pct);
            msg.put("message", message);
            if (etaSec != null) {
                msg.put("etaSec", etaSec);
                log.info("[sendProgress] pushing etaSec={} for {}/{}, msg={}", etaSec, factorCode, stage, msg);
            } else {
                log.info("[sendProgress] etaSec is NULL for {}/{} — NOT pushing etaSec", factorCode, stage);
            }
            messagingTemplate.convertAndSend("/topic/factor/" + factorCode, msg);
            // 同时广播到批量日志通道，供监控页面聚合展示
            Map<String, Object> batchMsg = new java.util.HashMap<>();
            batchMsg.put("type", "FACTOR_PROGRESS");
            batchMsg.put("factorCode", factorCode);
            batchMsg.put("stage", stage);
            batchMsg.put("progress", pct);
            batchMsg.put("message", message);
            batchMsg.put("timestamp", LocalDateTime.now().toString());
            if (etaSec != null) batchMsg.put("etaSec", etaSec);
            messagingTemplate.convertAndSend("/topic/factor/batch-log", batchMsg);
        } catch (Exception e) {
            log.warn("[sendProgress] WebSocket推送失败: {}/{} — {}", factorCode, stage, e.getMessage());
        }
    }

    /**
     * 返回当前正在计算的因子代码集合（供 Controller 暴露给前端）
     */
    public Set<String> getRunningFactorCodes() {
        return new HashSet<>(runningFactors);
    }

    /**
     * 计算因子衰减分析(因子有效期)
     * 分析因子值与未来不同期数收益的相关性衰减规律
     */
    private Map<String, Object> computeFactorDecayAnalysis(List<Map<String, Object>> icSeriesData) {
        List<Map<String, Object>> decaySeries = new ArrayList<>();
        double[] initialICs = icSeriesData.stream().mapToDouble(d -> ((Number) d.get("ic")).doubleValue()).toArray();

        double initialICMean = avg(Arrays.stream(initialICs).boxed().collect(Collectors.toList()));
        double initialICAbs = Math.abs(initialICMean);

        // 计算滞后1-10期的IC
        int maxLag = Math.min(10, initialICs.length - 1);
        for (int lag = 0; lag <= maxLag; lag++) {
            List<Double> laggedICs = new ArrayList<>();
            for (int i = 0; i < initialICs.length - lag; i++) {
                laggedICs.add(initialICs[i + lag]);
            }
            double laggedICMean = laggedICs.isEmpty() ? 0 : avg(laggedICs);
            double laggedICAbs = Math.abs(laggedICMean);

            Map<String, Object> decayPoint = new HashMap<>();
            decayPoint.put("period", lag);
            decayPoint.put("laggedIc", round4(laggedICMean));
            decayPoint.put("absoluteIc", round4(laggedICAbs));
            decaySeries.add(decayPoint);
        }

        // 计算因子有效期: IC绝对值首次低于阈值0.02的期数
        double decayPeriods = 0;
        final double IC_THRESHOLD = 0.02;
        for (Map<String, Object> decayPoint : decaySeries) {
            double absIC = ((Number) decayPoint.get("absoluteIc")).doubleValue();
            if (absIC < IC_THRESHOLD) {
                decayPeriods = ((Number) decayPoint.get("period")).doubleValue();
                break;
            }
        }
        if (decayPeriods == 0 && !decaySeries.isEmpty()) {
            decayPeriods = maxLag; // 如果未降至阈值,返回最大期数
        }

        // 计算因子半衰期: IC降至初始值的50%所需的期数
        double halfLifePeriods = 0;
        double halfICThreshold = initialICAbs * 0.5;
        for (Map<String, Object> decayPoint : decaySeries) {
            double absIC = ((Number) decayPoint.get("absoluteIc")).doubleValue();
            if (absIC < halfICThreshold) {
                halfLifePeriods = ((Number) decayPoint.get("period")).doubleValue();
                break;
            }
        }

        // 拟合指数衰减模型: IC(t) = IC(0) * exp(-λ * t)
        double decayCoefficient = 0;
        double decayRSquared = 0;
        try {
            // 使用前maxLag期数据进行拟合
            List<Double> periods = new ArrayList<>();
            List<Double> absICs = new ArrayList<>();
            for (Map<String, Object> decayPoint : decaySeries) {
                int period = ((Number) decayPoint.get("period")).intValue();
                double absIC = ((Number) decayPoint.get("absoluteIc")).doubleValue();
                if (period > 0 && absIC > 0) {
                    periods.add((double) period);
                    absICs.add(absIC);
                }
            }

            if (periods.size() >= 3) {
                // 对数变换: ln(IC(t)) = ln(IC(0)) - λ * t
                List<Double> logICs = absICs.stream().map(Math::log).toList();

                // 线性回归拟合
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

                decayCoefficient = -slope; // λ = -斜率

                // 计算R²
                double meanLogIC = sumLogIC / n;
                double ssRes = 0, ssTot = 0;
                for (int i = 0; i < n; i++) {
                    double predicted = intercept + slope * periods.get(i);
                    double actual = logICs.get(i);
                    ssRes += Math.pow(actual - predicted, 2);
                    ssTot += Math.pow(actual - meanLogIC, 2);
                }
                decayRSquared = ssTot == 0 ? 0 : 1 - (ssRes / ssTot);
            }
        } catch (Exception e) {
            log.warn("Failed to fit decay model", e);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("decayPeriods", bd(decayPeriods));
        result.put("halfLifePeriods", bd(halfLifePeriods));
        result.put("decayCoefficient", bd(decayCoefficient));
        result.put("decayRSquared", bd(decayRSquared));
        result.put("decaySeries", decaySeries);

        return result;
    }
}
