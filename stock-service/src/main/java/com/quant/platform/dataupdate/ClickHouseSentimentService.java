package com.quant.platform.dataupdate;

import com.quant.platform.calendar.service.TradeCalendarService;
import com.quant.platform.config.ClickHouseConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ClickHouse 情绪数据服务
 * 优先从 ClickHouse 查询，失败时回退到 MySQL SentimentService
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClickHouseSentimentService {

    private final ClickHouseConfig clickHouseConfig;
    private final SentimentService sentimentService;  // MySQL fallback
    private final TradeCalendarService tradeCalendarService;
    private final JdbcTemplate jdbcTemplate;  // 用于查询MySQL-only的表（基金持仓/股东人数/新闻）

    // 情绪数据表列表（与 SentimentService 一致）
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
    }

    // 各表的日期字段名（notice 用 notice_date，survey 用 meeting_date；MySQL-only 表补充，避免外部调用 getDateColumn 拿到错误的 trade_date）
    private static final Map<String, String> DATE_COLUMNS = new HashMap<>();
    static {
        DATE_COLUMNS.put("stock_sentiment_notice", "notice_date");
        DATE_COLUMNS.put("stock_sentiment_survey", "meeting_date");
        DATE_COLUMNS.put("stock_fund_holder", "report_date");
        DATE_COLUMNS.put("stock_shareholder", "report_date");
        DATE_COLUMNS.put("stock_news", "publish_date");
        DATE_COLUMNS.put("macro_bond_yield", "trade_date");
        DATE_COLUMNS.put("stock_consensus_estimate", "update_time");
        DATE_COLUMNS.put("stock_earnings_report", "report_date");
    }

    // 允许直接拼接到 SQL 中的表名白名单（sentiment 表 + coverage 中追加的 MySQL-only 表 + CH-only 表）
    private static final Set<String> ALLOWED_TABLES;
    static {
        Set<String> set = new HashSet<>(SENTIMENT_TABLES);
        set.add("stock_fund_holder");
        set.add("stock_shareholder");
        set.add("stock_news");
        set.add("macro_bond_yield");
        set.add("stock_consensus_estimate");
        set.add("stock_earnings_report");
        set.add("index_daily");
        set.add("market_sentiment");
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
     * 校验日期字符串格式，仅允许 yyyy-MM-dd
     */
    private boolean isValidDateParam(String date) {
        if (date == null || date.isEmpty()) {
            return false;
        }
        return date.matches("\\d{4}-\\d{2}-\\d{2}");
    }

    /**
     * 构建 ClickHouse 日期范围过滤条件（已校验格式，表名/列名调用前已白名单校验）
     */
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
     * 获取 ClickHouse 连接
     */
    private Connection getConnection() throws Exception {
        Properties props = new Properties();
        props.setProperty("user", clickHouseConfig.getUsername());
        if (clickHouseConfig.getPassword() != null && !clickHouseConfig.getPassword().isEmpty()) {
            props.setProperty("password", clickHouseConfig.getPassword());
        }
        return DriverManager.getConnection(clickHouseConfig.getJdbcUrl(), props);
    }

    /**
     * 获取情绪数据概览（优先 ClickHouse）
     */
    public Map<String, Object> getCoverage() {
        if (!clickHouseConfig.isEnabled()) {
            log.debug("[ClickHouse] 未启用，回退到 MySQL");
            return sentimentService.getCoverage();
        }

        try {
            Map<String, Object> result = queryCoverageFromClickHouse();
            log.debug("[ClickHouse] 情绪概览查询成功，共 {} 张表", result.get("tableCount"));
            return result;
        } catch (Exception e) {
            log.warn("[ClickHouse] 情绪概览查询失败，回退到 MySQL: {}", e.getMessage());
        }

        return sentimentService.getCoverage();
    }

    /**
     * 从 ClickHouse 查询情绪数据概览
     */
    private Map<String, Object> queryCoverageFromClickHouse() {
        Map<String, Object> result = new LinkedHashMap<>();
        int totalRecords = 0;
        List<Map<String, Object>> tableStats = new ArrayList<>();

        for (String table : SENTIMENT_TABLES) {
            try {
                // 白名单校验：SQL 拼接前确认表名和日期列名合法
                validateTableAndColumn(table, getDateColumn(table));

                // 查询记录数
                String countSql = "SELECT COUNT(*) as cnt FROM " + table;
                long count = 0L;
                try (Connection conn = getConnection();
                     PreparedStatement stmt = conn.prepareStatement(countSql);
                     ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        count = rs.getLong("cnt");
                    }
                }

                // 始终统计，即使 count=0
                totalRecords += (int) count;

                // 查询时间范围（不同表日期字段名不同）
                String dateCol = getDateColumn(table);
                String dateSql = "SELECT MIN(" + dateCol + ") as min_date, MAX(" + dateCol + ") as max_date FROM " + table;
                String minDate = null;
                String maxDate = null;
                try (Connection conn = getConnection();
                     PreparedStatement stmt = conn.prepareStatement(dateSql);
                     ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        minDate = rs.getString("min_date");
                        maxDate = rs.getString("max_date");
                    }
                }

                Map<String, Object> tableStat = new LinkedHashMap<>();
                tableStat.put("table", table);
                tableStat.put("name", TABLE_NAMES.getOrDefault(table, table));
                tableStat.put("recordCount", count);
                tableStat.put("minDate", minDate);
                tableStat.put("maxDate", maxDate);
                tableStats.add(tableStat);
            } catch (Exception e) {
                // 表不存在时也记录（count=0）
                log.warn("ClickHouse 查询表 {} 失败: {}", table, e.getMessage());
                Map<String, Object> tableStat = new LinkedHashMap<>();
                tableStat.put("table", table);
                tableStat.put("name", TABLE_NAMES.getOrDefault(table, table));
                tableStat.put("recordCount", 0);
                tableStat.put("minDate", null);
                tableStat.put("maxDate", null);
                tableStats.add(tableStat);
            }
        }

        // 追加：基金持仓、股东人数、新闻、国债收益率、一致预期、业绩快报（MySQL-only 表）
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
                log.warn("查询MySQL表 {} 失败: {}", table, e.getMessage());
                Map<String, Object> tableStat = new LinkedHashMap<>();
                tableStat.put("table", table);
                tableStat.put("name", name);
                tableStat.put("recordCount", 0);
                tableStat.put("minDate", null);
                tableStat.put("maxDate", null);
                tableStats.add(tableStat);
            }
        }

        // 追加：申万行业指数（ClickHouse 表）
        try {
            String chTable = "index_daily";
            // 白名单校验
            validateTableAndColumn(chTable, "trade_date");
            Long count = 0L;
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) as cnt FROM " + chTable);
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    count = rs.getLong("cnt");
                }
            }
            totalRecords += count;

            String minDate = null;
            String maxDate = null;
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement("SELECT MIN(trade_date) as min_date, MAX(trade_date) as max_date FROM " + chTable);
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    minDate = rs.getString("min_date");
                    maxDate = rs.getString("max_date");
                }
            }

            Map<String, Object> tableStat = new LinkedHashMap<>();
            tableStat.put("table", chTable);
            tableStat.put("name", "申万行业指数");
            tableStat.put("recordCount", count);
            tableStat.put("minDate", minDate);
            tableStat.put("maxDate", maxDate);
            tableStats.add(tableStat);
        } catch (Exception e) {
            log.warn("查询ClickHouse表 index_daily 失败: {}", e.getMessage());
            Map<String, Object> tableStat = new LinkedHashMap<>();
            tableStat.put("table", "index_daily");
            tableStat.put("name", "申万行业指数");
            tableStat.put("recordCount", 0);
            tableStat.put("minDate", null);
            tableStat.put("maxDate", null);
            tableStats.add(tableStat);
        }

        // 追加：QVIX恐慌指数（ClickHouse market_sentiment 表）
        try {
            String chTable = "market_sentiment";
            // 白名单校验
            validateTableAndColumn(chTable, "trade_date");
            Long count = 0L;
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) as cnt FROM " + chTable);
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    count = rs.getLong("cnt");
                }
            }
            totalRecords += count;

            String minDate = null;
            String maxDate = null;
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement("SELECT MIN(trade_date) as min_date, MAX(trade_date) as max_date FROM " + chTable);
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    minDate = rs.getString("min_date");
                    maxDate = rs.getString("max_date");
                }
            }

            Map<String, Object> tableStat = new LinkedHashMap<>();
            tableStat.put("table", chTable);
            tableStat.put("name", "QVIX恐慌指数");
            tableStat.put("recordCount", count);
            tableStat.put("minDate", minDate);
            tableStat.put("maxDate", maxDate);
            tableStats.add(tableStat);
        } catch (Exception e) {
            log.warn("查询ClickHouse表 market_sentiment(QVIX) 失败: {}", e.getMessage());
            Map<String, Object> tableStat = new LinkedHashMap<>();
            tableStat.put("table", "market_sentiment");
            tableStat.put("name", "QVIX恐慌指数");
            tableStat.put("recordCount", 0);
            tableStat.put("minDate", null);
            tableStat.put("maxDate", null);
            tableStats.add(tableStat);
        }

        result.put("tableCount", tableStats.size());
        result.put("totalRecords", totalRecords);
        result.put("tables", tableStats);
        return result;
    }

    /**
     * 获取指定表的详细统计（优先 ClickHouse）
     */
    public Map<String, Object> getTableStats(String table) {
        // 先完成白名单校验，非法表名直接抛异常
        validateTableAndColumn(table, getDateColumn(table));

        // MySQL-only 表直接走 MySQL，避免 ClickHouse UNKNOWN_TABLE 警告
        if (!clickHouseConfig.isEnabled() || !SENTIMENT_TABLES.contains(table)) {
            return sentimentService.getTableStats(table);
        }

        try {
            return queryTableStatsFromClickHouse(table);
        } catch (Exception e) {
            log.warn("[ClickHouse] 表统计查询失败，回退到 MySQL: {}", e.getMessage());
        }

        return sentimentService.getTableStats(table);
    }

    /**
     * 从 ClickHouse 查询表统计
     */
    private Map<String, Object> queryTableStatsFromClickHouse(String table) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        String dateCol = getDateColumn(table);
        // 白名单校验
        validateTableAndColumn(table, dateCol);

        // 记录数
        String countSql = "SELECT COUNT(*) as cnt FROM " + table;
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(countSql);
             ResultSet rs = stmt.executeQuery()) {
            Long count = rs.next() ? rs.getLong("cnt") : 0L;
            result.put("recordCount", count);
        }

        // 时间范围
        try {
            String dateSql = "SELECT MIN(" + dateCol + ") as min_date, MAX(" + dateCol + ") as max_date FROM " + table;
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(dateSql);
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    result.put("minDate", rs.getString("min_date"));
                    result.put("maxDate", rs.getString("max_date"));
                }
            }
        } catch (Exception e) {
            result.put("minDate", null);
            result.put("maxDate", null);
        }

        // 按日期分布（最近30天）- ClickHouse 语法
        String dateDistSql = "SELECT toString(" + dateCol + ") as trade_date, COUNT(*) as cnt FROM " + table +
                " WHERE " + dateCol + " >= today() - 30" +
                " GROUP BY " + dateCol + " ORDER BY " + dateCol + " DESC LIMIT 30";
        try {
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(dateDistSql);
                 ResultSet rs = stmt.executeQuery()) {
                List<Map<String, Object>> dateDist = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    row.put("trade_date", rs.getString("trade_date"));
                    row.put("cnt", rs.getLong("cnt"));
                    dateDist.add(row);
                }
                result.put("dateDistribution", dateDist);
            }
        } catch (Exception e) {
            result.put("dateDistribution", Collections.emptyList());
        }

        return result;
    }

    /**
     * 数据校验（优先 ClickHouse）
     */
    public Map<String, Object> validate() {
        return validate(null, null, Collections.emptyList());
    }

    public Map<String, Object> validate(String startDate, String endDate) {
        return validate(startDate, endDate, Collections.emptyList());
    }

    public Map<String, Object> validate(String startDate, String endDate, List<String> tables) {
        if (!clickHouseConfig.isEnabled()) {
            return sentimentService.validate(startDate, endDate, tables);
        }

        // 拆分为 ClickHouse 表与 MySQL-only 表，分别校验后合并
        List<String> chTables;
        List<String> mysqlOnlyTables;
        if (tables == null || tables.isEmpty()) {
            chTables = new ArrayList<>(SENTIMENT_TABLES);
            mysqlOnlyTables = Collections.emptyList();
        } else {
            chTables = tables.stream()
                    .filter(SENTIMENT_TABLES::contains)
                    .distinct()
                    .collect(Collectors.toList());
            mysqlOnlyTables = tables.stream()
                    .filter(t -> !SENTIMENT_TABLES.contains(t) && ALLOWED_TABLES.contains(t))
                    .distinct()
                    .collect(Collectors.toList());
        }

        Map<String, Object> chResult = Collections.emptyMap();
        if (!chTables.isEmpty()) {
            try {
                chResult = queryValidateFromClickHouse(startDate, endDate, chTables);
            } catch (Exception e) {
                log.warn("[ClickHouse] 数据校验失败，回退到 MySQL: {}", e.getMessage());
                return sentimentService.validate(startDate, endDate, tables);
            }
        }

        Map<String, Object> mysqlResult = Collections.emptyMap();
        if (!mysqlOnlyTables.isEmpty()) {
            mysqlResult = sentimentService.validate(startDate, endDate, mysqlOnlyTables);
        }

        return mergeValidateResults(chResult, mysqlResult);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mergeValidateResults(Map<String, Object> chResult, Map<String, Object> mysqlResult) {
        List<Map<String, Object>> chTables = getListMap(chResult, "tables");
        List<Map<String, Object>> mysqlTables = getListMap(mysqlResult, "tables");

        List<Map<String, Object>> mergedTables = new ArrayList<>(chTables.size() + mysqlTables.size());
        mergedTables.addAll(chTables);
        mergedTables.addAll(mysqlTables);

        int chWarnings = 0;
        Object chWarnObj = chResult != null ? chResult.get("totalWarnings") : null;
        if (chWarnObj instanceof Number) chWarnings = ((Number) chWarnObj).intValue();
        int myWarnings = 0;
        Object myWarnObj = mysqlResult != null ? mysqlResult.get("totalWarnings") : null;
        if (myWarnObj instanceof Number) myWarnings = ((Number) myWarnObj).intValue();
        int totalWarnings = chWarnings + myWarnings;

        long chRecords = 0L;
        Object chRecObj = chResult != null ? chResult.get("totalRecords") : null;
        if (chRecObj instanceof Number) chRecords = ((Number) chRecObj).longValue();
        long myRecords = 0L;
        Object myRecObj = mysqlResult != null ? mysqlResult.get("totalRecords") : null;
        if (myRecObj instanceof Number) myRecords = ((Number) myRecObj).longValue();
        long totalRecords = chRecords + myRecords;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tables", mergedTables);
        result.put("tableCount", mergedTables.size());
        result.put("totalWarnings", totalWarnings);
        result.put("status", totalWarnings == 0 ? "OK" : "WARNING");
        result.put("dateRangeStart", chResult.getOrDefault("dateRangeStart", mysqlResult.get("dateRangeStart")));
        result.put("dateRangeEnd", chResult.getOrDefault("dateRangeEnd", mysqlResult.get("dateRangeEnd")));
        result.put("totalRecords", totalRecords);
        return result;
    }

    /**
     * 从 ClickHouse 查询校验结果
     */
    private Map<String, Object> queryValidateFromClickHouse(String startDate, String endDate, List<String> tables) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> tableValidations = new ArrayList<>();
        int totalWarnings = 0;

        List<String> tablesToValidate = (tables == null || tables.isEmpty())
                ? SENTIMENT_TABLES
                : tables.stream()
                        .filter(SENTIMENT_TABLES::contains) // 安全：只处理 ClickHouse 中存在的情绪表
                        .distinct()
                        .collect(Collectors.toList());

        for (String table : tablesToValidate) {
            // 白名单校验
            validateTableAndColumn(table, getDateColumn(table));

            Map<String, Object> validation = new LinkedHashMap<>();
            validation.put("table", table);
            validation.put("name", TABLE_NAMES.getOrDefault(table, table));

            String dateCol = getDateColumn(table);
            String dateRangeCondition = buildDateRangeCondition(dateCol, startDate, endDate);

            // 记录数（按日期范围）
            String countSql = "SELECT COUNT(*) as cnt FROM " + table + dateRangeCondition;
            Long count = 0L;
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(countSql);
                 ResultSet rs = stmt.executeQuery()) {
                count = rs.next() ? rs.getLong("cnt") : 0L;
                validation.put("recordCount", count);
            }

            // 空值检查（按日期范围）
            List<Map<String, Object>> nullChecks = new ArrayList<>();
            try {
                String nullCheckSql = "SELECT SUM(CASE WHEN code IS NULL OR code = '' THEN 1 ELSE 0 END) as null_count FROM " + table + dateRangeCondition;
                try (Connection conn = getConnection();
                     PreparedStatement stmt = conn.prepareStatement(nullCheckSql);
                     ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        Long nullCount = rs.getLong("null_count");
                        if (nullCount > 0) {
                            Map<String, Object> check = new LinkedHashMap<>();
                            check.put("field", "code");
                            check.put("nullCount", nullCount);
                            check.put("message", "存在 " + nullCount + " 条空值");
                            nullChecks.add(check);
                        }
                    }
                }
            } catch (Exception e) {
                // 忽略字段不存在错误
            }
            validation.put("nullChecks", nullChecks);
            validation.put("warningCount", nullChecks.size());
            totalWarnings += nullChecks.size();

            // 日期范围内是否有数据
            validation.put("hasRecentData", count > 0);
            validation.put("recentRecordCount", count);

            // 资金流向专属深度校验（按日期范围）
            if ("stock_sentiment_moneyflow".equals(table)) {
                List<Map<String, Object>> extraChecks = new ArrayList<>();
                String mfDateRangeCondition = buildDateRangeCondition("trade_date", startDate, endDate);

                // 校验1: CH vs MySQL 两端记录数对比
                try {
                    Long chCount = count;
                    String myCountSql = "SELECT COUNT(*) FROM stock_sentiment_moneyflow" +
                            (mfDateRangeCondition.isEmpty() ? "" : " WHERE " + mfDateRangeCondition.substring(7));
                    Long myCount = jdbcTemplate.queryForObject(myCountSql, Long.class);
                    if (myCount == null) myCount = 0L;
                    long diff = Math.abs(chCount - myCount);
                    Map<String, Object> check = new LinkedHashMap<>();
                    check.put("type", "CH_MYSQL_DIFF");
                    if (diff > 0) {
                        check.put("status", "WARN");
                        check.put("message", "CH=" + chCount + " / MySQL=" + myCount + "，差异" + diff + "条");
                        check.put("chCount", chCount);
                        check.put("myCount", myCount);
                        check.put("diff", diff);
                        extraChecks.add(check);
                        totalWarnings++;
                    } else {
                        // 一致时也记录（绿色对勾显示）
                        check.put("status", "OK");
                        check.put("message", "两端一致，各" + chCount + "条");
                        check.put("chCount", chCount);
                        check.put("myCount", myCount);
                        check.put("diff", 0);
                        extraChecks.add(check);
                    }
                } catch (Exception e) {
                    log.warn("CH-MySQL 对比查询失败: {}", e.getMessage());
                }

                // 校验2: all_zero — 5个资金流向字段全为0
                try (Connection conn = getConnection();
                     PreparedStatement stmt = conn.prepareStatement(
                             "SELECT COUNT(*) FROM stock_sentiment_moneyflow FINAL " + mfDateRangeCondition +
                             (mfDateRangeCondition.isEmpty() ? "WHERE " : " AND ") +
                             "net_main=0 AND net_huge=0 AND net_big=0 AND net_medium=0 AND net_small=0");
                     ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        Long allZeroCount = rs.getLong(1);
                        Map<String, Object> check = new LinkedHashMap<>();
                        check.put("type", "ALL_ZERO");
                        if (allZeroCount > 0) {
                            check.put("status", "WARN");
                            check.put("message", allZeroCount + " 条资金流向全为0（停牌/ST/westock无数据）");
                            check.put("count", allZeroCount);
                            extraChecks.add(check);
                            totalWarnings++;
                        } else {
                            check.put("status", "OK");
                            check.put("message", "无全0数据");
                            check.put("count", 0);
                            extraChecks.add(check);
                        }
                    }
                } catch (Exception e) {
                    log.warn("all_zero 检查失败: {}", e.getMessage());
                }

                // 校验3: close=0 — 收盘价为0（停牌股）
                try (Connection conn = getConnection();
                     PreparedStatement stmt = conn.prepareStatement(
                             "SELECT COUNT(*) FROM stock_sentiment_moneyflow FINAL" + mfDateRangeCondition +
                             (mfDateRangeCondition.isEmpty() ? " WHERE close=0" : " AND close=0"));
                     ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        Long closeZeroCount = rs.getLong(1);
                        Map<String, Object> check = new LinkedHashMap<>();
                        check.put("type", "CLOSE_ZERO");
                        if (closeZeroCount > 0) {
                            check.put("status", "WARN");
                            check.put("message", closeZeroCount + " 条收盘价为0（停牌股或westock缺失）");
                            check.put("count", closeZeroCount);
                            extraChecks.add(check);
                            totalWarnings++;
                        } else {
                            check.put("status", "OK");
                            check.put("message", "无close=0数据");
                            check.put("count", 0);
                            extraChecks.add(check);
                        }
                    }
                } catch (Exception e) {
                    log.warn("close=0 检查失败: {}", e.getMessage());
                }

                validation.put("extraChecks", extraChecks);
            }

            tableValidations.add(validation);
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
        if (!clickHouseConfig.isEnabled()) {
            return sentimentService.validateDaily(startDate, endDate, tables);
        }

        // 拆分为 ClickHouse 表与 MySQL-only 表，分别查询后合并
        List<String> chTables;
        List<String> mysqlOnlyTables;
        if (tables == null || tables.isEmpty()) {
            chTables = new ArrayList<>(SENTIMENT_TABLES);
            mysqlOnlyTables = Collections.emptyList();
        } else {
            chTables = tables.stream()
                    .filter(SENTIMENT_TABLES::contains)
                    .distinct()
                    .collect(Collectors.toList());
            mysqlOnlyTables = tables.stream()
                    .filter(t -> !SENTIMENT_TABLES.contains(t) && ALLOWED_TABLES.contains(t))
                    .distinct()
                    .collect(Collectors.toList());
        }

        Map<String, Object> chResult = Collections.emptyMap();
        if (!chTables.isEmpty()) {
            try {
                chResult = queryValidateDailyFromClickHouse(startDate, endDate, chTables);
            } catch (Exception e) {
                log.warn("[ClickHouse] 按日数据校验失败，回退到 MySQL: {}", e.getMessage());
                // 混合模式下也整体回退到 MySQL，保证结果一致
                return sentimentService.validateDaily(startDate, endDate, tables);
            }
        }

        Map<String, Object> mysqlResult = Collections.emptyMap();
        if (!mysqlOnlyTables.isEmpty()) {
            mysqlResult = sentimentService.validateDaily(startDate, endDate, mysqlOnlyTables);
        }

        return mergeValidateDailyResults(chResult, mysqlResult, chTables, mysqlOnlyTables);
    }

    private Map<String, Object> mergeValidateDailyResults(Map<String, Object> chResult, Map<String, Object> mysqlResult,
                                                         List<String> chTables, List<String> mysqlOnlyTables) {
        // 合并表名
        Map<String, String> mergedTableNames = new LinkedHashMap<>();
        mergedTableNames.putAll(getMapString(chResult, "tableNames"));
        mergedTableNames.putAll(getMapString(mysqlResult, "tableNames"));

        // 按日期合并 dailyStats：每行都包含所有表列，缺省为 0/null
        List<Map<String, Object>> chDaily = getListMap(chResult, "dailyStats");
        List<Map<String, Object>> mysqlDaily = getListMap(mysqlResult, "dailyStats");
        Set<String> allDates = new TreeSet<>(Collections.reverseOrder());
        for (Map<String, Object> row : chDaily) allDates.add(String.valueOf(row.get("date")));
        for (Map<String, Object> row : mysqlDaily) allDates.add(String.valueOf(row.get("date")));

        List<Map<String, Object>> mergedDailyStats = new ArrayList<>();
        for (String date : allDates) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", date);
            long totalCount = 0L;
            long totalNullCount = 0L;

            for (String t : chTables) {
                Object v = findValue(chDaily, date, t, 0L);
                Object nv = findValue(chDaily, date, t + "_null", 0L);
                row.put(t, v);
                row.put(t + "_null", nv);
                if (v instanceof Number) totalCount += ((Number) v).longValue();
                if (nv instanceof Number) totalNullCount += ((Number) nv).longValue();
            }
            for (String t : mysqlOnlyTables) {
                Object v = findValue(mysqlDaily, date, t, 0L);
                Object nv = findValue(mysqlDaily, date, t + "_null", 0L);
                row.put(t, v);
                row.put(t + "_null", nv);
                if (v instanceof Number) totalCount += ((Number) v).longValue();
                if (nv instanceof Number) totalNullCount += ((Number) nv).longValue();
            }
            row.put("totalCount", totalCount);
            row.put("totalNullCount", totalNullCount);
            mergedDailyStats.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dailyStats", mergedDailyStats);
        result.put("tableNames", mergedTableNames);
        result.put("dateRangeStart", chResult.getOrDefault("dateRangeStart", mysqlResult.get("dateRangeStart")));
        result.put("dateRangeEnd", chResult.getOrDefault("dateRangeEnd", mysqlResult.get("dateRangeEnd")));
        return result;
    }

    private Object findValue(List<Map<String, Object>> rows, String date, String key, Object defaultValue) {
        for (Map<String, Object> row : rows) {
            if (date.equals(String.valueOf(row.get("date")))) {
                return row.containsKey(key) ? row.get(key) : defaultValue;
            }
        }
        return defaultValue;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> getMapString(Map<String, Object> source, String key) {
        if (source == null || !source.containsKey(key)) return Collections.emptyMap();
        Object v = source.get(key);
        return (v instanceof Map) ? (Map<String, String>) v : Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getListMap(Map<String, Object> source, String key) {
        if (source == null || !source.containsKey(key)) return Collections.emptyList();
        Object v = source.get(key);
        return (v instanceof List) ? (List<Map<String, Object>>) v : Collections.emptyList();
    }

    private int getInt(Map<String, Object> source, String key) {
        if (source == null || !source.containsKey(key)) return 0;
        Object v = source.get(key);
        return (v instanceof Number) ? ((Number) v).intValue() : 0;
    }

    private long getLong(Map<String, Object> source, String key) {
        if (source == null || !source.containsKey(key)) return 0L;
        Object v = source.get(key);
        return (v instanceof Number) ? ((Number) v).longValue() : 0L;
    }

    private Map<String, Object> queryValidateDailyFromClickHouse(String startDate, String endDate, List<String> tables) throws Exception {
        List<String> tablesToValidate = (tables == null || tables.isEmpty())
                ? SENTIMENT_TABLES
                : tables.stream()
                        .filter(SENTIMENT_TABLES::contains) // 安全：只处理 ClickHouse 中存在的情绪表
                        .distinct()
                        .collect(Collectors.toList());

        Map<String, String> tableNames = new LinkedHashMap<>();
        Map<String, Map<String, DailyRow>> tableDateMap = new LinkedHashMap<>();
        Set<String> allDates = new TreeSet<>(Collections.reverseOrder());

        for (String table : tablesToValidate) {
            String dateCol = getDateColumn(table);
            validateTableAndColumn(table, dateCol);
            String dateRangeCondition = buildDateRangeCondition(dateCol, startDate, endDate);

            Map<String, DailyRow> rows = new LinkedHashMap<>();
            String countSql = "SELECT toString(toDate(" + dateCol + ")) as d, COUNT(*) as cnt FROM " + table +
                    dateRangeCondition + " GROUP BY toDate(" + dateCol + ") ORDER BY d";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(countSql);
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String d = rs.getString("d");
                    long cnt = rs.getLong("cnt");
                    rows.put(d, new DailyRow(cnt, 0L));
                    allDates.add(d);
                }
            }

            try {
                String nullSql = "SELECT toString(toDate(" + dateCol + ")) as d, " +
                        "SUM(CASE WHEN code IS NULL OR code = '' THEN 1 ELSE 0 END) as null_count FROM " + table +
                        dateRangeCondition + " GROUP BY toDate(" + dateCol + ") ORDER BY d";
                try (Connection conn = getConnection();
                     PreparedStatement stmt = conn.prepareStatement(nullSql);
                     ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String d = rs.getString("d");
                        DailyRow row = rows.get(d);
                        if (row != null) {
                            row.nullCount = rs.getLong("null_count");
                        } else {
                            rows.put(d, new DailyRow(0L, rs.getLong("null_count")));
                            allDates.add(d);
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("表 {} 无 code 空值统计: {}", table, e.getMessage());
            }

            tableDateMap.put(table, rows);
            tableNames.put(table, TABLE_NAMES.getOrDefault(table, table));
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

        Map<String, Object> result = new LinkedHashMap<>();
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
}
