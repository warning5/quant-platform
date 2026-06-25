package com.quant.platform.recommendation.controller;

import com.quant.platform.common.ratelimit.RateLimit;
import com.quant.platform.common.dto.ApiResponse;
import com.quant.platform.factor.ic.domain.FactorIcRecord;
import com.quant.platform.factor.ic.service.FactorIcService;
import com.quant.platform.monitor.IntradayMonitorService;
import com.quant.platform.recommendation.domain.StockRecommendation;
import com.quant.platform.recommendation.service.RecommendationService;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能推荐 Controller
 */
@Slf4j
@RestController
@RequestMapping("/recommendations")
public class RecommendationController {

    private final RecommendationService recommendationService;
    private final FactorIcService factorIcService;
    private final IntradayMonitorService intradayMonitorService;

    public RecommendationController(RecommendationService recommendationService, FactorIcService factorIcService, IntradayMonitorService intradayMonitorService) {
        this.recommendationService = recommendationService;
        this.factorIcService = factorIcService;
        this.intradayMonitorService = intradayMonitorService;
    }

    /**
     * 手动触发生成推荐列表
     */
    @PostMapping("/generate")
    @RateLimit(capacity = 10, duration = 1)
    public ApiResponse<Map<String, Object>> generate(@RequestBody(required = false) GenerateRequest req) {
        try {
            LocalDate date = req != null ? req.getDate() : null;
            Integer topN = req != null ? req.getTopN() : null;
            Long strategyId = req != null ? req.getStrategyId() : null;
            String weightMode = req != null ? req.getWeightMode() : null;
            Boolean enableConfidenceControl = req != null && req.getEnableConfidenceControl() != null
                    ? req.getEnableConfidenceControl() : true; // 默认开启

            List<RecommendationService.FactorDiagnostic> diagnostics = new ArrayList<>();

            List<StockRecommendation> recommendations = recommendationService.generateRecommendations(
                    date, topN, strategyId, weightMode, diagnostics, enableConfidenceControl);

            Map<String, Object> result = new HashMap<>();
            if (!recommendations.isEmpty()) {
                StockRecommendation first = recommendations.getFirst();
                result.put("strategyId", first.getStrategyId());
                result.put("recommendDate", first.getRecommendDate().toString());
            } else {
                result.put("strategyId", null);
                result.put("recommendDate", null);
            }
            result.put("count", recommendations.size());
            result.put("recommendations", recommendations);
            result.put("factorDiagnostics", diagnostics);

            // 计算并返回IC数据可用日期（IC加权时显示提醒）
            if (!diagnostics.isEmpty()) {
                List<String> fcList = diagnostics.stream()
                        .map(d -> d.factorCode)
                        .distinct()
                        .collect(java.util.stream.Collectors.toList());
                java.time.LocalDate icDate = factorIcService.getLatestCommonIcDate(fcList);
                if (icDate != null) {
                    result.put("icDataDate", icDate.toString());
                }
            }

            // 生成推荐成功后，自动刷新盘中监控目标价缓存
            try {
                intradayMonitorService.loadTargetPrices();
                log.info("[Recommendation] 已自动刷新监控目标价");
            } catch (Exception ex) {
                log.warn("[Recommendation] 刷新监控目标价失败: {}", ex.getMessage());
            }

            return ApiResponse.success("推荐列表生成成功", result);
        } catch (Exception e) {
            log.error("[Recommendation] 生成推荐失败", e);
            return ApiResponse.error("生成推荐失败: " + e.getMessage());
        }
    }

    /**
     * 获取最新推荐列表
     */
    @GetMapping("/latest")
    public ApiResponse<List<StockRecommendation>> getLatest() {
        List<StockRecommendation> list = recommendationService.getLatestRecommendations();
        return ApiResponse.success(list);
    }

    /**
     * 获取指定策略+日期的推荐列表
     */
    @GetMapping("/strategy/{strategyId}/date/{date}")
    public ApiResponse<List<StockRecommendation>> getByStrategyAndDate(
            @PathVariable Long strategyId,
            @PathVariable @JsonFormat(pattern = "yyyy-MM-dd") LocalDate date) {
        List<StockRecommendation> list = recommendationService.getRecommendationsByStrategyAndDate(strategyId, date);
        return ApiResponse.success(list);
    }

    /**
     * 获取有推荐记录的策略+日期组合列表
     */
    @GetMapping("/strategy-date-combos")
    public ApiResponse<List<Map<String, Object>>> getStrategyDateCombos(@RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(recommendationService.getStrategyDateCombos(limit));
    }

    /**
     * 获取所有有推荐记录的策略列表（用于前端筛选下拉，含名称）
     */
    @GetMapping("/strategies-with-data")
    public ApiResponse<List<Map<String, Object>>> strategiesWithData() {
        return ApiResponse.success(recommendationService.strategiesWithData());
    }

    /**
     * 触发因子 IC/IR 计算
     */
    @PostMapping("/ic/compute")
    public ApiResponse<Map<String, FactorIcRecord>> computeIc(@RequestBody(required = false) Map<String, Object> params) {
        try {
            @SuppressWarnings("unchecked")
            List<String> factorCodes = params != null ? (List<String>) params.get("factorCodes") : null;
            if (factorCodes == null || factorCodes.isEmpty()) {
                return ApiResponse.error("请指定要计算IC的因子代码（factorCodes）");
            }
            LocalDate date = params.get("date") != null
                    ? LocalDate.parse(params.get("date").toString()) : null;
            Map<String, FactorIcRecord> results = factorIcService.computeAndSaveIc(date, factorCodes);
            return ApiResponse.success("IC计算完成", results);
        } catch (Exception e) {
            log.error("[Recommendation] IC计算失败", e);
            return ApiResponse.error("IC计算失败: " + e.getMessage());
        }
    }

    /**
     * 批量计算因子 IC/IR（按日期范围）
     */
    @PostMapping("/ic/compute-batch")
    @RateLimit(capacity = 3, duration = 1)
    public ApiResponse<Map<String, Object>> computeIcBatch(@RequestBody Map<String, Object> params) {
        try {
            LocalDate startDate = LocalDate.parse(params.get("startDate").toString());
            LocalDate endDate = LocalDate.parse(params.get("endDate").toString());

            @SuppressWarnings("unchecked")
            List<String> factorCodes = (List<String>) params.get("factorCodes");
            if (factorCodes == null || factorCodes.isEmpty()) {
                return ApiResponse.error("请指定要计算IC的因子代码（factorCodes）");
            }

            Map<LocalDate, Map<String, FactorIcRecord>> results = factorIcService.computeAndSaveIcBatch(startDate, endDate, factorCodes);

            int totalDays = results.size();
            int totalRecords = results.values().stream().mapToInt(Map::size).sum();

            // 将 LocalDate key 转为 String，确保前端 JSON 解析正确
            Map<String, Map<String, FactorIcRecord>> stringKeyResults = new LinkedHashMap<>();
            for (java.util.Map.Entry<LocalDate, Map<String, FactorIcRecord>> entry : results.entrySet()) {
                stringKeyResults.put(entry.getKey().toString(), entry.getValue());
            }

            Map<String, Object> summary = new HashMap<>();
            summary.put("startDate", startDate.toString());
            summary.put("endDate", endDate.toString());
            summary.put("totalDays", totalDays);
            summary.put("totalRecords", totalRecords);
            summary.put("factorCount", factorCodes.size());
            summary.put("results", stringKeyResults);

            return ApiResponse.success(String.format("批量IC计算完成: %d因子 × %d天 = %d条记录", factorCodes.size(), totalDays, totalRecords), summary);
        } catch (Exception e) {
            log.error("[Recommendation] 批量IC计算失败", e);
            return ApiResponse.error("批量IC计算失败: " + e.getMessage());
        }
    }

    /**
     * 获取因子 IC/IR 最新摘要
     */
    @GetMapping("/ic/summary")
    public ApiResponse<List<FactorIcRecord>> getIcSummary() {
        return ApiResponse.success(factorIcService.getLatestIcSummary());
    }

    /**
     * 获取因子自适应权重
     */
    @GetMapping("/ic/weights")
    public ApiResponse<Map<String, Double>> getAdaptiveWeights() {
        return ApiResponse.success(factorIcService.getAdaptiveWeights());
    }

    /**
     * 触发推荐表现追踪
     */
    @PostMapping("/track")
    public ApiResponse<Map<String, Object>> trackPerformance() {
        try {
            int updated = recommendationService.trackRecommendationPerformance();
            Map<String, Object> result = new HashMap<>();
            result.put("updated", updated);
            return ApiResponse.success("表现追踪完成", result);
        } catch (Exception e) {
            log.error("[Recommendation] 表现追踪失败", e);
            return ApiResponse.error("表现追踪失败: " + e.getMessage());
        }
    }

    /**
     * 获取推荐命中率统计
     */
    @GetMapping("/hit-rate/strategy/{strategyId}/date/{date}")
    public ApiResponse<Map<String, Object>> getHitRate(
            @PathVariable Long strategyId,
            @PathVariable @JsonFormat(pattern = "yyyy-MM-dd") LocalDate date) {
        return ApiResponse.success(recommendationService.getHitRate(strategyId, date));
    }

    /**
     * 获取批次历史表现汇总（含质量标签，支持按策略筛选）
     * 用于前端表现追踪面板：命中趋势图 + 平均收益率统计
     */
    @GetMapping("/batch-history")
    public ApiResponse<List<Map<String, Object>>> getBatchHistory(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) Long strategyId) {
        return ApiResponse.success(recommendationService.getBatchHistory(limit, strategyId));
    }

    /**
     * 获取指定策略+日期的最佳/最差股票（推荐复盘）
     */
    @GetMapping("/top-bottom")
    public ApiResponse<Map<String, Object>> getBatchTopBottom(
            @RequestParam Long strategyId,
            @RequestParam @JsonFormat(pattern = "yyyy-MM-dd") LocalDate recommendDate) {
        return ApiResponse.success(recommendationService.getBatchTopBottom(strategyId, recommendDate));
    }

    /**
     * 生成请求体
     */
    @Data
    public static class GenerateRequest {
        /** 推荐日期，null 则使用最新可用日期 */
        @JsonFormat(pattern = "yyyy-MM-dd")
        private LocalDate date;
        /** 最终推荐数量，默认20 */
        private Integer topN;
        /** 因子组合配置: EXISTING/NORMAL/NEW_QUALITY/HOT/COMPREHENSIVE（旧版，优先使用 strategyId） */
        private String factorProfile;  // @Deprecated 已废弃，请使用 strategyId
        /** 策略ID，从策略列表选择（因子配置从数据库读取） */
        private Long strategyId;
        /** 权重模式: STATIC(固定权重) / IC(动态IC加权) */
        private String weightMode;
        /** 是否启用置信度控制（默认 true） */
        private Boolean enableConfidenceControl;
    }
}
