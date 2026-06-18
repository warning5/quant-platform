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
            new FactorDef("TURN20", "换手率", "20日换手率 — 流动性溢价")
    );

    private static final int QUINTILE = 5;          // 分5组，Top 20% vs Bottom 20%
    private static final double MIN_DATA_RATIO = 0.3; // 单日最少数据比例才计算因子收益

    /** FF3 标准三因子 */
    static final List<FactorDef> FF3_FACTORS = List.of(
            new FactorDef("MKT", "市场因子", "全市场等权日收益 — 系统风险溢价"),
            new FactorDef("SMB", "规模因子", "小市值(底30%) − 大市值(顶30%) — 小盘股溢价"),
            new FactorDef("HML", "价值因子", "高BP(底30%) − 低BP(顶30%) — 价值股溢价")
    );

    // ──── 监控相关常量 ────
    private static final int[] ROLLING_WINDOWS = {60, 120, 252};  // 滚动窗口大小
    private static final double ALPHA_DECAY_THRESHOLD = -0.5;     // Alpha衰减阈值 (近N期 vs 历史均值降幅50%)
    private static final double STYLE_DRIFT_STD = 1.0;            // 风格漂移阈值 (偏离历史均值 N 个标准差)

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
                ? Math.max(0, 1 - Math.abs(residual) / Math.abs(totalExcess)) : 0;

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
        regressionDetail.put("alphaTStat", round4(regResult.alphaTStat));
        regressionDetail.put("alphaPValue", round4(regResult.alphaPValue));
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

        // A1: Alpha 解读增强
        result.put("alphaInterpretation", buildAlphaInterpretation(regResult, n));

        log.info("因子风格归因完成: taskId={}, 因子数={}, R²={}, 解释力={}, α/d={}," +
                        " α_t={}, α_p={}",
                task.getId(), factors.size(), round4(regResult.rSquared), round4(explanationRatio),
                round4(regResult.alpha), round4(regResult.alphaTStat), round4(regResult.alphaPValue));

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
                    new TypeReference<>() {
                    });
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

        // A2: Alpha 显著性 — t-stat & p-value (双尾)
        double alphaSe = Math.sqrt(Math.abs(covarBeta[0][0]));
        double alphaTStat = alphaSe > 1e-10 ? alpha / alphaSe : 0;
        double alphaPValue = computePValue(alphaTStat, n - k - 1);

        return new RegressionResult(alpha, betaOnly, tStats, rSquared, adjRSquared,
                fStatistic, alphaTStat, alphaPValue);
    }

    /**
     * 通过 t 分布近似计算双尾 p 值 (A2)
     * 使用 Abramowitz & Stegun 近似 (误差 < 0.002 for df >= 1)
     */
    private double computePValue(double t, int df) {
        if (df <= 0) return 1.0;
        double x = df / (df + t * t);
        // 不完全 Beta 函数近似
        double ibeta = regularizedIncompleteBeta(x, df / 2.0, 0.5);
        return ibeta;
    }

    /** 正则化不完全 Beta 函数 I_x(a,b) 的近似算法 */
    private double regularizedIncompleteBeta(double x, double a, double b) {
        if (x <= 0) return 0;
        if (x >= 1) return 1;
        // 用连分数近似
        double front = Math.exp(logGamma(a + b) - logGamma(a) - logGamma(b)
                + a * Math.log(x) + b * Math.log(1 - x)) / a;
        double f = 1.0, c = 1.0, d = 1.0 - (a + b) * x / (a + 1);
        if (Math.abs(d) < 1e-30) d = 1e-30;
        d = 1.0 / d;
        double h = d;
        for (int m = 1; m <= 100; m++) {
            int m2 = 2 * m;
            double aa = m * (b - m) * x / ((a + m2 - 1) * (a + m2));
            d = 1.0 + aa * d;
            if (Math.abs(d) < 1e-30) d = 1e-30;
            c = 1.0 + aa / c;
            if (Math.abs(c) < 1e-30) c = 1e-30;
            d = 1.0 / d;
            h *= d * c;
            aa = -(a + m) * (a + b + m) * x / ((a + m2) * (a + m2 + 1));
            d = 1.0 + aa * d;
            if (Math.abs(d) < 1e-30) d = 1e-30;
            c = 1.0 + aa / c;
            if (Math.abs(c) < 1e-30) c = 1e-30;
            d = 1.0 / d;
            double del = d * c;
            h *= del;
            if (Math.abs(del - 1.0) < 1e-10) break;
        }
        return front * h;
    }

    /** log Gamma 函数 (Stirling 近似, 足够精确用于 Beta 函数) */
    private double logGamma(double x) {
        double[] coef = {76.18009172947146, -86.50532032941677,
                24.01409824083091, -1.231739572450155,
                0.1208650973866179e-2, -0.5395239384953e-5};
        double y = x, tmp = x + 5.5;
        tmp -= (x + 0.5) * Math.log(tmp);
        double ser = 1.000000000190015;
        for (int j = 0; j < 6; j++) ser += coef[j] / ++y;
        return -tmp + Math.log(2.5066282746310005 * ser / x);
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
            double fStatistic,
            double alphaTStat,     // Alpha 的 t 统计量 (A2)
            double alphaPValue     // Alpha 的 p 值 (A2, 双尾)
    ) {}

    private record FF3AttributionResult(
            double marketBeta, double marketTStat, boolean marketSig,
            double sizeBeta, double sizeTStat, boolean sizeSig,
            double valueBeta, double valueTStat, boolean valueSig,
            double alpha, double alphaTStat, double alphaPValue,
            double rSquared, double adjRSquared, double fStatistic,
            double totalExcess, double totalFactorContrib, double residual,
            double explanationRatio
    ) {}

    // ──── 监控相关记录 ────

    /** Alpha 滚动窗口单点 */
    private record AlphaWindowPoint(LocalDate date, double alpha, double annualizedAlpha,
                                     double rSquared, int windowDays) {}

    /** Alpha 监控完整结果 */
    public record AlphaMonitorResult(
            List<AlphaWindowPoint> rolling60, List<AlphaWindowPoint> rolling120,
            List<AlphaWindowPoint> rolling252, boolean decayAlert,
            String decayWarning, double historicalMean, double recentMean,
            double slope, double decayRatio
    ) {}

    /** 风格β滚动窗口单点 */
    private record StyleBetaPoint(LocalDate date, double smbBeta, double hmlBeta,
                                   double marketBeta, double rSquared, int windowDays) {}

    /** 风格β监控完整结果 */
    public record StyleMonitorResult(
            List<StyleBetaPoint> rolling60, List<StyleBetaPoint> rolling120,
            List<StyleBetaPoint> rolling252, boolean smbDrift, boolean hmlDrift,
            String driftWarning, double smbHistoricalMean, double smbRecentMean,
            double hmlHistoricalMean, double hmlRecentMean,
            double smbStd, double hmlStd
    ) {}

    // ════════════════════════════════════════════════════════════════
    // A4+A5: FF3 归因模式 + 风格暴露报告
    // ════════════════════════════════════════════════════════════════

    /**
     * FF3 三因子归因：用标准 MKT/SMB/HML 回归组合超额收益，输出风格暴露报告。
     * <p>
     * 复用 compute() 的净值解析和超额收益计算逻辑，因子集切换为 FF3_FACTORS。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> computeFF3(BacktestTask task,
                                          String positionHistoryJson,
                                          String equityCurveJson,
                                          String benchmarkCurveJson) {
        // 1. 解析净值数据（复用现有逻辑）
        List<Map<String, Object>> equityCurve, benchmarkCurve;
        try {
            equityCurve = objectMapper.readValue(equityCurveJson != null ? equityCurveJson : "[]", List.class);
            benchmarkCurve = objectMapper.readValue(benchmarkCurveJson != null ? benchmarkCurveJson : "[]", List.class);
        } catch (Exception e) {
            throw new BusinessException("FF3 数据解析失败: " + e.getMessage());
        }

        Map<LocalDate, Double> stratNav = buildNavMap(equityCurve);
        Map<LocalDate, Double> benchNav = buildNavMap(benchmarkCurve);
        if (stratNav.isEmpty()) throw new BusinessException("净值曲线数据为空");

        List<LocalDate> sortedDates = new ArrayList<>(stratNav.keySet());
        sortedDates.sort(Comparator.naturalOrder());

        List<DailyExcess> dailyExcessList = new ArrayList<>();
        for (int i = 1; i < sortedDates.size(); i++) {
            LocalDate d = sortedDates.get(i), prev = sortedDates.get(i - 1);
            double stratRet = stratNav.get(d) / stratNav.get(prev) - 1;
            Double bNav = benchNav.get(d), bNavPrev = benchNav.get(prev);
            double benchRet = (bNav != null && bNavPrev != null && bNavPrev > 0)
                    ? bNav / bNavPrev - 1 : 0;
            dailyExcessList.add(new DailyExcess(d, stratRet - benchRet));
        }

        // 2. 计算 FF3 因子日收益
        LocalDate minDate = sortedDates.getFirst(), maxDate = sortedDates.getLast();
        Map<LocalDate, Map<String, Double>> factorDailyReturns
                = computeFF3FactorReturns(minDate, maxDate);

        // 3. 对齐日期并回归
        List<DailyExcess> alignedExcess = new ArrayList<>();
        List<double[]> alignedFactors = new ArrayList<>();
        for (DailyExcess de : dailyExcessList) {
            Map<String, Double> fr = factorDailyReturns.get(de.date);
            if (fr == null) continue;
            double[] row = new double[3];
            boolean allPresent = true;
            for (int f = 0; f < 3; f++) {
                Double v = fr.get(FF3_FACTORS.get(f).code);
                if (v == null || Double.isNaN(v)) { allPresent = false; break; }
                row[f] = v;
            }
            if (!allPresent) continue;
            alignedExcess.add(de);
            alignedFactors.add(row);
        }

        int n = alignedExcess.size();
        if (n < 10) throw new BusinessException("FF3 有效数据点不足: " + n + "天");
        RegressionResult reg = runOLS(alignedExcess, alignedFactors, n, 3);

        // 4. 计算各因子贡献
        List<Map<String, Object>> styleContributions = new ArrayList<>();
        String[] styleNames = {"市场(MKT)", "规模(SMB)", "价值(HML)"};
        double totalFactorContrib = 0;

        for (int f = 0; f < 3; f++) {
            double cumFactorRet = 0;
            for (double[] row : alignedFactors) cumFactorRet += row[f];
            double contribution = reg.betas[f] * cumFactorRet;
            totalFactorContrib += contribution;

            Map<String, Object> sc = new LinkedHashMap<>();
            sc.put("factorCode", FF3_FACTORS.get(f).code);
            sc.put("factorName", FF3_FACTORS.get(f).name);
            sc.put("styleName", styleNames[f]);
            sc.put("description", FF3_FACTORS.get(f).description);
            sc.put("beta", round4(reg.betas[f]));
            sc.put("tStat", round4(reg.tStats[f]));
            sc.put("significant", Math.abs(reg.tStats[f]) >= 1.96);
            sc.put("totalFactorReturn", round4(cumFactorRet));
            sc.put("annualizedFactorReturn", round4(cumFactorRet / n * 252));
            sc.put("contribution", round4(contribution));
            sc.put("contributionPct", round4(Math.abs(contribution / (Math.abs(totalFactorContrib) + 1e-8)) * 100));
            styleContributions.add(sc);
        }

        // 按贡献绝对值排序
        styleContributions.sort((a, b) ->
                Double.compare(Math.abs(((Number) b.get("contribution")).doubleValue()),
                               Math.abs(((Number) a.get("contribution")).doubleValue())));

        double totalExcess = alignedExcess.stream().mapToDouble(de -> de.excess).sum();
        double residual = totalExcess - totalFactorContrib;

        // FF3 的解释力直接使用回归 R²，而非自定义公式（自定义公式在超额很小时异常）
        double explanationRatio = reg.rSquared;

        // 5. 风格偏向解读
        String styleBias = buildStyleBiasDescription(reg);

        // 6. 组装结果
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", task.getId());
        result.put("model", "FF3");
        result.put("modelDescription", "Fama-French 三因子风格归因");
        result.put("observationDays", n);

        result.put("styleContributions", styleContributions);
        result.put("styleBias", styleBias);

        Map<String, Object> regDetail = new LinkedHashMap<>();
        regDetail.put("alpha", round4(reg.alpha));
        regDetail.put("annualizedAlpha", round4(reg.alpha * 252));
        regDetail.put("alphaTStat", round4(reg.alphaTStat));
        regDetail.put("alphaPValue", round4(reg.alphaPValue));
        regDetail.put("alphaSignificant", Math.abs(reg.alphaTStat) >= 1.96);
        regDetail.put("rSquared", round4(reg.rSquared));
        regDetail.put("adjRSquared", round4(reg.adjRSquared));
        regDetail.put("fStatistic", round4(reg.fStatistic));
        result.put("regressionDetail", regDetail);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalExcessReturn", round4(totalExcess));
        summary.put("totalFactorContribution", round4(totalFactorContrib));
        summary.put("residual", round4(residual));
        summary.put("explanationRatio", round4(explanationRatio));
        result.put("summary", summary);

        // Alpha 解读 (A1)
        result.put("alphaInterpretation", buildAlphaInterpretation(reg, n));

        log.info("FF3 归因完成: taskId={}, R²={}, MKTB={}, SMBB={}, HMLB={}, α/d={}",
                task.getId(), round4(reg.rSquared), round4(reg.betas[0]),
                round4(reg.betas[1]), round4(reg.betas[2]), round4(reg.alpha));

        return result;
    }

    /** 构建风格偏向解读（白话版，R² 感知） */
    private String buildStyleBiasDescription(RegressionResult reg) {
        double mktT = reg.tStats[0], smbT = reg.tStats[1], hmlT = reg.tStats[2];
        double mktBeta = reg.betas[0];
        double smbBeta = reg.betas[1], hmlBeta = reg.betas[2];
        double r2 = reg.rSquared;
        int r2Pct = (int) Math.round(r2 * 100);

        StringBuilder sb = new StringBuilder();

        // ── R² 分级前置说明 ──
        boolean lowConfidence = false;
        if (r2 < 0.30) {
            sb.append(String.format(
                "三因子模型无法解释该策略（R²=%d%%）——策略收益中仅%d%%与市场/市值/估值相关，" +
                "剩余%d%%来自其他因素。这不代表策略差，而是说明它的赚钱逻辑不在传统风格框架内" +
                "（可能来自因子选股、行业轮动或择时能力），风格标签对这类策略没有意义。",
                r2Pct, r2Pct, 100 - r2Pct));
            return sb.toString();
        } else if (r2 < 0.50) {
            lowConfidence = true;
            sb.append(String.format("三因子模型解释力偏弱（R²=%d%%），以下风格诊断仅供参考，并非定论。", r2Pct));
        } else if (r2 >= 0.70) {
            sb.append(String.format("三因子模型能很好地解释策略收益（R²=%d%%），风格特征明确。", r2Pct));
        }

        // ── 风格诊断 ──
        String prefix = lowConfidence ? "仅看有限的可解释部分，策略是个" : "策略是个";
        sb.append(prefix);
        // 市场β
        if (Math.abs(mktT) >= 1.96) {
            if (mktBeta > 0)
                sb.append("「跟涨型」选手——大盘涨1%你就跟着涨").append(String.format("%.1f", mktBeta * 100)).append("%，");
            else
                sb.append("「逆向型」选手——大盘涨你反而容易跌，");
        } else {
            sb.append("「独立派」——牛市未必涨、熊市未必跌，跟大盘没什么关系。");
        }
        // 市值风格
        if (Math.abs(smbT) >= 1.96) {
            sb.append(smbBeta > 0 ? "偏爱小盘股（SMB=" : "偏爱大蓝筹（SMB=")
              .append(String.format("%.2f", smbBeta)).append("），");
        } else {
            sb.append("选股不挑大小公司（大小票一视同仁），");
        }
        // 估值风格
        if (Math.abs(hmlT) >= 1.96) {
            sb.append(hmlBeta > 0 ? "偏好捡便宜货（低PE/PB）。" : "愿意为成长付溢价（高估值）。");
        } else {
            sb.append("不看股票贵贱（估值高低都能接受）。");
        }

        return sb.toString();
    }

    // ════════════════════════════════════════════════════════════════
    // A3: FF3 因子日收益计算（MKT/SMB/HML）
    // ════════════════════════════════════════════════════════════════

    /**
     * 计算 FF3 标准三因子的每日多空收益，结果存入 factor_premium 表。
     */
    Map<LocalDate, Map<String, Double>> computeFF3FactorReturns(
            LocalDate startDate, LocalDate endDate) {

        if (!clickHouseConfig.isEnabled())
            throw new BusinessException("ClickHouse 不可用");

        // 1. 从 CH 加载 stock_daily 日收益
        String sql = String.format("""
                SELECT code, trade_date, close_price / pre_close - 1 AS daily_ret
                FROM stock.stock_daily FINAL
                WHERE trade_date >= '%s' AND trade_date <= '%s'
                  AND pre_close > 0 AND close_price > 0
                ORDER BY trade_date
                """, startDate, endDate);

        Map<LocalDate, Map<String, Double>> dailyRetsByDate = new LinkedHashMap<>();
        try (Connection conn = DriverManager.getConnection(clickHouseConfig.getJdbcUrl());
             Statement stmt = conn.createStatement()) {
            stmt.setFetchSize(50000);
            try (ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    String code = rs.getString("code");
                    if (code.startsWith("8") || code.startsWith("4") || code.startsWith("2")) continue; // 排除北交所/B股
                    LocalDate d = rs.getDate("trade_date").toLocalDate();
                    double ret = rs.getDouble("daily_ret");
                    if (rs.wasNull() || Double.isNaN(ret)) continue;
                    dailyRetsByDate.computeIfAbsent(d, k -> new HashMap<>()).put(code, ret);
                }
            }
        } catch (Exception e) {
            log.error("CH stock_daily 查询失败: {}", e.getMessage(), e);
            throw new BusinessException("CH 行情查询失败: " + e.getMessage());
        }

        // 2. 从 MySQL stock_info 加载市值和 PB
        Map<String, double[]> stockInfoMap = new HashMap<>(); // code -> [total_market_cap, pb]
        try {
            jdbcTemplate.query(
                    "SELECT code, total_market_cap, pb FROM stock_info WHERE total_market_cap IS NOT NULL",
                    (rs) -> {
                        String code = rs.getString("code");
                        double mcap = rs.getDouble("total_market_cap");
                        double pb = rs.getDouble("pb");
                        if (!rs.wasNull() && mcap > 0) {
                            stockInfoMap.put(code, new double[]{mcap, rs.wasNull() ? 0 : pb});
                        }
                    });
        } catch (Exception e) {
            log.warn("MySQL stock_info 查询失败: {}", e.getMessage());
        }

        log.info("FF3 因子计算: CH daily数据 {}天, MySQL stock_info {}只",
                dailyRetsByDate.size(), stockInfoMap.size());

        // 3. 逐日计算三个因子
        Map<LocalDate, Map<String, Double>> result = new LinkedHashMap<>();
        int minStocks = 200;

        for (Map.Entry<LocalDate, Map<String, Double>> entry : dailyRetsByDate.entrySet()) {
            LocalDate date = entry.getKey();
            Map<String, Double> dayRets = entry.getValue();

            // 过滤：仅保留 stock_info 中有市值数据的股票
            List<StockDayData> dayData = new ArrayList<>();
            for (Map.Entry<String, Double> e : dayRets.entrySet()) {
                double[] info = stockInfoMap.get(e.getKey());
                if (info == null) continue;
                dayData.add(new StockDayData(e.getKey(), e.getValue(), info[0], info[1]));
            }
            if (dayData.size() < minStocks) continue;

            // MKT: 全市场等权收益
            double mkt = dayData.stream().mapToDouble(d -> d.dailyRet).average().orElse(0);

            // SMB: 按市值排序，底30% vs 顶30%
            dayData.sort(Comparator.comparingDouble(a -> a.marketCap));
            int n = dayData.size(), q = n / 3; // 30%
            if (q < 10) continue;
            double smbTop = 0, smbBot = 0;
            for (int i = 0; i < q; i++) smbBot += dayData.get(i).dailyRet;
            for (int i = n - q; i < n; i++) smbTop += dayData.get(i).dailyRet;
            double smb = smbBot / q - smbTop / q;

            // HML: 按 PB 排序（PB 越小=越价值），底30%(低PB/价值) vs 顶30%(高PB/成长)
            // PB <= 0 的排到最末尾（视为不可比）
            dayData.sort((a, b) -> {
                if (a.pb <= 0 && b.pb <= 0) return 0;
                if (a.pb <= 0) return 1;
                if (b.pb <= 0) return -1;
                return Double.compare(a.pb, b.pb);
            });
            int validN = (int) dayData.stream().filter(d -> d.pb > 0).count();
            if (validN < q * 2) continue;

            double hmlLow = 0, hmlHigh = 0;
            for (int i = 0; i < q; i++) hmlLow += dayData.get(i).dailyRet;
            for (int i = Math.max(validN - q, 0); i < validN; i++) hmlHigh += dayData.get(i).dailyRet;
            double hml = hmlLow / q - hmlHigh / q;

            Map<String, Double> dayResult = new LinkedHashMap<>();
            dayResult.put("MKT", round4(mkt));
            dayResult.put("SMB", round4(smb));
            dayResult.put("HML", round4(hml));
            result.put(date, dayResult);
        }

        log.info("FF3 因子计算完成: {} 个交易日 ({} ~ {})",
                result.size(), startDate, endDate);
        return result;
    }

    /** 股票日数据辅助类 */
    private record StockDayData(String code, double dailyRet, double marketCap, double pb) {}

    // ════════════════════════════════════════════════════════════════
    // M1+M2: Alpha 滚动窗口 + 衰减预警
    // ════════════════════════════════════════════════════════════════

    /**
     * 计算 Alpha 滚动窗口（基于策略因子集）
     * <p>
     * 对每个滚动窗口：超额收益 ~ 策略因子 OLS 回归 → 窗口 Alpha。
     * 三个窗口 (60/120/252天) 的 Alpha 序列联合分析衰减趋势。
     */
    @SuppressWarnings("unchecked")
    public AlphaMonitorResult computeRollingAlpha(BacktestTask task,
                                                   String equityCurveJson,
                                                   String benchmarkCurveJson) {
        List<Map<String, Object>> equityCurve, benchmarkCurve;
        try {
            equityCurve = objectMapper.readValue(equityCurveJson != null ? equityCurveJson : "[]", List.class);
            benchmarkCurve = objectMapper.readValue(benchmarkCurveJson != null ? benchmarkCurveJson : "[]", List.class);
        } catch (Exception e) {
            throw new BusinessException("数据解析失败: " + e.getMessage());
        }

        Map<LocalDate, Double> stratNav = buildNavMap(equityCurve);
        Map<LocalDate, Double> benchNav = buildNavMap(benchmarkCurve);
        if (stratNav.isEmpty()) throw new BusinessException("净值数据为空");

        List<LocalDate> sortedDates = new ArrayList<>(stratNav.keySet());
        sortedDates.sort(Comparator.naturalOrder());

        // 计算每日超额收益
        List<DailyExcess> dailyExcessList = new ArrayList<>();
        for (int i = 1; i < sortedDates.size(); i++) {
            LocalDate d = sortedDates.get(i), prev = sortedDates.get(i - 1);
            double stratRet = stratNav.get(d) / stratNav.get(prev) - 1;
            Double bNav = benchNav.get(d), bNavPrev = benchNav.get(prev);
            double benchRet = (bNav != null && bNavPrev != null && bNavPrev > 0)
                    ? bNav / bNavPrev - 1 : 0;
            dailyExcessList.add(new DailyExcess(d, stratRet - benchRet));
        }

        if (dailyExcessList.size() < 60)
            throw new BusinessException("数据不足: " + dailyExcessList.size() + "天 (需要≥60天)");

        // 加载策略因子日收益
        List<FactorDef> factors = loadStrategyFactors(task.getStrategyId());
        LocalDate minDate = sortedDates.getFirst(), maxDate = sortedDates.getLast();
        Map<LocalDate, Map<String, Double>> factorDailyReturns
                = computeFactorDailyReturns(minDate, maxDate, factors);

        // 对齐因子数据
        Map<LocalDate, double[]> alignedFactorMap = new HashMap<>();
        for (DailyExcess de : dailyExcessList) {
            Map<String, Double> fr = factorDailyReturns.get(de.date);
            if (fr == null) continue;
            double[] row = new double[factors.size()];
            boolean allPresent = true;
            for (int f = 0; f < factors.size(); f++) {
                Double v = fr.get(factors.get(f).code);
                if (v == null || Double.isNaN(v)) { allPresent = false; break; }
                row[f] = v;
            }
            if (!allPresent) continue;
            alignedFactorMap.put(de.date, row);
        }

        // 对每个窗口大小计算滚动 Alpha
        AlphaMonitorResult result = computeRollingAlphaForWindows(
                dailyExcessList, alignedFactorMap, factors.size(), ROLLING_WINDOWS);

        log.info("Alpha 滚动监控完成: taskId={}, decayAlert={}", task.getId(), result.decayAlert);
        return result;
    }

    private AlphaMonitorResult computeRollingAlphaForWindows(
            List<DailyExcess> excesses, Map<LocalDate, double[]> factorMap,
            int factorCount, int[] windows) {

        List<AlphaWindowPoint>[] results = new List[windows.length];
        for (int w = 0; w < windows.length; w++) {
            results[w] = new ArrayList<>();
            int win = windows[w];

            for (int start = 0; start + win <= excesses.size(); start++) {
                int end = start + win;
                List<DailyExcess> winExcess = new ArrayList<>();
                List<double[]> winFactors = new ArrayList<>();

                for (int i = start; i < end; i++) {
                    DailyExcess de = excesses.get(i);
                    double[] frow = factorMap.get(de.date);
                    if (frow == null) break;
                    winExcess.add(de);
                    winFactors.add(frow);
                }
                if (winExcess.size() < Math.max(20, win / 2)) continue; // 数据充足度检查

                RegressionResult reg = runOLS(winExcess, winFactors, winExcess.size(), factorCount);
                results[w].add(new AlphaWindowPoint(
                        excesses.get(end - 1).date, round4(reg.alpha),
                        round4(reg.alpha * 252), round4(reg.rSquared), win));
            }
        }

        // 衰减检测 (基于252天窗口，若数据不足则退而求其次)
        List<AlphaWindowPoint> primaryWindow = results[2].size() >= 10 ? results[2]
                : results[1].size() >= 10 ? results[1] : results[0];
        return detectAlphaDecay(primaryWindow, results[0], results[1], results[2]);
    }

    /** 检测 Alpha 衰减 —— 统一口径 + 趋势方向 */
    private AlphaMonitorResult detectAlphaDecay(
            List<AlphaWindowPoint> primary, List<AlphaWindowPoint> r60,
            List<AlphaWindowPoint> r120, List<AlphaWindowPoint> r252) {

        // 数据不足：不计算衰减
        if (primary.size() < 20) {
            return new AlphaMonitorResult(r60, r120, r252,
                    false,
                    "数据不足（需 ≥20 个滚动窗口才能启动衰减分析）",
                    0, 0, 0, 0);
        }

        // 统一口径：都用平均值
        double historicalMean = primary.stream().mapToDouble(p -> p.alpha).average().orElse(0);
        int recentN = Math.max(5, primary.size() / 4);
        double recentMean = primary.subList(primary.size() - recentN, primary.size())
                .stream().mapToDouble(p -> p.alpha).average().orElse(0);

        // 计算近期斜率（最近5个点，简单线性回归）
        int slopeN = Math.min(5, primary.size());
        double slope = computeSlope(primary, slopeN);

        // 趋势判断
        boolean decayAlert = (slope < 0 && recentMean < historicalMean);
        String decayWarning;
        if (decayAlert) {
            decayWarning = String.format(
                    "⚠ Alpha 有下行趋势：近 %d 期均值 %.4f%% 低于历史均值 %.4f%%（近期斜率 %.6f），建议排查策略有效性。",
                    recentN, recentMean * 100, historicalMean * 100, slope);
        } else if (slope < 0) {
            decayWarning = String.format(
                    "Alpha 近期斜率 %.6f 为负，但均值尚未明显低于历史水平，需持续观察。",
                    slope);
        } else {
            decayWarning = "Alpha 保持稳定，未检测到显著衰减趋势。";
        }

        double decayRatio = Math.abs(historicalMean) > 1e-8
                ? (recentMean - historicalMean) / Math.abs(historicalMean) : 0;

        return new AlphaMonitorResult(r60, r120, r252, decayAlert, decayWarning,
                round4(historicalMean), round4(recentMean), round4(slope), round4(decayRatio));
    }

    /** 计算最近 N 个点的斜率（简单线性回归） */
    private double computeSlope(List<AlphaWindowPoint> points, int n) {
        List<AlphaWindowPoint> tail = points.subList(points.size() - n, points.size());
        int size = tail.size();
        // x = 0,1,2,...,n-1
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        for (int i = 0; i < size; i++) {
            double x = i;
            double y = tail.get(i).alpha;
            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumX2 += x * x;
        }
        double denom = size * sumX2 - sumX * sumX;
        return Math.abs(denom) > 1e-12 ? (size * sumXY - sumX * sumY) / denom : 0;
    }

    // ════════════════════════════════════════════════════════════════
    // M3+M4: FF3 风格β滚动窗口 + 漂移预警
    // ════════════════════════════════════════════════════════════════

    /**
     * FF3 风格β滚动监控：对每个窗口做 FF3 回归，输出 SMB/HML beta 序列，检测风格漂移。
     */
    @SuppressWarnings("unchecked")
    public StyleMonitorResult computeRollingStyleBeta(BacktestTask task,
                                                       String equityCurveJson,
                                                       String benchmarkCurveJson) {
        List<Map<String, Object>> equityCurve, benchmarkCurve;
        try {
            equityCurve = objectMapper.readValue(equityCurveJson != null ? equityCurveJson : "[]", List.class);
            benchmarkCurve = objectMapper.readValue(benchmarkCurveJson != null ? benchmarkCurveJson : "[]", List.class);
        } catch (Exception e) {
            throw new BusinessException("数据解析失败: " + e.getMessage());
        }

        Map<LocalDate, Double> stratNav = buildNavMap(equityCurve);
        Map<LocalDate, Double> benchNav = buildNavMap(benchmarkCurve);
        if (stratNav.isEmpty()) throw new BusinessException("净值数据为空");

        List<LocalDate> sortedDates = new ArrayList<>(stratNav.keySet());
        sortedDates.sort(Comparator.naturalOrder());

        List<DailyExcess> dailyExcessList = new ArrayList<>();
        for (int i = 1; i < sortedDates.size(); i++) {
            LocalDate d = sortedDates.get(i), prev = sortedDates.get(i - 1);
            double stratRet = stratNav.get(d) / stratNav.get(prev) - 1;
            Double bNav = benchNav.get(d), bNavPrev = benchNav.get(prev);
            double benchRet = (bNav != null && bNavPrev != null && bNavPrev > 0)
                    ? bNav / bNavPrev - 1 : 0;
            dailyExcessList.add(new DailyExcess(d, stratRet - benchRet));
        }
        if (dailyExcessList.size() < 60)
            throw new BusinessException("数据不足: " + dailyExcessList.size() + "天 (需要≥60天)");

        // 计算 FF3 因子
        LocalDate minDate = sortedDates.getFirst(), maxDate = sortedDates.getLast();
        Map<LocalDate, Map<String, Double>> ff3Returns = computeFF3FactorReturns(minDate, maxDate);

        // 对齐
        Map<LocalDate, double[]> alignedFF3 = new HashMap<>();
        for (DailyExcess de : dailyExcessList) {
            Map<String, Double> fr = ff3Returns.get(de.date);
            if (fr == null) continue;
            Double mkt = fr.get("MKT"), smb = fr.get("SMB"), hml = fr.get("HML");
            if (mkt == null || smb == null || hml == null) continue;
            alignedFF3.put(de.date, new double[]{mkt, smb, hml});
        }

        // 滚动窗口
        List<StyleBetaPoint>[] results = new List[3];
        for (int w = 0; w < 3; w++) {
            results[w] = new ArrayList<>();
            int win = ROLLING_WINDOWS[w];
            for (int start = 0; start + win <= dailyExcessList.size(); start++) {
                int end = start + win;
                List<DailyExcess> winExcess = new ArrayList<>();
                List<double[]> winFactors = new ArrayList<>();
                for (int i = start; i < end; i++) {
                    DailyExcess de = dailyExcessList.get(i);
                    double[] frow = alignedFF3.get(de.date);
                    if (frow == null) break;
                    winExcess.add(de);
                    winFactors.add(frow);
                }
                if (winExcess.size() < Math.max(20, win / 2)) continue;
                RegressionResult reg = runOLS(winExcess, winFactors, winExcess.size(), 3);
                results[w].add(new StyleBetaPoint(
                        dailyExcessList.get(end - 1).date,
                        round4(reg.betas[1]), round4(reg.betas[2]),
                        round4(reg.betas[0]), round4(reg.rSquared), win));
            }
        }

        // 漂移检测
        return detectStyleDrift(results[0], results[1], results[2]);
    }

    /** 检测风格漂移 */
    private StyleMonitorResult detectStyleDrift(
            List<StyleBetaPoint> r60, List<StyleBetaPoint> r120, List<StyleBetaPoint> r252) {

        List<StyleBetaPoint> primary = r252.size() >= 10 ? r252 : r120.size() >= 10 ? r120 : r60;

        boolean smbDrift = false, hmlDrift = false;
        double smbHistMean = 0, smbRecentMean = 0, hmlHistMean = 0, hmlRecentMean = 0;
        double smbStd = 0, hmlStd = 0;
        String driftWarning = "风格暴露稳定，未检测到显著漂移";

        if (primary.size() >= 10) {
            // SMB
            double[] smbSeries = primary.stream().mapToDouble(p -> p.smbBeta).toArray();
            final double smbMean = Arrays.stream(smbSeries).average().orElse(0);
            smbHistMean = smbMean;
            smbStd = Math.sqrt(Arrays.stream(smbSeries)
                    .map(x -> (x - smbMean) * (x - smbMean)).average().orElse(0));

            int sN = Math.max(3, primary.size() / 4);
            smbRecentMean = Arrays.stream(smbSeries, smbSeries.length - sN, smbSeries.length)
                    .average().orElse(0);

            if (smbStd > 1e-8 && Math.abs(smbRecentMean - smbMean) > STYLE_DRIFT_STD * smbStd) {
                smbDrift = true;
            }

            // HML
            double[] hmlSeries = primary.stream().mapToDouble(p -> p.hmlBeta).toArray();
            final double hmlMean = Arrays.stream(hmlSeries).average().orElse(0);
            hmlHistMean = hmlMean;
            hmlStd = Math.sqrt(Arrays.stream(hmlSeries)
                    .map(x -> (x - hmlMean) * (x - hmlMean)).average().orElse(0));

            int hN = Math.max(3, primary.size() / 4);
            hmlRecentMean = Arrays.stream(hmlSeries, hmlSeries.length - hN, hmlSeries.length)
                    .average().orElse(0);

            if (hmlStd > 1e-8 && Math.abs(hmlRecentMean - hmlHistMean) > STYLE_DRIFT_STD * hmlStd) {
                hmlDrift = true;
            }

            if (smbDrift || hmlDrift) {
                StringBuilder sb = new StringBuilder("⚠ 风格漂移预警：");
                if (smbDrift)
                    sb.append(String.format("规模暴露偏移 (%.2f→%.2f)", smbHistMean, smbRecentMean));
                if (smbDrift && hmlDrift) sb.append("; ");
                if (hmlDrift)
                    sb.append(String.format("价值暴露偏移 (%.2f→%.2f)", hmlHistMean, hmlRecentMean));
                sb.append("。请检查策略是否出现风格切换。");
                driftWarning = sb.toString();
            }
        }

        return new StyleMonitorResult(r60, r120, r252, smbDrift, hmlDrift,
                driftWarning, round4(smbHistMean), round4(smbRecentMean),
                round4(hmlHistMean), round4(hmlRecentMean),
                round4(smbStd), round4(hmlStd));
    }

    // ════════════════════════════════════════════════════════════════
    // A1+A2: Alpha 解读增强 + 显著性检验
    // ════════════════════════════════════════════════════════════════

    /**
     * 构建 Alpha 解读摘要 (A1)
     */
    private Map<String, Object> buildAlphaInterpretation(RegressionResult reg, int observationDays) {
        Map<String, Object> interp = new LinkedHashMap<>();
        interp.put("alphaPerDay", round4(reg.alpha));
        interp.put("annualizedAlpha", round4(reg.alpha * 252));
        interp.put("alphaTStat", round4(reg.alphaTStat));
        interp.put("alphaPValue", round4(reg.alphaPValue));
        interp.put("alphaSignificant", Math.abs(reg.alphaTStat) >= 1.96);

        double pct = reg.alpha * observationDays * 100; // 全期 Alpha 贡献百分比
        interp.put("totalAlphaPct", round4(pct));

        // 解读文案（R² 感知）
        String interpretation;
        double r2 = reg.rSquared;
        int r2Pct = (int) Math.round(r2 * 100);

        if (r2 < 0.30) {
            interpretation = String.format(
                    "模型解释力弱（R²=%d%%），因子模型覆盖不足，无论 Alpha 是否显著，" +
                    "超额收益来源无法通过当前因子框架判断——%d%% 的收益变动来自其他因素。",
                    r2Pct, 100 - r2Pct);
        } else if (Math.abs(reg.alphaTStat) >= 2.58) {
            interpretation = String.format(
                    "Alpha 高度显著 (t=%.2f, p=%.4f)，日均 Alpha=%.4f%%，年化 %.2f%%。" +
                    "超额收益中有显著部分来自策略本身的选股/择时能力，非运气所致。",
                    reg.alphaTStat, reg.alphaPValue, reg.alpha * 100, reg.alpha * 252 * 100);
        } else if (Math.abs(reg.alphaTStat) >= 1.96) {
            interpretation = String.format(
                    "Alpha 显著 (t=%.2f, p=%.4f)，日均 Alpha=%.4f%%。" +
                    "策略有一定超额选股能力，但须结合样本外验证确认。",
                    reg.alphaTStat, reg.alphaPValue, reg.alpha * 100);
        } else {
            interpretation = String.format(
                    "Alpha 不显著 (t=%.2f, p=%.4f)，超额收益主要由因子暴露驱动。" +
                    "策略无证据表明存在独立选股能力，表现归因于风格/因子倾斜。",
                    reg.alphaTStat, reg.alphaPValue);
        }
        interp.put("interpretation", interpretation);

        // 残差分解
        double totalAlphaContrib = reg.alpha * observationDays;
        interp.put("totalAlphaContribution", round4(totalAlphaContrib));

        return interp;
    }
}
