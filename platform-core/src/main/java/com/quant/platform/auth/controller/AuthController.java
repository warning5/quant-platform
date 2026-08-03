package com.quant.platform.auth.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.quant.platform.auth.dto.ChangePasswordRequest;
import com.quant.platform.auth.dto.LoginRequest;
import com.quant.platform.auth.dto.LoginVO;
import com.quant.platform.auth.dto.ProfileVO;
import com.quant.platform.auth.dto.UpdateProfileRequest;
import com.quant.platform.auth.dto.WechatMiniLoginRequest;
import com.quant.platform.auth.service.AuthService;
import com.quant.platform.auth.service.LoginSecurityService;
import com.quant.platform.auth.service.RefreshTokenService;
import com.quant.platform.auth.service.WechatAuthService;
import com.quant.platform.common.dto.ApiResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
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
    private final RefreshTokenService refreshTokenService;

    // cookie 安全属性：生产 HTTPS 环境将 SA_TOKEN_COOKIE_SECURE 设为 true
    @Value("${SA_TOKEN_COOKIE_SECURE:false}")
    private boolean cookieSecure;
    // refresh token 有效期（秒），与 application.yml 中 auth.refresh-token-ttl-seconds 对应
    @Value("${auth.refresh-token-ttl-seconds:604800}")
    private long refreshTtlSeconds;
    // access token 有效期（秒），与 sa-token.timeout 对应，用于同步 cookie maxAge
    @Value("${sa-token.timeout:1800}")
    private int accessTokenMaxAge;

    /** 账号密码登录（IP 与 User-Agent 由服务端获取，供锁定与审计使用） */
    @PostMapping("/login")
    public ApiResponse<LoginVO> login(@Valid @RequestBody LoginRequest req, HttpServletRequest request,
            HttpServletResponse response) {
        String ip = getClientIp(request);
        String userAgent = request.getHeader("User-Agent");
        LoginVO vo = authService.login(req.getUsername(), req.getPassword(), ip,
                req.getCaptchaId(), req.getCaptchaCode(), userAgent);
        // #6：token 写入 httpOnly cookie（JS 读不到，防 XSS 窃取）；refreshToken 走同名 httpOnly cookie
        writeAuthCookies(response, vo);
        vo.setRefreshToken(null); // 已写入 cookie，响应体不再携带，避免任何日志泄露
        return ApiResponse.success(vo);
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
    public ApiResponse<LoginVO> wechatMiniLogin(@Valid @RequestBody WechatMiniLoginRequest req,
            HttpServletResponse response) {
        LoginVO vo = wechatAuthService.miniLogin(req.getCode());
        writeAuthCookies(response, vo);
        vo.setRefreshToken(null);
        return ApiResponse.success(vo);
    }

    /** 网站应用扫码：返回微信授权 URL（前端打开弹窗） */
    @GetMapping("/wechat/website/authorize")
    public ApiResponse<String> wechatWebsiteAuthorize() {
        return ApiResponse.success(wechatAuthService.buildWebsiteAuthorizeUrl());
    }

    /** 网站应用扫码回调：种好 cookie 后渲染页面通知父窗口刷新（不再通过 postMessage 传递 token 字符串） */
    @GetMapping("/wechat/website/callback")
    public ResponseEntity<String> wechatWebsiteCallback(@RequestParam("code") String code,
                                                        @RequestParam(value = "state", required = false) String state,
                                                        @RequestParam(value = "redirect", required = false) String redirect,
                                                        HttpServletResponse response) {
        LoginVO result = wechatAuthService.handleWebsiteCallback(code);
        writeAuthCookies(response, result);
        String html = buildPopupHtml(redirect);
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("text/html;charset=utf-8"))
                .body(html);
    }

    /** 公众号网页授权：重定向到微信授权页 */
    @GetMapping("/wechat/mp/authorize")
    public void wechatMpAuthorize(HttpServletResponse response) throws java.io.IOException {
        response.sendRedirect(wechatAuthService.buildMpAuthorizeUrl());
    }

    /** 公众号网页授权回调：种好 cookie 后重定向回前端登录页（不再把 token 塞进 URL） */
    @GetMapping("/wechat/mp/callback")
    public void wechatMpCallback(@RequestParam("code") String code,
                                 HttpServletResponse response) throws java.io.IOException {
        LoginVO result = wechatAuthService.handleMpCallback(code);
        writeAuthCookies(response, result);
        String base = wechatAuthService.getFrontendBaseUrl();
        response.sendRedirect(base + "/login?wechat=success");
    }

    /** 当前登录用户信息（用于刷新）；token 仅经 httpOnly cookie 携带，响应体剥离 */
    @GetMapping("/me")
    public ApiResponse<LoginVO> me() {
        LoginVO vo = authService.getMe();
        vo.setToken(null);
        vo.setTokenName(null);
        vo.setRefreshToken(null);
        return ApiResponse.success(vo);
    }

    /** 退出登录 */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        // 撤销 refreshToken（内存登记），避免被继续用来换新 token
        String rt = readCookie(request, "satoken_refresh");
        if (rt != null) {
            refreshTokenService.remove(rt);
        }
        authService.logout();
        // 清除浏览器侧 cookie
        clearCookie(response, "satoken");
        clearCookie(response, "satoken_refresh");
        return ApiResponse.ok();
    }

    /**
     * 静默刷新（#29）：用 httpOnly 的 refreshToken 换新 access token，前端无感知。
     * 匿名可访问（access token 可能已过期），需在 SaTokenConfigure 白名单中放行。
     */
    @PostMapping("/refresh")
    public ApiResponse<Void> refresh(HttpServletRequest request, HttpServletResponse response) {
        String rt = readCookie(request, "satoken_refresh");
        String loginId = refreshTokenService.consume(rt);
        if (loginId == null) {
            // refresh 无效/过期：清掉浏览器侧 cookie，让前端跳转登录
            clearCookie(response, "satoken");
            clearCookie(response, "satoken_refresh");
            return ApiResponse.error(401, "登录已过期，请重新登录");
        }
        // 统一用 Long 类型 loginId 重新登录，与原 issueToken 的会话键一致，
        // 否则 Sa-Token 会以 String 键新建会话，导致数据权限上下文（deptPath/roleIds）丢失
        long uid;
        try {
            uid = Long.parseLong(loginId);
        } catch (NumberFormatException e) {
            clearCookie(response, "satoken");
            clearCookie(response, "satoken_refresh");
            return ApiResponse.error(401, "登录已过期，请重新登录");
        }
        // 换新 access token（Sa-Token 内部会重新登记会话）
        StpUtil.login(uid);
        String newAccessToken = StpUtil.getTokenValue();
        // refreshToken 一次性消费即作废，这里轮换一个新的，降低泄露风险
        String newRefresh = refreshTokenService.create(uid);
        writeCookie(response, "satoken", newAccessToken, accessTokenMaxAge);
        writeCookie(response, "satoken_refresh", newRefresh, refreshTtlSeconds);
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

    // ===================== cookie 工具 =====================

    /** 登录态双 cookie 写入（access + refresh，均为 httpOnly） */
    private void writeAuthCookies(HttpServletResponse response, LoginVO vo) {
        writeCookie(response, "satoken", vo.getToken(), accessTokenMaxAge);
        writeCookie(response, "satoken_refresh", vo.getRefreshToken(), refreshTtlSeconds);
        // token/refreshToken 仅经 httpOnly cookie 下发，响应体一律剥离：杜绝 XSS 经 JSON 窃取、也避免被日志/代理记录
        vo.setToken(null);
        vo.setTokenName(null);
        vo.setRefreshToken(null);
    }

    private void writeCookie(HttpServletResponse response, String name, String value, long maxAge) {
        ResponseCookie c = ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, c.toString());
    }

    private void clearCookie(HttpServletResponse response, String name) {
        ResponseCookie c = ResponseCookie.from(name, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, c.toString());
    }

    private String readCookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie c : request.getCookies()) {
            if (name.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }

    /**
     * 微信扫码弹窗页面：只通知父窗口「登录成功」，由父窗口自行 fetchMe 恢复登录态
     * （token 已通过 httpOnly cookie 种下，不再出现在本页面的任何字符串里）。
     */
    private String buildPopupHtml(String redirect) {
        String jsRedirect = (redirect != null && !redirect.isBlank())
                ? "try{var ru=new URL(window.location.href).searchParams.get('redirect');if(ru){window.top.location.href=ru+(ru.indexOf('?')>=0?'&':'?')+'wechat=success';}}catch(e){}"
                : "";
        return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><title>微信登录</title></head>"
                + "<body><p style=\"font-family:system-ui;padding:24px;text-align:center;color:#666\">登录成功，正在跳转…</p>"
                + "<script>(function(){var d={type:'wechat-login-success'};"
                + "try{if(window.opener){window.opener.postMessage(d,'*');}}catch(e){}"
                + "try{if(window.parent&&window.parent!==window){window.parent.postMessage(d,'*');}}catch(e){}"
                + jsRedirect
                + "setTimeout(function(){try{window.close();}catch(e){}},800);})();</script>"
                + "</body></html>";
    }
}
