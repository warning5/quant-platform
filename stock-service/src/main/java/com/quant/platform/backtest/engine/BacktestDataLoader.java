package com.quant.platform.backtest.engine;

import com.quant.platform.backtest.domain.BacktestTask;
import com.quant.platform.factor.domain.FactorValue;
import com.quant.platform.factor.service.ClickHouseFactorValueService;
import com.quant.platform.market.domain.MarketDailyBar;
import com.quant.platform.market.service.MarketDataService;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

    @Resource
    private MarketDataService marketDataService;

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

    /**
     * 基准指数序列（收盘价映射 + 基准初始价）。
     *
     * @param closes          交易日 → 基准收盘价（LinkedHashMap，保持日期升序）
     * @param base            基准初始价：回测开始日或之后的第一个有效收盘价，兜底 1.0
     * @param firstValidClose 区间内第一个收盘价（可为 null）；仅供调用方打诊断日志，不参与计算
     * @param startDateClose  回测开始日或之后的第一个收盘价（可为 null）；同上，仅供日志
     */
    public record BenchmarkSeries(Map<LocalDate, Double> closes, double base,
                                  Double firstValidClose, Double startDateClose) {
    }

    /**
     * 加载基准指数行情并确定基准初始价。
     *
     * <p>God Class 拆分 Phase 5：自 {@code BacktestEngine#executeBacktest} 逐字迁出，
     * 含三段逻辑：区间行情加载（失败降级为空）、数据完整性校验（无数据直接抛异常终止回测，
     * 首尾缺失仅告警并由调用方前向填充）、基准初始价确定。</p>
     *
     * @throws RuntimeException 基准指数在回测区间内完全无数据
     */
    public BenchmarkSeries loadBenchmarkSeries(BacktestTask task, LocalDate startDate, LocalDate endDate,
                                               List<LocalDate> tradingDates) {
        // ── 加载基准指数行情 ──────────────────────────────────────────
        String benchmarkSymbol = task.getBenchmarkCode() != null ? task.getBenchmarkCode() : "000300.SH";
        List<MarketDailyBar> benchmarkBars = new ArrayList<>();
        try {
            benchmarkBars = marketDataService.getBarsInRange(benchmarkSymbol, startDate, endDate);
        } catch (Exception e) {
            log.warn("Failed to load benchmark bars for {}: {}", benchmarkSymbol, e.getMessage());
        }
        log.info("Loaded {} benchmark bars for {} from {} to {}", benchmarkBars.size(), benchmarkSymbol, startDate, endDate);

        // 建立日期→收盘价映射
        Map<LocalDate, Double> benchmarkClose = new LinkedHashMap<>();
        for (MarketDailyBar b : benchmarkBars) {
            benchmarkClose.put(b.getTradeDate(), b.getClose().doubleValue());
        }

        // ── 基准数据完整性检查 ─────────────────────────────────────
        if (benchmarkClose.isEmpty()) {
            throw new RuntimeException("基准指数 " + benchmarkSymbol + " 在 " + startDate + " 至 " + endDate
                    + " 期间无数据，请先在「数据更新」页面更新指数日线数据");
        }

        // 检查基准数据覆盖情况
        LocalDate firstBmDate = benchmarkClose.keySet().iterator().next();
        LocalDate lastBmDate = new ArrayList<>(benchmarkClose.keySet()).get(benchmarkClose.size() - 1);
        int missingStart = 0, missingEnd = 0;
        for (LocalDate d : tradingDates) {
            if (d.isBefore(firstBmDate)) missingStart++;
            else break;
        }
        for (int i = tradingDates.size() - 1; i >= 0; i--) {
            if (tradingDates.get(i).isAfter(lastBmDate)) missingEnd++;
            else break;
        }
        if (missingStart > 0 || missingEnd > 0) {
            log.warn("基准指数 {} 数据范围 {} ~ {}，回测区间 {} ~ {}，"
                            + "起始缺失{}个交易日、末尾缺失{}个交易日，未覆盖部分将使用前向填充",
                    benchmarkSymbol, firstBmDate, lastBmDate, startDate, endDate, missingStart, missingEnd);
        }

        // 找到第一个有效的基准价格（从回测开始日期往后找）
        Double startDateClose = null;
        Double firstValidClose = null;
        for (Map.Entry<LocalDate, Double> entry : benchmarkClose.entrySet()) {
            if (firstValidClose == null) {
                firstValidClose = entry.getValue();
            }
            if (!entry.getKey().isBefore(startDate)) {
                startDateClose = entry.getValue();
                break;
            }
        }

        // 基准初始价（回测开始日期或之后的第一个有效收盘价）
        double benchmarkBase = startDateClose != null ? startDateClose
                : (firstValidClose != null ? firstValidClose : 1.0);

        if (benchmarkBase <= 0) benchmarkBase = 1.0;
        return new BenchmarkSeries(benchmarkClose, benchmarkBase, firstValidClose, startDateClose);
    }
}
