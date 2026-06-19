package com.quant.platform.calendar.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.quant.platform.calendar.domain.TradeCalendar;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 交易日历 Mapper
 */
@Mapper
public interface TradeCalendarMapper extends BaseMapper<TradeCalendar> {

    /**
     * 查询指定日期的日历记录
     */
    TradeCalendar selectByDate(@Param("tradeDate") LocalDate tradeDate);

    /**
     * 查询指定年份的所有例外日记录
     */
    List<TradeCalendar> selectByYear(@Param("year") int year);

    /**
     * 查询最近的交易日
     */
    LocalDate selectLatestTradingDay(@Param("date") LocalDate date);

    /**
     * 查询指定范围内的交易日
     */
    List<LocalDate> selectTradingDaysBetween(@Param("startDate") LocalDate startDate,
                                               @Param("endDate") LocalDate endDate);

    /**
     * 插入或更新（ON DUPLICATE KEY UPDATE）
     */
    int upsertByDate(TradeCalendar cal);

    /**
     * 删除指定日期的记录
     */
    int deleteByDate(@Param("tradeDate") LocalDate tradeDate);
}
