package com.quant.platform.financial.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 现金流量表
 */
@Data
@TableName("stock_cashflow")
public class StockCashflow implements Serializable {

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

    @TableField("net_operate_cf")
    private BigDecimal netOperateCf;

    @TableField("cash_received_sales")
    private BigDecimal cashReceivedSales;

    @TableField("cash_paid_goods_services")
    private BigDecimal cashPaidGoodsServices;

    @TableField("cash_paid_employee")
    private BigDecimal cashPaidEmployee;

    @TableField("cash_paid_tax")
    private BigDecimal cashPaidTax;

    @TableField("net_invest_cf")
    private BigDecimal netInvestCf;

    @TableField("cash_paid_acquisition")
    private BigDecimal cashPaidAcquisition;

    @TableField("cash_paid_invest")
    private BigDecimal cashPaidInvest;

    @TableField("net_finance_cf")
    private BigDecimal netFinanceCf;

    @TableField("cash_received_absorb_invest")
    private BigDecimal cashReceivedAbsorbInvest;

    @TableField("cash_received_borrowing")
    private BigDecimal cashReceivedBorrowing;

    @TableField("cash_paid_borrowing")
    private BigDecimal cashPaidBorrowing;

    @TableField("cash_paid_dividend")
    private BigDecimal cashPaidDividend;

    @TableField("net_cash_increase")
    private BigDecimal netCashIncrease;

    @TableField("cash_at_beginning")
    private BigDecimal cashAtBeginning;

    @TableField("cash_at_end")
    private BigDecimal cashAtEnd;

    @TableField("free_cash_flow")
    private BigDecimal freeCashFlow;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
