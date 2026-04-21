package com.quant.platform.backtest.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.quant.platform.backtest.domain.BacktestTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 回测任务Mapper
 */
@Mapper
public interface BacktestTaskMapper extends BaseMapper<BacktestTask> {

    /**
     * 根据策略ID查询回测任务
     */
    @Select("SELECT * FROM backtest_task WHERE strategy_id = #{strategyId} ORDER BY created_at DESC")
    List<BacktestTask> findByStrategyId(@Param("strategyId") Long strategyId);

    /**
     * 查询运行中的任务
     */
    @Select("SELECT * FROM backtest_task WHERE status = 'RUNNING' OR status = 'PENDING' ORDER BY created_at")
    List<BacktestTask> findRunningTasks();
}
