package com.quant.platform.strategy.paper;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 模拟盘现金流记录（出入金/分红/手续费） */
@Data
@TableName("paper_cash_flow")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaperCashFlow {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 模拟盘ID */
    @TableField("paper_id")
    private Long paperId;

    /** 流水日期 */
    @TableField("flow_date")
    private LocalDate flowDate;

    /** 金额（正=入账，负=出账） */
    @TableField("amount")
    private BigDecimal amount;

    /** 流水类型：DEPOSIT(入金)/WITHDRAW(出金)/DIVIDEND(分红)/FEE(手续费)/BUY_COST(买入)/SELL_INCOME(卖出) */
    @TableField("flow_type")
    private String flowType;

    /** 备注 */
    @TableField("note")
    private String note;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
