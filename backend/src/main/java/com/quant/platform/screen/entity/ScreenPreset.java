package com.quant.platform.screen.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 因子选股预设组合
 */
@Data
@TableName("screen_preset")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScreenPreset implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("preset_name")
    private String presetName;

    @TableField("description")
    private String description;

    @TableField("factor_config")
    private String factorConfig;

    @TableField("is_builtin")
    private Integer isBuiltin;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
