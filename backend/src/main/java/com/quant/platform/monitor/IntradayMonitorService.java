package com.quant.platform.monitor;

import com.quant.platform.notification.NotificationService;
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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 盘中实时监控服务
 * 每60秒批量查询候选股实时价格（qt.gtimg.cn）
 * 与目标买入价比较，触达时进行入场信号评分后推送
 *
 * 改进点：
 * - 入场信号4维评分（突破+均线+量价+回踩）
 * - 止损独立判断，直接推送不参与评分
 * - 冷却期防重复推送（买入30分钟，止损60分钟）
 * - API失败降级为纯价格判断
 */
@Slf4j
@Service
public class IntradayMonitorService {

    private final JdbcTemplate jdbcTemplate;
    private final NotificationService notificationService;
    private final EntrySignalAnalyzer signalAnalyzer;

    /** 监控状态：是否处于交易时段 */
    private volatile boolean monitoring = false;

    /** 候选股目标价缓存: stockCode -> TargetPriceInfo */
    private final Map<String, TargetPriceInfo> targetPriceCache = new ConcurrentHashMap<>();

    /** 今日已推送记录: key -> 推送时间 */
    private final Map<String, LocalDateTime> pushedWithTime = new ConcurrentHashMap<>();

    /** SSE连接列表：前端订阅实时信号 */
    private final CopyOnWriteArrayList<SseEmitter> sseEmitters = new CopyOnWriteArrayList<>();

    /** 买入信号冷却期（分钟） */
    @Value("${quant.monitor.cooldown.buy-minutes:30}")
    private int buyCooldownMinutes;

    /** 止损信号冷却期（分钟） */
    @Value("${quant.monitor.cooldown.stop-minutes:60}")
    private int stopCooldownMinutes;

    /** 当前监控基于的数据日期（非交易日时取最近交易日） */
    private volatile LocalDate dataDate;

    /** 触发区间外扩比例 */
    @Value("${quant.monitor.signal.proximity-pct:0.02}")
    private double proximityPct;

    /** 腾讯实时行情URL（用HTTP，Windows下HTTPS握手慢7秒） */
    private static final String QUOTE_URL = "http://qt.gtimg.cn/q=%s";

    // ── 中国交易日判断 ──
    /** 2026年法定节假日 */
    private static final Set<String> HOLIDAYS_2026 = Set.of(
            "2026-01-01", "2026-01-02", "2026-01-03",
            "2026-02-17", "2026-02-18", "2026-02-19", "2026-02-20",
            "2026-02-21", "2026-02-22", "2026-02-23",
            "2026-04-04", "2026-04-05", "2026-04-06",
            "2026-05-01", "2026-05-02", "2026-05-03", "2026-05-04", "2026-05-05",
            "2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04",
            "2026-10-05", "2026-10-06", "2026-10-07"
    );
    /** 2026年调休上班日（周末上班算交易日） */
    private static final Set<String> MAKEUP_DAYS_2026 = Set.of(
            "2026-02-14", "2026-02-15", "2026-10-10"
    );

    /** 判断是否为非交易日 */
    private static boolean isNonTradingDay(LocalDate date) {
        String ds = date.toString();
        if (MAKEUP_DAYS_2026.contains(ds)) return false;
        if (HOLIDAYS_2026.contains(ds)) return true;
        java.time.DayOfWeek dow = date.getDayOfWeek();
        return dow == java.time.DayOfWeek.SATURDAY || dow == java.time.DayOfWeek.SUNDAY;
    }

    public IntradayMonitorService(JdbcTemplate jdbcTemplate, NotificationService notificationService, EntrySignalAnalyzer signalAnalyzer) {
        this.jdbcTemplate = jdbcTemplate;
        this.notificationService = notificationService;
        this.signalAnalyzer = signalAnalyzer;
        this.dataDate = LocalDate.now(); // 初始化
    }

    /**
     * 获取最新有推荐数据的交易日（非交易日时往前找最近交易日）
     * 优先取 llm_analysis 的最新日期，其次取 stock_recommendation 的最新日期
     */
    public LocalDate getLatestDataDate() {
        LocalDate result = null;
        try {
            String sql1 = "SELECT MAX(analysis_date) FROM llm_analysis WHERE analysis_date <= CURDATE()";
            LocalDate d1 = jdbcTemplate.queryForObject(sql1, LocalDate.class);
            if (d1 != null) { result = d1; log.info("[IntradayMonitor] llm_analysis最新日期: {}", d1); }
        } catch (Exception e) { log.warn("[IntradayMonitor] 查询llm_analysis最新日期失败: {}", e.getMessage()); }
        if (result == null) {
            try {
                String sql2 = "SELECT MAX(recommend_date) FROM stock_recommendation WHERE recommend_date <= CURDATE()";
                LocalDate d2 = jdbcTemplate.queryForObject(sql2, LocalDate.class);
                if (d2 != null) { result = d2; log.info("[IntradayMonitor] stock_recommendation最新日期: {}", d2); }
            } catch (Exception e) { log.warn("[IntradayMonitor] 查询stock_recommendation最新日期失败: {}", e.getMessage()); }
        }
        if (result == null) {
            result = LocalDate.now().minusDays(1);
            log.info("[IntradayMonitor] 无历史数据，使用昨天: {}", result);
        }
        // 如果查到的日期是非交易日，往前找最近的交易日
        while (isNonTradingDay(result)) {
            result = result.minusDays(1);
        }
        log.info("[IntradayMonitor] 最终使用数据日期: {}", result);
        this.dataDate = result;
        return result;
    }

    /** 获取当前监控基于的数据日期（供Controller展示用） */
    public LocalDate getDataDate() { return this.dataDate; }

    /**
     * 盘中监控主循环
     * 每分钟执行一次，仅在交易时段（09:30~15:00）内运行
     */
    @Scheduled(cron = "0 * 9-14 * * 1-5")  // 周一到周五，9:00~14:59每分钟
    public void monitorLoop() {
        LocalDateTime now = LocalDateTime.now();
        int hour = now.getHour();
        int minute = now.getMinute();

        // 仅在 09:30~15:00 执行
        boolean inTradingHours = (hour == 9 && minute >= 30) || (hour >= 10 && hour < 15);
        if (!inTradingHours) return;

        // 集合竞价阶段（09:25~09:30）和收盘竞价（14:55~15:00）跳过K线分析，只用快照
        boolean inAuction = (hour == 9 && minute >= 25 && minute < 30) || (hour == 14 && minute >= 55);

        // 每日重置推送记录
        if (hour == 9 && minute == 30) {
            pushedWithTime.clear();
        }

        if (!monitoring) {
            monitoring = true;
            loadTargetPrices();
        }

        if (targetPriceCache.isEmpty()) return;

        // 批量获取实时价格
        Map<String, Double> prices = fetchRealtimePrices(new ArrayList<>(targetPriceCache.keySet()));

        // 比较目标价
        for (Map.Entry<String, Double> entry : prices.entrySet()) {
            String code = entry.getKey();
            double currentPrice = entry.getValue();
            TargetPriceInfo target = targetPriceCache.get(code);
            if (target == null) continue;

            // === 1. 止损独立判断（直接推送，不参与评分） ===
            if (target.getStopLoss() != null && currentPrice <= target.getStopLoss().doubleValue()) {
                String key = code + "_STOP";
                if (canPush(key, stopCooldownMinutes)) {
                    pushStopLossSignal(code, target, currentPrice);
                    markPushed(key);
                }
                continue;  // 止损后不再判断买入
            }

            // === 2. 买入信号判断（带入场评分） ===
            double buyLow = target.getBuyPriceLow().doubleValue();
            double buyHigh = target.getBuyPriceHigh().doubleValue();
            double triggerLow = buyLow * (1 - proximityPct);
            double triggerHigh = buyHigh * (1 + proximityPct);

            // 价格进入触发区间附近
            if (currentPrice >= triggerLow && currentPrice <= triggerHigh) {
                String key = code + "_BUY";
                if (canPush(key, buyCooldownMinutes)) {
                    // 竞价阶段不做K线分析，直接用纯价格判断
                    EntrySignalAnalyzer.BreakoutSignal signal;
                    if (inAuction) {
                        signal = signalAnalyzer.fallbackPriceOnly(currentPrice, target, code);
                    } else {
                        signal = signalAnalyzer.analyze(code, currentPrice, target);
                    }

                    if (signal.isActionable()) {
                        pushBuySignal(code, target, currentPrice, signal);
                        markPushed(key);
                    } else if ("WATCH".equals(signal.getSignalType())) {
                        // 关注信号，仅日志不推送
                        log.info("[IntradayMonitor] 关注: {}({}) 现价{:.2f} 评分{}/100",
                                target.getStockName(), code, currentPrice, signal.getTotalScore());
                    }
                }
            }
        }
    }

    /**
     * 检查冷却期：距离上次推送是否已超过cooldown分钟
     */
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
        targetPriceCache.clear();
        LocalDate latestDate = getLatestDataDate();
        String dateStr = latestDate.toString();
        log.info("[IntradayMonitor] ===== 加载目标价 START ===== 数据日期: {}", dateStr);

        // 诊断：先查所有BUY记录（独立try-catch，不影响主流程）
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
            // === 数据源1: llm_analysis BUY推荐（精确止损/目标价） ===
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

            // === 数据源2: stock_recommendation 智能推荐（补充LLM未覆盖的） ===
            String recSql = String.format(
                    "SELECT r.stock_code, r.stock_name, r.suggested_buy_price, r.close_price " +
                    "FROM stock_recommendation r " +
                    "WHERE r.recommend_date = '%s' AND r.action_tag = 'BUY' " +
                    "AND r.suggested_buy_price IS NOT NULL", dateStr);
            jdbcTemplate.query(recSql, rs -> {
                String code = rs.getString("stock_code");
                // LLM数据优先，已存在则跳过
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
    }

    /**
     * 批量获取实时价格
     * 使用腾讯qt.gtimg.cn，约500只/次
     */
    public Map<String, Double> fetchRealtimePrices(List<String> stockCodes) {
        Map<String, Double> prices = new LinkedHashMap<>();
        if (stockCodes.isEmpty()) return prices;

        try {
            // 转换为腾讯代码格式
            List<String> tencentCodes = new ArrayList<>();
            Map<String, String> reverseMap = new HashMap<>();  // tencentCode -> stockCode

            for (String code : stockCodes) {
                String tc = convertToTencentCode(code);
                tencentCodes.add(tc);
                reverseMap.put(tc, code);
            }

            // 分批（每批最多500只）
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

                // 限速
                if (i + batchSize < tencentCodes.size()) {
                    try { Thread.sleep(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                }
            }
        } catch (Exception e) {
            log.error("[IntradayMonitor] 获取实时价格失败: {}", e.getMessage());
        }

        return prices;
    }

    /**
     * 解析腾讯实时行情返回
     * 格式: v_sh600519="1~贵州茅台~600519~1320.92~..."
     * [3]当前价
     */
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

                // 提取代码: v_sh600519 → sh600519
                String prefix = varName.replace("v_", "").replace("s_", "");

                // 解析数据
                String content = value.replace("\"", "");
                String[] fields = content.split("~");
                if (fields.length > 3) {
                    double currentPrice = Double.parseDouble(fields[3]);
                    String stockCode = reverseMap.getOrDefault(prefix, prefix);
                    prices.put(stockCode, currentPrice);
                }
            } catch (Exception ignored) {
                // 单条解析失败不影响整体
            }
        }
    }

    private void pushBuySignal(String stockCode, TargetPriceInfo target, double currentPrice,
                               EntrySignalAnalyzer.BreakoutSignal signal) {
        String msg = signal.toPushMessage();
        // 补充止损和目标价信息
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
    }

    private String convertToTencentCode(String stockCode) {
        if (stockCode.contains(".")) {
            String[] parts = stockCode.split("\\.");
            return parts[1].toLowerCase() + parts[0];
        }
        return stockCode.toLowerCase();
    }

    public Map<String, TargetPriceInfo> getTargetPriceCache() {
        return Collections.unmodifiableMap(targetPriceCache);
    }

    public boolean isMonitoring() {
        return monitoring;
    }

    // ── SSE 推送 ──

    /** 创建SSE连接（前端订阅） */
    public SseEmitter createSseEmitter() {
        // 超时设为交易时段长度（约4小时）
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

        // 立即推送当前监控状态
        try {
            Map<String, Object> statusEvent = new HashMap<>();
            statusEvent.put("type", "status");
            statusEvent.put("monitoring", monitoring);
            statusEvent.put("watchingCount", targetPriceCache.size());
            statusEvent.put("dataDate", dataDate != null ? dataDate.toString() : null);
            emitter.send(SseEmitter.event().name("monitor").data(statusEvent));
        } catch (IOException ignored) {}

        return emitter;
    }

    /** 向所有SSE客户端广播事件 */
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

    /**
     * 手动触发一次监控扫描
     * @param specifiedCodes 可选，指定股票代码；null或空则扫描全部监控股票
     * @return 扫描结果
     */
    public ScanResult triggerManualScan(List<String> specifiedCodes) {
        ScanResult result = new ScanResult();
        result.setTriggerTime(LocalDateTime.now());

        // 手动触发时强制重新加载目标价（确保拿到最新数据）
        log.info("[IntradayMonitor] triggerManualScan 开始, 强制重新加载目标价");
        loadTargetPrices();

        List<String> codesToScan = (specifiedCodes != null && !specifiedCodes.isEmpty())
                ? specifiedCodes : new ArrayList<>(targetPriceCache.keySet());

        if (codesToScan.isEmpty()) {
            result.setMessage("无监控股票，请先确保当日有BUY推荐");
            return result;
        }

        result.setTotalCount(codesToScan.size());

        // 批量获取实时价格
        Map<String, Double> prices = fetchRealtimePrices(codesToScan);

        // 判断交易时段（手动触发时如实填写，不做K线分析的限制）
        LocalDateTime now = LocalDateTime.now();
        int hour = now.getHour();
        boolean inAuction = (hour == 9 && now.getMinute() >= 25 && now.getMinute() < 30)
                || (hour == 14 && now.getMinute() >= 55);

        // 比较目标价
        for (Map.Entry<String, Double> entry : prices.entrySet()) {
            String code = entry.getKey();
            double currentPrice = entry.getValue();
            TargetPriceInfo target = targetPriceCache.get(code);
            if (target == null) continue;

            ScanResult.StockScanInfo stockInfo = new ScanResult.StockScanInfo();
            stockInfo.setStockCode(code);
            stockInfo.setStockName(target.getStockName());
            stockInfo.setCurrentPrice(currentPrice);

            // 止损判断
            if (target.getStopLoss() != null && currentPrice <= target.getStopLoss().doubleValue()) {
                stockInfo.setSignalType("STOP");
                stockInfo.setMessage(String.format("跌破止损价 %.2f", target.getStopLoss().doubleValue()));
                result.addSignal(stockInfo);
                continue;
            }

            // 买入信号判断
            double buyLow = target.getBuyPriceLow().doubleValue();
            double buyHigh = target.getBuyPriceHigh().doubleValue();
            double triggerLow = buyLow * (1 - proximityPct);
            double triggerHigh = buyHigh * (1 + proximityPct);

            if (currentPrice >= triggerLow && currentPrice <= triggerHigh) {
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
                    result.addSignal(stockInfo);
                } else {
                    stockInfo.setMessage("观察中: " + signal.getReason());
                    result.addWatch(stockInfo);
                }
            } else {
                stockInfo.setMessage("未进入触发区间");
                result.addSkipped(stockInfo);
            }
        }

        result.setMessage(String.format("扫描完成: %d只触发信号, %d只观察中, %d只跳过",
                result.getSignalCount(), result.getWatchCount(), result.getSkippedCount()));
        return result;
    }

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
        /** 来源: LLM / 推荐 */
        private String source;
    }
}
