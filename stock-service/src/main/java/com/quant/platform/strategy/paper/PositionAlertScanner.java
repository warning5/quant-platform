package com.quant.platform.strategy.paper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 持仓扫描层
 * 均线跌破 / 单日大跌 / 重大公告 / 研报变化 四类持仓扫描检测。
 * 数据访问委托 AlertDataLoader，行情/公告走 ClickHouse，研报走 MySQL。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PositionAlertScanner {

    private final AlertDataLoader alertData;
    private final JdbcTemplate jdbcTemplate;

    @Autowired(required = false)
    @Qualifier("clickHouseJdbcTemplate")
    private JdbcTemplate clickHouseJdbcTemplate;

    // ─── 数据访问委托（AlertDataLoader） ───────────────────────────────
    private void saveAlert(PositionAlert alert) { alertData.saveAlert(alert); }

    // ─── 内部方法 ──────────────────────────────────────────────────────

    private static final String[] IMPORTANT_NOTICE_TYPES = {
        "业绩预告", "业绩快报", "终止上市风险提示", "实施退市风险警示",
        "股票交易异常波动", "停牌公告", "违法违规", "处罚",
        "股东/实际控制人股份减持", "重大事故损失", "诉讼仲裁",
        "其他增发事项公告", "股份质押、冻结",
    };



    /**
     * 均线跌破检测
     * 检查今日收盘价是否跌破 MA20 或 MA60
     * 逻辑：今日收盘 < MA(N)，且昨日收盘 >= MA(N)，即今日破位
     */
    public int checkMaBreak(Long paperId, PaperPosition pos, LocalDate today) {
        if (clickHouseJdbcTemplate == null) return 0;
        int count = 0;

        int[] periods = {20, 60};
        for (int n : periods) {
            try {
                // 取最近 n+2 天的收盘价，确保能算 MA(N) 和判断破位
                // 修复 SQL 注入：使用 ? 占位符，不拼接 pos.getCode()
                String sql = String.format("""
                    SELECT trade_date, close_price,
                           avg(close_price) OVER (ORDER BY trade_date ROWS BETWEEN %d PRECEDING AND CURRENT ROW) as ma
                    FROM (
                        SELECT trade_date, close_price
                        FROM stock.stock_daily FINAL
                        WHERE code = ? AND trade_date <= ?
                        ORDER BY trade_date DESC
                        LIMIT %d
                    ) sub
                    ORDER BY trade_date ASC
                    """, n - 1, n + 5);

                List<Map<String, Object>> rows = clickHouseJdbcTemplate.query(
                    sql, 
                    new Object[]{pos.getCode(), today.toString()},
                    (rs, rowNum) -> {
                        Map<String, Object> m = new HashMap<>();
                        m.put("date", rs.getString("trade_date"));
                        m.put("close", rs.getBigDecimal("close_price"));
                        m.put("ma", rs.getBigDecimal("ma"));
                        return m;
                    });

                if (rows.size() < n + 2) continue;

                // 取最后两天
                Map<String, Object> yesterday = rows.get(rows.size() - 2);
                Map<String, Object> todayRow = rows.getLast();

                BigDecimal todayClose = (BigDecimal) todayRow.get("close");
                BigDecimal todayMa = (BigDecimal) todayRow.get("ma");
                BigDecimal ydClose = (BigDecimal) yesterday.get("close");
                BigDecimal ydMa = (BigDecimal) yesterday.get("ma");

                if (todayClose == null || todayMa == null || ydClose == null || ydMa == null) continue;

                // 今日收盘 < MA(N)，且昨日收盘 >= MA(N)
                boolean todayBelow = todayClose.compareTo(todayMa) < 0;
                boolean ydAbove = ydClose.compareTo(ydMa) >= 0;

                if (todayBelow && ydAbove) {
                    String maLabel = "MA" + n;
                    LocalDate dataDate = LocalDate.parse(todayRow.get("date").toString());
                    saveAlert(PositionAlert.builder()
                        .paperId(paperId)
                        .code(pos.getCode())
                        .name(pos.getName())
                        .alertType("MA_BREAK")
                        .alertLevel(n >= 60 ? "CRITICAL" : "WARNING")
                        .title(String.format("%s %s %s 跌破%s", dataDate, pos.getCode(), pos.getName(), maLabel))
                        .detail(String.format("%s 收盘价 %.2f 跌破 %s（%.2f），前一日 %.2f 在 %s（%.2f）之上",
                            dataDate, todayClose, maLabel, todayMa, ydClose, maLabel, ydMa))
                        .alertDate(dataDate)
                        .isRead(false)
                        .build());
                    count++;
                }
            } catch (Exception e) {
                log.debug("均线跌破检测失败: code={}, period={}, error={}", pos.getCode(), n, e.getMessage());
            }
        }
        return count;
    }


    /**
     * 大跌检测
     * 当日跌幅超过 5% 触发 WARNING，超过 8% 触发 CRITICAL
     */
    public int checkBigDrop(Long paperId, PaperPosition pos, LocalDate today) {
        if (clickHouseJdbcTemplate == null) return 0;

        try {
            // 修复 SQL 注入：使用 ? 占位符
            String sql = """
                SELECT trade_date, close_price, change_percent
                FROM stock.stock_daily FINAL
                WHERE code = ? AND trade_date <= ?
                ORDER BY trade_date DESC
                LIMIT 2
                """;

            List<Map<String, Object>> rows = clickHouseJdbcTemplate.query(
                sql,
                new Object[]{pos.getCode(), today.toString()},
                (rs, rowNum) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("date", rs.getString("trade_date"));
                    m.put("close", rs.getBigDecimal("close_price"));
                    m.put("change", rs.getBigDecimal("change_percent"));
                    return m;
                });

            if (rows.isEmpty()) return 0;
            Map<String, Object> latest = rows.getFirst();
            BigDecimal changePct = (BigDecimal) latest.get("change");
            if (changePct == null) return 0;

            // change_percent 存的是百分比形式（如 -5.23 表示跌 5.23%）
            double drop = changePct.doubleValue();
            if (drop <= -5.0) {
                String level = drop <= -8.0 ? "CRITICAL" : "WARNING";
                LocalDate dataDate = LocalDate.parse(latest.get("date").toString());
                saveAlert(PositionAlert.builder()
                    .paperId(paperId)
                    .code(pos.getCode())
                    .name(pos.getName())
                    .alertType("DROP")
                    .alertLevel(level)
                    .title(String.format("%s %s %s 单日跌幅 %.2f%%", dataDate, pos.getCode(), pos.getName(), Math.abs(drop)))
                    .detail(String.format("日期 %s，收盘价 %.2f，跌幅 %.2f%%，当前持仓 %d 股",
                        dataDate, latest.get("close"), Math.abs(drop), pos.getShares()))
                    .alertDate(dataDate)
                    .isRead(false)
                    .build());
                return 1;
            }
        } catch (Exception e) {
            log.debug("大跌检测失败: code={}, error={}", pos.getCode(), e.getMessage());
        }
        return 0;
    }


    /**
     * 重大公告检测
     * 查询持仓股最近3天的重要公告
     */
    public int checkImportantNotices(Long paperId, PaperPosition pos, LocalDate today) {
        if (clickHouseJdbcTemplate == null) return 0;
        int count = 0;

        try {
            // 构建 notice_type IN 条件（静态常量，低风险）
            // 修复 SQL 注入：code 和 notice_date 使用 ? 占位符
            String placeholders = String.join(",", java.util.Collections.nCopies(IMPORTANT_NOTICE_TYPES.length, "?"));

            String sql =
                "SELECT notice_type, notice_date, title " +
                "FROM stock.stock_sentiment_notice FINAL " +
                "WHERE code = ? " +
                "  AND notice_date >= ? " +
                "  AND notice_type IN (" + placeholders + ") " +
                "ORDER BY notice_date DESC " +
                "LIMIT 5";

            // 构建参数数组：code + notice_date + 所有 notice_type
            Object[] params = new Object[2 + IMPORTANT_NOTICE_TYPES.length];
            params[0] = pos.getCode();
            params[1] = today.minusDays(3);
            for (int i = 0; i < IMPORTANT_NOTICE_TYPES.length; i++) {
                params[2 + i] = IMPORTANT_NOTICE_TYPES[i];
            }

            List<Map<String, Object>> rows = clickHouseJdbcTemplate.query(
                sql, params,
                (rs, rowNum) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("type", rs.getString("notice_type"));
                    m.put("date", rs.getString("notice_date"));
                    m.put("title", rs.getString("title"));
                    return m;
                });

            for (Map<String, Object> row : rows) {
                LocalDate noticeDate = LocalDate.parse(row.get("date").toString());
                saveAlert(PositionAlert.builder()
                    .paperId(paperId)
                    .code(pos.getCode())
                    .name(pos.getName())
                    .alertType("NOTICE")
                    .alertLevel("WARNING")
                    .title(String.format("%s %s: %s", noticeDate, row.get("type"), row.get("title")))
                    .detail(String.format("公告日期: %s，类型: %s", noticeDate, row.get("type")))
                    .alertDate(noticeDate)
                    .isRead(false)
                    .build());
                count++;
            }
        } catch (Exception e) {
            log.debug("公告检测失败: code={}, error={}", pos.getCode(), e.getMessage());
        }
        return count;
    }


    /**
     * 研报变化检测
     * 查询持仓股最近3天的新增研报（评级变动特别关注）
     */
    public int checkResearchReports(Long paperId, PaperPosition pos, LocalDate today) {
        int count = 0;

        try {
            String sql = "SELECT report_title, rating, institution, report_date " +
                "FROM stock_research_report " +
                "WHERE code = ? AND report_date >= ? " +
                "ORDER BY report_date DESC LIMIT 3";

            List<Map<String, Object>> rows = jdbcTemplate.query(sql, (rs, rowNum) -> {
                Map<String, Object> m = new HashMap<>();
                m.put("title", rs.getString("report_title"));
                m.put("rating", rs.getString("rating"));
                m.put("institution", rs.getString("institution"));
                m.put("date", rs.getString("report_date"));
                return m;
            }, pos.getCode(), today.minusDays(3));

            for (Map<String, Object> row : rows) {
                // 只对包含评级关键词的报告预警（避免信息过载）
                String title = (String) row.get("title");
                if (title != null && (title.contains("首次") || title.contains("增持") || title.contains("买入") || title.contains("减持") || title.contains("下调"))) {
                    LocalDate reportDate = LocalDate.parse(row.get("date").toString());
                    saveAlert(PositionAlert.builder()
                        .paperId(paperId)
                        .code(pos.getCode())
                        .name(pos.getName())
                        .alertType("REPORT")
                        .alertLevel("INFO")
                        .title(String.format("%s %s 研报: %s", reportDate, pos.getName(), title))
                        .detail(String.format("机构: %s，评级: %s，日期: %s",
                            row.get("institution"), row.get("rating"), reportDate))
                        .alertDate(reportDate)
                        .isRead(false)
                        .build());
                    count++;
                }
            }
        } catch (Exception e) {
            log.debug("研报检测失败: code={}, error={}", pos.getCode(), e.getMessage());
        }
        return count;
    }
}
