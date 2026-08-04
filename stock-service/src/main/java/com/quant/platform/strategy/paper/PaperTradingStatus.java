package com.quant.platform.strategy.paper;

/**
 * 模拟盘运行状态（paper_trading.status）。
 *
 * <p>模拟盘是长期存续的实例，其状态描述的是"生命周期"而非一次性作业的执行结果，
 * 因此不复用 {@code com.quant.platform.common.enums.JobStatus}。
 * 取值与数据库、前端约定一致（{@link #name()} 即存量字符串）。
 */
public enum PaperTradingStatus {

    /** 运行中，允许生成与执行信号 */
    RUNNING,

    /** 已暂停，保留持仓但不再生成新信号 */
    PAUSED,

    /** 已停止，终态 */
    STOPPED;

    /** 按字符串反查，忽略大小写；无匹配返回 null。 */
    public static PaperTradingStatus fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (PaperTradingStatus s : values()) {
            if (s.name().equalsIgnoreCase(code)) {
                return s;
            }
        }
        return null;
    }
}
