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
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final JdbcTemplate jdbcTemplate;

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
    public ApiResponse<Map<String, Object>> getProgress() {
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

        return ApiResponse.success(result);
    }

    @GetMapping("/validate")
    @Operation(summary = "财务数据校验报告")
    public ApiResponse<Map<String, Object>> validate() {
        Map<String, Object> result = new LinkedHashMap<>();

        // 1. 表级记录统计
        Map<String, Map<String, Object>> tableStats = new LinkedHashMap<>();
        for (String[] table : new String[][]{
                {"stock_financial_indicator", "财务指标表"},
                {"stock_income", "利润表"},
                {"stock_balance", "资产负债表"},
                {"stock_cashflow", "现金流量表"}
        }) {
            Map<String, Object> stats = jdbcTemplate.queryForMap(
                    "SELECT COUNT(*) as cnt, COUNT(DISTINCT code) as stock_cnt FROM " + table[0]);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("label", table[1]);
            item.put("records", ((Number) stats.get("cnt")).longValue());
            item.put("stocks", ((Number) stats.get("stock_cnt")).longValue());
            tableStats.put(table[0], item);
        }
        result.put("tableStats", tableStats);

        // 2. 年份覆盖（report_date 格式 YYYYMMDD，取前4位作为年份）
        List<Map<String, Object>> yearCoverage = jdbcTemplate.queryForList("""
                SELECT LEFT(report_date, 4) AS report_year,
                       COUNT(*) AS record_cnt,
                       COUNT(DISTINCT code) AS stock_cnt
                FROM stock_financial_indicator
                GROUP BY LEFT(report_date, 4)
                ORDER BY LEFT(report_date, 4) DESC
                LIMIT 20
                """);
        result.put("yearCoverage", yearCoverage);

        // 3. 关键字段空值率（按实际所在表分别查询）
        Map<String, String> fieldTableMap = new LinkedHashMap<>();
        fieldTableMap.put("revenue", "stock_income");
        fieldTableMap.put("net_profit", "stock_income");
        fieldTableMap.put("total_assets", "stock_balance");
        fieldTableMap.put("total_liabilities", "stock_balance");
        fieldTableMap.put("gross_profit_margin", "stock_financial_indicator");
        fieldTableMap.put("net_profit_margin", "stock_financial_indicator");

        List<Map<String, Object>> fieldNullRates = new ArrayList<>();
        for (Map.Entry<String, String> entry : fieldTableMap.entrySet()) {
            String field = entry.getKey();
            String table = entry.getValue();
            try {
                Long nonNull = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM " + table + " WHERE " + field + " IS NOT NULL AND " + field + " != 0",
                        Long.class);
                if (nonNull == null) nonNull = 0L;
                Long totalRecords = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM " + table, Long.class);
                if (totalRecords == null || totalRecords == 0) totalRecords = 1L;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("field", field);
                item.put("nonNull", nonNull);
                item.put("total", totalRecords);
                item.put("rate", Math.round(nonNull * 100.0 / totalRecords * 10) / 10.0);
                fieldNullRates.add(item);
            } catch (Exception e) {
                // 字段不存在则跳过
            }
        }
        result.put("fieldNullRates", fieldNullRates);

        // 4. 净利润同比异常（net_profit 在 stock_income 表，report_date 格式 YYYYMMDD）
        List<Map<String, Object>> anomalies = jdbcTemplate.queryForList("""
                SELECT a.code,
                       si.name,
                       LEFT(a.report_date, 4) AS report_year,
                       a.report_type,
                       a.net_profit AS cur_profit,
                       b.net_profit AS prev_profit
                FROM stock_income a
                LEFT JOIN stock_info si ON a.code = si.code
                LEFT JOIN stock_income b
                  ON a.code = b.code
                 AND b.report_date LIKE CONCAT(CAST(LEFT(a.report_date, 4) AS UNSIGNED) - 1, '%')
                 AND b.report_type = a.report_type
                WHERE CAST(LEFT(a.report_date, 4) AS UNSIGNED) >= YEAR(CURDATE()) - 3
                  AND a.report_type IN (1, 2, 4)
                  AND a.net_profit IS NOT NULL AND b.net_profit IS NOT NULL
                  AND (a.net_profit > 0 AND b.net_profit < 0
                       OR a.net_profit < 0 AND b.net_profit > 0
                       OR ABS(a.net_profit / NULLIF(b.net_profit, 0)) > 10)
                ORDER BY LEFT(a.report_date, 4) DESC
                LIMIT 15
                """);
        result.put("anomalies", anomalies);

        // 5. 缺失近3年财报的股票（stock_info 无 list_status 列，查全部）
        List<Map<String, Object>> missingStocks = jdbcTemplate.queryForList("""
                SELECT si.code, si.name, si.market,
                       COUNT(sfi.report_date) as record_cnt
                FROM stock_info si
                LEFT JOIN stock_financial_indicator sfi
                  ON si.code = sfi.code
                 AND CAST(LEFT(sfi.report_date, 4) AS UNSIGNED) >= YEAR(CURDATE()) - 2
                  AND sfi.report_type IN (1, 2, 4)
                GROUP BY si.code, si.name, si.market
                HAVING record_cnt = 0 LIMIT 20
                """);
        result.put("missingStocks", missingStocks);

        Long totalStocks = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_info", Long.class);
        result.put("totalStocks", totalStocks != null ? totalStocks : 0);

        return ApiResponse.success(result);
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
