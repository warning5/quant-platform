-- ============================================================
-- 参数配置中心（R7）：建表 + 种子 + 菜单/权限
-- 可重复执行（CREATE TABLE IF NOT EXISTS + INSERT IGNORE + 幂等菜单）
-- ⚠️ 所有表必须 COLLATE=utf8mb4_unicode_ci，否则与业务表 JOIN 必崩。
-- ============================================================

CREATE TABLE IF NOT EXISTS sys_config (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    config_key    VARCHAR(100) NOT NULL COMMENT '配置键(唯一)',
    config_value  VARCHAR(2000) DEFAULT '' COMMENT '配置值',
    config_group  VARCHAR(50)  DEFAULT 'DEFAULT' COMMENT '分组(便于前端分栏)',
    config_label  VARCHAR(100) DEFAULT '' COMMENT '显示标签',
    config_type   VARCHAR(20)  DEFAULT 'STRING' COMMENT 'STRING/NUMBER/BOOLEAN/JSON',
    enabled       TINYINT      NOT NULL DEFAULT 1 COMMENT '0禁用 1启用',
    sort          INT          NOT NULL DEFAULT 0 COMMENT '展示顺序',
    remark        VARCHAR(255) DEFAULT '' COMMENT '备注',
    create_time   DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted       TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_config_key (config_key),
    KEY idx_group (config_group)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统参数配置表';

-- ---------- 2. 种子（代表性参数：限流/QPS/脚本超时/全局cron开关/分页）----------
INSERT IGNORE INTO sys_config (config_key, config_value, config_group, config_label, config_type, enabled, sort, remark) VALUES
('rate_limit_qps',        '200', 'SYSTEM',  '全局限流 QPS 上限',     'NUMBER',  1, 10, '单 IP 每秒最大请求数'),
('script_timeout_minutes','30',  'SYSTEM',  '脚本执行超时(分钟)',    'NUMBER',  1, 20, 'Groovy/Python 脚本执行超时'),
('global_cron_enabled',   'true','SCHEDULE','全局定时任务总开关',    'BOOLEAN', 1, 30, 'false 时暂停所有 CRON 触发'),
('page_size_default',     '20',  'SYSTEM',  '默认分页大小',          'NUMBER',  1, 40, '列表默认每页条数');

-- ============================================================
-- 3. 菜单 + 按钮权限节点（幂等：path 唯一 + MAX(id)+1）
-- ============================================================
-- 参数配置菜单挂在"系统管理"(parent_id=1) 下
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, icon, permission, sort, status, create_time, update_time, deleted)
SELECT COALESCE((SELECT MAX(id) FROM sys_menu), 0) + 1, 1, '参数配置', 1, '/system/config', 'system/ConfigCenter', 'SettingOutlined', 'system:config:list', 10, 1, NOW(), NOW(), 0
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/system/config');

-- 拿到参数配置菜单 id，供按钮节点挂接
SET @configMenuId = (SELECT id FROM sys_menu WHERE path = '/system/config');

-- 按钮权限节点（add/edit/delete），幂等按 permission 防重
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, icon, permission, sort, status, create_time, update_time, deleted)
SELECT COALESCE((SELECT MAX(id) FROM sys_menu), 0) + 1, @configMenuId, '新增', 2, '', '', '', 'system:config:add', 1, 1, NOW(), NOW(), 0
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'system:config:add');

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, icon, permission, sort, status, create_time, update_time, deleted)
SELECT COALESCE((SELECT MAX(id) FROM sys_menu), 0) + 1, @configMenuId, '编辑', 2, '', '', '', 'system:config:edit', 2, 1, NOW(), NOW(), 0
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'system:config:edit');

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, icon, permission, sort, status, create_time, update_time, deleted)
SELECT COALESCE((SELECT MAX(id) FROM sys_menu), 0) + 1, @configMenuId, '删除', 2, '', '', '', 'system:config:delete', 3, 1, NOW(), NOW(), 0
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'system:config:delete');

-- 绑定给 ADMIN（role_id=1）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu
WHERE permission IN ('system:config:list', 'system:config:add', 'system:config:edit', 'system:config:delete');
