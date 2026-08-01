package com.quant.platform.stock.analysis.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 尾部风险条目（暴露度×跌幅矩阵）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TailRisk {

    /** 风险名称 */
    private String name;

    /** 发生概率（百分比文本，如 "5-10%"） */
    private String probability;

    /** 影响程度：致命/毁灭性/重大/严重/中等 */
    private String impact;

    /** 潜在跌幅（百分比文本，如 "30-50%"） */
    private String potentialDecline;

    /** 风险触发条件 */
    private String triggerCondition;

    /** 量化指标（如"存货57亿/总资产24%"） */
    private String metric;

    /** 风险分组：VALUATION/FINANCIAL/BUSINESS/EXTERNAL */
    private String category;
}
