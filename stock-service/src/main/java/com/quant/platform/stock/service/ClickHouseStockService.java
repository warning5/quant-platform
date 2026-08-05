package com.quant.platform.stock.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quant.platform.config.ClickHouseConfig;
import com.quant.platform.stock.entity.StockDaily;
import com.quant.platform.stock.mapper.StockDailyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.sql.Date;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import static com.quant.platform.stock.service.StockDailySqlSupport.*;

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
    private final ClickHouseJdbcClient chJdbcClient;
    private final StockDailyChWriter chWriter;
    private final StockDailyChQuery chQuery;
    private final StockDailyMysqlFallback chFallback;

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
        } catch (ClickHouseQueryException e) {
            log.warn("[ClickHouse] 指数查询失败(index_daily)，回退到 MySQL: {}", e.getMessage(), e);
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
        } catch (SQLException e) {
            log.warn("[ClickHouse] 批量指数查询失败: {}", e.getMessage(), e);
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
        } catch (ClickHouseQueryException e) {
            log.warn("[ClickHouse] 查询失败，回退到 MySQL: {}", e.getMessage(), e);
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
        } catch (ClickHouseQueryException e) {
            log.warn("[ClickHouse] 批量查询失败，回退到 MySQL: {}", e.getMessage(), e);
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
        } catch (ClickHouseQueryException e) {
            log.warn("[ClickHouse] 截面查询失败，回退到 MySQL: {}", e.getMessage(), e);
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
        } catch (ClickHouseQueryException e) {
            log.warn("[ClickHouse] 截面分页查询失败，回退到 MySQL: {}", e.getMessage(), e);
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
        } catch (ClickHouseQueryException e) {
            log.warn("[ClickHouse] 概览统计失败，回退到 MySQL: {}", e.getMessage(), e);
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
        } catch (ClickHouseQueryException e) {
            log.warn("[ClickHouse] Top N 查询失败，回退到 MySQL: {}", e.getMessage(), e);
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
        } catch (ClickHouseQueryException e) {
            log.warn("[ClickHouse] 最新交易日查询失败，回退到 MySQL: {}", e.getMessage(), e);
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
        } catch (ClickHouseQueryException e) {
            log.warn("[ClickHouse] 交易日列表查询失败，回退到 MySQL: {}", e.getMessage(), e);
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
        } catch (ClickHouseQueryException e) {
            log.warn("[ClickHouse] 最近交易日查询失败，回退到 MySQL: {}", e.getMessage(), e);
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
        } catch (SQLException e) {
            log.warn("[ClickHouse] 总记录数查询失败，回退到 MySQL: {}", e.getMessage(), e);
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
        } catch (SQLException e) {
            log.warn("[ClickHouse] 按前缀统计失败，回退到 MySQL: {}", e.getMessage(), e);
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
        } catch (ClickHouseQueryException e) {
            log.warn("[ClickHouse] 获取最新交易日失败，回退到 MySQL: {}", e.getMessage(), e);
        }
        return getExtremeDateFromMySQL(true);
    }

    public LocalDate getEarliestTradeDate() {
        if (!clickHouseConfig.isEnabled()) {
            return getExtremeDateFromMySQL(false);
        }
        try {
            return getExtremeDateFromClickHouse(false);
        } catch (ClickHouseQueryException e) {
            log.warn("[ClickHouse] 获取最早交易日失败，回退到 MySQL: {}", e.getMessage(), e);
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
        } catch (SQLException e) {
            log.warn("[ClickHouse] distinct count 查询失败，回退到 MySQL: {}", e.getMessage(), e);
        }

        // MySQL 回退
        LambdaQueryWrapper<StockDaily> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StockDaily::getTradeDate, date);
        applyPrefixesToWrapper(wrapper, prefixes);
        List<StockDaily> list = stockDailyMapper.selectList(wrapper);
        return list.stream().map(StockDaily::getCode).distinct().count();
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
        } catch (ClickHouseQueryException e) {
            log.warn("[ClickHouse] existing codes 查询失败，回退到 MySQL: {}", e.getMessage(), e);
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
        } catch (ClickHouseQueryException e) {
            String rootCause = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            log.warn("[ClickHouse] 通用查询失败，回退到 MySQL: {} | SQL: {}", rootCause, sql, e);
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
        } catch (ClickHouseQueryException e) {
            String rootCause = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            log.warn("[ClickHouse] 通用单值查询失败，回退到 MySQL: {} | SQL: {}", rootCause, sql, e);
        }

        return queryForObjectFromMySQL(sql, params);
    }

    // ==================== ClickHouse 查询实现 ====================

    private List<StockDaily> queryFromClickHouse(String code, LocalDate startDate, LocalDate endDate) {
        return chQuery.queryFromClickHouse(code, startDate, endDate);
    }

    private List<StockDaily> queryBatchFromClickHouse(List<String> codes, LocalDate startDate, LocalDate endDate) {
        return chQuery.queryBatchFromClickHouse(codes, startDate, endDate);
    }

    private List<StockDaily> queryBatchFromClickHouse(List<String> codes, LocalDate startDate, LocalDate endDate, boolean useFinal) {
        return chQuery.queryBatchFromClickHouse(codes, startDate, endDate, useFinal);
    }

    private List<StockDaily> queryDailyByDateFromClickHouse(LocalDate date, Collection<String> excludeNames) {
        return chQuery.queryDailyByDateFromClickHouse(date, excludeNames);
    }

    private Map<String, Object> getCrossSectionPagedFromClickHouse(LocalDate date, int page, int size, String keyword, String sortField, String sortOrder) {
        return chQuery.getCrossSectionPagedFromClickHouse(date, page, size, keyword, sortField, sortOrder);
    }

    private Map<String, Object> getOverviewStatsFromClickHouse(LocalDate tradeDate) {
        return chQuery.getOverviewStatsFromClickHouse(tradeDate);
    }

    private List<Map<String, Object>> getTopByPctChgFromClickHouse(LocalDate tradeDate, int limit, String order) {
        return chQuery.getTopByPctChgFromClickHouse(tradeDate, limit, order);
    }

    private LocalDate getLatestTradingDateFromClickHouse(LocalDate start, LocalDate end) {
        return chQuery.getLatestTradingDateFromClickHouse(start, end);
    }

    private List<LocalDate> getTradingDatesFromClickHouse(LocalDate start, LocalDate end) {
        return chQuery.getTradingDatesFromClickHouse(start, end);
    }

    private List<String> getRecentTradingDatesFromClickHouse(int limit) {
        return chQuery.getRecentTradingDatesFromClickHouse(limit);
    }

    private LocalDate getExtremeDateFromClickHouse(boolean isMax) {
        return chQuery.getExtremeDateFromClickHouse(isMax);
    }

    private Set<String> getExistingCodesFromClickHouse(LocalDate date, Collection<String> codes) {
        return chQuery.getExistingCodesFromClickHouse(date, codes);
    }

    private List<Map<String, Object>> queryForListFromClickHouse(String sql, Object... params) {
        return chQuery.queryForListFromClickHouse(sql, params);
    }

    private Object queryForObjectFromClickHouse(String sql, Object... params) {
        return chQuery.queryForObjectFromClickHouse(sql, params);
    }

    // ==================== MySQL 回退实现 ====================

    private List<StockDaily> getFromMySQL(String code, LocalDate startDate, LocalDate endDate) {
        return chFallback.getFromMySQL(code, startDate, endDate);
    }

    private List<StockDaily> getBatchFromMySQL(List<String> codes, LocalDate startDate, LocalDate endDate) {
        return chFallback.getBatchFromMySQL(codes, startDate, endDate);
    }

    private List<StockDaily> getDailyByDateFromMySQL(LocalDate date, Collection<String> excludeNames) {
        return chFallback.getDailyByDateFromMySQL(date, excludeNames);
    }

    private Map<String, Object> getCrossSectionPagedFromMySQL(LocalDate date, int page, int size, String keyword, String sortField, String sortOrder) {
        return chFallback.getCrossSectionPagedFromMySQL(date, page, size, keyword, sortField, sortOrder);
    }

    private Map<String, Object> getOverviewStatsFromMySQL(LocalDate tradeDate) {
        return chFallback.getOverviewStatsFromMySQL(tradeDate);
    }

    private List<Map<String, Object>> getTopByPctChgFromMySQL(LocalDate tradeDate, int limit, String order) {
        return chFallback.getTopByPctChgFromMySQL(tradeDate, limit, order);
    }

    private LocalDate getLatestTradingDateFromMySQL(LocalDate start, LocalDate end) {
        return chFallback.getLatestTradingDateFromMySQL(start, end);
    }

    private List<LocalDate> getTradingDatesFromMySQL(LocalDate start, LocalDate end) {
        return chFallback.getTradingDatesFromMySQL(start, end);
    }

    private List<String> getRecentTradingDatesFromMySQL(int limit) {
        return chFallback.getRecentTradingDatesFromMySQL(limit);
    }

    private LocalDate getExtremeDateFromMySQL(boolean isMax) {
        return chFallback.getExtremeDateFromMySQL(isMax);
    }

    private Set<String> getExistingCodesFromMySQL(LocalDate date, Collection<String> codes) {
        return chFallback.getExistingCodesFromMySQL(date, codes);
    }

    private List<Map<String, Object>> queryForListFromMySQL(String sql, Object... params) {
        return chFallback.queryForListFromMySQL(sql, params);
    }

    private Object queryForObjectFromMySQL(String sql, Object... params) {
        return chFallback.queryForObjectFromMySQL(sql, params);
    }

    // ==================== 通用辅助方法 ====================

    private List<StockDaily> executeQuery(String sql, LocalDate startDate, LocalDate endDate) {
        return chJdbcClient.executeQuery(sql, startDate, endDate);
    }

    private List<StockDaily> executeQuery(String sql, String code, LocalDate startDate, LocalDate endDate) {
        return chJdbcClient.executeQuery(sql, code, startDate, endDate);
    }

    private Connection getConnection() throws SQLException {
        return chJdbcClient.getConnection();
    }

    public void executeDdl(String sql) throws SQLException {
        chJdbcClient.executeDdl(sql);
    }

    public void executeDdlWithParams(String sql, Object[] params) throws SQLException {
        chJdbcClient.executeDdlWithParams(sql, params);
    }

    public List<Map<String, Object>> queryForList(String sql) {
        return chJdbcClient.queryForList(sql);
    }

    public String queryForString(String sql) {
        return chJdbcClient.queryForString(sql);
    }

    // ==================== 写入方法 ====================

    public void writeStockDaily(StockDaily daily) {
        chWriter.writeStockDaily(daily);
    }

    public void writeStockDailyBatch(List<StockDaily> dailies) {
        chWriter.writeStockDailyBatch(dailies);
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
        } catch (SQLException e) {
            log.warn("[ClickHouse] 波动率计算失败({}): {}", code, e.getMessage(), e);
        }
        return null;
    }
}
