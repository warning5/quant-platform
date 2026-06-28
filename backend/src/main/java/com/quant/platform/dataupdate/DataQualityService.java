package com.quant.platform.dataupdate;

import com.quant.platform.stock.service.ClickHouseStockService;
import com.quant.platform.calendar.service.TradeCalendarService;
import com.quant.platform.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 数据质量监控服务
 * - 定期检查数据新鲜度（日线/因子/财务）
 * - 定期检查价格异常（单日涨跌幅>50%）
 * - 通过 WebSocket 推送告警到前端仪表盘
 * - 通过 NotificationService 推送告警到外部（企业微信/钉钉/Server酱）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataQualityService {

    private final ClickHouseStockService clickHouseStockService;
    private final JdbcTemplate jdbcTemplate;
    private final SimpMessagingTemplate messagingTemplate;
    private final NotificationService notificationService;
    private final TradeCalendarService tradeCalendarService;

    /**
     * 执行全量质量检查（新鲜度 + 价格异常）
     * 返回合并报告，并通过 WebSocket 推送
     */
    public Map<String, Object> runAllChecks() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("checkTime", LocalDateTime.now().toString());

        Map<String, Object> freshness = checkDataFreshness();
        report.put("freshness", freshness);

        Map<String, Object> anomalies = checkPriceAnomalies(7);
        report.put("priceAnomalies", anomalies);

        Map<String, Object> factorNulls = checkFactorNullRatio();
        report.put("factorNullRatio", factorNulls);

        Map<String, Object> finAnomalies = checkFinancialAnomalies();
        report.put("financialAnomalies", finAnomalies);

        // 汇总告警状态
        boolean hasWarning = freshness.get("hasWarning") instanceof Boolean b && b;
        if (anomalies.get("hasAnomaly") instanceof Boolean b && b) hasWarning = true;
        if (factorNulls.get("hasWarning") instanceof Boolean b && b) hasWarning = true;
        if (finAnomalies.get("hasAnomaly") instanceof Boolean b && b) hasWarning = true;
        report.put("hasWarning", hasWarning);

        // WebSocket 推送
        try {
            messagingTemplate.convertAndSend("/topic/data-quality", report);
        } catch (Exception e) {
            log.debug("[DataQuality] WebSocket 推送失败（前端可能未连接）: {}", e.getMessage());
        }

        // 如果存在告警，推送外部通知
        if (hasWarning) {
            StringBuilder alertMsg = new StringBuilder("## 数据质量告警\n\n");
            alertMsg.append("检查时间: ").append(LocalDateTime.now()).append("\n\n");

            if (freshness.get("hasWarning") instanceof Boolean b && b) {
                alertMsg.append("### 数据新鲜度异常\n");
                appendFreshnessWarnings(alertMsg, freshness);
            }
            if (anomalies.get("hasAnomaly") instanceof Boolean b && b) {
                alertMsg.append("### 价格异常\n");
                alertMsg.append("发现 ").append(anomalies.get("anomalyCount")).append(" 条涨跌幅>50%记录\n");
            }
            if (factorNulls.get("hasWarning") instanceof Boolean b && b) {
                alertMsg.append("### 因子数据 NULL 异常\n");
                alertMsg.append("发现 ").append(factorNulls.get("nullFactorCount")).append(" 个因子NULL比例>50%\n");
            }
            if (finAnomalies.get("hasAnomaly") instanceof Boolean b && b) {
                alertMsg.append("### 财务数据突变\n");
                alertMsg.append("发现 ").append(finAnomalies.get("anomalyCount")).append(" 条营收/利润环比跳变>100%记录\n");
            }

            notificationService.sendAlert(alertMsg.toString());
        }

        return report;
    }

    /**
     * 检查数据新鲜度
     * - stock_daily: 最新交易日 vs 当前交易日的天数差（>2天告警）
     * - factor_value: 最新计算日 vs 当前交易日（>1天告警）
     * - financial_indicator: 最新报告期 vs 最近季末（>90天告警）
     */
    public Map<String, Object> checkDataFreshness() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("checkTime", LocalDateTime.now().toString());

        // 确定最新交易日
        LocalDate today = LocalDate.now();
        LocalDate latestTradeDate = today;
        if (tradeCalendarService != null) {
            for (int i = 0; i < 10; i++) {
                LocalDate d = today.minusDays(i);
                if (tradeCalendarService.isTradingDay(d)) {
                    latestTradeDate = d;
                    break;
                }
            }
        }
        report.put("latestTradeDate", latestTradeDate.toString());

        // 1. stock_daily (ClickHouse)
        try {
            Object sdMax = clickHouseStockService.queryForObject(
                "SELECT max(trade_date) FROM stock_daily WHERE code NOT LIKE '399%' AND code NOT LIKE '000%' AND length(code)=6");
            LocalDate sdDate = sdMax != null ? LocalDate.parse(sdMax.toString()) : null;
            long sdDays = sdDate != null ? latestTradeDate.toEpochDay() - sdDate.toEpochDay() : 999;
            Map<String, Object> sdStatus = new LinkedHashMap<>();
            sdStatus.put("latestDate", sdDate != null ? sdDate.toString() : "N/A");
            sdStatus.put("daysBehind", sdDays);
            sdStatus.put("stale", sdDays > 2);
            if (sdDays > 2) {
                log.warn("[数据质量] stock_daily 落后 {} 天（最新={}，基准={}）", sdDays, sdDate, latestTradeDate);
            }
            report.put("stockDaily", sdStatus);
        } catch (Exception e) {
            log.warn("[数据质量] stock_daily 查询失败: {}", e.getMessage());
            report.put("stockDaily", Map.of("error", e.getMessage()));
        }

        // 2. factor_value (ClickHouse)
        try {
            Object fvMax = clickHouseStockService.queryForObject(
                "SELECT max(calc_date) FROM factor_value");
            LocalDate fvDate = fvMax != null ? LocalDate.parse(fvMax.toString()) : null;
            long fvDays = fvDate != null ? latestTradeDate.toEpochDay() - fvDate.toEpochDay() : 999;
            Map<String, Object> fvStatus = new LinkedHashMap<>();
            fvStatus.put("latestDate", fvDate != null ? fvDate.toString() : "N/A");
            fvStatus.put("daysBehind", fvDays);
            fvStatus.put("stale", fvDays > 1);
            if (fvDays > 1) {
                log.warn("[数据质量] factor_value 落后 {} 天（最新={}，基准={}）", fvDays, fvDate, latestTradeDate);
            }
            report.put("factorValue", fvStatus);
        } catch (Exception e) {
            log.warn("[数据质量] factor_value 查询失败: {}", e.getMessage());
            report.put("factorValue", Map.of("error", e.getMessage()));
        }

        // 3. stock_financial_indicator (MySQL)
        try {
            Object fiMax = jdbcTemplate.queryForObject(
                "SELECT max(report_date) FROM stock_financial_indicator WHERE report_type IN (1,2,4)", String.class);
            LocalDate fiDate = null;
            if (fiMax != null) {
                String fiStr = fiMax.toString().trim();
                try {
                    fiDate = LocalDate.parse(fiStr);
                } catch (Exception parseEx) {
                    // report_date 可能存储为 yyyyMMdd 格式（如 20260331）
                    fiDate = LocalDate.parse(fiStr, DateTimeFormatter.ofPattern("yyyyMMdd"));
                }
            }
            long fiStale = 999;
            if (fiDate != null) {
                int year = today.getYear();
                int month = today.getMonthValue();
                LocalDate lastQuarterEnd;
                if (month <= 3) lastQuarterEnd = LocalDate.of(year - 1, 12, 31);
                else if (month <= 6) lastQuarterEnd = LocalDate.of(year, 3, 31);
                else if (month <= 9) lastQuarterEnd = LocalDate.of(year, 6, 30);
                else lastQuarterEnd = LocalDate.of(year, 9, 30);
                fiStale = lastQuarterEnd.toEpochDay() - fiDate.toEpochDay();
                if (fiStale < 0) fiStale = 0;
            }
            Map<String, Object> fiStatus = new LinkedHashMap<>();
            fiStatus.put("latestReportDate", fiDate != null ? fiDate.toString() : "N/A");
            fiStatus.put("quartersBehind", fiStale > 90 ? fiStale / 90 : 0);
            fiStatus.put("stale", fiStale > 90);
            if (fiStale > 90) {
                log.warn("[数据质量] financial_indicator 落后约 {} 天（最新报告期={}）", fiStale, fiDate);
            }
            report.put("financialIndicator", fiStatus);
        } catch (Exception e) {
            log.warn("[数据质量] financial_indicator 查询失败: {}", e.getMessage());
            report.put("financialIndicator", Map.of("error", e.getMessage()));
        }

        // 汇总
        report.put("hasWarning", report.values().stream().anyMatch(v -> {
            if (v instanceof Map<?,?> m) {
                Object stale = m.get("stale");
                return stale instanceof Boolean b && b;
            }
            return false;
        }));

        return report;
    }

    /**
     * 价格异常检测：查询近 N 天内单日涨跌幅绝对值 > 50% 的记录
     */
    public Map<String, Object> checkPriceAnomalies(int days) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checkTime", LocalDateTime.now().toString());
        result.put("lookbackDays", days);

        try {
            String sql = String.format(
                "SELECT code, name, trade_date, close_price, pre_close, change_percent " +
                "FROM stock_daily " +
                "WHERE trade_date >= today() - %d " +
                "  AND pre_close > 0 " +
                "  AND abs(change_percent) > 50 " +
                "ORDER BY trade_date DESC, abs(change_percent) DESC " +
                "LIMIT 100", days);

            List<Map<String, Object>> anomalies = new ArrayList<>();
            List<Map<String, Object>> rows = clickHouseStockService.queryForList(sql);
            for (Map<String, Object> row : rows) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("code", row.get("code"));
                item.put("name", row.get("name"));
                item.put("tradeDate", row.get("trade_date") != null ? row.get("trade_date").toString() : null);
                item.put("closePrice", row.get("close_price"));
                item.put("preClose", row.get("pre_close"));
                item.put("changePct", row.get("change_percent"));
                anomalies.add(item);
            }

            result.put("anomalyCount", anomalies.size());
            result.put("anomalies", anomalies);
            result.put("hasAnomaly", !anomalies.isEmpty());

            if (!anomalies.isEmpty()) {
                log.warn("[数据质量] 近{}天发现 {} 条涨跌幅>50%记录", days, anomalies.size());
            }
        } catch (Exception e) {
            log.warn("[数据质量] 价格异常查询失败: {}", e.getMessage());
            result.put("error", e.getMessage());
        }

        return result;
    }

    /** 拼装新鲜度告警详情 */
    private void appendFreshnessWarnings(StringBuilder sb, Map<String, Object> freshness) {
        Map<?, ?> sd = (Map<?, ?>) freshness.get("stockDaily");
        if (sd != null && sd.get("stale") instanceof Boolean b && b) {
            sb.append("- 股票日线落后 ").append(sd.get("daysBehind")).append(" 天（最新=").append(sd.get("latestDate")).append("）\n");
        }
        Map<?, ?> fv = (Map<?, ?>) freshness.get("factorValue");
        if (fv != null && fv.get("stale") instanceof Boolean b && b) {
            sb.append("- 因子数据落后 ").append(fv.get("daysBehind")).append(" 天（最新=").append(fv.get("latestDate")).append("）\n");
        }
        Map<?, ?> fi = (Map<?, ?>) freshness.get("financialIndicator");
        if (fi != null && fi.get("stale") instanceof Boolean b && b) {
            sb.append("- 财务数据落后 ").append(fi.get("quartersBehind")).append(" 个季度（最新报告期=").append(fi.get("latestReportDate")).append("）\n");
        }
    }

    /**
     * 因子全 NULL 检测
     * 检查 factor_value 中每个 ACTIVE 因子在最新交易日的 NULL 值比例
     * NULL 比例 > 50% 的因子视为异常
     */
    public Map<String, Object> checkFactorNullRatio() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checkTime", LocalDateTime.now().toString());

        try {
            // 查询最新交易日
            Object latestDateObj = clickHouseStockService.queryForObject(
                "SELECT max(calc_date) FROM factor_value");
            if (latestDateObj == null) {
                result.put("error", "factor_value 表无数据");
                result.put("hasWarning", false);
                return result;
            }
            String latestDate = latestDateObj.toString();
            result.put("latestDate", latestDate);

            // 按因子代码统计 NULL 比例
            String sql =
                "SELECT factor_code, " +
                "  count() AS total, " +
                "  countIf(isNull(factor_val)) AS null_cnt, " +
                "  round(null_cnt / total * 100, 2) AS null_pct " +
                "FROM factor_value " +
                "WHERE calc_date = ? " +
                "GROUP BY factor_code " +
                "HAVING null_pct > 50 " +
                "ORDER BY null_pct DESC";

            List<Map<String, Object>> rows = clickHouseStockService.queryForList(sql, latestDate);

            List<Map<String, Object>> nullFactors = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("factorCode", row.get("factor_code"));
                item.put("nullPct", row.get("null_pct"));
                item.put("totalCount", row.get("total"));
                item.put("nullCount", row.get("null_cnt"));
                nullFactors.add(item);
            }

            result.put("nullFactorCount", nullFactors.size());
            result.put("nullFactors", nullFactors);
            result.put("hasWarning", !nullFactors.isEmpty());

            if (!nullFactors.isEmpty()) {
                log.warn("[数据质量] 发现 {} 个因子在 {} 的NULL比例>50%",
                    nullFactors.size(), latestDate);
            }
        } catch (Exception e) {
            log.warn("[数据质量] 因子NULL检测失败: {}", e.getMessage());
            result.put("error", e.getMessage());
            result.put("hasWarning", false);
        }

        return result;
    }

    /**
     * 财务数据突变检测
     * 检查 stock_income 中营收/净利润环比跳变 > 100% 的记录
     * 跳变 = |本期 - 上期| / |上期| > 100%（且上期 != 0）
     */
    public Map<String, Object> checkFinancialAnomalies() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checkTime", LocalDateTime.now().toString());

        try {
            // 查询最近两个报告期，用 LAG 窗口函数获取上期值
            // 注意：MySQL 不允许 WHERE 引用 SELECT 别名，需多包一层子查询
            // report_date 是 VARCHAR(10) 格式 "20250331"，需 STR_TO_DATE 转换后比较
            String sql =
                "SELECT * FROM (" +
                "  SELECT code, report_date, " +
                "    revenue, net_profit, " +
                "    prevRevenue, prevNetProfit, " +
                "    CASE WHEN prevRevenue IS NOT NULL AND prevRevenue != 0 " +
                "      THEN ROUND(ABS(revenue - prevRevenue) / ABS(prevRevenue) * 100, 2) " +
                "      ELSE 0 END AS revenue_chg_pct, " +
                "    CASE WHEN prevNetProfit IS NOT NULL AND prevNetProfit != 0 " +
                "      THEN ROUND(ABS(net_profit - prevNetProfit) / ABS(prevNetProfit) * 100, 2) " +
                "      ELSE 0 END AS profit_chg_pct " +
                "  FROM (" +
                "    SELECT code, report_date, revenue, net_profit, " +
                "      LAG(revenue) OVER (PARTITION BY code ORDER BY report_date) AS prevRevenue, " +
                "      LAG(net_profit) OVER (PARTITION BY code ORDER BY report_date) AS prevNetProfit " +
                "    FROM stock_income " +
                "    WHERE report_type IN (1, 2, 4) " +
                "      AND STR_TO_DATE(report_date, '%Y%m%d') >= DATE_SUB(CURDATE(), INTERVAL 18 MONTH) " +
                "  ) t" +
                ") t2 " +
                "WHERE (revenue_chg_pct > 100 OR profit_chg_pct > 100) " +
                "  AND prevRevenue IS NOT NULL " +
                "  AND prevNetProfit IS NOT NULL " +
                "ORDER BY GREATEST(revenue_chg_pct, profit_chg_pct) DESC " +
                "LIMIT 50";

            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);

            List<Map<String, Object>> anomalies = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("code", row.get("code"));
                item.put("reportDate", row.get("report_date") != null ? row.get("report_date").toString() : null);
                item.put("revenue", row.get("revenue"));
                item.put("prevRevenue", row.get("prevRevenue"));
                item.put("revenueChgPct", row.get("revenue_chg_pct"));
                item.put("netProfit", row.get("net_profit"));
                item.put("prevNetProfit", row.get("prevNetProfit"));
                item.put("profitChgPct", row.get("profit_chg_pct"));
                anomalies.add(item);
            }

            result.put("anomalyCount", anomalies.size());
            result.put("anomalies", anomalies);
            result.put("hasAnomaly", !anomalies.isEmpty());

            if (!anomalies.isEmpty()) {
                log.warn("[数据质量] 发现 {} 条财务数据环比跳变>100%记录", anomalies.size());
            }
        } catch (Exception e) {
            log.warn("[数据质量] 财务突变检测失败: {}", e.getMessage());
            result.put("error", e.getMessage());
            result.put("hasAnomaly", false);
        }

        return result;
    }
}
