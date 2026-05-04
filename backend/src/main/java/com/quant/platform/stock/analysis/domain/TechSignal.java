package com.quant.platform.stock.analysis.domain;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 技术面信号（缠论因子）
 */
@Data
public class TechSignal {
    
    /**
     * 股票代码
     */
    private String code;
    
    /**
     * 交易日期
     */
    private LocalDate tradeDate;
    
    /**
     * 缠论笔方向：UP/DOWN
     */
    private String penDir;
    
    /**
     * 趋势状态：BULLISH/BEARISH/SIDEWAYS
     */
    private String trend;
    
    /**
     * 缠论买卖信号：BUY/SELL/HOLD
     */
    private String chanSignal;
    
    /**
     * 中枢位置：UPPER/MIDDLE/LOWER
     */
    private String hubPos;
    
    /**
     * 笔的数量（近期）
     */
    private Integer penCount;
    
    /**
     * 均线多头排列：true/false
     */
    private Boolean maBullish;
    
    /**
     * MACD金叉：true/false
     */
    private Boolean macdGolden;
    
    /**
     * RSI值
     */
    private BigDecimal rsi;
    
    /**
     * 技术面评分
     */
    private int techScore;
}
