-- ============================================================
-- Quant Platform Database Schema
-- 生成自 JPA 实体类，兼容 H2 / MySQL 8.0+
-- 生成时间：2026-03-14
-- ============================================================

-- ------------------------------------------------------------
-- 1. 因子定义：factor_definition
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS factor_definition (
    id                BIGINT          NOT NULL AUTO_INCREMENT,
    factor_code       VARCHAR(50)     NOT NULL COMMENT '因子唯一代码',
    factor_name       VARCHAR(100)    NOT NULL COMMENT '因子名称',
    category          VARCHAR(30)     NOT NULL COMMENT '因子分类: MOMENTUM/VALUE/QUALITY/VOLATILITY/TECHNICAL/FUNDAMENTAL/SENTIMENT/CUSTOM',
    factor_type       VARCHAR(20)     NOT NULL COMMENT '因子类型: BUILTIN/SCRIPTED/COMPOSITE',
    status            VARCHAR(20)     NOT NULL DEFAULT 'DRAFT' COMMENT '状态: DRAFT/TESTING/ACTIVE/DEPRECATED',
    description       TEXT                     COMMENT '因子描述',
    script_code       TEXT                     COMMENT 'Groovy 计算脚本',
    params_config     TEXT                     COMMENT '参数配置 JSON',
    version           INT                      COMMENT '版本号',
    author            VARCHAR(100)             COMMENT '作者',
    created_at        DATETIME                 COMMENT '创建时间',
    updated_at        DATETIME                 COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_factor_code (factor_code)
);


-- ------------------------------------------------------------
-- 3. 因子值：factor_value
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS factor_value (
    id                BIGINT          NOT NULL AUTO_INCREMENT,
    factor_code       VARCHAR(50)     NOT NULL COMMENT '因子代码',
    symbol            VARCHAR(20)     NOT NULL COMMENT '股票代码',
    calc_date         DATE            NOT NULL COMMENT '计算日期',
    factor_val        DECIMAL(20, 8)           COMMENT '因子原始值',
    rank_value        DECIMAL(10, 6)           COMMENT '横截面百分位排名',
    z_score           DECIMAL(10, 6)           COMMENT 'Z-Score 标准化值',
    created_at        DATETIME                 COMMENT '创建时间',
    PRIMARY KEY (id)
);

CREATE INDEX idx_factor_symbol_date ON factor_value (factor_code, symbol, calc_date);
CREATE INDEX idx_factor_date        ON factor_value (factor_code, calc_date);


-- ------------------------------------------------------------
-- 4. 因子测试报告：factor_test_report
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS factor_test_report (
    id                    BIGINT          NOT NULL AUTO_INCREMENT,
    factor_code           VARCHAR(50)     NOT NULL COMMENT '因子代码',
    test_name             VARCHAR(100)             COMMENT '测试名称',
    stock_pool            VARCHAR(30)              COMMENT '股票池: ALL_A/CSI300/CSI500/CSI800/CSI1000',
    rebalance_freq        VARCHAR(20)              COMMENT '调仓频率: DAILY/WEEKLY/MONTHLY',
    start_date            DATE                     COMMENT '测试开始日期',
    end_date              DATE                     COMMENT '测试结束日期',
    status                VARCHAR(20)     NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/RUNNING/COMPLETED/FAILED',
    -- IC 统计
    ic_mean               DECIMAL(10, 6)           COMMENT 'IC 均值',
    ic_std                DECIMAL(10, 6)           COMMENT 'IC 标准差',
    icir                  DECIMAL(10, 6)           COMMENT 'ICIR',
    ic_positive_rate      DECIMAL(10, 6)           COMMENT 'IC 正值率',
    rank_ic_mean          DECIMAL(10, 6)           COMMENT 'Rank IC 均值',
    rank_icir             DECIMAL(10, 6)           COMMENT 'Rank ICIR',
    ic_t_stat             DECIMAL(10, 6)           COMMENT 'IC t检验统计量',
    ic_p_value            DECIMAL(10, 6)           COMMENT 'IC t检验 p值',
    -- 因子衰减分析
    decay_periods         DECIMAL(10, 2)           COMMENT '因子有效期(期数)',
    half_life_periods     DECIMAL(10, 2)           COMMENT '因子半衰期(期数)',
    decay_coefficient    DECIMAL(10, 6)           COMMENT '因子衰减系数',
    decay_r_squared       DECIMAL(10, 6)           COMMENT '因子衰减拟合优度R²',
    decay_series_json     TEXT                     COMMENT '因子衰减序列JSON',
    correlation_matrix_json TEXT                    COMMENT '因子间相关性矩阵JSON',
    -- 分层回测
    top_group_return      DECIMAL(10, 6)           COMMENT '多头组收益',
    bottom_group_return   DECIMAL(10, 6)           COMMENT '空头组收益',
    best_sharpe           DECIMAL(10, 6)           COMMENT '最佳分组夏普比率',
    active_volatility     DECIMAL(10, 6)           COMMENT '多头组主动年化波动率',
    win_rate_vs_benchmark DECIMAL(10, 6)           COMMENT '多头组相对基准胜率',
    monotonicity          DECIMAL(10, 6)           COMMENT '单调性得分',
    group_ir              DECIMAL(10, 6)           COMMENT '分组收益的信息比率',
    ls_p_value            DECIMAL(10, 6)           COMMENT '多空收益 t检验 p值',
    long_short_return     DECIMAL(10, 6)           COMMENT '多空组合收益',
    group_count           INT                      COMMENT '分组数(固定5)',
    -- 详细数据 JSON
    ic_series_json        TEXT                     COMMENT 'IC 时序 JSON',
    group_returns_json    TEXT                     COMMENT '分层收益 JSON',
    group_nav_json        TEXT                     COMMENT '分组净值曲线',
    long_short_nav_json    TEXT                     COMMENT '多空净值曲线',
    version               INT                      COMMENT '版本号',
    author                VARCHAR(100)             COMMENT '作者',
    completed_at          DATETIME                 COMMENT '完成时间',
    error_message         TEXT                     COMMENT '失败原因',
    created_at            DATETIME                 COMMENT '创建时间',
    updated_at            DATETIME                 COMMENT '更新时间',
    PRIMARY KEY (id)
);


-- ------------------------------------------------------------
-- 5. 策略定义：strategy_definition
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS strategy_definition (
    id                    BIGINT          NOT NULL AUTO_INCREMENT,
    strategy_code         VARCHAR(50)     NOT NULL COMMENT '策略唯一代码',
    strategy_name         VARCHAR(100)    NOT NULL COMMENT '策略名称',
    strategy_type         VARCHAR(30)     NOT NULL COMMENT '策略类型: FACTOR_LONG/LONG_SHORT/MARKET_NEUTRAL/MOMENTUM/MEAN_REVERSION/CUSTOM',
    status                VARCHAR(20)     NOT NULL DEFAULT 'DRAFT' COMMENT '状态: DRAFT/TESTING/ACTIVE/DEPRECATED',
    description           TEXT                     COMMENT '策略描述',
    rebalance_frequency   VARCHAR(20)              COMMENT '调仓频率: DAILY/WEEKLY/MONTHLY',
    max_position_count    INT                      COMMENT '最大持仓数量',
    position_size_type    VARCHAR(20)              COMMENT '仓位大小类型: EQUAL/FACTOR_WEIGHTED/CUSTOM',
    stop_loss_pct         DECIMAL(10, 4)           COMMENT '止损比例(%)',
    stop_profit_pct       DECIMAL(10, 4)           COMMENT '止盈比例(%)',
    max_drawdown_pct      DECIMAL(10, 4)           COMMENT '最大回撤限制(%)',
    factor_config_json    TEXT                     COMMENT '因子配置 JSON',
    filter_config_json    TEXT                     COMMENT '选股过滤配置 JSON',
    script_code           TEXT                     COMMENT 'Groovy 策略脚本',
    version               INT                      COMMENT '版本号',
    author                VARCHAR(100)             COMMENT '作者',
    created_at            DATETIME                 COMMENT '创建时间',
    updated_at            DATETIME                 COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_strategy_code (strategy_code)
);


-- ------------------------------------------------------------
-- 6. 回测任务：backtest_task
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS backtest_task (
    id                    BIGINT          NOT NULL AUTO_INCREMENT,
    task_name             VARCHAR(100)             COMMENT '任务名称',
    strategy_id           BIGINT                   COMMENT '关联策略 ID',
    strategy_code         VARCHAR(50)              COMMENT '关联策略代码',
    start_date            DATE                     COMMENT '回测开始日期',
    end_date              DATE                     COMMENT '回测结束日期',
    initial_capital       DECIMAL(20, 2)  NOT NULL DEFAULT 1000000.00 COMMENT '初始资金',
    commission_rate       DECIMAL(10, 6)  NOT NULL DEFAULT 0.0003 COMMENT '佣金率',
    slippage_rate         DECIMAL(10, 6)  NOT NULL DEFAULT 0.0002 COMMENT '滑点率',
    slippage_model        VARCHAR(20)     NOT NULL DEFAULT 'FIXED' COMMENT '滑点模型: FIXED/VOLUME',
    benchmark_code        VARCHAR(50)              COMMENT '基准指数代码',
    limit_filter          TINYINT          NOT NULL DEFAULT 1 COMMENT '涨跌停过滤: 0-关闭, 1-开启',
    suspend_filter        TINYINT          NOT NULL DEFAULT 1 COMMENT '停牌过滤: 0-关闭, 1-开启',
    stamp_tax_rate        DECIMAL(10, 6)  NOT NULL DEFAULT 0.0005 COMMENT '印花税率（仅卖出）',
    min_commission        DECIMAL(10, 2)  NOT NULL DEFAULT 5.00 COMMENT '最低佣金（元/笔）',
    dividend_reinvest      TINYINT          NOT NULL DEFAULT 0 COMMENT '分红处理: 0-关闭, 1-开启（分红到账+送转调整）',
    transfer_fee_rate     DECIMAL(10, 6)  NOT NULL DEFAULT 0.00002 COMMENT '过户费率（仅上交所，双向，默认0.02‰）',
    order_type            VARCHAR(20)     NOT NULL DEFAULT 'CLOSE' COMMENT '成交模式: CLOSE/NEXT_OPEN/VWAP',
    status                VARCHAR(20)     NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/RUNNING/COMPLETED/FAILED/CANCELLED',
    progress              INT             NOT NULL DEFAULT 0 COMMENT '进度(0-100)',
    error_message         TEXT                     COMMENT '失败原因',
    version               INT                      COMMENT '版本号',
    author                VARCHAR(100)             COMMENT '作者',
    created_at            DATETIME                 COMMENT '创建时间',
    updated_at            DATETIME                 COMMENT '更新时间',
    started_at            DATETIME                 COMMENT '开始执行时间',
    completed_at          DATETIME                 COMMENT '完成时间',
    PRIMARY KEY (id)
);


-- ------------------------------------------------------------
-- 7. 回测报告：backtest_report
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS backtest_report (
    id                       BIGINT          NOT NULL AUTO_INCREMENT,
    task_id                  BIGINT                   COMMENT '关联回测任务 ID',
    strategy_code            VARCHAR(50)              COMMENT '策略代码',
    -- 收益指标
    total_return             DECIMAL(10, 6)           COMMENT '总收益率',
    annual_return            DECIMAL(10, 6)           COMMENT '年化收益率',
    benchmark_return         DECIMAL(10, 6)           COMMENT '基准总收益率',
    benchmark_annual_return  DECIMAL(10, 6)           COMMENT '基准年化收益率',
    excess_return            DECIMAL(10, 6)           COMMENT '超额收益率（年化）',
    -- 风险指标
    volatility               DECIMAL(10, 6)           COMMENT '年化波动率',
    sharpe_ratio             DECIMAL(10, 6)           COMMENT '夏普比率',
    sortino_ratio            DECIMAL(10, 6)           COMMENT '索提诺比率',
    calmar_ratio             DECIMAL(10, 6)           COMMENT '卡玛比率',
    max_drawdown             DECIMAL(10, 6)           COMMENT '最大回撤',
    max_drawdown_duration    INT                      COMMENT '最大回撤持续天数',
    information_ratio        DECIMAL(10, 6)           COMMENT '信息比率',
    alpha                    DECIMAL(10, 6)           COMMENT 'Alpha（超额收益）',
    beta                     DECIMAL(10, 6)           COMMENT 'Beta（系统风险）',
    tracking_error           DECIMAL(10, 6)           COMMENT '跟踪误差',
    downside_risk            DECIMAL(10, 6)           COMMENT '下行风险',
    -- 交易统计
    total_trades             INT                      COMMENT '总交易次数',
    win_rate                 DECIMAL(10, 6)           COMMENT '胜率',
    avg_win_return           DECIMAL(10, 6)           COMMENT '平均盈利',
    avg_loss_return          DECIMAL(10, 6)           COMMENT '平均亏损',
    profit_loss_ratio        DECIMAL(10, 6)           COMMENT '盈亏比',
    -- 详细数据 JSON
    equity_curve_json        LONGTEXT                 COMMENT '净值曲线 JSON',
    benchmark_curve_json     LONGTEXT                 COMMENT '基准逐日净值曲线 JSON',
    drawdown_series_json     LONGTEXT                 COMMENT '回撤序列 JSON',
    monthly_returns_json     LONGTEXT                 COMMENT '月度收益 JSON',
    position_history_json    LONGTEXT                 COMMENT '持仓历史 JSON',
    trade_log_json           LONGTEXT                 COMMENT '交易日志 JSON',
    created_at               DATETIME                 COMMENT '创建时间',
    PRIMARY KEY (id),
    CONSTRAINT fk_report_task FOREIGN KEY (task_id) REFERENCES backtest_task (id)
);


-- ------------------------------------------------------------
-- 8. 分红除权：stock_dividend
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS stock_dividend (
    id                    BIGINT          NOT NULL AUTO_INCREMENT,
    code                  VARCHAR(20)     NOT NULL COMMENT '股票代码（不含市场后缀，如 600519）',
    name                  VARCHAR(100)             COMMENT '股票名称',
    ex_dividend_date      DATE            NOT NULL COMMENT '除权除息日',
    record_date           DATE                     COMMENT '股权登记日',
    pay_date              DATE                     COMMENT '派息日',
    cash_dividend         DECIMAL(14,6)            COMMENT '每股派息（元，税前）',
    stock_dividend        DECIMAL(14,6)            COMMENT '每股送股（股）',
    convert_dividend      DECIMAL(14,6)            COMMENT '每股转增（股）',
    report_year           VARCHAR(10)              COMMENT '报告年度',
    created_at            DATETIME                 COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_code_ex_date (code, ex_dividend_date),
    KEY idx_code (code),
    KEY idx_ex_date (ex_dividend_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='分红除权数据';


-- ------------------------------------------------------------
-- 参数优化报告：param_optimize_report
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS param_optimize_report (
    id                  BIGINT          NOT NULL AUTO_INCREMENT,
    job_id              VARCHAR(50)     NOT NULL COMMENT '优化任务ID',
    strategy_id         BIGINT          NOT NULL COMMENT '策略ID',
    strategy_code       VARCHAR(50)              COMMENT '策略代码',
    task_name           VARCHAR(200)             COMMENT '任务名称',
    start_date          VARCHAR(10)               COMMENT '开始日期',
    end_date            VARCHAR(10)               COMMENT '结束日期',
    objective           VARCHAR(50)               COMMENT '目标函数',
    status              VARCHAR(20)               COMMENT '状态: PENDING/RUNNING/COMPLETED/FAILED',
    total               INT                       COMMENT '总参数组合数',
    done                INT                       COMMENT '已完成数',
    progress            INT                       COMMENT '进度百分比',
    best_params_json    TEXT                      COMMENT '最优参数 JSON',
    best_score          DECIMAL(20, 8)            COMMENT '最优得分',
    best_annual_return  DECIMAL(20, 8)            COMMENT '最优年化收益',
    best_max_drawdown   DECIMAL(20, 8)            COMMENT '最优最大回撤',
    results_json        LONGTEXT                  COMMENT '全部结果 JSON',
    error_message       TEXT                      COMMENT '错误信息',
    elapsed_ms          BIGINT                    COMMENT '执行耗时',
    created_at          DATETIME                  COMMENT '创建时间',
    updated_at          DATETIME                  COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_job_id (job_id),
    KEY idx_strategy_id (strategy_id),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='参数优化报告';