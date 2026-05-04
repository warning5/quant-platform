package com.quant.platform.stock.analysis.service;

import com.quant.platform.stock.analysis.domain.*;
import com.quant.platform.stock.analysis.engine.TradingSignalEngine;
import com.quant.platform.stock.analysis.mapper.AnalysisChMapper;
import com.quant.platform.stock.analysis.mapper.StockAnalysisMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 个股分析服务
 * 整合四维度数据，调用规则引擎生成评分和建议
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnBean(AnalysisChMapper.class)
public class AnalysisService {
    
    private final AnalysisChMapper analysisChMapper;
    
    private final StockAnalysisMapper stockAnalysisMapper;
    
    private final TradingSignalEngine tradingSignalEngine;
    
    /**
     * 获取个股分析总览
     */
    public AnalysisOverview getOverview(String code) {
        log.info("开始分析个股: code={}", code);
        
        AnalysisOverview overview = new AnalysisOverview();
        overview.setCode(code);
        
        // 1. 获取股票基本信息
        java.util.Map<String, Object> stockInfo = stockAnalysisMapper.selectStockInfo(code);
        if (stockInfo != null) {
            overview.setName((String) stockInfo.get("name"));
        }
        
        // 1.1 从 ClickHouse 获取最新价格（CH 有数据，MySQL stock_daily 为空）
        java.util.Map<String, Object> chPrice = analysisChMapper.selectLatestDailyBar(code);
        if (chPrice != null) {
            BigDecimal closePrice = (BigDecimal) chPrice.get("close_price");
            BigDecimal changePercent = (BigDecimal) chPrice.get("change_percent");
            if (closePrice != null) {
                overview.setPrice(closePrice.setScale(2, RoundingMode.HALF_UP).toString());
            }
            if (changePercent != null) {
                overview.setChangePercent(changePercent.setScale(2, RoundingMode.HALF_UP) + "%");
            }
        } else {
            // fallback 到 MySQL（如果 CH 没数据）
            if (stockInfo != null) {
                overview.setPrice(stockInfo.get("close_price") != null ? 
                        new BigDecimal(stockInfo.get("close_price").toString()).setScale(2, RoundingMode.HALF_UP).toString() : "-");
                overview.setChangePercent(stockInfo.get("change_percent") != null ?
                        new BigDecimal(stockInfo.get("change_percent").toString()).setScale(2, RoundingMode.HALF_UP) + "%" : "-");
            }
        }
        
        // 2. 技术面信号（从CH查询缠论因子）
        TechSignal techSignal = analysisChMapper.selectLatestTechSignal(code);
        normalizeChanStrings(techSignal);
        if (techSignal == null) {
            techSignal = new TechSignal();
        }
        
        // 从 stock_daily 计算补充技术指标（均线多头/MACD金叉/RSI）
        List<DailyBarRow> techBars = analysisChMapper.selectRecentDailyBars(code, 120);
        supplementTechIndicators(techSignal, techBars);
        
        // 计算支撑/阻力位（近20日最低价/最高价）
        // techBars 按 trade_date ASC 排序，最近20天取最后20条
        BigDecimal supportPrice = null;
        BigDecimal resistancePrice = null;
        if (techBars != null && !techBars.isEmpty()) {
            int start = Math.max(0, techBars.size() - 20);
            for (int i = start; i < techBars.size(); i++) {
                DailyBarRow bar = techBars.get(i);
                if (bar.getLowPrice() != null) {
                    if (supportPrice == null || bar.getLowPrice().compareTo(supportPrice) < 0) {
                        supportPrice = bar.getLowPrice();
                    }
                }
                if (bar.getHighPrice() != null) {
                    if (resistancePrice == null || bar.getHighPrice().compareTo(resistancePrice) > 0) {
                        resistancePrice = bar.getHighPrice();
                    }
                }
            }
        }
        
        // 3. 资金面信号（从CH计算量比/换手率）
        MoneyFlowSignal moneySignal = calcMoneyFlowSignal(code);
        
        // 4. 事件面信号（从MySQL查询涨跌停等，强势股从CH计算）
        SentimentSignal sentimentSignal = stockAnalysisMapper.selectSentimentSignal(code);
        if (sentimentSignal == null) {
            sentimentSignal = new SentimentSignal();
        }
        // 用 CH 的 stock_daily 计算强势股（MySQL stock_daily 为空）
        BigDecimal ret20d = analysisChMapper.select20dReturn(code);
        if (ret20d != null) {
            sentimentSignal.setIsStrongStock(ret20d.doubleValue() > 30);
        }
        
        // 5. 基本面信号（从MySQL查 roe/增速等，pe/pb 从CH补充）
        FundamentalSignal fundamentalSignal = stockAnalysisMapper.selectFundamentalSignal(code);
        if (fundamentalSignal == null) {
            fundamentalSignal = new FundamentalSignal();
        }
        // 从 CH 最新日线补充 pe_ttm / pb（MySQL stock_daily 为空）
        if (chPrice != null) {
            if (chPrice.get("pe_ttm") != null) {
                fundamentalSignal.setPeTtm((BigDecimal) chPrice.get("pe_ttm"));
            }
            if (chPrice.get("pb") != null) {
                fundamentalSignal.setPb((BigDecimal) chPrice.get("pb"));
            }
        }
        
        // 6. 调用规则引擎评分
        TradingSignal signal = tradingSignalEngine.evaluate(
                code, 
                overview.getName() != null ? overview.getName() : code,
                techSignal, moneySignal, sentimentSignal, fundamentalSignal,
                supportPrice, resistancePrice);
        
        // 7. 填充总览
        overview.setTotalScore(signal.getTotalScore());
        overview.setAction(signal.getAction());
        overview.setActionName(signal.getActionName());
        overview.setPosition(signal.getPosition());
        overview.setTiming(signal.getTiming());
        overview.setRisks(signal.getRisks());
        overview.setScoreDetails(signal.getScoreDetails());
        // 回写各维度分数到 Signal 对象
        for (ScoreDetail detail : signal.getScoreDetails()) {
            switch (detail.getDimension()) {
                case "tech" -> techSignal.setTechScore(detail.getScore());
                case "money" -> moneySignal.setMoneyScore(detail.getScore());
                case "sentiment" -> sentimentSignal.setSentimentScore(detail.getScore());
                case "fundamental" -> fundamentalSignal.setFundamentalScore(detail.getScore());
            }
        }
        overview.setTechSignal(techSignal);
        overview.setMoneySignal(moneySignal);
        overview.setSentimentSignal(sentimentSignal);
        overview.setFundamentalSignal(fundamentalSignal);

        // 7.1 生成综合分析结论
        overview.setConclusion(buildConclusion(overview, signal));

        log.info("个股分析完成: code={}, totalScore={}, action={}",
                code, overview.getTotalScore(), overview.getAction());

        return overview;
    }

    /**
     * 根据四维度评分生成文字结论
     */
    private String buildConclusion(AnalysisOverview o, TradingSignal signal) {
        StringBuilder sb = new StringBuilder();
        sb.append("【").append(o.getName()).append("(").append(o.getCode()).append(")】");

        // 操作建议
        sb.append("综合评分").append(signal.getTotalScore()).append("分，");
        sb.append("建议【").append(signal.getActionName()).append("】");
        sb.append("，建议仓位").append(signal.getPosition()).append("%。");

        // 四维度简述
        if (o.getScoreDetails() != null) {
            for (var d : o.getScoreDetails()) {
                String level;
                double pct = (double) d.getScore() / d.getMaxScore();
                if (pct >= 0.8) level = "强";
                else if (pct >= 0.6) level = "较强";
                else if (pct >= 0.4) level = "一般";
                else if (pct >= 0.2) level = "较弱";
                else level = "弱";
                sb.append(d.getDimensionName()).append("表现").append(level).append("；");
            }
        }

        // 风险提示
        if (o.getRisks() != null && !o.getRisks().isEmpty()) {
            sb.append("注意：").append(o.getRisks());
        }
        return sb.toString();
    }
    
    /**
     * 计算资金面信号（量比、换手率偏离）
     */
    private MoneyFlowSignal calcMoneyFlowSignal(String code) {
        MoneyFlowSignal signal = new MoneyFlowSignal();
        
        // 获取最近30日数据
        List<DailyBarRow> bars = analysisChMapper.selectRecentDailyBars(code, 30);
        if (bars == null || bars.isEmpty()) {
            return signal;
        }
        
        // 最新一日数据
        DailyBarRow latest = bars.get(bars.size() - 1);
        
        // 计算量比（当日成交量 / 5日均量）
        if (latest.getVolume() != null && bars.size() >= 6) {
            long sum5 = 0;
            for (int i = bars.size() - 6; i < bars.size() - 1; i++) {
                if (bars.get(i).getVolume() != null) {
                    sum5 += bars.get(i).getVolume();
                }
            }
            double avg5 = sum5 / 5.0;
            if (avg5 > 0) {
                signal.setVolumeRatio(BigDecimal.valueOf(latest.getVolume() / avg5));
            }
        }
        
        // 计算换手率偏离（当日换手率 - 20日平均换手率）
        if (latest.getTurnoverRate() != null && bars.size() >= 21) {
            double sum20 = 0;
            int count = 0;
            for (int i = bars.size() - 21; i < bars.size() - 1; i++) {
                if (bars.get(i).getTurnoverRate() != null) {
                    sum20 += bars.get(i).getTurnoverRate().doubleValue();
                    count++;
                }
            }
            if (count > 0) {
                double avg20 = sum20 / count;
                signal.setTurnoverDeviation(
                        latest.getTurnoverRate().subtract(BigDecimal.valueOf(avg20)));
                signal.setTurnoverRate5d(BigDecimal.valueOf(avg20));
            }
            signal.setTurnoverRate(latest.getTurnoverRate());
        }
        
        // 判断量能状态
        if (signal.getVolumeRatio() != null) {
            double vr = signal.getVolumeRatio().doubleValue();
            if (vr >= 2.0) {
                signal.setVolumeStatus("HIGH");
            } else if (vr >= 1.2) {
                signal.setVolumeStatus("MEDIUM");
            } else {
                signal.setVolumeStatus("LOW");
            }
        }
        
        return signal;
    }
    
    /**
     * 从日线数据计算补充技术指标（均线多头/MACD金叉/RSI）
     * 优先用 factor_value 的值，缺失时从 stock_daily 行情计算
     */
    private void supplementTechIndicators(TechSignal tech, List<DailyBarRow> bars) {
        if (bars == null || bars.size() < 30) return;
        
        int n = bars.size();
        double[] closes = new double[n];
        for (int i = 0; i < n; i++) {
            closes[i] = bars.get(i).getClosePrice().doubleValue();
        }
        
        // --- 均线多头：MA5 > MA10 > MA20 > MA60 ---
        if (tech.getMaBullish() == null && n >= 60) {
            double ma5 = avg(closes, n - 5, n);
            double ma10 = avg(closes, n - 10, n);
            double ma20 = avg(closes, n - 20, n);
            double ma60 = avg(closes, n - 60, n);
            tech.setMaBullish(ma5 > ma10 && ma10 > ma20 && ma20 > ma60);
        }
        
        // --- MACD金叉：DIF 从下方穿越 DEA ---
        if (tech.getMacdGolden() == null && n >= 35) {
            double[] ema12 = calcEma(closes, 12);
            double[] ema26 = calcEma(closes, 26);
            double[] dif = new double[n];
            for (int i = 0; i < n; i++) dif[i] = ema12[i] - ema26[i];
            double[] dea = calcEma(dif, 9);
            // 最近1根：DIF > DEA，前1根：DIF <= DEA → 金叉
            boolean golden = dif[n - 1] > dea[n - 1] && dif[n - 2] <= dea[n - 2];
            tech.setMacdGolden(golden);
        }
        
        // --- RSI14 ---
        if (tech.getRsi() == null && n >= 15) {
            double rsi = calcRsi(closes, 14);
            tech.setRsi(BigDecimal.valueOf(rsi).setScale(2, RoundingMode.HALF_UP));
        }
    }
    
    private double avg(double[] arr, int from, int to) {
        double sum = 0;
        for (int i = from; i < to; i++) sum += arr[i];
        return sum / (to - from);
    }
    
    private double[] calcEma(double[] data, int period) {
        double[] ema = new double[data.length];
        double k = 2.0 / (period + 1);
        ema[0] = data[0];
        for (int i = 1; i < data.length; i++) {
            ema[i] = data[i] * k + ema[i - 1] * (1 - k);
        }
        return ema;
    }
    
    private double calcRsi(double[] closes, int period) {
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

    /**
     * 将 Chan 因子数据库数字值转换为评分引擎期望的字符串
     * DB 存储格式（FactorService screen 配置一致）：
     *   CHAN_PEN_DIR:  1=上升笔, -1=下降笔
     *   CHAN_TREND:    1=上涨, 0=盘整, -1=下跌
     *   CHAN_BUY_SELL: >0=买(1/2/3买), <0=卖(-1/-2/-3卖)
     *   CHAN_HUB_POS:  0.0~1.0 浮点，按阈值转为 UPPER/MIDDLE/LOWER
     */
    private void normalizeChanStrings(TechSignal tech) {
        if (tech == null) return;

        // CHAN_PEN_DIR: 前端期望数字 1/-1，但 BeanPropertyRowMapper 返回 "1.0"/"-1.0"
        // 清理为整数格式 "1"/"-1"
        if (tech.getPenDir() != null) {
            try {
                int v = (int) Math.round(Double.parseDouble(tech.getPenDir()));
                tech.setPenDir(String.valueOf(v));
            } catch (NumberFormatException ignored) {}
        }

        // CHAN_TREND: 1 → "BULLISH", 0 → "SIDEWAYS", -1 → "BEARISH"
        if (tech.getTrend() != null) {
            try {
                int v = (int) Math.round(Double.parseDouble(tech.getTrend()));
                tech.setTrend(v == 1 ? "BULLISH" : v == 0 ? "SIDEWAYS" : v == -1 ? "BEARISH" : tech.getTrend());
            } catch (NumberFormatException ignored) {}
        }

        // CHAN_BUY_SELL: >0 → "BUY", <0 → "SELL", 0 → "HOLD"
        if (tech.getChanSignal() != null) {
            try {
                double v = Double.parseDouble(tech.getChanSignal());
                tech.setChanSignal(v > 0 ? "BUY" : v < 0 ? "SELL" : "HOLD");
            } catch (NumberFormatException ignored) {}
        }

        // CHAN_HUB_POS: 浮点 0.0~1.0 → LOWER/MIDDLE/UPPER
        if (tech.getHubPos() != null) {
            try {
                double v = Double.parseDouble(tech.getHubPos());
                tech.setHubPos(v < 0.33 ? "LOWER" : v < 0.66 ? "MIDDLE" : "UPPER");
            } catch (NumberFormatException ignored) {}
        }
    }

    /**
     * 获取评分规则说明
     */
    public List<TradingSignalEngine.ScoreRule> getScoreRules() {
        return tradingSignalEngine.getScoreRules();
    }
}
