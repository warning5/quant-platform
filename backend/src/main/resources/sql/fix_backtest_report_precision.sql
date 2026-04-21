-- 修复 backtest_report 表数值列精度不足问题
-- DECIMAL(10,6) 最大约 9999.999999，年化收益常超过此范围
-- 改为 DECIMAL(20,8) 最多支持约 9999 亿，足以容纳各种回测指标

ALTER TABLE backtest_report
  MODIFY COLUMN total_return             DECIMAL(20,8) COMMENT '总收益率',
  MODIFY COLUMN annual_return            DECIMAL(20,8) COMMENT '年化收益率',
  MODIFY COLUMN benchmark_return          DECIMAL(20,8) COMMENT '基准总收益率',
  MODIFY COLUMN benchmark_annual_return  DECIMAL(20,8) COMMENT '基准年化收益率',
  MODIFY COLUMN excess_return             DECIMAL(20,8) COMMENT '超额收益率',
  MODIFY COLUMN volatility                DECIMAL(20,8) COMMENT '年化波动率',
  MODIFY COLUMN sharpe_ratio             DECIMAL(20,8) COMMENT '夏普比率',
  MODIFY COLUMN sortino_ratio            DECIMAL(20,8) COMMENT '索提诺比率',
  MODIFY COLUMN calmar_ratio             DECIMAL(20,8) COMMENT '卡玛比率',
  MODIFY COLUMN max_drawdown             DECIMAL(20,8) COMMENT '最大回撤',
  MODIFY COLUMN information_ratio        DECIMAL(20,8) COMMENT '信息比率',
  MODIFY COLUMN alpha                    DECIMAL(20,8) COMMENT 'Alpha',
  MODIFY COLUMN beta                     DECIMAL(20,8) COMMENT 'Beta',
  MODIFY COLUMN tracking_error           DECIMAL(20,8) COMMENT '跟踪误差',
  MODIFY COLUMN downside_risk            DECIMAL(20,8) COMMENT '下行偏差',
  MODIFY COLUMN win_rate                 DECIMAL(20,8) COMMENT '胜率',
  MODIFY COLUMN avg_win_return           DECIMAL(20,8) COMMENT '平均盈利',
  MODIFY COLUMN avg_loss_return           DECIMAL(20,8) COMMENT '平均亏损',
  MODIFY COLUMN profit_loss_ratio        DECIMAL(20,8) COMMENT '盈亏比';
