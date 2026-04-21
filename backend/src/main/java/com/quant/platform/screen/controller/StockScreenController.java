package com.quant.platform.screen.controller;

import com.quant.platform.common.dto.ApiResponse;
import com.quant.platform.screen.dto.ScreenRequest;
import com.quant.platform.screen.dto.ScreenResult;
import com.quant.platform.screen.service.StockScreenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 多因子选股接口
 */
@Tag(name = "多因子选股")
@RestController
@RequestMapping("/screen")
@RequiredArgsConstructor
public class StockScreenController {

    private final StockScreenService screenService;

    /**
     * 执行多因子选股
     * POST /api/screen/run
     */
    @Operation(summary = "执行多因子选股")
    @PostMapping("/run")
    public ApiResponse<ScreenResult> run(@RequestBody ScreenRequest req) {
        ScreenResult result = screenService.screen(req);
        return ApiResponse.success(result);
    }

    /**
     * 获取可用于选股的因子列表
     * GET /screen/factors
     */
    @Operation(summary = "获取可用因子列表")
    @GetMapping("/factors")
    public ApiResponse<List<Map<String, Object>>> availableFactors() {
        return ApiResponse.success(screenService.getAvailableFactors());
    }

    /**
     * 获取最新可用选股日期
     * GET /screen/latest-date
     */
    @Operation(summary = "获取最新可用选股日期")
    @GetMapping("/latest-date")
    public ApiResponse<String> latestDate() {
        return ApiResponse.success(screenService.getLatestAvailableDate());
    }
}
