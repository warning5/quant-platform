package com.quant.platform.auth.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Sa-Token 配置：
 * 1) 注册默认拦截器（内置 SaAnnotationInterceptor），对 /api/** 下的每个接口
 *    自动扫描并执行 @SaCheckPermission 注解。
 *    —— 所有业务接口均标注了类级/方法级 @SaCheckPermission，既强制登录，又按权限鉴权。
 * 2) 提供 RestTemplate 与 BCrypt 密码加密器
 * 3) API 文档（Swagger / springdoc）仅在 dev / local 环境匿名放开，
 *    生产环境移除白名单，访问文档需先登录。
 */
@Configuration
public class SaTokenConfigure implements WebMvcConfigurer {

    private final Environment environment;

    public SaTokenConfigure(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        List<String> excludes = new ArrayList<>();
        // 登录与微信联合登录（匿名可访问）
        excludes.add("/auth/login");
        excludes.add("/auth/wechat/**");
        // 健康检查（匿名）
        excludes.add("/test/**");
        // API 文档仅在 dev / local 环境放开，生产环境需登录后访问
        boolean devProfile = Arrays.stream(environment.getActiveProfiles())
                .anyMatch(p -> "dev".equals(p) || "local".equals(p));
        if (devProfile) {
            excludes.add("/v3/api-docs/**");
            excludes.add("/swagger-ui/**");
            excludes.add("/swagger-resources/**");
            excludes.add("/webjars/**");
        }
        // 注意：context-path 为 /api 时，Spring 对拦截器匹配的是「去掉 context-path 后」的路径
        // （如 /strategies、/auth/login），因此必须用 /** 而非 /api/**，否则拦截器对所有业务接口都不生效。
        registry.addInterceptor(new SaInterceptor())
                .addPathPatterns("/**")
                .excludePathPatterns(excludes);
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
