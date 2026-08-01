package com.quant.platform.backtest.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 回测推荐配置（用于创建模拟盘时自动带入参数，打通回测->模拟盘链路）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BacktestRecommendedConfig {

    /** 止损比例（小数，如 0.05 = 5%） */
    private BigDecimal stopLossPct;

    /** 止盈比例（小数，如 0.10 = 10%） */
    private BigDecimal takeProfitPct;

    /** 最大持仓数量 */
    private Integer maxPositions;

    /** 调仓频率：WEEKLY / BIWEEKLY / MONTHLY */
    private String rebalanceFreq;

    /** 手续费率 */
    private BigDecimal commissionRate;

    /** 滑点 */
    private BigDecimal slippageRate;

    /** 基准指数代码 */
    private String benchmarkCode;

    /** 单股仓位上限（小数，如 0.20 = 20%） */
    private BigDecimal maxPositionPct;

    /** 最大回撤限制（小数，如 0.15 = 15%） */
    private BigDecimal maxDrawdownPct;

    /** 是否启用大盘择时（0=禁用，1=启用） */
    private Integer timingEnabled;

    /** 资金分配模式：equal / dynamic */
    private String allocationMode;

    /** 推荐理由（人类可读） */
    private String reason;
}
