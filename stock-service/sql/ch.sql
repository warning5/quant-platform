-- stock.factor_health_metric 定义

CREATE TABLE stock.factor_health_metric
(

    `factor_code` String,

    `metric_date` Date,

    `ic_30d` Float64,

    `ic_60d` Float64,

    `ic_90d` Float64,

    `ir_30d` Float64,

    `ir_60d` Float64,

    `ic_at_activation` Float64,

    `decay_ratio` Float64,

    `health_status` String,

    `consecutive_decay_days` Int32,

    `consecutive_recovery_days` Int32,

    `created_at` DateTime,

    `update_time` DateTime DEFAULT now()
)
ENGINE = ReplacingMergeTree(update_time)
ORDER BY (factor_code,
 metric_date)
SETTINGS index_granularity = 8192;


-- stock.factor_premium 定义

CREATE TABLE stock.factor_premium
(

    `factor_code` String,

    `calc_date` Date,

    `factor_return` Float64,

    `stock_count` UInt32,

    `top_return` Float64,

    `bottom_return` Float64,

    `update_time` DateTime DEFAULT now()
)
ENGINE = ReplacingMergeTree(update_time)
ORDER BY (factor_code,
 calc_date)
SETTINGS index_granularity = 8192;


-- stock.factor_value 定义

CREATE TABLE stock.factor_value
(

    `id` Int64,

    `factor_code` String,

    `symbol` String,

    `calc_date` Date,

    `factor_val` Nullable(Float64),

    `rank_value` Nullable(Float64),

    `z_score` Nullable(Float64),

    `created_at` DateTime DEFAULT now(),

    `update_time` DateTime DEFAULT now(),

    `announce_date` Nullable(Date)
)
ENGINE = ReplacingMergeTree(update_time)
ORDER BY (factor_code,
 symbol,
 calc_date)
SETTINGS index_granularity = 8192;


-- stock.index_daily 定义

CREATE TABLE stock.index_daily
(

    `id` Int64 COMMENT '自增主键',

    `code` String COMMENT '指数代码（纯数字无前缀：000001=上证指数 / 000300=沪深300）',

    `trade_date` Date COMMENT '交易日期',

    `name` Nullable(String) COMMENT '指数名称（如：上证指数、沪深300、创业板指等）',

    `open_price` Nullable(Float64) COMMENT '开盘价',

    `close_price` Nullable(Float64) COMMENT '收盘价',

    `high_price` Nullable(Float64) COMMENT '最高价',

    `low_price` Nullable(Float64) COMMENT '最低价',

    `pre_close` Nullable(Float64) COMMENT '昨收价',

    `volume` Nullable(Int64) COMMENT '成交量（手）',

    `amount` Nullable(Float64) COMMENT '成交额（元）',

    `change_percent` Nullable(Float64) COMMENT '涨跌幅(%)',

    `change_amount` Nullable(Float64) COMMENT '涨跌额(元)',

    `turnover_rate` Nullable(Float64) COMMENT '换手率(%)（指数通常为NULL）',

    `pe_ttm` Nullable(Float64) COMMENT '市盈率TTM（指数通常为NULL）',

    `pb` Nullable(Float64) COMMENT '市净率（指数通常为NULL）',

    `create_time` DateTime DEFAULT now() COMMENT '创建时间',

    `update_time` DateTime DEFAULT now() COMMENT '更新时间（ReplacingMergeTree版本列，新值覆盖旧值）'
)
ENGINE = ReplacingMergeTree(update_time)
ORDER BY (code,
 trade_date)
SETTINGS index_granularity = 8192
COMMENT '指数日线数据（与 stock_daily 物理隔离，避免代码冲突如 000001=上证指数 vs 平安银行）';


-- stock.market_sentiment 定义

CREATE TABLE stock.market_sentiment
(

    `trade_date` Date,

    `indicator` String,

    `value` Float64
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(trade_date)
ORDER BY (indicator,
 trade_date)
SETTINGS index_granularity = 8192;


-- stock.stock_daily 定义

CREATE TABLE stock.stock_daily
(

    `id` Int64,

    `code` String,

    `trade_date` Date,

    `name` Nullable(String),

    `open_price` Nullable(Float64),

    `close_price` Nullable(Float64),

    `high_price` Nullable(Float64),

    `low_price` Nullable(Float64),

    `pre_close` Nullable(Float64),

    `volume` Nullable(Int64),

    `amount` Nullable(Float64),

    `change_percent` Nullable(Float64),

    `change_amount` Nullable(Float64),

    `turnover_rate` Nullable(Float64),

    `pe_ttm` Nullable(Float64),

    `pb` Nullable(Float64),

    `data_source` Nullable(String),

    `create_time` DateTime,

    `update_time` DateTime
)
ENGINE = ReplacingMergeTree(update_time)
ORDER BY (code,
 trade_date)
SETTINGS index_granularity = 8192;


-- stock.stock_info 定义

CREATE TABLE stock.stock_info
(

    `id` UInt64,

    `code` String,

    `name` String,

    `market` String,

    `industry` String,

    `list_date` Date,

    `is_hs` UInt8,

    `is_st` UInt8,

    `total_share` Decimal(20,
 2),

    `float_share` Decimal(20,
 2),

    `total_market_cap` Decimal(20,
 2),

    `float_market_cap` Decimal(20,
 2),

    `pe_ttm` Decimal(10,
 2),

    `pb` Decimal(10,
 2),

    `create_time` DateTime,

    `update_time` DateTime,

    `symbol` String
)
ENGINE = ReplacingMergeTree
ORDER BY code
SETTINGS index_granularity = 8192;


-- stock.stock_sentiment_activity 定义

CREATE TABLE stock.stock_sentiment_activity
(

    `trade_date` Date COMMENT '交易日期',

    `up_count` Int32 COMMENT '上涨家数',

    `zt_count` Int32 COMMENT '涨停家数',

    `zt_real_count` Int32 COMMENT '真实涨停家数',

    `zt_st_count` Int32 COMMENT 'ST涨停家数',

    `down_count` Int32 COMMENT '下跌家数',

    `dt_count` Int32 COMMENT '跌停家数',

    `dt_real_count` Int32 COMMENT '真实跌停家数',

    `dt_st_count` Int32 COMMENT 'ST跌停家数',

    `flat_count` Int32 COMMENT '平盘家数',

    `suspended_count` Int32 COMMENT '停牌家数',

    `activity_ratio` Decimal(10,
 4) COMMENT '活跃度(%)',

    `update_time` DateTime DEFAULT now() COMMENT '更新时间'
)
ENGINE = ReplacingMergeTree(update_time)
ORDER BY trade_date
SETTINGS index_granularity = 8192;


-- stock.stock_sentiment_block_trade 定义

CREATE TABLE stock.stock_sentiment_block_trade
(

    `seq_no` UInt32 COMMENT '�����',

    `trade_date` Date COMMENT '��������',

    `code` String COMMENT '֤ȯ����',

    `name` String COMMENT '֤ȯ���',

    `price` Decimal(10,
 2) COMMENT '�ɽ���(Ԫ)',

    `volume` Decimal(20,
 2) COMMENT '�ɽ���(��)',

    `amount` Decimal(20,
 2) COMMENT '�ɽ���(Ԫ)',

    `discount_rate` Nullable(Decimal(10,
 6)) DEFAULT NULL COMMENT '������',

    `change_pct` Nullable(Decimal(10,
 4)) DEFAULT NULL COMMENT '�ǵ���%',

    `close_price` Nullable(Decimal(10,
 4)) DEFAULT NULL COMMENT '���̼�',

    `pct_of_float` Nullable(Decimal(10,
 6)) DEFAULT NULL COMMENT '�ɽ���/��ͨ��ֵ',

    `buy_branch` String COMMENT '��Ӫҵ��',

    `sell_branch` String COMMENT '����Ӫҵ��',

    `update_time` DateTime DEFAULT now() COMMENT '����ʱ��'
)
ENGINE = ReplacingMergeTree(update_time)
ORDER BY (code,
 trade_date,
 seq_no)
SETTINGS index_granularity = 8192;


-- stock.stock_sentiment_lhb 定义

CREATE TABLE stock.stock_sentiment_lhb
(

    `code` String COMMENT '股票代码',

    `name` String COMMENT '股票名称',

    `trade_date` Date COMMENT '上榜日',

    `close` Decimal(10,
 2) COMMENT '收盘价(元)',

    `pct_change` Decimal(10,
 2) COMMENT '涨跌幅(%)',

    `net_amount` Decimal(20,
 2) COMMENT '龙虎榜净买额(元)',

    `buy_amount` Decimal(20,
 2) COMMENT '龙虎榜买入额(元)',

    `sell_amount` Decimal(20,
 2) COMMENT '龙虎榜卖出额(元)',

    `total_amount` Decimal(20,
 2) COMMENT '龙虎榜成交额(元)',

    `market_amount` Decimal(20,
 2) COMMENT '市场总成交额(元)',

    `net_ratio` Decimal(10,
 4) COMMENT '净买额占总成交比',

    `amount_ratio` Decimal(10,
 4) COMMENT '成交额占总成交比',

    `turnover` Decimal(10,
 4) COMMENT '换手率(%)',

    `float_mv` Decimal(20,
 2) COMMENT '流通市值(元)',

    `reason` String COMMENT '上榜原因',

    `after_1d` Nullable(Decimal(10,
 2)) COMMENT '上榜后1日涨跌幅(%)',

    `after_2d` Nullable(Decimal(10,
 2)) COMMENT '上榜后2日涨跌幅(%)',

    `after_5d` Nullable(Decimal(10,
 2)) COMMENT '上榜后5日涨跌幅(%)',

    `after_10d` Nullable(Decimal(10,
 2)) COMMENT '上榜后10日涨跌幅(%)',

    `update_time` DateTime DEFAULT now() COMMENT '更新时间'
)
ENGINE = ReplacingMergeTree(update_time)
ORDER BY (code,
 trade_date)
SETTINGS index_granularity = 8192;


-- stock.stock_sentiment_lhb_inst 定义

CREATE TABLE stock.stock_sentiment_lhb_inst
(

    `code` String COMMENT '股票代码',

    `name` String COMMENT '股票名称',

    `trade_date` Date COMMENT '上榜日期',

    `close` Decimal(10,
 2) COMMENT '收盘价(元)',

    `pct_change` Decimal(10,
 2) COMMENT '涨跌幅(%)',

    `buy_inst_cnt` Int32 COMMENT '买方机构数',

    `sell_inst_cnt` Int32 COMMENT '卖方机构数',

    `buy_inst_amt` Decimal(20,
 2) COMMENT '机构买入总额(元)',

    `sell_inst_amt` Decimal(20,
 2) COMMENT '机构卖出总额(元)',

    `net_inst_amt` Decimal(20,
 2) COMMENT '机构净买额(元)',

    `market_amount` Decimal(20,
 2) COMMENT '市场总成交额(元)',

    `net_inst_ratio` Decimal(10,
 4) COMMENT '机构净买额占总成交额比',

    `turnover` Decimal(10,
 4) COMMENT '换手率(%)',

    `float_mv` Decimal(20,
 2) COMMENT '流通市值(元)',

    `reason` String COMMENT '上榜原因',

    `update_time` DateTime DEFAULT now() COMMENT '更新时间'
)
ENGINE = ReplacingMergeTree(update_time)
ORDER BY (code,
 trade_date)
SETTINGS index_granularity = 8192;


-- stock.stock_sentiment_margin 定义

CREATE TABLE stock.stock_sentiment_margin
(

    `trade_date` Date COMMENT '信用交易日期',

    `margin_balance` Decimal(20,
 2) COMMENT '融资余额(元)',

    `margin_buy` Decimal(20,
 2) COMMENT '融资买入额(元)',

    `short_balance_vol` Decimal(20,
 2) COMMENT '融券余量(股)',

    `short_balance_amt` Decimal(20,
 2) COMMENT '融券余额(元)',

    `short_sell_vol` Decimal(20,
 2) COMMENT '融券卖出量(股)',

    `margin_short_bal` Decimal(20,
 2) COMMENT '融资融券余额(元)',

    `update_time` DateTime DEFAULT now() COMMENT '更新时间'
)
ENGINE = ReplacingMergeTree(update_time)
ORDER BY trade_date
SETTINGS index_granularity = 8192;


-- stock.stock_sentiment_margin_detail 定义

CREATE TABLE stock.stock_sentiment_margin_detail
(

    `trade_date` Date COMMENT '信用交易日期',

    `code` String COMMENT '标的证券代码',

    `name` String COMMENT '标的证券简称',

    `margin_balance` Decimal(20,
 2) COMMENT '融资余额(元)',

    `margin_buy` Decimal(20,
 2) COMMENT '融资买入额(元)',

    `margin_repay` Decimal(20,
 2) COMMENT '融资偿还额(元)',

    `short_balance_vol` Decimal(20,
 2) COMMENT '融券余量(股)',

    `short_sell_vol` Decimal(20,
 2) COMMENT '融券卖出量(股)',

    `short_repay_vol` Decimal(20,
 2) COMMENT '融券偿还量(股)',

    `update_time` DateTime DEFAULT now() COMMENT '更新时间'
)
ENGINE = ReplacingMergeTree(update_time)
ORDER BY (code,
 trade_date)
SETTINGS index_granularity = 8192;


-- stock.stock_sentiment_moneyflow 定义

CREATE TABLE stock.stock_sentiment_moneyflow
(

    `ts_code` String,

    `trade_date` Date,

    `code` String,

    `close` Decimal(10,
 2),

    `pct_change` Decimal(10,
 2),

    `net_main` Decimal(20,
 2),

    `net_main_pct` Decimal(10,
 4),

    `net_huge` Decimal(20,
 2),

    `net_big` Decimal(20,
 2),

    `net_medium` Decimal(20,
 2),

    `net_small` Decimal(20,
 2),

    `update_time` DateTime DEFAULT now()
)
ENGINE = ReplacingMergeTree(update_time)
ORDER BY (code,
 trade_date)
SETTINGS index_granularity = 8192;


-- stock.stock_sentiment_notice 定义

CREATE TABLE stock.stock_sentiment_notice
(

    `id` Int64 DEFAULT 0 COMMENT '����ID',

    `ts_code` String DEFAULT '' COMMENT '股票代码(纯数字)',

    `code` String DEFAULT '' COMMENT '股票代码',

    `name` String DEFAULT '' COMMENT '股票名称',

    `notice_type` String DEFAULT '' COMMENT '公告类型',

    `notice_date` Date DEFAULT toDate('1970-01-01') COMMENT '公告日期',

    `title` String DEFAULT '' COMMENT '公告标题',

    `update_time` DateTime DEFAULT now() COMMENT '更新时间'
)
ENGINE = ReplacingMergeTree(id)
ORDER BY (code,
 notice_date,
 notice_type)
SETTINGS index_granularity = 8192;


-- stock.stock_sentiment_survey 定义

CREATE TABLE stock.stock_sentiment_survey
(

    `code` String COMMENT '股票代码',

    `name` String COMMENT '股票名称',

    `price` Decimal(10,
 2) COMMENT '最新价(元)',

    `pct_change` Decimal(10,
 2) COMMENT '涨跌幅(%)',

    `inst_count` Int32 COMMENT '接待机构数量',

    `meeting_type` String COMMENT '接待方式',

    `staff` String COMMENT '接待人员',

    `location` String COMMENT '接待地点',

    `meeting_date` Date COMMENT '接待日期',

    `notice_date` Date COMMENT '公告日期',

    `update_time` DateTime DEFAULT now() COMMENT '更新时间'
)
ENGINE = ReplacingMergeTree(update_time)
ORDER BY (code,
 meeting_date,
 notice_date)
SETTINGS index_granularity = 8192;


-- stock.stock_sentiment_zt 定义

CREATE TABLE stock.stock_sentiment_zt
(

    `ts_code` String COMMENT '股票代码(纯数字)',

    `trade_date` Date COMMENT '交易日期',

    `code` String COMMENT '股票代码(同ts_code)',

    `name` String COMMENT '股票名称',

    `close` Decimal(10,
 2) COMMENT '收盘价(元)',

    `pct_change` Decimal(10,
 2) COMMENT '涨跌幅(%)',

    `zt_type` String COMMENT '类型(zt涨停/dt跌停/zbgc炸板)',

    `reason` String COMMENT '入选理由/所属行业',

    `is_new` UInt8 COMMENT '是否新涨停(1是/0否)',

    `update_time` DateTime DEFAULT now() COMMENT '更新时间'
)
ENGINE = ReplacingMergeTree(update_time)
ORDER BY (ts_code,
 trade_date)
SETTINGS index_granularity = 8192;