package com.quant.platform.strategy.service;

import com.quant.platform.strategy.domain.StrategyDefinition;
import com.quant.platform.strategy.mapper.StrategyDefinitionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 策略数据初始化器
 * 在应用启动时自动初始化演示策略
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyDataInitializer {

    private final StrategyDefinitionMapper strategyMapper;

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

        // 1. 多因子选股策略
        createStrategyIfNotExists("MULTI_FACTOR_LONG", "多因子选股策略",
                "综合动量(MOM20)、波动率(VOL20)、市值(SIZE)因子，等权选取综合得分TOP20的股票构建组合",
                StrategyDefinition.StrategyType.FACTOR_LONG, "MONTHLY", 20,
                "{\"factors\":[{\"code\":\"MOM20\",\"weight\":0.4},{\"code\":\"VOL20\",\"weight\":-0.3},{\"code\":\"SIZE\",\"weight\":-0.3}]}",
                null, new BigDecimal("0.08"));

        // 2. 动量策略
        createStrategyIfNotExists("MOMENTUM_TOP10", "动量选股策略",
                "选取过去60日涨幅最高的10只股票，月度调仓",
                StrategyDefinition.StrategyType.MOMENTUM, "MONTHLY", 10,
                "{\"factors\":[{\"code\":\"MOM60\",\"weight\":1.0}]}",
                null, new BigDecimal("0.10"));

        // 3. 低波动策略
        createStrategyIfNotExists("LOW_VOLATILITY", "低波动策略",
                "选取过去20日波动率最低的股票，构建低波动组合，适合稳健型投资者",
                StrategyDefinition.StrategyType.FACTOR_LONG, "MONTHLY", 15,
                "{\"factors\":[{\"code\":\"VOL20\",\"weight\":-1.0}]}",
                null, new BigDecimal("0.05"));

        // 4. 小市值策略
        createStrategyIfNotExists("SMALL_CAP", "小市值策略",
                "选取市值最小的20只股票，月度调仓，追求小盘股的超额收益",
                StrategyDefinition.StrategyType.FACTOR_LONG, "MONTHLY", 20,
                "{\"factors\":[{\"code\":\"SIZE\",\"weight\":-1.0}]}",
                null, new BigDecimal("0.10"));

        // 5. 高换手率策略
        createStrategyIfNotExists("HIGH_TURNOVER", "高换手率策略",
                "选取近期换手率较高的活跃股票，捕捉市场热点",
                StrategyDefinition.StrategyType.FACTOR_LONG, "WEEKLY", 12,
                "{\"factors\":[{\"code\":\"TURN20\",\"weight\":1.0}]}",
                null, new BigDecimal("0.08"));

        // 6. 均值回归策略（自定义脚本）- 使用已有因子
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
                    // 分数越高越优先选择
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

    private void createStrategyIfNotExists(String code, String name, String description,
                                           StrategyDefinition.StrategyType type, String frequency,
                                           int maxPositions, String factorConfig, String scriptCode,
                                           BigDecimal stopLoss) {
        // 使用MyBatis-Plus查询是否存在
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
