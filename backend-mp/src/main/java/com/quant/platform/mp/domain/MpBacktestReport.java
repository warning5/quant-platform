package com.quant.platform.mp.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 回测报告（只读视图，复用主后端 backtest_report 表）
 */
@Data
@TableName("backtest_report")
public class MpBacktestReport {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("task_id")
    private Long taskId;

    @TableField("strategy_code")
    private String strategyCode;

    @TableField("total_return")
    private BigDecimal totalReturn;

    @TableField("annual_return")
    private BigDecimal annualReturn;

    @TableField("benchmark_return")
    private BigDecimal benchmarkReturn;

    @TableField("benchmark_annual_return")
    private BigDecimal benchmarkAnnualReturn;

    @TableField("excess_return")
    private BigDecimal excessReturn;

    private BigDecimal volatility;

    @TableField("sharpe_ratio")
    private BigDecimal sharpeRatio;

    @TableField("sortino_ratio")
    private BigDecimal sortinoRatio;

    @TableField("calmar_ratio")
    private BigDecimal calmarRatio;

    @TableField("max_drawdown")
    private BigDecimal maxDrawdown;

    @TableField("max_drawdown_duration")
    private Integer maxDrawdownDuration;

    @TableField("information_ratio")
    private BigDecimal informationRatio;

    private BigDecimal alpha;

    private BigDecimal beta;

    @TableField("total_trades")
    private Integer totalTrades;

    @TableField("win_rate")
    private BigDecimal winRate;

    @TableField("profit_loss_ratio")
    private BigDecimal profitLossRatio;

    @TableField("equity_curve_json")
    private String equityCurveJson;

    @TableField("benchmark_curve_json")
    private String benchmarkCurveJson;

    @TableField("drawdown_series_json")
    private String drawdownSeriesJson;

    @TableField("monthly_returns_json")
    private String monthlyReturnsJson;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
