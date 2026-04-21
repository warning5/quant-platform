package com.quant.platform.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.quant.platform.stock.entity.StockInfo;
import org.apache.ibatis.annotations.Mapper;

/**
 * 股票基本信息Mapper
 */
@Mapper
public interface StockInfoMapper extends BaseMapper<StockInfo> {

    /**
     * 插入或更新股票基本信息
     */
    int insertOrUpdate(StockInfo stockInfo);
}
