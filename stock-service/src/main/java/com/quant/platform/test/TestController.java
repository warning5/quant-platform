package com.quant.platform.test;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查端点
 * 用于验证后端是否正常运行，不包含敏感信息
 */
@RestController
@RequestMapping("/api/test")
public class TestController {

    /**
     * 健康检查
     */
    @GetMapping("/health")
    public String health() {
        return "OK";
    }
}
