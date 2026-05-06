-- 模拟盘交易表
CREATE TABLE IF NOT EXISTS paper_trading (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    strategy_id BIGINT NOT NULL COMMENT '关联策略ID',
    strategy_code VARCHAR(50) COMMENT '策略代码',
    status VARCHAR(20) DEFAULT 'RUNNING' COMMENT '状态: RUNNING/PAUSED/STOPPED',
    initial_capital DECIMAL(18,2) DEFAULT 1000000 COMMENT '初始资金(元)',
    current_capital DECIMAL(18,2) COMMENT '当前可用资金',
    total_assets DECIMAL(18,2) COMMENT '总资产(资金+持仓市值)',
    position_count INT DEFAULT 0 COMMENT '持仓股票数',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_strategy (strategy_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模拟盘交易';

-- 模拟盘持仓表
CREATE TABLE IF NOT EXISTS paper_position (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    paper_id BIGINT NOT NULL COMMENT '模拟盘ID',
    code VARCHAR(20) NOT NULL COMMENT '股票代码',
    name VARCHAR(100) COMMENT '股票名称',
    shares INT NOT NULL COMMENT '持仓数量',
    cost_price DECIMAL(12,4) NOT NULL COMMENT '成本价',
    current_price DECIMAL(12,4) COMMENT '当前价',
    market_value DECIMAL(18,2) COMMENT '持仓市值',
    profit_loss DECIMAL(18,2) COMMENT '浮动盈亏',
    profit_loss_pct DECIMAL(10,4) COMMENT '盈亏比例',
    buy_date DATE COMMENT '买入日期',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_paper (paper_id),
    INDEX idx_code (code),
    UNIQUE KEY uk_paper_code (paper_id, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模拟盘持仓';

-- 模拟盘信号表
CREATE TABLE IF NOT EXISTS paper_signal (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    paper_id BIGINT NOT NULL COMMENT '模拟盘ID',
    signal_date DATE NOT NULL COMMENT '信号日期',
    code VARCHAR(20) NOT NULL COMMENT '股票代码',
    name VARCHAR(100) COMMENT '股票名称',
    direction VARCHAR(10) NOT NULL COMMENT '方向: BUY/SELL',
    signal_price DECIMAL(12,4) COMMENT '信号价格',
    factor_score DECIMAL(10,4) COMMENT '因子综合得分',
    reason VARCHAR(500) COMMENT '信号原因',
    status VARCHAR(20) DEFAULT 'PENDING' COMMENT '状态: PENDING/EXECUTED/SKIPPED/EXPIRED',
    executed_price DECIMAL(12,4) COMMENT '执行价格',
    executed_at DATETIME COMMENT '执行时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_paper_date (paper_id, signal_date),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模拟盘交易信号';

-- 模拟盘净值表
CREATE TABLE IF NOT EXISTS paper_nav (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    paper_id BIGINT NOT NULL COMMENT '模拟盘ID',
    nav_date DATE NOT NULL COMMENT '日期',
    total_assets DECIMAL(18,2) NOT NULL COMMENT '总资产',
    daily_return DECIMAL(10,6) COMMENT '日收益率',
    cumulative_return DECIMAL(10,6) COMMENT '累计收益率',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_paper_date (paper_id, nav_date),
    UNIQUE KEY uk_paper_date (paper_id, nav_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模拟盘净值';
