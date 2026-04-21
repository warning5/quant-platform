package com.quant.platform.screen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quant.platform.financial.entity.StockFinancialIndicator;
import com.quant.platform.financial.mapper.StockFinancialIndicatorMapper;
import com.quant.platform.stock.entity.StockDaily;
import com.quant.platform.stock.entity.StockInfo;
import com.quant.platform.stock.mapper.StockDailyMapper;
import com.quant.platform.stock.mapper.StockInfoMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * 买入价建议 + 止盈止损 + 风险提示
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PriceAdvisorService {

    private final StockDailyMapper stockDailyMapper;
    private final StockFinancialIndicatorMapper financialIndicatorMapper;
    private final StockInfoMapper stockInfoMapper;

    /**
     * 为一批股票计算买入价、止盈止损、风险提示
     * @param symbols 股票代码列表（如 000001.SZ）
     * @param screenDate 选股日期
     * @param valuationWeight 估值权重（如 0.4）
     */
    public Map<String, Map<String, Object>> batchAdvise(List<String> symbols, LocalDate screenDate,
                                                         double valuationWeight) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (String symbol : symbols) {
            try {
                Map<String, Object> advice = advise(symbol, screenDate, valuationWeight);
                if (advice != null) {
                    result.put(symbol, advice);
                }
            } catch (Exception e) {
                log.warn("Failed to advise for {}: {}", symbol, e.getMessage());
            }
        }
        return result;
    }

    /**
     * 为单只股票计算买入价建议
     */
    public Map<String, Object> advise(String symbol, LocalDate screenDate, double valuationWeight) {
        String code = symbol.contains(".") ? symbol.substring(0, symbol.indexOf('.')) : symbol;
        double techWeight = 1.0 - valuationWeight;

        // 1. 加载近60日行情
        LocalDate histStart = screenDate.minusDays(120);
        List<StockDaily> history = stockDailyMapper.selectList(
            new LambdaQueryWrapper<StockDaily>()
                .eq(StockDaily::getCode, code)
                .ge(StockDaily::getTradeDate, histStart)
                .le(StockDaily::getTradeDate, screenDate)
                .orderByAsc(StockDaily::getTradeDate));

        if (history.isEmpty()) return null;

        // 当日行情
        StockDaily today = history.get(history.size() - 1);
        BigDecimal currentClose = today.getClosePrice();
        if (currentClose == null || currentClose.doubleValue() <= 0) return null;

        Map<String, Object> advice = new LinkedHashMap<>();
        advice.put("currentPrice", currentClose);
        advice.put("screenDate", screenDate.toString());

        // 2. 技术面支撑位
        Map<String, Object> techLevels = calcTechLevels(history, currentClose);
        advice.put("techLevels", techLevels);

        // 3. 估值面支撑位
        Map<String, Object> valuationLevels = calcValuationLevels(code, symbol, screenDate);
        advice.put("valuationLevels", valuationLevels);

        // 4. 综合买入价
        double techPrice = toDouble(techLevels.get("suggestTechPrice"));
        double valuationPrice = toDouble(valuationLevels.get("suggestValuationPrice"));
        double suggestPrice;

        if (valuationPrice > 0 && techPrice > 0) {
            suggestPrice = valuationPrice * valuationWeight + techPrice * techWeight;
        } else if (techPrice > 0) {
            suggestPrice = techPrice;
        } else if (valuationPrice > 0) {
            suggestPrice = valuationPrice;
        } else {
            suggestPrice = currentClose.doubleValue() * 0.95;
        }

        // 不能超过当前价的 95%
        suggestPrice = Math.min(suggestPrice, currentClose.doubleValue() * 0.95);
        // 不能低于 0
        suggestPrice = Math.max(suggestPrice, 0.01);

        double suggestLow = suggestPrice * 0.97;
        double suggestHigh = suggestPrice * 1.02;

        advice.put("suggestPrice", round2(suggestPrice));
        advice.put("suggestPriceLow", round2(suggestLow));
        advice.put("suggestPriceHigh", round2(suggestHigh));

        // 5. 止盈止损建议（基于ATR）
        Map<String, Object> profitLoss = calcStopLevels(currentClose, history);
        advice.put("stopLoss", profitLoss.get("stopLoss"));
        advice.put("stopLossPercent", profitLoss.get("stopLossPercent"));
        advice.put("takeProfit1", profitLoss.get("takeProfit1"));
        advice.put("takeProfit1Percent", profitLoss.get("takeProfit1Percent"));
        advice.put("takeProfit2", profitLoss.get("takeProfit2"));
        advice.put("takeProfit2Percent", profitLoss.get("takeProfit2Percent"));
        advice.put("atr", profitLoss.get("atr"));

        // 6. 风险提示
        List<String> risks = assessRisks(currentClose, suggestPrice, history, valuationLevels, techLevels);
        advice.put("risks", risks);
        advice.put("riskLevel", calcRiskLevel(risks));

        // 7. 买入理由摘要
        advice.put("buyReason", buildBuyReason(valuationLevels, techLevels, risks));

        return advice;
    }

    /**
     * 技术面支撑位计算
     */
    private Map<String, Object> calcTechLevels(List<StockDaily> history, BigDecimal currentClose) {
        Map<String, Object> levels = new LinkedHashMap<>();

        if (history.size() < 5) {
            levels.put("suggestTechPrice", currentClose.doubleValue() * 0.95);
            return levels;
        }

        // 取最近最多60条
        int startIdx = Math.max(0, history.size() - 60);
        List<StockDaily> recent = history.subList(startIdx, history.size());

        double close = currentClose.doubleValue();

        // MA5, MA10, MA20
        double ma5 = calcMA(recent, 5);
        double ma10 = calcMA(recent, 10);
        double ma20 = calcMA(recent, 20);

        levels.put("MA5", round2(ma5));
        levels.put("MA10", round2(ma10));
        levels.put("MA20", round2(ma20));

        // 布林带下轨 (20日均值 - 2×20日标准差)
        double bollLower = calcBollLower(recent, 20);
        levels.put("bollLower", round2(bollLower));

        // 近20日最低价
        double low20 = recent.stream()
                .filter(d -> d.getLowPrice() != null)
                .mapToDouble(d -> d.getLowPrice().doubleValue())
                .min().orElse(0);
        levels.put("low20", round2(low20));

        // 近60日最低价
        double low60 = recent.stream()
                .filter(d -> d.getLowPrice() != null)
                .mapToDouble(d -> d.getLowPrice().doubleValue())
                .min().orElse(0);
        levels.put("low60", round2(low60));

        // ATR 止损位 (Close - 2×ATR(14))
        double atr14 = calcATR(recent, 14);
        double atrStop = close - 2 * atr14;
        levels.put("atrStop", round2(atrStop));
        levels.put("atr14", round2(atr14));

        // 综合技术面价格：取 MA20、布林下轨、近20日低点、ATR止损 的最高值
        double techPrice = Math.max(Math.max(Math.max(ma20, bollLower), low20), atrStop);
        // 不能超过当前价
        techPrice = Math.min(techPrice, close);
        levels.put("suggestTechPrice", round2(techPrice));

        return levels;
    }

    /**
     * 估值面支撑位计算（按行业 PB/PE 中位数）
     */
    private Map<String, Object> calcValuationLevels(String code, String symbol, LocalDate screenDate) {
        Map<String, Object> levels = new LinkedHashMap<>();

        // 查询行业
        StockInfo stockInfo = stockInfoMapper.selectOne(
            new LambdaQueryWrapper<StockInfo>().eq(StockInfo::getCode, code));
        String industry = stockInfo != null ? stockInfo.getIndustry() : null;

        // 查询最新财务指标
        StockFinancialIndicator indicator = financialIndicatorMapper.selectOne(
            new LambdaQueryWrapper<StockFinancialIndicator>()
                .eq(StockFinancialIndicator::getCode, code)
                .le(StockFinancialIndicator::getEndDate, screenDate)
                .orderByDesc(StockFinancialIndicator::getEndDate)
                .last("LIMIT 1"));

        if (indicator == null) {
            levels.put("suggestValuationPrice", 0);
            return levels;
        }

        BigDecimal bps = indicator.getBps();
        BigDecimal eps = indicator.getEpsBasic();

        // BPS 支撑价
        double bpsPrice = (bps != null && bps.doubleValue() > 0) ? bps.doubleValue() : 0;
        levels.put("bpsPrice", round2(bpsPrice));
        levels.put("bps", bps);
        levels.put("eps", eps);

        // 查询行业 PB 中位数
        double industryPbMedian = calcIndustryMedian(industry, screenDate, "pb");
        double industryPeMedian = calcIndustryMedian(industry, screenDate, "pe_ttm");
        levels.put("industry", industry);
        levels.put("industryPbMedian", round2(industryPbMedian));
        levels.put("industryPeMedian", round2(industryPeMedian));

        // PB 支撑价 = BPS × 行业 PB 中位数
        double pbPrice = (bpsPrice > 0 && industryPbMedian > 0) ? bpsPrice * industryPbMedian : 0;
        levels.put("pbPrice", round2(pbPrice));

        // PE 支撑价 = EPS × 行业 PE 中位数
        double pePrice = (eps != null && eps.doubleValue() > 0 && industryPeMedian > 0)
                ? eps.doubleValue() * industryPeMedian : 0;
        levels.put("pePrice", round2(pePrice));

        // 综合估值面价格：取 PB支撑、PE支撑、BPS支撑 的最高有效值
        double valuationPrice = 0;
        if (pbPrice > 0) valuationPrice = Math.max(valuationPrice, pbPrice);
        if (pePrice > 0) valuationPrice = Math.max(valuationPrice, pePrice);
        if (bpsPrice > 0) valuationPrice = Math.max(valuationPrice, bpsPrice);

        levels.put("suggestValuationPrice", round2(valuationPrice));
        return levels;
    }

    /**
     * 计算行业 PB/PE 中位数
     */
    private double calcIndustryMedian(String industry, LocalDate screenDate, String field) {
        if (industry == null || industry.isEmpty()) return 0;

        try {
            // 查询同行业所有股票当日的 PB/PE
            List<StockInfo> peers = stockInfoMapper.selectList(
                new LambdaQueryWrapper<StockInfo>()
                    .eq(StockInfo::getIndustry, industry)
                    .isNotNull(StockInfo::getMarket));

            if (peers.size() < 3) return 0;

            List<String> codes = peers.stream().map(StockInfo::getCode).toList();
            List<StockDaily> dailyBars = stockDailyMapper.selectList(
                new LambdaQueryWrapper<StockDaily>()
                    .in(StockDaily::getCode, codes)
                    .eq(StockDaily::getTradeDate, screenDate));

            List<Double> values = dailyBars.stream()
                .map(d -> "pb".equals(field) ? d.getPb() : d.getPeTtm())
                .filter(Objects::nonNull)
                .map(BigDecimal::doubleValue)
                .filter(v -> v > 0 && v < 200) // 过滤异常值
                .sorted()
                .toList();

            if (values.isEmpty()) return 0;
            return values.get(values.size() / 2); // 中位数
        } catch (Exception e) {
            log.warn("Failed to calc industry median for {}: {}", industry, e.getMessage());
            return 0;
        }
    }

    /**
     * 止盈止损计算（基于ATR）
     */
    private Map<String, Object> calcStopLevels(BigDecimal currentClose, List<StockDaily> history) {
        Map<String, Object> levels = new LinkedHashMap<>();
        double close = currentClose.doubleValue();

        int startIdx = Math.max(0, history.size() - 20);
        List<StockDaily> recent = history.subList(startIdx, history.size());

        double atr = calcATR(recent, 14);
        levels.put("atr", round2(atr));

        // 止损位：当前价 - 2×ATR
        double stopLoss = close - 2 * atr;
        double stopLossPercent = atr > 0 ? -(2 * atr / close * 100) : -5;
        levels.put("stopLoss", round2(stopLoss));
        levels.put("stopLossPercent", round2(stopLossPercent));

        // 第一止盈位：当前价 + 2×ATR（盈亏比1:1）
        double tp1 = close + 2 * atr;
        double tp1Percent = atr > 0 ? (2 * atr / close * 100) : 5;
        levels.put("takeProfit1", round2(tp1));
        levels.put("takeProfit1Percent", round2(tp1Percent));

        // 第二止盈位：当前价 + 3×ATR（盈亏比1:1.5）
        double tp2 = close + 3 * atr;
        double tp2Percent = atr > 0 ? (3 * atr / close * 100) : 7.5;
        levels.put("takeProfit2", round2(tp2));
        levels.put("takeProfit2Percent", round2(tp2Percent));

        return levels;
    }

    /**
     * 风险评估
     */
    private List<String> risks = new ArrayList<>();

    private List<String> assessRisks(BigDecimal currentClose, double suggestPrice,
                                      List<StockDaily> history,
                                      Map<String, Object> valuationLevels,
                                      Map<String, Object> techLevels) {
        risks = new ArrayList<>();
        double close = currentClose.doubleValue();

        // 1. 当前价距离建议买入价的偏离度
        double deviation = (close - suggestPrice) / close * 100;
        if (deviation > 10) {
            risks.add("当前价距建议买入价偏离较大（" + round1(deviation) + "%），建议等待回调后再买入");
        } else if (deviation < 3) {
            risks.add("当前价接近建议买入价，可考虑分批建仓");
        }

        // 2. 波动率风险
        if (history.size() >= 20) {
            double vol20 = calcVolatility(history, 20);
            if (vol20 > 50) {
                risks.add("近20日年化波动率较高（" + round1(vol20) + "%），短期波动风险大");
            }
        }

        // 3. RSI 超买风险
        if (history.size() >= 14) {
            double rsi = calcRSI(history, 14);
            if (rsi > 80) {
                risks.add("RSI(14)=" + round1(rsi) + " 处于超买区域，短期回调风险较高");
            } else if (rsi > 70) {
                risks.add("RSI(14)=" + round1(rsi) + " 接近超买，注意短期风险");
            }
        }

        // 4. 估值风险
        Double industryPbMedian = (Double) valuationLevels.get("industryPbMedian");
        Double pbPrice = (Double) valuationLevels.get("pbPrice");
        if (pbPrice != null && pbPrice > 0 && close > pbPrice * 1.5) {
            risks.add("当前价高于行业PB中位数估值较多，存在高估风险");
        }

        // 5. 成交量风险（缩量下跌）
        if (history.size() >= 10) {
            List<StockDaily> last10 = history.subList(history.size() - 10, history.size());
            double vol5 = last10.stream()
                    .filter(d -> d.getVolume() != null)
                    .mapToLong(d -> d.getVolume())
                    .average().orElse(0);
            List<StockDaily> prev10 = history.size() >= 20
                    ? history.subList(history.size() - 20, history.size() - 10) : last10;
            double volPrev = prev10.stream()
                    .filter(d -> d.getVolume() != null)
                    .mapToLong(d -> d.getVolume())
                    .average().orElse(1);

            if (volPrev > 0 && vol5 < volPrev * 0.5) {
                risks.add("近期成交量明显萎缩（仅为前期的一半），市场关注度下降");
            }
        }

        // 6. 连续下跌风险
        if (history.size() >= 5) {
            int declineDays = 0;
            for (int i = history.size() - 1; i >= Math.max(0, history.size() - 10); i--) {
                StockDaily d = history.get(i);
                if (d.getChangePercent() != null && d.getChangePercent().doubleValue() < 0) {
                    declineDays++;
                } else {
                    break;
                }
            }
            if (declineDays >= 3) {
                risks.add("已连续" + declineDays + "天下跌，短期可能继续调整，建议观望");
            }
        }

        if (risks.isEmpty()) {
            risks.add("当前未见明显风险信号，技术面和估值面均较健康");
        }

        return risks;
    }

    private String calcRiskLevel(List<String> risks) {
        int highCount = 0;
        for (String r : risks) {
            if (r.contains("较高") || r.contains("较大") || r.contains("较多") || r.contains("连续")) {
                highCount++;
            }
        }
        if (highCount >= 2) return "high";
        if (highCount >= 1) return "medium";
        return "low";
    }

    /**
     * 生成买入理由摘要
     */
    private String buildBuyReason(Map<String, Object> valuationLevels, Map<String, Object> techLevels,
                                    List<String> risks) {
        StringBuilder reason = new StringBuilder();

        double pbPrice = toDouble(valuationLevels.get("pbPrice"));
        double bpsPrice = toDouble(valuationLevels.get("bpsPrice"));
        double ma20 = toDouble(techLevels.get("MA20"));
        double bollLower = toDouble(techLevels.get("bollLower"));

        List<String> reasons = new ArrayList<>();

        if (bpsPrice > 0) {
            reasons.add("每股净资产" + round2(bpsPrice) + "元提供安全边际");
        }
        if (pbPrice > 0) {
            reasons.add("行业PB估值支撑位" + round2(pbPrice) + "元");
        }
        if (ma20 > 0) {
            reasons.add("20日均线(" + round2(ma20) + "元)提供技术支撑");
        }
        if (bollLower > 0) {
            reasons.add("布林带下轨(" + round2(bollLower) + "元)为短期支撑");
        }

        if (reasons.isEmpty()) {
            reason.append("综合技术面和估值面计算的建议买入价");
        } else {
            reason.append(String.join("；", reasons));
        }

        // 风险摘要
        if (risks.size() > 0 && !risks.get(0).contains("未见明显风险")) {
            reason.append("。注意：").append(risks.get(0));
        }

        return reason.toString();
    }

    // ── 技术指标计算辅助方法 ──

    private double calcMA(List<StockDaily> bars, int period) {
        if (bars.size() < period) return bars.isEmpty() ? 0 : bars.get(bars.size() - 1).getClosePrice().doubleValue();
        double sum = 0;
        for (int i = bars.size() - period; i < bars.size(); i++) {
            if (bars.get(i).getClosePrice() != null) {
                sum += bars.get(i).getClosePrice().doubleValue();
            }
        }
        return sum / period;
    }

    private double calcBollLower(List<StockDaily> bars, int period) {
        if (bars.size() < period) return calcMA(bars, period) * 0.95;
        double ma = calcMA(bars, period);
        double sumSq = 0;
        for (int i = bars.size() - period; i < bars.size(); i++) {
            double c = bars.get(i).getClosePrice().doubleValue();
            sumSq += (c - ma) * (c - ma);
        }
        double std = Math.sqrt(sumSq / period);
        return ma - 2 * std;
    }

    private double calcATR(List<StockDaily> bars, int period) {
        if (bars.size() < period + 1) return bars.isEmpty() ? 0 : bars.get(bars.size() - 1).getClosePrice().doubleValue() * 0.02;
        double sum = 0;
        for (int i = bars.size() - period; i < bars.size(); i++) {
            sum += calcTrueRange(bars.get(i), bars.get(i - 1));
        }
        return sum / period;
    }

    private double calcTrueRange(StockDaily today, StockDaily yesterday) {
        double high = today.getHighPrice() != null ? today.getHighPrice().doubleValue() : 0;
        double low = today.getLowPrice() != null ? today.getLowPrice().doubleValue() : 0;
        double prevClose = yesterday.getClosePrice() != null ? yesterday.getClosePrice().doubleValue() : low;

        double tr1 = high - low;
        double tr2 = Math.abs(high - prevClose);
        double tr3 = Math.abs(low - prevClose);
        return Math.max(tr1, Math.max(tr2, tr3));
    }

    private double calcVolatility(List<StockDaily> bars, int period) {
        if (bars.size() < period + 1) return 0;
        List<Double> returns = new ArrayList<>();
        for (int i = bars.size() - period; i < bars.size(); i++) {
            double prev = bars.get(i - 1).getClosePrice().doubleValue();
            double curr = bars.get(i).getClosePrice().doubleValue();
            if (prev > 0) {
                returns.add(Math.log(curr / prev));
            }
        }
        if (returns.isEmpty()) return 0;
        double mean = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = returns.stream().mapToDouble(r -> (r - mean) * (r - mean)).average().orElse(0);
        return Math.sqrt(variance) * Math.sqrt(252) * 100; // 年化百分比
    }

    private double calcRSI(List<StockDaily> bars, int period) {
        if (bars.size() < period + 1) return 50;
        double avgGain = 0, avgLoss = 0;
        // 初始平均值
        for (int i = bars.size() - period; i < bars.size(); i++) {
            double change = bars.get(i).getClosePrice().doubleValue()
                    - bars.get(i - 1).getClosePrice().doubleValue();
            if (change > 0) avgGain += change;
            else avgLoss += Math.abs(change);
        }
        avgGain /= period;
        avgLoss /= period;
        if (avgLoss == 0) return 100;
        double rs = avgGain / avgLoss;
        return 100 - 100 / (1 + rs);
    }

    private double toDouble(Object val) {
        if (val == null) return 0;
        if (val instanceof Number) return ((Number) val).doubleValue();
        return 0;
    }

    private double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private double round1(double v) { return Math.round(v * 10.0) / 10.0; }
}
