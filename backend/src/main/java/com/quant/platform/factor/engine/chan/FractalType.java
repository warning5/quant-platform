package com.quant.platform.factor.engine.chan;

import lombok.Getter;

/**
 * 分型类型
 */
@Getter
public enum FractalType {
    TOP(1),
    BOTTOM(-1);

    private final int value;

    FractalType(int value) {
        this.value = value;
    }

}
