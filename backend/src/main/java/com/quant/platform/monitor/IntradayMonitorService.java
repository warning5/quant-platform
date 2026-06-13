package com.quant.platform.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.notification.NotificationService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 盘中实时监控服务
 * 每60秒批量查询候选股实时价格（qt.gtimg.cn）
 * 与目标买入价比较，触达时生成推送
 */
@Slf4j
@Service
public class IntradayMonitorService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final NotificationService notificationService;

    /** 监控状态：是否处于交易时段 */
    private volatile boolean monitoring = false;

    /** 候选股目标价缓存: stockCode -> TargetPriceInfo */
    private final Map<String, TargetPriceInfo> targetPriceCache = new ConcurrentHashMap<>();

    /** 今日已推送记录，避免重复推送 */
    private final Set<String> pushedToday = ConcurrentHashMap.newKeySet();

    /** 腾讯实时行情URL（用HTTP，Windows下HTTPS握手慢7秒） */
    private static final String QUOTE_URL = "http://qt.gtimg.cn/q=%s";

    public IntradayMonitorService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, NotificationService notificationService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.notificationService = notificationService;
    }

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

        // 每日重置推送记录
        if (hour == 9 && minute == 30) {
            pushedToday.clear();
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

            // 判断是否触达买入价
            if (target.getBuyPriceHigh() != null && currentPrice <= target.getBuyPriceHigh().doubleValue()) {
                if (currentPrice >= target.getBuyPriceLow().doubleValue()) {
                    // 在买入区间内
                    String key = code + "_BUY_" + now.toLocalDate();
                    if (!pushedToday.contains(key)) {
                        pushBuySignal(code, target, currentPrice);
                        pushedToday.add(key);
                    }
                }
            }

            // 判断止损
            if (target.getStopLoss() != null && currentPrice <= target.getStopLoss().doubleValue()) {
                String key = code + "_STOP_" + now.toLocalDate();
                if (!pushedToday.contains(key)) {
                    pushStopLossSignal(code, target, currentPrice);
                    pushedToday.add(key);
                }
            }
        }
    }

    public void loadTargetPrices() {
        targetPriceCache.clear();
        try {
            // 从llm_analysis获取BUY推荐的买入价区间
            String sql = "SELECT a.stock_code, a.stock_name, a.buy_price_low, a.buy_price_high, " +
                    "a.stop_loss, a.target_price, a.recommendation " +
                    "FROM llm_analysis a " +
                    "WHERE a.analysis_date = CURDATE() AND a.recommendation = 'BUY' " +
                    "AND a.buy_price_high IS NOT NULL";
            jdbcTemplate.query(sql, rs -> {
                TargetPriceInfo info = new TargetPriceInfo();
                info.setStockCode(rs.getString("stock_code"));
                info.setStockName(rs.getString("stock_name"));
                info.setBuyPriceLow(rs.getBigDecimal("buy_price_low"));
                info.setBuyPriceHigh(rs.getBigDecimal("buy_price_high"));
                info.setStopLoss(rs.getBigDecimal("stop_loss"));
                info.setTargetPrice(rs.getBigDecimal("target_price"));
                targetPriceCache.put(info.getStockCode(), info);
            });
            log.info("[IntradayMonitor] 加载目标价: {} 只股票", targetPriceCache.size());
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

    private void pushBuySignal(String stockCode, TargetPriceInfo target, double currentPrice) {
        String msg = String.format("买入信号: %s(%s) 当前价 %.2f 在买入区间 [%.2f, %.2f]",
                target.getStockName(), stockCode, currentPrice,
                target.getBuyPriceLow().doubleValue(), target.getBuyPriceHigh().doubleValue());
        log.info("[IntradayMonitor] {}", msg);

        try {
            notificationService.sendAlert(msg);
        } catch (Exception e) {
            log.warn("[IntradayMonitor] 推送买入信号失败: {}", e.getMessage());
        }
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

    @Data
    public static class TargetPriceInfo {
        private String stockCode;
        private String stockName;
        private BigDecimal buyPriceLow;
        private BigDecimal buyPriceHigh;
        private BigDecimal stopLoss;
        private BigDecimal targetPrice;
    }
}
