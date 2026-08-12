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
     * 附带最新回测报告指标与置信度，减少前端 N+1 请求。
     */
    @Select("<script>" +
            "SELECT s.id, s.strategy_name AS strategyName, s.strategy_code AS strategyCode, " +
            "       s.strategy_type AS strategyType, s.status, s.rebalance_frequency AS rebalanceFrequency, " +
            "       MAX(r.recommend_date) AS latestDate, " +
            "       (SELECT br.total_return FROM backtest_report br " +
            "        JOIN backtest_task bt ON bt.id = br.task_id " +
            "        WHERE bt.strategy_id = s.id AND bt.status = 'COMPLETED' " +
            "        ORDER BY bt.completed_at DESC LIMIT 1) AS totalReturn, " +
            "       (SELECT br.annual_return FROM backtest_report br " +
            "        JOIN backtest_task bt ON bt.id = br.task_id " +
            "        WHERE bt.strategy_id = s.id AND bt.status = 'COMPLETED' " +
            "        ORDER BY bt.completed_at DESC LIMIT 1) AS annualReturn, " +
            "       (SELECT br.sharpe_ratio FROM backtest_report br " +
            "        JOIN backtest_task bt ON bt.id = br.task_id " +
            "        WHERE bt.strategy_id = s.id AND bt.status = 'COMPLETED' " +
            "        ORDER BY bt.completed_at DESC LIMIT 1) AS sharpeRatio, " +
            "       (SELECT br.win_rate FROM backtest_report br " +
            "        JOIN backtest_task bt ON bt.id = br.task_id " +
            "        WHERE bt.strategy_id = s.id AND bt.status = 'COMPLETED' " +
            "        ORDER BY bt.completed_at DESC LIMIT 1) AS winRate, " +
            "       (SELECT sc.score FROM strategy_confidence sc " +
            "        WHERE sc.strategy_id = s.id " +
            "        ORDER BY sc.data_as_of_date DESC, sc.id DESC LIMIT 1) AS confidenceScore, " +
            "       (SELECT sc.hit_rate_value FROM strategy_confidence sc " +
            "        WHERE sc.strategy_id = s.id " +
            "        ORDER BY sc.data_as_of_date DESC, sc.id DESC LIMIT 1) AS hitRateValue " +
            "FROM strategy_definition s " +
            "JOIN stock_recommendation r ON r.strategy_id = s.id " +
            "<if test='keyword != null and keyword != \"\"'>" +
            "  WHERE s.strategy_name LIKE CONCAT('%', #{keyword}, '%') " +
            "     OR s.strategy_code LIKE CONCAT('%', #{keyword}, '%')" +
            "</if>" +
            "GROUP BY s.id, s.strategy_name, s.strategy_code, s.strategy_type, s.status, s.rebalance_frequency " +
            "ORDER BY latestDate DESC" +
            "</script>")
    List<java.util.Map<String, Object>> findStrategiesWithData(@Param("keyword") String keyword);
}
