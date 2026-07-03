package com.quant.platform.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 入场时机判断器
 * 基于分钟K线进行4维信号评分：
 * 1. 突破确认(35分): 价格站稳买入区间上沿
 * 2. 均线排列(20分): m5级别MA5>MA10>MA30多头排列
 * 3. 量价配合(25分): 上涨+放量 vs 上涨+缩量
 * 4. 回踩确认(20分): 突破后回踩不破支撑
 *
 * 止损信号独立判断，不参与评分，直接推送
 */
@Slf4j
@Component
public class EntrySignalAnalyzer {

    private final ObjectMapper objectMapper;

    @Value("${quant.monitor.signal.threshold:70}")
    private int signalThreshold;

    /** 触发区间外扩比例（默认2%） */
    @Value("${quant.monitor.signal.proximity-pct:0.02}")
    private double proximityPct;

    /** 腾讯分钟K线接口（mkline支持m5，newfqkline只支持day/week/month） */
    private static final String KLINE_URL = "https://ifzq.gtimg.cn/appstock/app/kline/mkline?param=%s&_var=m5_today&r=%f";

    /** 复用HttpClient避免每次新建（连接池+keep-alive） */
    private final java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(5))
            .build();

    /** 信号权重配置 */
    private static final int W_BREAKOUT = 35;
    private static final int W_MA = 20;
    private static final int W_VOLUME = 25;
    private static final int W_PULLBACK = 20;

    public EntrySignalAnalyzer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 分析入场信号
     * @param stockCode 股票代码（纯代码如600519）
     * @param currentPrice 当前实时价格
     * @param target 目标价信息
     * @return 入场信号评分结果
     */
    public BreakoutSignal analyze(String stockCode, double currentPrice, IntradayMonitorService.TargetPriceInfo target) {
        BreakoutSignal signal = new BreakoutSignal();
        signal.setStockCode(stockCode);
        signal.setStockName(target.getStockName());
        signal.setCurrentPrice(currentPrice);
        signal.setTimestamp(java.time.LocalDateTime.now());

        // 计算触发区间（买入区间外扩proximityPct）
        double buyLow = target.getBuyPriceLow().doubleValue();
        double buyHigh = target.getBuyPriceHigh().doubleValue();
        double triggerLow = buyLow * (1 - proximityPct);
        double triggerHigh = buyHigh * (1 + proximityPct);

        signal.setTriggerLow(triggerLow);
        signal.setTriggerHigh(triggerHigh);

        // 如果价格完全不在触发区间附近，直接返回零分
        if (currentPrice < triggerLow || currentPrice > triggerHigh * 1.05) {
            signal.setTotalScore(0);
            signal.setSignalType("NONE");
            signal.setReason("价格不在触发区间附近");
            return signal;
        }

        // 拉取m5 K线
        List<KlineBar> klineBars = fetchM5Kline(stockCode);
        if (klineBars == null || klineBars.size() < 10) {
            // K线获取失败，降级为纯价格判断
            return fallbackPriceOnly(currentPrice, target, stockCode);
        }

        // 1. 突破确认评分 (0~35)
        int breakoutScore = scoreBreakout(klineBars, buyHigh, currentPrice);
        signal.setBreakoutScore(breakoutScore);

        // 2. 均线排列评分 (0~20)
        int maScore = scoreMA(klineBars);
        signal.setMaScore(maScore);

        // 3. 量价配合评分 (0~25)
        int volumeScore = scoreVolume(klineBars);
        signal.setVolumeScore(volumeScore);

        // 4. 回踩确认评分 (0~20)
        int pullbackScore = scorePullback(klineBars, buyLow, buyHigh, currentPrice);
        signal.setPullbackScore(pullbackScore);

        int total = breakoutScore + maScore + volumeScore + pullbackScore;
        signal.setTotalScore(total);
        signal.setSignalType(total >= signalThreshold ? "STRONG_BUY" : total >= 50 ? "WATCH" : "NONE");
        signal.setReason(buildReason(breakoutScore, maScore, volumeScore, pullbackScore));

        return signal;
    }

    /**
     * 1. 突破确认评分
     * 连续3根m5收在buyPriceHigh上方 → 满分
     * 2根 → 70%，1根 → 40%，0根但接近 → 10%
     */
    private int scoreBreakout(List<KlineBar> bars, double buyHigh, double currentPrice) {
        int consecutiveAbove = 0;
        for (int i = bars.size() - 1; i >= Math.max(0, bars.size() - 5); i--) {
            if (bars.get(i).close >= buyHigh) {
                consecutiveAbove++;
            } else {
                break;
            }
        }

        if (consecutiveAbove >= 3) return W_BREAKOUT;
        if (consecutiveAbove == 2) return (int) (W_BREAKOUT * 0.7);
        if (consecutiveAbove == 1) return (int) (W_BREAKOUT * 0.4);

        // 虽未突破但接近上沿（1%以内）
        if (currentPrice >= buyHigh * 0.99) return (int) (W_BREAKOUT * 0.1);
        return 0;
    }

    /**
     * 2. 均线排列评分
     * MA5 > MA10 > MA30 → 满分
     * MA5 > MA10 > MA30 部分满足 → 按比例
     */
    private int scoreMA(List<KlineBar> bars) {
        if (bars.size() < 30) return 0;

        double ma5 = calcMA(bars, 5);
        double ma10 = calcMA(bars, 10);
        double ma30 = calcMA(bars, 30);

        int score = 0;
        // MA5 > MA10 (+10)
        if (ma5 > ma10) score += W_MA / 2;
        // MA10 > MA30 (+5)
        if (ma10 > ma30) score += W_MA / 4;
        // MA5斜率向上（最近3根趋势向上）(+5)
        if (bars.size() >= 8) {
            double recentSlope = bars.get(bars.size() - 1).close - bars.get(bars.size() - 4).close;
            if (recentSlope > 0) score += W_MA / 4;
        }

        return Math.min(score, W_MA);
    }

    /**
     * 3. 量价配合评分
     * 上涨+放量 → 满分
     * 上涨+缩量 → 半分（可能假突破）
     * 下跌+缩量 → 正常回调（1/4分）
     */
    private int scoreVolume(List<KlineBar> bars) {
        if (bars.size() < 6) return 0;

        KlineBar latest = bars.get(bars.size() - 1);
        double avgVolume5 = bars.subList(bars.size() - 6, bars.size() - 1).stream()
                .mapToDouble(b -> b.volume).average().orElse(0);

        if (avgVolume5 <= 0) return 0;

        boolean priceUp = latest.close > latest.open;
        boolean volumeUp = latest.volume > avgVolume5 * 1.3;
        boolean volumeDown = latest.volume < avgVolume5 * 0.7;

        if (priceUp && volumeUp) return W_VOLUME;           // 放量上涨
        if (priceUp && !volumeDown) return (int) (W_VOLUME * 0.6); // 上涨+正常量
        if (priceUp && volumeDown) return (int) (W_VOLUME * 0.3);   // 缩量上涨
        if (!priceUp && volumeDown) return (int) (W_VOLUME * 0.25);  // 缩量回调（正常洗盘）

        return 0;
    }

    /**
     * 4. 回踩确认评分
     * 曾突破buyHigh后回踩至区间内但未跌破buyLow → 满分
     */
    private int scorePullback(List<KlineBar> bars, double buyLow, double buyHigh, double currentPrice) {
        // 检查最近20根K线是否曾突破过buyHigh
        boolean hadBreakout = false;
        for (int i = Math.max(0, bars.size() - 20); i < bars.size(); i++) {
            if (bars.get(i).high >= buyHigh) {
                hadBreakout = true;
                break;
            }
        }

        if (!hadBreakout) return 0;

        // 突破后当前价格在区间内且未跌破buyLow
        if (currentPrice >= buyLow && currentPrice <= buyHigh) {
            return W_PULLBACK;  // 完美回踩
        }
        // 接近区间下沿但未破
        if (currentPrice >= buyLow * 0.98 && currentPrice < buyLow) {
            return (int) (W_PULLBACK * 0.5);
        }

        return 0;
    }

    /**
     * 纯价格降级判断（K线获取失败时）
     */
    public BreakoutSignal fallbackPriceOnly(double currentPrice, IntradayMonitorService.TargetPriceInfo target, String stockCode) {
        BreakoutSignal signal = new BreakoutSignal();
        signal.setStockCode(stockCode);
        signal.setStockName(target.getStockName());
        signal.setCurrentPrice(currentPrice);
        signal.setTimestamp(java.time.LocalDateTime.now());

        double buyLow = target.getBuyPriceLow().doubleValue();
        double buyHigh = target.getBuyPriceHigh().doubleValue();

        // 价格在区间内 → 给60分（降级模式）
        if (currentPrice >= buyLow && currentPrice <= buyHigh) {
            signal.setBreakoutScore(W_BREAKOUT);
            signal.setTotalScore(60);
            signal.setSignalType("BUY_FALLBACK");
            signal.setReason("K线获取失败，降级纯价格判断：价格在买入区间内");
        } else if (currentPrice >= buyLow * 0.98) {
            signal.setTotalScore(40);
            signal.setSignalType("WATCH_FALLBACK");
            signal.setReason("K线获取失败，降级纯价格判断：价格接近买入区间下沿");
        } else {
            signal.setTotalScore(0);
            signal.setSignalType("NONE");
            signal.setReason("K线获取失败，价格不在触发区间附近");
        }

        return signal;
    }

    /**
     * 获取m5 K线数据
     * @return 最近N根m5 K线，失败返回null
     */
    private List<KlineBar> fetchM5Kline(String stockCode) {
        try {
            String tencentCode = guessTencentCode(stockCode);
            // mkline接口参数格式: code,period,,count（不需要日期范围）
            String param = String.format("%s,m5,,320", tencentCode);
            String url = String.format(KLINE_URL, param, Math.random());

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(8))
                    .GET()
                    .build();

            java.net.http.HttpResponse<String> response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) return null;

            // mkline响应以_var值开头（如"m5_today="），需要去掉前缀才是JSON
            String body = response.body();
            String jsonPrefix = "m5_today=";
            int jsonStart = body.indexOf(jsonPrefix);
            if (jsonStart >= 0) {
                body = body.substring(jsonStart + jsonPrefix.length());
            }

            return parseM5Kline(body, tencentCode);

        } catch (Exception e) {
            log.warn("[EntrySignal] 获取m5 K线失败: code={}, error={}", stockCode, e.getMessage());
            return null;
        }
    }

    /**
     * 解析m5 K线JSON
     * mkline返回格式: data.{code}.m5 = [[datetime,open,close,high,low,volume,{},turnover],...]
     */
    private List<KlineBar> parseM5Kline(String body, String tencentCode) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode data = root.path("data");
            JsonNode stockData = data.path(tencentCode);
            if (stockData.isMissingNode()) {
                // 尝试遍历
                var fields = data.fields();
                if (fields.hasNext()) stockData = fields.next().getValue();
            }

            // 找K线数组：优先找m5键，其次找qfq/day前缀键
            JsonNode klineArray = null;
            // 1) 直接找m5键（mkline接口）
            if (stockData.has("m5") && stockData.get("m5").isArray()) {
                klineArray = stockData.get("m5");
            }
            // 2) 找qfq/day前缀键（newfqkline接口兜底）
            if (klineArray == null) {
                var entryIter = stockData.fields();
                while (entryIter.hasNext()) {
                    var entry = entryIter.next();
                    if ((entry.getKey().startsWith("qfq") || entry.getKey().startsWith("day"))
                            && entry.getValue().isArray()) {
                        klineArray = entry.getValue();
                        break;
                    }
                }
            }

            if (klineArray == null || klineArray.isEmpty()) return null;

            List<KlineBar> bars = new ArrayList<>();
            for (JsonNode kline : klineArray) {
                if (!kline.isArray() || kline.size() < 6) continue;
                KlineBar bar = new KlineBar();
                bar.datetime = kline.get(0).asText();
                bar.open = kline.get(1).asDouble();
                bar.close = kline.get(2).asDouble();
                bar.high = kline.get(3).asDouble();
                bar.low = kline.get(4).asDouble();
                bar.volume = kline.get(5).asDouble();
                bars.add(bar);
            }

            // 只保留最近30根（足够计算MA30）
            if (bars.size() > 50) {
                bars = new ArrayList<>(bars.subList(bars.size() - 50, bars.size()));
            }

            return bars;

        } catch (Exception e) {
            log.warn("[EntrySignal] 解析m5 K线失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 猜测腾讯代码格式
     * 6开头 → sh, 0/3开头 → sz, 4/8开头 → sz, 北交所特殊处理
     */
    private String guessTencentCode(String stockCode) {
        if (stockCode.contains(".")) {
            String[] parts = stockCode.split("\\.");
            return parts[1].toLowerCase() + parts[0];
        }
        String pure = stockCode.replaceAll("[^0-9]", "");
        if (pure.startsWith("6") || pure.startsWith("9")) return "sh" + pure;
        if (pure.startsWith("4") || pure.startsWith("8")) return "bj" + pure;
        return "sz" + pure;
    }

    /**
     * 计算MA
     */
    private double calcMA(List<KlineBar> bars, int period) {
        if (bars.size() < period) return 0;
        double sum = 0;
        for (int i = bars.size() - period; i < bars.size(); i++) {
            sum += bars.get(i).close;
        }
        return BigDecimal.valueOf(sum / period).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private String buildReason(int breakout, int ma, int volume, int pullback) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("突破%d+均线%d+量价%d+回踩%d=合计%d", breakout, ma, volume, pullback,
                breakout + ma + volume + pullback));
        if (breakout >= W_BREAKOUT * 0.7) sb.append("；突破确认");
        if (ma >= W_MA * 0.5) sb.append("；均线多头");
        if (volume >= W_VOLUME * 0.6) sb.append("；量价配合");
        if (pullback >= W_PULLBACK * 0.5) sb.append("；回踩确认");
        return sb.toString();
    }

    /**
     * K线数据条
     */
    @Data
    public static class KlineBar {
        private String datetime;
        private double open;
        private double close;
        private double high;
        private double low;
        private double volume;
    }

    /**
     * 入场信号评分结果
     */
    @Data
    public static class BreakoutSignal {
        private String stockCode;
        private String stockName;
        private double currentPrice;
        private double triggerLow;
        private double triggerHigh;
        private java.time.LocalDateTime timestamp;

        /** 4维评分 */
        private int breakoutScore;
        private int maScore;
        private int volumeScore;
        private int pullbackScore;
        private int totalScore;

        /** 信号类型: STRONG_BUY / WATCH / BUY_FALLBACK / WATCH_FALLBACK / NONE */
        private String signalType;

        /** 评分原因说明 */
        private String reason;

        public boolean isActionable() {
            return "STRONG_BUY".equals(signalType) || "BUY_FALLBACK".equals(signalType);
        }

        public String toPushMessage() {
            return switch (signalType) {
                case "STRONG_BUY" -> String.format(
                    "强烈买入信号: %s(%s) 现价%.2f 评分%d/100\n%s\n买入区间[%.2f, %.2f] 止损%.2f",
                    stockName, stockCode, currentPrice, totalScore, reason, triggerLow, triggerHigh, 0);
                case "BUY_FALLBACK" -> String.format(
                    "买入信号(降级): %s(%s) 现价%.2f\n%s",
                    stockName, stockCode, currentPrice, reason);
                case "WATCH" -> String.format(
                    "关注信号: %s(%s) 现价%.2f 评分%d/100\n%s",
                    stockName, stockCode, currentPrice, totalScore, reason);
                default -> "";
            };
        }
    }
}
