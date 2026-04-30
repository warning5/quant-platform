package com.quant.platform.strategy.engine;

import com.quant.platform.factor.domain.FactorValue;
import com.quant.platform.market.domain.MarketDailyBar;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 策略信号接口 - 策略在每个调仓日返回目标持仓
 */
public interface StrategySignalGenerator {

    String getStrategyCode();

    /**
     * 生成目标持仓
     *
     * @param rebalanceDate    调仓日期
     * @param factorValues     当日因子值 (factorCode -> (symbol -> value))
     * @param marketBars       当日行情快照
     * @param currentPositions 当前持仓 (symbol -> weight)
     * @param context          额外上下文
     * @return 目标持仓 (symbol -> weight), weight之和应≤1
     */
    Map<String, Double> generateSignals(
            LocalDate rebalanceDate,
            Map<String, Map<String, FactorValue>> factorValues,
            List<MarketDailyBar> marketBars,
            Map<String, Double> currentPositions,
            Map<String, Object> context
    );
}
