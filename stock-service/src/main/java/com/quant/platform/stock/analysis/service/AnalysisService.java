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

private final SectorAnalysisService sectorAnalysisService;

public Map<String, Object> getSectorRanking() { return sectorAnalysisService.getSectorRanking(); }
public List<Map<String, Object>> getConceptStocks(String conceptName, String sortBy, String sortOrder) { return sectorAnalysisService.getConceptStocks(conceptName, sortBy, sortOrder); }
public List<Map<String, Object>> getIndustryStocks(String industry, String sortBy, String sortOrder) { return sectorAnalysisService.getIndustryStocks(industry, sortBy, sortOrder); }
public Map<String, Object> getIndustryCorrelation(String code) { return sectorAnalysisService.getIndustryCorrelation(code); }
public Map<String, Object> getHotSectors() { return sectorAnalysisService.getHotSectors(); }
public Map<String, Object> getHotSectorDetail(String conceptName) { return sectorAnalysisService.getHotSectorDetail(conceptName); }


private final ResearchAnalysisService researchAnalysisService;

private int calcResearchScore(String rating) { return researchAnalysisService.calcResearchScore(rating); }
public Map<String, Object> getResearchAnalysis(String code) { return researchAnalysisService.getResearchAnalysis(code); }
public Map<String, Object> getShareholderStructure(String code) { return researchAnalysisService.getShareholderStructure(code); }


private final MoneyFlowService moneyFlowService;

private MoneyFlowSignal calcMoneyFlowSignal(String code) { return moneyFlowService.calcMoneyFlowSignal(code); }
public Map<String, Object> getMoneyFlowHistory(String code, int days) { return moneyFlowService.getMoneyFlowHistory(code, days); }


    private final TechIndicatorService techIndicatorService;

    public List<TradingSignalEngine.ScoreRule> getScoreRules() {
        return techIndicatorService.getScoreRules();
    }

    private void supplementTechIndicators(TechSignal tech, List<DailyBarRow> bars) {
        techIndicatorService.supplementTechIndicators(tech, bars);
    }

    private void detectVolumePriceDivergence(TechSignal tech, String code) {
        techIndicatorService.detectVolumePriceDivergence(tech, code);
    }

    private BigDecimal calcTargetPrice2(BigDecimal currentPrice, FundamentalSignal fs) {
        return techIndicatorService.calcTargetPrice2(currentPrice, fs);
    }

    private BigDecimal calcExtremeTargetPrice(BigDecimal currentPrice, FundamentalSignal fs, Map<String, Object> stockInfo) {
        return techIndicatorService.calcExtremeTargetPrice(currentPrice, fs, stockInfo);
    }


    private final AnalysisCommonService analysisCommon;

    
    private final AnalysisChMapper analysisChMapper;

    private final StockAnalysisMapper stockAnalysisMapper;

    private final NewsMapper newsMapper;

    private final BidAskMapper bidAskMapper;

    private final TradingSignalEngine tradingSignalEngine;

    private final ClickHouseStockService clickHouseStockService;
    
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
    private String buildConclusion(AnalysisOverview o, TradingSignal signal) {
        StringBuilder sb = new StringBuilder();
        String displayName = o.getName() != null ? o.getName() : o.getCode();
        sb.append("【").append(displayName).append("(").append(o.getCode()).append(")】");

        // 操作建议
        sb.append("综合评分").append(signal.getTotalScore()).append("分，");
        sb.append("建议【").append(signal.getActionName()).append("】");
        sb.append("，建议仓位").append(signal.getPosition()).append("%。");

        // 四维度：输出关键判断依据，而非笼统的"强/弱"
        if (o.getScoreDetails() != null) {
            for (var d : o.getScoreDetails()) {
                String reason = buildDimensionReason(d, o);
                sb.append(d.getDimensionName()).append("：").append(reason).append("；");
            }
        }

        // 风险提示
        if (o.getRisks() != null && !o.getRisks().isEmpty()) {
            sb.append("注意：").append(o.getRisks());
        }
        return sb.toString();
    }

    /**
     * 生成单维度的一句话判断依据（只输出有信息的指标，英文状态转中文）
     */
    private String buildDimensionReason(ScoreDetail d, AnalysisOverview o) {
        double pct = d.getMaxScore() > 0 ? (double) d.getScore() / d.getMaxScore() : 0;
        String level;
        if (pct >= 0.8) level = "强";
        else if (pct >= 0.6) level = "较强";
        else if (pct >= 0.4) level = "一般";
        else if (pct >= 0.2) level = "较弱";
        else level = "弱";

        // 取各维度最有价值的判断依据（优先取有分数的item，最多2个）
        List<String> parts = new ArrayList<>();
        if (d.getItems() != null) {
            for (var item : d.getItems()) {
                if (item.getScore() > 0 && item.getValue() != null
                        && !item.getValue().equals("-") && !item.getValue().equals("暂无数据")) {
                    parts.add(mapChinese(item.getLabel()) + mapChinese(item.getValue()));
                }
                if (parts.size() >= 2) break;
            }
            // 如果没找到有分的，取前2个非空的
            if (parts.isEmpty()) {
                for (var item : d.getItems()) {
                    if (item.getValue() != null && !item.getValue().equals("-") && !item.getValue().equals("暂无数据")) {
                        parts.add(mapChinese(item.getLabel()) + mapChinese(item.getValue()));
                    }
                    if (parts.size() >= 2) break;
                }
            }
        }
        return level + (parts.isEmpty() ? "" : "（" + String.join("，", parts) + "）");
    }

    /**
     * 将英文状态码映射为中文，普通文本原样返回
     */
    private String mapChinese(String v) {
        if (v == null) return "";
        return switch (v) {
            case "BUY" -> "买入";
            case "SELL" -> "卖出";
            case "HOLD" -> "持有";
            case "BULLISH" -> "牛市";
            case "SIDEWAYS" -> "横盘";
            case "BEARISH" -> "熊市";
            case "是" -> "是";
            case "否" -> "否";
            default -> v;
        };
    }

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
    public List<Map<String, Object>> searchStocks(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return stockAnalysisMapper.searchStocks(keyword.trim());
    }

    /**
     * 同业对比：获取同行业股票的 PE/PB/ROE/涨跌幅/评分
     * @param code 股票代码
     * @return 行业名称 + 同业列表（含当前股高亮）
     */
    public Map<String, Object> getPeerComparison(String code) {
        Map<String, Object> result = new LinkedHashMap<>();

        // 1. 获取该股票的行业
        Map<String, Object> myInfo = stockAnalysisMapper.selectStockInfo(code);
        String industry = myInfo != null ? (String) myInfo.get("industry") : null;
        if (industry == null || industry.isBlank()) {
            result.put("industry", "未知");
            result.put("peers", Collections.emptyList());
            return result;
        }
        result.put("industry", industry);

        // 2. 获取同行业所有股票的基本信息（PE/PB/市值）
        List<Map<String, Object>> peers = stockAnalysisMapper.selectIndustryPeers(industry);
        if (peers == null || peers.isEmpty()) {
            result.put("peers", Collections.emptyList());
            return result;
        }

        // 3. 补充 CH 最新价格/涨跌幅数据
        for (Map<String, Object> peer : peers) {
            String peerCode = (String) peer.get("code");
            try {
                Map<String, Object> chBar = analysisChMapper.selectLatestDailyBar(peerCode);
                if (chBar != null) {
                    peer.put("changePercent", chBar.get("change_percent"));
                    peer.put("closePrice", chBar.get("close_price"));
                }
            } catch (Exception e) {
                log.debug("获取同业价格失败: {}", peerCode);
            }
        }

        // 4. 排序：按总市值降序（大公司在前）
        peers.sort((a, b) -> {
            BigDecimal ma = a.get("total_market_cap") instanceof BigDecimal ?
                    (BigDecimal) a.get("total_market_cap") : BigDecimal.ZERO;
            BigDecimal mb = b.get("total_market_cap") instanceof BigDecimal ?
                    (BigDecimal) b.get("total_market_cap") : BigDecimal.ZERO;
            return mb.compareTo(ma);
        });

        // 5. 标记当前股票
        result.put("peers", peers);
        result.put("currentCode", code);
        return result;
    }

    /**
     * 估值历史分位：计算当前 PE/PB 在 N 年中的百分位排名
     * @param code 股票代码
     * @param years 回溯年数（默认3）
     * @return pePercentile/pbPercentile/peCurrent/pbCurrent/peHistoryCount/pbHistoryCount
     */
    public Map<String, Object> getValuationPercentile(String code, int years) {
        Map<String, Object> result = new LinkedHashMap<>();
        String normalized = analysisCommon.normalizeCodeForDailyCH(code);

        try {
            // 从 CH 查询历史 PE/PB 序列（排除0值和null）
            List<BigDecimal> peHistory = new ArrayList<>();
            List<BigDecimal> pbHistory = new ArrayList<>();
            BigDecimal currentPe = null;
            BigDecimal currentPb = null;

            String sql = """
                SELECT pe_ttm, pb FROM stock.stock_daily FINAL
                WHERE code = ?
                  AND trade_date >= subtractYears(today(), ?)
                  AND pe_ttm > 0 AND pe_ttm < 50000
                  AND pb > 0 AND pb < 10000
                ORDER BY trade_date ASC
                """;
            List<Map<String, Object>> rows = clickHouseJdbcTemplate.query(sql,
                (rs, rowNum) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("pe_ttm", rs.getBigDecimal("pe_ttm"));
                    m.put("pb", rs.getBigDecimal("pb"));
                    return m;
                }, normalized, years);

            for (Map<String, Object> row : rows) {
                BigDecimal pe = (BigDecimal) row.get("pe_ttm");
                BigDecimal pb = (BigDecimal) row.get("pb");
                if (pe != null) peHistory.add(pe);
                if (pb != null) pbHistory.add(pb);
            }

            if (!peHistory.isEmpty()) currentPe = peHistory.getLast();
            if (!pbHistory.isEmpty()) currentPb = pbHistory.getLast();

            // 计算百分位：低于当前值的占比
            double pePct = calcPercentile(peHistory, currentPe);
            double pbPct = calcPercentile(pbHistory, currentPb);

            result.put("pePercentile", Math.round(pePct * 10.0) / 10.0);
            result.put("pbPercentile", Math.round(pbPct * 10.0) / 10.0);
            result.put("peCurrent", currentPe);
            result.put("pbCurrent", currentPb);
            result.put("peHistoryCount", peHistory.size());
            result.put("pbHistoryCount", pbHistory.size());
            result.put("years", years);

            // 分位描述
            result.put("peDesc", percentileDesc(pePct));
            result.put("pbDesc", percentileDesc(pbPct));
        } catch (Exception e) {
            log.error("查询估值分位失败: code={}, error={}", code, e.getMessage(), e);
            result.put("error", "查询失败: " + e.getMessage());
        }
        return result;
    }

    /** 将前端短代码转为 CH stock_daily 无后缀格式 */

    private double calcPercentile(List<BigDecimal> history, BigDecimal current) {
        if (history == null || history.isEmpty() || current == null) return 0;
        int belowOrEqual = 0;
        for (BigDecimal val : history) {
            if (val != null && val.compareTo(current) <= 0) belowOrEqual++;
        }
        return (double) belowOrEqual / history.size() * 100.0;
    }

    private static String percentileDesc(double pct) {
        if (pct >= 90) return "极高估（历史90%以上）";
        if (pct >= 75) return "偏高（历史75%~90%）";
        if (pct >= 50) return "中等偏上（50%~75%）";
        if (pct >= 25) return "偏低（25%~50%）";
        if (pct >= 10) return "很低估（10%~25%）";
        return "极低估（历史10%以下）";
    }

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
    public Map<String, Object> getLimitUpAnalysis(String code) {
        Map<String, Object> result = new LinkedHashMap<>();
        String normalized = analysisCommon.normalizeCodeForDailyCH(code);

        // 1. 近期涨跌停记录（CH stock_sentiment_zt）
        try {
            String ztSql = """
                SELECT trade_date, zt_type, reason, close as closePrice, pct_change as changePct
                FROM stock.stock_sentiment_zt
                WHERE code = ?
                ORDER BY trade_date DESC
                LIMIT 30
                """;
            List<Map<String, Object>> ztList = clickHouseJdbcTemplate.query(ztSql,
                (rs, rowNum) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("tradeDate", rs.getDate("trade_date").toString());
                    m.put("ztType", rs.getString("zt_type"));
                    m.put("reason", rs.getString("reason"));
                    m.put("closePrice", rs.getBigDecimal("closePrice"));
                    m.put("changePct", rs.getBigDecimal("changePct"));
                    return m;
                }, normalized);
            result.put("records", ztList);

            // 2. 统计汇总
            String statsSql = """
                SELECT
                    countIf(zt_type = 'zt') as limitUpCount,
                    countIf(zt_type = 'dt') as limitDownCount,
                    countIf(zt_type = 'zbgc') as brokenCount,
                    MIN(trade_date) as firstDate,
                    MAX(trade_date) as lastDate
                FROM stock.stock_sentiment_zt
                WHERE code = ?
                """;
            Map<String, Object> stats = clickHouseJdbcTemplate.queryForMap(statsSql, normalized);
            result.put("stats", stats);

            // 3. 涨停原因统计
            String reasonSql = """
                SELECT reason, COUNT(*) as cnt
                FROM stock.stock_sentiment_zt
                WHERE code = ? AND zt_type = 'zt' AND reason != ''
                GROUP BY reason
                ORDER BY cnt DESC
                LIMIT 10
                """;
            List<Map<String, Object>> reasons = clickHouseJdbcTemplate.query(reasonSql,
                (rs, rowNum) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("reason", rs.getString("reason"));
                    m.put("count", rs.getLong("cnt"));
                    return m;
                }, normalized);
            result.put("topReasons", reasons);

        } catch (Exception e) {
            log.error("涨跌停分析失败: code={}, error={}", code, e.getMessage(), e);
            result.put("error", "查询失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 大宗交易分析：逐笔明细 + 统计汇总 + 买卖营业部
     */
    public Map<String, Object> getBlockTradeAnalysis(String code) {
        Map<String, Object> result = new LinkedHashMap<>();
        String normalized = analysisCommon.normalizeCodeForDailyCH(code);

        // 1. 近期大宗交易逐笔记录
        try {
            String btSql = """
                SELECT trade_date, seq_no, price, volume, amount, discount_rate,
                       change_pct, close_price, pct_of_float,
                       buy_branch, sell_branch
                FROM stock.stock_sentiment_block_trade FINAL
                WHERE code = ?
                ORDER BY trade_date DESC, seq_no
                LIMIT 50
                """;
            List<Map<String, Object>> btList = clickHouseJdbcTemplate.query(btSql,
                (rs, rowNum) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("tradeDate", rs.getDate("trade_date").toString());
                    m.put("seqNo", rs.getInt("seq_no"));
                    m.put("price", rs.getBigDecimal("price"));
                    m.put("volume", rs.getBigDecimal("volume"));
                    m.put("amount", rs.getBigDecimal("amount"));
                    m.put("discountRate", rs.getBigDecimal("discount_rate"));
                    m.put("changePct", rs.getBigDecimal("change_pct"));
                    m.put("closePrice", rs.getBigDecimal("close_price"));
                    m.put("pctOfFloat", rs.getBigDecimal("pct_of_float"));
                    m.put("buyBranch", rs.getString("buy_branch"));
                    m.put("sellBranch", rs.getString("sell_branch"));
                    return m;
                }, normalized);
            result.put("records", btList);

            // 2. 统计汇总（从逐笔聚合）
            String statsSql = """
                SELECT
                    COUNT(*) as totalCount,
                    SUM(amount) as totalAmount,
                    AVG(discount_rate) as avgDiscountRate,
                    MIN(trade_date) as firstDate,
                    MAX(trade_date) as lastDate
                FROM stock.stock_sentiment_block_trade FINAL
                WHERE code = ?
                """;
            Map<String, Object> stats = clickHouseJdbcTemplate.queryForMap(statsSql, normalized);
            result.put("stats", stats);

            // 3. 买方营业部统计（从逐笔聚合）
            String buySql = """
                SELECT buy_branch as branch, COUNT(*) as cnt, SUM(amount) as totalAmt
                FROM stock.stock_sentiment_block_trade FINAL
                WHERE code = ? AND buy_branch != ''
                GROUP BY buy_branch
                ORDER BY cnt DESC
                LIMIT 10
                """;
            List<Map<String, Object>> buyBranches = clickHouseJdbcTemplate.query(buySql,
                (rs, rowNum) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("branch", rs.getString("branch"));
                    m.put("count", rs.getLong("cnt"));
                    m.put("totalAmount", rs.getBigDecimal("totalAmt"));
                    return m;
                }, normalized);
            result.put("topBuyBranches", buyBranches);

            // 4. 卖方营业部统计（从逐笔聚合）
            String sellSql = """
                SELECT sell_branch as branch, COUNT(*) as cnt, SUM(amount) as totalAmt
                FROM stock.stock_sentiment_block_trade FINAL
                WHERE code = ? AND sell_branch != ''
                GROUP BY sell_branch
                ORDER BY cnt DESC
                LIMIT 10
                """;
            List<Map<String, Object>> sellBranches = clickHouseJdbcTemplate.query(sellSql,
                (rs, rowNum) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("branch", rs.getString("branch"));
                    m.put("count", rs.getLong("cnt"));
                    m.put("totalAmount", rs.getBigDecimal("totalAmt"));
                    return m;
                }, normalized);
            result.put("topSellBranches", sellBranches);

        } catch (Exception e) {
            log.error("大宗交易分析失败: code={}, error={}", code, e.getMessage(), e);
            result.put("error", "查询失败: " + e.getMessage());
        }

        return result;
    }

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
    public double[][] fetchKlineData(String code, int days) {
        try {
            List<DailyBarRow> bars = analysisChMapper.selectRecentDailyBars(code, days);
            if (bars == null || bars.size() < 10) return null;
            int n = bars.size();
            double[] open = new double[n], high = new double[n], low = new double[n], close = new double[n], volume = new double[n];
            for (int i = 0; i < n; i++) {
                DailyBarRow bar = bars.get(i);
                open[i] = bar.getOpenPrice() != null ? bar.getOpenPrice().doubleValue() : 0;
                high[i] = bar.getHighPrice() != null ? bar.getHighPrice().doubleValue() : 0;
                low[i] = bar.getLowPrice() != null ? bar.getLowPrice().doubleValue() : 0;
                close[i] = bar.getClosePrice() != null ? bar.getClosePrice().doubleValue() : 0;
                volume[i] = bar.getVolume() != null ? bar.getVolume() : 0;
            }
            return new double[][] { open, high, low, close, volume };
        } catch (Exception e) {
            log.warn("拉取K线数据失败: {} - {}", code, e.getMessage());
            return null;
        }
    }

    /**
     * 批量获取全市场K线数据（一次ClickHouse查询，用于形态选股等全市场扫描场景）
     * @param days 需要的交易日天数
     * @return Map: code -> double[][] {open, high, low, close, volume}（按日期升序）
     */
    public Map<String, double[][]> batchFetchKlineData(int days) {
        Map<String, double[][]> result = new HashMap<>();
        if (clickHouseJdbcTemplate == null) return result;
        try {
            // 多取日历日确保有足够交易日
            int calDays = (int) Math.ceil(days * 7.0 / 5) + 10;
            LocalDate start = LocalDate.now().minusDays(calDays);
            String startStr = start.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));

            // 一次查询全市场，用argMax去重(ReplacingMergeTree)
            String sql = "SELECT code, trade_date, " +
                    "argMax(open_price, update_time) AS open_price, " +
                    "argMax(high_price, update_time) AS high_price, " +
                    "argMax(low_price, update_time) AS low_price, " +
                    "argMax(close_price, update_time) AS close_price, " +
                    "argMax(volume, update_time) AS volume " +
                    "FROM stock.stock_daily " +
                    "WHERE trade_date >= ? " +
                    "GROUP BY code, trade_date " +
                    "ORDER BY code, trade_date";

            List<Map<String, Object>> rows = clickHouseJdbcTemplate.queryForList(sql, startStr);
            log.info("[BatchKline] 查询到 {} 行K线数据 (start={})", rows.size(), startStr);

            // 按code分组组装
            Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                String code = String.valueOf(row.get("code"));
                grouped.computeIfAbsent(code, k -> new ArrayList<>()).add(row);
            }

            for (Map.Entry<String, List<Map<String, Object>>> entry : grouped.entrySet()) {
                List<Map<String, Object>> codeRows = entry.getValue();
                int n = codeRows.size();
                if (n < 30) continue;  // 数据不足跳过
                double[] open = new double[n], high = new double[n], low = new double[n], close = new double[n], volume = new double[n];
                for (int i = 0; i < n; i++) {
                    Map<String, Object> r = codeRows.get(i);
                    open[i] = r.get("open_price") != null ? ((Number) r.get("open_price")).doubleValue() : 0;
                    high[i] = r.get("high_price") != null ? ((Number) r.get("high_price")).doubleValue() : 0;
                    low[i] = r.get("low_price") != null ? ((Number) r.get("low_price")).doubleValue() : 0;
                    close[i] = r.get("close_price") != null ? ((Number) r.get("close_price")).doubleValue() : 0;
                    volume[i] = r.get("volume") != null ? ((Number) r.get("volume")).doubleValue() : 0;
                }
                result.put(entry.getKey(), new double[][] { open, high, low, close, volume });
            }
            log.info("[BatchKline] 组装完成: {} 只股票", result.size());
        } catch (Exception e) {
            log.error("[BatchKline] 批量查询K线数据失败: {}", e.getMessage(), e);
        }
        return result;
    }

    public Map<String, Object> getChanChart(String code) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);

        try {
            // 1. 获取 K 线数据（需要足够长，缠论至少100根才有效）
            List<DailyBarRow> bars = analysisChMapper.selectRecentDailyBars(code, 260);
            if (bars == null || bars.size() < 50) {
                result.put("error", "K线数据不足（需至少50个交易日）");
                return result;
            }

            // 2. 转换为 MarketDailyBar（ChanTheoryCalculator 入参）
            List<MarketDailyBar> marketBars = new ArrayList<>();
            for (DailyBarRow bar : bars) {
                if (bar.getOpenPrice() == null || bar.getClosePrice() == null
                        || bar.getHighPrice() == null || bar.getLowPrice() == null) continue;
                marketBars.add(MarketDailyBar.builder()
                        .symbol(analysisCommon.normalizeCodeForDailyCH(code))
                        .tradeDate(bar.getTradeDate())
                        .open(bar.getOpenPrice())
                        .high(bar.getHighPrice())
                        .low(bar.getLowPrice())
                        .close(bar.getClosePrice())
                        .vol(bar.getVolume() != null ? BigDecimal.valueOf(bar.getVolume()) : null)
                        .amount(bar.getAmount())
                        .turnoverRate(bar.getTurnoverRate())
                        .build());
            }

            // 3. 缠论计算
            ChanTheoryResult chanResult = ChanTheoryCalculator.calculate(marketBars);
            if (chanResult == null) {
                result.put("error", "缠论计算失败");
                return result;
            }

            // 4. 构建 K 线数据（前端 ECharts 格式）
            List<Object> klineData = new ArrayList<>(); // [open, close, low, high, volume]
            List<String> dates = new ArrayList<>();
            for (MarketDailyBar bar : marketBars) {
                klineData.add(List.of(
                        bar.getOpen().doubleValue(),
                        bar.getClose().doubleValue(),
                        bar.getLow().doubleValue(),
                        bar.getHigh().doubleValue(),
                        bar.getVol() != null ? bar.getVol().doubleValue() : 0
                ));
                dates.add(bar.getTradeDate().toString());
            }

            // 5. 笔数据（折线图标记）
            List<Object> penLines = new ArrayList<>();
            if (chanResult.getPens() != null) {
                for (var pen : chanResult.getPens()) {
                    // 笔连接两个分型端点，方向为 UP/DOWN
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("startIndex", pen.getStartIndex());
                    p.put("endIndex", pen.getEndIndex());
                    p.put("startPrice", pen.getStartPrice());
                    p.put("endPrice", pen.getEndPrice());
                    p.put("startDate", pen.getStartDate() != null ? pen.getStartDate().toString() : null);
                    p.put("endDate", pen.getEndDate() != null ? pen.getEndDate().toString() : null);
                    p.put("direction", pen.getDirection() != null ? pen.getDirection().name() : "UNKNOWN");
                    penLines.add(p);
                }
            }

            // 6. 中枢数据（矩形区域）
            List<Object> hubZones = new ArrayList<>();
            if (chanResult.getHubs() != null) {
                for (var hub : chanResult.getHubs()) {
                    Map<String, Object> h = new LinkedHashMap<>();
                    h.put("high", hub.getHigh());
                    h.put("low", hub.getLow());
                    h.put("zz", hub.getZz());
                    h.put("startDate", hub.getStartDate() != null ? hub.getStartDate().toString() : null);
                    h.put("endDate", hub.getEndDate() != null ? hub.getEndDate().toString() : null);
                    h.put("oscillationCount", hub.getOscillationCount());
                    hubZones.add(h);
                }
            }

            // 7. 买卖点标记
            List<Object> buySellMarks = new ArrayList<>();
            if (chanResult.getBuySellPoints() != null) {
                for (var bsp : chanResult.getBuySellPoints()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("index", bsp.getIndex());
                    m.put("type", bsp.getBuySellType() != null ? bsp.getBuySellType().name() : "UNKNOWN");
                    m.put("value", bsp.getBuySellType() != null ? bsp.getBuySellType().getValue() : 0);
                    m.put("isBuy", bsp.getBuySellType() != null && bsp.getBuySellType().isBuy());
                    m.put("date", bsp.getDate() != null ? bsp.getDate().toString() : null);
                    m.put("price", bsp.getPrice());
                    buySellMarks.add(m);
                }
            }

            result.put("dates", dates);
            result.put("klineData", klineData);
            result.put("pens", penLines);
            result.put("hubs", hubZones);
            result.put("buySellPoints", buySellMarks);
            result.put("penCount", chanResult.getPens() != null ? chanResult.getPens().size() : 0);
            result.put("hubCount", chanResult.getHubs() != null ? chanResult.getHubs().size() : 0);
            result.put("bsPointCount", chanResult.getBuySellPoints() != null ? chanResult.getBuySellPoints().size() : 0);
            result.put("barCount", marketBars.size());

        } catch (Exception e) {
            log.error("缠论K线图计算失败: code={}, error={}", code, e.getMessage(), e);
            result.put("error", "计算失败: " + e.getMessage());
        }

        return result;
    }

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
    public Map<String, Object> getRelativeStrength(String code) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);

        try {
            // 1. 获取该股票行业
            Map<String, Object> myInfo = stockAnalysisMapper.selectStockInfo(code);
            String industry = myInfo != null ? (String) myInfo.get("industry") : null;
            if (industry == null || industry.isBlank()) {
                result.put("error", "未找到行业信息");
                return result;
            }
            result.put("industry", industry);

            // 2. 获取个股近80日日线（多取确保对齐）
            List<DailyBarRow> bars = analysisChMapper.selectRecentDailyBars(code, 85);
            if (bars == null || bars.size() < 30) {
                result.put("error", "个股数据不足（需至少30个交易日）");
                return result;
            }

            // 3. 获取行业等权日收益率序列
            String normalized = analysisCommon.normalizeCodeForDailyCH(code);
            String indReturnSql = """
                SELECT sd.trade_date, AVG(sd.change_percent) / 100 as avg_ret
                FROM stock.stock_daily sd FINAL
                INNER JOIN stock_info si ON si.code = sd.code
                WHERE si.industry = ?
                  AND si.market NOT IN ('BJ','北交所')
                  AND sd.trade_date >= subtractDays(today(), 90)
                GROUP BY sd.trade_date
                ORDER BY sd.trade_date
                """;
            List<Map<String, Object>> indRows = clickHouseJdbcTemplate.query(indReturnSql,
                (rs, rowNum) -> {
                    Map<String, Object> m = new java.util.HashMap<>();
                    m.put("tradeDate", rs.getString("trade_date"));
                    m.put("avgRet", rs.getBigDecimal("avg_ret"));
                    return m;
                }, industry);

            // 构建行业收益 map
            Map<String, Double> indRetMap = new LinkedHashMap<>();
            for (Map<String, Object> r : indRows) {
                String td = (String) r.get("tradeDate");
                BigDecimal avgRet = (BigDecimal) r.get("avgRet");
                if (avgRet != null) indRetMap.put(td, avgRet.doubleValue());
            }

            // 4. 对齐日期并计算累计收益 + RS Ratio
            double stockCumRet = 0;
            double indCumRet = 0;
            List<Map<String, Object>> series = new ArrayList<>();
            List<String> dates = new ArrayList<>();
            List<Double> stockCumList = new ArrayList<>();
            List<Double> indCumList = new ArrayList<>();
            List<Double> rsRatioList = new ArrayList<>();

            for (int i = 1; i < bars.size(); i++) {
                DailyBarRow prev = bars.get(i - 1);
                DailyBarRow curr = bars.get(i);
                if (prev.getClosePrice() == null || curr.getClosePrice() == null
                        || prev.getClosePrice().doubleValue() == 0) continue;
                if (curr.getTradeDate() == null) continue;

                double stockRet = (curr.getClosePrice().doubleValue() - prev.getClosePrice().doubleValue())
                        / prev.getClosePrice().doubleValue();
                String td = curr.getTradeDate().toString();
                Double indRet = indRetMap.get(td);
                if (indRet == null) continue;

                stockCumRet += stockRet;
                indCumRet += indRet;

                // RS Ratio: 个股累计收益 / 行业累计收益（行业为0时取0）
                // 修正：当行业累计为负时，两负数相除会得到错误的大正数，改用超额收益修正
                double rsRatio;
                if (Math.abs(indCumRet) > 0.0001) {
                    if (indCumRet >= 0) {
                        rsRatio = stockCumRet / indCumRet;
                    } else {
                        double excess = stockCumRet - indCumRet;
                        rsRatio = 1.0 + excess / Math.abs(indCumRet);
                    }
                } else {
                    rsRatio = stockCumRet > 0 ? 1.0 : (stockCumRet < 0 ? -1.0 : 0);
                }

                Map<String, Object> day = new LinkedHashMap<>();
                day.put("tradeDate", td);
                day.put("stockRet", Math.round(stockRet * 10000.0) / 100.0);
                day.put("indRet", Math.round(indRet * 10000.0) / 100.0);
                day.put("excessRet", Math.round((stockRet - indRet) * 10000.0) / 100.0);
                day.put("stockCumRet", Math.round(stockCumRet * 10000.0) / 100.0);
                day.put("indCumRet", Math.round(indCumRet * 10000.0) / 100.0);
                day.put("rsRatio", Math.round(rsRatio * 100.0) / 100.0);
                series.add(day);

                dates.add(td);
                stockCumList.add(Math.round(stockCumRet * 10000.0) / 100.0);
                indCumList.add(Math.round(indCumRet * 10000.0) / 100.0);
                rsRatioList.add(Math.round(rsRatio * 100.0) / 100.0);
            }

            result.put("series", series);
            result.put("dates", dates);
            result.put("stockCumRet", stockCumList);
            result.put("indCumRet", indCumList);
            result.put("rsRatio", rsRatioList);
            result.put("totalDays", series.size());

            // 5. 统计汇总
            if (!series.isEmpty()) {
                double latestStockCum = stockCumList.getLast();
                double latestIndCum = indCumList.getLast();
                double latestRs = rsRatioList.getLast();
                result.put("latestStockCumRet", latestStockCum);
                result.put("latestIndCumRet", latestIndCum);
                result.put("latestExcessRet", Math.round((latestStockCum - latestIndCum) * 100.0) / 100.0);
                result.put("latestRsRatio", latestRs);

                // 超额收益为正的天数占比
                long exceedDays = series.stream()
                        .filter(d -> ((Number) d.get("excessRet")).doubleValue() > 0)
                        .count();
                result.put("exceedDays", exceedDays);
                result.put("exceedRatio", Math.round((double) exceedDays / series.size() * 10000) / 100.0);

                // RS Ratio 描述
                String rsDesc;
                if (latestRs > 1.5) rsDesc = "显著强于行业（RS>1.5）";
                else if (latestRs > 1.1) rsDesc = "明显强于行业（RS>1.1）";
                else if (latestRs > 0.9) rsDesc = "与行业同步（RS 0.9~1.1）";
                else if (latestRs > 0.5) rsDesc = "弱于行业（RS 0.5~0.9）";
                else rsDesc = "显著弱于行业（RS<0.5）";
                result.put("rsDesc", rsDesc);
            }

        } catch (Exception e) {
            log.error("相对强弱分析失败: code={}, error={}", code, e.getMessage(), e);
            result.put("error", "计算失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * K线数据（近N交易日，供前端图表使用）
     */
    public List<Map<String, Object>> getKLine(String code, int days) {
        List<DailyBarRow> bars = analysisChMapper.selectRecentDailyBars(code, days);
        if (bars == null || bars.isEmpty()) return Collections.emptyList();
        List<Map<String, Object>> result = new ArrayList<>();
        for (DailyBarRow bar : bars) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", bar.getTradeDate() != null ? bar.getTradeDate().toString() : "");
            item.put("open", bar.getOpenPrice());
            item.put("high", bar.getHighPrice());
            item.put("low", bar.getLowPrice());
            item.put("close", bar.getClosePrice());
            item.put("volume", bar.getVolume());
            item.put("changePercent", bar.getChangePercent());
            item.put("turnoverRate", bar.getTurnoverRate());
            result.add(item);
        }
        return result;
    }

    /**
     * P2 新增：个股长周期表现分析
     * 返回：YTD涨幅、相对沪深300超额收益、RS Rating（250日收益排名百分位）、行业内排名
     */
    public Map<String, Object> getStockPerformance(String code) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);

        try {
            // 1. 获取该股票行业信息
            Map<String, Object> myInfo = stockAnalysisMapper.selectStockInfo(code);
            String industry = myInfo != null ? (String) myInfo.get("industry") : null;
            result.put("industry", industry);

            // 2. 确定当年首个交易日（CH index_daily）
            String yearStartDate = getYearStartDate();
            if (yearStartDate == null) {
                result.put("error", "无法获取年度起始日期");
                return result;
            }
            result.put("yearStartDate", yearStartDate);

            // 3. 获取沪深300 YTD涨幅
            double hs300Ytd = calcIndexYtd("000300", yearStartDate);
            result.put("hs300Ytd", round2(hs300Ytd * 100));

            // 4. 获取个股YTD涨幅（从stock_daily）
            double stockYtd = calcStockYtd(code, yearStartDate);
            if (stockYtd == Double.NaN || stockYtd == Double.MAX_VALUE) {
                result.put("error", "个股数据不足");
                return result;
            }
            result.put("stockYtd", round2(stockYtd * 100));
            result.put("excessReturn", round2((stockYtd - hs300Ytd) * 100));

            // 5. RS Rating：近250日收益排名百分位（全市场）
            int rsRating = calcRsRating(code);
            result.put("rsRating", rsRating);
            result.put("rsRatingLabel", rsRatingToLabel(rsRating));

            // 6. 行业内排名（按20日涨幅）
            if (industry != null && !industry.isBlank()) {
                int indRank = calcIndustryRank(code, industry);
                int indTotal = calcIndustryTotal(industry);
                result.put("industryRank", indRank);
                result.put("industryTotal", indTotal);
                result.put("industryRankLabel", indRank + "/" + indTotal);
                result.put("industryRankPct", indTotal > 0 ? round2(indRank * 100.0 / indTotal) : null);
            }

            return result;
        } catch (Exception e) {
            log.error("个股长周期表现分析失败: code={}, error={}", code, e.getMessage(), e);
            result.put("error", e.getMessage());
            return result;
        }
    }

    /**
     * 获取当年首个交易日（以沪深300为准）
     */
    private String getYearStartDate() {
        int currentYear = java.time.LocalDate.now().getYear();
        try {
            String sql = String.format(
                "SELECT MIN(trade_date) FROM stock.index_daily WHERE code = '000300' AND trade_date >= '%d-01-01'",
                currentYear);
            Object rawDate = clickHouseJdbcTemplate.queryForObject(sql, Object.class);
            if (rawDate == null) return null;
            return rawDate instanceof LocalDate ? ((LocalDate) rawDate).toString() : rawDate.toString();
        } catch (Exception e) {
            log.warn("获取年度起始日期失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 计算指数YTD涨幅
     */
    private double calcIndexYtd(String indexCode, String yearStartDate) {
        try {
            String sql = String.format("""
                SELECT
                    (max(close_price) - min(close_price)) / min(close_price) as ytd
                FROM stock.index_daily
                WHERE code = '%s' AND trade_date >= '%s'
                """, indexCode, yearStartDate);
            Double ytd = clickHouseJdbcTemplate.queryForObject(sql, Double.class);
            return ytd != null ? ytd : 0.0;
        } catch (Exception e) {
            log.warn("计算指数YTD失败: code={}, {}", indexCode, e.getMessage());
            return 0.0;
        }
    }

    /**
     * 计算个股YTD涨幅（从stock_daily）
     * 使用子查询获取首日/末日价格，避免 maxBy/minBy（ClickHouse 26.5 不支持）
     */
    private double calcStockYtd(String code, String yearStartDate) {
        String normalized = analysisCommon.normalizeCodeForDailyCH(code);
        try {
            // 先查首日、末日两个日期
            String dateSql = String.format("""
                SELECT MIN(trade_date) as start_date, MAX(trade_date) as end_date
                FROM stock.stock_daily FINAL
                WHERE code = '%s' AND trade_date >= '%s'
                """, normalized, yearStartDate);
            Map<String, Object> dateRow = clickHouseJdbcTemplate.queryForMap(dateSql);
            // ClickHouse Date 类型返回 LocalDate，需要转 String
            Object startObj = dateRow.get("start_date");
            Object endObj = dateRow.get("end_date");
            String startDate = startObj instanceof LocalDate ? ((LocalDate) startObj).toString() : startObj.toString();
            String endDate = endObj instanceof LocalDate ? ((LocalDate) endObj).toString() : endObj.toString();
            if (startDate == null || endDate == null) return Double.NaN;

            // 查首日收盘价
            BigDecimal startPrice = null;
            String startSql = String.format(
                "SELECT close_price FROM stock.stock_daily FINAL WHERE code = '%s' AND trade_date = '%s' LIMIT 1",
                normalized, startDate);
            List<Map<String, Object>> startRows = clickHouseJdbcTemplate.queryForList(startSql);
            if (!startRows.isEmpty() && startRows.get(0).get("close_price") != null) {
                startPrice = new BigDecimal(startRows.get(0).get("close_price").toString());
            }
            // 查末日收盘价
            BigDecimal endPrice = null;
            String endSql = String.format(
                "SELECT close_price FROM stock.stock_daily FINAL WHERE code = '%s' AND trade_date = '%s' LIMIT 1",
                normalized, endDate);
            List<Map<String, Object>> endRows = clickHouseJdbcTemplate.queryForList(endSql);
            if (!endRows.isEmpty() && endRows.getFirst().get("close_price") != null) {
                endPrice = new BigDecimal(endRows.getFirst().get("close_price").toString());
            }

            if (startPrice == null || endPrice == null || startPrice.doubleValue() == 0) {
                return Double.NaN;
            }
            return endPrice.subtract(startPrice).divide(startPrice, 6, RoundingMode.HALF_UP).doubleValue();
        } catch (Exception e) {
            log.warn("计算个股YTD失败: code={}, {}", code, e.getMessage());
            return Double.NaN;
        }
    }
    /**
     * 计算RS Rating：近250日收益排名百分位（0~99）
     * 样本：全市场有≥160日数据的沪深股票
     */
    private int calcRsRating(String code) {
        String normalized = analysisCommon.normalizeCodeForDailyCH(code);
        try {
            // 近250日个股收益率（用 argMax/argMin 取首日/末日价格，已验证有效）
            String stockSql = String.format("""
                SELECT (argMax(close_price, trade_date) - argMin(close_price, trade_date))
                       / argMin(close_price, trade_date) as ret_250d
                FROM stock.stock_daily FINAL
                WHERE code = '%s' AND trade_date >= subtractDays(today(), 260)
                """, normalized);
            Double stockRet = clickHouseJdbcTemplate.queryForObject(stockSql, Double.class);
            if (stockRet == null) return 0;

            // 全市场近250日收益率分布（分位数）
            String pctSql = String.format("""
                WITH stock_ret AS (
                    SELECT code,
                           (argMax(close_price, trade_date) - argMin(close_price, trade_date))
                           / argMin(close_price, trade_date) as ret
                    FROM stock.stock_daily FINAL
                    WHERE trade_date >= subtractDays(today(), 260)
                    GROUP BY code
                    HAVING min(close_price) > 0 AND count() >= 160
                )
                SELECT
                    countIf(ret > %f) as above_count,
                    count() as total_count
                FROM stock_ret
                """, stockRet);
            Map<String, Object> pctRow = clickHouseJdbcTemplate.queryForMap(pctSql);
            long above = ((Number) pctRow.get("above_count")).longValue();
            long total = ((Number) pctRow.get("total_count")).longValue();

            if (total == 0) return 0;
            // 百分位：above/total = 比该股强的股票比例 → (1 - above/total) * 99 = 排名百分位
            int rating = (int) Math.round((1.0 - (double) above / total) * 99);
            return Math.max(0, Math.min(99, rating));
        } catch (Exception e) {
            log.warn("计算RS Rating失败: code={}, {}", code, e.getMessage());
            return 0;
        }
    }

    /**
     * 计算该股在行业内的20日涨幅排名
     */
    private int calcIndustryRank(String code, String industry) {
        String normalized = analysisCommon.normalizeCodeForDailyCH(code);
        try {
            // 先获取该股20日收益率
            String targetSql = String.format("""
                SELECT (argMax(close_price, trade_date) - min(close_price)) / min(close_price) as ret_20d
                FROM stock.stock_daily FINAL
                WHERE code = '%s' AND trade_date >= subtractDays(today(), 25)
                """, normalized);
            Double targetRet = clickHouseJdbcTemplate.queryForObject(targetSql, Double.class);
            if (targetRet == null) return 0;

            // 统计行业内收益率高于该股的股票数量
            String rankSql = """
                WITH latest AS (
                    SELECT code,
                           argMax(close_price, trade_date) as latest_close,
                           min(close_price) as min_close,
                           count() as day_count
                    FROM stock.stock_daily FINAL
                    WHERE trade_date >= subtractDays(today(), 25)
                    GROUP BY code
                    HAVING min(close_price) > 0 AND day_count >= 10
                ),
                ret20 AS (
                    SELECT l.code,
                           (l.latest_close - l.min_close) / l.min_close as ret_20d
                    FROM latest l
                    INNER JOIN stock.stock_info si ON si.code = l.code
                    WHERE si.industry = ?
                      AND si.market NOT IN ('BJ','北交所')
                )
                SELECT countIf(ret_20d > ?) + 1 as rank
                FROM ret20
                """;
            return clickHouseJdbcTemplate.queryForObject(rankSql, Integer.class, industry, targetRet);
        } catch (Exception e) {
            log.warn("计算行业内排名失败: industry={}, {}", industry, e.getMessage());
            return 0;
        }
    }

    /**
     * 计算行业内股票总数
     */
    private int calcIndustryTotal(String industry) {
        try {
            String sql = """
                SELECT COUNT(DISTINCT sd.code) as cnt
                FROM stock.stock_daily sd FINAL
                INNER JOIN stock_info si ON si.code = sd.code
                WHERE si.industry = ?
                  AND si.market NOT IN ('BJ','北交所')
                  AND sd.trade_date >= subtractDays(today(), 25)
                """;
            return clickHouseJdbcTemplate.queryForObject(sql, Integer.class, industry);
        } catch (Exception e) {
            log.warn("计算行业内总数失败: industry={}, {}", industry, e.getMessage());
            return 0;
        }
    }

    private String rsRatingToLabel(int rating) {
        if (rating >= 90) return "极强（Top 10%）";
        if (rating >= 80) return "很强（Top 20%）";
        if (rating >= 70) return "较强（Top 30%）";
        if (rating >= 50) return "中等偏强";
        if (rating >= 30) return "中等偏弱";
        if (rating >= 20) return "较弱（Bottom 30%）";
        if (rating >= 10) return "很弱（Bottom 20%）";
        return "极弱（Bottom 10%）";
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

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
    private String buildExecutionPlan(TradingSignal signal, BigDecimal currentPrice,
                                       String targetPrice, String stopLossPrice, String targetPrice2,
                                       String riskLevel, String confidenceLevel) {
        if (signal == null || signal.getAction() == null) return null;
        String action = signal.getAction();

        // 根据风险等级确定批次比例
        int b1, b2, b3;
        if ("高".equals(riskLevel)) {
            b1 = 50; b2 = 30; b3 = 20; // 高风险：首批更大（买入更谨慎/卖出更果断）
        } else if ("低".equals(riskLevel)) {
            b1 = 30; b2 = 35; b3 = 35; // 低风险：首批更小（分批更均匀）
        } else {
            b1 = 40; b2 = 30; b3 = 30; // 中风险：默认比例
        }
        // 信心低 → 首批再缩小10%
        if ("低".equals(confidenceLevel)) {
            b1 = Math.max(20, b1 - 10);
            b2 = b2 + 5;
            b3 = b3 + 5;
        }

        if ("CLEAR".equals(action) || "REDUCE".equals(action)) {
            // 卖出执行方案：风险越高首批越大
            int sellB1 = "高".equals(riskLevel) ? 70 : "低".equals(riskLevel) ? 50 : 60;
            int sellB2 = 100 - sellB1 - 15;
            int sellB3 = 15;
            StringBuilder sb = new StringBuilder();
            sb.append("第一批").append(sellB1).append("%立即卖出");
            if (targetPrice != null) sb.append("；第二批").append(sellB2)
                    .append("%反弹至").append(targetPrice).append("卖出");
            if (targetPrice2 != null) sb.append("，跌破").append(targetPrice2).append("清仓剩余");
            else sb.append("；第三批").append(sellB3).append("%止损位")
                    .append(stopLossPrice != null ? stopLossPrice : "自定").append("清仓");
            return sb.toString();
        } else if ("BUY".equals(action) || "STRONG_BUY".equals(action)) {
            StringBuilder sb = new StringBuilder();
            sb.append("第一批").append(b1).append("%当前价建仓");
            if (stopLossPrice != null) sb.append("；第二批").append(b2)
                    .append("%回调至").append(stopLossPrice).append("加仓");
            if (targetPrice2 != null) sb.append("；第三批").append(b3)
                    .append("%突破").append(targetPrice2).append("追击");
            return sb.toString();
        }
        return "暂无明显买卖信号，建议观望";
    }

    /**
     * 多空辩论：从四维度信号提取多空论据，生成结论文本
     * 逻辑与 WorkflowReportService.evaluateBullBear 一致，但内联在 AnalysisService 中
     * 避免循环依赖（WorkflowReportService 依赖 AnalysisService）
     */
    private void buildBullBearDebate(AnalysisOverview overview) {
        List<BullBearArgument> bullArgs = new ArrayList<>();
        List<BullBearArgument> bearArgs = new ArrayList<>();

        TechSignal tech = overview.getTechSignal();
        MoneyFlowSignal money = overview.getMoneySignal();
        FundamentalSignal fundamental = overview.getFundamentalSignal();
        SentimentSignal sentiment = overview.getSentimentSignal();
        ResearchSignal research = overview.getResearchSignal();

        // --- 技术面规则 ---
        if (tech != null) {
            if ("BUY".equals(tech.getChanSignal())) {
                bullArgs.add(new BullBearArgument("缠论买点", "技术", "缠论出现买入信号", 5));
            }
            if ("SELL".equals(tech.getChanSignal())) {
                bearArgs.add(new BullBearArgument("缠论卖点", "技术", "缠论出现卖出信号", 4));
            }
            if (Boolean.TRUE.equals(tech.getMaBullish())) {
                bullArgs.add(new BullBearArgument("均线多头", "技术", "MA5>MA10>MA20>MA60，均线多头排列", 4));
            }
            if (Boolean.TRUE.equals(tech.getMacdGolden())) {
                bullArgs.add(new BullBearArgument("MACD金叉", "技术", "MACD出现金叉，短期动能转强", 3));
            }
            if (tech.getRsi() != null) {
                double rsi = tech.getRsi().doubleValue();
                if (rsi < 30) {
                    bullArgs.add(new BullBearArgument("RSI超卖", "技术",
                            "RSI=" + analysisCommon.formatD(rsi) + "，超卖区间存在反弹可能", 3));
                } else if (rsi > 70) {
                    bearArgs.add(new BullBearArgument("RSI超买", "技术",
                            "RSI=" + analysisCommon.formatD(rsi) + "，超买区间注意回调", 3));
                }
            }
        }

        // --- 基本面规则 ---
        if (fundamental != null) {
            if (fundamental.getPeTtm() != null) {
                double pe = fundamental.getPeTtm().doubleValue();
                if (pe > 0 && pe < 15) {
                    bullArgs.add(new BullBearArgument("低PE估值", "基本面",
                            "PE(TTM)=" + analysisCommon.formatD(pe) + "，估值偏低", 4));
                } else if (pe > 50) {
                    bearArgs.add(new BullBearArgument("高PE估值", "基本面",
                            "PE(TTM)=" + analysisCommon.formatD(pe) + "，估值偏高", 4));
                }
            }
            if (fundamental.getPb() != null) {
                double pb = fundamental.getPb().doubleValue();
                if (pb > 0 && pb < 1.5) {
                    bullArgs.add(new BullBearArgument("低PB估值", "基本面",
                            "PB=" + analysisCommon.formatD(pb) + "，破净风险低", 3));
                } else if (pb > 8) {
                    bearArgs.add(new BullBearArgument("高PB估值", "基本面",
                            "PB=" + analysisCommon.formatD(pb) + "，市净率偏高", 3));
                }
            }
            if (fundamental.getRoe() != null) {
                double roe = fundamental.getRoe().doubleValue();
                if (roe > 15) {
                    bullArgs.add(new BullBearArgument("高ROE", "基本面",
                            "ROE=" + analysisCommon.formatD(roe) + "%，盈利能力优秀", 4));
                } else if (roe < 5) {
                    bearArgs.add(new BullBearArgument("低ROE", "基本面",
                            "ROE=" + analysisCommon.formatD(roe) + "%，盈利能力偏弱", 3));
                }
            }
            if (fundamental.getRevenueYoy() != null) {
                double rev = fundamental.getRevenueYoy().doubleValue();
                if (rev > 20) {
                    bullArgs.add(new BullBearArgument("营收高增", "基本面",
                            "营收同比+" + analysisCommon.formatD(rev) + "%，成长性突出", 4));
                } else if (rev < -10) {
                    bearArgs.add(new BullBearArgument("营收下滑", "基本面",
                            "营收同比" + analysisCommon.formatD(rev) + "%，增长承压", 3));
                }
            }
            if (fundamental.getNetProfitYoy() != null) {
                double profit = fundamental.getNetProfitYoy().doubleValue();
                if (profit > 30) {
                    bullArgs.add(new BullBearArgument("利润高增", "基本面",
                            "净利润同比+" + analysisCommon.formatD(profit) + "%，盈利爆发", 4));
                } else if (profit < -20) {
                    bearArgs.add(new BullBearArgument("利润下滑", "基本面",
                            "净利润同比" + analysisCommon.formatD(profit) + "%，盈利恶化", 3));
                }
            }
            if (fundamental.getDebtRatio() != null) {
                double debt = fundamental.getDebtRatio().doubleValue();
                if (debt > 80) {
                    bearArgs.add(new BullBearArgument("高负债率", "基本面",
                            "资产负债率" + analysisCommon.formatD(debt) + "%，杠杆过高", 3));
                } else if (debt < 30) {
                    bullArgs.add(new BullBearArgument("低负债率", "基本面",
                            "资产负债率" + analysisCommon.formatD(debt) + "%，财务稳健", 2));
                }
            }
        }

        // --- 资金面规则 ---
        if (money != null) {
            if (money.getNetMain() != null) {
                double netMain = money.getNetMain().doubleValue();
                if (netMain > 0) {
                    bullArgs.add(new BullBearArgument("主力流入", "资金",
                            "主力净流入" + analysisCommon.formatMoney(netMain) + "，资金积极介入", 4));
                } else if (netMain < 0) {
                    bearArgs.add(new BullBearArgument("主力流出", "资金",
                            "主力净流出" + analysisCommon.formatMoney(Math.abs(netMain)) + "，资金撤退", 4));
                }
            }
            if (money.getVolumeRatio() != null) {
                double vr = money.getVolumeRatio().doubleValue();
                if (vr >= 2.0) {
                    bullArgs.add(new BullBearArgument("量能放大", "资金",
                            "量比=" + analysisCommon.formatD(vr) + "，成交活跃", 3));
                } else if (vr < 0.5) {
                    bearArgs.add(new BullBearArgument("量能萎缩", "资金",
                            "量比=" + analysisCommon.formatD(vr) + "，成交清淡", 2));
                }
            }
        }

        // --- 情绪面规则 ---
        if (sentiment != null) {
            if (Boolean.TRUE.equals(sentiment.getIsStrongStock())) {
                bullArgs.add(new BullBearArgument("强势股", "情绪", "近20日涨幅>30%，强势状态", 3));
            }
            if (sentiment.getLimitUpDays() != null && sentiment.getLimitUpDays() > 0) {
                bullArgs.add(new BullBearArgument("涨停基因", "情绪",
                        "近20日涨停" + sentiment.getLimitUpDays() + "次", 3));
            }
        }

        // --- 研报规则 ---
        if (research != null) {
            if (research.getResearchScore() >= 4) {
                bullArgs.add(new BullBearArgument("机构看好", "研报",
                        "最新评级" + research.getLatestRating() + "，机构积极", 3));
            }
            if (research.getReportCount() >= 5) {
                bullArgs.add(new BullBearArgument("研报密集", "研报",
                        "近90天" + research.getReportCount() + "份研报覆盖", 2));
            }
        }

        // --- 综合评分规则 ---
        if (overview.getTotalScore() != null) {
            int score = overview.getTotalScore();
            if (score >= 75) {
                bullArgs.add(new BullBearArgument("高分综合", "综合",
                        "四维度综合评分" + score + "分，整体优秀", 5));
            } else if (score <= 35) {
                bearArgs.add(new BullBearArgument("低分综合", "综合",
                        "四维度综合评分" + score + "分，整体偏弱", 4));
            }
        }

        // 按强度排序
        bullArgs.sort((a, b) -> Integer.compare(b.getStrength(), a.getStrength()));
        bearArgs.sort((a, b) -> Integer.compare(b.getStrength(), a.getStrength()));

        overview.setBullArguments(bullArgs);
        overview.setBearArguments(bearArgs);

        // 生成结论文本
        overview.setBullBearConclusion(buildBullBearConclusionText(overview, bullArgs, bearArgs));
    }

    /**
     * 生成多空辩论结论文本（精炼投资逻辑句式）
     */
    private String buildBullBearConclusionText(AnalysisOverview overview,
                                                List<BullBearArgument> bullArgs,
                                                List<BullBearArgument> bearArgs) {
        String name = overview.getName() != null ? overview.getName() : overview.getCode();
        int bullCount = bullArgs.size();
        int bearCount = bearArgs.size();
        int bullStars = bullArgs.stream().mapToInt(a -> a.getStrength()).sum();
        int bearStars = bearArgs.stream().mapToInt(a -> a.getStrength()).sum();

        // 偏向判定（基于强度而非条数）
        String bias;
        if (bullStars > bearStars + 5) bias = "偏多";
        else if (bearStars > bullStars + 5) bias = "偏空";
        else if (bullStars > bearStars) bias = "中性偏多";
        else if (bearStars > bullStars) bias = "中性偏空";
        else bias = "中性";

        // 核心看多因据（取前2条简述）
        String bullReason = bullArgs.isEmpty() ? ""
                : bullArgs.stream().limit(2).map(a -> a.getRule()).collect(Collectors.joining("、"));
        // 核心看空因据（取前2条简述）
        String bearReason = bearArgs.isEmpty() ? ""
                : bearArgs.stream().limit(2).map(a -> a.getRule()).collect(Collectors.joining("、"));

        // 构建"因为…所以…"句式
        StringBuilder sb = new StringBuilder();
        sb.append(name).append("多空强度 ").append(bullStars).append("★:").append(bearStars).append("★，").append(bias).append("。");

        if (!bullReason.isEmpty()) {
            sb.append("看多因：").append(bullReason).append("；");
        }
        if (!bearReason.isEmpty()) {
            sb.append("看空因：").append(bearReason).append("；");
        }
        // 操作建议
        if (overview.getActionName() != null) {
            sb.append("建议【").append(overview.getActionName()).append("】");
            if (overview.getRiskLevel() != null) {
                sb.append("，风险").append(overview.getRiskLevel());
            }
        }
        return sb.toString();
    }


    private String calcSuggestedPositionPct(TradingSignal signal, String confidenceLevel,
                                            FundamentalSignal fundamental, boolean isBlueChip) {
        if (signal == null) return null;
        String action = signal.getAction();
        if (action == null) return null;
        double baseLow, baseHigh;
        switch (action) {
            case "STRONG_BUY": baseLow = 8; baseHigh = 10; break;
            case "BUY":        baseLow = 5; baseHigh = 8;  break;
            case "HOLD":       baseLow = 3; baseHigh = 5;  break;
            case "REDUCE":     baseLow = 0; baseHigh = 3;  break;
            case "CLEAR":      return "0%";
            default:           return null;
        }
        double confidenceCoef = "高".equals(confidenceLevel) ? 1.0
                : "中".equals(confidenceLevel) ? 0.85 : 0.6;
        double blueChipCoef = isBlueChip ? 1.1 : 1.0;
        if (fundamental != null && fundamental.getDebtRatio() != null
                && fundamental.getDebtRatio().compareTo(BigDecimal.valueOf(80)) > 0) {
            blueChipCoef *= 0.8;
        }
        double low = baseLow * confidenceCoef * blueChipCoef;
        double high = baseHigh * confidenceCoef * blueChipCoef;
        if (high > 15) high = 15;
        if (low > high) low = high;
        if (low < 0) low = 0;
        return String.format("%.0f-%.0f%%", low, high);
    }

    /**
     * 投资分析摘要表：减仓价区间（建议持有者分批减仓的价格带）
     */
    private String calcReducePriceRange(BigDecimal currentPrice, BigDecimal resistancePrice, TradingSignal signal) {
        if (currentPrice == null || signal == null) return null;
        String action = signal.getAction();
        if ("CLEAR".equals(action) || "REDUCE".equals(action)) {
            return "建议立即减仓";
        }
        BigDecimal anchor = resistancePrice != null ? resistancePrice : currentPrice;
        BigDecimal low = anchor.multiply(BigDecimal.valueOf(0.99)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal high = anchor.multiply(BigDecimal.valueOf(1.02)).setScale(2, RoundingMode.HALF_UP);
        return low + "-" + high;
    }

    /**
     * 投资分析摘要表：风险等级
     * 算法：基础分（action反转） + 估值加分 + 负债加分 + 主力流出加分
     * 0~3 低 / 4~6 中 / 7+ 高
     */
    private String calcRiskLevel(TradingSignal signal, FundamentalSignal fundamental,
                                 MoneyFlowSignal money, BigDecimal currentPrice) {
        if (signal == null) return "中";
        int risk = 0;
        String action = signal.getAction();
        if ("CLEAR".equals(action) || "REDUCE".equals(action)) risk += 3;
        else if ("HOLD".equals(action)) risk += 1;
        if (fundamental != null) {
            if (fundamental.getPeTtm() != null) {
                double pe = fundamental.getPeTtm().doubleValue();
                if (pe > 100) risk += 3;
                else if (pe > 50) risk += 2;
                else if (pe > 30) risk += 1;
            }
            if (fundamental.getDebtRatio() != null
                    && fundamental.getDebtRatio().compareTo(BigDecimal.valueOf(70)) > 0) {
                risk += 2;
            }
        }
        if (money != null && money.getNetMain() != null
                && money.getNetMain().doubleValue() < 0) {
            risk += 1;
        }
        if (risk <= 3) return "低";
        if (risk <= 6) return "中";
        return "高";
    }

    /**
     * P1: 三方分析师独立评分
     * 保守分析师：重防守（估值+负债+现金流），轻进攻（趋势+情绪）
     * 中性分析师：四维度均衡加权（当前评分体系）
     * 激进分析师：重进攻（趋势+资金+情绪），轻防守（估值容忍度高）
     * 每方输出0-10分的综合评分 + 仓位建议 + 一句话描述
     */
    private void calcMultiAnalystScores(AnalysisOverview overview, TechSignal tech,
                                         MoneyFlowSignal money, SentimentSignal sentiment,
                                         FundamentalSignal fundamental, boolean isBlueChip,
                                         BigDecimal currentPrice, BigDecimal supportPrice,
                                         BigDecimal resistancePrice) {
        try {
            // === 保守分析师：防守导向 ===
            int conservativeScore = 5; // 起点5分
            if (fundamental != null) {
                // 估值惩罚（高PE高PB=扣分，低PE低PB=加分）
                if (fundamental.getPeTtm() != null) {
                    double pe = fundamental.getPeTtm().doubleValue();
                    if (pe > 100) conservativeScore -= 3;
                    else if (pe > 50) conservativeScore -= 2;
                    else if (pe > 30) conservativeScore -= 1;
                    else if (pe < 10) conservativeScore += 2;
                    else if (pe < 15) conservativeScore += 1;
                }
                if (fundamental.getPb() != null) {
                    double pb = fundamental.getPb().doubleValue();
                    if (pb > 8) conservativeScore -= 2;
                    else if (pb > 5) conservativeScore -= 1;
                    else if (pb < 1) conservativeScore += 2;
                    else if (pb < 2) conservativeScore += 1;
                }
                // 资产负债率
                if (fundamental.getDebtRatio() != null) {
                    double dr = fundamental.getDebtRatio().doubleValue();
                    if (dr > 80) conservativeScore -= 2;
                    else if (dr > 60) conservativeScore -= 1;
                    else if (dr < 30) conservativeScore += 1;
                }
                // PE分位高=扣分
                if (fundamental.getPePercentile() != null) {
                    double pct = fundamental.getPePercentile().doubleValue();
                    if (pct > 90) conservativeScore -= 2;
                    else if (pct > 70) conservativeScore -= 1;
                    else if (pct < 20) conservativeScore += 1;
                }
            }
            // 技术面微弱加分（保守派不太看技术）
            if (tech != null && "BUY".equals(tech.getChanSignal())) conservativeScore += 1;
            conservativeScore = Math.max(1, Math.min(10, conservativeScore));
            overview.setConservativeScore(conservativeScore);

            // 保守仓位：评分≤3→清仓，≤5→10-15%，≤6→20-25%，>6→30%
            int conservativePos;
            if (conservativeScore <= 3) conservativePos = 0;
            else if (conservativeScore <= 5) conservativePos = 12;
            else if (conservativeScore <= 6) conservativePos = 22;
            else conservativePos = 30;
            overview.setConservativePosition(conservativePos + "%");
            overview.setConservativeDesc(conservativeScore <= 3 ? "极端保守，建议空仓" :
                conservativeScore <= 5 ? "偏保守，低仓试探" :
                conservativeScore <= 6 ? "谨慎乐观，适度参与" : "相对看好，中仓持有");

            // === 中性分析师：当前评分归一化到10分 ===
            int totalScore = overview.getTotalScore() != null ? overview.getTotalScore() : 50;
            int neutralScore = Math.max(1, Math.min(10, (int) Math.round(totalScore / 13.5))); // 135→10
            overview.setNeutralScore(neutralScore);
            int neutralPos = overview.getPosition() != null ? overview.getPosition() : 30;
            overview.setNeutralPosition(neutralPos + "%");
            overview.setNeutralDesc(neutralScore >= 7 ? "四维度均衡看多" :
                neutralScore >= 4 ? "中性偏谨慎" : "结构性问题需警惕");

            // === 激进分析师：进攻导向 ===
            int aggressiveScore = 5;
            if (tech != null) {
                // 趋势加分
                if ("BULLISH".equals(tech.getTrend())) aggressiveScore += 2;
                else if ("SIDEWAYS".equals(tech.getTrend())) aggressiveScore += 1;
                if ("BUY".equals(tech.getChanSignal())) aggressiveScore += 1;
                // 量能加分
                if (tech.getVolumeRatio() != null && tech.getVolumeRatio().doubleValue() > 1.5) aggressiveScore += 1;
            }
            if (fundamental != null) {
                // 增速加分（激进派重成长）
                if (fundamental.getRevenueYoy() != null) {
                    double revYoy = fundamental.getRevenueYoy().doubleValue();
                    if (revYoy > 30) aggressiveScore += 2;
                    else if (revYoy > 15) aggressiveScore += 1;
                    else if (revYoy < -10) aggressiveScore -= 2;
                }
                if (fundamental.getNetProfitYoy() != null) {
                    double npYoy = fundamental.getNetProfitYoy().doubleValue();
                    if (npYoy > 50) aggressiveScore += 2;
                    else if (npYoy > 20) aggressiveScore += 1;
                    else if (npYoy < -20) aggressiveScore -= 2;
                }
                // 估值容忍（高PE不减分，低PE加分）
                if (fundamental.getPeTtm() != null) {
                    double pe = fundamental.getPeTtm().doubleValue();
                    if (pe < 15) aggressiveScore += 1;
                    // PE>100 不减分（激进派看重成长而非当前估值）
                }
            }
            if (money != null) {
                if (money.getNetMain() != null && money.getNetMain().doubleValue() > 1e8) aggressiveScore += 1;
            }
            aggressiveScore = Math.max(1, Math.min(10, aggressiveScore));
            overview.setAggressiveScore(aggressiveScore);

            int aggressivePos;
            if (aggressiveScore >= 8) aggressivePos = 70;
            else if (aggressiveScore >= 6) aggressivePos = 50;
            else if (aggressiveScore >= 4) aggressivePos = 30;
            else aggressivePos = 10;
            overview.setAggressivePosition(aggressivePos + "%");
            overview.setAggressiveDesc(aggressiveScore >= 8 ? "强烈看多，重仓出击" :
                aggressiveScore >= 6 ? "看好成长，中等仓位" :
                aggressiveScore >= 4 ? "谨慎参与，轻仓观察" : "回避风险");

        } catch (Exception e) {
            log.warn("三方分析师评分计算失败: code={}, error={}", overview.getCode(), e.getMessage());
        }
    }

    /**
     * P0: 尾部风险暴露度表（动态计算版）
     * 概率/影响/潜在跌幅均基于实际数据计算，不再硬编码
     */
    private List<TailRisk> buildTailRisks(String code, FundamentalSignal fs,
                                           Map<String, Object> stockInfo,
                                           BigDecimal currentPrice) {
        List<TailRisk> risks = new ArrayList<>();
        if (fs == null) return risks;

        // 动态参数：市值 + CH 历史波动率
        BigDecimal totalMarketCap = (stockInfo != null && stockInfo.get("totalMarketCap") != null)
                ? new BigDecimal(stockInfo.get("totalMarketCap").toString()) : null;
        Double annualVol = null;
        try { annualVol = clickHouseStockService.getHistoricalVolatility(code); } catch (Exception ignore) {}
        String impactLevel  = calcImpactLevel(totalMarketCap);
        String drawdown    = calcPotentialDrawdown(annualVol, totalMarketCap);

        // 1. 估值泡沫风险（PE>100且PE分位>80%）
        if (fs.getPeTtm() != null && fs.getPePercentile() != null) {
            double pe = fs.getPeTtm().doubleValue();
            double pePct = fs.getPePercentile().doubleValue();
            if (pe > 100 && pePct > 80) {
                // 概率：PE 越高、分位越极端 → 概率越大
                double peScore = Math.min(1.0, pe / 300.0);
                double pctScore = pePct / 100.0;
                double comb = (peScore + pctScore) / 2.0;
                double prob = 5.0 + comb * 18.0;
                prob = Math.max(3.0, Math.min(25.0, prob));
                int pLow = (int) Math.floor(prob - 1);
                int pHigh = (int) Math.ceil(prob + 1);
                pLow = Math.max(2, Math.min(24, pLow));
                pHigh = Math.max(pLow + 1, Math.min(26, pHigh));
                risks.add(new TailRisk("估值泡沫破裂",
                        pLow + "-" + pHigh + "%",
                        "毁灭性", drawdown,
                        String.format("实际PE(%.0f)>100x阈值且分位(%.0f%%)>80%%阈值，估值泡沫信号强烈", pe, pePct),
                        "PE=" + String.format("%.0f", pe) + "x，" + String.format("%.0f", pePct) + "%历史分位",
                        "VALUATION"));
            } else if (pe > 50 && pePct > 70) {
                double prob = 4.0 + (pePct - 70) / 30.0 * 10.0;
                prob = Math.max(3.0, Math.min(20.0, prob));
                int pLow = (int) Math.floor(prob - 1);
                int pHigh = (int) Math.ceil(prob + 1);
                pLow = Math.max(2, Math.min(19, pLow));
                pHigh = Math.max(pLow + 1, Math.min(21, pHigh));
                risks.add(new TailRisk("估值回归压力",
                        pLow + "-" + pHigh + "%",
                        impactLevel, drawdown,
                        String.format("实际PE(%.0f)>50x阈值且分位(%.0f%%)>70%%阈值，存在均值回归压力", pe, pePct),
                        "PE=" + String.format("%.0f", pe) + "x，分位" + String.format("%.0f", pePct) + "%",
                        "VALUATION"));
            }
        }

        // 2. 商誉减值风险
        if (fs.getGoodwill() != null) {
            double goodwill = fs.getGoodwill().doubleValue();
            BigDecimal totalAssets = stockInfo != null && stockInfo.get("total_assets") != null
                    ? new BigDecimal(stockInfo.get("total_assets").toString()) : null;
            if (totalAssets != null && totalAssets.doubleValue() > 0) {
                double ratio = goodwill / totalAssets.doubleValue();
                String prob = calcTailRiskProbability(ratio, 0.15);
                String dd   = (annualVol != null && annualVol > 0.01)
                        ? calcPotentialDrawdown(annualVol * 1.2, null)  // 商誉减值跌幅更大
                        : drawdown;
                if (ratio > 0.2) {
                    risks.add(new TailRisk("商誉减值", prob,
                            "重大", dd,
                            String.format("商誉占比(%.0f%%)逾20%%高阈值，收购标的业绩下滑即可触发减值", ratio * 100),
                            "商誉" + formatAmount(goodwill) + "，占总资产" + String.format("%.0f", ratio * 100) + "%",
                            "FINANCIAL"));
                } else if (ratio > 0.1 && goodwill > 3e8) {
                    risks.add(new TailRisk("商誉风险关注", prob,
                            "中等", dd,
                            String.format("商誉占比(%.0f%%)超10%%关注线且商誉>3亿，需持续跟踪", ratio * 100),
                            "商誉" + formatAmount(goodwill) + "，占比" + String.format("%.0f", ratio * 100) + "%",
                            "FINANCIAL"));
                }
            }
        }

        // 3. 存货崩塌风险
        if (fs.getInventory() != null) {
            double inventory = fs.getInventory().doubleValue();
            BigDecimal totalAssets = stockInfo != null && stockInfo.get("total_assets") != null
                    ? new BigDecimal(stockInfo.get("total_assets").toString()) : null;
            if (totalAssets != null && totalAssets.doubleValue() > 0) {
                double ratio = inventory / totalAssets.doubleValue();
                String prob = calcTailRiskProbability(ratio, 0.15);
                String dd  = (annualVol != null && annualVol > 0.01)
                        ? calcPotentialDrawdown(annualVol * 1.3, null)
                        : drawdown;
                if (ratio > 0.25) {
                    risks.add(new TailRisk("存货积压减值", prob,
                            "严重", dd,
                            String.format("存货占比(%.0f%%)逾25%%高阈值，需求萎缩或跌价均可触发减值", ratio * 100),
                            "存货" + formatAmount(inventory) + "，占总资产" + String.format("%.0f", ratio * 100) + "%",
                            "FINANCIAL"));
                } else if (ratio > 0.15 && inventory > 10e8) {
                    risks.add(new TailRisk("存货周转压力", prob,
                            "中等", dd,
                            String.format("存货占比(%.0f%%)超15%%关注线且规模>10亿，下游走弱即承压", ratio * 100),
                            "存货" + formatAmount(inventory) + "，占比" + String.format("%.0f", ratio * 100) + "%",
                            "FINANCIAL"));
                }
            }
        }

        // 4. 流动性危机
        if (fs.getCurrentRatio() != null && fs.getQuickRatio() != null) {
            double cr = fs.getCurrentRatio().doubleValue();
            double qr = fs.getQuickRatio().doubleValue();
            String liqProb = calcLiquidityProbability(cr, qr, 1.5, 0.8);
            String dd       = (annualVol != null && annualVol > 0.01)
                    ? calcPotentialDrawdown(annualVol * 2.0, totalMarketCap)  // 流动性危机跌幅更大
                    : drawdown;
            if (cr < 1.0 || qr < 0.5) {
                risks.add(new TailRisk("流动性危机", liqProb,
                        "致命", dd,
                        String.format("流动比率(%.2f)<1.0低阈值或速动比率(%.2f)<0.5危机线，融资能力枯竭", cr, qr),
                        "流动比率" + String.format("%.2f", cr) + "，速动比率" + String.format("%.2f", qr),
                        "FINANCIAL"));
            } else if (cr < 1.5 && qr < 0.8) {
                risks.add(new TailRisk("流动性偏紧", liqProb,
                        impactLevel, dd,
                        String.format("流动比率(%.2f)<1.5安全线且速动比率(%.2f)<0.8警戒线，再融资渠道收窄", cr, qr),
                        "流动比率" + String.format("%.2f", cr) + "，速动比率" + String.format("%.2f", qr),
                        "FINANCIAL"));
            }
        }

        // 5. 应收账款坏账风险
        if (fs.getArTurnoverDays() != null) {
            double arDays = fs.getArTurnoverDays().doubleValue();
            String arProb = calcArProbability(arDays);
            String dd     = (annualVol != null && annualVol > 0.01)
                    ? calcPotentialDrawdown(annualVol * 1.8, null)
                    : drawdown;
            if (arDays > 180) {
                risks.add(new TailRisk("应收账款坏账", arProb,
                        "重大", dd,
                        String.format("周转天数(%.0f)>180天高危线，大客户违约概率大幅上升", arDays),
                        "应收账款周转天数" + String.format("%.0f", arDays) + "天",
                        "FINANCIAL"));
            } else if (arDays > 120) {
                risks.add(new TailRisk("回款周期偏长", arProb,
                        "中等", dd,
                        String.format("周转天数(%.0f)>120天关注线，下游回款周期明显拉长", arDays),
                        "应收账款周转天数" + String.format("%.0f", arDays) + "天",
                        "FINANCIAL"));
            }
        }

        return risks;
    }

    /**
     * 格式化金额（亿/万）
     */
    private String formatAmount(double amount) {
        if (amount >= 1e8) return String.format("%.1f亿", amount / 1e8);
        if (amount >= 1e4) return String.format("%.0f万", amount / 1e4);
        return String.format("%.0f", amount);
    }

    // ============================================================
    // 尾部风险动态计算 Helper
    // ============================================================

    /**
     * 动态计算尾部风险发生概率
     * 基准 3%（行业常态），财务指标距阈值越远概率越高，单因子最高 +18%
     * 结果钳位 [2%, 25%]，输出格式 "X-Y%"
     */
    private String calcTailRiskProbability(double actual, double threshold) {
        double distance = Math.max(0, (threshold - actual) / Math.max(threshold, 0.01));
        double prob = 3.0 + distance * 18.0;
        prob = Math.max(2.0, Math.min(25.0, prob));
        int low  = Math.max(1,  Math.min(24, (int) Math.floor(prob) - 1));
        int high = Math.max(2,  Math.min(25, (int) Math.ceil(prob) + 1));
        return low + "-" + high + "%";
    }

    /**
     * 动态计算尾部风险潜在跌幅
     * 优先用 CH 历史年化波动率 × 危机乘数(1.5~2.5)
     * CH 不可用则用市值分级经验值兜底
     */
    private String calcPotentialDrawdown(Double annualVol, BigDecimal totalMarketCap) {
        if (annualVol != null && annualVol > 0.01) {
            double ddLow  = annualVol * 1.5;
            double ddHigh = annualVol * 2.5;
            int lowPct  = Math.max(5,  Math.min(65, (int) Math.floor(ddLow  * 100)));
            int highPct = Math.max(lowPct + 1, Math.min(70, (int) Math.ceil(ddHigh * 100)));
            return lowPct + "-" + highPct + "%";
        }
        // 兜底：按市值分级
        if (totalMarketCap == null) return "20-30%";
        double cap = totalMarketCap.doubleValue();
        if (cap > 1000e8) return "15-25%";
        if (cap > 100e8)  return "20-35%";
        return "30-50%";
    }

    /**
     * 动态计算影响程度（基于总市值）
     * 大市值 → 市场消化能力强 → 影响较小
     */
    private String calcImpactLevel(BigDecimal totalMarketCap) {
        if (totalMarketCap == null) return "重大";
        double cap = totalMarketCap.doubleValue();
        if (cap > 1000e8) return "中等";
        if (cap > 100e8)  return "重大";
        return "致命";
    }

    /**
     * 流动性危机专用：同时考虑流动比率和速动比率，取更危险者的概率
     */
    private String calcLiquidityProbability(double cr, double qr,
                                             double thresholdCr, double thresholdQr) {
        double distCr  = Math.max(0, (thresholdCr  - cr)  / Math.max(thresholdCr, 0.01));
        double distQr  = Math.max(0, (thresholdQr  - qr)  / Math.max(thresholdQr, 0.01));
        double dist    = Math.max(distCr, distQr);
        double prob    = 3.0 + dist * 18.0;
        prob = Math.max(2.0, Math.min(25.0, prob));
        int low  = Math.max(1,  Math.min(24, (int) Math.floor(prob) - 1));
        int high = Math.max(2,  Math.min(25, (int) Math.ceil(prob) + 1));
        return low + "-" + high + "%";
    }

    /**
     * 应收账款风险概率：周转天数越长 → 概率越高
     * 基准 120 天，超过后每 60 天 +12% 概率，钳位 [2%, 25%]
     */
    private String calcArProbability(double arDays) {
        double excess = Math.max(0, arDays - 120.0);
        double prob = 3.0 + excess / 60.0 * 12.0;
        prob = Math.max(2.0, Math.min(25.0, prob));
        int low  = Math.max(1,  Math.min(24, (int) Math.floor(prob) - 1));
        int high = Math.max(low + 1, Math.min(26, (int) Math.ceil(prob) + 1));
        return low + "-" + high + "%";
    }

    // ============================================================

    /**
     * P0: 催化剂追踪矩阵
     * 从基本面信号、事件面信号、研报信号提取正面/负面催化剂，双列展示
     */
    private List<CatalystItem> buildCatalysts(String code, FundamentalSignal fs,
                                               SentimentSignal ss, ResearchSignal rs) {
        List<CatalystItem> catalysts = new ArrayList<>();

        // === 正面催化剂 ===
        // 从基本面提取
        if (fs != null) {
            if (fs.getRevenueYoy() != null && fs.getRevenueYoy().doubleValue() > 20) {
                catalysts.add(new CatalystItem("营收高速增长（+" + String.format("%.0f", fs.getRevenueYoy().doubleValue()) + "%）",
                        "POSITIVE", "Q2维持同等增速", 4, "FINANCE"));
            }
            if (fs.getNetProfitYoy() != null && fs.getNetProfitYoy().doubleValue() > 30) {
                catalysts.add(new CatalystItem("净利润大幅增长（+" + String.format("%.0f", fs.getNetProfitYoy().doubleValue()) + "%）",
                        "POSITIVE", "盈利质量改善（扣非同步增长）", 5, "FINANCE"));
            }
            if (fs.getDeductedNpYoY() != null && fs.getDeductedNpYoY().doubleValue() > 30) {
                catalysts.add(new CatalystItem("扣非净利润高速增长（+" + String.format("%.0f", fs.getDeductedNpYoY().doubleValue()) + "%）",
                        "POSITIVE", "主业持续向好", 5, "FINANCE"));
            }
            if (fs.getRoe() != null && fs.getRoe().doubleValue() > 15) {
                catalysts.add(new CatalystItem("ROE>15%高盈利质量",
                        "POSITIVE", "ROE维持高位", 3, "FINANCE"));
            }
            if (fs.getOperatingCfToNp() != null && fs.getOperatingCfToNp().doubleValue() > 1.5) {
                catalysts.add(new CatalystItem("经营现金流远超净利润",
                        "POSITIVE", "现金流持续强劲", 3, "FINANCE"));
            }
        }

        // 从事件面提取
        if (ss != null) {
            if (ss.getNewsPositive30d() > 0 && ss.getNewsSentimentBias() > 0.3) {
                catalysts.add(new CatalystItem("近30日利好新闻占优（偏向" + String.format("%.0f", ss.getNewsSentimentBias() * 100) + "%）",
                        "POSITIVE", "持续正面新闻催化市场关注", 3, "NEWS"));
            }
            if (ss.getResearchReportCount90d() > 5) {
                catalysts.add(new CatalystItem("机构覆盖度提升（近90日" + ss.getResearchReportCount90d() + "篇研报）",
                        "POSITIVE", "新增机构覆盖+买入评级", 3, "EVENT"));
            }
            if (ss.getFundHolderRatio() != null && ss.getFundHolderRatio().doubleValue() > 0.05) {
                catalysts.add(new CatalystItem("基金持仓>5%流通盘",
                        "POSITIVE", "机构持续加仓", 2, "EVENT"));
            }
        }

        // 从研报提取
        if (rs != null && rs.getLatestRating() != null) {
            if ("买入".equals(rs.getLatestRating()) || "增持".equals(rs.getLatestRating())) {
                catalysts.add(new CatalystItem("最新研报" + rs.getLatestRating() + "评级",
                        "POSITIVE", "机构上调目标价", 3, "EVENT"));
            }
        }

        // === 负面催化剂 ===
        if (fs != null) {
            if (fs.getPeTtm() != null && fs.getPeTtm().doubleValue() > 100) {
                catalysts.add(new CatalystItem("PE>100x极度高估",
                        "NEGATIVE", "业绩不及预期直接暴跌", 4, "VALUATION"));
            } else if (fs.getPeTtm() != null && fs.getPeTtm().doubleValue() > 50) {
                catalysts.add(new CatalystItem("PE>50x估值偏高",
                        "NEGATIVE", "估值中枢下移或增长放缓", 3, "VALUATION"));
            }
            if (fs.getPePercentile() != null && fs.getPePercentile().doubleValue() > 80) {
                catalysts.add(new CatalystItem("PE处于历史" + String.format("%.0f", fs.getPePercentile().doubleValue()) + "%分位高位",
                        "NEGATIVE", "均值回归压力", 3, "VALUATION"));
            }
            if (fs.getDebtRatio() != null && fs.getDebtRatio().doubleValue() > 70) {
                catalysts.add(new CatalystItem("资产负债率" + String.format("%.0f", fs.getDebtRatio().doubleValue()) + "%偏高",
                        "NEGATIVE", "利率上行或融资收紧", 3, "FINANCE"));
            }
            if (fs.getRevenueYoy() != null && fs.getRevenueYoy().doubleValue() < -10) {
                catalysts.add(new CatalystItem("营收大幅下滑（" + String.format("%.0f", fs.getRevenueYoy().doubleValue()) + "%）",
                        "NEGATIVE", "持续下滑确认衰退趋势", 4, "FINANCE"));
            }
            if (fs.getDeductedNpYoY() != null && fs.getDeductedNpYoY().doubleValue() < -20) {
                catalysts.add(new CatalystItem("扣非净利润大幅下滑",
                        "NEGATIVE", "主业盈利恶化", 4, "FINANCE"));
            }
        }

        // 从事件面提取
        if (ss != null) {
            if (ss.getNewsNegative30d() > 5 && ss.getNewsSentimentBias() < -0.3) {
                catalysts.add(new CatalystItem("近30日风险新闻频现（偏向" + String.format("%.0f", ss.getNewsSentimentBias() * 100) + "%）",
                        "NEGATIVE", "负面舆情持续发酵", 3, "NEWS"));
            }
            if (ss.getResearchReportCount90d() == 0) {
                catalysts.add(new CatalystItem("近90日零研报覆盖",
                        "NEGATIVE", "机构不关注=淘汰信号", 2, "EVENT"));
            }
        }

        return catalysts;
    }

    /**
     * 信心水平：基于数据完整性评分（低/中/高）
     * 研报覆盖 + 基本面数据完整度 + 技术信号
     */
    private String calcConfidenceLevel(FundamentalSignal fundamental, ResearchSignal research) {
        int score = 0;
        // 有研报覆盖（reportCount是int原始类型，无法判空）
        if (research != null && research.getReportCount() > 0) score += 3;
        // PE/PB/ROE 数据完整
        if (fundamental != null) {
            if (fundamental.getPeTtm() != null && fundamental.getPeTtm().doubleValue() > 0) score += 2;
            if (fundamental.getRoe() != null && fundamental.getRoe().doubleValue() > 0) score += 2;
            if (fundamental.getRevenueYoy() != null) score += 1;
        }
        if (score >= 6) return "高";
        if (score >= 3) return "中";
        return "低";
    }

    /**
     * 新闻面评分（满分10分，供评分引擎使用）
     * 规则：有新闻+1，利好偏多+3，利好远超风险+2，有事件标签+2，情感偏向强烈+2
     */
    private int calcNewsScore(int positive, int negative, int tagged, double sentimentBias) {
        int score = 0;
        if (positive + negative > 0) score += 1;  // 有新闻
        if (positive > negative) score += 3;       // 利好偏多
        else if (positive > 0 && negative == 0) score += 2;  // 纯利好
        if (tagged > 0) score += 2;                // 有重大事件标签
        if (sentimentBias > 0.5) score += 2;       // 强烈利好偏向
        else if (sentimentBias < -0.5) score -= 1; // 强烈风险偏向
        return Math.max(0, Math.min(10, score));
    }

    /**
     * 股东结构分析（Tab：股东结构）
     * 返回：股东人数趋势 + 基金持仓明细 + 筹码集中度信号
     */
}
