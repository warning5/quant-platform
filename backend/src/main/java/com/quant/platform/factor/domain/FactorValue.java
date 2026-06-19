package com.quant.platform.factor.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 因子值存储实体
 */
@Data
@TableName("factor_value")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FactorValue implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 因子代码
     */
    @TableField("factor_code")
    private String factorCode;

    /**
     * 股票代码
     */
    @TableField("symbol")
    private String symbol;

    /**
     * 计算日期
     */
    @TableField("calc_date")
    private LocalDate calcDate;

    /**
     * 因子值
     */
    @TableField("factor_val")
    private BigDecimal factorVal;

    /**
     * 财报发布日期（仅季度财务因子有意义，日频因子为 null）
     * 用于判断因子数据新鲜度：筛选时只用已发布的财报数据
     * 注意：此字段只存在于 ClickHouse，MySQL factor_value 表无此列
     * exist = false 让 MyBatis-Plus 跳过它，避免 MySQL 查询报错
     */
    @TableField(exist = false)
    private LocalDate announceDate;

    /**
     * 横截面排名（百分位）
     */
    @TableField("rank_value")
    private BigDecimal rankValue;

    /**
     * Z-Score标准化值
     */
    @TableField("z_score")
    private BigDecimal zScore;

    /**
     * 创建时间
     */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
