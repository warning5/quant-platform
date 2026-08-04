package com.quant.platform.backtest.engine;

/**
 * 因子权重（回测引擎内部使用）。
 *
 * <p>God Class 拆分 Phase 5：由 {@code BacktestEngine} 的内部 record 提升为包级顶层 record，
 * 以便 {@link BacktestScoring} 复用。字段与语义不变。</p>
 */
record FactorWeight(String factorCode, double weight) {
}
