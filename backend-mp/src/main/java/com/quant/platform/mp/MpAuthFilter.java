package com.quant.platform.mp;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.*;
import jakarta.servlet.http.*;

import java.io.IOException;

/**
 * 小程序接口鉴权过滤器：
 * - 放行 /health 与 /login（登录接口本身免校验）
 * - 其余 /mp/* 请求必须携带有效的用户 token（X-MP-Token 头或 token 参数），
 *   用 Sa-Token 校验登录态，无效返回 401。
 */
public class MpAuthFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpReq = (HttpServletRequest) request;
        HttpServletResponse httpResp = (HttpServletResponse) response;

        String uri = httpReq.getRequestURI();
        if (uri.contains("/health") || uri.contains("/login")) {
            chain.doFilter(request, response);
            return;
        }

        String token = httpReq.getHeader("X-MP-Token");
        if (token == null || token.isEmpty()) {
            token = httpReq.getParameter("token");
        }

        boolean valid = false;
        if (token != null && !token.isEmpty()) {
            try {
                Object loginId = StpUtil.getLoginIdByToken(token);
                valid = loginId != null;
            } catch (NotLoginException e) {
                valid = false;
            }
        }

        if (valid) {
            chain.doFilter(request, response);
        } else {
            httpResp.setStatus(401);
            httpResp.setContentType("application/json;charset=UTF-8");
            httpResp.getWriter().write("{\"code\":401,\"message\":\"认证失败\"}");
        }
    }
}
