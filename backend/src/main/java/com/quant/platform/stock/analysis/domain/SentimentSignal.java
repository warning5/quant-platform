package com.quant.platform.stock.analysis.domain;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class SentimentSignal {
    private Integer limitUpDays;
    private Integer limitUpStrength;
    private Integer brokenLimitUpCount20;
    private Double brokenLimitUpRate;
    private Boolean isStrongStock;
    private int sentimentScore;

    // 大盘蓝筹事件面专用字段
    private BigDecimal marginChgPct;       // 融资余额变化百分比
    private BigDecimal lhbInstitutionNet;   // 龙虎榜机构净买入（元）
    private BigDecimal holderChangePct;      // 股东户数变化百分比

    // 事件面增强字段（所有股票通用）
    private BigDecimal lhbNetAmount;        // 龙虎榜净买入金额（元）
    private Integer lhbAppearCount;         // 近20日龙虎榜上榜次数
    private Integer noticePositiveCount;    // 近30日正面公告数（回购/增持/业绩预增）
    private Integer noticeNegativeCount;    // 近30日负面公告数（减持/定增/业绩预降）
}
