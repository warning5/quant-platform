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

    /** 滑点比例（小数，默认0.002=0.2%） */
    @TableField("slippage_pct")
    private BigDecimal slippagePct;

    /** 滑点模型：NONE/FIXED，默认NONE */
    @TableField("slippage_model")
    private String slippageModel;

    /** 现金缓冲比例（小数，默认0.05=5%），买入分配时预留此比例不投入 */
    @TableField("cash_buffer_pct")
    private BigDecimal cashBufferPct;

    /** 再平衡频率：DAILY/WEEKLY/MONTHLY/THRESHOLD/VOL_ADAPTIVE/HYBRID */
    @TableField("rebalance_freq")
    private String rebalanceFreq;

    /** 再平衡偏离阈值（小数，默认0.05=5%），当前权重偏离目标超过此值触发调仓 */
    @TableField("rebalance_threshold")
    private BigDecimal rebalanceThreshold;

    /** 是否启用自动阻断（1=启用，0=仅预警），超限时自动阻止交易而非仅生成预警 */
    @TableField("auto_block_enabled")
    private Integer autoBlockEnabled;

    /** TWAP大单拆分阈值（股），超过此数量触发TWAP拆分，默认50000股 */
    @TableField("twap_threshold")
    private Integer twapThreshold;

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
            .slippagePct(new BigDecimal("0.002"))
            .slippageModel("NONE")
            .cashBufferPct(new BigDecimal("0.05"))
            .rebalanceFreq("DAILY")
            .rebalanceThreshold(new BigDecimal("0.05"))
            .autoBlockEnabled(1)  // 默认开启自动阻断
            .twapThreshold(50000)  // TWAP大单拆分阈值（默认50000股）
            .build();
    }
}
