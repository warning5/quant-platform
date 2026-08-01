package com.quant.platform.system.online;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.session.TokenSign;
import cn.dev33.satoken.stp.StpUtil;
import com.quant.platform.common.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 在线用户管理控制器
 * 路径前缀 /system/online，权限 system:online:list / system:online:kick。
 * 基于 Sa-Token 的 Session 列表实现在线会话查询与强制下线。
 */
@Slf4j
@RestController
@RequestMapping("/system/online")
public class OnlineUserController {

    private final JdbcTemplate jdbcTemplate;

    public OnlineUserController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 在线用户列表：列出所有 login session，按 token 签名拆分为多设备行 */
    @GetMapping("/list")
    @SaCheckPermission("system:online:list")
    public ApiResponse<List<Map<String, Object>>> list() {
        // sessionId 形如 "login:session:" + loginId；keyword="" 匹配全部
        List<String> sessionIds = StpUtil.searchSessionId("", 0, 200, false);
        List<Map<String, Object>> result = new ArrayList<>();
        String currentToken = StpUtil.getTokenValue();
        for (String sessionId : sessionIds) {
            SaSession s = StpUtil.getSessionBySessionId(sessionId);
            Object loginIdObj = s.getLoginId();
            if (loginIdObj == null) continue;
            Long userId = toLong(loginIdObj);
            String username = lookupUsername(userId);
            long loginTime = s.getCreateTime();
            List<TokenSign> signs = s.getTokenSignList();
            if (signs != null && !signs.isEmpty()) {
                for (TokenSign sign : signs) {
                    Map<String, Object> row = new HashMap<>();
                    row.put("loginId", loginIdObj);
                    row.put("username", username);
                    row.put("loginTime", loginTime);
                    row.put("token", sign.getValue());
                    row.put("device", sign.getDevice());
                    row.put("current", sign.getValue() != null && sign.getValue().equals(currentToken));
                    result.add(row);
                }
            } else {
                Map<String, Object> row = new HashMap<>();
                row.put("loginId", loginIdObj);
                row.put("username", username);
                row.put("loginTime", loginTime);
                row.put("token", "-");
                row.put("device", "-");
                row.put("current", false);
                result.add(row);
            }
        }
        return ApiResponse.success(result);
    }

    /** 强制下线（按 loginId 踢出全部会话） */
    @PostMapping("/kick")
    @SaCheckPermission("system:online:kick")
    public ApiResponse<?> kick(@RequestParam String loginId) {
        StpUtil.kickout(loginId);
        return ApiResponse.success("已强制下线");
    }

    private Long toLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (Exception e) {
            return null;
        }
    }

    private String lookupUsername(Long userId) {
        if (userId == null) return "-";
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT COALESCE(nickname, username) FROM sys_user WHERE id = ?",
                    String.class, userId);
        } catch (Exception e) {
            return String.valueOf(userId);
        }
    }
}
