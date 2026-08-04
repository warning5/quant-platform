package com.quant.platform.recommendation.service;

/**
 * 市场环境信息 (Phase 2 多维)
 * <p>
 * God Class 拆分 Phase 3：由 {@link RecommendationService} 的内部类提升为包内顶层类，
 * 字段名称、类型、可见性均保持原样，因此同包内既有调用点（RecommendationService /
 * MarketRegimeDetector）无需任何改动。
 */
class RegimeInfo {
    String regime; // BULL, BEAR, SIDEWAYS

    // 指数趋势
    double indexClose;
    double indexMa20;
    double indexMa60;
    Double indexChangePct; // 沪深300当日涨跌幅%

    // 波动率
    double atrValue;
    Double atrPercentile;  // 0~1
    String volatilityRegime; // LOW, MEDIUM, HIGH

    // 市场宽度
    Long riseCount;
    Long fallCount;
    Double breadthRatio;    // 0~1
    String breadthQuality;  // GOOD, NEUTRAL, POOR

    // 风格维度 (P1-1)
    String styleRegime;      // GROWTH, VALUE, NEUTRAL
    String sizeRegime;       // LARGE, SMALL, NEUTRAL
    Double sizeSpread;       // 大盘-小盘相对强度
    Double valueGrowthSpread; // 价值-成长相对强度

    // 利率/流动性环境 (P2-2)
    String rateRegime;       // UP, DOWN, NEUTRAL
    Double bondYield10y;     // 10年期国债收益率(%)
    Double bondYieldMa20;     // 10年国债20日均线(%)
    Double yieldCurveSpread;  // 10年-2年利差(%)
}
