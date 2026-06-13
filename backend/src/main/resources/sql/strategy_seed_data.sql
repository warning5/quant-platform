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

-- 4. 新质生产力
INSERT IGNORE INTO strategy_definition (strategy_code, strategy_name, description, strategy_type, status, rebalance_frequency, max_position_count, position_size_type, stop_loss_pct, factor_config_json, filter_config_json, author)
VALUES ('NEW_PRODUCTIVITY', '新质生产力',
        '聚焦成长加速+技术突破+量价配合，捕捉处于高速成长期和科技突破期的公司',
        'FACTOR_LONG', 'ACTIVE', 'MONTHLY', 15, 'EQUAL', 0.10,
        '{"factors":[{"code":"MOM20","weight":0.25,"direction":1},{"code":"PRICE_MOM_ACC","weight":0.20,"direction":1},{"code":"MACD","weight":0.25,"direction":1},{"code":"BOLL_POS","weight":0.15,"direction":1},{"code":"VPCORR20","weight":0.15,"direction":1}]}',
        '{"excludeIndustries":["证券","银行","保险","信托","期货","房地产开发","房地产服务","钢铁","煤炭开采","电力","水务","港口","高速公路","铁路运输"]}',
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
