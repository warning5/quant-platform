package com.quant.platform.recommendation.controller;

import com.quant.platform.common.dto.ApiResponse;
import com.quant.platform.recommendation.domain.StrategyConfidence;
import com.quant.platform.recommendation.service.StrategyConfidenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

/**
 * 策略置信度 Controller（方案C）
 */
@Slf4j
@RestController
@RequestMapping("/strategy-confidence")
@RequiredArgsConstructor
public class StrategyConfidenceController {

    private final StrategyConfidenceService strategyConfidenceService;

    /**
     * 获取所有策略的最新置信度列表
     * GET /strategy-confidence/latest-all
     */
    @GetMapping("/latest-all")
    public ApiResponse<List<StrategyConfidence>> getAllLatest() {
        return ApiResponse.success(strategyConfidenceService.getAllLatestConfidence());
    }

    /**
     * 获取某策略的最新置信度
     * GET /strategy-confidence?strategyId=35
     */
    @GetMapping
    public ApiResponse<StrategyConfidence> getLatest(@RequestParam Long strategyId) {
        Optional<StrategyConfidence> opt = strategyConfidenceService.getLatestConfidence(strategyId);
        if (opt.isPresent()) {
            return ApiResponse.success(opt.get());
        }
        // 返回空对象（前端显示"暂无数据"）
        StrategyConfidence empty = new StrategyConfidence();
        empty.setStrategyId(strategyId);
        empty.setLevel("UNTRAINED");
        return ApiResponse.success(empty);
    }

    /**
     * 获取某策略的置信度历史趋势（用于折线图）
     * GET /strategy-confidence/history?strategyId=35
     */
    @GetMapping("/history")
    public ApiResponse<List<StrategyConfidence>> getHistory(@RequestParam Long strategyId) {
        return ApiResponse.success(strategyConfidenceService.getHistory(strategyId));
    }

    /**
     * 手动触发置信度计算（通常在追踪后自动执行，此接口用于调试/手动刷新）
     * POST /strategy-confidence/recalculate?strategyId=35
     */
    @PostMapping("/recalculate")
    public ApiResponse<StrategyConfidence> recalculate(@RequestParam Long strategyId) {
        try {
            StrategyConfidence sc = strategyConfidenceService.calculateAndSave(strategyId);
            if (sc != null) {
                log.info("[Confidence] [手动计算] strategyId={}, score={}", strategyId, sc.getScore());
                return ApiResponse.success(sc);
            }
            return ApiResponse.error("该策略暂无足够的追踪数据，无法计算置信度");
        } catch (Exception e) {
            log.error("[Confidence] 计算异常: strategyId={}, error={}", strategyId, e.getMessage(), e);
            return ApiResponse.error("计算失败: " + e.getMessage());
        }
    }

    /**
     * 获取调整后的 topN 建议
     * GET /strategy-confidence/adjusted-topn?strategyId=35&originalTopN=20
     */
    @GetMapping("/adjusted-topn")
    public ApiResponse<Integer> getAdjustedTopN(@RequestParam Long strategyId,
                                           @RequestParam(defaultValue = "20") int originalTopN) {
        Optional<StrategyConfidence> opt = strategyConfidenceService.getLatestConfidence(strategyId);
        int adjusted = strategyConfidenceService.getAdjustedTopN(originalTopN, opt.orElse(null));
        return ApiResponse.success(adjusted);
    }
}
