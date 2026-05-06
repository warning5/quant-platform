package com.quant.platform.strategy.paper;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("paper_position")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaperPosition {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("paper_id")
    private Long paperId;

    private String code;
    private String name;
    private Integer shares;

    @TableField("cost_price")
    private BigDecimal costPrice;

    @TableField("current_price")
    private BigDecimal currentPrice;

    @TableField("market_value")
    private BigDecimal marketValue;

    @TableField("profit_loss")
    private BigDecimal profitLoss;

    @TableField("profit_loss_pct")
    private BigDecimal profitLossPct;

    @TableField("buy_date")
    private LocalDate buyDate;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
