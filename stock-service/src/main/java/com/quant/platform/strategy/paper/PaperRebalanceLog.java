package com.quant.platform.strategy.paper;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 模拟盘再平衡日志表（记录每次再平衡触发与调仓，Route B 每子账户独立记录） */
@Data
@TableName("paper_rebalance_log")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaperRebalanceLog {

    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 模拟盘ID（子账户或组合根） */
    @TableField("paper_id")
    private Long paperId;

    /** 触发类型：SCHEDULE/THRESHOLD/MANUAL */
    @TableField("trigger_type")
    private String triggerType;

    /** 再平衡日期 */
    @TableField("rebalance_date")
    private LocalDate rebalanceDate;

    /** 再平衡前各策略权重/占比JSON */
    @TableField("before_allocation_json")
    private String beforeAllocationJson;

    /** 再平衡后各策略权重/占比JSON */
    @TableField("after_allocation_json")
    private String afterAllocationJson;

    /** 最大偏离度（小数，0.05=5%） */
    @TableField("max_drift_pct")
    private BigDecimal maxDriftPct;

    /** 实际调仓标的（逗号分隔） */
    @TableField("traded_symbols")
    private String tradedSymbols;

    /** 备注 */
    @TableField("note")
    private String note;

    /** 记录创建时间 */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
