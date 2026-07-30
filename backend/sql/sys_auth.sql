-- ============================================================
-- 系统登录 / 用户 / 角色 / 菜单(权限) 模块建表语句
-- 库：stock（默认）；字符集 utf8mb4
-- 注意：应用不自动 DDL，请手动执行本文件。
-- 种子数据（管理员/默认角色/默认菜单/角色菜单绑定）见同目录 sys_seed_data.sql，建表后手动执行；
--       应用层 DataInitializer 已停用，不再做兜底初始化。
-- ============================================================

CREATE TABLE IF NOT EXISTS sys_user (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    username        VARCHAR(64)  NOT NULL COMMENT '登录账号',
    password        VARCHAR(100) DEFAULT NULL COMMENT 'BCrypt 密码，纯微信用户可为空',
    nickname        VARCHAR(64)  DEFAULT '' COMMENT '昵称',
    avatar          VARCHAR(255) DEFAULT '' COMMENT '头像 URL',
    email           VARCHAR(128) DEFAULT '' COMMENT '邮箱',
    phone           VARCHAR(32)  DEFAULT '' COMMENT '手机号',
    status          TINYINT      DEFAULT 1 COMMENT '1=启用 0=禁用',
    wechat_openid   VARCHAR(128) DEFAULT NULL COMMENT '微信 openid',
    wechat_unionid  VARCHAR(128) DEFAULT NULL COMMENT '微信 unionid（跨应用唯一，账号型用户为 NULL）',
    wechat_type     TINYINT      DEFAULT 0 COMMENT '0=无 1=网站应用 2=公众号 3=小程序',
    last_login_time DATETIME     DEFAULT NULL COMMENT '最近登录时间',
    create_time     DATETIME     DEFAULT NULL,
    update_time     DATETIME     DEFAULT NULL,
    deleted         TINYINT      DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username),
    UNIQUE KEY uk_unionid (wechat_unionid),
    KEY idx_openid (wechat_openid),
    KEY idx_phone (phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统用户';

CREATE TABLE IF NOT EXISTS sys_role (
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    role_code  VARCHAR(64)  NOT NULL COMMENT '角色编码（权限标识用）',
    role_name  VARCHAR(64)  NOT NULL COMMENT '角色名称',
    remark     VARCHAR(255) DEFAULT '' COMMENT '备注',
    status     TINYINT      DEFAULT 1 COMMENT '1=启用 0=禁用',
    create_time DATETIME    DEFAULT NULL,
    update_time DATETIME    DEFAULT NULL,
    deleted    TINYINT      DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_code (role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统角色';

CREATE TABLE IF NOT EXISTS sys_menu (
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    parent_id  BIGINT       DEFAULT 0 COMMENT '父菜单ID，0=顶级',
    menu_name  VARCHAR(64)  NOT NULL COMMENT '菜单名称',
    menu_type  TINYINT      NOT NULL DEFAULT 1 COMMENT '0=目录 1=菜单 2=按钮',
    path       VARCHAR(255) DEFAULT '' COMMENT '路由路径',
    component  VARCHAR(255) DEFAULT '' COMMENT '前端组件 key',
    icon       VARCHAR(64)  DEFAULT '' COMMENT '图标',
    permission VARCHAR(100) DEFAULT '' COMMENT '权限标识，如 system:user:list',
    sort       INT          DEFAULT 0 COMMENT '排序号',
    status     TINYINT      DEFAULT 1 COMMENT '1=显示 0=隐藏',
    create_time DATETIME    DEFAULT NULL,
    update_time DATETIME    DEFAULT NULL,
    deleted    TINYINT      DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_parent (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统菜单/权限';

CREATE TABLE IF NOT EXISTS sys_user_role (
    id      BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_role (user_id, role_id),
    KEY idx_role (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户-角色关联';

CREATE TABLE IF NOT EXISTS sys_role_menu (
    id      BIGINT NOT NULL AUTO_INCREMENT,
    role_id BIGINT NOT NULL,
    menu_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_menu (role_id, menu_id),
    KEY idx_menu (menu_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色-菜单关联';
