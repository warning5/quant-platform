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
 * 资金面打分器（满分 25）
 * 主力净流入（绝对额/占比）、融资余额、股东人数、量比、换手率偏离、内外盘比的评分与明细项构建。
 *
 * <p>⚠️ 各子项理论满分之和必须等于 MONEY_WEIGHT(25)，否则末尾的 Math.min 会静默截掉超出部分，
 * 使高分样本失去区分度。当前配置：5+5+3+3+2+2+3+2 = 25。
 */
@Slf4j
@Component
public class SignalMoneyScorer {

    /** 主力净流入满分。由 6 下调为 5 以配平总分到 25（该档满分率仅 0.2%，对实际打分影响≈0） */
    private static final int NET_MAIN_MAX = 5;

    /**
     * 计算资金面得分（满分25）
     * 权重：主力净流入(5分) + 主力净流入占比(5分) + 融资余额变化(3分)
     *   + 5日累计主力净流入(3分) + 股东人数变化(2分) + 量比(2分) + 换手率偏离(3分) + 内外盘比(2分)
     */
    public int calcMoneyScore(MoneyFlowSignal money) {
        if (money == null) return 0;
        int score = 0;

        // 1. 主力净流入（5分）
        if (money.getNetMain() != null) {
            double nm = money.getNetMain().doubleValue();
            if (nm >= NET_MAIN_HIGH) {
                score += NET_MAIN_MAX;
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

    public List<ScoreDetail.ScoreItem> buildMoneyItems(MoneyFlowSignal money) {
        List<ScoreDetail.ScoreItem> items = new ArrayList<>();

        // 1. 主力净流入（5分）
        BigDecimal nm = money != null ? money.getNetMain() : null;
        double nmVal = nm != null ? nm.doubleValue() : 0;
        int nmScore = 0;
        if (nm != null) {
            if (nmVal >= NET_MAIN_HIGH) nmScore = NET_MAIN_MAX;
            else if (nmVal >= NET_MAIN_MED) nmScore = 4;
            else if (nmVal > 0) nmScore = 3;
            else if (nmVal > NET_MAIN_LOW) nmScore = 1;
        }
        String nmColor = nmVal > 0 ? "red" : nmVal < 0 ? "green" : "default";
        items.add(buildItem("主力净流入", nm != null ? formatMoneyFlow(nm) : "暂无数据",
                nmScore, NET_MAIN_MAX,
                "【指标定义】主力净流入 = 大单(20~100万) + 超大单(>100万)当日净流入额，反映大资金当日净买卖方向。" +
                "正值 = 大资金净买入（做多）；负值 = 大资金净卖出（做空）。" +
                "【为什么用它】主力净流入是判断大资金态度最直接的指标——它回答'今天谁在买、谁在卖'。" +
                "散户交易呈现随机分布，而主力操作会集中体现在大单/超大单上。该指标源自东财真实逐笔成交数据，" +
                "经席位分类统计得出，比传统技术指标更能反映真实资金流向。" +
                "【评分逻辑】净流入≥5亿=5分（机构级别强力做多）；≥1亿=4分（大资金积极介入）；" +
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
}
