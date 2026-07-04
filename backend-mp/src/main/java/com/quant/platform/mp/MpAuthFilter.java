package com.quant.platform.mp;

import com.quant.platform.common.dto.ApiResponse;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public class MpAuthFilter implements Filter {

    @Value("${mp.token:quant-mp-2026-secret}")
    private String expectedToken;

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

        if (expectedToken.equals(token)) {
            chain.doFilter(request, response);
        } else {
            httpResp.setStatus(401);
            httpResp.setContentType("application/json;charset=UTF-8");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("code", 401);
            result.put("message", "认证失败");
            httpResp.getWriter().write(ApiResponse.error(401, "认证失败").toString());
        }
    }
}
