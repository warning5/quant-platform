package com.quant.platform.stock.controller;

import com.quant.platform.stock.analysis.domain.AnalysisOverview;
import com.quant.platform.stock.analysis.engine.TradingSignalEngine;
import com.quant.platform.stock.analysis.service.AnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一包装响应体，与前端 api.interceptors.response 约定格式一致
 */
class ApiResponse<T> {
    public int code;
    public T data;
    public String message;

    public ApiResponse(T data) {
        this.code = 200;
        this.data = data;
    }

    public ApiResponse(int code, String message) {
        this.code = code;
        this.message = message;
    }
}

/**
 * 个股分析 Controller
 * 提供四维度评分、操作建议、规则说明接口
 */
@Slf4j
@RestController
@RequestMapping("/analysis")
@RequiredArgsConstructor
public class StockAnalysisController {
    
    @Autowired(required = false)
    private AnalysisService analysisService;
    
    @Autowired(required = false)
    private TradingSignalEngine tradingSignalEngine;
    
    /**
     * 获取个股分析总览（含四维度评分）
     * GET /api/analysis/overview?code=000001
     */
    @GetMapping("/overview")
    public ResponseEntity<?> getOverview(@RequestParam String code) {
        if (analysisService == null) {
            return ResponseEntity.status(503).body(errorBody("分析服务不可用，ClickHouse未启用"));
        }
        
        if (code == null || code.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(errorBody("股票代码不能为空"));
        }
        
        try {
            AnalysisOverview overview = analysisService.getOverview(code.trim());
            return ResponseEntity.ok(new ApiResponse<>(overview));
        } catch (Exception e) {
            log.error("分析失败: code={}, error={}", code, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(errorBody("分析失败：" + e.getMessage()));
        }
    }
    
    /**
     * 获取评分规则说明
     * GET /api/analysis/score-rules
     */
    @GetMapping("/score-rules")
    public ResponseEntity<?> getScoreRules() {
        if (tradingSignalEngine == null) {
            return ResponseEntity.status(503).body(errorBody("规则引擎不可用，ClickHouse未启用"));
        }

        try {
            List<TradingSignalEngine.ScoreRule> rules = tradingSignalEngine.getScoreRules();
            return ResponseEntity.ok(new ApiResponse<>(rules));
        } catch (Exception e) {
            log.error("获取评分规则失败: error={}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(errorBody("获取规则失败：" + e.getMessage()));
        }
    }

    /**
     * 获取研报完整分析（独立 Tab 用）
     * GET /api/analysis/research?code=000001
     * 返回：评级趋势、EPS一致预期、覆盖强度、近期研报列表
     */
    @GetMapping("/research")
    public ResponseEntity<?> getResearchAnalysis(@RequestParam String code) {
        if (analysisService == null) {
            return ResponseEntity.status(503).body(errorBody("分析服务不可用，ClickHouse未启用"));
        }

        if (code == null || code.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(errorBody("股票代码不能为空"));
        }

        try {
            Map<String, Object> data = analysisService.getResearchAnalysis(code.trim());
            return ResponseEntity.ok(new ApiResponse<>(data));
        } catch (Exception e) {
            log.error("研报分析失败: code={}, error={}", code, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(errorBody("研报分析失败：" + e.getMessage()));
        }
    }
    
    /**
     * 股票联想搜索
     * GET /api/analysis/search?keyword=xxx
     * 返回：code, name, market
     */
    @GetMapping("/search")
    public ResponseEntity<?> searchStocks(@RequestParam String keyword) {
        if (analysisService == null) {
            return ResponseEntity.status(503).body(errorBody("分析服务不可用，ClickHouse未启用"));
        }
        if (keyword == null || keyword.trim().isEmpty()) {
            return ResponseEntity.ok(new ApiResponse<>(Collections.emptyList()));
        }
        try {
            List<Map<String, Object>> results = analysisService.searchStocks(keyword.trim());
            return ResponseEntity.ok(new ApiResponse<>(results));
        } catch (Exception e) {
            log.error("股票搜索失败: keyword={}, error={}", keyword, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(errorBody("搜索失败：" + e.getMessage()));
        }
    }

    private Map<String, Object> errorBody(String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", message);
        body.put("code", 500);
        return body;
    }
}
