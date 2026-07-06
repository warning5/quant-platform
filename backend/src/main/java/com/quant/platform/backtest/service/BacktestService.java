package com.quant.platform.backtest.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.quant.platform.backtest.domain.BacktestReport;
import com.quant.platform.backtest.domain.BacktestTask;
import com.quant.platform.backtest.domain.EquityCurve;
import com.quant.platform.backtest.domain.RebalanceRecord;
import com.quant.platform.backtest.dto.BacktestRecommendedConfig;
import com.quant.platform.backtest.engine.BacktestEngine;
import com.quant.platform.backtest.mapper.BacktestReportMapper;
import com.quant.platform.backtest.mapper.BacktestTaskMapper;
import com.quant.platform.backtest.mapper.EquityCurveMapper;
import com.quant.platform.backtest.mapper.RebalanceRecordMapper;
import com.quant.platform.common.exception.BusinessException;
import com.quant.platform.common.exception.ResourceNotFoundException;
import com.quant.platform.strategy.service.StrategyService;
import lombok.RequiredArgsConstructor;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 回测服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestService {

    private final BacktestTaskMapper taskMapper;
    private final BacktestReportMapper reportMapper;
    private final BacktestEngine backtestEngine;
    private final StrategyService strategyService;
    private final EquityCurveMapper equityCurveMapper;
    private final RebalanceRecordMapper rebalanceRecordMapper;

    /**
     * 创建并启动回测任务。
     * 注意：不加 @Transactional，因为需要确保任务记录立即提交到数据库后，
     * 异步线程才能读取到。
     */
    public BacktestTask createAndRun(BacktestTask task) {
        boolean isScreen = "SCREEN".equalsIgnoreCase(task.getSignalSource());
        if (isScreen) {
            // SCREEN 模式不需要 strategyId（或 strategyId 为空），使用因子筛选选股
            task.setStrategyCode(null); // SCREEN 模式没有策略代码
            taskMapper.insert(task);
        } else {
            // STRATEGY 模式：检查策略是否存在
            var strategy = strategyService.getById(task.getStrategyId());
            task.setStrategyCode(strategy.getStrategyCode());
            task.setStrategyName(strategy.getStrategyName());
            taskMapper.insert(task);
        }

        // 异步启动回测（此时记录已确定在数据库中）
        backtestEngine.runBacktest(task.getId());

        log.info("Backtest task created and started: taskId={}, signalSource={}",
                task.getId(), task.getSignalSource());
        return task;
    }

    /**
     * 查询回测任务列表
     */
    public IPage<BacktestTask> listTasks(String strategyCode, String status, String signalSource, int page, int size) {
        BacktestTask.BacktestStatus st = status != null ? BacktestTask.BacktestStatus.valueOf(status) : null;

        LambdaQueryWrapper<BacktestTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(strategyCode != null, BacktestTask::getStrategyCode, strategyCode)
                .eq(st != null, BacktestTask::getStatus, st)
                .eq(signalSource != null, BacktestTask::getSignalSource, signalSource)
                .orderByDesc(BacktestTask::getCreatedAt);

        return taskMapper.selectPage(new Page<>(page + 1, size), wrapper);
    }

    /**
     * 获取回测任务详情
     */
    public BacktestTask getTask(Long taskId) {
        BacktestTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new ResourceNotFoundException("回测任务", taskId);
        }
        // 填充策略名称（仅 STRATEGY 模式）
        if (task.getStrategyId() != null) {
            try {
                var strategy = strategyService.getById(task.getStrategyId());
                task.setStrategyName(strategy.getStrategyName());
            } catch (Exception e) {
                log.warn("Failed to resolve strategy name for taskId={}, strategyId={}", taskId, task.getStrategyId());
            }
        }
        return task;
    }

    /**
     * 获取回测报告
     */
    public BacktestReport getReport(Long taskId) {
        BacktestReport report = reportMapper.findByTaskId(taskId);
        if (report == null) {
            throw new ResourceNotFoundException("回测报告 (taskId=" + taskId + ") 尚未生成");
        }
        return report;
    }

    /**
     * 获取报告（直接按reportId）
     */
    public BacktestReport getReportById(Long reportId) {
        BacktestReport report = reportMapper.selectById(reportId);
        if (report == null) {
            throw new ResourceNotFoundException("回测报告", reportId);
        }
        return report;
    }

    /**
     * 取消回测（仅PENDING状态可取消）
     */
    @Transactional
    public BacktestTask cancelTask(Long taskId) {
        BacktestTask task = getTask(taskId);
        if (task.getStatus() != BacktestTask.BacktestStatus.PENDING) {
            throw new BusinessException("仅PENDING状态的任务可以取消");
        }
        task.setStatus(BacktestTask.BacktestStatus.CANCELLED);
        taskMapper.updateById(task);
        return task;
    }

    /**
     * 删除回测任务和报告，级联删除 rebalance_record / equity_curve
     */
    @Transactional
    public void deleteTask(Long taskId) {
        // 先删 report（子表），再删任务关联记录
        BacktestReport report = reportMapper.findByTaskId(taskId);
        if (report != null) {
            reportMapper.deleteById(report.getId());
        }
        try { equityCurveMapper.deleteByTaskId(taskId); } catch (Exception ignored) {}
        try { rebalanceRecordMapper.deleteByTaskId(taskId); } catch (Exception ignored) {}
        taskMapper.deleteById(taskId);
    }

    /**
     * 重跑回测任务：清空旧结果，重置状态，重新触发引擎。
     * 所有终态（COMPLETED / FAILED / CANCELLED）及后端重启后遗留的 RUNNING/PENDING 僵尸任务均可重跑。
     */
    @Transactional
    public BacktestTask rerunTask(Long taskId) {
        BacktestTask task = getTask(taskId);
        // 1. 清空旧报告
        BacktestReport oldReport = reportMapper.findByTaskId(taskId);
        if (oldReport != null) {
            reportMapper.deleteById(oldReport.getId());
        }
        // 1a. 清空 SCREEN 模式下的曲线/调仓记录
        try { equityCurveMapper.deleteByTaskId(taskId); } catch (Exception ignored) {}
        try { rebalanceRecordMapper.deleteByTaskId(taskId); } catch (Exception ignored) {}
        // 2. 重置任务状态
        task.setStatus(BacktestTask.BacktestStatus.PENDING);
        task.setProgress(0);
        task.setErrorMessage(null);
        taskMapper.updateById(task);
        log.info("回测任务重跑: taskId={}, strategy={}", taskId, task.getStrategyCode());
        // 3. 异步触发引擎（事务提交后执行，确保状态已持久化）
        backtestEngine.runBacktest(taskId);
        return task;
    }

    /**
     * 查询调仓记录（从 rebalance_record 表，SCREEN 模式使用）
     */
    public List<RebalanceRecord> getRebalanceRecords(Long taskId) {
        return rebalanceRecordMapper.findByTaskId(taskId);
    }

    /**
     * 查询权益曲线（从 equity_curve 表，SCREEN 模式使用）
     */
    public List<EquityCurve> getEquityCurves(Long taskId) {
        return equityCurveMapper.findByTaskId(taskId);
    }

    /**
     * 根据回测结果计算推荐的模拟盘参数。
     * 基本参数取自回测任务配置，再根据回测绩效指标（最大回撤、盈亏比、波动率）做自适应微调。
     */
    public BacktestRecommendedConfig calculateRecommendedConfig(Long taskId) {
        BacktestTask task = getTask(taskId);
        BacktestReport report = getReport(taskId);

        List<String> reasons = new ArrayList<>();

        // ---- 基本参数：从回测任务取，兜底默认值 ----
        BigDecimal stopLoss = task.getStopLossPct() != null ? task.getStopLossPct() : new BigDecimal("0.05");
        BigDecimal takeProfit = task.getStopProfitPct() != null ? task.getStopProfitPct() : new BigDecimal("0.10");
        Integer maxPositions = task.getMaxPositionCount() != null ? task.getMaxPositionCount() : 8;
        String rebalanceFreq = task.getRebalanceFreq() != null ? task.getRebalanceFreq() : "MONTHLY";
        BigDecimal commissionRate = task.getCommissionRate() != null ? task.getCommissionRate() : new BigDecimal("0.0003");
        BigDecimal slippageRate = task.getSlippageRate() != null ? task.getSlippageRate() : new BigDecimal("0.001");
        String benchmarkCode = task.getBenchmarkCode() != null ? task.getBenchmarkCode() : "000300";

        // ---- 根据回测表现微调止损 ----
        if (report.getMaxDrawdown() != null) {
            BigDecimal maxDD = report.getMaxDrawdown().abs();
            if (maxDD.compareTo(new BigDecimal("0.15")) > 0) {
                BigDecimal tightened = maxDD.multiply(new BigDecimal("0.8")).min(new BigDecimal("0.12"));
                stopLoss = tightened;
                reasons.add(String.format("回测最大回撤 %.1f%%，止损收紧至 %.1f%%",
                        maxDD.multiply(BigDecimal.valueOf(100)).doubleValue(),
                        stopLoss.multiply(BigDecimal.valueOf(100)).doubleValue()));
            }
        }

        // ---- 止盈调整：盈亏比 < 2 时按 R:R=2 调整 ----
        if (report.getProfitLossRatio() != null) {
            BigDecimal plr = report.getProfitLossRatio();
            if (plr.compareTo(new BigDecimal("2.0")) < 0) {
                takeProfit = stopLoss.multiply(new BigDecimal("2.0"));
                reasons.add(String.format("回测盈亏比 %.1f 偏低，止盈调整为止损的 2 倍 (%.1f%%)",
                        plr.doubleValue(),
                        takeProfit.multiply(BigDecimal.valueOf(100)).doubleValue()));
            }
        }

        // ---- 最大回撤限制：回测最大回撤 × 1.2，留 20% 余量 ----
        BigDecimal maxDrawdownPct = new BigDecimal("0.15");
        if (report.getMaxDrawdown() != null) {
            BigDecimal maxDD = report.getMaxDrawdown().abs();
            maxDrawdownPct = maxDD.multiply(new BigDecimal("1.2"))
                    .max(new BigDecimal("0.10"))
                    .min(new BigDecimal("0.25"));
            reasons.add(String.format("基于回测最大回撤 %.1f%%，风控限制设为 %.1f%%",
                    maxDD.multiply(BigDecimal.valueOf(100)).doubleValue(),
                    maxDrawdownPct.multiply(BigDecimal.valueOf(100)).doubleValue()));
        }

        // ---- 大盘择时：年化波动率 > 25% 建议开启 ----
        int timingEnabled = 0;
        if (report.getVolatility() != null && report.getVolatility().compareTo(new BigDecimal("0.25")) > 0) {
            timingEnabled = 1;
            reasons.add(String.format("年化波动率 %.1f%% 较高，建议开启大盘择时",
                    report.getVolatility().multiply(BigDecimal.valueOf(100)).doubleValue()));
        }

        // ---- 单股仓位上限：等权 1/maxPositions，上限 20% ----
        BigDecimal maxPositionPct = BigDecimal.ONE.divide(BigDecimal.valueOf(maxPositions), 4, RoundingMode.HALF_UP);
        if (maxPositionPct.compareTo(new BigDecimal("0.20")) > 0) {
            maxPositionPct = new BigDecimal("0.20");
        }

        // ---- 资金分配模式 ----
        String allocationMode = "equal";
        if ("SCORE_PROPORTIONAL".equals(task.getWeightMode())) {
            allocationMode = "dynamic";
        }

        if (reasons.isEmpty()) {
            reasons.add("参数取自回测配置，表现稳定无需调整");
        }

        return BacktestRecommendedConfig.builder()
                .stopLossPct(stopLoss)
                .takeProfitPct(takeProfit)
                .maxPositions(maxPositions)
                .rebalanceFreq(rebalanceFreq)
                .commissionRate(commissionRate)
                .slippageRate(slippageRate)
                .benchmarkCode(benchmarkCode)
                .maxPositionPct(maxPositionPct)
                .maxDrawdownPct(maxDrawdownPct)
                .timingEnabled(timingEnabled)
                .allocationMode(allocationMode)
                .reason(String.join("；", reasons))
                .build();
    }
}
