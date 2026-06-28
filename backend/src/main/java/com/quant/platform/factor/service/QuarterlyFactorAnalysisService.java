package com.quant.platform.factor.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * P4: 财务因子季频评估服务
 *
 * 财务因子（FIN_ROE_TTM, FIN_OPMARGIN 等）只在财报季更新，
 * 用日频IC评估会造成大量重复数据点，导致IC虚高/虚低。
 * 本服务专为财务因子提供季频IC计算。
 *
 * 核心逻辑：
 * 1. 只在季末日期（03-31/06-30/09-30/12-31）取因子值
 * 2. 可选：考虑财报公告日漂移（公告日+5个交易日后的收益）
 * 3. 与日频IC对比，识别"假有效"因子
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuarterlyFactorAnalysisService {

    private final JdbcTemplate jdbcTemplate; // MySQL
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.beans.factory.annotation.Qualifier("clickHouseJdbcTemplate")
    private JdbcTemplate clickHouseJdbcTemplate;
    private final ClickHouseFactorValueService clickHouseFactorValueService;

    /** 财务因子前缀 */
    private static final String FIN_PREFIX = "FIN_";

    /** 季末月份 */
    private static final int[] QUARTER_END_MONTHS = {3, 6, 9, 12};
    private static final int[] QUARTER_END_DAYS = {31, 30, 30, 31};

    /** 公告日后漂移天数（考虑市场消化时间） */
    private static final int ANNOUNCEMENT_DRIFT_DAYS = 5;

    /**
     * 季频因子IC快照
     */
    public static class QuarterlyIcResult {
        public String factorCode;
        public double icMean;           // 季频IC均值
        public double icStd;            // 季频IC标准差
        public double ir;               // 季频IR
        public int quarterCount;        // 有效季度数
        public double dailyIcMean;      // 日频IC均值（对比用）
        public boolean isInflated;      // 日频IC是否虚高（日频IC > 季频IC * 1.5）
        public String assessment;       // 评估结论
        public List<Map<String, Object>> quarterDetails; // 每季度IC详情
    }

    /**
     * 判断是否为财务因子
     */
    public boolean isFinancialFactor(String factorCode) {
        return factorCode != null && factorCode.startsWith(FIN_PREFIX);
    }

    /**
     * 获取策略因子列表中的财务因子
     */
    public List<String> extractFinancialFactors(List<String> factorCodes) {
        return factorCodes.stream()
                .filter(this::isFinancialFactor)
                .collect(Collectors.toList());
    }

    /**
     * 获取季末日期列表（在指定范围内）
     */
    public List<LocalDate> getQuarterEndDates(LocalDate startDate, LocalDate endDate) {
        List<LocalDate> dates = new ArrayList<>();
        LocalDate current = LocalDate.of(startDate.getYear(), 1, 1);

        while (!current.isAfter(endDate)) {
            for (int q = 0; q < 4; q++) {
                int month = QUARTER_END_MONTHS[q];
                int day = QUARTER_END_DAYS[q];
                LocalDate qEnd;
                try {
                    qEnd = LocalDate.of(current.getYear(), month, day);
                } catch (Exception e) {
                    // 处理月末日期无效的情况（如2月30日）
                    qEnd = LocalDate.of(current.getYear(), month, 1)
                            .plusMonths(1).minusDays(1);
                }
                if (!qEnd.isBefore(startDate) && !qEnd.isAfter(endDate)) {
                    dates.add(qEnd);
                }
            }
            current = current.plusYears(1);
        }

        return dates;
    }

    /**
     * P4: 计算财务因子季频IC
     *
     * @param factorCode        因子代码
     * @param startDate         开始日期
     * @param endDate           结束日期
     * @param forwardDays       前瞻天数（默认20，约1个月）
     * @param useAnnouncementDrift 是否考虑公告日漂移
     * @return 季频IC结果
     */
    public QuarterlyIcResult computeQuarterlyIc(
            String factorCode, LocalDate startDate, LocalDate endDate,
            int forwardDays, boolean useAnnouncementDrift) {

        QuarterlyIcResult result = new QuarterlyIcResult();
        result.factorCode = factorCode;

        if (!isFinancialFactor(factorCode)) {
            log.warn("[QuarterlyIC] {} 不是财务因子，跳过季频分析", factorCode);
            result.icMean = 0;
            result.assessment = "非财务因子";
            return result;
        }

        // 1. 获取季末日期
        List<LocalDate> quarterEnds = getQuarterEndDates(startDate, endDate);
        if (quarterEnds.size() < 3) {
            log.warn("[QuarterlyIC] {} 季度数据不足 ({})，至少需要3个季度", factorCode, quarterEnds.size());
            result.icMean = 0;
            result.quarterCount = quarterEnds.size();
            result.assessment = "季度数据不足";
            return result;
        }

        // 2. 获取每季度的因子值（从ClickHouse取季末日期的因子值）
        List<Map<String, Object>> quarterDetails = new ArrayList<>();
        List<Double> quarterIcs = new ArrayList<>();

        for (int qi = 0; qi < quarterEnds.size() - 1; qi++) {
            LocalDate qDate = quarterEnds.get(qi);
            LocalDate nextQDate = quarterEnds.get(qi + 1);

            try {
                // 获取该季度因子截面值
                Map<String, Double> factorValues = getFactorCrossSection(factorCode, qDate);
                if (factorValues == null || factorValues.size() < 10) {
                    continue;
                }

                // 获取未来收益（季末到下季末，或使用公告日漂移）
                LocalDate returnStart;
                if (useAnnouncementDrift) {
                    // 寻找最近的财报公告日
                    returnStart = findNearestAnnouncementDate(factorCode, qDate);
                    if (returnStart == null) {
                        returnStart = qDate.plusDays(1);
                    }
                } else {
                    returnStart = qDate.plusDays(1);
                }
                LocalDate returnEnd = nextQDate;

                // 计算截面IC
                Map<String, Double> returns = getReturns(factorValues.keySet(), returnStart, returnEnd);
                if (returns.size() < 10) continue;

                double ic = computeSpearmanIc(factorValues, returns);
                if (!Double.isNaN(ic)) {
                    quarterIcs.add(ic);

                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("quarterDate", qDate.toString());
                    detail.put("returnStart", returnStart.toString());
                    detail.put("returnEnd", returnEnd.toString());
                    detail.put("ic", Math.round(ic * 10000.0) / 10000.0);
                    detail.put("sampleSize", factorValues.size());
                    quarterDetails.add(detail);
                }
            } catch (Exception e) {
                log.debug("[QuarterlyIC] {} Q{} 计算失败: {}", factorCode, qDate, e.getMessage());
            }
        }

        // 3. 计算统计量
        result.quarterCount = quarterIcs.size();
        result.quarterDetails = quarterDetails;

        if (quarterIcs.isEmpty()) {
            result.icMean = 0;
            result.assessment = "无有效IC数据";
            return result;
        }

        result.icMean = quarterIcs.stream().mapToDouble(d -> d).average().orElse(0);
        result.icStd = calcStd(quarterIcs);
        result.ir = result.icStd > 1e-9 ? result.icMean / result.icStd : 0;

        // 4. 评估
        double absIc = Math.abs(result.icMean);
        double absIr = Math.abs(result.ir);
        if (absIc >= 0.10 && absIr >= 0.5) result.assessment = "有效财务因子";
        else if (absIc >= 0.05 && absIr >= 0.3) result.assessment = "弱有效";
        else result.assessment = "无效/不稳定";

        // 5. 与日频IC对比（日频IC数据从推荐引擎获取，此处仅做标记）
        // 日频IC虚高判断留待推荐引擎调用时进行
        result.dailyIcMean = 0;
        result.isInflated = false;

        log.info("[QuarterlyIC] {} 季频: IC={:.4f}, IR={:.3f}, 季度={}, 日频IC={:.4f}, 虚高={}",
                factorCode, result.icMean, result.ir, result.quarterCount,
                result.dailyIcMean, result.isInflated);

        return result;
    }

    /**
     * 批量计算财务因子季频IC
     */
    public List<QuarterlyIcResult> batchComputeQuarterlyIc(
            List<String> factorCodes, LocalDate startDate, LocalDate endDate,
            int forwardDays, boolean useAnnouncementDrift) {

        List<String> finFactors = extractFinancialFactors(factorCodes);
        if (finFactors.isEmpty()) {
            log.info("[QuarterlyIC] 无财务因子，跳过批量计算");
            return Collections.emptyList();
        }

        log.info("[QuarterlyIC] 批量计算: {}个财务因子, {}-{}, forward={}, drift={}",
                finFactors.size(), startDate, endDate, forwardDays, useAnnouncementDrift);

        List<QuarterlyIcResult> results = new ArrayList<>();
        for (String fc : finFactors) {
            try {
                QuarterlyIcResult r = computeQuarterlyIc(fc, startDate, endDate,
                        forwardDays, useAnnouncementDrift);
                results.add(r);
            } catch (Exception e) {
                log.error("[QuarterlyIC] 因子 {} 计算失败: {}", fc, e.getMessage());
            }
        }

        // 按|IC|降序
        results.sort((a, b) -> Double.compare(Math.abs(b.icMean), Math.abs(a.icMean)));

        return results;
    }

    // ======== 辅助方法 ========

    /** 获取指定日期的因子截面值（直接查ClickHouse） */
    private Map<String, Double> getFactorCrossSection(String factorCode, LocalDate date) {
        if (clickHouseJdbcTemplate == null) return Collections.emptyMap();
        try {
            String sql = "SELECT symbol, value FROM stock.factor_value " +
                    "WHERE factor_code = ? AND calc_date = ?";
            List<Map<String, Object>> rows = clickHouseJdbcTemplate.queryForList(
                    sql, factorCode, date.toString());
            Map<String, Double> result = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                Object code = row.get("symbol");
                Object val = row.get("value");
                if (code != null && val instanceof Number) {
                    result.put(code.toString(), ((Number) val).doubleValue());
                }
            }
            return result;
        } catch (Exception e) {
            log.debug("[QuarterlyIC] CH查询失败: {} date={}, {}", factorCode, date, e.getMessage());
            return Collections.emptyMap();
        }
    }

    /** 计算股票区间收益率 */
    private Map<String, Double> getReturns(Set<String> codes, LocalDate start, LocalDate end) {
        if (codes.isEmpty()) return Collections.emptyMap();
        
        // 直接从ClickHouse查询区间收益
        try {
            List<String> codeList = new ArrayList<>(codes);
            // 分批查询（ClickHouse IN子句限制）
            Map<String, Double> allRet = new LinkedHashMap<>();
            int batchSize = 500;
            for (int i = 0; i < codeList.size(); i += batchSize) {
                int endIdx = Math.min(i + batchSize, codeList.size());
                List<String> batch = codeList.subList(i, endIdx);
                String placeholders = batch.stream().map(c -> "?").collect(Collectors.joining(","));

                String sql = "SELECT symbol, (argMax(close, trade_date) - argMin(close, trade_date)) / argMin(close, trade_date) AS ret " +
                    "FROM stock_daily WHERE symbol IN (" + placeholders + ") AND trade_date BETWEEN ? AND ? " +
                    "GROUP BY symbol HAVING ret IS NOT NULL";

                // 构建参数数组：[batch codes..., start, end]
                Object[] args = new Object[batch.size() + 2];
                for (int j = 0; j < batch.size(); j++) {
                    args[j] = batch.get(j);
                }
                args[batch.size()] = start.toString();
                args[batch.size() + 1] = end.toString();

                List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args);
                for (Map<String, Object> row : rows) {
                    String code = (String) row.get("symbol");
                    Object retVal = row.get("ret");
                    if (retVal instanceof Number && code != null) {
                        allRet.put(code, ((Number) retVal).doubleValue());
                    }
                }
            }
            return allRet;
        } catch (Exception e) {
            log.debug("[QuarterlyIC] 收益查询失败: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /** 计算Spearman秩相关 */
    private double computeSpearmanIc(Map<String, Double> factorValues, Map<String, Double> returns) {
        List<String> commonCodes = new ArrayList<>();
        List<Double> fvList = new ArrayList<>();
        List<Double> retList = new ArrayList<>();

        for (Map.Entry<String, Double> e : factorValues.entrySet()) {
            String code = e.getKey();
            Double ret = returns.get(code);
            if (ret != null && !Double.isNaN(ret)) {
                commonCodes.add(code);
                fvList.add(e.getValue());
                retList.add(ret);
            }
        }

        if (commonCodes.size() < 10) return Double.NaN;

        // 计算排名
        double[] fRanks = rank(fvList);
        double[] rRanks = rank(retList);
        int n = commonCodes.size();

        // Spearman = Pearson on ranks
        double sumFR = 0, sumF = 0, sumR = 0, sumF2 = 0, sumR2 = 0;
        for (int i = 0; i < n; i++) {
            sumF += fRanks[i];
            sumR += rRanks[i];
            sumFR += fRanks[i] * rRanks[i];
            sumF2 += fRanks[i] * fRanks[i];
            sumR2 += rRanks[i] * rRanks[i];
        }

        double num = n * sumFR - sumF * sumR;
        double den = Math.sqrt((n * sumF2 - sumF * sumF) * (n * sumR2 - sumR * sumR));
        return den > 1e-9 ? num / den : 0;
    }

    /** 排名（平均排名法处理并列） */
    private double[] rank(List<Double> values) {
        int n = values.size();
        Integer[] indices = new Integer[n];
        for (int i = 0; i < n; i++) indices[i] = i;
        Arrays.sort(indices, Comparator.comparingDouble(values::get));

        double[] ranks = new double[n];
        for (int i = 0; i < n; ) {
            int j = i;
            while (j < n && values.get(indices[j]).equals(values.get(indices[i]))) j++;
            double avgRank = (i + j - 1) / 2.0 + 1;
            for (int k = i; k < j; k++) ranks[indices[k]] = avgRank;
            i = j;
        }
        return ranks;
    }

    /** 标准差 */
    private double calcStd(List<Double> values) {
        double mean = values.stream().mapToDouble(d -> d).average().orElse(0);
        double sumSq = 0;
        for (double v : values) sumSq += (v - mean) * (v - mean);
        return Math.sqrt(sumSq / (values.size() - 1));
    }

    /** 寻找最近的财报公告日（从MySQL stock_earnings_report表） */
    private LocalDate findNearestAnnouncementDate(String factorCode, LocalDate quarterEnd) {
        // 财报公告日期从stock_earnings_report表获取
        try {
            String sql = "SELECT MIN(announce_date) FROM stock_earnings_report " +
                    "WHERE report_date >= ? AND announce_date > ? " +
                    "ORDER BY announce_date LIMIT 1";
            // quarterEnd对应季末日期
            java.sql.Date result = jdbcTemplate.queryForObject(sql,
                    java.sql.Date.class, quarterEnd, quarterEnd);
            if (result != null) {
                LocalDate announceDate = result.toLocalDate();
                // 公告日后偏移ANNOUNCEMENT_DRIFT_DAYS个自然日
                return announceDate.plusDays(ANNOUNCEMENT_DRIFT_DAYS);
            }
        } catch (Exception e) {
            log.debug("[QuarterlyIC] 公告日查询失败: quarterEnd={}, {}", quarterEnd, e.getMessage());
        }
        return null;
    }
}
