package com.quant.platform.stock.analysis.domain;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 资金面信号（成交量异动）
 */
@Data
public class MoneyFlowSignal {
    
    /**
     * 量比（当日成交量/5日均量）
     */
    private BigDecimal volumeRatio;
    
    /**
     * 换手率偏离度（当日换手率 - 20日平均换手率）
     */
    private BigDecimal turnoverDeviation;
    
    /**
     * 当日换手率
     */
    private BigDecimal turnoverRate;
    
    /**
     * 5日平均换手率
     */
    private BigDecimal turnoverRate5d;
    
    /**
     * 资金面评分
     */
    private int moneyScore;
    
    /**
     * 量能状态：HIGH/MEDIUM/LOW
     */
    private String volumeStatus;
}
