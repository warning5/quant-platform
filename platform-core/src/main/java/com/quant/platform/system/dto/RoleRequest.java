package com.quant.platform.system.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 角色新增/更新请求
 */
@Data
public class RoleRequest {
    private Long id;
    @NotBlank(message = "角色编码不能为空")
    private String roleCode;
    @NotBlank(message = "角色名称不能为空")
    private String roleName;
    private String remark;
    private Integer status;
}
