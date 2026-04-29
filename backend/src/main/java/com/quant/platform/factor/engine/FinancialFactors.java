package com.quant.platform.factor.engine;

import com.quant.platform.financial.entity.StockFinancialIndicator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 财务因子实现集合
 * 包含25个基本面因子：盈利、成长、偿债、营运、现金流、每股指标
 */
@Component
public class FinancialFactors {

    private static final int SCALE = 8;

    // ======================== 盈利能力因子 ========================

    /** 毛利率 (%) */
    public static class GrossProfitMarginCalc implements FinancialFactorCalculator {
        @Override public String getFactorCode() { return "FIN_GROSS_MARGIN"; }
        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            return ind.getGrossProfitMargin();
        }
    }

    /** 净利率 (%) */
    public static class NetProfitMarginCalc implements FinancialFactorCalculator {
        @Override public String getFactorCode() { return "FIN_NET_MARGIN"; }
        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            return ind.getNetProfitMargin();
        }
    }

    /** ROE 净资产收益率 (%) */
    public static class RoeCalc implements FinancialFactorCalculator {
        @Override public String getFactorCode() { return "FIN_ROE"; }
        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            return ind.getRoe();
        }
    }

    /** ROA 总资产收益率 (%) */
    public static class RoaCalc implements FinancialFactorCalculator {
        @Override public String getFactorCode() { return "FIN_ROA"; }
        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            return ind.getRoa();
        }
    }

    /** 营业总成本/营业总收入 = 100 - 毛利率 */
    public static class TotalCostRatioCalc implements FinancialFactorCalculator {
        @Override public String getFactorCode() { return "FIN_TOTAL_COST_RATIO"; }
        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            if (ind.getGrossProfitMargin() == null) return null;
            return BigDecimal.valueOf(100).subtract(ind.getGrossProfitMargin())
                    .setScale(SCALE, RoundingMode.HALF_UP);
        }
    }

    /** 期间费用率(管理+销售+财务) = 毛利率 - 净利率 */
    public static class PeriodExpenseRatioCalc implements FinancialFactorCalculator {
        @Override public String getFactorCode() { return "FIN_PERIOD_EXPENSE_RATIO"; }
        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            if (ind.getGrossProfitMargin() == null || ind.getNetProfitMargin() == null) return null;
            return ind.getGrossProfitMargin().subtract(ind.getNetProfitMargin())
                    .setScale(SCALE, RoundingMode.HALF_UP);
        }
    }

    /** EBIT/营业总收入 ≈ ROA / 总资产周转率 */
    public static class EbitMarginCalc implements FinancialFactorCalculator {
        @Override public String getFactorCode() { return "FIN_EBIT_MARGIN"; }
        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            if (ind.getRoa() == null) return null;
            if (ind.getTotalAssetsTurnover() == null || ind.getTotalAssetsTurnover().doubleValue() == 0) return null;
            return ind.getRoa().divide(ind.getTotalAssetsTurnover(), SCALE, RoundingMode.HALF_UP);
        }
    }

    // ======================== 成长能力因子 ========================

    /** 营业收入同比增长率 (%) */
    public static class RevenueYoyCalc implements FinancialFactorCalculator {
        @Override public String getFactorCode() { return "FIN_REVENUE_YOY"; }
        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            return ind.getRevenueYoy();
        }
    }

    /** 净利润同比增长率 (%) */
    public static class NetProfitYoyCalc implements FinancialFactorCalculator {
        @Override public String getFactorCode() { return "FIN_NET_PROFIT_YOY"; }
        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            return ind.getNetProfitYoy();
        }
    }

    /** 营业利润同比增长率 (%) */
    public static class OperatingProfitYoyCalc implements FinancialFactorCalculator {
        @Override public String getFactorCode() { return "FIN_OPERATING_PROFIT_YOY"; }
        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            return ind.getOperatingProfitYoy();
        }
    }

    /** 总资产同比增长率 (%) */
    public static class TotalAssetsYoyCalc implements FinancialFactorCalculator {
        @Override public String getFactorCode() { return "FIN_TOTAL_ASSETS_YOY"; }
        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            return ind.getTotalAssetsYoy();
        }
    }

    /** 基本每股收益同比增长率（用净利润同比近似） */
    public static class EpsBasicYoyCalc implements FinancialFactorCalculator {
        @Override public String getFactorCode() { return "FIN_EPS_YOY"; }
        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            return ind.getNetProfitYoy();
        }
    }

    // ======================== 偿债能力因子 ========================

    /** 流动比率 */
    public static class CurrentRatioCalc implements FinancialFactorCalculator {
        @Override public String getFactorCode() { return "FIN_CURRENT_RATIO"; }
        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            return ind.getCurrentRatio();
        }
    }

    /** 速动比率 */
    public static class QuickRatioCalc implements FinancialFactorCalculator {
        @Override public String getFactorCode() { return "FIN_QUICK_RATIO"; }
        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            return ind.getQuickRatio();
        }
    }

    /** 资产负债率 (%) */
    public static class DebtToAssetRatioCalc implements FinancialFactorCalculator {
        @Override public String getFactorCode() { return "FIN_DEBT_TO_ASSET"; }
        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            return ind.getDebtToAssetRatio();
        }
    }

    /** 产权比率 = 资产负债率 / (1 - 资产负债率) */
    public static class DebtToEquityRatioCalc implements FinancialFactorCalculator {
        @Override public String getFactorCode() { return "FIN_DEBT_TO_EQUITY"; }
        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            if (ind.getDebtToAssetRatio() == null) return null;
            double da = ind.getDebtToAssetRatio().doubleValue();
            if (da >= 100) return null;
            return BigDecimal.valueOf(da / (100 - da)).setScale(SCALE, RoundingMode.HALF_UP);
        }
    }

    // ======================== 营运能力因子 ========================

    /** 应收账款周转率（次）= 365 / 周转天数 */
    public static class ArTurnoverCalc implements FinancialFactorCalculator {
        @Override public String getFactorCode() { return "FIN_AR_TURNOVER"; }
        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            if (ind.getArTurnoverDays() == null || ind.getArTurnoverDays().doubleValue() == 0) return null;
            return BigDecimal.valueOf(365.0 / ind.getArTurnoverDays().doubleValue())
                    .setScale(SCALE, RoundingMode.HALF_UP);
        }
    }

    /** 应收账款周转天数 */
    public static class ArTurnoverDaysCalc implements FinancialFactorCalculator {
        @Override public String getFactorCode() { return "FIN_AR_TURNOVER_DAYS"; }
        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            return ind.getArTurnoverDays();
        }
    }

    /** 总资产周转率（次） */
    public static class TotalAssetsTurnoverCalc implements FinancialFactorCalculator {
        @Override public String getFactorCode() { return "FIN_ASSETS_TURNOVER"; }
        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            return ind.getTotalAssetsTurnover();
        }
    }

    /** 存货周转率（次） */
    public static class InventoryTurnoverCalc implements FinancialFactorCalculator {
        @Override public String getFactorCode() { return "FIN_INVENTORY_TURNOVER"; }
        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            return ind.getInventoryTurnover();
        }
    }

    /** 存货周转天数 */
    public static class InventoryTurnoverDaysCalc implements FinancialFactorCalculator {
        @Override public String getFactorCode() { return "FIN_INVENTORY_TURNOVER_DAYS"; }
        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            return ind.getInventoryTurnoverDays();
        }
    }

    // ======================== 现金流因子 ========================

    /** 经营现金流/净利润 */
    public static class OperatingCfToNpCalc implements FinancialFactorCalculator {
        @Override public String getFactorCode() { return "FIN_CF_TO_NP"; }
        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            return ind.getOperatingCfToNp();
        }
    }

    /** 每股经营现金流 ≈ 经营现金流/净利润 × 每股收益 */
    public static class OperatingCfPerShareCalc implements FinancialFactorCalculator {
        @Override public String getFactorCode() { return "FIN_CF_PER_SHARE"; }
        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            if (ind.getOperatingCfToNp() == null || ind.getEpsBasic() == null) return null;
            return ind.getOperatingCfToNp().multiply(ind.getEpsBasic())
                    .divide(BigDecimal.valueOf(100), SCALE, RoundingMode.HALF_UP);
        }
    }

    /** 经营现金流/营业总收入 ≈ 经营现金流/净利润 × 净利率 */
    public static class OperatingCfToRevenueCalc implements FinancialFactorCalculator {
        @Override public String getFactorCode() { return "FIN_CF_TO_REVENUE"; }
        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            if (ind.getOperatingCfToNp() == null || ind.getNetProfitMargin() == null) return null;
            return ind.getOperatingCfToNp().multiply(ind.getNetProfitMargin())
                    .divide(BigDecimal.valueOf(100), SCALE, RoundingMode.HALF_UP);
        }
    }

    // ======================== 每股指标因子 ========================

    /** 每股净资产 BPS */
    public static class BpsCalc implements FinancialFactorCalculator {
        @Override public String getFactorCode() { return "FIN_BPS"; }
        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            return ind.getBps();
        }
    }

    // ======================== 质量因子 (QUALITY) ========================

    /** ROE稳定性 - 用ROE绝对值近似（多期ROE标准差需要多期数据，单期用ROE质量代理） */
    public static class RoeStabilityCalc implements FinancialFactorCalculator {
        @Override public String getFactorCode() { return "FIN_ROE_STABILITY"; }
        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            // ROE稳定性代理：用ROE/ROA的比率，比率越接近说明盈利结构越稳定
            if (ind.getRoe() == null || ind.getRoa() == null) return null;
            if (ind.getRoa().doubleValue() == 0) return null;
            // ROE/ROA = 权益乘数，越低越稳定（杠杆越小）
            return ind.getRoe().divide(ind.getRoa(), SCALE, RoundingMode.HALF_UP);
        }
    }

    /** 盈利质量 - 经营现金流/净利润比率，接近1说明利润含金量高 */
    public static class EarningsQualityCalc implements FinancialFactorCalculator {
        @Override public String getFactorCode() { return "FIN_EARNINGS_QUALITY"; }
        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            // 直接使用 operatingCfToNp 指标，值越接近1利润含金量越高
            return ind.getOperatingCfToNp();
        }
    }

    /** 毛利率稳定性代理 - 毛利率越高说明定价权越强（质量越好的代理指标） */
    public static class GrossMarginQualityCalc implements FinancialFactorCalculator {
        @Override public String getFactorCode() { return "FIN_GROSS_MARGIN_QUALITY"; }
        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            if (ind.getGrossProfitMargin() == null) return null;
            // 毛利率 > 40% 视为高质量，线性映射到 0~1
            double gpm = ind.getGrossProfitMargin().doubleValue();
            return BigDecimal.valueOf(Math.min(gpm / 40.0, 1.0)).setScale(SCALE, RoundingMode.HALF_UP);
        }
    }

    /** 经营杠杆 = 固定成本占比代理（用毛利率波动代理） */
    public static class OperatingLeverageCalc implements FinancialFactorCalculator {
        @Override public String getFactorCode() { return "FIN_OPERATING_LEVERAGE"; }
        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            // 经营杠杆代理：期间费用率/净利率，值越大说明固定成本占比越高
            if (ind.getGrossProfitMargin() == null || ind.getNetProfitMargin() == null) return null;
            if (ind.getNetProfitMargin().doubleValue() == 0) return null;
            double expenseRatio = ind.getGrossProfitMargin().doubleValue() - ind.getNetProfitMargin().doubleValue();
            return BigDecimal.valueOf(expenseRatio / ind.getNetProfitMargin().doubleValue())
                    .setScale(SCALE, RoundingMode.HALF_UP);
        }
    }

    /** 现金流质量 - 经营现金流/营收比率 */
    public static class CashFlowQualityCalc implements FinancialFactorCalculator {
        @Override public String getFactorCode() { return "FIN_CF_QUALITY"; }
        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            if (ind.getOperatingCfToNp() == null || ind.getNetProfitMargin() == null) return null;
            // CF/Revenue = CF/NP × NP/Revenue = CF/NP × netMargin/100
            return ind.getOperatingCfToNp().multiply(ind.getNetProfitMargin())
                    .divide(BigDecimal.valueOf(100), SCALE, RoundingMode.HALF_UP);
        }
    }

    // ======================== 自由现金流因子 ========================

    /** 自由现金流(亿元) — 经营现金流+投资现金流，正值说明公司能产生自由现金 */
    public static class FreeCashFlowCalc implements FinancialFactorCalculator {
        @Override public String getFactorCode() { return "FIN_FCF"; }
        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            if (ind.getFreeCashFlow() == null) return null;
            // 转换为亿元（原始单位为元），方便截面比较
            return ind.getFreeCashFlow()
                    .divide(BigDecimal.valueOf(1e8), SCALE, RoundingMode.HALF_UP);
        }
    }

    /** 自由现金流/经营现金流 — 反映经营赚来的钱有多少是自由的（扣除资本开支后） */
    public static class FreeCashFlowToOpCfCalc implements FinancialFactorCalculator {
        @Override public String getFactorCode() { return "FIN_FCF_TO_OPCF"; }
        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            if (ind.getFreeCashFlow() == null || ind.getNetOperateCf() == null) return null;
            double opCf = ind.getNetOperateCf().doubleValue();
            if (opCf == 0) return null;
            return ind.getFreeCashFlow().divide(ind.getNetOperateCf(), SCALE, RoundingMode.HALF_UP);
        }
    }

    /** 自由现金流/净利润 — FCF含金量，段永平最看重的指标之一，越接近1说明利润含金量越高 */
    public static class FreeCashFlowToNpCalc implements FinancialFactorCalculator {
        @Override public String getFactorCode() { return "FIN_FCF_TO_NP"; }
        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            // FCF/NP = (FCF/经营CF) × (经营CF/NP) = fcfToOpCf × operatingCfToNp/100
            if (ind.getFreeCashFlow() == null || ind.getNetOperateCf() == null) return null;
            if (ind.getOperatingCfToNp() == null) return null;
            double opCf = ind.getNetOperateCf().doubleValue();
            if (opCf == 0) return null;
            // FCF/NP = (FCF / 经营CF) × (经营CF / NP)
            double fcfToOpCf = ind.getFreeCashFlow().doubleValue() / opCf;
            double opCfToNp = ind.getOperatingCfToNp().doubleValue() / 100.0;
            return BigDecimal.valueOf(fcfToOpCf * opCfToNp).setScale(SCALE, RoundingMode.HALF_UP);
        }
    }

    // ======================== 价值因子 (VALUE - 财务数据部分) ========================

    /** 盈利收益率代理 = 净利率 / BPS 的倒数（不依赖市价） */
    public static class EarningsYieldCalc implements FinancialFactorCalculator {
        @Override public String getFactorCode() { return "FIN_EARNINGS_YIELD"; }
        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            // EP代理：ROE × 净利率 / 100（净资产收益率×利润率综合反映盈利能力）
            if (ind.getRoe() == null || ind.getNetProfitMargin() == null) return null;
            return ind.getRoe().multiply(ind.getNetProfitMargin())
                    .divide(BigDecimal.valueOf(10000), SCALE, RoundingMode.HALF_UP);
        }
    }

    /** 账面价值代理 = BPS 对数（不依赖市价，反映每股内在价值） */
    public static class BookValueCalc implements FinancialFactorCalculator {
        @Override public String getFactorCode() { return "FIN_BOOK_VALUE"; }
        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            if (ind.getBps() == null || ind.getBps().doubleValue() <= 0) return null;
            return BigDecimal.valueOf(Math.log(ind.getBps().doubleValue()))
                    .setScale(SCALE, RoundingMode.HALF_UP);
        }
    }

    /** 营收质量 = 营收同比增长率 × 净利率（高增长+高利润率 = 高质量） */
    public static class RevenueQualityCalc implements FinancialFactorCalculator {
        @Override public String getFactorCode() { return "FIN_REVENUE_QUALITY"; }
        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            if (ind.getRevenueYoy() == null || ind.getNetProfitMargin() == null) return null;
            return ind.getRevenueYoy().multiply(ind.getNetProfitMargin())
                    .divide(BigDecimal.valueOf(100), SCALE, RoundingMode.HALF_UP);
        }
    }
}
