package com.quant.platform.factor.engine;

import com.quant.platform.market.domain.MarketDailyBar;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 因子计算接口 - 所有因子计算器的基础接口
 * 自定义因子需要实现此接口（或通过Groovy脚本实现）
 */
public interface FactorCalculator {

    /**
     * 因子代码
     */
    String getFactorCode();

    /**
     * 计算单只股票在某日的因子值
     *
     * @param symbol    股票代码
     * @param calcDate  计算日期
     * @param history   历史数据（时间正序）
     * @param context   额外上下文（其他因子值等）
     * @return 因子值，null表示无法计算
     */
    BigDecimal calculate(String symbol, LocalDate calcDate,
                         List<MarketDailyBar> history,
                         Map<String, Object> context);
}
