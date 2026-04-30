package com.quant.platform.factor.service;

import com.quant.platform.factor.domain.FactorValue;
import com.quant.platform.factor.mapper.FactorValueMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 因子相关性分析服务
 * 计算因子之间的相关性矩阵
 * 算法说明：
 * 1. 对每个因子，按日期聚合所有股票的因子值，取截面中位数，得到一条时间序列
 * 2. 对两个因子的时间序列做 Pearson 相关性计算
 * 3. 使用中位数而非均值，对极端值更鲁棒
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactorCorrelationService {

    private final FactorValueMapper factorValueMapper;

    /**
     * 计算因子相关性矩阵
     *
     * @param factorCodes 因子代码列表
     * @param startDate   开始日期
     * @param endDate     结束日期
     * @return 相关性数据 [{factorCode1, factorCode2, correlation}]
     */
    public List<Map<String, Object>> computeCorrelationMatrix(List<String> factorCodes,
                                                              LocalDate startDate,
                                                              LocalDate endDate) {
        log.info("Computing correlation matrix for {} factors from {} to {}", factorCodes.size(), startDate, endDate);

        List<Map<String, Object>> correlations = new ArrayList<>();

        // ========== 第一步：按日期截面聚合，取中位数 ==========
        // factorSeriesMap: factorCode -> Map<日期, 截面中位数>
        Map<String, Map<LocalDate, Double>> factorSeriesMap = new HashMap<>();
        Set<LocalDate> allDates = new TreeSet<>();

        for (String factorCode : factorCodes) {
            List<FactorValue> values = factorValueMapper.findByFactorCodeAndDateRange(factorCode, startDate, endDate);

            // 按日期分组
            Map<LocalDate, List<Double>> crossSection = values.stream()
                    .filter(fv -> fv.getFactorVal() != null)
                    .filter(fv -> fv.getFactorVal().doubleValue() == fv.getFactorVal().doubleValue()) // 过滤NaN
                    .collect(Collectors.groupingBy(
                            FactorValue::getCalcDate,
                            TreeMap::new,
                            Collectors.mapping(fv -> fv.getFactorVal().doubleValue(), Collectors.toList())
                    ));

            // 每天取中位数
            Map<LocalDate, Double> series = new HashMap<>();
            for (Map.Entry<LocalDate, List<Double>> entry : crossSection.entrySet()) {
                List<Double> sorted = entry.getValue().stream().sorted().collect(Collectors.toList());
                series.put(entry.getKey(), median(sorted));
                allDates.add(entry.getKey());
            }

            factorSeriesMap.put(factorCode, series);
            log.info("Factor {} cross-section aggregated: {} dates, {} raw records",
                    factorCode, series.size(), values.size());
        }

        List<LocalDate> dateList = new ArrayList<>(allDates);
        if (dateList.isEmpty()) {
            log.warn("No data found for the specified date range");
            return correlations;
        }

        // ========== 第二步：两两配对计算 Pearson 相关系数 ==========
        for (int i = 0; i < factorCodes.size(); i++) {
            for (int j = i; j < factorCodes.size(); j++) {
                String code1 = factorCodes.get(i);
                String code2 = factorCodes.get(j);

                Map<LocalDate, Double> series1 = factorSeriesMap.get(code1);
                Map<LocalDate, Double> series2 = factorSeriesMap.get(code2);

                // 取两个因子都有数据的公共日期
                List<Double> list1 = new ArrayList<>();
                List<Double> list2 = new ArrayList<>();
                for (LocalDate date : dateList) {
                    Double v1 = series1.get(date);
                    Double v2 = series2.get(date);
                    if (v1 != null && v2 != null && !Double.isNaN(v1) && !Double.isNaN(v2)) {
                        list1.add(v1);
                        list2.add(v2);
                    }
                }

                if (list1.size() < 10) {
                    log.warn("Insufficient data points for {} vs {}: {} common dates",
                            code1, code2, list1.size());
                    continue;
                }

                double correlation = pearsonCorrelation(list1, list2);

                // NaN 保护（极端情况如所有值相同导致除零）
                if (Double.isNaN(correlation)) {
                    correlation = 0.0;
                }

                Map<String, Object> corr = new HashMap<>();
                corr.put("factorCode1", code1);
                corr.put("factorCode2", code2);
                corr.put("correlation", BigDecimal.valueOf(correlation).setScale(6, RoundingMode.HALF_UP));
                corr.put("sampleSize", list1.size());
                correlations.add(corr);
            }
        }

        log.info("Correlation matrix computation completed, {} pairs computed", correlations.size());
        return correlations;
    }

    /**
     * 计算列表中位数
     */
    private double median(List<Double> sorted) {
        int n = sorted.size();
        if (n == 0) return 0;
        if (n % 2 == 1) return sorted.get(n / 2);
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    /**
     * 计算Pearson相关系数
     */
    private double pearsonCorrelation(List<Double> x, List<Double> y) {
        int n = x.size();
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0, sumY2 = 0;

        for (int i = 0; i < n; i++) {
            double xi = x.get(i);
            double yi = y.get(i);
            sumX += xi;
            sumY += yi;
            sumXY += xi * yi;
            sumX2 += xi * xi;
            sumY2 += yi * yi;
        }

        double numerator = n * sumXY - sumX * sumY;
        double denominator = Math.sqrt((n * sumX2 - sumX * sumX) * (n * sumY2 - sumY * sumY));

        if (denominator == 0) return 0;
        return numerator / denominator;
    }
}
