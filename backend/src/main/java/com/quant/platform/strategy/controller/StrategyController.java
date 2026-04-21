package com.quant.platform.strategy.controller;

import com.quant.platform.common.dto.ApiResponse;
import com.quant.platform.strategy.domain.StrategyDefinition;
import com.quant.platform.strategy.service.StrategyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.springframework.web.bind.annotation.*;

/**
 * 策略管理API
 */
@RestController
@RequestMapping("/strategies")
@RequiredArgsConstructor
@Tag(name = "策略管理", description = "量化策略定义与管理接口")
public class StrategyController {

    private final StrategyService strategyService;

    @GetMapping
    @Operation(summary = "查询策略列表")
    public ApiResponse<IPage<StrategyDefinition>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(strategyService.searchStrategies(keyword, type, status, page, size));
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取策略详情")
    public ApiResponse<StrategyDefinition> getById(@PathVariable Long id) {
        return ApiResponse.success(strategyService.getById(id));
    }

    @PostMapping
    @Operation(summary = "创建策略")
    public ApiResponse<StrategyDefinition> create(@RequestBody StrategyDefinition strategy) {
        return ApiResponse.success("策略创建成功", strategyService.createStrategy(strategy));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新策略")
    public ApiResponse<StrategyDefinition> update(@PathVariable Long id,
                                                   @RequestBody StrategyDefinition strategy) {
        return ApiResponse.success("策略更新成功", strategyService.updateStrategy(id, strategy));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除策略")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        strategyService.deleteStrategy(id);
        return ApiResponse.ok();
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "更改策略状态")
    public ApiResponse<StrategyDefinition> changeStatus(@PathVariable Long id,
                                                         @RequestParam String status) {
        return ApiResponse.success(strategyService.changeStatus(id, StrategyDefinition.StrategyStatus.valueOf(status)));
    }

    @PostMapping("/init-demo")
    @Operation(summary = "初始化演示策略数据")
    public ApiResponse<String> initDemoStrategies() {
        strategyService.initDemoStrategies();
        return ApiResponse.success("演示策略初始化完成");
    }
}
