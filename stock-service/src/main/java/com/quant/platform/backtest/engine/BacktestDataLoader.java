package com.quant.platform.backtest.engine;

import com.quant.platform.factor.domain.FactorValue;
import com.quant.platform.factor.service.ClickHouseFactorValueService;
import com.quant.platform.market.domain.MarketDailyBar;
import com.quant.platform.stock.entity.StockInfo;
import com.quant.platform.stock.mapper.StockInfoMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 回测辅助数据加载器。
 *
 * <p>从 {@code BacktestEngine} 中抽取的「外部数据读取」职责（God Class 拆分 Phase 2）。
 * 方法体逐字迁移，行为与原实现完全一致，仅变更归属类。</p>
 */
@Slf4j
@Component
public class BacktestDataLoader {

    @Autowired(required = false)
    private ClickHouseFactorValueService clickHouseFactorValueService;

    @Autowired(required = false)
    private StockInfoMapper stockInfoMapper;

    @Resource
    private DataSource dataSource;

    /**
     * 加载候选股票的行业映射（code → industry）
     * 从 stock_info 表批量查询
     */
    public Map<String, String> loadIndustryMap(List<MarketDailyBar> bars) {
        List<String> codes = bars.stream().map(b -> {
            String sym = b.getSymbol();
            int dot = sym.lastIndexOf('.');
            return dot > 0 ? sym.substring(0, dot) : sym;
        }).distinct().toList();

        if (codes.isEmpty()) return Map.of();

        Map<String, String> result = new HashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT code, industry FROM stock_info WHERE code IN (" +
                             codes.stream().map(c -> "?").collect(Collectors.joining(",")) + ")")) {
            for (int i = 0; i < codes.size(); i++) {
                ps.setString(i + 1, codes.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString("code"), rs.getString("industry"));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load industry map: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 加载候选股票的基本信息映射（code → {listDate, totalShare, name}）
     */
    public Map<String, Map<String, Object>> loadStockInfoMap(List<MarketDailyBar> bars) {
        List<String> codes = bars.stream().map(b -> {
            String sym = b.getSymbol();
            int dot = sym.lastIndexOf('.');
            return dot > 0 ? sym.substring(0, dot) : sym;
        }).distinct().toList();

        if (codes.isEmpty()) return Map.of();

        Map<String, Map<String, Object>> result = new HashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT code, name, list_date, total_share, total_market_cap FROM stock_info WHERE code IN (" +
                             codes.stream().map(c -> "?").collect(Collectors.joining(",")) + ")")) {
            for (int i = 0; i < codes.size(); i++) {
                ps.setString(i + 1, codes.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> info = new HashMap<>();
                    info.put("name", rs.getString("name"));
                    info.put("listDate", rs.getDate("list_date") != null ? rs.getDate("list_date").toLocalDate() : null);
                    info.put("totalShare", rs.getBigDecimal("total_share"));
                    info.put("totalMarketCap", rs.getBigDecimal("total_market_cap"));
                    result.put(rs.getString("code"), info);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load stock info map: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 加载指定因子在最近 N 天内的历史值
     * 格式: { factorCode -> { symbol -> [FactorValue...] } }
     */
    public Map<String, Map<String, List<FactorValue>>> loadHistoricalFactors(
            Set<String> factorCodes, LocalDate endDate, int lookbackDays) {
        Map<String, Map<String, List<FactorValue>>> result = new HashMap<>();
        LocalDate startDate = endDate.minusDays(lookbackDays);

        for (String factorCode : factorCodes) {
            try {
                List<FactorValue> fvs = clickHouseFactorValueService.findByFactorCodeAndDateRange(
                        factorCode, startDate, endDate);
                if (fvs == null || fvs.isEmpty()) continue;

                Map<String, List<FactorValue>> symbolMap = fvs.stream()
                        .collect(Collectors.groupingBy(FactorValue::getSymbol));
                result.put(factorCode, symbolMap);
            } catch (Exception e) {
                log.debug("Failed to load historical factors for {}: {}", factorCode, e.getMessage());
            }
        }
        return result;
    }

    /**
     * 加载所有股票的退市日期映射（幸存者偏差修复）。
     * 从 stock_info 表查询 delist_date 字段，构建 symbol -> delistDate 的映射。
     * 如果 stock_info 中无退市日期数据，则返回空 map（不影响现有逻辑）。
     */
    public Map<String, LocalDate> loadDelistDateMap() {
        if (stockInfoMapper == null) {
            return Map.of();
        }
        try {
            List<StockInfo> list = stockInfoMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<StockInfo>()
                            .isNotNull("delist_date")
                            .gt("delist_date", "1900-01-01")
            );
            Map<String, LocalDate> map = new HashMap<>();
            for (StockInfo info : list) {
                if (info.getCode() != null && info.getDelistDate() != null) {
                    map.put(info.getCode(), info.getDelistDate());
                }
            }
            return map;
        } catch (Exception e) {
            log.warn("加载退市日期映射失败: {}", e.getMessage());
            return Map.of();
        }
    }
}
