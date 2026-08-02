package com.quant.platform.dataupdate;

import com.quant.platform.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 定时任务配置管理（瘦控制器）
 * 业务逻辑已下沉至 ScheduleConfigService；本类只负责参数接收、权限校验与响应包装。
 */
@Slf4j
@RestController
@RequestMapping("/schedule-config")
@RequiredArgsConstructor
@cn.dev33.satoken.annotation.SaCheckPermission("data:view")
public class ScheduleConfigController {

    private final ScheduleConfigService scheduleConfigService;

    @GetMapping
    @cn.dev33.satoken.annotation.SaCheckPermission(value = {"data:view", "data:edit"}, mode = cn.dev33.satoken.annotation.SaMode.AND)
    public ApiResponse<List<Map<String, Object>>> getAllConfigs() {
        return ApiResponse.success(scheduleConfigService.getAllConfigs());
    }

    @GetMapping("/global")
    public ApiResponse<Map<String, Object>> getGlobalConfig() {
        return ApiResponse.success(scheduleConfigService.getGlobalConfig());
    }

    @cn.dev33.satoken.annotation.SaCheckPermission(value = {"data:view", "data:edit"}, mode = cn.dev33.satoken.annotation.SaMode.AND)
    @PutMapping("/{taskKey}")
    public ApiResponse<Map<String, Object>> updateConfig(
            @PathVariable String taskKey,
            @RequestBody Map<String, Object> body) {
        return scheduleConfigService.updateConfig(taskKey, body);
    }

    @cn.dev33.satoken.annotation.SaCheckPermission(value = {"data:view", "data:edit"}, mode = cn.dev33.satoken.annotation.SaMode.AND)
    @PutMapping("/batch")
    public ApiResponse<Boolean> batchUpdate(@RequestBody List<Map<String, Object>> items) {
        return scheduleConfigService.batchUpdate(items);
    }

    @cn.dev33.satoken.annotation.SaCheckPermission(value = {"data:view", "data:edit"}, mode = cn.dev33.satoken.annotation.SaMode.AND)
    @PostMapping("/trigger/{taskKey}")
    public ApiResponse<Map<String, Object>> triggerTask(@PathVariable String taskKey) {
        return scheduleConfigService.triggerTask(taskKey);
    }

    @cn.dev33.satoken.annotation.SaCheckPermission(value = {"data:view", "data:edit"}, mode = cn.dev33.satoken.annotation.SaMode.AND)
    @PostMapping("/cancel/{taskKey}")
    public ApiResponse<Boolean> cancelTask(@PathVariable String taskKey) {
        return scheduleConfigService.cancelTask(taskKey);
    }

    @GetMapping("/history")
    public ApiResponse<List<Map<String, Object>>> getHistory() {
        return ApiResponse.success(scheduleConfigService.getHistory());
    }

    @cn.dev33.satoken.annotation.SaCheckPermission(value = {"data:view", "data:delete"}, mode = cn.dev33.satoken.annotation.SaMode.AND)
    @DeleteMapping("/{taskKey}")
    public ApiResponse<Boolean> deleteConfig(@PathVariable String taskKey) {
        return scheduleConfigService.deleteConfig(taskKey);
    }

    @GetMapping("/dependencies")
    public ApiResponse<List<Map<String, Object>>> getDependencies() {
        return ApiResponse.success(scheduleConfigService.getDependencies());
    }

    @GetMapping("/task-keys")
    public ApiResponse<List<Map<String, Object>>> getTaskKeys() {
        return ApiResponse.success(scheduleConfigService.getTaskKeys());
    }

    @cn.dev33.satoken.annotation.SaCheckPermission(value = {"data:view", "data:edit"}, mode = cn.dev33.satoken.annotation.SaMode.AND)
    @PostMapping("/dependencies")
    public ApiResponse<?> addDependency(@RequestBody Map<String, Object> body) {
        return scheduleConfigService.addDependency(body);
    }

    @cn.dev33.satoken.annotation.SaCheckPermission(value = {"data:view", "data:delete"}, mode = cn.dev33.satoken.annotation.SaMode.AND)
    @DeleteMapping("/dependencies/{id}")
    public ApiResponse<?> deleteDependency(@PathVariable("id") Long id) {
        return scheduleConfigService.deleteDependency(id);
    }
}
