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
@Slf4j
@Service
@RequiredArgsConstructor
public class FactorProgressService {
    private final SimpMessagingTemplate messagingTemplate;
    private final java.util.Set<String> runningFactors =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    public void sendProgress(String factorCode, String stage, int pct, String message) {
        sendProgress(factorCode, stage, pct, message, null);
    }

    public void sendProgress(String factorCode, String stage, int pct, String message, Long etaSec) {
        // 维护 runningFactors 集合
        if ("COMPUTING".equals(stage)) {
            runningFactors.add(factorCode);
        } else if (JobStatus.DONE.name().equals(stage) || JobStatus.FAILED.name().equals(stage) || JobStatus.TEST_DONE.name().equals(stage)) {
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

    public void markRunning(String code) { runningFactors.add(code); }
    public void unmarkRunning(String code) { runningFactors.remove(code); }
    public int runningCount() { return runningFactors.size(); }
    public Set<String> getRunningFactorCodes() { return new HashSet<>(runningFactors); }
}
