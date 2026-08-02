package com.quant.platform.system.dict;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;

/**
 * 字典类型（分类）实体
 */
@Data
public class SysDictType {
    private Long id;
    @NotBlank(message = "字典类型编码不能为空")
    private String dictType;
    @NotBlank(message = "字典类型名称不能为空")
    private String typeName;
    private String description;
    private Integer status;
    private Integer sort;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
