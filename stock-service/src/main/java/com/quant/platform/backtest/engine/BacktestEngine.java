package com.quant.platform.backtest.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.backtest.domain.BacktestReport;
import com.quant.platform.backtest.domain.BacktestTask;
import com.quant.platform.backtest.domain.RebalanceRecord;
import com.quant.platform.backtest.mapper.BacktestReportMapper;
import com.quant.platform.backtest.mapper.BacktestTaskMapper;
import com.quant.platform.backtest.mapper.EquityCurveMapper;
import com.quant.platform.backtest.mapper.RebalanceRecordMapper;
import com.quant.platform.factor.domain.FactorValue;
import com.quant.platform.factor.ic.service.FactorIcService;
import com.quant.platform.factor.mapper.FactorValueMapper;
import com.quant.platform.factor.service.ClickHouseFactorValueService;
import com.quant.platform.market.domain.MarketDailyBar;
import com.quant.platform.market.service.MarketDataService;
import com.quant.platform.screen.dto.ScreenRequest;
import com.quant.platform.screen.dto.ScreenResult;
import com.quant.platform.screen.service.StockScreenService;
import com.quant.platform.stock.analysis.engine.SellSignalEngine;
import com.quant.platform.stock.service.DividendService;
import com.quant.platform.strategy.domain.StrategyDefinition;
import com.quant.platform.strategy.service.StrategyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import com.quant.platform.common.enums.JobStatus;
/**
 * 核心回测引擎
 * 基于事件驱动的历史模拟框架，支持因子选股策略回测
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestEngine {

    /** 单笔成交金额占日成交额上限（复刻模拟盘 8% 规则） */

    private final BacktestTaskMapper taskMapper;
    private final BacktestReportMapper reportMapper;
    private final MarketDataService marketDataService;
    private final FactorValueMapper factorValueMapper;
    private final StrategyService strategyService;
    private final DividendService dividendService;
    private final ObjectMapper objectMapper;
    /** 外部数据加载（行业/基本信息/历史因子/退市日期）—— God Class 拆分 Phase 2 */
    private final BacktestDataLoader backtestDataLoader;
    /** WebSocket 进度推送 —— God Class 拆分 Phase 2 */
    private final BacktestProgressNotifier progressNotifier;
    /** 绩效报告构建 + 逐日净值落库 —— God Class 拆分 Phase 2 */
    private final BacktestReportBuilder backtestReportBuilder;
    /** 因子打分 / Top N 选股 / 动态权重 —— God Class 拆分 Phase 5 */
    private final BacktestScoring backtestScoring;
    /** 调仓触发判断 / 成交明细生成 / 现金重算 —— God Class 拆分 Phase 5 */
    private final BacktestRebalancer backtestRebalancer;
    /** 止损止盈 / 技术面卖点等被动退出 —— God Class 拆分 Phase 5 */
    private final BacktestRiskExit backtestRiskExit;
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
    private RebalanceRecordMapper rebalanceRecordMapper;
    /**
     * 逐日净值写入（SCREEN 模式可用性校验）
     */
    @Autowired(required = false)
    private EquityCurveMapper equityCurveMapper;
    @Autowired(required = false)
    private SellSignalEngine sellSignalEngine;

    /**
     * 异步运行回测
     * 注意：不加 @Transactional，避免与调用方事务冲突导致读不到新写入的记录。
     * 每个持久化操作各自用独立事务（JPA save 默认在自身事务中执行）。
     */
    @Async("backtestTaskExecutor")
    public void runBacktest(Long taskId) {
        runInternal(taskId, true);
    }

    /**
     * 同步执行回测（不加 @Async，用于参数优化批量调用）
     * 任务记录必须已存入 DB
     */
    public void runBacktestSync(Long taskId) {
        runInternal(taskId, false);
    }

    /**
     * 回测执行主流程 —— 异步 / 同步两条入口的公共实现。
     *
     * <p>God Class 拆分 Phase 5：原 {@code runBacktest} 与 {@code runBacktestSync} 有 30 余行
     * 完全重复的主干（模式判定 → 前置校验 → 执行 → 建报告 → 落库 → 状态流转），此处合并为单一
     * 实现，两者的全部差异收敛到 {@code notifyProgress} 分支上：</p>
     * <ul>
     *   <li>{@code true}（异步入口）：任务加载带重试（调用方事务可能尚未提交）、推送 WebSocket
     *       进度、完成时打 info 日志、失败时打 error + 堆栈</li>
     *   <li>{@code false}（同步入口）：任务加载不重试、不推进度、失败时仅打 warn 摘要</li>
     * </ul>
     *
     * @param taskId         回测任务 ID
     * @param notifyProgress true=异步入口，false=同步入口
     */
    private void runInternal(Long taskId, boolean notifyProgress) {
        BacktestTask task;
        if (notifyProgress) {
            // 调用方事务可能尚未提交，最多重试 10 次（每次等 200ms）
            task = null;
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
        } else {
            task = taskMapper.selectById(taskId);
            if (task == null) {
                log.error("runBacktestSync: task [{}] not found", taskId);
                return;
            }
        }

        task.setStatus(JobStatus.RUNNING);
        task.setStartedAt(LocalDateTime.now());
        taskMapper.updateById(task);

        if (notifyProgress) {
            // 立即推送状态变更，让前端知道回测已开始
            sendProgress(task.getId(), JobStatus.RUNNING.name(), 0, "回测初始化中...");
        }

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

            task.setStatus(JobStatus.COMPLETED);
            task.setProgress(100);
            task.setCompletedAt(LocalDateTime.now());
            taskMapper.updateById(task);

            if (notifyProgress) {
                sendProgress(taskId, JobStatus.COMPLETED.name(), 100, "回测完成，reportId=" + report.getId());
                log.info("Backtest task [{}] completed, mode={}", taskId, isScreen ? "SCREEN" : "STRATEGY");
            }
        } catch (Exception e) {
            if (notifyProgress) {
                log.error("Backtest task [{}] failed", taskId, e);
            } else {
                log.warn("runBacktestSync task [{}] failed: {}", taskId, e.getMessage());
            }
            task.setStatus(JobStatus.FAILED);
            task.setErrorMessage(e.getMessage());
            taskMapper.updateById(task);
            if (notifyProgress) {
                sendProgress(taskId, JobStatus.FAILED.name(), task.getProgress(), "回测失败: " + e.getMessage());
            }
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
        String slippageModel = task.getSlippageModel() != null ? task.getSlippageModel() : "VOLUME";
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
        // 技术面卖点信号退出开关（默认开启）
        boolean sellSignalEnabled = true;

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

        // ── 加载基准指数行情（含完整性校验与基准初始价确定）──────────────
        // 实现已迁移至 BacktestDataLoader#loadBenchmarkSeries
        BacktestDataLoader.BenchmarkSeries benchmarkSeries =
                backtestDataLoader.loadBenchmarkSeries(task, startDate, endDate, tradingDates);
        Map<LocalDate, Double> benchmarkClose = benchmarkSeries.closes();
        double benchmarkBase = benchmarkSeries.base();

        // 用于基准价格前向填充的变量
        double lastValidBmClose = benchmarkBase;

        log.info("Benchmark base price: {}, firstValidClose: {}, startDateClose: {}", benchmarkBase,
                benchmarkSeries.firstValidClose(), benchmarkSeries.startDateClose());
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
            // 实现已迁移至 BacktestRiskExit#applyStopLossTakeProfit；同上以引用回写现金与成交笔数
            double[] stopCashRef = {cash};
            int[] stopTradeRef = {totalTrades};
            backtestRiskExit.applyStopLossTakeProfit(stopCashRef, stopTradeRef, positions, positionCosts,
                    barMap, adjFactors, tradeLog, today, tradingDates, di, nextDayBarMap, orderType,
                    slippage, slippageModel, commission, stampTaxRate, minCommission, transferFeeRate,
                    limitFilter, suspendFilter, stopLossPct, stopProfitPct);
            cash = stopCashRef[0];
            totalTrades = stopTradeRef[0];

            // ── 技术面卖点信号检查（可选，通过参数 sellSignalEnabled 控制）─────────
            // 实现已迁移至 BacktestRiskExit#applySellSignals；cash/totalTrades 经引用回写保持累加顺序
            double[] signalCashRef = {cash};
            int[] signalTradeRef = {totalTrades};
            backtestRiskExit.applySellSignals(signalCashRef, signalTradeRef, positions, positionCosts,
                    barMap, tradeLog, today, tradingDates, di, nextDayBarMap, orderType, slippage,
                    slippageModel, commission, stampTaxRate, minCommission, transferFeeRate,
                    limitFilter, suspendFilter, sellSignalEnabled);
            cash = signalCashRef[0];
            totalTrades = signalTradeRef[0];

            // 判断是否调仓
            boolean shouldRebalance = shouldRebalance(today, lastRebalanceDate, freq);

            if (shouldRebalance && !bars.isEmpty()) {
                // 获取因子值
                Map<String, Map<String, FactorValue>> factorValueMap = new HashMap<>();
                for (FactorWeight fw : factorWeights) {
                    List<FactorValue> fvList = (clickHouseFactorValueService != null)
                            ? clickHouseFactorValueService.findByFactorCodeAndDate(fw.factorCode(), today)
                            : factorValueMapper.selectList(
                            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FactorValue>()
                                    .eq(FactorValue::getFactorCode, fw.factorCode())
                                    .eq(FactorValue::getCalcDate, today));
                    Map<String, FactorValue> fvMap = fvList.stream()
                            .collect(Collectors.toMap(FactorValue::getSymbol, fv -> fv));
                    factorValueMap.put(fw.factorCode(), fvMap);
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
                    if (suspendFilter && BacktestUtils.isSuspended(bar)) continue;
                    if (limitFilter && BacktestUtils.isLimitDown(bar)) continue;
                    double amount = oldPositions.get(sym) * bar.getClose().doubleValue();
                    sellFee += BacktestUtils.calcFee(amount, true, commission, stampTaxRate, minCommission, sym, transferFeeRate);
                }

                // 3. 新买入费用 + 原始买入总额
                double newBuyFee = 0;
                double rawInvestedValue = 0;
                for (Map.Entry<String, Double> entry : targetWeights.entrySet()) {
                    if (oldPositions.containsKey(entry.getKey())) continue;
                    MarketDailyBar bar = barMap.get(entry.getKey());
                    if (bar == null) continue;
                    if (suspendFilter && BacktestUtils.isSuspended(bar)) continue;
                    if (limitFilter && BacktestUtils.isLimitUp(bar)) continue;
                    double amount = portfolioValue * entry.getValue();
                    rawInvestedValue += amount;
                    newBuyFee += BacktestUtils.calcFee(amount, false, commission, stampTaxRate, minCommission, entry.getKey(), transferFeeRate);
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
                    else if (suspendFilter && BacktestUtils.isSuspended(bar)) effectiveTargets.remove(sym);
                    else if (limitFilter && BacktestUtils.isLimitUp(bar)) effectiveTargets.remove(sym);
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
                    if (suspendFilter && BacktestUtils.isSuspended(bar)) continue; // 停牌不卖
                    if (limitFilter && BacktestUtils.isLimitDown(bar)) continue; // 跌停不卖
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
            ep.put("value", BacktestUtils.round(portfolioValue / initialCapital, 6));
            ep.put("drawdown", BacktestUtils.round(-drawdown, 6));
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
                bm.put("value", BacktestUtils.round(bmClose / benchmarkBase, 6));
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
                        BacktestUtils.round(portfolioValue / initialCapital, 6), bmVal);
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
            m.put("return", BacktestUtils.round(monthRet, 6));
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
        String slippageModel = task.getSlippageModel() != null ? task.getSlippageModel() : "VOLUME";
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
                    if (suspendFilter && BacktestUtils.isSuspended(bar)) continue;
                    if (limitFilter && BacktestUtils.isLimitDown(bar)) continue;
                    double shares = positions.get(symbol);
                    double execPrice = BacktestUtils.getExecutionPrice(bar, tradingDates, di, orderType, nextDayBarMap);
                    double amount = shares * bar.getClose().doubleValue();
                    double dayAmount = bar.getAmount() != null ? bar.getAmount().doubleValue() * 1000 : 0;
                    double price = BacktestUtils.applySlippage(execPrice, false, slippage, amount, dayAmount, slippageModel);
                    double fee = BacktestUtils.calcFee(amount, true, commission, stampTaxRate, minCommission, symbol, transferFeeRate);
                    Map<String, Object> trade = new HashMap<>();
                    trade.put("date", today.toString());
                    trade.put("symbol", symbol);
                    trade.put("name", bar.getName());
                    trade.put("action", "STOP_LOSS_SELL");
                    trade.put("price", BacktestUtils.round(price, 4));
                    trade.put("amount", BacktestUtils.round(shares, 2));
                    trade.put("total", BacktestUtils.round(amount - fee, 2));
                    trade.put("commission", BacktestUtils.round(fee, 2));
                    trade.put("fee", BacktestUtils.round(fee, 2));
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
                    if (suspendFilter && BacktestUtils.isSuspended(bar)) continue;
                    if (limitFilter && BacktestUtils.isLimitUp(bar)) continue;
                    double amount = portfolioValue * entry.getValue();
                    rawInvestedValue += amount;
                    newBuyFee += BacktestUtils.calcFee(amount, false, commission, stampTaxRate, minCommission, entry.getKey(), transferFeeRate);
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
                    if (suspendFilter && BacktestUtils.isSuspended(bar)) continue;
                    if (limitFilter && BacktestUtils.isLimitDown(bar)) continue;
                    double amount = oldPositions.get(sym) * bar.getClose().doubleValue();
                    sellFee += BacktestUtils.calcFee(amount, true, commission, stampTaxRate, minCommission, sym, transferFeeRate);
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
                    if (suspendFilter && BacktestUtils.isSuspended(bar)) continue;
                    if (limitFilter && BacktestUtils.isLimitDown(bar)) continue;
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
            ep.put("value", BacktestUtils.round(portfolioValue / initialCapital, 6));
            ep.put("drawdown", BacktestUtils.round(-drawdown, 6));
            equityCurve.add(ep);

            Double bmClose = benchmarkClose.get(today);
            if (bmClose == null) bmClose = lastValidBmClose;
            else lastValidBmClose = bmClose;
            if (benchmarkBase > 0) {
                Map<String, Object> bm = new HashMap<>();
                bm.put("date", today.toString());
                bm.put("value", BacktestUtils.round(bmClose / benchmarkBase, 6));
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
                        BacktestUtils.round(portfolioValue / initialCapital, 6), bmVal);
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
            m.put("return", BacktestUtils.round(monthRet, 6));
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
     * <p>实现已迁移至 {@link BacktestReportBuilder#writeEquityCurveToDB}。</p>
     */
    private void writeEquityCurveToDB(Long taskId, List<Map<String, Object>> equityCurve, double initialCapital) {
        backtestReportBuilder.writeEquityCurveToDB(taskId, equityCurve, initialCapital);
    }

    /**
     * 计算综合因子得分（rank_value 优先，缺失回退截面 z-score；CUSTOM 策略走 Groovy 脚本）。
     * <p>实现已迁移至 {@link BacktestScoring#computeScores}。</p>
     */
    private Map<String, Double> computeScores(List<MarketDailyBar> bars,
                                              List<FactorWeight> factorWeights,
                                              Map<String, Map<String, FactorValue>> factorValueMap,
                                              BacktestTask task,
                                              StrategyDefinition strategy,
                                              LocalDate rebalanceDate,
                                              Map<String, Double> dynamicFactorWeights) {
        return backtestScoring.computeScores(bars, factorWeights, factorValueMap, task, strategy,
                rebalanceDate, dynamicFactorWeights);
    }

    /**
     * 选取 Top N 股票，等权。
     * <p>实现已迁移至 {@link BacktestScoring#selectTopStocks}。</p>
     */
    private Map<String, Double> selectTopStocks(Map<String, Double> scores, int topN) {
        return backtestScoring.selectTopStocks(scores, topN);
    }

    /**
     * 生成调仓成交明细（买卖双边，含停牌/涨跌停过滤、容量约束、滑点与费用）。
     * <p>实现已迁移至 {@link BacktestRebalancer#rebalance}。</p>
     */
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
        return backtestRebalancer.rebalance(oldPositions, targetWeights, barMap, portfolioValue,
                commission, slippage, date, positionValues, slippageModel, stampTaxRate,
                minCommission, limitFilter, suspendFilter, transferFeeRate, orderType,
                tradingDates, di, nextDayBarMap, positionCosts, buyScale);
    }

    /**
     * 重新计算现金。
     * <p>实现已迁移至 {@link BacktestRebalancer#recalcCash}。</p>
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
        return backtestRebalancer.recalcCash(oldPositions, targetWeights, barMap, portfolioValue,
                commission, slippage, slippageModel, stampTaxRate, minCommission,
                limitFilter, suspendFilter, transferFeeRate, buyScale);
    }

    /**
     * 再平衡触发判断：支持日历频率+偏离阈值+波动率自适应+混合。
     * <p>实现已迁移至 {@link BacktestRebalancer#shouldRebalance}。</p>
     */
    private boolean shouldRebalance(LocalDate today, LocalDate lastDate, String freq,
                                    double currentDeviation, double volatilityLevel,
                                    double threshold) {
        return backtestRebalancer.shouldRebalance(today, lastDate, freq, currentDeviation,
                volatilityLevel, threshold);
    }

    /**
     * 简化版：仅日历频率触发。
     * <p>实现已迁移至 {@link BacktestRebalancer#shouldRebalance}。</p>
     */
    private boolean shouldRebalance(LocalDate today, LocalDate lastDate, String freq) {
        return backtestRebalancer.shouldRebalance(today, lastDate, freq);
    }

    /**
     * 构建绩效报告
     */
    private BacktestReport buildReport(BacktestTask task, BacktestResult result) throws Exception {
        return backtestReportBuilder.buildReport(task, result);
    }

    private void sendProgress(Long taskId, String stage, int pct, String message) {
        progressNotifier.sendProgress(taskId, stage, pct, message);
    }

    /**
     * 回测进行中：携带当天净值数据点（用于前端实时绘图）
     * 消息格式：{ taskId, stage:"RUNNING", progress, date, stratValue, bmValue }
     * <p>实现已迁移至 {@link BacktestProgressNotifier#sendProgressWithCurve}。</p>
     */
    private void sendProgressWithCurve(Long taskId, int pct, String date,
                                       double stratValue, double bmValue) {
        progressNotifier.sendProgressWithCurve(taskId, pct, date, stratValue, bmValue);
    }

    /**
     * 解析策略的因子配置 JSON。
     * <p>实现已迁移至 {@link BacktestScoring#parseFactorConfig}。</p>
     */
    private List<FactorWeight> parseFactorConfig(String json) {
        return backtestScoring.parseFactorConfig(json);
    }

    /**
     * 基于近期IC/IR计算动态因子权重（与StockScreenService.getDynamicWeights逻辑对齐）。
     * <p>实现已迁移至 {@link BacktestScoring#computeDynamicFactorWeights}。</p>
     */
    private Map<String, Double> computeDynamicFactorWeights(List<FactorWeight> factorWeights,
                                                            String weightMode,
                                                            LocalDate rebalanceDate) {
        return backtestScoring.computeDynamicFactorWeights(factorWeights, weightMode, rebalanceDate);
    }

    /**
     * 加载所有股票的退市日期映射（幸存者偏差修复）。
     * 从 stock_info 表查询 delist_date 字段，构建 symbol -> delistDate 的映射。
     * 如果 stock_info 中无退市日期数据，则返回空 map（不影响现有逻辑）。
     */
    private Map<String, LocalDate> loadDelistDateMap() {
        return backtestDataLoader.loadDelistDateMap();
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
