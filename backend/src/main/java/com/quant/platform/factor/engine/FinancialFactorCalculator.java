package com.quant.platform.factor.engine;

import com.quant.platform.financial.entity.StockFinancialIndicator;

import java.math.BigDecimal;

/**
 * 财务因子计算接口
 * 与技术因子不同，财务因子的输入是财务指标数据而非行情数据
 */
public interface FinancialFactorCalculator {

    String getFactorCode();

    /**
     * 从财务指标中提取因子值
     *
     * @param code       股票代码
     * @param indicator  财务指标（最近一期年报数据）
     * @return 因子值，null表示无法计算
     */
    BigDecimal calculate(String code, StockFinancialIndicator indicator);
}
