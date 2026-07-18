package com.quant.platform.research.controller;

import com.quant.platform.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 研报数据控制器
 */
@Slf4j
@RestController
@RequestMapping("/research")
@RequiredArgsConstructor
@Tag(name = "研报数据", description = "东方财富个股研报数据查询接口")
public class ResearchController {

    private final JdbcTemplate jdbcTemplate;

    @GetMapping("/overview")
    @Operation(summary = "研报数据概览", description = "获取研报总数、覆盖股票数、最新日期")
    public ApiResponse<Map<String, Object>> getOverview() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Integer totalCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM stock_research_report", Integer.class);
            result.put("totalCount", totalCount != null ? totalCount : 0);

            Integer stockCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(DISTINCT code) FROM stock_research_report", Integer.class);
            result.put("stockCount", stockCount != null ? stockCount : 0);

            String latestDate = jdbcTemplate.queryForObject(
                    "SELECT MAX(report_date) FROM stock_research_report", String.class);
            result.put("latestDate", latestDate != null ? latestDate : "");

            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("获取研报概览失败", e);
            return ApiResponse.error("获取研报概览失败: " + e.getMessage());
        }
    }

    @GetMapping("/list")
    @Operation(summary = "分页查询研报列表", description = "支持按股票代码、名称、标题关键字搜索，支持日期范围和评级过滤")
    public ApiResponse<Map<String, Object>> getList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String rating) {
        try {
            List<String> whereClauses = new ArrayList<>();
            List<Object> params = new ArrayList<>();

            if (keyword != null && !keyword.trim().isEmpty()) {
                whereClauses.add("(code LIKE ? OR name LIKE ? OR report_title LIKE ?)");
                String kw = "%" + keyword.trim() + "%";
                params.add(kw);
                params.add(kw);
                params.add(kw);
            }
            if (startDate != null && !startDate.trim().isEmpty()) {
                whereClauses.add("report_date >= ?");
                params.add(startDate.trim());
            }
            if (endDate != null && !endDate.trim().isEmpty()) {
                whereClauses.add("report_date <= ?");
                params.add(endDate.trim());
            }
            if (rating != null && !rating.trim().isEmpty()) {
                whereClauses.add("rating = ?");
                params.add(rating.trim());
            }

            String where = whereClauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", whereClauses);
            String countSql = "SELECT COUNT(*) FROM stock_research_report" + where;
            Integer total = jdbcTemplate.queryForObject(countSql, Integer.class, params.toArray());
            int totalInt = total != null ? total : 0;

            int offset = (page - 1) * size;
            String dataSql = "SELECT id, code, name, report_title AS reportTitle, rating AS rating, institution AS institution, " +
                    "eps_forecast AS epsForecast, " +
                    "industry AS industry, report_date AS reportDate, pdf_url AS pdfUrl " +
                    "FROM stock_research_report" + where +
                    " ORDER BY report_date DESC LIMIT ? OFFSET ?";
            List<Object> dataParams = new ArrayList<>(params);
            dataParams.add(size);
            dataParams.add(offset);

            List<Map<String, Object>> list = jdbcTemplate.queryForList(dataSql, dataParams.toArray());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("list", list);
            result.put("total", totalInt);
            result.put("page", page);
            result.put("size", size);
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("查询研报列表失败", e);
            return ApiResponse.error("查询研报列表失败: " + e.getMessage());
        }
    }

    @DeleteMapping("/batch-delete")
    @Operation(summary = "批量删除研报", description = "从 MySQL 删除研报数据")
    public ApiResponse<Map<String, Object>> batchDelete(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        List<Number> ids = (List<Number>) body.get("ids");

        if (ids == null || ids.isEmpty()) {
            return ApiResponse.error("请选择要删除的记录");
        }
        try {
            List<Object> idParams = new ArrayList<>(ids);
            String ph = ids.stream().map(i -> "?").collect(java.util.stream.Collectors.joining(","));

            int deleted = jdbcTemplate.update(
                    "DELETE FROM stock_research_report WHERE id IN (" + ph + ")",
                    idParams.toArray());
            result.put("deleted", deleted);

            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("批量删除研报失败: ids={}", ids, e);
            return ApiResponse.error("删除失败: " + e.getMessage());
        }
    }
}
