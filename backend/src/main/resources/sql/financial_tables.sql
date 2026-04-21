-- ============================================================
-- 财务数据表
-- 创建时间：2026-04-16
-- 说明：三大财务报表 + 财务指标摘要表，用于支撑基本面因子计算
-- ============================================================

-- ------------------------------------------------------------
-- 1. 利润表：stock_income
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `stock_income` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `code` VARCHAR(20) NOT NULL COMMENT '股票代码（不含市场标识，如：000001）',
    `report_date` VARCHAR(10) NOT NULL COMMENT '报告期（如：20250331 表示一季报）',
    `report_type` TINYINT NOT NULL COMMENT '报告类型：1-一季报 2-中报(半年报) 3-三季报 4-年报',
    `end_date` DATE NOT NULL COMMENT '报告截止日期',

    -- 营业相关
    `total_revenue` DECIMAL(20,4) COMMENT '营业总收入（元）',
    `revenue` DECIMAL(20,4) COMMENT '营业收入（元）',
    `total_cost` DECIMAL(20,4) COMMENT '营业总成本（元）',
    `operating_cost` DECIMAL(20,4) COMMENT '营业成本（元）',
    `rd_expense` DECIMAL(20,4) COMMENT '研发费用（元）',
    `selling_expense` DECIMAL(20,4) COMMENT '销售费用（元）',
    `admin_expense` DECIMAL(20,4) COMMENT '管理费用（元）',
    `finance_expense` DECIMAL(20,4) COMMENT '财务费用（元）',

    -- 利润相关
    `operating_profit` DECIMAL(20,4) COMMENT '营业利润（元）',
    `total_profit` DECIMAL(20,4) COMMENT '利润总额（元）',
    `income_tax` DECIMAL(20,4) COMMENT '所得税费用（元）',
    `net_profit` DECIMAL(20,4) COMMENT '净利润（元）',
    `net_profit_incl_minority` DECIMAL(20,4) COMMENT '净利润（含少数股东损益）（元）',
    `np_parent_company_owners` DECIMAL(20,4) COMMENT '归属母公司净利润（元）',
    `np_minority` DECIMAL(20,4) COMMENT '少数股东损益（元）',

    -- 每股指标
    `eps_basic` DECIMAL(10,4) COMMENT '基本每股收益（元/股）',
    `eps_diluted` DECIMAL(10,4) COMMENT '稀释每股收益（元/股）',

    -- 其他综合收益
    `other_comprehensive_income` DECIMAL(20,4) COMMENT '其他综合收益（元）',
    `total_comprehensive_income` DECIMAL(20,4) COMMENT '综合收益总额（元）',

    -- 非经常性损益
    `non_recurring_gain` DECIMAL(20,4) COMMENT '非经常性损益（元）',
    `deducted_np_parent_company` DECIMAL(20,4) COMMENT '扣非归母净利润（元）',

    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code_report` (`code`, `report_date`),
    KEY `idx_report_type` (`report_type`),
    KEY `idx_end_date` (`end_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='利润表';

-- ------------------------------------------------------------
-- 2. 资产负债表：stock_balance
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `stock_balance` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `code` VARCHAR(20) NOT NULL COMMENT '股票代码（不含市场标识，如：000001）',
    `report_date` VARCHAR(10) NOT NULL COMMENT '报告期',
    `report_type` TINYINT NOT NULL COMMENT '报告类型：1-一季报 2-中报 3-三季报 4-年报',
    `end_date` DATE NOT NULL COMMENT '报告截止日期',

    -- 资产
    `total_assets` DECIMAL(20,4) COMMENT '资产总计（元）',
    `total_current_assets` DECIMAL(20,4) COMMENT '流动资产合计（元）',
    `total_non_current_assets` DECIMAL(20,4) COMMENT '非流动资产合计（元）',
    `cash_and_equivalents` DECIMAL(20,4) COMMENT '货币资金（元）',
    `trading_assets` DECIMAL(20,4) COMMENT '交易性金融资产（元）',
    `notes_receivable` DECIMAL(20,4) COMMENT '应收票据（元）',
    `accounts_receivable` DECIMAL(20,4) COMMENT '应收账款（元）',
    `prepayments` DECIMAL(20,4) COMMENT '预付款项（元）',
    `other_receivable` DECIMAL(20,4) COMMENT '其他应收款（元）',
    `inventory` DECIMAL(20,4) COMMENT '存货（元）',
    `contract_assets` DECIMAL(20,4) COMMENT '合同资产（元）',
    `long_term_equity_invest` DECIMAL(20,4) COMMENT '长期股权投资（元）',
    `fixed_assets` DECIMAL(20,4) COMMENT '固定资产（元）',
    `construction_in_progress` DECIMAL(20,4) COMMENT '在建工程（元）',
    `intangible_assets` DECIMAL(20,4) COMMENT '无形资产（元）',
    `goodwill` DECIMAL(20,4) COMMENT '商誉（元）',
    `long_term_prepaid_expense` DECIMAL(20,4) COMMENT '长期待摊费用（元）',
    `deferred_tax_assets` DECIMAL(20,4) COMMENT '递延所得税资产（元）',

    -- 负债
    `total_liabilities` DECIMAL(20,4) COMMENT '负债合计（元）',
    `total_current_liabilities` DECIMAL(20,4) COMMENT '流动负债合计（元）',
    `total_non_current_liabilities` DECIMAL(20,4) COMMENT '非流动负债合计（元）',
    `short_term_borrowing` DECIMAL(20,4) COMMENT '短期借款（元）',
    `notes_payable` DECIMAL(20,4) COMMENT '应付票据（元）',
    `accounts_payable` DECIMAL(20,4) COMMENT '应付账款（元）',
    `advance_peceipts` DECIMAL(20,4) COMMENT '预收款项（元）',
    `contract_liabilities` DECIMAL(20,4) COMMENT '合同负债（元）',
    `employee_benefit_payable` DECIMAL(20,4) COMMENT '应付职工薪酬（元）',
    `taxs_payable` DECIMAL(20,4) COMMENT '应交税费（元）',
    `other_payable` DECIMAL(20,4) COMMENT '其他应付款（元）',
    `long_term_borrowing` DECIMAL(20,4) COMMENT '长期借款（元）',
    `bonds_payable` DECIMAL(20,4) COMMENT '应付债券（元）',
    `lease_liabilities` DECIMAL(20,4) COMMENT '租赁负债（元）',
    `deferred_tax_liabilities` DECIMAL(20,4) COMMENT '递延所得税负债（元）',

    -- 所有者权益
    `total_equity` DECIMAL(20,4) COMMENT '所有者权益合计（元）',
    `parent_equity` DECIMAL(20,4) COMMENT '归属母公司所有者权益（元）',
    `minority_interests` DECIMAL(20,4) COMMENT '少数股东权益（元）',
    `paid_in_capital` DECIMAL(20,4) COMMENT '实收资本（元）',
    `capital_reserve` DECIMAL(20,4) COMMENT '资本公积（元）',
    `surplus_reserve` DECIMAL(20,4) COMMENT '盈余公积（元）',
    `treasury_stock` DECIMAL(20,4) COMMENT '库存股（元）',
    `undistributed_profit` DECIMAL(20,4) COMMENT '未分配利润（元）',

    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code_report` (`code`, `report_date`),
    KEY `idx_report_type` (`report_type`),
    KEY `idx_end_date` (`end_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='资产负债表';

-- ------------------------------------------------------------
-- 3. 现金流量表：stock_cashflow
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `stock_cashflow` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `code` VARCHAR(20) NOT NULL COMMENT '股票代码（不含市场标识，如：000001）',
    `report_date` VARCHAR(10) NOT NULL COMMENT '报告期',
    `report_type` TINYINT NOT NULL COMMENT '报告类型：1-一季报 2-中报 3-三季报 4-年报',
    `end_date` DATE NOT NULL COMMENT '报告截止日期',

    -- 经营活动现金流
    `net_operate_cf` DECIMAL(20,4) COMMENT '经营活动产生的现金流量净额（元）',
    `cash_received_sales` DECIMAL(20,4) COMMENT '销售商品、提供劳务收到的现金（元）',
    `tax_refund_received` DECIMAL(20,4) COMMENT '收到的税费返还（元）',
    `cash_paid_goods_services` DECIMAL(20,4) COMMENT '购买商品、接受劳务支付的现金（元）',
    `cash_paid_employee` DECIMAL(20,4) COMMENT '支付给职工以及为职工支付的现金（元）',
    `cash_paid_tax` DECIMAL(20,4) COMMENT '支付的各项税费（元）',

    -- 投资活动现金流
    `net_invest_cf` DECIMAL(20,4) COMMENT '投资活动产生的现金流量净额（元）',
    `cash_received_invest_income` DECIMAL(20,4) COMMENT '收回投资收到的现金（元）',
    `cash_received_invest_return` DECIMAL(20,4) COMMENT '取得投资收益收到的现金（元）',
    `dispose_invest_income` DECIMAL(20,4) COMMENT '处置固定资产等收回的现金净额（元）',
    `cash_paid_invest` DECIMAL(20,4) COMMENT '投资支付的现金（元）',
    `cash_paid_acquisition` DECIMAL(20,4) COMMENT '购建固定资产等支付的现金（元）',

    -- 筹资活动现金流
    `net_finance_cf` DECIMAL(20,4) COMMENT '筹资活动产生的现金流量净额（元）',
    `cash_received_absorb_invest` DECIMAL(20,4) COMMENT '吸收投资收到的现金（元）',
    `cash_received_borrowing` DECIMAL(20,4) COMMENT '取得借款收到的现金（元）',
    `cash_paid_borrowing` DECIMAL(20,4) COMMENT '偿还债务支付的现金（元）',
    `cash_paid_dividend` DECIMAL(20,4) COMMENT '分配股利、利润或偿付利息支付的现金（元）',

    -- 汇率及现金净增加
    `exchange_rate_effect` DECIMAL(20,4) COMMENT '汇率变动对现金的影响（元）',
    `net_cash_increase` DECIMAL(20,4) COMMENT '现金及现金等价物净增加额（元）',
    `cash_at_beginning` DECIMAL(20,4) COMMENT '期初现金及现金等价物余额（元）',
    `cash_at_end` DECIMAL(20,4) COMMENT '期末现金及现金等价物余额（元）',

    -- 自由现金流（计算字段，Python 脚本填充）
    `free_cash_flow` DECIMAL(20,4) COMMENT '自由现金流 = 经营净现金流 - 资本支出（元）',

    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code_report` (`code`, `report_date`),
    KEY `idx_report_type` (`report_type`),
    KEY `idx_end_date` (`end_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='现金流量表';

-- ------------------------------------------------------------
-- 4. 财务指标摘要表：stock_financial_indicator
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `stock_financial_indicator` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `code` VARCHAR(20) NOT NULL COMMENT '股票代码（不含市场标识，如：000001）',
    `report_date` VARCHAR(10) NOT NULL COMMENT '报告期',
    `report_type` TINYINT NOT NULL COMMENT '报告类型：1-一季报 2-中报 3-三季报 4-年报',
    `end_date` DATE NOT NULL COMMENT '报告截止日期',

    -- 盈利能力
    `gross_profit_margin` DECIMAL(10,4) COMMENT '毛利率（%）',
    `net_profit_margin` DECIMAL(10,4) COMMENT '净利率（%）',
    `roe` DECIMAL(10,4) COMMENT '净资产收益率 ROE（%）',
    `roa` DECIMAL(10,4) COMMENT '总资产收益率 ROA（%）',
    `roic` DECIMAL(10,4) COMMENT '投入资本回报率 ROIC（%）',

    -- 成长能力
    `revenue_yoy` DECIMAL(10,4) COMMENT '营业收入同比增长率（%）',
    `net_profit_yoy` DECIMAL(10,4) COMMENT '净利润同比增长率（%）',
    `operating_profit_yoy` DECIMAL(10,4) COMMENT '营业利润同比增长率（%）',
    `total_assets_yoy` DECIMAL(10,4) COMMENT '总资产同比增长率（%）',
    `total_equity_yoy` DECIMAL(10,4) COMMENT '净资产同比增长率（%）',

    -- 偿债能力
    `current_ratio` DECIMAL(10,4) COMMENT '流动比率',
    `quick_ratio` DECIMAL(10,4) COMMENT '速动比率',
    `debt_to_asset_ratio` DECIMAL(10,4) COMMENT '资产负债率（%）',
    `debt_to_equity_ratio` DECIMAL(10,4) COMMENT '权益乘数（资产负债率的倒数形式）',
    `interest_coverage_ratio` DECIMAL(10,4) COMMENT '利息保障倍数',

    -- 营运能力
    `accounts_receivable_turnover` DECIMAL(10,4) COMMENT '应收账款周转率（次）',
    `ar_turnover_days` DECIMAL(10,2) COMMENT '应收账款周转天数（天）',
    `inventory_turnover` DECIMAL(10,4) COMMENT '存货周转率（次）',
    `inventory_turnover_days` DECIMAL(10,2) COMMENT '存货周转天数（天）',
    `total_assets_turnover` DECIMAL(10,4) COMMENT '总资产周转率（次）',

    -- 现金流指标
    `operating_cf_to_np` DECIMAL(10,4) COMMENT '经营现金流/净利润',
    `operating_cf_to_debt` DECIMAL(10,4) COMMENT '经营现金流/负债',
    `sales_cash_ratio` DECIMAL(10,4) COMMENT '销售收现比率',

    -- 每股指标
    `bps` DECIMAL(10,4) COMMENT '每股净资产（元）',
    `operating_revenue_per_share` DECIMAL(10,4) COMMENT '每股营业收入（元）',

    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code_report` (`code`, `report_date`),
    KEY `idx_report_type` (`report_type`),
    KEY `idx_end_date` (`end_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='财务指标摘要表';
