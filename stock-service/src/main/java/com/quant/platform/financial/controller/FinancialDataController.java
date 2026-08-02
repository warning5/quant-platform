package com.quant.platform.financial.controller;

import com.quant.platform.common.dto.ApiResponse;
import com.quant.platform.financial.entity.StockBalance;
import com.quant.platform.financial.entity.StockCashflow;
import com.quant.platform.financial.entity.StockFinancialIndicator;
import com.quant.platform.financial.entity.StockIncome;
import com.quant.platform.financial.service.FinancialDataService;
import com.quant.platform.financial.service.FinancialDataQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 财务数据控制器（瘦控制器）
 * 进度/校验等数据访问密集型逻辑已下沉至 FinancialDataQueryService；
 * 本类只负责参数接收、权限校验与响应包装。
 */
@RestController
@RequestMapping("/financial")
@RequiredArgsConstructor
@Tag(name = "财务数据", description = "上市公司财务数据查询接口")
@cn.dev33.satoken.annotation.SaCheckPermission("financial:view")
public class FinancialDataController {

    private final FinancialDataService financialDataService;
    private final FinancialDataQueryService financialDataQueryService;

    @GetMapping("/overview/{code}")
    @Operation(summary = "获取财务概览", description = "获取指定股票最新一期的财务指标概览")
    public ApiResponse<Map<String, Object>> getOverview(@PathVariable String code) {
        Map<String, Object> result = financialDataService.getFinancialOverview(code);
        if (result.isEmpty()) {
            return ApiResponse.error("未找到该股票的财务数据");
        }
        return ApiResponse.success(result);
    }

    @GetMapping("/income/{code}")
    @Operation(summary = "获取利润表", description = "获取指定股票的利润表历史数据")
    public ApiResponse<List<StockIncome>> getIncome(
            @PathVariable String code,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(financialDataService.getIncomeHistory(code, limit));
    }

    @GetMapping("/balance/{code}")
    @Operation(summary = "获取资产负债表", description = "获取指定股票的资产负债表历史数据")
    public ApiResponse<List<StockBalance>> getBalance(
            @PathVariable String code,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(financialDataService.getBalanceHistory(code, limit));
    }

    @GetMapping("/cashflow/{code}")
    @Operation(summary = "获取现金流量表", description = "获取指定股票的现金流量表历史数据")
    public ApiResponse<List<StockCashflow>> getCashflow(
            @PathVariable String code,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(financialDataService.getCashflowHistory(code, limit));
    }

    @GetMapping("/indicator/{code}")
    @Operation(summary = "获取财务指标", description = "获取指定股票的财务指标历史数据")
    public ApiResponse<List<StockFinancialIndicator>> getIndicator(
            @PathVariable String code,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(financialDataService.getIndicatorHistory(code, limit));
    }

    @GetMapping("/trend/{code}")
    @Operation(summary = "获取财务趋势", description = "获取指定股票的财务指标趋势数据（用于图表展示）")
    public ApiResponse<List<Map<String, Object>>> getTrend(@PathVariable String code) {
        return ApiResponse.success(financialDataService.getFinancialTrend(code));
    }

    @GetMapping("/stocks")
    @Operation(summary = "获取有财务数据的股票列表", description = "分页获取有最新年报财务指标的股票列表")
    public ApiResponse<List<Map<String, Object>>> getStockList(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<Map<String, Object>> result = financialDataService.getStocksWithFinancialData(keyword, page, size);
        return ApiResponse.success(result);
    }

    @GetMapping("/stocks/count")
    @Operation(summary = "获取有财务数据的股票数量")
    public ApiResponse<Long> getStockCount() {
        return ApiResponse.success(financialDataService.getFinancialStockCount());
    }

    @GetMapping("/picks/duan-yongping")
    @Operation(summary = "段永平派选股", description = "基于价值投资理念筛选：好公司+好价格+现金流充裕")
    public ApiResponse<Map<String, Object>> getDuanYongpingPicks(
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(financialDataService.getDuanYongpingPicks(Math.min(limit, 50)));
    }

    @GetMapping("/picks/hot-money")
    @Operation(summary = "游资/短线派选股", description = "基于高弹性+中小盘+资金关注筛选")
    public ApiResponse<Map<String, Object>> getHotMoneyPicks(
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(financialDataService.getHotMoneyPicks(Math.min(limit, 50)));
    }

    @GetMapping("/picks/quant")
    @Operation(summary = "量化派选股", description = "基于多因子综合评分，追求风险收益比")
    public ApiResponse<Map<String, Object>> getQuantPicks(
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(financialDataService.getQuantPicks(Math.min(limit, 50)));
    }

    @GetMapping("/progress")
    @Operation(summary = "财务数据更新进度")
    public ApiResponse<Map<String, Object>> getProgress() {
        return ApiResponse.success(financialDataQueryService.getProgress());
    }

    @GetMapping("/validate")
    @Operation(summary = "财务数据校验报告")
    public ApiResponse<Map<String, Object>> validate() {
        return ApiResponse.success(financialDataQueryService.validate());
    }
}
