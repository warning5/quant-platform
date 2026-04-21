package com.quant.platform.backtest.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 参数优化报告实体
 */
@Data
@TableName("param_optimize_report")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParamOptimizeReport implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 优化任务ID
     */
    @TableField("job_id")
    private String jobId;

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
    private String startDate;

    /**
     * 结束日期
     */
    @TableField("end_date")
    private String endDate;

    /**
     * 目标函数
     */
    @TableField("objective")
    private String objective;

    /**
     * 参数网格定义（JSON）
     */
    @TableField("param_grid_json")
    private String paramGridJson;

    /**
     * 状态：PENDING / RUNNING / COMPLETED / FAILED
     */
    @TableField("status")
    private String status;

    /**
     * 总参数组合数
     */
    @TableField("total")
    private Integer total;

    /**
     * 已完成数
     */
    @TableField("done")
    private Integer done;

    /**
     * 进度百分比
     */
    @TableField("progress")
    private Integer progress;

    /**
     * 最优参数（JSON）
     */
    @TableField("best_params_json")
    private String bestParamsJson;

    /**
     * 最优得分
     */
    @TableField("best_score")
    private BigDecimal bestScore;

    /**
     * 最优年化收益
     */
    @TableField("best_annual_return")
    private BigDecimal bestAnnualReturn;

    /**
     * 最优最大回撤
     */
    @TableField("best_max_drawdown")
    private BigDecimal bestMaxDrawdown;

    /**
     * 全部结果（JSON数组）
     */
    @TableField("results_json")
    private String resultsJson;

    /**
     * 错误信息
     */
    @TableField("error_message")
    private String errorMessage;

    /**
     * 执行耗时（毫秒）
     */
    @TableField("elapsed_ms")
    private Long elapsedMs;

    /**
     * 创建时间
     */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
