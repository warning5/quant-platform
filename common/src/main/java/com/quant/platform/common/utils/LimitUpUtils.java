package com.quant.platform.common.utils;

import java.time.LocalDate;

/**
 * 涨跌停阈值统一计算工具
 * <p>
 * 统一处理A股各板块涨跌幅限制差异，包括：
 * <ul>
 *   <li>主板：10%（ST股5%）</li>
 *   <li>创业板（300/301）：2020-08-24改革前10%，改革后20%</li>
 *   <li>科创板（688）：20%（2019-07-22开板即20%）</li>
 *   <li>北交所（43/83/87/88/92/920）：30%（2021-11-15开市即30%）</li>
 * </ul>
 * 阈值留0.2%~0.5%容差，因为涨停价 = round(preClose * (1+limit%), 2)，
 * 实际涨跌幅可能因四舍五入略低于理论限制。
 */
public final class LimitUpUtils {

    private LimitUpUtils() {}

    /** 创业板涨跌幅改革日 */
    public static final LocalDate GEM_REFORM_DATE = LocalDate.of(2020, 8, 24);

    /** 涨停判定阈值（含容差） */
    public static final double THRESHOLD_MAIN = 9.8;      // 主板10%
    public static final double THRESHOLD_ST = 4.8;         // ST股5%
    public static final double THRESHOLD_GEM_STAR = 19.5;  // 创业板/科创板20%
    public static final double THRESHOLD_BSE = 29.5;       // 北交所30%

    /** 跌停判定阈值（负值，含容差） */
    public static final double THRESHOLD_MAIN_DOWN = -9.8;
    public static final double THRESHOLD_ST_DOWN = -4.8;
    public static final double THRESHOLD_GEM_STAR_DOWN = -19.5;
    public static final double THRESHOLD_BSE_DOWN = -29.5;

    /**
     * 判断股票名称是否为ST股（含 *ST）
     */
    public static boolean isStName(String name) {
        return name != null && name.contains("ST");
    }

    /**
     * 获取涨停阈值（正数）
     *
     * @param symbol  股票代码（含后缀如 300001.SZ，或纯代码 300001）
     * @param date    交易日期（用于判断创业板改革前后）
     * @param isSt    是否ST股（含*ST）
     * @return 涨停阈值百分比，如 9.8、4.8、19.5、29.5
     */
    public static double getLimitUpThreshold(String symbol, LocalDate date, boolean isSt) {
        String code = symbol.replaceAll("\\..*$", "");

        // 科创板 688：始终20%
        if (code.startsWith("688")) return THRESHOLD_GEM_STAR;

        // 创业板 300/301：改革后20%，改革前10%（ST仍5%）
        if (code.startsWith("300") || code.startsWith("301")) {
            if (date != null && date.isBefore(GEM_REFORM_DATE)) {
                return isSt ? THRESHOLD_ST : THRESHOLD_MAIN;
            }
            // 改革后，即使是ST也是20%（创业板ST股不受5%限制）
            return THRESHOLD_GEM_STAR;
        }

        // 北交所 43/83/87/88/92/920：始终30%
        if (code.startsWith("43") || code.startsWith("83") || code.startsWith("87")
                || code.startsWith("88") || code.startsWith("92") || code.startsWith("920")) {
            return THRESHOLD_BSE;
        }

        // 主板：ST 5%，普通 10%
        return isSt ? THRESHOLD_ST : THRESHOLD_MAIN;
    }

    /**
     * 获取跌停阈值（负数）
     */
    public static double getLimitDownThreshold(String symbol, LocalDate date, boolean isSt) {
        return -getLimitUpThreshold(symbol, date, isSt);
    }

    /**
     * 判断是否涨停
     *
     * @param pctChg   涨跌幅百分比（如 10.02 表示 +10.02%）
     * @param symbol   股票代码
     * @param date     交易日期
     * @param isSt     是否ST股
     * @return true=涨停
     */
    public static boolean isLimitUp(double pctChg, String symbol, LocalDate date, boolean isSt) {
        return pctChg >= getLimitUpThreshold(symbol, date, isSt);
    }

    /**
     * 判断是否跌停
     */
    public static boolean isLimitDown(double pctChg, String symbol, LocalDate date, boolean isSt) {
        return pctChg <= getLimitDownThreshold(symbol, date, isSt);
    }
}
