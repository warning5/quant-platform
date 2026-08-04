package com.quant.platform.backtest.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.backtest.domain.BacktestTask;
import com.quant.platform.common.security.GroovySandboxConfig;
import com.quant.platform.factor.domain.FactorValue;
import com.quant.platform.factor.ic.service.FactorIcService;
import com.quant.platform.market.domain.MarketDailyBar;
import com.quant.platform.market.service.MarketDataService;
import com.quant.platform.strategy.domain.StrategyDefinition;
import groovy.lang.Binding;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 回测选股打分器。
 *
 * <p>God Class 拆分 Phase 5：承载原 {@code BacktestEngine} 中「因子配置解析 → 因子归一化 →
 * 综合打分（含 Groovy 自定义脚本）→ Top N 选股 → IC/IR 动态权重」这一条打分链路。
 * 方法体逐字搬运，行为零变化；{@code BacktestEngine} 保留同名薄委托。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BacktestScoring {

    private final MarketDataService marketDataService;
    private final ObjectMapper objectMapper;
    /** 行业/基本信息/历史因子加载（Phase 2 已拆出） */
    private final BacktestDataLoader backtestDataLoader;

    @Autowired(required = false)
    private FactorIcService factorIcService;

    /**
     * 计算综合因子得分
     * 优先使用 rank_value（预计算百分位排名），若为 NULL 则回退用 factor_val 实时做截面 z-score 归一化。
     */
    Map<String, Double> computeScores(List<MarketDailyBar> bars,
                                              List<FactorWeight> factorWeights,
                                              Map<String, Map<String, FactorValue>> factorValueMap,
                                              BacktestTask task,
                                              StrategyDefinition strategy,
                                              LocalDate rebalanceDate,
                                              Map<String, Double> dynamicFactorWeights) {
        Map<String, Double> scores = new HashMap<>();

        // 如果是自定义脚本策略，使用Groovy脚本执行
        if (strategy.getStrategyType() == StrategyDefinition.StrategyType.CUSTOM
                && strategy.getScriptCode() != null && !strategy.getScriptCode().isBlank()) {
            return computeScoresWithScript(bars, factorValueMap, task, strategy, rebalanceDate);
        }

        // 检查每个因子是否有 rank_value，如果没有则需要实时计算截面 z-score
        // key: factorCode, value: { symbol -> normalized score }
        Map<String, Map<String, Double>> normalizedMap = new HashMap<>();
        for (FactorWeight fw : factorWeights) {
            Map<String, FactorValue> fvMap = factorValueMap.get(fw.factorCode());
            if (fvMap == null || fvMap.isEmpty()) continue;

            // 判断是否有 rank_value 可用
            boolean hasRankValue = fvMap.values().stream()
                    .anyMatch(fv -> fv.getRankValue() != null);

            if (hasRankValue) {
                // 直接使用 rank_value
                Map<String, Double> scoreMap = new HashMap<>();
                for (Map.Entry<String, FactorValue> entry : fvMap.entrySet()) {
                    if (entry.getValue().getRankValue() != null) {
                        scoreMap.put(entry.getKey(), entry.getValue().getRankValue().doubleValue());
                    }
                }
                normalizedMap.put(fw.factorCode(), scoreMap);
            } else {
                // 回退：使用 factor_val 实时做截面 z-score 归一化
                Map<String, Double> scoreMap = normalizeFactorVals(fvMap);
                normalizedMap.put(fw.factorCode(), scoreMap);
            }
        }

        // 综合评分：使用动态权重（如果有）或静态权重
        for (MarketDailyBar bar : bars) {
            double score = 0;
            boolean hasAnyFactor = false;
            for (FactorWeight fw : factorWeights) {
                Map<String, Double> scoreMap = normalizedMap.get(fw.factorCode());
                if (scoreMap == null) continue;
                Double val = scoreMap.get(bar.getSymbol());
                if (val == null) continue;
                // 优先使用动态权重，回退到静态配置权重
                double effectiveWeight = (dynamicFactorWeights != null && dynamicFactorWeights.containsKey(fw.factorCode()))
                        ? dynamicFactorWeights.get(fw.factorCode())
                        : fw.weight();
                score += val * effectiveWeight;
                hasAnyFactor = true;
            }
            if (hasAnyFactor) {
                scores.put(bar.getSymbol(), score);
            }
        }
        return scores;
    }

    /**
     * 对 factor_val 做截面 z-score 归一化，返回 { symbol -> zScore }
     */
    private Map<String, Double> normalizeFactorVals(Map<String, FactorValue> fvMap) {
        // 收集有效值
        double[] raw = fvMap.values().stream()
                .filter(fv -> fv.getFactorVal() != null)
                .mapToDouble(fv -> fv.getFactorVal().doubleValue())
                .toArray();

        if (raw.length == 0) return Map.of();

        double mean = Arrays.stream(raw).average().orElse(0);
        double std = Math.sqrt(Arrays.stream(raw).map(v -> (v - mean) * (v - mean)).average().orElse(1));
        // 避免除零
        if (std < 1e-10) std = 1.0;

        Map<String, Double> result = new HashMap<>();
        for (Map.Entry<String, FactorValue> entry : fvMap.entrySet()) {
            if (entry.getValue().getFactorVal() != null) {
                double z = (entry.getValue().getFactorVal().doubleValue() - mean) / std;
                result.put(entry.getKey(), z);
            }
        }
        return result;
    }

    /**
     * 使用Groovy脚本计算股票得分
     */
    private Map<String, Double> computeScoresWithScript(List<MarketDailyBar> bars,
                                                        Map<String, Map<String, FactorValue>> factorValueMap,
                                                        BacktestTask task,
                                                        StrategyDefinition strategy,
                                                        LocalDate rebalanceDate) {
        Map<String, Double> scores = new HashMap<>();

        try {
            Binding binding = new Binding();
            binding.setVariable("marketBars", bars);
            binding.setVariable("factorValues", factorValueMap);
            binding.setVariable("rebalanceDate", rebalanceDate.toString());
            // 优先用任务级持仓数，没有则用策略定义，都没有则默认20
            int maxPositions = task.getMaxPositionCount() != null
                    ? task.getMaxPositionCount()
                    : (strategy.getMaxPositionCount() != null ? strategy.getMaxPositionCount() : 20);
            binding.setVariable("maxPositions", maxPositions);

            // ── 新增绑定变量（供策略脚本使用）──
            // indexBars: 沪深300指数K线（供RSRS择时等策略使用）
            List<MarketDailyBar> indexBars = marketDataService.getBarsInRange(
                    "000300.SH", rebalanceDate.minusDays(1200), rebalanceDate);
            binding.setVariable("indexBars", indexBars);

            // industryMap: 股票代码 → 行业名称（从 stock_info）
            Map<String, String> industryMap = loadIndustryMap(bars);
            binding.setVariable("industryMap", industryMap);

            // stockInfoMap: 股票代码 → 上市日期等信息
            Map<String, Map<String, Object>> stockInfoMap = loadStockInfoMap(bars);
            binding.setVariable("stockInfoMap", stockInfoMap);

            // historicalFactors: 多期因子历史值（用于RSRS等需要序列的策略）
            // 格式: { factorCode -> { symbol -> [FactorValue...] } }
            Map<String, Map<String, List<FactorValue>>> historicalFactors = loadHistoricalFactors(
                    factorValueMap.keySet(), rebalanceDate, 120);
            binding.setVariable("historicalFactors", historicalFactors);

            // 安全预检 + 带超时执行（统一由 GroovySandboxConfig 提供双重防护 + 超时保护）
            Object result = GroovySandboxConfig.evaluateScriptWithTimeout(
                    binding, strategy.getScriptCode(), GroovySandboxConfig.BACKTEST_TIMEOUT_SECONDS);

            if (result instanceof Map<?, ?> resultMap) {
                for (Map.Entry<?, ?> entry : resultMap.entrySet()) {
                    if (entry.getKey() instanceof String symbol && entry.getValue() instanceof Number weight) {
                        scores.put(symbol, weight.doubleValue());
                    }
                }
            }

            log.debug("Script strategy [{}] computed scores for {} stocks", strategy.getStrategyCode(), scores.size());
        } catch (Exception e) {
            log.error("Failed to execute script strategy [{}]: {}", strategy.getStrategyCode(), e.getMessage(), e);
        }

        return scores;
    }

    /**
     * 选取Top N股票，等权
     */
    Map<String, Double> selectTopStocks(Map<String, Double> scores, int topN) {
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topN)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> 1.0 / Math.min(topN, scores.size())
                ));
    }

    /**
     * 加载候选股票的行业映射（code → industry）
     * 从 stock_info 表批量查询
     */
    private Map<String, String> loadIndustryMap(List<MarketDailyBar> bars) {
        return backtestDataLoader.loadIndustryMap(bars);
    }

    /**
     * 加载候选股票的基本信息映射（code → {listDate, totalShare, name}）
     * <p>实现已迁移至 {@link BacktestDataLoader#loadStockInfoMap}。</p>
     */
    private Map<String, Map<String, Object>> loadStockInfoMap(List<MarketDailyBar> bars) {
        return backtestDataLoader.loadStockInfoMap(bars);
    }

    /**
     * 加载指定因子在最近 N 天内的历史值
     * 格式: { factorCode -> { symbol -> [FactorValue...] } }
     * <p>实现已迁移至 {@link BacktestDataLoader#loadHistoricalFactors}。</p>
     */
    private Map<String, Map<String, List<FactorValue>>> loadHistoricalFactors(
            Set<String> factorCodes, LocalDate endDate, int lookbackDays) {
        return backtestDataLoader.loadHistoricalFactors(factorCodes, endDate, lookbackDays);
    }

    List<FactorWeight> parseFactorConfig(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            var node = objectMapper.readTree(json);
            var factorsNode = node.get("factors");
            if (factorsNode == null || !factorsNode.isArray()) return List.of();
            List<FactorWeight> result = new ArrayList<>();
            for (var fn : factorsNode) {
                result.add(new FactorWeight(
                        fn.get("code").asText(),
                        fn.get("weight").asDouble()
                ));
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 基于近期IC/IR计算动态因子权重（与StockScreenService.getDynamicWeights逻辑对齐）
     *
     * @param factorWeights 因子列表（含静态配置权重）
     * @param weightMode    权重模式：IC / IR
     * @param rebalanceDate 调仓日期
     * @return factorCode -> 动态权重系数（已与静态权重乘算）
     */
    Map<String, Double> computeDynamicFactorWeights(List<FactorWeight> factorWeights,
                                                            String weightMode,
                                                            LocalDate rebalanceDate) {
        Map<String, Double> dynamicWeights = new LinkedHashMap<>();
        Map<String, Double> icScores = new LinkedHashMap<>();

        // 1. 获取每个因子的IC/IR值
        for (FactorWeight fw : factorWeights) {
            String fc = fw.factorCode();
            try {
                List<Double> icValues = factorIcService.getIcHistory(fc, rebalanceDate, 60);
                if (icValues == null || icValues.isEmpty()) {
                    // 优化X：无IC历史时回退到配置权重(fw.weight)，使配置权重有话语权
                    double cfgW = (fw.weight() > 0) ? fw.weight() : 0.05;
                    log.debug("[BacktestEngine DynamicWeight] 因子 {} 在 {} 无IC历史数据，回退到配置权重{}", fc, rebalanceDate, cfgW);
                    icScores.put(fc, cfgW);
                    continue;
                }

                double score;
                if ("IR".equalsIgnoreCase(weightMode)) {
                    double avg = icValues.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                    double std = Math.sqrt(icValues.stream().mapToDouble(v -> Math.pow(v - avg, 2)).average().orElse(0));
                    score = std > 0 ? Math.abs(avg) / std : 0;
                } else {
                    score = icValues.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                }
                icScores.put(fc, score);
            } catch (Exception e) {
                log.debug("[BacktestEngine DynamicWeight] 获取因子 {} IC失败: {}", fc, e.getMessage());
                icScores.put(fc, 1.0);
            }
        }

        // 2. 计算IC>0的因子IC之和
        double sumPositiveIc = icScores.values().stream()
                .filter(v -> v > 0)
                .mapToDouble(Double::doubleValue)
                .sum();

        if (sumPositiveIc > 0) {
            for (FactorWeight fw : factorWeights) {
                String fc = fw.factorCode();
                double ic = icScores.getOrDefault(fc, 1.0);
                if (ic > 0) {
                    double normalized = ic / sumPositiveIc;
                    normalized = Math.max(0.1, Math.min(5.0, normalized));
                    dynamicWeights.put(fc, normalized * fw.weight());
                } else {
                    dynamicWeights.put(fc, 0.0);
                }
            }
        } else {
            // 所有IC均<=0，回退到静态权重
            log.debug("[BacktestEngine DynamicWeight] {} 所有因子IC均<=0，回退静态权重", rebalanceDate);
            for (FactorWeight fw : factorWeights) {
                dynamicWeights.put(fw.factorCode(), fw.weight());
            }
        }

        return dynamicWeights;
    }
}
