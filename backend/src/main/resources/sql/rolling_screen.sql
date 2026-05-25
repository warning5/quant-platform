-- ============================================================
-- rolling_screen_task + rebalance_record 建表脚本
-- 功能：支持「因子选股」的历史滚动回测
-- ============================================================

-- 滚动选股回测任务表
CREATE TABLE IF NOT EXISTS rolling_screen_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_name VARCHAR(200) COMMENT '任务名称',

    -- 选股配置（完整 ScreenRequest JSON，含 factors/weights/thresholds/topN/direction）
    screen_config_json TEXT COMMENT '选股配置JSON（ScreenRequest序列化）',

    -- 回测参数
    start_date DATE NOT NULL COMMENT '回测起始日',
    end_date DATE NOT NULL COMMENT '回测结束日',
    rebalance_freq VARCHAR(20) DEFAULT 'MONTHLY' COMMENT '调仓频率: WEEKLY/BIWEEKLY/MONTHLY',
    initial_capital DECIMAL(18,2) DEFAULT 1000000.00 COMMENT '初始资金(元)',
    commission_rate DECIMAL(8,6) DEFAULT 0.000300 COMMENT '佣金率',
    slippage_rate DECIMAL(8,6) DEFAULT 0.001000 COMMENT '滑点率',
    slippage_model VARCHAR(20) DEFAULT 'FIXED' COMMENT '滑点模型: FIXED/VOLUME',
    order_type VARCHAR(20) DEFAULT 'CLOSE' COMMENT '成交价: CLOSE/NEXT_OPEN/VWAP',
    benchmark_code VARCHAR(20) DEFAULT '000300.SH' COMMENT '基准指数代码',
    weight_mode VARCHAR(20) DEFAULT 'EQUAL' COMMENT '权重分配: EQUAL/SCORE_PROPORTIONAL',
    limit_filter TINYINT(1) DEFAULT 1 COMMENT '涨跌停过滤 0=禁用 1=启用',
    suspend_filter TINYINT(1) DEFAULT 1 COMMENT '停牌过滤 0=禁用 1=启用',
    stamp_tax_rate DECIMAL(8,6) DEFAULT 0.000500 COMMENT '印花税率(仅卖出)',
    min_commission DECIMAL(8,2) DEFAULT 5.00 COMMENT '最低佣金(元/笔)',
    transfer_fee_rate DECIMAL(8,6) DEFAULT 0.000020 COMMENT '过户费率',

    -- 任务状态
    status VARCHAR(20) DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/COMPLETED/FAILED/CANCELLED',
    progress INT DEFAULT 0 COMMENT '进度 0-100',
    error_message TEXT COMMENT '错误信息',

    -- 净值摘要（完成后填充，加速列表查询）
    final_nav DECIMAL(12,6) COMMENT '最终净值(从1.0起)',
    total_return DECIMAL(10,6) COMMENT '累计收益率',
    annual_return DECIMAL(10,6) COMMENT '年化收益率',
    max_drawdown DECIMAL(10,6) COMMENT '最大回撤',
    sharpe_ratio DECIMAL(10,6) COMMENT '夏普比率',

    -- 时间戳
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    started_at DATETIME COMMENT '开始执行时间',
    completed_at DATETIME COMMENT '完成时间',

    INDEX idx_status (status),
    INDEX idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='滚动选股回测任务';

-- 调仓记录表（每次调仓一笔记录）
CREATE TABLE IF NOT EXISTS rebalance_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL COMMENT '关联任务ID',
    rebalance_date DATE NOT NULL COMMENT '调仓日期',

    -- 调仓前持仓快照
    old_positions_json TEXT COMMENT '调仓前持仓JSON [{symbol,shares,cost}]',

    -- 调仓后目标持仓
    new_positions_json TEXT COMMENT '调仓后持仓JSON [{symbol,weight,score}]',

    -- 交易明细
    buys_json TEXT COMMENT '买入明细JSON [{symbol,price,shares,amount}]',
    sells_json TEXT COMMENT '卖出明细JSON [{symbol,price,shares,amount,pnl}]',

    -- 组合快照
    cash DECIMAL(18,2) COMMENT '当日现金(元)',
    total_value DECIMAL(18,2) COMMENT '总资产(元)',
    nav DECIMAL(12,6) COMMENT '当日净值(从1.0起)',
    daily_return DECIMAL(10,6) COMMENT '当日收益率',

    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_task_date (task_id, rebalance_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='滚动选股调仓记录';
