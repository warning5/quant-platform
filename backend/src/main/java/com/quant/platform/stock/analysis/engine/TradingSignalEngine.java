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
 * 评分维度：技术面(30) + 资金面(25) + 事件面(25) + 基本面(25) = 105分
 */
@Slf4j
@Component
public class TradingSignalEngine {
    
    // ========== 技术面阈值 ==========
    private static final double VOLUME_RATIO_HIGH = 2.0;      // 量比>2倍为放量
    private static final double VOLUME_RATIO_MEDIUM = 1.5;   // 量比>1.5倍为温和放量
    private static final double TURNOVER_DEVIATION_HIGH = 3.0; // 换手率偏离>3%为异常
    
    // ========== 资金面阈值 ==========
    private static final double NET_MAIN_HIGH = 5e8;       // 主力净流入>5亿=强
    private static final double NET_MAIN_MED = 1e8;        // 主力净流入>1亿=中
    private static final double NET_MAIN_LOW = -1e8;       // 主力净流入<-1亿=弱
    private static final double NET_MAIN_VLOW = -3e8;      // 主力净流入<-3亿=严重流出
    private static final double NET_MAIN_PCT_HIGH = 10.0;  // 主力净流入占比>10%=强
    private static final double NET_MAIN_PCT_MED = 5.0;    // 主力净流入占比>5%=中
    private static final double NET_MAIN_PCT_LOW = -5.0;   // 主力净流入占比<-5%=弱
    private static final double NET_MAIN_PCT_VLOW = -10.0;  // 主力净流入占比<-10%=严重流出

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

    // ========== 大盘蓝筹阈值 ==========
    private static final BigDecimal BLUE_CHIP_MARKET_CAP = new BigDecimal("100000000000"); // 1000亿

    // ========== 维度权重 ==========
    private static final int TECH_WEIGHT = 30;
    private static final int MONEY_WEIGHT = 25;
    private static final int SENTIMENT_WEIGHT = 25;
    private static final int FUNDAMENTAL_WEIGHT = 29;
    
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
        int techScore = calcTechScore(tech);
        int moneyScore = calcMoneyScore(money);
        int sentimentScore = isBlueChip
                ? calcSentimentScoreBlueChip(sentiment)
                : calcSentimentScore(sentiment);
        int fundamentalScore = calcFundamentalScore(fundamental);
        
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
     * 权重：主力净流入(10分) + 主力净流入占比(8分) + 量比(4分) + 换手率偏离(3分)
     */
    private int calcMoneyScore(MoneyFlowSignal money) {
        if (money == null) return 0;
        
        int score = 0;
        
        // 主力净流入（10分）— 最核心指标，直接反映大资金动向
        if (money.getNetMain() != null) {
            double nm = money.getNetMain().doubleValue();
            if (nm >= NET_MAIN_HIGH) {
                score += 10; // 强力流入（>5亿）
            } else if (nm >= NET_MAIN_MED) {
                score += 7;  // 明显流入（>1亿）
            } else if (nm > 0) {
                score += 5;  // 小幅流入
            } else if (nm > NET_MAIN_LOW) {
                score += 2;  // 小幅流出（>-1亿）
            } else if (nm > NET_MAIN_VLOW) {
                score += 1;  // 中度流出（>-3亿）
            }
            // ≤-3亿严重流出 = 0分
        }
        
        // 主力净流入占比（8分）— 相对比例，消除市值大小偏差
        if (money.getNetMainPct() != null) {
            double pct = money.getNetMainPct().doubleValue();
            if (pct >= NET_MAIN_PCT_HIGH) {
                score += 8;  // 占比>10%，强力
            } else if (pct >= NET_MAIN_PCT_MED) {
                score += 6;  // 占比>5%，明显
            } else if (pct > 0) {
                score += 4;  // 占比>0%，小幅
            } else if (pct > NET_MAIN_PCT_LOW) {
                score += 2;  // 占比>-5%，温和流出
            } else if (pct > NET_MAIN_PCT_VLOW) {
                score += 1;  // 占比>-10%，中度流出
            }
            // ≤-10% = 0分
        }
        
        // 量比（4分）— 成交活跃度
        if (money.getVolumeRatio() != null) {
            double vr = money.getVolumeRatio().doubleValue();
            if (vr >= VOLUME_RATIO_HIGH) {
                score += 4; // 放量
            } else if (vr >= VOLUME_RATIO_MEDIUM) {
                score += 3; // 温和放量
            } else if (vr >= 1.0) {
                score += 2; // 正常量能
            }
        }
        
        // 换手率偏离（3分）— 交投活跃度变化
        if (money.getTurnoverDeviation() != null) {
            double dev = money.getTurnoverDeviation().doubleValue();
            if (dev > 0) {
                score += 3; // 活跃度提升
            } else if (dev > -2) {
                score += 2; // 正常
            } else {
                score += 1; // 低迷
            }
        }
        
        return Math.max(0, Math.min(MONEY_WEIGHT, score));
    }
    
    /**
     * 计算事件面得分——大盘蓝筹模式（满分25）
     * 指标：融资余额变化(6分) + 龙虎榜机构净买入(6分) + 机构调研热度(6分) + 龙虎榜上榜(4分) + 公告事件(3分)
     */
    private int calcSentimentScoreBlueChip(SentimentSignal sentiment) {
        if (sentiment == null) return 0;
        int score = 0;

        // 融资余额变化（6分）— 杠杆资金态度
        if (sentiment.getMarginChgPct() != null) {
            double chg = sentiment.getMarginChgPct().doubleValue();
            if (chg > 5.0) {
                score += 6;
            } else if (chg > 2.0) {
                score += 4;
            } else if (chg > 0) {
                score += 2;
            } else if (chg > -3.0) {
                score += 1;
            }
        }

        // 龙虎榜机构净买入（6分）
        if (sentiment.getLhbInstitutionNet() != null) {
            double lhb = sentiment.getLhbInstitutionNet().doubleValue();
            if (lhb > 50e6) {
                score += 6;
            } else if (lhb > 10e6) {
                score += 4;
            } else if (lhb > 0) {
                score += 2;
            } else if (lhb > -10e6) {
                score += 1;
            }
        }

        // 机构调研热度（6分）
        if (sentiment.getHolderChangePct() != null) {
            double surveyCount = sentiment.getHolderChangePct().doubleValue();
            if (surveyCount >= 10.0) {
                score += 6;
            } else if (surveyCount >= 5.0) {
                score += 4;
            } else if (surveyCount >= 2.0) {
                score += 2;
            } else if (surveyCount >= 1.0) {
                score += 1;
            }
        }

        // 龙虎榜上榜（4分）— 非机构数据，所有龙虎榜记录
        if (sentiment.getLhbAppearCount() != null && sentiment.getLhbAppearCount() > 0) {
            BigDecimal netAmt = sentiment.getLhbNetAmount();
            if (netAmt != null && netAmt.doubleValue() > 0) {
                score += 4;
            } else {
                score += 1;
            }
        }

        // 公告事件（3分）
        int posCount = sentiment.getNoticePositiveCount() != null ? sentiment.getNoticePositiveCount() : 0;
        int negCount = sentiment.getNoticeNegativeCount() != null ? sentiment.getNoticeNegativeCount() : 0;
        int eventNet = posCount - negCount;
        if (eventNet >= 3) {
            score += 3;
        } else if (eventNet >= 1) {
            score += 2;
        } else if (eventNet < 0) {
            // 负面公告多，扣分到0
        }

        return Math.max(0, Math.min(SENTIMENT_WEIGHT, score));
    }

    /**
     * 计算事件面得分（满分25）
     * 涨停5 + 炸板率5 + 强势股4 + 龙虎榜4 + 融资余额3 + 公告事件4 = 25
     */
    private int calcSentimentScore(SentimentSignal sentiment) {
        if (sentiment == null) return 0;

        int score = 0;

        // 1. 连续涨停（5分）— 近10日涨停天数
        if (sentiment.getLimitUpDays() != null) {
            int days = sentiment.getLimitUpDays();
            if (days >= 3) {
                score += 5; // 连板3天以上
            } else if (days >= 2) {
                score += 4;
            } else if (days > 0) {
                score += 2;
            }
        }

        // 2. 炸板率（5分）— 越低越好，低炸板率说明封板坚决
        if (sentiment.getBrokenLimitUpRate() != null) {
            double rate = sentiment.getBrokenLimitUpRate();
            if (rate < 10.0) {
                score += 5;
            } else if (rate < 30.0) {
                score += 3;
            } else if (rate < 50.0) {
                score += 1;
            }
        }

        // 3. 强势股（4分）— 20日涨幅>30%
        if (Boolean.TRUE.equals(sentiment.getIsStrongStock())) {
            score += 4;
        }

        // 4. 龙虎榜信号（4分）— 上榜且净买入为正
        if (sentiment.getLhbAppearCount() != null && sentiment.getLhbAppearCount() > 0) {
            BigDecimal netAmt = sentiment.getLhbNetAmount();
            if (netAmt != null && netAmt.doubleValue() > 0) {
                score += 4; // 上榜+净买入
            } else {
                score += 1; // 上榜但净卖出
            }
        }

        // 5. 融资余额变化（3分）— 资金加杠杆看多
        if (sentiment.getMarginChgPct() != null) {
            double pct = sentiment.getMarginChgPct().doubleValue();
            if (pct > 5.0) {
                score += 3;
            } else if (pct > 2.0) {
                score += 2;
            } else if (pct > 0) {
                score += 1;
            }
        }

        // 6. 公告事件（4分）— 正面-负面
        int posCount = sentiment.getNoticePositiveCount() != null ? sentiment.getNoticePositiveCount() : 0;
        int negCount = sentiment.getNoticeNegativeCount() != null ? sentiment.getNoticeNegativeCount() : 0;
        int eventNet = posCount - negCount;
        if (eventNet >= 3) {
            score += 4;
        } else if (eventNet >= 1) {
            score += 2;
        } else if (eventNet < 0) {
            score += 0; // 负面公告多，不加分
        }

        return Math.min(SENTIMENT_WEIGHT, score);
    }
    
    /**
     * 计算基本面得分（满分29）
     * 权重：ROE 4分 + PE 3分 + 营收增速 3分 + 净利增速 4分 + PB 3分 + 毛利率 3分 + 研报评级 5分 + 研报覆盖热度 4分
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

        // 研报覆盖热度（4分）— 近90天研报数量
        int rc = fundamental.getReportCount();
        if (rc >= 10) {
            score += 4;  // 高度关注
        } else if (rc >= 5) {
            score += 3;
        } else if (rc >= 2) {
            score += 2;
        } else if (rc >= 1) {
            score += 1;
        }
        // 0篇 = 0分

        return Math.max(0, Math.min(FUNDAMENTAL_WEIGHT, score));
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
        techDetail.setItems(buildTechItems(tech));
        techDetail.setDataRange("缠论最新1条 + 均线/MACD近120日 + RSI14日");
        details.add(techDetail);
        
        // 资金面明细
        ScoreDetail moneyDetail = new ScoreDetail();
        moneyDetail.setDimension("money");
        moneyDetail.setDimensionName("资金面");
        moneyDetail.setScore(moneyScore);
        moneyDetail.setMaxScore(MONEY_WEIGHT);
        moneyDetail.setItems(buildMoneyItems(money));
        moneyDetail.setDataRange("当日主力净流入 + 量比(当日/5日均) + 换手率偏离(当日-20日均)");
        details.add(moneyDetail);
        
        // 事件面明细
        ScoreDetail sentimentDetail = new ScoreDetail();
        sentimentDetail.setDimension("sentiment");
        sentimentDetail.setDimensionName("事件面");
        sentimentDetail.setScore(sentimentScore);
        sentimentDetail.setMaxScore(SENTIMENT_WEIGHT);
        sentimentDetail.setItems(isBlueChip ? buildSentimentItemsBlueChip(sentiment) : buildSentimentItems(sentiment));
        sentimentDetail.setDataRange("近10日涨停 + 最新龙虎榜 + 融资余额(最新) + 近90天研报");
        details.add(sentimentDetail);
        
        // 基本面明细
        ScoreDetail fundDetail = new ScoreDetail();
        fundDetail.setDimension("fundamental");
        fundDetail.setDimensionName("基本面");
        fundDetail.setScore(fundamentalScore);
        fundDetail.setMaxScore(FUNDAMENTAL_WEIGHT);
        fundDetail.setItems(buildFundamentalItems(fundamental));
        fundDetail.setDataRange("最新一期财报 + 最新研报评级 + 近90天研报覆盖");
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

        // 主力净流入（10分）
        BigDecimal nm = money != null ? money.getNetMain() : null;
        int nmScore = 0;
        if (nm != null) {
            double v = nm.doubleValue();
            if (v >= NET_MAIN_HIGH) nmScore = 10;
            else if (v >= NET_MAIN_MED) nmScore = 7;
            else if (v > 0) nmScore = 5;
            else if (v > NET_MAIN_LOW) nmScore = 2;
            else if (v > NET_MAIN_VLOW) nmScore = 1;
        }
        items.add(buildItem("主力净流入", nm != null ? formatMoneyFlow(nm) : "暂无数据",
                nmScore, 10, ">5亿=10分, >1亿=7分, >0=5分, >-1亿=2分, >-3亿=1分, ≤-3亿=0分"));

        // 主力净流入占比（8分）
        BigDecimal nmPct = money != null ? money.getNetMainPct() : null;
        int pctScore = 0;
        if (nmPct != null) {
            double v = nmPct.doubleValue();
            if (v >= NET_MAIN_PCT_HIGH) pctScore = 8;
            else if (v >= NET_MAIN_PCT_MED) pctScore = 6;
            else if (v > 0) pctScore = 4;
            else if (v > NET_MAIN_PCT_LOW) pctScore = 2;
            else if (v > NET_MAIN_PCT_VLOW) pctScore = 1;
        }
        items.add(buildItem("主力净流入占比", nmPct != null ? nmPct.setScale(2, RoundingMode.HALF_UP) + "%" : "暂无数据",
                pctScore, 8, ">10%=8分, >5%=6分, >0%=4分, >-5%=2分, >-10%=1分, ≤-10%=0分"));

        // 量比（4分）
        BigDecimal vr = money != null ? money.getVolumeRatio() : null;
        int vrScore = 0;
        if (vr != null) {
            if (vr.doubleValue() >= VOLUME_RATIO_HIGH) vrScore = 4;
            else if (vr.doubleValue() >= VOLUME_RATIO_MEDIUM) vrScore = 3;
            else if (vr.doubleValue() >= 1.0) vrScore = 2;
        }
        items.add(buildItem("量比", vr != null ? vr.setScale(2, RoundingMode.HALF_UP).toString() : "-",
                vrScore, 4, "量比≥2.0=4分, ≥1.5=3分, ≥1.0=2分"));

        // 换手率偏离（3分）
        BigDecimal dev = money != null ? money.getTurnoverDeviation() : null;
        int devScore = 0;
        if (dev != null) {
            if (dev.doubleValue() > 0) devScore = 3;
            else if (dev.doubleValue() > -2) devScore = 2;
            else devScore = 1;
        }
        items.add(buildItem("换手率偏离", dev != null ? dev.setScale(2, RoundingMode.HALF_UP) + "%" : "-",
                devScore, 3, "偏离>0%=3分, >-2%=2分, ≤-2%=1分"));

        return items;
    }

    /**
     * 格式化资金流向金额为可读字符串
     * >1亿 显示"X.XX亿", >1万 显示"X.XX万", 否则显示具体数值
     */
    private String formatMoneyFlow(BigDecimal amount) {
        double v = amount.doubleValue();
        double absV = Math.abs(v);
        String sign = v >= 0 ? "+" : "";
        if (absV >= 1e8) {
            return sign + String.format("%.2f亿", v / 1e8);
        } else if (absV >= 1e4) {
            return sign + String.format("%.2f万", v / 1e4);
        } else {
            return sign + String.format("%.0f元", v);
        }
    }
    
    private List<ScoreDetail.ScoreItem> buildSentimentItems(SentimentSignal sentiment) {
        List<ScoreDetail.ScoreItem> items = new ArrayList<>();

        // 1. 连续涨停（5分）
        Integer days = sentiment != null ? sentiment.getLimitUpDays() : null;
        int daysScore = 0;
        if (days != null) {
            if (days >= 3) daysScore = 5;
            else if (days >= 2) daysScore = 4;
            else if (days > 0) daysScore = 2;
        }
        items.add(buildItem("连续涨停", days != null ? days + "天" : "-",
                daysScore, 5, "连板≥3天=5分, 2天=4分, 1天=2分"));

        // 2. 炸板率（5分）
        Double rate = sentiment != null ? sentiment.getBrokenLimitUpRate() : null;
        int rateScore = 0;
        if (rate != null) {
            if (rate < 10.0) rateScore = 5;
            else if (rate < 30.0) rateScore = 3;
            else if (rate < 50.0) rateScore = 1;
        }
        items.add(buildItem("炸板率", rate != null ? String.format("%.1f%%", rate) : "-",
                rateScore, 5, "炸板率<10%=5分, <30%=3分, <50%=1分"));

        // 3. 强势股（4分）
        items.add(buildItem("强势股",
                sentiment != null && Boolean.TRUE.equals(sentiment.getIsStrongStock()) ? "是" : "否",
                sentiment != null && Boolean.TRUE.equals(sentiment.getIsStrongStock()) ? 4 : 0, 4,
                "20日涨幅>30%=4分"));

        // 4. 龙虎榜信号（4分）
        Integer lhbCount = sentiment != null ? sentiment.getLhbAppearCount() : null;
        BigDecimal lhbNet = sentiment != null ? sentiment.getLhbNetAmount() : null;
        int lhbScore = 0;
        String lhbDisplay = "-";
        if (lhbCount != null && lhbCount > 0) {
            if (lhbNet != null && lhbNet.doubleValue() > 0) {
                lhbScore = 4;
                lhbDisplay = "上榜" + lhbCount + "次,净买入" + formatMoneyFlow(lhbNet);
            } else {
                lhbScore = 1;
                lhbDisplay = "上榜" + lhbCount + "次,净卖出";
            }
        } else {
            lhbDisplay = "未上榜";
        }
        items.add(buildItem("龙虎榜", lhbDisplay,
                lhbScore, 4, "上榜+净买入=4分, 上榜+净卖出=1分"));

        // 5. 融资余额变化（3分）
        BigDecimal mcp = sentiment != null ? sentiment.getMarginChgPct() : null;
        int mcpScore = 0;
        if (mcp != null) {
            double pct = mcp.doubleValue();
            if (pct > 5.0) mcpScore = 3;
            else if (pct > 2.0) mcpScore = 2;
            else if (pct > 0) mcpScore = 1;
        }
        items.add(buildItem("融资余额变化", mcp != null ? mcp.setScale(2, RoundingMode.HALF_UP) + "%" : "-",
                mcpScore, 3, "变化>5%=3分, >2%=2分, >0%=1分"));

        // 6. 公告事件（4分）
        int posCount = sentiment != null && sentiment.getNoticePositiveCount() != null ? sentiment.getNoticePositiveCount() : 0;
        int negCount = sentiment != null && sentiment.getNoticeNegativeCount() != null ? sentiment.getNoticeNegativeCount() : 0;
        int eventNet = posCount - negCount;
        int eventScore = 0;
        String eventDisplay = "正面" + posCount + "/负面" + negCount;
        if (eventNet >= 3) eventScore = 4;
        else if (eventNet >= 1) eventScore = 2;
        items.add(buildItem("公告事件", eventDisplay,
                eventScore, 4, "正面-负面≥3=4分, ≥1=2分"));

        return items;
    }

    /**
     * 大盘蓝筹事件面评分明细
     * 融资余额(6) + 机构净买入(6) + 机构调研(6) + 龙虎榜上榜(4) + 公告事件(3) = 25
     */
    private List<ScoreDetail.ScoreItem> buildSentimentItemsBlueChip(SentimentSignal sentiment) {
        List<ScoreDetail.ScoreItem> items = new ArrayList<>();

        // 融资余额变化（6分）
        BigDecimal mcp = sentiment != null ? sentiment.getMarginChgPct() : null;
        int mcpScore = 0;
        if (mcp != null) {
            if (mcp.doubleValue() > 5.0) mcpScore = 6;
            else if (mcp.doubleValue() > 2.0) mcpScore = 4;
            else if (mcp.doubleValue() > 0) mcpScore = 2;
            else if (mcp.doubleValue() > -3.0) mcpScore = 1;
        }
        items.add(buildItem("融资余额变化", mcp != null ? mcp.setScale(2, RoundingMode.HALF_UP) + "%" : "-",
                mcpScore, 6, "变化>5%=6分, >2%=4分, >0%=2分, >-3%=1分"));

        // 龙虎榜机构净买入（6分）
        BigDecimal lhb = sentiment != null ? sentiment.getLhbInstitutionNet() : null;
        int lhbScore = 0;
        if (lhb != null) {
            double v = lhb.doubleValue();
            if (v > 50e6) lhbScore = 6;
            else if (v > 10e6) lhbScore = 4;
            else if (v > 0) lhbScore = 2;
            else if (v > -10e6) lhbScore = 1;
        }
        String lhbDisplay = lhb != null ? formatMoneyFlow(lhb) : "-";
        items.add(buildItem("龙虎榜机构净买入", lhbDisplay,
                lhbScore, 6, "净买入>5000万=6分, >1000万=4分, >0=2分"));

        // 机构调研热度（6分）
        BigDecimal hc = sentiment != null ? sentiment.getHolderChangePct() : null;
        int hcScore = 0;
        if (hc != null) {
            if (hc.doubleValue() >= 10.0) hcScore = 6;
            else if (hc.doubleValue() >= 5.0) hcScore = 4;
            else if (hc.doubleValue() >= 2.0) hcScore = 2;
            else if (hc.doubleValue() >= 1.0) hcScore = 1;
        }
        items.add(buildItem("机构调研热度", hc != null ? hc.intValue() + "次" : "-",
                hcScore, 6, "90天内≥10次=6分, ≥5次=4分, ≥2次=2分, ≥1次=1分"));

        // 龙虎榜上榜（4分）
        Integer lhbCount = sentiment != null ? sentiment.getLhbAppearCount() : null;
        BigDecimal lhbNet = sentiment != null ? sentiment.getLhbNetAmount() : null;
        int lhbAppearScore = 0;
        String lhbAppearDisplay = "-";
        if (lhbCount != null && lhbCount > 0) {
            if (lhbNet != null && lhbNet.doubleValue() > 0) {
                lhbAppearScore = 4;
                lhbAppearDisplay = "上榜" + lhbCount + "次,净买入";
            } else {
                lhbAppearScore = 1;
                lhbAppearDisplay = "上榜" + lhbCount + "次,净卖出";
            }
        } else {
            lhbAppearDisplay = "未上榜";
        }
        items.add(buildItem("龙虎榜上榜", lhbAppearDisplay,
                lhbAppearScore, 4, "上榜+净买入=4分, 上榜+净卖出=1分"));

        // 公告事件（3分）
        int posCount = sentiment != null && sentiment.getNoticePositiveCount() != null ? sentiment.getNoticePositiveCount() : 0;
        int negCount = sentiment != null && sentiment.getNoticeNegativeCount() != null ? sentiment.getNoticeNegativeCount() : 0;
        int eventNet = posCount - negCount;
        int eventScore = 0;
        String eventDisplay = "正面" + posCount + "/负面" + negCount;
        if (eventNet >= 3) eventScore = 3;
        else if (eventNet >= 1) eventScore = 2;
        items.add(buildItem("公告事件", eventDisplay,
                eventScore, 3, "正面-负面≥3=3分, ≥1=2分"));

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

        // 研报覆盖热度（4分）— 近90天研报数量
        int rc = fundamental != null ? fundamental.getReportCount() : 0;
        int rcScore = 0;
        if (rc >= 10) rcScore = 4;
        else if (rc >= 5) rcScore = 3;
        else if (rc >= 2) rcScore = 2;
        else if (rc >= 1) rcScore = 1;
        items.add(buildItem("研报覆盖热度",
                rc + "篇(90天)",
                rcScore, 4, "≥10篇=4分, ≥5篇=3分, ≥2篇=2分, ≥1篇=1分, 0篇=0分"));

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
            case "缠论信号" -> "缠论信号转买入";
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
                "缠论信号(12分) + 趋势状态(8分) + 均线多头(5分) + MACD金叉(5分)",
                "缠论最新1条 + 均线/MACD近120日 + RSI14日"));
        
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
                "ROE(4分) + PE估值(3分) + 营收增速(3分) + 净利增速(4分) + PB(3分) + 毛利率(3分) + 研报评级(5分) + 研报覆盖热度(4分)",
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
