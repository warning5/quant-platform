package com.quant.platform.auth.dto;

import com.quant.platform.system.entity.SysMenu;
import lombok.Data;

import java.util.List;

/**
 * 登录结果（含 token、用户信息、角色、权限、菜单树）
 */
@Data
public class LoginResult {
    private String tokenName;
    private String token;
    private Long userId;
    private String username;
    private String nickname;
    private String avatar;
    private List<String> roles;
    private List<String> permissions;
    private List<SysMenu> menus;
}
