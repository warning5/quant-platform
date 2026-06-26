package com.quant.platform.factor.service;

import com.quant.platform.factor.mapper.FactorDefinitionMapper;
import com.quant.platform.factor.domain.FactorDefinition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * 因子组合权重优化服务
 * 支持三种优化方法：
 * 1. EQUAL           — 等权（基准）
 * 2. MARKOWITZ       — 均值-方差（最大化夏普比率）
 * 3. RISK_PARITY     — 风险平价（每个因子对组合风险的贡献相等）
 * 输入：多个因子代码 + 日期范围（用 factor_value.rank_value 作为截面收益代理）
 * 输出：每个因子的推荐权重
 * 数据点要求（按因子类别）：
 * - 财务类(FINANCIAL)：最低3个数据点，用原始rank值（季度财报本身即基本面指标）
 * - 其他因子：最低5个数据点，用rank差分值（捕捉因子变化趋势）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactorWeightOptimizeService {

    private final ClickHouseFactorValueService clickHouseFactorValueService;
    private final FactorDefinitionMapper factorDefinitionMapper;

    // ──────────────────────────────────────────────────────────────────────────
    // 公开接口
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 优化因子权重
     *
     * @param factorCodes 因子代码列表（至少2个）
     * @param startDate   开始日期（yyyy-MM-dd）
     * @param endDate     结束日期（yyyy-MM-dd）
     * @param method      优化方法：EQUAL / MARKOWITZ / RISK_PARITY
     * @return 优化结果，含权重、协方差矩阵、预期收益、预期波动率、夏普比率
     */
    public Map<String, Object> optimize(List<String> factorCodes,
                                        String startDate, String endDate,
                                        String method) {
        if (factorCodes == null || factorCodes.size() < 2) {
            throw new IllegalArgumentException("至少需要2个因子");
        }

        // ── 0.5 获取因子类别（用于区分数据点阈值）─────────────────────
        Map<String, String> factorCategories = getFactorCategories(factorCodes);
        Set<String> financialCodes = new HashSet<>();
        for (String code : factorCodes) {
            if ("FINANCIAL".equalsIgnoreCase(factorCategories.getOrDefault(code, ""))) {
                financialCodes.add(code);
            }
        }

        // ── 1. 获取各因子的截面 IC 时序（代理收益率）─────────────────────
        Map<String, FactorSeries> returns = loadFactorReturns(factorCodes, startDate, endDate, financialCodes);

        // ── 1.5 过滤无有效数据的因子（分类别阈值）────────────────────
        List<String> skippedFactors = new ArrayList<>();
        List<String> skippedDetails = new ArrayList<>();
        List<String> validCodes = new ArrayList<>();
        for (String code : factorCodes) {
            FactorSeries s = returns.get(code);
            int sz = (s != null) ? s.size() : 0;
            boolean isFinancial = financialCodes.contains(code);
            int minRequired = isFinancial ? 3 : 5;
            if (sz < minRequired) {
                skippedFactors.add(code);
                String reason = isFinancial
                        ? String.format("%s(财务因子,%d点,需≥%d)", code, sz, minRequired)
                        : String.format("%s(%d点,需≥%d)", code, sz, minRequired);
                skippedDetails.add(reason);
                log.warn("因子 {} 有效数据点不足（{} 个，最低要求 {}），已跳过", code, sz, minRequired);
            } else {
                validCodes.add(code);
            }
        }
        if (validCodes.size() < 2) {
            String detail = skippedFactors.isEmpty() ? "" : "；以下因子数据不足已被跳过: " + String.join(", ", skippedFactors);
            throw new IllegalStateException("有效因子不足（需≥2个，当前" + validCodes.size() + "个）" + detail);
        }

        // ── 2. 对齐日期（取所有因子的日期交集）───────────────────────────
        List<LocalDate> aligned = alignDates(returns, validCodes);
        int minAligned = validCodes.stream().anyMatch(financialCodes::contains) ? 3 : 5;
        if (aligned.size() < minAligned) {
            throw new IllegalStateException("有效数据点不足（" + aligned.size() + " 个），无法进行优化");
        }

        int n = validCodes.size();
        int T = aligned.size();

        // ── 3. 提取对齐后的收益矩阵 [n × T] ─────────────────────────────
        double[][] retMatrix = new double[n][T];
        for (int i = 0; i < n; i++) {
            FactorSeries series = returns.get(validCodes.get(i));
            Map<LocalDate, Integer> dateIndex = new HashMap<>();
            for (int t = 0; t < series.dates.size(); t++) dateIndex.put(series.dates.get(t), t);
            for (int t = 0; t < T; t++) {
                Integer idx = dateIndex.get(aligned.get(t));
                retMatrix[i][t] = (idx != null) ? series.values.get(idx) : 0.0;
            }
        }

        // ── 4. 计算均值收益和协方差矩阵 ──────────────────────────────────
        double[] means = calcMeans(retMatrix, n, T);
        double[][] cov = calcCovariance(retMatrix, means, n, T);

        // ── 5. 按方法求解权重 ─────────────────────────────────────────────
        double[] weights = switch (method.toUpperCase()) {
            case "MARKOWITZ" -> optimizeMarkowitz(means, cov, n);
            case "RISK_PARITY" -> optimizeRiskParity(cov, n);
            default -> equalWeights(n);
        };

        // ── 6. 计算组合预期指标 ────────────────────────────────────────────
        double portfolioReturn = dot(weights, means) * 252; // 年化
        double portfolioVariance = quadraticForm(weights, cov);
        double portfolioVol = Math.sqrt(portfolioVariance * 252);
        double sharpe = portfolioVol > 0 ? (portfolioReturn - 0.03) / portfolioVol : 0;

        // ── 7. 组装结果 ────────────────────────────────────────────────────
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("method", method.toUpperCase());
        result.put("factorCodes", validCodes);
        result.put("dataPoints", T);

        // 权重列表
        List<Map<String, Object>> weightList = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("factorCode", validCodes.get(i));
            w.put("weight", round4(weights[i]));
            w.put("meanReturn", round4(means[i] * 252));
            w.put("volatility", round4(Math.sqrt(cov[i][i] * 252)));
            weightList.add(w);
        }
        result.put("weights", weightList);
        result.put("portfolioReturn", round4(portfolioReturn));
        result.put("portfolioVolatility", round4(portfolioVol));
        result.put("sharpeRatio", round4(sharpe));

        // 相关系数矩阵（用于前端热力图）
        double[][] corrMatrix = toCorrelation(cov, n);
        List<List<Double>> corrList = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            List<Double> row = new ArrayList<>();
            for (int j = 0; j < n; j++) {
                row.add(round4(corrMatrix[i][j]));
            }
            corrList.add(row);
        }
        result.put("correlationMatrix", corrList);

        // 有效前沿（Markowitz 时额外计算）
        if ("MARKOWITZ".equalsIgnoreCase(method)) {
            result.put("efficientFrontier", calcEfficientFrontier(means, cov, n, 30));
        }

        // 警告：跳过的因子
        if (!skippedFactors.isEmpty()) {
            String detail = String.join("; ", skippedDetails);
            result.put("warnings", List.of(
                "以下因子因数据点不足被跳过: " + detail + "。" +
                "数据点要求：财务因子≥3（用原始rank值），其他因子≥5（用rank差分值）。"));
        }

        return result;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 私有：类别查询
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 批量查询因子类别
     */
    private Map<String, String> getFactorCategories(List<String> factorCodes) {
        Map<String, String> categories = new HashMap<>();
        try {
            List<FactorDefinition> defs = factorDefinitionMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FactorDefinition>()
                            .in(FactorDefinition::getFactorCode, factorCodes));
            for (FactorDefinition def : defs) {
                categories.put(def.getFactorCode(), def.getCategory() != null ? def.getCategory().name() : null);
            }
        } catch (Exception e) {
            log.warn("查询因子类别失败: {}", e.getMessage());
        }
        return categories;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 私有：数据加载
    // ──────────────────────────────────────────────────────────────────────────

    // ──────────────────────────────────────────────────────────────────────────
    // 内部数据结构：带时间戳的因子序列
    // ──────────────────────────────────────────────────────────────────────────

    /** 因子时序数据（日期 + 值一一对应） */
    private static class FactorSeries {
        final List<LocalDate> dates;
        final List<Double> values;
        FactorSeries(List<LocalDate> d, List<Double> v) { this.dates = d; this.values = v; }
        int size() { return dates.size(); }
    }

    /**
     * 加载因子截面收益率（带日期对齐）
     * - 财务因子(FINANCIAL)：用原始 rank_value 中位数作为收益
     * - 其他因子：用 rank_value 中位数差分作为日收益代理
     *
     * @return Map<factorCode, FactorSeries> 每个因子的日期+数值序列
     */
    private Map<String, FactorSeries> loadFactorReturns(List<String> factorCodes,
                                                        String startDate, String endDate,
                                                        Set<String> financialCodes) {
        LocalDate start = startDate != null ? LocalDate.parse(startDate) : LocalDate.of(2020, 1, 1);
        LocalDate end = endDate != null ? LocalDate.parse(endDate) : LocalDate.now();

        // CH 侧批量聚合：每个因子每日 rank_value 截面中位数
        Map<String, Map<LocalDate, Double>> rankMedians;
        try {
            rankMedians = clickHouseFactorValueService.getDailyRankMedians(factorCodes, start, end);
        } catch (Exception e) {
            log.error("ClickHouse 批量 rank 中位数查询失败: {}", e.getMessage());
            throw new RuntimeException("ClickHouse rank 中位数查询失败，请稍后重试", e);
        }

        Map<String, FactorSeries> result = new LinkedHashMap<>();
        for (String code : factorCodes) {
            Map<LocalDate, Double> dailyMedian = rankMedians.getOrDefault(code, new LinkedHashMap<>());
            List<LocalDate> dates = new ArrayList<>(dailyMedian.keySet());
            List<Double> values;

            if (financialCodes.contains(code)) {
                // 财务因子：直接用 rank 值（季度频率）
                values = new ArrayList<>();
                for (LocalDate date : dates) {
                    values.add(dailyMedian.get(date));
                }
                log.debug("Factor {} (FINANCIAL) loaded {} raw rank points", code, values.size());
            } else {
                // 非财务因子：差分得到日变化率（dates/values 同步缩减1位）
                values = new ArrayList<>();
                List<LocalDate> diffDates = new ArrayList<>();
                for (int i = 1; i < dates.size(); i++) {
                    double prev = dailyMedian.get(dates.get(i - 1));
                    double curr = dailyMedian.get(dates.get(i));
                    values.add(prev != 0 ? (curr - prev) / Math.abs(prev) : 0.0);
                    diffDates.add(dates.get(i));  // 差分值的日期 = 当前日
                }
                dates = diffDates;
                log.debug("Factor {} loaded {} daily return points (diff)", code, values.size());
            }
            result.put(code, new FactorSeries(dates, values));
        }
        return result;
    }

    /**
     * 基于日期交集对齐多因子时序数据
     *
     * 修复前：只按最短序列长度截断，假设所有因子日期天然对齐（实际不成立）
     * 修复后：取所有因子日期的交集，确保每个因子的第t个值对应同一个交易日
     *
     * @param returns 因子代码 → (日期列表, 数值列表)
     * @param codes   有效因子代码
     * @return 对齐后的共同日期列表（已排序）
     */
    private List<LocalDate> alignDates(Map<String, FactorSeries> returns, List<String> codes) {
        if (codes.isEmpty()) return List.of();

        // 取第一个因子的日期集合作为初始交集
        Set<LocalDate> intersection = null;
        for (String code : codes) {
            FactorSeries s = returns.get(code);
            if (s == null || s.dates.isEmpty()) continue;
            Set<LocalDate> datesSet = new LinkedHashSet<>(s.dates);
            if (intersection == null) {
                intersection = datesSet;
            } else {
                intersection.retainAll(datesSet);
            }
        }

        if (intersection == null || intersection.isEmpty()) {
            log.warn("[alignDates] 因子间无共同日期，无法对齐");
            return List.of();
        }

        List<LocalDate> aligned = new ArrayList<>(intersection);
        aligned.sort(LocalDate::compareTo);

        // 日志：报告各因子原始长度 vs 对齐后长度
        if (log.isDebugEnabled()) {
            for (String code : codes) {
                FactorSeries s = returns.get(code);
                log.debug("[alignDates] {} → 原始{}点 → 对齐{}点",
                        code, (s != null ? s.size() : 0), aligned.size());
            }
        }

        return aligned;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 私有：矩阵计算
    // ──────────────────────────────────────────────────────────────────────────

    private double[] calcMeans(double[][] r, int n, int T) {
        double[] means = new double[n];
        for (int i = 0; i < n; i++) {
            double sum = 0;
            for (int t = 0; t < T; t++) sum += r[i][t];
            means[i] = sum / T;
        }
        return means;
    }

    private double[][] calcCovariance(double[][] r, double[] means, int n, int T) {
        double[][] cov = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = i; j < n; j++) {
                double s = 0;
                for (int t = 0; t < T; t++) {
                    s += (r[i][t] - means[i]) * (r[j][t] - means[j]);
                }
                cov[i][j] = s / (T - 1);
                cov[j][i] = cov[i][j];
            }
        }
        return cov;
    }

    /**
     * Markowitz 均值-方差优化：最大化夏普比率
     * 使用梯度下降（负夏普比率）+ 边界约束（权重 ∈ [0,1]，权重之和=1）
     */
    private double[] optimizeMarkowitz(double[] means, double[][] cov, int n) {
        double riskFreeRate = 0.03 / 252; // 日无风险利率
        double[] weights = equalWeights(n);

        // 梯度下降迭代
        double lr = 0.01;
        for (int iter = 0; iter < 5000; iter++) {
            double[] grad = negativeSharpeGrad(weights, means, cov, riskFreeRate, n);
            for (int i = 0; i < n; i++) {
                weights[i] -= lr * grad[i];
                weights[i] = Math.max(0.0, weights[i]);  // 非负约束
            }
            normalize(weights);
            if (iter > 0 && iter % 500 == 0) lr *= 0.9;
        }
        return weights;
    }

    private double[] negativeSharpeGrad(double[] w, double[] mu, double[][] cov,
                                        double rf, int n) {
        double ret = dot(w, mu) - rf;
        double var = quadraticForm(w, cov);
        double vol = Math.sqrt(Math.max(var, 1e-12));
        double sharpe = ret / vol;

        double[] grad = new double[n];
        for (int i = 0; i < n; i++) {
            double dRet = mu[i];
            double dVar = 2 * dotRow(cov[i], w);
            double dVol = dVar / (2 * vol);
            // d(-Sharpe)/dw_i = -(dRet * vol - ret * dVol) / vol²
            grad[i] = -(dRet * vol - ret * dVol) / (vol * vol);
        }
        return grad;
    }

    /**
     * 风险平价优化：每个因子的风险贡献相等
     * 使用牛顿法迭代最小化 Σ(RC_i - target)²
     */
    private double[] optimizeRiskParity(double[][] cov, int n) {
        double[] weights = equalWeights(n);
        double target = 1.0 / n;

        for (int iter = 0; iter < 3000; iter++) {
            double var = quadraticForm(weights, cov);
            double vol = Math.sqrt(Math.max(var, 1e-12));

            // 边际风险贡献
            double[] mrc = new double[n];
            for (int i = 0; i < n; i++) {
                mrc[i] = dotRow(cov[i], weights) / vol;
            }

            // 风险贡献占比
            double[] rc = new double[n];
            for (int i = 0; i < n; i++) {
                rc[i] = weights[i] * mrc[i] / vol;
            }

            // 梯度
            double[] grad = new double[n];
            for (int i = 0; i < n; i++) {
                double err = rc[i] - target;
                // 数值梯度 ≈ 导数简化版
                grad[i] = err * mrc[i];
            }

            double lr = 0.02;
            for (int i = 0; i < n; i++) {
                weights[i] -= lr * grad[i];
                weights[i] = Math.max(1e-6, weights[i]);
            }
            normalize(weights);
        }
        return weights;
    }

    private double[] equalWeights(int n) {
        double[] w = new double[n];
        Arrays.fill(w, 1.0 / n);
        return w;
    }

    /**
     * 计算有效前沿（30个点）
     * 使用双目标优化：在风险和收益之间生成帕累托前沿
     */
    private List<Map<String, Object>> calcEfficientFrontier(double[] means, double[][] cov, int n, int points) {
        List<Map<String, Object>> frontier = new ArrayList<>();

        // 计算全局最小方差组合（有效前沿的左端点）
        double[] minVarWeights = minimizeVariance(cov, n);
        double minVarReturn = dot(minVarWeights, means) * 252;
        double minVarVol = Math.sqrt(Math.max(quadraticForm(minVarWeights, cov), 0) * 252);

        // 计算最大收益组合（有效前沿的右端点）
        int maxRetIdx = 0;
        for (int i = 1; i < n; i++) {
            if (means[i] > means[maxRetIdx]) maxRetIdx = i;
        }
        double[] maxRetWeights = new double[n];
        maxRetWeights[maxRetIdx] = 1.0;
        double maxRetReturn = means[maxRetIdx] * 252;
        double maxRetVol = Math.sqrt(Math.max(cov[maxRetIdx][maxRetIdx], 0) * 252);

        // 在最小方差和最大收益之间生成多个目标收益水平
        for (int k = 0; k < points; k++) {
            double lambda = (double) k / (points - 1); // 0 到 1
            // 在最小方差组合和最大收益组合之间插值目标收益
            double targetRet = minVarReturn + (maxRetReturn - minVarReturn) * lambda;

            // 求解给定目标收益下的最小方差组合
            double[] w = solveMinVarianceForTarget(means, cov, n, targetRet);
            double vol = Math.sqrt(Math.max(quadraticForm(w, cov), 0) * 252);
            double ret = dot(w, means) * 252;

            Map<String, Object> point = new LinkedHashMap<>();
            point.put("return", round4(ret));
            point.put("volatility", round4(vol));
            point.put("sharpe", round4(vol > 0 ? (ret - 0.03) / vol : 0));
            frontier.add(point);
        }
        return frontier;
    }

    /**
     * 求解全局最小方差组合
     */
    private double[] minimizeVariance(double[][] cov, int n) {
        double[] w = equalWeights(n);
        double lr = 0.01;
        for (int iter = 0; iter < 5000; iter++) {
            double[] grad = new double[n];
            for (int i = 0; i < n; i++) {
                grad[i] = 2 * dotRow(cov[i], w);
            }
            for (int i = 0; i < n; i++) {
                w[i] -= lr * grad[i];
                w[i] = Math.max(0, w[i]);
            }
            normalize(w);
            if (iter > 0 && iter % 500 == 0) lr *= 0.9;
        }
        return w;
    }

    /**
     * 求解给定目标收益下的最小方差组合
     * 使用投影梯度法 + 惩罚项
     */
    private double[] solveMinVarianceForTarget(double[] mu, double[][] cov, int n, double targetRet) {
        double[] w = equalWeights(n);
        double lr = 0.005;
        double penalty = 1000.0; // 强惩罚项确保收益约束

        for (int iter = 0; iter < 3000; iter++) {
            double currentRet = dot(w, mu) * 252;
            double retError = currentRet - targetRet;

            double[] grad = new double[n];
            for (int i = 0; i < n; i++) {
                // 方差梯度 + 收益约束惩罚梯度
                double varGrad = 2 * dotRow(cov[i], w);
                double retGrad = 2 * penalty * retError * mu[i] * 252;
                grad[i] = varGrad + retGrad;
            }

            for (int i = 0; i < n; i++) {
                w[i] -= lr * grad[i];
                w[i] = Math.max(0, w[i]); // 非负约束
            }
            normalize(w); // 权重和为1

            if (iter > 0 && iter % 500 == 0) lr *= 0.95;
        }
        return w;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 私有：线性代数工具
    // ──────────────────────────────────────────────────────────────────────────

    private double dot(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }

    private double dotRow(double[] row, double[] w) {
        return dot(row, w);
    }

    private double quadraticForm(double[] w, double[][] cov) {
        double s = 0;
        for (int i = 0; i < w.length; i++) {
            for (int j = 0; j < w.length; j++) {
                s += w[i] * cov[i][j] * w[j];
            }
        }
        return s;
    }

    private void normalize(double[] w) {
        double sum = Arrays.stream(w).sum();
        if (sum > 0) for (int i = 0; i < w.length; i++) w[i] /= sum;
    }

    private double[][] toCorrelation(double[][] cov, int n) {
        double[] stds = new double[n];
        for (int i = 0; i < n; i++) stds[i] = Math.sqrt(Math.max(cov[i][i], 1e-12));
        double[][] corr = new double[n][n];
        for (int i = 0; i < n; i++) {
            corr[i][i] = 1.0;
            for (int j = i + 1; j < n; j++) {
                corr[i][j] = cov[i][j] / (stds[i] * stds[j]);
                corr[j][i] = corr[i][j];
            }
        }
        return corr;
    }

    private double round4(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? 0
                : new BigDecimal(v).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }
}
