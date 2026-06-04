package com.quant.platform.recommendation.controller;

import com.quant.platform.common.dto.ApiResponse;
import com.quant.platform.factor.ic.domain.FactorIcRecord;
import com.quant.platform.factor.ic.service.FactorIcService;
import com.quant.platform.recommendation.domain.StockRecommendation;
import com.quant.platform.recommendation.service.RecommendationService;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    public RecommendationController(RecommendationService recommendationService, FactorIcService factorIcService) {
        this.recommendationService = recommendationService;
        this.factorIcService = factorIcService;
    }

    /**
     * 手动触发生成推荐列表
     */
    @PostMapping("/generate")
    public ApiResponse<Map<String, Object>> generate(@RequestBody(required = false) GenerateRequest req) {
        try {
            LocalDate date = req != null ? req.getDate() : null;
            Integer topN = req != null ? req.getTopN() : null;

            List<StockRecommendation> recommendations = recommendationService.generateRecommendations(date, topN);

            Map<String, Object> result = new HashMap<>();
            result.put("batchId", recommendations.isEmpty() ? null : recommendations.get(0).getBatchId());
            result.put("count", recommendations.size());
            result.put("recommendations", recommendations);

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
     * 获取指定批次推荐列表
     */
    @GetMapping("/batch/{batchId}")
    public ApiResponse<List<StockRecommendation>> getByBatch(@PathVariable String batchId) {
        List<StockRecommendation> list = recommendationService.getRecommendationsByBatch(batchId);
        return ApiResponse.success(list);
    }

    /**
     * 获取批次列表
     */
    @GetMapping("/batches")
    public ApiResponse<List<String>> getBatches(@RequestParam(defaultValue = "20") int limit) {
        List<String> batches = recommendationService.getBatchIds(limit);
        return ApiResponse.success(batches);
    }

    /**
     * 触发因子 IC/IR 计算
     */
    @PostMapping("/ic/compute")
    public ApiResponse<Map<String, FactorIcRecord>> computeIc(@RequestBody(required = false) Map<String, Object> params) {
        try {
            Map<String, FactorIcRecord> results = factorIcService.computeAndSaveIc(null);
            return ApiResponse.success("IC计算完成", results);
        } catch (Exception e) {
            log.error("[Recommendation] IC计算失败", e);
            return ApiResponse.error("IC计算失败: " + e.getMessage());
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
    @GetMapping("/hit-rate/{batchId}")
    public ApiResponse<Map<String, Object>> getHitRate(@PathVariable String batchId) {
        return ApiResponse.success(recommendationService.getHitRate(batchId));
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
    }
}
