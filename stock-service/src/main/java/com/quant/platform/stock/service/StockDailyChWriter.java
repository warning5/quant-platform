package com.quant.platform.stock.service;

import static com.quant.platform.stock.service.StockDailySqlSupport.*;

import com.quant.platform.config.ClickHouseConfig;
import com.quant.platform.stock.entity.StockDaily;
import com.quant.platform.stock.service.ClickHouseJdbcClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;

/**
 * ClickHouse 日线写入器（从 ClickHouseStockService 逐字搬出，no-behavior-change）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockDailyChWriter {

    private final ClickHouseConfig clickHouseConfig;
    private final ClickHouseJdbcClient chJdbcClient;

    private Connection getConnection() throws SQLException {
        return chJdbcClient.getConnection();
    }

    /**
     * 写入单条日线数据到 ClickHouse
     */
    public void writeStockDaily(StockDaily daily) {
        if (!clickHouseConfig.isEnabled()) return;

        String sql = """
                INSERT INTO stock_daily
                (code, trade_date, name, open_price, close_price, high_price, low_price,
                 pre_close, volume, amount, change_percent, change_amount,
                 turnover_rate, pe_ttm, pb)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, daily.getCode());
            stmt.setString(2, daily.getTradeDate().toString());
            stmt.setString(3, daily.getName());
            setParam(stmt, 4, daily.getOpenPrice());
            setParam(stmt, 5, daily.getClosePrice());
            setParam(stmt, 6, daily.getHighPrice());
            setParam(stmt, 7, daily.getLowPrice());
            setParam(stmt, 8, daily.getPreClose());
            setLongParam(stmt, 9, daily.getVolume());
            setParam(stmt, 10, daily.getAmount());
            setParam(stmt, 11, daily.getChangePercent());
            setParam(stmt, 12, daily.getChangeAmount());
            setParam(stmt, 13, daily.getTurnoverRate());
            setParam(stmt, 14, daily.getPeTtm());
            setParam(stmt, 15, daily.getPb());

            stmt.executeUpdate();
        } catch (Exception e) {
            log.warn("[ClickHouse] 写入失败: {}", e.getMessage());
        }
    }

    /**
     * 批量写入日线数据到 ClickHouse
     */
    public void writeStockDailyBatch(List<StockDaily> dailies) {
        if (!clickHouseConfig.isEnabled() || dailies.isEmpty()) return;

        String sql = """
                INSERT INTO stock_daily
                (code, trade_date, name, open_price, close_price, high_price, low_price,
                 pre_close, volume, amount, change_percent, change_amount,
                 turnover_rate, pe_ttm, pb)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            for (StockDaily daily : dailies) {
                stmt.setString(1, daily.getCode());
                stmt.setString(2, daily.getTradeDate().toString());
                stmt.setString(3, daily.getName());
                setParam(stmt, 4, daily.getOpenPrice());
                setParam(stmt, 5, daily.getClosePrice());
                setParam(stmt, 6, daily.getHighPrice());
                setParam(stmt, 7, daily.getLowPrice());
                setParam(stmt, 8, daily.getPreClose());
                setLongParam(stmt, 9, daily.getVolume());
                setParam(stmt, 10, daily.getAmount());
                setParam(stmt, 11, daily.getChangePercent());
                setParam(stmt, 12, daily.getChangeAmount());
                setParam(stmt, 13, daily.getTurnoverRate());
                setParam(stmt, 14, daily.getPeTtm());
                setParam(stmt, 15, daily.getPb());
                stmt.addBatch();
            }

            stmt.executeBatch();
            log.debug("[ClickHouse] 批量写入 {} 条记录", dailies.size());
        } catch (Exception e) {
            log.warn("[ClickHouse] 批量写入失败: {}", e.getMessage());
        }
    }
}
