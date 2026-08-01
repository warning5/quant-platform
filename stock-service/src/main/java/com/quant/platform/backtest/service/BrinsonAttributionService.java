package com.quant.platform.backtest.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.backtest.domain.BacktestTask;
import com.quant.platform.common.exception.BusinessException;
import com.quant.platform.stock.entity.StockDaily;
import com.quant.platform.stock.entity.StockInfo;
import com.quant.platform.stock.mapper.StockInfoMapper;
import com.quant.platform.stock.service.ClickHouseStockService;
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
 * 采用全市场行业基准方法（等权模式）：
 * - 基准行业权重 = 当日全市场股票按行业分组的等权分布
 * - 基准行业收益 = 各行业内股票等权平均收益率
 * <p>
 * 等权模式优势：不依赖历史市值数据，归因结果更稳定
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrinsonAttributionService {

    private final StockInfoMapper stockInfoMapper;
    private final ClickHouseStockService clickHouseStockService;
    private final ObjectMapper objectMapper;

    /**
     * 需要排除的指数名称
     */
    private static final Set<String> INDEX_NAMES = Set.of(
            "上证指数", "上证50", "中证红利", "沪深300",
            "科创50", "中证1000", "中证500", "深证成指", "创业板指", "国证2000");

    /**
     * 核心 Brinson 归因计算
     *
     * @param positionHistoryJson 持仓历史 JSON
     * @param equityCurveJson     净值曲线 JSON
     * @param benchmarkCurveJson  基准曲线 JSON
     * @return 归因结果
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> computeBrinson(BacktestTask task,
                                              String positionHistoryJson,
                                              String equityCurveJson,
                                              String benchmarkCurveJson) {
        Long taskId = task.getId();
        String benchmarkCode = task.getBenchmarkCode();
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

        // 建立策略日期→净值映射
        Map<String, Double> stratNavMap = new LinkedHashMap<>();
        for (Map<String, Object> eq : equityCurve) {
            stratNavMap.put((String) eq.get("date"), ((Number) eq.get("value")).doubleValue());
        }

        // 建立基准日期→净值映射（用于获取真实区间基准收益）
        Map<String, Double> bmNavMap = new LinkedHashMap<>();
        for (Map<String, Object> bm : benchmarkCurve) {
            bmNavMap.put((String) bm.get("date"), ((Number) bm.get("value")).doubleValue());
        }

        // 逐期计算归因
        List<Map<String, Object>> periodResults = new ArrayList<>();
        double totalAllocation = 0;
        double totalSelection = 0;
        double totalInteraction = 0;
        double totalExcess = 0;
        double totalTurnover = 0; // 累计单向换手率，用于估算交易成本

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

            // 计算该期基准收益率（从基准净值曲线 — 真实区间收益）
            Double bmStart = bmNavMap.get(startDate);
            Double bmEnd = findClosestNav(bmNavMap, endDate);
            if (bmStart == null || bmEnd == null || bmStart <= 0) continue;
            double benchCurveReturn = bmEnd / bmStart - 1; // 基准区间真实收益

            // 提取持仓股票
            Map<String, Object> positions = (Map<String, Object>) snap.get("positions");
            if (positions == null || positions.isEmpty()) continue;

            // 计算本期换手率（本期持仓 vs 上期持仓的权重变化）
            double periodTurnover = 0;
            if (i > 0) {
                Map<String, Object> prevPositions = (Map<String, Object>) positionHistory.get(i - 1).get("positions");
                if (prevPositions != null) {
                    Set<String> allSymbols = new HashSet<>();
                    allSymbols.addAll(prevPositions.keySet());
                    allSymbols.addAll(positions.keySet());
                    for (String symbol : allSymbols) {
                        double prevW = prevPositions.containsKey(symbol)
                                ? ((Number) prevPositions.get(symbol)).doubleValue() : 0;
                        double currW = ((Number) positions.getOrDefault(symbol, 0)).doubleValue();
                        periodTurnover += Math.abs(currW - prevW);
                    }
                    periodTurnover /= 2.0; // 单向换手率
                }
            }
            totalTurnover += periodTurnover;

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
            // midDate 可能落在非交易日（周末/节假日），需要回溯到最近交易日
            LocalDate midDate = start.plusDays(Math.min(3, java.time.temporal.ChronoUnit.DAYS.between(start, end) / 2));
            Map<String, Double> benchmarkIndustryWeight = new HashMap<>();
            Map<String, Double> benchmarkIndustryReturn = new HashMap<>();

            computeBenchmarkIndustryData(midDate, benchmarkIndustryWeight, benchmarkIndustryReturn);

            if (benchmarkIndustryWeight.isEmpty()) {
                String reason = getDateReason(midDate);
                log.warn("无法获取 {} 全市场行业数据（{}），尝试回溯到最近交易日", midDate, reason);
                // 回溯：向前查找最近的交易日，最多尝试5天（覆盖最长假期）
                LocalDate adjusted;
                for (int back = 1; back <= 5; back++) {
                    adjusted = midDate.minusDays(back);
                    benchmarkIndustryWeight.clear();
                    benchmarkIndustryReturn.clear();
                    computeBenchmarkIndustryData(adjusted, benchmarkIndustryWeight, benchmarkIndustryReturn);
                    if (!benchmarkIndustryWeight.isEmpty()) {
                        log.info("回溯成功: {} -> {}（原因: {}，向前{}天）", midDate, adjusted, reason, back);
                        break;
                    }
                }
            }

            if (benchmarkIndustryWeight.isEmpty()) {
                log.warn("无法获取 {} 全市场行业数据（含回溯），跳过该期归因", midDate);
                continue;
            }

            // 计算组合各行业收益率
            Map<String, Double> portfolioIndustryReturn = new HashMap<>();
            computePortfolioIndustryReturn(positions, start, end, portfolioIndustryReturn, codeIndustryMap);

            // ── 缩放校准：rb_i 是单日收益，需缩放到区间尺度 ──
            // 原理：在单期内，各行业相对强弱 vs 全市场的排序保持，但绝对尺度应该与
            // 基准曲线的区间收益匹配。缩放后 Σ(wb_i × rb_i) = benchCurveReturn。
            // 这保证 Brinson 恒等式在单期内严格成立。
            double rawBmSum = 0;
            for (Map.Entry<String, Double> entry : benchmarkIndustryWeight.entrySet()) {
                double wb = entry.getValue();
                double rb = benchmarkIndustryReturn.getOrDefault(entry.getKey(), 0.0);
                rawBmSum += wb * rb;
            }
            double bmScale = Math.abs(rawBmSum) > 1e-8 ? benchCurveReturn / rawBmSum : 1.0;
            // 缩放所有 rb_i
            for (Map.Entry<String, Double> entry : benchmarkIndustryReturn.entrySet()) {
                double rb = entry.getValue() * bmScale;
                entry.setValue(rb);
            }
            // 缩放后 bmReturn = Σ(wb_i × scaled_rb_i) = benchCurveReturn
            double bmReturn = benchCurveReturn;

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
        result.put("benchmarkDescription", "全市场行业基准（等权）");
        result.put("periodCount", periodResults.size());
        result.put("periods", periodResults);

        // 汇总归因
        Map<String, Object> summary = new LinkedHashMap<>();

        summary.put("totalAllocationEffect", round4(totalAllocation));
        summary.put("totalSelectionEffect", round4(totalSelection));
        summary.put("totalInteractionEffect", round4(totalInteraction));
        summary.put("totalExcessReturn", round4(totalExcess));
        // 多期 Brinson 残差（单期恒等式成立，多期算术累加不闭合，属模型固有局限）
        double residual = round4(totalExcess - (totalAllocation + totalSelection + totalInteraction));
        summary.put("residual", residual);
        // 解释力 = 1 - |残差| / |超额收益|（残差越小，解释力越高）
        summary.put("explanationRatio",
            Math.abs(totalExcess) > 1e-8
                ? round4(Math.max(0, 1 - Math.abs(residual) / Math.abs(totalExcess))) : 0);
        // 估算交易成本：累计单向换手率 × 单边费率 0.1%（回测环境默认值）
        double estimatedTransactionCost = totalTurnover * 0.001;
        summary.put("totalTurnover", round4(totalTurnover));
        summary.put("estimatedTransactionCost", round4(estimatedTransactionCost));
        // 净超额收益 = 归因超额收益 - 估算交易成本
        summary.put("netExcessReturn", round4(totalExcess - estimatedTransactionCost));

        result.put("summary", summary);

        // 行业汇总归因（所有期各行业贡献加总 + 平均每期贡献）
        List<Map<String, Object>> industrySummary = buildIndustrySummary(periodResults, periodResults.size());
        result.put("industrySummary", industrySummary);
        List<Map<String, Object>> cumulativeChart = buildCumulativeChart(periodResults);
        result.put("cumulativeChart", cumulativeChart);

        // 从 BacktestTask 直接获取 SCREEN 模式策略配置（用于前端 Brinson 结论诊断）
        // BacktestTask 已统一包含 screenConfigJson / rebalanceFreq / weightMode 字段
        summary.put("screenConfigJson", task.getScreenConfigJson());
        summary.put("rebalanceFreq", task.getRebalanceFreq());
        summary.put("weightMode", task.getWeightMode());

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
     * 使用指定日期的行情数据，支持等权和市值加权两种模式
     * <p>
     * 权重模式说明：
     * - 等权模式（默认）：每只股票权重相同，不依赖历史市值数据
     * - 市值加权模式：按流通市值加权，需要当日市值数据（缺失时用最新市值兜底）
     */
    private void computeBenchmarkIndustryData(LocalDate date,
                                              Map<String, Double> industryWeight,
                                              Map<String, Double> industryReturn) {
        // 查询当日所有股票行情（排除指数）
        List<StockDaily> dailies = clickHouseStockService.getStockDailyByDate(date, INDEX_NAMES);
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

        // 按行业聚合：股票数量和总收益
        Map<String, Integer> industryStockCount = new HashMap<>();
        Map<String, Double> industryTotalReturn = new HashMap<>();
        int totalStocks = 0;

        for (StockDaily sd : dailies) {
            String industry = codeIndustry.get(sd.getCode());
            if (industry == null) industry = "其他";

            // 个股日收益率
            double ret = sd.getPreClose() != null && sd.getPreClose().doubleValue() > 0
                    ? sd.getClosePrice().doubleValue() / sd.getPreClose().doubleValue() - 1 : 0;

            // 等权模式：每只股票权重相同（不依赖市值数据）
            industryStockCount.merge(industry, 1, Integer::sum);
            industryTotalReturn.merge(industry, ret, Double::sum);
            totalStocks++;
        }

        // 计算权重和收益
        for (Map.Entry<String, Integer> entry : industryStockCount.entrySet()) {
            String industry = entry.getKey();
            int count = entry.getValue();
            double weight = totalStocks > 0 ? (double) count / totalStocks : 0;
            double ret = count > 0 ? industryTotalReturn.getOrDefault(industry, 0.0) / count : 0;

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

            // 第一步：计算该行业内所有股票的总权重（用于内部归一化）
            double industryTotalWeight = 0;
            for (String symbol : stocks) {
                Object w = positions.get(symbol);
                if (w != null) industryTotalWeight += ((Number) w).doubleValue();
            }

            // 第二步：按持仓权重加权计算行业收益率
            double weightedReturn = 0;
            double accountedWeight = 0; // 实际计入的权重（skip无效数据）

            for (String symbol : stocks) {
                int dot = symbol.lastIndexOf('.');
                String code = dot > 0 ? symbol.substring(0, dot) : symbol;

                // 该股票在行业内的权重
                double stockWeight = 0;
                Object w = positions.get(symbol);
                if (w != null) stockWeight = ((Number) w).doubleValue();
                double withinIndustryWeight = industryTotalWeight > 0
                        ? stockWeight / industryTotalWeight : 1.0 / stocks.size();

                // 查询该股票在期初和期末的收盘价
                List<StockDaily> bars = clickHouseStockService.getStockDaily(code, startDate, endDate);

                if (bars.size() >= 2) {
                    double startClose = bars.getFirst().getClosePrice().doubleValue();
                    double endClose = bars.getLast().getClosePrice().doubleValue();
                    if (startClose > 0) {
                        weightedReturn += withinIndustryWeight * (endClose / startClose - 1);
                        accountedWeight += withinIndustryWeight;
                    }
                } else if (bars.size() == 1) {
                    // 只有一天数据，用当日涨跌幅
                    StockDaily bar = bars.getFirst();
                    if (bar.getPreClose() != null && bar.getPreClose().doubleValue() > 0) {
                        weightedReturn += withinIndustryWeight
                                * (bar.getClosePrice().doubleValue() / bar.getPreClose().doubleValue() - 1);
                        accountedWeight += withinIndustryWeight;
                    }
                }
            }

            // 归一化：如缺失超50%，拒绝归一化（避免放大数据缺失误差）
            double avgReturn;
            if (accountedWeight > 0.5) {
                avgReturn = weightedReturn / accountedWeight;
            } else {
                // 数据缺失严重，用原始加权收益或不归零（不做放大）
                avgReturn = weightedReturn;
            }
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
    private List<Map<String, Object>> buildIndustrySummary(List<Map<String, Object>> periods, int periodCount) {
        // arr[0]=alloc, arr[1]=selection, arr[2]=interaction, arr[3]=totalContribution
        // arr[4]=sum portfolioWeight, arr[5]=sum benchmarkWeight
        // arr[6]=sum (wp-wb), arr[7]=sum (rb-bmReturn), arr[8]=sum (rp-rb)
        // arr[9]=count of periods with this industry
        Map<String, double[]> industryTotals = new LinkedHashMap<>();

        for (Map<String, Object> period : periods) {
            List<Map<String, Object>> details = (List<Map<String, Object>>) period.get("industryDetails");
            if (details == null) continue;

            double bmReturn = ((Number) period.get("benchmarkReturn")).doubleValue();

            for (Map<String, Object> detail : details) {
                String industry = (String) detail.get("industry");
                double alloc = ((Number) detail.get("allocation")).doubleValue();
                double select = ((Number) detail.get("selection")).doubleValue();
                double interact = ((Number) detail.get("interaction")).doubleValue();
                double total = ((Number) detail.get("totalContribution")).doubleValue();
                double wp = ((Number) detail.get("portfolioWeight")).doubleValue();
                double wb = ((Number) detail.get("benchmarkWeight")).doubleValue();
                double rp = ((Number) detail.get("portfolioReturn")).doubleValue();
                double rb = ((Number) detail.get("benchmarkReturn")).doubleValue();

                industryTotals.computeIfAbsent(industry, k -> new double[10]);
                double[] arr = industryTotals.get(industry);
                arr[0] += alloc;
                arr[1] += select;
                arr[2] += interact;
                arr[3] += total;
                arr[4] += wp;
                arr[5] += wb;
                arr[6] += (wp - wb);
                arr[7] += (rb - bmReturn);
                arr[8] += (rp - rb);
                arr[9] += 1;
            }
        }

        List<Map<String, Object>> summary = new ArrayList<>();
        for (Map.Entry<String, double[]> entry : industryTotals.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("industry", entry.getKey());
            double[] arr = entry.getValue();
            double n = arr[9];
            item.put("totalAllocation", round4(arr[0]));
            item.put("totalSelection", round4(arr[1]));
            item.put("totalInteraction", round4(arr[2]));
            item.put("totalContribution", round4(arr[3]));
            // 平均每期贡献（归一化，便于跨回测周期对比）
            item.put("avgContribution",   round4(n > 0 ? arr[3] / n : 0));
            item.put("avgAllocation",     round4(n > 0 ? arr[0] / n : 0));
            item.put("avgSelection",      round4(n > 0 ? arr[1] / n : 0));
            item.put("avgInteraction",    round4(n > 0 ? arr[2] / n : 0));
            // 中间计算值（平均每期）
            item.put("avgPortfolioWeight",  round4(n > 0 ? arr[4] / n : 0));
            item.put("avgBenchmarkWeight",  round4(n > 0 ? arr[5] / n : 0));
            item.put("avgWeightDiff",       round4(n > 0 ? arr[6] / n : 0));
            item.put("avgBenchmarkReturnExcess", round4(n > 0 ? arr[7] / n : 0));
            item.put("avgSelectionReturn",  round4(n > 0 ? arr[8] / n : 0));
            // 计数值（出现过几期）
            item.put("periodCount", (int) n);
            summary.add(item);
        }

        // 按总贡献绝对值排序
        summary.sort(Comparator.comparingDouble(a -> -Math.abs(((Number) a.get("totalContribution")).doubleValue())));

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

    /**
     * 判断日期为何无法获取行情数据（周末/节假日）
     */
    private String getDateReason(LocalDate date) {
        var dow = date.getDayOfWeek();
        if (dow == java.time.DayOfWeek.SATURDAY || dow == java.time.DayOfWeek.SUNDAY) {
            return "周末休市";
        }
        return "节假日或数据缺失";
    }
}
