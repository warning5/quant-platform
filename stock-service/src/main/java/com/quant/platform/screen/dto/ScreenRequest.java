package com.quant.platform.screen.dto;

import lombok.Data;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDate;
import java.util.List;

/**
 * 多因子选股请求
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScreenRequest {

    /**
     * 选股日期（单日模式），不填则取最新可用日期
     * 与 screenStartDate 互斥：优先使用 screenStartDate
     */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate screenDate;

    /**
     * 多日平均模式的起始日期（含）
     * 设定时启用多日平均模式，因子值取 [screenStartDate, screenEndDate] 范围内的均值
     */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate screenStartDate;

    /**
     * 多日平均模式的结束日期（含）
     * 必须与 screenStartDate 配合使用
     */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate screenEndDate;

    /**
     * 因子权重配置列表
     */
    private List<FactorWeight> factors;

    /**
     * 选股数量上限，默认30
     */
    private Integer topN = 30;

    /**
     * 选股方向：LONG=做多（取高分）、SHORT=做空（取低分）
     */
    private String direction = "LONG";

    /**
     * 是否剔除ST股票，默认true
     */
    private Boolean excludeSt = true;

    /**
     * 极值处理方法（全局，也可在每个因子中单独覆盖）
     * NONE / MAD（中位数去极值法）/ SIGMA3（3σ法）/ PERCENTILE（百分位截断）
     */
    private String globalOutlierMethod = "MAD";

    /**
     * 标准化处理方法（全局，也可在每个因子中单独覆盖）
     * NONE / ZSCORE（标准化法）/ MINMAX（Min-Max归一化）/ RANK（百分位排名）
     */
    private String globalNormalizeMethod = "ZSCORE";

    /**
     * 策略定义ID（使用策略时优先级高于 factors 列表）
     */
    private Long strategyId;

    /**
     * 估值面权重（买入价计算），默认 0.4
     */
    private Double valuationWeight = 0.4;

    /**
     * 因子正交化方法
     * NONE = 不正交化（默认）
     * SCHMIDT = 施密特正交化（Gram-Schmidt，依赖因子顺序）
     */
    private String orthogonalizationMethod = "NONE";

    /**
     * 权重模式
     * EQUAL = 等权（默认，使用用户设置的权重）
     * IC = IC均值加权（根据因子近60日IC均值动态调整权重）
     * IR = IR加权（根据因子近60日IR动态调整权重）
     */
    private String weightMode = "EQUAL";

    /**
     * 中性化方法
     * NONE = 不中性化（默认）
     * INDUSTRY = 行业中性化（在每个行业内做标准化）
     * MARKET_CAP = 市值中性化（在每个市值分位内做标准化）
     * BOTH = 行业+市值双重中性化
     */
    private String neutralizationMethod = "NONE";

    /**
     * 自定义 SQL WHERE 条件（高级模式）
     * 将直接拼接到选股查询的 WHERE 子句中，用于进阶用户做精确筛选
     * 例如: "close > ? AND volume > ?"
     * 注意：必须使用 ? 占位符，参数值通过 customSqlParams 传递
     *       仅限 AND 条件，不允许写 OR/UNION 等危险语句
     */
    private String customSqlWhere;

    /**
     * 自定义 SQL 条件中的参数值列表（与 customSqlWhere 中的 ? 占位符一一对应）
     * 例如: customSqlWhere = "close > ? AND volume > ?"
     *       customSqlParams = [10.0, 1000000.0]
     */
    private List<Object> customSqlParams;

    /**
     * MA 均线位置过滤
     * 要求当前价在指定的均线上方（多头排列过滤）
     * 例如: aboveMA30=true 表示要求当前价在 MA30 上方
     */
    private MaPositionFilter maPositionFilter;

    /**
     * 市场环境覆盖（仅研究/回测用）
     * 不传则使用当日实际市场环境（BULL/BEAR/SIDEWAYS）
     * 用途：研究"如果当时是熊市，会选出哪些股票"
     * 可选值：BULL(牛市) / BEAR(熊市) / SIDEWAYS(震荡) / null(使用实际)
     */
    private String regimeOverride;

    /**
     * 是否应用黑名单过滤（默认 false）
     * 用途：回测时套用当前黑名单，验证历史推荐在黑名单下表现
     */
    private Boolean blacklistFilter = false;

    @Data
    public static class MaPositionFilter {
        /**
         * 是否要求价格在 MA30 上方，null = 不过滤
         */
        private Boolean aboveMA30;
        /**
         * 是否要求价格在 MA60 上方，null = 不过滤
         */
        private Boolean aboveMA60;
        /**
         * 是否要求价格在 MA100 上方，null = 不过滤
         */
        private Boolean aboveMA100;
    }

    // ─────────────────────────────────────────────────────────────────────

    /**
     * 单个因子配置
     */
    @Data
    public static class FactorWeight {

        /**
         * 因子代码，如 MOM20
         */
        private String factorCode;

        /**
         * 因子方向
         * 1 = 正向（值越大越好）
         * -1 = 反向（值越小越好）
         * 默认 1
         */
        private Integer direction = 1;

        /**
         * 权重（绝对值，≥0），与 direction 组合表达正反向及重要性
         */
        private Double weight = 1.0;

        /**
         * 筛选条件操作符
         * NONE / GT（大于）/ GTE（大于等于）/ LT（小于）/ LTE（小于等于）/ EQ（等于）
         */
        private String filterOp = "NONE";

        /**
         * 筛选阈值（仅 filterOp != NONE 时有效）
         */
        private Double filterValue;

    }
}
