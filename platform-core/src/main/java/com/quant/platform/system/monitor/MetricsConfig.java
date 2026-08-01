package com.quant.platform.system.monitor;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 注册 MetricsCollector 为 Servlet Filter（拦截所有请求采集 HTTP 指标）。
 * 顺序靠前，确保能测量完整处理耗时。
 */
@Configuration
public class MetricsConfig {

    @Bean
    public FilterRegistrationBean<MetricsCollector> metricsFilterRegistration(MetricsCollector collector) {
        FilterRegistrationBean<MetricsCollector> bean = new FilterRegistrationBean<>(collector);
        bean.addUrlPatterns("/*");
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        bean.setName("metricsCollector");
        return bean;
    }
}
