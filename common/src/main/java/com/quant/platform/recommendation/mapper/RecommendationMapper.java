package com.quant.platform.recommendation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.quant.platform.recommendation.domain.StockRecommendation;
import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 推荐记录 Mapper（共享，backend 和 backend-mp 共用）
 */
@Mapper
public interface RecommendationMapper extends BaseMapper<StockRecommendation> {

    /**
     * 查询指定策略+日期的所有推荐
     */
    @Select("SELECT * FROM stock_recommendation WHERE strategy_id = #{strategyId} AND recommend_date = #{recommendDate} ORDER BY rank_num")
    List<StockRecommendation> findByStrategyAndDate(@Param("strategyId") Long strategyId, @Param("recommendDate") LocalDate recommendDate);

    /**
     * 查询指定策略+日期+权重模式的所有推荐
     */
    @Select("SELECT * FROM stock_recommendation WHERE strategy_id = #{strategyId} AND recommend_date = #{recommendDate} AND weight_mode = #{weightMode} ORDER BY rank_num")
    List<StockRecommendation> findByStrategyAndDateAndMode(@Param("strategyId") Long strategyId, @Param("recommendDate") LocalDate recommendDate, @Param("weightMode") String weightMode);

    /**
     * 查询最新的一条推荐记录
     */
    @Select("SELECT * FROM stock_recommendation ORDER BY recommend_date DESC, id DESC LIMIT 1")
    StockRecommendation findLatest();

    /**
     * 查询最近的策略+日期组合（按时间倒序）
     */
    @Select("SELECT DISTINCT strategy_id, recommend_date FROM stock_recommendation WHERE strategy_id IS NOT NULL ORDER BY recommend_date DESC, strategy_id DESC LIMIT #{limit}")
    List<Map<String, Object>> findRecentStrategyDates(@Param("limit") int limit);

    /**
     * 查询最近的策略+日期+权重模式组合（按时间倒序，按模式分组）
     */
    @Select("SELECT DISTINCT strategy_id, recommend_date, weight_mode FROM stock_recommendation WHERE strategy_id IS NOT NULL ORDER BY recommend_date DESC, strategy_id DESC, weight_mode LIMIT #{limit}")
    List<Map<String, Object>> findRecentStrategyDateModes(@Param("limit") int limit);

    /** 拉取还有 nextDayReturn IS NULL 记录的策略+日期组合（追踪专用） */
    @Select("SELECT DISTINCT strategy_id, recommend_date FROM stock_recommendation " +
            "WHERE strategy_id IS NOT NULL AND next_day_return IS NULL " +
            "ORDER BY recommend_date DESC, strategy_id DESC LIMIT #{limit}")
    List<Map<String, Object>> findUntrackedStrategyDates(@Param("limit") int limit);

    /** 拉取还有 nextDayReturn IS NULL 记录的策略+日期+模式组合（追踪专用，按模式去重） */
    @Select("SELECT DISTINCT strategy_id, recommend_date, weight_mode FROM stock_recommendation " +
            "WHERE strategy_id IS NOT NULL AND next_day_return IS NULL " +
            "ORDER BY recommend_date DESC, strategy_id DESC, weight_mode LIMIT #{limit}")
    List<Map<String, Object>> findUntrackedStrategyDateModes(@Param("limit") int limit);

    /**
     * 统计指定策略+日期的推荐数量
     */
    @Select("SELECT COUNT(*) FROM stock_recommendation WHERE strategy_id = #{strategyId} AND recommend_date = #{recommendDate}")
    int countByStrategyAndDate(@Param("strategyId") Long strategyId, @Param("recommendDate") LocalDate recommendDate);

    /**
     * 统计指定策略+日期+模式的推荐数量
     */
    @Select("SELECT COUNT(*) FROM stock_recommendation WHERE strategy_id = #{strategyId} AND recommend_date = #{recommendDate} AND weight_mode = #{weightMode}")
    int countByStrategyAndDateAndMode(@Param("strategyId") Long strategyId, @Param("recommendDate") LocalDate recommendDate, @Param("weightMode") String weightMode);

    /**
     * 删除指定策略+日期的所有推荐
     */
    @Delete("DELETE FROM stock_recommendation WHERE strategy_id = #{strategyId} AND recommend_date = #{recommendDate}")
    int deleteByStrategyAndDate(@Param("strategyId") Long strategyId, @Param("recommendDate") LocalDate recommendDate);

    /**
     * 删除指定策略+日期+模式的所有推荐
     */
    @Delete("DELETE FROM stock_recommendation WHERE strategy_id = #{strategyId} AND recommend_date = #{recommendDate} AND weight_mode = #{weightMode}")
    int deleteByStrategyAndDateAndMode(@Param("strategyId") Long strategyId, @Param("recommendDate") LocalDate recommendDate, @Param("weightMode") String weightMode);

    /**
     * 按策略ID查询所有推荐
     */
    @Select("SELECT * FROM stock_recommendation WHERE strategy_id = #{strategyId} ORDER BY recommend_date DESC, rank_num LIMIT #{limit}")
    List<StockRecommendation> findByStrategyId(@Param("strategyId") Long strategyId, @Param("limit") int limit);

    /**
     * 获取所有有推荐记录的策略ID列表（去重）
     */
    @Select("SELECT DISTINCT strategy_id FROM stock_recommendation ORDER BY strategy_id")
    List<Long> findDistinctStrategyIds();

    /**
     * 获取指定策略的推荐日期列表（去重倒序）
     */
    @Select("SELECT DISTINCT recommend_date FROM stock_recommendation WHERE strategy_id = #{strategyId} ORDER BY recommend_date DESC LIMIT #{limit}")
    List<LocalDate> findDatesByStrategyId(@Param("strategyId") Long strategyId, @Param("limit") int limit);

    /**
     * 获取指定策略+日期的所有模式列表（用于前端模式筛选）
     */
    @Select("SELECT DISTINCT weight_mode FROM stock_recommendation WHERE strategy_id = #{strategyId} AND recommend_date = #{recommendDate} AND weight_mode IS NOT NULL")
    List<String> findModesByStrategyAndDate(@Param("strategyId") Long strategyId, @Param("recommendDate") LocalDate recommendDate);

    /**
     * 查询指定股票最新一条推荐的买入建议价（用于模拟盘信号价对齐）
     */
    @Select("SELECT suggested_buy_price FROM stock_recommendation WHERE stock_code = #{code} ORDER BY recommend_date DESC, id DESC LIMIT 1")
    BigDecimal findLatestSuggestedBuyPrice(@Param("code") String code);

    /**
     * 批次历史汇总（用于小程序表现页）
     */
    @Select("<script>" +
            "SELECT d.strategy_id, d.recommend_date AS date, " +
            "  s.strategy_name AS strategyName, " +
            "  COUNT(*) AS total, " +
            "  SUM(CASE WHEN r.next_day_return IS NOT NULL THEN 1 ELSE 0 END) AS tracked, " +
            "  SUM(CASE WHEN r.next_day_return IS NOT NULL AND r.next_day_return > 0 THEN 1 ELSE 0 END) AS hitCount, " +
            "  CASE WHEN SUM(CASE WHEN r.next_day_return IS NOT NULL THEN 1 ELSE 0 END) > 0 " +
            "    THEN SUM(CASE WHEN r.next_day_return IS NOT NULL AND r.next_day_return > 0 THEN 1 ELSE 0 END) * 1.0 / " +
            "         SUM(CASE WHEN r.next_day_return IS NOT NULL THEN 1 ELSE 0 END) " +
            "    ELSE 0 END AS hitRate, " +
            "  AVG(CASE WHEN r.next_day_return IS NOT NULL THEN r.next_day_return ELSE NULL END) AS avgDayReturn, " +
            "  AVG(CASE WHEN r.next_week_return IS NOT NULL THEN r.next_week_return ELSE NULL END) AS avgWeekReturn, " +
            "  AVG(CASE WHEN r.next_month_return IS NOT NULL THEN r.next_month_return ELSE NULL END) AS avgMonthReturn " +
            "FROM (SELECT DISTINCT strategy_id, recommend_date FROM stock_recommendation " +
            "      WHERE strategy_id IS NOT NULL " +
            "      <if test='strategyId != null'>AND strategy_id = #{strategyId}</if> " +
            "      ORDER BY recommend_date DESC, strategy_id DESC LIMIT #{limit}) d " +
            "JOIN stock_recommendation r ON r.strategy_id = d.strategy_id AND r.recommend_date = d.recommend_date " +
            "LEFT JOIN strategy_definition s ON s.id = d.strategy_id " +
            "GROUP BY d.strategy_id, d.recommend_date, s.strategy_name " +
            "ORDER BY d.recommend_date DESC"
            + "</script>")
    List<Map<String, Object>> findBatchHistory(@Param("limit") int limit, @Param("strategyId") Long strategyId);

    /**
     * 命中率统计（用于小程序推荐页）
     */
    @Select("SELECT " +
            "  COUNT(*) AS total, " +
            "  SUM(CASE WHEN next_day_return IS NOT NULL THEN 1 ELSE 0 END) AS tracked, " +
            "  SUM(CASE WHEN next_day_return IS NOT NULL AND next_day_return > 0 THEN 1 ELSE 0 END) AS hitCount, " +
            "  CASE WHEN SUM(CASE WHEN next_day_return IS NOT NULL THEN 1 ELSE 0 END) > 0 " +
            "    THEN SUM(CASE WHEN next_day_return IS NOT NULL AND next_day_return > 0 THEN 1 ELSE 0 END) * 1.0 / " +
            "         SUM(CASE WHEN next_day_return IS NOT NULL THEN 1 ELSE 0 END) " +
            "    ELSE 0 END AS hitRate " +
            "FROM stock_recommendation " +
            "WHERE strategy_id = #{strategyId} AND recommend_date = #{date}")
    Map<String, Object> calcHitRate(@Param("strategyId") Long strategyId, @Param("date") LocalDate date);
}
