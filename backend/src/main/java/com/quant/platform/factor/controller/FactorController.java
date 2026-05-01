package com.quant.platform.factor.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.quant.platform.common.dto.ApiResponse;
import com.quant.platform.factor.domain.FactorDefinition;
import com.quant.platform.factor.domain.FactorTestReport;
import com.quant.platform.factor.domain.FactorValue;
import com.quant.platform.factor.engine.FactorComputeEngine;
import com.quant.platform.factor.service.FactorCorrelationService;
import com.quant.platform.factor.service.FactorService;
import com.quant.platform.factor.service.FactorWeightOptimizeService;
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

/**
 * 因子管理API
 */
@RestController
@RequestMapping("/factors")
@RequiredArgsConstructor
@Tag(name = "因子管理", description = "因子定义、计算、测试管理接口")
public class FactorController {

    private final FactorService factorService;
    private final FactorCorrelationService correlationService;
    private final FactorWeightOptimizeService factorWeightOptimizeService;
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
    @Operation(summary = "批量并行计算多个因子（每个因子在独立线程中执行，最多8个）")
    public ApiResponse<Map<String, Object>> batchCompute(
            @RequestParam String factorCodes,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "true") boolean incremental,
            @RequestParam(defaultValue = "false") boolean force) {
        List<String> codeList = Arrays.asList(factorCodes.split(","));
        if (codeList.size() > 8) {
            return ApiResponse.error("最多同时计算 8 个因子，当前提交了 " + codeList.size() + " 个");
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
        return ApiResponse.success(factorService.getFactorSymbols(factorCode, keyword));
    }

    @GetMapping("/{factorCode}/values/series")
    @Operation(summary = "获取因子时间序列值")
    public ApiResponse<List<FactorValue>> getTimeSeries(
            @PathVariable String factorCode,
            @RequestParam String symbol,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.success(factorService.getFactorTimeSeries(factorCode, symbol, startDate, endDate));
    }

    @GetMapping("/{factorCode}/values/cross-section")
    @Operation(summary = "获取因子截面数据（带股票名称，按因子值降序）")
    public ApiResponse<Map<String, Object>> getCrossSection(
            @PathVariable String factorCode,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "100") int size) {
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

    @GetMapping("/chan-screen/meta")
    @Operation(summary = "缠论筛选元数据：动态获取所有缠论因子定义（代码/名称/筛选控件类型）")
    public ApiResponse<Map<String, Object>> chanScreenMeta() {
        return ApiResponse.success(factorService.getChanScreenMeta());
    }

    // ── 缠论因子筛选（P1）────────────────────────────────────────────
    /**
     * 缠论因子筛选
     * GET /factors/chan-screen?penDir=1,-1&trend=1,0,-1&buySell=1,2,-1&hubPosMin=0&hubPosMax=1&penCountMin=1&penCountMax=20&keyword=&page=0&size=20
     */
    @GetMapping("/chan-screen")
    @Operation(summary = "缠论因子筛选（P1）：按笔方向/走势类型/买卖点/中枢位置/笔数量筛选")
    public ApiResponse<Map<String, Object>> chanScreen(
            @RequestParam(required = false) List<Integer> penDir,
            @RequestParam(required = false) List<Integer> trend,
            @RequestParam(required = false) List<Integer> buySell,
            @RequestParam(required = false) BigDecimal hubPosMin,
            @RequestParam(required = false) BigDecimal hubPosMax,
            @RequestParam(required = false) BigDecimal penCountMin,
            @RequestParam(required = false) BigDecimal penCountMax,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(
                factorService.chanScreen(
                        penDir, trend, buySell,
                        hubPosMin, hubPosMax, penCountMin, penCountMax,
                        keyword, page, size));
    }
}
