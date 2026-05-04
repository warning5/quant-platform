package com.quant.platform.stock.analysis.domain;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 日线数据行映射（用于ClickHouse查询）
 */
@Data
public class DailyBarRow {
    
    private String code;
    
    private LocalDate tradeDate;
    
    private BigDecimal openPrice;
    
    private BigDecimal closePrice;
    
    private BigDecimal highPrice;
    
    private BigDecimal lowPrice;
    
    private BigDecimal preClose;
    
    private Long volume;
    
    private BigDecimal amount;
    
    private BigDecimal changePercent;
    
    private BigDecimal turnoverRate;
    
    private BigDecimal peTtm;
    
    private BigDecimal pb;
}
