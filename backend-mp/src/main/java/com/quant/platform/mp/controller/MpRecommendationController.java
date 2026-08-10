package com.quant.platform.mp.controller;

import com.quant.platform.common.dto.ApiResponse;
import com.quant.platform.recommendation.domain.StockRecommendation;
import com.quant.platform.recommendation.mapper.RecommendationMapper;
import com.quant.platform.strategy.domain.StrategyDefinition;
import com.quant.platform.strategy.mapper.StrategyDefinitionMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 小程序推荐接口（直连数据库，不再代理到主后端）
 */
@RestController
@RequestMapping("/mp/recommendations")
@RequiredArgsConstructor
public class MpRecommendationController {

    private final RecommendationMapper recommendationMapper;
    private final StrategyDefinitionMapper strategyDefinitionMapper;

    /**
     * 获取有推荐数据的策略列表
     */
    @GetMapping("/strategies")
    public ApiResponse<List<Map<String, Object>>> getStrategies(
            @RequestParam(required = false) String keyword) {
        List<Map<String, Object>> list = strategyDefinitionMapper.findStrategiesWithData(keyword);
        return ApiResponse.success(list);
    }

    /**
     * 获取某策略可用日期列表
     */
    @GetMapping("/dates")
    public ApiResponse<List<LocalDate>> getDates(
            @RequestParam Long strategyId,
            @RequestParam(defaultValue = "30") int days) {
        List<LocalDate> dates = recommendationMapper.findDatesByStrategyId(strategyId, days);
        return ApiResponse.success(dates);
    }

    /**
     * 按策略+日期获取推荐（精简字段）
     */
    @GetMapping("/strategy/{strategyId}/date/{date}")
    public ApiResponse<List<Map<String, Object>>> getByStrategyAndDate(
            @PathVariable Long strategyId,
            @PathVariable String date) {
        List<StockRecommendation> recs = recommendationMapper.findByStrategyAndDate(
                strategyId, LocalDate.parse(date));
        List<Map<String, Object>> result = recs.stream()
                .map(this::toSimplified)
                .collect(Collectors.toList());
        return ApiResponse.success(result);
    }

    /**
     * 获取最新推荐列表（精简版）
     */
    @GetMapping("/latest")
    public ApiResponse<List<Map<String, Object>>> getLatest(
            @RequestParam Long strategyId) {
        // 取该策略最新日期
        List<LocalDate> dates = recommendationMapper.findDatesByStrategyId(strategyId, 1);
        if (dates == null || dates.isEmpty()) {
            return ApiResponse.success(Collections.emptyList());
        }
        LocalDate latestDate = dates.get(0);
        List<StockRecommendation> recs = recommendationMapper.findByStrategyAndDate(strategyId, latestDate);
        List<Map<String, Object>> result = recs.stream()
                .map(this::toSimplified)
                .collect(Collectors.toList());
        return ApiResponse.success(result);
    }

    /**
     * 批次历史表现汇总
     */
    @GetMapping("/batch-history")
    public ApiResponse<List<Map<String, Object>>> getBatchHistory(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) Long strategyId) {
        List<Map<String, Object>> list = recommendationMapper.findBatchHistory(limit, strategyId);
        return ApiResponse.success(list);
    }

    /**
     * 命中率统计
     */
    @GetMapping("/hit-rate/strategy/{strategyId}/date/{date}")
    public ApiResponse<Map<String, Object>> getHitRate(
            @PathVariable Long strategyId,
            @PathVariable String date) {
        Map<String, Object> result = recommendationMapper.calcHitRate(strategyId, LocalDate.parse(date));
        return ApiResponse.success(result);
    }

    /**
     * 将 StockRecommendation 转为精简 Map
     */
    private Map<String, Object> toSimplified(StockRecommendation r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("strategyId", r.getStrategyId());
        m.put("stockCode", r.getStockCode());
        m.put("stockName", r.getStockName());
        m.put("recommendDate", r.getRecommendDate() != null ? r.getRecommendDate().toString() : null);
        m.put("rankNum", r.getRankNum());
        m.put("finalScore", r.getFinalScore());
        m.put("closePrice", r.getClosePrice());
        m.put("changePercent", r.getChangePercent());
        m.put("industry", r.getIndustry());
        m.put("marketCap", r.getMarketCap());
        m.put("actionTag", r.getActionTag());
        m.put("buyReason", r.getBuyReason());
        m.put("regime", r.getRegime());
        m.put("suggestedBuyPrice", r.getSuggestedBuyPrice());
        m.put("suggestedStopLoss", r.getSuggestedStopLoss());
        m.put("suggestedTakeProfit", r.getSuggestedTakeProfit());
        m.put("suggestedTargetPrice", r.getSuggestedTargetPrice());
        m.put("suggestedPositionPct", r.getSuggestedPositionPct());
        m.put("technicalScore", r.getTechnicalScore());
        m.put("capitalScore", r.getCapitalScore());
        m.put("fundamentalScore", r.getFundamentalScore());
        m.put("eventScore", r.getEventScore());
        m.put("riskScore", r.getRiskScore());
        m.put("liquidityScore", r.getLiquidityScore());
        m.put("factorScore", r.getFactorScore());
        m.put("analysisScore", r.getAnalysisScore());
        m.put("factorWeight", r.getFactorWeight());
        m.put("analysisWeight", r.getAnalysisWeight());
        m.put("industryMomentum", r.getIndustryMomentum());
        m.put("industryRegime", r.getIndustryRegime());
        m.put("nextDayReturn", r.getNextDayReturn());
        m.put("nextWeekReturn", r.getNextWeekReturn());
        m.put("nextMonthReturn", r.getNextMonthReturn());
        m.put("trackingUpdatedAt", r.getTrackingUpdatedAt() != null ? r.getTrackingUpdatedAt().toString() : null);
        return m;
    }

    /**
     * 个股推荐详情：评分明细 / 因子归因 / 买卖信号 / 风险 / 表现。
     * GET /mp/recommendations/stock/{stockCode}/detail?strategyId=&date=
     *
     * 定位逻辑：若给定 strategyId + date，取该策略当日推荐中的该股票；
     * 否则取该股票最新一条推荐记录。
     * 实时行情由前端并行调用 /mp/monitor/stocks?codes={stockCode} 获取，本接口不内嵌。
     */
    @GetMapping("/stock/{stockCode}/detail")
    public ApiResponse<Map<String, Object>> getStockDetail(
            @PathVariable String stockCode,
            @RequestParam(required = false) Long strategyId,
            @RequestParam(required = false) String date) {
        StockRecommendation rec = resolveRecommendation(stockCode, strategyId, date);
        if (rec == null) {
            return ApiResponse.success(Collections.emptyMap());
        }
        return ApiResponse.success(toDetailMap(rec));
    }

    private StockRecommendation resolveRecommendation(String stockCode, Long strategyId, String date) {
        if (strategyId != null && date != null) {
            List<StockRecommendation> list = recommendationMapper.findByStrategyAndDate(
                    strategyId, LocalDate.parse(date));
            return list.stream()
                    .filter(r -> stockCode.equals(r.getStockCode()))
                    .findFirst().orElse(null);
        }
        QueryWrapper<StockRecommendation> qw = new QueryWrapper<>();
        qw.eq("stock_code", stockCode)
                .orderByDesc("recommend_date").orderByDesc("id")
                .last("LIMIT 1");
        return recommendationMapper.selectOne(qw);
    }

    /**
     * 将 StockRecommendation 聚合为详情 Map（按原型 stock_detail 的分区组织）
     */
    private Map<String, Object> toDetailMap(StockRecommendation r) {
        Map<String, Object> detail = new LinkedHashMap<>();

        Map<String, Object> base = new LinkedHashMap<>();
        base.put("stockCode", r.getStockCode());
        base.put("stockName", r.getStockName());
        base.put("recommendDate", r.getRecommendDate() != null ? r.getRecommendDate().toString() : null);
        base.put("rankNum", r.getRankNum());
        base.put("regime", r.getRegime());
        base.put("industry", r.getIndustry());
        base.put("industryRegime", r.getIndustryRegime());
        base.put("industryMomentum", r.getIndustryMomentum());
        base.put("marketCap", r.getMarketCap());
        base.put("closePrice", r.getClosePrice());
        base.put("changePercent", r.getChangePercent());
        detail.put("base", base);

        Map<String, Object> scores = new LinkedHashMap<>();
        scores.put("finalScore", r.getFinalScore());
        scores.put("factorScore", r.getFactorScore());
        scores.put("analysisScore", r.getAnalysisScore());
        scores.put("factorWeight", r.getFactorWeight());
        scores.put("analysisWeight", r.getAnalysisWeight());
        scores.put("technicalScore", r.getTechnicalScore());
        scores.put("capitalScore", r.getCapitalScore());
        scores.put("fundamentalScore", r.getFundamentalScore());
        scores.put("eventScore", r.getEventScore());
        scores.put("riskScore", r.getRiskScore());
        scores.put("liquidityScore", r.getLiquidityScore());
        detail.put("scores", scores);

        Map<String, Object> factor = new LinkedHashMap<>();
        factor.put("factorRanks", parseFactorRanks(r.getFactorRanksJson()));
        factor.put("industryMomentum", r.getIndustryMomentum());
        factor.put("industryRegime", r.getIndustryRegime());
        detail.put("factorAttribution", factor);

        Map<String, Object> signal = new LinkedHashMap<>();
        signal.put("actionTag", r.getActionTag());
        signal.put("buyReason", r.getBuyReason());
        signal.put("suggestedBuyPrice", r.getSuggestedBuyPrice());
        signal.put("suggestedStopLoss", r.getSuggestedStopLoss());
        signal.put("suggestedTakeProfit", r.getSuggestedTakeProfit());
        signal.put("suggestedTargetPrice", r.getSuggestedTargetPrice());
        signal.put("suggestedPositionPct", r.getSuggestedPositionPct());
        detail.put("signal", signal);

        Map<String, Object> perf = new LinkedHashMap<>();
        perf.put("nextDayReturn", r.getNextDayReturn());
        perf.put("nextDayExcessReturn", r.getNextDayExcessReturn());
        perf.put("nextWeekReturn", r.getNextWeekReturn());
        perf.put("nextWeekExcessReturn", r.getNextWeekExcessReturn());
        perf.put("nextMonthReturn", r.getNextMonthReturn());
        perf.put("nextMonthExcessReturn", r.getNextMonthExcessReturn());
        perf.put("trackingUpdatedAt", r.getTrackingUpdatedAt() != null ? r.getTrackingUpdatedAt().toString() : null);
        detail.put("performance", perf);

        Map<String, Object> env = new LinkedHashMap<>();
        env.put("regime", r.getRegime());
        env.put("indexMa20", r.getIndexMa20());
        env.put("indexMa60", r.getIndexMa60());
        env.put("indexClose", r.getIndexClose());
        detail.put("marketEnv", env);

        detail.put("weightMode", r.getWeightMode());
        detail.put("realtimeNote", "实时行情请并行调用 /mp/monitor/stocks?codes=" + r.getStockCode());
        return detail;
    }

    private static Object parseFactorRanks(String json) {
        if (json == null || json.isEmpty()) return Collections.emptyList();
        try {
            return OBJECT_MAPPER.readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
}
