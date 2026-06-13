package com.quant.platform.position;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface StockPositionMapper extends BaseMapper<StockPosition> {

    @Select("SELECT * FROM stock_position WHERE status = 'OPEN' ORDER BY buy_date DESC")
    List<StockPosition> findAllOpen();

    @Select("SELECT * FROM stock_position WHERE status = 'CLOSED' ORDER BY sell_date DESC")
    List<StockPosition> findAllClosed();
}
