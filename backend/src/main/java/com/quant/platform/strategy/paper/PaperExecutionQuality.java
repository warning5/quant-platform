package com.quant.platform.strategy.paper;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 模拟盘执行质量记录 */
@Data
@TableName("paper_execution_quality")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaperExecutionQuality {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("paper_id")
    private Long paperId;

    @TableField("signal_id")
    private Long signalId;

    private String code;
    private String direction;

    @TableField("signal_price")
    private BigDecimal signalPrice;

    @TableField("executed_price")
    private BigDecimal executedPrice;

    @TableField("price_deviation")
    private BigDecimal priceDeviation;

    @TableField("price_deviation_pct")
    private BigDecimal priceDeviationPct;

    @TableField("slippage_cost")
    private BigDecimal slippageCost;

    @TableField("commission")
    private BigDecimal commission;

    @TableField("total_cost")
    private BigDecimal totalCost;

    @TableField("execution_time")
    private LocalDateTime executionTime;

    @TableField("fill_rate")
    private BigDecimal fillRate;
}
