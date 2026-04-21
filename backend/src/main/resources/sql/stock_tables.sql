-- 股票基础信息表
CREATE TABLE IF NOT EXISTS `stock_info` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `code` VARCHAR(20) NOT NULL COMMENT '股票代码（不含市场标识，如：000001）',
    `symbol` VARCHAR(20) NOT NULL COMMENT '股票代码（含市场标识，如：000001.SZ）',
    `name` VARCHAR(100) NOT NULL COMMENT '股票名称',
    `market` VARCHAR(20) COMMENT '市场（SH/SZ/BJ）',
    `industry` VARCHAR(100) COMMENT '所属行业',
    `list_date` DATE COMMENT '上市日期',
    `is_hs` TINYINT DEFAULT 0 COMMENT '是否沪深股通（0-否，1-是）',
    `is_st` TINYINT DEFAULT 0 COMMENT '是否ST（0-否，1-是）',
    `total_share` DECIMAL(20,2) COMMENT '总股本（股）',
    `float_share` DECIMAL(20,2) COMMENT '流通股本（股）',
    `total_market_cap` DECIMAL(20,2) COMMENT '总市值（元）',
    `float_market_cap` DECIMAL(20,2) COMMENT '流通市值（元）',
    `pe_ttm` DECIMAL(10,2) COMMENT '市盈率（TTM）',
    `pb` DECIMAL(10,2) COMMENT '市净率',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code` (`code`),
    UNIQUE KEY `uk_symbol` (`symbol`),
    KEY `idx_market` (`market`),
    KEY `idx_industry` (`industry`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='股票基础信息表';

-- 股票每日行情表
CREATE TABLE IF NOT EXISTS `stock_daily` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `code` VARCHAR(20) NOT NULL COMMENT '股票代码（不含市场标识，如：000001）',
    `symbol` VARCHAR(20) NOT NULL COMMENT '股票代码（含市场标识，如：000001.SZ）',
    `trade_date` DATE NOT NULL COMMENT '交易日期',
    `name` VARCHAR(100) COMMENT '股票名称',
    `open_price` DECIMAL(10,2) COMMENT '开盘价',
    `close_price` DECIMAL(10,2) COMMENT '收盘价',
    `high_price` DECIMAL(10,2) COMMENT '最高价',
    `low_price` DECIMAL(10,2) COMMENT '最低价',
    `pre_close` DECIMAL(10,2) COMMENT '昨收价',
    `volume` BIGINT COMMENT '成交量（手）',
    `amount` DECIMAL(20,2) COMMENT '成交额（元）',
    `change_percent` DECIMAL(10,4) COMMENT '涨跌幅（%）',
    `change_amount` DECIMAL(10,2) COMMENT '涨跌额（元）',
    `turnover_rate` DECIMAL(10,4) COMMENT '换手率（%）',
    `market_cap` DECIMAL(20,2) COMMENT '总市值（元）',
    `circ_market_cap` DECIMAL(20,2) COMMENT '流通市值（元）',
    `pe_ttm` DECIMAL(10,2) COMMENT '市盈率（TTM）',
    `pb` DECIMAL(10,2) COMMENT '市净率',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_symbol_date` (`symbol`, `trade_date`),
    KEY `idx_code_date` (`code`, `trade_date`),
    KEY `idx_trade_date` (`trade_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='股票每日行情表';

-- 为已有表补充新增字段（如果表已存在）
ALTER TABLE `stock_daily`
    ADD COLUMN IF NOT EXISTS `symbol` VARCHAR(20) DEFAULT NULL COMMENT '股票代码（含市场标识，如000001.SZ）' AFTER `code`,
    ADD COLUMN IF NOT EXISTS `pre_close` DECIMAL(10,2) COMMENT '昨收价' AFTER `low_price`,
    ADD COLUMN IF NOT EXISTS `market_cap` DECIMAL(20,2) COMMENT '总市值（元）' AFTER `turnover_rate`,
    ADD COLUMN IF NOT EXISTS `circ_market_cap` DECIMAL(20,2) COMMENT '流通市值（元）' AFTER `market_cap`;

-- 股票公司信息表
CREATE TABLE IF NOT EXISTS `stock_company` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `code` VARCHAR(20) NOT NULL COMMENT '股票代码（不含市场标识，如：000001）',
    `symbol` VARCHAR(20) NOT NULL COMMENT '股票代码（含市场标识，如：000001.SZ）',
    `name` VARCHAR(100) COMMENT '公司名称',
    `industry` VARCHAR(100) COMMENT '所属行业',
    `area` VARCHAR(50) COMMENT '所属地区',
    `fullname` VARCHAR(200) COMMENT '公司全称',
    `enname` VARCHAR(200) COMMENT '英文名称',
    `cnspell` VARCHAR(50) COMMENT '拼音缩写',
    `market` VARCHAR(20) COMMENT '市场（SH/SZ/BJ）',
    `exchange` VARCHAR(20) COMMENT '交易所',
    `list_date` DATE COMMENT '上市日期',
    `delist_date` DATE COMMENT '退市日期',
    `introduction` TEXT COMMENT '公司简介',
    `business_scope` TEXT COMMENT '经营范围',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code` (`code`),
    UNIQUE KEY `uk_symbol` (`symbol`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='股票公司信息表';
