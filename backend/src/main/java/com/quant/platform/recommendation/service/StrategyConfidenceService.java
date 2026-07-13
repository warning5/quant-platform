package com.quant.platform.recommendation.service;

import com.quant.platform.recommendation.domain.StockRecommendation;
import com.quant.platform.recommendation.domain.StrategyConfidence;
import com.quant.platform.recommendation.mapper.RecommendationMapper;
import com.quant.platform.recommendation.mapper.StrategyConfidenceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 策略置信度服务（方案C）
 *
 * 基于历史追踪表现计算策略置信度，用于策略级风控。
 * 与方案B（个股黑名单）形成两层风控体系：
 *   Layer 1 (C): 策略级 → 检查置信度，低则降topN/暂停
 *   Layer 2 (B): 个股级  → 过滤黑名单股票
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyConfidenceService {

    private final StrategyConfidenceMapper strategyConfidenceMapper;
    private final RecommendationMapper recommendationMapper;

    /** 计算用的回溯期数（P3优化: 10→20，增加样本稳定性） */
    private static final int LOOKBACK_PERIODS = 20;

    // ── 维度权重（满分100）──
    private static final int MAX_HIT_RATE_SCORE = 40;    // 命中率维度满分
    private static final int MAX_RETURN_SCORE = 25;       // 平均收益维度满分
    private static final int MAX_DRAWDOWN_SCORE = 20;     // 最大回撤维度满分
    private static final int MAX_VOLATILITY_SCORE = 15;   // 波动率稳定性满分

    // ==================== 核心查询接口 ====================

    /**
     * 获取某策略的最新置信度（不区分权重模式，向后兼容）
     */
    public Optional<StrategyConfidence> getLatestConfidence(Long strategyId) {
        if (strategyId == null) return Optional.empty();
        return strategyConfidenceMapper.findLatestByStrategyId(strategyId);
    }

    /**
     * 获取某策略指定权重模式的最新置信度（P1优化: 按模式区分）
     * 如果指定模式没有记录，回退到不区分模式的查询
     */
    public Optional<StrategyConfidence> getLatestConfidence(Long strategyId, String weightMode) {
        if (strategyId == null) return Optional.empty();
        if (weightMode == null || weightMode.isBlank()) {
            return getLatestConfidence(strategyId);
        }
        Optional<StrategyConfidence> modeSpecific = strategyConfidenceMapper.findLatestByStrategyIdAndMode(strategyId, weightMode);
        if (modeSpecific.isPresent()) {
            return modeSpecific;
        }
        // 回退: 该模式无置信度记录时，用通用的（向后兼容）
        return strategyConfidenceMapper.findLatestByStrategyId(strategyId);
    }

    /**
     * 获取所有策略的最新置信度列表
     */
    public List<StrategyConfidence> getAllLatestConfidence() {
        return strategyConfidenceMapper.findAllLatest();
    }

    /**
     * 获取某策略的置信度历史趋势
     */
    public List<StrategyConfidence> getHistory(Long strategyId) {
        return strategyConfidenceMapper.findByStrategyId(strategyId);
    }

    /**
     * 根据置信度获取推荐的 topN 调整建议
     * @return 调整后的 topN
     */
    public int getAdjustedTopN(int originalTopN, StrategyConfidence confidence) {
        if (confidence == null || confidence.getScore() == null) {
            return originalTopN; // 无数据，不调整
        }

        int score = confidence.getScore();
        String level = confidence.getLevel();

        return switch (level) {
            case "HIGH" -> originalTopN;           // 高置信度：正常推荐
            case "NORMAL" -> originalTopN;         // 中等：正常（前端显示提醒）
            case "LOW" -> Math.max(3, originalTopN / 3);  // 低：缩减至1/3
            // P0修复: SUSPENDED 不再返回0（会导致死锁），改为保留最小探底topN
            // 这样SUSPENDED策略仍能生成少量推荐来积累样本，表现好可自然恢复
            case "SUSPENDED" -> Math.max(3, originalTopN / 5);  // 暂停：缩减至1/5，最低3
            default -> originalTopN;
        };
    }

    /**
     * 判断是否应显示警告
     */
    public boolean shouldShowWarning(StrategyConfidence confidence) {
        if (confidence == null) return false;
        String level = confidence.getLevel();
        return "LOW".equals(level) || "SUSPENDED".equals(level);
    }

    // ==================== 计算与更新 ====================

    /**
     * 为指定策略重新计算并保存置信度（计算所有权重模式）
     * 通常在 trackRecommendationPerformance() 之后调用
     */
    @Transactional
    public StrategyConfidence calculateAndSave(Long strategyId) {
        if (strategyId == null) {
            log.warn("[Confidence] strategyId为空，跳过计算");
            return null;
        }

        // P1优化: 查找该策略的所有权重模式，分别计算
        List<String> modes = recommendationMapper.findModesByStrategyId(strategyId);
        if (modes.isEmpty()) {
            log.info("[Confidence] strategyId={} 无权重模式数据，使用默认ICW", strategyId);
            modes = List.of("ICW");
        }

        StrategyConfidence lastResult = null;
        for (String mode : modes) {
            try {
                lastResult = calculateAndSave(strategyId, mode);
            } catch (Exception e) {
                log.warn("[Confidence] 计算策略置信度异常: strategyId={}, mode={}, error={}", strategyId, mode, e.getMessage());
            }
        }
        return lastResult;
    }

    /**
     * 为指定策略+权重模式重新计算并保存置信度（P1优化: 按模式区分）
     */
    @Transactional
    public StrategyConfidence calculateAndSave(Long strategyId, String weightMode) {
        if (strategyId == null) {
            log.warn("[Confidence] strategyId为空，跳过计算");
            return null;
        }
        if (weightMode == null || weightMode.isBlank()) {
            weightMode = "ICW";
        }

        log.info("[Confidence] 开始计算策略置信度: strategyId={}, weightMode={}", strategyId, weightMode);

        // 1. 收集近LOOKBACK_PERIODS期的追踪数据（按权重模式过滤）
        List<StockRecommendation> trackedRecs = collectTrackedData(strategyId, weightMode);
        if (trackedRecs.isEmpty()) {
            log.info("[Confidence] strategyId={}, mode={} 暂无追踪数据，无法计算", strategyId, weightMode);
            return createUntrained(strategyId, weightMode);
        }

        // 2. 计算各维度得分
        ConfidenceCalculation calc = calculateDimensions(trackedRecs);

        // 3. 构建并保存结果
        StrategyConfidence sc = buildAndSave(strategyId, weightMode, calc, trackedRecs);

        log.info("[Confidence] 计算完成: strategyId={}, mode={}, score={}, level={}, sampleSize={}, hitRate={}%, avgReturn={}%",
                strategyId, weightMode, sc.getScore(), sc.getLevel(), sc.getSampleSize(),
                sc.getHitRateValue() != null ? sc.getHitRateValue().doubleValue() * 100 : 0,
                sc.getAvgReturnValue() != null ? sc.getAvgReturnValue().doubleValue() : 0);

        return sc;
    }

    /**
     * 收集近N期的追踪数据（P1优化: 按权重模式过滤）
     */
    private List<StockRecommendation> collectTrackedData(Long strategyId, String weightMode) {
        // 找到该策略最近的推荐日期
        List<LocalDate> dates = recommendationMapper.findDatesByStrategyId(strategyId, LOOKBACK_PERIODS);
        if (dates.isEmpty()) return List.of();

        List<StockRecommendation> allRecs = new ArrayList<>();
        for (LocalDate date : dates) {
            // P1修复: 使用 findByStrategyAndDateAndMode 按权重模式过滤
            List<StockRecommendation> recs = recommendationMapper.findByStrategyAndDateAndMode(strategyId, date, weightMode);
            for (StockRecommendation rec : recs) {
                // 只保留有次日追踪数据的
                if (rec.getNextDayReturn() != null) {
                    allRecs.add(rec);
                }
            }
        }
        return allRecs;
    }

    /**
     * 计算各维度得分（核心算法）
     */
    private ConfidenceCalculation calculateDimensions(List<StockRecommendation> recs) {
        ConfidenceCalculation calc = new ConfidenceCalculation();
        int n = recs.size();

        // ---- 维度1: 近N期命中率 (0~40分) ----
        long positiveCount = recs.stream()
                .filter(r -> r.getNextDayReturn() != null && r.getNextDayReturn() > 0)
                .count();
        double hitRate = (double) positiveCount / n;
        calc.hitRateValue = BigDecimal.valueOf(hitRate).setScale(4, RoundingMode.HALF_UP);
        // 非线性映射: 50%为随机基准，不超过10分
        if (hitRate < 0.35) {
            calc.hitRateScore = 0;
        } else if (hitRate < 0.50) {
            calc.hitRateScore = (int) Math.round((hitRate - 0.35) / 0.15 * 10);
        } else if (hitRate < 0.65) {
            calc.hitRateScore = 10 + (int) Math.round((hitRate - 0.50) / 0.15 * 18);
        } else {
            calc.hitRateScore = 28 + (int) Math.round((hitRate - 0.65) / 0.35 * 12);
            calc.hitRateScore = Math.min(MAX_HIT_RATE_SCORE, calc.hitRateScore);
        }

        // ---- 维度2: 平均收益率正负 (0~25分) ----
        double avgReturn = recs.stream()
                .filter(r -> r.getNextDayReturn() != null)
                .mapToDouble(StockRecommendation::getNextDayReturn)
                .average().orElse(0);
        calc.avgReturnValue = BigDecimal.valueOf(avgReturn).setScale(4, RoundingMode.HALF_UP);
        if (avgReturn >= 2.0) {
            calc.returnScore = MAX_RETURN_SCORE;
        } else if (avgReturn <= -3.0) {
            calc.returnScore = 0;
        } else {
            calc.returnScore = (int) Math.round((avgReturn + 3.0) / 5.0 * MAX_RETURN_SCORE);
            calc.returnScore = Math.max(0, Math.min(MAX_RETURN_SCORE, calc.returnScore));
        }

        // ---- 维度3: P5分位回撤 (0~20分) ----
        double[] sortedReturns = recs.stream()
                .filter(r -> r.getNextDayReturn() != null)
                .mapToDouble(StockRecommendation::getNextDayReturn)
                .sorted()
                .toArray();

        double drawdownMetric;
        if (sortedReturns.length >= 20) {
            int p5Index = (int) Math.floor(sortedReturns.length * 0.05);
            drawdownMetric = sortedReturns[p5Index];
        } else {
            drawdownMetric = sortedReturns.length > 0 ? sortedReturns[0] : 0;
        }
        calc.maxDrawdownValue = BigDecimal.valueOf(drawdownMetric).setScale(4, RoundingMode.HALF_UP);
        if (drawdownMetric >= 0) {
            calc.drawdownScore = MAX_DRAWDOWN_SCORE;
        } else if (drawdownMetric <= -8) {
            calc.drawdownScore = 0;
        } else {
            calc.drawdownScore = (int) Math.round(drawdownMetric / -8.0 * MAX_DRAWDOWN_SCORE);
        }

        // ---- 维度4: 收益波动率/稳定性 (0~15分) ----
        double[] returns = recs.stream()
                .filter(r -> r.getNextDayReturn() != null)
                .mapToDouble(StockRecommendation::getNextDayReturn)
                .toArray();
        double stdDev = calculateStdDev(returns);
        calc.volatilityValue = BigDecimal.valueOf(stdDev).setScale(4, RoundingMode.HALF_UP);
        if (stdDev <= 1.5) {
            calc.volatilityScore = MAX_VOLATILITY_SCORE;
        } else if (stdDev >= 6) {
            calc.volatilityScore = 0;
        } else {
            calc.volatilityScore = (int) Math.round((6 - stdDev) / 4.5 * MAX_VOLATILITY_SCORE);
            calc.volatilityScore = Math.max(0, Math.min(MAX_VOLATILITY_SCORE, calc.volatilityScore));
        }

        return calc;
    }

    /**
     * 构建并持久化 StrategyConfidence 对象
     * P1优化: 改为append模式（不再先删后插），保留历史趋势
     */
    private StrategyConfidence buildAndSave(Long strategyId, String weightMode,
                                           ConfidenceCalculation calc,
                                           List<StockRecommendation> recs) {
        int totalScore = calc.hitRateScore + calc.returnScore + calc.drawdownScore + calc.volatilityScore;
        String level = StrategyConfidence.getLevelByScore(totalScore);

        // 找最近一次追踪日期
        LocalDate dataAsOf = recs.stream()
                .map(StockRecommendation::getRecommendDate)
                .filter(Objects::nonNull)
                .max(LocalDate::compareTo)
                .orElse(LocalDate.now());

        StrategyConfidence sc = new StrategyConfidence();
        sc.setStrategyId(strategyId);
        sc.setWeightMode(weightMode);
        sc.setLevel(level);
        sc.setScore(totalScore);
        sc.setHitRateScore(calc.hitRateScore);
        sc.setHitRateValue(calc.hitRateValue);
        sc.setReturnScore(calc.returnScore);
        sc.setAvgReturnValue(calc.avgReturnValue);
        sc.setDrawdownScore(calc.drawdownScore);
        sc.setMaxDrawdownValue(calc.maxDrawdownValue);
        sc.setVolatilityScore(calc.volatilityScore);
        sc.setVolatilityValue(calc.volatilityValue);
        sc.setSampleSize(recs.size());
        sc.setDataAsOfDate(dataAsOf);
        sc.setCreatedAt(LocalDateTime.now());
        sc.setUpdatedAt(LocalDateTime.now());

        // P1修复: 改为append模式，保留历史趋势（/history API 可返回多条记录）
        // 不再 deleteByStrategyId，直接insert
        strategyConfidenceMapper.insert(sc);

        return sc;
    }

    /**
     * 创建未训练状态（无足够数据）
     */
    private StrategyConfidence createUntrained(Long strategyId, String weightMode) {
        StrategyConfidence sc = new StrategyConfidence();
        sc.setStrategyId(strategyId);
        sc.setWeightMode(weightMode);
        sc.setLevel("UNTRAINED");
        sc.setScore(null);  // 无分数表示未训练
        sc.setDataAsOfDate(LocalDate.now());
        sc.setSampleSize(0);
        sc.setCreatedAt(LocalDateTime.now());
        sc.setUpdatedAt(LocalDateTime.now());

        // 不覆盖已有的有效记录
        Optional<StrategyConfidence> existing = strategyConfidenceMapper.findLatestByStrategyIdAndMode(strategyId, weightMode);
        if (existing.isPresent()) {
            return existing.get(); // 返回旧值
        }
        // 回退到不区分模式的查询
        Optional<StrategyConfidence> existingGeneric = strategyConfidenceMapper.findLatestByStrategyId(strategyId);
        if (existingGeneric.isPresent()) {
            return existingGeneric.get();
        }

        strategyConfidenceMapper.insert(sc);
        return sc;
    }

    // ==================== 工具方法 ====================

    /**
     * 计算标准差
     * P2修复: 小样本(length<2)时返回5.0(中间值)而非0，避免波动率维度虚高满分
     */
    private static double calculateStdDev(double[] values) {
        if (values.length < 2) return 5.0; // 中间值，对应约8分而非满分15
        double mean = Arrays.stream(values).average().orElse(0);
        double sumSqDiff = Arrays.stream(values).map(v -> Math.pow(v - mean, 2)).sum();
        return Math.sqrt(sumSqDiff / (values.length - 1)); // 样本标准差
    }

    /** 内部计算中间对象 */
    private static class ConfidenceCalculation {
        int hitRateScore = 0;
        BigDecimal hitRateValue = BigDecimal.ZERO;
        int returnScore = 0;
        BigDecimal avgReturnValue = BigDecimal.ZERO;
        int drawdownScore = 0;
        BigDecimal maxDrawdownValue = BigDecimal.ZERO;
        int volatilityScore = 0;
        BigDecimal volatilityValue = BigDecimal.ZERO;
    }
}
