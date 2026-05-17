package com.quant.platform.stock.analysis.domain;

import lombok.Data;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Workflow A 综合分析报告数据聚合
 * 档一：轻量版报告模板填充
 */
@Data
public class WorkflowReport {

    /**
     * 股票代码
     */
    private String code;

    /**
     * 股票名称
     */
    private String name;

    /**
     * 报告生成时间
     */
    private String reportTime;

    /**
     * 四维度评分总览
     */
    private AnalysisOverview overview;

    /**
     * 估值历史分位
     */
    private Map<String, Object> valuationPercentile;

    /**
     * 研报分析
     */
    private Map<String, Object> researchAnalysis;

    /**
     * 同业对比
     */
    private Map<String, Object> peerComparison;

    /**
     * 多头论据列表
     */
    private List<BullBearArgument> bullArguments = new ArrayList<>();

    /**
     * 空头论据列表
     */
    private List<BullBearArgument> bearArguments = new ArrayList<>();

    /**
     * 综合结论
     */
    private String conclusion;

    /**
     * 综合评分（0-100）
     */
    private Integer totalScore;

    /**
     * 操作建议
     */
    private String actionName;

    /**
     * 建议仓位
     */
    private Integer position;

    public WorkflowReport() {
        this.reportTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    /**
     * 获取多头论据数量
     */
    public int getBullCount() {
        return bullArguments != null ? bullArguments.size() : 0;
    }

    /**
     * 获取空头论据数量
     */
    public int getBearCount() {
        return bearArguments != null ? bearArguments.size() : 0;
    }

    /**
     * 判断多空倾向
     */
    public String getBias() {
        int bull = getBullCount();
        int bear = getBearCount();
        if (bull > bear + 2) return "偏多";
        if (bear > bull + 2) return "偏空";
        if (bull > bear) return "中性偏多";
        if (bear > bull) return "中性偏空";
        return "中性";
    }
}
