package com.quant.platform.backtest.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.common.exception.BusinessException;
import com.quant.platform.stock.entity.StockDaily;
import com.quant.platform.stock.entity.StockInfo;
import com.quant.platform.stock.mapper.StockDailyMapper;
import com.quant.platform.stock.mapper.StockInfoMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Brinson 归因分析服务
 * 将组合超额收益分解为：配置效应(Allocation) + 选股效应(Selection) + 交互效应(Interaction)
 * <p>
 * 采用全市场行业基准方法：
 * - 基准行业权重 = 当日全市场股票按流通市值加权的行业分布
 * - 基准行业收益 = 各行业内股票按流通市值加权的平均收益率
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrinsonAttributionService {

    private final StockInfoMapper stockInfoMapper;
    private final StockDailyMapper stockDailyMapper;
    private final ObjectMapper objectMapper;

    /** 需要排除的指数名称 */
    private static final Set<String> INDEX_NAMES = Set.of(
            "上证指数", "上证50", "中证红利", "沪深300",
            "科创50", "中证1000", "中证500", "深证成指", "创业板指", "国证2000");

    /**
     * 核心 Brinson 归因计算
     *
     * @param taskId               回测任务ID
     * @param positionHistoryJson  持仓历史 JSON
     * @param equityCurveJson      净值曲线 JSON
     * @param benchmarkCurveJson   基准曲线 JSON
     * @param benchmarkCode        基准指数代码
     * @return 归因结果
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> computeBrinson(Long taskId,
                                               String positionHistoryJson,
                                               String equityCurveJson,
                                               String benchmarkCurveJson,
                                               String benchmarkCode) {
        if (positionHistoryJson == null || positionHistoryJson.isBlank()) {
            throw new BusinessException("持仓历史数据为空，无法进行归因分析");
        }

        List<Map<String, Object>> positionHistory;
        List<Map<String, Object>> equityCurve;
        List<Map<String, Object>> benchmarkCurve;
        try {
            positionHistory = objectMapper.readValue(positionHistoryJson, List.class);
            equityCurve = objectMapper.readValue(equityCurveJson != null ? equityCurveJson : "[]", List.class);
            benchmarkCurve = objectMapper.readValue(benchmarkCurveJson != null ? benchmarkCurveJson : "[]", List.class);
        } catch (Exception e) {
            throw new BusinessException("持仓历史数据解析失败: " + e.getMessage());
        }

        if (positionHistory.isEmpty()) {
            throw new BusinessException("持仓历史为空，无法进行归因分析");
        }

        // 批量加载所有涉及的股票行业信息
        Set<String> allCodes = new HashSet<>();
        for (Map<String, Object> snap : positionHistory) {
            Map<String, Object> positions = (Map<String, Object>) snap.get("positions");
            if (positions != null) {
                allCodes.addAll(positions.keySet());
            }
        }
        Map<String, String> codeIndustryMap = loadIndustryMap(allCodes);

        // 建立基准日期→净值映射
        Map<String, Double> bmNavMap = new LinkedHashMap<>();
        for (Map<String, Object> bm : benchmarkCurve) {
            bmNavMap.put((String) bm.get("date"), ((Number) bm.get("value")).doubleValue());
        }

        // 建立策略日期→净值映射
        Map<String, Double> stratNavMap = new LinkedHashMap<>();
        for (Map<String, Object> eq : equityCurve) {
            stratNavMap.put((String) eq.get("date"), ((Number) eq.get("value")).doubleValue());
        }

        // 逐期计算归因
        List<Map<String, Object>> periodResults = new ArrayList<>();
        double totalAllocation = 0;
        double totalSelection = 0;
        double totalInteraction = 0;
        double totalExcess = 0;

        for (int i = 0; i < positionHistory.size(); i++) {
            Map<String, Object> snap = positionHistory.get(i);
            String startDate = (String) snap.get("date");
            String endDate = (i + 1 < positionHistory.size())
                    ? (String) positionHistory.get(i + 1).get("date")
                    : null;

            if (endDate == null) {
                // 最后一个持仓期：使用净值曲线的最后日期
                if (!stratNavMap.isEmpty()) {
                    endDate = new ArrayList<>(stratNavMap.keySet()).get(stratNavMap.size() - 1);
                } else {
                    continue;
                }
            }

            LocalDate start = LocalDate.parse(startDate);
            LocalDate end = LocalDate.parse(endDate);

            // 计算该期策略收益率
            Double stratStart = stratNavMap.get(startDate);
            Double stratEnd = findClosestNav(stratNavMap, endDate);
            if (stratStart == null || stratEnd == null || stratStart <= 0) continue;
            double stratReturn = stratEnd / stratStart - 1;

            // 计算该期基准收益率
            Double bmStart = bmNavMap.get(startDate);
            Double bmEnd = findClosestNav(bmNavMap, endDate);
            if (bmStart == null || bmEnd == null || bmStart <= 0) continue;
            double bmReturn = bmEnd / bmStart - 1;

            // 提取持仓股票
            Map<String, Object> positions = (Map<String, Object>) snap.get("positions");
            if (positions == null || positions.isEmpty()) continue;

            // 计算组合行业权重
            Map<String, Double> portfolioIndustryWeight = new HashMap<>();
            for (Map.Entry<String, Object> entry : positions.entrySet()) {
                String symbol = entry.getKey();
                double weight = ((Number) entry.getValue()).doubleValue();
                String industry = codeIndustryMap.get(symbol);
                if (industry == null || industry.isBlank()) industry = "其他";
                portfolioIndustryWeight.merge(industry, weight, Double::sum);
            }

            // 计算全市场基准行业权重和收益率（使用持仓期的中间日期的数据）
            LocalDate midDate = start.plusDays(Math.min(3, java.time.temporal.ChronoUnit.DAYS.between(start, end) / 2));
            Map<String, Double> benchmarkIndustryWeight = new HashMap<>();
            Map<String, Double> benchmarkIndustryReturn = new HashMap<>();

            computeBenchmarkIndustryData(midDate, benchmarkIndustryWeight, benchmarkIndustryReturn);

            if (benchmarkIndustryWeight.isEmpty()) {
                log.warn("无法获取 {} 全市场行业数据，跳过该期归因", midDate);
                continue;
            }

            // 计算组合各行业收益率
            Map<String, Double> portfolioIndustryReturn = new HashMap<>();
            computePortfolioIndustryReturn(positions, start, end, portfolioIndustryReturn, codeIndustryMap);

            // Brinson 归因分解
            double allocation = 0;
            double selection = 0;
            double interaction = 0;

            Set<String> allIndustries = new HashSet<>();
            allIndustries.addAll(portfolioIndustryWeight.keySet());
            allIndustries.addAll(benchmarkIndustryWeight.keySet());

            for (String industry : allIndustries) {
                double wp = portfolioIndustryWeight.getOrDefault(industry, 0.0);
                double wb = benchmarkIndustryWeight.getOrDefault(industry, 0.0);
                double rp = portfolioIndustryReturn.getOrDefault(industry, 0.0);
                double rb = benchmarkIndustryReturn.getOrDefault(industry, 0.0);

                // 配置效应: (wp - wb) * (rb - bmReturn)
                allocation += (wp - wb) * (rb - bmReturn);
                // 选股效应: wb * (rp - rb)
                selection += wb * (rp - rb);
                // 交互效应: (wp - wb) * (rp - rb)
                interaction += (wp - wb) * (rp - rb);
            }

            double excessReturn = stratReturn - bmReturn;

            totalAllocation += allocation;
            totalSelection += selection;
            totalInteraction += interaction;
            totalExcess += excessReturn;

            // 记录该期结果
            Map<String, Object> periodResult = new LinkedHashMap<>();
            periodResult.put("period", startDate + " ~ " + endDate);
            periodResult.put("startDate", startDate);
            periodResult.put("endDate", endDate);
            periodResult.put("portfolioReturn", round4(stratReturn));
            periodResult.put("benchmarkReturn", round4(bmReturn));
            periodResult.put("excessReturn", round4(excessReturn));
            periodResult.put("allocationEffect", round4(allocation));
            periodResult.put("selectionEffect", round4(selection));
            periodResult.put("interactionEffect", round4(interaction));
            periodResult.put("industryCount", allIndustries.size());
            periodResult.put("positionCount", positions.size());

            // 详细行业归因
            List<Map<String, Object>> industryDetails = new ArrayList<>();
            for (String industry : allIndustries) {
                double wp = portfolioIndustryWeight.getOrDefault(industry, 0.0);
                double wb = benchmarkIndustryWeight.getOrDefault(industry, 0.0);
                double rp = portfolioIndustryReturn.getOrDefault(industry, 0.0);
                double rb = benchmarkIndustryReturn.getOrDefault(industry, 0.0);

                if (wp < 1e-8 && wb < 1e-8) continue;

                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("industry", industry);
                detail.put("portfolioWeight", round4(wp));
                detail.put("benchmarkWeight", round4(wb));
                detail.put("portfolioReturn", round4(rp));
                detail.put("benchmarkReturn", round4(rb));
                detail.put("allocation", round4((wp - wb) * (rb - bmReturn)));
                detail.put("selection", round4(wb * (rp - rb)));
                detail.put("interaction", round4((wp - wb) * (rp - rb)));
                detail.put("totalContribution", round4(
                        (wp - wb) * (rb - bmReturn) + wb * (rp - rb) + (wp - wb) * (rp - rb)));
                industryDetails.add(detail);
            }
            // 按总贡献排序
            industryDetails.sort(Comparator.comparingDouble(a -> -((Number) a.get("totalContribution")).doubleValue()));
            periodResult.put("industryDetails", industryDetails);

            periodResults.add(periodResult);
        }

        // 汇总结果
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", taskId);
        result.put("model", "Brinson");
        result.put("benchmarkDescription", "全市场行业基准（流通市值加权）");
        result.put("periodCount", periodResults.size());
        result.put("periods", periodResults);

        // 汇总归因
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalAllocationEffect", round4(totalAllocation));
        summary.put("totalSelectionEffect", round4(totalSelection));
        summary.put("totalInteractionEffect", round4(totalInteraction));
        summary.put("totalExcessReturn", round4(totalExcess));
        // 残差 = 超额收益 - 三项之和（衡量模型解释力）
        double residual = totalExcess - totalAllocation - totalSelection - totalInteraction;
        summary.put("residual", round4(residual));
        summary.put("explanationRatio", totalExcess != 0
                ? round4((totalAllocation + totalSelection + totalInteraction) / totalExcess) : 0);

        result.put("summary", summary);

        // 累计归因曲线（用于前端绘图）
        List<Map<String, Object>> cumulativeChart = buildCumulativeChart(periodResults);
        result.put("cumulativeChart", cumulativeChart);

        // 行业汇总归因（所有期各行业贡献加总）
        List<Map<String, Object>> industrySummary = buildIndustrySummary(periodResults);
        result.put("industrySummary", industrySummary);

        return result;
    }

    /**
     * 加载股票代码→行业映射
     */
    private Map<String, String> loadIndustryMap(Set<String> symbols) {
        // symbols 格式为 "000001.SZ"，需要提取 code 部分
        Set<String> codes = symbols.stream()
                .map(s -> {
                    int dot = s.lastIndexOf('.');
                    return dot > 0 ? s.substring(0, dot) : s;
                })
                .collect(Collectors.toSet());

        Map<String, String> result = new HashMap<>();
        if (codes.isEmpty()) return result;

        // 批量查询
        LambdaQueryWrapper<StockInfo> infoWrapper = new LambdaQueryWrapper<>();
        infoWrapper.in(StockInfo::getCode, codes);
        List<StockInfo> stocks = stockInfoMapper.selectList(infoWrapper);

        // 建立 code → industry 映射
        Map<String, String> codeIndustry = stocks.stream()
                .filter(s -> s.getIndustry() != null && !s.getIndustry().isBlank())
                .collect(Collectors.toMap(StockInfo::getCode, StockInfo::getIndustry, (a, b) -> a));

        // 映射回 symbol
        for (String symbol : symbols) {
            int dot = symbol.lastIndexOf('.');
            String code = dot > 0 ? symbol.substring(0, dot) : symbol;
            result.put(symbol, codeIndustry.getOrDefault(code, null));
        }

        return result;
    }

    /**
     * 计算全市场行业权重和行业收益率（基准）
     * 使用指定日期的行情数据，按流通市值加权
     */
    private void computeBenchmarkIndustryData(LocalDate date,
                                               Map<String, Double> industryWeight,
                                               Map<String, Double> industryReturn) {
        // 查询当日所有股票行情
        LambdaQueryWrapper<StockDaily> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StockDaily::getTradeDate, date)
                .isNotNull(StockDaily::getPreClose)
                .gt(StockDaily::getPreClose, 0)
                .isNotNull(StockDaily::getClosePrice);

        // 排除指数（指数名称列表）
        wrapper.notIn(StockDaily::getName, INDEX_NAMES);

        List<StockDaily> dailies = stockDailyMapper.selectList(wrapper);
        if (dailies.isEmpty()) return;

        // 批量获取行业信息
        Set<String> codes = dailies.stream().map(StockDaily::getCode).collect(Collectors.toSet());
        Map<String, String> codeIndustry = new HashMap<>();

        if (!codes.isEmpty()) {
            LambdaQueryWrapper<StockInfo> industryWrapper = new LambdaQueryWrapper<>();
            industryWrapper.in(StockInfo::getCode, codes)
                    .isNotNull(StockInfo::getIndustry)
                    .ne(StockInfo::getIndustry, "");
            List<StockInfo> infos = stockInfoMapper.selectList(industryWrapper);
            codeIndustry = infos.stream()
                    .collect(Collectors.toMap(StockInfo::getCode, StockInfo::getIndustry, (a, b) -> a));
        }

        // 按行业聚合：总市值和总收益
        Map<String, Double> industryTotalCap = new HashMap<>();
        Map<String, Double> industryTotalReturn = new HashMap<>();
        double totalCap = 0;

        for (StockDaily sd : dailies) {
            String industry = codeIndustry.get(sd.getCode());
            if (industry == null) industry = "其他";

            // 个股日收益率
            double ret = sd.getPreClose() != null && sd.getPreClose().doubleValue() > 0
                    ? sd.getClosePrice().doubleValue() / sd.getPreClose().doubleValue() - 1 : 0;

            // 权重使用流通市值（元）
            double cap = sd.getCircMarketCap() != null ? sd.getCircMarketCap().doubleValue() : 0;
            if (cap <= 0) cap = sd.getMarketCap() != null ? sd.getMarketCap().doubleValue() : 0;
            if (cap <= 0) continue;

            industryTotalCap.merge(industry, cap, Double::sum);
            industryTotalReturn.merge(industry, cap * ret, Double::sum);
            totalCap += cap;
        }

        // 计算权重和收益
        for (Map.Entry<String, Double> entry : industryTotalCap.entrySet()) {
            String industry = entry.getKey();
            double cap = entry.getValue();
            double weight = totalCap > 0 ? cap / totalCap : 0;
            double ret = cap > 0 ? industryTotalReturn.getOrDefault(industry, 0.0) / cap : 0;

            industryWeight.put(industry, round4(weight));
            industryReturn.put(industry, round4(ret));
        }
    }

    /**
     * 计算组合各行业的收益率
     */
    private void computePortfolioIndustryReturn(Map<String, Object> positions,
                                                 LocalDate startDate,
                                                 LocalDate endDate,
                                                 Map<String, Double> industryReturn,
                                                 Map<String, String> codeIndustryMap) {
        // 按行业分组持仓
        Map<String, List<String>> industryStocks = new HashMap<>();
        for (String symbol : positions.keySet()) {
            String industry = codeIndustryMap.get(symbol);
            if (industry == null || industry.isBlank()) industry = "其他";
            industryStocks.computeIfAbsent(industry, k -> new ArrayList<>()).add(symbol);
        }

        for (Map.Entry<String, List<String>> entry : industryStocks.entrySet()) {
            String industry = entry.getKey();
            List<String> stocks = entry.getValue();

            double totalReturn = 0;
            int validCount = 0;

            for (String symbol : stocks) {
                int dot = symbol.lastIndexOf('.');
                String code = dot > 0 ? symbol.substring(0, dot) : symbol;

                // 查询该股票在期初和期末的收盘价
                LambdaQueryWrapper<StockDaily> barWrapper = new LambdaQueryWrapper<>();
                barWrapper.eq(StockDaily::getCode, code)
                        .between(StockDaily::getTradeDate, startDate, endDate)
                        .isNotNull(StockDaily::getClosePrice)
                        .orderByAsc(StockDaily::getTradeDate);
                List<StockDaily> bars = stockDailyMapper.selectList(barWrapper);

                if (bars.size() >= 2) {
                    double startClose = bars.getFirst().getClosePrice().doubleValue();
                    double endClose = bars.getLast().getClosePrice().doubleValue();
                    if (startClose > 0) {
                        totalReturn += endClose / startClose - 1;
                        validCount++;
                    }
                } else if (bars.size() == 1) {
                    // 只有一天数据，用当日涨跌幅
                    StockDaily bar = bars.getFirst();
                    if (bar.getPreClose() != null && bar.getPreClose().doubleValue() > 0) {
                        totalReturn += bar.getClosePrice().doubleValue() / bar.getPreClose().doubleValue() - 1;
                        validCount++;
                    }
                }
            }

            // 行业收益 = 等权平均（因为组合是等权的）
            double avgReturn = validCount > 0 ? totalReturn / validCount : 0;
            industryReturn.put(industry, round4(avgReturn));
        }
    }

    /**
     * 查找最近日期的净值（容错处理）
     */
    private Double findClosestNav(Map<String, Double> navMap, String targetDate) {
        if (navMap.containsKey(targetDate)) return navMap.get(targetDate);

        // 如果当天没有数据，找最近的前一个日期
        Double closest = null;
        for (Map.Entry<String, Double> entry : navMap.entrySet()) {
            if (entry.getKey().compareTo(targetDate) <= 0) {
                closest = entry.getValue();
            }
        }
        return closest;
    }

    /**
     * 构建累计归因曲线（用于前端瀑布图/折线图）
     */
    private List<Map<String, Object>> buildCumulativeChart(List<Map<String, Object>> periods) {
        List<Map<String, Object>> chart = new ArrayList<>();
        double cumAllocation = 0;
        double cumSelection = 0;
        double cumInteraction = 0;
        double cumExcess = 0;

        for (Map<String, Object> period : periods) {
            double alloc = ((Number) period.get("allocationEffect")).doubleValue();
            double select = ((Number) period.get("selectionEffect")).doubleValue();
            double interact = ((Number) period.get("interactionEffect")).doubleValue();
            double excess = ((Number) period.get("excessReturn")).doubleValue();

            cumAllocation += alloc;
            cumSelection += select;
            cumInteraction += interact;
            cumExcess += excess;

            Map<String, Object> point = new LinkedHashMap<>();
            point.put("period", period.get("period"));
            point.put("startDate", period.get("startDate"));
            point.put("endDate", period.get("endDate"));
            point.put("cumAllocation", round4(cumAllocation));
            point.put("cumSelection", round4(cumSelection));
            point.put("cumInteraction", round4(cumInteraction));
            point.put("cumExcess", round4(cumExcess));
            // 当期贡献
            point.put("periodAllocation", round4(alloc));
            point.put("periodSelection", round4(select));
            point.put("periodInteraction", round4(interact));
            point.put("periodExcess", round4(excess));
            chart.add(point);
        }

        return chart;
    }

    /**
     * 构建行业汇总归因（各行业在所有期的贡献加总）
     */
    private List<Map<String, Object>> buildIndustrySummary(List<Map<String, Object>> periods) {
        Map<String, double[]> industryTotals = new LinkedHashMap<>();

        for (Map<String, Object> period : periods) {
            List<Map<String, Object>> details = (List<Map<String, Object>>) period.get("industryDetails");
            if (details == null) continue;

            for (Map<String, Object> detail : details) {
                String industry = (String) detail.get("industry");
                double alloc = ((Number) detail.get("allocation")).doubleValue();
                double select = ((Number) detail.get("selection")).doubleValue();
                double interact = ((Number) detail.get("interaction")).doubleValue();
                double total = ((Number) detail.get("totalContribution")).doubleValue();

                industryTotals.computeIfAbsent(industry, k -> new double[4]);
                double[] arr = industryTotals.get(industry);
                arr[0] += alloc;
                arr[1] += select;
                arr[2] += interact;
                arr[3] += total;
            }
        }

        List<Map<String, Object>> summary = new ArrayList<>();
        for (Map.Entry<String, double[]> entry : industryTotals.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("industry", entry.getKey());
            double[] arr = entry.getValue();
            item.put("totalAllocation", round4(arr[0]));
            item.put("totalSelection", round4(arr[1]));
            item.put("totalInteraction", round4(arr[2]));
            item.put("totalContribution", round4(arr[3]));
            summary.add(item);
        }

        // 按总贡献排序
        summary.sort(Comparator.comparingDouble(a -> -((Number) a.get("totalContribution")).doubleValue()));

        return summary;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseJsonList(String json) {
        try {
            return objectMapper.readValue(json != null ? json : "[]", List.class);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private double round4(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0;
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }
}
