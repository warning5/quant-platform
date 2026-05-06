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
@TableName("paper_nav")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaperNav {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("paper_id")
    private Long paperId;

    @TableField("nav_date")
    private LocalDate navDate;

    @TableField("total_assets")
    private BigDecimal totalAssets;

    @TableField("daily_return")
    private BigDecimal dailyReturn;

    @TableField("cumulative_return")
    private BigDecimal cumulativeReturn;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
