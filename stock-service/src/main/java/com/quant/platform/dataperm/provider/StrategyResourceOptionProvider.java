package com.quant.platform.dataperm.provider;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.quant.platform.common.enums.ResourceType;
import com.quant.spi.ResourceOptionVO;
import com.quant.spi.ResourceOptionProvider;
import com.quant.platform.strategy.domain.StrategyDefinition;
import com.quant.platform.strategy.mapper.StrategyDefinitionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** STRATEGY 资源下拉选项提供方（业务模块注册，X2 SPI）。 */
@Component
@RequiredArgsConstructor
public class StrategyResourceOptionProvider implements ResourceOptionProvider {

    private final StrategyDefinitionMapper strategyMapper;

    @Override
    public ResourceType supports() {
        return ResourceType.STRATEGY;
    }

    @Override
    public List<ResourceOptionVO> listOptions() {
        return strategyMapper.selectList(new QueryWrapper<StrategyDefinition>()
                        .select("id", "strategy_code", "strategy_name")
                        .last("ORDER BY id DESC LIMIT 200"))
                .stream().map(r -> new ResourceOptionVO(r.getId(),
                        r.getStrategyCode() + " - " + (r.getStrategyName() == null ? "" : r.getStrategyName())))
                .toList();
    }
}
