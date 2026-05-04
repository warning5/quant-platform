package com.quant.platform.stock.analysis.domain;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 基本面信号
 */
@Data
public class FundamentalSignal {
    
    /**
     * 市盈率TTM
     */
    private BigDecimal peTtm;
    
    /**
     * 市净率
     */
    private BigDecimal pb;
    
    /**
     * ROE（净资产收益率）
     */
    private BigDecimal roe;
    
    /**
     * 营收同比增速（%）
     */
    private BigDecimal revenueYoy;
    
    /**
     * 净利润同比增速（%）
     */
    private BigDecimal netProfitYoy;
    
    /**
     * 毛利率
     */
    private BigDecimal grossMargin;
    
    /**
     * 资产负债率
     */
    private BigDecimal debtRatio;
    
    /**
     * 基本面评分
     */
    private int fundamentalScore;

    /**
     * 研报评分（0-5分，由最新评级映射，计入基本面总分）
     */
    private int researchScore;
}
