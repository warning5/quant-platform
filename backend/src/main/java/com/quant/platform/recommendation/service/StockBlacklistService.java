package com.quant.platform.recommendation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quant.platform.recommendation.domain.StockBlacklist;
import com.quant.platform.recommendation.domain.StockRecommendation;
import com.quant.platform.recommendation.mapper.RecommendationMapper;
import com.quant.platform.recommendation.mapper.StockBlacklistMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 股票黑名单服务
 * 黑名单规则：
 * 1. 连续3次推荐，追踪收益率均为负 → 自动拉黑30天
 * 2. 近5次推荐，命中率 < 20%（即最多1次上涨） → 自动拉黑14天
 * 3. 单次推荐，次日跌幅 > -8%（踩雷） → 自动拉黑60天
 * 4. 手动屏蔽 → 默认30天
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockBlacklistService {

    private final StockBlacklistMapper stockBlacklistMapper;
    private final RecommendationMapper recommendationMapper;

    /** 连续失利阈值 */
    private static final int CONSECUTIVE_LOSS_THRESHOLD = 3;
    /** 连续失利自动拉黑天数 */
    private static final int CONSECUTIVE_LOSS_DAYS = 30;

    /** 低命中率回溯期数 */
    private static final int LOW_HIT_RATE_LOOKBACK = 5;
    /** 低命中率阈值（0~1） */
    private static final double LOW_HIT_RATE_THRESHOLD = 0.20;
    /** 低命中率自动拉黑天数 */
    private static final int LOW_HIT_RATE_DAYS = 14;

    /** 踩雷跌幅阈值（负数） */
    private static final double SEVERE_LOSS_THRESHOLD = -8.0;
    /** 踩雷自动拉黑天数 */
    private static final int SEVERE_LOSS_DAYS = 60;

    /** 重复踩雷判定：近10次推荐中严重亏损次数≥此值 → 永久拉黑 */
    private static final int REPEATED_SEVERE_LOSS_COUNT = 2;
    /** 重复踩雷回溯推荐次数 */
    private static final int REPEATED_SEVERE_LOSS_LOOKBACK = 10;

    /** 手动拉黑默认天数 */
    private static final int MANUAL_BLACKLIST_DAYS = 30;

    // ==================== 核心查询接口 ====================

    /**
     * 获取某策略当前生效的黑名单股票代码集合
     * 推荐生成时用于过滤候选池
     */
    public Set<String> getActiveBlacklistCodes(Long strategyId) {
        if (strategyId == null) {
            return Collections.emptySet();
        }
        return stockBlacklistMapper.findActiveStockCodes(strategyId);
    }

    /**
     * 获取某策略当前生效的黑名单记录列表
     */
    public List<StockBlacklist> getActiveBlacklist(Long strategyId) {
        return stockBlacklistMapper.findActiveByStrategyId(strategyId);
    }

    /**
     * 获取某策略所有黑名单记录（含已过期的）
     */
    public List<StockBlacklist> getAllBlacklist(Long strategyId) {
        return stockBlacklistMapper.findByStrategyId(strategyId);
    }

    // ==================== 自动黑名单逻辑（追踪后调用）====================

    /**
     * 基于追踪结果自动评估并更新黑名单
     * 在 trackRecommendationPerformance() 之后调用
     *
     * @param strategyId   策略ID
     * @param recommendDate 本次追踪的推荐日期
     */
    @Transactional
    public void evaluateAndBlacklist(Long strategyId, LocalDate recommendDate) {
        if (strategyId == null || recommendDate == null) {
            return;
        }

        log.info("[Blacklist] 开始评估黑名单: strategyId={}, date={}", strategyId, recommendDate);

        // 获取该日期的推荐列表（含追踪数据）
        List<StockRecommendation> recs = recommendationMapper.findByStrategyAndDate(strategyId, recommendDate);
        if (recs == null || recs.isEmpty()) {
            return;
        }

        for (StockRecommendation rec : recs) {
            try {
                evaluateSingleStock(strategyId, rec);
            } catch (Exception e) {
                log.warn("[Blacklist] 评估个股失败: code={} error={}", rec.getStockCode(), e.getMessage());
            }
        }
    }

    /**
     * 评估单只股票是否应加入黑名单
     */
    private void evaluateSingleStock(Long strategyId, StockRecommendation rec) {
        String rawCode = rec.getStockCode();
        if (rawCode == null || rec.getNextDayReturn() == null) {
            return;
        }
        // 统一存储纯代码（无后缀），与推荐管线过滤逻辑一致
        String code = stripSuffix(rawCode);
        Double nextDayReturn = rec.getNextDayReturn();
        LocalDate recommendDate = rec.getRecommendDate();

        // 规则3：踩雷检测（优先级最高）
        if (nextDayReturn <= SEVERE_LOSS_THRESHOLD) {
            // 先检查是否已是重复踩雷 → 永久拉黑
            List<StockRecommendation> history = getRecentRecommendationsForStock(strategyId, rawCode);
            long severeLossCount = history.stream()
                    .filter(h -> h.getNextDayReturn() != null && h.getNextDayReturn() <= SEVERE_LOSS_THRESHOLD)
                    .count();
            if (severeLossCount >= REPEATED_SEVERE_LOSS_COUNT) {
                // 规则4：重复踩雷 → 永久拉黑
                addToBlacklist(strategyId, code, rec.getStockName(),
                        "REPEATED_SEVERE_LOSS",
                        String.format("近%d次推荐中%d次严重亏损(≤%.0f%%)", 
                                Math.min(history.size(), REPEATED_SEVERE_LOSS_LOOKBACK),
                                severeLossCount, SEVERE_LOSS_THRESHOLD),
                        null, // null = 永久
                        "AUTO");
                log.warn("[Blacklist] [重复踩雷-永久] code={} name={} 近{}次中{}次≤{}% 已永久拉黑",
                        code, rec.getStockName(), history.size(), severeLossCount, (int) SEVERE_LOSS_THRESHOLD);
                return;
            }

            addToBlacklist(strategyId, code, rec.getStockName(),
                    "SEVERE_LOSS",
                    String.format("次日跌幅%.1f%%(≤%d%%)", nextDayReturn, (int) SEVERE_LOSS_THRESHOLD),
                    LocalDate.now().plusDays(SEVERE_LOSS_DAYS),
                    "AUTO");
            log.warn("[Blacklist] [踩雷] code={} name={} return={:.2f}% 已自动拉黑{}天",
                    code, rec.getStockName(), nextDayReturn, SEVERE_LOSS_DAYS);
            return;
        }

        // 获取该股票近N期的推荐历史
        List<StockRecommendation> history = getRecentRecommendationsForStock(strategyId, rawCode);
        if (history.size() < CONSECUTIVE_LOSS_THRESHOLD) {
            return; // 数据不足，不判定连续失利
        }

        // 规则1：连续N次失利
        boolean consecutiveLoss = true;
        for (int i = 0; i < Math.min(CONSECUTIVE_LOSS_THRESHOLD, history.size()); i++) {
            StockRecommendation h = history.get(i);
            if (h.getNextDayReturn() == null || h.getNextDayReturn() > 0) {
                consecutiveLoss = false;
                break;
            }
        }
        if (consecutiveLoss) {
            addToBlacklist(strategyId, code, rec.getStockName(),
                    "CONSECUTIVE_LOSS",
                    String.format("连续%d次推荐次日收益为负", CONSECUTIVE_LOSS_THRESHOLD),
                    LocalDate.now().plusDays(CONSECUTIVE_LOSS_DAYS),
                    "AUTO");
            log.warn("[Blacklist] [连续失利] code={} name={} 已自动拉黑{}天",
                    code, rec.getStockName(), CONSECUTIVE_LOSS_DAYS);
            return;
        }

        // 规则2：低命中率
        if (history.size() >= LOW_HIT_RATE_LOOKBACK) {
            int hitCount = 0;
            List<StockRecentReturn> recentList = new ArrayList<>();
            for (int i = 0; i < Math.min(LOW_HIT_RATE_LOOKBACK, history.size()); i++) {
                StockRecommendation h = history.get(i);
                if (h.getNextDayReturn() != null) {
                    recentList.add(new StockRecentReturn(h.getRecommendDate(), h.getNextDayReturn()));
                    if (h.getNextDayReturn() > 0) hitCount++;
                }
            }
            double hitRate = (double) hitCount / recentList.size();

            if (hitRate < LOW_HIT_RATE_THRESHOLD && recentList.size() >= LOW_HIT_RATE_LOOKBACK) {
                addToBlacklist(strategyId, code, rec.getStockName(),
                        "LOW_HIT_RATE",
                        String.format("近%d次推荐命中率仅%.0f%%(<%d%%)",
                                LOW_HIT_RATE_LOOKBACK, hitRate * 100, (int)(LOW_HIT_RATE_THRESHOLD * 100)),
                        LocalDate.now().plusDays(LOW_HIT_RATE_DAYS),
                        "AUTO");
                log.warn("[Blacklist] [低命中率] code={} name={} 近{}次命中{}/{} 已自动拉黑{}天",
                        code, rec.getStockName(), LOW_HIT_RATE_LOOKBACK, hitCount, recentList.size(), LOW_HIT_RATE_DAYS);
            }
        }
    }

    /**
     * 获取某股票在某策略下最近的推荐记录（按日期降序）
     */
    private List<StockRecommendation> getRecentRecommendationsForStock(Long strategyId, String stockCode) {
        // 通过 RecommendationMapper 查询该股票在指定策略下的最近推荐
        LambdaQueryWrapper<StockRecommendation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StockRecommendation::getStrategyId, strategyId)
               .eq(StockRecommendation::getStockCode, stockCode)
               .isNotNull(StockRecommendation::getNextDayReturn)
               .orderByDesc(StockRecommendation::getRecommendDate)
               .last("LIMIT 10");
        return recommendationMapper.selectList(wrapper);
    }

    // ==================== 手动操作接口 ====================

    /**
     * 手动添加到黑名单
     */
    @Transactional
    public StockBlacklist manualAdd(Long strategyId, String stockCode, String stockName,
                                     String reasonDetail, Integer days) {
        // 统一存储纯代码（无后缀）
        String pureCode = stripSuffix(stockCode);
        // 先解封已有的（如果存在，匹配纯代码和带后缀两种格式）
        removeFromBlacklist(strategyId, pureCode);

        int actualDays = days != null ? days : MANUAL_BLACKLIST_DAYS;
        return addToBlacklist(strategyId, pureCode, stockName,
                "MANUAL", reasonDetail != null ? reasonDetail : "手动屏蔽",
                LocalDate.now().plusDays(actualDays), "MANUAL");
    }

    /**
     * 从黑名单中移除（解封）
     * 同时匹配纯代码和带后缀的格式，确保历史数据也能正确解封
     */
    @Transactional
    public boolean removeFromBlacklist(Long strategyId, String stockCode) {
        String pureCode = stripSuffix(stockCode);
        // 删除纯代码和带后缀的所有变体
        LambdaQueryWrapper<StockBlacklist> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StockBlacklist::getStrategyId, strategyId)
               .and(w -> w.eq(StockBlacklist::getStockCode, pureCode)
                          .or().eq(StockBlacklist::getStockCode, pureCode + ".SZ")
                          .or().eq(StockBlacklist::getStockCode, pureCode + ".SH")
                          .or().eq(StockBlacklist::getStockCode, pureCode + ".BJ"));
        int deleted = stockBlacklistMapper.delete(wrapper);
        if (deleted > 0) {
            log.info("[Blacklist] [解封] strategyId={}, code={} 已从黑名单移除", strategyId, pureCode);
        }
        return deleted > 0;
    }

    /**
     * 按ID解封
     */
    public boolean removeById(Long id) {
        int deleted = stockBlacklistMapper.deleteById(id);
        if (deleted > 0) {
            log.info("[Blacklist] [按ID解封] id={} 已从黑名单移除", id);
        }
        return deleted > 0;
    }

    /**
     * 清空某策略全部黑名单
     */
    public void clearAll(Long strategyId) {
        int count = stockBlacklistMapper.deleteAllByStrategyId(strategyId);
        log.info("[Blacklist] [清空] strategyId={} 已清空{}条黑名单", strategyId, count);
    }

    // ==================== 内部方法 ====================

    /**
     * 加入黑名单（内部方法）
     */
    private StockBlacklist addToBlacklist(Long strategyId, String stockCode, String stockName,
                                           String reason, String reasonDetail,
                                           LocalDate blacklistUntil, String createdBy) {
        // 检查是否已在黑名单中（避免重复添加）
        StockBlacklist existing = stockBlacklistMapper.findActive(strategyId, stockCode);
        if (existing != null) {
            log.info("[Blacklist] code={} 已在黑名单中(id={}, reason={}, until={}), 跳过重复添加",
                    stockCode, existing.getId(), existing.getReason(), existing.getBlacklistUntil());
            return existing;
        }

        StockBlacklist bl = new StockBlacklist();
        bl.setStrategyId(strategyId);
        bl.setStockCode(stockCode);
        bl.setStockName(stockName);
        bl.setReason(reason);
        bl.setReasonDetail(reasonDetail);
        bl.setBlacklistUntil(blacklistUntil);
        bl.setCreatedBy(createdBy);
        bl.setCreatedAt(LocalDateTime.now());
        bl.setUpdatedAt(LocalDateTime.now());

        stockBlacklistMapper.insert(bl);
        log.info("[Blacklist] [新增] strategyId={}, code={}, name={}, reason={}, until={}, by={}",
                strategyId, stockCode, stockName, reason, blacklistUntil, createdBy);
        return bl;
    }

    /** 用于传递近期收益率数据的简单DTO */
    record StockRecentReturn(LocalDate date, Double nextDayReturn) {}

    /** 去除股票代码后缀（.SZ/.SH/.BJ），返回纯代码 */
    private static String stripSuffix(String code) {
        if (code == null) return null;
        int dot = code.indexOf('.');
        return dot > 0 ? code.substring(0, dot) : code;
    }
}
