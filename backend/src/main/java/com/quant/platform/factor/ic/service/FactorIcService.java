package com.quant.platform.factor.ic.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quant.platform.factor.ic.domain.FactorIcRecord;
import com.quant.platform.factor.ic.mapper.FactorIcRecordMapper;
import com.quant.platform.stock.service.ClickHouseStockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * 因子 IC/IR 计算服务 (Phase 3.1)
 *
 * IC (Information Coefficient) = 因子值与未来N日收益率的 Spearman Rank 相关系数
 * IR (Information Ratio) = IC序列均值 / IC序列标准差
 *
 * 用途：
 * 1. 评估因子有效性（IC绝对值越大越好）
 * 2. 评估因子稳定性（IR越大越好）
 * 3. 为推荐管线提供自适应因子权重（IC衰减的因子自动降权）
 */
@Slf4j
@Service
public class FactorIcService {

    private final FactorIcRecordMapper icRecordMapper;
    private final ClickHouseStockService clickHouseStockService;

    /** IC 计算用的未来收益天数 */
    private static final int FORWARD_RETURN_DAYS = 5;

    /** 需要跟踪 IC 的因子列表 */
    private static final List<String> TRACKED_FACTORS = List.of(
            "MOM20", "VOL20", "RSI14", "MACD_DIF",
            "VAL_PE_TTM", "VAL_PB", "VAL_DIVIDEND_YIELD",
            "QUAL_EARNINGS", "QUAL_HEALTH", "QUAL_REVENUE_STABILITY"
    );

    public FactorIcService(FactorIcRecordMapper icRecordMapper, ClickHouseStockService clickHouseStockService) {
        this.icRecordMapper = icRecordMapper;
        this.clickHouseStockService = clickHouseStockService;
    }

    /**
     * 计算并保存所有跟踪因子的 IC/IR
     *
     * @param date 截面日期（null 则使用最新交易日）
     */
    public Map<String, FactorIcRecord> computeAndSaveIc(LocalDate date) {
        if (date == null) {
            date = clickHouseStockService.getLatestTradeDate();
        }
        if (date == null) {
            log.warn("[FactorIC] 无法获取最新交易日");
            return Collections.emptyMap();
        }

        // 需要获取: 截面日期的因子值 + 未来N天收益率
        // 通过 CH 直接计算 Spearman Rank Correlation

        Map<String, FactorIcRecord> results = new LinkedHashMap<>();
        LocalDate forwardDate = date.plusDays(FORWARD_RETURN_DAYS);

        for (String factorCode : TRACKED_FACTORS) {
            try {
                FactorIcRecord record = computeSingleFactorIc(factorCode, date, forwardDate);
                if (record != null) {
                    // 计算滚动均值/IR
                    computeRollingStats(record);
                    // 保存
                    icRecordMapper.insert(record);
                    results.put(factorCode, record);
                    log.debug("[FactorIC] {} date={} IC={} IR_20d={}",
                            factorCode, date, String.format("%.4f", record.getIcValue()),
                            record.getIr20d() != null ? String.format("%.3f", record.getIr20d()) : "N/A");
                }
            } catch (Exception e) {
                log.warn("[FactorIC] 计算失败: factor={} date={} error={}", factorCode, date, e.getMessage());
            }
        }

        log.info("[FactorIC] 完成: date={} factors={}", date, results.size());
        return results;
    }

    /**
     * 获取因子自适应权重（基于IC历史）
     *
     * 规则：
     * - IR_20d > 0.5: 权重 1.2 (因子表现好，加仓)
     * - IR_20d > 0.2: 权重 1.0 (正常)
     * - IR_20d > -0.2: 权重 0.8 (表现一般，减仓)
     * - IR_20d <= -0.2: 权重 0.5 (因子失效，大幅降权)
     *
     * @return factorCode → adaptiveWeight
     */
    public Map<String, Double> getAdaptiveWeights() {
        Map<String, Double> weights = new HashMap<>();
        for (String factorCode : TRACKED_FACTORS) {
            FactorIcRecord latest = icRecordMapper.findLatest(factorCode);
            double weight = 1.0;
            if (latest != null && latest.getIr20d() != null) {
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
        return icRecordMapper.findAllLatest();
    }

    // ── 私有方法 ──

    /**
     * 计算单个因子的 IC (Spearman Rank Correlation)
     *
     * 通过 CH 查询: 因子值排名 vs 未来N日收益率排名的相关系数
     */
    private FactorIcRecord computeSingleFactorIc(String factorCode, LocalDate date, LocalDate forwardDate) {
        // CH SQL: 获取截面日期的因子值排名和未来收益率排名，计算相关系数
        // 使用 Spearman = Pearson(rank_x, rank_y)

        String sql = String.format("""
            SELECT corr(rankFactor, rankReturn) as ic_value, count() as stock_count FROM (
                SELECT
                    symbol,
                    rankRowNumber(factor_value) as rankFactor,
                    rankRowNumber(fwd_return) as rankReturn
                FROM (
                    SELECT
                        f.symbol,
                        f.factor_value,
                        (d2.close_price - d1.close_price) / d1.close_price * 100 as fwd_return
                    FROM stock_db.factor_value f
                    INNER JOIN stock_db.stock_daily d1 ON f.symbol = d1.code AND d1.trade_date = '%s'
                    INNER JOIN stock_db.stock_daily d2 ON f.symbol = d2.code AND d2.trade_date = '%s'
                    WHERE f.factor_code = '%s'
                      AND f.trade_date = '%s'
                      AND d1.close_price > 0
                      AND d2.close_price > 0
                )
            )
            """, date, forwardDate, factorCode, date);

        // 注意: CH 的 corr 函数是 Pearson，用在排名上就是 Spearman
        // rankRowNumber 是 CH 窗口函数

        try {
            Map<String, Object> row = clickHouseStockService.queryForList(sql).stream().findFirst().orElse(null);
            if (row == null || row.get("ic_value") == null) return null;

            FactorIcRecord record = new FactorIcRecord();
            record.setFactorCode(factorCode);
            record.setTradeDate(date);
            record.setIcValue(toDouble(row.get("ic_value")));
            record.setStockCount(toInt(row.get("stock_count")));
            return record;
        } catch (Exception e) {
            log.debug("[FactorIC] CH查询失败: factor={} error={}", factorCode, e.getMessage());
            return null;
        }
    }

    /**
     * 计算滚动均值和IR
     */
    private void computeRollingStats(FactorIcRecord record) {
        if (record.getIcValue() == null) return;

        // 20日滚动
        List<Double> ic20 = icRecordMapper.findRecentIcValues(record.getFactorCode(), record.getTradeDate(), 19);
        ic20.add(0, record.getIcValue()); // 加上当天

        if (ic20.size() >= 10) {
            double avg20 = avg(ic20);
            double std20 = std(ic20, avg20);
            record.setIc20dAvg(round(avg20, 4));
            record.setIr20d(std20 > 0 ? round(avg20 / std20, 3) : null);
        }

        // 60日滚动
        List<Double> ic60 = icRecordMapper.findRecentIcValues(record.getFactorCode(), record.getTradeDate(), 59);
        ic60.add(0, record.getIcValue());

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
