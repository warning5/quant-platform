package com.quant.platform.stock.analysis.engine;

import com.quant.platform.stock.analysis.domain.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static com.quant.platform.stock.analysis.engine.SignalItemFactory.*;
import static com.quant.platform.stock.analysis.engine.SignalScoreConstants.*;

/**
 * 事件面打分器（满分 25）
 * 涨停/龙虎榜/融资余额/研报覆盖的评分与明细项构建，含大盘蓝筹专用口径。
 */
@Slf4j
@Component
public class SignalSentimentScorer {

    /**
     * 计算事件面得分——大盘蓝筹模式（满分25）
     * 指标：龙虎榜机构净买入(4分) + 机构调研热度(6分) + 基金持仓(4分)
     *   + 龙虎榜上榜(3分) + 公告事件(2分) + 融资余额已移至资金面
     */
    public int calcSentimentScoreBlueChip(SentimentSignal sentiment) {
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
    public int calcSentimentScore(SentimentSignal sentiment) {
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

    public List<ScoreDetail.ScoreItem> buildSentimentItems(SentimentSignal sentiment) {
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
    public List<ScoreDetail.ScoreItem> buildSentimentItemsBlueChip(SentimentSignal sentiment) {
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
}
