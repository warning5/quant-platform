package com.quant.platform.stock.analysis.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 催化剂条目（正面/风险事件 + 触发条件）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CatalystItem {

    /** 催化剂描述 */
    private String description;

    /** 类型：POSITIVE(正面) / NEGATIVE(风险) */
    private String type;

    /** 触发条件（什么情况下会兑现） */
    private String trigger;

    /** 重要性：1-5星 */
    private int importance;

    /** 来源：FINANCE/NEWS/EVENT/MACRO */
    private String source;
}
