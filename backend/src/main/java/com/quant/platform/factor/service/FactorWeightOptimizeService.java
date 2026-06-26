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
 * 2. MARKOWITZ       — 均值-方差（最大化夏普比率），QP active-set 解析解
 * 3. RISK_PARITY     — 风险平价（等风险贡献），cyclical coordinate descent
 * 
 * 关键改进：
 * - 协方差矩阵使用 Ledoit-Wolf shrinkage（缩放单位矩阵目标），解决样本协方差不稳定问题
 * - Markowitz 用 Σ⁻¹(μ-rf) 解析解 + active-set 非负约束，替代梯度下降
 * - Risk Parity 用 coordinate descent（≤20轮收敛），替代3000轮梯度下降
 * - 有效前沿用 Lagrange 乘子法解析解，替代惩罚梯度
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

        // ── 4. 计算均值收益和协方差矩阵（Ledoit-Wolf shrinkage）────────────
        double[] means = calcMeans(retMatrix, n, T);
        double[][] cov = shrinkCovariance(retMatrix, means, n, T);

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

    // ──────────────────────────────────────────────────────────────────────────
    // 私有：协方差 shrinkage
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Ledoit-Wolf 协方差收缩（缩放单位矩阵目标）
     * Σ_shrunk = α·μI + (1-α)·S  其中 μ = tr(S)/n
     * 
     * 收缩强度 α = max(0, min(1, π̂ / (T·δ̂²)))
     * - π̂: 样本协方差各元素渐近方差之和（从4阶矩估计）
     * - δ̂²: S 与收缩目标 F=μI 的 Frobenius 距离
     * - T 大 → α 小（样本够用，少收缩）
     * - T 小 → α 大（样本不够，多收缩）
     * 
     * 效果：保证协方差矩阵正定、数值稳定，避免权重极端偏斜
     */
    private double[][] shrinkCovariance(double[][] retMatrix, double[] means, int n, int T) {
        // 1. 样本协方差
        double[][] S = calcCovariance(retMatrix, means, n, T);

        // 2. 收缩目标 F = μI，μ = tr(S)/n
        double mu = 0;
        for (int i = 0; i < n; i++) mu += S[i][i];
        mu /= n;

        // 3. δ̂² = ||S - μI||²_F / n²
        double delta2 = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double fij = (i == j) ? mu : 0;
                delta2 += (S[i][j] - fij) * (S[i][j] - fij);
            }
        }
        delta2 /= n * n;

        if (delta2 < 1e-10) {
            log.info("[Ledoit-Wolf] S ≈ μI，无需收缩 (δ²≈0)");
            return S;
        }

        // 4. π̂: 各元素渐近方差之和（利用原始数据计算4阶交叉矩）
        // π̂_ij = (1/T)Σ_t (y_it·y_jt)² - s_ij²
        double piHat = 0;
        for (int i = 0; i < n; i++) {
            for (int j = i; j < n; j++) {
                double sum4th = 0;
                for (int t = 0; t < T; t++) {
                    double yit = retMatrix[i][t] - means[i];
                    double yjt = retMatrix[j][t] - means[j];
                    double prod = yit * yjt;
                    sum4th += prod * prod;
                }
                double pij = sum4th / T - S[i][j] * S[i][j];
                piHat += (i == j) ? pij : 2 * pij; // 对称矩阵，off-diagonal 双倍
            }
        }
        piHat /= n * n;

        // 5. 单位矩阵目标的 ρ̂ = 0（f_ij = μ 是常数，与 s_ij 渐近无关）
        // 因此 α* = max(0, min(1, π̂ / (T · δ̂²)))
        double alpha = Math.max(0, Math.min(1, piHat / (T * delta2)));

        // 6. 应用收缩: Σ* = α·μI + (1-α)·S
        double[][] shrunk = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double fij = (i == j) ? mu : 0;
                shrunk[i][j] = alpha * fij + (1 - alpha) * S[i][j];
            }
        }

        log.info("[Ledoit-Wolf] α={}, μ={}, n={}, T={}, δ²={}, π={}",
                round4(alpha), round4(mu), n, T, round4(delta2), round4(piHat));

        return shrunk;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 私有：优化算法
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Markowitz 均值-方差优化：最大化夏普比率
     * 使用 QP active-set 方法（解析解 + 非负约束迭代剔除）
     * 
     * 核心思路：
     * 无约束 tangency portfolio: w* = Σ⁻¹(μ-rf) / (1'Σ⁻¹(μ-rf))
     * 有非负约束时：active-set 迭代——逐步剔除负权重因子，在子集上重解
     * 保证全局最优（凸目标+线性约束），收敛速度远快于梯度下降
     */
    private double[] optimizeMarkowitz(double[] means, double[][] cov, int n) {
        double riskFreeRate = 0.03 / 252;

        // Active-set: 初始包含所有因子
        List<Integer> activeSet = new ArrayList<>();
        for (int i = 0; i < n; i++) activeSet.add(i);

        double[] weights = new double[n];

        // 最多 n 次迭代（每次剔除一个因子）
        for (int iter = 0; iter < n; iter++) {
            int m = activeSet.size();
            if (m == 0) return equalWeights(n); // 全部被剔除，回退等权

            // 提取活跃子集的均值和协方差
            double[] subMeans = new double[m];
            double[][] subCov = new double[m][m];
            for (int i = 0; i < m; i++) {
                int idx_i = activeSet.get(i);
                subMeans[i] = means[idx_i] - riskFreeRate;
                for (int j = 0; j < m; j++) {
                    subCov[i][j] = cov[idx_i][activeSet.get(j)];
                }
            }

            // Σ⁻¹(μ-rf) / (1'Σ⁻¹(μ-rf))
            double[][] subCovInv = invertMatrix(subCov, m);
            double[] rawW = new double[m];
            double sumRaw = 0;
            for (int i = 0; i < m; i++) {
                for (int j = 0; j < m; j++) rawW[i] += subCovInv[i][j] * subMeans[j];
                sumRaw += rawW[i];
            }

            // Σ⁻¹(μ-rf) 加和 ≈ 0 → 因子超额收益接近无风险利率，tangency 无定义
            // 回退到最小方差组合
            if (Math.abs(sumRaw) < 1e-12) {
                double[] minVar = solveMinVarianceOnSubset(activeSet, cov, n);
                for (int i = 0; i < n; i++) weights[i] = minVar[i];
                normalize(weights);
                log.debug("[Markowitz] Σ⁻¹(μ-rf)≈0, 回退到 min-var (active={})", m);
                return weights;
            }

            // 归一化为权重
            for (int i = 0; i < m; i++) rawW[i] /= sumRaw;

            // 检查负权重
            int mostNegIdx = -1;
            double mostNegVal = 0;
            for (int i = 0; i < m; i++) {
                if (rawW[i] < mostNegVal) {
                    mostNegVal = rawW[i];
                    mostNegIdx = i;
                }
            }

            if (mostNegIdx == -1) {
                // 全部 ≥ 0 → 收敛！
                for (int i = 0; i < m; i++) weights[activeSet.get(i)] = rawW[i];
                normalize(weights);
                log.debug("[Markowitz] QP active-set 收敛 (iter={}, active={})", iter, m);
                return weights;
            }

            // 剔除最负权重的因子
            activeSet.remove(mostNegIdx);
        }

        // 剩余1个因子 → 100%权重
        if (activeSet.size() == 1) {
            weights[activeSet.get(0)] = 1.0;
            return weights;
        }
        return equalWeights(n); // 安全回退
    }

    /**
     * 风险平价优化：每个因子对组合风险的贡献相等
     * 使用 cyclical coordinate descent (Griveau-Billion, Richard, Roncalli 2013)
     * 
     * 核心迭代: w_i = (1/n) · σ_p / MRC_i, 归一化
     * MRC_i = Σw_i / σ_p (边际风险贡献)
     * RC_i = w_i · MRC_i / σ_p = w_i · Σw_i / σ_p² (风险贡献占比)
     * 
     * 通常 ≤20 轮收敛到精确解，比梯度下降3000轮快150倍
     */
    private double[] optimizeRiskParity(double[][] cov, int n) {
        double[] weights = equalWeights(n);
        double targetRC = 1.0 / n;

        for (int iter = 0; iter < 30; iter++) {
            double var = quadraticForm(weights, cov);
            double vol = Math.sqrt(Math.max(var, 1e-12));

            // MRC_i = Σw_i / σ_p
            double[] mrc = new double[n];
            for (int i = 0; i < n; i++) {
                mrc[i] = dotRow(cov[i], weights) / vol;
            }

            // 更新权重: w_i = targetRC * σ_p / MRC_i
            double sumNew = 0;
            for (int i = 0; i < n; i++) {
                weights[i] = targetRC * vol / Math.max(mrc[i], 1e-12);
                sumNew += weights[i];
            }

            // 归一化
            if (sumNew > 0) {
                for (int i = 0; i < n; i++) weights[i] /= sumNew;
            }

            // 收敛检查: max |RC_i - 1/n| < 0.001
            double maxErr = 0;
            double newVar = quadraticForm(weights, cov);
            for (int i = 0; i < n; i++) {
                double rc = weights[i] * dotRow(cov[i], weights) / newVar;
                maxErr = Math.max(maxErr, Math.abs(rc - targetRC));
            }
            if (maxErr < 0.001) {
                log.debug("[RiskParity] coordinate descent 收敛 (iter={}, max RC err={})", iter, round4(maxErr));
                break;
            }
        }
        return weights;
    }

    private double[] equalWeights(int n) {
        double[] w = new double[n];
        Arrays.fill(w, 1.0 / n);
        return w;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 私有：有效前沿（Lagrange 乘子法）
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 计算有效前沿（30个点）
     * 用 Lagrange 乘子法 + active-set 替代惩罚梯度，精度和速度大幅提升
     */
    private List<Map<String, Object>> calcEfficientFrontier(double[] means, double[][] cov, int n, int points) {
        List<Map<String, Object>> frontier = new ArrayList<>();

        // 计算全局最小方差组合（有效前沿左端点）
        double[] minVarWeights = minimizeVariance(cov, n);
        double minVarReturn = dot(minVarWeights, means) * 252;
        double minVarVol = Math.sqrt(Math.max(quadraticForm(minVarWeights, cov), 0) * 252);

        // 最大收益组合（右端点：单因子100%权重）
        int maxRetIdx = 0;
        for (int i = 1; i < n; i++) {
            if (means[i] > means[maxRetIdx]) maxRetIdx = i;
        }
        double maxRetReturn = means[maxRetIdx] * 252;
        double maxRetVol = Math.sqrt(Math.max(cov[maxRetIdx][maxRetIdx], 0) * 252);

        // 在最小方差和最大收益之间生成30个目标收益水平
        for (int k = 0; k < points; k++) {
            double lambda = (double) k / (points - 1);
            double targetRet = minVarReturn + (maxRetReturn - minVarReturn) * lambda;

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
     * 全局最小方差组合（解析解 + active-set）
     * w* = Σ⁻¹1 / (1'Σ⁻¹1)，负权重因子逐步剔除
     */
    private double[] minimizeVariance(double[][] cov, int n) {
        List<Integer> activeSet = new ArrayList<>();
        for (int i = 0; i < n; i++) activeSet.add(i);
        double[] weights = new double[n];

        for (int iter = 0; iter < n; iter++) {
            int m = activeSet.size();
            if (m == 0) return equalWeights(n);
            if (m == 1) { weights[activeSet.get(0)] = 1.0; return weights; }

            // 子集协方差
            double[][] subCov = new double[m][m];
            for (int i = 0; i < m; i++) {
                for (int j = 0; j < m; j++) {
                    subCov[i][j] = cov[activeSet.get(i)][activeSet.get(j)];
                }
            }

            double[][] subCovInv = invertMatrix(subCov, m);

            // Σ⁻¹1 / (1'Σ⁻¹1)
            double[] rawW = new double[m];
            double sumRaw = 0;
            for (int i = 0; i < m; i++) {
                for (int j = 0; j < m; j++) rawW[i] += subCovInv[i][j];
                sumRaw += rawW[i];
            }
            if (sumRaw <= 0) return equalWeights(n);
            for (int i = 0; i < m; i++) rawW[i] /= sumRaw;

            // 检查负权重
            int mostNegIdx = -1;
            double mostNegVal = 0;
            for (int i = 0; i < m; i++) {
                if (rawW[i] < mostNegVal) { mostNegVal = rawW[i]; mostNegIdx = i; }
            }
            if (mostNegIdx == -1) {
                for (int i = 0; i < m; i++) weights[activeSet.get(i)] = rawW[i];
                normalize(weights);
                return weights;
            }
            activeSet.remove(mostNegIdx);
        }
        return equalWeights(n);
    }

    /**
     * 给定目标收益下的最小方差组合（Lagrange 乘子法 + active-set）
     * 
     * min w'Σw  s.t. w'μ = r_target, Σw_i = 1
     * 解: w = (λ₁Σ⁻¹μ + λ₂Σ⁻¹1) / 2
     * λ₁, λ₂ 由 2×2 线性方程组求解:
     * | a  b | | λ₁ |   | 2·r_target |
     * | b  d | | λ₂ | = | 2          |
     * a = μ'Σ⁻¹μ, b = 1'Σ⁻¹μ, d = 1'Σ⁻¹1
     */
    private double[] solveMinVarianceForTarget(double[] mu, double[][] cov, int n, double targetRet) {
        List<Integer> activeSet = new ArrayList<>();
        for (int i = 0; i < n; i++) activeSet.add(i);
        double[] weights = new double[n];

        double targetDaily = targetRet / 252; // 年化 → 日化

        for (int iter = 0; iter < n; iter++) {
            int m = activeSet.size();
            if (m <= 1) {
                if (m == 1) weights[activeSet.get(0)] = 1.0;
                return weights;
            }

            // 子集
            double[] subMu = new double[m];
            double[][] subCov = new double[m][m];
            for (int i = 0; i < m; i++) {
                int idx_i = activeSet.get(i);
                subMu[i] = mu[idx_i];
                for (int j = 0; j < m; j++) {
                    subCov[i][j] = cov[idx_i][activeSet.get(j)];
                }
            }

            double[][] subCovInv = invertMatrix(subCov, m);

            // 计算 Σ⁻¹μ 和 Σ⁻¹1
            double[] covInvMu = new double[m];
            double[] covInv1 = new double[m];
            double a = 0, b = 0, d = 0;
            for (int i = 0; i < m; i++) {
                covInvMu[i] = 0; covInv1[i] = 0;
                for (int j = 0; j < m; j++) {
                    covInvMu[i] += subCovInv[i][j] * subMu[j];
                    covInv1[i] += subCovInv[i][j];
                }
                a += subMu[i] * covInvMu[i];   // μ'Σ⁻¹μ
                b += covInv1[i] * subMu[i];     // 1'Σ⁻¹μ (== μ'Σ⁻¹1)
                d += covInv1[i];                 // 1'Σ⁻¹1
            }

            // 解 2×2 系统
            double rhs1 = 2 * targetDaily;
            double rhs2 = 2;
            double det = a * d - b * b;

            if (Math.abs(det) < 1e-12) {
                // 退化 → 回退到子集最小方差
                double[] subW = new double[m];
                double sumSubW = 0;
                for (int i = 0; i < m; i++) {
                    subW[i] = 0;
                    for (int j = 0; j < m; j++) subW[i] += subCovInv[i][j];
                    sumSubW += subW[i];
                }
                if (sumSubW > 0) {
                    for (int i = 0; i < m; i++) subW[i] /= sumSubW;
                } else {
                    Arrays.fill(subW, 1.0 / m);
                }
                for (int i = 0; i < m; i++) weights[activeSet.get(i)] = subW[i];
                normalize(weights);
                return weights;
            }

            double lambda1 = (rhs1 * d - rhs2 * b) / det;
            double lambda2 = (rhs2 * a - rhs1 * b) / det;

            // w = (λ₁Σ⁻¹μ + λ₂Σ⁻¹1) / 2
            double[] subW = new double[m];
            for (int i = 0; i < m; i++) {
                subW[i] = (lambda1 * covInvMu[i] + lambda2 * covInv1[i]) / 2;
            }

            // 检查负权重
            int mostNegIdx = -1;
            double mostNegVal = 0;
            for (int i = 0; i < m; i++) {
                if (subW[i] < mostNegVal) { mostNegVal = subW[i]; mostNegIdx = i; }
            }
            if (mostNegIdx == -1) {
                for (int i = 0; i < m; i++) weights[activeSet.get(i)] = subW[i];
                normalize(weights);
                return weights;
            }
            activeSet.remove(mostNegIdx);
        }
        return equalWeights(n);
    }

    /**
     * 在指定子集上求解最小方差组合（Markowitz 回退用）
     */
    private double[] solveMinVarianceOnSubset(List<Integer> activeSet, double[][] cov, int n) {
        double[] weights = new double[n];
        int m = activeSet.size();
        if (m == 0) return equalWeights(n);
        if (m == 1) { weights[activeSet.get(0)] = 1.0; return weights; }

        double[][] subCov = new double[m][m];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < m; j++) {
                subCov[i][j] = cov[activeSet.get(i)][activeSet.get(j)];
            }
        }
        double[][] subCovInv = invertMatrix(subCov, m);

        double[] subW = new double[m];
        double sumW = 0;
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < m; j++) subW[i] += subCovInv[i][j];
            sumW += subW[i];
        }
        if (sumW > 0) {
            for (int i = 0; i < m; i++) subW[i] /= sumW;
        } else {
            Arrays.fill(subW, 1.0 / m);
        }
        for (int i = 0; i < m; i++) weights[activeSet.get(i)] = subW[i];
        return weights;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 私有：矩阵运算工具
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 矩阵求逆（Gauss-Jordan 消元法，带部分选主元）
     * 适用于小矩阵 (n ≤ 20)，Ledoit-Wolf 收缩后保证正定可逆
     */
    private double[][] invertMatrix(double[][] A, int n) {
        // 增广矩阵 [A | I]
        double[][] aug = new double[n][2 * n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) aug[i][j] = A[i][j];
            aug[i][n + i] = 1.0;
        }

        // Gauss-Jordan 消元 + 部分选主元
        for (int col = 0; col < n; col++) {
            // 选主元: max |aug[row][col]| for row >= col
            int pivotRow = col;
            double pivotVal = Math.abs(aug[col][col]);
            for (int row = col + 1; row < n; row++) {
                if (Math.abs(aug[row][col]) > pivotVal) {
                    pivotVal = Math.abs(aug[row][col]);
                    pivotRow = row;
                }
            }
            if (pivotVal < 1e-12) {
                // Ledoit-Wolf 收缩后理论上不应出现此情况
                log.warn("[invertMatrix] pivot ≈ 0 at col={}, 矩阵可能奇异", col);
                throw new RuntimeException("协方差矩阵不可逆（pivot ≈ 0），请检查数据");
            }

            // 交换行
            if (pivotRow != col) {
                double[] tmp = aug[col];
                aug[col] = aug[pivotRow];
                aug[pivotRow] = tmp;
            }

            // 主元行归一
            double pivot = aug[col][col];
            for (int j = 0; j < 2 * n; j++) aug[col][j] /= pivot;

            // 消去其他行
            for (int row = 0; row < n; row++) {
                if (row == col) continue;
                double factor = aug[row][col];
                for (int j = 0; j < 2 * n; j++) aug[row][j] -= factor * aug[col][j];
            }
        }

        // 提取逆矩阵
        double[][] inv = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) inv[i][j] = aug[i][n + j];
        }
        return inv;
    }

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
