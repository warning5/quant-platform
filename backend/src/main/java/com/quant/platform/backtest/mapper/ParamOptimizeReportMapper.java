package com.quant.platform.backtest.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.quant.platform.backtest.domain.ParamOptimizeReport;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Delete;

import java.util.List;

/**
 * 参数优化报告Mapper
 */
@Mapper
public interface ParamOptimizeReportMapper extends BaseMapper<ParamOptimizeReport> {

    /**
     * 根据jobId查询优化报告
     */
    @Select("SELECT * FROM param_optimize_report WHERE job_id = #{jobId}")
    ParamOptimizeReport findByJobId(@Param("jobId") String jobId);

    /**
     * 根据策略ID查询优化报告列表
     */
    @Select("SELECT * FROM param_optimize_report WHERE strategy_id = #{strategyId} ORDER BY created_at DESC")
    List<ParamOptimizeReport> findByStrategyId(@Param("strategyId") Long strategyId);

    /**
     * 查询最近的优化报告
     */
    @Select("SELECT * FROM param_optimize_report ORDER BY created_at DESC LIMIT #{limit}")
    List<ParamOptimizeReport> findRecent(@Param("limit") int limit);

    /**
     * 根据jobId删除优化报告
     */
    @Delete("DELETE FROM param_optimize_report WHERE job_id = #{jobId}")
    int deleteByJobId(@Param("jobId") String jobId);
}
