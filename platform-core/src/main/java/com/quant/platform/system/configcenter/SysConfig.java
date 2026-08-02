package com.quant.platform.system.configcenter;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;

/**
 * 系统参数配置实体
 * 业务通过 configKey 读取；configType 决定前端渲染的控件（STRING/NUMBER/BOOLEAN/JSON）。
 */
@Data
public class SysConfig {
    private Long id;
    @NotBlank(message = "配置键不能为空")
    private String configKey;
    private String configValue;
    private String configGroup;
    @NotBlank(message = "配置名称不能为空")
    private String configLabel;
    private String configType;
    private Integer enabled;
    private Integer sort;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
