package com.quant.platform.factor.engine.chan;

import lombok.Getter;

/**
 * 走势类型
 */
@Getter
public enum TrendType {
    CONSOLIDATION(0),
    UPTREND(1),
    DOWNTREND(-1);

    private final int value;

    TrendType(int value) {
        this.value = value;
    }

}
