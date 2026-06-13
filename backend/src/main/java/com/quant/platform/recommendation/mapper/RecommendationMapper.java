package com.quant.platform.recommendation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.quant.platform.recommendation.domain.StockRecommendation;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 推荐记录 Mapper
 */
@Mapper
public interface RecommendationMapper extends BaseMapper<StockRecommendation> {

    /**
     * 查询指定策略+日期的所有推荐
     */
    @Select("SELECT * FROM stock_recommendation WHERE strategy_id = #{strategyId} AND recommend_date = #{recommendDate} ORDER BY rank_num")
    List<StockRecommendation> findByStrategyAndDate(@Param("strategyId") Long strategyId, @Param("recommendDate") LocalDate recommendDate);

    /**
     * 查询最新的一条推荐记录（用于获取 latest strategyId + date）
     */
    @Select("SELECT * FROM stock_recommendation ORDER BY recommend_date DESC, id DESC LIMIT 1")
    StockRecommendation findLatest();

    /**
     * 查询最近的策略+日期组合（按时间倒序，用于历史列表）
     * 返回去重后的 (strategy_id, recommend_date) 组合
     */
    @Select("SELECT DISTINCT strategy_id, recommend_date FROM stock_recommendation WHERE strategy_id IS NOT NULL ORDER BY recommend_date DESC, strategy_id DESC LIMIT #{limit}")
    List<Map<String, Object>> findRecentStrategyDates(@Param("limit") int limit);

    /**
     * 统计指定策略+日期的推荐数量
     */
    @Select("SELECT COUNT(*) FROM stock_recommendation WHERE strategy_id = #{strategyId} AND recommend_date = #{recommendDate}")
    int countByStrategyAndDate(@Param("strategyId") Long strategyId, @Param("recommendDate") LocalDate recommendDate);

    /**
     * 删除指定策略+日期的所有推荐（生成前清理旧数据，避免重复）
     */
    @Delete("DELETE FROM stock_recommendation WHERE strategy_id = #{strategyId} AND recommend_date = #{recommendDate}")
    int deleteByStrategyAndDate(@Param("strategyId") Long strategyId, @Param("recommendDate") LocalDate recommendDate);

    /**
     * 按策略ID查询所有推荐（用于复盘按策略筛选，支持分页/限制数量）
     */
    @Select("SELECT * FROM stock_recommendation WHERE strategy_id = #{strategyId} ORDER BY recommend_date DESC, rank_num LIMIT #{limit}")
    List<StockRecommendation> findByStrategyId(@Param("strategyId") Long strategyId, @Param("limit") int limit);

    /**
     * 获取所有有推荐记录的策略ID列表（去重，用于前端策略筛选下拉）
     */
    @Select("SELECT DISTINCT strategy_id FROM stock_recommendation ORDER BY strategy_id")
    List<Long> findDistinctStrategyIds();

    /**
     * 获取指定策略的推荐日期列表（去重倒序，用于前端日期筛选下拉）
     */
    @Select("SELECT DISTINCT recommend_date FROM stock_recommendation WHERE strategy_id = #{strategyId} ORDER BY recommend_date DESC LIMIT #{limit}")
    List<LocalDate> findDatesByStrategyId(@Param("strategyId") Long strategyId, @Param("limit") int limit);
}
