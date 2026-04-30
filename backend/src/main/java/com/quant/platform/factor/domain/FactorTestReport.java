package com.quant.platform.factor.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 因子测试报告（IC分析、分组回测等）
 */
@Data
@TableName("factor_test_report")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FactorTestReport implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 因子代码
     */
    @TableField("factor_code")
    private String factorCode;

    /**
     * 测试名称
     */
    @TableField("test_name")
    private String testName;

    /**
     * 开始日期
     */
    @TableField("start_date")
    private LocalDate startDate;

    /**
     * 结束日期
     */
    @TableField("end_date")
    private LocalDate endDate;

    /**
     * 股票池：ALL_A / CSI300 / CSI500 / CSI800 / CSI1000
     */
    @TableField("stock_pool")
    private String stockPool;

    /**
     * 调仓频率：DAILY / WEEKLY / MONTHLY
     */
    @TableField("rebalance_freq")
    private String rebalanceFreq;

    // ── IC (信息系数) 统计 ────────────────────────────────

    /**
     * IC均值
     */
    @TableField("ic_mean")
    private BigDecimal icMean;

    /**
     * IC标准差
     */
    @TableField("ic_std")
    private BigDecimal icStd;

    /**
     * ICIR = IC均值/IC标准差
     */
    @TableField("icir")
    private BigDecimal icir;

    /**
     * IC正比率
     */
    @TableField("ic_positive_rate")
    private BigDecimal icPositiveRate;

    /**
     * RankIC均值
     */
    @TableField("rank_ic_mean")
    private BigDecimal rankIcMean;

    /**
     * RankICIR
     */
    @TableField("rank_icir")
    private BigDecimal rankIcir;

    /**
     * IC t检验统计量 (icMean / (icStd/sqrt(n)))
     */
    @TableField("ic_t_stat")
    private BigDecimal icTStat;

    /**
     * IC t检验 p值（双尾，近似）
     */
    @TableField("ic_p_value")
    private BigDecimal icPValue;

    // ── 分组回测 ──────────────────────────────────────────

    /**
     * 分组数（固定5）
     */
    @TableField("group_count")
    private Integer groupCount;

    /**
     * 头部分组年化收益
     */
    @TableField("top_group_return")
    private BigDecimal topGroupReturn;

    /**
     * 尾部分组年化收益
     */
    @TableField("bottom_group_return")
    private BigDecimal bottomGroupReturn;

    /**
     * 多空组合年化收益
     */
    @TableField("long_short_return")
    private BigDecimal longShortReturn;

    /**
     * 最佳分组夏普比率
     */
    @TableField("best_sharpe")
    private BigDecimal bestSharpe;

    /**
     * 多头组主动年化波动率（多头组波动率 - 基准波动率）
     */
    @TableField("active_volatility")
    private BigDecimal activeVolatility;

    /**
     * 多头组相对基准胜率（多头日收益 > 基准日收益的天数占比）
     */
    @TableField("win_rate_vs_benchmark")
    private BigDecimal winRateVsBenchmark;

    /**
     * 单调性得分：各组年化收益的Spearman秩相关（与组别序号的相关系数），[-1,1]
     */
    @TableField("monotonicity")
    private BigDecimal monotonicity;

    /**
     * 分组收益的信息比率：多空收益 / 多空收益标准差
     */
    @TableField("group_ir")
    private BigDecimal groupIr;

    /**
     * 多空收益 t检验 p值
     */
    @TableField("ls_p_value")
    private BigDecimal lsPValue;

    // ── 因子衰减分析(因子有效期) ──────────────────────────────────────────

    /**
     * 因子有效期(期数): IC绝对值首次低于阈值0.02的期数
     */
    @TableField("decay_periods")
    private BigDecimal decayPeriods;

    /**
     * 因子半衰期(期数): IC降至初始值的50%所需的期数
     */
    @TableField("half_life_periods")
    private BigDecimal halfLifePeriods;

    /**
     * 因子衰减系数: 拟合的指数衰减系数
     */
    @TableField("decay_coefficient")
    private BigDecimal decayCoefficient;

    /**
     * 因子衰减拟合优度R²
     */
    @TableField("decay_r_squared")
    private BigDecimal decayRSquared;

    /**
     * 因子衰减序列JSON: [{period, laggedIc, absoluteIc}]
     */
    @TableField("decay_series_json")
    private String decaySeriesJson;

    // ── 因子相关性分析 ─────────────────────────────────────────────

    /**
     * 因子间相关性矩阵JSON: [{factorCode1, factorCode2, correlation}]
     */
    @TableField("correlation_matrix_json")
    private String correlationMatrixJson;

    // ── 详细数据 JSON ─────────────────────────────────────

    /**
     * IC序列 [{date, ic, rankIc}]
     */
    @TableField("ic_series_json")
    private String icSeriesJson;

    /**
     * 分组年化收益 [{group, annualReturn, sharpe, maxDrawdown, volatility}]
     */
    @TableField("group_returns_json")
    private String groupReturnsJson;

    /**
     * 分组净值曲线 [{date, g1, g2, g3, g4, g5, benchmark}]
     */
    @TableField("group_nav_json")
    private String groupNavJson;

    /**
     * 多空净值曲线 [{date, topMinusBenchmark, bottomMinusBenchmark, topMinusBottom}]
     */
    @TableField("long_short_nav_json")
    private String longShortNavJson;

    /**
     * 测试状态
     */
    @TableField("status")
    private TestStatus status;

    /**
     * 错误信息
     */
    @TableField("error_message")
    private String errorMessage;

    /**
     * 创建时间
     */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 完成时间
     */
    @TableField("completed_at")
    private LocalDateTime completedAt;

    /**
     * 测试状态枚举
     */
    public enum TestStatus {
        PENDING, RUNNING, COMPLETED, FAILED
    }
}
