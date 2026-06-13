-- 自选股观察池
CREATE TABLE IF NOT EXISTS `stock_watchlist` (
    `id`                     BIGINT        NOT NULL AUTO_INCREMENT,
    `stock_code`             VARCHAR(20)   NOT NULL COMMENT '股票代码',
    `stock_name`             VARCHAR(50)   DEFAULT NULL COMMENT '股票名称',
    `group_name`             VARCHAR(50)   DEFAULT 'default' COMMENT '分组名称',
    `reason`                 VARCHAR(500)  DEFAULT NULL COMMENT '加入原因',
    `source`                 VARCHAR(20)   DEFAULT 'MANUAL' COMMENT '来源: MANUAL/RECOMMENDATION/SCREEN',
    `recommendation_batch_id` BIGINT       DEFAULT NULL COMMENT '关联推荐批次ID',
    `target_buy_price`       DECIMAL(10,3) DEFAULT NULL COMMENT '目标买入价',
    `stop_loss_price`        DECIMAL(10,3) DEFAULT NULL COMMENT '止损价',
    `target_sell_price`      DECIMAL(10,3) DEFAULT NULL COMMENT '目标卖出价',
    `watch_end_date`         DATE          DEFAULT NULL COMMENT '观测到期日',
    `notes`                  TEXT          DEFAULT NULL COMMENT '备注',
    `sort_order`             INT           DEFAULT 0 COMMENT '排序序号',
    `archived`               TINYINT       DEFAULT 0 COMMENT '0=活跃, 1=归档',
    `created_at`             DATETIME      DEFAULT CURRENT_TIMESTAMP,
    `updated_at`             DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_stock_code` (`stock_code`, `archived`),
    KEY `idx_group` (`group_name`, `archived`),
    KEY `idx_watch_end` (`watch_end_date`, `archived`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='自选股观察池';
