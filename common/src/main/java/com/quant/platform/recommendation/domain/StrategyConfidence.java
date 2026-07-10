package com.quant.platform.recommendation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 策略置信度
 * 基于历史追踪表现，为每个策略计算置信度分数，
 * 低置信度时自动降低推荐数量或提示用户风险。
 */
@Data
@TableName("strategy_confidence")
public class StrategyConfidence {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 策略ID */
    private Long strategyId;

    /**
     * 置信度等级: HIGH / NORMAL / LOW / SUSPENDED
     */
    private String level;

    /** 综合置信度分数 (0~100) */
    private Integer score;

    /** 近10期命中率维度得分 (0~40) */
    private Integer hitRateScore;

    /** 近10期实际命中率 (0~1) */
    private BigDecimal hitRateValue;

    /** 平均收益率维度得分 (0~25) */
    private Integer returnScore;

    /** 近10期平均收益率% */
    private BigDecimal avgReturnValue;

    /** 最大回撤维度得分 (0~20) */
    private Integer drawdownScore;

    /** 近10期P5分位回撤%（样本<20时为最大单日跌幅%） */
    private BigDecimal maxDrawdownValue;

    /** 波动率/稳定性维度得分 (0~15) */
    private Integer volatilityScore;

    /** 近10期收益标准差% */
    private BigDecimal volatilityValue;

    /** 用于计算的推荐记录数 */
    private Integer sampleSize;

    /** 数据截止日期 */
    private LocalDate dataAsOfDate;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    public static String getLevelByScore(int score) {
        if (score >= 75) return "HIGH";
        if (score >= 55) return "NORMAL";
        if (score >= 35) return "LOW";
        return "SUSPENDED";
    }

    public static String getLevelLabel(String level) {
        return switch (level) {
            case "HIGH" -> "高";
            case "NORMAL" -> "中等";
            case "LOW" -> "偏低";
            case "SUSPENDED" -> "建议暂停";
            default -> "未知";
        };
    }

    public static String getLevelColor(String level) {
        return switch (level) {
            case "HIGH" -> "#3f8600";
            case "NORMAL" -> "#597ef7";
            case "LOW" -> "#fa8c16";
            case "SUSPENDED" -> "#cf1322";
            default -> "#8c8c8c";
        };
    }
}
