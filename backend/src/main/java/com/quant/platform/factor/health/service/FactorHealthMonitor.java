package com.quant.platform.factor.health.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quant.platform.config.ClickHouseConfig;
import com.quant.platform.factor.domain.FactorDefinition;
import com.quant.platform.factor.ic.domain.FactorIcRecord;
import com.quant.platform.factor.ic.mapper.FactorIcRecordMapper;
import com.quant.platform.factor.mapper.FactorDefinitionMapper;
import com.quant.platform.factor.health.domain.FactorHealthLog;
import com.quant.platform.factor.health.mapper.FactorHealthLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 因子健康监控服务（P3-11）
 * 全生命周期管理：ACTIVE → 监控 → 预警 → 降权(DEGRADED) → 复活(ACTIVE)
 *
 * 核心规则：
 * - 衰减检测：90日|IC| < 启用时|IC|×50% → 预警；连续20日衰减>50% → DEGRADED
 * - 复活机制：DEGRADED因子连续10日|IC|>0.03且IR>0.2 → RESURRECTED→ACTIVE
 * - DEGRADED因子ICW权重=0，FACTOR_COMPUTE不计算
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactorHealthMonitor {

    private final FactorDefinitionMapper factorDefinitionMapper;
    private final FactorIcRecordMapper factorIcRecordMapper;
    private final FactorHealthLogMapper factorHealthLogMapper;
    private final ClickHouseConfig clickHouseConfig;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    /** 衰减预警阈值：90日IC < 启用时IC × 此比例 → 预警 */
    private static final double DECAY_WARNING_RATIO = 0.50;
    /** 降级阈值：90日IC < 启用时IC × 此比例 → DEGRADED */
    private static final double DECAY_DEGRADE_RATIO = 0.30;
    /** 连续衰减天数阈值：连续N日IC衰减>50% → DEGRADED */
    private static final int CONSECUTIVE_DECAY_DAYS_THRESHOLD = 20;
    /** 复活IC阈值：|IC| > 此值才算恢复 */
    private static final double RESURRECT_IC_THRESHOLD = 0.03;
    /** 复活IR阈值：IR > 此值才算恢复 */
    private static final double RESURRECT_IR_THRESHOLD = 0.2;
    /** 复活连续天数：连续N日IC恢复 → RESURRECTED */
    private static final int CONSECUTIVE_RECOVERY_DAYS_THRESHOLD = 10;
    /** IC前瞻天数（与推荐系统一致） */
    private static final int FORWARD_DAYS = 5;

    /**
     * 检查所有因子健康状态，执行预警/降级/复活
     * @return 健康检查报告
     */
    @Transactional
    public Map<String, Object> checkAllFactorsHealth() {
        log.info("[FactorHealth] 开始全因子健康检查");

        Map<String, Object> report = new LinkedHashMap<>();
        int warningCount = 0, degradedCount = 0, resurrectCount = 0;

        // 1. 检查ACTIVE因子（检测衰减）
        List<FactorDefinition> activeFactors = factorDefinitionMapper.selectList(
                new LambdaQueryWrapper<FactorDefinition>()
                        .eq(FactorDefinition::getStatus, FactorDefinition.FactorStatus.ACTIVE));

        for (FactorDefinition factor : activeFactors) {
            String code = factor.getFactorCode();
            try {
                FactorHealthStatus status = evaluateHealth(code);
                if (status.hasIcData) {
                    String healthStatus = status.shouldDegrade ? "DEGRADED" : "ACTIVE";
                    saveHealthMetric(code, status.latestTradeDate, status, healthStatus);
                }
                if (status.isWarning) {
                    warningCount++;
                    logEvent(code, FactorHealthLog.EventType.DEGRADE_WARNING, status, "IC衰减预警");
                }
                if (status.shouldDegrade) {
                    degradedCount++;
                    degradeFactor(code, status);
                }
            } catch (Exception e) {
                log.warn("[FactorHealth] 检查因子 {} 健康状态异常: {}", code, e.getMessage());
            }
        }

        // 2. 检查DEGRADED因子（检测复活）
        List<FactorDefinition> degradedFactors = factorDefinitionMapper.selectList(
                new LambdaQueryWrapper<FactorDefinition>()
                        .eq(FactorDefinition::getStatus, FactorDefinition.FactorStatus.DEGRADED));

        for (FactorDefinition factor : degradedFactors) {
            String code = factor.getFactorCode();
            try {
                FactorHealthStatus status = evaluateHealth(code);
                if (status.hasIcData) {
                    String healthStatus = status.shouldResurrect ? "ACTIVE" : "DEGRADED";
                    saveHealthMetric(code, status.latestTradeDate, status, healthStatus);
                }
                if (status.shouldResurrect) {
                    resurrectCount++;
                    resurrectFactor(code, status);
                } else if (status.hasIcData && Math.abs(status.ic90d) > RESURRECT_IC_THRESHOLD) {
                    logEvent(code, FactorHealthLog.EventType.RESURRECT_CANDIDATE, status, "IC开始恢复，未达复活阈值");
                }
            } catch (Exception e) {
                log.warn("[FactorHealth] 检查DEGRADED因子 {} 复活状态异常: {}", code, e.getMessage());
            }
        }

        report.put("activeChecked", activeFactors.size());
        report.put("degradedChecked", degradedFactors.size());
        report.put("warnings", warningCount);
        report.put("degraded", degradedCount);
        report.put("resurrected", resurrectCount);
        log.info("[FactorHealth] 健康检查完成: active={} degraded={} warnings={} newDegraded={} resurrected={}",
                activeFactors.size(), degradedFactors.size(), warningCount, degradedCount, resurrectCount);
        return report;
    }

    /**
     * 评估单个因子健康状态
     */
    private FactorHealthStatus evaluateHealth(String factorCode) {
        FactorHealthStatus status = new FactorHealthStatus();
        status.factorCode = factorCode;

        // 获取最近IC记录
        List<FactorIcRecord> recentRecords = factorIcRecordMapper.selectList(
                new LambdaQueryWrapper<FactorIcRecord>()
                        .eq(FactorIcRecord::getFactorCode, factorCode)
                        .eq(FactorIcRecord::getForwardDays, FORWARD_DAYS)
                        .orderByDesc(FactorIcRecord::getTradeDate)
                        .last("LIMIT 90"));

        if (recentRecords.isEmpty()) {
            status.hasIcData = false;
            return status;
        }
        status.hasIcData = true;
        status.latestTradeDate = recentRecords.get(0).getTradeDate();

        // 计算30/60/90日IC均值
        int total = recentRecords.size();
        status.ic30d = computeAbsIcAvg(recentRecords, Math.min(30, total));
        status.ic60d = computeAbsIcAvg(recentRecords, Math.min(60, total));
        status.ic90d = computeAbsIcAvg(recentRecords, total);

        // 计算30/60日IR
        if (total >= 30) {
            List<FactorIcRecord> last30 = recentRecords.subList(0, 30);
            double icAvg = last30.stream().mapToDouble(r -> r.getIcValue() != null ? r.getIcValue() : 0).average().orElse(0);
            double icStd = Math.sqrt(last30.stream().mapToDouble(r -> r.getIcValue() != null ? Math.pow(r.getIcValue() - icAvg, 2) : 0).average().orElse(0));
            status.ir30d = icStd > 0 ? Math.abs(icAvg) / icStd : 0;
        }
        if (total >= 60) {
            List<FactorIcRecord> last60 = recentRecords.subList(0, 60);
            double icAvg = last60.stream().mapToDouble(r -> r.getIcValue() != null ? r.getIcValue() : 0).average().orElse(0);
            double icStd = Math.sqrt(last60.stream().mapToDouble(r -> r.getIcValue() != null ? Math.pow(r.getIcValue() - icAvg, 2) : 0).average().orElse(0));
            status.ir60d = icStd > 0 ? Math.abs(icAvg) / icStd : 0;
        }

        // 计算衰减比例：使用最近记录中已有的ic20d_avg作为"近期IC基准"
        // 取最近20条记录的平均IC作为基准
        double recentBaseline = computeAbsIcAvg(recentRecords, Math.min(20, total));
        status.icAtActivation = recentBaseline;

        if (recentBaseline > 0.001) {
            status.decayRatio = status.ic90d / recentBaseline;
        } else {
            status.decayRatio = 1.0; // 基准太低，不判断衰减
        }

        // 衰减预警判断
        if (status.decayRatio < DECAY_WARNING_RATIO && status.ic90d < 0.03) {
            status.isWarning = true;
        }

        // 降级判断：连续20日IC衰减>50%
        int consecutiveDecayDays = 0;
        for (FactorIcRecord r : recentRecords) {
            if (r.getIcValue() != null && Math.abs(r.getIcValue()) < 0.015) {
                consecutiveDecayDays++;
            } else {
                break; // 从最近开始连续计数
            }
        }
        status.consecutiveDecayDays = consecutiveDecayDays;

        // 降级条件：(衰减比例<30% 且 IR<0.1) 或 连续20日IC<0.015
        boolean severeDecay = status.decayRatio < DECAY_DEGRADE_RATIO && (status.ir60d == null || status.ir60d < 0.1);
        boolean consecutiveNoise = consecutiveDecayDays >= CONSECUTIVE_DECAY_DAYS_THRESHOLD;
        status.shouldDegrade = severeDecay || consecutiveNoise;

        // 复活判断：DEGRADED因子连续10日|IC|>0.03且IR>0.2
        int consecutiveRecoveryDays = 0;
        for (FactorIcRecord r : recentRecords) {
            if (r.getIcValue() != null && Math.abs(r.getIcValue()) > RESURRECT_IC_THRESHOLD) {
                consecutiveRecoveryDays++;
            } else {
                break;
            }
        }
        status.consecutiveRecoveryDays = consecutiveRecoveryDays;
        status.shouldResurrect = consecutiveRecoveryDays >= CONSECUTIVE_RECOVERY_DAYS_THRESHOLD
                && status.ir30d != null && status.ir30d > RESURRECT_IR_THRESHOLD;

        return status;
    }

    /**
     * 计算最近N条记录的|IC|均值
     */
    private double computeAbsIcAvg(List<FactorIcRecord> records, int n) {
        return records.subList(0, Math.min(n, records.size()))
                .stream()
                .mapToDouble(r -> r.getIcValue() != null ? Math.abs(r.getIcValue()) : 0)
                .average()
                .orElse(0);
    }

    /**
     * 降级因子：ACTIVE → DEGRADED
     */
    @Transactional
    public void degradeFactor(String factorCode, FactorHealthStatus status) {
        FactorDefinition factor = factorDefinitionMapper.selectOne(
                new LambdaQueryWrapper<FactorDefinition>()
                        .eq(FactorDefinition::getFactorCode, factorCode));
        if (factor == null) return;

        factor.setStatus(FactorDefinition.FactorStatus.DEGRADED);
        factor.setUpdatedAt(LocalDateTime.now());
        factorDefinitionMapper.updateById(factor);

        String reason = String.format("IC衰减严重: decayRatio=%.2f, ic90d=%.4f, ir60d=%.2f, 连续噪声%d日",
                status.decayRatio, status.ic90d, status.ir60d != null ? status.ir60d : 0, status.consecutiveDecayDays);
        logEvent(factorCode, FactorHealthLog.EventType.DEGRADED, status, reason);
        log.info("[FactorHealth] 因子 {} 已降级为DEGRADED: {}", factorCode, reason);
        // P3-12: 发布因子状态变更事件
        eventPublisher.publishEvent(new com.quant.platform.common.event.FactorStatusChangedEvent(
            this, factorCode, "ACTIVE", "DEGRADED", reason));
    }

    /**
     * 手动降级因子（从API调用，不需要FactorHealthStatus）
     */
    @Transactional
    public void degradeFactorManual(String factorCode, String reason) {
        FactorDefinition factor = factorDefinitionMapper.selectOne(
                new LambdaQueryWrapper<FactorDefinition>()
                        .eq(FactorDefinition::getFactorCode, factorCode));
        if (factor == null) {
            throw new IllegalArgumentException("因子不存在: " + factorCode);
        }
        if (factor.getStatus() == FactorDefinition.FactorStatus.DEGRADED) {
            throw new IllegalStateException("因子已是DEGRADED状态: " + factorCode);
        }

        factor.setStatus(FactorDefinition.FactorStatus.DEGRADED);
        factor.setUpdatedAt(LocalDateTime.now());
        factorDefinitionMapper.updateById(factor);

        FactorHealthStatus status = new FactorHealthStatus();
        status.factorCode = factorCode;
        logEvent(factorCode, FactorHealthLog.EventType.DEGRADED, status, "手动降级: " + reason);
        log.info("[FactorHealth] 因子 {} 手动降级为DEGRADED: {}", factorCode, reason);
        // P3-12: 发布因子状态变更事件
        eventPublisher.publishEvent(new com.quant.platform.common.event.FactorStatusChangedEvent(
            this, factorCode, "ACTIVE", "DEGRADED", "手动降级: " + reason));
    }

    /**
     * 手动复活因子（从API调用）
     */
    @Transactional
    public void resurrectFactorManual(String factorCode) {
        FactorDefinition factor = factorDefinitionMapper.selectOne(
                new LambdaQueryWrapper<FactorDefinition>()
                        .eq(FactorDefinition::getFactorCode, factorCode));
        if (factor == null) {
            throw new IllegalArgumentException("因子不存在: " + factorCode);
        }
        if (factor.getStatus() != FactorDefinition.FactorStatus.DEGRADED) {
            throw new IllegalStateException("只有DEGRADED因子可复活，当前状态: " + factor.getStatus());
        }

        factor.setStatus(FactorDefinition.FactorStatus.ACTIVE);
        factor.setUpdatedAt(LocalDateTime.now());
        factorDefinitionMapper.updateById(factor);

        FactorHealthStatus status = new FactorHealthStatus();
        status.factorCode = factorCode;
        logEvent(factorCode, FactorHealthLog.EventType.RESURRECTED, status, "手动复活");
        log.info("[FactorHealth] 因子 {} 手动复活为ACTIVE", factorCode);
        // P3-12: 发布因子状态变更事件
        eventPublisher.publishEvent(new com.quant.platform.common.event.FactorStatusChangedEvent(
            this, factorCode, "DEGRADED", "ACTIVE", "手动复活"));
    }

    /**
     * 复活因子：DEGRADED → ACTIVE
     */
    @Transactional
    public void resurrectFactor(String factorCode, FactorHealthStatus status) {
        FactorDefinition factor = factorDefinitionMapper.selectOne(
                new LambdaQueryWrapper<FactorDefinition>()
                        .eq(FactorDefinition::getFactorCode, factorCode));
        if (factor == null) return;

        factor.setStatus(FactorDefinition.FactorStatus.ACTIVE);
        factor.setUpdatedAt(LocalDateTime.now());
        factorDefinitionMapper.updateById(factor);

        String reason = String.format("IC持续恢复: 连续恢复%d日, ic30d=%.4f, ir30d=%.2f",
                status.consecutiveRecoveryDays, status.ic30d, status.ir30d != null ? status.ir30d : 0);
        logEvent(factorCode, FactorHealthLog.EventType.RESURRECTED, status, reason);
        log.info("[FactorHealth] 因子 {} 已复活为ACTIVE: {}", factorCode, reason);
        // P3-12: 发布因子状态变更事件
        eventPublisher.publishEvent(new com.quant.platform.common.event.FactorStatusChangedEvent(
            this, factorCode, "DEGRADED", "ACTIVE", reason));
    }

    /**
     * 写入 ClickHouse factor_health_metric 时序表
     */
    private void saveHealthMetric(String factorCode, LocalDate metricDate,
                                  FactorHealthStatus status, String healthStatus) {
        if (clickHouseConfig == null || !clickHouseConfig.isEnabled()) {
            return;
        }
        if (metricDate == null) {
            metricDate = LocalDate.now();
        }
        String sql = """
            INSERT INTO stock.factor_health_metric
            (factor_code, metric_date, ic_30d, ic_60d, ic_90d, ir_30d, ir_60d,
             ic_at_activation, decay_ratio, health_status, consecutive_decay_days,
             consecutive_recovery_days, created_at, update_time)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())
            """;
        try (Connection conn = DriverManager.getConnection(
                clickHouseConfig.getJdbcUrl(), clickHouseConfig.getUsername(),
                clickHouseConfig.getPassword() != null ? clickHouseConfig.getPassword() : "");
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, factorCode);
            ps.setObject(2, metricDate);
            ps.setDouble(3, status.ic30d);
            ps.setDouble(4, status.ic60d);
            ps.setDouble(5, status.ic90d);
            ps.setObject(6, status.ir30d);
            ps.setObject(7, status.ir60d);
            ps.setDouble(8, status.icAtActivation);
            ps.setDouble(9, status.decayRatio);
            ps.setString(10, healthStatus);
            ps.setInt(11, status.consecutiveDecayDays);
            ps.setInt(12, status.consecutiveRecoveryDays);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("[FactorHealth] 写入 CH factor_health_metric 失败, factor={}, date={}: {}",
                    factorCode, metricDate, e.getMessage());
        }
    }

    /**
     * 记录健康事件日志
     */
    private void logEvent(String factorCode, FactorHealthLog.EventType eventType,
                          FactorHealthStatus status, String reason) {
        FactorHealthLog logEntry = FactorHealthLog.builder()
                .factorCode(factorCode)
                .eventType(eventType.name())
                .ic30d(status.ic30d)
                .ic60d(status.ic60d)
                .ic90d(status.ic90d)
                .ir30d(status.ir30d)
                .ir60d(status.ir60d)
                .icAtActivation(status.icAtActivation)
                .decayRatio(status.decayRatio)
                .reason(reason)
                .createdAt(LocalDateTime.now())
                .build();
        factorHealthLogMapper.insert(logEntry);
    }

    /**
     * 获取因子健康报告
     */
    public Map<String, Object> getHealthReport() {
        Map<String, Object> report = new LinkedHashMap<>();

        // 所有因子当前状态
        List<FactorDefinition> allFactors = factorDefinitionMapper.selectList(null);
        Map<String, String> statusMap = allFactors.stream()
                .collect(Collectors.toMap(FactorDefinition::getFactorCode, f -> f.getStatus().name()));
        report.put("factorStatuses", statusMap);

        // 最近健康日志
        List<FactorHealthLog> recentLogs = factorHealthLogMapper.selectList(
                new LambdaQueryWrapper<FactorHealthLog>()
                        .orderByDesc(FactorHealthLog::getCreatedAt)
                        .last("LIMIT 20"));
        report.put("recentLogs", recentLogs);

        // 状态分布
        long activeCount = allFactors.stream().filter(f -> f.getStatus() == FactorDefinition.FactorStatus.ACTIVE).count();
        long degradedCount = allFactors.stream().filter(f -> f.getStatus() == FactorDefinition.FactorStatus.DEGRADED).count();
        long deprecatedCount = allFactors.stream().filter(f -> f.getStatus() == FactorDefinition.FactorStatus.DEPRECATED).count();
        report.put("active", activeCount);
        report.put("degraded", degradedCount);
        report.put("deprecated", deprecatedCount);

        return report;
    }

    /**
     * 因子健康状态内部类
     */
    @lombok.Data
    private static class FactorHealthStatus {
        String factorCode;
        boolean hasIcData;
        LocalDate latestTradeDate;
        double ic30d;
        double ic60d;
        double ic90d;
        Double ir30d;
        Double ir60d;
        double icAtActivation;
        double decayRatio;
        boolean isWarning;
        boolean shouldDegrade;
        int consecutiveDecayDays;
        boolean shouldResurrect;
        int consecutiveRecoveryDays;
    }
}
