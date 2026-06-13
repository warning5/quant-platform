package com.quant.platform.watchlist.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 自选股观察池实体
 */
@Data
@TableName("stock_watchlist")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockWatchlist implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 股票代码（不含市场后缀） */
    private String stockCode;

    /** 股票名称 */
    private String stockName;

    /** 分组名称（默认"default"） */
    private String groupName;

    /** 加入原因 */
    private String reason;

    /** 来源（MANUAL=手动添加, RECOMMENDATION=推荐, SCREEN=选股） */
    private String source;

    /** 关联的推荐批次ID */
    private Long recommendationBatchId;

    /** 目标买入价 */
    private BigDecimal targetBuyPrice;

    /** 止损价 */
    private BigDecimal stopLossPrice;

    /** 目标卖出价 */
    private BigDecimal targetSellPrice;

    /** 观测到期日（加入后N天到期提醒决策） */
    private LocalDate watchEndDate;

    /** 备注 */
    private String notes;

    /** 排序序号 */
    private Integer sortOrder;

    /** 是否已归档（0=活跃, 1=归档） */
    private Integer archived;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
