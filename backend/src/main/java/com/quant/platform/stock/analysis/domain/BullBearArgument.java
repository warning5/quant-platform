package com.quant.platform.stock.analysis.domain;

import lombok.Data;

/**
 * 多空辩论论据
 */
@Data
public class BullBearArgument {

    /**
     * 规则名称（如：PE>50、营收增速>20%）
     */
    private String rule;

    /**
     * 所属维度（技术/基本面/情绪/资金/研报）
     */
    private String dimension;

    /**
     * 论据描述
     */
    private String description;

    /**
     * 论据强度（1-5星）
     */
    private int strength;

    public BullBearArgument() {
    }

    public BullBearArgument(String rule, String dimension, String description) {
        this.rule = rule;
        this.dimension = dimension;
        this.description = description;
        this.strength = 3;
    }

    public BullBearArgument(String rule, String dimension, String description, int strength) {
        this.rule = rule;
        this.dimension = dimension;
        this.description = description;
        this.strength = strength;
    }
}
