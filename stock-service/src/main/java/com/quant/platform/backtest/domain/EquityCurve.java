package com.quant.platform.backtest.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 回测权益曲线记录
 */
@Data
@Builder
@TableName("equity_curve")
@NoArgsConstructor
@AllArgsConstructor
public class EquityCurve implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("task_id")
    private Long taskId;

    @TableField("trade_date")
    private LocalDate tradeDate;

    /** 组合市值 */
    @TableField("portfolio_value")
    private BigDecimal portfolioValue;

    /** 净值（以 initialCapital=1 为基准） */
    @TableField("nav")
    private BigDecimal nav;

    /** 基准净值（暂无） */
    @TableField("benchmark_nav")
    private BigDecimal benchmarkNav;

    /** 当日收益率（%） */
    @TableField("return_pct")
    private BigDecimal returnPct;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
