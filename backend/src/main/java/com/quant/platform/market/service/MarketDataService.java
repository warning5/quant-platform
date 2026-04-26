package com.quant.platform.market.service;

import com.quant.platform.market.domain.MarketDailyBar;
import com.quant.platform.stock.entity.StockDaily;
import com.quant.platform.stock.entity.StockInfo;
import com.quant.platform.stock.mapper.StockInfoMapper;
import com.quant.platform.stock.service.ClickHouseStockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 市场数据服务 - 适配层
 * 所有 stock_daily 查询统一走 ClickHouseStockService（自动 CH/MySQL 切换）
 */
@Slf4j
@Service
public class MarketDataService {

    private final ClickHouseStockService clickHouseStockService;
    private final StockInfoMapper stockInfoMapper;

    /** code → market (SH/SZ/BJ) */
    private Map<String, String> codeMarketMap;

    /** code → totalMarketCap (来自 stock_info 的最新市值，万元) */
    private Map<String, BigDecimal> codeMarketCapMap;

    /** 指数代码 → 指数名称（用于区分指数和同代码个股，如 000001=上证指数 vs 平安银行） */
    private static final Map<String, String> INDEX_NAME_MAP = Map.ofEntries(
            Map.entry("000001", "上证指数"),
            Map.entry("000016", "上证50"),
            Map.entry("000022", "中证红利"),
            Map.entry("000300", "沪深300"),
            Map.entry("000688", "科创50"),
            Map.entry("000852", "中证1000"),
            Map.entry("000905", "中证500"),
            Map.entry("399001", "深证成指"),
            Map.entry("399006", "创业板指"),
            Map.entry("399303", "国证2000")
    );

    public MarketDataService(ClickHouseStockService clickHouseStockService, StockInfoMapper stockInfoMapper) {
        this.clickHouseStockService = clickHouseStockService;
        this.stockInfoMapper = stockInfoMapper;
    }

    @PostConstruct
    public void init() {
        log.info("[MarketDataService] 加载 code -> market 映射...");
        List<StockInfo> all = stockInfoMapper.selectList(null);
        codeMarketMap = all.stream()
                .collect(Collectors.toMap(StockInfo::getCode, StockInfo::getMarket));
        log.info("[MarketDataService] 已加载 {} 只股票的 market 映射", codeMarketMap.size());

        // 预加载最新市值数据（用于市值因子 SIZE 的兜底）
        codeMarketCapMap = all.stream()
                .filter(s -> s.getTotalMarketCap() != null)
                .collect(Collectors.toMap(
                        StockInfo::getCode,
                        s -> s.getTotalMarketCap(),
                        (v1, v2) -> v1  // 处理重复 key
                ));
        log.info("[MarketDataService] 已加载 {} 只股票的最新市值", codeMarketCapMap.size());
    }

    // ==================== 概览（轻量） ====================

    /** 概览缓存：同一交易日不重复查询数据库 */
    private volatile Map<String, Object> overviewCache;
    private volatile String cachedDate = null;

    /**
     * 获取最新交易日的统计摘要（SQL聚合 + 日级缓存，不加载全量数据）
     */
    public Map<String, Object> getOverviewSummary() {
        LocalDate today = LocalDate.now();
        LocalDate latestDate = clickHouseStockService.getLatestTradingDate(today.minusDays(30), today);
        if (latestDate == null) {
            return Map.of(
                    "symbolCount", codeMarketMap.size(),
                    "latestDate", "-",
                    "riseCount", 0, "fallCount", 0, "flatCount", 0,
                    "avgPctChg", "0.00",
                    "totalAmount", BigDecimal.ZERO,
                    "topGainers", List.of(),
                    "topLosers", List.of()
            );
        }

        String dateStr = latestDate.toString();
        // 日级缓存：同一交易日直接返回缓存
        if (dateStr.equals(cachedDate) && overviewCache != null) {
            return overviewCache;
        }

        // SQL 聚合查询统计
        Map<String, Object> stats = clickHouseStockService.getOverviewStats(latestDate);
        long count = ((Number) stats.get("count")).longValue();
        long rises = ((Number) stats.getOrDefault("riseCount", 0)).longValue();
        long falls = ((Number) stats.getOrDefault("fallCount", 0)).longValue();
        long flats = ((Number) stats.getOrDefault("flatCount", 0)).longValue();
        double avgPctChg = ((Number) stats.getOrDefault("avgPctChg", 0)).doubleValue();
        BigDecimal totalAmount = new BigDecimal(stats.getOrDefault("totalAmount", "0").toString());

        // SQL 查询 Top20 涨幅 / Top20 跌幅
        List<Map<String, Object>> topGainRows = clickHouseStockService.getTopByPctChg(latestDate, 20, "DESC");
        List<Map<String, Object>> topLossRows = clickHouseStockService.getTopByPctChg(latestDate, 20, "ASC");

        List<MarketDailyBar> topGainers = topGainRows.stream()
                .map(row -> rowToMarketBar(row)).collect(Collectors.toList());
        List<MarketDailyBar> topLosers = topLossRows.stream()
                .map(row -> rowToMarketBar(row)).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("symbolCount", codeMarketMap.size());
        result.put("latestDate", dateStr);
        result.put("riseCount", rises);
        result.put("fallCount", falls);
        result.put("flatCount", flats);
        result.put("avgPctChg", String.format("%.2f", avgPctChg));
        result.put("totalAmount", totalAmount.divide(BigDecimal.valueOf(10000), 0, RoundingMode.HALF_UP));
        result.put("topGainers", topGainers);
        result.put("topLosers", topLosers);

        // 写入缓存
        this.overviewCache = result;
        this.cachedDate = dateStr;

        return result;
    }

    // ==================== 截面数据（分页） ====================

    /**
     * 分页获取截面数据
     */
    public Map<String, Object> getCrossSectionPaged(LocalDate date, int page, int size, String keyword, String sortField, String sortOrder) {
        Map<String, Object> pageResult = clickHouseStockService.getCrossSectionPaged(date, page, size, keyword, sortField, sortOrder);

        @SuppressWarnings("unchecked")
        List<StockDaily> records = (List<StockDaily>) pageResult.get("data");
        List<MarketDailyBar> data = records.stream()
                .map(sd -> toMarketBar(sd, codeMarketMap.get(sd.getCode())))
                .collect(Collectors.toList());

        pageResult.put("data", data);
        return pageResult;
    }

    // ==================== 原有接口 ====================

    /**
     * 根据 symbol（如 000001.SZ）解析并查询
     */
    public List<MarketDailyBar> getBarsBySymbol(String symbol, LocalDate startDate, LocalDate endDate) {
        String code = parseCode(symbol);
        String market = parseMarket(symbol);
        List<StockDaily> dailies = clickHouseStockService.getStockDaily(code, startDate, endDate);
        return dailies.stream()
                .map(sd -> toMarketBar(sd, market))
                .collect(Collectors.toList());
    }

    /**
     * 获取单个标的在指定日期区间的K线（供回测引擎加载基准使用）
     *
     * 自动识别指数代码：若 code 在 INDEX_NAME_MAP 中，则按 code + name 精确查询指数数据，
     * 避免与同代码个股混淆（如 000001 上证指数 vs 000001 平安银行）。
     */
    public List<MarketDailyBar> getBarsInRange(String symbol, LocalDate startDate, LocalDate endDate) {
        String code = parseCode(symbol);
        String market = parseMarket(symbol);
        String indexName = INDEX_NAME_MAP.get(code);

        List<StockDaily> dailies = clickHouseStockService.getStockDaily(code, startDate, endDate);

        if (indexName != null) {
            // 指数：按 name 精确匹配，避免查到同代码个股
            dailies = dailies.stream()
                    .filter(sd -> indexName.equals(sd.getName()))
                    .collect(Collectors.toList());
        }

        return dailies.stream()
                .map(sd -> toMarketBar(sd, market))
                .collect(Collectors.toList());
    }

    /**
     * 获取某日所有股票数据（截面数据，排除指数）
     */
    public List<MarketDailyBar> getBarsAtDate(LocalDate date) {
        List<StockDaily> dailies = clickHouseStockService.getStockDailyByDate(date, INDEX_NAME_MAP.values());
        return dailies.stream()
                .map(sd -> toMarketBar(sd, codeMarketMap.get(sd.getCode())))
                .collect(Collectors.toList());
    }

    /**
     * 获取所有交易日期
     */
    public List<LocalDate> getTradingDates(LocalDate start, LocalDate end) {
        return clickHouseStockService.getTradingDates(start, end);
    }

    /**
     * 获取所有股票 symbol 列表
     */
    public List<String> getAllSymbols() {
        return codeMarketMap.entrySet().stream()
                .map(e -> e.getKey() + "." + e.getValue())
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * 远程搜索股票（前端 Select 用）
     */
    public List<Map<String, String>> searchSymbols(String keyword, int limit) {
        String kw = (keyword != null) ? keyword.trim() : "";
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StockInfo> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        // 空关键词时返回前 N 只股票（按代码排序），有关键词时模糊搜索
        if (!kw.isEmpty()) {
            wrapper.and(w -> w.like(StockInfo::getCode, kw).or().like(StockInfo::getName, kw));
        }
        wrapper.last(String.format("LIMIT %d", limit));
        wrapper.orderByAsc(StockInfo::getCode);
        return stockInfoMapper.selectList(wrapper).stream()
                .map(s -> Map.of("code", s.getCode(), "market", s.getMarket() != null ? s.getMarket() : "",
                        "name", s.getName() != null ? s.getName() : "",
                        "symbol", s.getCode() + "." + (s.getMarket() != null ? s.getMarket() : "")))
                .collect(Collectors.toList());
    }

    /**
     * 批量导入行情数据
     * @deprecated 数据统一通过 stock_daily 写入
     */
    @Deprecated
    public int importBars(List<MarketDailyBar> bars) {
        log.warn("importBars is deprecated. Data should be written to stock_daily directly.");
        return 0;
    }

    // ==================== 字段转换 ====================

    private MarketDailyBar toMarketBar(StockDaily sd, String market) {
        String symbol = sd.getCode() + "." + (market != null ? market : "");

        // 从 stock_info 获取最新市值（不再依赖 stock_daily 的市值字段）
        BigDecimal marketCap = null;
        BigDecimal circMarketCap = null;
        BigDecimal fallbackCap = codeMarketCapMap.get(sd.getCode());
        if (fallbackCap != null && fallbackCap.compareTo(BigDecimal.ZERO) > 0) {
            // stock_info 存储的是元，转换为万元（除以 10000）
            marketCap = fallbackCap.divide(BigDecimal.valueOf(10000), 4, RoundingMode.HALF_UP);
            // 流通市值暂用总市值近似（stock_info 没有单独的流通市值字段）
            circMarketCap = marketCap;
        }

        return MarketDailyBar.builder()
                .symbol(symbol)
                .name(sd.getName())
                .tradeDate(sd.getTradeDate())
                .open(sd.getOpenPrice())
                .high(sd.getHighPrice())
                .low(sd.getLowPrice())
                .close(sd.getClosePrice())
                .preClose(sd.getPreClose())
                .changeAmt(sd.getChangeAmount())
                .pctChg(sd.getChangePercent())
                .vol(sd.getVolume() != null ? BigDecimal.valueOf(sd.getVolume()) : null)
                .amount(sd.getAmount() != null ? sd.getAmount().divide(BigDecimal.valueOf(1000), 4, RoundingMode.HALF_UP) : null)
                .marketCap(marketCap)
                .circMarketCap(circMarketCap)
                .turnoverRate(sd.getTurnoverRate())
                .build();
    }

    /**
     * 将 SQL 聚合查询的 Map 行转为 MarketDailyBar（用于概览 Top20）
     */
    private MarketDailyBar rowToMarketBar(Map<String, Object> row) {
        String code = (String) row.get("code");
        String market = codeMarketMap.getOrDefault(code, "");
        String symbol = code + "." + market;
        return MarketDailyBar.builder()
                .symbol(symbol)
                .name((String) row.get("name"))
                .pctChg(row.get("change_percent") != null ? new BigDecimal(row.get("change_percent").toString()) : null)
                .close(row.get("close_price") != null ? new BigDecimal(row.get("close_price").toString()) : null)
                .vol(row.get("volume") != null ? BigDecimal.valueOf(((Number) row.get("volume")).longValue()) : null)
                .amount(row.get("amount") != null ? new BigDecimal(row.get("amount").toString())
                        .divide(BigDecimal.valueOf(1000), 4, RoundingMode.HALF_UP) : null)
                .turnoverRate(row.get("turnover_rate") != null ? new BigDecimal(row.get("turnover_rate").toString()) : null)
                .build();
    }

    private String parseCode(String symbol) {
        int dot = symbol.lastIndexOf('.');
        return dot > 0 ? symbol.substring(0, dot) : symbol;
    }

    private String parseMarket(String symbol) {
        int dot = symbol.lastIndexOf('.');
        return dot > 0 ? symbol.substring(dot + 1) : null;
    }
}
