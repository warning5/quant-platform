package com.quant.platform.stock.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 股票每日行情表
 */
@Data
@TableName("stock_daily")
public class StockDaily implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 股票代码（不含市场标识，如：000001）
     */
    @TableField("code")
    private String code;

    /**
     * 交易日期
     */
    @TableField("trade_date")
    private LocalDate tradeDate;

    /**
     * 股票名称
     */
    @TableField("name")
    private String name;

    /**
     * 开盘价
     */
    @TableField("open_price")
    private BigDecimal openPrice;

    /**
     * 收盘价
     */
    @TableField("close_price")
    private BigDecimal closePrice;

    /**
     * 最高价
     */
    @TableField("high_price")
    private BigDecimal highPrice;

    /**
     * 最低价
     */
    @TableField("low_price")
    private BigDecimal lowPrice;

    /**
     * 昨收价
     */
    @TableField("pre_close")
    private BigDecimal preClose;

    /**
     * 成交量（手）
     */
    @TableField("volume")
    private Long volume;

    /**
     * 成交额（元）
     */
    @TableField("amount")
    private BigDecimal amount;

    /**
     * 涨跌幅（%）
     */
    @TableField("change_percent")
    private BigDecimal changePercent;

    /**
     * 涨跌额（元）
     */
    @TableField("change_amount")
    private BigDecimal changeAmount;

    /**
     * 换手率（%）
     */
    @TableField("turnover_rate")
    private BigDecimal turnoverRate;

    /**
     * 市盈率（TTM）
     */
    @TableField("pe_ttm")
    private BigDecimal peTtm;

    /**
     * 市净率
     */
    @TableField("pb")
    private BigDecimal pb;

    /**
     * 创建时间
     */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
