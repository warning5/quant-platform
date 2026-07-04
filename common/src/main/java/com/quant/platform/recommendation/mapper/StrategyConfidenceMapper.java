package com.quant.platform.recommendation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.quant.platform.recommendation.domain.StrategyConfidence;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Optional;

public interface StrategyConfidenceMapper extends BaseMapper<StrategyConfidence> {

    @Select("SELECT * FROM strategy_confidence WHERE strategy_id = #{strategyId} ORDER BY data_as_of_date DESC LIMIT 1")
    Optional<StrategyConfidence> findLatestByStrategyId(@Param("strategyId") Long strategyId);

    @Select("SELECT sc.* FROM strategy_confidence sc " +
            "INNER JOIN (SELECT strategy_id, MAX(data_as_of_date) AS max_date FROM strategy_confidence GROUP BY strategy_id) latest " +
            "ON sc.strategy_id = latest.strategy_id AND sc.data_as_of_date = latest.max_date " +
            "ORDER BY sc.score DESC")
    List<StrategyConfidence> findAllLatest();

    @Delete("DELETE FROM strategy_confidence WHERE strategy_id = #{strategyId}")
    int deleteByStrategyId(@Param("strategyId") Long strategyId);

    @Select("SELECT * FROM strategy_confidence WHERE strategy_id = #{strategyId} ORDER BY data_as_of_date ASC")
    List<StrategyConfidence> findByStrategyId(@Param("strategyId") Long strategyId);
}
