package com.quant.platform.stock.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quant.platform.stock.entity.StockDaily;

import java.math.BigDecimal;
import java.sql.*;

/**
 * 股票日线查询的无状态支撑函数集合。
 * 覆盖：ResultSet→实体映射、PreparedStatement 参数绑定、
 * ORDER BY 片段构建、代码规范化、QueryWrapper 前缀条件。
 * 由 ClickHouseStockService 拆分而来，方法体逐字搬运，行为零变化。
 */
public final class StockDailySqlSupport {

    private StockDailySqlSupport() {}

    /**
     * 将前缀数组应用到 QueryWrapper
     */
    public static void applyPrefixesToWrapper(LambdaQueryWrapper<StockDaily> wrapper, String... prefixes) {
        if (prefixes == null || prefixes.length == 0) return;
        
        wrapper.and(w -> {
            w.likeRight(StockDaily::getCode, prefixes[0]);
            for (int i = 1; i < prefixes.length; i++) {
                w.or().likeRight(StockDaily::getCode, prefixes[i]);
            }
        });
    }

    public static String buildOrderByClause(String sortField, String sortOrder) {
        if (sortField == null || sortField.isEmpty()) {
            return "ORDER BY change_percent DESC";
        }
        boolean asc = !"desc".equalsIgnoreCase(sortOrder);
        String col = switch (sortField) {
            case "pctChg" -> "change_percent";
            case "amount" -> "amount";
            case "vol" -> "volume";
            case "close" -> "close_price";
            case "turnoverRate" -> "turnover_rate";
            default -> "code";
        };
        return "ORDER BY " + col + (asc ? " ASC" : " DESC");
    }

    /**
     * 转换 ResultSet 为 StockDaily
     */
    public static StockDaily convertResultSet(ResultSet rs) throws SQLException {
        StockDaily daily = new StockDaily();
        daily.setCode(rs.getString("code"));
        daily.setTradeDate(rs.getDate("trade_date").toLocalDate());

        String name = rs.getString("name");
        daily.setName(name);

        setDouble(rs, "open_price", daily::setOpenPrice);
        setDouble(rs, "close_price", daily::setClosePrice);
        setDouble(rs, "high_price", daily::setHighPrice);
        setDouble(rs, "low_price", daily::setLowPrice);
        setDouble(rs, "pre_close", daily::setPreClose);
        setLong(rs, "volume", daily::setVolume);
        setDouble(rs, "amount", daily::setAmount);
        setDouble(rs, "change_percent", daily::setChangePercent);
        setDouble(rs, "change_amount", daily::setChangeAmount);
        setDouble(rs, "turnover_rate", daily::setTurnoverRate);
        setDouble(rs, "pe_ttm", daily::setPeTtm);
        setDouble(rs, "pb", daily::setPb);

        return daily;
    }

    public static void setDouble(ResultSet rs, String col, java.util.function.Consumer<BigDecimal> setter) throws SQLException {
        double val = rs.getDouble(col);
        if (!rs.wasNull()) {
            setter.accept(BigDecimal.valueOf(val));
        }
    }

    public static void setLong(ResultSet rs, String col, java.util.function.Consumer<Long> setter) throws SQLException {
        long val = rs.getLong(col);
        if (!rs.wasNull()) {
            setter.accept(val);
        }
    }

    public static void setParam(PreparedStatement stmt, int index, BigDecimal value) throws SQLException {
        if (value != null) {
            stmt.setDouble(index, value.doubleValue());
        } else {
            stmt.setNull(index, Types.DOUBLE);
        }
    }

    public static void setLongParam(PreparedStatement stmt, int index, Long value) throws SQLException {
        if (value != null) {
            stmt.setLong(index, value);
        } else {
            stmt.setNull(index, Types.BIGINT);
        }
    }

    /**
     * 将 A 股代码转为 CH 存储格式（无前缀无后缀，如 600519）
     */
    public static String normalizeCodeForCH(String code) {
        if (code == null) return null;
        String c = code.trim().toLowerCase();
        // 去掉 sh/sz/bj 前缀
        if (c.matches("^(sh|sz|bj)\\d+")) {
            return c.substring(2);
        }
        // 去掉 .SH/.SZ/.BJ 后缀
        if (c.matches("^\\d+\\.(sh|sz|bj)$")) {
            return c.substring(0, c.indexOf('.'));
        }
        return c;
    }
}
