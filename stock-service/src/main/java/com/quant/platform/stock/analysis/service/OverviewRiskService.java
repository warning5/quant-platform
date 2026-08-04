package com.quant.platform.stock.analysis.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.factor.engine.chan.ChanTheoryCalculator;
import com.quant.platform.factor.engine.chan.ChanTheoryResult;
import com.quant.platform.market.domain.MarketDailyBar;
import com.quant.platform.stock.analysis.domain.*;
import com.quant.platform.stock.analysis.engine.TradingSignalEngine;
import com.quant.platform.stock.analysis.mapper.AnalysisChMapper;
import com.quant.platform.stock.analysis.mapper.BidAskMapper;
import com.quant.platform.stock.analysis.mapper.NewsMapper;
import com.quant.platform.stock.analysis.mapper.StockAnalysisMapper;
import com.quant.platform.stock.service.ClickHouseStockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
@Slf4j
@Service
@RequiredArgsConstructor
public class OverviewRiskService {
    private final ClickHouseStockService clickHouseStockService;
    private final AnalysisCommonService analysisCommon;
    public List<TailRisk> buildTailRisks(String code, FundamentalSignal fs,
                                           Map<String, Object> stockInfo,
                                           BigDecimal currentPrice) {
        List<TailRisk> risks = new ArrayList<>();
        if (fs == null) return risks;

        // 动态参数：市值 + CH 历史波动率
        BigDecimal totalMarketCap = (stockInfo != null && stockInfo.get("totalMarketCap") != null)
                ? new BigDecimal(stockInfo.get("totalMarketCap").toString()) : null;
        Double annualVol = null;
        try { annualVol = clickHouseStockService.getHistoricalVolatility(code); } catch (Exception ignore) {}
        String impactLevel  = calcImpactLevel(totalMarketCap);
        String drawdown    = calcPotentialDrawdown(annualVol, totalMarketCap);

        // 1. 估值泡沫风险（PE>100且PE分位>80%）
        if (fs.getPeTtm() != null && fs.getPePercentile() != null) {
            double pe = fs.getPeTtm().doubleValue();
            double pePct = fs.getPePercentile().doubleValue();
            if (pe > 100 && pePct > 80) {
                // 概率：PE 越高、分位越极端 → 概率越大
                double peScore = Math.min(1.0, pe / 300.0);
                double pctScore = pePct / 100.0;
                double comb = (peScore + pctScore) / 2.0;
                double prob = 5.0 + comb * 18.0;
                prob = Math.max(3.0, Math.min(25.0, prob));
                int pLow = (int) Math.floor(prob - 1);
                int pHigh = (int) Math.ceil(prob + 1);
                pLow = Math.max(2, Math.min(24, pLow));
                pHigh = Math.max(pLow + 1, Math.min(26, pHigh));
                risks.add(new TailRisk("估值泡沫破裂",
                        pLow + "-" + pHigh + "%",
                        "毁灭性", drawdown,
                        String.format("实际PE(%.0f)>100x阈值且分位(%.0f%%)>80%%阈值，估值泡沫信号强烈", pe, pePct),
                        "PE=" + String.format("%.0f", pe) + "x，" + String.format("%.0f", pePct) + "%历史分位",
                        "VALUATION"));
            } else if (pe > 50 && pePct > 70) {
                double prob = 4.0 + (pePct - 70) / 30.0 * 10.0;
                prob = Math.max(3.0, Math.min(20.0, prob));
                int pLow = (int) Math.floor(prob - 1);
                int pHigh = (int) Math.ceil(prob + 1);
                pLow = Math.max(2, Math.min(19, pLow));
                pHigh = Math.max(pLow + 1, Math.min(21, pHigh));
                risks.add(new TailRisk("估值回归压力",
                        pLow + "-" + pHigh + "%",
                        impactLevel, drawdown,
                        String.format("实际PE(%.0f)>50x阈值且分位(%.0f%%)>70%%阈值，存在均值回归压力", pe, pePct),
                        "PE=" + String.format("%.0f", pe) + "x，分位" + String.format("%.0f", pePct) + "%",
                        "VALUATION"));
            }
        }

        // 2. 商誉减值风险
        if (fs.getGoodwill() != null) {
            double goodwill = fs.getGoodwill().doubleValue();
            BigDecimal totalAssets = stockInfo != null && stockInfo.get("total_assets") != null
                    ? new BigDecimal(stockInfo.get("total_assets").toString()) : null;
            if (totalAssets != null && totalAssets.doubleValue() > 0) {
                double ratio = goodwill / totalAssets.doubleValue();
                String prob = calcTailRiskProbability(ratio, 0.15);
                String dd   = (annualVol != null && annualVol > 0.01)
                        ? calcPotentialDrawdown(annualVol * 1.2, null)  // 商誉减值跌幅更大
                        : drawdown;
                if (ratio > 0.2) {
                    risks.add(new TailRisk("商誉减值", prob,
                            "重大", dd,
                            String.format("商誉占比(%.0f%%)逾20%%高阈值，收购标的业绩下滑即可触发减值", ratio * 100),
                            "商誉" + formatAmount(goodwill) + "，占总资产" + String.format("%.0f", ratio * 100) + "%",
                            "FINANCIAL"));
                } else if (ratio > 0.1 && goodwill > 3e8) {
                    risks.add(new TailRisk("商誉风险关注", prob,
                            "中等", dd,
                            String.format("商誉占比(%.0f%%)超10%%关注线且商誉>3亿，需持续跟踪", ratio * 100),
                            "商誉" + formatAmount(goodwill) + "，占比" + String.format("%.0f", ratio * 100) + "%",
                            "FINANCIAL"));
                }
            }
        }

        // 3. 存货崩塌风险
        if (fs.getInventory() != null) {
            double inventory = fs.getInventory().doubleValue();
            BigDecimal totalAssets = stockInfo != null && stockInfo.get("total_assets") != null
                    ? new BigDecimal(stockInfo.get("total_assets").toString()) : null;
            if (totalAssets != null && totalAssets.doubleValue() > 0) {
                double ratio = inventory / totalAssets.doubleValue();
                String prob = calcTailRiskProbability(ratio, 0.15);
                String dd  = (annualVol != null && annualVol > 0.01)
                        ? calcPotentialDrawdown(annualVol * 1.3, null)
                        : drawdown;
                if (ratio > 0.25) {
                    risks.add(new TailRisk("存货积压减值", prob,
                            "严重", dd,
                            String.format("存货占比(%.0f%%)逾25%%高阈值，需求萎缩或跌价均可触发减值", ratio * 100),
                            "存货" + formatAmount(inventory) + "，占总资产" + String.format("%.0f", ratio * 100) + "%",
                            "FINANCIAL"));
                } else if (ratio > 0.15 && inventory > 10e8) {
                    risks.add(new TailRisk("存货周转压力", prob,
                            "中等", dd,
                            String.format("存货占比(%.0f%%)超15%%关注线且规模>10亿，下游走弱即承压", ratio * 100),
                            "存货" + formatAmount(inventory) + "，占比" + String.format("%.0f", ratio * 100) + "%",
                            "FINANCIAL"));
                }
            }
        }

        // 4. 流动性危机
        if (fs.getCurrentRatio() != null && fs.getQuickRatio() != null) {
            double cr = fs.getCurrentRatio().doubleValue();
            double qr = fs.getQuickRatio().doubleValue();
            String liqProb = calcLiquidityProbability(cr, qr, 1.5, 0.8);
            String dd       = (annualVol != null && annualVol > 0.01)
                    ? calcPotentialDrawdown(annualVol * 2.0, totalMarketCap)  // 流动性危机跌幅更大
                    : drawdown;
            if (cr < 1.0 || qr < 0.5) {
                risks.add(new TailRisk("流动性危机", liqProb,
                        "致命", dd,
                        String.format("流动比率(%.2f)<1.0低阈值或速动比率(%.2f)<0.5危机线，融资能力枯竭", cr, qr),
                        "流动比率" + String.format("%.2f", cr) + "，速动比率" + String.format("%.2f", qr),
                        "FINANCIAL"));
            } else if (cr < 1.5 && qr < 0.8) {
                risks.add(new TailRisk("流动性偏紧", liqProb,
                        impactLevel, dd,
                        String.format("流动比率(%.2f)<1.5安全线且速动比率(%.2f)<0.8警戒线，再融资渠道收窄", cr, qr),
                        "流动比率" + String.format("%.2f", cr) + "，速动比率" + String.format("%.2f", qr),
                        "FINANCIAL"));
            }
        }

        // 5. 应收账款坏账风险
        if (fs.getArTurnoverDays() != null) {
            double arDays = fs.getArTurnoverDays().doubleValue();
            String arProb = calcArProbability(arDays);
            String dd     = (annualVol != null && annualVol > 0.01)
                    ? calcPotentialDrawdown(annualVol * 1.8, null)
                    : drawdown;
            if (arDays > 180) {
                risks.add(new TailRisk("应收账款坏账", arProb,
                        "重大", dd,
                        String.format("周转天数(%.0f)>180天高危线，大客户违约概率大幅上升", arDays),
                        "应收账款周转天数" + String.format("%.0f", arDays) + "天",
                        "FINANCIAL"));
            } else if (arDays > 120) {
                risks.add(new TailRisk("回款周期偏长", arProb,
                        "中等", dd,
                        String.format("周转天数(%.0f)>120天关注线，下游回款周期明显拉长", arDays),
                        "应收账款周转天数" + String.format("%.0f", arDays) + "天",
                        "FINANCIAL"));
            }
        }

        return risks;
    }

    public String formatAmount(double amount) {
        if (amount >= 1e8) return String.format("%.1f亿", amount / 1e8);
        if (amount >= 1e4) return String.format("%.0f万", amount / 1e4);
        return String.format("%.0f", amount);
    }

    public String calcTailRiskProbability(double actual, double threshold) {
        double distance = Math.max(0, (threshold - actual) / Math.max(threshold, 0.01));
        double prob = 3.0 + distance * 18.0;
        prob = Math.max(2.0, Math.min(25.0, prob));
        int low  = Math.max(1,  Math.min(24, (int) Math.floor(prob) - 1));
        int high = Math.max(2,  Math.min(25, (int) Math.ceil(prob) + 1));
        return low + "-" + high + "%";
    }

    public String calcPotentialDrawdown(Double annualVol, BigDecimal totalMarketCap) {
        if (annualVol != null && annualVol > 0.01) {
            double ddLow  = annualVol * 1.5;
            double ddHigh = annualVol * 2.5;
            int lowPct  = Math.max(5,  Math.min(65, (int) Math.floor(ddLow  * 100)));
            int highPct = Math.max(lowPct + 1, Math.min(70, (int) Math.ceil(ddHigh * 100)));
            return lowPct + "-" + highPct + "%";
        }
        // 兜底：按市值分级
        if (totalMarketCap == null) return "20-30%";
        double cap = totalMarketCap.doubleValue();
        if (cap > 1000e8) return "15-25%";
        if (cap > 100e8)  return "20-35%";
        return "30-50%";
    }

    public String calcImpactLevel(BigDecimal totalMarketCap) {
        if (totalMarketCap == null) return "重大";
        double cap = totalMarketCap.doubleValue();
        if (cap > 1000e8) return "中等";
        if (cap > 100e8)  return "重大";
        return "致命";
    }

    public String calcLiquidityProbability(double cr, double qr,
                                             double thresholdCr, double thresholdQr) {
        double distCr  = Math.max(0, (thresholdCr  - cr)  / Math.max(thresholdCr, 0.01));
        double distQr  = Math.max(0, (thresholdQr  - qr)  / Math.max(thresholdQr, 0.01));
        double dist    = Math.max(distCr, distQr);
        double prob    = 3.0 + dist * 18.0;
        prob = Math.max(2.0, Math.min(25.0, prob));
        int low  = Math.max(1,  Math.min(24, (int) Math.floor(prob) - 1));
        int high = Math.max(2,  Math.min(25, (int) Math.ceil(prob) + 1));
        return low + "-" + high + "%";
    }

    public String calcArProbability(double arDays) {
        double excess = Math.max(0, arDays - 120.0);
        double prob = 3.0 + excess / 60.0 * 12.0;
        prob = Math.max(2.0, Math.min(25.0, prob));
        int low  = Math.max(1,  Math.min(24, (int) Math.floor(prob) - 1));
        int high = Math.max(low + 1, Math.min(26, (int) Math.ceil(prob) + 1));
        return low + "-" + high + "%";
    }

    public List<CatalystItem> buildCatalysts(String code, FundamentalSignal fs,
                                               SentimentSignal ss, ResearchSignal rs) {
        List<CatalystItem> catalysts = new ArrayList<>();

        // === 正面催化剂 ===
        // 从基本面提取
        if (fs != null) {
            if (fs.getRevenueYoy() != null && fs.getRevenueYoy().doubleValue() > 20) {
                catalysts.add(new CatalystItem("营收高速增长（+" + String.format("%.0f", fs.getRevenueYoy().doubleValue()) + "%）",
                        "POSITIVE", "Q2维持同等增速", 4, "FINANCE"));
            }
            if (fs.getNetProfitYoy() != null && fs.getNetProfitYoy().doubleValue() > 30) {
                catalysts.add(new CatalystItem("净利润大幅增长（+" + String.format("%.0f", fs.getNetProfitYoy().doubleValue()) + "%）",
                        "POSITIVE", "盈利质量改善（扣非同步增长）", 5, "FINANCE"));
            }
            if (fs.getDeductedNpYoY() != null && fs.getDeductedNpYoY().doubleValue() > 30) {
                catalysts.add(new CatalystItem("扣非净利润高速增长（+" + String.format("%.0f", fs.getDeductedNpYoY().doubleValue()) + "%）",
                        "POSITIVE", "主业持续向好", 5, "FINANCE"));
            }
            if (fs.getRoe() != null && fs.getRoe().doubleValue() > 15) {
                catalysts.add(new CatalystItem("ROE>15%高盈利质量",
                        "POSITIVE", "ROE维持高位", 3, "FINANCE"));
            }
            if (fs.getOperatingCfToNp() != null && fs.getOperatingCfToNp().doubleValue() > 1.5) {
                catalysts.add(new CatalystItem("经营现金流远超净利润",
                        "POSITIVE", "现金流持续强劲", 3, "FINANCE"));
            }
        }

        // 从事件面提取
        if (ss != null) {
            if (ss.getNewsPositive30d() > 0 && ss.getNewsSentimentBias() > 0.3) {
                catalysts.add(new CatalystItem("近30日利好新闻占优（偏向" + String.format("%.0f", ss.getNewsSentimentBias() * 100) + "%）",
                        "POSITIVE", "持续正面新闻催化市场关注", 3, "NEWS"));
            }
            if (ss.getResearchReportCount90d() > 5) {
                catalysts.add(new CatalystItem("机构覆盖度提升（近90日" + ss.getResearchReportCount90d() + "篇研报）",
                        "POSITIVE", "新增机构覆盖+买入评级", 3, "EVENT"));
            }
            if (ss.getFundHolderRatio() != null && ss.getFundHolderRatio().doubleValue() > 0.05) {
                catalysts.add(new CatalystItem("基金持仓>5%流通盘",
                        "POSITIVE", "机构持续加仓", 2, "EVENT"));
            }
        }

        // 从研报提取
        if (rs != null && rs.getLatestRating() != null) {
            if ("买入".equals(rs.getLatestRating()) || "增持".equals(rs.getLatestRating())) {
                catalysts.add(new CatalystItem("最新研报" + rs.getLatestRating() + "评级",
                        "POSITIVE", "机构上调目标价", 3, "EVENT"));
            }
        }

        // === 负面催化剂 ===
        if (fs != null) {
            if (fs.getPeTtm() != null && fs.getPeTtm().doubleValue() > 100) {
                catalysts.add(new CatalystItem("PE>100x极度高估",
                        "NEGATIVE", "业绩不及预期直接暴跌", 4, "VALUATION"));
            } else if (fs.getPeTtm() != null && fs.getPeTtm().doubleValue() > 50) {
                catalysts.add(new CatalystItem("PE>50x估值偏高",
                        "NEGATIVE", "估值中枢下移或增长放缓", 3, "VALUATION"));
            }
            if (fs.getPePercentile() != null && fs.getPePercentile().doubleValue() > 80) {
                catalysts.add(new CatalystItem("PE处于历史" + String.format("%.0f", fs.getPePercentile().doubleValue()) + "%分位高位",
                        "NEGATIVE", "均值回归压力", 3, "VALUATION"));
            }
            if (fs.getDebtRatio() != null && fs.getDebtRatio().doubleValue() > 70) {
                catalysts.add(new CatalystItem("资产负债率" + String.format("%.0f", fs.getDebtRatio().doubleValue()) + "%偏高",
                        "NEGATIVE", "利率上行或融资收紧", 3, "FINANCE"));
            }
            if (fs.getRevenueYoy() != null && fs.getRevenueYoy().doubleValue() < -10) {
                catalysts.add(new CatalystItem("营收大幅下滑（" + String.format("%.0f", fs.getRevenueYoy().doubleValue()) + "%）",
                        "NEGATIVE", "持续下滑确认衰退趋势", 4, "FINANCE"));
            }
            if (fs.getDeductedNpYoY() != null && fs.getDeductedNpYoY().doubleValue() < -20) {
                catalysts.add(new CatalystItem("扣非净利润大幅下滑",
                        "NEGATIVE", "主业盈利恶化", 4, "FINANCE"));
            }
        }

        // 从事件面提取
        if (ss != null) {
            if (ss.getNewsNegative30d() > 5 && ss.getNewsSentimentBias() < -0.3) {
                catalysts.add(new CatalystItem("近30日风险新闻频现（偏向" + String.format("%.0f", ss.getNewsSentimentBias() * 100) + "%）",
                        "NEGATIVE", "负面舆情持续发酵", 3, "NEWS"));
            }
            if (ss.getResearchReportCount90d() == 0) {
                catalysts.add(new CatalystItem("近90日零研报覆盖",
                        "NEGATIVE", "机构不关注=淘汰信号", 2, "EVENT"));
            }
        }

        return catalysts;
    }

    public void calcMultiAnalystScores(AnalysisOverview overview, TechSignal tech,
                                         MoneyFlowSignal money, SentimentSignal sentiment,
                                         FundamentalSignal fundamental, boolean isBlueChip,
                                         BigDecimal currentPrice, BigDecimal supportPrice,
                                         BigDecimal resistancePrice) {
        try {
            // === 保守分析师：防守导向 ===
            int conservativeScore = 5; // 起点5分
            if (fundamental != null) {
                // 估值惩罚（高PE高PB=扣分，低PE低PB=加分）
                if (fundamental.getPeTtm() != null) {
                    double pe = fundamental.getPeTtm().doubleValue();
                    if (pe > 100) conservativeScore -= 3;
                    else if (pe > 50) conservativeScore -= 2;
                    else if (pe > 30) conservativeScore -= 1;
                    else if (pe < 10) conservativeScore += 2;
                    else if (pe < 15) conservativeScore += 1;
                }
                if (fundamental.getPb() != null) {
                    double pb = fundamental.getPb().doubleValue();
                    if (pb > 8) conservativeScore -= 2;
                    else if (pb > 5) conservativeScore -= 1;
                    else if (pb < 1) conservativeScore += 2;
                    else if (pb < 2) conservativeScore += 1;
                }
                // 资产负债率
                if (fundamental.getDebtRatio() != null) {
                    double dr = fundamental.getDebtRatio().doubleValue();
                    if (dr > 80) conservativeScore -= 2;
                    else if (dr > 60) conservativeScore -= 1;
                    else if (dr < 30) conservativeScore += 1;
                }
                // PE分位高=扣分
                if (fundamental.getPePercentile() != null) {
                    double pct = fundamental.getPePercentile().doubleValue();
                    if (pct > 90) conservativeScore -= 2;
                    else if (pct > 70) conservativeScore -= 1;
                    else if (pct < 20) conservativeScore += 1;
                }
            }
            // 技术面微弱加分（保守派不太看技术）
            if (tech != null && "BUY".equals(tech.getChanSignal())) conservativeScore += 1;
            conservativeScore = Math.max(1, Math.min(10, conservativeScore));
            overview.setConservativeScore(conservativeScore);

            // 保守仓位：评分≤3→清仓，≤5→10-15%，≤6→20-25%，>6→30%
            int conservativePos;
            if (conservativeScore <= 3) conservativePos = 0;
            else if (conservativeScore <= 5) conservativePos = 12;
            else if (conservativeScore <= 6) conservativePos = 22;
            else conservativePos = 30;
            overview.setConservativePosition(conservativePos + "%");
            overview.setConservativeDesc(conservativeScore <= 3 ? "极端保守，建议空仓" :
                conservativeScore <= 5 ? "偏保守，低仓试探" :
                conservativeScore <= 6 ? "谨慎乐观，适度参与" : "相对看好，中仓持有");

            // === 中性分析师：当前评分归一化到10分 ===
            int totalScore = overview.getTotalScore() != null ? overview.getTotalScore() : 50;
            int neutralScore = Math.max(1, Math.min(10, (int) Math.round(totalScore / 13.5))); // 135→10
            overview.setNeutralScore(neutralScore);
            int neutralPos = overview.getPosition() != null ? overview.getPosition() : 30;
            overview.setNeutralPosition(neutralPos + "%");
            overview.setNeutralDesc(neutralScore >= 7 ? "四维度均衡看多" :
                neutralScore >= 4 ? "中性偏谨慎" : "结构性问题需警惕");

            // === 激进分析师：进攻导向 ===
            int aggressiveScore = 5;
            if (tech != null) {
                // 趋势加分
                if ("BULLISH".equals(tech.getTrend())) aggressiveScore += 2;
                else if ("SIDEWAYS".equals(tech.getTrend())) aggressiveScore += 1;
                if ("BUY".equals(tech.getChanSignal())) aggressiveScore += 1;
                // 量能加分
                if (tech.getVolumeRatio() != null && tech.getVolumeRatio().doubleValue() > 1.5) aggressiveScore += 1;
            }
            if (fundamental != null) {
                // 增速加分（激进派重成长）
                if (fundamental.getRevenueYoy() != null) {
                    double revYoy = fundamental.getRevenueYoy().doubleValue();
                    if (revYoy > 30) aggressiveScore += 2;
                    else if (revYoy > 15) aggressiveScore += 1;
                    else if (revYoy < -10) aggressiveScore -= 2;
                }
                if (fundamental.getNetProfitYoy() != null) {
                    double npYoy = fundamental.getNetProfitYoy().doubleValue();
                    if (npYoy > 50) aggressiveScore += 2;
                    else if (npYoy > 20) aggressiveScore += 1;
                    else if (npYoy < -20) aggressiveScore -= 2;
                }
                // 估值容忍（高PE不减分，低PE加分）
                if (fundamental.getPeTtm() != null) {
                    double pe = fundamental.getPeTtm().doubleValue();
                    if (pe < 15) aggressiveScore += 1;
                    // PE>100 不减分（激进派看重成长而非当前估值）
                }
            }
            if (money != null) {
                if (money.getNetMain() != null && money.getNetMain().doubleValue() > 1e8) aggressiveScore += 1;
            }
            aggressiveScore = Math.max(1, Math.min(10, aggressiveScore));
            overview.setAggressiveScore(aggressiveScore);

            int aggressivePos;
            if (aggressiveScore >= 8) aggressivePos = 70;
            else if (aggressiveScore >= 6) aggressivePos = 50;
            else if (aggressiveScore >= 4) aggressivePos = 30;
            else aggressivePos = 10;
            overview.setAggressivePosition(aggressivePos + "%");
            overview.setAggressiveDesc(aggressiveScore >= 8 ? "强烈看多，重仓出击" :
                aggressiveScore >= 6 ? "看好成长，中等仓位" :
                aggressiveScore >= 4 ? "谨慎参与，轻仓观察" : "回避风险");

        } catch (Exception e) {
            log.warn("三方分析师评分计算失败: code={}, error={}", overview.getCode(), e.getMessage());
        }
    }

    public void buildBullBearDebate(AnalysisOverview overview) {
        List<BullBearArgument> bullArgs = new ArrayList<>();
        List<BullBearArgument> bearArgs = new ArrayList<>();

        TechSignal tech = overview.getTechSignal();
        MoneyFlowSignal money = overview.getMoneySignal();
        FundamentalSignal fundamental = overview.getFundamentalSignal();
        SentimentSignal sentiment = overview.getSentimentSignal();
        ResearchSignal research = overview.getResearchSignal();

        // --- 技术面规则 ---
        if (tech != null) {
            if ("BUY".equals(tech.getChanSignal())) {
                bullArgs.add(new BullBearArgument("缠论买点", "技术", "缠论出现买入信号", 5));
            }
            if ("SELL".equals(tech.getChanSignal())) {
                bearArgs.add(new BullBearArgument("缠论卖点", "技术", "缠论出现卖出信号", 4));
            }
            if (Boolean.TRUE.equals(tech.getMaBullish())) {
                bullArgs.add(new BullBearArgument("均线多头", "技术", "MA5>MA10>MA20>MA60，均线多头排列", 4));
            }
            if (Boolean.TRUE.equals(tech.getMacdGolden())) {
                bullArgs.add(new BullBearArgument("MACD金叉", "技术", "MACD出现金叉，短期动能转强", 3));
            }
            if (tech.getRsi() != null) {
                double rsi = tech.getRsi().doubleValue();
                if (rsi < 30) {
                    bullArgs.add(new BullBearArgument("RSI超卖", "技术",
                            "RSI=" + analysisCommon.formatD(rsi) + "，超卖区间存在反弹可能", 3));
                } else if (rsi > 70) {
                    bearArgs.add(new BullBearArgument("RSI超买", "技术",
                            "RSI=" + analysisCommon.formatD(rsi) + "，超买区间注意回调", 3));
                }
            }
        }

        // --- 基本面规则 ---
        if (fundamental != null) {
            if (fundamental.getPeTtm() != null) {
                double pe = fundamental.getPeTtm().doubleValue();
                if (pe > 0 && pe < 15) {
                    bullArgs.add(new BullBearArgument("低PE估值", "基本面",
                            "PE(TTM)=" + analysisCommon.formatD(pe) + "，估值偏低", 4));
                } else if (pe > 50) {
                    bearArgs.add(new BullBearArgument("高PE估值", "基本面",
                            "PE(TTM)=" + analysisCommon.formatD(pe) + "，估值偏高", 4));
                }
            }
            if (fundamental.getPb() != null) {
                double pb = fundamental.getPb().doubleValue();
                if (pb > 0 && pb < 1.5) {
                    bullArgs.add(new BullBearArgument("低PB估值", "基本面",
                            "PB=" + analysisCommon.formatD(pb) + "，破净风险低", 3));
                } else if (pb > 8) {
                    bearArgs.add(new BullBearArgument("高PB估值", "基本面",
                            "PB=" + analysisCommon.formatD(pb) + "，市净率偏高", 3));
                }
            }
            if (fundamental.getRoe() != null) {
                double roe = fundamental.getRoe().doubleValue();
                if (roe > 15) {
                    bullArgs.add(new BullBearArgument("高ROE", "基本面",
                            "ROE=" + analysisCommon.formatD(roe) + "%，盈利能力优秀", 4));
                } else if (roe < 5) {
                    bearArgs.add(new BullBearArgument("低ROE", "基本面",
                            "ROE=" + analysisCommon.formatD(roe) + "%，盈利能力偏弱", 3));
                }
            }
            if (fundamental.getRevenueYoy() != null) {
                double rev = fundamental.getRevenueYoy().doubleValue();
                if (rev > 20) {
                    bullArgs.add(new BullBearArgument("营收高增", "基本面",
                            "营收同比+" + analysisCommon.formatD(rev) + "%，成长性突出", 4));
                } else if (rev < -10) {
                    bearArgs.add(new BullBearArgument("营收下滑", "基本面",
                            "营收同比" + analysisCommon.formatD(rev) + "%，增长承压", 3));
                }
            }
            if (fundamental.getNetProfitYoy() != null) {
                double profit = fundamental.getNetProfitYoy().doubleValue();
                if (profit > 30) {
                    bullArgs.add(new BullBearArgument("利润高增", "基本面",
                            "净利润同比+" + analysisCommon.formatD(profit) + "%，盈利爆发", 4));
                } else if (profit < -20) {
                    bearArgs.add(new BullBearArgument("利润下滑", "基本面",
                            "净利润同比" + analysisCommon.formatD(profit) + "%，盈利恶化", 3));
                }
            }
            if (fundamental.getDebtRatio() != null) {
                double debt = fundamental.getDebtRatio().doubleValue();
                if (debt > 80) {
                    bearArgs.add(new BullBearArgument("高负债率", "基本面",
                            "资产负债率" + analysisCommon.formatD(debt) + "%，杠杆过高", 3));
                } else if (debt < 30) {
                    bullArgs.add(new BullBearArgument("低负债率", "基本面",
                            "资产负债率" + analysisCommon.formatD(debt) + "%，财务稳健", 2));
                }
            }
        }

        // --- 资金面规则 ---
        if (money != null) {
            if (money.getNetMain() != null) {
                double netMain = money.getNetMain().doubleValue();
                if (netMain > 0) {
                    bullArgs.add(new BullBearArgument("主力流入", "资金",
                            "主力净流入" + analysisCommon.formatMoney(netMain) + "，资金积极介入", 4));
                } else if (netMain < 0) {
                    bearArgs.add(new BullBearArgument("主力流出", "资金",
                            "主力净流出" + analysisCommon.formatMoney(Math.abs(netMain)) + "，资金撤退", 4));
                }
            }
            if (money.getVolumeRatio() != null) {
                double vr = money.getVolumeRatio().doubleValue();
                if (vr >= 2.0) {
                    bullArgs.add(new BullBearArgument("量能放大", "资金",
                            "量比=" + analysisCommon.formatD(vr) + "，成交活跃", 3));
                } else if (vr < 0.5) {
                    bearArgs.add(new BullBearArgument("量能萎缩", "资金",
                            "量比=" + analysisCommon.formatD(vr) + "，成交清淡", 2));
                }
            }
        }

        // --- 情绪面规则 ---
        if (sentiment != null) {
            if (Boolean.TRUE.equals(sentiment.getIsStrongStock())) {
                bullArgs.add(new BullBearArgument("强势股", "情绪", "近20日涨幅>30%，强势状态", 3));
            }
            if (sentiment.getLimitUpDays() != null && sentiment.getLimitUpDays() > 0) {
                bullArgs.add(new BullBearArgument("涨停基因", "情绪",
                        "近20日涨停" + sentiment.getLimitUpDays() + "次", 3));
            }
        }

        // --- 研报规则 ---
        if (research != null) {
            if (research.getResearchScore() >= 4) {
                bullArgs.add(new BullBearArgument("机构看好", "研报",
                        "最新评级" + research.getLatestRating() + "，机构积极", 3));
            }
            if (research.getReportCount() >= 5) {
                bullArgs.add(new BullBearArgument("研报密集", "研报",
                        "近90天" + research.getReportCount() + "份研报覆盖", 2));
            }
        }

        // --- 综合评分规则 ---
        if (overview.getTotalScore() != null) {
            int score = overview.getTotalScore();
            if (score >= 75) {
                bullArgs.add(new BullBearArgument("高分综合", "综合",
                        "四维度综合评分" + score + "分，整体优秀", 5));
            } else if (score <= 35) {
                bearArgs.add(new BullBearArgument("低分综合", "综合",
                        "四维度综合评分" + score + "分，整体偏弱", 4));
            }
        }

        // 按强度排序
        bullArgs.sort((a, b) -> Integer.compare(b.getStrength(), a.getStrength()));
        bearArgs.sort((a, b) -> Integer.compare(b.getStrength(), a.getStrength()));

        overview.setBullArguments(bullArgs);
        overview.setBearArguments(bearArgs);

        // 生成结论文本
        overview.setBullBearConclusion(buildBullBearConclusionText(overview, bullArgs, bearArgs));
    }

    public String buildBullBearConclusionText(AnalysisOverview overview,
                                                List<BullBearArgument> bullArgs,
                                                List<BullBearArgument> bearArgs) {
        String name = overview.getName() != null ? overview.getName() : overview.getCode();
        int bullCount = bullArgs.size();
        int bearCount = bearArgs.size();
        int bullStars = bullArgs.stream().mapToInt(a -> a.getStrength()).sum();
        int bearStars = bearArgs.stream().mapToInt(a -> a.getStrength()).sum();

        // 偏向判定（基于强度而非条数）
        String bias;
        if (bullStars > bearStars + 5) bias = "偏多";
        else if (bearStars > bullStars + 5) bias = "偏空";
        else if (bullStars > bearStars) bias = "中性偏多";
        else if (bearStars > bullStars) bias = "中性偏空";
        else bias = "中性";

        // 核心看多因据（取前2条简述）
        String bullReason = bullArgs.isEmpty() ? ""
                : bullArgs.stream().limit(2).map(a -> a.getRule()).collect(Collectors.joining("、"));
        // 核心看空因据（取前2条简述）
        String bearReason = bearArgs.isEmpty() ? ""
                : bearArgs.stream().limit(2).map(a -> a.getRule()).collect(Collectors.joining("、"));

        // 构建"因为…所以…"句式
        StringBuilder sb = new StringBuilder();
        sb.append(name).append("多空强度 ").append(bullStars).append("★:").append(bearStars).append("★，").append(bias).append("。");

        if (!bullReason.isEmpty()) {
            sb.append("看多因：").append(bullReason).append("；");
        }
        if (!bearReason.isEmpty()) {
            sb.append("看空因：").append(bearReason).append("；");
        }
        // 操作建议
        if (overview.getActionName() != null) {
            sb.append("建议【").append(overview.getActionName()).append("】");
            if (overview.getRiskLevel() != null) {
                sb.append("，风险").append(overview.getRiskLevel());
            }
        }
        return sb.toString();
    }

}
