package com.quant.platform.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.quant.platform.stock.entity.StockDividend;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/**
 * 分红除权数据 Mapper
 */
@Mapper
public interface StockDividendMapper extends BaseMapper<StockDividend> {

    /**
     * 查询指定股票在日期范围内的所有分红记录
     */
    @Select("SELECT * FROM stock_dividend WHERE code = #{code} " +
            "AND ex_dividend_date BETWEEN #{startDate} AND #{endDate} " +
            "ORDER BY ex_dividend_date")
    List<StockDividend> findByCodeAndDateRange(@Param("code") String code,
                                                 @Param("startDate") LocalDate startDate,
                                                 @Param("endDate") LocalDate endDate);

    /**
     * 查询指定日期所有发生除权除息的记录
     */
    @Select("SELECT * FROM stock_dividend WHERE ex_dividend_date = #{date}")
    List<StockDividend> findByExDate(@Param("date") LocalDate date);

    /**
     * 查询指定股票的所有分红记录
     */
    @Select("SELECT * FROM stock_dividend WHERE code = #{code} ORDER BY ex_dividend_date")
    List<StockDividend> findByCode(@Param("code") String code);
}
