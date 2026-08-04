package com.quant.platform.strategy.paper;

/**
 * 模拟盘交易信号状态（paper_signal.status）。
 *
 * <p>描述单条信号从产生到落地的处理结果，与作业状态语义不同，
 * 故不复用 {@code com.quant.platform.common.enums.JobStatus}。
 * 取值与数据库、前端约定一致（{@link #name()} 即存量字符串）。
 */
public enum PaperSignalStatus {

    /** 待处理 */
    PENDING,

    /** 已成交 */
    EXECUTED,

    /** 主动跳过（资金不足、已持仓、无行情等） */
    SKIPPED,

    /** 被风控拦截（黑名单等） */
    BLOCKED,

    /** 已过期未执行 */
    EXPIRED;

    /** 按字符串反查，忽略大小写；无匹配返回 null。 */
    public static PaperSignalStatus fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (PaperSignalStatus s : values()) {
            if (s.name().equalsIgnoreCase(code)) {
                return s;
            }
        }
        return null;
    }
}
