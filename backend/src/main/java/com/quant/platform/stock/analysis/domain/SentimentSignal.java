package com.quant.platform.stock.analysis.domain;

import lombok.Data;

@Data
public class SentimentSignal {
    private Integer limitUpDays;
    private Integer limitUpStrength;
    private Integer brokenLimitUpCount20;
    private Double brokenLimitUpRate;
    private Boolean isStrongStock;
    private int sentimentScore;
}
