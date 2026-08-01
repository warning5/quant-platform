package com.quant.platform.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 菜单新增/编辑请求（白名单 DTO）
 * 仅暴露可写字段；id 由 edit 传入、createTime/deleted 由系统维护，均不接收客户端输入。
 */
@Data
public class MenuRequest {
    /** 编辑时必填，新增时忽略（自增） */
    private Long id;

    @NotNull(message = "父菜单不能为空")
    private Long parentId;

    @NotBlank(message = "菜单名称不能为空")
    @Size(max = 50, message = "菜单名称不能超过50个字符")
    private String menuName;

    @NotNull(message = "菜单类型不能为空")
    private Integer menuType;

    @Size(max = 200, message = "路由路径过长")
    private String path;

    @Size(max = 200, message = "组件标识过长")
    private String component;

    @Size(max = 100, message = "图标标识过长")
    private String icon;

    @Size(max = 100, message = "权限标识过长")
    private String permission;

    private Integer sort;

    @NotNull(message = "状态不能为空")
    private Integer status;
}
