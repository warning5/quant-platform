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

    private String getDateColumn(String table) {
        return DATE_COLUMNS.getOrDefault(table, "trade_date");
    }

    /**
     * 获取 ClickHouse 连接
     */
    private Connection getConnection() throws Exception {
        return DriverManager.getConnection(clickHouseConfig.getJdbcUrl());
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
        int tableCount = 0;
        List<Map<String, Object>> tableStats = new ArrayList<>();

        for (String table : SENTIMENT_TABLES) {
            try {
                // 查询记录数
                String countSql = "SELECT COUNT(*) as cnt FROM " + table;
                Long count = 0L;
                try (Connection conn = getConnection();
                     PreparedStatement stmt = conn.prepareStatement(countSql);
                     ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        count = rs.getLong("cnt");
                    }
                }

                // 始终统计，即使 count=0
                tableCount++;
                totalRecords += count;

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

        // 追加：基金持仓、股东人数、新闻（MySQL-only 表，但同属数据采集管线）
        for (String[] extra : new String[][]{
                {"stock_fund_holder", "基金持仓", "report_date"},
                {"stock_shareholder", "股东人数", "report_date"},
                {"stock_news", "新闻", "publish_date"}
        }) {
            String table = extra[0];
            String name = extra[1];
            String dateCol = extra[2];
            try {
                Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) as cnt FROM " + table, Integer.class);
                if (count == null) count = 0;
                totalRecords += count;

                // 新闻表 publish_date 含时间，只取 DATE 部分
                String dateSql = "news".equals(extra[0])
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

        result.put("tableCount", tableStats.size());
        result.put("totalRecords", totalRecords);
        result.put("tables", tableStats);
        return result;
    }

    /**
     * 获取指定表的详细统计（优先 ClickHouse）
     */
    public Map<String, Object> getTableStats(String table) {
        if (!SENTIMENT_TABLES.contains(table)) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "无效的表名: " + table);
            return error;
        }

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

            tableValidations.add(validation);
        }

        result.put("tables", tableValidations);
        result.put("tableCount", tableValidations.size());
        result.put("totalWarnings", totalWarnings);
        result.put("status", totalWarnings == 0 ? "OK" : "WARNING");

        return result;
    }
}
