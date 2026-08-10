package com.quant.platform.mp.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.quant.platform.common.dto.ApiResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 开发环境专用：颁发 Sa-Token 用户 token，供本机 curl/联调使用。
 * 仅 dev profile 生效；生产环境无此端点。
 */
@Profile("dev")
@RestController
@RequestMapping("/mp/dev")
public class MpDevAuthController {

    @GetMapping("/token")
    public ApiResponse<Map<String, Object>> token(@RequestParam(defaultValue = "1") Long userId) {
        StpUtil.login(userId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token", StpUtil.getTokenValue());
        m.put("tokenName", StpUtil.getTokenName());
        m.put("userId", userId);
        return ApiResponse.success(m);
    }
}
