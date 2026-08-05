package com.quant.platform.backtest.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.math3.stat.regression.OLSMultipleLinearRegression;
import static com.quant.platform.backtest.service.FactorStyleAttributionService.*;
import java.util.*;

@Slf4j
public final class OlsRegressionCalculator {

    private OlsRegressionCalculator() {}

    /**
     * OLS 多元回归
     */
    public static RegressionResult runOLS(List<DailyExcess> excess, List<double[]> factors, int n, int k) {
        double[] y = new double[n];
        double[][] x = new double[n][k];

        for (int i = 0; i < n; i++) {
            y[i] = excess.get(i).excess();
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
    public static double computePValue(double t, int df) {
        if (df <= 0) return 1.0;
        double x = df / (df + t * t);
        // 不完全 Beta 函数近似
        double ibeta = regularizedIncompleteBeta(x, df / 2.0, 0.5);
        return ibeta;
    }

    /** 正则化不完全 Beta 函数 I_x(a,b) 的近似算法 */
    public static double regularizedIncompleteBeta(double x, double a, double b) {
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
    public static double logGamma(double x) {
        double[] coef = {76.18009172947146, -86.50532032941677,
                24.01409824083091, -1.231739572450155,
                0.1208650973866179e-2, -0.5395239384953e-5};
        double y = x, tmp = x + 5.5;
        tmp -= (x + 0.5) * Math.log(tmp);
        double ser = 1.000000000190015;
        for (int j = 0; j < 6; j++) ser += coef[j] / ++y;
        return -tmp + Math.log(2.5066282746310005 * ser / x);
    }

    /** 计算最近 N 个点的斜率（简单线性回归） */
    public static double computeSlope(List<AlphaWindowPoint> points, int n) {
        List<AlphaWindowPoint> tail = points.subList(points.size() - n, points.size());
        int size = tail.size();
        // x = 0,1,2,...,n-1
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        for (int i = 0; i < size; i++) {
            double x = i;
            double y = tail.get(i).alpha();
            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumX2 += x * x;
        }
        double denom = size * sumX2 - sumX * sumX;
        return Math.abs(denom) > 1e-12 ? (size * sumXY - sumX * sumY) / denom : 0;
    }

    public static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
