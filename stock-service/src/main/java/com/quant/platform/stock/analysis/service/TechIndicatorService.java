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
public class TechIndicatorService {

    private final AnalysisChMapper analysisChMapper;
    private final TradingSignalEngine tradingSignalEngine;
    private final AnalysisCommonService analysisCommon;

    public void supplementTechIndicators(TechSignal tech, List<DailyBarRow> bars) {
        if (bars == null || bars.size() < 30) return;

        int n = bars.size();
        double[] closes = new double[n];
        double[] highs  = new double[n];
        double[] lows   = new double[n];
        double[] volumes = new double[n];
        for (int i = 0; i < n; i++) {
            closes[i]  = bars.get(i).getClosePrice().doubleValue();
            highs[i]   = bars.get(i).getHighPrice().doubleValue();
            lows[i]    = bars.get(i).getLowPrice().doubleValue();
            volumes[i] = bars.get(i).getVolume() != null
                         ? bars.get(i).getVolume().doubleValue() : 0;
        }

        // ── SMA5 单独值（用于明细展示）────────────────────────────
        if (n >= 5) {
            tech.setMa5Value(BigDecimal.valueOf(avg(closes, n - 5, n)).setScale(3, RoundingMode.HALF_UP));
        }

        // ── 近60日局部高点 / 低点 ─────────────────────────────────
        if (n >= 60) {
            double maxHigh = Double.MIN_VALUE;
            double minLow  = Double.MAX_VALUE;
            for (int i = n - 60; i < n; i++) {
                if (highs[i] > maxHigh) maxHigh = highs[i];
                if (lows[i]  < minLow)  minLow  = lows[i];
            }
            double lastClose = closes[n - 1];
            tech.setNearHigh60(BigDecimal.valueOf(maxHigh).setScale(3, RoundingMode.HALF_UP));
            tech.setNearLow60(BigDecimal.valueOf(minLow).setScale(3, RoundingMode.HALF_UP));
            if (maxHigh > lastClose) {
                tech.setNearHighPct(BigDecimal.valueOf((maxHigh - lastClose) / lastClose * 100)
                        .setScale(2, RoundingMode.HALF_UP));
            }
            if (lastClose > minLow) {
                tech.setNearLowPct(BigDecimal.valueOf((lastClose - minLow) / minLow * 100)
                        .setScale(2, RoundingMode.HALF_UP));
            }
        }

        // ── 量比：近5日均量 / 近20日均量 ─────────────────────────
        if (n >= 20) {
            double sum5 = 0, sum20 = 0;
            for (int i = n - 5;  i < n;      i++) sum5  += volumes[i];
            for (int i = n - 20; i < n;      i++) sum20 += volumes[i];
            double vr = sum20 > 0 ? (sum5 / 5.0) / (sum20 / 20.0) : 0;
            tech.setVolumeRatio(BigDecimal.valueOf(vr).setScale(2, RoundingMode.HALF_UP));
        }

        // ── 趋势状态：用 MA20/MA60 判定 ──
        if (tech.getTrend() == null && n >= 60) {
            double ma20 = avg(closes, n - 20, n);
            double ma60 = avg(closes, n - 60, n);
            double lastClose = closes[n - 1];
            if (lastClose > ma20 && ma20 > ma60) {
                tech.setTrend("BULLISH");
            } else if (lastClose < ma20 && ma20 < ma60) {
                tech.setTrend("BEARISH");
            } else {
                tech.setTrend("SIDEWAYS");
            }
        }

        // ── SAR 抛物线转向（Parabolic SAR）───────────────────────
        // 参数：初始AF=0.02，步长0.02，上限0.2
        if (n >= 3) {
            final double AF_INIT = 0.02;
            final double AF_STEP = 0.02;
            final double AF_MAX  = 0.20;

            // 判断第一段趋势：取最近3天，若最后一根收盘 > 第一根收盘 → 初始多头
            boolean isBull = closes[n - 1] >= closes[n - 3];
            double sar = isBull ? lows[n - 3] : highs[n - 3];
            double af  = AF_INIT;
            double ep  = isBull ? highs[n - 3] : lows[n - 3]; // 极点价

            for (int i = n - 2; i < n; i++) {
                // 预测SAR
                double predSar = sar + af * (ep - sar);
                boolean currentBull = closes[i] > predSar;
                if (currentBull != isBull) {
                    // 翻转：开新空或多
                    isBull = currentBull;
                    af = AF_INIT;
                    ep = currentBull ? highs[i] : lows[i];
                    sar = currentBull ? lows[i] : highs[i];
                } else {
                    // 更新EP
                    if ((isBull && highs[i] > ep) || (!isBull && lows[i] < ep)) {
                        ep = isBull ? highs[i] : lows[i];
                        af = Math.min(AF_MAX, af + AF_STEP);
                    }
                    // SAR = 前SAR + AF*(EP - 前SAR)
                    sar = sar + af * (ep - sar);
                    // 多头SAR不能高于前一根最低；空头SAR不能低于前一根最高
                    if (isBull && i > n - 2) {
                        sar = Math.min(sar, lows[i - 1]);
                    }
                    if (!isBull && i > n - 2) {
                        sar = Math.max(sar, highs[i - 1]);
                    }
                }
            }

            double lastClose2 = closes[n - 1];
            double lastSar    = sar;
            tech.setSar(BigDecimal.valueOf(lastSar).setScale(3, RoundingMode.HALF_UP));
            tech.setSarAbovePrice(lastSar < lastClose2);  // SAR在价格下方=多头

            // 翻多/翻空：重新运行 SAR 算法到倒数第二天，比较其趋势方向 vs 当前趋势方向
            if (n >= 3) {
                // 从头算到倒数第二天（不包含今天）
                boolean isBullPrev = closes[n - 2] >= closes[n - 3];
                double sarP = isBullPrev ? lows[n - 3] : highs[n - 3];
                double afP  = AF_INIT;
                double epP  = isBullPrev ? highs[n - 3] : lows[n - 3];
                for (int i = n - 2; i < n - 1; i++) {
                    double predS = sarP + afP * (epP - sarP);
                    boolean curB = closes[i] > predS;
                    if (curB != isBullPrev) {
                        isBullPrev = curB; afP = AF_INIT;
                        epP = curB ? highs[i] : lows[i];
                        sarP = curB ? lows[i] : highs[i];
                    } else {
                        if ((isBullPrev && highs[i] > epP) || (!isBullPrev && lows[i] < epP)) {
                            epP = isBullPrev ? highs[i] : lows[i];
                            afP = Math.min(AF_MAX, afP + AF_STEP);
                        }
                        sarP = sarP + afP * (epP - sarP);
                    }
                }
                // 前一根SAR是否在收盘价下方
                boolean prevSarBelow = sarP < closes[n - 2];
                boolean currSarBelow = lastSar < lastClose2;
                tech.setSarTurnBullish(!prevSarBelow && currSarBelow);  // 前上方 → 当前下方 = 翻多
                tech.setSarTurnBearish(prevSarBelow && !currSarBelow);  // 前下方 → 当前上方 = 翻空
            }
        }
        
        // --- 均线多头：MA5 > MA10 > MA20 > MA60 ---
        if (tech.getMaBullish() == null && n >= 60) {
            double ma5 = avg(closes, n - 5, n);
            double ma10 = avg(closes, n - 10, n);
            double ma20 = avg(closes, n - 20, n);
            double ma60 = avg(closes, n - 60, n);
            tech.setMaBullish(ma5 > ma10 && ma10 > ma20 && ma20 > ma60);
        }
        
        // --- MACD金叉 + 柱值 + 动能检测 ---
        if (n >= 35) {
            double[] ema12 = calcEma(closes, 12);
            double[] ema26 = calcEma(closes, 26);
            double[] dif = new double[n];
            for (int i = 0; i < n; i++) dif[i] = ema12[i] - ema26[i];
            double[] dea = calcEma(dif, 9);
            
            // 金叉
            if (tech.getMacdGolden() == null) {
                boolean golden = dif[n - 1] > dea[n - 1] && dif[n - 2] <= dea[n - 2];
                tech.setMacdGolden(golden);
            }
            
            // MACD柱值（DIF - DEA）
            double hist = dif[n - 1] - dea[n - 1];
            double histPrev = n >= 2 ? dif[n - 2] - dea[n - 2] : hist;
            tech.setMacdHistogram(BigDecimal.valueOf(hist).setScale(4, RoundingMode.HALF_UP));
            tech.setMacdHistogramPrev(BigDecimal.valueOf(histPrev).setScale(4, RoundingMode.HALF_UP));
        }
        
        // --- RSI14 ---
        if (tech.getRsi() == null && n >= 15) {
            double rsi = calcRsi(closes, 14);
            tech.setRsi(BigDecimal.valueOf(rsi).setScale(2, RoundingMode.HALF_UP));
        }
        
        // --- BOLL轨道（20日周期，±2倍标准差）---
        if (n >= 25) {
            double ma20 = avg(closes, n - 20, n);
            double variance = 0;
            for (int i = n - 20; i < n; i++) {
                variance += (closes[i] - ma20) * (closes[i] - ma20);
            }
            double stdDev = Math.sqrt(variance / 20.0);
            double bollUpper = ma20 + 2 * stdDev;
            double bollMid = ma20;
            double bollLower = ma20 - 2 * stdDev;
            double latestClose = closes[n - 1];
            // 位置：0=下轨，1=中轨，2=上轨；>1突破上轨，<0跌破下轨
            double bandWidth = bollUpper - bollLower;
            double bollPos = bandWidth > 0 ? (latestClose - bollLower) / bandWidth : 0.5;
            
            tech.setBollUpper(BigDecimal.valueOf(bollUpper).setScale(3, RoundingMode.HALF_UP));
            tech.setBollMid(BigDecimal.valueOf(bollMid).setScale(3, RoundingMode.HALF_UP));
            tech.setBollLower(BigDecimal.valueOf(bollLower).setScale(3, RoundingMode.HALF_UP));
            tech.setBollPosition(BigDecimal.valueOf(bollPos).setScale(4, RoundingMode.HALF_UP));
            // 布林带带宽：(<5%为极度收敛，即将突破)
            double bandwidth = bollMid > 0 ? (bandWidth / bollMid) * 100 : 0;
            tech.setBollBandwidth(BigDecimal.valueOf(bandwidth).setScale(2, RoundingMode.HALF_UP));
        }
        
        // --- 收益率（用于量价背离检测）---
        if (n >= 6) {
            double ret5 = (closes[n - 1] - closes[n - 6]) / closes[n - 6];
            tech.setRet5d(BigDecimal.valueOf(ret5).setScale(4, RoundingMode.HALF_UP));
        }
        if (n >= 21) {
            double ret20 = (closes[n - 1] - closes[n - 21]) / closes[n - 21];
            tech.setRet20d(BigDecimal.valueOf(ret20).setScale(4, RoundingMode.HALF_UP));
        }

        // --- MACD死叉 + 零轴判断 ---
        if (n >= 35) {
            double[] ema12 = calcEma(closes, 12);
            double[] ema26 = calcEma(closes, 26);
            double[] dif = new double[n];
            for (int i = 0; i < n; i++) dif[i] = ema12[i] - ema26[i];
            double[] dea = calcEma(dif, 9);

            // MACD零轴：DIF > 0 表示在零轴上方
            boolean aboveZero = dif[n - 1] > 0;
            tech.setMacdAboveZero(aboveZero);

            // MACD死叉：DIF下穿DEA（前一根DIF>=DEA，当前DIF<DEA）
            if (n >= 2) {
                boolean dead = dif[n - 1] < dea[n - 1] && dif[n - 2] >= dea[n - 2];
                tech.setMacdDeadCross(dead);
            }
        }

        // --- 均线空头排列（MA5 < MA10 < MA20 < MA60）---
        if (tech.getMaBearish() == null && n >= 60) {
            double ma5 = avg(closes, n - 5, n);
            double ma10 = avg(closes, n - 10, n);
            double ma20 = avg(closes, n - 20, n);
            double ma60 = avg(closes, n - 60, n);
            tech.setMaBearish(ma5 < ma10 && ma10 < ma20 && ma20 < ma60);
        }

        // --- KDJ（9,3,3）---
        if (n >= 10) {
            // 计算 RSV（9日周期）
            double[] rsv = new double[n];
            for (int i = 9; i < n; i++) {
                double maxHigh = Double.MIN_VALUE, minLow = Double.MAX_VALUE;
                for (int j = i - 8; j <= i; j++) {
                    if (highs[j] > maxHigh) maxHigh = highs[j];
                    if (lows[j]  < minLow)  minLow  = lows[j];
                }
                double range = maxHigh - minLow;
                rsv[i] = range > 0 ? (closes[i] - minLow) / range * 100 : 50;
            }
            // 单次 EMA 推进，记录最后一天和倒数第二天的 K/D/J
            double kPrev = 50, dPrev = 50;
            double kCurr = 50, dCurr = 50;
            for (int i = 9; i < n; i++) {
                kCurr = 2.0 / 3 * kPrev + 1.0 / 3 * rsv[i];
                dCurr = 2.0 / 3 * dPrev + 1.0 / 3 * kCurr;
                if (i == n - 1) {
                    double jCurr = 3 * kCurr - 2 * dCurr;
                    tech.setKdjK(BigDecimal.valueOf(kCurr).setScale(2, RoundingMode.HALF_UP));
                    tech.setKdjD(BigDecimal.valueOf(dCurr).setScale(2, RoundingMode.HALF_UP));
                    tech.setKdjJ(BigDecimal.valueOf(jCurr).setScale(2, RoundingMode.HALF_UP));
                    // 金叉：K从下方上穿D；死叉：K从上方下穿D
                    tech.setKdjGoldenCross(kPrev <= dPrev && kCurr > dCurr);
                    tech.setKdjDeadCross(kPrev >= dPrev && kCurr < dCurr);
                }
                kPrev = kCurr;
                dPrev = dCurr;
            }
        }

        // --- WR(14) 威廉指标 ---
        if (n >= 14) {
            double maxHigh14 = Double.MIN_VALUE, minLow14 = Double.MAX_VALUE;
            for (int i = n - 14; i < n; i++) {
                if (highs[i] > maxHigh14) maxHigh14 = highs[i];
                if (lows[i] < minLow14) minLow14 = lows[i];
            }
            double range14 = maxHigh14 - minLow14;
            double wrVal = range14 > 0 ? (maxHigh14 - closes[n - 1]) / range14 * -100 : -50;
            tech.setWr(BigDecimal.valueOf(wrVal).setScale(2, RoundingMode.HALF_UP));
        }

        // --- DMI / ADX（14日）---
        if (n >= 30) {
            int period = 14;
            // 初始化 TR、+DM、-DM
            double[] trArr = new double[n - period];
            double[] plusDMArr = new double[n - period];
            double[] minusDMArr = new double[n - period];
            for (int i = period; i < n; i++) {
                double high = highs[i], low = lows[i], prevHigh = highs[i - 1], prevLow = lows[i - 1];
                double tr = Math.max(high - low, Math.max(Math.abs(high - closes[i - 1]), Math.abs(low - closes[i - 1])));
                double plusDM = Math.max(high - prevHigh, 0) > Math.max(prevLow - low, 0)
                        ? Math.max(high - prevHigh, 0) : 0;
                double minusDM = Math.max(prevLow - low, 0) > Math.max(high - prevHigh, 0)
                        ? Math.max(prevLow - low, 0) : 0;
                trArr[i - period] = tr;
                plusDMArr[i - period] = plusDM;
                minusDMArr[i - period] = minusDM;
            }

            // Wilder平滑
            int m = trArr.length;
            double smoothedTR = 0, smoothedPlusDM = 0, smoothedMinusDM = 0;
            for (int i = 0; i < period; i++) {
                smoothedTR += trArr[i];
                smoothedPlusDM += plusDMArr[i];
                smoothedMinusDM += minusDMArr[i];
            }
            double[] plusDI = new double[m];
            double[] minusDI = new double[m];
            double[] dxArr = new double[m];
            plusDI[period - 1] = smoothedTR > 0 ? smoothedPlusDM / smoothedTR * 100 : 0;
            minusDI[period - 1] = smoothedTR > 0 ? smoothedMinusDM / smoothedTR * 100 : 0;
            dxArr[period - 1] = (plusDI[period - 1] + minusDI[period - 1]) > 0
                    ? Math.abs(plusDI[period - 1] - minusDI[period - 1]) / (plusDI[period - 1] + minusDI[period - 1]) * 100 : 0;

            for (int i = period; i < m; i++) {
                smoothedTR = smoothedTR - smoothedTR / period + trArr[i];
                smoothedPlusDM = smoothedPlusDM - smoothedPlusDM / period + plusDMArr[i];
                smoothedMinusDM = smoothedMinusDM - smoothedMinusDM / period + minusDMArr[i];
                plusDI[i] = smoothedTR > 0 ? smoothedPlusDM / smoothedTR * 100 : 0;
                minusDI[i] = smoothedTR > 0 ? smoothedMinusDM / smoothedTR * 100 : 0;
                dxArr[i] = (plusDI[i] + minusDI[i]) > 0
                        ? Math.abs(plusDI[i] - minusDI[i]) / (plusDI[i] + minusDI[i]) * 100 : 0;
            }

            // ADX：DX的 Wilder 平滑
            int lastIdx = m - 1;
            double adx = 0;
            if (m > period * 2) {
                double smoothedDX = 0;
                for (int i = period; i < period + period; i++) smoothedDX += dxArr[i];
                smoothedDX /= period;
                for (int i = period + period; i < m; i++) {
                    smoothedDX = smoothedDX - smoothedDX / period + dxArr[i];
                }
                adx = smoothedDX;
                // ADX 理论范围 [0, 100]，Wilder 平滑不会超限；防御性处理 NaN/Inf/异常值
                // 注意：ADX=0 是合法值（震荡市），只过滤负数或超限值
                if (Double.isNaN(adx) || Double.isInfinite(adx) || adx < 0 || adx > 100) {
                    adx = 0; // 无效则置 0，前端不显示 ADX
                }
            }

            if (lastIdx >= period - 1) {
                tech.setDmiPlusDI(BigDecimal.valueOf(plusDI[lastIdx]).setScale(2, RoundingMode.HALF_UP));
                tech.setDmiMinusDI(BigDecimal.valueOf(minusDI[lastIdx]).setScale(2, RoundingMode.HALF_UP));
                if (adx >= 0) {
                    tech.setDmiAdx(BigDecimal.valueOf(adx).setScale(2, RoundingMode.HALF_UP));
                }
            }
        }

        // --- MA间距发散/收敛检测（满分3分）---
        // 核心：MA5 与 MA20 的间距变化反映趋势加速/衰减
        // 逻辑：短均线远离长均线（间距扩大）= 发散 = 趋势加速 = 正分
        //       短均线靠拢长均线（间距收窄）= 收敛 = 动能衰减 = 负分
        if (n >= 22) {
            // 当前 MA5 / MA20
            double ma5Curr   = avg(closes, n - 5,  n);
            double ma20Curr  = avg(closes, n - 20, n);
            // 前日 MA5 / MA20（各往前推1天）
            double ma5Prev   = avg(closes, n - 6,  n - 1);
            double ma20Prev  = avg(closes, n - 21, n - 1);

            double spacing      = ma20Curr  > 0 ? (ma5Curr  - ma20Curr)  / ma20Curr  * 100 : 0;
            double spacingPrev  = ma20Prev  > 0 ? (ma5Prev  - ma20Prev)  / ma20Prev  * 100 : 0;
            double spacingDelta = spacing - spacingPrev;  // >0=发散，<0=收敛

            tech.setMaSpacing(BigDecimal.valueOf(spacing).setScale(3, RoundingMode.HALF_UP));
            tech.setMaSpacingPrev(BigDecimal.valueOf(spacingPrev).setScale(3, RoundingMode.HALF_UP));

            // 阈值：0.5% 为显著变化（消除噪音）
            if (spacingDelta > 0.5) {
                tech.setMaDivergence("发散");  // 趋势加速
            } else if (spacingDelta < -0.5) {
                tech.setMaDivergence("收敛");  // 动能衰减
            } else {
                tech.setMaDivergence("稳定");  // 无明显变化
            }
        }
    }

    public double avg(double[] arr, int from, int to) {
        double sum = 0;
        for (int i = from; i < to; i++) sum += arr[i];
        return sum / (to - from);
    }

    public double[] calcEma(double[] data, int period) {
        double[] ema = new double[data.length];
        double k = 2.0 / (period + 1);
        ema[0] = data[0];
        for (int i = 1; i < data.length; i++) {
            ema[i] = data[i] * k + ema[i - 1] * (1 - k);
        }
        return ema;
    }

    public double calcRsi(double[] closes, int period) {
        int n = closes.length;
        double gain = 0, loss = 0;
        for (int i = n - period; i < n; i++) {
            double change = closes[i] - closes[i - 1];
            if (change > 0) gain += change;
            else loss -= change;
        }
        double avgGain = gain / period;
        double avgLoss = loss / period;
        if (avgLoss == 0) return 100.0;
        double rs = avgGain / avgLoss;
        return 100.0 - 100.0 / (1.0 + rs);
    }

    public void detectVolumePriceDivergence(TechSignal tech, String code) {
        if (tech == null) return;
        BigDecimal ret5d = tech.getRet5d();
        if (ret5d == null) return;

        double ret5 = ret5d.doubleValue();

        // 从 CH 查近5日主力净流入累计值
        double netMain5d = 0;
        List<Map<String, Object>> mfList = analysisChMapper.selectMoneyFlowHistory(code, 5, analysisCommon.getLatestTradeDate());
        if (mfList != null && !mfList.isEmpty()) {
            for (Map<String, Object> mf : mfList) {
                if (mf.get("netMain") != null) {
                    netMain5d += ((Number) mf.get("netMain")).doubleValue();
                }
            }
            log.info("[量价背离] code={} ret5={}% netMain5d={}(元) mfListSize={}", code,
                    String.format("%.2f", ret5 * 100),
                    String.format("%.0f", netMain5d),
                    mfList.size());
        } else {
            log.info("[量价背离] code={} 资金流向数据为空，ret5={}%", code, String.format("%.2f", ret5 * 100));
        }

        // 近5日净流出 >= 5000万 且 5日涨幅 >= 3% = 高位背离
        if (ret5 >= 0.03 && netMain5d <= -50_000_000) {
            tech.setPriceVolumeDivergence(true);
            tech.setDivergenceType("HIGH_PRICE_MAIN_OUTFLOW");
            log.info("[量价背离] code={} 触发高位背离: ret5={}% >= 3% 且 netMain5d={}(元) <= -5000万", code, String.format("%.2f", ret5 * 100), String.format("%.0f", netMain5d));
        }
        // 近5日净流入 >= 5000万 且 5日跌幅 >= 3% = 低位背离
        else if (ret5 <= -0.03 && netMain5d >= 50_000_000) {
            tech.setPriceVolumeDivergence(true);
            tech.setDivergenceType("LOW_PRICE_MAIN_INFLOW");
            log.info("[量价背离] code={} 触发低位背离: ret5={}% <= -3% 且 netMain5d={}(元) >= 5000万", code, String.format("%.2f", ret5 * 100), String.format("%.0f", netMain5d));
        } else {
            tech.setPriceVolumeDivergence(false);
            tech.setDivergenceType(null);
            log.info("[量价背离] code={} 未触发: ret5={}%（需>=3%或<=-3%），netMain5d={}(元)（需<=-5000万或>=5000万）", code, String.format("%.2f", ret5 * 100), String.format("%.0f", netMain5d));
        }
        // 将原始计算数据存入 TechSignal，供前端 tooltip 显示不满足原因
        tech.setNetMain5d(BigDecimal.valueOf(netMain5d));
    }

    public List<TradingSignalEngine.ScoreRule> getScoreRules() {
        return tradingSignalEngine.getScoreRules();
    }

    public BigDecimal calcTargetPrice2(BigDecimal currentPrice, FundamentalSignal fs) {
        if (fs == null || fs.getPeTtm() == null || fs.getPeTtm().doubleValue() <= 0) return null;
        double curPE = fs.getPeTtm().doubleValue();
        double pePct = fs.getPePercentile() != null ? fs.getPePercentile().doubleValue() : 50;

        // PE分位≥40 → 估值中性或偏高，均值回归方向向下，不适用
        if (pePct >= 40) return null;

        // PE低于历史中位，回归中位 → 目标价向上
        // 合理PE = curPE / 分位比例（近似回到中位）
        double fairPE = curPE / (pePct / 100.0 + 0.01); // 回到中位附近，下限防除零
        double ratio = fairPE / curPE;
        // 目标价合理上限：不超过当前价2倍
        if (ratio > 2.0) ratio = 2.0;

        return currentPrice.multiply(BigDecimal.valueOf(ratio));
    }

    public BigDecimal calcExtremeTargetPrice(BigDecimal currentPrice, FundamentalSignal fs,
                                              Map<String, Object> stockInfo) {
        if (fs == null || fs.getPb() == null || fs.getPb().doubleValue() <= 0) return null;
        double curPB = fs.getPb().doubleValue();
        if (curPB <= 1.0) return currentPrice; // 已经破净，不再跌

        return currentPrice.multiply(BigDecimal.valueOf(1.0 / curPB));
    }

}
