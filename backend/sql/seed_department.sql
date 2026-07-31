-- ============================================================
-- 部门管理：示例部门数据 + 菜单(权限) + ADMIN 绑定
-- 库：stock；执行：在 stock 库手动执行本文件
-- 特性：固定 id + INSERT IGNORE（可重复执行，已存在则跳过，安全）
--   菜单/权限变更请以本文件为准（应用不自动 DDL、无兜底初始化）
-- ============================================================

-- ---------- 1. 示例部门（id=1 默认部门已存在，这里挂二级/三级） ----------
-- sys_department 字段：id, parent_id, dept_name, dept_path, dept_level, sort, status, create_time
INSERT IGNORE INTO sys_department (id, parent_id, dept_name, dept_path, dept_level, sort, status, create_time)
VALUES
(2, 1, '量化研究部', '/1/2',   2, 1, 1, NOW()),
(3, 1, '技术部',     '/1/3',   2, 2, 1, NOW()),
(4, 1, '投资交易部', '/1/4',   2, 3, 1, NOW()),
(5, 2, '因子组',     '/1/2/5', 3, 1, 1, NOW());

-- ---------- 2. 部门管理菜单 + 按钮权限节点 ----------
-- sys_menu 字段：id, parent_id, menu_name, menu_type, path, component, icon, permission, sort, status, create_time, update_time, deleted
-- menu_type: 0=目录 1=菜单 2=按钮；按钮节点不显示于侧边栏，但供后端 @SaCheckPermission 鉴权
INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, icon, permission, sort, status, create_time, update_time, deleted)
VALUES
(79, 1,  '部门管理',     1, '/system/departments', 'System/DepartmentManage', 'ApartmentOutlined', 'system:dept:list',   5, 1, NOW(), NOW(), 0),
(80, 79, '部门-新增',    2, '', '', '', 'system:dept:add',    1, 1, NOW(), NOW(), 0),
(81, 79, '部门-编辑',    2, '', '', '', 'system:dept:edit',   2, 1, NOW(), NOW(), 0),
(82, 79, '部门-删除',    2, '', '', '', 'system:dept:delete', 3, 1, NOW(), NOW(), 0);

-- ---------- 3. ADMIN(role_id=1) 绑定部门管理菜单与按钮权限 ----------
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
(1, 79), (1, 80), (1, 81), (1, 82);

-- ---------- 4. 抬高自增计数器，避免后续 AUTO 插入与固定 id 冲突 ----------
ALTER TABLE sys_department AUTO_INCREMENT = 100;
