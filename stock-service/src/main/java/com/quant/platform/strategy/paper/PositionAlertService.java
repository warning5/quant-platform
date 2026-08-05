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
    private final PositionRiskChecker alertRiskChecker;
    private final PreTradeValidator preTradeValidator;

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

    // ─── 风控委托（PositionRiskChecker） ─────────────────────────────
    private int checkRiskConcentration(Long paperId, List<PaperPosition> positions, LocalDate today) { return alertRiskChecker.checkRiskConcentration(paperId, positions, today); }
    private int checkRiskIndustry(Long paperId, List<PaperPosition> positions, LocalDate today) { return alertRiskChecker.checkRiskIndustry(paperId, positions, today); }
    private int checkRiskDrawdown(Long paperId, LocalDate today) { return alertRiskChecker.checkRiskDrawdown(paperId, today); }
    private int checkRiskCorrelation(Long paperId, List<PaperPosition> positions, LocalDate today) { return alertRiskChecker.checkRiskCorrelation(paperId, positions, today); }
    private int checkEventDrivenAlerts(Long paperId, List<PaperPosition> positions, LocalDate today) { return alertRiskChecker.checkEventDrivenAlerts(paperId, positions, today); }
    private double calcPearsonCorrelation(double[] x, double[] y) { return alertRiskChecker.calcPearsonCorrelation(x, y); }

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




    // ─── 辅助方法 ───────────────────────────────────────────────────

    // ─── 交易前风控委托（PreTradeValidator） ──────────────────────────
    public RiskCheckResult checkBeforeTrade(Long paperId, String code, BigDecimal plannedAmount) { return preTradeValidator.checkBeforeTrade(paperId, code, plannedAmount); }
}
