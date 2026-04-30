package com.quant.platform.backtest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.backtest.domain.BacktestReport;
import com.quant.platform.backtest.domain.BacktestTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

/**
 * 多策略对比服务
 * 将多个已完成回测的绩效指标、净值曲线合并为可对比的结构
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompareService {

    private final BacktestService backtestService;
    private final ObjectMapper objectMapper;

    /**
     * 批量获取多策略对比数据
     *
     * @param taskIds 多个回测任务ID列表
     * @return 包含 metrics(指标表) 和 curves(净值曲线) 的 Map
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> compare(List<Long> taskIds) {
        List<Map<String, Object>> metrics = new ArrayList<>();
        List<Map<String, Object>> curves = new ArrayList<>();

        // 颜色列表，依次为各策略着色
        String[] colors = {"#cf1322", "#1677ff", "#fa8c16", "#52c41a",
                "#722ed1", "#eb2f96", "#13c2c2", "#faad14"};

        for (int i = 0; i < taskIds.size(); i++) {
            Long taskId = taskIds.get(i);
            try {
                BacktestTask task = backtestService.getTask(taskId);
                BacktestReport report = backtestService.getReport(taskId);

                // ── 指标行 ──────────────────────────────────────────────────
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("taskId", taskId);
                row.put("taskName", task.getTaskName() != null ? task.getTaskName() : "回测-" + task.getStrategyCode());
                row.put("strategyCode", task.getStrategyCode());
                row.put("startDate", task.getStartDate());
                row.put("endDate", task.getEndDate());
                row.put("color", colors[i % colors.length]);

                // 收益指标
                row.put("totalReturn", report.getTotalReturn());
                row.put("annualReturn", report.getAnnualReturn());
                row.put("benchmarkReturn", report.getBenchmarkReturn());
                row.put("excessReturn", report.getExcessReturn());
                // 风险指标
                row.put("maxDrawdown", report.getMaxDrawdown());
                row.put("volatility", report.getVolatility());
                row.put("sharpeRatio", report.getSharpeRatio());
                row.put("sortinoRatio", report.getSortinoRatio());
                row.put("calmarRatio", report.getCalmarRatio());
                row.put("informationRatio", report.getInformationRatio());
                row.put("alpha", report.getAlpha());
                row.put("beta", report.getBeta());
                row.put("trackingError", report.getTrackingError());
                row.put("downsideRisk", report.getDownsideRisk());
                // 交易统计
                row.put("totalTrades", report.getTotalTrades());
                row.put("winRate", report.getWinRate());
                row.put("profitLossRatio", report.getProfitLossRatio());
                row.put("maxDrawdownDuration", report.getMaxDrawdownDuration());

                metrics.add(row);

                // ── 净值曲线 ─────────────────────────────────────────────────
                List<Map<String, Object>> equityCurve = safeParseList(report.getEquityCurveJson());
                Map<String, Object> curveEntry = new HashMap<>();
                curveEntry.put("taskId", taskId);
                curveEntry.put("name", row.get("taskName"));
                curveEntry.put("color", colors[i % colors.length]);
                curveEntry.put("data", equityCurve);
                curves.add(curveEntry);

            } catch (Exception e) {
                log.warn("跳过任务 [{}]: {}", taskId, e.getMessage());
            }
        }

        // 计算排名（按年化收益降序）
        metrics.sort((a, b) -> {
            BigDecimal ra = toBD(a.get("annualReturn"));
            BigDecimal rb = toBD(b.get("annualReturn"));
            return rb.compareTo(ra);
        });
        for (int i = 0; i < metrics.size(); i++) {
            metrics.get(i).put("rank", i + 1);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("metrics", metrics);
        result.put("curves", curves);
        result.put("count", metrics.size());
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> safeParseList(String json) {
        try {
            if (json == null || json.isBlank()) return Collections.emptyList();
            return objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private BigDecimal toBD(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal bd) return bd;
        try {
            return new BigDecimal(v.toString());
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }
}
