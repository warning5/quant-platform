package com.quant.platform.backtest.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.quant.platform.backtest.domain.EquityCurve;
import com.quant.platform.backtest.domain.RebalanceRecord;
import com.quant.platform.backtest.domain.RollingScreenTask;
import com.quant.platform.backtest.mapper.EquityCurveMapper;
import com.quant.platform.backtest.mapper.RebalanceRecordMapper;
import com.quant.platform.backtest.mapper.RollingScreenTaskMapper;
import com.quant.platform.market.domain.MarketDailyBar;
import com.quant.platform.market.service.MarketDataService;
import com.quant.platform.screen.dto.ScreenRequest;
import com.quant.platform.screen.dto.ScreenResult;
import com.quant.platform.screen.service.StockScreenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 滚动选股回测引擎
 * <p>
 * 核心逻辑：在每个调仓日调用 {@link StockScreenService#screen(ScreenRequest)} 获取选股结果，
 * 按权重分配资金执行交易，逐日记录净值曲线，最终输出完整的回测报告。
 * <p>
 * 与 {@link BacktestEngine} 的区别：
 * <ul>
 *   <li>选股驱动：用因子筛选替代 GroovyShell 策略脚本</li>
 *   <li>轻量级：不支持参数优化/蒙特卡洛等高级功能</li>
 *   <li>聚焦验证：回答"这套因子配置在历史上表现如何"</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RollingScreenEngine {

    private final RollingScreenTaskMapper taskMapper;
    private final RebalanceRecordMapper recordMapper;
    private final EquityCurveMapper equityCurveMapper;
    private final MarketDataService marketDataService;
    private final StockScreenService stockScreenService;

    @Autowired(required = false)
    private SimpMessagingTemplate messagingTemplate;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    // ================================================================
    //  公开入口：异步执行回测
    // ================================================================

    /**
     * 异步执行滚动选股回测。
     * 调用方应先将 RollingScreenRequest 存入 DB（status=PENDING），再传入 taskId。
     */
    @Async("backtestTaskExecutor")
    public void runRollingScreen(Long taskId) {
        // 等待 DB 事务提交（最多重试 10 次，每次 200ms）
        RollingScreenTask task = null;
        for (int i = 0; i < 10; i++) {
            task = taskMapper.selectById(taskId);
            if (task != null) break;
            try {
                Thread.sleep(200);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (task == null) {
            log.error("Rolling screen task [{}] not found after retries", taskId);
            return;
        }

        task.setStatus("RUNNING");
        task.setStartedAt(LocalDateTime.now());
        taskMapper.updateById(task);

        sendProgress(taskId, "RUNNING", 0, "初始化回测...");

        try {
            executeRollingScreen(task);

            task.setStatus("COMPLETED");
            task.setProgress(100);
            task.setCompletedAt(LocalDateTime.now());
            taskMapper.updateById(task);

            sendProgress(taskId, "COMPLETED", 100, "回测完成");
            log.info("Rolling screen task [{}] completed", taskId);

        } catch (Exception e) {
            log.error("Rolling screen task [{}] failed", taskId, e);
            task.setStatus("FAILED");
            task.setErrorMessage(e.getMessage());
            taskMapper.updateById(task);
            sendProgress(taskId, "FAILED", task.getProgress() != null ? task.getProgress() : 0,
                    "回测失败: " + e.getMessage());
        }
    }

    // ================================================================
    //  核心执行逻辑
    // ================================================================

    /**
     * 执行滚动选股回测的主循环。
     */
    private void executeRollingScreen(RollingScreenTask task) {
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
        String weightMode = task.getWeightMode() != null ? task.getWeightMode() : "EQUAL";
        boolean limitFilter = Boolean.TRUE.equals(task.getLimitFilter());
        boolean suspendFilter = Boolean.TRUE.equals(task.getSuspendFilter());

        // ── 1. 加载交易日历 ──────────────────────────────────────
        List<LocalDate> tradingDates = marketDataService.getTradingDates(startDate, endDate);
        if (tradingDates.isEmpty()) {
            throw new RuntimeException("无可用交易日数据: " + startDate + " ~ " + endDate);
        }

        // ── 2. 生成调仓日序列 ──────────────────────────────────
        Set<LocalDate> rebalanceDates = generateRebalanceDates(tradingDates, task.getRebalanceFreq());
        log.info("Rebalance dates: {} dates, freq={}, first={}, last={}",
                rebalanceDates.size(), task.getRebalanceFreq(),
                rebalanceDates.isEmpty() ? "-" : rebalanceDates.iterator().next(),
                rebalanceDates.isEmpty() ? "-" : new ArrayList<>(rebalanceDates).getLast());

        // ── 3. 解析选股配置 ────────────────────────────────────
        ScreenRequest baseScreenReq;
        try {
            baseScreenReq = MAPPER.readValue(task.getScreenConfigJson(), ScreenRequest.class);
            log.info("[RollingScreen] Parsed baseScreenReq: screenDate={}, topN={}, direction={}, factors={}, excludeSt={}",
                    baseScreenReq.getScreenDate(), baseScreenReq.getTopN(), baseScreenReq.getDirection(),
                    baseScreenReq.getFactors() != null ? baseScreenReq.getFactors().size() : "NULL",
                    baseScreenReq.getExcludeSt());
            if (baseScreenReq.getFactors() != null) {
                for (ScreenRequest.FactorWeight fw : baseScreenReq.getFactors()) {
                    log.info("[RollingScreen]   factor: code={}, direction={}, weight={}, filterOp={}, filterValue={}",
                            fw.getFactorCode(), fw.getDirection(), fw.getWeight(), fw.getFilterOp(), fw.getFilterValue());
                }
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException("解析 screenConfigJson 失败: " + e.getMessage(), e);
        }

        // 校验选股配置完整性
        if (baseScreenReq.getFactors() == null || baseScreenReq.getFactors().isEmpty()) {
            throw new RuntimeException("选股配置无效：未指定任何因子（factors 为空）。请先在因子选股页面配置筛选条件，再启动回测。");
        }

        // ── 4. 加载基准行情 ────────────────────────────────────
        String benchmarkCode = task.getBenchmarkCode() != null ? task.getBenchmarkCode() : "000300.SH";
        List<MarketDailyBar> benchmarkBars = new ArrayList<>();
        try {
            benchmarkBars = marketDataService.getBarsInRange(benchmarkCode, startDate, endDate);
        } catch (Exception e) {
            log.warn("加载基准 {} 行情失败: {}", benchmarkCode, e.getMessage());
        }

        Map<LocalDate, Double> benchmarkClose = new LinkedHashMap<>();
        for (MarketDailyBar b : benchmarkBars) {
            benchmarkClose.put(b.getTradeDate(), b.getClose().doubleValue());
        }
        double benchmarkBase = findBenchmarkBase(benchmarkClose, tradingDates.getFirst());
        double lastValidBmClose = benchmarkBase;

        // ── 5. 回测状态初始化 ──────────────────────────────────
        double cash = initialCapital;
        Map<String, Double> positions = new HashMap<>();      // symbol → 股数
        Map<String, Double> positionCosts = new HashMap<>();    // symbol → 累计成本价
        double portfolioValue = initialCapital;

        // 净值曲线
        List<Map<String, Object>> equityCurve = new ArrayList<>();
        List<Map<String, Object>> benchmarkCurve = new ArrayList<>();
        List<Map<String, Object>> tradeLog = new ArrayList<>();
        Map<String, Map<String, Double>> monthlyReturns = new TreeMap<>();

        // 回撤跟踪
        double peakValue = initialCapital;
        double maxDrawdown = 0;
        int totalTrades = 0;
        int winCount = 0;          // 盈利调仓次数（用于计算胜率）
        int totalRebalanceDays = 0; // 总调仓次数
        Double lastPortfolioValueAtRebalance = null; // 上次调仓时的组合市值
        List<Double> dailyReturnList = new ArrayList<>();

        // 次日行情预缓存（NEXT_OPEN 模式）
        Map<String, MarketDailyBar> nextDayBarMap = new HashMap<>();
        if (tradingDates.size() > 1) {
            List<MarketDailyBar> nextBars = marketDataService.getBarsAtDate(tradingDates.get(1));
            nextDayBarMap = nextBars.stream()
                    .collect(Collectors.toMap(MarketDailyBar::getSymbol, b -> b));
        }

        int total = tradingDates.size();
        sendProgress(task.getId(), "RUNNING", 1,
                "开始回测, 共 " + total + " 个交易日, " + rebalanceDates.size() + " 个调仓日");

        // ════════════════════════════════════════════════════════
        //  主循环：遍历每个交易日
        // ════════════════════════════════════════════════════════
        for (int di = 0; di < total; di++) {
            LocalDate today = tradingDates.get(di);

            // 更新次日行情缓存
            if (di + 1 < total) {
                LocalDate nextDay = tradingDates.get(di + 1);
                if (!nextDay.equals(today)) {  // 非同日
                    List<MarketDailyBar> nextBars = marketDataService.getBarsAtDate(nextDay);
                    nextDayBarMap = nextBars.stream()
                            .collect(Collectors.toMap(MarketDailyBar::getSymbol, b -> b));
                }
            }

            // 加载当日截面行情
            List<MarketDailyBar> bars = marketDataService.getBarsAtDate(today);
            Map<String, MarketDailyBar> barMap = bars.stream()
                    .collect(Collectors.toMap(MarketDailyBar::getSymbol, b -> b, (a, b2) -> a));

            // ── 计算持仓市值 ─────────────────────────────────
            double holdingValue = 0;
            for (Map.Entry<String, Double> pos : positions.entrySet()) {
                MarketDailyBar bar = barMap.get(pos.getKey());
                if (bar != null && bar.getClose() != null) {
                    holdingValue += pos.getValue() * bar.getClose().doubleValue();
                }
            }
            portfolioValue = cash + holdingValue;

            // 记录当日收益率
            double dailyRet = 0;
            if (di > 0 && !equityCurve.isEmpty()) {
                double prevValue = ((Number) equityCurve.getLast().get("totalValue")).doubleValue();
                if (prevValue > 0) dailyRet = (portfolioValue - prevValue) / prevValue;
            }
            dailyReturnList.add(dailyRet);

            // 回撤计算
            if (portfolioValue > peakValue) peakValue = portfolioValue;
            double dd = peakValue > 0 ? (peakValue - portfolioValue) / peakValue : 0;
            if (dd > maxDrawdown) maxDrawdown = dd;

            // 基准收益率
            double bmClose = benchmarkClose.getOrDefault(today, lastValidBmClose);
            if (benchmarkClose.containsKey(today)) lastValidBmClose = bmClose;
            double bmRet = benchmarkBase > 0 ? (bmClose - benchmarkBase) / benchmarkBase : 0;

            // ── 是否为调仓日？────────────────────────────────
            boolean isRebalance = rebalanceDates.contains(today);

            if (isRebalance) {
                log.info("[{}] === REBALANCE DAY (#{}/{}) ===", today, di + 1, total);
                int progressPct = Math.min(95, (int) ((double) di / total * 100));

                // 6a. 调用选股服务获取当期股票池
                ScreenRequest screenReq = buildScreenRequest(baseScreenReq, today);
                ScreenResult screenResult;
                try {
                    screenResult = stockScreenService.screen(screenReq);
                } catch (Exception e) {
                    log.warn("[{}] 选股失败，跳过本次调仓: {}", today, e.getMessage());
                    // 选股失败时保持原持仓不变，仅记录权益曲线
                    recordEquityAndBenchmark(equityCurve, benchmarkCurve, monthlyReturns,
                            today, portfolioValue, initialCapital, dailyRet, bmRet);
                    continue;
                }

                if (screenResult.getStocks() == null || screenResult.getStocks().isEmpty()) {
                    log.warn("[{}] 选股结果为空（{} 候选），跳过调仓",
                            today, screenResult.getCandidateCount());
                    recordEquityAndBenchmark(equityCurve, benchmarkCurve, monthlyReturns,
                            today, portfolioValue, initialCapital, dailyRet, bmRet);
                    continue;
                }

                log.info("[{}] Selected {} stocks (from {} candidates)",
                        today, screenResult.getStocks().size(), screenResult.getCandidateCount());

                // 6b. 计算目标权重
                List<TargetWeight> targets = computeTargets(screenResult, weightMode);

                // 6c. 获取成交价格
                Map<String, Double> priceMap = getTradePrices(targets, barMap, nextDayBarMap, orderType, today);

                // 6d. 过滤涨跌停 / 停牌
                filterUntradeable(targets, priceMap, barMap, limitFilter, suspendFilter, today);

                // 6e. 快照调仓前持仓
                String oldPositionsJson = snapshotPositions(positions, barMap);

                // 6f. 执行交易（先卖后买）
                List<Map<String, Object>> sells = new ArrayList<>();
                List<Map<String, Object>> buys = new ArrayList<>();

                // 卖出：不在目标中的持仓全部清仓
                Set<String> targetSymbols = targets.stream()
                        .map(TargetWeight::symbol).collect(Collectors.toSet());
                for (Iterator<Map.Entry<String, Double>> it = positions.entrySet().iterator(); it.hasNext(); ) {
                    Map.Entry<String, Double> entry = it.next();
                    String symbol = entry.getKey();
                    if (!targetSymbols.contains(symbol)) {
                        double shares = entry.getValue();
                        Double price = priceMap.get(symbol);
                        if (price == null || price <= 0) {
                            MarketDailyBar bar = barMap.get(symbol);
                            price = (bar != null && bar.getClose() != null) ? bar.getClose().doubleValue() : null;
                        }
                        if (price != null && price > 0 && shares > 0) {
                            double amount = price * shares;
                            double fee = BacktestUtils.calcFee(amount, true, commission, stampTaxRate,
                                    minCommission, symbol, transferFeeRate);
                            double execPrice = BacktestUtils.applySlippage(price, false, slippage,
                                    amount, 0, slippageModel);
                            double sellAmount = execPrice * shares;
                            double actualFee = BacktestUtils.calcFee(sellAmount, true, commission, stampTaxRate,
                                    minCommission, symbol, transferFeeRate);
                            cash += sellAmount - actualFee;

                            double cost = positionCosts.getOrDefault(symbol, 0.0);
                            double pnl = sellAmount - cost;

                            Map<String, Object> sellRecord = new LinkedHashMap<>();
                            sellRecord.put("date", today.toString());
                            sellRecord.put("symbol", symbol);
                            sellRecord.put("price", BacktestUtils.round(execPrice, 4));
                            sellRecord.put("shares", BacktestUtils.round(shares, 2));
                            sellRecord.put("amount", BacktestUtils.round(sellAmount, 2));
                            sellRecord.put("fee", BacktestUtils.round(actualFee, 2));
                            sellRecord.put("pnl", BacktestUtils.round(pnl, 2));
                            sells.add(sellRecord);
                            tradeLog.add(sellRecord);
                            totalTrades++;
                        }
                        it.remove();
                        positionCosts.remove(symbol);
                    }
                }

                // 再平衡：在目标中但持仓量需要调整的
                for (TargetWeight tw : targets) {
                    if (tw.weight() <= 0) continue;
                    double targetValue = portfolioValue * tw.weight();
                    Double currentPrice = priceMap.get(tw.symbol());
                    if (currentPrice == null || currentPrice <= 0) continue;

                    double currentShares = positions.getOrDefault(tw.symbol(), 0.0);
                    double currentValue = currentShares * currentPrice;
                    double diff = targetValue - currentValue;

                    // 需要卖出部分
                    if (diff < -1.0) {  // 差额超过 1 元才操作
                        double sellShares = Math.abs(diff) / currentPrice;
                        sellShares = Math.floor(sellShares / 100) * 100;  // 手数取整（向下到整百）
                        if (sellShares >= 100 && currentShares >= sellShares) {
                            double amount = sellShares * currentPrice;
                            double fee = BacktestUtils.calcFee(amount, true, commission, stampTaxRate,
                                    minCommission, tw.symbol(), transferFeeRate);
                            double execPrice = BacktestUtils.applySlippage(currentPrice, false, slippage,
                                    amount, 0, slippageModel);
                            double sellAmount = execPrice * sellShares;
                            double actualFee = BacktestUtils.calcFee(sellAmount, true, commission, stampTaxRate,
                                    minCommission, tw.symbol(), transferFeeRate);
                            cash += sellAmount - actualFee;
                            positions.put(tw.symbol(), currentShares - sellShares);

                            double cost = positionCosts.getOrDefault(tw.symbol(), 0.0);
                            // 按比例减少成本
                            double ratio = sellShares / currentShares;
                            double reducedCost = cost * ratio;
                            positionCosts.put(tw.symbol(), cost - reducedCost);

                            Map<String, Object> sellRecord = new LinkedHashMap<>();
                            sellRecord.put("date", today.toString());
                            sellRecord.put("symbol", tw.symbol());
                            sellRecord.put("price", BacktestUtils.round(execPrice, 4));
                            sellRecord.put("shares", BacktestUtils.round(sellShares, 2));
                            sellRecord.put("amount", BacktestUtils.round(sellAmount, 2));
                            sellRecord.put("fee", BacktestUtils.round(actualFee, 2));
                            sellRecord.put("action", "REDUCE");
                            sells.add(sellRecord);
                            tradeLog.add(sellRecord);
                            totalTrades++;
                        }
                    }
                    // 需要买入
                    else if (diff > 1.0) {
                        double buyAmount = diff;
                        double fee = BacktestUtils.calcFee(buyAmount, false, commission, stampTaxRate,
                                minCommission, tw.symbol(), transferFeeRate);
                        double available = buyAmount + cash;  // 可用资金含现金余额
                        double execPrice = BacktestUtils.applySlippage(currentPrice, true, slippage,
                                buyAmount, 0, slippageModel);
                        // 用现金限额决定买入量
                        double affordable = (cash - fee) / execPrice;
                        double buyShares = Math.floor(affordable / 100) * 100;  // 整手

                        if (buyShares >= 100) {
                            double buyCost = buyShares * execPrice;
                            double actualFee = BacktestUtils.calcFee(buyCost, false, commission, stampTaxRate,
                                    minCommission, tw.symbol(), transferFeeRate);
                            cash -= buyCost + actualFee;
                            double newShares = positions.getOrDefault(tw.symbol(), 0.0) + buyShares;
                            positions.put(tw.symbol(), newShares);

                            // 更新成本（加权平均）
                            double oldCost = positionCosts.getOrDefault(tw.symbol(), 0.0);
                            double oldShares = newShares - buyShares;
                            double newCost = oldCost + buyCost;
                            positionCosts.put(tw.symbol(), newCost);

                            Map<String, Object> buyRecord = new LinkedHashMap<>();
                            buyRecord.put("date", today.toString());
                            buyRecord.put("symbol", tw.symbol());
                            buyRecord.put("price", BacktestUtils.round(execPrice, 4));
                            buyRecord.put("shares", BacktestUtils.round(buyShares, 2));
                            buyRecord.put("amount", BacktestUtils.round(buyCost, 2));
                            buyRecord.put("fee", BacktestUtils.round(actualFee, 2));
                            buys.add(buyRecord);
                            tradeLog.add(buyRecord);
                            totalTrades++;
                        }
                    }
                }

                // 重新计算调仓后组合市值
                holdingValue = 0;
                for (Map.Entry<String, Double> pos : positions.entrySet()) {
                    MarketDailyBar bar = barMap.get(pos.getKey());
                    if (bar != null && bar.getClose() != null) {
                        holdingValue += pos.getValue() * bar.getClose().doubleValue();
                    }
                }
                portfolioValue = cash + holdingValue;

                // 6g. 保存 RebalanceRecord
                saveRebalanceRecord(task.getId(), today, oldPositionsJson, targets,
                        buys, sells, cash, portfolioValue, initialCapital, dailyRet);

                // 6h. 胜率统计：本次组合市值 > 上次调仓时市值 = 盈利
                totalRebalanceDays++;
                double lastRebalanceValue = initialCapital; // 首次调仓默认用初始资金
                if (lastPortfolioValueAtRebalance != null) {
                    lastRebalanceValue = lastPortfolioValueAtRebalance;
                }
                if (portfolioValue > lastRebalanceValue) {
                    winCount++;
                }
                lastPortfolioValueAtRebalance = portfolioValue;

                sendProgress(task.getId(), "RUNNING", progressPct,
                        String.format("调仓 %s: %d 只股票, 现金 %.0f, 总资产 %.0f",
                                today, targets.size(), cash, portfolioValue));
            }

            // ── 记录权益曲线和基准曲线 ─────────────────────────
            recordEquityAndBenchmark(equityCurve, benchmarkCurve, monthlyReturns,
                    today, portfolioValue, initialCapital, dailyRet, bmRet);
        }

        // ════════════════════════════════════════════════════════
        //  回测结束：汇总指标并更新任务记录
        // ════════════════════════════════════════════════════════

        // 最终净值
        double finalNav = initialCapital > 0 ? portfolioValue / initialCapital : 1.0;
        double totalReturn = (portfolioValue - initialCapital) / initialCapital;

        // 年化收益率
        double years = (double) tradingDates.size() / 245.0;
        double annualReturn = years > 0 ? Math.pow(1 + totalReturn, 1.0 / years) - 1 : 0;

        // 夏普比率（假设无风险利率 3%）
        double sharpe = computeSharpe(dailyReturnList, 0.03 / 245);

        // 月度收益
        Map<String, Double> summaryMonthlyReturns = summarizeMonthlyReturns(monthlyReturns);

        log.info("=== Rolling Screen Backtest Result ===");
        log.info("Final NAV: {}, Total Return: {:.2%}, Annual Return: {:.2%}", finalNav, totalReturn, annualReturn);
        log.info("Max Drawdown: {:.2%}, Sharpe: {:.2f}, Total Trades: {}", maxDrawdown, sharpe, totalTrades);
        log.info("Equity curve points: {}, Win rate: {}/{}", equityCurve.size(), winCount, totalRebalanceDays);

        // ── 批量写入净值曲线到 equity_curve 表 ────────────────────────
        if (!equityCurve.isEmpty()) {
            for (Map<String, Object> point : equityCurve) {
                try {
                    String dateStr = (String) point.get("date");
                    Number totalVal = (Number) point.get("totalValue");
                    Number dailyRetVal = (Number) point.get("dailyReturn");
                    if (dateStr == null || totalVal == null) continue;
                    double tv = totalVal.doubleValue();
                    EquityCurve ec = EquityCurve.builder()
                            .taskId(task.getId())
                            .tradeDate(LocalDate.parse(dateStr))
                            .portfolioValue(BigDecimal.valueOf(tv).setScale(4, RoundingMode.HALF_UP))
                            .nav(BigDecimal.valueOf(initialCapital > 0 ? tv / initialCapital : 1.0).setScale(6, RoundingMode.HALF_UP))
                            .returnPct(dailyRetVal != null ? BigDecimal.valueOf(dailyRetVal.doubleValue() * 100).setScale(6, RoundingMode.HALF_UP) : BigDecimal.ZERO)
                            .build();
                    equityCurveMapper.insertOne(ec);
                } catch (Exception e) {
                    log.warn("写入净值曲线失败: {}", e.getMessage());
                }
            }
            log.info("Equity curve saved: {} points", equityCurve.size());
        }

        // 更新任务摘要字段
        task.setFinalNav(BigDecimal.valueOf(finalNav).setScale(4, RoundingMode.HALF_UP));
        task.setTotalReturn(BigDecimal.valueOf(totalReturn).setScale(4, RoundingMode.HALF_UP));
        task.setAnnualReturn(BigDecimal.valueOf(annualReturn).setScale(4, RoundingMode.HALF_UP));
        task.setMaxDrawdown(BigDecimal.valueOf(maxDrawdown).setScale(4, RoundingMode.HALF_UP));
        task.setSharpeRatio(BigDecimal.valueOf(sharpe).setScale(4, RoundingMode.HALF_UP));
        task.setTotalTrades(totalTrades);
        // 胜率：盈利调仓次数 / 总有效调仓次数
        if (winCount > 0 && totalRebalanceDays > 0) {
            task.setWinRate(BigDecimal.valueOf((double) winCount / (double) totalRebalanceDays)
                    .setScale(4, RoundingMode.HALF_UP));
        }
        // 基准累计收益率
        if (!benchmarkCurve.isEmpty()) {
            Object lastBmRet = benchmarkCurve.get(benchmarkCurve.size() - 1).get("bmReturn");
            if (lastBmRet instanceof Number) {
                task.setBenchmarkReturn(BigDecimal.valueOf(((Number) lastBmRet).doubleValue())
                        .setScale(4, RoundingMode.HALF_UP));
            }
        }
        taskMapper.updateById(task);
    }

    // ================================================================
    //  内部工具方法
    // ================================================================

    /**
     * 根据频率从交易日列表中提取调仓日。
     * WEEKLY: 每周第一个交易日
     * BIWEEKLY: 每两周第一个交易日
     * MONTHLY: 每月第一个交易日
     */
    private Set<LocalDate> generateRebalanceDates(List<LocalDate> tradingDates, String freq) {
        Set<LocalDate> result = new LinkedHashSet<>();
        if (tradingDates.isEmpty()) return result;

        String frequency = (freq != null) ? freq.toUpperCase() : "MONTHLY";
        YearMonth currentYM = null;
        int weekCounter = 0;
        LocalDate lastRebalance = null;

        for (LocalDate d : tradingDates) {
            YearMonth ym = YearMonth.from(d);
            boolean isFirstOfMonth = !ym.equals(currentYM);
            boolean isNewWeek = lastRebalance == null ||
                    java.time.temporal.ChronoUnit.WEEKS.between(lastRebalance, d) >= 1;

            switch (frequency) {
                case "WEEKLY" -> {
                    if (isNewWeek) {
                        result.add(d);
                        lastRebalance = d;
                    }
                }
                case "BIWEEKLY" -> {
                    weekCounter++;
                    if (weekCounter % 2 == 1 && (lastRebalance == null ||
                            java.time.temporal.ChronoUnit.WEEKS.between(lastRebalance, d) >= 2)) {
                        result.add(d);
                        lastRebalance = d;
                        weekCounter = 0;
                    }
                }
                default -> {  // MONTHLY
                    if (isFirstOfMonth) {
                        result.add(d);
                        currentYM = ym;
                    }
                }
            }
        }

        return result;
    }

    /**
     * 为指定调仓日构建 ScreenRequest（复用基础配置，替换日期）。
     */
    private ScreenRequest buildScreenRequest(ScreenRequest base, LocalDate rebalanceDate) {
        // 使用 Jackson 深拷贝，避免修改原始配置
        try {
            String json = MAPPER.writeValueAsString(base);
            log.info("[buildScreenRequest] JSON length={}, preview={}", json.length(), json.length() > 200 ? json.substring(0, 200) + "..." : json);
            ScreenRequest req = MAPPER.readValue(json, ScreenRequest.class);
            req.setScreenDate(rebalanceDate);
            // 多日模式关闭（回测默认单日选股）
            req.setScreenStartDate(null);
            req.setScreenEndDate(null);
            log.info("[buildScreenRequest] result: screenDate={}, factors={}, topN={}, direction={}",
                    req.getScreenDate(), req.getFactors() != null ? req.getFactors().size() : "NULL",
                    req.getTopN(), req.getDirection());
            return req;
        } catch (JsonProcessingException e) {
            log.error("[buildScreenRequest] Jackson deep-copy FAILED, falling back to manual copy: {}", e.getMessage());
            // 降级：直接修改副本
            ScreenRequest req = new ScreenRequest();
            req.setScreenDate(rebalanceDate);
            req.setFactors(base.getFactors());
            req.setTopN(base.getTopN());
            req.setDirection(base.getDirection());
            req.setExcludeSt(base.getExcludeSt());
            return req;
        }
    }

    /**
     * 计算目标权重列表。
     * EQUAL: 每只股票均分
     * SCORE_PROPORTIONAL: 按 compositeScore 比例分配
     */
    private List<TargetWeight> computeTargets(ScreenResult result, String weightMode) {
        List<ScreenResult.StockScore> stocks = result.getStocks();
        if (stocks == null || stocks.isEmpty()) return List.of();

        if ("SCORE_PROPORTIONAL".equalsIgnoreCase(weightMode)) {
            double totalScore = stocks.stream()
                    .mapToDouble(ScreenResult.StockScore::getCompositeScore).sum();
            if (totalScore <= 0) totalScore = 1.0;
            final double denom = totalScore;

            return new java.util.ArrayList<>(stocks.stream()
                    .map(s -> new TargetWeight(
                            s.getSymbol(),
                            s.getName(),
                            s.getCompositeScore() / denom,
                            s.getCompositeScore()))
                    .toList());
        }

        // 默认等权
        double equalWeight = 1.0 / stocks.size();
        return new java.util.ArrayList<>(stocks.stream()
                .map(s -> new TargetWeight(s.getSymbol(), s.getName(), equalWeight, s.getCompositeScore()))
                .toList());
    }

    /**
     * 获取目标股票的成交价。
     * CLOSE: 当日收盘价
     * NEXT_OPEN: 次日开盘价（更真实，避免未来函数）
     * VWAP: 成交量加权均价
     */
    private Map<String, Double> getTradePrices(List<TargetWeight> targets,
                                                Map<String, MarketDailyBar> barMap,
                                                Map<String, MarketDailyBar> nextDayBarMap,
                                                String orderType, LocalDate today) {
        Map<String, Double> prices = new HashMap<>();

        for (TargetWeight tw : targets) {
            double price = 0;
            switch (orderType.toUpperCase()) {
                case "NEXT_OPEN" -> {
                    MarketDailyBar nextBar = nextDayBarMap.get(tw.symbol());
                    if (nextBar != null && nextBar.getOpen() != null) {
                        price = nextBar.getOpen().doubleValue();
                    } else {
                        // 次日无数据则回退到收盘价
                        MarketDailyBar bar = barMap.get(tw.symbol());
                        price = (bar != null && bar.getClose() != null) ? bar.getClose().doubleValue() : 0;
                    }
                }
                case "VWAP" -> {
                    MarketDailyBar bar = barMap.get(tw.symbol());
                    if (bar != null && bar.getAmount() != null && bar.getVol() != null
                            && bar.getVol().doubleValue() > 0) {
                        price = bar.getAmount().doubleValue() / bar.getVol().doubleValue();
                    } else if (bar != null && bar.getClose() != null) {
                        price = bar.getClose().doubleValue();
                    }
                }
                default -> {  // CLOSE
                    MarketDailyBar bar = barMap.get(tw.symbol());
                    price = (bar != null && bar.getClose() != null) ? bar.getClose().doubleValue() : 0;
                }
            }
            prices.put(tw.symbol(), price);
        }
        return prices;
    }

    /**
     * 过滤不可交易的股票（涨跌停 / 停牌）。
     */
    @SuppressWarnings("Java8SetForEach")
    private void filterUntradeable(List<TargetWeight> targets, Map<String, Double> priceMap,
                                   Map<String, MarketDailyBar> barMap,
                                   boolean limitFilter, boolean suspendFilter,
                                   LocalDate today) {
        Iterator<TargetWeight> it = targets.iterator();
        while (it.hasNext()) {
            TargetWeight tw = it.next();
            MarketDailyBar bar = barMap.get(tw.symbol());

            // 停牌过滤：无数据或成交量=0
            if (suspendFilter && (bar == null ||
                    (bar.getVol() != null && bar.getVol().doubleValue() <= 0))) {
                log.debug("{}: 停牌过滤移除 {}", today, tw.symbol());
                it.remove();
                continue;
            }

            // 涨跌停过滤
            if (limitFilter && bar != null && bar.getClose() != null
                    && bar.getPreClose() != null && bar.getPreClose().doubleValue() > 0) {
                double pctChg = (bar.getClose().doubleValue() - bar.getPreClose().doubleValue())
                        / bar.getPreClose().doubleValue();
                // 涨跌停约 ±10%（ST ±5%），20cm 板块 ±20%
                if (Math.abs(pctChg) >= 0.095) {
                    log.debug("{}: 涨跌停过滤移除 {} ({:.2%})", today, tw.symbol(), pctChg);
                    it.remove();
                }
            }
        }
    }

    /**
     * 持仓快照 JSON 序列化。
     */
    private String snapshotPositions(Map<String, Double> positions, Map<String, MarketDailyBar> barMap) {
        if (positions.isEmpty()) return "[]";
        List<Map<String, Object>> snapshot = new ArrayList<>();
        for (Map.Entry<String, Double> pos : positions.entrySet()) {
            if (pos.getValue() <= 0) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("symbol", pos.getKey());
            item.put("shares", BacktestUtils.round(pos.getValue(), 2));
            MarketDailyBar bar = barMap.get(pos.getKey());
            item.put("name", bar != null ? bar.getName() : pos.getKey());
            if (bar != null && bar.getClose() != null) {
                item.put("close", bar.getClose().doubleValue());
            }
            snapshot.add(item);
        }
        try {
            return MAPPER.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    /**
     * 目标持仓快照 JSON 序列化。
     */
    private String snapshotTargets(List<TargetWeight> targets) {
        if (targets.isEmpty()) return "[]";
        try {
            return MAPPER.writeValueAsString(
                    targets.stream().map(tw -> Map.of(
                            "symbol", tw.symbol(),
                            "name", tw.name(),
                            "weight", BacktestUtils.round(tw.weight(), 6),
                            "score", BacktestUtils.round(tw.score(), 6)
                    )).toList());
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    /**
     * 保存一条调仓记录到数据库。
     */
    private void saveRebalanceRecord(Long taskId, LocalDate date,
                                     String oldPositionsJson,
                                     List<TargetWeight> targets,
                                     List<Map<String, Object>> buys,
                                     List<Map<String, Object>> sells,
                                     double cash, double totalValue,
                                     double initialCapital, double dailyRet) {
        try {
            RebalanceRecord record = RebalanceRecord.builder()
                    .taskId(taskId)
                    .rebalanceDate(date)
                    .oldPositionsJson(oldPositionsJson)
                    .newPositionsJson(snapshotTargets(targets))
                    .buysJson(buys.isEmpty() ? "[]" : MAPPER.writeValueAsString(buys))
                    .sellsJson(sells.isEmpty() ? "[]" : MAPPER.writeValueAsString(sells))
                    .cash(BigDecimal.valueOf(cash).setScale(2, RoundingMode.HALF_UP))
                    .totalValue(BigDecimal.valueOf(totalValue).setScale(2, RoundingMode.HALF_UP))
                    .nav(BigDecimal.valueOf(initialCapital > 0 ? totalValue / initialCapital : 1.0)
                            .setScale(4, RoundingMode.HALF_UP))
                    .dailyReturn(BigDecimal.valueOf(dailyRet).setScale(6, RoundingMode.HALF_UP))
                    .build();
            recordMapper.insert(record);
        } catch (Exception e) {
            log.warn("保存调仓记录失败 date={}: {}", date, e.getMessage());
        }
    }

    /**
     * 记录权益曲线和基准曲线的数据点。
     */
    private void recordEquityAndBenchmark(List<Map<String, Object>> equityCurve,
                                          List<Map<String, Object>> benchmarkCurve,
                                          Map<String, Map<String, Double>> monthlyReturns,
                                          LocalDate today, double totalValue,
                                          double initialCapital, double dailyRet,
                                          double bmRet) {
        double nav = initialCapital > 0 ? totalValue / initialCapital : 1.0;

        Map<String, Object> eqPoint = new LinkedHashMap<>();
        eqPoint.put("date", today.toString());
        eqPoint.put("totalValue", BacktestUtils.round(totalValue, 2));
        eqPoint.put("nav", BacktestUtils.round(nav, 6));
        eqPoint.put("dailyReturn", BacktestUtils.round(dailyRet, 6));
        equityCurve.add(eqPoint);

        Map<String, Object> bmPoint = new LinkedHashMap<>();
        bmPoint.put("date", today.toString());
        bmPoint.put("bmReturn", BacktestUtils.round(bmRet, 6));
        benchmarkCurve.add(bmPoint);

        // 月度收益汇总
        String monthKey = today.getYear() + "-" + String.format("%02d", today.getMonthValue());
        monthlyReturns.computeIfAbsent(monthKey, k -> new HashMap<>())
                .put("return", monthlyReturns.get(monthKey).getOrDefault("return", 0.0) + dailyRet);
        monthlyReturns.get(monthKey).put("days",
                monthlyReturns.get(monthKey).getOrDefault("days", 0.0) + 1.0);
    }

    /**
     * 查找基准初始价格（回测起始日或之后的第一个有效价格）。
     */
    private double findBenchmarkBase(Map<LocalDate, Double> benchmarkClose, LocalDate startDate) {
        for (Map.Entry<LocalDate, Double> entry : benchmarkClose.entrySet()) {
            if (!entry.getKey().isBefore(startDate)) {
                return entry.getValue();
            }
        }
        // 如果没有起始日之后的数据，取最后一个
        if (!benchmarkClose.isEmpty()) {
            return new ArrayList<>(benchmarkClose.values()).getLast();
        }
        return 1.0;
    }

    /**
     * 计算 Sharpe Ratio（日度收益序列 → 年化）。
     */
    private double computeSharpe(List<Double> returns, double riskFreeDaily) {
        if (returns.size() < 10) return 0;

        double mean = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = returns.stream()
                .mapToDouble(r -> (r - mean) * (r - mean))
                .average().orElse(0);
        double std = Math.sqrt(variance);

        if (std < 1e-10) return 0;
        // 日度 Sharpe × √245 → 年化
        return (mean - riskFreeDaily) / std * Math.sqrt(245);
    }

    /**
     * 将月度收益 Map 简化为 月→月收益率 的映射。
     */
    private Map<String, Double> summarizeMonthlyReturns(Map<String, Map<String, Double>> monthlyReturns) {
        Map<String, Double> result = new TreeMap<>();
        for (Map.Entry<String, Map<String, Double>> entry : monthlyReturns.entrySet()) {
            double totalRet = entry.getValue().getOrDefault("return", 0.0);
            result.put(entry.getKey(), totalRet);
        }
        return result;
    }

    /**
     * 推送进度：WebSocket + 同步写入 DB（供轮询模式读取）。
     */
    private void sendProgress(Long taskId, String status, int progress, String message) {
        // 1. 写入 DB（轮询前端依赖此字段）
        try {
            RollingScreenTask update = new RollingScreenTask();
            update.setId(taskId);
            update.setProgress(progress);
            if (status != null) {
                update.setStatus(status);
            }
            taskMapper.updateById(update);
        } catch (Exception e) {
            log.debug("进度写入DB失败: {}", e.getMessage());
        }

        // 2. WebSocket 推送
        if (messagingTemplate != null) {
            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("type", "ROLLING_SCREEN_PROGRESS");
                payload.put("taskId", taskId);
                payload.put("status", status);
                payload.put("progress", progress);
                payload.put("message", message);
                messagingTemplate.convertAndSend("/topic/backtest/progress", payload);
            } catch (Exception e) {
                log.debug("WebSocket 推进推送失败（可能未连接）: {}", e.getMessage());
            }
        }
    }

    // ================================================================
    //  内部记录类型
    // ================================================================

    /**
     * 目标权重（调仓时的目标持仓项）。
     *
     * @param symbol   股票代码
     * @param name     股票名称
     * @param weight   目标权重（0~1，总和=1）
     * @param score    综合得分
     */
    record TargetWeight(String symbol, String name, double weight, double score) {}
}
