package com.quant.platform.position;

import com.quant.platform.monitor.IntradayMonitorService;
import com.quant.platform.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PositionService {

    private final StockPositionMapper positionMapper;
    private final IntradayMonitorService intradayMonitorService;
    private final NotificationService notificationService;

    public StockPosition openPosition(String stockCode, String stockName,
                                       BigDecimal buyPrice, Integer quantity,
                                       BigDecimal stopLoss, BigDecimal takeProfit,
                                       String source, String notes) {
        BigDecimal cost = buyPrice.multiply(BigDecimal.valueOf(quantity));
        StockPosition pos = StockPosition.builder()
                .stockCode(stockCode).stockName(stockName)
                .buyPrice(buyPrice).buyDate(LocalDate.now()).quantity(quantity)
                .stopLossPrice(stopLoss).takeProfitPrice(takeProfit)
                .source(source).notes(notes).status("OPEN").direction("LONG")
                .costValue(cost).currentPrice(buyPrice).marketValue(cost)
                .profitLoss(BigDecimal.ZERO).profitLossPct(BigDecimal.ZERO)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();

        positionMapper.insert(pos);
        log.info("[PositionService] 建仓: code={}, price={}, qty={}", stockCode, buyPrice, quantity);
        return pos;
    }

    public StockPosition closePosition(Long positionId, BigDecimal sellPrice) {
        StockPosition pos = positionMapper.selectById(positionId);
        if (pos == null) throw new IllegalArgumentException("持仓不存在: " + positionId);
        if ("CLOSED".equals(pos.getStatus())) throw new IllegalArgumentException("持仓已平仓");

        pos.setSellPrice(sellPrice);
        pos.setSellDate(LocalDate.now());
        pos.setStatus("CLOSED");
        pos.setRealizedPl(sellPrice.multiply(BigDecimal.valueOf(pos.getQuantity())).subtract(pos.getCostValue()));
        pos.setUpdatedAt(LocalDateTime.now());

        positionMapper.updateById(pos);
        log.info("[PositionService] 平仓: code={}, P/L={}", pos.getStockCode(), pos.getRealizedPl());
        return pos;
    }

    public int updateAllPositionPrices() {
        List<StockPosition> openPositions = positionMapper.findAllOpen();
        if (openPositions.isEmpty()) return 0;

        List<String> codes = openPositions.stream().map(StockPosition::getStockCode).distinct().toList();
        Map<String, Double> prices = intradayMonitorService.fetchRealtimePrices(codes);

        int updated = 0;
        for (StockPosition pos : openPositions) {
            Double price = prices.get(pos.getStockCode());
            if (price == null) continue;

            BigDecimal currentPrice = BigDecimal.valueOf(price).setScale(3, RoundingMode.HALF_UP);
            pos.setCurrentPrice(currentPrice);
            pos.setMarketValue(currentPrice.multiply(BigDecimal.valueOf(pos.getQuantity())));
            pos.setProfitLoss(pos.getMarketValue().subtract(pos.getCostValue()));
            if (pos.getCostValue().compareTo(BigDecimal.ZERO) > 0) {
                pos.setProfitLossPct(pos.getProfitLoss().divide(pos.getCostValue(), 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)));
            }
            pos.setUpdatedAt(LocalDateTime.now());
            positionMapper.updateById(pos);
            updated++;
        }
        return updated;
    }

    @Scheduled(cron = "0 */5 9-14 * * 1-5")
    public void checkStopLossTakeProfit() {
        List<StockPosition> openPositions = positionMapper.findAllOpen();
        if (openPositions.isEmpty()) return;

        List<String> codes = openPositions.stream().map(StockPosition::getStockCode).distinct().toList();
        Map<String, Double> prices = intradayMonitorService.fetchRealtimePrices(codes);

        for (StockPosition pos : openPositions) {
            Double price = prices.get(pos.getStockCode());
            if (price == null) continue;

            if (pos.getStopLossPrice() != null && price <= pos.getStopLossPrice().doubleValue()) {
                String msg = String.format("止损提醒: %s(%s) 当前价%.2f已跌破止损价%.2f",
                        pos.getStockName(), pos.getStockCode(), price, pos.getStopLossPrice());
                log.warn("[PositionService] {}", msg);
                try { notificationService.sendAlert(msg); } catch (Exception ignored) {}
            }
            if (pos.getTakeProfitPrice() != null && price >= pos.getTakeProfitPrice().doubleValue()) {
                String msg = String.format("止盈提醒: %s(%s) 当前价%.2f已达到止盈价%.2f",
                        pos.getStockName(), pos.getStockCode(), price, pos.getTakeProfitPrice());
                log.info("[PositionService] {}", msg);
                try { notificationService.sendAlert(msg); } catch (Exception ignored) {}
            }
        }
    }

    @Scheduled(cron = "0 5 15 * * 1-5")
    public void dailyPositionUpdate() {
        log.info("[PositionService] 每日持仓更新开始");
        int updated = updateAllPositionPrices();
        log.info("[PositionService] 每日持仓更新完成: 更新{}条", updated);
    }

    public Map<String, Object> generateDailyReport() {
        List<StockPosition> openPositions = positionMapper.findAllOpen();
        List<StockPosition> closedToday = positionMapper.findAllClosed().stream()
                .filter(p -> p.getSellDate() != null && p.getSellDate().equals(LocalDate.now())).toList();

        BigDecimal totalMV = BigDecimal.ZERO, totalCost = BigDecimal.ZERO, totalPL = BigDecimal.ZERO;
        for (StockPosition p : openPositions) {
            if (p.getMarketValue() != null) totalMV = totalMV.add(p.getMarketValue());
            if (p.getCostValue() != null) totalCost = totalCost.add(p.getCostValue());
            if (p.getProfitLoss() != null) totalPL = totalPL.add(p.getProfitLoss());
        }
        BigDecimal totalPlPct = totalCost.compareTo(BigDecimal.ZERO) > 0 ?
                totalPL.divide(totalCost, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)) : BigDecimal.ZERO;

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("date", LocalDate.now().toString());
        report.put("openCount", openPositions.size());
        report.put("closedTodayCount", closedToday.size());
        report.put("totalMarketValue", totalMV);
        report.put("totalCostValue", totalCost);
        report.put("totalProfitLoss", totalPL);
        report.put("totalProfitLossPct", totalPlPct);
        report.put("positions", openPositions);
        report.put("closedToday", closedToday);
        return report;
    }

    @Scheduled(cron = "0 0 18 * * 1-5")
    public void dailyReportPush() {
        Map<String, Object> report = generateDailyReport();
        try { notificationService.sendDailyReport(report); } catch (Exception e) {
            log.warn("[PositionService] 每日报告推送失败: {}", e.getMessage());
        }
    }

    public List<StockPosition> getOpenPositions() { return positionMapper.findAllOpen(); }
    public List<StockPosition> getClosedPositions() { return positionMapper.findAllClosed(); }
    public StockPosition getPosition(Long id) { return positionMapper.selectById(id); }
}
