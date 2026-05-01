package com.quant.platform.factor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.quant.platform.factor.domain.FactorValue;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 因子值 Mapper
 * 注意：查询全部走 ClickHouse（FactorService / ClickHouseFactorValueService）
 * 此处仅保留写入方法
 */
@Mapper
public interface FactorValueMapper extends BaseMapper<FactorValue> {

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
}
