package com.quant.platform.backtest.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.quant.platform.backtest.domain.BacktestReport;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 回测报告Mapper
 */
@Mapper
public interface BacktestReportMapper extends BaseMapper<BacktestReport> {

    /**
     * 根据任务ID查询回测报告
     */
    @Select("SELECT * FROM backtest_report WHERE task_id = #{taskId}")
    BacktestReport findByTaskId(@Param("taskId") Long taskId);

    /**
     * 根据策略代码查询回测报告
     */
    @Select("SELECT * FROM backtest_report WHERE strategy_code = #{strategyCode} ORDER BY created_at DESC")
    List<BacktestReport> findByStrategyCode(@Param("strategyCode") String strategyCode);
}
