package com.quant.platform.stock.analysis.service;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * 事件信号服务
 * 读取 MySQL 中的业绩快报 + 一致预期数据，计算"超预期/不及预期"信号。
 * 供事件驱动策略使用。
 * 核心逻辑:
 * 1. 从 stock_earnings_report 获取最新业绩快报（净利润）
 * 2. 从 stock_consensus_estimate 获取一致预期（净利润均值）
 * 3. 对比: 实际 vs 预期 → 超预期/符合/不及预期
 * 信号输出:
 * - EARN_BEAT: 超预期（实际 > 预期 * 1.05，即超出5%以上）
 * - EARN_MISS: 不及预期（实际 < 预期 * 0.95）
 * - EARN_IN_LINE: 符合预期
 * - NO_DATA: 无数据
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventSignalService {

    private final JdbcTemplate jdbcTemplate;

    /** 超预期阈值：实际 > 预期 * (1 + BEAT_THRESHOLD) */
    private static final double BEAT_THRESHOLD = 0.05;
    /** 不及预期阈值：实际 < 预期 * (1 - MISS_THRESHOLD) */
    private static final double MISS_THRESHOLD = 0.05;

    /**
     * 获取个股的事件信号（超预期/不及预期）
     * @param code 股票代码（纯代码）
     * @return 事件信号
     */
    public EventSignal getEventSignal(String code) {
        EventSignal signal = new EventSignal();
        signal.setCode(code);

        // 1. 获取最新业绩快报
        Map<String, Object> latestReport = getLatestEarningsReport(code);
        if (latestReport == null) {
            signal.setSignalType("NO_DATA");
            signal.setSignalDescription("无业绩快报数据");
            return signal;
        }

        BigDecimal netProfit = toBigDecimal(latestReport.get("net_profit"));
        BigDecimal netProfitYoy = toBigDecimal(latestReport.get("net_profit_yoy"));
        String reportDate = (String) latestReport.get("report_date");
        String announceDate = (String) latestReport.get("announce_date");

        signal.setReportDate(reportDate);
        signal.setAnnounceDate(announceDate);
        signal.setActualNetProfit(netProfit);
        signal.setNetProfitYoy(netProfitYoy);

        // 2. 获取一致预期
        Map<String, Object> consensus = getLatestConsensus(code, reportDate);
        if (consensus == null) {
            // 无一致预期 → 只能用同比增速判断
            signal.setSignalType("EARN_NO_CONSENSUS");
            if (netProfitYoy != null) {
                double yoyGrowth = netProfitYoy.doubleValue();
                if (yoyGrowth > 30) {
                    signal.setBullishScore(0.6);
                    signal.setSignalDescription(String.format("业绩快报: 净利润同比+%.1f%%, 无一致预期但高增长", yoyGrowth));
                } else if (yoyGrowth > 0) {
                    signal.setBullishScore(0.2);
                    signal.setSignalDescription(String.format("业绩快报: 净利润同比+%.1f%%, 无一致预期", yoyGrowth));
                } else {
                    signal.setBullishScore(-0.3);
                    signal.setSignalDescription(String.format("业绩快报: 净利润同比%.1f%%, 无一致预期", yoyGrowth));
                }
            } else {
                signal.setSignalDescription("业绩快报无同比数据");
            }
            return signal;
        }

        BigDecimal estimateAvg = toBigDecimal(consensus.get("estimate_avg"));
        Integer agencyCount = toInteger(consensus.get("agency_count"));
        Integer forecastYear = toInteger(consensus.get("forecast_year"));

        signal.setEstimateAvg(estimateAvg);
        signal.setAgencyCount(agencyCount);
        signal.setForecastYear(forecastYear);

        // 3. 对比实际 vs 预期
        // 注意: 业绩快报是单季度数据，一致预期是年度数据
        // 简化对比: 用年度快报的累计数据 vs 年度一致预期
        if (estimateAvg != null && estimateAvg.compareTo(BigDecimal.ZERO) > 0
                && netProfit != null && netProfit.compareTo(BigDecimal.ZERO) > 0) {
            // 将一致预期从亿元转为元（业绩快报是元）
            BigDecimal estimateInYuan = estimateAvg.multiply(BigDecimal.valueOf(1_0000_0000));
            BigDecimal beatRatio = netProfit.subtract(estimateInYuan)
                    .divide(estimateInYuan, 4, RoundingMode.HALF_UP);

            signal.setBeatRatio(beatRatio.doubleValue());

            if (beatRatio.doubleValue() > BEAT_THRESHOLD) {
                signal.setSignalType("EARN_BEAT");
                signal.setBullishScore(Math.min(1.0, 0.5 + beatRatio.doubleValue()));
                signal.setSignalDescription(String.format(
                        "超预期! 实际净利润%.2f亿 vs 预期%.2f亿, 超出%.1f%% (%d家机构)",
                        netProfit.doubleValue() / 1_0000_0000,
                        estimateAvg.doubleValue(),
                        beatRatio.doubleValue() * 100,
                        agencyCount != null ? agencyCount : 0));
            } else if (beatRatio.doubleValue() < -MISS_THRESHOLD) {
                signal.setSignalType("EARN_MISS");
                signal.setBullishScore(Math.max(-1.0, -0.5 + beatRatio.doubleValue()));
                signal.setSignalDescription(String.format(
                        "不及预期! 实际净利润%.2f亿 vs 预期%.2f亿, 低于%.1f%% (%d家机构)",
                        netProfit.doubleValue() / 1_0000_0000,
                        estimateAvg.doubleValue(),
                        Math.abs(beatRatio.doubleValue()) * 100,
                        agencyCount != null ? agencyCount : 0));
            } else {
                signal.setSignalType("EARN_IN_LINE");
                signal.setBullishScore(0.1);
                signal.setSignalDescription(String.format(
                        "符合预期: 实际净利润%.2f亿 vs 预期%.2f亿, 偏差%.1f%%",
                        netProfit.doubleValue() / 1_0000_0000,
                        estimateAvg.doubleValue(),
                        beatRatio.doubleValue() * 100));
            }
        } else {
            signal.setSignalType("EARN_NO_COMPARE");
            signal.setSignalDescription("无法对比: 数据不足");
        }

        return signal;
    }

    /**
     * 批量获取事件信号（供推荐引擎使用）
     * @param codes 股票代码列表
     * @return code → EventSignal
     */
    public Map<String, EventSignal> batchGetEventSignals(List<String> codes) {
        Map<String, EventSignal> results = new LinkedHashMap<>();
        for (String code : codes) {
            try {
                results.put(code, getEventSignal(code));
            } catch (Exception e) {
                log.debug("[EventSignal] 查询异常 code={}: {}", code, e.getMessage());
                EventSignal signal = new EventSignal();
                signal.setCode(code);
                signal.setSignalType("ERROR");
                results.put(code, signal);
            }
        }
        return results;
    }

    /**
     * 获取最新业绩快报
     */
    private Map<String, Object> getLatestEarningsReport(String code) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT code, report_date, net_profit, net_profit_yoy, eps, announce_date " +
                    "FROM stock_earnings_report WHERE code = ? ORDER BY report_date DESC LIMIT 1",
                    code);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            log.debug("[EventSignal] 业绩快报查询失败 code={}: {}", code, e.getMessage());
            return null;
        }
    }

    /**
     * 获取最新一致预期（对应当前年度）
     */
    private Map<String, Object> getLatestConsensus(String code, String reportDate) {
        try {
            // 根据报告期推算对应年度（如 20250331 → 2025年）
            int year = reportDate != null && reportDate.length() >= 4
                    ? Integer.parseInt(reportDate.substring(0, 4))
                    : LocalDate.now().getYear();

            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT code, forecast_year, agency_count, estimate_avg, estimate_max, estimate_min " +
                    "FROM stock_consensus_estimate WHERE code = ? AND forecast_year >= ? " +
                    "ORDER BY forecast_year ASC LIMIT 1",
                    code, year);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            log.debug("[EventSignal] 一致预期查询失败 code={}: {}", code, e.getMessage());
            return null;
        }
    }

    // ── 工具方法 ──────────────────────────────────────────
    private BigDecimal toBigDecimal(Object val) {
        if (val == null) return null;
        if (val instanceof BigDecimal) return (BigDecimal) val;
        try {
            return new BigDecimal(val.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private Integer toInteger(Object val) {
        if (val == null) return null;
        if (val instanceof Number) return ((Number) val).intValue();
        try {
            return Integer.parseInt(val.toString());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 事件信号DTO
     */
    @Data
    public static class EventSignal {
        private String code;
        /** 信号类型: EARN_BEAT / EARN_MISS / EARN_IN_LINE / EARN_NO_CONSENSUS / NO_DATA / ERROR */
        private String signalType;
        /** 利好分数 -1~1, 1=极利好, -1=极利空 */
        private double bullishScore;
        /** 信号描述 */
        private String signalDescription;
        /** 报告期 */
        private String reportDate;
        /** 公告日期 */
        private String announceDate;
        /** 实际净利润（元） */
        private BigDecimal actualNetProfit;
        /** 净利润同比增速(%) */
        private BigDecimal netProfitYoy;
        /** 一致预期均值（亿元） */
        private BigDecimal estimateAvg;
        /** 预测机构数 */
        private Integer agencyCount;
        /** 预测年度 */
        private Integer forecastYear;
        /** 超预期比例 */
        private Double beatRatio;
    }
}
