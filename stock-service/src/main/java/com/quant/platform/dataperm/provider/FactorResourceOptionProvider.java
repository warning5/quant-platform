package com.quant.platform.dataperm.provider;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.quant.platform.common.enums.ResourceType;
import com.quant.spi.ResourceOptionVO;
import com.quant.spi.ResourceOptionProvider;
import com.quant.platform.factor.domain.FactorDefinition;
import com.quant.platform.factor.mapper.FactorDefinitionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** FACTOR 资源下拉选项提供方（业务模块注册，X2 SPI）。 */
@Component
@RequiredArgsConstructor
public class FactorResourceOptionProvider implements ResourceOptionProvider {

    private final FactorDefinitionMapper factorMapper;
    private static final int MAX_OPTION_ROWS = 200;

    @Override
    public ResourceType supports() {
        return ResourceType.FACTOR;
    }

    @Override
    public List<ResourceOptionVO> listOptions() {
        return factorMapper.selectList(new QueryWrapper<FactorDefinition>()
                        .select("id", "factor_code", "factor_name")
                        .last("ORDER BY id DESC LIMIT " + MAX_OPTION_ROWS))
                .stream().map(r -> new ResourceOptionVO(r.getId(),
                        r.getFactorCode() + " - " + (r.getFactorName() == null ? "" : r.getFactorName())))
                .toList();
    }
}
