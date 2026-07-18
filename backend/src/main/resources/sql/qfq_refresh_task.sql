-- ============================================================
-- QFQ_REFRESH 任务配置
-- 前复权因子刷新：除权除息后重刷历史 qfq 价格
-- ============================================================

-- 1. 添加 QFQ_REFRESH 任务到调度配置表
-- 列: task_key, task_name, category, enabled, cron_expression, use_global_cron, extra_config
INSERT INTO data_schedule_config (task_key, task_name, category, enabled, cron_expression, use_global_cron, extra_config)
SELECT 'QFQ_REFRESH', '前复权因子刷新', 'DATA', 1, '0 30 16 * * 1-5', 0,
       '{"incremental": true, "dateMode": "today"}'
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM data_schedule_config WHERE task_key = 'QFQ_REFRESH'
);

-- 2. 添加 DIVIDEND → QFQ_REFRESH 依赖关系
-- 分红数据采集完成后，延迟60秒触发前复权刷新
INSERT INTO data_task_dependency (upstream_key, downstream_key, delay_seconds)
SELECT 'DIVIDEND', 'QFQ_REFRESH', 60
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM data_task_dependency
    WHERE upstream_key = 'DIVIDEND' AND downstream_key = 'QFQ_REFRESH'
);

-- QFQ_REFRESH → FACTOR_COMPUTE 依赖关系（多上游条件：需 DAILY 也完成）
-- 前复权刷新完成后，延迟30秒触发因子重算（确保因子基于最新qfq价格）
INSERT INTO data_task_dependency (upstream_key, downstream_key, delay_seconds, require_all_upstreams)
SELECT 'QFQ_REFRESH', 'FACTOR_COMPUTE', 30, 1
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM data_task_dependency
    WHERE upstream_key = 'QFQ_REFRESH' AND downstream_key = 'FACTOR_COMPUTE'
);

-- 3. DAILY → FACTOR_COMPUTE 依赖关系（多上游条件：需 QFQ_REFRESH 也完成）
-- 日线数据完成后，延迟30秒触发因子重算（确保两条上游都完成）
INSERT INTO data_task_dependency (upstream_key, downstream_key, delay_seconds, require_all_upstreams)
SELECT 'DAILY', 'FACTOR_COMPUTE', 30, 1
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM data_task_dependency
    WHERE upstream_key = 'DAILY' AND downstream_key = 'FACTOR_COMPUTE'
);

-- 4. BIDASK → FACTOR_COMPUTE 依赖关系（单上游条件，可选，若因子公式不需要可删除）
-- 内外盘数据完成后，延迟30秒触发因子重算
INSERT INTO data_task_dependency (upstream_key, downstream_key, delay_seconds, require_all_upstreams)
SELECT 'BIDASK', 'FACTOR_COMPUTE', 30, 0
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM data_task_dependency
    WHERE upstream_key = 'BIDASK' AND downstream_key = 'FACTOR_COMPUTE'
);


-- 说明：
-- cron_expression '0 30 16 * * 1-5' = 每个交易日16:30执行
-- use_global_cron=0 使用独立 cron，不跟随全局
-- DIVIDEND任务通常在15:30左右完成，16:30触发QFQ刷新确保分红数据已就绪
-- 也可由 DIVIDEND → QFQ_REFRESH 依赖链自动触发（延迟60秒）
-- QFQ_REFRESH → FACTOR_COMPUTE 确保因子计算基于刷新后的qfq价格
