package com.quant.platform.test;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 测试控制器（用于验证安全修复）
 */
@RestController
@RequestMapping("/api/test")
public class TestController {

    /**
     * 测试全局异常处理（不泄露敏感信息）
     */
    @GetMapping("/error")
    public String testError() {
        throw new RuntimeException("这是一个测试异常，包含敏感信息：password=123456, api_key=secret123");
    }

    /**
     * 测试安全响应头
     */
    @GetMapping("/headers")
    public String testHeaders() {
        return "Check response headers for security headers";
    }
}
