package com.quant.platform.auth.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.client.RestTemplate;

/**
 * Sa-Token 配置：
 * 1) 注册默认拦截器（内置 SaAnnotationInterceptor），对 /system/** 下的每个接口
 *    自动扫描并执行 @SaCheckLogin / @SaCheckPermission / @SaCheckRole 注解。
 *    —— 所有 /system/** 接口均标注了 @SaCheckPermission，因此既强制登录，又按权限/菜单鉴权。
 *    —— 注意：若传入自定义 handle lambda（如 handle -> StpUtil.checkLogin()），会替换默认注解拦截器，
 *       导致 @SaCheckPermission 失效，切勿使用。
 * 2) 提供 RestTemplate 与 BCrypt 密码加密器
 */
@Configuration
public class SaTokenConfigure implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor())
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        // 登录与微信联合登录（匿名可访问）
                        "/api/auth/login",
                        "/api/auth/wechat/**",
                        // 健康检查
                        "/api/test/**",
                        // 开发期 API 文档
                        "/api/v3/api-docs/**",
                        "/api/swagger-ui/**",
                        "/api/swagger-resources/**",
                        "/api/webjars/**");
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
