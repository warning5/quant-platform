package com.quant.platform.calendar.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * 交易日历实体（仅存储例外日：节假日、手动标记）
 * 普通交易日和周末不存库，由代码逻辑自动判断
 */
@Setter
@Getter
@TableName("trade_calendar")
public class TradeCalendar {

    /** 交易日期 */
    @TableId(type = IdType.INPUT)
    private LocalDate tradeDate;

    /** 是否为交易日（true=交易日/false=非交易日） */
    private Boolean isTrading;

    /** 原因说明（如"端午节"、"国庆"、"临时停牌"等） */
    private String reason;

    /** 数据来源（AUTO=自动生成/MANUAL=手动设置） */
    private String source;

    public TradeCalendar() {
    }

    public TradeCalendar(LocalDate tradeDate, Boolean isTrading, String reason, String source) {
        this.tradeDate = tradeDate;
        this.isTrading = isTrading;
        this.reason = reason;
        this.source = source;
    }

}
