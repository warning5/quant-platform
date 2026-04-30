package com.quant.platform.financial.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quant.platform.financial.entity.StockBalance;
import com.quant.platform.financial.entity.StockCashflow;
import com.quant.platform.financial.entity.StockFinancialIndicator;
import com.quant.platform.financial.entity.StockIncome;
import com.quant.platform.financial.mapper.StockBalanceMapper;
import com.quant.platform.financial.mapper.StockCashflowMapper;
import com.quant.platform.financial.mapper.StockFinancialIndicatorMapper;
import com.quant.platform.financial.mapper.StockIncomeMapper;
import com.quant.platform.stock.entity.StockInfo;
import com.quant.platform.stock.mapper.StockInfoMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 财务数据服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FinancialDataService {

    private final StockIncomeMapper incomeMapper;
    private final StockBalanceMapper balanceMapper;
    private final StockCashflowMapper cashflowMapper;
    private final StockFinancialIndicatorMapper indicatorMapper;
    private final StockInfoMapper stockInfoMapper;
    private final JdbcTemplate jdbcTemplate;

    /**
     * 获取股票财务概览
     * 优先取最新年报(report_type=4)——年报数据最完整（含现金流量表、含少数股东净利润等），
     * 一季报/中报/三季报因数据源(baostock)可能缺失部分字段，导致衍生指标无法计算。
     */
    public Map<String, Object> getFinancialOverview(String code) {
        // 获取股票基本信息
        StockInfo stockInfo = getStockInfo(code);
        Map<String, Object> result = new HashMap<>();
        if (stockInfo != null) {
            result.put("code", stockInfo.getCode());
            result.put("name", stockInfo.getName());
            result.put("market", stockInfo.getMarket());
        }

        // 优先取最新年报，若无年报则回退到最新一期
        StockFinancialIndicator indicator = getLatestIndicatorByType(code, 4);
        if (indicator == null) {
            indicator = getLatestIndicator(code);
        }
        if (indicator != null) {
            result.put("reportDate", indicator.getReportDate());
            result.put("endDate", indicator.getEndDate());
            result.put("roe", indicator.getRoe());
            result.put("grossProfitMargin", indicator.getGrossProfitMargin());
            result.put("netProfitMargin", indicator.getNetProfitMargin());
            result.put("revenueYoy", indicator.getRevenueYoy());
            result.put("netProfitYoy", indicator.getNetProfitYoy());
            result.put("debtToAssetRatio", indicator.getDebtToAssetRatio());
            result.put("currentRatio", indicator.getCurrentRatio());
            result.put("epsBasic", indicator.getEpsBasic());
            result.put("bps", indicator.getBps());
            result.put("inventoryTurnover", indicator.getInventoryTurnover());
            result.put("arTurnoverDays", indicator.getArTurnoverDays());
            result.put("freeCashFlow", indicator.getFreeCashFlow());
            result.put("netOperateCf", indicator.getNetOperateCf());
            result.put("operatingCfToNp", indicator.getOperatingCfToNp());
        }

        return result;
    }

    /**
     * 获取利润表历史
     */
    public List<StockIncome> getIncomeHistory(String code, int limit) {
        LambdaQueryWrapper<StockIncome> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StockIncome::getCode, code)
                .orderByDesc(StockIncome::getReportDate)
                .last("LIMIT " + Math.min(limit, 50));
        return incomeMapper.selectList(wrapper);
    }

    /**
     * 获取资产负债表历史
     */
    public List<StockBalance> getBalanceHistory(String code, int limit) {
        LambdaQueryWrapper<StockBalance> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StockBalance::getCode, code)
                .orderByDesc(StockBalance::getReportDate)
                .last("LIMIT " + Math.min(limit, 50));
        return balanceMapper.selectList(wrapper);
    }

    /**
     * 获取现金流量表历史
     */
    public List<StockCashflow> getCashflowHistory(String code, int limit) {
        LambdaQueryWrapper<StockCashflow> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StockCashflow::getCode, code)
                .orderByDesc(StockCashflow::getReportDate)
                .last("LIMIT " + Math.min(limit, 50));
        return cashflowMapper.selectList(wrapper);
    }

    /**
     * 获取财务指标历史
     */
    public List<StockFinancialIndicator> getIndicatorHistory(String code, int limit) {
        LambdaQueryWrapper<StockFinancialIndicator> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StockFinancialIndicator::getCode, code)
                .orderByDesc(StockFinancialIndicator::getReportDate)
                .last("LIMIT " + Math.min(limit, 50));
        return indicatorMapper.selectList(wrapper);
    }

    /**
     * 获取最近一期财务指标（不限报告类型）
     */
    private StockFinancialIndicator getLatestIndicator(String code) {
        LambdaQueryWrapper<StockFinancialIndicator> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StockFinancialIndicator::getCode, code)
                .orderByDesc(StockFinancialIndicator::getReportDate)
                .last("LIMIT 1");
        return indicatorMapper.selectOne(wrapper);
    }

    /**
     * 获取指定报告类型的最新一期财务指标
     */
    private StockFinancialIndicator getLatestIndicatorByType(String code, int reportType) {
        LambdaQueryWrapper<StockFinancialIndicator> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StockFinancialIndicator::getCode, code)
                .eq(StockFinancialIndicator::getReportType, reportType)
                .orderByDesc(StockFinancialIndicator::getReportDate)
                .last("LIMIT 1");
        return indicatorMapper.selectOne(wrapper);
    }

    /**
     * 获取有财务数据的股票列表（带最新指标摘要）
     */
    public List<Map<String, Object>> getStocksWithFinancialData(String keyword, int page, int size) {
        // 查询有最新年报财务指标的股票
        LambdaQueryWrapper<StockFinancialIndicator> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StockFinancialIndicator::getReportType, 4) // 年报
                .orderByDesc(StockFinancialIndicator::getReportDate);

        List<StockFinancialIndicator> indicators = indicatorMapper.selectList(wrapper);

        // 去重，每只股票只保留最新一条
        Map<String, StockFinancialIndicator> latestMap = new LinkedHashMap<>();
        for (StockFinancialIndicator ind : indicators) {
            latestMap.putIfAbsent(ind.getCode(), ind);
        }

        // 关联 stock_info 获取股票名称
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, StockFinancialIndicator> entry : latestMap.entrySet()) {
            String code = entry.getKey();
            StockFinancialIndicator ind = entry.getValue();

            StockInfo stockInfo = getStockInfo(code);
            if (stockInfo == null) continue;

            String name = stockInfo.getName();
            if (keyword != null && !keyword.isEmpty()
                    && !code.contains(keyword) && !name.contains(keyword)) {
                continue;
            }

            Map<String, Object> item = new HashMap<>();
            item.put("code", code);
            item.put("name", name);
            item.put("market", stockInfo.getMarket());
            item.put("industry", stockInfo.getIndustry());
            item.put("reportDate", ind.getReportDate());
            item.put("roe", ind.getRoe());
            item.put("grossProfitMargin", ind.getGrossProfitMargin());
            item.put("netProfitMargin", ind.getNetProfitMargin());
            item.put("revenueYoy", ind.getRevenueYoy());
            item.put("netProfitYoy", ind.getNetProfitYoy());
            item.put("debtToAssetRatio", ind.getDebtToAssetRatio());
            item.put("epsBasic", ind.getEpsBasic());
            item.put("bps", ind.getBps());
            result.add(item);
        }

        // 分页
        int fromIndex = page * size;
        int toIndex = Math.min(fromIndex + size, result.size());
        if (fromIndex >= result.size()) {
            return List.of();
        }
        return result.subList(fromIndex, toIndex);
    }

    /**
     * 获取有财务数据的股票总数
     */
    public long getFinancialStockCount() {
        // 查不同股票数
        LambdaQueryWrapper<StockFinancialIndicator> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StockFinancialIndicator::getReportType, 4);
        List<StockFinancialIndicator> list = indicatorMapper.selectList(wrapper);
        return list.stream().map(StockFinancialIndicator::getCode).distinct().count();
    }

    /**
     * 获取财务趋势数据（用于图表展示）
     */
    public List<Map<String, Object>> getFinancialTrend(String code) {
        List<StockFinancialIndicator> indicators = getIndicatorHistory(code, 20);
        // 按报告期正序
        Collections.reverse(indicators);

        List<Map<String, Object>> trend = new ArrayList<>();
        for (StockFinancialIndicator ind : indicators) {
            Map<String, Object> item = new HashMap<>();
            item.put("reportDate", ind.getReportDate());
            item.put("endDate", ind.getEndDate());
            item.put("roe", ind.getRoe());
            item.put("grossProfitMargin", ind.getGrossProfitMargin());
            item.put("netProfitMargin", ind.getNetProfitMargin());
            item.put("revenueYoy", ind.getRevenueYoy());
            item.put("netProfitYoy", ind.getNetProfitYoy());
            item.put("debtToAssetRatio", ind.getDebtToAssetRatio());
            item.put("currentRatio", ind.getCurrentRatio());
            item.put("freeCashFlow", ind.getFreeCashFlow());
            item.put("netOperateCf", ind.getNetOperateCf());
            item.put("operatingCfToNp", ind.getOperatingCfToNp());
            trend.add(item);
        }
        return trend;
    }

    private StockInfo getStockInfo(String code) {
        LambdaQueryWrapper<StockInfo> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StockInfo::getCode, code).last("LIMIT 1");
        return stockInfoMapper.selectOne(wrapper);
    }

    // ═══════════════════════════════════════════════════════════════
    // 三大流派选股
    // ═══════════════════════════════════════════════════════════════

    /**
     * 段永平派（价值投资）—— 近一年多期财务数据综合评估
     * 策略核心：好公司 + 好价格 + 现金流充裕 + 盈利稳定
     * 评估方式：取近15个月所有报告期，对ROE/毛利率/负债率取均值
     * 筛选条件：
     * - 平均ROE ≥ 15%（持续盈利能力强）
     * - 平均毛利率 ≥ 30%（护城河稳定）
     * - 最大CF/NP ≥ 80%（年报期利润含金量高，一季报/中报/三季报无现金流量表不影响）
     * - 平均资产负债率 ≤ 60%（财务稳健）
     * - PE(TTM) 0~30（估值合理）
     * - 至少2期数据（排除单期偶然）
     * 排序：综合得分 = avgROE×0.25 + avgGPM×0.15 + avgCF/NP×0.002×0.15 + (100-avgDebt)×0.15
     * + PE估值×0.15 + 稳定性加分×0.15（stdROE越低越加分）
     */
    @Cacheable(value = "stylePicks", key = "'duan-yongping-' + #limit", cacheManager = "stylePicksCacheManager")
    public Map<String, Object> getDuanYongpingPicks(int limit) {
        String sql = """
                SELECT t.code, si.name, si.pe_ttm, si.pb, si.total_market_cap,
                       t.avg_roe, t.avg_gpm, t.max_cfnp, t.avg_debt, t.periods,
                       t.std_roe, t.latest_report_date,
                       ROUND(t.avg_roe * 0.25
                           + t.avg_gpm * 0.15
                           + COALESCE(t.max_cfnp, 0) * 0.002 * 0.15
                           + (100 - t.avg_debt) * 0.15
                           + CASE WHEN si.pe_ttm > 0 AND si.pe_ttm <= 30
                                  THEN (30 - si.pe_ttm) / 30 * 100 * 0.15
                                  ELSE 0 END
                           + CASE WHEN t.std_roe <= 5 THEN 100
                                  WHEN t.std_roe <= 10 THEN 80
                                  WHEN t.std_roe <= 15 THEN 60
                                  ELSE 40 END * 0.15
                       , 2) AS score
                FROM (
                    SELECT fi.code,
                           ROUND(AVG(fi.roe), 2) AS avg_roe,
                           ROUND(AVG(fi.gross_profit_margin), 2) AS avg_gpm,
                           MAX(fi.operating_cf_to_np) AS max_cfnp,
                           ROUND(AVG(fi.debt_to_asset_ratio), 2) AS avg_debt,
                           ROUND(STD(fi.roe), 2) AS std_roe,
                           COUNT(*) AS periods,
                           MAX(fi.report_date) AS latest_report_date
                    FROM stock_financial_indicator fi
                    WHERE fi.report_date >= DATE_FORMAT(DATE_SUB(CURDATE(), INTERVAL 15 MONTH), '%Y%m%d')
                    GROUP BY fi.code
                    HAVING avg_roe >= 15 AND avg_gpm >= 30
                       AND (max_cfnp >= 80 OR max_cfnp IS NULL)
                       AND avg_debt <= 60
                       AND periods >= 2
                ) t
                JOIN stock_info si ON t.code = si.code
                WHERE si.pe_ttm > 0 AND si.pe_ttm <= 30 AND si.is_st = 0
                ORDER BY score DESC
                LIMIT ?
                """;
        List<Map<String, Object>> stocks = jdbcTemplate.queryForList(sql, limit);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("style", "段永平派");
        result.put("subtitle", "价值投资：好公司 + 好价格 + 现金流充裕");
        result.put("strategy", List.of(
                "评估方式：近15个月所有报告期（通常4期：一季报+中报+三季报+年报）综合评估",
                "平均ROE ≥ 15%（跨期均值）：持续赚钱能力强，非单期偶然",
                "平均毛利率 ≥ 30%（跨期均值）：护城河稳定，非单期波动",
                "最大CF/NP ≥ 80%（取年报最大值）：利润有真金白银支撑；一季报/中报/三季报无现金流量表不影响",
                "平均资产负债率 ≤ 60%（跨期均值）：财务稳健，不靠杠杆",
                "PE(TTM) 0~30：估值合理，不为情怀买单",
                "稳定性加分：ROE标准差越小加分越高（≤5→100分, ≤10→80分, ≤15→60分, >15→40分）",
                "至少2期数据：确保评估可靠性",
                "综合排序：盈利×25% + 护城河×15% + 利润含金量×15% + 财务稳健×15% + 估值×15% + 稳定性×15%"
        ));
        result.put("stocks", stocks);
        return result;
    }

    /**
     * 游资/短线派 —— 近一年多期财务数据综合评估
     * 策略核心：高弹性 + 资金关注 + 筹码集中
     * 评估方式：取近15个月所有报告期，取利润增速/营收增速最大值（体现最高弹性）
     * 筛选条件：
     * - 最大利润增速 ≥ 50% 或 最大营收增速 ≥ 30%（任一期高弹性）
     * - 总市值 10~800亿（游资偏爱中小盘）
     * - PE(TTM) > 0（不碰亏损股）
     * - 排除ST
     * 排序：利润增速×0.4 + 营收增速×0.3 + 市值得分×0.3
     */
    @Cacheable(value = "stylePicks", key = "'hot-money-' + #limit", cacheManager = "stylePicksCacheManager")
    public Map<String, Object> getHotMoneyPicks(int limit) {
        String sql = """
                SELECT t.code, t.name, t.pe_ttm, t.total_market_cap,
                       t.max_np_yoy, t.max_rev_yoy, t.avg_roe,
                       t.avg_gpm, t.periods, t.latest_report_date,
                       ROUND(
                           CASE WHEN t.max_np_yoy > 0 THEN LEAST(t.max_np_yoy, 1000) ELSE 0 END * 0.4
                         + CASE WHEN t.max_rev_yoy > 0 THEN LEAST(t.max_rev_yoy, 500) ELSE 0 END * 0.3
                         + CASE WHEN t.total_market_cap BETWEEN 30e8 AND 500e8 THEN 100
                                 WHEN t.total_market_cap < 30e8 THEN 60
                                 ELSE 30 END * 0.3
                       , 2) AS score
                FROM (
                    SELECT fi.code,
                           si.name,
                           si.pe_ttm,
                           si.total_market_cap,
                           MAX(fi.net_profit_yoy) AS max_np_yoy,
                           MAX(fi.revenue_yoy) AS max_rev_yoy,
                           ROUND(AVG(fi.roe), 2) AS avg_roe,
                           ROUND(AVG(fi.gross_profit_margin), 2) AS avg_gpm,
                           COUNT(*) AS periods,
                           MAX(fi.report_date) AS latest_report_date
                    FROM stock_financial_indicator fi
                    JOIN stock_info si ON fi.code = si.code
                    WHERE fi.report_date >= DATE_FORMAT(DATE_SUB(CURDATE(), INTERVAL 15 MONTH), '%Y%m%d')
                    GROUP BY fi.code, si.name, si.pe_ttm, si.total_market_cap
                    HAVING (max_np_yoy >= 50 OR max_rev_yoy >= 30)
                ) t
                WHERE t.total_market_cap BETWEEN 10e8 AND 800e8
                  AND t.max_np_yoy IS NOT NULL
                ORDER BY score DESC
                LIMIT ?
                """;
        List<Map<String, Object>> stocks = jdbcTemplate.queryForList(sql, limit);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("style", "游资/短线派");
        result.put("subtitle", "高弹性 + 中小盘 + 资金关注");
        result.put("strategy", List.of(
                "评估方式：近15个月所有报告期综合评估，取利润增速/营收增速最大值（体现最高弹性）",
                "最大利润增速 ≥ 50% 或 最大营收增速 ≥ 30%：任一期展现高弹性即可",
                "总市值 10~800亿：游资偏爱中小盘，易拉升",
                "PE > 0：排除亏损股，基本面有底线",
                "排除ST：规避退市风险",
                "至少2期数据：确保增速非单期异常",
                "综合排序：利润增速×40% + 营收增速×30% + 市值适配×30%（30~500亿加分）"
        ));
        result.put("stocks", stocks);
        return result;
    }

    /**
     * 量化派 —— 近一年多期财务数据综合评估
     * 策略核心：多因子综合评分，追求风险收益比
     * 评估方式：取近15个月所有报告期，因子取均值，加稳定性考量
     * 筛选条件：
     * - 平均ROE ≥ 8%（基本盈利能力）
     * - 平均资产负债率 ≤ 70%（风控底线）
     * - PE(TTM) 3~40（估值合理区间）
     * - 总市值 ≥ 50亿（流动性保障）
     * - 至少2期数据
     * - 排除ST
     * 排序：avgROE×0.2 + avgGPM×0.1 + 利润增速×0.15 + avgCF/NP×0.1 + 低估值×0.15
     * + 运营效率×0.1 + 稳定性×0.1 + 数据完整度×0.1
     */
    @Cacheable(value = "stylePicks", key = "'quant-' + #limit", cacheManager = "stylePicksCacheManager")
    public Map<String, Object> getQuantPicks(int limit) {
        String sql = """
                SELECT t.code, si.name, si.pe_ttm, si.pb, si.total_market_cap,
                       t.avg_roe, t.avg_gpm, t.max_np_yoy, t.avg_cfnp,
                       t.avg_debt, t.avg_turnover, t.std_roe, t.periods,
                       t.latest_report_date,
                       ROUND(
                           LEAST(t.avg_roe, 40) * 0.2
                         + LEAST(COALESCE(t.avg_gpm, 0), 60) * 0.1
                         + CASE WHEN t.max_np_yoy > 0 THEN LEAST(t.max_np_yoy, 300) ELSE ABS(GREATEST(t.max_np_yoy, -50)) END * 0.15
                         + CASE WHEN t.avg_cfnp >= 100 THEN 100
                                 WHEN t.avg_cfnp >= 50 THEN 70
                                 ELSE 30 END * 0.1
                         + CASE WHEN si.pe_ttm BETWEEN 3 AND 15 THEN 100
                                 WHEN si.pe_ttm BETWEEN 15 AND 25 THEN 70
                                 WHEN si.pe_ttm BETWEEN 25 AND 40 THEN 40
                                 ELSE 0 END * 0.15
                         + LEAST(COALESCE(t.avg_turnover, 0) * 100, 100) * 0.1
                         + CASE WHEN t.std_roe <= 5 THEN 100
                                 WHEN t.std_roe <= 10 THEN 80
                                 WHEN t.std_roe <= 15 THEN 60
                                 ELSE 40 END * 0.1
                         + LEAST(t.periods * 25, 100) * 0.1
                       , 2) AS score
                FROM (
                    SELECT fi.code,
                           ROUND(AVG(fi.roe), 2) AS avg_roe,
                           ROUND(AVG(fi.gross_profit_margin), 2) AS avg_gpm,
                           MAX(fi.net_profit_yoy) AS max_np_yoy,
                           ROUND(AVG(COALESCE(fi.operating_cf_to_np, 0)), 2) AS avg_cfnp,
                           ROUND(AVG(fi.debt_to_asset_ratio), 2) AS avg_debt,
                           ROUND(AVG(COALESCE(fi.total_assets_turnover, 0)), 4) AS avg_turnover,
                           ROUND(STD(fi.roe), 2) AS std_roe,
                           COUNT(*) AS periods,
                           MAX(fi.report_date) AS latest_report_date
                    FROM stock_financial_indicator fi
                    WHERE fi.report_date >= DATE_FORMAT(DATE_SUB(CURDATE(), INTERVAL 15 MONTH), '%Y%m%d')
                    GROUP BY fi.code
                    HAVING avg_roe >= 8 AND avg_debt <= 70 AND periods >= 2
                ) t
                JOIN stock_info si ON t.code = si.code
                WHERE si.pe_ttm BETWEEN 3 AND 40
                  AND si.total_market_cap >= 50e8 AND si.is_st = 0
                ORDER BY score DESC
                LIMIT ?
                """;
        List<Map<String, Object>> stocks = jdbcTemplate.queryForList(sql, limit);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("style", "量化派");
        result.put("subtitle", "多因子综合评分，追求风险收益比");
        result.put("strategy", List.of(
                "评估方式：近15个月所有报告期综合评估（通常4期），因子取均值+标准差",
                "平均ROE ≥ 8%（跨期均值）：基本盈利能力门槛",
                "平均资产负债率 ≤ 70%（跨期均值）：风控底线",
                "PE(TTM) 3~40：估值合理区间，兼顾安全边际与成长空间",
                "总市值 ≥ 50亿：流动性保障，降低冲击成本",
                "稳定性加分：ROE标准差越小加分越高（≤5→100分, ≤10→80分, ≤15→60分, >15→40分）",
                "数据完整度加分：报告期数越多越可靠（每期+25分，满分100）",
                "排除ST：规避退市风险",
                "综合排序：盈利×20% + 护城河×10% + 成长性×15% + 现金流×10% + 低估值×15% + 运营效率×10% + 稳定性×10% + 数据完整度×10%"
        ));
        result.put("stocks", stocks);
        return result;
    }
}
