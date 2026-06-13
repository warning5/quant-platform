package com.quant.platform.llm.controller;

import com.quant.platform.llm.LlmAnalysisService;
import com.quant.platform.llm.domain.LlmAnalysis;
import com.quant.platform.recommendation.domain.StockRecommendation;
import com.quant.platform.recommendation.service.RecommendationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM分析 API
 */
@Slf4j
@RestController
@RequestMapping("/llm")
@RequiredArgsConstructor
public class LlmAnalysisController {

    private final LlmAnalysisService llmAnalysisService;
    private final RecommendationService recommendationService;

    /** 获取今日LLM分析结果 */
    @GetMapping("/analyses")
    public ResponseEntity<Map<String, Object>> getAnalyses() {
        List<LlmAnalysis> analyses = llmAnalysisService.getLatestAnalyses();
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", analyses);
        result.put("total", analyses.size());
        return ResponseEntity.ok(result);
    }

    /** 获取指定股票的LLM分析 */
    @GetMapping("/analysis/{stockCode}")
    public ResponseEntity<Map<String, Object>> getAnalysis(@PathVariable String stockCode) {
        LlmAnalysis analysis = llmAnalysisService.getLatestAnalysis(stockCode);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", analysis);
        return ResponseEntity.ok(result);
    }

    /** 手动触发LLM推理（对当前推荐候选股执行） */
    @PostMapping("/analyze")
    public ResponseEntity<Map<String, Object>> triggerAnalysis(
            @RequestParam(defaultValue = "15") Integer topN,
            @RequestParam(required = false) Long strategyId) {

        log.info("[LlmController] 手动触发LLM推理: strategyId={}, topN={}", strategyId, topN);

        // 1. 先生成推荐候选
        List<StockRecommendation> recommendations =
                recommendationService.generateRecommendations(null, topN, strategyId, null, List.of(), true);

        // 2. 对候选执行LLM推理
        List<LlmAnalysis> analyses = llmAnalysisService.analyzeRecommendations(recommendations);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", Map.of(
                "candidateCount", recommendations.size(),
                "analyzedCount", analyses.size(),
                "analyses", analyses
        ));
        return ResponseEntity.ok(result);
    }
}
