package com.quant.platform.mp.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 因子定义（只读视图，复用主后端 factor_definition 表）
 */
@Data
@TableName("factor_definition")
public class MpFactorDefinition {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("factor_code")
    private String factorCode;

    @TableField("factor_name")
    private String factorName;

    private String category;

    @TableField("factor_type")
    private String factorType;

    private String status;

    private String description;

    @TableField("stock_pool")
    private String stockPool;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
