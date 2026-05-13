package com.quant.platform.strategy.paper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.factor.service.FactorService;
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
    private final JdbcTemplate jdbcTemplate;

    @Autowired(required = false)
    @Qualifier("clickHouseJdbcTemplate")
    private JdbcTemplate clickHouseJdbcTemplate;

    @Autowired(required = false)
    private FactorService factorService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 创建模拟盘
     */
    @Transactional
    public PaperTrading createPaperTrading(Long strategyId, String strategyCode, BigDecimal initialCapital) {
        PaperTrading pt = PaperTrading.builder()
            .strategyId(strategyId)
            .strategyCode(strategyCode)
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

        return pt;
    }

    /**
     * 获取所有模拟盘列表
     */
    public List<PaperTrading> listAll() {
        return paperTradingMapper.selectList(
            new LambdaQueryWrapper<PaperTrading>().orderByDesc(PaperTrading::getCreatedAt));
    }

    /**
     * 获取模拟盘详情
     */
    public Map<String, Object> getDetail(Long paperId) {
        PaperTrading pt = paperTradingMapper.selectById(paperId);
        if (pt == null) throw new IllegalArgumentException("模拟盘不存在");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("paper", pt);

        // 持仓
        List<PaperPosition> positions = paperPositionMapper.selectList(
            new LambdaQueryWrapper<PaperPosition>().eq(PaperPosition::getPaperId, paperId));
        result.put("positions", positions);

        // 最新净值
        List<PaperNav> navs = paperNavMapper.selectList(
            new LambdaQueryWrapper<PaperNav>()
                .eq(PaperNav::getPaperId, paperId)
                .orderByDesc(PaperNav::getNavDate)
                .last("LIMIT 30"));
        Collections.reverse(navs);
        result.put("navHistory", navs);

        return result;
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

        // 生成前先删除该模拟盘所有 PENDING 信号，避免重复生成
        int cleared = paperSignalMapper.delete(
                new LambdaQueryWrapper<PaperSignal>()
                        .eq(PaperSignal::getPaperId, paperId)
                        .eq(PaperSignal::getStatus, "PENDING"));
        if (cleared > 0) {
            log.info("generateSignals: 清除旧 PENDING 信号 {} 条", cleared);
        }

        // 获取策略因子配置
        String factorConfigJson = getStrategyFactorConfig(pt.getStrategyId());
        if (factorConfigJson == null || factorConfigJson.isEmpty()) {
            throw new IllegalArgumentException("策略因子配置为空");
        }

        // 兼容两种格式: {"factors":[...]} 或直接 [...]
        List<Map<String, Object>> factorConfigs;
        try {
            Object raw = objectMapper.readValue(factorConfigJson, Object.class);
            if (raw instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> list = (List<Map<String, Object>>) raw;
                factorConfigs = list;
            } else if (raw instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) raw;
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> factors = (List<Map<String, Object>>) map.get("factors");
                factorConfigs = factors != null ? factors : List.of();
            } else {
                factorConfigs = List.of();
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("因子配置解析失败: " + e.getMessage());
        }

        // 收集策略中实际使用的因子code
        Set<String> usedFactorCodes = new LinkedHashSet<>();
        for (Map<String, Object> fc : factorConfigs) {
            String code = (String) fc.getOrDefault("code", fc.get("factorCode"));
            if (code != null && !code.isBlank()) usedFactorCodes.add(code);
        }

        // 获取信号日期：用日频因子的最新日期（排除 FIN_/CHAN_）
        // 这样信号日期最接近当前交易日
        String signalDate = getLatestTradeDate(usedFactorCodes);
        log.info("generateSignals: paperId={}, signalDate={}, factorConfigs={}", paperId, signalDate, factorConfigs.size());

        // 改造：每个因子用自己的最新日期，分别归一化后加权合并
        // 不再统一用 latestDate，避免财务因子拖拽整体日期
        Map<String, Double> stockScores = new HashMap<>();
        Map<String, Double> stockWeights = new HashMap<>();

        for (Map<String, Object> fc : factorConfigs) {
            // 兼容 "code" (前端/DB存储) 和 "factorCode" 两种字段名
            String factorCode = (String) fc.getOrDefault("code", fc.get("factorCode"));
            double weight = fc.get("weight") != null ? ((Number) fc.get("weight")).doubleValue() : 1.0;
            String direction = fc.get("direction") != null ? (String) fc.get("direction") : "ASC";

            if (factorCode == null || factorCode.isBlank()) {
                log.warn("generateSignals: 因子配置缺少code字段, 跳过: {}", fc);
                continue;
            }

            // 从 CH 获取因子值：每个因子用自己的最新日期
            if (clickHouseJdbcTemplate != null) {
                try {
                    // 查该因子自己的最新日期
                    String factorDate = getFactorLatestDate(factorCode);
                    if (factorDate == null) {
                        log.warn("generateSignals: 因子 {} 无数据，跳过", factorCode);
                        continue;
                    }
                    String sql = String.format("""
                        SELECT symbol, rank_value FROM stock.factor_value FINAL
                        WHERE factor_code = '%s' AND calc_date = '%s'
                          AND rank_value IS NOT NULL
                        """, factorCode, factorDate);
                    clickHouseJdbcTemplate.query(sql, (rs) -> {
                        String sym = rs.getString("symbol");
                        if (sym != null && sym.contains(".")) sym = sym.split("\\.")[0];
                        double rankVal = rs.getBigDecimal("rank_value").doubleValue();
                        // ASC 方向 rank 越大越好，DESC 反转
                        double adjustedRank = "DESC".equals(direction) ? (1.0 - rankVal) : rankVal;
                        stockScores.merge(sym, adjustedRank * weight, Double::sum);
                        stockWeights.merge(sym, weight, Double::sum);
                    });
                    log.info("generateSignals: factor={}, date={}, rows={}", factorCode, factorDate, stockScores.size());
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

        // 得分低于 0.3 的持仓 → 卖出信号
        List<PaperSignal> signals = new ArrayList<>();
        for (PaperPosition pos : currentPositions) {
            Double score = finalScores.get(pos.getCode());
            if (score == null || score < 0.3) {
                PaperSignal sellSignal = PaperSignal.builder()
                    .paperId(paperId)
                    .signalDate(LocalDate.parse(signalDate))
                    .code(pos.getCode())
                    .name(pos.getName())
                    .direction("SELL")
                    .signalPrice(pos.getCurrentPrice())
                    .factorScore(score != null ? BigDecimal.valueOf(score).setScale(4, RoundingMode.HALF_UP) : null)
                    .reason(score == null ? "无因子得分" : String.format("得分%.2f低于阈值", score))
                    .status("PENDING")
                    .build();
                paperSignalMapper.insert(sellSignal);
                signals.add(sellSignal);
            }
        }

        // 新股买入信号（不在持仓中、非当天已SKIPPED的高分股）
        // buySlots 只看当前持仓，不预支 SELL 释放的仓位（SELL 执行后再生成新 BUY 信号）
        int buySlots = 10 - heldCodes.size();
        if (buySlots <= 0) {
            log.info("generateSignals: 持仓已满({}只)，跳过买入信号生成", heldCodes.size());
        }
        for (Map.Entry<String, Double> e : sorted) {
            if (buySlots <= 0) break;
            if (heldCodes.contains(e.getKey())) continue;
            if (skippedCodes.contains(e.getKey())) continue;

            BigDecimal price = getLatestPrice(e.getKey(), signalDate);
            PaperSignal buySignal = PaperSignal.builder()
                .paperId(paperId)
                .signalDate(LocalDate.parse(signalDate))
                .code(e.getKey())
                .name(getStockName(e.getKey()))
                .direction("BUY")
                .signalPrice(price)
                .factorScore(BigDecimal.valueOf(e.getValue()).setScale(4, RoundingMode.HALF_UP))
                .reason(String.format("因子得分%.2f，排名靠前", e.getValue()))
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

        PaperTrading pt = paperTradingMapper.selectById(signal.getPaperId());

        if ("BUY".equals(signal.getDirection())) {
            // 等权买入
            BigDecimal price = signal.getSignalPrice();
            if (price == null || price.doubleValue() <= 0) {
                signal.setStatus("SKIPPED");
                signal.setReason("价格无效");
                paperSignalMapper.updateById(signal);
                return null;
            }

            // 固定金额分配：初始资金/目标持仓数（10只），不受持仓数变化影响
            BigDecimal perStock = pt.getInitialCapital()
                .divide(BigDecimal.valueOf(10), 2, RoundingMode.HALF_UP);
            int shares = perStock.divide(price, 0, RoundingMode.DOWN).divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN).intValue() * 100;
            if (shares <= 0) shares = 100; // 最少1手

            BigDecimal cost = price.multiply(BigDecimal.valueOf(shares));
            if (cost.compareTo(pt.getCurrentCapital()) > 0) {
                signal.setStatus("SKIPPED");
                signal.setReason("资金不足");
                paperSignalMapper.updateById(signal);
                return null;
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

            // 更新信号
            signal.setStatus("EXECUTED");
            signal.setExecutedPrice(price);
            signal.setExecutedAt(LocalDateTime.now());
            paperSignalMapper.updateById(signal);

            updateTotalAssets(pt);
            appendNavRecord(pt.getId());
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

            BigDecimal sellAmount = pos.getCurrentPrice().multiply(BigDecimal.valueOf(pos.getShares()));
            pt.setCurrentCapital(pt.getCurrentCapital().add(sellAmount));
            pt.setPositionCount(Math.max(0, pt.getPositionCount() - 1));
            paperTradingMapper.updateById(pt);

            paperPositionMapper.deleteById(pos.getId());

            signal.setStatus("EXECUTED");
            signal.setExecutedPrice(pos.getCurrentPrice());
            signal.setExecutedAt(LocalDateTime.now());
            paperSignalMapper.updateById(signal);

            updateTotalAssets(pt);
            appendNavRecord(pt.getId());
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
        appendNavRecord(pt.getId());
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
     * 追加/更新当日 NAV 记录（每次交易后自动调用）
     * 同一天多次交易时，更新当日已存在的记录，不重复插入
     */
    private void appendNavRecord(Long paperId) {
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
                        String.format(
                            "SELECT MAX(calc_date) FROM stock.factor_value FINAL WHERE factor_code = '%s'",
                            code),
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

        // 回退：全局 MAX 排除缠论和财务因子
        List<String> dates = clickHouseJdbcTemplate.query(
            "SELECT MAX(calc_date) FROM stock.factor_value FINAL " +
            "WHERE factor_code NOT LIKE 'CHAN_%' AND factor_code NOT LIKE 'FIN_%'",
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
                String.format("SELECT MAX(calc_date) FROM stock.factor_value FINAL WHERE factor_code = '%s'", factorCode),
                (rs, rowNum) -> rs.getString(1));
            return dates.isEmpty() || dates.getFirst() == null ? null : dates.getFirst();
        } catch (Exception e) {
            log.debug("查询因子 {} 最新日期失败: {}", factorCode, e.getMessage());
            return null;
        }
    }

    private BigDecimal getLatestPrice(String code, String date) {
        if (clickHouseJdbcTemplate == null) return BigDecimal.ZERO;
        try {
            String sql = String.format(
                "SELECT close_price FROM stock.stock_daily FINAL WHERE code = '%s' AND trade_date = '%s'",
                code, date);
            List<BigDecimal> prices = clickHouseJdbcTemplate.query(sql,
                (rs, rowNum) -> rs.getBigDecimal("close_price"));
            return prices.isEmpty() ? BigDecimal.ZERO : prices.getFirst();
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
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
}
