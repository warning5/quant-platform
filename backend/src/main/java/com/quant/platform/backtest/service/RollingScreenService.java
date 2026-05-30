package com.quant.platform.backtest.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.quant.platform.backtest.domain.RebalanceRecord;
import com.quant.platform.backtest.domain.RollingScreenTask;
import com.quant.platform.backtest.engine.RollingScreenEngine;
import com.quant.platform.backtest.mapper.EquityCurveMapper;
import com.quant.platform.backtest.mapper.RebalanceRecordMapper;
import com.quant.platform.backtest.mapper.RollingScreenTaskMapper;
import com.quant.platform.common.exception.BusinessException;
import com.quant.platform.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 滚动选股回测服务层
 * <p>
 * 职责：
 * 1. 接收前端请求（RollingScreenRequest DTO），转换为 RollingScreenTask 实体存库
 * 2. 调用 {@link RollingScreenEngine} 异步执行回测
 * 3. 提供任务 CRUD 和调仓记录查询
 * <p>
 * 设计原则：参照 {@link com.quant.platform.backtest.service.BacktestService} 模式，
 * createAndRun 不加 @Transactional，确保任务记录立即对异步线程可见。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RollingScreenService {

    private final RollingScreenTaskMapper taskMapper;
    private final RebalanceRecordMapper recordMapper;
    private final EquityCurveMapper equityCurveMapper;
    private final RollingScreenEngine rollingScreenEngine;

    /**
     * 创建并启动滚动选股回测任务。
     * <ol>
     *   <li>将请求参数转为实体，设初始状态为 PENDING</li>
     *   <li>insert 到数据库（立即提交，不加事务）</li>
     *   <li>调用 Engine 异步执行</li>
     * </ol>
     *
     * @param task 前端提交的任务实体（含 screenConfigJson / 回测参数）
     * @return 已入库的任务（含自增 ID）
     */
    public RollingScreenTask createAndRun(RollingScreenTask task) {
        // 设初始状态
        task.setStatus("PENDING");
        task.setProgress(0);

        // 立即存库 —— 不加 @Transactional，确保异步线程能读到
        taskMapper.insert(task);

        // 异步启动回测引擎
        rollingScreenEngine.runRollingScreen(task.getId());

        log.info("滚动选股回测任务创建并启动: taskId={}, name={}, {}~{}",
                task.getId(), task.getTaskName(), task.getStartDate(), task.getEndDate());
        return task;
    }

    /**
     * 分页查询任务列表（按创建时间倒序）。
     *
     * @param status  状态过滤（可选：PENDING/RUNNING/COMPLETED/FAILED/CANCELLED）
     * @param page    页码（0-based）
     * @param size    每页条数
     * @return 分页结果
     */
    public IPage<RollingScreenTask> listTasks(String status, int page, int size) {
        LambdaQueryWrapper<RollingScreenTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(status != null && !status.isEmpty(), RollingScreenTask::getStatus, status)
                .orderByDesc(RollingScreenTask::getCreatedAt);
        return taskMapper.selectPage(new Page<>(page + 1, size), wrapper);
    }

    /**
     * 获取任务详情。
     *
     * @param taskId 任务 ID
     * @return 任务实体
     * @throws ResourceNotFoundException 任务不存在时抛出
     */
    public RollingScreenTask getTask(Long taskId) {
        RollingScreenTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new ResourceNotFoundException("滚动选股回测任务", taskId);
        }
        return task;
    }

    /**
     * 获取某任务的所有调仓记录（按日期升序）。
     *
     * @param taskId 任务 ID
     * @return 调仓记录列表
     */
    public List<RebalanceRecord> getRecords(Long taskId) {
        // 先确认任务存在
        getTask(taskId);
        return recordMapper.findByTaskId(taskId);
    }

    /**
     * 取消任务（仅 PENDING 状态可取消）。
     * <p>
     * RUNNING 状态的取消由 Engine 内部通过检查 task.status == "CANCELLED" 实现，
     * 本方法只做状态标记。Engine 每次调仓循环前会检查取消标志。
     *
     * @param taskId 任务 ID
     * @return 更新后的任务
     */
    @Transactional
    public RollingScreenTask cancelTask(Long taskId) {
        RollingScreenTask task = getTask(taskId);
        String status = task.getStatus();
        if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
            throw new BusinessException("已结束的任务（" + status + "）无法取消");
        }
        // PENDING / RUNNING → 标记为 CANCELLED，Engine 下一次检查时会退出
        task.setStatus("CANCELLED");
        taskMapper.updateById(task);
        log.info("任务已标记取消: taskId={}", taskId);
        return task;
    }

    /**
     * 删除任务及其级联数据（调仓记录）。
     * <p>
     * 仅已结束状态（COMPLETED / FAILED / CANCELLED）可删除。

     *
     * @param taskId 任务 ID
     */
    @Transactional
    public void deleteTask(Long taskId) {
        RollingScreenTask task = getTask(taskId);
        String status = task.getStatus();
        if ("RUNNING".equals(status) || "PENDING".equals(status)) {
            throw new BusinessException("运行中的任务无法删除，请先取消");
        }
        // 级联删除调仓记录
        recordMapper.deleteByTaskId(taskId);
        // 删除任务
        taskMapper.deleteById(taskId);
        log.info("任务已删除: taskId={}, name={}", taskId, task.getTaskName());
    }

    /**
     * 重跑滚动选股回测：清空旧净值曲线和调仓记录，重置状态，重新触发引擎。
     * 仅 COMPLETED / FAILED / CANCELLED 状态可重跑。
     *
     * @param taskId 任务 ID
     * @return 重置后的任务
     */
    @Transactional
    public RollingScreenTask rerunTask(Long taskId) {
        RollingScreenTask task = getTask(taskId);
        // 所有状态均可重跑（RUNNING/PENDING 在后端重启后会成为僵尸任务，需要强制重跑清理）
        // 1. 清空旧数据
        equityCurveMapper.deleteByTaskId(taskId);
        recordMapper.deleteByTaskId(taskId);
        // 2. 重置任务摘要字段
        task.setStatus("PENDING");
        task.setProgress(0);
        task.setErrorMessage(null);
        task.setFinalNav(null);
        task.setTotalReturn(null);
        task.setAnnualReturn(null);
        task.setMaxDrawdown(null);
        task.setSharpeRatio(null);
        task.setBenchmarkReturn(null);
        task.setTotalTrades(null);
        task.setWinRate(null);
        taskMapper.updateById(task);
        log.info("滚动回测任务重跑: taskId={}, name={}", taskId, task.getTaskName());
        // 3. 重新触发引擎（事务提交后执行）
        rollingScreenEngine.runRollingScreen(taskId);
        return task;
    }

    /**
     * 查询正在运行或排队的任务（用于并发控制检查）。
     *
     * @return 运行中/排队中的任务列表
     */
    public List<RollingScreenTask> findRunningTasks() {
        return taskMapper.findRunningTasks();
    }
}
