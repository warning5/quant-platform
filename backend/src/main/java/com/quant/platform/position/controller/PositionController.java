package com.quant.platform.position.controller;

import com.quant.platform.position.PositionService;
import com.quant.platform.position.StockPosition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/positions")
@RequiredArgsConstructor
public class PositionController {

    private final PositionService positionService;

    /** 获取持仓列表 */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(defaultValue = "OPEN") String status) {
        List<StockPosition> positions;
        if ("CLOSED".equals(status)) {
            positions = positionService.getClosedPositions();
        } else {
            positions = positionService.getOpenPositions();
        }

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", positions);
        result.put("total", positions.size());
        return ResponseEntity.ok(result);
    }

    /** 获取持仓详情 */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable Long id) {
        StockPosition pos = positionService.getPosition(id);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", pos);
        return ResponseEntity.ok(result);
    }

    /** 建仓 */
    @PostMapping
    public ResponseEntity<Map<String, Object>> open(@RequestBody Map<String, Object> body) {
        StockPosition pos = positionService.openPosition(
                (String) body.get("stockCode"),
                (String) body.get("stockName"),
                new BigDecimal(body.get("buyPrice").toString()),
                Integer.parseInt(body.get("quantity").toString()),
                body.get("stopLossPrice") != null ? new BigDecimal(body.get("stopLossPrice").toString()) : null,
                body.get("takeProfitPrice") != null ? new BigDecimal(body.get("takeProfitPrice").toString()) : null,
                (String) body.getOrDefault("source", "MANUAL"),
                (String) body.get("notes")
        );

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", pos);
        return ResponseEntity.ok(result);
    }

    /** 平仓 */
    @PostMapping("/{id}/close")
    public ResponseEntity<Map<String, Object>> close(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        StockPosition pos = positionService.closePosition(
                id, new BigDecimal(body.get("sellPrice").toString()));

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", pos);
        return ResponseEntity.ok(result);
    }

    /** 手动更新持仓价格 */
    @PostMapping("/update-prices")
    public ResponseEntity<Map<String, Object>> updatePrices() {
        int count = positionService.updateAllPositionPrices();

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", Map.of("updatedCount", count));
        return ResponseEntity.ok(result);
    }

    /** 每日持仓报告 */
    @GetMapping("/daily-report")
    public ResponseEntity<Map<String, Object>> dailyReport() {
        Map<String, Object> report = positionService.generateDailyReport();

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", report);
        return ResponseEntity.ok(result);
    }
}
