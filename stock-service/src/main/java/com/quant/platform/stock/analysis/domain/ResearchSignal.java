package com.quant.platform.stock.analysis.domain;

import lombok.Data;

/**
 * 研报信号（机构观点）
 */
@Data
public class ResearchSignal {
    
    /**
     * 最新研报评级（买入/增持/中性/减持/卖出）
     */
    private String latestRating;
    
    /**
     * 研报评分（0-5分，由评级映射）
     */
    private int researchScore;
    
    /**
     * 近90天研报数量
     */
    private int reportCount;
    
    /**
     * 研报评级列表（最近5条，供前端展示）
     * 每条包含：rating, institution, reportDate, reportTitle
     */
    private java.util.List<java.util.Map<String, Object>> recentReports;
}
