package com.quant.platform.stock.analysis.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * 个股分析共享辅助方法（被 AnalysisService 及其各专责类共用）。
 * 从原 AnalysisService 抽出，统一以 analysisCommon.xxx 调用。
 * refactor(no-behavior-change): 方法体逐字搬运，可见性由 private 提升为 public。
 */
@Service
public class AnalysisCommonService {

    @Autowired(required = false)
    @Qualifier("clickHouseJdbcTemplate")
    private JdbcTemplate clickHouseJdbcTemplate;

    public String normalizeCodeForDailyCH(String code) {
        if (code == null) return null;
        String c = code.trim();
        if (c.contains(".")) return c.split("\\.")[0];
        return c;
    }

    public String getLatestTradeDate() {
        List<String> dates = clickHouseJdbcTemplate.query(
            "SELECT MAX(trade_date) FROM stock.stock_daily FINAL",
            (rs, rowNum) -> rs.getString(1));
        return dates.isEmpty() ? "2026-01-01" : dates.getFirst();
    }

    public double median(List<Double> values) {
        if (values == null || values.isEmpty()) return 0;
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        if (n % 2 == 1) return sorted.get(n / 2);
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    public String formatD(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toString();
    }

    public String formatMoney(double value) {
        if (Math.abs(value) >= 1_0000_0000) {
            return BigDecimal.valueOf(value / 1_0000_0000).setScale(2, RoundingMode.HALF_UP) + "亿";
        } else if (Math.abs(value) >= 10000) {
            return BigDecimal.valueOf(value / 10000).setScale(2, RoundingMode.HALF_UP) + "万";
        }
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toString();
    }
}
