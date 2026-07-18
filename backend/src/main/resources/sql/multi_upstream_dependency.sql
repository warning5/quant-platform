-- ============================================================
-- 多上游依赖支持
-- FACTOR_COMPUTE 需要 DAILY 和 QFQ_REFRESH 都完成后才触发
-- ============================================================

-- 1. 在 data_task_dependency 表添加多上游依赖标志
-- MySQL 不支持 ADD COLUMN IF NOT EXISTS，用 information_schema 条件判断
SET @col_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'data_task_dependency'
      AND COLUMN_NAME = 'require_all_upstreams'
);
SET @sql = IF(@col_exists = 0,
    'ALTER TABLE data_task_dependency ADD COLUMN require_all_upstreams TINYINT NOT NULL DEFAULT 0 COMMENT ''是否要求所有上游都完成才触发下游（0=任一上游完成即触发，1=所有上游完成才触发）''',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. 删除旧的 FACTOR_COMPUTE 单上游依赖（来自 DAILY/BIDASK/RECOMMENDATION_TRACK）
-- 注意：先清理，避免 FACTOR_COMPUTE 被提前触发
DELETE FROM data_task_dependency
WHERE downstream_key = 'FACTOR_COMPUTE' AND upstream_key IN ('DAILY', 'BIDASK', 'RECOMMENDATION_TRACK');

-- 3. 建立新的依赖关系：
--    DAILY → QFQ_REFRESH (已有，除权后刷新)
--    QFQ_REFRESH → FACTOR_COMPUTE (延迟 30s)
--    DAILY → FACTOR_COMPUTE (require_all_upstreams=1，等待 QFQ_REFRESH 也完成)
-- 这样 FACTOR_COMPUTE 只有 DAILY 和 QFQ_REFRESH 都完成后才执行

-- DAILY → FACTOR_COMPUTE（多上游条件）
INSERT INTO data_task_dependency (upstream_key, downstream_key, delay_seconds, require_all_upstreams)
SELECT 'DAILY', 'FACTOR_COMPUTE', 30, 1
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM data_task_dependency
    WHERE upstream_key = 'DAILY' AND downstream_key = 'FACTOR_COMPUTE'
);

-- QFQ_REFRESH → FACTOR_COMPUTE（多上游条件）
INSERT INTO data_task_dependency (upstream_key, downstream_key, delay_seconds, require_all_upstreams)
SELECT 'QFQ_REFRESH', 'FACTOR_COMPUTE', 30, 1
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM data_task_dependency
    WHERE upstream_key = 'QFQ_REFRESH' AND downstream_key = 'FACTOR_COMPUTE'
);

-- 4. BIDASK 仍可独立触发 FACTOR_COMPUTE（内外盘数据不依赖前复权，但因子计算需要它）
-- 这里设为单上游触发，等 BIDASK 完成后也允许触发 FACTOR_COMPUTE；
-- 或者如果你的因子公式不需要 bidask 则删除。下面保持单上游触发，用于兜底：
INSERT INTO data_task_dependency (upstream_key, downstream_key, delay_seconds, require_all_upstreams)
SELECT 'BIDASK', 'FACTOR_COMPUTE', 30, 0
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM data_task_dependency
    WHERE upstream_key = 'BIDASK' AND downstream_key = 'FACTOR_COMPUTE'
);
