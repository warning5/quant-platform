package com.quant.platform.calendar.controller;

import com.quant.platform.calendar.domain.TradeCalendar;
import com.quant.platform.calendar.service.TradeCalendarService;
import com.quant.platform.common.dto.ApiResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 交易日历 Controller
 */
@RestController
@RequestMapping("/calendar")
public class TradeCalendarController {
    
    @Autowired
    private TradeCalendarService tradeCalendarService;
    
    /**
     * 获取指定日期范围内的所有交易日
     * GET /api/calendar/trading-dates?startDate=2026-07-01&endDate=2026-07-14
     */
    @GetMapping("/trading-dates")
    public ApiResponse<Map<String, Object>> getTradingDatesBetween(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        Map<String, Object> result = Map.of(
            "dates", tradeCalendarService.getTradingDaysBetween(startDate, endDate)
        );
        return ApiResponse.success(result);
    }

    /**
     * 获取最近 N 个交易日
     * GET /api/calendar/last-trading-dates?count=7
     */
    @GetMapping("/last-trading-dates")
    public ApiResponse<Map<String, Object>> getLastTradingDates(@RequestParam(defaultValue = "7") int count) {
        Map<String, Object> result = Map.of(
            "dates", tradeCalendarService.getLastTradingDays(count)
        );
        return ApiResponse.success(result);
    }

    /**
     * 判断某天是否为交易日
     * GET /api/calendar/is-trading?date=2026-06-19
     */
    @GetMapping("/is-trading")
    public ApiResponse<Map<String, Object>> isTradingDay(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        Map<String, Object> result = Map.of(
            "date", date,
            "isTrading", tradeCalendarService.isTradingDay(date)
        );
        return ApiResponse.success(result);
    }
    
    /**
     * 获取最近的交易日
     * GET /api/calendar/latest-trading-day?date=2026-06-19
     */
    @GetMapping("/latest-trading-day")
    public ApiResponse<Map<String, Object>> getLatestTradingDay(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        Map<String, Object> result = Map.of(
            "date", tradeCalendarService.getLatestTradingDay(date)
        );
        return ApiResponse.success(result);
    }
    
    /**
     * 查询某天的日历记录
     * GET /api/calendar/date?date=2026-06-19
     */
    @GetMapping("/date")
    public ApiResponse<TradeCalendar> getByDate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.success(tradeCalendarService.getByDate(date));
    }
    
    /**
     * 查询某年的所有记录
     * GET /api/calendar/year?year=2026
     */
    @GetMapping("/year")
    public ApiResponse<List<TradeCalendar>> getByYear(@RequestParam int year) {
        return ApiResponse.success(tradeCalendarService.getByYear(year));
    }
    
    /**
     * 手动标记某天
     * POST /api/calendar/mark
     * Body: {"date":"2026-06-19","isTrading":false,"reason":"临时标记"}
     */
    @PostMapping("/mark")
    public ApiResponse<Void> markDay(@RequestBody Map<String, Object> params) {
        String dateStr = (String) params.get("date");
        Boolean isTrading = (Boolean) params.get("isTrading");
        String reason = (String) params.get("reason");
        
        LocalDate date = LocalDate.parse(dateStr);
        tradeCalendarService.markDay(date, isTrading, reason);
        
        return ApiResponse.success(null);
    }
}
