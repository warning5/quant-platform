package com.quant.platform.system.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 系统菜单 / 权限
 * menu_type: 0=目录 1=菜单 2=按钮
 */
@Data
@TableName("sys_menu")
public class SysMenu {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 父菜单ID，0=顶级 */
    private Long parentId;

    private String menuName;

    /** 0=目录 1=菜单 2=按钮 */
    private Integer menuType;

    /** 路由路径 */
    private String path;

    /** 前端组件 key */
    private String component;

    private String icon;

    /** 权限标识，如 system:user:list */
    private String permission;

    private Integer sort;

    /** 1=显示 0=隐藏 */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;

    /** 子菜单（非表字段） */
    @TableField(exist = false)
    private List<SysMenu> children;
}
