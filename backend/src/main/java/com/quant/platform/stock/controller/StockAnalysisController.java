package com.quant.platform.stock.controller;

import com.quant.platform.stock.analysis.domain.AnalysisOverview;
import com.quant.platform.stock.analysis.engine.TradingSignalEngine;
import com.quant.platform.stock.analysis.service.AnalysisService;
import com.quant.platform.stock.analysis.service.BidAskService;
import com.quant.platform.stock.analysis.service.EventSignalService;
import com.quant.platform.stock.analysis.service.InstitutionCoverageService;
import com.quant.platform.stock.analysis.service.MarketThermometerService;
import com.quant.platform.stock.analysis.service.NewsEventParser;
import com.quant.platform.stock.analysis.service.NewsService;
import com.quant.platform.stock.analysis.service.WorkflowReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
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
    private NewsService newsService;

    @Autowired(required = false)
    private BidAskService bidAskService;

    @Autowired(required = false)
    private InstitutionCoverageService institutionCoverageService;

    @Autowired(required = false)
    private TradingSignalEngine tradingSignalEngine;

    @Autowired(required = false)
    private MarketThermometerService marketThermometerService;

    @Autowired(required = false)
    private WorkflowReportService workflowReportService;

    @Autowired(required = false)
    private NewsEventParser newsEventParser;

    @Autowired(required = false)
    private EventSignalService eventSignalService;

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

    /**
     * 同业对比
     * GET /api/analysis/peers?code=600519
     * 返回：行业名称 + 同业列表（PE/PB/市值/涨跌幅）
     */
    @GetMapping("/peers")
    public ResponseEntity<?> getPeerComparison(@RequestParam String code) {
        if (analysisService == null) {
            return ResponseEntity.status(503).body(errorBody("分析服务不可用，ClickHouse未启用"));
        }
        if (code == null || code.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(errorBody("股票代码不能为空"));
        }
        try {
            Map<String, Object> data = analysisService.getPeerComparison(code.trim());
            return ResponseEntity.ok(new ApiResponse<>(data));
        } catch (Exception e) {
            log.error("同业对比失败: code={}, error={}", code, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(errorBody("同业对比失败：" + e.getMessage()));
        }
    }

    /**
     * 估值历史分位
     * GET /api/analysis/valuation-percentile?code=600519&years=3
     * 返回：pePercentile/pbPercentile/peCurrent/pbCurrent + 分位描述
     */
    @GetMapping("/valuation-percentile")
    public ResponseEntity<?> getValuationPercentile(@RequestParam String code,
                                                     @RequestParam(defaultValue = "3") int years) {
        if (analysisService == null) {
            return ResponseEntity.status(503).body(errorBody("分析服务不可用，ClickHouse未启用"));
        }
        if (code == null || code.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(errorBody("股票代码不能为空"));
        }
        try {
            Map<String, Object> data = analysisService.getValuationPercentile(code.trim(), years);
            return ResponseEntity.ok(new ApiResponse<>(data));
        } catch (Exception e) {
            log.error("估值分位查询失败: code={}, error={}", code, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(errorBody("估值分位查询失败：" + e.getMessage()));
        }
    }

    /**
     * 行业/概念板块涨跌排行
     * GET /api/analysis/sector-ranking
     */
    @GetMapping("/sector-ranking")
    public ResponseEntity<?> getSectorRanking() {
        if (analysisService == null) {
            return ResponseEntity.status(503).body(errorBody("分析服务不可用，ClickHouse未启用"));
        }
        try {
            Map<String, Object> data = analysisService.getSectorRanking();
            return ResponseEntity.ok(new ApiResponse<>(data));
        } catch (Exception e) {
            log.error("板块排行查询失败: error={}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(errorBody("板块排行查询失败：" + e.getMessage()));
        }
    }

    /**
     * 行业内个股排名
     * GET /api/analysis/industry-stocks?industry=白酒&sortBy=changePercent&sortOrder=desc
     */
    @GetMapping("/industry-stocks")
    public ResponseEntity<?> getIndustryStocks(
            @RequestParam String industry,
            @RequestParam(defaultValue = "changePercent") String sortBy,
            @RequestParam(defaultValue = "desc") String sortOrder) {
        if (analysisService == null) {
            return ResponseEntity.status(503).body(errorBody("分析服务不可用，ClickHouse未启用"));
        }
        try {
            List<Map<String, Object>> data = analysisService.getIndustryStocks(industry, sortBy, sortOrder);
            return ResponseEntity.ok(new ApiResponse<>(data));
        } catch (Exception e) {
            log.error("行业内个股查询失败: error={}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(errorBody("行业内个股查询失败：" + e.getMessage()));
        }
    }

    /**
     * 概念板块内个股排名
     * GET /api/analysis/concept-stocks?conceptName=算力/AI&sortBy=changePercent&sortOrder=desc
     */
    @GetMapping("/concept-stocks")
    public ResponseEntity<?> getConceptStocks(
            @RequestParam String conceptName,
            @RequestParam(defaultValue = "changePercent") String sortBy,
            @RequestParam(defaultValue = "desc") String sortOrder) {
        if (analysisService == null) {
            return ResponseEntity.status(503).body(errorBody("分析服务不可用，ClickHouse未启用"));
        }
        try {
            List<Map<String, Object>> data = analysisService.getConceptStocks(conceptName, sortBy, sortOrder);
            return ResponseEntity.ok(new ApiResponse<>(data));
        } catch (Exception e) {
            log.error("概念板块个股查询失败: error={}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(errorBody("概念板块个股查询失败：" + e.getMessage()));
        }
    }

    /**
     * 行业关联分析（Beta暴露+行业联动）
     * GET /api/analysis/industry-correlation?code=600519
     */
    @GetMapping("/industry-correlation")
    public ResponseEntity<?> getIndustryCorrelation(@RequestParam String code) {
        if (analysisService == null) {
            return ResponseEntity.status(503).body(errorBody("分析服务不可用，ClickHouse未启用"));
        }
        try {
            Map<String, Object> data = analysisService.getIndustryCorrelation(code.trim());
            return ResponseEntity.ok(new ApiResponse<>(data));
        } catch (Exception e) {
            log.error("行业关联分析失败: code={}, error={}", code, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(errorBody("行业关联分析失败：" + e.getMessage()));
        }
    }

    /**
     * 涨跌停分析
     * GET /api/analysis/limit-up?code=600519
     */
    @GetMapping("/limit-up")
    public ResponseEntity<?> getLimitUpAnalysis(@RequestParam String code) {
        if (analysisService == null) {
            return ResponseEntity.status(503).body(errorBody("分析服务不可用，ClickHouse未启用"));
        }
        try {
            Map<String, Object> data = analysisService.getLimitUpAnalysis(code.trim());
            return ResponseEntity.ok(new ApiResponse<>(data));
        } catch (Exception e) {
            log.error("涨跌停分析失败: code={}, error={}", code, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(errorBody("涨跌停分析失败：" + e.getMessage()));
        }
    }

    /**
     * 大宗交易分析
     * GET /api/analysis/block-trade?code=600519
     */
    @GetMapping("/block-trade")
    public ResponseEntity<?> getBlockTradeAnalysis(@RequestParam String code) {
        if (analysisService == null) {
            return ResponseEntity.status(503).body(errorBody("分析服务不可用，ClickHouse未启用"));
        }
        try {
            Map<String, Object> data = analysisService.getBlockTradeAnalysis(code.trim());
            return ResponseEntity.ok(new ApiResponse<>(data));
        } catch (Exception e) {
            log.error("大宗交易分析失败: code={}, error={}", code, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(errorBody("大宗交易分析失败：" + e.getMessage()));
        }
    }

    /**
     * 新闻事件分析
     * GET /api/analysis/news?code=600619
     * 返回：利好/风险/中性新闻列表 + 情感偏向 + 事件标签统计 + 新闻评分
     */
    @GetMapping("/news")
    public ResponseEntity<?> getNewsAnalysis(@RequestParam String code) {
        if (newsService == null) {
            return ResponseEntity.status(503).body(errorBody("新闻服务不可用"));
        }
        if (code == null || code.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(errorBody("股票代码不能为空"));
        }
        try {
            Map<String, Object> data = newsService.getNewsAnalysis(code.trim());
            return ResponseEntity.ok(new ApiResponse<>(data));
        } catch (Exception e) {
            log.error("新闻分析失败: code={}, error={}", code, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(errorBody("新闻分析失败：" + e.getMessage()));
        }
    }

    /**
     * 按事件标签查询新闻
     * GET /api/analysis/news/tag?code=600619&tag=PERFORMANCE
     */
    @GetMapping("/news/tag")
    public ResponseEntity<?> getNewsByTag(@RequestParam String code,
                                          @RequestParam String tag) {
        if (newsService == null) {
            return ResponseEntity.status(503).body(errorBody("新闻服务不可用"));
        }
        try {
            List<Map<String, Object>> data = newsService.getNewsByTag(code.trim(), tag);
            return ResponseEntity.ok(new ApiResponse<>(data));
        } catch (Exception e) {
            log.error("标签新闻查询失败: code={}, tag={}, error={}", code, tag, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(errorBody("标签新闻查询失败：" + e.getMessage()));
        }
    }

    /**
     * 新闻事件信号（供评分引擎使用）
     * GET /api/analysis/news-signal?code=600619
     */
    @GetMapping("/news-signal")
    public ResponseEntity<?> getNewsSignal(@RequestParam String code) {
        if (newsService == null) {
            return ResponseEntity.status(503).body(errorBody("新闻服务不可用"));
        }
        try {
            Map<String, Object> data = newsService.getNewsSignal(code.trim());
            return ResponseEntity.ok(new ApiResponse<>(data));
        } catch (Exception e) {
            log.error("新闻信号获取失败: code={}, error={}", code, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(errorBody("新闻信号获取失败：" + e.getMessage()));
        }
    }

    /**
     * 内外盘比分析
     * GET /api/analysis/bid-ask
     */
    @GetMapping("/bid-ask")
    public ResponseEntity<?> getBidAskAnalysis(@RequestParam String code) {
        if (bidAskService == null) {
            return ResponseEntity.status(503).body(errorBody("内外盘比服务不可用"));
        }
        try {
            Map<String, Object> data = bidAskService.getBidAskAnalysis(code.trim());
            return ResponseEntity.ok(new ApiResponse<>(data));
        } catch (Exception e) {
            log.error("内外盘比获取失败: code={}, error={}", code, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(errorBody("内外盘比获取失败：" + e.getMessage()));
        }
    }

    /**
     * 内外盘比信号（供评分引擎使用）
     * GET /api/analysis/bid-ask-signal
     */
    @GetMapping("/bid-ask-signal")
    public ResponseEntity<?> getBidAskSignal(@RequestParam String code) {
        if (bidAskService == null) {
            return ResponseEntity.status(503).body(errorBody("内外盘比服务不可用"));
        }
        try {
            Map<String, Object> data = bidAskService.getBidAskSignal(code.trim());
            return ResponseEntity.ok(new ApiResponse<>(data));
        } catch (Exception e) {
            log.error("内外盘比信号获取失败: code={}, error={}", code, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(errorBody("内外盘比信号获取失败：" + e.getMessage()));
        }
    }

    /**
     * 机构覆盖度综合指标（Tab④ 机构跟踪）
     * GET /api/analysis/institution-coverage
     */
    @GetMapping("/institution-coverage")
    public ResponseEntity<?> getInstitutionCoverage(@RequestParam String code) {
        if (institutionCoverageService == null) {
            return ResponseEntity.status(503).body(errorBody("机构覆盖度服务不可用"));
        }
        try {
            Map<String, Object> data = institutionCoverageService.getInstitutionCoverage(code.trim());
            return ResponseEntity.ok(new ApiResponse<>(data));
        } catch (Exception e) {
            log.error("机构覆盖度获取失败: code={}, error={}", code, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(errorBody("机构覆盖度获取失败：" + e.getMessage()));
        }
    }

    /**
     * 大盘温度计
     * GET /api/analysis/market-thermometer
     * 返回：恐慌贪婪指数 + 各维度指标（PE分位/PB分位/均线温度/股债收益比/融资余额）
     */
    @GetMapping("/market-thermometer")
    public ResponseEntity<?> getMarketThermometer() {
        if (marketThermometerService == null) {
            return ResponseEntity.status(503).body(errorBody("大盘温度计服务不可用"));
        }
        try {
            Map<String, Object> data = marketThermometerService.getThermometer();
            return ResponseEntity.ok(new ApiResponse<>(data));
        } catch (Exception e) {
            log.error("大盘温度计查询失败: error={}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(errorBody("大盘温度计查询失败：" + e.getMessage()));
        }
    }

    /**
     * 缠论K线图数据
     * GET /api/analysis/chan-chart?code=600519
     * 返回：K线数据 + 笔 + 中枢 + 买卖点
     */
    @GetMapping("/chan-chart")
    public ResponseEntity<?> getChanChart(@RequestParam String code) {
        if (analysisService == null) {
            return ResponseEntity.status(503).body(errorBody("分析服务不可用，ClickHouse未启用"));
        }
        if (code == null || code.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(errorBody("股票代码不能为空"));
        }
        try {
            Map<String, Object> data = analysisService.getChanChart(code.trim());
            return ResponseEntity.ok(new ApiResponse<>(data));
        } catch (Exception e) {
            log.error("缠论K线图查询失败: code={}, error={}", code, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(errorBody("缠论K线图查询失败：" + e.getMessage()));
        }
    }

    /**
     * 资金流向历史趋势
     * GET /api/analysis/money-flow-history?code=600519&days=120
     * 返回：逐日资金流向 + 评分
     */
    @GetMapping("/money-flow-history")
    public ResponseEntity<?> getMoneyFlowHistory(@RequestParam String code,
                                                   @RequestParam(defaultValue = "120") int days) {
        if (analysisService == null) {
            return ResponseEntity.status(503).body(errorBody("分析服务不可用，ClickHouse未启用"));
        }
        if (code == null || code.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(errorBody("股票代码不能为空"));
        }
        try {
            Map<String, Object> data = analysisService.getMoneyFlowHistory(code.trim(), days);
            return ResponseEntity.ok(new ApiResponse<>(data));
        } catch (Exception e) {
            log.error("资金流向历史查询失败: code={}, error={}", code, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(errorBody("资金流向历史查询失败：" + e.getMessage()));
        }
    }

    /**
     * 相对强弱分析（个股 vs 行业）
     * GET /api/analysis/relative-strength?code=600519
     * 返回：累计收益对比 + RS Ratio
     */
    @GetMapping("/relative-strength")
    public ResponseEntity<?> getRelativeStrength(@RequestParam String code) {
        if (analysisService == null) {
            return ResponseEntity.status(503).body(errorBody("分析服务不可用，ClickHouse未启用"));
        }
        if (code == null || code.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(errorBody("股票代码不能为空"));
        }
        try {
            Map<String, Object> data = analysisService.getRelativeStrength(code.trim());
            return ResponseEntity.ok(new ApiResponse<>(data));
        } catch (Exception e) {
            log.error("相对强弱分析失败: code={}, error={}", code, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(errorBody("相对强弱分析失败：" + e.getMessage()));
        }
    }

    /**
     * P2新增：个股长周期表现分析（YTD、超额收益、RS Rating、行业内排名）
     * GET /api/analysis/stock-performance
     */
    @GetMapping("/stock-performance")
    public ResponseEntity<?> getStockPerformance(@RequestParam String code) {
        if (analysisService == null) {
            return ResponseEntity.status(503).body(errorBody("分析服务不可用，ClickHouse未启用"));
        }
        if (code == null || code.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(errorBody("股票代码不能为空"));
        }
        try {
            Map<String, Object> data = analysisService.getStockPerformance(code.trim());
            return ResponseEntity.ok(new ApiResponse<>(data));
        } catch (Exception e) {
            log.error("个股长周期表现分析失败: code={}, error={}", code, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(errorBody("个股长周期表现分析失败：" + e.getMessage()));
        }
    }

    /**
     * 热门行业专题概览
     * GET /api/analysis/hot-sectors
     * 返回：各热门板块聚合数据（涨跌/资金/龙头/估值）
     */
    @GetMapping("/hot-sectors")
    public ResponseEntity<?> getHotSectors() {
        if (analysisService == null) {
            return ResponseEntity.status(503).body(errorBody("分析服务不可用，ClickHouse未启用"));
        }
        try {
            Map<String, Object> data = analysisService.getHotSectors();
            return ResponseEntity.ok(new ApiResponse<>(data));
        } catch (Exception e) {
            log.error("热门行业查询失败: error={}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(errorBody("热门行业查询失败：" + e.getMessage()));
        }
    }

    /**
     * 热门行业专题详情
     * GET /api/analysis/hot-sectors/{conceptName}
     * 返回：板块成分股 + 龙头 + 资金流向 + 近5日涨跌
     */
    @GetMapping("/hot-sectors/detail")
    public ResponseEntity<?> getHotSectorDetail(@RequestParam String conceptName) {
        if (analysisService == null) {
            return ResponseEntity.status(503).body(errorBody("分析服务不可用，ClickHouse未启用"));
        }
        try {
            Map<String, Object> data = analysisService.getHotSectorDetail(conceptName);
            return ResponseEntity.ok(new ApiResponse<>(data));
        } catch (Exception e) {
            log.error("热门行业详情查询失败: conceptName={}, error={}", conceptName, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(errorBody("热门行业详情查询失败：" + e.getMessage()));
        }
    }

    /**
     * 多空辩论论据
     * GET /api/analysis/bull-bear?code=000001
     */
    @GetMapping("/bull-bear")
    public ResponseEntity<?> getBullBear(@RequestParam String code) {
        if (workflowReportService == null) {
            return ResponseEntity.status(503).body(errorBody("报告服务不可用"));
        }
        if (code == null || code.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(errorBody("股票代码不能为空"));
        }
        try {
            com.quant.platform.stock.analysis.domain.WorkflowReport report = workflowReportService.generateReport(code.trim());
            Map<String, Object> data = new HashMap<>();
            data.put("bullArguments", report.getBullArguments());
            data.put("bearArguments", report.getBearArguments());
            data.put("conclusion", report.getConclusion());
            data.put("totalScore", report.getTotalScore());
            data.put("bias", report.getBias());
            return ResponseEntity.ok(new ApiResponse<>(data));
        } catch (Exception e) {
            log.error("多空辩论生成失败: code={}, error={}", code, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(errorBody("多空辩论生成失败：" + e.getMessage()));
        }
    }

    /**
     * HTML 报告导出
     * GET /api/analysis/html-report?code=000001&mode=preview
     * mode=preview: 浏览器预览；mode=download: 下载HTML文件
     */
    @GetMapping("/html-report")
    public ResponseEntity<?> getHtmlReport(@RequestParam String code,
                                           @RequestParam(defaultValue = "preview") String mode) {
        if (workflowReportService == null) {
            return ResponseEntity.status(503).body(errorBody("报告服务不可用"));
        }
        if (code == null || code.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(errorBody("股票代码不能为空"));
        }
        try {
            String html = workflowReportService.generateHtml(code.trim());
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.TEXT_HTML);
            if ("download".equals(mode)) {
                headers.set(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + code.trim() + "_report.html\"");
            }
            return ResponseEntity.ok().headers(headers).body(bytes);
        } catch (Exception e) {
            log.error("HTML报告生成失败: code={}, error={}", code, e.getMessage(), e);
            String errorHtml = "<html><body><h1>报告生成失败</h1><p>" + escapeHtml(e.getMessage()) + "</p></body></html>";
            return ResponseEntity.internalServerError()
                    .contentType(MediaType.TEXT_HTML)
                    .body(errorHtml.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * 股东结构分析（股东人数趋势 + 基金持仓明细 + 筹码集中度）
     * GET /api/analysis/shareholder-structure?code=600519
     */
    @GetMapping("/shareholder-structure")
    public ResponseEntity<?> getShareholderStructure(@RequestParam String code) {
        if (analysisService == null) {
            return ResponseEntity.status(503).body(errorBody("分析服务不可用，ClickHouse未启用"));
        }
        if (code == null || code.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(errorBody("股票代码不能为空"));
        }
        try {
            Map<String, Object> data = analysisService.getShareholderStructure(code.trim());
            return ResponseEntity.ok(new ApiResponse<>(data));
        } catch (Exception e) {
            log.error("股东结构查询失败: code={}, error={}", code, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(errorBody("股东结构查询失败：" + e.getMessage()));
        }
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private Map<String, Object> errorBody(String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", message);
        body.put("code", 500);
        return body;
    }

    /**
     * K线数据（近N交易日）
     * GET /api/analysis/kline?code=000001&days=60
     */
    @GetMapping("/kline")
    public ResponseEntity<?> getKLine(@RequestParam String code,
                                       @RequestParam(defaultValue = "60") int days) {
        if (code == null || code.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(errorBody("股票代码不能为空"));
        }
        try {
            List<Map<String, Object>> kline = analysisService.getKLine(code.trim(), days);
            return ResponseEntity.ok(new ApiResponse<>(kline));
        } catch (Exception e) {
            log.error("K线数据获取失败: code={}, error={}", code, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(errorBody("K线数据获取失败：" + e.getMessage()));
        }
    }

    /**
     * 手动触发新闻事件解析
     * POST /api/analysis/news-event/parse
     */
    @GetMapping("/news-event/parse")
    public ResponseEntity<?> parseNewsEvents() {
        if (newsEventParser == null) {
            return ResponseEntity.status(503).body(errorBody("新闻事件解析服务不可用"));
        }
        try {
            int count = newsEventParser.parseUnprocessedNews();
            Map<String, Object> data = new HashMap<>();
            data.put("parsedCount", count);
            data.put("message", "成功解析 " + count + " 条新闻");
            return ResponseEntity.ok(new ApiResponse<>(data));
        } catch (Exception e) {
            log.error("新闻事件解析失败: error={}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(errorBody("新闻事件解析失败：" + e.getMessage()));
        }
    }

    /**
     * 获取个股事件信号（超预期/不及预期）
     * GET /api/analysis/event-signal?code=000001
     */
    @GetMapping("/event-signal")
    public ResponseEntity<?> getEventSignal(@RequestParam String code) {
        if (eventSignalService == null) {
            return ResponseEntity.status(503).body(errorBody("事件信号服务不可用"));
        }
        try {
            EventSignalService.EventSignal signal = eventSignalService.getEventSignal(code.trim());
            return ResponseEntity.ok(new ApiResponse<>(signal));
        } catch (Exception e) {
            log.error("事件信号获取失败: code={}, error={}", code, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(errorBody("事件信号获取失败：" + e.getMessage()));
        }
    }
}
