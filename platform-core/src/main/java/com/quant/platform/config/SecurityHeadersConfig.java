package com.quant.platform.config;

import jakarta.servlet.*;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 安全响应头过滤器
 * 防止XSS、点击劫持等攻击
 */
@WebFilter(urlPatterns = "/*", filterName = "securityHeadersFilter")
@Component
public class SecurityHeadersConfig implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (response instanceof HttpServletResponse httpResponse) {
            // 防止MIME类型嗅探（XSS防护）
            httpResponse.setHeader("X-Content-Type-Options", "nosniff");
            
            // 防止点击劫持
            httpResponse.setHeader("X-Frame-Options", "DENY");
            
            // XSS保护（现代浏览器已弃用，但保留以兼容旧浏览器）
            httpResponse.setHeader("X-XSS-Protection", "1; mode=block");
            
            // Content Security Policy（限制资源加载）
            httpResponse.setHeader("Content-Security-Policy", 
                "default-src 'self'; script-src 'self' 'unsafe-inline' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; img-src 'self' data:");
            
            // Referrer策略（限制Referrer信息泄露）
            httpResponse.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
            
            // 强制HTTPS（生产环境启用）
            // httpResponse.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
        chain.doFilter(request, response);
    }
}
