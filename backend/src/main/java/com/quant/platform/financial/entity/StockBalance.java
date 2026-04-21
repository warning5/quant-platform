package com.quant.platform.financial.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 资产负债表
 */
@Data
@TableName("stock_balance")
public class StockBalance implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("code")
    private String code;

    @TableField("report_date")
    private String reportDate;

    @TableField("report_type")
    private Integer reportType;

    @TableField("end_date")
    private LocalDate endDate;

    @TableField("total_assets")
    private BigDecimal totalAssets;

    @TableField("total_current_assets")
    private BigDecimal totalCurrentAssets;

    @TableField("cash_and_equivalents")
    private BigDecimal cashAndEquivalents;

    @TableField("trading_assets")
    private BigDecimal tradingAssets;

    @TableField("accounts_receivable")
    private BigDecimal accountsReceivable;

    @TableField("inventory")
    private BigDecimal inventory;

    @TableField("long_term_equity_invest")
    private BigDecimal longTermEquityInvest;

    @TableField("fixed_assets")
    private BigDecimal fixedAssets;

    @TableField("construction_in_progress")
    private BigDecimal constructionInProgress;

    @TableField("intangible_assets")
    private BigDecimal intangibleAssets;

    @TableField("goodwill")
    private BigDecimal goodwill;

    @TableField("deferred_tax_assets")
    private BigDecimal deferredTaxAssets;

    @TableField("total_liabilities")
    private BigDecimal totalLiabilities;

    @TableField("short_term_borrowing")
    private BigDecimal shortTermBorrowing;

    @TableField("accounts_payable")
    private BigDecimal accountsPayable;

    @TableField("long_term_borrowing")
    private BigDecimal longTermBorrowing;

    @TableField("total_equity")
    private BigDecimal totalEquity;

    @TableField("parent_equity")
    private BigDecimal parentEquity;

    @TableField("minority_interests")
    private BigDecimal minorityInterests;

    @TableField("paid_in_capital")
    private BigDecimal paidInCapital;

    @TableField("capital_reserve")
    private BigDecimal capitalReserve;

    @TableField("surplus_reserve")
    private BigDecimal surplusReserve;

    @TableField("undistributed_profit")
    private BigDecimal undistributedProfit;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
