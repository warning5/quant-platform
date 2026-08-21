package com.quant.platform.recommendation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quant.platform.recommendation.domain.StockRecommendation;
import com.quant.platform.recommendation.mapper.RecommendationMapper;
import com.quant.platform.stock.entity.StockInfo;
import com.quant.platform.stock.mapper.StockInfoMapper;
import com.quant.platform.strategy.domain.StrategyDefinition;
import com.quant.platform.strategy.mapper.StrategyDefinitionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 推荐服务的纯查询/统计职责（God Class 拆分 Phase 1）。
 * 从 {@link RecommendationService} 抽取的只读查询方法，方法体与原实现逐字一致，仅变更归属类。
 * {@link RecommendationService} 保留同名公开方法作为薄委托层，外部调用方（Controller 等）签名不变。
 */
@Slf4j
@Service
public class RecommendationQueryService {

    private final RecommendationMapper recommendationMapper;
    private final StrategyDefinitionMapper strategyDefinitionMapper;
    private final StockInfoMapper stockInfoMapper;

    public RecommendationQueryService(RecommendationMapper recommendationMapper,
                                      StrategyDefinitionMapper strategyDefinitionMapper,
                                      StockInfoMapper stockInfoMapper) {
        this.recommendationMapper = recommendationMapper;
        this.strategyDefinitionMapper = strategyDefinitionMapper;
        this.stockInfoMapper = stockInfoMapper;
    }

    /**
     * 获取最新推荐列表
     */
    public List<StockRecommendation> getLatestRecommendations() {
        StockRecommendation latest = recommendationMapper.findLatest();
        if (latest == null) {
            return List.of();
        }
        List<StockRecommendation> recs = recommendationMapper.findByStrategyAndDate(
                latest.getStrategyId(), latest.getRecommendDate());
        return enrichFromStockInfo(recs);
    }

    /**
     * 获取指定策略+日期的推荐列表（不过滤模式，合并所有模式快照）
     */
    public List<StockRecommendation> getRecommendationsByStrategyAndDate(Long strategyId, LocalDate recommendDate) {
        return enrichFromStockInfo(recommendationMapper.findByStrategyAndDate(strategyId, recommendDate));
    }

    /**
     * 获取指定策略+日期的推荐列表（按权重模式过滤）
     * @param weightMode 权重模式，null/空/ALL=不过滤
     */
    public List<StockRecommendation> getRecommendationsByStrategyAndDate(Long strategyId, LocalDate recommendDate, String weightMode) {
        if (weightMode == null || weightMode.isEmpty() || "ALL".equalsIgnoreCase(weightMode)) {
            return getRecommendationsByStrategyAndDate(strategyId, recommendDate);
        }
        return enrichFromStockInfo(recommendationMapper.findByStrategyAndDateAndMode(strategyId, recommendDate, weightMode));
    }

    /**
     * 读侧补充：从 stock_info 填充 industry/marketCap，并修复旧数据的 actionTag 和 buyReason
     * <p>
     * 因为生成时的 fillIndustryAndMarketCap 只在新批次生成时执行，
     * 旧批次读出来后需要同样处理才能保证前端展示正确。
     */
    public List<StockRecommendation> enrichFromStockInfo(List<StockRecommendation> recs) {
        if (recs == null || recs.isEmpty()) return recs;
        fillIndustryAndMarketCap(recs);
        for (StockRecommendation rec : recs) {
            // 修复旧数据的 actionTag（5值→3值）
            if (rec.getActionTag() != null) {
                rec.setActionTag(RecommendationMath.mapActionTag(rec.getActionTag()));
            }
            // 修复旧数据的 buyReason: 替换 [null(code)] → [name(code)]
            if (rec.getBuyReason() != null && rec.getBuyReason().contains("null(")) {
                String name = rec.getStockName() != null ? rec.getStockName() : rec.getStockCode();
                rec.setBuyReason(rec.getBuyReason().replace("null", name));
            }
            // 修复旧数据的 eventScore: 旧代码维度名写错未捕获 → 回算
            // 分析总分 = technical + capital + event + fundamental，反推即可
            if (rec.getEventScore() == null
                    && rec.getTechnicalScore() != null
                    && rec.getCapitalScore() != null
                    && rec.getFundamentalScore() != null
                    && rec.getAnalysisScore() != null) {
                int eventScore = rec.getAnalysisScore()
                        - rec.getTechnicalScore()
                        - rec.getCapitalScore()
                        - rec.getFundamentalScore();
                rec.setEventScore(Math.max(0, eventScore));
            }
            // 修复旧数据的因子权重: 旧批次 Phase 2 动态权重未实现 → 根据 regime 回填
            if (rec.getFactorWeight() == null && rec.getRegime() != null) {
                double wFactor, wAnalysis;
                switch (rec.getRegime()) {
                    case "BULL" -> {
                        wFactor = 0.6;
                        wAnalysis = 0.4;
                    }
                    case "BEAR" -> {
                        wFactor = 0.4;
                        wAnalysis = 0.6;
                    }
                    default -> {
                        wFactor = 0.5;
                        wAnalysis = 0.5;
                    }
                }
                rec.setFactorWeight(wFactor);
                rec.setAnalysisWeight(wAnalysis);
            }
        }
        return recs;
    }

    /**
     * 获取推荐命中率统计
     *
     * @param strategyId    策略ID
     * @param recommendDate 推荐日期
     * @return { total, positive, hitRate, avgReturn }
     */
    public Map<String, Object> getHitRate(Long strategyId, LocalDate recommendDate) {
        List<StockRecommendation> recs = recommendationMapper.findByStrategyAndDate(strategyId, recommendDate);
        Map<String, Object> stats = new HashMap<>();
        stats.put("strategyId", strategyId);
        stats.put("recommendDate", recommendDate.toString());
        stats.put("total", recs.size());

        if (recs.isEmpty()) return stats;

        // 用 nextDayReturn 计算命中率（至少有次日数据的）
        long positive = 0;
        long tracked = 0;
        double sumReturn = 0;

        for (StockRecommendation rec : recs) {
            if (rec.getNextDayReturn() != null) {
                tracked++;
                if (rec.getNextDayReturn() > 0) positive++;
                sumReturn += rec.getNextDayReturn();
            }
        }

        stats.put("tracked", tracked);
        stats.put("positive", positive);
        stats.put("hitRate", tracked > 0 ? (double) positive / tracked : 0);
        stats.put("avgReturn", tracked > 0 ? sumReturn / tracked : 0);

        return stats;
    }

    /**
     * 获取指定策略+日期的所有模式列表
     */
    public List<String> getModesByStrategyAndDate(Long strategyId, LocalDate recommendDate) {
        return recommendationMapper.findModesByStrategyAndDate(strategyId, recommendDate);
    }

    /**
     * 获取最近的策略+日期组合列表（含权重模式）
     */
    public List<Map<String, Object>> getStrategyDateCombos(int limit) {
        return recommendationMapper.findRecentStrategyDateModes(limit);
    }

    /**
     * 获取指定策略在最近 days 天内有推荐数据的日期列表（倒序）
     */
    public List<String> getDatesByStrategy(Long strategyId, int days) {
        List<java.time.LocalDate> dates = recommendationMapper.findDatesByStrategyId(strategyId, days);
        List<String> result = new java.util.ArrayList<>(dates.size());
        for (java.time.LocalDate d : dates) {
            // 只保留最近 days 天内的日期
            if (!d.isBefore(java.time.LocalDate.now().minusDays(days))) {
                result.add(d.toString());
            }
        }
        return result;
    }

    /**
     * 获取所有有推荐记录的策略列表（id + name）
     */
    public List<Map<String, Object>> strategiesWithData() {
        List<Long> ids = recommendationMapper.findDistinctStrategyIds();
        List<Map<String, Object>> result = new java.util.ArrayList<>(ids.size());
        for (Long sid : ids) {
            StrategyDefinition s = strategyDefinitionMapper.selectById(sid);
            // 跳过没有策略定义（多为历史残留）的策略，避免下拉框出现"策略73"这类兜底文案
            if (s == null) {
                continue;
            }
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", sid);
            m.put("strategyName", s.getStrategyName());
            result.add(m);
        }
        return result;
    }

    /**
     * 获取批次历史表现汇总（含质量标签，按策略隔离）
     * 用于前端表现追踪面板：命中趋势图 + 平均收益率统计
     *
     * @param limit      返回最近N条策略+日期组合
     * @param strategyId 可选，指定时只返回该策略的数据
     * @return [{ strategyId, recommendDate, total, hitRate, avgDayReturn, avgWeekReturn, avgMonthReturn, qualityTag, tracked }]
     */
    public List<Map<String, Object>> getBatchHistory(int limit, Long strategyId) {
        List<Map<String, Object>> rawCombos;
        if (strategyId != null) {
            // 按策略筛选：获取该策略的日期列表
            List<LocalDate> dates = recommendationMapper.findDatesByStrategyId(strategyId, limit);
            rawCombos = new ArrayList<>();
            for (LocalDate d : dates) {
                Map<String, Object> m = new HashMap<>();
                m.put("strategy_id", strategyId);
                m.put("recommend_date", java.sql.Date.valueOf(d));
                rawCombos.add(m);
            }
        } else {
            rawCombos = recommendationMapper.findRecentStrategyDates(limit);
        }

        // 先收集所有组合的 hitRate，用于滚动5期均值计算
        List<Double> hitRates = new ArrayList<>();
        List<Long> trackedCounts = new ArrayList<>();
        List<Map<String, Object>> rawEntries = new ArrayList<>();

        for (Map<String, Object> combo : rawCombos) {
            Object sidObj = combo.get("strategy_id");
            if (sidObj == null) {
                continue;
            }
            Long sid = ((Number) sidObj).longValue();
            java.sql.Date sqlDate = (java.sql.Date) combo.get("recommend_date");
            if (sqlDate == null) {
                continue;
            }
            LocalDate recDate = sqlDate.toLocalDate();

            Map<String, Object> stats = getHitRate(sid, recDate);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("strategyId", sid);
            entry.put("recommendDate", recDate.toString());
            entry.put("total", stats.get("total"));
            entry.put("tracked", stats.get("tracked"));

            Double hitRate = (Double) stats.get("hitRate");
            Double avgDayReturn = (Double) stats.get("avgReturn");
            entry.put("hitRate", hitRate);
            entry.put("avgDayReturn", avgDayReturn);

            // 计算一周/一月平均收益
            List<StockRecommendation> recs = recommendationMapper.findByStrategyAndDate(sid, recDate);
            double sumWeek = 0, sumMonth = 0;
            long weekTracked = 0, monthTracked = 0;
            for (StockRecommendation rec : recs) {
                if (rec.getNextWeekReturn() != null) {
                    sumWeek += rec.getNextWeekReturn();
                    weekTracked++;
                }
                if (rec.getNextMonthReturn() != null) {
                    sumMonth += rec.getNextMonthReturn();
                    monthTracked++;
                }
            }
            entry.put("avgWeekReturn", weekTracked > 0 ? sumWeek / weekTracked : null);
            entry.put("avgMonthReturn", monthTracked > 0 ? sumMonth / monthTracked : null);

            hitRates.add(hitRate);
            trackedCounts.add((Long) stats.get("tracked"));
            rawEntries.add(entry);
        }

        // 质量标签: 基于近5期滚动平均命中率判定
        // rawEntries 按日期 DESC 排序（最新在前）
        for (int i = 0; i < rawEntries.size(); i++) {
            Map<String, Object> entry = rawEntries.get(i);
            // 计算当前及之后4期（共5期）滚动均值
            double rollingSum = 0;
            long rollingTracked = 0;
            for (int j = i; j < rawEntries.size() && j <= i + 4; j++) {
                if (trackedCounts.get(j) > 0) {
                    rollingSum += hitRates.get(j) != null ? hitRates.get(j) : 0;
                    rollingTracked++;
                }
            }
            double rollingAvg = rollingTracked > 0 ? rollingSum / rollingTracked : 0;

            String qualityTag;
            if (rollingTracked == 0) {
                qualityTag = "UNTRAINED";
            } else if (rollingAvg >= 0.6) {
                qualityTag = "HIGH_QUALITY";
            } else if (rollingAvg >= 0.4) {
                qualityTag = "NORMAL";
            } else {
                qualityTag = "LOW_QUALITY";
            }
            entry.put("qualityTag", qualityTag);
            entry.put("rollingAvgHitRate", rollingTracked > 0 ? rollingAvg : null);
        }

        // 按日期 ASC 排序返回（图表从左到右时间递增）
        List<Map<String, Object>> result = new ArrayList<>(rawEntries);
        result.sort((a, b) -> ((String) a.get("recommendDate")).compareTo((String) b.get("recommendDate")));
        return result;
    }

    /**
     * 获取指定策略+日期的最佳/最差股票（用于推荐复盘）
     * 按次日收益率排序，分别取 top3 / bottom3
     * 含深度归因分析：行业分布对比、市值中位数对比、因子/分析得分对比
     *
     * @return { best3: [...], worst3: [...], analysis: { industryDiff, marketCapDiff, scoreDiff, failurePatterns } }
     */
    public Map<String, Object> getBatchTopBottom(Long strategyId, LocalDate recommendDate) {
        List<StockRecommendation> recs = recommendationMapper.findByStrategyAndDate(strategyId, recommendDate);
        Map<String, Object> result = new HashMap<>();

        // 只取有次日收益的
        List<StockRecommendation> tracked = recs.stream()
                .filter(r -> r.getNextDayReturn() != null)
                .toList();

        // 最佳3只（次日收益最高）
        List<StockRecommendation> best3 = tracked.stream()
                .sorted(java.util.Comparator.comparingDouble(StockRecommendation::getNextDayReturn).reversed())
                .limit(3)
                .collect(java.util.stream.Collectors.toList());

        // 最差3只（次日收益最低）
        List<StockRecommendation> worst3 = tracked.stream()
                .sorted(java.util.Comparator.comparingDouble(StockRecommendation::getNextDayReturn))
                .limit(3)
                .collect(java.util.stream.Collectors.toList());

        result.put("best3", best3);
        result.put("worst3", worst3);

        // ── 深度归因分析 ──
        Map<String, Object> analysis = new LinkedHashMap<>();

        // 1) 行业分布对比
        Map<String, Object> industryDiff = new LinkedHashMap<>();
        Map<String, Long> bestIndustries = best3.stream()
                .filter(r -> r.getIndustry() != null)
                .collect(java.util.stream.Collectors.groupingBy(StockRecommendation::getIndustry, java.util.stream.Collectors.counting()));
        Map<String, Long> worstIndustries = worst3.stream()
                .filter(r -> r.getIndustry() != null)
                .collect(java.util.stream.Collectors.groupingBy(StockRecommendation::getIndustry, java.util.stream.Collectors.counting()));
        industryDiff.put("bestIndustries", bestIndustries);
        industryDiff.put("worstIndustries", worstIndustries);
        // 找出仅在 worst 中出现的行业（可能为弱势行业）
        Set<String> worstOnlyIndustries = new java.util.LinkedHashSet<>(worstIndustries.keySet());
        worstOnlyIndustries.removeAll(bestIndustries.keySet());
        industryDiff.put("worstOnlyIndustries", worstOnlyIndustries);
        analysis.put("industryDiff", industryDiff);

        // 2) 市值中位数对比
        Map<String, Object> marketCapDiff = new LinkedHashMap<>();
        Double bestMedianCap = RecommendationMath.median(best3.stream()
                .map(StockRecommendation::getMarketCap)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toList()));
        Double worstMedianCap = RecommendationMath.median(worst3.stream()
                .map(StockRecommendation::getMarketCap)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toList()));
        marketCapDiff.put("bestMedianCap", bestMedianCap);
        marketCapDiff.put("worstMedianCap", worstMedianCap);
        marketCapDiff.put("hint", bestMedianCap != null && worstMedianCap != null
                ? (bestMedianCap > worstMedianCap ? "大盘股表现优于小盘股" : "小盘股表现优于大盘股")
                : null);
        analysis.put("marketCapDiff", marketCapDiff);

        // 3) 因子得分 vs 分析得分对比
        Map<String, Object> scoreDiff = new LinkedHashMap<>();
        double bestAvgFactor = best3.stream()
                .filter(r -> r.getFactorScore() != null)
                .mapToDouble(StockRecommendation::getFactorScore).average().orElse(0);
        double worstAvgFactor = worst3.stream()
                .filter(r -> r.getFactorScore() != null)
                .mapToDouble(StockRecommendation::getFactorScore).average().orElse(0);
        double bestAvgAnalysis = best3.stream()
                .filter(r -> r.getAnalysisScorePct() != null)
                .mapToDouble(StockRecommendation::getAnalysisScorePct).average().orElse(0);
        double worstAvgAnalysis = worst3.stream()
                .filter(r -> r.getAnalysisScorePct() != null)
                .mapToDouble(StockRecommendation::getAnalysisScorePct).average().orElse(0);
        scoreDiff.put("bestAvgFactorScore", bestAvgFactor);
        scoreDiff.put("worstAvgFactorScore", worstAvgFactor);
        scoreDiff.put("bestAvgAnalysisPct", bestAvgAnalysis);
        scoreDiff.put("worstAvgAnalysisPct", worstAvgAnalysis);
        // 分析差距来源
        double factorGap = bestAvgFactor - worstAvgFactor;
        double analysisGap = bestAvgAnalysis - worstAvgAnalysis;
        if (Math.abs(factorGap) > Math.abs(analysisGap)) {
            scoreDiff.put("dominantGap", "FACTOR");
            scoreDiff.put("hint", factorGap > 0
                    ? "因子得分差距更大，因子筛选效果显著"
                    : "因子得分差距更大，但方向反转，需检查因子有效性");
        } else {
            scoreDiff.put("dominantGap", "ANALYSIS");
            scoreDiff.put("hint", analysisGap > 0
                    ? "分析得分差距更大，深度分析筛选效果好"
                    : "分析得分差距更大，但方向反转，需检查分析模型");
        }
        analysis.put("scoreDiff", scoreDiff);

        // 4) 失败模式识别
        List<String> failurePatterns = new java.util.ArrayList<>();
        if (!worst3.isEmpty()) {
            // 检查是否集中在弱势行业
            long weakMomentumCount = worst3.stream()
                    .filter(r -> r.getIndustryMomentum() != null && r.getIndustryMomentum() < -0.3)
                    .count();
            if (weakMomentumCount > 0) {
                failurePatterns.add(String.format("弱势行业占比 %d/%d（行业动量 < -0.3），行业环境拖累明显", weakMomentumCount, worst3.size()));
            }
            // 检查是否风险评分偏高
            double worstAvgRisk = worst3.stream()
                    .filter(r -> r.getRiskScore() != null)
                    .mapToInt(StockRecommendation::getRiskScore).average().orElse(0);
            double bestAvgRisk = best3.stream()
                    .filter(r -> r.getRiskScore() != null)
                    .mapToInt(StockRecommendation::getRiskScore).average().orElse(0);
            if (worstAvgRisk > bestAvgRisk + 2) {
                failurePatterns.add(String.format("最差组平均风险评分 %.1f 显著高于最佳组 %.1f，风险控制不足", worstAvgRisk, bestAvgRisk));
            }
            // 检查是否流动性评分偏低
            double worstAvgLiquidity = worst3.stream()
                    .filter(r -> r.getLiquidityScore() != null)
                    .mapToInt(StockRecommendation::getLiquidityScore).average().orElse(0);
            double bestAvgLiquidity = best3.stream()
                    .filter(r -> r.getLiquidityScore() != null)
                    .mapToInt(StockRecommendation::getLiquidityScore).average().orElse(0);
            if (worstAvgLiquidity < bestAvgLiquidity - 2) {
                failurePatterns.add(String.format("最差组平均流动性评分 %.1f 低于最佳组 %.1f，流动性风险较高", worstAvgLiquidity, bestAvgLiquidity));
            }
            // 检查同行业集中度
            if (worstIndustries.size() == 1 && worst3.size() >= 2) {
                failurePatterns.add(String.format("最差组全部来自「%s」行业，行业集中风险极高", worstIndustries.keySet().iterator().next()));
            }
            if (failurePatterns.isEmpty()) {
                failurePatterns.add("无明显共性失败模式，可能受个股特有事件或市场随机波动影响");
            }
        }
        analysis.put("failurePatterns", failurePatterns);

        result.put("analysis", analysis);
        return result;
    }

    /**
     * 从 stock_info 批量填充 industry / marketCap（读侧补充用）
     * stockCode 格式: "600027.SH" → 去后缀查 stock_info.code = "600027"
     */
    public void fillIndustryAndMarketCap(List<StockRecommendation> recs) {
        Set<String> pureCodes = recs.stream()
                .map(r -> RecommendationMath.stripSuffix(r.getStockCode()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (pureCodes.isEmpty()) return;

        // 批量查 stock_info（IN 查询，一次性）
        List<StockInfo> infos = stockInfoMapper.selectList(
                new LambdaQueryWrapper<StockInfo>()
                        .in(StockInfo::getCode, pureCodes));

        Map<String, StockInfo> infoMap = infos.stream()
                .collect(Collectors.toMap(StockInfo::getCode, i -> i, (a, b) -> a));

        for (StockRecommendation rec : recs) {
            String pureCode = RecommendationMath.stripSuffix(rec.getStockCode());
            StockInfo info = pureCode != null ? infoMap.get(pureCode) : null;
            if (info != null) {
                rec.setIndustry(info.getIndustry());
                if (info.getTotalMarketCap() != null) {
                    rec.setMarketCap(info.getTotalMarketCap().doubleValue());
                }
            }
        }
    }
}
