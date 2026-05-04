package com.quant.platform.stock.analysis.domain;

import lombok.Data;
import java.util.List;

/**
 * 交易信号（综合评分结果）
 */
@Data
public class TradingSignal {
    
    /**
     * 股票代码
     */
    private String code;
    
    /**
     * 股票名称
     */
    private String name;
    
    /**
     * 交易日期
     */
    private String tradeDate;
    
    /**
     * 总分（0-100）
     */
    private int totalScore;
    
    /**
     * 四维度评分明细
     */
    private List<ScoreDetail> scoreDetails;
    
    /**
     * 操作建议：STRONG_BUY/BUY/HOLD/REDUCE/CLEAR
     */
    private String action;
    
    /**
     * 操作建议中文
     */
    private String actionName;
    
    /**
     * 仓位建议（0-100%）
     */
    private Integer position;
    
    /**
     * 置信度（0-100）
     */
    private Integer confidence;
    
    /**
     * 建议时机
     */
    private String timing;
    
    /**
     * 风险提示
     */
    private String risks;
    
    /**
     * 技术面信号
     */
    private TechSignal techSignal;
    
    /**
     * 资金面信号
     */
    private MoneyFlowSignal moneySignal;
    
    /**
     * 事件面信号
     */
    private SentimentSignal sentimentSignal;
    
    /**
     * 基本面信号
     */
    private FundamentalSignal fundamentalSignal;
}
