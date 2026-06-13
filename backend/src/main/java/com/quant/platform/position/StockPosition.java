package com.quant.platform.position;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("stock_position")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockPosition {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String stockCode;
    private String stockName;
    private String direction;
    private BigDecimal buyPrice;
    private LocalDate buyDate;
    private Integer quantity;
    private BigDecimal currentPrice;
    private BigDecimal marketValue;
    private BigDecimal costValue;
    private BigDecimal profitLoss;
    private BigDecimal profitLossPct;
    private BigDecimal stopLossPrice;
    private BigDecimal takeProfitPrice;
    private String source;
    private String notes;
    private String status;
    private BigDecimal sellPrice;
    private LocalDate sellDate;
    private BigDecimal realizedPl;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
