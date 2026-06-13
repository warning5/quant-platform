CREATE TABLE IF NOT EXISTS stock_minute_kline (
    id BIGINT NOT NULL AUTO_INCREMENT,
    stock_code VARCHAR(20) NOT NULL COMMENT '股票代码',
    datetime DATETIME NOT NULL COMMENT 'K线时间',
    period VARCHAR(10) NOT NULL COMMENT '周期: m1/m5/m15/m30/m60',
    open_price DECIMAL(10,3) DEFAULT NULL COMMENT '开盘价',
    close_price DECIMAL(10,3) DEFAULT NULL COMMENT '收盘价',
    high_price DECIMAL(10,3) DEFAULT NULL COMMENT '最高价',
    low_price DECIMAL(10,3) DEFAULT NULL COMMENT '最低价',
    volume DOUBLE DEFAULT NULL COMMENT '成交量',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_code_datetime_period (stock_code, datetime, period),
    KEY idx_code_date (stock_code, datetime)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分钟K线数据';
