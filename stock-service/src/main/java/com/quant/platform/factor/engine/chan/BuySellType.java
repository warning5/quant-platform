package com.quant.platform.factor.engine.chan;

import lombok.Getter;

/**
 * 买卖点类型
 */
@Getter
public enum BuySellType {
    FIRST_BUY(1),
    SECOND_BUY(2),
    THIRD_BUY(3),
    FIRST_SELL(-1),
    SECOND_SELL(-2),
    THIRD_SELL(-3);

    private final int value;

    BuySellType(int value) {
        this.value = value;
    }

    public boolean isBuy() {
        return value > 0;
    }

    public boolean isSell() {
        return value < 0;
    }
}
