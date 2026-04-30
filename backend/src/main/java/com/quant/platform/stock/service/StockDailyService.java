package com.quant.platform.stock.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quant.platform.stock.entity.StockDaily;
import com.quant.platform.stock.mapper.StockDailyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 股票日线数据服务
 * 写入时双写到 MySQL 和 ClickHouse
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockDailyService {

    private final StockDailyMapper stockDailyMapper;
    private final ClickHouseStockService clickHouseStockService;

    /**
     * 查询单只股票的历史日线数据
     */
    public List<StockDaily> getStockDailyList(String code, LocalDate startDate, LocalDate endDate) {
        return clickHouseStockService.getStockDaily(code, startDate, endDate);
    }

    /**
     * 批量查询多只股票的历史数据
     */
    public List<StockDaily> getStockDailyListBatch(List<String> codes, LocalDate startDate, LocalDate endDate) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        return clickHouseStockService.getStockDailyBatch(codes, startDate, endDate);
    }

    /**
     * 查询指定日期的所有股票数据
     */
    public List<StockDaily> getStockDailyByDate(LocalDate tradeDate) {
        return clickHouseStockService.getStockDailyBatch(List.of(), tradeDate, tradeDate);
    }

    /**
     * 查询指定日期范围的所有股票数据
     */
    public List<StockDaily> getStockDailyByDateRange(LocalDate startDate, LocalDate endDate) {
        LambdaQueryWrapper<StockDaily> wrapper = new LambdaQueryWrapper<>();
        wrapper.ge(StockDaily::getTradeDate, startDate)
                .le(StockDaily::getTradeDate, endDate)
                .orderByAsc(StockDaily::getTradeDate);
        return stockDailyMapper.selectList(wrapper);
    }

    /**
     * 保存单条日线数据（双写 MySQL + ClickHouse）
     */
    @Transactional
    public void saveStockDaily(StockDaily daily) {
        stockDailyMapper.insert(daily);
        clickHouseStockService.writeStockDaily(daily);
    }

    /**
     * 批量保存日线数据（双写 MySQL + ClickHouse）
     */
    @Transactional
    public void saveStockDailyBatch(List<StockDaily> dailies) {
        if (dailies == null || dailies.isEmpty()) {
            return;
        }
        for (StockDaily daily : dailies) {
            stockDailyMapper.insert(daily);
        }
        clickHouseStockService.writeStockDailyBatch(dailies);
    }

    /**
     * 更新单条日线数据（双写）
     */
    @Transactional
    public void updateStockDaily(StockDaily daily) {
        stockDailyMapper.updateById(daily);
        clickHouseStockService.writeStockDaily(daily);
    }

    /**
     * 批量更新日线数据（双写）
     */
    @Transactional
    public void updateStockDailyBatch(List<StockDaily> dailies) {
        if (dailies == null || dailies.isEmpty()) {
            return;
        }
        for (StockDaily daily : dailies) {
            stockDailyMapper.updateById(daily);
        }
        clickHouseStockService.writeStockDailyBatch(dailies);
    }
}
