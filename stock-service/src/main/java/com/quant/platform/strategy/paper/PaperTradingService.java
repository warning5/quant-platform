package com.quant.platform.strategy.paper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.common.enums.ResourceType;
import com.quant.platform.dataperm.service.DataPermissionService;
import com.quant.platform.factor.service.FactorService;
import com.quant.platform.recommendation.mapper.RecommendationMapper;
import com.quant.platform.stock.analysis.engine.SellSignalEngine;
import com.quant.platform.stock.analysis.service.MarketThermometerService;
import com.quant.platform.calendar.service.TradeCalendarService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import com.quant.platform.common.enums.JobStatus;
/**
 * 模拟盘交易服务
 * 基于策略配置生成信号、管理持仓、追踪净值
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaperTradingService {

    private final PaperOrderExecutionService executionService;

    private final PaperSignalGenerator signalGenerator;

    private final PaperAccountService accountService;

    private final PaperPriceService priceService;

    private final PaperTradingMapper paperTradingMapper;
    private final PaperPositionMapper paperPositionMapper;
    private final PaperSignalMapper paperSignalMapper;
    private final PaperNavMapper paperNavMapper;
    private final PaperRiskConfigMapper paperRiskConfigMapper;
    private final PaperCashFlowMapper paperCashFlowMapper;
    private final com.quant.platform.factor.service.FactorMetaCacheService factorMetaCache;
    private final DataPermissionService dataPermissionService;
    @Autowired(required = false)
    private PaperExecutionQualityMapper paperExecutionQualityMapper;
    private final JdbcTemplate jdbcTemplate;

    @Autowired(required = false)
    @Qualifier("clickHouseJdbcTemplate")
    private JdbcTemplate clickHouseJdbcTemplate;

    @Autowired(required = false)
    private FactorService factorService;

    @Autowired(required = false)
    private MarketThermometerService marketThermometerService;

    @Autowired(required = false)
    private TradeCalendarService tradeCalendarService;

    @Autowired(required = false)
    private PositionAlertService positionAlertService;

    @Autowired(required = false)
    private RecommendationMapper recommendationMapper;

    @Autowired(required = false)
    private SellSignalEngine sellSignalEngine;

    @Autowired(required = false)
    private com.quant.platform.backtest.service.BacktestService backtestService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 创建模拟盘
     * @param backtestId 可选：从回测报告导入推荐风控参数（打通回测->模拟盘链路）
     */
    @Transactional
    public PaperTrading createPaperTrading(Long strategyId, String strategyCode, BigDecimal initialCapital, String strategyConfigJson, Long backtestId) {
        // 组合模式校验：strategyConfigJson必须有效，权重之和≈1
        if (strategyConfigJson != null && !strategyConfigJson.isBlank()) {
            double weightSum = signalGenerator.parseStrategyWeights(strategyConfigJson).values().stream().mapToDouble(Double::doubleValue).sum();
            if (Math.abs(weightSum - 1.0) > 0.05) {
                throw new IllegalArgumentException("组合策略权重之和必须≈1.0，当前=" + weightSum);
            }
            // 组合模式不需要strategyId（取JSON中第一个策略的ID做标记）
            if (strategyId == null) {
                strategyId = signalGenerator.parseStrategyWeights(strategyConfigJson).keySet().stream().findFirst().orElse(0L);
            }
        }

        PaperTrading pt = PaperTrading.builder()
            .strategyId(strategyId)
            .strategyCode(strategyCode)
            .strategyConfigJson(strategyConfigJson)
            .status(PaperTradingStatus.RUNNING)
            .initialCapital(initialCapital)
            .currentCapital(initialCapital)
            .totalAssets(initialCapital)
            .positionCount(0)
            .build();
        paperTradingMapper.insert(pt);

        // 初始净值记录
        PaperNav nav = PaperNav.builder()
            .paperId(pt.getId())
            .navDate(LocalDate.now())
            .totalAssets(initialCapital)
            .dailyReturn(BigDecimal.ZERO)
            .cumulativeReturn(BigDecimal.ZERO)
            .build();
        paperNavMapper.insert(nav);

        // 风控配置：默认值，若指定 backtestId 则从回测推荐参数覆盖
        PaperRiskConfig riskConfig = PaperRiskConfig.defaults(pt.getId());
        if (backtestId != null && backtestService != null) {
            try {
                var recommended = backtestService.calculateRecommendedConfig(backtestId);
                riskConfig.setStopLossPct(recommended.getStopLossPct());
                riskConfig.setTakeProfitPct(recommended.getTakeProfitPct());
                riskConfig.setMaxPositionPct(recommended.getMaxPositionPct());
                riskConfig.setMaxDrawdownPct(recommended.getMaxDrawdownPct());
                riskConfig.setTimingEnabled(recommended.getTimingEnabled());
                riskConfig.setBenchmarkCode(recommended.getBenchmarkCode());
                riskConfig.setAllocationMode(recommended.getAllocationMode());
                // 回测频率映射：WEEKLY->WEEKLY, MONTHLY->MONTHLY, BIWEEKLY->WEEKLY, 其他保持DAILY
                String freq = recommended.getRebalanceFreq();
                if (freq != null) {
                    if ("MONTHLY".equalsIgnoreCase(freq)) {
                        riskConfig.setRebalanceFreq("MONTHLY");
                    } else if ("WEEKLY".equalsIgnoreCase(freq) || "BIWEEKLY".equalsIgnoreCase(freq)) {
                        riskConfig.setRebalanceFreq("WEEKLY");
                    }
                }
                log.info("模拟盘 {} 从回测 taskId={} 导入风控参数: stopLoss={}, takeProfit={}, maxDrawdown={}, timing={}",
                    pt.getId(), backtestId, recommended.getStopLossPct(), recommended.getTakeProfitPct(),
                    recommended.getMaxDrawdownPct(), recommended.getTimingEnabled());
            } catch (Exception e) {
                log.warn("从回测 taskId={} 导入参数失败，使用默认风控配置: {}", backtestId, e.getMessage());
            }
        }
        paperRiskConfigMapper.insert(riskConfig);

        return pt;
    }

    /**
     * 获取所有模拟盘列表（刷新持仓价格和总资产）
     */
    public List<PaperTrading> listAll() {
        List<PaperTrading> list = paperTradingMapper.selectList(
            new LambdaQueryWrapper<PaperTrading>().orderByDesc(PaperTrading::getCreatedAt));
        for (PaperTrading pt : list) {
            try {
                List<PaperPosition> positions = paperPositionMapper.selectList(
                        new LambdaQueryWrapper<PaperPosition>().eq(PaperPosition::getPaperId, pt.getId()));
                refreshPositionPrices(positions);
                BigDecimal posValue = positions.stream()
                        .map(p -> p.getMarketValue() != null ? p.getMarketValue() : BigDecimal.ZERO)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                pt.setTotalAssets(pt.getCurrentCapital().add(posValue));
                pt.setPositionCount(positions.size());
            } catch (Exception e) {
                log.warn("刷新模拟盘列表价格失败: paperId={}, error={}", pt.getId(), e.getMessage());
            }
        }
        return list;
    }

    /**
     * 获取模拟盘详情
     */
    public Map<String, Object> getDetail(Long paperId) {
        PaperTrading pt = paperTradingMapper.selectById(paperId);
        if (pt == null) throw new IllegalArgumentException("模拟盘不存在");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("paperId", paperId);
        result.put("paper", pt);

        // 持仓（刷新现价和盈亏）
        List<PaperPosition> positions = paperPositionMapper.selectList(
            new LambdaQueryWrapper<PaperPosition>().eq(PaperPosition::getPaperId, paperId));
        refreshPositionPrices(positions);
        result.put("positions", positions);

        // 用刷新后的持仓市值重新计算总资产
        BigDecimal posValue = positions.stream()
            .map(p -> p.getMarketValue() != null ? p.getMarketValue() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        pt.setTotalAssets(pt.getCurrentCapital().add(posValue));

        // 最新净值
        List<PaperNav> navs = paperNavMapper.selectList(
            new LambdaQueryWrapper<PaperNav>()
                .eq(PaperNav::getPaperId, paperId)
                .orderByDesc(PaperNav::getNavDate)
                .last("LIMIT 30"));
        Collections.reverse(navs);
        result.put("navHistory", navs);

        // 基准指数净值（指数增强监控）
        PaperRiskConfig cfg = paperRiskConfigMapper.selectOne(
            new LambdaQueryWrapper<PaperRiskConfig>().eq(PaperRiskConfig::getPaperId, paperId));
        String benchmarkCode = cfg != null && cfg.getBenchmarkCode() != null
            ? cfg.getBenchmarkCode() : "000300";

        if (clickHouseJdbcTemplate != null && !navs.isEmpty()) {
            try {
                LocalDate navStartDate = navs.getFirst().getNavDate();
                // 基准往前多查30天，让基准曲线有参考意义（即使模拟盘只运行1天也能看到指数走势）
                LocalDate startDate = navStartDate.minusDays(30);
                LocalDate endDate = LocalDate.now();

                // 归一化基准指数净值（起点=1.0）
                String benchmarkSql = """
                    SELECT trade_date, close_price
                    FROM stock.index_daily FINAL
                    WHERE code = ? AND trade_date >= ? AND trade_date <= ?
                    ORDER BY trade_date ASC
                    """;

                List<Map<String, Object>> indexRows = clickHouseJdbcTemplate.query(benchmarkSql,
                    new Object[]{benchmarkCode, startDate, endDate},
                    (rs, rowNum) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("date", rs.getString("trade_date"));
                        m.put("close", rs.getBigDecimal("close_price"));
                        return m;
                    });

                // 找到 navStartDate 前一个交易日的收盘价作为归一化基准
                // 这样 navStartDate 当天显示的是当日涨跌幅，不是强制的0%
                BigDecimal basePrice = null;
                for (int i = 0; i < indexRows.size(); i++) {
                    if (navStartDate.toString().equals(indexRows.get(i).get("date"))) {
                        if (i > 0) {
                            basePrice = (BigDecimal) indexRows.get(i - 1).get("close");
                        }
                        break;
                    }
                }
                // 如果找不到前一个交易日，回退到 navStartDate 当天
                if (basePrice == null) {
                    for (Map<String, Object> row : indexRows) {
                        if (navStartDate.toString().equals(row.get("date"))) {
                            basePrice = (BigDecimal) row.get("close");
                            break;
                        }
                    }
                }
                // 如果还是找不到，取第一条
                if (basePrice == null && !indexRows.isEmpty()) {
                    basePrice = (BigDecimal) indexRows.getFirst().get("close");
                }

                // 只输出 navHistory 日期范围内的基准数据（不展示模拟盘创建前的基准历史）
                Set<String> navDateSet = navs.stream()
                    .map(n -> n.getNavDate().toString()).collect(Collectors.toSet());

                List<Map<String, Object>> benchmarkNav = new ArrayList<>();
                for (Map<String, Object> row : indexRows) {
                    String date = (String) row.get("date");
                    if (!navDateSet.contains(date)) continue;
                    Map<String, Object> b = new LinkedHashMap<>();
                    b.put("date", date);
                    if (basePrice != null && basePrice.compareTo(BigDecimal.ZERO) > 0) {
                        double normalized = ((BigDecimal) row.get("close"))
                            .divide(basePrice, 6, RoundingMode.HALF_UP).doubleValue();
                        b.put("nav", Math.round(normalized * 1000.0) / 1000.0);
                    } else {
                        b.put("nav", 1.0);
                    }
                    benchmarkNav.add(b);
                }
                result.put("benchmarkNav", benchmarkNav);
                result.put("benchmarkCode", benchmarkCode);

                // ── 信息比率（IR）= 滚动N日超额收益均值 / 超额收益标准差 ──
                // 传入 indexRows（全量基准数据）和 basePrice，用于逐日超额收益计算
                accountService.calculateInformationRatio(result, navs, indexRows, basePrice);
            } catch (Exception e) {
                log.debug("基准指数净值查询失败: paperId={}, error={}", paperId, e.getMessage());
            }
        }

        // 刷新/追加快照当日净值（非交易时段也能看到最新净值）
        accountService.refreshTodayNav(pt);

        return result;
    }

    /**
     * 获取模拟盘持仓列表（供 Scheduler 调用）
     */
    public List<PaperPosition> getPositionsForPaper(Long paperId) {
        return paperPositionMapper.selectList(
            new LambdaQueryWrapper<PaperPosition>().eq(PaperPosition::getPaperId, paperId));
    }

    /**
     * 刷新持仓现价、市值、盈亏（公开方法，供 Scheduler 在收盘后统一刷新）
     */
    public void refreshPositionPrices(List<PaperPosition> positions) {
        if (positions == null || positions.isEmpty() || clickHouseJdbcTemplate == null) return;

        // 获取最新交易日
        String latestDate;
        try {
            List<String> dates = clickHouseJdbcTemplate.query(
                "SELECT max(trade_date) as d FROM stock.stock_daily FINAL",
                (rs, rowNum) -> rs.getString("d"));
            latestDate = dates.isEmpty() || dates.getFirst() == null ? null : dates.getFirst();
        } catch (Exception e) {
            log.warn("获取最新交易日失败: {}", e.getMessage());
            return;
        }
        if (latestDate == null) return;

        for (PaperPosition pos : positions) {
            try {
                BigDecimal latestPrice = priceService.getOpenPrice(pos.getCode(), latestDate);
                if (latestPrice == null || latestPrice.compareTo(BigDecimal.ZERO) <= 0) continue;

                pos.setCurrentPrice(latestPrice);
                BigDecimal marketValue = latestPrice.multiply(BigDecimal.valueOf(pos.getShares()));
                pos.setMarketValue(marketValue);

                BigDecimal cost = pos.getCostPrice().multiply(BigDecimal.valueOf(pos.getShares()));
                BigDecimal profitLoss = marketValue.subtract(cost);
                pos.setProfitLoss(profitLoss);

                if (cost.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal profitPct = profitLoss.divide(cost, 6, RoundingMode.HALF_UP);
                    pos.setProfitLossPct(profitPct);
                }
                paperPositionMapper.updateById(pos);
            } catch (Exception e) {
                log.warn("刷新持仓价格失败: code={}, error={}", pos.getCode(), e.getMessage());
            }
        }
    }

    /**
     * 获取信号列表
     */
    public List<PaperSignal> getSignals(Long paperId) {
        List<PaperSignal> signals = paperSignalMapper.selectList(
            new LambdaQueryWrapper<PaperSignal>()
                .eq(PaperSignal::getPaperId, paperId)
                .orderByDesc(PaperSignal::getSignalDate)
                .orderByDesc(PaperSignal::getId)
                .last("LIMIT 50"));
        // 同日期内：BUY 按 factorScore 降序，SELL 排后面
        signals.sort((a, b) -> {
            int dateCmp = b.getSignalDate().compareTo(a.getSignalDate());
            if (dateCmp != 0) return dateCmp;
            // 同日期：BUY 在前，SELL 在后
            boolean aBuy = "BUY".equals(a.getDirection());
            boolean bBuy = "BUY".equals(b.getDirection());
            if (aBuy && !bBuy) return -1;
            if (!aBuy && bBuy) return 1;
            // 同方向：按得分降序
            if (a.getFactorScore() != null && b.getFactorScore() != null) {
                return b.getFactorScore().compareTo(a.getFactorScore());
            }
            return 0;
        });
        return signals;
    }

    /**
     * 暂停/恢复/停止模拟盘
     */
    public PaperTrading updateStatus(Long paperId, String status) {
        dataPermissionService.assertCanWrite(ResourceType.PAPER_TRADING.getCode(), paperId);
        PaperTrading pt = paperTradingMapper.selectById(paperId);
        if (pt == null) throw new IllegalArgumentException("模拟盘不存在");
        // 入参来自 HTTP，必须校验：枚举化前此处可写入任意字符串
        PaperTradingStatus target = PaperTradingStatus.fromCode(status);
        if (target == null) {
            throw new IllegalArgumentException("非法的模拟盘状态: " + status + "，仅支持 RUNNING/PAUSED/STOPPED");
        }
        pt.setStatus(target);
        paperTradingMapper.updateById(pt);
        return pt;
    }

    // ─── 内部方法 ──────────────────────────────────────────────────────

    /**
     * 删除模拟盘（及关联的持仓、信号、净值）
     */
    @Transactional
    public void deletePaperTrading(Long paperId) {
        dataPermissionService.assertCanWrite(ResourceType.PAPER_TRADING.getCode(), paperId);
        PaperTrading pt = paperTradingMapper.selectById(paperId);
        if (pt == null) throw new IllegalArgumentException("模拟盘不存在");
        // 按依赖顺序删除：信号 → 持仓 → 净值 → 主表
        paperSignalMapper.delete(
            new LambdaQueryWrapper<PaperSignal>().eq(PaperSignal::getPaperId, paperId));
        paperPositionMapper.delete(
            new LambdaQueryWrapper<PaperPosition>().eq(PaperPosition::getPaperId, paperId));
        paperNavMapper.delete(
            new LambdaQueryWrapper<PaperNav>().eq(PaperNav::getPaperId, paperId));
        paperTradingMapper.deleteById(paperId);
        log.info("模拟盘已删除: id={}, strategyCode={}", paperId, pt.getStrategyCode());
    }

    /**
     * 获取风控配置（无配置则返回默认值）
     */
    public PaperRiskConfig getRiskConfig(Long paperId) {
        PaperRiskConfig cfg = paperRiskConfigMapper.selectOne(
            new LambdaQueryWrapper<PaperRiskConfig>().eq(PaperRiskConfig::getPaperId, paperId));
        return cfg != null ? cfg : PaperRiskConfig.defaults(paperId);
    }

    /**
     * 更新风控配置（部分更新，只更新非 null 参数）
     */
    @Transactional
    public PaperRiskConfig updateRiskConfig(Long paperId,
            BigDecimal stopLossPct, BigDecimal takeProfitPct,
            BigDecimal trailingAtr, BigDecimal maxPositionPct,
            BigDecimal maxIndustryPct, BigDecimal maxDrawdownPct,
            Integer timingEnabled, String benchmarkCode, String allocationMode,
            BigDecimal slippagePct, String slippageModel, BigDecimal cashBufferPct,
            String rebalanceFreq, BigDecimal rebalanceThreshold,
            Integer autoBlockEnabled, Integer twapThreshold) {
        dataPermissionService.assertCanWrite(ResourceType.PAPER_TRADING.getCode(), paperId);
        PaperRiskConfig cfg = paperRiskConfigMapper.selectOne(
            new LambdaQueryWrapper<PaperRiskConfig>().eq(PaperRiskConfig::getPaperId, paperId));
        if (cfg == null) {
            cfg = PaperRiskConfig.defaults(paperId);
            paperRiskConfigMapper.insert(cfg);
        }
        if (stopLossPct != null) cfg.setStopLossPct(stopLossPct);
        if (takeProfitPct != null) cfg.setTakeProfitPct(takeProfitPct);
        if (trailingAtr != null) cfg.setTrailingAtr(trailingAtr);
        if (maxPositionPct != null) cfg.setMaxPositionPct(maxPositionPct);
        if (maxIndustryPct != null) cfg.setMaxIndustryPct(maxIndustryPct);
        if (maxDrawdownPct != null) cfg.setMaxDrawdownPct(maxDrawdownPct);
        if (timingEnabled != null) cfg.setTimingEnabled(timingEnabled);
        if (benchmarkCode != null) cfg.setBenchmarkCode(benchmarkCode);
        if (allocationMode != null) cfg.setAllocationMode(allocationMode);
        if (slippagePct != null) cfg.setSlippagePct(slippagePct);
        if (slippageModel != null) cfg.setSlippageModel(slippageModel);
        if (cashBufferPct != null) cfg.setCashBufferPct(cashBufferPct);
        if (rebalanceFreq != null) cfg.setRebalanceFreq(rebalanceFreq);
        if (rebalanceThreshold != null) cfg.setRebalanceThreshold(rebalanceThreshold);
        if (autoBlockEnabled != null) cfg.setAutoBlockEnabled(autoBlockEnabled);
        if (twapThreshold != null) cfg.setTwapThreshold(twapThreshold);
        cfg.setUpdatedAt(LocalDateTime.now());
        paperRiskConfigMapper.updateById(cfg);

        // 配置变更后清除旧的风控类预警，下次扫描按新配置重新生成
        jdbcTemplate.update(
            "DELETE FROM position_alert WHERE paper_id = ? AND alert_type IN ('RISK_CONCENTRATION','RISK_INDUSTRY','RISK_DRAWDOWN')",
            paperId);

        log.info("风控配置已更新: paperId={}", paperId);
        return cfg;
    }

    // ── Fix #2: 一键买入（从推荐页快速建仓） ──────────────────────────────

    public void appendNavRecord(Long paperId) {
        accountService.appendNavRecord(paperId);
    }

    public PaperCashFlow deposit(Long paperId, BigDecimal amount, String note) {
        return accountService.deposit(paperId, amount, note);
    }

    public PaperCashFlow withdraw(Long paperId, BigDecimal amount, String note) {
        return accountService.withdraw(paperId, amount, note);
    }

    public List<PaperCashFlow> getCashFlows(Long paperId) {
        return accountService.getCashFlows(paperId);
    }

    public List<PaperSignal> generateSignals(Long paperId) {
        return signalGenerator.generateSignals(paperId);
    }

    public PaperPosition executeSignal(Long signalId) {
        return executionService.executeSignal(signalId);
    }

    public List<PaperPosition> executeAllSignals(Long paperId) {
        return executionService.executeAllSignals(paperId);
    }

    public void processDividends(Long paperId) {
        executionService.processDividends(paperId);
    }

    public void autoSellByStopLoss(Long paperId, String code, String reason) {
        executionService.autoSellByStopLoss(paperId, code, reason);
    }

    public PaperPosition quickBuy(Long paperId, String code, String name, BigDecimal price) {
        return executionService.quickBuy(paperId, code, name, price);
    }

    public PaperSignal createConditionalOrder(Long paperId, String code, String direction, String orderType, BigDecimal triggerPrice, BigDecimal limitPrice, BigDecimal trailPct, BigDecimal trailAmount, BigDecimal signalPrice, String reason) {
        return executionService.createConditionalOrder(paperId, code, direction, orderType, triggerPrice, limitPrice, trailPct, trailAmount, signalPrice, reason);
    }

    public int checkAndExecuteConditionalOrders(Long paperId) {
        return executionService.checkAndExecuteConditionalOrders(paperId);
    }

}
