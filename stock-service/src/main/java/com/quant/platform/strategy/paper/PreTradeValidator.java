package com.quant.platform.strategy.paper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 交易前风控检查层
 * 买入前检查行业集中度 / 单股仓位 / 最大回撤 / 相关性集中，可阻断交易（autoBlockEnabled）。
 * 数据访问委托 AlertDataLoader，净值走 MySQL(paper_nav)，相关性行情走 ClickHouse，
 * 皮尔逊相关系数委托 PositionRiskChecker。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PreTradeValidator {

    private final AlertDataLoader alertData;
    private final JdbcTemplate jdbcTemplate;
    private final PositionRiskChecker riskChecker;

    @Autowired(required = false)
    @Qualifier("clickHouseJdbcTemplate")
    private JdbcTemplate clickHouseJdbcTemplate;

    // ─── 数据访问委托（AlertDataLoader） ───────────────────────────────
    private PaperRiskConfig getRiskConfig(Long paperId) { return alertData.getRiskConfig(paperId); }
    private BigDecimal getTotalAssets(Long paperId) { return alertData.getTotalAssets(paperId); }
    private String getStockIndustry(String code) { return alertData.getStockIndustry(code); }
    private BigDecimal getIndustryMarketValue(Long paperId, String industry) { return alertData.getIndustryMarketValue(paperId, industry); }
    private BigDecimal getStockMarketValue(Long paperId, String code) { return alertData.getStockMarketValue(paperId, code); }
    private List<PaperPosition> getPositions(Long paperId) { return alertData.getPositions(paperId); }

    // ─── 风控计算委托（PositionRiskChecker） ───────────────────────────
    private double calcPearsonCorrelation(double[] x, double[] y) { return riskChecker.calcPearsonCorrelation(x, y); }

    // ─── 内部方法 ──────────────────────────────────────────────────────



    /**
     * 买入前风控检查（可阻断交易）
     * 检查行业集中度、单股仓位、最大回撤，若超限且autoBlockEnabled=true则阻断
     */
    public RiskCheckResult checkBeforeTrade(Long paperId, String code, BigDecimal plannedAmount) {
        PaperRiskConfig cfg = getRiskConfig(paperId);
        if (cfg == null) return RiskCheckResult.pass();

        // 若未开启自动阻断，仅记录预警不阻断
        boolean autoBlock = cfg.getAutoBlockEnabled() != null && cfg.getAutoBlockEnabled() == 1;

        BigDecimal totalAssets = getTotalAssets(paperId);
        if (totalAssets == null || totalAssets.compareTo(BigDecimal.ZERO) <= 0) {
            return RiskCheckResult.pass();
        }

        // 1. 行业集中度检查
        if (cfg.getMaxIndustryPct() != null) {
            String industry = getStockIndustry(code);
            if (industry != null) {
                BigDecimal industryValue = getIndustryMarketValue(paperId, industry);
                // 加上本次计划买入金额
                industryValue = industryValue.add(plannedAmount);
                double ratio = industryValue.divide(totalAssets, 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100")).doubleValue();
                double limit = cfg.getMaxIndustryPct().doubleValue() * 100;
                if (ratio > limit) {
                    String msg = String.format("行业集中度超限：%s 占比 %.1f%% > %.1f%%", industry, ratio, limit);
                    if (autoBlock) {
                        return RiskCheckResult.blocked(msg);
                    }
                    log.warn("行业集中度预警(不阻断): {}", msg);
                }
            }
        }

        // 2. 单股仓位检查
        if (cfg.getMaxPositionPct() != null) {
            // 查当前该股市值
            BigDecimal currentValue = getStockMarketValue(paperId, code);
            // 加上本次计划买入金额
            BigDecimal newValue = currentValue.add(plannedAmount);
            double ratio = newValue.divide(totalAssets, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100")).doubleValue();
            double limit = cfg.getMaxPositionPct().doubleValue() * 100;
            if (ratio > limit) {
                String msg = String.format("单股仓位超限：%s 占比 %.1f%% > %.1f%%", code, ratio, limit);
                if (autoBlock) {
                    return RiskCheckResult.blocked(msg);
                }
                log.warn("单股仓位预警(不阻断): {}", msg);
            }
        }

        // 3. 最大回撤检查
        if (cfg.getMaxDrawdownPct() != null) {
            double maxDd = cfg.getMaxDrawdownPct().doubleValue();
            try {
                List<BigDecimal> navs = jdbcTemplate.query(
                    "SELECT total_assets FROM paper_nav WHERE paper_id = ? ORDER BY nav_date ASC",
                    (rs, rowNum) -> rs.getBigDecimal("total_assets"), paperId);

                if (!navs.isEmpty()) {
                    BigDecimal peak = navs.stream()
                        .filter(Objects::nonNull)
                        .reduce(BigDecimal.ZERO, (a, b) -> a.compareTo(b) > 0 ? a : b);
                    BigDecimal current = navs.get(navs.size() - 1);
                    if (peak.compareTo(BigDecimal.ZERO) > 0) {
                        double drawdownPct = peak.subtract(current)
                            .divide(peak, 4, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("100")).doubleValue();
                        if (drawdownPct > maxDd * 100) {
                            String msg = String.format("最大回撤超限：当前回撤 %.1f%% > %.1f%%", drawdownPct, maxDd * 100);
                            if (autoBlock) {
                                return RiskCheckResult.blocked(msg);
                            }
                            log.warn("最大回撤预警(不阻断): {}", msg);
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("回撤检查失败: {}", e.getMessage());
            }
        }

        // 4. 相关性集中度检查（交易前阻断版）
        // 检查目标股票与现有持仓的相关系数，若高相关+合计仓位超限则阻断
        if (clickHouseJdbcTemplate != null) {
            try {
                List<PaperPosition> positions = getPositions(paperId);
                if (positions.size() >= 1) {
                    BigDecimal plannedMv = plannedAmount;
                    // 构建包含目标股票的代码列表
                    List<String> existingCodes = positions.stream()
                        .map(PaperPosition::getCode)
                        .distinct()
                        .filter(c -> !c.equals(code))
                        .toList();

                    if (!existingCodes.isEmpty()) {
                        // 从 CH 获取目标股票和现有持仓的近60日收盘价
                        List<String> allCodes = new ArrayList<>();
                        allCodes.add(code);
                        allCodes.addAll(existingCodes);
                        allCodes = allCodes.stream().distinct().toList();

                        String placeholders = String.join(",", Collections.nCopies(allCodes.size(), "?"));
                        String priceSql =
                            "SELECT code, trade_date, close_price " +
                            "FROM stock.stock_daily " +
                            "WHERE code IN (" + placeholders + ") " +
                            "  AND trade_date >= ? " +
                            "ORDER BY code, trade_date ";

                        Object[] priceParams = new Object[allCodes.size() + 1];
                        for (int i = 0; i < allCodes.size(); i++) {
                            priceParams[i] = allCodes.get(i);
                        }
                        priceParams[allCodes.size()] = LocalDate.now().minusDays(90);

                        List<Map<String, Object>> priceRows = clickHouseJdbcTemplate.query(
                            priceSql, priceParams,
                            (rs, rowNum) -> {
                                Map<String, Object> m = new HashMap<>();
                                m.put("code", rs.getString("code"));
                                m.put("close", rs.getDouble("close_price"));
                                return m;
                            });

                        // 构建代码 -> 收盘价列表
                        Map<String, List<Double>> priceMap = new HashMap<>();
                        for (Map<String, Object> row : priceRows) {
                            String c = (String) row.get("code");
                            priceMap.computeIfAbsent(c, k -> new ArrayList<>()).add((Double) row.get("close"));
                        }

                        // 目标股票至少需要30个数据点
                        List<Double> targetPrices = priceMap.get(code);
                        if (targetPrices != null && targetPrices.size() >= 30) {
                            double[] targetReturns = new double[targetPrices.size() - 1];
                            for (int i = 1; i < targetPrices.size(); i++) {
                                targetReturns[i - 1] = targetPrices.get(i - 1) > 0
                                    ? (targetPrices.get(i) - targetPrices.get(i - 1)) / targetPrices.get(i - 1)
                                    : 0;
                            }

                            // 持仓市值映射
                            Map<String, BigDecimal> mvMap = new HashMap<>();
                            for (PaperPosition pos : positions) {
                                if (pos.getMarketValue() != null) {
                                    mvMap.merge(pos.getCode(), pos.getMarketValue(), BigDecimal::add);
                                }
                            }

                            double CORR_THRESHOLD = 0.70;
                            double POS_THRESHOLD = 0.40;

                            for (String existingCode : existingCodes) {
                                List<Double> existPrices = priceMap.get(existingCode);
                                if (existPrices == null || existPrices.size() < 30) continue;

                                // 对齐长度
                                int minLen = Math.min(targetReturns.length, existPrices.size() - 1);
                                if (minLen < 20) continue;

                                double[] existReturns = new double[existPrices.size() - 1];
                                for (int i = 1; i < existPrices.size(); i++) {
                                    existReturns[i - 1] = existPrices.get(i - 1) > 0
                                        ? (existPrices.get(i) - existPrices.get(i - 1)) / existPrices.get(i - 1)
                                        : 0;
                                }

                                // 截取相同长度计算相关系数
                                double[] x = Arrays.copyOf(targetReturns, minLen);
                                double[] y = Arrays.copyOf(existReturns, minLen);
                                double corr = calcPearsonCorrelation(x, y);

                                if (corr > CORR_THRESHOLD) {
                                    BigDecimal existMv = mvMap.getOrDefault(existingCode, BigDecimal.ZERO);
                                    // 合计仓位 = 现有持仓市值 + 计划买入金额
                                    double combinedPct = existMv.add(plannedMv)
                                        .divide(totalAssets, 4, RoundingMode.HALF_UP)
                                        .doubleValue();

                                    if (combinedPct > POS_THRESHOLD) {
                                        String msg = String.format(
                                            "相关性集中度超限：%s 与持仓 %s 相关系数 %.2f > %.2f，合计仓位 %.1f%% > %.1f%%",
                                            code, existingCode, corr, CORR_THRESHOLD,
                                            combinedPct * 100, POS_THRESHOLD * 100);
                                        if (autoBlock) {
                                            return RiskCheckResult.blocked(msg);
                                        }
                                        log.warn("相关性集中度预警(不阻断): {}", msg);
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("交易前相关性检查失败: {}", e.getMessage());
            }
        }

        return RiskCheckResult.pass();
    }
}
