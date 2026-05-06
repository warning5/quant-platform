package com.quant.platform.strategy.paper;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("paper_trading")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaperTrading {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("strategy_id")
    private Long strategyId;

    @TableField("strategy_code")
    private String strategyCode;

    @TableField("status")
    private String status; // RUNNING/PAUSED/STOPPED

    @TableField("initial_capital")
    private BigDecimal initialCapital;

    @TableField("current_capital")
    private BigDecimal currentCapital;

    @TableField("total_assets")
    private BigDecimal totalAssets;

    @TableField("position_count")
    private Integer positionCount;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
