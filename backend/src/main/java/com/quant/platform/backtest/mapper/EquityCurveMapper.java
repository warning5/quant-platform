package com.quant.platform.backtest.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.quant.platform.backtest.domain.EquityCurve;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 权益曲线 Mapper
 */
@Mapper
public interface EquityCurveMapper extends BaseMapper<EquityCurve> {

    /**
     * 查询某任务的所有权益曲线（按日期升序）
     */
    @Select("""
            SELECT * FROM equity_curve
            WHERE task_id = #{taskId}
            ORDER BY trade_date ASC
            """)
    List<EquityCurve> findByTaskId(@Param("taskId") Long taskId);

    /**
     * 批量插入（回测结束时使用）
     */
    @Insert("""
            INSERT INTO equity_curve (task_id, trade_date, portfolio_value, nav, benchmark_nav, return_pct)
            VALUES (#{ec.taskId}, #{ec.tradeDate}, #{ec.portfolioValue}, #{ec.nav}, #{ec.benchmarkNav}, #{ec.returnPct})
            """)
    Integer insertOne(@Param("ec") EquityCurve ec);

    /**
     * 删除某任务的所有曲线（删任务时级联删除）
     */
    @Select("DELETE FROM equity_curve WHERE task_id = #{taskId}")
    Integer deleteByTaskId(@Param("taskId") Long taskId);
}
