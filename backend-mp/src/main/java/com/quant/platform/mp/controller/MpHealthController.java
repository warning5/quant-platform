package com.quant.platform.mp.controller;

import com.quant.platform.common.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 健康检查端点（MpAuthFilter 已放行 /health）。
 * 供负载均衡 / 容器探针 / 监控使用。
 */
@RestController
@RequestMapping("/mp")
public class MpHealthController {

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        Map<String, Object> body = new HashMap<>();
        body.put("status", "UP");
        body.put("service", "quant-platform-mp");
        return ApiResponse.success(body);
    }
}
