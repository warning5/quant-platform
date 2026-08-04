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
 * 因子横截面归一化服务
 * <p>从 {@link FactorComputeEngine} 拆出：Z-Score + 百分位排名计算，优先走 ClickHouse 窗口函数，
 * CH 不可用时回退 Java 内存计算。行为与拆分前逐字一致。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactorNormalizationService {

    private final FactorValueMapper factorValueMapper;
    private final ClickHouseFactorValueService clickHouseFactorValueService;
    private final FactorProgressService progressService;

    @Resource
    private ClickHouseConfig clickHouseConfig;

    /**
     * 对因子值做横截面归一化（Z-Score + 百分位排名）
     * 优化：ClickHouse 窗口函数一次性算完所有日期，INSERT 覆盖（ReplacingMergeTree 去重）
     * 性能：从 ~178万次 UPDATE 优化为 2次SQL，提速 10x+
     */
    public void normalizeFactorValues(String factorCode, List<LocalDate> dates) {
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

                progressService.sendProgress(factorCode, "COMPUTING", 99, String.format(
                        "归一化完成 | %d 日期 × 均值 %d 只/日 ≈ %,d 行 | 耗时 %.1f 秒",
                        dates.size(), rowCount > 0 && !dates.isEmpty() ? rowCount / dates.size() : 0,
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
    public void normalizeFactorValuesFallback(String factorCode, List<LocalDate> dates) {
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
                progressService.sendProgress(factorCode, "COMPUTING", Math.min(pct, 99), String.format("归一化 %d/%d (%s) | 本日 %d 只 | 速度 %.1f 日/s | 剩余约 %s", di + 1, totalDates, date, n, speed, progressService.formatEta(etaSec)), etaSec);
            }
        }
    }

    /**
     * 二分查找第一个 >= target 的下标（0-based）
     */
    public int lowerBound(double[] sorted, double target) {
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
    public int upperBound(double[] sorted, double target) {
        int lo = 0, hi = sorted.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted[mid] <= target) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

}
