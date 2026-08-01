package com.quant.platform.strategy.paper;

import lombok.Builder;
import lombok.Data;

/**
 * 风控检查结果
 * 用于交易前风控检查，判断是否阻断交易
 */
@Data
@Builder
public class RiskCheckResult {
    /** 是否阻断交易 */
    private boolean blocked;
    /** 阻断原因 */
    private String blockReason;
    /** 风险等级：WARNING / CRITICAL */
    private String riskLevel;
    /** 建议操作：REDUCE / STOP / BLOCK */
    private String suggestAction;

    public static RiskCheckResult pass() {
        return RiskCheckResult.builder().blocked(false).build();
    }

    public static RiskCheckResult blocked(String reason) {
        return RiskCheckResult.builder()
            .blocked(true)
            .blockReason(reason)
            .riskLevel("WARNING")
            .suggestAction("BLOCK")
            .build();
    }
}
