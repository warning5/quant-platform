package com.quant.platform.recommendation.service;

/**
 * 行业动量信息 (Phase A+C)
 * <p>
 * 从 getSectorRanking() 获取行业涨跌幅，计算:
 * - relativeStrength: 相对沪深300的强度(标准化z-score, 越大越强势)
 * - momentumRank: 行业内排名百分位(0~1, 越大越靠前)
 */
class IndustryMomentum {
    String industry;
    double avgChangePct;       // 行业当日平均涨跌幅%
    double relativeStrength;   // 相对沪深300强度(z-score标准化, -3~3)
    int industryDiversifyLimit; // 该行业分散化上限(根据强度动态调整: 1~6)
    double fusionBonus;        // 因子融合加分(-0.06~+0.06)
    String industryRegime;     // 分行业Regime: BULL/BEAR/SIDEWAYS (Phase A 完整版)

    // P2-1: 行业动量增强
    double momentum20d;        // 行业近20日动量（累计涨跌幅%）
    double momentumScore;      // 动量综合评分（0~1）
    String momentumTrend;      // 动量趋势: ACCELERATING / DECELERATING / FLAT
}
