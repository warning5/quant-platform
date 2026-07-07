package com.quant.platform.llm.controller;

import com.quant.platform.llm.LlmAnalysisService;
import com.quant.platform.llm.LlmService;
import com.quant.platform.llm.domain.LlmAnalysis;
import com.quant.platform.recommendation.domain.StockRecommendation;
import com.quant.platform.recommendation.service.RecommendationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
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
    private final LlmService llmService;

    /** 检查LLM是否启用及连通性（前端在展示AI分析按钮前可先调用） */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getLlmStatus() {
        boolean enabled = llmService.isEnabled();
        boolean apiKeyConfigured = llmService.isApiKeyConfigured();
        Map<String, Object> data = new HashMap<>();
        data.put("enabled", enabled);
        data.put("apiKeyConfigured", apiKeyConfigured);
        data.put("model", llmService.getDefaultModel());
        data.put("baseUrl", llmService.getBaseUrl());
        if (!enabled) {
            data.put("message", apiKeyConfigured
                    ? "LLM配置已读取，但启用开关为false"
                    : "LLM API Key未配置，请在 application.yml 或环境变量 LLM_API_KEY 中设置");
        }
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", data);
        return ResponseEntity.ok(result);
    }

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

    /** 对单只股票执行LLM深度分析（个股分析页"LLM深度解读"按钮调用） */
    @PostMapping("/analyze/{stockCode}")
    public ResponseEntity<Map<String, Object>> analyzeSingleStock(@PathVariable String stockCode) {
        log.info("[LlmController] 单股LLM分析: code={}", stockCode);
        try {
            LlmAnalysis analysis = llmAnalysisService.analyzeSingleStock(stockCode);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("data", analysis);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("[LlmController] 单股LLM分析失败: code={}", stockCode, e);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 500);
            result.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result);
        }
    }

    /** 手动触发LLM推理（对当前推荐候选股执行） */
    @PostMapping("/analyze")
    public ResponseEntity<Map<String, Object>> triggerAnalysis(
            @RequestParam(defaultValue = "15") Integer topN,
            @RequestParam(defaultValue = "74") Long strategyId) {

        log.info("[LlmController] 手动触发LLM推理: strategyId={}, topN={}", strategyId, topN);

        // 1. 先生成推荐候选
        List<StockRecommendation> recommendations =
                recommendationService.generateRecommendations(null, topN, strategyId, null, new ArrayList<>(), true);

        // 2. 对候选执行LLM推理
        List<LlmAnalysis> analyses = llmAnalysisService.analyzeRecommendations(recommendations);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", Map.of(
                "candidateCount", recommendations.size(),
                "analyzedCount", analyses.size(),
                "strategyId", strategyId,
                "analyses", analyses
        ));
        return ResponseEntity.ok(result);
    }
}
