package com.quant.platform.stock.analysis.service;

import com.quant.platform.stock.analysis.mapper.InstitutionCoverageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * 机构覆盖度综合指标服务
 *
 * 数据来源：
 *   - stock_research_report：研报覆盖（最权威，2738只股票，14202条近1年）
 *   - stock_fund_holder：基金持仓（5188只股票）
 *   - stock_institution_research：机构调研（仅21条，覆盖有限，作为补充）
 *
 * 综合评分（满分10分）：
 *   维度1 - 研报覆盖（0-5分）：近1年研报数量
 *   维度2 - 基金持仓（0-4分）：合计流通比例
 *   维度3 - 机构调研（0-1分）：近90天调研次数
 *
 * 评级分映射：买入=5, 增持=4, 持有/中性=3, 减持=1, 卖出=0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InstitutionCoverageService {

    private final InstitutionCoverageMapper mapper;

    private static final int RATING_BUY = 5;
    private static final int RATING_INCREASE = 4;
    private static final int RATING_HOLD = 3;
    private static final int RATING_REDUCE = 1;
    private static final int RATING_SELL = 0;

    /**
     * 获取完整机构覆盖度分析（供 Tab④ 前端展示）
     */
    public Map<String, Object> getInstitutionCoverage(String code) {
        Map<String, Object> result = new LinkedHashMap<>();

        // 1. 研报覆盖统计
        Map<String, Object> reportStats = mapper.selectResearchReportStats(code);
        int reportCount = reportStats != null && reportStats.get("report_count") != null
                ? ((Number) reportStats.get("report_count")).intValue() : 0;
        int institutionCount = reportStats != null && reportStats.get("institution_count") != null
                ? ((Number) reportStats.get("institution_count")).intValue() : 0;
        String latestReportDate = reportStats != null
                ? String.valueOf(reportStats.get("latest_report_date") != null ? reportStats.get("latest_report_date") : "-")
                : "-";

        // 2. 研报明细（最新5条）
        List<Map<String, Object>> recentReports = mapper.selectRecentResearchReports(code, 5);

        // 3. 基金持仓
        Map<String, Object> fundSummary = mapper.selectFundHolderSummary(code);
        int fundCount = fundSummary != null && fundSummary.get("fund_count") != null
                ? ((Number) fundSummary.get("fund_count")).intValue() : 0;
        BigDecimal totalFloatRatio = fundSummary != null && fundSummary.get("total_float_ratio") != null
                ? new BigDecimal(fundSummary.get("total_float_ratio").toString()).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // 4. 机构调研（近90天）
        List<Map<String, Object>> recentResearch = mapper.selectRecentInstitutionResearch(code, 90);
        int researchCount90d = recentResearch != null ? recentResearch.size() : 0;

        // 5. 计算综合评分
        int totalScore = calcTotalScore(reportCount, totalFloatRatio.doubleValue(), researchCount90d);

        // 6. 各维度评分
        int reportScore = calcReportScore(reportCount);
        int fundScore = calcFundScore(totalFloatRatio.doubleValue());
        int researchScore = calcResearchScore(researchCount90d);

        // 7. 综合评级
        String coverageLevel;
        if (totalScore >= 8) coverageLevel = "非常高";
        else if (totalScore >= 6) coverageLevel = "高";
        else if (totalScore >= 4) coverageLevel = "中";
        else if (totalScore >= 2) coverageLevel = "低";
        else coverageLevel = "极少";

        result.put("totalScore", totalScore);
        result.put("maxScore", 10);
        result.put("coverageLevel", coverageLevel);
        result.put("reportCount", reportCount);
        result.put("institutionCount", institutionCount);
        result.put("latestReportDate", latestReportDate);
        result.put("reportScore", reportScore);
        result.put("reportScoreMax", 5);
        result.put("fundCount", fundCount);
        result.put("totalFloatRatio", totalFloatRatio);
        result.put("fundScore", fundScore);
        result.put("fundScoreMax", 4);
        result.put("researchCount90d", researchCount90d);
        result.put("researchScore", researchScore);
        result.put("recentReports", recentReports);
        result.put("recentResearch", recentResearch);
        result.put("hasData", reportCount > 0 || fundCount > 0 || researchCount90d > 0);

        return result;
    }

    /**
     * 综合评分（满分10分）
     */
    private int calcTotalScore(int reportCount, double totalFloatRatio, int researchCount90d) {
        return calcReportScore(reportCount)
                + calcFundScore(totalFloatRatio)
                + calcResearchScore(researchCount90d);
    }

    /**
     * 研报覆盖评分（0-5分）
     */
    private int calcReportScore(int count) {
        if (count >= 30) return 5;   // 机构密集覆盖
        if (count >= 15) return 4;   // 较多覆盖
        if (count >= 8)  return 3;   // 正常覆盖
        if (count >= 3)  return 2;   // 一般覆盖
        if (count >= 1)  return 1;   // 极少覆盖
        return 0;
    }

    /**
     * 基金持仓评分（0-4分）
     */
    private int calcFundScore(double floatRatio) {
        if (floatRatio >= 30) return 4;   // 基金高度控盘
        if (floatRatio >= 20) return 3;   // 较多基金持仓
        if (floatRatio >= 10) return 2;   // 一般
        if (floatRatio >= 3)  return 1;   // 少量
        return 0;
    }

    /**
     * 机构调研评分（0-1分）
     */
    private int calcResearchScore(int count) {
        if (count >= 3) return 1;
        if (count >= 1) return 1;
        return 0;
    }

    /**
     * 评级标签转数值（供汇总用）
     */
    public int ratingToScore(String rating) {
        if (rating == null) return 0;
        String r = rating.trim();
        if (r.contains("买入") || r.contains("推荐") || r.contains("强烈")) return RATING_BUY;
        if (r.contains("增持") || r.contains("谨慎增持")) return RATING_INCREASE;
        if (r.contains("持有") || r.contains("中性") || r.contains("维持") || r.contains("观望")) return RATING_HOLD;
        if (r.contains("减持")) return RATING_REDUCE;
        if (r.contains("卖出")) return RATING_SELL;
        return 0;
    }

    /**
     * 获取机构覆盖度信号（供评分引擎使用）
     */
    public Map<String, Object> getInstitutionCoverageSignal(String code) {
        Map<String, Object> data = getInstitutionCoverage(code);
        Map<String, Object> signal = new LinkedHashMap<>();
        signal.put("totalScore", data.get("totalScore"));
        signal.put("coverageLevel", data.get("coverageLevel"));
        signal.put("reportScore", data.get("reportScore"));
        signal.put("fundScore", data.get("fundScore"));
        signal.put("reportCount", data.get("reportCount"));
        signal.put("fundCount", data.get("fundCount"));
        signal.put("hasData", data.get("hasData"));
        return signal;
    }
}
