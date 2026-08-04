package com.quant.platform.common.enums;

import lombok.Getter;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 任务/作业统一状态枚举（P0：消除散落的魔法字符串）。
 *
 * 回测、参数优化、数据更新、模拟盘、因子计算、调度历史等子系统共用同一套状态词表。
 * 通过 {@link #name()} 返回与数据库/前端约定完全一致的大写字符串，
 * 因此存量数据无需迁移；新增代码统一引用本枚举即可避免拼写漂移。
 */
@Getter
public enum JobStatus {
    /** 空闲：任务从未启动或已复位（DataUpdateTask 初始态） */
    IDLE,
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    SUCCESS,
    PARTIAL,
    DONE,
    TEST_DONE,
    TIMEOUT;

    /**
     * 回测任务（backtest_task.status）允许的状态子集。
     *
     * <p>本枚举为跨子系统共用词表，取值范围比单个子系统宽。
     * 合并 {@code BacktestTask.BacktestStatus} 后编译期约束被放宽，
     * 故在外部入参处用本集合做运行期白名单校验，防止 TEST_DONE 等
     * 与回测无关的状态被写入或用于查询。
     */
    public static final Set<JobStatus> BACKTEST_STATES = Collections.unmodifiableSet(
            EnumSet.of(PENDING, RUNNING, COMPLETED, FAILED, CANCELLED));

    /** 与数据库/前端约定的字符串（等价于 name()）。 */
    public String code() {
        return name();
    }

    /** 是否为终态（不会再流转）。 */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED
                || this == SUCCESS || this == DONE || this == TEST_DONE
                || this == TIMEOUT || this == PARTIAL;
    }

    /** 按字符串反查枚举，忽略大小写；无匹配返回 null。 */
    public static JobStatus fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (JobStatus s : values()) {
            if (s.name().equalsIgnoreCase(code)) {
                return s;
            }
        }
        return null;
    }
}
