package com.quant.platform.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.quant.platform.stock.entity.StockDaily;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 股票每日行情Mapper
 */
@Mapper
public interface StockDailyMapper extends BaseMapper<StockDaily> {

    /**
     * 插入或更新股票每日行情
     */
    int insertOrUpdate(StockDaily stockDaily);

    /**
     * 获取指定日期的市场统计摘要（SQL聚合，不加载全量数据）
     * 返回: count, riseCount, fallCount, flatCount, avgPctChg, totalAmount
     */
    Map<String, Object> selectOverviewStats(@Param("tradeDate") LocalDate tradeDate);

    /**
     * 获取指定日期涨跌幅 Top N（SQL聚合排序，不加载全量数据）
     * 返回列表: code, name, change_percent, close_price, volume, amount, turnover_rate
     */
    List<Map<String, Object>> selectTopByPctChg(@Param("tradeDate") LocalDate tradeDate,
                                                 @Param("limit") int limit,
                                                 @Param("order") String order);
}
