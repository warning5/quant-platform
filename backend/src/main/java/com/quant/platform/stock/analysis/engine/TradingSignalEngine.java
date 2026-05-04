package com.quant.platform.stock.analysis.engine;

import com.quant.platform.stock.analysis.domain.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 交易信号引擎（四维度评分 + 规则生成操作建议）
 * 
 * 评分维度：技术面(30) + 资金面(25) + 事件面(25) + 基本面(25) = 105分
 */
@Slf4j
@Component
public class TradingSignalEngine {
    
    // ========== 技术面阈值 ==========
    private static final double VOLUME_RATIO_HIGH = 2.0;      // 量比>2倍为放量
    private static final double VOLUME_RATIO_MEDIUM = 1.5;   // 量比>1.5倍为温和放量
    private static final double TURNOVER_DEVIATION_HIGH = 3.0; // 换手率偏离>3%为异常
    
    // ========== 基本面阈值 ==========
    private static final double ROE_THRESHOLD = 10.0;         // ROE>10%为优质
    private static final double ROE_MED = 5.0;              // ROE>5%为中等
    private static final double PE_TTM_LOW = 15.0;            // PE<15为低估
    private static final double PE_TTM_HIGH = 40.0;           // PE>40为高估
    private static final double PE_TTM_EXTREME = 100.0;     // PE>100为极高（给1分）
    private static final double REVENUE_YOY_GOOD = 20.0;     // 营收增速>20%为优秀
    private static final double REVENUE_YOY_PASS = 10.0;     // 营收增速>10%为及格
    private static final double NETPROFIT_YOY_GOOD = 20.0;   // 净利增速>20%为优秀
    private static final double NETPROFIT_YOY_PASS = 10.0;   // 净利增速>10%为及格
    private static final double PB_LOW = 3.0;                // PB<3为低风险
    private static final double PB_MID = 5.0;                // PB<5为中等
    
    // ========== 事件面阈值 ==========
    private static final int LIMIT_UP_DAYS_STRONG = 2;         // 连续2涨停为强势
    private static final double STRONG_STOCK_GAIN = 30.0;     // 20日涨幅>30%为强势股
    
    // ========== 维度权重 ==========
    private static final int TECH_WEIGHT = 30;
    private static final int MONEY_WEIGHT = 25;
    private static final int SENTIMENT_WEIGHT = 25;
    private static final int FUNDAMENTAL_WEIGHT = 25;
    
    /**
     * 综合评分入口
     */
    public TradingSignal evaluate(String code, String name, 
                                 TechSignal tech, MoneyFlowSignal money,
                                 SentimentSignal sentiment, FundamentalSignal fundamental,
                                 BigDecimal supportPrice, BigDecimal resistancePrice) {
        
        TradingSignal signal = new TradingSignal();
        signal.setCode(code);
        signal.setName(name);
        signal.setTechSignal(tech);
        signal.setMoneySignal(money);
        signal.setSentimentSignal(sentiment);
        signal.setFundamentalSignal(fundamental);
        
        // 计算各维度得分
        int techScore = calcTechScore(tech);
        int moneyScore = calcMoneyScore(money);
        int sentimentScore = calcSentimentScore(sentiment);
        int fundamentalScore = calcFundamentalScore(fundamental);
        
        // 构建评分明细
        List<ScoreDetail> details = buildScoreDetails(tech, money, sentiment, fundamental,
                techScore, moneyScore, sentimentScore, fundamentalScore);
        signal.setScoreDetails(details);
        
        // 总分
        int totalScore = techScore + moneyScore + sentimentScore + fundamentalScore;
        signal.setTotalScore(totalScore);
        
        // 生成操作建议
        generateSignal(signal, totalScore, supportPrice, resistancePrice);
        
        return signal;
    }
    
    /**
     * 计算技术面得分（满分30）
     */
    private int calcTechScore(TechSignal tech) {
        if (tech == null) return 0;
        
        int score = 0;
        
        // 缠论买卖信号（12分）
        if ("BUY".equals(tech.getChanSignal())) {
            score += 12;
        } else if ("SELL".equals(tech.getChanSignal())) {
            score -= 5; // 卖出信号扣分
        }
        
        // 趋势状态（8分）
        if ("BULLISH".equals(tech.getTrend())) {
            score += 8;
        } else if ("SIDEWAYS".equals(tech.getTrend())) {
            score += 4;
        }
        
        // 均线多头（5分）
        if (Boolean.TRUE.equals(tech.getMaBullish())) {
            score += 5;
        }
        
        // MACD金叉（5分）
        if (Boolean.TRUE.equals(tech.getMacdGolden())) {
            score += 5;
        }
        
        return Math.max(0, Math.min(TECH_WEIGHT, score));
    }
    
    /**
     * 计算资金面得分（满分25）
     */
    private int calcMoneyScore(MoneyFlowSignal money) {
        if (money == null) return 0;
        
        int score = 0;
        
        // 量比（12分）
        if (money.getVolumeRatio() != null) {
            double vr = money.getVolumeRatio().doubleValue();
            if (vr >= VOLUME_RATIO_HIGH) {
                score += 12; // 放量明显
            } else if (vr >= VOLUME_RATIO_MEDIUM) {
                score += 8;  // 温和放量
            } else if (vr >= 1.0) {
                score += 4;  // 正常量能
            }
        }
        
        // 换手率偏离（13分）
        if (money.getTurnoverDeviation() != null) {
            double dev = money.getTurnoverDeviation().doubleValue();
            if (dev > TURNOVER_DEVIATION_HIGH) {
                score += 5; // 异常活跃，谨慎
            } else if (dev > 0) {
                score += 13; // 活跃度提升
            } else if (dev > -2) {
                score += 8; // 正常
            }
        }
        
        return Math.max(0, Math.min(MONEY_WEIGHT, score));
    }
    
    /**
     * 计算事件面得分（满分25）
     */
    private int calcSentimentScore(SentimentSignal sentiment) {
        if (sentiment == null) return 0;
        
        int score = 0;
        
        // 连续涨停（10分）
        if (sentiment.getLimitUpDays() != null) {
            int days = sentiment.getLimitUpDays();
            if (days >= LIMIT_UP_DAYS_STRONG) {
                score += 10; // 强势
            } else if (days > 0) {
                score += 5;
            }
        }
        
        // 炸板率（8分）— 越低越好
        if (sentiment.getBrokenLimitUpRate() != null) {
            double rate = sentiment.getBrokenLimitUpRate();
            if (rate < 10.0) {
                score += 8;
            } else if (rate < 30.0) {
                score += 4;
            }
        }
        
        // 强势股（7分）
        if (Boolean.TRUE.equals(sentiment.getIsStrongStock())) {
            score += 7;
        }
        
        return Math.max(0, Math.min(SENTIMENT_WEIGHT, score));
    }
    
    /**
     * 计算基本面得分（满分25）
     * 权重：ROE 4分 + PE 3分 + 营收增速 3分 + 净利增速 4分 + PB 3分 + 毛利率 3分 + 研报评级 5分
     */
    private int calcFundamentalScore(FundamentalSignal fundamental) {
        if (fundamental == null) return 0;
        
        int score = 0;
        
        // ROE（4分）
        if (fundamental.getRoe() != null) {
            double roe = fundamental.getRoe().doubleValue();
            if (roe >= ROE_THRESHOLD) {
                score += 4;
            } else if (roe >= ROE_MED) {
                score += 2;
            }
        }
        
        // PE估值（3分）
        if (fundamental.getPeTtm() != null) {
            double pe = fundamental.getPeTtm().doubleValue();
            if (pe > 0 && pe < PE_TTM_LOW) {
                score += 3; // 低估
            } else if (pe < PE_TTM_HIGH) {
                score += 2; // 合理
            } else if (pe < PE_TTM_EXTREME) {
                score += 1; // 偏高
            }
        }
        
        // 营收增速（3分）
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
        
        // 归母净利润增速（4分）
        if (fundamental.getNetProfitYoy() != null) {
            double np = fundamental.getNetProfitYoy().doubleValue();
            if (np >= NETPROFIT_YOY_GOOD) {
                score += 4;
            } else if (np >= NETPROFIT_YOY_PASS) {
                score += 3;
            } else if (np > 0) {
                score += 2;
            }
        }
        
        // PB（3分）
        if (fundamental.getPb() != null) {
            double pb = fundamental.getPb().doubleValue();
            if (pb > 0 && pb < PB_LOW) {
                score += 3;
            } else if (pb < PB_MID) {
                score += 2;
            }
        }
        
        // 毛利率（3分）
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
        
        // 研报评级（5分）— 从 fundamental.researchScore 注入
        score += fundamental.getResearchScore();
        
        return Math.max(0, Math.min(FUNDAMENTAL_WEIGHT, score));
    }
    
    /**
     * 构建评分明细
     */
    private List<ScoreDetail> buildScoreDetails(TechSignal tech, MoneyFlowSignal money,
                                                SentimentSignal sentiment, FundamentalSignal fundamental,
                                                int techScore, int moneyScore, 
                                                int sentimentScore, int fundamentalScore) {
        
        List<ScoreDetail> details = new ArrayList<>();
        
        // 技术面明细
        ScoreDetail techDetail = new ScoreDetail();
        techDetail.setDimension("tech");
        techDetail.setDimensionName("技术面");
        techDetail.setScore(techScore);
        techDetail.setMaxScore(TECH_WEIGHT);
        techDetail.setItems(buildTechItems(tech));
        details.add(techDetail);
        
        // 资金面明细
        ScoreDetail moneyDetail = new ScoreDetail();
        moneyDetail.setDimension("money");
        moneyDetail.setDimensionName("资金面");
        moneyDetail.setScore(moneyScore);
        moneyDetail.setMaxScore(MONEY_WEIGHT);
        moneyDetail.setItems(buildMoneyItems(money));
        details.add(moneyDetail);
        
        // 事件面明细
        ScoreDetail sentimentDetail = new ScoreDetail();
        sentimentDetail.setDimension("sentiment");
        sentimentDetail.setDimensionName("事件面");
        sentimentDetail.setScore(sentimentScore);
        sentimentDetail.setMaxScore(SENTIMENT_WEIGHT);
        sentimentDetail.setItems(buildSentimentItems(sentiment));
        details.add(sentimentDetail);
        
        // 基本面明细
        ScoreDetail fundDetail = new ScoreDetail();
        fundDetail.setDimension("fundamental");
        fundDetail.setDimensionName("基本面");
        fundDetail.setScore(fundamentalScore);
        fundDetail.setMaxScore(FUNDAMENTAL_WEIGHT);
        fundDetail.setItems(buildFundamentalItems(fundamental));
        details.add(fundDetail);
        
        return details;
    }
    
    private List<ScoreDetail.ScoreItem> buildTechItems(TechSignal tech) {
        List<ScoreDetail.ScoreItem> items = new ArrayList<>();
        
        items.add(buildItem("缠论信号", 
                tech != null && tech.getChanSignal() != null ? tech.getChanSignal() : "-",
                tech != null && "BUY".equals(tech.getChanSignal()) ? 12 : 0, 12,
                "缠论买卖信号：BUY=12分, SELL=-5分, HOLD=0分"));
        
        items.add(buildItem("趋势状态", 
                tech != null && tech.getTrend() != null ? tech.getTrend() : "-",
                tech != null && "BULLISH".equals(tech.getTrend()) ? 8 : 
                tech != null && "SIDEWAYS".equals(tech.getTrend()) ? 4 : 0, 8,
                "BULLISH=8分, SIDEWAYS=4分, BEARISH=0分"));
        
        items.add(buildItem("均线多头", 
                tech != null && Boolean.TRUE.equals(tech.getMaBullish()) ? "是" : "否",
                tech != null && Boolean.TRUE.equals(tech.getMaBullish()) ? 5 : 0, 5,
                "均线多头排列=5分"));
        
        items.add(buildItem("MACD金叉", 
                tech != null && Boolean.TRUE.equals(tech.getMacdGolden()) ? "是" : "否",
                tech != null && Boolean.TRUE.equals(tech.getMacdGolden()) ? 5 : 0, 5,
                "MACD金叉=5分"));
        
        return items;
    }
    
    private List<ScoreDetail.ScoreItem> buildMoneyItems(MoneyFlowSignal money) {
        List<ScoreDetail.ScoreItem> items = new ArrayList<>();
        
        BigDecimal vr = money != null ? money.getVolumeRatio() : null;
        int vrScore = 0;
        if (vr != null) {
            if (vr.doubleValue() >= VOLUME_RATIO_HIGH) vrScore = 12;
            else if (vr.doubleValue() >= VOLUME_RATIO_MEDIUM) vrScore = 8;
            else if (vr.doubleValue() >= 1.0) vrScore = 4;
        }
        items.add(buildItem("量比", vr != null ? vr.setScale(2, RoundingMode.HALF_UP).toString() : "-",
                vrScore, 12, "量比≥2.0=12分, ≥1.5=8分, ≥1.0=4分"));
        
        BigDecimal dev = money != null ? money.getTurnoverDeviation() : null;
        int devScore = 0;
        if (dev != null) {
            if (dev.doubleValue() > TURNOVER_DEVIATION_HIGH) devScore = 5;
            else if (dev.doubleValue() > 0) devScore = 13;
            else if (dev.doubleValue() > -2) devScore = 8;
        }
        items.add(buildItem("换手率偏离", dev != null ? dev.setScale(2, RoundingMode.HALF_UP) + "%" : "-",
                devScore, 13, "偏离>3%=5分(异常), >0%=13分, >-2%=8分"));
        
        return items;
    }
    
    private List<ScoreDetail.ScoreItem> buildSentimentItems(SentimentSignal sentiment) {
        List<ScoreDetail.ScoreItem> items = new ArrayList<>();
        
        Integer days = sentiment != null ? sentiment.getLimitUpDays() : null;
        items.add(buildItem("连续涨停", days != null ? days + "天" : "-",
                days != null && days >= LIMIT_UP_DAYS_STRONG ? 10 : days != null && days > 0 ? 5 : 0, 10,
                "连续2涨停=10分, 1涨停=5分"));
        
        Double rate = sentiment != null ? sentiment.getBrokenLimitUpRate() : null;
        int rateScore = 0;
        if (rate != null) {
            if (rate < 10.0) rateScore = 8;
            else if (rate < 30.0) rateScore = 4;
        }
        items.add(buildItem("炸板率", rate != null ? String.format("%.1f%%", rate) : "-",
                rateScore, 8, "炸板率<10%=8分, <30%=4分"));
        
        items.add(buildItem("强势股", 
                sentiment != null && Boolean.TRUE.equals(sentiment.getIsStrongStock()) ? "是" : "否",
                sentiment != null && Boolean.TRUE.equals(sentiment.getIsStrongStock()) ? 7 : 0, 7,
                "20日涨幅>30%=7分"));
        
        return items;
    }
    
    private List<ScoreDetail.ScoreItem> buildFundamentalItems(FundamentalSignal fundamental) {
        List<ScoreDetail.ScoreItem> items = new ArrayList<>();
        
        BigDecimal roe = fundamental != null ? fundamental.getRoe() : null;
        int roeScore = 0;
        if (roe != null) {
            if (roe.doubleValue() >= ROE_THRESHOLD) roeScore = 4;
            else if (roe.doubleValue() >= ROE_MED) roeScore = 2;
        }
        items.add(buildItem("ROE", roe != null ? roe.setScale(2, RoundingMode.HALF_UP) + "%" : "-",
                roeScore, 4, "ROE≥10%=4分, ≥5%=2分"));
        
        BigDecimal pe = fundamental != null ? fundamental.getPeTtm() : null;
        int peScore = 0;
        if (pe != null && pe.doubleValue() > 0) {
            if (pe.doubleValue() < PE_TTM_LOW) peScore = 3;
            else if (pe.doubleValue() < PE_TTM_HIGH) peScore = 2;
            else if (pe.doubleValue() < PE_TTM_EXTREME) peScore = 1;
        }
        items.add(buildItem("PE(TTM)", pe != null ? pe.setScale(2, RoundingMode.HALF_UP).toString() : "-",
                peScore, 3, "PE<15=3分(低估), <40=2分(合理), <100=1分"));
        
        BigDecimal rev = fundamental != null ? fundamental.getRevenueYoy() : null;
        int revScore = 0;
        if (rev != null) {
            if (rev.doubleValue() >= REVENUE_YOY_GOOD) revScore = 3;
            else if (rev.doubleValue() >= REVENUE_YOY_PASS) revScore = 2;
            else if (rev.doubleValue() > 0) revScore = 1;
        }
        items.add(buildItem("营收增速", rev != null ? rev.setScale(2, RoundingMode.HALF_UP) + "%" : "-",
                revScore, 3, "营收增速≥20%=3分, ≥10%=2分, >0%=1分"));
        
        // 净利增速（新增）
        BigDecimal np = fundamental != null ? fundamental.getNetProfitYoy() : null;
        int npScore = 0;
        if (np != null) {
            if (np.doubleValue() >= NETPROFIT_YOY_GOOD) npScore = 4;
            else if (np.doubleValue() >= NETPROFIT_YOY_PASS) npScore = 3;
            else if (np.doubleValue() > 0) npScore = 2;
        }
        items.add(buildItem("净利增速", np != null ? np.setScale(2, RoundingMode.HALF_UP) + "%" : "-",
                npScore, 4, "净利增速≥20%=4分, ≥10%=3分, >0%=2分"));
        
        BigDecimal pb = fundamental != null ? fundamental.getPb() : null;
        int pbScore = 0;
        if (pb != null && pb.doubleValue() > 0) {
            if (pb.doubleValue() < PB_LOW) pbScore = 3;
            else if (pb.doubleValue() < PB_MID) pbScore = 2;
        }
        items.add(buildItem("PB", pb != null ? pb.setScale(2, RoundingMode.HALF_UP).toString() : "-",
                pbScore, 3, "PB<3=3分, <5=2分"));
        
        // 毛利率（新增，仅供参考）
        BigDecimal gm = fundamental != null ? fundamental.getGrossMargin() : null;
        int gmScore = 0;
        if (gm != null) {
            if (gm.doubleValue() >= 40.0) gmScore = 3;
            else if (gm.doubleValue() >= 20.0) gmScore = 2;
            else if (gm.doubleValue() > 0) gmScore = 1;
        }
        items.add(buildItem("毛利率", gm != null ? gm.setScale(2, RoundingMode.HALF_UP) + "%" : "-",
                gmScore, 3, "毛利率≥40%=3分, ≥20%=2分, >0%=1分"));

        // 研报评级（5分）— 从 fundamental.researchScore 注入
        int rScore = fundamental != null ? fundamental.getResearchScore() : 0;
        items.add(buildItem("研报评级",
                fundamental != null && fundamental.getResearchScore() > 0 ? ratingDesc(fundamental.getResearchScore()) : "暂无",
                rScore, 5, "买入=5分, 增持=3分, 中性=1分, 其他=0分"));

        return items;
    }

    private String ratingDesc(int score) {
        if (score >= 5) return "买入";
        if (score >= 3) return "增持";
        if (score >= 1) return "中性";
        return "减持/卖出";
    }
    
    private ScoreDetail.ScoreItem buildItem(String label, String value, int score, int maxScore, String desc) {
        ScoreDetail.ScoreItem item = new ScoreDetail.ScoreItem();
        item.setLabel(label);
        item.setValue(value);
        item.setScore(score);
        item.setMaxScore(maxScore);
        item.setDesc(desc);
        return item;
    }
    
    /**
     * 生成操作建议
     */
    private void generateSignal(TradingSignal signal, int totalScore,
                                BigDecimal supportPrice, BigDecimal resistancePrice) {
        
        String support = supportPrice != null ? supportPrice.setScale(2, RoundingMode.HALF_UP).toString() : null;
        String resistance = resistancePrice != null ? resistancePrice.setScale(2, RoundingMode.HALF_UP).toString() : null;
        
        if (totalScore >= 84) {
            signal.setAction("STRONG_BUY");
            signal.setActionName("强烈买入");
            signal.setPosition(80);
            signal.setConfidence(90);
            signal.setTiming(support != null ? "可分批建仓，回踩" + support + "附近加仓" : "可分批建仓，逢低加仓");
            signal.setRisks(resistance != null ? "关注" + resistance + "阻力位，突破加仓，回落减仓" : "注意高位回调风险，设置止损");
        } else if (totalScore >= 63) {
            signal.setAction("BUY");
            signal.setActionName("买入");
            signal.setPosition(50);
            signal.setConfidence(70);
            signal.setTiming(resistance != null ? "突破" + resistance + "后可加仓" : "可适量参与，突破关键阻力位后加仓");
            signal.setRisks(support != null ? "跌破" + support + "需止损" : "注意量能配合，若缩量上涨需谨慎");
        } else if (totalScore >= 42) {
            signal.setAction("HOLD");
            signal.setActionName("持有");
            signal.setPosition(30);
            signal.setConfidence(50);
            signal.setTiming("暂时观望，等待明确信号");
            signal.setRisks(support != null ? "若跌破" + support + "（近20日低点），考虑减仓" : "若跌破关键支撑位，考虑减仓");
        } else if (totalScore >= 21) {
            signal.setAction("REDUCE");
            signal.setActionName("减仓");
            signal.setPosition(10);
            signal.setConfidence(60);
            signal.setTiming(support != null ? "建议逐步减仓，若失守" + support + "则加速离场" : "建议逐步减仓，控制风险");
            signal.setRisks("趋势偏弱，注意止损");
        } else {
            signal.setAction("CLEAR");
            signal.setActionName("清仓");
            signal.setPosition(0);
            signal.setConfidence(80);
            signal.setTiming("建议清仓离场，等待更好机会");
            signal.setRisks("多项指标走弱，风险较高");
        }
    }
    
    /**
     * 获取评分规则说明（供前端展示）
     */
    public List<ScoreRule> getScoreRules() {
        List<ScoreRule> rules = new ArrayList<>();
        
        rules.add(new ScoreRule("技术面", TECH_WEIGHT,
                "缠论信号(12分) + 趋势状态(8分) + 均线多头(5分) + MACD金叉(5分)"));
        
        rules.add(new ScoreRule("资金面", MONEY_WEIGHT,
                "量比(12分)：≥2.0=12分, ≥1.5=8分, ≥1.0=4分\n" +
                "换手率偏离(13分)：>0%=13分, >-2%=8分, >3%=5分(异常)"));
        
        rules.add(new ScoreRule("事件面", SENTIMENT_WEIGHT,
                "连续涨停(10分) + 炸板率(8分) + 强势股(7分)"));
        
        rules.add(new ScoreRule("基本面", FUNDAMENTAL_WEIGHT,
                "ROE(4分) + PE估值(3分) + 营收增速(3分) + 净利增速(4分) + PB(3分) + 毛利率(3分) + 研报评级(5分)"));
        
        rules.add(new ScoreRule("操作建议", 0,
                "≥84分=强烈买入, ≥63分=买入, ≥42分=持有, ≥21分=减仓, <21分=清仓"));
        
        return rules;
    }
    
    /**
     * 评分规则说明
     */
    @lombok.Data
    public static class ScoreRule {
        private String dimension;
        private int maxScore;
        private String rule;
        
        public ScoreRule(String dimension, int maxScore, String rule) {
            this.dimension = dimension;
            this.maxScore = maxScore;
            this.rule = rule;
        }
    }
}
