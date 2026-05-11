package com.quant.platform.factor.service;

import com.quant.platform.config.ClickHouseConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    public java.util.List<com.quant.platform.factor.domain.FactorValue> findByFactorCodeAndDate(
            String factorCode, java.time.LocalDate calcDate) {
        if (!clickHouseConfig.isEnabled()) {
            return List.of(); // 回退到 Mapper
        }

        try {
            java.util.List<com.quant.platform.factor.domain.FactorValue> result = queryByFactorCodeAndDateFromCH(factorCode, calcDate);
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
    public java.util.List<com.quant.platform.factor.domain.FactorValue> findByFactorCodeAndDateRange(
            String factorCode, java.time.LocalDate startDate, java.time.LocalDate endDate) {
        if (!clickHouseConfig.isEnabled()) {
            return List.of(); // 回退到 Mapper
        }

        try {
            java.util.List<com.quant.platform.factor.domain.FactorValue> result = queryByFactorCodeAndDateRangeFromCH(factorCode, startDate, endDate);
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
     * 按因子代码列表批量查询统计（只查CH，不降级到MySQL）
     * 用于因子列表页的计算状态展示
     */
    public Map<String, Map<String, Object>> batchGetStatusFromCH(List<String> factorCodes) {
        if (!clickHouseConfig.isEnabled() || factorCodes == null || factorCodes.isEmpty()) {
            return Map.of();
        }

        String placeholders = String.join(",", factorCodes.stream()
                .map(c -> "'" + c.replace("'", "''") + "'").toList());

        String sql = String.format("""
                SELECT factor_code,
                       count() AS value_count,
                       min(calc_date) AS min_date,
                       max(calc_date) AS max_date
                FROM stock.factor_value FINAL
                WHERE factor_code IN (%s)
                GROUP BY factor_code
                """, placeholders);

        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                String code = rs.getString("factor_code");
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("valueCount", rs.getLong("value_count"));
                java.sql.Date minDate = rs.getDate("min_date");
                java.sql.Date maxDate = rs.getDate("max_date");
                if (minDate != null) entry.put("minDate", minDate.toString());
                if (maxDate != null) entry.put("maxDate", maxDate.toString());
                result.put(code, entry);
            }
        } catch (Exception e) {
            log.error("[ClickHouse] batchGetStatus 查询失败: {}", e.getMessage(), e);
            throw new RuntimeException("ClickHouse 批量状态查询失败", e);
        }
        return result;
    }

    /**
     * 缠论因子筛选 Pivot 查询（CH 版本）
     * penDir / trend / buySell 传 null 表示不过滤
     * 动态SQL：自动从 factor_definition 表查询 CHANTHEORY 类别的激活因子，
     * 动态构建 pivot 列，新增/删除/重命名缠论因子后自动生效。
     */
    public List<Map<String, Object>> chanScreen(
            List<Integer> penDirList,
            List<Integer> trendList,
            List<Integer> buySellList,
            BigDecimal hubPosMin,
            BigDecimal hubPosMax,
            BigDecimal penCountMin,
            BigDecimal penCountMax,
            String keyword,
            List<String> factorCodes) {
        if (!clickHouseConfig.isEnabled()) {
            return List.of(); // 回退到 Mapper
        }

        // factorCodes 由调用方（FactorService）从 MySQL 的 factor_definition 表动态传入
        if (factorCodes == null || factorCodes.isEmpty()) {
            log.warn("[ClickHouse] chanScreen: 未传入因子代码列表，返回空结果");
            return List.of();
        }

        // 2. 构建 IN 子句
        String factorInClause = factorCodes.stream()
                .map(c -> "'" + c.replace("'", "''") + "'")
                .reduce((a, b) -> a + "," + b)
                .orElse("''");

        // 3. 构造 WHERE 条件片段（兼容现有5个因子的筛选参数）
        StringBuilder where = new StringBuilder();

        if (penDirList != null && !penDirList.isEmpty()) {
            String vals = penDirList.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
            where.append(" AND chan_pen_dir IN (").append(vals).append(")");
        }
        if (trendList != null && !trendList.isEmpty()) {
            String vals = trendList.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
            where.append(" AND chan_trend IN (").append(vals).append(")");
        }
        if (buySellList != null && !buySellList.isEmpty()) {
            String vals = buySellList.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
            where.append(" AND chan_buy_sell IN (").append(vals).append(")");
        }
        if (hubPosMin != null) {
            where.append(" AND chan_hub_pos >= ").append(hubPosMin);
        }
        if (hubPosMax != null) {
            where.append(" AND chan_hub_pos <= ").append(hubPosMax);
        }
        if (penCountMin != null) {
            where.append(" AND chan_pen_count >= ").append(penCountMin);
        }
        if (penCountMax != null) {
            where.append(" AND chan_pen_count <= ").append(penCountMax);
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (d.symbol LIKE '%").append(keyword.replace("'", "''")).append("%' OR si.name LIKE '%").append(keyword.replace("'", "''")).append("%')");
        }

        // 5. 拼接完整 SQL（用 StringBuilder 避免模板占位符混淆）
        String firstFactorCode = factorCodes.getFirst();

        // 构建 pivot 列名（小写）列表，用于 GROUP BY
        List<String> colAliases = factorCodes.stream().map(String::toLowerCase).toList();

        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("SELECT d.symbol AS ts_code, si.name, d.calc_date");
        for (String alias : colAliases) {
            sqlBuilder.append(", d.").append(alias);
        }
        sqlBuilder.append("\nFROM (\n");
        sqlBuilder.append("  SELECT symbol, calc_date");
        for (String code : factorCodes) {
            sqlBuilder.append(", maxIf(toFloat64(factor_val), factor_code = '")
                    .append(code.replace("'", "''"))
                    .append("') AS ").append(code.toLowerCase());
        }
        sqlBuilder.append(", max(calc_date) AS latest_date\n");
        sqlBuilder.append("  FROM stock.factor_value FINAL\n");
        sqlBuilder.append("  WHERE factor_code IN (").append(factorInClause).append(")\n");
        sqlBuilder.append("    AND calc_date = (\n");
        sqlBuilder.append("      SELECT max(calc_date)\n");
        sqlBuilder.append("      FROM stock.factor_value FINAL\n");
        sqlBuilder.append("      WHERE factor_code = '").append(firstFactorCode.replace("'", "''")).append("'\n");
        sqlBuilder.append("    )\n");
        sqlBuilder.append("  GROUP BY symbol, calc_date\n");
        sqlBuilder.append("  HAVING ");
        for (int i = 0; i < colAliases.size(); i++) {
            if (i > 0) sqlBuilder.append(" AND ");
            sqlBuilder.append(colAliases.get(i)).append(" IS NOT NULL");
        }
        sqlBuilder.append("\n");
        sqlBuilder.append(") d\n");
        sqlBuilder.append("LEFT JOIN stock_info si ON si.symbol = d.symbol\n");
        sqlBuilder.append("WHERE 1=1").append(where).append("\n");
        sqlBuilder.append("ORDER BY d.symbol\n");

        String sql = sqlBuilder.toString();

        log.info("[ClickHouse] chanScreen dynamic SQL: factors={}, where={}", factorCodes.size(), where);

        // 6. 执行查询（动态列映射）
        colAliases = factorCodes.stream()
                .map(String::toLowerCase)
                .toList();

        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("ts_code", rs.getString("ts_code"));
                row.put("name", rs.getString("name"));
                row.put("calc_date", rs.getDate("calc_date"));
                // 动态列：所有因子值
                for (String col : colAliases) {
                    row.put(col, rs.getObject(col));
                }
                result.add(row);
            }
            log.debug("[ClickHouse] chanScreen dynamic query hit {} rows", result.size());
            return result;
        } catch (Exception e) {
            log.error("[ClickHouse] chanScreen dynamic query failed: {}", e.getMessage(), e);
            throw new RuntimeException("ClickHouse 缠论筛选失败: " + e.getMessage(), e);
        }
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

    /**
     * 查询因子在日期范围内有数据的日期列表（仅 DISTINCT calc_date，轻量查询）
     */
    public java.util.List<java.time.LocalDate> findDistinctDatesByFactorCode(
            String factorCode, java.time.LocalDate startDate, java.time.LocalDate endDate) {
        if (!clickHouseConfig.isEnabled()) {
            return List.of();
        }
        String sql = """
                SELECT DISTINCT calc_date
                FROM stock.factor_value FINAL
                WHERE factor_code = ? AND calc_date >= ? AND calc_date <= ?
                ORDER BY calc_date
                """;
        java.util.List<java.time.LocalDate> result = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, factorCode);
            stmt.setString(2, startDate.toString());
            stmt.setString(3, endDate.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    java.sql.Date d = rs.getDate("calc_date");
                    if (d != null) result.add(d.toLocalDate());
                }
            }
        } catch (Exception e) {
            log.warn("[ClickHouse] findDistinctDates 查询失败: {}", e.getMessage());
        }
        return result;
    }

    // ==================== 写入方法（双写） ====================

    /**
     * 批量写入因子值到 ClickHouse
     */
    public void batchUpsertToCH(java.util.List<com.quant.platform.factor.domain.FactorValue> values) {
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

            for (com.quant.platform.factor.domain.FactorValue fv : values) {
                stmt.setLong(1, fv.getId() != null ? fv.getId() : 0);
                stmt.setString(2, fv.getFactorCode());
                stmt.setString(3, fv.getSymbol());
                stmt.setString(4, fv.getCalcDate().toString());
                setBigDecimal(stmt, 5, fv.getFactorVal());
                setBigDecimal(stmt, 6, fv.getRankValue());
                setBigDecimal(stmt, 7, fv.getZScore());
                setLocalDateTime(stmt, 8, fv.getCreatedAt());
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

    private java.util.List<com.quant.platform.factor.domain.FactorValue> queryByFactorCodeAndDateFromCH(
            String factorCode, java.time.LocalDate calcDate) {
        String sql = """
                SELECT id, factor_code, symbol, calc_date, factor_val, rank_value, z_score, created_at
                FROM stock.factor_value FINAL
                WHERE factor_code = ? AND calc_date = ?
                ORDER BY symbol
                """;

        java.util.List<com.quant.platform.factor.domain.FactorValue> result = new ArrayList<>();
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

    private java.util.List<com.quant.platform.factor.domain.FactorValue> queryByFactorCodeAndDateRangeFromCH(
            String factorCode, java.time.LocalDate startDate, java.time.LocalDate endDate) {
        String sql = """
                SELECT id, factor_code, symbol, calc_date, factor_val, rank_value, z_score, created_at
                FROM stock.factor_value FINAL
                WHERE factor_code = ? AND calc_date >= ? AND calc_date <= ?
                ORDER BY calc_date, symbol
                """;

        java.util.List<com.quant.platform.factor.domain.FactorValue> result = new ArrayList<>();
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
                FROM stock.factor_value FINAL
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
        String sql = "SELECT count() FROM stock.factor_value FINAL";
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

    private com.quant.platform.factor.domain.FactorValue convertResultSet(ResultSet rs) throws SQLException {
        com.quant.platform.factor.domain.FactorValue fv = new com.quant.platform.factor.domain.FactorValue();
        fv.setId(rs.getLong("id"));
        fv.setFactorCode(rs.getString("factor_code"));
        fv.setSymbol(rs.getString("symbol"));

        java.sql.Date date = rs.getDate("calc_date");
        fv.setCalcDate(date != null ? date.toLocalDate() : null);

        double factorVal = rs.getDouble("factor_val");
        fv.setFactorVal(rs.wasNull() ? null : java.math.BigDecimal.valueOf(factorVal));

        double rankValue = rs.getDouble("rank_value");
        fv.setRankValue(rs.wasNull() ? null : java.math.BigDecimal.valueOf(rankValue));

        double zScore = rs.getDouble("z_score");
        fv.setZScore(rs.wasNull() ? null : java.math.BigDecimal.valueOf(zScore));

        Timestamp createdAt = rs.getTimestamp("created_at");
        fv.setCreatedAt(createdAt != null ? createdAt.toLocalDateTime() : null);

        return fv;
    }

    private void setBigDecimal(PreparedStatement stmt, int index, java.math.BigDecimal value) throws SQLException {
        if (value != null) {
            stmt.setDouble(index, value.doubleValue());
        } else {
            stmt.setNull(index, Types.DOUBLE);
        }
    }

    private void setLocalDateTime(PreparedStatement stmt, int index, java.time.LocalDateTime value) throws SQLException {
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
