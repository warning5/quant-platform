package com.quant.platform.recommendation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.quant.platform.recommendation.domain.StockRecommendation;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 推荐记录 Mapper
 */
@Mapper
public interface RecommendationMapper extends BaseMapper<StockRecommendation> {

    /**
     * 查询指定批次的所有推荐
     */
    @Select("SELECT * FROM stock_recommendation WHERE batch_id = #{batchId} ORDER BY rank_num")
    List<StockRecommendation> findByBatchId(@Param("batchId") String batchId);

    /**
     * 查询最新的批次ID
     */
    @Select("SELECT DISTINCT batch_id FROM stock_recommendation ORDER BY batch_id DESC LIMIT 1")
    String findLatestBatchId();

    /**
     * 查询所有批次ID（按时间倒序）
     */
    @Select("SELECT DISTINCT batch_id FROM stock_recommendation ORDER BY batch_id DESC LIMIT #{limit}")
    List<String> findRecentBatchIds(@Param("limit") int limit);

    /**
     * 统计指定批次的推荐数量
     */
    @Select("SELECT COUNT(*) FROM stock_recommendation WHERE batch_id = #{batchId}")
    int countByBatchId(@Param("batchId") String batchId);

    /**
     * 删除指定批次的所有推荐（生成前清理旧数据，避免唯一键冲突）
     */
    @Delete("DELETE FROM stock_recommendation WHERE batch_id = #{batchId}")
    int deleteByBatchId(@Param("batchId") String batchId);
}
