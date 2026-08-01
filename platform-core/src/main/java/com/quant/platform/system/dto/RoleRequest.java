package com.quant.platform.system.dto;

import lombok.Data;

/**
 * 角色新增/更新请求
 */
@Data
public class RoleRequest {
    private Long id;
    private String roleCode;
    private String roleName;
    private String remark;
    private Integer status;
}
