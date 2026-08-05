package com.quant.platform.dataperm.provider;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.quant.platform.common.enums.ResourceType;
import com.quant.spi.ResourceOptionVO;
import com.quant.spi.ResourceOptionProvider;
import com.quant.platform.strategy.paper.PaperTrading;
import com.quant.platform.strategy.paper.PaperTradingMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** PAPER_TRADING 资源下拉选项提供方（业务模块注册，X2 SPI）。 */
@Component
@RequiredArgsConstructor
public class PaperTradingResourceOptionProvider implements ResourceOptionProvider {

    private final PaperTradingMapper paperMapper;
    private static final int MAX_OPTION_ROWS = 200;

    @Override
    public ResourceType supports() {
        return ResourceType.PAPER_TRADING;
    }

    @Override
    public List<ResourceOptionVO> listOptions() {
        return paperMapper.selectList(new QueryWrapper<PaperTrading>()
                        .select("id", "strategy_code")
                        .last("ORDER BY id DESC LIMIT " + MAX_OPTION_ROWS))
                .stream().map(r -> new ResourceOptionVO(r.getId(),
                        "模拟盘 #" + r.getId()
                                + (r.getStrategyCode() != null ? "（策略:" + r.getStrategyCode() + "）" : "")))
                .toList();
    }
}
