package com.quant.platform.backtest.service;

import lombok.extern.slf4j.Slf4j;
import static com.quant.platform.backtest.service.OlsRegressionCalculator.*;
import static com.quant.platform.backtest.service.FactorStyleAttributionService.*;
import java.util.*;
import java.time.LocalDate;

@Slf4j
public final class AttributionResultAssembler {

    private AttributionResultAssembler() {}

    // ════════════════════════════════════════════════════════════════
    // A1+A2: Alpha 解读增强 + 显著性检验
    // ════════════════════════════════════════════════════════════════

    /**
     * 构建 Alpha 解读摘要 (A1)
     */
    public static Map<String, Object> buildAlphaInterpretation(RegressionResult reg, int observationDays) {
        Map<String, Object> interp = new LinkedHashMap<>();
        interp.put("alphaPerDay", round4(reg.alpha()));
        interp.put("annualizedAlpha", round4(reg.alpha() * 252));
        interp.put("alphaTStat", round4(reg.alphaTStat()));
        interp.put("alphaPValue", round4(reg.alphaPValue()));
        interp.put("alphaSignificant", Math.abs(reg.alphaTStat()) >= 1.96);

        double pct = reg.alpha() * observationDays * 100; // 全期 Alpha 贡献百分比
        interp.put("totalAlphaPct", round4(pct));

        // 解读文案（R² 感知）
        String interpretation;
        double r2 = reg.rSquared();
        int r2Pct = (int) Math.round(r2 * 100);

        if (r2 < 0.30) {
            interpretation = String.format(
                    "模型解释力弱（R²=%d%%），因子模型覆盖不足，无论 Alpha 是否显著，" +
                    "超额收益来源无法通过当前因子框架判断——%d%% 的收益变动来自其他因素。",
                    r2Pct, 100 - r2Pct);
        } else if (Math.abs(reg.alphaTStat()) >= 2.58) {
            interpretation = String.format(
                    "Alpha 高度显著 (t=%.2f, p=%.4f)，日均 Alpha=%.4f%%，年化 %.2f%%。" +
                    "超额收益中有显著部分来自策略本身的选股/择时能力，非运气所致。",
                    reg.alphaTStat(), reg.alphaPValue(), reg.alpha() * 100, reg.alpha() * 252 * 100);
        } else if (Math.abs(reg.alphaTStat()) >= 1.96) {
            interpretation = String.format(
                    "Alpha 显著 (t=%.2f, p=%.4f)，日均 Alpha=%.4f%%。" +
                    "策略有一定超额选股能力，但须结合样本外验证确认。",
                    reg.alphaTStat(), reg.alphaPValue(), reg.alpha() * 100);
        } else {
            interpretation = String.format(
                    "Alpha 不显著 (t=%.2f, p=%.4f)，超额收益主要由因子暴露驱动。" +
                    "策略无证据表明存在独立选股能力，表现归因于风格/因子倾斜。",
                    reg.alphaTStat(), reg.alphaPValue());
        }
        interp.put("interpretation", interpretation);

        // 残差分解
        double totalAlphaContrib = reg.alpha() * observationDays;
        interp.put("totalAlphaContribution", round4(totalAlphaContrib));

        return interp;
    }

    /** 构建风格偏向解读（白话版，R² 感知） */
    public static String buildStyleBiasDescription(RegressionResult reg) {
        double mktT = reg.tStats()[0], smbT = reg.tStats()[1], hmlT = reg.tStats()[2];
        double mktBeta = reg.betas()[0];
        double smbBeta = reg.betas()[1], hmlBeta = reg.betas()[2];
        double r2 = reg.rSquared();
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

    /**
     * 按 rebalance 期间分解因子贡献
     */
    public static List<Map<String, Object>> computePeriodContributions(
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
            dateToIdx.put(alignedExcess.get(i).date(), i);
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
                if (de.date().isAfter(start) && !de.date().isAfter(end)) {
                    periodExcess += de.excess();
                    Integer idx = dateToIdx.get(de.date());
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
                periodContributions[f] = regResult.betas()[f] * periodFactorRets[f];
                periodTotalContrib += periodContributions[f];
            }

            List<Map<String, Object>> factorBreakdown = new ArrayList<>();
            for (int f = 0; f < factors.size(); f++) {
                Map<String, Object> fb = new LinkedHashMap<>();
                fb.put("factorCode", factors.get(f).code());
                fb.put("factorName", factors.get(f).name());
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
    public static Map<LocalDate, Double> buildNavMap(List<Map<String, Object>> curve) {
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
}
