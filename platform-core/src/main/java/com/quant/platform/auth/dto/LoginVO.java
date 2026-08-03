package com.quant.platform.auth.dto;

import com.quant.platform.system.entity.SysMenu;
import lombok.Data;

import java.util.List;

/**
 * 登录视图（含 token、用户信息、角色、权限、菜单树）
 */
@Data
public class LoginVO {
    private String tokenName;
    private String token;
    // 仅后端流转用（写 httpOnly cookie），前端不应读取/持久化
    private String refreshToken;
    private Long userId;
    private String username;
    private String nickname;
    private String avatar;
    private List<String> roles;
    private List<String> permissions;
    private List<SysMenu> menus;
}
