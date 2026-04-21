package com.quant.platform.factor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.quant.platform.factor.domain.FactorValue;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Options;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 因子值Mapper
 */
@Mapper
public interface FactorValueMapper extends BaseMapper<FactorValue> {

    /**
     * 根据因子代码和日期查询因子值
     */
    @Select("SELECT * FROM factor_value WHERE factor_code = #{factorCode} AND calc_date = #{calcDate} ORDER BY symbol")
    List<FactorValue> findByFactorCodeAndDate(
            @Param("factorCode") String factorCode,
            @Param("calcDate") LocalDate calcDate);

    /**
     * 根据因子代码和日期范围查询因子值
     */
    @Select("SELECT * FROM factor_value WHERE factor_code = #{factorCode} AND calc_date BETWEEN #{startDate} AND #{endDate} ORDER BY calc_date, symbol")
    List<FactorValue> findByFactorCodeAndDateRange(
            @Param("factorCode") String factorCode,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /**
     * 根据日期查询所有因子值
     */
    @Select("SELECT * FROM factor_value WHERE calc_date = #{calcDate} ORDER BY factor_code, symbol")
    List<FactorValue> findByDate(@Param("calcDate") LocalDate calcDate);

    /**
     * 根据因子代码和股票查询因子值历史
     */
    @Select("SELECT * FROM factor_value WHERE factor_code = #{factorCode} AND symbol = #{symbol} ORDER BY calc_date DESC")
    List<FactorValue> findByFactorCodeAndSymbol(
            @Param("factorCode") String factorCode,
            @Param("symbol") String symbol);

    /**
     * 批量查询多个因子的最新值
     */
    @Select("SELECT * FROM factor_value WHERE factor_code IN (${factorCodes}) AND calc_date = #{calcDate} ORDER BY factor_code, symbol")
    List<FactorValue> findByFactorCodesAndDate(
            @Param("factorCodes") String factorCodes,
            @Param("calcDate") LocalDate calcDate);

    /**
     * INSERT ... ON DUPLICATE KEY UPDATE（配合唯一索引 uk_factor_symbol_date）
     */
    @Insert("INSERT INTO factor_value (factor_code, symbol, calc_date, factor_val, z_score, rank_value, created_at) " +
            "VALUES (#{fv.factorCode}, #{fv.symbol}, #{fv.calcDate}, #{fv.factorVal}, #{fv.zScore}, #{fv.rankValue}, #{fv.createdAt}) " +
            "ON DUPLICATE KEY UPDATE factor_val = VALUES(factor_val), z_score = VALUES(z_score), rank_value = VALUES(rank_value)")
    int upsert(@Param("fv") FactorValue fv);

    /**
     * 批量 INSERT ... ON DUPLICATE KEY UPDATE
     * 一次性插入多行，性能远优于逐行 upsert
     */
    @Insert("<script>" +
            "INSERT INTO factor_value (factor_code, symbol, calc_date, factor_val, z_score, rank_value, created_at) VALUES " +
            "<foreach collection='list' item='fv' separator=','>" +
            "(#{fv.factorCode}, #{fv.symbol}, #{fv.calcDate}, #{fv.factorVal}, #{fv.zScore}, #{fv.rankValue}, #{fv.createdAt})" +
            "</foreach>" +
            " ON DUPLICATE KEY UPDATE factor_val=VALUES(factor_val), z_score=VALUES(z_score), rank_value=VALUES(rank_value)" +
            "</script>")
    int batchUpsert(@Param("list") List<FactorValue> list);

    /**
     * 按因子代码聚合统计：记录数、最早日期、最新日期、交易日数、股票数
     */
    @Select("SELECT factor_code, COUNT(*) AS cnt, " +
            "MIN(calc_date) AS min_date, MAX(calc_date) AS max_date, " +
            "COUNT(DISTINCT calc_date) AS days, COUNT(DISTINCT symbol) AS stocks " +
            "FROM factor_value GROUP BY factor_code")
    List<Map<String, Object>> selectFactorStats();

    /**
     * 全表总记录数（不走 MyBatis-Plus 避免全量查询）
     */
    @Select("SELECT COUNT(*) FROM factor_value")
    long selectTotalCount();
}
