package com.quant.platform.factor.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.factor.domain.FactorDefinition;
import com.quant.platform.factor.domain.FactorTestReport;
import com.quant.platform.factor.domain.FactorValue;
import com.quant.platform.factor.mapper.FactorTestReportMapper;
import com.quant.platform.factor.mapper.FactorValueMapper;
import com.quant.platform.financial.entity.StockFinancialIndicator;
import com.quant.platform.financial.mapper.StockFinancialIndicatorMapper;
import com.quant.platform.market.domain.MarketDailyBar;
import com.quant.platform.market.service.MarketDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
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
    private final FactorTestReportMapper testReportMapper;
    private final ScriptedFactorEngine scriptedEngine;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;
    private final StockFinancialIndicatorMapper financialIndicatorMapper;

    private final Map<String, FactorCalculator> builtinCalculators = new HashMap<>();
    private final Map<String, FinancialFactorCalculator> financialCalculators = new HashMap<>();

    // 自注入，用于内部调用时走代理（解决 @Transactional 自调用失效）
    @Lazy
    @Autowired
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
        registerFinancial(new FinancialFactors.EarningsQualityCalc());
        registerFinancial(new FinancialFactors.GrossMarginQualityCalc());
        registerFinancial(new FinancialFactors.OperatingLeverageCalc());
        registerFinancial(new FinancialFactors.CashFlowQualityCalc());
        registerFinancial(new FinancialFactors.EarningsYieldCalc());
        registerFinancial(new FinancialFactors.BookValueCalc());
        registerFinancial(new FinancialFactors.RevenueQualityCalc());
        log.info("Registered {} financial factor calculators", financialCalculators.size());
    }

    private void registerBuiltin(FactorCalculator calc) {
        builtinCalculators.put(calc.getFactorCode(), calc);
    }

    private void registerFinancial(FinancialFactorCalculator calc) {
        financialCalculators.put(calc.getFactorCode(), calc);
    }

    /**
     * 计算因子值（时间区间 × 股票池）
     */
    @Async("backtestTaskExecutor")
    public void computeFactor(FactorDefinition factor, LocalDate startDate, LocalDate endDate,
                              List<String> symbols, Long reportId) {
        self.computeFactorSync(factor, startDate, endDate, symbols);
        sendProgress(factor.getFactorCode(), "DONE", 100, "因子计算完成");
    }

    /**
     * 查询指定因子已有数据的最新日期（用于增量续算）
     * @return 最新日期，无数据时返回 null
     */
    public LocalDate findLatestDate(String factorCode) {
        LambdaQueryWrapper<FactorValue> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FactorValue::getFactorCode, factorCode)
                .orderByDesc(FactorValue::getCalcDate)
                .last("LIMIT 1");
        FactorValue latest = factorValueMapper.selectOne(wrapper);
        return latest != null ? latest.getCalcDate() : null;
    }

    /**
     * 增量计算因子值（不清除旧数据，跳过已有日期，只算新日期）
     */
    @Async("backtestTaskExecutor")
    public void computeFactorIncremental(FactorDefinition factor, LocalDate startDate, LocalDate endDate,
                                          List<String> symbols) {
        String code = factor.getFactorCode();

        // 获取所有需要计算的交易日
        List<LocalDate> tradingDates = marketDataService.getTradingDates(startDate, endDate);

        // 过滤掉已有数据的日期（通过查 factor_value 中已存在的 calc_date）
        final Set<LocalDate> existingDates;
        if (!tradingDates.isEmpty()) {
            LambdaQueryWrapper<FactorValue> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(FactorValue::getFactorCode, code)
                    .ge(FactorValue::getCalcDate, tradingDates.get(0))
                    .le(FactorValue::getCalcDate, tradingDates.get(tradingDates.size() - 1))
                    .select(FactorValue::getCalcDate)
                    .groupBy(FactorValue::getCalcDate);
            existingDates = new HashSet<>(factorValueMapper.selectList(wrapper).stream()
                    .map(FactorValue::getCalcDate)
                    .toList());
        } else {
            existingDates = Collections.emptySet();
        }

        List<LocalDate> newDates = tradingDates.stream()
                .filter(d -> !existingDates.contains(d))
                .toList();

        if (newDates.isEmpty()) {
            sendProgress(code, "DONE", 100, "增量计算：无新日期需要计算（已有数据到 " +
                    (existingDates.isEmpty() ? "无" : Collections.max(existingDates)) + "）");
            return;
        }

        log.info("[{}] incremental: total {} dates, {} new (skipping {} existing)",
                code, tradingDates.size(), newDates.size(), existingDates.size());

        int totalDates  = newDates.size();
        int totalStocks = symbols.size();
        long totalTasks = (long) totalDates * totalStocks;

        sendProgress(code, "COMPUTING", 0,
                String.format("[增量] 开始计算 [%s]，新增 %d 交易日 × %d 只股票 = %,d 条（跳过 %d 已有）",
                        code, totalDates, totalStocks, totalTasks, existingDates.size()));

        // ── 并行参数 ──
        // 限制内部线程数避免多因子并行时耗尽数据库连接池（HikariCP 30连接，最多10因子并行）
        int threads = Math.min(Math.max(Runtime.getRuntime().availableProcessors() / 2, 2), 4);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        log.info("[{}] Using {} threads", code, threads);

        AtomicInteger datesCompleted = new AtomicInteger(0);
        AtomicLong rowsInserted = new AtomicLong(0);
        AtomicLong startTimeMs = new AtomicLong(System.currentTimeMillis());
        int lastPushedPct = -1;

        List<Future<List<FactorValue>>> futures = new ArrayList<>(totalDates);
        for (LocalDate date : newDates) {
            futures.add(pool.submit(() -> computeOneDate(factor, date, symbols)));
        }
        pool.shutdown();

        List<FactorValue> writeBuffer = new ArrayList<>(2000);
        final int BATCH_SIZE = 500;

        for (int di = 0; di < futures.size(); di++) {
            LocalDate date = newDates.get(di);
            try {
                List<FactorValue> dayValues = futures.get(di).get();
                if (dayValues != null && !dayValues.isEmpty()) {
                    writeBuffer.addAll(dayValues);
                    rowsInserted.addAndGet(dayValues.size());
                }
            } catch (Exception e) {
                log.warn("[{}] date {} failed: {}", code, date, e.getMessage());
            }

            datesCompleted.incrementAndGet();

            if (writeBuffer.size() >= BATCH_SIZE || di == futures.size() - 1) {
                if (!writeBuffer.isEmpty()) {
                    batchSaveWithRetry(writeBuffer, code);
                    writeBuffer.clear();
                }
            }

            int pct = Math.min((int) ((double) datesCompleted.get() / totalDates * 90), 90);
            if (pct > lastPushedPct || datesCompleted.get() % 10 == 0) {
                lastPushedPct = pct;
                long elapsed = System.currentTimeMillis() - startTimeMs.get();
                double speed = elapsed > 0 ? (double) datesCompleted.get() / elapsed * 1000 : 0;
                int remaining = totalDates - datesCompleted.get();
                long etaSec = speed > 0 ? (long) (remaining / speed) : 0;
                sendProgress(code, "COMPUTING", pct,
                        String.format("[增量] %d/%d 交易日 (%d%%) | 已写 %,d 行 | 速度 %.1f 日/s | 剩余约 %s",
                                datesCompleted.get(), totalDates, pct,
                                rowsInserted.get(), speed, formatEta(etaSec)));
            }
        }

        // ── 归一化（只对新日期做） ──
        sendProgress(code, "COMPUTING", 91,
                String.format("增量写入完成，%,d 条。开始归一化 %d 个新日期...", rowsInserted.get(), newDates.size()));
        normalizeFactorValues(code, newDates.get(0), newDates.get(newDates.size() - 1), newDates);
        sendProgress(code, "COMPUTING", 100,
                String.format("[增量] 全部完成，新增 %,d 条", rowsInserted.get()));

        log.info("[{}] incremental done: {} new dates, {} rows", code, totalDates, rowsInserted.get());
    }

    /**
     * 同步计算因子值（供 runFactorTest 内部调用）
     * 优化：多线程并行（按日期分片）+ 批量写入（每批500条）
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public void computeFactorSync(FactorDefinition factor, LocalDate startDate, LocalDate endDate,
                                   List<String> symbols) {

        // 先清除该时间段的旧数据
        log.info("Clearing existing factor values for [{}] between {} and {}", factor.getFactorCode(), startDate, endDate);
        self.deleteExistingValues(factor.getFactorCode(), startDate, endDate);

        List<LocalDate> tradingDates = marketDataService.getTradingDates(startDate, endDate);
        int totalDates  = tradingDates.size();
        int totalStocks = symbols.size();
        long totalTasks = (long) totalDates * totalStocks;

        sendProgress(factor.getFactorCode(), "COMPUTING", 0,
                String.format("开始计算 [%s]，共 %d 交易日 × %d 只股票 = %,d 条",
                        factor.getFactorCode(), totalDates, totalStocks, totalTasks));

        // ── 并行参数 ──────────────────────────────────────────────
        // 按交易日并行，限制线程数避免多因子并行时耗尽数据库连接池
        int threads  = Math.min(Math.max(Runtime.getRuntime().availableProcessors() / 2, 2), 4);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        log.info("[{}] Using {} threads", factor.getFactorCode(), threads);

        // ── 进度计数器 ────────────────────────────────────────────
        AtomicInteger datesCompleted   = new AtomicInteger(0);
        AtomicLong    rowsInserted     = new AtomicLong(0);
        AtomicLong    startTimeMs      = new AtomicLong(System.currentTimeMillis());
        int lastPushedPct              = -1;

        // ── 提交每个交易日为一个任务 ──────────────────────────────
        List<Future<List<FactorValue>>> futures = new ArrayList<>(totalDates);
        for (LocalDate date : tradingDates) {
            futures.add(pool.submit(() -> computeOneDate(factor, date, symbols)));
        }
        pool.shutdown();

        // ── 收集结果并批量写入 ────────────────────────────────────
        List<FactorValue> writeBuffer = new ArrayList<>(2000);
        final int BATCH_SIZE = 500;

        for (int di = 0; di < futures.size(); di++) {
            LocalDate date = tradingDates.get(di);
            try {
                List<FactorValue> dayValues = futures.get(di).get();
                if (dayValues != null && !dayValues.isEmpty()) {
                    writeBuffer.addAll(dayValues);
                    rowsInserted.addAndGet(dayValues.size());
                }
            } catch (Exception e) {
                log.warn("[{}] date {} failed: {}", factor.getFactorCode(), date, e.getMessage());
            }

            datesCompleted.incrementAndGet();

            // 满 BATCH_SIZE 或每日都 flush
            if (writeBuffer.size() >= BATCH_SIZE || di == futures.size() - 1) {
                if (!writeBuffer.isEmpty()) {
                    batchSaveWithRetry(writeBuffer, factor.getFactorCode());
                    writeBuffer.clear();
                }
            }

            // 进度推送：每完成 1% 或每 10 个交易日推送一次
            int pct = Math.min((int) ((double) datesCompleted.get() / totalDates * 90), 90);
            if (pct > lastPushedPct || datesCompleted.get() % 10 == 0) {
                lastPushedPct = pct;
                long elapsed  = System.currentTimeMillis() - startTimeMs.get();
                double speed  = elapsed > 0 ? (double) datesCompleted.get() / elapsed * 1000 : 0; // dates/sec
                int remaining = totalDates - datesCompleted.get();
                long etaSec   = speed > 0 ? (long)(remaining / speed) : 0;
                sendProgress(factor.getFactorCode(), "COMPUTING", pct,
                        String.format("计算中 %d/%d 交易日 (%d%%) | 已写 %,d 行 | 速度 %.1f 日/s | 剩余约 %s",
                                datesCompleted.get(), totalDates, pct,
                                rowsInserted.get(), speed, formatEta(etaSec)));
            }
        }

        // ── 归一化阶段 ────────────────────────────────────────────
        sendProgress(factor.getFactorCode(), "COMPUTING", 91,
                String.format("因子值写入完成，共 %,d 条。开始横截面归一化...", rowsInserted.get()));
        normalizeFactorValues(factor.getFactorCode(), startDate, endDate, tradingDates);
        sendProgress(factor.getFactorCode(), "COMPUTING", 100,
                String.format("归一化完成，共处理 %d 个交易日，写入 %,d 条因子值", totalDates, rowsInserted.get()));

        log.info("[{}] computation done: {} dates, {} rows", factor.getFactorCode(), totalDates, rowsInserted.get());
    }

    /**
     * 计算单个交易日所有股票的因子值（在线程池中执行）
     */
    private List<FactorValue> computeOneDate(FactorDefinition factor, LocalDate date, List<String> symbols) {
        String factorCode = factor.getFactorCode();

        // 财务因子走单独的计算路径（基于财务报表数据，非行情K线）
        if (financialCalculators.containsKey(factorCode)) {
            return computeOneDateFinancial(factorCode, date, symbols);
        }

        LocalDate histStart = date.minusDays(400); // 预留足够历史窗口
        // 一次性加载该日期前的行情数据（减少重复查询）
        List<FactorValue> results = new ArrayList<>(symbols.size());
        LocalDateTime now = LocalDateTime.now();

        for (String symbol : symbols) {
            try {
                List<MarketDailyBar> history = marketDataService.getBarsBySymbol(symbol, histStart, date);
                BigDecimal value = computeSingleValue(factor, symbol, date, history);
                if (value != null) {
                    FactorValue fv = FactorValue.builder()
                            .factorCode(factor.getFactorCode())
                            .symbol(symbol)
                            .calcDate(date)
                            .factorVal(value)
                            .createdAt(now)
                            .build();
                    results.add(fv);
                }
            } catch (Exception e) {
                // 单只股票失败不影响整体
            }
        }
        return results;
    }

    /**
     * 财务因子计算：每个交易日使用该股票最新的年报财务指标
     * 财务数据按 end_date 排序，取 <= calcDate 的最近一期年报
     */
    private List<FactorValue> computeOneDateFinancial(String factorCode, LocalDate date, List<String> symbols) {
        FinancialFactorCalculator calculator = financialCalculators.get(factorCode);
        List<FactorValue> results = new ArrayList<>(symbols.size());
        LocalDateTime now = LocalDateTime.now();
        int year = date.getYear();

        for (String symbol : symbols) {
            try {
                // stock_financial_indicator.code 存储纯数字（如 000001），而 symbol 带后缀（如 000001.SZ）
                String code = symbol.contains(".") ? symbol.substring(0, symbol.indexOf('.')) : symbol;
                // 查询该股票 end_date <= calcDate 的最新一期年报（report_type=0 或年报）
                LambdaQueryWrapper<StockFinancialIndicator> wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(StockFinancialIndicator::getCode, code)
                        .le(StockFinancialIndicator::getEndDate, date)
                        .orderByDesc(StockFinancialIndicator::getEndDate)
                        .last("LIMIT 1");
                StockFinancialIndicator indicator = financialIndicatorMapper.selectOne(wrapper);

                if (indicator == null) continue;

                BigDecimal value = calculator.calculate(symbol, indicator);
                if (value != null) {
                    FactorValue fv = FactorValue.builder()
                            .factorCode(factorCode)
                            .symbol(symbol)
                            .calcDate(date)
                            .factorVal(value)
                            .createdAt(now)
                            .build();
                    results.add(fv);
                }
            } catch (Exception e) {
                // 单只股票失败不影响整体
            }
        }
        return results;
    }

    /** 格式化剩余时间：秒->分:秒 或 时:分 */
    private String formatEta(long seconds) {
        if (seconds <= 0) return "计算中";
        if (seconds < 60) return seconds + "秒";
        if (seconds < 3600) return (seconds / 60) + "分" + (seconds % 60) + "秒";
        return (seconds / 3600) + "时" + (seconds % 3600 / 60) + "分";
    }

    /**
     * 在独立事务中删除旧数据，避免长事务
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void deleteExistingValues(String factorCode, LocalDate startDate, LocalDate endDate) {
        LambdaQueryWrapper<FactorValue> deleteWrapper = new LambdaQueryWrapper<>();
        deleteWrapper.eq(FactorValue::getFactorCode, factorCode)
                .between(FactorValue::getCalcDate, startDate, endDate);
        factorValueMapper.delete(deleteWrapper);
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
            } catch (org.springframework.dao.DeadlockLoserDataAccessException e) {
                log.warn("Deadlock on batch insert for [{}], attempt {}/{}", factorCode, attempt, maxRetries);
                if (attempt == maxRetries) {
                    throw e;
                }
                try { Thread.sleep(500L * attempt); } catch (InterruptedException ignored) {}
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
        // 分批防止 SQL 过长（每批最多 500 条）
        int batchSize = 500;
        for (int i = 0; i < values.size(); i += batchSize) {
            List<FactorValue> sub = values.subList(i, Math.min(i + batchSize, values.size()));
            factorValueMapper.batchUpsert(sub);
        }
    }

    /**
     * 计算单个因子值
     */
    private BigDecimal computeSingleValue(FactorDefinition factor, String symbol,
                                           LocalDate calcDate, List<MarketDailyBar> history) {
        if (factor.getFactorType() == FactorDefinition.FactorType.BUILTIN) {
            FactorCalculator calc = builtinCalculators.get(factor.getFactorCode());
            if (calc != null) {
                return calc.calculate(symbol, calcDate, history, Map.of());
            }
        } else if (factor.getFactorType() == FactorDefinition.FactorType.SCRIPTED
                   && factor.getScriptCode() != null) {
            return scriptedEngine.calculate(factor.getScriptCode(), factor.getFactorCode(),
                    symbol, calcDate, history, Map.of());
        }
        return null;
    }

    /**
     * 对因子值做横截面归一化（Z-Score + 百分位排名）
     * 优化：批量 updateById 替代逐行更新，并带速率进度
     */
    private void normalizeFactorValues(String factorCode, LocalDate start, LocalDate end,
                                        List<LocalDate> dates) {
        int totalDates = dates.size();
        long normStart = System.currentTimeMillis();

        for (int di = 0; di < totalDates; di++) {
            LocalDate date = dates.get(di);
            LambdaQueryWrapper<FactorValue> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(FactorValue::getFactorCode, factorCode)
                    .eq(FactorValue::getCalcDate, date)
                    .orderByAsc(FactorValue::getSymbol);
            List<FactorValue> values = factorValueMapper.selectList(wrapper);
            if (values.isEmpty()) continue;

            double[] raw = values.stream()
                    .mapToDouble(v -> v.getFactorVal().doubleValue())
                    .toArray();

            int n = raw.length;
            double mean = Arrays.stream(raw).average().orElse(0);
            double std  = Math.sqrt(Arrays.stream(raw).map(v -> (v - mean) * (v - mean)).average().orElse(1));

            // 百分位排名（避免 O(n²)，改用排序后映射）
            double[] sorted = raw.clone();
            Arrays.sort(sorted);
            double[] pctRanks = new double[n];
            for (int i = 0; i < n; i++) {
                int lo = lowerBound(sorted, raw[i]);
                int hi = upperBound(sorted, raw[i]);
                double avgRank = lo + (hi - lo) / 2.0; // 0-based 平均秩
                pctRanks[i]    = n <= 1 ? 0.5 : avgRank / (n - 1);
            }

            // 批量设值，收集后统一批量更新
            for (int i = 0; i < values.size(); i++) {
                FactorValue fv = values.get(i);
                double zScore  = std == 0 ? 0 : (raw[i] - mean) / std;
                fv.setZScore(BigDecimal.valueOf(zScore).setScale(6, RoundingMode.HALF_UP));
                fv.setRankValue(BigDecimal.valueOf(pctRanks[i]).setScale(6, RoundingMode.HALF_UP));
            }
            // 批量 update（每批 500 条）
            int batchSize = 500;
            for (int i = 0; i < values.size(); i += batchSize) {
                List<FactorValue> sub = values.subList(i, Math.min(i + batchSize, values.size()));
                for (FactorValue fv : sub) factorValueMapper.updateById(fv);
            }

            // 每 5% 或每 20 日推送进度
            if ((di + 1) % Math.max(1, totalDates / 20) == 0 || di == totalDates - 1) {
                long elapsed = System.currentTimeMillis() - normStart;
                double speed = elapsed > 0 ? (di + 1.0) / elapsed * 1000 : 0;
                int remaining = totalDates - di - 1;
                long etaSec   = speed > 0 ? (long)(remaining / speed) : 0;
                int pct = 91 + (int)((double)(di + 1) / totalDates * 9);
                sendProgress(factorCode, "COMPUTING", Math.min(pct, 99),
                        String.format("归一化 %d/%d (%s) | 本日 %d 只 | 速度 %.1f 日/s | 剩余约 %s",
                                di + 1, totalDates, date, n, speed, formatEta(etaSec)));
            }
        }
    }

    /** 二分查找第一个 >= target 的下标（0-based） */
    private int lowerBound(double[] sorted, double target) {
        int lo = 0, hi = sorted.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted[mid] < target) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    /** 二分查找第一个 > target 的下标（0-based） */
    private int upperBound(double[] sorted, double target) {
        int lo = 0, hi = sorted.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted[mid] <= target) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    /**
     * 执行因子测试（IC分析 + 分组回测）
     * 产出：IC序列、分组收益、分组净值曲线、多空净值、单调性、IR、统计显著性
     */
    @Async("backtestTaskExecutor")
    public void runFactorTest(FactorTestReport report, FactorDefinition factor) {
        log.info("Running factor test for [{}], report id: {}, pool={}, freq={}",
                factor.getFactorCode(), report.getId(), report.getStockPool(), report.getRebalanceFreq());

        try {
            report.setStatus(FactorTestReport.TestStatus.RUNNING);
            testReportMapper.updateById(report);

            // ── 自动检查并计算因子值（无数据时同步计算，在异步线程内不影响HTTP） ──
            long valueCount = factorValueMapper.selectCount(
                    new LambdaQueryWrapper<FactorValue>()
                            .eq(FactorValue::getFactorCode, factor.getFactorCode())
                            .ge(FactorValue::getCalcDate, report.getStartDate())
                            .le(FactorValue::getCalcDate, report.getEndDate()));
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
            sendProgress(factor.getFactorCode(), "TEST_START", 6, "股票池加载完成，" + poolSymbols.size() + "只股票");

            final int GROUP_COUNT = 5;

            // ── IC 序列 ──────────────────────────────────────────
            List<Double> icList     = new ArrayList<>();
            List<Double> rankIcList = new ArrayList<>();
            List<Map<String, Object>> icSeriesData = new ArrayList<>();

            // ── 分组累计收益（每日收益累加，用于计算年化） ──────────
            double[] groupTotalReturns = new double[GROUP_COUNT];
            List<double[]> groupDailyReturnsList = new ArrayList<>();

            // ── 净值序列 ─────────────────────────────────────────
            double[] groupNavs  = new double[GROUP_COUNT];
            Arrays.fill(groupNavs, 1.0);
            double benchmarkNav = 1.0;

            List<Map<String, Object>> groupNavData     = new ArrayList<>();
            List<Map<String, Object>> longShortNavData = new ArrayList<>();

            // ── 多空净值 ─────────────────────────────────────────
            double lsTopNav    = 1.0;
            double lsBottomNav = 1.0;
            double lsNetNav    = 1.0;

            // ── 用于计算主动指标 ─────────────────────────────────
            List<Double> topGroupDailyList       = new ArrayList<>();  // 多头组日收益
            List<Double> benchmarkDailyList      = new ArrayList<>();  // 基准日收益
            List<Double> topActiveReturnList     = new ArrayList<>();  // 多头超额日收益

            int totalDays = dates.size() - 1;
            int processed = 0;

            for (int di = 0; di < dates.size() - 1; di++) {
                LocalDate calcDate = dates.get(di);
                LocalDate nextDate = dates.get(di + 1);

                LambdaQueryWrapper<FactorValue> wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(FactorValue::getFactorCode, factor.getFactorCode())
                        .eq(FactorValue::getCalcDate, calcDate)
                        .orderByAsc(FactorValue::getSymbol);
                List<FactorValue> factorValues = factorValueMapper.selectList(wrapper);

                // 按股票池过滤
                if (!poolSymbols.isEmpty()) {
                    factorValues = factorValues.stream()
                            .filter(fv -> poolSymbols.contains(fv.getSymbol()))
                            .toList();
                }

                if (factorValues.size() < GROUP_COUNT * 2) {
                    log.warn("Skip date {}: factorValues={} < {}", calcDate, factorValues.size(), GROUP_COUNT * 2);
                    sendProgress(factor.getFactorCode(), "TESTING", 10,
                            String.format("[%s] 因子值不足，仅 %d 只（需至少 %d），跳过", calcDate, factorValues.size(), GROUP_COUNT * 2));
                    appendNavPoint(groupNavData, calcDate, groupNavs, benchmarkNav);
                    appendLsNavPoint(longShortNavData, calcDate, lsTopNav, lsBottomNav, lsNetNav);
                    // 数据不足时添加0收益到统计列表，保持数据一致性
                    double[] zeros = new double[GROUP_COUNT];
                    groupDailyReturnsList.add(zeros);
                    topGroupDailyList.add(0.0);
                    benchmarkDailyList.add(0.0);
                    topActiveReturnList.add(0.0);
                    processed++;
                    continue;
                }

                // 获取下期收益
                Map<String, Double> nextReturns = new HashMap<>();
                for (FactorValue fv : factorValues) {
                    List<MarketDailyBar> curr = marketDataService.getBarsBySymbol(fv.getSymbol(), calcDate, calcDate);
                    List<MarketDailyBar> next = marketDataService.getBarsBySymbol(fv.getSymbol(), nextDate, nextDate);
                    if (!curr.isEmpty() && !next.isEmpty()) {
                        double r = next.getFirst().getClose().doubleValue()
                                / curr.getFirst().getClose().doubleValue() - 1;
                        nextReturns.put(fv.getSymbol(), r);
                    }
                }

                List<FactorValue> valid = factorValues.stream()
                        .filter(fv -> nextReturns.containsKey(fv.getSymbol()))
                        .toList();

                if (valid.size() < GROUP_COUNT * 2) {
                    log.warn("Skip date {}: valid returns={} (total={}, nextReturns={})", calcDate, valid.size(), factorValues.size(), nextReturns.size());
                    sendProgress(factor.getFactorCode(), "TESTING", 10,
                            String.format("[%s] 下期行情数据不足，有效 %d 只（需至少 %d），跳过", calcDate, valid.size(), GROUP_COUNT * 2));
                    appendNavPoint(groupNavData, calcDate, groupNavs, benchmarkNav);
                    appendLsNavPoint(longShortNavData, calcDate, lsTopNav, lsBottomNav, lsNetNav);
                    // 数据不足时添加0收益到统计列表，保持数据一致性
                    double[] zeros = new double[GROUP_COUNT];
                    groupDailyReturnsList.add(zeros);
                    topGroupDailyList.add(0.0);
                    benchmarkDailyList.add(0.0);
                    topActiveReturnList.add(0.0);
                    processed++;
                    continue;
                }

                // ── IC 计算 ──────────────────────────────────────
                double[] fValues   = valid.stream().mapToDouble(fv -> fv.getFactorVal().doubleValue()).toArray();
                double[] returns   = valid.stream().mapToDouble(fv -> nextReturns.get(fv.getSymbol())).toArray();
                double[] rankVals  = valid.stream().mapToDouble(fv -> fv.getRankValue() == null ? 0 : fv.getRankValue().doubleValue()).toArray();

                double ic     = pearsonCorr(fValues, returns);
                double rankIc = pearsonCorr(rankVals, returns);

                if (!Double.isNaN(ic))     icList.add(ic);
                if (!Double.isNaN(rankIc)) rankIcList.add(rankIc);

                Map<String, Object> icPoint = new HashMap<>();
                icPoint.put("date",   calcDate.toString());
                icPoint.put("ic",     Double.isNaN(ic)     ? 0 : round4(ic));
                icPoint.put("rankIc", Double.isNaN(rankIc) ? 0 : round4(rankIc));
                icSeriesData.add(icPoint);

                // ── 分组收益 ─────────────────────────────────────
                List<FactorValue> sortedByFactor = valid.stream()
                        .sorted(Comparator.comparingDouble(fv -> fv.getFactorVal().doubleValue()))
                        .toList();
                int groupSize = sortedByFactor.size() / GROUP_COUNT;

                double[] todayGroupRet = new double[GROUP_COUNT];
                double   benchmarkRet  = valid.stream()
                        .mapToDouble(fv -> nextReturns.getOrDefault(fv.getSymbol(), 0.0))
                        .average().orElse(0);

                for (int g = 0; g < GROUP_COUNT; g++) {
                    int from = g * groupSize;
                    int to   = (g == GROUP_COUNT - 1) ? sortedByFactor.size() : (g + 1) * groupSize;
                    if (from >= to) continue;
                    double gRet = sortedByFactor.subList(from, to).stream()
                            .mapToDouble(fv -> nextReturns.getOrDefault(fv.getSymbol(), 0.0))
                            .average().orElse(0);
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
                lsTopNav    *= (1 + topRet    - benchmarkRet);
                lsBottomNav *= (1 + bottomRet - benchmarkRet);
                lsNetNav    *= (1 + topRet    - bottomRet);

                appendNavPoint(groupNavData, calcDate, groupNavs, benchmarkNav);
                appendLsNavPoint(longShortNavData, calcDate, lsTopNav, lsBottomNav, lsNetNav);

                processed++;
                int pct = 10 + (int) ((double) processed / totalDays * 85);

                // 每期推送详细日志（IC + 分组收益 + 股票数）
                StringBuilder detail = new StringBuilder();
                detail.append(String.format("[%s] 股票:%d | IC:%.4f | RankIC:%.4f | 分组收益:",
                        calcDate, valid.size(),
                        Double.isNaN(ic) ? 0 : ic,
                        Double.isNaN(rankIc) ? 0 : rankIc));
                for (int g = 0; g < GROUP_COUNT; g++) {
                    detail.append(String.format(" G%d:%.2f%%", g + 1, todayGroupRet[g] * 100));
                }
                detail.append(String.format(" | 基准:%.2f%%", benchmarkRet * 100));
                sendProgress(factor.getFactorCode(), "TESTING", pct, detail.toString());
            }
            sendProgress(factor.getFactorCode(), "TESTING", 95, "回测计算完成，开始计算统计指标");

            // ── 汇总：IC 统计 ────────────────────────────────────
            sendProgress(factor.getFactorCode(), "TESTING", 96, "计算IC统计指标");
            if (!icList.isEmpty()) {
                double icMean = avg(icList);
                double icStdV = std(icList);
                double icir   = icStdV == 0 ? 0 : icMean / icStdV;
                long   posC   = icList.stream().filter(v -> v > 0).count();

                report.setIcMean(bd(icMean));
                report.setIcStd(bd(icStdV));
                report.setIcir(bd(icir));
                report.setIcPositiveRate(bd((double) posC / icList.size()));

                int    n      = icList.size();
                double tStat  = icStdV == 0 ? 0 : icMean / (icStdV / Math.sqrt(n));
                double pValue = tStatToPValue(tStat, n - 1);
                report.setIcTStat(bd(tStat));
                report.setIcPValue(bd(pValue));
                sendProgress(factor.getFactorCode(), "TESTING", 96,
                        String.format("IC统计完成，样本数%d | IC均值:%.4f | ICIR:%.4f | 正IC率:%.1f%% | t统计:%.2f | p值:%.4f",
                                icList.size(), icMean, icir, (double) posC / icList.size() * 100, tStat, pValue));
            }

            if (!rankIcList.isEmpty()) {
                double rIcMean = avg(rankIcList);
                double rIcStd  = std(rankIcList);
                report.setRankIcMean(bd(rIcMean));
                report.setRankIcir(bd(rIcStd == 0 ? 0 : rIcMean / rIcStd));
            }

            // ── 汇总：分组收益 ───────────────────────────────────
            report.setGroupCount(GROUP_COUNT);
            int tradingDays   = Math.max(processed, 1);
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
                double activeVol  = std(topActiveReturnList) * Math.sqrt(periodsPerYear);
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
                gr.put("group",        "分组" + (g + 1));
                gr.put("annualReturn", round4(annualReturns[g]));

                if (!groupDailyReturnsList.isEmpty()) {
                    final int gIdx = g;
                    List<Double> gDaily = groupDailyReturnsList.stream()
                            .map(arr -> arr[gIdx]).collect(Collectors.toList());
                    double vol    = std(gDaily) * Math.sqrt(periodsPerYear);
                    double sharpe = vol == 0 ? 0 : annualReturns[g] / vol;
                    if (sharpe > bestSharpe) bestSharpe = sharpe;
                    double maxDd  = calcMaxDrawdown(gDaily);
                    // 胜率：日收益 > 0 的比例
                    long winDays = gDaily.stream().filter(r -> r > 0).count();
                    double winRate = (double) winDays / gDaily.size();
                    // Calmar比率：年化收益 / 最大回撤
                    double calmar = maxDd == 0 ? 0 : annualReturns[g] / maxDd;
                    // 超额收益：该组年化 - 基准年化
                    double benchmarkNavFinal = benchmarkNav;
                    double benchmarkAnnual = years <= 0 ? 0 : Math.pow(benchmarkNavFinal, 1.0 / years) - 1;
                    double excessReturn = annualReturns[g] - benchmarkAnnual;
                    gr.put("volatility",   round4(vol));
                    gr.put("sharpe",       round4(sharpe));
                    gr.put("maxDrawdown",  round4(maxDd));
                    gr.put("winRate",      round4(winRate));
                    gr.put("calmar",       round4(calmar));
                    gr.put("excessReturn", round4(excessReturn));
                }
                groupReturnData.add(gr);
            }
            if (bestSharpe != Double.NEGATIVE_INFINITY) {
                report.setBestSharpe(bd(bestSharpe));
            }

            // 分组 IR + 多空显著性
            if (groupDailyReturnsList.size() > 1) {
                List<Double> lsDailyList = groupDailyReturnsList.stream()
                        .map(arr -> arr[GROUP_COUNT - 1] - arr[0])
                        .collect(Collectors.toList());
                double lsAvg  = avg(lsDailyList);
                double lsStd  = std(lsDailyList);
                double groupIr = lsStd == 0 ? 0 : lsAvg / lsStd * Math.sqrt(periodsPerYear);
                report.setGroupIr(bd(groupIr));

                int    n2     = lsDailyList.size();
                double tStat2 = lsStd == 0 ? 0 : lsAvg / (lsStd / Math.sqrt(n2));
                report.setLsPValue(bd(tStatToPValue(tStat2, n2 - 1)));
            }

            // 分组收益汇总日志
            StringBuilder groupSummary = new StringBuilder("分组年化收益：");
            for (int g = 0; g < GROUP_COUNT; g++) {
                groupSummary.append(String.format(" G%d:%.2f%%", g + 1, annualReturns[g] * 100));
            }
            groupSummary.append(String.format(" | 多空:%.2f%% | 单调性:%.4f",
                    (annualReturns[GROUP_COUNT - 1] - annualReturns[0]) * 100,
                    report.getMonotonicity() != null ? report.getMonotonicity().doubleValue() : 0));
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
            report.setErrorMessage(e.getMessage());
            testReportMapper.updateById(report);
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
                if (!Integer.valueOf(week).equals(lastWeek)) { result.add(d); lastWeek = week; }
            }
        } else if ("MONTHLY".equalsIgnoreCase(freq)) {
            // 每月取第一个交易日
            Integer lastMonth = null;
            for (LocalDate d : allDates) {
                if (!Integer.valueOf(d.getMonthValue()).equals(lastMonth)) {
                    result.add(d); lastMonth = d.getMonthValue();
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
            case "CSI500" -> new HashSet<>(sorted.subList(Math.min(300, sorted.size()),
                    Math.min(800, sorted.size())));
            case "CSI800" -> new HashSet<>(sorted.subList(0, Math.min(800, sorted.size())));
            case "CSI1000" -> new HashSet<>(sorted.subList(Math.min(800, sorted.size()),
                    Math.min(1800, sorted.size())));
            default -> Collections.emptySet();
        };
    }

    /** 根据调仓频率和有效期数计算年化因子（每年有多少个调仓期） */
    private double getAnnualFactor(String freq, int periods) {
        if (freq == null || "DAILY".equalsIgnoreCase(freq))   return 252.0 / periods;
        if ("WEEKLY".equalsIgnoreCase(freq))                   return 52.0  / periods;
        if ("MONTHLY".equalsIgnoreCase(freq))                  return 12.0  / periods;
        return 252.0 / periods;
    }

    /** 每年对应的调仓期数（用于年化波动率/IR等计算） */
    private double getPeriodsPerYear(String freq) {
        if (freq == null || "DAILY".equalsIgnoreCase(freq))   return 252.0;
        if ("WEEKLY".equalsIgnoreCase(freq))                   return 52.0;
        if ("MONTHLY".equalsIgnoreCase(freq))                  return 12.0;
        return 252.0;
    }

    // ── 私有工具方法 ───────────────────────────────────────────────

    private void appendNavPoint(List<Map<String, Object>> navData, LocalDate date,
                                 double[] groupNavs, double benchmarkNav) {
        Map<String, Object> pt = new LinkedHashMap<>();
        pt.put("date", date.toString());
        for (int g = 0; g < groupNavs.length; g++) {
            pt.put("g" + (g + 1), round4(groupNavs[g]));
        }
        pt.put("benchmark", round4(benchmarkNav));
        navData.add(pt);
    }

    private void appendLsNavPoint(List<Map<String, Object>> lsData, LocalDate date,
                                   double top, double bottom, double net) {
        Map<String, Object> pt = new LinkedHashMap<>();
        pt.put("date",   date.toString());
        pt.put("top",    round4(top));
        pt.put("bottom", round4(bottom));
        pt.put("net",    round4(net));
        lsData.add(pt);
    }

    /** Pearson 相关系数，den==0 返回 NaN */
    private double pearsonCorr(double[] x, double[] y) {
        int n = x.length;
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0, sumY2 = 0;
        for (int i = 0; i < n; i++) {
            sumX += x[i]; sumY += y[i];
            sumXY += x[i] * y[i];
            sumX2 += x[i] * x[i];
            sumY2 += y[i] * y[i];
        }
        double num = n * sumXY - sumX * sumY;
        double den = Math.sqrt((n * sumX2 - sumX * sumX) * (n * sumY2 - sumY * sumY));
        return den == 0 ? Double.NaN : num / den;
    }

    /** t统计量 → 双尾 p值（用正态近似 df > 30 时误差小） */
    private double tStatToPValue(double t, int df) {
        if (df <= 0) return 1.0;
        // 使用正态近似：p ≈ 2 * (1 - Φ(|t|))
        double abst = Math.abs(t);
        // Abramowitz and Stegun 近似
        double p = 2.0 * normalCdfComplement(abst);
        return Math.max(0, Math.min(1, p));
    }

    /** 标准正态分布右尾概率 P(Z > x) */
    private double normalCdfComplement(double x) {
        // 使用 Horner 近似（精度 ~1e-7）
        double t = 1.0 / (1.0 + 0.2316419 * x);
        double poly = t * (0.319381530 + t * (-0.356563782
                + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))));
        double phi = Math.exp(-0.5 * x * x) / Math.sqrt(2 * Math.PI);
        return phi * poly;
    }

    /** 最大回撤（基于日收益序列） */
    private double calcMaxDrawdown(List<Double> dailyReturns) {
        double nav = 1.0, peak = 1.0, maxDd = 0;
        for (double r : dailyReturns) {
            nav  *= (1 + r);
            peak  = Math.max(peak, nav);
            maxDd = Math.max(maxDd, (peak - nav) / peak);
        }
        return maxDd;
    }

    private double avg(List<Double> list) {
        return list.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private double std(List<Double> list) {
        double mean = avg(list);
        double var  = list.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0);
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
        try {
            Map<String, Object> msg = Map.of("factorCode", factorCode, "stage", stage,
                    "progress", pct, "message", message);
            messagingTemplate.convertAndSend("/topic/factor/" + factorCode, msg);
            // 同时广播到批量日志通道，供监控页面聚合展示
            messagingTemplate.convertAndSend("/topic/factor/batch-log", Map.of(
                    "type", "FACTOR_PROGRESS",
                    "factorCode", factorCode,
                    "stage", stage,
                    "progress", pct,
                    "message", message,
                    "timestamp", LocalDateTime.now().toString()
            ));
        } catch (Exception ignored) {}
    }

    /**
     * 计算因子衰减分析(因子有效期)
     * 分析因子值与未来不同期数收益的相关性衰减规律
     */
    private Map<String, Object> computeFactorDecayAnalysis(List<Map<String, Object>> icSeriesData) {
        List<Map<String, Object>> decaySeries = new ArrayList<>();
        double[] initialICs = icSeriesData.stream()
                .mapToDouble(d -> ((Number) d.get("ic")).doubleValue())
                .toArray();

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
                List<Double> logICs = absICs.stream()
                        .map(Math::log)
                        .toList();

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
