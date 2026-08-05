package com.quant.platform.factor.service;

import com.quant.platform.factor.regime.MarketRegimeCalendarService;
import static com.quant.platform.factor.service.FactorIcMath.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.math3.distribution.TDistribution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 因子有效性分析服务
 * 计算 IC (Information Coefficient) / IR (Information Ratio) 等指标
 * IC = Spearman秩相关系数(因子值, 下期收益率)
 * IR = IC均值 / IC标准差
 *
 * 安全：所有接受 factorCode/factorCodes 的方法均通过白名单校验（字母/数字/下划线/横线）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactorAnalysisService {

    private final FactorIcCalculator factorIcCalculator;




    // ================================================================
    //  P1+P2: 推荐引擎因子IC快照 + 衰减加权
    // ================================================================

    /**
     * 因子IC快照（供推荐引擎使用）
     */
    public static class FactorIcSnapshot {
        public String factorCode;
        public double icMean;         // 衰减加权IC均值
        public double icMeanRaw;      // 原始等权IC均值（用于诊断）
        public double icStd;          // IC标准差
        public double ir;             // 信息比率 = |IC均值| / IC标准差（衡量信号稳定性）
        public double icSign;         // IC符号：+1正向，-1反向（用于方向对齐）
        public int sampleDays;        // 有效样本日数
        public String status;         // KEPT: 保留, DROPPED: IR不足, NO_DATA: 无数据
        public String assessment;     // 有效因子/弱有效/无效因子
        public List<Double> icTimeline; // IC时序
        public int halflifeUsed;      // 使用的半衰期

        /** |IC|绝对值（方向对齐后的权重基准） */
        public double absIc() { return Math.abs(icMean); }
    }

    /**
     * 动态半衰期：根据市场波动率自适应
     * HIGH vol → 短半衰(10天) 更快适应
     * LOW vol  → 长半衰(30天) 更稳定
     */
    public static int adaptiveHalflife(double volatilityPercentile) {
        if (volatilityPercentile >= 0.7) return 10;  // 高波动：快适应
        if (volatilityPercentile >= 0.4) return 20;  // 中波动：默认
        return 30;  // 低波动：稳定
    }

    public List<Map<String, Object>> batchCalcIcIr(List<String> factorCodes, String startDate, String endDate, int forwardDays, boolean neutralizeByIndustry, boolean neutralizeByMarketCap, String correlationType, double icThreshold) {
        return factorIcCalculator.batchCalcIcIr(factorCodes, startDate, endDate, forwardDays, neutralizeByIndustry, neutralizeByMarketCap, correlationType, icThreshold);
    }

    public Map<String, Object> getFactorIcTrend(String factorCode, String startDate, String endDate, int forwardDays) {
        return factorIcCalculator.getFactorIcTrend(factorCode, startDate, endDate, forwardDays);
    }

    public Map<String, Object> batchCalcIcIrSegmented(List<String> factorCodes, String startDate, String endDate, String splitDate, int forwardDays, boolean neutralizeByIndustry, boolean neutralizeByMarketCap, String correlationType) {
        return factorIcCalculator.batchCalcIcIrSegmented(factorCodes, startDate, endDate, splitDate, forwardDays, neutralizeByIndustry, neutralizeByMarketCap, correlationType);
    }

    public Map<String, FactorIcSnapshot> quickFactorIcSnapshot(List<String> factorCodes, LocalDate referenceDate, int lookbackDays, double irThreshold, int halflifeDays) {
        return factorIcCalculator.quickFactorIcSnapshot(factorCodes, referenceDate, lookbackDays, irThreshold, halflifeDays);
    }

}


