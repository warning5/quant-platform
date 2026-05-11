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

        // 获取最新交易日期
        String latestDate = getLatestTradeDate();

        // 对每个因子查最新截面 rank_value，加权求和
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

            // 从 CH 获取因子值
            if (clickHouseJdbcTemplate != null) {
                try {
                    String sql = String.format("""
                        SELECT symbol, rank_value FROM stock.factor_value FINAL                         WHERE factor_code = '%s' AND calc_date = '%s'
                          AND rank_value IS NOT NULL
                        """, factorCode, latestDate);
                    clickHouseJdbcTemplate.query(sql, (rs) -> {
                        String sym = rs.getString("symbol");
                        if (sym != null && sym.contains(".")) sym = sym.split("\\.")[0];
                        double rankVal = rs.getBigDecimal("rank_value").doubleValue();
                        // ASC 方向 rank 越大越好，DESC 反转
                        double adjustedRank = "DESC".equals(direction) ? (1.0 - rankVal) : rankVal;
                        stockScores.merge(sym, adjustedRank * weight, Double::sum);
                        stockWeights.merge(sym, weight, Double::sum);
                    });
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

        // 获取当前持仓
        List<PaperPosition> currentPositions = paperPositionMapper.selectList(
            new LambdaQueryWrapper<PaperPosition>().eq(PaperPosition::getPaperId, paperId));
        Set<String> heldCodes = currentPositions.stream()
            .map(PaperPosition::getCode).collect(Collectors.toSet());

        // 得分低于 0.3 的持仓 → 卖出信号
        List<PaperSignal> signals = new ArrayList<>();
        for (PaperPosition pos : currentPositions) {
            Double score = finalScores.get(pos.getCode());
            if (score == null || score < 0.3) {
                PaperSignal sellSignal = PaperSignal.builder()
                    .paperId(paperId)
                    .signalDate(LocalDate.parse(latestDate))
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

        // 新股买入信号（不在持仓中的高分股）
        int buySlots = 10 - heldCodes.size() + signals.size(); // 目标10只持仓
        for (Map.Entry<String, Double> e : sorted) {
            if (buySlots <= 0) break;
            if (heldCodes.contains(e.getKey())) continue;

            BigDecimal price = getLatestPrice(e.getKey(), latestDate);
            PaperSignal buySignal = PaperSignal.builder()
                .paperId(paperId)
                .signalDate(LocalDate.parse(latestDate))
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

        return signals;
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

            BigDecimal perStock = pt.getCurrentCapital()
                .divide(BigDecimal.valueOf(Math.max(1, pt.getPositionCount() + 1)), 2, RoundingMode.HALF_UP);
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
            return pos;
        }

        return null;
    }

    /**
     * 获取信号列表
     */
    public List<PaperSignal> getSignals(Long paperId) {
        return paperSignalMapper.selectList(
            new LambdaQueryWrapper<PaperSignal>()
                .eq(PaperSignal::getPaperId, paperId)
                .orderByDesc(PaperSignal::getSignalDate)
                .orderByDesc(PaperSignal::getId)
                .last("LIMIT 50"));
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

    private String getLatestTradeDate() {
        if (clickHouseJdbcTemplate == null) return LocalDate.now().toString();
        List<String> dates = clickHouseJdbcTemplate.query(
            "SELECT MAX(trade_date) FROM stock.stock_daily FINAL",
            (rs, rowNum) -> rs.getString(1));
        return dates.isEmpty() ? LocalDate.now().toString() : dates.getFirst();
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
