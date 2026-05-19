package com.quant.platform.stock.analysis.domain;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 资金面信号（量能异动 + 主力资金流向）
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
     * 主力净流入（元，正=净流入，负=净流出）
     */
    private BigDecimal netMain;
    
    /**
     * 主力净流入占比（%）
     */
    private BigDecimal netMainPct;
    
    /**
     * 超大单净流入（元）
     */
    private BigDecimal netHuge;
    
    /**
     * 大单净流入（元）
     */
    private BigDecimal netBig;
    
    /**
     * 主力资金状态：INFLOW=主力流入 / OUTFLOW=主力流出 / NEUTRAL=无数据
     */
    private String mainFlowStatus;
    
    /**
     * 资金面评分
     */
    private int moneyScore;
    
    /**
     * 量能状态：HIGH/MEDIUM/LOW
     */
    private String volumeStatus;

    /**
     * 融资余额变化百分比（最新 vs 5日前）
     */
    private BigDecimal marginChgPct;

    /**
     * 股东人数变化百分比（最新一季度 vs 上一季度，负值=筹码集中）
     */
    private BigDecimal shareholderChangePct;

    /**
     * 5日累计主力净流入（元）
     */
    private BigDecimal netMain5d;

    /**
     * 5日累计主力净流入占比（%）
     */
    private BigDecimal netMainPct5d;

    /**
     * 内外盘比（外盘/内盘，>1买方强势）
     */
    private BigDecimal outerInnerRatio;

    /**
     * 内外盘趋势（3日均值判断）：BUYER_STRONG / BUYER_SLIGHT / BALANCED / SELLER_SLIGHT / SELLER_STRONG / NO_DATA
     */
    private String bidAskTrend;
}
