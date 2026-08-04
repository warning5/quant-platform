package com.quant.platform.backtest.engine;

import com.quant.platform.common.utils.LimitUpUtils;
import com.quant.platform.market.domain.MarketDailyBar;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 回测公共工具类
 * <p>
 * 抽取自 {@link BacktestEngine}，供 RollingScreenEngine 等新引擎复用。
 * <p>
 * <b>唯一实现来源</b>：本类中的方法为回测费用/滑点/复权计算的唯一实现，
 * {@code BacktestEngine} 中不得再保留同名私有副本（历史上曾出现双轨实现且数学不等价，
 * 详见 {@code docs/god-class-refactor-plan-2026-08-04.md} §2）。
 */
@Slf4j
public class BacktestUtils {

    private BacktestUtils() {}  // 工具类，不实例化

    /** VOLUME 滑点模型的市场冲击系数（成交额占比开方后的放大倍数）。 */
    public static final double VOLUME_IMPACT_COEFF = 10.0;

    /** 单笔买入金额不得超过日成交额的比例上限。 */
    public static final double MAX_PARTICIPATION = 0.08;

    // ============================================================
    //  费用计算
    // ============================================================

    /**
     * 计算单笔交易总费用（佣金 + 印花税 + 过户费）。
     *
     * @param amount         成交金额（元）
     * @param isSell        是否卖出（印花税仅卖出收取）
     * @param commissionRate 佣金率（如 0.0003）
     * @param stampTaxRate  印花税率（如 0.0005，仅卖出）
     * @param minCommission 最低佣金（元/笔，如 5.0）
     * @param symbol         股票代码（用于判断沪深/北交所过户费）
     * @param transferFeeRate 过户费率（如 0.00002，沪深双向）
     * @return 总费用（元）
     */
    public static double calcFee(double amount, boolean isSell,
                               double commissionRate, double stampTaxRate,
                               double minCommission, @Nullable String symbol,
                               double transferFeeRate) {
        double commission = Math.max(amount * commissionRate, minCommission);
        double stampTax = isSell ? amount * stampTaxRate : 0;
        double transferFee = (symbol != null && (symbol.endsWith(".SH") || symbol.endsWith(".SZ")))
                ? amount * transferFeeRate : 0;
        return commission + stampTax + transferFee;
    }

    // ============================================================
    //  复权因子处理
    // ============================================================

    /**
     * 更新复权因子（用于 dividendReinvest=false 时消除除权价格跳空）。
     * 仅在除权日（有送转股）更新 adjFactor。
     *
     * @apiNote <b>已知缺陷（KNOWN-ISSUE-BT-01，待单独修复）</b>：本方法只处理送转股，
     * <b>未处理现金分红</b>对复权因子的调整（应为 {@code adj *= (preClose - cashDiv) / preClose}）。
     * {@code BacktestEngine} 中曾存在的私有同名方法逻辑完整，但从未被调用（死代码，已于 Phase 0 删除）。
     * 此处保持现状以确保 Phase 0 重构「输出逐位不变」，修复须单独提交并重跑基准比对。
     *
     * @param adjFactors   symbol → 复权因子（会被直接修改）
     * @param barMap       当日行情快照
     * @param today       当前日期
     * @param dividendService 分红服务（用于查送转比例）
     */
    public static void updateAdjFactors(Map<String, Double> adjFactors,
                                        Map<String, MarketDailyBar> barMap,
                                        LocalDate today,
                                        com.quant.platform.stock.service.DividendService dividendService) {
        if (dividendService == null) return;

        for (Map.Entry<String, MarketDailyBar> entry : barMap.entrySet()) {
            String symbol = entry.getKey();
            double curAdj = adjFactors.getOrDefault(symbol, 1.0);

            java.math.BigDecimal stockConvert = dividendService.getStockConvertRatio(symbol, today);
            if (stockConvert != null && stockConvert.doubleValue() > 0) {
                curAdj = curAdj / (1 + stockConvert.doubleValue());
                adjFactors.put(symbol, curAdj);
            }
        }
    }

    /**
     * 处理分红除权事件（dividendReinvest=true 时调用）。
     * 修改 positions（送转股增加股数），并通过 cashRef 返回现金分红。
     *
     * @apiNote <b>已知缺陷（KNOWN-ISSUE-BT-02，待单独修复）</b>：末尾「未持仓但有除权事件」的补偿循环
     * 只处理了现金分红，<b>漏掉了送转股</b>对复权因子的调整（应先 {@code adj /= (1 + stockConvert)}）。
     * {@code BacktestEngine} 中曾存在的私有同名方法两者都处理，但从未被调用（死代码，已于 Phase 0 删除）。
     * 此处保持现状以确保 Phase 0 重构「输出逐位不变」，修复须单独提交并重跑基准比对。
     *
     * @param positions      symbol → 持股数（会被直接修改）
     * @param cashRef       长度为1的数组，用于返回累计现金分红
     * @param barMap        当日行情快照
     * @param today        当前日期
     * @param tradeLog      交易日志（分红事件会追加记录）
     * @param adjFactors   symbol → 复权因子（会被直接修改）
     * @param dividendService 分红服务
     */
    public static void processDividendEvents(
            Map<String, Double> positions,
            double[] cashRef,
            Map<String, MarketDailyBar> barMap,
            LocalDate today,
            List<Map<String, Object>> tradeLog,
            Map<String, Double> adjFactors,
            com.quant.platform.stock.service.DividendService dividendService) {

        if (dividendService == null) return;
        double totalDividendCash = 0.0;

        for (Map.Entry<String, Double> pos : positions.entrySet()) {
            String symbol = pos.getKey();
            double shares = pos.getValue();
            if (shares <= 0) continue;

            java.math.BigDecimal cashDiv = dividendService.getCashDividend(symbol, today);
            java.math.BigDecimal stockConvert = dividendService.getStockConvertRatio(symbol, today);

            boolean hasDividend = cashDiv != null && cashDiv.doubleValue() > 0;
            boolean hasStockConvert = stockConvert != null && stockConvert.doubleValue() > 0;

            if (!hasDividend && !hasStockConvert) continue;

            MarketDailyBar bar = barMap.get(symbol);
            String name = bar != null ? bar.getName() : symbol;

            // 更新复权因子
            double curAdj = adjFactors.getOrDefault(symbol, 1.0);
            if (hasStockConvert) {
                curAdj = curAdj / (1 + stockConvert.doubleValue());
            }
            if (hasDividend && bar != null && bar.getPreClose() != null && bar.getPreClose().doubleValue() > 0) {
                double preClose = bar.getPreClose().doubleValue();
                curAdj = curAdj * (preClose - cashDiv.doubleValue()) / preClose;
            }
            adjFactors.put(symbol, curAdj);

            // 送转股：增加股数
            if (hasStockConvert) {
                double newShares = shares * (1 + stockConvert.doubleValue());
                pos.setValue(newShares);
                log.debug("[{}] {} 送转: {} → {} (增加 {} 股)", today, symbol, shares, newShares, newShares - shares);
            }

            // 现金分红
            if (hasDividend) {
                double dividendAmount = shares * cashDiv.doubleValue();
                totalDividendCash += dividendAmount;

                Map<String, Object> trade = new java.util.HashMap<>();
                trade.put("date", today.toString());
                trade.put("symbol", symbol);
                trade.put("name", name);
                trade.put("action", "DIVIDEND");
                trade.put("price", round(cashDiv.doubleValue(), 4));
                trade.put("amount", round(shares, 2));
                trade.put("total", round(dividendAmount, 2));
                trade.put("commission", 0.0);
                trade.put("fee", 0.0);
                tradeLog.add(trade);
            }
        }

        // 对未持仓但有除权事件的股票也更新复权因子
        for (Map.Entry<String, MarketDailyBar> entry : barMap.entrySet()) {
            String symbol = entry.getKey();
            if (positions.containsKey(symbol)) continue;
            if (barMap.get(symbol) == null) continue;

            MarketDailyBar bar = barMap.get(symbol);
            if (bar.getPreClose() == null || bar.getPreClose().doubleValue() <= 0) continue;

            java.math.BigDecimal cashDiv = dividendService.getCashDividend(symbol, today);
            if (cashDiv != null && cashDiv.doubleValue() > 0 && bar.getPreClose().doubleValue() > 0) {
                double curAdj = adjFactors.getOrDefault(symbol, 1.0);
                double preClose = bar.getPreClose().doubleValue();
                curAdj = curAdj * (preClose - cashDiv.doubleValue()) / preClose;
                adjFactors.put(symbol, curAdj);
            }
        }

        if (cashRef != null) cashRef[0] = totalDividendCash;
    }

    // ============================================================
    //  滑点计算
    // ============================================================

    /**
     * 应用滑点模型，返回实际成交价。
     * <ul>
     *   <li>FIXED ：固定比例滑点，买入加价、卖出减价。</li>
     *   <li>VOLUME：在基础滑点上叠加市场冲击，
     *       {@code slip = baseSlippage × (1 + √ratio × VOLUME_IMPACT_COEFF)}，
     *       其中 {@code ratio = min(tradeAmount / dayAmount, 1.0)}。
     *       成交额占当日成交额比例越高，滑点越大；ratio 上限 1.0 防止极端值放大。</li>
     * </ul>
     *
     * @param basePrice     基准价（收盘价或次日均价）
     * @param isBuy         是否买入（买入滑点使成交价偏高）
     * @param baseSlippage  基础滑点率（如 0.001 = 0.1%）
     * @param tradeAmount   本笔成交金额（VOLUME 模式用）
     * @param dayAmount     当日成交额（VOLUME 模式用）
     * @param slippageModel FIXED / VOLUME
     * @return 实际成交价
     */
    public static double applySlippage(double basePrice, boolean isBuy,
                                        double baseSlippage, double tradeAmount,
                                        double dayAmount, String slippageModel) {
        double slip = baseSlippage;
        if ("VOLUME".equalsIgnoreCase(slippageModel) && dayAmount > 0) {
            // 成交量比例滑点：成交额占日成交额比例越高，滑点越大
            double ratio = Math.min(tradeAmount / dayAmount, 1.0);
            slip = baseSlippage * (1 + Math.sqrt(ratio) * VOLUME_IMPACT_COEFF);
        }
        return isBuy ? basePrice * (1 + slip) : basePrice * (1 - slip);
    }

    // ============================================================
    //  工具方法
    // ============================================================

    /**
     * 四舍五入到指定小数位。
     * <p>
     * 使用 {@link java.math.BigDecimal#valueOf(double)}（走 {@code Double.toString} 的最短表示）
     * 而非 {@code new BigDecimal(double)}（走精确二进制值），两者在舍入边界上结果不同，
     * 例如 {@code round(2.675, 2)}：前者得 2.68，后者得 2.67。
     * 全项目回测统一采用前者语义。NaN / Infinity 一律归零，避免 BigDecimal 抛异常。
     */
    public static double round(double value, int scale) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return 0;
        return java.math.BigDecimal.valueOf(value)
                .setScale(scale, java.math.RoundingMode.HALF_UP)
                .doubleValue();
    }

    /**
     * 计算收益率（小数形式，0.05 表示 +5%）。成本非正时返回 0。
     */
    public static double returnPct(double exitValue, double entryValue) {
        if (entryValue <= 0) return 0.0;
        return (exitValue - entryValue) / entryValue;
    }

    // ============================================================
    //  市场规则（涨跌停 / 停牌 / 容量约束 / 成交价）
    // ============================================================

    /**
     * 判断是否涨停（无法买入）。
     * <p>由 BacktestEngine 迁入（Phase5），方法体逐字迁移，行为不变。
     */
    public static boolean isLimitUp(MarketDailyBar bar) {
        if (bar.getPreClose() == null || bar.getPreClose().doubleValue() <= 0) return false;
        if (bar.getPctChg() == null) return false;
        double pct = bar.getPctChg().doubleValue();
        boolean isSt = LimitUpUtils.isStName(bar.getName());
        return LimitUpUtils.isLimitUp(pct, bar.getSymbol(), bar.getTradeDate(), isSt);
    }

    /**
     * 判断是否跌停（无法卖出）。
     * <p>由 BacktestEngine 迁入（Phase5），方法体逐字迁移，行为不变。
     */
    public static boolean isLimitDown(MarketDailyBar bar) {
        if (bar.getPreClose() == null || bar.getPreClose().doubleValue() <= 0) return false;
        if (bar.getPctChg() == null) return false;
        double pct = bar.getPctChg().doubleValue();
        boolean isSt = LimitUpUtils.isStName(bar.getName());
        return LimitUpUtils.isLimitDown(pct, bar.getSymbol(), bar.getTradeDate(), isSt);
    }

    /**
     * 判断是否停牌（成交量为0）。
     * <p>由 BacktestEngine 迁入（Phase5），方法体逐字迁移，行为不变。
     */
    public static boolean isSuspended(MarketDailyBar bar) {
        return bar.getVol() == null || bar.getVol().doubleValue() <= 0;
    }

    /**
     * 容量约束：买入金额不得超过日成交额 MAX_PARTICIPATION，否则缩金额。
     * 仅用于买入侧（卖出侧缩股会导致持仓丢失，VOLUME 滑点已覆盖大额卖出惩罚）。
     * <p>由 BacktestEngine 迁入（Phase5），方法体逐字迁移，行为不变。
     */
    public static double scaleAmountToCapacity(double amount, MarketDailyBar bar) {
        if (bar == null || bar.getAmount() == null) return amount;
        double dayAmount = bar.getAmount().doubleValue() * 1000; // 千元→元
        if (dayAmount <= 0) return amount;
        double maxAmount = dayAmount * MAX_PARTICIPATION;
        return Math.min(amount, maxAmount);
    }

    /**
     * 根据 orderType 获取实际成交价格。
     * <ul>
     *   <li>CLOSE    → 当日收盘价（默认，最保守）</li>
     *   <li>NEXT_OPEN → 次日开盘价（从预加载的 nextDayBarMap 获取，更真实）</li>
     *   <li>VWAP     → 当日成交量加权均价，用 (high+low+close)/3 近似</li>
     * </ul>
     * <p>由 BacktestEngine 迁入（Phase5），方法体逐字迁移，行为不变。
     *
     * @param bar           当日行情
     * @param tradingDates  全部交易日列表
     * @param di            当前交易日下标
     * @param orderType     成交模式
     * @param nextDayBarMap 次日行情快照（NEXT_OPEN 模式使用）
     * @return 成交参考价格
     */
    public static double getExecutionPrice(MarketDailyBar bar, List<LocalDate> tradingDates,
                                            int di, String orderType,
                                            Map<String, MarketDailyBar> nextDayBarMap) {
        double close = bar.getClose().doubleValue();
        if ("NEXT_OPEN".equalsIgnoreCase(orderType)) {
            MarketDailyBar nextBar = nextDayBarMap.get(bar.getSymbol());
            if (nextBar != null && nextBar.getOpen() != null && nextBar.getOpen().doubleValue() > 0) {
                return nextBar.getOpen().doubleValue();
            }
            return close;
        } else if ("VWAP".equalsIgnoreCase(orderType)) {
            double high = bar.getHigh() != null ? bar.getHigh().doubleValue() : close;
            double low = bar.getLow() != null ? bar.getLow().doubleValue() : close;
            return (high + low + close) / 3.0;
        }
        return close;
    }
}
