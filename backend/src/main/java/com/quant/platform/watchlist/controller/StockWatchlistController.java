package com.quant.platform.watchlist.controller;

import com.quant.platform.common.dto.ApiResponse;
import com.quant.platform.watchlist.domain.StockWatchlist;
import com.quant.platform.watchlist.service.StockWatchlistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/watchlist")
@RequiredArgsConstructor
public class StockWatchlistController {

    private final StockWatchlistService watchlistService;

    /** 获取所有自选股 */
    @GetMapping
    public ApiResponse<List<StockWatchlist>> getAll(@RequestParam(required = false) String group) {
        if (group != null && !group.isBlank()) {
            return ApiResponse.success(watchlistService.getByGroup(group));
        }
        return ApiResponse.success(watchlistService.getAllActive());
    }

    /** 获取按分组汇总的Map */
    @GetMapping("/grouped")
    public ApiResponse<Map<String, List<StockWatchlist>>> getGrouped() {
        return ApiResponse.success(watchlistService.getGroupedWatchlist());
    }

    /** 获取所有分组名 */
    @GetMapping("/groups")
    public ApiResponse<List<String>> getGroups() {
        return ApiResponse.success(watchlistService.getGroupNames());
    }

    /** 获取观测到期的股票 */
    @GetMapping("/expired")
    public ApiResponse<List<StockWatchlist>> getExpired() {
        return ApiResponse.success(watchlistService.getExpiredWatchlist());
    }

    /** 检查股票是否在自选池 */
    @GetMapping("/check")
    public ApiResponse<Boolean> checkInWatchlist(@RequestParam String stockCode) {
        return ApiResponse.success(watchlistService.isInWatchlist(stockCode));
    }

    /** 添加自选股 */
    @PostMapping
    public ApiResponse<StockWatchlist> addStock(@RequestBody StockWatchlist watchlist) {
        return ApiResponse.success(watchlistService.addStock(watchlist));
    }

    /** 从推荐结果批量添加 */
    @PostMapping("/batch")
    public ApiResponse<Integer> addFromRecommendation(@RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<String> codes = (List<String>) request.get("stockCodes");
        @SuppressWarnings("unchecked")
        List<String> names = (List<String>) request.get("stockNames");
        Long batchId = request.get("batchId") != null ?
                Long.valueOf(request.get("batchId").toString()) : null;
        String groupName = (String) request.get("groupName");
        int added = watchlistService.addFromRecommendation(codes, names, batchId, groupName);
        return ApiResponse.success(added);
    }

    /** 更新自选股 */
    @PutMapping("/{id}")
    public ApiResponse<StockWatchlist> updateWatchlist(@PathVariable Long id,
                                                        @RequestBody StockWatchlist update) {
        return ApiResponse.success(watchlistService.updateWatchlist(id, update));
    }

    /** 移除自选股（归档） */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> removeStock(@PathVariable Long id) {
        watchlistService.removeStock(id);
        return ApiResponse.success(null);
    }

    /** 真正删除 */
    @DeleteMapping("/{id}/delete")
    public ApiResponse<Void> deleteStock(@PathVariable Long id) {
        watchlistService.deleteStock(id);
        return ApiResponse.success(null);
    }

    /** 清空分组 */
    @DeleteMapping("/group/{groupName}")
    public ApiResponse<Integer> clearGroup(@PathVariable String groupName) {
        return ApiResponse.success(watchlistService.clearGroup(groupName));
    }
}
