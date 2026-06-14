package com.quant.platform.monitor.controller;

import com.quant.platform.monitor.IntradayMonitorService;
import com.quant.platform.monitor.MinuteKlineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDate;
import java.util.*;

/**
 * 监控相关API
 */
@Slf4j
@RestController
@RequestMapping("/monitor")
@RequiredArgsConstructor
public class MonitorController {

    private final IntradayMonitorService intradayMonitorService;
    private final MinuteKlineService minuteKlineService;
    private final JdbcTemplate jdbcTemplate;

    /** 获取盘中监控状态 */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> targetPrices = new ArrayList<>();
        intradayMonitorService.getTargetPriceCache().forEach((k, v) -> {
            Map<String, Object> m = new HashMap<>();
            m.put("stockCode", v.getStockCode());
            m.put("stockName", v.getStockName());
            m.put("buyPriceLow", v.getBuyPriceLow());
            m.put("buyPriceHigh", v.getBuyPriceHigh());
            m.put("stopLoss", v.getStopLoss());
            m.put("targetPrice", v.getTargetPrice());
            m.put("source", v.getSource());
            targetPrices.add(m);
        });
        result.put("code", 200);
        result.put("data", Map.of(
                "monitoring", intradayMonitorService.isMonitoring(),
                "watchingCount", intradayMonitorService.getTargetPriceCache().size(),
                "dataDate", intradayMonitorService.getDataDate().toString(),
                "targetPrices", targetPrices
        ));
        return ResponseEntity.ok(result);
    }

    /** 手动触发分钟K线采集 */
    @PostMapping("/fetch-kline")
    public ResponseEntity<Map<String, Object>> fetchKline(
            @RequestParam String stockCode,
            @RequestParam(defaultValue = "m30") String period,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {

        LocalDate start = startDate != null ? LocalDate.parse(startDate) : LocalDate.now().minusDays(30);
        LocalDate end = endDate != null ? LocalDate.parse(endDate) : LocalDate.now();

        int count = minuteKlineService.fetchMinuteKline(stockCode, period, start, end);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", Map.of(
                "stockCode", stockCode,
                "period", period,
                "count", count,
                "dateRange", start + " ~ " + end
        ));
        return ResponseEntity.ok(result);
    }

    /** SSE实时信号推送 */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return intradayMonitorService.createSseEmitter();
    }

    /** 手动刷新监控目标价 */
    @PostMapping("/refresh-targets")
    public ResponseEntity<Map<String, Object>> refreshTargets() {
        intradayMonitorService.loadTargetPrices();

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", Map.of(
                "watchingCount", intradayMonitorService.getTargetPriceCache().size()
        ));
        return ResponseEntity.ok(result);
    }

    /** 获取实时价格快照 */
    @GetMapping("/realtime")
    public ResponseEntity<Map<String, Object>> getRealtimePrices(
            @RequestParam String stockCodes) {
        String[] codes = stockCodes.split(",");
        Map<String, Double> prices = intradayMonitorService.fetchRealtimePrices(Arrays.asList(codes));

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", prices);
        return ResponseEntity.ok(result);
    }

    /**
     * 手动触发一次监控扫描
     * POST /api/monitor/trigger-scan
     * POST /api/monitor/trigger-scan?stockCodes=sh600519,sz000858
     */
    @PostMapping("/trigger-scan")
    public ResponseEntity<Map<String, Object>> triggerScan(
            @RequestParam(required = false) String stockCodes) {

        List<String> codes = null;
        if (stockCodes != null && !stockCodes.trim().isEmpty()) {
            codes = Arrays.asList(stockCodes.split(","));
        }

        IntradayMonitorService.ScanResult scanResult = intradayMonitorService.triggerManualScan(codes);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", scanResult);
        return ResponseEntity.ok(result);
    }
}
