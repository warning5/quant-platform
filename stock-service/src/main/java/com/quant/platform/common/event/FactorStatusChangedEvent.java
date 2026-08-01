package com.quant.platform.common.event;

import org.springframework.context.ApplicationEvent;

/**
 * 因子状态变更事件（P3-12 服务解耦）
 *
 * 当因子健康检查导致因子状态变更（ACTIVE→DEGRADED 或 DEGRADED→ACTIVE）时发布。
 * 下游服务（如推荐系统、因子计算引擎）可监听此事件，
 * 自动调整因子配置而无需直接调用FactorHealthMonitor。
 */
public class FactorStatusChangedEvent extends ApplicationEvent {

    private final String factorCode;
    private final String oldStatus;
    private final String newStatus;
    private final String reason;

    public FactorStatusChangedEvent(Object source, String factorCode, String oldStatus, String newStatus, String reason) {
        super(source);
        this.factorCode = factorCode;
        this.oldStatus = oldStatus;
        this.newStatus = newStatus;
        this.reason = reason;
    }

    public String getFactorCode() { return factorCode; }
    public String getOldStatus() { return oldStatus; }
    public String getNewStatus() { return newStatus; }
    public String getReason() { return reason; }

    public boolean isDegraded() { return "DEGRADED".equals(newStatus); }
    public boolean isResurrected() { return "ACTIVE".equals(newStatus) && "DEGRADED".equals(oldStatus); }
}
