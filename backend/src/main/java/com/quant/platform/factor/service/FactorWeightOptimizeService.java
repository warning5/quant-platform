package com.quant.platform.factor.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quant.platform.factor.domain.FactorValue;
import com.quant.platform.factor.mapper.FactorValueMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 因子组合权重优化服务
 *
 * 支持三种优化方法：
 *  1. EQUAL           — 等权（基准）
 *  2. MARKOWITZ       — 均值-方差（最大化夏普比率）
 *  3. RISK_PARITY     — 风险平价（每个因子对组合风险的贡献相等）
 *
 * 输入：多个因子代码 + 日期范围（用 factor_value.rank_value 作为截面收益代理）
 * 输出：每个因子的推荐权重
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactorWeightOptimizeService {

    private final FactorValueMapper factorValueMapper;

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

        // ── 1. 获取各因子的截面 IC 时序（代理收益率）─────────────────────
        Map<String, List<Double>> returns = loadFactorReturns(factorCodes, startDate, endDate);

        // ── 2. 对齐日期（取交集）─────────────────────────────────────────
        List<String> aligned = alignDates(returns, factorCodes);
        if (aligned.size() < 10) {
            throw new IllegalStateException("有效数据点不足（" + aligned.size() + " 个），无法进行优化");
        }

        int n = factorCodes.size();
        int T = aligned.size();

        // ── 3. 提取对齐后的收益矩阵 [n × T] ─────────────────────────────
        double[][] retMatrix = new double[n][T];
        for (int i = 0; i < n; i++) {
            List<Double> r = returns.get(factorCodes.get(i));
            for (int t = 0; t < T; t++) {
                retMatrix[i][t] = r.get(t);
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
        result.put("factorCodes", factorCodes);
        result.put("dataPoints", T);

        // 权重列表
        List<Map<String, Object>> weightList = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("factorCode", factorCodes.get(i));
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

        return result;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 私有：数据加载
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 加载因子截面收益率：用 rank_value 的日变化量（差分）作为日收益代理
     */
    private Map<String, List<Double>> loadFactorReturns(List<String> factorCodes,
                                                         String startDate, String endDate) {
        Map<String, List<Double>> result = new HashMap<>();
        for (String code : factorCodes) {
            // 取全市场 rank_value 的中位数作为因子"收益"
            List<FactorValue> vals = factorValueMapper.selectList(
                    new LambdaQueryWrapper<FactorValue>()
                            .eq(FactorValue::getFactorCode, code)
                            .ge(startDate != null, FactorValue::getCalcDate, startDate)
                            .le(endDate != null, FactorValue::getCalcDate, endDate)
                            .orderByAsc(FactorValue::getCalcDate)
                            .select(FactorValue::getCalcDate, FactorValue::getRankValue)
            );

            // 按日期分组取中位数
            Map<String, Double> dailyMedian = new TreeMap<>();
            Map<String, List<Double>> byDate = new TreeMap<>();
            for (FactorValue fv : vals) {
                if (fv.getRankValue() == null || fv.getCalcDate() == null) continue;
                byDate.computeIfAbsent(fv.getCalcDate().toString(), k -> new ArrayList<>())
                        .add(fv.getRankValue().doubleValue());
            }
            byDate.forEach((date, list) -> {
                list.sort(Double::compareTo);
                int mid = list.size() / 2;
                dailyMedian.put(date, list.size() % 2 == 0
                        ? (list.get(mid - 1) + list.get(mid)) / 2.0
                        : list.get(mid));
            });

            // 差分得到日变化率
            List<String> dates = new ArrayList<>(dailyMedian.keySet());
            List<Double> dailyReturns = new ArrayList<>();
            for (int i = 1; i < dates.size(); i++) {
                double prev = dailyMedian.get(dates.get(i - 1));
                double curr = dailyMedian.get(dates.get(i));
                dailyReturns.add(prev != 0 ? (curr - prev) / Math.abs(prev) : 0.0);
            }
            result.put(code, dailyReturns);
            log.debug("Factor {} loaded {} daily return points", code, dailyReturns.size());
        }
        return result;
    }

    private List<String> alignDates(Map<String, List<Double>> returns, List<String> codes) {
        // 找出所有因子最短的那个序列长度作为对齐基准
        int minLen = codes.stream()
                .mapToInt(c -> returns.getOrDefault(c, Collections.emptyList()).size())
                .min().orElse(0);
        // 截断到共同长度（假设各因子日期对齐）
        codes.forEach(c -> {
            List<Double> r = returns.get(c);
            if (r != null && r.size() > minLen) {
                returns.put(c, r.subList(r.size() - minLen, r.size()));
            }
        });
        // 返回代表日期（简化：序号列表）
        List<String> dates = new ArrayList<>();
        for (int i = 0; i < minLen; i++) dates.add(String.valueOf(i));
        return dates;
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
