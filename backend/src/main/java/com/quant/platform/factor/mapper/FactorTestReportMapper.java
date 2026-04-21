package com.quant.platform.factor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.quant.platform.factor.domain.FactorTestReport;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 因子测试报告Mapper
 */
@Mapper
public interface FactorTestReportMapper extends BaseMapper<FactorTestReport> {

    /**
     * 根据因子代码查询测试报告
     */
    @Select("SELECT * FROM factor_test_report WHERE factor_code = #{factorCode} ORDER BY created_at DESC")
    List<FactorTestReport> findByFactorCode(@Param("factorCode") String factorCode);

    /**
     * 根据状态查询测试报告
     */
    @Select("SELECT * FROM factor_test_report WHERE status = #{status} ORDER BY created_at DESC")
    List<FactorTestReport> findByStatus(@Param("status") FactorTestReport.TestStatus status);
}
