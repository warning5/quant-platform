package com.quant.platform.backtest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.backtest.domain.BacktestTask;
import com.quant.platform.common.exception.BusinessException;
import com.quant.platform.config.ClickHouseConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import static com.quant.platform.backtest.service.OlsRegressionCalculator.*;
import static com.quant.platform.backtest.service.AttributionResultAssembler.*;
import static com.quant.platform.backtest.service.RollingWindowMonitor.*;

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
    private final StrategyProfiler strategyProfiler;
    private final FactorDataLoader factorDataLoader;

    /** 无配置时的兜底因子（3因子：动量/波动率/规模），仅极端兜底用 */
    static final List<FactorDef> DEFAULT_FACTORS = List.of(
            new FactorDef("MOM20", "动量", "20日动量 — 追涨杀跌收益"),
            new FactorDef("VOL20", "波动率", "20日波动率 — 高波动股短期溢价"),
            new FactorDef("SIZE", "规模", "市值规模 — 小盘股溢价")
    );

    static final int QUINTILE = 5;          // 分5组，Top 20% vs Bottom 20%
    static final double MIN_DATA_RATIO = 0.3; // 单日最少数据比例才计算因子收益

    /** FF3 标准三因子 */
    static final List<FactorDef> FF3_FACTORS = List.of(
            new FactorDef("MKT", "市场因子", "全市场等权日收益 — 系统风险溢价"),
            new FactorDef("SMB", "规模因子", "小市值(底30%) − 大市值(顶30%) — 小盘股溢价"),
            new FactorDef("HML", "价值因子", "高BP(底30%) − 低BP(顶30%) — 价值股溢价")
    );

    // ──── 监控相关常量 ────
    static final int[] ROLLING_WINDOWS = {60, 120, 252};  // 滚动窗口大小
    static final double ALPHA_DECAY_THRESHOLD = -0.5;     // Alpha衰减阈值 (近N期 vs 历史均值降幅50%)
    static final double STYLE_DRIFT_STD = 1.0;            // 风格漂移阈值 (偏离历史均值 N 个标准差)

    /**
     * 因子定义
     */
    record FactorDef(String code, String name, String description) {}

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
        List<FactorDef> factors = factorDataLoader.loadStrategyFactors(task.getStrategyId());
        log.info("因子风格归因使用的因子集: {} (共{}个因子)",
                factors.stream().map(FactorDef::code).collect(Collectors.joining(",")),
                factors.size());

        // 4. 获取回测期间的日期范围
        LocalDate minDate = sortedDates.getFirst();
        LocalDate maxDate = sortedDates.getLast();

        // 5. 从 ClickHouse 批量加载因子值 → 计算每日因子收益
        Map<LocalDate, Map<String, Double>> factorDailyReturns
                = factorDataLoader.computeFactorDailyReturns(minDate, maxDate, factors);

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
     * <p>零行为变化拆分：实现已迁至 {@link StrategyProfiler}，此处保留委托桩以兼容既有调用方。
     */
    public StrategyCharacteristics detectCharacteristics(BacktestTask task,
                                                         String positionHistoryJson) {
        return strategyProfiler.detectCharacteristics(task, positionHistoryJson);
    }

    // ════════════════════════════════════════════════════════════════
    // 内部类
    // ════════════════════════════════════════════════════════════════

    record DailyExcess(LocalDate date, double excess) {}

    record RegressionResult(
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
    record AlphaWindowPoint(LocalDate date, double alpha, double annualizedAlpha,
                                     double rSquared, int windowDays) {}

    /** Alpha 监控完整结果 */
    public record AlphaMonitorResult(
            List<AlphaWindowPoint> rolling60, List<AlphaWindowPoint> rolling120,
            List<AlphaWindowPoint> rolling252, boolean decayAlert,
            String decayWarning, double historicalMean, double recentMean,
            double slope, double decayRatio
    ) {}

    /** 风格β滚动窗口单点 */
    record StyleBetaPoint(LocalDate date, double smbBeta, double hmlBeta,
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
                = factorDataLoader.computeFF3FactorReturns(minDate, maxDate);

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

    /**
     * 每日 FF3 因子收益计算（增量：只计算最近N天缺失数据）
     * 由 FactorComputeCompletedEvent 事件触发。
     */
    public void computeDailyFF3Premium() {
        if (!clickHouseConfig.isEnabled()) {
            log.info("[FactorStyle] CH 不可用，跳过每日 FF3 计算");
            return;
        }

        // 查 factor_premium 最新日期
        LocalDate lastDate = null;
        try (Connection conn = clickHouseConfig.getConnection();
             Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT max(calc_date) FROM stock.factor_premium");
            if (rs.next()) {
                java.sql.Date d = rs.getDate(1);
                if (d != null) lastDate = d.toLocalDate();
            }
        } catch (Exception e) {
            log.warn("[FactorStyle] 查询 factor_premium max date 失败: {}", e.getMessage());
        }

        LocalDate startDate = lastDate != null ? lastDate.plusDays(1) : LocalDate.now().minusDays(120);
        LocalDate endDate = LocalDate.now();

        if (startDate.isAfter(endDate)) {
            log.info("[FactorStyle] factor_premium 数据已最新，无需补算");
            return;
        }

        log.info("[FactorStyle] 开始补算 FF3 因子收益: {} ~ {}", startDate, endDate);
        factorDataLoader.computeFF3FactorReturns(startDate, endDate);
    }

    /** 股票日数据辅助类 */
    record StockDayData(String code, double dailyRet, double marketCap, double pb) {}

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
        List<FactorDef> factors = factorDataLoader.loadStrategyFactors(task.getStrategyId());
        LocalDate minDate = sortedDates.getFirst(), maxDate = sortedDates.getLast();
        Map<LocalDate, Map<String, Double>> factorDailyReturns
                = factorDataLoader.computeFactorDailyReturns(minDate, maxDate, factors);

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
        Map<LocalDate, Map<String, Double>> ff3Returns = factorDataLoader.computeFF3FactorReturns(minDate, maxDate);

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

}


