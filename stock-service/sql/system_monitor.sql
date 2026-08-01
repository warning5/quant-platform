-- ============================================================
-- 系统监控面板（R8）：菜单 + 权限（纯菜单，无独立表，指标进程内实时采集）
-- 路径 /system/monitor，挂在"系统管理"(parent_id=1) 下
-- 幂等：path 唯一 + MAX(id)+1
-- ============================================================
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, icon, permission, sort, status, create_time, update_time, deleted)
SELECT COALESCE((SELECT MAX(id) FROM sys_menu), 0) + 1, 1, '系统监控', 1, '/system/monitor', 'system/SystemMonitor', 'DashboardOutlined', 'system:monitor:list', 11, 1, NOW(), NOW(), 0
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/system/monitor');

-- 绑定给 ADMIN（role_id=1）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE permission = 'system:monitor:list';
