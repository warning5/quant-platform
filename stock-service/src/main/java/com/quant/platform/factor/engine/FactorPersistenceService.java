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
 * 因子值持久化服务
 * <p>从 {@link FactorComputeEngine} 拆出：负责因子值的批量落库、死锁重试、旧数据清理。
 * 行为与拆分前逐字一致。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactorPersistenceService {

    private final ClickHouseFactorValueService clickHouseFactorValueService;
    private final FactorProgressService progressService;

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
    public void batchSaveWithRetry(List<FactorValue> values, String factorCode) {
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

    public void batchSave(List<FactorValue> values) {
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
    public void batchSaveWithProgress(List<FactorValue> values, String factorCode) {
        if (values == null || values.isEmpty()) return;
        LocalDateTime now = LocalDateTime.now();
        for (FactorValue value : values) {
            if (value.getCreatedAt() == null) {
                value.setCreatedAt(now);
            }
        }
        progressService.sendProgress(factorCode, "COMPUTING", 65,
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
        progressService.sendProgress(factorCode, "COMPUTING", 90,
                String.format("写入完成，%,d 行，耗时 %.1f 秒，速度 %.0f 行/s", values.size(), ms / 1000.0, speed), null);
    }

}
