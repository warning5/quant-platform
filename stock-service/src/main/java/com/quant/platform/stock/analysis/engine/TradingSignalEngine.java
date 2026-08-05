package com.quant.platform.stock.analysis.engine;

import com.quant.platform.stock.analysis.domain.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import static com.quant.platform.stock.analysis.engine.SignalItemFactory.*;
import static com.quant.platform.stock.analysis.engine.SignalScoreConstants.*;

/**
 * 交易信号引擎（四维度评分 + 规则生成操作建议）
 * 评分维度：技术面(50) + 资金面(25) + 事件面(25) + 基本面(35) = 135分
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TradingSignalEngine {

    private final SignalFundamentalScorer fundamentalScorer;

    private final SignalSentimentScorer sentimentScorer;

    private final SignalMoneyScorer moneyScorer;

    private final SignalTechScorer techScorer;
    

    
    /**
     * 综合评分入口（兼容旧调用，isBlueChip默认false）
     */
    public TradingSignal evaluate(String code, String name,
                                 TechSignal tech, MoneyFlowSignal money,
                                 SentimentSignal sentiment, FundamentalSignal fundamental,
                                 BigDecimal supportPrice, BigDecimal resistancePrice) {
        return evaluate(code, name, tech, money, sentiment, fundamental,
                supportPrice, resistancePrice, false);
    }

    /**
     * 综合评分入口（完整版，支持大盘蓝筹模式）
     */
    public TradingSignal evaluate(String code, String name,
                                 TechSignal tech, MoneyFlowSignal money,
                                 SentimentSignal sentiment, FundamentalSignal fundamental,
                                 BigDecimal supportPrice, BigDecimal resistancePrice,
                                 boolean isBlueChip) {

        TradingSignal signal = new TradingSignal();
        signal.setCode(code);
        signal.setName(name);
        signal.setTechSignal(tech);
        signal.setMoneySignal(money);
        signal.setSentimentSignal(sentiment);
        signal.setFundamentalSignal(fundamental);
        
        // 计算各维度得分
        int techScore = techScorer.calcTechScore(tech);
        int moneyScore = moneyScorer.calcMoneyScore(money);
        int sentimentScore = isBlueChip
                ? sentimentScorer.calcSentimentScoreBlueChip(sentiment)
                : sentimentScorer.calcSentimentScore(sentiment);
        int fundamentalScore = fundamentalScorer.calcFundamentalScore(fundamental);
        
        // 构建评分明细
        List<ScoreDetail> details = buildScoreDetails(tech, money, sentiment, fundamental,
                techScore, moneyScore, sentimentScore, fundamentalScore, isBlueChip);
        signal.setScoreDetails(details);
        
        // 总分
        int totalScore = techScore + moneyScore + sentimentScore + fundamentalScore;
        signal.setTotalScore(totalScore);
        
        // 生成操作建议
        generateSignal(signal, totalScore, supportPrice, resistancePrice);
        
        return signal;
    }
    
    
    

    
    
    /**
     * 构建评分明细
     */
    private List<ScoreDetail> buildScoreDetails(TechSignal tech, MoneyFlowSignal money,
                                                SentimentSignal sentiment, FundamentalSignal fundamental,
                                                int techScore, int moneyScore, 
                                                int sentimentScore, int fundamentalScore,
                                                boolean isBlueChip) {
        
        List<ScoreDetail> details = new ArrayList<>();
        
        // 技术面明细
        ScoreDetail techDetail = new ScoreDetail();
        techDetail.setDimension("tech");
        techDetail.setDimensionName("技术面");
        techDetail.setScore(techScore);
        techDetail.setMaxScore(TECH_WEIGHT);
        techDetail.setItems(techScorer.buildTechItems(tech));
        techDetail.setDataRange("均线/MACD近120日 + RSI14日 + BOLL20日轨道 + 量价背离检测");
        details.add(techDetail);
        
        // 资金面明细
        ScoreDetail moneyDetail = new ScoreDetail();
        moneyDetail.setDimension("money");
        moneyDetail.setDimensionName("资金面");
        moneyDetail.setScore(moneyScore);
        moneyDetail.setMaxScore(MONEY_WEIGHT);
        moneyDetail.setItems(moneyScorer.buildMoneyItems(money));
        moneyDetail.setDataRange("当日主力净流入 + 量比(当日/5日均) + 换手率偏离(当日-20日均)");
        details.add(moneyDetail);
        
        // 事件面明细
        ScoreDetail sentimentDetail = new ScoreDetail();
        sentimentDetail.setDimension("sentiment");
        sentimentDetail.setDimensionName("事件面");
        sentimentDetail.setScore(sentimentScore);
        sentimentDetail.setMaxScore(SENTIMENT_WEIGHT);
        sentimentDetail.setItems(isBlueChip ? sentimentScorer.buildSentimentItemsBlueChip(sentiment) : sentimentScorer.buildSentimentItems(sentiment));
        sentimentDetail.setDataRange("近10日涨停 + 最新龙虎榜 + 融资余额(最新) + 近90天研报");
        details.add(sentimentDetail);
        
        // 基本面明细
        ScoreDetail fundDetail = new ScoreDetail();
        fundDetail.setDimension("fundamental");
        fundDetail.setDimensionName("基本面");
        fundDetail.setScore(fundamentalScore);
        fundDetail.setMaxScore(FUNDAMENTAL_WEIGHT);
        fundDetail.setItems(fundamentalScorer.buildFundamentalItems(fundamental));
        fundDetail.setDataRange("最新一期财报 + 最新研报评级 + 近90天研报覆盖");
        details.add(fundDetail);
        
        return details;
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

        // 计算反转条件（减仓/清仓时，列出回到更高档位需满足的条件）
        computeReversalConditions(signal);
    }

    /**
     * 计算反转条件：当操作为减仓/清仓时，列出将评分提升到更高档位需要满足的条件
     */
    private void computeReversalConditions(TradingSignal signal) {
        String action = signal.getAction();
        if (!"REDUCE".equals(action) && !"CLEAR".equals(action)) {
            signal.setReversalConditions(null);
            return;
        }

        List<String> conditions = new ArrayList<>();
        List<ScoreDetail> details = signal.getScoreDetails();
        if (details == null) {
            signal.setReversalConditions(null);
            return;
        }

        for (ScoreDetail d : details) {
            double pct = d.getMaxScore() > 0 ? (double) d.getScore() / d.getMaxScore() : 0;
            if (pct >= 0.6) continue; // 该维度分数够高，不需要反转条件

            String dim = d.getDimension();
            if ("tech".equals(dim)) {
                if (d.getItems() != null) {
                    for (ScoreDetail.ScoreItem item : d.getItems()) {
                        if (item.getScore() == 0) {
                            conditions.add(mapReversalLabel(item.getLabel()));
                        }
                    }
                }
            } else if ("money".equals(dim)) {
                conditions.add("主力净流入转正");
                conditions.add("量比>1.5");
            } else if ("sentiment".equals(dim)) {
                conditions.add("出现涨停");
                conditions.add("龙虎榜净买入");
            } else if ("fundamental".equals(dim)) {
                conditions.add("ROE>10%");
                conditions.add("营收增速>20%");
            }
        }

        if (conditions.isEmpty()) {
            signal.setReversalConditions(null);
        } else {
            // 去重，最多列4条
            List<String> unique = conditions.stream().distinct().limit(4).collect(java.util.stream.Collectors.toList());
            signal.setReversalConditions(String.join("、", unique) + "后可关注介入时机");
        }
    }

    /**
     * 将英文指标名映射为中文反转条件描述
     */
    private String mapReversalLabel(String label) {
        return switch (label) {
            case "趋势状态" -> "趋势转牛市";
            case "均线多头" -> "均线转多头排列";
            case "MACD金叉" -> "MACD金叉";
            case "主力净流入" -> "主力净流入转正";
            case "主力净流入占比" -> "主力净流入占比转正";
            case "量比" -> "量比>1.5";
            case "换手率偏离" -> "换手率偏离转正";
            case "连续涨停" -> "出现涨停";
            case "炸板率" -> "炸板率降低";
            case "强势股" -> "进入强势股区间";
            case "龙虎榜" -> "龙虎榜净买入";
            case "融资余额变化" -> "融资余额回升";
            case "公告事件" -> "正面公告增加";
            case "ROE" -> "ROE>10%";
            case "PE(TTM)" -> "PE回归合理区间";
            case "营收增速" -> "营收增速>20%";
            case "净利增速" -> "净利增速>20%";
            case "PB" -> "PB<5";
            case "毛利率" -> "毛利率改善";
            case "研报评级" -> "研报评级提升";
            case "研报覆盖热度" -> "研报覆盖增加";
            default -> label + "改善";
        };
    }
    
    /**
     * 获取评分规则说明（供前端展示）
     */
    public List<ScoreRule> getScoreRules() {
        List<ScoreRule> rules = new ArrayList<>();
        
        rules.add(new ScoreRule("技术面", TECH_WEIGHT,
                "趋势状态(10分)：上涨=10分，盘整=4分，下跌=0分\n" +
                "MACD综合(10分)：零轴上金叉+红柱扩张=9分；零轴上金叉+红柱缩=8分；零轴上金叉=7分；零轴下金叉=2分\n" +
                "RSI14(6分)：<30超卖=6分，<50偏弱=4分，<70正常=2分，>70超买=1分\n" +
                "BOLL轨道(6分)：突破上轨(RSI≤70)=6分，突破上轨(RSI>70)=2分，≥0.8上轨附近=4/2分，≥0.5中上=3分，≥0.2中下=2分\n" +
                "DMI强度(3分)：ADX>30强趋势=3分，ADX>20弱趋势=1分（+DI/-DI方向由趋势状态覆盖，不重复计分）\n" +
                "量比(5分)：≥2.0放量=5分，≥1.5温和=3分，≥1.0正常=2分，<0.5极度缩量=-1分\n" +
                "近高近低(8分)：距60日低点<3%=+6分，距<10%=+3分；距高点<3%=-3分，距<10%=-1分\n" +
                "BOLL带宽(2分)：<5%极度收敛=2分（变盘前兆）\n" +
                "综合惩罚：高位背离(价涨主力出)扣6分，低位背离(价跌主力进)加2分；5日涨幅>>20日涨幅=反弹偏离扣2分；均线空头+3分；KDJ死叉+2分；DMI空头+1分；SAR翻空+2分；SAR翻多-2分",
                "均线/MACD近120日 + RSI14日 + BOLL20日轨道 + DMI(ADX) + SAR(抛物线转向) + 量价背离检测 + 近60日高低价"));
        
        rules.add(new ScoreRule("资金面", MONEY_WEIGHT,
                "主力净流入(10分)：>5亿=10分, >1亿=7分, >0=5分, >-1亿=2分\n" +
                "主力净流入占比(8分)：>10%=8分, >5%=6分, >0%=4分, >-5%=2分\n" +
                "量比(4分)：≥2.0=4分, ≥1.5=3分, ≥1.0=2分\n" +
                "换手率偏离(3分)：>0%=3分, >-2%=2分, ≤-2%=1分",
                "当日主力净流入 + 量比(当日/5日均) + 换手率偏离(当日-20日均)"));
        
        rules.add(new ScoreRule("事件面", SENTIMENT_WEIGHT,
                "连续涨停(5分) + 炸板率(5分) + 强势股(4分) + 龙虎榜(4分) + 融资余额(3分) + 公告事件(4分)",
                "近10日涨停 + 最新龙虎榜 + 融资余额(最新) + 近90天研报"));
        
        rules.add(new ScoreRule("基本面", FUNDAMENTAL_WEIGHT,
                "ROE(4分) + 净利率(3分) + PE估值(3分) + 营收增速(3分) + 净利增速(3分) + PB(2分) + 毛利率(3分) + 资产负债率(2分) + 现金流(2分) + 偿债能力(2分) + 研报评级(3分)",
                "最新一期财报 + 最新研报评级 + 近90天研报覆盖"));
        
        rules.add(new ScoreRule("操作建议", 0,
                "≥84分=强烈买入, ≥63分=买入, ≥42分=持有, ≥21分=减仓, <21分=清仓",
                "-"));
        
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
        private String dataRange;

        public ScoreRule(String dimension, int maxScore, String rule, String dataRange) {
            this.dimension = dimension;
            this.maxScore = maxScore;
            this.rule = rule;
            this.dataRange = dataRange;
        }
    }
}
