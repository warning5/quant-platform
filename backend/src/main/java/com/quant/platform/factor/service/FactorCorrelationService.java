package com.quant.platform.factor.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * 因子相关性分析服务
 * 计算因子之间的相关性矩阵
 * 算法说明：
 * 1. 通过 ClickHouse 侧 quantileExact(0.5) 批量聚合取得每日截面中位数（避免拉取千万级原始数据）
 * 2. 对两个因子的日度中位数时间序列做 Pearson 相关性计算
 * 3. 使用中位数而非均值，对极端值更鲁棒
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactorCorrelationService {

    private final ClickHouseFactorValueService clickHouseFactorValueService;

    /**
     * 计算因子相关性矩阵
     *
     * @param factorCodes 因子代码列表
     * @param startDate   开始日期
     * @param endDate     结束日期
     * @return 相关性数据 [{factorCode1, factorCode2, correlation, sampleSize}]
     * @throws RuntimeException 当 ClickHouse 查询失败时抛出，前端可解析 error message 显示
     */
    public List<Map<String, Object>> computeCorrelationMatrix(List<String> factorCodes,
                                                              LocalDate startDate,
                                                              LocalDate endDate) {
        log.info("Computing correlation matrix for {} factors from {} to {}", factorCodes.size(), startDate, endDate);

        // ========== 第一步：CH 侧聚合取每日截面中位数（批量单次查询）==========
        // factorSeriesMap: factorCode -> Map<日期, 截面中位数>
        Map<String, Map<LocalDate, Double>> factorSeriesMap;
        try {
            factorSeriesMap = clickHouseFactorValueService.getDailyMedians(factorCodes, startDate, endDate);
        } catch (Exception e) {
            log.error("ClickHouse 批量聚合查询失败: {}", e.getMessage());
            throw new RuntimeException("ClickHouse 查询超时或不可用，请稍后重试。密集计算（OPTIMIZE）期间 CH 负载高，建议错峰查询。", e);
        }

        // 收集所有有数据的日期
        Set<LocalDate> allDates = new TreeSet<>();
        for (Map<LocalDate, Double> series : factorSeriesMap.values()) {
            allDates.addAll(series.keySet());
        }

        List<LocalDate> dateList = new ArrayList<>(allDates);
        if (dateList.isEmpty()) {
            log.warn("所有因子在指定范围内均无截面中位数数据: factors={}, range={}~{}", factorCodes, startDate, endDate);
            // 区分"数据真的不存在"和"连接超时"：连接超时已在 try-catch 中抛出
            return List.of();  // 合法空结果，前端会提示
        }

        // 记录各因子覆盖情况
        for (String factorCode : factorCodes) {
            Map<LocalDate, Double> series = factorSeriesMap.getOrDefault(factorCode, Map.of());
            log.info("Factor {} cross-section median from CH: {} dates", factorCode, series.size());
        }

        // ========== 第二步：两两配对计算 Pearson 相关系数 ==========
        List<Map<String, Object>> correlations = new ArrayList<>();

        for (int i = 0; i < factorCodes.size(); i++) {
            for (int j = i; j < factorCodes.size(); j++) {
                String code1 = factorCodes.get(i);
                String code2 = factorCodes.get(j);

                Map<LocalDate, Double> series1 = factorSeriesMap.getOrDefault(code1, Map.of());
                Map<LocalDate, Double> series2 = factorSeriesMap.getOrDefault(code2, Map.of());

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
                    log.info("Insufficient data for {} vs {}: {} common dates (need >=10)",
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

        log.info("Correlation matrix completed: {} factors, {} pairs, {} dates",
                factorCodes.size(), correlations.size(), dateList.size());
        return correlations;
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
