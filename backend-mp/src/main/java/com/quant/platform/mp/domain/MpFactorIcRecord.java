package com.quant.platform.mp.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;

/**
 * 因子 IC 记录（只读视图，复用主后端 factor_ic_record 表）
 */
@Data
@TableName("factor_ic_record")
public class MpFactorIcRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("factor_code")
    private String factorCode;

    @TableField("trade_date")
    private LocalDate tradeDate;

    @TableField("ic_value")
    private Double icValue;

    @TableField("ic20d_avg")
    private Double ic20dAvg;

    @TableField("ic60d_avg")
    private Double ic60dAvg;

    @TableField("ir20d")
    private Double ir20d;

    @TableField("ir60d")
    private Double ir60d;

    @TableField("forward_days")
    private Integer forwardDays;
}
