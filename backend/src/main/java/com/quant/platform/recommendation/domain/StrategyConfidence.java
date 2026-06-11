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
 *
 * 置信度计算（满分100）：
 *   - 近10期命中率 (0~40分)
 *   - 平均收益率正负 (0~25分)
 *   - 最大回撤控制 (0~20分)
 *   - 收益稳定性/波动率 (0~15分)
 *
 * 等级划分：
 *   - HIGH:    70~100  → 正常推荐
 *   - NORMAL:  50~69   → 正常推荐（显示提醒）
 *   - LOW:     30~49   → 降低topN、提高入选门槛
 *   - SUSPENDED: <30   → 建议暂停使用该策略
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

    // ── 维度得分 ──

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

    /** 近10期最大单日跌幅% */
    private BigDecimal maxDrawdownValue;

    /** 波动率/稳定性维度得分 (0~15) */
    private Integer volatilityScore;

    /** 近10期收益标准差% */
    private BigDecimal volatilityValue;

    // ── 元信息 ──

    /** 用于计算的推荐记录数 */
    private Integer sampleSize;

    /** 数据截止日期（最近一次追踪的推荐日期） */
    private LocalDate dataAsOfDate;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    // ── 辅助方法 ──

    /** 根据分数获取等级 */
    public static String getLevelByScore(int score) {
        if (score >= 70) return "HIGH";
        if (score >= 50) return "NORMAL";
        if (score >= 30) return "LOW";
        return "SUSPENDED";
    }

    /** 获取等级的中文名称 */
    public static String getLevelLabel(String level) {
        return switch (level) {
            case "HIGH" -> "高";
            case "NORMAL" -> "中等";
            case "LOW" -> "偏低";
            case "SUSPENDED" -> "建议暂停";
            default -> "未知";
        };
    }

    /** 获取等级对应的颜色 */
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
