package com.quant.platform.strategy.regime;

import com.quant.platform.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Regime权重回验API（P2-7）
 */
@Slf4j
@RestController
@RequestMapping("/regime-backtest")
@RequiredArgsConstructor
@Tag(name = "Regime权重回验", description = "市场环境Regime权重最优性验证")
public class RegimeBacktestController {

    private final RegimeBacktestService regimeBacktestService;

    @Operation(summary = "执行Regime权重回验", description = "回验历史Regime分布、各Regime下推荐表现、权重最优性")
    @GetMapping("/run")
    public ApiResponse<Map<String, Object>> runBacktest(
            @RequestParam(defaultValue = "120") int days) {
        Map<String, Object> report = regimeBacktestService.runRegimeBacktest(days);
        return ApiResponse.success(report);
    }
}
