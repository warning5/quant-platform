package com.quant.platform.stock.analysis.engine;

import com.quant.platform.stock.analysis.domain.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import static com.quant.platform.stock.analysis.engine.SignalItemFactory.*;
import static com.quant.platform.stock.analysis.engine.SignalScoreConstants.*;

/**
 * 基本面打分器（满分 30）
 * ROE/净利率/PE/PB/营收与净利增速/资产负债率 的评分与明细项构建，
 * 金融行业走差异化口径。
 */
@Slf4j
@Component
public class SignalFundamentalScorer {

    /**
     * 判断是否为金融行业股票
     */
    public boolean isFinancialStock(FundamentalSignal fundamental) {
        if (fundamental == null || fundamental.getIndustry() == null) return false;
        return FINANCIAL_INDUSTRIES.contains(fundamental.getIndustry().trim());
    }

    /**
     * 计算基本面得分（满分30）
     * 评分项（共8项）：
     *   ROE(4分) + 净利率(3分) + PE估值(3分) + 营收增速(3分)
     *   + 净利增速(3分) + PB估值(2分) + 毛利率(3分) + 资产负债率(2分)
     *   + 现金流质量(2分) + 偿债能力(2分) + 研报评级(3分) = 30分
     * 不评分仅展示：营收/净利润绝对值、扣非增速、商誉、存货、货币资金、自由现金流、研报覆盖热度
     */
    public int calcFundamentalScore(FundamentalSignal fundamental) {
        if (fundamental == null) return 0;

        int score = 0;

        // === 1. ROE（4分） ===
        if (fundamental.getRoe() != null) {
            double roe = fundamental.getRoe().doubleValue();
            if (roe >= ROE_THRESHOLD) {
                score += 4;
            } else if (roe >= ROE_MED) {
                score += 2;
            }
        }

        // === 2. 净利率（3分）— 新增核心盈利指标 ===
        if (fundamental.getNetProfitMargin() != null) {
            double nm = fundamental.getNetProfitMargin().doubleValue();
            if (nm >= NET_PROFIT_MARGIN_GOOD) {
                score += 3;
            } else if (nm >= NET_PROFIT_MARGIN_MED) {
                score += 2;
            } else if (nm > 0) {
                score += 1;
            }
        }

        // === 3. PE估值（3分）：优先使用历史分位，分位缺失则用绝对值 ===
        boolean peScored = false;
        if (fundamental.getPePercentile() != null) {
            double pct = fundamental.getPePercentile().doubleValue();
            if (pct <= 20) {
                score += 3;
            } else if (pct <= 40) {
                score += 2;
            } else if (pct <= 60) {
                score += 1;
            }
            peScored = true;
        }
        if (!peScored && fundamental.getPeTtm() != null) {
            double pe = fundamental.getPeTtm().doubleValue();
            if (pe > 0 && pe < PE_TTM_LOW) {
                score += 3;
            } else if (pe < PE_TTM_HIGH) {
                score += 2;
            } else if (pe < PE_TTM_EXTREME) {
                score += 1;
            }
        }

        // === 4. 营收增速（3分） ===
        if (fundamental.getRevenueYoy() != null) {
            double rev = fundamental.getRevenueYoy().doubleValue();
            if (rev >= REVENUE_YOY_GOOD) {
                score += 3;
            } else if (rev >= REVENUE_YOY_PASS) {
                score += 2;
            } else if (rev > 0) {
                score += 1;
            }
        }

        // === 5. 归母净利润增速（3分） ===
        if (fundamental.getNetProfitYoy() != null) {
            double np = fundamental.getNetProfitYoy().doubleValue();
            if (np >= NETPROFIT_YOY_GOOD) {
                score += 3;
            } else if (np >= NETPROFIT_YOY_PASS) {
                score += 2;
            } else if (np > 0) {
                score += 1;
            }
        }

        // === 6. PB（2分）：优先使用历史分位 ===
        boolean pbScored = false;
        if (fundamental.getPbPercentile() != null) {
            double pct = fundamental.getPbPercentile().doubleValue();
            if (pct <= 20) {
                score += 2;
            } else if (pct <= 40) {
                score += 1;
            }
            pbScored = true;
        }
        if (!pbScored && fundamental.getPb() != null) {
            double pb = fundamental.getPb().doubleValue();
            if (pb > 0 && pb < PB_LOW) {
                score += 2;
            } else if (pb < PB_MID) {
                score += 1;
            }
        }

        // === 7. 毛利率（3分） ===
        if (fundamental.getGrossMargin() != null) {
            double gm = fundamental.getGrossMargin().doubleValue();
            if (gm >= 40.0) {
                score += 3;
            } else if (gm >= 20.0) {
                score += 2;
            } else if (gm > 0) {
                score += 1;
            }
        }

        // === 8. 资产负债率（2分）— 风险控制 ===
        if (fundamental.getDebtRatio() != null) {
            double dr = fundamental.getDebtRatio().doubleValue();
            if (dr <= DEBT_RATIO_GOOD) {
                score += 2;
            } else if (dr <= DEBT_RATIO_MED) {
                score += 1;
            }
            // >60% 不加分（偏高，风险上升）
        }

        // === 9. 现金流质量（2分） ===
        // 数据库存储为百分比，需除以100转为倍数
        if (fundamental.getOperatingCfToNp() != null) {
            double cfNp = fundamental.getOperatingCfToNp().doubleValue() / 100.0;
            if (cfNp >= 1.0) {
                score += 2;
            } else if (cfNp >= 0.5) {
                score += 1;
            }
        }

        // === 10. 偿债能力（2分） ===
        if (fundamental.getCurrentRatio() != null && fundamental.getCurrentRatio().doubleValue() >= 1.5) {
            score += 1;
        }
        if (fundamental.getQuickRatio() != null && fundamental.getQuickRatio().doubleValue() >= 1.0) {
            score += 1;
        }

        // === 11. 研报评级（3分，从5分降至3分，降低主观权重）===
        int researchScore = fundamental != null ? fundamental.getResearchScore() : 0;
        if (researchScore >= 5) {
            score += 3;
        } else if (researchScore >= 3) {
            score += 2;
        } else if (researchScore >= 1) {
            score += 1;
        }
        // 研报覆盖热度：仅展示，不参与评分

        return Math.max(0, Math.min(FUNDAMENTAL_WEIGHT, score));
    }

    public List<ScoreDetail.ScoreItem> buildFundamentalItems(FundamentalSignal fundamental) {
        List<ScoreDetail.ScoreItem> items = new ArrayList<>();

        // ========== 评分项（满分30）==========

        // === 1. ROE（4分） ===
        BigDecimal roe = fundamental != null ? fundamental.getRoe() : null;
        double roeVal = roe != null ? roe.doubleValue() : 0;
        int roeScore = 0;
        if (roe != null) {
            if (roeVal >= ROE_THRESHOLD) roeScore = 4;
            else if (roeVal >= ROE_MED) roeScore = 2;
        }
        items.add(buildItem("ROE", roe != null ? roe.setScale(2, RoundingMode.HALF_UP) + "%" : "-",
                roeScore, 4, "ROE≥10%=4分(优质), ≥5%=2分(中等)，ROE越高盈利能力越强", false,
                roeVal >= 10 ? "green" : "default"));

        // === 2. 净利率（3分）— 新增 ===
        BigDecimal nm = fundamental != null ? fundamental.getNetProfitMargin() : null;
        double nmVal = nm != null ? nm.doubleValue() : 0;
        int nmScore = 0;
        if (nm != null) {
            if (nmVal >= NET_PROFIT_MARGIN_GOOD) nmScore = 3;
            else if (nmVal >= NET_PROFIT_MARGIN_MED) nmScore = 2;
            else if (nmVal > 0) nmScore = 1;
        }
        items.add(buildItem("净利率", nm != null ? nm.setScale(2, RoundingMode.HALF_UP) + "%" : "-",
                nmScore, 3, "净利率≥15%=3分(高盈利)，≥5%=2分(中等)，>0%=1分；净利率=归母净利润/营业收入，衡量最终盈利效率", false,
                nmVal >= 15 ? "green" : nmVal >= 5 ? "default" : "red"));

        // === 3. PE估值（3分） ===
        BigDecimal pePercentile = fundamental != null ? fundamental.getPePercentile() : null;
        BigDecimal peTtm = fundamental != null ? fundamental.getPeTtm() : null;
        String peDisplay;
        int peScore = 0;
        if (pePercentile != null) {
            peDisplay = "分位" + pePercentile.setScale(1, RoundingMode.HALF_UP) + "%" +
                    (peTtm != null ? " (PE=" + peTtm.setScale(1, RoundingMode.HALF_UP) + ")" : "");
            double pctVal = pePercentile.doubleValue();
            if (pctVal <= 20) peScore = 3;
            else if (pctVal <= 40) peScore = 2;
            else if (pctVal <= 60) peScore = 1;
        } else if (peTtm != null && peTtm.doubleValue() > 0) {
            peDisplay = "PE=" + peTtm.setScale(2, RoundingMode.HALF_UP).toString();
            double peVal = peTtm.doubleValue();
            if (peVal < PE_TTM_LOW) peScore = 3;
            else if (peVal < PE_TTM_HIGH) peScore = 2;
            else if (peVal < PE_TTM_EXTREME) peScore = 1;
        } else {
            peDisplay = "-";
        }
        items.add(buildItem("PE估值", peDisplay, peScore, 3,
                "PE历史分位：≤20%=3分(低估)，≤40%=2分(偏低)，≤60%=1分；分位缺失时用绝对PE：<15=3分，<40=2分，<100=1分",
                false, peScore >= 2 ? "green" : peScore == 1 ? "default" : "red"));

        // === 4. 营收增速（3分） ===
        BigDecimal rev = fundamental != null ? fundamental.getRevenueYoy() : null;
        double revVal = rev != null ? rev.doubleValue() : 0;
        int revScore = 0;
        if (rev != null) {
            if (revVal >= REVENUE_YOY_GOOD) revScore = 3;
            else if (revVal >= REVENUE_YOY_PASS) revScore = 2;
            else if (revVal > 0) revScore = 1;
        }
        items.add(buildItem("营收增速", rev != null ? rev.setScale(2, RoundingMode.HALF_UP) + "%" : "-",
                revScore, 3, "营收增速≥20%=3分，≥10%=2分，>0%=1分；正增长表示业务扩张中", false,
                revVal > 0 ? "green" : "default"));

        // === 5. 净利增速（3分） ===
        BigDecimal np = fundamental != null ? fundamental.getNetProfitYoy() : null;
        double npVal = np != null ? np.doubleValue() : 0;
        int npScore = 0;
        if (np != null) {
            if (npVal >= NETPROFIT_YOY_GOOD) npScore = 3;
            else if (npVal >= NETPROFIT_YOY_PASS) npScore = 2;
            else if (npVal > 0) npScore = 1;
        }
        items.add(buildItem("净利增速", np != null ? np.setScale(2, RoundingMode.HALF_UP) + "%" : "-",
                npScore, 3, "净利增速≥20%=3分，≥10%=2分，>0%=1分；反映最终盈利质量", false,
                npVal > 0 ? "green" : "default"));

        // === 6. PB估值（2分） ===
        BigDecimal pbPercentile = fundamental != null ? fundamental.getPbPercentile() : null;
        BigDecimal pb = fundamental != null ? fundamental.getPb() : null;
        String pbDisplay;
        int pbScore = 0;
        if (pbPercentile != null) {
            pbDisplay = "分位" + pbPercentile.setScale(1, RoundingMode.HALF_UP) + "%" +
                    (pb != null ? " (PB=" + pb.setScale(2, RoundingMode.HALF_UP) + ")" : "");
            double pctVal = pbPercentile.doubleValue();
            if (pctVal <= 20) pbScore = 2;
            else if (pctVal <= 40) pbScore = 1;
        } else if (pb != null && pb.doubleValue() > 0) {
            pbDisplay = "PB=" + pb.setScale(2, RoundingMode.HALF_UP).toString();
            double pbVal = pb.doubleValue();
            if (pbVal < PB_LOW) pbScore = 2;
            else if (pbVal < PB_MID) pbScore = 1;
        } else {
            pbDisplay = "-";
        }
        items.add(buildItem("PB估值", pbDisplay, pbScore, 2,
                "PB历史分位：≤20%=2分(低估)，≤40%=1分；分位缺失时用绝对PB：<3=2分，<5=1分", false,
                pbScore >= 1 ? "green" : "default"));

        // === 7. 毛利率（3分） ===
        BigDecimal gm = fundamental != null ? fundamental.getGrossMargin() : null;
        double gmVal = gm != null ? gm.doubleValue() : 0;
        int gmScore = 0;
        boolean isFin = isFinancialStock(fundamental);
        if (isFin) {
            // 金融股不适用毛利率（银行用净息差，保险用综合成本率）
            gmScore = 1; // 不扣分，给基础分
            items.add(buildItem("毛利率", "不适用", gmScore, 3,
                    "金融行业不适用毛利率指标（银行看净息差/利差，保险看综合成本率）", true, "default"));
        } else if (gm != null) {
            if (gmVal >= 40.0) gmScore = 3;
            else if (gmVal >= 20.0) gmScore = 2;
            else if (gmVal > 0) gmScore = 1;
            items.add(buildItem("毛利率", gm.setScale(2, RoundingMode.HALF_UP) + "%",
                    gmScore, 3, "毛利率≥40%=3分，≥20%=2分；高毛利率说明定价权强（消费/医药特征）", false,
                    gmVal >= 40 ? "green" : "default"));
        } else {
            items.add(buildItem("毛利率", "-", 0, 3,
                    "毛利率≥40%=3分，≥20%=2分；高毛利率说明定价权强（消费/医药特征）", false, "default"));
        }

        // === 8. 资产负债率（2分）— 新增风险控制 ===
        BigDecimal dr = fundamental != null ? fundamental.getDebtRatio() : null;
        double drVal = dr != null ? dr.doubleValue() : 0;
        int drScore = 0;
        if (dr != null) {
            if (drVal <= DEBT_RATIO_GOOD) drScore = 2;
            else if (drVal <= DEBT_RATIO_MED) drScore = 1;
        }
        items.add(buildItem("资产负债率", dr != null ? dr.setScale(1, RoundingMode.HALF_UP) + "%" : "-",
                drScore, 2, "资产负债率≤40%=2分(健康)，≤60%=1分(可接受)，>60%=0分(偏高，关注偿债风险)", false,
                drVal <= 40 ? "green" : drVal <= 60 ? "default" : "red"));

        // === 9. 现金流质量（2分） ===
        // operatingCfToNp 在数据库中存为百分比（375.00 = 375% = 3.75倍），需除以100转为倍数
        BigDecimal cfNpRaw = fundamental != null ? fundamental.getOperatingCfToNp() : null;
        BigDecimal cfNp = cfNpRaw != null ? cfNpRaw.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP) : null;
        double cfNpVal = cfNp != null ? cfNp.doubleValue() : 0;
        int cfScore = 0;
        if (cfNp != null && cfNpVal > 0) {
            if (cfNpVal >= 1.0) cfScore = 2;
            else if (cfNpVal >= 0.5) cfScore = 1;
        }
        items.add(buildItem("现金流质量",
                cfNp != null ? (cfNpVal > 0 ? cfNp.setScale(2, RoundingMode.HALF_UP) + "倍" : "-") : "-",
                cfScore, 2,
                "经营现金流/净利润比值：≥1倍=2分(盈利质量优，钱都收到了)，≥0.5倍=1分；<0.5说明账面利润未完全变现", false,
                cfScore >= 1 ? "green" : "default"));

        // === 10. 偿债能力（2分） ===
        BigDecimal cr = fundamental != null ? fundamental.getCurrentRatio() : null;
        BigDecimal qr = fundamental != null ? fundamental.getQuickRatio() : null;
        double crVal = cr != null ? cr.doubleValue() : 0;
        double qrVal = qr != null ? qr.doubleValue() : 0;
        int crScore = 0, qrScore = 0;
        if (isFin) {
            // 金融股不适用流动/速动比率（银行受资本充足率监管，证券看净资本）
            crScore = 1; qrScore = 1; // 不扣分
            items.add(buildItem("偿债能力", "监管指标(不适用)", crScore + qrScore, 2,
                    "金融行业适用监管指标（银行:资本充足率/杠杆率，证券:净资本/风险覆盖率），不使用流动比率", true, "default"));
        } else {
            if (cr != null && crVal >= 1.5) crScore = 1;
            if (qr != null && qrVal >= 1.0) qrScore = 1;
            String debtDisplay = (cr != null ? "流动=" + cr.setScale(2, RoundingMode.HALF_UP) : "-") +
                    " | " + (qr != null ? "速动=" + qr.setScale(2, RoundingMode.HALF_UP) : "-");
            items.add(buildItem("偿债能力", debtDisplay, crScore + qrScore, 2,
                    "流动比率≥1.5=1分(短期偿债强)，速动比率≥1.0=1分(剔除存货后仍有偿债能力)；两者均低=资金紧张风险", false,
                    (crScore + qrScore) >= 2 ? "green" : (crScore + qrScore) >= 1 ? "default" : "red"));
        }

        // === 11. 研报评级（3分，从5分降至3分） ===
        int rScore = fundamental != null ? fundamental.getResearchScore() : 0;
        items.add(buildItem("研报评级",
                fundamental != null && fundamental.getResearchScore() > 0 ? ratingDesc(fundamental.getResearchScore()) : "暂无",
                rScore >= 5 ? 3 : rScore >= 3 ? 2 : rScore >= 1 ? 1 : 0, 3,
                "买入=3分，增持=2分，中性=1分；机构评级反映专业机构对公司的认可程度（权重已降低）", false,
                rScore >= 5 ? "green" : rScore >= 3 ? "volcano" : "default"));

        // ========== 展示项（不参与评分，仅展示）==========

        // 营收/净利润绝对值
        BigDecimal totalRev = fundamental != null ? fundamental.getTotalRevenue() : null;
        BigDecimal netProfitAbs = fundamental != null ? fundamental.getNetProfitAbs() : null;
        String absDisplay = "";
        if (totalRev != null) {
            double revVal2 = totalRev.doubleValue();
            String revStr = revVal2 > 1e8 ? String.format("%.2f亿", revVal2 / 1e8) : String.format("%.2f万", revVal2 / 1e4);
            absDisplay += "营收=" + revStr;
        }
        if (netProfitAbs != null) {
            double npVal2 = netProfitAbs.doubleValue();
            String npStr = npVal2 > 1e8 ? String.format("%.2f亿", npVal2 / 1e8) : String.format("%.2f万", npVal2 / 1e4);
            absDisplay += absDisplay.isEmpty() ? "净利=" + npStr : " | 净利=" + npStr;
        }
        if (!absDisplay.isEmpty()) {
            items.add(buildItem("营收/净利(绝对值)", absDisplay, 0, 0,
                    "最新一期财报营业收入和归母净利润绝对值，单位：亿/万元。" + (totalRev != null && netProfitAbs != null ?
                    String.format("净利率=%.2f%%", netProfitAbs.doubleValue() / totalRev.doubleValue() * 100) : ""), true, null));
        }

        // 扣非净利润
        BigDecimal dnp = fundamental != null ? fundamental.getDeductedNpYoY() : null;
        String dnpDisplay = dnp != null ? dnp.setScale(2, RoundingMode.HALF_UP) + "%" : "-";
        if (fundamental != null && fundamental.getDeductedNetProfit() != null) {
            double dnpAbs = fundamental.getDeductedNetProfit().doubleValue();
            dnpDisplay += dnpAbs > 1e8 ? String.format(" (绝对值%.2f亿)", dnpAbs / 1e8) : "";
        }
        items.add(buildItem("扣非增速", dnpDisplay, 0, 0,
                "扣非净利润同比增速。扣非≈归母说明业绩真实；扣非<<归母说明靠非经常性损益撑业绩（卖资产/政府补贴等）", true, null));

        // 回款质量（展示）
        BigDecimal arDays = fundamental != null ? fundamental.getArTurnoverDays() : null;
        if (isFin) {
            items.add(buildItem("回款天数", "不适用", 0, 0,
                    "金融行业无应收账款周转概念（银行看不良贷款率，证券看自营资产质量）", true, "default"));
        } else {
            items.add(buildItem("回款天数",
                    arDays != null && arDays.doubleValue() > 0 ? arDays.setScale(0, RoundingMode.HALF_UP) + "天" : "-",
                    0, 0,
                    "应收账款周转天数：越低=回款越快；>120天需警惕坏账风险；同行业横向对比更准确", true,
                    arDays != null && arDays.doubleValue() <= 60 ? "green" : arDays != null && arDays.doubleValue() > 120 ? "red" : "default"));
        }

        // 商誉（展示）
        BigDecimal goodwill = fundamental != null ? fundamental.getGoodwill() : null;
        String gwDisplay;
        if (isFin) {
            gwDisplay = "不适用";
        } else if (goodwill != null && fundamental.getNetProfitAbs() != null && fundamental.getNetProfitAbs().doubleValue() != 0) {
            double gwRatio = goodwill.doubleValue() / Math.abs(fundamental.getNetProfitAbs().doubleValue());
            gwDisplay = String.format("%.2f亿(占净利%.1f倍)", goodwill.doubleValue() / 1e8, gwRatio);
        } else if (goodwill != null) {
            gwDisplay = String.format("%.2f亿", goodwill.doubleValue() / 1e8);
        } else {
            gwDisplay = "-";
        }
        items.add(buildItem("商誉", isFin ? gwDisplay : gwDisplay, 0, 0,
                isFin ? "金融行业通常无商誉（适用金融资产公允价值评估）" : "商誉占净利润倍数过高（>10倍）说明并购溢价高，减值风险大；每年末需关注商誉减值测试",
                true, "default"));

        // 存货（展示）
        BigDecimal inventory = fundamental != null ? fundamental.getInventory() : null;
        if (isFin) {
            items.add(buildItem("存货", "不适用", 0, 0,
                    "金融行业无存货概念（银行看生息资产，证券看自营投资）", true, "default"));
        } else {
            String invDisplay = inventory != null ? String.format("%.2f亿", inventory.doubleValue() / 1e8) : "-";
            items.add(buildItem("存货", invDisplay, 0, 0,
                    "存货金额（最新一期资产负债表）。存货过高且周转慢可能意味着滞销风险；需结合行业特点判断", true, null));
        }

        // 货币资金（展示）
        BigDecimal monetary = fundamental != null ? fundamental.getMonetaryCapital() : null;
        String monDisplay = monetary != null ? String.format("%.2f亿", monetary.doubleValue() / 1e8) : "-";
        if (monetary != null && fundamental.getDebtRatio() != null) {
            // 粗略估算：货币资金/流动负债（需要stock_balance数据，这里只展示绝对值）
            monDisplay += "（见速动比率判断短期支付能力）";
        }
        items.add(buildItem("货币资金", monDisplay, 0, 0,
                "货币资金（最新一期资产负债表）。充裕的现金是抗风险能力强的表现，也是未来投资/分红的保障", true, null));

        // 自由现金流（展示）
        BigDecimal fcf = fundamental != null ? fundamental.getFreeCashFlow() : null;
        String fcfDisplay = fcf != null ?
                (fcf.doubleValue() > 1e8 ? String.format("%.2f亿", fcf.doubleValue() / 1e8) :
                 String.format("%.2f万", fcf.doubleValue() / 1e4)) : "-";
        items.add(buildItem("自由现金流", fcfDisplay, 0, 0,
                "自由现金流=经营净现金流-资本支出。持续为正当说明公司无需不断投入就能产生现金，是高质量公司的标志", true,
                fcf != null && fcf.doubleValue() > 0 ? "green" : "default"));

        // 研报覆盖热度（展示，不再评分）
        int rc = fundamental != null ? fundamental.getReportCount() : 0;
        items.add(buildItem("研报覆盖热度", rc + "篇(90天)", 0, 0,
                "近90天券商研报覆盖数量。≥10篇=高度关注，0篇=无人问津（可能是被低估，也可能是真没亮点）", true,
                rc >= 5 ? "green" : "default"));

        return items;
    }

    public String ratingDesc(int score) {
        if (score >= 5) return "买入";
        if (score >= 3) return "增持";
        if (score >= 1) return "中性";
        return "减持/卖出";
    }
}
