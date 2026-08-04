package com.quant.platform.recommendation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.factor.engine.PatternDetector;
import com.quant.platform.screen.dto.ScreenRequest;
import com.quant.platform.screen.dto.ScreenResult;
import com.quant.platform.screen.service.StockScreenService;
import com.quant.platform.stock.analysis.service.AnalysisService;
import com.quant.platform.stock.entity.StockInfo;
import com.quant.platform.stock.mapper.StockInfoMapper;
import com.quant.platform.strategy.domain.StrategyDefinition;
import com.quant.platform.strategy.mapper.StrategyDefinitionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 候选池筛选器：多因子选股 + 形态驱动选股。
 * <p>
 * 从 {@link RecommendationService} 抽取（God Class 拆分 Phase 3），方法体逐字迁移。
 * 唯一的结构性调整：原 {@code screenStocks} 内联调用的
 * {@code getFactorConfig(...)} / {@code applyDynamicFactorWeights(...)}（仍属 Phase 4 的
 * FactorWeightResolver 职责，暂留在 RecommendationService）改由调用方以
 * {@link Supplier} 形式传入。Supplier 只在**非 PATTERN 分支**才被求值，
 * 与原实现的短路顺序完全一致，因此 PATTERN 策略不会多做一次因子解析。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CandidateScreener {

    /**
     * 因子选股取 Top N（广筛）
     */
    private static final int SCREEN_TOP_N = 50;

    private final StockScreenService stockScreenService;
    private final StrategyDefinitionMapper strategyDefinitionMapper;
    private final StockInfoMapper stockInfoMapper;
    private final AnalysisService analysisService;
    private final ObjectMapper objectMapper;

    /**
     * 多因子选股（支持高级选项覆盖）
     *
     * @param strategyId          策略ID（必须）
     * @param effectiveWeightMode 生效的权重模式（EQW/STATIC → EQUAL，其余 → IC）
     * @param advancedOptions     高级选项（中性化/正交化/极值/标准化/均线），null 则使用默认
     * @param factorSupplier      因子配置提供者（含动态IC权重调整），仅非 PATTERN 分支才求值
     */
    ScreenResult screenStocks(LocalDate date, Long strategyId,
                              String effectiveWeightMode,
                              RecommendationService.AdvancedScreenOptions advancedOptions,
                              Supplier<List<ScreenRequest.FactorWeight>> factorSupplier) {
        // 检查是否为形态驱动策略
        StrategyDefinition strategy = strategyDefinitionMapper.selectById(strategyId);
        if (strategy != null && strategy.getStrategyType() == StrategyDefinition.StrategyType.PATTERN) {
            return screenByPattern(date, strategyId);
        }

        // 从策略因子配置获取因子列表 + 动态调整因子权重（基于IC），同时收集诊断信息
        List<ScreenRequest.FactorWeight> factors = factorSupplier.get();

        ScreenRequest req = new ScreenRequest();
        req.setScreenDate(date);
        req.setFactors(factors);
        req.setStrategyId(strategyId);
        req.setTopN(SCREEN_TOP_N);
        req.setDirection("LONG");
        req.setExcludeSt(true);
        // 智能推荐使用IC加权或等权
        String screenWeightMode = switch (effectiveWeightMode) {
            case "EQW", "STATIC" -> "EQUAL";
            default -> "IC";
        };
        req.setWeightMode(screenWeightMode);

        // 高级选项覆盖（默认行为不变）
        if (advancedOptions != null) {
            if (advancedOptions.getNeutralizationMethod() != null) {
                req.setNeutralizationMethod(advancedOptions.getNeutralizationMethod());
            }
            if (advancedOptions.getOrthogonalizationMethod() != null) {
                req.setOrthogonalizationMethod(advancedOptions.getOrthogonalizationMethod());
            }
            if (advancedOptions.getGlobalOutlierMethod() != null) {
                req.setGlobalOutlierMethod(advancedOptions.getGlobalOutlierMethod());
            }
            if (advancedOptions.getGlobalNormalizeMethod() != null) {
                req.setGlobalNormalizeMethod(advancedOptions.getGlobalNormalizeMethod());
            }
            if (advancedOptions.getMaPositionFilter() != null) {
                req.setMaPositionFilter(advancedOptions.getMaPositionFilter());
            }
            log.info("[Recommendation] advanced options applied: neutralization={}, orthogonal={}, outlier={}, normalize={}, maFilter={}",
                    advancedOptions.getNeutralizationMethod(), advancedOptions.getOrthogonalizationMethod(),
                    advancedOptions.getGlobalOutlierMethod(), advancedOptions.getGlobalNormalizeMethod(),
                    advancedOptions.getMaPositionFilter() != null);
        }
        return stockScreenService.screen(req);
    }

    /**
     * 形态驱动选股：使用 PatternDetector 检测全市场股票形态
     * 从 filterConfigJson 读取 patternType（可选，不指定则检测全部形态）
     */
    private ScreenResult screenByPattern(LocalDate date, Long strategyId) {
        log.info("[Recommendation] 形态驱动选股开始, strategyId={}", strategyId);
        StrategyDefinition strategy = strategyDefinitionMapper.selectById(strategyId);

        // 解析 filterConfigJson 获取形态类型和股票池
        String patternTypeFilter = null;
        if (strategy.getFilterConfigJson() != null) {
            try {
                Map<String, Object> filter = objectMapper.readValue(strategy.getFilterConfigJson(), Map.class);
                patternTypeFilter = (String) filter.get("patternType");
            } catch (Exception e) {
                log.warn("[Recommendation] 解析filterConfigJson失败: {}", e.getMessage());
            }
        }

        // 获取候选股票池（排除ST、退市）
        List<StockInfo> allStocks = stockInfoMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StockInfo>()
                        .isNull(StockInfo::getDelistDate)
                        .eq(StockInfo::getIsSt, 0));
        log.info("[Recommendation] 形态选股候选池: {} 只", allStocks.size());

        // 批量获取全市场K线数据（一次ClickHouse查询）
        Map<String, double[][]> klineMap = analysisService.batchFetchKlineData(120);
        log.info("[Recommendation] 批量K线数据: {} 只股票", klineMap.size());

        List<ScreenResult.StockScore> results = new ArrayList<>();
        for (StockInfo stock : allStocks) {
            try {
                double[][] ohlcv = klineMap.get(stock.getCode());
                if (ohlcv == null || ohlcv[3].length < 30) continue;

                    PatternDetector.PatternResult strongest = PatternDetector.getStrongestPattern(
                            ohlcv[1], ohlcv[2], ohlcv[0], ohlcv[3], ohlcv[4]);
                    if (strongest == null) continue;

                    // 如果指定了形态类型，只保留匹配的
                    if (patternTypeFilter != null && !patternTypeFilter.isEmpty()
                            && !strongest.getPatternType().name().equals(patternTypeFilter)) continue;

                    Map<String, Double> patternInfo = new HashMap<>();
                    patternInfo.put(strongest.getPatternType().name(), strongest.getScore());
                    ScreenResult.StockScore ss = ScreenResult.StockScore.builder()
                            .symbol(stock.getCode())
                            .name(stock.getName())
                            .compositeScore(strongest.getScore() / 100.0)
                            .factorValues(patternInfo)
                            .build();
                    results.add(ss);
            } catch (Exception e) {
                // 单只股票失败跳过
            }
        }
        log.info("[Recommendation] 形态选股扫描完成: 命中 {} 只", results.size());

        // 按形态得分排序取 TopN
        results.sort((a, b) -> Double.compare(b.getCompositeScore(), a.getCompositeScore()));
        int topN = Math.min(SCREEN_TOP_N, results.size());
        results = results.subList(0, topN);

        ScreenResult result = ScreenResult.builder()
                .screenDate(date != null ? date : LocalDate.now())
                .stocks(results)
                .candidateCount(results.size())
                .build();
        log.info("[Recommendation] 形态选股完成: 命中 {} 只, 取 Top {}", results.size(), topN);
        return result;
    }
}
