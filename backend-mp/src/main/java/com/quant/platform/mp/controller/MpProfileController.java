package com.quant.platform.mp.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.quant.platform.common.dto.ApiResponse;
import com.quant.platform.mp.domain.SysUser;
import com.quant.platform.mp.mapper.MpUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 小程序「我的」资料接口：读 / 改当前登录用户资料（复用 sys_user 表）。
 * 当前用户从 Sa-Token 登录态取 userId。
 */
@RestController
@RequestMapping("/mp/me")
@RequiredArgsConstructor
public class MpProfileController {

    private final MpUserMapper userMapper;

    /**
     * 获取当前登录用户资料
     */
    @GetMapping
    public ApiResponse<Map<String, Object>> get() {
        SysUser user = currentUser();
        if (user == null) {
            return ApiResponse.error(401, "未登录");
        }
        return ApiResponse.success(toMap(user));
    }

    /**
     * 更新当前登录用户资料（仅开放昵称/头像/邮箱/手机号）
     */
    @PutMapping
    public ApiResponse<Map<String, Object>> update(@RequestBody Map<String, String> body) {
        SysUser user = currentUser();
        if (user == null) {
            return ApiResponse.error(401, "未登录");
        }
        if (body.containsKey("nickname")) {
            user.setNickname(body.get("nickname"));
        }
        if (body.containsKey("avatar")) {
            user.setAvatar(body.get("avatar"));
        }
        if (body.containsKey("email")) {
            user.setEmail(body.get("email"));
        }
        if (body.containsKey("phone")) {
            user.setPhone(body.get("phone"));
        }
        userMapper.updateById(user);
        return ApiResponse.success(toMap(user));
    }

    private SysUser currentUser() {
        try {
            Long userId = StpUtil.getLoginIdAsLong();
            return userId == null ? null : userMapper.selectById(userId);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> toMap(SysUser u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("nickname", u.getNickname());
        m.put("avatar", u.getAvatar());
        m.put("email", u.getEmail());
        m.put("phone", u.getPhone());
        m.put("status", u.getStatus());
        m.put("wechatType", u.getWechatType());
        m.put("lastLoginTime", u.getLastLoginTime() != null ? u.getLastLoginTime().toString() : null);
        return m;
    }
}
