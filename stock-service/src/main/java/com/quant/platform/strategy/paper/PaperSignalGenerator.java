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
 * 模拟盘信号生成器
 * 依据策略因子配置打分选股，产出买卖信号（含卖出信号引擎联动）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaperSignalGenerator {

    private final PaperTradingMapper paperTradingMapper;
    private final PaperPositionMapper paperPositionMapper;
    private final PaperSignalMapper paperSignalMapper;
    private final PaperRiskConfigMapper paperRiskConfigMapper;
    private final com.quant.platform.factor.service.FactorMetaCacheService factorMetaCache;
    private final JdbcTemplate jdbcTemplate;
    private final PaperPriceService priceService;

    @Autowired(required = false)
    @Qualifier("clickHouseJdbcTemplate")
    private JdbcTemplate clickHouseJdbcTemplate;

    @Autowired(required = false)
    private MarketThermometerService marketThermometerService;

    @Autowired(required = false)
    private RecommendationMapper recommendationMapper;

    @Autowired(required = false)
    private SellSignalEngine sellSignalEngine;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 生成交易信号
     * 根据策略因子配置，计算最新截面得分，生成买入/卖出信号
     */
    @Transactional
    public List<PaperSignal> generateSignals(Long paperId) {
        PaperTrading pt = paperTradingMapper.selectById(paperId);
        if (pt == null) throw new IllegalArgumentException("模拟盘不存在");
        if (PaperTradingStatus.RUNNING != pt.getStatus()) throw new IllegalArgumentException("模拟盘未运行");

        // 交易日门控：若今天非交易日（周末/节假日），跳过信号生成
        if (!priceService.isTradingDay()) {
            log.info("generateSignals: 最近3日内无有效交易日数据（因子可能断档），跳过信号生成");
            return List.of();
        }

        // 生成前先删除该模拟盘所有 PENDING 信号，避免重复生成
        int cleared = paperSignalMapper.delete(
                new LambdaQueryWrapper<PaperSignal>()
                        .eq(PaperSignal::getPaperId, paperId)
                        .eq(PaperSignal::getStatus, PaperSignalStatus.PENDING));
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
            String d = priceService.getFactorLatestDate(fc);
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
                    String factorDate = priceService.getFactorLatestDate(factorCode);
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
                        .eq(PaperSignal::getStatus, PaperSignalStatus.SKIPPED)
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
                    double[][] ohlcv = priceService.fetchKlineForSellCheck(pos.getCode());
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
                    .status(PaperSignalStatus.PENDING)
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
                price = priceService.getOpenPrice(e.getKey(), null);
            }
            PaperSignal buySignal = PaperSignal.builder()
                .paperId(paperId)
                .signalDate(LocalDate.parse(signalDate))
                .factorDate(LocalDate.parse(signalDate))
                .code(e.getKey())
                .name(priceService.getStockName(e.getKey()))
                .direction("BUY")
                .signalPrice(price)
                .factorScore(BigDecimal.valueOf(e.getValue()).setScale(4, RoundingMode.HALF_UP))
                .reason(String.format("因子得分%.2f，排名靠前%s", e.getValue(), marketBearish ? "（大盘多头）" : ""))
                .status(PaperSignalStatus.PENDING)
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

    public String getStrategyFactorConfig(Long strategyId) {
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
    public Map<Long, Double> parseStrategyWeights(String strategyConfigJson) {
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
    public List<Map<String, Object>> parseFactorConfigs(String factorConfigJson) {
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
    public String getFactorDirection(String factorCode) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.query(
                "SELECT direction FROM factor_definition WHERE code = ? LIMIT 1",
                (rs, rowNum) -> Map.of("direction", rs.getString("direction")), factorCode);
            return rows.isEmpty() ? "ASC" : (String) rows.getFirst().get("direction");
        } catch (Exception e) {
            return "ASC";
        }
    }

}
