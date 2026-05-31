package com.quant.platform.backtest.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.backtest.domain.BacktestTask;
import com.quant.platform.common.exception.BusinessException;
import com.quant.platform.config.ClickHouseConfig;
import com.quant.platform.strategy.domain.StrategyDefinition;
import com.quant.platform.strategy.mapper.StrategyDefinitionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.math3.stat.regression.OLSMultipleLinearRegression;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 因子风格归因服务（Factor-Based Style Attribution）
 * <p>
 * 将策略超额收益对策略配置的因子做多元回归（跟随策略 factorConfigJson）：
 * <pre>
 *   R_strategy - R_benchmark = α + Σ βᵢ×Fᵢ + ε
 * </pre>
 * <p>
 * 因子日收益率 = 多空组合收益（Top 20% 等权 − Bottom 20% 等权），
 * 覆盖 A股全市场（来自 ClickHouse factor_value 表）。
 * <p>
 * 适用场景：高换手率 / 因子驱动 / 量化选股策略（Brinson 行业归因不适用时）。
 * <p>
 * 因子集来源：优先从策略 factorConfigJson 读取；无配置时使用默认4因子。
 *
 * @see BrinsonAttributionService
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactorStyleAttributionService {

    private final ClickHouseConfig clickHouseConfig;
    private final ObjectMapper objectMapper;
    private final StrategyDefinitionMapper strategyMapper;
    private final JdbcTemplate jdbcTemplate;

    /** 无配置时的默认因子（向后兼容） */
    private static final List<FactorDef> DEFAULT_FACTORS = List.of(
            new FactorDef("MOM20", "动量", "20日动量 — 追涨杀跌收益"),
            new FactorDef("VOL20", "波动率", "20日波动率 — 高波动股短期溢价"),
            new FactorDef("SIZE", "市值", "总市值 — 小盘股溢价"),
            new FactorDef("TURN20", "换手率", "20日换手率 — 流动性溢价")
    );

    private static final int QUINTILE = 5;          // 分5组，Top 20% vs Bottom 20%
    private static final double MIN_DATA_RATIO = 0.3; // 单日最少数据比例才计算因子收益

    /**
     * 因子定义
     */
    private record FactorDef(String code, String name, String description) {}

    /**
     * 单日因子收益（多空组合）
     */
    private record FactorDailyReturn(LocalDate date, Map<String, Double> factorReturns) {}

    /**
     * 策略特征（用于前端自动匹配归因方案）
     */
    public record StrategyCharacteristics(
            double avgDailyTurnover,      // 日均换手率（单向）
            double avgHoldingDays,        // 平均持仓天数
            double industryConcentration,  // 行业集中度（HHI）
            String recommendedModel,      // "FACTOR" | "BRINSON"
            String reason
    ) {}

    // ════════════════════════════════════════════════════════════════
    // 公开接口
    // ════════════════════════════════════════════════════════════════

    /**
     * 执行因子风格归因
     * <p>
     * 因子集来自策略的 factorConfigJson；无配置时使用默认4因子。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> compute(BacktestTask task,
                                       String positionHistoryJson,
                                       String equityCurveJson,
                                       String benchmarkCurveJson) {
        if (positionHistoryJson == null || positionHistoryJson.isBlank()) {
            throw new BusinessException("持仓历史数据为空，无法进行因子风格归因");
        }

        List<Map<String, Object>> positionHistory;
        List<Map<String, Object>> equityCurve;
        List<Map<String, Object>> benchmarkCurve;
        try {
            positionHistory = objectMapper.readValue(positionHistoryJson, List.class);
            equityCurve = objectMapper.readValue(equityCurveJson != null ? equityCurveJson : "[]", List.class);
            benchmarkCurve = objectMapper.readValue(benchmarkCurveJson != null ? benchmarkCurveJson : "[]", List.class);
        } catch (Exception e) {
            throw new BusinessException("数据解析失败: " + e.getMessage());
        }

        if (positionHistory.isEmpty()) throw new BusinessException("持仓历史为空");

        // 1. 构建日期→策略净值 & 基准净值映射
        Map<LocalDate, Double> stratNav = buildNavMap(equityCurve);
        Map<LocalDate, Double> benchNav = buildNavMap(benchmarkCurve);
        if (stratNav.isEmpty()) throw new BusinessException("净值曲线数据为空");

        // 2. 计算每日策略超额收益
        List<LocalDate> sortedDates = new ArrayList<>(stratNav.keySet());
        sortedDates.sort(Comparator.naturalOrder());

        List<DailyExcess> dailyExcessList = new ArrayList<>();
        for (int i = 1; i < sortedDates.size(); i++) {
            LocalDate d = sortedDates.get(i);
            LocalDate prev = sortedDates.get(i - 1);
            double stratRet = stratNav.get(d) / stratNav.get(prev) - 1;

            Double bNav = benchNav.get(d);
            Double bNavPrev = benchNav.get(prev);
            double benchRet = (bNav != null && bNavPrev != null && bNavPrev > 0)
                    ? bNav / bNavPrev - 1 : 0;

            dailyExcessList.add(new DailyExcess(d, stratRet - benchRet));
        }

        // 3. 加载策略因子集
        List<FactorDef> factors = loadStrategyFactors(task.getStrategyId());
        log.info("因子风格归因使用的因子集: {} (共{}个因子)",
                factors.stream().map(FactorDef::code).collect(Collectors.joining(",")),
                factors.size());

        // 4. 获取回测期间的日期范围
        LocalDate minDate = sortedDates.getFirst();
        LocalDate maxDate = sortedDates.getLast();

        // 5. 从 ClickHouse 批量加载因子值 → 计算每日因子收益
        Map<LocalDate, Map<String, Double>> factorDailyReturns
                = computeFactorDailyReturns(minDate, maxDate, factors);

        // 6. 对齐日期：取 dailyExcessList 和 factorDailyReturns 的交集
        List<DailyExcess> alignedExcess = new ArrayList<>();
        List<double[]> alignedFactors = new ArrayList<>();

        for (DailyExcess de : dailyExcessList) {
            Map<String, Double> fr = factorDailyReturns.get(de.date);
            if (fr == null) continue;

            // 需要所有因子都有值
            double[] row = new double[factors.size()];
            boolean allPresent = true;
            for (int f = 0; f < factors.size(); f++) {
                Double v = fr.get(factors.get(f).code);
                if (v == null || Double.isNaN(v)) { allPresent = false; break; }
                row[f] = v;
            }
            if (!allPresent) continue;

            alignedExcess.add(de);
            alignedFactors.add(row);
        }

        int n = alignedExcess.size();
        if (n < factors.size() + 5) {
            throw new BusinessException(
                    String.format("有效数据点不足：%d天（需要≥%d天才能回归），因子日收益数据可能覆盖不足",
                            n, factors.size() + 5));
        }

        // 7. OLS 回归
        RegressionResult regResult = runOLS(alignedExcess, alignedFactors, n, factors.size());

        // 8. 计算各因子贡献（β_f × 累计因子收益）
        List<Map<String, Object>> factorContributions = new ArrayList<>();
        double totalFactorReturn = 0; // Σ(β_f × total_factor_ret)

        for (int f = 0; f < factors.size(); f++) {
            FactorDef fd = factors.get(f);
            double beta = regResult.betas[f];
            double tStat = regResult.tStats[f];
            boolean significant = Math.abs(tStat) >= 1.96;

            // 累计因子收益（整个回测期间因子多空组合的总收益）
            double cumFactorRet = 0;
            for (double[] row : alignedFactors) cumFactorRet += row[f];

            double contribution = beta * cumFactorRet; // β × 总因子收益
            totalFactorReturn += contribution;

            Map<String, Object> fc = new LinkedHashMap<>();
            fc.put("factorCode", fd.code);
            fc.put("factorName", fd.name);
            fc.put("description", fd.description);
            fc.put("beta", round4(beta));
            fc.put("tStat", round4(tStat));
            fc.put("significant", significant);
            fc.put("totalFactorReturn", round4(cumFactorRet));      // 因子多空组合总收益
            fc.put("annualizedFactorReturn", round4(cumFactorRet / n * 252)); // 年化因子收益
            fc.put("contribution", round4(contribution));            // β × 总因子收益
            fc.put("dailyAlpha", round4(regResult.alpha));
            fc.put("rSquared", round4(regResult.rSquared));
            fc.put("adjRSquared", round4(regResult.adjRSquared));

            factorContributions.add(fc);
        }

        // 按贡献绝对值排序
        factorContributions.sort((a, b) ->
                Double.compare(Math.abs(((Number) b.get("contribution")).doubleValue()),
                               Math.abs(((Number) a.get("contribution")).doubleValue())));

        // 总超额收益
        double totalExcess = alignedExcess.stream().mapToDouble(de -> de.excess).sum();
        // 残差 = 总超额 - Σ(因子贡献)
        double residual = totalExcess - totalFactorReturn;
        double explanationRatio = Math.abs(totalExcess) > 1e-8
                ? 1 - Math.abs(residual) / Math.abs(totalExcess) : 0;

        // 9. 分期间归因（按 rebalance 期间分解）
        List<Map<String, Object>> periodContributions = computePeriodContributions(
                positionHistory, alignedExcess, alignedFactors, regResult, stratNav, factors);

        // 10. 汇总结果
        String factorNames = factors.stream().map(f -> f.name + "(" + f.code + ")")
                .collect(Collectors.joining(" / "));
        String modelDescription = factors.size() == 1
                ? "因子风格归因（单因子: " + factorNames + "）"
                : "因子风格归因 — " + factorNames;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", task.getId());
        result.put("model", "FactorStyle");
        result.put("modelDescription", modelDescription);
        result.put("observationDays", n);
        result.put("factorCount", factors.size());

        // 因子定义
        List<Map<String, Object>> factorDefs = factors.stream().map(fd -> {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("code", fd.code);
            d.put("name", fd.name);
            d.put("description", fd.description);
            return d;
        }).collect(Collectors.toList());
        result.put("factors", factorDefs);

        // 因子贡献
        result.put("factorContributions", factorContributions);

        // 回归细节
        Map<String, Object> regressionDetail = new LinkedHashMap<>();
        regressionDetail.put("alpha", round4(regResult.alpha));
        regressionDetail.put("annualizedAlpha", round4(regResult.alpha * 252));
        regressionDetail.put("rSquared", round4(regResult.rSquared));
        regressionDetail.put("adjRSquared", round4(regResult.adjRSquared));
        regressionDetail.put("fStatistic", round4(regResult.fStatistic));
        result.put("regressionDetail", regressionDetail);

        // 汇总
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalExcessReturn", round4(totalExcess));
        summary.put("totalFactorContribution", round4(totalFactorReturn));
        summary.put("residual", round4(residual));
        summary.put("explanationRatio", round4(explanationRatio));
        result.put("summary", summary);

        // 按期间归因
        result.put("periodContributions", periodContributions);

        log.info("因子风格归因完成: taskId={}, 因子数={}, R²={}, 解释力={}, α/d={}",
                task.getId(), factors.size(), round4(regResult.rSquared), round4(explanationRatio), round4(regResult.alpha));

        return result;
    }

    /**
     * 检测策略特征 — 用于自动匹配归因方案
     */
    @SuppressWarnings("unchecked")
    public StrategyCharacteristics detectCharacteristics(BacktestTask task,
                                                         String positionHistoryJson) {
        if (positionHistoryJson == null || positionHistoryJson.isBlank()) {
            return new StrategyCharacteristics(0, 0, 0, "FACTOR",
                    "持仓数据为空，默认使用因子风格归因");
        }

        List<Map<String, Object>> positionHistory;
        try {
            positionHistory = objectMapper.readValue(positionHistoryJson, List.class);
        } catch (Exception e) {
            return new StrategyCharacteristics(0, 0, 0, "FACTOR",
                    "持仓数据解析失败");
        }
        if (positionHistory.size() < 2) {
            return new StrategyCharacteristics(0, 0, 0, "FACTOR",
                    "持仓期数不足，默认使用因子风格归因");
        }

        // 计算日均换手率
        double totalTurnover = 0;
        int turnoverPeriods = 0;
        // 计算行业集中度
        double totalHHI = 0;
        int hhiPeriods = 0;

        for (int i = 0; i < positionHistory.size(); i++) {
            Map<String, Object> snap = positionHistory.get(i);
            Map<String, Object> positions = (Map<String, Object>) snap.get("positions");
            if (positions == null || positions.isEmpty()) continue;

            // 换手率：本期 vs 上期权重变化
            if (i > 0) {
                Map<String, Object> prevPositions = (Map<String, Object>) positionHistory.get(i - 1).get("positions");
                if (prevPositions != null) {
                    double periodTurnover = 0;
                    Set<String> allSymbols = new HashSet<>();
                    allSymbols.addAll(prevPositions.keySet());
                    allSymbols.addAll(positions.keySet());
                    for (String symbol : allSymbols) {
                        double prevW = prevPositions.containsKey(symbol)
                                ? ((Number) prevPositions.get(symbol)).doubleValue() : 0;
                        double currW = ((Number) positions.getOrDefault(symbol, 0)).doubleValue();
                        periodTurnover += Math.abs(currW - prevW);
                    }
                    totalTurnover += periodTurnover / 2.0;
                    turnoverPeriods++;
                }
            }

            // HHI
            double sumW = 0;
            double sumW2 = 0;
            for (Object w : positions.values()) {
                double weight = ((Number) w).doubleValue();
                sumW += weight;
                sumW2 += weight * weight;
            }
            if (sumW > 0) {
                totalHHI += sumW2 / (sumW * sumW);
                hhiPeriods++;
            }
        }

        double avgTurnover = turnoverPeriods > 0 ? totalTurnover / turnoverPeriods : 0;
        double avgHHI = hhiPeriods > 0 ? totalHHI / hhiPeriods : 0;
        int periodCount = positionHistory.size();
        double avgHoldingDays = 0;
        if (periodCount >= 2 && avgTurnover > 0.001) {
            String startStr = (String) positionHistory.getFirst().get("date");
            String endStr = (String) positionHistory.get(periodCount - 1).get("date");
            try {
                long days = LocalDate.parse(endStr).toEpochDay() - LocalDate.parse(startStr).toEpochDay();
                double rebalanceInterval = (double) days / (periodCount - 1);
                avgHoldingDays = rebalanceInterval / Math.max(avgTurnover, 0.01);
                avgHoldingDays = Math.min(avgHoldingDays, days);
            } catch (Exception ignored) {}
        }

        // 决策逻辑
        boolean highTurnover = avgTurnover > 0.5 || (avgHoldingDays > 0 && avgHoldingDays < 5);
        boolean highConcentration = avgHHI > 0.3;

        String model;
        String reason;
        if (highTurnover && !highConcentration) {
            model = "FACTOR";
            reason = String.format("日均换手率 %.0f%%, 平均持仓约 %.1f天 — 收益来源主要在因子暴露维度，行业归因不适用",
                    avgTurnover * 100, avgHoldingDays);
        } else if (highConcentration && !highTurnover) {
            model = "BRINSON";
            reason = String.format("行业集中度(HHI) %.2f, 日均换手率 %.0f%% — 适合行业层面的归因分析",
                    avgHHI, avgTurnover * 100);
        } else if (highTurnover) {
            model = "FACTOR";
            reason = String.format("高换手(%.0f%%)+高集中度(HHI=%.2f) — 优先用因子归因，Brinson可能辅助",
                    avgTurnover * 100, avgHHI);
        } else {
            model = "BRINSON";
            reason = String.format("低频策略(换手 %.0f%%) — 行业归因适用", avgTurnover * 100);
        }

        return new StrategyCharacteristics(
                round4(avgTurnover), round4(avgHoldingDays), round4(avgHHI), model, reason);
    }

    // ════════════════════════════════════════════════════════════════
    // 因子加载
    // ════════════════════════════════════════════════════════════════

    /**
     * 从策略 factorConfigJson 读取因子列表，解析为 FactorDef。
     * 无配置时回退到默认4因子。
     */
    private List<FactorDef> loadStrategyFactors(Long strategyId) {
        if (strategyId == null) {
            log.info("无策略ID，使用默认因子集");
            return DEFAULT_FACTORS;
        }

        StrategyDefinition strategy = strategyMapper.selectById(strategyId);
        if (strategy == null || strategy.getFactorConfigJson() == null
                || strategy.getFactorConfigJson().isBlank()) {
            log.info("策略 {} 无 factorConfigJson，使用默认因子集", strategyId);
            return DEFAULT_FACTORS;
        }

        List<String> factorCodes;
        try {
            // 解析 factorConfigJson: {"factors":[{"code":"TURN20","weight":1.0}]}
            List<Map<String, Object>> factorList = objectMapper.readValue(
                    strategy.getFactorConfigJson(),
                    new TypeReference<List<Map<String, Object>>>() {});
            if (factorList == null || factorList.isEmpty()) {
                factorCodes = List.of();
            } else {
                factorCodes = factorList.stream()
                        .map(m -> (String) m.get("code"))
                        .filter(Objects::nonNull)
                        .filter(c -> !c.isBlank())
                        .distinct()
                        .collect(Collectors.toList());
            }
        } catch (Exception e) {
            // 可能不是数组而是对象 {"factors": [...]}
            try {
                Map<String, Object> root = objectMapper.readValue(
                        strategy.getFactorConfigJson(), Map.class);
                Object factorsObj = root.get("factors");
                if (factorsObj instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> factorList = (List<Map<String, Object>>) factorsObj;
                    factorCodes = factorList.stream()
                            .map(m -> (String) m.get("code"))
                            .filter(Objects::nonNull)
                            .filter(c -> !c.isBlank())
                            .distinct()
                            .collect(Collectors.toList());
                } else {
                    factorCodes = List.of();
                }
            } catch (Exception e2) {
                log.warn("解析策略 {} 的 factorConfigJson 失败: {}", strategyId, e2.getMessage());
                return DEFAULT_FACTORS;
            }
        }

        if (factorCodes.isEmpty()) {
            log.info("策略 {} 因子配置为空，使用默认因子集", strategyId);
            return DEFAULT_FACTORS;
        }

        // 加载因子名称
        Map<String, String> nameMap = loadFactorNames(factorCodes);

        List<FactorDef> result = new ArrayList<>();
        for (String code : factorCodes) {
            String name = nameMap.getOrDefault(code, code);
            result.add(new FactorDef(code, name, code + "因子"));
        }

        log.info("策略 {} 加载到 {} 个因子: {}", strategyId, result.size(),
                result.stream().map(FactorDef::code).collect(Collectors.joining(",")));
        return result;
    }

    /**
     * 批量加载因子名称
     */
    private Map<String, String> loadFactorNames(List<String> codes) {
        if (codes.isEmpty()) return Map.of();
        Map<String, String> map = new LinkedHashMap<>();
        try {
            String inClause = codes.stream().map(c -> "'" + c + "'").collect(Collectors.joining(","));
            String sql = "SELECT factor_code, factor_name FROM factor_definition WHERE factor_code IN (" + inClause + ")";
            jdbcTemplate.query(sql, (rs) -> {
                map.put(rs.getString("factor_code"), rs.getString("factor_name"));
            });
        } catch (Exception e) {
            log.warn("加载因子名称失败: {}", e.getMessage());
        }
        return map;
    }

    // ════════════════════════════════════════════════════════════════
    // 内部方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 计算每日因子收益（多空组合：Top 20% 等权收益 − Bottom 20% 等权收益）
     */
    private Map<LocalDate, Map<String, Double>> computeFactorDailyReturns(
            LocalDate startDate, LocalDate endDate, List<FactorDef> factors) {

        if (!clickHouseConfig.isEnabled()) {
            throw new BusinessException("ClickHouse 不可用，因子风格归因需要 CH 因子数据");
        }

        String factorList = factors.stream().map(f -> "'" + f.code + "'")
                .collect(Collectors.joining(","));

        String sql = String.format("""
                SELECT fv.calc_date, fv.factor_code,
                       replaceRegexpOne(fv.symbol, '\\\\.[A-Z]+$', '') AS code,
                       fv.factor_val,
                       sd.close_price / sd.pre_close - 1 AS daily_ret
                FROM (SELECT symbol, calc_date, factor_code, factor_val
                      FROM stock.factor_value FINAL) AS fv
                INNER JOIN (SELECT code, trade_date, close_price, pre_close
                            FROM stock.stock_daily FINAL) AS sd
                  ON replaceRegexpOne(fv.symbol, '\\\\.[A-Z]+$', '') = sd.code
                  AND fv.calc_date = sd.trade_date
                WHERE fv.factor_code IN (%s)
                  AND fv.calc_date >= '%s'
                  AND fv.calc_date <= '%s'
                  AND fv.factor_val IS NOT NULL
                  AND sd.pre_close > 0
                  AND sd.close_price > 0
                ORDER BY fv.calc_date, fv.factor_code, fv.factor_val
                """, factorList, startDate, endDate);

        // date → factor_code → List of {factor_val, daily_ret}
        Map<LocalDate, Map<String, List<double[]>>> rawData = new LinkedHashMap<>();

        try (Connection conn = DriverManager.getConnection(clickHouseConfig.getJdbcUrl());
             Statement stmt = conn.createStatement()) {

            stmt.setFetchSize(50000);
            try (ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    LocalDate d = rs.getDate("calc_date").toLocalDate();
                    String factorCode = rs.getString("factor_code");
                    String code = rs.getString("code");

                    // 排除北交所(code 以 8 开头)、B 股(2 开头）、ST等
                    if (code.startsWith("8") || code.startsWith("4") || code.startsWith("2")) continue;

                    double factorVal = rs.getDouble("factor_val");
                    double dailyRet = rs.getDouble("daily_ret");
                    if (rs.wasNull() || Double.isNaN(dailyRet)) continue;

                    rawData.computeIfAbsent(d, k -> new HashMap<>())
                            .computeIfAbsent(factorCode, k -> new ArrayList<>())
                            .add(new double[]{factorVal, dailyRet});
                }
            }
        } catch (Exception e) {
            log.error("CH 因子收益查询失败: {}", e.getMessage(), e);
            throw new BusinessException("ClickHouse 因子数据查询失败: " + e.getMessage());
        }

        if (rawData.isEmpty()) {
            throw new BusinessException("CH 中无因子收益数据，请确认 factor_value 和 stock_daily 表有交集");
        }

        // 对每个因子每天：按 factor_val 排序 → 分5组 → Top 20% 等权收益 − Bottom 20% 等权收益
        Map<LocalDate, Map<String, Double>> result = new LinkedHashMap<>();
        int minTotalStocks = 200;

        for (Map.Entry<LocalDate, Map<String, List<double[]>>> dateEntry : rawData.entrySet()) {
            LocalDate date = dateEntry.getKey();
            Map<String, List<double[]>> factorData = dateEntry.getValue();
            Map<String, Double> dayResult = new LinkedHashMap<>();
            boolean dayValid = false;

            for (FactorDef fd : factors) {
                List<double[]> rows = factorData.get(fd.code);
                if (rows == null || rows.size() < minTotalStocks) continue;

                rows.sort(Comparator.comparingDouble(a -> a[0]));
                int n = rows.size();
                int qSize = n / QUINTILE;
                if (qSize < 10) continue;

                double topReturn = 0;
                for (int i = n - qSize; i < n; i++) topReturn += rows.get(i)[1];
                topReturn /= qSize;

                double bottomReturn = 0;
                for (int i = 0; i < qSize; i++) bottomReturn += rows.get(i)[1];
                bottomReturn /= qSize;

                double factorReturn = topReturn - bottomReturn;
                dayResult.put(fd.code, round4(factorReturn));
                dayValid = true;
            }

            if (dayValid) result.put(date, dayResult);
        }

        log.info("因子日收益计算完成: 覆盖 {} 个交易日 ({} ~ {})",
                result.size(), startDate, endDate);
        return result;
    }

    /**
     * OLS 多元回归
     */
    private RegressionResult runOLS(List<DailyExcess> excess, List<double[]> factors, int n, int k) {
        double[] y = new double[n];
        double[][] x = new double[n][k];

        for (int i = 0; i < n; i++) {
            y[i] = excess.get(i).excess;
            x[i] = factors.get(i);
        }

        OLSMultipleLinearRegression ols = new OLSMultipleLinearRegression();
        ols.newSampleData(y, x);

        double[] betas = ols.estimateRegressionParameters(); // [α, β₁, β₂, ...]
        double[] residuals = ols.estimateResiduals();
        double[][] covarBeta = ols.estimateRegressionParametersVariance();

        double alpha = betas[0];
        double[] betaOnly = Arrays.copyOfRange(betas, 1, betas.length);

        // t-stat
        double[] tStats = new double[k];
        for (int f = 0; f < k; f++) {
            double se = Math.sqrt(Math.abs(covarBeta[f + 1][f + 1]));
            tStats[f] = se > 1e-10 ? betaOnly[f] / se : 0;
        }

        // R²
        double rSquared = ols.calculateRSquared();
        double adjRSquared = ols.calculateAdjustedRSquared();

        // F statistic
        double sse = 0, ssr = 0;
        double yMean = Arrays.stream(y).average().orElse(0);
        for (int i = 0; i < n; i++) {
            sse += residuals[i] * residuals[i];
            double pred = alpha;
            for (int f = 0; f < k; f++) pred += betaOnly[f] * x[i][f];
            ssr += (pred - yMean) * (pred - yMean);
        }
        double fStatistic = sse > 1e-10 ? (ssr / k) / (sse / (n - k - 1)) : 0;

        return new RegressionResult(alpha, betaOnly, tStats, rSquared, adjRSquared, fStatistic);
    }

    /**
     * 按 rebalance 期间分解因子贡献
     */
    private List<Map<String, Object>> computePeriodContributions(
            List<Map<String, Object>> positionHistory,
            List<DailyExcess> alignedExcess,
            List<double[]> alignedFactors,
            RegressionResult regResult,
            Map<LocalDate, Double> stratNav,
            List<FactorDef> factors) {

        List<Map<String, Object>> result = new ArrayList<>();

        // 建立日期→索引映射
        Map<LocalDate, Integer> dateToIdx = new HashMap<>();
        for (int i = 0; i < alignedExcess.size(); i++) {
            dateToIdx.put(alignedExcess.get(i).date, i);
        }

        for (int i = 0; i < positionHistory.size(); i++) {
            Map<String, Object> snap = positionHistory.get(i);
            String startDate = (String) snap.get("date");
            String endDate;
            if (i + 1 < positionHistory.size()) {
                endDate = (String) positionHistory.get(i + 1).get("date");
            } else {
                endDate = new ArrayList<>(stratNav.keySet()).get(stratNav.size() - 1).toString();
            }

            LocalDate start = LocalDate.parse(startDate);
            LocalDate end = LocalDate.parse(endDate);

            double periodExcess = 0;
            double[] periodFactorRets = new double[factors.size()];

            for (DailyExcess de : alignedExcess) {
                if (de.date.isAfter(start) && !de.date.isAfter(end)) {
                    periodExcess += de.excess;
                    Integer idx = dateToIdx.get(de.date);
                    if (idx != null && idx < alignedFactors.size()) {
                        double[] factorRow = alignedFactors.get(idx);
                        for (int f = 0; f < factors.size() && f < factorRow.length; f++) {
                            periodFactorRets[f] += factorRow[f];
                        }
                    }
                }
            }

            Map<String, Object> period = new LinkedHashMap<>();
            period.put("period", startDate + " ~ " + endDate);
            period.put("startDate", startDate);
            period.put("endDate", endDate);
            period.put("excessReturn", round4(periodExcess));

            double[] periodContributions = new double[factors.size()];
            double periodTotalContrib = 0;
            for (int f = 0; f < factors.size(); f++) {
                periodContributions[f] = regResult.betas[f] * periodFactorRets[f];
                periodTotalContrib += periodContributions[f];
            }

            List<Map<String, Object>> factorBreakdown = new ArrayList<>();
            for (int f = 0; f < factors.size(); f++) {
                Map<String, Object> fb = new LinkedHashMap<>();
                fb.put("factorCode", factors.get(f).code);
                fb.put("factorName", factors.get(f).name);
                fb.put("contribution", round4(periodContributions[f]));
                fb.put("factorReturn", round4(periodFactorRets[f]));
                factorBreakdown.add(fb);
            }
            period.put("factorBreakdown", factorBreakdown);
            period.put("totalFactorContrib", round4(periodTotalContrib));
            period.put("residual", round4(periodExcess - periodTotalContrib));

            result.add(period);
        }

        return result;
    }

    /**
     * 净值 JSON → date→value 映射
     */
    private Map<LocalDate, Double> buildNavMap(List<Map<String, Object>> curve) {
        Map<LocalDate, Double> result = new LinkedHashMap<>();
        for (Map<String, Object> point : curve) {
            try {
                LocalDate d = LocalDate.parse((String) point.get("date"));
                double v = ((Number) point.get("value")).doubleValue();
                result.put(d, v);
            } catch (Exception ignored) {}
        }
        return result;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    // ════════════════════════════════════════════════════════════════
    // 内部类
    // ════════════════════════════════════════════════════════════════

    private record DailyExcess(LocalDate date, double excess) {}

    private record RegressionResult(
            double alpha,
            double[] betas,
            double[] tStats,
            double rSquared,
            double adjRSquared,
            double fStatistic
    ) {}
}
