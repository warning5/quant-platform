package com.quant.platform.strategy.paper;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 模拟盘风控配置表（止损/止盈/集中度/行业暴露/回撤限制） */
@Data
@TableName("paper_risk_config")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaperRiskConfig {

    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 模拟盘ID */
    @TableField("paper_id")
    private Long paperId;

    /** 止损比例（%）默认8% */
    @TableField("stop_loss_pct")
    private BigDecimal stopLossPct;

    /** 止盈比例（%）默认30% */
    @TableField("take_profit_pct")
    private BigDecimal takeProfitPct;

    /** ATR移动止损倍数（0=禁用） */
    @TableField("trailing_atr")
    private BigDecimal trailingAtr;

    /** 单股仓位上限（%）默认20% */
    @TableField("max_position_pct")
    private BigDecimal maxPositionPct;

    /** 单一行业仓位上限（%）默认35% */
    @TableField("max_industry_pct")
    private BigDecimal maxIndustryPct;

    /** 最大回撤限制（%）默认20% */
    @TableField("max_drawdown_pct")
    private BigDecimal maxDrawdownPct;

    /** 是否启用大盘择时（0=禁用，1=启用） */
    @TableField("timing_enabled")
    private Integer timingEnabled;

    /** 基准指数代码 */
    @TableField("benchmark_code")
    private String benchmarkCode;

    /** 资金分配模式：equal/dynamic/kelly */
    @TableField("allocation_mode")
    private String allocationMode;

    /** 配置创建时间 */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 配置更新时间 */
    @TableField("updated_at")
    private LocalDateTime updatedAt;

    /** 返回带默认值的配置对象 */
    public static PaperRiskConfig defaults(Long paperId) {
        return PaperRiskConfig.builder()
            .paperId(paperId)
            .stopLossPct(new BigDecimal("0.08"))
            .takeProfitPct(new BigDecimal("0.30"))
            .trailingAtr(BigDecimal.ZERO)
            .maxPositionPct(new BigDecimal("0.20"))
            .maxIndustryPct(new BigDecimal("0.30"))
            .maxDrawdownPct(new BigDecimal("0.15"))
            .timingEnabled(0)
            .benchmarkCode("000300")
            .allocationMode("equal")
            .build();
    }
}
