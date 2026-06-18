package com.quant.platform.stock.analysis.domain;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 技术面信号（缠论因子）
 */
@Data
public class TechSignal {
    
    /**
     * 股票代码
     */
    private String code;
    
    /**
     * 交易日期
     */
    private LocalDate tradeDate;
    
    /**
     * 趋势状态：BULLISH/BEARISH/SIDEWAYS
     */
    private String trend;
    
    /**
     * 缠论买卖信号：BUY/SELL/HOLD
     */
    private String chanSignal;
    
    /**
     * 中枢位置：UPPER/MIDDLE/LOWER
     */
    private String hubPos;
    
    /**
     * 均线多头排列：true/false
     */
    private Boolean maBullish;
    
    /**
     * MACD金叉：true/false
     */
    private Boolean macdGolden;
    
    /**
     * RSI值
     */
    private BigDecimal rsi;
    
    /**
     * MACD柱值（DIF - DEA）
     */
    private BigDecimal macdHistogram;
    
    /**
     * 前一根MACD柱值（用于判断动能是否衰竭）
     */
    private BigDecimal macdHistogramPrev;
    
    /**
     * BOLL位置（0=下轨，1=上轨，>1=突破上轨，<0=跌破下轨）
     */
    private BigDecimal bollPosition;
    
    /**
     * BOLL上轨价格
     */
    private BigDecimal bollUpper;
    
    /**
     * BOLL中轨价格（20日均线）
     */
    private BigDecimal bollMid;
    
    /**
     * BOLL下轨价格
     */
    private BigDecimal bollLower;
    
    /**
     * 5日收益率（用于量价背离检测）
     */
    private BigDecimal ret5d;
    
    /**
     * 20日收益率（中期趋势）
     */
    private BigDecimal ret20d;
    
    /**
     * 量价背离信号：true=存在背离，false=无背离，null=无法判断
     */
    private Boolean priceVolumeDivergence;
    
    /**
     * 背离类型
     */
    private String divergenceType;
    
    // ── 新增指标 ──────────────────────────────────────────────
    
    /**
     * KDJ K值（9日RSV的3日EMA）
     */
    private BigDecimal kdjK;
    
    /**
     * KDJ D值（K的3日EMA）
     */
    private BigDecimal kdjD;
    
    /**
     * KDJ J值（3*K - 2*D）
     */
    private BigDecimal kdjJ;
    
    /**
     * KDJ金叉：K上穿D
     */
    private Boolean kdjGoldenCross;
    
    /**
     * KDJ死叉：K下穿D
     */
    private Boolean kdjDeadCross;
    
    /**
     * DMI +DI值（14日）
     */
    private BigDecimal dmiPlusDI;
    
    /**
     * DMI -DI值（14日）
     */
    private BigDecimal dmiMinusDI;
    
    /**
     * DMI ADX趋势强度（14日）
     */
    private BigDecimal dmiAdx;
    
    /**
     * MACD是否在零轴上方（零轴以上金叉更可靠）
     */
    private Boolean macdAboveZero;
    
    /**
     * MACD死叉：DIF下穿DEA
     */
    private Boolean macdDeadCross;
    
    /**
     * 均线空头排列：MA5 < MA10 < MA20 < MA60
     */
    private Boolean maBearish;
    
    /**
     * 技术面评分
     */
    private int techScore;

    // ── 新增：SAR 抛物线转向 ──────────────────────────────────

    /**
     * SAR 当前值（Parabolic Stop and Reverse）
     * 趋势多头时在价格下方，趋势空头时在价格上方
     */
    private BigDecimal sar;

    /**
     * SAR 多头信号：SAR < 当前价（价格站在SAR上方=多头）
     */
    private Boolean sarAbovePrice;

    /**
     * SAR 翻多：前一根SAR >= 前一根收盘价，当前SAR < 当前收盘价
     */
    private Boolean sarTurnBullish;

    /**
     * SAR 翻空：前一根SAR <= 前一根收盘价，当前SAR > 当前收盘价
     */
    private Boolean sarTurnBearish;

    // ── 新增：近高/近低 + 量比 + MA5 ────────────────────────────

    /**
     * 近60日局部最高价
     */
    private BigDecimal nearHigh60;

    /**
     * 近60日局部最低价
     */
    private BigDecimal nearLow60;

    /**
     * 当前价距近60日高点的距离百分比（正数=还需涨这么多）
     */
    private BigDecimal nearHighPct;

    /**
     * 当前价距近60日低点的距离百分比（正数=已从低点涨这么多）
     */
    private BigDecimal nearLowPct;

    /**
     * 量比：近5日均量 / 近20日均量（>1放量，<1缩量）
     */
    private BigDecimal volumeRatio;

    /**
     * SMA5 收盘均线值（单独展示）
     */
    private BigDecimal ma5Value;

    /**
     * 近5日主力净流入（元），用于量价背离计算和显示
     */
    private BigDecimal netMain5d;

    /**
     * WR(14) 威廉指标：范围 [-100, 0]，<-80为超卖，>-20为超买
     */
    private BigDecimal wr;

    /**
     * BOLL布林带带宽百分比：(上轨-下轨)/中轨 × 100%，<5%为极度收敛
     */
    private BigDecimal bollBandwidth;

    // ── MA间距发散/收敛检测 ──────────────────────────────────────

    /**
     * MA5 与 MA20 间距百分比：(MA5 - MA20) / MA20 × 100%
     * 正值 = MA5 在 MA20 上方（多头排列间距）；负值 = MA5 在 MA20 下方（空头排列间距）
     */
    private BigDecimal maSpacing;

    /**
     * 前一交易日 MA5-MA20 间距百分比（用于判断发散/收敛方向）
     */
    private BigDecimal maSpacingPrev;

    /**
     * 均线发散/收敛类型：
     * - "发散"（短均线远离长均线，趋势加速）
     * - "收敛"（短均线靠拢长均线，动能衰减）
     * - "稳定"（间距变化不大）
     * - null（数据不足）
     */
    private String maDivergence;
}
