package com.quant.platform.auth.controller;

import com.quant.platform.auth.dto.LoginRequest;
import com.quant.platform.auth.dto.LoginResult;
import com.quant.platform.auth.dto.WechatMiniLoginRequest;
import com.quant.platform.auth.service.AuthService;
import com.quant.platform.auth.service.WechatAuthService;
import com.quant.platform.common.dto.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口（公开，无需登录）
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final WechatAuthService wechatAuthService;

    /** 账号密码登录 */
    @PostMapping("/login")
    public ApiResponse<LoginResult> login(@RequestBody LoginRequest req) {
        return ApiResponse.success(authService.login(req.getUsername(), req.getPassword()));
    }

    /** 小程序登录 */
    @PostMapping("/wechat/mini/login")
    public ApiResponse<LoginResult> wechatMiniLogin(@RequestBody WechatMiniLoginRequest req) {
        return ApiResponse.success(wechatAuthService.miniLogin(req.getCode()));
    }

    /** 网站应用扫码：返回微信授权 URL（前端打开弹窗） */
    @GetMapping("/wechat/website/authorize")
    public ApiResponse<String> wechatWebsiteAuthorize() {
        return ApiResponse.success(wechatAuthService.buildWebsiteAuthorizeUrl());
    }

    /** 网站应用扫码回调：渲染页面把 token 通过 postMessage 发给父窗口 */
    @GetMapping("/wechat/website/callback")
    public ResponseEntity<String> wechatWebsiteCallback(@RequestParam("code") String code,
                                                        @RequestParam(value = "state", required = false) String state,
                                                        @RequestParam(value = "redirect", required = false) String redirect) {
        LoginResult result = wechatAuthService.handleWebsiteCallback(code);
        String html = buildPopupHtml(result.getToken(), redirect);
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("text/html;charset=utf-8"))
                .body(html);
    }

    /** 公众号网页授权：重定向到微信授权页 */
    @GetMapping("/wechat/mp/authorize")
    public void wechatMpAuthorize(HttpServletResponse response) throws java.io.IOException {
        response.sendRedirect(wechatAuthService.buildMpAuthorizeUrl());
    }

    /** 公众号网页授权回调：重定向回前端并带上 token */
    @GetMapping("/wechat/mp/callback")
    public void wechatMpCallback(@RequestParam("code") String code,
                                 HttpServletResponse response) throws java.io.IOException {
        LoginResult result = wechatAuthService.handleMpCallback(code);
        String base = wechatAuthService.getFrontendBaseUrl();
        response.sendRedirect(base + "/login?wechat=success&token=" + result.getToken());
    }

    /** 当前登录用户信息（用于刷新） */
    @GetMapping("/me")
    public ApiResponse<LoginResult> me() {
        return ApiResponse.success(authService.getMe());
    }

    /** 退出登录 */
    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        authService.logout();
        return ApiResponse.ok();
    }

    private String buildPopupHtml(String token, String redirect) {
        String jsRedirect = (redirect != null && !redirect.isBlank())
                ? "try{var ru=new URL(window.location.href).searchParams.get('redirect');if(ru){window.top.location.href=ru+(ru.indexOf('?')>=0?'&':'?')+'wechat=success&token='+encodeURIComponent('" + token + "');}}catch(e){}"
                : "";
        return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><title>微信登录</title></head>"
                + "<body><p style=\"font-family:system-ui;padding:24px;text-align:center;color:#666\">登录成功，正在跳转…</p>"
                + "<script>(function(){var d={type:'wechat-login',token:'" + token + "',tokenName:'satoken'};"
                + "try{if(window.opener){window.opener.postMessage(d,'*');}}catch(e){}"
                + "try{if(window.parent&&window.parent!==window){window.parent.postMessage(d,'*');}}catch(e){}"
                + jsRedirect
                + "setTimeout(function(){try{window.close();}catch(e){}},800);})();</script>"
                + "</body></html>";
    }
}
