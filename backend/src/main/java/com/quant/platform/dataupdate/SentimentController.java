package com.quant.platform.dataupdate;

import com.quant.platform.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 情绪数据控制器
 */
@RestController
@RequestMapping("/data-update/sentiment")
@RequiredArgsConstructor
@Tag(name = "情绪数据", description = "情绪数据概览与校验接口")
public class SentimentController {

    private final ClickHouseSentimentService clickHouseSentimentService;

    @GetMapping("/coverage")
    @Operation(summary = "获取情绪数据概览", description = "从 ClickHouse 获取各情绪数据表的记录数和时间范围，失败回退 MySQL")
    public ApiResponse<Map<String, Object>> getCoverage() {
        try {
            Map<String, Object> coverage = clickHouseSentimentService.getCoverage();
            return ApiResponse.success(coverage);
        } catch (Exception e) {
            return ApiResponse.error("获取概览失败: " + e.getMessage());
        }
    }

    @GetMapping("/table-stats/{table}")
    @Operation(summary = "获取指定表统计", description = "获取指定情绪数据表的详细统计")
    public ApiResponse<Map<String, Object>> getTableStats(@PathVariable String table) {
        try {
            Map<String, Object> stats = clickHouseSentimentService.getTableStats(table);
            if (stats.containsKey("error")) {
                return ApiResponse.error((String) stats.get("error"));
            }
            return ApiResponse.success(stats);
        } catch (Exception e) {
            return ApiResponse.error("获取表统计失败: " + e.getMessage());
        }
    }

    @GetMapping("/validate")
    @Operation(summary = "情绪数据校验", description = "按日期跨度校验各情绪数据表的记录数、空值和一致性")
    public ApiResponse<Map<String, Object>> validate(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String tables) {
        try {
            List<String> tableList = (tables == null || tables.isBlank())
                    ? Collections.emptyList()
                    : Arrays.stream(tables.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .collect(Collectors.toList());
            Map<String, Object> validation = clickHouseSentimentService.validate(startDate, endDate, tableList);
            return ApiResponse.success(validation);
        } catch (Exception e) {
            return ApiResponse.error("校验失败: " + e.getMessage());
        }
    }

    @GetMapping("/validate-daily")
    @Operation(summary = "情绪数据按日校验", description = "按日期跨度返回每张表每天的记录数与 code 空值检查")
    public ApiResponse<Map<String, Object>> validateDaily(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String tables) {
        try {
            List<String> tableList = (tables == null || tables.isBlank())
                    ? Collections.emptyList()
                    : Arrays.stream(tables.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .collect(Collectors.toList());
            Map<String, Object> validation = clickHouseSentimentService.validateDaily(startDate, endDate, tableList);
            return ApiResponse.success(validation);
        } catch (Exception e) {
            return ApiResponse.error("按日校验失败: " + e.getMessage());
        }
    }
}
