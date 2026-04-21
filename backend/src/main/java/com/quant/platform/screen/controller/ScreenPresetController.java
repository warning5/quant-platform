package com.quant.platform.screen.controller;

import com.quant.platform.common.dto.ApiResponse;
import com.quant.platform.screen.entity.ScreenPreset;
import com.quant.platform.screen.service.ScreenPresetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 因子预设组合接口
 */
@Tag(name = "因子预设组合")
@RestController
@RequestMapping("/screen/presets")
@RequiredArgsConstructor
public class ScreenPresetController {

    private final ScreenPresetService presetService;

    @Operation(summary = "获取所有预设组合")
    @GetMapping
    public ApiResponse<List<ScreenPreset>> listAll() {
        return ApiResponse.success(presetService.listAll());
    }

    @Operation(summary = "获取内置预设组合")
    @GetMapping("/builtin")
    public ApiResponse<List<ScreenPreset>> listBuiltin() {
        return ApiResponse.success(presetService.listBuiltin());
    }

    @Operation(summary = "获取单个预设详情")
    @GetMapping("/{id}")
    public ApiResponse<ScreenPreset> getById(@PathVariable Long id) {
        return ApiResponse.success(presetService.getById(id));
    }

    @Operation(summary = "创建自定义预设")
    @PostMapping
    public ApiResponse<ScreenPreset> create(@RequestBody ScreenPreset preset) {
        return ApiResponse.success(presetService.create(preset));
    }

    @Operation(summary = "更新自定义预设")
    @PutMapping("/{id}")
    public ApiResponse<ScreenPreset> update(@PathVariable Long id, @RequestBody ScreenPreset preset) {
        ScreenPreset result = presetService.update(id, preset);
        return result != null ? ApiResponse.success(result) : ApiResponse.error(404, "预设不存在或为内置预设，不可修改");
    }

    @Operation(summary = "删除自定义预设")
    @DeleteMapping("/{id}")
    public ApiResponse<Boolean> delete(@PathVariable Long id) {
        return ApiResponse.success(presetService.delete(id));
    }
}
