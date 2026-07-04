package com.quant.platform.strategy.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 策略定义实体（共享，backend 和 backend-mp 共用）
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

    @TableField("strategy_code")
    private String strategyCode;

    @TableField("strategy_name")
    private String strategyName;

    @TableField("description")
    private String description;

    @TableField("strategy_type")
    private StrategyType strategyType;

    @TableField("status")
    private StrategyStatus status;

    @TableField("rebalance_frequency")
    private String rebalanceFrequency;

    @TableField("max_position_count")
    private Integer maxPositionCount;

    @TableField("position_size_type")
    private String positionSizeType;

    @TableField("stop_loss_pct")
    private BigDecimal stopLossPct;

    @TableField("stop_profit_pct")
    private BigDecimal stopProfitPct;

    @TableField("max_drawdown_pct")
    private BigDecimal maxDrawdownPct;

    @TableField("factor_config_json")
    private String factorConfigJson;

    @TableField("script_code")
    private String scriptCode;

    @TableField("filter_config_json")
    private String filterConfigJson;

    @TableField("version")
    private Integer version;

    @TableField("author")
    private String author;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public enum StrategyType {
        FACTOR_LONG, LONG_SHORT, MARKET_NEUTRAL, MOMENTUM, MEAN_REVERSION, PATTERN, CUSTOM
    }

    public enum StrategyStatus {
        DRAFT, TESTING, ACTIVE, DEPRECATED
    }
}
