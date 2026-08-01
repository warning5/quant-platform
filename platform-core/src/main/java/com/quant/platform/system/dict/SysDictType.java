package com.quant.platform.system.dict;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 字典类型（分类）实体
 */
@Data
public class SysDictType {
    private Long id;
    private String dictType;
    private String typeName;
    private String description;
    private Integer status;
    private Integer sort;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
