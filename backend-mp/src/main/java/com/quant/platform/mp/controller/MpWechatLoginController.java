package com.quant.platform.mp.controller;

import com.quant.platform.common.dto.ApiResponse;
import com.quant.platform.mp.service.MpWechatAuthService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/mp/login")
public class MpWechatLoginController {

    private final MpWechatAuthService wechatAuthService;

    public MpWechatLoginController(MpWechatAuthService wechatAuthService) {
        this.wechatAuthService = wechatAuthService;
    }

    /**
     * 微信小程序登录：前端 wx.login 拿 code 后 POST { "code": "..." }
     * 免 MpAuthFilter 校验（uri 含 /login）。
     */
    @PostMapping
    public ApiResponse<Map<String, Object>> login(@RequestBody(required = false) Map<String, String> body) {
        String code = (body == null) ? null : body.get("code");
        if (code == null || code.isBlank()) {
            return ApiResponse.error(400, "缺少 code 参数");
        }
        return ApiResponse.success(wechatAuthService.miniLogin(code));
    }
}
