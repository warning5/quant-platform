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
 * 回测任务实体
 */
@Data
@TableName("backtest_task")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BacktestTask implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 策略ID
     */
    @TableField("strategy_id")
    private Long strategyId;

    /**
     * 策略代码
     */
    @TableField("strategy_code")
    private String strategyCode;

    /**
     * 任务名称
     */
    @TableField("task_name")
    private String taskName;

    /**
     * 开始日期
     */
    @TableField("start_date")
    private LocalDate startDate;

    /**
     * 结束日期
     */
    @TableField("end_date")
    private LocalDate endDate;

    /**
     * 初始资金
     */
    @TableField("initial_capital")
    private BigDecimal initialCapital;

    /**
     * 手续费率
     */
    @TableField("commission_rate")
    private BigDecimal commissionRate;

    /**
     * 滑点
     */
    @TableField("slippage_rate")
    private BigDecimal slippageRate;

    /**
     * 滑点模型: FIXED(固定比例) / VOLUME(成交量比例)
     */
    @TableField("slippage_model")
    private String slippageModel;

    /**
     * 基准指数代码
     */
    @TableField("benchmark_code")
    private String benchmarkCode;

    /**
     * 涨跌停过滤: true=启用
     */
    @TableField("limit_filter")
    private Boolean limitFilter;

    /**
     * 停牌过滤: true=启用
     */
    @TableField("suspend_filter")
    private Boolean suspendFilter;

    /**
     * 印花税率（仅卖出，默认万5）
     */
    @TableField("stamp_tax_rate")
    private BigDecimal stampTaxRate;

    /**
     * 最低佣金（元/笔，默认5元）
     */
    @TableField("min_commission")
    private BigDecimal minCommission;

    /**
     * 分红处理: true=启用（分红到账+送转股本调整）, false=忽略
     */
    @TableField("dividend_reinvest")
    private Boolean dividendReinvest;

    /**
     * 过户费率（沪深股票双向收取，默认 0.00002 = 0.02‰，北交所不收取）
     */
    @TableField("transfer_fee_rate")
    private BigDecimal transferFeeRate;

    /**
     * 限价单模式: CLOSE(收盘价，默认) / NEXT_OPEN(次日开盘价) / VWAP(成交量加权均价)
     */
    @TableField("order_type")
    private String orderType;

    /**
     * 止损比例（如 0.05 = 5%）
     */
    @TableField("stop_loss_pct")
    private BigDecimal stopLossPct;

    /**
     * 止盈比例（如 0.10 = 10%）
     */
    @TableField("stop_profit_pct")
    private BigDecimal stopProfitPct;

    /**
     * 最大持仓数量（覆盖策略定义，null = 使用策略默认值）
     */
    @TableField("max_position_count")
    private Integer maxPositionCount;

    /**
     * 状态
     */
    @TableField("status")
    private BacktestStatus status;

    /**
     * 进度 0-100
     */
    @TableField("progress")
    private Integer progress;

    /**
     * 错误信息
     */
    @TableField("error_message")
    private String errorMessage;

    /**
     * 创建时间
     */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 开始时间
     */
    @TableField("started_at")
    private LocalDateTime startedAt;

    /**
     * 完成时间
     */
    @TableField("completed_at")
    private LocalDateTime completedAt;

    /**
     * 回测状态枚举
     */
    public enum BacktestStatus {
        PENDING, RUNNING, COMPLETED, FAILED, CANCELLED
    }
}
