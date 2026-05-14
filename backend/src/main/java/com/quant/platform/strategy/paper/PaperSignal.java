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
@TableName("paper_signal")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaperSignal {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("paper_id")
    private Long paperId;

    @TableField("signal_date")
    private LocalDate signalDate;

    @TableField("factor_date")
    private LocalDate factorDate;

    private String code;
    private String name;
    private String direction; // BUY/SELL

    @TableField("signal_price")
    private BigDecimal signalPrice;

    @TableField("factor_score")
    private BigDecimal factorScore;

    private String reason;
    private String status; // PENDING/EXECUTED/SKIPPED/EXPIRED

    @TableField("executed_price")
    private BigDecimal executedPrice;

    @TableField("executed_at")
    private LocalDateTime executedAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
