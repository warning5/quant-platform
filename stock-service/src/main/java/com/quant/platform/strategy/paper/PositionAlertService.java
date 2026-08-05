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
 * 持仓预警服务
 * 扫描持仓股票，检测均线跌破、单日大跌、重大公告、研报变化
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PositionAlertService {

    private final JdbcTemplate jdbcTemplate;
    private final AlertDataLoader alertData;
    private final PositionAlertScanner alertScanner;

    @Autowired(required = false)
    @Qualifier("clickHouseJdbcTemplate")
    private JdbcTemplate clickHouseJdbcTemplate;

    // ─── 数据访问委托（AlertDataLoader） ───────────────────────────────
    private List<PaperPosition> getPositions(Long paperId) { return alertData.getPositions(paperId); }
    private PaperRiskConfig getRiskConfig(Long paperId) { return alertData.getRiskConfig(paperId); }
    private BigDecimal getTotalAssets(Long paperId) { return alertData.getTotalAssets(paperId); }
    private String getStockIndustry(String code) { return alertData.getStockIndustry(code); }
    private BigDecimal getIndustryMarketValue(Long paperId, String industry) { return alertData.getIndustryMarketValue(paperId, industry); }
    private BigDecimal getStockMarketValue(Long paperId, String code) { return alertData.getStockMarketValue(paperId, code); }
    private void saveAlert(PositionAlert alert) { alertData.saveAlert(alert); }

    // ─── CRUD 委托 ───────────────────────────────────────────────────
    public List<PositionAlert> getAlerts(Long paperId, int limit) { return alertData.getAlerts(paperId, limit); }
    public long getUnreadCount(Long paperId) { return alertData.getUnreadCount(paperId); }
    public int markAllRead(Long paperId) { return alertData.markAllRead(paperId); }
    public void markRead(Long alertId) { alertData.markRead(alertId); }
    public void deleteAlert(Long alertId) { alertData.deleteAlert(alertId); }
    public int clearAlerts(Long paperId) { return alertData.clearAlerts(paperId); }

    // ─── 持仓扫描委托（PositionAlertScanner） ─────────────────────────
    private int checkMaBreak(Long paperId, PaperPosition pos, LocalDate today) { return alertScanner.checkMaBreak(paperId, pos, today); }
    private int checkBigDrop(Long paperId, PaperPosition pos, LocalDate today) { return alertScanner.checkBigDrop(paperId, pos, today); }
    private int checkImportantNotices(Long paperId, PaperPosition pos, LocalDate today) { return alertScanner.checkImportantNotices(paperId, pos, today); }
    private int checkResearchReports(Long paperId, PaperPosition pos, LocalDate today) { return alertScanner.checkResearchReports(paperId, pos, today); }

    /**
     * 扫描模拟盘持仓，生成预警
     */
    public int scanAlerts(Long paperId) {
        List<PaperPosition> positions = getPositions(paperId);
        if (positions.isEmpty()) {
            log.info("模拟盘 {} 无持仓，跳过预警扫描", paperId);
            return 0;
        }

        LocalDate today = LocalDate.now();
        int alertCount = 0;

        for (PaperPosition pos : positions) {
            // 1. 均线跌破检测
            alertCount += checkMaBreak(paperId, pos, today);

            // 2. 大跌检测
            alertCount += checkBigDrop(paperId, pos, today);

            // 3. 重大公告检测
            alertCount += checkImportantNotices(paperId, pos, today);

            // 4. 研报变化检测
            alertCount += checkResearchReports(paperId, pos, today);
        }

        // 5. 集中度/行业/回撤/相关性风控检测（每模拟盘一次）
        alertCount += checkRiskConcentration(paperId, positions, today);
        alertCount += checkRiskIndustry(paperId, positions, today);
        alertCount += checkRiskCorrelation(paperId, positions, today);
        alertCount += checkRiskDrawdown(paperId, today);

        // 6. 事件驱动预警（定增/解禁/股权激励/业绩预告）
        alertCount += checkEventDrivenAlerts(paperId, positions, today);

        log.info("模拟盘 {} 预警扫描完成，生成 {} 条预警", paperId, alertCount);
        return alertCount;
    }


    // ─── 风控检测 ───────────────────────────────────────────────────

    /**
     * 单股集中度检测
     * 单股市值 / 总资产 > maxPositionPct → WARNING
     */
    private int checkRiskConcentration(Long paperId, List<PaperPosition> positions, LocalDate today) {
        PaperRiskConfig cfg = getRiskConfig(paperId);
        if (cfg == null || cfg.getMaxPositionPct() == null) return 0;

        double maxPct = cfg.getMaxPositionPct().doubleValue(); // 0.20 表示 20%
        BigDecimal totalAssets = getTotalAssets(paperId);
        if (totalAssets == null || totalAssets.compareTo(BigDecimal.ZERO) <= 0) return 0;

        int count = 0;
        for (PaperPosition pos : positions) {
            if (pos.getMarketValue() == null) continue;
            double ratio = pos.getMarketValue().divide(totalAssets, 4, java.math.RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100")).doubleValue(); // 转为百分比，如 15.5 表示 15.5%
            if (ratio > maxPct * 100) { // maxPct=0.20 → 比较 15.5 > 20
                saveAlert(PositionAlert.builder()
                    .paperId(paperId).code(pos.getCode()).name(pos.getName())
                    .alertType("RISK_CONCENTRATION").alertLevel("WARNING")
                    .title(String.format("持仓集中度预警：%s %s 占比 %.1f%% > %.1f%%",
                        pos.getCode(), pos.getName(), ratio, maxPct * 100))
                    .detail(String.format("单股持仓 %.2f 元 / 总资产 %.2f 元 = %.1f%%（上限 %.1f%%）",
                        pos.getMarketValue(), totalAssets, ratio, maxPct * 100))
                    .alertDate(today).isRead(false).build());
                count++;
            }
        }
        return count;
    }

    /**
     * 行业暴露度检测
     * 单一行业市值 / 总资产 > maxIndustryPct → WARNING
     */
    private int checkRiskIndustry(Long paperId, List<PaperPosition> positions, LocalDate today) {
        PaperRiskConfig cfg = getRiskConfig(paperId);
        if (cfg == null || cfg.getMaxIndustryPct() == null) return 0;

        BigDecimal maxPct = cfg.getMaxIndustryPct();
        BigDecimal totalAssets = getTotalAssets(paperId);
        if (totalAssets == null || totalAssets.compareTo(BigDecimal.ZERO) <= 0) return 0;

        // 按行业聚合市值
        Map<String, BigDecimal> industryValue = new HashMap<>();
        for (PaperPosition pos : positions) {
            if (pos.getMarketValue() == null) continue;
            String industry = getStockIndustry(pos.getCode());
            if (industry == null) industry = "未知";
            industryValue.merge(industry, pos.getMarketValue(), BigDecimal::add);
        }

        log.info("[行业暴露检测] paperId={}, totalAssets={}, maxPct={}, 行业数量={}", 
            paperId, totalAssets, maxPct, industryValue.size());
        for (Map.Entry<String, BigDecimal> e : industryValue.entrySet()) {
            double ratio = e.getValue().divide(totalAssets, 4, java.math.RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100")).doubleValue();
            log.info("  行业: {}, 市值: {}, 占比: {}%, 阈值: {}%, 触发: {}", 
                e.getKey(), e.getValue(), String.format("%.2f", ratio), 
                maxPct.doubleValue() * 100, ratio > maxPct.doubleValue() * 100);
        }
        
        int count = 0;
        for (Map.Entry<String, BigDecimal> e : industryValue.entrySet()) {
            double ratio = e.getValue().divide(totalAssets, 4, java.math.RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100")).doubleValue();
            if (ratio > maxPct.doubleValue() * 100) {
                log.info("[行业暴露预警] 触发: {} 占比 {}%", e.getKey(), String.format("%.2f", ratio));
                saveAlert(PositionAlert.builder()
                    .paperId(paperId).code(null).name(e.getKey())
                    .alertType("RISK_INDUSTRY").alertLevel("WARNING")
                    .title(String.format("行业暴露预警：%s 占比 %.1f%% > %.1f%%",
                        e.getKey(), ratio, maxPct.doubleValue() * 100))
                    .detail(String.format("行业 %s 持仓 %.2f 元 / 总资产 %.2f 元 = %.1f%%（上限 %.1f%%）",
                        e.getKey(), e.getValue(), totalAssets, ratio, maxPct.doubleValue() * 100))
                    .alertDate(today).isRead(false).build());
                count++;
            }
        }
        return count;
    }

    /**
     * 最大回撤检测
     * 从峰值回撤 > maxDrawdownPct → CRITICAL
     */
    private int checkRiskDrawdown(Long paperId, LocalDate today) {
        PaperRiskConfig cfg = getRiskConfig(paperId);
        if (cfg == null || cfg.getMaxDrawdownPct() == null) return 0;

        double maxDd = cfg.getMaxDrawdownPct().doubleValue(); // 0.15 表示 15%

        // 从 paper_nav 取历史净值峰值
        try {
            List<Map<String, Object>> navs = jdbcTemplate.query(
                "SELECT nav_date, total_assets FROM paper_nav WHERE paper_id = ? ORDER BY nav_date ASC",
                (rs, rowNum) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("date", rs.getDate("nav_date").toLocalDate());
                    m.put("assets", rs.getBigDecimal("total_assets"));
                    return m;
                }, paperId);

            if (navs.isEmpty()) return 0;

            BigDecimal peak = navs.stream()
                .map(m -> (BigDecimal) m.get("assets"))
                .reduce(BigDecimal.ZERO, (a, b) -> a.compareTo(b) > 0 ? a : b);

            BigDecimal current = navs.getLast() != null
                ? (BigDecimal) navs.get(navs.size() - 1).get("assets") : null;
            if (current == null || peak.compareTo(BigDecimal.ZERO) <= 0) return 0;

            double drawdownPct = peak.subtract(current)
                .divide(peak, 4, java.math.RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100")).doubleValue();

            if (drawdownPct > maxDd * 100) {
                saveAlert(PositionAlert.builder()
                    .paperId(paperId).code(null).name("组合")
                    .alertType("RISK_DRAWDOWN").alertLevel("CRITICAL")
                    .title(String.format("最大回撤预警：%.1f%% > %.1f%%", drawdownPct, maxDd * 100))
                    .detail(String.format("当前资产 %.2f，峰值 %.2f，回撤 %.1f%%（阈值 %.1f%%）",
                        current, peak, drawdownPct, maxDd * 100))
                    .alertDate(today).isRead(false).build());
                return 1;
            }
        } catch (Exception e) {
            log.debug("回撤检测失败: paperId={}, error={}", paperId, e.getMessage());
        }
        return 0;
    }

    /**
     * 相关性集中度检测（幸存者偏差修复：隐性集中风险）
     * 计算持仓股票近60日收益率相关系数矩阵，
     * 若任意两只持仓相关系数 > 0.7 且合计仓位 > 40%，则触发 WARNING。
     */
    private int checkRiskCorrelation(Long paperId, List<PaperPosition> positions, LocalDate today) {
        if (clickHouseJdbcTemplate == null || positions.size() < 2) return 0;

        BigDecimal totalAssets = getTotalAssets(paperId);
        if (totalAssets == null || totalAssets.compareTo(BigDecimal.ZERO) <= 0) return 0;

        // code -> name 映射
        Map<String, String> nameMap = positions.stream()
                .collect(Collectors.toMap(PaperPosition::getCode, p -> p.getName() != null ? p.getName() : p.getCode()));

        // 提取持仓代码
        List<String> codes = positions.stream()
            .map(PaperPosition::getCode)
            .distinct()
            .toList();
        if (codes.size() < 2) return 0;

        // 从 ClickHouse 批量获取近60日收盘价
            // 修复 SQL 注入：使用 ? 占位符，不拼接 code 列表
            String placeholders = String.join(",", java.util.Collections.nCopies(codes.size(), "?"));
            String sql =
                "SELECT code, trade_date, close_price " +
                "FROM stock.stock_daily " +
                "WHERE code IN (" + placeholders + ") " +
                "  AND trade_date >= ? " +
                "ORDER BY code, trade_date ";

            // 构建参数数组
            Object[] params = new Object[codes.size() + 1];
            for (int i = 0; i < codes.size(); i++) {
                params[i] = codes.get(i);
            }
            params[codes.size()] = today.minusDays(90);

        try {
            List<Map<String, Object>> rows = clickHouseJdbcTemplate.query(
                sql, params,
                (rs, rowNum) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("code", rs.getString("code"));
                    m.put("date", rs.getString("trade_date"));
                    m.put("close", rs.getDouble("close_price"));
                    return m;
                });

            // 构建 code -> List<Double> closePrices
            Map<String, List<Double>> priceMap = new HashMap<>();
            for (Map<String, Object> row : rows) {
                String code = (String) row.get("code");
                priceMap.computeIfAbsent(code, k -> new ArrayList<>()).add((Double) row.get("close"));
            }

            // 只保留至少有30个价格点的股票（约30个交易日）
            List<String> validCodes = priceMap.entrySet().stream()
                .filter(e -> e.getValue().size() >= 30)
                .map(Map.Entry::getKey)
                .toList();
            if (validCodes.size() < 2) return 0;

            // 计算日收益率
            Map<String, double[]> returnsMap = new HashMap<>();
            for (String code : validCodes) {
                List<Double> prices = priceMap.get(code);
                double[] rets = new double[prices.size() - 1];
                for (int i = 1; i < prices.size(); i++) {
                    rets[i - 1] = prices.get(i - 1) > 0
                        ? (prices.get(i) - prices.get(i - 1)) / prices.get(i - 1)
                        : 0;
                }
                returnsMap.put(code, rets);
            }

            // 获取仓位映射
            Map<String, BigDecimal> marketValueMap = new HashMap<>();
            for (PaperPosition pos : positions) {
                if (pos.getMarketValue() != null) {
                    marketValueMap.merge(pos.getCode(), pos.getMarketValue(), BigDecimal::add);
                }
            }

            // 计算相关系数矩阵，检查高相关+高仓位的组合
            int alertCount = 0;
            double CORR_THRESHOLD = 0.70;
            double POS_THRESHOLD = 0.40;
            for (int i = 0; i < validCodes.size(); i++) {
                for (int j = i + 1; j < validCodes.size(); j++) {
                    String codeA = validCodes.get(i);
                    String codeB = validCodes.get(j);
                    double corr = calcPearsonCorrelation(returnsMap.get(codeA), returnsMap.get(codeB));
                    if (corr > CORR_THRESHOLD) {
                        BigDecimal mvA = marketValueMap.getOrDefault(codeA, BigDecimal.ZERO);
                        BigDecimal mvB = marketValueMap.getOrDefault(codeB, BigDecimal.ZERO);
                        double combinedPct = mvA.add(mvB).divide(totalAssets, 4, java.math.RoundingMode.HALF_UP)
                            .doubleValue();
                        if (combinedPct > POS_THRESHOLD) {
                            String nameA = nameMap.getOrDefault(codeA, codeA);
                            String nameB = nameMap.getOrDefault(codeB, codeB);
                            saveAlert(PositionAlert.builder()
                                .paperId(paperId).code(codeA).name(nameA + "+" + nameB)
                                .alertType("RISK_CORRELATION").alertLevel("WARNING")
                                .title(String.format("相关性集中预警：%s+%s 相关系数%.2f 合计仓位%.1f%%",
                                    nameA, nameB, corr, combinedPct * 100))
                                .detail(String.format("%s(%s) + %s(%s) 近60日相关系数%.2f，合计市值%.2f/总资产%.2f = %.1f%%（阈值%.1f%%）",
                                    nameA, codeA, nameB, codeB, corr,
                                    mvA.add(mvB), totalAssets, combinedPct * 100, POS_THRESHOLD * 100))
                                .alertDate(today).isRead(false).build());
                            alertCount++;
                            log.info("[相关性预警] paperId={} {}+{} corr={:.2f} combined={:.1f}%",
                                paperId, codeA, codeB, corr, combinedPct * 100);
                        }
                    }
                }
            }
            return alertCount;
        } catch (Exception e) {
            log.warn("相关性检测失败: paperId={}, error={}", paperId, e.getMessage());
            return 0;
        }
    }

    /**
     * 计算皮尔逊相关系数
     */
    private double calcPearsonCorrelation(double[] x, double[] y) {
        if (x.length != y.length || x.length < 2) return 0;
        int n = x.length;
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0, sumY2 = 0;
        for (int i = 0; i < n; i++) {
            sumX += x[i];
            sumY += y[i];
            sumXY += x[i] * y[i];
            sumX2 += x[i] * x[i];
            sumY2 += y[i] * y[i];
        }
        double numerator = n * sumXY - sumX * sumY;
        double denominator = Math.sqrt((n * sumX2 - sumX * sumX) * (n * sumY2 - sumY * sumY));
        return denominator > 1e-10 ? numerator / denominator : 0;
    }

    /**
     * 事件驱动预警
     * 定增 / 解禁 / 股权激励 / 业绩预告（近7天）
     */
    private int checkEventDrivenAlerts(Long paperId, List<PaperPosition> positions, LocalDate today) {
        if (clickHouseJdbcTemplate == null) return 0;
        int count = 0;

        for (PaperPosition pos : positions) {
            try {
                // 修复 SQL 注入：使用 ? 占位符
                    String sql = """
                    SELECT notice_type, notice_date, title
                    FROM stock.stock_sentiment_notice FINAL
                    WHERE code = ?
                      AND notice_date >= ?
                      AND notice_type IN (?, ?, ?, ?, ?)
                    ORDER BY notice_date DESC
                    LIMIT 3
                    """;

                List<Map<String, Object>> rows = clickHouseJdbcTemplate.query(
                    sql,
                    new Object[]{pos.getCode(), today.minusDays(7),
                                 "定增", "解禁", "股权激励", "业绩预告", "业绩快报"},
                    (rs, rowNum) -> {
                        Map<String, Object> m = new HashMap<>();
                        m.put("type", rs.getString("notice_type"));
                        m.put("date", rs.getString("notice_date"));
                        m.put("title", rs.getString("title"));
                        return m;
                    });

                for (Map<String, Object> row : rows) {
                    String type = (String) row.get("type");
                    String title = (String) row.get("title");
                    LocalDate noticeDate = LocalDate.parse(row.get("date").toString());

                    String alertType = switch (type) {
                        case "定增" -> "EVENT_INCREASE";
                        case "解禁" -> "EVENT_UNLOCK";
                        case "股权激励" -> "EVENT_INCENTIVE";
                        case "业绩预告" -> "EVENT_FORECAST";
                        case "业绩快报" -> "EVENT_EXPRESS";
                        default -> "EVENT_" + type;
                    };
                    String level = ("股权激励".equals(type) || "业绩预告".equals(type) || "业绩快报".equals(type)) ? "INFO" : "WARNING";

                    saveAlert(PositionAlert.builder()
                        .paperId(paperId)
                        .code(pos.getCode())
                        .name(pos.getName())
                        .alertType(alertType)
                        .alertLevel(level)
                        .title(String.format("%s %s: %s", noticeDate, type,
                            title != null ? title.substring(0, Math.min(title.length(), 30)) : ""))
                        .detail(String.format("事件类型: %s，日期: %s", type, noticeDate))
                        .alertDate(noticeDate)
                        .isRead(false)
                        .build());
                    count++;
                }
            } catch (Exception e) {
                log.debug("事件驱动检测失败: code={}, error={}", pos.getCode(), e.getMessage());
            }
        }
        return count;
    }

    // ─── 交易前风控检查 ─────────────────────────────────────────────

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

    // ─── 辅助方法 ───────────────────────────────────────────────────
}
