package com.quant.platform.market.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 市场行情数据 - 日线级别OHLCV
 */
@Data
@TableName("market_daily_bar")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketDailyBar implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 股票代码
     */
    @TableField("symbol")
    private String symbol;

    /**
     * 股票名称
     */
    @TableField("name")
    private String name;

    /**
     * 交易日期
     */
    @TableField("trade_date")
    private LocalDate tradeDate;

    /**
     * 开盘价
     */
    @TableField("open")
    private BigDecimal open;

    /**
     * 最高价
     */
    @TableField("high")
    private BigDecimal high;

    /**
     * 最低价
     */
    @TableField("low")
    private BigDecimal low;

    /**
     * 收盘价
     */
    @TableField("close")
    private BigDecimal close;

    /**
     * 前收盘价
     */
    @TableField("pre_close")
    private BigDecimal preClose;

    /**
     * 涨跌额
     */
    @TableField("change_amt")
    private BigDecimal changeAmt;

    /**
     * 涨跌幅%
     */
    @TableField("pct_chg")
    private BigDecimal pctChg;

    /**
     * 成交量（手）
     */
    @TableField("vol")
    private BigDecimal vol;

    /**
     * 成交额（千元）
     */
    @TableField("amount")
    private BigDecimal amount;

    /**
     * 换手率
     */
    @TableField("turnover_rate")
    private BigDecimal turnoverRate;

    /**
     * 总市值（万元），非持久化字段。
     * 由 MarketDataService.toMarketBar() 从 stock_info.total_market_cap 注入（元→万元）。
     * 用于 SIZE 因子计算，不写入 stock_daily 表。
     */
    @TableField(exist = false)
    private BigDecimal marketCap;

    /**
     * 流通市值（万元），非持久化字段。
     * 由 MarketDataService.toMarketBar() 从 stock_info.total_market_cap 注入（元→万元）。
     * stock_info 没有单独的流通市值，用总市值近似。
     */
    @TableField(exist = false)
    private BigDecimal circMarketCap;

    /**
     * 创建时间
     */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
