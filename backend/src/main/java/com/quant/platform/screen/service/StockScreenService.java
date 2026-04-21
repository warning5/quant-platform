package com.quant.platform.screen.service;

import com.quant.platform.factor.domain.FactorDefinition;
import com.quant.platform.factor.domain.FactorValue;
import com.quant.platform.factor.mapper.FactorDefinitionMapper;
import com.quant.platform.factor.mapper.FactorValueMapper;
import com.quant.platform.market.domain.MarketDailyBar;
import com.quant.platform.market.service.MarketDataService;
import com.quant.platform.screen.dto.ScreenRequest;
import com.quant.platform.screen.dto.ScreenResult;
import com.quant.platform.screen.entity.ScreenPreset;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 多因子选股服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockScreenService {

    private final FactorValueMapper factorValueMapper;
    private final FactorDefinitionMapper factorDefMapper;
    private final MarketDataService marketDataService;
    private final PriceAdvisorService priceAdvisorService;
    private final ScreenPresetService presetService;
    private final ObjectMapper objectMapper;

    /**
     * 执行多因子选股
     */
    public ScreenResult screen(ScreenRequest req) {
        // ── 0. 加载预设组合（如果指定了 presetId）────────────────────
        if (req.getPresetId() != null && (req.getFactors() == null || req.getFactors().isEmpty())) {
            ScreenPreset preset = presetService.getById(req.getPresetId());
            if (preset != null) {
                try {
                    List<Map<String, Object>> config = presetService.parseFactorConfig(preset.getFactorConfig());
                    List<ScreenRequest.FactorWeight> factors = config.stream().map(m -> {
                        ScreenRequest.FactorWeight fw = new ScreenRequest.FactorWeight();
                        fw.setFactorCode((String) m.get("factorCode"));
                        fw.setDirection(m.get("direction") instanceof Number ? ((Number) m.get("direction")).intValue() : 1);
                        fw.setWeight(m.get("weight") instanceof Number ? ((Number) m.get("weight")).doubleValue() : 1.0);
                        fw.setFilterOp(m.get("filterOp") != null ? (String) m.get("filterOp") : "NONE");
                        fw.setFilterValue(m.get("filterValue") instanceof Number ? ((Number) m.get("filterValue")).doubleValue() : null);
                        return fw;
                    }).collect(Collectors.toList());
                    req.setFactors(factors);
                    log.info("Loaded preset [{}] with {} factors", preset.getPresetName(), factors.size());
                } catch (Exception e) {
                    log.warn("Failed to load preset {}: {}", req.getPresetId(), e.getMessage());
                }
            }
        }

        // ── 1. 确定选股日期 ─────────────────────────────────────────
        LocalDate screenDate = req.getScreenDate();
        if (screenDate == null) {
            screenDate = resolveLatestDate(req.getFactors());
        }
        log.info("Running stock screen on date={}, factors={}, topN={}",
                screenDate, req.getFactors().size(), req.getTopN());

        // ── 2. 加载当日行情（用于股票名称、过滤ST）─────────────────
        List<MarketDailyBar> bars = marketDataService.getBarsAtDate(screenDate);
        log.info("[Screen] screenDate={}, bars count={}", screenDate, bars.size());
        if (bars.isEmpty()) {
            // 往前找最近5个交易日
            for (int i = 1; i <= 5; i++) {
                screenDate = screenDate.minusDays(1);
                bars = marketDataService.getBarsAtDate(screenDate);
                if (!bars.isEmpty()) break;
            }
        }

        // 建立 symbol -> bar 映射
        Map<String, MarketDailyBar> barMap = bars.stream()
                .collect(Collectors.toMap(MarketDailyBar::getSymbol, b -> b, (a, b) -> a));

        // 候选股票池（若 excludeSt，则剔除名称含"ST"的）
        Set<String> candidates = barMap.keySet().stream()
                .filter(sym -> {
                    if (Boolean.TRUE.equals(req.getExcludeSt())) {
                        MarketDailyBar b = barMap.get(sym);
                        String name = b.getName() != null ? b.getName().toUpperCase() : "";
                        return !name.contains("ST");
                    }
                    return true;
                })
                .collect(Collectors.toSet());

        log.info("Candidate stocks after ST filter: {}", candidates.size());

        // ── 3. 加载各因子的截面数据，并进行极值处理、标准化 ────────────
        Map<String, Map<String, FactorValue>> factorData = new LinkedHashMap<>();
        Map<String, Integer> coverage = new LinkedHashMap<>();

        for (ScreenRequest.FactorWeight fw : req.getFactors()) {
            String code = fw.getFactorCode();

            // 尝试当天，不够就向前最多5日
            List<FactorValue> crossSection = Collections.emptyList();
            LocalDate searchDate = screenDate;
            for (int i = 0; i <= 5; i++) {
                crossSection = factorValueMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FactorValue>()
                        .eq(FactorValue::getFactorCode, code)
                        .eq(FactorValue::getCalcDate, searchDate)
                        .orderByAsc(FactorValue::getSymbol));
                if (!crossSection.isEmpty()) break;
                searchDate = searchDate.minusDays(1);
            }

            // 过滤候选股票，并提取原始值
            List<FactorValue> filtered = crossSection.stream()
                    .filter(fv -> candidates.contains(fv.getSymbol()))
                    .collect(Collectors.toList());

            // 极值处理
            String outlierMethod = fw.getOutlierMethod() != null && !fw.getOutlierMethod().isEmpty()
                    ? fw.getOutlierMethod() : req.getGlobalOutlierMethod();
            List<Double> outlierProcessed = applyOutlierProcessing(
                    filtered.stream()
                            .map(FactorValue::getFactorVal)
                            .map(bd -> bd != null ? bd.doubleValue() : 0.0)
                            .collect(Collectors.toList()),
                    outlierMethod
            );

            // 标准化处理
            String normalizeMethod = fw.getNormalizeMethod() != null && !fw.getNormalizeMethod().isEmpty()
                    ? fw.getNormalizeMethod() : req.getGlobalNormalizeMethod();
            List<Double> normalized = applyNormalization(outlierProcessed, normalizeMethod);

            // 重新组装 FactorValue（用标准化后的值替换 rankValue）
            Map<String, FactorValue> symbolMap = new LinkedHashMap<>();
            for (int i = 0; i < filtered.size(); i++) {
                FactorValue orig = filtered.get(i);
                FactorValue processed = new FactorValue();
                processed.setSymbol(orig.getSymbol());
                processed.setFactorCode(orig.getFactorCode());
                processed.setCalcDate(orig.getCalcDate());
                processed.setFactorVal(orig.getFactorVal());
                // 用标准化后的值作为 rankValue 参与后续计算
                processed.setRankValue(BigDecimal.valueOf(normalized.get(i)));
                symbolMap.put(orig.getSymbol(), processed);
            }

            factorData.put(code, symbolMap);
            coverage.put(code, symbolMap.size());
            log.info("[Screen] Factor {} coverage: {} stocks on {}, outlier={}, normalize={}",
                    code, symbolMap.size(), searchDate, outlierMethod, normalizeMethod);
        }

        // 调试：打印候选股票数和各因子覆盖情况
        log.info("[Screen] Candidates: {}, FactorData keys: {}", candidates.size(), factorData.keySet());

        // ── 3.5 因子正交化（可选，消除多因子间共线性）───────────────
        String orthoMethod = req.getOrthogonalizationMethod();
        if (orthoMethod != null && !"NONE".equalsIgnoreCase(orthoMethod) && factorData.size() > 1) {
            applyOrthogonalization(factorData, orthoMethod);
        }

        // ── 4. 计算综合得分 ──────────────────────────────────────────
        // 权重归一化（绝对值之和 = 1）
        double totalAbsWeight = req.getFactors().stream()
                .mapToDouble(fw -> Math.abs(fw.getWeight())).sum();
        if (totalAbsWeight == 0) totalAbsWeight = 1.0;

        // 只选在所有因子中都有值的股票（或放宽到至少有 50% 因子有值）
        int minFactors = Math.max(1, (int) Math.ceil(req.getFactors().size() * 0.5));

        List<ScreenResult.StockScore> scores = new ArrayList<>();
        for (String sym : candidates) {
            Map<String, Double> rankMap = new LinkedHashMap<>();
            Map<String, Double> valueMap = new LinkedHashMap<>();
            int validCount = 0;
            double compositeScore = 0.0;

            for (ScreenRequest.FactorWeight fw : req.getFactors()) {
                FactorValue fv = factorData.get(fw.getFactorCode()).get(sym);
                if (fv != null) {
                    double normalized = fv.getRankValue() != null ? fv.getRankValue().doubleValue() : 0.5;
                    double raw = fv.getFactorVal() != null ? fv.getFactorVal().doubleValue() : 0.0;

                    // 筛选条件过滤
                    if (!passFilter(raw, fw.getFilterOp(), fw.getFilterValue())) {
                        validCount = -9999; // 标记为不满足筛选条件
                        break;
                    }

                    rankMap.put(fw.getFactorCode(), normalized);
                    valueMap.put(fw.getFactorCode(), raw);

                    // 权重 * 方向 * 标准化值
                    double normalizedWeight = fw.getWeight() / totalAbsWeight;
                    // direction: 1=正向（越高越好），-1=反向（越低越好）
                    double factorScore = fw.getDirection() >= 0 ? normalized : (1.0 - normalized);
                    compositeScore += normalizedWeight * factorScore;
                    validCount++;
                }
            }

            if (validCount < minFactors) continue;

            MarketDailyBar bar = barMap.get(sym);
            scores.add(ScreenResult.StockScore.builder()
                    .symbol(sym)
                    .name(bar != null ? bar.getName() : sym)
                    .compositeScore(compositeScore)
                    .factorRanks(rankMap)
                    .factorValues(valueMap)
                    .build());
        }

        // ── 5. 排序 & 取 TopN ────────────────────────────────────────
        boolean isLong = !"SHORT".equalsIgnoreCase(req.getDirection());
        scores.sort((a, b) -> isLong
                ? Double.compare(b.getCompositeScore(), a.getCompositeScore())
                : Double.compare(a.getCompositeScore(), b.getCompositeScore()));

        int topN = req.getTopN() != null ? req.getTopN() : 30;
        List<ScreenResult.StockScore> topStocks = scores.stream().limit(topN).collect(Collectors.toList());
        log.info("[Screen] Scores total: {}, topStocks: {}", scores.size(), topStocks.size());

        // 填充排名
        for (int i = 0; i < topStocks.size(); i++) {
            topStocks.get(i).setRank(i + 1);
        }

        log.info("Screen done: {} stocks scored, top {} selected", scores.size(), topStocks.size());

        // ── 6. 为 TopN 股票计算买入价建议 ──────────────────────────
        double valuationWeight = req.getValuationWeight() != null ? req.getValuationWeight() : 0.4;
        List<String> topSymbols = topStocks.stream().map(ScreenResult.StockScore::getSymbol).toList();
        if (!topSymbols.isEmpty()) {
            try {
                Map<String, Map<String, Object>> advices = priceAdvisorService.batchAdvise(
                        topSymbols, screenDate, valuationWeight);
                for (ScreenResult.StockScore stock : topStocks) {
                    Map<String, Object> advice = advices.get(stock.getSymbol());
                    if (advice != null) {
                        stock.setCurrentPrice(toBD(advice.get("currentPrice")));
                        stock.setSuggestPrice(toBD(advice.get("suggestPrice")));
                        stock.setSuggestPriceLow(toBD(advice.get("suggestPriceLow")));
                        stock.setSuggestPriceHigh(toBD(advice.get("suggestPriceHigh")));
                        stock.setStopLoss(toBD(advice.get("stopLoss")));
                        stock.setStopLossPercent(toBD(advice.get("stopLossPercent")));
                        stock.setTakeProfit1(toBD(advice.get("takeProfit1")));
                        stock.setTakeProfit1Percent(toBD(advice.get("takeProfit1Percent")));
                        stock.setTakeProfit2(toBD(advice.get("takeProfit2")));
                        stock.setTakeProfit2Percent(toBD(advice.get("takeProfit2Percent")));
                        stock.setAtr(toBD(advice.get("atr")));
                        stock.setRiskLevel((String) advice.get("riskLevel"));
                        stock.setRisks((List<String>) advice.get("risks"));
                        stock.setBuyReason((String) advice.get("buyReason"));
                        stock.setTechLevels((Map<String, Object>) advice.get("techLevels"));
                        stock.setValuationLevels((Map<String, Object>) advice.get("valuationLevels"));
                    }
                }
                log.info("Price advice computed for {} stocks", advices.size());
            } catch (Exception e) {
                log.warn("Failed to compute price advice: {}", e.getMessage());
            }
        }

        return ScreenResult.builder()
                .screenDate(screenDate)
                .factors(req.getFactors())
                .candidateCount(candidates.size())
                .stocks(topStocks)
                .factorCoverage(coverage)
                .build();
    }

    /**
     * 筛选条件判断
     */
    private boolean passFilter(double value, String op, Double threshold) {
        if (op == null || "NONE".equals(op) || threshold == null) return true;
        return switch (op.toUpperCase()) {
            case "GT" -> value > threshold;
            case "GTE" -> value >= threshold;
            case "LT" -> value < threshold;
            case "LTE" -> value <= threshold;
            case "EQ" -> Math.abs(value - threshold) < 1e-10;
            default -> true;
        };
    }

    /**
     * 极值处理
     */
    private List<Double> applyOutlierProcessing(List<Double> values, String method) {
        if (values == null || values.isEmpty()) return values;
        if (method == null || "NONE".equalsIgnoreCase(method)) return values;

        List<Double> sorted = values.stream().sorted().collect(Collectors.toList());
        int n = sorted.size();

        return switch (method.toUpperCase()) {
            case "MAD" -> applyMAD(values);
            case "SIGMA3", "3SIGMA" -> applySigma3(values);
            case "PERCENTILE" -> applyPercentileClip(values, 0.01, 0.99);
            default -> values;
        };
    }

    /**
     * MAD（中位数去极值法）
     * 中位数 ± 5*MAD 范围外的值截断
     */
    private List<Double> applyMAD(List<Double> values) {
        List<Double> sorted = values.stream().sorted().collect(Collectors.toList());
        int n = sorted.size();
        double median = n % 2 == 0
                ? (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0
                : sorted.get(n / 2);

        List<Double> absDeviations = values.stream()
                .map(v -> Math.abs(v - median))
                .sorted()
                .collect(Collectors.toList());
        double mad = n % 2 == 0
                ? (absDeviations.get(n / 2 - 1) + absDeviations.get(n / 2)) / 2.0
                : absDeviations.get(n / 2);

        double lower = median - 5 * mad;
        double upper = median + 5 * mad;

        return values.stream()
                .map(v -> Math.max(lower, Math.min(upper, v)))
                .collect(Collectors.toList());
    }

    /**
     * 3σ 法
     * 均值 ± 3*标准差 范围外的值截断
     */
    private List<Double> applySigma3(List<Double> values) {
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double variance = values.stream()
                .mapToDouble(v -> (v - mean) * (v - mean))
                .average()
                .orElse(0.0);
        double std = Math.sqrt(variance);

        double lower = mean - 3 * std;
        double upper = mean + 3 * std;

        return values.stream()
                .map(v -> Math.max(lower, Math.min(upper, v)))
                .collect(Collectors.toList());
    }

    /**
     * 百分位截断
     */
    private List<Double> applyPercentileClip(List<Double> values, double lowerP, double upperP) {
        List<Double> sorted = values.stream().sorted().collect(Collectors.toList());
        int n = sorted.size();
        int lowerIdx = (int) (n * lowerP);
        int upperIdx = (int) (n * upperP);
        double lower = sorted.get(Math.max(0, lowerIdx));
        double upper = sorted.get(Math.min(n - 1, upperIdx));

        return values.stream()
                .map(v -> Math.max(lower, Math.min(upper, v)))
                .collect(Collectors.toList());
    }

    /**
     * 标准化处理
     */
    private List<Double> applyNormalization(List<Double> values, String method) {
        if (values == null || values.isEmpty()) return values;
        if (method == null || "NONE".equalsIgnoreCase(method)) return values;

        return switch (method.toUpperCase()) {
            case "ZSCORE" -> applyZScore(values);
            case "MINMAX" -> applyMinMax(values);
            case "RANK", "PERCENTRANK" -> applyPercentRank(values);
            default -> values;
        };
    }

    /**
     * Z-Score 标准化
     * (x - mean) / std
     */
    private List<Double> applyZScore(List<Double> values) {
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double variance = values.stream()
                .mapToDouble(v -> (v - mean) * (v - mean))
                .average()
                .orElse(0.0);
        double std = Math.sqrt(variance);
        if (std < 1e-10) return values.stream().map(v -> 0.0).collect(Collectors.toList());

        return values.stream()
                .map(v -> (v - mean) / std)
                .collect(Collectors.toList());
    }

    /**
     * Min-Max 归一化到 [0, 1]
     */
    private List<Double> applyMinMax(List<Double> values) {
        double min = values.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
        double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
        double range = max - min;
        if (range < 1e-10) return values.stream().map(v -> 0.5).collect(Collectors.toList());

        return values.stream()
                .map(v -> (v - min) / range)
                .collect(Collectors.toList());
    }

    /**
     * 百分位排名（0-1）
     */
    private List<Double> applyPercentRank(List<Double> values) {
        List<Double> sorted = values.stream().sorted().collect(Collectors.toList());
        int n = sorted.size();
        return values.stream()
                .map(v -> {
                    int rank = 0;
                    for (int i = 0; i < n; i++) {
                        if (sorted.get(i) < v) rank++;
                    }
                    return n <= 1 ? 0.5 : (double) rank / (n - 1);
                })
                .collect(Collectors.toList());
    }

    /**
     * 获取所有可用因子（已有因子值的因子代码 + 定义信息）
     */
    public List<Map<String, Object>> getAvailableFactors() {
        return factorDefMapper.selectList(null).stream()
                .filter(fd -> fd.getStatus() != null && fd.getStatus().name().equals("ACTIVE"))
                .map(fd -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("factorCode", fd.getFactorCode());
                    m.put("factorName", fd.getFactorName());
                    m.put("category", fd.getCategory().name());
                    m.put("description", fd.getDescription());
                    return m;
                })
                .collect(Collectors.toList());
    }

    /**
     * 获取最新可用选股日期（取所有有数据的因子中最新日期的最小值）
     */
    public String getLatestAvailableDate() {
        List<String> keyCodes = List.of("MOM20", "VOL20", "SIZE", "MOM60");
        LocalDate latest = null;
        for (String code : keyCodes) {
            // 使用MyBatis-Plus查询最大日期
            var wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FactorValue>();
            wrapper.eq(FactorValue::getFactorCode, code)
                   .orderByDesc(FactorValue::getCalcDate)
                   .last("LIMIT 1");
            FactorValue fv = factorValueMapper.selectOne(wrapper);
            if (fv != null && fv.getCalcDate() != null) {
                if (latest == null || fv.getCalcDate().isBefore(latest)) {
                    latest = fv.getCalcDate();
                }
            }
        }
        return latest != null ? latest.toString() : "2024-12-31";
    }

    /**
     * 查找各因子最新的可用日期（取各因子最新日期的最小值，确保每个因子都有数据）
     */
    private LocalDate resolveLatestDate(List<ScreenRequest.FactorWeight> factors) {
        if (factors == null || factors.isEmpty()) return LocalDate.now().minusDays(1);
        LocalDate latest = null;
        for (ScreenRequest.FactorWeight fw : factors) {
            var wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FactorValue>();
            wrapper.eq(FactorValue::getFactorCode, fw.getFactorCode())
                   .orderByDesc(FactorValue::getCalcDate)
                   .last("LIMIT 1");
            FactorValue fv = factorValueMapper.selectOne(wrapper);
            if (fv != null && fv.getCalcDate() != null) {
                if (latest == null || fv.getCalcDate().isBefore(latest)) {
                    latest = fv.getCalcDate();
                }
            }
        }
        // 如果数据库里找不到任何数据，回退到昨天（后续会报 candidateCount=0 提示用户）
        return latest != null ? latest : LocalDate.now().minusDays(1);
    }

    private BigDecimal toBD(Object val) {
        if (val == null) return null;
        if (val instanceof BigDecimal) return (BigDecimal) val;
        if (val instanceof Number) return BigDecimal.valueOf(((Number) val).doubleValue()).setScale(2, RoundingMode.HALF_UP);
        return null;
    }

    /**
     * 施密特正交化（Gram-Schmidt）
     * 对标准化后的因子值矩阵做正交化，消除因子间共线性
     *
     * 算法：
     * 1. 提取 N 个因子 × M 只股票的矩阵（使用 rankValue）
     * 2. 按因子顺序做 Gram-Schmidt 正交化
     * 3. 将正交化后的值回写到 FactorValue.rankValue
     *
     * 注意：结果依赖因子顺序，建议将低IC因子放在前面
     */
    private void applyOrthogonalization(Map<String, Map<String, FactorValue>> factorData,
                                         String method) {
        List<String> factorCodes = new ArrayList<>(factorData.keySet());
        int numFactors = factorCodes.size();
        if (numFactors < 2) return;

        // 找出所有因子共有的股票（取交集）
        Set<String> commonSymbols = new LinkedHashSet<>(factorData.get(factorCodes.get(0)).keySet());
        for (int i = 1; i < numFactors; i++) {
            commonSymbols.retainAll(factorData.get(factorCodes.get(i)).keySet());
        }
        List<String> symbols = new ArrayList<>(commonSymbols);
        int numSymbols = symbols.size();

        if (numSymbols < 10) {
            log.warn("Too few common stocks ({}) for orthogonalization, skipping", numSymbols);
            return;
        }

        // 构建因子矩阵：factorMatrix[f][s] = 第f个因子在第s只股票上的标准化值
        double[][] factorMatrix = new double[numFactors][numSymbols];
        for (int f = 0; f < numFactors; f++) {
            Map<String, FactorValue> symMap = factorData.get(factorCodes.get(f));
            for (int s = 0; s < numSymbols; s++) {
                FactorValue fv = symMap.get(symbols.get(s));
                factorMatrix[f][s] = fv != null && fv.getRankValue() != null
                        ? fv.getRankValue().doubleValue() : 0.0;
            }
        }

        // Gram-Schmidt 正交化
        // orthoVectors[f] = 正交化后的第f个因子向量
        double[][] orthoVectors = new double[numFactors][numSymbols];
        for (int f = 0; f < numFactors; f++) {
            // 先复制原始向量
            System.arraycopy(factorMatrix[f], 0, orthoVectors[f], 0, numSymbols);

            // 减去在之前所有正交向量上的投影
            for (int k = 0; k < f; k++) {
                double proj = dotProduct(orthoVectors[f], orthoVectors[k])
                        / dotProduct(orthoVectors[k], orthoVectors[k]);
                for (int s = 0; s < numSymbols; s++) {
                    orthoVectors[f][s] -= proj * orthoVectors[k][s];
                }
            }

            // 归一化（保持方差，使正交化后的值分布与原始值相似）
            double norm = Math.sqrt(dotProduct(orthoVectors[f], orthoVectors[f]) / numSymbols);
            if (norm > 1e-10) {
                // 用原始因子的标准差来缩放，保持量级
                double origStd = standardDeviation(factorMatrix[f]);
                double scale = origStd / norm;
                for (int s = 0; s < numSymbols; s++) {
                    orthoVectors[f][s] *= scale;
                }
            }
        }

        // 回写到 factorData 的 rankValue
        for (int f = 0; f < numFactors; f++) {
            Map<String, FactorValue> symMap = factorData.get(factorCodes.get(f));
            for (int s = 0; s < numSymbols; s++) {
                FactorValue fv = symMap.get(symbols.get(s));
                if (fv != null) {
                    fv.setRankValue(BigDecimal.valueOf(orthoVectors[f][s]).setScale(6, RoundingMode.HALF_UP));
                }
            }
        }

        // 计算正交化前后相关性变化（用于日志）
        double avgCorrBefore = avgCorrelation(factorMatrix);
        double avgCorrAfter = avgCorrelation(orthoVectors);
        log.info("Orthogonalization ({}): {} factors × {} stocks, avg correlation: {:.4f} → {:.4f}",
                method, numFactors, numSymbols, avgCorrBefore, avgCorrAfter);
    }

    private double dotProduct(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) sum += a[i] * b[i];
        return sum;
    }

    private double standardDeviation(double[] values) {
        double mean = 0;
        for (double v : values) mean += v;
        mean /= values.length;
        double var = 0;
        for (double v : values) var += (v - mean) * (v - mean);
        return Math.sqrt(var / values.length);
    }

    private double avgCorrelation(double[][] matrix) {
        int n = matrix.length;
        if (n < 2) return 0;
        double totalCorr = 0;
        int pairs = 0;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                totalCorr += Math.abs(pearsonCorrelation(matrix[i], matrix[j]));
                pairs++;
            }
        }
        return pairs > 0 ? totalCorr / pairs : 0;
    }

    private double pearsonCorrelation(double[] x, double[] y) {
        int n = x.length;
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0, sumY2 = 0;
        for (int i = 0; i < n; i++) {
            sumX += x[i]; sumY += y[i];
            sumXY += x[i] * y[i];
            sumX2 += x[i] * x[i]; sumY2 += y[i] * y[i];
        }
        double num = n * sumXY - sumX * sumY;
        double den = Math.sqrt((n * sumX2 - sumX * sumX) * (n * sumY2 - sumY * sumY));
        return den == 0 ? 0 : num / den;
    }
}
