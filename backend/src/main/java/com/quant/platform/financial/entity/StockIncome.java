package com.quant.platform.financial.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 利润表
 */
@Data
@TableName("stock_income")
public class StockIncome implements Serializable {

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

    @TableField("total_revenue")
    private BigDecimal totalRevenue;

    @TableField("revenue")
    private BigDecimal revenue;

    @TableField("operating_cost")
    private BigDecimal operatingCost;

    @TableField("operating_profit")
    private BigDecimal operatingProfit;

    @TableField("total_profit")
    private BigDecimal totalProfit;

    @TableField("income_tax")
    private BigDecimal incomeTax;

    @TableField("net_profit")
    private BigDecimal netProfit;

    @TableField("np_parent_company_owners")
    private BigDecimal npParentCompanyOwners;

    @TableField("np_minority")
    private BigDecimal npMinority;

    @TableField("eps_basic")
    private BigDecimal epsBasic;

    @TableField("eps_diluted")
    private BigDecimal epsDiluted;

    @TableField("deducted_np_parent_company")
    private BigDecimal deductedNpParentCompany;

    @TableField("total_comprehensive_income")
    private BigDecimal totalComprehensiveIncome;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
