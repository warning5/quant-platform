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
public class SectorAnalysisService {
    /** 热门板块定义（名称 + 图标色系） */
    private static final List<String> HOT_CONCEPTS = List.of(
        "人工智能", "半导体概念", "国产芯片", "算力/AI",
        "储能概念", "光伏概念", "新能源车", "锂电池概念", "新能源",
        "机器人概念", "人形机器人",
        "军工", "低空经济", "医疗器械概念", "创新药",
        "消费电子概念", "信创", "数字经济", "氢能源", "充电桩"
    );
    private final AnalysisChMapper analysisChMapper;
    private final StockAnalysisMapper stockAnalysisMapper;
    private final ClickHouseStockService clickHouseStockService;
    /** CH JDBC template 注入（用于直接 SQL） */
    @Autowired(required = false)
    @Qualifier("clickHouseJdbcTemplate")
    private JdbcTemplate clickHouseJdbcTemplate;
    private final AnalysisCommonService analysisCommon;
    public Map<String, Object> getSectorRanking() {
        Map<String, Object> result = new LinkedHashMap<>();

        // 1. 行业排行（纯 CH 查询：stock_info + stock_daily 都在 CH）
        // ⚠️ Spring JdbcTemplate 内部走 PreparedStatement，CH JDBC 返回空 → 改用 clickHouseStockService（Statement）
        List<Map<String, Object>> industryList = Collections.emptyList();
        String latestTradeDate = null;
        try {
            latestTradeDate = clickHouseStockService.queryForString(
                    "SELECT MAX(trade_date) FROM stock.stock_daily FINAL");
        } catch (Exception e) {
            log.error("获取最新交易日失败: {}", e.getMessage());
        }

        if (latestTradeDate != null) {
            try {
                String sql = String.format("""
                    SELECT
                        si.industry,
                        COUNT(*) as stockCount,
                        AVG(sd.change_percent) as avgChangePct,
                        median(sd.pe_ttm) as medianPe,
                        median(sd.pb) as medianPb
                    FROM stock.stock_info si
                      INNER JOIN (
                        SELECT code, change_percent, pe_ttm, pb FROM stock.stock_daily FINAL
                        WHERE trade_date = '%s'
                      ) sd ON sd.code = si.code
                    WHERE si.industry IS NOT NULL AND si.industry != ''
                      AND si.market NOT IN ('BJ','北交所')
                    GROUP BY si.industry
                    ORDER BY avgChangePct DESC
                    """, latestTradeDate);
                List<Map<String, Object>> rows = clickHouseStockService.queryForList(sql);
                industryList = new ArrayList<>();
                for (Map<String, Object> row : rows) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("industry", row.get("industry"));
                    // ClickHouse JDBC 返回的列名保持别名大小写（stockCount/avgChangePct...），须按原 key 取
                    m.put("stockCount", row.get("stockCount"));
                    Object avgChg = row.get("avgChangePct");
                    m.put("avgChangePct", avgChg instanceof Number ?
                            BigDecimal.valueOf(((Number) avgChg).doubleValue()).setScale(2, RoundingMode.HALF_UP) : null);
                    Object medPe = row.get("medianPe");
                    m.put("medianPe", medPe instanceof Number ?
                            BigDecimal.valueOf(((Number) medPe).doubleValue()).setScale(1, RoundingMode.HALF_UP) : null);
                    Object medPb = row.get("medianPb");
                    m.put("medianPb", medPb instanceof Number ?
                            BigDecimal.valueOf(((Number) medPb).doubleValue()).setScale(2, RoundingMode.HALF_UP) : null);
                    industryList.add(m);
                }
            } catch (Exception e) {
                log.error("查询行业排行失败: error={}", e.getMessage(), e);
            }
        }

        // 在结果中返回最新交易日期（stock_concept 仅在 MySQL，但可一次 JOIN CH 聚合）
        List<Map<String, Object>> conceptList = Collections.emptyList();
        try {
            // 先获取最新交易日期
            String maxDateSql = "SELECT MAX(trade_date) as maxDate FROM stock.stock_daily FINAL";
            String maxDate = clickHouseJdbcTemplate.queryForObject(maxDateSql, String.class);

            if (maxDate != null) {
                // 从 MySQL 取概念-股票映射
                List<Map<String, Object>> concepts = stockAnalysisMapper.selectAllConcepts();
                if (concepts != null && !concepts.isEmpty()) {
                    // 构建 concept→codes 映射（一只股票可属于多个概念，不能去重）
                    Map<String, Set<String>> conceptToCodes = new LinkedHashMap<>();
                    Set<String> allCodes = new LinkedHashSet<>();
                    for (Map<String, Object> c : concepts) {
                        String cname = (String) c.get("conceptName");
                        String ccode = (String) c.get("code");
                        conceptToCodes.computeIfAbsent(cname, k -> new LinkedHashSet<>()).add(ccode);
                        allCodes.add(ccode);
                    }

                    // 一次查出所有涉及股票的涨跌幅（限最新日期）
                    List<Map<String, Object>> conceptListRaw = new ArrayList<>();

                    // CH IN 子句有长度限制，分批查询（每批500）
                    List<String> codeList = new ArrayList<>(allCodes);
                    // 安全校验：确保所有股票代码格式合法（6位纯数字），防止 SQL 注入
                    codeList = codeList.stream()
                        .filter(c -> c != null && c.matches("\\d{6}"))
                        .collect(Collectors.toList());
                    Map<String, Map<String, Object>> codeChgMap = new HashMap<>();
                    for (int i = 0; i < codeList.size(); i += 500) {
                        List<String> batch = codeList.subList(i, Math.min(i + 500, codeList.size()));
                        String inClause = String.join("','", batch);
                        // 与下钻查询 getConceptStocks() 统一口径：必须同时存在于 stock_info + stock_daily
                        String batchSql = String.format("""
                            SELECT sd.code, sd.change_percent as chg, sd.pe_ttm, sd.pb
                            FROM stock.stock_daily sd FINAL
                            INNER JOIN stock.stock_info si ON si.code = sd.code
                            WHERE sd.code IN ('%s') AND sd.trade_date = '%s'
                            """, inClause, maxDate);
                        List<Map<String, Object>> rows = clickHouseJdbcTemplate.query(batchSql,
                            (rs, rowNum) -> {
                                Map<String, Object> m = new HashMap<>();
                                m.put("code", rs.getString("code"));
                                m.put("chg", rs.getObject("chg"));
                                m.put("pe", rs.getObject("pe_ttm"));
                                m.put("pb", rs.getObject("pb"));
                                return m;
                            });
                        for (Map<String, Object> r : rows) {
                            codeChgMap.put((String) r.get("code"), r);
                        }
                    }

                    // 按概念聚合（一只股票可属于多个概念，每个概念都要计入）
                    Map<String, List<Double>> conceptChgs = new LinkedHashMap<>();
                    Map<String, List<Double>> conceptPes = new LinkedHashMap<>();
                    Map<String, List<Double>> conceptPbs = new LinkedHashMap<>();
                    Map<String, Integer> conceptCounts = new LinkedHashMap<>();
                    for (Map.Entry<String, Set<String>> e : conceptToCodes.entrySet()) {
                        String cname = e.getKey();
                        Set<String> codes = e.getValue();
                        for (String code : codes) {
                            Map<String, Object> chgData = codeChgMap.get(code);
                            if (chgData != null) {
                                conceptChgs.computeIfAbsent(cname, k -> new ArrayList<>());
                                conceptPes.computeIfAbsent(cname, k -> new ArrayList<>());
                                conceptPbs.computeIfAbsent(cname, k -> new ArrayList<>());
                                Object chg = chgData.get("chg");
                                if (chg instanceof Number) conceptChgs.get(cname).add(((Number) chg).doubleValue());
                                Object pe = chgData.get("pe");
                                if (pe instanceof Number && ((Number) pe).doubleValue() > 0) conceptPes.get(cname).add(((Number) pe).doubleValue());
                                Object pb = chgData.get("pb");
                                if (pb instanceof Number && ((Number) pb).doubleValue() > 0) conceptPbs.get(cname).add(((Number) pb).doubleValue());
                                conceptCounts.merge(cname, 1, Integer::sum);
                            }
                        }
                    }

                    // 构建结果
                    conceptList = new ArrayList<>();
                    for (String cname : conceptChgs.keySet()) {
                        List<Double> chgs = conceptChgs.get(cname);
                        if (chgs.isEmpty()) continue;
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("conceptName", cname);
                        row.put("stockCount", conceptCounts.getOrDefault(cname, chgs.size()));
                        double avgChg = chgs.stream().mapToDouble(v -> v).average().orElse(0);
                        row.put("avgChangePct", BigDecimal.valueOf(avgChg).setScale(2, RoundingMode.HALF_UP));
                        // 简易中位数（Java排序取中间值）
                        List<Double> pes = conceptPes.getOrDefault(cname, Collections.emptyList());
                        List<Double> pbs = conceptPbs.getOrDefault(cname, Collections.emptyList());
                        row.put("medianPe", pes.isEmpty() ? null : BigDecimal.valueOf(analysisCommon.median(pes)).setScale(1, RoundingMode.HALF_UP));
                        row.put("medianPb", pbs.isEmpty() ? null : BigDecimal.valueOf(analysisCommon.median(pbs)).setScale(2, RoundingMode.HALF_UP));
                        conceptList.add(row);
                    }
                    conceptList.sort((a, b) -> {
                        BigDecimal ma = a.get("avgChangePct") instanceof BigDecimal ? (BigDecimal) a.get("avgChangePct") : BigDecimal.ZERO;
                        BigDecimal mb = b.get("avgChangePct") instanceof BigDecimal ? (BigDecimal) b.get("avgChangePct") : BigDecimal.ZERO;
                        return mb.compareTo(ma);
                    });
                }
            }
        } catch (Exception e) {
            log.error("查询概念排行失败: error={}", e.getMessage(), e);
        }

        result.put("industry", industryList != null ? industryList : Collections.emptyList());
        result.put("concept", conceptList);
        result.put("tradeDate", latestTradeDate);
        return result;
    }

    public List<Map<String, Object>> getConceptStocks(String conceptName, String sortBy, String sortOrder) {
        Set<String> allowedSort = Set.of("changePercent", "peTtm", "pb", "totalMarketCap", "turnoverRate");
        if (!allowedSort.contains(sortBy)) sortBy = "changePercent";
        String order = "desc".equalsIgnoreCase(sortOrder) ? "DESC" : "ASC";

        // 1. MySQL获取概念成分股代码列表
        List<Map<String, Object>> conceptRows = stockAnalysisMapper.selectAllConcepts();
        Set<String> codes = new TreeSet<>();
        for (Map<String, Object> r : conceptRows) {
            String cname = (String) r.get("conceptName");
            if (conceptName.equals(cname)) {
                codes.add((String) r.get("code"));
            }
        }
        if (codes.isEmpty()) return Collections.emptyList();

        // 2. CH批量查询行情（校验股票代码格式，防止 SQL 注入）
        List<String> validCodes = codes.stream().filter(c -> c.matches("\\d{6}")).collect(Collectors.toList());
        if (validCodes.isEmpty()) return Collections.emptyList();
        String inClause = String.join("','", validCodes);
        String chSortCol = switch (sortBy) {
            case "changePercent" -> "sd.change_percent";
            case "peTtm" -> "sd.pe_ttm";
            case "pb" -> "sd.pb";
            case "totalMarketCap" -> "si.total_market_cap";
            case "turnoverRate" -> "sd.turnover_rate";
            default -> "sd.change_percent";
        };

        String sql = String.format("""
            SELECT si.code, si.name,
                   si.total_market_cap as totalMarketCap,
                   sd.close_price as closePrice,
                   sd.change_percent as changePercent,
                   sd.pe_ttm as peTtm,
                   sd.pb as pb,
                   sd.turnover_rate as turnoverRate
            FROM stock_info si
              INNER JOIN (
                SELECT code, close_price, change_percent, pe_ttm, pb, turnover_rate
                FROM stock.stock_daily FINAL
                WHERE trade_date = (SELECT MAX(trade_date) FROM stock.stock_daily FINAL)
              ) sd ON sd.code = si.code
            WHERE si.code IN ('%s')
            ORDER BY %s %s
            LIMIT 500
            """, inClause, chSortCol, order);

        return clickHouseJdbcTemplate.query(sql,
            (rs, rowNum) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("code", rs.getString("code"));
                m.put("name", rs.getString("name"));
                m.put("totalMarketCap", rs.getBigDecimal("totalMarketCap"));
                m.put("closePrice", rs.getBigDecimal("closePrice"));
                m.put("changePercent", rs.getBigDecimal("changePercent"));
                m.put("peTtm", rs.getBigDecimal("peTtm"));
                m.put("pb", rs.getBigDecimal("pb"));
                m.put("turnoverRate", rs.getBigDecimal("turnoverRate"));
                return m;
            });
    }

    public List<Map<String, Object>> getIndustryStocks(String industry, String sortBy, String sortOrder) {
        // 白名单排序字段
        Set<String> allowedSort = Set.of("changePercent", "peTtm", "pb", "totalMarketCap", "turnoverRate");
        if (!allowedSort.contains(sortBy)) sortBy = "changePercent";
        String order = "desc".equalsIgnoreCase(sortOrder) ? "DESC" : "ASC";

        // CH字段映射
        String chSortCol = switch (sortBy) {
            case "changePercent" -> "sd.change_percent";
            case "peTtm" -> "sd.pe_ttm";
            case "pb" -> "sd.pb";
            case "totalMarketCap" -> "si.total_market_cap";
            case "turnoverRate" -> "sd.turnover_rate";
            default -> "sd.change_percent";
        };

        String sql = String.format("""
            SELECT si.code, si.name, si.industry,
                   si.total_market_cap as totalMarketCap,
                   sd.close_price as closePrice,
                   sd.change_percent as changePercent,
                   sd.pe_ttm as peTtm,
                   sd.pb as pb,
                   sd.turnover_rate as turnoverRate
            FROM stock_info si
              INNER JOIN (
                SELECT code, close_price, change_percent, pe_ttm, pb, turnover_rate
                FROM stock.stock_daily FINAL
                WHERE trade_date = (SELECT MAX(trade_date) FROM stock.stock_daily FINAL)
              ) sd ON sd.code = si.code
            WHERE si.industry = ?
              AND si.market NOT IN ('BJ','北交所')
            ORDER BY %s %s
            LIMIT 500
            """, chSortCol, order);

        return clickHouseJdbcTemplate.query(sql,
            (rs, rowNum) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("code", rs.getString("code"));
                m.put("name", rs.getString("name"));
                m.put("industry", rs.getString("industry"));
                m.put("totalMarketCap", rs.getBigDecimal("totalMarketCap"));
                m.put("closePrice", rs.getBigDecimal("closePrice"));
                m.put("changePercent", rs.getBigDecimal("changePercent"));
                m.put("peTtm", rs.getBigDecimal("peTtm"));
                m.put("pb", rs.getBigDecimal("pb"));
                m.put("turnoverRate", rs.getBigDecimal("turnoverRate"));
                return m;
            }, industry);
    }

    public Map<String, Object> getIndustryCorrelation(String code) {
        Map<String, Object> result = new LinkedHashMap<>();
        String normalized = analysisCommon.normalizeCodeForDailyCH(code);

        // 1. 获取该股票所属行业
        Map<String, Object> myInfo = stockAnalysisMapper.selectStockInfo(code);
        String industry = myInfo != null ? (String) myInfo.get("industry") : null;
        if (industry == null || industry.isBlank()) {
            result.put("error", "未找到行业信息");
            return result;
        }
        result.put("industry", industry);

        // 2. 获取近60日个股收益率序列
        List<DailyBarRow> bars = analysisChMapper.selectRecentDailyBars(code, 65);
        if (bars == null || bars.size() < 20) {
            result.put("error", "个股数据不足（需至少20日）");
            return result;
        }

        // 计算日收益率
        List<Double> stockReturns = new ArrayList<>();
        for (int i = 1; i < bars.size(); i++) {
            if (bars.get(i - 1).getClosePrice() != null && bars.get(i).getClosePrice() != null) {
                double prev = bars.get(i - 1).getClosePrice().doubleValue();
                double curr = bars.get(i).getClosePrice().doubleValue();
                if (prev > 0) stockReturns.add((curr - prev) / prev);
            }
        }
        if (stockReturns.size() < 20) {
            result.put("error", "收益率数据不足");
            return result;
        }

        // 3. 获取同行业所有股票近60日收益率 → 计算行业等权平均收益率
        try {
            String industryReturnSql = """
                SELECT trade_date, AVG(change_percent) / 100 as avg_ret
                FROM stock.stock_daily sd FINAL
                INNER JOIN stock_info si ON si.code = sd.code
                WHERE si.industry = ?
                  AND si.market NOT IN ('BJ','北交所')
                  AND sd.trade_date >= subtractDays(today(), 70)
                GROUP BY trade_date
                ORDER BY trade_date
                """;
            List<Map<String, Object>> indRows = clickHouseJdbcTemplate.query(industryReturnSql,
                (rs, rowNum) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("tradeDate", rs.getDate("trade_date").toLocalDate());
                    m.put("avgRet", rs.getBigDecimal("avg_ret"));
                    return m;
                }, industry);

            // 构建 industry return map
            Map<java.time.LocalDate, Double> indRetMap = new LinkedHashMap<>();
            for (Map<String, Object> r : indRows) {
                java.time.LocalDate td = (java.time.LocalDate) r.get("tradeDate");
                BigDecimal avgRet = (BigDecimal) r.get("avgRet");
                if (avgRet != null) indRetMap.put(td, avgRet.doubleValue());
            }

            // 对齐日期序列
            List<Double> alignedStock = new ArrayList<>();
            List<Double> alignedInd = new ArrayList<>();
            List<java.time.LocalDate> alignedDates = new ArrayList<>();  // 同步保存日期
            for (int i = 1; i < bars.size(); i++) {
                java.time.LocalDate td = bars.get(i).getTradeDate();
                if (td != null && indRetMap.containsKey(td) && i - 1 < stockReturns.size()) {
                    alignedStock.add(stockReturns.get(i - 1));
                    alignedInd.add(indRetMap.get(td));
                    alignedDates.add(td);  // 保存对应日期
                }
            }

            if (alignedStock.size() >= 20) {
                // 计算 Beta = Cov(stock, industry) / Var(industry)
                double[] betaCorr = calcBetaAndCorrelation(alignedStock, alignedInd);
                double beta = betaCorr[0];
                double corr = betaCorr[1];

                result.put("beta", Math.round(beta * 100.0) / 100.0);
                result.put("correlation", Math.round(corr * 100.0) / 100.0);
                result.put("sampleDays", alignedStock.size());

                // Beta 解读
                String betaDesc;
                if (beta > 1.5) betaDesc = "高Beta（>1.5），弹性大，涨跌幅放大";
                else if (beta > 1.0) betaDesc = "中高Beta，波动略大于行业";
                else if (beta > 0.7) betaDesc = "中Beta，与行业基本同步";
                else if (beta > 0.3) betaDesc = "低Beta，波动小于行业";
                else betaDesc = "极低Beta，独立行情特征";
                result.put("betaDesc", betaDesc);

                // 相关系数解读
                String corrDesc;
                if (corr > 0.7) corrDesc = "高度联动，与行业同涨同跌";
                else if (corr > 0.4) corrDesc = "中度联动，受行业影响较大";
                else if (corr > 0.2) corrDesc = "弱联动，有一定独立性";
                else corrDesc = "低联动，走势独立于行业";
                result.put("corrDesc", corrDesc);

                // 4. 近5日联动分析
                List<Map<String, Object>> recentAlign = new ArrayList<>();
                int n = alignedStock.size();
                for (int i = Math.max(0, n - 5); i < n; i++) {
                    Map<String, Object> day = new LinkedHashMap<>();
                    day.put("dayIndex", i - Math.max(0, n - 5) + 1);
                    // 使用对齐时保存的日期（避免 bars 索引错位）
                    if (i < alignedDates.size()) {
                        day.put("tradeDate", alignedDates.get(i).toString());
                    }
                    day.put("stockRet", Math.round(alignedStock.get(i) * 10000.0) / 100.0);
                    day.put("industryRet", Math.round(alignedInd.get(i) * 10000.0) / 100.0);
                    // 超额收益
                    day.put("excessRet", Math.round((alignedStock.get(i) - alignedInd.get(i)) * 10000.0) / 100.0);
                    recentAlign.add(day);
                }
                result.put("recentAlignment", recentAlign);
            } else {
                result.put("error", "对齐数据不足（需至少20日）");
            }
        } catch (Exception e) {
            log.error("行业关联分析失败: code={}, error={}", code, e.getMessage(), e);
            result.put("error", "计算失败: " + e.getMessage());
        }

        // 5. 同行业近期涨跌分布
        try {
            String distSql = """
                SELECT
                    COUNT(*) as total,
                    countIf(change_percent > 0) as upCount,
                    countIf(change_percent < 0) as downCount,
                    countIf(change_percent = 0) as flatCount,
                    AVG(change_percent) as avgChange
                FROM stock_info si
                  INNER JOIN (
                    SELECT code, change_percent FROM stock.stock_daily FINAL
                    WHERE trade_date = (SELECT MAX(trade_date) FROM stock.stock_daily FINAL)
                  ) sd ON sd.code = si.code
                WHERE si.industry = ?
                  AND si.market NOT IN ('BJ','北交所')
                """;
            Map<String, Object> dist = clickHouseJdbcTemplate.queryForMap(distSql, industry);
            result.put("industryDist", dist);
        } catch (Exception e) {
            log.debug("行业分布查询失败: {}", e.getMessage());
        }

        return result;
    }

    public double[] calcBetaAndCorrelation(List<Double> x, List<Double> y) {
        int n = x.size();
        double meanX = x.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double meanY = y.stream().mapToDouble(Double::doubleValue).average().orElse(0);

        double covXY = 0, varX = 0, varY = 0;
        for (int i = 0; i < n; i++) {
            double dx = x.get(i) - meanX;
            double dy = y.get(i) - meanY;
            covXY += dx * dy;
            varX += dx * dx;
            varY += dy * dy;
        }
        covXY /= (n - 1);
        varX /= (n - 1);
        varY /= (n - 1);

        double beta = varY > 0 ? covXY / varY : 0;
        double corr = (varX > 0 && varY > 0) ? covXY / Math.sqrt(varX * varY) : 0;

        return new double[]{beta, corr};
    }

    public Map<String, Object> getHotSectors() {
        List<Map<String, Object>> results = new ArrayList<>();

        // 从 MySQL 获取概念→股票映射
        List<Map<String, Object>> concepts = stockAnalysisMapper.selectAllConcepts();
        Map<String, Set<String>> conceptCodes = new LinkedHashMap<>();
        for (Map<String, Object> c : concepts) {
            String cname = (String) c.get("conceptName");
            String ccode = (String) c.get("code");
            conceptCodes.computeIfAbsent(cname, k -> new TreeSet<>()).add(ccode);
        }

        // 最新交易日期
        String latestDate = analysisCommon.getLatestTradeDate();

        for (String conceptName : HOT_CONCEPTS) {
            Set<String> codes = conceptCodes.get(conceptName);
            if (codes == null || codes.isEmpty()) continue;

            try {
                Map<String, Object> sector = new LinkedHashMap<>();
                sector.put("conceptName", conceptName);
                // stockCount 延后设置：先放原始数量，查完 CH 后更新为实际有行情的数量
                sector.put("stockCount", 0);

                // 批量查 CH：涨跌幅/PE/PB/市值
                String inClause = codes.stream()
                    .filter(s -> s.matches("\\d{6}"))
                    .collect(Collectors.joining("','", "'", "'"));
                if (inClause.length() <= 2) continue;

                String sql = String.format("""
                    SELECT
                        COUNT(*) as stockCount,
                        AVG(sd.change_percent) as avgChange,
                        median(sd.pe_ttm) as medianPe,
                        median(sd.pb) as medianPb,
                        SUM(si.total_market_cap) as totalCap
                    FROM stock.stock_daily sd FINAL
                    JOIN stock.stock_info si ON sd.code = si.code
                    WHERE sd.code IN (%s) AND sd.trade_date = '%s'
                    """, inClause, latestDate);

                clickHouseJdbcTemplate.query(sql, (rs) -> {
                    Object cnt = rs.getObject(1);
                    sector.put("stockCount", cnt instanceof Number ? ((Number) cnt).intValue() : 0);
                    Object avgChg = rs.getObject(2);
                    sector.put("avgChange", avgChg instanceof Number ?
                        BigDecimal.valueOf(((Number) avgChg).doubleValue()).setScale(2, RoundingMode.HALF_UP) : null);
                    Object medPe = rs.getObject(3);
                    sector.put("medianPe", medPe instanceof Number ?
                        BigDecimal.valueOf(((Number) medPe).doubleValue()).setScale(1, RoundingMode.HALF_UP) : null);
                    Object medPb = rs.getObject(4);
                    sector.put("medianPb", medPb instanceof Number ?
                        BigDecimal.valueOf(((Number) medPb).doubleValue()).setScale(2, RoundingMode.HALF_UP) : null);
                    Object totalCap = rs.getObject(5);
                    sector.put("totalMarketCap", totalCap instanceof Number ?
                        BigDecimal.valueOf(((Number) totalCap).doubleValue()).setScale(0, RoundingMode.HALF_UP) : null);
                });

                // 涨幅前3龙头
                String topSql = String.format("""
                    SELECT sd.code, si.name, sd.change_percent as chg, si.total_market_cap as cap
                    FROM stock.stock_daily sd FINAL
                    JOIN stock.stock_info si ON sd.code = si.code
                    WHERE sd.code IN (%s) AND sd.trade_date = '%s'
                    ORDER BY sd.change_percent DESC LIMIT 3
                    """, inClause, latestDate);
                List<Map<String, Object>> topStocks = clickHouseJdbcTemplate.query(topSql,
                    (rs, rowNum) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("code", rs.getString(1));
                        m.put("name", rs.getString(2));
                        m.put("change", rs.getBigDecimal(3));
                        return m;
                    });
                sector.put("topStocks", topStocks);

                results.add(sector);
            } catch (Exception e) {
                log.warn("热门板块聚合跳过 {}: {}", conceptName, e.getMessage());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tradeDate", latestDate);
        result.put("sectors", results);
        return result;
    }

    public Map<String, Object> getHotSectorDetail(String conceptName) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conceptName", conceptName);

        // 从 MySQL 获取概念下股票
        List<Map<String, Object>> concepts = stockAnalysisMapper.selectAllConcepts();
        Set<String> codes = new TreeSet<>();
        for (Map<String, Object> c : concepts) {
            if (conceptName.equals(c.get("conceptName"))) {
                String code = (String) c.get("code");
                if (code != null && code.matches("\\d{6}")) codes.add(code);
            }
        }
        result.put("stockCount", codes.size());  // 初始值，下面查完后更新为实际有行情的数量
        if (codes.isEmpty()) {
            result.put("error", "无成分股数据");
            return result;
        }

        String latestDate = analysisCommon.getLatestTradeDate();
        String inClause = String.join("','", codes);

        // 成分股列表（按涨跌幅排序）
        String stockSql = String.format("""
            SELECT sd.code, si.name, sd.close_price, sd.change_percent,
                   sd.pe_ttm, sd.pb, sd.turnover_rate, si.total_market_cap,
                   sd.volume
            FROM stock.stock_daily sd FINAL
            JOIN stock.stock_info si ON sd.code = si.code
            WHERE sd.code IN ('%s') AND sd.trade_date = '%s'
            ORDER BY sd.change_percent DESC
            """, inClause, latestDate);
        List<Map<String, Object>> stocks = clickHouseJdbcTemplate.query(stockSql,
            (rs, rowNum) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("code", rs.getString(1));
                m.put("name", rs.getString(2));
                m.put("closePrice", rs.getBigDecimal(3));
                m.put("changePercent", rs.getBigDecimal(4));
                m.put("peTtm", rs.getBigDecimal(5));
                m.put("pb", rs.getBigDecimal(6));
                m.put("turnoverRate", rs.getBigDecimal(7));
                Object cap = rs.getObject(8);
                m.put("totalMarketCap", cap instanceof Number ?
                    BigDecimal.valueOf(((Number) cap).doubleValue()).setScale(0, RoundingMode.HALF_UP) : null);
                return m;
            });
        result.put("stocks", stocks);
        result.put("stockCount", stocks.size());  // 更新为实际查询到的有行情且未退市的股票数

        // 概览统计
        if (!stocks.isEmpty()) {
            double avgChg = stocks.stream()
                .filter(s -> s.get("changePercent") != null)
                .mapToDouble(s -> ((BigDecimal) s.get("changePercent")).doubleValue())
                .average().orElse(0);
            long upCount = stocks.stream()
                .filter(s -> s.get("changePercent") != null && ((BigDecimal) s.get("changePercent")).doubleValue() > 0)
                .count();
            result.put("avgChange", BigDecimal.valueOf(avgChg).setScale(2, RoundingMode.HALF_UP));
            result.put("upCount", upCount);
            result.put("downCount", stocks.size() - upCount);
        }

        // 近5日板块涨跌趋势（注意：ReplacingMergeTree 表不能直接用 OFFSET 取日期，
        // 因为 OFFSET 按物理行偏移而非逻辑日期，需用子查询 DISTINCT）
        String trendSql = String.format("""
            SELECT sd.trade_date, AVG(sd.change_percent) as avgChg
            FROM stock.stock_daily sd FINAL
            WHERE sd.code IN ('%s')
              AND sd.trade_date >= (SELECT trade_date FROM (
                  SELECT DISTINCT trade_date FROM stock.stock_daily FINAL ORDER BY trade_date DESC LIMIT 6
                ) ORDER BY trade_date ASC LIMIT 1)
              AND sd.trade_date <= (SELECT MAX(trade_date) FROM stock.stock_daily FINAL)
            GROUP BY sd.trade_date
            ORDER BY sd.trade_date
            """, inClause);
        List<Map<String, Object>> trend = clickHouseJdbcTemplate.query(trendSql,
            (rs, rowNum) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("date", rs.getString(1));
                m.put("avgChange", rs.getBigDecimal(2).setScale(2, RoundingMode.HALF_UP));
                return m;
            });
        result.put("trend", trend);

        return result;
    }

}
