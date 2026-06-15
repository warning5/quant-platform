package com.quant.platform.monitor;

import com.quant.platform.notification.NotificationService;
import lombok.Data;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 盘中实时监控服务
 * 改进点：
 * 1. 高频轮询（默认10秒）替代1分钟轮询，接近实时
 * 2. K线拉取并行化（CompletableFuture线程池），多只股同时分析
 * 3. 实时价格通过SSE推送到前端
 * 4. 入场信号4维评分（突破+均线+量价+回踩）
 * 5. 止损独立判断，直接推送不参与评分
 * 6. 冷却期防重复推送（买入30分钟，止损60分钟）
 */
@Slf4j
@Service
public class IntradayMonitorService {

    private static final String QUOTE_URL = "http://qt.gtimg.cn/q=%s";

    private static final Set<String> HOLIDAYS_2026 = Set.of(
            "2026-01-01", "2026-01-02", "2026-01-03",
            "2026-02-17", "2026-02-18", "2026-02-19", "2026-02-20",
            "2026-02-21", "2026-02-22", "2026-02-23",
            "2026-04-04", "2026-04-05", "2026-04-06",
            "2026-05-01", "2026-05-02", "2026-05-03", "2026-05-04", "2026-05-05",
            "2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04",
            "2026-10-05", "2026-10-06", "2026-10-07"
    );
    private static final Set<String> MAKEUP_DAYS_2026 = Set.of(
            "2026-02-14", "2026-02-15", "2026-10-10"
    );

    private final JdbcTemplate jdbcTemplate;
    private final NotificationService notificationService;
    private final EntrySignalAnalyzer signalAnalyzer;

    /** K线并行拉取线程池 */
    private final ExecutorService klinePool;

    /** 候选股目标价缓存: stockCode -> TargetPriceInfo */
    private final Map<String, TargetPriceInfo> targetPriceCache = new ConcurrentHashMap<>();
    /** 今日已推送记录: key -> 推送时间 */
    private final Map<String, LocalDateTime> pushedWithTime = new ConcurrentHashMap<>();
    /** SSE连接列表 */
    private final CopyOnWriteArrayList<SseEmitter> sseEmitters = new CopyOnWriteArrayList<>();
    /** 最新实时价格缓存: stockCode -> price（供SSE推送和手动查询用） */
    private final Map<String, Double> latestPrices = new ConcurrentHashMap<>();
    private final Map<String, Double> latestChangePct = new ConcurrentHashMap<>();
    /** 用户自定义股票: stockCode -> TargetPriceInfo（刷新目标价时不被覆盖） */
    private final Map<String, TargetPriceInfo> customStocks = new ConcurrentHashMap<>();
    /** 信号历史记录（最近50条，供/status接口返回） */
    private final CopyOnWriteArrayList<Map<String, Object>> signalHistory = new CopyOnWriteArrayList<>();

    private volatile boolean monitoring = false;
    /** 轮询计数器（用于控制日志输出频率） */
    private int pollCount = 0;

    @Value("${quant.monitor.cooldown.buy-minutes:30}")
    private int buyCooldownMinutes;
    @Value("${quant.monitor.cooldown.stop-minutes:60}")
    private int stopCooldownMinutes;
    @Value("${quant.monitor.signal.proximity-pct:0.02}")
    private double proximityPct;
    @Value("${quant.monitor.poll-interval-seconds:10}")
    private int pollIntervalSeconds;

    @Getter
    private volatile LocalDate dataDate;

    public IntradayMonitorService(JdbcTemplate jdbcTemplate, NotificationService notificationService,
                                  EntrySignalAnalyzer signalAnalyzer,
                                  @Value("${quant.monitor.kline-thread-pool-size:4}") int klinePoolSize) {
        this.jdbcTemplate = jdbcTemplate;
        this.notificationService = notificationService;
        this.signalAnalyzer = signalAnalyzer;
        this.klinePool = Executors.newFixedThreadPool(klinePoolSize,
                r -> {
                    Thread t = new Thread(r, "kline-analyze");
                    t.setDaemon(true);
                    return t;
                });
        this.dataDate = LocalDate.now();
        // 启动时从数据库加载自定义股票
        loadCustomStocksFromDb();
    }

    // ── 中国交易日判断 ──

    private static boolean isNonTradingDay(LocalDate date) {
        String ds = date.toString();
        if (MAKEUP_DAYS_2026.contains(ds)) return false;
        if (HOLIDAYS_2026.contains(ds)) return true;
        java.time.DayOfWeek dow = date.getDayOfWeek();
        return dow == java.time.DayOfWeek.SATURDAY || dow == java.time.DayOfWeek.SUNDAY;
    }

    public LocalDate getLatestDataDate() {
        LocalDate result = null;
        try {
            String sql1 = "SELECT MAX(analysis_date) FROM llm_analysis WHERE analysis_date <= CURDATE()";
            LocalDate d1 = jdbcTemplate.queryForObject(sql1, LocalDate.class);
            if (d1 != null) result = d1;
        } catch (Exception e) {
            log.warn("[IntradayMonitor] 查询llm_analysis最新日期失败: {}", e.getMessage());
        }
        if (result == null) {
            try {
                String sql2 = "SELECT MAX(recommend_date) FROM stock_recommendation WHERE recommend_date <= CURDATE()";
                LocalDate d2 = jdbcTemplate.queryForObject(sql2, LocalDate.class);
                if (d2 != null) result = d2;
            } catch (Exception e) {
                log.warn("[IntradayMonitor] 查询stock_recommendation最新日期失败: {}", e.getMessage());
            }
        }
        if (result == null) {
            result = LocalDate.now().minusDays(1);
        }
        while (isNonTradingDay(result)) {
            result = result.minusDays(1);
        }
        this.dataDate = result;
        return result;
    }

    // ── 盘中监控主循环（高频轮询） ──

    /**
     * 高频轮询入口，每10秒执行一次
     * 仅在交易时段（09:30~15:00）内运行
     */
    @Scheduled(cron = "0/10 * 9-14 * * 1-5")  // 周一到周五，9:00~14:59每10秒
    public void monitorLoop() {
        LocalDateTime now = LocalDateTime.now();
        int hour = now.getHour();
        int minute = now.getMinute();

        boolean inTradingHours = (hour == 9 && minute >= 30) || (hour >= 10 && hour < 15);
        if (!inTradingHours) return;

        boolean inAuction = (hour == 9 && minute >= 25 && minute < 30) || (hour == 14 && minute >= 55);

        // 每日重置推送记录
        if (hour == 9 && minute == 30 && now.getSecond() < 10) {
            pushedWithTime.clear();
        }

        if (!monitoring) {
            monitoring = true;
            log.info("[IntradayMonitor] ===== 监控启动 ===== 当前时间: {}", now);
            loadTargetPrices();
        }

        if (targetPriceCache.isEmpty()) return;

        pollCount++;
        // 每60秒（6次轮询）输出一次日志
        if (pollCount % 6 == 1) {
            log.info("[IntradayMonitor] 轮询 #{}, 监控{}只股票, SSE连接数: {}",
                    pollCount, targetPriceCache.size(), sseEmitters.size());
        }

        // 批量获取实时价格
        Map<String, Double> prices = fetchRealtimePrices(new ArrayList<>(targetPriceCache.keySet()));

        // 更新最新价格缓存
        latestPrices.putAll(prices);

        // SSE推送实时价格更新
        broadcastPriceUpdate(prices);

        // 并行分析所有触发区间的股票
        analyzePricesParallel(prices, inAuction);
    }

    /**
     * 并行分析：先筛选触发区间的股票，再并行拉K线+评分
     */
    private void analyzePricesParallel(Map<String, Double> prices, boolean inAuction) {
        // 第一步：快速筛选——先找出进入触发区间的股票
        List<Map.Entry<String, Double>> triggeredStocks = new ArrayList<>();

        for (Map.Entry<String, Double> entry : prices.entrySet()) {
            String code = entry.getKey();
            double currentPrice = entry.getValue();
            TargetPriceInfo target = targetPriceCache.get(code);
            if (target == null) continue;

            // 止损独立判断（无需K线，立即处理）
            if (target.getStopLoss() != null && currentPrice <= target.getStopLoss().doubleValue()) {
                String key = code + "_STOP";
                if (canPush(key, stopCooldownMinutes)) {
                    pushStopLossSignal(code, target, currentPrice);
                    markPushed(key);
                }
                continue;  // 止损后不再判断买入
            }

            // 检查是否进入触发区间
            double buyLow = target.getBuyPriceLow().doubleValue();
            double buyHigh = target.getBuyPriceHigh().doubleValue();
            double triggerLow = buyLow * (1 - proximityPct);
            double triggerHigh = buyHigh * (1 + proximityPct);

            if (currentPrice >= triggerLow && currentPrice <= triggerHigh) {
                String key = code + "_BUY";
                if (canPush(key, buyCooldownMinutes)) {
                    triggeredStocks.add(entry);
                }
            }
        }

        if (triggeredStocks.isEmpty()) return;

        // 第二步：并行拉K线+评分
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (Map.Entry<String, Double> entry : triggeredStocks) {
            String code = entry.getKey();
            double currentPrice = entry.getValue();
            TargetPriceInfo target = targetPriceCache.get(code);

            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    EntrySignalAnalyzer.BreakoutSignal signal;
                    if (inAuction) {
                        signal = signalAnalyzer.fallbackPriceOnly(currentPrice, target, code);
                    } else {
                        signal = signalAnalyzer.analyze(code, currentPrice, target);
                    }

                    if (signal.isActionable()) {
                        pushBuySignal(code, target, currentPrice, signal);
                        markPushed(code + "_BUY");
                    } else {
                        // 区间内但评分不够，推送WATCH信号到前端展示
                        pushWatchSignal(code, target, currentPrice, signal);
                        log.info("[IntradayMonitor] 观察: {}({}) 现价{} 评分{}/100",
                                target.getStockName(), code, currentPrice, signal.getTotalScore());
                    }
                } catch (Exception e) {
                    log.warn("[IntradayMonitor] 并行分析异常: {} - {}", code, e.getMessage());
                }
            }, klinePool);

            futures.add(future);
        }

        // 等待所有分析完成（最多等5秒）
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("[IntradayMonitor] 部分K线分析超时，已降级处理");
        } catch (Exception e) {
            log.warn("[IntradayMonitor] 并行分析异常: {}", e.getMessage());
        }
    }

    // ── 冷却期 ──

    private boolean canPush(String key, int cooldownMinutes) {
        LocalDateTime lastPush = pushedWithTime.get(key);
        if (lastPush == null) return true;
        long minutesSince = ChronoUnit.MINUTES.between(lastPush, LocalDateTime.now());
        return minutesSince >= cooldownMinutes;
    }

    private void markPushed(String key) {
        pushedWithTime.put(key, LocalDateTime.now());
    }

    // ── 目标价加载 ──

    public void loadTargetPrices() {
        targetPriceCache.clear();
        LocalDate latestDate = getLatestDataDate();
        String dateStr = latestDate.toString();
        log.info("[IntradayMonitor] ===== 加载目标价 START ===== 数据日期: {}", dateStr);

        // 诊断：先查所有BUY记录
        try {
            String diagSql = String.format(
                    "SELECT r.stock_code, r.stock_name, r.suggested_buy_price " +
                            "FROM stock_recommendation r " +
                            "WHERE r.recommend_date = '%s' AND r.action_tag = 'BUY'", dateStr);
            List<Map<String, Object>> allBuy = jdbcTemplate.queryForList(diagSql);
            log.info("[IntradayMonitor] 诊断: {} 共有 {} 条BUY推荐", dateStr, allBuy.size());
            for (Map<String, Object> rec : allBuy) {
                log.info("[IntradayMonitor] 诊断-BUY: {}({}) suggested_buy_price={}",
                        rec.get("stock_name"), rec.get("stock_code"), rec.get("suggested_buy_price"));
            }
        } catch (Exception e) {
            log.warn("[IntradayMonitor] 诊断查询失败: {}", e.getMessage());
        }

        try {
            // 数据源1: llm_analysis BUY推荐
            String llmSql = String.format(
                    "SELECT a.stock_code, a.stock_name, a.buy_price_low, a.buy_price_high, " +
                            "a.stop_loss, a.target_price " +
                            "FROM llm_analysis a " +
                            "WHERE a.analysis_date = '%s' AND a.recommendation = 'BUY' " +
                            "AND a.buy_price_high IS NOT NULL", dateStr);
            jdbcTemplate.query(llmSql, rs -> {
                TargetPriceInfo info = new TargetPriceInfo();
                info.setStockCode(rs.getString("stock_code"));
                info.setStockName(rs.getString("stock_name"));
                info.setBuyPriceLow(rs.getBigDecimal("buy_price_low"));
                info.setBuyPriceHigh(rs.getBigDecimal("buy_price_high"));
                info.setStopLoss(rs.getBigDecimal("stop_loss"));
                info.setTargetPrice(rs.getBigDecimal("target_price"));
                info.setSource("LLM");
                targetPriceCache.put(info.getStockCode(), info);
            });
            int llmCount = targetPriceCache.size();

            // 数据源2: stock_recommendation 智能推荐
            String recSql = String.format(
                    "SELECT r.stock_code, r.stock_name, r.suggested_buy_price, r.close_price " +
                            "FROM stock_recommendation r " +
                            "WHERE r.recommend_date = '%s' AND r.action_tag = 'BUY' " +
                            "AND r.suggested_buy_price IS NOT NULL", dateStr);
            jdbcTemplate.query(recSql, rs -> {
                String code = rs.getString("stock_code");
                if (targetPriceCache.containsKey(code)) return;

                double buyPrice = rs.getDouble("suggested_buy_price");
                double closePrice = rs.getDouble("close_price");

                TargetPriceInfo info = new TargetPriceInfo();
                info.setStockCode(code);
                info.setStockName(rs.getString("stock_name"));
                info.setBuyPriceLow(BigDecimal.valueOf(buyPrice * 0.95));
                info.setBuyPriceHigh(BigDecimal.valueOf(buyPrice * 1.05));
                info.setStopLoss(BigDecimal.valueOf(buyPrice * 0.92));
                info.setTargetPrice(closePrice > 0 ? BigDecimal.valueOf(closePrice * 1.20) : null);
                info.setSource("推荐");
                targetPriceCache.put(code, info);
            });
            int recCount = targetPriceCache.size() - llmCount;

            log.info("[IntradayMonitor] 加载目标价: {} 只股票 (LLM:{}, 推荐:{})", targetPriceCache.size(), llmCount, recCount);
        } catch (Exception e) {
            log.warn("[IntradayMonitor] 加载目标价失败: {}", e.getMessage());
        }

        // 恢复用户自定义股票（刷新不被覆盖）
        targetPriceCache.putAll(customStocks);
        if (!customStocks.isEmpty()) {
            log.info("[IntradayMonitor] 恢复自定义股票: {} 只", customStocks.size());
        }
    }

    // ── 自定义股票管理 ──

    /**
     * 从数据库加载自定义股票（启动时调用）
     */
    private void loadCustomStocksFromDb() {
        try {
            String sql = "SELECT stock_code, stock_name, buy_price_low, buy_price_high, stop_loss, target_price " +
                    "FROM monitor_custom_stock";
            jdbcTemplate.query(sql, rs -> {
                TargetPriceInfo info = new TargetPriceInfo();
                info.setStockCode(rs.getString("stock_code"));
                info.setStockName(rs.getString("stock_name"));
                info.setBuyPriceLow(rs.getBigDecimal("buy_price_low"));
                info.setBuyPriceHigh(rs.getBigDecimal("buy_price_high"));
                info.setStopLoss(rs.getBigDecimal("stop_loss"));
                info.setTargetPrice(rs.getBigDecimal("target_price"));
                info.setSource("客户定义");
                customStocks.put(info.getStockCode(), info);
            });
            if (!customStocks.isEmpty()) {
                log.info("[IntradayMonitor] 从数据库加载自定义股票: {} 只", customStocks.size());
            }
        } catch (Exception e) {
            log.warn("[IntradayMonitor] 从数据库加载自定义股票失败: {}", e.getMessage());
        }
    }

    /**
     * 添加用户自定义监控股票（同时持久化到数据库）
     * @param info 目标价信息（source自动设为"客户定义"）
     */
    public void addCustomStock(TargetPriceInfo info) {
        info.setSource("客户定义");
        customStocks.put(info.getStockCode(), info);
        targetPriceCache.put(info.getStockCode(), info);
        log.info("[IntradayMonitor] 添加自定义股票: {}({}) 买入区间[{}~{}] 止损价:{}",
                info.getStockName(), info.getStockCode(),
                info.getBuyPriceLow(), info.getBuyPriceHigh(), info.getStopLoss());

        // 持久化到数据库（INSERT ON DUPLICATE KEY UPDATE）
        try {
            String sql = "INSERT INTO monitor_custom_stock (stock_code, stock_name, buy_price_low, buy_price_high, stop_loss, target_price) " +
                    "VALUES (?, ?, ?, ?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE stock_name=VALUES(stock_name), buy_price_low=VALUES(buy_price_low), " +
                    "buy_price_high=VALUES(buy_price_high), stop_loss=VALUES(stop_loss), target_price=VALUES(target_price)";
            jdbcTemplate.update(sql,
                    info.getStockCode(), info.getStockName(),
                    info.getBuyPriceLow(), info.getBuyPriceHigh(),
                    info.getStopLoss(), info.getTargetPrice());
            log.debug("[IntradayMonitor] 自定义股票已持久化: {}", info.getStockCode());
        } catch (Exception e) {
            log.warn("[IntradayMonitor] 自定义股票持久化失败: {} - {}", info.getStockCode(), e.getMessage());
        }
    }

    /**
     * 移除用户自定义监控股票（同时从数据库删除）
     */
    public boolean removeCustomStock(String stockCode) {
        TargetPriceInfo removed = customStocks.remove(stockCode);
        if (removed != null) {
            targetPriceCache.remove(stockCode);
            latestPrices.remove(stockCode);
            latestChangePct.remove(stockCode);
            log.info("[IntradayMonitor] 移除自定义股票: {}", stockCode);

            // 从数据库删除
            try {
                jdbcTemplate.update("DELETE FROM monitor_custom_stock WHERE stock_code = ?", stockCode);
                log.debug("[IntradayMonitor] 自定义股票已从数据库删除: {}", stockCode);
            } catch (Exception e) {
                log.warn("[IntradayMonitor] 自定义股票数据库删除失败: {} - {}", stockCode, e.getMessage());
            }
            return true;
        }
        return false;
    }

    /**
     * 获取所有自定义股票列表
     */
    public List<TargetPriceInfo> getCustomStocks() {
        return new ArrayList<>(customStocks.values());
    }

    // ── 实时价格获取 ──

    public Map<String, Double> fetchRealtimePrices(List<String> stockCodes) {
        Map<String, Double> prices = new LinkedHashMap<>();
        if (stockCodes.isEmpty()) return prices;

        try {
            List<String> tencentCodes = new ArrayList<>();
            Map<String, String> reverseMap = new HashMap<>();

            for (String code : stockCodes) {
                String tc = convertToTencentCode(code);
                tencentCodes.add(tc);
                reverseMap.put(tc, code);
            }

            int batchSize = 500;
            for (int i = 0; i < tencentCodes.size(); i += batchSize) {
                List<String> batch = tencentCodes.subList(i, Math.min(i + batchSize, tencentCodes.size()));
                String codesParam = String.join(",", batch);
                String url = String.format(QUOTE_URL, codesParam);

                java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                        .connectTimeout(java.time.Duration.ofSeconds(5))
                        .build();

                java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(url))
                        .timeout(java.time.Duration.ofSeconds(10))
                        .GET()
                        .build();

                java.net.http.HttpResponse<String> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    parseRealtimePrices(response.body(), reverseMap, prices);
                }

                if (i + batchSize < tencentCodes.size()) {
                    try { Thread.sleep(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                }
            }
        } catch (Exception e) {
            log.error("[IntradayMonitor] 获取实时价格失败: {}", e.getMessage());
        }

        return prices;
    }

    private void parseRealtimePrices(String body, Map<String, String> reverseMap, Map<String, Double> prices) {
        String[] lines = body.split(";");
        for (String line : lines) {
            if (line.isEmpty()) continue;
            try {
                int eqIdx = line.indexOf('=');
                if (eqIdx < 0) continue;
                String varName = line.substring(0, eqIdx).trim();
                String value = line.substring(eqIdx + 1).trim();
                if (value.isEmpty() || value.equals("\"\"")) continue;

                String prefix = varName.replace("v_", "").replace("s_", "");
                String content = value.replace("\"", "");
                String[] fields = content.split("~");
                if (fields.length > 3) {
                    double currentPrice = Double.parseDouble(fields[3]);
                    String stockCode = reverseMap.getOrDefault(prefix, prefix);
                    prices.put(stockCode, currentPrice);
                    // fields[32] = 涨跌幅(%)
                    if (fields.length > 32 && !fields[32].isEmpty()) {
                        try {
                            latestChangePct.put(stockCode, Double.parseDouble(fields[32]));
                        } catch (NumberFormatException ignored) {}
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    private String convertToTencentCode(String stockCode) {
        if (stockCode.contains(".")) {
            String[] parts = stockCode.split("\\.");
            return parts[1].toLowerCase() + parts[0];
        }
        // 纯数字代码：根据首位推断市场前缀
        String lower = stockCode.toLowerCase();
        if (lower.startsWith("6")) {
            return "sh" + lower;
        } else if (lower.startsWith("0") || lower.startsWith("3")) {
            return "sz" + lower;
        } else if (lower.startsWith("4") || lower.startsWith("8")) {
            return "bj" + lower;
        }
        return lower; // 兜底：未知格式原样返回
    }

    // ── 信号推送 ──

    private void pushBuySignal(String stockCode, TargetPriceInfo target, double currentPrice,
                               EntrySignalAnalyzer.BreakoutSignal signal) {
        String msg = signal.toPushMessage();
        if (target.getStopLoss() != null) {
            msg += String.format(" 止损%.2f", target.getStopLoss().doubleValue());
        }
        if (target.getTargetPrice() != null) {
            msg += String.format(" 目标%.2f", target.getTargetPrice().doubleValue());
        }

        log.info("[IntradayMonitor] {}", msg);

        try {
            notificationService.sendAlert(msg);
        } catch (Exception e) {
            log.warn("[IntradayMonitor] 推送买入信号失败: {}", e.getMessage());
        }

        // SSE广播买入信号
        Map<String, Object> sseEvent = new HashMap<>();
        sseEvent.put("type", "signal");
        sseEvent.put("signalType", "BUY");
        sseEvent.put("stockCode", stockCode);
        sseEvent.put("stockName", target.getStockName());
        sseEvent.put("currentPrice", currentPrice);
        sseEvent.put("score", signal.getTotalScore());
        sseEvent.put("message", msg);
        sseEvent.put("time", LocalDateTime.now().toString());
        broadcastSse(sseEvent);
        recordSignalHistory(sseEvent);
    }

    private void pushWatchSignal(String stockCode, TargetPriceInfo target, double currentPrice,
                                  EntrySignalAnalyzer.BreakoutSignal signal) {
        String msg = String.format("观察中: %s(%s) 现价%.2f 在区间[%.2f~%.2f] 评分%d/100",
                target.getStockName(), stockCode, currentPrice,
                target.getBuyPriceLow().doubleValue(), target.getBuyPriceHigh().doubleValue(),
                signal.getTotalScore());

        log.info("[IntradayMonitor] {}", msg);

        // SSE广播观察信号
        Map<String, Object> sseEvent = new HashMap<>();
        sseEvent.put("type", "signal");
        sseEvent.put("signalType", "WATCH");
        sseEvent.put("stockCode", stockCode);
        sseEvent.put("stockName", target.getStockName());
        sseEvent.put("currentPrice", currentPrice);
        sseEvent.put("score", signal.getTotalScore());
        sseEvent.put("message", msg);
        sseEvent.put("time", LocalDateTime.now().toString());
        broadcastSse(sseEvent);
        recordSignalHistory(sseEvent);
    }

    private void pushStopLossSignal(String stockCode, TargetPriceInfo target, double currentPrice) {
        String msg = String.format("止损警告: %s(%s) 当前价 %.2f 已跌破止损价 %.2f",
                target.getStockName(), stockCode, currentPrice, target.getStopLoss().doubleValue());
        log.warn("[IntradayMonitor] {}", msg);

        try {
            notificationService.sendAlert(msg);
        } catch (Exception e) {
            log.warn("[IntradayMonitor] 推送止损信号失败: {}", e.getMessage());
        }

        // SSE广播止损信号
        Map<String, Object> sseEvent = new HashMap<>();
        sseEvent.put("type", "signal");
        sseEvent.put("signalType", "STOP");
        sseEvent.put("stockCode", stockCode);
        sseEvent.put("stockName", target.getStockName());
        sseEvent.put("currentPrice", currentPrice);
        sseEvent.put("stopLoss", target.getStopLoss().doubleValue());
        sseEvent.put("message", msg);
        sseEvent.put("time", LocalDateTime.now().toString());
        broadcastSse(sseEvent);
        recordSignalHistory(sseEvent);
    }

    /**
     * 记录信号到历史（保留最近50条）
     */
    private void recordSignalHistory(Map<String, Object> sseEvent) {
        signalHistory.add(0, new HashMap<>(sseEvent)); // 最新的在前面
        while (signalHistory.size() > 50) {
            signalHistory.remove(signalHistory.size() - 1);
        }
    }

    /** 获取信号历史记录（供/status接口返回） */
    public List<Map<String, Object>> getSignalHistory() {
        return Collections.unmodifiableList(new ArrayList<>(signalHistory));
    }

    // ── SSE 推送 ──

    public SseEmitter createSseEmitter() {
        SseEmitter emitter = new SseEmitter(4 * 60 * 60 * 1000L);
        emitter.onCompletion(() -> {
            sseEmitters.remove(emitter);
            log.info("[IntradayMonitor] SSE连接关闭, 当前连接数: {}", sseEmitters.size());
        });
        emitter.onTimeout(() -> {
            sseEmitters.remove(emitter);
            log.info("[IntradayMonitor] SSE连接超时, 当前连接数: {}", sseEmitters.size());
        });
        emitter.onError(e -> {
            sseEmitters.remove(emitter);
            log.warn("[IntradayMonitor] SSE连接异常: {}", e.getMessage());
        });
        sseEmitters.add(emitter);
        log.info("[IntradayMonitor] 新SSE连接, 当前连接数: {}", sseEmitters.size());

        // 立即推送当前状态
        try {
            Map<String, Object> statusEvent = new HashMap<>();
            statusEvent.put("type", "status");
            statusEvent.put("monitoring", monitoring);
            statusEvent.put("watchingCount", targetPriceCache.size());
            statusEvent.put("dataDate", dataDate != null ? dataDate.toString() : null);
            emitter.send(SseEmitter.event().name("monitor").data(statusEvent));
        } catch (IOException ignored) {
        }

        return emitter;
    }

    /**
     * SSE广播：实时价格更新（每次轮询后推送）
     */
    private void broadcastPriceUpdate(Map<String, Double> prices) {
        if (sseEmitters.isEmpty()) return;

        Map<String, Object> priceEvent = new HashMap<>();
        priceEvent.put("type", "price");
        priceEvent.put("prices", prices);
        priceEvent.put("changePct", new HashMap<>(latestChangePct));
        priceEvent.put("time", LocalDateTime.now().toString());
        broadcastSse(priceEvent);
    }

    private void broadcastSse(Map<String, Object> event) {
        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : sseEmitters) {
            try {
                emitter.send(SseEmitter.event().name("monitor").data(event));
            } catch (IOException e) {
                dead.add(emitter);
            }
        }
        if (!dead.isEmpty()) {
            sseEmitters.removeAll(dead);
            log.debug("[IntradayMonitor] 清理 {} 个断开的SSE连接", dead.size());
        }
    }

    // ── 公共接口 ──

    public Map<String, TargetPriceInfo> getTargetPriceCache() {
        return Collections.unmodifiableMap(targetPriceCache);
    }

    public boolean isMonitoring() {
        return monitoring;
    }

    /** 获取最新实时价格缓存（供Controller查询） */
    public Map<String, Double> getLatestPrices() {
        return Collections.unmodifiableMap(latestPrices);
    }

    /** 获取最新涨跌幅缓存（供Controller查询） */
    public Map<String, Double> getLatestChangePct() {
        return Collections.unmodifiableMap(latestChangePct);
    }

    // ── 手动扫描 ──

    public ScanResult triggerManualScan(List<String> specifiedCodes) {
        ScanResult result = new ScanResult();
        result.setTriggerTime(LocalDateTime.now());

        log.info("[IntradayMonitor] triggerManualScan 开始, 强制重新加载目标价");
        loadTargetPrices();

        List<String> codesToScan = (specifiedCodes != null && !specifiedCodes.isEmpty())
                ? specifiedCodes : new ArrayList<>(targetPriceCache.keySet());

        if (codesToScan.isEmpty()) {
            result.setMessage("无监控股票，请先确保当日有BUY推荐");
            return result;
        }

        result.setTotalCount(codesToScan.size());

        Map<String, Double> prices = fetchRealtimePrices(codesToScan);
        latestPrices.putAll(prices);

        LocalDateTime now = LocalDateTime.now();
        int hour = now.getHour();
        boolean inAuction = (hour == 9 && now.getMinute() >= 25 && now.getMinute() < 30)
                || (hour == 14 && now.getMinute() >= 55);

        // 并行分析
        List<CompletableFuture<ScanResult.StockScanInfo>> futures = new ArrayList<>();

        for (Map.Entry<String, Double> entry : prices.entrySet()) {
            String code = entry.getKey();
            double currentPrice = entry.getValue();
            TargetPriceInfo target = targetPriceCache.get(code);
            if (target == null) continue;

            // 止损判断（无需K线，立即处理）
            if (target.getStopLoss() != null && currentPrice <= target.getStopLoss().doubleValue()) {
                ScanResult.StockScanInfo stockInfo = new ScanResult.StockScanInfo();
                stockInfo.setStockCode(code);
                stockInfo.setStockName(target.getStockName());
                stockInfo.setCurrentPrice(currentPrice);
                stockInfo.setSignalType("STOP");
                stockInfo.setMessage(String.format("跌破止损价 %.2f", target.getStopLoss().doubleValue()));
                result.addSignal(stockInfo);
                continue;
            }

            // 买入信号判断——并行拉K线
            double buyLow = target.getBuyPriceLow().doubleValue();
            double buyHigh = target.getBuyPriceHigh().doubleValue();
            double triggerLow = buyLow * (1 - proximityPct);
            double triggerHigh = buyHigh * (1 + proximityPct);

            if (currentPrice >= triggerLow && currentPrice <= triggerHigh) {
                CompletableFuture<ScanResult.StockScanInfo> future = CompletableFuture.supplyAsync(() -> {
                    ScanResult.StockScanInfo stockInfo = new ScanResult.StockScanInfo();
                    stockInfo.setStockCode(code);
                    stockInfo.setStockName(target.getStockName());
                    stockInfo.setCurrentPrice(currentPrice);

                    try {
                        EntrySignalAnalyzer.BreakoutSignal signal;
                        if (inAuction) {
                            signal = signalAnalyzer.fallbackPriceOnly(currentPrice, target, code);
                        } else {
                            signal = signalAnalyzer.analyze(code, currentPrice, target);
                        }

                        stockInfo.setScore(signal.getTotalScore());
                        stockInfo.setSignalType(signal.getSignalType());

                        if (signal.isActionable()) {
                            stockInfo.setMessage(signal.toPushMessage());
                        } else {
                            stockInfo.setMessage("观察中: " + signal.getReason());
                        }
                    } catch (Exception e) {
                        stockInfo.setSignalType("ERROR");
                        stockInfo.setMessage("分析异常: " + e.getMessage());
                    }
                    return stockInfo;
                }, klinePool);

                futures.add(future);
            } else {
                ScanResult.StockScanInfo stockInfo = new ScanResult.StockScanInfo();
                stockInfo.setStockCode(code);
                stockInfo.setStockName(target.getStockName());
                stockInfo.setCurrentPrice(currentPrice);
                stockInfo.setMessage("未进入触发区间");
                result.addSkipped(stockInfo);
            }
        }

        // 等待所有并行分析完成
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[IntradayMonitor] 部分手动扫描分析超时: {}", e.getMessage());
        }

        // 收集并行结果
        for (CompletableFuture<ScanResult.StockScanInfo> future : futures) {
            try {
                ScanResult.StockScanInfo info = future.getNow(null);
                if (info != null) {
                    if ("STRONG_BUY".equals(info.getSignalType()) || "BUY_FALLBACK".equals(info.getSignalType())) {
                        result.addSignal(info);
                    } else {
                        result.addWatch(info);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        result.setMessage(String.format("扫描完成: %d只触发信号, %d只观察中, %d只跳过",
                result.getSignalCount(), result.getWatchCount(), result.getSkippedCount()));
        return result;
    }

    // ── 内部数据类 ──

    @Data
    public static class ScanResult {
        private LocalDateTime triggerTime;
        private int totalCount;
        private String message;
        private List<StockScanInfo> signals = new ArrayList<>();
        private List<StockScanInfo> watches = new ArrayList<>();
        private List<StockScanInfo> skipped = new ArrayList<>();

        public void addSignal(StockScanInfo info) { signals.add(info); }
        public void addWatch(StockScanInfo info) { watches.add(info); }
        public void addSkipped(StockScanInfo info) { skipped.add(info); }
        public int getSignalCount() { return signals.size(); }
        public int getWatchCount() { return watches.size(); }
        public int getSkippedCount() { return skipped.size(); }

        @Data
        public static class StockScanInfo {
            private String stockCode;
            private String stockName;
            private double currentPrice;
            private int score;
            private String signalType;
            private String message;
        }
    }

    @Data
    public static class TargetPriceInfo {
        private String stockCode;
        private String stockName;
        private BigDecimal buyPriceLow;
        private BigDecimal buyPriceHigh;
        private BigDecimal stopLoss;
        private BigDecimal targetPrice;
        private String source;
    }
}
