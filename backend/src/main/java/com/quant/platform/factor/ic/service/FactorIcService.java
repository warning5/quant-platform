package com.quant.platform.factor.ic.service;

import com.quant.platform.factor.ic.domain.FactorIcRecord;
import com.quant.platform.factor.ic.mapper.FactorIcRecordMapper;
import com.quant.platform.stock.service.ClickHouseStockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * 因子 IC/IR 计算服务 (Phase 3.1)
 * IC (Information Coefficient) = 因子值与未来N日收益率的 Spearman Rank 相关系数
 * IR (Information Ratio) = IC序列均值 / IC序列标准差
 * 用途：
 * 1. 评估因子有效性（IC绝对值越大越好）
 * 2. 评估因子稳定性（IR越大越好）
 * 3. 为推荐管线提供自适应因子权重（IC衰减的因子自动降权）
 *
 * 安全：所有接受 factorCode/factorCodes 的方法均通过白名单校验（字母/数字/下划线/横线）
 */
@Slf4j
@Service
public class FactorIcService {

    /** factorCode 白名单正则（防御 SQL 注入） */
    private static final java.util.regex.Pattern FACTOR_CODE_PATTERN =
            java.util.regex.Pattern.compile("[a-zA-Z0-9_\\-]+");

    private static void checkFactorCode(String factorCode) {
        if (factorCode == null || !FACTOR_CODE_PATTERN.matcher(factorCode).matches()) {
            throw new IllegalArgumentException("Invalid factorCode: " + factorCode);
        }
    }

    private static void checkFactorCodes(List<String> factorCodes) {
        if (factorCodes == null) return;
        for (String fc : factorCodes) {
            checkFactorCode(fc);
        }
    }

    private final FactorIcRecordMapper icRecordMapper;
    private final ClickHouseStockService clickHouseStockService;

    /** IC 计算用的未来收益天数（可通过 factor.ic.forward-return-days 配置，默认5） */
    @Value("${quant.factor.ic.forward-return-days:5}")
    private int forwardReturnDays;

    public FactorIcService(FactorIcRecordMapper icRecordMapper, ClickHouseStockService clickHouseStockService) {
        this.icRecordMapper = icRecordMapper;
        this.clickHouseStockService = clickHouseStockService;
    }

    /**
     * 计算并保存指定因子的 IC/IR
     *
     * @param date        截面日期（null 则使用最新交易日）
     * @param factorCodes 要计算的因子代码列表（null 或 空 则无操作）
     */
    public Map<String, FactorIcRecord> computeAndSaveIc(LocalDate date, List<String> factorCodes) {
        // 安全校验：factorCode 白名单
        checkFactorCodes(factorCodes);

        if (factorCodes == null || factorCodes.isEmpty()) {
            log.warn("[FactorIC] 未指定因子，跳过计算");
            return Collections.emptyMap();
        }
        if (date == null) {
            date = clickHouseStockService.getLatestTradeDate();
        }
        if (date == null) {
            log.warn("[FactorIC] 无法获取最新交易日");
            return Collections.emptyMap();
        }

        // 获取实际的前瞻交易日（第N个交易日，而非N个日历日）
        // 近期日期可能没有足够的前瞻数据，属于正常情况
        LocalDate forwardDate = findForwardTradingDate(date, forwardReturnDays);
        if (forwardDate == null) {
            log.debug("[FactorIC] 前瞻交易日不足: date={} forwardDays={}", date, forwardReturnDays);
            return Collections.emptyMap();
        }

        // 需要获取: 截面日期的因子值 + 未来N天收益率
        // 通过 CH 直接计算 Spearman Rank Correlation

        Map<String, FactorIcRecord> results = new LinkedHashMap<>();

        for (String factorCode : factorCodes) {
            try {
                FactorIcRecord record = computeSingleFactorIc(factorCode, date, forwardDate);
                if (record != null) {
                    record.setForwardDays(forwardReturnDays);
                    // 计算滚动均值/IR
                    computeRollingStats(record);
                    // 保存
                    icRecordMapper.upsert(record);
                    results.put(factorCode, record);
                    log.debug("[FactorIC] {} date={} IC={} IR_20d={}",
                            factorCode, date, String.format("%.4f", record.getIcValue()),
                            record.getIr20d() != null ? String.format("%.3f", record.getIr20d()) : "N/A");
                } else {
                    log.debug("[FactorIC] 无IC数据: factor={} date={} forwardDate={}", factorCode, date, forwardDate);
                }
            } catch (Exception e) {
                log.warn("[FactorIC] 计算异常: factor={} date={} error={}", factorCode, date, e.getMessage());
            }
        }

        log.info("[FactorIC] 完成: date={} factors={}", date, results.size());
        return results;
    }

    /**
     * 批量计算并保存 IC/IR（按日期范围，仅遍历实际交易日）
     *
     * @param startDate   开始日期
     * @param endDate     结束日期
     * @param factorCodes 要计算的因子代码列表
     * @return 每个日期的计算结果
     */
    public Map<LocalDate, Map<String, FactorIcRecord>> computeAndSaveIcBatch(LocalDate startDate, LocalDate endDate, List<String> factorCodes) {
        // 安全校验：factorCode 白名单
        checkFactorCodes(factorCodes);

        Map<LocalDate, Map<String, FactorIcRecord>> allResults = new LinkedHashMap<>();
        int totalRecords = 0;

        // 获取范围内的实际交易日，避免遍历周末和非交易日
        List<LocalDate> tradingDates = clickHouseStockService.getTradingDates(startDate, endDate);
        if (tradingDates.isEmpty()) {
            log.warn("[FactorIC] 日期范围内无交易日: range=[{}, {}]", startDate, endDate);
            return allResults;
        }

        log.info("[FactorIC] 批量计算开始: range=[{}, {}] tradingDays={} factors={}",
                startDate, endDate, tradingDates.size(), factorCodes.size());

        int skipped = 0;
        for (LocalDate current : tradingDates) {
            try {
                Map<String, FactorIcRecord> dayResults = computeAndSaveIc(current, factorCodes);
                if (!dayResults.isEmpty()) {
                    allResults.put(current, dayResults);
                    totalRecords += dayResults.size();
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                log.warn("[FactorIC] 批量计算异常: date={} error={}", current, e.getMessage());
                skipped++;
            }
        }

        // 计算可计算的最新日期（需要有前瞻交易日数据）
        LocalDate latestAvailable = null;
        if (!tradingDates.isEmpty()) {
            for (int i = tradingDates.size() - 1; i >= 0; i--) {
                if (findForwardTradingDate(tradingDates.get(i), forwardReturnDays) != null) {
                    latestAvailable = tradingDates.get(i);
                    break;
                }
            }
        }

        log.info("[FactorIC] 批量计算完成: range=[{}, {}] tradingDays={} calculated={} skipped={} totalRecords={}{}",
                startDate, endDate, tradingDates.size(), allResults.size(), skipped, totalRecords,
                latestAvailable != null ? String.format(" (最近可计算日期=%s, 需往前%d交易日才有前瞻数据)", latestAvailable, forwardReturnDays)
                        : " (数据不足，无法计算任何日期的IC)");
        return allResults;
    }

    /**
     * 获取因子自适应权重（基于IC历史）
     * 规则：
     * - IR_20d > 0.5: 权重 1.2 (因子表现好，加仓)
     * - IR_20d > 0.2: 权重 1.0 (正常)
     * - IR_20d > -0.2: 权重 0.8 (表现一般，减仓)
     * - IR_20d <= -0.2: 权重 0.5 (因子失效，大幅降权)
     * 自动从 factor_ic_record 表获取所有已有 IC 记录的因子
     *
     * @return factorCode → adaptiveWeight
     */
    public Map<String, Double> getAdaptiveWeights() {
        Map<String, Double> weights = new HashMap<>();
        // 从 DB 查询所有已有 IC 记录的因子（不再硬编码）
        List<FactorIcRecord> allLatest = icRecordMapper.findAllLatest(forwardReturnDays);
        for (FactorIcRecord latest : allLatest) {
            String factorCode = latest.getFactorCode();
            double weight = 1.0;
            if (latest.getIr20d() != null) {
                double ir = latest.getIr20d();
                if (ir > 0.5) weight = 1.2;
                else if (ir > 0.2) weight = 1.0;
                else if (ir > -0.2) weight = 0.8;
                else weight = 0.5;
            }
            weights.put(factorCode, weight);
        }
        return weights;
    }

    /**
     * 获取所有因子的最新 IR 摘要
     */
    public List<FactorIcRecord> getLatestIcSummary() {
        return icRecordMapper.findAllLatest(forwardReturnDays);
    }

    /**
     * 获取因子最近N天的IC历史序列
     *
     * @param factorCode 因子代码
     * @param endDate 结束日期
     * @param days 天数
     * @return IC值列表（按日期倒序）
     */
    public List<Double> getIcHistory(String factorCode, LocalDate endDate, int days) {
        // 安全校验：factorCode 白名单
        checkFactorCode(factorCode);

        return icRecordMapper.findRecentIcValues(factorCode, endDate, days, forwardReturnDays);
    }

    /**
     * 获取指定因子列表中，所有因子都有IC数据的最新日期（取最新日期的交集）
     * 用于IC加权时自动回退：推荐日太新时，使用此日期查询IC历史
     * 无IC数据的因子会被跳过，仅在所有因子均无IC数据时返回 null
     *
     * @param factorCodes 要查询的因子代码列表
     * @return 所有因子共有IC数据的最新日期，若所有因子均无数据返回 null
     */
    public LocalDate getLatestCommonIcDate(List<String> factorCodes) {
        // 安全校验：factorCode 白名单
        checkFactorCodes(factorCodes);

        if (factorCodes == null || factorCodes.isEmpty()) return null;
        // 修复：取各因子最新IC日期的交集中最新的那个，而非最早的
        LocalDate latestCommon = null;
        int validCount = 0;
        for (String fc : factorCodes) {
            FactorIcRecord record = icRecordMapper.findLatest(fc, forwardReturnDays);
            if (record == null || record.getTradeDate() == null) {
                continue; // 跳过无IC数据的因子
            }
            validCount++;
            if (latestCommon == null || record.getTradeDate().isAfter(latestCommon)) {
                latestCommon = record.getTradeDate();
            }
        }
        return validCount > 0 ? latestCommon : null;
    }

    // ── 私有方法 ──

    /**
     * 查找前瞻交易日（第N个交易日，而非N个日历日）
     *
     * @param date        基准日期
     * @param forwardDays 前瞻交易日数
     * @return 第N个交易日，如果没有足够数据则返回 null
     */
    private LocalDate findForwardTradingDate(LocalDate date, int forwardDays) {
        // 向后查询足够多的交易日（forwardDays * 2 作为缓冲应对周末和假期）
        List<LocalDate> tradingDates = clickHouseStockService.getTradingDates(
                date, date.plusDays(forwardDays * 2L));
        if (tradingDates.size() <= forwardDays) {
            // 扩大范围重试
            tradingDates = clickHouseStockService.getTradingDates(
                    date, date.plusDays(forwardDays * 5L));
        }
        if (tradingDates.size() > forwardDays) {
            return tradingDates.get(forwardDays);
        }
        return null;
    }

    /**
     * 从 ClickHouse 查询单因子单日 IC 值（带缓存）
     *
     * 缓存 key: factorCode + date + forwardDays
     * 缓存时间: 30分钟（因子值日频更新，同日内结果不变）
     * 这是纯查询方法，不写数据库
     */
    @Cacheable(value = "factorIc", cacheManager = "factorIcCacheManager",
               key = "#factorCode + '_' + #date.toString() + '_' + #forwardDays")
    public Map<String, Object> querySpearmanIcFromCH(String factorCode, LocalDate date, int forwardDays) {
        // 安全校验：factorCode 白名单
        checkFactorCode(factorCode);

        LocalDate forwardDate = findForwardTradingDate(date, forwardDays);
        if (forwardDate == null) {
            return Map.of("ic_value", null, "stock_count", 0);
        }

        try {
            String sql = String.format("""
                WITH base AS (
                    SELECT
                        f.symbol,
                        f.factor_val,
                        (d2.close_price - d1.close_price) / d1.close_price * 100 as fwd_return
                    FROM stock.factor_value f
                    INNER JOIN stock.stock_daily d1
                        ON replaceRegexpOne(f.symbol, '\\.[A-Z]+$', '') = d1.code
                        AND d1.trade_date = '%s'
                    INNER JOIN stock.stock_daily d2
                        ON replaceRegexpOne(f.symbol, '\\.[A-Z]+$', '') = d2.code
                        AND d2.trade_date = '%s'
                    WHERE f.factor_code = '%s'
                      AND f.calc_date = '%s'
                      AND f.factor_val IS NOT NULL
                      AND d1.close_price > 0
                      AND d2.close_price > 0
                ),
                ranked AS (
                    SELECT
                        factor_val,
                        fwd_return,
                        rank() OVER (ORDER BY factor_val)     as rk_f,
                        count() OVER (PARTITION BY factor_val) as tie_f,
                        rank() OVER (ORDER BY fwd_return)    as rk_r,
                        count() OVER (PARTITION BY fwd_return) as tie_r
                    FROM base
                ),
                avg_ranked AS (
                    SELECT
                        rk_f + (tie_f - 1) / 2.0  as rankFactor,
                        rk_r + (tie_r - 1) / 2.0  as rankReturn
                    FROM ranked
                )
                SELECT corr(rankFactor, rankReturn) as ic_value, count() as stock_count
                FROM avg_ranked
                """, date, forwardDate, factorCode, date);

            Map<String, Object> row = clickHouseStockService.queryForList(sql).stream().findFirst().orElse(null);
            if (row == null) {
                return Map.of("ic_value", null, "stock_count", 0);
            }
            return row;
        } catch (Exception e) {
            log.warn("[FactorIC] CH查询异常: factor={} date={} forwardDays={} error={}",
                    factorCode, date, forwardDays, e.getMessage());
            return Map.of("ic_value", null, "stock_count", 0, "error", e.getMessage());
        }
    }

    /**
     * 计算单个因子的 IC (Spearman Rank Correlation)
     * 通过 CH 查询: 因子值排名 vs 未来N日收益率排名的相关系数
     */
    private FactorIcRecord computeSingleFactorIc(String factorCode, LocalDate date, LocalDate forwardDate) {
        // CH SQL: 获取截面日期的因子值排名和未来收益率排名，计算相关系数
        // 使用 Spearman = Pearson(rank_x, rank_y)
        // 注意：factor_value.symbol 可能带市场后缀（如 600519.SH），
        // stock_daily.code 不带后缀，需要用 replaceRegexpOne 去掉后缀后再 JOIN

        try {
            // Spearman = Pearson(rank_x, rank_y)，排名需处理并列值（平均排名法）
            // 先用 rank() 得到并列最小值，再用 count() OVER (PARTITION BY val) 计算组团大小，
            // 平均排名 = rk + (tie_size - 1) / 2
            String sql = String.format("""
                WITH base AS (
                    SELECT
                        f.symbol,
                        f.factor_val,
                        (d2.close_price - d1.close_price) / d1.close_price * 100 as fwd_return
                    FROM stock.factor_value f
                    INNER JOIN stock.stock_daily d1
                        ON replaceRegexpOne(f.symbol, '\\.[A-Z]+$', '') = d1.code
                        AND d1.trade_date = '%s'
                    INNER JOIN stock.stock_daily d2
                        ON replaceRegexpOne(f.symbol, '\\.[A-Z]+$', '') = d2.code
                        AND d2.trade_date = '%s'
                    WHERE f.factor_code = '%s'
                      AND f.calc_date = '%s'
                      AND f.factor_val IS NOT NULL
                      AND d1.close_price > 0
                      AND d2.close_price > 0
                ),
                ranked AS (
                    SELECT
                        factor_val,
                        fwd_return,
                        rank() OVER (ORDER BY factor_val)     as rk_f,
                        count() OVER (PARTITION BY factor_val) as tie_f,
                        rank() OVER (ORDER BY fwd_return)    as rk_r,
                        count() OVER (PARTITION BY fwd_return) as tie_r
                    FROM base
                ),
                avg_ranked AS (
                    SELECT
                        rk_f + (tie_f - 1) / 2.0  as rankFactor,
                        rk_r + (tie_r - 1) / 2.0  as rankReturn
                    FROM ranked
                )
                SELECT corr(rankFactor, rankReturn) as ic_value, count() as stock_count
                FROM avg_ranked
                """, date, forwardDate, factorCode, date);

            Map<String, Object> row = clickHouseStockService.queryForList(sql).stream().findFirst().orElse(null);
            if (row == null || row.get("ic_value") == null) {
                log.debug("[FactorIC] CH查询无结果: factor={} date={} forwardDate={}", factorCode, date, forwardDate);
                return null;
            }

            FactorIcRecord record = new FactorIcRecord();
            record.setFactorCode(factorCode);
            record.setTradeDate(date);
            record.setIcValue(toDouble(row.get("ic_value")));
            record.setStockCount(toInt(row.get("stock_count")));
            return record;
        } catch (Exception e) {
            log.warn("[FactorIC] CH查询异常: factor={} date={} forwardDate={} type={} error={}",
                    factorCode, date, forwardDate, e.getClass().getSimpleName(), e.getMessage());
            return null;
        }

    }

    /**
     * 计算滚动均值和IR
     */
    private void computeRollingStats(FactorIcRecord record) {
        if (record.getIcValue() == null) return;

        // 20日滚动
        int fwdDays = record.getForwardDays() != null ? record.getForwardDays() : forwardReturnDays;
        List<Double> ic20 = icRecordMapper.findRecentIcValues(record.getFactorCode(), record.getTradeDate(), 19, fwdDays);
        ic20.addFirst(record.getIcValue()); // 加上当天

        if (ic20.size() >= 10) {
            double avg20 = avg(ic20);
            double std20 = std(ic20, avg20);
            record.setIc20dAvg(round(avg20, 4));
            record.setIr20d(std20 > 0 ? round(avg20 / std20, 3) : null);
        }

        // 60日滚动
        List<Double> ic60 = icRecordMapper.findRecentIcValues(record.getFactorCode(), record.getTradeDate(), 59, fwdDays);
        ic60.addFirst(record.getIcValue());

        if (ic60.size() >= 30) {
            double avg60 = avg(ic60);
            double std60 = std(ic60, avg60);
            record.setIc60dAvg(round(avg60, 4));
            record.setIr60d(std60 > 0 ? round(avg60 / std60, 3) : null);
        }
    }

    private static double avg(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private static double std(List<Double> values, double mean) {
        double sum = values.stream().mapToDouble(v -> Math.pow(v - mean, 2)).sum();
        return Math.sqrt(sum / values.size());
    }

    private static double round(double val, int scale) {
        return Math.round(val * Math.pow(10, scale)) / Math.pow(10, scale);
    }

    private static double toDouble(Object obj) {
        if (obj == null) return 0;
        if (obj instanceof Number) return ((Number) obj).doubleValue();
        return 0;
    }

    private static int toInt(Object obj) {
        if (obj == null) return 0;
        if (obj instanceof Number) return ((Number) obj).intValue();
        return 0;
    }
}
