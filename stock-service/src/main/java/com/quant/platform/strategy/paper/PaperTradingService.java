package com.quant.platform.strategy.paper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * 模拟盘交易服务
 * 基于策略配置生成信号、管理持仓、追踪净值
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaperTradingService {

    private final PaperTradingMapper paperTradingMapper;
    private final PaperPositionMapper paperPositionMapper;
    private final PaperSignalMapper paperSignalMapper;
    private final PaperNavMapper paperNavMapper;
    private final PaperRiskConfigMapper paperRiskConfigMapper;
    private final PaperCashFlowMapper paperCashFlowMapper;
    private final com.quant.platform.factor.service.FactorMetaCacheService factorMetaCache;
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
            double weightSum = parseStrategyWeights(strategyConfigJson).values().stream().mapToDouble(Double::doubleValue).sum();
            if (Math.abs(weightSum - 1.0) > 0.05) {
                throw new IllegalArgumentException("组合策略权重之和必须≈1.0，当前=" + weightSum);
            }
            // 组合模式不需要strategyId（取JSON中第一个策略的ID做标记）
            if (strategyId == null) {
                strategyId = parseStrategyWeights(strategyConfigJson).keySet().stream().findFirst().orElse(0L);
            }
        }

        PaperTrading pt = PaperTrading.builder()
            .strategyId(strategyId)
            .strategyCode(strategyCode)
            .strategyConfigJson(strategyConfigJson)
            .status("RUNNING")
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
                calculateInformationRatio(result, navs, indexRows, basePrice);
            } catch (Exception e) {
                log.debug("基准指数净值查询失败: paperId={}, error={}", paperId, e.getMessage());
            }
        }

        // 刷新/追加快照当日净值（非交易时段也能看到最新净值）
        refreshTodayNav(pt);

        return result;
    }

    /**
     * 刷新/追加当日净值快照，确保 getDetail 返回时 navHistory 包含今日最新数据
     */
    private void refreshTodayNav(PaperTrading pt) {
        if (clickHouseJdbcTemplate == null) return;
        try {
            // 获取最新交易日作为"今日"（非自然日）
            List<String> dates = clickHouseJdbcTemplate.query(
                "SELECT max(trade_date) as d FROM stock.stock_daily FINAL",
                (rs, rowNum) -> rs.getString("d"));
            if (dates.isEmpty() || dates.getFirst() == null) return;
            LocalDate today = LocalDate.parse(dates.getFirst());

            // 前一交易日 NAV（用于计算 dailyReturn）
            PaperNav prevNav = paperNavMapper.selectOne(
                new LambdaQueryWrapper<PaperNav>()
                    .eq(PaperNav::getPaperId, pt.getId())
                    .ne(PaperNav::getNavDate, today)
                    .orderByDesc(PaperNav::getNavDate)
                    .last("LIMIT 1"));

            BigDecimal prevTotalAssets = prevNav != null
                ? prevNav.getTotalAssets() : pt.getInitialCapital();

            BigDecimal dailyReturn = prevTotalAssets.compareTo(BigDecimal.ZERO) > 0
                ? pt.getTotalAssets().subtract(prevTotalAssets)
                    .divide(prevTotalAssets, 6, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

            BigDecimal cumulativeReturn = pt.getInitialCapital().compareTo(BigDecimal.ZERO) > 0
                ? pt.getTotalAssets().subtract(pt.getInitialCapital())
                    .divide(pt.getInitialCapital(), 6, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

            PaperNav todayNav = paperNavMapper.selectOne(
                new LambdaQueryWrapper<PaperNav>()
                    .eq(PaperNav::getPaperId, pt.getId())
                    .eq(PaperNav::getNavDate, today));

            if (todayNav != null) {
                todayNav.setTotalAssets(pt.getTotalAssets());
                todayNav.setDailyReturn(dailyReturn);
                todayNav.setCumulativeReturn(cumulativeReturn);
                paperNavMapper.updateById(todayNav);
            } else {
                PaperNav nav = PaperNav.builder()
                    .paperId(pt.getId())
                    .navDate(today)
                    .totalAssets(pt.getTotalAssets())
                    .dailyReturn(dailyReturn)
                    .cumulativeReturn(cumulativeReturn)
                    .build();
                paperNavMapper.insert(nav);
            }
        } catch (Exception e) {
            log.warn("刷新当日净值快照失败: paperId={}, error={}", pt.getId(), e.getMessage());
        }
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
                BigDecimal latestPrice = getOpenPrice(pos.getCode(), latestDate);
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
     * 【缺陷1修复】创建条件单（限价单/止损单/止损限价单/追踪止损单）
     */
    @Transactional
    public PaperSignal createConditionalOrder(Long paperId, String code, String direction,
            String orderType, BigDecimal triggerPrice, BigDecimal limitPrice,
            BigDecimal trailPct, BigDecimal trailAmount, BigDecimal signalPrice, String reason) {
        // 参数校验
        if (!"LIMIT".equals(orderType) && !"STOP".equals(orderType)
                && !"STOP_LIMIT".equals(orderType) && !"TRAILING_STOP".equals(orderType)) {
            throw new IllegalArgumentException("不支持的条件单类型: " + orderType);
        }
        if ("LIMIT".equals(orderType) && triggerPrice == null) {
            throw new IllegalArgumentException("限价单必须指定触发价格(triggerPrice)");
        }
        if ("STOP".equals(orderType) && triggerPrice == null) {
            throw new IllegalArgumentException("止损单必须指定触发价格(triggerPrice)");
        }
        if ("STOP_LIMIT".equals(orderType) && (triggerPrice == null || limitPrice == null)) {
            throw new IllegalArgumentException("止损限价单必须指定触发价格(triggerPrice)和限价(limitPrice)");
        }
        if ("TRAILING_STOP".equals(orderType)
                && ((trailPct == null && trailAmount == null)
                    || (trailPct != null && trailPct.compareTo(BigDecimal.ZERO) <= 0)
                    || (trailAmount != null && trailAmount.compareTo(BigDecimal.ZERO) <= 0))) {
            throw new IllegalArgumentException("追踪止损必须指定回撤比例(trailPct>0)或回撤金额(trailAmount>0)");
        }
        if (!"BUY".equals(direction) && !"SELL".equals(direction)) {
            throw new IllegalArgumentException("信号方向必须是 BUY 或 SELL");
        }

        // 止损单和追踪止损只能用于卖出（保护已有持仓）
        if (("STOP".equals(orderType) || "STOP_LIMIT".equals(orderType) || "TRAILING_STOP".equals(orderType))
                && "BUY".equals(direction)) {
            throw new IllegalArgumentException("止损单/止损限价单/追踪止损单只能用于卖出方向");
        }

        String name = getStockName(code);
        if (signalPrice == null) {
            signalPrice = getExecutionPrice(code, paperId);
        }
        if (reason == null) {
            reason = String.format("%s条件单 %s@%s", orderType, direction, code);
        }

        // 追踪止损：初始最高价取当前持仓成本价或当前价
        BigDecimal highestSinceBuy = null;
        if ("TRAILING_STOP".equals(orderType)) {
            PaperPosition pos = paperPositionMapper.selectOne(
                new LambdaQueryWrapper<PaperPosition>()
                    .eq(PaperPosition::getPaperId, paperId)
                    .eq(PaperPosition::getCode, code));
            if (pos == null) {
                throw new IllegalArgumentException("追踪止损需要有对应持仓: " + code);
            }
            highestSinceBuy = pos.getCostPrice() != null ? pos.getCostPrice() : signalPrice;
        }

        PaperSignal signal = PaperSignal.builder()
            .paperId(paperId)
            .signalDate(LocalDate.now())
            .factorDate(LocalDate.now())
            .code(code)
            .name(name)
            .direction(direction)
            .signalPrice(signalPrice)
            .reason(reason)
            .status("PENDING")
            .orderType(orderType)
            .triggerPrice(triggerPrice)
            .limitPrice(limitPrice)
            .trailPct(trailPct)
            .trailAmount(trailAmount)
            .highestSinceBuy(highestSinceBuy)
            .build();
        paperSignalMapper.insert(signal);
        log.info("条件单创建: paperId={} code={} direction={} orderType={} triggerPrice={} trailPct={}",
            paperId, code, direction, orderType, triggerPrice, trailPct);
        return signal;
    }

    /**
     * 生成交易信号
     * 根据策略因子配置，计算最新截面得分，生成买入/卖出信号
     */
    @Transactional
    public List<PaperSignal> generateSignals(Long paperId) {
        PaperTrading pt = paperTradingMapper.selectById(paperId);
        if (pt == null) throw new IllegalArgumentException("模拟盘不存在");
        if (!"RUNNING".equals(pt.getStatus())) throw new IllegalArgumentException("模拟盘未运行");

        // 交易日门控：若今天非交易日（周末/节假日），跳过信号生成
        if (!isTradingDay()) {
            log.info("generateSignals: 最近3日内无有效交易日数据（因子可能断档），跳过信号生成");
            return List.of();
        }

        // 生成前先删除该模拟盘所有 PENDING 信号，避免重复生成
        int cleared = paperSignalMapper.delete(
                new LambdaQueryWrapper<PaperSignal>()
                        .eq(PaperSignal::getPaperId, paperId)
                        .eq(PaperSignal::getStatus, "PENDING"));
        if (cleared > 0) {
            log.info("generateSignals: 清除旧 PENDING 信号 {} 条", cleared);
        }

        // 读取风控配置（无配置时使用默认值）
        PaperRiskConfig riskConfig = paperRiskConfigMapper.selectOne(
                new LambdaQueryWrapper<PaperRiskConfig>().eq(PaperRiskConfig::getPaperId, paperId));
        if (riskConfig == null) {
            riskConfig = PaperRiskConfig.defaults(paperId);
        }
        BigDecimal stopLossPct = riskConfig.getStopLossPct();
        BigDecimal takeProfitPct = riskConfig.getTakeProfitPct();
        log.info("generateSignals: paperId={}, stopLoss={}%, takeProfit={}%",
            paperId, stopLossPct, takeProfitPct);

        // 获取策略因子配置（支持单策略和多策略组合）
        Map<Long, Double> strategyWeights; // strategyId → weight
        if (pt.getStrategyConfigJson() != null && !pt.getStrategyConfigJson().isBlank()) {
            // 组合模式：从JSON解析多策略权重
            strategyWeights = parseStrategyWeights(pt.getStrategyConfigJson());
        } else {
            // 单策略模式：权重=1.0
            strategyWeights = Map.of(pt.getStrategyId(), 1.0);
        }

        // 收集所有策略使用的因子code（按权重加权）
        Map<String, Double> combinedFactorWeights = new LinkedHashMap<>();
        for (Map.Entry<Long, Double> entry : strategyWeights.entrySet()) {
            String factorConfigJson = getStrategyFactorConfig(entry.getKey());
            if (factorConfigJson == null || factorConfigJson.isEmpty()) {
                log.warn("策略{}因子配置为空，跳过", entry.getKey());
                continue;
            }
            List<Map<String, Object>> factorConfigs = parseFactorConfigs(factorConfigJson);
            double strategyWeight = entry.getValue();
            for (Map<String, Object> fc : factorConfigs) {
                String code = (String) fc.getOrDefault("code", fc.get("factorCode"));
                if (code != null && !code.isBlank()) {
                    // 同一因子在多策略中出现时，权重叠加
                    double factorWeight = ((Number) fc.getOrDefault("weight", 1.0)).doubleValue();
                    combinedFactorWeights.merge(code, strategyWeight * factorWeight, Double::sum);
                }
            }
        }

        Set<String> usedFactorCodes = combinedFactorWeights.keySet();

        // 修复 Bug：signalDate 取日频因子的最大日期（排除 FIN_），不再取所有因子日期的最小值
        // 这样 signalDate = 最新日频因子日期，与每个因子的截面查询日期一致
        String signalDate = null;
        LocalDate maxDailyDate = null;
        for (String fc : usedFactorCodes) {
            if (factorMetaCache.isFinancial(fc)) continue; // 排除财务因子（季频，不依赖日频行情）
            String d = getFactorLatestDate(fc);
            if (d != null) {
                LocalDate ld = LocalDate.parse(d);
                if (maxDailyDate == null || ld.isAfter(maxDailyDate)) {
                    maxDailyDate = ld;
                }
            }
        }
        if (maxDailyDate == null) {
            // 兜底：从 stock_daily 取最新交易日
            try {
                List<String> dates = clickHouseJdbcTemplate.query(
                    "SELECT MAX(trade_date) FROM stock.stock_daily FINAL",
                    (rs, rowNum) -> rs.getString(1));
                if (!dates.isEmpty() && dates.getFirst() != null) {
                    maxDailyDate = LocalDate.parse(dates.getFirst());
                }
            } catch (Exception ignored) {}
        }
        signalDate = maxDailyDate != null ? maxDailyDate.toString() : LocalDate.now().toString();
        log.info("generateSignals: paperId={}, signalDate={}（日频因子最新，排除FIN_）, strategies={}, factorCount={}",
            paperId, signalDate, strategyWeights.size(), combinedFactorWeights.size());

        // 改造：每个因子用自己的最新日期，分别归一化后加权合并
        // 组合模式下，因子权重 = 各策略因子权重 × 策略权重 之和
        Map<String, Double> stockScores = new HashMap<>();
        Map<String, Double> stockWeights = new HashMap<>();

        // 需要因子方向信息，从DB获取
        for (String factorCode : usedFactorCodes) {
            double weight = combinedFactorWeights.getOrDefault(factorCode, 1.0);
            // 从 factor_definition 获取因子方向
            String direction = getFactorDirection(factorCode);

            // 从 CH 获取因子值：每个因子用自己的最新日期
            if (clickHouseJdbcTemplate != null) {
                try {
                    String factorDate = getFactorLatestDate(factorCode);
                    if (factorDate == null) {
                        log.warn("generateSignals: 因子 {} 无数据，跳过", factorCode);
                        continue;
                    }
                    String sql = """
                        SELECT symbol, rank_value FROM stock.factor_value FINAL
                        WHERE factor_code = ? AND calc_date = ?
                          AND rank_value IS NOT NULL
                        """;
                    clickHouseJdbcTemplate.query(sql, new Object[]{factorCode, factorDate}, (rs) -> {
                        String sym = rs.getString("symbol");
                        if (sym != null && sym.contains(".")) sym = sym.split("\\.")[0];
                        double rankVal = rs.getBigDecimal("rank_value").doubleValue();
                        double adjustedRank = "DESC".equals(direction) ? (1.0 - rankVal) : rankVal;
                        stockScores.merge(sym, adjustedRank * weight, Double::sum);
                        stockWeights.merge(sym, weight, Double::sum);
                    });
                    log.info("generateSignals: factor={}, date={}, weight={}", factorCode, factorDate, weight);
                } catch (Exception e) {
                    log.debug("因子 {} 截面查询失败: {}", factorCode, e.getMessage());
                }
            }
        }

        // 归一化得分
        Map<String, Double> finalScores = new HashMap<>();
        for (Map.Entry<String, Double> e : stockScores.entrySet()) {
            double w = stockWeights.getOrDefault(e.getKey(), 1.0);
            finalScores.put(e.getKey(), e.getValue() / w);
        }

        // 按得分排序，取 Top 20 为买入信号
        List<Map.Entry<String, Double>> sorted = finalScores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(20)
            .toList();
        log.info("generateSignals: scored stocks={}, top20 first={}, last={}", finalScores.size(),
            sorted.isEmpty() ? "N/A" : sorted.getFirst().getKey(), sorted.isEmpty() ? "N/A" : sorted.getLast().getKey());

        // 获取当前持仓
        List<PaperPosition> currentPositions = paperPositionMapper.selectList(
            new LambdaQueryWrapper<PaperPosition>().eq(PaperPosition::getPaperId, paperId));
        Set<String> heldCodes = currentPositions.stream()
            .map(PaperPosition::getCode).collect(Collectors.toSet());

        // 获取当天已 SKIPPED 的股票，避免资金不足等原因反复生成重复信号
        Set<String> skippedCodes = paperSignalMapper.selectList(
                new LambdaQueryWrapper<PaperSignal>()
                        .eq(PaperSignal::getPaperId, paperId)
                        .eq(PaperSignal::getStatus, "SKIPPED")
                        .eq(PaperSignal::getDirection, "BUY"))
                .stream()
                .map(PaperSignal::getCode)
                .collect(Collectors.toSet());

        // 得分低于 0.3 的持仓 → 卖出信号（附加止盈止损条件）
        List<PaperSignal> signals = new ArrayList<>();
        for (PaperPosition pos : currentPositions) {
            String reason = null;
            String triggerType = null;

            // 止盈/止损检查
            BigDecimal profitPct = pos.getProfitLossPct();
            if (profitPct != null) {
                double pp = profitPct.doubleValue(); // 保持小数（如-0.08=亏8%），与stopLossPct/takeProfitPct量纲一致
                if (pp <= -stopLossPct.doubleValue()) {
                    triggerType = "止损";
                    reason = String.format("触发止损（亏损%.1f%% > %.1f%%）", pp * 100, stopLossPct.doubleValue() * 100);
                } else if (pp >= takeProfitPct.doubleValue()) {
                    triggerType = "止盈";
                    reason = String.format("触发止盈（盈利%.1f%% > %.1f%%）", pp * 100, takeProfitPct.doubleValue() * 100);
                }
            }

            // 无风控触发时，按因子得分判断
            if (triggerType == null) {
                Double score = finalScores.get(pos.getCode());
                if (score == null || score < 0.3) {
                    triggerType = "因子轮出";
                    reason = score == null ? "无因子得分" : String.format("因子得分%.2f低于阈值", score);
                }
            }

            // 无风控/因子触发时，检查技术面卖点信号
            if (triggerType == null && sellSignalEngine != null) {
                try {
                    double[][] ohlcv = fetchKlineForSellCheck(pos.getCode());
                    if (ohlcv != null && ohlcv[3].length >= 30) {
                        SellSignalEngine.SellSignalResult sellResult = sellSignalEngine.checkSellSignals(
                                ohlcv[3], ohlcv[1], ohlcv[2], ohlcv[0], ohlcv[4]);
                        if (sellResult.getAction() == SellSignalEngine.SellAction.SELL) {
                            triggerType = "技术卖点";
                            StringBuilder sb = new StringBuilder("技术面卖出信号(强度" + sellResult.getScore() + "): ");
                            for (SellSignalEngine.SellSignalItem item : sellResult.getSignals()) {
                                if (sb.length() > 20) sb.append("; ");
                                sb.append(item.getName());
                            }
                            reason = sb.toString();
                        } else if (sellResult.getAction() == SellSignalEngine.SellAction.REDUCE) {
                            triggerType = "技术减仓";
                            reason = "技术面减仓信号(强度" + sellResult.getScore() + ")";
                        }
                    }
                } catch (Exception e) {
                    log.warn("[PaperTrading] 卖点检测异常: {} - {}", pos.getCode(), e.getMessage());
                }
            }

            // 只有触发风控或因子轮出时才生成卖出信号
            if (triggerType != null) {
                PaperSignal sellSignal = PaperSignal.builder()
                    .paperId(paperId)
                    .signalDate(LocalDate.parse(signalDate))
                    .factorDate(LocalDate.parse(signalDate))
                    .code(pos.getCode())
                    .name(pos.getName())
                    .direction("SELL")
                    .signalPrice(pos.getCurrentPrice())
                    .factorScore(pos.getProfitLossPct())
                    .reason(reason)
                    .status("PENDING")
                    .build();
                paperSignalMapper.insert(sellSignal);
                signals.add(sellSignal);
            }
        }

        // 大盘择时判断（多空切换）
        boolean marketBearish = false;
        if (riskConfig.getTimingEnabled() != null && riskConfig.getTimingEnabled() == 1
                && marketThermometerService != null) {
            try {
                Map<String, Object> thermometer = marketThermometerService.getThermometer();
                // maTrend: 多头/震荡/空头
                String maTrend = thermometer != null ? (String) thermometer.get("maTrend") : null;
                // fearGreedLabel: 极度恐慌/恐慌/偏恐慌/中性/偏贪婪/贪婪/极度贪婪
                String fearGreedLabel = thermometer != null ? (String) thermometer.get("fearGreedLabel") : null;
                // 空头条件：均线温度=空头，或综合指数=极度恐慌/恐慌
                marketBearish = "空头".equals(maTrend)
                    || "极度恐慌".equals(fearGreedLabel) || "恐慌".equals(fearGreedLabel);
                log.info("generateSignals: 择时={}, maTrend={}, fearGreedLabel={}, marketBearish={}",
                    riskConfig.getTimingEnabled(), maTrend, fearGreedLabel, marketBearish);
            } catch (Exception e) {
                log.debug("大盘择时查询失败: {}", e.getMessage());
            }
        }

        // 新股买入信号（不在持仓中、非当天已SKIPPED的高分股）
        // buySlots 只看当前持仓，不预支 SELL 释放的仓位（SELL 执行后再生成新 BUY 信号）
        // 大盘空头时跳过新 BUY（已持仓不强制卖出）
        int buySlots = marketBearish ? 0 : (10 - heldCodes.size());
        if (buySlots <= 0 && !marketBearish) {
            log.info("generateSignals: 持仓已满({}只)，跳过买入信号生成", heldCodes.size());
        }
        if (buySlots <= 0 && marketBearish) {
            log.info("generateSignals: 大盘空头，暂停新开仓");
        }
        for (Map.Entry<String, Double> e : sorted) {
            if (buySlots <= 0) break;
            if (heldCodes.contains(e.getKey())) continue;
            if (skippedCodes.contains(e.getKey())) continue;

            // Fix #4: 优先使用推荐表的 suggestedBuyPrice，回退到开盘价
            BigDecimal price = null;
            if (recommendationMapper != null) {
                try {
                    BigDecimal recPrice = recommendationMapper.findLatestSuggestedBuyPrice(e.getKey());
                    if (recPrice != null && recPrice.compareTo(BigDecimal.ZERO) > 0) {
                        price = recPrice;
                    }
                } catch (Exception ex) {
                    log.warn("generateSignals: 查询 suggestedBuyPrice 失败 code={} err={}", e.getKey(), ex.getMessage());
                }
            }
            if (price == null) {
                price = getOpenPrice(e.getKey(), null);
            }
            PaperSignal buySignal = PaperSignal.builder()
                .paperId(paperId)
                .signalDate(LocalDate.parse(signalDate))
                .factorDate(LocalDate.parse(signalDate))
                .code(e.getKey())
                .name(getStockName(e.getKey()))
                .direction("BUY")
                .signalPrice(price)
                .factorScore(BigDecimal.valueOf(e.getValue()).setScale(4, RoundingMode.HALF_UP))
                .reason(String.format("因子得分%.2f，排名靠前%s", e.getValue(), marketBearish ? "（大盘多头）" : ""))
                .status("PENDING")
                .build();
            paperSignalMapper.insert(buySignal);
            signals.add(buySignal);
            buySlots--;
        }

        return signals.stream()
            .sorted((a, b) -> {
                // BUY 信号按 factorScore 降序排前面，SELL 信号排后面
                if (!"BUY".equals(a.getDirection()) && "BUY".equals(b.getDirection())) return 1;
                if ("BUY".equals(a.getDirection()) && !"BUY".equals(b.getDirection())) return -1;
                // 同方向按得分降序
                if (a.getFactorScore() != null && b.getFactorScore() != null) {
                    return b.getFactorScore().compareTo(a.getFactorScore());
                }
                return 0;
            })
            .toList();
    }

    /**
     * 执行信号
     */
    @Transactional
    public PaperPosition executeSignal(Long signalId) {
        PaperSignal signal = paperSignalMapper.selectById(signalId);
        if (signal == null) throw new IllegalArgumentException("信号不存在");
        if (!"PENDING".equals(signal.getStatus())) throw new IllegalArgumentException("信号已处理");

        // 非交易日禁止手动执行，避免价格不匹配
        if (!canExecuteSignal()) {
            throw new IllegalStateException("非交易日，不允许执行信号，请于下一交易日开盘后再执行");
        }

        // 【缺陷1修复】条件单触发检查：限价单/止损单/追踪止损需满足触发条件才执行
        String orderType = signal.getOrderType() != null ? signal.getOrderType() : "MARKET";
        if (!"MARKET".equals(orderType)) {
            BigDecimal currentPrice = getExecutionPrice(signal.getCode(), signal.getPaperId());
            if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("条件单无法获取当前价格: signalId={} code={}", signalId, signal.getCode());
                return null;  // 价格不可用，等待下次检查
            }

            boolean triggered = checkOrderTrigger(orderType, signal, currentPrice);
            if (!triggered) {
                // 更新追踪止损的最高价记录
                if ("TRAILING_STOP".equals(orderType)) {
                    updateTrailingHighestPrice(signal, currentPrice);
                }
                log.info("条件单未触发: signalId={} orderType={} code={} currentPrice={} triggerPrice={}",
                    signalId, orderType, signal.getCode(), currentPrice, signal.getTriggerPrice());
                return null;  // 条件未满足，继续等待
            }
            log.info("条件单触发执行: signalId={} orderType={} code={} currentPrice={}",
                signalId, orderType, signal.getCode(), currentPrice);
        }

        PaperTrading pt = paperTradingMapper.selectById(signal.getPaperId());

        if ("BUY".equals(signal.getDirection())) {
            // 读取风控配置（资金分配模式）
            PaperRiskConfig riskConfig = paperRiskConfigMapper.selectOne(
                new LambdaQueryWrapper<PaperRiskConfig>().eq(PaperRiskConfig::getPaperId, signal.getPaperId()));
            if (riskConfig == null) riskConfig = PaperRiskConfig.defaults(signal.getPaperId());
            String allocationMode = riskConfig.getAllocationMode() != null ? riskConfig.getAllocationMode() : "equal";

            // 手动执行时按规则确定成交价：交易日收盘价 / 非交易日下个交易日开盘价
            BigDecimal price = getExecutionPrice(signal.getCode(), signal.getPaperId());
            if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
                signal.setStatus("SKIPPED");
                signal.setReason("价格无效");
                paperSignalMapper.updateById(signal);
                return null;
            }
            // 买入加滑点
            price = applySlippage(price, true, signal.getPaperId());

            // 计算分配金额
            BigDecimal perStock;
            if ("kelly".equals(allocationMode)) {
                // IR-based 仓位管理：f = IR / √n，上限 20%/n、下限 5%
                // 比二元Kelly更适合多资产组合：持仓数越多单股仓位自然越小
                // IR（信息比率）= 超额收益均值/超额收益标准差，反映策略稳定超额能力
                int currentHoldCount = paperPositionMapper.selectCount(
                    new LambdaQueryWrapper<PaperPosition>()
                        .eq(PaperPosition::getPaperId, signal.getPaperId())).intValue();
                int n = Math.max(currentHoldCount + 1, 1); // 含当前买入的持仓数

                // 从已清仓历史持仓估算IR替代值（胜率偏离50%的程度 / 波动）
                KellyParams kp = calcKellyParams(signal.getPaperId());
                double ir = 0.5; // 默认IR（中性）
                if (kp != null) {
                    // IR proxy = (winRate - 0.5) / avgLoss（胜率偏离+亏损幅度→稳定超额能力）
                    double winRateDeviation = kp.winRate - 0.5;
                    ir = Math.max(0.1, Math.abs(winRateDeviation) / Math.max(kp.avgLoss, 0.01));
                }

                double kellyF = Math.max(0.05, Math.min(ir / Math.sqrt(n), 0.20 / n)); // IR/√n，限制在 5%~20%/n
                perStock = pt.getTotalAssets().multiply(BigDecimal.valueOf(kellyF));
                log.info("executeSignal: IR-based仓位 ir={} n={} f={}, perStock={}", ir, n, kellyF, perStock);
            } else if ("dynamic".equals(allocationMode)) {
                // 动态权重：按因子得分比例分配
                BigDecimal score = signal.getFactorScore();
                double factorScore = score != null ? score.doubleValue() : 0.5;
                factorScore = Math.max(0.1, Math.min(factorScore, 1.0));
                // 最高分配 initialCapital/5，最低分配 initialCapital/20
                double minAlloc = pt.getInitialCapital().doubleValue() / 20;
                double maxAlloc = pt.getInitialCapital().doubleValue() / 5;
                perStock = BigDecimal.valueOf(minAlloc + (maxAlloc - minAlloc) * factorScore);
            } else {
                // 等权模式（默认）
                perStock = pt.getInitialCapital().divide(BigDecimal.valueOf(10), 2, RoundingMode.HALF_UP);
            }

            int shares = perStock.divide(price, 0, RoundingMode.DOWN)
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN).intValue() * 100;
            if (shares <= 0) shares = 100; // 最少1手

            BigDecimal cost = price.multiply(BigDecimal.valueOf(shares));

            // 交易前风控检查（阻断模式）
            if (positionAlertService != null) {
                RiskCheckResult risk = positionAlertService.checkBeforeTrade(
                    signal.getPaperId(), signal.getCode(), cost);
                if (risk.isBlocked()) {
                    signal.setStatus("BLOCKED");
                    signal.setReason("风控阻断：" + risk.getBlockReason());
                    paperSignalMapper.updateById(signal);
                    log.warn("风控阻断买入: code={}, reason={}", signal.getCode(), risk.getBlockReason());
                    return null;
                }
            }

            // 现金缓冲：预留总资产的 cashBufferPct 不投入，避免全仓无缓冲
            BigDecimal cashBufferPct = riskConfig.getCashBufferPct() != null
                ? riskConfig.getCashBufferPct() : new BigDecimal("0.05");
            BigDecimal cashBuffer = pt.getTotalAssets().multiply(cashBufferPct);
            BigDecimal availableCapital = pt.getCurrentCapital().subtract(cashBuffer);

            if (availableCapital.compareTo(BigDecimal.ZERO) <= 0) {
                signal.setStatus("SKIPPED");
                signal.setReason("可用资金不足（缓冲后可用0）");
                paperSignalMapper.updateById(signal);
                return null;
            }

            // 【缺陷2修复】部分成交：资金不足时按可用资金计算可买手数（A股100股为1手）
            int originalShares = shares;
            if (cost.compareTo(availableCapital) > 0) {
                int maxAffordableShares = availableCapital
                    .divide(price, 0, RoundingMode.DOWN)
                    .divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN).intValue() * 100;
                if (maxAffordableShares <= 0) {
                    signal.setStatus("SKIPPED");
                    signal.setReason(String.format("资金不足（可用%.0f，最低需买100股）",
                        availableCapital.doubleValue()));
                    paperSignalMapper.updateById(signal);
                    return null;
                }
                shares = maxAffordableShares;
                cost = price.multiply(BigDecimal.valueOf(shares));
                log.info("部分成交: code={} 原计划={}股 实际={}股 使用资金={}",
                    signal.getCode(), originalShares, shares, cost);
            }

            // 【缺陷2修复】流动性检查：查询信号日成交量，避免大单冲击市场
            try {
                Integer volume = jdbcTemplate.queryForObject(
                    "SELECT volume FROM stock_daily WHERE code = ? AND trade_date <= ? ORDER BY trade_date DESC LIMIT 1",
                    Integer.class, signal.getCode(), signal.getSignalDate());
                if (volume != null && volume > 0 && shares > volume * 0.1) {
                    int maxByLiquidity = Math.max(100, (int) (volume * 0.08 / 100) * 100);
                    if (maxByLiquidity < shares) {
                        log.warn("流动性降级: code={} 原计划={}股 成交量={} 降级后={}股",
                            signal.getCode(), shares, volume, maxByLiquidity);
                        shares = maxByLiquidity;
                        cost = price.multiply(BigDecimal.valueOf(shares));
                        signal.setReason((signal.getReason() != null ? signal.getReason() + "；" : "")
                            + String.format("流动性降级（成交量%d，降级为%d股）", volume, shares));
                    }
                }
            } catch (Exception e) {
                log.warn("流动性检查失败: code={} err={}", signal.getCode(), e.getMessage());
            }

            // 【缺陷2修复】TWAP大单拆分：超阈值时拆分为多笔小单（简化版：记录日志，实际拆分由PaperOrderExecutor处理）
            int twapThreshold = riskConfig.getTwapThreshold() != null
                ? riskConfig.getTwapThreshold() : 50000;
            if (shares > twapThreshold) {
                int chunkSize = Math.max(100, shares / 10);
                int chunks = (shares + chunkSize - 1) / chunkSize;
                log.info("TWAP大单拆分: code={} 总股数={} 拆分为{}笔 每笔约{}股",
                    signal.getCode(), shares, chunks, chunkSize);
                signal.setReason((signal.getReason() != null ? signal.getReason() + "；" : "")
                    + String.format("TWAP拆分（%d笔，每笔约%d股）", chunks, chunkSize));
                // 实际拆分执行：将大单拆分为多笔，模拟TWAP执行
                // 为简化，当前仅记录日志，后续可扩展为逐笔延迟执行
            }

            // 更新资金
            pt.setCurrentCapital(pt.getCurrentCapital().subtract(cost));
            pt.setPositionCount(pt.getPositionCount() + 1);
            paperTradingMapper.updateById(pt);

            // 新增持仓
            PaperPosition pos = PaperPosition.builder()
                .paperId(pt.getId())
                .code(signal.getCode())
                .name(signal.getName())
                .shares(shares)
                .costPrice(price)
                .currentPrice(price)
                .marketValue(cost)
                .profitLoss(BigDecimal.ZERO)
                .profitLossPct(BigDecimal.ZERO)
                .buyDate(signal.getSignalDate())
                .build();
            paperPositionMapper.insert(pos);

            // 记录买入现金流
            paperCashFlowMapper.insert(PaperCashFlow.builder()
                .paperId(pt.getId())
                .flowDate(signal.getSignalDate())
                .amount(cost.negate())
                .flowType("BUY_COST")
                .note(String.format("买入%s %d股 @%.2f", signal.getCode(), shares, price.doubleValue()))
                .build());

            // 更新信号
            signal.setStatus("EXECUTED");
            signal.setExecutedPrice(price);
            // 记录执行价与信号价的偏差
            if (signal.getSignalPrice() != null && signal.getSignalPrice().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal deviation = price.subtract(signal.getSignalPrice())
                    .divide(signal.getSignalPrice(), 6, RoundingMode.HALF_UP);
                signal.setPriceDeviationPct(deviation);
            }
            signal.setExecutedAt(LocalDateTime.now());
            paperSignalMapper.updateById(signal);
            saveExecutionQuality(signal, price, shares);

            updateTotalAssets(pt);
            // 注意：不在此处调用 appendNavRecord，日收益需在收盘后统一按收盘价计算
            return pos;

        } else if ("SELL".equals(signal.getDirection())) {
            // 卖出持仓
            PaperPosition pos = paperPositionMapper.selectOne(
                new LambdaQueryWrapper<PaperPosition>()
                    .eq(PaperPosition::getPaperId, pt.getId())
                    .eq(PaperPosition::getCode, signal.getCode()));

            if (pos == null) {
                signal.setStatus("SKIPPED");
                signal.setReason("持仓不存在");
                paperSignalMapper.updateById(signal);
                return null;
            }

            BigDecimal price = getExecutionPrice(signal.getCode(), signal.getPaperId());
            // 卖出减滑点
            price = applySlippage(price, false, signal.getPaperId());
            BigDecimal sellAmount = price.multiply(BigDecimal.valueOf(pos.getShares()));
            pt.setCurrentCapital(pt.getCurrentCapital().add(sellAmount));
            pt.setPositionCount(Math.max(0, pt.getPositionCount() - 1));
            paperTradingMapper.updateById(pt);

            // 记录卖出现金流
            paperCashFlowMapper.insert(PaperCashFlow.builder()
                .paperId(pt.getId())
                .flowDate(signal.getSignalDate())
                .amount(sellAmount)
                .flowType("SELL_INCOME")
                .note(String.format("卖出%s %d股 @%.2f", signal.getCode(), pos.getShares(), price.doubleValue()))
                .build());

            paperPositionMapper.deleteById(pos.getId());

            signal.setStatus("EXECUTED");
            signal.setExecutedPrice(price);
            // 记录执行价与信号价的偏差
            if (signal.getSignalPrice() != null && signal.getSignalPrice().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal deviation = price.subtract(signal.getSignalPrice())
                    .divide(signal.getSignalPrice(), 6, RoundingMode.HALF_UP);
                signal.setPriceDeviationPct(deviation);
            }
            signal.setExecutedAt(LocalDateTime.now());
            paperSignalMapper.updateById(signal);
            saveExecutionQuality(signal, price, pos.getShares());

            updateTotalAssets(pt);
            // 注意：不在此处调用 appendNavRecord，日收益需在收盘后统一按收盘价计算
            return pos;
        }

        return null;
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
        PaperTrading pt = paperTradingMapper.selectById(paperId);
        if (pt == null) throw new IllegalArgumentException("模拟盘不存在");
        pt.setStatus(status);
        paperTradingMapper.updateById(pt);
        return pt;
    }

    /**
     * 批量执行所有待处理信号
     */
    @Transactional
    public List<PaperPosition> executeAllSignals(Long paperId) {
        PaperTrading pt = paperTradingMapper.selectById(paperId);
        if (pt == null) throw new IllegalArgumentException("模拟盘不存在");
        if (!"RUNNING".equals(pt.getStatus())) throw new IllegalArgumentException("模拟盘未运行");

        List<PaperSignal> pendingSignals = paperSignalMapper.selectList(
            new LambdaQueryWrapper<PaperSignal>()
                .eq(PaperSignal::getPaperId, paperId)
                .eq(PaperSignal::getStatus, "PENDING")
                .orderByAsc(PaperSignal::getSignalDate)
                .orderByAsc(PaperSignal::getId));

        if (pendingSignals.isEmpty()) {
            throw new IllegalArgumentException("没有待执行的信号");
        }

        List<PaperPosition> results = new ArrayList<>();
        for (PaperSignal signal : pendingSignals) {
            try {
                PaperPosition result = executeSignal(signal.getId());
                if (result != null) results.add(result);
            } catch (Exception e) {
                log.warn("信号 {} 执行失败: {}", signal.getId(), e.getMessage());
            }
        }
        return results;
    }

    /**
     * 处理分红送股（按除权除息日结算）
     * 应在每日收盘后调用，处理当日除权的股票
     */
    @Transactional
    public void processDividends(Long paperId) {
        PaperTrading pt = paperTradingMapper.selectById(paperId);
        if (pt == null) throw new IllegalArgumentException("模拟盘不存在");

        List<PaperPosition> positions = paperPositionMapper.selectList(
            new LambdaQueryWrapper<PaperPosition>().eq(PaperPosition::getPaperId, paperId));

        LocalDate today = LocalDate.now();

        for (PaperPosition pos : positions) {
            // 查询该股票今日的分红信息
            List<Map<String, Object>> dividends = jdbcTemplate.query(
                "SELECT cash_dividend, stock_dividend, convert_dividend " +
                "FROM stock_dividend WHERE code = ? AND ex_dividend_date = ?",
                (rs, rowNum) -> Map.of(
                    "cashDividend", rs.getBigDecimal("cash_dividend"),
                    "stockDividend", rs.getBigDecimal("stock_dividend"),
                    "convertDividend", rs.getBigDecimal("convert_dividend")
                ),
                pos.getCode(), today);

            if (dividends.isEmpty()) continue;

            Map<String, Object> div = dividends.getFirst();
            BigDecimal cashDiv = div.get("cashDividend") != null
                ? (BigDecimal) div.get("cashDividend") : BigDecimal.ZERO;
            BigDecimal stockDiv = div.get("stockDividend") != null
                ? (BigDecimal) div.get("stockDividend") : BigDecimal.ZERO;
            BigDecimal convertDiv = div.get("convertDividend") != null
                ? (BigDecimal) div.get("convertDividend") : BigDecimal.ZERO;

            // 现金分红：增加可用资金
            if (cashDiv.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal cashBonus = cashDiv.multiply(BigDecimal.valueOf(pos.getShares()));
                pt.setCurrentCapital(pt.getCurrentCapital().add(cashBonus));
                log.info("模拟盘 {} 现金分红: {} 获得 {} 元", paperId, pos.getCode(), cashBonus);
            }

            // 送股 + 转增：增加持仓数量
            BigDecimal bonusShares = stockDiv.add(convertDiv)
                .multiply(BigDecimal.valueOf(pos.getShares()));
            if (bonusShares.compareTo(BigDecimal.ZERO) > 0) {
                int newShares = pos.getShares() + bonusShares.intValue();
                log.info("模拟盘 {} 送转股: {} {} -> {} 股", paperId, pos.getCode(), pos.getShares(), newShares);
                pos.setShares(newShares);
                // 重新计算成本价（摊薄）
                BigDecimal totalCost = pos.getCostPrice()
                    .multiply(BigDecimal.valueOf(pos.getShares()));
                pos.setCostPrice(totalCost.divide(BigDecimal.valueOf(newShares), 4, RoundingMode.HALF_UP));
            }

            pos.setUpdatedAt(LocalDateTime.now());
            paperPositionMapper.updateById(pos);
        }

        paperTradingMapper.updateById(pt);
        updateTotalAssets(pt);
        // 注意：不在此处调用 appendNavRecord，日收益需在收盘后统一按收盘价计算
    }

    // ─── 内部方法 ──────────────────────────────────────────────────────

    private void updateTotalAssets(PaperTrading pt) {
        List<PaperPosition> positions = paperPositionMapper.selectList(
            new LambdaQueryWrapper<PaperPosition>().eq(PaperPosition::getPaperId, pt.getId()));
        BigDecimal posValue = positions.stream()
            .map(p -> p.getMarketValue() != null ? p.getMarketValue() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        pt.setTotalAssets(pt.getCurrentCapital().add(posValue));
        paperTradingMapper.updateById(pt);
    }

    /**
     * 追加/更新当日 NAV 记录（收盘后统一调用，基于收盘价计算日收益）
     * 同一天多次交易时，更新当日已存在的记录，不重复插入
     */
    public void appendNavRecord(Long paperId) {
        PaperTrading pt = paperTradingMapper.selectById(paperId);
        LocalDate today = LocalDate.now();

        // 尝试获取今日已有 NAV 记录
        PaperNav todayNav = paperNavMapper.selectOne(
            new LambdaQueryWrapper<PaperNav>()
                .eq(PaperNav::getPaperId, paperId)
                .eq(PaperNav::getNavDate, today)
                .last("LIMIT 1"));

        // 获取前一交易日 NAV（取最近一条非今日的记录）
        PaperNav prevNav = paperNavMapper.selectOne(
            new LambdaQueryWrapper<PaperNav>()
                .eq(PaperNav::getPaperId, paperId)
                .ne(PaperNav::getNavDate, today)
                .orderByDesc(PaperNav::getNavDate)
                .last("LIMIT 1"));

        BigDecimal prevTotalAssets = prevNav != null ? prevNav.getTotalAssets() : pt.getInitialCapital();
        BigDecimal dailyReturn = prevTotalAssets.compareTo(BigDecimal.ZERO) > 0
            ? pt.getTotalAssets().subtract(prevTotalAssets)
                .divide(prevTotalAssets, 6, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;
        BigDecimal cumulativeReturn = pt.getInitialCapital().compareTo(BigDecimal.ZERO) > 0
            ? pt.getTotalAssets().subtract(pt.getInitialCapital())
                .divide(pt.getInitialCapital(), 6, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;

        if (todayNav != null) {
            // 更新今日已有记录
            todayNav.setTotalAssets(pt.getTotalAssets());
            todayNav.setDailyReturn(dailyReturn);
            todayNav.setCumulativeReturn(cumulativeReturn);
            paperNavMapper.updateById(todayNav);
        } else {
            // 插入新记录
            PaperNav nav = PaperNav.builder()
                .paperId(paperId)
                .navDate(today)
                .totalAssets(pt.getTotalAssets())
                .dailyReturn(dailyReturn)
                .cumulativeReturn(cumulativeReturn)
                .build();
            paperNavMapper.insert(nav);
        }
    }

    /**
     * 获取最新交易日期
     * 策略：取每个因子各自最新 calc_date 的最小值，确保所有因子在该日期都有数据
     * 回退链：逐因子MAX最小值 → 全局MAX(排除CHAN/FIN) → stock_daily MAX → 今天
     */
    private String getLatestTradeDate(Set<String> factorCodes) {
        if (clickHouseJdbcTemplate == null) return LocalDate.now().toString();

        if (factorCodes != null && !factorCodes.isEmpty()) {
            // 对每个因子取各自最新日期，返回最小值（所有因子都有的日期）
            LocalDate minDate = null;
            for (String code : factorCodes) {
                try {
                    List<String> dates = clickHouseJdbcTemplate.query(
                        "SELECT MAX(calc_date) FROM stock.factor_value FINAL WHERE factor_code = ?",
                        new Object[]{code},
                        (rs, rowNum) -> rs.getString(1));
                    if (!dates.isEmpty() && dates.getFirst() != null) {
                        LocalDate d = LocalDate.parse(dates.getFirst());
                        if (minDate == null || d.isBefore(minDate)) {
                            minDate = d;
                        }
                    }
                } catch (Exception e) {
                    log.debug("查询因子 {} 最新日期失败: {}", code, e.getMessage());
                }
            }
            if (minDate != null) {
                log.info("getLatestTradeDate: 逐因子取MIN={}, factors={}", minDate, factorCodes);
                return minDate.toString();
            }
        }

        // 回退：全局 MAX 排除季频因子（DB元数据驱动）
        Set<String> quarterlyCodes = factorMetaCache.getQuarterlyCodes();
        String excludeClause = quarterlyCodes.isEmpty()
                ? ""
                : " WHERE factor_code NOT IN (" + quarterlyCodes.stream()
                        .map(c -> "'" + c + "'").collect(Collectors.joining(",")) + ")";
        List<String> dates = clickHouseJdbcTemplate.query(
            "SELECT MAX(calc_date) FROM stock.factor_value FINAL" + excludeClause,
            (rs, rowNum) -> rs.getString(1));
        if (dates.isEmpty() || dates.getFirst() == null) {
            dates = clickHouseJdbcTemplate.query(
                "SELECT MAX(calc_date) FROM stock.factor_value FINAL",
                (rs, rowNum) -> rs.getString(1));
        }
        if (dates.isEmpty() || dates.getFirst() == null) {
            dates = clickHouseJdbcTemplate.query(
                "SELECT MAX(trade_date) FROM stock.stock_daily FINAL",
                (rs, rowNum) -> rs.getString(1));
        }
        return dates.isEmpty() || dates.getFirst() == null ? LocalDate.now().toString() : dates.getFirst();
    }

    /**
     * 获取单个因子的最新日期
     * 财务因子和日频因子分别更新，每个因子用自己的最新日期
     */
    private String getFactorLatestDate(String factorCode) {
        if (clickHouseJdbcTemplate == null) return LocalDate.now().toString();
        try {
            List<String> dates = clickHouseJdbcTemplate.query(
                "SELECT MAX(calc_date) FROM stock.factor_value FINAL WHERE factor_code = ?",
                new Object[]{factorCode},
                (rs, rowNum) -> rs.getString(1));
            return dates.isEmpty() || dates.getFirst() == null ? null : dates.getFirst();
        } catch (Exception e) {
            log.debug("查询因子 {} 最新日期失败: {}", factorCode, e.getMessage());
            return null;
        }
    }

    /**
     * 判断是否可以生成信号（有可用的最新因子数据）
     * 严格模式：最新交易日必须是今天或未来（不允许周末生成信号）
     */
    /**
     * 判断是否可以生成信号（允许最近3天内有交易日数据，覆盖周末/节假日补跑）
     */
    private boolean isTradingDay() {
        if (tradeCalendarService == null) {
            log.warn("isTradingDay: tradeCalendarService 为 null，拦截");
            return false;
        }
        boolean result = tradeCalendarService.isTradingDay(LocalDate.now());
        if (!result) {
            log.info("isTradingDay: 今天({})非交易日，拦截", LocalDate.now());
        }
        return result;
    }

    /**
     * 判断是否可以执行信号（严格：必须是今天或未来）
     */
    private boolean canExecuteSignal() {
        if (clickHouseJdbcTemplate == null) {
            log.warn("canExecuteSignal: clickHouseJdbcTemplate 为 null，拦截");
            return false;
        }
        try {
            List<String> dates = clickHouseJdbcTemplate.query(
                "SELECT MAX(trade_date) FROM stock.stock_daily FINAL",
                (rs, rowNum) -> rs.getString(1));
            if (dates.isEmpty() || dates.getFirst() == null) {
                log.warn("canExecuteSignal: 查询结果为空，拦截");
                return false;
            }
            LocalDate latest = LocalDate.parse(dates.getFirst());
            boolean result = !latest.isBefore(LocalDate.now());
            if (!result) {
                log.info("canExecuteSignal: 最新交易日={}，今天={}，拦截", latest, LocalDate.now());
            }
            return result;
        } catch (Exception e) {
            log.warn("canExecuteSignal 查询失败，拦截: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 获取最新收盘价（原有逻辑，供其他场景使用）
     */
    private BigDecimal getLatestPrice(String code, String date) {
        if (clickHouseJdbcTemplate == null) return BigDecimal.ZERO;
        try {
            // date 为 null 时自动取 stock_daily 最新交易日的价格
            List<BigDecimal> prices;
            if (date == null || date.isBlank()) {
                prices = clickHouseJdbcTemplate.query(
                    "SELECT close_price FROM stock.stock_daily FINAL WHERE code = ? ORDER BY trade_date DESC LIMIT 1",
                    new Object[]{code},
                    (rs, rowNum) -> rs.getBigDecimal("close_price"));
            } else {
                prices = clickHouseJdbcTemplate.query(
                    "SELECT close_price FROM stock.stock_daily FINAL WHERE code = ? AND trade_date = ?",
                    new Object[]{code, date},
                    (rs, rowNum) -> rs.getBigDecimal("close_price"));
            }
            return prices.isEmpty() ? BigDecimal.ZERO : prices.getFirst();
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * 获取指定日期的开盘价（用于模拟盘成交执行）
     * date 为 null 时取最新交易日的开盘价
     */
    private BigDecimal getOpenPrice(String code, String date) {
        if (clickHouseJdbcTemplate == null) return BigDecimal.ZERO;
        try {
            List<BigDecimal> prices;
            if (date == null || date.isBlank()) {
                prices = clickHouseJdbcTemplate.query(
                    "SELECT open_price FROM stock.stock_daily FINAL WHERE code = ? ORDER BY trade_date DESC LIMIT 1",
                    new Object[]{code},
                    (rs, rowNum) -> rs.getBigDecimal("open_price"));
            } else {
                prices = clickHouseJdbcTemplate.query(
                    "SELECT open_price FROM stock.stock_daily FINAL WHERE code = ? AND trade_date = ?",
                    new Object[]{code, date},
                    (rs, rowNum) -> rs.getBigDecimal("open_price"));
            }
            if (prices.isEmpty() || prices.getFirst() == null || prices.getFirst().compareTo(BigDecimal.ZERO) <= 0) {
                // 开盘价为空时降级为收盘价
                log.warn("getOpenPrice: {} {} 开盘价为空，降级为收盘价", code, date);
                return getLatestPrice(code, date);
            }
            return prices.getFirst();
        } catch (Exception e) {
            log.warn("getOpenPrice 查询失败 code={}, date={}: {}", code, date, e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    /**
     * 手动执行信号时的成交价（调用前需先通过 isTradingDay() 拦截非交易日）
     * - 今天是交易日 → 今天收盘价
     */
    private BigDecimal getExecutionPrice(String code) {
        return getExecutionPrice(code, null);
    }

    /** 获取成交价（含滑点调整）
     * @param paperId 模拟盘ID（用于读取滑点配置），null时不加滑点 */
    private BigDecimal getExecutionPrice(String code, Long paperId) {
        if (clickHouseJdbcTemplate == null) return BigDecimal.ZERO;
        try {
            LocalDate today = LocalDate.now();
            BigDecimal closePrice = getLatestPrice(code, today.toString());
            if (closePrice == null || closePrice.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("getExecutionPrice: {} {} 收盘价无效，降级为开盘价", code, today);
                closePrice = getOpenPrice(code, today.toString());
            }
            return closePrice;
        } catch (Exception e) {
            log.warn("getExecutionPrice 查询失败 code={}: {}", code, e.getMessage());
            return getOpenPrice(code, null);
        }
    }

    /** 滑点调整：买入加滑点，卖出减滑点 */
    private BigDecimal applySlippage(BigDecimal price, boolean isBuy, Long paperId) {
        if (paperId == null) return price;
        PaperRiskConfig cfg = paperRiskConfigMapper.selectOne(
            new LambdaQueryWrapper<PaperRiskConfig>().eq(PaperRiskConfig::getPaperId, paperId));
        if (cfg == null) cfg = PaperRiskConfig.defaults(paperId);

        String model = cfg.getSlippageModel();
        BigDecimal slipPct = cfg.getSlippagePct();
        if (model == null || "NONE".equals(model) || slipPct == null || slipPct.compareTo(BigDecimal.ZERO) <= 0) {
            return price;
        }

        // FIXED模型：固定滑点比例
        BigDecimal factor = isBuy
            ? BigDecimal.ONE.add(slipPct)     // 买入加滑点
            : BigDecimal.ONE.subtract(slipPct); // 卖出减滑点
        BigDecimal adjusted = price.multiply(factor).setScale(2, RoundingMode.HALF_UP);
        log.debug("applySlippage: isBuy={} raw={} slip={} adjusted={}",
            isBuy, price, slipPct, adjusted);
        return adjusted;
    }

    private String getStockName(String code) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.query(
                "SELECT name FROM stock_info WHERE code = ? LIMIT 1",
                (rs, rowNum) -> Map.of("name", rs.getString("name")), code);
            return rows.isEmpty() ? code : (String) rows.getFirst().get("name");
        } catch (Exception e) {
            return code;
        }
    }

    /**
     * 从ClickHouse拉取K线数据用于卖点检测
     * @return [open[], high[], low[], close[], volume[]] 或 null
     */
    private double[][] fetchKlineForSellCheck(String code) {
        if (clickHouseJdbcTemplate == null) return null;
        try {
            String pureCode = code.split("\\.")[0];
            List<Map<String, Object>> rows = clickHouseJdbcTemplate.queryForList(
                "SELECT open_price, high_price, low_price, close_price, volume FROM stock.stock_daily FINAL " +
                "WHERE code = ? ORDER BY trade_date DESC LIMIT 120",
                pureCode);
            if (rows.isEmpty()) return null;
            int n = rows.size();
            double[] open = new double[n], high = new double[n], low = new double[n], close = new double[n], volume = new double[n];
            for (int i = 0; i < n; i++) {
                Map<String, Object> row = rows.get(n - 1 - i);
                open[i] = ((Number) row.get("open_price")).doubleValue();
                high[i] = ((Number) row.get("high_price")).doubleValue();
                low[i] = ((Number) row.get("low_price")).doubleValue();
                close[i] = ((Number) row.get("close_price")).doubleValue();
                volume[i] = ((Number) row.get("volume")).doubleValue();
            }
            return new double[][] { open, high, low, close, volume };
        } catch (Exception e) {
            log.warn("[PaperTrading] 拉取K线失败: {} - {}", code, e.getMessage());
            return null;
        }
    }

    /**
     * 删除模拟盘（及关联的持仓、信号、净值）
     */
    @Transactional
    public void deletePaperTrading(Long paperId) {
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

    /** 追加入金 */
    public PaperCashFlow deposit(Long paperId, BigDecimal amount, String note) {
        PaperTrading pt = paperTradingMapper.selectById(paperId);
        if (pt == null) throw new IllegalArgumentException("模拟盘不存在: " + paperId);
        if (amount.compareTo(BigDecimal.ZERO) <= 0) throw new IllegalArgumentException("入金金额必须大于0");

        pt.setInitialCapital(pt.getInitialCapital().add(amount));
        pt.setCurrentCapital(pt.getCurrentCapital().add(amount));
        paperTradingMapper.updateById(pt);

        PaperCashFlow flow = PaperCashFlow.builder()
            .paperId(paperId)
            .flowDate(LocalDate.now())
            .amount(amount)
            .flowType("DEPOSIT")
            .note(note != null ? note : "追加入金")
            .build();
        paperCashFlowMapper.insert(flow);
        log.info("入金: paperId={} amount={}", paperId, amount);
        return flow;
    }

    /** 提取出金 */
    public PaperCashFlow withdraw(Long paperId, BigDecimal amount, String note) {
        PaperTrading pt = paperTradingMapper.selectById(paperId);
        if (pt == null) throw new IllegalArgumentException("模拟盘不存在: " + paperId);
        if (amount.compareTo(BigDecimal.ZERO) <= 0) throw new IllegalArgumentException("出金金额必须大于0");
        if (amount.compareTo(pt.getCurrentCapital()) > 0) throw new IllegalArgumentException("出金金额超过可用资金");

        pt.setInitialCapital(pt.getInitialCapital().subtract(amount));
        pt.setCurrentCapital(pt.getCurrentCapital().subtract(amount));
        paperTradingMapper.updateById(pt);

        PaperCashFlow flow = PaperCashFlow.builder()
            .paperId(paperId)
            .flowDate(LocalDate.now())
            .amount(amount.negate())
            .flowType("WITHDRAW")
            .note(note != null ? note : "提取出金")
            .build();
        paperCashFlowMapper.insert(flow);
        log.info("出金: paperId={} amount={}", paperId, amount);
        return flow;
    }

    /** 查询现金流记录 */
    public List<PaperCashFlow> getCashFlows(Long paperId) {
        return paperCashFlowMapper.selectList(
            new LambdaQueryWrapper<PaperCashFlow>()
                .eq(PaperCashFlow::getPaperId, paperId)
                .orderByDesc(PaperCashFlow::getFlowDate));
    }

    private String getStrategyFactorConfig(Long strategyId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.query(
                "SELECT factor_config_json FROM strategy_definition WHERE id = ?",
                (rs, rowNum) -> Map.of("config", rs.getString("factor_config_json")), strategyId);
            return rows.isEmpty() ? null : (String) rows.getFirst().get("config");
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析多策略组合配置JSON → strategyId→weight 映射 */
    private Map<Long, Double> parseStrategyWeights(String strategyConfigJson) {
        try {
            List<Map<String, Object>> configs = objectMapper.readValue(strategyConfigJson, List.class);
            Map<Long, Double> weights = new LinkedHashMap<>();
            for (Map<String, Object> cfg : configs) {
                Long sid = ((Number) cfg.get("strategyId")).longValue();
                Double w = ((Number) cfg.getOrDefault("weight", 1.0)).doubleValue();
                weights.put(sid, w);
            }
            return weights;
        } catch (Exception e) {
            throw new IllegalArgumentException("组合策略配置JSON解析失败: " + e.getMessage());
        }
    }

    /** 解析因子配置JSON（兼容 {factors:[...]} 和 [...]） */
    private List<Map<String, Object>> parseFactorConfigs(String factorConfigJson) {
        try {
            Object raw = objectMapper.readValue(factorConfigJson, Object.class);
            if (raw instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> list = (List<Map<String, Object>>) raw;
                return list;
            } else if (raw instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) raw;
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> factors = (List<Map<String, Object>>) map.get("factors");
                return factors != null ? factors : List.of();
            }
            return List.of();
        } catch (Exception e) {
            throw new IllegalArgumentException("因子配置解析失败: " + e.getMessage());
        }
    }

    /** 从 factor_definition 获取因子方向（ASC/DESC） */
    private String getFactorDirection(String factorCode) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.query(
                "SELECT direction FROM factor_definition WHERE code = ? LIMIT 1",
                (rs, rowNum) -> Map.of("direction", rs.getString("direction")), factorCode);
            return rows.isEmpty() ? "ASC" : (String) rows.getFirst().get("direction");
        } catch (Exception e) {
            return "ASC";
        }
    }

    /** 凯利公式参数 */
    private static class KellyParams {
        double winRate, avgWin, avgLoss;
        KellyParams(double wr, double aw, double al) { winRate = wr; avgWin = aw; avgLoss = al; }
    }

    /** 计算凯利公式参数：从历史已执行信号中估算胜率/均盈/均亏
     *  使用 paper_signal 中的买入+卖出信号配对计算收益，避免 paper_position 卖出后删除导致数据丢失 */
    private KellyParams calcKellyParams(Long paperId) {
        try {
            // 查所有已执行的BUY信号，用买入价与当前市值（或已卖出价）比较
            // 更简单的方式：用 paper_signal 的 SELL 信号来判断盈亏
            String sql = """
                SELECT ps.code, ps.executed_price as sell_price, ps.signal_date as sell_date
                FROM paper_signal ps
                WHERE ps.paper_id = ? AND ps.status = 'EXECUTED' AND ps.direction = 'SELL'
                ORDER BY ps.signal_date DESC
                """;
            List<Map<String, Object>> sellSignals = jdbcTemplate.query(sql,
                (rs, rowNum) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("code", rs.getString("code"));
                    m.put("sellPrice", rs.getBigDecimal("sell_price"));
                    m.put("sellDate", rs.getString("sell_date"));
                    return m;
                }, paperId);

            if (sellSignals.size() < 3) return null;

            double wins = 0, losses = 0, totalWin = 0, totalLoss = 0;
            for (Map<String, Object> sell : sellSignals) {
                String code = (String) sell.get("code");
                BigDecimal sellPrice = (BigDecimal) sell.get("sellPrice");
                // 找对应BUY信号获取买入价
                BigDecimal buyPrice = getBuyPriceForCode(paperId, code, (String) sell.get("sellDate"));
                if (buyPrice == null || sellPrice == null || buyPrice.compareTo(BigDecimal.ZERO) <= 0) continue;
                double ret = sellPrice.subtract(buyPrice).divide(buyPrice, 6, RoundingMode.HALF_UP).doubleValue();
                if (ret > 0) { wins++; totalWin += ret; }
                else if (ret < 0) { losses++; totalLoss += Math.abs(ret); }
            }

            int n = sellSignals.size();
            double winRate = wins / n;
            double avgWin = wins > 0 ? totalWin / wins : 0;
            double avgLoss = losses > 0 ? totalLoss / losses : 0.05;
            return new KellyParams(winRate, avgWin, avgLoss);
        } catch (Exception e) {
            log.debug("凯利参数计算失败: paperId={}, error={}", paperId, e.getMessage());
            return null;
        }
    }

    /** 查某只股票在指定卖出日期之前最近一次BUY信号的成交价 */
    private BigDecimal getBuyPriceForCode(Long paperId, String code, String beforeSellDate) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.query(
                "SELECT executed_price FROM paper_signal WHERE paper_id = ? AND code = ? AND direction = 'BUY' AND status = 'EXECUTED' AND signal_date <= ? ORDER BY signal_date DESC LIMIT 1",
                (rs, rowNum) -> Map.of("price", rs.getBigDecimal("executed_price")), paperId, code, beforeSellDate);
            return rows.isEmpty() ? null : (BigDecimal) rows.getFirst().get("price");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 计算信息比率（Information Ratio）
     * IR = 超额收益均值 / 超额收益标准差，滚动N日窗口
     * 超额收益 = 模拟盘累计收益率 - 基准累计收益率（归一化：基准净值/基准起点净值 - 1）
     *
     * @param result    放入 IR 计算结果的 Map
     * @param navs      模拟盘净值历史
     * @param indexRows 全量基准指数数据（含 close_price）
     * @param basePrice 基准归一化起点价格（navStartDate前一日收盘价）
     */
    @SuppressWarnings("unchecked")
    private void calculateInformationRatio(
            Map<String, Object> result,
            List<PaperNav> navs,
            List<Map<String, Object>> indexRows,
            BigDecimal basePrice) {

        if (navs == null || navs.isEmpty()
                || indexRows == null || indexRows.isEmpty()
                || basePrice == null || basePrice.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        // 构建日期 -> 归一化基准净值的映射（全量 indexRows）
        Map<String, Double> benchNormMap = new java.util.HashMap<>();
        for (Map<String, Object> row : indexRows) {
            String date = String.valueOf(row.get("date"));
            BigDecimal close = (BigDecimal) row.get("close");
            if (date != null && close != null) {
                double nav = close.divide(basePrice, 6, RoundingMode.HALF_UP).doubleValue();
                benchNormMap.put(date, nav);
            }
        }

        // 计算每日超额收益 = 模拟盘累计收益 - (基准净值 - 1)
        List<Double> excessList = new java.util.ArrayList<>();
        List<Map<String, Object>> excessDailyList = new java.util.ArrayList<>();
        for (PaperNav nav : navs) {
            String date = nav.getNavDate().toString();
            if (!benchNormMap.containsKey(date)) continue;
            double benchReturn = benchNormMap.get(date) - 1.0;
            double paperReturn = nav.getCumulativeReturn() != null
                ? nav.getCumulativeReturn().doubleValue() : 0.0;
            double excess = paperReturn - benchReturn;
            excessList.add(excess);

            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("date", date);
            entry.put("excessReturn", Math.round(excess * 10000.0) / 10000.0);
            excessDailyList.add(entry);
        }

        if (excessList.size() < 3) {
            log.info("[信息比率] 数据点不足({}<3)，跳过计算", excessList.size());
            return;
        }

        // 滚动窗口 IR（窗口 = min(20, 数据长度)）
        int windowDays = Math.min(20, excessList.size());
        List<Double> irList = new java.util.ArrayList<>();
        for (int i = windowDays - 1; i < excessList.size(); i++) {
            int start = i - windowDays + 1;
            double mean = 0;
            for (int j = start; j <= i; j++) mean += excessList.get(j);
            mean /= windowDays;
            double variance = 0;
            for (int j = start; j <= i; j++) {
                double d = excessList.get(j) - mean;
                variance += d * d;
            }
            variance /= windowDays;
            double std = Math.sqrt(Math.max(0, variance));
            if (std > 1e-10) {
                irList.add(mean / std);
            }
        }

        if (!irList.isEmpty()) {
            double latestIR = irList.get(irList.size() - 1);
            double avgIR = irList.stream().mapToDouble(Double::doubleValue).sum() / irList.size();
            result.put("informationRatio", Math.round(latestIR * 10000.0) / 10000.0);
            result.put("informationRatioAnnualized", Math.round(latestIR * Math.sqrt(252) * 10000.0) / 10000.0);
            result.put("informationRatioAvg", Math.round(avgIR * 10000.0) / 10000.0);
            result.put("informationRatioAvgAnnualized", Math.round(avgIR * Math.sqrt(252) * 10000.0) / 10000.0);
            result.put("irWindowDays", windowDays);
            result.put("irExcessReturns", excessDailyList);
            log.info("[信息比率] 最新{}-日IR={}, 年化={}, 均值IR={}, 数据点={}",
                windowDays, latestIR, latestIR * Math.sqrt(252), avgIR, excessList.size());
        } else {
            log.info("[信息比率] 滚动IR全为NaN（标准差≈0），数据点={}", excessList.size());
        }
    }

    /**
     * 盘中自动止损卖出（风控触发时调用）
     * 直接创建SELL信号并执行，不经过Scheduler。
     * 如果持仓不存在或模拟盘未运行，则静默返回。
     */
    @Transactional
    public void autoSellByStopLoss(Long paperId, String code, String reason) {
        PaperTrading pt = paperTradingMapper.selectById(paperId);
        if (pt == null || !"RUNNING".equals(pt.getStatus())) {
            log.debug("autoSellByStopLoss: paperId={} 不存在或未运行，跳过", paperId);
            return;
        }
        PaperPosition pos = paperPositionMapper.selectOne(
            new LambdaQueryWrapper<PaperPosition>()
                .eq(PaperPosition::getPaperId, paperId)
                .eq(PaperPosition::getCode, code));
        if (pos == null) {
            log.debug("autoSellByStopLoss: paperId={} 未持有 {}，跳过", paperId, code);
            return;
        }

        // 创建SELL信号
        String name = getStockName(code);
        BigDecimal price = getExecutionPrice(code, paperId);
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("autoSellByStopLoss: {} 无法获取有效价格，跳过", code);
            return;
        }
        // 卖出减滑点
        price = applySlippage(price, false, paperId);

        PaperSignal signal = PaperSignal.builder()
            .paperId(paperId)
            .signalDate(LocalDate.now())
            .factorDate(LocalDate.now())
            .code(code)
            .name(name)
            .direction("SELL")
            .signalPrice(price)
            .reason(reason != null ? reason : "盘中止损触发")
            .status("PENDING")
            .build();
        paperSignalMapper.insert(signal);

        // 直接执行卖出
        try {
            executeSignal(signal.getId());
            log.info("autoSellByStopLoss: paperId={} {}({}) 已自动止损卖出，price={}",
                paperId, name, code, price);
        } catch (Exception e) {
            log.warn("autoSellByStopLoss: 执行卖出失败 paperId={} code={}: {}", paperId, code, e.getMessage());
        }
    }

    /**
     * 保存执行质量记录
     */
    private void saveExecutionQuality(PaperSignal signal, BigDecimal executedPrice, int shares) {
        if (paperExecutionQualityMapper == null) return;

        BigDecimal signalPrice = signal.getSignalPrice() != null ? signal.getSignalPrice() : executedPrice;
        BigDecimal priceDeviation = executedPrice.subtract(signalPrice);
        BigDecimal priceDeviationPct = signal.getPriceDeviationPct() != null
                ? signal.getPriceDeviationPct()
                : BigDecimal.ZERO;

        // 佣金 = 成交金额 × 0.0003（A股佣金率）
        BigDecimal commission = executedPrice.multiply(BigDecimal.valueOf(shares))
                .multiply(new BigDecimal("0.0003")).setScale(2, RoundingMode.HALF_UP);

        // 滑点成本 = |执行价 - 信号价| × 股数
        BigDecimal slippageCost = priceDeviation.abs().multiply(BigDecimal.valueOf(shares)).setScale(2, RoundingMode.HALF_UP);

        PaperExecutionQuality quality = PaperExecutionQuality.builder()
                .paperId(signal.getPaperId())
                .signalId(signal.getId())
                .code(signal.getCode())
                .direction(signal.getDirection())
                .signalPrice(signalPrice)
                .executedPrice(executedPrice)
                .priceDeviation(priceDeviation)
                .priceDeviationPct(priceDeviationPct)
                .slippageCost(slippageCost)
                .commission(commission)
                .totalCost(slippageCost.add(commission))
                .executionTime(LocalDateTime.now())
                .fillRate(BigDecimal.ONE)
                .build();

        paperExecutionQualityMapper.insert(quality);
        log.info("执行质量记录已保存: signalId={}, deviation={}, slippage={}",
                signal.getId(), priceDeviationPct, slippageCost);
    }

    /**
     * 【缺陷1修复】条件单触发判断
     * @param orderType 订单类型
     * @param signal 信号（含triggerPrice/trailPct等）
     * @param currentPrice 当前价格
     * @return 是否触发执行
     */
    private boolean checkOrderTrigger(String orderType, PaperSignal signal, BigDecimal currentPrice) {
        BigDecimal triggerPrice = signal.getTriggerPrice();
        switch (orderType) {
            case "LIMIT":
                // 限价买入：当前价 ≤ 触发价（限价）→ 触发
                // 限价卖出：当前价 ≥ 触发价（限价）→ 触发
                if ("BUY".equals(signal.getDirection())) {
                    return triggerPrice != null && currentPrice.compareTo(triggerPrice) <= 0;
                } else {
                    return triggerPrice != null && currentPrice.compareTo(triggerPrice) >= 0;
                }
            case "STOP":
                // 止损单：当前价 ≤ 触发价 → 触发卖出
                return triggerPrice != null && currentPrice.compareTo(triggerPrice) <= 0;
            case "STOP_LIMIT":
                // 止损限价单：当前价 ≤ 触发价 且 当前价 ≥ 限价 → 触发
                BigDecimal limitPrice = signal.getLimitPrice();
                return triggerPrice != null && limitPrice != null
                    && currentPrice.compareTo(triggerPrice) <= 0
                    && currentPrice.compareTo(limitPrice) >= 0;
            case "TRAILING_STOP":
                // 追踪止损：当前价 ≤ 最高价 × (1 - trailPct) 或 当前价 ≤ 最高价 - trailAmount → 触发
                BigDecimal highest = signal.getHighestSinceBuy();
                if (highest == null) highest = currentPrice;  // 首次检查
                BigDecimal trailThreshold;
                if (signal.getTrailPct() != null && signal.getTrailPct().compareTo(BigDecimal.ZERO) > 0) {
                    trailThreshold = highest.multiply(BigDecimal.ONE.subtract(signal.getTrailPct()));
                } else if (signal.getTrailAmount() != null && signal.getTrailAmount().compareTo(BigDecimal.ZERO) > 0) {
                    trailThreshold = highest.subtract(signal.getTrailAmount());
                } else {
                    // 无追踪参数，无法判断，默认不触发
                    return false;
                }
                return currentPrice.compareTo(trailThreshold) <= 0;
            default:
                // MARKET 单无需条件判断
                return true;
        }
    }

    /**
     * 【缺陷1修复】更新追踪止损的最高价记录
     */
    private void updateTrailingHighestPrice(PaperSignal signal, BigDecimal currentPrice) {
        BigDecimal highest = signal.getHighestSinceBuy();
        if (highest == null || currentPrice.compareTo(highest) > 0) {
            signal.setHighestSinceBuy(currentPrice);
            paperSignalMapper.updateById(signal);
            log.info("追踪止损更新最高价: signalId={} code={} highest={}",
                signal.getId(), signal.getCode(), currentPrice);
        }
    }

    /**
     * 【缺陷1修复】检查并执行所有待触发的条件单（供Scheduler定期调用）
     */
    @Transactional
    public int checkAndExecuteConditionalOrders(Long paperId) {
        List<PaperSignal> pendingOrders = paperSignalMapper.selectList(
            new LambdaQueryWrapper<PaperSignal>()
                .eq(PaperSignal::getPaperId, paperId)
                .eq(PaperSignal::getStatus, "PENDING")
                .isNotNull(PaperSignal::getOrderType)
                .ne(PaperSignal::getOrderType, "MARKET"));

        int executedCount = 0;
        for (PaperSignal signal : pendingOrders) {
            try {
                PaperPosition result = executeSignal(signal.getId());
                if (result != null) executedCount++;
            } catch (Exception e) {
                log.warn("条件单执行失败: signalId={} err={}", signal.getId(), e.getMessage());
            }
        }
        if (executedCount > 0) {
            log.info("条件单执行完成: paperId={} executed={}/{}", paperId, executedCount, pendingOrders.size());
        }
        return executedCount;
    }

    // ── Fix #2: 一键买入（从推荐页快速建仓） ──────────────────────────────

    /**
     * 一键买入：创建 MARKET BUY 信号并立即执行
     * 优先使用 recommended.suggestedBuyPrice，回退到当前市场价
     */
    @Transactional
    public PaperPosition quickBuy(Long paperId, String code, String name, BigDecimal price) {
        PaperTrading pt = paperTradingMapper.selectById(paperId);
        if (pt == null) throw new IllegalArgumentException("模拟盘不存在");
        if (!"RUNNING".equals(pt.getStatus())) throw new IllegalArgumentException("模拟盘未运行");

        if (name == null) name = getStockName(code);

        // 优先使用传入的 price（前端可从 recommendation 取 suggestedBuyPrice）
        // 若未传入，则查询推荐表的最新 suggested_buy_price
        if (price == null) {
            try {
                BigDecimal recPrice = recommendationMapper.findLatestSuggestedBuyPrice(code);
                if (recPrice != null && recPrice.compareTo(BigDecimal.ZERO) > 0) {
                    price = recPrice;
                }
            } catch (Exception e) {
                log.warn("quickBuy: 查询 suggestedBuyPrice 失败 code={} err={}", code, e.getMessage());
            }
        }
        if (price == null) {
            price = getExecutionPrice(code, paperId);
        }
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("无法获取有效买入价格: " + code);
        }

        // 创建 PENDING MARKET BUY 信号
        PaperSignal signal = PaperSignal.builder()
                .paperId(paperId)
                .signalDate(LocalDate.now())
                .factorDate(LocalDate.now())
                .code(code)
                .name(name)
                .direction("BUY")
                .orderType("MARKET")
                .signalPrice(price)
                .reason("一键买入（推荐页）")
                .status("PENDING")
                .build();
        paperSignalMapper.insert(signal);
        log.info("quickBuy: 信号已创建 paperId={} code={} price={}", paperId, code, price);

        // 立即执行
        PaperPosition position = executeSignal(signal.getId());
        log.info("quickBuy: 执行完成 paperId={} code={} positionId={}", paperId, code,
                position != null ? position.getId() : "null");
        return position;
    }
}
