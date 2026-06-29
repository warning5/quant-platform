package com.quant.platform.calendar.service;

import com.quant.platform.calendar.domain.TradeCalendar;
import com.quant.platform.calendar.mapper.TradeCalendarMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

/**
 * 交易日历服务
 * 设计原则：trade_calendar 表只存"例外日"（非交易日）
 * - 周末 → 代码自动判断为非交易日，不入库
 * - 工作日节假日 → 入库 is_trading=0 + reason
 * - 普通工作日 → 不入库，默认为交易日
 * - 手动覆盖 → 入库标记 source=MANUAL
 * - 调休/补班 → 不入库，周末统一按非交易日处理
 */
@Service
public class TradeCalendarService {

    @Autowired
    private TradeCalendarMapper tradeCalendarMapper;

    /**
     * 判断指定日期是否为交易日
     * 原则：trade_calendar 只存非交易日例外日，有记录按记录；无记录按周末/工作日默认规则
     */
    public boolean isTradingDay(LocalDate date) {
        // 先查例外日记录（节假日/手动标记）
        TradeCalendar cal = tradeCalendarMapper.selectByDate(date);
        if (cal != null) {
            return Boolean.TRUE.equals(cal.getIsTrading());
        }
        // 无记录：周末为非交易，工作日默认为交易
        DayOfWeek dow = date.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
    }

    /**
     * 获取最近的交易日（从指定日期往前找）
     */
    public LocalDate getLatestTradingDay(LocalDate date) {
        if (date == null) {
            date = LocalDate.now();
        }
        for (int i = 0; i < 30; i++) {
            LocalDate d = date.minusDays(i);
            if (isTradingDay(d)) {
                return d;
            }
        }
        return date; // fallback
    }

    /**
     * 获取最近的交易日（重载，默认用今天）
     */
    public LocalDate getLatestTradingDay() {
        return getLatestTradingDay(LocalDate.now());
    }

    /**
     * 查询指定日期的日历记录（可能返回null）
     */
    public TradeCalendar getByDate(LocalDate date) {
        return tradeCalendarMapper.selectByDate(date);
    }

    /**
     * 查询某年所有例外日记录
     */
    public List<TradeCalendar> getByYear(int year) {
        return tradeCalendarMapper.selectByYear(year);
    }

    /**
     * 计算两个日期之间（不含起始日，含结束日）的交易日个数
     * 例如：from=周五, to=周一 → 周末2天非交易，周一1天交易 → 返回1
     * 算法：总日历日 - 周末 - 节假日(从 trade_calendar 批量查询)
     */
    public long countTradingDays(LocalDate from, LocalDate to) {
        if (from == null || to == null || !to.isAfter(from)) return 0;

        long totalDays = to.toEpochDay() - from.toEpochDay();

        // 统计周末天数（数学计算，无 DB 查询）
        long weekendDays = 0;
        LocalDate d = from.plusDays(1);
        while (!d.isAfter(to)) {
            DayOfWeek dow = d.getDayOfWeek();
            if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
                weekendDays++;
            }
            d = d.plusDays(1);
        }

        // 批量查询区间内节假日（1次 SQL）
        long holidays = tradeCalendarMapper.countHolidaysBetween(from, to);

        return totalDays - weekendDays - holidays;
    }

    /**
     * 手动标记某天
     * 规则：标记为交易日时删除该日记录（恢复默认）；标记为非交易日时插入/更新记录
     */
    public void markDay(LocalDate date, boolean isTrading, String reason) {
        if (isTrading) {
            // 恢复为默认交易日：删除记录
            tradeCalendarMapper.deleteByDate(date);
        } else {
            TradeCalendar cal = new TradeCalendar(date, false, reason, "MANUAL");
            tradeCalendarMapper.upsertByDate(cal);
        }
    }
}
