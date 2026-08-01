package com.quant.platform.dataperm.provider;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.quant.platform.common.enums.ResourceType;
import com.quant.spi.ResourceOptionVO;
import com.quant.spi.ResourceOptionProvider;
import com.quant.platform.backtest.domain.BacktestTask;
import com.quant.platform.backtest.mapper.BacktestTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** BACKTEST 资源下拉选项提供方（业务模块注册，X2 SPI）。 */
@Component
@RequiredArgsConstructor
public class BacktestResourceOptionProvider implements ResourceOptionProvider {

    private final BacktestTaskMapper backtestMapper;

    @Override
    public ResourceType supports() {
        return ResourceType.BACKTEST;
    }

    @Override
    public List<ResourceOptionVO> listOptions() {
        return backtestMapper.selectList(new QueryWrapper<BacktestTask>()
                        .select("id", "task_name", "strategy_code")
                        .last("ORDER BY id DESC LIMIT 200"))
                .stream().map(r -> new ResourceOptionVO(r.getId(),
                        (r.getTaskName() == null ? "回测#" + r.getId() : r.getTaskName())
                                + (r.getStrategyCode() != null ? "（策略:" + r.getStrategyCode() + "）" : "")))
                .toList();
    }
}
