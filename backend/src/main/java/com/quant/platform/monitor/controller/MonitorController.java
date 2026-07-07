package com.quant.platform.monitor.controller;

import com.quant.platform.monitor.IntradayMonitorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
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
    private final JdbcTemplate jdbcTemplate;

    /**
     * 安全地将Object转为Double，兼容String/Number/null
     */
    private Double toDouble(Object val) {
        switch (val) {
            case null -> {
                return null;
            }
            case Number number -> {
                return number.doubleValue();
            }
            case String string -> {
                String s = string.trim();
                if (s.isEmpty()) return null;
                return Double.parseDouble(s);
            }
            default -> {
            }
        }
        return null;
    }

    /** 获取盘中监控状态（含实时价格和快速信号状态） */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> targetPrices = new ArrayList<>();

        // 获取缓存的实时价格和涨跌幅
        Map<String, Double> latestPrices = intradayMonitorService.getLatestPrices();
        Map<String, Double> latestChangePct = intradayMonitorService.getLatestChangePct();

        intradayMonitorService.getTargetPriceCache().forEach((k, v) -> {
            Map<String, Object> m = new HashMap<>();
            m.put("stockCode", v.getStockCode());
            m.put("stockName", v.getStockName());
            m.put("buyPriceLow", v.getBuyPriceLow());
            m.put("buyPriceHigh", v.getBuyPriceHigh());
            m.put("stopLoss", v.getStopLoss());
            m.put("targetPrice", v.getTargetPrice());
            m.put("source", v.getSource());

            // 附加实时价格
            Double currentPrice = latestPrices.get(k);
            Double changePct = latestChangePct.get(k);
            if (currentPrice != null) {
                m.put("currentPrice", currentPrice);
                if (changePct != null) {
                    m.put("changePct", changePct);
                }
                // 快速信号状态判断（纯价格，无需K线）
                String signalStatus;
                String signalMessage;
                double buyLow = v.getBuyPriceLow() != null ? v.getBuyPriceLow().doubleValue() : 0;
                double buyHigh = v.getBuyPriceHigh() != null ? v.getBuyPriceHigh().doubleValue() : 0;
                double stopLoss = v.getStopLoss() != null ? v.getStopLoss().doubleValue() : 0;

                if (stopLoss > 0 && currentPrice <= stopLoss) {
                    signalStatus = "STOP";
                    signalMessage = String.format("跌破止损价 %.2f", stopLoss);
                } else if (buyLow > 0 && buyHigh > 0) {
                    double proximityPct = 0.02;
                    double triggerLow = buyLow * (1 - proximityPct);
                    double triggerHigh = buyHigh * (1 + proximityPct);
                    // 先精确匹配区间内，再判断±2%接近区间（顺序很重要！）
                    if (currentPrice >= buyLow && currentPrice <= buyHigh) {
                        signalStatus = "IN_RANGE";
                        signalMessage = String.format("在买入区间内 [%.2f~%.2f]", buyLow, buyHigh);
                    } else if (currentPrice >= triggerLow && currentPrice <= triggerHigh) {
                        signalStatus = "WATCH";
                        signalMessage = String.format("接近买入区间 [%.2f~%.2f]", buyLow, buyHigh);
                    } else if (currentPrice < buyLow) {
                        double pct = (buyLow - currentPrice) / buyLow * 100;
                        signalStatus = "BELOW";
                        signalMessage = String.format("低于买入区间下沿 %.1f%%", pct);
                    } else {
                        double pct = (currentPrice - buyHigh) / buyHigh * 100;
                        signalStatus = "ABOVE";
                        signalMessage = String.format("高于买入区间上沿 %.1f%%", pct);
                    }
                } else {
                    signalStatus = "NO_RANGE";
                    signalMessage = "未设置买入区间";
                }
                m.put("signalStatus", signalStatus);
                m.put("signalMessage", signalMessage);
            } else {
                m.put("signalStatus", "NO_PRICE");
                m.put("signalMessage", "暂无实时价格");
            }

            targetPrices.add(m);
        });
        result.put("code", 200);
        result.put("data", Map.of(
                "monitoring", intradayMonitorService.isMonitoring(),
                "initialized", !intradayMonitorService.getTargetPriceCache().isEmpty(),
                "watchingCount", intradayMonitorService.getTargetPriceCache().size(),
                "dataDate", intradayMonitorService.getDataDate().toString(),
                "targetPrices", targetPrices,
                "signalHistory", intradayMonitorService.getSignalHistory()
        ));
        return ResponseEntity.ok(result);
    }

    /** SSE实时信号推送 */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return intradayMonitorService.createSseEmitter();
    }

    /** 手动启动/恢复盘中监控 */
    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> startMonitoring() {
        intradayMonitorService.startMonitoring();
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", Map.of(
                "monitoring", intradayMonitorService.isMonitoring(),
                "watchingCount", intradayMonitorService.getTargetPriceCache().size()
        ));
        result.put("message", "监控已启动");
        return ResponseEntity.ok(result);
    }

    /** 手动停止盘中监控 */
    @PostMapping("/stop")
    public ResponseEntity<Map<String, Object>> stopMonitoring() {
        intradayMonitorService.stopMonitoring();
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", Map.of("monitoring", false));
        result.put("message", "监控已停止");
        return ResponseEntity.ok(result);
    }

    /** 清除信号历史 */
    @PostMapping("/clear-signals")
    public ResponseEntity<Map<String, Object>> clearSignals() {
        intradayMonitorService.clearSignalHistory();
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "信号历史已清除");
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

    /** 获取实时价格快照（优先用缓存，无缓存则主动拉取） */
    @GetMapping("/realtime")
    public ResponseEntity<Map<String, Object>> getRealtimePrices(
            @RequestParam String stockCodes) {
        String[] codes = stockCodes.split(",");
        
        // 优先使用高频轮询缓存的最新价格
        Map<String, Double> cachedPrices = intradayMonitorService.getLatestPrices();
        Map<String, Double> prices = new LinkedHashMap<>();
        boolean allCached = true;
        
        for (String code : codes) {
            Double price = cachedPrices.get(code);
            if (price != null) {
                prices.put(code, price);
            } else {
                allCached = false;
            }
        }
        
        // 缓存不完整时主动拉取
        if (!allCached) {
            Map<String, Double> freshPrices = intradayMonitorService.fetchRealtimePrices(Arrays.asList(codes));
            prices.putAll(freshPrices);
        }

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

    /**
     * 模拟执行一个完整盘中周期（非交易日/非交易时段测试推送用）
     * POST /api/monitor/simulate-cycle
     * POST /api/monitor/simulate-cycle?force=true
     */
    @PostMapping("/simulate-cycle")
    public ResponseEntity<Map<String, Object>> simulateCycle(
            @RequestParam(defaultValue = "false") boolean force) {

        intradayMonitorService.simulateOneCycle(force);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", Map.of("simulated", true, "force", force));
        result.put("message", "模拟盘中周期已执行，SSE信号已推送");
        return ResponseEntity.ok(result);
    }

    /**
     * 添加自定义监控股票
     * POST /api/monitor/add-custom-stock
     */
    @PostMapping("/add-custom-stock")
    public ResponseEntity<Map<String, Object>> addCustomStock(@RequestBody Map<String, Object> body) {
        String stockCode = (String) body.get("stockCode");
        String stockName = (String) body.get("stockName");
        Double buyPriceLow = toDouble(body.get("buyPriceLow"));
        Double buyPriceHigh = toDouble(body.get("buyPriceHigh"));
        Double stopLoss = toDouble(body.get("stopLoss"));
        Double targetPrice = toDouble(body.get("targetPrice"));

        if (stockCode == null || stockCode.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "股票代码不能为空"));
        }
        if (buyPriceLow == null || buyPriceHigh == null) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "买入区间不能为空"));
        }

        IntradayMonitorService.TargetPriceInfo info = new IntradayMonitorService.TargetPriceInfo();
        info.setStockCode(stockCode.trim());
        info.setStockName(stockName != null ? stockName.trim() : stockCode.trim());
        info.setBuyPriceLow(BigDecimal.valueOf(buyPriceLow));
        info.setBuyPriceHigh(BigDecimal.valueOf(buyPriceHigh));
        info.setStopLoss(stopLoss != null ? BigDecimal.valueOf(stopLoss) : null);
        info.setTargetPrice(targetPrice != null ? BigDecimal.valueOf(targetPrice) : null);

        intradayMonitorService.addCustomStock(info);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", Map.of(
                "stockCode", stockCode,
                "stockName", info.getStockName(),
                "source", "客户定义",
                "watchingCount", intradayMonitorService.getTargetPriceCache().size()
        ));
        return ResponseEntity.ok(result);
    }

    /**
     * 根据股票代码查询名称（用于自动补全）
     * GET /api/monitor/stock-name?code=600519
     */
    @GetMapping("/stock-name")
    public ResponseEntity<Map<String, Object>> getStockName(@RequestParam String code) {
        String pureCode = code.trim().replaceAll("(?i)^(sh|sz|bj)", "");
        Map<String, Object> result = new HashMap<>();
        try {
            String sql = "SELECT name, market FROM stock_info WHERE code = ? LIMIT 1";
            Map<String, Object> row = jdbcTemplate.queryForMap(sql, pureCode);
            result.put("code", 200);
            result.put("data", Map.of(
                    "stockCode", pureCode,
                    "stockName", row.get("name"),
                    "market", row.get("market") != null ? row.get("market") : ""
            ));
        } catch (Exception e) {
            // stock_info查不到，返回空名称让用户手动填写
            result.put("code", 200);
            result.put("data", Map.of(
                    "stockCode", pureCode,
                    "stockName", "",
                    "market", ""
            ));
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 获取大盘指数实时行情（上证、深证、创业板、科创50、上证50、沪深300、中证500、中证1000、北证50）
     * 后端每10秒自动刷新缓存，前端调用直接返回
     */
    @GetMapping("/indices")
    public ResponseEntity<Map<String, Object>> getIndexQuotes() {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", intradayMonitorService.getIndexQuotes());
        return ResponseEntity.ok(result);
    }

    /**
     * 移除自定义监控股票
     * DELETE /api/monitor/custom-stock?stockCode=xxx
     */
    @DeleteMapping("/custom-stock")
    public ResponseEntity<Map<String, Object>> removeCustomStock(@RequestParam String stockCode) {
        boolean removed = intradayMonitorService.removeCustomStock(stockCode.trim());

        Map<String, Object> result = new HashMap<>();
        if (removed) {
            result.put("code", 200);
            result.put("data", Map.of("removed", true, "stockCode", stockCode));
        } else {
            result.put("code", 404);
            result.put("message", "未找到该自定义股票: " + stockCode);
        }
        return ResponseEntity.ok(result);
    }
}
