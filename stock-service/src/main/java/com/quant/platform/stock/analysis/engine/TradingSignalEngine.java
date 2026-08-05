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

    private final SignalTechScorer techScorer;
    

    /**
     * 判断是否为金融行业股票
     */
    private boolean isFinancialStock(FundamentalSignal fundamental) {
        if (fundamental == null || fundamental.getIndustry() == null) return false;
        return FINANCIAL_INDUSTRIES.contains(fundamental.getIndustry().trim());
    }
    
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
     * 计算资金面得分（满分25）
     * 权重：主力净流入(6分) + 主力净流入占比(5分) + 融资余额变化(3分)
     *   + 5日累计主力净流入(3分) + 股东人数变化(2分) + 量比(3分) + 换手率偏离(3分)
     */
    private int calcMoneyScore(MoneyFlowSignal money) {
        if (money == null) return 0;
        int score = 0;

        // 1. 主力净流入（6分）
        if (money.getNetMain() != null) {
            double nm = money.getNetMain().doubleValue();
            if (nm >= NET_MAIN_HIGH) {
                score += 6;
            } else if (nm >= NET_MAIN_MED) {
                score += 4;
            } else if (nm > 0) {
                score += 3;
            } else if (nm > NET_MAIN_LOW) {
                score += 1;
            }
        }

        // 2. 主力净流入占比（5分）
        if (money.getNetMainPct() != null) {
            double pct = money.getNetMainPct().doubleValue();
            if (pct >= NET_MAIN_PCT_HIGH) {
                score += 5;
            } else if (pct >= NET_MAIN_PCT_MED) {
                score += 4;
            } else if (pct > 0) {
                score += 3;
            } else if (pct > NET_MAIN_PCT_LOW) {
                score += 1;
            }
        }

        // 3. 融资余额变化（3分）— 从事件面移入
        if (money.getMarginChgPct() != null) {
            double pct = money.getMarginChgPct().doubleValue();
            if (pct > 5.0) {
                score += 3;
            } else if (pct > 2.0) {
                score += 2;
            } else if (pct > 0) {
                score += 1;
            }
        }

        // 4. 5日累计主力净流入（3分）
        if (money.getNetMain5d() != null) {
            double nm5 = money.getNetMain5d().doubleValue();
            if (nm5 >= 3e8) {
                score += 3;  // >3亿
            } else if (nm5 >= 1e8) {
                score += 2;  // >1亿
            } else if (nm5 > 0) {
                score += 1;  // >0
            }
        }

        // 5. 股东人数变化（2分，负值=筹码集中=看多）
        if (money.getShareholderChangePct() != null) {
            double chg = money.getShareholderChangePct().doubleValue();
            if (chg < -10.0) {
                score += 2;  // 筹码高度集中
            } else if (chg < -5.0) {
                score += 1;  // 筹码集中
            }
        }

        // 6. 量比（2分，调整为给新指标腾出空间）
        if (money.getVolumeRatio() != null) {
            double vr = money.getVolumeRatio().doubleValue();
            if (vr >= VOLUME_RATIO_HIGH) {
                score += 2;
            } else if (vr >= VOLUME_RATIO_MEDIUM) {
                score += 1;
            }
        }

        // 7. 换手率偏离（3分）
        if (money.getTurnoverDeviation() != null) {
            double dev = money.getTurnoverDeviation().doubleValue();
            if (dev > 0) {
                score += 3;
            } else if (dev > -2) {
                score += 2;
            } else {
                score += 1;
            }
        }

        // 8. 内外盘比（2分，外盘/内盘，>1买方强势，防造假参考）
        if (money.getOuterInnerRatio() != null) {
            double ratio = money.getOuterInnerRatio().doubleValue();
            if (ratio > 1.2) {
                score += 2;  // 强势买方
            } else if (ratio > 1.0) {
                score += 1;  // 买方略强
            }
        }

        return Math.max(0, Math.min(MONEY_WEIGHT, score));
    }
    
    /**
     * 计算事件面得分——大盘蓝筹模式（满分25）
     * 指标：龙虎榜机构净买入(4分) + 机构调研热度(6分) + 基金持仓(4分)
     *   + 龙虎榜上榜(3分) + 公告事件(2分) + 融资余额已移至资金面
     */
    private int calcSentimentScoreBlueChip(SentimentSignal sentiment) {
        if (sentiment == null) return 0;
        int score = 0;

        // 1. 龙虎榜机构净买入（4分）— 机构席位真金白银
        if (sentiment.getLhbInstitutionNet() != null) {
            double lhb = sentiment.getLhbInstitutionNet().doubleValue();
            if (lhb > 50e6) {
                score += 4;
            } else if (lhb > 10e6) {
                score += 3;
            } else if (lhb > 0) {
                score += 2;
            } else if (lhb > -10e6) {
                score += 1;
            }
        }

        // 2. 机构调研热度（6分）— 近90天研报数量（替代旧 holderChangePct）
        if (sentiment.getResearchReportCount90d() != null) {
            int cnt = sentiment.getResearchReportCount90d();
            if (cnt >= 10) {
                score += 6;
            } else if (cnt >= 5) {
                score += 4;
            } else if (cnt >= 2) {
                score += 2;
            } else if (cnt >= 1) {
                score += 1;
            }
        }

        // 3. 基金持仓集中度（4分）— float_ratio 合计占流通股比例
        if (sentiment.getFundHolderRatio() != null) {
            double ratio = sentiment.getFundHolderRatio().doubleValue();
            if (ratio >= 20.0) {
                score += 4;
            } else if (ratio >= 10.0) {
                score += 3;
            } else if (ratio >= 5.0) {
                score += 2;
            } else if (ratio > 0) {
                score += 1;
            }
        }

        // 4. 龙虎榜上榜（3分）— 非机构数据，所有龙虎榜记录
        if (sentiment.getLhbAppearCount() != null && sentiment.getLhbAppearCount() > 0) {
            BigDecimal netAmt = sentiment.getLhbNetAmount();
            if (netAmt != null && netAmt.doubleValue() > 0) {
                score += 3;
            } else {
                score += 1;
            }
        }

        // 5. 公告事件（2分）— 正面-负面（关键词已扩展）
        int posCount = sentiment.getNoticePositiveCount() != null ? sentiment.getNoticePositiveCount() : 0;
        int negCount = sentiment.getNoticeNegativeCount() != null ? sentiment.getNoticeNegativeCount() : 0;
        int eventNet = posCount - negCount;
        if (eventNet >= 3) {
            score += 2;
        } else if (eventNet >= 1) {
            score += 1;
        }

        // 融资余额变化已移至资金面评分

        return Math.max(0, Math.min(SENTIMENT_WEIGHT, score));
    }

    /**
     * 计算事件面得分（满分25）
     * 涨停4 + 炸板率3 + 强势股4 + 龙虎榜3 + 机构调研6 + 基金持仓4 + 公告事件2 = 26... → 调整
     * 涨停4 + 炸板率3 + 强势股3 + 龙虎榜3 + 机构调研6 + 基金持仓4 + 公告事件2 = 25
     */
    private int calcSentimentScore(SentimentSignal sentiment) {
        if (sentiment == null) return 0;

        int score = 0;

        // 1. 连续涨停（4分）— 近10日涨停天数
        if (sentiment.getLimitUpDays() != null) {
            int days = sentiment.getLimitUpDays();
            if (days >= 3) {
                score += 4;
            } else if (days >= 2) {
                score += 3;
            } else if (days > 0) {
                score += 2;
            }
        }

        // 2. 炸板率（3分）— 越低越好
        if (sentiment.getBrokenLimitUpRate() != null) {
            double rate = sentiment.getBrokenLimitUpRate();
            if (rate < 10.0) {
                score += 3;
            } else if (rate < 30.0) {
                score += 2;
            } else if (rate < 50.0) {
                score += 1;
            }
        }

        // 3. 强势股（3分）— 20日涨幅>30%
        if (Boolean.TRUE.equals(sentiment.getIsStrongStock())) {
            score += 3;
        }

        // 4. 龙虎榜信号（3分）— 上榜且净买入为正
        if (sentiment.getLhbAppearCount() != null && sentiment.getLhbAppearCount() > 0) {
            BigDecimal netAmt = sentiment.getLhbNetAmount();
            if (netAmt != null && netAmt.doubleValue() > 0) {
                score += 3;
            } else {
                score += 1;
            }
        }

        // 5. 机构调研热度（6分）— 近90天研报数量（新增）
        if (sentiment.getResearchReportCount90d() != null) {
            int cnt = sentiment.getResearchReportCount90d();
            if (cnt >= 10) {
                score += 6;
            } else if (cnt >= 5) {
                score += 4;
            } else if (cnt >= 2) {
                score += 2;
            } else if (cnt >= 1) {
                score += 1;
            }
        }

        // 6. 基金持仓集中度（4分）— float_ratio 合计占流通股比例（新增）
        if (sentiment.getFundHolderRatio() != null) {
            double ratio = sentiment.getFundHolderRatio().doubleValue();
            if (ratio >= 20.0) {
                score += 4;  // >20% = 机构重仓
            } else if (ratio >= 10.0) {
                score += 3;  // >10%
            } else if (ratio >= 5.0) {
                score += 2;  // >5%
            } else if (ratio > 0) {
                score += 1;  // 有基金持仓
            }
        }

        // 7. 公告事件（2分）— 正面-负面（关键词已扩展为含投资/扩产）
        int posCount = sentiment.getNoticePositiveCount() != null ? sentiment.getNoticePositiveCount() : 0;
        int negCount = sentiment.getNoticeNegativeCount() != null ? sentiment.getNoticeNegativeCount() : 0;
        int eventNet = posCount - negCount;
        if (eventNet >= 3) {
            score += 2;
        } else if (eventNet >= 1) {
            score += 1;
        }

        // 8. 新闻事件（2分）— 来自东方财富个股新闻（利好/风险/情感偏向）
        Integer ns = sentiment.getNewsScore();
        if (ns != null && ns > 0) {
            if (ns >= 8) {
                score += 2;  // 重大利好新闻
            } else if (ns >= 5) {
                score += 1;  // 中性偏多
            }
        } else if (ns != null && ns == 0) {
            // 无利好新闻，且有风险信号，扣0.5分
            // 不做扣分处理，保持评分非负
        }

        return Math.max(0, Math.min(SENTIMENT_WEIGHT, score));
    }
    
    /**
     * 计算基本面得分（满分30）
     * 评分项（共8项）：
     *   ROE(4分) + 净利率(3分) + PE估值(3分) + 营收增速(3分)
     *   + 净利增速(3分) + PB估值(2分) + 毛利率(3分) + 资产负债率(2分)
     *   + 现金流质量(2分) + 偿债能力(2分) + 研报评级(3分) = 30分
     * 不评分仅展示：营收/净利润绝对值、扣非增速、商誉、存货、货币资金、自由现金流、研报覆盖热度
     */
    private int calcFundamentalScore(FundamentalSignal fundamental) {
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
    
    
    private List<ScoreDetail.ScoreItem> buildMoneyItems(MoneyFlowSignal money) {
        List<ScoreDetail.ScoreItem> items = new ArrayList<>();

        // 1. 主力净流入（6分）
        BigDecimal nm = money != null ? money.getNetMain() : null;
        double nmVal = nm != null ? nm.doubleValue() : 0;
        int nmScore = 0;
        if (nm != null) {
            if (nmVal >= NET_MAIN_HIGH) nmScore = 6;
            else if (nmVal >= NET_MAIN_MED) nmScore = 4;
            else if (nmVal > 0) nmScore = 3;
            else if (nmVal > NET_MAIN_LOW) nmScore = 1;
        }
        String nmColor = nmVal > 0 ? "red" : nmVal < 0 ? "green" : "default";
        items.add(buildItem("主力净流入", nm != null ? formatMoneyFlow(nm) : "暂无数据",
                nmScore, 6,
                "【指标定义】主力净流入 = 大单(20~100万) + 超大单(>100万)当日净流入额，反映大资金当日净买卖方向。" +
                "正值 = 大资金净买入（做多）；负值 = 大资金净卖出（做空）。" +
                "【为什么用它】主力净流入是判断大资金态度最直接的指标——它回答'今天谁在买、谁在卖'。" +
                "散户交易呈现随机分布，而主力操作会集中体现在大单/超大单上。该指标源自东财真实逐笔成交数据，" +
                "经席位分类统计得出，比传统技术指标更能反映真实资金流向。" +
                "【评分逻辑】净流入≥5亿=6分（机构级别强力做多）；≥1亿=4分（大资金积极介入）；" +
                ">0=3分（资金小幅流入）；>-1亿=1分（资金小幅流出，但抛压可控）；≤-1亿=0分（资金明显撤离）。" +
                "【注意】单日报应结合5日累计看趋势，单日净流入可能受消息脉冲影响。",
                false, nmColor));

        // 2. 主力净流入占比（5分）
        BigDecimal nmPct = money != null ? money.getNetMainPct() : null;
        double nmPctVal = nmPct != null ? nmPct.doubleValue() : 0;
        int pctScore = 0;
        if (nmPct != null) {
            if (nmPctVal >= NET_MAIN_PCT_HIGH) pctScore = 5;
            else if (nmPctVal >= NET_MAIN_PCT_MED) pctScore = 4;
            else if (nmPctVal > 0) pctScore = 3;
            else if (nmPctVal > NET_MAIN_PCT_LOW) pctScore = 1;
        }
        String pctColor = nmPctVal > 0 ? "red" : nmPctVal < 0 ? "green" : "default";
        items.add(buildItem("主力净流入占比", nmPct != null ? nmPct.setScale(2, RoundingMode.HALF_UP) + "%" : "暂无数据",
                pctScore, 5,
                "【指标定义】主力净流入占比 = 主力净流入额 / 当日成交额 × 100%，衡量主力资金在当日总成交中的占比。" +
                "它回答'今天的大资金动向，在整体交易中占多大分量'。" +
                "【为什么用它】绝对金额对小盘股不公平——1亿净流入对100亿市值是巨量，对5000亿市值只是毛毛雨。" +
                "占比消除了市值差异，实现跨股票可比。同时它能识别'伪流入'：某股成交额100亿、净流入5亿(占比5%)，" +
                "另一股成交额1亿、净流入3000万(占比30%)，后者资金态度坚决得多。" +
                "【评分逻辑】占比≥10%=5分（资金态度坚决，控盘度高）；≥5%=4分（资金积极介入）；" +
                ">0%=3分（资金小幅流入）；>-5%=1分（资金微幅流出）；≤-5%=0分（资金明显撤离）。" +
                "【注意】小盘股占比容易虚高（少量资金即可撬动），大盘股占比更真实反映机构态度。",
                false, pctColor));

        // 3. 融资余额变化（3分）— 从事件面移入
        BigDecimal marginChg = money != null ? money.getMarginChgPct() : null;
        double mcVal = marginChg != null ? marginChg.doubleValue() : 0;
        int mcScore = 0;
        if (marginChg != null) {
            if (mcVal > 5.0) mcScore = 3;
            else if (mcVal > 2.0) mcScore = 2;
            else if (mcVal > 0) mcScore = 1;
        }
        items.add(buildItem("融资余额变化", marginChg != null ? marginChg.setScale(2, RoundingMode.HALF_UP) + "%" : "-",
                mcScore, 3,
                "【指标定义】融资余额日环比变化率 = (最新融资余额 - 前一交易日融资余额) / 前一交易日融资余额 × 100%。" +
                "融资余额 = 投资者向券商借钱买入股票后尚未偿还的余额，是杠杆资金的代理指标。" +
                "【为什么用它】融资客是典型的趋势追随者——市场上涨时加杠杆追涨，下跌时被迫平仓杀跌。" +
                "融资余额增加 = 杠杆资金看多情绪升温；减少 = 杠杆资金撤退或被动平仓。" +
                "它是市场情绪的'放大器'：融资增加会助推上涨，融资减少会加速下跌。" +
                "【评分逻辑】增长>5%=3分（杠杆资金大幅加码，情绪极度乐观）；>2%=2分（杠杆资金积极做多）；" +
                ">0%=1分（杠杆资金小幅加仓）；≤0%=0分（杠杆资金撤退或观望）。" +
                "【风险警示】融资余额过高时，一旦股价回调可能触发连环平仓（踩踏），是双刃剑。",
                false, mcVal > 0 ? "red" : mcVal < 0 ? "green" : "default"));

        // 4. 5日累计主力净流入（3分）— 新增
        BigDecimal nm5d = money != null ? money.getNetMain5d() : null;
        double nm5dVal = nm5d != null ? nm5d.doubleValue() : 0;
        int nm5dScore = 0;
        if (nm5d != null) {
            if (nm5dVal >= 3e8) nm5dScore = 3;
            else if (nm5dVal >= 1e8) nm5dScore = 2;
            else if (nm5dVal > 0) nm5dScore = 1;
        }
        String nm5dColor = nm5dVal > 0 ? "red" : nm5dVal < 0 ? "green" : "default";
        items.add(buildItem("5日累计净流入", nm5d != null ? formatMoneyFlow(nm5d) : "-",
                nm5dScore, 3,
                "【指标定义】近5个交易日主力净流入的累计值，反映大资金近一周的持续态度。" +
                "【为什么用它】单日净流入易受消息脉冲影响（如突发利好导致一日游），5日累计能过滤单日噪音，" +
                "识别主力真实的持续意图。连续5天净流入 = 主力在系统性建仓；连续5天净流出 = 主力在系统性出货。" +
                "它回答'大资金是短期扰动还是持续布局'。" +
                "【评分逻辑】累计≥3亿=3分（主力持续大力做多）；≥1亿=2分（主力持续流入）；>0=1分（主力小幅净流入）；" +
                "≤0=0分（主力净流出或无明显动作）。" +
                "【注意】结合当日净流入看：当日正+5日正 = 趋势确认；当日正+5日负 = 诱多反弹；" +
                "当日负+5日正 = 短期洗盘；当日负+5日负 = 出货确认。",
                false, nm5dColor));

        // 5. 股东人数变化（2分，负值=筹码集中）— 新增
        BigDecimal holderChg = money != null ? money.getShareholderChangePct() : null;
        double hcVal = holderChg != null ? holderChg.doubleValue() : 0;
        int hcScore = 0;
        if (holderChg != null) {
            if (hcVal < -10.0) hcScore = 2;
            else if (hcVal < -5.0) hcScore = 1;
        }
        String hcDisplay = holderChg != null ? holderChg.setScale(2, RoundingMode.HALF_UP) + "%" : "-";
        String hcColor = hcScore == 2 ? "red" : hcScore == 1 ? "volcano" : hcVal > 0 ? "green" : "default";
        items.add(buildItem("股东人数变化", hcDisplay,
                hcScore, 2,
                "【指标定义】股东人数变化率 = (最新季度股东人数 - 上季度股东人数) / 上季度股东人数 × 100%。" +
                "负值 = 股东人数减少（筹码从散户向大户/机构集中）；正值 = 股东人数增加（筹码分散）。" +
                "【为什么用它】股东人数是A股特有的筹码分布代理指标——散户割肉离场、机构悄悄吸筹时，" +
                "股东人数会持续下降（筹码集中），这是主力建仓期的典型特征。" +
                "它直接回答'谁在持有这只股票'的问题：人少=主力控盘度高，人多=散户扎堆。" +
                "【评分逻辑】减少>10%=2分（筹码高度集中，主力控盘）；减少>5%=1分（筹码趋于集中）；" +
                "增加或微降=0分（筹码分散或无明显变化）。" +
                "【注意】该指标季度更新，滞后性强，适合中长期判断而非短线交易。",
                false, hcColor));

        // 6. 量比（2分）
        BigDecimal vr = money != null ? money.getVolumeRatio() : null;
        double vrVal = vr != null ? vr.doubleValue() : 0;
        int vrScore = 0;
        if (vr != null) {
            if (vrVal >= VOLUME_RATIO_HIGH) vrScore = 2;
            else if (vrVal >= VOLUME_RATIO_MEDIUM) vrScore = 1;
        }
        String vrColor = vrVal >= 2.0 ? "red" : vrVal >= 1.5 ? "volcano" : vr != null ? "green" : "default";
        items.add(buildItem("量比", vr != null ? vr.setScale(2, RoundingMode.HALF_UP).toString() : "-",
                vrScore, 2,
                "【指标定义】量比 = 当日成交量 / 近5日平均成交量，衡量当日成交相对于近期平均水平的活跃程度。" +
                "基准值1.0表示与近期平均水平持平；>1表示放量，<1表示缩量。" +
                "【为什么用它】量比是识别资金异动最灵敏的指标——主力进场必然伴随成交量异常放大，" +
                "而量比能在当日盘中就捕捉到这种变化（不同于换手率需要收盘后对比历史）。" +
                "它过滤了个股流通盘大小的影响：大盘股1.5倍放量与小盘股1.5倍放量意义相同。" +
                "【评分逻辑】量比≥2.0=2分（显著放量，资金积极介入）；≥1.5=1分（温和放量，有增量资金）；" +
                "<1.5=0分（量能正常或缩量）。" +
                "【实战要点】放量上涨=真金白银做多（可信）；放量下跌=主力出货（危险）；" +
                "缩量上涨=虚涨无量（不可持续）；缩量下跌=阴跌延续或地量见底（需结合位置判断）。",
                false, vrColor));

        // 7. 内外盘比（2分，参考指标，不做主要评分依据）
        BigDecimal oir = money != null ? money.getOuterInnerRatio() : null;
        double oirVal = oir != null ? oir.doubleValue() : 0;
        int oirScore = 0;
        String oirTrend = null;
        if (oir != null) {
            if (oirVal > 1.2) oirScore = 2;
            else if (oirVal > 1.0) oirScore = 1;
            if (oirVal > 1.2) oirTrend = "强势买方";
            else if (oirVal > 1.0) oirTrend = "买方略强";
            else if (oirVal >= 0.85) oirTrend = "多空均衡";
            else if (oirVal >= 0.7) oirTrend = "卖方略强";
            else if (oirVal > 0) oirTrend = "强势卖方";
            else oirTrend = "无数据";
        } else {
            oirTrend = "无数据";
        }
        String oirColor = oirScore == 2 ? "red" : oirScore == 1 ? "volcano" : oirVal < 1.0 && oir != null ? "green" : "default";
        items.add(buildItem("内外盘比", oir != null
                ? oir.setScale(3, RoundingMode.HALF_UP).toString() + " " + oirTrend
                : "无数据",
                oirScore, 2,
                "【指标定义】内外盘比 = 外盘成交量 / 内盘成交量。外盘 = 主动买入成交（以卖一价或更高价成交）；" +
                "内盘 = 主动卖出成交（以买一价或更低价成交）。比值>1表示买方更积极，<1表示卖方更积极。" +
                "【为什么用它替代委比】委比 = (委买手数 - 委卖手数) / (委买手数 + 委卖手数)，极易被主力在盘口挂大单后撤单操纵。" +
                "而内外盘是真实成交数据，无法造假——买方真的掏钱买入、卖方真的割肉卖出才会计入。" +
                "【评分逻辑】比值>1.2=2分（买方强势，主动买入明显多于卖出）；>1.0=1分（买方略强）；" +
                "≤1.0=0分（卖方占优或均衡）。" +
                "【注意】涨停板时外盘极小（没人卖），比值失真；跌停板时内盘极小（没人买），比值也失真。" +
                "需结合股价位置判断：低位外盘大 = 吸筹；高位外盘大 = 诱多。",
                false, oirColor));

        // 8. 换手率偏离（3分）
        BigDecimal dev = money != null ? money.getTurnoverDeviation() : null;
        double devVal = dev != null ? dev.doubleValue() : 0;
        int devScore = 0;
        if (dev != null) {
            if (devVal > 0) devScore = 3;
            else if (devVal > -2) devScore = 2;
            else devScore = 1;
        }
        String devColor = devVal > 3.0 ? "red" : devVal > 0 ? "volcano" : dev != null ? "green" : "default";
        items.add(buildItem("换手率偏离", dev != null ? dev.setScale(2, RoundingMode.HALF_UP) + "%" : "-",
                devScore, 3,
                "【指标定义】换手率偏离 = 当日换手率 - 近20日平均换手率，衡量当日筹码换手活跃程度相对于中期常态的偏离。" +
                "正值 = 当日交投比近期更活跃（有资金异动）；负值 = 当日交投比近期更清淡（筹码锁定或无人问津）。" +
                "【为什么用它而非绝对换手率】不同股票的常态换手率天差地别：银行股日常0.3%就算高换手，" +
                "而科技股日常5%才算正常。直接用绝对换手率评分对大盘股不公平。偏离值消除了个股基差，" +
                "只衡量'今天比平常活跃了多少'，实现跨市值可比。" +
                "【评分逻辑】偏离>0%=3分（活跃度提升，资金异动）；>-2%=2分（接近正常，略有降温）；" +
                "≤-2%=1分（明显缩量，筹码锁定或无人关注）。" +
                "【实战要点】正偏离+股价上涨 = 资金进场确认；正偏离+股价下跌 = 主力出货；" +
                "负偏离+股价上涨 = 无量上涨（虚涨）；负偏离+股价下跌 = 缩量阴跌（恐慌盘未出尽或地量见底）。",
                false, devColor));

        // === 参考指标（不参与评分） ===
        BigDecimal netHuge = money != null ? money.getNetHuge() : null;
        double hugeVal = netHuge != null ? netHuge.doubleValue() : 0;
        items.add(buildItem("超大单净流入", netHuge != null ? formatMoneyFlow(netHuge) : "-", 0, 0,
                "超大单（>100万元）当日净流入额，反映机构资金动向", true, hugeVal > 0 ? "red" : hugeVal < 0 ? "green" : "default"));

        BigDecimal netBig = money != null ? money.getNetBig() : null;
        double bigVal = netBig != null ? netBig.doubleValue() : 0;
        items.add(buildItem("大单净流入", netBig != null ? formatMoneyFlow(netBig) : "-", 0, 0,
                "大单（20~100万元）当日净流入额，反映大户资金动向", true, bigVal > 0 ? "red" : bigVal < 0 ? "green" : "default"));

        String flowStatus = money != null ? money.getMainFlowStatus() : null;
        String flowDisplay = "INFLOW".equals(flowStatus) ? "主力流入"
                : "OUTFLOW".equals(flowStatus) ? "主力流出" : "暂无数据";
        String flowColor = "INFLOW".equals(flowStatus) ? "red" : "OUTFLOW".equals(flowStatus) ? "green" : "default";
        items.add(buildItem("主力资金状态", flowDisplay, 0, 0,
                "综合主力净流入方向判断。流入=大资金积极介入，流出=大资金撤离", true, flowColor));

        BigDecimal turnoverRate = money != null ? money.getTurnoverRate() : null;
        items.add(buildItem("当日换手率", turnoverRate != null ? turnoverRate.setScale(2, RoundingMode.HALF_UP) + "%" : "-", 0, 0,
                "当日成交量/流通股本。高换手=交投活跃，低换手=交易清淡", true, "default"));

        String volumeStatus = money != null ? money.getVolumeStatus() : null;
        String vsDisplay = "HIGH".equals(volumeStatus) ? "放量"
                : "MEDIUM".equals(volumeStatus) ? "温和放量"
                : "LOW".equals(volumeStatus) ? "缩量" : "-";
        String vsColor = "HIGH".equals(volumeStatus) ? "red" : "MEDIUM".equals(volumeStatus) ? "volcano" : "LOW".equals(volumeStatus) ? "green" : "default";
        items.add(buildItem("量能状态", vsDisplay, 0, 0,
                "综合量比和换手率的量能判断。放量=资金积极介入，缩量=观望情绪浓厚", true, vsColor));

        return items;
    }

    
    private List<ScoreDetail.ScoreItem> buildSentimentItems(SentimentSignal sentiment) {
        List<ScoreDetail.ScoreItem> items = new ArrayList<>();

        // 1. 连续涨停（4分）
        Integer days = sentiment != null ? sentiment.getLimitUpDays() : null;
        int daysScore = 0;
        if (days != null) {
            if (days >= 3) daysScore = 4;
            else if (days >= 2) daysScore = 3;
            else if (days > 0) daysScore = 2;
        }
        String daysColor = days != null && days >= 3 ? "red" : days != null && days >= 2 ? "volcano" : days != null && days >= 1 ? "blue" : "default";
        items.add(buildItem("连续涨停", days != null ? days + "天" : "-",
                daysScore, 4,
                "【选用原因】涨停是A股最强做多信号，连续涨停代表市场情绪极度亢奋，是龙头股的核心特征。" +
                "【价值】连板天数直接反映短期爆发力和资金接力意愿；连板越高代表资金接力越强，但同时也意味着分歧风险越大——给分但不过度奖励。",
                false, daysColor));

        // 2. 炸板率（3分）
        Double rate = sentiment != null ? sentiment.getBrokenLimitUpRate() : null;
        int rateScore = 0;
        if (rate != null) {
            if (rate < 10.0) rateScore = 3;
            else if (rate < 30.0) rateScore = 2;
            else if (rate < 50.0) rateScore = 1;
        }
        items.add(buildItem("炸板率", rate != null ? String.format("%.1f%%", rate) : "-",
                rateScore, 3,
                "【选用原因】炸板率衡量涨停封板力度，炸板率高说明抛压重、封板不坚决，是\"假强势\"的识别器。" +
                "【价值】配合连续涨停使用——涨停次数多+炸板率低=真强势；涨停多+炸板率高=资金博弈激烈，次日低开概率大。此指标有效过滤弱势涨停，避免被\"虚假繁荣\"误导。",
                false, "default"));

        // 3. 强势股（3分）
        boolean isStrong = sentiment != null && Boolean.TRUE.equals(sentiment.getIsStrongStock());
        items.add(buildItem("强势股", isStrong ? "是" : "否",
                isStrong ? 3 : 0, 3,
                "【选用原因】20日涨幅>30%是中期趋势跟踪的核心阈值，代表资金在中期维度持续做多。" +
                "【价值】与连续涨停互补——涨停看短期爆发力，强势股看中期动能。趋势一旦形成延续概率大，但波动也会加大，适合趋势跟踪而非逆势抄底。",
                false, isStrong ? "red" : "default"));

        // 4. 龙虎榜信号（3分）
        Integer lhbCount = sentiment != null ? sentiment.getLhbAppearCount() : null;
        BigDecimal lhbNet = sentiment != null ? sentiment.getLhbNetAmount() : null;
        int lhbScore = 0;
        String lhbDisplay = "-";
        String lhbColor = "default";
        if (lhbCount != null && lhbCount > 0) {
            if (lhbNet != null && lhbNet.doubleValue() > 0) {
                lhbScore = 3;
                lhbDisplay = "上榜" + lhbCount + "次,净买入" + formatMoneyFlow(lhbNet);
                lhbColor = "red";
            } else {
                lhbScore = 1;
                lhbDisplay = "上榜" + lhbCount + "次,净卖出";
                lhbColor = "volcano";
            }
        } else {
            lhbDisplay = "未上榜";
        }
        items.add(buildItem("龙虎榜", lhbDisplay,
                lhbScore, 3,
                "【选用原因】龙虎榜是交易所公开的大资金交易席位数据，机构席位净买入代表专业机构的真实交易行为，是市场最透明的资金信号之一。" +
                "【价值】机构席位净买入=专业资金真金白银看多，可信度高于散户资金；净卖出=机构减仓离场，是中期看淡警示。龙虎榜上榜次日继续上涨概率较大，但需区分机构席位vs游资席位。",
                false, lhbColor));

        // 5. 机构调研热度（6分）— 近90天研报数量，新增
        Integer rrCount = sentiment != null ? sentiment.getResearchReportCount90d() : null;
        int rrScore = 0;
        if (rrCount != null) {
            if (rrCount >= 10) rrScore = 6;
            else if (rrCount >= 5) rrScore = 4;
            else if (rrCount >= 2) rrScore = 2;
            else if (rrCount >= 1) rrScore = 1;
        }
        String rrColor = rrScore >= 4 ? "red" : rrScore >= 1 ? "volcano" : "default";
        items.add(buildItem("机构调研热度", rrCount != null ? rrCount + "篇/90天" : "-",
                rrScore, 6,
                "【选用原因】研报数量代表机构分析师的关注频次，是机构覆盖度的量化指标。机构密集调研往往对应业绩拐点或重大业务变化，是基本面研究的\"先行指标\"。" +
                "【价值】事件面权重最高的单项（6分），≥10篇/90天给满分。研报覆盖=机构正式关注，与基金持仓互补——调研是\"正在研究\"，持仓是\"已经买入\"。无研报覆盖的股票往往是被市场忽略的冷门股。",
                false, rrColor));

        // 6. 基金持仓集中度（4分）— float_ratio合计，新增
        BigDecimal fhr = sentiment != null ? sentiment.getFundHolderRatio() : null;
        double fhrVal = fhr != null ? fhr.doubleValue() : 0;
        int fhrScore = 0;
        if (fhr != null) {
            if (fhrVal >= 20.0) fhrScore = 4;
            else if (fhrVal >= 10.0) fhrScore = 3;
            else if (fhrVal >= 5.0) fhrScore = 2;
            else if (fhrVal > 0) fhrScore = 1;
        }
        String fhrDisplay = fhr != null ? String.format("%.2f%%", fhrVal) : "-";
        String fhrColor = fhrScore >= 3 ? "red" : fhrScore >= 1 ? "volcano" : fhrVal == 0 ? "default" : "default";
        items.add(buildItem("基金持仓集中度", fhrDisplay,
                fhrScore, 4,
                "【选用原因】基金持仓占流通股比例反映机构资金的\"长期配置意愿\"，季报披露的基金持仓数据比实时资金流向更稳定、更具参考价值。" +
                "【价值】≥20%=机构重仓（4分），>0%=有基金配置（1分）。与机构调研互补——调研是\"关注\"，持仓是\"真金白银买入\"。高基金持仓意味着大量筹码被机构锁定，抛压相对较小，股价稳定性更高。",
                false, fhrColor));

        // 7. 公告事件（2分）— 关键词已扩展为含投资/扩产
        int posCount = sentiment != null && sentiment.getNoticePositiveCount() != null ? sentiment.getNoticePositiveCount() : 0;
        int negCount = sentiment != null && sentiment.getNoticeNegativeCount() != null ? sentiment.getNoticeNegativeCount() : 0;
        int eventNet = posCount - negCount;
        int eventScore = 0;
        String eventDisplay = "正面" + posCount + "/负面" + negCount;
        if (eventNet >= 3) eventScore = 2;
        else if (eventNet >= 1) eventScore = 1;
        items.add(buildItem("公告事件", eventDisplay,
                eventScore, 2,
                "【选用原因】公告是上市公司最正式的信息披露形式，回购/增持/业绩预增等是直接利好信号；减持/定增/业绩预降等是直接风险信号。" +
                "【价值】权重较低（2分）是因为公告信息往往已被市场提前反映。但净正面公告密集出现=基本面有持续催化，是中长期持有的加分项。含投资/扩产/产能扩张等关键词作为正面补充。",
                false, eventNet >= 1 ? "red" : eventNet < 0 ? "green" : "default"));

        // 8. 新闻事件（2分）— 东方财富个股新闻（利好/风险/情感偏向）
        Integer ns = sentiment != null ? sentiment.getNewsScore() : null;
        Integer nPos = sentiment != null ? sentiment.getNewsPositive30d() : null;
        Integer nNeg = sentiment != null ? sentiment.getNewsNegative30d() : null;
        Double nBias = sentiment != null ? sentiment.getNewsSentimentBias() : null;
        int nsScore = 0;
        String nsDisplay = "-";
        String nsColor = "default";
        if (ns != null) {
            nsScore = ns >= 8 ? 2 : ns >= 5 ? 1 : 0;
            int pos = nPos != null ? nPos : 0;
            int neg = nNeg != null ? nNeg : 0;
            String biasStr;
            if (nBias != null) {
                int biasPct = (int) Math.round(nBias * 100);
                biasStr = (biasPct > 0 ? "+" : "") + biasPct + "%";
            } else {
                biasStr = "-";
            }
            nsDisplay = pos + "利好/" + neg + "风险 | " + biasStr;
            nsColor = ns >= 8 ? "red" : ns >= 5 ? "volcano" : ns == 0 && neg > 0 ? "green" : "default";
        }
        items.add(buildItem("新闻事件", nsDisplay,
                nsScore, 2,
                "【选用原因】东方财富个股新闻覆盖市场舆论，可捕捉非公告信息的公开信息来源。利好/风险分类+情感偏向+事件标签（业绩/扩产/政策等）是对公告信息的补充验证。" +
                "【价值】权重最低（2分）是因为新闻噪音大、易被操纵，更多作为辅助验证而非主要信号。新闻面评分≥8分代表近期利好舆论密集；情感偏向>+50%代表市场情绪明显偏向乐观。与\"新闻事件\"Tab互补，Tab展示详情，此处提供评分。",
                false, nsColor));

        return items;
    }

    /**
     * 大盘蓝筹事件面评分明细
     * 机构净买入(4) + 机构调研(6) + 基金持仓(4) + 龙虎榜上榜(3) + 公告事件(2) + 融资余额已移至资金面
     */
    private List<ScoreDetail.ScoreItem> buildSentimentItemsBlueChip(SentimentSignal sentiment) {
        List<ScoreDetail.ScoreItem> items = new ArrayList<>();

        // 1. 龙虎榜机构净买入（4分）
        BigDecimal lhb = sentiment != null ? sentiment.getLhbInstitutionNet() : null;
        double lhbVal = lhb != null ? lhb.doubleValue() : 0;
        int lhbScore = 0;
        if (lhb != null) {
            if (lhbVal > 50e6) lhbScore = 4;
            else if (lhbVal > 10e6) lhbScore = 3;
            else if (lhbVal > 0) lhbScore = 2;
            else if (lhbVal > -10e6) lhbScore = 1;
        }
        String lhbDisplay = lhb != null ? formatMoneyFlow(lhb) : "-";
        items.add(buildItem("龙虎榜机构净买入", lhbDisplay,
                lhbScore, 4,
                "【选用原因】机构席位龙虎榜净买入额是大资金真实交易的直接证据，比散户资金更可信。金额门槛（5000万/1000万/0）对应不同级别的机构认可度。" +
                "【价值】4分给净买入>5000万，代表机构高度认可；净买入>0万给2分（哪怕是小额净买入）。大盘蓝筹盘子大，机构净买入金额本身就有说服力。",
                false,
                lhbVal > 0 ? "red" : lhbVal < 0 ? "green" : "default"));

        // 2. 机构调研热度（6分）— 近90天研报数量（替代旧holderChangePct）
        Integer rrCount = sentiment != null ? sentiment.getResearchReportCount90d() : null;
        int rrScore = 0;
        if (rrCount != null) {
            if (rrCount >= 10) rrScore = 6;
            else if (rrCount >= 5) rrScore = 4;
            else if (rrCount >= 2) rrScore = 2;
            else if (rrCount >= 1) rrScore = 1;
        }
        String rrColor = rrScore >= 4 ? "red" : rrScore >= 1 ? "volcano" : "default";
        items.add(buildItem("机构调研热度", rrCount != null ? rrCount + "篇/90天" : "-",
                rrScore, 6,
                "【选用原因】研报数量代表机构分析师的关注频次，是机构覆盖度的量化指标。机构密集调研往往对应业绩拐点或重大业务变化，是基本面研究的\"先行指标\"。" +
                "【价值】事件面权重最高的单项（6分），≥10篇/90天给满分。调研是\"正在研究\"，持仓是\"已经买入\"。大盘蓝筹有研报覆盖是标配，无覆盖反而说明机构关注度低于市场预期。",
                false, rrColor));

        // 3. 基金持仓集中度（4分）— float_ratio合计，新增
        BigDecimal fhr = sentiment != null ? sentiment.getFundHolderRatio() : null;
        double fhrVal = fhr != null ? fhr.doubleValue() : 0;
        int fhrScore = 0;
        if (fhr != null) {
            if (fhrVal >= 20.0) fhrScore = 4;
            else if (fhrVal >= 10.0) fhrScore = 3;
            else if (fhrVal >= 5.0) fhrScore = 2;
            else if (fhrVal > 0) fhrScore = 1;
        }
        String fhrDisplay = fhr != null ? String.format("%.2f%%", fhrVal) : "-";
        String fhrColor = fhrScore >= 3 ? "red" : fhrScore >= 1 ? "volcano" : "default";
        items.add(buildItem("基金持仓集中度", fhrDisplay,
                fhrScore, 4,
                "【选用原因】基金持仓占流通股比例反映机构资金的\"长期配置意愿\"，季报披露的基金持仓数据比实时资金流向更稳定。" +
                "【价值】≥20%=机构重仓（4分），>0%=有基金配置（1分）。高基金持仓意味着大量筹码被机构锁定，抛压小，股价稳定性高。大盘蓝筹的基金持仓比例也是机构抱团程度的体现。",
                false, fhrColor));

        // 4. 龙虎榜上榜（3分）
        Integer lhbCount = sentiment != null ? sentiment.getLhbAppearCount() : null;
        BigDecimal lhbNet = sentiment != null ? sentiment.getLhbNetAmount() : null;
        int lhbAppearScore = 0;
        String lhbAppearDisplay = "-";
        String lhbAppearColor = "default";
        if (lhbCount != null && lhbCount > 0) {
            if (lhbNet != null && lhbNet.doubleValue() > 0) {
                lhbAppearScore = 3;
                lhbAppearDisplay = "上榜" + lhbCount + "次,净买入";
                lhbAppearColor = "red";
            } else {
                lhbAppearScore = 1;
                lhbAppearDisplay = "上榜" + lhbCount + "次,净卖出";
                lhbAppearColor = "volcano";
            }
        } else {
            lhbAppearDisplay = "未上榜";
        }
        items.add(buildItem("龙虎榜上榜", lhbAppearDisplay,
                lhbAppearScore, 3,
                "【选用原因】龙虎榜是交易所公开的大资金交易席位数据，上榜次数代表个股被大资金关注的频率，净买入方向代表机构vs游资的博弈结果。" +
                "【价值】与\"龙虎榜机构净买入\"指标互补——前者看绝对金额，此处看上榜频率和整体净买卖方向。多次上榜+净买入=大资金持续关注，是中长期看好信号。",
                false, lhbAppearColor));

        // 5. 公告事件（2分）— 关键词已扩展
        int posCount = sentiment != null && sentiment.getNoticePositiveCount() != null ? sentiment.getNoticePositiveCount() : 0;
        int negCount = sentiment != null && sentiment.getNoticeNegativeCount() != null ? sentiment.getNoticeNegativeCount() : 0;
        int eventNet = posCount - negCount;
        int eventScore = 0;
        String eventDisplay = "正面" + posCount + "/负面" + negCount;
        if (eventNet >= 3) eventScore = 2;
        else if (eventNet >= 1) eventScore = 1;
        items.add(buildItem("公告事件", eventDisplay,
                eventScore, 2,
                "【选用原因】公告是上市公司最正式的信息披露，回购/增持/业绩预增等是直接利好信号；减持/定增/业绩预降等是直接风险信号。" +
                "【价值】权重较低（2分）是因为公告信息往往已被市场提前反映。净正面公告密集出现=基本面有持续催化。大盘蓝筹的公告往往涉及重大资产重组或股权激励，是中期重要催化剂。",
                false,
                eventNet >= 1 ? "red" : eventNet < 0 ? "green" : "default"));

        // 融资余额变化已移至资金面评分，此处不再展示

        return items;
    }

    private List<ScoreDetail.ScoreItem> buildFundamentalItems(FundamentalSignal fundamental) {
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

    private String ratingDesc(int score) {
        if (score >= 5) return "买入";
        if (score >= 3) return "增持";
        if (score >= 1) return "中性";
        return "减持/卖出";
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
