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
    private final PaperRebalanceLogMapper rebalanceLogMapper;

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
        boolean combo = strategyConfigJson != null && !strategyConfigJson.isBlank();

        if (combo) {
            // ── 多策略组合（Route B：子账户聚合）──
            // 每个子策略 = 一条 paper_trading 子记录（parent_id=根），资金按权重切分
            Map<Long, Double> weights = signalGenerator.parseStrategyWeights(strategyConfigJson);
            double weightSum = weights.values().stream().mapToDouble(Double::doubleValue).sum();
            if (Math.abs(weightSum - 1.0) > 0.05) {
                throw new IllegalArgumentException("组合策略权重之和必须≈1.0，当前=" + weightSum);
            }
            if (weights.isEmpty()) {
                throw new IllegalArgumentException("组合策略配置为空");
            }
            // 组合根 = meta 账户（不交易、不持仓，仅聚合）；strategyId 取首个策略作标签（列 NOT NULL）
            Long rootStrategyId = strategyId != null ? strategyId
                : weights.keySet().stream().findFirst().orElse(0L);
            PaperTrading root = PaperTrading.builder()
                .strategyId(rootStrategyId)
                .strategyCode(strategyCode)
                .strategyConfigJson(strategyConfigJson)
                .status(PaperTradingStatus.RUNNING)
                .initialCapital(initialCapital)
                .currentCapital(initialCapital)
                .totalAssets(initialCapital)
                .positionCount(0)
                .build();
            paperTradingMapper.insert(root);
            insertInitialNav(root.getId(), initialCapital);
            paperRiskConfigMapper.insert(buildRiskConfig(root.getId(), backtestId));

            // 子账户：资本按权重切分，末位吸收取整误差，保证 Σ=总资本
            BigDecimal allocated = BigDecimal.ZERO;
            int idx = 0, n = weights.size();
            for (Map.Entry<Long, Double> e : weights.entrySet()) {
                idx++;
                BigDecimal childCapital = (idx == n)
                    ? initialCapital.subtract(allocated)
                    : initialCapital.multiply(BigDecimal.valueOf(e.getValue()))
                        .setScale(2, RoundingMode.HALF_UP);
                allocated = allocated.add(childCapital);

                PaperTrading child = PaperTrading.builder()
                    .parentId(root.getId())
                    .strategyId(e.getKey())
                    .strategyCode(lookupStrategyCode(e.getKey()))
                    .strategyConfigJson(null)
                    .status(PaperTradingStatus.RUNNING)
                    .initialCapital(childCapital)
                    .currentCapital(childCapital)
                    .totalAssets(childCapital)
                    .positionCount(0)
                    .build();
                paperTradingMapper.insert(child);
                insertInitialNav(child.getId(), childCapital);
                paperRiskConfigMapper.insert(PaperRiskConfig.defaults(child.getId()));
            }
            log.info("组合创建成功: rootId={}, 子策略数={}, 总资本={}", root.getId(), n, initialCapital);
            return root;
        }

        // ── 单策略模式（原逻辑）──
        PaperTrading pt = PaperTrading.builder()
            .strategyId(strategyId)
            .strategyCode(strategyCode)
            .strategyConfigJson(null)
            .status(PaperTradingStatus.RUNNING)
            .initialCapital(initialCapital)
            .currentCapital(initialCapital)
            .totalAssets(initialCapital)
            .positionCount(0)
            .build();
        paperTradingMapper.insert(pt);
        insertInitialNav(pt.getId(), initialCapital);
        paperRiskConfigMapper.insert(buildRiskConfig(pt.getId(), backtestId));
        return pt;
    }

    /** 初始净值记录（当日，累计收益=0） */
    private void insertInitialNav(Long paperId, BigDecimal initialCapital) {
        PaperNav nav = PaperNav.builder()
            .paperId(paperId)
            .navDate(LocalDate.now())
            .totalAssets(initialCapital)
            .dailyReturn(BigDecimal.ZERO)
            .cumulativeReturn(BigDecimal.ZERO)
            .build();
        paperNavMapper.insert(nav);
    }

    /** 风控配置：默认值，若指定 backtestId 则从回测推荐参数覆盖 */
    private PaperRiskConfig buildRiskConfig(Long paperId, Long backtestId) {
        PaperRiskConfig riskConfig = PaperRiskConfig.defaults(paperId);
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
                log.info("模拟盘 {} 从回测 taskId={} 导入风控参数", paperId, backtestId);
            } catch (Exception e) {
                log.warn("从回测 taskId={} 导入参数失败，使用默认风控配置: {}", backtestId, e.getMessage());
            }
        }
        return riskConfig;
    }

    /** 查询策略代码（strategy_definition.strategy_code），失败返回 null */
    private String lookupStrategyCode(Long strategyId) {
        try {
            List<String> codes = jdbcTemplate.query(
                "SELECT strategy_code FROM strategy_definition WHERE id = ?",
                (rs, rowNum) -> rs.getString("strategy_code"), strategyId);
            return codes.isEmpty() ? null : codes.getFirst();
        } catch (Exception e) {
            return null;
        }
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
        // 组合根删除时需级联清理子账户：parent_id 仅建索引、无外键约束，不会自动级联，
        // 否则子账户会残留为孤儿记录（parent_id 指向已删除的根）
        List<PaperTrading> children = paperTradingMapper.selectList(
            new LambdaQueryWrapper<PaperTrading>().eq(PaperTrading::getParentId, paperId));
        List<Long> allIds = new ArrayList<>();
        allIds.add(paperId);
        for (PaperTrading child : children) {
            allIds.add(child.getId());
        }
        // 按依赖顺序删除：信号 → 持仓 → 净值 → 风控配置 → 主表（根 + 子账户）
        for (Long id : allIds) {
            paperSignalMapper.delete(
                new LambdaQueryWrapper<PaperSignal>().eq(PaperSignal::getPaperId, id));
            paperPositionMapper.delete(
                new LambdaQueryWrapper<PaperPosition>().eq(PaperPosition::getPaperId, id));
            paperNavMapper.delete(
                new LambdaQueryWrapper<PaperNav>().eq(PaperNav::getPaperId, id));
            paperRiskConfigMapper.delete(
                new LambdaQueryWrapper<PaperRiskConfig>().eq(PaperRiskConfig::getPaperId, id));
        }
        paperTradingMapper.deleteBatchIds(allIds);
        log.info("模拟盘已删除: id={}, strategyCode={}, 级联子账户数={}", paperId, pt.getStrategyCode(), children.size());
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

    /** 聚合组合根净值（Route B：汇总子账户总资产/净值，写入组合根） */
    public void aggregateCombo(Long comboId) {
        accountService.aggregateCombo(comboId);
    }

    /** 查询组合根下的运行中子账户 */
    public List<PaperTrading> getComboChildren(Long comboId) {
        return paperTradingMapper.selectList(
            new LambdaQueryWrapper<PaperTrading>().eq(PaperTrading::getParentId, comboId));
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

    // ════════════════════════════════════════════════════════════════
    // 多策略组合（Route B）详情 / 归因
    // ════════════════════════════════════════════════════════════════

    /** 组合详情：总览 + 子策略贡献 + 相关性矩阵 + 分散化比率 */
    public Map<String, Object> getComboDetail(Long comboId) {
        PaperTrading root = paperTradingMapper.selectById(comboId);
        if (root == null) throw new IllegalArgumentException("组合不存在");
        if (root.getParentId() != null) throw new IllegalArgumentException("该模拟盘不是组合根，无法作为组合查看");
        List<PaperTrading> children = getComboChildren(comboId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("comboId", comboId);
        result.put("isCombo", true);
        result.put("initialCapital", root.getInitialCapital());
        result.put("currentCapital", root.getCurrentCapital());
        result.put("totalAssets", root.getTotalAssets());
        result.put("positionCount", root.getPositionCount());
        BigDecimal totalRet = (root.getInitialCapital() != null && root.getInitialCapital().compareTo(BigDecimal.ZERO) > 0)
            ? root.getTotalAssets().subtract(root.getInitialCapital()).divide(root.getInitialCapital(), 6, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;
        result.put("totalReturn", totalRet);

        Map<Long, Double> weights = (root.getStrategyConfigJson() != null && !root.getStrategyConfigJson().isBlank())
            ? signalGenerator.parseStrategyWeights(root.getStrategyConfigJson()) : new LinkedHashMap<>();
        result.put("weights", weights);
        result.put("subStrategies", buildSubStrategyMaps(root, children, weights));
        result.put("correlation", computeCorrelationMatrix(children));
        result.put("diversificationRatio", computeDiversificationRatio(children));
        return result;
    }

    /** 子策略列表（各自 P&L / 持仓 / 状态 / 权重 / 贡献） */
    public List<Map<String, Object>> getComboSubStrategies(Long comboId) {
        PaperTrading root = paperTradingMapper.selectById(comboId);
        if (root == null) throw new IllegalArgumentException("组合不存在");
        List<PaperTrading> children = getComboChildren(comboId);
        Map<Long, Double> weights = (root.getStrategyConfigJson() != null && !root.getStrategyConfigJson().isBlank())
            ? signalGenerator.parseStrategyWeights(root.getStrategyConfigJson()) : new LinkedHashMap<>();
        return buildSubStrategyMaps(root, children, weights);
    }

    /** 组合与各子账户净值曲线（供前端多线对比） */
    public Map<String, Object> getComboNav(Long comboId) {
        PaperTrading root = paperTradingMapper.selectById(comboId);
        if (root == null) throw new IllegalArgumentException("组合不存在");
        List<PaperTrading> children = getComboChildren(comboId);

        List<Map<String, Object>> comboNav = paperNavMapper.selectList(
                new LambdaQueryWrapper<PaperNav>().eq(PaperNav::getPaperId, comboId).orderByAsc(PaperNav::getNavDate))
            .stream().map(n -> navToMap(n)).collect(Collectors.toList());

        List<Map<String, Object>> subNavs = new ArrayList<>();
        for (PaperTrading c : children) {
            List<Map<String, Object>> navs = paperNavMapper.selectList(
                    new LambdaQueryWrapper<PaperNav>().eq(PaperNav::getPaperId, c.getId()).orderByAsc(PaperNav::getNavDate))
                .stream().map(n -> navToMap(n)).collect(Collectors.toList());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("subPaperId", c.getId());
            m.put("strategyId", c.getStrategyId());
            m.put("strategyCode", c.getStrategyCode());
            m.put("nav", navs);
            subNavs.add(m);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("comboId", comboId);
        result.put("comboNav", comboNav);
        result.put("subNavs", subNavs);
        return result;
    }

    /** 组合再平衡历史（组合根 + 所有子账户的日志） */
    public List<Map<String, Object>> getComboRebalanceLogs(Long comboId) {
        List<PaperRebalanceLog> logs = rebalanceLogMapper.selectByComboId(comboId);
        List<Map<String, Object>> res = new ArrayList<>();
        for (PaperRebalanceLog l : logs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", l.getId());
            m.put("paperId", l.getPaperId());
            m.put("triggerType", l.getTriggerType());
            m.put("rebalanceDate", l.getRebalanceDate() != null ? l.getRebalanceDate().toString() : null);
            m.put("maxDriftPct", l.getMaxDriftPct());
            m.put("tradedSymbols", l.getTradedSymbols());
            m.put("note", l.getNote());
            m.put("beforeAllocationJson", l.getBeforeAllocationJson());
            m.put("afterAllocationJson", l.getAfterAllocationJson());
            res.add(m);
        }
        return res;
    }

    /** 组合信号流水（聚合所有子账户信号，按日期倒序） */
    public List<Map<String, Object>> getComboSignals(Long comboId) {
        List<PaperTrading> children = getComboChildren(comboId);
        List<Long> childIds = children.stream().map(PaperTrading::getId).collect(Collectors.toList());
        if (childIds.isEmpty()) return new ArrayList<>();
        List<PaperSignal> signals = paperSignalMapper.selectList(
            new LambdaQueryWrapper<PaperSignal>().in(PaperSignal::getPaperId, childIds).orderByDesc(PaperSignal::getSignalDate));
        List<Map<String, Object>> res = new ArrayList<>();
        for (PaperSignal s : signals) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("paperId", s.getPaperId());
            m.put("signalDate", s.getSignalDate() != null ? s.getSignalDate().toString() : null);
            m.put("code", s.getCode());
            m.put("name", s.getName());
            m.put("direction", s.getDirection());
            m.put("signalPrice", s.getSignalPrice());
            m.put("factorScore", s.getFactorScore());
            m.put("reason", s.getReason());
            m.put("status", s.getStatus());
            m.put("strategyId", s.getStrategyId());
            res.add(m);
        }
        return res;
    }

    /** 暂停某子策略（资金留在该子账户，不再交易） */
    public PaperTrading pauseSubStrategy(Long comboId, Long strategyId) {
        PaperTrading child = findChildByStrategy(comboId, strategyId);
        child.setStatus(PaperTradingStatus.PAUSED);
        paperTradingMapper.updateById(child);
        return child;
    }

    /** 恢复某子策略 */
    public PaperTrading resumeSubStrategy(Long comboId, Long strategyId) {
        PaperTrading child = findChildByStrategy(comboId, strategyId);
        child.setStatus(PaperTradingStatus.RUNNING);
        paperTradingMapper.updateById(child);
        return child;
    }

    // ── 内部工具 ──

    private Map<String, Object> navToMap(PaperNav n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", n.getNavDate().toString());
        m.put("totalAssets", n.getTotalAssets());
        m.put("cumulativeReturn", n.getCumulativeReturn());
        m.put("dailyReturn", n.getDailyReturn());
        return m;
    }

    private List<Map<String, Object>> buildSubStrategyMaps(PaperTrading root, List<PaperTrading> children, Map<Long, Double> weights) {
        List<Map<String, Object>> subs = new ArrayList<>();
        BigDecimal comboInit = (root.getInitialCapital() != null) ? root.getInitialCapital() : BigDecimal.ZERO;
        for (PaperTrading c : children) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("subPaperId", c.getId());
            m.put("strategyId", c.getStrategyId());
            m.put("strategyCode", c.getStrategyCode());
            m.put("weight", weights.getOrDefault(c.getStrategyId(), 0.0));
            m.put("initialCapital", c.getInitialCapital());
            m.put("currentCapital", c.getCurrentCapital());
            m.put("totalAssets", c.getTotalAssets());
            m.put("positionCount", c.getPositionCount());
            m.put("status", c.getStatus());
            BigDecimal ret = (c.getInitialCapital() != null && c.getInitialCapital().compareTo(BigDecimal.ZERO) > 0)
                ? c.getTotalAssets().subtract(c.getInitialCapital()).divide(c.getInitialCapital(), 6, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
            m.put("returnRate", ret);
            BigDecimal contrib = comboInit.compareTo(BigDecimal.ZERO) > 0
                ? c.getTotalAssets().subtract(c.getInitialCapital()).divide(comboInit, 6, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
            m.put("contribution", contrib);
            subs.add(m);
        }
        return subs;
    }

    private PaperTrading findChildByStrategy(Long comboId, Long strategyId) {
        PaperTrading child = paperTradingMapper.selectOne(new LambdaQueryWrapper<PaperTrading>()
            .eq(PaperTrading::getParentId, comboId).eq(PaperTrading::getStrategyId, strategyId));
        if (child == null) throw new IllegalArgumentException("组合下未找到子策略: " + strategyId);
        return child;
    }

    /** 策略间相关性矩阵（基于各子账户日收益率 Pearson 相关） */
    private Map<String, Object> computeCorrelationMatrix(List<PaperTrading> children) {
        Map<Long, TreeMap<LocalDate, BigDecimal>> returnsByChild = new LinkedHashMap<>();
        for (PaperTrading c : children) {
            List<PaperNav> navs = paperNavMapper.selectList(new LambdaQueryWrapper<PaperNav>()
                .eq(PaperNav::getPaperId, c.getId()).orderByAsc(PaperNav::getNavDate));
            TreeMap<LocalDate, BigDecimal> ret = new TreeMap<>();
            BigDecimal prev = null;
            for (PaperNav n : navs) {
                if (prev != null && prev.compareTo(BigDecimal.ZERO) > 0) {
                    ret.put(n.getNavDate(), n.getTotalAssets().subtract(prev).divide(prev, 6, RoundingMode.HALF_UP));
                }
                prev = n.getTotalAssets();
            }
            returnsByChild.put(c.getId(), ret);
        }
        TreeSet<LocalDate> allDates = new TreeSet<>();
        returnsByChild.values().forEach(m -> allDates.addAll(m.keySet()));

        List<Map<String, Object>> matrix = new ArrayList<>();
        for (Map.Entry<Long, TreeMap<LocalDate, BigDecimal>> a : returnsByChild.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("strategyId", a.getKey());
            List<Map<String, Object>> corrs = new ArrayList<>();
            for (Map.Entry<Long, TreeMap<LocalDate, BigDecimal>> b : returnsByChild.entrySet()) {
                double corr = a.getKey().equals(b.getKey()) ? 1.0
                    : pearsonOnCommonDates(a.getValue(), b.getValue(), allDates);
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("strategyId", b.getKey());
                cell.put("correlation", Math.round(corr * 10000.0) / 10000.0);
                corrs.add(cell);
            }
            row.put("correlations", corrs);
            matrix.add(row);
        }
        double avg = 0; int cnt = 0;
        for (Map<String, Object> row : matrix) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> corrs = (List<Map<String, Object>>) row.get("correlations");
            for (Map<String, Object> cell : corrs) {
                double v = ((Number) cell.get("correlation")).doubleValue();
                if (v < 0.999) { avg += v; cnt++; }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("matrix", matrix);
        out.put("averageCorrelation", cnt > 0 ? Math.round((avg / cnt) * 10000.0) / 10000.0 : 0.0);
        return out;
    }

    private double pearsonOnCommonDates(TreeMap<LocalDate, BigDecimal> a, TreeMap<LocalDate, BigDecimal> b, TreeSet<LocalDate> allDates) {
        List<Double> xa = new ArrayList<>(), xb = new ArrayList<>();
        for (LocalDate d : allDates) {
            BigDecimal va = a.get(d), vb = b.get(d);
            if (va != null && vb != null) { xa.add(va.doubleValue()); xb.add(vb.doubleValue()); }
        }
        return pearson(xa, xb);
    }

    private double pearson(List<Double> x, List<Double> y) {
        int n = Math.min(x.size(), y.size());
        if (n < 3) return 0.0;
        double mx = 0, my = 0;
        for (int i = 0; i < n; i++) { mx += x.get(i); my += y.get(i); }
        mx /= n; my /= n;
        double cov = 0, vx = 0, vy = 0;
        for (int i = 0; i < n; i++) {
            double dx = x.get(i) - mx, dy = y.get(i) - my;
            cov += dx * dy; vx += dx * dx; vy += dy * dy;
        }
        if (vx < 1e-12 || vy < 1e-12) return 0.0;
        return cov / Math.sqrt(vx * vy);
    }

    /** 分散化比率 DR = 加权个券波动率 / 组合波动率（>1 说明分散化有效） */
    private BigDecimal computeDiversificationRatio(List<PaperTrading> children) {
        if (children.size() < 2) return BigDecimal.ONE;
        BigDecimal totalInit = children.stream()
            .map(c -> c.getInitialCapital() != null ? c.getInitialCapital() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalInit.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ONE;

        Map<Long, TreeMap<LocalDate, BigDecimal>> retMap = new LinkedHashMap<>();
        Map<Long, Double> volMap = new HashMap<>();
        Map<Long, Double> weightMap = new HashMap<>();
        for (PaperTrading c : children) {
            List<PaperNav> navs = paperNavMapper.selectList(new LambdaQueryWrapper<PaperNav>()
                .eq(PaperNav::getPaperId, c.getId()).orderByAsc(PaperNav::getNavDate));
            TreeMap<LocalDate, BigDecimal> ret = new TreeMap<>();
            BigDecimal prev = null;
            for (PaperNav n : navs) {
                if (prev != null && prev.compareTo(BigDecimal.ZERO) > 0) {
                    ret.put(n.getNavDate(), n.getTotalAssets().subtract(prev).divide(prev, 6, RoundingMode.HALF_UP));
                }
                prev = n.getTotalAssets();
            }
            double[] arr = ret.values().stream().mapToDouble(BigDecimal::doubleValue).toArray();
            retMap.put(c.getId(), ret);
            volMap.put(c.getId(), std(arr));
            weightMap.put(c.getId(), c.getInitialCapital().divide(totalInit, 6, RoundingMode.HALF_UP).doubleValue());
        }

        TreeSet<LocalDate> dates = new TreeSet<>();
        retMap.values().forEach(m -> dates.addAll(m.keySet()));
        List<Double> portRets = new ArrayList<>();
        for (LocalDate d : dates) {
            double pr = 0;
            for (PaperTrading c : children) {
                BigDecimal r = retMap.get(c.getId()).get(d);
                if (r != null) pr += weightMap.get(c.getId()) * r.doubleValue();
            }
            portRets.add(pr);
        }
        double portVol = std(portRets.stream().mapToDouble(Double::doubleValue).toArray());
        double weightedAvgVol = children.stream()
            .mapToDouble(c -> weightMap.get(c.getId()) * volMap.get(c.getId())).sum();
        if (portVol < 1e-10) return BigDecimal.ONE;
        return BigDecimal.valueOf(weightedAvgVol / portVol).setScale(4, RoundingMode.HALF_UP);
    }

    private double std(double[] arr) {
        if (arr.length < 2) return 0.0;
        double mean = 0;
        for (double v : arr) mean += v;
        mean /= arr.length;
        double var = 0;
        for (double v : arr) { double d = v - mean; var += d * d; }
        var /= arr.length;
        return Math.sqrt(var);
    }

}
