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

    /** 记录创建时间 */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
