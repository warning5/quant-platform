package com.quant.platform.stock.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quant.platform.config.ClickHouseConfig;
import com.quant.platform.stock.entity.StockDaily;
import com.quant.platform.stock.mapper.StockDailyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.*;
import java.sql.Date;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ClickHouse 股票数据服务
 * 优先从 ClickHouse 查询，失败时回退到 MySQL
 * 所有查询方法统一遵循: CH enabled → 查 CH → 失败/无数据 → 回退 MySQL
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClickHouseStockService {

    private final ClickHouseConfig clickHouseConfig;
    private final StockDailyMapper stockDailyMapper;

    // ==================== 指数查询（index_daily 表） ====================

    /**
     * 查询单个指数的历史日线数据（从 index_daily 表）
     * 分表存储后，指数数据独立于 stock_daily，避免 code 冲突
     */
    public List<StockDaily> getIndexDaily(String code, LocalDate startDate, LocalDate endDate) {
        if (!clickHouseConfig.isEnabled()) {
            // MySQL 回退：查 index_daily 表
            return getIndexDailyFromMySQL(code, startDate, endDate);
        }

        try {
            String sql = """
                    SELECT code, trade_date, name, open_price, close_price, high_price, low_price,
                           pre_close, volume, amount, change_percent, change_amount,
                           turnover_rate, pe_ttm, pb
                    FROM index_daily FINAL
                    WHERE code = ? AND trade_date >= ? AND trade_date <= ?
                    ORDER BY trade_date
                    """;
            return executeQuery(sql, code, startDate, endDate);
        } catch (Exception e) {
            log.warn("[ClickHouse] 指数查询失败(index_daily)，回退到 MySQL: {}", e.getMessage());
            return getIndexDailyFromMySQL(code, startDate, endDate);
        }
    }

    private List<StockDaily> getIndexDailyFromMySQL(String code, LocalDate startDate, LocalDate endDate) {
        LambdaQueryWrapper<StockDaily> wrapper = new LambdaQueryWrapper<>();
        // MySQL 回退时也尝试查 index_daily（如果存在的话），否则查 stock_daily + name 过滤
        wrapper.eq(StockDaily::getCode, code)
                .ge(StockDaily::getTradeDate, startDate)
                .le(StockDaily::getTradeDate, endDate)
                .orderByAsc(StockDaily::getTradeDate);
        return stockDailyMapper.selectList(wrapper);
    }

    /**
     * 批量查询多个指数的日线数据（使用 Statement 避免 PreparedStatement 参数绑定问题）
     *
     * @param codes     指数代码集合（如 ["801010", "801030", ...]）
     * @param startDate 开始日期
     * @param endDate   结束日期
     * @return code → bars 映射（按 trade_date ASC 排序）
     */
    public Map<String, List<StockDaily>> getIndexDailyBatch(Set<String> codes, LocalDate startDate, LocalDate endDate) {
        Map<String, List<StockDaily>> result = new LinkedHashMap<>();
        if (codes == null || codes.isEmpty()) return result;

        if (!clickHouseConfig.isEnabled()) {
            // MySQL 回退：逐个查询
            for (String code : codes) {
                List<StockDaily> bars = getIndexDailyFromMySQL(code, startDate, endDate);
                if (!bars.isEmpty()) result.put(code, bars);
            }
            return result;
        }

        // ClickHouse: 修复 SQL 注入 - 使用 PreparedStatement 参数化查询
        String placeholders = codes.stream()
                .map(c -> "?")
                .collect(Collectors.joining(","));
        String sql = 
                "SELECT code, trade_date, name, open_price, close_price, high_price, low_price, " +
                "pre_close, volume, amount, change_percent, change_amount, " +
                "turnover_rate, pe_ttm, pb " +
                "FROM index_daily FINAL " +
                "WHERE code IN (" + placeholders + ") AND trade_date >= ? AND trade_date <= ? " +
                "ORDER BY code, trade_date";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            int paramIdx = 1;
            for (String code : codes) {
                stmt.setString(paramIdx++, code);
            }
            stmt.setDate(paramIdx++, Date.valueOf(startDate));
            stmt.setDate(paramIdx, Date.valueOf(endDate));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    StockDaily daily = convertResultSet(rs);
                    result.computeIfAbsent(daily.getCode(), k -> new ArrayList<>()).add(daily);
                }
            }
        } catch (Exception e) {
            log.warn("[ClickHouse] 批量指数查询失败: {}", e.getMessage());
            // 不回退 MySQL，静默返回空
        }
        return result;
    }

    // ==================== 基础按 code+日期范围查询 ====================

    /**
     * 查询单只股票的历史日线数据（优先 ClickHouse）
     */
    public List<StockDaily> getStockDaily(String code, LocalDate startDate, LocalDate endDate) {
        if (!clickHouseConfig.isEnabled()) {
            return getFromMySQL(code, startDate, endDate);
        }

        try {
            List<StockDaily> result = queryFromClickHouse(code, startDate, endDate);
            if (!result.isEmpty()) {
                log.debug("[ClickHouse] 命中: {} {}~{}", code, startDate, endDate);
                return result;
            }
        } catch (Exception e) {
            log.warn("[ClickHouse] 查询失败，回退到 MySQL: {}", e.getMessage());
        }

        return getFromMySQL(code, startDate, endDate);
    }

    /**
     * 批量查询多只股票的历史数据（优先 ClickHouse）
     */
    public List<StockDaily> getStockDailyBatch(List<String> codes, LocalDate startDate, LocalDate endDate) {
        return getStockDailyBatch(codes, startDate, endDate, false);
    }

    /**
     * 批量查询多只股票日线
     * @param useFinal 是否使用 FINAL（预加载场景设 false 可大幅提速）
     */
    public List<StockDaily> getStockDailyBatch(List<String> codes, LocalDate startDate, LocalDate endDate, boolean useFinal) {
        if (!clickHouseConfig.isEnabled() || codes.isEmpty()) {
            return getBatchFromMySQL(codes, startDate, endDate);
        }

        try {
            List<StockDaily> result = queryBatchFromClickHouse(codes, startDate, endDate, useFinal);
            if (!result.isEmpty()) {
                log.debug("[ClickHouse] 批量命中: {}只股票 {}~{} (final={})", codes.size(), startDate, endDate, useFinal);
                return result;
            }
            log.warn("[ClickHouse] 批量查询返回 0 行: {} 只股票 {}~{}, 回退 MySQL", codes.size(), startDate, endDate);
        } catch (Exception e) {
            log.warn("[ClickHouse] 批量查询失败，回退到 MySQL: {}", e.getMessage());
        }

        return getBatchFromMySQL(codes, startDate, endDate);
    }

    // ==================== 按日期查询（截面） ====================

    /**
     * 查询指定日期的所有股票数据（截面）
     *
     * @param date         交易日期
     * @param excludeNames 需要排除的名称（如指数名称）
     */
    public List<StockDaily> getStockDailyByDate(LocalDate date, Collection<String> excludeNames) {
        if (!clickHouseConfig.isEnabled()) {
            return getDailyByDateFromMySQL(date, excludeNames);
        }

        try {
            List<StockDaily> result = queryDailyByDateFromClickHouse(date, excludeNames);
            if (!result.isEmpty()) {
                log.debug("[ClickHouse] 截面命中: {} 共{}条", date, result.size());
                return result;
            }
        } catch (Exception e) {
            log.warn("[ClickHouse] 截面查询失败，回退到 MySQL: {}", e.getMessage());
        }

        return getDailyByDateFromMySQL(date, excludeNames);
    }

    /**
     * 分页查询截面数据
     */
    public Map<String, Object> getCrossSectionPaged(LocalDate date, int page, int size,
                                                    String keyword, String sortField, String sortOrder) {
        if (!clickHouseConfig.isEnabled()) {
            return getCrossSectionPagedFromMySQL(date, page, size, keyword, sortField, sortOrder);
        }

        try {
            return getCrossSectionPagedFromClickHouse(date, page, size, keyword, sortField, sortOrder);
        } catch (Exception e) {
            log.warn("[ClickHouse] 截面分页查询失败，回退到 MySQL: {}", e.getMessage());
        }

        return getCrossSectionPagedFromMySQL(date, page, size, keyword, sortField, sortOrder);
    }

    // ==================== 聚合查询（概览统计） ====================

    /**
     * 获取指定日期的市场统计摘要
     * 返回: count, riseCount, fallCount, flatCount, avgPctChg, totalAmount
     */
    public Map<String, Object> getOverviewStats(LocalDate tradeDate) {
        if (!clickHouseConfig.isEnabled()) {
            return getOverviewStatsFromMySQL(tradeDate);
        }

        try {
            return getOverviewStatsFromClickHouse(tradeDate);
        } catch (Exception e) {
            log.warn("[ClickHouse] 概览统计失败，回退到 MySQL: {}", e.getMessage());
        }

        return getOverviewStatsFromMySQL(tradeDate);
    }

    /**
     * 获取指定日期涨跌幅 Top N
     * 返回列表: code, name, change_percent, close_price, volume, amount, turnover_rate
     */
    public List<Map<String, Object>> getTopByPctChg(LocalDate tradeDate, int limit, String order) {
        if (!clickHouseConfig.isEnabled()) {
            return getTopByPctChgFromMySQL(tradeDate, limit, order);
        }

        try {
            return getTopByPctChgFromClickHouse(tradeDate, limit, order);
        } catch (Exception e) {
            log.warn("[ClickHouse] Top N 查询失败，回退到 MySQL: {}", e.getMessage());
        }

        return getTopByPctChgFromMySQL(tradeDate, limit, order);
    }

    // ==================== 交易日查询 ====================

    /**
     * 获取最新交易日期
     */
    public LocalDate getLatestTradingDate(LocalDate start, LocalDate end) {
        if (!clickHouseConfig.isEnabled()) {
            return getLatestTradingDateFromMySQL(start, end);
        }

        try {
            return getLatestTradingDateFromClickHouse(start, end);
        } catch (Exception e) {
            log.warn("[ClickHouse] 最新交易日查询失败，回退到 MySQL: {}", e.getMessage());
        }

        return getLatestTradingDateFromMySQL(start, end);
    }

    /**
     * 获取交易日期列表
     */
    public List<LocalDate> getTradingDates(LocalDate start, LocalDate end) {
        if (!clickHouseConfig.isEnabled()) {
            return getTradingDatesFromMySQL(start, end);
        }

        try {
            return getTradingDatesFromClickHouse(start, end);
        } catch (Exception e) {
            log.warn("[ClickHouse] 交易日列表查询失败，回退到 MySQL: {}", e.getMessage());
        }

        return getTradingDatesFromMySQL(start, end);
    }

    /**
     * 获取最近有数据的交易日列表（倒序）
     */
    public List<String> getRecentTradingDates(int limit) {
        if (!clickHouseConfig.isEnabled()) {
            return getRecentTradingDatesFromMySQL(limit);
        }

        try {
            return getRecentTradingDatesFromClickHouse(limit);
        } catch (Exception e) {
            log.warn("[ClickHouse] 最近交易日查询失败，回退到 MySQL: {}", e.getMessage());
        }

        return getRecentTradingDatesFromMySQL(limit);
    }

    // ==================== 统计查询（覆盖率） ====================

    /**
     * 查询 stock_daily 总记录数
     */
    public long getTotalDailyCount() {
        if (!clickHouseConfig.isEnabled()) {
            return stockDailyMapper.selectCount(null);
        }

        try {
            String sql = "SELECT COUNT(*) FROM stock_daily";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        } catch (Exception e) {
            log.warn("[ClickHouse] 总记录数查询失败，回退到 MySQL: {}", e.getMessage());
        }

        return stockDailyMapper.selectCount(null);
    }

    /**
     * 按条件查询记录数（用于市场覆盖率统计）
     * @param codeLike 代码前缀模式，如 "6%" 表示 6开头
     */
    public long getDailyCountByCodePrefix(String codeLike) {
        if (!clickHouseConfig.isEnabled()) {
            LambdaQueryWrapper<StockDaily> wrapper = new LambdaQueryWrapper<>();
            wrapper.likeRight(StockDaily::getCode, codeLike.replace("%", ""));
            return stockDailyMapper.selectCount(wrapper);
        }

        try {
            // 修复 SQL 注入 - 使用 PreparedStatement 参数化查询
            String sql = "SELECT COUNT(*) FROM stock_daily WHERE code LIKE ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, codeLike);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) return rs.getLong(1);
                }
            }
        } catch (Exception e) {
            log.warn("[ClickHouse] 按前缀统计失败，回退到 MySQL: {}", e.getMessage());
        }

        LambdaQueryWrapper<StockDaily> wrapper = new LambdaQueryWrapper<>();
        wrapper.likeRight(StockDaily::getCode, codeLike.replace("%", ""));
        return stockDailyMapper.selectCount(wrapper);
    }

    /**
     * 获取最新/最早交易日期
     */
    public LocalDate getLatestTradeDate() {
        if (!clickHouseConfig.isEnabled()) {
            return getExtremeDateFromMySQL(true);
        }
        try {
            return getExtremeDateFromClickHouse(true);
        } catch (Exception e) {
            log.warn("[ClickHouse] 获取最新交易日失败，回退到 MySQL: {}", e.getMessage());
        }
        return getExtremeDateFromMySQL(true);
    }

    public LocalDate getEarliestTradeDate() {
        if (!clickHouseConfig.isEnabled()) {
            return getExtremeDateFromMySQL(false);
        }
        try {
            return getExtremeDateFromClickHouse(false);
        } catch (Exception e) {
            log.warn("[ClickHouse] 获取最早交易日失败，回退到 MySQL: {}", e.getMessage());
        }
        return getExtremeDateFromMySQL(false);
    }

    /**
     * 按市场和日期查询最新交易日的股票数 (COUNT DISTINCT code)
     * @param prefixes 代码前缀数组，如 ["6"], ["0", "3"], ["92"]
     */
    public long getDistinctCodeCount(LocalDate date, String... prefixes) {
        if (!clickHouseConfig.isEnabled()) {
            // MySQL 回退：查所有再 count distinct
            LambdaQueryWrapper<StockDaily> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(StockDaily::getTradeDate, date);
            applyPrefixesToWrapper(wrapper, prefixes);
            List<StockDaily> list = stockDailyMapper.selectList(wrapper);
            return list.stream().map(StockDaily::getCode).distinct().count();
        }

        try {
            // 修复 SQL 注入 - 使用 PreparedStatement 参数化查询
            StringBuilder sql = new StringBuilder("SELECT COUNT(DISTINCT code) FROM stock_daily FINAL WHERE trade_date = ?");
            if (prefixes != null && prefixes.length > 0) {
                sql.append(" AND (");
                for (int i = 0; i < prefixes.length; i++) {
                    if (i > 0) sql.append(" OR ");
                    sql.append("code LIKE ?");
                }
                sql.append(")");
            }
            
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                int paramIdx = 1;
                stmt.setString(paramIdx++, date.toString());
                if (prefixes != null) {
                    for (String prefix : prefixes) {
                        stmt.setString(paramIdx++, prefix + "%");
                    }
                }
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) return rs.getLong(1);
                }
            }
        } catch (Exception e) {
            log.warn("[ClickHouse] distinct count 查询失败，回退到 MySQL: {}", e.getMessage());
        }

        // MySQL 回退
        LambdaQueryWrapper<StockDaily> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StockDaily::getTradeDate, date);
        applyPrefixesToWrapper(wrapper, prefixes);
        List<StockDaily> list = stockDailyMapper.selectList(wrapper);
        return list.stream().map(StockDaily::getCode).distinct().count();
    }

    /**
     * 将前缀数组应用到 QueryWrapper
     */
    private void applyPrefixesToWrapper(LambdaQueryWrapper<StockDaily> wrapper, String... prefixes) {
        if (prefixes == null || prefixes.length == 0) return;
        
        wrapper.and(w -> {
            w.likeRight(StockDaily::getCode, prefixes[0]);
            for (int i = 1; i < prefixes.length; i++) {
                w.or().likeRight(StockDaily::getCode, prefixes[i]);
            }
        });
    }

    /**
     * 查询指定日期有数据的 codes
     */
    public Set<String> getExistingCodes(LocalDate date, Collection<String> codes) {
        if (!clickHouseConfig.isEnabled() || codes.isEmpty()) {
            return getExistingCodesFromMySQL(date, codes);
        }

        try {
            return getExistingCodesFromClickHouse(date, codes);
        } catch (Exception e) {
            log.warn("[ClickHouse] existing codes 查询失败，回退到 MySQL: {}", e.getMessage());
        }

        return getExistingCodesFromMySQL(date, codes);
    }

    /**
     * 执行通用 SQL 查询（用于 DataUpdateService 中的指数覆盖率等）
     * 返回 Map 列表
     */
    public List<Map<String, Object>> queryForList(String sql, Object... params) {
        if (!clickHouseConfig.isEnabled()) {
            return queryForListFromMySQL(sql, params);
        }

        try {
            return queryForListFromClickHouse(sql, params);
        } catch (Exception e) {
            log.warn("[ClickHouse] 通用查询失败，回退到 MySQL: {}", e.getMessage());
        }

        return queryForListFromMySQL(sql, params);
    }

    /**
     * 执行通用 SQL 查询单值
     */
    public Object queryForObject(String sql, Object... params) {
        if (!clickHouseConfig.isEnabled()) {
            return queryForObjectFromMySQL(sql, params);
        }

        try {
            return queryForObjectFromClickHouse(sql, params);
        } catch (Exception e) {
            log.warn("[ClickHouse] 通用单值查询失败，回退到 MySQL: {}", e.getMessage());
        }

        return queryForObjectFromMySQL(sql, params);
    }

    // ==================== ClickHouse 查询实现 ====================

    private List<StockDaily> queryFromClickHouse(String code, LocalDate startDate, LocalDate endDate) {
        String sql = """
                SELECT code, trade_date, name, open_price, close_price, high_price, low_price,
                       pre_close, volume, amount, change_percent, change_amount,
                       turnover_rate, pe_ttm, pb
                FROM stock_daily FINAL
                WHERE code = ? AND trade_date >= ? AND trade_date <= ?
                ORDER BY trade_date
                """;

        return executeQuery(sql, code, startDate, endDate);
    }

    private List<StockDaily> queryBatchFromClickHouse(List<String> codes, LocalDate startDate, LocalDate endDate) {
        return queryBatchFromClickHouse(codes, startDate, endDate, true);
    }

    private List<StockDaily> queryBatchFromClickHouse(List<String> codes, LocalDate startDate, LocalDate endDate, boolean useFinal) {
        if (codes.size() == 1) {
            return queryFromClickHouse(codes.get(0), startDate, endDate);
        }

        String placeholders = String.join(",", Collections.nCopies(codes.size(), "?"));
        String finalClause = useFinal ? " FINAL" : "";
        String sql = String.format("""
                SELECT code, trade_date, name, open_price, close_price, high_price, low_price,
                       pre_close, volume, amount, change_percent, change_amount,
                       turnover_rate, pe_ttm, pb
                FROM stock_daily%s
                WHERE code IN (%s) AND trade_date >= ? AND trade_date <= ?
                ORDER BY code, trade_date
                """, finalClause, placeholders);

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            int paramIdx = 1;
            for (String code : codes) {
                stmt.setString(paramIdx++, code);
            }
            stmt.setString(paramIdx++, startDate.toString());
            stmt.setString(paramIdx, endDate.toString());
            
            try (ResultSet rs = stmt.executeQuery()) {
                List<StockDaily> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(convertResultSet(rs));
                }
                return result;
            }
        } catch (Exception e) {
            log.warn("[ClickHouse] 批量查询失败: {}", e.getMessage());
            throw new RuntimeException("ClickHouse 批量查询失败", e);
        }
    }

    private List<StockDaily> queryDailyByDateFromClickHouse(LocalDate date, Collection<String> excludeNames) {
        StringBuilder sql = new StringBuilder("""
                SELECT code, trade_date, name, open_price, close_price, high_price, low_price,
                       pre_close, volume, amount, change_percent, change_amount,
                       turnover_rate, pe_ttm, pb
                FROM stock_daily FINAL
                WHERE trade_date = ?
                """);

        if (excludeNames != null && !excludeNames.isEmpty()) {
            String placeholders = String.join(",", Collections.nCopies(excludeNames.size(), "?"));
            sql.append("AND name NOT IN (").append(placeholders).append(") ");
        }
        sql.append("ORDER BY code");

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            int paramIdx = 1;
            stmt.setString(paramIdx++, date.toString());
            if (excludeNames != null && !excludeNames.isEmpty()) {
                for (String name : excludeNames) {
                    stmt.setString(paramIdx++, name);
                }
            }
            try (ResultSet rs = stmt.executeQuery()) {
                List<StockDaily> result = new ArrayList<>();
                while (rs.next()) result.add(convertResultSet(rs));
                return result;
            }
        } catch (Exception e) {
            log.warn("[ClickHouse] 截面查询失败: {}", e.getMessage());
            throw new RuntimeException("ClickHouse 截面查询失败", e);
        }
    }

    private Map<String, Object> getCrossSectionPagedFromClickHouse(LocalDate date, int page, int size,
                                                                   String keyword, String sortField, String sortOrder) {
        StringBuilder whereSql = new StringBuilder("WHERE trade_date = ?");
        List<Object> params = new ArrayList<>();
        params.add(date.toString());
        
        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim();
            whereSql.append(" AND (code LIKE ? OR name LIKE ?)");
            params.add("%" + kw + "%");
            params.add("%" + kw + "%");
        }

        // 总数
        long total;
        String countSql = "SELECT COUNT(*) FROM stock_daily FINAL " + whereSql;
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(countSql)) {
            for (int i = 0; i < params.size(); i++) {
                stmt.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = stmt.executeQuery()) {
                total = rs.next() ? rs.getLong(1) : 0;
            }
        } catch (Exception e) {
            log.warn("[ClickHouse] 截面分页count查询失败: {}", e.getMessage());
            throw new RuntimeException("ClickHouse 截面分页查询失败", e);
        }

        // 排序
        String orderBy = buildOrderByClause(sortField, sortOrder);

        // 分页查询
        int offset = (page - 1) * size;
        String dataSql = """
                SELECT code, trade_date, name, open_price, close_price, high_price, low_price,
                       pre_close, volume, amount, change_percent, change_amount,
                       turnover_rate, pe_ttm, pb
                FROM stock_daily FINAL
                """ + whereSql + " " + orderBy + " LIMIT ? OFFSET ?";

        List<StockDaily> records = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(dataSql)) {
            int paramIdx = 1;
            for (Object param : params) {
                stmt.setObject(paramIdx++, param);
            }
            stmt.setInt(paramIdx++, size);
            stmt.setInt(paramIdx, offset);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) records.add(convertResultSet(rs));
            }
        } catch (Exception e) {
            log.warn("[ClickHouse] 截面分页查询失败: {}", e.getMessage());
            throw new RuntimeException("ClickHouse 截面分页查询失败", e);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        result.put("totalPages", (total + size - 1) / size);
        result.put("data", records);
        return result;
    }

    private Map<String, Object> getOverviewStatsFromClickHouse(LocalDate tradeDate) {
        // 排除指数代码（sh.000xxx / sz.399xxx），只统计股票
        String sql = """
                SELECT
                    COUNT(*) AS count,
                    countIf(change_percent > 0) AS riseCount,
                    countIf(change_percent < 0) AS fallCount,
                    countIf(change_percent IS NULL OR change_percent = 0) AS flatCount,
                    ifNull(avg(change_percent), 0) AS avgPctChg,
                    ifNull(SUM(amount), 0) AS totalAmount
                FROM stock_daily FINAL
                WHERE trade_date = ?
                  AND code NOT LIKE 'sh.000%'
                  AND code NOT LIKE 'sz.399%'
                """;

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, tradeDate.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> map = new HashMap<>();
                    map.put("count", rs.getLong("count"));
                    map.put("riseCount", rs.getLong("riseCount"));
                    map.put("fallCount", rs.getLong("fallCount"));
                    map.put("flatCount", rs.getLong("flatCount"));
                    map.put("avgPctChg", rs.getDouble("avgPctChg"));
                    map.put("totalAmount", rs.getBigDecimal("totalAmount"));
                    return map;
                }
            }
        } catch (Exception e) {
            log.warn("[ClickHouse] 概览统计查询失败: {}", e.getMessage());
            throw new RuntimeException("ClickHouse 概览统计失败", e);
        }
        return Map.of("count", 0L, "riseCount", 0L, "fallCount", 0L, "flatCount", 0L,
                "avgPctChg", 0.0, "totalAmount", BigDecimal.ZERO);
    }

    private List<Map<String, Object>> getTopByPctChgFromClickHouse(LocalDate tradeDate, int limit, String order) {
        // 排除指数代码（sh.000xxx / sz.399xxx）
        String orderClause = "ASC".equalsIgnoreCase(order) ? "ASC" : "DESC";
        String sql = """
                SELECT code, name, change_percent, close_price, volume, amount, turnover_rate
                FROM stock_daily FINAL
                WHERE trade_date = ? AND change_percent IS NOT NULL
                  AND code NOT LIKE 'sh.000%' AND code NOT LIKE 'sz.399%'
                ORDER BY change_percent
                """ + orderClause + " LIMIT ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, tradeDate.toString());
            stmt.setInt(2, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                List<Map<String, Object>> result = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    row.put("code", rs.getString("code"));
                    row.put("name", rs.getString("name"));
                    row.put("change_percent", rs.getDouble("change_percent"));
                    row.put("close_price", rs.getBigDecimal("close_price"));
                    row.put("volume", rs.getLong("volume"));
                    row.put("amount", rs.getBigDecimal("amount"));
                    row.put("turnover_rate", rs.getBigDecimal("turnover_rate"));
                    result.add(row);
                }
                return result;
            }
        } catch (Exception e) {
            log.warn("[ClickHouse] Top N 查询失败: {}", e.getMessage());
            throw new RuntimeException("ClickHouse Top N 查询失败", e);
        }
    }

    private LocalDate getLatestTradingDateFromClickHouse(LocalDate start, LocalDate end) {
        String sql = "SELECT MAX(trade_date) FROM stock_daily WHERE trade_date >= ? AND trade_date <= ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, start.toString());
            stmt.setString(2, end.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Date d = rs.getDate(1);
                    return d != null ? d.toLocalDate() : null;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("ClickHouse 最新交易日查询失败", e);
        }
        return null;
    }

    private List<LocalDate> getTradingDatesFromClickHouse(LocalDate start, LocalDate end) {
        String sql = "SELECT DISTINCT trade_date FROM stock_daily WHERE trade_date >= ? AND trade_date <= ? ORDER BY trade_date";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, start.toString());
            stmt.setString(2, end.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                List<LocalDate> result = new ArrayList<>();
                while (rs.next()) result.add(rs.getDate("trade_date").toLocalDate());
                return result;
            }
        } catch (Exception e) {
            throw new RuntimeException("ClickHouse 交易日列表查询失败", e);
        }
    }

    private List<String> getRecentTradingDatesFromClickHouse(int limit) {
        String sql = "SELECT DISTINCT trade_date FROM stock_daily ORDER BY trade_date DESC LIMIT ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, Math.min(limit, 10000));
            try (ResultSet rs = stmt.executeQuery()) {
                List<String> result = new ArrayList<>();
                while (rs.next()) result.add(rs.getString("trade_date"));
                return result;
            }
        } catch (Exception e) {
            throw new RuntimeException("ClickHouse 最近交易日查询失败", e);
        }
    }

    private LocalDate getExtremeDateFromClickHouse(boolean isMax) {
        // 排除指数代码（sh.000xxx / sz.399xxx），只统计股票
        String sql = isMax
                ? "SELECT MAX(trade_date) FROM stock_daily WHERE code NOT LIKE 'sh.000%' AND code NOT LIKE 'sz.399%'"
                : "SELECT MIN(trade_date) FROM stock_daily WHERE code NOT LIKE 'sh.000%' AND code NOT LIKE 'sz.399%'";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                Date d = rs.getDate(1);
                return d != null ? d.toLocalDate() : null;
            }
        } catch (Exception e) {
            throw new RuntimeException("ClickHouse 极端日期查询失败", e);
        }
        return null;
    }

    private Set<String> getExistingCodesFromClickHouse(LocalDate date, Collection<String> codes) {
        Set<String> result = new HashSet<>();
        List<String> codeList = new ArrayList<>(codes);
        int batchSize = 500;

        // 批量查询，避免超长 IN clause
        for (int i = 0; i < codeList.size(); i += batchSize) {
            List<String> batch = codeList.subList(i, Math.min(i + batchSize, codeList.size()));
            String placeholders = String.join(",", Collections.nCopies(batch.size(), "?"));
            String sql = "SELECT DISTINCT code FROM stock_daily WHERE trade_date = ? AND code IN (" + placeholders + ")";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                int paramIdx = 1;
                stmt.setString(paramIdx++, date.toString());
                for (String code : batch) {
                    stmt.setString(paramIdx++, code);
                }
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) result.add(rs.getString("code"));
                }
            } catch (Exception e) {
                throw new RuntimeException("ClickHouse existing codes 查询失败", e);
            }
        }
        return result;
    }

    private List<Map<String, Object>> queryForListFromClickHouse(String sql, Object... params) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                stmt.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = stmt.executeQuery()) {
                List<Map<String, Object>> result = new ArrayList<>();
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    for (int i = 1; i <= colCount; i++) {
                        row.put(meta.getColumnLabel(i), rs.getObject(i));
                    }
                    result.add(row);
                }
                return result;
            }
        } catch (Exception e) {
            throw new RuntimeException("ClickHouse 通用查询失败", e);
        }
    }

    private Object queryForObjectFromClickHouse(String sql, Object... params) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                stmt.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getObject(1);
            }
        } catch (Exception e) {
            throw new RuntimeException("ClickHouse 通用单值查询失败", e);
        }
        return null;
    }

    // ==================== MySQL 回退实现 ====================

    private List<StockDaily> getFromMySQL(String code, LocalDate startDate, LocalDate endDate) {
        LambdaQueryWrapper<StockDaily> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StockDaily::getCode, code)
                .ge(StockDaily::getTradeDate, startDate)
                .le(StockDaily::getTradeDate, endDate)
                .orderByAsc(StockDaily::getTradeDate);
        return stockDailyMapper.selectList(wrapper);
    }

    private List<StockDaily> getBatchFromMySQL(List<String> codes, LocalDate startDate, LocalDate endDate) {
        LambdaQueryWrapper<StockDaily> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(StockDaily::getCode, codes)
                .ge(StockDaily::getTradeDate, startDate)
                .le(StockDaily::getTradeDate, endDate)
                .orderByAsc(StockDaily::getCode, StockDaily::getTradeDate);
        return stockDailyMapper.selectList(wrapper);
    }

    private List<StockDaily> getDailyByDateFromMySQL(LocalDate date, Collection<String> excludeNames) {
        LambdaQueryWrapper<StockDaily> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StockDaily::getTradeDate, date);
        if (excludeNames != null && !excludeNames.isEmpty()) {
            wrapper.notIn(StockDaily::getName, excludeNames);
        }
        wrapper.orderByAsc(StockDaily::getCode);
        return stockDailyMapper.selectList(wrapper);
    }

    private Map<String, Object> getCrossSectionPagedFromMySQL(LocalDate date, int page, int size,
                                                              String keyword, String sortField, String sortOrder) {
        String sortClause = buildOrderByClause(sortField, sortOrder);

        // 总数查询
        LambdaQueryWrapper<StockDaily> countWrapper = new LambdaQueryWrapper<>();
        countWrapper.eq(StockDaily::getTradeDate, date);
        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim();
            countWrapper.and(w -> w.like(StockDaily::getCode, kw)
                    .or().like(StockDaily::getName, kw));
        }
        Long total = stockDailyMapper.selectCount(countWrapper);

        // 分页查询（含排序）
        LambdaQueryWrapper<StockDaily> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StockDaily::getTradeDate, date);
        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(StockDaily::getCode, kw)
                    .or().like(StockDaily::getName, kw));
        }
        int offset = (page - 1) * size;
        wrapper.last(sortClause + String.format(" LIMIT %d OFFSET %d", size, offset));

        List<StockDaily> records = stockDailyMapper.selectList(wrapper);

        Map<String, Object> result = new HashMap<>();
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        result.put("totalPages", (total + size - 1) / size);
        result.put("data", records);
        return result;
    }

    private Map<String, Object> getOverviewStatsFromMySQL(LocalDate tradeDate) {
        return stockDailyMapper.selectOverviewStats(tradeDate);
    }

    private List<Map<String, Object>> getTopByPctChgFromMySQL(LocalDate tradeDate, int limit, String order) {
        return stockDailyMapper.selectTopByPctChg(tradeDate, limit, order);
    }

    private LocalDate getLatestTradingDateFromMySQL(LocalDate start, LocalDate end) {
        LambdaQueryWrapper<StockDaily> wrapper = new LambdaQueryWrapper<>();
        wrapper.between(StockDaily::getTradeDate, start, end)
                .select(StockDaily::getTradeDate)
                .orderByDesc(StockDaily::getTradeDate)
                .last("LIMIT 1");
        List<StockDaily> result = stockDailyMapper.selectList(wrapper);
        return result.isEmpty() ? null : result.get(0).getTradeDate();
    }

    private List<LocalDate> getTradingDatesFromMySQL(LocalDate start, LocalDate end) {
        LambdaQueryWrapper<StockDaily> wrapper = new LambdaQueryWrapper<>();
        wrapper.between(StockDaily::getTradeDate, start, end)
                .select(StockDaily::getTradeDate)
                .groupBy(StockDaily::getTradeDate)
                .orderByAsc(StockDaily::getTradeDate);
        return stockDailyMapper.selectList(wrapper).stream()
                .map(StockDaily::getTradeDate)
                .toList();
    }

    private List<String> getRecentTradingDatesFromMySQL(int limit) {
        LambdaQueryWrapper<StockDaily> wrapper = new LambdaQueryWrapper<>();
        wrapper.groupBy(StockDaily::getTradeDate)
                .orderByDesc(StockDaily::getTradeDate)
                .last("LIMIT " + Math.min(limit, 10000))
                .select(StockDaily::getTradeDate);
        return stockDailyMapper.selectList(wrapper).stream()
                .map(d -> d.getTradeDate().toString())
                .toList();
    }

    private LocalDate getExtremeDateFromMySQL(boolean isMax) {
        // 排除指数代码（sh.000xxx / sz.399xxx），只统计股票
        LambdaQueryWrapper<StockDaily> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(StockDaily::getTradeDate);
        wrapper.apply("code NOT LIKE 'sh.000%' AND code NOT LIKE 'sz.399%'");
        if (isMax) wrapper.orderByDesc(StockDaily::getTradeDate);
        else wrapper.orderByAsc(StockDaily::getTradeDate);
        wrapper.last("LIMIT 1");
        List<StockDaily> result = stockDailyMapper.selectList(wrapper);
        return result.isEmpty() ? null : result.get(0).getTradeDate();
    }

    private Set<String> getExistingCodesFromMySQL(LocalDate date, Collection<String> codes) {
        if (codes.isEmpty()) return Set.of();
        LambdaQueryWrapper<StockDaily> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StockDaily::getTradeDate, date)
                .in(StockDaily::getCode, codes)
                .select(StockDaily::getCode);
        return stockDailyMapper.selectList(wrapper).stream()
                .map(StockDaily::getCode)
                .collect(java.util.stream.Collectors.toSet());
    }

    private List<Map<String, Object>> queryForListFromMySQL(String sql, Object... params) {
        // MySQL 回退通过 JdbcTemplate 不可用（这里 Service 不注入 JdbcTemplate），
        // 通过 stockDailyMapper 无法执行任意 SQL，返回空列表
        log.warn("[MySQL回退] 无法执行通用 SQL，请使用 JdbcTemplate 直接查询");
        return List.of();
    }

    private Object queryForObjectFromMySQL(String sql, Object... params) {
        log.warn("[MySQL回退] 无法执行通用 SQL，请使用 JdbcTemplate 直接查询");
        return null;
    }

    // ==================== 通用辅助方法 ====================

    private String buildOrderByClause(String sortField, String sortOrder) {
        if (sortField == null || sortField.isEmpty()) {
            return "ORDER BY change_percent DESC";
        }
        boolean asc = !"desc".equalsIgnoreCase(sortOrder);
        String col = switch (sortField) {
            case "pctChg" -> "change_percent";
            case "amount" -> "amount";
            case "vol" -> "volume";
            case "close" -> "close_price";
            case "turnoverRate" -> "turnover_rate";
            default -> "code";
        };
        return "ORDER BY " + col + (asc ? " ASC" : " DESC");
    }


    /**
     * 执行 ClickHouse 查询
     */
    private List<StockDaily> executeQuery(String sql, LocalDate startDate, LocalDate endDate) {
        List<StockDaily> result = new ArrayList<>();

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            int paramIndex = 1;
            if (sql.contains("code = ?")) {
                paramIndex = 1;
            }
            stmt.setString(paramIndex, startDate.toString());
            stmt.setString(paramIndex + 1, endDate.toString());

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(convertResultSet(rs));
                }
            }
        } catch (Exception e) {
            log.warn("[ClickHouse] 查询失败: {}", e.getMessage());
            throw new RuntimeException("ClickHouse 查询失败", e);
        }

        return result;
    }

    /**
     * 执行 ClickHouse 查询（带 code 参数）
     */
    private List<StockDaily> executeQuery(String sql, String code, LocalDate startDate, LocalDate endDate) {
        List<StockDaily> result = new ArrayList<>();

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, code);
            stmt.setString(2, startDate.toString());
            stmt.setString(3, endDate.toString());

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(convertResultSet(rs));
                }
            }
        } catch (Exception e) {
            log.warn("[ClickHouse] 查询失败: {}", e.getMessage());
            throw new RuntimeException("ClickHouse 查询失败", e);
        }

        return result;
    }

    /**
     * 转换 ResultSet 为 StockDaily
     */
    private StockDaily convertResultSet(ResultSet rs) throws SQLException {
        StockDaily daily = new StockDaily();
        daily.setCode(rs.getString("code"));
        daily.setTradeDate(rs.getDate("trade_date").toLocalDate());

        String name = rs.getString("name");
        daily.setName(name);

        setDouble(rs, "open_price", daily::setOpenPrice);
        setDouble(rs, "close_price", daily::setClosePrice);
        setDouble(rs, "high_price", daily::setHighPrice);
        setDouble(rs, "low_price", daily::setLowPrice);
        setDouble(rs, "pre_close", daily::setPreClose);
        setLong(rs, "volume", daily::setVolume);
        setDouble(rs, "amount", daily::setAmount);
        setDouble(rs, "change_percent", daily::setChangePercent);
        setDouble(rs, "change_amount", daily::setChangeAmount);
        setDouble(rs, "turnover_rate", daily::setTurnoverRate);
        setDouble(rs, "pe_ttm", daily::setPeTtm);
        setDouble(rs, "pb", daily::setPb);

        return daily;
    }

    private void setDouble(ResultSet rs, String col, java.util.function.Consumer<BigDecimal> setter) throws SQLException {
        double val = rs.getDouble(col);
        if (!rs.wasNull()) {
            setter.accept(BigDecimal.valueOf(val));
        }
    }

    private void setLong(ResultSet rs, String col, java.util.function.Consumer<Long> setter) throws SQLException {
        long val = rs.getLong(col);
        if (!rs.wasNull()) {
            setter.accept(val);
        }
    }

    /**
     * 获取 ClickHouse 连接
     */
    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(clickHouseConfig.getJdbcUrl());
    }

    /**
     * 执行 DDL/DML 语句（如 ALTER TABLE DELETE）
     */
    public void executeDdl(String sql) throws SQLException {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    /**
     * 通用查询（Statement 模式，避免 PreparedStatement 空结果问题）
     * 返回每行为 Map<String, Object> 的列表，key 为列名小写
     * 异常时静默返回空列表，与 Spring JdbcTemplate 行为一致
     */
    public List<Map<String, Object>> queryForList(String sql) {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            java.sql.ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= colCount; i++) {
                    row.put(meta.getColumnLabel(i).toLowerCase(), rs.getObject(i));
                }
                result.add(row);
            }
        } catch (Exception e) {
            log.warn("[ClickHouse] queryForList 失败: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 通用标量查询（Statement 模式），返回单行单列，异常时返回 null
     */
    public String queryForString(String sql) {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) return rs.getString(1);
        } catch (Exception e) {
            log.warn("[ClickHouse] queryForString 失败: {}", e.getMessage());
        }
        return null;
    }

    // ==================== 写入方法 ====================

    /**
     * 写入单条日线数据到 ClickHouse
     */
    public void writeStockDaily(StockDaily daily) {
        if (!clickHouseConfig.isEnabled()) return;

        String sql = """
                INSERT INTO stock_daily
                (code, trade_date, name, open_price, close_price, high_price, low_price,
                 pre_close, volume, amount, change_percent, change_amount,
                 turnover_rate, pe_ttm, pb)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, daily.getCode());
            stmt.setString(2, daily.getTradeDate().toString());
            stmt.setString(3, daily.getName());
            setParam(stmt, 4, daily.getOpenPrice());
            setParam(stmt, 5, daily.getClosePrice());
            setParam(stmt, 6, daily.getHighPrice());
            setParam(stmt, 7, daily.getLowPrice());
            setParam(stmt, 8, daily.getPreClose());
            setLongParam(stmt, 9, daily.getVolume());
            setParam(stmt, 10, daily.getAmount());
            setParam(stmt, 11, daily.getChangePercent());
            setParam(stmt, 12, daily.getChangeAmount());
            setParam(stmt, 13, daily.getTurnoverRate());
            setParam(stmt, 14, daily.getPeTtm());
            setParam(stmt, 15, daily.getPb());

            stmt.executeUpdate();
        } catch (Exception e) {
            log.warn("[ClickHouse] 写入失败: {}", e.getMessage());
        }
    }

    /**
     * 批量写入日线数据到 ClickHouse
     */
    public void writeStockDailyBatch(List<StockDaily> dailies) {
        if (!clickHouseConfig.isEnabled() || dailies.isEmpty()) return;

        String sql = """
                INSERT INTO stock_daily
                (code, trade_date, name, open_price, close_price, high_price, low_price,
                 pre_close, volume, amount, change_percent, change_amount,
                 turnover_rate, pe_ttm, pb)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            for (StockDaily daily : dailies) {
                stmt.setString(1, daily.getCode());
                stmt.setString(2, daily.getTradeDate().toString());
                stmt.setString(3, daily.getName());
                setParam(stmt, 4, daily.getOpenPrice());
                setParam(stmt, 5, daily.getClosePrice());
                setParam(stmt, 6, daily.getHighPrice());
                setParam(stmt, 7, daily.getLowPrice());
                setParam(stmt, 8, daily.getPreClose());
                setLongParam(stmt, 9, daily.getVolume());
                setParam(stmt, 10, daily.getAmount());
                setParam(stmt, 11, daily.getChangePercent());
                setParam(stmt, 12, daily.getChangeAmount());
                setParam(stmt, 13, daily.getTurnoverRate());
                setParam(stmt, 14, daily.getPeTtm());
                setParam(stmt, 15, daily.getPb());
                stmt.addBatch();
            }

            stmt.executeBatch();
            log.debug("[ClickHouse] 批量写入 {} 条记录", dailies.size());
        } catch (Exception e) {
            log.warn("[ClickHouse] 批量写入失败: {}", e.getMessage());
        }
    }

    private void setParam(PreparedStatement stmt, int index, BigDecimal value) throws SQLException {
        if (value != null) {
            stmt.setDouble(index, value.doubleValue());
        } else {
            stmt.setNull(index, Types.DOUBLE);
        }
    }

    private void setLongParam(PreparedStatement stmt, int index, Long value) throws SQLException {
        if (value != null) {
            stmt.setLong(index, value);
        } else {
            stmt.setNull(index, Types.BIGINT);
        }
    }

    // ============================================================
    // 历史波动率（用于尾部风险动态计算）
    // ============================================================

    /**
     * 计算个股历史年化波动率
     * 取最近 300 个交易日的日收益率，计算 stddev × √252
     * @return 年化波动率（小数形式，如 0.25 = 25%），失败返回 null
     */
    public Double getHistoricalVolatility(String code) {
        if (!clickHouseConfig.isEnabled()) {
            log.debug("[ClickHouse] disabled，跳过波动率计算");
            return null;
        }
        String chCode = normalizeCodeForCH(code);
        // CH SQL: 用 lagInFrame 窗口函数取前一交易日收盘价，计算日收益率，再年化
        // ⚠️ neighbor() 在 CH v26+ 已被移除，改用 lagInFrame()
        // 修复 SQL 注入 - 使用 PreparedStatement 参数化查询
        String sql = """
                WITH daily_ret AS (
                    SELECT
                        close_price / nullIf(lagInFrame(close_price, 1) OVER (ORDER BY trade_date), 0) - 1 AS ret
                    FROM stock_daily
                    WHERE code = ? AND trade_date >= today() - 400
                )
                SELECT stddevPop(ret) * sqrt(252) AS annual_vol
                FROM daily_ret
                WHERE ret IS NOT NULL AND abs(ret) < 0.25
                """;
        try (Connection conn = this.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, chCode);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    double vol = rs.getDouble(1);
                    if (!rs.wasNull()) {
                        log.debug("[ClickHouse] {} 年化波动率: {}%", code, String.format("%.1f", vol * 100));
                        return vol;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[ClickHouse] 波动率计算失败({}): {}", code, e.getMessage());
        }
        return null;
    }

    /**
     * 将 A 股代码转为 CH 存储格式（无前缀无后缀，如 600519）
     */
    private String normalizeCodeForCH(String code) {
        if (code == null) return null;
        String c = code.trim().toLowerCase();
        // 去掉 sh/sz/bj 前缀
        if (c.matches("^(sh|sz|bj)\\d+")) {
            return c.substring(2);
        }
        // 去掉 .SH/.SZ/.BJ 后缀
        if (c.matches("^\\d+\\.(sh|sz|bj)$")) {
            return c.substring(0, c.indexOf('.'));
        }
        return c;
    }
}
