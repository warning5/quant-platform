package com.quant.platform.strategy.paper;

import com.quant.platform.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 模拟盘交易 API
 */
@RestController
@RequestMapping("/paper-trading")
@RequiredArgsConstructor
@Tag(name = "模拟盘交易", description = "策略模拟盘：信号生成、持仓管理、净值追踪")
public class PaperTradingController {

    private final PaperTradingService paperTradingService;

    @PostMapping("/create")
    @Operation(summary = "创建模拟盘")
    public ApiResponse<PaperTrading> create(
            @RequestParam Long strategyId,
            @RequestParam(required = false) String strategyCode,
            @RequestParam(defaultValue = "1000000") BigDecimal initialCapital) {
        return ApiResponse.success("模拟盘创建成功",
            paperTradingService.createPaperTrading(strategyId, strategyCode, initialCapital));
    }

    @GetMapping("/list")
    @Operation(summary = "获取所有模拟盘")
    public ApiResponse<List<PaperTrading>> list() {
        return ApiResponse.success(paperTradingService.listAll());
    }

    @GetMapping("/{paperId}")
    @Operation(summary = "获取模拟盘详情（持仓+净值）")
    public ApiResponse<Map<String, Object>> getDetail(@PathVariable Long paperId) {
        return ApiResponse.success(paperTradingService.getDetail(paperId));
    }

    @PostMapping("/{paperId}/generate-signals")
    @Operation(summary = "生成交易信号")
    public ApiResponse<List<PaperSignal>> generateSignals(@PathVariable Long paperId) {
        return ApiResponse.success("信号生成成功", paperTradingService.generateSignals(paperId));
    }

    @PostMapping("/signals/{signalId}/execute")
    @Operation(summary = "执行信号（买入/卖出）")
    public ApiResponse<PaperPosition> executeSignal(@PathVariable Long signalId) {
        return ApiResponse.success("信号执行成功", paperTradingService.executeSignal(signalId));
    }

    @GetMapping("/{paperId}/signals")
    @Operation(summary = "获取信号列表")
    public ApiResponse<List<PaperSignal>> getSignals(@PathVariable Long paperId) {
        return ApiResponse.success(paperTradingService.getSignals(paperId));
    }

    @PatchMapping("/{paperId}/status")
    @Operation(summary = "更新模拟盘状态（RUNNING/PAUSED/STOPPED）")
    public ApiResponse<PaperTrading> updateStatus(
            @PathVariable Long paperId,
            @RequestParam String status) {
        return ApiResponse.success(paperTradingService.updateStatus(paperId, status));
    }
}
