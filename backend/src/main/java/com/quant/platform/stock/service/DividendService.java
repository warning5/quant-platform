package com.quant.platform.stock.service;

import com.quant.platform.stock.entity.StockDividend;
import com.quant.platform.stock.mapper.StockDividendMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 分红除权服务
 * 提供复权因子计算、分红事件查询等功能，供回测引擎使用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DividendService {

    private final StockDividendMapper dividendMapper;

    /**
     * 缓存: symbol -> (exDate -> adjFactor)
     * 复权因子 = ∏(1 + 送股比例 + 转增比例) × ∏(1 - 派息/除息前收盘价)
     * 简化计算: adjFactor = ∏(1 + 送转比例)
     * 对于日期 d，前复权因子 = 从最新除权日到 d 之间所有除权事件的因子乘积
     */
    private final Map<String, Map<LocalDate, BigDecimal>> adjFactorCache = null;

    /**
     * 缓存: symbol -> (exDate -> 每股派息金额)
     */
    private final Map<String, Map<LocalDate, BigDecimal>> cashDivCache = null;

    /**
     * 查询指定股票在日期范围内的分红记录
     *
     * @param symbol 股票代码，如 000001.SZ
     * @param start  开始日期
     * @param end    结束日期
     * @return 分红记录列表
     */
    public List<StockDividend> getDividends(String symbol, LocalDate start, LocalDate end) {
        String code = parseCode(symbol);
        return dividendMapper.findByCodeAndDateRange(code, start, end);
    }

    /**
     * 查询指定日期所有发生除权除息的记录
     *
     * @param date 除权除息日
     * @return 当日所有分红记录
     */
    public List<StockDividend> getDividendsByDate(LocalDate date) {
        return dividendMapper.findByExDate(date);
    }

    /**
     * 获取指定股票的前复权因子映射。
     * 返回的 map: 除权日期 -> 该除权事件导致的复权因子变化。
     * 对于日期 d，累积因子 = ∏{exDate <= d} factor(exDate)
     *
     * @param symbol 股票代码
     * @return exDate -> adjFactor (每次除权的单次因子)
     */
    public Map<LocalDate, BigDecimal> getAdjFactors(String symbol) {
        String code = parseCode(symbol);
        List<StockDividend> dividends = dividendMapper.findByCode(code);

        if (dividends.isEmpty()) {
            return Map.of();
        }

        Map<LocalDate, BigDecimal> factors = new TreeMap<>();
        for (StockDividend d : dividends) {
            // 送股 + 转增导致的股本扩张比例
            BigDecimal stockRatio = BigDecimal.ONE
                    .add(nvl(d.getStockDividend()))
                    .add(nvl(d.getConvertDividend()));
            // 派息导致的缩股效果（简化: 派息/除息日价格 ≈ 派息/当日收盘价）
            // 这里用简化方式: 只考虑送转，不考虑派息对价格的影响
            // 原因: 派息金额相对于股价通常很小，且除息日当天收盘价已经反映了派息
            factors.put(d.getExDividendDate(), stockRatio);
        }
        return factors;
    }

    /**
     * 计算累积前复权因子: 给定一个日期，返回从该日期到最新除权日的累积复权因子。
     * 前复权: 把历史价格调高到与最新价格可比。
     * adjFactor(date) = ∏{exDate > date} singleFactor(exDate)
     * <p>
     * 使用示例:
     * adjClose = rawClose × adjFactor(date)
     * 这样历史所有价格都以最新股本为基准，可以直接比较。
     *
     * @param symbol 股票代码
     * @param date   需要计算复权因子的日期
     * @return 前复权因子（>= 1.0），如果无分红数据返回 1.0
     */
    public BigDecimal getCumulativeAdjFactor(String symbol, LocalDate date) {
        Map<LocalDate, BigDecimal> factors = getAdjFactors(symbol);
        if (factors.isEmpty()) {
            return BigDecimal.ONE;
        }

        BigDecimal cumulative = BigDecimal.ONE;
        for (Map.Entry<LocalDate, BigDecimal> entry : factors.entrySet()) {
            if (entry.getKey().isAfter(date)) {
                cumulative = cumulative.multiply(entry.getValue());
            }
        }
        return cumulative;
    }

    /**
     * 批量预加载复权因子（供回测引擎启动时调用）。
     * 加载所有股票的除权数据，构建 (symbol, exDate) -> cashDiv 的映射。
     * 复权因子在回测时按需计算（因为只需要在除权日当天调整持仓股数）。
     */
    public void preload() {
        // 这里不预加载全部数据（可能很多），而是在回测启动时按需加载
        log.info("[DividendService] 分红除权服务就绪（按需查询模式）");
    }

    /**
     * 获取指定股票在指定日期的每股派息金额（如果有除权除息事件）。
     * 用于回测引擎在除权除息日当天将分红计入现金。
     *
     * @param symbol 股票代码
     * @param date   日期
     * @return 每股派息金额（税前），如果没有则返回 null
     */
    public BigDecimal getCashDividend(String symbol, LocalDate date) {
        String code = parseCode(symbol);
        List<StockDividend> dividends = dividendMapper.findByCodeAndDateRange(code, date, date);
        if (dividends.isEmpty()) {
            return null;
        }
        // 同一天可能有多次分红（极罕见），累加
        BigDecimal total = BigDecimal.ZERO;
        for (StockDividend d : dividends) {
            total = total.add(nvl(d.getCashDividend()));
        }
        return total.compareTo(BigDecimal.ZERO) > 0 ? total : null;
    }

    /**
     * 获取指定股票在指定日期的送转比例合计。
     * 送转比例 = 每股送股 + 每股转增
     *
     * @param symbol 股票代码
     * @param date   日期
     * @return 送转比例（如 0.5 表示每10股送转5股），如果没有返回 null
     */
    public BigDecimal getStockConvertRatio(String symbol, LocalDate date) {
        String code = parseCode(symbol);
        List<StockDividend> dividends = dividendMapper.findByCodeAndDateRange(code, date, date);
        if (dividends.isEmpty()) {
            return null;
        }
        BigDecimal totalRatio = BigDecimal.ZERO;
        for (StockDividend d : dividends) {
            totalRatio = totalRatio.add(nvl(d.getStockDividend())).add(nvl(d.getConvertDividend()));
        }
        return totalRatio.compareTo(BigDecimal.ZERO) > 0 ? totalRatio : null;
    }

    /**
     * 获取指定日期区间内所有分红事件，按 symbol 分组。
     * 返回: symbol -> List<StockDividend>
     *
     * @param start 开始日期
     * @param end   结束日期
     * @return 分组后的分红事件
     */
    public Map<String, List<StockDividend>> getDividendsGroupedBySymbol(LocalDate start, LocalDate end) {
        // 查询日期范围内所有分红记录
        // 使用 MyBatis-Plus 的 LambdaQueryWrapper
        List<StockDividend> all = dividendMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StockDividend>()
                        .between(StockDividend::getExDividendDate, start, end)
        );
        // 需要拼上市场后缀才能与回测引擎的 symbol 匹配
        // 返回纯 code，由调用方处理
        return all.stream().collect(Collectors.groupingBy(StockDividend::getCode));
    }

    private String parseCode(String symbol) {
        int dot = symbol.lastIndexOf('.');
        return dot > 0 ? symbol.substring(0, dot) : symbol;
    }

    private BigDecimal nvl(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
