-- ============================================================
-- P0-2: 为 stock_recommendation 表添加超额收益字段
-- 超额收益 = 个股收益 - 沪深300收益
-- 用于置信度计算，避免牛市命中率虚高
-- ============================================================

ALTER TABLE stock_recommendation
    ADD COLUMN next_day_excess_return DOUBLE NULL COMMENT '次日超额收益率%（vs沪深300）' AFTER next_day_return,
    ADD COLUMN next_week_excess_return DOUBLE NULL COMMENT '一周超额收益率%（vs沪深300）' AFTER next_week_return,
    ADD COLUMN next_month_excess_return DOUBLE NULL COMMENT '一月超额收益率%（vs沪深300）' AFTER next_month_return;
