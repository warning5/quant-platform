package com.quant.platform.backtest.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 交易级归因分析服务
 * <p>
 * 提供两个分析维度：
 * <ul>
 *   <li><b>P1 持仓周期分析</b> — 不同持有天数区间的收益分布，定位最优持仓周期</li>
 *   <li><b>P2 关键交易分析</b> — 单笔交易的帕累托图，找出贡献90%利润的少数关键交易</li>
 * </ul>
 * <p>
 * 通过 FIFO 配对 BUY→SELL 交易来统一计算持仓天数、单笔盈亏。
 * 兼容 BacktestEngine 和 RollingScreenEngine 两种交易格式。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradeAnalysisService {

    private final ObjectMapper objectMapper;

    // ─── 公共数据结构 ─────────────────────────────────────────────────────────

    /** 配对后的完整交易 */
    public record PairedTrade(
            String symbol,
            String name,
            LocalDate buyDate,
            LocalDate sellDate,
            int holdingDays,
            double buyCost,      // 成本（含手续费）
            double sellAmount,   // 卖出收入（已扣手续费）
            double pnl,          // 绝对盈亏
            double pnlPct,       // 盈亏百分比
            String sellAction    // SELL / REDUCE / STOP_LOSS_SELL / STOP_PROFIT_SELL
    ) {}

    /** P1 持仓周期分析结果 */
    public static class HoldingPeriodResult {
        public String bucket;           // "1天" / "2-3天" / ...
        public int minDays, maxDays;
        public int tradeCount;
        public double avgReturn;        // 平均收益率
        public double winRate;          // 胜率
        public double totalPnl;         // 总盈亏额
        public double avgPnl;           // 平均每笔盈亏额
        public double contributionPct;  // 对总盈亏的贡献百分比
        public String summary;          // 一句话总结
    }

    /** P2 关键交易分析结果 */
    public static class TradeAttributionResult {
        public List<PairedTrade> topWinners;      // Top 10 盈利交易
        public List<PairedTrade> topLosers;       // Bottom 10 亏损交易
        public int totalTrades;                   // 总配对交易数
        public double totalPnl;                   // 总盈亏
        public double top3Contribution;           // Top 3 盈利贡献占比
        public double top10Contribution;          // Top 10 盈利贡献占比
        public double top3LossContribution;       // Bottom 3 亏损贡献占比（绝对值）
        public double top10LossContribution;      // Bottom 10 亏损贡献占比（绝对值）
        public int winnerCount, loserCount;       // 盈利/亏损交易数
        public double maxWin, maxLoss;            // 最大单笔盈利/亏损
        public double avgWin, avgLoss;            // 平均盈利/亏损
    }


    // ─── 主入口 ───────────────────────────────────────────────────────────────

    /**
     * 从 tradeLog JSON 计算完整的交易分析
     */
    public Map<String, Object> analyze(String tradeLogJson) {
        if (tradeLogJson == null || tradeLogJson.isBlank()) {
            return Map.of("error", "无交易日志数据");
        }

        try {
            List<Map<String, Object>> tradeLog = objectMapper.readValue(
                    tradeLogJson, new TypeReference<>() {
                    });

            // Step 1: FIFO 配对
            List<PairedTrade> paired = pairTrades(tradeLog);
            if (paired.isEmpty()) {
                return Map.of(
                        "error", "无法配对交易（可能交易记录过少或格式不兼容）",
                        "rawTradeCount", tradeLog.size()
                );
            }

            // Step 2: P1 持仓周期分析
            HoldingPeriodResult[] periods = computeHoldingPeriods(paired);

            // Step 3: P2 关键交易分析
            TradeAttributionResult attribution = computeTradeAttribution(paired);

            // 组装返回
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("totalPairedTrades", paired.size());
            result.put("totalRawTrades", tradeLog.size());
            result.put("holdingPeriods", periods);
            result.put("tradeAttribution", attribution);
            return result;

        } catch (Exception e) {
            log.error("交易分析失败", e);
            return Map.of("error", "交易分析失败: " + e.getMessage());
        }
    }


    // ─── FIFO 配对 ────────────────────────────────────────────────────────────

    List<PairedTrade> pairTrades(List<Map<String, Object>> tradeLog) {
        // 按 symbol 维护 FIFO 队列：每个元素是 [buyDate, shares, costBasis]
        Map<String, Deque<BuyLot>> buyQueues = new LinkedHashMap<>();
        List<PairedTrade> paired = new ArrayList<>();

        // 按日期排序（tradeLog 已经有序，但保险起见）
        List<Map<String, Object>> sorted = new ArrayList<>(tradeLog);
        sorted.sort(Comparator.comparing(t -> safeStr(t.get("date"))));

        for (Map<String, Object> trade : sorted) {
            String action = safeStr(trade.get("action"));
            String symbol = safeStr(trade.get("symbol"));
            if (symbol.isEmpty()) continue;
            String dateStr = safeStr(trade.get("date"));
            LocalDate date;
            try {
                date = LocalDate.parse(dateStr);
            } catch (Exception e) {
                continue;
            }

            // 分红：不算交易，跳过
            if ("DIVIDEND".equals(action)) continue;

            boolean isSell = "SELL".equals(action)
                    || "STOP_LOSS_SELL".equals(action)
                    || "STOP_PROFIT_SELL".equals(action)
                    || "REDUCE".equals(action);

            if (!isSell) {
                // BUY: 入队
                double shares = safeDouble(trade, "shares", safeDouble(trade, "amount", 0));
                double cost = safeDouble(trade, "total",
                        safeDouble(trade, "amount", 0));
                if (shares > 0) {
                    buyQueues.computeIfAbsent(symbol, k -> new ArrayDeque<>())
                            .addLast(new BuyLot(date, shares, cost));
                }
                continue;
            }

            // SELL: 匹配 FIFO 队列
            Deque<BuyLot> queue = buyQueues.get(symbol);
            if (queue == null || queue.isEmpty()) continue;

            double sellShares = safeDouble(trade, "shares", safeDouble(trade, "amount", 0));
            double sellAmount = safeDouble(trade, "total", safeDouble(trade, "amount", 0));
            double sellPnl = safeDouble(trade, "pnl", Double.NaN);       // RollingScreenEngine 有
            double returnPct = safeDouble(trade, "returnPct", Double.NaN); // BacktestEngine STOP 有
            String name = safeStr(trade.get("name"));

            double remainingShares = sellShares;

            while (remainingShares > 0.001 && !queue.isEmpty()) {
                BuyLot lot = queue.peekFirst();
                double matchedShares = Math.min(remainingShares, lot.shares);

                // 按比例计算成本
                double ratio = matchedShares / lot.shares;
                double matchedCost = lot.cost * ratio;

                // 按比例计算卖出收入
                double sellRatio = matchedShares / sellShares;
                double matchedSellAmount = sellAmount * sellRatio;

                double pnl;
                double pnlPct;
                if (!Double.isNaN(sellPnl) && remainingShares == sellShares && queue.size() == 1) {
                    // RollingScreenEngine: SELL 记录自带 pnl（完整清仓）
                    pnl = sellPnl * ratio;
                    pnlPct = pnl / matchedCost;
                } else if (!Double.isNaN(returnPct) && remainingShares == sellShares && queue.size() == 1) {
                    // BacktestEngine STOP: 自带 returnPct
                    pnlPct = returnPct;
                    pnl = matchedCost * returnPct;
                } else {
                    // 默认：卖出收入 - 成本
                    pnl = matchedSellAmount - matchedCost;
                    pnlPct = matchedCost > 0 ? pnl / matchedCost : 0;
                }

                int holdingDays = (int) ChronoUnit.DAYS.between(lot.date, date);

                paired.add(new PairedTrade(
                        symbol, name, lot.date, date, holdingDays,
                        matchedCost, matchedSellAmount,
                        Math.round(pnl * 100.0) / 100.0,
                        Math.round(pnlPct * 10000.0) / 10000.0,
                        action
                ));

                remainingShares -= matchedShares;
                lot.shares -= matchedShares;
                lot.cost -= matchedCost;

                if (lot.shares < 0.001) {
                    queue.pollFirst(); // 该 buy lot 完全匹配完成
                }
            }
        }

        // 按 sellDate 排序
        paired.sort(Comparator.comparing(p -> p.sellDate));
        return paired;
    }


    // ─── P1: 持仓周期分析 ─────────────────────────────────────────────────────

    HoldingPeriodResult[] computeHoldingPeriods(List<PairedTrade> paired) {
        // 定义持仓天数区间
        int[][] buckets = {
                {1, 1},
                {2, 3},
                {4, 7},
                {8, 15},
                {16, 30},
                {31, 9999}
        };
        String[] labels = {"1天", "2-3天", "4-7天", "8-15天", "16-30天", "30天以上"};

        double totalPnl = paired.stream().mapToDouble(p -> p.pnl).sum();
        List<HoldingPeriodResult> results = new ArrayList<>();

        for (int i = 0; i < buckets.length; i++) {
            int minDays = buckets[i][0], maxDays = buckets[i][1];
            List<PairedTrade> bucket = paired.stream()
                    .filter(p -> p.holdingDays >= minDays && p.holdingDays <= maxDays)
                    .toList();

            if (bucket.isEmpty()) continue;

            HoldingPeriodResult r = new HoldingPeriodResult();
            r.bucket = labels[i];
            r.minDays = minDays;
            r.maxDays = maxDays;
            r.tradeCount = bucket.size();
            r.avgReturn = bucket.stream().mapToDouble(p -> p.pnlPct).average().orElse(0);
            r.winRate = (double) bucket.stream().filter(p -> p.pnl > 0).count() / bucket.size();
            r.totalPnl = bucket.stream().mapToDouble(p -> p.pnl).sum();
            r.avgPnl = r.totalPnl / bucket.size();
            r.contributionPct = totalPnl != 0 ? r.totalPnl / Math.abs(totalPnl) * 100 : 0;

            // 总结
            if (r.winRate > 0.6 && r.avgReturn > 0) {
                r.summary = "高胜率+正收益，此持仓周期策略有效";
            } else if (r.winRate < 0.4) {
                r.summary = "胜率偏低，此持仓周期需审视进出场逻辑";
            } else if (r.avgReturn < 0) {
                r.summary = "整体亏损，此持仓周期可能不适合当前策略";
            } else {
                r.summary = "表现一般，需结合其他指标综合判断";
            }

            results.add(r);
        }

        // 按 contribution 排序
        results.sort((a, b) -> Double.compare(
                Math.abs(b.contributionPct), Math.abs(a.contributionPct)));
        return results.toArray(new HoldingPeriodResult[0]);
    }


    // ─── P2: 关键交易分析 ─────────────────────────────────────────────────────

    TradeAttributionResult computeTradeAttribution(List<PairedTrade> paired) {
        TradeAttributionResult r = new TradeAttributionResult();

        r.totalTrades = paired.size();
        r.totalPnl = paired.stream().mapToDouble(p -> p.pnl).sum();

        // 按盈亏排序
        List<PairedTrade> byPnl = paired.stream()
                .sorted((a, b) -> Double.compare(b.pnl, a.pnl))
                .toList();
        List<PairedTrade> byLoss = paired.stream()
                .sorted(Comparator.comparingDouble(a -> a.pnl))
                .toList();

        r.topWinners = byPnl.stream().limit(10).toList();
        r.topLosers = byLoss.stream().limit(10).toList();

        // 盈利/亏损统计
        r.winnerCount = (int) paired.stream().filter(p -> p.pnl > 0).count();
        r.loserCount = (int) paired.stream().filter(p -> p.pnl < 0).count();

        r.maxWin = paired.stream().mapToDouble(p -> p.pnl).max().orElse(0);
        r.maxLoss = paired.stream().mapToDouble(p -> p.pnl).min().orElse(0);

        r.avgWin = paired.stream().filter(p -> p.pnl > 0)
                .mapToDouble(p -> p.pnl).average().orElse(0);
        r.avgLoss = paired.stream().filter(p -> p.pnl < 0)
                .mapToDouble(p -> p.pnl).average().orElse(0);

        // 贡献占比
        double positiveSum = paired.stream().filter(p -> p.pnl > 0)
                .mapToDouble(p -> p.pnl).sum();
        if (positiveSum > 0) {
            r.top3Contribution = byPnl.stream().limit(3)
                    .mapToDouble(p -> Math.max(p.pnl, 0)).sum() / positiveSum;
            r.top10Contribution = byPnl.stream().limit(10)
                    .mapToDouble(p -> Math.max(p.pnl, 0)).sum() / positiveSum;
        }

        double lossSum = Math.abs(paired.stream().filter(p -> p.pnl < 0)
                .mapToDouble(p -> p.pnl).sum());
        if (lossSum > 0) {
            r.top3LossContribution = byLoss.stream().limit(3)
                    .mapToDouble(p -> Math.abs(Math.min(p.pnl, 0))).sum() / lossSum;
            r.top10LossContribution = byLoss.stream().limit(10)
                    .mapToDouble(p -> Math.abs(Math.min(p.pnl, 0))).sum() / lossSum;
        }

        return r;
    }


    // ─── 辅助方法 ─────────────────────────────────────────────────────────────

    private static String safeStr(Object obj) {
        return obj != null ? obj.toString().trim() : "";
    }

    private static double safeDouble(Map<String, Object> map, String key, double defaultVal) {
        Object val = map.get(key);
        if (val == null) return defaultVal;
        if (val instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(val.toString());
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    /** FIFO 队列中的买入批次 */
    private static class BuyLot {
        final LocalDate date;
        double shares;
        double cost;  // 总成本（含手续费）

        BuyLot(LocalDate date, double shares, double cost) {
            this.date = date;
            this.shares = shares;
            this.cost = cost;
        }
    }
}
