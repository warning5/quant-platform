package com.quant.platform.factor.engine.chan;

import lombok.Getter;

/**
 * 缠论方向枚举
 */
@Getter
public enum Direction {
    UP(1),
    DOWN(-1);

    private final int value;

    Direction(int value) {
        this.value = value;
    }

}
