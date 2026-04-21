package com.quant.platform.screen.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 多因子选股结果
 */
@Data
@Builder
public class ScreenResult {

    /** 实际选股日期 */
    private LocalDate screenDate;

    /** 使用的因子列表（含权重） */
    private List<ScreenRequest.FactorWeight> factors;

    /** 参与选股的股票总数 */
    private int candidateCount;

    /** 选出的股票列表，按综合得分降序 */
    private List<StockScore> stocks;

    /** 因子有效性统计（因子代码 -> 有值的股票数量） */
    private Map<String, Integer> factorCoverage;

    /**
     * 单只股票的综合评分结果
     */
    @Data
    @Builder
    public static class StockScore {
        /** 排名（从1开始）*/
        private int rank;
        /** 股票代码 */
        private String symbol;
        /** 股票名称 */
        private String name;
        /** 综合得分（加权合成后的百分位，0~1） */
        private double compositeScore;
        /** 各因子原始百分位排名（因子代码 -> rankValue） */
        private Map<String, Double> factorRanks;
        /** 各因子原始值（因子代码 -> value） */
        private Map<String, Double> factorValues;

        // ── 买入价建议 ──
        /** 当前价格 */
        private java.math.BigDecimal currentPrice;
        /** 建议买入价 */
        private java.math.BigDecimal suggestPrice;
        /** 建议买入区间下限 */
        private java.math.BigDecimal suggestPriceLow;
        /** 建议买入区间上限 */
        private java.math.BigDecimal suggestPriceHigh;
        /** 止损价 */
        private java.math.BigDecimal stopLoss;
        /** 止损百分比 */
        private java.math.BigDecimal stopLossPercent;
        /** 第一止盈价 */
        private java.math.BigDecimal takeProfit1;
        /** 第一止盈百分比 */
        private java.math.BigDecimal takeProfit1Percent;
        /** 第二止盈价 */
        private java.math.BigDecimal takeProfit2;
        /** 第二止盈百分比 */
        private java.math.BigDecimal takeProfit2Percent;
        /** ATR(14) */
        private java.math.BigDecimal atr;
        /** 风险等级: low/medium/high */
        private String riskLevel;
        /** 风险提示列表 */
        private java.util.List<String> risks;
        /** 买入理由摘要 */
        private String buyReason;
        /** 技术支撑位明细 */
        private Map<String, Object> techLevels;
        /** 估值支撑位明细 */
        private Map<String, Object> valuationLevels;
    }
}
