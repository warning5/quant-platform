package com.quant.platform.mp.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.quant.platform.common.dto.ApiResponse;
import com.quant.platform.mp.domain.MpFactorDefinition;
import com.quant.platform.mp.domain.MpFactorIcRecord;
import com.quant.platform.mp.mapper.MpFactorIcRecordMapper;
import com.quant.platform.mp.mapper.MpFactorMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 小程序因子接口（只读）：列表 / 详情 / IC 趋势。
 * 直连 MySQL，复用主后端 factor_definition / factor_ic_record 表。
 */
@RestController
@RequestMapping("/mp/factors")
@RequiredArgsConstructor
public class MpFactorController {

    private final MpFactorMapper factorMapper;
    private final MpFactorIcRecordMapper factorIcRecordMapper;

    /**
     * 因子列表（支持关键词 / 分类 / 状态筛选）
     */
    @GetMapping
    public ApiResponse<List<MpFactorDefinition>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status) {
        QueryWrapper<MpFactorDefinition> qw = new QueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.like("factor_name", keyword).or().like("factor_code", keyword));
        }
        if (category != null && !category.isBlank()) {
            qw.eq("category", category);
        }
        if (status != null && !status.isBlank()) {
            qw.eq("status", status);
        }
        qw.orderByDesc("id");
        return ApiResponse.success(factorMapper.selectList(qw));
    }

    /**
     * 因子详情
     */
    @GetMapping("/{id}")
    public ApiResponse<MpFactorDefinition> getById(@PathVariable Long id) {
        return ApiResponse.success(factorMapper.selectById(id));
    }

    /**
     * 因子 IC 趋势：按 id 解析 factorCode，返回 IC 时间序列
     */
    @GetMapping("/{id}/ic-trend")
    public ApiResponse<Map<String, Object>> icTrend(
            @PathVariable Long id,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "5") int forwardDays) {
        MpFactorDefinition factor = factorMapper.selectById(id);
        if (factor == null) {
            return ApiResponse.success(Collections.emptyMap());
        }
        QueryWrapper<MpFactorIcRecord> qw = new QueryWrapper<>();
        qw.eq("factor_code", factor.getFactorCode()).eq("forward_days", forwardDays);
        if (startDate != null && !startDate.isBlank()) {
            qw.ge("trade_date", LocalDate.parse(startDate));
        }
        if (endDate != null && !endDate.isBlank()) {
            qw.le("trade_date", LocalDate.parse(endDate));
        }
        qw.orderByAsc("trade_date");
        List<MpFactorIcRecord> trend = factorIcRecordMapper.selectList(qw);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("factor", factor);
        result.put("forwardDays", forwardDays);
        result.put("trend", trend);
        return ApiResponse.success(result);
    }
}
