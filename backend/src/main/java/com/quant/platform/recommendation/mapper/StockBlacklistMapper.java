package com.quant.platform.recommendation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.quant.platform.recommendation.domain.StockBlacklist;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 股票黑名单 Mapper
 */
public interface StockBlacklistMapper extends BaseMapper<StockBlacklist> {

    /**
     * 查询某策略当前生效的黑名单（未过期的）
     */
    @Select("SELECT * FROM stock_blacklist WHERE strategy_id = #{strategyId} " +
            "AND (blacklist_until IS NULL OR blacklist_until >= CURDATE()) " +
            "ORDER BY created_at DESC")
    List<StockBlacklist> findActiveByStrategyId(@Param("strategyId") Long strategyId);

    /**
     * 查询某策略当前生效的黑名单股票代码集合（用于过滤）
     */
    @Select("SELECT DISTINCT stock_code FROM stock_blacklist WHERE strategy_id = #{strategyId} " +
            "AND (blacklist_until IS NULL OR blacklist_until >= CURDATE())")
    Set<String> findActiveStockCodes(@Param("strategyId") Long strategyId);

    /**
     * 查询所有黑名单记录（含已过期的）
     */
    @Select("SELECT * FROM stock_blacklist WHERE strategy_id = #{strategyId} ORDER BY created_at DESC")
    List<StockBlacklist> findByStrategyId(@Param("strategyId") Long strategyId);

    /**
     * 根据策略+股票查询是否在黑名单中
     */
    @Select("SELECT * FROM stock_blacklist WHERE strategy_id = #{strategyId} AND stock_code = #{stockCode} " +
            "AND (blacklist_until IS NULL OR blacklist_until >= CURDATE()) LIMIT 1")
    StockBlacklist findActive(@Param("strategyId") Long strategyId, @Param("stockCode") String stockCode);

    /**
     * 删除黑名单记录（解封）
     */
    @Delete("DELETE FROM stock_blacklist WHERE id = #{id}")
    int deleteById(@Param("id") Long id);

    /**
     * 解封指定策略的所有黑名单记录
     */
    @Delete("DELETE FROM stock_blacklist WHERE strategy_id = #{strategyId}")
    int deleteAllByStrategyId(@Param("strategyId") Long strategyId);
}
