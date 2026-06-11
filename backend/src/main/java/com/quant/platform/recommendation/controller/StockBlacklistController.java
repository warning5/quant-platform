package com.quant.platform.recommendation.controller;

import com.quant.platform.common.dto.ApiResponse;
import com.quant.platform.recommendation.domain.StockBlacklist;
import com.quant.platform.recommendation.service.StockBlacklistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 股票黑名单 Controller
 */
@Slf4j
@RestController
@RequestMapping("/blacklist")
@RequiredArgsConstructor
public class StockBlacklistController {

    private final StockBlacklistService stockBlacklistService;

    /**
     * 获取某策略当前生效的黑名单列表
     * GET /blacklist?strategyId=35&includeExpired=false
     */
    @GetMapping
    public ApiResponse<List<StockBlacklist>> getBlacklist(
            @RequestParam Long strategyId,
            @RequestParam(defaultValue = "false") boolean includeExpired) {
        if (includeExpired) {
            return ApiResponse.success(stockBlacklistService.getAllBlacklist(strategyId));
        }
        return ApiResponse.success(stockBlacklistService.getActiveBlacklist(strategyId));
    }

    /**
     * 获取某策略当前生效的黑名单股票代码集合（用于前端过滤提示）
     * GET /blacklist/codes?strategyId=35
     */
    @GetMapping("/codes")
    public ApiResponse<Set<String>> getBlacklistCodes(@RequestParam Long strategyId) {
        return ApiResponse.success(stockBlacklistService.getActiveBlacklistCodes(strategyId));
    }

    /**
     * 手动添加到黑名单
     * POST /blacklist
     * Body: { "strategyId": 35, "stockCode": "600519", "stockName": "贵州茅台", "reasonDetail": "...", "days": 30 }
     */
    @PostMapping
    public ApiResponse<StockBlacklist> addBlacklist(@RequestBody Map<String, Object> request) {
        Long strategyId = Long.valueOf(request.get("strategyId").toString());
        String stockCode = (String) request.get("stockCode");
        String stockName = (String) request.get("stockName");
        String reasonDetail = (String) request.get("reasonDetail");
        Integer days = request.get("days") != null ? Integer.valueOf(request.get("days").toString()) : null;

        StockBlacklist bl = stockBlacklistService.manualAdd(strategyId, stockCode, stockName, reasonDetail, days);
        log.info("[Blacklist] [手动添加] strategyId={}, code={}, by=用户", strategyId, stockCode);
        return ApiResponse.success(bl);
    }

    /**
     * 从黑名单中移除（按策略+代码）
     * DELETE /blacklist?strategyId=35&stockCode=600519
     */
    @DeleteMapping
    public ApiResponse<Void> removeBlacklist(
            @RequestParam Long strategyId,
            @RequestParam String stockCode) {
        boolean removed = stockBlacklistService.removeFromBlacklist(strategyId, stockCode);
        if (removed) {
            return ApiResponse.success(null);
        } else {
            return ApiResponse.error("未找到匹配的黑名单记录");
        }
    }

    /**
     * 按ID从黑名单中移除（解封）
     * DELETE /blacklist/{id}
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> removeById(@PathVariable Long id) {
        boolean removed = stockBlacklistService.removeById(id);
        if (removed) {
            return ApiResponse.success(null);
        } else {
            return ApiResponse.error("未找到该黑名单记录(id=" + id + ")");
        }
    }

    /**
     * 清空某策略全部黑名单
     * DELETE /blacklist/all?strategyId=35
     */
    @DeleteMapping("/all")
    public ApiResponse<Void> clearAll(@RequestParam Long strategyId) {
        stockBlacklistService.clearAll(strategyId);
        return ApiResponse.success(null);
    }
}
