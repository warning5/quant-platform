package com.quant.platform.stock.service;

/**
 * ClickHouse 查询语义化异常。
 *
 * <p>用于替代原先散落在 {@link ClickHouseJdbcClient} / {@link StockDailyChQuery} 中的
 * {@code throw new RuntimeException("ClickHouse … 失败", e)}。公开层
 * {@link ClickHouseStockService} 的软降级回退（CH 不可用 → 回退 MySQL）统一捕获此类型，
 * 既消除了宽泛的 {@code catch (Exception)}，又保留了"查询失败即回退"的语义。</p>
 *
 * <p>作为 {@link RuntimeException} 的子类，无需在方法签名上声明，对现有调用方二进制兼容。</p>
 */
public class ClickHouseQueryException extends RuntimeException {

    public ClickHouseQueryException(String message) {
        super(message);
    }

    public ClickHouseQueryException(String message, Throwable cause) {
        super(message, cause);
    }
}
