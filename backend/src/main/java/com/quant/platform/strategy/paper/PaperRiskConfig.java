package com.quant.platform.strategy.paper;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("paper_risk_config")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaperRiskConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("paper_id")
    private Long paperId;

    /** 止损%，默认 8% */
    @TableField("stop_loss_pct")
    private BigDecimal stopLossPct;

    /** 止盈%，默认 30% */
    @TableField("take_profit_pct")
    private BigDecimal takeProfitPct;

    /** ATR 移动止损倍数，0=禁用 */
    @TableField("trailing_atr")
    private BigDecimal trailingAtr;

    /** 单股仓位上限%，默认 20% */
    @TableField("max_position_pct")
    private BigDecimal maxPositionPct;

    /** 单一行业仓位上限%，默认 35% */
    @TableField("max_industry_pct")
    private BigDecimal maxIndustryPct;

    /** 最大回撤%，默认 20% */
    @TableField("max_drawdown_pct")
    private BigDecimal maxDrawdownPct;

    /** 是否启用择时信号（0=禁用，1=启用） */
    @TableField("timing_enabled")
    private Integer timingEnabled;

    /** 基准指数代码，默认 000300 */
    @TableField("benchmark_code")
    private String benchmarkCode;

    /** 分配模式：equal/dynamic/kelly，默认 equal */
    @TableField("allocation_mode")
    private String allocationMode;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

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
