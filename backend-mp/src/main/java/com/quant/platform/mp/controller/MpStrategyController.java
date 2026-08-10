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

import java.util.Collections;
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
        return ApiResponse.success(strategyDefinitionMapper.findStrategiesWithData(keyword));
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
