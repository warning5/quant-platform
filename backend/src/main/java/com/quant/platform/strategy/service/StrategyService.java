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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 策略管理服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyService {

    private final StrategyDefinitionMapper strategyMapper;

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
