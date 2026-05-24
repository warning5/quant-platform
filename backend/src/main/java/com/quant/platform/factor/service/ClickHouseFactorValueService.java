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
            log.warn("[ClickHouse] CH disabled by config, returning empty. factorCode={}, date={}, jdbcUrl={}",
                    factorCode, calcDate, clickHouseConfig.getJdbcUrl());
            return List.of();
        }

        log.info("[ClickHouse] Querying CH: factorCode={}, date={}, url={}",
                factorCode, calcDate, clickHouseConfig.getJdbcUrl());
        try {
            java.util.List<com.quant.platform.factor.domain.FactorValue> result = queryByFactorCodeAndDateFromCH(factorCode, calcDate);
            if (!result.isEmpty()) {
                log.info("[ClickHouse] 因子值命中: {} {} size={}", factorCode, calcDate, result.size());
                return result;
            }
            log.warn("[ClickHouse] 因子值查询返回空: code={}, date={}", factorCode, calcDate);
        } catch (Exception e) {
            log.error("[ClickHouse] 因子值查询异常: code={}, date={}, error={}",
                    factorCode, calcDate, e.toString());
        }
        return List.of();
    }

    /**
     * 根据因子代码和日期范围查询因子值（CH 优先）
     */
    public java.util.List<com.quant.platform.factor.domain.FactorValue> findByFactorCodeAndDateRange(
            String factorCode, java.time.LocalDate startDate, java.time.LocalDate endDate) {
        if (!clickHouseConfig.isEnabled()) {
            log.debug("[ClickHouse] 因子值查询已禁用, factorCode={}", factorCode);
            return List.of(); // 回退到 Mapper
        }

        try {
            java.util.List<com.quant.platform.factor.domain.FactorValue> result = queryByFactorCodeAndDateRangeFromCH(factorCode, startDate, endDate);
            if (!result.isEmpty()) {
                log.debug("[ClickHouse] 因子值范围查询命中: {} {}~{}", factorCode, startDate, endDate);
                return result;
            }
            log.warn("[ClickHouse] 因子值范围查询返回空: code={}, 范围={}~{}", factorCode, startDate, endDate);
        } catch (Exception e) {
            log.error("[ClickHouse] 因子值范围查询异常: code={}, 范围={}~{}，错误: {}", 
                    factorCode, startDate, endDate, e.getMessage());
            // 重新抛出，让上游感知 CH 不可用，不要静默吞异常导致假阴性
            throw new RuntimeException("ClickHouse 查询因子值失败: " + factorCode, e);
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

    // ==================== 缺失因子查询 ====================

    /**
     * 查询指定日期范围内有数据的因子代码列表
     * 用于对比全量因子，找出缺失日期的因子
     */
    public java.util.List<String> findFactorsWithDates(String date) {
        if (!clickHouseConfig.isEnabled()) {
            return List.of();
        }
        String sql = """
                SELECT DISTINCT factor_code
                FROM stock.factor_value FINAL
                WHERE calc_date = ?
                """;
        java.util.List<String> result = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, date);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getString("factor_code"));
                }
            }
        } catch (Exception e) {
            log.warn("[ClickHouse] findFactorsWithDates 查询失败: {}", e.getMessage());
        }
        return result;
    }

    // ==================== 写入方法（双写） ====================

    /**
     * 批量写入因子值到 ClickHouse（多行 VALUES INSERT，比 JDBC batch 快 3-5x）
     * 每批 5000 行，单条 SQL 一次网络往返
     */
    public void batchUpsertToCH(java.util.List<com.quant.platform.factor.domain.FactorValue> values) {
        if (!clickHouseConfig.isEnabled() || values == null || values.isEmpty()) {
            return;
        }

        final int BATCH_SIZE = 5000;
        try (Connection conn = getConnection()) {
            for (int i = 0; i < values.size(); i += BATCH_SIZE) {
                int end = Math.min(i + BATCH_SIZE, values.size());
                StringBuilder sql = new StringBuilder(
                        "INSERT INTO stock.factor_value " +
                        "(factor_code, symbol, calc_date, factor_val, rank_value, z_score, created_at, update_time) VALUES ");
                for (int j = i; j < end; j++) {
                    if (j > i) sql.append(',');
                    com.quant.platform.factor.domain.FactorValue fv = values.get(j);
                    sql.append("('")
                       .append(escapeSql(fv.getFactorCode())).append("','")
                       .append(escapeSql(fv.getSymbol())).append("','")
                       .append(fv.getCalcDate()).append("',")
                       .append(toSqlNullable(fv.getFactorVal())).append(',')
                       .append(toSqlNullable(fv.getRankValue())).append(',')
                       .append(toSqlNullable(fv.getZScore())).append(',')
                       .append("now(),now())");
                }
                try (java.sql.Statement stmt = conn.createStatement()) {
                    stmt.executeUpdate(sql.toString());
                }
            }
            log.debug("[ClickHouse] 批量写入 {} 条因子值", values.size());
        } catch (Exception e) {
            log.error("[ClickHouse] 批量写入因子值失败: {}", e.getMessage(), e);
            throw new RuntimeException("ClickHouse 批量写入失败: " + e.getMessage(), e);
        }
    }

    /** 转义单引号 */
    private String escapeSql(String s) {
        if (s == null) return "";
        return s.replace("'", "\\'");
    }

    /** BigDecimal -> SQL 数值（null -> NULL） */
    private String toSqlNullable(java.math.BigDecimal v) {
        return v == null ? "NULL" : v.toPlainString();
    }

    // ==================== ClickHouse 查询实现 ====================

    private java.util.List<com.quant.platform.factor.domain.FactorValue> queryByFactorCodeAndDateFromCH(
            String factorCode, java.time.LocalDate calcDate) {
        // CH JDBC v0.6.3 对 PreparedStatement 参数绑定处理有问题，改为字符串拼接
        String sql = String.format("""
                SELECT id, factor_code, symbol, calc_date, factor_val, rank_value, z_score, created_at
                FROM stock.factor_value FINAL
                WHERE factor_code = '%s' AND calc_date = '%s'
                ORDER BY symbol
                """, factorCode.replace("'", "''"), calcDate);

        java.util.List<com.quant.platform.factor.domain.FactorValue> result = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                result.add(convertResultSet(rs));
            }
        } catch (Exception e) {
            log.warn("[ClickHouse] findByFactorCodeAndDate 查询失败: {}", e.getMessage());
            throw new RuntimeException("ClickHouse 查询失败", e);
        }
        return result;
    }

    private java.util.List<com.quant.platform.factor.domain.FactorValue> queryByFactorCodeAndDateRangeFromCH(
            String factorCode, java.time.LocalDate startDate, java.time.LocalDate endDate) {
        // CH JDBC v0.6.3 对 PreparedStatement 参数绑定处理有问题，改为字符串拼接
        String sql = String.format("""
                SELECT id, factor_code, symbol, calc_date, factor_val, rank_value, z_score, created_at
                FROM stock.factor_value FINAL
                WHERE factor_code = '%s' AND calc_date >= '%s' AND calc_date <= '%s'
                ORDER BY calc_date, symbol
                """, factorCode.replace("'", "''"), startDate, endDate);

        java.util.List<com.quant.platform.factor.domain.FactorValue> result = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                result.add(convertResultSet(rs));
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

    /**
     * 按因子代码删除 ClickHouse 因子值（异步ALTER TABLE DELETE）
     * @return 删除前的记录数（用于前端提示）
     */
    public long deleteByFactorCode(String factorCode) {
        if (!clickHouseConfig.isEnabled()) {
            return 0L;
        }
        // 先查删除前数量
        long countBefore = 0L;
        String countSql = "SELECT count() FROM stock.factor_value FINAL WHERE factor_code = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(countSql)) {
            stmt.setString(1, factorCode);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    countBefore = rs.getLong(1);
                }
            }
        } catch (Exception e) {
            log.warn("[ClickHouse] deleteByFactorCode 计数失败: {}", e.getMessage());
        }

        // 异步删除（ALTER TABLE DELETE WHERE）
        String deleteSql = "ALTER TABLE stock.factor_value DELETE WHERE factor_code = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(deleteSql)) {
            stmt.setString(1, factorCode);
            stmt.executeUpdate();
            log.info("[ClickHouse] 已提交异步删除任务: factor_code={}, 删除前数量={}", factorCode, countBefore);
        } catch (Exception e) {
            log.error("[ClickHouse] deleteByFactorCode 删除失败: {}", e.getMessage(), e);
            throw new RuntimeException("ClickHouse 删除失败: " + e.getMessage(), e);
        }
        return countBefore;
    }

    /**
     * 按因子代码+日期范围删除 ClickHouse 因子值（异步ALTER TABLE DELETE）
     * 用于重算前清除旧数据，避免重复写入导致 ReplacingMergeTree 物理行膨胀。
     *
     * @return 删除前的记录数
     */
    public long deleteByFactorCodeAndDateRange(String factorCode, String startDate, String endDate) {
        if (!clickHouseConfig.isEnabled()) {
            return 0L;
        }
        // 先查删除前数量
        long countBefore = 0L;
        String countSql = "SELECT count() FROM stock.factor_value FINAL WHERE factor_code = ? AND calc_date >= ? AND calc_date <= ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(countSql)) {
            stmt.setString(1, factorCode);
            stmt.setString(2, startDate);
            stmt.setString(3, endDate);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    countBefore = rs.getLong(1);
                }
            }
        } catch (Exception e) {
            log.warn("[ClickHouse] deleteByFactorCodeAndDateRange 计数失败: {}", e.getMessage());
        }

        if (countBefore == 0) {
            log.info("[ClickHouse] deleteByFactorCodeAndDateRange: {} {}~{} 无数据，跳过删除", factorCode, startDate, endDate);
            return 0L;
        }

        // 异步删除（ALTER TABLE DELETE WHERE）
        String deleteSql = "ALTER TABLE stock.factor_value DELETE WHERE factor_code = ? AND calc_date >= ? AND calc_date <= ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(deleteSql)) {
            stmt.setString(1, factorCode);
            stmt.setString(2, startDate);
            stmt.setString(3, endDate);
            stmt.executeUpdate();
            log.info("[ClickHouse] 已提交异步删除任务: factor_code={}, {}~{}, 删除前数量={}", factorCode, startDate, endDate, countBefore);
        } catch (Exception e) {
            log.error("[ClickHouse] deleteByFactorCodeAndDateRange 删除失败: {}", e.getMessage(), e);
            throw new RuntimeException("ClickHouse 删除失败: " + e.getMessage(), e);
        }

        // 等待 mutation 完成后再返回（避免新写入的数据被 mutation 吃掉）
        waitForMutations();

        return countBefore;
    }

    /**
     * 等待 ClickHouse 所有 mutations 完成
     * 通过轮询 system.mutations 表，直到没有活跃的 mutation
     */
    private void waitForMutations() {
        try {
            int maxWait = 60; // 最多等60秒
            int waited = 0;
            while (waited < maxWait) {
                String checkSql = "SELECT count() FROM system.mutations WHERE is_done = 0 AND table = 'factor_value' AND database = 'stock'";
                try (Connection conn = getConnection();
                     PreparedStatement stmt = conn.prepareStatement(checkSql);
                     ResultSet rs = stmt.executeQuery()) {
                    if (rs.next() && rs.getLong(1) == 0) {
                        log.info("[ClickHouse] mutations 已全部完成");
                        return;
                    }
                }
                Thread.sleep(1000);
                waited++;
            }
            log.warn("[ClickHouse] 等待 mutations 超时（{}秒），继续执行", maxWait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("[ClickHouse] 检查 mutations 状态失败: {}", e.getMessage());
        }
    }

    // ==================== 归一化（SQL 窗口函数一次性计算） ====================

    /**
     * 批量归一化：利用 CH 窗口函数一次性计算所有日期的 z_score 和 rank_value，
     * 然后 INSERT 覆盖（ReplacingMergeTree 按最新 update_time 去重）。
     *
     * 优化：将原来 178万次 UPDATE（逐日逐行）替换为 2次SQL（算+写），提速 10x+
     *
     * @return 处理的行数
     */
    public long batchNormalize(String factorCode, java.util.List<java.time.LocalDate> dates) {
        if (!clickHouseConfig.isEnabled() || dates == null || dates.isEmpty()) {
            return 0L;
        }

        if (dates.size() == 1) {
            // 单日期走内存计算路径（避免小数据量也走复杂SQL）
            return normalizeSingleDate(factorCode, dates.getFirst());
        }

        String startDate = dates.getFirst().toString();
        String endDate = dates.get(dates.size() - 1).toString();

        log.info("[ClickHouse] batchNormalize: {} | {} ~ {} ({} dates)", factorCode, startDate, endDate, dates.size());

        // Step 1: 用窗口函数一次性计算所有日期的 z_score 和 rank_value
        // 所有聚合函数必须带 OVER (PARTITION BY calc_date)，否则 CH 报 NOT_AN_AGGREGATE
        String sql = """
                SELECT factor_code, symbol, calc_date, factor_val,
                       if(stddevPop(factor_val) OVER (PARTITION BY calc_date) = 0, 0,
                           (factor_val - avg(factor_val) OVER (PARTITION BY calc_date))
                           / stddevPop(factor_val) OVER (PARTITION BY calc_date)) AS z_score,
                       if(count(*) OVER (PARTITION BY calc_date) <= 1, 0.5,
                           (rank() OVER (PARTITION BY calc_date ORDER BY factor_val) - 1) * 1.0
                           / (count(*) OVER (PARTITION BY calc_date) - 1)) AS rank_value
                FROM stock.factor_value FINAL
                WHERE factor_code = ? AND calc_date >= ? AND calc_date <= ?
                ORDER BY calc_date, symbol
                """;

        java.util.List<Object[]> rows = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, factorCode);
            stmt.setString(2, startDate);
            stmt.setString(3, endDate);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    rows.add(new Object[]{
                            rs.getString("factor_code"),
                            rs.getString("symbol"),
                            rs.getString("calc_date"),
                            rs.getDouble("factor_val"),
                            rs.getDouble("z_score"),
                            rs.getDouble("rank_value")
                    });
                }
            }
        } catch (Exception e) {
            log.error("[ClickHouse] batchNormalize 查询失败: {}", e.getMessage(), e);
            throw new RuntimeException("ClickHouse 归一化查询失败: " + e.getMessage(), e);
        }

        if (rows.isEmpty()) {
            log.info("[ClickHouse] batchNormalize: 无数据，跳过");
            return 0L;
        }

        // Step 2: 批量 INSERT 覆盖（ReplacingMergeTree 自动去重，保留最新 update_time 行）
        String insertSql = """
                INSERT INTO stock.factor_value
                (factor_code, symbol, calc_date, factor_val, z_score, rank_value, created_at, update_time)
                VALUES (?, ?, ?, ?, ?, ?, now(), now())
                """;
        final int BATCH_SIZE = 10000;

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(insertSql)) {

            for (int i = 0; i < rows.size(); i++) {
                Object[] row = rows.get(i);
                stmt.setString(1, (String) row[0]); // factor_code
                stmt.setString(2, (String) row[1]); // symbol
                stmt.setString(3, (String) row[2]); // calc_date
                stmt.setDouble(4, (Double) row[3]); // factor_val
                stmt.setDouble(5, (Double) row[4]); // z_score
                stmt.setDouble(6, (Double) row[5]); // rank_value
                stmt.addBatch();

                if ((i + 1) % BATCH_SIZE == 0) {
                    stmt.executeBatch();
                    log.info("[ClickHouse] batchNormalize: 已写入 {}/{}", i + 1, rows.size());
                }
            }
            stmt.executeBatch();
        } catch (Exception e) {
            log.error("[ClickHouse] batchNormalize 写入失败: {}", e.getMessage(), e);
            throw new RuntimeException("ClickHouse 归一化写入失败: " + e.getMessage(), e);
        }

        log.info("[ClickHouse] batchNormalize 完成: {} 行", rows.size());
        return rows.size();
    }

    /**
     * 单日期内存归一化（小数据量场景）
     */
    private long normalizeSingleDate(String factorCode, java.time.LocalDate date) {
        String sql = """
                SELECT factor_code, symbol, calc_date, factor_val,
                       if(stddevPop(factor_val) OVER () = 0, 0,
                           (factor_val - avg(factor_val) OVER ())
                           / stddevPop(factor_val) OVER ()) AS z_score,
                       if(count(*) OVER () <= 1, 0.5,
                           (rank() OVER (ORDER BY factor_val) - 1) * 1.0
                           / (count(*) OVER () - 1)) AS rank_value
                FROM stock.factor_value FINAL
                WHERE factor_code = ? AND calc_date = ?
                ORDER BY symbol
                """;

        java.util.List<Object[]> rows = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, factorCode);
            stmt.setString(2, date.toString());

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    rows.add(new Object[]{
                            rs.getString("factor_code"),
                            rs.getString("symbol"),
                            rs.getString("calc_date"),
                            rs.getDouble("factor_val"),
                            rs.getDouble("z_score"),
                            rs.getDouble("rank_value")
                    });
                }
            }
        } catch (Exception e) {
            log.error("[ClickHouse] normalizeSingleDate 失败: {}", e.getMessage(), e);
            throw new RuntimeException("ClickHouse 单日归一化失败: " + e.getMessage(), e);
        }

        if (rows.isEmpty()) return 0L;

        String insertSql = """
                INSERT INTO stock.factor_value
                (factor_code, symbol, calc_date, factor_val, z_score, rank_value, created_at, update_time)
                VALUES (?, ?, ?, ?, ?, ?, now(), now())
                """;

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(insertSql)) {
            for (Object[] row : rows) {
                stmt.setString(1, (String) row[0]);
                stmt.setString(2, (String) row[1]);
                stmt.setString(3, (String) row[2]);
                stmt.setDouble(4, (Double) row[3]);
                stmt.setDouble(5, (Double) row[4]);
                stmt.setDouble(6, (Double) row[5]);
                stmt.addBatch();
            }
            stmt.executeBatch();
        } catch (Exception e) {
            log.error("[ClickHouse] normalizeSingleDate 写入失败: {}", e.getMessage(), e);
            throw new RuntimeException("ClickHouse 单日归一化写入失败: " + e.getMessage(), e);
        }

        return rows.size();
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

    /**
     * 通过 HTTP POST JSONEachRow 批量写入因子值（绕过 JDBC，速度更快）
     * 147万条约 10-20 秒，比 JDBC VALUES INSERT 快 10x+
     */
    public void httpBatchInsert(List<com.quant.platform.factor.domain.FactorValue> values) {
        if (!clickHouseConfig.isEnabled() || values == null || values.isEmpty()) return;
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        java.time.format.DateTimeFormatter chDtFmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        // 分块：每块 50 万行，避免 HTTP body 过大
        int chunkSize = 500_000;
        for (int i = 0; i < values.size(); i += chunkSize) {
            int end = Math.min(i + chunkSize, values.size());
            List<com.quant.platform.factor.domain.FactorValue> chunk = values.subList(i, end);
            StringBuilder body = new StringBuilder(chunk.size() * 120);
            for (com.quant.platform.factor.domain.FactorValue fv : chunk) {
                if (body.length() > 0) body.append('\n');
                java.time.LocalDateTime created = fv.getCreatedAt() != null ? fv.getCreatedAt() : now;
                body.append('{')
                    .append("\"factor_code\":\"").append(escapeJson(fv.getFactorCode())).append("\",")
                    .append("\"symbol\":\"").append(escapeJson(fv.getSymbol())).append("\",")
                    .append("\"calc_date\":\"").append(fv.getCalcDate()).append("\",")
                    .append("\"factor_val\":").append(toJsonVal(fv.getFactorVal())).append(",")
                    .append("\"rank_value\":").append(toJsonVal(fv.getRankValue())).append(",")
                    .append("\"z_score\":").append(toJsonVal(fv.getZScore())).append(",")
                    .append("\"created_at\":\"").append(created.format(chDtFmt)).append("\",")
                    .append("\"update_time\":\"").append(now.format(chDtFmt)).append("\"")
                    .append('}');
            }
            httpPost("INSERT INTO stock.factor_value FORMAT JSONEachRow", body.toString());
            log.debug("[ClickHouse] HTTP批量写入 {} 条（{}/{}）", end - i, end, values.size());
        }
        log.info("[ClickHouse] HTTP批量写入完成，共 {} 条", values.size());
    }

    /**
     * 对 factor_value 表执行 OPTIMIZE TABLE FINAL，合并 ReplacingMergeTree 重复行。
     * 归一化写入新行后调用，确保查询能立即读到 z_score/rank_value 而非旧行 NULL。
     */
    public void optimizeFactorValue() {
        // 用 HTTP POST body 方式发送 DDL，绕过 readonly=2 的 URL 参数限制
        try {
            httpPost("OPTIMIZE TABLE stock.factor_value FINAL", "");
        } catch (Exception e) {
            throw new RuntimeException("OPTIMIZE factor_value 失败: " + e.getMessage(), e);
        }
    }

    /** HTTP POST 到 ClickHouse */
    private void httpPost(String query, String body) {
        try {
            String url = String.format("http://%s:%d/?user=%s&password=%s&query=%s",
                    clickHouseConfig.getHost(), clickHouseConfig.getPort(),
                    clickHouseConfig.getUsername(), clickHouseConfig.getPassword(),
                    java.net.URLEncoder.encode(query, "UTF-8"));
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                    .timeout(java.time.Duration.ofMinutes(5))
                    .build();
            java.net.http.HttpResponse<String> resp = java.net.http.HttpClient.newHttpClient()
                    .send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new RuntimeException("CH HTTP " + resp.statusCode() + ": " + resp.body());
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("CH HTTP 请求失败: " + e.getMessage(), e);
        }
    }

    /** JSON 字符串转义 */
    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 批量获取多因子的日度截面中位数（CH 侧聚合，避免拉取全量数据）
     * @return Map<factorCode, Map<日期, 中位数>>
     */
    public Map<String, Map<java.time.LocalDate, Double>> getDailyMedians(
            List<String> factorCodes, java.time.LocalDate startDate, java.time.LocalDate endDate) {
        if (!clickHouseConfig.isEnabled() || factorCodes == null || factorCodes.isEmpty()) {
            return Map.of();
        }

        String placeholders = factorCodes.stream()
                .map(c -> "'" + c.replace("'", "''") + "'")
                .collect(java.util.stream.Collectors.joining(","));

        String sql = String.format("""
                SELECT factor_code, calc_date, quantileExact(0.5)(factor_val) AS median_val
                FROM stock.factor_value FINAL
                WHERE factor_code IN (%s)
                  AND calc_date >= '%s'
                  AND calc_date <= '%s'
                  AND factor_val IS NOT NULL
                  AND factor_val = factor_val
                GROUP BY factor_code, calc_date
                ORDER BY factor_code, calc_date
                """, placeholders, startDate.toString(), endDate.toString());

        Map<String, Map<java.time.LocalDate, Double>> result = new java.util.LinkedHashMap<>();
        try (Connection conn = getConnection();
             java.sql.Statement stmt = conn.createStatement();
             java.sql.ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                String fc = rs.getString("factor_code");
                java.time.LocalDate date = rs.getDate("calc_date").toLocalDate();
                double medianVal = rs.getDouble("median_val");

                result.computeIfAbsent(fc, k -> new java.util.LinkedHashMap<>())
                        .put(date, medianVal);
            }
            log.info("[ClickHouse] 批量日度中位数查询完成: {} factors, {} dates total",
                    result.size(), 
                    result.values().stream().mapToInt(Map::size).sum());
        } catch (Exception e) {
            log.error("[ClickHouse] 批量日度中位数查询失败: factors={}, error={}", factorCodes, e.getMessage());
            throw new RuntimeException("ClickHouse 批量日度中位数查询失败", e);
        }
        return result;
    }

    /**
     * 批量获取多因子的日度截面 rank_value 中位数（CH 侧聚合）
     * 用于权重优化等需要因子截面排名的场景
     * @return Map<factorCode, Map<日期, rank_value中位数>>
     */
    public Map<String, Map<java.time.LocalDate, Double>> getDailyRankMedians(
            List<String> factorCodes, java.time.LocalDate startDate, java.time.LocalDate endDate) {
        if (!clickHouseConfig.isEnabled() || factorCodes == null || factorCodes.isEmpty()) {
            return Map.of();
        }

        String placeholders = factorCodes.stream()
                .map(c -> "'" + c.replace("'", "''") + "'")
                .collect(java.util.stream.Collectors.joining(","));

        String sql = String.format("""
                SELECT factor_code, calc_date, quantileExact(0.5)(rank_value) AS median_val
                FROM stock.factor_value FINAL
                WHERE factor_code IN (%s)
                  AND calc_date >= '%s'
                  AND calc_date <= '%s'
                  AND rank_value IS NOT NULL
                  AND rank_value = rank_value
                GROUP BY factor_code, calc_date
                ORDER BY factor_code, calc_date
                """, placeholders, startDate.toString(), endDate.toString());

        Map<String, Map<java.time.LocalDate, Double>> result = new java.util.LinkedHashMap<>();
        try (Connection conn = getConnection();
             java.sql.Statement stmt = conn.createStatement();
             java.sql.ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                String fc = rs.getString("factor_code");
                java.time.LocalDate date = rs.getDate("calc_date").toLocalDate();
                double medianVal = rs.getDouble("median_val");

                result.computeIfAbsent(fc, k -> new java.util.LinkedHashMap<>())
                        .put(date, medianVal);
            }
            log.info("[ClickHouse] 批量日度 rank 中位数查询完成: {} factors, {} dates total",
                    result.size(),
                    result.values().stream().mapToInt(Map::size).sum());
        } catch (Exception e) {
            log.error("[ClickHouse] 批量日度 rank 中位数查询失败: factors={}, error={}", factorCodes, e.getMessage());
            throw new RuntimeException("ClickHouse 批量日度 rank 中位数查询失败", e);
        }
        return result;
    }

    /**
     * 获取因子最新数据日期
     */
    public java.time.LocalDate getLatestDate(String factorCode) {
        if (!clickHouseConfig.isEnabled() || factorCode == null) return null;
        String sql = String.format(
                "SELECT max(calc_date) FROM stock.factor_value FINAL WHERE factor_code='%s'",
                factorCode.replace("'", "''"));
        try (Connection conn = getConnection();
             java.sql.Statement stmt = conn.createStatement();
             java.sql.ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                java.sql.Date d = rs.getDate(1);
                return d != null ? d.toLocalDate() : null;
            }
        } catch (Exception e) {
            log.warn("[ClickHouse] getLatestDate 查询失败: code={}, error={}", factorCode, e.getMessage());
        }
        return null;
    }

    /**
     * 获取因子有数据的所有股票代码
     */
    public java.util.Set<String> getDistinctSymbols(String factorCode) {
        if (!clickHouseConfig.isEnabled() || factorCode == null) return java.util.Set.of();
        String sql = String.format(
                "SELECT DISTINCT symbol FROM stock.factor_value FINAL WHERE factor_code='%s'",
                factorCode.replace("'", "''"));
        java.util.Set<String> symbols = new java.util.HashSet<>();
        try (Connection conn = getConnection();
             java.sql.Statement stmt = conn.createStatement();
             java.sql.ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                symbols.add(rs.getString("symbol"));
            }
        } catch (Exception e) {
            log.warn("[ClickHouse] getDistinctSymbols 查询失败: code={}, error={}", factorCode, e.getMessage());
        }
        return symbols;
    }

    /** BigDecimal -> JSON 数值（null -> null） */
    private String toJsonVal(java.math.BigDecimal v) {
        return v == null ? "null" : v.toPlainString();
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(clickHouseConfig.getJdbcUrl());
    }
}
