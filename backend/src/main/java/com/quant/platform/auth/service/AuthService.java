package com.quant.platform.auth.service;

import cn.dev33.satoken.stp.StpUtil;
import com.quant.platform.common.exception.BusinessException;
import com.quant.platform.system.entity.SysMenu;
import com.quant.platform.system.entity.SysUser;
import com.quant.platform.system.mapper.SysUserMapper;
import com.quant.platform.system.service.MenuService;
import com.quant.platform.auth.dto.LoginResult;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 账号密码登录与会话发放
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserMapper userMapper;
    private final MenuService menuService;
    private final PasswordEncoder passwordEncoder;

    /**
     * 账号密码登录
     */
    public LoginResult login(String username, String password) {
        SysUser user = userMapper.selectByUsername(username);
        if (user == null || (user.getStatus() != null && user.getStatus() == 0)) {
            throw new BusinessException("账号不存在或已禁用");
        }
        if (user.getPassword() == null || !passwordEncoder.matches(password, user.getPassword())) {
            throw new BusinessException("账号或密码错误");
        }
        return issueToken(user);
    }

    /**
     * 已解析出用户后发放登录态并构建返回结果（微信登录复用）
     */
    public LoginResult issueToken(SysUser user) {
        StpUtil.login(user.getId());
        user.setLastLoginTime(LocalDateTime.now());
        userMapper.updateById(user);
        return buildResult(user, StpUtil.getTokenValue());
    }

    /**
     * 获取当前登录用户信息（刷新用）
     */
    public LoginResult getMe() {
        StpUtil.checkLogin();
        Long uid = Long.parseLong(StpUtil.getLoginIdAsString());
        SysUser user = userMapper.selectById(uid);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        return buildResult(user, StpUtil.getTokenValue());
    }

    public void logout() {
        StpUtil.logout();
    }

    private LoginResult buildResult(SysUser user, String token) {
        LoginResult result = new LoginResult();
        result.setTokenName(StpUtil.getTokenName());
        result.setToken(token);
        result.setUserId(user.getId());
        result.setUsername(user.getUsername());
        result.setNickname(user.getNickname());
        result.setAvatar(user.getAvatar());
        result.setRoles(StpUtil.getRoleList());
        result.setPermissions(StpUtil.getPermissionList());
        List<SysMenu> menus = menuService.getUserMenuTree(user.getId());
        result.setMenus(menus);
        return result;
    }
}
