package com.quant.platform.mp.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.quant.platform.common.dto.ApiResponse;
import com.quant.platform.mp.domain.MpBacktestReport;
import com.quant.platform.mp.domain.MpBacktestTask;
import com.quant.platform.mp.mapper.MpBacktestReportMapper;
import com.quant.platform.mp.mapper.MpBacktestTaskMapper;
import com.quant.platform.strategy.domain.StrategyDefinition;
import com.quant.platform.strategy.mapper.StrategyDefinitionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 小程序策略接口（只读）：列表 / 详情 / 回测表现。
 * 直连 MySQL，复用 common 的 StrategyDefinitionMapper 与本地 backtest mapper。
 */
@RestController
@RequestMapping("/mp/strategies")
@RequiredArgsConstructor
public class MpStrategyController {

    private final StrategyDefinitionMapper strategyDefinitionMapper;
    private final MpBacktestTaskMapper backtestTaskMapper;
    private final MpBacktestReportMapper backtestReportMapper;

    /**
     * 策略列表（仅含推荐数据的策略）
     */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam(required = false) String keyword) {
        List<Map<String, Object>> list = strategyDefinitionMapper.findStrategiesWithData(keyword);
        for (Map<String, Object> item : list) {
            Object latestDateObj = item.get("latestDate");
            Object freqObj = item.get("rebalanceFrequency");
            LocalDate latestDate = parseLocalDate(latestDateObj);
            if (latestDate != null && freqObj instanceof String freq) {
                LocalDate nextDate = nextRebalanceDate(latestDate, freq);
                if (nextDate != null) {
                    item.put("nextRebalanceDate", nextDate);
                }
            }
        }
        return ApiResponse.success(list);
    }

    private LocalDate parseLocalDate(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof LocalDate d) {
            return d;
        }
        if (obj instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        if (obj instanceof Date d) {
            return d.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        }
        if (obj instanceof String s) {
            try {
                return LocalDate.parse(s);
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private LocalDate nextRebalanceDate(LocalDate latestDate, String rebalanceFrequency) {
        if (latestDate == null || rebalanceFrequency == null) {
            return null;
        }
        return switch (rebalanceFrequency.toUpperCase()) {
            case "DAILY" -> latestDate.plusDays(1);
            case "WEEKLY" -> latestDate.plusDays(7);
            case "MONTHLY" -> latestDate.plusMonths(1);
            default -> null;
        };
    }

    /**
     * 策略详情
     */
    @GetMapping("/{id}")
    public ApiResponse<StrategyDefinition> getById(@PathVariable Long id) {
        return ApiResponse.success(strategyDefinitionMapper.selectById(id));
    }

    /**
     * 策略回测表现：取该策略最新一条 COMPLETED 回测任务及其报告
     */
    @GetMapping("/{id}/backtest")
    public ApiResponse<Map<String, Object>> backtest(@PathVariable Long id) {
        QueryWrapper<MpBacktestTask> qw = new QueryWrapper<>();
        qw.eq("strategy_id", id)
          .eq("status", "COMPLETED")
          .orderByDesc("completed_at")
          .last("LIMIT 1");
        MpBacktestTask task = backtestTaskMapper.selectOne(qw);
        if (task == null) {
            return ApiResponse.success(Collections.emptyMap());
        }
        QueryWrapper<MpBacktestReport> rqw = new QueryWrapper<>();
        rqw.eq("task_id", task.getId());
        MpBacktestReport report = backtestReportMapper.selectOne(rqw);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("task", task);
        result.put("report", report);
        return ApiResponse.success(result);
    }
}
