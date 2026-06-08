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

    /**
     * 目标价
     */
    private String targetPrice;

    /**
     * 介入价格（基于MA20支撑位计算的建议买入价）
     */
    private String entryPrice;

    /**
     * 止损价
     */
    private String stopLossPrice;

    /**
     * 信心水平（低/中/高）
     */
    private String confidenceLevel;

    // ========== P0-P2 新增字段 ==========

    /**
     * 第二目标价（估值回归位，基于PE均值回归）
     */
    private String targetPrice2;

    /**
     * 极端目标价（PB=1x极端估值）
     */
    private String extremeTargetPrice;

    /**
     * 分批执行方案描述
     */
    private String executionPlan;

    /**
     * 保守分析师评分（0-10）
     */
    private Integer conservativeScore;

    /**
     * 中性分析师评分（0-10）
     */
    private Integer neutralScore;

    /**
     * 激进分析师评分（0-10）
     */
    private Integer aggressiveScore;

    /**
     * 尾部风险列表
     */
    private List<TailRisk> tailRisks;

    /**
     * 催化剂追踪矩阵
     */
    private List<CatalystItem> catalysts;

    /**
     * 分析师三角额外信息：保守分析师仓位建议
     */
    private String conservativePosition;

    /**
     * 分析师三角额外信息：保守分析师描述
     */
    private String conservativeDesc;

    /**
     * 分析师三角额外信息：中性分析师仓位建议
     */
    private String neutralPosition;

    /**
     * 分析师三角额外信息：中性分析师描述
     */
    private String neutralDesc;

    /**
     * 分析师三角额外信息：激进分析师仓位建议
     */
    private String aggressivePosition;

    /**
     * 分析师三角额外信息：激进分析师描述
     */
    private String aggressiveDesc;

    /**
     * 多空辩论结论文本
     */
    private String bullBearConclusion;

    // ========== P1-2 风险/流动性评分字段 ==========

    /**
     * 最大回撤(%)
     */
    private Double maxDrawdown;

    /**
     * 20日波动率(%)
     */
    private Double volatility20d;

    /**
     * ATR（平均真实波幅）
     */
    private Double atr;

    /**
     * 20日均成交额(元)
     */
    private Double avgAmount20d;

    /**
     * 20日换手率(%)
     */
    private Double turnoverRate20d;
}
