-- 为 backtest_task 表添加 max_position_count 字段
-- 用于支持参数优化中的持仓数量网格搜索（任务级覆盖策略定义）
ALTER TABLE backtest_task
ADD COLUMN IF NOT EXISTS max_position_count INT DEFAULT NULL
COMMENT '最大持仓数量，null=使用策略默认值'
AFTER stop_profit_pct;
