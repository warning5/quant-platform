package com.quant.platform.strategy.paper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quant.platform.calendar.service.TradeCalendarService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import com.quant.platform.common.enums.JobStatus;
/**
 * 模拟盘定时调度器
 * 每个交易日收盘后自动：
 *   1. 处理分红送股
 *   2. 生成交易信号
 *   3. 批量执行信号
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaperTradingScheduler {

    private final PaperTradingService paperTradingService;
    private final PaperTradingMapper paperTradingMapper;
    private final PositionAlertService positionAlertService;
    private final TradeCalendarService tradeCalendarService;
    private final PaperRebalanceService paperRebalanceService;

    /**
     * 每个交易日 15:30 执行（周一至周五）
     * cron: 秒 分 时 日 月 周
     */
    @Scheduled(cron = "0 30 15 * * MON-FRI", zone = "Asia/Shanghai")
    public void runDailyPaperTrading() {
        // 节假日跳过（MON-FRI 不足以排除 A 股法定节假日）
        LocalDate today = LocalDate.now();
        if (!tradeCalendarService.isTradingDay(today)) {
            log.info("今日 [{}] 为非交易日，模拟盘定时任务跳过", today);
            return;
        }
        log.info("========== 模拟盘定时任务开始 ==========");

        // 仅处理顶层盘（parent_id IS NULL）：单策略盘 或 组合根。
        // 子账户(parent_id!=NULL)由组合根统一分发，避免双重处理。
        List<PaperTrading> topPapers = paperTradingMapper.selectList(
                new LambdaQueryWrapper<PaperTrading>()
                        .eq(PaperTrading::getStatus, PaperTradingStatus.RUNNING)
                        .isNull(PaperTrading::getParentId));

        if (topPapers.isEmpty()) {
            log.info("没有运行中的模拟盘，跳过");
            return;
        }

        for (PaperTrading paper : topPapers) {
            try {
                if (paper.getStrategyConfigJson() != null && !paper.getStrategyConfigJson().isBlank()) {
                    // 组合根（Route B）：分发子账户跑单策略管线，再聚合
                    runComboPipeline(paper);
                } else {
                    // 单策略盘
                    runSingleStrategyPipeline(paper);
                }
            } catch (Exception e) {
                log.error("模拟盘 [{}] 定时任务异常", paper.getId(), e);
            }
        }

        log.info("========== 模拟盘定时任务结束 ==========");
    }

    /**
     * 单策略盘完整管线：分红 → 信号 → 执行 → 刷新价 → 预警 → NAV
     */
    private void runSingleStrategyPipeline(PaperTrading paper) {
        log.info("模拟盘 [{}] ({}) 开始处理...", paper.getId(), paper.getStrategyCode());

        // Step 1: 处理当日分红送股
        try {
            paperTradingService.processDividends(paper.getId());
        } catch (Exception e) {
            log.warn("模拟盘 [{}] 分红处理异常: {}", paper.getId(), e.getMessage());
        }

        // Step 2: 生成交易信号
        List<PaperSignal> signals;
        try {
            signals = paperTradingService.generateSignals(paper.getId());
            log.info("模拟盘 [{}] 生成 {} 个信号", paper.getId(), signals.size());
        } catch (Exception e) {
            log.warn("模拟盘 [{}] 信号生成失败: {}", paper.getId(), e.getMessage());
            return;
        }

        if (signals.isEmpty()) {
            log.info("模拟盘 [{}] 无新信号", paper.getId());
            // 即便无信号也要刷新价 + NAV，保证净值连续
        }

        // Step 3: 批量执行信号
        try {
            List<PaperPosition> executed = paperTradingService.executeAllSignals(paper.getId());
            log.info("模拟盘 [{}] 执行 {} 笔交易", paper.getId(), executed.size());
        } catch (Exception e) {
            log.warn("模拟盘 [{}] 信号执行异常: {}", paper.getId(), e.getMessage());
        }

        // Step 4: 收盘后刷新持仓价格为当日收盘价
        try {
            List<PaperPosition> positions = paperTradingService.getPositionsForPaper(paper.getId());
            paperTradingService.refreshPositionPrices(positions);
        } catch (Exception e) {
            log.warn("模拟盘 [{}] 持仓价格刷新异常: {}", paper.getId(), e.getMessage());
        }

        // Step 5: 持仓预警扫描
        try {
            int alertCount = positionAlertService.scanAlerts(paper.getId());
            log.info("模拟盘 [{}] 预警扫描完成，生成 {} 条预警", paper.getId(), alertCount);
        } catch (Exception e) {
            log.warn("模拟盘 [{}] 预警扫描异常: {}", paper.getId(), e.getMessage());
        }

        // Step 6: 收盘后统一计算并记录当日 NAV（日收益基于收盘价）
        try {
            paperTradingService.appendNavRecord(paper.getId());
            log.info("模拟盘 [{}] 当日 NAV 记录完成", paper.getId());
        } catch (Exception e) {
            log.warn("模拟盘 [{}] NAV 记录异常: {}", paper.getId(), e.getMessage());
        }

        log.info("模拟盘 [{}] 处理完成", paper.getId());
    }

    /**
     * 组合根（Route B）：将各 RUNNING 子账户分发跑单策略管线，最后聚合组合层净值/总资产。
     */
    private void runComboPipeline(PaperTrading root) {
        log.info("组合根 [{}] 开始分发子账户...", root.getId());
        List<PaperTrading> children = paperTradingMapper.selectList(
                new LambdaQueryWrapper<PaperTrading>()
                        .eq(PaperTrading::getParentId, root.getId())
                        .eq(PaperTrading::getStatus, PaperTradingStatus.RUNNING));

        if (children.isEmpty()) {
            log.warn("组合根 [{}] 无运行中子账户", root.getId());
        }
        for (PaperTrading child : children) {
            try {
                runSingleStrategyPipeline(child);
            } catch (Exception e) {
                log.warn("组合子账户 [{}] 处理异常: {}", child.getId(), e.getMessage());
            }
        }

        // 子账户全部跑完后聚合组合层
        try {
            paperTradingService.aggregateCombo(root.getId());
            log.info("组合根 [{}] 聚合完成", root.getId());
        } catch (Exception e) {
            log.warn("组合根 [{}] 聚合异常: {}", root.getId(), e.getMessage());
        }

        // 组合层聚合后，逐子账户检查再平衡（阈值/周期触发），记录 paper_rebalance_log
        try {
            paperRebalanceService.rebalanceCombo(root.getId());
        } catch (Exception e) {
            log.warn("组合根 [{}] 再平衡异常: {}", root.getId(), e.getMessage());
        }
    }

    // ── Fix #3: 条件单自动触发（盘中每分钟检查一次） ────────────────────

    /**
     * 盘中每分钟自动检查并执行所有运行中的模拟盘条件单
     * cron: 每分钟（9:00~14:59）周一至周五
     */
    @Scheduled(cron = "0 * 9-14 * * MON-FRI", zone = "Asia/Shanghai")
    public void autoCheckConditionalOrders() {
        List<PaperTrading> runningPapers = paperTradingMapper.selectList(
                new LambdaQueryWrapper<PaperTrading>()
                        .eq(PaperTrading::getStatus, PaperTradingStatus.RUNNING));

        if (runningPapers.isEmpty()) return;

        for (PaperTrading pt : runningPapers) {
            try {
                int executed = paperTradingService.checkAndExecuteConditionalOrders(pt.getId());
                if (executed > 0) {
                    log.info("条件单自动触发: paperId={} executed={}", pt.getId(), executed);
                }
            } catch (Exception e) {
                log.warn("条件单自动检查失败: paperId={} err={}", pt.getId(), e.getMessage());
            }
        }
    }
}
