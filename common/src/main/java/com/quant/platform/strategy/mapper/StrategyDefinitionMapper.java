package com.quant.platform.strategy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.quant.platform.strategy.domain.StrategyDefinition;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 策略定义Mapper（共享，backend 和 backend-mp 共用）
 */
@Mapper
public interface StrategyDefinitionMapper extends BaseMapper<StrategyDefinition> {

    @Select("SELECT COUNT(*) > 0 FROM strategy_definition WHERE strategy_code = #{strategyCode}")
    boolean existsByStrategyCode(@Param("strategyCode") String strategyCode);

    @Select("SELECT COUNT(*) > 0 FROM strategy_definition WHERE strategy_code = #{strategyCode} AND id != #{excludeId}")
    boolean existsByStrategyCodeExcluding(@Param("strategyCode") String strategyCode, @Param("excludeId") Long excludeId);

    /**
     * 查询有推荐数据的策略列表（用于小程序）
     */
    @Select("<script>" +
            "SELECT s.id, s.strategy_name AS strategyName, s.strategy_code AS strategyCode, " +
            "       s.strategy_type AS strategyType, s.status, " +
            "       MAX(r.recommend_date) AS latestDate " +
            "FROM strategy_definition s " +
            "JOIN stock_recommendation r ON r.strategy_id = s.id " +
            "<if test='keyword != null and keyword != \"\"'>" +
            "  WHERE s.strategy_name LIKE CONCAT('%', #{keyword}, '%') " +
            "     OR s.strategy_code LIKE CONCAT('%', #{keyword}, '%')" +
            "</if>" +
            "GROUP BY s.id, s.strategy_name, s.strategy_code, s.strategy_type, s.status " +
            "ORDER BY latestDate DESC" +
            "</script>")
    List<java.util.Map<String, Object>> findStrategiesWithData(@Param("keyword") String keyword);
}
