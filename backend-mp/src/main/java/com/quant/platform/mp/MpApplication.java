package com.quant.platform.mp;

import com.quant.platform.mp.config.DataSourceConfig;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan({"com.quant.platform.**.mapper", "com.quant.platform.mp.mapper"})
@EnableScheduling
public class MpApplication {

    public static void main(String[] args) {
        SpringApplication.run(MpApplication.class, args);
    }

    @Bean
    public MpAuthFilter mpAuthFilter() {
        return new MpAuthFilter();
    }

    @Bean
    public FilterRegistrationBean<MpAuthFilter> mpAuthFilterRegistration(MpAuthFilter filter) {
        FilterRegistrationBean<MpAuthFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(filter);
        registration.addUrlPatterns("/mp/*");
        registration.setOrder(1);
        return registration;
    }
}
