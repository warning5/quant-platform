package com.quant.platform.screen.service;

import com.quant.platform.factor.domain.FactorValue;
import com.quant.platform.factor.mapper.FactorDefinitionMapper;
import com.quant.platform.factor.service.ClickHouseFactorValueService;
import com.quant.platform.market.domain.MarketDailyBar;
import com.quant.platform.market.service.MarketDataService;
import com.quant.platform.screen.dto.ScreenRequest;
import com.quant.platform.screen.dto.ScreenResult;
import com.quant.platform.screen.entity.ScreenPreset;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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

    private final ClickHouseFactorValueService clickHouseFactorValueService;
    private final FactorDefinitionMapper factorDefMapper;
    private final MarketDataService marketDataService;
    private final PriceAdvisorService priceAdvisorService;
    private final ScreenPresetService presetService;

    @Resource
    private DataSource dataSource;

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

        // factor_value.symbol 无后缀，MarketDailyBar.symbol 有后缀（如 600519.SH）
        // 构建纯净代码到完整 symbol 的映射，以及按纯净代码索引的 barMap
        Map<String, String> codeToSymbol = new HashMap<>();
        Map<String, MarketDailyBar> barMapByCode = new HashMap<>();
        for (Map.Entry<String, MarketDailyBar> entry : barMap.entrySet()) {
            String fullSym = entry.getKey();
            int dot = fullSym.lastIndexOf('.');
            String code = dot > 0 ? fullSym.substring(0, dot) : fullSym;
            codeToSymbol.put(code, fullSym);
            if (!barMapByCode.containsKey(code)) {
                barMapByCode.put(code, entry.getValue());
            }
        }

        // 候选股票池（若 excludeSt，则剔除名称含"ST"的）
        Set<String> candidates = barMapByCode.keySet().stream()
                .filter(sym -> {
                    if (Boolean.TRUE.equals(req.getExcludeSt())) {
                        MarketDailyBar b = barMapByCode.get(sym);
                        String name = b.getName() != null ? b.getName().toUpperCase() : "";
                        return !name.contains("ST");
                    }
                    return true;
                })
                .collect(Collectors.toSet());

        log.info("Candidate stocks after ST filter: {}", candidates.size());

        // ── 2.5 自定义 SQL WHERE 条件过滤（高级模式）──────────────────
        if (req.getCustomSqlWhere() != null && !req.getCustomSqlWhere().isBlank()) {
            String rawSql = req.getCustomSqlWhere().trim();
            // 安全检查：禁止危险关键字
            String upper = rawSql.toUpperCase();
            for (String keyword : new String[]{"UNION", "DELETE", "DROP", "INSERT", "UPDATE", "OR", "--", ";", "/*"}) {
                if (upper.contains(keyword)) {
                    log.warn("Blocked custom SQL containing forbidden keyword: {}", keyword);
                    throw new IllegalArgumentException("自定义SQL条件包含不安全的关键字: " + keyword);
                }
            }
            try {
                // 用 stock_daily 表 + 选股日期做安全查询，只返回符合条件的 symbol 列表
                Set<String> sqlFiltered = new HashSet<>();
                try (Connection conn = dataSource.getConnection();
                     PreparedStatement ps = conn.prepareStatement(
                             "SELECT DISTINCT code FROM stock_daily WHERE trade_date = ? AND (" + rawSql + ")")) {
                    ps.setString(1, screenDate.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            sqlFiltered.add(rs.getString("code"));
                        }
                    }
                }
                candidates.retainAll(sqlFiltered);
                log.info("After custom SQL filter: {} stocks remain (filtered from SQL: {})", candidates.size(), rawSql.substring(0, Math.min(rawSql.length(), 80)));
            } catch (Exception e) {
                log.warn("Custom SQL filter failed: {}, error: {}", rawSql, e.getMessage());
                // SQL 执行失败时不过滤（降级为不使用自定义条件），避免阻断整个选股流程
            }
        }

        // ── 2.6 MA 均线位置过滤（价格在 MA30/60/100 上方）──────────────
        if (req.getMaPositionFilter() != null) {
            ScreenRequest.MaPositionFilter mpf = req.getMaPositionFilter();
            boolean needMaFilter = Boolean.TRUE.equals(mpf.getAboveMA30())
                    || Boolean.TRUE.equals(mpf.getAboveMA60())
                    || Boolean.TRUE.equals(mpf.getAboveMA100());
            if (needMaFilter) {
                // 将候选 symbol 转为带后缀格式（barMap key），批量计算均线位置
                // MA过滤器需要完整symbol（带后缀）
                List<String> candidateList = candidates.stream()
                        .map(code -> codeToSymbol.getOrDefault(code, code))
                        .collect(Collectors.toList());
                Map<String, Map<String, Object>> maPositions =
                        priceAdvisorService.batchCalcMaPositions(candidateList, screenDate);
                candidates.removeIf(sym -> {
                    Map<String, Object> pos = maPositions.get(sym);
                    if (pos == null) return true; // 无数据，剔除
                    if (Boolean.TRUE.equals(mpf.getAboveMA30())
                            && !Boolean.TRUE.equals(pos.get("aboveMA30"))) return true;
                    if (Boolean.TRUE.equals(mpf.getAboveMA60())
                            && !Boolean.TRUE.equals(pos.get("aboveMA60"))) return true;
                    return Boolean.TRUE.equals(mpf.getAboveMA100())
                            && !Boolean.TRUE.equals(pos.get("aboveMA100"));
                });
                log.info("[Screen] After MA position filter: {} stocks remain", candidates.size());
            }
        }

        // ── 3. 加载各因子的截面数据，并进行极值处理、标准化 ────────────
        Map<String, Map<String, FactorValue>> factorData = new LinkedHashMap<>();
        Map<String, Integer> coverage = new LinkedHashMap<>();

        for (ScreenRequest.FactorWeight fw : req.getFactors()) {
            String code = fw.getFactorCode();

            // 尝试当天，不够就向前最多5日
            List<FactorValue> crossSection = Collections.emptyList();
            LocalDate searchDate = screenDate;
            for (int i = 0; i <= 5; i++) {
                crossSection = clickHouseFactorValueService.findByFactorCodeAndDate(code, searchDate);
                if (!crossSection.isEmpty()) break;
                searchDate = searchDate.minusDays(1);
            }

            // 过滤候选股票，并提取原始值
            List<FactorValue> filtered = crossSection.stream()
                    .filter(fv -> candidates.contains(fv.getSymbol()))
                    .toList();

            // 诊断：symbol 格式不匹配时打印样本
            if (filtered.isEmpty() && !crossSection.isEmpty()) {
                log.warn("[Screen] symbol mismatch! crossSection size={}, first 5 symbols={}, candidates size={}, first 5 candidates={}",
                        crossSection.size(),
                        crossSection.stream().limit(5).map(FactorValue::getSymbol).collect(Collectors.toList()),
                        candidates.size(),
                        candidates.stream().limit(5).collect(Collectors.toList()));
            }

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

        // ── 4. 筛选 + 计算综合得分 ───────────────────────────────────
        // 权重归一化（绝对值之和 = 1）
        double totalAbsWeight = req.getFactors().stream()
                .mapToDouble(fw -> Math.abs(fw.getWeight())).sum();
        if (totalAbsWeight == 0) totalAbsWeight = 1.0;

        // 只选在所有因子中都有值的股票（或放宽到至少有 50% 因子有值）
        int minFactors = Math.max(1, (int) Math.ceil(req.getFactors().size() * 0.5));

        // 统计每个因子的筛选通过数
        Map<String, Integer> filterPassCount = new LinkedHashMap<>();
        for (ScreenRequest.FactorWeight fw : req.getFactors()) {
            filterPassCount.put(fw.getFactorCode(), 0);
        }

        // 第一遍：收集通过筛选的股票及其原始因子值
        List<String> passedSymbols = new ArrayList<>();
        Map<String, Map<String, Double>> passedRawValues = new LinkedHashMap<>(); // symbol -> {factorCode: rawValue}

        for (String sym : candidates) {
            Map<String, Double> valueMap = new LinkedHashMap<>();
            boolean passed = true;

            for (ScreenRequest.FactorWeight fw : req.getFactors()) {
                FactorValue fv = factorData.get(fw.getFactorCode()).get(sym);
                if (fv != null) {
                    double raw = fv.getFactorVal() != null ? fv.getFactorVal().doubleValue() : 0.0;
                    // 筛选条件过滤
                    if (!passFilter(raw, fw.getFilterOp(), fw.getFilterValue())) {
                        passed = false;
                        break;
                    }
                    filterPassCount.merge(fw.getFactorCode(), 1, Integer::sum);
                    valueMap.put(fw.getFactorCode(), raw);
                }
            }

            if (passed && !valueMap.isEmpty()) {
                passedSymbols.add(sym);
                passedRawValues.put(sym, valueMap);
            }
        }

        log.info("[Screen] Filter passed: {} stocks (from {} candidates)", passedSymbols.size(), candidates.size());

        // 第二遍：在通过池内对每个因子重新做 rank 归一化（0~1），使排名有区分度
        for (ScreenRequest.FactorWeight fw : req.getFactors()) {
            final String fc = fw.getFactorCode();
            // 收集该因子所有通过股票的原始值
            List<Map.Entry<String, Double>> vals = new ArrayList<>();
            for (String s : passedSymbols) {
                Map<String, Double> vm = passedRawValues.get(s);
                if (vm == null) continue;
                Double v = vm.get(fc);
                if (v != null) vals.add(new AbstractMap.SimpleEntry<>(s, v));
            }

            if (vals.isEmpty()) continue;

            // 按 raw 值排序，分配 rank（0~1）
            List<Map.Entry<String, Double>> sorted = vals.stream()
                    .sorted((a, b) -> Double.compare(a.getValue(), b.getValue()))
                    .toList();

            int n = sorted.size();
            for (int i = 0; i < n; i++) {
                // rank = (i + 0.5) / n，均匀分布在 (0, 1)
                double rank = (i + 0.5) / n;
                String sym = sorted.get(i).getKey();
                passedRawValues.get(sym).put("__rank_" + fc, rank);
            }
        }

        // 第三遍：计算综合得分 + 构建 result
        List<ScreenResult.StockScore> scores = new ArrayList<>();
        for (String sym : passedSymbols) {
            Map<String, Double> rankMap = new LinkedHashMap<>();
            Map<String, Double> valueMap = passedRawValues.get(sym);
            int validCount = valueMap.size();
            double compositeScore = 0.0;

            for (ScreenRequest.FactorWeight fw : req.getFactors()) {
                String fc = fw.getFactorCode();
                Double raw = valueMap.get(fc);
                if (raw == null) continue;

                // 使用池内 rank 归一化值
                Double normalized = valueMap.get("__rank_" + fc);
                if (normalized == null) normalized = 0.5;

                rankMap.put(fc, normalized);

                // 权重 * 方向 * 标准化值
                double normalizedWeight = fw.getWeight() / totalAbsWeight;
                // direction: 1=正向（越高越好），-1=反向（越低越好）
                double factorScore = fw.getDirection() >= 0 ? normalized : (1.0 - normalized);
                compositeScore += normalizedWeight * factorScore;
                validCount++;
            }

            // 从 valueMap 中剥离 __rank_ 前缀的临时数据
            Map<String, Double> cleanValueMap = valueMap.entrySet().stream()
                    .filter(e -> !e.getKey().startsWith("__rank_"))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a,b) -> a, LinkedHashMap::new));

            MarketDailyBar bar = barMapByCode.get(sym);
            scores.add(ScreenResult.StockScore.builder()
                    .symbol(codeToSymbol.getOrDefault(sym, sym))
                    .name(bar != null ? bar.getName() : sym)
                    .compositeScore(compositeScore)
                    .factorRanks(rankMap)
                    .factorValues(cleanValueMap)
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
                .factorFilterPass(filterPassCount)
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

        List<Double> sorted = values.stream().sorted().toList();
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
        List<Double> sorted = values.stream().sorted().toList();
        int n = sorted.size();
        double median = n % 2 == 0
                ? (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0
                : sorted.get(n / 2);

        List<Double> absDeviations = values.stream()
                .map(v -> Math.abs(v - median))
                .sorted()
                .toList();
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
        List<Double> sorted = values.stream().sorted().toList();
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
        List<Double> sorted = values.stream().sorted().toList();
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
            LocalDate d = clickHouseFactorValueService.getLatestDate(code);
            if (d != null) {
                if (latest == null || d.isBefore(latest)) {
                    latest = d;
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
            LocalDate d = clickHouseFactorValueService.getLatestDate(fw.getFactorCode());
            if (d != null) {
                if (latest == null || d.isBefore(latest)) {
                    latest = d;
                }
            }
        }
        // 如果数据库里找不到任何数据，回退到昨天（后续会报 candidateCount=0 提示用户）
        return latest != null ? latest : LocalDate.now().minusDays(1);
    }

    private BigDecimal toBD(Object val) {
        return switch (val) {
            case BigDecimal bigDecimal -> bigDecimal;
            case Number number -> BigDecimal.valueOf(number.doubleValue()).setScale(2, RoundingMode.HALF_UP);
            case null, default -> null;
        };
    }

    /**
     * 施密特正交化（Gram-Schmidt）
     * 对标准化后的因子值矩阵做正交化，消除因子间共线性
     * 算法：
     * 1. 提取 N 个因子 × M 只股票的矩阵（使用 rankValue）
     * 2. 按因子顺序做 Gram-Schmidt 正交化
     * 3. 将正交化后的值回写到 FactorValue.rankValue
     * 注意：结果依赖因子顺序，建议将低IC因子放在前面
     */
    private void applyOrthogonalization(Map<String, Map<String, FactorValue>> factorData,
                                        String method) {
        List<String> factorCodes = new ArrayList<>(factorData.keySet());
        int numFactors = factorCodes.size();
        if (numFactors < 2) return;

        // 找出所有因子共有的股票（取交集）
        Set<String> commonSymbols = new LinkedHashSet<>(factorData.get(factorCodes.getFirst()).keySet());
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
        log.info("Orthogonalization ({}): {} factors × {} stocks, avg correlation: {} → {}",
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
            sumX += x[i];
            sumY += y[i];
            sumXY += x[i] * y[i];
            sumX2 += x[i] * x[i];
            sumY2 += y[i] * y[i];
        }
        double num = n * sumXY - sumX * sumY;
        double den = Math.sqrt((n * sumX2 - sumX * sumX) * (n * sumY2 - sumY * sumY));
        return den == 0 ? 0 : num / den;
    }
}
