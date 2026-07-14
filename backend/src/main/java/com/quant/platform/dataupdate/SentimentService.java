package com.quant.platform.dataupdate;

import com.quant.platform.calendar.service.TradeCalendarService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 情绪数据服务
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SentimentService {

    private final JdbcTemplate jdbcTemplate;
    private final TradeCalendarService tradeCalendarService;

    // 情绪数据表列表
    // 保留：zt(涨跌停池) + moneyflow(资金情绪代理) + notice(公告)
    // 新建：lhb/lhb_inst/margin/margin_detail/survey/block_trade/activity
    private static final List<String> SENTIMENT_TABLES = Arrays.asList(
            "stock_sentiment_zt",
            "stock_sentiment_moneyflow",
            "stock_sentiment_notice",
            "stock_sentiment_lhb",
            "stock_sentiment_lhb_inst",
            "stock_sentiment_margin",
            "stock_sentiment_margin_detail",
            "stock_sentiment_survey",
            "stock_sentiment_block_trade",
            "stock_sentiment_activity"
    );

    // 表的中文名和描述
    private static final Map<String, String> TABLE_NAMES = new LinkedHashMap<>();
    static {
        TABLE_NAMES.put("stock_sentiment_zt", "涨跌停池");
        TABLE_NAMES.put("stock_sentiment_moneyflow", "资金流向");
        TABLE_NAMES.put("stock_sentiment_notice", "公告");
        TABLE_NAMES.put("stock_sentiment_lhb", "龙虎榜");
        TABLE_NAMES.put("stock_sentiment_lhb_inst", "龙虎榜机构明细");
        TABLE_NAMES.put("stock_sentiment_margin", "融资融券");
        TABLE_NAMES.put("stock_sentiment_margin_detail", "融资融券明细");
        TABLE_NAMES.put("stock_sentiment_survey", "机构调研");
        TABLE_NAMES.put("stock_sentiment_block_trade", "大宗交易");
        TABLE_NAMES.put("stock_sentiment_activity", "市场活跃度");
        TABLE_NAMES.put("stock_fund_holder", "基金持仓");
        TABLE_NAMES.put("stock_shareholder", "股东人数");
        TABLE_NAMES.put("stock_news", "新闻");
        TABLE_NAMES.put("macro_bond_yield", "国债收益率");
        TABLE_NAMES.put("stock_consensus_estimate", "一致预期");
        TABLE_NAMES.put("stock_earnings_report", "业绩快报");
    }

    // 各表的日期字段名（notice 用 notice_date，survey 用 meeting_date，其余用 trade_date）
    private static final Map<String, String> DATE_COLUMNS = new HashMap<>();
    static {
        DATE_COLUMNS.put("stock_sentiment_notice", "notice_date");
        DATE_COLUMNS.put("stock_sentiment_survey", "meeting_date");
        DATE_COLUMNS.put("stock_fund_holder", "report_date");
        DATE_COLUMNS.put("stock_shareholder", "report_date");
        DATE_COLUMNS.put("stock_news", "publish_date");
        DATE_COLUMNS.put("stock_consensus_estimate", "update_time");
        DATE_COLUMNS.put("stock_earnings_report", "report_date");
    }

    // 允许直接拼接到 SQL 中的表名白名单（sentiment 表 + coverage 中追加的 MySQL-only 表）
    private static final Set<String> ALLOWED_TABLES;
    static {
        Set<String> set = new HashSet<>(SENTIMENT_TABLES);
        set.add("stock_fund_holder");
        set.add("stock_shareholder");
        set.add("stock_news");
        set.add("macro_bond_yield");
        set.add("stock_consensus_estimate");
        set.add("stock_earnings_report");
        ALLOWED_TABLES = Collections.unmodifiableSet(set);
    }

    // 允许直接拼接到 SQL 中的日期列名白名单
    private static final Set<String> ALLOWED_DATE_COLUMNS = new HashSet<>(Arrays.asList(
            "trade_date", "notice_date", "meeting_date", "report_date", "publish_date", "update_time"
    ));

    private String getDateColumn(String table) {
        return DATE_COLUMNS.getOrDefault(table, "trade_date");
    }

    /**
     * 校验表名和日期列名是否在白名单中，避免 SQL 拼接被注入。
     * 校验不通过直接抛出 IllegalArgumentException，不执行后续 SQL。
     */
    private static void validateTableAndColumn(String table, String dateCol) {
        if (!ALLOWED_TABLES.contains(table)) {
            throw new IllegalArgumentException("无效的表名: " + table);
        }
        if (!ALLOWED_DATE_COLUMNS.contains(dateCol)) {
            throw new IllegalArgumentException("无效的日期列: " + dateCol);
        }
    }

    /**
     * 获取情绪数据概览
     */
    public Map<String, Object> getCoverage() {
        Map<String, Object> result = new LinkedHashMap<>();

        int totalRecords = 0;
        List<Map<String, Object>> tableStats = new ArrayList<>();

        for (String table : SENTIMENT_TABLES) {
            try {
                // 白名单校验：SQL 拼接前确认表名和日期列名合法
                validateTableAndColumn(table, getDateColumn(table));

                // 查询记录数
                String countSql = "SELECT COUNT(*) as cnt FROM " + table;
                Integer count = jdbcTemplate.queryForObject(countSql, Integer.class);
                if (count == null) count = 0;

                totalRecords += count;

                // 查询时间范围（不同表日期字段名不同）
                String dateCol = getDateColumn(table);
                String dateSql = "SELECT MIN(" + dateCol + ") as min_date, MAX(" + dateCol + ") as max_date FROM " + table;
                Map<String, Object> dateRange = jdbcTemplate.queryForMap(dateSql);

                Map<String, Object> tableStat = new LinkedHashMap<>();
                tableStat.put("table", table);
                tableStat.put("name", TABLE_NAMES.getOrDefault(table, table));
                tableStat.put("recordCount", count);
                tableStat.put("minDate", dateRange.get("min_date"));
                tableStat.put("maxDate", dateRange.get("max_date"));
                tableStats.add(tableStat);
            } catch (Exception e) {
                // 表不存在时也记录（count=0）
                log.warn("查询表 {} 失败: {}", table, e.getMessage());
                Map<String, Object> tableStat = new LinkedHashMap<>();
                tableStat.put("table", table);
                tableStat.put("name", TABLE_NAMES.getOrDefault(table, table));
                tableStat.put("recordCount", 0);
                tableStat.put("minDate", null);
                tableStat.put("maxDate", null);
                tableStats.add(tableStat);
            }
        }

        // 追加：基金持仓、股东人数、新闻、国债收益率、一致预期、业绩快报（非情绪数据表，但同属数据采集管线）
        for (String[] extra : new String[][]{
                {"stock_fund_holder", "基金持仓", "report_date"},
                {"stock_shareholder", "股东人数", "report_date"},
                {"stock_news", "新闻", "publish_date"},
                {"macro_bond_yield", "国债收益率", "trade_date"},
                {"stock_consensus_estimate", "一致预期", "update_time"},
                {"stock_earnings_report", "业绩快报", "report_date"}
        }) {
            String table = extra[0];
            String name = extra[1];
            String dateCol = extra[2];
            try {
                // 白名单校验
                validateTableAndColumn(table, dateCol);

                Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) as cnt FROM " + table, Integer.class);
                if (count == null) count = 0;
                totalRecords += count;

                // 新闻/一致预期的日期字段含时间，只取 DATE 部分
                String dateSql = ("news".equals(extra[0]) || "stock_consensus_estimate".equals(extra[0]))
                        ? "SELECT MIN(DATE(" + dateCol + ")) as min_date, MAX(DATE(" + dateCol + ")) as max_date FROM " + table
                        : "SELECT MIN(" + dateCol + ") as min_date, MAX(" + dateCol + ") as max_date FROM " + table;
                Map<String, Object> dateRange = jdbcTemplate.queryForMap(dateSql);

                Map<String, Object> tableStat = new LinkedHashMap<>();
                tableStat.put("table", table);
                tableStat.put("name", name);
                tableStat.put("recordCount", count);
                tableStat.put("minDate", dateRange.get("min_date"));
                tableStat.put("maxDate", dateRange.get("max_date"));
                tableStats.add(tableStat);
            } catch (Exception e) {
                log.warn("查询表 {} 失败: {}", table, e.getMessage());
                Map<String, Object> tableStat = new LinkedHashMap<>();
                tableStat.put("table", table);
                tableStat.put("name", name);
                tableStat.put("recordCount", 0);
                tableStat.put("minDate", null);
                tableStat.put("maxDate", null);
                tableStats.add(tableStat);
            }
        }

        result.put("tableCount", tableStats.size());
        result.put("totalRecords", totalRecords);
        result.put("tables", tableStats);

        return result;
    }

    /**
     * 获取指定表的详细统计
     */
    public Map<String, Object> getTableStats(String table) {
        // 先计算日期列并完成白名单校验，校验失败直接抛异常
        String dateCol = getDateColumn(table);
        validateTableAndColumn(table, dateCol);

        Map<String, Object> result = new LinkedHashMap<>();

        try {
            // 记录数
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
            result.put("recordCount", count != null ? count : 0);

            // 时间范围
            try {
                Map<String, Object> dateRange = jdbcTemplate.queryForMap(
                        "SELECT MIN(" + dateCol + ") as min_date, MAX(" + dateCol + ") as max_date FROM " + table);
                result.put("minDate", dateRange.get("min_date"));
                result.put("maxDate", dateRange.get("max_date"));
            } catch (Exception e) {
                // 表可能没有对应日期字段
                result.put("minDate", null);
                result.put("maxDate", null);
            }

            // 按日期分布（最近30天）
            String dateDistSql = "SELECT " + dateCol + " as trade_date, COUNT(*) as cnt FROM " + table +
                    " WHERE " + dateCol + " >= DATE_SUB(CURDATE(), INTERVAL 30 DAY)" +
                    " GROUP BY " + dateCol + " ORDER BY " + dateCol + " DESC LIMIT 30";
            try {
                List<Map<String, Object>> dateDist = jdbcTemplate.queryForList(dateDistSql);
                result.put("dateDistribution", dateDist);
            } catch (Exception e) {
                result.put("dateDistribution", Collections.emptyList());
            }

        } catch (Exception e) {
            log.error("获取表 {} 统计失败: {}", table, e.getMessage(), e);
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * 数据校验
     */
    public Map<String, Object> validate() {
        return validate(null, null, Collections.emptyList());
    }

    public Map<String, Object> validate(String startDate, String endDate) {
        return validate(startDate, endDate, Collections.emptyList());
    }

    public Map<String, Object> validate(String startDate, String endDate, List<String> tables) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> tableValidations = new ArrayList<>();
        int totalWarnings = 0;

        List<String> tablesToValidate = (tables == null || tables.isEmpty())
                ? SENTIMENT_TABLES
                : tables.stream()
                        .filter(ALLOWED_TABLES::contains)
                        .distinct()
                        .collect(Collectors.toList());

        for (String table : tablesToValidate) {
            try {
                // 白名单校验
                String dateCol = getDateColumn(table);
                validateTableAndColumn(table, dateCol);

                Map<String, Object> validation = new LinkedHashMap<>();
                validation.put("table", table);
                validation.put("name", TABLE_NAMES.getOrDefault(table, table));

                String dateRangeCondition = buildDateRangeCondition(dateCol, startDate, endDate);

                // 记录数（按日期范围）
                Integer count = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM " + table + dateRangeCondition, Integer.class);
                validation.put("recordCount", count != null ? count : 0);

                // 空值检查（按日期范围）
                List<Map<String, Object>> nullChecks = new ArrayList<>();
                try {
                    String nullCheckSql = "SELECT 'code' as field, SUM(CASE WHEN code IS NULL OR code = '' THEN 1 ELSE 0 END) as null_count FROM " + table + dateRangeCondition +
                            " UNION ALL " +
                            "SELECT 'trade_date' as field, SUM(CASE WHEN trade_date IS NULL THEN 1 ELSE 0 END) FROM " + table + dateRangeCondition;
                    List<Map<String, Object>> nullResults = jdbcTemplate.queryForList(nullCheckSql);
                    for (Map<String, Object> r : nullResults) {
                        Long nullCount = ((Number) r.get("null_count")).longValue();
                        if (nullCount > 0) {
                            Map<String, Object> check = new LinkedHashMap<>();
                            check.put("field", r.get("field"));
                            check.put("nullCount", nullCount);
                            check.put("message", "存在 " + nullCount + " 条空值");
                            nullChecks.add(check);
                        }
                    }
                } catch (Exception e) {
                    // 忽略字段不存在错误
                }
                validation.put("nullChecks", nullChecks);
                validation.put("warningCount", nullChecks.size());
                totalWarnings += nullChecks.size();

                // 日期范围内是否有数据
                validation.put("hasRecentData", count != null && count > 0);
                validation.put("recentRecordCount", count != null ? count : 0);

                tableValidations.add(validation);
            } catch (Exception e) {
                log.warn("校验表 {} 失败: {}", table, e.getMessage());
            }
        }

        result.put("tables", tableValidations);
        result.put("tableCount", tableValidations.size());
        result.put("totalWarnings", totalWarnings);
        result.put("status", totalWarnings == 0 ? "OK" : "WARNING");
        result.put("dateRangeStart", isValidDateParam(startDate) ? startDate : null);
        result.put("dateRangeEnd", isValidDateParam(endDate) ? endDate : null);
        result.put("totalRecords", tableValidations.stream()
                .mapToLong(t -> ((Number) t.get("recordCount")).longValue())
                .sum());

        return result;
    }

    public Map<String, Object> validateDaily(String startDate, String endDate, List<String> tables) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> tablesToValidate = (tables == null || tables.isEmpty())
                ? SENTIMENT_TABLES
                : tables.stream()
                        .filter(ALLOWED_TABLES::contains)
                        .distinct()
                        .collect(Collectors.toList());

        Map<String, String> tableNames = new LinkedHashMap<>();
        Map<String, Map<String, DailyRow>> tableDateMap = new LinkedHashMap<>();
        Set<String> allDates = new TreeSet<>(Collections.reverseOrder());

        for (String table : tablesToValidate) {
            try {
                String dateCol = getDateColumn(table);
                validateTableAndColumn(table, dateCol);
                String dateRangeCondition = buildDateRangeCondition(dateCol, startDate, endDate);

                Map<String, DailyRow> rows = new LinkedHashMap<>();
                String countSql = "SELECT DATE(" + dateCol + ") as d, COUNT(*) as cnt FROM " + table +
                        dateRangeCondition + " GROUP BY DATE(" + dateCol + ") ORDER BY d";
                List<Map<String, Object>> countList = jdbcTemplate.queryForList(countSql);
                for (Map<String, Object> r : countList) {
                    String d = r.get("d") != null ? r.get("d").toString() : null;
                    if (d == null || d.isEmpty()) continue;
                    long cnt = ((Number) r.get("cnt")).longValue();
                    rows.put(d, new DailyRow(cnt, 0L));
                    allDates.add(d);
                }

                try {
                    String nullSql = "SELECT DATE(" + dateCol + ") as d, " +
                            "SUM(CASE WHEN code IS NULL OR code = '' THEN 1 ELSE 0 END) as null_count FROM " + table +
                            dateRangeCondition + " GROUP BY DATE(" + dateCol + ") ORDER BY d";
                    List<Map<String, Object>> nullList = jdbcTemplate.queryForList(nullSql);
                    for (Map<String, Object> r : nullList) {
                        String d = r.get("d") != null ? r.get("d").toString() : null;
                        if (d == null || d.isEmpty()) continue;
                        long nullCount = ((Number) r.get("null_count")).longValue();
                        DailyRow row = rows.get(d);
                        if (row != null) {
                            row.nullCount = nullCount;
                        } else {
                            rows.put(d, new DailyRow(0L, nullCount));
                            allDates.add(d);
                        }
                    }
                } catch (Exception e) {
                    log.debug("表 {} 无 code 空值统计: {}", table, e.getMessage());
                }

                tableDateMap.put(table, rows);
                tableNames.put(table, TABLE_NAMES.getOrDefault(table, table));
            } catch (Exception e) {
                log.warn("按日校验表 {} 失败: {}", table, e.getMessage());
            }
        }

        List<Map<String, Object>> dailyStats = new ArrayList<>();
        Set<String> tradingDates = isValidDateParam(startDate) && isValidDateParam(endDate)
                ? tradeCalendarService.getTradingDaysBetween(LocalDate.parse(startDate), LocalDate.parse(endDate))
                        .stream().map(LocalDate::toString).collect(Collectors.toSet())
                : null;
        for (String date : allDates) {
            if (tradingDates != null && !tradingDates.contains(date)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", date);
            long totalCount = 0L;
            long totalNullCount = 0L;
            for (String table : tablesToValidate) {
                DailyRow dr = tableDateMap.getOrDefault(table, Collections.emptyMap()).getOrDefault(date, DailyRow.ZERO);
                row.put(table, dr.count);
                row.put(table + "_null", dr.nullCount);
                totalCount += dr.count;
                totalNullCount += dr.nullCount;
            }
            row.put("totalCount", totalCount);
            row.put("totalNullCount", totalNullCount);
            dailyStats.add(row);
        }

        result.put("dailyStats", dailyStats);
        result.put("tableNames", tableNames);
        result.put("dateRangeStart", isValidDateParam(startDate) ? startDate : null);
        result.put("dateRangeEnd", isValidDateParam(endDate) ? endDate : null);
        return result;
    }

    private static class DailyRow {
        static final DailyRow ZERO = new DailyRow(0L, 0L);
        long count;
        long nullCount;
        DailyRow(long count, long nullCount) {
            this.count = count;
            this.nullCount = nullCount;
        }
    }

    private boolean isValidDateParam(String date) {
        if (date == null || date.isEmpty()) {
            return false;
        }
        return date.matches("\\d{4}-\\d{2}-\\d{2}");
    }

    private String buildDateRangeCondition(String dateCol, String startDate, String endDate) {
        StringBuilder sb = new StringBuilder();
        if (isValidDateParam(startDate)) {
            sb.append(dateCol).append(" >= '").append(startDate).append("'");
        }
        if (isValidDateParam(endDate)) {
            if (sb.length() > 0) {
                sb.append(" AND ");
            }
            sb.append(dateCol).append(" <= '").append(endDate).append("'");
        }
        return sb.length() > 0 ? " WHERE " + sb.toString() : "";
    }
}
