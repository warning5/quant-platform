-- ============================================================
-- 策略定义初始化数据
-- 使用 INSERT IGNORE 确保幂等（已有则跳过）
-- 策略管理完全依赖数据库，无 Java 代码初始化
-- ============================================================

-- 确保 strategy_code 有唯一索引
ALTER TABLE strategy_definition ADD UNIQUE INDEX uk_strategy_code (strategy_code);

-- 1. 全因子综合
INSERT IGNORE INTO strategy_definition (strategy_code, strategy_name, description, strategy_type, status, rebalance_frequency, max_position_count, position_size_type, stop_loss_pct, factor_config_json, author)
VALUES ('ALL_FACTOR_COMPOSITE', '全因子综合',
        '覆盖动量、价值、波动率、技术、量价五大类因子，均衡配置追求稳健超额收益',
        'FACTOR_LONG', 'ACTIVE', 'MONTHLY', 20, 'EQUAL', 0.08,
        '{"factors":[{"code":"MOM20","weight":0.25,"direction":1},{"code":"SIZE","weight":0.20,"direction":-1},{"code":"VOL20","weight":0.20,"direction":-1},{"code":"RSI14","weight":0.15,"direction":1},{"code":"VOLUME_RATIO","weight":0.20,"direction":1}]}',
        'system');

-- 2. 现有持仓增强
INSERT IGNORE INTO strategy_definition (strategy_code, strategy_name, description, strategy_type, status, rebalance_frequency, max_position_count, position_size_type, stop_loss_pct, factor_config_json, author)
VALUES ('POSITION_ENHANCE', '现有持仓增强',
        '基于中期趋势确认+低估值保护+低波动控制，帮助已有持仓优化风险收益比',
        'FACTOR_LONG', 'ACTIVE', 'MONTHLY', 15, 'EQUAL', 0.08,
        '{"factors":[{"code":"MOM60","weight":0.30,"direction":1},{"code":"VAL_PB","weight":0.25,"direction":-1},{"code":"VOL20","weight":0.25,"direction":-1},{"code":"MACD","weight":0.20,"direction":1}]}',
        'system');

-- 3. 均衡配置
INSERT IGNORE INTO strategy_definition (strategy_code, strategy_name, description, strategy_type, status, rebalance_frequency, max_position_count, position_size_type, stop_loss_pct, factor_config_json, author)
VALUES ('BALANCED_CONFIG', '均衡配置',
        '动量、估值、波动率、技术、量价五类因子等权配置，降低单一因子失效风险',
        'FACTOR_LONG', 'ACTIVE', 'MONTHLY', 20, 'EQUAL', 0.08,
        '{"factors":[{"code":"MOM20","weight":0.20,"direction":1},{"code":"VAL_PE_TTM","weight":0.20,"direction":-1},{"code":"VOL20","weight":0.20,"direction":-1},{"code":"MACD","weight":0.20,"direction":1},{"code":"VPCORR20","weight":0.20,"direction":1}]}',
        'system');

-- 4. 新质生产力（因子必须使用factor_definition表中实际存在的ACTIVE因子，且能通过IC>=0.03预筛选和0.70拥挤度过滤）
-- 当前配置：3因子跨3维度（趋势/研发强度/利润增长），日频+季频天然不拥挤，FIN_RD_REVENUE_RATIO是新质生产力核心因子
-- MACD(0.35,dir=1,TECHNICAL,decayIC=0.1003) FIN_RD_REVENUE_RATIO(0.35,dir=1,FINANCIAL,decayIC=0.3100) FIN_NET_PROFIT_YOY(0.30,dir=1,FINANCIAL,decayIC=0.0915)
INSERT IGNORE INTO strategy_definition (strategy_code, strategy_name, description, strategy_type, status, rebalance_frequency, max_position_count, position_size_type, stop_loss_pct, factor_config_json, filter_config_json, author)
VALUES ('NEW_PRODUCTIVITY', '新质生产力',
        '聚焦趋势突破+研发投入强度+净利润增速，研发费用率(|IC|=0.31)是创新驱动增长的最强先行指标，三大维度互补捕捉技术驱动成长公司',
        'FACTOR_LONG', 'ACTIVE', 'MONTHLY', 15, 'EQUAL', 0.10,
        '{"factors":[{"code":"MACD","weight":0.35,"direction":1},{"code":"FIN_RD_REVENUE_RATIO","weight":0.35,"direction":1},{"code":"FIN_NET_PROFIT_YOY","weight":0.30,"direction":1}]}',
        '{"includeIndustries":["半导体","元件","光学光电子","消费电子","其他电子","电子化学品","光伏设备","电池","风电设备","电网设备","其他电源设备","计算机设备","软件开发","IT服务","通信服务","通信设备","军工电子","军工装备","自动化设备","专用设备","通用设备","医疗器械","生物制品","化学制药","汽车零部件","能源金属","金属新材料","电机"],"excludeIndustries":["证券","银行","保险","信托","期货","房地产开发","房地产服务","钢铁","煤炭开采","电力","水务","港口","高速公路","铁路运输"]}',
        'system');

-- 5. 热点追踪
INSERT IGNORE INTO strategy_definition (strategy_code, strategy_name, description, strategy_type, status, rebalance_frequency, max_position_count, position_size_type, stop_loss_pct, factor_config_json, author)
VALUES ('HOT_TRACKING', '热点追踪',
        '基于短期动量+情绪热度+量价异动，捕捉资金涌入的市场热点和强势个股',
        'MOMENTUM', 'ACTIVE', 'WEEKLY', 12, 'EQUAL', 0.10,
        '{"factors":[{"code":"MOM5","weight":0.25,"direction":1},{"code":"MOM20","weight":0.20,"direction":1},{"code":"LIMIT_UP_COUNT","weight":0.20,"direction":1},{"code":"VOLUME_RATIO","weight":0.20,"direction":1},{"code":"VROC12","weight":0.15,"direction":1}]}',
        'system');

-- 6. 低价优质（核心策略：三层漏斗）
INSERT IGNORE INTO strategy_definition (strategy_code, strategy_name, description, strategy_type, status, rebalance_frequency, max_position_count, position_size_type, stop_loss_pct, factor_config_json, author)
VALUES ('VALUE_QUALITY', '低价优质',
        '三层漏斗选股：估值便宜(PE/PB历史分位低+52周回撤深) + 质地好(盈利质量+ROE) + 现金流安全(FCF收益率高)，全市场筛选不排除行业',
        'FACTOR_LONG', 'ACTIVE', 'MONTHLY', 20, 'EQUAL', 0.08,
        '{"factors":[{"code":"VAL_PE_PERCENTILE","weight":0.25,"direction":-1},{"code":"VAL_PB_PERCENTILE","weight":0.20,"direction":-1},{"code":"PRICE_52W_HIGH_PCT","weight":0.15,"direction":-1},{"code":"VAL_FCF_YIELD","weight":0.20,"direction":1},{"code":"FIN_EARNINGS_QUALITY","weight":0.10,"direction":1},{"code":"FIN_NET_PROFIT_YOY","weight":0.10,"direction":1}]}',
        'system');

-- 7. 均值回归策略（自定义脚本）
INSERT IGNORE INTO strategy_definition (strategy_code, strategy_name, description, strategy_type, status, rebalance_frequency, max_position_count, position_size_type, stop_loss_pct, script_code, author)
VALUES ('CUSTOM_MEAN_REVERSION', '均值回归策略',
        '使用动量和波动率因子构建的均值回归策略，选取前期超跌的股票博取反弹收益',
        'CUSTOM', 'ACTIVE', 'WEEKLY', 15, 'EQUAL', 0.08,
        '// 均值回归策略脚本\n// 使用动量因子反向选取：前期跌幅较大的股票可能有反弹机会\ndef momMap = factorValues[''MOM20''] ?: [:]\ndef volMap = factorValues[''VOL20''] ?: [:]\ndef scores = [:]\nmarketBars.each { bar ->\n    def mom = momMap[bar.symbol]?.rankValue?.toDouble() ?: 0.5\n    def vol = volMap[bar.symbol]?.rankValue?.toDouble() ?: 0.5\n    def score = (1 - mom) * 60 + (1 - Math.abs(vol - 0.5) * 2) * 40\n    scores[bar.symbol] = score\n}\ndef top = scores.sort { -it.value }.take(maxPositions ?: 15)\ndef totalScore = top.values().sum() ?: 1.0\nreturn top.collectEntries { k, v -> [k, v / totalScore] }',
        'system');

-- ============================================================
-- 以下为 A股适配策略（2026-06-15 新增6个，排除北向资金）
-- ============================================================

-- 8. 红利低波 ⭐ 无阻塞
INSERT IGNORE INTO strategy_definition (strategy_code, strategy_name, description, strategy_type, status, rebalance_frequency, max_position_count, position_size_type, stop_loss_pct, factor_config_json, author)
VALUES ('DIVIDEND_LOW_VOL', '红利低波',
        '高股息率+低波动率组合，熊市防御性强，适合稳健型投资者',
        'FACTOR_LONG', 'ACTIVE', 'MONTHLY', 20, 'EQUAL', 0.08,
        '{"factors":[{"code":"VAL_DIVIDEND_YIELD","weight":0.35,"direction":1},{"code":"VOL20","weight":0.25,"direction":-1},{"code":"VAL_PE_PERCENTILE","weight":0.15,"direction":-1},{"code":"FIN_ROE","weight":0.15,"direction":1},{"code":"VAL_PB_PERCENTILE","weight":0.10,"direction":-1}]}',
        'system');

-- 9. 涨停板 ⭐ 无阻塞
INSERT IGNORE INTO strategy_definition (strategy_code, strategy_name, description, strategy_type, status, rebalance_frequency, max_position_count, position_size_type, stop_loss_pct, factor_config_json, author)
VALUES ('LIMIT_UP_MOMENTUM', '涨停板',
        '捕捉涨停板股票短期动量，基于涨停次数+短期动量+量比，高风险高收益',
        'MOMENTUM', 'ACTIVE', 'WEEKLY', 10, 'EQUAL', 0.10,
        '{"factors":[{"code":"LIMIT_UP_COUNT","weight":0.40,"direction":1},{"code":"MOM5","weight":0.30,"direction":1},{"code":"VOLUME_RATIO","weight":0.30,"direction":1}]}',
        'system');

-- 10. 板块轮动（降级版）⚠️ 可绕过，用行业动量替代资金流
INSERT IGNORE INTO strategy_definition (strategy_code, strategy_name, description, strategy_type, status, rebalance_frequency, max_position_count, position_size_type, stop_loss_pct, factor_config_json, filter_config_json, author)
VALUES ('SECTOR_ROTATION', '板块轮动',
        '基于行业动量轮动，选择近期表现最好的行业中的低估值优质股票。降级版：用个股动量代替行业资金流',
        'FACTOR_LONG', 'ACTIVE', 'MONTHLY', 20, 'EQUAL', 0.08,
        '{"factors":[{"code":"MOM20","weight":0.30,"direction":1},{"code":"VAL_PE_PERCENTILE","weight":0.25,"direction":-1},{"code":"VOL20","weight":0.20,"direction":-1},{"code":"FIN_ROE","weight":0.25,"direction":1}]}',
        '{"excludeIndustries":["证券","银行","保险","信托","房地产开发","钢铁","煤炭开采","电力"]}',
        'system');

-- 11. 市场情绪（降级版）⚠️ 可绕过，用技术指标准替代VIX
INSERT IGNORE INTO strategy_definition (strategy_code, strategy_name, description, strategy_type, status, rebalance_frequency, max_position_count, position_size_type, stop_loss_pct, factor_config_json, author)
VALUES ('MARKET_SENTIMENT', '市场情绪',
        '基于换手率异常+成交量惊喜+涨停次数捕捉市场情绪，适合短线操作。降级版：用技术指标准替代VIX',
        'MOMENTUM', 'ACTIVE', 'WEEKLY', 15, 'EQUAL', 0.10,
        '{"factors":[{"code":"TURNOVER_ANOMALY","weight":0.25,"direction":1},{"code":"VOLUME_SURPRISE","weight":0.25,"direction":1},{"code":"LIMIT_UP_COUNT","weight":0.25,"direction":1},{"code":"MOM5","weight":0.25,"direction":1}]}',
        'system');

-- 12. 估值修复（LLM版基础版）⚠️ 部分阻塞，LLM解析增减持/回购后可完全解决
INSERT IGNORE INTO strategy_definition (strategy_code, strategy_name, description, strategy_type, status, rebalance_frequency, max_position_count, position_size_type, stop_loss_pct, factor_config_json, author)
VALUES ('VALUATION_RECOVERY_LLM', '估值修复',
        '低估值+高质量+现金流安全，后续加入LLM新闻解析（增持/回购事件）',
        'FACTOR_LONG', 'ACTIVE', 'MONTHLY', 20, 'EQUAL', 0.08,
        '{"factors":[{"code":"VAL_PE_PERCENTILE","weight":0.30,"direction":-1},{"code":"VAL_PB_PERCENTILE","weight":0.20,"direction":-1},{"code":"PRICE_52W_HIGH_PCT","weight":0.20,"direction":-1},{"code":"FIN_EARNINGS_QUALITY","weight":0.15,"direction":1},{"code":"VAL_FCF_YIELD","weight":0.15,"direction":1}]}',
        'system');

-- 13. 事件驱动（降级版）❌ 核心阻塞，无一致预期数据
INSERT IGNORE INTO strategy_definition (strategy_code, strategy_name, description, strategy_type, status, rebalance_frequency, max_position_count, position_size_type, stop_loss_pct, factor_config_json, author)
VALUES ('EVENT_DRIVEN_DOWNGRADED', '事件驱动（降级版）',
        '基于业绩增长+盈利质量，降级版无一致预期数据，无法判断超预期',
        'FACTOR_LONG', 'ACTIVE', 'MONTHLY', 15, 'EQUAL', 0.08,
        '{"factors":[{"code":"FIN_NET_PROFIT_YOY","weight":0.30,"direction":1},{"code":"FIN_REVENUE_YOY","weight":0.30,"direction":1},{"code":"FIN_EARNINGS_QUALITY","weight":0.20,"direction":1},{"code":"FIN_ROE","weight":0.20,"direction":1}]}',
        'system');

-- ============================================================
-- 14~16: 形态驱动策略 (PATTERN)
-- 基于《稳中求胜》5大起涨形态，使用MACD/BOLL/KDJ/均线/量价标准技术指标
-- ============================================================

-- 14. 底部反转
INSERT IGNORE INTO strategy_definition (strategy_code, strategy_name, description, strategy_type, status, rebalance_frequency, max_position_count, position_size_type, stop_loss_pct, factor_config_json, filter_config_json, author)
VALUES ('PATTERN_BOTTOM_REVERSAL', '底部反转形态',
        '双底/W底结构 + MACD底背离 + 放量突破颈线 + KDJ低位金叉，捕捉底部反转机会',
        'PATTERN', 'ACTIVE', 'WEEKLY', 10, 'EQUAL', 0.08,
        '{}',
        '{"patternType":"BOTTOM_REVERSAL"}',
        'system');

-- 15. 主升浪
INSERT IGNORE INTO strategy_definition (strategy_code, strategy_name, description, strategy_type, status, rebalance_frequency, max_position_count, position_size_type, stop_loss_pct, factor_config_json, filter_config_json, author)
VALUES ('PATTERN_MAIN_TREND', '主升浪形态',
        '突破平台整理区 + 均线多头排列(MA5>MA10>MA20>MA60) + 放量配合 + MACD多头信号',
        'PATTERN', 'ACTIVE', 'WEEKLY', 10, 'EQUAL', 0.08,
        '{}',
        '{"patternType":"MAIN_TREND"}',
        'system');

-- 16. 变盘突破
INSERT IGNORE INTO strategy_definition (strategy_code, strategy_name, description, strategy_type, status, rebalance_frequency, max_position_count, position_size_type, stop_loss_pct, factor_config_json, filter_config_json, author)
VALUES ('PATTERN_BREAKOUT', '变盘突破形态',
        '布林带收窄后向上突破 + 放量配合 + MA5金叉MA10，捕捉变盘节点',
        'PATTERN', 'ACTIVE', 'WEEKLY', 10, 'EQUAL', 0.08,
        '{}',
        '{"patternType":"BREAKOUT"}',
        'system');
