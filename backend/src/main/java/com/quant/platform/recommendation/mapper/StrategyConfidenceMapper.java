package com.quant.platform.recommendation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.quant.platform.recommendation.domain.StrategyConfidence;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Optional;

/**
 * 策略置信度 Mapper
 */
public interface StrategyConfidenceMapper extends BaseMapper<StrategyConfidence> {

    /**
     * 查询某策略最新的置信度记录
     */
    @Select("SELECT * FROM strategy_confidence WHERE strategy_id = #{strategyId} ORDER BY data_as_of_date DESC LIMIT 1")
    Optional<StrategyConfidence> findLatestByStrategyId(@Param("strategyId") Long strategyId);

    /**
     * 查询所有策略的最新置信度（用于列表展示）
     */
    @Select("SELECT sc.* FROM strategy_confidence sc " +
            "INNER JOIN (SELECT strategy_id, MAX(data_as_of_date) AS max_date FROM strategy_confidence GROUP BY strategy_id) latest " +
            "ON sc.strategy_id = latest.strategy_id AND sc.data_as_of_date = latest.max_date " +
            "ORDER BY sc.score DESC")
    List<StrategyConfidence> findAllLatest();

    /**
     * 删除某策略的旧置信度记录（更新时先删后插）
     */
    @Delete("DELETE FROM strategy_confidence WHERE strategy_id = #{strategyId}")
    int deleteByStrategyId(@Param("strategyId") Long strategyId);

    /**
     * 获取某策略的置信度历史（用于趋势图）
     */
    @Select("SELECT * FROM strategy_confidence WHERE strategy_id = #{strategyId} ORDER BY data_as_of_date ASC")
    List<StrategyConfidence> findByStrategyId(@Param("strategyId") Long strategyId);
}
