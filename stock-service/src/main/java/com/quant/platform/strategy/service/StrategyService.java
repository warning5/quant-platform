package com.quant.platform.strategy.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.quant.platform.common.exception.BusinessException;
import com.quant.platform.common.exception.ResourceNotFoundException;
import com.quant.platform.common.security.GroovySandboxConfig;
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
        // 脚本安全验证（防止恶意脚本存入数据库）
        if (strategy.getScriptCode() != null && !strategy.getScriptCode().isBlank()) {
            String error = GroovySandboxConfig.validateScript(strategy.getScriptCode());
            if (error != null) {
                throw new BusinessException("Groovy脚本安全/语法验证未通过: " + error);
            }
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
        // 支持修改策略代码，需校验唯一性
        String newCode = update.getStrategyCode();
        if (newCode != null && !newCode.equals(existing.getStrategyCode())) {
            if (strategyMapper.existsByStrategyCodeExcluding(newCode, id)) {
                throw new BusinessException("策略代码已存在: " + newCode);
            }
            existing.setStrategyCode(newCode);
        }
        // 脚本安全验证（防止通过 update 注入恶意脚本绕过创建时验证）
        String newScriptCode = update.getScriptCode();
        if (newScriptCode != null && !newScriptCode.isBlank()) {
            String error = GroovySandboxConfig.validateScript(newScriptCode);
            if (error != null) {
                throw new BusinessException("Groovy脚本安全/语法验证未通过: " + error);
            }
        }
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
