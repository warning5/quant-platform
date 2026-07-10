package com.quant.platform.factor.engine;

import com.quant.platform.financial.entity.StockFinancialIndicator;
import com.quant.platform.stock.mapper.StockInfoMapper;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 财务因子实现集合（ACTIVE 状态）
 * 2026-07-10: 废弃4个冗余TTM因子(FIN_ROE_TTM/FIN_REVENUE_TTM_YOY/
 *   FIN_NET_PROFIT_TTM_YOY/FIN_TOTAL_ASSETS_YOY)和3个估值因子
 *   (VAL_PB/VAL_DIVIDEND_YIELD 的 FinancialFactors 版本)
 *   保留: FIN_ROE/FIN_REVENUE_YOY/FIN_NET_PROFIT_YOY/FIN_EARNINGS_QUALITY/
 *   FIN_REVENUE_QUALITY/FIN_RD_REVENUE_RATIO/FIN_OPERATING_PROFIT_YOY/FIN_TOTAL_EQUITY_YOY
 *   估值因子: VAL_PE_TTM/VAL_FCF_YIELD (FinancialFactors 版本仍保留，作为日频计算的兜底)
 */
@Setter
@Slf4j
@Component
public class FinancialFactors {

    private static final int SCALE = 8;

    @Autowired
    private StockInfoMapper stockInfoMapper;

    // ====================================================================
    // ACTIVE 财务因子（8个）
    // ====================================================================

    /**
     * ROE 净资产收益率 (%)
     */
    public static class RoeCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() { return "FIN_ROE"; }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            return ind.getRoe();
        }
    }

    /**
     * 营业收入同比增长率 (%)
     */
    public static class RevenueYoyCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() { return "FIN_REVENUE_YOY"; }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            return ind.getRevenueYoy();
        }
    }

    /**
     * 净利润同比增长率 (%)
     */
    public static class NetProfitYoyCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() { return "FIN_NET_PROFIT_YOY"; }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            return ind.getNetProfitYoy();
        }
    }

    /**
     * 盈利质量 - 经营现金流/净利润比率
     */
    public static class EarningsQualitySimpleCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() { return "FIN_EARNINGS_QUALITY"; }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            return ind.getOperatingCfToNp();
        }
    }

    /**
     * 营收质量 = 营收同比增长率 × 净利率
     */
    public static class RevenueQualityCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() { return "FIN_REVENUE_QUALITY"; }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            if (ind.getRevenueYoy() == null || ind.getNetProfitMargin() == null) return null;
            return ind.getRevenueYoy().multiply(ind.getNetProfitMargin())
                    .divide(BigDecimal.valueOf(100), SCALE, RoundingMode.HALF_UP);
        }
    }

    /**
     * 研发费用率 = rd_revenue_ratio
     */
    public static class RdRevenueRatioCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() { return "FIN_RD_REVENUE_RATIO"; }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            if (ind == null || ind.getRdRevenueRatio() == null) return null;
            return ind.getRdRevenueRatio().setScale(SCALE, RoundingMode.HALF_UP);
        }
    }

    /**
     * 营业利润同比增长率 (%)
     */
    public static class OperatingProfitYoyCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() { return "FIN_OPERATING_PROFIT_YOY"; }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            return ind.getOperatingProfitYoy();
        }
    }

    /**
     * 净资产同比增长率 (%)
     */
    public static class TotalEquityYoyCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() { return "FIN_TOTAL_EQUITY_YOY"; }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            return ind.getTotalEquityYoy();
        }
    }

    // ====================================================================
    // 估值因子（FinancialFactors 版本，非静态，作为日频计算兜底）
    // ====================================================================

    /**
     * 市盈率TTM = 总市值 / TTM净利润（FinancialFactors 版本）
     */
    public class PeTtmCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() { return "VAL_PE_TTM"; }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            // 简化：直接取 ind 中的 roe_ttm 估算或取 stock_daily 的 pe_ttm
            // 日频版本已在 BuiltinFactors 中实现，此处保留作兜底
            return null; // 兜底模式不计算，日频 BuiltinFactors 负责
        }
    }

    /**
     * 自由现金流收益率 = 自由现金流 / 总市值 × 100（FinancialFactors 版本）
     */
    public class FcfYieldCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() { return "VAL_FCF_YIELD"; }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            return null; // 兜底模式不计算，日频 BuiltinFactors 负责
        }
    }
}
