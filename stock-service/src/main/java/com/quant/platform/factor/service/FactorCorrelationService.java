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

    // ================================================================
    //  P3: 因子拥挤度检测
    // ================================================================

    /** 因子组（聚类结果） */
    public static class FactorCluster {
        public String representative;         // 组代表（IC最高的因子）
        public List<String> members;          // 所有成员
        public double maxCorrelation;         // 组内最大相关性
        public List<String> redundantFactors; // 被标记为冗余的因子（不含代表）
    }

    /**
     * P3: 因子拥挤度检测 + 聚类去重
     *
     * @param factorCodes    因子代码列表
     * @param startDate      开始日期
     * @param endDate        结束日期
     * @param corrThreshold  相关性阈值（默认0.7）
     * @param icSnapshot     因子IC快照（用于选组内代表），可为null（用等权策略仅聚类）
     * @return 拥挤度分析报告
     */
    public List<FactorCluster> detectCrowding(
            List<String> factorCodes, LocalDate startDate, LocalDate endDate,
            double corrThreshold,
            Map<String, ?> icSnapshot) {

        if (factorCodes == null || factorCodes.size() < 2) {
            return Collections.emptyList();
        }

        // 1. 获取相关性矩阵
        List<Map<String, Object>> corrMatrix;
        try {
            corrMatrix = computeCorrelationMatrix(factorCodes, startDate, endDate);
        } catch (Exception e) {
            log.warn("[Crowding] 相关性矩阵计算失败: {}", e.getMessage());
            return Collections.emptyList();
        }

        if (corrMatrix.isEmpty()) {
            log.info("[Crowding] 无相关性数据，跳过拥挤度分析");
            return Collections.emptyList();
        }

        // 2. 构建邻接表（只保留相关性 >= 阈值且非自相关的边）
        Map<String, Set<String>> adjacency = new LinkedHashMap<>();
        for (String fc : factorCodes) {
            adjacency.put(fc, new HashSet<>());
        }
        for (Map<String, Object> pair : corrMatrix) {
            String c1 = (String) pair.get("factorCode1");
            String c2 = (String) pair.get("factorCode2");
            if (c1.equals(c2)) continue;
            double corr = ((Number) pair.get("correlation")).doubleValue();
            if (Math.abs(corr) >= corrThreshold) {
                adjacency.get(c1).add(c2);
                adjacency.get(c2).add(c1);
            }
        }

        // 3. 并查集聚类
        Map<String, String> parent = new HashMap<>();
        for (String fc : factorCodes) parent.put(fc, fc);

        for (Map<String, Object> pair : corrMatrix) {
            String c1 = (String) pair.get("factorCode1");
            String c2 = (String) pair.get("factorCode2");
            if (c1.equals(c2)) continue;
            double corr = ((Number) pair.get("correlation")).doubleValue();
            if (Math.abs(corr) >= corrThreshold) {
                union(parent, c1, c2);
            }
        }

        // 4. 构建聚类组
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (String fc : factorCodes) {
            String root = find(parent, fc);
            groups.computeIfAbsent(root, k -> new ArrayList<>()).add(fc);
        }

        // 5. 构建报告：每组选IC最高为代表
        List<FactorCluster> clusters = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : groups.entrySet()) {
            List<String> members = entry.getValue();
            if (members.size() <= 1) continue; // 孤立因子，无拥挤

            // 选代表：IC最高的因子
            String representative = members.get(0);
            if (icSnapshot != null && !icSnapshot.isEmpty()) {
                double bestIc = -Double.MAX_VALUE;
                for (String fc : members) {
                    Object icObj = icSnapshot.get(fc);
                    double ic = 0;
                    if (icObj instanceof FactorAnalysisService.FactorIcSnapshot) {
                        ic = Math.abs(((FactorAnalysisService.FactorIcSnapshot) icObj).icMean);
                    } else if (icObj instanceof Number) {
                        ic = Math.abs(((Number) icObj).doubleValue());
                    }
                    if (ic > bestIc) {
                        bestIc = ic;
                        representative = fc;
                    }
                }
            }

            // 找到组内最大相关性
            double maxCorr = 0;
            for (int i = 0; i < members.size(); i++) {
                for (int j = i + 1; j < members.size(); j++) {
                    for (Map<String, Object> pair : corrMatrix) {
                        String c1 = (String) pair.get("factorCode1");
                        String c2 = (String) pair.get("factorCode2");
                        if ((c1.equals(members.get(i)) && c2.equals(members.get(j))) ||
                            (c1.equals(members.get(j)) && c2.equals(members.get(i)))) {
                            double corr = Math.abs(((Number) pair.get("correlation")).doubleValue());
                            if (corr > maxCorr) maxCorr = corr;
                        }
                    }
                }
            }

            List<String> redundant = new ArrayList<>(members);
            redundant.remove(representative);

            FactorCluster cluster = new FactorCluster();
            cluster.representative = representative;
            cluster.members = members;
            cluster.maxCorrelation = maxCorr;
            cluster.redundantFactors = redundant;

            clusters.add(cluster);

            log.info("[Crowding] 拥挤组: {} 成员={}, 代表={}, maxCorr={:.3f}",
                    members.size(), members, representative, maxCorr);
        }

        log.info("[Crowding] 拥挤度分析完成: {}个因子, {}个聚类组, 阈值={}",
                factorCodes.size(), clusters.size(), corrThreshold);

        return clusters;
    }

    /** 并查集：查找 */
    private String find(Map<String, String> parent, String x) {
        if (!parent.get(x).equals(x)) {
            parent.put(x, find(parent, parent.get(x)));
        }
        return parent.get(x);
    }

    /** 并查集：合并 */
    private void union(Map<String, String> parent, String a, String b) {
        String ra = find(parent, a);
        String rb = find(parent, b);
        if (!ra.equals(rb)) parent.put(ra, rb);
    }

    /**
     * P3: 生成拥挤度报告摘要（供前端展示）
     */
    public Map<String, Object> getCrowdingReport(
            List<String> factorCodes, LocalDate startDate, LocalDate endDate,
            double corrThreshold,
            Map<String, FactorAnalysisService.FactorIcSnapshot> icSnapshots) {

        Map<String, Object> report = new LinkedHashMap<>();

        List<FactorCluster> clusters = detectCrowding(factorCodes, startDate, endDate,
                corrThreshold, icSnapshots);

        report.put("totalFactors", factorCodes.size());
        report.put("clusterCount", clusters.size());
        report.put("correlationThreshold", corrThreshold);
        report.put("startDate", startDate.toString());
        report.put("endDate", endDate.toString());

        int redundantCount = clusters.stream()
                .mapToInt(c -> c.redundantFactors.size()).sum();
        report.put("redundantCount", redundantCount);
        report.put("efficiencyGain", factorCodes.size() > 0 ?
                (double) redundantCount / factorCodes.size() : 0);

        // 详细集群信息
        List<Map<String, Object>> clusterDetails = new ArrayList<>();
        for (FactorCluster c : clusters) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("representative", c.representative);
            detail.put("members", c.members);
            detail.put("clusterSize", c.members.size());
            detail.put("maxCorrelation", Math.round(c.maxCorrelation * 1000.0) / 1000.0);

            List<String> dropped = c.redundantFactors;
            detail.put("dropped", dropped);
            detail.put("droppedCount", dropped.size());

            clusterDetails.add(detail);
        }
        report.put("clusters", clusterDetails);

        // 去重后的因子列表
        Set<String> deduped = new LinkedHashSet<>(factorCodes);
        for (FactorCluster c : clusters) {
            for (String d : c.redundantFactors) {
                deduped.remove(d);
            }
        }
        report.put("dedupedFactorCodes", new ArrayList<>(deduped));
        report.put("dedupedCount", deduped.size());

        return report;
    }
}
