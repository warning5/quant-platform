package com.quant.platform.factor.health.controller;

import com.quant.platform.common.dto.ApiResponse;
import com.quant.platform.factor.health.service.FactorHealthMonitor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 因子健康检查API（P3-11）
 * 衰减检测 → 降级 → 复活 全生命周期管理
 */
@Slf4j
@RestController
@RequestMapping("/factor-health")
@RequiredArgsConstructor
@Tag(name = "因子健康检查", description = "因子时效性管理：衰减检测、降级、复活")
public class FactorHealthController {

    private final FactorHealthMonitor factorHealthMonitor;

    @Operation(summary = "执行因子健康检查", description = "检查所有因子健康状态，执行衰减预警/降级/复活")
    @PostMapping("/check")
    public ApiResponse<Map<String, Object>> checkAllFactorsHealth() {
        Map<String, Object> result = factorHealthMonitor.checkAllFactorsHealth();
        return ApiResponse.success(result);
    }

    @Operation(summary = "获取因子健康报告", description = "查看所有因子当前状态、最近健康日志、状态分布")
    @GetMapping("/report")
    public ApiResponse<Map<String, Object>> getHealthReport() {
        Map<String, Object> report = factorHealthMonitor.getHealthReport();
        return ApiResponse.success(report);
    }

    @Operation(summary = "手动降级因子", description = "将指定因子从ACTIVE降级为DEGRADED")
    @PostMapping("/degrade/{factorCode}")
    public ApiResponse<String> degradeFactor(
            @PathVariable String factorCode,
            @RequestParam(defaultValue = "手动降级") String reason) {
        factorHealthMonitor.degradeFactorManual(factorCode, reason);
        return ApiResponse.success("因子 " + factorCode + " 已降级为DEGRADED");
    }

    @Operation(summary = "手动复活因子", description = "将指定因子从DEGRADED复活为ACTIVE")
    @PostMapping("/resurrect/{factorCode}")
    public ApiResponse<String> resurrectFactor(@PathVariable String factorCode) {
        factorHealthMonitor.resurrectFactorManual(factorCode);
        return ApiResponse.success("因子 " + factorCode + " 已复活为ACTIVE");
    }
}
