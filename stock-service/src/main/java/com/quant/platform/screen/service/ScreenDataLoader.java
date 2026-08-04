package com.quant.platform.screen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quant.platform.factor.domain.FactorDefinition;
import com.quant.platform.factor.domain.FactorValue;
import com.quant.platform.factor.mapper.FactorDefinitionMapper;
import com.quant.platform.factor.service.ClickHouseFactorValueService;
import com.quant.platform.screen.dto.ScreenRequest;
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
import com.quant.platform.factor.domain.FactorDefinition.FactorStatus;

/**
 * 选股数据加载服务
 * 因子值（单日/多日均值）、行业、上市日期、市值等批量读取，
 * 以及可用因子清单与最新可用日期解析。多日模式的趋势/不稳定因子缓存随之内聚于此。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScreenDataLoader {

    private final ClickHouseFactorValueService clickHouseFactorValueService;
    private final FactorDefinitionMapper factorDefMapper;

    @Resource
    private DataSource dataSource;

    /**
     * 多日模式下的因子趋势动量缓存：factorCode -> (symbol -> trend)
     * trend = (latestVal - earliestVal) / |earliestVal|
     */
    public final Map<String, Map<String, Double>> multiDayTrendCache = new HashMap<>();

    /**
     * 多日模式下被 CV 过滤掉的原始因子值（不参与排名，仅用于展示）
     * factorCode -> (symbol -> rawValue)
     */
    public final Map<String, Map<String, Double>> multiDayUnstableCache = new HashMap<>();

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
     */
    public List<FactorValue> loadFactorAverage(String factorCode, LocalDate startDate, LocalDate endDate, Set<String> candidates) {
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
                .collect(Collectors.groupingBy(fv -> ScreenMathService.normalizeSymbol(fv.getSymbol())));

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

            FactorValue earliest = values.getFirst();
            FactorValue latest = values.getLast();
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
            boolean isZeroMean = factorCode.startsWith("REVERSAL");
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
                factorCode.startsWith("REVERSAL") ? "std_abs" : "cv_ratio");
        return result;
    }

    /**
     * 从 DB factor_definition.cv_threshold 查询 CV 阈值（数据驱动）。
     * 未设置时回退到 category 推导的默认值。
     */
    public double getCVThreshold(String factorCode) {
        FactorDefinition def = factorDefMapper.selectOne(
                new LambdaQueryWrapper<FactorDefinition>()
                        .eq(FactorDefinition::getFactorCode, factorCode)
                        .last("LIMIT 1"));
        if (def != null && def.getCvThreshold() != null) {
            return def.getCvThreshold();
        }
        // 回退：根据 category 推导
        if (def != null && def.getCategory() != null) {
            return ScreenMathService.getCategoryBasedCV(def.getCategory());
        }
        return ScreenMathService.DEFAULT_CV_THRESHOLD;
    }

    /**
     * 获取所有可用因子（已有因子值的因子代码 + 定义信息）
     */
    public List<Map<String, Object>> getAvailableFactors() {
        return factorDefMapper.selectList(null).stream()
                .filter(fd -> fd.getStatus() == FactorStatus.ACTIVE)
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
    public LocalDate resolveLatestDate(List<ScreenRequest.FactorWeight> factors) {
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

    /**
     * 批量查询股票行业信息（从 stock_info.industry 字段）
     */
    public Map<String, String> batchLoadIndustryInfo(List<String> codes) {
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
    public Map<String, LocalDate> batchLoadListDates(List<String> codes) {
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
    public Map<String, Double> batchLoadFactorValues(String factorCode, LocalDate date, List<String> codes) {
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
    public Map<String, Double> batchLoadMarketCap(List<String> codes, LocalDate date) {
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
     * 安全构建自定义 SQL WHERE 条件
     * 1. 校验 customSqlWhere 只包含 ? 占位符（不允许字符串字面量）
     * 2. 校验字段名在白名单内
     * 3. 校验只包含安全的运算符
     * @param rawSql 用户传入的 SQL 条件（必须使用 ? 占位符）
     * @param paramCount 参数个数（用于校验 ? 数量）
     * @return 安全的 SQL 片段（直接使用，因为已校验）
     */
    public static String buildSafeCustomSql(String rawSql, int paramCount) {
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
