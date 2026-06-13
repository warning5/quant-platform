package com.quant.platform.factor.engine;

import com.quant.platform.financial.entity.StockBalance;
import com.quant.platform.financial.entity.StockCashflow;
import com.quant.platform.financial.entity.StockFinancialIndicator;
import com.quant.platform.financial.entity.StockIncome;
import com.quant.platform.financial.mapper.StockBalanceMapper;
import com.quant.platform.financial.mapper.StockCashflowMapper;
import com.quant.platform.financial.mapper.StockIncomeMapper;
import com.quant.platform.stock.entity.StockDividend;
import com.quant.platform.stock.entity.StockInfo;
import com.quant.platform.stock.mapper.StockDividendMapper;
import com.quant.platform.stock.mapper.StockInfoMapper;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * 财务因子实现集合
 * 包含25个基本面因子：盈利、成长、偿债、营运、现金流、每股指标
 */
@Setter
@Slf4j
@Component
public class FinancialFactors {

    private static final int SCALE = 8;

    // 手动注入 Setter（供 FactorComputeEngine 调用）
    @Autowired
    private StockIncomeMapper stockIncomeMapper;

    @Autowired
    private StockBalanceMapper stockBalanceMapper;

    @Autowired
    private StockCashflowMapper stockCashflowMapper;

    @Autowired
    private StockInfoMapper stockInfoMapper;

    @Autowired
    private StockDividendMapper stockDividendMapper;

    // ======================== 盈利能力因子 ========================

    private StockIncome queryIncome(String code, LocalDate endDate) {
        if (code == null || endDate == null) return null;
        return stockIncomeMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StockIncome>()
                        .eq(StockIncome::getCode, code)
                        .le(StockIncome::getEndDate, endDate)
                        .orderByDesc(StockIncome::getEndDate)
                        .last("LIMIT 1")
        );
    }

    private StockBalance queryBalance(String code, LocalDate endDate) {
        if (code == null || endDate == null) return null;
        return stockBalanceMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StockBalance>()
                        .eq(StockBalance::getCode, code)
                        .le(StockBalance::getEndDate, endDate)
                        .orderByDesc(StockBalance::getEndDate)
                        .last("LIMIT 1")
        );
    }

    private StockCashflow queryCashflow(String code, LocalDate endDate) {
        if (code == null || endDate == null) return null;
        return stockCashflowMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StockCashflow>()
                        .eq(StockCashflow::getCode, code)
                        .le(StockCashflow::getEndDate, endDate)
                        .orderByDesc(StockCashflow::getEndDate)
                        .last("LIMIT 1")
        );
    }

    /**
     * 毛利率 (%)
     */
    public static class GrossProfitMarginCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() {
            return "FIN_GROSS_MARGIN";
        }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            return ind.getGrossProfitMargin();
        }
    }

    /**
     * 净利率 (%)
     */
    public static class NetProfitMarginCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() {
            return "FIN_NET_MARGIN";
        }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            return ind.getNetProfitMargin();
        }
    }

    /**
     * ROE 净资产收益率 (%)
     */
    public static class RoeCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() {
            return "FIN_ROE";
        }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            return ind.getRoe();
        }
    }

    /**
     * ROA 总资产收益率 (%)
     */
    public static class RoaCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() {
            return "FIN_ROA";
        }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            return ind.getRoa();
        }
    }

    // ======================== 成长能力因子 ========================

    /**
     * 营业总成本/营业总收入 = 100 - 毛利率
     */
    public static class TotalCostRatioCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() {
            return "FIN_TOTAL_COST_RATIO";
        }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            if (ind.getGrossProfitMargin() == null) return null;
            return BigDecimal.valueOf(100).subtract(ind.getGrossProfitMargin())
                    .setScale(SCALE, RoundingMode.HALF_UP);
        }
    }

    /**
     * 期间费用率(管理+销售+财务) = 毛利率 - 净利率
     */
    public static class PeriodExpenseRatioCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() {
            return "FIN_PERIOD_EXPENSE_RATIO";
        }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            if (ind.getGrossProfitMargin() == null || ind.getNetProfitMargin() == null) return null;
            return ind.getGrossProfitMargin().subtract(ind.getNetProfitMargin())
                    .setScale(SCALE, RoundingMode.HALF_UP);
        }
    }

    /**
     * EBIT/营业总收入 ≈ ROA / 总资产周转率
     */
    public static class EbitMarginCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() {
            return "FIN_EBIT_MARGIN";
        }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            if (ind.getRoa() == null) return null;
            if (ind.getTotalAssetsTurnover() == null || ind.getTotalAssetsTurnover().doubleValue() == 0) return null;
            return ind.getRoa().divide(ind.getTotalAssetsTurnover(), SCALE, RoundingMode.HALF_UP);
        }
    }

    /**
     * 营业收入同比增长率 (%)
     */
    public static class RevenueYoyCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() {
            return "FIN_REVENUE_YOY";
        }

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
        public String getFactorCode() {
            return "FIN_NET_PROFIT_YOY";
        }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            return ind.getNetProfitYoy();
        }
    }

    // ======================== 偿债能力因子 ========================

    /**
     * 营业利润同比增长率 (%)
     */
    public static class OperatingProfitYoyCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() {
            return "FIN_OPERATING_PROFIT_YOY";
        }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            return ind.getOperatingProfitYoy();
        }
    }

    /**
     * 总资产同比增长率 (%)
     */
    public static class TotalAssetsYoyCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() {
            return "FIN_TOTAL_ASSETS_YOY";
        }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            return ind.getTotalAssetsYoy();
        }
    }

    /**
     * 基本每股收益同比增长率（用净利润同比近似）
     */
    public static class EpsBasicYoyCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() {
            return "FIN_EPS_YOY";
        }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            return ind.getNetProfitYoy();
        }
    }

    /**
     * 流动比率
     */
    public static class CurrentRatioCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() {
            return "FIN_CURRENT_RATIO";
        }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            return ind.getCurrentRatio();
        }
    }

    // ======================== 营运能力因子 ========================

    /**
     * 速动比率
     */
    public static class QuickRatioCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() {
            return "FIN_QUICK_RATIO";
        }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            return ind.getQuickRatio();
        }
    }

    /**
     * 资产负债率 (%)
     */
    public static class DebtToAssetRatioCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() {
            return "FIN_DEBT_TO_ASSET";
        }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            return ind.getDebtToAssetRatio();
        }
    }

    /**
     * 产权比率 = 资产负债率 / (1 - 资产负债率)
     */
    public static class DebtToEquityRatioCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() {
            return "FIN_DEBT_TO_EQUITY";
        }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            if (ind.getDebtToAssetRatio() == null) return null;
            double da = ind.getDebtToAssetRatio().doubleValue();
            if (da >= 100) return null;
            return BigDecimal.valueOf(da / (100 - da)).setScale(SCALE, RoundingMode.HALF_UP);
        }
    }

    /**
     * 应收账款周转率（次）= 365 / 周转天数
     */
    public static class ArTurnoverCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() {
            return "FIN_AR_TURNOVER";
        }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            if (ind.getArTurnoverDays() == null || ind.getArTurnoverDays().doubleValue() == 0) return null;
            return BigDecimal.valueOf(365.0 / ind.getArTurnoverDays().doubleValue())
                    .setScale(SCALE, RoundingMode.HALF_UP);
        }
    }

    /**
     * 应收账款周转天数
     */
    public static class ArTurnoverDaysCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() {
            return "FIN_AR_TURNOVER_DAYS";
        }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            return ind.getArTurnoverDays();
        }
    }

    // ======================== 现金流因子 ========================

    /**
     * 总资产周转率（次）
     */
    public static class TotalAssetsTurnoverCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() {
            return "FIN_ASSETS_TURNOVER";
        }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            return ind.getTotalAssetsTurnover();
        }
    }

    /**
     * 存货周转率（次）
     */
    public static class InventoryTurnoverCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() {
            return "FIN_INVENTORY_TURNOVER";
        }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            return ind.getInventoryTurnover();
        }
    }

    /**
     * 存货周转天数
     */
    public static class InventoryTurnoverDaysCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() {
            return "FIN_INVENTORY_TURNOVER_DAYS";
        }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            return ind.getInventoryTurnoverDays();
        }
    }

    // ======================== 每股指标因子 ========================

    /**
     * 经营现金流/净利润
     */
    public static class OperatingCfToNpCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() {
            return "FIN_CF_TO_NP";
        }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            return ind.getOperatingCfToNp();
        }
    }

    // ======================== 质量因子 (QUALITY) ========================

    /**
     * 每股经营现金流 ≈ 经营现金流/净利润 × 每股收益
     */
    public static class OperatingCfPerShareCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() {
            return "FIN_CF_PER_SHARE";
        }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            if (ind.getOperatingCfToNp() == null || ind.getEpsBasic() == null) return null;
            return ind.getOperatingCfToNp().multiply(ind.getEpsBasic())
                    .divide(BigDecimal.valueOf(100), SCALE, RoundingMode.HALF_UP);
        }
    }

    /**
     * 经营现金流/营业总收入 ≈ 经营现金流/净利润 × 净利率
     */
    public static class OperatingCfToRevenueCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() {
            return "FIN_CF_TO_REVENUE";
        }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            if (ind.getOperatingCfToNp() == null || ind.getNetProfitMargin() == null) return null;
            return ind.getOperatingCfToNp().multiply(ind.getNetProfitMargin())
                    .divide(BigDecimal.valueOf(100), SCALE, RoundingMode.HALF_UP);
        }
    }

    /**
     * 每股净资产 BPS
     */
    public static class BpsCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() {
            return "FIN_BPS";
        }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            return ind.getBps();
        }
    }

    /**
     * ROE稳定性 - 用ROE绝对值近似（多期ROE标准差需要多期数据，单期用ROE质量代理）
     */
    public static class RoeStabilityCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() {
            return "FIN_ROE_STABILITY";
        }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            // ROE稳定性代理：用ROE/ROA的比率，比率越接近说明盈利结构越稳定
            if (ind.getRoe() == null || ind.getRoa() == null) return null;
            if (ind.getRoa().doubleValue() == 0) return null;
            // ROE/ROA = 权益乘数，越低越稳定（杠杆越小）
            return ind.getRoe().divide(ind.getRoa(), SCALE, RoundingMode.HALF_UP);
        }
    }

    /**
     * 盈利质量 - 经营现金流/净利润比率，接近1说明利润含金量高
     */
    public static class EarningsQualitySimpleCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() {
            return "FIN_EARNINGS_QUALITY";
        }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            // 直接使用 operatingCfToNp 指标，值越接近1利润含金量越高
            return ind.getOperatingCfToNp();
        }
    }

    // ======================== 自由现金流因子 ========================

    /**
     * 毛利率稳定性代理 - 毛利率越高说明定价权越强（质量越好的代理指标）
     */
    public static class GrossMarginQualityCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() {
            return "FIN_GROSS_MARGIN_QUALITY";
        }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            if (ind.getGrossProfitMargin() == null) return null;
            // 毛利率 > 40% 视为高质量，线性映射到 0~1
            double gpm = ind.getGrossProfitMargin().doubleValue();
            return BigDecimal.valueOf(Math.min(gpm / 40.0, 1.0)).setScale(SCALE, RoundingMode.HALF_UP);
        }
    }

    /**
     * 经营杠杆 = 固定成本占比代理（用毛利率波动代理）
     */
    public static class OperatingLeverageCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() {
            return "FIN_OPERATING_LEVERAGE";
        }

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

    /**
     * 现金流质量 - 经营现金流/营收比率
     */
    public static class CashFlowQualityCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() {
            return "FIN_CF_QUALITY";
        }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            if (ind.getOperatingCfToNp() == null || ind.getNetProfitMargin() == null) return null;
            // CF/Revenue = CF/NP × NP/Revenue = CF/NP × netMargin/100
            return ind.getOperatingCfToNp().multiply(ind.getNetProfitMargin())
                    .divide(BigDecimal.valueOf(100), SCALE, RoundingMode.HALF_UP);
        }
    }

    // ======================== 价值因子 (VALUE - 财务数据部分) ========================

    /**
     * 自由现金流(亿元) — 经营现金流+投资现金流，正值说明公司能产生自由现金
     */
    public static class FreeCashFlowCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() {
            return "FIN_FCF";
        }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            if (ind.getFreeCashFlow() == null) return null;
            // 转换为亿元（原始单位为元），方便截面比较
            return ind.getFreeCashFlow()
                    .divide(BigDecimal.valueOf(1e8), SCALE, RoundingMode.HALF_UP);
        }
    }

    /**
     * 自由现金流/经营现金流 — 反映经营赚来的钱有多少是自由的（扣除资本开支后）
     */
    public static class FreeCashFlowToOpCfCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() {
            return "FIN_FCF_TO_OPCF";
        }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            if (ind.getFreeCashFlow() == null || ind.getNetOperateCf() == null) return null;
            double opCf = ind.getNetOperateCf().doubleValue();
            if (opCf == 0) return null;
            return ind.getFreeCashFlow().divide(ind.getNetOperateCf(), SCALE, RoundingMode.HALF_UP);
        }
    }

    /**
     * 自由现金流/净利润 — FCF含金量，段永平最看重的指标之一，越接近1说明利润含金量越高
     */
    public static class FreeCashFlowToNpCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() {
            return "FIN_FCF_TO_NP";
        }

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

    // ======================== 需联查原始表的三因子 ========================

    /**
     * 盈利收益率代理 = 净利率 / BPS 的倒数（不依赖市价）
     */
    public static class EarningsYieldCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() {
            return "FIN_EARNINGS_YIELD";
        }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            // EP代理：ROE × 净利率 / 100（净资产收益率×利润率综合反映盈利能力）
            if (ind.getRoe() == null || ind.getNetProfitMargin() == null) return null;
            return ind.getRoe().multiply(ind.getNetProfitMargin())
                    .divide(BigDecimal.valueOf(10000), SCALE, RoundingMode.HALF_UP);
        }
    }

    /**
     * 账面价值代理 = BPS 对数（不依赖市价，反映每股内在价值）
     */
    public static class BookValueCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() {
            return "FIN_BOOK_VALUE";
        }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            if (ind.getBps() == null || ind.getBps().doubleValue() <= 0) return null;
            return BigDecimal.valueOf(Math.log(ind.getBps().doubleValue()))
                    .setScale(SCALE, RoundingMode.HALF_UP);
        }
    }

    /**
     * 营收质量 = 营收同比增长率 × 净利率（高增长+高利润率 = 高质量）
     */
    public static class RevenueQualityCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() {
            return "FIN_REVENUE_QUALITY";
        }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            if (ind.getRevenueYoy() == null || ind.getNetProfitMargin() == null) return null;
            return ind.getRevenueYoy().multiply(ind.getNetProfitMargin())
                    .divide(BigDecimal.valueOf(100), SCALE, RoundingMode.HALF_UP);
        }
    }

    /**
     * ROE(TTM) = 来自 stock_financial_indicator.roe_ttm（已预计算）
     */
    public static class RoeTtmCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() {
            return "FIN_ROE_TTM";
        }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            if (ind == null || ind.getRoeTtm() == null) return null;
            return ind.getRoeTtm().setScale(SCALE, RoundingMode.HALF_UP);
        }
    }

    /**
     * 营收同比(TTM) = 来自 stock_financial_indicator.revenue_ttm_yoy（已预计算）
     */
    public static class RevenueTtmYoyCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() {
            return "FIN_REVENUE_TTM_YOY";
        }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            if (ind == null || ind.getRevenueTtmYoy() == null) return null;
            return ind.getRevenueTtmYoy().setScale(SCALE, RoundingMode.HALF_UP);
        }
    }

    /**
     * 净利同比(TTM) = 来自 stock_financial_indicator.net_profit_ttm_yoy（已预计算）
     */
    public static class NetProfitTtmYoyCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() {
            return "FIN_NET_PROFIT_TTM_YOY";
        }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            if (ind == null || ind.getNetProfitTtmYoy() == null) return null;
            return ind.getNetProfitTtmYoy().setScale(SCALE, RoundingMode.HALF_UP);
        }
    }

    // ======================== 联查辅助方法 ========================

    /**
     * ROIC 投入资本回报率 (%)
     * ROIC = EBIT / 投入资本 × 100
     * EBIT ≈ 利润总额 + 财务费用
     * 投入资本 ≈ 总权益 + 总负债（简化版）
     */
    public class RoicCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() {
            return "FIN_ROIC";
        }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            if (ind == null || ind.getEndDate() == null) {
                return null;
            }

            // 查利润表：totalProfit, financeExpense, incomeTax, netProfitInclMinority
            StockIncome inc = queryIncome(code, ind.getEndDate());
            // 查资产负债表：totalEquity, totalLiabilities
            StockBalance bal = queryBalance(code, ind.getEndDate());

            if (inc == null) return null;
            if (bal == null) return null;

            BigDecimal totalProfit = inc.getTotalProfit();
            BigDecimal financeExpense = inc.getFinanceExpense();
            BigDecimal totalEquity = bal.getTotalEquity();
            BigDecimal totalLiabilities = bal.getTotalLiabilities();

            if (totalProfit == null || financeExpense == null || totalEquity == null || totalLiabilities == null) {
                return null;
            }
            if (financeExpense.compareTo(BigDecimal.ZERO) == 0) {
                return null;
            }

            // EBIT ≈ totalProfit + financeExpense
            BigDecimal ebit = totalProfit.add(financeExpense);
            // 投入资本 ≈ 总权益 + 总负债
            BigDecimal investedCap = totalEquity.add(totalLiabilities);
            if (investedCap.compareTo(BigDecimal.ZERO) == 0) {
                return null;
            }

            return ebit.divide(investedCap, SCALE, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
        }
    }

    /**
     * 利息保障倍数 = (利润总额 + 财务费用) / |财务费用|
     */
    public class InterestCoverageCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() {
            return "FIN_INTEREST_COVERAGE";
        }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            if (ind == null || ind.getEndDate() == null) {
                return null;
            }

            StockIncome inc = queryIncome(code, ind.getEndDate());
            if (inc == null) {
                return null;
            }

            BigDecimal totalProfit = inc.getTotalProfit();
            BigDecimal financeExpense = inc.getFinanceExpense();

            if (totalProfit == null || financeExpense == null) return null;
            if (financeExpense.compareTo(BigDecimal.ZERO) == 0) return null;

            BigDecimal numerator = totalProfit.add(financeExpense);
            BigDecimal denominator = financeExpense.abs();

            return numerator.divide(denominator, SCALE, RoundingMode.HALF_UP);
        }
    }

    /**
     * 经营现金流/总负债 (%)
     * = 经营活动现金流净额 / 总负债 × 100
     */
    public class OperatingCfToDebtCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() {
            return "FIN_OPERATING_CF_TO_DEBT";
        }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            if (ind == null || ind.getEndDate() == null) {
                return null;
            }

            // 查现金流量表：netOperateCf
            StockCashflow cf = queryCashflow(code, ind.getEndDate());
            // 查资产负债表：totalLiabilities
            StockBalance bal = queryBalance(code, ind.getEndDate());

            if (cf == null || bal == null) return null;

            BigDecimal netOperateCf = cf.getNetOperateCf();
            BigDecimal totalLiabilities = bal.getTotalLiabilities();

            if (netOperateCf == null || totalLiabilities == null) return null;
            if (totalLiabilities.compareTo(BigDecimal.ZERO) == 0) return null;

            return netOperateCf.divide(totalLiabilities, SCALE, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
        }
    }

    // ======================== 估值因子 (VALUE - 需联表市值/分红数据) ========================

    /**
     * 市盈率TTM = 总市值 / TTM净利润（最近4季度归属母公司净利润之和）
     * 正值=正常估值，负值=亏损（返回null排除）
     */
    public class PeTtmCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() {
            return "VAL_PE_TTM";
        }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            if (ind == null || ind.getEndDate() == null) return null;
            StockInfo info = stockInfoMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StockInfo>()
                            .eq(StockInfo::getCode, code).last("LIMIT 1"));
            if (info == null || info.getTotalMarketCap() == null || info.getTotalMarketCap().doubleValue() <= 0) return null;

            BigDecimal ttmProfit = computeTtmNetProfit(code, ind.getEndDate());
            if (ttmProfit == null || ttmProfit.doubleValue() <= 0) return null;

            return info.getTotalMarketCap().divide(ttmProfit, SCALE, RoundingMode.HALF_UP);
        }
    }

    /**
     * 市销率TTM = 总市值 / TTM营收（最近4季度营业总收入之和）
     */
    public class PsTtmCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() {
            return "VAL_PS_TTM";
        }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            if (ind == null || ind.getEndDate() == null) return null;
            StockInfo info = stockInfoMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StockInfo>()
                            .eq(StockInfo::getCode, code).last("LIMIT 1"));
            if (info == null || info.getTotalMarketCap() == null || info.getTotalMarketCap().doubleValue() <= 0) return null;

            BigDecimal ttmRevenue = computeTtmRevenue(code, ind.getEndDate());
            if (ttmRevenue == null || ttmRevenue.doubleValue() <= 0) return null;

            return info.getTotalMarketCap().divide(ttmRevenue, SCALE, RoundingMode.HALF_UP);
        }
    }

    /**
     * 市净率 = 总市值 / 净资产（BPS × 总股本）
     */
    public class PbCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() {
            return "VAL_PB";
        }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            if (ind == null) return null;
            if (ind.getBps() == null || ind.getBps().doubleValue() <= 0) return null;

            StockInfo info = stockInfoMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StockInfo>()
                            .eq(StockInfo::getCode, code).last("LIMIT 1"));
            if (info == null || info.getTotalMarketCap() == null || info.getTotalMarketCap().doubleValue() <= 0) return null;
            if (info.getTotalShare() == null || info.getTotalShare().doubleValue() <= 0) return null;

            // 净资产 = BPS × 总股本
            BigDecimal totalEquity = ind.getBps().multiply(info.getTotalShare());
            if (totalEquity.doubleValue() <= 0) return null;

            return info.getTotalMarketCap().divide(totalEquity, SCALE, RoundingMode.HALF_UP);
        }
    }

    /**
     * 股息率 = 最近12个月每股派息总和 / 当前股价 × 100
     * 当前股价用 总市值/总股本 近似
     */
    public class DividendYieldCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() {
            return "VAL_DIVIDEND_YIELD";
        }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            if (ind == null || ind.getEndDate() == null) return null;

            StockInfo info = stockInfoMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StockInfo>()
                            .eq(StockInfo::getCode, code).last("LIMIT 1"));
            if (info == null || info.getTotalMarketCap() == null || info.getTotalMarketCap().doubleValue() <= 0) return null;
            if (info.getTotalShare() == null || info.getTotalShare().doubleValue() <= 0) return null;

            // 查最近12个月的分红记录
            LocalDate startDate = ind.getEndDate().minusYears(1);
            LocalDate endDate = ind.getEndDate();
            List<StockDividend> dividends = stockDividendMapper.findByCodeAndDateRange(code, startDate, endDate);
            if (dividends == null || dividends.isEmpty()) return null;

            // 汇总每股派息
            BigDecimal totalDivPerShare = BigDecimal.ZERO;
            for (StockDividend d : dividends) {
                if (d.getCashDividend() != null && d.getCashDividend().doubleValue() > 0) {
                    totalDivPerShare = totalDivPerShare.add(d.getCashDividend());
                }
            }
            if (totalDivPerShare.doubleValue() <= 0) return null;

            // 当前股价 = 总市值 / 总股本
            BigDecimal pricePerShare = info.getTotalMarketCap().divide(info.getTotalShare(), SCALE, RoundingMode.HALF_UP);
            if (pricePerShare.doubleValue() <= 0) return null;

            // 股息率 = 每股派息 / 股价 × 100
            return totalDivPerShare.divide(pricePerShare, SCALE, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
        }
    }

    /**
     * 自由现金流收益率 = 自由现金流 / 总市值 × 100
     * 含义：公司产生的自由现金流相对于市值的比率，越高说明公司"便宜"且现金流充沛
     * 类似于股息率，但更全面（不依赖分红政策，反映真实可支配现金）
     */
    public class FcfYieldCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() {
            return "VAL_FCF_YIELD";
        }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            if (ind == null || ind.getEndDate() == null) return null;
            if (ind.getFreeCashFlow() == null || ind.getFreeCashFlow().doubleValue() <= 0) return null;

            StockInfo info = stockInfoMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StockInfo>()
                            .eq(StockInfo::getCode, code).last("LIMIT 1"));
            if (info == null || info.getTotalMarketCap() == null || info.getTotalMarketCap().doubleValue() <= 0) return null;

            // 总市值单位是万元，FCF单位是元
            // FCF Yield = FCF / (总市值 × 10000) × 100
            BigDecimal mcapYuan = info.getTotalMarketCap().multiply(BigDecimal.valueOf(10000));
            return ind.getFreeCashFlow().divide(mcapYuan, SCALE, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
        }
    }

    // ======================== 估值因子辅助方法 ========================

    /**
     * 计算 TTM 净利润（最近4季度归属母公司净利润之和）
     * A股财报规则：Q1(3月)累计3月，中报(6月)累计6月，Q3(9月)累计9月，年报(12月)累计12月
     * TTM = 最近一期年报净利润，或 最近一期累计 + 上年互补期
     */
    private BigDecimal computeTtmNetProfit(String code, LocalDate asOfDate) {
        // 查最近4期的利润表（覆盖当前累计+上年）
        List<StockIncome> incomes = stockIncomeMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StockIncome>()
                        .eq(StockIncome::getCode, code)
                        .le(StockIncome::getEndDate, asOfDate)
                        .orderByDesc(StockIncome::getEndDate)
                        .last("LIMIT 8"));

        if (incomes == null || incomes.isEmpty()) return null;

        // 按自然年分组，取每年最新一期（正向 TreeMap）
        java.util.TreeMap<Integer, StockIncome> byYear = new java.util.TreeMap<>();
        for (StockIncome inc : incomes) {
            int year = inc.getEndDate().getYear();
            byYear.putIfAbsent(year, inc);
        }

        if (byYear.isEmpty()) return null;

        // 最新一期的累计净利润（正向 TreeMap 的 lastEntry = 最大年份）
        java.util.Map.Entry<Integer, StockIncome> latest = byYear.lastEntry();
        StockIncome latestInc = latest.getValue();
        BigDecimal latestNp = latestInc.getNpParentCompanyOwners();
        if (latestNp == null) latestNp = latestInc.getNetProfit();
        if (latestNp == null) return null;

        int latestMonth = latestInc.getEndDate().getMonthValue();

        if (latestMonth == 12) {
            // 年报：直接使用年报净利润（已是TTM）
            return latestNp;
        }

        // 非年报：当前累计 + 上年互补期
        // 互补期 = 上年全年 - 上年同期累计
        java.util.Map.Entry<Integer, StockIncome> prevYearEntry = byYear.lowerEntry(latest.getKey());
        if (prevYearEntry == null) return null;

        StockIncome prevYearInc = prevYearEntry.getValue();
        BigDecimal prevYearFull = prevYearInc.getNpParentCompanyOwners();
        if (prevYearFull == null) prevYearFull = prevYearInc.getNetProfit();
        if (prevYearFull == null) return null;

        // 上年同期：需找到上一年同一截止月份的报告
        // 上市公司的报告月份通常是 3/6/9/12
        int targetMonth = latestMonth;
        BigDecimal prevSamePeriod = null;
        for (StockIncome inc : incomes) {
            if (inc.getEndDate().getYear() == latest.getKey() - 1 && inc.getEndDate().getMonthValue() == targetMonth) {
                prevSamePeriod = inc.getNpParentCompanyOwners();
                if (prevSamePeriod == null) prevSamePeriod = inc.getNetProfit();
                break;
            }
        }

        if (prevSamePeriod == null) return null;

        // TTM = 当前累计 + (上年全年 - 上年同期)
        BigDecimal complement = prevYearFull.subtract(prevSamePeriod);
        return latestNp.add(complement);
    }

    /**
     * 计算 TTM 营收（最近4季度营业总收入之和）
     * 逻辑同 computeTtmNetProfit
     */
    private BigDecimal computeTtmRevenue(String code, LocalDate asOfDate) {
        List<StockIncome> incomes = stockIncomeMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StockIncome>()
                        .eq(StockIncome::getCode, code)
                        .le(StockIncome::getEndDate, asOfDate)
                        .orderByDesc(StockIncome::getEndDate)
                        .last("LIMIT 8"));

        if (incomes == null || incomes.isEmpty()) return null;

        java.util.TreeMap<Integer, StockIncome> byYear = new java.util.TreeMap<>();
        for (StockIncome inc : incomes) {
            int year = inc.getEndDate().getYear();
            byYear.putIfAbsent(year, inc);
        }

        if (byYear.isEmpty()) return null;

        java.util.Map.Entry<Integer, StockIncome> latest = byYear.lastEntry();
        StockIncome latestInc = latest.getValue();
        BigDecimal latestRevenue = latestInc.getTotalRevenue();
        if (latestRevenue == null) return null;

        int latestMonth = latestInc.getEndDate().getMonthValue();

        if (latestMonth == 12) {
            return latestRevenue;
        }

        java.util.Map.Entry<Integer, StockIncome> prevYearEntry = byYear.lowerEntry(latest.getKey());
        if (prevYearEntry == null) return null;

        StockIncome prevYearInc = prevYearEntry.getValue();
        BigDecimal prevYearFull = prevYearInc.getTotalRevenue();
        if (prevYearFull == null) return null;

        int targetMonth = latestMonth;
        BigDecimal prevSamePeriod = null;
        for (StockIncome inc : incomes) {
            if (inc.getEndDate().getYear() == latest.getKey() - 1 && inc.getEndDate().getMonthValue() == targetMonth) {
                prevSamePeriod = inc.getTotalRevenue();
                break;
            }
        }

        if (prevSamePeriod == null) return null;

        BigDecimal complement = prevYearFull.subtract(prevSamePeriod);
        return latestRevenue.add(complement);
    }

    // ======================== 质量因子 (Phase 2.3) ========================

    /**
     * 盈利质量 = 营业现金流TTM / 净利润TTM
     *
     * 含义：经营活动产生的现金流与净利润的比值，衡量利润的现金含量。
     * > 1.0 说明利润有充足的现金支撑（质量好），< 0.7 说明可能存在应收账款过多。
     * 返回值就是比值本身（非百分位），后续由 CH 归一化。
     */
    public class EarningsQualityCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() {
            return "QUAL_EARNINGS";
        }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            if (ind == null || ind.getEndDate() == null) return null;

            // TTM 净利润
            BigDecimal ttmProfit = computeTtmNetProfit(code, ind.getEndDate());
            if (ttmProfit == null || ttmProfit.doubleValue() == 0) return null;

            // TTM 营业现金流：取最近4个季度的 netOperateCf 之和
            List<StockCashflow> cashflows = stockCashflowMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StockCashflow>()
                            .eq(StockCashflow::getCode, code)
                            .le(StockCashflow::getReportDate, ind.getEndDate())
                            .orderByDesc(StockCashflow::getReportDate)
                            .last("LIMIT 4")
            );
            if (cashflows.isEmpty()) return null;

            BigDecimal totalCf = BigDecimal.ZERO;
            for (StockCashflow cf : cashflows) {
                if (cf.getNetOperateCf() != null) {
                    totalCf = totalCf.add(cf.getNetOperateCf());
                }
            }
            if (totalCf.doubleValue() == 0) return null;

            return totalCf.divide(ttmProfit, SCALE, RoundingMode.HALF_UP);
        }
    }

    /**
     * 简化 Altman-Z（财务健康度）
     *
     * 原始 Z = 1.2X1 + 1.4X2 + 3.3X3 + 0.6X4 + 1.0X5
     * 简化版（适配可用数据）:
     *   X1 = (流动资产 - 流动负债) / 总资产  → Working Capital / Total Assets
     *   X3 = 息税前利润(≈营业利润) / 总资产  → Operating Profit / Total Assets
     *   X4 = 净资产 / 总资产  → Equity / Total Assets
     *
     * 返回 Z-Score，越高越安全。阈值: > 2.9 安全, 1.8~2.9 灰色, < 1.8 危险
     */
    public class FinancialHealthCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() {
            return "QUAL_HEALTH";
        }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            if (ind == null || ind.getEndDate() == null) return null;

            // 资产负债表（最新一期）
            StockBalance balance = queryBalance(code, ind.getEndDate());
            if (balance == null) return null;

            BigDecimal totalAssets = balance.getTotalAssets();
            if (totalAssets == null || totalAssets.doubleValue() <= 0) return null;

            // 利润表（最新一期）
            StockIncome income = queryIncome(code, ind.getEndDate());
            BigDecimal operatingProfit = income != null ? income.getOperatingProfit() : null;

            // X1: 流动比率贡献 (流动资产 - 流动负债) / 总资产
            BigDecimal currentAssets = balance.getTotalCurrentAssets();
            BigDecimal totalLiab = balance.getTotalLiabilities();
            double x1 = 0;
            if (currentAssets != null && totalLiab != null) {
                x1 = (currentAssets.doubleValue() - totalLiab.doubleValue()) / totalAssets.doubleValue();
            }

            // X3: 营业利润 / 总资产
            double x3 = 0;
            if (operatingProfit != null) {
                x3 = operatingProfit.doubleValue() / totalAssets.doubleValue();
            }

            // X4: 净资产 / 总资产
            BigDecimal equity = balance.getTotalEquity();
            double x4 = 0;
            if (equity != null) {
                x4 = equity.doubleValue() / totalAssets.doubleValue();
            }

            // 简化 Z = 1.2*X1 + 3.3*X3 + 1.0*X4
            double z = 1.2 * x1 + 3.3 * x3 + 1.0 * x4;
            return BigDecimal.valueOf(z).setScale(SCALE, RoundingMode.HALF_UP);
        }
    }

    /**
     * 营收稳定性 = 最近4个季度营收的标准差 / 平均营收的反比
     *
     * 标准差小且均值为正 → 稳定增长 → 得分高
     * 返回值 = 平均营收 / (标准差 + 1)，值越大越稳定
     */
    public class RevenueStabilityCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() {
            return "QUAL_REVENUE_STABILITY";
        }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            if (ind == null || ind.getEndDate() == null) return null;

            List<StockIncome> incomes = stockIncomeMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StockIncome>()
                            .eq(StockIncome::getCode, code)
                            .le(StockIncome::getEndDate, ind.getEndDate())
                            .orderByDesc(StockIncome::getEndDate)
                            .last("LIMIT 4")
            );
            if (incomes.size() < 2) return null;

            double sum = 0;
            int count = 0;
            for (StockIncome inc : incomes) {
                if (inc.getTotalRevenue() != null) {
                    sum += inc.getTotalRevenue().doubleValue();
                    count++;
                }
            }
            if (count < 2) return null;

            double mean = sum / count;
            double sumSq = 0;
            for (StockIncome inc : incomes) {
                if (inc.getTotalRevenue() != null) {
                    double diff = inc.getTotalRevenue().doubleValue() - mean;
                    sumSq += diff * diff;
                }
            }
            double stdDev = Math.sqrt(sumSq / count);

            // 平均营收 / (标准差 + 1)，值越大越稳定
            double stability = mean / (stdDev + 1);
            return BigDecimal.valueOf(stability).setScale(SCALE, RoundingMode.HALF_UP);
        }
    }
}
