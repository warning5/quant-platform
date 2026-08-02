package com.quant.platform.system.dict;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;

/**
 * 字典数据项实体
 * 业务代码消费 dictValue；dictLabel/color/ext_json 用于展示；sort 用于排序。
 */
@Data
public class SysDictData {
    private Long id;
    @NotBlank(message = "字典类型不能为空")
    private String dictType;
    @NotBlank(message = "字典值不能为空")
    private String dictValue;
    @NotBlank(message = "字典标签不能为空")
    private String dictLabel;
    private Integer sort;
    private String color;
    private String extJson;
    private String remark;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
