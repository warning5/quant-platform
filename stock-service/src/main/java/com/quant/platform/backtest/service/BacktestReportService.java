package com.quant.platform.backtest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.backtest.domain.BacktestReport;
import com.quant.platform.backtest.domain.BacktestTask;
import com.quant.platform.backtest.domain.EquityCurve;
import com.quant.platform.backtest.domain.ParamOptimizeReport;
import com.quant.platform.backtest.domain.WalkForwardResult;
import com.quant.platform.backtest.mapper.ParamOptimizeReportMapper;
import com.quant.platform.common.dto.ApiResponse;
import com.quant.platform.screen.dto.ScreenRequest;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * 回测报告/曲线的组装与归因方案推荐。
 * 承载原 BacktestController 内联的业务计算逻辑，让 Controller 退化为
 * 仅做参数校验、@Valid/@RateLimit 与 ApiResponse 包装的薄层。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BacktestReportService {

    private final BacktestService backtestService;
    private final BrinsonAttributionService brinsonAttributionService;
    private final FactorStyleAttributionService factorStyleAttributionService;
    private final ParamOptimizeService paramOptimizeService;
    private final ParamOptimizeReportMapper paramOptimizeReportMapper;
    private final WalkForwardService walkForwardService;
    private final ObjectMapper objectMapper;

    /**
     * 获取回测实时/历史净值曲线数据（用于前端执行中页面展示）
     * 如果报告已生成，返回完整数据；否则返回空数组
     */
    @SneakyThrows
    public Map<String, Object> getCurveData(Long taskId) {
        BacktestTask task = backtestService.getTask(taskId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", taskId);
        result.put("status", task.getStatus().name());
        result.put("progress", task.getProgress());

        boolean isScreen = "SCREEN".equalsIgnoreCase(task.getSignalSource());

        if (task.getStatus() == BacktestTask.BacktestStatus.COMPLETED) {
            if (isScreen) {
                // SCREEN 模式：从 equity_curve 表读取
                List<EquityCurve> curves = backtestService.getEquityCurves(taskId);
                List<Map<String, Object>> equityCurve = curves.stream().map(ec -> {
                    Map<String, Object> pt = new LinkedHashMap<>();
                    pt.put("tradeDate", ec.getTradeDate().toString());
                    pt.put("nav", ec.getNav());
                    return pt;
                }).toList();
                result.put("equityCurve", equityCurve);
            } else {
                // STRATEGY 模式：从报告 JSON 读取
                try {
                    BacktestReport report = backtestService.getReport(taskId);
                    List<Map<String, Object>> stratCurve = parseJsonList(report.getEquityCurveJson());
                    List<Map<String, Object>> bmCurve = parseJsonList(report.getBenchmarkCurveJson());
                    result.put("stratCurve", stratCurve);
                    result.put("bmCurve", bmCurve);
                } catch (Exception e) {
                    // 报告可能还没生成，忽略错误
                }
            }
        }
        return result;
    }

    /**
     * 策略特征检测 + 归因方案推荐
     * <p>
     * Brinson 和因子归因都跑一遍，选解释力(explanationRatio)高的作为推荐。
     * 如果两者解释力都低 (<0.15)，返回 "UNCLEAR" 并在 reason 中说明。
     * 特征检测数据（换手率/持仓天数/行业集中度）保留供前端展示参考。
     */
    public Map<String, Object> getAttributionStrategy(Long taskId) {
        BacktestTask task = backtestService.getTask(taskId);
        BacktestReport report = backtestService.getReport(taskId);

        String positionJson = report.getPositionHistoryJson();
        String equityJson    = report.getEquityCurveJson();
        String benchJson     = report.getBenchmarkCurveJson();

        // 1. 特征检测（保留，供前端参考）
        FactorStyleAttributionService.StrategyCharacteristics chars
                = factorStyleAttributionService.detectCharacteristics(task, positionJson);

        // 2. 尝试 Brinson 归因
        Map<String, Object> brinsonResult = null;
        double brinsonExplanatioRatio = -1;
        try {
            brinsonResult = brinsonAttributionService.computeBrinson(
                    task, positionJson, equityJson, benchJson);
            // explanationRatio 在 result.summary.explanationRatio 里
            Map<String, Object> bSummary = (Map<String, Object>) brinsonResult.get("summary");
            if (bSummary != null) {
                Object er = bSummary.get("explanationRatio");
                if (er instanceof Number) brinsonExplanatioRatio = ((Number) er).doubleValue();
            }
        } catch (Exception e) {
            log.warn("[AttributionStrategy] Brinson归因失败: {}", e.getMessage());
        }

        // 3. 尝试因子风格归因
        Map<String, Object> factorResult = null;
        double factorExplanationRatio = -1;
        try {
            factorResult = factorStyleAttributionService.compute(
                    task, positionJson, equityJson, benchJson);
            Object er = factorResult.get("summary") instanceof Map
                    ? ((Map<String, Object>) factorResult.get("summary")).get("explanationRatio")
                    : null;
            if (er instanceof Number) factorExplanationRatio = ((Number) er).doubleValue();
        } catch (Exception e) {
            log.warn("[AttributionStrategy] 因子归因失败: {}", e.getMessage());
        }

        // 4. 计算 FF3 三因子归因
        Map<String, Object> ff3Result = null;
        double ff3ExplanationRatio = -1;
        try {
            ff3Result = factorStyleAttributionService.computeFF3(
                    task, positionJson, equityJson, benchJson);
            Object er = ff3Result.get("summary") instanceof Map
                    ? ((Map<String, Object>) ff3Result.get("summary")).get("explanationRatio")
                    : null;
            if (er instanceof Number) ff3ExplanationRatio = ((Number) er).doubleValue();
        } catch (Exception e) {
            log.warn("[AttributionStrategy] FF3归因失败: {}", e.getMessage());
        }

        // 5. 提取各模型关键发现 + 构建综合归因结论
        Map<String, Object> keyFindings = new LinkedHashMap<>();
        String comprehensiveReason = buildComprehensiveReason(
                brinsonResult, brinsonExplanatioRatio,
                factorResult, factorExplanationRatio,
                ff3Result, ff3ExplanationRatio,
                keyFindings);

        // 6. 推荐最佳模型（用于前端默认展开）
        String recommendedModel;
        boolean brinsonOk = brinsonExplanatioRatio >= 0.15;
        boolean factorOk  = factorExplanationRatio >= 0.15;

        if (!brinsonOk && !factorOk) {
            recommendedModel = "UNCLEAR";
        } else if (brinsonOk && !factorOk) {
            recommendedModel = "BRINSON";
        } else if (!brinsonOk) {
            recommendedModel = "FACTOR";
        } else {
            recommendedModel = factorExplanationRatio >= brinsonExplanatioRatio ? "FACTOR" : "BRINSON";
        }

        // 7. 组装返回
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", taskId);
        result.put("recommendedModel", recommendedModel);
        result.put("reason", comprehensiveReason);
        result.put("availableModels", List.of("BRINSON", "FACTOR", "FF3"));

        // 特征数据
        result.put("avgDailyTurnover",   chars.avgDailyTurnover());
        result.put("avgHoldingDays",     chars.avgHoldingDays());
        result.put("industryConcentration", chars.industryConcentration());

        // 三模型对比
        Map<String, Object> modelComparison = new LinkedHashMap<>();

        Map<String, Object> bComp = new LinkedHashMap<>();
        bComp.put("explanationRatio", round2(brinsonExplanatioRatio));
        bComp.put("available", brinsonResult != null);
        modelComparison.put("BRINSON", bComp);

        Map<String, Object> fComp = new LinkedHashMap<>();
        fComp.put("explanationRatio", round2(factorExplanationRatio));
        fComp.put("available", factorResult != null);
        modelComparison.put("FACTOR", fComp);

        Map<String, Object> ff3Comp = new LinkedHashMap<>();
        ff3Comp.put("explanationRatio", round2(ff3ExplanationRatio));
        ff3Comp.put("available", ff3Result != null);
        modelComparison.put("FF3", ff3Comp);

        result.put("modelComparison", modelComparison);
        result.put("keyFindings", keyFindings);

        log.info("[AttributionStrategy] taskId={}, recommended={}, Brinson={}, Factor={}, FF3={}",
                taskId, recommendedModel,
                round2(brinsonExplanatioRatio), round2(factorExplanationRatio), round2(ff3ExplanationRatio));
        return result;
    }

    /**
     * 查询参数优化任务状态和结果
     * GET /backtests/param-optimize/{jobId}
     */
    public ApiResponse<Map<String, Object>> getParamOptimizeResult(String jobId) {
        ParamOptimizeService.OptimizeJob job = paramOptimizeService.getJob(jobId);
        if (job == null) {
            return ApiResponse.error("优化任务不存在: " + jobId);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobId", job.jobId);
        result.put("status", job.status);
        result.put("total", job.total);
        result.put("done", job.done.get());
        result.put("progress", job.total > 0 ? (int) (100.0 * job.done.get() / job.total) : 0);
        // 从DB恢复的记录包含 paramGrid 等信息
        ParamOptimizeReport report = paramOptimizeService.getOptimizeReport(jobId);
        if (report != null) {
            result.put("paramGrid", report.getParamGridJson());
            result.put("startDate", report.getStartDate());
            result.put("endDate", report.getEndDate());
            result.put("objective", report.getObjective());
        }
        // 运行中（COMPLETED/RUNNING/PENDING）都返回已完成的 results，前端实时展示最新结果
        if ("COMPLETED".equals(job.status) || "RUNNING".equals(job.status)) {
            result.put("results", new ArrayList<>(job.results));
            result.put("bestResult", job.bestResult);
            // DEBUG: 打印 bestResult 的所有参数字段
            if (job.bestResult != null) {
                log.info("[ParamOptimize] bestResult fields: {}", job.bestResult.keySet());
                log.info("[ParamOptimize] bestResult maxPositionCount={}, stopLossPct={}, stopProfitPct={}",
                        job.bestResult.get("maxPositionCount"),
                        job.bestResult.get("stopLossPct"),
                        job.bestResult.get("stopProfitPct"));
            } else {
                log.warn("[ParamOptimize] bestResult is null for jobId={}, status={}, results.size={}, done={}, total={}",
                        jobId, job.status, job.results.size(), job.done.get(), job.total);
            }
            // 运行中任务 endMs=0，使用当前时间计算已耗时
            long elapsed = job.endMs > 0 ? job.endMs - job.startMs
                    : System.currentTimeMillis() - job.startMs;
            result.put("elapsedMs", elapsed);
        }
        if (job.errorMessage != null) result.put("errorMessage", job.errorMessage);
        return ApiResponse.success(result);
    }

    /**
     * 查询优化任务列表（历史记录）
     * GET /backtests/param-optimize/list?strategyId=xxx
     */
    public List<Map<String, Object>> listParamOptimizeJobs(Long strategyId) {
        List<ParamOptimizeReport> reports;
        if (strategyId != null) {
            reports = paramOptimizeReportMapper.findByStrategyId(strategyId);
        } else {
            reports = paramOptimizeReportMapper.findRecent(50);
        }
        List<Map<String, Object>> data = reports.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("jobId", r.getJobId());
            m.put("strategyId", r.getStrategyId());
            m.put("strategyCode", r.getStrategyCode());
            m.put("taskName", r.getTaskName());
            m.put("startDate", r.getStartDate());
            m.put("endDate", r.getEndDate());
            m.put("objective", r.getObjective());
            m.put("paramGrid", r.getParamGridJson());
            m.put("status", r.getStatus());
            m.put("total", r.getTotal());
            m.put("done", r.getDone());
            m.put("progress", r.getProgress());
            m.put("bestScore", r.getBestScore());
            m.put("bestAnnualReturn", r.getBestAnnualReturn());
            m.put("bestMaxDrawdown", r.getBestMaxDrawdown());
            m.put("elapsedMs", r.getElapsedMs());
            m.put("errorMessage", r.getErrorMessage());
            m.put("createdAt", r.getCreatedAt());
            return m;
        }).toList();
        return data;
    }

    /**
     * 删除优化任务
     * DELETE /backtests/param-optimize/{jobId}
     */
    public void deleteParamOptimizeJob(String jobId) {
        paramOptimizeReportMapper.deleteByJobId(jobId);
    }

    /**
     * Walk-Forward 滚动窗口验证
     * POST /backtests/walk-forward
     */
    public ApiResponse<Map<String, Object>> runWalkForward(Map<String, Object> params) {
        try {
            // 解析因子配置
            List<Map<String, Object>> factorMaps = (List<Map<String, Object>>) params.get("factors");
            List<ScreenRequest.FactorWeight> factors = new ArrayList<>();
            if (factorMaps != null) {
                for (Map<String, Object> fm : factorMaps) {
                    ScreenRequest.FactorWeight fw = new ScreenRequest.FactorWeight();
                    fw.setFactorCode((String) fm.get("factorCode"));
                    fw.setDirection(fm.get("direction") != null ? ((Number) fm.get("direction")).intValue() : 1);
                    fw.setWeight(fm.get("weight") != null ? ((Number) fm.get("weight")).doubleValue() : 1.0);
                    factors.add(fw);
                }
            }

            LocalDate startDate = null;
            if (params.containsKey("startDate")) {
                startDate = LocalDate.parse((String) params.get("startDate"));
            }
            LocalDate endDate = LocalDate.now();
            if (params.containsKey("endDate")) {
                endDate = LocalDate.parse((String) params.get("endDate"));
            }
            int trainDays = params.containsKey("trainDays") ? ((Number) params.get("trainDays")).intValue() : 60;
            int validateDays = params.containsKey("validateDays") ? ((Number) params.get("validateDays")).intValue() : 20;
            int stepDays = params.containsKey("stepDays") ? ((Number) params.get("stepDays")).intValue() : 10;
            int maxRounds = params.containsKey("maxRounds") ? ((Number) params.get("maxRounds")).intValue() : 10;
            Double transactionCost = params.containsKey("transactionCost")
                    ? ((Number) params.get("transactionCost")).doubleValue() : null;
            Integer rebalanceInterval = params.containsKey("rebalanceInterval")
                    ? ((Number) params.get("rebalanceInterval")).intValue() : null;

            List<WalkForwardResult> results = walkForwardService.runWalkForward(
                    factors, startDate, endDate, trainDays, validateDays, stepDays, maxRounds, transactionCost, rebalanceInterval);
            Map<String, Object> summary = walkForwardService.summarize(results);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("summary", summary);
            response.put("rounds", results);
            return ApiResponse.success("Walk-Forward验证完成", response);
        } catch (Exception e) {
            log.error("[WalkForward] 验证失败: {}", e.getMessage(), e);
            return ApiResponse.error("Walk-Forward验证失败: " + e.getMessage());
        }
    }

    // ═══════════════════════ 私有辅助方法 ═══════════════════════

    private static double round2(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    /**
     * 综合三个归因模型的关键发现，构建完整结论。
     * 同时填充 keyFindings Map 供前端结构化展示。
     */
    @SuppressWarnings("unchecked")
    private String buildComprehensiveReason(
            Map<String, Object> brinsonResult, double brinsonER,
            Map<String, Object> factorResult, double factorER,
            Map<String, Object> ff3Result, double ff3ER,
            Map<String, Object> keyFindings) {

        StringBuilder sb = new StringBuilder();

        // ── Brinson 行业归因 ──
        Map<String, Object> bKey = new LinkedHashMap<>();
        bKey.put("available", brinsonResult != null);
        bKey.put("explanationRatio", round2(brinsonER));
        if (brinsonResult != null) {
            Map<String, Object> bSummary = (Map<String, Object>) brinsonResult.get("summary");
            if (bSummary != null) {
                bKey.put("totalAllocationEffect", bSummary.get("totalAllocationEffect"));
                bKey.put("totalSelectionEffect", bSummary.get("totalSelectionEffect"));
                bKey.put("totalExcessReturn", bSummary.get("totalExcessReturn"));
            }
            // 前3贡献 + 前3拖累行业
            List<Map<String, Object>> bIndSec = extList(brinsonResult.get("industrySummary"));
            List<Map<String, Object>> topInd = new ArrayList<>(), btmInd = new ArrayList<>();
            for (int i = 0; i < Math.min(6, bIndSec.size()); i++) {
                double c = num(bIndSec.get(i).get("totalContribution"));
                if (c > 0 && topInd.size() < 3) topInd.add(bIndSec.get(i));
                else if (c < 0 && btmInd.size() < 3) btmInd.add(bIndSec.get(i));
            }
            bKey.put("topIndustries", topInd);
            bKey.put("bottomIndustries", btmInd);
        }
        keyFindings.put("brinson", bKey);

        // Brinson 结论
        sb.append("【行业归因：钱从哪来？】");
        if (brinsonResult == null) {
            sb.append(" 数据不足，无法分析。");
        } else {
            if (brinsonER >= 0.5) {
                sb.append(String.format(" 可信度 %.0f%%。", brinsonER * 100));
            } else if (brinsonER >= 0.15) {
                sb.append(String.format(" 可信度 %.0f%%，有一定参考价值。", brinsonER * 100));
            } else {
                sb.append(String.format(" 可信度 %.0f%%，偏低，结果仅供参考。", brinsonER * 100));
            }
            Map<String, Object> bSum = (Map<String, Object>) brinsonResult.get("summary");
            if (bSum != null) {
                double alloc = num(bSum.get("totalAllocationEffect"));
                double sel = num(bSum.get("totalSelectionEffect"));
                String mainDriver = Math.abs(alloc) >= Math.abs(sel) ? "行业配置" : "行业内选股";
                sb.append(" 策略主要靠").append(mainDriver).append("赚钱");
                sb.append(String.format("（配置贡献 %+.2f%%，选股贡献 %+.2f%%）。",
                        alloc * 100, sel * 100));
            }
            List<Map<String, Object>> top3 = (List<Map<String, Object>>) bKey.get("topIndustries");
            List<Map<String, Object>> btm3 = (List<Map<String, Object>>) bKey.get("bottomIndustries");
            if (!top3.isEmpty()) {
                sb.append(" 赚钱最多的行业：");
                for (int i = 0; i < top3.size(); i++) {
                    if (i > 0) sb.append("、");
                    sb.append(top3.get(i).get("industry")).append("(+")
                      .append(String.format("%.1f", num(top3.get(i).get("totalContribution")) * 100)).append("%)");
                }
            }
            if (!btm3.isEmpty()) {
                sb.append("；亏钱的行业：");
                for (int i = 0; i < btm3.size(); i++) {
                    if (i > 0) sb.append("、");
                    sb.append(btm3.get(i).get("industry")).append("(")
                      .append(String.format("%.1f", num(btm3.get(i).get("totalContribution")) * 100)).append("%)");
                }
            }
            sb.append("。");
        }
        sb.append("\n\n");

        // ── 因子风格归因 ──
        Map<String, Object> fKey = new LinkedHashMap<>();
        fKey.put("available", factorResult != null);
        fKey.put("explanationRatio", round2(factorER));
        if (factorResult != null) {
            Map<String, Object> fReg = (Map<String, Object>) factorResult.get("regressionDetail");
            if (fReg != null) {
                fKey.put("rSquared", fReg.get("rSquared"));
            }
            List<Map<String, Object>> fContribs = extList(factorResult.get("factorContributions"));
            List<Map<String, Object>> sigFactors = new ArrayList<>();
            for (Map<String, Object> fc : fContribs) {
                if (Boolean.TRUE.equals(fc.get("significant"))) sigFactors.add(fc);
            }
            fKey.put("significantFactors", sigFactors);
            boolean isFactorDriven = factorER >= 0.3;
            fKey.put("isFactorDriven", isFactorDriven);
        }
        keyFindings.put("factor", fKey);

        // Factor 结论
        sb.append("【因子分析：为什么赚钱？】");
        if (factorResult == null) {
            sb.append(" 数据不足，无法分析。");
        } else {
            if (factorER >= 0.5) {
                sb.append(String.format(" 可信度 %.0f%%。", factorER * 100));
            } else if (factorER >= 0.15) {
                sb.append(String.format(" 可信度 %.0f%%，有一定参考价值。", factorER * 100));
            } else {
                sb.append(String.format(" 可信度 %.0f%%，偏低，结果仅供参考。", factorER * 100));
            }
            List<Map<String, Object>> sigF = (List<Map<String, Object>>) fKey.get("significantFactors");
            if (sigF != null && !sigF.isEmpty()) {
                sb.append(" 策略偏好：");
                for (int i = 0; i < sigF.size(); i++) {
                    if (i > 0) sb.append("、");
                    double beta = num(sigF.get(i).get("beta"));
                    String fName = (String) sigF.get(i).get("factorName");
                    sb.append(fName).append(beta > 0 ? "（偏爱）" : "（回避）");
                }
                sb.append("。");
            }
            Object r2 = fKey.get("rSquared");
            if (r2 instanceof Number) {
                String style = Boolean.TRUE.equals(fKey.get("isFactorDriven"))
                        ? "收益主要靠这些因子驱动，按因子选股就能复现大部分表现。"
                        : "因子只能解释部分收益，策略有自己的独立选股逻辑。";
                sb.append(" ").append(style);
            }
        }
        sb.append("\n\n");

        // ── FF3 三因子归因 ──
        Map<String, Object> ff3Key = new LinkedHashMap<>();
        ff3Key.put("available", ff3Result != null);
        ff3Key.put("explanationRatio", round2(ff3ER));
        if (ff3Result != null) {
            Map<String, Object> ff3Reg = (Map<String, Object>) ff3Result.get("regressionDetail");
            if (ff3Reg != null) {
                ff3Key.put("rSquared", ff3Reg.get("rSquared"));
                ff3Key.put("annualizedAlpha", ff3Reg.get("annualizedAlpha"));
                ff3Key.put("alphaSignificant", ff3Reg.get("alphaSignificant"));
            }
            ff3Key.put("styleBias", ff3Result.get("styleBias"));
        }
        keyFindings.put("ff3", ff3Key);

        // FF3 结论
        sb.append("【纯Alpha：到底有多少真本事？】");
        if (ff3Result == null) {
            sb.append(" 数据不足，无法分析。");
        } else if (ff3ER < 0.30) {
            sb.append(String.format(" 不可信（R²=%.0f%%），三因子模型无法解释该策略——"
                + "策略的赚钱逻辑不在市场/市值/估值这些传统框架内，风格标签无意义。", ff3ER * 100));
        } else {
            sb.append(String.format(" 可信度 %.0f%%。", ff3ER * 100));
            Object annAlpha = ff3Key.get("annualizedAlpha");
            if (annAlpha instanceof Number) {
                double aa = ((Number) annAlpha).doubleValue();
                boolean sig = Boolean.TRUE.equals(ff3Key.get("alphaSignificant"));
                if (sig && aa > 0) {
                    sb.append(String.format(" 扣除市场涨跌、大小盘偏好、价值偏好后，策略每年额外赚 %.1f%%。", aa * 100));
                    sb.append(" 这部分是因子无法解释的独立选股能力，值得关注。");
                } else if (aa > 0) {
                    sb.append(String.format(" 扣除被动因子后，策略每年额外赚 %.1f%%，但统计上不够显著（可能是运气）。", aa * 100));
                } else {
                    sb.append(String.format(" 扣除被动因子后，策略每年跑输 %.1f%%。", Math.abs(aa * 100)));
                    sb.append(" 策略收益基本可以被市场涨跌和大小盘/价值偏好解释，独立选股能力弱。");
                }
            }
            Object bias = ff3Key.get("styleBias");
            if (bias instanceof String && !((String) bias).isEmpty()) {
                sb.append(" ").append(bias);
            }
        }
        sb.append("\n\n");

        // ── 总体判断 ──
        sb.append("【总评】");
        boolean brinsonOk = brinsonER >= 0.15;
        boolean factorOk  = factorER >= 0.15;
        boolean ff3Ok     = ff3ER >= 0.15;

        if (!brinsonOk && !factorOk && !ff3Ok) {
            sb.append(" 三种分析方式的可靠度都偏低，策略收益很难用现有模型解释。");
            sb.append(" 可能来源：个股精选能力、精准择时、或者运气。建议检查选股信号的质量和一致性。");
        } else {
            List<String> strengths = new ArrayList<>();
            if (brinsonOk) strengths.add("行业层面可分析");
            if (factorOk) strengths.add("因子层面可分析");
            if (ff3Ok) strengths.add("纯Alpha可量化");
            sb.append(" ").append(String.join("、", strengths)).append("。");
            if (!brinsonOk && factorOk) {
                sb.append(" 行业归因可靠度不高，策略不依赖行业择时，建议重点看因子和Alpha。");
            } else if (brinsonOk && !factorOk) {
                sb.append(" 因子归因可靠度不高，策略不靠因子玩法，重点看行业配置和选股能力。");
            }
        }

        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extList(Object o) {
        if (o instanceof List) return (List<Map<String, Object>>) o;
        return Collections.emptyList();
    }

    private double num(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        return 0;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseJsonList(String json) throws Exception {
        if (json == null || json.isEmpty()) return Collections.emptyList();
        return objectMapper.readValue(json, List.class);
    }
}
