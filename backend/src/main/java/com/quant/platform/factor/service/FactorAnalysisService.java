package com.quant.platform.factor.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

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

        // 1. 从 MySQL 获取因子值（按日期×股票）
        String factorSql = """
            SELECT symbol, calc_date, factor_val
            FROM factor_value
            WHERE factor_code = ?
              AND calc_date BETWEEN ? AND ?
              AND factor_val IS NOT NULL
            ORDER BY calc_date, symbol
            """;
        List<Map<String, Object>> factorRows = jdbcTemplate.query(factorSql,
            (rs, rowNum) -> {
                Map<String, Object> m = new HashMap<>();
                String symbol = rs.getString("symbol");
                // 统一去掉后缀
                if (symbol != null && symbol.contains(".")) symbol = symbol.split("\\.")[0];
                m.put("symbol", symbol);
                m.put("calcDate", rs.getDate("calc_date").toLocalDate().toString());
                m.put("factorVal", rs.getBigDecimal("factor_val"));
                return m;
            }, factorCode, startDate, endDate);

        if (factorRows.isEmpty()) {
            result.put("error", "无因子数据");
            result.put("icMean", 0);
            result.put("ir", 0);
            return result;
        }

        // 按 calcDate 分组
        Map<String, List<Map<String, Object>>> byDate = factorRows.stream()
            .collect(Collectors.groupingBy(r -> (String) r.get("calcDate"), LinkedHashMap::new, Collectors.toList()));

        // 2. 从 CH 获取前向收益率
        List<Double> icSeries = new ArrayList<>();
        List<Map<String, Object>> icTimeline = new ArrayList<>();

        for (Map.Entry<String, List<Map<String, Object>>> entry : byDate.entrySet()) {
            String calcDate = entry.getKey();
            List<Map<String, Object>> dayFactors = entry.getValue();

            if (dayFactors.size() < 10) continue; // 样本太少跳过

            Set<String> symbols = dayFactors.stream()
                .map(r -> (String) r.get("symbol"))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

            if (symbols.isEmpty()) continue;

            try {
                // 当前日收盘价（参数化查询）
                Map<String, Double> currentPrices = queryClosePrices(symbols, calcDate);

                // 第 N 个交易日收盘价（支持 forwardDays > 1）
                Map<String, Double> forwardPrices = queryForwardClosePrices(symbols, calcDate, forwardDays);

                // 计算前向收益率
                List<Double> factorVals = new ArrayList<>();
                List<Double> forwardReturns = new ArrayList<>();

                for (Map<String, Object> f : dayFactors) {
                    String sym = (String) f.get("symbol");
                    BigDecimal fval = (BigDecimal) f.get("factorVal");
                    Double currP = currentPrices.get(sym);
                    Double fwdP = forwardPrices.get(sym);

                    if (sym != null && fval != null && currP != null && fwdP != null && currP > 0) {
                        factorVals.add(fval.doubleValue());
                        forwardReturns.add((fwdP - currP) / currP);
                    }
                }

                if (factorVals.size() < 10) continue;

                // 计算 Spearman 秩相关（IC）
                double ic = calcSpearmanCorrelation(factorVals, forwardReturns);
                if (!Double.isNaN(ic)) {
                    icSeries.add(ic);
                    Map<String, Object> timelineEntry = new LinkedHashMap<>();
                    timelineEntry.put("date", calcDate);
                    timelineEntry.put("ic", Math.round(ic * 10000.0) / 100.0);
                    timelineEntry.put("sampleSize", factorVals.size());
                    icTimeline.add(timelineEntry);
                }
            } catch (Exception e) {
                log.debug("IC计算跳过日期 {}: {}", calcDate, e.getMessage());
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

        result.put("icMean", Math.round(icMean * 10000.0) / 100.0);
        result.put("icStd", Math.round(icStd * 10000.0) / 100.0);
        result.put("ir", Math.round(ir * 100.0) / 100.0);
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
     * 查询指定日期的收盘价（参数化查询，防SQL注入）
     */
    private Map<String, Double> queryClosePrices(Set<String> symbols, String date) {
        if (clickHouseJdbcTemplate == null) return Collections.emptyMap();

        // CH 不支持 IN 子句用 PreparedStatement 的方式，改用临时表思路
        // 安全做法：用 List<Object[]> 批量查询或构造安全 IN 子句
        // symbols 来自 MySQL factor_value.symbol，已去后缀，为6位纯数字，不含特殊字符
        String inClause = symbols.stream()
            .filter(s -> s.matches("\\d{6}"))  // 严格校验：仅6位数字
            .collect(Collectors.joining("','", "'", "'"));

        if (inClause.length() <= 2) return Collections.emptyMap(); // 空集合

        String sql = String.format(
            "SELECT code, close_price FROM stock.stock_daily WHERE code IN (%s) AND trade_date = ?",
            inClause);

        Map<String, Double> prices = new HashMap<>();
        clickHouseJdbcTemplate.query(sql, (rs) -> {
            prices.put(rs.getString("code"), rs.getBigDecimal("close_price").doubleValue());
        }, date);
        return prices;
    }

    /**
     * 查询第 forwardDays 个交易日的收盘价
     * forwardDays=1 → 下一个交易日, forwardDays=5 → 第5个交易日
     */
    private Map<String, Double> queryForwardClosePrices(Set<String> symbols, String calcDate, int forwardDays) {
        if (clickHouseJdbcTemplate == null) return Collections.emptyMap();

        String inClause = symbols.stream()
            .filter(s -> s.matches("\\d{6}"))
            .collect(Collectors.joining("','", "'", "'"));

        if (inClause.length() <= 2) return Collections.emptyMap();

        // 获取 calcDate 之后第 forwardDays 个交易日（全市场统一交易日历）
        // 先找到从 calcDate 之后的第 forwardDays 个交易日
        String tradingDateSql;
        if (forwardDays == 1) {
            // 简化：下一个交易日
            tradingDateSql = "SELECT MIN(trade_date) AS td FROM stock.stock_daily WHERE trade_date > ?";
        } else {
            // 第 N 个交易日：取 trade_date > calcDate 的第 forwardDays 个
            tradingDateSql = String.format("""
                SELECT trade_date AS td FROM stock.stock_daily
                WHERE trade_date > ?
                GROUP BY trade_date
                ORDER BY trade_date ASC
                LIMIT 1 OFFSET %d
                """, forwardDays - 1);
        }

        // 查询目标交易日
        List<String> targetDates = clickHouseJdbcTemplate.query(tradingDateSql,
            (rs, rowNum) -> rs.getString("td"), calcDate);

        if (targetDates.isEmpty()) return Collections.emptyMap();

        String targetDate = targetDates.getFirst();

        // 用目标交易日查询收盘价
        String priceSql = String.format(
            "SELECT code, close_price FROM stock.stock_daily WHERE code IN (%s) AND trade_date = ?",
            inClause);

        Map<String, Double> prices = new HashMap<>();
        clickHouseJdbcTemplate.query(priceSql, (rs) -> {
            prices.put(rs.getString("code"), rs.getBigDecimal("close_price").doubleValue());
        }, targetDate);
        return prices;
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

    private double calcStd(List<Double> values) {
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double sumSq = 0;
        for (double v : values) {
            sumSq += (v - mean) * (v - mean);
        }
        return Math.sqrt(sumSq / (values.size() - 1));
    }
}
