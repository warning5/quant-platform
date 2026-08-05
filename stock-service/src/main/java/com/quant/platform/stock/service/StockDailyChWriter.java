package com.quant.platform.stock.service;

import static com.quant.platform.stock.service.StockDailySqlSupport.*;

import com.quant.platform.config.ClickHouseConfig;
import com.quant.platform.stock.entity.StockDaily;
import com.quant.platform.stock.service.ClickHouseJdbcClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
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
     * OHLCV 写入前自洽性校验（T-DATA-1）。
     * 校验不通过返回 false，调用方跳过该条并告警，避免异常值污染 stock_daily。
     */
    private boolean isValidStockDaily(StockDaily d) {
        if (d == null || d.getCode() == null || d.getTradeDate() == null) return false;
        BigDecimal open = d.getOpenPrice();
        BigDecimal close = d.getClosePrice();
        BigDecimal high = d.getHighPrice();
        BigDecimal low = d.getLowPrice();
        BigDecimal preClose = d.getPreClose();
        Long volume = d.getVolume();
        if (anyNull(open, close, high, low)
                || open.signum() <= 0 || close.signum() <= 0
                || high.signum() <= 0 || low.signum() <= 0) {
            return false;
        }
        if (low.compareTo(high) > 0) return false;                                  // low <= high
        if (close.compareTo(low) < 0 || close.compareTo(high) > 0) return false;   // close ∈ [low, high]
        if (open.compareTo(low) < 0 || open.compareTo(high) > 0) return false;     // open  ∈ [low, high]
        if (preClose != null && preClose.signum() <= 0) return false;
        if (volume == null || volume < 0) return false;                            // 成交量非负
        if (d.getAmount() != null && d.getAmount().signum() < 0) return false;
        return true;
    }

    private boolean anyNull(BigDecimal... vals) {
        for (BigDecimal v : vals) {
            if (v == null) return true;
        }
        return false;
    }

    /**
     * 写入单条日线数据到 ClickHouse
     */
    public void writeStockDaily(StockDaily daily) {
        if (!clickHouseConfig.isEnabled()) return;
        if (!isValidStockDaily(daily)) {
            log.warn("[ClickHouse] 跳过校验失败记录 code={} date={} (OHLCV 自洽性不通过)",
                    daily.getCode(), daily.getTradeDate());
            return;
        }

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
            log.warn("[ClickHouse] 写入失败: {}", e.getMessage(), e);
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

        int skipped = 0;
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            for (StockDaily daily : dailies) {
                if (!isValidStockDaily(daily)) {
                    skipped++;
                    log.warn("[ClickHouse] 跳过校验失败记录 code={} date={} (OHLCV 自洽性不通过)",
                            daily.getCode(), daily.getTradeDate());
                    continue;
                }
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
            int written = dailies.size() - skipped;
            if (skipped > 0) {
                log.warn("[ClickHouse] 批量写入 {} 条，跳过 {} 条校验失败记录", written, skipped);
            }
            log.debug("[ClickHouse] 批量写入 {} 条记录", written);
        } catch (Exception e) {
            log.warn("[ClickHouse] 批量写入失败: {}", e.getMessage(), e);
        }
    }
}
