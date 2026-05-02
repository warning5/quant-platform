-- ============================================================
-- 情绪数据表（MySQL 副本，ClickHouse 为主要数据源）
-- 创建时间：2026-05-01
-- 说明：涨跌停池 / 北向资金指数 / 北向持股 / 资金情绪代理指标
-- ============================================================

-- ------------------------------------------------------------
-- 1. 涨跌停池：stock_sentiment_zt
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `stock_sentiment_zt`
(
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `ts_code`       VARCHAR(20)  NOT NULL COMMENT '股票代码（纯数字，如 000001）',
    `trade_date`    DATE         NOT NULL COMMENT '交易日期',
    `code`          VARCHAR(20)  NOT NULL COMMENT '股票代码（纯数字，同 ts_code）',
    `name`          VARCHAR(100) COMMENT '股票名称',
    `close`         DECIMAL(10, 2) COMMENT '最新价（元）',
    `pct_change`   DECIMAL(10, 2) COMMENT '涨跌幅（%）',
    `zt_type`       VARCHAR(20)  COMMENT '涨停类型：zt-涨停 dt-跌停 zbgc-炸板',
    `reason`        VARCHAR(500) COMMENT '入选理由/所属行业',
    `is_new`        TINYINT      DEFAULT 0 COMMENT '是否新涨停（1=新涨停，0=非新）',
    `update_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code_date_type` (`code`, `trade_date`, `zt_type`),
    KEY `idx_trade_date` (`trade_date`),
    KEY `idx_zt_type` (`zt_type`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='涨跌停池';

-- ------------------------------------------------------------
-- 2. 北向资金指数：stock_sentiment_hsgt
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `stock_sentiment_hsgt`
(
    `id`               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `trade_date`       DATE          NOT NULL COMMENT '交易日期',
    `north_net_inflow` DECIMAL(20, 2) COMMENT '北向资金净流入（元，负数表示净流出）',
    `shanghai_net`     DECIMAL(20, 2) COMMENT '沪股通净流入（元）',
    `shenzhen_net`     DECIMAL(20, 2) COMMENT '深股通净流入（元）',
    `update_time`      DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_trade_date` (`trade_date`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='北向资金指数日线';

-- ------------------------------------------------------------
-- 3. 北向持股明细：stock_sentiment_hsgt_hold
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `stock_sentiment_hsgt_hold`
(
    `id`            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `trade_date`    DATE          NOT NULL COMMENT '交易日期（北向持股公布日）',
    `ts_code`       VARCHAR(20)  NOT NULL COMMENT '股票代码（纯数字）',
    `code`          VARCHAR(20)  NOT NULL COMMENT '股票代码（同 ts_code）',
    `name`          VARCHAR(100) COMMENT '股票名称',
    `hold_amount`   DECIMAL(20, 2) COMMENT '持股总市值（元）',
    `hold_pct`      DECIMAL(10, 4) COMMENT '持股占流通股比例（%）',
    `update_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code_date` (`code`, `trade_date`),
    KEY `idx_trade_date` (`trade_date`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='北向持股明细';

-- ------------------------------------------------------------
-- 4. 资金情绪代理指标：stock_sentiment_moneyflow
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `stock_sentiment_moneyflow`
(
    `id`             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `ts_code`        VARCHAR(20)  NOT NULL COMMENT '股票代码（纯数字）',
    `trade_date`     DATE         NOT NULL COMMENT '交易日期',
    `code`           VARCHAR(20)  NOT NULL COMMENT '股票代码（同 ts_code）',
    `name`           VARCHAR(100) COMMENT '股票名称',
    `close`          DECIMAL(10, 2) COMMENT '收盘价（元）',
    `pct_change`     DECIMAL(10, 2) COMMENT '涨跌幅（%）',
    `net_main`       DECIMAL(20, 2) DEFAULT 0 COMMENT '主力净流入代理（元，暂无真实数据）',
    `net_main_pct`   DECIMAL(10, 4) DEFAULT 0 COMMENT '主力净流入占比（暂无）',
    `net_huge`       DECIMAL(20, 2) DEFAULT 0 COMMENT '超大单净流入（暂无）',
    `net_big`        DECIMAL(20, 2) DEFAULT 0 COMMENT '大单净流入（暂无）',
    `net_medium`      DECIMAL(20, 2) DEFAULT 0 COMMENT '中单代理（换手率×1e8）',
    `net_small`       DECIMAL(20, 2) DEFAULT 0 COMMENT '小单代理（量比×1e8）',
    `update_time`    DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code_date` (`code`, `trade_date`),
    KEY `idx_trade_date` (`trade_date`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='资金情绪代理指标（涨停池量价提取）';

-- ------------------------------------------------------------
-- 5. 东方财富人气榜：stock_sentiment_hot_rank
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `stock_sentiment_hot_rank`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `rank`        INT          COMMENT '当前排名',
    `ts_code`     VARCHAR(20)  NOT NULL COMMENT '股票代码（纯数字，如000001）',
    `code`        VARCHAR(20)  NOT NULL COMMENT '股票代码（同ts_code）',
    `name`        VARCHAR(100) COMMENT '股票名称',
    `trade_date`  DATE         NOT NULL COMMENT '采集日期',
    `hot_value`   DECIMAL(20,2) COMMENT '热度值',
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code_date` (`code`, `trade_date`),
    KEY `idx_trade_date` (`trade_date`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='东方财富人气榜';

-- ------------------------------------------------------------
-- 6. 东方财富公告：stock_sentiment_notice
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `stock_sentiment_notice`
(
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `ts_code`      VARCHAR(20)  NOT NULL COMMENT '股票代码（纯数字，如000001）',
    `code`         VARCHAR(20)  NOT NULL COMMENT '股票代码（同ts_code）',
    `name`         VARCHAR(100) COMMENT '股票名称',
    `notice_type`  VARCHAR(50)  COMMENT '公告类型（重大事项/财务报告/融资公告等）',
    `notice_date`  DATE         NOT NULL COMMENT '公告日期',
    `title`        VARCHAR(500) COMMENT '公告标题',
    `update_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code_date_type` (`code`, `notice_date`, `notice_type`),
    KEY `idx_notice_date` (`notice_date`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='东方财富公告';

-- ------------------------------------------------------------
-- 7. A股新闻情绪指数：stock_sentiment_news_scope
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `stock_sentiment_news_scope`
(
    `id`              BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `trade_date`      DATE        NOT NULL COMMENT '交易日期',
    `sentiment_index` DECIMAL(10,4) COMMENT '情绪指数',
    `update_time`     DATETIME    DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_trade_date` (`trade_date`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='A股新闻情绪指数（数库）';

-- ------------------------------------------------------------
-- 8. CCTV新闻：stock_sentiment_cctv_news
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `stock_sentiment_cctv_news`
(
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `news_date`    VARCHAR(20)  NOT NULL COMMENT '新闻日期',
    `title`        VARCHAR(500) COMMENT '新闻标题',
    `update_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_news_date` (`news_date`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='CCTV新闻标题';
