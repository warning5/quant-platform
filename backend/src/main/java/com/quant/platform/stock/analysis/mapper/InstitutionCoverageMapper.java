package com.quant.platform.stock.analysis.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 机构覆盖度数据Mapper（分析包）
 * 整合: stock_research_report(研报) + stock_fund_holder(基金持仓) + stock_institution_research(机构调研)
 */
@Mapper
public interface InstitutionCoverageMapper {

    /**
     * 近1年研报统计
     * 返回: report_count, institution_count, latest_report_date
     */
    Map<String, Object> selectResearchReportStats(@Param("code") String code);

    /**
     * 近1年研报明细（最新N条）
     */
    List<Map<String, Object>> selectRecentResearchReports(
            @Param("code") String code, @Param("limit") int limit);

    /**
     * 最新季度基金持仓汇总
     * 返回: fund_count, total_float_ratio, total_market_value
     */
    Map<String, Object> selectFundHolderSummary(@Param("code") String code);

    /**
     * 近N天机构调研记录
     */
    List<Map<String, Object>> selectRecentInstitutionResearch(
            @Param("code") String code, @Param("days") int days);
}
