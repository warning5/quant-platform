package com.quant.platform.factor.ic.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.quant.platform.factor.ic.domain.FactorIcRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface FactorIcRecordMapper extends BaseMapper<FactorIcRecord> {

    /** 覆盖写入（存在则更新，不存在则插入），支持重复计算。唯一键：(factor_code, trade_date, forward_days) */
    @Insert("INSERT INTO factor_ic_record (factor_code, trade_date, forward_days, ic_value, ic20d_avg, ic60d_avg, ir20d, ir60d, stock_count) "
            + "VALUES (#{factorCode}, #{tradeDate}, #{forwardDays}, #{icValue}, #{ic20dAvg}, #{ic60dAvg}, #{ir20d}, #{ir60d}, #{stockCount}) "
            + "ON DUPLICATE KEY UPDATE ic_value = VALUES(ic_value), ic20d_avg = VALUES(ic20d_avg), "
            + "ic60d_avg = VALUES(ic60d_avg), ir20d = VALUES(ir20d), ir60d = VALUES(ir60d), "
            + "stock_count = VALUES(stock_count)")
    int upsert(FactorIcRecord record);

    /** 获取因子最近N天的IC序列（按指定前瞻天数过滤） */
    @Select("SELECT ic_value FROM factor_ic_record WHERE factor_code = #{factorCode} AND forward_days = #{forwardDays} AND trade_date <= #{date} ORDER BY trade_date DESC LIMIT #{limit}")
    List<Double> findRecentIcValues(@Param("factorCode") String factorCode, @Param("date") LocalDate date, @Param("limit") int limit, @Param("forwardDays") int forwardDays);

    /** 获取因子最新IC记录（按指定前瞻天数过滤） */
    @Select("SELECT * FROM factor_ic_record WHERE factor_code = #{factorCode} AND forward_days = #{forwardDays} ORDER BY trade_date DESC LIMIT 1")
    FactorIcRecord findLatest(@Param("factorCode") String factorCode, @Param("forwardDays") int forwardDays);

    /** 获取所有因子的最新IR（默认5天前瞻） */
    @Select("SELECT fr1.* FROM factor_ic_record fr1 " +
            "INNER JOIN (SELECT factor_code, MAX(trade_date) as max_date FROM factor_ic_record WHERE forward_days = #{forwardDays} GROUP BY factor_code) fr2 " +
            "ON fr1.factor_code = fr2.factor_code AND fr1.trade_date = fr2.max_date AND fr1.forward_days = #{forwardDays}")
    List<FactorIcRecord> findAllLatest(@Param("forwardDays") int forwardDays);
}
