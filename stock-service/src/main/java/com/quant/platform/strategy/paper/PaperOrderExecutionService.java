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
 * 模拟盘订单执行服务
 * 信号撮合成交、仓位与现金变更、条件单触发、分红处理与止损自动卖出。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaperOrderExecutionService {

    private final PaperTradingMapper paperTradingMapper;
    private final PaperPositionMapper paperPositionMapper;
    private final PaperSignalMapper paperSignalMapper;
    private final PaperRiskConfigMapper paperRiskConfigMapper;
    private final PaperCashFlowMapper paperCashFlowMapper;
    private final JdbcTemplate jdbcTemplate;
    private final PaperPriceService priceService;
    private final PaperAccountService accountService;

    @Autowired(required = false)
    private PaperExecutionQualityMapper paperExecutionQualityMapper;

    @Autowired(required = false)
    @Qualifier("clickHouseJdbcTemplate")
    private JdbcTemplate clickHouseJdbcTemplate;

    @Autowired(required = false)
    private PositionAlertService positionAlertService;

    @Autowired(required = false)
    private RecommendationMapper recommendationMapper;

    /**
     * 执行信号
     */
    @Transactional
    public PaperPosition executeSignal(Long signalId) {
        PaperSignal signal = paperSignalMapper.selectById(signalId);
        if (signal == null) throw new IllegalArgumentException("信号不存在");
        if (PaperSignalStatus.PENDING != signal.getStatus()) throw new IllegalArgumentException("信号已处理");

        // 非交易日禁止手动执行，避免价格不匹配
        if (!canExecuteSignal()) {
            throw new IllegalStateException("非交易日，不允许执行信号，请于下一交易日开盘后再执行");
        }

        // 【缺陷1修复】条件单触发检查：限价单/止损单/追踪止损需满足触发条件才执行
        String orderType = signal.getOrderType() != null ? signal.getOrderType() : "MARKET";
        if (!"MARKET".equals(orderType)) {
            BigDecimal currentPrice = priceService.getExecutionPrice(signal.getCode(), signal.getPaperId());
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
            BigDecimal price = priceService.getExecutionPrice(signal.getCode(), signal.getPaperId());
            if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
                signal.setStatus(PaperSignalStatus.SKIPPED);
                signal.setReason("价格无效");
                paperSignalMapper.updateById(signal);
                return null;
            }
            // 买入加滑点
            price = priceService.applySlippage(price, true, signal.getPaperId());

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
                    signal.setStatus(PaperSignalStatus.BLOCKED);
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
                signal.setStatus(PaperSignalStatus.SKIPPED);
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
                    signal.setStatus(PaperSignalStatus.SKIPPED);
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
                .strategyId(signal.getStrategyId())
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
            signal.setStatus(PaperSignalStatus.EXECUTED);
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

            accountService.updateTotalAssets(pt);
            // 注意：不在此处调用 appendNavRecord，日收益需在收盘后统一按收盘价计算
            return pos;

        } else if ("SELL".equals(signal.getDirection())) {
            // 卖出持仓
            PaperPosition pos = paperPositionMapper.selectOne(
                new LambdaQueryWrapper<PaperPosition>()
                    .eq(PaperPosition::getPaperId, pt.getId())
                    .eq(PaperPosition::getCode, signal.getCode()));

            if (pos == null) {
                signal.setStatus(PaperSignalStatus.SKIPPED);
                signal.setReason("持仓不存在");
                paperSignalMapper.updateById(signal);
                return null;
            }

            BigDecimal price = priceService.getExecutionPrice(signal.getCode(), signal.getPaperId());
            // 卖出减滑点
            price = priceService.applySlippage(price, false, signal.getPaperId());
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

            signal.setStatus(PaperSignalStatus.EXECUTED);
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

            accountService.updateTotalAssets(pt);
            // 注意：不在此处调用 appendNavRecord，日收益需在收盘后统一按收盘价计算
            return pos;
        }

        return null;
    }

    /**
     * 批量执行所有待处理信号
     */
    @Transactional
    public List<PaperPosition> executeAllSignals(Long paperId) {
        PaperTrading pt = paperTradingMapper.selectById(paperId);
        if (pt == null) throw new IllegalArgumentException("模拟盘不存在");
        if (PaperTradingStatus.RUNNING != pt.getStatus()) throw new IllegalArgumentException("模拟盘未运行");

        List<PaperSignal> pendingSignals = paperSignalMapper.selectList(
            new LambdaQueryWrapper<PaperSignal>()
                .eq(PaperSignal::getPaperId, paperId)
                .eq(PaperSignal::getStatus, PaperSignalStatus.PENDING)
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
     * 判断是否可以执行信号（严格：必须是今天或未来）
     */
    public boolean canExecuteSignal() {
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
     * 保存执行质量记录
     */
    public void saveExecutionQuality(PaperSignal signal, BigDecimal executedPrice, int shares) {
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

    /** 计算凯利公式参数：从历史已执行信号中估算胜率/均盈/均亏
     *  使用 paper_signal 中的买入+卖出信号配对计算收益，避免 paper_position 卖出后删除导致数据丢失 */
    public KellyParams calcKellyParams(Long paperId) {
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
                BigDecimal buyPrice = priceService.getBuyPriceForCode(paperId, code, (String) sell.get("sellDate"));
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
        accountService.updateTotalAssets(pt);
        // 注意：不在此处调用 appendNavRecord，日收益需在收盘后统一按收盘价计算
    }

    /**
     * 盘中自动止损卖出（风控触发时调用）
     * 直接创建SELL信号并执行，不经过Scheduler。
     * 如果持仓不存在或模拟盘未运行，则静默返回。
     */
    @Transactional
    public void autoSellByStopLoss(Long paperId, String code, String reason) {
        PaperTrading pt = paperTradingMapper.selectById(paperId);
        if (pt == null || PaperTradingStatus.RUNNING != pt.getStatus()) {
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
        String name = priceService.getStockName(code);
        BigDecimal price = priceService.getExecutionPrice(code, paperId);
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("autoSellByStopLoss: {} 无法获取有效价格，跳过", code);
            return;
        }
        // 卖出减滑点
        price = priceService.applySlippage(price, false, paperId);

        PaperSignal signal = PaperSignal.builder()
            .paperId(paperId)
            .strategyId(pt.getStrategyId())
            .signalDate(LocalDate.now())
            .factorDate(LocalDate.now())
            .code(code)
            .name(name)
            .direction("SELL")
            .signalPrice(price)
            .reason(reason != null ? reason : "盘中止损触发")
            .status(PaperSignalStatus.PENDING)
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
     * 一键买入：创建 MARKET BUY 信号并立即执行
     * 优先使用 recommended.suggestedBuyPrice，回退到当前市场价
     */
    @Transactional
    public PaperPosition quickBuy(Long paperId, String code, String name, BigDecimal price) {
        PaperTrading pt = paperTradingMapper.selectById(paperId);
        if (pt == null) throw new IllegalArgumentException("模拟盘不存在");
        if (PaperTradingStatus.RUNNING != pt.getStatus()) throw new IllegalArgumentException("模拟盘未运行");

        if (name == null) name = priceService.getStockName(code);

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
            price = priceService.getExecutionPrice(code, paperId);
        }
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("无法获取有效买入价格: " + code);
        }

        // 创建 PENDING MARKET BUY 信号
        PaperSignal signal = PaperSignal.builder()
                .paperId(paperId)
                .strategyId(pt.getStrategyId())
                .signalDate(LocalDate.now())
                .factorDate(LocalDate.now())
                .code(code)
                .name(name)
                .direction("BUY")
                .orderType("MARKET")
                .signalPrice(price)
                .reason("一键买入（推荐页）")
                .status(PaperSignalStatus.PENDING)
                .build();
        paperSignalMapper.insert(signal);
        log.info("quickBuy: 信号已创建 paperId={} code={} price={}", paperId, code, price);

        // 立即执行
        PaperPosition position = executeSignal(signal.getId());
        log.info("quickBuy: 执行完成 paperId={} code={} positionId={}", paperId, code,
                position != null ? position.getId() : "null");
        return position;
    }

    /**
     * 【缺陷1修复】创建条件单（限价单/止损单/止损限价单/追踪止损单）
     */
    @Transactional
    public PaperSignal createConditionalOrder(Long paperId, String code, String direction,
            String orderType, BigDecimal triggerPrice, BigDecimal limitPrice,
            BigDecimal trailPct, BigDecimal trailAmount, BigDecimal signalPrice, String reason) {
        // 取组合盘策略ID，标识该条件单归属（因子融合模式下为组合盘自身策略ID）
        PaperTrading pt = paperTradingMapper.selectById(paperId);
        Long strategyId = pt != null ? pt.getStrategyId() : null;
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

        String name = priceService.getStockName(code);
        if (signalPrice == null) {
            signalPrice = priceService.getExecutionPrice(code, paperId);
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
            .strategyId(strategyId)
            .signalDate(LocalDate.now())
            .factorDate(LocalDate.now())
            .code(code)
            .name(name)
            .direction(direction)
            .signalPrice(signalPrice)
            .reason(reason)
            .status(PaperSignalStatus.PENDING)
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
     * 【缺陷1修复】条件单触发判断
     * @param orderType 订单类型
     * @param signal 信号（含triggerPrice/trailPct等）
     * @param currentPrice 当前价格
     * @return 是否触发执行
     */
    public boolean checkOrderTrigger(String orderType, PaperSignal signal, BigDecimal currentPrice) {
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
    public void updateTrailingHighestPrice(PaperSignal signal, BigDecimal currentPrice) {
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
                .eq(PaperSignal::getStatus, PaperSignalStatus.PENDING)
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

    /** 凯利公式参数 */
    private static class KellyParams {
        double winRate, avgWin, avgLoss;
        KellyParams(double wr, double aw, double al) { winRate = wr; avgWin = aw; avgLoss = al; }
    }

}
