package com.quant.platform.factor.ic.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 因子 IC/IR 记录
 */
@Data
@TableName("factor_ic_record")
public class FactorIcRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String factorCode;
    private LocalDate tradeDate;

    /** IC值（Spearman Rank相关系数） */
    private Double icValue;

    /** IC 20日/60日均值 */
    private Double ic20dAvg;
    private Double ic60dAvg;

    /** IR (IC均值/IC标准差) */
    private Double ir20d;
    private Double ir60d;

    /** 截面股票数量 */
    private Integer stockCount;

    private LocalDateTime createdAt;
}
