package com.quant.platform.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 分钟K线采集服务
 * 数据源：腾讯 proxy.finance.qq.com
 * 支持：1m/5m/15m/30m/60m
 * 注意：用HTTP而非HTTPS（Windows下HTTPS握手慢7秒）
 */
@Slf4j
@Service
public class MinuteKlineService {

    private final JdbcTemplate clickHouseJdbcTemplate;
    private final ObjectMapper objectMapper;

    @Value("${quant.clickhouse.enabled:true}")
    private boolean chEnabled;

    /** 腾讯分钟K线接口 */
    private static final String KLINE_URL = "https://proxy.finance.qq.com/ifzqgtimg/appstock/app/newfqkline/get?param=%s";

    public MinuteKlineService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        // 需要ClickHouse的JdbcTemplate，这里用MySQL的替代
        this.clickHouseJdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 采集单只股票的分钟K线
     * @param stockCode 股票代码（如 sh600519）
     * @param period 周期：m30, m60 等
     * @param startDate 开始日期
     * @param endDate 结束日期
     * @return 采集到的K线条数
     */
    public int fetchMinuteKline(String stockCode, String period, LocalDate startDate, LocalDate endDate) {
        try {
            // 构造腾讯代码格式
            String tencentCode = convertToTencentCode(stockCode);
            String param = String.format("%s,%s,%s,%s,640,qfq",
                    tencentCode, period,
                    startDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
                    endDate.format(DateTimeFormatter.ISO_LOCAL_DATE));

            String url = String.format(KLINE_URL, param);
            log.info("[MinuteKlineService] 采集分钟K线: code={}, period={}, range={}~{}", stockCode, period, startDate, endDate);

            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .build();

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(30))
                    .GET()
                    .build();

            java.net.http.HttpResponse<String> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("[MinuteKlineService] HTTP错误: code={}, status={}", stockCode, response.statusCode());
                return 0;
            }

            return parseAndSaveKline(stockCode, period, response.body());

        } catch (Exception e) {
            log.error("[MinuteKlineService] 采集失败: code={}, error={}", stockCode, e.getMessage());
            return 0;
        }
    }

    /**
     * 批量采集候选股分钟K线
     */
    public int batchFetchMinuteKline(List<String> stockCodes, String period, LocalDate startDate, LocalDate endDate) {
        int total = 0;
        for (String code : stockCodes) {
            int count = fetchMinuteKline(code, period, startDate, endDate);
            total += count;

            // 限速
            try { Thread.sleep(300); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        log.info("[MinuteKlineService] 批量采集完成: total={}, codes={}", total, stockCodes.size());
        return total;
    }

    /**
     * 解析腾讯返回的K线数据并存入ClickHouse
     */
    private int parseAndSaveKline(String stockCode, String period, String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode data = root.path("data");
            if (data.isMissingNode() || data.isNull()) {
                // 尝试另一种格式
                data = root;
            }

            // 腾讯返回格式: { "code": 0, "data": { "sh600519": { "qfqday30": [...], "qt": {...} } } }
            String tencentCode = convertToTencentCode(stockCode);
            JsonNode stockData = data.path(tencentCode);
            if (stockData.isMissingNode()) {
                // 尝试直接从data下找
                Iterator<String> fieldNames = data.fieldNames();
                if (fieldNames.hasNext()) {
                    stockData = data.path(fieldNames.next());
                }
            }

            // 找到K线数组 (qfqday30, qfqday60, day30 等)
            JsonNode klineArray = null;
            Iterator<Map.Entry<String, JsonNode>> fields = stockData.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (entry.getKey().startsWith("qfq") || entry.getKey().startsWith("day")) {
                    if (entry.getValue().isArray()) {
                        klineArray = entry.getValue();
                        break;
                    }
                }
            }

            if (klineArray == null || !klineArray.isArray()) {
                log.debug("[MinuteKlineService] 无K线数据: code={}", stockCode);
                return 0;
            }

            int count = 0;
            List<Object[]> batch = new ArrayList<>();

            for (JsonNode kline : klineArray) {
                if (!kline.isArray() || kline.size() < 6) continue;

                String datetime = kline.get(0).asText(); // "2026-06-12 10:00"
                double open = kline.get(1).asDouble();
                double close = kline.get(2).asDouble();
                double high = kline.get(3).asDouble();
                double low = kline.get(4).asDouble();
                double volume = kline.get(5).asDouble();

                batch.add(new Object[]{stockCode, datetime, period, open, close, high, low, volume});
                count++;

                if (batch.size() >= 200) {
                    insertKlineBatch(batch);
                    batch.clear();
                }
            }

            if (!batch.isEmpty()) {
                insertKlineBatch(batch);
            }

            log.info("[MinuteKlineService] 解析完成: code={}, period={}, count={}", stockCode, period, count);
            return count;

        } catch (Exception e) {
            log.error("[MinuteKlineService] 解析失败: code={}, error={}", stockCode, e.getMessage());
            return 0;
        }
    }

    /**
     * 批量插入分钟K线到ClickHouse
     */
    private void insertKlineBatch(List<Object[]> batch) {
        if (!chEnabled) return;

        try {
            StringBuilder sql = new StringBuilder("INSERT INTO stock_minute_kline (stock_code, datetime, period, open, close, high, low, volume) VALUES ");
            for (int i = 0; i < batch.size(); i++) {
                if (i > 0) sql.append(", ");
                Object[] row = batch.get(i);
                sql.append(String.format("('%s', '%s', '%s', %.3f, %.3f, %.3f, %.3f, %.0f)",
                        row[0], row[1], row[2], row[3], row[4], row[5], row[6], row[7]));
            }
            clickHouseJdbcTemplate.execute(sql.toString());
        } catch (Exception e) {
            log.warn("[MinuteKlineService] ClickHouse插入失败（表可能不存在）: {}", e.getMessage());
            // 降级到MySQL
            insertKlineBatchMySQL(batch);
        }
    }

    /**
     * 降级：插入到MySQL
     */
    private void insertKlineBatchMySQL(List<Object[]> batch) {
        try {
            String sql = "INSERT IGNORE INTO stock_minute_kline (stock_code, datetime, period, open_price, close_price, high_price, low_price, volume) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
            clickHouseJdbcTemplate.batchUpdate(sql, batch, batch.size(), (ps, row) -> {
                ps.setString(1, (String) row[0]);
                ps.setString(2, (String) row[1]);
                ps.setString(3, (String) row[2]);
                ps.setDouble(4, (Double) row[3]);
                ps.setDouble(5, (Double) row[4]);
                ps.setDouble(6, (Double) row[5]);
                ps.setDouble(7, (Double) row[6]);
                ps.setDouble(8, (Double) row[7]);
            });
        } catch (Exception e) {
            log.warn("[MinuteKlineService] MySQL插入也失败: {}", e.getMessage());
        }
    }

    /**
     * 转换股票代码格式: sh600519 → sh600519, 601077.SH → sh601077
     */
    private String convertToTencentCode(String stockCode) {
        if (stockCode.contains(".")) {
            // 601077.SH → sh601077
            String[] parts = stockCode.split("\\.");
            return parts[1].toLowerCase() + parts[0];
        }
        return stockCode.toLowerCase();
    }
}
