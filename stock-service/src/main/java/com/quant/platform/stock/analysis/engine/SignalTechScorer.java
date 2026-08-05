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
 * 技术面打分器（满分 50）
 * 趋势/MACD/RSI/BOLL/DMI/量比/近高近低 等指标的评分与明细项构建。
 */
@Slf4j
@Component
public class SignalTechScorer {

    /**
     * 计算技术面得分（满分50）
     * 指标：趋势状态(10) + MACD综合(10) + RSI14(6) + BOLL轨道(6) + DMI强度(3) + 量比(5) + 近高近低(8) + BOLL带宽(2) = 50
     * 惩罚项（扣分）：量价背离(高位)/均线背离/均线空头/KDJ死叉/DMI空头/SAR翻空
     */
    public int calcTechScore(TechSignal tech) {
        if (tech == null) return 0;

        int score = 0;
        int penalty = 0;
        BigDecimal rsi = tech.getRsi();
        double rsiVal = rsi != null ? rsi.doubleValue() : 50;

        // 1. 趋势状态（10分）
        if ("BULLISH".equals(tech.getTrend())) {
            score += 10;
        } else if ("SIDEWAYS".equals(tech.getTrend())) {
            score += 4;
        }

        // 2. MACD综合（10分）— 金叉/零轴/动能 三合一
        BigDecimal hist = tech.getMacdHistogram();
        BigDecimal histPrev = tech.getMacdHistogramPrev();
        boolean macdGolden = Boolean.TRUE.equals(tech.getMacdGolden());
        boolean macdAboveZero = Boolean.TRUE.equals(tech.getMacdAboveZero());
        boolean macdDead = Boolean.TRUE.equals(tech.getMacdDeadCross());
        if (hist != null && histPrev != null) {
            double h = hist.doubleValue();
            double hp = histPrev.doubleValue();
            if (macdGolden) {
                // 金叉 + 零轴位置 + 动能方向（满分10分）
                int base = macdAboveZero ? 7 : 3;
                if (h > 0 && hp > 0) {
                    if (h >= hp) {
                        score += base + 2;  // 零轴上金叉+红柱扩张
                    } else {
                        score += base + 1;  // 零轴上金叉+红柱缩
                    }
                } else if (h > 0 && hp <= 0) {
                    score += base;  // 刚转红
                } else {
                    score += base - 1;  // 零轴下金叉，弱反弹
                }
            } else if (macdDead) {
                score -= macdAboveZero ? 3 : 1;  // 零轴上死叉更危险
            } else {
                // 无交叉：看动能
                if (h > 0 && hp > 0) {
                    score += h >= hp ? 3 : 1;
                } else if (h < 0 && hp < 0) {
                    score += 1;
                }
            }
        }

        // 4. RSI14（6分）
        if (rsi != null) {
            if (rsiVal < 30) {
                score += 6;  // 超卖，反弹机会
            } else if (rsiVal < 50) {
                score += 4;  // 偏弱
            } else if (rsiVal <= 70) {
                score += 2;  // 正常
            } else {
                score += 1;  // 超买
            }
        }

        // 5. BOLL轨道（6分）— 加RSI二次过滤
        BigDecimal bollPos = tech.getBollPosition();
        if (bollPos != null) {
            double pos = bollPos.doubleValue();
            if (pos > 1.0) {
                score += (rsiVal <= 70) ? 6 : 2;  // 突破上轨
            } else if (pos >= 0.8) {
                score += (rsiVal <= 70) ? 4 : 2;  // 上轨附近
            } else if (pos >= 0.5) {
                score += 3;  // 中上
            } else if (pos >= 0.2) {
                score += 2;  // 中下
            } else {
                score += (rsiVal < 30) ? 2 : 0;  // 下轨附近，超卖给分
            }
        }

        // 6. DMI趋势强度（3分）— 只看 ADX，不看方向（方向由趋势状态覆盖）
        BigDecimal dmiAdx = tech.getDmiAdx();
        if (dmiAdx != null) {
            double adxVal = dmiAdx.doubleValue();
            if (adxVal > 30) {
                score += 3;  // 强趋势
            } else if (adxVal > 20) {
                score += 1;  // 弱趋势
            }
        }

        // 7. 量比（5分）
        BigDecimal volRatio = tech.getVolumeRatio();
        if (volRatio != null) {
            double vr = volRatio.doubleValue();
            if (vr >= 2.0) {
                score += 5;
            } else if (vr >= 1.5) {
                score += 3;
            } else if (vr >= 1.0) {
                score += 2;
            } else if (vr < 0.5) {
                penalty += 1;  // 极度缩量
            }
        }

        // 8. 近高近低（8分）
        BigDecimal nearHighPct = tech.getNearHighPct();
        BigDecimal nearLowPct = tech.getNearLowPct();
        if (nearLowPct != null) {
            double lowPct = nearLowPct.doubleValue();
            if (lowPct < 3.0) {
                score += 6;
            } else if (lowPct < 10.0) {
                score += 3;
            }
        }
        if (nearHighPct != null) {
            double highPct = nearHighPct.doubleValue();
            if (highPct < 3.0) {
                penalty += 3;  // 接近高点，阻力位
            } else if (highPct < 10.0) {
                penalty += 1;
            }
        }

        // 9. BOLL带宽（2分）
        BigDecimal bollBw = tech.getBollBandwidth();
        if (bollBw != null && bollBw.doubleValue() < 5.0) {
            score += 2;
        }

        // === 惩罚项（扣分） ===

        // 量价背离惩罚
        Boolean divergence = tech.getPriceVolumeDivergence();
        if (Boolean.TRUE.equals(divergence)) {
            String divType = tech.getDivergenceType();
            if ("HIGH_PRICE_MAIN_OUTFLOW".equals(divType)) {
                penalty += 6;
            } else if ("LOW_PRICE_MAIN_INFLOW".equals(divType)) {
                penalty -= 2;
            }
        }

        // 均线背离检测
        BigDecimal ret5d = tech.getRet5d();
        BigDecimal ret20d = tech.getRet20d();
        if (ret5d != null && ret20d != null) {
            double r5 = ret5d.doubleValue();
            double r20 = ret20d.doubleValue();
            if (r5 > 0.10 && r20 < 0.03) {
                penalty += 2;  // 短期反弹非趋势
            }
        }

        // 均线空头排列
        if (Boolean.TRUE.equals(tech.getMaBearish())) {
            penalty += 3;
        }

        // KDJ死叉
        if (Boolean.TRUE.equals(tech.getKdjDeadCross())) {
            penalty += 2;
        }

        // DMI空头
        BigDecimal dmiPlusDI = tech.getDmiPlusDI();
        BigDecimal dmiMinusDI = tech.getDmiMinusDI();
        if (dmiPlusDI != null && dmiMinusDI != null
                && dmiMinusDI.doubleValue() > dmiPlusDI.doubleValue()) {
            penalty += 1;
        }

        // SAR 翻多/翻空
        if (Boolean.TRUE.equals(tech.getSarTurnBullish())) {
            penalty -= 2;
        }
        if (Boolean.TRUE.equals(tech.getSarTurnBearish())) {
            penalty += 2;
        }

        score = Math.max(0, score + penalty);
        return Math.min(TECH_WEIGHT, score);
    }

    public List<ScoreDetail.ScoreItem> buildTechItems(TechSignal tech) {
        List<ScoreDetail.ScoreItem> items = new ArrayList<>();

        // 趋势状态（10分）
        String trend = tech != null ? tech.getTrend() : null;
        String trendDisplay = "BULLISH".equals(trend) ? "上涨" : "BEARISH".equals(trend) ? "下跌" : "SIDEWAYS".equals(trend) ? "盘整" : "-";
        int trendScore = "BULLISH".equals(trend) ? 10 : "SIDEWAYS".equals(trend) ? 4 : 0;
        String trendColor = "BULLISH".equals(trend) ? "red" : "BEARISH".equals(trend) ? "green" : "blue";
        items.add(buildItem("趋势状态", trendDisplay, trendScore, 10,
                "上涨=10分, 盘整=4分, 下跌=0分", false, trendColor));

        // 均线多头 → 合并到趋势状态，此处移除独立计分项
        // （保留均线空头作为参考项，见下方）

        // === MACD综合（10分）— 金叉 + 零轴位置 + 动能 三合一 ===
        boolean macdGolden = tech != null && Boolean.TRUE.equals(tech.getMacdGolden());
        boolean macdAboveZero = Boolean.TRUE.equals(tech != null ? tech.getMacdAboveZero() : null);
        Boolean macdDead = tech != null ? tech.getMacdDeadCross() : null;
        BigDecimal hist = tech != null ? tech.getMacdHistogram() : null;
        BigDecimal histPrev = tech != null ? tech.getMacdHistogramPrev() : null;
        String macdDisplay = "-";
        String macdColor = "default";
        int macdScore = 0;
        if (hist != null && histPrev != null) {
            double h = hist.doubleValue();
            double hp = histPrev.doubleValue();
            if (macdGolden) {
                int base = macdAboveZero ? 5 : 2;
                String histState = "";
                if (h > 0 && hp > 0) {
                    histState = h >= hp ? "红柱扩张" : "红柱缩";
                    macdScore = base + (h >= hp ? 2 : 1);
                    macdColor = h >= hp ? "red" : "volcano";
                } else if (h > 0 && hp <= 0) {
                    histState = "刚转红柱";
                    macdScore = base;
                    macdColor = "red";
                } else {
                    histState = "零轴下金叉";
                    macdScore = base - 1;
                    macdColor = "blue";
                }
                macdDisplay = (macdAboveZero ? "金叉(零上)" : "金叉(零下)") + " " + histState;
            } else if (Boolean.TRUE.equals(macdDead)) {
                macdDisplay = macdAboveZero ? "死叉(零上)" : "死叉(零下)";
                macdScore = -(macdAboveZero ? 2 : 1);
                macdColor = macdAboveZero ? "volcano" : "green";
            } else {
                if (h > 0 && hp > 0) {
                    macdDisplay = h >= hp ? "红柱扩张" : "红柱缩";
                    macdScore = h >= hp ? 4 : 2;
                    macdColor = h >= hp ? "red" : "volcano";
                } else if (h < 0 && hp < 0) {
                    macdDisplay = "绿柱收窄";
                    macdScore = 1;
                    macdColor = "blue";
                } else {
                    macdDisplay = "绿柱扩张";
                    macdScore = 0;
                    macdColor = "green";
                }
            }
        }
        items.add(buildItem("MACD综合", macdDisplay, macdScore, 10,
                "【三合一指标】MACD金叉+零轴位置+动能综合评分，满分8分。" +
                "零轴上金叉+红柱扩张=8分最强；零轴上金叉+红柱缩=6分；零轴上金叉=5分；" +
                "零轴下金叉=1分（弱反弹）；零轴上死叉=-2分；零轴下死叉=-1分；无交叉看动能。" +
                "【为什么合并】MACD金叉/动能/零轴三者信息高度重叠，单独计分导致同一信号被重复加权。", false, macdColor));

        // RSI14（5分）— 从参考项升级为计分项
        BigDecimal rsi = tech != null ? tech.getRsi() : null;
        double rsiVal = rsi != null ? rsi.doubleValue() : 0;
        int rsiScore = 0;
        if (rsi != null) {
            if (rsiVal < 30) rsiScore = 5;       // 超卖→反弹机会
            else if (rsiVal < 50) rsiScore = 3;   // 偏弱
            else if (rsiVal <= 70) rsiScore = 2;  // 正常
            else rsiScore = 1;                     // 超买
        }
        String rsiColor = rsiVal > 70 ? "red" : rsiVal < 30 ? "green" : "blue";
        items.add(buildItem("RSI14", rsi != null ? rsi.setScale(1, RoundingMode.HALF_UP).toString() : "-", rsiScore, 6,
                "<30超卖=6分, 30~50偏弱=4分, 50~70正常=2分, >70超买=1分。与KDJ/WR互补，保留其一即可。", false, rsiColor));

        // === BOLL轨道（6分）===
        BigDecimal bollPos = tech != null ? tech.getBollPosition() : null;
        BigDecimal bollUpper = tech != null ? tech.getBollUpper() : null;
        BigDecimal bollLower = tech != null ? tech.getBollLower() : null;
        int bollScore = 0;
        String bollDisplay = "-";
        String bollColor = "default";
        if (bollPos != null) {
            double pos = bollPos.doubleValue();
            if (pos > 1.0) {
                bollDisplay = "突破上轨";
                bollScore = (rsiVal <= 70) ? 6 : 2;
                bollColor = (rsiVal <= 70) ? "red" : "volcano";
            } else if (pos >= 0.8) {
                bollDisplay = "上轨附近";
                bollScore = (rsiVal <= 70) ? 4 : 2;
                bollColor = (rsiVal <= 70) ? "volcano" : "volcano";
            } else if (pos >= 0.5) {
                bollDisplay = "中上";
                bollScore = 3;
                bollColor = "blue";
            } else if (pos >= 0.2) {
                bollDisplay = "中下";
                bollScore = 2;
                bollColor = "default";
            } else {
                bollDisplay = "下轨附近";
                bollScore = (rsiVal < 30) ? 2 : 0;
                bollColor = (rsiVal < 30) ? "blue" : "green";
            }
        }
        String bollRange = "";
        if (bollUpper != null && bollLower != null) {
            bollRange = "上" + bollUpper.setScale(2, RoundingMode.HALF_UP) + "/下" + bollLower.setScale(2, RoundingMode.HALF_UP);
        }
        items.add(buildItem("BOLL轨道", bollDisplay + (bollRange.isEmpty() ? "" : " (" + bollRange + ")"),
                bollScore, 6,
                "【指标原理】布林带(Bollinger Bands)由 John Bollinger 于1980年代发明，是衡量价格波动范围和相对位置的轨道指标。" +
                "中轨 = N日简单移动平均线(SMA，默认20日)，代表中期趋势方向；" +
                "上轨 = 中轨 + k × 标准差(默认k=2)，代表价格波动的上边界；" +
                "下轨 = 中轨 - k × 标准差，代表价格波动的下边界。" +
                "标准差反映价格波动幅度——波动越大，带宽越宽；波动越小，带宽越窄。" +
                "【三条线的含义】中轨：多空分水岭，价格在中轨上方运行偏强，下方偏弱；" +
                "上轨：动态阻力位，价格触及或突破上轨说明买方力量极强（但持续突破会过度延伸）；" +
                "下轨：动态支撑位，价格触及或跌破下轨说明卖方力量极强（但持续跌破会过度延伸）。" +
                "【位置含义】突破上轨 = 极端强势，但需警惕超买（配合RSI过滤）；上轨附近 = 偏强运行，上方空间有限；" +
                "中轨附近 = 多空均衡；下轨附近 = 偏弱运行，若RSI超卖则可能是反弹机会；跌破下轨 = 极端弱势。" +
                "【带宽含义】带宽>15% = 波动大，趋势强但可能即将收敛；带宽5%~10% = 正常波动；" +
                "带宽<5% = 极度收敛（布林带收口），市场犹豫期即将结束，突破在即——一旦放量突破，趋势力度往往很强。" +
                "【评分规则】突破上轨(RSI<=70)=6分，突破上轨(RSI>70)=2分（冲顶嫌疑）；" +
                "上轨附近(RSI<=70)=4分，上轨附近(RSI>70)=2分；>=0.5=3分；>=0.2=2分；" +
                "下轨附近(RSI<30)=2分（超卖反弹），否则0分。" +
                "【为什么加RSI过滤】突破布林上轨本身是强势信号，但若同时RSI>70超买区，往往是冲顶诱多，需降权处理。" +
                "【实战用法】① 开口（带宽扩大）+ 价格沿上轨运行 = 强趋势延续；② 收口（带宽收窄到<5%）+ 放量突破 = 变盘启动；" +
                "③ 价格从下轨反弹穿越中轨 = 弱势转强信号；④ 价格从上轨回落跌破中轨 = 强势转弱信号。",
                false, bollColor));

        // === 量价背离（参考项，扣分在 calcTechScore 中）===
        Boolean divergence = tech != null ? tech.getPriceVolumeDivergence() : null;
        BigDecimal ret5dVal = tech != null ? tech.getRet5d() : null;
        BigDecimal netMain5dVal = tech != null ? tech.getNetMain5d() : null;
        String divDisplay = "-";
        String divColor = "default";
        if (Boolean.TRUE.equals(divergence)) {
            String divType = tech.getDivergenceType();
            if ("HIGH_PRICE_MAIN_OUTFLOW".equals(divType)) {
                divDisplay = "⚠ 高位背离（主力出货）";
                divColor = "red";
            } else if ("LOW_PRICE_MAIN_INFLOW".equals(divType)) {
                divDisplay = "✓ 低位背离（主力吸筹）";
                divColor = "green";
            } else {
                divDisplay = "⚠ 量价背离";
                divColor = "volcano";
            }
        } else {
            // 未触发：嵌入实际值供前端 tooltip 显示
            // 格式：ret5=+x.xx%/main=+xxxx万(元)，前端可正则提取
            String ret5Str = ret5dVal != null ? String.format("%+.2f%%", ret5dVal.doubleValue() * 100) : "N/A";
            String mainStr;
            if (netMain5dVal != null) {
                double v = netMain5dVal.doubleValue();
                if (Math.abs(v) >= 1_0000_0000) {
                    mainStr = String.format("%+.1f亿(元)", v / 1_0000_0000);
                } else {
                    mainStr = String.format("%+.0f万(元)", v / 10000);
                }
            } else {
                mainStr = "N/A";
            }
            divDisplay = "条件未达 | ret5=" + ret5Str + " main=" + mainStr;
            divColor = "default";
        }
        items.add(buildItem("量价背离", divDisplay, 0, 0,
                "【解决什么问题】量价背离解决的是\"股价涨了但谁在买\"的资金识别问题。" +
                "如果价格持续上涨但大资金反而在净流出（主力出货），上涨不可持续，应减仓；" +
                "如果价格持续下跌但大资金反而在净流入（主力吸筹），下跌可能即将见底，可关注。" +
                "【触发条件】" +
                "高位背离：近5日涨幅>=3% 且 近5日主力净流出>=5000万元；" +
                "低位背离：近5日跌幅<=-3% 且 近5日主力净流入>=5000万元。" +
                "【为什么重要】单纯看价格容易被\"虚涨\"欺骗——主力可以在小成交量下用少量资金拉高股价吸引散户接盘。" +
                "量价背离通过对比价格方向与资金方向，能识别主力暗中派发还是悄悄吸筹，是防骗线的重要辅助。", true, divColor));

        // === 短期趋势偏离 ===
        BigDecimal ret5d = tech != null ? tech.getRet5d() : null;
        BigDecimal ret20d = tech != null ? tech.getRet20d() : null;
        String shortTermDisplay = "-";
        String shortTermColor = "default";
        if (ret5d != null && ret20d != null) {
            double r5 = ret5d.doubleValue() * 100;
            double r20 = ret20d.doubleValue() * 100;
            String r5Str = String.format("%.1f%%", r5);
            String r20Str = String.format("%.1f%%", r20);
            if (r5 > 10 && r20 < 3) {
                shortTermDisplay = "⚠ 反弹（5日" + r5Str + " / 20日" + r20Str + "）";
                shortTermColor = "volcano";
            } else if (r5 > r20) {
                shortTermDisplay = "短强（5日" + r5Str + " / 20日" + r20Str + "）";
                shortTermColor = "red";
            } else if (r5 < -5 && r20 > 0) {
                shortTermDisplay = "⚠ 回撤（5日" + r5Str + " / 20日" + r20Str + "）";
                shortTermColor = "green";
            } else {
                shortTermDisplay = "5日" + r5Str + " / 20日" + r20Str;
                shortTermColor = "blue";
            }
        }
        items.add(buildItem("趋势判断", shortTermDisplay, 0, 0,
                "5日 vs 20日涨幅对比。短期>>中期=反弹非趋势（评分综合惩罚），短期<<中期=回撤非见底", true, shortTermColor));

        // === 均线空头排列 ===
        boolean maBearish = tech != null && Boolean.TRUE.equals(tech.getMaBearish());
        items.add(buildItem("均线空头", maBearish ? "是" : "否", maBearish ? -2 : 0, 0,
                "均线空头排列(MA5<MA10<MA20<MA60)时综合评分额外减2分", true, maBearish ? "green" : "default"));

        // === DMI趋势强度（仅ADX，3分）— 方向部分由趋势状态覆盖，此处只保留强度 ===
        // 注意：+DI/-DI 方向由 calcTechScore 趋势状态 覆盖，DMI 只保留 ADX 强度评分
        BigDecimal dmiAdx = tech != null ? tech.getDmiAdx() : null;
        BigDecimal dmiPlusDI = tech != null ? tech.getDmiPlusDI() : null;
        BigDecimal dmiMinusDI = tech != null ? tech.getDmiMinusDI() : null;
        String dmiDisplay = "-";
        String dmiColor = "default";
        int dmiScore = 0;
        if (dmiAdx != null) {
            double adxVal = dmiAdx.doubleValue();
            String adxStr = dmiAdx.setScale(1, RoundingMode.HALF_UP).toString();
            String trendStr = adxVal > 30 ? "强趋势" : adxVal > 20 ? "弱趋势" : "震荡";
            // 额外显示多空方向（参考，不影响评分）
            String dirStr = "";
            if (dmiPlusDI != null && dmiMinusDI != null) {
                dirStr = dmiPlusDI.doubleValue() > dmiMinusDI.doubleValue() ? "多头" : "空头";
            }
            dmiDisplay = (dirStr.isEmpty() ? "" : dirStr + " / ") + "ADX=" + adxStr + "(" + trendStr + ")";
            dmiColor = adxVal > 30 ? "red" : adxVal > 20 ? "blue" : "default";
            dmiScore = adxVal > 30 ? 3 : adxVal > 20 ? 1 : 0;
        }
        items.add(buildItem("DMI强度", dmiDisplay, dmiScore, 3,
                "【仅看ADX】方向(+DI/-DI)由趋势状态覆盖，不重复计分。ADX=趋势强度绝对值：" +
                "ADX>30=强趋势(3分)；ADX>20=弱趋势(1分)；ADX<20=震荡(0分)。" +
                "【核心用法】ADX>30=趋势明确；ADX<20=震荡市指标失效；ADX从低位上升=趋势形成中。", false, dmiColor));

        // === WR威廉指标（参考项）===
        BigDecimal wr = tech != null ? tech.getWr() : null;
        String wrDisplay = "-";
        String wrColor = "default";
        if (wr != null) {
            double wrVal = wr.doubleValue();
            String wrStr = wr.setScale(0, RoundingMode.HALF_UP).toString();
            if (wrVal < -80) {
                wrDisplay = wrStr + "（超卖区）";
                wrColor = "blue";  // 超卖，低位
            } else if (wrVal > -20) {
                wrDisplay = wrStr + "（超买区）";
                wrColor = "volcano";  // 超买，高位
            } else {
                wrDisplay = wrStr + "（中性）";
                wrColor = "default";
            }
        }
        items.add(buildItem("WR(14)", wrDisplay, 0, 0,
                "【指标原理】WR(14) = (N日内最高价 - 今日收盘价) / (N日内最高价 - N日内最低价) × (-100)。" +
                "范围 [-100, 0]：<-80 为超卖（低位积累反弹动能），>-20 为超买（高位积累回调风险）。" +
                "【与RSI的关系】WR 与 RSI 是互为逆运算的指标，RSI>70 超买对应 WR<-30，RSI<30 超卖对应 WR>-70。" +
                "WR 对短期极端值更敏感，适合辅助 RSI 做二次确认。" +
                "【实战用法】WR 连续3天<-80 = 超卖积累，可关注反弹机会；WR 连续3天>-20 = 超买积累，注意回调风险。",
                true, wrColor));

        // === BOLL带宽（正向评分项，满分2分）===
        BigDecimal bollBw = tech != null ? tech.getBollBandwidth() : null;
        String bwDisplay = "-";
        String bwColor = "default";
        int bwScore = 0;
        if (bollBw != null) {
            double bw = bollBw.doubleValue();
            if (bw < 5.0) {
                bwDisplay = String.format("%.2f%%（极度收敛）", bw);
                bwColor = "red";
                bwScore = 2;
            } else if (bw < 10.0) {
                bwDisplay = String.format("%.2f%%（收敛）", bw);
                bwColor = "volcano";
            } else {
                bwDisplay = String.format("%.2f%%（正常）", bw);
                bwColor = "default";
            }
        }
        items.add(buildItem("BOLL带宽", bwDisplay, bwScore, 2,
                "【指标原理】布林带带宽 = (上轨 - 下轨) / 中轨 × 100%，衡量价格波动范围的相对大小。" +
                "【评分规则】带宽<5%(极度收敛)=2分；5%~10%(收敛)=0分；>10%(正常或发散)=0分。" +
                "【核心用法】带宽极度收敛意味着市场犹豫期即将结束，突破在即——一旦放量突破方向确立，趋势力度往往很强。", false, bwColor));

        // === KDJ随机指标（参考项，死叉扣分在 calcTechScore 中）===
        BigDecimal kdjK = tech != null ? tech.getKdjK() : null;
        BigDecimal kdjD = tech != null ? tech.getKdjD() : null;
        BigDecimal kdjJ = tech != null ? tech.getKdjJ() : null;
        Boolean kdjGolden = tech != null ? tech.getKdjGoldenCross() : null;
        Boolean kdjDead = tech != null ? tech.getKdjDeadCross() : null;
        String kdjDisplay = "-";
        String kdjColor = "default";
        if (kdjK != null && kdjD != null && kdjJ != null) {
            String kStr = kdjK.setScale(1, RoundingMode.HALF_UP).toString();
            String dStr = kdjD.setScale(1, RoundingMode.HALF_UP).toString();
            String jStr = kdjJ.setScale(1, RoundingMode.HALF_UP).toString();
            if (Boolean.TRUE.equals(kdjGolden)) {
                kdjDisplay = "K=" + kStr + " D=" + dStr + " J=" + jStr + " ★金叉";
                kdjColor = "red";
            } else if (Boolean.TRUE.equals(kdjDead)) {
                kdjDisplay = "K=" + kStr + " D=" + dStr + " J=" + jStr + " ✗死叉";
                kdjColor = "green";
            } else {
                kdjDisplay = "K=" + kStr + " D=" + dStr + " J=" + jStr;
                // J>80 超买偏红，J<20 超卖偏蓝
                double jVal = kdjJ.doubleValue();
                if (jVal > 80) kdjColor = "volcano";
                else if (jVal < 20) kdjColor = "blue";
            }
        }
        items.add(buildItem("KDJ(9,3,3)", kdjDisplay, 0, 0,
                "【指标原理】KDJ = RSV 的 M日 EMA，由 K线（快速线）、D线（慢速线）、J线（敏感线）组成。" +
                "J = 3×K - 2×D，波动最大，对价格变化最敏感，可正可负（范围约 -50~150）。" +
                "【参数】(9,3,3)：9日RSV计算周期，K/D的3日EMA平滑。参数越小越敏感。" +
                "【金叉/死叉】K上穿D=金叉（买入信号）；K下穿D=死叉（卖出信号，需配合其他指标）。" +
                "【超买超卖】J>80=超买区，J<20=超卖区；注意：金叉在20以下出现更可靠，死叉在80以上出现更可靠。" +
                "【与RSI的关系】RSI和KDJ都是动量指标，RSI更稳定，KDJ更敏感，常配合使用互相验证。" +
                "【实战用法】KDJ低位（K<20）金叉+RSI未超买=较强的买入信号；KDJ高位（K>80）死叉+RSI>70=较强的卖出信号。" +
                "KDJ的缺点：对震荡行情敏感，容易反复金叉死叉，建议结合趋势状态使用。",
                true, kdjColor));

        // === 惩罚项：DMI空头 ===
        BigDecimal dmiP = tech != null ? tech.getDmiPlusDI() : null;
        BigDecimal dmiM = tech != null ? tech.getDmiMinusDI() : null;
        if (dmiP != null && dmiM != null && dmiM.doubleValue() > dmiP.doubleValue()) {
            items.add(buildItem("DMI空头", "-DI > +DI", -1, 0,
                    "DMI空头信号：-DI > +DI，下跌力度强于上涨力度，技术面扣分1分", true, "green"));
        }

        // === SAR 抛物线转向（参考项）===
        BigDecimal sar = tech != null ? tech.getSar() : null;
        Boolean sarAbove = tech != null ? tech.getSarAbovePrice() : null;
        Boolean sarTurnBull = tech != null ? tech.getSarTurnBullish() : null;
        Boolean sarTurnBear = tech != null ? tech.getSarTurnBearish() : null;
        String sarDisplay = "-";
        String sarColor = "default";
        if (sar != null && sarAbove != null) {
            if (Boolean.TRUE.equals(sarTurnBull)) {
                sarDisplay = "✓ 翻多 SAR=" + sar.setScale(3, RoundingMode.HALF_UP);
                sarColor = "red";
            } else if (Boolean.TRUE.equals(sarTurnBear)) {
                sarDisplay = "✗ 翻空 SAR=" + sar.setScale(3, RoundingMode.HALF_UP);
                sarColor = "green";
            } else if (sarAbove) {
                sarDisplay = "多头 SAR=" + sar.setScale(3, RoundingMode.HALF_UP) + " < 价";
                sarColor = "red";
            } else {
                sarDisplay = "空头 SAR=" + sar.setScale(3, RoundingMode.HALF_UP) + " > 价";
                sarColor = "green";
            }
        }
        items.add(buildItem("SAR", sarDisplay, 0, 0,
                "SAR抛物线转向，衡量趋势反转和动态止损点。" +
                "SAR在价格下方=多头持仓区；上方=空头持仓区。" +
                "穿越价格=反转信号。注意：对震荡行情敏感易反复", true, sarColor));

        // === 惩罚项：SAR翻空 ===
        if (Boolean.TRUE.equals(sarTurnBear)) {
            items.add(buildItem("SAR翻空", "是", -2, 0,
                    "SAR翻空：抛物线转向从价格下方翻至上方，趋势反转看跌，技术面扣分2分", true, "green"));
        }

        // === 近60日局部高/低点（正向评分项，满分4分）===
        BigDecimal nearHigh = tech != null ? tech.getNearHigh60() : null;
        BigDecimal nearLow  = tech != null ? tech.getNearLow60()  : null;
        BigDecimal nearHighPct = tech != null ? tech.getNearHighPct() : null;
        BigDecimal nearLowPct  = tech != null ? tech.getNearLowPct()  : null;
        String nearDisplay = "-";
        String nearColor = "default";
        int nearScore = 0;
        if (nearHigh != null && nearLow != null) {
            String highStr = "近高=" + nearHigh.setScale(3, RoundingMode.HALF_UP);
            String lowStr  = "近低=" + nearLow.setScale(3, RoundingMode.HALF_UP);
            String pctHighStr = nearHighPct != null ? "(-" + nearHighPct.setScale(1, RoundingMode.HALF_UP) + "%)" : "";
            String pctLowStr  = nearLowPct  != null ? "(+" + nearLowPct.setScale(1, RoundingMode.HALF_UP) + "%)" : "";
            if (nearHighPct != null && nearHighPct.doubleValue() < 3) {
                nearColor = "volcano";  // 接近高点
            } else if (nearLowPct != null && nearLowPct.doubleValue() < 3) {
                nearColor = "blue";     // 接近低点
            }
            nearDisplay = highStr + pctHighStr + " / " + lowStr + pctLowStr;
        }
        if (nearLowPct != null) {
            double lowPct = nearLowPct.doubleValue();
            if (lowPct < 3.0) nearScore += 6;
            else if (lowPct < 10.0) nearScore += 3;
        }
        if (nearHighPct != null) {
            double highPct = nearHighPct.doubleValue();
            if (highPct < 3.0) nearScore -= 3;
            else if (highPct < 10.0) nearScore -= 1;
        }
        items.add(buildItem("近高/低(60日)", nearDisplay, nearScore, 8,
                "【含义】近60日最高价和最低价，代表近三个月内股价波动的上下边界。" +
                "【评分规则】距低点<3%=+6分（强支撑，反弹概率高）；距低点<10%=+3分（低位区域）；" +
                "距高点<3%=-3分（阻力附近，追高风险）；距高点<10%=-1分（上部区域，空间有限）。最大8分。", false, nearColor));

        // === 量比（正向评分项，满分5分）===
        BigDecimal volRatio = tech != null ? tech.getVolumeRatio() : null;
        String vrDisplay = "-";
        String vrColor = "default";
        int vrScore = 0;
        if (volRatio != null) {
            double vr = volRatio.doubleValue();
            if (vr >= 2.0) {
                vrDisplay = String.format("%.2f（放量）", vr);
                vrColor = "red";
                vrScore = 5;
            } else if (vr >= 1.5) {
                vrDisplay = String.format("%.2f（温和放量）", vr);
                vrColor = "volcano";
                vrScore = 3;
            } else if (vr >= 1.0) {
                vrDisplay = String.format("%.2f（正常）", vr);
                vrColor = "default";
                vrScore = 2;
            } else if (vr >= 0.5) {
                vrDisplay = String.format("%.2f（缩量）", vr);
                vrColor = "blue";
                vrScore = 0;
            } else {
                vrDisplay = String.format("%.2f（极度缩量）", vr);
                vrColor = "blue";
                vrScore = -1;  // 极度缩量惩罚
            }
        }
        items.add(buildItem("量比(5日/20日)", vrDisplay, vrScore, 5,
                "【计算】量比 = 近5日平均成交量 ÷ 近20日平均成交量，衡量短期量能相对中期均量的活跃程度。" +
                "【评分规则】≥2.0(放量)=5分；≥1.5(温和放量)=3分；≥1.0(正常)=2分；<0.5(极度缩量)=-1分。" +
                "【阈值解读】>3.0 = 极度放量（异常信号，可能是主力对倒或消息刺激）；" +
                "2.0~3.0 = 显著放量（资金活跃度大幅提升）；1.5~2.0 = 温和放量（最健康的量价配合，趋势延续）；" +
                "1.0~1.5 = 正常水平；0.5~1.0 = 缩量（动力不足）；<0.5 = 极度缩量（变盘前兆或冷门股）。" +
                "【核心原则】量比必须结合价格方向看：放量上涨=真金白银做多；放量下跌=主力出货；" +
                "缩量上涨=虚涨；缩量下跌=阴跌。价量配合是技术分析基础。", false, vrColor));

        // === MA间距发散/收敛（正向评分项，满分3分）===
        // 原理：MA5-MA20 间距扩大=趋势加速（发散）=正分；间距收窄=动能衰减（收敛）=负分
        BigDecimal maSpacing    = tech != null ? tech.getMaSpacing()    : null;
        BigDecimal maSpacingPrev = tech != null ? tech.getMaSpacingPrev() : null;
        String maDivergence = tech != null ? tech.getMaDivergence() : null;
        String maDivDisplay = "-";
        String maDivColor = "default";
        int divScore = 0;
        if (maDivergence != null && maSpacing != null) {
            double spacing = maSpacing.doubleValue();
            double spacingPrev = maSpacingPrev != null ? maSpacingPrev.doubleValue() : 0;
            double spacingDelta = spacing - spacingPrev;
            if ("发散".equals(maDivergence)) {
                if (spacing > 2.0) {
                    maDivDisplay = String.format("发散（%.2f%%→%.2f%%）", spacingPrev, spacing);
                    maDivColor = "red";
                    divScore = 3;  // 大间距扩大=强趋势
                } else if (spacing > 0) {
                    maDivDisplay = String.format("发散（%.2f%%→%.2f%%）", spacingPrev, spacing);
                    maDivColor = "volcano";
                    divScore = 2;  // 正间距扩大=趋势延续
                } else {
                    maDivDisplay = String.format("发散（%.2f%%→%.2f%%）", spacingPrev, spacing);
                    maDivColor = "blue";
                    divScore = 1;  // 负间距扩大（空头排列加速）=偏弱但有方向
                }
            } else if ("收敛".equals(maDivergence)) {
                if (spacing < -2.0) {
                    maDivDisplay = String.format("收敛（%.2f%%→%.2f%%）", spacingPrev, spacing);
                    maDivColor = "green";
                    divScore = -2;  // 大间距收窄=趋势快速衰竭
                } else {
                    maDivDisplay = String.format("收敛（%.2f%%→%.2f%%）", spacingPrev, spacing);
                    maDivColor = "blue";
                    divScore = -1;  // 间距收窄=动能衰减
                }
            } else {
                maDivDisplay = String.format("稳定（%.2f%%）", spacing);
                maDivColor = "default";
                divScore = 0;
            }
        }
        items.add(buildItem("MA发散/收敛", maDivDisplay, divScore, 0,
                "【指标原理】均线间距发散/收敛检测的是 MA5 与 MA20 之间的间距变化率。" +
                "间距 = (MA5 - MA20) / MA20 × 100%。" +
                "【发散含义】短均线（MA5）远离长均线（MA20）= 多空分歧扩大 = 趋势加速 = 行情大概率延续。" +
                "【收敛含义】短均线靠拢长均线 = 多空力量趋于一致 = 动能衰减 = 趋势大概率衰竭或横盘整理。" +
                "【评分规则】大间距扩大(>2%Δ)=+3分；正间距扩大=+2分；负间距扩大=+1分；大间距收窄(<-2%Δ)=-2分；一般收敛=-1分；稳定=0分。" +
                "【实战用法】发散+RSI未超买=顺势信号；收敛+RSI超买=趋势衰竭预警；收敛+BOLL极度收口=变盘共振。", true, maDivColor));

        // === SMA5 单独值 ===
        BigDecimal ma5Val = tech != null ? tech.getMa5Value() : null;
        items.add(buildItem("SMA5均线", ma5Val != null ? ma5Val.setScale(3, RoundingMode.HALF_UP) + " 元" : "-",
                0, 0, "SMA5=过去5日收盘价算术平均，代表短期持仓成本。" +
                "需结合MA10/MA20/MA60判断多头排列状态，单独看意义有限", true, "default"));

        return items;
    }
}
