package com.quant.platform.market.controller;

import com.quant.platform.common.dto.ApiResponse;
import com.quant.platform.market.domain.MarketDailyBar;
import com.quant.platform.market.service.MarketDataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 行情数据管理API
 */
@RestController
@RequestMapping("/market")
@RequiredArgsConstructor
@Tag(name = "行情数据", description = "股票行情数据查询接口")
public class MarketDataController {

    private final MarketDataService marketDataService;

    /**
     * 获取市场概览（轻量：统计摘要 + 涨跌 Top20）
     */
    @GetMapping("/overview")
    @Operation(summary = "获取市场概览")
    public ApiResponse<Map<String, Object>> getOverview() {
        return ApiResponse.success(marketDataService.getOverviewSummary());
    }

    /**
     * 分页获取截面行情数据
     */
    @GetMapping("/cross-section")
    @Operation(summary = "分页获取截面行情")
    public ApiResponse<Map<String, Object>> getCrossSection(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String sortField,
            @RequestParam(required = false) String sortOrder) {
        return ApiResponse.success(marketDataService.getCrossSectionPaged(date, page, size, keyword, sortField, sortOrder));
    }

    /**
     * 获取单只股票的日K数据
     */
    @GetMapping("/bars/{symbol}")
    @Operation(summary = "获取股票日K数据")
    public ApiResponse<List<MarketDailyBar>> getBarsBySymbol(
            @PathVariable String symbol,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.success(marketDataService.getBarsBySymbol(symbol, startDate, endDate));
    }

    /**
     * 获取交易日历
     */
    @GetMapping("/trading-dates")
    @Operation(summary = "获取交易日历")
    public ApiResponse<List<LocalDate>> getTradingDates(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.success(marketDataService.getTradingDates(startDate, endDate));
    }

    /**
     * 远程搜索股票（前端 Select 用）
     */
    @GetMapping("/search")
    @Operation(summary = "搜索股票代码或名称")
    public ApiResponse<List<Map<String, String>>> searchSymbols(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(marketDataService.searchSymbols(keyword, limit));
    }

    /**
     * 获取所有股票代码列表
     */
    @GetMapping("/symbols")
    @Operation(summary = "获取所有股票代码列表")
    public ApiResponse<List<String>> getAllSymbols() {
        return ApiResponse.success(marketDataService.getAllSymbols());
    }

    /**
     * 批量导入行情数据
     */
    @PostMapping("/import")
    @Operation(summary = "批量导入行情数据")
    public ApiResponse<Map<String, Object>> importBars(@RequestBody List<MarketDailyBar> bars) {
        int count = marketDataService.importBars(bars);
        return ApiResponse.success("导入成功", Map.of("count", count));
    }
}
