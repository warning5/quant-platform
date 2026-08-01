package com.quant.platform.backtest.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;

/**
 * Walk-Forward 验证结果实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalkForwardResult implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 训练期开始日期 */
    private LocalDate trainStart;

    /** 训练期结束日期 */
    private LocalDate trainEnd;

    /** 验证期开始日期 */
    private LocalDate validateStart;

    /** 验证期结束日期 */
    private LocalDate validateEnd;

    /** 轮次编号 */
    private int round;

    /** 训练期IC均值 */
    private Double trainIcMean;

    /** 验证期IC均值 */
    private Double validateIcMean;

    /** 验证期选股收益率(%) */
    private Double validateReturn;

    /** 验证期超额收益(%) */
    private Double excessReturn;

    /** 验证期基准收益率(%) */
    private Double benchmarkReturn;

    /** 验证期最大回撤(%) */
    private Double maxDrawdown;

    /** 验证期选股数量 */
    private Integer stockCount;

    /** 各因子训练期IC */
    private String factorIcJson;

    /** 各因子验证期IC */
    private String factorValidateIcJson;
}
