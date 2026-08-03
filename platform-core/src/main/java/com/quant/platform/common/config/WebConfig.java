package com.quant.platform.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;

/**
 * CORS 跨域配置（Filter 方式，避免 Spring Boot 3.x allowedOrigins 校验问题）
 * <p>
 * 通过 {@code cors.allowed-origins} 配置允许的来源列表（逗号分隔）：
 * <ul>
 *   <li>未配置时默认允许所有来源（仅适用开发环境）</li>
 *   <li>生产环境必须配置具体域名，如 {@code https://example.com,https://www.example.com}</li>
 * </ul>
 */
@Slf4j
@Configuration
public class WebConfig {

    @Value("${cors.allowed-origins:}")
    private String allowedOrigins;

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        config.addAllowedMethod("*");
        config.addAllowedHeader("*");

        if (allowedOrigins == null || allowedOrigins.isBlank()) {
            // 开发环境兜底：允许所有来源
            log.warn("[CORS] cors.allowed-origins 未配置，允许所有来源。生产环境必须配置具体域名！");
            config.addAllowedOriginPattern("*");
        } else {
            // 生产环境：仅允许配置的域名
            List<String> origins = Arrays.stream(allowedOrigins.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            log.info("[CORS] 允许的来源: {}", origins);
            for (String origin : origins) {
                config.addAllowedOriginPattern(origin);
            }
        }

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}
