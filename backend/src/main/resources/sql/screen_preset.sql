-- 因子选股预设组合表
CREATE TABLE IF NOT EXISTS screen_preset (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    preset_name VARCHAR(100) NOT NULL COMMENT '预设组合名称',
    description VARCHAR(500) COMMENT '组合说明',
    factor_config TEXT NOT NULL COMMENT '因子配置JSON: [{factorCode,direction,weight,filterOp,filterValue}]',
    is_builtin TINYINT(1) DEFAULT 0 COMMENT '是否内置预设',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='因子选股预设组合';
