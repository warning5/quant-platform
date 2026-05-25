package com.quant.platform.backtest.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.quant.platform.backtest.domain.RebalanceRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/**
 * 调仓记录 Mapper
 */
@Mapper
public interface RebalanceRecordMapper extends BaseMapper<RebalanceRecord> {

    /**
     * 查询某任务的所有调仓记录（按日期升序）
     */
    @Select("""
            SELECT * FROM rebalance_record
            WHERE task_id = #{taskId}
            ORDER BY rebalance_date ASC
            """)
    List<RebalanceRecord> findByTaskId(@Param("taskId") Long taskId);

    /**
     * 查询某任务最近 N 条调仓记录
     */
    @Select("""
            SELECT * FROM rebalance_record
            WHERE task_id = #{taskId}
            ORDER BY rebalance_date DESC
            LIMIT #{limit}
            """)
    List<RebalanceRecord> findRecent(@Param("taskId") Long taskId, @Param("limit") int limit);

    /**
     * 删除某任务的所有调仓记录（删任务时级联删除）
     */
    @Select("DELETE FROM rebalance_record WHERE task_id = #{taskId}")
    Integer deleteByTaskId(@Param("taskId") Long taskId);
}
