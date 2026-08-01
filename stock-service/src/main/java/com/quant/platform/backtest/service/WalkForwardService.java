package com.quant.platform.backtest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.backtest.domain.WalkForwardResult;
import com.quant.platform.factor.ic.service.FactorIcService;
import com.quant.platform.market.domain.MarketDailyBar;
import com.quant.platform.market.service.MarketDataService;
import com.quant.platform.screen.dto.ScreenRequest;
import com.quant.platform.screen.dto.ScreenResult;
import com.quant.platform.screen.service.StockScreenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Walk-Forward 验证服务
 * <p>
 * 滚动窗口验证策略有效性：
 * 1. 训练期：计算因子IC，确定因子权重
 * 2. 验证期：用训练期权重选股，计算实际收益
 * 3. 滚动窗口：向前移动，重复
 * 4. 汇总：IC半衰期 + 策略失效预警 + 交易成本
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalkForwardService {

    /**
     * 沪深300代码（基准）
     */
    private static final String BENCHMARK_CODE = "000300";
    /**
     * 默认单边交易成本（佣金+印花税+滑点，约0.15%）
     */
    private static final double DEFAULT_TRANSACTION_COST = 0.0015;
    /**
     * 策略失效预警：连续负超额收益轮次数
     */
    private static final int WARNING_CONSECUTIVE_NEGATIVE = 3;
    private final StockScreenService stockScreenService;
    private final MarketDataService marketDataService;
    private final FactorIcService factorIcService;
    private final ObjectMapper objectMapper;

    /**
     * 执行Walk-Forward验证
     *
     * @param factors           因子配置列表
     * @param startDate         分析起始日期（训练期最早日期）
     * @param endDate           分析结束日期（验证期最晚日期）
     * @param trainDays         训练期天数（默认60）
     * @param validateDays      验证期天数（默认20）
     * @param stepDays          滚动步长（默认10）
     * @param maxRounds         最大轮次（默认0=不限，由startDate控制）
     * @param transactionCost   单边交易成本比例（默认0.0015）
     * @param rebalanceInterval 验证期内调仓间隔天数（默认=validateDays，即不调仓）
     * @return 各轮验证结果
     */
    public List<WalkForwardResult> runWalkForward(
            List<ScreenRequest.FactorWeight> factors,
            LocalDate startDate,
            LocalDate endDate,
            int trainDays,
            int validateDays,
            int stepDays,
            int maxRounds,
            Double transactionCost,
            Integer rebalanceInterval) {

        if (trainDays <= 0) trainDays = 60;
        if (validateDays <= 0) validateDays = 20;
        if (stepDays <= 0) stepDays = 10;
        if (transactionCost == null || transactionCost < 0) transactionCost = DEFAULT_TRANSACTION_COST;
        if (rebalanceInterval == null || rebalanceInterval <= 0) rebalanceInterval = validateDays;

        // startDate 兜底：若未提供，默认回溯3年
        LocalDate effectiveStartDate = (startDate != null) ? startDate : endDate.minusYears(3);

        List<WalkForwardResult> results = new ArrayList<>();
        LocalDate currentEnd = endDate;
        int round = 0;

        while (!currentEnd.minusDays(trainDays + validateDays).isBefore(effectiveStartDate)) {
            if (maxRounds > 0 && round >= maxRounds) break;

            LocalDate validateStart = currentEnd.minusDays(validateDays - 1);
            LocalDate trainEnd = validateStart.minusDays(1);
            LocalDate trainStart = trainEnd.minusDays(trainDays - 1);

            log.info("[WalkForward] 轮次{}: 训练[{}~{}] 验证[{}~{}]",
                    round + 1, trainStart, trainEnd, validateStart, currentEnd);

            WalkForwardResult wfResult = runSingleRound(
                    factors, trainStart, trainEnd, validateStart, currentEnd,
                    round + 1, trainDays, validateDays,
                    transactionCost, rebalanceInterval);
            results.add(wfResult);

            log.info("[WalkForward] 轮次{}完成: trainIC={} validateIC={} return={}% excess={}%",
                    round + 1,
                    wfResult.getTrainIcMean() != null ? wfResult.getTrainIcMean() : 0,
                    wfResult.getValidateIcMean() != null ? wfResult.getValidateIcMean() : 0,
                    wfResult.getValidateReturn() != null ? wfResult.getValidateReturn() : 0,
                    wfResult.getExcessReturn() != null ? wfResult.getExcessReturn() : 0);

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
            int round, int trainDays, int validateDays,
            double transactionCost,
            int rebalanceInterval) {

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

            // ── 阶段2: 验证期 - 选股（支持期内调仓） ──
            List<String> allSelectedCodes = new ArrayList<>();

            if (rebalanceInterval >= validateDays) {
                // 不调仓：验证期第一天选一次
                List<String> codes = screenStocks(factors, normalizedWeights, validateStart);
                if (codes != null && !codes.isEmpty()) {
                    allSelectedCodes.addAll(codes);
                    // 计算收益（含交易成本）
                    double portfolioReturn = calcPortfolioReturn(codes, validateStart, validateEnd, transactionCost, false);
                    builder.validateReturn(portfolioReturn);
                    double benchmarkReturn = calcBenchmarkReturn(validateStart, validateEnd);
                    builder.benchmarkReturn(benchmarkReturn);
                    builder.excessReturn(portfolioReturn - benchmarkReturn);
                    builder.stockCount(codes.size());
                    // 计算最大回撤（批量预加载K线）
                    double maxDD = calcMaxDrawdown(codes, validateStart, validateEnd);
                    builder.maxDrawdown(maxDD);
                }
            } else {
                // 期内调仓：每 rebalanceInterval 天重新选股
                List<Double> roundReturns = new ArrayList<>();
                LocalDate segStart = validateStart;
                while (!segStart.isAfter(validateEnd)) {
                    LocalDate segEnd = segStart.plusDays(rebalanceInterval - 1);
                    if (segEnd.isAfter(validateEnd)) segEnd = validateEnd;
                    List<String> codes = screenStocks(factors, normalizedWeights, segStart);
                    if (codes != null && !codes.isEmpty()) {
                        double segReturn = calcPortfolioReturn(codes, segStart, segEnd, transactionCost, true);
                        roundReturns.add(segReturn);
                        for (String c : codes) {
                            if (!allSelectedCodes.contains(c)) allSelectedCodes.add(c);
                        }
                    }
                    segStart = segStart.plusDays(rebalanceInterval);
                }
                double totalReturn = roundReturns.stream().mapToDouble(Double::doubleValue).sum();
                builder.validateReturn(totalReturn);
                double benchmarkReturn = calcBenchmarkReturn(validateStart, validateEnd);
                builder.benchmarkReturn(benchmarkReturn);
                builder.excessReturn(totalReturn - benchmarkReturn);
                builder.stockCount(allSelectedCodes.size());
                // 调仓时回撤计算较复杂，用全过程近似
                if (!allSelectedCodes.isEmpty()) {
                    double maxDD = calcMaxDrawdown(allSelectedCodes, validateStart, validateEnd);
                    builder.maxDrawdown(maxDD);
                }
            }

            // ── 阶段3: 验证期IC ──
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
            log.error("[WalkForward] 轮次{}执行异常: {}", round, e.getMessage(), e);
        }

        return builder.build();
    }

    /**
     * 用指定权重和日期选股
     */
    private List<String> screenStocks(
            List<ScreenRequest.FactorWeight> factors,
            Map<String, Double> normalizedWeights,
            LocalDate screenDate) {
        List<ScreenRequest.FactorWeight> validateFactors = new ArrayList<>();
        for (ScreenRequest.FactorWeight fw : factors) {
            ScreenRequest.FactorWeight vfw = new ScreenRequest.FactorWeight();
            vfw.setFactorCode(fw.getFactorCode());
            vfw.setDirection(fw.getDirection());
            vfw.setWeight(normalizedWeights.getOrDefault(fw.getFactorCode(), 1.0));
            validateFactors.add(vfw);
        }
        ScreenRequest req = new ScreenRequest();
        req.setScreenDate(screenDate);
        req.setFactors(validateFactors);
        req.setTopN(20);
        req.setDirection("LONG");
        req.setExcludeSt(true);
        req.setGlobalOutlierMethod("MAD");
        req.setGlobalNormalizeMethod("ZSCORE");
        req.setWeightMode("EQUAL");

        ScreenResult screenResult = stockScreenService.screen(req);
        if (screenResult.getStocks() != null && !screenResult.getStocks().isEmpty()) {
            return screenResult.getStocks().stream()
                    .map(ScreenResult.StockScore::getSymbol)
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    /**
     * 计算组合在验证期的收益率（扣除交易成本）
     *
     * @param isPartial 是否部分持仓（调仓场景，成本只算一次）
     */
    private double calcPortfolioReturn(List<String> codes, LocalDate startDate, LocalDate endDate,
                                       double transactionCost, boolean isPartial) {
        if (codes.isEmpty()) return 0;
        double totalReturn = 0;
        int count = 0;
        for (String code : codes) {
            try {
                List<MarketDailyBar> bars = marketDataService.getBarsInRange(code, startDate, endDate);
                if (bars != null && bars.size() >= 2) {
                    bars.sort(Comparator.comparing(MarketDailyBar::getTradeDate));
                    double startPrice = bars.getFirst().getClose().doubleValue();
                    double endPrice = bars.getLast().getClose().doubleValue();
                    if (startPrice > 0) {
                        totalReturn += (endPrice - startPrice) / startPrice * 100;
                        count++;
                    }
                }
            } catch (Exception e) {
                // 跳过无法获取数据的股票
            }
        }
        double grossReturn = count > 0 ? totalReturn / count : 0;
        // 扣除交易成本：买入 + 卖出（非部分持仓时扣双边）
        double cost = isPartial ? transactionCost * 100 : transactionCost * 2 * 100;
        return grossReturn - cost;
    }

    /**
     * 计算基准（沪深300）在验证期的收益率
     */
    private double calcBenchmarkReturn(LocalDate startDate, LocalDate endDate) {
        try {
            List<MarketDailyBar> bars = marketDataService.getBarsInRange(BENCHMARK_CODE, startDate, endDate);
            if (bars != null && bars.size() >= 2) {
                bars.sort(Comparator.comparing(MarketDailyBar::getTradeDate));
                double startPrice = bars.getFirst().getClose().doubleValue();
                double endPrice = bars.getLast().getClose().doubleValue();
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
     * 计算组合在验证期的最大回撤（批量预加载K线，避免N次DB查询）
     */
    private double calcMaxDrawdown(List<String> codes, LocalDate startDate, LocalDate endDate) {
        if (codes.isEmpty()) return 0;
        try {
            // 批量预加载所有股票的K线数据
            Map<String, SortedMap<LocalDate, BigDecimal>> priceMap = new HashMap<>();
            for (String code : codes) {
                try {
                    List<MarketDailyBar> bars = marketDataService.getBarsInRange(code, startDate, endDate);
                    if (bars != null && !bars.isEmpty()) {
                        SortedMap<LocalDate, BigDecimal> datePrice = new TreeMap<>();
                        for (MarketDailyBar bar : bars) {
                            datePrice.put(bar.getTradeDate(), bar.getClose());
                        }
                        priceMap.put(code, datePrice);
                    }
                } catch (Exception e) {
                    log.debug("[WalkForward] 加载{}K线失败: {}", code, e.getMessage());
                }
            }

            if (priceMap.isEmpty()) return 0;

            // 找出所有股票的共同交易日序列
            List<LocalDate> tradingDays = findCommonTradingDays(priceMap);

            double peak = 0;
            double maxDD = 0;
            double cumulativeReturn = 0;

            for (int d = 1; d < tradingDays.size(); d++) {
                LocalDate prevDay = tradingDays.get(d - 1);
                LocalDate currDay = tradingDays.get(d);
                double dailyReturn = 0;
                int count = 0;
                for (Map.Entry<String, SortedMap<LocalDate, BigDecimal>> entry : priceMap.entrySet()) {
                    SortedMap<LocalDate, BigDecimal> datePrice = entry.getValue();
                    BigDecimal prev = datePrice.get(prevDay);
                    BigDecimal curr = datePrice.get(currDay);
                    if (prev != null && curr != null && prev.doubleValue() > 0) {
                        dailyReturn += (curr.doubleValue() - prev.doubleValue()) / prev.doubleValue();
                        count++;
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
            log.warn("[WalkForward] 最大回撤计算异常: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 找出所有股票的共同交易日（按日期排序）
     */
    private List<LocalDate> findCommonTradingDays(Map<String, SortedMap<LocalDate, BigDecimal>> priceMap) {
        if (priceMap.isEmpty()) return Collections.emptyList();
        Iterator<Map.Entry<String, SortedMap<LocalDate, BigDecimal>>> it = priceMap.entrySet().iterator();
        Set<LocalDate> commonDays = new TreeSet<>(it.next().getValue().keySet());
        while (it.hasNext()) {
            commonDays.retainAll(it.next().getValue().keySet());
        }
        return new ArrayList<>(commonDays);
    }

    /**
     * 汇总Walk-Forward结果（含IC半衰期 + 策略失效预警）
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

        // 最大回撤（各轮最大值）
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
        summary.put("icDecay", avgTrainIc > 0
                ? Math.round((1 - avgValidateIc / avgTrainIc) * 10000.0) / 10000.0 : 0);

        // ── IC半衰期分析 ──
        double icHalfLifeRounds = computeIcHalfLife(results);
        summary.put("icHalfLifeRounds", icHalfLifeRounds > 0 ? Math.round(icHalfLifeRounds * 100.0) / 100.0 : null);
        summary.put("icHalfLifeDays", icHalfLifeRounds > 0
                ? Math.round(icHalfLifeRounds * 10 * 100.0) / 100.0 : null); // 默认stepDays=10

        // ── 策略失效预警 ──
        List<String> warnings = new ArrayList<>();
        int consecutiveNegative = 0;
        for (WalkForwardResult r : results) {
            if (r.getExcessReturn() != null && r.getExcessReturn() < 0) {
                consecutiveNegative++;
                if (consecutiveNegative >= WARNING_CONSECUTIVE_NEGATIVE) {
                    warnings.add(String.format("连续%d轮负超额收益（第%d~%d轮）",
                            consecutiveNegative, r.getRound() - consecutiveNegative + 1, r.getRound()));
                }
            } else {
                consecutiveNegative = 0;
            }
        }
        // IC衰减超过50%预警
        if (avgTrainIc > 0 && avgValidateIc > 0) {
            double decay = (avgTrainIc - avgValidateIc) / avgTrainIc;
            if (decay > 0.5) {
                warnings.add(String.format("IC衰减严重：训练期IC=%.4f，验证期IC=%.4f，衰减%.1f%%",
                        avgTrainIc, avgValidateIc, decay * 100));
            }
        }
        if (!warnings.isEmpty()) {
            summary.put("warnings", warnings);
        }

        return summary;
    }

    /**
     * 计算IC半衰期（指数衰减拟合）
     * 返回：半衰期（轮次数）
     * 算法：对各轮的trainIcMean做指数衰减拟合 IC(t) = IC0 * e^(-k*t)，半衰期 = ln(2)/k
     */
    private double computeIcHalfLife(List<WalkForwardResult> results) {
        // 收集有效数据：轮次序号 + trainIcMean
        List<double[]> data = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            WalkForwardResult r = results.get(i);
            if (r.getTrainIcMean() != null && r.getTrainIcMean() > 0) {
                data.add(new double[]{i, r.getTrainIcMean()});
            }
        }
        if (data.size() < 3) return -1; // 数据不足，无法拟合

        try {
            // 线性化：ln(IC) = ln(IC0) - k*t
            // 用简单线性回归拟合 ln(IC) ~ t
            double sumT = 0, sumLnIc = 0, sumTLnIc = 0, sumTSq = 0;
            int n = data.size();
            for (double[] d : data) {
                double t = d[0];
                double lnIc = Math.log(d[1]);
                sumT += t;
                sumLnIc += lnIc;
                sumTLnIc += t * lnIc;
                sumTSq += t * t;
            }
            double denominator = n * sumTSq - sumT * sumT;
            if (Math.abs(denominator) < 1e-10) return -1;
            double k = (n * sumTLnIc - sumT * sumLnIc) / denominator;
            // k应为正（IC随时间衰减），若k<=0说明IC未衰减或无规律
            if (k >= -1e-6) return -1;
            double halfLife = Math.log(2) / (-k);
            return halfLife > 0 ? halfLife : -1;
        } catch (Exception e) {
            log.debug("[WalkForward] IC半衰期拟合失败: {}", e.getMessage());
            return -1;
        }
    }
}
