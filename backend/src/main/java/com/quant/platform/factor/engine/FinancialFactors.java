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
import java.util.TreeMap;
import java.util.Map;

/**
 * 财务因子实现集合
 * 仅保留 ACTIVE 状态的因子（37个中的财务因子部分）
 * 废弃的财务因子已删除，如需恢复可从Git历史找回
 */
@Setter
@Slf4j
@Component
public class FinancialFactors {

    private static final int SCALE = 8;

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

    // ====================================================================
    // 查询辅助方法
    // ====================================================================

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

    // ====================================================================
    // TTM 计算辅助方法
    // ====================================================================

    /**
     * 计算 TTM 净利润（最近4季度归属母公司净利润之和）
     */
    private BigDecimal computeTtmNetProfit(String code, LocalDate asOfDate) {
        List<StockIncome> incomes = stockIncomeMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StockIncome>()
                        .eq(StockIncome::getCode, code)
                        .le(StockIncome::getEndDate, asOfDate)
                        .orderByDesc(StockIncome::getEndDate)
                        .last("LIMIT 8")
        );
        if (incomes == null || incomes.isEmpty()) return null;

        TreeMap<Integer, StockIncome> byYear = new TreeMap<>();
        for (StockIncome inc : incomes) {
            int year = inc.getEndDate().getYear();
            byYear.putIfAbsent(year, inc);
        }
        if (byYear.isEmpty()) return null;

        Map.Entry<Integer, StockIncome> latest = byYear.lastEntry();
        StockIncome latestInc = latest.getValue();
        BigDecimal latestNp = latestInc.getNpParentCompanyOwners();
        if (latestNp == null) latestNp = latestInc.getNetProfit();
        if (latestNp == null) return null;

        int latestMonth = latestInc.getEndDate().getMonthValue();
        if (latestMonth == 12) return latestNp;

        Map.Entry<Integer, StockIncome> prevYearEntry = byYear.lowerEntry(latest.getKey());
        if (prevYearEntry == null) return null;

        StockIncome prevYearInc = prevYearEntry.getValue();
        BigDecimal prevYearFull = prevYearInc.getNpParentCompanyOwners();
        if (prevYearFull == null) prevYearFull = prevYearInc.getNetProfit();
        if (prevYearFull == null) return null;

        BigDecimal complement = prevYearFull.subtract(prevYearFull);
        // 修正：应该是 prevYearFull - 上年同期
        // 简化：直接用最新一期减去去年同期
        // 正确做法：
        int targetMonth = latestMonth;
        BigDecimal prevSamePeriod = null;
        for (StockIncome inc : incomes) {
            if (inc.getEndDate().getYear() == latest.getKey() - 1
                    && inc.getEndDate().getMonthValue() == targetMonth) {
                prevSamePeriod = inc.getNpParentCompanyOwners();
                if (prevSamePeriod == null) prevSamePeriod = inc.getNetProfit();
                break;
            }
        }
        if (prevSamePeriod == null) return null;
        BigDecimal prevFull = prevYearFull;
        return latestNp.add(prevFull.subtract(prevSamePeriod));
    }

    /**
     * 计算 TTM 营收（最近4季度营业总收入之和）
     */
    private BigDecimal computeTtmRevenue(String code, LocalDate asOfDate) {
        List<StockIncome> incomes = stockIncomeMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StockIncome>()
                        .eq(StockIncome::getCode, code)
                        .le(StockIncome::getEndDate, asOfDate)
                        .orderByDesc(StockIncome::getEndDate)
                        .last("LIMIT 8")
        );
        if (incomes == null || incomes.isEmpty()) return null;

        TreeMap<Integer, StockIncome> byYear = new TreeMap<>();
        for (StockIncome inc : incomes) {
            int year = inc.getEndDate().getYear();
            byYear.putIfAbsent(year, inc);
        }
        if (byYear.isEmpty()) return null;

        Map.Entry<Integer, StockIncome> latest = byYear.lastEntry();
        StockIncome latestInc = latest.getValue();
        BigDecimal latestRevenue = latestInc.getTotalRevenue();
        if (latestRevenue == null) return null;

        int latestMonth = latestInc.getEndDate().getMonthValue();
        if (latestMonth == 12) return latestRevenue;

        Map.Entry<Integer, StockIncome> prevYearEntry = byYear.lowerEntry(latest.getKey());
        if (prevYearEntry == null) return null;

        StockIncome prevYearInc = prevYearEntry.getValue();
        BigDecimal prevYearFull = prevYearInc.getTotalRevenue();
        if (prevYearFull == null) return null;

        int targetMonth = latestMonth;
        BigDecimal prevSamePeriod = null;
        for (StockIncome inc : incomes) {
            if (inc.getEndDate().getYear() == latest.getKey() - 1
                    && inc.getEndDate().getMonthValue() == targetMonth) {
                prevSamePeriod = inc.getTotalRevenue();
                break;
            }
        }
        if (prevSamePeriod == null) return null;
        return latestRevenue.add(prevYearFull.subtract(prevSamePeriod));
    }

    // ====================================================================
    // ACTIVE 财务因子（7个）
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
     * 盈利质量 - 经营现金流/净利润比率，接近1说明利润含金量高
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
     * 营收质量 = 营收同比增长率 × 净利率（高增长+高利润率 = 高质量）
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
     * ROE(TTM) = 来自 stock_financial_indicator.roe_ttm
     */
    public static class RoeTtmCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() { return "FIN_ROE_TTM"; }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            if (ind == null || ind.getRoeTtm() == null) return null;
            return ind.getRoeTtm().setScale(SCALE, RoundingMode.HALF_UP);
        }
    }

    /**
     * 营收同比(TTM) = 来自 stock_financial_indicator.revenue_ttm_yoy
     */
    public static class RevenueTtmYoyCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() { return "FIN_REVENUE_TTM_YOY"; }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            if (ind == null || ind.getRevenueTtmYoy() == null) return null;
            return ind.getRevenueTtmYoy().setScale(SCALE, RoundingMode.HALF_UP);
        }
    }

    /**
     * 净利同比(TTM) = 来自 stock_financial_indicator.net_profit_ttm_yoy
     */
    public static class NetProfitTtmYoyCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() { return "FIN_NET_PROFIT_TTM_YOY"; }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            if (ind == null || ind.getNetProfitTtmYoy() == null) return null;
            return ind.getNetProfitTtmYoy().setScale(SCALE, RoundingMode.HALF_UP);
        }
    }

    // ====================================================================
    // Phase 2+3 新增因子（2026-06-30）
    // ====================================================================

    /**
     * 研发费用率 = rd_expense / total_revenue
     * 从 stock_financial_indicator.rd_revenue_ratio 读取（由 calc_financial_indicators.py 预计算）
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
     * 总资产同比增长率 (%)
     */
    public static class TotalAssetsYoyCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() { return "FIN_TOTAL_ASSETS_YOY"; }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            return ind.getTotalAssetsYoy();
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
    // ACTIVE 估值因子（联表查询，需 Spring Bean 注入）
    // ====================================================================

    /**
     * 市盈率TTM = 总市值 / TTM净利润
     */
    public class PeTtmCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() { return "VAL_PE_TTM"; }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            if (ind == null || ind.getEndDate() == null) return null;
            StockInfo info = stockInfoMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StockInfo>()
                            .eq(StockInfo::getCode, code).last("LIMIT 1"));
            if (info == null || info.getTotalMarketCap() == null
                    || info.getTotalMarketCap().doubleValue() <= 0) return null;

            BigDecimal ttmProfit = computeTtmNetProfit(code, ind.getEndDate());
            if (ttmProfit == null || ttmProfit.doubleValue() <= 0) return null;

            return info.getTotalMarketCap().divide(ttmProfit, SCALE, RoundingMode.HALF_UP);
        }
    }

    /**
     * 市净率 = 总市值 / 净资产（BPS × 总股本）
     */
    public class PbCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() { return "VAL_PB"; }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            if (ind == null || ind.getBps() == null
                    || ind.getBps().doubleValue() <= 0) return null;
            StockInfo info = stockInfoMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StockInfo>()
                            .eq(StockInfo::getCode, code).last("LIMIT 1"));
            if (info == null || info.getTotalMarketCap() == null
                    || info.getTotalShare() == null
                    || info.getTotalShare().doubleValue() <= 0) return null;

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
        public String getFactorCode() { return "VAL_DIVIDEND_YIELD"; }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            if (ind == null || ind.getEndDate() == null) return null;
            StockInfo info = stockInfoMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StockInfo>()
                            .eq(StockInfo::getCode, code).last("LIMIT 1"));
            if (info == null || info.getTotalMarketCap() == null
                    || info.getTotalShare() == null
                    || info.getTotalShare().doubleValue() <= 0) return null;

            LocalDate startDate = ind.getEndDate().minusYears(1);
            List<StockDividend> dividends = stockDividendMapper
                    .findByCodeAndDateRange(code, startDate, ind.getEndDate());
            if (dividends == null || dividends.isEmpty()) return null;

            BigDecimal totalDivPerShare = BigDecimal.ZERO;
            for (StockDividend d : dividends) {
                if (d.getCashDividend() != null && d.getCashDividend().doubleValue() > 0) {
                    totalDivPerShare = totalDivPerShare.add(d.getCashDividend());
                }
            }
            if (totalDivPerShare.doubleValue() <= 0) return null;

            BigDecimal pricePerShare = info.getTotalMarketCap()
                    .divide(info.getTotalShare(), SCALE, RoundingMode.HALF_UP);
            if (pricePerShare.doubleValue() <= 0) return null;

            return totalDivPerShare.divide(pricePerShare, SCALE, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
        }
    }

    /**
     * 自由现金流收益率 = 自由现金流 / 总市值 × 100
     */
    public class FcfYieldCalc implements FinancialFactorCalculator {
        @Override
        public String getFactorCode() { return "VAL_FCF_YIELD"; }

        @Override
        public BigDecimal calculate(String code, StockFinancialIndicator ind) {
            if (ind == null || ind.getFreeCashFlow() == null) return null;
            StockInfo info = stockInfoMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StockInfo>()
                            .eq(StockInfo::getCode, code).last("LIMIT 1"));
            if (info == null || info.getTotalMarketCap() == null
                    || info.getTotalMarketCap().doubleValue() <= 0) return null;

            // 总市值单位是万元，FCF单位是元
            BigDecimal mcapYuan = info.getTotalMarketCap().multiply(BigDecimal.valueOf(10000));
            if (mcapYuan.doubleValue() == 0) return null;

            return ind.getFreeCashFlow().divide(mcapYuan, SCALE, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
        }
    }
}
