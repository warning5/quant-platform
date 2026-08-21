package com.quant.platform.monitor;

import com.quant.platform.calendar.service.TradeCalendarService;
import com.quant.platform.stock.analysis.engine.SellSignalEngine;
import lombok.Data;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

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

    private final EntrySignalAnalyzer signalAnalyzer;
    private final TradeCalendarService tradeCalendarService;
    private final SellSignalEngine sellSignalEngine;
    private final MonitorTargetPriceLoader targetPriceLoader;
    private final MonitorSignalPublisher signalPublisher;
    private final MonitorQuoteClient quoteClient;
    /**
     * K线并行拉取线程池
     */
    private final ExecutorService klinePool;
    /**
     * 独立调度器（不受 Spring TaskScheduler 休眠损坏影响）
     */
    private final ScheduledExecutorService monitorScheduler;
    /**
     * 今日已推送记录: key -> 推送时间
     */
    private final Map<String, LocalDateTime> pushedWithTime = new ConcurrentHashMap<>();
    /**
     * 轮询计数器（用于控制日志输出频率）
     */
    private final AtomicInteger pollCount = new AtomicInteger(0);
    private final int pollIntervalSeconds;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.beans.factory.annotation.Qualifier("clickHouseJdbcTemplate")
    private JdbcTemplate clickHouseJdbcTemplate;

    /**
     * 盘中主力资金流缓存：platformCode -> 当日快照。由低频刷新填充，不写 CH。
     */
    private final Map<String, IntradayMoneyFlowService.MoneyFlowSnapshot> intradayMoneyFlowCache = new ConcurrentHashMap<>();
    /**
     * 盘中资金流刷新线程池（独立，避免阻塞价格轮询）
     */
    private final ExecutorService moneyFlowPool;
    /**
     * 盘中主力资金流采集服务（注入在构造器）
     */
    private final IntradayMoneyFlowService intradayMoneyFlowService;
    private volatile boolean monitoring = false;
    /**
     * 今日是否已发送收盘事件（防止重复）
     */
    private volatile boolean marketClosedSent = false;
    @Value("${quant.monitor.cooldown.buy-minutes:30}")
    private int buyCooldownMinutes;
    @Value("${quant.monitor.cooldown.stop-minutes:60}")
    private int stopCooldownMinutes;
    @Value("${quant.monitor.signal.proximity-pct:0.02}")
    private double proximityPct;

    public IntradayMonitorService(EntrySignalAnalyzer signalAnalyzer, TradeCalendarService tradeCalendarService,
                                  SellSignalEngine sellSignalEngine,
                                  MonitorTargetPriceLoader targetPriceLoader,
                                  MonitorSignalPublisher signalPublisher,
                                  MonitorQuoteClient quoteClient,
                                  IntradayMoneyFlowService intradayMoneyFlowService,
                                  @Value("${quant.monitor.kline-thread-pool-size:4}") int klinePoolSize,
                                  @Value("${quant.monitor.poll-interval-seconds:10}") int pollIntervalSeconds) {
        this.signalAnalyzer = signalAnalyzer;
        this.tradeCalendarService = tradeCalendarService;
        this.sellSignalEngine = sellSignalEngine;
        this.targetPriceLoader = targetPriceLoader;
        this.signalPublisher = signalPublisher;
        this.quoteClient = quoteClient;
        this.intradayMoneyFlowService = intradayMoneyFlowService;
        this.pollIntervalSeconds = pollIntervalSeconds;
        this.klinePool = Executors.newFixedThreadPool(klinePoolSize,
                r -> {
                    Thread t = new Thread(r, "kline-analyze");
                    t.setDaemon(true);
                    return t;
                });
        this.moneyFlowPool = Executors.newSingleThreadExecutor(
                r -> {
                    Thread t = new Thread(r, "moneyflow-refresh");
                    t.setDaemon(true);
                    return t;
                });
        // 使用交易日历获取最近交易日，避免周末/节假日启动时dataDate卡在非交易日
        this.targetPriceLoader.setDataDate(tradeCalendarService.getLatestTradingDay());
        // 启动时从数据库加载自定义股票
        targetPriceLoader.loadCustomStocksFromDb();

        // 独立调度器：不依赖 Spring @Scheduled，避免系统休眠后 TaskScheduler 损坏导致监控失效
        this.monitorScheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "monitor-sched");
            t.setDaemon(true);
            return t;
        });
        // 每 pollIntervalSeconds 秒执行监控循环
        this.monitorScheduler.scheduleAtFixedRate(this::safeMonitorLoop, 10, pollIntervalSeconds, TimeUnit.SECONDS);
        // 每5秒刷新指数行情
        this.monitorScheduler.scheduleAtFixedRate(this::safeRefreshIndexQuotes, 5, 5, TimeUnit.SECONDS);
        log.info("[IntradayMonitor] 独立调度器已启动, pollInterval={}s", pollIntervalSeconds);
    }

    /**
     * 数据日期（零行为变化拆分：状态已迁至 {@link MonitorTargetPriceLoader}）
     */
    public LocalDate getDataDate() {
        return targetPriceLoader.getDataDate();
    }

    // ── 交易日判断（使用数据库交易日历） ──

    private boolean isNonTradingDay(LocalDate date) {
        return !tradeCalendarService.isTradingDay(date);
    }

    /**
     * 获取监控面板显示的数据日期
     * <p>零行为变化拆分：实现已迁至 {@link MonitorTargetPriceLoader}。
     */
    public LocalDate getLatestDataDate() {
        return targetPriceLoader.getLatestDataDate();
    }

    // ── 盘中监控主循环（高频轮询） ──

    /**
     * 高频轮询入口，每10秒执行一次
     * 仅在交易时段（09:30~15:00）内运行
     * 由独立调度器触发（不使用 @Scheduled，避免系统休眠后失效）
     */
    private void safeMonitorLoop() {
        try {
            monitorLoop();
        } catch (Exception e) {
            log.error("[IntradayMonitor] 监控循环异常", e);
        }
    }

    public void monitorLoop() {
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();

        // 节假日/周末跳过（调休补班遇周末仍为非交易日）
        if (isNonTradingDay(today)) return;

        int hour = now.getHour();
        int minute = now.getMinute();

        // A股交易时段：09:30~11:30 和 13:00~15:00（午休 11:30~13:00 不监控）
        boolean inMorningSession = (hour == 9 && minute >= 30) || hour == 10
                || (hour == 11 && minute < 30);
        boolean inAfternoonSession = hour == 13 || hour == 14;
        boolean inTradingHours = inMorningSession || inAfternoonSession;
        if (!inTradingHours) return;

        boolean inAuction = (hour == 9 && minute >= 25 && minute < 30) || (hour == 14 && minute >= 55);

        // 每日重置推送记录 + 收盘标志
        if (hour == 9 && minute == 30 && now.getSecond() < 10) {
            pushedWithTime.clear();
            marketClosedSent = false;
            intradayMoneyFlowCache.clear();
        }

        if (!monitoring) {
            monitoring = true;
            log.info("[IntradayMonitor] ===== 监控启动 ===== 当前时间: {}", now);
            loadTargetPrices();
            // 启动即异步拉一次盘中主力资金流（不阻塞价格轮询）
            CompletableFuture.runAsync(this::refreshIntradayMoneyFlow, moneyFlowPool);
        }

        if (targetPriceLoader.targetPriceCacheRef().isEmpty()) return;

        pollCount.incrementAndGet();
        // 每60秒（6次轮询）输出一次日志
        if (pollCount.get() % 6 == 1) {
            log.info("[IntradayMonitor] 轮询 #{}, 监控{}只股票, SSE连接数: {}",
                    pollCount.get(), targetPriceLoader.targetPriceCacheRef().size(), signalPublisher.sseEmittersRef().size());
        }
        // 每5分钟（30次轮询）顺带刷新盘中主力资金流（异步，不阻塞价格轮询）
        if (pollCount.get() % 30 == 1) {
            CompletableFuture.runAsync(this::refreshIntradayMoneyFlow, moneyFlowPool);
        }

        // 批量获取实时价格
        Map<String, Double> prices = fetchRealtimePrices(new ArrayList<>(targetPriceLoader.targetPriceCacheRef().keySet()));

        // 更新最新价格缓存
        quoteClient.latestPricesRef().putAll(prices);

        // SSE推送实时价格更新
        signalPublisher.broadcastPriceUpdate(prices);

        // 并行分析所有触发区间的股票
        analyzePricesParallel(prices, inAuction);
    }

    /**
     * 定时刷新大盘指数行情（每5秒一次）
     * 由独立调度器触发
     */
    private void safeRefreshIndexQuotes() {
        try {
            refreshIndexQuotes();
        } catch (Exception e) {
            log.error("[IntradayMonitorService] 捕获到未处理异常", e);
            // 静默失败，下次再重试
        }
    }

    public void refreshIndexQuotes() {
        quoteClient.refreshIndexQuotes();
    }

    /**
     * 获取所有指数实时行情（提供给前端和Controller）
     */
    public List<Map<String, Object>> getIndexQuotes() {
        return quoteClient.getIndexQuotes();
    }

    /**
     * 收盘后清理：15:01 触发，广播 market_closed 事件并关闭所有 SSE 连接
     * 仅交易日执行，每天最多执行一次（marketClosedSent 防重复）
     */
    @Scheduled(cron = "0 1 15 * * MON-FRI")
    public void closeMarket() {
        LocalDate today = LocalDate.now();
        // 非交易日跳过
        if (isNonTradingDay(today)) return;
        // 已发送过收盘事件，跳过
        if (marketClosedSent) return;

        marketClosedSent = true;
        monitoring = false;
        log.info("[IntradayMonitor] ===== 收盘清理 ===== 当前连接数: {}", signalPublisher.sseEmittersRef().size());

        // 广播收盘事件
        Map<String, Object> closeEvent = new HashMap<>();
        closeEvent.put("type", "market_closed");
        closeEvent.put("message", "今日交易已结束，盘中监控停止");
        closeEvent.put("time", LocalDateTime.now().toString());
        signalPublisher.broadcastSse(closeEvent);

        // 关闭所有 SSE 连接
        for (SseEmitter emitter : signalPublisher.sseEmittersRef()) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
                log.error("[IntradayMonitorService] 捕获到未处理异常", ignored);
            }
        }
        signalPublisher.sseEmittersRef().clear();
        log.info("[IntradayMonitor] ===== 收盘清理完成 ===== 所有SSE连接已关闭");
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
            TargetPriceInfo target = targetPriceLoader.targetPriceCacheRef().get(code);
            if (target == null) continue;

            // 止损独立判断（无需K线，立即处理）
            if (target.getStopLoss() != null && currentPrice <= target.getStopLoss().doubleValue()) {
                String key = code + "_STOP";
                if (canPush(key, stopCooldownMinutes)) {
                    signalPublisher.pushStopLossSignal(code, target, currentPrice);
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
            TargetPriceInfo target = targetPriceLoader.targetPriceCacheRef().get(code);

            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    EntrySignalAnalyzer.BreakoutSignal signal;
                    if (inAuction) {
                        signal = signalAnalyzer.fallbackPriceOnly(currentPrice, target, code);
                    } else {
                        signal = signalAnalyzer.analyze(code, currentPrice, target);
                    }

                    if (signal.isActionable()) {
                        signalPublisher.pushBuySignal(code, target, currentPrice, signal);
                        markPushed(code + "_BUY");
                    } else {
                        // 区间内但评分不够，推送WATCH信号到前端展示
                        signalPublisher.pushWatchSignal(code, target, currentPrice, signal);
                        log.info("[IntradayMonitor] 观察: {}({}) 现价{} 评分{}/100",
                                target.getStockName(), code, currentPrice, signal.getTotalScore());
                    }
                } catch (Exception e) {
                    // K线分析异常时走降级逻辑（价格已在触发区间内）
                    EntrySignalAnalyzer.BreakoutSignal fallback = signalAnalyzer.fallbackPriceOnly(currentPrice, target, code, true);
                    if (fallback.isActionable()) {
                        signalPublisher.pushBuySignal(code, target, currentPrice, fallback);
                        markPushed(code + "_BUY");
                    } else {
                        signalPublisher.pushWatchSignal(code, target, currentPrice, fallback);
                        log.info("[IntradayMonitor] 观察(降级): {}({}) 现价{} 评分{}/100",
                                target.getStockName(), code, currentPrice, fallback.getTotalScore());
                    }
                    log.warn("[IntradayMonitor] K线分析异常→降级: {} - {}", code, e.getMessage(), e);
                }
            }, klinePool);

            futures.add(future);
        }

        // 等待所有分析完成（最多等30秒，留足K线重试时间：12s请求×2次重试+缓冲）
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(30, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("[IntradayMonitor] 部分K线分析超时，已降级处理");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[IntradayMonitor] K线分析被中断");
        } catch (Exception e) {
            log.warn("[IntradayMonitor] K线分析异常: {}", e.getMessage());
        }
    }

    /**
     * 定时卖点检测：每5分钟检查一次所有监控股票的技术面卖点
     * 独立于买入监控循环，避免频繁拉取K线
     */
    @Scheduled(cron = "0 */5 9-14 * * MON-FRI")
    public void checkSellSignalsPeriodic() {
        LocalDateTime now = LocalDateTime.now();
        if (isNonTradingDay(now.toLocalDate())) return;
        int hour = now.getHour();
        boolean inTradingHours = (hour == 9 && now.getMinute() >= 30) || hour == 10
                || (hour == 11 && now.getMinute() < 30) || hour == 13 || hour == 14;
        if (!inTradingHours) return;
        if (targetPriceLoader.targetPriceCacheRef().isEmpty()) return;

        log.info("[IntradayMonitor] 定时卖点检测开始, 共{}只股票", targetPriceLoader.targetPriceCacheRef().size());

        for (String code : targetPriceLoader.targetPriceCacheRef().keySet()) {
            String key = code + "_SELL";
            if (!canPush(key, 15)) continue; // 15分钟冷却

            try {
                double[][] ohlcv = fetchKlineData(code);
                if (ohlcv == null || ohlcv[3].length < 30) continue;

                SellSignalEngine.SellSignalResult sellResult = sellSignalEngine.checkSellSignals(
                        ohlcv[3], ohlcv[1], ohlcv[2], ohlcv[0], ohlcv[4]);

                if (sellResult.getAction() != SellSignalEngine.SellAction.HOLD) {
                    TargetPriceInfo target = targetPriceLoader.targetPriceCacheRef().get(code);
                    String name = target != null ? target.getStockName() : code;
                    Double currentPrice = quoteClient.latestPricesRef().get(code);
                    if (currentPrice == null) currentPrice = ohlcv[3][ohlcv[3].length - 1];
                    signalPublisher.pushSellSignal(code, name, sellResult, currentPrice);
                    markPushed(key);
                }
            } catch (Exception e) {
                log.warn("[IntradayMonitor] 卖点检测异常: {} - {}", code, e.getMessage());
            }
        }

        log.info("[IntradayMonitor] 定时卖点检测完成");
    }

    /**
     * 从ClickHouse拉取K线数据
     *
     * @return [open[], high[], low[], close[], volume[]]
     */
    private double[][] fetchKlineData(String code) {
        if (clickHouseJdbcTemplate == null) return null;
        try {
            String pureCode = code.split("\\.")[0];
            List<Map<String, Object>> rows = clickHouseJdbcTemplate.queryForList(
                    "SELECT open_price, high_price, low_price, close_price, volume FROM stock.stock_daily FINAL " +
                            "WHERE code = ? ORDER BY trade_date DESC LIMIT 120",
                    pureCode);
            if (rows.isEmpty()) return null;
            int n = rows.size();
            double[] open = new double[n], high = new double[n], low = new double[n], close = new double[n], volume = new double[n];
            for (int i = 0; i < n; i++) {
                Map<String, Object> row = rows.get(n - 1 - i);
                open[i] = ((Number) row.get("open_price")).doubleValue();
                high[i] = ((Number) row.get("high_price")).doubleValue();
                low[i] = ((Number) row.get("low_price")).doubleValue();
                close[i] = ((Number) row.get("close_price")).doubleValue();
                volume[i] = ((Number) row.get("volume")).doubleValue();
            }
            return new double[][]{open, high, low, close, volume};
        } catch (Exception e) {
            log.warn("[IntradayMonitor] 拉取K线失败: {} - {}", code, e.getMessage());
            return null;
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

    public void loadTargetPrices() {
        targetPriceLoader.loadTargetPrices();
    }

    /**
     * 刷新盘中主力资金流缓存（只对当前 watching 列表）。异常已内部吞掉，不影响主循环。
     */
    public void refreshIntradayMoneyFlow() {
        try {
            Set<String> codes = targetPriceLoader.targetPriceCacheRef().keySet();
            if (codes == null || codes.isEmpty()) return;
            Map<String, IntradayMoneyFlowService.MoneyFlowSnapshot> fresh =
                    intradayMoneyFlowService.fetchToday(codes);
            if (!fresh.isEmpty()) {
                intradayMoneyFlowCache.putAll(fresh);
            }
        } catch (Exception e) {
            log.warn("[IntradayMonitor] 盘中主力资金流刷新失败: {}", e.getMessage());
        }
    }

    /**
     * 获取盘中主力资金流缓存（供 MonitorController 暴露给前端）
     */
    public Map<String, IntradayMoneyFlowService.MoneyFlowSnapshot> getIntradayMoneyFlowCache() {
        return intradayMoneyFlowCache;
    }

    /**
     * 添加用户自定义监控股票（同时持久化到数据库）
     *
     * @param info 目标价信息（source自动设为"客户定义"）
     */
    public void addCustomStock(TargetPriceInfo info) {
        targetPriceLoader.addCustomStock(info);
    }

    /**
     * 移除用户自定义监控股票（同时从数据库删除）
     */
    public boolean removeCustomStock(String stockCode) {
        return targetPriceLoader.removeCustomStock(stockCode);
    }

    /**
     * 获取所有自定义股票列表
     */
    public List<TargetPriceInfo> getCustomStocks() {
        return targetPriceLoader.getCustomStocks();
    }

    public Map<String, Double> fetchRealtimePrices(List<String> stockCodes) {
        return quoteClient.fetchRealtimePrices(stockCodes);
    }

    /**
     * 获取信号历史记录（供/status接口返回）
     */
    public List<Map<String, Object>> getSignalHistory() {
        return signalPublisher.getSignalHistory();
    }

    /**
     * 清除信号历史（内存 + 前端状态）
     */
    public void clearSignalHistory() {
        signalPublisher.clearSignalHistory();
    }

    // ── SSE 推送 ──

    public SseEmitter createSseEmitter() {
        SseEmitter emitter = new SseEmitter(4 * 60 * 60 * 1000L);
        emitter.onCompletion(() -> {
            signalPublisher.sseEmittersRef().remove(emitter);
            log.info("[IntradayMonitor] SSE连接关闭, 当前连接数: {}", signalPublisher.sseEmittersRef().size());
        });
        emitter.onTimeout(() -> {
            signalPublisher.sseEmittersRef().remove(emitter);
            log.info("[IntradayMonitor] SSE连接超时, 当前连接数: {}", signalPublisher.sseEmittersRef().size());
        });
        emitter.onError(e -> {
            signalPublisher.sseEmittersRef().remove(emitter);
            log.warn("[IntradayMonitor] SSE连接异常: {}", e.getMessage());
        });
        signalPublisher.sseEmittersRef().add(emitter);
        log.info("[IntradayMonitor] 新SSE连接, 当前连接数: {}", signalPublisher.sseEmittersRef().size());

        // 立即推送当前状态
        try {
            Map<String, Object> statusEvent = new HashMap<>();
            statusEvent.put("type", "status");
            statusEvent.put("monitoring", monitoring);
            statusEvent.put("marketClosed", marketClosedSent);
            statusEvent.put("watchingCount", targetPriceLoader.targetPriceCacheRef().size());
            statusEvent.put("dataDate", targetPriceLoader.getDataDate() != null ? targetPriceLoader.getDataDate().toString() : null);
            emitter.send(SseEmitter.event().name("monitor").data(statusEvent));
        } catch (IOException ignored) {
            log.debug("[IntradayMonitor] 发送监控状态事件失败（客户端可能已断开）");
        }

        return emitter;
    }

    // ── 公共接口 ──

    public Map<String, TargetPriceInfo> getTargetPriceCache() {
        return targetPriceLoader.getTargetPriceCache();
    }

    public boolean isMonitoring() {
        return monitoring;
    }

    /**
     * 手动启动/恢复监控（供 Controller 调用）
     * 场景：系统休眠后 @Scheduled 失效、或非交易时段手动启动
     */
    public void startMonitoring() {
        if (monitoring) {
            log.info("[IntradayMonitor] 监控已在运行中，跳过启动");
            return;
        }
        monitoring = true;
        marketClosedSent = false;
        log.info("[IntradayMonitor] ===== 手动启动监控 =====");
        loadTargetPrices();
    }

    /**
     * 手动停止监控
     */
    public void stopMonitoring() {
        monitoring = false;
        log.info("[IntradayMonitor] ===== 手动停止监控 =====");
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        log.info("[IntradayMonitor] 关闭调度器...");
        monitorScheduler.shutdownNow();
        klinePool.shutdownNow();
    }

    /**
     * 获取最新实时价格缓存（供Controller查询）
     */
    public Map<String, Double> getLatestPrices() {
        return quoteClient.getLatestPrices();
    }

    /**
     * 获取最新涨跌幅缓存（供Controller查询）
     */
    public Map<String, Double> getLatestChangePct() {
        return quoteClient.getLatestChangePct();
    }

    /**
     * 获取最新成交额缓存（亿元，供Controller查询）
     */
    public Map<String, Double> getLatestAmount() {
        return quoteClient.getLatestAmount();
    }

    // ── 手动扫描 ──

    public ScanResult triggerManualScan(List<String> specifiedCodes) {
        ScanResult result = new ScanResult();
        result.setTriggerTime(LocalDateTime.now());

        log.info("[IntradayMonitor] triggerManualScan 开始, 强制重新加载目标价");
        loadTargetPrices();

        List<String> codesToScan = (specifiedCodes != null && !specifiedCodes.isEmpty())
                ? specifiedCodes : new ArrayList<>(targetPriceLoader.targetPriceCacheRef().keySet());

        if (codesToScan.isEmpty()) {
            result.setMessage("无监控股票，请先确保当日有BUY推荐");
            return result;
        }

        result.setTotalCount(codesToScan.size());

        Map<String, Double> prices = fetchRealtimePrices(codesToScan);
        quoteClient.latestPricesRef().putAll(prices);

        LocalDateTime now = LocalDateTime.now();
        int hour = now.getHour();
        boolean inAuction = (hour == 9 && now.getMinute() >= 25 && now.getMinute() < 30)
                || (hour == 14 && now.getMinute() >= 55);

        // 并行分析
        List<CompletableFuture<ScanResult.StockScanInfo>> futures = new ArrayList<>();

        for (Map.Entry<String, Double> entry : prices.entrySet()) {
            String code = entry.getKey();
            double currentPrice = entry.getValue();
            TargetPriceInfo target = targetPriceLoader.targetPriceCacheRef().get(code);
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
                        // K线分析异常时走降级逻辑
                        EntrySignalAnalyzer.BreakoutSignal fallback = signalAnalyzer.fallbackPriceOnly(currentPrice, target, code, true);
                        stockInfo.setScore(fallback.getTotalScore());
                        stockInfo.setSignalType(fallback.getSignalType());
                        stockInfo.setMessage(fallback.toPushMessage());
                        log.warn("[IntradayMonitor] 手动扫描K线异常→降级: {} - {}", code, e.getMessage());
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
                    .get(20, TimeUnit.SECONDS);
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
                log.error("[IntradayMonitorService] 捕获到未处理异常", ignored);
            }
        }

        result.setMessage(String.format("扫描完成: %d只触发信号, %d只观察中, %d只区间外",
                result.getSignalCount(), result.getWatchCount(), result.getSkippedCount()));
        return result;
    }

    /**
     * 模拟执行一个完整的盘中监控周期
     * 不校验是否交易日/交易时段，用于非交易日测试推送效果
     *
     * @param force 是否强制清除推送冷却期，确保本次能真正触发 SSE 推送
     */
    public void simulateOneCycle(boolean force) {
        log.info("[IntradayMonitor] 模拟盘中周期开始, force={}", force);
        if (force) {
            pushedWithTime.clear();
            log.info("[IntradayMonitor] 已清除推送冷却记录");
        }
        loadTargetPrices();
        if (targetPriceLoader.targetPriceCacheRef().isEmpty()) {
            log.warn("[IntradayMonitor] 模拟周期：无监控股票，请先确保有BUY推荐");
            return;
        }
        monitoring = true;
        Map<String, Double> prices = fetchRealtimePrices(new ArrayList<>(targetPriceLoader.targetPriceCacheRef().keySet()));
        quoteClient.latestPricesRef().putAll(prices);
        signalPublisher.broadcastPriceUpdate(prices);
        analyzePricesParallel(prices, false);
        log.info("[IntradayMonitor] 模拟盘中周期结束");
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

        public void addSignal(StockScanInfo info) {
            signals.add(info);
        }

        public void addWatch(StockScanInfo info) {
            watches.add(info);
        }

        public void addSkipped(StockScanInfo info) {
            skipped.add(info);
        }

        public int getSignalCount() {
            return signals.size();
        }

        public int getWatchCount() {
            return watches.size();
        }

        public int getSkippedCount() {
            return skipped.size();
        }

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
