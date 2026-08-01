package com.quant.platform.strategy.paper;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 模拟盘持仓表（代码/股数/成本价/现价/浮盈亏） */
@Data
@TableName("paper_position")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaperPosition {

    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 模拟盘ID */
    @TableField("paper_id")
    private Long paperId;

    /** 股票代码 */
    private String code;

    /** 股票名称 */
    private String name;

    /** 持有股数 */
    private Integer shares;

    /** 持仓成本价 */
    @TableField("cost_price")
    private BigDecimal costPrice;

    /** 当前市价（收盘后更新） */
    @TableField("current_price")
    private BigDecimal currentPrice;

    /** 持仓市值（元） */
    @TableField("market_value")
    private BigDecimal marketValue;

    /** 持仓浮盈亏（元） */
    @TableField("profit_loss")
    private BigDecimal profitLoss;

    /** 持仓浮盈亏比例（%） */
    @TableField("profit_loss_pct")
    private BigDecimal profitLossPct;

    /** 买入日期 */
    @TableField("buy_date")
    private LocalDate buyDate;

    /** 最后更新时间 */
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
