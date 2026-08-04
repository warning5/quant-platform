package com.quant.platform.strategy.paper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quant.platform.calendar.service.TradeCalendarService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 模拟盘行情取价服务
 * 最新价/开盘价/成交价、滑点应用、交易日判定、股票名称等只读行情查询。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaperPriceService {

    private final PaperRiskConfigMapper paperRiskConfigMapper;
    private final com.quant.platform.factor.service.FactorMetaCacheService factorMetaCache;
    private final JdbcTemplate jdbcTemplate;

    @Autowired(required = false)
    @Qualifier("clickHouseJdbcTemplate")
    private JdbcTemplate clickHouseJdbcTemplate;

    @Autowired(required = false)
    private TradeCalendarService tradeCalendarService;

    /**
     * 获取最新交易日期
     * 策略：取每个因子各自最新 calc_date 的最小值，确保所有因子在该日期都有数据
     * 回退链：逐因子MAX最小值 → 全局MAX(排除CHAN/FIN) → stock_daily MAX → 今天
     */
    public String getLatestTradeDate(Set<String> factorCodes) {
        if (clickHouseJdbcTemplate == null) return LocalDate.now().toString();

        if (factorCodes != null && !factorCodes.isEmpty()) {
            // 对每个因子取各自最新日期，返回最小值（所有因子都有的日期）
            LocalDate minDate = null;
            for (String code : factorCodes) {
                try {
                    List<String> dates = clickHouseJdbcTemplate.query(
                            "SELECT MAX(calc_date) FROM stock.factor_value FINAL WHERE factor_code = ?",
                            (rs, rowNum) -> rs.getString(1), code);
                    if (!dates.isEmpty() && dates.getFirst() != null) {
                        LocalDate d = LocalDate.parse(dates.getFirst());
                        if (minDate == null || d.isBefore(minDate)) {
                            minDate = d;
                        }
                    }
                } catch (Exception e) {
                    log.debug("查询因子 {} 最新日期失败: {}", code, e.getMessage());
                }
            }
            if (minDate != null) {
                log.info("getLatestTradeDate: 逐因子取MIN={}, factors={}", minDate, factorCodes);
                return minDate.toString();
            }
        }

        // 回退：全局 MAX 排除季频因子（DB元数据驱动）
        Set<String> quarterlyCodes = factorMetaCache.getQuarterlyCodes();
        String excludeClause = quarterlyCodes.isEmpty()
                ? ""
                : " WHERE factor_code NOT IN (" + quarterlyCodes.stream()
                .map(c -> "'" + c + "'").collect(Collectors.joining(",")) + ")";
        List<String> dates = clickHouseJdbcTemplate.query(
                "SELECT MAX(calc_date) FROM stock.factor_value FINAL" + excludeClause,
                (rs, rowNum) -> rs.getString(1));
        if (dates.isEmpty() || dates.getFirst() == null) {
            dates = clickHouseJdbcTemplate.query(
                    "SELECT MAX(calc_date) FROM stock.factor_value FINAL",
                    (rs, rowNum) -> rs.getString(1));
        }
        if (dates.isEmpty() || dates.getFirst() == null) {
            dates = clickHouseJdbcTemplate.query(
                    "SELECT MAX(trade_date) FROM stock.stock_daily FINAL",
                    (rs, rowNum) -> rs.getString(1));
        }
        return dates.isEmpty() || dates.getFirst() == null ? LocalDate.now().toString() : dates.getFirst();
    }

    /**
     * 获取单个因子的最新日期
     * 财务因子和日频因子分别更新，每个因子用自己的最新日期
     */
    public String getFactorLatestDate(String factorCode) {
        if (clickHouseJdbcTemplate == null) return LocalDate.now().toString();
        try {
            List<String> dates = clickHouseJdbcTemplate.query(
                    "SELECT MAX(calc_date) FROM stock.factor_value FINAL WHERE factor_code = ?",
                    (rs, rowNum) -> rs.getString(1), factorCode);
            return dates.isEmpty() || dates.getFirst() == null ? null : dates.getFirst();
        } catch (Exception e) {
            log.debug("查询因子 {} 最新日期失败: {}", factorCode, e.getMessage());
            return null;
        }
    }

    /**
     * 判断是否可以生成信号（允许最近3天内有交易日数据，覆盖周末/节假日补跑）
     */
    public boolean isTradingDay() {
        if (tradeCalendarService == null) {
            log.warn("isTradingDay: tradeCalendarService 为 null，拦截");
            return false;
        }
        boolean result = tradeCalendarService.isTradingDay(LocalDate.now());
        if (!result) {
            log.info("isTradingDay: 今天({})非交易日，拦截", LocalDate.now());
        }
        return result;
    }

    /**
     * 获取最新收盘价（原有逻辑，供其他场景使用）
     */
    public BigDecimal getLatestPrice(String code, String date) {
        if (clickHouseJdbcTemplate == null) return BigDecimal.ZERO;
        try {
            // date 为 null 时自动取 stock_daily 最新交易日的价格
            List<BigDecimal> prices;
            if (date == null || date.isBlank()) {
                prices = clickHouseJdbcTemplate.query(
                        "SELECT close_price FROM stock.stock_daily FINAL WHERE code = ? ORDER BY trade_date DESC LIMIT 1",
                        (rs, rowNum) -> rs.getBigDecimal("close_price"), code);
            } else {
                prices = clickHouseJdbcTemplate.query(
                        "SELECT close_price FROM stock.stock_daily FINAL WHERE code = ? AND trade_date = ?",
                        (rs, rowNum) -> rs.getBigDecimal("close_price"), code, date);
            }
            return prices.isEmpty() ? BigDecimal.ZERO : prices.getFirst();
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * 获取指定日期的开盘价（用于模拟盘成交执行）
     * date 为 null 时取最新交易日的开盘价
     */
    public BigDecimal getOpenPrice(String code, String date) {
        if (clickHouseJdbcTemplate == null) return BigDecimal.ZERO;
        try {
            List<BigDecimal> prices;
            if (date == null || date.isBlank()) {
                prices = clickHouseJdbcTemplate.query(
                        "SELECT open_price FROM stock.stock_daily FINAL WHERE code = ? ORDER BY trade_date DESC LIMIT 1",
                        (rs, rowNum) -> rs.getBigDecimal("open_price"), code);
            } else {
                prices = clickHouseJdbcTemplate.query(
                        "SELECT open_price FROM stock.stock_daily FINAL WHERE code = ? AND trade_date = ?",
                        (rs, rowNum) -> rs.getBigDecimal("open_price"), code, date);
            }
            if (prices.isEmpty() || prices.getFirst() == null || prices.getFirst().compareTo(BigDecimal.ZERO) <= 0) {
                // 开盘价为空时降级为收盘价
                log.warn("getOpenPrice: {} {} 开盘价为空，降级为收盘价", code, date);
                return getLatestPrice(code, date);
            }
            return prices.getFirst();
        } catch (Exception e) {
            log.warn("getOpenPrice 查询失败 code={}, date={}: {}", code, date, e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    /**
     * 手动执行信号时的成交价（调用前需先通过 isTradingDay() 拦截非交易日）
     * - 今天是交易日 → 今天收盘价
     */
    public BigDecimal getExecutionPrice(String code) {
        return getExecutionPrice(code, null);
    }

    /**
     * 获取成交价（含滑点调整）
     *
     * @param paperId 模拟盘ID（用于读取滑点配置），null时不加滑点
     */
    public BigDecimal getExecutionPrice(String code, Long paperId) {
        if (clickHouseJdbcTemplate == null) return BigDecimal.ZERO;
        try {
            LocalDate today = LocalDate.now();
            BigDecimal closePrice = getLatestPrice(code, today.toString());
            if (closePrice == null || closePrice.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("getExecutionPrice: {} {} 收盘价无效，降级为开盘价", code, today);
                closePrice = getOpenPrice(code, today.toString());
            }
            return closePrice;
        } catch (Exception e) {
            log.warn("getExecutionPrice 查询失败 code={}: {}", code, e.getMessage());
            return getOpenPrice(code, null);
        }
    }

    /**
     * 滑点调整：买入加滑点，卖出减滑点
     */
    public BigDecimal applySlippage(BigDecimal price, boolean isBuy, Long paperId) {
        if (paperId == null) return price;
        PaperRiskConfig cfg = paperRiskConfigMapper.selectOne(
                new LambdaQueryWrapper<PaperRiskConfig>().eq(PaperRiskConfig::getPaperId, paperId));
        if (cfg == null) cfg = PaperRiskConfig.defaults(paperId);

        String model = cfg.getSlippageModel();
        BigDecimal slipPct = cfg.getSlippagePct();
        if (model == null || "NONE".equals(model) || slipPct == null || slipPct.compareTo(BigDecimal.ZERO) <= 0) {
            return price;
        }

        // FIXED模型：固定滑点比例
        BigDecimal factor = isBuy
                ? BigDecimal.ONE.add(slipPct)     // 买入加滑点
                : BigDecimal.ONE.subtract(slipPct); // 卖出减滑点
        BigDecimal adjusted = price.multiply(factor).setScale(2, RoundingMode.HALF_UP);
        log.debug("applySlippage: isBuy={} raw={} slip={} adjusted={}",
                isBuy, price, slipPct, adjusted);
        return adjusted;
    }

    public String getStockName(String code) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.query(
                    "SELECT name FROM stock_info WHERE code = ? LIMIT 1",
                    (rs, rowNum) -> Map.of("name", rs.getString("name")), code);
            return rows.isEmpty() ? code : (String) rows.getFirst().get("name");
        } catch (Exception e) {
            return code;
        }
    }

    /**
     * 从ClickHouse拉取K线数据用于卖点检测
     *
     * @return [open[], high[], low[], close[], volume[]] 或 null
     */
    public double[][] fetchKlineForSellCheck(String code) {
        if (clickHouseJdbcTemplate == null) return null;
        try {
            String pureCode = code.split("\\.")[0];
            List<Map<String, Object>> rows = clickHouseJdbcTemplate.queryForList(
                    "SELECT open_price, high_price, low_price, close_price, volume FROM stock.stock_daily FINAL " +
                            "WHERE code = ? ORDER BY trade_date DESC LIMIT 120",
                    pureCode);
            if (rows.isEmpty()) return null;
            int n = rows.size();
            double[] open = new double[n], high = new double[n], low = new double[n], close = new double[n], volume = new double[n];
            for (int i = 0; i < n; i++) {
                Map<String, Object> row = rows.get(n - 1 - i);
                open[i] = ((Number) row.get("open_price")).doubleValue();
                high[i] = ((Number) row.get("high_price")).doubleValue();
                low[i] = ((Number) row.get("low_price")).doubleValue();
                close[i] = ((Number) row.get("close_price")).doubleValue();
                volume[i] = ((Number) row.get("volume")).doubleValue();
            }
            return new double[][]{open, high, low, close, volume};
        } catch (Exception e) {
            log.warn("[PaperTrading] 拉取K线失败: {} - {}", code, e.getMessage());
            return null;
        }
    }

    /**
     * 查某只股票在指定卖出日期之前最近一次BUY信号的成交价
     */
    public BigDecimal getBuyPriceForCode(Long paperId, String code, String beforeSellDate) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.query(
                    "SELECT executed_price FROM paper_signal WHERE paper_id = ? AND code = ? AND direction = 'BUY' AND status = 'EXECUTED' AND signal_date <= ? ORDER BY signal_date DESC LIMIT 1",
                    (rs, rowNum) -> Map.of("price", rs.getBigDecimal("executed_price")), paperId, code, beforeSellDate);
            return rows.isEmpty() ? null : (BigDecimal) rows.getFirst().get("price");
        } catch (Exception e) {
            return null;
        }
    }

}
