package com.quant.platform.stock.analysis.domain;

import lombok.Data;

import java.util.List;

/**
 * 个股分析总览（聚合四维度信号）
 */
@Data
public class AnalysisOverview {
    
    /**
     * 股票代码
     */
    private String code;
    
    /**
     * 股票名称
     */
    private String name;
    
    /**
     * 当前价格
     */
    private String price;
    
    /**
     * 涨跌幅
     */
    private String changePercent;
    
    /**
     * 总分
     */
    private Integer totalScore;
    
    /**
     * 操作建议
     */
    private String action;
    
    /**
     * 操作建议中文
     */
    private String actionName;
    
    /**
     * 仓位建议（%）
     */
    private Integer position;
    
    /**
     * 操作时机
     */
    private String timing;

    /**
     * 风险提示
     */
    private String risks;
    
    /**
     * 四维度评分
     */
    private List<ScoreDetail> scoreDetails;
    
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

    /**
     * 研报信号（机构观点）
     */
    private ResearchSignal researchSignal;

    /**
     * 分析结论（四维度综合文字结论）
     */
    private String conclusion;
    
    /**
     * 反转条件（减仓/清仓时，列出回到买入信号需满足的条件）
     */
    private String reversalConditions;
    
    /**
     * 是否大盘蓝筹（总市值 ≥ 1000亿）
     */
    private boolean blueChip;
}
