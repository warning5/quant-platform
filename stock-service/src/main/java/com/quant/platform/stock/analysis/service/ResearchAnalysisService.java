package com.quant.platform.stock.analysis.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.factor.engine.chan.ChanTheoryCalculator;
import com.quant.platform.factor.engine.chan.ChanTheoryResult;
import com.quant.platform.market.domain.MarketDailyBar;
import com.quant.platform.stock.analysis.domain.*;
import com.quant.platform.stock.analysis.engine.TradingSignalEngine;
import com.quant.platform.stock.analysis.mapper.AnalysisChMapper;
import com.quant.platform.stock.analysis.mapper.BidAskMapper;
import com.quant.platform.stock.analysis.mapper.NewsMapper;
import com.quant.platform.stock.analysis.mapper.StockAnalysisMapper;
import com.quant.platform.stock.service.ClickHouseStockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
@Slf4j
@Service
@RequiredArgsConstructor
public class ResearchAnalysisService {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private final StockAnalysisMapper stockAnalysisMapper;
    public Map<String, Object> getResearchAnalysis(String code) {
        log.info("获取研报分析: code={}", code);
        Map<String, Object> result = new LinkedHashMap<>();

        // 1. 近期研报列表（含 EPS 预测）
        List<Map<String, Object>> reports = stockAnalysisMapper.selectRecentReportsWithEps(code, 10);
        result.put("recentReports", reports != null ? reports : List.of());

        // 2. 评级趋势（近6个月，按月+评级分组）
        List<Map<String, Object>> rawRatingTrend = stockAnalysisMapper.selectRatingTrend(code);
        result.put("ratingTrend", pivotRatingTrend(rawRatingTrend));

        // 3. 研报数量月度趋势
        List<Map<String, Object>> reportTrend = stockAnalysisMapper.selectReportCountByMonth(code);
        result.put("reportTrend", reportTrend);

        // 4. 覆盖强度
        Map<String, Object> coverage = new LinkedHashMap<>();
        List<Map<String, Object>> institutions = stockAnalysisMapper.selectCoverageInstitutions(code);
        coverage.put("institutionCount", institutions.size());
        coverage.put("institutions", institutions);
        String firstDate = stockAnalysisMapper.selectFirstCoverageDate(code);
        coverage.put("firstCoverageDate", firstDate != null ? firstDate : "");
        // 近期总研报数
        int recent90d = 0;
        if (reports != null) recent90d = reports.size();
        // 从 reportTrend 汇总近6个月总数
        int total6m = 0;
        if (reportTrend != null) {
            for (Map<String, Object> m : reportTrend) {
                Object cnt = m.get("cnt");
                if (cnt instanceof Number) total6m += ((Number) cnt).intValue();
            }
        }
        coverage.put("reportCount6m", total6m);
        coverage.put("reportCount90d", recent90d);
        result.put("coverage", coverage);

        // 5. EPS 一致预期（从 eps_forecast JSON 解析聚合）
        result.put("epsForecast", calcEpsConsensus(reports));

        // 6. 最新评级 + 买入占比
        ResearchSignal signal = stockAnalysisMapper.selectResearchSignal(code);
        Map<String, Object> ratingSummary = new LinkedHashMap<>();
        ratingSummary.put("latestRating", signal != null ? signal.getLatestRating() : null);
        ratingSummary.put("reportCount", signal != null ? signal.getReportCount() : 0);
        // 计算买入+增持占比
        int buyCount = 0;
        int ratedCount = 0;
        if (reports != null) {
            for (Map<String, Object> r : reports) {
                Object rat = r.get("rating");
                if (rat != null && !"".equals(rat.toString())) {
                    ratedCount++;
                    String rt = rat.toString();
                    if ("买入".equals(rt) || "增持".equals(rt)) buyCount++;
                }
            }
        }
        double buyRatio = ratedCount > 0 ? Math.round((double) buyCount / ratedCount * 10000) / 100.0 : 0;
        ratingSummary.put("buyRatio", buyRatio);
        ratingSummary.put("ratedCount", ratedCount);
        // 评级共识描述
        if (buyRatio >= 80) ratingSummary.put("consensusDesc", "强烈看多");
        else if (buyRatio >= 60) ratingSummary.put("consensusDesc", "偏多");
        else if (buyRatio >= 40) ratingSummary.put("consensusDesc", "中性偏多");
        else if (buyRatio >= 20) ratingSummary.put("consensusDesc", "中性偏空");
        else ratingSummary.put("consensusDesc", "偏空");
        result.put("ratingSummary", ratingSummary);

        return result;
    }

    public Map<String, Object> calcEpsConsensus(List<Map<String, Object>> reports) {
        Map<String, Object> consensus = new LinkedHashMap<>();
        if (reports == null || reports.isEmpty()) return consensus;

        // year -> [eps_values] / [pe_values]
        Map<String, List<Double>> epsByYear = new LinkedHashMap<>();
        Map<String, List<Double>> peByYear = new LinkedHashMap<>();

        for (Map<String, Object> r : reports) {
            Object epsRaw = r.get("epsForecast");
            if (epsRaw == null || epsRaw.toString().isBlank()) continue;
            try {
                Map<String, Object> forecast = objectMapper.readValue(epsRaw.toString(),
                        new TypeReference<>() {
                        });
                for (Map.Entry<String, Object> entry : forecast.entrySet()) {
                    String year = entry.getKey();
                    Object val = entry.getValue();
                    if (val instanceof Map) {
                        Map<String, Object> detail = (Map<String, Object>) val;
                        Object epsObj = detail.get("eps");
                        Object peObj = detail.get("pe");
                        if (epsObj instanceof Number) {
                            epsByYear.computeIfAbsent(year, k -> new ArrayList<>())
                                    .add(((Number) epsObj).doubleValue());
                        }
                        if (peObj instanceof Number) {
                            peByYear.computeIfAbsent(year, k -> new ArrayList<>())
                                    .add(((Number) peObj).doubleValue());
                        }
                    }
                }
            } catch (Exception ignored) {
                log.error("[ResearchAnalysisService] 捕获到未处理异常", ignored);
            }
        }

        // 取各年份平均
        for (String year : epsByYear.keySet()) {
            List<Double> vals = epsByYear.get(year);
            double avgEps = vals.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            avgEps = BigDecimal.valueOf(avgEps).setScale(2, RoundingMode.HALF_UP).doubleValue();
            Map<String, Object> yearData = new LinkedHashMap<>();
            yearData.put("year", year);
            yearData.put("avgEps", avgEps);
            yearData.put("sourceCount", vals.size());

            List<Double> peVals = peByYear.get(year);
            if (peVals != null && !peVals.isEmpty()) {
                double avgPe = peVals.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                avgPe = BigDecimal.valueOf(avgPe).setScale(1, RoundingMode.HALF_UP).doubleValue();
                yearData.put("avgPe", avgPe);
            }
            consensus.put(year, yearData);
        }

        return consensus;
    }

    public List<Map<String, Object>> pivotRatingTrend(List<Map<String, Object>> raw) {
        Map<String, Map<String, Object>> byMonth = new LinkedHashMap<>();
        for (Map<String, Object> row : raw) {
            String month = row.get("month") != null ? row.get("month").toString() : "";
            String rating = row.get("rating") != null ? row.get("rating").toString() : "无";
            Number cnt = (Number) row.get("cnt");

            Map<String, Object> monthData = byMonth.computeIfAbsent(month, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("month", k);
                return m;
            });
            monthData.merge(rating, cnt != null ? cnt.intValue() : 0,
                    (oldVal, newVal) -> ((Number) oldVal).intValue() + ((Number) newVal).intValue());
        }
        return new ArrayList<>(byMonth.values());
    }

    public Map<String, Object> getShareholderStructure(String code) {
        Map<String, Object> result = new HashMap<>();

        try {
            // 1. 股东人数历史（最近8期）
            List<Map<String, Object>> history = stockAnalysisMapper.selectShareholderHistory(code);
            result.put("shareholderHistory", history);

            // 2. 基金持仓明细（最新期前10）
            List<Map<String, Object>> fundHolders = stockAnalysisMapper.selectFundHolderTop(code);
            result.put("fundHolders", fundHolders);

            // 3. 筹码集中度信号
            if (history != null && !history.isEmpty()) {
                BigDecimal latestChange = (BigDecimal) history.get(0).get("change_pct");
                Long latestCount = (Long) history.get(0).get("holder_count");
                result.put("latestHolderCount", latestCount);
                result.put("changePct", latestChange);

                // 集中度判断
                String concentration;
                if (latestChange == null) concentration = "未知";
                else if (latestChange.doubleValue() < -10) concentration = "高度集中（筹码快速收敛）";
                else if (latestChange.doubleValue() < -3) concentration = "趋于集中（散户离场）";
                else if (latestChange.doubleValue() > 5) concentration = "趋于分散（新散户进场）";
                else if (latestChange.doubleValue() > 10) concentration = "高度分散（筹码大幅扩散）";
                else concentration = "相对稳定";
                result.put("concentration", concentration);
            }

            // 4. 基金持仓汇总
            BigDecimal fundRatio = stockAnalysisMapper.selectFundHolderRatio(code);
            result.put("totalFundRatio", fundRatio);
        } catch (Exception e) {
            log.warn("股东结构查询失败: code={}, error={}", code, e.getMessage());
            result.put("error", e.getMessage());
        }

        return result;
    }

    public int calcResearchScore(String rating) {
        if (rating == null || rating.isEmpty()) return 0;
        return switch (rating) {
            case "买入" -> 5;
            case "增持", "推荐", "强烈推荐" -> 3;
            case "中性", "持有" -> 1;
            default -> 0;
        };
    }

}
