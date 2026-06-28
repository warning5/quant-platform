package com.quant.platform.backtest.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.backtest.domain.BacktestReport;
import com.quant.platform.backtest.domain.BacktestTask;
import com.quant.platform.backtest.domain.EquityCurve;
import com.quant.platform.backtest.domain.RebalanceRecord;
import com.quant.platform.backtest.mapper.BacktestReportMapper;
import com.quant.platform.backtest.mapper.BacktestTaskMapper;
import com.quant.platform.backtest.mapper.EquityCurveMapper;
import com.quant.platform.backtest.mapper.RebalanceRecordMapper;
import com.quant.platform.common.security.GroovySandboxConfig;
import com.quant.platform.common.utils.LimitUpUtils;
import com.quant.platform.factor.domain.FactorValue;
import com.quant.platform.factor.ic.service.FactorIcService;
import com.quant.platform.factor.mapper.FactorValueMapper;
import com.quant.platform.factor.service.ClickHouseFactorValueService;
import com.quant.platform.market.domain.MarketDailyBar;
import com.quant.platform.market.service.MarketDataService;
import com.quant.platform.screen.dto.ScreenRequest;
import com.quant.platform.screen.dto.ScreenResult;
import com.quant.platform.screen.service.StockScreenService;
import com.quant.platform.stock.entity.StockInfo;
import com.quant.platform.stock.mapper.StockInfoMapper;
import com.quant.platform.stock.service.DividendService;
import com.quant.platform.strategy.domain.StrategyDefinition;
import com.quant.platform.strategy.service.StrategyService;
import groovy.lang.Binding;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * 核心回测引擎
 * 基于事件驱动的历史模拟框架，支持因子选股策略回测
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestEngine {

    private final BacktestTaskMapper taskMapper;
    private final BacktestReportMapper reportMapper;
    private final MarketDataService marketDataService;
    private final FactorValueMapper factorValueMapper;
    private final StrategyService strategyService;
    private final DividendService dividendService;
    private final ObjectMapper objectMapper;
    @Autowired(required = false)
    private ClickHouseFactorValueService clickHouseFactorValueService;
    @Autowired(required = false)
    private FactorIcService factorIcService;
    /**
     * SCREEN 模式选股服务
     */
    @Autowired(required = false)
    private StockScreenService stockScreenService;
    /**
     * 调仓记录写入（统一后两种模式都写）
     */
    @Autowired(required = false)
    private StockInfoMapper stockInfoMapper;
    @Autowired(required = false)
    private RebalanceRecordMapper rebalanceRecordMapper;
    /**
     * 逐日净值写入
     */
    @Autowired(required = false)
    private EquityCurveMapper equityCurveMapper;
    @Autowired(required = false)
    private SimpMessagingTemplate messagingTemplate;
    @Resource
    private DataSource dataSource;

    /**
     * 异步运行回测
     * 注意：不加 @Transactional，避免与调用方事务冲突导致读不到新写入的记录。
     * 每个持久化操作各自用独立事务（JPA save 默认在自身事务中执行）。
     */
    @Async("backtestTaskExecutor")
    public void runBacktest(Long taskId) {
        // 调用方事务可能尚未提交，最多重试 10 次（每次等 200ms）
        BacktestTask task = null;
        for (int i = 0; i < 10; i++) {
            task = taskMapper.selectById(taskId);
            if (task != null) {
                break;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        if (task == null) {
            log.error("Backtest task [{}] not found in DB after retries, aborting", taskId);
            return;
        }

        task.setStatus(BacktestTask.BacktestStatus.RUNNING);
        task.setStartedAt(LocalDateTime.now());
        taskMapper.updateById(task);

        // 立即推送状态变更，让前端知道回测已开始
        sendProgress(task.getId(), "RUNNING", 0, "回测初始化中...");

        try {
            boolean isScreen = "SCREEN".equalsIgnoreCase(task.getSignalSource());
            String modeLabel = isScreen ? "SCREEN" : "STRATEGY";
            StrategyDefinition strategy = isScreen ? null : strategyService.getById(task.getStrategyId());

            if (!isScreen && strategy == null) {
                throw new RuntimeException("策略不存在: strategyId=" + task.getStrategyId());
            }
            if (isScreen && (stockScreenService == null || rebalanceRecordMapper == null || equityCurveMapper == null)) {
                throw new RuntimeException("SCREEN 模式需要 StockScreenService/RebalanceRecordMapper/EquityCurveMapper 可用");
            }

            BacktestResult result = isScreen
                    ? executeScreenBacktest(task)
                    : executeBacktest(task, strategy);

            BacktestReport report = buildReport(task, result);
            reportMapper.insert(report);

            task.setStatus(BacktestTask.BacktestStatus.COMPLETED);
            task.setProgress(100);
            task.setCompletedAt(LocalDateTime.now());
            taskMapper.updateById(task);

            sendProgress(taskId, "COMPLETED", 100, "回测完成，reportId=" + report.getId());
            log.info("Backtest task [{}] completed, mode={}", taskId, modeLabel);

        } catch (Exception e) {
            log.error("Backtest task [{}] failed", taskId, e);
            task.setStatus(BacktestTask.BacktestStatus.FAILED);
            task.setErrorMessage(e.getMessage());
            taskMapper.updateById(task);
            sendProgress(taskId, "FAILED", task.getProgress(), "回测失败: " + e.getMessage());
        }
    }

    /**
     * 同步执行回测（不加 @Async，用于参数优化批量调用）
     * 任务记录必须已存入 DB
     */
    public void runBacktestSync(Long taskId) {
        BacktestTask task = taskMapper.selectById(taskId);
        if (task == null) {
            log.error("runBacktestSync: task [{}] not found", taskId);
            return;
        }
        task.setStatus(BacktestTask.BacktestStatus.RUNNING);
        task.setStartedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        try {
            boolean isScreen = "SCREEN".equalsIgnoreCase(task.getSignalSource());
            StrategyDefinition strategy = isScreen ? null : strategyService.getById(task.getStrategyId());

            if (!isScreen && strategy == null) {
                throw new RuntimeException("策略不存在: strategyId=" + task.getStrategyId());
            }
            if (isScreen && (stockScreenService == null || rebalanceRecordMapper == null || equityCurveMapper == null)) {
                throw new RuntimeException("SCREEN 模式需要 StockScreenService/RebalanceRecordMapper/EquityCurveMapper 可用");
            }

            BacktestResult result = isScreen
                    ? executeScreenBacktest(task)
                    : executeBacktest(task, strategy);

            BacktestReport report = buildReport(task, result);
            reportMapper.insert(report);
            task.setStatus(BacktestTask.BacktestStatus.COMPLETED);
            task.setProgress(100);
            task.setCompletedAt(LocalDateTime.now());
            taskMapper.updateById(task);
        } catch (Exception e) {
            log.warn("runBacktestSync task [{}] failed: {}", taskId, e.getMessage());
            task.setStatus(BacktestTask.BacktestStatus.FAILED);
            task.setErrorMessage(e.getMessage());
            taskMapper.updateById(task);
        }
    }

    /**
     * 执行回测核心逻辑
     */
    private BacktestResult executeBacktest(BacktestTask task, StrategyDefinition strategy) {
        LocalDate startDate = task.getStartDate();
        LocalDate endDate = task.getEndDate();
        double initialCapital = task.getInitialCapital().doubleValue();
        double commission = task.getCommissionRate().doubleValue();
        double slippage = task.getSlippageRate().doubleValue();
        double stampTaxRate = task.getStampTaxRate() != null ? task.getStampTaxRate().doubleValue() : 0.0005;
        double minCommission = task.getMinCommission() != null ? task.getMinCommission().doubleValue() : 5.0;
        double transferFeeRate = task.getTransferFeeRate() != null ? task.getTransferFeeRate().doubleValue() : 0.00002;
        String slippageModel = task.getSlippageModel() != null ? task.getSlippageModel() : "FIXED";
        String orderType = task.getOrderType() != null ? task.getOrderType() : "CLOSE";
        boolean limitFilter = task.getLimitFilter() != null && task.getLimitFilter();
        boolean suspendFilter = task.getSuspendFilter() != null && task.getSuspendFilter();

        List<LocalDate> tradingDates = marketDataService.getTradingDates(startDate, endDate);
        if (tradingDates.isEmpty()) throw new RuntimeException("无可用交易日数据");

        // ── 预加载退市日期映射（幸存者偏差修复）────────────────────────
        Map<String, LocalDate> delistDateMap = loadDelistDateMap();
        log.info("Loaded {} delist dates for survivor-bias filtering", delistDateMap.size());

        // ── 前复权因子缓存（symbol → adjFactor）────────────────────────
        // 在回测时，如果 dividendReinvest=false，仍需对历史价格做前复权调整，
        // 以消除除权日价格跳空对技术指标/价格比较的影响。
        // 简化方案：记录每只股票的累积复权因子，并在除权日当天更新。
        // adjFactor[symbol] = 累积前复权因子（初始值1.0，除权日 *= 1/(1+stockConvert) 并 -= cashDiv/preClose）
        Map<String, Double> adjFactors = new HashMap<>();

        // ── 次日行情预加载（NEXT_OPEN 成交模式用）────────────────────
        // 预加载 di=0 时的次日行情，后续每个交易日滚动更新，避免 NEXT_OPEN 模式下频繁 DB 查询
        Map<String, MarketDailyBar> nextDayBarMap = new HashMap<>();
        if (tradingDates.size() > 1) {
            List<MarketDailyBar> nextBars = marketDataService.getBarsAtDate(tradingDates.get(1));
            nextDayBarMap = nextBars.stream()
                    .collect(Collectors.toMap(MarketDailyBar::getSymbol, b -> b));
        }

        // 解析调仓频率
        String freq = strategy.getRebalanceFrequency() != null ? strategy.getRebalanceFrequency() : "MONTHLY";
        // 解析因子配置
        List<FactorWeight> factorWeights = parseFactorConfig(strategy.getFactorConfigJson());
        // 因子权重计算模式：STATIC（默认）/ IC / IR
        String factorWeightMode = task.getFactorWeightMode() != null ? task.getFactorWeightMode() : "STATIC";
        boolean useDynamicFactorWeights = "IC".equalsIgnoreCase(factorWeightMode) || "IR".equalsIgnoreCase(factorWeightMode);

        // 解析止损止盈参数（参数优化使用）
        double stopLossPct = task.getStopLossPct() != null ? task.getStopLossPct().doubleValue() : 0.0;
        double stopProfitPct = task.getStopProfitPct() != null ? task.getStopProfitPct().doubleValue() : 0.0;

        // 回测状态
        double cash = initialCapital;
        Map<String, Double> positions = new HashMap<>();    // symbol -> shares
        Map<String, Double> positionValues = new HashMap<>();
        // 持仓成本记录（symbol -> 累计买入成本，用于止损止盈计算）
        Map<String, Double> positionCosts = new HashMap<>();
        double portfolioValue = initialCapital;

        List<Map<String, Object>> equityCurve = new ArrayList<>();
        List<Map<String, Object>> drawdownSeries = new ArrayList<>();
        List<Map<String, Object>> benchmarkCurve = new ArrayList<>();   // 基准逐日净值
        List<Map<String, Object>> tradeLog = new ArrayList<>();
        List<Map<String, Object>> positionHistory = new ArrayList<>();
        Map<String, Map<String, Double>> monthlyReturns = new TreeMap<>();

        // ── 加载基准指数行情 ──────────────────────────────────────────
        String benchmarkSymbol = task.getBenchmarkCode() != null ? task.getBenchmarkCode() : "000300.SH";
        List<MarketDailyBar> benchmarkBars = new ArrayList<>();
        try {
            benchmarkBars = marketDataService.getBarsInRange(benchmarkSymbol, startDate, endDate);
        } catch (Exception e) {
            log.warn("Failed to load benchmark bars for {}: {}", benchmarkSymbol, e.getMessage());
        }
        log.info("Loaded {} benchmark bars for {} from {} to {}", benchmarkBars.size(), benchmarkSymbol, startDate, endDate);

        // 建立日期→收盘价映射
        Map<LocalDate, Double> benchmarkClose = new LinkedHashMap<>();
        for (MarketDailyBar b : benchmarkBars) {
            benchmarkClose.put(b.getTradeDate(), b.getClose().doubleValue());
        }

        // ── 基准数据完整性检查 ─────────────────────────────────────
        if (benchmarkClose.isEmpty()) {
            throw new RuntimeException("基准指数 " + benchmarkSymbol + " 在 " + startDate + " 至 " + endDate
                    + " 期间无数据，请先在「数据更新」页面更新指数日线数据");
        }

        // 检查基准数据覆盖情况
        LocalDate firstBmDate = benchmarkClose.keySet().iterator().next();
        LocalDate lastBmDate = new ArrayList<>(benchmarkClose.keySet()).get(benchmarkClose.size() - 1);
        int missingStart = 0, missingEnd = 0;
        for (LocalDate d : tradingDates) {
            if (d.isBefore(firstBmDate)) missingStart++;
            else break;
        }
        for (int i = tradingDates.size() - 1; i >= 0; i--) {
            if (tradingDates.get(i).isAfter(lastBmDate)) missingEnd++;
            else break;
        }
        if (missingStart > 0 || missingEnd > 0) {
            log.warn("基准指数 {} 数据范围 {} ~ {}，回测区间 {} ~ {}，"
                            + "起始缺失{}个交易日、末尾缺失{}个交易日，未覆盖部分将使用前向填充",
                    benchmarkSymbol, firstBmDate, lastBmDate, startDate, endDate, missingStart, missingEnd);
        }

        // 找到第一个有效的基准价格（从回测开始日期往后找）
        Double startDateClose = null;
        Double firstValidClose = null;
        for (Map.Entry<LocalDate, Double> entry : benchmarkClose.entrySet()) {
            if (firstValidClose == null) {
                firstValidClose = entry.getValue();
            }
            if (!entry.getKey().isBefore(startDate)) {
                startDateClose = entry.getValue();
                break;
            }
        }

        // 基准初始价（回测开始日期或之后的第一个有效收盘价）
        double benchmarkBase = startDateClose != null ? startDateClose
                : (firstValidClose != null ? firstValidClose : 1.0);

        if (benchmarkBase <= 0) benchmarkBase = 1.0;

        // 用于基准价格前向填充的变量
        double lastValidBmClose = benchmarkBase;

        log.info("Benchmark base price: {}, firstValidClose: {}, startDateClose: {}", benchmarkBase, firstValidClose, startDateClose);
        log.info("First trading date: {}, Last trading date: {}", tradingDates.getFirst(), tradingDates.getLast());

        double peakValue = initialCapital;
        double maxDrawdown = 0;
        int maxDrawdownDuration = 0;
        int drawdownDays = 0;
        int totalTrades = 0;
        List<Double> tradeReturns = new ArrayList<>();

        LocalDate lastRebalanceDate = null;
        int total = tradingDates.size();

        for (int di = 0; di < tradingDates.size(); di++) {
            LocalDate today = tradingDates.get(di);

            // 获取今日行情快照，过滤 ST/*ST/退市股（name 含 ST 或 close<=0）
            List<MarketDailyBar> barsRaw = marketDataService.getBarsAtDate(today);
            List<MarketDailyBar> bars = new ArrayList<>();
            for (MarketDailyBar bar : barsRaw) {
                String name = bar.getName();
                String symbol = bar.getSymbol();
                boolean isST = name != null && name.contains("ST");
                boolean isDelisted = bar.getClose() == null || bar.getClose().doubleValue() <= 0;
                // 幸存者偏差修复：过滤已退市（含即将退市）股票
                LocalDate delistDate = delistDateMap.get(symbol);
                boolean willDelist = delistDate != null && !today.isBefore(delistDate);
                if (!isST && !isDelisted && !willDelist) {
                    bars.add(bar);
                }
            }
            Map<String, MarketDailyBar> barMap = bars.stream()
                    .collect(Collectors.toMap(MarketDailyBar::getSymbol, b -> b));

            // ── 分红除权处理（在计算持仓市值之前）──────────────────
            // 判断是否启用分红处理
            boolean dividendReinvest = task.getDividendReinvest() != null && task.getDividendReinvest();
            if (dividendReinvest) {
                double[] divCashRef = new double[]{0.0};
                BacktestUtils.processDividendEvents(positions, divCashRef, barMap, today, tradeLog, adjFactors, dividendService);
                cash += divCashRef[0]; // 分红现金到账
            } else {
                // 即使不启用分红处理，也要更新复权因子（用于价格连续性）
                BacktestUtils.updateAdjFactors(adjFactors, barMap, today, dividendService);
            }

            // 更新持仓市值
            // 当 dividendReinvest=false 时，使用复权价格计算市值，消除除权日价格跳空对净值的影响
            double holdingValue = 0;
            for (Map.Entry<String, Double> pos : positions.entrySet()) {
                MarketDailyBar bar = barMap.get(pos.getKey());
                if (bar != null) {
                    double adj = adjFactors.getOrDefault(pos.getKey(), 1.0);
                    double adjClose = bar.getClose().doubleValue() * adj;
                    holdingValue += pos.getValue() * adjClose;
                }
            }
            portfolioValue = cash + holdingValue;

            // ── 止损止盈检查（参数优化时启用）────────────────────────────────
            if ((stopLossPct > 0 || stopProfitPct > 0) && !positions.isEmpty()) {
                List<String> toSell = new ArrayList<>();
                for (Map.Entry<String, Double> pos : positions.entrySet()) {
                    String symbol = pos.getKey();
                    MarketDailyBar bar = barMap.get(symbol);
                    if (bar == null) continue;

                    double cost = positionCosts.getOrDefault(symbol, 0.0);
                    if (cost <= 0) continue;

                    double shares = pos.getValue();
                    double adj = adjFactors.getOrDefault(symbol, 1.0);
                    double currentValue = shares * bar.getClose().doubleValue() * adj;
                    double returnPct = (currentValue - cost) / cost;

                    // 止损：亏损超过阈值
                    if (stopLossPct > 0 && returnPct <= -stopLossPct) {
                        log.debug("[{}] {} 触发止损: 收益率={}, 阈值={}", today, symbol, returnPct, -stopLossPct);
                        toSell.add(symbol);
                    }
                    // 止盈：盈利超过阈值
                    else if (stopProfitPct > 0 && returnPct >= stopProfitPct) {
                        log.debug("[{}] {} 触发止盈: 收益率={}, 阈值={}", today, symbol, returnPct, stopProfitPct);
                        toSell.add(symbol);
                    }
                }

                // 执行止损止盈卖出
                if (!toSell.isEmpty()) {
                    for (String symbol : toSell) {
                        MarketDailyBar bar = barMap.get(symbol);
                        if (bar == null) continue;

                        // 停牌/涨跌停过滤
                        if (suspendFilter && isSuspended(bar)) {
                            log.debug("[{}] {} 停牌，跳过止损止盈", today, symbol);
                            continue;
                        }
                        if (limitFilter && isLimitDown(bar)) {
                            log.debug("[{}] {} 跌停，跳过止损止盈", today, symbol);
                            continue;
                        }

                        double shares = positions.get(symbol);
                        double cost = positionCosts.get(symbol);
                        double execPrice = getExecutionPrice(bar, tradingDates, di, orderType, nextDayBarMap);
                        double closePrice = bar.getClose().doubleValue();
                        double amount = shares * closePrice;
                        double dayAmount = bar.getAmount() != null ? bar.getAmount().doubleValue() * 1000 : 0;
                        double price = applySlippage(execPrice, false, slippage, amount, dayAmount, slippageModel);
                        double fee = BacktestUtils.calcFee(amount, true, commission, stampTaxRate, minCommission, symbol, transferFeeRate);

                        Map<String, Object> trade = new HashMap<>();
                        trade.put("date", today.toString());
                        trade.put("symbol", symbol);
                        trade.put("name", bar.getName());
                        trade.put("action", "STOP_LOSS_SELL");  // STOP_LOSS 或 STOP_PROFIT
                        trade.put("price", round(price, 4));
                        trade.put("amount", round(shares, 2));
                        trade.put("total", round(amount - fee, 2));
                        trade.put("commission", round(fee, 2));
                        trade.put("fee", round(fee, 2));
                        trade.put("returnPct", round(returnPct(amount, cost), 4));
                        tradeLog.add(trade);

                        cash += (shares * price) - fee;
                        totalTrades++;
                        positions.remove(symbol);
                        positionCosts.remove(symbol);
                    }
                }
            }

            // 判断是否调仓
            boolean shouldRebalance = shouldRebalance(today, lastRebalanceDate, freq);

            if (shouldRebalance && !bars.isEmpty()) {
                // 获取因子值
                Map<String, Map<String, FactorValue>> factorValueMap = new HashMap<>();
                for (FactorWeight fw : factorWeights) {
                    List<FactorValue> fvList = (clickHouseFactorValueService != null)
                            ? clickHouseFactorValueService.findByFactorCodeAndDate(fw.factorCode, today)
                            : factorValueMapper.selectList(
                            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FactorValue>()
                                    .eq(FactorValue::getFactorCode, fw.factorCode)
                                    .eq(FactorValue::getCalcDate, today));
                    Map<String, FactorValue> fvMap = fvList.stream()
                            .collect(Collectors.toMap(FactorValue::getSymbol, fv -> fv));
                    factorValueMap.put(fw.factorCode, fvMap);
                }

                // 计算动态因子权重（基于近期IC/IR）
                Map<String, Double> dynamicFactorWeights = null;
                if (useDynamicFactorWeights && factorIcService != null) {
                    dynamicFactorWeights = computeDynamicFactorWeights(factorWeights, factorWeightMode, today);
                }

                // 计算每只股票的综合评分
                Map<String, Double> scores = computeScores(bars, factorWeights, factorValueMap, task, strategy, today, dynamicFactorWeights);

                int maxPositions = task.getMaxPositionCount() != null
                        ? task.getMaxPositionCount()
                        : (strategy.getMaxPositionCount() != null ? strategy.getMaxPositionCount() : 20);
                Map<String, Double> targetWeights = selectTopStocks(scores, maxPositions);

                // 保存调仓前的持仓快照（用于后续涨跌停/停牌过滤保留未卖出持仓）
                Map<String, Double> oldPositions = new HashMap<>(positions);

                // ---- 预计算可用资金上限 ----
                // 1. 保留持仓市值
                double keptValue = 0;
                for (String sym : oldPositions.keySet()) {
                    if (!targetWeights.containsKey(sym)) continue;
                    MarketDailyBar bar = barMap.get(sym);
                    if (bar == null) continue;
                    keptValue += oldPositions.get(sym) * bar.getClose().doubleValue();
                }

                // 2. 卖出费用（不在新目标中的旧持仓）
                double sellFee = 0;
                for (String sym : oldPositions.keySet()) {
                    if (targetWeights.containsKey(sym)) continue;
                    MarketDailyBar bar = barMap.get(sym);
                    if (bar == null) continue;
                    if (suspendFilter && isSuspended(bar)) continue;
                    if (limitFilter && isLimitDown(bar)) continue;
                    double amount = oldPositions.get(sym) * bar.getClose().doubleValue();
                    sellFee += calcFee(amount, true, commission, stampTaxRate, minCommission, sym, transferFeeRate);
                }

                // 3. 新买入费用 + 原始买入总额
                double newBuyFee = 0;
                double rawInvestedValue = 0;
                for (Map.Entry<String, Double> entry : targetWeights.entrySet()) {
                    if (oldPositions.containsKey(entry.getKey())) continue;
                    MarketDailyBar bar = barMap.get(entry.getKey());
                    if (bar == null) continue;
                    if (suspendFilter && isSuspended(bar)) continue;
                    if (limitFilter && isLimitUp(bar)) continue;
                    double amount = portfolioValue * entry.getValue();
                    rawInvestedValue += amount;
                    newBuyFee += calcFee(amount, false, commission, stampTaxRate, minCommission, entry.getKey(), transferFeeRate);
                }

                // 4. 计算买入缩放比例
                double maxInvestable = portfolioValue - keptValue - sellFee - newBuyFee;
                double scale = rawInvestedValue > 0 ? Math.max(0, Math.min(1.0, maxInvestable / rawInvestedValue)) : 0;
                if (scale < 0.9999) {
                    log.info("Buy scale capped: {} (available={}, target={}, kept={})",
                            String.format("%.4f", scale), String.format("%.2f", maxInvestable),
                            String.format("%.2f", rawInvestedValue), String.format("%.2f", keptValue));
                }

                // 执行调仓（传入缩放因子）
                List<Map<String, Object>> rebalanceTrades = rebalance(
                        positions, targetWeights, barMap, portfolioValue, commission, slippage,
                        today, positionValues, slippageModel, stampTaxRate, minCommission,
                        limitFilter, suspendFilter, transferFeeRate, orderType, tradingDates, di,
                        nextDayBarMap, positionCosts, scale);
                tradeLog.addAll(rebalanceTrades);
                totalTrades += rebalanceTrades.size();

                // 重新计算cash
                cash = recalcCash(positions, targetWeights, barMap, portfolioValue, commission, slippage,
                        slippageModel, stampTaxRate, minCommission, limitFilter, suspendFilter,
                        transferFeeRate, scale);

                // 计算实际可买入的标的（排除涨停/停牌）
                Map<String, Double> effectiveTargets = new HashMap<>(targetWeights);
                for (String sym : new HashSet<>(effectiveTargets.keySet())) {
                    MarketDailyBar bar = barMap.get(sym);
                    if (bar == null) effectiveTargets.remove(sym);
                    else if (suspendFilter && isSuspended(bar)) effectiveTargets.remove(sym);
                    else if (limitFilter && isLimitUp(bar)) effectiveTargets.remove(sym);
                }

                // 计算实际可卖出的标的（排除跌停/停牌）
                Set<String> soldSymbols = new HashSet<>();
                for (String sym : new HashSet<>(oldPositions.keySet())) {
                    if (effectiveTargets.containsKey(sym)) continue; // 继续持有
                    MarketDailyBar bar = barMap.get(sym);
                    if (bar == null) {
                        soldSymbols.add(sym);
                        continue;
                    }
                    if (suspendFilter && isSuspended(bar)) continue; // 停牌不卖
                    if (limitFilter && isLimitDown(bar)) continue; // 跌停不卖
                    soldSymbols.add(sym);
                }

                positions = new HashMap<>();

                // 保留未卖出的旧持仓
                for (String sym : oldPositions.keySet()) {
                    if (!soldSymbols.contains(sym) && barMap.containsKey(sym)) {
                        positions.put(sym, oldPositions.get(sym));
                    }
                }
                // 加入新买入的持仓（应用缩放）
                // 注意：已保留的旧持仓（soldSymbols 不包含且 barMap 存在）在上方已写入 positions，
                // 这里只处理真正的新买入标的，避免 buyScale=0 时覆盖旧持仓的正确股数。
                for (Map.Entry<String, Double> entry : effectiveTargets.entrySet()) {
                    String sym = entry.getKey();
                    if (barMap.containsKey(sym) && !positions.containsKey(sym)) {
                        positions.put(sym,
                                (portfolioValue * entry.getValue() * scale) / barMap.get(sym).getClose().doubleValue());
                    }
                }

                lastRebalanceDate = today;

                // 记录持仓快照
                Map<String, Object> posSnapshot = new HashMap<>();
                posSnapshot.put("date", today.toString());
                posSnapshot.put("positions", new HashMap<>(targetWeights));
                positionHistory.add(posSnapshot);
            }

            // 计算当日最终组合净值（复权价格）
            holdingValue = 0;
            for (Map.Entry<String, Double> pos : positions.entrySet()) {
                MarketDailyBar bar = barMap.get(pos.getKey());
                if (bar != null) {
                    double adj = adjFactors.getOrDefault(pos.getKey(), 1.0);
                    double adjClose = bar.getClose().doubleValue() * adj;
                    holdingValue += pos.getValue() * adjClose;
                }
            }
            portfolioValue = cash + holdingValue;

            // 更新最大回撤
            if (portfolioValue > peakValue) {
                peakValue = portfolioValue;
                drawdownDays = 0;
            } else {
                drawdownDays++;
                maxDrawdownDuration = Math.max(maxDrawdownDuration, drawdownDays);
            }
            double drawdown = (peakValue - portfolioValue) / peakValue;
            maxDrawdown = Math.max(maxDrawdown, drawdown);

            // 记录净值曲线
            Map<String, Object> ep = new HashMap<>();
            ep.put("date", today.toString());
            ep.put("value", round(portfolioValue / initialCapital, 6));
            ep.put("drawdown", round(-drawdown, 6));
            equityCurve.add(ep);

            // 记录基准净值曲线（基准当日收盘 / 基准首日收盘）
            // 使用最后一个有效的基准价格进行前向填充
            Double bmClose = benchmarkClose.get(today);
            if (bmClose == null) {
                // 如果当天没有基准数据，使用最近的有效价格
                bmClose = lastValidBmClose;
            } else {
                lastValidBmClose = bmClose;
            }
            if (benchmarkBase > 0) {
                Map<String, Object> bm = new HashMap<>();
                bm.put("date", today.toString());
                bm.put("value", round(bmClose / benchmarkBase, 6));
                benchmarkCurve.add(bm);
            }

            // 月度收益记录
            String monthKey = today.getYear() + "-" + String.format("%02d", today.getMonthValue());
            monthlyReturns.put(monthKey, Map.of("value", portfolioValue / initialCapital - 1));

            // 进度更新 + 推送实时净值数据点（每 10 天推一次，避免过于频繁）
            int pct = (int) ((double) (di + 1) / total * 90);
            if (pct != task.getProgress() && di % 10 == 0) {
                task.setProgress(pct);
                taskMapper.updateById(task);
                // 取最近一个基准净值
                double bmVal = benchmarkCurve.isEmpty() ? 1.0
                        : ((Number) benchmarkCurve.getLast().get("value")).doubleValue();
                sendProgressWithCurve(task.getId(), pct, today.toString(),
                        round(portfolioValue / initialCapital, 6), bmVal);
            }

            // ── 滚动更新次日行情缓存（NEXT_OPEN 成交模式用）──────────
            if (di + 2 < tradingDates.size()) {
                LocalDate nextNextDate = tradingDates.get(di + 2);
                List<MarketDailyBar> nextNextBars = marketDataService.getBarsAtDate(nextNextDate);
                nextDayBarMap = nextNextBars.stream()
                        .collect(Collectors.toMap(MarketDailyBar::getSymbol, b -> b));
            } else {
                nextDayBarMap = new HashMap<>(); // 回测末尾无次日数据
            }
        }

        // 计算月度收益
        List<Map<String, Object>> monthlyReturnsList = new ArrayList<>();
        double prevValue = 1.0;
        for (Map.Entry<String, Map<String, Double>> entry : monthlyReturns.entrySet()) {
            double curValue = 1 + entry.getValue().get("value");
            double monthRet = prevValue > 0 ? (curValue - prevValue) / prevValue : 0;
            Map<String, Object> m = new HashMap<>();
            m.put("month", entry.getKey());
            m.put("return", round(monthRet, 6));
            monthlyReturnsList.add(m);
            prevValue = curValue;
        }

        // 计算基准总收益
        double benchmarkTotalReturn = benchmarkCurve.isEmpty() ? 0.0
                : ((Number) benchmarkCurve.getLast().get("value")).doubleValue() - 1.0;

        return new BacktestResult(
                portfolioValue / initialCapital - 1,
                portfolioValue, initialCapital,
                maxDrawdown, maxDrawdownDuration,
                totalTrades, tradeReturns,
                equityCurve, benchmarkCurve, drawdownSeries, monthlyReturnsList,
                positionHistory, tradeLog,
                tradingDates.size(),
                benchmarkTotalReturn
        );
    }

    /**
     * SCREEN 模式回测：使用 StockScreenService 选股，保持与 STRATEGY 模式一致的交易执行框架。
     * 复用了 executeBacktest 的大部分逻辑：分红处理、止损止盈、滑点、成交模式等，
     * 区别仅在于选股阶段调用 StockScreenService.screen() 代替因子算分。
     */
    private BacktestResult executeScreenBacktest(BacktestTask task) {
        LocalDate startDate = task.getStartDate();
        LocalDate endDate = task.getEndDate();
        double initialCapital = task.getInitialCapital().doubleValue();
        double commission = task.getCommissionRate().doubleValue();
        double slippage = task.getSlippageRate().doubleValue();
        double stampTaxRate = task.getStampTaxRate() != null ? task.getStampTaxRate().doubleValue() : 0.0005;
        double minCommission = task.getMinCommission() != null ? task.getMinCommission().doubleValue() : 5.0;
        double transferFeeRate = task.getTransferFeeRate() != null ? task.getTransferFeeRate().doubleValue() : 0.00002;
        String slippageModel = task.getSlippageModel() != null ? task.getSlippageModel() : "FIXED";
        String orderType = task.getOrderType() != null ? task.getOrderType() : "CLOSE";
        boolean limitFilter = task.getLimitFilter() != null && task.getLimitFilter();
        boolean suspendFilter = task.getSuspendFilter() != null && task.getSuspendFilter();
        String freq = task.getRebalanceFreq() != null ? task.getRebalanceFreq() : "MONTHLY";
        String weightMode = task.getWeightMode() != null ? task.getWeightMode() : "EQUAL";

        // ── 解析 screen_config_json ──
        ScreenRequest baseScreenReq;
        try {
            baseScreenReq = objectMapper.readValue(task.getScreenConfigJson(), ScreenRequest.class);
        } catch (Exception e) {
            throw new RuntimeException("解析 screenConfigJson 失败: " + e.getMessage(), e);
        }
        if (baseScreenReq.getFactors() == null || baseScreenReq.getFactors().isEmpty()) {
            throw new RuntimeException("SCREEN 模式必须指定因子配置（factors 不能为空）");
        }

        List<LocalDate> tradingDates = marketDataService.getTradingDates(startDate, endDate);
        if (tradingDates.isEmpty()) throw new RuntimeException("无可用交易日数据");

        // ── 预加载退市日期映射（幸存者偏差修复）────────────────────────
        Map<String, LocalDate> delistDateMap = loadDelistDateMap();
        log.info("[SCREEN] Loaded {} delist dates for survivor-bias filtering", delistDateMap.size());

        // ── 止损止盈参数 ──
        double stopLossPct = task.getStopLossPct() != null ? task.getStopLossPct().doubleValue() : 0.0;
        double stopProfitPct = task.getStopProfitPct() != null ? task.getStopProfitPct().doubleValue() : 0.0;

        // ── 前复权因子 ──
        Map<String, Double> adjFactors = new HashMap<>();

        // ── 次日行情预加载 ──
        Map<String, MarketDailyBar> nextDayBarMap = new HashMap<>();
        if (tradingDates.size() > 1) {
            List<MarketDailyBar> nextBars = marketDataService.getBarsAtDate(tradingDates.get(1));
            nextDayBarMap = nextBars.stream()
                    .collect(Collectors.toMap(MarketDailyBar::getSymbol, b -> b));
        }

        // ── 基准 ──
        String benchmarkSymbol = task.getBenchmarkCode() != null ? task.getBenchmarkCode() : "000300.SH";
        List<MarketDailyBar> benchmarkBars = marketDataService.getBarsInRange(benchmarkSymbol, startDate, endDate);
        Map<LocalDate, Double> benchmarkClose = new LinkedHashMap<>();
        for (MarketDailyBar b : benchmarkBars) {
            benchmarkClose.put(b.getTradeDate(), b.getClose().doubleValue());
        }
        double benchmarkBase = benchmarkClose.isEmpty() ? 1.0
                : benchmarkClose.values().iterator().next();
        double lastValidBmClose = benchmarkBase;

        // ── 回测状态 ──
        double cash = initialCapital;
        Map<String, Double> positions = new HashMap<>();
        Map<String, Double> positionCosts = new HashMap<>();
        double portfolioValue = initialCapital;

        List<Map<String, Object>> equityCurve = new ArrayList<>();
        List<Map<String, Object>> drawdownSeries = new ArrayList<>();
        List<Map<String, Object>> benchmarkCurve = new ArrayList<>();
        List<Map<String, Object>> tradeLog = new ArrayList<>();
        List<Map<String, Object>> positionHistory = new ArrayList<>();
        Map<String, Map<String, Double>> monthlyReturns = new TreeMap<>();

        double peakValue = initialCapital;
        double maxDrawdown = 0;
        int maxDrawdownDuration = 0;
        int drawdownDays = 0;
        int totalTrades = 0;
        List<Double> tradeReturns = new ArrayList<>();

        LocalDate lastRebalanceDate = null;
        double prevNav = 0;  // 上一个调仓日的净值，用于计算调仓区间收益
        int total = tradingDates.size();

        for (int di = 0; di < tradingDates.size(); di++) {
            LocalDate today = tradingDates.get(di);

            // 获取今日行情，过滤 ST/退市
            List<MarketDailyBar> barsRaw = marketDataService.getBarsAtDate(today);
            List<MarketDailyBar> bars = new ArrayList<>();
            for (MarketDailyBar bar : barsRaw) {
                String name = bar.getName();
                String symbol = bar.getSymbol();
                boolean isST = name != null && name.contains("ST");
                boolean isDelisted = bar.getClose() == null || bar.getClose().doubleValue() <= 0;
                // 幸存者偏差修复：过滤已退市（含即将退市）股票
                LocalDate delistDate = delistDateMap.get(symbol);
                boolean willDelist = delistDate != null && !today.isBefore(delistDate);
                if (!isST && !isDelisted && !willDelist) bars.add(bar);
            }
            Map<String, MarketDailyBar> barMap = bars.stream()
                    .collect(Collectors.toMap(MarketDailyBar::getSymbol, b -> b));

            // ── 分红处理 ──
            boolean dividendReinvest = task.getDividendReinvest() != null && task.getDividendReinvest();
            if (dividendReinvest) {
                double[] divCashRef = new double[]{0.0};
                BacktestUtils.processDividendEvents(positions, divCashRef, barMap, today, tradeLog, adjFactors, dividendService);
                cash += divCashRef[0];
            } else {
                BacktestUtils.updateAdjFactors(adjFactors, barMap, today, dividendService);
            }

            // 更新持仓市值
            double holdingValue = 0;
            for (Map.Entry<String, Double> pos : positions.entrySet()) {
                MarketDailyBar bar = barMap.get(pos.getKey());
                if (bar != null) {
                    double adj = adjFactors.getOrDefault(pos.getKey(), 1.0);
                    holdingValue += pos.getValue() * bar.getClose().doubleValue() * adj;
                }
            }
            portfolioValue = cash + holdingValue;

            // ── 止损止盈 ──
            if ((stopLossPct > 0 || stopProfitPct > 0) && !positions.isEmpty()) {
                List<String> toSell = new ArrayList<>();
                for (Map.Entry<String, Double> pos : positions.entrySet()) {
                    String symbol = pos.getKey();
                    MarketDailyBar bar = barMap.get(symbol);
                    if (bar == null) continue;
                    double cost = positionCosts.getOrDefault(symbol, 0.0);
                    if (cost <= 0) continue;
                    double shares = pos.getValue();
                    double adj = adjFactors.getOrDefault(symbol, 1.0);
                    double currentValue = shares * bar.getClose().doubleValue() * adj;
                    double returnPct = (currentValue - cost) / cost;
                    if (stopLossPct > 0 && returnPct <= -stopLossPct) toSell.add(symbol);
                    else if (stopProfitPct > 0 && returnPct >= stopProfitPct) toSell.add(symbol);
                }
                for (String symbol : toSell) {
                    MarketDailyBar bar = barMap.get(symbol);
                    if (bar == null) continue;
                    if (suspendFilter && isSuspended(bar)) continue;
                    if (limitFilter && isLimitDown(bar)) continue;
                    double shares = positions.get(symbol);
                    double execPrice = getExecutionPrice(bar, tradingDates, di, orderType, nextDayBarMap);
                    double amount = shares * bar.getClose().doubleValue();
                    double dayAmount = bar.getAmount() != null ? bar.getAmount().doubleValue() * 1000 : 0;
                    double price = applySlippage(execPrice, false, slippage, amount, dayAmount, slippageModel);
                    double fee = calcFee(amount, true, commission, stampTaxRate, minCommission, symbol, transferFeeRate);
                    Map<String, Object> trade = new HashMap<>();
                    trade.put("date", today.toString());
                    trade.put("symbol", symbol);
                    trade.put("name", bar.getName());
                    trade.put("action", "STOP_LOSS_SELL");
                    trade.put("price", round(price, 4));
                    trade.put("amount", round(shares, 2));
                    trade.put("total", round(amount - fee, 2));
                    trade.put("commission", round(fee, 2));
                    trade.put("fee", round(fee, 2));
                    tradeLog.add(trade);
                    cash += (shares * price) - fee;
                    totalTrades++;
                    positions.remove(symbol);
                    positionCosts.remove(symbol);
                }
            }

            // ── 调仓判定 ──
            boolean shouldRebalance = shouldRebalance(today, lastRebalanceDate, freq);
            if (shouldRebalance && !bars.isEmpty()) {
                // ── SCREEN 模式选股 ──
                ScreenRequest screenReq = buildScreenRequest(baseScreenReq, today);
                ScreenResult screenResult;
                try {
                    screenResult = stockScreenService.screen(screenReq);
                } catch (Exception e) {
                    log.warn("[{}] 选股失败，跳过本次调仓: {}", today, e.getMessage());
                    // 选股失败则保持持仓，继续记录权益曲线
                    screenResult = null;
                }

                Map<String, Double> targetWeights = new LinkedHashMap<>();
                Map<String, ScreenResult.StockScore> stockScoreMap = new LinkedHashMap<>();
                if (screenResult != null && screenResult.getStocks() != null) {
                    List<ScreenResult.StockScore> stocks = screenResult.getStocks();
                    if ("SCORE_PROPORTIONAL".equalsIgnoreCase(weightMode)) {
                        double totalScore = stocks.stream().mapToDouble(ScreenResult.StockScore::getCompositeScore).sum();
                        if (totalScore <= 0) totalScore = 1.0;
                        for (ScreenResult.StockScore s : stocks) {
                            targetWeights.put(s.getSymbol(), s.getCompositeScore() / totalScore);
                            stockScoreMap.put(s.getSymbol(), s);
                        }
                    } else {
                        double ew = 1.0 / stocks.size();
                        for (ScreenResult.StockScore s : stocks) {
                            targetWeights.put(s.getSymbol(), ew);
                            stockScoreMap.put(s.getSymbol(), s);
                        }
                    }
                }

                Map<String, Double> oldPositions = new HashMap<>(positions);

                // ── 预计算费用（复用 STRATEGY 模式的预算逻辑）──
                double rawInvestedValue = 0, newBuyFee = 0;
                for (Map.Entry<String, Double> entry : targetWeights.entrySet()) {
                    if (oldPositions.containsKey(entry.getKey())) continue;
                    MarketDailyBar bar = barMap.get(entry.getKey());
                    if (bar == null) continue;
                    if (suspendFilter && isSuspended(bar)) continue;
                    if (limitFilter && isLimitUp(bar)) continue;
                    double amount = portfolioValue * entry.getValue();
                    rawInvestedValue += amount;
                    newBuyFee += calcFee(amount, false, commission, stampTaxRate, minCommission, entry.getKey(), transferFeeRate);
                }
                double keptValue = 0;
                for (String sym : oldPositions.keySet()) {
                    if (!targetWeights.containsKey(sym)) continue;
                    MarketDailyBar bar = barMap.get(sym);
                    if (bar == null) continue;
                    keptValue += oldPositions.get(sym) * bar.getClose().doubleValue();
                }
                double sellFee = 0;
                for (String sym : oldPositions.keySet()) {
                    if (targetWeights.containsKey(sym)) continue;
                    MarketDailyBar bar = barMap.get(sym);
                    if (bar == null) continue;
                    if (suspendFilter && isSuspended(bar)) continue;
                    if (limitFilter && isLimitDown(bar)) continue;
                    double amount = oldPositions.get(sym) * bar.getClose().doubleValue();
                    sellFee += calcFee(amount, true, commission, stampTaxRate, minCommission, sym, transferFeeRate);
                }
                double maxInvestable = portfolioValue - keptValue - sellFee - newBuyFee;
                double scale = rawInvestedValue > 0 ? Math.max(0, Math.min(1.0, maxInvestable / rawInvestedValue)) : 0;

                // ── 交易执行（复用 rebalance/recalcCash）──
                List<Map<String, Object>> rebalanceTrades = rebalance(
                        positions, targetWeights, barMap, portfolioValue, commission, slippage,
                        today, null, slippageModel, stampTaxRate, minCommission,
                        limitFilter, suspendFilter, transferFeeRate, orderType, tradingDates, di,
                        nextDayBarMap, positionCosts, scale);
                tradeLog.addAll(rebalanceTrades);
                totalTrades += rebalanceTrades.size();

                cash = recalcCash(positions, targetWeights, barMap, portfolioValue, commission, slippage,
                        slippageModel, stampTaxRate, minCommission, limitFilter, suspendFilter,
                        transferFeeRate, scale);

                // ── 写入 rebalance_record ──
                try {
                    Map<String, Map<String, Object>> oldSnap = new HashMap<>();
                    for (Map.Entry<String, Double> pos : oldPositions.entrySet()) {
                        Map<String, Object> item = new HashMap<>();
                        item.put("symbol", pos.getKey());
                        item.put("shares", pos.getValue());
                        oldSnap.put(pos.getKey(), item);
                    }
                    List<Map<String, Object>> oldList = new ArrayList<>(oldSnap.values());
                    List<Map<String, Object>> newList = new ArrayList<>();
                    List<Map<String, Object>> buyList = new ArrayList<>();
                    List<Map<String, Object>> sellList = new ArrayList<>();
                    for (Map<String, Object> t : rebalanceTrades) {
                        if ("SELL".equals(t.get("action"))) sellList.add(t);
                        else if ("BUY".equals(t.get("action"))) buyList.add(t);
                    }
                    for (Map.Entry<String, Double> entry : targetWeights.entrySet()) {
                        Map<String, Object> item = new HashMap<>();
                        item.put("symbol", entry.getKey());
                        item.put("weight", entry.getValue());
                        ScreenResult.StockScore ss = stockScoreMap.get(entry.getKey());
                        item.put("score", ss != null ? ss.getCompositeScore() : 0);
                        item.put("name", ss != null ? ss.getName() : entry.getKey());
                        newList.add(item);
                    }

                    double nav = portfolioValue / initialCapital;
                    RebalanceRecord rec = RebalanceRecord.builder()
                            .taskId(task.getId())
                            .rebalanceDate(today)
                            .oldPositionsJson(objectMapper.writeValueAsString(oldList))
                            .newPositionsJson(objectMapper.writeValueAsString(newList))
                            .buysJson(objectMapper.writeValueAsString(buyList))
                            .sellsJson(objectMapper.writeValueAsString(sellList))
                            .cash(BigDecimal.valueOf(cash))
                            .totalValue(BigDecimal.valueOf(portfolioValue))
                            .nav(BigDecimal.valueOf(nav))
                            .dailyReturn(BigDecimal.valueOf(prevNav > 0 ? (nav - prevNav) / prevNav : 0))
                            .build();
                    rebalanceRecordMapper.insert(rec);
                    prevNav = nav;
                } catch (Exception e) {
                    log.warn("写入 rebalance_record 失败: {}", e.getMessage());
                }

                // ── 更新持仓 ──
                Set<String> soldSymbols = new HashSet<>();
                for (String sym : new HashSet<>(oldPositions.keySet())) {
                    if (targetWeights.containsKey(sym)) continue;
                    MarketDailyBar bar = barMap.get(sym);
                    if (bar == null) {
                        soldSymbols.add(sym);
                        continue;
                    }
                    if (suspendFilter && isSuspended(bar)) continue;
                    if (limitFilter && isLimitDown(bar)) continue;
                    soldSymbols.add(sym);
                }
                positions = new HashMap<>();
                for (String sym : oldPositions.keySet()) {
                    if (!soldSymbols.contains(sym) && barMap.containsKey(sym)) {
                        positions.put(sym, oldPositions.get(sym));
                    }
                }
                for (Map.Entry<String, Double> entry : targetWeights.entrySet()) {
                    String sym = entry.getKey();
                    if (barMap.containsKey(sym) && !positions.containsKey(sym)) {
                        positions.put(sym,
                                (portfolioValue * entry.getValue() * scale) / barMap.get(sym).getClose().doubleValue());
                    }
                }

                // 调仓后重新计算 portfolioValue（持仓和现金已按当日价格更新），
                // 确保 rebalance_record 的 NAV 与 equity_curve 一致
                double newHoldingValue = 0;
                for (Map.Entry<String, Double> pos : positions.entrySet()) {
                    MarketDailyBar bar = barMap.get(pos.getKey());
                    if (bar != null) {
                        newHoldingValue += pos.getValue() * bar.getClose().doubleValue();
                    }
                }
                portfolioValue = cash + newHoldingValue;

                lastRebalanceDate = today;
                Map<String, Object> posSnapshot = new HashMap<>();
                posSnapshot.put("date", today.toString());
                posSnapshot.put("positions", new HashMap<>(targetWeights));
                positionHistory.add(posSnapshot);
            }

            // ── 净值曲线 ──
            holdingValue = 0;
            for (Map.Entry<String, Double> pos : positions.entrySet()) {
                MarketDailyBar bar = barMap.get(pos.getKey());
                if (bar != null) {
                    double adj = adjFactors.getOrDefault(pos.getKey(), 1.0);
                    holdingValue += pos.getValue() * bar.getClose().doubleValue() * adj;
                }
            }
            portfolioValue = cash + holdingValue;

            if (portfolioValue > peakValue) {
                peakValue = portfolioValue;
                drawdownDays = 0;
            } else {
                drawdownDays++;
                maxDrawdownDuration = Math.max(maxDrawdownDuration, drawdownDays);
            }
            double drawdown = (peakValue - portfolioValue) / peakValue;
            maxDrawdown = Math.max(maxDrawdown, drawdown);

            Map<String, Object> ep = new HashMap<>();
            ep.put("date", today.toString());
            ep.put("value", round(portfolioValue / initialCapital, 6));
            ep.put("drawdown", round(-drawdown, 6));
            equityCurve.add(ep);

            Double bmClose = benchmarkClose.get(today);
            if (bmClose == null) bmClose = lastValidBmClose;
            else lastValidBmClose = bmClose;
            if (benchmarkBase > 0) {
                Map<String, Object> bm = new HashMap<>();
                bm.put("date", today.toString());
                bm.put("value", round(bmClose / benchmarkBase, 6));
                benchmarkCurve.add(bm);
            }

            String monthKey = today.getYear() + "-" + String.format("%02d", today.getMonthValue());
            monthlyReturns.put(monthKey, Map.of("value", portfolioValue / initialCapital - 1));

            int pct = (int) ((double) (di + 1) / total * 90);
            if (pct != task.getProgress() && di % 10 == 0) {
                task.setProgress(pct);
                taskMapper.updateById(task);
                double bmVal = benchmarkCurve.isEmpty() ? 1.0
                        : ((Number) benchmarkCurve.getLast().get("value")).doubleValue();
                sendProgressWithCurve(task.getId(), pct, today.toString(),
                        round(portfolioValue / initialCapital, 6), bmVal);
            }

            if (di + 2 < tradingDates.size()) {
                LocalDate nextNextDate = tradingDates.get(di + 2);
                List<MarketDailyBar> nextNextBars = marketDataService.getBarsAtDate(nextNextDate);
                nextDayBarMap = nextNextBars.stream()
                        .collect(Collectors.toMap(MarketDailyBar::getSymbol, b -> b));
            } else {
                nextDayBarMap = new HashMap<>();
            }
        }

        // ── 写入逐日净值到 equity_curve 表 ──
        writeEquityCurveToDB(task.getId(), equityCurve, initialCapital);

        // 月度收益计算
        List<Map<String, Object>> monthlyReturnsList = new ArrayList<>();
        double prevValue = 1.0;
        for (Map.Entry<String, Map<String, Double>> entry : monthlyReturns.entrySet()) {
            double curValue = 1 + entry.getValue().get("value");
            double monthRet = prevValue > 0 ? (curValue - prevValue) / prevValue : 0;
            Map<String, Object> m = new HashMap<>();
            m.put("month", entry.getKey());
            m.put("return", round(monthRet, 6));
            monthlyReturnsList.add(m);
            prevValue = curValue;
        }

        double benchmarkTotalReturn = benchmarkCurve.isEmpty() ? 0.0
                : ((Number) benchmarkCurve.getLast().get("value")).doubleValue() - 1.0;

        return new BacktestResult(
                portfolioValue / initialCapital - 1,
                portfolioValue, initialCapital,
                maxDrawdown, maxDrawdownDuration,
                totalTrades, tradeReturns,
                equityCurve, benchmarkCurve, drawdownSeries, monthlyReturnsList,
                positionHistory, tradeLog,
                tradingDates.size(),
                benchmarkTotalReturn
        );
    }

    /**
     * 构建选股请求（设置 screenDate 为指定日期）
     */
    private ScreenRequest buildScreenRequest(ScreenRequest base, LocalDate screenDate) {
        ScreenRequest req = new ScreenRequest();
        req.setScreenDate(screenDate);
        req.setFactors(base.getFactors());
        req.setDirection(base.getDirection());
        req.setTopN(base.getTopN());
        req.setExcludeSt(base.getExcludeSt());
        return req;
    }

    /**
     * 写入逐日净值到 equity_curve 表
     */
    private void writeEquityCurveToDB(Long taskId, List<Map<String, Object>> equityCurve, double initialCapital) {
        if (equityCurveMapper == null || equityCurve == null || equityCurve.isEmpty()) return;
        try {
            // 先清理旧数据
            try {
                equityCurveMapper.deleteByTaskId(taskId);
            } catch (Exception ignored) {
            }
            double prevNav = 0;
            for (Map<String, Object> point : equityCurve) {
                LocalDate date = LocalDate.parse((String) point.get("date"));
                double nav = ((Number) point.get("value")).doubleValue();
                double portfolioValue = nav * initialCapital;
                EquityCurve ec = EquityCurve.builder()
                        .taskId(taskId)
                        .tradeDate(date)
                        .portfolioValue(BigDecimal.valueOf(portfolioValue))
                        .nav(BigDecimal.valueOf(nav))
                        .returnPct(BigDecimal.valueOf(prevNav > 0 ? (nav - prevNav) / prevNav : 0))
                        .build();
                prevNav = nav;
                try {
                    equityCurveMapper.insertOne(ec);
                } catch (Exception e) {
                    // 逐条插入容错
                }
            }
        } catch (Exception e) {
            log.warn("写入 equity_curve 失败: {}", e.getMessage());
        }
    }

    /**
     * 计算综合因子得分
     * 优先使用 rank_value（预计算百分位排名），若为 NULL 则回退用 factor_val 实时做截面 z-score 归一化。
     */
    private Map<String, Double> computeScores(List<MarketDailyBar> bars,
                                              List<FactorWeight> factorWeights,
                                              Map<String, Map<String, FactorValue>> factorValueMap,
                                              BacktestTask task,
                                              StrategyDefinition strategy,
                                              LocalDate rebalanceDate,
                                              Map<String, Double> dynamicFactorWeights) {
        Map<String, Double> scores = new HashMap<>();

        // 如果是自定义脚本策略，使用Groovy脚本执行
        if (strategy.getStrategyType() == StrategyDefinition.StrategyType.CUSTOM
                && strategy.getScriptCode() != null && !strategy.getScriptCode().isBlank()) {
            return computeScoresWithScript(bars, factorValueMap, task, strategy, rebalanceDate);
        }

        // 检查每个因子是否有 rank_value，如果没有则需要实时计算截面 z-score
        // key: factorCode, value: { symbol -> normalized score }
        Map<String, Map<String, Double>> normalizedMap = new HashMap<>();
        for (FactorWeight fw : factorWeights) {
            Map<String, FactorValue> fvMap = factorValueMap.get(fw.factorCode);
            if (fvMap == null || fvMap.isEmpty()) continue;

            // 判断是否有 rank_value 可用
            boolean hasRankValue = fvMap.values().stream()
                    .anyMatch(fv -> fv.getRankValue() != null);

            if (hasRankValue) {
                // 直接使用 rank_value
                Map<String, Double> scoreMap = new HashMap<>();
                for (Map.Entry<String, FactorValue> entry : fvMap.entrySet()) {
                    if (entry.getValue().getRankValue() != null) {
                        scoreMap.put(entry.getKey(), entry.getValue().getRankValue().doubleValue());
                    }
                }
                normalizedMap.put(fw.factorCode, scoreMap);
            } else {
                // 回退：使用 factor_val 实时做截面 z-score 归一化
                Map<String, Double> scoreMap = normalizeFactorVals(fvMap);
                normalizedMap.put(fw.factorCode, scoreMap);
            }
        }

        // 综合评分：使用动态权重（如果有）或静态权重
        for (MarketDailyBar bar : bars) {
            double score = 0;
            boolean hasAnyFactor = false;
            for (FactorWeight fw : factorWeights) {
                Map<String, Double> scoreMap = normalizedMap.get(fw.factorCode);
                if (scoreMap == null) continue;
                Double val = scoreMap.get(bar.getSymbol());
                if (val == null) continue;
                // 优先使用动态权重，回退到静态配置权重
                double effectiveWeight = (dynamicFactorWeights != null && dynamicFactorWeights.containsKey(fw.factorCode))
                        ? dynamicFactorWeights.get(fw.factorCode)
                        : fw.weight;
                score += val * effectiveWeight;
                hasAnyFactor = true;
            }
            if (hasAnyFactor) {
                scores.put(bar.getSymbol(), score);
            }
        }
        return scores;
    }

    /**
     * 对 factor_val 做截面 z-score 归一化，返回 { symbol -> zScore }
     */
    private Map<String, Double> normalizeFactorVals(Map<String, FactorValue> fvMap) {
        // 收集有效值
        double[] raw = fvMap.values().stream()
                .filter(fv -> fv.getFactorVal() != null)
                .mapToDouble(fv -> fv.getFactorVal().doubleValue())
                .toArray();

        if (raw.length == 0) return Map.of();

        double mean = Arrays.stream(raw).average().orElse(0);
        double std = Math.sqrt(Arrays.stream(raw).map(v -> (v - mean) * (v - mean)).average().orElse(1));
        // 避免除零
        if (std < 1e-10) std = 1.0;

        Map<String, Double> result = new HashMap<>();
        for (Map.Entry<String, FactorValue> entry : fvMap.entrySet()) {
            if (entry.getValue().getFactorVal() != null) {
                double z = (entry.getValue().getFactorVal().doubleValue() - mean) / std;
                result.put(entry.getKey(), z);
            }
        }
        return result;
    }

    /**
     * 使用Groovy脚本计算股票得分
     */
    private Map<String, Double> computeScoresWithScript(List<MarketDailyBar> bars,
                                                        Map<String, Map<String, FactorValue>> factorValueMap,
                                                        BacktestTask task,
                                                        StrategyDefinition strategy,
                                                        LocalDate rebalanceDate) {
        Map<String, Double> scores = new HashMap<>();

        try {
            Binding binding = new Binding();
            binding.setVariable("marketBars", bars);
            binding.setVariable("factorValues", factorValueMap);
            binding.setVariable("rebalanceDate", rebalanceDate.toString());
            // 优先用任务级持仓数，没有则用策略定义，都没有则默认20
            int maxPositions = task.getMaxPositionCount() != null
                    ? task.getMaxPositionCount()
                    : (strategy.getMaxPositionCount() != null ? strategy.getMaxPositionCount() : 20);
            binding.setVariable("maxPositions", maxPositions);

            // ── 新增绑定变量（供策略脚本使用）──
            // indexBars: 沪深300指数K线（供RSRS择时等策略使用）
            List<MarketDailyBar> indexBars = marketDataService.getBarsInRange(
                    "000300.SH", rebalanceDate.minusDays(1200), rebalanceDate);
            binding.setVariable("indexBars", indexBars);

            // industryMap: 股票代码 → 行业名称（从 stock_info）
            Map<String, String> industryMap = loadIndustryMap(bars);
            binding.setVariable("industryMap", industryMap);

            // stockInfoMap: 股票代码 → 上市日期等信息
            Map<String, Map<String, Object>> stockInfoMap = loadStockInfoMap(bars);
            binding.setVariable("stockInfoMap", stockInfoMap);

            // historicalFactors: 多期因子历史值（用于RSRS等需要序列的策略）
            // 格式: { factorCode -> { symbol -> [FactorValue...] } }
            Map<String, Map<String, List<FactorValue>>> historicalFactors = loadHistoricalFactors(
                    factorValueMap.keySet(), rebalanceDate, 120);
            binding.setVariable("historicalFactors", historicalFactors);

            // 安全预检 + 带超时执行（统一由 GroovySandboxConfig 提供双重防护 + 超时保护）
            Object result = GroovySandboxConfig.evaluateScriptWithTimeout(
                    binding, strategy.getScriptCode(), GroovySandboxConfig.BACKTEST_TIMEOUT_SECONDS);

            if (result instanceof Map<?, ?> resultMap) {
                for (Map.Entry<?, ?> entry : resultMap.entrySet()) {
                    if (entry.getKey() instanceof String symbol && entry.getValue() instanceof Number weight) {
                        scores.put(symbol, weight.doubleValue());
                    }
                }
            }

            log.debug("Script strategy [{}] computed scores for {} stocks", strategy.getStrategyCode(), scores.size());
        } catch (Exception e) {
            log.error("Failed to execute script strategy [{}]: {}", strategy.getStrategyCode(), e.getMessage(), e);
        }

        return scores;
    }

    /**
     * 选取Top N股票，等权
     */
    private Map<String, Double> selectTopStocks(Map<String, Double> scores, int topN) {
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topN)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> 1.0 / Math.min(topN, scores.size())
                ));
    }

    // ======================== Groovy 绑定变量辅助方法 ========================

    /**
     * 加载候选股票的行业映射（code → industry）
     * 从 stock_info 表批量查询
     */
    private Map<String, String> loadIndustryMap(List<MarketDailyBar> bars) {
        List<String> codes = bars.stream().map(b -> {
            String sym = b.getSymbol();
            int dot = sym.lastIndexOf('.');
            return dot > 0 ? sym.substring(0, dot) : sym;
        }).distinct().toList();

        if (codes.isEmpty()) return Map.of();

        Map<String, String> result = new HashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT code, industry FROM stock_info WHERE code IN (" +
                             codes.stream().map(c -> "?").collect(Collectors.joining(",")) + ")")) {
            for (int i = 0; i < codes.size(); i++) {
                ps.setString(i + 1, codes.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString("code"), rs.getString("industry"));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load industry map: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 加载候选股票的基本信息映射（code → {listDate, totalShare, name}）
     */
    private Map<String, Map<String, Object>> loadStockInfoMap(List<MarketDailyBar> bars) {
        List<String> codes = bars.stream().map(b -> {
            String sym = b.getSymbol();
            int dot = sym.lastIndexOf('.');
            return dot > 0 ? sym.substring(0, dot) : sym;
        }).distinct().toList();

        if (codes.isEmpty()) return Map.of();

        Map<String, Map<String, Object>> result = new HashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT code, name, list_date, total_share, total_market_cap FROM stock_info WHERE code IN (" +
                             codes.stream().map(c -> "?").collect(Collectors.joining(",")) + ")")) {
            for (int i = 0; i < codes.size(); i++) {
                ps.setString(i + 1, codes.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> info = new HashMap<>();
                    info.put("name", rs.getString("name"));
                    info.put("listDate", rs.getDate("list_date") != null ? rs.getDate("list_date").toLocalDate() : null);
                    info.put("totalShare", rs.getBigDecimal("total_share"));
                    info.put("totalMarketCap", rs.getBigDecimal("total_market_cap"));
                    result.put(rs.getString("code"), info);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load stock info map: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 加载指定因子在最近 N 天内的历史值
     * 格式: { factorCode -> { symbol -> [FactorValue...] } }
     */
    private Map<String, Map<String, List<FactorValue>>> loadHistoricalFactors(
            Set<String> factorCodes, LocalDate endDate, int lookbackDays) {
        Map<String, Map<String, List<FactorValue>>> result = new HashMap<>();
        LocalDate startDate = endDate.minusDays(lookbackDays);

        for (String factorCode : factorCodes) {
            try {
                List<FactorValue> fvs = clickHouseFactorValueService.findByFactorCodeAndDateRange(
                        factorCode, startDate, endDate);
                if (fvs == null || fvs.isEmpty()) continue;

                Map<String, List<FactorValue>> symbolMap = fvs.stream()
                        .collect(Collectors.groupingBy(FactorValue::getSymbol));
                result.put(factorCode, symbolMap);
            } catch (Exception e) {
                log.debug("Failed to load historical factors for {}: {}", factorCode, e.getMessage());
            }
        }
        return result;
    }

    /**
     * 判断是否涨停（无法买入）
     * 使用 LimitUpUtils 统一处理板块差异、ST股、创业板改革日期。
     * - 主板：10%（ST 5%）
     * - 创业板 300/301：2020-08-24前10%，之后20%
     * - 科创板 688：20%
     * - 北交所：30%
     */
    private boolean isLimitUp(MarketDailyBar bar) {
        if (bar.getPreClose() == null || bar.getPreClose().doubleValue() <= 0) return false;
        if (bar.getPctChg() == null) return false;
        double pct = bar.getPctChg().doubleValue();
        boolean isSt = LimitUpUtils.isStName(bar.getName());
        return LimitUpUtils.isLimitUp(pct, bar.getSymbol(), bar.getTradeDate(), isSt);
    }

    /**
     * 判断是否跌停（无法卖出）
     */
    private boolean isLimitDown(MarketDailyBar bar) {
        if (bar.getPreClose() == null || bar.getPreClose().doubleValue() <= 0) return false;
        if (bar.getPctChg() == null) return false;
        double pct = bar.getPctChg().doubleValue();
        boolean isSt = LimitUpUtils.isStName(bar.getName());
        return LimitUpUtils.isLimitDown(pct, bar.getSymbol(), bar.getTradeDate(), isSt);
    }

    /**
     * 判断是否停牌（成交量为0）
     */
    private boolean isSuspended(MarketDailyBar bar) {
        return bar.getVol() == null || bar.getVol().doubleValue() <= 0;
    }

    /**
     * 计算滑点后的成交价格
     * FIXED: 固定比例滑点（买入加滑点，卖出减滑点）
     * VOLUME: 按成交量比例计算滑点，滑点 = baseRate × (tradeAmount / dayAmount)^0.5
     */
    private double applySlippage(double price, boolean isBuy, double baseSlippage,
                                 double tradeAmount, double dayAmount, String model) {
        double slip = baseSlippage;
        if ("VOLUME".equalsIgnoreCase(model) && dayAmount > 0) {
            // 成交量比例滑点：成交额占日成交额比例越高，滑点越大
            double ratio = Math.min(tradeAmount / dayAmount, 1.0);
            slip = baseSlippage * (1 + Math.sqrt(ratio) * 10);
        }
        return isBuy ? price * (1 + slip) : price * (1 - slip);
    }

    /**
     * 计算交易费用
     * 买入：佣金（双向，最低5元）+ 过户费（沪深双向）
     * 卖出：佣金（双向，最低5元）+ 印花税（单向，万5）+ 过户费（沪深双向）
     *
     * @param symbol          股票代码（含交易所后缀，如 600000.SH、000001.SZ）
     * @param transferFeeRate 过户费率（沪深默认 0.00002 = 0.02‰，2022年起统一双向收取）
     */
    private double calcFee(double amount, boolean isSell, double commissionRate,
                           double stampTaxRate, double minCommission,
                           String symbol, double transferFeeRate) {
        double commission = Math.max(amount * commissionRate, minCommission);
        double stampTax = isSell ? amount * stampTaxRate : 0;
        // 过户费：沪深股票（.SH/.SZ后缀）双向收取，北交所不收取
        double transferFee = (symbol != null && (symbol.endsWith(".SH") || symbol.endsWith(".SZ")))
                ? amount * transferFeeRate : 0;
        return commission + stampTax + transferFee;
    }

    /**
     * 处理分红除权事件，并同步更新复权因子。
     * 在每个交易日开盘前处理，包括：
     * 1. 送股/转增：增加持仓股数（positions map 直接修改）
     * 2. 现金分红：以现金形式到账（暂时不计入，由外部通过返回值处理）
     * <p>
     * 注意：分红处理不产生交易费用，因为是公司行为而非主动交易。
     *
     * @param positions  当前持仓 (symbol -> shares)，会被直接修改
     * @param cashRef    长度为1的数组，用于返回分红现金（供调用方加到 cash 上）
     * @param barMap     当日行情快照
     * @param today      当前日期
     * @param tradeLog   交易日志（分红事件会记录为 DIVIDEND 类型）
     * @param adjFactors 复权因子 map（会被直接修改）
     */
    private void processDividendEvents(Map<String, Double> positions, double[] cashRef,
                                       Map<String, MarketDailyBar> barMap,
                                       LocalDate today, List<Map<String, Object>> tradeLog,
                                       Map<String, Double> adjFactors) {
        double totalDividendCash = 0.0;

        for (Map.Entry<String, Double> pos : positions.entrySet()) {
            String symbol = pos.getKey();
            double shares = pos.getValue();
            if (shares <= 0) continue;

            // 查询当日是否有除权除息事件
            BigDecimal cashDiv = dividendService.getCashDividend(symbol, today);
            BigDecimal stockConvert = dividendService.getStockConvertRatio(symbol, today);

            boolean hasDividend = cashDiv != null && cashDiv.doubleValue() > 0;
            boolean hasStockConvert = stockConvert != null && stockConvert.doubleValue() > 0;

            if (!hasDividend && !hasStockConvert) continue;

            MarketDailyBar bar = barMap.get(symbol);
            String name = bar != null ? bar.getName() : symbol;

            // 更新前复权因子
            double curAdj = adjFactors.getOrDefault(symbol, 1.0);
            if (hasStockConvert) {
                // 送转：复权因子除以(1+比例)，使历史价格等比例下调
                curAdj = curAdj / (1 + stockConvert.doubleValue());
            }
            if (hasDividend && bar != null && bar.getPreClose() != null && bar.getPreClose().doubleValue() > 0) {
                // 现金分红：复权因子调整 = preClose-cashDiv / preClose
                double preClose = bar.getPreClose().doubleValue();
                curAdj = curAdj * (preClose - cashDiv.doubleValue()) / preClose;
            }
            adjFactors.put(symbol, curAdj);

            // 1. 处理送股/转增：股数增加
            if (hasStockConvert) {
                double newShares = shares * (1 + stockConvert.doubleValue());
                double addedShares = newShares - shares;
                pos.setValue(newShares);
                log.debug("[{}] {} 送转: {} → {} (增加 {} 股)", today, symbol, shares, newShares, addedShares);
            }

            // 2. 处理现金分红
            if (hasDividend) {
                double dividendAmount = shares * cashDiv.doubleValue();
                totalDividendCash += dividendAmount;

                // 记录分红事件
                Map<String, Object> trade = new HashMap<>();
                trade.put("date", today.toString());
                trade.put("symbol", symbol);
                trade.put("name", name);
                trade.put("action", "DIVIDEND");
                trade.put("price", round(cashDiv.doubleValue(), 4));  // 每股派息
                trade.put("amount", round(shares, 2));               // 持仓股数
                trade.put("total", round(dividendAmount, 2));         // 分红总额
                trade.put("commission", 0.0);
                trade.put("fee", 0.0);
                tradeLog.add(trade);

                log.debug("[{}] {} 分红: {} 股 × {} 元/股 = {} 元",
                        today, symbol, shares, cashDiv.doubleValue(), dividendAmount);
            }
        }

        // 对未持仓但有除权事件的股票也更新复权因子（避免因选股价格跳变影响评分）
        for (Map.Entry<String, MarketDailyBar> entry : barMap.entrySet()) {
            String symbol = entry.getKey();
            if (positions.containsKey(symbol)) continue; // 持仓的已处理
            MarketDailyBar bar = entry.getValue();
            if (bar.getPreClose() == null || bar.getPreClose().doubleValue() <= 0) continue;
            BigDecimal cashDiv = dividendService.getCashDividend(symbol, today);
            BigDecimal stockConvert = dividendService.getStockConvertRatio(symbol, today);
            boolean hasDividend = cashDiv != null && cashDiv.doubleValue() > 0;
            boolean hasStockConvert = stockConvert != null && stockConvert.doubleValue() > 0;
            if (!hasDividend && !hasStockConvert) continue;
            double curAdj = adjFactors.getOrDefault(symbol, 1.0);
            if (hasStockConvert) curAdj = curAdj / (1 + stockConvert.doubleValue());
            if (hasDividend) {
                double preClose = bar.getPreClose().doubleValue();
                curAdj = curAdj * (preClose - cashDiv.doubleValue()) / preClose;
            }
            adjFactors.put(symbol, curAdj);
        }

        // 返回分红现金（调用方会加到 cash 上）
        cashRef[0] = totalDividendCash;
    }

    /**
     * 仅更新复权因子（不做持仓调整），用于 dividendReinvest=false 时保持价格连续性。
     */
    private void updateAdjFactors(Map<String, Double> adjFactors,
                                  Map<String, MarketDailyBar> barMap, LocalDate today) {
        for (Map.Entry<String, MarketDailyBar> entry : barMap.entrySet()) {
            String symbol = entry.getKey();
            MarketDailyBar bar = entry.getValue();
            if (bar.getPreClose() == null || bar.getPreClose().doubleValue() <= 0) continue;
            BigDecimal cashDiv = dividendService.getCashDividend(symbol, today);
            BigDecimal stockConvert = dividendService.getStockConvertRatio(symbol, today);
            boolean hasDividend = cashDiv != null && cashDiv.doubleValue() > 0;
            boolean hasStockConvert = stockConvert != null && stockConvert.doubleValue() > 0;
            if (!hasDividend && !hasStockConvert) continue;
            double curAdj = adjFactors.getOrDefault(symbol, 1.0);
            if (hasStockConvert) curAdj = curAdj / (1 + stockConvert.doubleValue());
            if (hasDividend) {
                double preClose = bar.getPreClose().doubleValue();
                curAdj = curAdj * (preClose - cashDiv.doubleValue()) / preClose;
            }
            adjFactors.put(symbol, curAdj);
        }
    }

    /**
     * 根据 orderType 获取实际成交价格。
     * CLOSE    → 当日收盘价（默认，最保守）
     * NEXT_OPEN → 次日开盘价（从预加载的 nextDayBarMap 获取，更真实）
     * VWAP     → 当日成交量加权均价，用 (high+low+close)/3 近似
     *
     * @param bar           当日行情
     * @param tradingDates  全部交易日列表
     * @param di            当前交易日下标
     * @param orderType     成交模式
     * @param nextDayBarMap 次日行情快照（NEXT_OPEN 模式使用）
     * @return 成交参考价格
     */
    private double getExecutionPrice(MarketDailyBar bar, List<LocalDate> tradingDates,
                                     int di, String orderType,
                                     Map<String, MarketDailyBar> nextDayBarMap) {
        double close = bar.getClose().doubleValue();
        if ("NEXT_OPEN".equalsIgnoreCase(orderType)) {
            // 次日开盘价：从预加载的次日行情中获取真实开盘价
            MarketDailyBar nextBar = nextDayBarMap.get(bar.getSymbol());
            if (nextBar != null && nextBar.getOpen() != null && nextBar.getOpen().doubleValue() > 0) {
                return nextBar.getOpen().doubleValue();
            }
            // 回退：次日数据缺失时使用当日收盘价
            return close;
        } else if ("VWAP".equalsIgnoreCase(orderType)) {
            // VWAP 近似：(high + low + close) / 3
            double high = bar.getHigh() != null ? bar.getHigh().doubleValue() : close;
            double low = bar.getLow() != null ? bar.getLow().doubleValue() : close;
            return (high + low + close) / 3.0;
        }
        // CLOSE（默认）
        return close;
    }

    private List<Map<String, Object>> rebalance(Map<String, Double> oldPositions,
                                                Map<String, Double> targetWeights,
                                                Map<String, MarketDailyBar> barMap,
                                                double portfolioValue,
                                                double commission, double slippage,
                                                LocalDate date,
                                                Map<String, Double> positionValues,
                                                String slippageModel,
                                                double stampTaxRate,
                                                double minCommission,
                                                boolean limitFilter,
                                                boolean suspendFilter,
                                                double transferFeeRate,
                                                String orderType,
                                                List<LocalDate> tradingDates,
                                                int di,
                                                Map<String, MarketDailyBar> nextDayBarMap,
                                                Map<String, Double> positionCosts,
                                                double buyScale) {
        List<Map<String, Object>> trades = new ArrayList<>();

        // 记录卖出
        for (String symbol : new HashSet<>(oldPositions.keySet())) {
            if (!targetWeights.containsKey(symbol)) {
                MarketDailyBar bar = barMap.get(symbol);
                if (bar == null) continue;

                // 停牌过滤
                if (suspendFilter && isSuspended(bar)) {
                    log.debug("[{}] {} 停牌，跳过卖出", date, symbol);
                    continue;
                }
                // 跌停过滤
                if (limitFilter && isLimitDown(bar)) {
                    log.debug("[{}] {} 跌停，跳过卖出", date, symbol);
                    continue;
                }

                double execPrice = getExecutionPrice(bar, tradingDates, di, orderType, nextDayBarMap);
                double closePrice = bar.getClose().doubleValue();
                double shares = oldPositions.getOrDefault(symbol, 0.0);
                double amount = shares * closePrice;
                double dayAmount = bar.getAmount() != null ? bar.getAmount().doubleValue() * 1000 : 0;
                double price = applySlippage(execPrice, false, slippage, amount, dayAmount, slippageModel);
                double fee = calcFee(amount, true, commission, stampTaxRate, minCommission, symbol, transferFeeRate);

                Map<String, Object> trade = new HashMap<>();
                trade.put("date", date.toString());
                trade.put("symbol", symbol);
                trade.put("name", bar.getName());
                trade.put("action", "SELL");
                trade.put("price", round(price, 4));
                trade.put("amount", round(shares, 2));
                trade.put("total", round(amount, 2));
                trade.put("commission", round(fee, 2));
                trade.put("fee", round(fee, 2));
                trades.add(trade);
            }
        }

        // 记录买入
        for (Map.Entry<String, Double> entry : targetWeights.entrySet()) {
            if (!oldPositions.containsKey(entry.getKey())) {
                MarketDailyBar bar = barMap.get(entry.getKey());
                if (bar == null) continue;

                // 停牌过滤
                if (suspendFilter && isSuspended(bar)) {
                    log.debug("[{}] {} 停牌，跳过买入", date, entry.getKey());
                    continue;
                }
                // 涨停过滤
                if (limitFilter && isLimitUp(bar)) {
                    log.debug("[{}] {} 涨停，跳过买入", date, entry.getKey());
                    continue;
                }

                double execPrice = getExecutionPrice(bar, tradingDates, di, orderType, nextDayBarMap);
                double closePrice = bar.getClose().doubleValue();
                double amount = portfolioValue * entry.getValue() * buyScale;
                double dayAmount = bar.getAmount() != null ? bar.getAmount().doubleValue() * 1000 : 0;
                double price = applySlippage(execPrice, true, slippage, amount, dayAmount, slippageModel);
                double fee = calcFee(amount, false, commission, stampTaxRate, minCommission, entry.getKey(), transferFeeRate);

                // 记录买入成本（用于止损止盈计算）
                double totalCost = amount + fee;
                positionCosts.merge(entry.getKey(), totalCost, Double::sum);

                Map<String, Object> trade = new HashMap<>();
                trade.put("date", date.toString());
                trade.put("symbol", entry.getKey());
                trade.put("name", bar.getName());
                trade.put("action", "BUY");
                trade.put("price", round(price, 4));
                trade.put("amount", round(amount / price, 2));
                trade.put("total", round(amount, 2));
                trade.put("commission", round(fee, 2));
                trade.put("fee", round(fee, 2));
                trades.add(trade);
            }
        }

        return trades;
    }

    /**
     * 重新计算现金
     */
    private double recalcCash(Map<String, Double> oldPositions,
                              Map<String, Double> targetWeights,
                              Map<String, MarketDailyBar> barMap,
                              double portfolioValue, double commission, double slippage,
                              String slippageModel,
                              double stampTaxRate,
                              double minCommission,
                              boolean limitFilter,
                              boolean suspendFilter,
                              double transferFeeRate,
                              double buyScale) {
        double totalFee = 0;

        // 买入费用（应用缩放）
        for (Map.Entry<String, Double> entry : targetWeights.entrySet()) {
            if (oldPositions.containsKey(entry.getKey())) continue;
            MarketDailyBar bar = barMap.get(entry.getKey());
            if (bar == null) continue;
            if (suspendFilter && isSuspended(bar)) continue;
            if (limitFilter && isLimitUp(bar)) continue;

            double amount = portfolioValue * entry.getValue() * buyScale;
            totalFee += calcFee(amount, false, commission, stampTaxRate, minCommission, entry.getKey(), transferFeeRate);
        }

        // 卖出费用
        for (String symbol : oldPositions.keySet()) {
            if (targetWeights.containsKey(symbol)) continue;
            MarketDailyBar bar = barMap.get(symbol);
            if (bar == null) continue;
            if (suspendFilter && isSuspended(bar)) continue;
            if (limitFilter && isLimitDown(bar)) continue;

            double amount = oldPositions.get(symbol) * bar.getClose().doubleValue();
            totalFee += calcFee(amount, true, commission, stampTaxRate, minCommission, symbol, transferFeeRate);
        }

        // 实际投入金额（扣除被过滤掉的买入，应用缩放）
        double investedValue = 0;
        for (Map.Entry<String, Double> entry : targetWeights.entrySet()) {
            if (oldPositions.containsKey(entry.getKey())) continue;
            MarketDailyBar bar = barMap.get(entry.getKey());
            if (bar == null) continue;
            if (suspendFilter && isSuspended(bar)) continue;
            if (limitFilter && isLimitUp(bar)) continue;
            investedValue += portfolioValue * entry.getValue() * buyScale;
        }

        // 扣除保留持仓的市值：这些持仓没卖，不能当现金用
        double keptValue = 0;
        for (String sym : oldPositions.keySet()) {
            if (!targetWeights.containsKey(sym)) continue;
            MarketDailyBar bar = barMap.get(sym);
            if (bar == null) continue;
            keptValue += oldPositions.get(sym) * bar.getClose().doubleValue();
        }

        double cash = portfolioValue - keptValue - investedValue - totalFee;
        if (cash < -0.01) {
            log.warn("Residual negative cash after rebalance: {} (scale={}, portfolioValue={}, keptValue={}, invested={}, fee={})",
                    String.format("%.2f", cash), String.format("%.4f", buyScale),
                    String.format("%.2f", portfolioValue), String.format("%.2f", keptValue),
                    String.format("%.2f", investedValue), String.format("%.2f", totalFee));
        }
        return Math.max(0, cash);
    }

    /**
     * 再平衡触发判断：支持日历频率+偏离阈值+波动率自适应+混合
     */
    private boolean shouldRebalance(LocalDate today, LocalDate lastDate, String freq,
                                    double currentDeviation, double volatilityLevel,
                                    double threshold) {
        if (lastDate == null) return true;

        // 日历频率基础判断
        boolean calendarTrigger = switch (freq.toUpperCase()) {
            case "DAILY" -> true;
            case "WEEKLY" -> today.getYear() != lastDate.getYear() ||
                    today.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR) !=
                            lastDate.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            case "MONTHLY" -> today.getYear() != lastDate.getYear() ||
                    today.getMonthValue() != lastDate.getMonthValue();
            case "QUARTERLY" -> today.getYear() != lastDate.getYear() ||
                    (today.getMonthValue() - 1) / 3 != (lastDate.getMonthValue() - 1) / 3;
            default -> today.getMonthValue() != lastDate.getMonthValue();
        };

        // 根据触发模式组合
        // THRESHOLD: 仅偏离触发
        // VOL_ADAPTIVE: 日历触发（高波动时周频，低波动时月频）
        // HYBRID: 日历+偏离双重触发
        if ("THRESHOLD".equalsIgnoreCase(freq)) {
            return currentDeviation > threshold;
        } else if ("VOL_ADAPTIVE".equalsIgnoreCase(freq)) {
            // 高波动(volatility>0.03日波动≈年化48%) → 周频
            // 低波动(volatility<=0.02日波动≈年化32%) → 月频
            // 中间 → 两周频
            String adaptedFreq = volatilityLevel > 0.03 ? "WEEKLY" :
                    volatilityLevel <= 0.02 ? "MONTHLY" : "WEEKLY";
            return shouldRebalance(today, lastDate, adaptedFreq, 0, 0, threshold);
        } else if ("HYBRID".equalsIgnoreCase(freq)) {
            return calendarTrigger || currentDeviation > threshold;
        } else {
            return calendarTrigger;
        }
    }

    /**
     * 简化版：仅日历频率触发
     */
    private boolean shouldRebalance(LocalDate today, LocalDate lastDate, String freq) {
        return shouldRebalance(today, lastDate, freq, 0, 0, 0);
    }

    /**
     * 构建绩效报告
     */
    private BacktestReport buildReport(BacktestTask task, BacktestResult result) throws Exception {
        int tradingDays = result.tradingDays();
        double years = tradingDays > 0 ? tradingDays / 252.0 : 1.0;

        double totalReturn = result.totalReturn();
        double annualReturn = years > 0 ? Math.pow(1 + totalReturn, 1.0 / years) - 1 : 0;

        // ── 基准收益 ──────────────────────────────────────────────────
        double benchmarkTotalReturn = result.benchmarkTotalReturn();
        double benchmarkAnnualReturn = years > 0 ? Math.pow(1 + benchmarkTotalReturn, 1.0 / years) - 1 : 0;
        double excessAnnualReturn = annualReturn - benchmarkAnnualReturn;

        // ── 从策略净值曲线计算日收益序列 ─────────────────────────────
        List<Double> dailyReturns = new ArrayList<>();
        List<Map<String, Object>> curve = result.equityCurve();
        for (int i = 1; i < curve.size(); i++) {
            double prev = ((Number) curve.get(i - 1).get("value")).doubleValue();
            double curr = ((Number) curve.get(i).get("value")).doubleValue();
            if (prev > 0) dailyReturns.add(curr / prev - 1);
        }

        double meanRet = dailyReturns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = dailyReturns.stream().mapToDouble(r -> (r - meanRet) * (r - meanRet)).average().orElse(0);
        double volatility = Math.sqrt(variance) * Math.sqrt(252);

        double riskFreeRate = 0.03;
        double sharpeRatio = volatility > 0 ? (annualReturn - riskFreeRate) / volatility : 0;
        // 限制异常值（理论上夏普比率不太可能超过 100）
        sharpeRatio = Math.max(-100, Math.min(100, sharpeRatio));

        // Sortino（只考虑下行波动）
        double downside = Math.sqrt(dailyReturns.stream()
                .mapToDouble(r -> r < 0 ? r * r : 0)
                .average().orElse(0)) * Math.sqrt(252);
        double sortinoRatio = downside > 0 ? (annualReturn - riskFreeRate) / downside : 0;
        sortinoRatio = Math.max(-100, Math.min(100, sortinoRatio));

        double calmarRatio = result.maxDrawdown() > 0 ? annualReturn / result.maxDrawdown() : 0;
        calmarRatio = Math.max(-100, Math.min(100, calmarRatio));

        // ── 基准相关指标（Alpha、Beta、Tracking Error、Information Ratio）────────────────────
        double informationRatio = 0.0, alpha = 0.0, beta = 0.0, trackingError = 0.0;
        List<Map<String, Object>> bmCurve = result.benchmarkCurve();
        List<Double> stratRets = new ArrayList<>();
        List<Double> bmRets = new ArrayList<>();

        // 超额收益序列（在 if 外定义，供后续 Alpha 分析使用）
        List<Double> excessReturns = new ArrayList<>();

        if (!bmCurve.isEmpty()) {
            // 建立基准日期→净值 map
            Map<String, Double> bmMap = new HashMap<>();
            for (Map<String, Object> bm : bmCurve) {
                bmMap.put((String) bm.get("date"), ((Number) bm.get("value")).doubleValue());
            }
            for (int i = 1; i < curve.size(); i++) {
                String date = (String) curve.get(i).get("date");
                String prevDate = (String) curve.get(i - 1).get("date");
                Double bmCurr = bmMap.get(date);
                Double bmPrev = bmMap.get(prevDate);
                if (bmCurr != null && bmPrev != null && bmPrev > 0) {
                    double stratRet = ((Number) curve.get(i).get("value")).doubleValue()
                            / ((Number) curve.get(i - 1).get("value")).doubleValue() - 1;
                    double bmRet = bmCurr / bmPrev - 1;
                    stratRets.add(stratRet);
                    bmRets.add(bmRet);
                    excessReturns.add(stratRet - bmRet);
                }
            }

            int n = excessReturns.size();
            if (n > 1) {
                // 信息比率
                double exMean = excessReturns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                double exVar = excessReturns.stream().mapToDouble(r -> (r - exMean) * (r - exMean)).average().orElse(0);
                double exStd = Math.sqrt(exVar) * Math.sqrt(252);
                informationRatio = exStd > 0 ? (exMean * 252) / exStd : 0;
                trackingError = exStd;  // 年化跟踪误差

                // Beta 和 Alpha（CAPM）
                double stratMean = stratRets.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                double bmMean = bmRets.stream().mapToDouble(Double::doubleValue).average().orElse(0);

                double cov = 0, bmVar = 0;
                for (int i = 0; i < n; i++) {
                    cov += (stratRets.get(i) - stratMean) * (bmRets.get(i) - bmMean);
                    bmVar += (bmRets.get(i) - bmMean) * (bmRets.get(i) - bmMean);
                }
                cov /= n;
                bmVar /= n;

                beta = bmVar > 0 ? cov / bmVar : 1.0;
                // Alpha = 策略平均收益 - Beta * 基准平均收益（日频，年化）
                alpha = (stratMean - beta * bmMean) * 252;
            }
        }

        // ── 胜率 & 盈亏比（从配对交易统计）─────────────────────────
        double winRate = 0.5, avgWin = 0.01, avgLoss = -0.008, plRatio = 1.25;
        List<Map<String, Object>> allTrades = result.tradeLog();
        Map<String, Double> buyPrices = new HashMap<>();
        List<Double> tradeRets = new ArrayList<>();
        for (Map<String, Object> t : allTrades) {
            String sym = (String) t.get("symbol");
            String action = (String) t.get("action");
            double price = ((Number) t.get("price")).doubleValue();
            if ("BUY".equals(action)) {
                buyPrices.put(sym, price);
            } else if ("SELL".equals(action) && buyPrices.containsKey(sym)) {
                double bp = buyPrices.remove(sym);
                if (bp > 0) tradeRets.add((price - bp) / bp);
            }
        }
        if (!tradeRets.isEmpty()) {
            long wins = tradeRets.stream().filter(r -> r > 0).count();
            long loses = tradeRets.stream().filter(r -> r < 0).count();
            winRate = (double) wins / tradeRets.size();
            avgWin = tradeRets.stream().filter(r -> r > 0).mapToDouble(Double::doubleValue).average().orElse(0.01);
            avgLoss = tradeRets.stream().filter(r -> r < 0).mapToDouble(Double::doubleValue).average().orElse(-0.008);
            plRatio = loses > 0 && avgLoss != 0 ? Math.abs(avgWin / avgLoss) : 1.25;
        }

        // ── 已实现收益曲线（按交易配对，逐日累计）────────────────────────────
        // BUY 时记录成本；SELL/STOP_LOSS_SELL 时计算已实现PnL并按日期汇总
        List<Map<String, Object>> realizedCurve = new ArrayList<>();
        {
            double initialCapitalLocal = result.initialCapital();
            Map<String, Double> buyCostMap = new HashMap<>();   // symbol -> 买入成本（含手续费）
            Map<String, Double> buySharesMap = new HashMap<>();  // symbol -> 持有股数
            Map<String, Double> dailyRealizedPnl = new TreeMap<>();  // date -> 当日新增已实现PnL
            for (Map<String, Object> t : allTrades) {
                String sym = (String) t.get("symbol");
                String action = (String) t.get("action");
                double tTotal = t.get("total") != null ? ((Number) t.get("total")).doubleValue() : 0;
                double tFee = t.get("fee") != null ? ((Number) t.get("fee")).doubleValue() : 0;
                String tDate = (String) t.get("date");
                if ("BUY".equals(action)) {
                    // 买入成本 = 金额 + 手续费
                    buyCostMap.merge(sym, tTotal + tFee, Double::sum);
                    double shares = t.get("amount") != null ? ((Number) t.get("amount")).doubleValue() : 0;
                    buySharesMap.merge(sym, shares, Double::sum);
                } else if (("SELL".equals(action) || "STOP_LOSS_SELL".equals(action))
                        && buyCostMap.containsKey(sym)) {
                    // 已实现 = 卖出金额(扣费后) - 对应成本
                    double proceeds = tTotal - tFee;
                    double cost = buyCostMap.remove(sym);
                    buySharesMap.remove(sym);
                    double pnl = proceeds - cost;
                    dailyRealizedPnl.merge(tDate, pnl, Double::sum);
                }
            }
            // 对 equityCurve 的每个日期，前向填充已实现PnL累计值 → 净值
            double cumPnl = 0;
            for (Map<String, Object> ep : result.equityCurve()) {
                String d = (String) ep.get("date");
                if (dailyRealizedPnl.containsKey(d)) {
                    cumPnl += dailyRealizedPnl.get(d);
                }
                Map<String, Object> rp = new HashMap<>();
                rp.put("date", d);
                // 已实现净值 = 1 + 累计已实现PnL / 初始资金
                rp.put("value", round(1.0 + cumPnl / initialCapitalLocal, 6));
                realizedCurve.add(rp);
            }
        }

        // ── 超额收益分析（参考 baostock 用户案例的 Alpha 分析表）────────────────
        double excessMean = 0, excessStd = 0, excessWinRate = 0.5, excessMaxDrawdown = 0, alphaContribution = 0;
        if (!excessReturns.isEmpty()) {
            // 超额收益均值（年化）
            excessMean = excessReturns.stream().mapToDouble(Double::doubleValue).average().orElse(0) * 252;
            // 超额收益标准差（年化）
            double exVar2 = excessReturns.stream().mapToDouble(r -> r * r).average().orElse(0)
                    - Math.pow(excessReturns.stream().mapToDouble(Double::doubleValue).average().orElse(0), 2);
            excessStd = Math.sqrt(Math.max(exVar2, 0)) * Math.sqrt(252);
            // 超额胜率：跑赢大盘的天数占比
            long exWins = excessReturns.stream().filter(r -> r > 0).count();
            excessWinRate = (double) exWins / excessReturns.size();
            // 超额收益最大回撤（复利累计超额曲线，避免算术累加放大偏差）
            double cumExcess = 1.0, peakExcess = 1.0;
            for (double er : excessReturns) {
                cumExcess *= (1 + er);
                if (cumExcess > peakExcess) peakExcess = cumExcess;
                double dd = 1 - cumExcess / peakExcess;
                if (dd > excessMaxDrawdown) excessMaxDrawdown = dd;
            }
            // Alpha贡献占比 = |alpha| / (|alpha| + |beta * benchmark_return|)
            // 这样与市场贡献之和为100%，避免CAPM残差导致>100%无意义值
            double absAlpha = Math.abs(alpha);
            double absMarket = Math.abs(beta * benchmarkAnnualReturn);
            double denom = absAlpha + absMarket;
            alphaContribution = denom > 1e-10 ? absAlpha / denom : 0;
        }

        return BacktestReport.builder()
                .taskId(task.getId())
                .strategyCode(task.getStrategyCode())
                .totalReturn(bd(totalReturn))
                .annualReturn(bd(annualReturn))
                .benchmarkReturn(bd(benchmarkTotalReturn))
                .benchmarkAnnualReturn(bd(benchmarkAnnualReturn))
                .excessReturn(bd(excessAnnualReturn))
                .volatility(bd(volatility))
                .sharpeRatio(bd(sharpeRatio))
                .sortinoRatio(bd(sortinoRatio))
                .calmarRatio(bd(calmarRatio))
                .maxDrawdown(bd(result.maxDrawdown()))
                .maxDrawdownDuration(result.maxDrawdownDuration())
                .informationRatio(bd(informationRatio))
                .alpha(bd(alpha))
                .beta(bd(beta))
                .trackingError(bd(trackingError))
                .downsideRisk(bd(downside))
                .totalTrades(result.totalTrades())
                .winRate(bd(winRate))
                .avgWinReturn(bd(avgWin))
                .avgLossReturn(bd(avgLoss))
                .profitLossRatio(bd(plRatio))
                .excessMean(bd(excessMean))
                .excessStd(bd(excessStd))
                .excessWinRate(bd(excessWinRate))
                .excessMaxDrawdown(bd(excessMaxDrawdown))
                .alphaContribution(bd(alphaContribution))
                .equityCurveJson(objectMapper.writeValueAsString(result.equityCurve()))
                .benchmarkCurveJson(objectMapper.writeValueAsString(result.benchmarkCurve()))
                .drawdownSeriesJson(objectMapper.writeValueAsString(result.equityCurve().stream()
                        .map(p -> Map.of("date", p.get("date"), "drawdown", p.get("drawdown")))
                        .collect(Collectors.toList())))
                .monthlyReturnsJson(objectMapper.writeValueAsString(result.monthlyReturns()))
                .positionHistoryJson(objectMapper.writeValueAsString(result.positionHistory()))
                .tradeLogJson(objectMapper.writeValueAsString(result.tradeLog().stream()
                        .limit(500)
                        .collect(Collectors.toList())))
                .realizedCurveJson(objectMapper.writeValueAsString(realizedCurve))
                .build();
    }

    private void sendProgress(Long taskId, String stage, int pct, String message) {
        try {
            if (messagingTemplate != null) {
                messagingTemplate.convertAndSend("/topic/backtest/" + taskId,
                        Map.of("taskId", taskId, "stage", stage, "progress", pct, "message", message));
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 回测进行中：携带当天净值数据点（用于前端实时绘图）
     * 消息格式：{ taskId, stage:"RUNNING", progress, date, stratValue, bmValue }
     */
    private void sendProgressWithCurve(Long taskId, int pct, String date,
                                       double stratValue, double bmValue) {
        try {
            if (messagingTemplate != null) {
                Map<String, Object> msg = new HashMap<>();
                msg.put("taskId", taskId);
                msg.put("stage", "RUNNING");
                msg.put("progress", pct);
                msg.put("message", "回测进行中 " + date);
                msg.put("date", date);
                msg.put("stratValue", stratValue);
                msg.put("bmValue", bmValue);
                messagingTemplate.convertAndSend("/topic/backtest/" + taskId, msg);
            }
        } catch (Exception ignored) {
        }
    }

    private BigDecimal bd(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return BigDecimal.ZERO;
        return BigDecimal.valueOf(v).setScale(6, RoundingMode.HALF_UP);
    }

    private double round(double v, int scale) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0;
        return BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }

    private List<FactorWeight> parseFactorConfig(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            var node = objectMapper.readTree(json);
            var factorsNode = node.get("factors");
            if (factorsNode == null || !factorsNode.isArray()) return List.of();
            List<FactorWeight> result = new ArrayList<>();
            for (var fn : factorsNode) {
                result.add(new FactorWeight(
                        fn.get("code").asText(),
                        fn.get("weight").asDouble()
                ));
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 计算收益率百分比
     */
    private double returnPct(double currentValue, double cost) {
        if (cost <= 0) return 0;
        return (currentValue - cost) / cost;
    }

    /**
     * 基于近期IC/IR计算动态因子权重（与StockScreenService.getDynamicWeights逻辑对齐）
     *
     * @param factorWeights 因子列表（含静态配置权重）
     * @param weightMode    权重模式：IC / IR
     * @param rebalanceDate 调仓日期
     * @return factorCode -> 动态权重系数（已与静态权重乘算）
     */
    private Map<String, Double> computeDynamicFactorWeights(List<FactorWeight> factorWeights,
                                                            String weightMode,
                                                            LocalDate rebalanceDate) {
        Map<String, Double> dynamicWeights = new LinkedHashMap<>();
        Map<String, Double> icScores = new LinkedHashMap<>();

        // 1. 获取每个因子的IC/IR值
        for (FactorWeight fw : factorWeights) {
            String fc = fw.factorCode;
            try {
                List<Double> icValues = factorIcService.getIcHistory(fc, rebalanceDate, 60);
                if (icValues == null || icValues.isEmpty()) {
                    log.debug("[BacktestEngine DynamicWeight] 因子 {} 在 {} 无IC历史数据，使用静态权重", fc, rebalanceDate);
                    icScores.put(fc, 1.0);
                    continue;
                }

                double score;
                if ("IR".equalsIgnoreCase(weightMode)) {
                    double avg = icValues.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                    double std = Math.sqrt(icValues.stream().mapToDouble(v -> Math.pow(v - avg, 2)).average().orElse(0));
                    score = std > 0 ? Math.abs(avg) / std : 0;
                } else {
                    score = icValues.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                }
                icScores.put(fc, score);
            } catch (Exception e) {
                log.debug("[BacktestEngine DynamicWeight] 获取因子 {} IC失败: {}", fc, e.getMessage());
                icScores.put(fc, 1.0);
            }
        }

        // 2. 计算IC>0的因子IC之和
        double sumPositiveIc = icScores.values().stream()
                .filter(v -> v > 0)
                .mapToDouble(Double::doubleValue)
                .sum();

        if (sumPositiveIc > 0) {
            for (FactorWeight fw : factorWeights) {
                String fc = fw.factorCode;
                double ic = icScores.getOrDefault(fc, 1.0);
                if (ic > 0) {
                    double normalized = ic / sumPositiveIc;
                    normalized = Math.max(0.1, Math.min(5.0, normalized));
                    dynamicWeights.put(fc, normalized * fw.weight);
                } else {
                    dynamicWeights.put(fc, 0.0);
                }
            }
        } else {
            // 所有IC均<=0，回退到静态权重
            log.debug("[BacktestEngine DynamicWeight] {} 所有因子IC均<=0，回退静态权重", rebalanceDate);
            for (FactorWeight fw : factorWeights) {
                dynamicWeights.put(fw.factorCode, fw.weight);
            }
        }

        return dynamicWeights;
    }

    /**
     * 加载所有股票的退市日期映射（幸存者偏差修复）。
     * 从 stock_info 表查询 delist_date 字段，构建 symbol -> delistDate 的映射。
     * 如果 stock_info 中无退市日期数据，则返回空 map（不影响现有逻辑）。
     */
    private Map<String, LocalDate> loadDelistDateMap() {
        if (stockInfoMapper == null) {
            return Map.of();
        }
        try {
            List<StockInfo> list = stockInfoMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<StockInfo>()
                            .isNotNull("delist_date")
                            .gt("delist_date", "1900-01-01")
            );
            Map<String, LocalDate> map = new HashMap<>();
            for (StockInfo info : list) {
                if (info.getCode() != null && info.getDelistDate() != null) {
                    map.put(info.getCode(), info.getDelistDate());
                }
            }
            return map;
        } catch (Exception e) {
            log.warn("加载退市日期映射失败: {}", e.getMessage());
            return Map.of();
        }
    }

    record FactorWeight(String factorCode, double weight) {
    }

    record BacktestResult(
            double totalReturn,
            double finalValue,
            double initialCapital,
            double maxDrawdown,
            int maxDrawdownDuration,
            int totalTrades,
            List<Double> tradeReturns,
            List<Map<String, Object>> equityCurve,
            List<Map<String, Object>> benchmarkCurve,
            List<Map<String, Object>> drawdownSeries,
            List<Map<String, Object>> monthlyReturns,
            List<Map<String, Object>> positionHistory,
            List<Map<String, Object>> tradeLog,
            int tradingDays,
            double benchmarkTotalReturn
    ) {
    }
}
