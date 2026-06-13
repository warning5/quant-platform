package com.quant.platform.watchlist.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.quant.platform.watchlist.domain.StockWatchlist;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface StockWatchlistMapper extends BaseMapper<StockWatchlist> {

    @Select("SELECT DISTINCT group_name FROM stock_watchlist WHERE archived = 0 ORDER BY group_name")
    List<String> findActiveGroupNames();

    @Select("SELECT * FROM stock_watchlist WHERE archived = 0 AND group_name = #{groupName} ORDER BY sort_order, created_at DESC")
    List<StockWatchlist> findActiveByGroupName(String groupName);

    @Select("SELECT * FROM stock_watchlist WHERE archived = 0 ORDER BY group_name, sort_order, created_at DESC")
    List<StockWatchlist> findAllActive();
}
