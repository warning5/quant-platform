package com.quant.platform.dataupdate;

import lombok.Data;

import java.time.LocalDateTime;
import com.quant.platform.common.enums.JobStatus;

/**
 * 定时任务执行历史记录（对应表 task_run_history）
 * 每次任务执行（CRON/MANUAL/DEPENDENCY）落一条记录，用于回看失败历史、成功率趋势、
 * 连续失败统计、超时/孤儿检测。
 */
@Data
public class TaskRunHistory {

    private Long id;
    private String taskKey;
    private String taskName;
    private String triggerType;   // CRON / MANUAL / DEPENDENCY
    private String updateType;    // DAILY / SENTIMENT / FACTOR_COMPUTE ...
    private String upstreamKey;   // 依赖触发时的上游 key
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private JobStatus status;     // RUNNING / SUCCESS / FAILED / TIMEOUT / PARTIAL / CANCELLED
    private Integer exitCode;
    private Integer durationSec;
    private String errorMsg;
    private LocalDateTime createdAt;
}
