package com.quant.platform.factor.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.quant.platform.factor.domain.FactorValue;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import org.apache.commons.math3.distribution.TDistribution;

/**
 * 因子有效性分析服务
 * 计算 IC (Information Coefficient) / IR (Information Ratio) 等指标
 * IC = Spearman秩相关系数(因子值, 下期收益率)
 * IR = IC均值 / IC标准差
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactorAnalysisService {

    @Autowired(required = false)
    @Qualifier("clickHouseJdbcTemplate")
    private JdbcTemplate clickHouseJdbcTemplate;

    private final JdbcTemplate jdbcTemplate; // MySQL

    @Autowired(required = false)
    private ClickHouseFactorValueService clickHouseFactorValueService;

    /**
     * 批量计算多因子 IC/IR
     * @param factorCodes 因子代码列表
     * @param startDate 开始日期
     * @param endDate 结束日期
     * @param forwardDays 前瞻天数（默认5日）
     * @return 每个因子的 IC/IR 统计
     */
    public List<Map<String, Object>> batchCalcIcIr(List<String> factorCodes, String startDate, String endDate, int forwardDays) {
        List<Map<String, Object>> results = new ArrayList<>();

        for (String factorCode : factorCodes) {
            try {
                Map<String, Object> stat = calcSingleFactorIcIr(factorCode, startDate, endDate, forwardDays);
                results.add(stat);
            } catch (Exception e) {
                log.error("因子IC/IR计算失败: factorCode={}, error={}", factorCode, e.getMessage());
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("factorCode", factorCode);
                err.put("error", e.getMessage());
                results.add(err);
            }
        }

        // 按 IC 绝对值降序排列
        results.sort((a, b) -> {
            double icA = Math.abs(((Number) a.getOrDefault("icMean", 0)).doubleValue());
            double icB = Math.abs(((Number) b.getOrDefault("icMean", 0)).doubleValue());
            return Double.compare(icB, icA);
        });

        return results;
    }

    /**
     * 获取因子IC/IR趋势数据（单个因子）
     */
    public Map<String, Object> getFactorIcTrend(String factorCode, String startDate, String endDate, int forwardDays) {
        // icTimeline 已包含在 result 中
        return calcSingleFactorIcIr(factorCode, startDate, endDate, forwardDays);
    }

    /**
     * 单因子 IC/IR 详细计算
     */
    private Map<String, Object> calcSingleFactorIcIr(String factorCode, String startDate, String endDate, int forwardDays) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("factorCode", factorCode);
        result.put("forwardDays", forwardDays);

        // 1. 从 ClickHouse 获取因子值（直接用 clickHouseJdbcTemplate，避免 FINAL 导致的超时）
        List<Map<String, Object>> factorRows = new ArrayList<>();
        if (clickHouseJdbcTemplate != null) {
            try {
                // 不用 FINAL：ReplacingMergeTree 的 FINAL 对190万行做合并极慢，
                // IC 计算对少量重复行不敏感，直接查更快
                String factorSql = String.format("""
                    SELECT symbol, toString(calc_date) AS calc_date, factor_val
                    FROM stock.factor_value
                    WHERE factor_code = '%s' AND calc_date >= '%s' AND calc_date <= '%s'
                      AND factor_val IS NOT NULL
                    ORDER BY calc_date, symbol
                    """, factorCode.replace("'", "''"), startDate, endDate);
                factorRows = clickHouseJdbcTemplate.query(factorSql,
                    (rs, rowNum) -> {
                        Map<String, Object> m = new HashMap<>();
                        String symbol = rs.getString("symbol");
                        if (symbol != null && symbol.contains(".")) symbol = symbol.split("\\.")[0];
                        m.put("symbol", symbol);
                        m.put("calcDate", rs.getString("calc_date"));
                        m.put("factorVal", rs.getBigDecimal("factor_val"));
                        return m;
                    });
            } catch (Exception e) {
                log.warn("[IC/IR] ClickHouse 因子值查询失败，尝试回退 MySQL: {}", e.getMessage());
            }
        }
        if (factorRows.isEmpty()) {
            // 回退 MySQL
            String factorSql = """
                SELECT symbol, calc_date, factor_val
                FROM factor_value
                WHERE factor_code = ?
                  AND calc_date BETWEEN ? AND ?
                  AND factor_val IS NOT NULL
                ORDER BY calc_date, symbol
                """;
            factorRows = jdbcTemplate.query(factorSql,
                (rs, rowNum) -> {
                    Map<String, Object> m = new HashMap<>();
                    String symbol = rs.getString("symbol");
                    if (symbol != null && symbol.contains(".")) symbol = symbol.split("\\.")[0];
                    m.put("symbol", symbol);
                    m.put("calcDate", rs.getDate("calc_date").toLocalDate().toString());
                    m.put("factorVal", rs.getBigDecimal("factor_val"));
                    return m;
                }, factorCode, startDate, endDate);
        }

        if (factorRows.isEmpty()) {
            result.put("error", "无因子数据");
            result.put("icMean", 0);
            result.put("ir", 0);
            return result;
        }

        // 按 calcDate 分组
        Map<String, List<Map<String, Object>>> byDate = factorRows.stream()
            .collect(Collectors.groupingBy(r -> (String) r.get("calcDate"), LinkedHashMap::new, Collectors.toList()));

        // 2. 批量从 CH 取价格数据（一次查询替代 N×2 次查询）
        Set<String> allSymbols = factorRows.stream()
            .map(r -> (String) r.get("symbol"))
            .filter(Objects::nonNull)
            .filter(s -> s.matches("\\d{6}"))
            .collect(Collectors.toSet());

        // 确定需要的价格日期范围：startDate 到 endDate+forwardDays 缓冲区
        LocalDate priceEnd = LocalDate.parse(endDate).plusDays(forwardDays * 3L);
        Map<String, Map<String, Double>> codeDatePrice  // code → date → close_price
            = queryBulkClosePrices(allSymbols, startDate, priceEnd.toString());
        Map<String, String> forwardDateMap       // calcDate → forwardDate
            = buildForwardDateMap(startDate, priceEnd.toString(), forwardDays);

        // 3. 纯内存计算 IC
        List<Double> icSeries = new ArrayList<>();
        List<Map<String, Object>> icTimeline = new ArrayList<>();

        for (Map.Entry<String, List<Map<String, Object>>> entry : byDate.entrySet()) {
            String calcDate = entry.getKey();
            List<Map<String, Object>> dayFactors = entry.getValue();

            if (dayFactors.size() < 10) continue;
            String fwdDate = forwardDateMap.get(calcDate);
            if (fwdDate == null) continue;

            List<Double> factorVals = new ArrayList<>();
            List<Double> forwardReturns = new ArrayList<>();

            for (Map<String, Object> f : dayFactors) {
                String sym = (String) f.get("symbol");
                BigDecimal fval = (BigDecimal) f.get("factorVal");
                if (sym == null || fval == null) continue;

                Map<String, Double> symPrices = codeDatePrice.get(sym);
                if (symPrices == null) continue;
                Double currP = symPrices.get(calcDate);
                Double fwdP = symPrices.get(fwdDate);
                if (currP == null || fwdP == null || currP <= 0) continue;

                factorVals.add(fval.doubleValue());
                forwardReturns.add((fwdP - currP) / currP);
            }

            if (factorVals.size() < 10) continue;

            double ic = calcSpearmanCorrelation(factorVals, forwardReturns);
            if (!Double.isNaN(ic)) {
                icSeries.add(ic);
                Map<String, Object> timelineEntry = new LinkedHashMap<>();
                timelineEntry.put("date", calcDate);
                timelineEntry.put("ic", Math.round(ic * 10000.0) / 10000.0);
                timelineEntry.put("sampleSize", factorVals.size());
                icTimeline.add(timelineEntry);
            }
        }

        // 3. 汇总统计
        if (icSeries.isEmpty()) {
            result.put("error", "IC序列为空");
            result.put("icMean", 0);
            result.put("ir", 0);
            return result;
        }

        double icMean = icSeries.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double icStd = calcStd(icSeries);
        double ir = icStd > 0 ? icMean / icStd : 0;
        long icPositiveCount = icSeries.stream().filter(v -> v > 0).count();
        double icWinRate = (double) icPositiveCount / icSeries.size() * 100;

        result.put("icMean", Math.round(icMean * 10000.0) / 10000.0);
        result.put("icStd", Math.round(icStd * 10000.0) / 10000.0);
        result.put("ir", Math.round(ir * 100.0) / 100.0);

        // t 统计量 和 p 值
        int n = icSeries.size();
        double tStat = icStd > 0 ? icMean / (icStd / Math.sqrt(n)) : 0;
        result.put("tStat", Math.round(tStat * 100.0) / 100.0);

        double pValue = 1.0;
        if (n > 1 && icStd > 0) {
            try {
                TDistribution tDist = new TDistribution(n - 1);
                pValue = 2.0 * (1.0 - tDist.cumulativeProbability(Math.abs(tStat)));
            } catch (Exception e) {
                pValue = 1.0;
            }
        }
        result.put("pValue", Math.round(pValue * 10000.0) / 10000.0);

        result.put("icWinRate", Math.round(icWinRate * 10.0) / 10.0);
        result.put("sampleDays", icSeries.size());
        result.put("totalFactorRows", factorRows.size());
        result.put("icTimeline", icTimeline);

        // IC 有效性判断
        String assessment;
        if (Math.abs(icMean) >= 0.05 && Math.abs(ir) >= 0.5) assessment = "有效因子";
        else if (Math.abs(icMean) >= 0.03 && Math.abs(ir) >= 0.3) assessment = "弱有效";
        else assessment = "无效因子";
        result.put("assessment", assessment);

        return result;
    }

    /**
     * 批量取收盘价：一次查询所有股票在指定日期范围内的所有收盘价。
     * @return Map<code, Map<tradeDate, closePrice>>
     */
    private Map<String, Map<String, Double>> queryBulkClosePrices(
            Set<String> symbols, String startDate, String endDate) {
        Map<String, Map<String, Double>> result = new LinkedHashMap<>();
        if (clickHouseJdbcTemplate == null || symbols.isEmpty()) return result;

        String inClause = symbols.stream()
            .filter(s -> s.matches("\\d{6}"))
            .collect(Collectors.joining("','", "'", "'"));
        if (inClause.length() <= 2) return result;

        String sql = String.format("""
            SELECT code, trade_date, close_price
            FROM stock.stock_daily
            WHERE code IN (%s) AND trade_date BETWEEN ? AND ?
            ORDER BY code, trade_date
            """, inClause);

        clickHouseJdbcTemplate.query(sql, (rs) -> {
            String code = rs.getString("code");
            String date = rs.getString("trade_date");
            Double price = rs.getBigDecimal("close_price").doubleValue();
            result.computeIfAbsent(code, k -> new LinkedHashMap<>()).put(date, price);
        }, startDate, endDate);
        return result;
    }

    /**
     * 构建交易日历前向映射：calcDate → forwardDate (第 forwardDays 个交易日)
     */
    private Map<String, String> buildForwardDateMap(String startDate, String endDate, int forwardDays) {
        Map<String, String> result = new LinkedHashMap<>();
        if (clickHouseJdbcTemplate == null) return result;

        String sql = """
            SELECT DISTINCT trade_date FROM stock.stock_daily
            WHERE trade_date BETWEEN ? AND ?
            ORDER BY trade_date
            """;

        List<String> tradingDates = clickHouseJdbcTemplate.query(sql,
            (rs, rowNum) -> rs.getString("trade_date"), startDate, endDate);

        int n = tradingDates.size();
        for (int i = 0; i < n - forwardDays; i++) {
            result.put(tradingDates.get(i), tradingDates.get(i + forwardDays));
        }
        return result;
    }

    /**
     * 计算 Spearman 秩相关系数
     */
    private double calcSpearmanCorrelation(List<Double> x, List<Double> y) {
        int n = x.size();
        if (n != y.size() || n < 3) return Double.NaN;

        int[] rankX = calcRank(x);
        int[] rankY = calcRank(y);

        // Pearson correlation on ranks
        double meanRX = 0, meanRY = 0;
        for (int i = 0; i < n; i++) {
            meanRX += rankX[i];
            meanRY += rankY[i];
        }
        meanRX /= n;
        meanRY /= n;

        double cov = 0, varX = 0, varY = 0;
        for (int i = 0; i < n; i++) {
            double dx = rankX[i] - meanRX;
            double dy = rankY[i] - meanRY;
            cov += dx * dy;
            varX += dx * dx;
            varY += dy * dy;
        }

        if (varX == 0 || varY == 0) return Double.NaN;
        return cov / Math.sqrt(varX * varY);
    }

    private int[] calcRank(List<Double> values) {
        int n = values.size();
        Integer[] indices = new Integer[n];
        for (int i = 0; i < n; i++) indices[i] = i;

        Arrays.sort(indices, Comparator.comparingDouble(values::get));

        int[] ranks = new int[n];
        for (int i = 0; i < n; i++) {
            ranks[indices[i]] = i + 1;
        }
        return ranks;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  分段对比 IC 分析
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 批量分段 IC/IR 分析：按 splitDate 将时间窗口分为前段/后段/全量三组
     */
    public Map<String, Object> batchCalcIcIrSegmented(
            List<String> factorCodes, String startDate, String endDate,
            String splitDate, int forwardDays) {

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("segmented", true);
        result.put("splitDate", splitDate);
        result.put("forwardDays", forwardDays);

        List<Map<String, Object>> beforeResults = new ArrayList<>();
        List<Map<String, Object>> afterResults = new ArrayList<>();
        List<Map<String, Object>> fullResults = new ArrayList<>();

        for (String factorCode : factorCodes) {
            try {
                Map<String, Object> seg = calcSingleFactorIcIrSegmented(
                        factorCode, startDate, endDate, splitDate, forwardDays);

                @SuppressWarnings("unchecked")
                Map<String, Object> before = (Map<String, Object>) seg.get("before");
                @SuppressWarnings("unchecked")
                Map<String, Object> after = (Map<String, Object>) seg.get("after");
                @SuppressWarnings("unchecked")
                Map<String, Object> full = (Map<String, Object>) seg.get("full");

                if (before != null) {
                    before.put("factorCode", factorCode);
                    beforeResults.add(before);
                }
                if (after != null) {
                    after.put("factorCode", factorCode);
                    afterResults.add(after);
                }
                if (full != null) {
                    full.put("factorCode", factorCode);
                    fullResults.add(full);
                }
            } catch (Exception e) {
                log.error("分段IC失败: factorCode={}, error={}", factorCode, e.getMessage());
            }
        }

        // 按 |IC| 绝对值降序（全量排序）
        Comparator<Map<String, Object>> byAbsIc = (a, b) -> {
            double ia = Math.abs(((Number) a.getOrDefault("icMean", 0)).doubleValue());
            double ib = Math.abs(((Number) b.getOrDefault("icMean", 0)).doubleValue());
            return Double.compare(ib, ia);
        };
        beforeResults.sort(byAbsIc);
        afterResults.sort(byAbsIc);
        fullResults.sort(byAbsIc);

        Map<String, Object> segments = new LinkedHashMap<>();

        Map<String, Object> beforeSeg = new LinkedHashMap<>();
        beforeSeg.put("label", startDate + " ~ " + splitDate);
        beforeSeg.put("startDate", startDate);
        beforeSeg.put("endDate", splitDate);
        beforeSeg.put("results", beforeResults);
        segments.put("before", beforeSeg);

        // after 段的 startDate 取 splitDate 的下一个自然日（显示用）
        LocalDate afterStart = LocalDate.parse(splitDate);
        Map<String, Object> afterSeg = new LinkedHashMap<>();
        afterSeg.put("label", splitDate + " ~ " + endDate);
        afterSeg.put("startDate", splitDate);
        afterSeg.put("endDate", endDate);
        afterSeg.put("results", afterResults);
        segments.put("after", afterSeg);

        Map<String, Object> fullSeg = new LinkedHashMap<>();
        fullSeg.put("label", startDate + " ~ " + endDate + " (全量)");
        fullSeg.put("startDate", startDate);
        fullSeg.put("endDate", endDate);
        fullSeg.put("results", fullResults);
        segments.put("full", fullSeg);

        result.put("segments", segments);
        return result;
    }

    /**
     * 单因子分段 IC 计算 — 核心优化：查一次 CH，按日期拆分 IC 序列
     * @return { before: {icMean,ir,...}, after: {...}, full: {...} }
     */
    private Map<String, Object> calcSingleFactorIcIrSegmented(
            String factorCode, String startDate, String endDate,
            String splitDate, int forwardDays) {

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("factorCode", factorCode);

        // ── 1. 获取因子值（一次CH查询，全量范围） ──
        List<Map<String, Object>> factorRows = new ArrayList<>();
        if (clickHouseJdbcTemplate != null) {
            try {
                String factorSql = String.format("""
                    SELECT symbol, toString(calc_date) AS calc_date, factor_val
                    FROM stock.factor_value
                    WHERE factor_code = '%s' AND calc_date >= '%s' AND calc_date <= '%s'
                      AND factor_val IS NOT NULL
                    ORDER BY calc_date, symbol
                    """, factorCode.replace("'", "''"), startDate, endDate);
                factorRows = clickHouseJdbcTemplate.query(factorSql,
                    (rs, rowNum) -> {
                        Map<String, Object> m = new HashMap<>();
                        String symbol = rs.getString("symbol");
                        if (symbol != null && symbol.contains(".")) symbol = symbol.split("\\.")[0];
                        m.put("symbol", symbol);
                        m.put("calcDate", rs.getString("calc_date"));
                        m.put("factorVal", rs.getBigDecimal("factor_val"));
                        return m;
                    });
            } catch (Exception e) {
                log.warn("[分段IC] CH 因子值查询失败: {}", e.getMessage());
            }
        }
        if (factorRows.isEmpty()) {
            factorRows = jdbcTemplate.query("""
                SELECT symbol, calc_date, factor_val FROM factor_value
                WHERE factor_code = ? AND calc_date BETWEEN ? AND ?
                  AND factor_val IS NOT NULL ORDER BY calc_date, symbol
                """, (rs, rowNum) -> {
                    Map<String, Object> m = new HashMap<>();
                    String symbol = rs.getString("symbol");
                    if (symbol != null && symbol.contains(".")) symbol = symbol.split("\\.")[0];
                    m.put("symbol", symbol);
                    m.put("calcDate", rs.getDate("calc_date").toLocalDate().toString());
                    m.put("factorVal", rs.getBigDecimal("factor_val"));
                    return m;
                }, factorCode, startDate, endDate);
        }
        if (factorRows.isEmpty()) {
            result.put("before", emptyIcResult("无因子数据"));
            result.put("after", emptyIcResult("无因子数据"));
            result.put("full", emptyIcResult("无因子数据"));
            return result;
        }

        // ── 2. 批量获取价格（一次CH查询） ──
        Set<String> allSymbols = factorRows.stream()
                .map(r -> (String) r.get("symbol"))
                .filter(Objects::nonNull)
                .filter(s -> s.matches("\\d{6}"))
                .collect(Collectors.toSet());
        LocalDate priceEnd = LocalDate.parse(endDate).plusDays(forwardDays * 3L);
        Map<String, Map<String, Double>> codeDatePrice =
                queryBulkClosePrices(allSymbols, startDate, priceEnd.toString());
        Map<String, String> forwardDateMap =
                buildForwardDateMap(startDate, priceEnd.toString(), forwardDays);

        // ── 3. 按日期分组，计算每日期 IC ──
        Map<String, List<Map<String, Object>>> byDate = factorRows.stream()
                .collect(Collectors.groupingBy(
                        r -> (String) r.get("calcDate"),
                        LinkedHashMap::new, Collectors.toList()));

        List<Double> fullIcSeries = new ArrayList<>();
        List<Map<String, Object>> fullTimeline = new ArrayList<>();

        for (Map.Entry<String, List<Map<String, Object>>> entry : byDate.entrySet()) {
            String calcDate = entry.getKey();
            List<Map<String, Object>> dayFactors = entry.getValue();

            if (dayFactors.size() < 10) continue;
            String fwdDate = forwardDateMap.get(calcDate);
            if (fwdDate == null) continue;

            List<Double> factorVals = new ArrayList<>();
            List<Double> forwardReturns = new ArrayList<>();

            for (Map<String, Object> f : dayFactors) {
                String sym = (String) f.get("symbol");
                BigDecimal fval = (BigDecimal) f.get("factorVal");
                if (sym == null || fval == null) continue;

                Map<String, Double> symPrices = codeDatePrice.get(sym);
                if (symPrices == null) continue;
                Double currP = symPrices.get(calcDate);
                Double fwdP = symPrices.get(fwdDate);
                if (currP == null || fwdP == null || currP <= 0) continue;

                factorVals.add(fval.doubleValue());
                forwardReturns.add((fwdP - currP) / currP);
            }

            if (factorVals.size() < 10) continue;

            double ic = calcSpearmanCorrelation(factorVals, forwardReturns);
            if (!Double.isNaN(ic)) {
                fullIcSeries.add(ic);
                Map<String, Object> tl = new LinkedHashMap<>();
                tl.put("date", calcDate);
                tl.put("ic", Math.round(ic * 10000.0) / 10000.0);
                tl.put("sampleSize", factorVals.size());
                fullTimeline.add(tl);
            }
        }

        if (fullIcSeries.isEmpty()) {
            result.put("before", emptyIcResult("IC序列为空"));
            result.put("after", emptyIcResult("IC序列为空"));
            result.put("full", emptyIcResult("IC序列为空"));
            return result;
        }

        // ── 4. 按 splitDate 拆分 IC 序列 ──
        List<Double> beforeIcSeries = new ArrayList<>();
        List<Double> afterIcSeries = new ArrayList<>();
        List<Map<String, Object>> beforeTimeline = new ArrayList<>();
        List<Map<String, Object>> afterTimeline = new ArrayList<>();

        for (int i = 0; i < fullTimeline.size(); i++) {
            String date = (String) fullTimeline.get(i).get("date");
            if (date.compareTo(splitDate) < 0) {
                beforeIcSeries.add(fullIcSeries.get(i));
                beforeTimeline.add(fullTimeline.get(i));
            } else {
                afterIcSeries.add(fullIcSeries.get(i));
                afterTimeline.add(fullTimeline.get(i));
            }
        }

        // ── 5. 分别聚合统计 ──
        result.put("before", aggregateIcSegment(beforeIcSeries, beforeTimeline, factorRows.size(), forwardDays));
        result.put("after", aggregateIcSegment(afterIcSeries, afterTimeline, factorRows.size(), forwardDays));
        result.put("full", aggregateIcSegment(fullIcSeries, fullTimeline, factorRows.size(), forwardDays));

        return result;
    }

    /** 从 IC 序列聚合统计 */
    private Map<String, Object> aggregateIcSegment(
            List<Double> icSeries, List<Map<String, Object>> icTimeline,
            int totalFactorRows, int forwardDays) {

        Map<String, Object> stat = new LinkedHashMap<>();
        if (icSeries.isEmpty()) {
            stat.put("icMean", 0);
            stat.put("ir", 0);
            stat.put("sampleDays", 0);
            stat.put("error", "IC序列为空");
            return stat;
        }

        double icMean = icSeries.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double icStd = calcStd(icSeries);
        double ir = icStd > 0 ? icMean / icStd : 0;
        long icPositiveCount = icSeries.stream().filter(v -> v > 0).count();
        double icWinRate = (double) icPositiveCount / icSeries.size() * 100;

        stat.put("icMean", Math.round(icMean * 10000.0) / 10000.0);
        stat.put("icStd", Math.round(icStd * 10000.0) / 10000.0);
        stat.put("ir", Math.round(ir * 100.0) / 100.0);

        int n = icSeries.size();
        double tStat = icStd > 0 ? icMean / (icStd / Math.sqrt(n)) : 0;
        stat.put("tStat", Math.round(tStat * 100.0) / 100.0);

        double pValue = 1.0;
        if (n > 1 && icStd > 0) {
            try {
                TDistribution tDist = new TDistribution(n - 1);
                pValue = 2.0 * (1.0 - tDist.cumulativeProbability(Math.abs(tStat)));
            } catch (Exception e) { pValue = 1.0; }
        }
        stat.put("pValue", Math.round(pValue * 10000.0) / 10000.0);
        stat.put("icWinRate", Math.round(icWinRate * 10.0) / 10.0);
        stat.put("sampleDays", n);
        stat.put("totalFactorRows", totalFactorRows);
        stat.put("icTimeline", icTimeline);
        stat.put("forwardDays", forwardDays);

        // 有效性判断
        String assessment;
        if (Math.abs(icMean) >= 0.05 && Math.abs(ir) >= 0.5) assessment = "有效因子";
        else if (Math.abs(icMean) >= 0.03 && Math.abs(ir) >= 0.3) assessment = "弱有效";
        else assessment = "无效因子";
        stat.put("assessment", assessment);

        return stat;
    }

    private Map<String, Object> emptyIcResult(String error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("icMean", 0);
        m.put("ir", 0);
        m.put("sampleDays", 0);
        m.put("error", error);
        return m;
    }

    private double calcStd(List<Double> values) {
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double sumSq = 0;
        for (double v : values) {
            sumSq += (v - mean) * (v - mean);
        }
        return Math.sqrt(sumSq / (values.size() - 1));
    }
}
