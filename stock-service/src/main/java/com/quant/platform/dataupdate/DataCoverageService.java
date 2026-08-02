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
