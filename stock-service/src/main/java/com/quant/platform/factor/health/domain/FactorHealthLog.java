package com.quant.platform.factor.health.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 因子健康日志实体（降级/复活事件记录）
 */
@Data
@TableName("factor_health_log")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FactorHealthLog implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("factor_code")
    private String factorCode;

    @TableField("event_type")
    private String eventType;

    @TableField("ic_30d")
    private Double ic30d;

    @TableField("ic_60d")
    private Double ic60d;

    @TableField("ic_90d")
    private Double ic90d;

    @TableField("ir_30d")
    private Double ir30d;

    @TableField("ir_60d")
    private Double ir60d;

    @TableField("ic_at_activation")
    private Double icAtActivation;

    @TableField("decay_ratio")
    private Double decayRatio;

    @TableField("reason")
    private String reason;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    public enum EventType {
        DEGRADE_WARNING,   // 衰减预警（IC下降但未达降级阈值）
        DEGRADED,          // 已降级（IC严重衰减，自动停用）
        RESURRECT_CANDIDATE, // 复活候选（IC开始恢复但未达复活阈值）
        RESURRECTED        // 已复活（IC持续恢复，自动恢复ACTIVE）
    }
}
