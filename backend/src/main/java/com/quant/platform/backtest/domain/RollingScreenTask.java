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
 * 滚动选股回测任务实体
 */
@Data
@TableName("rolling_screen_task")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RollingScreenTask implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 任务名称
     */
    @TableField("task_name")
    private String taskName;

    // ===== 选股配置（JSON，完整 ScreenRequest 序列化）=====

    /**
     * 选股配置 JSON（ScreenRequest 完整序列化，含 factors/weights/thresholds/topN/direction）
     */
    @TableField("screen_config_json")
    private String screenConfigJson;

    // ===== 回测参数 =====

    /**
     * 回测起始日
     */
    @TableField("start_date")
    private LocalDate startDate;

    /**
     * 回测结束日
     */
    @TableField("end_date")
    private LocalDate endDate;

    /**
     * 调仓频率: WEEKLY / BIWEEKLY / MONTHLY
     */
    @TableField("rebalance_freq")
    private String rebalanceFreq;

    /**
     * 初始资金（元）
     */
    @TableField("initial_capital")
    private BigDecimal initialCapital;

    /**
     * 佣金率
     */
    @TableField("commission_rate")
    private BigDecimal commissionRate;

    /**
     * 滑点率
     */
    @TableField("slippage_rate")
    private BigDecimal slippageRate;

    /**
     * 滑点模型: FIXED / VOLUME
     */
    @TableField("slippage_model")
    private String slippageModel;

    /**
     * 成交价模式: CLOSE(收盘价) / NEXT_OPEN(次日开盘价) / VWAP(成交量加权均价)
     */
    @TableField("order_type")
    private String orderType;

    /**
     * 基准指数代码
     */
    @TableField("benchmark_code")
    private String benchmarkCode;

    /**
     * 权重分配模式: EQUAL(等权) / SCORE_PROPORTIONAL(按得分比例)
     */
    @TableField("weight_mode")
    private String weightMode;

    /**
     * 涨跌停过滤
     */
    @TableField("limit_filter")
    private Boolean limitFilter;

    /**
     * 停牌过滤
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
     * 过户费率（沪深双向，默认 0.00002 = 0.02‰）
     */
    @TableField("transfer_fee_rate")
    private BigDecimal transferFeeRate;

    // ===== 任务状态 =====

    /**
     * 状态: PENDING / RUNNING / COMPLETED / FAILED / CANCELLED
     */
    @TableField("status")
    private String status;

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

    // ===== 净值摘要（完成后填充，加速列表查询）=====

    /**
     * 最终净值（从 1.0 起）
     */
    @TableField("final_nav")
    private BigDecimal finalNav;

    /**
     * 累计收益率
     */
    @TableField("total_return")
    private BigDecimal totalReturn;

    /**
     * 年化收益率
     */
    @TableField("annual_return")
    private BigDecimal annualReturn;

    /**
     * 最大回撤
     */
    @TableField("max_drawdown")
    private BigDecimal maxDrawdown;

    /**
     * 夏普比率
     */
    @TableField("sharpe_ratio")
    private BigDecimal sharpeRatio;

    /**
     * 基准累计收益率（与 totalReturn 对比计算超额收益）
     */
    @TableField("benchmark_return")
    private BigDecimal benchmarkReturn;

    /**
     * 总交易次数（调仓次数）
     */
    @TableField("total_trades")
    private Integer totalTrades;

    /**
     * 胜率（盈利调仓次数 / 总调仓次数）
     */
    @TableField("win_rate")
    private BigDecimal winRate;

    // ===== 时间戳 =====

    /**
     * 创建时间
     */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 开始执行时间
     */
    @TableField("started_at")
    private LocalDateTime startedAt;

    /**
     * 完成时间
     */
    @TableField("completed_at")
    private LocalDateTime completedAt;
}
