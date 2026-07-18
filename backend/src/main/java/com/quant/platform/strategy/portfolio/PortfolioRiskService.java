package com.quant.platform.strategy.portfolio;

import com.quant.platform.recommendation.domain.StockRecommendation;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 策略组合风控服务（P2-9）
 *
 * 跨策略组合层面的风险管理，补充现有单策略风控（PositionAlertService）的缺口：
 * 1. 跨策略个股去重 — 同一股票在多个策略推荐中出现时，保留得分最高的，降权其余
 * 2. 组合级行业暴露 — 聚合多策略推荐后的全局行业集中度检查
 * 3. 组合级回撤监控 — 聚合多模拟盘的总回撤
 * 4. 仓位上限控制 — 单股跨策略总仓位不超过上限
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortfolioRiskService {

    private final JdbcTemplate jdbcTemplate;

    /** 跨策略单股最大仓位占比（所有策略合计） */
    private static final double MAX_CROSS_STRATEGY_POSITION_PCT = 0.15;
    /** 组合级行业集中度上限 */
    private static final double MAX_PORTFOLIO_INDUSTRY_PCT = 0.35;
    /** 组合级最大回撤 */
    private static final double MAX_PORTFOLIO_DRAWDOWN_PCT = 0.20;
    /** 同股跨策略去重模式：BEST_ONLY（保留最佳） / MERGE（合并仓位） */
    private static final String DEDUP_MODE = "BEST_ONLY";

    /**
     * 跨策略个股去重
     * 当同一股票被多个策略推荐时，保留finalScore最高的版本，其余标记为DEMOTE
     *
     * @param strategyRecommendations Map<strategyId, List<StockRecommendation>>
     * @return 去重后的 Map<strategyId, List<StockRecommendation>>（被去重的推荐标记qualityTag=PORTFOLIO_DEDUP）
     */
    public Map<Long, List<StockRecommendation>> deduplicateAcrossStrategies(
            Map<Long, List<StockRecommendation>> strategyRecommendations) {

        if (strategyRecommendations == null || strategyRecommendations.size() <= 1) {
            return strategyRecommendations;
        }

        // 1. 收集所有推荐，按stockCode分组
        Map<String, List<StockRecommendation>> byStock = new HashMap<>();
        for (Map.Entry<Long, List<StockRecommendation>> entry : strategyRecommendations.entrySet()) {
            for (StockRecommendation rec : entry.getValue()) {
                byStock.computeIfAbsent(rec.getStockCode(), k -> new ArrayList<>()).add(rec);
            }
        }

        // 2. 对每个被多策略推荐的股票，保留finalScore最高的，其余降权
        int dedupCount = 0;
        for (Map.Entry<String, List<StockRecommendation>> entry : byStock.entrySet()) {
            List<StockRecommendation> recs = entry.getValue();
            if (recs.size() <= 1) continue;

            // 按finalScore降序排列
            recs.sort((a, b) -> {
                double sa = a.getFinalScore() != null ? a.getFinalScore() : 0;
                double sb = b.getFinalScore() != null ? b.getFinalScore() : 0;
                return Double.compare(sb, sa);
            });

            // 保留第一名，其余标记降权
            for (int i = 1; i < recs.size(); i++) {
                StockRecommendation demoted = recs.get(i);
                demoted.setQualityTag("PORTFOLIO_DEDUP");
                if (demoted.getSuggestedPositionPct() != null) {
                    demoted.setSuggestedPositionPct(demoted.getSuggestedPositionPct() * 0.3); // 降权70%
                }
                dedupCount++;
                log.debug("[PortfolioRisk] 跨策略去重: {} 在策略{}中被降权(finalScore={}, 最佳策略finalScore={})",
                    demoted.getStockCode(), demoted.getStrategyId(),
                    demoted.getFinalScore(), recs.get(0).getFinalScore());
            }
        }

        if (dedupCount > 0) {
            log.info("[PortfolioRisk] 跨策略去重完成: {}条推荐被降权(共{}策略, {}条总推荐)",
                dedupCount, strategyRecommendations.size(),
                strategyRecommendations.values().stream().mapToInt(List::size).sum());
        }

        return strategyRecommendations;
    }

    /**
     * 检查组合级行业集中度
     * 聚合所有推荐后的行业分布，超过上限的行业发出预警
     *
     * @param allRecommendations 所有策略的推荐列表
     * @return 行业暴露报告
     */
    public PortfolioIndustryReport checkPortfolioIndustryExposure(List<StockRecommendation> allRecommendations) {
        PortfolioIndustryReport report = new PortfolioIndustryReport();
        if (allRecommendations == null || allRecommendations.isEmpty()) {
            return report;
        }

        // 按行业聚合仓位
        Map<String, Double> industryExposure = new HashMap<>();
        double totalExposure = 0;

        for (StockRecommendation rec : allRecommendations) {
            String industry = rec.getIndustry() != null ? rec.getIndustry() : "UNKNOWN";
            double pos = rec.getSuggestedPositionPct() != null ? rec.getSuggestedPositionPct() : 0.05; // 默认5%
            industryExposure.merge(industry, pos, Double::sum);
            totalExposure += pos;
        }

        report.setTotalExposure(totalExposure);
        report.setIndustryExposure(industryExposure);

        // 检查超限行业
        List<String> warnings = new ArrayList<>();
        for (Map.Entry<String, Double> entry : industryExposure.entrySet()) {
            double pct = totalExposure > 0 ? entry.getValue() / totalExposure : 0;
            if (pct > MAX_PORTFOLIO_INDUSTRY_PCT) {
                warnings.add(String.format("%s: %.1f%% (上限%.0f%%)", entry.getKey(), pct * 100, MAX_PORTFOLIO_INDUSTRY_PCT * 100));
            }
        }
        report.setWarnings(warnings);

        if (!warnings.isEmpty()) {
            log.warn("[PortfolioRisk] 组合行业集中度预警: {}", String.join(", ", warnings));
        }

        return report;
    }

    /**
     * 检查跨策略单股仓位集中度
     * 同一股票在多个策略推荐中的合计仓位不超过上限
     *
     * @param allRecommendations 所有策略的推荐列表
     * @return 超限股票列表
     */
    public List<String> checkCrossStrategyPosition(List<StockRecommendation> allRecommendations) {
        if (allRecommendations == null || allRecommendations.isEmpty()) {
            return List.of();
        }

        // 按股票聚合仓位
        Map<String, Double> stockExposure = new HashMap<>();
        for (StockRecommendation rec : allRecommendations) {
            double pos = rec.getSuggestedPositionPct() != null ? rec.getSuggestedPositionPct() : 0.05;
            stockExposure.merge(rec.getStockCode(), pos, Double::sum);
        }

        // 检查超限
        List<String> overLimit = new ArrayList<>();
        for (Map.Entry<String, Double> entry : stockExposure.entrySet()) {
            if (entry.getValue() > MAX_CROSS_STRATEGY_POSITION_PCT) {
                overLimit.add(String.format("%s: %.1f%% (上限%.0f%%)",
                    entry.getKey(), entry.getValue() * 100, MAX_CROSS_STRATEGY_POSITION_PCT * 100));
            }
        }

        if (!overLimit.isEmpty()) {
            log.warn("[PortfolioRisk] 跨策略单股仓位超限: {}", String.join(", ", overLimit));
        }

        return overLimit;
    }

    /**
     * 检查组合级回撤
     * 聚合所有运行中模拟盘的总回撤
     *
     * @return 组合回撤报告
     */
    public PortfolioDrawdownReport checkPortfolioDrawdown() {
        PortfolioDrawdownReport report = new PortfolioDrawdownReport();

        try {
            // 查询所有RUNNING模拟盘的最新净值
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT pt.id as paper_id, pt.strategy_id, pt.total_assets, pt.current_capital " +
                "FROM paper_trading pt " +
                "WHERE pt.status = 'RUNNING'");

            if (rows.isEmpty()) {
                report.setHasActivePapers(false);
                return report;
            }

            double totalAssets = 0;
            double totalPeak = 0;
            List<PortfolioDrawdownReport.PaperDrawdown> paperDrawdowns = new ArrayList<>();

            for (Map<String, Object> row : rows) {
                Long paperId = ((Number) row.get("paper_id")).longValue();
                Long strategyId = row.get("strategy_id") != null ? ((Number) row.get("strategy_id")).longValue() : 0;
                double assets = row.get("total_assets") != null ? ((Number) row.get("total_assets")).doubleValue() : 0;

                // 查询该模拟盘的历史峰值净值
                try {
                    Double peak = jdbcTemplate.queryForObject(
                        "SELECT MAX(total_assets) FROM paper_nav WHERE paper_id = ?",
                        Double.class, paperId);
                    if (peak == null || peak == 0) peak = assets;

                    double drawdown = peak > 0 ? (peak - assets) / peak : 0;
                    totalAssets += assets;
                    totalPeak += peak;

                    paperDrawdowns.add(new PortfolioDrawdownReport.PaperDrawdown(
                        paperId, "策略#" + strategyId, assets, peak, drawdown));
                } catch (Exception e) {
                    log.debug("[PortfolioRisk] 查询模拟盘 {} 历史净值失败: {}", paperId, e.getMessage());
                }
            }

            report.setHasActivePapers(true);
            report.setPaperCount(paperDrawdowns.size());
            report.setTotalNav(totalAssets);
            report.setPaperDrawdowns(paperDrawdowns);

            // 组合级回撤 = (总峰值 - 总当前) / 总峰值
            double portfolioDrawdown = totalPeak > 0 ? (totalPeak - totalAssets) / totalPeak : 0;
            report.setPortfolioDrawdown(portfolioDrawdown);

            if (portfolioDrawdown > MAX_PORTFOLIO_DRAWDOWN_PCT) {
                report.setAlert(true);
                report.setAlertMessage(String.format(
                    "组合级回撤 %.1f%% 超过上限 %.0f%%", portfolioDrawdown * 100, MAX_PORTFOLIO_DRAWDOWN_PCT * 100));
                log.warn("[PortfolioRisk] {}", report.getAlertMessage());
            }

        } catch (Exception e) {
            log.error("[PortfolioRisk] 组合回撤检查失败", e);
            report.setError(e.getMessage());
        }

        return report;
    }

    /**
     * 执行全量组合风控检查（可由调度器每日调用）
     *
     * @return 综合风控报告
     */
    public Map<String, Object> runFullPortfolioRiskCheck(LocalDate date) {
        Map<String, Object> report = new LinkedHashMap<>();
        log.info("[PortfolioRisk] 开始组合风控检查 date={}", date);

        // 1. 查询当天所有策略的推荐
        List<StockRecommendation> allRecommendations = new ArrayList<>();
        try {
            List<Long> strategyIds = jdbcTemplate.queryForList(
                "SELECT DISTINCT strategy_id FROM stock_recommendation WHERE recommend_date = ?",
                Long.class, date);
            for (Long sid : strategyIds) {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT * FROM stock_recommendation WHERE strategy_id = ? AND recommend_date = ?",
                    sid, date);
                // 简化处理：只取关键字段做风控
                for (Map<String, Object> row : rows) {
                    StockRecommendation rec = new StockRecommendation();
                    rec.setStockCode((String) row.get("stock_code"));
                    rec.setStockName((String) row.get("stock_name"));
                    rec.setIndustry((String) row.get("industry"));
                    rec.setStrategyId(sid);
                    Object fp = row.get("suggested_position_pct");
                    if (fp != null) rec.setSuggestedPositionPct(((Number) fp).doubleValue());
                    Object fs = row.get("final_score");
                    if (fs != null) rec.setFinalScore(((Number) fs).doubleValue());
                    allRecommendations.add(rec);
                }
            }
        } catch (Exception e) {
            log.warn("[PortfolioRisk] 查询推荐数据失败: {}", e.getMessage());
        }

        report.put("totalRecommendations", allRecommendations.size());

        // 2. 跨策略单股仓位检查
        List<String> positionWarnings = checkCrossStrategyPosition(allRecommendations);
        report.put("positionWarnings", positionWarnings);

        // 3. 组合行业集中度检查
        PortfolioIndustryReport industryReport = checkPortfolioIndustryExposure(allRecommendations);
        report.put("industryReport", industryReport);

        // 4. 组合回撤检查
        PortfolioDrawdownReport drawdownReport = checkPortfolioDrawdown();
        report.put("drawdownReport", drawdownReport);

        // 5. 汇总
        int totalWarnings = positionWarnings.size() + industryReport.getWarnings().size()
            + (drawdownReport.isAlert() ? 1 : 0);
        report.put("totalWarnings", totalWarnings);
        report.put("riskLevel", totalWarnings == 0 ? "OK" : totalWarnings <= 2 ? "WARNING" : "CRITICAL");

        log.info("[PortfolioRisk] 组合风控检查完成: recommendations={} warnings={} riskLevel={}",
            allRecommendations.size(), totalWarnings, report.get("riskLevel"));

        return report;
    }

    // ===== 报告类 =====

    @Data
    public static class PortfolioIndustryReport {
        private double totalExposure;
        private Map<String, Double> industryExposure = new LinkedHashMap<>();
        private List<String> warnings = new ArrayList<>();
    }

    @Data
    public static class PortfolioDrawdownReport {
        private boolean hasActivePapers;
        private int paperCount;
        private double totalNav;
        private double portfolioDrawdown;
        private boolean alert;
        private String alertMessage;
        private String error;
        private List<PaperDrawdown> paperDrawdowns = new ArrayList<>();

        @Data
        public static class PaperDrawdown {
            private final Long paperId;
            private final String paperName;
            private final double nav;
            private final double peak;
            private final double drawdown;

            public PaperDrawdown(Long paperId, String paperName, double nav, double peak, double drawdown) {
                this.paperId = paperId;
                this.paperName = paperName;
                this.nav = nav;
                this.peak = peak;
                this.drawdown = drawdown;
            }
        }
    }
}
