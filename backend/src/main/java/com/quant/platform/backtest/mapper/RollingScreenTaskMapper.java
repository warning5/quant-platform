package com.quant.platform.backtest.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.quant.platform.backtest.domain.RollingScreenTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 滚动选股回测任务 Mapper
 */
@Mapper
public interface RollingScreenTaskMapper extends BaseMapper<RollingScreenTask> {

    /**
     * 查询运行中的任务（PENDING 或 RUNNING）
     */
    @Select("""
            SELECT * FROM rolling_screen_task
            WHERE status = 'PENDING' OR status = 'RUNNING'
            ORDER BY created_at
            """)
    List<RollingScreenTask> findRunningTasks();

    /**
     * 根据状态查询任务列表（分页在 Service 层处理）
     */
    @Select("""
            SELECT * FROM rolling_screen_task
            WHERE #{status} IS NULL OR status = #{status}
            ORDER BY created_at DESC
            LIMIT #{limit}
            """)
    List<RollingScreenTask> findRecent(@Param("status") String status, @Param("limit") int limit);
}
