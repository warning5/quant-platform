package com.quant.platform.backtest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.backtest.domain.BacktestTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.quant.platform.backtest.service.FactorStyleAttributionService.StrategyCharacteristics;
import static com.quant.platform.backtest.service.OlsRegressionCalculator.round4;

@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyProfiler {

    private final ObjectMapper objectMapper;

    /**
     * 检测策略特征 — 用于自动匹配归因方案
     */
    @SuppressWarnings("unchecked")
    public StrategyCharacteristics detectCharacteristics(BacktestTask task,
                                                         String positionHistoryJson) {
        if (positionHistoryJson == null || positionHistoryJson.isBlank()) {
            return new StrategyCharacteristics(0, 0, 0, "FACTOR",
                    "持仓数据为空，默认使用因子风格归因");
        }

        List<Map<String, Object>> positionHistory;
        try {
            positionHistory = objectMapper.readValue(positionHistoryJson, List.class);
        } catch (Exception e) {
            return new StrategyCharacteristics(0, 0, 0, "FACTOR",
                    "持仓数据解析失败");
        }
        if (positionHistory.size() < 2) {
            return new StrategyCharacteristics(0, 0, 0, "FACTOR",
                    "持仓期数不足，默认使用因子风格归因");
        }

        // 计算日均换手率
        double totalTurnover = 0;
        int turnoverPeriods = 0;
        // 计算行业集中度
        double totalHHI = 0;
        int hhiPeriods = 0;

        for (int i = 0; i < positionHistory.size(); i++) {
            Map<String, Object> snap = positionHistory.get(i);
            Map<String, Object> positions = (Map<String, Object>) snap.get("positions");
            if (positions == null || positions.isEmpty()) continue;

            // 换手率：本期 vs 上期权重变化
            if (i > 0) {
                Map<String, Object> prevPositions = (Map<String, Object>) positionHistory.get(i - 1).get("positions");
                if (prevPositions != null) {
                    double periodTurnover = 0;
                    Set<String> allSymbols = new HashSet<>();
                    allSymbols.addAll(prevPositions.keySet());
                    allSymbols.addAll(positions.keySet());
                    for (String symbol : allSymbols) {
                        double prevW = prevPositions.containsKey(symbol)
                                ? ((Number) prevPositions.get(symbol)).doubleValue() : 0;
                        double currW = ((Number) positions.getOrDefault(symbol, 0)).doubleValue();
                        periodTurnover += Math.abs(currW - prevW);
                    }
                    totalTurnover += periodTurnover / 2.0;
                    turnoverPeriods++;
                }
            }

            // HHI
            double sumW = 0;
            double sumW2 = 0;
            for (Object w : positions.values()) {
                double weight = ((Number) w).doubleValue();
                sumW += weight;
                sumW2 += weight * weight;
            }
            if (sumW > 0) {
                totalHHI += sumW2 / (sumW * sumW);
                hhiPeriods++;
            }
        }

        double avgTurnover = turnoverPeriods > 0 ? totalTurnover / turnoverPeriods : 0;
        double avgHHI = hhiPeriods > 0 ? totalHHI / hhiPeriods : 0;
        int periodCount = positionHistory.size();
        double avgHoldingDays = 0;
        if (periodCount >= 2 && avgTurnover > 0.001) {
            String startStr = (String) positionHistory.getFirst().get("date");
            String endStr = (String) positionHistory.get(periodCount - 1).get("date");
            try {
                long days = LocalDate.parse(endStr).toEpochDay() - LocalDate.parse(startStr).toEpochDay();
                double rebalanceInterval = (double) days / (periodCount - 1);
                avgHoldingDays = rebalanceInterval / Math.max(avgTurnover, 0.01);
                avgHoldingDays = Math.min(avgHoldingDays, days);
            } catch (Exception ignored) {
                log.error("[StrategyProfiler] 捕获到未处理异常", ignored);
            }
        }

        // 决策逻辑
        boolean highTurnover = avgTurnover > 0.5 || (avgHoldingDays > 0 && avgHoldingDays < 5);
        boolean highConcentration = avgHHI > 0.3;

        String model;
        String reason;
        if (highTurnover && !highConcentration) {
            model = "FACTOR";
            reason = String.format("日均换手率 %.0f%%, 平均持仓约 %.1f天 — 收益来源主要在因子暴露维度，行业归因不适用",
                    avgTurnover * 100, avgHoldingDays);
        } else if (highConcentration && !highTurnover) {
            model = "BRINSON";
            reason = String.format("行业集中度(HHI) %.2f, 日均换手率 %.0f%% — 适合行业层面的归因分析",
                    avgHHI, avgTurnover * 100);
        } else if (highTurnover) {
            model = "FACTOR";
            reason = String.format("高换手(%.0f%%)+高集中度(HHI=%.2f) — 优先用因子归因，Brinson可能辅助",
                    avgTurnover * 100, avgHHI);
        } else {
            model = "BRINSON";
            reason = String.format("低频策略(换手 %.0f%%) — 行业归因适用", avgTurnover * 100);
        }

        return new StrategyCharacteristics(
                round4(avgTurnover), round4(avgHoldingDays), round4(avgHHI), model, reason);
    }
}
