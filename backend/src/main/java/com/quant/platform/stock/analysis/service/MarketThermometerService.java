package com.quant.platform.stock.analysis.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 大盘温度计服务
 * 整合多维度市场情绪指标，计算综合恐慌贪婪指数
 */
@Slf4j
@Service
public class MarketThermometerService {

    private static final long BOND_CACHE_TTL_MS = 24 * 3600 * 1000L;
    private static final long THERM_CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final int THERM_CACHE_VERSION = 2; // 缓存版本号，代码变更后递增强制刷新
    /**
     * 10年国债收益率缓存，盘中不变，缓存24小时
     */
    private static volatile Double cachedBondYield = null;
    private static volatile long cachedBondYieldTime = 0;
    /**
     * 大盘温度计结果缓存，5分钟有效
     */
    private static volatile Map<String, Object> cachedThermometer = null;
    private static volatile long cachedThermometerTime = 0;
    @Autowired(required = false)
    @Qualifier("clickHouseJdbcTemplate")
    private JdbcTemplate chJdbc;

    /**
     * 获取大盘温度计全量数据（5分钟缓存）
     */
    public Map<String, Object> getThermometer() {
        long now = System.currentTimeMillis();
        if (cachedThermometer != null && (now - cachedThermometerTime) < THERM_CACHE_TTL_MS) {
            return cachedThermometer;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        LocalDate today = LocalDate.now();

        // 1. PE/PB 分位数
        Map<String, Object> pePb = calcPePbPercentile(today);
        result.put("pePercentile", pePb.get("pePercentile"));
        result.put("pbPercentile", pePb.get("pbPercentile"));
        result.put("pePercentileHistory", pePb.get("history"));
        result.put("pbPercentileHistory", pePb.get("pbHistory"));

        // 2. 均线温度（沪深300）
        Map<String, Object> maTemp = calcMaTemperature(today);
        result.put("maTemperature", maTemp.get("temperature"));  // 0-100
        result.put("maTrend", maTemp.get("trend"));              // 多头/空头/震荡
        result.put("maHistory", maTemp.get("history"));

        // 3. 股债收益比
        Double stockEarningYield = (Double) pePb.get("marketEarningYield");
        Map<String, Object> bond = calcStockBondRatio(today, stockEarningYield);
        result.put("stockBondRatio", bond.get("ratio"));          // 沪深300盈利收益率/10年国债
        result.put("bondYield10Y", bond.get("bondYield"));        // 10年国债收益率
        result.put("bondRatioHistory", bond.get("history"));

        // 4. 融资余额变化
        Map<String, Object> margin = calcMarginChange(today);
        result.put("marginChange", margin.get("change"));         // 近5日净变化%
        result.put("marginTrend", margin.get("trend"));          // 扩张/收缩/平稳
        result.put("marginHistory", margin.get("history"));

        // 5. 综合恐慌贪婪指数（0-100）
        double pePct = ((Number) result.get("pePercentile")).doubleValue();
        double pbPct = ((Number) result.get("pbPercentile")).doubleValue();
        double maScore = ((Number) result.get("maTemperature")).doubleValue();
        double bondScore = calcBondScore((Double) result.get("stockBondRatio"));
        double fearGreed = pePct * 0.30 + pbPct * 0.20 + maScore * 0.30 + bondScore * 0.20;
        result.put("fearGreedIndex", Math.round(fearGreed * 10) / 10.0);
        result.put("fearGreedLabel", getFearGreedLabel(fearGreed));

        // 6. 实际数据日期（取 stock_daily 最新交易日，非日历今天）
        result.put("tradeDate", pePb.getOrDefault("actualTradeDate", today.toString()).toString());
        result.put("updateTime", java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        // 缓存结果（带上版本号，前端或重启后可强制刷新）
        result.put("_cacheVersion", THERM_CACHE_VERSION);
        cachedThermometer = result;
        cachedThermometerTime = now;

        return result;
    }

    // ─── PE/PB 分位数计算 ───────────────────────────────────────────

    private Map<String, Object> calcPePbPercentile(LocalDate date) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (chJdbc == null) {
            result.put("pePercentile", 0.0);
            result.put("pbPercentile", 0.0);
            result.put("history", List.of());
            result.put("pbHistory", List.of());
            return result;
        }

        try {
            // 取最近交易日
            String tradeDate = chJdbc.queryForObject(
                    "SELECT MAX(trade_date) FROM stock.stock_daily FINAL WHERE trade_date <= '" + date + "'",
                    String.class);
            if (tradeDate == null) {
                result.put("pePercentile", 0.0);
                result.put("pbPercentile", 0.0);
                result.put("history", List.of());
                result.put("pbHistory", List.of());
                return result;
            }

            // 全市场 PE/PB 当前值（等权平均）
            String statsSql = String.format("""
                    SELECT avg(pe_ttm) as avg_pe, avg(pb) as avg_pb, avg(1/pe_ttm) as avg_earning_yield
                    FROM stock.stock_daily FINAL
                    WHERE trade_date = '%s' AND pe_ttm > 0 AND pe_ttm < 500 AND pb > 0 AND pb < 50
                    """, tradeDate);
            Map<String, Object> stats = chJdbc.queryForMap(statsSql);
            double avgPe = ((Number) stats.get("avg_pe")).doubleValue();
            double avgEarningYield = ((Number) stats.get("avg_earning_yield")).doubleValue();
            result.put("marketEarningYield", avgEarningYield * 100);  // 盈利收益率%

            // 近3年历史 PE 分布，计算当前分位
            String histStart = date.minusYears(3).toString();
            String histSql = String.format("""
                    WITH daily_stats AS (
                        SELECT trade_date,
                               avg(pe_ttm) as avg_pe,
                               avg(pb) as avg_pb
                        FROM stock.stock_daily FINAL
                        WHERE trade_date >= '%s' AND trade_date <= '%s'
                          AND pe_ttm > 0 AND pe_ttm < 500
                        GROUP BY trade_date
                    )
                    SELECT countIf(avg_pe <= %f) * 100.0 / count(*) as pe_percentile,
                           countIf(avg_pb <= %f) * 100.0 / count(*) as pb_percentile
                    FROM daily_stats
                    """, histStart, tradeDate, avgPe, ((Number) stats.get("avg_pb")).doubleValue());
            Map<String, Object> pctResult = chJdbc.queryForMap(histSql);
            result.put("pePercentile", Math.round(((Number) pctResult.get("pe_percentile")).doubleValue() * 10) / 10.0);
            result.put("pbPercentile", Math.round(((Number) pctResult.get("pb_percentile")).doubleValue() * 10) / 10.0);

            // PE 分位历史（近30个交易日）
            String historySql = String.format("""
                    WITH daily_stats AS (
                        SELECT trade_date,
                               avg(pe_ttm) as avg_pe
                        FROM stock.stock_daily FINAL
                        WHERE trade_date >= '%s' AND trade_date <= '%s'
                          AND pe_ttm > 0 AND pe_ttm < 500
                        GROUP BY trade_date
                    ),
                    all_pe AS (
                        SELECT trade_date, avg_pe
                        FROM daily_stats
                        ORDER BY trade_date
                    )
                    SELECT a.trade_date, a.avg_pe,
                           countIf(b.avg_pe <= a.avg_pe) * 100.0 / count(b.avg_pe) as percentile
                    FROM all_pe a
                    JOIN all_pe b ON b.trade_date <= a.trade_date
                    GROUP BY a.trade_date, a.avg_pe
                    ORDER BY a.trade_date DESC
                    LIMIT 30
                    """, histStart, tradeDate);
            List<Map<String, Object>> rawHistory = chJdbc.queryForList(historySql);
            List<Map<String, Object>> history = new ArrayList<>();
            for (Map<String, Object> row : rawHistory) {
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("date", row.get("trade_date"));
                h.put("pe", Math.round(((Number) row.get("avg_pe")).doubleValue() * 100) / 100.0);
                h.put("percentile", Math.round(((Number) row.get("percentile")).doubleValue() * 10) / 10.0);
                history.add(h);
            }
            result.put("history", history);
            result.put("pbHistory", List.of());  // PB历史简化处理
            result.put("actualTradeDate", tradeDate);

            log.info("大盘温度计 PE分位={}, PB分位={}, 日期={}",
                    result.get("pePercentile"), result.get("pbPercentile"), tradeDate);
        } catch (Exception e) {
            log.warn("PE/PB分位计算失败: {}", e.getMessage());
            result.put("pePercentile", 0.0);
            result.put("pbPercentile", 0.0);
            result.put("history", List.of());
            result.put("pbHistory", List.of());
            result.put("marketEarningYield", 0.0);
            result.put("actualTradeDate", date.toString());
        }
        return result;
    }

    // ─── 均线温度计算 ──────────────────────────────────────────────

    private Map<String, Object> calcMaTemperature(LocalDate date) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (chJdbc == null) {
            result.put("temperature", 50.0);
            result.put("trend", "震荡");
            result.put("history", List.of());
            return result;
        }

        try {
            // 沪深300 指数 code = 000300（存储在 index_daily）
            String sql = String.format("""
                    WITH ma AS (
                        SELECT trade_date, close_price,
                               avg(close_price) OVER (ORDER BY trade_date ROWS BETWEEN CURRENT ROW AND 19 FOLLOWING) as ma20,
                               avg(close_price) OVER (ORDER BY trade_date ROWS BETWEEN CURRENT ROW AND 59 FOLLOWING) as ma60
                        FROM stock.index_daily FINAL
                        WHERE code = '000300' AND trade_date <= '%s'
                        QUALIFY row_number() OVER (ORDER BY trade_date DESC) <= 65
                    )
                    SELECT trade_date, close_price, ma20, ma60,
                           CASE WHEN ma20 > ma60 THEN 1 ELSE -1 END as trend_signal
                    FROM ma
                    ORDER BY trade_date DESC
                    LIMIT 20
                    """, date);
            List<Map<String, Object>> rows = chJdbc.queryForList(sql);

            if (rows.isEmpty()) {
                result.put("temperature", 50.0);
                result.put("trend", "震荡");
                result.put("history", List.of());
                return result;
            }

            // 当前趋势
            Map<String, Object> curr = rows.get(0);
            // 计算温度：近20天趋势信号总和，归一化到0-100
            double sumSignal = rows.stream()
                    .mapToDouble(r -> ((Number) r.get("trend_signal")).doubleValue())
                    .sum();
            double temperature = (sumSignal + 20) / 40 * 100;  // -20→0, +20→100
            temperature = Math.max(0, Math.min(100, temperature));

            String trend;
            if (temperature >= 70) trend = "多头";
            else if (temperature <= 30) trend = "空头";
            else trend = "震荡";

            result.put("temperature", Math.round(temperature * 10) / 10.0);
            result.put("trend", trend);
            result.put("currPrice", curr.get("close_price"));
            result.put("ma20", curr.get("ma20"));
            result.put("ma60", curr.get("ma60"));

            // 历史
            List<Map<String, Object>> history = new ArrayList<>();
            for (Map<String, Object> r : rows) {
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("date", r.get("trade_date"));
                h.put("price", r.get("close_price"));
                h.put("ma20", Math.round(((Number) r.get("ma20")).doubleValue() * 100) / 100.0);
                h.put("ma60", Math.round(((Number) r.get("ma60")).doubleValue() * 100) / 100.0);
                h.put("trendSignal", ((Number) r.get("trend_signal")).intValue());
                history.add(h);
            }
            result.put("history", history);

        } catch (Exception e) {
            log.warn("均线温度计算失败: {}", e.getMessage());
            result.put("temperature", 50.0);
            result.put("trend", "震荡");
            result.put("history", List.of());
        }
        return result;
    }

    // ─── 股债收益比计算 ─────────────────────────────────────────────

    private Map<String, Object> calcStockBondRatio(LocalDate date, Double stockEarningYield) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (stockEarningYield == null || stockEarningYield <= 0) {
            stockEarningYield = 4.0;  // 默认4%
        }

        // 用 akshare 获取10年国债收益率（爬取东方财富）
        Double bondYield = getChinaBondYield10Y();

        if (bondYield == null || bondYield <= 0) {
            // 回退：用固定值（历史均值约2.8%）
            bondYield = 2.8;
        }

        double ratio = stockEarningYield / bondYield;
        result.put("bondYield", bondYield);
        result.put("ratio", Math.round(ratio * 100) / 100.0);

        // 历史：从 index_daily 查沪深300近30日数据，推算历史盈利收益率→股债比
        List<Map<String, Object>> history = new ArrayList<>();
        if (chJdbc != null) {
            try {
                String histSql = String.format("""
                        SELECT trade_date, close_price
                        FROM stock.index_daily FINAL
                        WHERE code = '000300' AND trade_date <= '%s'
                        ORDER BY trade_date DESC
                        LIMIT 30
                        """, date);
                List<Map<String, Object>> indexRows = chJdbc.queryForList(histSql);
                // 盈利收益率 = 1/PE，用沪深300平均PE（用bondYield和当前ratio反推今日基准）
                // 当前ratio作为基准
                for (int i = indexRows.size() - 1; i >= 0; i--) {
                    Map<String, Object> r = indexRows.get(i);
                    Map<String, Object> h = new LinkedHashMap<>();
                    h.put("date", r.get("trade_date"));
                    h.put("bondYield", bondYield);
                    h.put("ratio", Math.round(ratio * 100) / 100.0);
                    history.add(h);
                }
            } catch (Exception e) {
                log.debug("股债比历史查询失败: {}", e.getMessage());
            }
        }
        if (history.isEmpty()) {
            for (int i = 29; i >= 0; i--) {
                LocalDate d = date.minusDays(i);
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("date", d.toString());
                h.put("bondYield", bondYield);
                h.put("ratio", Math.round(ratio * 100) / 100.0);
                history.add(h);
            }
        }
        result.put("history", history);

        return result;
    }

    /**
     * 获取中国10年国债收益率（24小时缓存）
     * 数据源：akshare 东方财富国债数据
     */
    private Double getChinaBondYield10Y() {
        long now = System.currentTimeMillis();
        if (cachedBondYield != null && (now - cachedBondYieldTime) < BOND_CACHE_TTL_MS) {
            return cachedBondYield;
        }
        try {
            // 调用专用脚本，完全规避命令行列名编码问题
            String scriptPath = "C:\\Users\\warning5\\WorkBuddy\\Claw\\quant-platform\\scripts\\get_bond_yield_10y.py";
            ProcessBuilder pb = new ProcessBuilder("python", scriptPath);
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            p.waitFor();
            if (!output.isEmpty() && !output.startsWith("ERROR") && !output.equals("N/A")) {
                Double val = Double.parseDouble(output);
                cachedBondYield = val;
                cachedBondYieldTime = now;
                log.info("10年国债收益率已缓存: {}%", val);
                return val;
            }
            log.warn("国债收益率返回异常: {}", output);
        } catch (Exception e) {
            log.warn("国债收益率获取失败: {}", e.getMessage());
        }
        // 网络失败时保留旧缓存值
        return cachedBondYield;
    }

    // ─── 融资余额变化计算 ───────────────────────────────────────────

    private Map<String, Object> calcMarginChange(LocalDate date) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (chJdbc == null) {
            result.put("change", 0.0);
            result.put("trend", "平稳");
            result.put("history", List.of());
            return result;
        }

        try {
            // 从 stock_sentiment_moneyflow 查主力净流入作为情绪代理指标（近5日）
            String sql = String.format("""
                    SELECT trade_date,
                           sum(net_main) as net_main,
                           sum(net_main_pct) as net_main_pct
                    FROM stock.stock_sentiment_moneyflow FINAL
                    WHERE trade_date <= '%s'
                    GROUP BY trade_date
                    ORDER BY trade_date DESC
                    LIMIT 10
                    """, date);
            List<Map<String, Object>> rows = chJdbc.queryForList(sql);

            if (rows.size() < 2) {
                result.put("change", 0.0);
                result.put("trend", "平稳");
                result.put("history", List.of());
                return result;
            }

            // 近5日主力净流入总额（元 → 亿元）
            double last5NetMain = rows.stream()
                    .limit(Math.min(5, rows.size()))
                    .mapToDouble(r -> ((Number) r.get("net_main")).doubleValue())
                    .sum();
            double last5NetMainYi = Math.round(last5NetMain / 100000000 * 100) / 100.0;  // 元→亿，保留2位

            // 前5日主力净流入总额（对比用）
            double prev5NetMain = rows.stream()
                    .skip(5)
                    .limit(5)
                    .mapToDouble(r -> ((Number) r.get("net_main")).doubleValue())
                    .sum();
            double prev5NetMainYi = Math.round(prev5NetMain / 100000000 * 100) / 100.0;

            // 用总额正负判断趋势
            String trend;
            if (last5NetMainYi > 10) trend = "大幅流入";
            else if (last5NetMainYi > 0) trend = "小幅流入";
            else if (last5NetMainYi < -10) trend = "大幅流出";
            else if (last5NetMainYi < 0) trend = "小幅流出";
            else trend = "平稳";

            result.put("change", last5NetMainYi);
            result.put("prevChange", prev5NetMainYi);
            result.put("trend", trend);

            // 历史
            List<Map<String, Object>> history = new ArrayList<>();
            for (Map<String, Object> r : rows) {
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("date", r.get("trade_date"));
                h.put("netMain", ((Number) r.get("net_main")).doubleValue());
                h.put("netMainPct", ((Number) r.get("net_main_pct")).doubleValue());
                history.add(h);
            }
            result.put("history", history);

        } catch (Exception e) {
            log.warn("融资余额查询失败: {}", e.getMessage(), e);
            result.put("change", 0.0);
            result.put("prevChange", 0.0);
            result.put("trend", "平稳");
            result.put("history", List.of());
        }
        return result;
    }

    // ─── 辅助方法 ───────────────────────────────────────────────────

    /**
     * 股债收益比转恐慌贪婪分（0-100）
     * ratio > 3 表示股票显著低估 → 贪婪
     * ratio < 1.5 表示股票显著高估 → 恐慌
     */
    private double calcBondScore(Double ratio) {
        if (ratio == null) return 50;
        if (ratio >= 3.0) return 90;
        if (ratio <= 1.5) return 10;
        return 10 + (ratio - 1.5) / 1.5 * 80;
    }

    private String getFearGreedLabel(double score) {
        if (score >= 80) return "极度贪婪";
        if (score >= 65) return "贪婪";
        if (score >= 55) return "偏贪婪";
        if (score >= 45) return "中性";
        if (score >= 35) return "偏恐慌";
        if (score >= 20) return "恐慌";
        return "极度恐慌";
    }

}
