-- Trade Calendar 2026 (例外日模式：只记录工作日被调整为非交易日的节假日)
-- 周末由 TradeCalendarService / 前端自动判断，不入库
-- 调休/补班同样不入库，周末一律为非交易日

CREATE TABLE IF NOT EXISTS trade_calendar (
  trade_date DATE PRIMARY KEY COMMENT '日期',
  is_trading TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否交易日：0=非交易',
  reason VARCHAR(50) DEFAULT NULL COMMENT '非交易原因',
  source VARCHAR(20) NOT NULL DEFAULT 'AUTO' COMMENT '来源：AUTO=自动/MANUAL=手动',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  INDEX idx_is_trading (is_trading)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易日历例外日表';

-- 清理旧的周末/调休/补班记录，只保留非交易日
DELETE FROM trade_calendar WHERE is_trading = 1;

-- 2026 年节假日（仅工作日非交易日）
INSERT INTO trade_calendar (trade_date, is_trading, reason, source) VALUES
('2026-01-01', 0, '元旦', 'AUTO'),
('2026-01-02', 0, '元旦', 'AUTO'),
('2026-02-17', 0, '春节', 'AUTO'),
('2026-02-18', 0, '春节', 'AUTO'),
('2026-02-19', 0, '春节', 'AUTO'),
('2026-02-20', 0, '春节', 'AUTO'),
('2026-02-21', 0, '春节', 'AUTO'),
('2026-02-22', 0, '春节', 'AUTO'),
('2026-02-23', 0, '春节', 'AUTO'),
('2026-04-06', 0, '清明', 'AUTO'),
('2026-05-01', 0, '劳动节', 'AUTO'),
('2026-05-04', 0, '劳动节', 'AUTO'),
('2026-05-05', 0, '劳动节', 'AUTO'),
('2026-06-19', 0, '端午节', 'AUTO'),
('2026-09-25', 0, '中秋节', 'AUTO'),
('2026-10-01', 0, '国庆节', 'AUTO'),
('2026-10-02', 0, '国庆节', 'AUTO'),
('2026-10-05', 0, '国庆节', 'AUTO'),
('2026-10-06', 0, '国庆节', 'AUTO'),
('2026-10-07', 0, '国庆节', 'AUTO')
ON DUPLICATE KEY UPDATE is_trading=VALUES(is_trading), reason=VALUES(reason), source=VALUES(source);
