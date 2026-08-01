package com.quant.platform.dataupdate;

import org.springframework.context.ApplicationEvent;

/**
 * 数据更新任务完成事件
 * 在 DataUpdateService 异步任务执行完毕后发布，
 * 供 ScheduleService 监听以触发下游依赖任务。
 */
public class DataUpdateCompletedEvent extends ApplicationEvent {

    private final String taskKey;
    private final boolean success;
    private final long durationSeconds;

    public DataUpdateCompletedEvent(Object source, String taskKey, boolean success, long durationSeconds) {
        super(source);
        this.taskKey = taskKey;
        this.success = success;
        this.durationSeconds = durationSeconds;
    }

    public String getTaskKey() {
        return taskKey;
    }

    public boolean isSuccess() {
        return success;
    }

    public long getDurationSeconds() {
        return durationSeconds;
    }
}
