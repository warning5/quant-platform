package com.quant.platform.screen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quant.platform.factor.domain.FactorDefinition;
import com.quant.platform.factor.domain.FactorDefinition.FactorCategory;
import com.quant.platform.factor.domain.FactorValue;
import com.quant.platform.factor.mapper.FactorDefinitionMapper;
import com.quant.platform.factor.service.ClickHouseFactorValueService;
import com.quant.platform.factor.ic.service.FactorIcService;
import com.quant.platform.market.domain.MarketDailyBar;
import com.quant.platform.market.service.MarketDataService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.screen.dto.ScreenRequest;
import com.quant.platform.screen.dto.ScreenResult;
import com.quant.platform.strategy.domain.StrategyDefinition;
import com.quant.platform.strategy.mapper.StrategyDefinitionMapper;
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
    private final StrategyDefinitionMapper strategyDefMapper;
    private final ObjectMapper objectMapper;
    private final FactorIcService factorIcService;

    @Resource
    private DataSource dataSource;

    /**
     * 多日模式下的因子趋势动量缓存：factorCode -> (symbol -> trend)
     * trend = (latestVal - earliestVal) / |earliestVal|
     */
    private Map<String, Map<String, Double>> multiDayTrendCache = new HashMap<>();

    /**
     * 多日模式下被 CV 过滤掉的原始因子值（不参与排名，仅用于展示）
     * factorCode -> (symbol -> rawValue)
     */
    private Map<String, Map<String, Double>> multiDayUnstableCache = new HashMap<>();

    /**
     * 执行多因子选股
     */
    public ScreenResult screen(ScreenRequest req) {
        // ── 0. 加载策略定义因子配置 + 过滤条件 ──────────────────────────
        Long sid = req.getStrategyId();
        Map<String, Object> filterConfig = null;
        if (sid != null && (req.getFactors() == null || req.getFactors().isEmpty())) {
            StrategyDefinition strategy = strategyDefMapper.selectById(sid);
            if (strategy != null) {
                if (strategy.getFactorConfigJson() != null) {
                    try {
                        List<ScreenRequest.FactorWeight> factors = parseStrategyFactorConfig(strategy.getFactorConfigJson());
                        req.setFactors(factors);
                        log.info("Loaded strategy [{}] with {} factors", strategy.getStrategyName(), factors.size());
                    } catch (Exception e) {
                        log.warn("Failed to load strategy {}: {}", sid, e.getMessage());
                    }
                }
                // 解析 filterConfigJson（行业排除、最少上市天数、自定义因子过滤）
                if (strategy.getFilterConfigJson() != null && !strategy.getFilterConfigJson().isBlank()) {
                    try {
                        filterConfig = objectMapper.readValue(strategy.getFilterConfigJson(),
                                new TypeReference<>() {
                                });
                        log.info("Loaded filter config for strategy [{}]: {}", strategy.getStrategyName(), filterConfig.keySet());
                    } catch (Exception e) {
                        log.warn("Failed to parse filterConfigJson for strategy {}: {}", sid, e.getMessage());
                    }
                }
            }
        }

        // ── 1. 确定选股日期（支持单日 / 多日平均模式）────────────────
        LocalDate screenDate = req.getScreenDate();
        LocalDate screenStartDate = req.getScreenStartDate();
        LocalDate screenEndDate = req.getScreenEndDate();
        boolean useMultiDayMode = (screenStartDate != null && screenEndDate != null);

        // 清空多日趋势缓存 + CV 过滤缓存
        this.multiDayTrendCache.clear();
        this.multiDayUnstableCache.clear();

        // 多日平均模式下，screenDate = endDate（用于行情加载、MA计算等）
        if (useMultiDayMode) {
            screenDate = screenEndDate;
            log.info("Running stock screen in MULTI-DAY mode: range={} ~ {}, factors={}, topN={}",
                    screenStartDate, screenEndDate, req.getFactors().size(), req.getTopN());
        } else {
            if (screenDate == null) {
                screenDate = resolveLatestDate(req.getFactors());
            }
            log.info("Running stock screen on SINGLE date={}, factors={}, topN={}",
                    screenDate, req.getFactors().size(), req.getTopN());
        }

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
        Set<String> candidatesRaw = barMapByCode.keySet().stream()
                .filter(sym -> {
                    if (Boolean.TRUE.equals(req.getExcludeSt())) {
                        MarketDailyBar b = barMapByCode.get(sym);
                        String name = b.getName() != null ? b.getName().toUpperCase() : "";
                        return !name.contains("ST");
                    }
                    return true;
                })
                .collect(Collectors.toSet());

        log.info("Candidate stocks after ST filter: {}", candidatesRaw.size());

        // ── 2.1 filterConfigJson 过滤（行业排除、上市天数、自定义因子条件）──
        Set<String> candidates;
        if (filterConfig != null) {
            candidates = applyFilterConfig(candidatesRaw, barMapByCode, filterConfig, screenDate);
            log.info("After filterConfig: {} stocks remain", candidates.size());
        } else {
            candidates = candidatesRaw;
        }

        // ── 2.5 自定义 SQL WHERE 条件过滤（高级模式）──────────────────
        // 安全说明：req.getCustomSqlWhere() 来自前端用户输入，存在 SQL 注入风险。
        // 修复方式：使用参数化查询，禁止拼接；白名单校验字段名，拒绝危险关键字。
        if (req.getCustomSqlWhere() != null && !req.getCustomSqlWhere().isBlank()) {
            String rawSql = req.getCustomSqlWhere().trim();
            // 安全校验1：禁止危险关键字（含注释符、多语句分隔符）
            String upper = rawSql.toUpperCase(Locale.US);
            for (String keyword : new String[]{"UNION", "DELETE", "DROP", "INSERT", "UPDATE", "OR 1=", "--", ";", "/*", "*/", "@@", "CHAR(", "EXEC", "XP_", "SLEEP(", "BENCHMARK("}) {
                if (upper.contains(keyword)) {
                    log.warn("Blocked custom SQL containing forbidden keyword: {}", keyword);
                    throw new IllegalArgumentException("自定义SQL条件包含不安全的关键字: " + keyword);
                }
            }
            // 安全校验2：只允许常见的 WHERE 条件语法（字段名 运算符 值），拒绝子查询、JOIN 等
            if (rawSql.toUpperCase(Locale.US).contains("SELECT ") ||
                rawSql.toUpperCase(Locale.US).contains("JOIN ") ||
                rawSql.toUpperCase(Locale.US).contains("SUBQUERY") ||
                rawSql.contains("(") && rawSql.contains(")")) {
                // 允许简单括号（优先级），但拒绝看起来像子查询的结构
                if (rawSql.toUpperCase(Locale.US).contains("SELECT ") ||
                    rawSql.toUpperCase(Locale.US).contains("FROM ")) {
                    log.warn("Blocked custom SQL possibly containing subquery: {}", rawSql);
                    throw new IllegalArgumentException("自定义SQL条件不支持子查询语法");
                }
            }
            try {
                // 用 stock_daily 表 + 选股日期做安全查询，只返回符合条件的 symbol 列表
                // 修复：不再拼接 rawSql，而是要求其使用 ? 占位符，通过参数传入
                // 前端必须按 "field OP ?" 格式传参，参数值在 customSqlParams 中
                List<Object> sqlParams = req.getCustomSqlParams() != null
                        ? req.getCustomSqlParams() : Collections.emptyList();
                String safeSql = buildSafeCustomSql(rawSql, sqlParams.size());
                Set<String> sqlFiltered = new HashSet<>();
                try (Connection conn = dataSource.getConnection();
                     PreparedStatement ps = conn.prepareStatement(
                             "SELECT DISTINCT code FROM stock_daily WHERE trade_date = ? AND " + safeSql)) {
                    int paramIdx = 1;
                    ps.setString(paramIdx++, screenDate.toString());
                    for (Object p : sqlParams) {
                        if (p instanceof String) {
                            ps.setString(paramIdx++, (String) p);
                        } else if (p instanceof Number) {
                            ps.setBigDecimal(paramIdx++, BigDecimal.valueOf(((Number) p).doubleValue()));
                        } else if (p instanceof java.time.LocalDate) {
                            ps.setString(paramIdx++, p.toString());
                        } else {
                            ps.setObject(paramIdx++, p);
                        }
                    }
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
                long maStart = System.currentTimeMillis();
                // 将候选 symbol 转为带后缀格式（barMap key），批量计算均线位置
                // MA过滤器需要完整symbol（带后缀）
                List<String> candidateList = candidates.stream()
                        .map(code -> codeToSymbol.getOrDefault(code, code))
                        .collect(Collectors.toList());
                Map<String, Map<String, Object>> maPositions =
                        priceAdvisorService.batchCalcMaPositions(candidateList, screenDate);
                candidates.removeIf(sym -> {
                    Map<String, Object> pos = maPositions.get(codeToSymbol.getOrDefault(sym, sym));
                    if (pos == null) return true; // 无数据，剔除
                    if (Boolean.TRUE.equals(mpf.getAboveMA30())
                            && !Boolean.TRUE.equals(pos.get("aboveMA30"))) return true;
                    if (Boolean.TRUE.equals(mpf.getAboveMA60())
                            && !Boolean.TRUE.equals(pos.get("aboveMA60"))) return true;
                    return Boolean.TRUE.equals(mpf.getAboveMA100())
                            && !Boolean.TRUE.equals(pos.get("aboveMA100"));
                });
                log.info("[Screen] After MA position filter: {} stocks remain (took {} ms)", candidates.size(), System.currentTimeMillis() - maStart);
            }
        }

        // ── 3. 加载各因子的截面数据，并进行极值处理、标准化 ────────────
        Map<String, Map<String, FactorValue>> factorData = new LinkedHashMap<>();
        Map<String, Integer> coverage = new LinkedHashMap<>();
        long factorLoadStart = System.currentTimeMillis();

        // 3.0 预加载行业信息和市值信息（用于中性化）
        Map<String, String> industryMap = new HashMap<>();
        Map<String, Double> marketCapMap = new HashMap<>();
        String neutralizationMethod = req.getNeutralizationMethod();
        boolean needIndustry = neutralizationMethod != null && 
            (neutralizationMethod.contains("INDUSTRY") || "BOTH".equalsIgnoreCase(neutralizationMethod));
        boolean needMarketCap = neutralizationMethod != null && 
            (neutralizationMethod.contains("MARKET_CAP") || "BOTH".equalsIgnoreCase(neutralizationMethod));
        
        if (needIndustry || needMarketCap) {
            long neutStart = System.currentTimeMillis();
            List<String> candidateList = new ArrayList<>(candidates);
            if (needIndustry) {
                industryMap = batchLoadIndustryInfo(candidateList);
            }
            if (needMarketCap) {
                marketCapMap = batchLoadMarketCap(candidateList, screenDate);
            }
            log.info("[Screen] Neutralization pre-load: industry={}, marketCap={}, took {} ms", 
                industryMap.size(), marketCapMap.size(), System.currentTimeMillis() - neutStart);
        }

        for (ScreenRequest.FactorWeight fw : req.getFactors()) {
            String code = fw.getFactorCode();
            long fStart = System.currentTimeMillis();

            List<FactorValue> crossSection;

            // P1-5: 提前查询因子定义，供后续 P1-5/P1-6 使用
            FactorDefinition factorDef = factorDefMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FactorDefinition>()
                    .eq(FactorDefinition::getFactorCode, code)
                    .last("LIMIT 1"));

            if (useMultiDayMode) {
                // ── 多日平均模式：查询日期范围，按 symbol 聚合取均值 ──
                crossSection = loadFactorAverage(code, screenStartDate, screenEndDate, candidates);
                log.info("[Screen] Multi-day factor {} avg: {} stocks, range={} ~ {}",
                        code, crossSection.size(), screenStartDate, screenEndDate);
            } else {
                // ── 单日模式 ──
                // P1-5: 季度因子按 announce_date 过滤，只用已发布的财报数据
                boolean isQuarterly = factorDef != null && "QUARTERLY".equalsIgnoreCase(factorDef.getDataFrequency());
                if (isQuarterly || code.startsWith("FIN_")) {
                    // 季度财务因子：用 announce_date 过滤，只取已发布数据
                    crossSection = clickHouseFactorValueService.findQuarterlyByScreenDate(code, screenDate);
                    log.info("[Screen] 季度因子 {} screenDate={}: {} 条 (announce_date <= {})",
                            code, screenDate, crossSection.size(), screenDate);
                } else {
                    // 日频因子：回退 5 日
                    crossSection = Collections.emptyList();
                    LocalDate searchDate = screenDate;
                    for (int i = 0; i <= 5; i++) {
                        crossSection = clickHouseFactorValueService.findByFactorCodeAndDate(code, searchDate);
                        if (!crossSection.isEmpty()) break;
                        searchDate = searchDate.minusDays(1);
                    }
                }
            }

            // 过滤候选股票，并提取原始值
            // factor_value.symbol 可能带后缀（如 000001.SZ），candidates 是纯代码（如 000001）
            // 需要做 symbol 归一化：去掉 .SH/.SZ/.BJ 后缀
            List<FactorValue> filtered = crossSection.stream()
                    .filter(fv -> candidates.contains(normalizeFactorSymbol(fv.getSymbol())))
                    .toList();

            // 诊断：symbol 格式不匹配时打印样本
            if (filtered.isEmpty() && !crossSection.isEmpty()) {
                log.warn("[Screen] symbol mismatch! crossSection size={}, first 5 symbols={}, candidates size={}, first 5 candidates={}",
                        crossSection.size(),
                        crossSection.stream().limit(5).map(FactorValue::getSymbol).collect(Collectors.toList()),
                        candidates.size(),
                        candidates.stream().limit(5).collect(Collectors.toList()));
            }

            // 极值处理（P1-6: 优先使用因子配置的方法，回退到全局配置）
            String outlierMethod = req.getGlobalOutlierMethod() != null ? req.getGlobalOutlierMethod() : "MAD";
            if (factorDef != null && factorDef.getOutlierMethod() != null) {
                outlierMethod = factorDef.getOutlierMethod();
            }
            List<Double> outlierProcessed = applyOutlierProcessing(
                    filtered.stream()
                            .map(FactorValue::getFactorVal)
                            .map(bd -> bd != null ? bd.doubleValue() : 0.0)
                            .collect(Collectors.toList()),
                    outlierMethod
            );

            // 中性化处理（在标准化之前）
            if (neutralizationMethod != null && !"NONE".equalsIgnoreCase(neutralizationMethod)) {
                outlierProcessed = applyNeutralization(
                    filtered, outlierProcessed, industryMap, marketCapMap, neutralizationMethod
                );
            }

            // 标准化处理（P1-6: 优先使用因子配置的方法，回退到全局配置）
            String normalizeMethod = req.getGlobalNormalizeMethod() != null ? req.getGlobalNormalizeMethod() : "ZSCORE";
            if (factorDef != null && factorDef.getNormalizeMethod() != null) {
                normalizeMethod = factorDef.getNormalizeMethod();
            }
            List<Double> normalized = applyNormalization(outlierProcessed, normalizeMethod);

            // P1-6: 因子分布诊断（标准化后检查偏度）
            if (normalized.size() > 10) {
                double skewness = calcSkewness(normalized);
                if (Math.abs(skewness) > 2.0) {
                    log.warn("[Screen] Factor {} 标准化后偏度={:.2f}，分布严重偏斜，建议检查因子值", code, skewness);
                }
            }

            // 重新组装 FactorValue（用标准化后的值替换 rankValue）
            // key 使用归一化后的纯代码 symbol（与 candidates 格式一致）
            Map<String, FactorValue> symbolMap = new LinkedHashMap<>();
            for (int i = 0; i < filtered.size(); i++) {
                FactorValue orig = filtered.get(i);
                String normSym = normalizeFactorSymbol(orig.getSymbol());
                FactorValue processed = new FactorValue();
                processed.setSymbol(normSym);
                processed.setFactorCode(orig.getFactorCode());
                processed.setCalcDate(orig.getCalcDate());
                processed.setFactorVal(orig.getFactorVal());
                // 用标准化后的值作为 rankValue 参与后续计算
                processed.setRankValue(BigDecimal.valueOf(normalized.get(i)));
                symbolMap.put(normSym, processed);
            }

            factorData.put(code, symbolMap);
        coverage.put(code, symbolMap.size());
        log.info("[Screen] Factor {} coverage: {} stocks on {}, outlier={}, normalize={} (took {} ms)",
                code, symbolMap.size(), useMultiDayMode ? (screenStartDate + " ~ " + screenEndDate) : screenDate, outlierMethod, normalizeMethod, System.currentTimeMillis() - fStart);
        }
        log.info("[Screen] All factors loaded: total took {} ms", System.currentTimeMillis() - factorLoadStart);

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
                    .sorted(Comparator.comparingDouble(Map.Entry::getValue))
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
        // 根据 weightMode 获取动态权重
        Map<String, Double> dynamicWeights = null;
        if (!"EQUAL".equalsIgnoreCase(req.getWeightMode())) {
            dynamicWeights = getDynamicWeights(req.getFactors(), req.getWeightMode(), screenDate);
            // 动态权重调整后，重新计算 totalAbsWeight
            totalAbsWeight = 0;
            for (ScreenRequest.FactorWeight fw : req.getFactors()) {
                String fc = fw.getFactorCode();
                double baseWeight = fw.getWeight();
                if (dynamicWeights != null && dynamicWeights.containsKey(fc)) {
                    baseWeight = baseWeight * dynamicWeights.get(fc);
                }
                totalAbsWeight += Math.abs(baseWeight);
            }
            if (totalAbsWeight == 0) totalAbsWeight = 1.0;
            log.info("[Screen] Dynamic weight adjusted, new totalAbsWeight={}", totalAbsWeight);
        }

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

                // 根据 weightMode 计算动态权重
                double baseWeight = fw.getWeight();
                if (dynamicWeights != null && dynamicWeights.containsKey(fc)) {
                    baseWeight = baseWeight * dynamicWeights.get(fc);
                }
                double normalizedWeight = baseWeight / totalAbsWeight;
                // direction: 1=正向（越高越好），-1=反向（越低越好）
                double factorScore = fw.getDirection() >= 0 ? normalized : (1.0 - normalized);
                compositeScore += normalizedWeight * factorScore;
                validCount++;
            }

            // 从 valueMap 中剥离 __rank_ 前缀的临时数据
            Map<String, Double> cleanValueMap = valueMap.entrySet().stream()
                    .filter(e -> !e.getKey().startsWith("__rank_"))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a,b) -> a, LinkedHashMap::new));

            // 多日模式：补回被 CV 过滤掉但仍有原始值的因子（不参与排名，仅用于展示）
            if (useMultiDayMode && !multiDayUnstableCache.isEmpty()) {
                for (ScreenRequest.FactorWeight fw : req.getFactors()) {
                    String fc = fw.getFactorCode();
                    if (cleanValueMap.containsKey(fc)) continue; // 已有排名值的跳过
                    Map<String, Double> unstableMap = multiDayUnstableCache.get(fc);
                    if (unstableMap != null) {
                        Double unstableVal = unstableMap.get(sym);
                        if (unstableVal != null) {
                            cleanValueMap.put(fc, unstableVal);
                        }
                    }
                }
            }

            // 多日模式：提取因子趋势动量
            Map<String, Double> factorTrends = null;
            if (useMultiDayMode && !multiDayTrendCache.isEmpty()) {
                factorTrends = new LinkedHashMap<>();
                for (ScreenRequest.FactorWeight fw : req.getFactors()) {
                    Map<String, Double> tMap = multiDayTrendCache.get(fw.getFactorCode());
                    if (tMap != null && tMap.containsKey(sym)) {
                        factorTrends.put(fw.getFactorCode(), tMap.get(sym));
                    }
                }
                if (factorTrends.isEmpty()) factorTrends = null;
            }

            MarketDailyBar bar = barMapByCode.get(sym);
            scores.add(ScreenResult.StockScore.builder()
                    .symbol(codeToSymbol.getOrDefault(sym, sym))
                    .name(bar != null ? bar.getName() : sym)
                    .compositeScore(compositeScore)
                    .factorRanks(rankMap)
                    .factorValues(cleanValueMap)
                    .factorTrends(factorTrends)
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
                .screenStartDate(useMultiDayMode ? screenStartDate : null)
                .screenEndDate(useMultiDayMode ? screenEndDate : null)
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
     * 多日平均模式：查询日期范围内的因子值，按 symbol 聚合取均值
     * 返回的 FactorValue 列表中每个 symbol 只有一条记录，factor_val = 范围内均值
     */
    /**
     * 多日模式：最新值优先 + 稳定性过滤 + 趋势动量
     * 取每个 symbol 在范围内最新一天的因子值（保留灵敏度），
     * 同时计算该范围内的变异系数 CV = std/|mean|，
     * CV 过高说明因子值波动剧烈、不稳定，予以剔除。
     * 另外计算趋势动量 trend = (latest - earliest) / |earliest|，存入 multiDayTrendCache。
     *
     * 阈值从 factor_definition.cv_threshold 读取（数据驱动）。
     * 若该因子未设置 cv_threshold，则按 category 推导默认值：
     * - MOMENTUM / 含 CORR/VPCORR 的技术因子：宽松(3.0)
     * - VOLATILITY / LIQUIDITY / VOLUME_PRICE：中等(2.0)
     * - 其他（TECHNICAL/FINANCIAL/VALUE/SENTIMENT/CHANTHEORY）：严格(0.5)
     */
    private static final double DEFAULT_CV_THRESHOLD = 0.5;

    /**
     * 从 DB factor_definition.cv_threshold 查询 CV 阈值（数据驱动）。
     * 未设置时回退到 category 推导的默认值。
     */
    private double getCVThreshold(String factorCode) {
        FactorDefinition def = factorDefMapper.selectOne(
                new LambdaQueryWrapper<FactorDefinition>()
                        .eq(FactorDefinition::getFactorCode, factorCode)
                        .last("LIMIT 1"));
        if (def != null && def.getCvThreshold() != null) {
            return def.getCvThreshold();
        }
        // 回退：根据 category 推导
        if (def != null && def.getCategory() != null) {
            return getCategoryBasedCV(def.getCategory());
        }
        return DEFAULT_CV_THRESHOLD;
    }

    /**
     * 根据 FactorCategory 推导 CV 阈值默认值
     */
    private static double getCategoryBasedCV(FactorCategory category) {
        return switch (category) {
            case MOMENTUM -> 3.0;
            case VOLATILITY, LIQUIDITY, VOLUME_PRICE -> 2.0;
            default -> DEFAULT_CV_THRESHOLD;
        };
    }

    /**
     * 统一 symbol 格式：去掉 .SZ/.SH/.BJ 等交易所后缀
     * 解决 CH factor_value 中 5月12日前后 symbol 格式不一致的问题
     */
    private static String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isEmpty()) return symbol;
        int dot = symbol.lastIndexOf('.');
        if (dot > 0) {
            String suffix = symbol.substring(dot + 1).toUpperCase();
            if (suffix.equals("SZ") || suffix.equals("SH") || suffix.equals("BJ")) {
                return symbol.substring(0, dot);
            }
        }
        return symbol;
    }

    private List<FactorValue> loadFactorAverage(String factorCode, LocalDate startDate, LocalDate endDate, Set<String> candidates) {
        // 查询范围内所有因子值
        List<FactorValue> allValues = clickHouseFactorValueService.findByFactorCodeAndDateRange(factorCode, startDate, endDate);
        if (allValues.isEmpty()) {
            // 范围内无数据（常见于季度财务因子：如选股 5 月但最新财报只到 3/31）
            // 自动回退到该因子的最新可用日期
            LocalDate latestDate = clickHouseFactorValueService.getLatestDate(factorCode);
            if (latestDate != null && !latestDate.isAfter(endDate) && !latestDate.isBefore(startDate.minusYears(2))) {
                log.info("[Screen] Multi-day: 回退查询 {} 范围 {}~{} → 最新可用日期 {}", factorCode, startDate, endDate, latestDate);
                allValues = clickHouseFactorValueService.findByFactorCodeAndDateRange(factorCode, latestDate, latestDate);
                if (!allValues.isEmpty()) {
                    log.info("[Screen] Multi-day: 回退成功，{} 在 {} 有 {} 条数据", factorCode, latestDate, allValues.size());
                }
            }
            if (allValues.isEmpty()) {
                log.warn("[Screen] Multi-day: no data for {} in {} ~ {} (回退后仍无数据)", factorCode, startDate, endDate);
                return Collections.emptyList();
            }
        }

        // 按 symbol 分组，保留每条记录以便取最新值 + 计算统计量
        // CH 中 symbol 格式可能不一致（如 300905 vs 300905.SZ），统一 strip 后缀再分组
        Map<String, List<FactorValue>> grouped = allValues.stream()
                .filter(fv -> fv.getFactorVal() != null)
                .collect(Collectors.groupingBy(fv -> normalizeSymbol(fv.getSymbol())));

        // 用结束日期作为 calc_date
        LocalDate refDate = endDate;
        int totalSymbols = 0, stableCount = 0, filteredByCV = 0;
        List<FactorValue> result = new ArrayList<>();
        Map<String, Double> trendMap = new LinkedHashMap<>(); // symbol -> trend

        // 动态 CV 阈值：根据因子数值特性选择
        double cvThreshold = getCVThreshold(factorCode);

        for (Map.Entry<String, List<FactorValue>> entry : grouped.entrySet()) {
            String symbol = entry.getKey();
            if (!candidates.contains(symbol)) continue;
            totalSymbols++;

            List<FactorValue> values = entry.getValue();
            if (values.isEmpty()) continue;

            // 按日期排序：正序（最早→最晚），同日期无后缀优先
            values.sort(Comparator.comparing(FactorValue::getCalcDate)
                    .thenComparing(v -> v.getSymbol().contains(".") ? 1 : 0));

            // 去重：同日期只保留一条，解决 ReplacingMergeTree 无法合并不同 symbol 格式的重复行
            List<FactorValue> deduped = new ArrayList<>();
            LocalDate prevDate = null;
            for (FactorValue v : values) {
                if (v.getCalcDate().equals(prevDate)) continue;
                deduped.add(v);
                prevDate = v.getCalcDate();
            }
            values = deduped;
            if (values.isEmpty()) continue;

            FactorValue earliest = values.get(0);
            FactorValue latest = values.get(values.size() - 1);
            double latestVal = latest.getFactorVal().doubleValue();
            double earliestVal = earliest.getFactorVal().doubleValue();

            // 计算趋势动量: (latest - earliest) / |earliest|
            double trend = 0;
            if (earliestVal != 0) {
                trend = (latestVal - earliestVal) / Math.abs(earliestVal);
            }
            trendMap.put(symbol, trend);

            // 计算范围内的均值和标准差 → 变异系数 CV
            double mean = values.stream().mapToDouble(v -> v.getFactorVal().doubleValue()).average().orElse(0);
            double cv = 0;
            double variance = 0;
            if (mean != 0) {
                variance = values.stream()
                        .mapToDouble(v -> Math.pow(v.getFactorVal().doubleValue() - mean, 2))
                        .average().orElse(0);
                cv = Math.sqrt(variance) / Math.abs(mean);
            }
            double stdDev = Math.sqrt(variance);

            // 稳定性过滤：CV 超阈值则剔除（阈值按因子数值特性动态选择）
            // 数据不足 10 个点时跳过 CV 过滤（样本太少时 CV 不可靠）
            //
            // ⚠️ 特殊处理：均值接近 0 的因子（REVERSAL / MACD 等零轴震荡指标），
            //   CV = std/|mean| 会因 |mean|≈0 而爆炸式偏大（数学伪影，非真不稳定）。
            //   改用绝对标准差阈值：std 过大才认为波动异常。
            boolean isZeroMean = factorCode.startsWith("REVERSAL") || factorCode.equals("MACD");
            boolean unstable = false;
            if (values.size() >= 10) {
                if (isZeroMean) {
                    // 零轴因子用绝对 std 阈值
                    unstable = (stdDev > 0.20);
                } else {
                    unstable = (cv > cvThreshold);
                }
            }
            if (unstable) {
                filteredByCV++;
                // 保存原始值到不稳定缓存（仅用于结果展示，不参与排名）
                multiDayUnstableCache.computeIfAbsent(factorCode, k -> new LinkedHashMap<>())
                        .put(symbol, latestVal);
                continue;
            }

            stableCount++;
            FactorValue fv = new FactorValue();
            fv.setSymbol(symbol);
            fv.setFactorCode(factorCode);
            fv.setCalcDate(refDate);
            fv.setFactorVal(BigDecimal.valueOf(latestVal));
            result.add(fv);
        }

        // 存入趋势缓存
        multiDayTrendCache.put(factorCode, trendMap);

        log.info("[Screen] Multi-day (latest+stable) for {}: candidates={} -> stable={} filtered_by_CV={} (threshold={}, mode={})",
                factorCode, totalSymbols, stableCount, filteredByCV, cvThreshold,
                factorCode.startsWith("REVERSAL") || factorCode.equals("MACD") ? "std_abs" : "cv_ratio");
        return result;
    }

    /**
     * 归一化 symbol：去掉 .SH/.SZ/.BJ 等交易所后缀，返回纯代码。
     * 用于统一 factor_value.symbol（可能带后缀）和 candidates（纯代码）的格式。
     */
    private static String normalizeFactorSymbol(String symbol) {
        if (symbol == null) return null;
        int dot = symbol.lastIndexOf('.');
        return dot > 0 ? symbol.substring(0, dot) : symbol;
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
     * 计算偏度（P1-6 因子分布诊断）
     */
    private double calcSkewness(List<Double> values) {
        if (values == null || values.size() < 3) return 0;
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = values.stream().mapToDouble(v -> Math.pow(v - mean, 2)).average().orElse(0);
        double std = Math.sqrt(variance);
        if (std == 0) return 0;
        double n = values.size();
        return values.stream().mapToDouble(v -> Math.pow((v - mean) / std, 3)).average().orElse(0) * n / (n - 1) / (n - 2);
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
        List<String> keyCodes = List.of("MOM20", "VOL20");
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
                if (latest == null || d.isAfter(latest)) {
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

    /**
     * 解析策略定义的 factor_config_json
     * 格式: {"factors": [{"code":"MOM20","weight":1.0,"direction":1,"filterOp":"NONE",...}, ...]}
     */
    private List<ScreenRequest.FactorWeight> parseStrategyFactorConfig(String factorConfigJson) {
        try {
            Map<String, Object> root = objectMapper.readValue(factorConfigJson,
                    new TypeReference<>() {
                    });
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> factorList = (List<Map<String, Object>>) root.get("factors");
            if (factorList == null) return Collections.emptyList();
            return factorList.stream().map(m -> {
                ScreenRequest.FactorWeight fw = new ScreenRequest.FactorWeight();
                fw.setFactorCode((String) m.get("code"));
                fw.setDirection(m.get("direction") instanceof Number
                        ? ((Number) m.get("direction")).intValue() : 1);
                fw.setWeight(m.get("weight") instanceof Number
                        ? ((Number) m.get("weight")).doubleValue() : 1.0);
                fw.setFilterOp(m.get("filterOp") != null ? (String) m.get("filterOp") : "NONE");
                fw.setFilterValue(m.get("filterValue") instanceof Number
                        ? ((Number) m.get("filterValue")).doubleValue() : null);
                return fw;
            }).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Failed to parse strategy factor config: {}", factorConfigJson, e);
            return Collections.emptyList();
        }
    }

    /**
     * 应用 filterConfigJson 中的过滤条件到候选股票池
     * 支持：
     *   - excludeIndustries: List<String> 排除的行业代码列表（申万行业）
     *   - excludeMarkets: List<String> 排除的市场代码列表，如 ["BJ"] 排除北交所
     *   - minListingDays: int 最少上市天数
     *   - minMarketCap: double 最小市值(亿元)
     *   - customFilters: List<Map> 自定义因子过滤 [{factor, op, value}]
     */
    @SuppressWarnings("unchecked")
    private Set<String> applyFilterConfig(Set<String> candidates,
                                           Map<String, MarketDailyBar> barMapByCode,
                                           Map<String, Object> filterConfig,
                                           LocalDate screenDate) {
        Set<String> result = new HashSet<>(candidates);

        // 0. 市场排除（北交所 BJ: code 以 8 或 9 开头）
        Object excludeMarkets = filterConfig.get("excludeMarkets");
        if (excludeMarkets instanceof List) {
            Set<String> marketSet = new HashSet<>((List<String>) excludeMarkets);
            if (marketSet.contains("BJ")) {
                int before = result.size();
                result.removeIf(code -> code.startsWith("8") || code.startsWith("9"));
                log.info("[FilterConfig] ExcludeMarket(BJ): removed {} stocks, remaining={}",
                        before - result.size(), result.size());
            }
        }

        // 1a. 行业白名单（includeIndustries）—— 只保留属于白名单行业的股票
        Object includeIndustries = filterConfig.get("includeIndustries");
        if (includeIndustries instanceof List) {
            Set<String> includeSet = new HashSet<>((List<String>) includeIndustries);
            if (!includeSet.isEmpty()) {
                Map<String, String> codeIndustryMap = batchLoadIndustryInfo(new ArrayList<>(result));
                int before = result.size();
                result.removeIf(code -> {
                    String ind = codeIndustryMap.get(code);
                    if (ind == null) return true; // 无行业信息→排除
                    return includeSet.stream().noneMatch(ind::contains);
                });
                log.info("[FilterConfig] Industry include: whitelist={}, before={}, after={}",
                        includeSet.size(), before, result.size());
            }
        }

        // 1b. 行业排除
        Object excludeIndustries = filterConfig.get("excludeIndustries");
        if (excludeIndustries instanceof List) {
            Set<String> excludeSet = new HashSet<>((List<String>) excludeIndustries);
            if (!excludeSet.isEmpty()) {
                // 批量查询候选股票的行业信息
                Map<String, String> codeIndustryMap = batchLoadIndustryInfo(new ArrayList<>(result));
                result.removeIf(code -> excludeSet.contains(codeIndustryMap.get(code)));
                log.info("[FilterConfig] Industry exclude: removed industries={}, remaining={}",
                        excludeSet.size(), result.size());
            }
        }

        // 2. 最少上市天数
        Object minDays = filterConfig.get("minListingDays");
        if (minDays instanceof Number) {
            int minListingDays = ((Number) minDays).intValue();
            if (minListingDays > 0) {
                Map<String, LocalDate> codeListDateMap = batchLoadListDates(new ArrayList<>(result));
                LocalDate cutoff = screenDate.minusDays(minListingDays);
                int before = result.size();
                result.removeIf(code -> {
                    LocalDate listDate = codeListDateMap.get(code);
                    return listDate == null || listDate.isAfter(cutoff);
                });
                log.info("[FilterConfig] MinListingDays={}: removed {} stocks, remaining={}",
                        minListingDays, before - result.size(), result.size());
            }
        }

        // 3. 最小市值过滤
        Object minCap = filterConfig.get("minMarketCap");
        if (minCap instanceof Number) {
            double minMarketCap = ((Number) minCap).doubleValue();
            if (minMarketCap > 0) {
                Map<String, Double> marketCapMap = batchLoadMarketCap(new ArrayList<>(result), screenDate);
                int before = result.size();
                result.removeIf(code -> {
                    Double cap = marketCapMap.get(code);
                    return cap == null || cap < minMarketCap;
                });
                log.info("[FilterConfig] MinMarketCap={}: removed {} stocks, remaining={}",
                        (long) minMarketCap, before - result.size(), result.size());
            }
        }

        // 4. 自定义因子过滤（单因子条件，GT/GTE/LT/LTE/EQ）
        Object customFilters = filterConfig.get("customFilters");
        if (customFilters instanceof List) {
            List<Map<String, Object>> filters = (List<Map<String, Object>>) customFilters;
            for (Map<String, Object> cf : filters) {
                String factorCode = (String) cf.get("factor");
                String op = (String) cf.getOrDefault("op", "GT");
                double value = cf.get("value") instanceof Number ? ((Number) cf.get("value")).doubleValue() : 0;
                if (factorCode == null) continue;

                // 查询因子截面值
                Map<String, Double> factorVals = batchLoadFactorValues(factorCode, screenDate, new ArrayList<>(result));
                if (factorVals.isEmpty()) continue;

                int before = result.size();
                result.removeIf(code -> {
                    Double fv = factorVals.get(code);
                    if (fv == null) return true; // 无因子值，剔除
                    return !compareFactorValue(fv, op, value);
                });
                log.info("[FilterConfig] customFilter: {} {} {}, removed {}, remaining={}",
                        factorCode, op, value, before - result.size(), result.size());
            }
        }

        return result;
    }

    /**
     * 因子值比较
     */
    private boolean compareFactorValue(double actual, String op, double threshold) {
        return switch (op.toUpperCase()) {
            case "GT" -> actual > threshold;
            case "GTE" -> actual >= threshold;
            case "LT" -> actual < threshold;
            case "LTE" -> actual <= threshold;
            case "EQ" -> Math.abs(actual - threshold) < 1e-8;
            default -> true; // UNKNOWN op 不过滤
        };
    }

    /**
     * 批量查询股票行业信息（从 stock_info.industry 字段）
     */
    private Map<String, String> batchLoadIndustryInfo(List<String> codes) {
        Map<String, String> result = new HashMap<>();
        final int BATCH = 500;
        for (int i = 0; i < codes.size(); i += BATCH) {
            List<String> batch = codes.subList(i, Math.min(i + BATCH, codes.size()));
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT code, industry FROM stock_info WHERE code IN (" +
                                 batch.stream().map(c -> "?").collect(Collectors.joining(",")) + ")")) {
                for (int j = 0; j < batch.size(); j++) {
                    ps.setString(j + 1, batch.get(j));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.put(rs.getString("code"), rs.getString("industry"));
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to batch load industry info: {}", e.getMessage());
            }
        }
        return result;
    }

    /**
     * 批量查询股票上市日期
     */
    private Map<String, LocalDate> batchLoadListDates(List<String> codes) {
        Map<String, LocalDate> result = new HashMap<>();
        final int BATCH = 500;
        for (int i = 0; i < codes.size(); i += BATCH) {
            List<String> batch = codes.subList(i, Math.min(i + BATCH, codes.size()));
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT code, list_date FROM stock_info WHERE code IN (" +
                                 batch.stream().map(c -> "?").collect(Collectors.joining(",")) + ")")) {
                for (int j = 0; j < batch.size(); j++) {
                    ps.setString(j + 1, batch.get(j));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.put(rs.getString("code"), rs.getDate("list_date").toLocalDate());
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to batch load list dates: {}", e.getMessage());
            }
        }
        return result;
    }

    /**
     * 批量查询指定因子在指定日期的截面值
     */
    private Map<String, Double> batchLoadFactorValues(String factorCode, LocalDate date, List<String> codes) {
        if (codes.isEmpty()) return Map.of();
        try {
            // 查询该因子在该日期的全量截面数据，然后在内存中过滤候选股票
            List<FactorValue> fvs = clickHouseFactorValueService.findByFactorCodeAndDate(factorCode, date);
            Set<String> codeSet = new HashSet<>(codes);
            Map<String, Double> result = new HashMap<>();
            for (FactorValue fv : fvs) {
                if (fv.getFactorVal() != null && codeSet.contains(fv.getSymbol())) {
                    result.put(fv.getSymbol(), fv.getFactorVal().doubleValue());
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to load factor values for {}: {}", factorCode, e.getMessage());
            return Map.of();
        }
    }

    /**
     * 批量查询股票市值（从 stock_daily 表获取当日总市值）
     */
    private Map<String, Double> batchLoadMarketCap(List<String> codes, LocalDate date) {
        Map<String, Double> result = new HashMap<>();
        if (codes.isEmpty()) return result;
        final int BATCH = 500;
        for (int i = 0; i < codes.size(); i += BATCH) {
            List<String> batch = codes.subList(i, Math.min(i + BATCH, codes.size()));
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT code, total_market_cap FROM stock_daily WHERE trade_date = ? AND code IN (" +
                                 batch.stream().map(c -> "?").collect(Collectors.joining(",")) + ")")) {
                ps.setString(1, date.toString());
                for (int j = 0; j < batch.size(); j++) {
                    ps.setString(j + 2, batch.get(j));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        double cap = rs.getDouble("total_market_cap");
                        if (cap > 0) {
                            result.put(rs.getString("code"), cap);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to batch load market cap: {}", e.getMessage());
            }
        }
        return result;
    }

    /**
     * 中性化处理
     * 
     * 在行业或市值分组内，将因子值减去组内均值，消除行业/市值偏差
     * 
     * @param filtered 原始因子值列表（与outlierProcessed一一对应）
     * @param values 极值处理后的因子值
     * @param industryMap 行业映射
     * @param marketCapMap 市值映射
     * @param method 中性化方法：INDUSTRY / MARKET_CAP / BOTH
     * @return 中性化后的因子值
     */
    private List<Double> applyNeutralization(
            List<FactorValue> filtered,
            List<Double> values,
            Map<String, String> industryMap,
            Map<String, Double> marketCapMap,
            String method) {
        
        if (values == null || values.isEmpty()) return values;
        
        // 构建 symbol -> 索引映射
        Map<String, Integer> symbolIndex = new HashMap<>();
        for (int i = 0; i < filtered.size(); i++) {
            symbolIndex.put(normalizeFactorSymbol(filtered.get(i).getSymbol()), i);
        }
        
        List<Double> result = new ArrayList<>(values);
        
        // 1. 行业中性化
        if (method.contains("INDUSTRY") || "BOTH".equalsIgnoreCase(method)) {
            // 按行业分组
            Map<String, List<Integer>> industryGroups = new HashMap<>();
            for (Map.Entry<String, Integer> entry : symbolIndex.entrySet()) {
                String industry = industryMap.get(entry.getKey());
                if (industry == null) industry = "UNKNOWN";
                industryGroups.computeIfAbsent(industry, k -> new ArrayList<>()).add(entry.getValue());
            }
            
            // 在每个行业内做均值归一
            for (List<Integer> indices : industryGroups.values()) {
                if (indices.size() < 3) continue; // 行业内股票太少，跳过
                
                double sum = 0;
                for (int idx : indices) {
                    sum += values.get(idx);
                }
                double mean = sum / indices.size();
                
                for (int idx : indices) {
                    result.set(idx, result.get(idx) - mean);
                }
            }
            
            log.debug("[Neutralization] Industry groups: {}, stocks processed: {}", 
                industryGroups.size(), symbolIndex.size());
        }
        
        // 2. 市值中性化
        if (method.contains("MARKET_CAP") || "BOTH".equalsIgnoreCase(method)) {
            // 按市值分5组
            List<Map.Entry<String, Double>> capEntries = new ArrayList<>();
            for (String sym : symbolIndex.keySet()) {
                Double cap = marketCapMap.get(sym);
                if (cap != null && cap > 0) {
                    capEntries.add(new AbstractMap.SimpleEntry<>(sym, cap));
                }
            }
            
            if (capEntries.size() >= 10) {
                // 按市值排序
                capEntries.sort(Map.Entry.comparingByValue());
                
                // 分5组
                int groupSize = Math.max(1, capEntries.size() / 5);
                Map<String, Integer> capGroup = new HashMap<>();
                for (int i = 0; i < capEntries.size(); i++) {
                    int group = Math.min(i / groupSize, 4);
                    capGroup.put(capEntries.get(i).getKey(), group);
                }
                
                // 在每个市值组内做均值归一
                for (int g = 0; g < 5; g++) {
                    final int groupId = g;
                    List<Integer> indices = capGroup.entrySet().stream()
                        .filter(e -> e.getValue() == groupId)
                        .map(e -> symbolIndex.get(e.getKey()))
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
                    
                    if (indices.size() < 3) continue;
                    
                    double sum = 0;
                    for (int idx : indices) {
                        sum += values.get(idx);
                    }
                    double mean = sum / indices.size();
                    
                    for (int idx : indices) {
                        result.set(idx, result.get(idx) - mean);
                    }
                }
                
                log.debug("[Neutralization] Market cap groups: 5, stocks with cap data: {}", capEntries.size());
            }
        }
        
        return result;
    }

    /**
     * 获取动态权重（基于IC/IR）—— IC加权综合得分（P0需求）
     *
     * 规则：
     * - 获取每个因子最近60天的IC序列，计算IC均值（保留正负号）
     * - 只取IC均值 > 0的因子参与加权（有正向预测能力的因子）
     * - 归一化：dynamicWeight = factorIC / sum(all positive ICs)
     * - IC <= 0的因子：权重置零
     * - IC < 0的因子：自动反转direction
     * - 所有IC均<=0时回退到等权，但IC<0的仍反转direction
     *
     * @param factors 因子配置列表
     * @param weightMode 权重模式：IC / IR
     * @param screenDate 选股日期
     * @return factorCode -> 动态权重系数
     */
    private Map<String, Double> getDynamicWeights(List<ScreenRequest.FactorWeight> factors, String weightMode, LocalDate screenDate) {
        Map<String, Double> dynamicWeights = new HashMap<>();
        Map<String, Double> icScores = new HashMap<>();

        // 1. 获取每个因子的IC/IR值
        for (ScreenRequest.FactorWeight fw : factors) {
            String fc = fw.getFactorCode();
            try {
                // 从 factor_ic_record 表获取最近60天的IC序列
                List<Double> icValues = factorIcService.getIcHistory(fc, screenDate, 60);
                if (icValues == null || icValues.isEmpty()) {
                    log.warn("[DynamicWeight] 因子 {} 无IC历史数据，使用默认权重1.0", fc);
                    icScores.put(fc, 1.0);
                    continue;
                }

                double score;
                if ("IR".equalsIgnoreCase(weightMode)) {
                    // IR = IC均值 / IC标准差（IR始终非负）
                    double avg = icValues.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                    double std = Math.sqrt(icValues.stream().mapToDouble(v -> Math.pow(v - avg, 2)).average().orElse(0));
                    score = std > 0 ? Math.abs(avg) / std : 0;
                } else {
                    // IC模式：使用IC均值（保留正负号，用于判断方向）
                    score = icValues.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                }
                icScores.put(fc, score);
            } catch (Exception e) {
                log.warn("[DynamicWeight] 获取因子 {} IC数据失败: {}", fc, e.getMessage());
                icScores.put(fc, 1.0);
            }
        }

        // 2. 计算IC>0的因子的IC之和
        double sumPositiveIc = icScores.values().stream()
            .filter(v -> v > 0)
            .mapToDouble(Double::doubleValue)
            .sum();

        if (sumPositiveIc > 0) {
            for (Map.Entry<String, Double> entry : icScores.entrySet()) {
                String fc = entry.getKey();
                double ic = entry.getValue();
                if (ic > 0) {
                    double normalized = ic / sumPositiveIc;
                    // 限制范围避免极端值
                    normalized = Math.max(0.1, Math.min(5.0, normalized));
                    dynamicWeights.put(fc, normalized);
                } else {
                    dynamicWeights.put(fc, 0.0);
                }
                // IC<0时反转direction
                if (ic < 0) {
                    for (ScreenRequest.FactorWeight fw : factors) {
                        if (fw.getFactorCode().equals(fc)) {
                            int oldDir = fw.getDirection();
                            fw.setDirection(-oldDir);
                            log.info("[DynamicWeight] 因子 {} IC为负({:.4f})，反转方向: {} -> {}",
                                fc, ic, oldDir, fw.getDirection());
                            break;
                        }
                    }
                }
            }
        } else {
            // 所有IC均<=0，回退到等权
            log.warn("[DynamicWeight] 所有因子IC均<=0，回退到等权，IC为负的因子反转方向");
            for (Map.Entry<String, Double> entry : icScores.entrySet()) {
                String fc = entry.getKey();
                double ic = entry.getValue();
                dynamicWeights.put(fc, 1.0);
                if (ic < 0) {
                    for (ScreenRequest.FactorWeight fw : factors) {
                        if (fw.getFactorCode().equals(fc)) {
                            fw.setDirection(-fw.getDirection());
                            break;
                        }
                    }
                }
            }
        }

        log.info("[DynamicWeight] mode={} date={} weights={}", weightMode, screenDate, dynamicWeights);
        return dynamicWeights;
    }

    /**
     * 安全构建自定义 SQL WHERE 条件
     * 1. 校验 customSqlWhere 只包含 ? 占位符（不允许字符串字面量）
     * 2. 校验字段名在白名单内
     * 3. 校验只包含安全的运算符
     * @param rawSql 用户传入的 SQL 条件（必须使用 ? 占位符）
     * @param paramCount 参数个数（用于校验 ? 数量）
     * @return 安全的 SQL 片段（直接使用，因为已校验）
     */
    private static String buildSafeCustomSql(String rawSql, int paramCount) {
        if (rawSql == null || rawSql.isBlank()) {
            throw new IllegalArgumentException("自定义SQL条件不能为空");
        }

        // 校验1：统计 ? 占位符数量
        int qmCount = 0;
        for (int i = 0; i < rawSql.length(); i++) {
            if (rawSql.charAt(i) == '?') qmCount++;
        }
        if (qmCount != paramCount) {
            throw new IllegalArgumentException("占位符 ? 数量(" + qmCount + ")与参数个数(" + paramCount + ")不匹配");
        }

        // 校验2：拒绝字符串字面量（单引号）
        if (rawSql.contains("'") || rawSql.contains("\"")) {
            throw new IllegalArgumentException("自定义SQL条件不允许包含字符串字面量，请使用 ? 占位符");
        }

        // 校验3：字段名白名单（stock_daily 表的字段）
        java.util.Set<String> allowedFields = new java.util.HashSet<>(java.util.Arrays.asList(
            "code", "name", "trade_date", "open", "high", "low", "close", "volume", "amount",
            "change_percent", "turnover_rate", "total_market_cap", "pe_ttm", "pb",
            "ma5", "ma10", "ma20", "ma60", "ma120", "ma250",
            "volatility_20", "volume_ratio", "turnover_rate_f", "ps_ttm"
        ));

        // 使用正则提取字段名（字母开头，后跟字母/数字/下划线）
        java.util.regex.Pattern fieldPat = java.util.regex.Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\b");
        java.util.regex.Matcher m = fieldPat.matcher(rawSql);
        while (m.find()) {
            String field = m.group(1);
            // 排除 SQL 关键字
            String upper = field.toUpperCase();
            if (upper.equals("AND") || upper.equals("OR") || upper.equals("NOT") ||
                upper.equals("NULL") || upper.equals("TRUE") || upper.equals("FALSE") ||
                upper.equals("SELECT") || upper.equals("FROM") || upper.equals("WHERE") ||
                upper.equals("INSERT") || upper.equals("UPDATE") || upper.equals("DELETE") ||
                upper.equals("DROP") || upper.equals("UNION") || upper.equals("JOIN")) {
                continue;
            }
            if (!allowedFields.contains(field) && !allowedFields.contains(field.toLowerCase())) {
                throw new IllegalArgumentException("自定义SQL条件包含不允许的字段名: " + field);
            }
        }

        // 校验4：只允许安全的运算符
        String upperSql = rawSql.toUpperCase();
        if (upperSql.contains("UNION") || upperSql.contains("SELECT") || upperSql.contains("FROM") ||
            upperSql.contains("DELETE") || upperSql.contains("DROP") || upperSql.contains("INSERT") ||
            upperSql.contains("UPDATE") || upperSql.contains(";") || upperSql.contains("--") ||
            upperSql.contains("/*") || upperSql.contains("*/")) {
            throw new IllegalArgumentException("自定义SQL条件包含不安全的语法");
        }

        return rawSql;
    }
}
