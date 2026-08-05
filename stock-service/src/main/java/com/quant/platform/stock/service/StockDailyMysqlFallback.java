package com.quant.platform.stock.service;

import static com.quant.platform.stock.service.StockDailySqlSupport.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quant.platform.stock.entity.StockDaily;
import com.quant.platform.stock.mapper.StockDailyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * MySQL 日线兜底层（从 ClickHouseStockService 逐字搬出，no-behavior-change）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockDailyMysqlFallback {

    private final StockDailyMapper stockDailyMapper;

    public List<StockDaily> getFromMySQL(String code, LocalDate startDate, LocalDate endDate) {
        LambdaQueryWrapper<StockDaily> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StockDaily::getCode, code)
                .ge(StockDaily::getTradeDate, startDate)
                .le(StockDaily::getTradeDate, endDate)
                .orderByAsc(StockDaily::getTradeDate);
        return stockDailyMapper.selectList(wrapper);
    }

    public List<StockDaily> getBatchFromMySQL(List<String> codes, LocalDate startDate, LocalDate endDate) {
        LambdaQueryWrapper<StockDaily> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(StockDaily::getCode, codes)
                .ge(StockDaily::getTradeDate, startDate)
                .le(StockDaily::getTradeDate, endDate)
                .orderByAsc(StockDaily::getCode, StockDaily::getTradeDate);
        return stockDailyMapper.selectList(wrapper);
    }

    public List<StockDaily> getDailyByDateFromMySQL(LocalDate date, Collection<String> excludeNames) {
        LambdaQueryWrapper<StockDaily> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StockDaily::getTradeDate, date);
        if (excludeNames != null && !excludeNames.isEmpty()) {
            wrapper.notIn(StockDaily::getName, excludeNames);
        }
        wrapper.orderByAsc(StockDaily::getCode);
        return stockDailyMapper.selectList(wrapper);
    }

    public Map<String, Object> getCrossSectionPagedFromMySQL(LocalDate date, int page, int size,
                                                              String keyword, String sortField, String sortOrder) {
        String sortClause = buildOrderByClause(sortField, sortOrder);

        // 总数查询
        LambdaQueryWrapper<StockDaily> countWrapper = new LambdaQueryWrapper<>();
        countWrapper.eq(StockDaily::getTradeDate, date);
        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim();
            countWrapper.and(w -> w.like(StockDaily::getCode, kw)
                    .or().like(StockDaily::getName, kw));
        }
        Long total = stockDailyMapper.selectCount(countWrapper);

        // 分页查询（含排序）
        LambdaQueryWrapper<StockDaily> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StockDaily::getTradeDate, date);
        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(StockDaily::getCode, kw)
                    .or().like(StockDaily::getName, kw));
        }
        int offset = (page - 1) * size;
        wrapper.last(sortClause + String.format(" LIMIT %d OFFSET %d", size, offset));

        List<StockDaily> records = stockDailyMapper.selectList(wrapper);

        Map<String, Object> result = new HashMap<>();
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        result.put("totalPages", (total + size - 1) / size);
        result.put("data", records);
        return result;
    }

    public Map<String, Object> getOverviewStatsFromMySQL(LocalDate tradeDate) {
        return stockDailyMapper.selectOverviewStats(tradeDate);
    }

    public List<Map<String, Object>> getTopByPctChgFromMySQL(LocalDate tradeDate, int limit, String order) {
        return stockDailyMapper.selectTopByPctChg(tradeDate, limit, order);
    }

    public LocalDate getLatestTradingDateFromMySQL(LocalDate start, LocalDate end) {
        LambdaQueryWrapper<StockDaily> wrapper = new LambdaQueryWrapper<>();
        wrapper.between(StockDaily::getTradeDate, start, end)
                .select(StockDaily::getTradeDate)
                .orderByDesc(StockDaily::getTradeDate)
                .last("LIMIT 1");
        List<StockDaily> result = stockDailyMapper.selectList(wrapper);
        return result.isEmpty() ? null : result.get(0).getTradeDate();
    }

    public List<LocalDate> getTradingDatesFromMySQL(LocalDate start, LocalDate end) {
        LambdaQueryWrapper<StockDaily> wrapper = new LambdaQueryWrapper<>();
        wrapper.between(StockDaily::getTradeDate, start, end)
                .select(StockDaily::getTradeDate)
                .groupBy(StockDaily::getTradeDate)
                .orderByAsc(StockDaily::getTradeDate);
        return stockDailyMapper.selectList(wrapper).stream()
                .map(StockDaily::getTradeDate)
                .toList();
    }

    public List<String> getRecentTradingDatesFromMySQL(int limit) {
        LambdaQueryWrapper<StockDaily> wrapper = new LambdaQueryWrapper<>();
        wrapper.groupBy(StockDaily::getTradeDate)
                .orderByDesc(StockDaily::getTradeDate)
                .last("LIMIT " + Math.min(limit, 10000))
                .select(StockDaily::getTradeDate);
        return stockDailyMapper.selectList(wrapper).stream()
                .map(d -> d.getTradeDate().toString())
                .toList();
    }

    public LocalDate getExtremeDateFromMySQL(boolean isMax) {
        // 排除指数代码（sh.000xxx / sz.399xxx），只统计股票
        LambdaQueryWrapper<StockDaily> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(StockDaily::getTradeDate);
        wrapper.apply("code NOT LIKE 'sh.000%' AND code NOT LIKE 'sz.399%'");
        if (isMax) wrapper.orderByDesc(StockDaily::getTradeDate);
        else wrapper.orderByAsc(StockDaily::getTradeDate);
        wrapper.last("LIMIT 1");
        List<StockDaily> result = stockDailyMapper.selectList(wrapper);
        return result.isEmpty() ? null : result.get(0).getTradeDate();
    }

    public Set<String> getExistingCodesFromMySQL(LocalDate date, Collection<String> codes) {
        if (codes.isEmpty()) return Set.of();
        LambdaQueryWrapper<StockDaily> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StockDaily::getTradeDate, date)
                .in(StockDaily::getCode, codes)
                .select(StockDaily::getCode);
        return stockDailyMapper.selectList(wrapper).stream()
                .map(StockDaily::getCode)
                .collect(java.util.stream.Collectors.toSet());
    }

    public List<Map<String, Object>> queryForListFromMySQL(String sql, Object... params) {
        // MySQL 回退通过 JdbcTemplate 不可用（这里 Service 不注入 JdbcTemplate），
        // 通过 stockDailyMapper 无法执行任意 SQL，返回空列表
        log.warn("[MySQL回退] 无法执行通用 SQL，请使用 JdbcTemplate 直接查询");
        return List.of();
    }

    public Object queryForObjectFromMySQL(String sql, Object... params) {
        log.warn("[MySQL回退] 无法执行通用 SQL，请使用 JdbcTemplate 直接查询");
        return null;
    }
}
