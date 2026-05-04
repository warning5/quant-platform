package com.quant.platform.stock.analysis.mapper;

import com.quant.platform.stock.analysis.domain.FundamentalSignal;
import com.quant.platform.stock.analysis.domain.SentimentSignal;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 个股分析 MySQL 查询 Mapper
 * 资金面/事件面/基本面 数据从 MySQL 读取
 */
@Mapper
public interface StockAnalysisMapper {
    
    /**
     * 查询机构调研统计（最近N天）
     * 返回：survey_count(调研次数), avg_score(平均评分)
     */
    Map<String, Object> selectSurveyStats(@Param("code") String code, 
                                          @Param("days") int days);
    
    /**
     * 查询涨跌停池统计（最近N天）
     * 返回：limit_up_count, broken_count, avg_strength
     */
    Map<String, Object> selectZtPoolStats(@Param("code") String code,
                                           @Param("days") int days);
    
    /**
     * 查询基本面数据（从 stock_financial_indicator 等表）
     * 返回：pe_ttm, pb, roe, revenue_yoy, net_profit_yoy, gross_margin, debt_ratio
     */
    FundamentalSignal selectFundamentalSignal(@Param("code") String code);
    
    /**
     * 查询事件面数据
     * 返回：limit_up_days, limit_up_strength, broken_limit_up_count, is_strong_stock
     */
    SentimentSignal selectSentimentSignal(@Param("code") String code);
    
    /**
     * 查询股票基本信息
     */
    Map<String, Object> selectStockInfo(@Param("code") String code);
}
