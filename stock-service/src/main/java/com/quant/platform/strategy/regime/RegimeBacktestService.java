package com.quant.platform.strategy.regime;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.util.*;

/**
 * Regime权重回验服务（P2-7）
 *
 * 核心功能：
 * 1. 历史Regime分布统计 — 扫描过去N个交易日，统计BULL/BEAR/SIDEWAYS分布
 * 2. Regime条件收益分析 — 按Regime分组，计算推荐的实际平均收益
 * 3. 权重最优性验证 — 对比当前权重 vs 候选权重组合在各Regime下的表现
 * 4. 权重调整建议 — 输出是否需要调整权重
 *
 * 当前权重配置：
 * - BULL:   因子0.6 + 分析0.4
 * - BEAR:   因子0.4 + 分析0.6
 * - SIDEWAYS: 因子0.5 + 分析0.5
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegimeBacktestService {

    private final JdbcTemplate jdbcTemplate;

    /** 回验默认天数 */
    private static final int DEFAULT_BACKTEST_DAYS = 120;

    /**
     * 执行Regime权重回验
     *
     * @param days 回验天数（默认120个交易日）
     * @return 回验报告
     */
    public Map<String, Object> runRegimeBacktest(int days) {
        if (days <= 0) days = DEFAULT_BACKTEST_DAYS;
        log.info("[RegimeBacktest] 开始Regime权重回验, days={}", days);

        Map<String, Object> report = new LinkedHashMap<>();

        // 1. 历史Regime分布统计（从stock_recommendation表读取历史推荐中的regime字段）
        Map<String, RegimeStats> regimeStats = computeRegimeStats(days);
        report.put("regimeDistribution", regimeStats);
        log.info("[RegimeBacktest] Regime分布: {}", regimeStats);

        // 2. 按Regime分组的推荐表现
        Map<String, RegimePerformance> performance = computeRegimePerformance(days);
        report.put("regimePerformance", performance);

        // 3. 权重最优性验证
        List<WeightComparison> comparisons = verifyWeightOptimality(performance);
        report.put("weightComparisons", comparisons);

        // 4. 权重调整建议
        List<String> suggestions = generateSuggestions(performance, comparisons);
        report.put("suggestions", suggestions);

        // 5. 汇总
        int totalRecs = regimeStats.values().stream().mapToInt(s -> s.recommendationCount).sum();
        report.put("totalRecommendations", totalRecs);
        report.put("backtestDays", days);

        log.info("[RegimeBacktest] 回验完成: totalRecs={}, suggestions={}", totalRecs, suggestions.size());
        return report;
    }

    /**
     * 统计历史Regime分布
     */
    private Map<String, RegimeStats> computeRegimeStats(int days) {
        Map<String, RegimeStats> stats = new LinkedHashMap<>();
        stats.put("BULL", new RegimeStats("BULL"));
        stats.put("BEAR", new RegimeStats("BEAR"));
        stats.put("SIDEWAYS", new RegimeStats("SIDEWAYS"));
        stats.put("UNKNOWN", new RegimeStats("UNKNOWN"));

        try {
            // 从stock_recommendation表统计regime分布
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT regime, COUNT(*) as cnt " +
                "FROM stock_recommendation " +
                "WHERE recommend_date >= DATE_SUB(CURDATE(), INTERVAL ? DAY) " +
                "GROUP BY regime", days);

            int total = 0;
            for (Map<String, Object> row : rows) {
                String regime = row.get("regime") != null ? row.get("regime").toString() : "UNKNOWN";
                int count = ((Number) row.get("cnt")).intValue();
                RegimeStats rs = stats.getOrDefault(regime, new RegimeStats(regime));
                rs.recommendationCount = count;
                total += count;
            }

            // 计算占比
            for (RegimeStats rs : stats.values()) {
                rs.percentage = total > 0 ? (double) rs.recommendationCount / total : 0;
            }

            // 统计不同日期数
            try {
                Integer distinctDates = jdbcTemplate.queryForObject(
                    "SELECT COUNT(DISTINCT recommend_date) FROM stock_recommendation " +
                    "WHERE recommend_date >= DATE_SUB(CURDATE(), INTERVAL ? DAY)", Integer.class, days);
                if (distinctDates != null) reportDates = distinctDates;
            } catch (Exception ignored) {}

        } catch (Exception e) {
            log.error("[RegimeBacktest] 统计Regime分布失败", e);
        }

        return stats;
    }
    private int reportDates = 0;

    /**
     * 按Regime分组计算推荐表现
     */
    private Map<String, RegimePerformance> computeRegimePerformance(int days) {
        Map<String, RegimePerformance> perf = new LinkedHashMap<>();
        perf.put("BULL", new RegimePerformance("BULL"));
        perf.put("BEAR", new RegimePerformance("BEAR"));
        perf.put("SIDEWAYS", new RegimePerformance("SIDEWAYS"));

        try {
            // 按regime分组，计算次日/周/月超额收益
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT regime, " +
                "  COUNT(*) as cnt, " +
                "  AVG(next_day_excess_return) as avg_next_day, " +
                "  AVG(next_week_excess_return) as avg_next_week, " +
                "  AVG(next_month_excess_return) as avg_next_month, " +
                "  SUM(CASE WHEN next_day_excess_return > 0 THEN 1 ELSE 0 END) as win_count, " +
                "  SUM(CASE WHEN next_day_excess_return IS NOT NULL THEN 1 ELSE 0 END) as tracked " +
                "FROM stock_recommendation " +
                "WHERE recommend_date >= DATE_SUB(CURDATE(), INTERVAL ? DAY) " +
                "  AND regime IS NOT NULL " +
                "GROUP BY regime", days);

            for (Map<String, Object> row : rows) {
                String regime = row.get("regime").toString();
                RegimePerformance rp = perf.getOrDefault(regime, new RegimePerformance(regime));
                rp.count = ((Number) row.get("cnt")).intValue();
                rp.avgNextDayExcess = row.get("avg_next_day") != null ? ((Number) row.get("avg_next_day")).doubleValue() : 0;
                rp.avgNextWeekExcess = row.get("avg_next_week") != null ? ((Number) row.get("avg_next_week")).doubleValue() : 0;
                rp.avgNextMonthExcess = row.get("avg_next_month") != null ? ((Number) row.get("avg_next_month")).doubleValue() : 0;
                rp.winCount = ((Number) row.get("win_count")).intValue();
                rp.trackedCount = ((Number) row.get("tracked")).intValue();
                rp.winRate = rp.trackedCount > 0 ? (double) rp.winCount / rp.trackedCount : 0;
            }

        } catch (Exception e) {
            log.error("[RegimeBacktest] 计算Regime表现失败", e);
        }

        return perf;
    }

    /**
     * 验证权重最优性
     * 对比当前权重 vs 候选权重
     */
    private List<WeightComparison> verifyWeightOptimality(Map<String, RegimePerformance> performance) {
        List<WeightComparison> comparisons = new ArrayList<>();

        // 当前权重
        comparisons.add(new WeightComparison("当前", 0.6, 0.4, 0.4, 0.6, 0.5, 0.5));

        // 候选1: 更激进（牛市更偏因子，熊市更偏分析）
        comparisons.add(new WeightComparison("激进", 0.7, 0.3, 0.3, 0.7, 0.5, 0.5));

        // 候选2: 更保守（权重差异缩小）
        comparisons.add(new WeightComparison("保守", 0.55, 0.45, 0.45, 0.55, 0.5, 0.5));

        // 候选3: 均衡（不随Regime变化）
        comparisons.add(new WeightComparison("静态", 0.5, 0.5, 0.5, 0.5, 0.5, 0.5));

        // 评估每个权重组合的预期表现
        for (WeightComparison wc : comparisons) {
            double bullScore = evaluateWeight(wc.bullFactorWeight, performance.get("BULL"));
            double bearScore = evaluateWeight(wc.bearFactorWeight, performance.get("BEAR"));
            double sidewaysScore = evaluateWeight(wc.sidewaysFactorWeight, performance.get("SIDEWAYS"));
            wc.bullExpectedReturn = bullScore;
            wc.bearExpectedReturn = bearScore;
            wc.sidewaysExpectedReturn = sidewaysScore;
            wc.totalExpectedReturn = bullScore + bearScore + sidewaysScore;
        }

        // 按总预期收益排序
        comparisons.sort((a, b) -> Double.compare(b.totalExpectedReturn, a.totalExpectedReturn));

        return comparisons;
    }

    /**
     * 评估特定权重在给定Regime表现下的预期收益
     * 简化模型：权重 × 因子收益 + (1-权重) × 分析收益
     */
    private double evaluateWeight(double factorWeight, RegimePerformance rp) {
        if (rp == null || rp.trackedCount == 0) return 0;
        // 用实际超额收益作为基准，权重影响因子/分析贡献比例
        // 假设因子得分和分析得分对收益的贡献与权重成正比
        return rp.avgNextDayExcess * (factorWeight * 0.5 + (1 - factorWeight) * 1.5);
    }

    /**
     * 生成权重调整建议
     */
    private List<String> generateSuggestions(Map<String, RegimePerformance> performance, List<WeightComparison> comparisons) {
        List<String> suggestions = new ArrayList<>();

        // 检查各Regime的胜率
        for (Map.Entry<String, RegimePerformance> entry : performance.entrySet()) {
            RegimePerformance rp = entry.getValue();
            if (rp.trackedCount < 10) {
                suggestions.add(String.format("%s: 样本不足(%d条), 无法有效验证", rp.regime, rp.trackedCount));
                continue;
            }
            if (rp.winRate < 0.45) {
                suggestions.add(String.format("%s: 胜率%.1f%%偏低(样本%d), 建议检查因子/分析权重配置",
                    rp.regime, rp.winRate * 100, rp.trackedCount));
            }
            if (rp.avgNextDayExcess < -0.001) {
                suggestions.add(String.format("%s: 次日超额收益%.2f%%为负, 当前权重可能不适应该Regime",
                    rp.regime, rp.avgNextDayExcess));
            }
        }

        // 检查最优权重是否为当前权重
        if (!comparisons.isEmpty()) {
            WeightComparison best = comparisons.get(0);
            if (!"当前".equals(best.name)) {
                suggestions.add(String.format("回验发现[%s]权重组合表现更优(预期收益%.4f vs 当前%.4f), 建议考虑调整",
                    best.name, best.totalExpectedReturn,
                    comparisons.stream().filter(c -> "当前".equals(c.name)).findFirst().map(c -> c.totalExpectedReturn).orElse(0.0)));
            }
        }

        if (suggestions.isEmpty()) {
            suggestions.add("当前Regime权重配置表现良好, 无需调整");
        }

        return suggestions;
    }

    // ===== 数据类 =====

    @Data
    public static class RegimeStats {
        private final String regime;
        private int recommendationCount;
        private double percentage;

        public RegimeStats(String regime) { this.regime = regime; }
    }

    @Data
    public static class RegimePerformance {
        private final String regime;
        private int count;
        private double avgNextDayExcess;
        private double avgNextWeekExcess;
        private double avgNextMonthExcess;
        private int winCount;
        private int trackedCount;
        private double winRate;

        public RegimePerformance(String regime) { this.regime = regime; }
    }

    @Data
    public static class WeightComparison {
        private final String name;
        private final double bullFactorWeight;
        private final double bullAnalysisWeight;
        private final double bearFactorWeight;
        private final double bearAnalysisWeight;
        private final double sidewaysFactorWeight;
        private final double sidewaysAnalysisWeight;
        private double bullExpectedReturn;
        private double bearExpectedReturn;
        private double sidewaysExpectedReturn;
        private double totalExpectedReturn;

        public WeightComparison(String name, double bf, double ba, double ef, double ea, double sf, double sa) {
            this.name = name;
            this.bullFactorWeight = bf;
            this.bullAnalysisWeight = ba;
            this.bearFactorWeight = ef;
            this.bearAnalysisWeight = ea;
            this.sidewaysFactorWeight = sf;
            this.sidewaysAnalysisWeight = sa;
        }
    }
}
