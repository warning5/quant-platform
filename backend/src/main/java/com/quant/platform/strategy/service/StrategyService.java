package com.quant.platform.strategy.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.quant.platform.common.exception.BusinessException;
import com.quant.platform.common.exception.ResourceNotFoundException;
import com.quant.platform.strategy.domain.StrategyDefinition;
import com.quant.platform.strategy.mapper.StrategyDefinitionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 策略管理服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyService {

    private final StrategyDefinitionMapper strategyMapper;

    /**
     * 初始化内置演示策略
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void initDemoStrategies() {
        // 检查关键策略是否存在，不存在则重新初始化
        boolean hasMultiFactor = strategyMapper.existsByStrategyCode("MULTI_FACTOR_LONG");
        boolean hasMomentum = strategyMapper.existsByStrategyCode("MOMENTUM_TOP10");

        if (hasMultiFactor && hasMomentum) {
            log.info("Demo strategies already initialized, skipping");
            return;
        }

        log.info("Initializing demo strategies...");

        // 1. 多因子选股策略
        if (!hasMultiFactor) {
            StrategyDefinition factorLong = StrategyDefinition.builder()
                    .strategyCode("MULTI_FACTOR_LONG")
                    .strategyName("多因子选股策略")
                    .description("综合动量(MOM20)、波动率(VOL20)、市值(SIZE)因子，等权选取综合得分TOP20的股票构建组合")
                    .strategyType(StrategyDefinition.StrategyType.FACTOR_LONG)
                    .status(StrategyDefinition.StrategyStatus.ACTIVE)
                    .rebalanceFrequency("MONTHLY")
                    .maxPositionCount(20)
                    .positionSizeType("EQUAL")
                    .stopLossPct(BigDecimal.valueOf(0.08))
                    .factorConfigJson("{\"factors\":[{\"code\":\"MOM20\",\"weight\":0.4},{\"code\":\"VOL20\",\"weight\":-0.3},{\"code\":\"SIZE\",\"weight\":-0.3}]}")
                    .author("system")
                    .build();
            strategyMapper.insert(factorLong);
            log.info("Created strategy: MULTI_FACTOR_LONG");
        }

        // 2. 动量策略
        if (!hasMomentum) {
            StrategyDefinition momentum = StrategyDefinition.builder()
                    .strategyCode("MOMENTUM_TOP10")
                    .strategyName("动量选股策略")
                    .description("选取过去60日涨幅最高的10只股票，月度调仓")
                    .strategyType(StrategyDefinition.StrategyType.MOMENTUM)
                    .status(StrategyDefinition.StrategyStatus.ACTIVE)
                    .rebalanceFrequency("MONTHLY")
                    .maxPositionCount(10)
                    .positionSizeType("EQUAL")
                    .stopLossPct(BigDecimal.valueOf(0.10))
                    .factorConfigJson("{\"factors\":[{\"code\":\"MOM60\",\"weight\":1.0}]}")
                    .author("system")
                    .build();
            strategyMapper.insert(momentum);
            log.info("Created strategy: MOMENTUM_TOP10");
        }

        // 3. 低波动策略
        StrategyDefinition lowVol = StrategyDefinition.builder()
                .strategyCode("LOW_VOLATILITY")
                .strategyName("低波动策略")
                .description("选取过去20日波动率最低的股票，构建低波动组合，适合稳健型投资者")
                .strategyType(StrategyDefinition.StrategyType.FACTOR_LONG)
                .status(StrategyDefinition.StrategyStatus.ACTIVE)
                .rebalanceFrequency("MONTHLY")
                .maxPositionCount(15)
                .positionSizeType("EQUAL")
                .stopLossPct(BigDecimal.valueOf(0.05))
                .factorConfigJson("{\"factors\":[{\"code\":\"VOL20\",\"weight\":-1.0}]}")
                .author("system")
                .build();
        strategyMapper.insert(lowVol);

        // 4. 小市值策略
        StrategyDefinition smallCap = StrategyDefinition.builder()
                .strategyCode("SMALL_CAP")
                .strategyName("小市值策略")
                .description("选取市值最小的20只股票，月度调仓，追求小盘股的超额收益")
                .strategyType(StrategyDefinition.StrategyType.FACTOR_LONG)
                .status(StrategyDefinition.StrategyStatus.ACTIVE)
                .rebalanceFrequency("MONTHLY")
                .maxPositionCount(20)
                .positionSizeType("EQUAL")
                .stopLossPct(BigDecimal.valueOf(0.10))
                .factorConfigJson("{\"factors\":[{\"code\":\"SIZE\",\"weight\":-1.0}]}")
                .author("system")
                .build();
        strategyMapper.insert(smallCap);

        // 5. 高换手率策略
        StrategyDefinition highTurnover = StrategyDefinition.builder()
                .strategyCode("HIGH_TURNOVER")
                .strategyName("高换手率策略")
                .description("选取近期换手率较高的活跃股票，捕捉市场热点")
                .strategyType(StrategyDefinition.StrategyType.FACTOR_LONG)
                .status(StrategyDefinition.StrategyStatus.ACTIVE)
                .rebalanceFrequency("WEEKLY")
                .maxPositionCount(12)
                .positionSizeType("EQUAL")
                .stopLossPct(BigDecimal.valueOf(0.08))
                .factorConfigJson("{\"factors\":[{\"code\":\"TURN20\",\"weight\":1.0}]}")
                .author("system")
                .build();
        strategyMapper.insert(highTurnover);

        // 6. 均值回归策略（自定义脚本）- 使用已有因子
        StrategyDefinition custom = StrategyDefinition.builder()
                .strategyCode("CUSTOM_MEAN_REVERSION")
                .strategyName("均值回归策略")
                .description("使用动量和波动率因子构建的均值回归策略，选取前期超跌的股票博取反弹收益")
                .strategyType(StrategyDefinition.StrategyType.CUSTOM)
                .status(StrategyDefinition.StrategyStatus.ACTIVE)
                .rebalanceFrequency("WEEKLY")
                .maxPositionCount(15)
                .positionSizeType("FACTOR_WEIGHTED")
                .stopLossPct(BigDecimal.valueOf(0.08))
                .scriptCode("""
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
                        """)
                .author("system")
                .build();
        strategyMapper.insert(custom);

        log.info("Demo strategies initialized (total: 6)");
    }

    /**
     * 查询策略列表
     */
    public IPage<StrategyDefinition> searchStrategies(String keyword, String type, String status,
                                                      int page, int size) {
        StrategyDefinition.StrategyType st = type != null ? StrategyDefinition.StrategyType.valueOf(type) : null;
        StrategyDefinition.StrategyStatus ss = status != null ? StrategyDefinition.StrategyStatus.valueOf(status) : null;

        LambdaQueryWrapper<StrategyDefinition> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(keyword != null, StrategyDefinition::getStrategyName, keyword)
                .or()
                .like(keyword != null, StrategyDefinition::getStrategyCode, keyword)
                .eq(st != null, StrategyDefinition::getStrategyType, st)
                .eq(ss != null, StrategyDefinition::getStatus, ss)
                .orderByDesc(StrategyDefinition::getCreatedAt);

        return strategyMapper.selectPage(new Page<>(page + 1, size), wrapper);
    }

    /**
     * 获取策略详情
     */
    public StrategyDefinition getById(Long id) {
        StrategyDefinition result = strategyMapper.selectById(id);
        if (result == null) {
            throw new ResourceNotFoundException("策略", id);
        }
        return result;
    }

    /**
     * 创建策略
     */
    @Transactional
    public StrategyDefinition createStrategy(StrategyDefinition strategy) {
        if (strategyMapper.existsByStrategyCode(strategy.getStrategyCode())) {
            throw new BusinessException("策略代码已存在: " + strategy.getStrategyCode());
        }
        strategyMapper.insert(strategy);
        return strategy;
    }

    /**
     * 更新策略
     */
    @Transactional
    public StrategyDefinition updateStrategy(Long id, StrategyDefinition update) {
        StrategyDefinition existing = getById(id);
        existing.setStrategyName(update.getStrategyName());
        existing.setDescription(update.getDescription());
        existing.setStrategyType(update.getStrategyType());
        existing.setRebalanceFrequency(update.getRebalanceFrequency());
        existing.setMaxPositionCount(update.getMaxPositionCount());
        existing.setPositionSizeType(update.getPositionSizeType());
        existing.setStopLossPct(update.getStopLossPct());
        existing.setStopProfitPct(update.getStopProfitPct());
        existing.setMaxDrawdownPct(update.getMaxDrawdownPct());
        existing.setFactorConfigJson(update.getFactorConfigJson());
        existing.setScriptCode(update.getScriptCode());
        existing.setFilterConfigJson(update.getFilterConfigJson());
        existing.setVersion(existing.getVersion() + 1);
        existing.setStatus(StrategyDefinition.StrategyStatus.DRAFT);
        strategyMapper.updateById(existing);
        return existing;
    }

    /**
     * 删除策略
     */
    @Transactional
    public void deleteStrategy(Long id) {
        strategyMapper.deleteById(id);
    }

    /**
     * 更改策略状态
     */
    @Transactional
    public StrategyDefinition changeStatus(Long id, StrategyDefinition.StrategyStatus status) {
        StrategyDefinition s = getById(id);
        s.setStatus(status);
        strategyMapper.updateById(s);
        return s;
    }
}
