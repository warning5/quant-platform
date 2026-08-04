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
public class QuoteDataService {
    private final AnalysisChMapper analysisChMapper;
    private final StockAnalysisMapper stockAnalysisMapper;
    /** CH JDBC template 注入（用于直接 SQL） */
    @Autowired(required = false)
    @Qualifier("clickHouseJdbcTemplate")
    private JdbcTemplate clickHouseJdbcTemplate;
    private final AnalysisCommonService analysisCommon;
    public double[][] fetchKlineData(String code, int days) {
        try {
            List<DailyBarRow> bars = analysisChMapper.selectRecentDailyBars(code, days);
            if (bars == null || bars.size() < 10) return null;
            int n = bars.size();
            double[] open = new double[n], high = new double[n], low = new double[n], close = new double[n], volume = new double[n];
            for (int i = 0; i < n; i++) {
                DailyBarRow bar = bars.get(i);
                open[i] = bar.getOpenPrice() != null ? bar.getOpenPrice().doubleValue() : 0;
                high[i] = bar.getHighPrice() != null ? bar.getHighPrice().doubleValue() : 0;
                low[i] = bar.getLowPrice() != null ? bar.getLowPrice().doubleValue() : 0;
                close[i] = bar.getClosePrice() != null ? bar.getClosePrice().doubleValue() : 0;
                volume[i] = bar.getVolume() != null ? bar.getVolume() : 0;
            }
            return new double[][] { open, high, low, close, volume };
        } catch (Exception e) {
            log.warn("拉取K线数据失败: {} - {}", code, e.getMessage());
            return null;
        }
    }

    public Map<String, double[][]> batchFetchKlineData(int days) {
        Map<String, double[][]> result = new HashMap<>();
        if (clickHouseJdbcTemplate == null) return result;
        try {
            // 多取日历日确保有足够交易日
            int calDays = (int) Math.ceil(days * 7.0 / 5) + 10;
            LocalDate start = LocalDate.now().minusDays(calDays);
            String startStr = start.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));

            // 一次查询全市场，用argMax去重(ReplacingMergeTree)
            String sql = "SELECT code, trade_date, " +
                    "argMax(open_price, update_time) AS open_price, " +
                    "argMax(high_price, update_time) AS high_price, " +
                    "argMax(low_price, update_time) AS low_price, " +
                    "argMax(close_price, update_time) AS close_price, " +
                    "argMax(volume, update_time) AS volume " +
                    "FROM stock.stock_daily " +
                    "WHERE trade_date >= ? " +
                    "GROUP BY code, trade_date " +
                    "ORDER BY code, trade_date";

            List<Map<String, Object>> rows = clickHouseJdbcTemplate.queryForList(sql, startStr);
            log.info("[BatchKline] 查询到 {} 行K线数据 (start={})", rows.size(), startStr);

            // 按code分组组装
            Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                String code = String.valueOf(row.get("code"));
                grouped.computeIfAbsent(code, k -> new ArrayList<>()).add(row);
            }

            for (Map.Entry<String, List<Map<String, Object>>> entry : grouped.entrySet()) {
                List<Map<String, Object>> codeRows = entry.getValue();
                int n = codeRows.size();
                if (n < 30) continue;  // 数据不足跳过
                double[] open = new double[n], high = new double[n], low = new double[n], close = new double[n], volume = new double[n];
                for (int i = 0; i < n; i++) {
                    Map<String, Object> r = codeRows.get(i);
                    open[i] = r.get("open_price") != null ? ((Number) r.get("open_price")).doubleValue() : 0;
                    high[i] = r.get("high_price") != null ? ((Number) r.get("high_price")).doubleValue() : 0;
                    low[i] = r.get("low_price") != null ? ((Number) r.get("low_price")).doubleValue() : 0;
                    close[i] = r.get("close_price") != null ? ((Number) r.get("close_price")).doubleValue() : 0;
                    volume[i] = r.get("volume") != null ? ((Number) r.get("volume")).doubleValue() : 0;
                }
                result.put(entry.getKey(), new double[][] { open, high, low, close, volume });
            }
            log.info("[BatchKline] 组装完成: {} 只股票", result.size());
        } catch (Exception e) {
            log.error("[BatchKline] 批量查询K线数据失败: {}", e.getMessage(), e);
        }
        return result;
    }

    public Map<String, Object> getChanChart(String code) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);

        try {
            // 1. 获取 K 线数据（需要足够长，缠论至少100根才有效）
            List<DailyBarRow> bars = analysisChMapper.selectRecentDailyBars(code, 260);
            if (bars == null || bars.size() < 50) {
                result.put("error", "K线数据不足（需至少50个交易日）");
                return result;
            }

            // 2. 转换为 MarketDailyBar（ChanTheoryCalculator 入参）
            List<MarketDailyBar> marketBars = new ArrayList<>();
            for (DailyBarRow bar : bars) {
                if (bar.getOpenPrice() == null || bar.getClosePrice() == null
                        || bar.getHighPrice() == null || bar.getLowPrice() == null) continue;
                marketBars.add(MarketDailyBar.builder()
                        .symbol(analysisCommon.normalizeCodeForDailyCH(code))
                        .tradeDate(bar.getTradeDate())
                        .open(bar.getOpenPrice())
                        .high(bar.getHighPrice())
                        .low(bar.getLowPrice())
                        .close(bar.getClosePrice())
                        .vol(bar.getVolume() != null ? BigDecimal.valueOf(bar.getVolume()) : null)
                        .amount(bar.getAmount())
                        .turnoverRate(bar.getTurnoverRate())
                        .build());
            }

            // 3. 缠论计算
            ChanTheoryResult chanResult = ChanTheoryCalculator.calculate(marketBars);
            if (chanResult == null) {
                result.put("error", "缠论计算失败");
                return result;
            }

            // 4. 构建 K 线数据（前端 ECharts 格式）
            List<Object> klineData = new ArrayList<>(); // [open, close, low, high, volume]
            List<String> dates = new ArrayList<>();
            for (MarketDailyBar bar : marketBars) {
                klineData.add(List.of(
                        bar.getOpen().doubleValue(),
                        bar.getClose().doubleValue(),
                        bar.getLow().doubleValue(),
                        bar.getHigh().doubleValue(),
                        bar.getVol() != null ? bar.getVol().doubleValue() : 0
                ));
                dates.add(bar.getTradeDate().toString());
            }

            // 5. 笔数据（折线图标记）
            List<Object> penLines = new ArrayList<>();
            if (chanResult.getPens() != null) {
                for (var pen : chanResult.getPens()) {
                    // 笔连接两个分型端点，方向为 UP/DOWN
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("startIndex", pen.getStartIndex());
                    p.put("endIndex", pen.getEndIndex());
                    p.put("startPrice", pen.getStartPrice());
                    p.put("endPrice", pen.getEndPrice());
                    p.put("startDate", pen.getStartDate() != null ? pen.getStartDate().toString() : null);
                    p.put("endDate", pen.getEndDate() != null ? pen.getEndDate().toString() : null);
                    p.put("direction", pen.getDirection() != null ? pen.getDirection().name() : "UNKNOWN");
                    penLines.add(p);
                }
            }

            // 6. 中枢数据（矩形区域）
            List<Object> hubZones = new ArrayList<>();
            if (chanResult.getHubs() != null) {
                for (var hub : chanResult.getHubs()) {
                    Map<String, Object> h = new LinkedHashMap<>();
                    h.put("high", hub.getHigh());
                    h.put("low", hub.getLow());
                    h.put("zz", hub.getZz());
                    h.put("startDate", hub.getStartDate() != null ? hub.getStartDate().toString() : null);
                    h.put("endDate", hub.getEndDate() != null ? hub.getEndDate().toString() : null);
                    h.put("oscillationCount", hub.getOscillationCount());
                    hubZones.add(h);
                }
            }

            // 7. 买卖点标记
            List<Object> buySellMarks = new ArrayList<>();
            if (chanResult.getBuySellPoints() != null) {
                for (var bsp : chanResult.getBuySellPoints()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("index", bsp.getIndex());
                    m.put("type", bsp.getBuySellType() != null ? bsp.getBuySellType().name() : "UNKNOWN");
                    m.put("value", bsp.getBuySellType() != null ? bsp.getBuySellType().getValue() : 0);
                    m.put("isBuy", bsp.getBuySellType() != null && bsp.getBuySellType().isBuy());
                    m.put("date", bsp.getDate() != null ? bsp.getDate().toString() : null);
                    m.put("price", bsp.getPrice());
                    buySellMarks.add(m);
                }
            }

            result.put("dates", dates);
            result.put("klineData", klineData);
            result.put("pens", penLines);
            result.put("hubs", hubZones);
            result.put("buySellPoints", buySellMarks);
            result.put("penCount", chanResult.getPens() != null ? chanResult.getPens().size() : 0);
            result.put("hubCount", chanResult.getHubs() != null ? chanResult.getHubs().size() : 0);
            result.put("bsPointCount", chanResult.getBuySellPoints() != null ? chanResult.getBuySellPoints().size() : 0);
            result.put("barCount", marketBars.size());

        } catch (Exception e) {
            log.error("缠论K线图计算失败: code={}, error={}", code, e.getMessage(), e);
            result.put("error", "计算失败: " + e.getMessage());
        }

        return result;
    }

    public List<Map<String, Object>> getKLine(String code, int days) {
        List<DailyBarRow> bars = analysisChMapper.selectRecentDailyBars(code, days);
        if (bars == null || bars.isEmpty()) return Collections.emptyList();
        List<Map<String, Object>> result = new ArrayList<>();
        for (DailyBarRow bar : bars) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", bar.getTradeDate() != null ? bar.getTradeDate().toString() : "");
            item.put("open", bar.getOpenPrice());
            item.put("high", bar.getHighPrice());
            item.put("low", bar.getLowPrice());
            item.put("close", bar.getClosePrice());
            item.put("volume", bar.getVolume());
            item.put("changePercent", bar.getChangePercent());
            item.put("turnoverRate", bar.getTurnoverRate());
            result.add(item);
        }
        return result;
    }

    public Map<String, Object> getStockPerformance(String code) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);

        try {
            // 1. 获取该股票行业信息
            Map<String, Object> myInfo = stockAnalysisMapper.selectStockInfo(code);
            String industry = myInfo != null ? (String) myInfo.get("industry") : null;
            result.put("industry", industry);

            // 2. 确定当年首个交易日（CH index_daily）
            String yearStartDate = getYearStartDate();
            if (yearStartDate == null) {
                result.put("error", "无法获取年度起始日期");
                return result;
            }
            result.put("yearStartDate", yearStartDate);

            // 3. 获取沪深300 YTD涨幅
            double hs300Ytd = calcIndexYtd("000300", yearStartDate);
            result.put("hs300Ytd", round2(hs300Ytd * 100));

            // 4. 获取个股YTD涨幅（从stock_daily）
            double stockYtd = calcStockYtd(code, yearStartDate);
            if (stockYtd == Double.NaN || stockYtd == Double.MAX_VALUE) {
                result.put("error", "个股数据不足");
                return result;
            }
            result.put("stockYtd", round2(stockYtd * 100));
            result.put("excessReturn", round2((stockYtd - hs300Ytd) * 100));

            // 5. RS Rating：近250日收益排名百分位（全市场）
            int rsRating = calcRsRating(code);
            result.put("rsRating", rsRating);
            result.put("rsRatingLabel", rsRatingToLabel(rsRating));

            // 6. 行业内排名（按20日涨幅）
            if (industry != null && !industry.isBlank()) {
                int indRank = calcIndustryRank(code, industry);
                int indTotal = calcIndustryTotal(industry);
                result.put("industryRank", indRank);
                result.put("industryTotal", indTotal);
                result.put("industryRankLabel", indRank + "/" + indTotal);
                result.put("industryRankPct", indTotal > 0 ? round2(indRank * 100.0 / indTotal) : null);
            }

            return result;
        } catch (Exception e) {
            log.error("个股长周期表现分析失败: code={}, error={}", code, e.getMessage(), e);
            result.put("error", e.getMessage());
            return result;
        }
    }

    public Map<String, Object> getRelativeStrength(String code) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);

        try {
            // 1. 获取该股票行业
            Map<String, Object> myInfo = stockAnalysisMapper.selectStockInfo(code);
            String industry = myInfo != null ? (String) myInfo.get("industry") : null;
            if (industry == null || industry.isBlank()) {
                result.put("error", "未找到行业信息");
                return result;
            }
            result.put("industry", industry);

            // 2. 获取个股近80日日线（多取确保对齐）
            List<DailyBarRow> bars = analysisChMapper.selectRecentDailyBars(code, 85);
            if (bars == null || bars.size() < 30) {
                result.put("error", "个股数据不足（需至少30个交易日）");
                return result;
            }

            // 3. 获取行业等权日收益率序列
            String normalized = analysisCommon.normalizeCodeForDailyCH(code);
            String indReturnSql = """
                SELECT sd.trade_date, AVG(sd.change_percent) / 100 as avg_ret
                FROM stock.stock_daily sd FINAL
                INNER JOIN stock_info si ON si.code = sd.code
                WHERE si.industry = ?
                  AND si.market NOT IN ('BJ','北交所')
                  AND sd.trade_date >= subtractDays(today(), 90)
                GROUP BY sd.trade_date
                ORDER BY sd.trade_date
                """;
            List<Map<String, Object>> indRows = clickHouseJdbcTemplate.query(indReturnSql,
                (rs, rowNum) -> {
                    Map<String, Object> m = new java.util.HashMap<>();
                    m.put("tradeDate", rs.getString("trade_date"));
                    m.put("avgRet", rs.getBigDecimal("avg_ret"));
                    return m;
                }, industry);

            // 构建行业收益 map
            Map<String, Double> indRetMap = new LinkedHashMap<>();
            for (Map<String, Object> r : indRows) {
                String td = (String) r.get("tradeDate");
                BigDecimal avgRet = (BigDecimal) r.get("avgRet");
                if (avgRet != null) indRetMap.put(td, avgRet.doubleValue());
            }

            // 4. 对齐日期并计算累计收益 + RS Ratio
            double stockCumRet = 0;
            double indCumRet = 0;
            List<Map<String, Object>> series = new ArrayList<>();
            List<String> dates = new ArrayList<>();
            List<Double> stockCumList = new ArrayList<>();
            List<Double> indCumList = new ArrayList<>();
            List<Double> rsRatioList = new ArrayList<>();

            for (int i = 1; i < bars.size(); i++) {
                DailyBarRow prev = bars.get(i - 1);
                DailyBarRow curr = bars.get(i);
                if (prev.getClosePrice() == null || curr.getClosePrice() == null
                        || prev.getClosePrice().doubleValue() == 0) continue;
                if (curr.getTradeDate() == null) continue;

                double stockRet = (curr.getClosePrice().doubleValue() - prev.getClosePrice().doubleValue())
                        / prev.getClosePrice().doubleValue();
                String td = curr.getTradeDate().toString();
                Double indRet = indRetMap.get(td);
                if (indRet == null) continue;

                stockCumRet += stockRet;
                indCumRet += indRet;

                // RS Ratio: 个股累计收益 / 行业累计收益（行业为0时取0）
                // 修正：当行业累计为负时，两负数相除会得到错误的大正数，改用超额收益修正
                double rsRatio;
                if (Math.abs(indCumRet) > 0.0001) {
                    if (indCumRet >= 0) {
                        rsRatio = stockCumRet / indCumRet;
                    } else {
                        double excess = stockCumRet - indCumRet;
                        rsRatio = 1.0 + excess / Math.abs(indCumRet);
                    }
                } else {
                    rsRatio = stockCumRet > 0 ? 1.0 : (stockCumRet < 0 ? -1.0 : 0);
                }

                Map<String, Object> day = new LinkedHashMap<>();
                day.put("tradeDate", td);
                day.put("stockRet", Math.round(stockRet * 10000.0) / 100.0);
                day.put("indRet", Math.round(indRet * 10000.0) / 100.0);
                day.put("excessRet", Math.round((stockRet - indRet) * 10000.0) / 100.0);
                day.put("stockCumRet", Math.round(stockCumRet * 10000.0) / 100.0);
                day.put("indCumRet", Math.round(indCumRet * 10000.0) / 100.0);
                day.put("rsRatio", Math.round(rsRatio * 100.0) / 100.0);
                series.add(day);

                dates.add(td);
                stockCumList.add(Math.round(stockCumRet * 10000.0) / 100.0);
                indCumList.add(Math.round(indCumRet * 10000.0) / 100.0);
                rsRatioList.add(Math.round(rsRatio * 100.0) / 100.0);
            }

            result.put("series", series);
            result.put("dates", dates);
            result.put("stockCumRet", stockCumList);
            result.put("indCumRet", indCumList);
            result.put("rsRatio", rsRatioList);
            result.put("totalDays", series.size());

            // 5. 统计汇总
            if (!series.isEmpty()) {
                double latestStockCum = stockCumList.getLast();
                double latestIndCum = indCumList.getLast();
                double latestRs = rsRatioList.getLast();
                result.put("latestStockCumRet", latestStockCum);
                result.put("latestIndCumRet", latestIndCum);
                result.put("latestExcessRet", Math.round((latestStockCum - latestIndCum) * 100.0) / 100.0);
                result.put("latestRsRatio", latestRs);

                // 超额收益为正的天数占比
                long exceedDays = series.stream()
                        .filter(d -> ((Number) d.get("excessRet")).doubleValue() > 0)
                        .count();
                result.put("exceedDays", exceedDays);
                result.put("exceedRatio", Math.round((double) exceedDays / series.size() * 10000) / 100.0);

                // RS Ratio 描述
                String rsDesc;
                if (latestRs > 1.5) rsDesc = "显著强于行业（RS>1.5）";
                else if (latestRs > 1.1) rsDesc = "明显强于行业（RS>1.1）";
                else if (latestRs > 0.9) rsDesc = "与行业同步（RS 0.9~1.1）";
                else if (latestRs > 0.5) rsDesc = "弱于行业（RS 0.5~0.9）";
                else rsDesc = "显著弱于行业（RS<0.5）";
                result.put("rsDesc", rsDesc);
            }

        } catch (Exception e) {
            log.error("相对强弱分析失败: code={}, error={}", code, e.getMessage(), e);
            result.put("error", "计算失败: " + e.getMessage());
        }

        return result;
    }

    public int calcRsRating(String code) {
        String normalized = analysisCommon.normalizeCodeForDailyCH(code);
        try {
            // 近250日个股收益率（用 argMax/argMin 取首日/末日价格，已验证有效）
            String stockSql = String.format("""
                SELECT (argMax(close_price, trade_date) - argMin(close_price, trade_date))
                       / argMin(close_price, trade_date) as ret_250d
                FROM stock.stock_daily FINAL
                WHERE code = '%s' AND trade_date >= subtractDays(today(), 260)
                """, normalized);
            Double stockRet = clickHouseJdbcTemplate.queryForObject(stockSql, Double.class);
            if (stockRet == null) return 0;

            // 全市场近250日收益率分布（分位数）
            String pctSql = String.format("""
                WITH stock_ret AS (
                    SELECT code,
                           (argMax(close_price, trade_date) - argMin(close_price, trade_date))
                           / argMin(close_price, trade_date) as ret
                    FROM stock.stock_daily FINAL
                    WHERE trade_date >= subtractDays(today(), 260)
                    GROUP BY code
                    HAVING min(close_price) > 0 AND count() >= 160
                )
                SELECT
                    countIf(ret > %f) as above_count,
                    count() as total_count
                FROM stock_ret
                """, stockRet);
            Map<String, Object> pctRow = clickHouseJdbcTemplate.queryForMap(pctSql);
            long above = ((Number) pctRow.get("above_count")).longValue();
            long total = ((Number) pctRow.get("total_count")).longValue();

            if (total == 0) return 0;
            // 百分位：above/total = 比该股强的股票比例 → (1 - above/total) * 99 = 排名百分位
            int rating = (int) Math.round((1.0 - (double) above / total) * 99);
            return Math.max(0, Math.min(99, rating));
        } catch (Exception e) {
            log.warn("计算RS Rating失败: code={}, {}", code, e.getMessage());
            return 0;
        }
    }

    public int calcIndustryRank(String code, String industry) {
        String normalized = analysisCommon.normalizeCodeForDailyCH(code);
        try {
            // 先获取该股20日收益率
            String targetSql = String.format("""
                SELECT (argMax(close_price, trade_date) - min(close_price)) / min(close_price) as ret_20d
                FROM stock.stock_daily FINAL
                WHERE code = '%s' AND trade_date >= subtractDays(today(), 25)
                """, normalized);
            Double targetRet = clickHouseJdbcTemplate.queryForObject(targetSql, Double.class);
            if (targetRet == null) return 0;

            // 统计行业内收益率高于该股的股票数量
            String rankSql = """
                WITH latest AS (
                    SELECT code,
                           argMax(close_price, trade_date) as latest_close,
                           min(close_price) as min_close,
                           count() as day_count
                    FROM stock.stock_daily FINAL
                    WHERE trade_date >= subtractDays(today(), 25)
                    GROUP BY code
                    HAVING min(close_price) > 0 AND day_count >= 10
                ),
                ret20 AS (
                    SELECT l.code,
                           (l.latest_close - l.min_close) / l.min_close as ret_20d
                    FROM latest l
                    INNER JOIN stock.stock_info si ON si.code = l.code
                    WHERE si.industry = ?
                      AND si.market NOT IN ('BJ','北交所')
                )
                SELECT countIf(ret_20d > ?) + 1 as rank
                FROM ret20
                """;
            return clickHouseJdbcTemplate.queryForObject(rankSql, Integer.class, industry, targetRet);
        } catch (Exception e) {
            log.warn("计算行业内排名失败: industry={}, {}", industry, e.getMessage());
            return 0;
        }
    }

    public int calcIndustryTotal(String industry) {
        try {
            String sql = """
                SELECT COUNT(DISTINCT sd.code) as cnt
                FROM stock.stock_daily sd FINAL
                INNER JOIN stock_info si ON si.code = sd.code
                WHERE si.industry = ?
                  AND si.market NOT IN ('BJ','北交所')
                  AND sd.trade_date >= subtractDays(today(), 25)
                """;
            return clickHouseJdbcTemplate.queryForObject(sql, Integer.class, industry);
        } catch (Exception e) {
            log.warn("计算行业内总数失败: industry={}, {}", industry, e.getMessage());
            return 0;
        }
    }

    public String rsRatingToLabel(int rating) {
        if (rating >= 90) return "极强（Top 10%）";
        if (rating >= 80) return "很强（Top 20%）";
        if (rating >= 70) return "较强（Top 30%）";
        if (rating >= 50) return "中等偏强";
        if (rating >= 30) return "中等偏弱";
        if (rating >= 20) return "较弱（Bottom 30%）";
        if (rating >= 10) return "很弱（Bottom 20%）";
        return "极弱（Bottom 10%）";
    }

    public String getYearStartDate() {
        int currentYear = java.time.LocalDate.now().getYear();
        try {
            String sql = String.format(
                "SELECT MIN(trade_date) FROM stock.index_daily WHERE code = '000300' AND trade_date >= '%d-01-01'",
                currentYear);
            Object rawDate = clickHouseJdbcTemplate.queryForObject(sql, Object.class);
            if (rawDate == null) return null;
            return rawDate instanceof LocalDate ? ((LocalDate) rawDate).toString() : rawDate.toString();
        } catch (Exception e) {
            log.warn("获取年度起始日期失败: {}", e.getMessage());
            return null;
        }
    }

    public double calcIndexYtd(String indexCode, String yearStartDate) {
        try {
            String sql = String.format("""
                SELECT
                    (max(close_price) - min(close_price)) / min(close_price) as ytd
                FROM stock.index_daily
                WHERE code = '%s' AND trade_date >= '%s'
                """, indexCode, yearStartDate);
            Double ytd = clickHouseJdbcTemplate.queryForObject(sql, Double.class);
            return ytd != null ? ytd : 0.0;
        } catch (Exception e) {
            log.warn("计算指数YTD失败: code={}, {}", indexCode, e.getMessage());
            return 0.0;
        }
    }

    public double calcStockYtd(String code, String yearStartDate) {
        String normalized = analysisCommon.normalizeCodeForDailyCH(code);
        try {
            // 先查首日、末日两个日期
            String dateSql = String.format("""
                SELECT MIN(trade_date) as start_date, MAX(trade_date) as end_date
                FROM stock.stock_daily FINAL
                WHERE code = '%s' AND trade_date >= '%s'
                """, normalized, yearStartDate);
            Map<String, Object> dateRow = clickHouseJdbcTemplate.queryForMap(dateSql);
            // ClickHouse Date 类型返回 LocalDate，需要转 String
            Object startObj = dateRow.get("start_date");
            Object endObj = dateRow.get("end_date");
            String startDate = startObj instanceof LocalDate ? ((LocalDate) startObj).toString() : startObj.toString();
            String endDate = endObj instanceof LocalDate ? ((LocalDate) endObj).toString() : endObj.toString();
            if (startDate == null || endDate == null) return Double.NaN;

            // 查首日收盘价
            BigDecimal startPrice = null;
            String startSql = String.format(
                "SELECT close_price FROM stock.stock_daily FINAL WHERE code = '%s' AND trade_date = '%s' LIMIT 1",
                normalized, startDate);
            List<Map<String, Object>> startRows = clickHouseJdbcTemplate.queryForList(startSql);
            if (!startRows.isEmpty() && startRows.get(0).get("close_price") != null) {
                startPrice = new BigDecimal(startRows.get(0).get("close_price").toString());
            }
            // 查末日收盘价
            BigDecimal endPrice = null;
            String endSql = String.format(
                "SELECT close_price FROM stock.stock_daily FINAL WHERE code = '%s' AND trade_date = '%s' LIMIT 1",
                normalized, endDate);
            List<Map<String, Object>> endRows = clickHouseJdbcTemplate.queryForList(endSql);
            if (!endRows.isEmpty() && endRows.getFirst().get("close_price") != null) {
                endPrice = new BigDecimal(endRows.getFirst().get("close_price").toString());
            }

            if (startPrice == null || endPrice == null || startPrice.doubleValue() == 0) {
                return Double.NaN;
            }
            return endPrice.subtract(startPrice).divide(startPrice, 6, RoundingMode.HALF_UP).doubleValue();
        } catch (Exception e) {
            log.warn("计算个股YTD失败: code={}, {}", code, e.getMessage());
            return Double.NaN;
        }
    }

    public double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    public Map<String, Object> getPeerComparison(String code) {
        Map<String, Object> result = new LinkedHashMap<>();

        // 1. 获取该股票的行业
        Map<String, Object> myInfo = stockAnalysisMapper.selectStockInfo(code);
        String industry = myInfo != null ? (String) myInfo.get("industry") : null;
        if (industry == null || industry.isBlank()) {
            result.put("industry", "未知");
            result.put("peers", Collections.emptyList());
            return result;
        }
        result.put("industry", industry);

        // 2. 获取同行业所有股票的基本信息（PE/PB/市值）
        List<Map<String, Object>> peers = stockAnalysisMapper.selectIndustryPeers(industry);
        if (peers == null || peers.isEmpty()) {
            result.put("peers", Collections.emptyList());
            return result;
        }

        // 3. 补充 CH 最新价格/涨跌幅数据
        for (Map<String, Object> peer : peers) {
            String peerCode = (String) peer.get("code");
            try {
                Map<String, Object> chBar = analysisChMapper.selectLatestDailyBar(peerCode);
                if (chBar != null) {
                    peer.put("changePercent", chBar.get("change_percent"));
                    peer.put("closePrice", chBar.get("close_price"));
                }
            } catch (Exception e) {
                log.debug("获取同业价格失败: {}", peerCode);
            }
        }

        // 4. 排序：按总市值降序（大公司在前）
        peers.sort((a, b) -> {
            BigDecimal ma = a.get("total_market_cap") instanceof BigDecimal ?
                    (BigDecimal) a.get("total_market_cap") : BigDecimal.ZERO;
            BigDecimal mb = b.get("total_market_cap") instanceof BigDecimal ?
                    (BigDecimal) b.get("total_market_cap") : BigDecimal.ZERO;
            return mb.compareTo(ma);
        });

        // 5. 标记当前股票
        result.put("peers", peers);
        result.put("currentCode", code);
        return result;
    }

    public Map<String, Object> getValuationPercentile(String code, int years) {
        Map<String, Object> result = new LinkedHashMap<>();
        String normalized = analysisCommon.normalizeCodeForDailyCH(code);

        try {
            // 从 CH 查询历史 PE/PB 序列（排除0值和null）
            List<BigDecimal> peHistory = new ArrayList<>();
            List<BigDecimal> pbHistory = new ArrayList<>();
            BigDecimal currentPe = null;
            BigDecimal currentPb = null;

            String sql = """
                SELECT pe_ttm, pb FROM stock.stock_daily FINAL
                WHERE code = ?
                  AND trade_date >= subtractYears(today(), ?)
                  AND pe_ttm > 0 AND pe_ttm < 50000
                  AND pb > 0 AND pb < 10000
                ORDER BY trade_date ASC
                """;
            List<Map<String, Object>> rows = clickHouseJdbcTemplate.query(sql,
                (rs, rowNum) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("pe_ttm", rs.getBigDecimal("pe_ttm"));
                    m.put("pb", rs.getBigDecimal("pb"));
                    return m;
                }, normalized, years);

            for (Map<String, Object> row : rows) {
                BigDecimal pe = (BigDecimal) row.get("pe_ttm");
                BigDecimal pb = (BigDecimal) row.get("pb");
                if (pe != null) peHistory.add(pe);
                if (pb != null) pbHistory.add(pb);
            }

            if (!peHistory.isEmpty()) currentPe = peHistory.getLast();
            if (!pbHistory.isEmpty()) currentPb = pbHistory.getLast();

            // 计算百分位：低于当前值的占比
            double pePct = calcPercentile(peHistory, currentPe);
            double pbPct = calcPercentile(pbHistory, currentPb);

            result.put("pePercentile", Math.round(pePct * 10.0) / 10.0);
            result.put("pbPercentile", Math.round(pbPct * 10.0) / 10.0);
            result.put("peCurrent", currentPe);
            result.put("pbCurrent", currentPb);
            result.put("peHistoryCount", peHistory.size());
            result.put("pbHistoryCount", pbHistory.size());
            result.put("years", years);

            // 分位描述
            result.put("peDesc", percentileDesc(pePct));
            result.put("pbDesc", percentileDesc(pbPct));
        } catch (Exception e) {
            log.error("查询估值分位失败: code={}, error={}", code, e.getMessage(), e);
            result.put("error", "查询失败: " + e.getMessage());
        }
        return result;
    }

    public double calcPercentile(List<BigDecimal> history, BigDecimal current) {
        if (history == null || history.isEmpty() || current == null) return 0;
        int belowOrEqual = 0;
        for (BigDecimal val : history) {
            if (val != null && val.compareTo(current) <= 0) belowOrEqual++;
        }
        return (double) belowOrEqual / history.size() * 100.0;
    }

    public static String percentileDesc(double pct) {
        if (pct >= 90) return "极高估（历史90%以上）";
        if (pct >= 75) return "偏高（历史75%~90%）";
        if (pct >= 50) return "中等偏上（50%~75%）";
        if (pct >= 25) return "偏低（25%~50%）";
        if (pct >= 10) return "很低估（10%~25%）";
        return "极低估（历史10%以下）";
    }

    public List<Map<String, Object>> searchStocks(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return stockAnalysisMapper.searchStocks(keyword.trim());
    }

}
