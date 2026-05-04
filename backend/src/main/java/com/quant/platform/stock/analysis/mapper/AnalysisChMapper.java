package com.quant.platform.stock.analysis.mapper;

import com.quant.platform.stock.analysis.domain.DailyBarRow;
import com.quant.platform.stock.analysis.domain.TechSignal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 分析模块 ClickHouse 查询 Mapper
 * 仅当 quant.clickhouse.enabled=true 时加载
 */
@Slf4j
@Repository
@ConditionalOnProperty(name = "quant.clickhouse.enabled", havingValue = "true")
public class AnalysisChMapper {
    
    private final JdbcTemplate clickHouseJdbcTemplate;
    
    public AnalysisChMapper(@Qualifier("clickHouseJdbcTemplate") JdbcTemplate clickHouseJdbcTemplate) {
        this.clickHouseJdbcTemplate = clickHouseJdbcTemplate;
    }

    /**
     * 将前端传入的短代码（如 600619）规范为带后缀格式（如 600619.SH）
     * stock_daily / factor_value 的 code 字段存的是带后缀格式
     */
    private String normalizeCode(String code) {
        if (code == null) return null;
        String c = code.trim();
        if (c.contains(".")) return c;
        if (c.startsWith("6") || c.startsWith("5")) return c + ".SH";
        if (c.startsWith("0") || c.startsWith("3") || c.startsWith("2")) return c + ".SZ";
        if (c.startsWith("8") || c.startsWith("4")) return c + ".BJ";
        return c;
    }

    /**
     * ClickHouse stock_daily 表的 code 字段存的是【无后缀】格式（如 600519）
     * 此方法用于查询 stock_daily 时规范化代码
     */
    private String normalizeCodeForDaily(String code) {
        if (code == null) return null;
        String c = code.trim();
        // 如果带后缀，去掉后缀
        if (c.contains(".")) return c.split("\\.")[0];
        return c;
    }

    /**
     * 查询缠论因子（从 factor_value 表）
     * 返回每个因子各自最新交易日的缠论信号
     * 使用 argMax(factor_val, calc_date) 获取每个因子最新日期的值
     * CH 列名: factor_code/symbol/calc_date/factor_val
     * 
     * symbol 格式兼容：旧数据带后缀(600619.SH)，新数据无后缀(600619)
     */
    public TechSignal selectLatestTechSignal(String code) {
        String withSuffix = normalizeCode(code);
        String noSuffix = normalizeCodeForDaily(code);

        String sql = """
            SELECT
                '%s' as code,
                MAX(calc_date) as trade_date,
                argMax(CASE WHEN factor_code = 'CHAN_PEN_DIR' THEN factor_val END, calc_date) as pen_dir,
                argMax(CASE WHEN factor_code = 'CHAN_TREND' THEN factor_val END, calc_date) as trend,
                argMax(CASE WHEN factor_code = 'CHAN_BUY_SELL' THEN factor_val END, calc_date) as chan_signal,
                argMax(CASE WHEN factor_code = 'CHAN_HUB_POS' THEN factor_val END, calc_date) as hub_pos,
                argMax(CASE WHEN factor_code = 'CHAN_PEN_COUNT' THEN factor_val END, calc_date) as pen_count
            FROM stock.factor_value
            WHERE (symbol = ? OR symbol = ?)
              AND factor_code IN ('CHAN_PEN_DIR','CHAN_TREND','CHAN_BUY_SELL','CHAN_HUB_POS','CHAN_PEN_COUNT')
            GROUP BY '%s'
            """.formatted(noSuffix, noSuffix);

        try {
            List<TechSignal> results = clickHouseJdbcTemplate.query(sql,
                    new BeanPropertyRowMapper<>(TechSignal.class), withSuffix, noSuffix);
            if (!results.isEmpty()) {
                return results.get(0);
            }
        } catch (Exception e) {
            log.error("查询缠论因子失败: code={}, error={}", code, e.getMessage());
        }
        return null;
    }

    /**
     * 查询股票最新日线数据（价格、涨跌幅等）
     * 用于填充 AnalysisOverview 的 price / changePercent
     */
    public java.util.Map<String, Object> selectLatestDailyBar(String code) {
        String sql = """
            SELECT
                close_price,
                change_percent,
                pe_ttm,
                pb
            FROM stock.stock_daily
            WHERE code = ?
            ORDER BY trade_date DESC
            LIMIT 1
            """;
        try {
            String normalized = normalizeCodeForDaily(code);
            return clickHouseJdbcTemplate.queryForObject(sql,
                    (rs, rowNum) -> {
                        java.util.Map<String, Object> map = new java.util.HashMap<>();
                        map.put("close_price", rs.getBigDecimal("close_price"));
                        map.put("change_percent", rs.getBigDecimal("change_percent"));
                        map.put("pe_ttm", rs.getBigDecimal("pe_ttm"));
                        map.put("pb", rs.getBigDecimal("pb"));
                        return map;
                    }, normalized);
        } catch (Exception e) {
            log.warn("查询最新日线失败: code={}, error={}", code, e.getMessage());
            return null;
        }
    }
    
    /**
     * 查询日线数据（用于计算量比、换手率偏离）
     * 获取最近N日数据
     * CH 不支持参数化 LIMIT，改为在 SQL 中直接用子查询限定日期范围
     */
    public List<DailyBarRow> selectRecentDailyBars(String code, int days) {
        // ClickHouse 用 subtractDays() 替代 MySQL 的 DATE_SUB()
        String sql = """
            SELECT
                code, trade_date, open_price, close_price, high_price, low_price,
                pre_close, volume, amount, change_percent, turnover_rate, pe_ttm, pb
            FROM stock.stock_daily
            WHERE code = ?
              AND trade_date >= (SELECT subtractDays(MAX(trade_date), ?) FROM stock.stock_daily WHERE code = ?)
            ORDER BY trade_date
            """;
        try {
            String normalized = normalizeCodeForDaily(code);
            return clickHouseJdbcTemplate.query(sql,
                    new BeanPropertyRowMapper<>(DailyBarRow.class), normalized, days, normalized);
        } catch (Exception e) {
            log.warn("查询日线数据失败: code={}, error={}", code, e.getMessage());
            return new ArrayList<>();
        }
    }
    
    /**
     * 查询最新 RSI（从 factor_value 表，CH 列名）
     * symbol 格式兼容：旧数据带后缀(600619.SH)，新数据无后缀(600619)
     */
    public BigDecimal selectLatestRsi(String code) {
        // 数据库中实际 factor_code 为 RSI5 / RSI14，此处用 RSI14（个股分析常用）
        String sql = """
            SELECT factor_val AS rsi FROM stock.factor_value
            WHERE (symbol = ? OR symbol = ?) AND factor_code = 'RSI14'
              AND calc_date = (SELECT MAX(calc_date) FROM stock.factor_value WHERE (symbol = ? OR symbol = ?) AND factor_code = 'RSI14')
            LIMIT 1
            """;
        try {
            String withSuffix = normalizeCode(code);
            String noSuffix = normalizeCodeForDaily(code);
            BigDecimal rsi = clickHouseJdbcTemplate.queryForObject(sql, BigDecimal.class, withSuffix, noSuffix, withSuffix, noSuffix);
            return rsi;
        } catch (Exception e) {
            log.warn("查询RSI失败: code={}, error={}", code, e.getMessage());
            return null;
        }
    }

    /**
     * 计算20日涨跌幅（用于判断强势股）
     * 返回百分比，如 15.5 表示涨15.5%
     */
    public BigDecimal select20dReturn(String code) {
        String sql = """
            SELECT
                (latest.close_price - old.close_price) / old.close_price * 100 AS ret_20d
            FROM (
                SELECT close_price FROM stock.stock_daily
                WHERE code = ? ORDER BY trade_date DESC LIMIT 1
            ) AS latest,
            (
                SELECT close_price FROM stock.stock_daily
                WHERE code = ? ORDER BY trade_date DESC LIMIT 1 OFFSET 20
            ) AS old
            """;
        try {
            String normalized = normalizeCodeForDaily(code);
            return clickHouseJdbcTemplate.queryForObject(sql, BigDecimal.class, normalized, normalized);
        } catch (Exception e) {
            log.warn("查询20日涨跌幅失败: code={}, error={}", code, e.getMessage());
            return null;
        }
    }
}
