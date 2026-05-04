package com.quant.platform.stock.analysis.mapper;

import com.quant.platform.stock.analysis.domain.FundamentalSignal;
import com.quant.platform.stock.analysis.domain.ResearchSignal;
import com.quant.platform.stock.analysis.domain.SentimentSignal;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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

    /**
     * 查询研报数据（最近90天）
     * 返回：latest_rating, report_count, recent_reports(list)
     */
    ResearchSignal selectResearchSignal(@Param("code") String code);
    
    /**
     * 查询研报明细列表（最近5条，供前端展示）
     */
    List<Map<String, Object>> selectRecentResearchReports(@Param("code") String code);

    /**
     * 查询研报明细列表（含EPS预测，指定条数）
     */
    List<Map<String, Object>> selectRecentReportsWithEps(@Param("code") String code, @Param("limit") int limit);

    /**
     * 查询评级趋势（近6个月，按月+评级分组）
     * 返回：month(raty-YY-MM), rating, cnt
     */
    List<Map<String, Object>> selectRatingTrend(@Param("code") String code);

    /**
     * 查询研报数量月度趋势（近6个月）
     * 返回：month(YYYY-MM), cnt
     */
    List<Map<String, Object>> selectReportCountByMonth(@Param("code") String code);

    /**
     * 查询覆盖机构列表（去重）
     * 返回：institution, first_date(首次覆盖日期), report_count
     */
    List<Map<String, Object>> selectCoverageInstitutions(@Param("code") String code);

    /**
     * 查询首次覆盖日期
     */
    String selectFirstCoverageDate(@Param("code") String code);

    /**
     * 股票联想搜索（按代码或名称模糊匹配）
     * 返回：code, name, market
     */
    List<Map<String, Object>> searchStocks(@Param("keyword") String keyword);
}
