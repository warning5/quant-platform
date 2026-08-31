package com.quant.platform.stock.analysis.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 内外盘比数据Mapper（分析包）
 * 数据来源: akshare.stock_bid_ask_em（东方财富实时行情快照）
 * 存储表: stock_bid_ask
 */
@Mapper
public interface BidAskMapper {

    /**
     * 获取指定股票的当日/最新内外盘比数据
     */
    Map<String, Object> selectLatestBidAsk(@Param("code") String code);

    /**
     * 获取指定股票近N日内外盘比历史
     * ⚠️ 按记录数取（LIMIT days），缺失交易日会被静默跳过，导致前端趋势图断档不可见。
     * 仅作为交易日历不可用时的兜底，正常路径请用 {@link #selectBidAskByDates}。
     */
    List<Map<String, Object>> selectBidAskHistory(@Param("code") String code, @Param("days") int days);

    /**
     * 按交易日列表精确取内外盘数据（只返回有数据的行）
     *
     * @param dates 交易日列表（java.time.LocalDate）
     */
    List<Map<String, Object>> selectBidAskByDates(@Param("code") String code, @Param("dates") List<LocalDate> dates);

    /**
     * 获取指定日期的内外盘数据
     */
    Map<String, Object> selectBidAskByDate(@Param("code") String code, @Param("tradeDate") LocalDate tradeDate);
}
