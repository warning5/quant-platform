package com.quant.platform.stock.entity;

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
 * 分红除权数据实体
 */
@Data
@TableName("stock_dividend")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockDividend implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 股票代码（不含市场后缀） */
    @TableField("code")
    private String code;

    /** 股票名称 */
    @TableField("name")
    private String name;

    /** 除权除息日 */
    @TableField("ex_dividend_date")
    private LocalDate exDividendDate;

    /** 股权登记日 */
    @TableField("record_date")
    private LocalDate recordDate;

    /** 派息日 */
    @TableField("pay_date")
    private LocalDate payDate;

    /** 每股派息（元，税前） */
    @TableField("cash_dividend")
    private BigDecimal cashDividend;

    /** 每股送股（股） */
    @TableField("stock_dividend")
    private BigDecimal stockDividend;

    /** 每股转增（股） */
    @TableField("convert_dividend")
    private BigDecimal convertDividend;

    /** 报告年度 */
    @TableField("report_year")
    private String reportYear;

    /** 创建时间 */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
