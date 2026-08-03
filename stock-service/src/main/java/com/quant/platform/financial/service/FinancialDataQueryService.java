package com.quant.platform.financial.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.quant.platform.financial.entity.StockBalance;
import com.quant.platform.financial.entity.StockCashflow;
import com.quant.platform.financial.entity.StockFinancialIndicator;
import com.quant.platform.financial.entity.StockIncome;
import com.quant.platform.financial.mapper.StockBalanceMapper;
import com.quant.platform.financial.mapper.StockCashflowMapper;
import com.quant.platform.financial.mapper.StockFinancialIndicatorMapper;
import com.quant.platform.financial.mapper.StockIncomeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 财务数据查询/统计业务逻辑层
 * 承接原 FinancialDataController 中直接内联的 JdbcTemplate 访问、4 个财务 Mapper 调用、
 * 去重计数私有方法（countDistinctFiltered / getDistinctCodes ×4）与进度/校验报表组装。
 * 简单读数类接口仍由 FinancialDataService 提供。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FinancialDataQueryService {

    private final StockIncomeMapper incomeMapper;
    private final StockBalanceMapper balanceMapper;
    private final StockCashflowMapper cashflowMapper;
    private final StockFinancialIndicatorMapper indicatorMapper;
    private final JdbcTemplate jdbcTemplate;

    // 允许拼入 SQL 的表名与字段名白名单（仅内部常量使用）
    private static final Set<String> ALLOWED_TABLES = new HashSet<>(Arrays.asList(
            "stock_financial_indicator", "stock_income", "stock_balance", "stock_cashflow"
    ));
    private static final Set<String> ALLOWED_FIELDS = new HashSet<>(Arrays.asList(
            "revenue", "net_profit", "total_assets", "total_liabilities",
            "gross_profit_margin", "net_profit_margin"
    ));
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[a-z_][a-z0-9_]*$");

    /**
     * 财务数据更新进度
     */
    public Map<String, Object> getProgress() {
        Map<String, Object> result = new LinkedHashMap<>();

        List<String> validCodeList = jdbcTemplate.queryForList(
                "SELECT DISTINCT code FROM stock_info", String.class);
        Set<String> validCodes = new HashSet<>(validCodeList);

        Map<String, Object> income = new LinkedHashMap<>();
        long incomeCount = incomeMapper.selectCount(null);
        long incomeStocks = countDistinctFiltered(incomeMapper, validCodes);
        income.put("count", incomeCount);
        income.put("stocks", incomeStocks);
        result.put("income", income);

        Map<String, Object> balance = new LinkedHashMap<>();
        long balanceCount = balanceMapper.selectCount(null);
        long balanceStocks = countDistinctBalanceFiltered(balanceMapper, validCodes);
        balance.put("count", balanceCount);
        balance.put("stocks", balanceStocks);
        result.put("balance", balance);

        Map<String, Object> cashflow = new LinkedHashMap<>();
        long cashflowCount = cashflowMapper.selectCount(null);
        long cashflowStocks = countDistinctCashflowFiltered(cashflowMapper, validCodes);
        cashflow.put("count", cashflowCount);
        cashflow.put("stocks", cashflowStocks);
        result.put("cashflow", cashflow);

        Map<String, Object> indicator = new LinkedHashMap<>();
        long indicatorCount = indicatorMapper.selectCount(null);
        long indicatorStocks = countDistinctIndicatorFiltered(indicatorMapper, validCodes);
        indicator.put("count", indicatorCount);
        indicator.put("stocks", indicatorStocks);
        result.put("indicator", indicator);

        Set<String> allCodes = new HashSet<>();
        allCodes.addAll(getDistinctCodes(incomeMapper));
        allCodes.addAll(getDistinctCodesBalance(balanceMapper));
        allCodes.addAll(getDistinctCodesCashflow(cashflowMapper));
        allCodes.addAll(getDistinctCodesIndicator(indicatorMapper));
        allCodes.retainAll(validCodes);
        result.put("uniqueStocks", allCodes.size());

        List<String> logLines = new ArrayList<>();
        try {
            Path logPath = Path.of("c:/Users/warning5/WorkBuddy/Claw/update_data/_financial_update.log");
            if (Files.exists(logPath)) {
                List<String> allLines = Files.readAllLines(logPath);
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

    /**
     * 财务数据校验报告
     */
    public Map<String, Object> validate() {
        Map<String, Object> result = new LinkedHashMap<>();

        Map<String, Map<String, Object>> tableStats = new LinkedHashMap<>();
        for (String[] table : new String[][]{
                {"stock_financial_indicator", "财务指标表"},
                {"stock_income", "利润表"},
                {"stock_balance", "资产负债表"},
                {"stock_cashflow", "现金流量表"}
        }) {
            assertSafeIdentifier(table[0]);
            Map<String, Object> stats = jdbcTemplate.queryForMap(
                    "SELECT COUNT(*) as cnt, COUNT(DISTINCT code) as stock_cnt FROM " + table[0]);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("label", table[1]);
            item.put("records", ((Number) stats.get("cnt")).longValue());
            item.put("stocks", ((Number) stats.get("stock_cnt")).longValue());
            tableStats.put(table[0], item);
        }
        result.put("tableStats", tableStats);

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
            assertSafeIdentifier(table);
            assertSafeField(field);
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

        return result;
    }

    private static void assertSafeIdentifier(String table) {
        if (table == null || !ALLOWED_TABLES.contains(table) || !SAFE_IDENTIFIER.matcher(table).matches()) {
            throw new IllegalArgumentException("非法表名: " + table);
        }
    }

    private static void assertSafeField(String field) {
        if (field == null || !ALLOWED_FIELDS.contains(field) || !SAFE_IDENTIFIER.matcher(field).matches()) {
            throw new IllegalArgumentException("非法字段名: " + field);
        }
    }

    private long countDistinctFiltered(StockIncomeMapper mapper, Set<String> validCodes) {
        Set<String> codes = getDistinctCodes(mapper);
        codes.retainAll(validCodes);
        return codes.size();
    }

    private long countDistinctBalanceFiltered(StockBalanceMapper mapper, Set<String> validCodes) {
        Set<String> codes = getDistinctCodesBalance(mapper);
        codes.retainAll(validCodes);
        return codes.size();
    }

    private long countDistinctCashflowFiltered(StockCashflowMapper mapper, Set<String> validCodes) {
        Set<String> codes = getDistinctCodesCashflow(mapper);
        codes.retainAll(validCodes);
        return codes.size();
    }

    private long countDistinctIndicatorFiltered(StockFinancialIndicatorMapper mapper, Set<String> validCodes) {
        Set<String> codes = getDistinctCodesIndicator(mapper);
        codes.retainAll(validCodes);
        return codes.size();
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
