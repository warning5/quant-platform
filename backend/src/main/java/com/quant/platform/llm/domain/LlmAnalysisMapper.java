package com.quant.platform.llm.domain;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface LlmAnalysisMapper extends BaseMapper<LlmAnalysis> {

    /**
     * 查询指定日期的分析结果，每只股票只取最新一条（避免重复触发导致重复展示）
     */
    @Select("SELECT a.* FROM llm_analysis a " +
            "INNER JOIN (SELECT stock_code, MAX(created_at) AS max_time FROM llm_analysis " +
            "WHERE analysis_date = #{date} GROUP BY stock_code) latest " +
            "ON a.stock_code = latest.stock_code AND a.created_at = latest.max_time " +
            "ORDER BY a.created_at DESC")
    List<LlmAnalysis> findByDate(LocalDate date);

    @Select("SELECT * FROM llm_analysis WHERE stock_code = #{stockCode} ORDER BY created_at DESC LIMIT 1")
    LlmAnalysis findLatestByStockCode(String stockCode);

    @Select("SELECT * FROM llm_analysis WHERE recommendation = #{rec} AND analysis_date = #{date}")
    List<LlmAnalysis> findByRecommendationAndDate(String recommendation, LocalDate date);

    /** 删除指定分析日期的所有记录（防止历史旧记录污染前端展示） */
    @Delete("DELETE FROM llm_analysis WHERE analysis_date = #{date}")
    int deleteByAnalysisDate(LocalDate date);

    /** 删除指定股票在指定日期的旧记录（单股重新分析时使用） */
    @Delete("DELETE FROM llm_analysis WHERE stock_code = #{stockCode} AND analysis_date = #{date}")
    int deleteByStockCodeAndDate(@Param("stockCode") String stockCode, @Param("date") LocalDate date);
}
