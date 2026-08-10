package com.quant.platform.mp.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 回测任务（只读视图，复用主后端 backtest_task 表）
 */
@Data
@TableName("backtest_task")
public class MpBacktestTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("task_name")
    private String taskName;

    @TableField("strategy_id")
    private Long strategyId;

    @TableField("strategy_code")
    private String strategyCode;

    private String status;

    @TableField("start_date")
    private LocalDate startDate;

    @TableField("end_date")
    private LocalDate endDate;

    @TableField("benchmark_code")
    private String benchmarkCode;

    @TableField("rebalance_freq")
    private String rebalanceFreq;

    @TableField("completed_at")
    private LocalDateTime completedAt;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
