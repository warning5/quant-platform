package com.quant.platform.market.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 市场行情数据 DTO - 日线级别 OHLCV
 * <p>
 * 由 MarketDataService 从 ClickHouse stock_daily/index_daily 构建输出，
 * 不再映射 MySQL market_daily_bar 表（该表已废弃）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketDailyBar implements Serializable {

    /**
     * 股票代码（含市场后缀，如 000001.SZ）
     */
    private String symbol;

    /**
     * 股票名称
     */
    private String name;

    /**
     * 交易日期
     */
    private LocalDate tradeDate;

    /**
     * 开盘价
     */
    private BigDecimal open;

    /**
     * 最高价
     */
    private BigDecimal high;

    /**
     * 最低价
     */
    private BigDecimal low;

    /**
     * 收盘价
     */
    private BigDecimal close;

    /**
     * 前收盘价
     */
    private BigDecimal preClose;

    /**
     * 涨跌额
     */
    private BigDecimal changeAmt;

    /**
     * 涨跌幅%
     */
    private BigDecimal pctChg;

    /**
     * 成交量（手）
     */
    private BigDecimal vol;

    /**
     * 成交额（千元）
     */
    private BigDecimal amount;

    /**
     * 换手率
     */
    private BigDecimal turnoverRate;

    /**
     * 总市值（万元），非持久化字段。
     * 由 MarketDataService.toMarketBar() 从 stock_info.total_market_cap 注入（元→万元）。
     * 用于 SIZE 因子计算，不写入 stock_daily 表。
     */
    private BigDecimal marketCap;

    /**
     * 流通市值（万元），非持久化字段。
     * 由 MarketDataService.toMarketBar() 从 stock_info.total_market_cap 注入（元→万元）。
     * stock_info 没有单独的流通市值，用总市值近似。
     */
    private BigDecimal circMarketCap;

    // ---- 估值字段（供 VAL_PE_PERCENTILE / VAL_PB_PERCENTILE 计算使用）----

    /**
     * 滚动市盈率 (TTM)，来自 ClickHouse stock_daily.pe_ttm。
     * NULL 表示亏损股或数据缺失。
     */
    private BigDecimal peTtm;

    /**
     * 市净率，来自 ClickHouse stock_daily.pb。
     * NULL 表示数据缺失。
     */
    private BigDecimal pb;

    // ---- 分红 / FCF 字段（供 VAL_DIVIDEND_YIELD / VAL_FCF_YIELD 日频计算使用）----

    /**
     * 最近 12 个月每股派息（元），从 MySQL stock_dividend 聚合，
     * 由 MarketDataService 统一注入，计算期间不变。
     */
    private BigDecimal dividendPerShare12m;

    /**
     * 自由现金流（元），来自 MySQL stock_financial_indicator.free_cash_flow 最新值，
     * 由 MarketDataService 统一注入，计算期间不变（季频更新）。
     */
    private BigDecimal fcf;
}
