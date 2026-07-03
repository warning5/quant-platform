package com.quant.platform.market;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * 市场情绪指标服务
 * 读取 QVIX(中国恐慌指数) 等市场级指标，供策略使用
 *
 * 数据来源: scripts/collect_qvix.py → ClickHouse market_sentiment
 * QVIX 含义: 数值越高代表市场恐慌越重
 *   - QVIX < 15: 市场平静，可能过度乐观
 *   - QVIX 15~25: 正常波动
 *   - QVIX 25~35: 市场担忧
 *   - QVIX > 35: 市场恐慌，可能是买入机会(逆向)
 */
@Service
@Slf4j
public class MarketSentimentService {

    private final JdbcTemplate clickHouseTemplate;

    @Autowired
    public MarketSentimentService(@Qualifier("clickHouseJdbcTemplate") JdbcTemplate clickHouseTemplate) {
        this.clickHouseTemplate = clickHouseTemplate;
    }

    /**
     * 获取最新 QVIX (50ETF, 最主流)
     */
    public QvixData getLatestQvix() {
        return getLatestQvix("QVIX_50ETF");
    }

    /**
     * 获取最新 QVIX (指定品种)
     */
    public QvixData getLatestQvix(String indicator) {
        String sql = """
            SELECT indicator, trade_date, value
            FROM stock.market_sentiment
            WHERE indicator = ?
            ORDER BY trade_date DESC
            LIMIT 1
            """;
        try {
            Map<String, Object> row = clickHouseTemplate.queryForMap(sql, indicator);
            if (row == null || row.isEmpty()) {
                log.warn("[MarketSentiment] 未找到 QVIX 数据: {}", indicator);
                return null;
            }
            QvixData data = new QvixData();
            data.setIndicator((String) row.get("indicator"));
            // ClickHouse JDBC 可能返回 java.time.LocalDate 或 java.sql.Date
            Object tradeDateObj = row.get("trade_date");
            LocalDate tradeDate;
            if (tradeDateObj instanceof java.sql.Date) {
                tradeDate = ((java.sql.Date) tradeDateObj).toLocalDate();
            } else if (tradeDateObj instanceof LocalDate) {
                tradeDate = (LocalDate) tradeDateObj;
            } else {
                tradeDate = LocalDate.parse(tradeDateObj.toString());
            }
            data.setTradeDate(tradeDate);
            data.setValue(BigDecimal.valueOf(((Number) row.get("value")).doubleValue()));
            data.setLevel(interpretQvix(data.getValue()));
            return data;
        } catch (Exception e) {
            log.error("[MarketSentiment] 查询 QVIX 失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 获取最近N天 QVIX 序列
     */
    public Map<LocalDate, BigDecimal> getQvixSeries(int days) {
        String sql = """
            SELECT trade_date, value
            FROM stock.market_sentiment
            WHERE indicator = 'QVIX_50ETF'
            ORDER BY trade_date DESC
            LIMIT ?
            """;
        try {
            Map<LocalDate, BigDecimal> series = new HashMap<>();
            clickHouseTemplate.query(sql, new Object[]{days}, rs -> {
                series.put(rs.getDate("trade_date").toLocalDate(),
                           BigDecimal.valueOf(rs.getDouble("value")));
            });
            return series;
        } catch (Exception e) {
            log.error("[MarketSentiment] 查询 QVIX 序列失败: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * 解读 QVIX 数值
     */
    public QvixLevel interpretQvix(BigDecimal value) {
        if (value == null) return QvixLevel.UNKNOWN;
        double v = value.doubleValue();
        if (v < 15) return QvixLevel.CALM;
        if (v < 25) return QvixLevel.NORMAL;
        if (v < 35) return QvixLevel.FEAR;
        return QvixLevel.PANIC;
    }

    /**
     * 计算QVIX Z-Score（相对于最近N天的位置）
     * 用于策略：Z>1.5 表示当前恐慌程度异常高（逆向买入信号）
     */
    public BigDecimal getQvixZScore(int lookbackDays) {
        Map<LocalDate, BigDecimal> series = getQvixSeries(lookbackDays);
        if (series.size() < 20) return null;

        var values = series.values().stream()
                .mapToDouble(BigDecimal::doubleValue)
                .toArray();

        double mean = java.util.Arrays.stream(values).average().orElse(0);
        double variance = java.util.Arrays.stream(values).map(x -> (x - mean) * (x - mean)).sum() / values.length;
        double std = Math.sqrt(variance);

        QvixData latest = getLatestQvix();
        if (latest == null) return null;

        double latestVal = latest.getValue().doubleValue();
        if (std == 0) return BigDecimal.ZERO;

        return BigDecimal.valueOf((latestVal - mean) / std)
                .setScale(4, BigDecimal.ROUND_HALF_UP);
    }

    // ============ DTO ============

    @Data
    public static class QvixData {
        private String indicator;
        private LocalDate tradeDate;
        private BigDecimal value;
        private QvixLevel level;
    }

    public enum QvixLevel {
        CALM("平静", "市场平静，可能过度乐观"),
        NORMAL("正常", "正常波动范围"),
        FEAR("担忧", "市场出现担忧情绪"),
        PANIC("恐慌", "市场恐慌，逆向买入机会"),
        UNKNOWN("未知", "");

        private final String label;
        private final String desc;

        QvixLevel(String label, String desc) {
            this.label = label;
            this.desc = desc;
        }

        public String getLabel() { return label; }
        public String getDesc() { return desc; }
    }
}
