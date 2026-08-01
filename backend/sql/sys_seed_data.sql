-- ============================================================
-- 系统权限种子数据（管理员 / 角色 / 菜单树 / 角色菜单绑定）
-- 依赖：backend/sql/sys_auth.sql 已先执行建表
-- 执行：在 stock 库手动执行本文件（全新库或已初始化库均可）
-- 特性：固定 id + INSERT IGNORE，可重复执行，不会覆盖已有数据
--       （已初始化过的库，所有记录因 id 已存在会被 IGNORE 跳过，安全）
-- 维护：菜单/权限变更请同步修改本文件，而不是改应用代码
--       应用层 DataInitializer 已停用，不再做兜底初始化
-- ============================================================

-- ---------- 1. 角色 ADMIN ----------
INSERT IGNORE INTO sys_role (id, role_code, role_name, remark, status, create_time, update_time, deleted)
VALUES (1, 'ADMIN', '超级管理员', '系统初始化自动创建，拥有全部权限', 1, NOW(), NOW(), 0);

-- ---------- 2. 管理员账号 admin ----------
-- 默认密码 admin123，哈希由 Spring Security BCryptPasswordEncoder(strength=10) 生成
-- 【全新库部署前】请将下方 '__ADMIN_BCRYPT_HASH__' 替换为真实哈希，生成命令：
--   python -c "import bcrypt;print(bcrypt.hashpw(b'admin123', bcrypt.gensalt(10)).decode())"
-- （已初始化过的库因为 id=1 已存在会被 INSERT IGNORE 跳过，无需替换；
--   若需改密，用后端“重置密码”功能或手动 UPDATE sys_user SET password='<hash>' WHERE username='admin'）
INSERT IGNORE INTO sys_user (id, username, password, nickname, status, create_time, update_time, deleted)
VALUES (1, 'admin', '__ADMIN_BCRYPT_HASH__', '管理员', 1, NOW(), NOW(), 0);

-- ---------- 3. 用户-角色绑定 ----------
INSERT IGNORE INTO sys_user_role (user_id, role_id) VALUES (1, 1);

-- ---------- 4. 菜单树（menu_type: 0=目录 1=菜单 2=按钮） ----------
-- 字段顺序：id, parent_id, menu_name, menu_type, path, component, icon, permission, sort, status, create_time, update_time, deleted
INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, icon, permission, sort, status, create_time, update_time, deleted)
VALUES
-- 系统管理（目录）及其子项
(1, 0,  '系统管理',      0, '', '', 'SettingOutlined',       '',                 900, 1, NOW(), NOW(), 0),
(2, 1,  '用户管理',      1, '/system/users',   'System/UserManage',   'UserOutlined',   'system:user:list',   1, 1, NOW(), NOW(), 0),
(3, 2,  '新增用户',      2, '', '', '', 'system:user:add',    1, 1, NOW(), NOW(), 0),
(4, 2,  '编辑用户',      2, '', '', '', 'system:user:edit',   2, 1, NOW(), NOW(), 0),
(5, 2,  '删除用户',      2, '', '', '', 'system:user:delete', 3, 1, NOW(), NOW(), 0),
(6, 2,  '重置密码',      2, '', '', '', 'system:user:reset',  4, 1, NOW(), NOW(), 0),
(7, 2,  '分配角色',      2, '', '', '', 'system:user:assign', 5, 1, NOW(), NOW(), 0),
(8, 1,  '角色管理',      1, '/system/roles',   'System/RoleManage',   'TeamOutlined',   'system:role:list',   2, 1, NOW(), NOW(), 0),
(9, 8,  '新增角色',      2, '', '', '', 'system:role:add',    1, 1, NOW(), NOW(), 0),
(10, 8, '编辑角色',      2, '', '', '', 'system:role:edit',   2, 1, NOW(), NOW(), 0),
(11, 8, '删除角色',      2, '', '', '', 'system:role:delete', 3, 1, NOW(), NOW(), 0),
(12, 8, '分配菜单',      2, '', '', '', 'system:role:assign', 4, 1, NOW(), NOW(), 0),
(13, 1, '菜单管理',      1, '/system/menus',   'System/MenuManage',   'SafetyOutlined', 'system:menu:list',   3, 1, NOW(), NOW(), 0),
(14, 13, '新增菜单',     2, '', '', '', 'system:menu:add',    1, 1, NOW(), NOW(), 0),
(15, 13, '编辑菜单',     2, '', '', '', 'system:menu:edit',   2, 1, NOW(), NOW(), 0),
(16, 13, '删除菜单',     2, '', '', '', 'system:menu:delete', 3, 1, NOW(), NOW(), 0),
-- 业务模块
(17, 0, '总览',          1, '/',                'Dashboard',                 'DashboardOutlined', '',            10, 1, NOW(), NOW(), 0),
(18, 0, '行情数据',      1, '/market',          'Market/MarketList',        'FundViewOutlined',  'market:view',   20, 1, NOW(), NOW(), 0),
(19, 0, '大盘温度计',    1, '/market-thermometer', 'Analysis/MarketThermometer', 'ControlOutlined', 'market:view', 30, 1, NOW(), NOW(), 0),
(20, 0, '个股分析',      1, '/stock-analysis',  'Analysis/StockAnalysis',   'SearchOutlined',    'stock:view',    40, 1, NOW(), NOW(), 0),
(21, 0, '因子管理',      0, '', '', 'FundOutlined',        'factor:view',   50, 1, NOW(), NOW(), 0),
(22, 21, '因子列表',     1, '/factors',         'Factors/FactorList',       '',                 'factor:view',   1, 1, NOW(), NOW(), 0),
(23, 21, '因子计算',     1, '/factor-monitor',  'Factors/FactorMonitor',    '',                 'factor:view',   2, 1, NOW(), NOW(), 0),
(24, 21, '因子相关性',   1, '/factor-correlation', 'Factors/FactorCorrelation', '',              'factor:view',   3, 1, NOW(), NOW(), 0),
(25, 21, '权重优化',     1, '/factor-weight-optimize', 'Factors/FactorWeightOptimize', '',      'factor:view',   4, 1, NOW(), NOW(), 0),
(26, 21, 'IC管理',       1, '/factor-ic-ir',    'Factors/FactorIcIrAnalysis', '',               'factor:view',   5, 1, NOW(), NOW(), 0),
(27, 0, '策略管理',      0, '', '', 'ThunderboltOutlined',  'strategy:view', 60, 1, NOW(), NOW(), 0),
(28, 27, '策略列表',     1, '/strategies',      'Strategies/StrategyList',   '',                'strategy:view', 1, 1, NOW(), NOW(), 0),
(29, 27, '回测列表',     1, '/backtests',       'Backtest/BacktestList',     '',                'strategy:view', 2, 1, NOW(), NOW(), 0),
(30, 27, '策略对比',     1, '/backtests/compare', 'Backtest/BacktestCompare', '',               'strategy:view', 3, 1, NOW(), NOW(), 0),
(31, 27, '参数优化',     1, '/backtests/param-optimize', 'Backtest/ParamOptimize', '',          'strategy:view', 4, 1, NOW(), NOW(), 0),
(32, 27, 'Walk-Forward验证', 1, '/backtests/walk-forward', 'Backtest/WalkForward', '',          'strategy:view', 5, 1, NOW(), NOW(), 0),
(33, 27, '模拟盘',       1, '/paper-trading',   'Strategies/PaperTradingPage', '',              'strategy:view', 6, 1, NOW(), NOW(), 0),
(34, 0, '选股工具',      0, '', '', 'AppstoreOutlined',     '',               70, 1, NOW(), NOW(), 0),
(35, 34, '因子选股',     1, '/screen',          'Screen/StockScreen',        '',                'screen:view',   1, 1, NOW(), NOW(), 0),
(36, 34, '智能推荐',     1, '/recommendation',  'recommendation/RecommendationList', '',        'recommendation:view', 2, 1, NOW(), NOW(), 0),
(37, 34, 'AI推理分析',   1, '/llm',             'llm/LlmAnalysisPage',      '',                'llm:view',      3, 1, NOW(), NOW(), 0),
(38, 34, '盘中监控',     1, '/monitor',         'monitor/MonitorPage',      '',                'monitor:view',  4, 1, NOW(), NOW(), 0),
(39, 34, '交易日历',     1, '/calendar',        'calendar/TradeCalendar',   '',                'calendar:view', 5, 1, NOW(), NOW(), 0),
(40, 0, '数据信息',      0, '', '', 'AccountBookOutlined',   'data:view',     80, 1, NOW(), NOW(), 0),
(41, 40, '数据更新',     1, '/data-update',     'dataupdate/DataUpdate',    '',                'data:view',     1, 1, NOW(), NOW(), 0),
(42, 40, '财务数据',     1, '/data-detail/financial', 'financial/FinancialData', '',            'financial:view',2, 1, NOW(), NOW(), 0),
(43, 40, '研报数据',     1, '/data-detail/research', 'datadetail/ResearchData', '',             'research:view', 3, 1, NOW(), NOW(), 0),
(44, 40, '行业排行',     1, '/sector-ranking',  'market/SectorRanking',     '',                'data:view',     4, 1, NOW(), NOW(), 0),
(45, 40, '定时任务',     1, '/scheduled-tasks', 'dataupdate/ScheduledTasks', '',               'data:view',     5, 1, NOW(), NOW(), 0),
(46, 40, '质量监控',     1, '/data-quality',    'dataupdate/DataQualityDashboard', '',         'data:view',     6, 1, NOW(), NOW(), 0),
(47, 0, '使用手册 v3.0', 1, '/manual/full',     'Manual/ManualFullPage',    'BookOutlined',    '',              90, 1, NOW(), NOW(), 0);

-- ---------- 5. ADMIN 绑定全部菜单（缺失的补齐，已有的跳过） ----------
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
(1,1),(1,2),(1,3),(1,4),(1,5),(1,6),(1,7),(1,8),(1,9),(1,10),(1,11),(1,12),(1,13),(1,14),(1,15),(1,16),
(1,17),(1,18),(1,19),(1,20),(1,21),(1,22),(1,23),(1,24),(1,25),(1,26),(1,27),(1,28),(1,29),(1,30),(1,31),(1,32),(1,33),
(1,34),(1,35),(1,36),(1,37),(1,38),(1,39),(1,40),(1,41),(1,42),(1,43),(1,44),(1,45),(1,46),(1,47);

-- ---------- 4.1 确保"任务监控"菜单存在（按 path 幂等；id 用 MAX(id)+1 自动避开库内任何已占用 id） ----------
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, icon, permission, sort, status, create_time, update_time, deleted)
SELECT COALESCE((SELECT MAX(id) FROM sys_menu), 0) + 1, 40, '任务监控', 1, '/task-monitor', 'dataupdate/TaskRunHistory', 'BellOutlined', 'data:view', 7, 1, NOW(), NOW(), 0
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/task-monitor');
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE path = '/task-monitor';

-- ---------- 6. 抬高自增计数器（避免后续 AUTO 插入与固定种子 id 冲突） ----------
-- 若表中已有更大 id，MySQL 会自动忽略此处设置（保持 max+1），因此安全。
ALTER TABLE sys_role      AUTO_INCREMENT = 100;
ALTER TABLE sys_user      AUTO_INCREMENT = 100;
ALTER TABLE sys_menu      AUTO_INCREMENT = 1000;
ALTER TABLE sys_user_role AUTO_INCREMENT = 100;
ALTER TABLE sys_role_menu AUTO_INCREMENT = 1000;

-- ---------- 7. 按钮级权限节点（2026-07-31 补充，配合后端方法级 @SaCheckPermission） ----------
-- 前端写按钮按 module:edit / module:delete 显隐；后端写方法要求对应权限（AND view）。
-- 固定 id 60-73（避开 1-47 与 AUTO_INCREMENT 1000）；INSERT IGNORE 可重复执行。
INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, icon, permission, sort, status, create_time, update_time, deleted)
VALUES
(60, 21, '因子-新增/计算/编辑',    2, '', '', '', 'factor:edit',        1, 1, NOW(), NOW(), 0),
(61, 21, '因子-删除',              2, '', '', '', 'factor:delete',      2, 1, NOW(), NOW(), 0),
(62, 38, '盘中监控-操作',          2, '', '', '', 'monitor:edit',        1, 1, NOW(), NOW(), 0),
(63, 36, '推荐-生成/计算/跟踪',    2, '', '', '', 'recommendation:edit', 1, 1, NOW(), NOW(), 0),
(64, 36, '推荐-黑名单删除',        2, '', '', '', 'recommendation:delete',2, 1, NOW(), NOW(), 0),
(65, 35, '选股-执行',              2, '', '', '', 'screen:edit',         1, 1, NOW(), NOW(), 0),
(66, 37, 'AI推理-分析',            2, '', '', '', 'llm:edit',            1, 1, NOW(), NOW(), 0),
(67, 27, '策略-新增/回测/模拟盘',  2, '', '', '', 'strategy:edit',       1, 1, NOW(), NOW(), 0),
(68, 27, '策略-删除',              2, '', '', '', 'strategy:delete',     2, 1, NOW(), NOW(), 0),
(69, 39, '日历-标记',              2, '', '', '', 'calendar:edit',       1, 1, NOW(), NOW(), 0),
(70, 40, '数据-更新/任务',         2, '', '', '', 'data:edit',           1, 1, NOW(), NOW(), 0),
(71, 40, '数据-删除',              2, '', '', '', 'data:delete',         2, 1, NOW(), NOW(), 0),
(72, 43, '研报-批量删除',          2, '', '', '', 'research:delete',      1, 1, NOW(), NOW(), 0),
(73, 20, '个股分析-解析',          2, '', '', '', 'stock:edit',          1, 1, NOW(), NOW(), 0);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
(1,60),(1,61),(1,62),(1,63),(1,64),(1,65),(1,66),(1,67),(1,68),(1,69),(1,70),(1,71),(1,72),(1,73);
