package com.quant.platform.mp.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统用户（与 backend 的 sys_user 表共用同一张表，字段保持一致）
 * 此处独立放在 mp 包，使 backend-mp 模块自包含，不反向依赖 backend。
 */
@Data
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录账号 */
    private String username;

    /** BCrypt 密码，纯微信用户可为空 */
    private String password;

    /** 昵称 */
    private String nickname;

    /** 头像 URL */
    private String avatar;

    private String email;

    private String phone;

    /** 1=启用 0=禁用 */
    private Integer status;

    private String wechatOpenid;

    private String wechatUnionid;

    /** 0=无 1=网站应用 2=公众号 3=小程序 */
    private Integer wechatType;

    private LocalDateTime lastLoginTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
