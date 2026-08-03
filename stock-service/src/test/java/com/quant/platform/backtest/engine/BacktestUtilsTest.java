package com.quant.platform.backtest.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BacktestUtils 回测工具类单元测试
 * 纯逻辑测试，不依赖外部服务
 */
@DisplayName("BacktestUtils 回测工具")
class BacktestUtilsTest {

    private static final double DELTA = 1e-9;

    // ==================== calcFee ====================

    @Test
    @DisplayName("calcFee - 买入: 佣金+过户费, 无印花税")
    void testCalcFeeBuy() {
        // 金额10000, 佣金率0.0003, 印花税率0.0005(不收), 最低佣金5, 沪市过户费0.00002
        double fee = BacktestUtils.calcFee(10000, false, 0.0003, 0.0005, 5.0, "600001.SH", 0.00002);
        double expectedCommission = Math.max(10000 * 0.0003, 5.0); // max(3, 5) = 5
        double expectedTransfer = 10000 * 0.00002; // 0.2
        assertEquals(expectedCommission + expectedTransfer, fee, DELTA);
    }

    @Test
    @DisplayName("calcFee - 卖出: 佣金+印花税+过户费")
    void testCalcFeeSell() {
        double fee = BacktestUtils.calcFee(10000, true, 0.0003, 0.0005, 5.0, "600001.SH", 0.00002);
        double expectedCommission = Math.max(10000 * 0.0003, 5.0); // 5
        double expectedStampTax = 10000 * 0.0005; // 5
        double expectedTransfer = 10000 * 0.00002; // 0.2
        assertEquals(expectedCommission + expectedStampTax + expectedTransfer, fee, DELTA);
    }

    @Test
    @DisplayName("calcFee - 佣金不低于最低佣金")
    void testCalcFeeMinCommission() {
        // 小额交易: 金额100, 佣金率0.0003 -> 0.03 < 5, 应取最低5
        double fee = BacktestUtils.calcFee(100, false, 0.0003, 0.0005, 5.0, "600001.SH", 0.00002);
        double expectedTransfer = 100 * 0.00002; // 0.002
        assertEquals(5.0 + expectedTransfer, fee, DELTA);
    }

    @Test
    @DisplayName("calcFee - 深市有过户费（沪深双向收过户费）")
    void testCalcFeeSzTransferFee() {
        double fee = BacktestUtils.calcFee(10000, false, 0.0003, 0.0005, 5.0, "000001.SZ", 0.00002);
        double expectedCommission = Math.max(10000 * 0.0003, 5.0); // 5
        double expectedTransfer = 10000 * 0.00002; // 0.2
        assertEquals(expectedCommission + expectedTransfer, fee, DELTA, "沪深市均收过户费");
    }

    @Test
    @DisplayName("calcFee - null symbol 无过户费")
    void testCalcFeeNullSymbol() {
        double fee = BacktestUtils.calcFee(10000, false, 0.0003, 0.0005, 5.0, null, 0.00002);
        double expectedCommission = Math.max(10000 * 0.0003, 5.0); // 5
        assertEquals(expectedCommission, fee, DELTA);
    }

    // ==================== applySlippage ====================

    @Test
    @DisplayName("applySlippage - FIXED 买入滑点上浮")
    void testSlippageFixedBuy() {
        double result = BacktestUtils.applySlippage(10.0, true, 0.001, 0, 0, "FIXED");
        assertEquals(10.01, result, DELTA);
    }

    @Test
    @DisplayName("applySlippage - FIXED 卖出滑点下浮")
    void testSlippageFixedSell() {
        double result = BacktestUtils.applySlippage(10.0, false, 0.001, 0, 0, "FIXED");
        assertEquals(9.99, result, DELTA);
    }

    @Test
    @DisplayName("applySlippage - VOLUME 模型")
    void testSlippageVolume() {
        // amount=100, dayAmount=10000, rate=0.001, base=10
        // impact = sqrt(100/10000) * 0.001 * 10 = 0.1 * 0.001 * 10 = 0.001
        double result = BacktestUtils.applySlippage(10.0, true, 0.001, 100, 10000, "VOLUME");
        assertEquals(10.001, result, DELTA);
    }

    @Test
    @DisplayName("applySlippage - VOLUME dayAmount=0 回退到 FIXED")
    void testSlippageVolumeFallback() {
        double result = BacktestUtils.applySlippage(10.0, true, 0.001, 100, 0, "VOLUME");
        assertEquals(10.01, result, DELTA, "dayAmount=0 应回退到 FIXED 模型");
    }

    // ==================== round ====================

    @Test
    @DisplayName("round - 四舍五入")
    void testRound() {
        assertEquals(3.14, BacktestUtils.round(3.14159, 2), DELTA);
        assertEquals(3.142, BacktestUtils.round(3.14159, 3), DELTA);
        assertEquals(0.0, BacktestUtils.round(0.0, 2), DELTA);
    }

    @Test
    @DisplayName("round - 负数")
    void testRoundNegative() {
        assertEquals(-3.14, BacktestUtils.round(-3.14159, 2), DELTA);
    }

    // ==================== returnPct ====================

    @Test
    @DisplayName("returnPct - 正收益")
    void testReturnPctPositive() {
        assertEquals(0.2, BacktestUtils.returnPct(120, 100), DELTA);
    }

    @Test
    @DisplayName("returnPct - 负收益")
    void testReturnPctNegative() {
        assertEquals(-0.2, BacktestUtils.returnPct(80, 100), DELTA);
    }

    @Test
    @DisplayName("returnPct - entryValue=0 返回0")
    void testReturnPctZeroEntry() {
        assertEquals(0.0, BacktestUtils.returnPct(100, 0), DELTA);
    }
}
