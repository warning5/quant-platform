package com.quant.platform.factor.ic.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.quant.platform.factor.ic.domain.FactorIcRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface FactorIcRecordMapper extends BaseMapper<FactorIcRecord> {

    /** 获取因子最近N天的IC序列 */
    @Select("SELECT ic_value FROM factor_ic_record WHERE factor_code = #{factorCode} AND trade_date <= #{date} ORDER BY trade_date DESC LIMIT #{limit}")
    List<Double> findRecentIcValues(@Param("factorCode") String factorCode, @Param("date") LocalDate date, @Param("limit") int limit);

    /** 获取因子最新IC记录 */
    @Select("SELECT * FROM factor_ic_record WHERE factor_code = #{factorCode} ORDER BY trade_date DESC LIMIT 1")
    FactorIcRecord findLatest(@Param("factorCode") String factorCode);

    /** 获取所有因子的最新IR */
    @Select("SELECT fr1.* FROM factor_ic_record fr1 " +
            "INNER JOIN (SELECT factor_code, MAX(trade_date) as max_date FROM factor_ic_record GROUP BY factor_code) fr2 " +
            "ON fr1.factor_code = fr2.factor_code AND fr1.trade_date = fr2.max_date")
    List<FactorIcRecord> findAllLatest();
}
