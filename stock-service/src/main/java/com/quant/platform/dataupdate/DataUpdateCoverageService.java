package com.quant.platform.dataupdate;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quant.platform.stock.entity.StockInfo;
import com.quant.platform.stock.mapper.StockInfoMapper;
import com.quant.platform.stock.service.ClickHouseStockService;
import com.quant.platform.calendar.service.TradeCalendarService;
import com.quant.platform.factor.domain.FactorDefinition;
import com.quant.platform.factor.mapper.FactorDefinitionMapper;
import com.quant.platform.factor.service.FactorService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import com.quant.platform.common.enums.JobStatus;
@Slf4j
@Service
@RequiredArgsConstructor
public class DataUpdateCoverageService {
    private final com.quant.platform.stock.mapper.StockInfoMapper stockInfoMapper;
    private final com.quant.platform.stock.service.ClickHouseStockService clickHouseStockService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
@Autowired(required = false)
        private TradeCalendarService tradeCalendarService;
    public Map<String, Object> getDataCoverage() {
        Map<String, Object> result = new LinkedHashMap<>();

        // 总体统计（MySQL）
        // 排除已退市股票
        long totalStocks = stockInfoMapper.selectCount(
                new LambdaQueryWrapper<StockInfo>().isNull(StockInfo::getDelistDate));

        // ── ClickHouse 合并查询：一次SQL查出所有指标（排除指数） ─────────────
        String mergedSql = """
                 SELECT\s
                     COUNT(*) as total_records,
                     MIN(trade_date) as earliest_date,
                     MAX(trade_date) as latest_date,
                     -- 各市场记录数
                     countIf(code LIKE '6%' OR code LIKE '688%' OR code LIKE '689%') as sh_records,
                     countIf(code LIKE '0%' OR code LIKE '3%') as sz_records,
                     countIf(code LIKE '92%') as bj_records,
                     -- 各市场最新交易日
                     maxIf(trade_date, code LIKE '6%' OR code LIKE '688%' OR code LIKE '689%') as sh_latest,
                     maxIf(trade_date, code LIKE '0%' OR code LIKE '3%') as sz_latest,
                     maxIf(trade_date, code LIKE '92%') as bj_latest
                 FROM stock_daily FINAL
                 WHERE code NOT LIKE 'sh.%' AND code NOT LIKE 'sz.%'
                \s""";

        Map<String, Object> chData;
        boolean chOk = false;
        String chError = null;
        try {
            List<Map<String, Object>> rows = clickHouseStockService.queryForList(mergedSql);
            if (!rows.isEmpty()) {
                chData = rows.getFirst();
                chOk = true;
            } else {
                chData = Map.of();
                chError = "ClickHouse 查询返回空结果";
            }
        } catch (Exception e) {
            chError = e.getMessage();
            log.warn("[DataCoverage] ClickHouse合并查询失败: {}", chError);
            chData = Map.of();
        }

        long totalDailyRecords = toLong(chData.get("total_records"));
        String latestTradeDate = toDateStr(chData.get("latest_date"));
        String earliestTradeDate = toDateStr(chData.get("earliest_date"));

        // 概览
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("totalStocks", totalStocks);
        overview.put("totalDailyRecords", totalDailyRecords);
        overview.put("latestTradeDate", latestTradeDate);
        overview.put("earliestTradeDate", earliestTradeDate);

        // 各市场详细统计（MySQL查股票数，ClickHouse查日线数）
        List<Map<String, Object>> marketCoverage = new ArrayList<>();
        String[] markets = {"SH", "SZ", "BJ"};
        String[] chRecordKeys = {"sh_records", "sz_records", "bj_records"};
        String[] chDateKeys = {"sh_latest", "sz_latest", "bj_latest"};
        String[][] marketPatterns = {
                {"6", "688", "689"},
                {"0", "3"},
                {"92"}
        };

        for (int i = 0; i < markets.length; i++) {
            String market = markets[i];
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("market", market);

            // stock_info 中该市场股票总数（MySQL）
            LambdaQueryWrapper<StockInfo> iw = new LambdaQueryWrapper<>();
            iw.eq(StockInfo::getMarket, market)
              .isNull(StockInfo::getDelistDate);  // 排除已退市
            long infoCount = stockInfoMapper.selectCount(iw);
            m.put("infoCount", infoCount);

            // ClickHouse 数据
            long dailyCount = toLong(chData.get(chRecordKeys[i]));
            m.put("dailyRecords", dailyCount);

            String latestDateStr = toDateStr(chData.get(chDateKeys[i]));
            m.put("latestDate", latestDateStr);

            // 最新交易日覆盖股票数
            if (latestDateStr != null && !latestDateStr.equals("无数据")) {
                LocalDate latestDate = LocalDate.parse(latestDateStr);
                long latestDayCount = clickHouseStockService.getDistinctCodeCount(latestDate, marketPatterns[i]);
                m.put("latestDayCount", latestDayCount);
            } else {
                m.put("latestDayCount", 0);
            }

            marketCoverage.add(m);
        }

        result.put("overview", overview);
        result.put("markets", marketCoverage);
        if (!chOk) {
            result.put("warning", "ClickHouse 查询失败，数据可能不完整: " + (chError != null ? chError : "未知错误"));
        }

        log.info("[DataCoverage] 数据概览: {}只股票, {}条日线记录, 最新 {}, 最早 {}, 市场数 {}",
                totalStocks, totalDailyRecords, latestTradeDate, earliestTradeDate, marketCoverage.size());
        return result;
    }

    public long toLong(Object obj) {
        if (obj == null) return 0L;
        if (obj instanceof Number) return ((Number) obj).longValue();
        try {
            return Long.parseLong(obj.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public String toDateStr(Object obj) {
        if (obj == null) return "无数据";
        String str = obj.toString();
        return str.isEmpty() ? "无数据" : str;
    }

    public Map<String, Object> getIndexCoverage() {
        Map<String, Object> result = new LinkedHashMap<>();

        // 各指数数据统计（已迁移到 index_daily 表，code 为纯数字）
        String indexSql = """
                SELECT code, name,
                       COUNT(*) as record_count,
                       MIN(trade_date) as min_date,
                       MAX(trade_date) as max_date
                FROM index_daily
                GROUP BY code, name
                ORDER BY code
                """;
        List<Map<String, Object>> indices = clickHouseStockService.queryForList(indexSql);
        result.put("indices", indices);

        // 总记录数
        String totalSql = """
                SELECT COUNT(*) as cnt FROM index_daily FINAL
                """;
        Object totalObj = clickHouseStockService.queryForObject(totalSql);
        long totalRecords = totalObj != null ? ((Number) totalObj).longValue() : 0;
        result.put("totalRecords", totalRecords);
        result.put("indexCount", indices.size());

        // 最新交易日（指数数据的最大 trade_date）
        String latestSql = """
                SELECT MAX(trade_date) FROM index_daily
                """;
        Object latest = clickHouseStockService.queryForObject(latestSql);
        result.put("latestTradeDate", latest != null ? latest.toString() : null);

        return result;
    }

    public List<Map<String, Object>> getMissingIndices(LocalDate date) {
        // 全部 10 个指数，index_daily 中 code 为纯数字格式
        List<Map<String, String>> allIndices = List.of(
                Map.of("code", "000001", "name", "上证指数"),
                Map.of("code", "000016", "name", "上证50"),
                Map.of("code", "000022", "name", "中证红利"),
                Map.of("code", "000300", "name", "沪深300"),
                Map.of("code", "000688", "name", "科创50"),
                Map.of("code", "000852", "name", "中证1000"),
                Map.of("code", "000905", "name", "中证500"),
                Map.of("code", "399001", "name", "深证成指"),
                Map.of("code", "399006", "name", "创业板指"),
                Map.of("code", "399303", "name", "国证2000")
        );

        // 查该日期有数据的指数 code（index_daily 表）
        Set<String> existingCodes = new HashSet<>();
        String sql = """
                SELECT DISTINCT code FROM index_daily
                WHERE trade_date = ?
                """;
        List<Map<String, Object>> rows = clickHouseStockService.queryForList(sql, date.toString());
        for (Map<String, Object> row : rows) {
            existingCodes.add(String.valueOf(row.get("code")));
        }

        List<Map<String, Object>> missing = new ArrayList<>();
        for (Map<String, String> idx : allIndices) {
            if (!existingCodes.contains(idx.get("code"))) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("code", idx.get("code"));
                m.put("name", idx.get("name"));
                missing.add(m);
            }
        }
        return missing;
    }

    public Map<String, Object> getDividendCoverage() {
        Map<String, Object> result = new LinkedHashMap<>();

        // 总记录数
        long totalRecords = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_dividend", Long.class);
        result.put("totalRecords", totalRecords);

        // 覆盖股票数
        long distinctCodes = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT code) FROM stock_dividend", Long.class);
        result.put("coveredStocks", distinctCodes);

        // 沪深股票总数
        long totalShSz = stockInfoMapper.selectCount(
                new LambdaQueryWrapper<StockInfo>().in(StockInfo::getMarket, "SH", "SZ"));
        result.put("totalShSzStocks", totalShSz);
        result.put("coverageRate", totalShSz > 0
                ? Math.round((double) distinctCodes / totalShSz * 10000.0) / 100.0 : 0);

        // 时间范围
        Map<String, Object> dateRange = jdbcTemplate.queryForMap(
                "SELECT MIN(ex_dividend_date) as min_date, MAX(ex_dividend_date) as max_date FROM stock_dividend");
        result.put("minDate", dateRange.get("min_date"));
        result.put("maxDate", dateRange.get("max_date"));

        return result;
    }

    public Map<String, Object> getBidaskCoverage() {
        Map<String, Object> result = new LinkedHashMap<>();

        // 总记录数
        long totalRecords = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_bid_ask", Long.class);
        result.put("totalRecords", totalRecords);

        // 覆盖股票数
        long distinctCodes = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT code) FROM stock_bid_ask", Long.class);
        result.put("coveredStocks", distinctCodes);

        // 时间范围（max_date 不应展示未来源数据）
        Map<String, Object> dateRange = jdbcTemplate.queryForMap(
                "SELECT MIN(trade_date) as min_date, MAX(trade_date) as max_date FROM stock_bid_ask");
        result.put("minDate", dateRange.get("min_date"));
        Object rawMax = dateRange.get("max_date");
        if (rawMax != null) {
            LocalDate maxDay = ((java.sql.Date) rawMax).toLocalDate();
            LocalDate today = LocalDate.now();
            if (maxDay.isAfter(today)) {
                maxDay = today;
            }
            result.put("maxDate", java.sql.Date.valueOf(maxDay));
        } else {
            result.put("maxDate", null);
        }

        // 各市场统计
        List<Map<String, Object>> marketStats = jdbcTemplate.queryForList(
                "SELECT " +
                "  CASE " +
                "    WHEN LEFT(code, 1) = '6' THEN 'SH' " +
                "    WHEN LEFT(code, 1) IN ('0','3') THEN 'SZ' " +
                "    ELSE 'BJ' " +
                "  END as market, " +
                "  COUNT(*) as record_count, " +
                "  COUNT(DISTINCT code) as stock_count " +
                "FROM stock_bid_ask " +
                "GROUP BY market " +
                "ORDER BY market"
        );
        result.put("marketStats", marketStats);

        // 近期缺失交易日（源不可回溯）：最近30个自然日内、属交易日历但无数据的日期
        try {
            Object maxObj = dateRange.get("max_date");
            if (maxObj != null && tradeCalendarService != null) {
                LocalDate maxD = ((java.sql.Date) maxObj).toLocalDate();
                LocalDate today = LocalDate.now();
                // 缺失交易日只统计到当天，尚未到来的日期不展示
                if (maxD.isAfter(today)) {
                    maxD = today;
                }
                LocalDate startD = maxD.minusDays(30);
                List<LocalDate> tds = tradeCalendarService.getTradingDaysBetween(startD, maxD);
                if (!tds.isEmpty()) {
                    String placeholders = tds.stream()
                            .map(d -> "'" + d + "'")
                            .collect(Collectors.joining(","));
                    List<String> present = jdbcTemplate.queryForList(
                            "SELECT DISTINCT trade_date FROM stock_bid_ask WHERE trade_date IN (" + placeholders + ")",
                            String.class);
                    Set<String> presentSet = new HashSet<>(present);
                    List<String> missing = tds.stream()
                            .map(String::valueOf)
                            .filter(d -> !presentSet.contains(d))
                            .collect(Collectors.toList());
                    result.put("missingTradingDays", missing);
                }
            }
        } catch (Exception e) {
            log.warn("[getBidaskCoverage] 计算近期缺失交易日失败: {}", e.getMessage());
        }
        result.put("sourceNote", "内外盘依赖腾讯实时盘口，仅含交易日当日累计值；历史上未采集的交易日无法回溯补采。");

        return result;
    }

    public Map<String, Object> getMissingDividendStats() {
        Map<String, Object> result = new LinkedHashMap<>();

        // 沪深各市场缺少分红数据的股票数
        for (String market : Arrays.asList("SH", "SZ")) {
            long total = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM stock_info WHERE market = ?", Long.class, market);
            long covered = jdbcTemplate.queryForObject(
                    "SELECT COUNT(DISTINCT sd.code) FROM stock_dividend sd " +
                            "INNER JOIN stock_info si ON sd.code COLLATE utf8mb4_unicode_ci = si.code WHERE si.market = ?", Long.class, market);
            result.put(market, total - covered);
        }
        long totalSh = (long) result.getOrDefault("SH", 0L);
        long totalSz = (long) result.getOrDefault("SZ", 0L);
        result.put("total", totalSh + totalSz);
        result.put("coveredStocks", jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT code) FROM stock_dividend", Long.class));

        return result;
    }

    public List<Map<String, Object>> getMissingDividendStocks(String market, int page, int pageSize) {
        String marketCondition = "ALL".equals(market) ? "" : " AND si.market = '" + market + "'";
        int offset = (page - 1) * pageSize;
        String sql = "SELECT si.code, si.name, si.market " +
                "FROM stock_info si " +
                "LEFT JOIN stock_dividend sd ON si.code = sd.code " +
                "WHERE si.market IN ('SH', 'SZ') AND sd.id IS NULL" +
                marketCondition + " " +
                "ORDER BY si.code " +
                "LIMIT " + pageSize + " OFFSET " + offset;
        return jdbcTemplate.queryForList(sql);
    }

    public List<Map<String, Object>> getMissingStocks(String date, String market) {
        // 使用 SQL 直接查询缺失股票
        String marketCondition = "ALL".equals(market) ? "" : " AND si.market = '" + market + "'";
        String sql = """
                SELECT si.code, si.name, si.market
                FROM stock_info si
                WHERE si.market IN ('SH', 'SZ', 'BJ')
                AND si.delist_date IS NULL
                %s
                AND si.code NOT IN (
                    SELECT code FROM stock_daily WHERE trade_date = '%s'
                )
                ORDER BY si.code
                """.formatted(marketCondition, date);

        return jdbcTemplate.queryForList(sql);
    }

    public Map<String, Map<String, Object>> loadBidAskStats(LocalDate startDate, LocalDate endDate) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        if (startDate == null) startDate = endDate;

        // 目标总数（stock_info 中所有市场，排除 ST/退市）
        String totalSql = """
            SELECT COUNT(*) FROM stock_info si
            WHERE LENGTH(si.code)=6
            AND (si.name NOT LIKE '%退%' AND si.name NOT LIKE '%ST%')
            """;
        int total = jdbcTemplate.queryForObject(totalSql, Integer.class);

        // 查询日期范围内每一天的成功数（和 stock_info 目标范围保持一致）
        String dailySql = """
            SELECT b.trade_date, COUNT(*) as cnt
            FROM stock_bid_ask b
            INNER JOIN stock_info si ON si.code COLLATE utf8mb4_unicode_ci = b.code
            WHERE b.trade_date >= ? AND b.trade_date <= ?
              AND LENGTH(si.code)=6
              AND (si.name NOT LIKE '%退%' AND si.name NOT LIKE '%ST%')
            GROUP BY b.trade_date
            ORDER BY b.trade_date
            """;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(dailySql, startDate.toString(), endDate.toString());
        Map<String, Integer> dateSuccessMap = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String d = row.get("trade_date").toString();
            Integer cnt = ((Number) row.get("cnt")).intValue();
            dateSuccessMap.put(d, cnt);
        }

        // 遍历日期范围，生成每一天的统计
        for (LocalDate d = startDate; !d.isAfter(endDate); d = d.plusDays(1)) {
            String dateStr = d.toString();

            Map<String, Object> stats = new LinkedHashMap<>();

            // 使用交易日历判断（若服务不可用则默认为交易日，避免阻断统计）
            boolean isTrading = tradeCalendarService == null
                || tradeCalendarService.isTradingDay(d);

            if (!isTrading) {
                // 非交易日：标记为假日，不显示失败数和成功率
                boolean isWeekend = d.getDayOfWeek() == java.time.DayOfWeek.SATURDAY
                    || d.getDayOfWeek() == java.time.DayOfWeek.SUNDAY;
                stats.put("total", total);
                stats.put("success", 0);
                stats.put("failed", null);
                stats.put("rate", null);
                stats.put("holiday", true);
                stats.put("label", isWeekend ? "周末" : "节假日");
            } else {
                int success = dateSuccessMap.getOrDefault(dateStr, 0);
                stats.put("total", total);
                stats.put("success", success);
                stats.put("failed", total - success);
                stats.put("rate", total > 0 ? String.format("%.1f%%", 100.0 * success / total) : "N/A");
                stats.put("holiday", false);
                stats.put("label", null);
            }
            result.put(dateStr, stats);
        }
        return result;
    }

    public Map<String, Object> checkDataFreshness() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("checkTime", LocalDateTime.now().toString());

        // 确定"最新交易日"作为基准
        LocalDate today = LocalDate.now();
        LocalDate latestTradeDate = today;
        if (tradeCalendarService != null) {
            // 回退到最近交易日
            for (int i = 0; i < 10; i++) {
                LocalDate d = today.minusDays(i);
                if (tradeCalendarService.isTradingDay(d)) {
                    latestTradeDate = d;
                    break;
                }
            }
        }
        report.put("latestTradeDate", latestTradeDate.toString());

        // ── 1. stock_daily (ClickHouse) ──
        try {
            Object sdMax = clickHouseStockService.queryForObject(
                "SELECT max(trade_date) FROM stock_daily WHERE code NOT LIKE '399%' AND code NOT LIKE '000%' AND length(code)=6");
            LocalDate sdDate = sdMax != null ? LocalDate.parse(sdMax.toString()) : null;
            long sdDays = sdDate != null ? tradeCalendarService.countTradingDays(sdDate, latestTradeDate) : 999;
            Map<String, Object> sdStatus = new LinkedHashMap<>();
            sdStatus.put("latestDate", sdDate != null ? sdDate.toString() : "N/A");
            sdStatus.put("daysBehind", sdDays);
            sdStatus.put("stale", sdDays > 2);
            if (sdDays > 2) {
                log.warn("[数据新鲜度] stock_daily 落后 {} 天（最新={}，基准={}）",
                    sdDays, sdDate, latestTradeDate);
            }
            report.put("stockDaily", sdStatus);
        } catch (Exception e) {
            log.warn("[数据新鲜度] stock_daily 查询失败: {}", e.getMessage());
            report.put("stockDaily", Map.of("error", e.getMessage()));
        }

        // ── 2. factor_value (ClickHouse) ──
        try {
            Object fvMax = clickHouseStockService.queryForObject(
                "SELECT max(calc_date) FROM factor_value");
            LocalDate fvDate = fvMax != null ? LocalDate.parse(fvMax.toString()) : null;
            long fvDays = fvDate != null ? tradeCalendarService.countTradingDays(fvDate, latestTradeDate) : 999;
            Map<String, Object> fvStatus = new LinkedHashMap<>();
            fvStatus.put("latestDate", fvDate != null ? fvDate.toString() : "N/A");
            fvStatus.put("daysBehind", fvDays);
            fvStatus.put("stale", fvDays > 1);
            if (fvDays > 1) {
                log.warn("[数据新鲜度] factor_value 落后 {} 天（最新={}，基准={}）",
                    fvDays, fvDate, latestTradeDate);
            }
            report.put("factorValue", fvStatus);
        } catch (Exception e) {
            log.warn("[数据新鲜度] factor_value 查询失败: {}", e.getMessage());
            report.put("factorValue", Map.of("error", e.getMessage()));
        }

        // ── 3. stock_financial_indicator (MySQL) ──
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
            // 财务数据按季度判断：计算距离最近季末的天数
            long fiStale = 999;
            if (fiDate != null) {
                // 最近季末日期（3/31, 6/30, 9/30, 12/31）
                int year = today.getYear();
                int month = today.getMonthValue();
                LocalDate lastQuarterEnd;
                if (month <= 3) lastQuarterEnd = LocalDate.of(year - 1, 12, 31);
                else if (month <= 6) lastQuarterEnd = LocalDate.of(year, 3, 31);
                else if (month <= 9) lastQuarterEnd = LocalDate.of(year, 6, 30);
                else lastQuarterEnd = LocalDate.of(year, 9, 30);
                fiStale = lastQuarterEnd.toEpochDay() - fiDate.toEpochDay();
                if (fiStale < 0) fiStale = 0; // 数据比预期新，没问题
            }
            Map<String, Object> fiStatus = new LinkedHashMap<>();
            fiStatus.put("latestReportDate", fiDate != null ? fiDate.toString() : "N/A");
            fiStatus.put("quartersBehind", fiStale > 90 ? fiStale / 90 : 0);
            fiStatus.put("stale", fiStale > 90);
            if (fiStale > 90) {
                log.warn("[数据新鲜度] stock_financial_indicator 落后约 {} 天（最新报告期={}）",
                    fiStale, fiDate);
            }
            report.put("financialIndicator", fiStatus);
        } catch (Exception e) {
            log.warn("[数据新鲜度] financial_indicator 查询失败: {}", e.getMessage());
            report.put("financialIndicator", Map.of("error", e.getMessage()));
        }

        report.put("hasWarning", report.values().stream().anyMatch(v -> {
            if (v instanceof Map<?,?> m) {
                Object stale = m.get("stale");
                return stale instanceof Boolean b && b;
            }
            return false;
        }));

        return report;
    }

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
                log.warn("[价格异常检测] 近{}天发现 {} 条涨跌幅 >50% 记录，建议人工复核", days, anomalies.size());
            }
        } catch (Exception e) {
            log.warn("[价格异常检测] 查询失败: {}", e.getMessage());
            result.put("error", e.getMessage());
        }

        return result;
    }

}
