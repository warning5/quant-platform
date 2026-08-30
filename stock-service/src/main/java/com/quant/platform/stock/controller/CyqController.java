package com.quant.platform.stock.controller;

import com.quant.platform.common.dto.ApiResponse;
import com.quant.platform.stock.service.ClickHouseStockService;
import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 筹码分布(CYQ)查询接口
 *
 * 数据来源:
 *   - 最新快照: stock.stock_cyq(实时展示快)
 *   - 历史逐日: stock.stock_cyq_daily(回看/演变)
 */
@Slf4j
@RestController
@RequestMapping("/cyq")
@RequiredArgsConstructor
public class CyqController {

    private final ClickHouseStockService clickHouseStockService;

    /**
     * 个股筹码分布
     * GET /api/cyq?code=002080             -> 最新快照
     * GET /api/cyq?code=002080&date=2026-08-01 -> 指定交易日(历史回看)
     */
    @GetMapping
    @SaCheckPermission("stock:view")
    public ApiResponse<Map<String, Object>> getCyq(
            @RequestParam String code,
            @RequestParam(required = false) String date) {
        if (date == null || date.isBlank()) {
            Map<String, Object> latest = clickHouseStockService.getCyqLatest(code);
            if (latest == null) {
                return ApiResponse.success(Map.of("code", code, "found", false));
            }
            latest.put("code", code);
            latest.put("found", true);
            return ApiResponse.success(latest);
        }
        try {
            LocalDate d = LocalDate.parse(date.trim());
            Map<String, Object> byDate = clickHouseStockService.getCyqByDate(code, d);
            if (byDate == null) {
                return ApiResponse.success(Map.of("code", code, "date", date, "found", false));
            }
            byDate.put("code", code);
            byDate.put("found", true);
            return ApiResponse.success(byDate);
        } catch (DateTimeParseException e) {
            return ApiResponse.error(400, "date 格式应为 yyyy-MM-dd");
        }
    }

    /**
     * 个股多日筹码分布(最多 10 天, 用于前端叠加对比)
     * GET /api/cyq/multi?code=002080&dates=2026-08-01,2026-08-02,...,2026-08-10
     */
    @GetMapping("/multi")
    @SaCheckPermission("stock:view")
    public ApiResponse<Map<String, Object>> getCyqMulti(
            @RequestParam String code,
            @RequestParam String dates) {
        List<LocalDate> list = new ArrayList<>();
        for (String s : dates.split(",")) {
            s = s.trim();
            if (s.isEmpty()) continue;
            try {
                list.add(LocalDate.parse(s));
            } catch (DateTimeParseException e) {
                return ApiResponse.error(400, "日期格式错误: " + s);
            }
        }
        if (list.isEmpty()) {
            return ApiResponse.error(400, "dates 不能为空");
        }
        if (list.size() > 10) {
            return ApiResponse.error(400, "最多支持 10 天对比, 当前 " + list.size());
        }
        List<Map<String, Object>> rows = clickHouseStockService.getCyqMulti(code, list);
        return ApiResponse.success(Map.of(
                "code", code,
                "count", rows.size(),
                "items", rows
        ));
    }
}
