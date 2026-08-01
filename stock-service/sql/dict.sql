-- ============================================================
-- 字典管理：建表 + 全部字典类型种子（P1/P2/P3，共 34 类型）+ 菜单/权限种子
-- 可重复执行（CREATE TABLE IF NOT EXISTS + INSERT IGNORE/幂等）
-- 依赖：无
-- 说明：所有 dict_value 必须与前端业务代码里的真实枚举 key 完全一致，
--       否则前端 useDict 会回退成原始 key。已按代码实际枚举校正
--       （如 BACKTEST_STATUS 用 PENDING/COMPLETED...，STRATEGY_TYPE 用 FACTOR_LONG...）。
-- ⚠️ 任何新建表必须显式 COLLATE=utf8mb4_unicode_ci，否则与业务表 JOIN 必崩。
-- ============================================================

-- ---------- 1. 字典类型表 ----------
CREATE TABLE IF NOT EXISTS sys_dict_type (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    dict_type    VARCHAR(50)  NOT NULL COMMENT '类型编码，如 SLA_SEVERITY',
    type_name    VARCHAR(100) NOT NULL COMMENT '类型名称，如 SLA严重级别',
    description  VARCHAR(255) DEFAULT '' COMMENT '说明',
    status       TINYINT      NOT NULL DEFAULT 1 COMMENT '0禁用 1启用',
    sort         INT          NOT NULL DEFAULT 0 COMMENT '类型展示顺序',
    create_time  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted      TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dict_type (dict_type),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='字典类型表';

-- ---------- 2. 字典数据项表 ----------
CREATE TABLE IF NOT EXISTS sys_dict_data (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    dict_type    VARCHAR(50)  NOT NULL COMMENT '关联 sys_dict_type.dict_type',
    dict_value   VARCHAR(100) NOT NULL COMMENT '业务实际值(代码读它)，如 HIGH',
    dict_label   VARCHAR(100) NOT NULL COMMENT '显示标签，如 高',
    sort         INT          NOT NULL DEFAULT 0 COMMENT '同类型内排序(升序)',
    color        VARCHAR(50)  DEFAULT NULL COMMENT '前端颜色(antd 状态色或 hex)，如 red',
    ext_json     VARCHAR(1000) DEFAULT NULL COMMENT '扩展属性JSON，如 {"notifyLevel":1}',
    remark       VARCHAR(255) DEFAULT '' COMMENT '备注',
    status       TINYINT      NOT NULL DEFAULT 1 COMMENT '0禁用 1启用',
    create_time  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted      TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_type_value (dict_type, dict_value),
    KEY idx_type_status_sort (dict_type, status, sort)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='字典数据项表';

-- ============================================================
-- 3. 字典类型种子（34 个类型，P1/P2/P3 全覆盖）
--    注：FACTOR_TYPE 在代码中为死代码(无 UI 落点)，仍种以便字典完整。
-- ============================================================
INSERT IGNORE INTO sys_dict_type (dict_type, type_name, description, status, sort) VALUES
('SLA_SEVERITY',        'SLA严重级别',       '定时任务 SLA 看板严重等级',        1, 10),
('TASK_STATUS',         '任务执行状态',      '定时任务执行历史状态',            1, 20),
('TASK_TRIGGER_TYPE',   '任务触发类型',      'CRON/MANUAL/DEPENDENCY',         1, 30),
('FACTOR_STATUS',       '因子状态',          '因子生命周期状态',               1, 40),
('FACTOR_TYPE',         '因子类型',          'BUILTIN/SCRIPTED/COMPOSITE',     1, 45),
('FACTOR_CATEGORY',     '因子分类',          '动量/价值/质量等因子分类',        1, 47),
('STRATEGY_STATUS',     '策略状态',          '策略生命周期状态',               1, 50),
('STRATEGY_TYPE',       '策略类型',          '因子多头/多空/市场中性等',        1, 55),
('STRATEGY_FREQ',       '策略频率',          'DAILY/WEEKLY/MONTHLY/QUARTERLY', 1, 60),
('BACKTEST_STATUS',     '回测状态',          '回测任务状态',                   1, 65),
('NOTIFY_CHANNEL',      '通知渠道',          '告警通知渠道',                   1, 70),
('MENU_TYPE',           '菜单类型',          '系统菜单类型(目录/菜单/按钮)',    1, 80),
('SCREEN_OUTLIER',      '离群值处理',        '选股离群值处理方法',              1, 90),
('SCREEN_NORMALIZE',    '标准化方法',        '选股标准化方法',                 1, 91),
('SCREEN_ORTHOGONAL',   '正交化方法',        '选股正交化方法',                 1, 92),
('SCREEN_NEUTRAL',      '中性化方法',        '选股中性化方法',                 1, 93),
('SCREEN_WEIGHT_MODE',  '选股权重模式',      '选股权重模式',                   1, 94),
('SCREEN_FILTER_OP',    '过滤操作符',        '选股过滤操作符',                 1, 95),
('ROLLING_FREQ',        '滚动回测频率',      '滚动回测频率',                   1, 100),
('ROLLING_WEIGHT_MODE', '滚动回测权重模式',  '滚动回测权重模式',               1, 101),
('ROLLING_ORDER_TYPE',  '滚动回测订单类型',  '滚动回测订单类型',               1, 102),
('ROLLING_OP',          '滚动回测比较符',    '滚动回测比较操作符',             1, 103),
('REPORT_TYPE',         '财报类型',          '1一季报/2中报/3三季报/4年报',    1, 110),
('RESEARCH_RATING',     '研报评级',          '研报评级',                       1, 111),
('SELL_ACTION',         '卖出动作',          'HOLD/REDUCE/SELL',              1, 112),
('LLM_RISK_LEVEL',      'LLM风险等级',       'LOW/MEDIUM/HIGH',               1, 120),
('LLM_REC',             'LLM推荐动作',       'BUY/WATCH/SKIP',                1, 121),
('MARKET',              '市场',              'SH/SZ/BJ',                      1, 130),
('DATA_QUALITY_LEVEL',  '数据质量级别',      '数据质量级别',                   1, 131),
('SECTOR_CATEGORY',     '板块分类',          '概念板块→分类(开放域，种代表性样本)', 1, 132),
('RECOMMEND_CORR_GROUP','推荐相关性分组',    '相关性分组',                     1, 140),
('RECOMMEND_REASON',    '推荐屏蔽原因',      '推荐屏蔽原因',                   1, 141),
('ALERT_TYPE',          '告警类型',          '实盘告警类型',                   1, 150),
('EVENT_TYPE',          '事件类型',          '定增/解禁/股权激励等',           1, 151);

-- ============================================================
-- 4. 字典数据项种子
--    颜色使用 antd v5 状态色(success/processing/error/warning/default)
--    或预设色(red/green/blue...)或 hex。
-- ============================================================

-- SLA_SEVERITY：CRITICAL 为新级别示例，sort=0 自动排最前
INSERT IGNORE INTO sys_dict_data (dict_type, dict_value, dict_label, sort, color, ext_json, status) VALUES
('SLA_SEVERITY', 'CRITICAL', '紧急', 0, 'red',    '{"notifyLevel":2}', 1),
('SLA_SEVERITY', 'HIGH',     '高',   1, 'volcano','{"notifyLevel":1}', 1),
('SLA_SEVERITY', 'MEDIUM',   '中',   2, 'orange', '{"notifyLevel":0}', 1),
('SLA_SEVERITY', 'LOW',      '低',   3, 'blue',   '{"notifyLevel":0}', 1);

-- TASK_STATUS（颜色用 antd 状态色，与原代码一致）
INSERT IGNORE INTO sys_dict_data (dict_type, dict_value, dict_label, sort, color, status) VALUES
('TASK_STATUS', 'RUNNING',   '运行中',   1, 'processing', 1),
('TASK_STATUS', 'SUCCESS',   '成功',     2, 'success',    1),
('TASK_STATUS', 'FAILED',    '失败',     3, 'error',      1),
('TASK_STATUS', 'TIMEOUT',   '超时',     4, 'error',      1),
('TASK_STATUS', 'PARTIAL',   '部分成功', 5, 'warning',    1),
('TASK_STATUS', 'CANCELLED', '已取消',   6, 'default',    1);

-- TASK_TRIGGER_TYPE（原 TRIGGER_LABEL 仅标签，无颜色）
INSERT IGNORE INTO sys_dict_data (dict_type, dict_value, dict_label, sort, status) VALUES
('TASK_TRIGGER_TYPE', 'CRON',       '定时触发', 1, 1),
('TASK_TRIGGER_TYPE', 'MANUAL',     '手动触发', 2, 1),
('TASK_TRIGGER_TYPE', 'DEPENDENCY', '依赖触发', 3, 1);

-- FACTOR_STATUS
INSERT IGNORE INTO sys_dict_data (dict_type, dict_value, dict_label, sort, color, status) VALUES
('FACTOR_STATUS', 'DRAFT',     '草稿',   1, 'default',  1),
('FACTOR_STATUS', 'TESTING',   '测试中', 2, 'processing', 1),
('FACTOR_STATUS', 'ACTIVE',    '已激活', 3, 'success',  1),
('FACTOR_STATUS', 'DEPRECATED','已废弃', 4, 'default',  1),
('FACTOR_STATUS', 'DEGRADED',  '已降级', 5, 'error',    1);

-- FACTOR_TYPE（代码中为死代码，仅占位保持字典完整）
INSERT IGNORE INTO sys_dict_data (dict_type, dict_value, dict_label, sort, status) VALUES
('FACTOR_TYPE', 'BUILTIN',   '内置', 1, 1),
('FACTOR_TYPE', 'SCRIPTED',  '脚本', 2, 1),
('FACTOR_TYPE', 'COMPOSITE', '合成', 3, 1);

-- FACTOR_CATEGORY（11 个，颜色取自原 CATEGORY_COLORS）
INSERT IGNORE INTO sys_dict_data (dict_type, dict_value, dict_label, sort, color, status) VALUES
('FACTOR_CATEGORY', 'MOMENTUM',    '动量',   1,  '#fb923c', 1),
('FACTOR_CATEGORY', 'VALUE',       '价值',   2,  '#a78bfa', 1),
('FACTOR_CATEGORY', 'QUALITY',     '质量',   3,  '#facc15', 1),
('FACTOR_CATEGORY', 'VOLATILITY',  '波动率', 4,  '#f472b6', 1),
('FACTOR_CATEGORY', 'TECHNICAL',   '技术',   5,  '#38bdf8', 1),
('FACTOR_CATEGORY', 'FINANCIAL',   '财务',   6,  '#22c55e', 1),
('FACTOR_CATEGORY', 'SENTIMENT',   '情绪',   7,  '#ef4444', 1),
('FACTOR_CATEGORY', 'CHANTHEORY',  '缠论',   8,  '#f97316', 1),
('FACTOR_CATEGORY', 'LIQUIDITY',   '流动性', 9,  '#06b6d4', 1),
('FACTOR_CATEGORY', 'VOLUME_PRICE','量价',   10, '#84cc16', 1),
('FACTOR_CATEGORY', 'CUSTOM',      '自定义', 11, '#9ca3af', 1);

-- STRATEGY_STATUS
INSERT IGNORE INTO sys_dict_data (dict_type, dict_value, dict_label, sort, color, status) VALUES
('STRATEGY_STATUS', 'DRAFT',     '草稿',   1, 'default',   1),
('STRATEGY_STATUS', 'TESTING',   '测试中', 2, 'processing', 1),
('STRATEGY_STATUS', 'ACTIVE',    '已激活', 3, 'success',   1),
('STRATEGY_STATUS', 'DEPRECATED','已废弃', 4, 'default',   1);

-- STRATEGY_TYPE（实际代码枚举，非 BUILTIN/SCRIPTED/COMPOSITE）
INSERT IGNORE INTO sys_dict_data (dict_type, dict_value, dict_label, sort, status) VALUES
('STRATEGY_TYPE', 'FACTOR_LONG',     '因子多头',  1, 1),
('STRATEGY_TYPE', 'LONG_SHORT',      '多空策略',  2, 1),
('STRATEGY_TYPE', 'MARKET_NEUTRAL',  '市场中性',  3, 1),
('STRATEGY_TYPE', 'MOMENTUM',        '动量策略',  4, 1),
('STRATEGY_TYPE', 'MEAN_REVERSION',  '均值回归',  5, 1),
('STRATEGY_TYPE', 'PATTERN',         '形态驱动',  6, 1),
('STRATEGY_TYPE', 'CUSTOM',          '自定义脚本', 7, 1);

-- STRATEGY_FREQ
INSERT IGNORE INTO sys_dict_data (dict_type, dict_value, dict_label, sort, status) VALUES
('STRATEGY_FREQ', 'DAILY',     '日频', 1, 1),
('STRATEGY_FREQ', 'WEEKLY',    '周频', 2, 1),
('STRATEGY_FREQ', 'MONTHLY',   '月频', 3, 1),
('STRATEGY_FREQ', 'QUARTERLY', '季频', 4, 1);

-- BACKTEST_STATUS（实际代码枚举 PENDING/COMPLETED/RUNNING/FAILED/CANCELLED）
INSERT IGNORE INTO sys_dict_data (dict_type, dict_value, dict_label, sort, color, status) VALUES
('BACKTEST_STATUS', 'PENDING',   '等待中', 1, 'default',   1),
('BACKTEST_STATUS', 'RUNNING',   '运行中', 2, 'processing', 1),
('BACKTEST_STATUS', 'COMPLETED', '已完成', 3, 'success',   1),
('BACKTEST_STATUS', 'FAILED',    '失败',   4, 'error',     1),
('BACKTEST_STATUS', 'CANCELLED', '已取消', 5, 'warning',   1);

-- NOTIFY_CHANNEL
INSERT IGNORE INTO sys_dict_data (dict_type, dict_value, dict_label, sort, color, status) VALUES
('NOTIFY_CHANNEL', 'serverchan', 'Server酱',  1, 'blue',    1),
('NOTIFY_CHANNEL', 'wecom',     '企业微信',  2, 'green',   1),
('NOTIFY_CHANNEL', 'dingtalk',  '钉钉',     3, 'geekblue', 1);

-- MENU_TYPE（dict_value 为整数串，前端用 String(menuType) 比较）
INSERT IGNORE INTO sys_dict_data (dict_type, dict_value, dict_label, sort, color, status) VALUES
('MENU_TYPE', '0', '目录', 1, 'blue',   1),
('MENU_TYPE', '1', '菜单', 2, 'green',  1),
('MENU_TYPE', '2', '按钮', 3, 'orange', 1);

-- SCREEN_OUTLIER
INSERT IGNORE INTO sys_dict_data (dict_type, dict_value, dict_label, sort, status) VALUES
('SCREEN_OUTLIER', 'NONE',     '不处理',       1, 1),
('SCREEN_OUTLIER', 'MAD',      '中位数去极值法', 2, 1),
('SCREEN_OUTLIER', 'SIGMA3',   '3σ法',         3, 1),
('SCREEN_OUTLIER', 'PERCENTILE','百分位截断',  4, 1);

-- SCREEN_NORMALIZE
INSERT IGNORE INTO sys_dict_data (dict_type, dict_value, dict_label, sort, status) VALUES
('SCREEN_NORMALIZE', 'NONE',    '不处理',           1, 1),
('SCREEN_NORMALIZE', 'ZSCORE',  '标准化法(Z-Score)', 2, 1),
('SCREEN_NORMALIZE', 'MINMAX',  'Min-Max归一化',    3, 1),
('SCREEN_NORMALIZE', 'RANK',    '百分位排名',       4, 1);

-- SCREEN_ORTHOGONAL
INSERT IGNORE INTO sys_dict_data (dict_type, dict_value, dict_label, sort, status) VALUES
('SCREEN_ORTHOGONAL', 'NONE',    '不正交化',     1, 1),
('SCREEN_ORTHOGONAL', 'SCHMIDT', '施密特正交化', 2, 1);

-- SCREEN_NEUTRAL
INSERT IGNORE INTO sys_dict_data (dict_type, dict_value, dict_label, sort, status) VALUES
('SCREEN_NEUTRAL', 'NONE',       '不中性化',           1, 1),
('SCREEN_NEUTRAL', 'INDUSTRY',   '行业中性化',         2, 1),
('SCREEN_NEUTRAL', 'MARKET_CAP', '市值中性化',         3, 1),
('SCREEN_NEUTRAL', 'BOTH',       '行业+市值双重中性化', 4, 1);

-- SCREEN_WEIGHT_MODE
INSERT IGNORE INTO sys_dict_data (dict_type, dict_value, dict_label, sort, status) VALUES
('SCREEN_WEIGHT_MODE', 'EQUAL', '等权',     1, 1),
('SCREEN_WEIGHT_MODE', 'IC',    'IC动态加权', 2, 1),
('SCREEN_WEIGHT_MODE', 'IR',    'IR动态加权', 3, 1);

-- SCREEN_FILTER_OP
INSERT IGNORE INTO sys_dict_data (dict_type, dict_value, dict_label, sort, status) VALUES
('SCREEN_FILTER_OP', 'NONE', '无',    1, 1),
('SCREEN_FILTER_OP', 'GT',   '大于(>)',   2, 1),
('SCREEN_FILTER_OP', 'GTE',  '大于等于(≥)', 3, 1),
('SCREEN_FILTER_OP', 'LT',   '小于(<)',   4, 1),
('SCREEN_FILTER_OP', 'LTE',  '小于等于(≤)', 5, 1);

-- ROLLING_FREQ
INSERT IGNORE INTO sys_dict_data (dict_type, dict_value, dict_label, sort, status) VALUES
('ROLLING_FREQ', 'WEEKLY',   '每周',   1, 1),
('ROLLING_FREQ', 'BIWEEKLY', '每两周', 2, 1),
('ROLLING_FREQ', 'MONTHLY',  '每月',   3, 1);

-- ROLLING_WEIGHT_MODE
INSERT IGNORE INTO sys_dict_data (dict_type, dict_value, dict_label, sort, status) VALUES
('ROLLING_WEIGHT_MODE', 'EQUAL',             '等权',       1, 1),
('ROLLING_WEIGHT_MODE', 'SCORE_PROPORTIONAL', '按得分比例', 2, 1);

-- ROLLING_ORDER_TYPE
INSERT IGNORE INTO sys_dict_data (dict_type, dict_value, dict_label, sort, status) VALUES
('ROLLING_ORDER_TYPE', 'CLOSE',    '收盘价',     1, 1),
('ROLLING_ORDER_TYPE', 'NEXT_OPEN', '次日开盘价', 2, 1),
('ROLLING_ORDER_TYPE', 'VWAP',     '成交量加权均价', 3, 1);

-- ROLLING_OP
INSERT IGNORE INTO sys_dict_data (dict_type, dict_value, dict_label, sort, status) VALUES
('ROLLING_OP', 'GT',  '>',  1, 1),
('ROLLING_OP', 'GTE', '≥', 2, 1),
('ROLLING_OP', 'LT',  '<',  3, 1),
('ROLLING_OP', 'LTE', '≤', 4, 1),
('ROLLING_OP', 'EQ',  '=',  5, 1);

-- REPORT_TYPE（value 为数字串）
INSERT IGNORE INTO sys_dict_data (dict_type, dict_value, dict_label, sort, color, status) VALUES
('REPORT_TYPE', '1', '一季报', 1, 'blue',  1),
('REPORT_TYPE', '2', '中报',   2, 'cyan',  1),
('REPORT_TYPE', '3', '三季报', 3, 'orange', 1),
('REPORT_TYPE', '4', '年报',   4, 'green', 1);

-- RESEARCH_RATING（value 为中文评级名）
INSERT IGNORE INTO sys_dict_data (dict_type, dict_value, dict_label, sort, color, status) VALUES
('RESEARCH_RATING', '买入',     '买入',     1, 'red',    1),
('RESEARCH_RATING', '强烈推荐', '强烈推荐', 2, 'volcano', 1),
('RESEARCH_RATING', '推荐',     '推荐',     3, 'orange',  1),
('RESEARCH_RATING', '增持',     '增持',     4, 'gold',    1),
('RESEARCH_RATING', '中性',     '中性',     5, 'blue',    1),
('RESEARCH_RATING', '持有',     '持有',     6, 'cyan',    1),
('RESEARCH_RATING', '减持',     '减持',     7, 'green',   1),
('RESEARCH_RATING', '卖出',     '卖出',     8, 'purple',  1);

-- SELL_ACTION
INSERT IGNORE INTO sys_dict_data (dict_type, dict_value, dict_label, sort, color, status) VALUES
('SELL_ACTION', 'HOLD',   '持有', 1, 'default', 1),
('SELL_ACTION', 'REDUCE', '减仓', 2, 'orange',  1),
('SELL_ACTION', 'SELL',   '卖出', 3, 'red',     1);

-- LLM_RISK_LEVEL（与 SLA_SEVERITY 值相同但语义独立）
INSERT IGNORE INTO sys_dict_data (dict_type, dict_value, dict_label, sort, color, status) VALUES
('LLM_RISK_LEVEL', 'LOW',  '低风险', 1, 'green',  1),
('LLM_RISK_LEVEL', 'MEDIUM','中风险', 2, 'orange', 1),
('LLM_RISK_LEVEL', 'HIGH', '高风险', 3, 'red',    1);

-- LLM_REC
INSERT IGNORE INTO sys_dict_data (dict_type, dict_value, dict_label, sort, color, status) VALUES
('LLM_REC', 'BUY',  '建议买入', 1, 'red',    1),
('LLM_REC', 'WATCH', '持续观察', 2, 'orange',  1),
('LLM_REC', 'SKIP',  '暂不介入', 3, 'default', 1);

-- MARKET
INSERT IGNORE INTO sys_dict_data (dict_type, dict_value, dict_label, sort, color, status) VALUES
('MARKET', 'SH', '沪市', '#1677ff', 1, 1),
('MARKET', 'SZ', '深市', '#52c41a', 1, 1),
('MARKET', 'BJ', '北交所', '#fa8c16', 1, 1);

-- DATA_QUALITY_LEVEL
INSERT IGNORE INTO sys_dict_data (dict_type, dict_value, dict_label, sort, status) VALUES
('DATA_QUALITY_LEVEL', 'stockDaily',        '股票日线', 1, 1),
('DATA_QUALITY_LEVEL', 'factorValue',       '因子数据', 2, 1),
('DATA_QUALITY_LEVEL', 'financialIndicator', '财务数据', 3, 1);

-- SECTOR_CATEGORY（开放域：dict_value=概念名，dict_label=分类。
--   仅种代表性样本，未覆盖的概念名由前端回退为"其他"/#999，可在字典管理页按需增补）
INSERT IGNORE INTO sys_dict_data (dict_type, dict_value, dict_label, sort, color, status) VALUES
('SECTOR_CATEGORY', '银行',       '金融',   '#1677ff', 1, 1),
('SECTOR_CATEGORY', '券商',       '金融',   '#1677ff', 1, 1),
('SECTOR_CATEGORY', '保险',       '金融',   '#1677ff', 1, 1),
('SECTOR_CATEGORY', '房地产',     '地产',   '#52c41a', 1, 1),
('SECTOR_CATEGORY', '煤炭',       '能源',   '#fa8c16', 1, 1),
('SECTOR_CATEGORY', '石油',       '能源',   '#fa8c16', 1, 1),
('SECTOR_CATEGORY', '食品饮料',   '消费',   '#722ed1', 1, 1),
('SECTOR_CATEGORY', '医药生物',   '医药',   '#52c41a', 1, 1),
('SECTOR_CATEGORY', '计算机',     '科技',   '#1890ff', 1, 1),
('SECTOR_CATEGORY', '电子',       '科技',   '#1890ff', 1, 1),
('SECTOR_CATEGORY', '通信',       '科技',   '#1890ff', 1, 1),
('SECTOR_CATEGORY', '汽车',       '制造',   '#13c2c2', 1, 1),
('SECTOR_CATEGORY', '国防军工',   '国防',   '#fa541c', 1, 1),
('SECTOR_CATEGORY', '新能源',     '新能源', '#52c41a', 1, 1),
('SECTOR_CATEGORY', '钢铁',       '材料',   '#8c8c8c', 1, 1),
('SECTOR_CATEGORY', '有色金属',   '材料',   '#8c8c8c', 1, 1),
('SECTOR_CATEGORY', '化工',       '材料',   '#8c8c8c', 1, 1),
('SECTOR_CATEGORY', '传媒',       '传媒',   '#eb2f96', 1, 1),
('SECTOR_CATEGORY', '农林牧渔',   '农业',   '#a0d911', 1, 1),
('SECTOR_CATEGORY', '机械设备',   '制造',   '#13c2c2', 1, 1);

-- RECOMMEND_CORR_GROUP
INSERT IGNORE INTO sys_dict_data (dict_type, dict_value, dict_label, sort, status) VALUES
('RECOMMEND_CORR_GROUP', '银行',       '金融板块', 1, 1),
('RECOMMEND_CORR_GROUP', '房地产开发', '地产链',   2, 1),
('RECOMMEND_CORR_GROUP', '煤炭',       '能源链',   3, 1),
('RECOMMEND_CORR_GROUP', '食品饮料',   '消费链',   4, 1),
('RECOMMEND_CORR_GROUP', '计算机',     'TMT',     5, 1),
('RECOMMEND_CORR_GROUP', '汽车',       '制造链',   6, 1),
('RECOMMEND_CORR_GROUP', '医药生物',   '防御板块', 7, 1),
('RECOMMEND_CORR_GROUP', '电子',       '科技制造', 8, 1);

-- RECOMMEND_REASON
INSERT IGNORE INTO sys_dict_data (dict_type, dict_value, dict_label, sort, color, status) VALUES
('RECOMMEND_REASON', 'CONSECUTIVE_LOSS',     '连续失利',   1, 'orange',  1),
('RECOMMEND_REASON', 'LOW_HIT_RATE',        '低命中率',   2, 'gold',    1),
('RECOMMEND_REASON', 'SEVERE_LOSS',         '踩雷',      3, 'red',     1),
('RECOMMEND_REASON', 'REPEATED_SEVERE_LOSS','多次踩雷',   4, 'volcano', 1),
('RECOMMEND_REASON', 'MANUAL',              '手动屏蔽',   5, 'blue',    1);

-- ALERT_TYPE
INSERT IGNORE INTO sys_dict_data (dict_type, dict_value, dict_label, sort, status) VALUES
('ALERT_TYPE', 'MA_BREAK',          '均线破位',   1, 1),
('ALERT_TYPE', 'DROP',              '大跌',      2, 1),
('ALERT_TYPE', 'NOTICE',            '公告',      3, 1),
('ALERT_TYPE', 'REPORT',            '研报',      4, 1),
('ALERT_TYPE', 'RISK_CONCENTRATION','集中度',    5, 1),
('ALERT_TYPE', 'RISK_INDUSTRY',     '行业暴露',  6, 1),
('ALERT_TYPE', 'RISK_DRAWDOWN',     '回撤',      7, 1);

-- EVENT_TYPE
INSERT IGNORE INTO sys_dict_data (dict_type, dict_value, dict_label, sort, status) VALUES
('EVENT_TYPE', 'EVENT_INCREASE', '定增',     1, 1),
('EVENT_TYPE', 'EVENT_UNLOCK',   '解禁',     2, 1),
('EVENT_TYPE', 'EVENT_INCENTIVE','股权激励', 3, 1),
('EVENT_TYPE', 'EVENT_FORECAST', '业绩预告', 4, 1),
('EVENT_TYPE', 'EVENT_EXPRESS',  '业绩快报', 5, 1);

-- ============================================================
-- 5. 菜单 + 按钮权限节点（幂等：path 唯一 + MAX(id)+1）
-- ============================================================
-- 字典管理菜单挂在"系统管理"(parent_id=1) 下
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, icon, permission, sort, status, create_time, update_time, deleted)
SELECT COALESCE((SELECT MAX(id) FROM sys_menu), 0) + 1, 1, '字典管理', 1, '/system/dict', 'system/DictManage', 'TagsOutlined', 'system:dict:list', 9, 1, NOW(), NOW(), 0
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/system/dict');

-- 拿到字典菜单 id，供按钮节点挂接
SET @dictMenuId = (SELECT id FROM sys_menu WHERE path = '/system/dict');

-- 按钮权限节点（add/edit/delete），幂等按 permission 防重
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, icon, permission, sort, status, create_time, update_time, deleted)
SELECT COALESCE((SELECT MAX(id) FROM sys_menu), 0) + 1, @dictMenuId, '新增', 2, '', '', '', 'system:dict:add', 1, 1, NOW(), NOW(), 0
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'system:dict:add');

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, icon, permission, sort, status, create_time, update_time, deleted)
SELECT COALESCE((SELECT MAX(id) FROM sys_menu), 0) + 1, @dictMenuId, '编辑', 2, '', '', '', 'system:dict:edit', 2, 1, NOW(), NOW(), 0
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'system:dict:edit');

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, icon, permission, sort, status, create_time, update_time, deleted)
SELECT COALESCE((SELECT MAX(id) FROM sys_menu), 0) + 1, @dictMenuId, '删除', 2, '', '', '', 'system:dict:delete', 3, 1, NOW(), NOW(), 0
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'system:dict:delete');

-- 绑定给 ADMIN（role_id=1）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu
WHERE permission IN ('system:dict:list', 'system:dict:add', 'system:dict:edit', 'system:dict:delete');
