package com.quant.platform.financial.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.quant.platform.common.dto.ApiResponse;
import com.quant.platform.financial.entity.StockBalance;
import com.quant.platform.financial.entity.StockCashflow;
import com.quant.platform.financial.entity.StockFinancialIndicator;
import com.quant.platform.financial.entity.StockIncome;
import com.quant.platform.financial.mapper.StockBalanceMapper;
import com.quant.platform.financial.mapper.StockCashflowMapper;
import com.quant.platform.financial.mapper.StockFinancialIndicatorMapper;
import com.quant.platform.financial.mapper.StockIncomeMapper;
import com.quant.platform.financial.service.FinancialDataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 财务数据控制器
 */
@RestController
@RequestMapping("/financial")
@RequiredArgsConstructor
@Tag(name = "财务数据", description = "上市公司财务数据查询接口")
public class FinancialDataController {

    private final FinancialDataService financialDataService;
    private final StockIncomeMapper incomeMapper;
    private final StockBalanceMapper balanceMapper;
    private final StockCashflowMapper cashflowMapper;
    private final StockFinancialIndicatorMapper indicatorMapper;

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

    @GetMapping("/progress")
    @Operation(summary = "财务数据更新进度")
    public Map<String, Object> getProgress() {
        Map<String, Object> result = new LinkedHashMap<>();

        // 各表记录数和去重股票数
        Map<String, Object> income = new LinkedHashMap<>();
        long incomeCount = incomeMapper.selectCount(null);
        long incomeStocks = countDistinct(incomeMapper);
        income.put("count", incomeCount);
        income.put("stocks", incomeStocks);
        result.put("income", income);

        Map<String, Object> balance = new LinkedHashMap<>();
        long balanceCount = balanceMapper.selectCount(null);
        long balanceStocks = countDistinctBalance(balanceMapper);
        balance.put("count", balanceCount);
        balance.put("stocks", balanceStocks);
        result.put("balance", balance);

        Map<String, Object> cashflow = new LinkedHashMap<>();
        long cashflowCount = cashflowMapper.selectCount(null);
        long cashflowStocks = countDistinctCashflow(cashflowMapper);
        cashflow.put("count", cashflowCount);
        cashflow.put("stocks", cashflowStocks);
        result.put("cashflow", cashflow);

        Map<String, Object> indicator = new LinkedHashMap<>();
        long indicatorCount = indicatorMapper.selectCount(null);
        long indicatorStocks = countDistinctIndicator(indicatorMapper);
        indicator.put("count", indicatorCount);
        indicator.put("stocks", indicatorStocks);
        result.put("indicator", indicator);

        // 覆盖的不同股票总数
        Set<String> allCodes = new HashSet<>();
        allCodes.addAll(getDistinctCodes(incomeMapper));
        allCodes.addAll(getDistinctCodesBalance(balanceMapper));
        allCodes.addAll(getDistinctCodesCashflow(cashflowMapper));
        allCodes.addAll(getDistinctCodesIndicator(indicatorMapper));
        result.put("uniqueStocks", allCodes.size());

        // 读取日志文件
        List<String> logLines = new ArrayList<>();
        try {
            Path logPath = Path.of("c:/Users/warning5/WorkBuddy/Claw/update_data/_financial_update.log");
            if (Files.exists(logPath)) {
                List<String> allLines = Files.readAllLines(logPath);
                // 取最后 50 行非空行
                for (int i = Math.max(0, allLines.size() - 50); i < allLines.size(); i++) {
                    String line = allLines.get(i).trim();
                    if (!line.isEmpty()) logLines.add(line);
                }
            }
        } catch (IOException e) {
            logLines.add("日志文件读取失败: " + e.getMessage());
        }
        result.put("log", logLines);

        return result;
    }

    private long countDistinct(StockIncomeMapper mapper) {
        QueryWrapper<StockIncome> wrapper = new QueryWrapper<>();
        wrapper.select("COUNT(DISTINCT code) as cnt");
        Map<String, Object> map = mapper.selectMaps(wrapper).getFirst();
        return ((Number) map.get("cnt")).longValue();
    }

    private long countDistinctBalance(StockBalanceMapper mapper) {
        QueryWrapper<StockBalance> wrapper = new QueryWrapper<>();
        wrapper.select("COUNT(DISTINCT code) as cnt");
        Map<String, Object> map = mapper.selectMaps(wrapper).getFirst();
        return ((Number) map.get("cnt")).longValue();
    }

    private long countDistinctCashflow(StockCashflowMapper mapper) {
        QueryWrapper<StockCashflow> wrapper = new QueryWrapper<>();
        wrapper.select("COUNT(DISTINCT code) as cnt");
        Map<String, Object> map = mapper.selectMaps(wrapper).getFirst();
        return ((Number) map.get("cnt")).longValue();
    }

    private long countDistinctIndicator(StockFinancialIndicatorMapper mapper) {
        QueryWrapper<StockFinancialIndicator> wrapper = new QueryWrapper<>();
        wrapper.select("COUNT(DISTINCT code) as cnt");
        Map<String, Object> map = mapper.selectMaps(wrapper).getFirst();
        return ((Number) map.get("cnt")).longValue();
    }

    private Set<String> getDistinctCodes(StockIncomeMapper mapper) {
        QueryWrapper<StockIncome> wrapper = new QueryWrapper<>();
        wrapper.select("DISTINCT code");
        Set<String> codes = new HashSet<>();
        for (Map<String, Object> m : mapper.selectMaps(wrapper)) {
            if (m.get("code") != null) codes.add(m.get("code").toString());
        }
        return codes;
    }

    private Set<String> getDistinctCodesBalance(StockBalanceMapper mapper) {
        QueryWrapper<StockBalance> wrapper = new QueryWrapper<>();
        wrapper.select("DISTINCT code");
        Set<String> codes = new HashSet<>();
        for (Map<String, Object> m : mapper.selectMaps(wrapper)) {
            if (m.get("code") != null) codes.add(m.get("code").toString());
        }
        return codes;
    }

    private Set<String> getDistinctCodesCashflow(StockCashflowMapper mapper) {
        QueryWrapper<StockCashflow> wrapper = new QueryWrapper<>();
        wrapper.select("DISTINCT code");
        Set<String> codes = new HashSet<>();
        for (Map<String, Object> m : mapper.selectMaps(wrapper)) {
            if (m.get("code") != null) codes.add(m.get("code").toString());
        }
        return codes;
    }

    private Set<String> getDistinctCodesIndicator(StockFinancialIndicatorMapper mapper) {
        QueryWrapper<StockFinancialIndicator> wrapper = new QueryWrapper<>();
        wrapper.select("DISTINCT code");
        Set<String> codes = new HashSet<>();
        for (Map<String, Object> m : mapper.selectMaps(wrapper)) {
            if (m.get("code") != null) codes.add(m.get("code").toString());
        }
        return codes;
    }
}
