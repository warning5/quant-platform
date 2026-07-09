package com.quant.platform.factor.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.quant.platform.common.dto.ApiResponse;
import com.quant.platform.common.exception.ValidationException;
import com.quant.platform.factor.domain.FactorDefinition;
import com.quant.platform.factor.domain.FactorTestReport;
import com.quant.platform.factor.domain.FactorValue;
import com.quant.platform.factor.engine.FactorComputeEngine;
import com.quant.platform.factor.service.FactorAnalysisService;
import com.quant.platform.factor.service.FactorCorrelationService;
import com.quant.platform.factor.service.FactorService;
import com.quant.platform.factor.service.FactorWeightOptimizeService;
import com.quant.platform.factor.service.QuarterlyFactorAnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 因子管理API
 */
@RestController
@RequestMapping("/factors")
@RequiredArgsConstructor
@Tag(name = "因子管理", description = "因子定义、计算、测试管理接口")
public class FactorController {

    /**
     * factorCode 白名单正则（防御 SQL 注入）
     * 只允许字母、数字、下划线、横线
     */
    private static final java.util.regex.Pattern FACTOR_CODE_PATTERN =
            java.util.regex.Pattern.compile("[a-zA-Z0-9_\\-]+");

    /**
     * 校验 factorCode（防御 SQL 注入）
     * @param factorCode 因子代码
     * @throws ValidationException 如果 factorCode 包含非法字符
     */
    private void validateFactorCode(String factorCode) {
        if (factorCode == null || !FACTOR_CODE_PATTERN.matcher(factorCode).matches()) {
            throw new ValidationException("Invalid factorCode: " + factorCode);
        }
    }

    private final FactorService factorService;
    private final FactorCorrelationService correlationService;
    private final FactorWeightOptimizeService factorWeightOptimizeService;
    private final FactorAnalysisService factorAnalysisService;
    private final QuarterlyFactorAnalysisService quarterlyFactorAnalysisService;
    private final SimpMessagingTemplate messagingTemplate;
    private final FactorComputeEngine computeEngine;

    @GetMapping
    @Operation(summary = "查询因子列表（分页）")
    public ApiResponse<IPage<FactorDefinition>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(factorService.searchFactors(keyword, category, status, page, size));
    }

    @GetMapping("/status-batch")
    @Operation(summary = "批量查询因子计算状态（因子值数量+检测报告数量）")
    public ApiResponse<Map<String, Map<String, Object>>> batchStatus(
            @RequestParam String factorCodes) {
        List<String> codeList = Arrays.asList(factorCodes.split(","));
        return ApiResponse.success(factorService.batchGetFactorStatus(codeList));
    }

    @DeleteMapping("/{id:\\d+}/values")
    @Operation(summary = "删除指定因子的所有因子值")
    public ApiResponse<Map<String, Object>> deleteValues(@PathVariable Long id) {
        int deleted = factorService.deleteFactorValues(id);
        return ApiResponse.success("已删除 " + deleted + " 条因子值", Map.of("deleted", deleted));
    }

    @GetMapping("/{id:\\d+}")
    @Operation(summary = "获取因子详情")
    public ApiResponse<FactorDefinition> getById(@PathVariable Long id) {
        return ApiResponse.success(factorService.getById(id));
    }

    @GetMapping("/{id:\\d+}/init")
    @Operation(summary = "因子详情页聚合初始化接口（详情+报告列表+值数量，一次 RTT）")
    public ApiResponse<Map<String, Object>> getFactorInit(@PathVariable Long id) {
        return ApiResponse.success(factorService.getFactorInit(id));
    }

    @PostMapping
    @Operation(summary = "创建自定义因子")
    public ApiResponse<FactorDefinition> create(@Valid @RequestBody FactorDefinition factor) {
        return ApiResponse.success("因子创建成功", factorService.createFactor(factor));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新因子")
    public ApiResponse<FactorDefinition> update(@PathVariable Long id,
                                                @RequestBody FactorDefinition factor) {
        return ApiResponse.success("因子更新成功", factorService.updateFactor(id, factor));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除因子")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        factorService.deleteFactor(id);
        return ApiResponse.ok();
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "更改因子状态")
    public ApiResponse<FactorDefinition> changeStatus(@PathVariable Long id,
                                                      @RequestParam String status) {
        return ApiResponse.success(factorService.changeStatus(id, FactorDefinition.FactorStatus.valueOf(status)));
    }

    @PostMapping("/{id}/compute")
    @Operation(summary = "触发因子计算，返回 factorCode 供前端订阅进度")
    public ApiResponse<Map<String, Object>> compute(
            @PathVariable Long id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        String factorCode = factorService.triggerCompute(id, startDate, endDate);
        return ApiResponse.success("因子计算任务已提交", Map.of("factorCode", factorCode));
    }

    @PostMapping("/batch-compute")
    @Operation(summary = "批量并行计算多个因子（日期范围≤7天时无因子数量限制，否则最多8个）")
    public ApiResponse<Map<String, Object>> batchCompute(
            @RequestParam String factorCodes,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "true") boolean incremental,
            @RequestParam(defaultValue = "false") boolean force) {
        List<String> codeList = Arrays.asList(factorCodes.split(","));
        long daysBetween = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) + 1;
        if (daysBetween > 7 && codeList.size() > 8) {
            return ApiResponse.error("日期范围超过7天时最多同时计算 8 个因子（当前 " + codeList.size() + " 个，范围 " + daysBetween + " 天）");
        }
        Map<String, Object> result = factorService.triggerBatchCompute(codeList, startDate, endDate, incremental, force);
        return ApiResponse.success("批量计算任务已提交", result);
    }

    @GetMapping("/{id}/value-count")
    @Operation(summary = "查询因子已计算的数据量（用于判断是否需要先触发计算）")
    public ApiResponse<Map<String, Object>> getValueCount(@PathVariable Long id) {
        return ApiResponse.success(factorService.getFactorValueCount(id));
    }

    @PostMapping("/{id}/test")
    @Operation(summary = "触发因子测试（IC分析+分层回测）")
    public ApiResponse<FactorTestReport> test(
            @PathVariable Long id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "因子测试") String testName,
            @RequestParam(defaultValue = "ALL_A") String stockPool,
            @RequestParam(defaultValue = "DAILY") String rebalanceFreq) {
        return ApiResponse.success("测试任务已提交",
                factorService.triggerTest(id, startDate, endDate, testName, stockPool, rebalanceFreq));
    }

    @GetMapping("/{factorCode}/tests")
    @Operation(summary = "查询因子测试报告列表")
    public ApiResponse<List<FactorTestReport>> getTests(@PathVariable String factorCode) {
        validateFactorCode(factorCode);  // SQL注入防护
        return ApiResponse.success(factorService.getTestReports(factorCode));
    }

    @GetMapping("/tests/{reportId}")
    @Operation(summary = "获取测试报告详情")
    public ApiResponse<FactorTestReport> getTestReport(@PathVariable Long reportId) {
        return ApiResponse.success(factorService.getTestReport(reportId));
    }

    @DeleteMapping("/tests/{reportId}")
    @Operation(summary = "删除测试报告")
    public ApiResponse<Void> deleteTestReport(@PathVariable Long reportId) {
        factorService.deleteTestReport(reportId);
        return ApiResponse.ok();
    }

    @GetMapping("/{factorCode}/values/symbols")
    @Operation(summary = "查询该因子有数据的股票列表（带名称，支持关键词搜索）")
    public ApiResponse<List<Map<String, String>>> getFactorSymbols(
            @PathVariable String factorCode,
            @RequestParam(required = false, defaultValue = "") String keyword) {
        validateFactorCode(factorCode);  // SQL注入防护
        return ApiResponse.success(factorService.getFactorSymbols(factorCode, keyword));
    }

    @GetMapping("/{factorCode}/values/series")
    @Operation(summary = "获取因子时间序列值")
    public ApiResponse<List<FactorValue>> getTimeSeries(
            @PathVariable String factorCode,
            @RequestParam String symbol,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        validateFactorCode(factorCode);  // SQL注入防护
        return ApiResponse.success(factorService.getFactorTimeSeries(factorCode, symbol, startDate, endDate));
    }

    @GetMapping("/{factorCode}/values/cross-section")
    @Operation(summary = "获取因子截面数据（带股票名称，按因子值降序）")
    public ApiResponse<Map<String, Object>> getCrossSection(
            @PathVariable String factorCode,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "100") int size) {
        validateFactorCode(factorCode);  // SQL注入防护
        return ApiResponse.success(factorService.getFactorCrossSection(factorCode, date, page, size));
    }

    @GetMapping("/script/template")
    @Operation(summary = "获取脚本模板")
    public ApiResponse<String> getTemplate(@RequestParam(defaultValue = "default") String type) {
        return ApiResponse.success(factorService.getScriptTemplate(type));
    }

    @PostMapping("/script/validate")
    @Operation(summary = "验证脚本语法")
    public ApiResponse<Map<String, Object>> validateScript(@RequestBody Map<String, String> body) {
        return ApiResponse.success(factorService.validateScript(body.get("scriptCode")));
    }

    @GetMapping("/correlation")
    @Operation(summary = "计算因子相关性矩阵")
    public ApiResponse<List<Map<String, Object>>> computeCorrelation(
            @RequestParam(required = false) List<String> factorCodes,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        if (factorCodes == null || factorCodes.isEmpty()) {
            throw new IllegalArgumentException("factorCodes参数不能为空");
        }
        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("startDate和endDate参数不能为空");
        }
        return ApiResponse.success(correlationService.computeCorrelationMatrix(factorCodes, startDate, endDate));
    }

    @GetMapping("/monitor")
    @Operation(summary = "因子计算监控数据（各因子统计+全局总数）")
    public ApiResponse<Map<String, Object>> monitor(
            @RequestParam(defaultValue = "false") boolean force) {
        if (force) {
            factorService.clearMonitorCache();
        }
        return ApiResponse.success(factorService.getMonitorData());
    }

    @GetMapping("/running")
    @Operation(summary = "当前正在计算的因子代码列表")
    public ApiResponse<Set<String>> running() {
        return ApiResponse.success(computeEngine.getRunningFactorCodes());
    }

    @GetMapping("/missing-by-date")
    @Operation(summary = "查询指定日期缺少因子值的因子列表")
    public ApiResponse<List<Map<String, Object>>> missingByDate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.success(factorService.findMissingFactorsByDate(date));
    }

    @GetMapping("/ws-test")
    @Operation(summary = "WebSocket 广播测试（调试用）")
    public ApiResponse<String> wsTest() {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "FACTOR_PROGRESS");
        msg.put("factorCode", "TEST");
        msg.put("stage", "COMPUTING");
        msg.put("progress", 50);
        msg.put("message", "WebSocket 广播测试消息 - " + LocalDateTime.now());
        msg.put("timestamp", LocalDateTime.now().toString());
        messagingTemplate.convertAndSend("/topic/factor/batch-log", msg);
        return ApiResponse.success("已发送测试消息");
    }

    // ─── P1：因子组合权重优化 ─────────────────────────────────────────────────

    /**
     * 因子组合权重优化
     * POST /factors/weight-optimize?factorCodes=MOM20,VOL20,SIZE&startDate=2024-01-01&endDate=2025-01-01&method=MARKOWITZ
     */
    @PostMapping("/weight-optimize")
    @Operation(summary = "因子组合权重优化（P1）：EQUAL / MARKOWITZ / RISK_PARITY")
    public ApiResponse<Map<String, Object>> weightOptimize(
            @RequestParam String factorCodes,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "MARKOWITZ") String method) {
        List<String> codes = Arrays.asList(factorCodes.split(","));
        return ApiResponse.success(
                factorWeightOptimizeService.optimize(codes, startDate, endDate, method));
    }

    // ─── P1：因子 IC/IR 批量分析 ─────────────────────────────────────────────

    /**
     * 批量计算因子 IC/IR
     * POST /factors/ic-ir-analysis?factorCodes=MOM20,VOL20,SIZE&startDate=2024-01-01&endDate=2025-01-01&forwardDays=5
     */
    @PostMapping("/ic-ir-analysis")
    @Operation(summary = "批量计算因子IC/IR：支持Spearman秩相关 / Pearson线性相关")
    public ApiResponse<List<Map<String, Object>>> batchIcIrAnalysis(
            @RequestParam String factorCodes,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "5") int forwardDays,
            @RequestParam(defaultValue = "false") boolean neutralizeByIndustry,
            @RequestParam(defaultValue = "false") boolean neutralizeByMarketCap,
            @RequestParam(defaultValue = "spearman") String correlationType,
            @RequestParam(defaultValue = "0.03") double icThreshold) {
        if (forwardDays < 1 || forwardDays > 60) {
            throw new IllegalArgumentException("forwardDays须在1~60之间");
        }
        List<String> codes = Arrays.asList(factorCodes.split(","));
        if (codes.size() > 50) {
            throw new IllegalArgumentException("单次最多分析50个因子");
        }
        return ApiResponse.success(
                factorAnalysisService.batchCalcIcIr(codes, startDate.toString(), endDate.toString(), forwardDays, neutralizeByIndustry, neutralizeByMarketCap, correlationType, icThreshold));
    }

    /**
     * 分段 IC/IR 对比分析
     * POST /factors/ic-ir-analysis/segmented?factorCodes=MOM20,...&startDate=...&endDate=...&splitDate=...&forwardDays=5
     */
    @PostMapping("/ic-ir-analysis/segmented")
    @Operation(summary = "分段IC/IR对比：按splitDate拆分前后两段+全量，对比因子时效性")
    public ApiResponse<Map<String, Object>> segmentedIcIrAnalysis(
            @RequestParam String factorCodes,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate splitDate,
            @RequestParam(defaultValue = "5") int forwardDays,
            @RequestParam(defaultValue = "false") boolean neutralizeByIndustry,
            @RequestParam(defaultValue = "false") boolean neutralizeByMarketCap,
            @RequestParam(defaultValue = "spearman") String correlationType) {
        if (forwardDays < 1 || forwardDays > 60) {
            throw new IllegalArgumentException("forwardDays须在1~60之间");
        }
        if (!splitDate.isAfter(startDate) || !splitDate.isBefore(endDate)) {
            throw new IllegalArgumentException("splitDate必须在startDate和endDate之间");
        }
        List<String> codes = Arrays.asList(factorCodes.split(","));
        if (codes.size() > 50) {
            throw new IllegalArgumentException("单次最多分析50个因子");
        }
        return ApiResponse.success(
                factorAnalysisService.batchCalcIcIrSegmented(
                        codes, startDate.toString(), endDate.toString(), splitDate.toString(), forwardDays, neutralizeByIndustry, neutralizeByMarketCap, correlationType));
    }

    /**
     * 单因子 IC 趋势（含时间线）
     * GET /factors/{factorCode}/ic-trend?startDate=2024-01-01&endDate=2025-01-01&forwardDays=5
     */
    @GetMapping("/{factorCode}/ic-trend")
    @Operation(summary = "单因子IC趋势（P1）：IC时间线 + 有效性评估")
    public ApiResponse<Map<String, Object>> icTrend(
            @PathVariable String factorCode,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "5") int forwardDays) {
        validateFactorCode(factorCode);  // SQL注入防护
        if (forwardDays < 1 || forwardDays > 60) {
            throw new IllegalArgumentException("forwardDays须在1~60之间");
        }
        return ApiResponse.success(
                factorAnalysisService.getFactorIcTrend(factorCode, startDate.toString(), endDate.toString(), forwardDays));
    }

    // ================================================================
    //  P3: 因子拥挤度检测
    // ================================================================

    @PostMapping("/crowding-detection")
    @Operation(summary = "P3: 因子拥挤度检测 — 相关性聚类+去重建议")
    public ApiResponse<Map<String, Object>> crowdingDetection(
            @RequestParam String factorCodes,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "0.7") double corrThreshold) {

        List<String> codes = Arrays.asList(factorCodes.split(","));
        if (codes.size() < 2) {
            throw new IllegalArgumentException("至少需要2个因子进行拥挤度分析");
        }

        // 获取IC快照用于选代表
        Map<String, FactorAnalysisService.FactorIcSnapshot> icSnapshots =
                factorAnalysisService.quickFactorIcSnapshot(codes, endDate, 60, 0.02, 20);

        Map<String, Object> report = correlationService.getCrowdingReport(
                codes, startDate, endDate, corrThreshold, icSnapshots);

        return ApiResponse.success(report);
    }

    @PostMapping("/crowding-dedup")
    @Operation(summary = "P3: 因子拥挤度去重 — 返回去重后的因子列表")
    public ApiResponse<Map<String, Object>> crowdingDedup(
            @RequestParam String factorCodes,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "0.7") double corrThreshold) {

        List<String> codes = Arrays.asList(factorCodes.split(","));
        if (codes.size() < 2) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("originalCount", codes.size());
            result.put("dedupedCodes", codes);
            result.put("removedCount", 0);
            result.put("clusters", Collections.emptyList());
            return ApiResponse.success(result);
        }

        Map<String, FactorAnalysisService.FactorIcSnapshot> icSnapshots =
                factorAnalysisService.quickFactorIcSnapshot(codes, endDate, 60, 0.02, 20);

        Map<String, Object> report = correlationService.getCrowdingReport(
                codes, startDate, endDate, corrThreshold, icSnapshots);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("originalCount", codes.size());
        result.put("dedupedCodes", report.get("dedupedFactorCodes"));
        result.put("removedCount", report.get("redundantCount"));
        result.put("clusters", report.get("clusters"));

        return ApiResponse.success(result);
    }

    // ================================================================
    //  P4: 财务因子季频评估
    // ================================================================

    @PostMapping("/quarterly-ic")
    @Operation(summary = "P4: 财务因子季频IC分析 — 只在季末日期评估，避免日频虚高")
    public ApiResponse<List<QuarterlyFactorAnalysisService.QuarterlyIcResult>> quarterlyIc(
            @RequestParam String factorCodes,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "20") int forwardDays,
            @RequestParam(defaultValue = "false") boolean useAnnouncementDrift) {

        List<String> codes = Arrays.asList(factorCodes.split(","));

        List<QuarterlyFactorAnalysisService.QuarterlyIcResult> results =
                quarterlyFactorAnalysisService.batchComputeQuarterlyIc(
                        codes, startDate, endDate, forwardDays, useAnnouncementDrift);

        return ApiResponse.success(results);
    }

    @GetMapping("/quarterly-dates")
    @Operation(summary = "P4: 获取指定范围内的季末日期列表")
    public ApiResponse<List<String>> quarterlyDates(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        List<LocalDate> dates = quarterlyFactorAnalysisService.getQuarterEndDates(startDate, endDate);
        List<String> result = dates.stream().map(LocalDate::toString).collect(Collectors.toList());
        return ApiResponse.success(result);
    }
}
