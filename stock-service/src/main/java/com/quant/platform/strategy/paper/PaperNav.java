package com.quant.platform.strategy.paper;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 模拟盘净值表（日总资产/日收益率/累计收益率） */
@Data
@TableName("paper_nav")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaperNav {

    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 模拟盘ID */
    @TableField("paper_id")
    private Long paperId;

    /** 净值日期 */
    @TableField("nav_date")
    private LocalDate navDate;

    /** 当日总资产（元） */
    @TableField("total_assets")
    private BigDecimal totalAssets;

    /** 日收益率（%） */
    @TableField("daily_return")
    private BigDecimal dailyReturn;

    /** 累计收益率（%） */
    @TableField("cumulative_return")
    private BigDecimal cumulativeReturn;

    /** 记录创建时间 */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
