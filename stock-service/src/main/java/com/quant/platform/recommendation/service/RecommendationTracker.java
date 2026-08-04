package com.quant.platform.recommendation.service;

import com.quant.platform.market.domain.MarketDailyBar;
import com.quant.platform.market.service.MarketDataService;
import com.quant.platform.recommendation.domain.StockRecommendation;
import com.quant.platform.recommendation.mapper.RecommendationMapper;
import com.quant.platform.stock.service.DividendService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 推荐表现追踪器（次日/一周/一月收益 + 超额收益 + 黑名单/置信度联动）。
 * <p>
 * 从 {@link RecommendationService} 抽取（God Class 拆分 Phase 3），方法体逐字迁移，
 * SQL 访问、复权口径、四舍五入方式、日志文案与副作用调用顺序均未改变。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecommendationTracker {

    /**
     * 沪深300指数代码
     */
    private static final String SSE300_CODE = "000300";

    private final RecommendationMapper recommendationMapper;
    private final MarketDataService marketDataService;
    private final DividendService dividendService;
    private final StockBlacklistService stockBlacklistService;
    private final StrategyConfidenceService strategyConfidenceService;

    /**
     * 追踪推荐表现（Phase 3.2）
     * 对未追踪或需要更新的推荐批次，计算:
     * - 次日收益率
     * - 一周收益率
     * - 一月收益率
     *
     * @return 更新的记录数
     */
    public int trackRecommendationPerformance() {
        // 按 (strategy_id, recommend_date, weight_mode) 三元组去重，确保每种模式的快照都能被追踪
        // P1-4 修复：放宽 LIMIT，避免老 combo（周/月可补全的）被截断丢失。
        // 配合 RecommendationMapper 中完成标志改为 next_month_return，确保历史周/月收益持续补算。
        List<Map<String, Object>> recentCombos = recommendationMapper.findUntrackedStrategyDateModes(1000);
        if (recentCombos.isEmpty()) {
            log.info("[Recommendation] 所有近期组合均已追踪，跳过");
            return 0;
        }
        int totalUpdated = 0;

        LocalDate today = LocalDate.now();

        for (Map<String, Object> combo : recentCombos) {
            Object sidObj = combo.get("strategy_id");
            if (sidObj == null) continue; // 跳过 strategy_id 为空的脏数据
            Long sid = ((Number) sidObj).longValue();
            java.sql.Date sqlDate = (java.sql.Date) combo.get("recommend_date");
            if (sqlDate == null) continue;
            LocalDate recDate = sqlDate.toLocalDate();
            String weightMode = (String) combo.get("weight_mode");

            // 按模式读取该模式下的快照（多模式快照隔离追踪）
            List<StockRecommendation> recs = weightMode != null
                ? recommendationMapper.findByStrategyAndDateAndMode(sid, recDate, weightMode)
                : recommendationMapper.findByStrategyAndDate(sid, recDate);
            if (recs.isEmpty()) continue;

            int daysSince = (int) java.time.temporal.ChronoUnit.DAYS.between(recDate, today);
            if (daysSince <= 0) continue; // 推荐当天或未来，不追踪

            for (StockRecommendation rec : recs) {
                try {
                    boolean updated = false;

                    // 次日收益率（总是重新计算，覆盖旧值，以支持除权修正/数据修正）
                    if (daysSince >= 1) {
                        Double val = calcForwardReturn(rec.getStockCode(), recDate, 1);
                        if (val != null) {
                            rec.setNextDayReturn(val);
                            // P0-2: 计算超额收益 = 个股收益 - 沪深300收益
                            Double benchReturn = calcForwardReturn(SSE300_CODE, recDate, 1);
                            if (benchReturn != null) {
                                rec.setNextDayExcessReturn(Math.round((val - benchReturn) * 100.0) / 100.0);
                            }
                            updated = true;
                        }
                    }

                    // 一周收益率
                    if (daysSince >= 5) {
                        Double val = calcForwardReturn(rec.getStockCode(), recDate, 5);
                        if (val != null) {
                            rec.setNextWeekReturn(val);
                            Double benchReturn = calcForwardReturn(SSE300_CODE, recDate, 5);
                            if (benchReturn != null) {
                                rec.setNextWeekExcessReturn(Math.round((val - benchReturn) * 100.0) / 100.0);
                            }
                            updated = true;
                        }
                    }

                    // 一月收益率
                    if (daysSince >= 22) {
                        Double val = calcForwardReturn(rec.getStockCode(), recDate, 22);
                        if (val != null) {
                            rec.setNextMonthReturn(val);
                            Double benchReturn = calcForwardReturn(SSE300_CODE, recDate, 22);
                            if (benchReturn != null) {
                                rec.setNextMonthExcessReturn(Math.round((val - benchReturn) * 100.0) / 100.0);
                            }
                            updated = true;
                        }
                    }

                    if (updated) {
                        rec.setTrackingUpdatedAt(java.time.LocalDateTime.now());
                        recommendationMapper.updateById(rec);
                        totalUpdated++;
                    }
                } catch (Exception e) {
                    log.warn("[Recommendation] 追踪失败: code={} strategyId={} date={} error={}",
                            rec.getStockCode(), sid, recDate, e.getMessage());
                }
            }
        }

        log.info("[Recommendation] 表现追踪完成: 更新{}条记录", totalUpdated);

        // 追踪完成后，自动评估并更新黑名单（方案B）
        for (Map<String, Object> combo : recentCombos) {
            Object sidObj = combo.get("strategy_id");
            Object dateObj = combo.get("recommend_date");
            if (sidObj == null || dateObj == null) continue;
            Long sid = ((Number) sidObj).longValue();
            LocalDate recDate = ((java.sql.Date) dateObj).toLocalDate();
            try {
                stockBlacklistService.evaluateAndBlacklist(sid, recDate);
            } catch (Exception e) {
                log.warn("[Recommendation] 黑名单自动评估异常: strategyId={} date={} error={}", sid, recDate, e.getMessage());
            }
        }

        // 追踪完成后，自动更新策略置信度（方案C）
        // P1优化: 按 (strategyId, weightMode) 去重，分别计算每种模式的置信度
        Set<String> seenStrategyModes = new HashSet<>();
        for (Map<String, Object> combo : recentCombos) {
            Object sidObj = combo.get("strategy_id");
            if (sidObj == null) continue;
            Long sid = ((Number) sidObj).longValue();
            String mode = combo.get("weight_mode") != null ? combo.get("weight_mode").toString() : "ICW";
            String key = sid + ":" + mode;
            if (!seenStrategyModes.add(key)) continue; // 已处理过该策略+模式
            try {
                strategyConfidenceService.calculateAndSave(sid, mode);
            } catch (Exception e) {
                log.warn("[Recommendation] 置信度自动计算异常: strategyId={}, mode={} error={}", sid, mode, e.getMessage());
            }
        }

        return totalUpdated;
    }

    /**
     * 计算未来N日收益率 (Phase 3.2)
     * <p>
     * 通过 MarketDataService 获取目标日和基准日收盘价
     * stockCode 格式可能是 "600027.SH" 或纯代码
     */
    private Double calcForwardReturn(String stockCode, LocalDate baseDate, int forwardDays) {
        try {
            // 先取 baseDate 前后足够多的行情，找到 baseDate 对应的交易日及之后第 forwardDays 个交易日
            LocalDate searchStart = baseDate.minusDays(5); // 确保包含baseDate当天
            LocalDate searchEnd = baseDate.plusDays(forwardDays * 2L + 10); // 多取一些确保有足够交易日
            List<MarketDailyBar> bars = marketDataService.getBarsInRange(stockCode, searchStart, searchEnd);
            if (bars == null || bars.isEmpty()) return null;

            // 找到 >= baseDate 的第一个交易日（作为基准日）
            int baseIdx = -1;
            for (int i = 0; i < bars.size(); i++) {
                if (!bars.get(i).getTradeDate().isBefore(baseDate)) {
                    baseIdx = i;
                    break;
                }
            }
            if (baseIdx < 0) return null;

            // 找到基准日之后第 forwardDays 个交易日
            int targetIdx = baseIdx + forwardDays;
            if (targetIdx >= bars.size()) return null; // 数据不足，无法计算

            double baseClose = bars.get(baseIdx).getClose().doubleValue();
            double targetClose = bars.get(targetIdx).getClose().doubleValue();
            if (baseClose <= 0 || targetClose <= 0) return null;

            // 前复权调整：用累积复权因子消除除权除息的价格跳空
            LocalDate targetDate = bars.get(targetIdx).getTradeDate();
            double baseAdj = dividendService.getCumulativeAdjFactor(stockCode, baseDate).doubleValue();
            double targetAdj = dividendService.getCumulativeAdjFactor(stockCode, targetDate).doubleValue();
            if (baseAdj <= 0 || targetAdj <= 0) {
                // 复权因子异常，回退到不复权计算
                return Math.round((targetClose / baseClose - 1.0) * 10000.0) / 100.0;
            }

            double adjBaseClose = baseClose * baseAdj;
            double adjTargetClose = targetClose * targetAdj;
            return Math.round((adjTargetClose / adjBaseClose - 1.0) * 10000.0) / 100.0; // 百分比，保留2位
        } catch (Exception e) {
            return null;
        }
    }
}
