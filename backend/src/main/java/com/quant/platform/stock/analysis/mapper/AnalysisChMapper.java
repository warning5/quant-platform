package com.quant.platform.stock.analysis.mapper;

import com.quant.platform.stock.analysis.domain.DailyBarRow;
import com.quant.platform.stock.analysis.domain.TechSignal;
import com.quant.platform.stock.entity.StockDaily;
import com.quant.platform.stock.service.ClickHouseStockService;
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
 *
 * 日线数据查询已统一委托给 ClickHouseStockService（不再直接 SQL 查 stock_daily）。
 * 本类只保留 factor_value / moneyflow 等分析专用表的查询。
 */
@Slf4j
@Repository
@ConditionalOnProperty(name = "quant.clickhouse.enabled", havingValue = "true")
public class AnalysisChMapper {
    
    private final JdbcTemplate clickHouseJdbcTemplate;
    private final ClickHouseStockService stockService;
    
    public AnalysisChMapper(@Qualifier("clickHouseJdbcTemplate") JdbcTemplate clickHouseJdbcTemplate,
                            ClickHouseStockService stockService) {
        this.clickHouseJdbcTemplate = clickHouseJdbcTemplate;
        this.stockService = stockService;
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

        // 注意：SELECT 里全是聚合函数(MAX/argMax)，不需要 GROUP BY，天然返回一行
        String sql = """
            SELECT
                '%s' as code,
                MAX(calc_date) as trade_date,
                argMax(CASE WHEN factor_code = 'CHAN_PEN_DIR' THEN factor_val END, calc_date) as pen_dir,
                argMax(CASE WHEN factor_code = 'CHAN_TREND' THEN factor_val END, calc_date) as trend,
                argMax(CASE WHEN factor_code = 'CHAN_BUY_SELL' THEN factor_val END, calc_date) as chan_signal,
                argMax(CASE WHEN factor_code = 'CHAN_HUB_POS' THEN factor_val END, calc_date) as hub_pos,
                argMax(CASE WHEN factor_code = 'CHAN_PEN_COUNT' THEN factor_val END, calc_date) as pen_count
            FROM stock.factor_value FINAL
            WHERE (symbol = ? OR symbol = ?)
              AND factor_code IN ('CHAN_PEN_DIR','CHAN_TREND','CHAN_BUY_SELL','CHAN_HUB_POS','CHAN_PEN_COUNT')
            """.formatted(noSuffix);

        try {
            // 不用 BeanPropertyRowMapper：CH 返回 Float64，
            // 但 TechSignal 的 penDir/trend/chanSignal/hubPos 是 String，
            // penCount 是 Integer，直接映射会失败 → 手写 RowMapper 做类型转换
            List<TechSignal> results = clickHouseJdbcTemplate.query(sql, (rs, rowNum) -> {
                TechSignal t = new TechSignal();
                t.setCode(noSuffix);
                Object td = rs.getObject("trade_date");
                if (td != null) {
                    try { t.setTradeDate(java.time.LocalDate.parse(td.toString())); }
                    catch (Exception ignored) {}
                }
                // CH 返回 Float64 → 转 String（CHAN_PEN_DIR/CHAN_TREND 等）
                Object pd = rs.getObject("pen_dir");
                t.setPenDir(pd != null ? String.valueOf((long) Math.round(((Number) pd).doubleValue())) : null);
                Object tr = rs.getObject("trend");
                t.setTrend(tr != null ? String.valueOf((long) Math.round(((Number) tr).doubleValue())) : null);
                Object cs = rs.getObject("chan_signal");
                t.setChanSignal(cs != null ? String.valueOf((long) Math.round(((Number) cs).doubleValue())) : null);
                Object hp = rs.getObject("hub_pos");
                t.setHubPos(hp != null ? String.valueOf(((Number) hp).doubleValue()) : null);
                Object pc = rs.getObject("pen_count");
                t.setPenCount(pc != null ? ((Number) pc).intValue() : null);
                return t;
            }, withSuffix, noSuffix);
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
     * 委托 ClickHouseStockService（统一查询层），不再直接 SQL
     */
    public java.util.Map<String, Object> selectLatestDailyBar(String code) {
        try {
            String noSuffix = normalizeCodeForDaily(code);
            LocalDate end = LocalDate.now();
            LocalDate start = end.minusDays(10);
            List<StockDaily> dailies = stockService.getStockDaily(noSuffix, start, end);
            if (dailies != null && !dailies.isEmpty()) {
                // 取最近一条（getStockDaily 已按 trade_date DESC 排序）
                StockDaily latest = dailies.get(0);
                java.util.Map<String, Object> map = new java.util.HashMap<>();
                map.put("close_price", latest.getClosePrice());
                map.put("change_percent", latest.getChangePercent());
                map.put("pe_ttm", latest.getPeTtm());
                map.put("pb", latest.getPb());
                return map;
            }
        } catch (Exception e) {
            log.warn("查询最新日线失败: code={}, error={}", code, e.getMessage());
        }
        return null;
    }
    
    /**
     * 查询日线数据（用于计算量比、换手率偏离、技术指标）
     * 委托 ClickHouseStockService（统一查询层）
     */
    public List<DailyBarRow> selectRecentDailyBars(String code, int days) {
        try {
            String noSuffix = normalizeCodeForDaily(code);
            LocalDate end = LocalDate.now();
            // 多取一些天数确保有足够交易日数据
            int calDays = (int) Math.ceil(days * 7.0 / 5) + 10;
            LocalDate start = end.minusDays(calDays);
            List<StockDaily> dailies = stockService.getStockDaily(noSuffix, start, end);

            if (dailies == null || dailies.isEmpty()) {
                return new ArrayList<>();
            }

            List<DailyBarRow> result = new ArrayList<>();
            for (StockDaily sd : dailies) {
                DailyBarRow row = new DailyBarRow();
                row.setCode(sd.getCode());
                row.setTradeDate(sd.getTradeDate());
                row.setOpenPrice(sd.getOpenPrice());
                row.setClosePrice(sd.getClosePrice());
                row.setHighPrice(sd.getHighPrice());
                row.setLowPrice(sd.getLowPrice());
                row.setPreClose(sd.getPreClose());
                row.setVolume(sd.getVolume() != null ? sd.getVolume().longValue() : null);
                row.setAmount(sd.getAmount());
                row.setChangePercent(sd.getChangePercent());
                row.setTurnoverRate(sd.getTurnoverRate());
                row.setPeTtm(sd.getPeTtm());
                row.setPb(sd.getPb());
                result.add(row);
            }
            return result;
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
            SELECT factor_val AS rsi FROM stock.factor_value FINAL
            WHERE (symbol = ? OR symbol = ?) AND factor_code = 'RSI14'
              AND calc_date = (SELECT MAX(calc_date) FROM stock.factor_value FINAL WHERE (symbol = ? OR symbol = ?) AND factor_code = 'RSI14')
            LIMIT 1
            """;
        try {
            String withSuffix = normalizeCode(code);
            String noSuffix = normalizeCodeForDaily(code);
            return clickHouseJdbcTemplate.queryForObject(sql, BigDecimal.class, withSuffix, noSuffix, withSuffix, noSuffix);
        } catch (Exception e) {
            log.warn("查询RSI失败: code={}, error={}", code, e.getMessage());
            return null;
        }
    }

    /**
     * 查询最新一日资金流向数据（从 stock_sentiment_moneyflow 表）
     * 返回：net_main, net_main_pct, net_huge, net_big
     */
    public java.util.Map<String, Object> selectLatestMoneyFlow(String code) {
        String sql = """
            SELECT net_main, net_main_pct, net_huge, net_big
            FROM stock.stock_sentiment_moneyflow FINAL             WHERE code = ?
            ORDER BY trade_date DESC
            LIMIT 1
            """;
        try {
            String normalized = normalizeCodeForDaily(code);
            return clickHouseJdbcTemplate.queryForObject(sql,
                    (rs, rowNum) -> {
                        java.util.Map<String, Object> map = new java.util.HashMap<>();
                        map.put("net_main", rs.getBigDecimal("net_main"));
                        map.put("net_main_pct", rs.getBigDecimal("net_main_pct"));
                        map.put("net_huge", rs.getBigDecimal("net_huge"));
                        map.put("net_big", rs.getBigDecimal("net_big"));
                        return map;
                    }, normalized);
        } catch (Exception e) {
            log.warn("查询资金流向失败: code={}, error={}", code, e.getMessage());
            return null;
        }
    }

    /**
     * 计算20日涨跌幅（用于判断强势股）
     * 委托 ClickHouseStockService 获取数据后内存计算
     */
    public BigDecimal select20dReturn(String code) {
        try {
            String noSuffix = normalizeCodeForDaily(code);
            LocalDate end = LocalDate.now();
            // 多取30天确保有足够交易日
            LocalDate start = end.minusDays(40);
            List<StockDaily> dailies = stockService.getStockDaily(noSuffix, start, end);
            if (dailies == null || dailies.isEmpty()) return null;
            // 取最新一条
            BigDecimal latestClose = dailies.get(0).getClosePrice();
            if (latestClose == null) return null;
            // 找第21条（约20个交易日前的收盘价）
            if (dailies.size() > 20) {
                BigDecimal oldClose = dailies.get(20).getClosePrice();
                if (oldClose != null && oldClose.compareTo(BigDecimal.ZERO) != 0) {
                    return latestClose.subtract(oldClose)
                            .divide(oldClose, 4, java.math.RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("100"));
                }
            }
        } catch (Exception e) {
            log.warn("查询20日涨跌幅失败: code={}, error={}", code, e.getMessage());
        }
        return null;
    }
}
