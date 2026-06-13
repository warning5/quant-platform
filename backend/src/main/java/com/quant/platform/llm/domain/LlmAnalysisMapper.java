package com.quant.platform.llm.domain;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface LlmAnalysisMapper extends BaseMapper<LlmAnalysis> {

    @Select("SELECT * FROM llm_analysis WHERE analysis_date = #{date} ORDER BY created_at DESC")
    List<LlmAnalysis> findByDate(LocalDate date);

    @Select("SELECT * FROM llm_analysis WHERE stock_code = #{stockCode} ORDER BY created_at DESC LIMIT 1")
    LlmAnalysis findLatestByStockCode(String stockCode);

    @Select("SELECT * FROM llm_analysis WHERE recommendation = #{rec} AND analysis_date = #{date}")
    List<LlmAnalysis> findByRecommendationAndDate(String recommendation, LocalDate date);
}
