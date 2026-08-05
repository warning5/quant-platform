package com.quant.platform.strategy.paper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 多策略组合再平衡引擎（Route B）。
 * 每个子账户按各自 paper_risk_config 的 rebalance_freq / rebalance_threshold 独立判定：
 *   - THRESHOLD：当前持仓权重偏离等权目标超过阈值即触发
 *   - WEEKLY / MONTHLY：到达周期即触发（DAILY 由每日管线处理，本引擎跳过）
 * 触发后旋转至最新因子组合（reuse generateSignals + executeAllSignals），并写 paper_rebalance_log。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaperRebalanceService {

    private final PaperTradingMapper paperTradingMapper;
    private final PaperPositionMapper paperPositionMapper;
    private final PaperRiskConfigMapper paperRiskConfigMapper;
    private final PaperRebalanceLogMapper rebalanceLogMapper;
    private final PaperSignalGenerator signalGenerator;
    private final PaperOrderExecutionService executionService;
    private final PaperTradingService paperTradingService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 组合级：遍历运行中子账户，各自独立判定并触发再平衡 */
    public void rebalanceCombo(Long comboId) {
        List<PaperTrading> children = paperTradingMapper.selectList(
            new LambdaQueryWrapper<PaperTrading>()
                .eq(PaperTrading::getParentId, comboId)
                .eq(PaperTrading::getStatus, PaperTradingStatus.RUNNING));
        for (PaperTrading child : children) {
            try {
                rebalanceChild(child.getId(), false);
            } catch (Exception e) {
                log.warn("组合子账户 [{}] 再平衡异常: {}", child.getId(), e.getMessage());
            }
        }
    }

    /** 单个子账户再平衡（manual=true 时跳过同日去重，强制触发） */
    public PaperRebalanceLog rebalanceChild(Long childId, boolean manual) {
        PaperTrading child = paperTradingMapper.selectById(childId);
        if (child == null || !PaperTradingStatus.RUNNING.equals(child.getStatus())) {
            throw new IllegalArgumentException("子账户不存在或不在运行态");
        }
        PaperRiskConfig cfg = paperRiskConfigMapper.selectOne(
            new LambdaQueryWrapper<PaperRiskConfig>().eq(PaperRiskConfig::getPaperId, childId));
        if (cfg == null) cfg = PaperRiskConfig.defaults(childId);

        String freq = cfg.getRebalanceFreq();
        if (freq == null || "DAILY".equalsIgnoreCase(freq)) {
            log.debug("子账户 [{}] 日频再平衡由每日管线处理，跳过", childId);
            return null;
        }

        BigDecimal maxDrift = computeMaxDrift(child);
        BigDecimal threshold = cfg.getRebalanceThreshold() != null
            ? cfg.getRebalanceThreshold() : new BigDecimal("0.05");
        boolean thresholdMode = "THRESHOLD".equalsIgnoreCase(freq);
        boolean thresholdHit = maxDrift.compareTo(threshold) > 0;
        boolean scheduleHit = !thresholdMode && scheduleDue(child, freq);

        if (thresholdMode && !thresholdHit) {
            log.debug("子账户 [{}] 阈值未触发（maxDrift={}, threshold={}）", childId, maxDrift, threshold);
            return null;
        }
        if (!thresholdMode && !scheduleHit) {
            log.debug("子账户 [{}] 未到再平衡周期（freq={}）", childId, freq);
            return null;
        }
        if (!manual && alreadyRebalancedToday(childId)) {
            log.debug("子账户 [{}] 今日已再平衡，跳过", childId);
            return null;
        }

        // 执行再平衡：旋转至最新因子组合
        String before = allocationJson(childId);
        List<PaperSignal> signals = signalGenerator.generateSignals(childId);
        List<PaperPosition> executed = executionService.executeAllSignals(childId);
        // 刷新持仓价 + 记录当日 NAV
        paperTradingService.refreshPositionPrices(
            paperTradingService.getPositionsForPaper(childId));
        paperTradingService.appendNavRecord(childId);
        String after = allocationJson(childId);

        PaperRebalanceLog rebalanceLog = PaperRebalanceLog.builder()
            .paperId(childId)
            .triggerType(manual ? "MANUAL" : (thresholdMode ? "THRESHOLD" : "SCHEDULE"))
            .rebalanceDate(LocalDate.now())
            .beforeAllocationJson(before)
            .afterAllocationJson(after)
            .maxDriftPct(maxDrift)
            .tradedSymbols(executed.stream().map(PaperPosition::getCode).collect(Collectors.joining(",")))
            .note(String.format("freq=%s, maxDrift=%s, threshold=%s, signals=%d",
                freq, maxDrift, threshold, signals.size()))
            .build();
        rebalanceLogMapper.insert(rebalanceLog);
        log.info("子账户 [{}] 再平衡完成：trigger={}, maxDrift={}, 成交{}笔",
            childId, rebalanceLog.getTriggerType(), maxDrift, executed.size());
        return rebalanceLog;
    }

    /** 手动触发组合再平衡（遍历子账户，强制） */
    public int manualRebalanceCombo(Long comboId) {
        List<PaperTrading> children = paperTradingMapper.selectList(
            new LambdaQueryWrapper<PaperTrading>()
                .eq(PaperTrading::getParentId, comboId)
                .eq(PaperTrading::getStatus, PaperTradingStatus.RUNNING));
        int count = 0;
        for (PaperTrading child : children) {
            try {
                if (rebalanceChild(child.getId(), true) != null) count++;
            } catch (Exception e) {
                log.warn("组合子账户 [{}] 手动再平衡异常: {}", child.getId(), e.getMessage());
            }
        }
        return count;
    }

    /** 调整某子策略权重：更新组合根 strategyConfigJson 中该策略权重（重新归一化），并触发该子账户再平衡 */
    public PaperRebalanceLog adjustSubStrategyWeight(Long comboId, Long strategyId, BigDecimal newWeight) {
        if (newWeight == null || newWeight.compareTo(BigDecimal.ZERO) <= 0
                || newWeight.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("权重必须在 (0,1] 区间");
        }
        PaperTrading root = paperTradingMapper.selectById(comboId);
        if (root == null) throw new IllegalArgumentException("组合不存在");
        if (root.getStrategyConfigJson() == null || root.getStrategyConfigJson().isBlank()) {
            throw new IllegalArgumentException("该组合无策略配置");
        }
        Map<Long, Double> weights = signalGenerator.parseStrategyWeights(root.getStrategyConfigJson());
        if (!weights.containsKey(strategyId)) {
            throw new IllegalArgumentException("组合中未找到子策略: " + strategyId);
        }
        // 用新权重替换并重新归一化（保留其他策略相对比例）
        weights.put(strategyId, newWeight.doubleValue());
        double sum = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        List<Map<String, Object>> newConfig = weights.entrySet().stream()
            .map(e -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("strategyId", e.getKey());
                m.put("weight", Math.round((e.getValue() / sum) * 10000.0) / 10000.0);
                return m;
            }).collect(Collectors.toList());
        try {
            root.setStrategyConfigJson(objectMapper.writeValueAsString(newConfig));
            paperTradingMapper.updateById(root);
        } catch (Exception e) {
            throw new RuntimeException("更新组合权重配置失败: " + e.getMessage());
        }

        // 触发该子账户再平衡
        PaperTrading child = findChildByStrategy(comboId, strategyId);
        return rebalanceChild(child.getId(), true);
    }

    // ── 内部工具 ──

    private PaperTrading findChildByStrategy(Long comboId, Long strategyId) {
        PaperTrading child = paperTradingMapper.selectOne(new LambdaQueryWrapper<PaperTrading>()
            .eq(PaperTrading::getParentId, comboId).eq(PaperTrading::getStrategyId, strategyId));
        if (child == null) throw new IllegalArgumentException("组合下未找到子策略: " + strategyId);
        return child;
    }

    private boolean scheduleDue(PaperTrading child, String freq) {
        LocalDate last = lastRebalanceDate(child.getId());
        LocalDate base = last != null ? last
            : (child.getCreatedAt() != null ? child.getCreatedAt().toLocalDate() : LocalDate.now());
        long days = ChronoUnit.DAYS.between(base, LocalDate.now());
        if ("WEEKLY".equalsIgnoreCase(freq)) return days >= 7;
        if ("MONTHLY".equalsIgnoreCase(freq)) return days >= 30;
        return false;
    }

    private LocalDate lastRebalanceDate(Long paperId) {
        PaperRebalanceLog log = rebalanceLogMapper.selectOne(
            new LambdaQueryWrapper<PaperRebalanceLog>()
                .eq(PaperRebalanceLog::getPaperId, paperId)
                .orderByDesc(PaperRebalanceLog::getRebalanceDate)
                .last("LIMIT 1"));
        return log != null ? log.getRebalanceDate() : null;
    }

    private boolean alreadyRebalancedToday(Long paperId) {
        Long cnt = rebalanceLogMapper.selectCount(
            new LambdaQueryWrapper<PaperRebalanceLog>()
                .eq(PaperRebalanceLog::getPaperId, paperId)
                .eq(PaperRebalanceLog::getRebalanceDate, LocalDate.now()));
        return cnt != null && cnt > 0;
    }

    /** 当前最大权重偏离（相对等权目标 1/N） */
    private BigDecimal computeMaxDrift(PaperTrading child) {
        List<PaperPosition> positions = paperPositionMapper.selectList(
            new LambdaQueryWrapper<PaperPosition>().eq(PaperPosition::getPaperId, child.getId()));
        BigDecimal total = child.getTotalAssets();
        if (total == null || total.compareTo(BigDecimal.ZERO) <= 0 || positions.isEmpty()) {
            return BigDecimal.ZERO;
        }
        int n = positions.size();
        BigDecimal target = BigDecimal.ONE.divide(BigDecimal.valueOf(n), 6, RoundingMode.HALF_UP);
        BigDecimal max = BigDecimal.ZERO;
        for (PaperPosition p : positions) {
            BigDecimal mv = p.getMarketValue() != null ? p.getMarketValue() : BigDecimal.ZERO;
            BigDecimal w = mv.divide(total, 6, RoundingMode.HALF_UP);
            BigDecimal drift = w.subtract(target).abs();
            if (drift.compareTo(max) > 0) max = drift;
        }
        return max;
    }

    /** 持仓权重快照 JSON（再平衡前后对比用） */
    private String allocationJson(Long paperId) {
        try {
            PaperTrading pt = paperTradingMapper.selectById(paperId);
            List<PaperPosition> positions = paperPositionMapper.selectList(
                new LambdaQueryWrapper<PaperPosition>().eq(PaperPosition::getPaperId, paperId));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("totalAssets", pt != null ? pt.getTotalAssets() : null);
            m.put("positions", positions.stream().map(p -> {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("code", p.getCode());
                BigDecimal total = pt != null ? pt.getTotalAssets() : null;
                x.put("weight", (total != null && total.compareTo(BigDecimal.ZERO) > 0
                    && p.getMarketValue() != null)
                    ? p.getMarketValue().divide(total, 6, RoundingMode.HALF_UP) : BigDecimal.ZERO);
                return x;
            }).collect(Collectors.toList()));
            return objectMapper.writeValueAsString(m);
        } catch (Exception e) {
            return "{}";
        }
    }
}
