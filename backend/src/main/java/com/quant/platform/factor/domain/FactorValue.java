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
