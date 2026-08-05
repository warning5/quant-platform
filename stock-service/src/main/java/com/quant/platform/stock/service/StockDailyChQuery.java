package com.quant.platform.stock.service;

import static com.quant.platform.stock.service.StockDailySqlSupport.*;

import com.quant.platform.stock.entity.StockDaily;
import com.quant.platform.stock.service.ClickHouseJdbcClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.*;
import java.sql.Date;
import java.time.LocalDate;
import java.util.*;

/**
 * ClickHouse 日线查询实现层（从 ClickHouseStockService 逐字搬出，no-behavior-change）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockDailyChQuery {

    private final ClickHouseJdbcClient chJdbcClient;

    private Connection getConnection() throws SQLException {
        return chJdbcClient.getConnection();
    }

    private List<StockDaily> executeQuery(String sql, LocalDate startDate, LocalDate endDate) {
        return chJdbcClient.executeQuery(sql, startDate, endDate);
    }

    private List<StockDaily> executeQuery(String sql, String code, LocalDate startDate, LocalDate endDate) {
        return chJdbcClient.executeQuery(sql, code, startDate, endDate);
    }

    public List<StockDaily> queryFromClickHouse(String code, LocalDate startDate, LocalDate endDate) {
        String sql = """
                SELECT code, trade_date, name, open_price, close_price, high_price, low_price,
                       pre_close, volume, amount, change_percent, change_amount,
                       turnover_rate, pe_ttm, pb
                FROM stock_daily FINAL
                WHERE code = ? AND trade_date >= ? AND trade_date <= ?
                ORDER BY trade_date
                """;

        return executeQuery(sql, code, startDate, endDate);
    }

    public List<StockDaily> queryBatchFromClickHouse(List<String> codes, LocalDate startDate, LocalDate endDate) {
        return queryBatchFromClickHouse(codes, startDate, endDate, true);
    }

    public List<StockDaily> queryBatchFromClickHouse(List<String> codes, LocalDate startDate, LocalDate endDate, boolean useFinal) {
        if (codes.size() == 1) {
            return queryFromClickHouse(codes.get(0), startDate, endDate);
        }

        String placeholders = String.join(",", Collections.nCopies(codes.size(), "?"));
        String finalClause = useFinal ? " FINAL" : "";
        String sql = String.format("""
                SELECT code, trade_date, name, open_price, close_price, high_price, low_price,
                       pre_close, volume, amount, change_percent, change_amount,
                       turnover_rate, pe_ttm, pb
                FROM stock_daily%s
                WHERE code IN (%s) AND trade_date >= ? AND trade_date <= ?
                ORDER BY code, trade_date
                """, finalClause, placeholders);

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            int paramIdx = 1;
            for (String code : codes) {
                stmt.setString(paramIdx++, code);
            }
            stmt.setString(paramIdx++, startDate.toString());
            stmt.setString(paramIdx, endDate.toString());
            
            try (ResultSet rs = stmt.executeQuery()) {
                List<StockDaily> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(convertResultSet(rs));
                }
                return result;
            }
        } catch (Exception e) {
            log.warn("[ClickHouse] 批量查询失败: {}", e.getMessage());
            throw new RuntimeException("ClickHouse 批量查询失败", e);
        }
    }

    public List<StockDaily> queryDailyByDateFromClickHouse(LocalDate date, Collection<String> excludeNames) {
        StringBuilder sql = new StringBuilder("""
                SELECT code, trade_date, name, open_price, close_price, high_price, low_price,
                       pre_close, volume, amount, change_percent, change_amount,
                       turnover_rate, pe_ttm, pb
                FROM stock_daily FINAL
                WHERE trade_date = ?
                """);

        if (excludeNames != null && !excludeNames.isEmpty()) {
            String placeholders = String.join(",", Collections.nCopies(excludeNames.size(), "?"));
            sql.append("AND name NOT IN (").append(placeholders).append(") ");
        }
        sql.append("ORDER BY code");

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            int paramIdx = 1;
            stmt.setString(paramIdx++, date.toString());
            if (excludeNames != null && !excludeNames.isEmpty()) {
                for (String name : excludeNames) {
                    stmt.setString(paramIdx++, name);
                }
            }
            try (ResultSet rs = stmt.executeQuery()) {
                List<StockDaily> result = new ArrayList<>();
                while (rs.next()) result.add(convertResultSet(rs));
                return result;
            }
        } catch (Exception e) {
            log.warn("[ClickHouse] 截面查询失败: {}", e.getMessage());
            throw new RuntimeException("ClickHouse 截面查询失败", e);
        }
    }

    public Map<String, Object> getCrossSectionPagedFromClickHouse(LocalDate date, int page, int size,
                                                                   String keyword, String sortField, String sortOrder) {
        StringBuilder whereSql = new StringBuilder("WHERE trade_date = ?");
        List<Object> params = new ArrayList<>();
        params.add(date.toString());
        
        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim();
            whereSql.append(" AND (code LIKE ? OR name LIKE ?)");
            params.add("%" + kw + "%");
            params.add("%" + kw + "%");
        }

        // 总数
        long total;
        String countSql = "SELECT COUNT(*) FROM stock_daily FINAL " + whereSql;
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(countSql)) {
            for (int i = 0; i < params.size(); i++) {
                stmt.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = stmt.executeQuery()) {
                total = rs.next() ? rs.getLong(1) : 0;
            }
        } catch (Exception e) {
            log.warn("[ClickHouse] 截面分页count查询失败: {}", e.getMessage());
            throw new RuntimeException("ClickHouse 截面分页查询失败", e);
        }

        // 排序
        String orderBy = buildOrderByClause(sortField, sortOrder);

        // 分页查询
        int offset = (page - 1) * size;
        String dataSql = """
                SELECT code, trade_date, name, open_price, close_price, high_price, low_price,
                       pre_close, volume, amount, change_percent, change_amount,
                       turnover_rate, pe_ttm, pb
                FROM stock_daily FINAL
                """ + whereSql + " " + orderBy + " LIMIT ? OFFSET ?";

        List<StockDaily> records = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(dataSql)) {
            int paramIdx = 1;
            for (Object param : params) {
                stmt.setObject(paramIdx++, param);
            }
            stmt.setInt(paramIdx++, size);
            stmt.setInt(paramIdx, offset);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) records.add(convertResultSet(rs));
            }
        } catch (Exception e) {
            log.warn("[ClickHouse] 截面分页查询失败: {}", e.getMessage());
            throw new RuntimeException("ClickHouse 截面分页查询失败", e);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        result.put("totalPages", (total + size - 1) / size);
        result.put("data", records);
        return result;
    }

    public Map<String, Object> getOverviewStatsFromClickHouse(LocalDate tradeDate) {
        // 排除指数代码（sh.000xxx / sz.399xxx），只统计股票
        String sql = """
                SELECT
                    COUNT(*) AS count,
                    countIf(change_percent > 0) AS riseCount,
                    countIf(change_percent < 0) AS fallCount,
                    countIf(change_percent IS NULL OR change_percent = 0) AS flatCount,
                    ifNull(avg(change_percent), 0) AS avgPctChg,
                    ifNull(SUM(amount), 0) AS totalAmount
                FROM stock_daily FINAL
                WHERE trade_date = ?
                  AND code NOT LIKE 'sh.000%'
                  AND code NOT LIKE 'sz.399%'
                """;

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, tradeDate.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> map = new HashMap<>();
                    map.put("count", rs.getLong("count"));
                    map.put("riseCount", rs.getLong("riseCount"));
                    map.put("fallCount", rs.getLong("fallCount"));
                    map.put("flatCount", rs.getLong("flatCount"));
                    map.put("avgPctChg", rs.getDouble("avgPctChg"));
                    map.put("totalAmount", rs.getBigDecimal("totalAmount"));
                    return map;
                }
            }
        } catch (Exception e) {
            log.warn("[ClickHouse] 概览统计查询失败: {}", e.getMessage());
            throw new RuntimeException("ClickHouse 概览统计失败", e);
        }
        return Map.of("count", 0L, "riseCount", 0L, "fallCount", 0L, "flatCount", 0L,
                "avgPctChg", 0.0, "totalAmount", BigDecimal.ZERO);
    }

    public List<Map<String, Object>> getTopByPctChgFromClickHouse(LocalDate tradeDate, int limit, String order) {
        // 排除指数代码（sh.000xxx / sz.399xxx）
        String orderClause = "ASC".equalsIgnoreCase(order) ? "ASC" : "DESC";
        String sql = """
                SELECT code, name, change_percent, close_price, volume, amount, turnover_rate
                FROM stock_daily FINAL
                WHERE trade_date = ? AND change_percent IS NOT NULL
                  AND code NOT LIKE 'sh.000%' AND code NOT LIKE 'sz.399%'
                ORDER BY change_percent
                """ + orderClause + " LIMIT ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, tradeDate.toString());
            stmt.setInt(2, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                List<Map<String, Object>> result = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    row.put("code", rs.getString("code"));
                    row.put("name", rs.getString("name"));
                    row.put("change_percent", rs.getDouble("change_percent"));
                    row.put("close_price", rs.getBigDecimal("close_price"));
                    row.put("volume", rs.getLong("volume"));
                    row.put("amount", rs.getBigDecimal("amount"));
                    row.put("turnover_rate", rs.getBigDecimal("turnover_rate"));
                    result.add(row);
                }
                return result;
            }
        } catch (Exception e) {
            log.warn("[ClickHouse] Top N 查询失败: {}", e.getMessage());
            throw new RuntimeException("ClickHouse Top N 查询失败", e);
        }
    }

    public LocalDate getLatestTradingDateFromClickHouse(LocalDate start, LocalDate end) {
        String sql = "SELECT MAX(trade_date) FROM stock_daily WHERE trade_date >= ? AND trade_date <= ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, start.toString());
            stmt.setString(2, end.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Date d = rs.getDate(1);
                    return d != null ? d.toLocalDate() : null;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("ClickHouse 最新交易日查询失败", e);
        }
        return null;
    }

    public List<LocalDate> getTradingDatesFromClickHouse(LocalDate start, LocalDate end) {
        String sql = "SELECT DISTINCT trade_date FROM stock_daily WHERE trade_date >= ? AND trade_date <= ? ORDER BY trade_date";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, start.toString());
            stmt.setString(2, end.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                List<LocalDate> result = new ArrayList<>();
                while (rs.next()) result.add(rs.getDate("trade_date").toLocalDate());
                return result;
            }
        } catch (Exception e) {
            throw new RuntimeException("ClickHouse 交易日列表查询失败", e);
        }
    }

    public List<String> getRecentTradingDatesFromClickHouse(int limit) {
        String sql = "SELECT DISTINCT trade_date FROM stock_daily ORDER BY trade_date DESC LIMIT ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, Math.min(limit, 10000));
            try (ResultSet rs = stmt.executeQuery()) {
                List<String> result = new ArrayList<>();
                while (rs.next()) result.add(rs.getString("trade_date"));
                return result;
            }
        } catch (Exception e) {
            throw new RuntimeException("ClickHouse 最近交易日查询失败", e);
        }
    }

    public LocalDate getExtremeDateFromClickHouse(boolean isMax) {
        // 排除指数代码（sh.000xxx / sz.399xxx），只统计股票
        String sql = isMax
                ? "SELECT MAX(trade_date) FROM stock_daily WHERE code NOT LIKE 'sh.000%' AND code NOT LIKE 'sz.399%'"
                : "SELECT MIN(trade_date) FROM stock_daily WHERE code NOT LIKE 'sh.000%' AND code NOT LIKE 'sz.399%'";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                Date d = rs.getDate(1);
                return d != null ? d.toLocalDate() : null;
            }
        } catch (Exception e) {
            throw new RuntimeException("ClickHouse 极端日期查询失败", e);
        }
        return null;
    }

    public Set<String> getExistingCodesFromClickHouse(LocalDate date, Collection<String> codes) {
        Set<String> result = new HashSet<>();
        List<String> codeList = new ArrayList<>(codes);
        int batchSize = 500;

        // 批量查询，避免超长 IN clause
        for (int i = 0; i < codeList.size(); i += batchSize) {
            List<String> batch = codeList.subList(i, Math.min(i + batchSize, codeList.size()));
            String placeholders = String.join(",", Collections.nCopies(batch.size(), "?"));
            String sql = "SELECT DISTINCT code FROM stock_daily WHERE trade_date = ? AND code IN (" + placeholders + ")";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                int paramIdx = 1;
                stmt.setString(paramIdx++, date.toString());
                for (String code : batch) {
                    stmt.setString(paramIdx++, code);
                }
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) result.add(rs.getString("code"));
                }
            } catch (Exception e) {
                throw new RuntimeException("ClickHouse existing codes 查询失败", e);
            }
        }
        return result;
    }

    public List<Map<String, Object>> queryForListFromClickHouse(String sql, Object... params) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                stmt.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = stmt.executeQuery()) {
                List<Map<String, Object>> result = new ArrayList<>();
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    for (int i = 1; i <= colCount; i++) {
                        row.put(meta.getColumnLabel(i), rs.getObject(i));
                    }
                    result.add(row);
                }
                return result;
            }
        } catch (Exception e) {
            throw new RuntimeException("ClickHouse 通用查询失败", e);
        }
    }

    public Object queryForObjectFromClickHouse(String sql, Object... params) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                stmt.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getObject(1);
            }
        } catch (Exception e) {
            throw new RuntimeException("ClickHouse 通用单值查询失败", e);
        }
        return null;
    }
}
