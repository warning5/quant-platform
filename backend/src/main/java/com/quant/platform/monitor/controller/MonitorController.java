package com.quant.platform.monitor.controller;

import com.quant.platform.monitor.IntradayMonitorService;
import com.quant.platform.monitor.MinuteKlineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

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
        result.put("code", 200);
        result.put("data", Map.of(
                "monitoring", intradayMonitorService.isMonitoring(),
                "watchingCount", intradayMonitorService.getTargetPriceCache().size(),
                "targetPrices", intradayMonitorService.getTargetPriceCache()
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
}
