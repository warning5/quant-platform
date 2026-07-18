package com.quant.platform.strategy.portfolio;

import com.quant.platform.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

/**
 * 策略组合风控API（P2-9）
 */
@Slf4j
@RestController
@RequestMapping("/portfolio-risk")
@RequiredArgsConstructor
@Tag(name = "策略组合风控", description = "跨策略组合层面风险管理")
public class PortfolioRiskController {

    private final PortfolioRiskService portfolioRiskService;

    @Operation(summary = "执行全量组合风控检查", description = "跨策略个股去重 + 行业集中度 + 回撤监控")
    @GetMapping("/check")
    public ApiResponse<Map<String, Object>> checkPortfolioRisk(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (date == null) date = LocalDate.now();
        Map<String, Object> report = portfolioRiskService.runFullPortfolioRiskCheck(date);
        return ApiResponse.success(report);
    }

    @Operation(summary = "组合回撤报告", description = "聚合所有运行中模拟盘的总回撤")
    @GetMapping("/drawdown")
    public ApiResponse<PortfolioRiskService.PortfolioDrawdownReport> getDrawdownReport() {
        return ApiResponse.success(portfolioRiskService.checkPortfolioDrawdown());
    }
}
