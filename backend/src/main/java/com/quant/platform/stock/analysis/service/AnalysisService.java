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
import java.util.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

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

        // 5.1 研报信号（机构观点）
        ResearchSignal researchSignal = stockAnalysisMapper.selectResearchSignal(code);
        if (researchSignal == null) {
            researchSignal = new ResearchSignal();
        }
        // 计算研报评分（0-5分，由最新评级映射），注入到基本面评分
        int researchScore = calcResearchScore(researchSignal.getLatestRating());
        researchSignal.setResearchScore(researchScore);
        fundamentalSignal.setResearchScore(researchScore);
        // 查询最近5条研报明细
        researchSignal.setRecentReports(stockAnalysisMapper.selectRecentResearchReports(code));
        
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
        overview.setResearchSignal(researchSignal);

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
     * 研报评级 → 评分（0-5分）
     * 买入=5，增持=3，中性=1，减持/卖出=0
     */
    private int calcResearchScore(String rating) {
        if (rating == null || rating.isEmpty()) return 0;
        return switch (rating) {
            case "买入" -> 5;
            case "增持", "推荐", "强烈推荐" -> 3;
            case "中性", "持有" -> 1;
            default -> 0;
        };
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

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 获取个股研报完整分析（独立 Tab 用）
     * 包含：评级趋势、EPS一致预期、覆盖强度、近期研报列表
     */
    public Map<String, Object> getResearchAnalysis(String code) {
        log.info("获取研报分析: code={}", code);
        Map<String, Object> result = new LinkedHashMap<>();

        // 1. 近期研报列表（含 EPS 预测）
        List<Map<String, Object>> reports = stockAnalysisMapper.selectRecentReportsWithEps(code, 10);
        result.put("recentReports", reports != null ? reports : List.of());

        // 2. 评级趋势（近6个月，按月+评级分组）
        List<Map<String, Object>> rawRatingTrend = stockAnalysisMapper.selectRatingTrend(code);
        result.put("ratingTrend", pivotRatingTrend(rawRatingTrend));

        // 3. 研报数量月度趋势
        List<Map<String, Object>> reportTrend = stockAnalysisMapper.selectReportCountByMonth(code);
        result.put("reportTrend", reportTrend);

        // 4. 覆盖强度
        Map<String, Object> coverage = new LinkedHashMap<>();
        List<Map<String, Object>> institutions = stockAnalysisMapper.selectCoverageInstitutions(code);
        coverage.put("institutionCount", institutions.size());
        coverage.put("institutions", institutions);
        String firstDate = stockAnalysisMapper.selectFirstCoverageDate(code);
        coverage.put("firstCoverageDate", firstDate != null ? firstDate : "");
        // 近期总研报数
        int recent90d = 0;
        if (reports != null) recent90d = reports.size();
        // 从 reportTrend 汇总近6个月总数
        int total6m = 0;
        if (reportTrend != null) {
            for (Map<String, Object> m : reportTrend) {
                Object cnt = m.get("cnt");
                if (cnt instanceof Number) total6m += ((Number) cnt).intValue();
            }
        }
        coverage.put("reportCount6m", total6m);
        coverage.put("reportCount90d", recent90d);
        result.put("coverage", coverage);

        // 5. EPS 一致预期（从 eps_forecast JSON 解析聚合）
        result.put("epsForecast", calcEpsConsensus(reports));

        // 6. 最新评级 + 买入占比
        ResearchSignal signal = stockAnalysisMapper.selectResearchSignal(code);
        Map<String, Object> ratingSummary = new LinkedHashMap<>();
        ratingSummary.put("latestRating", signal != null ? signal.getLatestRating() : null);
        ratingSummary.put("reportCount", signal != null ? signal.getReportCount() : 0);
        // 计算买入+增持占比
        int buyCount = 0;
        int ratedCount = 0;
        if (reports != null) {
            for (Map<String, Object> r : reports) {
                Object rat = r.get("rating");
                if (rat != null && !"".equals(rat.toString())) {
                    ratedCount++;
                    String rt = rat.toString();
                    if ("买入".equals(rt) || "增持".equals(rt)) buyCount++;
                }
            }
        }
        double buyRatio = ratedCount > 0 ? Math.round((double) buyCount / ratedCount * 10000) / 100.0 : 0;
        ratingSummary.put("buyRatio", buyRatio);
        ratingSummary.put("ratedCount", ratedCount);
        // 评级共识描述
        if (buyRatio >= 80) ratingSummary.put("consensusDesc", "强烈看多");
        else if (buyRatio >= 60) ratingSummary.put("consensusDesc", "偏多");
        else if (buyRatio >= 40) ratingSummary.put("consensusDesc", "中性偏多");
        else if (buyRatio >= 20) ratingSummary.put("consensusDesc", "中性偏空");
        else ratingSummary.put("consensusDesc", "偏空");
        result.put("ratingSummary", ratingSummary);

        return result;
    }

    /**
     * 将原始评级趋势数据按月份 pivot 为图表友好的格式
     * 原始: [{month:"2025-11", rating:"买入", cnt:3}, {month:"2025-11", rating:"增持", cnt:2}, ...]
     * 输出: [{month:"2025-11", 买入:3, 增持:2, ...}]
     */
    private List<Map<String, Object>> pivotRatingTrend(List<Map<String, Object>> raw) {
        Map<String, Map<String, Object>> byMonth = new LinkedHashMap<>();
        for (Map<String, Object> row : raw) {
            String month = row.get("month") != null ? row.get("month").toString() : "";
            String rating = row.get("rating") != null ? row.get("rating").toString() : "无";
            Number cnt = (Number) row.get("cnt");

            Map<String, Object> monthData = byMonth.computeIfAbsent(month, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("month", k);
                return m;
            });
            monthData.merge(rating, cnt != null ? cnt.intValue() : 0,
                    (oldVal, newVal) -> ((Number) oldVal).intValue() + ((Number) newVal).intValue());
        }
        return new ArrayList<>(byMonth.values());
    }

    /**
     * 计算 EPS 一致预期
     * 解析多份研报的 eps_forecast JSON，按年度聚合取平均
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> calcEpsConsensus(List<Map<String, Object>> reports) {
        Map<String, Object> consensus = new LinkedHashMap<>();
        if (reports == null || reports.isEmpty()) return consensus;

        // year -> [eps_values] / [pe_values]
        Map<String, List<Double>> epsByYear = new LinkedHashMap<>();
        Map<String, List<Double>> peByYear = new LinkedHashMap<>();

        for (Map<String, Object> r : reports) {
            Object epsRaw = r.get("epsForecast");
            if (epsRaw == null || epsRaw.toString().isBlank()) continue;
            try {
                Map<String, Object> forecast = objectMapper.readValue(epsRaw.toString(),
                        new TypeReference<Map<String, Object>>() {});
                for (Map.Entry<String, Object> entry : forecast.entrySet()) {
                    String year = entry.getKey();
                    Object val = entry.getValue();
                    if (val instanceof Map) {
                        Map<String, Object> detail = (Map<String, Object>) val;
                        Object epsObj = detail.get("eps");
                        Object peObj = detail.get("pe");
                        if (epsObj instanceof Number) {
                            epsByYear.computeIfAbsent(year, k -> new ArrayList<>())
                                    .add(((Number) epsObj).doubleValue());
                        }
                        if (peObj instanceof Number) {
                            peByYear.computeIfAbsent(year, k -> new ArrayList<>())
                                    .add(((Number) peObj).doubleValue());
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // 取各年份平均
        for (String year : epsByYear.keySet()) {
            List<Double> vals = epsByYear.get(year);
            double avgEps = vals.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            avgEps = BigDecimal.valueOf(avgEps).setScale(2, RoundingMode.HALF_UP).doubleValue();
            Map<String, Object> yearData = new LinkedHashMap<>();
            yearData.put("year", year);
            yearData.put("avgEps", avgEps);
            yearData.put("sourceCount", vals.size());

            List<Double> peVals = peByYear.get(year);
            if (peVals != null && !peVals.isEmpty()) {
                double avgPe = peVals.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                avgPe = BigDecimal.valueOf(avgPe).setScale(1, RoundingMode.HALF_UP).doubleValue();
                yearData.put("avgPe", avgPe);
            }
            consensus.put(year, yearData);
        }

        return consensus;
    }

    /**
     * 股票联想搜索（按代码或名称模糊匹配）
     * @param keyword 搜索关键词（代码或名称片段）
     * @return 匹配的股票列表（code, name, market）
     */
    public List<Map<String, Object>> searchStocks(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return stockAnalysisMapper.searchStocks(keyword.trim());
    }
}
