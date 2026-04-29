package com.quant.platform.factor.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 因子定义实体
 */
@Data
@TableName("factor_definition")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FactorDefinition implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 因子代码（唯一标识）
     */
    @TableField("factor_code")
    private String factorCode;

    /**
     * 因子名称
     */
    @TableField("factor_name")
    private String factorName;

    /**
     * 因子描述
     */
    @TableField("description")
    private String description;

    /**
     * 因子分类
     */
    @TableField("category")
    private FactorCategory category;

    /**
     * 因子类型
     */
    @TableField("factor_type")
    private FactorType factorType;

    /**
     * 状态
     */
    @TableField("status")
    private FactorStatus status;

    /**
     * Groovy计算脚本
     */
    @TableField("script_code")
    private String scriptCode;

    /**
     * 参数配置JSON
     */
    @TableField("parameters_json")
    private String parametersJson;

    /**
     * 版本号
     */
    @TableField("version")
    private Integer version;

    /**
     * 创建者
     */
    @TableField("author")
    private String author;

    /**
     * 适配股票池
     */
    @TableField("stock_pool")
    private String stockPool;

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
     * 因子分类枚举
     */
    public enum FactorCategory {
        MOMENTUM,       // 动量因子
        VALUE,          // 价值因子
        QUALITY,        // 质量因子
        VOLATILITY,     // 波动率因子
        TECHNICAL,      // 技术因子
        FUNDAMENTAL,    // 基本面因子
        SENTIMENT,      // 情绪因子
        CHANTHEORY,     // 缠论因子
        LIQUIDITY,      // 流动性因子
        VOLUME_PRICE,   // 量价因子
        CUSTOM          // 自定义
    }

    /**
     * 因子类型枚举
     */
    public enum FactorType {
        BUILTIN,    // 内置因子
        SCRIPTED,   // 脚本因子
        COMPOSITE   // 合成因子
    }

    /**
     * 因子状态枚举
     */
    public enum FactorStatus {
        DRAFT,      // 草稿
        TESTING,    // 测试中
        ACTIVE,     // 已激活
        DEPRECATED  // 已废弃
    }
}
