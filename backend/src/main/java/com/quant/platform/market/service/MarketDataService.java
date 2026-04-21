package com.quant.platform.market.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quant.platform.market.domain.MarketDailyBar;
import com.quant.platform.stock.entity.StockDaily;
import com.quant.platform.stock.entity.StockInfo;
import com.quant.platform.stock.mapper.StockDailyMapper;
import com.quant.platform.stock.mapper.StockInfoMapper;
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
 */
@Slf4j
@Service
public class MarketDataService {

    private final StockDailyMapper stockDailyMapper;
    private final StockInfoMapper stockInfoMapper;

    /** code → market (SH/SZ/BJ) */
    private Map<String, String> codeMarketMap;

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

    public MarketDataService(StockDailyMapper stockDailyMapper, StockInfoMapper stockInfoMapper) {
        this.stockDailyMapper = stockDailyMapper;
        this.stockInfoMapper = stockInfoMapper;
    }

    @PostConstruct
    public void init() {
        log.info("[MarketDataService] 加载 code -> market 映射...");
        List<StockInfo> all = stockInfoMapper.selectList(null);
        codeMarketMap = all.stream()
                .collect(Collectors.toMap(StockInfo::getCode, StockInfo::getMarket));
        log.info("[MarketDataService] 已加载 {} 只股票的 market 映射", codeMarketMap.size());
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
        LocalDate latestDate = findLatestTradingDate(today.minusDays(30), today);
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
        Map<String, Object> stats = stockDailyMapper.selectOverviewStats(latestDate);
        long count = ((Number) stats.get("count")).longValue();
        long rises = ((Number) stats.getOrDefault("riseCount", 0)).longValue();
        long falls = ((Number) stats.getOrDefault("fallCount", 0)).longValue();
        long flats = ((Number) stats.getOrDefault("flatCount", 0)).longValue();
        double avgPctChg = ((Number) stats.getOrDefault("avgPctChg", 0)).doubleValue();
        BigDecimal totalAmount = new BigDecimal(stats.getOrDefault("totalAmount", "0").toString());

        // SQL 查询 Top20 涨幅 / Top20 跌幅
        List<Map<String, Object>> topGainRows = stockDailyMapper.selectTopByPctChg(latestDate, 20, "DESC");
        List<Map<String, Object>> topLossRows = stockDailyMapper.selectTopByPctChg(latestDate, 20, "ASC");

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

    /**
     * 获取最新交易日日期（不拉全量数据）
     */
    private LocalDate findLatestTradingDate(LocalDate start, LocalDate end) {
        LambdaQueryWrapper<StockDaily> wrapper = new LambdaQueryWrapper<>();
        wrapper.between(StockDaily::getTradeDate, start, end)
                .select(StockDaily::getTradeDate)
                .orderByDesc(StockDaily::getTradeDate)
                .last("LIMIT 1");
        List<StockDaily> result = stockDailyMapper.selectList(wrapper);
        return result.isEmpty() ? null : result.get(0).getTradeDate();
    }

    // ==================== 截面数据（分页） ====================

    /**
     * 分页获取截面数据
     */
    public Map<String, Object> getCrossSectionPaged(LocalDate date, int page, int size, String keyword, String sortField, String sortOrder) {
        LambdaQueryWrapper<StockDaily> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StockDaily::getTradeDate, date);

        // 关键词过滤（代码或名称）
        if (keyword != null && !keyword.trim().isEmpty()) {
            wrapper.and(w -> w
                    .like(StockDaily::getCode, keyword.trim())
                    .or()
                    .like(StockDaily::getName, keyword.trim()));
        }

        // 排序
        if (sortField != null && !sortField.isEmpty()) {
            boolean asc = !"desc".equalsIgnoreCase(sortOrder);
            switch (sortField) {
                case "pctChg":
                    if (asc) wrapper.orderByAsc(StockDaily::getChangePercent);
                    else wrapper.orderByDesc(StockDaily::getChangePercent);
                    break;
                case "amount":
                    if (asc) wrapper.orderByAsc(StockDaily::getAmount);
                    else wrapper.orderByDesc(StockDaily::getAmount);
                    break;
                case "vol":
                    if (asc) wrapper.orderByAsc(StockDaily::getVolume);
                    else wrapper.orderByDesc(StockDaily::getVolume);
                    break;
                case "close":
                    if (asc) wrapper.orderByAsc(StockDaily::getClosePrice);
                    else wrapper.orderByDesc(StockDaily::getClosePrice);
                    break;
                case "turnoverRate":
                    if (asc) wrapper.orderByAsc(StockDaily::getTurnoverRate);
                    else wrapper.orderByDesc(StockDaily::getTurnoverRate);
                    break;
                default:
                    wrapper.orderByAsc(StockDaily::getCode);
            }
        } else {
            wrapper.orderByDesc(StockDaily::getChangePercent);
        }

        // 总数
        Long total = stockDailyMapper.selectCount(wrapper);

        // 分页
        wrapper.last(String.format("LIMIT %d OFFSET %d", size, (page - 1) * size));
        List<StockDaily> records = stockDailyMapper.selectList(wrapper);

        List<MarketDailyBar> data = records.stream()
                .map(sd -> toMarketBar(sd, codeMarketMap.get(sd.getCode())))
                .collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        result.put("totalPages", (total + size - 1) / size);
        result.put("data", data);
        return result;
    }

    // ==================== 原有接口 ====================

    /**
     * 根据 symbol（如 000001.SZ）解析并查询
     */
    public List<MarketDailyBar> getBarsBySymbol(String symbol, LocalDate startDate, LocalDate endDate) {
        String code = parseCode(symbol);
        String market = parseMarket(symbol);
        LambdaQueryWrapper<StockDaily> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StockDaily::getCode, code)
                .between(StockDaily::getTradeDate, startDate, endDate)
                .orderByAsc(StockDaily::getTradeDate);
        return stockDailyMapper.selectList(wrapper).stream()
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

        LambdaQueryWrapper<StockDaily> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StockDaily::getCode, code);
        if (indexName != null) {
            // 指数：按 name 精确匹配，避免查到同代码个股
            wrapper.eq(StockDaily::getName, indexName);
        }
        wrapper.between(StockDaily::getTradeDate, startDate, endDate)
                .orderByAsc(StockDaily::getTradeDate);

        return stockDailyMapper.selectList(wrapper).stream()
                .map(sd -> toMarketBar(sd, market))
                .collect(Collectors.toList());
    }

    /**
     * 获取某日所有股票数据（截面数据，排除指数）
     */
    public List<MarketDailyBar> getBarsAtDate(LocalDate date) {
        LambdaQueryWrapper<StockDaily> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StockDaily::getTradeDate, date)
                .notIn(StockDaily::getName, INDEX_NAME_MAP.values());
        return stockDailyMapper.selectList(wrapper).stream()
                .map(sd -> toMarketBar(sd, codeMarketMap.get(sd.getCode())))
                .collect(Collectors.toList());
    }

    /**
     * 获取所有交易日期
     */
    public List<LocalDate> getTradingDates(LocalDate start, LocalDate end) {
        LambdaQueryWrapper<StockDaily> wrapper = new LambdaQueryWrapper<>();
        wrapper.between(StockDaily::getTradeDate, start, end)
                .select(StockDaily::getTradeDate)
                .groupBy(StockDaily::getTradeDate)
                .orderByAsc(StockDaily::getTradeDate);
        return stockDailyMapper.selectList(wrapper).stream()
                .map(StockDaily::getTradeDate)
                .collect(Collectors.toList());
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
        LambdaQueryWrapper<StockInfo> wrapper = new LambdaQueryWrapper<>();
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
                .marketCap(sd.getMarketCap())
                .circMarketCap(sd.getCircMarketCap())
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
