package com.quant.platform.backtest.engine;

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
 */
@Slf4j
public class BacktestUtils {

    private BacktestUtils() {}  // 工具类，不实例化

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
     * 应用滑点模型。
     *
     * @param basePrice     基准价（收盘价或次日均价）
     * @param isBuy        是否买入（买入滑点使成交价偏高）
     * @param slippageRate 滑点率（如 0.001 = 0.1%）
     * @param amount       成交金额（VOLUME 模式用）
     * @param dayAmount    当日成交额（VOLUME 模式用）
     * @param slippageModel FIXED / VOLUME
     * @return 实际成交价
     */
    public static double applySlippage(double basePrice, boolean isBuy,
                                        double slippageRate, double amount,
                                        double dayAmount, String slippageModel) {
        if ("VOLUME".equalsIgnoreCase(slippageModel) && dayAmount > 0) {
            double impact = Math.sqrt(amount / dayAmount) * slippageRate * basePrice;
            return isBuy ? basePrice + impact : basePrice - impact;
        }
        // 默认 FIXED
        return isBuy ? basePrice * (1 + slippageRate) : basePrice * (1 - slippageRate);
    }

    // ============================================================
    //  工具方法
    // ============================================================

    public static double round(double value, int scale) {
        return new java.math.BigDecimal(value)
                .setScale(scale, java.math.RoundingMode.HALF_UP)
                .doubleValue();
    }

    public static double returnPct(double exitValue, double entryValue) {
        if (entryValue <= 0) return 0.0;
        return (exitValue - entryValue) / entryValue;
    }
}
