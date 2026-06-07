package com.quant.platform.backtest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.backtest.domain.WalkForwardResult;
import com.quant.platform.factor.domain.FactorValue;
import com.quant.platform.factor.ic.service.FactorIcService;
import com.quant.platform.factor.service.ClickHouseFactorValueService;
import com.quant.platform.market.domain.MarketDailyBar;
import com.quant.platform.market.service.MarketDataService;
import com.quant.platform.screen.dto.ScreenRequest;
import com.quant.platform.screen.dto.ScreenResult;
import com.quant.platform.screen.service.StockScreenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Walk-Forward 验证服务 (P2-3)
 *
 * 滚动窗口验证策略有效性：
 * 1. 训练期：计算因子IC，确定因子权重
 * 2. 验证期：用训练期权重选股，计算实际收益
 * 3. 滚动窗口：向前移动，重复
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalkForwardService {

    private final StockScreenService stockScreenService;
    private final ClickHouseFactorValueService clickHouseFactorValueService;
    private final FactorIcService factorIcService;
    private final MarketDataService marketDataService;
    private final ObjectMapper objectMapper;

    /** 沪深300代码（基准） */
    private static final String BENCHMARK_CODE = "000300";

    /**
     * 执行Walk-Forward验证
     *
     * @param factors 因子配置列表
     * @param endDate 结束日期
     * @param trainDays 训练期天数（默认60）
     * @param validateDays 验证期天数（默认20）
     * @param stepDays 滚动步长（默认10）
     * @param maxRounds 最大轮次（默认0=不限）
     * @return 各轮验证结果
     */
    public List<WalkForwardResult> runWalkForward(
            List<ScreenRequest.FactorWeight> factors,
            LocalDate endDate,
            int trainDays,
            int validateDays,
            int stepDays,
            int maxRounds) {

        if (trainDays <= 0) trainDays = 60;
        if (validateDays <= 0) validateDays = 20;
        if (stepDays <= 0) stepDays = 10;

        List<WalkForwardResult> results = new ArrayList<>();
        LocalDate currentEnd = endDate;

        int round = 0;
        while (currentEnd.minusDays(trainDays + validateDays).isAfter(endDate.minusDays(500))) {
            if (maxRounds > 0 && round >= maxRounds) break;

            LocalDate validateStart = currentEnd.minusDays(validateDays - 1);
            LocalDate trainEnd = validateStart.minusDays(1);
            LocalDate trainStart = trainEnd.minusDays(trainDays - 1);

            log.info("[WalkForward] 轮次{}: 训练[{}~{}] 验证[{}~{}]",
                    round + 1, trainStart, trainEnd, validateStart, currentEnd);

            WalkForwardResult wfResult = runSingleRound(factors, trainStart, trainEnd, validateStart, currentEnd, round + 1, trainDays, validateDays);
            results.add(wfResult);

            log.info("[WalkForward] 轮次{}完成: trainIC={:.4f} validateIC={:.4f} return={:.2f}% excess={:.2f}%",
                    round + 1,
                    wfResult.getTrainIcMean() != null ? wfResult.getTrainIcMean() : 0,
                    wfResult.getValidateIcMean() != null ? wfResult.getValidateIcMean() : 0,
                    wfResult.getValidateReturn() != null ? wfResult.getValidateReturn() : 0,
                    wfResult.getExcessReturn() != null ? wfResult.getExcessReturn() : 0);

            // 向前滚动
            currentEnd = currentEnd.minusDays(stepDays);
            round++;
        }

        log.info("[WalkForward] 全部完成: {}轮", results.size());
        return results;
    }

    /**
     * 执行单轮Walk-Forward验证
     */
    private WalkForwardResult runSingleRound(
            List<ScreenRequest.FactorWeight> factors,
            LocalDate trainStart, LocalDate trainEnd,
            LocalDate validateStart, LocalDate validateEnd,
            int round, int trainDays, int validateDays) {

        WalkForwardResult.WalkForwardResultBuilder builder = WalkForwardResult.builder()
                .round(round)
                .trainStart(trainStart)
                .trainEnd(trainEnd)
                .validateStart(validateStart)
                .validateEnd(validateEnd);

        try {
            // ── 阶段1: 训练期 - 计算各因子IC ──
            Map<String, Double> factorTrainIc = new LinkedHashMap<>();
            Map<String, Double> factorWeights = new LinkedHashMap<>();
            double totalWeight = 0;

            for (ScreenRequest.FactorWeight fw : factors) {
                String fc = fw.getFactorCode();
                try {
                    List<Double> icValues = factorIcService.getIcHistory(fc, trainEnd, trainDays);
                    if (icValues != null && !icValues.isEmpty()) {
                        double avgIc = icValues.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                        factorTrainIc.put(fc, avgIc);

                        // 根据IC调整权重
                        double weight = Math.abs(avgIc);
                        factorWeights.put(fc, weight);
                        totalWeight += weight;
                    }
                } catch (Exception e) {
                    log.debug("[WalkForward] 因子{}训练期IC计算失败: {}", fc, e.getMessage());
                }
            }

            // 归一化权重
            Map<String, Double> normalizedWeights = new LinkedHashMap<>();
            if (totalWeight > 0) {
                for (Map.Entry<String, Double> entry : factorWeights.entrySet()) {
                    normalizedWeights.put(entry.getKey(), entry.getValue() / totalWeight);
                }
            }

            double trainIcMean = factorTrainIc.values().stream()
                    .mapToDouble(Math::abs).average().orElse(0);
            builder.trainIcMean(trainIcMean);

            try {
                builder.factorIcJson(objectMapper.writeValueAsString(factorTrainIc));
            } catch (Exception e) {
                log.debug("[WalkForward] IC JSON序列化失败");
            }

            // ── 阶段2: 验证期 - 用训练期权重选股 ──
            // 构建选股请求，使用训练期确定的权重
            List<ScreenRequest.FactorWeight> validateFactors = new ArrayList<>();
            for (ScreenRequest.FactorWeight fw : factors) {
                ScreenRequest.FactorWeight vfw = new ScreenRequest.FactorWeight();
                vfw.setFactorCode(fw.getFactorCode());
                vfw.setDirection(fw.getDirection());
                vfw.setWeight(normalizedWeights.getOrDefault(fw.getFactorCode(), 1.0));
                validateFactors.add(vfw);
            }

            ScreenRequest req = new ScreenRequest();
            req.setScreenDate(validateStart);
            req.setFactors(validateFactors);
            req.setTopN(20);
            req.setDirection("LONG");
            req.setExcludeSt(true);
            req.setGlobalOutlierMethod("MAD");
            req.setGlobalNormalizeMethod("ZSCORE");
            req.setWeightMode("EQUAL"); // 已经手动调整了权重

            ScreenResult screenResult = stockScreenService.screen(req);
            builder.stockCount(screenResult.getStocks() != null ? screenResult.getStocks().size() : 0);

            // ── 阶段3: 计算验证期收益 ──
            if (screenResult.getStocks() != null && !screenResult.getStocks().isEmpty()) {
                List<String> selectedCodes = screenResult.getStocks().stream()
                        .map(ScreenResult.StockScore::getSymbol)
                        .collect(Collectors.toList());

                // 计算组合收益
                double portfolioReturn = calcPortfolioReturn(selectedCodes, validateStart, validateEnd);
                builder.validateReturn(portfolioReturn);

                // 计算基准收益
                double benchmarkReturn = calcBenchmarkReturn(validateStart, validateEnd);
                builder.benchmarkReturn(benchmarkReturn);
                builder.excessReturn(portfolioReturn - benchmarkReturn);

                // 计算最大回撤
                double maxDD = calcMaxDrawdown(selectedCodes, validateStart, validateEnd);
                builder.maxDrawdown(maxDD);
            }

            // ── 阶段4: 验证期IC ──
            Map<String, Double> factorValidateIc = new LinkedHashMap<>();
            for (ScreenRequest.FactorWeight fw : factors) {
                String fc = fw.getFactorCode();
                try {
                    List<Double> icValues = factorIcService.getIcHistory(fc, validateEnd, validateDays);
                    if (icValues != null && !icValues.isEmpty()) {
                        double avgIc = icValues.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                        factorValidateIc.put(fc, avgIc);
                    }
                } catch (Exception e) {
                    log.debug("[WalkForward] 因子{}验证期IC计算失败: {}", fc, e.getMessage());
                }
            }

            double validateIcMean = factorValidateIc.values().stream()
                    .mapToDouble(Math::abs).average().orElse(0);
            builder.validateIcMean(validateIcMean);

            try {
                builder.factorValidateIcJson(objectMapper.writeValueAsString(factorValidateIc));
            } catch (Exception e) {
                log.debug("[WalkForward] 验证IC JSON序列化失败");
            }

        } catch (Exception e) {
            log.error("[WalkForward] 轮次{}执行异常: {}", round, e.getMessage());
        }

        return builder.build();
    }

    /**
     * 计算组合在验证期的收益率
     */
    private double calcPortfolioReturn(List<String> codes, LocalDate startDate, LocalDate endDate) {
        if (codes.isEmpty()) return 0;
        double totalReturn = 0;
        int count = 0;
        for (String code : codes) {
            try {
                List<MarketDailyBar> bars = marketDataService.getBarsInRange(code, startDate, endDate);
                if (bars != null && bars.size() >= 2) {
                    double startPrice = bars.get(0).getClose().doubleValue();
                    double endPrice = bars.get(bars.size() - 1).getClose().doubleValue();
                    if (startPrice > 0) {
                        totalReturn += (endPrice - startPrice) / startPrice * 100;
                        count++;
                    }
                }
            } catch (Exception e) {
                // 跳过无法获取数据的股票
            }
        }
        return count > 0 ? totalReturn / count : 0;
    }

    /**
     * 计算基准（沪深300）在验证期的收益率
     */
    private double calcBenchmarkReturn(LocalDate startDate, LocalDate endDate) {
        try {
            List<MarketDailyBar> bars = marketDataService.getBarsInRange(BENCHMARK_CODE, startDate, endDate);
            if (bars != null && bars.size() >= 2) {
                double startPrice = bars.get(0).getClose().doubleValue();
                double endPrice = bars.get(bars.size() - 1).getClose().doubleValue();
                if (startPrice > 0) {
                    return (endPrice - startPrice) / startPrice * 100;
                }
            }
        } catch (Exception e) {
            log.debug("[WalkForward] 基准收益计算失败: {}", e.getMessage());
        }
        return 0;
    }

    /**
     * 计算组合在验证期的最大回撤
     */
    private double calcMaxDrawdown(List<String> codes, LocalDate startDate, LocalDate endDate) {
        if (codes.isEmpty()) return 0;
        // 简化：用等权组合的每日收益计算最大回撤
        try {
            int days = (int) java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) + 1;
            double peak = 0;
            double maxDD = 0;
            double cumulativeReturn = 0;

            for (int d = 0; d < days; d++) {
                LocalDate day = startDate.plusDays(d);
                double dailyReturn = 0;
                int count = 0;
                for (String code : codes) {
                    try {
                        List<MarketDailyBar> bars = marketDataService.getBarsInRange(code, day.minusDays(1), day);
                        if (bars != null && bars.size() >= 2) {
                            double prev = bars.get(0).getClose().doubleValue();
                            double curr = bars.get(bars.size() - 1).getClose().doubleValue();
                            if (prev > 0) {
                                dailyReturn += (curr - prev) / prev;
                                count++;
                            }
                        }
                    } catch (Exception e) {
                        // skip
                    }
                }
                if (count > 0) {
                    cumulativeReturn += dailyReturn / count;
                }
                if (cumulativeReturn > peak) peak = cumulativeReturn;
                double dd = peak - cumulativeReturn;
                if (dd > maxDD) maxDD = dd;
            }
            return maxDD * 100;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 汇总Walk-Forward结果
     */
    public Map<String, Object> summarize(List<WalkForwardResult> results) {
        if (results == null || results.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalRounds", results.size());

        // 平均验证期收益
        double avgReturn = results.stream()
                .filter(r -> r.getValidateReturn() != null)
                .mapToDouble(WalkForwardResult::getValidateReturn)
                .average().orElse(0);
        summary.put("avgValidateReturn", Math.round(avgReturn * 100.0) / 100.0);

        // 平均超额收益
        double avgExcess = results.stream()
                .filter(r -> r.getExcessReturn() != null)
                .mapToDouble(WalkForwardResult::getExcessReturn)
                .average().orElse(0);
        summary.put("avgExcessReturn", Math.round(avgExcess * 100.0) / 100.0);

        // 胜率
        long winCount = results.stream()
                .filter(r -> r.getExcessReturn() != null && r.getExcessReturn() > 0)
                .count();
        summary.put("winRate", Math.round(winCount * 100.0 / results.size() * 100.0) / 100.0);

        // 最大回撤
        double maxDD = results.stream()
                .filter(r -> r.getMaxDrawdown() != null)
                .mapToDouble(WalkForwardResult::getMaxDrawdown)
                .max().orElse(0);
        summary.put("maxDrawdown", Math.round(maxDD * 100.0) / 100.0);

        // IC衰减：训练期IC vs 验证期IC
        double avgTrainIc = results.stream()
                .filter(r -> r.getTrainIcMean() != null)
                .mapToDouble(WalkForwardResult::getTrainIcMean)
                .average().orElse(0);
        double avgValidateIc = results.stream()
                .filter(r -> r.getValidateIcMean() != null)
                .mapToDouble(WalkForwardResult::getValidateIcMean)
                .average().orElse(0);
        summary.put("avgTrainIc", Math.round(avgTrainIc * 10000.0) / 10000.0);
        summary.put("avgValidateIc", Math.round(avgValidateIc * 10000.0) / 10000.0);
        summary.put("icDecay", avgTrainIc > 0 ? Math.round((1 - avgValidateIc / avgTrainIc) * 10000.0) / 10000.0 : 0);

        return summary;
    }
}
