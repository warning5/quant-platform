package com.quant.platform.stock.service;

import static com.quant.platform.stock.service.StockDailySqlSupport.*;

import com.quant.platform.config.ClickHouseConfig;
import com.quant.platform.stock.entity.StockDaily;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.time.LocalDate;
import java.util.*;

/**
 * ClickHouse JDBC 底座（从 ClickHouseStockService 逐字搬出，no-behavior-change）。
 * 持有 clickHouseConfig，提供连接获取与通用查询/写入原语。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClickHouseJdbcClient {

    private final ClickHouseConfig clickHouseConfig;

    /**
     * 获取 ClickHouse 连接（从 HikariCP 连接池获取）
     */
    public Connection getConnection() throws SQLException {
        return clickHouseConfig.getConnection();
    }

    /**
     * 执行 ClickHouse 查询
     */
    public List<StockDaily> executeQuery(String sql, LocalDate startDate, LocalDate endDate) {
        List<StockDaily> result = new ArrayList<>();

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            int paramIndex = 1;
            if (sql.contains("code = ?")) {
                paramIndex = 1;
            }
            stmt.setString(paramIndex, startDate.toString());
            stmt.setString(paramIndex + 1, endDate.toString());

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(convertResultSet(rs));
                }
            }
        } catch (Exception e) {
            log.warn("[ClickHouse] 查询失败: {}", e.getMessage());
            throw new ClickHouseQueryException("ClickHouse 查询失败", e);
        }

        return result;
    }

    /**
     * 执行 ClickHouse 查询（带 code 参数）
     */
    public List<StockDaily> executeQuery(String sql, String code, LocalDate startDate, LocalDate endDate) {
        List<StockDaily> result = new ArrayList<>();

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, code);
            stmt.setString(2, startDate.toString());
            stmt.setString(3, endDate.toString());

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(convertResultSet(rs));
                }
            }
        } catch (Exception e) {
            log.warn("[ClickHouse] 查询失败: {}", e.getMessage());
            throw new ClickHouseQueryException("ClickHouse 查询失败", e);
        }

        return result;
    }

    /**
     * 执行 DDL/DML 语句（如 ALTER TABLE DELETE）
     */
    public void executeDdl(String sql) throws SQLException {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    /**
     * 执行参数化 DDL/DML 语句（如 ALTER TABLE DELETE WHERE ... IN (?, ?)）
     * @param sql  含 ? 占位符的 SQL
     * @param params 参数值数组
     */
    public void executeDdlWithParams(String sql, Object[] params) throws SQLException {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                stmt.setObject(i + 1, params[i]);
            }
            stmt.execute();
        }
    }

    /**
     * 通用查询（Statement 模式，避免 PreparedStatement 空结果问题）
     * 返回每行为 Map<String, Object> 的列表，key 为列名小写
     * 异常时抛出 ClickHouseQueryException（带 cause），由公开层统一捕获并回退 MySQL，
     * 不再静默吞异常返回空列表 —— 否则调用方无法区分"无数据"与"查询失败"
     */
    public List<Map<String, Object>> queryForList(String sql) {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            java.sql.ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= colCount; i++) {
                    row.put(meta.getColumnLabel(i).toLowerCase(), rs.getObject(i));
                }
                result.add(row);
            }
        } catch (Exception e) {
            log.warn("[ClickHouse] queryForList 失败: {}", e.getMessage(), e);
            throw new ClickHouseQueryException("ClickHouse 通用查询失败: " + sql, e);
        }
        return result;
    }

    /**
     * 通用标量查询（Statement 模式），返回单行单列，异常时抛出 ClickHouseQueryException（带 cause）
     */
    public String queryForString(String sql) {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) return rs.getString(1);
        } catch (Exception e) {
            log.warn("[ClickHouse] queryForString 失败: {}", e.getMessage(), e);
            throw new ClickHouseQueryException("ClickHouse 通用单值查询失败: " + sql, e);
        }
        return null;
    }
}
