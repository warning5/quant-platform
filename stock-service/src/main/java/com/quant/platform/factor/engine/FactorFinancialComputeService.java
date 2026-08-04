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
 * 财务因子计算服务
 * <p>从 {@link FactorComputeEngine} 拆出：财务因子计算器注册表、财务因子判定，
 * 以及按财报报告期（而非交易日）口径的增量/全量计算。行为与拆分前逐字一致。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactorFinancialComputeService {
    private final ClickHouseFactorValueService clickHouseFactorValueService;
    private final StockFinancialIndicatorMapper financialIndicatorMapper;
    private final com.quant.platform.factor.service.FactorMetaCacheService factorMetaCache;
    private final FactorProgressService progressService;
    private final FactorPersistenceService factorPersistenceService;
    private final FactorNormalizationService factorNormalizationService;
    private final Map<String, FinancialFactorCalculator> financialCalculators = new HashMap<>();

    {
        // 注册财务因子（8个ACTIVE）
        registerFinancial(new FinancialFactors.RoeCalc());
        registerFinancial(new FinancialFactors.RevenueYoyCalc());
        registerFinancial(new FinancialFactors.NetProfitYoyCalc());
        registerFinancial(new FinancialFactors.EarningsQualitySimpleCalc());
        registerFinancial(new FinancialFactors.RevenueQualityCalc());
        registerFinancial(new FinancialFactors.RdRevenueRatioCalc());
        registerFinancial(new FinancialFactors.OperatingProfitYoyCalc());
        registerFinancial(new FinancialFactors.TotalEquityYoyCalc());
        log.info("Registered {} financial factor calculators (static)", financialCalculators.size());
    }

    @jakarta.annotation.PostConstruct
    private void registerDeferred() {
        // VAL_PE_TTM / VAL_PB / VAL_DIVIDEND_YIELD / VAL_FCF_YIELD 已全部改为日频 builtin
        log.info("Registered {} financial factor calculators (total, after deferred)", financialCalculators.size());
    }
    /**
     * 财务因子增量计算（基于财报报告期，而非交易日）
     * 财务数据每年只有 4 份报告（一季报、半年报、三季报、年报），
     * 因此只需要在财报的 end_date 上计算，而不是按每个交易日计算。
     */
    public void computeFinancialFactorIncremental(String factorCode, LocalDate startDate, LocalDate endDate, List<String> symbols) {
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
            progressService.sendProgress(factorCode, JobStatus.DONE.name(), 100, "财务因子计算：无财报数据（" + startDate + " ~ " + endDate + "）");
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
            progressService.sendProgress(factorCode, JobStatus.DONE.name(), 100, "财务因子：无新报告期需要计算（已有数据到 " + (existingDatesFinal.isEmpty() ? "无" : Collections.max(existingDatesFinal)) + "）");
            return;
        }

        log.info("[{}] financial incremental: total {} report dates, {} new (skipping {} existing)",
                factorCode, reportDates.size(), newDates.size(), existingDates.size());

        int totalDates = newDates.size();
        int totalStocks = symbols.size();
        long totalTasks = (long) totalDates * totalStocks;

        progressService.sendProgress(factorCode, "COMPUTING", 0, String.format("[财务] 开始计算 [%s]，新增 %d 个报告期 × %d 只股票 = %,d 条", factorCode, totalDates, totalStocks, totalTasks));

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
                    factorPersistenceService.batchSaveWithRetry(writeBuffer, factorCode);
                    writeBuffer.clear();
                }
            }

            // 发送进度
            int pct = (int) ((double) (i + 1) / totalDates * 90);
            long elapsed = System.currentTimeMillis() - startTimeMs;
            double speed = elapsed > 0 ? (double) (i + 1) / elapsed * 1000 : 0;
            int remaining = totalDates - i - 1;
            long etaSec = speed > 0 ? (long) (remaining / speed) : 0;
            progressService.sendProgress(factorCode, "COMPUTING", pct, String.format("[财务] %d/%d 报告期 (%d%%) | 已写 %,d 行 | 剩余约 %s", i + 1, totalDates, pct, rowsInserted.get(), progressService.formatEta(etaSec)), etaSec);
        }

        // 归一化
        progressService.sendProgress(factorCode, "COMPUTING", 91, String.format("财务因子写入完成，%,d 条。开始归一化...", rowsInserted.get()));
        factorNormalizationService.normalizeFactorValues(factorCode, newDates);
        progressService.sendProgress(factorCode, JobStatus.DONE.name(), 100, String.format("[财务] 全部完成，新增 %,d 条", rowsInserted.get()));
        log.info("[{}] financial incremental done: {} new dates, {} rows", factorCode, totalDates, rowsInserted.get());
    }

    /**
     * 为单个财报报告期计算财务因子（对应一个日期，多只股票）
     * 优化：一次批量查询所有股票在该日期的最新财报，替代逐只N+1查询
     */
    public List<FactorValue> computeOneDateFinancialForReportDate(String factorCode, LocalDate reportDate, List<String> symbols, FinancialFactorCalculator calculator) {
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
                            .announceDate(indicator.getAnnounceDate())  // 真实公告日期
                            .createdAt(now)
                            .build();
                    results.add(fv);
                }
            } catch (Exception e) {
                failCount.incrementAndGet();
                if (!logged[0]) {
                    logged[0] = true;
                    log.warn("[{}] computeOneDateFinancial {} {}: 首个异常 code={}, msg={}", factorCode, reportDate, e.getClass().getSimpleName(), code, e.getMessage());
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
    public Map<String, StockFinancialIndicator> batchLoadLatestFinancials(List<String> symbols, LocalDate beforeDate) {
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
            // 批量查出所有符合条件的数据，Java 端 groupBy 取最新一条
            // （避免 GROUP BY + SELECT 非聚合字段触发 only_full_group_by 报错）
            List<StockFinancialIndicator> allIndicators = financialIndicatorMapper.selectList(
                    new LambdaQueryWrapper<StockFinancialIndicator>()
                            .in(StockFinancialIndicator::getCode, batch)
                            .le(StockFinancialIndicator::getEndDate, beforeDate)
                            .orderByDesc(StockFinancialIndicator::getEndDate));

            // 按 code 分组取最新一条
            result.putAll(allIndicators.stream()
                    .collect(Collectors.groupingBy(
                            StockFinancialIndicator::getCode,
                            Collectors.collectingAndThen(
                                    Collectors.maxBy(Comparator.comparing(StockFinancialIndicator::getEndDate)),
                                    opt -> opt.orElse(null)))));
        }
        return result;
    }

    /**
     * （基于财报报告期，而非交易日）
     */
    public void computeFinancialFactorSync(String factorCode, LocalDate startDate, LocalDate endDate, List<String> symbols) {
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
            progressService.sendProgress(factorCode, JobStatus.DONE.name(), 100, "财务因子：无财报数据（" + startDate + " ~ " + endDate + "）");
            return;
        }

        int totalDates = reportDates.size();
        int totalStocks = symbols.size();
        long totalTasks = (long) totalDates * totalStocks;

        // 不再 ALTER TABLE DELETE，直接 INSERT 覆盖（ReplacingMergeTree 按 update_time 去重）。
        log.info("[财务全量] 跳过删除，直接覆盖写入: factor={}, {}~{}", factorCode, startDate, endDate);

        progressService.sendProgress(factorCode, "COMPUTING", 0, String.format("[财务全量] 开始计算 [%s]，共 %d 个报告期 × %d 只股票 = %,d 条", factorCode, totalDates, totalStocks, totalTasks));

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
                    factorPersistenceService.batchSaveWithRetry(writeBuffer, factorCode);
                    writeBuffer.clear();
                }
            }

            // 发送进度
            int pct = (int) ((double) (i + 1) / totalDates * 90);
            long elapsed = System.currentTimeMillis() - startTimeMs;
            double speed = elapsed > 0 ? (double) (i + 1) / elapsed * 1000 : 0;
            int remaining = totalDates - i - 1;
            long etaSec = speed > 0 ? (long) (remaining / speed) : 0;
            progressService.sendProgress(factorCode, "COMPUTING", pct, String.format("[财务全量] %d/%d 报告期 (%d%%) | 已写 %,d 行 | 剩余约 %s", i + 1, totalDates, pct, rowsInserted.get(), progressService.formatEta(etaSec)), etaSec);
        }

        // 归一化
        progressService.sendProgress(factorCode, "COMPUTING", 91, String.format("财务因子写入完成，%,d 条。开始归一化...", rowsInserted.get()));
        factorNormalizationService.normalizeFactorValues(factorCode, reportDates);
        progressService.sendProgress(factorCode, JobStatus.DONE.name(), 100, String.format("[财务全量] 全部完成，共 %,d 条", rowsInserted.get()));
        log.info("[{}] financial sync done: {} dates, {} rows", factorCode, totalDates, rowsInserted.get());
    }

    public List<FactorValue> computeOneDateFinancial(String factorCode, LocalDate date, List<String> symbols) {
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
                    FactorValue fv = FactorValue.builder()
                            .factorCode(factorCode)
                            .symbol(code)
                            .calcDate(date)
                            .factorVal(value)
                            .announceDate(indicator.getAnnounceDate())
                            .createdAt(now)
                            .build();
                    results.add(fv);
                }
            } catch (Exception ignored) {
            }
        }
        return results;
    }

    /**
     * 判断是否为财务因子：从 DB 元数据驱动，季频+FINANCIAL/QUALITY分类即为财务因子
     */
    public boolean isFinancialFactor(String code) {
        if (code == null) return false;
        // DB驱动的财务因子判断
        if (factorMetaCache.isFinancial(code)) return true;
        // 兜底：已注册的财务计算器映射
        return financialCalculators.containsKey(code);
    }

    public void registerFinancial(FinancialFactorCalculator calc) {
        financialCalculators.put(calc.getFactorCode(), calc);
    }

}
