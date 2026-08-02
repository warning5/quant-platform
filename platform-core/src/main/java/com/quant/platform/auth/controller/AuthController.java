package com.quant.platform.auth.controller;

import com.quant.platform.auth.dto.ChangePasswordRequest;
import com.quant.platform.auth.dto.LoginRequest;
import com.quant.platform.auth.dto.LoginResult;
import com.quant.platform.auth.dto.ProfileVO;
import com.quant.platform.auth.dto.UpdateProfileRequest;
import com.quant.platform.auth.dto.WechatMiniLoginRequest;
import com.quant.platform.auth.service.AuthService;
import com.quant.platform.auth.service.LoginSecurityService;
import com.quant.platform.auth.service.WechatAuthService;
import com.quant.platform.common.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
    private final LoginSecurityService loginSecurityService;

    /** 账号密码登录（IP 与 User-Agent 由服务端获取，供锁定与审计使用） */
    @PostMapping("/login")
    public ApiResponse<LoginResult> login(@Valid @RequestBody LoginRequest req, HttpServletRequest request) {
        String ip = getClientIp(request);
        String userAgent = request.getHeader("User-Agent");
        return ApiResponse.success(authService.login(req.getUsername(), req.getPassword(), ip,
                req.getCaptchaId(), req.getCaptchaCode(), userAgent));
    }

    /** 获取图形验证码（渐进式：登录失败达阈值后前端调用） */
    @PostMapping("/captcha")
    public ApiResponse<LoginSecurityService.CaptchaResult> captcha() {
        return ApiResponse.success(loginSecurityService.generateCaptcha());
    }

    /** 从请求中提取真实客户端 IP（兼容反向代理 X-Forwarded-For） */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isBlank() && !"unknown".equalsIgnoreCase(ip)) {
            return ip.split(",")[0].trim();
        }
        ip = request.getHeader("X-Real-IP");
        if (ip != null && !ip.isBlank() && !"unknown".equalsIgnoreCase(ip)) {
            return ip.trim();
        }
        return request.getRemoteAddr();
    }

    /** 小程序登录 */
    @PostMapping("/wechat/mini/login")
    public ApiResponse<LoginResult> wechatMiniLogin(@Valid @RequestBody WechatMiniLoginRequest req) {
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

    /** 获取当前登录用户的个人资料（不含密码） */
    @GetMapping("/profile")
    public ApiResponse<ProfileVO> profile() {
        return ApiResponse.success(authService.getProfile());
    }

    /** 更新当前登录用户自己的资料（昵称/邮箱/手机/头像） */
    @PutMapping("/profile")
    public ApiResponse<Void> updateProfile(@Valid @RequestBody UpdateProfileRequest req) {
        authService.updateProfile(req);
        return ApiResponse.ok();
    }

    /** 自助修改密码（校验原密码 + 新密码复杂度） */
    @PostMapping("/change-password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest req) {
        authService.changePassword(req);
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
