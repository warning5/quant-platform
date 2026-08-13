package com.quant.platform.mp.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.common.dto.ApiResponse;
import com.quant.platform.mp.domain.MpFactorDefinition;
import com.quant.platform.mp.domain.MpFactorIcRecord;
import com.quant.platform.mp.mapper.MpFactorIcRecordMapper;
import com.quant.platform.mp.mapper.MpFactorMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 小程序因子接口（只读）：列表 / 详情 / IC 趋势 / 测试报告。
 * 直连 MySQL，复用主后端 factor_definition / factor_ic_record / factor_test_report 表。
 */
@RestController
@RequestMapping("/mp/factors")
@RequiredArgsConstructor
public class MpFactorController {

    private final MpFactorMapper factorMapper;
    private final MpFactorIcRecordMapper factorIcRecordMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 因子列表（支持关键词 / 分类 / 状态筛选）。
     * 额外按 factorCode 聚合 IC 序列，附 icStat（IC 均值 / ICIR / IC 正比率 / 有效性），供列表徽标展示。
     */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "5") int forwardDays) {
        QueryWrapper<MpFactorDefinition> qw = new QueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.like("factor_name", keyword).or().like("factor_code", keyword));
        }
        if (category != null && !category.isBlank()) {
            qw.eq("category", category);
        }
        if (status != null && !status.isBlank()) {
            qw.eq("status", status);
        }
        qw.orderByDesc("id");
        List<MpFactorDefinition> factors = factorMapper.selectList(qw);

        // 一次性拉取该前瞻窗口的全部 IC 记录，按 factorCode 分组，避免 N+1
        List<MpFactorIcRecord> records = factorIcRecordMapper.selectList(
                new QueryWrapper<MpFactorIcRecord>().eq("forward_days", forwardDays));
        Map<String, List<MpFactorIcRecord>> byCode = records.stream()
                .collect(Collectors.groupingBy(MpFactorIcRecord::getFactorCode));

        List<Map<String, Object>> result = new ArrayList<>();
        for (MpFactorDefinition f : factors) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", f.getId());
            m.put("factorCode", f.getFactorCode());
            m.put("factorName", f.getFactorName());
            m.put("category", f.getCategory());
            m.put("factorType", f.getFactorType());
            m.put("status", f.getStatus());
            m.put("description", f.getDescription());
            m.put("stockPool", f.getStockPool());
            m.put("icStat", computeIcStat(byCode.get(f.getFactorCode())));
            result.add(m);
        }
        return ApiResponse.success(result);
    }

    /**
     * 因子详情
     */
    @GetMapping("/{id}")
    public ApiResponse<MpFactorDefinition> getById(@PathVariable Long id) {
        return ApiResponse.success(factorMapper.selectById(id));
    }

    /**
     * 因子 IC 趋势：按 id 解析 factorCode，返回 IC 时间序列
     */
    @GetMapping("/{id}/ic-trend")
    public ApiResponse<Map<String, Object>> icTrend(
            @PathVariable Long id,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "5") int forwardDays) {
        MpFactorDefinition factor = factorMapper.selectById(id);
        if (factor == null) {
            return ApiResponse.success(Collections.emptyMap());
        }
        QueryWrapper<MpFactorIcRecord> qw = new QueryWrapper<>();
        qw.eq("factor_code", factor.getFactorCode()).eq("forward_days", forwardDays);
        if (startDate != null && !startDate.isBlank()) {
            qw.ge("trade_date", LocalDate.parse(startDate));
        }
        if (endDate != null && !endDate.isBlank()) {
            qw.le("trade_date", LocalDate.parse(endDate));
        }
        qw.orderByAsc("trade_date");
        List<MpFactorIcRecord> trend = factorIcRecordMapper.selectList(qw);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("factor", factor);
        result.put("forwardDays", forwardDays);
        result.put("trend", trend);
        return ApiResponse.success(result);
    }

    /**
     * 因子测试报告：读取主后端 FactorTestEngine 产出的最新 COMPLETED 报告。
     * 包含分组回测、因子衰减、拥挤度/换手率等完整数据。
     * 若无 COMPLETED 报告，返回 hasData=false。
     */
    @GetMapping("/{id}/test-report")
    public ApiResponse<Map<String, Object>> testReport(@PathVariable Long id) {
        MpFactorDefinition factor = factorMapper.selectById(id);
        if (factor == null) {
            return ApiResponse.success(Map.of("hasData", false));
        }

        String sql = """
                SELECT id, factor_code, test_name, start_date, end_date, stock_pool, rebalance_freq,
                       ic_mean, ic_std, icir, ic_positive_rate, rank_ic_mean, rank_icir,
                       ic_t_stat, ic_p_value,
                       group_count, top_group_return, bottom_group_return, long_short_return,
                       best_sharpe, active_volatility, win_rate_vs_benchmark,
                       monotonicity, group_ir, ls_p_value,
                       decay_periods, half_life_periods, decay_coefficient, decay_r_squared,
                       turnover_rate, factor_auto_corr,
                       decay_series_json, group_returns_json, group_nav_json, long_short_nav_json,
                       status, error_message, created_at, completed_at
                FROM factor_test_report
                WHERE factor_code = ? AND status = 'COMPLETED'
                ORDER BY created_at DESC
                LIMIT 1
                """;

        try {
            Map<String, Object> report = jdbcTemplate.queryForMap(sql, factor.getFactorCode());
            // 统一转成 camelCase 返回给前端
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("hasData", true);
            result.put("reportId", report.get("id"));
            result.put("factorCode", report.get("factor_code"));
            result.put("testName", report.get("test_name"));
            result.put("startDate", report.get("start_date"));
            result.put("endDate", report.get("end_date"));
            result.put("stockPool", report.get("stock_pool"));
            result.put("rebalanceFreq", report.get("rebalance_freq"));

            // ── 分组回测 ──
            Map<String, Object> groupBacktest = new LinkedHashMap<>();
            groupBacktest.put("groupCount", report.get("group_count"));
            groupBacktest.put("topGroupReturn", report.get("top_group_return"));
            groupBacktest.put("bottomGroupReturn", report.get("bottom_group_return"));
            groupBacktest.put("longShortReturn", report.get("long_short_return"));
            groupBacktest.put("bestSharpe", report.get("best_sharpe"));
            groupBacktest.put("activeVolatility", report.get("active_volatility"));
            groupBacktest.put("winRateVsBenchmark", report.get("win_rate_vs_benchmark"));
            groupBacktest.put("monotonicity", report.get("monotonicity"));
            groupBacktest.put("groupIr", report.get("group_ir"));
            groupBacktest.put("lsPValue", report.get("ls_p_value"));
            // 解析 JSON 字段
            groupBacktest.put("groupReturns", parseJson(report.get("group_returns_json")));
            groupBacktest.put("groupNav", parseJson(report.get("group_nav_json")));
            groupBacktest.put("longShortNav", parseJson(report.get("long_short_nav_json")));
            result.put("groupBacktest", groupBacktest);

            // ── 因子衰减 ──
            Map<String, Object> decay = new LinkedHashMap<>();
            decay.put("decayPeriods", report.get("decay_periods"));
            decay.put("halfLifePeriods", report.get("half_life_periods"));
            decay.put("decayCoefficient", report.get("decay_coefficient"));
            decay.put("decayRSquared", report.get("decay_r_squared"));
            decay.put("series", parseJson(report.get("decay_series_json")));
            result.put("decay", decay);

            // ── 拥挤度与去重 ──
            Map<String, Object> crowd = new LinkedHashMap<>();
            crowd.put("turnoverRate", report.get("turnover_rate"));
            crowd.put("factorAutoCorr", report.get("factor_auto_corr"));
            result.put("crowd", crowd);

            // ── IC 汇总（来自 test_report，含 Pearson IC 与 Rank IC 区分）──
            Map<String, Object> icSummary = new LinkedHashMap<>();
            icSummary.put("icMean", report.get("ic_mean"));
            icSummary.put("icStd", report.get("ic_std"));
            icSummary.put("icir", report.get("icir"));
            icSummary.put("icPositiveRate", report.get("ic_positive_rate"));
            icSummary.put("rankIcMean", report.get("rank_ic_mean"));
            icSummary.put("rankIcir", report.get("rank_icir"));
            icSummary.put("icTStat", report.get("ic_t_stat"));
            icSummary.put("iPValue", report.get("ic_p_value"));
            result.put("icSummary", icSummary);

            // 元信息
            result.put("completedAt", report.get("completed_at"));
            result.put("createdAt", report.get("created_at"));

            return ApiResponse.success(result);
        } catch (Exception e) {
            // 无 COMPLETED 报告 → 返回空占位
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("hasData", false);
            empty.put("factorCode", factor.getFactorCode());
            return ApiResponse.success(empty);
        }
    }

    /**
     * 根据 IC 序列计算汇总指标：IC 均值、ICIR、IC 正比率、有效性。
     * ICIR = IC 均值 / IC 标准差；有效性判定：|IC|>=0.05 且 |IR|>=0.5 有效；>=0.03 且 >=0.3 弱有效。
     */
    private Map<String, Object> computeIcStat(List<MpFactorIcRecord> recs) {
        Map<String, Object> s = new LinkedHashMap<>();
        if (recs == null || recs.isEmpty()) {
            s.put("hasData", false);
            return s;
        }
        List<Double> vals = recs.stream()
                .map(MpFactorIcRecord::getIcValue)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        int n = vals.size();
        if (n == 0) {
            s.put("hasData", false);
            return s;
        }
        double mean = vals.stream().mapToDouble(Double::doubleValue).average().orElse(0d);
        double variance = n > 1
                ? vals.stream().mapToDouble(v -> (v - mean) * (v - mean)).sum() / (n - 1)
                : 0d;
        double std = Math.sqrt(variance);
        double ir = std > 0 ? mean / std : 0d;
        long pos = vals.stream().filter(v -> v > 0).count();
        double posRatio = (double) pos / n * 100d;

        MpFactorIcRecord latest = recs.get(recs.size() - 1);
        Double latestIr = latest.getIr60d() != null ? latest.getIr60d()
                : (latest.getIr20d() != null ? latest.getIr20d() : null);

        String eff = "invalid";
        if (Math.abs(mean) >= 0.05 && Math.abs(ir) >= 0.5) eff = "valid";
        else if (Math.abs(mean) >= 0.03 && Math.abs(ir) >= 0.3) eff = "weak";

        s.put("hasData", true);
        s.put("icMean", round(mean, 3));
        s.put("icir", round(ir, 2));
        s.put("icStd", round(std, 3));
        s.put("icPosRatio", round(posRatio, 1));
        s.put("latestIc", latest.getIcValue() != null ? round(latest.getIcValue(), 3) : null);
        s.put("latestIr", latestIr != null ? round(latestIr, 2) : null);
        s.put("eff", eff);
        s.put("sampleSize", n);
        return s;
    }

    private double round(double v, int scale) {
        double p = Math.pow(10, scale);
        return Math.round(v * p) / p;
    }

    /**
     * 安全解析 JSON 字符串（数据库 TEXT/JSON 列）。
     * 返回 List&lt;Map&gt; 或 null（输入为空 / 格式异常时返回 null 而非抛异常）。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseJson(Object raw) {
        if (raw == null || !(raw instanceof String) || ((String) raw).isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue((String) raw,
                    new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return null;
        }
    }
}
