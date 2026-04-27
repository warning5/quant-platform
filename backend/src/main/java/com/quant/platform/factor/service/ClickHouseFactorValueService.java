package com.quant.platform.factor.service;

import com.quant.platform.config.ClickHouseConfig;
import com.quant.platform.factor.domain.FactorValue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.*;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * ClickHouse 因子值服务
 * 优先从 ClickHouse 查询，失败时回退到 MySQL（通过 FactorValueMapper）
 * 所有查询方法统一遵循: CH enabled → 查 CH → 失败/无数据 → 回退 MySQL
 * 写入方法统一: 同时写入 MySQL + ClickHouse
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClickHouseFactorValueService {

    private final ClickHouseConfig clickHouseConfig;

    // ==================== 查询方法（CH 优先） ====================

    /**
     * 根据因子代码和日期查询因子值（CH 优先）
     */
    public List<FactorValue> findByFactorCodeAndDate(String factorCode, LocalDate calcDate) {
        if (!clickHouseConfig.isEnabled()) {
            return List.of(); // 回退到 Mapper
        }

        try {
            List<FactorValue> result = queryByFactorCodeAndDateFromCH(factorCode, calcDate);
            if (!result.isEmpty()) {
                log.debug("[ClickHouse] 因子值命中: {} {}", factorCode, calcDate);
                return result;
            }
        } catch (Exception e) {
            log.warn("[ClickHouse] 因子值查询失败，回退: {}", e.getMessage());
        }
        return List.of(); // 回退到 Mapper
    }

    /**
     * 根据因子代码和日期范围查询因子值（CH 优先）
     */
    public List<FactorValue> findByFactorCodeAndDateRange(String factorCode, LocalDate startDate, LocalDate endDate) {
        if (!clickHouseConfig.isEnabled()) {
            return List.of(); // 回退到 Mapper
        }

        try {
            List<FactorValue> result = queryByFactorCodeAndDateRangeFromCH(factorCode, startDate, endDate);
            if (!result.isEmpty()) {
                log.debug("[ClickHouse] 因子值范围查询命中: {} {}~{}", factorCode, startDate, endDate);
                return result;
            }
        } catch (Exception e) {
            log.warn("[ClickHouse] 因子值范围查询失败，回退: {}", e.getMessage());
        }
        return List.of(); // 回退到 Mapper
    }

    /**
     * 聚合统计查询（CH 优先）
     */
    public List<Map<String, Object>> selectFactorStats() {
        if (!clickHouseConfig.isEnabled()) {
            return List.of(); // 回退到 Mapper
        }

        try {
            return queryFactorStatsFromCH();
        } catch (Exception e) {
            log.warn("[ClickHouse] 因子统计查询失败，回退: {}", e.getMessage());
        }
        return List.of(); // 回退到 Mapper
    }

    /**
     * 总记录数查询（CH 优先）
     */
    public long selectTotalCount() {
        if (!clickHouseConfig.isEnabled()) {
            return -1; // 回退到 Mapper
        }

        try {
            return queryTotalCountFromCH();
        } catch (Exception e) {
            log.warn("[ClickHouse] 总记录数查询失败，回退: {}", e.getMessage());
        }
        return -1; // 回退到 Mapper
    }

    // ==================== 写入方法（双写） ====================

    /**
     * 批量写入因子值到 ClickHouse
     */
    public void batchUpsertToCH(List<FactorValue> values) {
        if (!clickHouseConfig.isEnabled() || values == null || values.isEmpty()) {
            return;
        }

        String sql = """
            INSERT INTO stock.factor_value
            (id, factor_code, symbol, calc_date, factor_val, rank_value, z_score, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            for (FactorValue fv : values) {
                stmt.setLong(1, fv.getId() != null ? fv.getId() : 0);
                stmt.setString(2, fv.getFactorCode());
                stmt.setString(3, fv.getSymbol());
                stmt.setString(4, fv.getCalcDate().toString());
                setParam(stmt, 5, fv.getFactorVal());
                setParam(stmt, 6, fv.getRankValue());
                setParam(stmt, 7, fv.getZScore());
                setParam(stmt, 8, fv.getCreatedAt());
                stmt.addBatch();
            }

            stmt.executeBatch();
            log.debug("[ClickHouse] 批量写入 {} 条因子值", values.size());
        } catch (Exception e) {
            log.error("[ClickHouse] 批量写入因子值失败: {}", e.getMessage(), e);
            throw new RuntimeException("ClickHouse 批量写入失败: " + e.getMessage(), e);
        }
    }

    // ==================== ClickHouse 查询实现 ====================

    private List<FactorValue> queryByFactorCodeAndDateFromCH(String factorCode, LocalDate calcDate) {
        String sql = """
            SELECT id, factor_code, symbol, calc_date, factor_val, rank_value, z_score, created_at
            FROM stock.factor_value
            WHERE factor_code = ? AND calc_date = ?
            ORDER BY symbol
            """;

        List<FactorValue> result = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, factorCode);
            stmt.setString(2, calcDate.toString());

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(convertResultSet(rs));
                }
            }
        } catch (Exception e) {
            log.warn("[ClickHouse] findByFactorCodeAndDate 查询失败: {}", e.getMessage());
            throw new RuntimeException("ClickHouse 查询失败", e);
        }
        return result;
    }

    private List<FactorValue> queryByFactorCodeAndDateRangeFromCH(String factorCode, LocalDate startDate, LocalDate endDate) {
        String sql = """
            SELECT id, factor_code, symbol, calc_date, factor_val, rank_value, z_score, created_at
            FROM stock.factor_value
            WHERE factor_code = ? AND calc_date >= ? AND calc_date <= ?
            ORDER BY calc_date, symbol
            """;

        List<FactorValue> result = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, factorCode);
            stmt.setString(2, startDate.toString());
            stmt.setString(3, endDate.toString());

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(convertResultSet(rs));
                }
            }
        } catch (Exception e) {
            log.warn("[ClickHouse] findByFactorCodeAndDateRange 查询失败: {}", e.getMessage());
            throw new RuntimeException("ClickHouse 查询失败", e);
        }
        return result;
    }

    private List<Map<String, Object>> queryFactorStatsFromCH() {
        String sql = """
            SELECT factor_code,
                   count() AS cnt,
                   min(calc_date) AS min_date,
                   max(calc_date) AS max_date,
                   count(DISTINCT calc_date) AS days,
                   count(DISTINCT symbol) AS stocks
            FROM stock.factor_value
            GROUP BY factor_code
            """;

        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("factor_code", rs.getString("factor_code"));
                row.put("cnt", rs.getLong("cnt"));
                row.put("min_date", rs.getDate("min_date"));
                row.put("max_date", rs.getDate("max_date"));
                row.put("days", rs.getLong("days"));
                row.put("stocks", rs.getLong("stocks"));
                result.add(row);
            }
        } catch (Exception e) {
            log.warn("[ClickHouse] selectFactorStats 查询失败: {}", e.getMessage());
            throw new RuntimeException("ClickHouse 统计查询失败", e);
        }
        return result;
    }

    private long queryTotalCountFromCH() {
        String sql = "SELECT count() FROM stock.factor_value";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            if (rs.next()) {
                return rs.getLong(1);
            }
        } catch (Exception e) {
            log.warn("[ClickHouse] selectTotalCount 查询失败: {}", e.getMessage());
            throw new RuntimeException("ClickHouse 总数查询失败", e);
        }
        return 0;
    }

    // ==================== 辅助方法 ====================

    private FactorValue convertResultSet(ResultSet rs) throws SQLException {
        FactorValue fv = new FactorValue();
        fv.setId(rs.getLong("id"));
        fv.setFactorCode(rs.getString("factor_code"));
        fv.setSymbol(rs.getString("symbol"));

        Date date = rs.getDate("calc_date");
        fv.setCalcDate(date != null ? date.toLocalDate() : null);

        double factorVal = rs.getDouble("factor_val");
        fv.setFactorVal(rs.wasNull() ? null : BigDecimal.valueOf(factorVal));

        double rankValue = rs.getDouble("rank_value");
        fv.setRankValue(rs.wasNull() ? null : BigDecimal.valueOf(rankValue));

        double zScore = rs.getDouble("z_score");
        fv.setZScore(rs.wasNull() ? null : BigDecimal.valueOf(zScore));

        Timestamp createdAt = rs.getTimestamp("created_at");
        fv.setCreatedAt(createdAt != null ? createdAt.toLocalDateTime() : null);

        return fv;
    }

    private void setParam(PreparedStatement stmt, int index, BigDecimal value) throws SQLException {
        if (value != null) {
            stmt.setDouble(index, value.doubleValue());
        } else {
            stmt.setNull(index, Types.DOUBLE);
        }
    }

    private void setParam(PreparedStatement stmt, int index, LocalDateTime value) throws SQLException {
        if (value != null) {
            stmt.setTimestamp(index, Timestamp.valueOf(value));
        } else {
            stmt.setNull(index, Types.TIMESTAMP);
        }
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(clickHouseConfig.getJdbcUrl());
    }
}
