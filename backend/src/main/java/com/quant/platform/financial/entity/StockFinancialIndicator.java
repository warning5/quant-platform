package com.quant.platform.financial.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 财务指标摘要表
 */
@Data
@TableName("stock_financial_indicator")
public class StockFinancialIndicator implements Serializable {

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

    // 盈利能力
    @TableField("gross_profit_margin")
    private BigDecimal grossProfitMargin;

    @TableField("net_profit_margin")
    private BigDecimal netProfitMargin;

    @TableField("roe")
    private BigDecimal roe;

    @TableField("roa")
    private BigDecimal roa;

    // 成长能力
    @TableField("revenue_yoy")
    private BigDecimal revenueYoy;

    @TableField("net_profit_yoy")
    private BigDecimal netProfitYoy;

    @TableField("operating_profit_yoy")
    private BigDecimal operatingProfitYoy;

    @TableField("total_assets_yoy")
    private BigDecimal totalAssetsYoy;

    // 偿债能力
    @TableField("current_ratio")
    private BigDecimal currentRatio;

    @TableField("quick_ratio")
    private BigDecimal quickRatio;

    @TableField("debt_to_asset_ratio")
    private BigDecimal debtToAssetRatio;

    // 营运能力
    @TableField("inventory_turnover")
    private BigDecimal inventoryTurnover;

    @TableField("inventory_turnover_days")
    private BigDecimal inventoryTurnoverDays;

    @TableField("ar_turnover_days")
    private BigDecimal arTurnoverDays;

    @TableField("total_assets_turnover")
    private BigDecimal totalAssetsTurnover;

    // 每股指标
    @TableField("eps_basic")
    private BigDecimal epsBasic;

    @TableField("bps")
    private BigDecimal bps;

    @TableField("operating_cf_to_np")
    private BigDecimal operatingCfToNp;

    @TableField("free_cash_flow")
    private BigDecimal freeCashFlow;

    @TableField("net_operate_cf")
    private BigDecimal netOperateCf;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
