package com.quant.platform.monitor;

import com.quant.platform.calendar.service.TradeCalendarService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MonitorQuoteClient —— 由 IntradayMonitorService 零行为变化拆分而来。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonitorQuoteClient {

    private final TradeCalendarService tradeCalendarService;

    private static final String QUOTE_URL = "http://qt.gtimg.cn/q=%s";

    /** 最新实时价格缓存: stockCode -> price（供SSE推送和手动查询用） */
    private final Map<String, Double> latestPrices = new ConcurrentHashMap<>();
    private final Map<String, Double> latestChangePct = new ConcurrentHashMap<>();
    /** 指数实时行情缓存: code -> {name, price, changePct, changeAmount, time} */
    private final Map<String, Map<String, Object>> indexQuoteCache = new ConcurrentHashMap<>();
    /** 指数代码到名称映射 */
    private static final Map<String, String> INDEX_NAME_MAP = new LinkedHashMap<>();
    static {
        INDEX_NAME_MAP.put("sh000001", "上证指数");
        INDEX_NAME_MAP.put("sz399001", "深证成指");
        INDEX_NAME_MAP.put("sh000300", "沪深300");
        INDEX_NAME_MAP.put("sh000016", "上证50");
        INDEX_NAME_MAP.put("sh000905", "中证500");
        INDEX_NAME_MAP.put("sh000852", "中证1000");
        INDEX_NAME_MAP.put("sz399303", "国证2000");      // 微盘股代表（中证2000 CSI内部码932302，腾讯不支持）
        INDEX_NAME_MAP.put("sz399006", "创业板指");
        INDEX_NAME_MAP.put("sh000688", "科创50");
        INDEX_NAME_MAP.put("bj899050", "北证50");
        INDEX_NAME_MAP.put("sz399808", "中证新能源");
        INDEX_NAME_MAP.put("sz399975", "证券公司");
        INDEX_NAME_MAP.put("sz399967", "中证军工");
        INDEX_NAME_MAP.put("sh000985", "中证全指");
        INDEX_NAME_MAP.put("sh000906", "中证800");
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
                        .version(java.net.http.HttpClient.Version.HTTP_1_1)
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
                log.error("[MonitorQuoteClient] 捕获到未处理异常", ignored);
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

    public void refreshIndexQuotes() {
        if (isNonTradingDay(LocalDate.now())) return;
        try {
            String codesParam = String.join(",", INDEX_NAME_MAP.keySet());
            String url = String.format(QUOTE_URL, codesParam);
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .version(java.net.http.HttpClient.Version.HTTP_1_1)
                    .connectTimeout(java.time.Duration.ofSeconds(3))
                    .build();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(5))
                    .GET()
                    .build();
            java.net.http.HttpResponse<String> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                parseIndexQuotes(response.body());
            }
        } catch (Exception e) {
            log.error("[MonitorQuoteClient] 捕获到未处理异常", e);
            // 静默失败，下次再重试
        }
    }

    /**
     * 解析指数行情（与个股共用 qt.gtimg.cn，字段顺序相同：fields[3]现价, fields[32]涨跌幅%）
     */
    private void parseIndexQuotes(String body) {
        if (body == null || body.isEmpty()) return;
        String[] lines = body.split(";");
        for (String line : lines) {
            if (line.isEmpty()) continue;
            try {
                int eqIdx = line.indexOf('=');
                if (eqIdx < 0) continue;
                String varName = line.substring(0, eqIdx).trim();
                String value = line.substring(eqIdx + 1).trim();
                if (value.isEmpty() || value.equals("\"\"")) continue;

                String code = varName.replace("v_", "").replace("s_", "");
                String content = value.replace("\"", "");
                String[] fields = content.split("~");
                if (fields.length < 6) continue;

                Map<String, Object> info = new LinkedHashMap<>();
                info.put("code", code);
                info.put("name", INDEX_NAME_MAP.getOrDefault(code, fields[1]));
                try { info.put("price", Double.parseDouble(fields[3])); } catch (Exception e) { continue; }
                try { info.put("changePct", Double.parseDouble(fields[32])); } catch (Exception e) { info.put("changePct", 0.0); }
                try { info.put("changeAmount", Double.parseDouble(fields[31])); } catch (Exception e) { info.put("changeAmount", 0.0); }
                info.put("time", LocalDateTime.now().toString());

                indexQuoteCache.put(code, info);
            } catch (Exception ignored) {
                log.error("[MonitorQuoteClient] 捕获到未处理异常", ignored);
            }
        }
    }

    /** 获取所有指数实时行情（提供给前端和Controller） */
    public List<Map<String, Object>> getIndexQuotes() {
        return new ArrayList<>(indexQuoteCache.values());
    }

    /** 获取最新实时价格缓存（供Controller查询） */
    public Map<String, Double> getLatestPrices() {
        return Collections.unmodifiableMap(latestPrices);
    }

    /** 获取最新涨跌幅缓存（供Controller查询） */
    public Map<String, Double> getLatestChangePct() {
        return Collections.unmodifiableMap(latestChangePct);
    }

    // ── 交易日判断（与 IntradayMonitorService 同实现，避免反向依赖） ──

    private boolean isNonTradingDay(LocalDate date) {
        return !tradeCalendarService.isTradingDay(date);
    }

    /** 可变引用：供 IntradayMonitorService 按原语义直接读写实时价缓存 */
    Map<String, Double> latestPricesRef() {
        return latestPrices;
    }

    /** 可变引用：供 IntradayMonitorService 按原语义直接读写涨跌幅缓存 */
    Map<String, Double> latestChangePctRef() {
        return latestChangePct;
    }
}
