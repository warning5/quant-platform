package com.quant.platform.screen.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 多因子选股请求
 */
@Data
public class ScreenRequest {

    /** 选股日期，不填则取最新可用日期 */
    private LocalDate screenDate;

    /** 因子权重配置列表 */
    private List<FactorWeight> factors;

    /** 选股数量上限，默认30 */
    private Integer topN = 30;

    /** 选股方向：LONG=做多（取高分）、SHORT=做空（取低分）*/
    private String direction = "LONG";

    /** 是否剔除ST股票，默认true */
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

    /** 预设组合ID（使用预设时优先级高于 factors 列表） */
    private Long presetId;

    /** 估值面权重（买入价计算），默认 0.4 */
    private Double valuationWeight = 0.4;

    /**
     * 因子正交化方法
     * NONE = 不正交化（默认）
     * SCHMIDT = 施密特正交化（Gram-Schmidt，依赖因子顺序）
     */
    private String orthogonalizationMethod = "NONE";

    /**
     * 自定义 SQL WHERE 条件（高级模式）
     * 将直接拼接到选股查询的 WHERE 子句中，用于进阶用户做精确筛选
     * 例如: "close > 10 AND volume > 1000000"
     * 注意：仅限 AND 条件，不允许写 OR/UNION 等危险语句
     */
    private String customSqlWhere;

    /**
     * MA 均线位置过滤
     * 要求当前价在指定的均线上方（多头排列过滤）
     * 例如: aboveMA30=true 表示要求当前价在 MA30 上方
     */
    private MaPositionFilter maPositionFilter;

    @Data
    public static class MaPositionFilter {
        /** 是否要求价格在 MA30 上方，null = 不过滤 */
        private Boolean aboveMA30;
        /** 是否要求价格在 MA60 上方，null = 不过滤 */
        private Boolean aboveMA60;
        /** 是否要求价格在 MA100 上方，null = 不过滤 */
        private Boolean aboveMA100;
    }

    // ─────────────────────────────────────────────────────────────────────
    /**
     * 单个因子配置
     */
    @Data
    public static class FactorWeight {

        /** 因子代码，如 MOM20 */
        private String factorCode;

        /**
         * 因子方向
         * 1 = 正向（值越大越好）
         * -1 = 反向（值越小越好）
         * 默认 1
         */
        private Integer direction = 1;

        /** 权重（绝对值，≥0），与 direction 组合表达正反向及重要性 */
        private Double weight = 1.0;

        /**
         * 筛选条件操作符
         * NONE / GT（大于）/ GTE（大于等于）/ LT（小于）/ LTE（小于等于）/ EQ（等于）
         */
        private String filterOp = "NONE";

        /** 筛选阈值（仅 filterOp != NONE 时有效） */
        private Double filterValue;

        /**
         * 该因子极值处理方法，null/空 = 使用全局配置
         * NONE / MAD / SIGMA3 / PERCENTILE
         */
        private String outlierMethod;

        /**
         * 该因子标准化方法，null/空 = 使用全局配置
         * NONE / ZSCORE / MINMAX / RANK
         */
        private String normalizeMethod;
    }
}
