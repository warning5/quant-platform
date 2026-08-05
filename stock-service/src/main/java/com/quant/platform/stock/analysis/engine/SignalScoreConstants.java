package com.quant.platform.stock.analysis.engine;

import java.math.BigDecimal;
import java.util.Set;

/**
 * 交易信号评分阈值与维度权重常量
 * 自 TradingSignalEngine 逐字抽出，供各维度打分器共享（consumer 用 import static 引入）。
 */
public final class SignalScoreConstants {

    private SignalScoreConstants() {
    }

    // ========== 技术面阈值 ==========
    public static final double VOLUME_RATIO_HIGH = 2.0;      // 量比>2倍为放量
    public static final double VOLUME_RATIO_MEDIUM = 1.5;   // 量比>1.5倍为温和放量
    public static final double TURNOVER_DEVIATION_HIGH = 3.0; // 换手率偏离>3%为异常
    
    // ========== 资金面阈值 ==========
    public static final double NET_MAIN_HIGH = 5e8;       // 主力净流入>5亿=强
    public static final double NET_MAIN_MED = 1e8;        // 主力净流入>1亿=中
    public static final double NET_MAIN_LOW = -1e8;       // 主力净流入<-1亿=弱
    public static final double NET_MAIN_VLOW = -3e8;      // 主力净流入<-3亿=严重流出
    public static final double NET_MAIN_PCT_HIGH = 10.0;  // 主力净流入占比>10%=强
    public static final double NET_MAIN_PCT_MED = 5.0;    // 主力净流入占比>5%=中
    public static final double NET_MAIN_PCT_LOW = -5.0;   // 主力净流入占比<-5%=弱
    public static final double NET_MAIN_PCT_VLOW = -10.0;  // 主力净流入占比<-10%=严重流出

    // ========== 基本面阈值 ==========
    public static final double ROE_THRESHOLD = 10.0;         // ROE>10%为优质
    public static final double ROE_MED = 5.0;               // ROE>5%为中等
    public static final double NET_PROFIT_MARGIN_GOOD = 15.0;  // 净利率>15%为优秀
    public static final double NET_PROFIT_MARGIN_MED = 5.0;    // 净利率>5%为中等
    public static final double PE_TTM_LOW = 15.0;            // PE<15为低估
    public static final double PE_TTM_HIGH = 40.0;           // PE>40为高估
    public static final double PE_TTM_EXTREME = 100.0;      // PE>100为极高（给1分）
    public static final double REVENUE_YOY_GOOD = 20.0;     // 营收增速>20%为优秀
    public static final double REVENUE_YOY_PASS = 10.0;     // 营收增速>10%为及格
    public static final double NETPROFIT_YOY_GOOD = 20.0;   // 净利增速>20%为优秀
    public static final double NETPROFIT_YOY_PASS = 10.0;   // 净利增速>10%为及格
    public static final double PB_LOW = 3.0;                // PB<3为低风险
    public static final double PB_MID = 5.0;                // PB<5为中等
    public static final double DEBT_RATIO_GOOD = 40.0;     // 资产负债率<40%为健康
    public static final double DEBT_RATIO_MED = 60.0;      // 资产负债率<60%为可接受

    // ========== 新增基本面阈值 ==========
    public static final double AR_TURNOVER_DAYS_GOOD = 60.0;   // 应收账款周转天数<60=优质
    public static final double AR_TURNOVER_DAYS_MED = 120.0;    // 应收账款周转天数<120=一般
    
    // ========== 事件面阈值 ==========
    public static final int LIMIT_UP_DAYS_STRONG = 2;         // 连续2涨停为强势
    public static final double STRONG_STOCK_GAIN = 30.0;     // 20日涨幅>30%为强势股

    // ========== 大盘蓝筹阈值 ==========
    public static final BigDecimal BLUE_CHIP_MARKET_CAP = new BigDecimal("100000000000"); // 1000亿

    // ========== 维度权重 ==========
    // 研报权重从9分(5+4)降到3分，降低主观指标对评分的影响
    public static final int TECH_WEIGHT = 50;
    public static final int MONEY_WEIGHT = 25;
    public static final int SENTIMENT_WEIGHT = 25;
    public static final int FUNDAMENTAL_WEIGHT = 30;

    /** 金融行业列表（这些行业不适用毛利率/存货/流动比率等工商企业指标） */
    public static final java.util.Set<String> FINANCIAL_INDUSTRIES = Set.of(
            "银行", "证券", "保险", "多元金融"
    );
}
