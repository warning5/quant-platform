package com.quant.platform.dataupdate;

import com.quant.platform.config.ClickHouseConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

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

    // 各表的日期字段名（notice/survey 用 notice_date，其余用 trade_date）
    private static final Map<String, String> DATE_COLUMNS = new HashMap<>();
    static {
        DATE_COLUMNS.put("stock_sentiment_notice", "notice_date");
        DATE_COLUMNS.put("stock_sentiment_survey", "notice_date");
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
            "trade_date", "notice_date", "report_date", "publish_date", "update_time"
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

        if (!clickHouseConfig.isEnabled()) {
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
        if (!clickHouseConfig.isEnabled()) {
            return sentimentService.validate();
        }

        try {
            return queryValidateFromClickHouse();
        } catch (Exception e) {
            log.warn("[ClickHouse] 数据校验失败，回退到 MySQL: {}", e.getMessage());
        }

        return sentimentService.validate();
    }

    /**
     * 从 ClickHouse 查询校验结果
     */
    private Map<String, Object> queryValidateFromClickHouse() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> tableValidations = new ArrayList<>();
        int totalWarnings = 0;

        for (String table : SENTIMENT_TABLES) {
            // 白名单校验
            validateTableAndColumn(table, getDateColumn(table));

            Map<String, Object> validation = new LinkedHashMap<>();
            validation.put("table", table);
            validation.put("name", TABLE_NAMES.getOrDefault(table, table));

            // 记录数
            String countSql = "SELECT COUNT(*) as cnt FROM " + table;
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(countSql);
                 ResultSet rs = stmt.executeQuery()) {
                Long count = rs.next() ? rs.getLong("cnt") : 0L;
                validation.put("recordCount", count);
            }

            // 空值检查
            List<Map<String, Object>> nullChecks = new ArrayList<>();
            try {
                String nullCheckSql = "SELECT SUM(CASE WHEN code IS NULL OR code = '' THEN 1 ELSE 0 END) as null_count FROM " + table;
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

            // 最近7天是否有数据
            String dateCol = getDateColumn(table);
            String recentSql = "SELECT COUNT(*) as cnt FROM " + table +
                    " WHERE " + dateCol + " >= today() - 7";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(recentSql);
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Long recentCount = rs.getLong("cnt");
                    validation.put("hasRecentData", recentCount > 0);
                    validation.put("recentRecordCount", recentCount);
                }
            } catch (Exception e) {
                validation.put("hasRecentData", null);
                validation.put("recentRecordCount", 0);
            }

            // 资金流向专属深度校验
            if ("stock_sentiment_moneyflow".equals(table)) {
                List<Map<String, Object>> extraChecks = new ArrayList<>();

                // 校验1: CH vs MySQL 两端记录数对比
                try {
                    Long chCount = (Long) validation.get("recordCount");
                    Long myCount = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM stock_sentiment_moneyflow", Long.class);
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
                             "SELECT COUNT(*) FROM stock_sentiment_moneyflow FINAL " +
                             "WHERE net_main=0 AND net_huge=0 AND net_big=0 AND net_medium=0 AND net_small=0");
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
                             "SELECT COUNT(*) FROM stock_sentiment_moneyflow FINAL WHERE close=0");
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

        return result;
    }
}
