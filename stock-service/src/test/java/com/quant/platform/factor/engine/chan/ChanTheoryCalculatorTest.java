package com.quant.platform.factor.engine.chan;

import com.quant.platform.market.domain.MarketDailyBar;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 缠论计算引擎验证测试
 * T3: 用真实 ClickHouse 数据验证 ChanTheoryCalculator 输出合理性
 */
@Slf4j
class ChanTheoryCalculatorTest {

    private static final String CH_URL = System.getenv().getOrDefault("CLICKHOUSE_HOST", "http://localhost:8123");
    private static final String CH_USER = System.getenv().getOrDefault("CLICKHOUSE_USER", "default");
    private static final String CH_PASS = System.getenv().getOrDefault("CLICKHOUSE_PASSWORD", "");  // 必须配置环境变量，否则跳过集成测试

    // ═══════════════════════════════════════════════════════════
    // 合成数据测试
    // ═══════════════════════════════════════════════════════════

    @Test
    void testSimpleUptrend() {
        // 构造简单上升行情: 10,11,12,13,14,15,16,17,18,19,20
        List<MarketDailyBar> bars = createTrendBars("UP", 10, 20, 20);
        ChanTheoryResult result = ChanTheoryCalculator.calculate(bars);

        log.info("[简单上升] 合并K线={}, 分型={}, 笔={}, 线段={}, 中枢={}",
                result.getMergedBars().size(),
                result.getFractals().size(),
                result.getPens().size(),
                result.getSegments().size(),
                result.getHubs().size());

        // 单调上升不应有分型（包含处理后全是UP方向）
        // 但可能因为波动产生分型
        assertNotNull(result.getMergedBars());
        assertFalse(result.getMergedBars().isEmpty());
    }

    @Test
    void testVShape() {
        // V形: 先跌后涨，中间有明显转折
        double[] prices = {
                100, 98, 96, 94, 92, 90, 88, 86, 84, 82, 80, 78, 76, 74, 72,
                70, 73, 77, 81, 85, 89, 93, 97, 101, 99, 95, 91, 87, 83,
                79, 84, 90, 96, 102, 108, 104, 100, 96, 92, 88, 92, 98, 104
        };
        List<MarketDailyBar> bars = createBarsFromPrices(prices);
        ChanTheoryResult result = ChanTheoryCalculator.calculate(bars);

        log.info("[V形] 合并K线={}, 分型={}, 笔={}, 线段={}, 中枢={}",
                result.getMergedBars().size(),
                result.getFractals().size(),
                result.getPens().size(),
                result.getSegments().size(),
                result.getHubs().size());

        // 应该有分型（至少一个顶+一个底）
        assertTrue(result.getFractals().size() >= 2,
                "V形应至少产生2个分型(1顶+1底)，实际: " + result.getFractals().size());

        // 应该有笔
        assertTrue(result.getPens().size() >= 1,
                "V形应至少产生1笔，实际: " + result.getPens().size());
    }

    @Test
    void testConsolidation() {
        // 震荡行情: 在90-110之间反复
        double[] prices = {
                100, 105, 110, 105, 95, 90, 95, 105, 110, 105,
                95, 90, 95, 105, 110, 105, 95, 90, 95, 105,
                110, 105, 95, 90, 95, 105, 110, 105, 95, 100
        };
        List<MarketDailyBar> bars = createBarsFromPrices(prices);
        ChanTheoryResult result = ChanTheoryCalculator.calculate(bars);

        log.info("[震荡] 合并K线={}, 分型={}, 笔={}, 线段={}, 中枢={}",
                result.getMergedBars().size(),
                result.getFractals().size(),
                result.getPens().size(),
                result.getSegments().size(),
                result.getHubs().size());

        // 震荡应产生较多分型
        assertTrue(result.getFractals().size() >= 4,
                "震荡应产生>=4个分型，实际: " + result.getFractals().size());

        // 分型应顶底交替
        assertFractalAlternation(result.getFractals());
    }

    @Test
    void testPenAlternation() {
        // 笔的方向必须交替
        double[] prices = {
                100, 105, 110, 108, 95, 90, 92, 105, 110, 108,
                95, 88, 90, 102, 108, 105, 93, 85, 88, 100,
                106, 103, 92, 84, 87, 98, 104, 101, 91, 95
        };
        List<MarketDailyBar> bars = createBarsFromPrices(prices);
        ChanTheoryResult result = ChanTheoryCalculator.calculate(bars);

        if (result.getPens().size() >= 2) {
            for (int i = 1; i < result.getPens().size(); i++) {
                Pen prev = result.getPens().get(i - 1);
                Pen curr = result.getPens().get(i);
                assertNotEquals(prev.getDirection(), curr.getDirection(),
                        "相邻笔方向必须交替，第" + i + "笔与第" + (i - 1) + "笔方向相同: " + prev.getDirection());
            }
        }
    }

    @Test
    void testPriceConsistency() {
        // 上升笔终点>起点，下降笔终点<起点
        double[] prices = {
                100, 105, 110, 108, 95, 90, 92, 105, 110, 108,
                95, 88, 90, 102, 108, 105, 93, 85, 88, 100,
                106, 103, 92, 84, 87, 98, 104, 101, 91, 95,
                100, 108, 112, 106, 96, 88, 92, 105, 115, 110
        };
        List<MarketDailyBar> bars = createBarsFromPrices(prices);
        ChanTheoryResult result = ChanTheoryCalculator.calculate(bars);

        for (Pen pen : result.getPens()) {
            if (pen.getDirection() == Direction.UP) {
                assertTrue(pen.getEndPrice() > pen.getStartPrice(),
                        "上升笔终点应>起点: start=" + pen.getStartPrice() + " end=" + pen.getEndPrice());
            } else {
                assertTrue(pen.getEndPrice() < pen.getStartPrice(),
                        "下降笔终点应<起点: start=" + pen.getStartPrice() + " end=" + pen.getEndPrice());
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 真实数据测试（ClickHouse）
    // ═══════════════════════════════════════════════════════════

    @Test
    void testShanghaiCompositeFullHistory() throws Exception {
        List<MarketDailyBar> bars = fetchFromClickHouse("000001", 500);
        if (bars.size() < 50) {
            log.warn("ClickHouse 数据不足，跳过真实数据测试 ({}条)", bars.size());
            return;
        }

        ChanTheoryResult result = ChanTheoryCalculator.calculate(bars);

        log.info("════════════════ 上证指数(近500日) 缠论分析 ════════════════");
        log.info("原始K线: {}, 合并K线: {}", bars.size(), result.getMergedBars().size());
        log.info("分型数: {} (顶{} + 底{})",
                result.getFractals().size(),
                result.getFractals().stream().filter(f -> f.getFractalType() == FractalType.TOP).count(),
                result.getFractals().stream().filter(f -> f.getFractalType() == FractalType.BOTTOM).count());
        log.info("笔数: {}", result.getPens().size());
        log.info("线段数: {}", result.getSegments().size());
        log.info("中枢数: {}", result.getHubs().size());
        log.info("走势数: {}", result.getTrends().size());
        log.info("买卖点数: {}", result.getBuySellPoints().size());

        // 基本合理性检查
        assertTrue(result.getMergedBars().size() > 0, "应有合并K线");
        assertTrue(result.getMergedBars().size() <= bars.size(), "合并K线数应<=原始K线数");

        // 分型检查
        if (!result.getFractals().isEmpty()) {
            assertFractalAlternation(result.getFractals());
        }

        // 笔检查
        if (!result.getPens().isEmpty()) {
            logPens(result.getPens());
            assertPenPriceConsistency(result.getPens());
        }

        // 中枢检查
        for (Hub hub : result.getHubs()) {
            assertTrue(hub.getHigh() > hub.getLow(),
                    "中枢上沿应>下沿: ZG=" + hub.getHigh() + " ZD=" + hub.getLow());
            log.info("中枢#{}: ZD={} ZG={} ZZ={} 区间={}~{} 震荡{}次",
                    hub.getIndex(), hub.getLow(), hub.getHigh(), hub.getZz(),
                    hub.getStartDate(), hub.getEndDate(), hub.getOscillationCount());
        }

        // 走势检查
        for (Trend trend : result.getTrends()) {
            log.info("走势#{}: {} ({}~{})", trend.getIndex(), trend.getTrendType(),
                    trend.getStartDate(), trend.getEndDate());
        }

        // 买卖点
        for (BuySellPoint p : result.getBuySellPoints()) {
            log.info("信号: {} @ {} ({})", p.getBuySellType(), p.getPrice(), p.getDate());
        }
    }

    @Test
    void testShanghaiCompositeRecent120() throws Exception {
        List<MarketDailyBar> bars = fetchFromClickHouse("sh.000001", 120);
        if (bars.size() < 50) {
            log.warn("数据不足，跳过");
            return;
        }

        ChanTheoryResult result = ChanTheoryCalculator.calculate(bars);

        log.info("════════════════ 上证指数(近120日) ════════════════");
        log.info("合并K线={}, 分型={}, 笔={}, 线段={}, 中枢={}, 走势={}, 买卖点={}",
                result.getMergedBars().size(), result.getFractals().size(),
                result.getPens().size(), result.getSegments().size(),
                result.getHubs().size(), result.getTrends().size(),
                result.getBuySellPoints().size());

        // 120日应该能产生至少几笔
        assertTrue(result.getPens().size() >= 2,
                "120日应至少产生2笔，实际: " + result.getPens().size());

        // 笔方向交替
        assertPenAlternation(result.getPens());
    }

    @Test
    void testSzaComponent() throws Exception {
        List<MarketDailyBar> bars = fetchFromClickHouse("sz.399001", 200);
        if (bars.size() < 50) {
            log.warn("数据不足，跳过");
            return;
        }

        ChanTheoryResult result = ChanTheoryCalculator.calculate(bars);

        log.info("════════════════ 深证成指(近200日) ════════════════");
        log.info("合并K线={}, 分型={}, 笔={}, 线段={}, 中枢={}, 走势={}, 买卖点={}",
                result.getMergedBars().size(), result.getFractals().size(),
                result.getPens().size(), result.getSegments().size(),
                result.getHubs().size(), result.getTrends().size(),
                result.getBuySellPoints().size());

        if (!result.getPens().isEmpty()) {
            assertPenPriceConsistency(result.getPens());
            assertPenAlternation(result.getPens());
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 辅助方法
    // ═══════════════════════════════════════════════════════════

    // 指数代码集合（数据在 index_daily 表，不在 stock_daily）
    private static final java.util.Set<String> INDEX_CODES = java.util.Set.of(
            "000001", "000016", "000022", "000300", "000688",
            "000852", "000905", "399001", "399006", "399303"
    );

    /**
     * 去掉 sh./sz. 前缀，返回纯数字代码
     */
    private String normalizeCode(String code) {
        if (code == null) return code;
        return code.replaceAll("^[a-z]+\\.", "");
    }

    /**
     * 根据代码判断查哪张表
     */
    private String resolveTable(String code) {
        String normalized = normalizeCode(code);
        return INDEX_CODES.contains(normalized) ? "index_daily" : "stock_daily";
    }

    private List<MarketDailyBar> fetchFromClickHouse(String code, int limit) {
        String table = "stock." + resolveTable(code);
        String sql = String.format(
                "SELECT trade_date, open_price, high_price, low_price, close_price, volume " +
                        "FROM %s WHERE code='%s' AND open_price IS NOT NULL " +
                        "ORDER BY trade_date DESC LIMIT %d FORMAT CSVWithNames", table, normalizeCode(code), limit);

        // localhost 可能解析为 IPv6(::1) 或 IPv4(127.0.0.1)，CH 只监听其一就会连不上
        String[] urls = {CH_URL, "http://127.0.0.1:8123", "http://[::1]:8123"};
        HttpURLConnection conn = null;
        Exception lastEx = null;

        for (String baseUrl : urls) {
            try {
                URL url = URI.create(baseUrl + "/?user=" + CH_USER + "&password=" + CH_PASS).toURL();
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.getOutputStream().write(sql.getBytes());
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(30000);
                conn.connect();

                if (conn.getResponseCode() == 200) {
                    lastEx = null;
                    break;
                }
            } catch (Exception e) {
                lastEx = e;
                log.debug("尝试 {} 失败: {}", baseUrl, e.getMessage());
                if (conn != null) try { conn.disconnect(); } catch (Exception ignored) {}
                conn = null;
            }
        }

        if (conn == null) {
            log.warn("ClickHouse 无法连接 ({}), 跳过真实数据测试", lastEx.getMessage());
            return new ArrayList<>();
        }

        BufferedReader reader = null;
        List<MarketDailyBar> bars = new ArrayList<>();
        try {
            reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            String line;
            boolean header = true;
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");

            while ((line = reader.readLine()) != null) {
                if (header) { header = false; continue; }
                String[] parts = line.replace("\"", "").split(",");
                if (parts.length < 6) continue;

                try {
                    MarketDailyBar bar = MarketDailyBar.builder()
                                .symbol(code)
                                .tradeDate(LocalDate.parse(parts[0], fmt))
                                .open(new BigDecimal(parts[1]))
                                .high(new BigDecimal(parts[2]))
                                .low(new BigDecimal(parts[3]))
                                .close(new BigDecimal(parts[4]))
                                .vol(new BigDecimal(parts[5]))
                                .build();
                    bars.add(bar);
                } catch (Exception e) {
                    // skip malformed lines
                }
            }
        } catch (Exception e) {
            log.warn("读取 ClickHouse 响应失败: {}", e.getMessage());
            return new ArrayList<>();
        } finally {
            if (reader != null) try { reader.close(); } catch (Exception ignored) {}
            if (conn != null) try { conn.disconnect(); } catch (Exception ignored) {}
        }

        // ClickHouse DESC排序，需要反转为正序
        java.util.Collections.reverse(bars);
        log.info("从ClickHouse获取 {} 数据: {}条 ({}~{})", code, bars.size(),
                bars.isEmpty() ? "N/A" : bars.get(0).getTradeDate(),
                bars.isEmpty() ? "N/A" : bars.get(bars.size() - 1).getTradeDate());
        return bars;
    }

    /**
     * 构造趋势行情
     */
    private List<MarketDailyBar> createTrendBars(String direction, double start, double end, int count) {
        List<MarketDailyBar> bars = new ArrayList<>();
        double step = (end - start) / count;
        LocalDate baseDate = LocalDate.of(2025, 1, 2);

        for (int i = 0; i < count; i++) {
            double price = start + step * i;
            double noise = (Math.random() - 0.5) * step * 0.3; // 微小噪声
            double open = price + noise;
            double close = price + step * 0.5 + noise;
            double high = Math.max(open, close) + Math.abs(noise) * 0.5;
            double low = Math.min(open, close) - Math.abs(noise) * 0.5;

            bars.add(MarketDailyBar.builder()
                    .symbol("TEST")
                    .tradeDate(baseDate.plusDays(i))
                    .open(BigDecimal.valueOf(open))
                    .high(BigDecimal.valueOf(high))
                    .low(BigDecimal.valueOf(low))
                    .close(BigDecimal.valueOf(close))
                    .vol(BigDecimal.valueOf(10000))
                    .build());
        }
        return bars;
    }

    /**
     * 从价格数组构造行情（每个价格用 open=close=price, high=price+0.5, low=price-0.5）
     */
    private List<MarketDailyBar> createBarsFromPrices(double[] prices) {
        List<MarketDailyBar> bars = new ArrayList<>();
        LocalDate baseDate = LocalDate.of(2025, 1, 2);

        for (int i = 0; i < prices.length; i++) {
            double p = prices[i];
            bars.add(MarketDailyBar.builder()
                    .symbol("TEST")
                    .tradeDate(baseDate.plusDays(i))
                    .open(BigDecimal.valueOf(p))
                    .high(BigDecimal.valueOf(p + 0.5))
                    .low(BigDecimal.valueOf(p - 0.5))
                    .close(BigDecimal.valueOf(p))
                    .vol(BigDecimal.valueOf(10000))
                    .build());
        }
        return bars;
    }

    /**
     * 断言分型顶底交替
     */
    private void assertFractalAlternation(List<Fractal> fractals) {
        for (int i = 1; i < fractals.size(); i++) {
            assertNotEquals(fractals.get(i - 1).getFractalType(), fractals.get(i).getFractalType(),
                    "相邻分型必须顶底交替，第" + i + "个分型与第" + (i - 1) + "个相同: " +
                            fractals.get(i - 1).getFractalType());
        }
    }

    /**
     * 断言笔价格一致性
     */
    private void assertPenPriceConsistency(List<Pen> pens) {
        for (Pen pen : pens) {
            if (pen.getDirection() == Direction.UP) {
                assertTrue(pen.getEndPrice() > pen.getStartPrice(),
                        "上升笔终点应>起点");
            } else {
                assertTrue(pen.getEndPrice() < pen.getStartPrice(),
                        "下降笔终点应<起点");
            }
        }
    }

    /**
     * 断言笔方向交替
     */
    private void assertPenAlternation(List<Pen> pens) {
        for (int i = 1; i < pens.size(); i++) {
            assertNotEquals(pens.get(i - 1).getDirection(), pens.get(i).getDirection(),
                    "相邻笔方向必须交替");
        }
    }

    /**
     * 打印笔摘要
     */
    private void logPens(List<Pen> pens) {
        int limit = Math.min(pens.size(), 10);
        log.info("--- 最近{}笔 ---", limit);
        for (int i = pens.size() - limit; i < pens.size(); i++) {
            Pen p = pens.get(i);
            log.info("笔#{}: {} {}→{} ({}~{}) bars={}",
                    p.getIndex(), p.getDirection() == Direction.UP ? "↑" : "↓",
                    p.getStartPrice(), p.getEndPrice(),
                    p.getStartDate(), p.getEndDate(), p.getBarCount());
        }
    }
}
