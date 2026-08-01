package com.quant.platform.common.event;

import org.springframework.context.ApplicationEvent;

import java.time.LocalDate;

/**
 * 因子计算完成事件（P3-12 服务解耦）
 *
 * 当 FACTOR_COMPUTE 任务完成后发布此事件。
 * 下游服务（如推荐系统、IC计算、因子健康检查）可监听此事件
 * 自动触发后续操作，而不需要直接调用。
 *
 * 替代原来 ScheduleService 中硬编码的依赖链触发逻辑。
 */
public class FactorComputeCompletedEvent extends ApplicationEvent {

    private final LocalDate computeDate;
    private final int factorCount;
    private final boolean success;

    public FactorComputeCompletedEvent(Object source, LocalDate computeDate, int factorCount, boolean success) {
        super(source);
        this.computeDate = computeDate;
        this.factorCount = factorCount;
        this.success = success;
    }

    public LocalDate getComputeDate() { return computeDate; }
    public int getFactorCount() { return factorCount; }
    public boolean isSuccess() { return success; }
}
