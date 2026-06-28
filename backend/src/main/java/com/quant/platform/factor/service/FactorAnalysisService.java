package com.quant.platform.factor.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.math3.distribution.TDistribution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 因子有效性分析服务
 * 计算 IC (Information Coefficient) / IR (Information Ratio) 等指标
 * IC = Spearman秩相关系数(因子值, 下期收益率)
 * IR = IC均值 / IC标准差
 *
 * 安全：所有接受 factorCode/factorCodes 的方法均通过白名单校验（字母/数字/下划线/横线）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactorAnalysisService {

    /** factorCode 白名单正则（防御 SQL 注入） */
    private static final java.util.regex.Pattern FACTOR_CODE_PATTERN =
            java.util.regex.Pattern.compile("[a-zA-Z0-9_\\-]+");

    private static void checkFactorCode(String factorCode) {
        if (factorCode == null || !FACTOR_CODE_PATTERN.matcher(factorCode).matches()) {
            throw new IllegalArgumentException("Invalid factorCode: " + factorCode);
        }
    }

    private static void checkFactorCodes(List<String> factorCodes) {
        if (factorCodes == null) return;
        for (String fc : factorCodes) {
            checkFactorCode(fc);
        }
    }

    @Autowired(required = false)
    @Qualifier("clickHouseJdbcTemplate")
    private JdbcTemplate clickHouseJdbcTemplate;

    private final JdbcTemplate jdbcTemplate; // MySQL

    @Autowired(required = false)
    private ClickHouseFactorValueService clickHouseFactorValueService;

    /** IC 计算用的未来收益天数（与 FactorIcService 共用配置，默认5） */
    @Value("${quant.factor.ic.forward-return-days:5}")
    private int forwardReturnDays;

    /**
     * 行业映射缓存：symbol → industry
     * 从 ClickHouse stock_info.industry 加载
     */
    private volatile Map<String, String> stockIndustryMap = new HashMap<>();
    private volatile long industryMapLoadTime = 0;
    private static final long INDUSTRY_CACHE_MS = 3600_000L; // 1小时刷新一次

    /**
     * 市值缓存：symbol → market_cap (对数)
     * 从 ClickHouse stock_info.market_cap 加载，用于市值中性化
     */
    private volatile Map<String, Double> stockMarketCapLogMap = new HashMap<>();
    private volatile long marketCapMapLoadTime = 0;
    private static final long MARKETCAP_CACHE_MS = 3600_000L; // 1小时刷新一次

    /**
     * 批量计算多因子 IC/IR
     * @param factorCodes 因子代码列表
     * @param startDate 开始日期
     * @param endDate 结束日期
     * @param forwardDays 前瞻天数（默认5日）
     * @return 每个因子的 IC/IR 统计
     */
    public List<Map<String, Object>> batchCalcIcIr(List<String> factorCodes, String startDate, String endDate, int forwardDays, boolean neutralizeByIndustry, boolean neutralizeByMarketCap, String correlationType, double icThreshold) {
        // 安全校验：factorCode 白名单
        checkFactorCodes(factorCodes);

        List<Map<String, Object>> results = new ArrayList<>();

        for (String factorCode : factorCodes) {
            try {
                Map<String, Object> stat = calcSingleFactorIcIr(factorCode, startDate, endDate, forwardDays, neutralizeByIndustry, neutralizeByMarketCap, correlationType);
                results.add(stat);
            } catch (Exception e) {
                log.error("因子IC/IR计算失败: factorCode={}, error={}", factorCode, e.getMessage());
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("factorCode", factorCode);
                err.put("error", e.getMessage());
                results.add(err);
            }
        }

        // ══════════════════════════════════════════════════════════════════
        //  多因子复合 IC（至少 2 个有效因子才计算）
        // ══════════════════════════════════════════════════════════════════
        List<Map<String, Object>> validResults = results.stream()
            .filter(r -> !r.containsKey("error"))
            .collect(Collectors.toList());
        if (validResults.size() >= 2) {
            try {
                List<Map<String, Object>> composites = computeCompositeIcResults(
                    factorCodes, startDate, endDate, forwardDays,
                    neutralizeByIndustry, neutralizeByMarketCap, correlationType, validResults,
                    icThreshold);
                results.addAll(composites);
            } catch (Exception e) {
                log.warn("[复合IC] 计算失败: {}", e.getMessage());
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
        // 安全校验：factorCode 白名单
        checkFactorCode(factorCode);

        // icTimeline 已包含在 result 中
        return calcSingleFactorIcIr(factorCode, startDate, endDate, forwardDays);
    }

    // ======== 行业中性化辅助方法 ========
    
    
    /**
     * 行业中性化（百分位秩法 → 标准正态映射）
     * 原理：z-score 是行业内单调变换，与 Spearman 秩相关天然冲突。
     * 百分位秩法先将行业内因子值映射到 [0,1] 均匀分布，
     * 再通过 probit 映射到标准正态 N(0,1)，打破行业内单调性。
     * 
     * @param dayFactors 当日因子值列表
     * @param industryMap code → industry 映射
     * @return 中性化后的因子值列表（与输入顺序一致，probit 映射到 N(0,1)）
     */
    private List<Double> neutralizeByIndustry(
            java.util.List<java.util.Map<String, Object>> dayFactors,
            java.util.Map<String, String> industryMap) {

        final int MIN_INDUSTRY_SIZE = 5;
        final double ZSCORE_CAP = 3.0;
        final org.apache.commons.math3.distribution.NormalDistribution normalDist
            = new org.apache.commons.math3.distribution.NormalDistribution();

        // 1. 按行业分组
        Map<String, List<Integer>> indIndices = new LinkedHashMap<>();
        List<Integer> marketIndices = new ArrayList<>();

        for (int i = 0; i < dayFactors.size(); i++) {
            Map<String, Object> f = dayFactors.get(i);
            String sym = (String) f.get("symbol");
            BigDecimal fv = (BigDecimal) f.get("factorVal");
            if (sym == null || fv == null) continue;
            String ind = industryMap.get(sym);
            if (ind != null && !ind.isEmpty()) {
                indIndices.computeIfAbsent(ind, k -> new ArrayList<>()).add(i);
            } else {
                marketIndices.add(i);
            }
        }

        // 2. 小行业（< MIN_INDUSTRY_SIZE）合并到全市场
        for (Iterator<Map.Entry<String, List<Integer>>> it = indIndices.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, List<Integer>> e = it.next();
            if (e.getValue().size() < MIN_INDUSTRY_SIZE) {
                marketIndices.addAll(e.getValue());
                it.remove();
            }
        }

        // 3. 初始化结果数组（先填入原始值作为 fallback）
        double[] result = new double[dayFactors.size()];
        for (int i = 0; i < dayFactors.size(); i++) {
            Map<String, Object> f = dayFactors.get(i);
            BigDecimal fv = (BigDecimal) f.get("factorVal");
            result[i] = (fv != null) ? fv.doubleValue() : 0.0;
        }

        // 4. 行业内百分位秩 → probit
        for (Map.Entry<String, List<Integer>> e : indIndices.entrySet()) {
            List<Integer> indices = e.getValue();
            Integer[] sorted = indices.toArray(new Integer[0]);
            Arrays.sort(sorted, Comparator.comparingDouble(a -> ((BigDecimal) dayFactors.get(a).get("factorVal")).doubleValue()));
            int n = sorted.length;
            for (int rank = 0; rank < n; rank++) {
                int idx = sorted[rank];
                // mid-rank percentile: (rank + 0.5) / n，避免 0 和 1
                double p = (rank + 0.5) / n;
                // 钳制 p 到 [1e-9, 1-1e-9] 防止 probit 溢出
                p = Math.max(1e-9, Math.min(1.0 - 1e-9, p));
                double z = normalDist.inverseCumulativeProbability(p);
                result[idx] = Math.max(-ZSCORE_CAP, Math.min(ZSCORE_CAP, z));
            }
        }

        // 5. 全市场百分位秩 → probit（小行业 / 无行业股票）
        if (!marketIndices.isEmpty()) {
            Integer[] mSorted = marketIndices.toArray(new Integer[0]);
            Arrays.sort(mSorted, (a, b) -> Double.compare(
                ((BigDecimal) dayFactors.get(a).get("factorVal")).doubleValue(),
                ((BigDecimal) dayFactors.get(b).get("factorVal")).doubleValue()
            ));
            int n = mSorted.length;
            for (int rank = 0; rank < n; rank++) {
                int idx = mSorted[rank];
                double p = (rank + 0.5) / n;
                p = Math.max(1e-9, Math.min(1.0 - 1e-9, p));
                double z = normalDist.inverseCumulativeProbability(p);
                result[idx] = Math.max(-ZSCORE_CAP, Math.min(ZSCORE_CAP, z));
            }
        }

        List<Double> out = new ArrayList<>(dayFactors.size());
        for (double v : result) out.add(v);
        return out;
    }

    /**
     * 市值中性化（行业内按市值线性回归取残差）
     * 在行业中性化的基础上，进一步消除大小盘偏差。
     * 大盘股天然有某些因子特征（如低波动、高质量），不剥离的话
     * 因子IC中会混入"大小盘效应"而非真正的alpha。
     * 算法：
     * 1. 按行业分组（复用 industryMap）
     * 2. 组内对 factor = α + β × log(market_cap) + ε 做 OLS 回归
     * 3. 取残差 ε 作为市值中性化后的因子值
     *
     * @param neutralizedFactors 已经过行业中性化的因子值列表（与 dayFactors 同顺序）
     * @param dayFactors         原始因子数据（用于提取 symbol）
     * @param industryMap        code → industry 映射
     * @return 市值中性化后的因子值列表（与输入顺序一致）
     */
    private List<Double> neutralizeByMarketCap(
            List<Double> neutralizedFactors,
            java.util.List<java.util.Map<String, Object>> dayFactors,
            Map<String, String> industryMap) {

        // 加载市值数据
        Map<String, Double> marketCapLogMap = loadMarketCapLogMap();
        if (marketCapLogMap.isEmpty()) {
            log.debug("[市值中性化] 无市值数据，跳过");
            return neutralizedFactors;
        }

        final int MIN_REG_SIZE = 10;  // 最少10只股票才做回归

        // 1. 按行业分组索引
        Map<String, List<Integer>> indIndices = new LinkedHashMap<>();
        List<Integer> marketIndices = new ArrayList<>();
        for (int i = 0; i < dayFactors.size(); i++) {
            String sym = (String) dayFactors.get(i).get("symbol");
            String ind = industryMap.get(sym);
            if (ind != null && !ind.isEmpty()) {
                indIndices.computeIfAbsent(ind, k -> new ArrayList<>()).add(i);
            } else {
                marketIndices.add(i);
            }
        }

        // 2. 初始化结果（fallback = 行业中性化后的值）
        double[] result = new double[dayFactors.size()];
        for (int i = 0; i < dayFactors.size(); i++) {
            result[i] = neutralizedFactors.get(i);
        }

        // 3. 行业内 OLS 回归: factor = α + β × log(mcap) → 取残差
        for (Map.Entry<String, List<Integer>> entry : indIndices.entrySet()) {
            List<Integer> indices = entry.getValue();
            if (indices.size() < MIN_REG_SIZE) continue;

            List<double[]> points = new ArrayList<>();
            for (int idx : indices) {
                String sym = (String) dayFactors.get(idx).get("symbol");
                Double logMcap = marketCapLogMap.get(sym);
                if (logMcap != null && !Double.isNaN(logMcap)) {
                    points.add(new double[]{logMcap, neutralizedFactors.get(idx)});
                }
            }

            if (points.size() < MIN_REG_SIZE) continue;

            // OLS: β = Cov(x,y)/Var(x),  α = mean(y) - β*mean(x)
            double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
            int n = points.size();
            for (double[] p : points) {
                sumX += p[0]; sumY += p[1];
                sumXY += p[0] * p[1];
                sumX2 += p[0] * p[0];
            }
            double meanX = sumX / n, meanY = sumY / n;
            double beta = (sumXY - n * meanX * meanY) / (sumX2 - n * meanX * meanX + 1e-15);
            double alpha = meanY - beta * meanX;

            // 取残差
            for (int idx : indices) {
                String sym = (String) dayFactors.get(idx).get("symbol");
                Double logMcap = marketCapLogMap.get(sym);
                if (logMcap != null) {
                    double predicted = alpha + beta * logMcap;
                    result[idx] = neutralizedFactors.get(idx) - predicted;
                }
            }
        }

        List<Double> out = new ArrayList<>(dayFactors.size());
        for (double v : result) out.add(v);
        return out;
    }

    /**
     * 加载市值缓存（从 CH stock_info.market_cap，存储为对数）
     */
    private synchronized Map<String, Double> loadMarketCapLogMap() {
        long now = System.currentTimeMillis();
        if (!stockMarketCapLogMap.isEmpty() && (now - marketCapMapLoadTime) < MARKETCAP_CACHE_MS) {
            return stockMarketCapLogMap;
        }
        Map<String, Double> newMap = new HashMap<>();
        if (clickHouseJdbcTemplate != null) {
            try {
                String sql = "SELECT code, market_cap FROM stock.stock_info WHERE market_cap IS NOT NULL AND market_cap > 0";
                clickHouseJdbcTemplate.query(sql, (rs) -> {
                    String code = rs.getString("code");
                    double mcap = rs.getDouble("market_cap");
                    if (code != null && mcap > 0) {
                        String c = code.contains(".") ? code.split("\\.")[0] : code;
                        newMap.put(c, Math.log(mcap));
                    }
                });
                log.info("[市值中性化] 加载市值映射完成：{} 只股票有市值数据", newMap.size());
            } catch (Exception e) {
                log.warn("[市值中性化] 加载市值映射失败：{}，将跳过市值中性化", e.getMessage());
            }
        }
        stockMarketCapLogMap = newMap;
        marketCapMapLoadTime = now;
        return stockMarketCapLogMap;
    }

    /**
     * 加载行业映射缓存（从 CH stock_info.industry）
     * 缓存 1 小时，避免每次查询都访问 CH
     */
    private synchronized java.util.Map<String, String> loadIndustryMap() {
        long now = System.currentTimeMillis();
        if (!stockIndustryMap.isEmpty() && (now - industryMapLoadTime) < 3600000L) {
            return stockIndustryMap;
        }
        java.util.Map<String, String> newMap = new java.util.HashMap<>();
        if (clickHouseJdbcTemplate != null) {
            try {
                String sql = "SELECT code, industry FROM stock.stock_info WHERE industry IS NOT NULL AND industry != ''";
                clickHouseJdbcTemplate.query(sql, (rs) -> {
                    String code = rs.getString("code");
                    String ind = rs.getString("industry");
                    if (code != null && ind != null && !ind.isEmpty()) {
                        String c = code.contains(".") ? code.split("\\.")[0] : code;
                        newMap.put(c, ind);
                    }
                });
                log.info("[行业中性化] 加载行业映射完成：{} 只股票有行业分类", newMap.size());
            } catch (Exception e) {
                log.warn("[行业中性化] 加载行业映射失败：{}，将跳过中性化", e.getMessage());
            }
        }
        stockIndustryMap = newMap;
        industryMapLoadTime = now;
        return stockIndustryMap;
    }

    /**

    // ══════════════════════════════════════════════════════════════════════
    //  多因子复合 IC 计算（P0）
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 多因子复合 IC：将多个因子按不同权重方案组合为单一信号，计算其预测力
     * 三种方案：EQW（等权）、ICW（IC绝对值加权）、OPT（逆方差加权）
     */
    private List<Map<String, Object>> computeCompositeIcResults(
            List<String> factorCodes, String startDate, String endDate,
            int forwardDays, boolean neutralizeByIndustry, boolean neutralizeByMarketCap,
            String correlationType, List<Map<String, Object>> individualResults,
            double icThreshold) {

        // ════════════════════════════════════════════════════════════════
        //  Step 0: 因子预筛选 + 信号方向对齐准备
        //  ════════════════════════════════════════════════════════════════
        // 提取各因子 IC 值和方向（sign）
        Map<String, Double> factorIcMap = new LinkedHashMap<>();
        Map<String, Integer> factorSignMap = new LinkedHashMap<>();
        for (Map<String, Object> r : individualResults) {
            String fc = (String) r.get("factorCode");
            double ic = ((Number) r.getOrDefault("icMean", 0)).doubleValue();
            factorIcMap.put(fc, ic);
            factorSignMap.put(fc, ic >= 0 ? 1 : -1); // 信号方向：+1 或 -1
        }

        // 预筛选：只保留 |IC| >= threshold 的因子
        List<String> filteredCodes = factorCodes.stream()
            .filter(fc -> Math.abs(factorIcMap.getOrDefault(fc, 0.0)) >= icThreshold)
            .collect(Collectors.toList());

        if (filteredCodes.size() < 2) {
            log.info("[复合IC] 预筛选后因子数={}，不足2个，跳过复合计算 (threshold={})",
                     filteredCodes.size(), icThreshold);
            return new ArrayList<>();
        }

        log.info("[复合IC] 预筛选: {}/{} 个因子保留 (|IC|>={}), 剔除: {}",
                 filteredCodes.size(), factorCodes.size(), icThreshold,
                 factorCodes.stream()
                     .filter(fc -> Math.abs(factorIcMap.getOrDefault(fc, 0.0)) < icThreshold)
                     .collect(Collectors.toList()));

        // 用筛选后的因子列表替换原始列表
        factorCodes = filteredCodes;
        int nFactorsFinal = factorCodes.size();

        // 各因子信号方向（+1 或 -1），用于复合时对齐
        int[] signs = new int[nFactorsFinal];
        for (int j = 0; j < nFactorsFinal; j++) {
            signs[j] = factorSignMap.getOrDefault(factorCodes.get(j), 1);
        }

        List<Map<String, Object>> composites = new ArrayList<>();
        String inClause = factorCodes.stream()
            .map(c -> "'" + c.replace("'", "''") + "'")
            .collect(Collectors.joining(","));

        // 1. 批量查询所有因子值（一次 CH 查询）
        List<Map<String, Object>> allRows = new ArrayList<>();
        if (clickHouseJdbcTemplate != null) {
            try {
                String sql = String.format("""
                    SELECT symbol, toString(calc_date) AS calc_date, factor_code, factor_val
                    FROM stock.factor_value
                    WHERE factor_code IN (%s) AND calc_date >= '%s' AND calc_date <= '%s'
                      AND factor_val IS NOT NULL
                    ORDER BY calc_date, symbol
                    """, inClause, startDate, endDate);
                allRows = clickHouseJdbcTemplate.query(sql, (rs, rowNum) -> {
                    Map<String, Object> m = new HashMap<>();
                    String symbol = rs.getString("symbol");
                    if (symbol != null && symbol.contains(".")) symbol = symbol.split("\\.")[0];
                    m.put("symbol", symbol);
                    m.put("calcDate", rs.getString("calc_date"));
                    m.put("factorCode", rs.getString("factor_code"));
                    m.put("factorVal", rs.getBigDecimal("factor_val"));
                    return m;
                });
            } catch (Exception e) {
                log.warn("[复合IC] CH 查询失败: {}", e.getMessage());
                return composites;
            }
        }
        if (allRows.isEmpty()) return composites;

        // 2. 分组：date → symbol → (factor → value)
        Map<String, Map<String, Map<String, Double>>> dateSymFactor = new LinkedHashMap<>();
        for (Map<String, Object> row : allRows) {
            String date = (String) row.get("calcDate");
            String sym = (String) row.get("symbol");
            String fc = (String) row.get("factorCode");
            BigDecimal fv = (BigDecimal) row.get("factorVal");
            if (date == null || sym == null || fc == null || fv == null) continue;
            dateSymFactor
                .computeIfAbsent(date, k -> new LinkedHashMap<>())
                .computeIfAbsent(sym, k -> new LinkedHashMap<>())
                .put(fc, fv.doubleValue());
        }

        // 3. 权重：从 individualResults 中提取各因子 IC，用于 ICW / OPT
        //    注意：此处使用 filteredCodes（已筛选）
        Map<String, Double> factorAbsIc = new LinkedHashMap<>();
        Map<String, Double> factorIcStd = new LinkedHashMap<>();
        for (Map<String, Object> r : individualResults) {
            String fc = (String) r.get("factorCode");
            // 只处理被保留的因子
            if (!factorCodes.contains(fc)) continue;
            double ic = ((Number) r.getOrDefault("icMean", 0)).doubleValue();
            double std = ((Number) r.getOrDefault("icStd", 0)).doubleValue();
            factorAbsIc.put(fc, Math.abs(ic));
            factorIcStd.put(fc, Math.max(std, 1e-9));
        }
        double absIcSum = factorAbsIc.values().stream().mapToDouble(d -> d).sum();

        // 三种权重方案（数组长度 = 筛选后因子数）
        double[] weightsEqw = new double[nFactorsFinal];
        double[] weightsIcw = new double[nFactorsFinal];
        double[] weightsOpt = new double[nFactorsFinal];
        double optSum = 0;
        for (int i = 0; i < nFactorsFinal; i++) {
            String fc = factorCodes.get(i);
            weightsEqw[i] = 1.0 / nFactorsFinal;
            weightsIcw[i] = absIcSum > 1e-9 ? factorAbsIc.getOrDefault(fc, 0.0) / absIcSum : 1.0 / nFactorsFinal;
            double invVar = 1.0 / Math.pow(factorIcStd.getOrDefault(fc, 1.0), 2);
            weightsOpt[i] = invVar;
            optSum += invVar;
        }
        for (int i = 0; i < nFactorsFinal; i++) {
            weightsOpt[i] /= Math.max(optSum, 1e-9);
        }

        // 4. 获取价格数据和 forward date map
        Set<String> allSymbols = allRows.stream()
            .map(r -> (String) r.get("symbol")).filter(Objects::nonNull)
            .filter(s -> s.matches("\\d{6}")).collect(Collectors.toSet());
        LocalDate priceEnd = LocalDate.parse(endDate).plusDays(forwardDays * 3L);
        Map<String, Map<String, Double>> codeDatePrice = queryBulkClosePrices(allSymbols, startDate, priceEnd.toString());
        Map<String, String> forwardDateMap = buildForwardDateMap(startDate, priceEnd.toString(), forwardDays);

        // 5. 日度 IC 序列（三种方案）
        List<Double> icEqw = new ArrayList<>();
        List<Double> icIcw = new ArrayList<>();
        List<Double> icOpt = new ArrayList<>();

        for (Map.Entry<String, Map<String, Map<String, Double>>> dateEntry : dateSymFactor.entrySet()) {
            String calcDate = dateEntry.getKey();
            String fwdDate = forwardDateMap.get(calcDate);
            if (fwdDate == null) continue;

            Map<String, Map<String, Double>> symFactors = dateEntry.getValue();
            List<String> validSyms = new ArrayList<>();
            List<double[]> factorVecs = new ArrayList<>();
            List<Double> returns = new ArrayList<>();

            for (Map.Entry<String, Map<String, Double>> se : symFactors.entrySet()) {
                String sym = se.getKey();
                Map<String, Double> facs = se.getValue();
                Map<String, Double> prices = codeDatePrice.get(sym);
                if (prices == null) continue;
                Double currP = prices.get(calcDate);
                Double fwdP = prices.get(fwdDate);
                if (currP == null || fwdP == null || currP <= 0) continue;

                double[] vec = new double[nFactorsFinal];
                int present = 0;
                for (int j = 0; j < nFactorsFinal; j++) {
                    Double v = facs.get(factorCodes.get(j));
                    if (v != null) { vec[j] = v; present++; }
                    else vec[j] = Double.NaN;
                }
                if (present < 2) continue;

                validSyms.add(sym);
                factorVecs.add(vec);
                returns.add((fwdP - currP) / currP);
            }
            if (validSyms.size() < 10) continue;

            // 截面 z-score 标准化（每列独立），并对齐信号方向
            int nStocks = factorVecs.size();
            double[][] zScores = new double[nStocks][nFactorsFinal];
            for (int j = 0; j < nFactorsFinal; j++) {
                double sum = 0; int cnt = 0;
                for (double[] factorVec : factorVecs) {
                    if (!Double.isNaN(factorVec[j])) {
                        sum += factorVec[j];
                        cnt++;
                    }
                }
                if (cnt < 5) { for (int i = 0; i < nStocks; i++) zScores[i][j] = 0; continue; }
                double mean = sum / cnt;
                double var = 0;
                for (int i = 0; i < nStocks; i++) {
                    if (!Double.isNaN(factorVecs.get(i)[j])) {
                        double d = factorVecs.get(i)[j] - mean;
                        var += d * d;
                    }
                }
                double stdv = Math.sqrt(var / (cnt - 1));
                if (stdv < 1e-9) stdv = 1.0;
                int sign = signs[j]; // 信号方向：+1 或 -1
                for (int i = 0; i < nStocks; i++) {
                    if (!Double.isNaN(factorVecs.get(i)[j])) {
                        // z-score 乘以 sign：让所有因子"高值=好股票"方向一致
                        zScores[i][j] = sign * (factorVecs.get(i)[j] - mean) / stdv;
                    } else zScores[i][j] = 0;
                }
            }

            // 三种方案的复合值
            double[] compEqw = new double[nStocks];
            double[] compIcw = new double[nStocks];
            double[] compOpt = new double[nStocks];
            for (int i = 0; i < nStocks; i++) {
                for (int j = 0; j < nFactorsFinal; j++) {
                    compEqw[i] += zScores[i][j] * weightsEqw[j];
                    compIcw[i] += zScores[i][j] * weightsIcw[j];
                    compOpt[i] += zScores[i][j] * weightsOpt[j];
                }
            }

            // 计算 IC
            List<Double> rets = Collections.unmodifiableList(returns);
            double icEqwVal = computeIc(toList(compEqw), rets, correlationType);
            double icIcwVal = computeIc(toList(compIcw), rets, correlationType);
            double icOptVal = computeIc(toList(compOpt), rets, correlationType);
            if (!Double.isNaN(icEqwVal)) icEqw.add(icEqwVal);
            if (!Double.isNaN(icIcwVal)) icIcw.add(icIcwVal);
            if (!Double.isNaN(icOptVal)) icOpt.add(icOptVal);
        }

        // 6. 构建结果（名称加"对齐"标记，表示已做信号方向对齐）
        int usedCount = factorCodes.size(); // 已是筛选后数量
        // 构建实际参与因子详情列表
        List<Map<String, Object>> factorDetailList = new ArrayList<>();
        for (int i = 0; i < nFactorsFinal; i++) {
            String fc = factorCodes.get(i);
            Map<String, Object> fd = new LinkedHashMap<>();
            fd.put("code", fc);
            fd.put("ic", factorIcMap.getOrDefault(fc, 0.0));
            fd.put("sign", signs[i]);
            fd.put("weightEqw", weightsEqw[i]);
            fd.put("weightIcw", weightsIcw[i]);
            fd.put("weightOpt", weightsOpt[i]);
            factorDetailList.add(fd);
        }
        if (!icEqw.isEmpty()) composites.add(buildCompositeResult("COMPOSITE_EQW", "多因子等权✓", icEqw, usedCount, correlationType, factorDetailList));
        if (!icIcw.isEmpty()) composites.add(buildCompositeResult("COMPOSITE_ICW", "多因子|IC|加权✓", icIcw, usedCount, correlationType, factorDetailList));
        if (!icOpt.isEmpty()) composites.add(buildCompositeResult("COMPOSITE_OPT", "多因子逆方差✓", icOpt, usedCount, correlationType, factorDetailList));

        log.info("[复合IC] 计算完成，生成 {} 个复合因子结果", composites.size());
        return composites;
    }

    /** 工具：double[] → List<Double> */
    private static List<Double> toList(double[] arr) {
        List<Double> out = new ArrayList<>(arr.length);
        for (double v : arr) out.add(v);
        return out;
    }

    /** 根据 correlationType 选择 IC 计算方法 */
    private double computeIc(List<Double> x, List<Double> y, String correlationType) {
        return "pearson".equals(correlationType) ? calcPearsonCorrelation(x, y) : calcSpearmanCorrelation(x, y);
    }

    /** 构建复合因子 IC 结果
     *  @param filteredFactors 实际参与组合的因子列表（已通过预筛选），含 code/ic/weight/sign */
    private Map<String, Object> buildCompositeResult(String code, String name, List<Double> icSeries,
            int k, String correlationType, List<Map<String, Object>> filteredFactors) {
        double icMean = icSeries.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double icStd = calcStd(icSeries);
        double ir = icStd > 0 ? icMean / icStd : 0;
        long icPos = icSeries.stream().filter(v -> v > 0).count();
        double wr = 100.0 * icPos / icSeries.size();
        int n = icSeries.size();
        double tStat = icStd > 0 ? icMean / (icStd / Math.sqrt(n)) : 0;
        double pValue = 1.0;
        if (n > 1 && icStd > 0) {
            try {
                TDistribution tDist = new TDistribution(n - 1);
                pValue = 2.0 * (1.0 - tDist.cumulativeProbability(Math.abs(tStat)));
            } catch (Exception ignored) {
            }
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("factorCode", code);
        r.put("factorName", name);
        r.put("composite", true);
        r.put("compositeSize", k);
        r.put("forwardDays", 5);
        r.put("icMean", Math.round(icMean * 10000.0) / 10000.0);
        r.put("icStd", Math.round(icStd * 10000.0) / 10000.0);
        r.put("ir", Math.round(ir * 100.0) / 100.0);
        r.put("tStat", Math.round(tStat * 100.0) / 100.0);
        r.put("pValue", Math.round(pValue * 10000.0) / 10000.0);
        r.put("icWinRate", Math.round(wr * 10.0) / 10.0);
        r.put("sampleDays", icSeries.size());
        r.put("assessment", assessIcIr(icMean, ir));
        r.put("correlationType", correlationType);
        // 实际参与组合的因子详情（含IC、权重、方向）
        r.put("filteredFactors", filteredFactors);
        return r;
    }

    /** IC 有效性评估 */
    private String assessIcIr(double icMean, double ir) {
        if (Math.abs(icMean) >= 0.05 && Math.abs(ir) >= 0.5) return "有效因子";
        if (Math.abs(icMean) >= 0.03 && Math.abs(ir) >= 0.3) return "弱有效";
        return "无效因子";
    }

    /**
     * 单因子 IC/IR 详细计算（原始值，不含中性化）
     * 重载方法，默认 neutralizeByIndustry=false, neutralizeByMarketCap=false
     */
    private Map<String, Object> calcSingleFactorIcIr(String factorCode, String startDate, String endDate, int forwardDays) {
        return calcSingleFactorIcIr(factorCode, startDate, endDate, forwardDays, false, false, "spearman");
    }

    /**
     * 单因子 IC/IR 详细计算
     * @param neutralizeByIndustry 是否做行业中性化
     * @param neutralizeByMarketCap 是否做市值中性化（独立于行业中性化）
     * @param correlationType spearman | pearson
     */
    private Map<String, Object> calcSingleFactorIcIr(String factorCode, String startDate, String endDate, int forwardDays, boolean neutralizeByIndustry, boolean neutralizeByMarketCap, String correlationType) {
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

            // 1. 先过滤出有价格数据的股票
            List<Map<String, Object>> validDayFactors = new ArrayList<>();
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
                validDayFactors.add(f);
                forwardReturns.add((fwdP - currP) / currP);
            }
            if (validDayFactors.size() < 10) continue;

            // 2. 行业中性化 + 市值中性化（独立控制）
            List<Double> factorVals;
            Map<String, String> indMap = neutralizeByIndustry || neutralizeByMarketCap ? loadIndustryMap() : null;
            if (neutralizeByIndustry && indMap != null && !indMap.isEmpty()) {
                factorVals = neutralizeByIndustry(validDayFactors, indMap);
            } else if (neutralizeByIndustry) {
                log.warn("[IC/IR] 行业映射为空，行业中性化退化为原始值");
                factorVals = new ArrayList<>();
                for (Map<String, Object> f : validDayFactors) {
                    BigDecimal fv = (BigDecimal) f.get("factorVal");
                    factorVals.add(fv != null ? fv.doubleValue() : 0.0);
                }
            } else {
                factorVals = new ArrayList<>();
                for (Map<String, Object> f : validDayFactors) {
                    BigDecimal fv = (BigDecimal) f.get("factorVal");
                    factorVals.add(fv != null ? fv.doubleValue() : 0.0);
                }
            }
            // 市值中性化（可在行业中性化之后叠加，也可独立执行）
            if (neutralizeByMarketCap && indMap != null && !indMap.isEmpty()) {
                factorVals = neutralizeByMarketCap(factorVals, validDayFactors, indMap);
            }

            if (factorVals.size() < 10) continue;

            double ic = "pearson".equals(correlationType)
                    ? calcPearsonCorrelation(factorVals, forwardReturns)
                    : calcSpearmanCorrelation(factorVals, forwardReturns);
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
            } catch (Exception ignored) {
            }
        }
        result.put("pValue", Math.round(pValue * 10000.0) / 10000.0);

        result.put("icWinRate", Math.round(icWinRate * 10.0) / 10.0);
        result.put("sampleDays", icSeries.size());
        result.put("totalFactorRows", factorRows.size());
        result.put("icTimeline", icTimeline);
        result.put("neutralized", neutralizeByIndustry);

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

        double[] rankX = calcRank(x);
        double[] rankY = calcRank(y);

        // Pearson correlation on ranks
        double meanRX = Arrays.stream(rankX).average().orElse(0);
        double meanRY = Arrays.stream(rankY).average().orElse(0);

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

    /**
     * Pearson 相关系数 —— 对量值敏感，适合行业中性化后的分析
     */
    private double calcPearsonCorrelation(List<Double> x, List<Double> y) {
        int n = x.size();
        if (n != y.size() || n < 3) return Double.NaN;

        double meanX = 0, meanY = 0;
        for (int i = 0; i < n; i++) {
            meanX += x.get(i);
            meanY += y.get(i);
        }
        meanX /= n;
        meanY /= n;

        double cov = 0, varX = 0, varY = 0;
        for (int i = 0; i < n; i++) {
            double dx = x.get(i) - meanX;
            double dy = y.get(i) - meanY;
            cov += dx * dy;
            varX += dx * dx;
            varY += dy * dy;
        }

        if (varX == 0 || varY == 0) return Double.NaN;
        return cov / Math.sqrt(varX * varY);
    }

    /**
     * 计算排名，正确处理并列值（平均排名法）
     * 例如 [10, 20, 20, 30] → [1, 2.5, 2.5, 4]
     */
    private double[] calcRank(List<Double> values) {
        int n = values.size();
        Integer[] indices = new Integer[n];
        for (int i = 0; i < n; i++) indices[i] = i;

        Arrays.sort(indices, Comparator.comparingDouble(values::get));

        double[] ranks = new double[n];
        int i = 0;
        while (i < n) {
            int j = i;
            // 找并列值组团
            while (j + 1 < n
                    && Double.compare(values.get(indices[j + 1]), values.get(indices[j])) == 0) {
                j++;
            }
            // 平均排名: (起始排名 + 结束排名) / 2 = (i+1 + j+1) / 2
            double avgRank = (i + j + 2) / 2.0;
            for (int k = i; k <= j; k++) {
                ranks[indices[k]] = avgRank;
            }
            i = j + 1;
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
            String splitDate, int forwardDays, boolean neutralizeByIndustry, boolean neutralizeByMarketCap, String correlationType) {

        // 安全校验：factorCode 白名单
        checkFactorCodes(factorCodes);

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
                        factorCode, startDate, endDate, splitDate, forwardDays, neutralizeByIndustry, neutralizeByMarketCap, correlationType);

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
            String splitDate, int forwardDays, boolean neutralizeByIndustry, boolean neutralizeByMarketCap, String correlationType) {

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

            // 1. 先过滤出有价格数据的股票
            List<Map<String, Object>> validDayFactors = new ArrayList<>();
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
                validDayFactors.add(f);
                forwardReturns.add((fwdP - currP) / currP);
            }
            if (validDayFactors.size() < 10) continue;

            // 2. 行业中性化 + 市值中性化（独立控制）
            List<Double> factorVals;
            Map<String, String> indMap = neutralizeByIndustry || neutralizeByMarketCap ? loadIndustryMap() : null;
            if (neutralizeByIndustry && indMap != null && !indMap.isEmpty()) {
                factorVals = neutralizeByIndustry(validDayFactors, indMap);
            } else if (neutralizeByIndustry) {
                log.warn("[分段IC] 行业映射为空，行业中性化退化为原始值");
                factorVals = new ArrayList<>();
                for (Map<String, Object> f : validDayFactors) {
                    BigDecimal fv = (BigDecimal) f.get("factorVal");
                    factorVals.add(fv != null ? fv.doubleValue() : 0.0);
                }
            } else {
                factorVals = new ArrayList<>();
                for (Map<String, Object> f : validDayFactors) {
                    BigDecimal fv = (BigDecimal) f.get("factorVal");
                    factorVals.add(fv != null ? fv.doubleValue() : 0.0);
                }
            }
            // 市值中性化（可在行业中性化之后叠加，也可独立执行）
            if (neutralizeByMarketCap && indMap != null && !indMap.isEmpty()) {
                factorVals = neutralizeByMarketCap(factorVals, validDayFactors, indMap);
            }

            if (factorVals.size() < 10) continue;

            double ic = "pearson".equals(correlationType)
                    ? calcPearsonCorrelation(factorVals, forwardReturns)
                    : calcSpearmanCorrelation(factorVals, forwardReturns);
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

    // ================================================================
    //  P1+P2: 推荐引擎因子IC快照 + 衰减加权
    // ================================================================

    /**
     * 因子IC快照（供推荐引擎使用）
     */
    public static class FactorIcSnapshot {
        public String factorCode;
        public double icMean;         // 衰减加权IC均值
        public double icMeanRaw;      // 原始等权IC均值（用于诊断）
        public double icStd;          // IC标准差
        public double icSign;         // IC符号：+1正向，-1反向（用于方向对齐）
        public int sampleDays;        // 有效样本日数
        public String status;         // KEPT: 保留, DROPPED: |IC|不足, NO_DATA: 无数据
        public String assessment;     // 有效因子/弱有效/无效因子
        public List<Double> icTimeline; // IC时序
        public int halflifeUsed;      // 使用的半衰期

        /** |IC|绝对值（方向对齐后的权重基准） */
        public double absIc() { return Math.abs(icMean); }
    }

    /**
     * 快速因子IC快照 — 供每日推荐前校准因子权重使用
     *
     * @param factorCodes    因子代码列表
     * @param referenceDate  参考日期（推荐日期）
     * @param lookbackDays   回溯天数（默认60）
     * @param icThreshold    IC阈值（|IC|低于此值的因子被剔除）
     * @param halflifeDays   半衰期天数（默认20，0表示等权）
     * @return 因子IC快照映射
     */
    public Map<String, FactorIcSnapshot> quickFactorIcSnapshot(
            List<String> factorCodes, LocalDate referenceDate,
            int lookbackDays, double icThreshold, int halflifeDays) {

        // 安全校验：factorCode 白名单
        checkFactorCodes(factorCodes);

        Map<String, FactorIcSnapshot> snapshots = new LinkedHashMap<>();
        if (factorCodes == null || factorCodes.isEmpty()) return snapshots;

        // 计算起始日期
        LocalDate startDate = referenceDate.minusDays(lookbackDays * 2L); // buffer
        String startDateStr = startDate.toString();
        String endDateStr = referenceDate.toString();

        for (String fc : factorCodes) {
            try {
                FactorIcSnapshot snapshot = computeSnapshot(fc, startDateStr, endDateStr,
                        forwardReturnDays, false, "spearman", halflifeDays, icThreshold);
                snapshots.put(fc, snapshot);
            } catch (Exception e) {
                log.warn("[IC快照] 因子 {} 计算失败: {}", fc, e.getMessage());
                FactorIcSnapshot err = new FactorIcSnapshot();
                err.factorCode = fc;
                err.status = "NO_DATA";
                err.icMean = 0;
                err.icSign = 0;
                snapshots.put(fc, err);
            }
        }

        return snapshots;
    }

    /**
     * 计算单个因子IC快照（含衰减加权）
     */
    private FactorIcSnapshot computeSnapshot(String factorCode, String startDate, String endDate,
                                              int forwardDays, boolean neutralizeByIndustry,
                                              String correlationType, int halflifeDays, double icThreshold) {

        // 1. 获取IC时序
        List<Double> icTimeline = getIcTimeline(factorCode, startDate, endDate, forwardDays,
                neutralizeByIndustry, correlationType);
        if (icTimeline.isEmpty()) {
            FactorIcSnapshot s = new FactorIcSnapshot();
            s.factorCode = factorCode;
            s.status = "NO_DATA";
            s.icMean = 0;
            s.icSign = 0;
            return s;
        }

        // 2. 计算衰减加权IC均值
        double decayIcMean;
        if (halflifeDays > 0) {
            decayIcMean = decayWeightedMean(icTimeline, halflifeDays);
        } else {
            decayIcMean = icTimeline.stream().mapToDouble(d -> d).average().orElse(0);
        }
        double rawIcMean = icTimeline.stream().mapToDouble(d -> d).average().orElse(0);

        // 3. 计算标准差
        double icStd = calcStd(icTimeline);

        // 4. 阈值判断
        FactorIcSnapshot s = new FactorIcSnapshot();
        s.factorCode = factorCode;
        s.icMean = decayIcMean;
        s.icMeanRaw = rawIcMean;
        s.icStd = icStd;
        s.sampleDays = icTimeline.size();
        s.icTimeline = icTimeline;
        s.halflifeUsed = halflifeDays;

        if (Math.abs(decayIcMean) < icThreshold) {
            s.status = "DROPPED";
            s.icSign = decayIcMean >= 0 ? 1 : -1;
        } else {
            s.status = "KEPT";
            s.icSign = decayIcMean >= 0 ? 1 : -1;
        }

        // 5. 评估
        s.assessment = assessIcIr(Math.abs(decayIcMean),
                icStd > 1e-9 ? Math.abs(decayIcMean) / icStd : 0);

        return s;
    }

    /**
     * 获取IC时间序列（单因子，简化版，不返回完整统计）
     * 复用 clickHouseFactorValueService 和收益数据
     */
    private List<Double> getIcTimeline(String factorCode, String startDate, String endDate,
                                        int forwardDays, boolean neutralizeByIndustry,
                                        String correlationType) {
        // 复用 calcSingleFactorIcIr，提取 icTimeline
        try {
            Map<String, Object> result = calcSingleFactorIcIr(factorCode, startDate, endDate,
                    forwardDays, neutralizeByIndustry, false, correlationType);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> timeline = (List<Map<String, Object>>) result.get("icTimeline");
            if (timeline == null) return Collections.emptyList();

            List<Double> ics = new ArrayList<>();
            for (Map<String, Object> pt : timeline) {
                Object icVal = pt.get("ic");
                if (icVal instanceof Number) {
                    ics.add(((Number) icVal).doubleValue());
                }
            }
            return ics;
        } catch (Exception e) {
            log.debug("[IC时序] 因子{}获取失败: {}", factorCode, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 衰减加权均值：Σ(IC_t × 2^(-t/halflife)) / Σ(2^(-t/halflife))
     * t=0 是最新（最后一个），t=N-1 是最远（第一个）
     */
    public static double decayWeightedMean(List<Double> values, int halflifeDays) {
        if (values == null || values.isEmpty()) return 0;
        int n = values.size();
        double sumWeighted = 0, sumWeights = 0;
        for (int i = 0; i < n; i++) {
            // offset: 0=latest, n-1=oldest
            int offset = n - 1 - i;
            double weight = Math.pow(2, -offset / (double) halflifeDays);
            sumWeighted += values.get(i) * weight;
            sumWeights += weight;
        }
        return sumWeights > 0 ? sumWeighted / sumWeights : 0;
    }

    /**
     * 动态半衰期：根据市场波动率自适应
     * HIGH vol → 短半衰(10天) 更快适应
     * LOW vol  → 长半衰(30天) 更稳定
     */
    public static int adaptiveHalflife(double volatilityPercentile) {
        if (volatilityPercentile >= 0.7) return 10;  // 高波动：快适应
        if (volatilityPercentile >= 0.4) return 20;  // 中波动：默认
        return 30;  // 低波动：稳定
    }
}
