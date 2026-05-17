package com.quant.platform.strategy.paper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

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

    /**
     * 每个交易日 15:30 执行（周一至周五）
     * cron: 秒 分 时 日 月 周
     */
    @Scheduled(cron = "0 30 15 * * MON-FRI", zone = "Asia/Shanghai")
    public void runDailyPaperTrading() {
        log.info("========== 模拟盘定时任务开始 ==========");

        List<PaperTrading> runningPapers = paperTradingMapper.selectList(
                new LambdaQueryWrapper<PaperTrading>()
                        .eq(PaperTrading::getStatus, "RUNNING"));

        if (runningPapers.isEmpty()) {
            log.info("没有运行中的模拟盘，跳过");
            return;
        }

        for (PaperTrading paper : runningPapers) {
            try {
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
                    continue;
                }

                if (signals.isEmpty()) {
                    log.info("模拟盘 [{}] 无新信号", paper.getId());
                    continue;
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
            } catch (Exception e) {
                log.error("模拟盘 [{}] 定时任务异常", paper.getId(), e);
            }
        }

        log.info("========== 模拟盘定时任务结束 ==========");
    }
}
