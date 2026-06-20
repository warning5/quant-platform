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
     * 净利率（%，来自 stock_financial_indicator.net_profit_margin）
     */
    private BigDecimal netProfitMargin;

    /**
     * 营收绝对值（元，最新一期，来自 stock_income.total_revenue）
     */
    private BigDecimal totalRevenue;

    /**
     * 净利润绝对值（元，最新一期，来自 stock_income.np_parent_company_owners）
     */
    private BigDecimal netProfitAbs;

    /**
     * 商誉（元，最新一期，来自 stock_balance.goodwill）
     */
    private BigDecimal goodwill;

    /**
     * 存货（元，最新一期，来自 stock_balance.inventory）
     */
    private BigDecimal inventory;

    /**
     * 货币资金（元，最新一期，来自 stock_balance.cash_and_equivalents）
     */
    private BigDecimal monetaryCapital;

    /**
     * 自由现金流（元，最新一期，来自 stock_cashflow.free_cash_flow）
     */
    private BigDecimal freeCashFlow;
    
    /**
     * 基本面评分
     */
    private int fundamentalScore;

    /**
     * 研报评分（0-5分，由最新评级映射，计入基本面总分）
     */
    private int researchScore;

    /**
     * 近90天研报数量（用于研报覆盖热度评分，0-4分）
     */
    private int reportCount;

    // ========== 新增基本面指标（补充1-5） ==========

    /**
     * PE历史分位（%，0-100）
     */
    private BigDecimal pePercentile;

    /**
     * PB历史分位（%，0-100）
     */
    private BigDecimal pbPercentile;

    /**
     * 扣非归母净利润（元）— 用于扣非增速计算
     */
    private BigDecimal deductedNetProfit;

    /**
     * 扣非净利润同比增速（%）— 从 stock_income.deducted_np_parent_company 计算
     */
    private BigDecimal deductedNpYoY;

    /**
     * 经营现金流/净利润比值（>1=现金流好）
     */
    private BigDecimal operatingCfToNp;

    /**
     * 经营现金流/负债比值
     */
    private BigDecimal operatingCfToDebt;

    /**
     * 流动比率（>1.5=健康）
     */
    private BigDecimal currentRatio;

    /**
     * 速动比率（>1=健康）
     */
    private BigDecimal quickRatio;

    /**
     * 应收账款周转天数（天，越低=回款越好）
     */
    private BigDecimal arTurnoverDays;

    /**
     * 财务数据报告期（如 2025-09-30）
     */
    private String endDate;

    /**
     * 所属行业（来自 stock_info，用于判断金融/非金融）
     */
    private String industry;
}
