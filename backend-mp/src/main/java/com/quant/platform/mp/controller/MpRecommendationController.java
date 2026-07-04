package com.quant.platform.mp.controller;

import com.quant.platform.common.dto.ApiResponse;
import com.quant.platform.recommendation.domain.StockRecommendation;
import com.quant.platform.recommendation.mapper.RecommendationMapper;
import com.quant.platform.strategy.domain.StrategyDefinition;
import com.quant.platform.strategy.mapper.StrategyDefinitionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
public class MpRecommendationController {

    private final RecommendationMapper recommendationMapper;
    private final StrategyDefinitionMapper strategyDefinitionMapper;

    /**
     * 获取有推荐数据的策略列表
     */
    @GetMapping("/strategies")
    public ApiResponse<List<Map<String, Object>>> getStrategies(
            @RequestParam(required = false) String keyword) {
        try {
            List<Map<String, Object>> list = strategyDefinitionMapper.findStrategiesWithData(keyword);
            return ApiResponse.success(list);
        } catch (Exception e) {
            log.error("获取策略列表失败", e);
            return ApiResponse.error("获取策略列表失败: " + e.getMessage());
        }
    }

    /**
     * 获取某策略可用日期列表
     */
    @GetMapping("/dates")
    public ApiResponse<List<LocalDate>> getDates(
            @RequestParam Long strategyId,
            @RequestParam(defaultValue = "30") int days) {
        try {
            List<LocalDate> dates = recommendationMapper.findDatesByStrategyId(strategyId, days);
            return ApiResponse.success(dates);
        } catch (Exception e) {
            log.error("获取日期列表失败", e);
            return ApiResponse.error("获取日期列表失败: " + e.getMessage());
        }
    }

    /**
     * 按策略+日期获取推荐（精简字段）
     */
    @GetMapping("/strategy/{strategyId}/date/{date}")
    public ApiResponse<List<Map<String, Object>>> getByStrategyAndDate(
            @PathVariable Long strategyId,
            @PathVariable String date) {
        try {
            List<StockRecommendation> recs = recommendationMapper.findByStrategyAndDate(
                    strategyId, LocalDate.parse(date));
            List<Map<String, Object>> result = recs.stream()
                    .map(this::toSimplified)
                    .collect(Collectors.toList());
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("按策略日期获取推荐失败", e);
            return ApiResponse.error("获取推荐失败: " + e.getMessage());
        }
    }

    /**
     * 获取最新推荐列表（精简版）
     */
    @GetMapping("/latest")
    public ApiResponse<List<Map<String, Object>>> getLatest(
            @RequestParam Long strategyId) {
        try {
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
        } catch (Exception e) {
            log.error("获取最新推荐失败", e);
            return ApiResponse.error("获取推荐失败: " + e.getMessage());
        }
    }

    /**
     * 批次历史表现汇总
     */
    @GetMapping("/batch-history")
    public ApiResponse<List<Map<String, Object>>> getBatchHistory(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) Long strategyId) {
        try {
            List<Map<String, Object>> list = recommendationMapper.findBatchHistory(limit, strategyId);
            return ApiResponse.success(list);
        } catch (Exception e) {
            log.error("获取批次历史失败", e);
            return ApiResponse.error("获取批次历史失败: " + e.getMessage());
        }
    }

    /**
     * 命中率统计
     */
    @GetMapping("/hit-rate/strategy/{strategyId}/date/{date}")
    public ApiResponse<Map<String, Object>> getHitRate(
            @PathVariable Long strategyId,
            @PathVariable String date) {
        try {
            Map<String, Object> result = recommendationMapper.calcHitRate(strategyId, LocalDate.parse(date));
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("获取命中率失败", e);
            return ApiResponse.error("获取命中率失败: " + e.getMessage());
        }
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
}
