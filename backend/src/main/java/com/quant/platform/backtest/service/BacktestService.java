package com.quant.platform.backtest.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.quant.platform.backtest.domain.BacktestReport;
import com.quant.platform.backtest.domain.BacktestTask;
import com.quant.platform.backtest.engine.BacktestEngine;
import com.quant.platform.backtest.mapper.BacktestReportMapper;
import com.quant.platform.backtest.mapper.BacktestTaskMapper;
import com.quant.platform.common.exception.BusinessException;
import com.quant.platform.common.exception.ResourceNotFoundException;
import com.quant.platform.strategy.service.StrategyService;
import lombok.RequiredArgsConstructor;
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

    /**
     * 创建并启动回测任务。
     * 注意：不加 @Transactional，因为需要确保任务记录立即提交到数据库后，
     * 异步线程才能读取到。
     */
    public BacktestTask createAndRun(BacktestTask task) {
        // 检查策略是否存在（strategyService.getById 有自己的事务）
        var strategy = strategyService.getById(task.getStrategyId());
        task.setStrategyCode(strategy.getStrategyCode());

        // 直接保存并立即刷到数据库
        taskMapper.insert(task);

        // 异步启动回测（此时记录已确定在数据库中）
        backtestEngine.runBacktest(task.getId());

        log.info("Backtest task created and started: taskId={}, strategy={}", task.getId(), strategy.getStrategyCode());
        return task;
    }

    /**
     * 查询回测任务列表
     */
    public IPage<BacktestTask> listTasks(String strategyCode, String status, int page, int size) {
        BacktestTask.BacktestStatus st = status != null ? BacktestTask.BacktestStatus.valueOf(status) : null;

        LambdaQueryWrapper<BacktestTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(strategyCode != null, BacktestTask::getStrategyCode, strategyCode)
                .eq(st != null, BacktestTask::getStatus, st)
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
     * 删除回测任务和报告
     */
    @Transactional
    public void deleteTask(Long taskId) {
        // 先删 report（子表），再删 task（父表），避免外键约束冲突
        BacktestReport report = reportMapper.findByTaskId(taskId);
        if (report != null) {
            reportMapper.deleteById(report.getId());
        }
        taskMapper.deleteById(taskId);
    }
}
