package com.quant.platform.stock.analysis.domain;

import lombok.Data;

import java.util.List;

/**
 * 评分明细（四维度评分结果）
 */
@Data
public class ScoreDetail {
    
    /**
     * 维度名称：tech/money/fundamental/sentiment
     */
    private String dimension;
    
    /**
     * 维度中文名
     */
    private String dimensionName;
    
    /**
     * 该维度得分
     */
    private int score;
    
    /**
     * 该维度满分
     */
    private int maxScore;
    
    /**
     * 评分项明细
     */
    private List<ScoreItem> items;
    
    /**
     * 评分项
     */
    @Data
    public static class ScoreItem {
        /**
         * 指标名称
         */
        private String label;
        
        /**
         * 指标值
         */
        private String value;
        
        /**
         * 得分
         */
        private int score;
        
        /**
         * 满分
         */
        private int maxScore;
        
        /**
         * 说明
         */
        private String desc;
    }
}
