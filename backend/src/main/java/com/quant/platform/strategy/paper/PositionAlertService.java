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

    private final PositionAlertMapper positionAlertMapper;
    private final PaperRiskConfigMapper paperRiskConfigMapper;
    private final JdbcTemplate jdbcTemplate;

    @Autowired(required = false)
    @Qualifier("clickHouseJdbcTemplate")
    private JdbcTemplate clickHouseJdbcTemplate;

    /** 需要关注的公告类型 */
    private static final String[] IMPORTANT_NOTICE_TYPES = {
        "业绩预告", "业绩快报", "终止上市风险提示", "实施退市风险警示",
        "股票交易异常波动", "停牌公告", "违法违规", "处罚",
        "股东/实际控制人股份减持", "重大事故损失", "诉讼仲裁",
        "其他增发事项公告", "股份质押、冻结",
    };

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

    /**
     * 查询模拟盘的预警列表
     */
    public List<PositionAlert> getAlerts(Long paperId, int limit) {
        return positionAlertMapper.selectList(
            new LambdaQueryWrapper<PositionAlert>()
                .eq(PositionAlert::getPaperId, paperId)
                .orderByDesc(PositionAlert::getAlertDate)
                .orderByDesc(PositionAlert::getId)
                .last("LIMIT " + Math.min(limit, 200)));
    }

    /**
     * 获取未读预警数量
     */
    public long getUnreadCount(Long paperId) {
        return positionAlertMapper.selectCount(
            new LambdaQueryWrapper<PositionAlert>()
                .eq(PositionAlert::getPaperId, paperId)
                .eq(PositionAlert::getIsRead, false));
    }

    /**
     * 标记所有预警为已读
     */
    public int markAllRead(Long paperId) {
        List<PositionAlert> unread = positionAlertMapper.selectList(
            new LambdaQueryWrapper<PositionAlert>()
                .eq(PositionAlert::getPaperId, paperId)
                .eq(PositionAlert::getIsRead, false));
        for (PositionAlert a : unread) {
            a.setIsRead(true);
            positionAlertMapper.updateById(a);
        }
        return unread.size();
    }

    /**
     * 标记单条预警为已读
     */
    public void markRead(Long alertId) {
        PositionAlert alert = positionAlertMapper.selectById(alertId);
        if (alert != null && !alert.getIsRead()) {
            alert.setIsRead(true);
            positionAlertMapper.updateById(alert);
        }
    }

    /**
     * 删除单条预警
     */
    public void deleteAlert(Long alertId) {
        positionAlertMapper.deleteById(alertId);
    }

    /**
     * 清空模拟盘所有预警
     */
    public int clearAlerts(Long paperId) {
        return positionAlertMapper.delete(
            new LambdaQueryWrapper<PositionAlert>()
                .eq(PositionAlert::getPaperId, paperId));
    }

    // ─── 内部方法 ──────────────────────────────────────────────────────

    private List<PaperPosition> getPositions(Long paperId) {
        // 直接用 JdbcTemplate 查，避免循环依赖
        return jdbcTemplate.query(
            "SELECT id, paper_id, code, name, shares, cost_price, current_price, market_value, profit_loss_pct, buy_date " +
            "FROM paper_position WHERE paper_id = ?",
            (rs, rowNum) -> PaperPosition.builder()
                .id(rs.getLong("id"))
                .paperId(rs.getLong("paper_id"))
                .code(rs.getString("code"))
                .name(rs.getString("name"))
                .shares(rs.getInt("shares"))
                .costPrice(rs.getBigDecimal("cost_price"))
                .currentPrice(rs.getBigDecimal("current_price"))
                .marketValue(rs.getBigDecimal("market_value"))
                .profitLossPct(rs.getBigDecimal("profit_loss_pct"))
                .buyDate(rs.getDate("buy_date") != null ? rs.getDate("buy_date").toLocalDate() : null)
                .build(),
            paperId);
    }

    /**
     * 均线跌破检测
     * 检查今日收盘价是否跌破 MA20 或 MA60
     * 逻辑：今日收盘 < MA(N)，且昨日收盘 >= MA(N)，即今日破位
     */
    private int checkMaBreak(Long paperId, PaperPosition pos, LocalDate today) {
        if (clickHouseJdbcTemplate == null) return 0;
        int count = 0;

        int[] periods = {20, 60};
        for (int n : periods) {
            try {
                // 取最近 n+2 天的收盘价，确保能算 MA(N) 和判断破位
                // 修复 SQL 注入：使用 ? 占位符，不拼接 pos.getCode()
                String sql = String.format("""
                    SELECT trade_date, close_price,
                           avg(close_price) OVER (ORDER BY trade_date ROWS BETWEEN %d PRECEDING AND CURRENT ROW) as ma
                    FROM (
                        SELECT trade_date, close_price
                        FROM stock.stock_daily FINAL
                        WHERE code = ? AND trade_date <= ?
                        ORDER BY trade_date DESC
                        LIMIT %d
                    ) sub
                    ORDER BY trade_date ASC
                    """, n - 1, n + 5);

                List<Map<String, Object>> rows = clickHouseJdbcTemplate.query(
                    sql, 
                    new Object[]{pos.getCode(), today.toString()},
                    (rs, rowNum) -> {
                        Map<String, Object> m = new HashMap<>();
                        m.put("date", rs.getString("trade_date"));
                        m.put("close", rs.getBigDecimal("close_price"));
                        m.put("ma", rs.getBigDecimal("ma"));
                        return m;
                    });

                if (rows.size() < n + 2) continue;

                // 取最后两天
                Map<String, Object> yesterday = rows.get(rows.size() - 2);
                Map<String, Object> todayRow = rows.getLast();

                BigDecimal todayClose = (BigDecimal) todayRow.get("close");
                BigDecimal todayMa = (BigDecimal) todayRow.get("ma");
                BigDecimal ydClose = (BigDecimal) yesterday.get("close");
                BigDecimal ydMa = (BigDecimal) yesterday.get("ma");

                if (todayClose == null || todayMa == null || ydClose == null || ydMa == null) continue;

                // 今日收盘 < MA(N)，且昨日收盘 >= MA(N)
                boolean todayBelow = todayClose.compareTo(todayMa) < 0;
                boolean ydAbove = ydClose.compareTo(ydMa) >= 0;

                if (todayBelow && ydAbove) {
                    String maLabel = "MA" + n;
                    LocalDate dataDate = LocalDate.parse(todayRow.get("date").toString());
                    saveAlert(PositionAlert.builder()
                        .paperId(paperId)
                        .code(pos.getCode())
                        .name(pos.getName())
                        .alertType("MA_BREAK")
                        .alertLevel(n >= 60 ? "CRITICAL" : "WARNING")
                        .title(String.format("%s %s %s 跌破%s", dataDate, pos.getCode(), pos.getName(), maLabel))
                        .detail(String.format("%s 收盘价 %.2f 跌破 %s（%.2f），前一日 %.2f 在 %s（%.2f）之上",
                            dataDate, todayClose, maLabel, todayMa, ydClose, maLabel, ydMa))
                        .alertDate(dataDate)
                        .isRead(false)
                        .build());
                    count++;
                }
            } catch (Exception e) {
                log.debug("均线跌破检测失败: code={}, period={}, error={}", pos.getCode(), n, e.getMessage());
            }
        }
        return count;
    }

    /**
     * 大跌检测
     * 当日跌幅超过 5% 触发 WARNING，超过 8% 触发 CRITICAL
     */
    private int checkBigDrop(Long paperId, PaperPosition pos, LocalDate today) {
        if (clickHouseJdbcTemplate == null) return 0;

        try {
            // 修复 SQL 注入：使用 ? 占位符
            String sql = """
                SELECT trade_date, close_price, change_percent
                FROM stock.stock_daily FINAL
                WHERE code = ? AND trade_date <= ?
                ORDER BY trade_date DESC
                LIMIT 2
                """;

            List<Map<String, Object>> rows = clickHouseJdbcTemplate.query(
                sql,
                new Object[]{pos.getCode(), today.toString()},
                (rs, rowNum) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("date", rs.getString("trade_date"));
                    m.put("close", rs.getBigDecimal("close_price"));
                    m.put("change", rs.getBigDecimal("change_percent"));
                    return m;
                });

            if (rows.isEmpty()) return 0;
            Map<String, Object> latest = rows.getFirst();
            BigDecimal changePct = (BigDecimal) latest.get("change");
            if (changePct == null) return 0;

            // change_percent 存的是百分比形式（如 -5.23 表示跌 5.23%）
            double drop = changePct.doubleValue();
            if (drop <= -5.0) {
                String level = drop <= -8.0 ? "CRITICAL" : "WARNING";
                LocalDate dataDate = LocalDate.parse(latest.get("date").toString());
                saveAlert(PositionAlert.builder()
                    .paperId(paperId)
                    .code(pos.getCode())
                    .name(pos.getName())
                    .alertType("DROP")
                    .alertLevel(level)
                    .title(String.format("%s %s %s 单日跌幅 %.2f%%", dataDate, pos.getCode(), pos.getName(), Math.abs(drop)))
                    .detail(String.format("日期 %s，收盘价 %.2f，跌幅 %.2f%%，当前持仓 %d 股",
                        dataDate, latest.get("close"), Math.abs(drop), pos.getShares()))
                    .alertDate(dataDate)
                    .isRead(false)
                    .build());
                return 1;
            }
        } catch (Exception e) {
            log.debug("大跌检测失败: code={}, error={}", pos.getCode(), e.getMessage());
        }
        return 0;
    }

    /**
     * 重大公告检测
     * 查询持仓股最近3天的重要公告
     */
    private int checkImportantNotices(Long paperId, PaperPosition pos, LocalDate today) {
        if (clickHouseJdbcTemplate == null) return 0;
        int count = 0;

        try {
            // 构建 notice_type IN 条件（静态常量，低风险）
            // 修复 SQL 注入：code 和 notice_date 使用 ? 占位符
            String placeholders = String.join(",", java.util.Collections.nCopies(IMPORTANT_NOTICE_TYPES.length, "?"));

            String sql =
                "SELECT notice_type, notice_date, title " +
                "FROM stock.stock_sentiment_notice FINAL " +
                "WHERE code = ? " +
                "  AND notice_date >= ? " +
                "  AND notice_type IN (" + placeholders + ") " +
                "ORDER BY notice_date DESC " +
                "LIMIT 5";

            // 构建参数数组：code + notice_date + 所有 notice_type
            Object[] params = new Object[2 + IMPORTANT_NOTICE_TYPES.length];
            params[0] = pos.getCode();
            params[1] = today.minusDays(3);
            for (int i = 0; i < IMPORTANT_NOTICE_TYPES.length; i++) {
                params[2 + i] = IMPORTANT_NOTICE_TYPES[i];
            }

            List<Map<String, Object>> rows = clickHouseJdbcTemplate.query(
                sql, params,
                (rs, rowNum) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("type", rs.getString("notice_type"));
                    m.put("date", rs.getString("notice_date"));
                    m.put("title", rs.getString("title"));
                    return m;
                });

            for (Map<String, Object> row : rows) {
                LocalDate noticeDate = LocalDate.parse(row.get("date").toString());
                saveAlert(PositionAlert.builder()
                    .paperId(paperId)
                    .code(pos.getCode())
                    .name(pos.getName())
                    .alertType("NOTICE")
                    .alertLevel("WARNING")
                    .title(String.format("%s %s: %s", noticeDate, row.get("type"), row.get("title")))
                    .detail(String.format("公告日期: %s，类型: %s", noticeDate, row.get("type")))
                    .alertDate(noticeDate)
                    .isRead(false)
                    .build());
                count++;
            }
        } catch (Exception e) {
            log.debug("公告检测失败: code={}, error={}", pos.getCode(), e.getMessage());
        }
        return count;
    }

    /**
     * 研报变化检测
     * 查询持仓股最近3天的新增研报（评级变动特别关注）
     */
    private int checkResearchReports(Long paperId, PaperPosition pos, LocalDate today) {
        int count = 0;

        try {
            String sql = "SELECT report_title, rating, institution, report_date " +
                "FROM stock_research_report " +
                "WHERE code = ? AND report_date >= ? " +
                "ORDER BY report_date DESC LIMIT 3";

            List<Map<String, Object>> rows = jdbcTemplate.query(sql, (rs, rowNum) -> {
                Map<String, Object> m = new HashMap<>();
                m.put("title", rs.getString("report_title"));
                m.put("rating", rs.getString("rating"));
                m.put("institution", rs.getString("institution"));
                m.put("date", rs.getString("report_date"));
                return m;
            }, pos.getCode(), today.minusDays(3));

            for (Map<String, Object> row : rows) {
                // 只对包含评级关键词的报告预警（避免信息过载）
                String title = (String) row.get("title");
                if (title != null && (title.contains("首次") || title.contains("增持") || title.contains("买入") || title.contains("减持") || title.contains("下调"))) {
                    LocalDate reportDate = LocalDate.parse(row.get("date").toString());
                    saveAlert(PositionAlert.builder()
                        .paperId(paperId)
                        .code(pos.getCode())
                        .name(pos.getName())
                        .alertType("REPORT")
                        .alertLevel("INFO")
                        .title(String.format("%s %s 研报: %s", reportDate, pos.getName(), title))
                        .detail(String.format("机构: %s，评级: %s，日期: %s",
                            row.get("institution"), row.get("rating"), reportDate))
                        .alertDate(reportDate)
                        .isRead(false)
                        .build());
                    count++;
                }
            }
        } catch (Exception e) {
            log.debug("研报检测失败: code={}, error={}", pos.getCode(), e.getMessage());
        }
        return count;
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

        return RiskCheckResult.pass();
    }

    /**
     * 查询某行业的当前持仓市值
     */
    private BigDecimal getIndustryMarketValue(Long paperId, String industry) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.query(
                "SELECT p.code, p.market_value, s.industry " +
                "FROM paper_position p " +
                "LEFT JOIN stock_info s ON p.code = s.code " +
                "WHERE p.paper_id = ?",
                (rs, rowNum) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("industry", rs.getString("industry"));
                    m.put("mv", rs.getBigDecimal("market_value"));
                    return m;
                }, paperId);

            BigDecimal total = BigDecimal.ZERO;
            for (Map<String, Object> row : rows) {
                if (industry.equals(row.get("industry"))) {
                    BigDecimal mv = (BigDecimal) row.get("mv");
                    if (mv != null) total = total.add(mv);
                }
            }
            return total;
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * 查询某股票的当前持仓市值
     */
    private BigDecimal getStockMarketValue(Long paperId, String code) {
        try {
            List<BigDecimal> r = jdbcTemplate.query(
                "SELECT market_value FROM paper_position WHERE paper_id = ? AND code = ?",
                (rs, rowNum) -> rs.getBigDecimal("market_value"), paperId, code);
            return r.isEmpty() ? BigDecimal.ZERO : r.getFirst();
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    // ─── 辅助方法 ───────────────────────────────────────────────────

    private PaperRiskConfig getRiskConfig(Long paperId) {
        PaperRiskConfig cfg = paperRiskConfigMapper.selectOne(
            new LambdaQueryWrapper<PaperRiskConfig>()
                .eq(PaperRiskConfig::getPaperId, paperId));
        if (cfg != null) return cfg;
        // 未配置时返回默认值，确保风控预警能正常触发
        return PaperRiskConfig.defaults(paperId);
    }

    private BigDecimal getTotalAssets(Long paperId) {
        try {
            List<BigDecimal> r = jdbcTemplate.query(
                "SELECT total_assets FROM paper_trading WHERE id = ?",
                (rs, rowNum) -> rs.getBigDecimal("total_assets"), paperId);
            return r.isEmpty() ? null : r.getFirst();
        } catch (Exception e) {
            return null;
        }
    }

    private String getStockIndustry(String code) {
        if (code == null) return null;
        try {
            List<String> r = jdbcTemplate.query(
                "SELECT industry FROM stock_info WHERE code = ? LIMIT 1",
                (rs, rowNum) -> rs.getString("industry"), code);
            return r.isEmpty() ? null : r.getFirst();
        } catch (Exception e) {
            return null;
        }
    }

    private void saveAlert(PositionAlert alert) {
        try {
            // 去重：同一模拟盘+同一股票+同一类型+同一日期，不重复插入
            long existing = positionAlertMapper.selectCount(
                new LambdaQueryWrapper<PositionAlert>()
                    .eq(PositionAlert::getPaperId, alert.getPaperId())
                    .eq(PositionAlert::getCode, alert.getCode())
                    .eq(PositionAlert::getAlertType, alert.getAlertType())
                    .eq(PositionAlert::getAlertDate, alert.getAlertDate()));
            if (existing == 0) {
                positionAlertMapper.insert(alert);
            }
        } catch (Exception e) {
            log.warn("保存预警失败: paperId={}, code={}, error={}", alert.getPaperId(), alert.getCode(), e.getMessage());
        }
    }
}
