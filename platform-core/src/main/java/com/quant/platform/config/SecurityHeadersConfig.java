package com.quant.platform.config;

import jakarta.servlet.*;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 安全响应头过滤器
 * 防止XSS、点击劫持等攻击
 * HSTS（Strict-Transport-Security）仅在 prod profile 下启用
 */
@Slf4j
@WebFilter(urlPatterns = "/*", filterName = "securityHeadersFilter")
@Component
public class SecurityHeadersConfig implements Filter {

    @Value("${security.hsts.enabled:false}")
    private boolean hstsEnabled;

    @Value("${security.hsts.max-age-seconds:31536000}")
    private int hstsMaxAge;

    @Value("${security.hsts.include-sub-domains:true}")
    private boolean hstsIncludeSubDomains;

    @Override
    public void init(FilterConfig filterConfig) {
        if (hstsEnabled) {
            log.info("[SecurityHeaders] HSTS 已启用: max-age={}s, includeSubDomains={}", hstsMaxAge, hstsIncludeSubDomains);
        } else {
            log.info("[SecurityHeaders] HSTS 未启用（非生产环境）");
        }
    }

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

            // HSTS：强制 HTTPS（仅生产环境启用）
            if (hstsEnabled) {
                String hstsValue = "max-age=" + hstsMaxAge;
                if (hstsIncludeSubDomains) {
                    hstsValue += "; includeSubDomains";
                }
                httpResponse.setHeader("Strict-Transport-Security", hstsValue);
            }
        }
        chain.doFilter(request, response);
    }
}
