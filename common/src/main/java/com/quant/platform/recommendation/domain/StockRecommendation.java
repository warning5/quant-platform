package com.quant.platform.recommendation.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 股票推荐记录（共享实体，backend 和 backend-mp 共用）
 */
@Data
@TableName("stock_recommendation")
public class StockRecommendation {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 策略ID（关联 strategy_definition 表） */
    private Long strategyId;

    /** 股票代码（纯代码，无后缀） */
    private String stockCode;

    /** 股票名称 */
    private String stockName;

    /** 推荐日期 */
    private LocalDate recommendDate;

    /** 排名（从1开始） */
    private Integer rankNum;

    /** 因子综合得分（百分位 0~1） */
    private Double factorScore;

    /** 个股分析得分（0~109） */
    private Integer analysisScore;

    /** 个股分析得分百分位（0~1） */
    private Double analysisScorePct;

    /** 融合最终得分（0~1） */
    private Double finalScore;

    /** 因子得分融合权重 */
    private Double factorWeight;

    /** 分析得分融合权重 */
    private Double analysisWeight;

    /** 市场环境: BULL/BEAR/SIDEWAYS */
    private String regime;

    /** 沪深300 MA20 */
    private Double indexMa20;

    /** 沪深300 MA60 */
    private Double indexMa60;

    /** 沪深300收盘价 */
    private Double indexClose;

    /** 行业 */
    private String industry;

    /** 总市值（元） */
    private Double marketCap;

    /** 当日收盘价 */
    private Double closePrice;

    /** 推荐买入价格 */
    private Double suggestedBuyPrice;

    /** 建议止损价 */
    private Double suggestedStopLoss;

    /** 建议止盈价 */
    private Double suggestedTakeProfit;

    /** 建议目标价 */
    private Double suggestedTargetPrice;

    /** 建议仓位比例（0~1） */
    private Double suggestedPositionPct;

    /** 当日涨跌幅% */
    private Double changePercent;

    /** 行业相对强度 */
    private Double industryMomentum;

    /** 行业Regime */
    private String industryRegime;

    /** 技术面得分 */
    private Integer technicalScore;

    /** 资金面得分 */
    private Integer capitalScore;

    /** 事件面得分 */
    private Integer eventScore;

    /** 基本面得分 */
    private Integer fundamentalScore;

    /** 操作建议: BUY/HOLD/SELL */
    private String actionTag;

    /** 买入理由摘要 */
    private String buyReason;

    /** 次日收益率% */
    private Double nextDayReturn;

    /** 次日超额收益率%（vs 沪深300） */
    private Double nextDayExcessReturn;

    /** 一周收益率% */
    private Double nextWeekReturn;

    /** 一周超额收益率%（vs 沪深300） */
    private Double nextWeekExcessReturn;

    /** 一月收益率% */
    private Double nextMonthReturn;

    /** 一月超额收益率%（vs 沪深300） */
    private Double nextMonthExcessReturn;

    /** 追踪更新时间 */
    private LocalDateTime trackingUpdatedAt;

    /** 风险评分（0-15分） */
    private Integer riskScore;

    /** 流动性评分（0-10分） */
    private Integer liquidityScore;

    /** 高相关行业分组名（瞬态，不持久化） */
    @TableField(exist = false)
    private String corrGroup;

    /** 是否因行业分散化限制被降权（瞬态，不持久化） */
    @TableField(exist = false)
    private Boolean diversificationDemoted;

    /** 该批次推荐质量标签（瞬态，不持久化） */
    @TableField(exist = false)
    private String qualityTag;

    /** 各因子百分位排名 JSON */
    private String factorRanksJson;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    /** 权重模式: FIXED(固定权重) / ICW(动态IC权重) */
    private String weightMode;
}
