package com.quant.platform.screen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quant.platform.factor.domain.FactorDefinition;
import com.quant.platform.factor.domain.FactorValue;
import com.quant.platform.factor.mapper.FactorDefinitionMapper;
import com.quant.platform.factor.service.ClickHouseFactorValueService;
import com.quant.platform.market.domain.MarketDailyBar;
import com.quant.platform.market.service.MarketDataService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.recommendation.service.StockBlacklistService;
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

    private final ScreenFactorProcessor factorProcessor;

    private final ScreenDataLoader dataLoader;

    private final ScreenMathService mathService;

    private final ClickHouseFactorValueService clickHouseFactorValueService;
    private final FactorDefinitionMapper factorDefMapper;
    private final MarketDataService marketDataService;
    private final PriceAdvisorService priceAdvisorService;
    private final StrategyDefinitionMapper strategyDefMapper;
    private final ObjectMapper objectMapper;
    private final StockBlacklistService stockBlacklistService;
    private final com.quant.platform.factor.service.FactorMetaCacheService factorMetaCache;

    @Resource
    private DataSource dataSource;

    /**
     * 执行多因子选股
     */
    public ScreenResult screen(ScreenRequest req) {
        Map<String, Object> filterConfig = loadStrategyConfig(req);
        ScreenDatePlan plan = resolveScreenDate(req);
        LocalDate screenDate = plan.screenDate();
        boolean useMultiDayMode = plan.useMultiDayMode();
        LocalDate screenStartDate = plan.screenStartDate();
        LocalDate screenEndDate = plan.screenEndDate();
        CandidateBundle cb = buildCandidates(req, screenDate, useMultiDayMode, filterConfig);
        Map<String, String> codeToSymbol = cb.codeToSymbol();
        Map<String, MarketDailyBar> barMapByCode = cb.barMapByCode();
        Set<String> candidates = cb.candidates();

        FactorLoadResult flr = loadFactorData(req, screenDate, screenStartDate, screenEndDate, useMultiDayMode, candidates);
        Map<String, Map<String, FactorValue>> factorData = flr.factorData();
        Map<String, Integer> coverage = flr.coverage();

        ScoreResult sr = computeScores(req, candidates, factorData, codeToSymbol, barMapByCode, useMultiDayMode, screenDate);
        List<ScreenResult.StockScore> scores = sr.scores();
        Map<String, Integer> filterPassCount = sr.filterPassCount();

        List<ScreenResult.StockScore> topStocks = selectTopN(scores, req);
        attachPriceAdvice(topStocks, screenDate, req);

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

    public List<Map<String, Object>> getAvailableFactors() {
        return dataLoader.getAvailableFactors();
    }

    public String getLatestAvailableDate() {
        return dataLoader.getLatestAvailableDate();
    }

    // ── 私有编排方法：section 0/1/2 抽取（零行为变化）──
    private Map<String, Object> loadStrategyConfig(ScreenRequest req) {
        // ── 0. 加载策略定义因子配置 + 过滤条件 ──────────────────────────
        Long sid = req.getStrategyId();
        Map<String, Object> filterConfig = null;
        if (sid != null && (req.getFactors() == null || req.getFactors().isEmpty())) {
            StrategyDefinition strategy = strategyDefMapper.selectById(sid);
            if (strategy != null) {
                if (strategy.getFactorConfigJson() != null) {
                    try {
                        List<ScreenRequest.FactorWeight> factors = factorProcessor.parseStrategyFactorConfig(strategy.getFactorConfigJson());
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

        return filterConfig;
    }
    private ScreenDatePlan resolveScreenDate(ScreenRequest req) {
        // ── 1. 确定选股日期（支持单日 / 多日平均模式）────────────────
        LocalDate screenDate = req.getScreenDate();
        LocalDate screenStartDate = req.getScreenStartDate();
        LocalDate screenEndDate = req.getScreenEndDate();
        boolean useMultiDayMode = (screenStartDate != null && screenEndDate != null);

        // 清空多日趋势缓存 + CV 过滤缓存
        dataLoader.multiDayTrendCache.clear();
        dataLoader.multiDayUnstableCache.clear();

        // 多日平均模式下，screenDate = endDate（用于行情加载、MA计算等）
        if (useMultiDayMode) {
            screenDate = screenEndDate;
            log.info("Running stock screen in MULTI-DAY mode: range={} ~ {}, factors={}, topN={}",
                    screenStartDate, screenEndDate, req.getFactors().size(), req.getTopN());
        } else {
            if (screenDate == null) {
                screenDate = dataLoader.resolveLatestDate(req.getFactors());
            }
            log.info("Running stock screen on SINGLE date={}, factors={}, topN={}",
                    screenDate, req.getFactors().size(), req.getTopN());
        }

        return new ScreenDatePlan(screenDate, useMultiDayMode, screenStartDate, screenEndDate);
    }
    private CandidateBundle buildCandidates(ScreenRequest req, LocalDate screenDate, boolean useMultiDayMode, Map<String, Object> filterConfig) {
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

        // ── 2.0b 黑名单过滤（研究/回测场景套用当前黑名单，默认关闭）──
        if (Boolean.TRUE.equals(req.getBlacklistFilter()) && req.getStrategyId() != null) {
            Set<String> blacklist = stockBlacklistService.getActiveBlacklistCodes(req.getStrategyId());
            if (!blacklist.isEmpty()) {
                int before = candidatesRaw.size();
                candidatesRaw = candidatesRaw.stream()
                        .filter(code -> !blacklist.contains(code))
                        .collect(Collectors.toSet());
                log.info("[Screen] blacklist filter applied: {} -> {} (filtered {} blacklisted stocks)",
                        before, candidatesRaw.size(), before - candidatesRaw.size());
            }
        }

        // ── 2.0c 市场环境覆盖（仅日志记录，不影响选股逻辑）──
        if (req.getRegimeOverride() != null && !req.getRegimeOverride().isBlank()) {
            log.info("[Screen] regimeOverride={} (note: screen stage does not use regime, override is for downstream recommendation pipeline)",
                    req.getRegimeOverride());
        }

        // ── 2.1 filterConfigJson 过滤（行业排除、上市天数、自定义因子条件）──
        Set<String> candidates;
        if (filterConfig != null) {
            candidates = factorProcessor.applyFilterConfig(candidatesRaw, barMapByCode, filterConfig, screenDate);
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
                String safeSql = ScreenDataLoader.buildSafeCustomSql(rawSql, sqlParams.size());
                Set<String> sqlFiltered = new HashSet<>();
                try (Connection conn = dataSource.getConnection();
                     PreparedStatement ps = conn.prepareStatement(
                             "SELECT DISTINCT code FROM stock_daily WHERE trade_date = ? AND " + safeSql)) {
                    int paramIdx = 1;
                    ps.setString(paramIdx++, screenDate.toString());
                    for (Object p : sqlParams) {
                        switch (p) {
                            case String s -> ps.setString(paramIdx++, s);
                            case Number number ->
                                    ps.setBigDecimal(paramIdx++, BigDecimal.valueOf(number.doubleValue()));
                            case LocalDate ignored -> ps.setString(paramIdx++, p.toString());
                            case null, default -> ps.setObject(paramIdx++, p);
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

        return new CandidateBundle(codeToSymbol, barMapByCode, candidates);
    }

    private record ScreenDatePlan(
            LocalDate screenDate, boolean useMultiDayMode, LocalDate screenStartDate, LocalDate screenEndDate) {}

    private record CandidateBundle(
            Map<String, String> codeToSymbol, Map<String, MarketDailyBar> barMapByCode, Set<String> candidates) {}

    private FactorLoadResult loadFactorData(ScreenRequest req, LocalDate screenDate, LocalDate screenStartDate, LocalDate screenEndDate, boolean useMultiDayMode, Set<String> candidates) {
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
                industryMap = dataLoader.batchLoadIndustryInfo(candidateList);
            }
            if (needMarketCap) {
                marketCapMap = dataLoader.batchLoadMarketCap(candidateList, screenDate);
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
                crossSection = dataLoader.loadFactorAverage(code, screenStartDate, screenEndDate, candidates);
                log.info("[Screen] Multi-day factor {} avg: {} stocks, range={} ~ {}",
                        code, crossSection.size(), screenStartDate, screenEndDate);
            } else {
                // ── 单日模式 ──
                // P1-5: 季度因子按 announce_date 过滤，只用已发布的财报数据（DB元数据驱动）
                if (factorMetaCache.isQuarterly(code)) {
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
                    .filter(fv -> candidates.contains(ScreenMathService.normalizeFactorSymbol(fv.getSymbol())))
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
            List<Double> outlierProcessed = mathService.applyOutlierProcessing(
                    filtered.stream()
                            .map(FactorValue::getFactorVal)
                            .map(bd -> bd != null ? bd.doubleValue() : 0.0)
                            .collect(Collectors.toList()),
                    outlierMethod
            );

            // 中性化处理（在标准化之前）
            if (neutralizationMethod != null && !"NONE".equalsIgnoreCase(neutralizationMethod)) {
                outlierProcessed = factorProcessor.applyNeutralization(
                    filtered, outlierProcessed, industryMap, marketCapMap, neutralizationMethod
                );
            }

            // 标准化处理（P1-6: 优先使用因子配置的方法，回退到全局配置）
            String normalizeMethod = req.getGlobalNormalizeMethod() != null ? req.getGlobalNormalizeMethod() : "ZSCORE";
            if (factorDef != null && factorDef.getNormalizeMethod() != null) {
                normalizeMethod = factorDef.getNormalizeMethod();
            }
            List<Double> normalized = mathService.applyNormalization(outlierProcessed, normalizeMethod);

            // P1-6: 因子分布诊断（标准化后检查偏度）
            if (normalized.size() > 10) {
                double skewness = mathService.calcSkewness(normalized);
                if (Math.abs(skewness) > 2.0) {
                    log.warn("[Screen] Factor {} 标准化后偏度={}，分布严重偏斜，建议检查因子值", code, skewness);
                }
            }

            // 重新组装 FactorValue（用标准化后的值替换 rankValue）
            // key 使用归一化后的纯代码 symbol（与 candidates 格式一致）
            Map<String, FactorValue> symbolMap = new LinkedHashMap<>();
            for (int i = 0; i < filtered.size(); i++) {
                FactorValue orig = filtered.get(i);
                String normSym = ScreenMathService.normalizeFactorSymbol(orig.getSymbol());
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
            factorProcessor.applyOrthogonalization(factorData, orthoMethod);
        }

        return new FactorLoadResult(factorData, coverage);
    }

    private record FactorLoadResult(
            Map<String, Map<String, FactorValue>> factorData, Map<String, Integer> coverage) {}

    private ScoreResult computeScores(ScreenRequest req, Set<String> candidates,
            Map<String, Map<String, FactorValue>> factorData, Map<String, String> codeToSymbol,
            Map<String, MarketDailyBar> barMapByCode, boolean useMultiDayMode, LocalDate screenDate) {
        // ── 4. 筛选 + 计算综合得分 ───────────────────────────────────
        // 权重归一化（绝对值之和 = 1）
        double totalAbsWeight = req.getFactors().stream()
                .mapToDouble(fw -> Math.abs(fw.getWeight())).sum();
        if (totalAbsWeight == 0) totalAbsWeight = 1.0;

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
                    if (!mathService.passFilter(raw, fw.getFilterOp(), fw.getFilterValue())) {
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
            dynamicWeights = factorProcessor.getDynamicWeights(req.getFactors(), req.getWeightMode(), screenDate);
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
            }

            // 从 valueMap 中剥离 __rank_ 前缀的临时数据
            Map<String, Double> cleanValueMap = valueMap.entrySet().stream()
                    .filter(e -> !e.getKey().startsWith("__rank_"))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a,b) -> a, LinkedHashMap::new));

            // 多日模式：补回被 CV 过滤掉但仍有原始值的因子（不参与排名，仅用于展示）
            if (useMultiDayMode && !dataLoader.multiDayUnstableCache.isEmpty()) {
                for (ScreenRequest.FactorWeight fw : req.getFactors()) {
                    String fc = fw.getFactorCode();
                    if (cleanValueMap.containsKey(fc)) continue; // 已有排名值的跳过
                    Map<String, Double> unstableMap = dataLoader.multiDayUnstableCache.get(fc);
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
            if (useMultiDayMode && !dataLoader.multiDayTrendCache.isEmpty()) {
                factorTrends = new LinkedHashMap<>();
                for (ScreenRequest.FactorWeight fw : req.getFactors()) {
                    Map<String, Double> tMap = dataLoader.multiDayTrendCache.get(fw.getFactorCode());
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

        return new ScoreResult(scores, filterPassCount);
    }

    private record ScoreResult(
            List<ScreenResult.StockScore> scores, Map<String, Integer> filterPassCount) {}

    private List<ScreenResult.StockScore> selectTopN(List<ScreenResult.StockScore> scores, ScreenRequest req) {
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

        return topStocks;
    }
    private void attachPriceAdvice(List<ScreenResult.StockScore> topStocks, LocalDate screenDate, ScreenRequest req) {
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
                        stock.setCurrentPrice(mathService.toBD(advice.get("currentPrice")));
                        stock.setSuggestPrice(mathService.toBD(advice.get("suggestPrice")));
                        stock.setSuggestPriceLow(mathService.toBD(advice.get("suggestPriceLow")));
                        stock.setSuggestPriceHigh(mathService.toBD(advice.get("suggestPriceHigh")));
                        stock.setStopLoss(mathService.toBD(advice.get("stopLoss")));
                        stock.setStopLossPercent(mathService.toBD(advice.get("stopLossPercent")));
                        stock.setTakeProfit1(mathService.toBD(advice.get("takeProfit1")));
                        stock.setTakeProfit1Percent(mathService.toBD(advice.get("takeProfit1Percent")));
                        stock.setTakeProfit2(mathService.toBD(advice.get("takeProfit2")));
                        stock.setTakeProfit2Percent(mathService.toBD(advice.get("takeProfit2Percent")));
                        stock.setAtr(mathService.toBD(advice.get("atr")));
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

    }
}
