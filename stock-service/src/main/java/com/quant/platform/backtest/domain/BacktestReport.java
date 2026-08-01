package com.quant.platform.backtest.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 回测绩效报告实体
 */
@Data
@TableName("backtest_report")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BacktestReport implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 任务ID
     */
    @TableField("task_id")
    private Long taskId;

    /**
     * 策略代码
     */
    @TableField("strategy_code")
    private String strategyCode;

    // ===== 收益指标 =====

    /**
     * 总收益率
     */
    @TableField("total_return")
    private BigDecimal totalReturn;

    /**
     * 年化收益率
     */
    @TableField("annual_return")
    private BigDecimal annualReturn;

    /**
     * 基准总收益率
     */
    @TableField("benchmark_return")
    private BigDecimal benchmarkReturn;

    /**
     * 基准年化收益率
     */
    @TableField("benchmark_annual_return")
    private BigDecimal benchmarkAnnualReturn;

    /**
     * 超额收益率（年化）
     */
    @TableField("excess_return")
    private BigDecimal excessReturn;

    // ===== 风险指标 =====

    /**
     * 年化波动率
     */
    @TableField("volatility")
    private BigDecimal volatility;

    /**
     * 夏普比率
     */
    @TableField("sharpe_ratio")
    private BigDecimal sharpeRatio;

    /**
     * 索提诺比率
     */
    @TableField("sortino_ratio")
    private BigDecimal sortinoRatio;

    /**
     * 卡玛比率
     */
    @TableField("calmar_ratio")
    private BigDecimal calmarRatio;

    /**
     * 最大回撤
     */
    @TableField("max_drawdown")
    private BigDecimal maxDrawdown;

    /**
     * 最大回撤持续天数
     */
    @TableField("max_drawdown_duration")
    private Integer maxDrawdownDuration;

    /**
     * 信息比率
     */
    @TableField("information_ratio")
    private BigDecimal informationRatio;

    /**
     * Alpha（超额收益）
     */
    @TableField("alpha")
    private BigDecimal alpha;

    /**
     * Beta（系统风险）
     */
    @TableField("beta")
    private BigDecimal beta;

    /**
     * 跟踪误差
     */
    @TableField("tracking_error")
    private BigDecimal trackingError;

    /**
     * 下行风险
     */
    @TableField("downside_risk")
    private BigDecimal downsideRisk;

    // ===== 交易统计 =====

    /**
     * 总交易次数
     */
    @TableField("total_trades")
    private Integer totalTrades;

    /**
     * 胜率
     */
    @TableField("win_rate")
    private BigDecimal winRate;

    /**
     * 平均盈利
     */
    @TableField("avg_win_return")
    private BigDecimal avgWinReturn;

    /**
     * 平均亏损
     */
    @TableField("avg_loss_return")
    private BigDecimal avgLossReturn;

    /**
     * 盈亏比
     */
    @TableField("profit_loss_ratio")
    private BigDecimal profitLossRatio;

    // ===== 超额收益分析指标（参考 baostock 用户案例）=====

    /**
     * 超额收益均值（日频年化）
     */
    @TableField("excess_mean")
    private BigDecimal excessMean;

    /**
     * 超额收益标准差（日频年化）
     */
    @TableField("excess_std")
    private BigDecimal excessStd;

    /**
     * 超额收益胜率（跑赢大盘的交易天数占比）
     */
    @TableField("excess_win_rate")
    private BigDecimal excessWinRate;

    /**
     * 超额收益最大回撤
     */
    @TableField("excess_max_drawdown")
    private BigDecimal excessMaxDrawdown;

    /**
     * Alpha贡献占比（超额收益中alpha贡献的比例，0~1）
     */
    @TableField("alpha_contribution")
    private BigDecimal alphaContribution;

    // ===== 详细数据 (JSON) =====

    /**
     * 净值曲线
     */
    @TableField("equity_curve_json")
    private String equityCurveJson;

    /**
     * 基准逐日净值曲线
     */
    @TableField("benchmark_curve_json")
    private String benchmarkCurveJson;

    /**
     * 回撤序列
     */
    @TableField("drawdown_series_json")
    private String drawdownSeriesJson;

    /**
     * 月度收益热力图数据
     */
    @TableField("monthly_returns_json")
    private String monthlyReturnsJson;

    /**
     * 持仓历史
     */
    @TableField("position_history_json")
    private String positionHistoryJson;

    /**
     * 交易记录
     */
    @TableField("trade_log_json")
    private String tradeLogJson;

    /**
     * 已实现收益率曲线（仅统计已平仓交易的累计PnL，剔除浮盈浮亏）
     * 格式同 equityCurveJson：[{date, value}]，value 从 1.0 开始
     */
    @TableField(exist = false)
    private String realizedCurveJson;

    /**
     * 创建时间
     */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
