package com.quant.platform.dataupdate;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quant.platform.config.ClickHouseConfig;
import com.quant.platform.stock.entity.StockInfo;
import com.quant.platform.stock.mapper.StockInfoMapper;
import com.quant.platform.stock.service.ClickHouseStockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * 数据覆盖/完整性统计业务逻辑层
 * 承接原 DataUpdateController 中直接内联的缺失股票/缺失统计/交易日/研报覆盖率与校验逻辑。
 * （注意：数据质量监控另由 DataQualityService 负责，本类只做覆盖统计，避免命名冲突。）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataCoverageService {

    private final JdbcTemplate jdbcTemplate;
    private final StockInfoMapper stockInfoMapper;
    private final ClickHouseStockService clickHouseStockService;
    private final ClickHouseConfig clickHouseConfig;

    /** stock_info 全量 code → market 映射（ClickHouse 路径懒加载缓存） */
    private volatile Map<String, String> codeToMarket;
    /** stock_info 全量 code → listDate 映射（ClickHouse 路径懒加载缓存） */
    private volatile Map<String, LocalDate> codeToListDate;

    /**
     * 各市场数据缺失统计
     */
    public Map<String, Object> getMissingStats(LocalDate date) {
        Map<String, Object> result = new LinkedHashMap<>();
        int totalMissing = 0;

        Map<String, Long> totalByMarket = new HashMap<>();
        for (String market : Arrays.asList("SH", "SZ", "BJ")) {
            LambdaQueryWrapper<StockInfo> w = new LambdaQueryWrapper<>();
            w.eq(StockInfo::getMarket, market);
            w.le(StockInfo::getListDate, date);
            w.isNull(StockInfo::getDelistDate);
            Long total = stockInfoMapper.selectCount(w);
            totalByMarket.put(market, total != null ? total : 0L);
        }

        if (clickHouseConfig.isEnabled()) {
            Set<String> existingCodes = new HashSet<>();
            String chSql = "SELECT DISTINCT code FROM stock.stock_daily FINAL WHERE trade_date = ?";
            List<Map<String, Object>> rows = clickHouseStockService.queryForList(chSql, date.toString());
            for (Map<String, Object> row : rows) {
                Object codeVal = row.get("code");
                if (codeVal != null) existingCodes.add(codeVal.toString());
            }

            if (codeToMarket == null) {
                codeToMarket = new HashMap<>();
                codeToListDate = new HashMap<>();
                LambdaQueryWrapper<StockInfo> w = new LambdaQueryWrapper<>();
                w.select(StockInfo::getCode, StockInfo::getMarket, StockInfo::getListDate);
                w.isNull(StockInfo::getDelistDate);
                for (StockInfo s : stockInfoMapper.selectList(w)) {
                    if (s.getCode() != null && s.getMarket() != null) {
                        codeToMarket.put(s.getCode(), s.getMarket());
                        codeToListDate.put(s.getCode(), s.getListDate());
                    }
                }
            }

            Map<String, Integer> existingByMarket = new HashMap<>();
            for (String m : Arrays.asList("SH", "SZ", "BJ")) existingByMarket.put(m, 0);
            for (String code : existingCodes) {
                LocalDate listDate = codeToListDate.get(code);
                if (listDate != null && date.isBefore(listDate)) continue;
                String m = codeToMarket.get(code);
                if (m != null && existingByMarket.containsKey(m)) {
                    existingByMarket.put(m, existingByMarket.get(m) + 1);
                }
            }

            for (String market : Arrays.asList("SH", "SZ", "BJ")) {
                long total = totalByMarket.getOrDefault(market, 0L);
                long existing = existingByMarket.getOrDefault(market, 0);
                long missing = Math.max(0, total - existing);
                result.put(market, missing);
                totalMissing += (int) missing;
            }
        } else {
            for (String market : Arrays.asList("SH", "SZ", "BJ")) {
                long total = totalByMarket.getOrDefault(market, 0L);
                String mysqlSql =
                        "SELECT COUNT(DISTINCT sd.code) FROM stock_daily sd " +
                        "JOIN stock_info si ON sd.code = si.code " +
                        "WHERE sd.trade_date = ? " +
                        "  AND si.market = ?" +
                        "  AND si.list_date IS NOT NULL AND si.list_date <= ?" +
                        "  AND si.delist_date IS NULL";
                Long existing = jdbcTemplate.queryForObject(mysqlSql, Long.class,
                        java.sql.Date.valueOf(date), market, java.sql.Date.valueOf(date));
                long missing = Math.max(0, total - existing);
                result.put(market, (int) missing);
                totalMissing += (int) missing;
            }
        }

        result.put("total", totalMissing);
        result.put("date", date.toString());
        return result;
    }

    /**
     * 查询缺失股票列表（stock_info 中有但 stock_daily 指定日期没有的）
     */
    public List<Map<String, Object>> getMissingStocks(LocalDate date, String market) {
        LambdaQueryWrapper<StockInfo> wrapper = new LambdaQueryWrapper<>();
        if (!"ALL".equalsIgnoreCase(market)) {
            wrapper.eq(StockInfo::getMarket, market);
        }
        wrapper.isNull(StockInfo::getDelistDate);
        wrapper.select(StockInfo::getCode, StockInfo::getName, StockInfo::getMarket,
                StockInfo::getListDate, StockInfo::getDelistDate);
        List<StockInfo> allStocks = stockInfoMapper.selectList(wrapper);

        Set<String> existingCodes = new HashSet<>();
        if (clickHouseConfig.isEnabled()) {
            String chSql = "SELECT DISTINCT code FROM stock.stock_daily FINAL WHERE trade_date = ?";
            List<Map<String, Object>> rows = clickHouseStockService.queryForList(chSql, date.toString());
            for (Map<String, Object> row : rows) {
                Object codeVal = row.get("code");
                if (codeVal != null) existingCodes.add(codeVal.toString());
            }
        } else {
            String mysqlSql;
            if ("ALL".equalsIgnoreCase(market)) {
                mysqlSql = "SELECT DISTINCT sd.code FROM stock_daily sd WHERE sd.trade_date = ?";
                jdbcTemplate.query(mysqlSql,
                        (java.sql.ResultSet rs) -> existingCodes.add(rs.getString("code")),
                        java.sql.Date.valueOf(date));
            } else {
                mysqlSql = "SELECT DISTINCT sd.code FROM stock_daily sd " +
                        "JOIN stock_info si ON sd.code = si.code " +
                        "WHERE sd.trade_date = ? AND si.market = ?";
                jdbcTemplate.query(mysqlSql,
                        (java.sql.ResultSet rs) -> existingCodes.add(rs.getString("code")),
                        java.sql.Date.valueOf(date), market);
            }
        }

        List<Map<String, Object>> missing = new ArrayList<>();
        for (StockInfo stock : allStocks) {
            if (stock.getListDate() != null && date.isBefore(stock.getListDate())) continue;
            if (!existingCodes.contains(stock.getCode())) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("code", stock.getCode());
                m.put("name", stock.getName() != null ? stock.getName() : "");
                m.put("market", stock.getMarket() != null ? stock.getMarket() : "");
                missing.add(m);
            }
        }
        return missing;
    }

    /**
     * 获取有数据的交易日列表（limit>100 时视为全部）
     */
    public List<String> getTradingDates(int limit) {
        return clickHouseStockService.getRecentTradingDates(limit > 100 ? limit : 9999);
    }

    /**
     * 股票日线完整性校验（日期跨度版）。
     * 给定 [startDate, endDate] 与可选市场，列出各市场在区间内「未完整更新」的股票
     * （即区间内应存在交易日数据但实际覆盖天数不足的个股）。
     * 返回结构按市场分组，每项含 totalStocks / missingCount / missingStocks 明细。
     */
    public Map<String, Object> getMissingStocksRange(LocalDate startDate, LocalDate endDate, String market) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("startDate", startDate.toString());
        result.put("endDate", endDate.toString());

        // 1. 区间内所有交易日（去重，升序）
        List<LocalDate> tradingDates = new ArrayList<>();
        if (clickHouseConfig.isEnabled()) {
            String sql = "SELECT DISTINCT trade_date FROM stock.stock_daily FINAL " +
                    "WHERE trade_date BETWEEN ? AND ? ORDER BY trade_date";
            for (Map<String, Object> row : clickHouseStockService.queryForList(sql, startDate.toString(), endDate.toString())) {
                LocalDate d = toLocalDate(row.get("trade_date"));
                if (d != null) tradingDates.add(d);
            }
        } else {
            String sql = "SELECT DISTINCT trade_date FROM stock_daily " +
                    "WHERE trade_date BETWEEN ? AND ? ORDER BY trade_date";
            jdbcTemplate.query(sql, (rs) -> {
                LocalDate d = toLocalDate(rs.getDate("trade_date"));
                if (d != null) tradingDates.add(d);
            }, java.sql.Date.valueOf(startDate), java.sql.Date.valueOf(endDate));
        }
        result.put("tradingDays", tradingDates.size());

        // 2. 区间内每个 code 已覆盖的交易日数
        Map<String, Integer> coveredMap = new HashMap<>();
        if (clickHouseConfig.isEnabled()) {
            String sql = "SELECT code, COUNT(DISTINCT trade_date) AS covered " +
                    "FROM stock.stock_daily FINAL WHERE trade_date BETWEEN ? AND ? GROUP BY code";
            for (Map<String, Object> row : clickHouseStockService.queryForList(sql, startDate.toString(), endDate.toString())) {
                Object codeVal = row.get("code");
                Object covVal = row.get("covered");
                if (codeVal != null && covVal != null) {
                    coveredMap.put(codeVal.toString(), ((Number) covVal).intValue());
                }
            }
        } else {
            String sql = "SELECT code, COUNT(DISTINCT trade_date) AS covered " +
                    "FROM stock_daily WHERE trade_date BETWEEN ? AND ? GROUP BY code";
            jdbcTemplate.query(sql, (rs) -> {
                coveredMap.put(rs.getString("code"), rs.getInt("covered"));
            }, java.sql.Date.valueOf(startDate), java.sql.Date.valueOf(endDate));
        }

        // 3. 活跃股票（按市场过滤，上市不晚于区间末，未退市）
        List<String> markets = "ALL".equalsIgnoreCase(market)
                ? Arrays.asList("SH", "SZ", "BJ")
                : Collections.singletonList(market.toUpperCase());
        LambdaQueryWrapper<StockInfo> w = new LambdaQueryWrapper<>();
        w.in(StockInfo::getMarket, markets);
        w.le(StockInfo::getListDate, endDate);
        w.isNull(StockInfo::getDelistDate);
        w.select(StockInfo::getCode, StockInfo::getName, StockInfo::getMarket, StockInfo::getListDate);
        List<StockInfo> stocks = stockInfoMapper.selectList(w);

        // 4. 按市场分组
        Map<String, List<Map<String, Object>>> byMarket = new LinkedHashMap<>();
        Map<String, Integer> totalByMarket = new LinkedHashMap<>();
        Map<String, Integer> missingCountByMarket = new LinkedHashMap<>();
        for (String m : markets) {
            byMarket.put(m, new ArrayList<>());
            totalByMarket.put(m, 0);
            missingCountByMarket.put(m, 0);
        }

        for (StockInfo s : stocks) {
            if (s.getCode() == null || s.getMarket() == null) continue;
            String m = s.getMarket();
            if (!byMarket.containsKey(m)) continue; // 非目标市场（理论上不会发生）
            totalByMarket.put(m, totalByMarket.get(m) + 1);

            // 期望交易日数 = 区间内不早于上市日的交易日（tradingDates 已升序）
            LocalDate listDate = s.getListDate();
            int expected;
            if (listDate == null) {
                expected = tradingDates.size();
            } else {
                int idx = Collections.binarySearch(tradingDates, listDate);
                if (idx < 0) idx = -idx - 1; // 第一个 >= listDate 的位置
                expected = tradingDates.size() - idx;
            }
            if (expected <= 0) continue; // 区间内尚未上市

            int covered = coveredMap.getOrDefault(s.getCode(), 0);
            int missing = expected - covered;
            if (missing > 0) {
                Map<String, Object> mm = new LinkedHashMap<>();
                mm.put("code", s.getCode());
                mm.put("name", s.getName() != null ? s.getName() : "");
                mm.put("market", m);
                mm.put("listDate", listDate != null ? listDate.toString() : "");
                mm.put("expectedDays", expected);
                mm.put("coveredDays", covered);
                mm.put("missingDays", missing);
                byMarket.get(m).add(mm);
                missingCountByMarket.put(m, missingCountByMarket.get(m) + 1);
            }
        }

        // 各市场列表按缺失天数降序
        for (List<Map<String, Object>> list : byMarket.values()) {
            list.sort((a, b) -> ((Integer) b.get("missingDays")) - ((Integer) a.get("missingDays")));
        }

        // 6. 按日期×市场统计缺失量（用于前端汇总表格，不含个股明细）
        List<Map<String, Object>> dailyBreakdown = new ArrayList<>();
        if (!tradingDates.isEmpty()) {
            for (LocalDate td : tradingDates) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("date", td.toString());
                // 各市场当日缺失数
                if (clickHouseConfig.isEnabled()) {
                    String sql = "SELECT si.market, COUNT(*) AS missing " +
                            "FROM stock.stock_info si FINAL " +
                            "LEFT JOIN stock.stock_daily sd ON sd.code = si.code AND sd.trade_date = ? " +
                            "WHERE si.list_date <= ? AND si.delist_date IS NULL AND si.market IN ('SH','SZ','BJ') " +
                            "AND sd.code IS NULL GROUP BY si.market";
                    Map<String, Integer> dayMissing = new HashMap<>();
                    for (Map<String, Object> r : clickHouseStockService.queryForList(sql, td.toString(), endDate.toString())) {
                        String mk = r.get("market") != null ? r.get("market").toString() : null;
                        Integer cnt = r.get("missing") != null ? ((Number) r.get("missing")).intValue() : 0;
                        if (mk != null) dayMissing.put(mk, cnt);
                    }
                    row.put("SH", dayMissing.getOrDefault("SH", 0));
                    row.put("SZ", dayMissing.getOrDefault("SZ", 0));
                    row.put("BJ", dayMissing.getOrDefault("BJ", 0));
                } else {
                    String sql = "SELECT si.market, COUNT(*) AS missing " +
                            "FROM stock_info si " +
                            "LEFT JOIN stock_daily sd ON sd.code = si.code AND sd.trade_date = ? " +
                            "WHERE si.list_date <= ? AND si.delist_date IS NULL AND si.market IN ('SH','SZ','BJ') " +
                            "AND sd.code IS NULL GROUP BY si.market";
                    Map<String, Integer> dayMissing = new HashMap<>();
                    jdbcTemplate.query(sql, (rs) -> {
                        dayMissing.put(rs.getString("market"), rs.getInt("missing"));
                    }, java.sql.Date.valueOf(td), java.sql.Date.valueOf(endDate));
                    row.put("SH", dayMissing.getOrDefault("SH", 0));
                    row.put("SZ", dayMissing.getOrDefault("SZ", 0));
                    row.put("BJ", dayMissing.getOrDefault("BJ", 0));
                }
                int total = ((Number) row.get("SH")).intValue() + ((Number) row.get("SZ")).intValue() + ((Number) row.get("BJ")).intValue();
                row.put("total", total);
                dailyBreakdown.add(row);
            }
        }
        result.put("dailyBreakdown", dailyBreakdown);

        // 7. 组装 markets 输出
        Map<String, Object> marketsOut = new LinkedHashMap<>();
        for (String m : markets) {
            Map<String, Object> mo = new LinkedHashMap<>();
            mo.put("totalStocks", totalByMarket.get(m));
            mo.put("missingCount", missingCountByMarket.get(m));
            mo.put("missingStocks", byMarket.get(m));
            marketsOut.put(m, mo);
        }
        result.put("markets", marketsOut);

        int grandMissing = missingCountByMarket.values().stream().mapToInt(Integer::intValue).sum();
        result.put("totalMissingStocks", grandMissing);
        return result;
    }

    private LocalDate toLocalDate(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDate) return (LocalDate) v;
        if (v instanceof java.sql.Date) return ((java.sql.Date) v).toLocalDate();
        return LocalDate.parse(v.toString());
    }

    /**
     * 研报数据覆盖率概览
     */
    public Map<String, Object> getResearchCoverage() {
        Map<String, Object> result = new LinkedHashMap<>();
        Integer totalCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_research_report", Integer.class);
        result.put("totalCount", totalCount != null ? totalCount : 0);

        Integer stockCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT code) FROM stock_research_report", Integer.class);
        result.put("stockCount", stockCount != null ? stockCount : 0);

        String latestDate = jdbcTemplate.queryForObject(
                "SELECT MAX(report_date) FROM stock_research_report", String.class);
        result.put("latestDate", latestDate != null ? latestDate : "");
        return result;
    }

    /**
     * 研报数据校验
     */
    public Map<String, Object> validateResearch() {
        Map<String, Object> result = new LinkedHashMap<>();
        Integer totalReports = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_research_report", Integer.class);
        result.put("totalReports", totalReports != null ? totalReports : 0);

        List<String> warnings = new ArrayList<>();
        Integer nullRating = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_research_report WHERE rating IS NULL OR rating = ''", Integer.class);
        if (nullRating != null && nullRating > 0) {
            warnings.add("rating 为空: " + nullRating + " 条");
        }
        Integer nullTitle = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_research_report WHERE report_title IS NULL OR report_title = ''", Integer.class);
        if (nullTitle != null && nullTitle > 0) {
            warnings.add("report_title 为空: " + nullTitle + " 条");
        }
        Integer recentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_research_report WHERE report_date >= DATE_SUB(CURDATE(), INTERVAL 7 DAY)", Integer.class);
        result.put("recentReports", recentCount != null ? recentCount : 0);

        result.put("warnings", warnings);
        result.put("status", warnings.isEmpty() ? "OK" : "WARNING");
        return result;
    }
}
