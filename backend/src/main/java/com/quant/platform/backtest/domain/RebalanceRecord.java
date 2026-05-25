package com.quant.platform.backtest.domain;

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
 * 调仓记录实体
 * 每次调仓写入一条记录，记录调仓前后的持仓变化。
 */
@Data
@TableName("rebalance_record")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RebalanceRecord implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 关联任务 ID
     */
    @TableField("task_id")
    private Long taskId;

    /**
     * 调仓日期
     */
    @TableField("rebalance_date")
    private LocalDate rebalanceDate;

    // ===== 持仓快照（JSON）=====

    /**
     * 调仓前持仓快照
     * JSON 格式: [{"symbol":"000001.SZ","shares":1000,"cost":10.50},...]
     */
    @TableField("old_positions_json")
    private String oldPositionsJson;

    /**
     * 调仓后目标持仓
     * JSON 格式: [{"symbol":"000001.SZ","weight":0.05,"score":1.23},...]
     */
    @TableField("new_positions_json")
    private String newPositionsJson;

    // ===== 交易明细（JSON）=====

    /**
     * 买入明细
     * JSON 格式: [{"symbol":"000001.SZ","price":10.50,"shares":1000,"amount":10500},...]
     */
    @TableField("buys_json")
    private String buysJson;

    /**
     * 卖出明细
     * JSON 格式: [{"symbol":"000001.SZ","price":11.00,"shares":1000,"amount":11000,"pnl":500},...]
     */
    @TableField("sells_json")
    private String sellsJson;

    // ===== 组合快照 =====

    /**
     * 当日现金（元）
     */
    @TableField("cash")
    private BigDecimal cash;

    /**
     * 总资产（元）
     */
    @TableField("total_value")
    private BigDecimal totalValue;

    /**
     * 当日净值（从 1.0 起）
     */
    @TableField("nav")
    private BigDecimal nav;

    /**
     * 当日收益率
     */
    @TableField("daily_return")
    private BigDecimal dailyReturn;

    /**
     * 创建时间
     */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
