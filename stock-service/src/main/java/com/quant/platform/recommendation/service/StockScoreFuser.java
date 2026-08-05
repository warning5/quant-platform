package com.quant.platform.recommendation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.recommendation.domain.StockRecommendation;
import com.quant.platform.screen.dto.ScreenResult;
import com.quant.platform.stock.analysis.domain.AnalysisOverview;
import com.quant.platform.stock.analysis.domain.ScoreDetail;
import com.quant.platform.stock.analysis.service.AnalysisService;
import com.quant.platform.stock.analysis.service.NewsEventParser;
import com.quant.platform.strategy.domain.StrategyDefinition;
import com.quant.platform.strategy.mapper.StrategyDefinitionMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 个股深度分析与融合评分：多维打分融合、风险/流动性评分。
 * <p>由 RecommendationService 拆出（Phase4），方法体逐字迁移，行为不变。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockScoreFuser {

    private final AnalysisService analysisService;
    private final StrategyDefinitionMapper strategyDefinitionMapper;
    private final ObjectMapper objectMapper;
    private final NewsEventParser newsEventParser;
    private final com.quant.platform.market.MarketSentimentService marketSentimentService;
    private final PricePlanCalculator pricePlanCalculator;



    /**
     * Regime-Adaptive 动态权重融合 (Phase 2, Phase C 升级)
     * <p>
     * 不同市场环境下，因子得分和分析得分的权重不同:
     * - BULL:   因子0.6 + 分析0.4 (动量因子在牛市更有效)
     * - BEAR:   因子0.4 + 分析0.6 (个股基本面在熊市更抗跌)
     * - SIDEWAYS: 因子0.5 + 分析0.5 (均衡)
     * <p>
     * Phase C 升级: 叠加行业轮动信号加分/扣分(±0.06)
     *
     * @param im 行业动量, 可为 null(无行业轮动信号时跳过)
     */
    double fuseScore(StockRecommendation rec, RegimeInfo regime, IndustryMomentum im) {
        double factorPart = rec.getFactorScore() != null ? rec.getFactorScore() : 0.0;
        // 尺度校验：factorScore应已归一化到0~1，若异常则截断，确保融合公平
        factorPart = Math.max(0.0, Math.min(1.0, factorPart));

        // 分析得分各维度归一化后加权
        double techPct = RecommendationMath.safeDiv(rec.getTechnicalScore(), 30.0);    // 技术面满分30
        double moneyPct = RecommendationMath.safeDiv(rec.getCapitalScore(), 25.0);    // 资金面满分25
        double eventPct = RecommendationMath.safeDiv(rec.getEventScore(), 25.0);      // 事件面满分25
        double fundPct = RecommendationMath.safeDiv(rec.getFundamentalScore(), 29.0); // 基本面满分29

        // P1-2: 风险和流动性评分归一化
        double riskPct = RecommendationMath.safeDiv(rec.getRiskScore(), 15.0);       // 风险满分15
        double liqPct = RecommendationMath.safeDiv(rec.getLiquidityScore(), 10.0);   // 流动性满分10

        // Regime-Adaptive 总权重
        double wFactor, wAnalysis;
        switch (regime.regime) {
            case "BULL" -> {
                wFactor = 0.6;
                wAnalysis = 0.4;
            }
            case "BEAR" -> {
                wFactor = 0.4;
                wAnalysis = 0.6;
            }
            default -> {
                wFactor = 0.5;
                wAnalysis = 0.5;
            }
        }

        // P1-1: 小盘风格占优时，微调因子得分权重
        if ("SMALL".equals(regime.sizeRegime)) {
            wFactor = Math.min(0.7, wFactor + 0.05);
            wAnalysis = 1.0 - wFactor;
        }

        // P1-2: 融合风险和流动性评分到最终得分
        // 分析总分从 109 分制调整为 134 分制（+风险15+流动性10）
        double adjustedAnalysisPart;
        switch (regime.regime) {
            case "BULL" -> adjustedAnalysisPart = 0.30 * techPct + 0.25 * moneyPct
                    + 0.10 * eventPct + 0.15 * fundPct
                    + 0.10 * riskPct + 0.10 * liqPct;
            case "BEAR" -> adjustedAnalysisPart = 0.15 * techPct + 0.10 * moneyPct
                    + 0.10 * eventPct + 0.35 * fundPct
                    + 0.20 * riskPct + 0.10 * liqPct;
            default -> adjustedAnalysisPart = 0.25 * techPct + 0.20 * moneyPct
                    + 0.10 * eventPct + 0.20 * fundPct
                    + 0.15 * riskPct + 0.10 * liqPct;
        }

        // P2-2: 利率环境影响权重
        if ("DOWN".equals(regime.rateRegime)) {
            // 利率下行 → 成长风格友好 → 提高技术面/资金面权重
            adjustedAnalysisPart = adjustedAnalysisPart * 0.92 + techPct * 0.04 + moneyPct * 0.04;
        } else if ("UP".equals(regime.rateRegime)) {
            // 利率上行 → 价值风格友好 → 提高基本面权重，提高风险权重
            adjustedAnalysisPart = adjustedAnalysisPart * 0.92 + fundPct * 0.05 + riskPct * 0.03;
        }

        double finalScore = wFactor * factorPart + wAnalysis * adjustedAnalysisPart;

        // Phase C: 行业轮动信号加分/扣分
        if (im != null) {
            // P2-1: 动量增强 - fusionBonus 结合动量趋势调整
            double bonus = im.fusionBonus;
            if ("ACCELERATING".equals(im.momentumTrend)) {
                bonus *= 1.5; // 动量加速时，行业信号加成放大
            } else if ("DECELERATING".equals(im.momentumTrend)) {
                bonus *= 0.5; // 动量减速时，行业信号加成缩小
            }
            finalScore += bonus;
            rec.setIndustryMomentum(im.relativeStrength);
        }

        rec.setFactorWeight(wFactor);
        rec.setAnalysisWeight(wAnalysis);
        return Math.round(finalScore * 10000.0) / 10000.0;
    }

    /**
     * 对单只股票做深度分析并融合评分
     *
     * @param im 行业动量(Phase A+C), 可为 null
     */
    StockRecommendation analyzeAndFuse(ScreenResult.StockScore stock, RegimeInfo regime, LocalDate date,
                                               IndustryMomentum im, Long strategyId) {
        StockRecommendation rec = new StockRecommendation();

        // 基本信息
        rec.setStockCode(stock.getSymbol());
        rec.setStockName(stock.getName());
        rec.setRecommendDate(date);
        rec.setFactorScore(stock.getCompositeScore());
        rec.setClosePrice(stock.getCurrentPrice() != null ? stock.getCurrentPrice().doubleValue() : null);

        // 推荐买入价（基于MA20支撑位）
        rec.setSuggestedBuyPrice(pricePlanCalculator.calcSuggestedBuyPrice(stock.getSymbol(), date));

        // 市场环境
        rec.setRegime(regime.regime);
        rec.setIndexClose(regime.indexClose);
        rec.setIndexMa20(regime.indexMa20);
        rec.setIndexMa60(regime.indexMa60);

        // 因子明细 JSON
        try {
            if (stock.getFactorRanks() != null && !stock.getFactorRanks().isEmpty()) {
                rec.setFactorRanksJson(objectMapper.writeValueAsString(stock.getFactorRanks()));
            }
        } catch (Exception ignored) {
            log.error("[StockScoreFuser] 捕获到未处理异常", ignored);
        }

        // 个股深度分析：getOverview 内部用 selectStockInfo(code) 查 stock_info 取 name，
        // stock_info.code 是纯代码（无后缀），故必须去后缀传入
        String pureCode = RecommendationMath.stripSuffix(stock.getSymbol());
        AnalysisOverview overview = analysisService.getOverview(pureCode);
        if (overview != null) {
            // 回填 stock name（getOverview 内部可能查不到 name，用 stock 的 name 兜底）
            if (overview.getName() == null && stock.getName() != null) {
                overview.setName(stock.getName());
            }
            // 只有 overview.name 非空才覆盖，避免 null 覆盖已有的 stock.getName()
            if (overview.getName() != null) {
                rec.setStockName(overview.getName());
            }
            rec.setAnalysisScore(overview.getTotalScore());
            // actionTag 映射：TradingSignalEngine 输出 5 种 (STRONG_BUY/BUY/HOLD/REDUCE/CLEAR)
            // 前端只认 3 种 (BUY/HOLD/SELL)，需要做转换
            rec.setActionTag(RecommendationMath.mapActionTag(overview.getAction()));
            // buyReason: getOverview 内部 buildConclusion 已正确生成（含 name）
            rec.setBuyReason(overview.getConclusion());

            // 从 scoreDetails 提取各维度得分
            // 维度名: tech=技术面, money=资金面, sentiment=事件面, fundamental=基本面
            if (overview.getScoreDetails() != null) {
                for (ScoreDetail detail : overview.getScoreDetails()) {
                    switch (detail.getDimension()) {
                        case "tech" -> rec.setTechnicalScore(detail.getScore());
                        case "money" -> rec.setCapitalScore(detail.getScore());
                        case "sentiment" -> rec.setEventScore(detail.getScore());
                        case "fundamental" -> rec.setFundamentalScore(detail.getScore());
                    }
                }
            }

            // 归一化到 0~1（134分满分：技术30+资金25+事件25+基本面29+风险15+流动性10）
            rec.setAnalysisScorePct(overview.getTotalScore() != null
                    ? overview.getTotalScore() / 134.0 : 0.0);
        } else {
            rec.setAnalysisScore(0);
            rec.setAnalysisScorePct(0.0);
        }

        // P1-2: 计算风险和流动性评分
        calculateRiskAndLiquidityScore(rec, overview, stock.getCurrentPrice());

        // #5+#9: 计算价格计划（止损/止盈/目标价/仓位），依赖 riskScore+liquidityScore
        pricePlanCalculator.calcPricePlan(rec, overview);

        // 新闻事件加分：估值修复/事件驱动策略，如果近30天有利好事件(增持/回购/业绩预增)，额外加分
        String strategyCode = "";
        if (strategyId != null) {
            try {
                StrategyDefinition strat = strategyDefinitionMapper.selectById(strategyId);
                strategyCode = strat != null ? strat.getStrategyCode() : "";
                boolean useEventBoost = "VALUATION_RECOVERY_LLM".equals(strategyCode)
                        || "MARKET_SENTIMENT".equals(strategyCode);
                if (useEventBoost) {
                    // A. 新闻事件加分
                    double eventSentiment = newsEventParser.getEventSentimentScore(pureCode, 30);
                    List<String> bullishEvents = newsEventParser.getRecentBullishEvents(pureCode, 30);
                    if (eventSentiment > 0.3 || !bullishEvents.isEmpty()) {
                        int currentEvent = rec.getEventScore() != null ? rec.getEventScore() : 0;
                        int bonus = Math.min(8, bullishEvents.size() * 3);
                        rec.setEventScore(Math.min(25, currentEvent + bonus));
                        if (!bullishEvents.isEmpty()) {
                            String existing = rec.getBuyReason() != null ? rec.getBuyReason() : "";
                            rec.setBuyReason(existing + " | 近期利好事件: " + String.join(",", bullishEvents));
                        }
                        log.info("[Recommendation] 新闻事件加分: strategy={}, code={}, bonus=+{}, events={}",
                                strategyCode, pureCode, bonus, bullishEvents);
                    } else if (eventSentiment < -0.3) {
                        int currentEvent = rec.getEventScore() != null ? rec.getEventScore() : 0;
                        rec.setEventScore(Math.max(0, currentEvent - 5));
                    }
                }
            } catch (Exception e) {
                log.debug("[Recommendation] 新闻事件加分查询异常: code={}, error={}", pureCode, e.getMessage());
            }
        }

        // 融合评分 (Regime-Adaptive + 行业轮动)
        rec.setFinalScore(fuseScore(rec, regime, im));

        // QVIX 市场恐慌指数调整（仅市场情绪策略）
        if (strategyId != null && "MARKET_SENTIMENT".equals(strategyCode)) {
            try {
                var qvix = marketSentimentService.getLatestQvix();
                if (qvix != null) {
                    double qvixVal = qvix.getValue().doubleValue();
                    double multiplier = 1.0;
                    String qvixNote;
                    if (qvixVal >= 35) {
                        // 市场恐慌 → 高动量股票风险加大，降分
                        multiplier = 0.85;
                        qvixNote = "QVIX=" + String.format("%.1f", qvixVal) + "(恐慌)";
                    } else if (qvixVal >= 25) {
                        // 市场担忧 → 微降
                        multiplier = 0.92;
                        qvixNote = "QVIX=" + String.format("%.1f", qvixVal) + "(担忧)";
                    } else if (qvixVal < 15) {
                        // 市场平静 → 动量策略效果好，微增
                        multiplier = 1.08;
                        qvixNote = "QVIX=" + String.format("%.1f", qvixVal) + "(平静)";
                    } else {
                        qvixNote = "QVIX=" + String.format("%.1f", qvixVal) + "(正常)";
                    }
                    double adjusted = rec.getFinalScore() * multiplier;
                    rec.setFinalScore(Math.round(adjusted * 10000.0) / 10000.0);
                    String existing = rec.getBuyReason() != null ? rec.getBuyReason() : "";
                    rec.setBuyReason(existing + " | " + qvixNote);
                    log.info("[Recommendation] QVIX调整: code={}, QVIX={}, multiplier={}, score={}",
                            pureCode, String.format("%.1f", qvixVal), String.format("%.2f", multiplier), rec.getFinalScore());
                }
            } catch (Exception e) {
                log.debug("[Recommendation] QVIX调整异常: code={}, error={}", pureCode, e.getMessage());
            }
        }

        // Phase A: 行业 Regime
        if (im != null && im.industryRegime != null) {
            rec.setIndustryRegime(im.industryRegime);
        }

        return rec;
    }

    /**
     * 计算风险和流动性评分 (P1-2)
     * <p>
     * 风险评分（0-15分）：
     * - 最大回撤（0-5分）
     * - 20日波动率（0-5分）
     * - ATR/价格比（0-5分）
     * <p>
     * 流动性评分（0-10分）：
     * - 20日均成交额（0-5分）
     * - 换手率适中度（0-5分）
     */
    void calculateRiskAndLiquidityScore(StockRecommendation rec, AnalysisOverview overview, BigDecimal currentPrice) {
        if (overview == null) return;

        // ── 风险评分（0-15分）──
        int riskScore = 0;

        // a) 最大回撤扣分（0-5分）
        if (overview.getMaxDrawdown() != null) {
            double dd = overview.getMaxDrawdown();
            if (dd < -0.10) riskScore += 0;      // 回撤>10%，0分
            else if (dd < -0.05) riskScore += 2;  // 回撤5-10%，2分
            else if (dd < -0.02) riskScore += 4;  // 回撤2-5%，4分
            else riskScore += 5;                   // 回撤<2%，满分
        }

        // b) 波动率扣分（0-5分）
        if (overview.getVolatility20d() != null) {
            double vol = overview.getVolatility20d();
            if (vol > 0.40) riskScore += 0;       // 波动率>40%，0分
            else if (vol > 0.30) riskScore += 2;   // 波动率30-40%，2分
            else if (vol > 0.20) riskScore += 4;   // 波动率20-30%，4分
            else riskScore += 5;                    // 波动率<20%，满分
        }

        // c) ATR/价格比扣分（0-5分，低波动=高分）
        if (overview.getAtr() != null && currentPrice != null && currentPrice.doubleValue() > 0) {
            double atrPct = overview.getAtr() / currentPrice.doubleValue();
            if (atrPct > 0.04) riskScore += 0;      // ATR/价格>4%，0分
            else if (atrPct > 0.03) riskScore += 2;  // 3-4%，2分
            else if (atrPct > 0.02) riskScore += 4;  // 2-3%，4分
            else riskScore += 5;                      // <2%，满分
        }

        rec.setRiskScore(riskScore);

        // ── 流动性评分（0-10分）──
        int liquidityScore = 0;

        // a) 日均成交额（0-5分）
        if (overview.getAvgAmount20d() != null) {
            double avgAmt = overview.getAvgAmount20d();
            if (avgAmt > 5e9) liquidityScore += 5;       // >50亿，5分
            else if (avgAmt > 1e9) liquidityScore += 4;   // >10亿，4分
            else if (avgAmt > 3e8) liquidityScore += 3;   // >3亿，3分
            else if (avgAmt > 1e8) liquidityScore += 2;   // >1亿，2分
            else liquidityScore += 1;                      // <1亿，1分
        }

        // b) 换手率适中度（0-5分，过高过低都扣分）
        if (overview.getTurnoverRate20d() != null) {
            double turn = overview.getTurnoverRate20d();
            if (turn >= 1.0 && turn <= 5.0) liquidityScore += 5;  // 适中，5分
            else if (turn >= 0.5 && turn <= 8.0) liquidityScore += 3; // 略偏，3分
            else liquidityScore += 1;                              // 过低或过高，1分
        }

        rec.setLiquidityScore(liquidityScore);

        log.debug("[RiskLiquidity] code={} riskScore={}/15 liquidityScore={}/10",
                rec.getStockCode(), riskScore, liquidityScore);
    }
}
