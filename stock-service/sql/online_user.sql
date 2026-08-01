-- ============================================================
-- 在线用户管理（R2）：菜单 + 权限
-- 路径 /system/online，挂在"系统管理"(parent_id=1) 下
-- 幂等：path 唯一 + MAX(id)+1
-- ============================================================
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, icon, permission, sort, status, create_time, update_time, deleted)
SELECT COALESCE((SELECT MAX(id) FROM sys_menu), 0) + 1, 1, '在线用户', 1, '/system/online', 'system/OnlineUser', 'TeamOutlined', 'system:online:list', 12, 1, NOW(), NOW(), 0
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/system/online');

-- 按钮权限：强制下线
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, icon, permission, sort, status, create_time, update_time, deleted)
SELECT COALESCE((SELECT MAX(id) FROM sys_menu), 0) + 1, (SELECT id FROM sys_menu WHERE path = '/system/online'), '强制下线', 2, '', '', '', 'system:online:kick', 1, 1, NOW(), NOW(), 0
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'system:online:kick');

-- 绑定给 ADMIN（role_id=1）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE permission IN ('system:online:list', 'system:online:kick');
