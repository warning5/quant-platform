package com.quant.platform.llm.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * LLM分析结果实体
 */
@Data
@TableName("llm_analysis")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmAnalysis {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String stockCode;
    private String stockName;
    private LocalDate analysisDate;
    private String model;
    private String recommendation;

    private BigDecimal buyPriceLow;
    private BigDecimal buyPriceHigh;
    private BigDecimal stopLoss;
    private BigDecimal targetPrice;

    private String riskLevel;
    private String logic;
    private String positionAdvice;
    private String timeHorizon;
    private String catalysts;
    private String risks;

    private String rawResponse;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
