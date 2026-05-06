package com.quant.platform.dataupdate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 情绪数据服务
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SentimentService {

    private final JdbcTemplate jdbcTemplate;

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
    }

    // 各表的日期字段名（notice 用 notice_date，survey 用 notice_date，其余用 trade_date）
    private static final Map<String, String> DATE_COLUMNS = new HashMap<>();
    static {
        DATE_COLUMNS.put("stock_sentiment_notice", "notice_date");
        DATE_COLUMNS.put("stock_sentiment_survey", "notice_date");
    }

    private String getDateColumn(String table) {
        return DATE_COLUMNS.getOrDefault(table, "trade_date");
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

        result.put("tableCount", tableStats.size());
        result.put("totalRecords", totalRecords);
        result.put("tables", tableStats);

        return result;
    }

    /**
     * 获取指定表的详细统计
     */
    public Map<String, Object> getTableStats(String table) {
        Map<String, Object> result = new LinkedHashMap<>();

        if (!SENTIMENT_TABLES.contains(table)) {
            result.put("error", "无效的表名: " + table);
            return result;
        }

        try {
            // 记录数
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
            result.put("recordCount", count != null ? count : 0);

            // 时间范围
            try {
                String dateCol = getDateColumn(table);
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
            String dateCol = getDateColumn(table);
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
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> tableValidations = new ArrayList<>();
        int totalWarnings = 0;

        for (String table : SENTIMENT_TABLES) {
            try {
                Map<String, Object> validation = new LinkedHashMap<>();
                validation.put("table", table);
                validation.put("name", TABLE_NAMES.getOrDefault(table, table));

                // 记录数
                Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
                validation.put("recordCount", count != null ? count : 0);

                // 空值检查
                List<Map<String, Object>> nullChecks = new ArrayList<>();
                try {
                    String nullCheckSql = "SELECT 'code' as field, SUM(CASE WHEN code IS NULL OR code = '' THEN 1 ELSE 0 END) as null_count FROM " + table +
                            " UNION ALL " +
                            "SELECT 'trade_date' as field, SUM(CASE WHEN trade_date IS NULL THEN 1 ELSE 0 END) FROM " + table;
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

                // 最近7天是否有数据
                String dateCol = getDateColumn(table);
                String recentSql = "SELECT COUNT(*) FROM " + table +
                        " WHERE " + dateCol + " >= DATE_SUB(CURDATE(), INTERVAL 7 DAY)";
                try {
                    Integer recentCount = jdbcTemplate.queryForObject(recentSql, Integer.class);
                    validation.put("hasRecentData", recentCount != null && recentCount > 0);
                    validation.put("recentRecordCount", recentCount != null ? recentCount : 0);
                } catch (Exception e) {
                    validation.put("hasRecentData", null);
                    validation.put("recentRecordCount", 0);
                }

                tableValidations.add(validation);
            } catch (Exception e) {
                log.warn("校验表 {} 失败: {}", table, e.getMessage());
            }
        }

        result.put("tables", tableValidations);
        result.put("tableCount", tableValidations.size());
        result.put("totalWarnings", totalWarnings);
        result.put("status", totalWarnings == 0 ? "OK" : "WARNING");

        return result;
    }
}
