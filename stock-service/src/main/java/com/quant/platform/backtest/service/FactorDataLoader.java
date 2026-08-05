package com.quant.platform.backtest.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import static com.quant.platform.backtest.service.OlsRegressionCalculator.*;
import static com.quant.platform.backtest.service.FactorStyleAttributionService.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.common.exception.BusinessException;
import com.quant.platform.config.ClickHouseConfig;
import com.quant.platform.strategy.domain.StrategyDefinition;
import com.quant.platform.strategy.mapper.StrategyDefinitionMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;
import java.time.LocalDate;

@Slf4j
@Service
@RequiredArgsConstructor
public class FactorDataLoader {

    private final ClickHouseConfig clickHouseConfig;
    private final JdbcTemplate jdbcTemplate;
    private final StrategyDefinitionMapper strategyMapper;
    private final ObjectMapper objectMapper;

    // ════════════════════════════════════════════════════════════════
    // 内部方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 计算每日因子收益（多空组合：Top 20% 等权收益 − Bottom 20% 等权收益）
     */
    public Map<LocalDate, Map<String, Double>> computeFactorDailyReturns(
            LocalDate startDate, LocalDate endDate, List<FactorDef> factors) {

        if (!clickHouseConfig.isEnabled()) {
            throw new BusinessException("ClickHouse 不可用，因子风格归因需要 CH 因子数据");
        }

        String placeholders = factors.stream().map(f -> "?").collect(Collectors.joining(","));

        String sql = String.format("""
                SELECT fv.calc_date, fv.factor_code,
                       replaceRegexpOne(fv.symbol, '\\\\.[A-Z]+$', '') AS code,
                       fv.factor_val,
                       sd.close_price / sd.pre_close - 1 AS daily_ret
                FROM (SELECT symbol, calc_date, factor_code, factor_val
                      FROM stock.factor_value FINAL) AS fv
                INNER JOIN (SELECT code, trade_date, close_price, pre_close
                            FROM stock.stock_daily FINAL) AS sd
                  ON replaceRegexpOne(fv.symbol, '\\\\.[A-Z]+$', '') = sd.code
                  AND fv.calc_date = sd.trade_date
                WHERE fv.factor_code IN (%s)
                  AND fv.calc_date >= ?
                  AND fv.calc_date <= ?
                  AND fv.factor_val IS NOT NULL
                  AND sd.pre_close > 0
                  AND sd.close_price > 0
                ORDER BY fv.calc_date, fv.factor_code, fv.factor_val
                """, placeholders);

        // date → factor_code → List of {factor_val, daily_ret}
        Map<LocalDate, Map<String, List<double[]>>> rawData = new LinkedHashMap<>();

        try (Connection conn = clickHouseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            int idx = 1;
            for (FactorDef f : factors) {
                ps.setString(idx++, f.code());
            }
            ps.setDate(idx++, java.sql.Date.valueOf(startDate));
            ps.setDate(idx++, java.sql.Date.valueOf(endDate));

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    LocalDate d = rs.getDate("calc_date").toLocalDate();
                    String factorCode = rs.getString("factor_code");
                    String code = rs.getString("code");

                    // 排除北交所(code 以 8 开头)、B 股(2 开头）、ST等
                    if (code.startsWith("8") || code.startsWith("4") || code.startsWith("2")) continue;

                    double factorVal = rs.getDouble("factor_val");
                    double dailyRet = rs.getDouble("daily_ret");
                    if (rs.wasNull() || Double.isNaN(dailyRet)) continue;

                    rawData.computeIfAbsent(d, k -> new HashMap<>())
                            .computeIfAbsent(factorCode, k -> new ArrayList<>())
                            .add(new double[]{factorVal, dailyRet});
                }
            }
        } catch (Exception e) {
            log.error("CH 因子收益查询失败: {}", e.getMessage(), e);
            throw new BusinessException("ClickHouse 因子数据查询失败: " + e.getMessage());
        }

        if (rawData.isEmpty()) {
            throw new BusinessException("CH 中无因子收益数据，请确认 factor_value 和 stock_daily 表有交集");
        }

        // 对每个因子每天：按 factor_val 排序 → 分5组 → Top 20% 等权收益 − Bottom 20% 等权收益
        Map<LocalDate, Map<String, Double>> result = new LinkedHashMap<>();
        int minTotalStocks = 200;

        for (Map.Entry<LocalDate, Map<String, List<double[]>>> dateEntry : rawData.entrySet()) {
            LocalDate date = dateEntry.getKey();
            Map<String, List<double[]>> factorData = dateEntry.getValue();
            Map<String, Double> dayResult = new LinkedHashMap<>();
            boolean dayValid = false;

            for (FactorDef fd : factors) {
                List<double[]> rows = factorData.get(fd.code());
                if (rows == null || rows.size() < minTotalStocks) continue;

                rows.sort(Comparator.comparingDouble(a -> a[0]));
                int n = rows.size();
                int qSize = n / QUINTILE;
                if (qSize < 10) continue;

                double topReturn = 0;
                for (int i = n - qSize; i < n; i++) topReturn += rows.get(i)[1];
                topReturn /= qSize;

                double bottomReturn = 0;
                for (int i = 0; i < qSize; i++) bottomReturn += rows.get(i)[1];
                bottomReturn /= qSize;

                double factorReturn = topReturn - bottomReturn;
                dayResult.put(fd.code(), round4(factorReturn));
                dayValid = true;
            }

            if (dayValid) result.put(date, dayResult);
        }

        log.info("因子日收益计算完成: 覆盖 {} 个交易日 ({} ~ {})",
                result.size(), startDate, endDate);
        return result;
    }

    // ════════════════════════════════════════════════════════════════
    // A3: FF3 因子日收益计算（MKT/SMB/HML）
    // ════════════════════════════════════════════════════════════════

    /**
     * 计算 FF3 标准三因子的每日多空收益，结果存入 factor_premium 表。
     */
    public Map<LocalDate, Map<String, Double>> computeFF3FactorReturns(
            LocalDate startDate, LocalDate endDate) {

        if (!clickHouseConfig.isEnabled())
            throw new BusinessException("ClickHouse 不可用");

        // 1. 从 CH 加载 stock_daily 日收益
        String sql = String.format("""
                SELECT code, trade_date, close_price / pre_close - 1 AS daily_ret
                FROM stock.stock_daily FINAL
                WHERE trade_date >= '%s' AND trade_date <= '%s'
                  AND pre_close > 0 AND close_price > 0
                ORDER BY trade_date
                """, startDate, endDate);

        Map<LocalDate, Map<String, Double>> dailyRetsByDate = new LinkedHashMap<>();
        try (Connection conn = clickHouseConfig.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.setFetchSize(50000);
            try (ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    String code = rs.getString("code");
                    if (code.startsWith("8") || code.startsWith("4") || code.startsWith("2")) continue; // 排除北交所/B股
                    LocalDate d = rs.getDate("trade_date").toLocalDate();
                    double ret = rs.getDouble("daily_ret");
                    if (rs.wasNull() || Double.isNaN(ret)) continue;
                    dailyRetsByDate.computeIfAbsent(d, k -> new HashMap<>()).put(code, ret);
                }
            }
        } catch (Exception e) {
            log.error("CH stock_daily 查询失败: {}", e.getMessage(), e);
            throw new BusinessException("CH 行情查询失败: " + e.getMessage());
        }

        // 2. 从 MySQL stock_info 加载市值和 PB
        Map<String, double[]> stockInfoMap = new HashMap<>(); // code -> [total_market_cap, pb]
        try {
            jdbcTemplate.query(
                    "SELECT code, total_market_cap, pb FROM stock_info WHERE total_market_cap IS NOT NULL",
                    (rs) -> {
                        String code = rs.getString("code");
                        double mcap = rs.getDouble("total_market_cap");
                        double pb = rs.getDouble("pb");
                        if (!rs.wasNull() && mcap > 0) {
                            stockInfoMap.put(code, new double[]{mcap, rs.wasNull() ? 0 : pb});
                        }
                    });
        } catch (Exception e) {
            log.warn("MySQL stock_info 查询失败: {}", e.getMessage());
        }

        log.info("FF3 因子计算: CH daily数据 {}天, MySQL stock_info {}只",
                dailyRetsByDate.size(), stockInfoMap.size());

        // 3. 逐日计算三个因子
        Map<LocalDate, Map<String, Double>> result = new LinkedHashMap<>();
        int minStocks = 200;

        for (Map.Entry<LocalDate, Map<String, Double>> entry : dailyRetsByDate.entrySet()) {
            LocalDate date = entry.getKey();
            Map<String, Double> dayRets = entry.getValue();

            // 过滤：仅保留 stock_info 中有市值数据的股票
            List<StockDayData> dayData = new ArrayList<>();
            for (Map.Entry<String, Double> e : dayRets.entrySet()) {
                double[] info = stockInfoMap.get(e.getKey());
                if (info == null) continue;
                dayData.add(new StockDayData(e.getKey(), e.getValue(), info[0], info[1]));
            }
            if (dayData.size() < minStocks) continue;

            // MKT: 全市场等权收益
            double mkt = dayData.stream().mapToDouble(d -> d.dailyRet()).average().orElse(0);

            // SMB: 按市值排序，底30% vs 顶30%
            dayData.sort(Comparator.comparingDouble(a -> a.marketCap()));
            int n = dayData.size(), q = n / 3; // 30%
            if (q < 10) continue;
            double smbTop = 0, smbBot = 0;
            for (int i = 0; i < q; i++) smbBot += dayData.get(i).dailyRet();
            for (int i = n - q; i < n; i++) smbTop += dayData.get(i).dailyRet();
            double smb = smbBot / q - smbTop / q;

            // HML: 按 PB 排序（PB 越小=越价值），底30%(低PB/价值) vs 顶30%(高PB/成长)
            // PB <= 0 的排到最末尾（视为不可比）
            dayData.sort((a, b) -> {
                if (a.pb() <= 0 && b.pb() <= 0) return 0;
                if (a.pb() <= 0) return 1;
                if (b.pb() <= 0) return -1;
                return Double.compare(a.pb(), b.pb());
            });
            int validN = (int) dayData.stream().filter(d -> d.pb() > 0).count();
            if (validN < q * 2) continue;

            double hmlLow = 0, hmlHigh = 0;
            for (int i = 0; i < q; i++) hmlLow += dayData.get(i).dailyRet();
            for (int i = Math.max(validN - q, 0); i < validN; i++) hmlHigh += dayData.get(i).dailyRet();
            double hml = hmlLow / q - hmlHigh / q;

            Map<String, Double> dayResult = new LinkedHashMap<>();
            dayResult.put("MKT", round4(mkt));
            dayResult.put("SMB", round4(smb));
            dayResult.put("HML", round4(hml));
            result.put(date, dayResult);
        }

        log.info("FF3 因子计算完成: {} 个交易日 ({} ~ {})",
                result.size(), startDate, endDate);

        // 持久化到 ClickHouse factor_premium 表
        saveFactorPremiumToCH(result);

        return result;
    }

    /**
     * 将 FF3 因子日收益写入 ClickHouse factor_premium 表。
     * 使用 ReplacingMergeTree，按 (factor_code, calc_date) 去重。
     */
    public void saveFactorPremiumToCH(Map<LocalDate, Map<String, Double>> factorReturns) {
        if (!clickHouseConfig.isEnabled()) return;

        String sql = """
            INSERT INTO stock.factor_premium
            (factor_code, calc_date, factor_return, stock_count, top_return, bottom_return, created_at, update_time)
            VALUES (?, ?, ?, ?, ?, ?, now(), now())
            """;

        try (Connection conn = clickHouseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            for (Map.Entry<LocalDate, Map<String, Double>> entry : factorReturns.entrySet()) {
                LocalDate date = entry.getKey();
                Map<String, Double> dayMap = entry.getValue();
                int idx = 0;
                for (String factorCode : List.of("MKT", "SMB", "HML")) {
                    Double ret = dayMap.get(factorCode);
                    if (ret == null) continue;
                    idx++;
                    ps.setString(1, factorCode);
                    ps.setObject(2, date);
                    ps.setDouble(3, ret);
                    // stock_count: MKT用全市场，SMB/HML用分组(无精确值，用0占位)
                    ps.setInt(4, "MKT".equals(factorCode) ? dayMap.size() : 0);
                    // top/bottom return: SMB=S(小市值)-B(大市值); HML=V(价值)-G(成长)
                    // 这里直接存 factor_return，精确的top/bottom已含在因子定义中
                    ps.setDouble(5, 0);  // top_return 占位
                    ps.setDouble(6, 0);  // bottom_return 占位
                    ps.addBatch();
                }
            }
            int[] counts = ps.executeBatch();
            log.info("[FactorStyle] factor_premium 写入 CH: {} 条记录", counts.length);
        } catch (Exception e) {
            log.warn("[FactorStyle] factor_premium 写入 CH 失败: {}", e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 因子加载
    // ════════════════════════════════════════════════════════════════

    /**
     * 从策略 factorConfigJson 读取因子列表，解析为 FactorDef。
     * 无配置时回退到默认3因子（兜底）。
     */
    public List<FactorDef> loadStrategyFactors(Long strategyId) {
        if (strategyId == null) {
            log.info("无策略ID，使用默认因子集");
            return DEFAULT_FACTORS;
        }

        StrategyDefinition strategy = strategyMapper.selectById(strategyId);
        if (strategy == null || strategy.getFactorConfigJson() == null
                || strategy.getFactorConfigJson().isBlank()) {
            log.info("策略 {} 无 factorConfigJson，使用默认因子集", strategyId);
            return DEFAULT_FACTORS;
        }

        List<String> factorCodes;
        try {
            // 解析 factorConfigJson: {"factors":[{"code":"MOM20","weight":1.0}]}
            List<Map<String, Object>> factorList = objectMapper.readValue(
                    strategy.getFactorConfigJson(),
                    new TypeReference<>() {
                    });
            if (factorList == null || factorList.isEmpty()) {
                factorCodes = List.of();
            } else {
                factorCodes = factorList.stream()
                        .map(m -> (String) m.get("code"))
                        .filter(Objects::nonNull)
                        .filter(c -> !c.isBlank())
                        .distinct()
                        .collect(Collectors.toList());
            }
        } catch (Exception e) {
            // 可能不是数组而是对象 {"factors": [...]}
            try {
                Map<String, Object> root = objectMapper.readValue(
                        strategy.getFactorConfigJson(), Map.class);
                Object factorsObj = root.get("factors");
                if (factorsObj instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> factorList = (List<Map<String, Object>>) factorsObj;
                    factorCodes = factorList.stream()
                            .map(m -> (String) m.get("code"))
                            .filter(Objects::nonNull)
                            .filter(c -> !c.isBlank())
                            .distinct()
                            .collect(Collectors.toList());
                } else {
                    factorCodes = List.of();
                }
            } catch (Exception e2) {
                log.warn("解析策略 {} 的 factorConfigJson 失败: {}", strategyId, e2.getMessage());
                return DEFAULT_FACTORS;
            }
        }

        if (factorCodes.isEmpty()) {
            log.info("策略 {} 因子配置为空，使用默认因子集", strategyId);
            return DEFAULT_FACTORS;
        }

        // 加载因子名称
        Map<String, String> nameMap = loadFactorNames(factorCodes);
        
        List<FactorDef> result = new ArrayList<>();
        for (String code : factorCodes) {
            String name = nameMap.getOrDefault(code, code);
            result.add(new FactorDef(code, name, code + "因子"));
        }
        
        log.info("策略 {} 加载到 {} 个因子: {}", strategyId, result.size(),
                result.stream().map(FactorDef::code).collect(Collectors.joining(",")));
        return result;
    }

    /**
     * 批量加载因子名称（参数化查询，防SQL注入）
     */
    public Map<String, String> loadFactorNames(List<String> codes) {
        if (codes.isEmpty()) return Map.of();
        Map<String, String> map = new LinkedHashMap<>();
        try {
            String placeholders = codes.stream().map(c -> "?").collect(Collectors.joining(","));
            String sql = "SELECT factor_code, factor_name FROM factor_definition WHERE factor_code IN (" + placeholders + ")";
            jdbcTemplate.query(sql, codes.toArray(), (rs) -> {
                map.put(rs.getString("factor_code"), rs.getString("factor_name"));
            });
        } catch (Exception e) {
            log.warn("加载因子名称失败: {}", e.getMessage());
        }
        return map;
    }
}
