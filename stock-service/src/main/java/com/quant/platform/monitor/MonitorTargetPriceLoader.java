package com.quant.platform.monitor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.quant.platform.calendar.service.TradeCalendarService;
import com.quant.platform.monitor.IntradayMonitorService.TargetPriceInfo;
import lombok.Getter;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MonitorTargetPriceLoader —— 由 IntradayMonitorService 零行为变化拆分而来。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonitorTargetPriceLoader {

    private final JdbcTemplate jdbcTemplate;
    private final TradeCalendarService tradeCalendarService;
    private final MonitorQuoteClient quoteClient;

    /** 候选股目标价缓存: stockCode -> TargetPriceInfo */
    private final Map<String, TargetPriceInfo> targetPriceCache = new ConcurrentHashMap<>();

    /** 用户自定义股票: stockCode -> TargetPriceInfo（刷新目标价时不被覆盖） */
    private final Map<String, TargetPriceInfo> customStocks = new ConcurrentHashMap<>();

    @Getter
    private volatile LocalDate dataDate;

    /**
     * 获取监控面板显示的数据日期
     * 优先用 stock_recommendation.recommend_date（一定是交易日，含义明确）
     *  fallback 用 llm_analysis.analysis_date（可能需要调整到交易日）
     */
    public LocalDate getLatestDataDate() {
        // 优先：推荐日期（一定是交易日，无需调整）
        try {
            LocalDate recDate = jdbcTemplate.queryForObject(
                "SELECT MAX(recommend_date) FROM stock_recommendation WHERE recommend_date <= CURDATE()",
                LocalDate.class);
            if (recDate != null) {
                this.dataDate = recDate;
                return recDate;
            }
        } catch (Exception e) {
            log.warn("[IntradayMonitor] 查询stock_recommendation最新日期失败: {}", e.getMessage());
        }

        // fallback: LLM分析日期（可能是节假日，需调整到前一交易日）
        try {
            LocalDate llmDate = jdbcTemplate.queryForObject(
                "SELECT MAX(analysis_date) FROM llm_analysis WHERE analysis_date <= CURDATE()",
                LocalDate.class);
            if (llmDate != null) {
                while (isNonTradingDay(llmDate)) {
                    llmDate = llmDate.minusDays(1);
                }
                this.dataDate = llmDate;
                return llmDate;
            }
        } catch (Exception e) {
            log.warn("[IntradayMonitor] 查询llm_analysis最新日期失败: {}", e.getMessage());
        }

        // 都没有：昨天（调整到交易日）
        LocalDate fallback = LocalDate.now().minusDays(1);
        while (isNonTradingDay(fallback)) {
            fallback = fallback.minusDays(1);
        }
        this.dataDate = fallback;
        return fallback;
    }

    // ── 目标价加载 ──

    public void loadTargetPrices() {
        targetPriceCache.clear();

        // 各数据源取各自最新日期
        LocalDate llmDate = null;
        LocalDate recDate = null;
        try {
            llmDate = jdbcTemplate.queryForObject(
                "SELECT MAX(analysis_date) FROM llm_analysis WHERE analysis_date <= CURDATE()", LocalDate.class);
        } catch (Exception e) {
            log.warn("[IntradayMonitor] 查询llm_analysis最新日期失败: {}", e.getMessage());
        }
        try {
            recDate = jdbcTemplate.queryForObject(
                "SELECT MAX(recommend_date) FROM stock_recommendation WHERE recommend_date <= CURDATE()", LocalDate.class);
        } catch (Exception e) {
            log.warn("[IntradayMonitor] 查询stock_recommendation最新日期失败: {}", e.getMessage());
        }

        // 监控面板显示的数据日期 = 推荐日期（一定是交易日，有最直接的意义）
        // llm_analysis.analysis_date 可以是任意一天（含节假日），不适合作为显示日期
        if (recDate != null) {
            this.dataDate = recDate;
        } else if (llmDate != null) {
            // llm_analysis日期可能是节假日，调整到最近交易日
            while (isNonTradingDay(llmDate)) {
                llmDate = llmDate.minusDays(1);
            }
            this.dataDate = llmDate;
        } else {
            this.dataDate = tradeCalendarService.getLatestTradingDay(LocalDate.now().minusDays(1));
        }
        log.info("[IntradayMonitor] ===== 加载目标价 START ===== LLM日期={}, 推荐日期={}, 显示日期={}", llmDate, recDate, this.dataDate);

        // 诊断：先查推荐BUY记录
        if (recDate != null) {
            try {
                String diagSql =
                        "SELECT r.stock_code, r.stock_name, r.suggested_buy_price " +
                                "FROM stock_recommendation r " +
                                "WHERE r.recommend_date = ? AND r.action_tag = 'BUY'";
                List<Map<String, Object>> allBuy = jdbcTemplate.queryForList(diagSql, recDate);
                log.info("[IntradayMonitor] 诊断: {} 共有 {} 条BUY推荐", recDate, allBuy.size());
                for (Map<String, Object> rec : allBuy) {
                    log.info("[IntradayMonitor] 诊断-BUY: {}({}) suggested_buy_price={}",
                            rec.get("stock_name"), rec.get("stock_code"), rec.get("suggested_buy_price"));
                }
            } catch (Exception e) {
                log.warn("[IntradayMonitor] 诊断查询失败: {}", e.getMessage());
            }
        }

        try {
            // 数据源1: llm_analysis BUY推荐（用LLM自己的最新日期）
            if (llmDate != null) {
                String llmSql =
                        "SELECT a.stock_code, a.stock_name, a.buy_price_low, a.buy_price_high, " +
                                "a.stop_loss, a.target_price " +
                                "FROM llm_analysis a " +
                                "WHERE a.analysis_date = ? AND a.recommendation = 'BUY' " +
                                "AND a.buy_price_high IS NOT NULL";
                jdbcTemplate.query(llmSql, new Object[]{llmDate}, rs -> {
                    TargetPriceInfo info = new TargetPriceInfo();
                    info.setStockCode(rs.getString("stock_code"));
                    info.setStockName(rs.getString("stock_name"));
                    info.setBuyPriceLow(rs.getBigDecimal("buy_price_low"));
                    info.setBuyPriceHigh(rs.getBigDecimal("buy_price_high"));
                    info.setStopLoss(rs.getBigDecimal("stop_loss"));
                    info.setTargetPrice(rs.getBigDecimal("target_price"));
                    info.setSource("LLM");
                    targetPriceCache.put(info.getStockCode(), info);
                });
            }
            int llmCount = targetPriceCache.size();

            // 数据源2: stock_recommendation 智能推荐（用推荐自己的最新日期）
            if (recDate != null) {
                String recSql =
                        "SELECT r.stock_code, r.stock_name, r.suggested_buy_price, r.close_price, " +
                                "r.suggested_stop_loss, r.suggested_take_profit, r.suggested_target_price " +
                                "FROM stock_recommendation r " +
                                "WHERE r.recommend_date = ? AND r.action_tag = 'BUY' " +
                                "AND r.suggested_buy_price IS NOT NULL";
                jdbcTemplate.query(recSql, new Object[]{recDate}, rs -> {
                    String code = rs.getString("stock_code");
                    if (targetPriceCache.containsKey(code)) return;

                    double buyPrice = rs.getDouble("suggested_buy_price");
                    double closePrice = rs.getDouble("close_price");

                    TargetPriceInfo info = new TargetPriceInfo();
                    info.setStockCode(code);
                    info.setStockName(rs.getString("stock_name"));
                    info.setBuyPriceLow(BigDecimal.valueOf(buyPrice * 0.95));
                    info.setBuyPriceHigh(BigDecimal.valueOf(buyPrice * 1.05));
                    // #6: 从推荐实体读取止损价/目标价，不再硬编码
                    double stopLoss = rs.getDouble("suggested_stop_loss");
                    info.setStopLoss(!rs.wasNull() && stopLoss > 0
                            ? BigDecimal.valueOf(stopLoss)
                            : BigDecimal.valueOf(buyPrice * 0.92)); // 回退8%止损
                    double targetPrice = rs.getDouble("suggested_target_price");
                    if (!rs.wasNull() && targetPrice > 0) {
                        info.setTargetPrice(BigDecimal.valueOf(targetPrice));
                    } else {
                        info.setTargetPrice(closePrice > 0 ? BigDecimal.valueOf(closePrice * 1.20) : null);
                    }
                    info.setSource("推荐");
                    targetPriceCache.put(code, info);
                });
            }
            int recCount = targetPriceCache.size() - llmCount;

            log.info("[IntradayMonitor] 加载目标价: {} 只股票 (LLM:{}, 推荐:{})", targetPriceCache.size(), llmCount, recCount);
        } catch (Exception e) {
            log.warn("[IntradayMonitor] 加载目标价失败: {}", e.getMessage());
        }

        // 恢复用户自定义股票（刷新不被覆盖）
        targetPriceCache.putAll(customStocks);
        if (!customStocks.isEmpty()) {
            log.info("[IntradayMonitor] 恢复自定义股票: {} 只", customStocks.size());
        }
    }

    // ── 自定义股票管理 ──

    /**
     * 从数据库加载自定义股票（启动时调用）
     */
    public void loadCustomStocksFromDb() {
        try {
            String sql = "SELECT stock_code, stock_name, buy_price_low, buy_price_high, stop_loss, target_price " +
                    "FROM monitor_custom_stock";
            jdbcTemplate.query(sql, rs -> {
                TargetPriceInfo info = new TargetPriceInfo();
                info.setStockCode(rs.getString("stock_code"));
                info.setStockName(rs.getString("stock_name"));
                info.setBuyPriceLow(rs.getBigDecimal("buy_price_low"));
                info.setBuyPriceHigh(rs.getBigDecimal("buy_price_high"));
                info.setStopLoss(rs.getBigDecimal("stop_loss"));
                info.setTargetPrice(rs.getBigDecimal("target_price"));
                info.setSource("客户定义");
                customStocks.put(info.getStockCode(), info);
            });
            if (!customStocks.isEmpty()) {
                log.info("[IntradayMonitor] 从数据库加载自定义股票: {} 只", customStocks.size());
            }
        } catch (Exception e) {
            log.warn("[IntradayMonitor] 从数据库加载自定义股票失败: {}", e.getMessage());
        }
    }

    /**
     * 添加用户自定义监控股票（同时持久化到数据库）
     * @param info 目标价信息（source自动设为"客户定义"）
     */
    public void addCustomStock(TargetPriceInfo info) {
        info.setSource("客户定义");
        customStocks.put(info.getStockCode(), info);
        targetPriceCache.put(info.getStockCode(), info);
        log.info("[IntradayMonitor] 添加自定义股票: {}({}) 买入区间[{}~{}] 止损价:{}",
                info.getStockName(), info.getStockCode(),
                info.getBuyPriceLow(), info.getBuyPriceHigh(), info.getStopLoss());

        // 持久化到数据库（INSERT ON DUPLICATE KEY UPDATE）
        try {
            String sql = "INSERT INTO monitor_custom_stock (stock_code, stock_name, buy_price_low, buy_price_high, stop_loss, target_price) " +
                    "VALUES (?, ?, ?, ?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE stock_name=VALUES(stock_name), buy_price_low=VALUES(buy_price_low), " +
                    "buy_price_high=VALUES(buy_price_high), stop_loss=VALUES(stop_loss), target_price=VALUES(target_price)";
            jdbcTemplate.update(sql,
                    info.getStockCode(), info.getStockName(),
                    info.getBuyPriceLow(), info.getBuyPriceHigh(),
                    info.getStopLoss(), info.getTargetPrice());
            log.debug("[IntradayMonitor] 自定义股票已持久化: {}", info.getStockCode());
        } catch (Exception e) {
            log.warn("[IntradayMonitor] 自定义股票持久化失败: {} - {}", info.getStockCode(), e.getMessage());
        }
    }

    /**
     * 移除用户自定义监控股票（同时从数据库删除）
     */
    public boolean removeCustomStock(String stockCode) {
        TargetPriceInfo removed = customStocks.remove(stockCode);
        if (removed != null) {
            targetPriceCache.remove(stockCode);
            quoteClient.latestPricesRef().remove(stockCode);
            quoteClient.latestChangePctRef().remove(stockCode);
            log.info("[IntradayMonitor] 移除自定义股票: {}", stockCode);

            // 从数据库删除
            try {
                jdbcTemplate.update("DELETE FROM monitor_custom_stock WHERE stock_code = ?", stockCode);
                log.debug("[IntradayMonitor] 自定义股票已从数据库删除: {}", stockCode);
            } catch (Exception e) {
                log.warn("[IntradayMonitor] 自定义股票数据库删除失败: {} - {}", stockCode, e.getMessage());
            }
            return true;
        }
        return false;
    }

    /**
     * 获取所有自定义股票列表
     */
    public List<TargetPriceInfo> getCustomStocks() {
        return new ArrayList<>(customStocks.values());
    }

    // ── 交易日判断（与 IntradayMonitorService 同实现，避免反向依赖） ──

    private boolean isNonTradingDay(LocalDate date) {
        return !tradeCalendarService.isTradingDay(date);
    }

    /** 由 IntradayMonitorService 构造期设置初始数据日期 */
    void setDataDate(LocalDate date) {
        this.dataDate = date;
    }

    /** 可变引用：供 IntradayMonitorService 按原语义直接读写目标价缓存 */
    Map<String, TargetPriceInfo> targetPriceCacheRef() {
        return targetPriceCache;
    }

    /** 只读视图：与拆分前 IntradayMonitorService#getTargetPriceCache 语义一致 */
    public Map<String, TargetPriceInfo> getTargetPriceCache() {
        return Collections.unmodifiableMap(targetPriceCache);
    }
}
