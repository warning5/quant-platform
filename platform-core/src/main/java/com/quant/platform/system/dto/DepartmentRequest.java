package com.quant.platform.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 部门新增/编辑请求（白名单 DTO）
 * dept_path / dept_level 由服务端根据 parentId 计算，不接收客户端输入。
 */
@Data
public class DepartmentRequest {
    /** 编辑时必填，新增时忽略（自增） */
    private Long id;

    @NotNull(message = "父部门不能为空")
    private Long parentId;

    @NotBlank(message = "部门名称不能为空")
    @Size(max = 50, message = "部门名称不能超过50个字符")
    private String deptName;

    private Integer sort;

    @NotNull(message = "状态不能为空")
    private Integer status;
}
