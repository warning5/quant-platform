package com.quant.platform.financial.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quant.platform.financial.entity.*;
import com.quant.platform.financial.mapper.*;
import com.quant.platform.stock.entity.StockInfo;
import com.quant.platform.stock.mapper.StockInfoMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

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

    /**
     * 获取股票财务概览（最近一期指标）
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

        // 最近一期财务指标
        StockFinancialIndicator latestIndicator = getLatestIndicator(code);
        if (latestIndicator != null) {
            result.put("reportDate", latestIndicator.getReportDate());
            result.put("endDate", latestIndicator.getEndDate());
            result.put("roe", latestIndicator.getRoe());
            result.put("grossProfitMargin", latestIndicator.getGrossProfitMargin());
            result.put("netProfitMargin", latestIndicator.getNetProfitMargin());
            result.put("revenueYoy", latestIndicator.getRevenueYoy());
            result.put("netProfitYoy", latestIndicator.getNetProfitYoy());
            result.put("debtToAssetRatio", latestIndicator.getDebtToAssetRatio());
            result.put("currentRatio", latestIndicator.getCurrentRatio());
            result.put("epsBasic", latestIndicator.getEpsBasic());
            result.put("bps", latestIndicator.getBps());
            result.put("inventoryTurnover", latestIndicator.getInventoryTurnover());
            result.put("arTurnoverDays", latestIndicator.getArTurnoverDays());
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
     * 获取最近一期财务指标
     */
    private StockFinancialIndicator getLatestIndicator(String code) {
        LambdaQueryWrapper<StockFinancialIndicator> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StockFinancialIndicator::getCode, code)
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
            trend.add(item);
        }
        return trend;
    }

    private StockInfo getStockInfo(String code) {
        LambdaQueryWrapper<StockInfo> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StockInfo::getCode, code).last("LIMIT 1");
        return stockInfoMapper.selectOne(wrapper);
    }
}
