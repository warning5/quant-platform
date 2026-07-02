package com.quant.platform.strategy.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 策略定义实体
 */
@Data
@TableName("strategy_definition")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StrategyDefinition implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 策略代码（唯一）
     */
    @TableField("strategy_code")
    private String strategyCode;

    /**
     * 策略名称
     */
    @TableField("strategy_name")
    private String strategyName;

    /**
     * 策略描述
     */
    @TableField("description")
    private String description;

    /**
     * 策略类型
     */
    @TableField("strategy_type")
    private StrategyType strategyType;

    /**
     * 策略状态
     */
    @TableField("status")
    private StrategyStatus status;

    /**
     * 调仓频率: DAILY, WEEKLY, MONTHLY
     */
    @TableField("rebalance_frequency")
    private String rebalanceFrequency;

    /**
     * 最大持仓数量
     */
    @TableField("max_position_count")
    private Integer maxPositionCount;

    /**
     * 仓位大小类型: EQUAL, FACTOR_WEIGHTED, CUSTOM
     */
    @TableField("position_size_type")
    private String positionSizeType;

    /**
     * 止损比例
     */
    @TableField("stop_loss_pct")
    private BigDecimal stopLossPct;

    /**
     * 止盈比例
     */
    @TableField("stop_profit_pct")
    private BigDecimal stopProfitPct;

    /**
     * 最大回撤控制
     */
    @TableField("max_drawdown_pct")
    private BigDecimal maxDrawdownPct;

    /**
     * 因子权重配置（JSON）
     */
    @TableField("factor_config_json")
    private String factorConfigJson;

    /**
     * Groovy策略脚本
     */
    @TableField("script_code")
    private String scriptCode;

    /**
     * 选股过滤条件（JSON）
     */
    @TableField("filter_config_json")
    private String filterConfigJson;

    /**
     * 版本号
     */
    @TableField("version")
    private Integer version;

    /**
     * 作者
     */
    @TableField("author")
    private String author;

    /**
     * 创建时间
     */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 策略类型枚举
     */
    public enum StrategyType {
        FACTOR_LONG,        // 因子多头选股
        LONG_SHORT,         // 多空策略
        MARKET_NEUTRAL,     // 市场中性
        MOMENTUM,           // 动量策略
        MEAN_REVERSION,     // 均值回归
        PATTERN,            // 形态驱动策略
        CUSTOM              // 自定义脚本策略
    }

    /**
     * 策略状态枚举
     */
    public enum StrategyStatus {
        DRAFT, TESTING, ACTIVE, DEPRECATED
    }
}
