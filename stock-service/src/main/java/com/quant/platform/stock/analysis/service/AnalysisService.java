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

/**
 * 个股分析服务
 * 整合四维度数据，调用规则引擎生成评分和建议
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnBean(AnalysisChMapper.class)
public class AnalysisService {

    // ==================== 本体自持依赖 ====================

    private final AnalysisChMapper analysisChMapper;
    private final StockAnalysisMapper stockAnalysisMapper;
    private final NewsMapper newsMapper;
    private final BidAskMapper bidAskMapper;
    private final TradingSignalEngine tradingSignalEngine;
    private final ClickHouseStockService clickHouseStockService;

    // ==================== 共享辅助（编码归一化/最新交易日/中位数/格式化） ====================
    private final AnalysisCommonService analysisCommon;

    // ==================== 技术指标（MA/EMA/RSI/量价背离/目标价） ====================
    private final TechIndicatorService techIndicatorService;
    public List<TradingSignalEngine.ScoreRule> getScoreRules() { return techIndicatorService.getScoreRules(); }
    private void supplementTechIndicators(TechSignal tech, List<DailyBarRow> bars) { techIndicatorService.supplementTechIndicators(tech, bars); }
    private void detectVolumePriceDivergence(TechSignal tech, String code) { techIndicatorService.detectVolumePriceDivergence(tech, code); }
    private BigDecimal calcTargetPrice2(BigDecimal currentPrice, FundamentalSignal fs) { return techIndicatorService.calcTargetPrice2(currentPrice, fs); }
    private BigDecimal calcExtremeTargetPrice(BigDecimal currentPrice, FundamentalSignal fs, Map<String, Object> stockInfo) { return techIndicatorService.calcExtremeTargetPrice(currentPrice, fs, stockInfo); }

    // ==================== 资金面（主力净流入/资金流历史/日度资金分） ====================
    private final MoneyFlowService moneyFlowService;
    private MoneyFlowSignal calcMoneyFlowSignal(String code) { return moneyFlowService.calcMoneyFlowSignal(code); }
    public Map<String, Object> getMoneyFlowHistory(String code, int days) { return moneyFlowService.getMoneyFlowHistory(code, days); }

    // ==================== 研报与股东（EPS一致预期/评级趋势/股东结构） ====================
    private final ResearchAnalysisService researchAnalysisService;
    private int calcResearchScore(String rating) { return researchAnalysisService.calcResearchScore(rating); }
    public Map<String, Object> getResearchAnalysis(String code) { return researchAnalysisService.getResearchAnalysis(code); }
    public Map<String, Object> getShareholderStructure(String code) { return researchAnalysisService.getShareholderStructure(code); }

    // ==================== 板块行业（排行/成分股/相关性/热门板块） ====================
    private final SectorAnalysisService sectorAnalysisService;
    public Map<String, Object> getSectorRanking() { return sectorAnalysisService.getSectorRanking(); }
    public List<Map<String, Object>> getConceptStocks(String conceptName, String sortBy, String sortOrder) { return sectorAnalysisService.getConceptStocks(conceptName, sortBy, sortOrder); }
    public List<Map<String, Object>> getIndustryStocks(String industry, String sortBy, String sortOrder) { return sectorAnalysisService.getIndustryStocks(industry, sortBy, sortOrder); }
    public Map<String, Object> getIndustryCorrelation(String code) { return sectorAnalysisService.getIndustryCorrelation(code); }
    public Map<String, Object> getHotSectors() { return sectorAnalysisService.getHotSectors(); }
    public Map<String, Object> getHotSectorDetail(String conceptName) { return sectorAnalysisService.getHotSectorDetail(conceptName); }

    // ==================== 事件（涨停分析/大宗交易） ====================
    private final EventAnalysisService eventAnalysisService;
    public Map<String, Object> getLimitUpAnalysis(String code) { return eventAnalysisService.getLimitUpAnalysis(code); }
    public Map<String, Object> getBlockTradeAnalysis(String code) { return eventAnalysisService.getBlockTradeAnalysis(code); }

    // ==================== 行情数据（K线/缠论/相对强度/估值分位/搜索） ====================
    private final QuoteDataService quoteDataService;
    public double[][] fetchKlineData(String code, int days) { return quoteDataService.fetchKlineData(code, days); }
    public Map<String, double[][]> batchFetchKlineData(int days) { return quoteDataService.batchFetchKlineData(days); }
    public Map<String, Object> getChanChart(String code) { return quoteDataService.getChanChart(code); }
    public List<Map<String, Object>> getKLine(String code, int days) { return quoteDataService.getKLine(code, days); }
    public Map<String, Object> getStockPerformance(String code) { return quoteDataService.getStockPerformance(code); }
    public Map<String, Object> getRelativeStrength(String code) { return quoteDataService.getRelativeStrength(code); }
    public Map<String, Object> getPeerComparison(String code) { return quoteDataService.getPeerComparison(code); }
    public Map<String, Object> getValuationPercentile(String code, int years) { return quoteDataService.getValuationPercentile(code, years); }
    public List<Map<String, Object>> searchStocks(String keyword) { return quoteDataService.searchStocks(keyword); }

    // ==================== 总览风险（尾部风险/催化剂/多空辩论/多分析师评分） ====================
    private final OverviewRiskService overviewRiskService;
    private List<TailRisk> buildTailRisks(String code, FundamentalSignal fs, Map<String, Object> stockInfo, BigDecimal currentPrice) { return overviewRiskService.buildTailRisks(code, fs, stockInfo, currentPrice); }
    private List<CatalystItem> buildCatalysts(String code, FundamentalSignal fs, SentimentSignal ss, ResearchSignal rs) { return overviewRiskService.buildCatalysts(code, fs, ss, rs); }
    private void calcMultiAnalystScores(AnalysisOverview overview, TechSignal tech, MoneyFlowSignal money, SentimentSignal sentiment, FundamentalSignal fundamental, boolean isBlueChip, BigDecimal currentPrice, BigDecimal supportPrice, BigDecimal resistancePrice) { overviewRiskService.calcMultiAnalystScores(overview, tech, money, sentiment, fundamental, isBlueChip, currentPrice, supportPrice, resistancePrice); }
    private void buildBullBearDebate(AnalysisOverview overview) { overviewRiskService.buildBullBearDebate(overview); }

    // ==================== 总览装配（结论/执行计划/仓位/风险等级） ====================
    private final OverviewAssembler overviewAssembler;
    private String buildConclusion(AnalysisOverview o, TradingSignal signal) { return overviewAssembler.buildConclusion(o, signal); }
    private String buildExecutionPlan(TradingSignal signal, BigDecimal currentPrice, String targetPrice, String stopLossPrice, String targetPrice2, String riskLevel, String confidenceLevel) { return overviewAssembler.buildExecutionPlan(signal, currentPrice, targetPrice, stopLossPrice, targetPrice2, riskLevel, confidenceLevel); }
    private String calcSuggestedPositionPct(TradingSignal signal, String confidenceLevel, FundamentalSignal fundamental, boolean isBlueChip) { return overviewAssembler.calcSuggestedPositionPct(signal, confidenceLevel, fundamental, isBlueChip); }
    private String calcReducePriceRange(BigDecimal currentPrice, BigDecimal resistancePrice, TradingSignal signal) { return overviewAssembler.calcReducePriceRange(currentPrice, resistancePrice, signal); }
    private String calcRiskLevel(TradingSignal signal, FundamentalSignal fundamental, MoneyFlowSignal money, BigDecimal currentPrice) { return overviewAssembler.calcRiskLevel(signal, fundamental, money, currentPrice); }
    private String calcConfidenceLevel(FundamentalSignal fundamental, ResearchSignal research) { return overviewAssembler.calcConfidenceLevel(fundamental, research); }
    private int calcNewsScore(int positive, int negative, int tagged, double sentimentBias) { return overviewAssembler.calcNewsScore(positive, negative, tagged, sentimentBias); }

    
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

        // 1.05 判断是否大盘蓝筹（总市值 ≥ 1000亿）
        final boolean isBlueChip = stockInfo != null
                && stockInfo.get("total_market_cap") instanceof BigDecimal
                && ((BigDecimal) stockInfo.get("total_market_cap")).compareTo(new BigDecimal("100000000000")) >= 0;

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
        
        // 2. 技术面信号（从CH获取最新因子日期，缠论因子已废弃由兜底逻辑提供）
        TechSignal techSignal = analysisChMapper.selectLatestTechSignal(code);
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

        // 计算ATR（14日平均真实波幅）
        BigDecimal atr = null;
        if (techBars != null && techBars.size() >= 14) {
            List<DailyBarRow> atrBars = techBars.subList(techBars.size() - 14, techBars.size());
            double sum = 0;
            for (int i = 1; i < atrBars.size(); i++) {
                DailyBarRow cur = atrBars.get(i);
                DailyBarRow pre = atrBars.get(i - 1);
                double tr1 = cur.getHighPrice().subtract(cur.getLowPrice()).doubleValue();
                double tr2 = Math.abs(cur.getHighPrice().subtract(pre.getClosePrice()).doubleValue());
                double tr3 = Math.abs(cur.getLowPrice().subtract(pre.getClosePrice()).doubleValue());
                sum += Math.max(tr1, Math.max(tr2, tr3));
            }
            atr = BigDecimal.valueOf(sum / 13.0);
        }

        // ── P1-2: 计算风险/流动性基础指标（供 RecommendationService 使用）──
        if (techBars != null && techBars.size() >= 20) {
            List<DailyBarRow> recent20 = techBars.subList(techBars.size() - 20, techBars.size());

            // a) 最大回撤（近20日）
            double maxDd = 0.0;
            double peak = Double.NEGATIVE_INFINITY;
            for (DailyBarRow bar : recent20) {
                if (bar.getClosePrice() != null) {
                    double cp = bar.getClosePrice().doubleValue();
                    if (cp > peak) peak = cp;
                    if (peak > 0) {
                        double dd = (cp - peak) / peak;
                        if (dd < maxDd) maxDd = dd;
                    }
                }
            }
            overview.setMaxDrawdown(maxDd < 0 ? maxDd : 0.0);

            // b) 20日波动率（收益率标准差，年化前原始值）
            List<Double> rets = new java.util.ArrayList<>();
            for (int i = 1; i < recent20.size(); i++) {
                if (recent20.get(i).getClosePrice() != null && recent20.get(i - 1).getClosePrice() != null) {
                    double prev = recent20.get(i - 1).getClosePrice().doubleValue();
                    double curr = recent20.get(i).getClosePrice().doubleValue();
                    if (prev > 0) rets.add((curr - prev) / prev);
                }
            }
            if (!rets.isEmpty()) {
                double mean = rets.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                double variance = rets.stream().mapToDouble(r -> (r - mean) * (r - mean)).average().orElse(0);
                overview.setVolatility20d(Math.sqrt(variance));
            }

            // c) 20日均成交额
            double amtSum = 0;
            int amtCnt = 0;
            for (DailyBarRow bar : recent20) {
                if (bar.getAmount() != null) {
                    amtSum += bar.getAmount().doubleValue();
                    amtCnt++;
                }
            }
            if (amtCnt > 0) {
                overview.setAvgAmount20d(amtSum / amtCnt);
            }

            // d) 20日平均换手率
            double turnSum = 0;
            int turnCnt = 0;
            for (DailyBarRow bar : recent20) {
                if (bar.getTurnoverRate() != null) {
                    turnSum += bar.getTurnoverRate().doubleValue();
                    turnCnt++;
                }
            }
            if (turnCnt > 0) {
                overview.setTurnoverRate20d(turnSum / turnCnt);
            }
        }
        // 回填 ATR
        if (atr != null) {
            overview.setAtr(atr.doubleValue());
        }
        log.debug("[RiskLiquidityBase] code={} maxDd={} vol20d={} avgAmt={} avgTurn={} atr={}",
            code, overview.getMaxDrawdown(), overview.getVolatility20d(),
            overview.getAvgAmount20d(), overview.getTurnoverRate20d(), overview.getAtr());

        // 计算目标价/止损价（依赖 chPrice 中的当前价）
        BigDecimal currentPrice = null;
        if (chPrice != null && chPrice.get("close_price") != null) {
            currentPrice = (BigDecimal) chPrice.get("close_price");
        }
        String targetPriceStr = null;
        String stopLossPriceStr = null;
        if (currentPrice != null) {
            // 目标价：阻力位上方5%，若无则当前价×1.10
            BigDecimal target = resistancePrice != null
                    ? resistancePrice.multiply(new BigDecimal("1.05"))
                    : currentPrice.multiply(new BigDecimal("1.10"));
            targetPriceStr = target.setScale(2, RoundingMode.HALF_UP).toString();

            // 止损价：缠论支撑位 与 ATR止损 两者取较近的
            BigDecimal atrStop = atr != null
                    ? currentPrice.subtract(atr.multiply(new BigDecimal("1.5")))
                    : currentPrice.multiply(new BigDecimal("0.90"));
            BigDecimal stopLoss = (supportPrice != null && supportPrice.compareTo(atrStop) > 0)
                    ? atrStop  // ATR止损更近（更保守）
                    : supportPrice;  // 缠论支撑更近
            stopLossPriceStr = stopLoss.setScale(2, RoundingMode.HALF_UP).toString();
        }
        // 介入价格：基于MA20支撑位（与推荐列表逻辑一致）
        String entryPriceStr = null;
        if (currentPrice != null) {
            BigDecimal ma20 = null;
            if (techBars != null && !techBars.isEmpty()) {
                int ma20Count = Math.min(20, techBars.size());
                double ma20Sum = 0;
                int validCount = 0;
                for (int i = techBars.size() - ma20Count; i < techBars.size(); i++) {
                    BigDecimal cp = techBars.get(i).getClosePrice();
                    if (cp != null) {
                        ma20Sum += cp.doubleValue();
                        validCount++;
                    }
                }
                if (validCount > 0) {
                    ma20 = BigDecimal.valueOf(ma20Sum / validCount);
                }
            }
            // 若MA20可获取且 < closePrice，买入价=MA20（回踩支撑买入）
            // 若MA20可获取且 >= closePrice 或无法获取，买入价=closePrice×0.95
            if (ma20 != null && ma20.compareTo(currentPrice) < 0) {
                entryPriceStr = ma20.setScale(2, RoundingMode.HALF_UP).toString();
            } else {
                entryPriceStr = currentPrice.multiply(new BigDecimal("0.95")).setScale(2, RoundingMode.HALF_UP).toString();
            }
        }
        
        // 3. 资金面信号（从CH计算量比/换手率）
        MoneyFlowSignal moneySignal = calcMoneyFlowSignal(code);
        
        // 3.1 量价背离检测（需同时有价格动量 + 5日累计资金流向数据）
        detectVolumePriceDivergence(techSignal, code);
        
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

        // 4.5 融资余额/股东人数已移至 calcMoneyFlowSignal()，此处不再重复查询

        // 4.6 大盘蓝筹补充事件面数据（龙虎榜机构净买入）
        if (isBlueChip) {
            try {
                BigDecimal lhbNet = stockAnalysisMapper.selectLhbInstitutionNet(code);
                if (lhbNet != null) sentimentSignal.setLhbInstitutionNet(lhbNet);
            } catch (Exception e) { log.debug("龙虎榜机构净买入查询失败: {}", e.getMessage()); }
        }

        // 4.7 事件面补充：机构调研热度 + 基金持仓集中度（所有股票）
        try {
            Integer rrCount = stockAnalysisMapper.selectResearchReportCount90d(code);
            if (rrCount != null) sentimentSignal.setResearchReportCount90d(rrCount);
        } catch (Exception e) { log.debug("研报数量查询失败: {}", e.getMessage()); }
        try {
            BigDecimal fhr = stockAnalysisMapper.selectFundHolderRatio(code);
            if (fhr != null) sentimentSignal.setFundHolderRatio(fhr);
        } catch (Exception e) { log.debug("基金持仓集中度查询失败: {}", e.getMessage()); }

        // 4.8 新闻事件信号（来自 stock_news 表）
        try {
            Map<String, Object> stats30d = newsMapper.selectNewsStats30d(code, 30);
            if (stats30d != null && !stats30d.isEmpty()) {
                int pos30d = ((Number) stats30d.getOrDefault("positive_30d", 0)).intValue();
                int neg30d = ((Number) stats30d.getOrDefault("negative_30d", 0)).intValue();
                int tagged30d = ((Number) stats30d.getOrDefault("tagged_30d", 0)).intValue();
                int total30d = pos30d + neg30d;
                double bias = total30d > 0 ? (double) (pos30d - neg30d) / total30d : 0.0;
                // 新闻评分（满分10分）
                int newsScore = calcNewsScore(pos30d, neg30d, tagged30d, bias);
                sentimentSignal.setNewsPositive30d(pos30d);
                sentimentSignal.setNewsNegative30d(neg30d);
                sentimentSignal.setNewsTagged30d(tagged30d);
                sentimentSignal.setNewsSentimentBias(bias);
                sentimentSignal.setNewsScore(newsScore);
            }
        } catch (Exception e) { log.debug("新闻事件信号查询失败: {}", e.getMessage()); }

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

        // 5a. 估值历史分位（从 CH 计算后注入）
        try {
            Map<String, Object> vp = getValuationPercentile(code, 3);
            if (vp.get("pePercentile") != null) {
                fundamentalSignal.setPePercentile((BigDecimal) vp.get("pePercentile"));
            }
            if (vp.get("pbPercentile") != null) {
                fundamentalSignal.setPbPercentile((BigDecimal) vp.get("pbPercentile"));
            }
        } catch (Exception e) {
            log.debug("估值历史分位查询失败: {}", e.getMessage());
        }

        // 5b. 扣非净利润同比增速（跨年比较）
        try {
            BigDecimal deductedYoY = stockAnalysisMapper.selectDeductedNpYoY(code);
            if (deductedYoY != null) {
                fundamentalSignal.setDeductedNpYoY(deductedYoY);
            }
        } catch (Exception e) {
            log.debug("扣非净利润增速查询失败: {}", e.getMessage());
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
        // 研报覆盖热度（近90天数量）
        fundamentalSignal.setReportCount(researchSignal.getReportCount());
        // 查询最近5条研报明细
        researchSignal.setRecentReports(stockAnalysisMapper.selectRecentResearchReports(code));
        
        // 6. 调用规则引擎评分
        TradingSignal signal = tradingSignalEngine.evaluate(
                code,
                overview.getName() != null ? overview.getName() : code,
                techSignal, moneySignal, sentimentSignal, fundamentalSignal,
                supportPrice, resistancePrice,
                isBlueChip);
        
        // 7. 填充总览
        overview.setTotalScore(signal.getTotalScore());
        overview.setAction(signal.getAction());
        overview.setActionName(signal.getActionName());
        overview.setPosition(signal.getPosition());
        overview.setTiming(signal.getTiming());
        overview.setRisks(signal.getRisks());
        overview.setReversalConditions(signal.getReversalConditions());
        overview.setScoreDetails(signal.getScoreDetails());
        // 资金面数据日期更新到 money detail 的 dataRange
        try {
            java.util.Map<String, Object> mfForDate = analysisChMapper.selectLatestMoneyFlow(code, analysisCommon.getLatestTradeDate());
            if (mfForDate != null && mfForDate.get("tradeDate") != null) {
                String mfDate = mfForDate.get("tradeDate").toString();
                for (ScoreDetail detail : overview.getScoreDetails()) {
                    if ("money".equals(detail.getDimension())) {
                        detail.setDataRange("数据日期：" + mfDate);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("获取资金流向日期失败: code={}", code);
        }
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

        // 7.2 标记是否大盘蓝筹（供前端切换展示）
        overview.setBlueChip(isBlueChip);

        // 7.3 决策卡片：目标价 / 介入价 / 止损价 / 信心水平
        overview.setTargetPrice(targetPriceStr);
        overview.setEntryPrice(entryPriceStr);
        overview.setStopLossPrice(stopLossPriceStr);
        overview.setConfidenceLevel(calcConfidenceLevel(fundamentalSignal, researchSignal));

        // ========== P0-P2 新增逻辑 ==========

        // 7.4 多级目标价：第二目标价（估值回归位）+ 极端目标价（PB=1x）
        if (currentPrice != null) {
            // 第二目标价：基于PE分位均值回归估算
            // 公式：当前价 × (合理PE / 当前PE)，合理PE取行业中性值
            BigDecimal target2 = calcTargetPrice2(currentPrice, fundamentalSignal);
            // 防御：第二目标价必须高于止损价才有意义
            if (target2 != null && stopLossPriceStr != null) {
                BigDecimal sl = new BigDecimal(stopLossPriceStr);
                if (target2.compareTo(sl) <= 0) target2 = null;
            }
            overview.setTargetPrice2(target2 != null ? target2.setScale(2, RoundingMode.HALF_UP).toString() : null);

            // 极端目标价：PB=1x 极端估值
            BigDecimal extremeTgt = calcExtremeTargetPrice(currentPrice, fundamentalSignal, stockInfo);
            overview.setExtremeTargetPrice(extremeTgt != null ? extremeTgt.setScale(2, RoundingMode.HALF_UP).toString() : null);
        }

        // 7.4b 投资分析摘要表：建议仓位 + 减仓价区间 + 风险等级（须先算 riskLevel 供 executionPlan 使用）
        overview.setSuggestedPositionPct(calcSuggestedPositionPct(signal, overview.getConfidenceLevel(), fundamentalSignal, isBlueChip));
        overview.setReducePriceRange(calcReducePriceRange(currentPrice, resistancePrice, signal));
        overview.setRiskLevel(calcRiskLevel(signal, fundamentalSignal, moneySignal, currentPrice));

        // 7.5 分批执行方案（根据风险等级+信心水平动态调整批次比例）
        overview.setExecutionPlan(buildExecutionPlan(signal, currentPrice, targetPriceStr, stopLossPriceStr,
                overview.getTargetPrice2(), overview.getRiskLevel(), overview.getConfidenceLevel()));

        // 7.6 三方分析师独立评分（保守/中性/激进）
        calcMultiAnalystScores(overview, techSignal, moneySignal, sentimentSignal, fundamentalSignal,
                isBlueChip, currentPrice, supportPrice, resistancePrice);

        // 7.7 尾部风险暴露度计算（传入 code 用于动态计算）
        overview.setTailRisks(buildTailRisks(code, fundamentalSignal, stockInfo, currentPrice));

        // 7.8 催化剂追踪矩阵
        overview.setCatalysts(buildCatalysts(code, fundamentalSignal, sentimentSignal, researchSignal));

        // 7.9 多空辩论：生成论据列表 + 结论文本（供前端"核心结论"和"多空交锋"区域）
        buildBullBearDebate(overview);

        log.info("个股分析完成: code={}, totalScore={}, action={}, isBlueChip={}, tailRisks={}, catalysts={}, bullArgs={}, bearArgs={}",
                code, overview.getTotalScore(), overview.getAction(), isBlueChip,
                overview.getTailRisks() != null ? overview.getTailRisks().size() : 0,
                overview.getCatalysts() != null ? overview.getCatalysts().size() : 0,
                overview.getBullArguments() != null ? overview.getBullArguments().size() : 0,
                overview.getBearArguments() != null ? overview.getBearArguments().size() : 0);
        // 诊断：打印各维度得分明细
        if (overview.getScoreDetails() != null) {
            for (ScoreDetail d : overview.getScoreDetails()) {
                log.info("[Analysis] code={} dim={} score={}/{} items={}",
                        code, d.getDimension(), d.getScore(), d.getMaxScore(),
                        d.getItems() != null ? d.getItems().size() : 0);
            }
        }

        return overview;
    }

    /**
     * 根据四维度评分生成文字结论（含关键判断依据）
     */

    /**
     * 生成单维度的一句话判断依据（只输出有信息的指标，英文状态转中文）
     */

    /**
     * 将英文状态码映射为中文，普通文本原样返回
     */

    /**
     * 研报评级 → 评分（0-5分）
     * 买入=5，增持=3，中性=1，减持/卖出=0
     */
    
    /**
     * 计算资金面信号（量比、换手率偏离 + 主力资金流向）
     */
    
    /**
     * 从日线数据计算补充技术指标（均线多头/MACD金叉/RSI/BOLL/MACD动能/收益率/量价背离）
     * 优先用 factor_value 的值，缺失时从 stock_daily 行情计算
     */
    
    /**
     * 量价背离检测：价格涨但主力跑 = 高危出货；价格跌但主力进 = 低估蓄力
     * 使用5日累计主力净流入（与报告"近5日主力净流出"口径一致），阈值放宽
     * 高位背离条件：5日涨幅 >= 3% 且 5日累计主力净流入 < -5000万
     * 低位背离条件：5日跌幅 <= -3% 且 5日累计主力净流入 > 5000万
     */
    
    
    

    /**
     * 获取评分规则说明
     */

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 获取个股研报完整分析（独立 Tab 用）
     * 包含：评级趋势、EPS一致预期、覆盖强度、近期研报列表
     */

    /**
     * 将原始评级趋势数据按月份 pivot 为图表友好的格式
     * 原始: [{month:"2025-11", rating:"买入", cnt:3}, {month:"2025-11", rating:"增持", cnt:2}, ...]
     * 输出: [{month:"2025-11", 买入:3, 增持:2, ...}]
     */

    /**
     * 计算 EPS 一致预期
     * 解析多份研报的 eps_forecast JSON，按年度聚合取平均
     */
    @SuppressWarnings("unchecked")

    /**
     * 股票联想搜索（按代码或名称模糊匹配）
     * @param keyword 搜索关键词（代码或名称片段）
     * @return 匹配的股票列表（code, name, market）
     */

    /**
     * 同业对比：获取同行业股票的 PE/PB/ROE/涨跌幅/评分
     * @param code 股票代码
     * @return 行业名称 + 同业列表（含当前股高亮）
     */

    /**
     * 估值历史分位：计算当前 PE/PB 在 N 年中的百分位排名
     * @param code 股票代码
     * @param years 回溯年数（默认3）
     * @return pePercentile/pbPercentile/peCurrent/pbCurrent/peHistoryCount/pbHistoryCount
     */

    /** 将前端短代码转为 CH stock_daily 无后缀格式 */



    /** CH JDBC template 注入（用于直接 SQL） */
    @Autowired(required = false)
    @Qualifier("clickHouseJdbcTemplate")
    private JdbcTemplate clickHouseJdbcTemplate;

    /**
     * 行业涨跌排行 + 概念板块排行
     * 注意：MySQL stock_daily 为空表，涨跌幅从 ClickHouse 获取
     * stock_concept 表仅在 MySQL 存在，概念排行需要先从 MySQL 取股票列表再聚合 CH 行情
     */

    /**
     * 行业内个股排名 — CH查询 stock_info + stock_daily
     */

    /**
     * 行业关联分析：Beta暴露 + 行业联动
     * 计算个股与所属行业的 Beta、相关系数、行业涨跌联动
     */

    /**
     * 计算 Beta 和相关系数
     */

    /**
     * 涨跌停分析：历史涨停/跌停记录 + 涨停原因 + 炸板统计
     */

    /**
     * 大宗交易分析：逐笔明细 + 统计汇总 + 买卖营业部
     */

    /**
     * 概念板块内个股排名 — MySQL取代码列表 + CH取行情
     */

    // ─── 热门行业专题 ──────────────────────────────────────────────────


    /**
     * 热门行业专题概览
     * 返回 Map，包含 tradeDate 和 sectors
     */

    /**
     * 热门行业专题详情
     */

    /** 获取最新交易日期 */


    // ══════════════════════════════════════════════════════════════
    // P0 新增：缠论K线可视化、资金流向趋势、相对强弱
    // ══════════════════════════════════════════════════════════════

    /**
     * 缠论K线图数据（实时计算）
     * 获取近250个交易日K线 → ChanTheoryCalculator 计算 → 返回可视化数据
     */
    /**
     * 拉取K线数据，返回原始double数组
     * @param code 股票代码
     * @param days 拉取天数
     * @return [open[], high[], low[], close[], volume[]] 或 null
     */

    /**
     * 批量获取全市场K线数据（一次ClickHouse查询，用于形态选股等全市场扫描场景）
     * @param days 需要的交易日天数
     * @return Map: code -> double[][] {open, high, low, close, volume}（按日期升序）
     */


    /**
     * 资金流向历史趋势（逐日评分）
     * 复用 TradingSignalEngine 评分规则对每日资金流向打分
     */

    /**
     * 单日资金面评分（复用 TradingSignalEngine 权重规则）
     * 满分25分：主力净流入(10) + 占比(8) + 量比(4) + 换手率偏离(3)
     * 注意：历史数据无量比/换手率，只按主力净流入(10)+占比(8)+净流入分级(7)=25分简化
     */

    /**
     * 相对强弱分析：个股 vs 行业等权组合的累计收益对比
     * 计算近60日的 RS Ratio（个股累计收益 / 行业累计收益）
     */

    /**
     * K线数据（近N交易日，供前端图表使用）
     */

    /**
     * P2 新增：个股长周期表现分析
     * 返回：YTD涨幅、相对沪深300超额收益、RS Rating（250日收益排名百分位）、行业内排名
     */

    /**
     * 获取当年首个交易日（以沪深300为准）
     */

    /**
     * 计算指数YTD涨幅
     */

    /**
     * 计算个股YTD涨幅（从stock_daily）
     * 使用子查询获取首日/末日价格，避免 maxBy/minBy（ClickHouse 26.5 不支持）
     */
    /**
     * 计算RS Rating：近250日收益排名百分位（0~99）
     * 样本：全市场有≥160日数据的沪深股票
     */

    /**
     * 计算该股在行业内的20日涨幅排名
     */

    /**
     * 计算行业内股票总数
     */



    /**
     * P1: 第二目标价 — PE均值回归估算（仅低估时有效）
     * 公式：当前价 × (合理PE / 当前PE)
     * PE分位<40（低估）时，合理PE取历史中位数，目标价>当前价
     * PE分位≥40 时，回归方向向下，不作为获利目标，返回null
     */

    /**
     * P1: 极端目标价 — PB=1x 极端估值
     */

    /**
     * P2: 分批执行方案
     * 根据操作方向（买入/卖出）+ 风险等级 + 信心水平 生成多批操作指令
     * 风险越高→首批越大（买入更谨慎/卖出更果断）；风险越低→首批越小（分批更均匀）
     */

    /**
     * 多空辩论：从四维度信号提取多空论据，生成结论文本
     * 逻辑与 WorkflowReportService.evaluateBullBear 一致，但内联在 AnalysisService 中
     * 避免循环依赖（WorkflowReportService 依赖 AnalysisService）
     */

    /**
     * 生成多空辩论结论文本（精炼投资逻辑句式）
     */



    /**
     * 投资分析摘要表：减仓价区间（建议持有者分批减仓的价格带）
     */

    /**
     * 投资分析摘要表：风险等级
     * 算法：基础分（action反转） + 估值加分 + 负债加分 + 主力流出加分
     * 0~3 低 / 4~6 中 / 7+ 高
     */

    /**
     * P1: 三方分析师独立评分
     * 保守分析师：重防守（估值+负债+现金流），轻进攻（趋势+情绪）
     * 中性分析师：四维度均衡加权（当前评分体系）
     * 激进分析师：重进攻（趋势+资金+情绪），轻防守（估值容忍度高）
     * 每方输出0-10分的综合评分 + 仓位建议 + 一句话描述
     */

    /**
     * P0: 尾部风险暴露度表（动态计算版）
     * 概率/影响/潜在跌幅均基于实际数据计算，不再硬编码
     */

    /**
     * 格式化金额（亿/万）
     */

    // ============================================================
    // 尾部风险动态计算 Helper
    // ============================================================

    /**
     * 动态计算尾部风险发生概率
     * 基准 3%（行业常态），财务指标距阈值越远概率越高，单因子最高 +18%
     * 结果钳位 [2%, 25%]，输出格式 "X-Y%"
     */

    /**
     * 动态计算尾部风险潜在跌幅
     * 优先用 CH 历史年化波动率 × 危机乘数(1.5~2.5)
     * CH 不可用则用市值分级经验值兜底
     */

    /**
     * 动态计算影响程度（基于总市值）
     * 大市值 → 市场消化能力强 → 影响较小
     */

    /**
     * 流动性危机专用：同时考虑流动比率和速动比率，取更危险者的概率
     */

    /**
     * 应收账款风险概率：周转天数越长 → 概率越高
     * 基准 120 天，超过后每 60 天 +12% 概率，钳位 [2%, 25%]
     */

    // ============================================================

    /**
     * P0: 催化剂追踪矩阵
     * 从基本面信号、事件面信号、研报信号提取正面/负面催化剂，双列展示
     */

    /**
     * 信心水平：基于数据完整性评分（低/中/高）
     * 研报覆盖 + 基本面数据完整度 + 技术信号
     */

    /**
     * 新闻面评分（满分10分，供评分引擎使用）
     * 规则：有新闻+1，利好偏多+3，利好远超风险+2，有事件标签+2，情感偏向强烈+2
     */

    /**
     * 股东结构分析（Tab：股东结构）
     * 返回：股东人数趋势 + 基金持仓明细 + 筹码集中度信号
     */
}
