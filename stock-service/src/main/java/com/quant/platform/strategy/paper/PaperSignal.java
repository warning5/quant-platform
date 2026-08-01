package com.quant.platform.strategy.paper;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 模拟盘交易信号表（买入卖出信号/价格/状态） */
@Data
@TableName("paper_signal")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaperSignal {

    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 模拟盘ID */
    @TableField("paper_id")
    private Long paperId;

    /** 信号生成日期 */
    @TableField("signal_date")
    private LocalDate signalDate;

    /** 因子数据日期 */
    @TableField("factor_date")
    private LocalDate factorDate;

    /** 股票代码 */
    private String code;

    /** 股票名称 */
    private String name;

    /** 信号方向：BUY/SELL */
    private String direction;

    /** 信号价格 */
    @TableField("signal_price")
    private BigDecimal signalPrice;

    /** 因子综合得分 */
    @TableField("factor_score")
    private BigDecimal factorScore;

    /** 信号原因/依据 */
    private String reason;

    /** 信号状态：PENDING/EXECUTED/SKIPPED/EXPIRED */
    private String status;

    /** 实际成交价格 */
    @TableField("executed_price")
    private BigDecimal executedPrice;

    /** 实际成交时间 */
    @TableField("executed_at")
    private LocalDateTime executedAt;

    /** 执行价与信号价的偏差百分比（小数，正=执行价更高，负=执行价更低） */
    @TableField("price_deviation_pct")
    private BigDecimal priceDeviationPct;

    /** 订单类型：MARKET(市价单)/LIMIT(限价单)/STOP(止损单)/STOP_LIMIT(止损限价单)/TRAILING_STOP(追踪止损)，默认MARKET */
    @TableField("order_type")
    private String orderType;

    /** 触发价格（限价单买入上限价/止损单触发价/止损限价单触发价） */
    @TableField("trigger_price")
    private BigDecimal triggerPrice;

    /** 限价（止损限价单的执行限价） */
    @TableField("limit_price")
    private BigDecimal limitPrice;

    /** 追踪止损回撤比例（小数，如0.05=5%），从最高价回撤此比例触发卖出 */
    @TableField("trail_pct")
    private BigDecimal trailPct;

    /** 追踪止损回撤金额（元），从最高价回撤此金额触发卖出 */
    @TableField("trail_amount")
    private BigDecimal trailAmount;

    /** 追踪止损记录的最高价（自买入后最高收盘价） */
    @TableField("highest_since_buy")
    private BigDecimal highestSinceBuy;

    /** 记录创建时间 */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
