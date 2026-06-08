package com.quant.platform.strategy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.factor.domain.FactorDefinition;
import com.quant.platform.factor.mapper.FactorDefinitionMapper;
import com.quant.platform.strategy.domain.StrategyDefinition;
import com.quant.platform.strategy.mapper.StrategyDefinitionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * 策略数据初始化器
 * 在应用启动时自动初始化演示策略
 * 策略因子配置基于 factor_definition 表中实际存在的因子动态构建，避免硬编码不存在因子
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyDataInitializer {

    private final StrategyDefinitionMapper strategyMapper;
    private final FactorDefinitionMapper factorMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 因子偏好：code -> (weight, direction) */
    private record FactorPref(String code, double weight, int direction) {}

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void init() {
        log.info("Checking strategy data initialization...");

        long count = strategyMapper.selectCount(null);
        if (count >= 5) {
            log.info("Strategy data already exists ({} strategies), skipping initialization", count);
            return;
        }

        log.info("Initializing strategy data...");

        // 查询实际可用的因子（避免硬编码不存在的因子）
        List<FactorDefinition> availableFactors = factorMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FactorDefinition>()
                        .eq(FactorDefinition::getStatus, FactorDefinition.FactorStatus.ACTIVE));
        Set<String> availableCodes = availableFactors.stream()
                .map(FactorDefinition::getFactorCode)
                .collect(java.util.HashSet::new, java.util.HashSet::add, java.util.HashSet::addAll);
        log.info("Available factors for strategy init: {}", availableCodes.size());

        // ── 1. 全因子综合 ──────────────────────────────────────────────
        createDynamicStrategy("ALL_FACTOR_COMPOSITE", "全因子综合",
                "覆盖动量、价值、波动率、技术、量价五大类因子，均衡配置追求稳健超额收益",
                StrategyDefinition.StrategyType.FACTOR_LONG, "MONTHLY", 20,
                List.of(
                        new FactorPref("MOM20", 0.25, 1),
                        new FactorPref("SIZE", 0.20, -1),
                        new FactorPref("VOL20", 0.20, -1),
                        new FactorPref("RSI14", 0.15, 1),
                        new FactorPref("VOLUME_RATIO", 0.20, 1)
                ), availableCodes, new BigDecimal("0.08"));

        // ── 2. 现有持仓增强 ────────────────────────────────────────────
        createDynamicStrategy("POSITION_ENHANCE", "现有持仓增强",
                "基于中期趋势确认+低估值保护+低波动控制，帮助已有持仓优化风险收益比",
                StrategyDefinition.StrategyType.FACTOR_LONG, "MONTHLY", 15,
                List.of(
                        new FactorPref("MOM60", 0.30, 1),
                        new FactorPref("VAL_PB", 0.25, -1),
                        new FactorPref("VOL20", 0.25, -1),
                        new FactorPref("MACD", 0.20, 1)
                ), availableCodes, new BigDecimal("0.08"));

        // ── 3. 均衡配置 ────────────────────────────────────────────────
        createDynamicStrategy("BALANCED_CONFIG", "均衡配置",
                "动量、估值、波动率、技术、量价五类因子等权配置，降低单一因子失效风险",
                StrategyDefinition.StrategyType.FACTOR_LONG, "MONTHLY", 20,
                List.of(
                        new FactorPref("MOM20", 0.20, 1),
                        new FactorPref("VAL_PE_TTM", 0.20, -1),
                        new FactorPref("VOL20", 0.20, -1),
                        new FactorPref("MACD", 0.20, 1),
                        new FactorPref("VPCORR20", 0.20, 1)
                ), availableCodes, new BigDecimal("0.08"));

        // ── 4. 新质生产力 ──────────────────────────────────────────────
        createDynamicStrategy("NEW_PRODUCTIVITY", "新质生产力",
                "聚焦成长加速+技术突破+量价配合，捕捉处于高速成长期和科技突破期的公司",
                StrategyDefinition.StrategyType.FACTOR_LONG, "MONTHLY", 15,
                List.of(
                        new FactorPref("MOM20", 0.25, 1),
                        new FactorPref("PRICE_MOM_ACC", 0.20, 1),
                        new FactorPref("MACD", 0.25, 1),
                        new FactorPref("BOLL_POS", 0.15, 1),
                        new FactorPref("VPCORR20", 0.15, 1)
                ), availableCodes, new BigDecimal("0.10"));

        // ── 5. 热点追踪 ────────────────────────────────────────────────
        createDynamicStrategy("HOT_TRACKING", "热点追踪",
                "基于短期动量+情绪热度+量价异动，捕捉资金涌入的市场热点和强势个股",
                StrategyDefinition.StrategyType.MOMENTUM, "WEEKLY", 12,
                List.of(
                        new FactorPref("MOM5", 0.25, 1),
                        new FactorPref("MOM20", 0.20, 1),
                        new FactorPref("LIMIT_UP_COUNT", 0.20, 1),
                        new FactorPref("VOLUME_RATIO", 0.20, 1),
                        new FactorPref("VROC12", 0.15, 1)
                ), availableCodes, new BigDecimal("0.10"));

        // ── 6. 均值回归策略（自定义脚本）────────────────────────────────
        String scriptCode = """
                // 均值回归策略脚本
                // 使用动量因子反向选取：前期跌幅较大的股票可能有反弹机会
                def momMap = factorValues['MOM20'] ?: [:]
                def volMap = factorValues['VOL20'] ?: [:]
                def scores = [:]
                marketBars.each { bar ->
                    def mom = momMap[bar.symbol]?.rankValue?.toDouble() ?: 0.5
                    def vol = volMap[bar.symbol]?.rankValue?.toDouble() ?: 0.5
                    // 动量排名低（前期跌幅大）+ 波动率适中 = 均值回归潜力
                    def score = (1 - mom) * 60 + (1 - Math.abs(vol - 0.5) * 2) * 40
                    scores[bar.symbol] = score
                }
                def top = scores.sort { -it.value }.take(maxPositions ?: 15)
                def totalScore = top.values().sum() ?: 1.0
                return top.collectEntries { k, v -> [k, v / totalScore] }
                """;
        createStrategyIfNotExists("CUSTOM_MEAN_REVERSION", "均值回归策略",
                "使用动量和波动率因子构建的均值回归策略，选取前期超跌的股票博取反弹收益",
                StrategyDefinition.StrategyType.CUSTOM, "WEEKLY", 15,
                null, scriptCode, new BigDecimal("0.08"));

        log.info("Strategy data initialization completed. Total strategies: {}", strategyMapper.selectCount(null));
    }

    /**
     * 基于实际可用因子动态构建策略配置，自动跳过不存在的因子
     */
    private void createDynamicStrategy(String code, String name, String description,
                                        StrategyDefinition.StrategyType type, String frequency,
                                        int maxPositions, List<FactorPref> prefs,
                                        Set<String> availableCodes, BigDecimal stopLoss) {
        List<Map<String, Object>> factors = new ArrayList<>();
        for (FactorPref pref : prefs) {
            if (availableCodes.contains(pref.code)) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("code", pref.code);
                f.put("weight", pref.weight);
                f.put("direction", pref.direction);
                factors.add(f);
            } else {
                log.warn("Strategy [{}] factor [{}] not available in factor_definition, skipping", code, pref.code);
            }
        }
        if (factors.isEmpty()) {
            log.error("Strategy [{}] has no available factors, skipping creation", code);
            return;
        }
        try {
            String factorConfig = objectMapper.writeValueAsString(Map.of("factors", factors));
            createStrategyIfNotExists(code, name, description, type, frequency, maxPositions, factorConfig, null, stopLoss);
        } catch (Exception e) {
            log.error("Failed to serialize factor config for strategy [{}]", code, e);
        }
    }

    private void createStrategyIfNotExists(String code, String name, String description,
                                           StrategyDefinition.StrategyType type, String frequency,
                                           int maxPositions, String factorConfig, String scriptCode,
                                           BigDecimal stopLoss) {
        var wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StrategyDefinition>();
        wrapper.eq(StrategyDefinition::getStrategyCode, code);
        long count = strategyMapper.selectCount(wrapper);
        if (count > 0) {
            log.info("Strategy {} already exists, skipping", code);
            return;
        }

        StrategyDefinition strategy = StrategyDefinition.builder()
                .strategyCode(code)
                .strategyName(name)
                .description(description)
                .strategyType(type)
                .status(StrategyDefinition.StrategyStatus.ACTIVE)
                .rebalanceFrequency(frequency)
                .maxPositionCount(maxPositions)
                .positionSizeType("EQUAL")
                .stopLossPct(stopLoss)
                .factorConfigJson(factorConfig)
                .scriptCode(scriptCode)
                .author("system")
                .build();

        strategyMapper.insert(strategy);
        log.info("Created strategy: {}", code);
    }
}
