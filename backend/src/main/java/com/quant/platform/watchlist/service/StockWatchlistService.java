package com.quant.platform.watchlist.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quant.platform.common.exception.BusinessException;
import com.quant.platform.watchlist.domain.StockWatchlist;
import com.quant.platform.watchlist.mapper.StockWatchlistMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockWatchlistService {

    private final StockWatchlistMapper watchlistMapper;

    /** 获取所有活跃的自选股（按分组） */
    public List<StockWatchlist> getAllActive() {
        return watchlistMapper.findAllActive();
    }

    /** 按分组获取 */
    public List<StockWatchlist> getByGroup(String groupName) {
        return watchlistMapper.findActiveByGroupName(groupName);
    }

    /** 获取所有分组名 */
    public List<String> getGroupNames() {
        return watchlistMapper.findActiveGroupNames();
    }

    /** 获取按分组汇总的Map */
    public Map<String, List<StockWatchlist>> getGroupedWatchlist() {
        List<StockWatchlist> all = getAllActive();
        return all.stream().collect(Collectors.groupingBy(
                w -> w.getGroupName() != null ? w.getGroupName() : "default",
                Collectors.toList()));
    }

    /** 添加自选股 */
    @Transactional
    public StockWatchlist addStock(StockWatchlist watchlist) {
        // 检查是否已存在
        LambdaQueryWrapper<StockWatchlist> exists = new LambdaQueryWrapper<StockWatchlist>()
                .eq(StockWatchlist::getStockCode, watchlist.getStockCode())
                .eq(StockWatchlist::getArchived, 0);
        if (watchlist.getGroupName() != null) {
            exists.eq(StockWatchlist::getGroupName, watchlist.getGroupName());
        }
        Long count = watchlistMapper.selectCount(exists);
        if (count > 0) {
            throw new BusinessException("股票 " + watchlist.getStockCode() + " 已在自选池中");
        }

        // 设置默认值
        if (watchlist.getGroupName() == null || watchlist.getGroupName().isBlank()) {
            watchlist.setGroupName("default");
        }
        if (watchlist.getSource() == null) {
            watchlist.setSource("MANUAL");
        }
        if (watchlist.getArchived() == null) {
            watchlist.setArchived(0);
        }
        if (watchlist.getSortOrder() == null) {
            // 默认排在最后
            LambdaQueryWrapper<StockWatchlist> maxOrder = new LambdaQueryWrapper<StockWatchlist>()
                    .eq(StockWatchlist::getGroupName, watchlist.getGroupName())
                    .eq(StockWatchlist::getArchived, 0)
                    .orderByDesc(StockWatchlist::getSortOrder)
                    .last("LIMIT 1");
            StockWatchlist last = watchlistMapper.selectOne(maxOrder);
            watchlist.setSortOrder(last != null && last.getSortOrder() != null ? last.getSortOrder() + 1 : 1);
        }
        // 默认观察期5个交易日
        if (watchlist.getWatchEndDate() == null) {
            watchlist.setWatchEndDate(LocalDate.now().plusDays(7));
        }

        watchlistMapper.insert(watchlist);
        log.info("自选股已添加: code={}, group={}", watchlist.getStockCode(), watchlist.getGroupName());
        return watchlist;
    }

    /** 从推荐结果批量添加 */
    @Transactional
    public int addFromRecommendation(List<String> stockCodes, List<String> stockNames,
                                      Long batchId, String groupName) {
        int added = 0;
        for (int i = 0; i < stockCodes.size(); i++) {
            StockWatchlist w = StockWatchlist.builder()
                    .stockCode(stockCodes.get(i))
                    .stockName(i < stockNames.size() ? stockNames.get(i) : "")
                    .groupName(groupName != null ? groupName : "推荐候选")
                    .source("RECOMMENDATION")
                    .recommendationBatchId(batchId)
                    .watchEndDate(LocalDate.now().plusDays(7))
                    .archived(0)
                    .sortOrder(i + 1)
                    .build();
            try {
                addStock(w);
                added++;
            } catch (BusinessException e) {
                log.debug("跳过已存在的自选股: {}", stockCodes.get(i));
            }
        }
        return added;
    }

    /** 更新自选股信息（目标价、止损价等） */
    @Transactional
    public StockWatchlist updateWatchlist(Long id, StockWatchlist update) {
        StockWatchlist existing = watchlistMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException("自选股记录不存在: " + id);
        }
        if (update.getTargetBuyPrice() != null) existing.setTargetBuyPrice(update.getTargetBuyPrice());
        if (update.getStopLossPrice() != null) existing.setStopLossPrice(update.getStopLossPrice());
        if (update.getTargetSellPrice() != null) existing.setTargetSellPrice(update.getTargetSellPrice());
        if (update.getWatchEndDate() != null) existing.setWatchEndDate(update.getWatchEndDate());
        if (update.getNotes() != null) existing.setNotes(update.getNotes());
        if (update.getGroupName() != null) existing.setGroupName(update.getGroupName());
        if (update.getSortOrder() != null) existing.setSortOrder(update.getSortOrder());
        watchlistMapper.updateById(existing);
        return existing;
    }

    /** 移除自选股（归档而非删除） */
    @Transactional
    public void removeStock(Long id) {
        StockWatchlist w = watchlistMapper.selectById(id);
        if (w == null) return;
        w.setArchived(1);
        watchlistMapper.updateById(w);
        log.info("自选股已归档: code={}", w.getStockCode());
    }

    /** 真正删除 */
    @Transactional
    public void deleteStock(Long id) {
        watchlistMapper.deleteById(id);
    }

    /** 清空分组的所有自选股 */
    @Transactional
    public int clearGroup(String groupName) {
        LambdaQueryWrapper<StockWatchlist> wrapper = new LambdaQueryWrapper<StockWatchlist>()
                .eq(StockWatchlist::getGroupName, groupName)
                .eq(StockWatchlist::getArchived, 0);
        List<StockWatchlist> list = watchlistMapper.selectList(wrapper);
        for (StockWatchlist w : list) {
            w.setArchived(1);
            watchlistMapper.updateById(w);
        }
        return list.size();
    }

    /** 检查观测到期的股票 */
    public List<StockWatchlist> getExpiredWatchlist() {
        LambdaQueryWrapper<StockWatchlist> wrapper = new LambdaQueryWrapper<StockWatchlist>()
                .eq(StockWatchlist::getArchived, 0)
                .lt(StockWatchlist::getWatchEndDate, LocalDate.now());
        return watchlistMapper.selectList(wrapper);
    }

    /** 按股票代码查询是否在自选池 */
    public boolean isInWatchlist(String stockCode) {
        LambdaQueryWrapper<StockWatchlist> wrapper = new LambdaQueryWrapper<StockWatchlist>()
                .eq(StockWatchlist::getStockCode, stockCode)
                .eq(StockWatchlist::getArchived, 0);
        return watchlistMapper.selectCount(wrapper) > 0;
    }
}
