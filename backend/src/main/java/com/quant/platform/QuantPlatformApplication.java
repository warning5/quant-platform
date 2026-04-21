package com.quant.platform;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
@MapperScan("com.quant.platform.**.mapper")
public class QuantPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(QuantPlatformApplication.class, args);
    }
}
