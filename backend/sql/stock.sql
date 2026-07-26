/*
 Navicat Premium Dump SQL

 Source Server         : localhost
 Source Server Type    : MySQL
 Source Server Version : 90300 (9.3.0)
 Source Host           : 172.19.64.1:3306
 Source Schema         : stock

 Target Server Type    : MySQL
 Target Server Version : 90300 (9.3.0)
 File Encoding         : 65001

 Date: 26/07/2026 11:19:28
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for backtest_report
-- ----------------------------
DROP TABLE IF EXISTS `backtest_report`;
CREATE TABLE `backtest_report`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `task_id` bigint NULL DEFAULT NULL COMMENT '关联回测任务 ID',
  `strategy_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '策略代码',
  `total_return` decimal(20, 8) NULL DEFAULT NULL COMMENT '总收益率',
  `annual_return` decimal(40, 8) NULL DEFAULT NULL COMMENT '年化收益率',
  `benchmark_return` decimal(20, 8) NULL DEFAULT NULL COMMENT '基准总收益率',
  `benchmark_annual_return` decimal(20, 8) NULL DEFAULT NULL COMMENT '基准年化收益率',
  `excess_return` decimal(40, 8) NULL DEFAULT NULL COMMENT '超额收益率',
  `volatility` decimal(20, 8) NULL DEFAULT NULL COMMENT '波动率',
  `sharpe_ratio` decimal(20, 8) NULL DEFAULT NULL COMMENT '夏普比率',
  `sortino_ratio` decimal(20, 8) NULL DEFAULT NULL COMMENT '索提诺比率',
  `calmar_ratio` decimal(40, 8) NULL DEFAULT NULL COMMENT '卡玛比率',
  `max_drawdown` decimal(20, 8) NULL DEFAULT NULL COMMENT '最大回撤',
  `max_drawdown_duration` int NULL DEFAULT NULL COMMENT '最大回撤持续天数',
  `information_ratio` decimal(20, 8) NULL DEFAULT NULL COMMENT '信息比率',
  `alpha` decimal(20, 8) NULL DEFAULT NULL COMMENT 'Alpha',
  `beta` decimal(20, 8) NULL DEFAULT NULL COMMENT 'Beta',
  `tracking_error` decimal(20, 8) NULL DEFAULT NULL COMMENT '跟踪误差',
  `downside_risk` decimal(20, 8) NULL DEFAULT NULL COMMENT '下行风险',
  `total_trades` int NULL DEFAULT NULL COMMENT '总交易次数',
  `win_rate` decimal(20, 8) NULL DEFAULT NULL COMMENT '胜率',
  `avg_win_return` decimal(20, 8) NULL DEFAULT NULL COMMENT '平均盈利收益率',
  `avg_loss_return` decimal(20, 8) NULL DEFAULT NULL COMMENT '平均亏损收益率',
  `profit_loss_ratio` decimal(20, 8) NULL DEFAULT NULL COMMENT '盈亏比',
  `excess_mean` decimal(10, 6) NULL DEFAULT NULL COMMENT '超额收益均值（年化）',
  `excess_std` decimal(10, 6) NULL DEFAULT NULL COMMENT '超额收益标准差（年化）',
  `excess_win_rate` decimal(10, 6) NULL DEFAULT NULL COMMENT '超额收益胜率',
  `excess_max_drawdown` decimal(10, 6) NULL DEFAULT NULL COMMENT '超额收益最大回撤',
  `alpha_contribution` decimal(10, 6) NULL DEFAULT NULL COMMENT 'Alpha贡献占比',
  `equity_curve_json` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '净值曲线JSON',
  `benchmark_curve_json` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '基准曲线JSON',
  `drawdown_series_json` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '回撤序列JSON',
  `monthly_returns_json` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '月度收益JSON',
  `position_history_json` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '持仓历史JSON',
  `trade_log_json` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '交易日志JSON',
  `created_at` datetime NULL DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `fk_report_task`(`task_id` ASC) USING BTREE,
  CONSTRAINT `fk_report_task` FOREIGN KEY (`task_id`) REFERENCES `backtest_task` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 278 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '回测报告表（收益率/风险指标/净值曲线）' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for backtest_task
-- ----------------------------
DROP TABLE IF EXISTS `backtest_task`;
CREATE TABLE `backtest_task`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `task_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '任务名称',
  `strategy_id` bigint NULL DEFAULT NULL COMMENT '关联策略 ID',
  `strategy_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '关联策略代码',
  `start_date` date NULL DEFAULT NULL COMMENT '回测开始日期',
  `end_date` date NULL DEFAULT NULL COMMENT '回测结束日期',
  `initial_capital` decimal(20, 2) NOT NULL DEFAULT 1000000.00 COMMENT '初始资金',
  `commission_rate` decimal(10, 6) NOT NULL DEFAULT 0.000300 COMMENT '佣金率',
  `slippage_rate` decimal(10, 6) NOT NULL DEFAULT 0.000200 COMMENT '滑点率',
  `benchmark_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '基准指数代码',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/RUNNING/COMPLETED/FAILED/CANCELLED',
  `progress` int NOT NULL DEFAULT 0 COMMENT '进度(0-100)',
  `error_message` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '失败原因',
  `created_at` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `updated_at` datetime NULL DEFAULT NULL COMMENT '更新时间',
  `started_at` datetime NULL DEFAULT NULL COMMENT '开始执行时间',
  `completed_at` datetime NULL DEFAULT NULL COMMENT '完成时间',
  `version` int NULL DEFAULT NULL COMMENT '版本号',
  `author` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '作者',
  `slippage_model` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'FIXED' COMMENT 'slippage model',
  `limit_filter` tinyint NOT NULL DEFAULT 1 COMMENT 'limit filter',
  `suspend_filter` tinyint NOT NULL DEFAULT 1 COMMENT 'suspend filter',
  `stamp_tax_rate` decimal(10, 6) NOT NULL DEFAULT 0.000500 COMMENT 'stamp tax rate',
  `min_commission` decimal(10, 2) NOT NULL DEFAULT 5.00 COMMENT 'min commission',
  `dividend_reinvest` tinyint NOT NULL DEFAULT 0 COMMENT '是否红利再投资',
  `transfer_fee_rate` decimal(10, 6) NOT NULL DEFAULT 0.000020 COMMENT '过户费率（仅上交所，双向，默认0.02‰）',
  `order_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'CLOSE' COMMENT '成交模式: CLOSE/NEXT_OPEN/VWAP',
  `stop_loss_pct` decimal(10, 6) NULL DEFAULT NULL COMMENT '止损比例',
  `stop_profit_pct` decimal(10, 6) NULL DEFAULT NULL COMMENT '止盈比例',
  `max_position_count` int NULL DEFAULT NULL COMMENT '最大持仓数量，null=使用策略默认值',
  `screen_config_json` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '选股配置JSON',
  `signal_source` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'STRATEGY' COMMENT '选股来源: STRATEGY策略因子 / SCREEN因子筛选',
  `rebalance_freq` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'MONTHLY' COMMENT '调仓频率: WEEKLY/BIWEEKLY/MONTHLY',
  `weight_mode` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'EQUAL' COMMENT '权重分配: EQUAL等权/SCORE_PROPORTIONAL按得分',
  `factor_weight_mode` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'STATIC',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 435 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '回测任务表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for data_schedule_config
-- ----------------------------
DROP TABLE IF EXISTS `data_schedule_config`;
CREATE TABLE `data_schedule_config`  (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `task_key` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '任务标识: GLOBAL/DAILY/INDEX/DIVIDEND/FINANCIAL/BIDASK/SENTIMENT/RESEARCH',
  `task_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '显示名称',
  `category` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT 'MAIN' COMMENT '分类: MAIN/SENTIMENT_SUB',
  `enabled` tinyint(1) NULL DEFAULT 1 COMMENT '是否启用',
  `cron_expression` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT 'cron表达式, NULL表示跟随全局',
  `use_global_cron` tinyint(1) NULL DEFAULT 1 COMMENT '1=使用全局cron, 0=使用独立cron',
  `extra_config` json NULL COMMENT '额外配置(JSON), 如SENTIMENT子项开关等',
  `last_run_time` datetime NULL DEFAULT NULL COMMENT '上次运行时间',
  `last_run_status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '上次运行状态',
  `last_run_duration_sec` int NULL DEFAULT NULL COMMENT '上次运行耗时(秒)',
  `next_run_time` datetime NULL DEFAULT NULL COMMENT '下次运行时间',
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `task_key`(`task_key` ASC) USING BTREE,
  INDEX `idx_category`(`category` ASC) USING BTREE,
  INDEX `idx_enabled`(`enabled` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 35 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '数据调度配置表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for data_task_dependency
-- ----------------------------
DROP TABLE IF EXISTS `data_task_dependency`;
CREATE TABLE `data_task_dependency`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `upstream_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `downstream_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `delay_seconds` int NULL DEFAULT 300 COMMENT '触发延迟秒数',
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `require_all_upstreams` tinyint NOT NULL DEFAULT 0 COMMENT '是否要求所有上游都完成才触发下游（0=任一上游完成即触发，1=所有上游完成才触发）',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_upstream_downstream`(`upstream_key` ASC, `downstream_key` ASC) USING BTREE,
  INDEX `idx_downstream`(`downstream_key` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 13 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '任务上下游依赖关系' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for equity_curve
-- ----------------------------
DROP TABLE IF EXISTS `equity_curve`;
CREATE TABLE `equity_curve`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `task_id` bigint NOT NULL COMMENT '任务ID',
  `trade_date` date NOT NULL COMMENT '交易日期',
  `portfolio_value` decimal(18, 4) NULL DEFAULT NULL COMMENT '组合市值',
  `nav` decimal(10, 6) NULL DEFAULT NULL COMMENT '净值',
  `benchmark_nav` decimal(10, 6) NULL DEFAULT NULL COMMENT '基准净值',
  `return_pct` decimal(12, 6) NULL DEFAULT NULL COMMENT '当日收益率(%)',
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_task_date`(`task_id` ASC, `trade_date` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 2407 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '净值曲线表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for factor_definition
-- ----------------------------
DROP TABLE IF EXISTS `factor_definition`;
CREATE TABLE `factor_definition`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `factor_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '因子唯一代码',
  `factor_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '因子名称',
  `category` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '因子分类: MOMENTUM/VALUE/QUALITY/VOLATILITY/TECHNICAL/FUNDAMENTAL/SENTIMENT/CUSTOM',
  `factor_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'BUILTIN',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'DRAFT' COMMENT '状态: DRAFT/TESTING/ACTIVE/DEPRECATED',
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '因子描述',
  `script_code` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT 'Groovy 计算脚本',
  `parameters_json` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '参数配置 JSON',
  `version` int NULL DEFAULT 1 COMMENT '版本号',
  `author` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'system' COMMENT '创建者',
  `stock_pool` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '适配股票池',
  `created_at` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `updated_at` datetime NULL DEFAULT NULL COMMENT '更新时间',
  `cv_threshold` double NULL DEFAULT NULL COMMENT '多日模式CV稳定性过滤阈值，NULL则按category自动推导',
  `data_frequency` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'DAILY',
  `outlier_method` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '极值处理方法: MAD/PERCENTILE/WINSORIZE',
  `normalize_method` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '标准化方法: ZSCORE/RANK/MINMAX',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_factor_code`(`factor_code` ASC) USING BTREE,
  CONSTRAINT `chk_factor_status` CHECK (`status` in (_utf8mb4'DRAFT',_utf8mb4'TESTING',_utf8mb4'ACTIVE',_utf8mb4'DEPRECATED',_utf8mb4'DEGRADED')),
  CONSTRAINT `chk_factor_type` CHECK (`factor_type` in (_utf8mb4'BUILTIN',_utf8mb4'SCRIPTED',_utf8mb4'COMPOSITE',_utf8mb4'PATTERN'))
) ENGINE = InnoDB AUTO_INCREMENT = 388 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '因子定义表（因子配置和元数据）' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for factor_health_log
-- ----------------------------
DROP TABLE IF EXISTS `factor_health_log`;
CREATE TABLE `factor_health_log`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `factor_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '因子代码',
  `event_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '事件类型: DEGRADE_WARNING/DEGRADED/RESURRECT_CANDIDATE/RESURRECTED',
  `ic_30d` double NULL DEFAULT NULL COMMENT '30日IC均值',
  `ic_60d` double NULL DEFAULT NULL COMMENT '60日IC均值',
  `ic_90d` double NULL DEFAULT NULL COMMENT '90日IC均值',
  `ir_30d` double NULL DEFAULT NULL COMMENT '30日IR',
  `ir_60d` double NULL DEFAULT NULL COMMENT '60日IR',
  `ic_at_activation` double NULL DEFAULT NULL COMMENT '激活时的IC基准值',
  `decay_ratio` double NULL DEFAULT NULL COMMENT '衰减比例(ic_90d/ic_at_activation)',
  `reason` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '事件原因说明',
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_factor_code`(`factor_code` ASC) USING BTREE,
  INDEX `idx_event_type`(`event_type` ASC) USING BTREE,
  INDEX `idx_created_at`(`created_at` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 13 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '因子健康日志(降级/复活事件)' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for factor_ic_record
-- ----------------------------
DROP TABLE IF EXISTS `factor_ic_record`;
CREATE TABLE `factor_ic_record`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `factor_code` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '因子代码',
  `trade_date` date NOT NULL COMMENT '交易日期',
  `ic_value` double NULL DEFAULT NULL COMMENT 'IC值（ Spearman Rank 相关系数）',
  `ic20d_avg` double NULL DEFAULT NULL COMMENT 'IC 20日滚动均值',
  `ic60d_avg` double NULL DEFAULT NULL COMMENT 'IC 60日滚动均值',
  `ir20d` double NULL DEFAULT NULL COMMENT 'IR 20日滚动',
  `ir60d` double NULL DEFAULT NULL COMMENT 'IR 60日滚动',
  `ic_20d_avg` double NULL DEFAULT NULL COMMENT 'IC 20日均值',
  `ic_60d_avg` double NULL DEFAULT NULL COMMENT 'IC 60日均值',
  `ir_20d` double NULL DEFAULT NULL COMMENT 'IR (20日IC均值/IC标准差)',
  `ir_60d` double NULL DEFAULT NULL COMMENT 'IR (60日IC均值/IC标准差)',
  `stock_count` int NULL DEFAULT NULL COMMENT '截面股票数量',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `forward_days` int NOT NULL DEFAULT 5 COMMENT 'IC前瞻天数',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_factor_date_fwd`(`factor_code` ASC, `trade_date` ASC, `forward_days` ASC) USING BTREE,
  INDEX `idx_factor_date`(`factor_code` ASC, `trade_date` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 12018 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '因子IC记录表（信息系数历史）' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for factor_test_report
-- ----------------------------
DROP TABLE IF EXISTS `factor_test_report`;
CREATE TABLE `factor_test_report`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `factor_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '因子代码',
  `test_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '测试名称',
  `stock_pool` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '股票池: ALL_A/CSI300/CSI500/CSI800/CSI1000',
  `rebalance_freq` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '调仓频率: DAILY/WEEKLY/MONTHLY',
  `start_date` date NULL DEFAULT NULL COMMENT '测试开始日期',
  `end_date` date NULL DEFAULT NULL COMMENT '测试结束日期',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/RUNNING/COMPLETED/FAILED',
  `ic_mean` decimal(10, 6) NULL DEFAULT NULL COMMENT 'IC 均值',
  `ic_std` decimal(10, 6) NULL DEFAULT NULL COMMENT 'IC 标准差',
  `icir` decimal(10, 6) NULL DEFAULT NULL COMMENT 'ICIR',
  `ic_positive_rate` decimal(10, 6) NULL DEFAULT NULL COMMENT 'IC 正值率',
  `rank_ic_mean` decimal(10, 6) NULL DEFAULT NULL COMMENT 'Rank IC 均值',
  `rank_icir` decimal(10, 6) NULL DEFAULT NULL COMMENT 'Rank ICIR',
  `ic_t_stat` decimal(10, 6) NULL DEFAULT NULL COMMENT 'IC t检验统计量',
  `ic_p_value` decimal(10, 6) NULL DEFAULT NULL COMMENT 'IC t检验 p值',
  `decay_periods` decimal(10, 2) NULL DEFAULT NULL COMMENT '因子有效期(期数)',
  `half_life_periods` decimal(10, 2) NULL DEFAULT NULL COMMENT '因子半衰期(期数)',
  `decay_coefficient` decimal(10, 6) NULL DEFAULT NULL COMMENT '因子衰减系数',
  `decay_r_squared` decimal(10, 6) NULL DEFAULT NULL COMMENT '因子衰减拟合优度R²',
  `turnover_rate` decimal(10, 6) NULL DEFAULT NULL COMMENT 'Top组截面换手率',
  `factor_auto_corr` decimal(10, 6) NULL DEFAULT NULL COMMENT '因子值一阶自相关',
  `decay_series_json` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '因子衰减序列JSON',
  `correlation_matrix_json` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '因子间相关性矩阵JSON',
  `top_group_return` decimal(10, 6) NULL DEFAULT NULL COMMENT '多头组收益',
  `bottom_group_return` decimal(10, 6) NULL DEFAULT NULL COMMENT '空头组收益',
  `best_sharpe` decimal(10, 6) NULL DEFAULT NULL COMMENT '最佳分组夏普比率',
  `active_volatility` decimal(10, 6) NULL DEFAULT NULL COMMENT '多头组主动年化波动率',
  `win_rate_vs_benchmark` decimal(10, 6) NULL DEFAULT NULL COMMENT '多头组相对基准胜率',
  `monotonicity` decimal(10, 6) NULL DEFAULT NULL COMMENT '单调性得分',
  `group_ir` decimal(10, 6) NULL DEFAULT NULL COMMENT '分组收益的信息比率',
  `ls_p_value` decimal(10, 6) NULL DEFAULT NULL COMMENT '多空收益 t检验 p值',
  `long_short_return` decimal(10, 6) NULL DEFAULT NULL COMMENT '多空组合收益',
  `group_count` int NULL DEFAULT NULL COMMENT '分组数(固定5)',
  `ic_series_json` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT 'IC 时序 JSON',
  `group_returns_json` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '分层收益 JSON',
  `group_nav_json` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '分组净值曲线',
  `long_short_nav_json` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '多空净值曲线',
  `version` int NULL DEFAULT NULL COMMENT '版本号',
  `author` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '作者',
  `completed_at` datetime NULL DEFAULT NULL COMMENT '完成时间',
  `error_message` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '失败原因',
  `created_at` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `updated_at` datetime NULL DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 45 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '因子测试报告表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for factor_value
-- ----------------------------
DROP TABLE IF EXISTS `factor_value`;
CREATE TABLE `factor_value`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `factor_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '因子代码',
  `symbol` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '股票代码',
  `calc_date` date NOT NULL COMMENT '计算日期',
  `factor_val` decimal(20, 8) NULL DEFAULT NULL COMMENT '因子原始值',
  `rank_value` decimal(10, 6) NULL DEFAULT NULL COMMENT '横截面百分位排名',
  `z_score` decimal(10, 6) NULL DEFAULT NULL COMMENT 'Z-Score 标准化值',
  `created_at` datetime NULL DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_factor_symbol_date`(`factor_code` ASC, `symbol` ASC, `calc_date` ASC) USING BTREE,
  INDEX `idx_factor_symbol_date`(`factor_code` ASC, `symbol` ASC, `calc_date` ASC) USING BTREE,
  INDEX `idx_factor_date`(`factor_code` ASC, `calc_date` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '因子值表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for index_daily
-- ----------------------------
DROP TABLE IF EXISTS `index_daily`;
CREATE TABLE `index_daily`  (
  `code` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '指数代码（纯数字无前缀：000001=上证指数 / 000300=沪深300 / 399006=创业板指）',
  `trade_date` date NOT NULL COMMENT '交易日期',
  `name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '指数名称（如：上证指数、沪深300、创业板指等）',
  `open_price` decimal(10, 2) NULL DEFAULT NULL COMMENT '开盘价',
  `close_price` decimal(10, 2) NULL DEFAULT NULL COMMENT '收盘价',
  `high_price` decimal(10, 2) NULL DEFAULT NULL COMMENT '最高价',
  `low_price` decimal(10, 2) NULL DEFAULT NULL COMMENT '最低价',
  `pre_close` decimal(10, 2) NULL DEFAULT NULL COMMENT '昨收价',
  `volume` bigint NULL DEFAULT NULL COMMENT '成交量（手）',
  `amount` decimal(18, 2) NULL DEFAULT NULL COMMENT '成交额（元）',
  `change_percent` decimal(8, 2) NULL DEFAULT NULL COMMENT '涨跌幅(%)',
  `change_amount` decimal(10, 2) NULL DEFAULT NULL COMMENT '涨跌额(元)',
  `turnover_rate` decimal(8, 4) NULL DEFAULT NULL COMMENT '换手率(%)（指数通常为NULL）',
  `pe_ttm` decimal(12, 4) NULL DEFAULT NULL COMMENT '市盈率TTM（指数通常为NULL）',
  `pb` decimal(8, 4) NULL DEFAULT NULL COMMENT '市净率（指数通常为NULL）',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（ON UPDATE自动更新当前时间）',
  PRIMARY KEY (`code`, `trade_date`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '指数日线行情（沪深10大宽基指数）' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for llm_analysis
-- ----------------------------
DROP TABLE IF EXISTS `llm_analysis`;
CREATE TABLE `llm_analysis`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `stock_code` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '股票代码',
  `stock_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '股票名称',
  `analysis_date` date NOT NULL COMMENT '分析日期',
  `model` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'deepseek-chat' COMMENT '使用的LLM模型',
  `recommendation` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'WATCH' COMMENT 'BUY/WATCH/SKIP',
  `buy_price_low` decimal(10, 3) NULL DEFAULT NULL COMMENT '买入价下限',
  `buy_price_high` decimal(10, 3) NULL DEFAULT NULL COMMENT '买入价上限',
  `stop_loss` decimal(10, 3) NULL DEFAULT NULL COMMENT '止损价',
  `target_price` decimal(10, 3) NULL DEFAULT NULL COMMENT '目标价',
  `risk_level` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'MEDIUM' COMMENT 'LOW/MEDIUM/HIGH',
  `logic` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '投资逻辑',
  `position_advice` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '仓位建议',
  `time_horizon` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '投资周期',
  `catalysts` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '催化剂(分号分隔)',
  `risks` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '风险(分号分隔)',
  `raw_response` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT 'LLM原始返回JSON',
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_stock_date`(`stock_code` ASC, `analysis_date` ASC) USING BTREE,
  INDEX `idx_date`(`analysis_date` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 155 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'LLM推理分析结果' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for macro_bond_yield
-- ----------------------------
DROP TABLE IF EXISTS `macro_bond_yield`;
CREATE TABLE `macro_bond_yield`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `trade_date` date NOT NULL COMMENT '交易日期',
  `yield_10y` double NULL DEFAULT NULL COMMENT '10年期国债收益率(%)',
  `yield_2y` double NULL DEFAULT NULL COMMENT '2年期国债收益率(%)',
  `yield_spread` double NULL DEFAULT NULL COMMENT '10年-2年利差(%)',
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_date`(`trade_date` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1626 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '宏观债券收益率表（国债收益率/利差）' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for market_regime_calendar
-- ----------------------------
DROP TABLE IF EXISTS `market_regime_calendar`;
CREATE TABLE `market_regime_calendar`  (
  `trade_date` date NOT NULL COMMENT '交易日',
  `regime` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'BULL/BEAR/SIDEWAYS',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`trade_date`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '市场环境体制日历' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for market_sentiment
-- ----------------------------
DROP TABLE IF EXISTS `market_sentiment`;
CREATE TABLE `market_sentiment`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `trade_date` date NOT NULL,
  `indicator` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `value` double NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_date_ind`(`trade_date` ASC, `indicator` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 257810 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for monitor_custom_stock
-- ----------------------------
DROP TABLE IF EXISTS `monitor_custom_stock`;
CREATE TABLE `monitor_custom_stock`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `stock_code` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `stock_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `buy_price_low` decimal(10, 2) NULL DEFAULT NULL,
  `buy_price_high` decimal(10, 2) NULL DEFAULT NULL,
  `stop_loss` decimal(10, 2) NULL DEFAULT NULL,
  `target_price` decimal(10, 2) NULL DEFAULT NULL,
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_stock_code`(`stock_code` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 6 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '盘中监控自定义股票表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for paper_cash_flow
-- ----------------------------
DROP TABLE IF EXISTS `paper_cash_flow`;
CREATE TABLE `paper_cash_flow`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `paper_id` bigint NOT NULL COMMENT '模拟盘ID',
  `flow_date` date NOT NULL COMMENT '流水日期',
  `amount` decimal(18, 2) NOT NULL COMMENT '金额（正=入账，负=出账）',
  `flow_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '类型：DEPOSIT/WITHDRAW/DIVIDEND/FEE/BUY_COST/SELL_INCOME',
  `note` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '备注',
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_paper_id`(`paper_id` ASC) USING BTREE,
  INDEX `idx_flow_date`(`flow_date` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '模拟盘现金流记录' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for paper_execution_quality
-- ----------------------------
DROP TABLE IF EXISTS `paper_execution_quality`;
CREATE TABLE `paper_execution_quality`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `paper_id` bigint NOT NULL,
  `signal_id` bigint NOT NULL,
  `code` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `direction` varchar(4) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `signal_price` decimal(10, 2) NULL DEFAULT NULL,
  `executed_price` decimal(10, 2) NULL DEFAULT NULL,
  `price_deviation` decimal(10, 2) NULL DEFAULT NULL,
  `price_deviation_pct` decimal(10, 6) NULL DEFAULT NULL,
  `slippage_cost` decimal(12, 2) NULL DEFAULT NULL,
  `commission` decimal(12, 2) NULL DEFAULT NULL,
  `total_cost` decimal(12, 2) NULL DEFAULT NULL,
  `execution_time` datetime NULL DEFAULT NULL,
  `fill_rate` decimal(5, 4) NULL DEFAULT 1.0000,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_paper_signal`(`paper_id` ASC, `signal_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '模拟盘执行质量记录表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for paper_nav
-- ----------------------------
DROP TABLE IF EXISTS `paper_nav`;
CREATE TABLE `paper_nav`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `paper_id` bigint NOT NULL COMMENT '模拟盘ID',
  `nav_date` date NOT NULL COMMENT '净值日期',
  `total_assets` decimal(18, 2) NOT NULL COMMENT '当日总资产（元）',
  `daily_return` decimal(10, 6) NULL DEFAULT NULL COMMENT '日收益率（%）',
  `cumulative_return` decimal(10, 6) NULL DEFAULT NULL COMMENT '累计收益率（%）',
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_paper_date`(`paper_id` ASC, `nav_date` ASC) USING BTREE,
  INDEX `idx_paper_date`(`paper_id` ASC, `nav_date` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 38 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '模拟盘净值表（日总资产/日收益率/累计收益率）' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for paper_position
-- ----------------------------
DROP TABLE IF EXISTS `paper_position`;
CREATE TABLE `paper_position`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `paper_id` bigint NOT NULL COMMENT '模拟盘ID',
  `code` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '股票代码',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '股票名称',
  `shares` int NOT NULL COMMENT '持有股数',
  `cost_price` decimal(12, 4) NOT NULL COMMENT '持仓成本价',
  `current_price` decimal(12, 4) NULL DEFAULT NULL COMMENT '当前市价（收盘后更新）',
  `market_value` decimal(18, 2) NULL DEFAULT NULL COMMENT '持仓市值（元）',
  `profit_loss` decimal(18, 2) NULL DEFAULT NULL COMMENT '持仓浮盈亏（元）',
  `profit_loss_pct` decimal(10, 4) NULL DEFAULT NULL COMMENT '持仓浮盈亏比例（%）',
  `buy_date` date NULL DEFAULT NULL COMMENT '买入日期',
  `updated_at` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最后更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_paper_code`(`paper_id` ASC, `code` ASC) USING BTREE,
  INDEX `idx_paper`(`paper_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 48 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '模拟盘持仓表（代码/股数/成本价/现价/浮盈亏）' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for paper_risk_config
-- ----------------------------
DROP TABLE IF EXISTS `paper_risk_config`;
CREATE TABLE `paper_risk_config`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `paper_id` bigint NOT NULL COMMENT '模拟盘ID',
  `stop_loss_pct` decimal(5, 2) NULL DEFAULT 8.00 COMMENT '止损比例（%）默认8%',
  `take_profit_pct` decimal(5, 2) NULL DEFAULT 30.00 COMMENT '止盈比例（%）默认30%',
  `trailing_atr` decimal(3, 1) NULL DEFAULT 0.0 COMMENT 'ATR移动止损倍数（0=禁用）',
  `max_position_pct` decimal(5, 2) NULL DEFAULT 20.00 COMMENT '单股仓位上限（%）默认20%',
  `max_industry_pct` decimal(5, 2) NULL DEFAULT 35.00 COMMENT '单一行业仓位上限（%）默认35%',
  `max_drawdown_pct` decimal(5, 2) NULL DEFAULT 20.00 COMMENT '最大回撤限制（%）默认20%',
  `timing_enabled` tinyint NULL DEFAULT 0 COMMENT '是否启用大盘择时（0=禁用，1=启用）',
  `benchmark_code` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '000300' COMMENT '基准指数代码',
  `allocation_mode` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'equal' COMMENT '资金分配模式：equal/dynamic/kelly',
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '配置创建时间',
  `updated_at` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '配置更新时间',
  `slippage_pct` decimal(6, 4) NULL DEFAULT 0.0020 COMMENT '滑点比例（小数，0.002=0.2%）',
  `slippage_model` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'NONE' COMMENT '滑点模型：NONE/FIXED',
  `cash_buffer_pct` decimal(6, 4) NULL DEFAULT 0.0500 COMMENT '现金缓冲比例（小数，0.05=5%）',
  `rebalance_freq` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'DAILY' COMMENT '再平衡频率：DAILY/WEEKLY/MONTHLY/QUARTERLY/THRESHOLD/VOL_ADAPTIVE/HYBRID',
  `rebalance_threshold` decimal(6, 4) NULL DEFAULT 0.0500 COMMENT '再平衡偏离阈值（小数，0.05=5%）',
  `auto_block_enabled` int NULL DEFAULT 1 COMMENT '是否启用自动阻断（1=启用阻断，0=仅预警）',
  `twap_threshold` int NULL DEFAULT 50000 COMMENT 'TWAP大单拆分阈值（股），超过此数量触发TWAP拆分',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_paper`(`paper_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 3 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '模拟盘风控配置表（止损/止盈/集中度/行业暴露/回撤限制）' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for paper_signal
-- ----------------------------
DROP TABLE IF EXISTS `paper_signal`;
CREATE TABLE `paper_signal`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `paper_id` bigint NOT NULL COMMENT '模拟盘ID',
  `signal_date` date NOT NULL COMMENT '信号生成日期',
  `factor_date` date NULL DEFAULT NULL COMMENT '因子数据日期',
  `code` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '股票代码',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '股票名称',
  `direction` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '信号方向：BUY/SELL',
  `signal_price` decimal(12, 4) NULL DEFAULT NULL COMMENT '信号价格',
  `factor_score` decimal(10, 4) NULL DEFAULT NULL COMMENT '因子综合得分',
  `reason` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '信号原因/依据',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'PENDING' COMMENT '信号状态：PENDING/EXECUTED/SKIPPED/EXPIRED',
  `executed_price` decimal(12, 4) NULL DEFAULT NULL COMMENT '实际成交价格',
  `executed_at` datetime NULL DEFAULT NULL COMMENT '实际成交时间',
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  `price_deviation_pct` decimal(10, 6) NULL DEFAULT NULL COMMENT '执行价与信号价的偏差百分比（小数，正=执行价更高）',
  `order_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'MARKET' COMMENT '订单类型：MARKET/LIMIT/STOP/STOP_LIMIT/TRAILING_STOP',
  `trigger_price` decimal(12, 4) NULL DEFAULT NULL COMMENT '触发价格（限价单/止损单触发价）',
  `limit_price` decimal(12, 4) NULL DEFAULT NULL COMMENT '限价（止损限价单的执行限价）',
  `trail_pct` decimal(8, 6) NULL DEFAULT NULL COMMENT '追踪止损回撤比例（如0.05=5%）',
  `trail_amount` decimal(12, 4) NULL DEFAULT NULL COMMENT '追踪止损回撤金额（元）',
  `highest_since_buy` decimal(12, 4) NULL DEFAULT NULL COMMENT '追踪止损最高价记录',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_paper_date`(`paper_id` ASC, `signal_date` ASC) USING BTREE,
  INDEX `idx_status`(`status` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 408 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '模拟盘交易信号表（买入卖出信号/价格/状态）' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for paper_trading
-- ----------------------------
DROP TABLE IF EXISTS `paper_trading`;
CREATE TABLE `paper_trading`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `strategy_id` bigint NOT NULL COMMENT '关联策略ID',
  `strategy_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '策略代码',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/PAUSED/STOPPED',
  `initial_capital` decimal(18, 2) NULL DEFAULT 1000000.00 COMMENT '初始资金',
  `current_capital` decimal(18, 2) NULL DEFAULT NULL COMMENT '当前资金',
  `total_assets` decimal(18, 2) NULL DEFAULT NULL COMMENT '总资产',
  `position_count` int NULL DEFAULT 0 COMMENT '持仓数量',
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `strategy_config_json` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '多策略组合配置JSON：[{strategyId:1,weight:0.4},...]',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_strategy`(`strategy_id` ASC) USING BTREE,
  INDEX `idx_status`(`status` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 8 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '模拟交易表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for param_optimize_report
-- ----------------------------
DROP TABLE IF EXISTS `param_optimize_report`;
CREATE TABLE `param_optimize_report`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `job_id` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '优化任务唯一ID（用于恢复和查询）',
  `strategy_id` bigint NOT NULL COMMENT '关联的策略ID',
  `strategy_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '策略代码（如：MACROSS）',
  `task_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '任务名称（用户自定义）',
  `start_date` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '回测开始日期（YYYY-MM-DD）',
  `end_date` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '回测结束日期（YYYY-MM-DD）',
  `objective` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '目标函数（Sharpe/Calar/AnnualReturn）',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '任务状态：PENDING（待执行）/ RUNNING（运行中）/ COMPLETED（已完成）/ FAILED（失败）',
  `total` int NULL DEFAULT NULL COMMENT '总参数组合数',
  `done` int NULL DEFAULT NULL COMMENT '已完成组合数',
  `progress` int NULL DEFAULT NULL COMMENT '进度百分比（0~100）',
  `best_params_json` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '最优参数组合（JSON格式）',
  `best_score` decimal(30, 10) NULL DEFAULT NULL COMMENT '最优得分',
  `best_annual_return` decimal(30, 10) NULL DEFAULT NULL COMMENT '最优年化收益率',
  `best_max_drawdown` decimal(30, 10) NULL DEFAULT NULL COMMENT '最优最大回撤',
  `results_json` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '全部参数组合结果（JSON数组）',
  `error_message` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '错误信息（任务失败时记录）',
  `elapsed_ms` bigint NULL DEFAULT NULL COMMENT '任务总耗时（毫秒）',
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  `updated_at` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
  `param_grid_json` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '参数网格定义JSON',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_job_id`(`job_id` ASC) USING BTREE,
  INDEX `idx_strategy_id`(`strategy_id` ASC) USING BTREE,
  INDEX `idx_status`(`status` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 94 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '参数优化报告表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for position_alert
-- ----------------------------
DROP TABLE IF EXISTS `position_alert`;
CREATE TABLE `position_alert`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `paper_id` bigint NOT NULL COMMENT '模拟盘ID',
  `code` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '股票代码',
  `name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '股票名称',
  `alert_type` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '预警类型: MA_BREAK/DROP/NOTICE/REPORT',
  `alert_level` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'WARNING' COMMENT '级别: CRITICAL/WARNING/INFO',
  `title` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '预警标题',
  `detail` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '预警详情',
  `alert_date` date NOT NULL COMMENT '预警日期',
  `is_read` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否已读',
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_paper_date`(`paper_id` ASC, `alert_date` ASC) USING BTREE,
  INDEX `idx_paper_read`(`paper_id` ASC, `is_read` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 29 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '持仓预警表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for rebalance_record
-- ----------------------------
DROP TABLE IF EXISTS `rebalance_record`;
CREATE TABLE `rebalance_record`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `task_id` bigint NOT NULL COMMENT '关联任务ID',
  `rebalance_date` date NOT NULL COMMENT '调仓日期',
  `old_positions_json` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '调仓前持仓JSON [{symbol,shares,cost}]',
  `new_positions_json` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '调仓后持仓JSON [{symbol,weight,score}]',
  `buys_json` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '买入明细JSON [{symbol,price,shares,amount}]',
  `sells_json` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '卖出明细JSON [{symbol,price,shares,amount,pnl}]',
  `cash` decimal(18, 2) NULL DEFAULT NULL COMMENT '当日现金(元)',
  `total_value` decimal(18, 2) NULL DEFAULT NULL COMMENT '总资产(元)',
  `nav` decimal(12, 6) NULL DEFAULT NULL COMMENT '当日净值(从1.0起)',
  `daily_return` decimal(10, 6) NULL DEFAULT NULL COMMENT '当日收益率',
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_task_date`(`task_id` ASC, `rebalance_date` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 277 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '调仓记录表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for stock_announcement
-- ----------------------------
DROP TABLE IF EXISTS `stock_announcement`;
CREATE TABLE `stock_announcement`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `code` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '股票代码',
  `title` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '公告标题',
  `category` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '公告类别（定增/解禁/股权激励/业绩预告等）',
  `publish_date` date NOT NULL COMMENT '公告日期',
  `summary` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '摘要',
  `url` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '原文链接',
  `risk_level` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '风险等级：HIGH/MEDIUM/LOW',
  `fetched_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_code`(`code` ASC) USING BTREE,
  INDEX `idx_publish_date`(`publish_date` ASC) USING BTREE,
  INDEX `idx_category`(`category` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '公告表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for stock_balance
-- ----------------------------
DROP TABLE IF EXISTS `stock_balance`;
CREATE TABLE `stock_balance`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `code` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '股票代码（不含市场标识，如：000001）',
  `report_date` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '报告期',
  `report_type` tinyint NOT NULL COMMENT '报告类型：1-一季报 2-中报 3-三季报 4-年报',
  `end_date` date NOT NULL COMMENT '报告截止日期',
  `total_assets` decimal(20, 4) NULL DEFAULT NULL COMMENT '资产总计（元）',
  `total_current_assets` decimal(20, 4) NULL DEFAULT NULL COMMENT '流动资产合计（元）',
  `total_non_current_assets` decimal(20, 4) NULL DEFAULT NULL COMMENT '非流动资产合计（元）',
  `cash_and_equivalents` decimal(20, 4) NULL DEFAULT NULL COMMENT '货币资金（元）',
  `trading_assets` decimal(20, 4) NULL DEFAULT NULL COMMENT '交易性金融资产（元）',
  `notes_receivable` decimal(20, 4) NULL DEFAULT NULL COMMENT '应收票据（元）',
  `accounts_receivable` decimal(20, 4) NULL DEFAULT NULL COMMENT '应收账款（元）',
  `prepayments` decimal(20, 4) NULL DEFAULT NULL COMMENT '预付款项（元）',
  `other_receivable` decimal(20, 4) NULL DEFAULT NULL COMMENT '其他应收款（元）',
  `inventory` decimal(20, 4) NULL DEFAULT NULL COMMENT '存货（元）',
  `contract_assets` decimal(20, 4) NULL DEFAULT NULL COMMENT '合同资产（元）',
  `long_term_equity_invest` decimal(20, 4) NULL DEFAULT NULL COMMENT '长期股权投资（元）',
  `fixed_assets` decimal(20, 4) NULL DEFAULT NULL COMMENT '固定资产（元）',
  `construction_in_progress` decimal(20, 4) NULL DEFAULT NULL COMMENT '在建工程（元）',
  `intangible_assets` decimal(20, 4) NULL DEFAULT NULL COMMENT '无形资产（元）',
  `goodwill` decimal(20, 4) NULL DEFAULT NULL COMMENT '商誉（元）',
  `long_term_prepaid_expense` decimal(20, 4) NULL DEFAULT NULL COMMENT '长期待摊费用（元）',
  `deferred_tax_assets` decimal(20, 4) NULL DEFAULT NULL COMMENT '递延所得税资产（元）',
  `total_liabilities` decimal(20, 4) NULL DEFAULT NULL COMMENT '负债合计（元）',
  `total_current_liabilities` decimal(20, 4) NULL DEFAULT NULL COMMENT '流动负债合计（元）',
  `total_non_current_liabilities` decimal(20, 4) NULL DEFAULT NULL COMMENT '非流动负债合计（元）',
  `short_term_borrowing` decimal(20, 4) NULL DEFAULT NULL COMMENT '短期借款（元）',
  `notes_payable` decimal(20, 4) NULL DEFAULT NULL COMMENT '应付票据（元）',
  `accounts_payable` decimal(20, 4) NULL DEFAULT NULL COMMENT '应付账款（元）',
  `advance_peceipts` decimal(20, 4) NULL DEFAULT NULL COMMENT '预收款项（元）',
  `contract_liabilities` decimal(20, 4) NULL DEFAULT NULL COMMENT '合同负债（元）',
  `employee_benefit_payable` decimal(20, 4) NULL DEFAULT NULL COMMENT '应付职工薪酬（元）',
  `taxs_payable` decimal(20, 4) NULL DEFAULT NULL COMMENT '应交税费（元）',
  `other_payable` decimal(20, 4) NULL DEFAULT NULL COMMENT '其他应付款（元）',
  `long_term_borrowing` decimal(20, 4) NULL DEFAULT NULL COMMENT '长期借款（元）',
  `bonds_payable` decimal(20, 4) NULL DEFAULT NULL COMMENT '应付债券（元）',
  `lease_liabilities` decimal(20, 4) NULL DEFAULT NULL COMMENT '租赁负债（元）',
  `deferred_tax_liabilities` decimal(20, 4) NULL DEFAULT NULL COMMENT '递延所得税负债（元）',
  `total_equity` decimal(20, 4) NULL DEFAULT NULL COMMENT '所有者权益合计（元）',
  `parent_equity` decimal(20, 4) NULL DEFAULT NULL COMMENT '归属母公司所有者权益（元）',
  `minority_interests` decimal(20, 4) NULL DEFAULT NULL COMMENT '少数股东权益（元）',
  `paid_in_capital` decimal(20, 4) NULL DEFAULT NULL COMMENT '实收资本（元）',
  `capital_reserve` decimal(20, 4) NULL DEFAULT NULL COMMENT '资本公积（元）',
  `surplus_reserve` decimal(20, 4) NULL DEFAULT NULL COMMENT '盈余公积（元）',
  `treasury_stock` decimal(20, 4) NULL DEFAULT NULL COMMENT '库存股（元）',
  `undistributed_profit` decimal(20, 4) NULL DEFAULT NULL COMMENT '未分配利润（元）',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `source` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_code_report`(`code` ASC, `report_date` ASC) USING BTREE,
  INDEX `idx_report_type`(`report_type` ASC) USING BTREE,
  INDEX `idx_end_date`(`end_date` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1221335 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '资产负债表（季报/年报，含总资产/总负债/所有者权益）' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for stock_bid_ask
-- ----------------------------
DROP TABLE IF EXISTS `stock_bid_ask`;
CREATE TABLE `stock_bid_ask`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `code` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `trade_date` date NOT NULL COMMENT '交易日期',
  `outer_vol` bigint NULL DEFAULT NULL COMMENT '外盘量（主动买盘成交量）',
  `inner_vol` bigint NULL DEFAULT NULL COMMENT '内盘量（主动卖盘成交量）',
  `ratio` decimal(10, 4) NULL DEFAULT NULL COMMENT '内外盘比（外盘/内盘）',
  `latest_price` decimal(10, 2) NULL DEFAULT NULL COMMENT '最新价',
  `total_vol` bigint NULL DEFAULT NULL COMMENT '总手',
  `amount` decimal(20, 2) NULL DEFAULT NULL COMMENT '成交额（元）',
  `turnover_rate` decimal(10, 4) NULL DEFAULT NULL COMMENT '换手率（%）',
  `change_pct` decimal(10, 2) NULL DEFAULT NULL COMMENT '涨跌幅（%）',
  `fetched_at` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '数据获取时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_code_date`(`code` ASC, `trade_date` ASC) USING BTREE,
  UNIQUE INDEX `stock_bid_ask_code_trade_date_uindex`(`code` ASC, `trade_date` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 540809 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '内外盘数据表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for stock_blacklist
-- ----------------------------
DROP TABLE IF EXISTS `stock_blacklist`;
CREATE TABLE `stock_blacklist`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `strategy_id` bigint NOT NULL COMMENT '策略ID',
  `stock_code` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '股票代码（纯代码，无后缀）',
  `stock_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '股票名称',
  `reason` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '加入原因: CONSECUTIVE_LOSS/LOW_HIT_RATE/SEVERE_LOSS/MANUAL',
  `reason_detail` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '原因详情描述',
  `blacklist_until` date NULL DEFAULT NULL COMMENT '黑名单到期日期（NULL=永久）',
  `created_by` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT 'AUTO' COMMENT '创建方式: AUTO/MANUAL',
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_strategy_active`(`strategy_id` ASC, `stock_code` ASC, `blacklist_until` ASC) USING BTREE,
  INDEX `idx_strategy_id`(`strategy_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 743 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '股票黑名单 - 自动/手动屏蔽历史失利或踩雷的股票' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for stock_cashflow
-- ----------------------------
DROP TABLE IF EXISTS `stock_cashflow`;
CREATE TABLE `stock_cashflow`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `code` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '股票代码（不含市场标识，如：000001）',
  `report_date` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '报告期',
  `report_type` tinyint NOT NULL COMMENT '报告类型：1-一季报 2-中报 3-三季报 4-年报',
  `end_date` date NOT NULL COMMENT '报告截止日期',
  `net_operate_cf` decimal(20, 4) NULL DEFAULT NULL COMMENT '经营活动产生的现金流量净额（元）',
  `cash_received_sales` decimal(20, 4) NULL DEFAULT NULL COMMENT '销售商品、提供劳务收到的现金（元）',
  `tax_refund_received` decimal(20, 4) NULL DEFAULT NULL COMMENT '收到的税费返还（元）',
  `cash_paid_goods_services` decimal(20, 4) NULL DEFAULT NULL COMMENT '购买商品、接受劳务支付的现金（元）',
  `cash_paid_employee` decimal(20, 4) NULL DEFAULT NULL COMMENT '支付给职工以及为职工支付的现金（元）',
  `cash_paid_tax` decimal(20, 4) NULL DEFAULT NULL COMMENT '支付的各项税费（元）',
  `net_invest_cf` decimal(20, 4) NULL DEFAULT NULL COMMENT '投资活动产生的现金流量净额（元）',
  `cash_received_invest_income` decimal(20, 4) NULL DEFAULT NULL COMMENT '收回投资收到的现金（元）',
  `cash_received_invest_return` decimal(20, 4) NULL DEFAULT NULL COMMENT '取得投资收益收到的现金（元）',
  `dispose_invest_income` decimal(20, 4) NULL DEFAULT NULL COMMENT '处置固定资产等收回的现金净额（元）',
  `cash_paid_invest` decimal(20, 4) NULL DEFAULT NULL COMMENT '投资支付的现金（元）',
  `cash_paid_acquisition` decimal(20, 4) NULL DEFAULT NULL COMMENT '购建固定资产等支付的现金（元）',
  `net_finance_cf` decimal(20, 4) NULL DEFAULT NULL COMMENT '筹资活动产生的现金流量净额（元）',
  `cash_received_absorb_invest` decimal(20, 4) NULL DEFAULT NULL COMMENT '吸收投资收到的现金（元）',
  `cash_received_borrowing` decimal(20, 4) NULL DEFAULT NULL COMMENT '取得借款收到的现金（元）',
  `cash_paid_borrowing` decimal(20, 4) NULL DEFAULT NULL COMMENT '偿还债务支付的现金（元）',
  `cash_paid_dividend` decimal(20, 4) NULL DEFAULT NULL COMMENT '分配股利、利润或偿付利息支付的现金（元）',
  `exchange_rate_effect` decimal(20, 4) NULL DEFAULT NULL COMMENT '汇率变动对现金的影响（元）',
  `net_cash_increase` decimal(20, 4) NULL DEFAULT NULL COMMENT '现金及现金等价物净增加额（元）',
  `cash_at_beginning` decimal(20, 4) NULL DEFAULT NULL COMMENT '期初现金及现金等价物余额（元）',
  `cash_at_end` decimal(20, 4) NULL DEFAULT NULL COMMENT '期末现金及现金等价物余额（元）',
  `free_cash_flow` decimal(20, 4) NULL DEFAULT NULL COMMENT '自由现金流 = 经营净现金流 - 资本支出（元）',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `source` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_code_report`(`code` ASC, `report_date` ASC) USING BTREE,
  INDEX `idx_report_type`(`report_type` ASC) USING BTREE,
  INDEX `idx_end_date`(`end_date` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1212133 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '现金流量表（季报/年报，含经营现金流/投资现金流/筹资现金流）' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for stock_company
-- ----------------------------
DROP TABLE IF EXISTS `stock_company`;
CREATE TABLE `stock_company`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `code` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '股票代码（不含市场标识，如：000001）',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '公司名称',
  `area` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '所属地区',
  `fullname` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '公司全称',
  `enname` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '英文名称',
  `cnspell` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '拼音缩写',
  `market` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '市场（SH/SZ/BJ）',
  `list_date` date NULL DEFAULT NULL COMMENT '上市日期',
  `introduction` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '公司简介',
  `business_scope` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '经营范围',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_code`(`code` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 21873 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '公司基本信息表（证监会行业分类/上市时间/注册地）' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for stock_concept
-- ----------------------------
DROP TABLE IF EXISTS `stock_concept`;
CREATE TABLE `stock_concept`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `concept_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '概念名称，如储能/算力/芯片',
  `code` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '股票代码',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '股票名称',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_concept_code`(`concept_name` ASC, `code` ASC) USING BTREE,
  INDEX `idx_code`(`code` ASC) USING BTREE,
  INDEX `idx_concept`(`concept_name` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 12517 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '概念股关联表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for stock_consensus_estimate
-- ----------------------------
DROP TABLE IF EXISTS `stock_consensus_estimate`;
CREATE TABLE `stock_consensus_estimate`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `code` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '股票代码(纯代码)',
  `forecast_year` int NOT NULL COMMENT '预测年度',
  `agency_count` int NULL DEFAULT NULL COMMENT '预测机构数',
  `estimate_min` decimal(20, 4) NULL DEFAULT NULL COMMENT '预测最小值(亿元)',
  `estimate_avg` decimal(20, 4) NULL DEFAULT NULL COMMENT '预测均值(亿元)',
  `estimate_max` decimal(20, 4) NULL DEFAULT NULL COMMENT '预测最大值(亿元)',
  `industry_avg` decimal(20, 4) NULL DEFAULT NULL COMMENT '行业平均(亿元)',
  `update_time` datetime NOT NULL COMMENT '数据更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_code_year`(`code` ASC, `forecast_year` ASC) USING BTREE,
  INDEX `idx_code`(`code` ASC) USING BTREE,
  INDEX `idx_update_time`(`update_time` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 413784 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '一致预期(同花顺)' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for stock_daily
-- ----------------------------
DROP TABLE IF EXISTS `stock_daily`;
CREATE TABLE `stock_daily`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `code` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '股票代码（不含市场标识，如：000001）',
  `trade_date` date NOT NULL COMMENT '交易日期',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '股票名称',
  `open_price` decimal(10, 2) NULL DEFAULT NULL COMMENT '开盘价',
  `close_price` decimal(10, 2) NULL DEFAULT NULL COMMENT '收盘价',
  `high_price` decimal(10, 2) NULL DEFAULT NULL COMMENT '最高价',
  `low_price` decimal(10, 2) NULL DEFAULT NULL COMMENT '最低价',
  `pre_close` decimal(10, 2) NULL DEFAULT NULL COMMENT '昨收价',
  `volume` bigint NULL DEFAULT NULL COMMENT '成交量（手）',
  `amount` decimal(20, 2) NULL DEFAULT NULL COMMENT '成交额（元）',
  `change_percent` decimal(10, 4) NULL DEFAULT NULL COMMENT '涨跌幅（%）',
  `change_amount` decimal(10, 2) NULL DEFAULT NULL COMMENT '涨跌额（元）',
  `turnover_rate` decimal(10, 4) NULL DEFAULT NULL COMMENT '换手率（%）',
  `pe_ttm` decimal(10, 2) NULL DEFAULT NULL COMMENT '市盈率（TTM）',
  `pb` decimal(10, 2) NULL DEFAULT NULL COMMENT '市净率',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_code_date`(`code` ASC, `trade_date` ASC) USING BTREE,
  INDEX `idx_trade_date`(`trade_date` ASC) USING BTREE,
  INDEX `idx_trade_date_pctchg`(`trade_date` ASC, `change_percent` ASC) USING BTREE,
  INDEX `stock_daily_code_index`(`code` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 9268167 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '个股日线行情（Open/High/Low/Close/Volume）' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for stock_dividend
-- ----------------------------
DROP TABLE IF EXISTS `stock_dividend`;
CREATE TABLE `stock_dividend`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `code` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '股票名称',
  `ex_dividend_date` date NOT NULL COMMENT '除权除息日',
  `record_date` date NULL DEFAULT NULL COMMENT '股权登记日',
  `pay_date` date NULL DEFAULT NULL COMMENT '派息日',
  `cash_dividend` decimal(14, 6) NULL DEFAULT NULL COMMENT '每股派息（元，税前）',
  `stock_dividend` decimal(14, 6) NULL DEFAULT NULL COMMENT '每股送股（股）',
  `convert_dividend` decimal(14, 6) NULL DEFAULT NULL COMMENT '每股转增（股）',
  `report_year` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '报告年度',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_code_ex_date`(`code` ASC, `ex_dividend_date` ASC) USING BTREE,
  INDEX `idx_code`(`code` ASC) USING BTREE,
  INDEX `idx_ex_date`(`ex_dividend_date` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1360844 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '分红送股数据表（含现金分红/送股/转增/配股）' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for stock_earnings_report
-- ----------------------------
DROP TABLE IF EXISTS `stock_earnings_report`;
CREATE TABLE `stock_earnings_report`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `code` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '股票代码(纯代码)',
  `name` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '股票简称',
  `report_date` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '报告期(如20250331)',
  `eps` decimal(10, 4) NULL DEFAULT NULL COMMENT '每股收益',
  `revenue` decimal(20, 4) NULL DEFAULT NULL COMMENT '营业收入(元)',
  `revenue_yoy` decimal(10, 4) NULL DEFAULT NULL COMMENT '营收同比增长率(%)',
  `net_profit` decimal(20, 4) NULL DEFAULT NULL COMMENT '净利润(元)',
  `net_profit_yoy` decimal(10, 4) NULL DEFAULT NULL COMMENT '净利润同比增长率(%)',
  `roe` decimal(10, 4) NULL DEFAULT NULL COMMENT '净资产收益率(%)',
  `bvps` decimal(10, 4) NULL DEFAULT NULL COMMENT '每股净资产',
  `industry` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '所属行业',
  `announce_date` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '公告日期',
  `update_time` datetime NOT NULL COMMENT '数据更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_code_report`(`code` ASC, `report_date` ASC) USING BTREE,
  INDEX `idx_code`(`code` ASC) USING BTREE,
  INDEX `idx_announce`(`announce_date` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 42076 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '业绩快报(东财)' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for stock_financial_indicator
-- ----------------------------
DROP TABLE IF EXISTS `stock_financial_indicator`;
CREATE TABLE `stock_financial_indicator`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `code` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '股票代码（不含市场标识，如：000001）',
  `report_date` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '报告期',
  `report_type` tinyint NOT NULL COMMENT '报告类型：1-一季报 2-中报 3-三季报 4-年报',
  `end_date` date NOT NULL COMMENT '报告截止日期',
  `gross_profit_margin` decimal(14, 4) NULL DEFAULT NULL COMMENT '毛利率(%)',
  `net_profit_margin` decimal(14, 4) NULL DEFAULT NULL COMMENT '净利率(%)',
  `roe` decimal(14, 4) NULL DEFAULT NULL COMMENT '净资产收益率ROE(%)',
  `roa` decimal(14, 4) NULL DEFAULT NULL COMMENT '总资产收益率ROA(%)',
  `roic` decimal(14, 4) NULL DEFAULT NULL COMMENT '投入资本回报率ROIC(%)',
  `revenue_yoy` decimal(12, 4) NULL DEFAULT NULL COMMENT '营收同比增长率(%)',
  `net_profit_yoy` decimal(12, 4) NULL DEFAULT NULL COMMENT '净利润同比增长率(%)',
  `operating_profit_yoy` decimal(14, 4) NULL DEFAULT NULL COMMENT '营业利润同比增长率(%)',
  `total_assets_yoy` decimal(14, 4) NULL DEFAULT NULL COMMENT '总资产同比增长率(%)',
  `total_equity_yoy` decimal(14, 4) NULL DEFAULT NULL COMMENT '净资产同比增长率(%)',
  `current_ratio` decimal(14, 4) NULL DEFAULT NULL COMMENT '流动比率',
  `quick_ratio` decimal(14, 4) NULL DEFAULT NULL COMMENT '速动比率',
  `debt_to_asset_ratio` decimal(14, 4) NULL DEFAULT NULL COMMENT '资产负债率(%)',
  `debt_to_equity_ratio` decimal(14, 4) NULL DEFAULT NULL COMMENT '产权比率',
  `interest_coverage_ratio` decimal(14, 4) NULL DEFAULT NULL COMMENT '利息保障倍数',
  `accounts_receivable_turnover` decimal(14, 4) NULL DEFAULT NULL COMMENT '应收账款周转率',
  `ar_turnover_days` decimal(10, 2) NULL DEFAULT NULL COMMENT '应收账款周转天数（天）',
  `ar_to_np_ratio` decimal(20, 4) NULL DEFAULT NULL COMMENT '应收账款/净利润（含少数）*100，反映应收款相对盈利的比率',
  `inventory_turnover` decimal(14, 4) NULL DEFAULT NULL COMMENT '存货周转率',
  `inventory_turnover_days` decimal(10, 2) NULL DEFAULT NULL COMMENT '存货周转天数（天）',
  `total_assets_turnover` decimal(14, 4) NULL DEFAULT NULL COMMENT '总资产周转率',
  `operating_cf_to_np` decimal(14, 4) NULL DEFAULT NULL COMMENT '经营现金流/净利润',
  `free_cash_flow` decimal(20, 4) NULL DEFAULT NULL COMMENT '自由现金流(元)',
  `net_operate_cf` decimal(20, 4) NULL DEFAULT NULL COMMENT '经营现金流净额(元)',
  `operating_cf_to_debt` decimal(14, 4) NULL DEFAULT NULL COMMENT '经营现金流/负债',
  `sales_cash_ratio` decimal(14, 4) NULL DEFAULT NULL COMMENT '销售现金比率',
  `bps` decimal(14, 4) NULL DEFAULT NULL COMMENT '每股净资产',
  `eps_basic` decimal(14, 4) NULL DEFAULT NULL COMMENT '基本每股收益',
  `operating_revenue_per_share` decimal(14, 4) NULL DEFAULT NULL COMMENT '每股营业收入',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `roe_ttm` decimal(14, 4) NULL DEFAULT NULL COMMENT 'ROE_TTM(%)',
  `revenue_ttm_yoy` decimal(12, 4) NULL DEFAULT NULL COMMENT '营收TTM同比增长(%)',
  `net_profit_ttm_yoy` decimal(12, 4) NULL DEFAULT NULL COMMENT '净利润TTM同比增长(%)',
  `announce_date` date NULL DEFAULT NULL COMMENT '财报公告日期（真实披露日期）',
  `source` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `rd_revenue_ratio` decimal(18, 6) NULL DEFAULT NULL COMMENT '研发费用率',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_code_report`(`code` ASC, `report_date` ASC) USING BTREE,
  INDEX `idx_report_type`(`report_type` ASC) USING BTREE,
  INDEX `idx_end_date`(`end_date` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 3570637 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '财务指标表（盈利能力/偿债能力/运营能力）' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for stock_fund_holder
-- ----------------------------
DROP TABLE IF EXISTS `stock_fund_holder`;
CREATE TABLE `stock_fund_holder`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `stock_code` varchar(6) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '股票代码',
  `report_date` date NULL DEFAULT NULL COMMENT '报告期（持仓公布日）',
  `fund_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '基金名称',
  `fund_code` varchar(6) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '基金代码',
  `holding_quantity` bigint NULL DEFAULT NULL COMMENT '持股数量（股）',
  `float_ratio` decimal(10, 6) NULL DEFAULT NULL COMMENT '占流通股本比例（%）',
  `market_value` decimal(20, 2) NULL DEFAULT NULL COMMENT '持仓市值（元）',
  `nav_ratio` decimal(10, 4) NULL DEFAULT NULL COMMENT '占基金净值比例（%）',
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_stock_fund_date`(`stock_code` ASC, `fund_code` ASC, `report_date` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 8414078 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '基金持仓明细表（来源：akshare stock_fund_stock_holder）' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for stock_hsgt_snapshot
-- ----------------------------
DROP TABLE IF EXISTS `stock_hsgt_snapshot`;
CREATE TABLE `stock_hsgt_snapshot`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `code` varchar(6) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '股票代码（6位）',
  `hold_date` date NULL DEFAULT NULL COMMENT '持股快照日期',
  `hold_shares` decimal(20, 4) NULL DEFAULT NULL COMMENT '持股数量',
  `hold_value` decimal(20, 4) NULL DEFAULT NULL COMMENT '持股数量（北向持股口径）',
  `hold_value_yuan` double NULL DEFAULT NULL COMMENT '持股市值（元）',
  `change_shares` decimal(20, 4) NULL DEFAULT NULL COMMENT '较上期持股变化数量',
  `hold_ratio` decimal(10, 4) NULL DEFAULT NULL COMMENT '占流通股本比例（%）',
  `fetched_at` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '数据获取时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_code_date`(`code` ASC, `hold_date` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 10 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '北向资金持股快照表（持股数量/市值/增持减持）' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for stock_income
-- ----------------------------
DROP TABLE IF EXISTS `stock_income`;
CREATE TABLE `stock_income`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `code` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '股票代码（不含市场标识，如：000001）',
  `report_date` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '报告期（如：20250331 表示一季报）',
  `report_type` tinyint NOT NULL COMMENT '报告类型：1-一季报 2-中报(半年报) 3-三季报 4-年报',
  `end_date` date NOT NULL COMMENT '报告截止日期',
  `total_revenue` decimal(20, 4) NULL DEFAULT NULL COMMENT '营业总收入（元）',
  `revenue` decimal(20, 4) NULL DEFAULT NULL COMMENT '营业收入（元）',
  `total_cost` decimal(20, 4) NULL DEFAULT NULL COMMENT '营业总成本（元）',
  `operating_cost` decimal(20, 4) NULL DEFAULT NULL COMMENT '营业成本（元）',
  `rd_expense` decimal(20, 4) NULL DEFAULT NULL COMMENT '研发费用（元）',
  `selling_expense` decimal(20, 4) NULL DEFAULT NULL COMMENT '销售费用（元）',
  `admin_expense` decimal(20, 4) NULL DEFAULT NULL COMMENT '管理费用（元）',
  `finance_expense` decimal(20, 4) NULL DEFAULT NULL COMMENT '财务费用（元）',
  `operating_profit` decimal(20, 4) NULL DEFAULT NULL COMMENT '营业利润（元）',
  `total_profit` decimal(20, 4) NULL DEFAULT NULL COMMENT '利润总额（元）',
  `income_tax` decimal(20, 4) NULL DEFAULT NULL COMMENT '所得税费用（元）',
  `net_profit` decimal(20, 4) NULL DEFAULT NULL COMMENT '净利润（元）',
  `net_profit_incl_minority` decimal(20, 4) NULL DEFAULT NULL COMMENT '净利润（含少数股东损益）（元）',
  `np_parent_company_owners` decimal(20, 4) NULL DEFAULT NULL COMMENT '归属母公司净利润（元）',
  `np_minority` decimal(20, 4) NULL DEFAULT NULL COMMENT '少数股东损益（元）',
  `eps_basic` decimal(14, 4) NULL DEFAULT NULL COMMENT '基本每股收益',
  `eps_diluted` decimal(14, 4) NULL DEFAULT NULL COMMENT '稀释每股收益',
  `other_comprehensive_income` decimal(20, 4) NULL DEFAULT NULL COMMENT '其他综合收益（元）',
  `total_comprehensive_income` decimal(20, 4) NULL DEFAULT NULL COMMENT '综合收益总额（元）',
  `non_recurring_gain` decimal(20, 4) NULL DEFAULT NULL COMMENT '非经常性损益（元）',
  `deducted_np_parent_company` decimal(20, 4) NULL DEFAULT NULL COMMENT '扣非归母净利润（元）',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `source` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_code_report`(`code` ASC, `report_date` ASC) USING BTREE,
  INDEX `idx_report_type`(`report_type` ASC) USING BTREE,
  INDEX `idx_end_date`(`end_date` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 3883312 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '利润表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for stock_info
-- ----------------------------
DROP TABLE IF EXISTS `stock_info`;
CREATE TABLE `stock_info`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `code` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '股票代码（不含市场标识，如：000001）',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '股票名称',
  `market` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '市场（SH/SZ/BJ）',
  `industry` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '所属行业',
  `list_date` date NULL DEFAULT NULL COMMENT '上市日期',
  `is_hs` tinyint NULL DEFAULT 0 COMMENT '是否沪深股通（0-否，1-是）',
  `is_st` tinyint NULL DEFAULT 0 COMMENT '是否ST（0-否，1-是）',
  `delist_date` date NULL DEFAULT NULL COMMENT '退市日期',
  `total_share` decimal(20, 2) NULL DEFAULT NULL COMMENT '总股本（股）',
  `float_share` decimal(20, 2) NULL DEFAULT NULL COMMENT '流通股本（股）',
  `total_market_cap` decimal(20, 2) NULL DEFAULT NULL COMMENT '总市值（元）',
  `float_market_cap` decimal(20, 2) NULL DEFAULT NULL COMMENT '流通市值（元）',
  `pe_ttm` decimal(10, 2) NULL DEFAULT NULL COMMENT '市盈率（TTM）',
  `pb` decimal(10, 2) NULL DEFAULT NULL COMMENT '市净率',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_code`(`code` ASC) USING BTREE,
  INDEX `idx_industry`(`industry` ASC) USING BTREE,
  INDEX `idx_market`(`market` ASC) USING BTREE,
  INDEX `idx_delist_date`(`delist_date` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 5491 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '股票基本信息表（名称/行业/总市值/流通市值等）' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for stock_institution_research
-- ----------------------------
DROP TABLE IF EXISTS `stock_institution_research`;
CREATE TABLE `stock_institution_research`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `code` varchar(6) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '股票代码（6位）',
  `report_date` date NULL DEFAULT NULL COMMENT '调研日期',
  `org_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '调研机构名称',
  `content_summary` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '调研内容摘要',
  `fetched_at` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '数据获取时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_code`(`code` ASC) USING BTREE,
  INDEX `idx_report_date`(`report_date` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 22 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '机构调研记录表（接待日期/调研机构/调研方式）' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for stock_news
-- ----------------------------
DROP TABLE IF EXISTS `stock_news`;
CREATE TABLE `stock_news`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `code` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '股票代码',
  `title` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '新闻标题',
  `content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '新闻正文摘要',
  `source` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '来源（如：东方财富、同花顺）',
  `publish_date` datetime NULL DEFAULT NULL COMMENT '发布时间',
  `news_type` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '类型：正面/负面/中性',
  `sentiment_score` decimal(5, 4) NULL DEFAULT NULL COMMENT '情感评分 -1~1',
  `event_tag` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '事件标签（印度高温/Q1业绩/建厂等）',
  `url` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '原文链接',
  `fetched_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_code_title_date`(`code` ASC, `title`(200) ASC, `publish_date` ASC) USING BTREE,
  INDEX `idx_code`(`code` ASC) USING BTREE,
  INDEX `idx_publish_date`(`publish_date` ASC) USING BTREE,
  INDEX `idx_event_tag`(`event_tag` ASC) USING BTREE,
  INDEX `idx_news_type`(`news_type` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1059391 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '新闻表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for stock_recommendation
-- ----------------------------
DROP TABLE IF EXISTS `stock_recommendation`;
CREATE TABLE `stock_recommendation`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `strategy_id` bigint NULL DEFAULT NULL COMMENT '策略ID，关联strategy_definition表',
  `stock_code` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '股票代码（纯代码，无后缀）',
  `stock_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '股票名称',
  `recommend_date` date NOT NULL COMMENT '推荐日期',
  `rank_num` int NOT NULL COMMENT '排名（从1开始）',
  `factor_score` double NULL DEFAULT NULL COMMENT '因子综合得分（百分位 0~1）',
  `analysis_score` int NULL DEFAULT NULL COMMENT '个股分析得分（0~109）',
  `analysis_score_pct` double NULL DEFAULT NULL COMMENT '个股分析得分百分位（0~1）',
  `final_score` double NOT NULL COMMENT '融合最终得分（0~1）',
  `factor_weight` double NULL DEFAULT NULL COMMENT '因子得分融合权重',
  `analysis_weight` double NULL DEFAULT NULL COMMENT '分析得分融合权重',
  `regime` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'SIDEWAYS' COMMENT '市场环境: BULL/BEAR/SIDEWAYS',
  `index_ma20` double NULL DEFAULT NULL COMMENT '沪深300 MA20',
  `index_ma60` double NULL DEFAULT NULL COMMENT '沪深300 MA60',
  `index_close` double NULL DEFAULT NULL COMMENT '沪深300收盘价',
  `industry` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '行业',
  `market_cap` double NULL DEFAULT NULL COMMENT '总市值（元）',
  `close_price` double NULL DEFAULT NULL COMMENT '当日收盘价',
  `suggested_buy_price` double NULL DEFAULT NULL COMMENT '推荐买入价(MA20支撑位)',
  `suggested_stop_loss` double NULL DEFAULT NULL COMMENT '建议止损价(ATR 2倍)',
  `suggested_take_profit` double NULL DEFAULT NULL COMMENT '建议止盈价(R:R=1:2)',
  `suggested_target_price` double NULL DEFAULT NULL COMMENT '建议目标价(R:R=1:3)',
  `suggested_position_pct` double NULL DEFAULT NULL COMMENT '建议仓位比例(0~1)',
  `change_percent` double NULL DEFAULT NULL COMMENT '当日涨跌幅%',
  `industry_momentum` double NULL DEFAULT NULL COMMENT '行业相对强度(z-score)',
  `industry_regime` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '行业Regime: BULL/BEAR/SIDEWAYS',
  `technical_score` int NULL DEFAULT NULL COMMENT '技术面得分',
  `capital_score` int NULL DEFAULT NULL COMMENT '资金面得分',
  `event_score` int NULL DEFAULT NULL COMMENT '事件面得分',
  `fundamental_score` int NULL DEFAULT NULL COMMENT '基本面得分',
  `action_tag` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '操作建议: BUY/HOLD/SELL',
  `buy_reason` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '买入理由摘要',
  `risk_score` int NULL DEFAULT 0 COMMENT '风险评分（0-15分）',
  `liquidity_score` int NULL DEFAULT 0 COMMENT '流动性评分（0-10分）',
  `next_day_return` double NULL DEFAULT NULL COMMENT '次日收益率%',
  `next_day_excess_return` double NULL DEFAULT NULL COMMENT '次日超额收益率%（vs沪深300）',
  `next_week_return` double NULL DEFAULT NULL COMMENT '一周收益率%',
  `next_week_excess_return` double NULL DEFAULT NULL COMMENT '一周超额收益率%（vs沪深300）',
  `next_month_return` double NULL DEFAULT NULL COMMENT '一月收益率%',
  `next_month_excess_return` double NULL DEFAULT NULL COMMENT '一月超额收益率%（vs沪深300）',
  `tracking_updated_at` datetime NULL DEFAULT NULL COMMENT '追踪更新时间',
  `factor_ranks_json` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '各因子百分位排名 JSON',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `style_regime` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '风格维度: GROWTH/VALUE/NEUTRAL',
  `weight_mode` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '权重模式: FIXED/ICW',
  `size_regime` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '大小盘维度: LARGE/SMALL/NEUTRAL',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_strategy_date_stock_mode`(`strategy_id` ASC, `recommend_date` ASC, `stock_code` ASC, `weight_mode` ASC) USING BTREE,
  INDEX `idx_recommend_date`(`recommend_date` ASC) USING BTREE,
  INDEX `idx_stock_code`(`stock_code` ASC) USING BTREE,
  INDEX `idx_stock_rec_strategy_id`(`strategy_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 30541 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '股票推荐表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for stock_research_report
-- ----------------------------
DROP TABLE IF EXISTS `stock_research_report`;
CREATE TABLE `stock_research_report`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `code` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '股票代码',
  `name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '股票名称',
  `report_title` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '报告标题',
  `rating` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '东财评级：买入/增持/中性/减持/卖出',
  `institution` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '机构名称',
  `industry` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '行业',
  `eps_forecast` json NULL COMMENT 'EPS预测JSON',
  `report_date` date NULL DEFAULT NULL COMMENT '报告发布日期',
  `pdf_url` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '报告PDF链接',
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_code_pdf_url`(`code` ASC, `pdf_url`(255) ASC) USING BTREE,
  INDEX `idx_code`(`code` ASC) USING BTREE,
  INDEX `idx_report_date`(`report_date` ASC) USING BTREE,
  INDEX `idx_institution`(`institution`(50) ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 58892 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '研报表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for stock_sentiment_activity
-- ----------------------------
DROP TABLE IF EXISTS `stock_sentiment_activity`;
CREATE TABLE `stock_sentiment_activity`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `trade_date` date NOT NULL COMMENT '交易日期',
  `up_count` int NULL DEFAULT NULL COMMENT '上涨家数',
  `zt_count` int NULL DEFAULT NULL COMMENT '涨停家数',
  `zt_real_count` int NULL DEFAULT NULL COMMENT '真实涨停家数',
  `zt_st_count` int NULL DEFAULT NULL COMMENT 'ST涨停家数',
  `down_count` int NULL DEFAULT NULL COMMENT '下跌家数',
  `dt_count` int NULL DEFAULT NULL COMMENT '跌停家数',
  `dt_real_count` int NULL DEFAULT NULL COMMENT '真实跌停家数',
  `dt_st_count` int NULL DEFAULT NULL COMMENT 'ST跌停家数',
  `flat_count` int NULL DEFAULT NULL COMMENT '平盘家数',
  `suspended_count` int NULL DEFAULT NULL COMMENT '停牌家数',
  `activity_ratio` decimal(10, 4) NULL DEFAULT NULL COMMENT '活跃度(%)',
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_activity_date`(`trade_date` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 108 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '市场活跃度情绪指标表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for stock_sentiment_block_trade
-- ----------------------------
DROP TABLE IF EXISTS `stock_sentiment_block_trade`;
CREATE TABLE `stock_sentiment_block_trade`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `seq_no` int NOT NULL DEFAULT 1 COMMENT '序号',
  `trade_date` date NOT NULL COMMENT '交易日期',
  `code` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '证券代码',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '证券简称',
  `price` decimal(10, 2) NULL DEFAULT NULL COMMENT '成交价(元)',
  `volume` decimal(20, 2) NULL DEFAULT NULL COMMENT '成交量(股)',
  `amount` decimal(20, 2) NULL DEFAULT NULL COMMENT '成交额(元)',
  `discount_rate` decimal(10, 6) NULL DEFAULT NULL COMMENT '折溢率',
  `trade_count` int NULL DEFAULT NULL COMMENT '成交笔数',
  `change_pct` decimal(10, 4) NULL DEFAULT NULL COMMENT '涨跌幅%',
  `close_price` decimal(10, 4) NULL DEFAULT NULL COMMENT '收盘价',
  `pct_of_float` decimal(10, 6) NULL DEFAULT NULL COMMENT '成交总额/流通市值',
  `buy_branch` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '买方营业部',
  `sell_branch` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '卖方营业部',
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_block_trade`(`code` ASC, `trade_date` ASC, `price` ASC, `volume` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 28777 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '大宗交易表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for stock_sentiment_lhb
-- ----------------------------
DROP TABLE IF EXISTS `stock_sentiment_lhb`;
CREATE TABLE `stock_sentiment_lhb`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `code` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '股票代码',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '股票名称',
  `trade_date` date NOT NULL COMMENT '上榜日',
  `close` decimal(10, 2) NULL DEFAULT NULL COMMENT '收盘价(元)',
  `pct_change` decimal(10, 2) NULL DEFAULT NULL COMMENT '涨跌幅(%)',
  `net_amount` decimal(20, 2) NULL DEFAULT NULL COMMENT '龙虎榜净买额(元)',
  `buy_amount` decimal(20, 2) NULL DEFAULT NULL COMMENT '龙虎榜买入额(元)',
  `sell_amount` decimal(20, 2) NULL DEFAULT NULL COMMENT '龙虎榜卖出额(元)',
  `total_amount` decimal(20, 2) NULL DEFAULT NULL COMMENT '龙虎榜成交额(元)',
  `market_amount` decimal(20, 2) NULL DEFAULT NULL COMMENT '市场总成交额(元)',
  `net_ratio` decimal(10, 4) NULL DEFAULT NULL COMMENT '净买额占总成交比',
  `amount_ratio` decimal(10, 4) NULL DEFAULT NULL COMMENT '成交额占总成交比',
  `turnover` decimal(10, 4) NULL DEFAULT NULL COMMENT '换手率(%)',
  `float_mv` decimal(20, 2) NULL DEFAULT NULL COMMENT '流通市值(元)',
  `reason` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '上榜原因',
  `after_1d` decimal(10, 2) NULL DEFAULT NULL COMMENT '上榜后1日涨跌幅(%)',
  `after_2d` decimal(10, 2) NULL DEFAULT NULL COMMENT '上榜后2日涨跌幅(%)',
  `after_5d` decimal(10, 2) NULL DEFAULT NULL COMMENT '上榜后5日涨跌幅(%)',
  `after_10d` decimal(10, 2) NULL DEFAULT NULL COMMENT '上榜后10日涨跌幅(%)',
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_lhb`(`code` ASC, `trade_date` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 7622 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '龙虎榜情绪数据表（上榜营业部买卖统计）' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for stock_sentiment_lhb_inst
-- ----------------------------
DROP TABLE IF EXISTS `stock_sentiment_lhb_inst`;
CREATE TABLE `stock_sentiment_lhb_inst`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `code` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '股票代码',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '股票名称',
  `trade_date` date NOT NULL COMMENT '上榜日期',
  `close` decimal(10, 2) NULL DEFAULT NULL COMMENT '收盘价(元)',
  `pct_change` decimal(10, 2) NULL DEFAULT NULL COMMENT '涨跌幅(%)',
  `buy_inst_cnt` int NULL DEFAULT NULL COMMENT '买方机构数',
  `sell_inst_cnt` int NULL DEFAULT NULL COMMENT '卖方机构数',
  `buy_inst_amt` decimal(20, 2) NULL DEFAULT NULL COMMENT '机构买入总额(元)',
  `sell_inst_amt` decimal(20, 2) NULL DEFAULT NULL COMMENT '机构卖出总额(元)',
  `net_inst_amt` decimal(20, 2) NULL DEFAULT NULL COMMENT '机构净买额(元)',
  `market_amount` decimal(20, 2) NULL DEFAULT NULL COMMENT '市场总成交额(元)',
  `net_inst_ratio` decimal(10, 4) NULL DEFAULT NULL COMMENT '机构净买额占总成交额比',
  `turnover` decimal(10, 4) NULL DEFAULT NULL COMMENT '换手率(%)',
  `float_mv` decimal(20, 2) NULL DEFAULT NULL COMMENT '流通市值(元)',
  `reason` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '上榜原因',
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_lhb_inst`(`code` ASC, `trade_date` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 4511 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '龙虎榜机构买卖数据表（机构席位净买卖统计）' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for stock_sentiment_margin
-- ----------------------------
DROP TABLE IF EXISTS `stock_sentiment_margin`;
CREATE TABLE `stock_sentiment_margin`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `trade_date` date NOT NULL COMMENT '信用交易日期',
  `margin_balance` decimal(20, 2) NULL DEFAULT NULL COMMENT '融资余额(元)',
  `margin_buy` decimal(20, 2) NULL DEFAULT NULL COMMENT '融资买入额(元)',
  `short_balance_vol` decimal(20, 2) NULL DEFAULT NULL COMMENT '融券余量(股)',
  `short_balance_amt` decimal(20, 2) NULL DEFAULT NULL COMMENT '融券余额(元)',
  `short_sell_vol` decimal(20, 2) NULL DEFAULT NULL COMMENT '融券卖出量(股)',
  `margin_short_bal` decimal(20, 2) NULL DEFAULT NULL COMMENT '融资融券余额(元)',
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_margin_date`(`trade_date` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 405 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '融资融券汇总表（每日融资余额/融券余额）' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for stock_sentiment_margin_detail
-- ----------------------------
DROP TABLE IF EXISTS `stock_sentiment_margin_detail`;
CREATE TABLE `stock_sentiment_margin_detail`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `trade_date` date NOT NULL COMMENT '信用交易日期',
  `code` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '标的证券代码',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '标的证券简称',
  `margin_balance` decimal(20, 2) NULL DEFAULT NULL COMMENT '融资余额(元)',
  `margin_buy` decimal(20, 2) NULL DEFAULT NULL COMMENT '融资买入额(元)',
  `margin_repay` decimal(20, 2) NULL DEFAULT NULL COMMENT '融资偿还额(元)',
  `short_balance_vol` decimal(20, 2) NULL DEFAULT NULL COMMENT '融券余量(股)',
  `short_sell_vol` decimal(20, 2) NULL DEFAULT NULL COMMENT '融券卖出量(股)',
  `short_repay_vol` decimal(20, 2) NULL DEFAULT NULL COMMENT '融券偿还量(股)',
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_margin_detail`(`code` ASC, `trade_date` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 834398 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '融资融券明细表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for stock_sentiment_moneyflow
-- ----------------------------
DROP TABLE IF EXISTS `stock_sentiment_moneyflow`;
CREATE TABLE `stock_sentiment_moneyflow`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `ts_code` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '股票代码(纯数字)',
  `trade_date` date NOT NULL COMMENT '交易日期',
  `code` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '股票代码(同ts_code)',
  `close` decimal(10, 2) NULL DEFAULT NULL COMMENT '收盘价(元)',
  `pct_change` decimal(10, 2) NULL DEFAULT NULL COMMENT '涨跌幅(%)',
  `net_main` decimal(20, 2) NULL DEFAULT NULL COMMENT '主力净流入(元,涨停池代理)',
  `net_main_pct` decimal(10, 4) NULL DEFAULT NULL COMMENT '主力净流入占比(%)',
  `net_huge` decimal(20, 2) NULL DEFAULT NULL COMMENT '超大单净流入(元,预留)',
  `net_big` decimal(20, 2) NULL DEFAULT NULL COMMENT '大单净流入(元,预留)',
  `net_medium` decimal(20, 2) NULL DEFAULT NULL COMMENT '中单净流入(元,代理指标)',
  `net_small` decimal(20, 2) NULL DEFAULT NULL COMMENT '小单净流入(元,代理指标)',
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_code_date`(`code` ASC, `trade_date` ASC) USING BTREE,
  INDEX `idx_trade_date`(`trade_date` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 977304 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '个股资金流向情绪表（东方财富真实资金流，120天历史）' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for stock_sentiment_notice
-- ----------------------------
DROP TABLE IF EXISTS `stock_sentiment_notice`;
CREATE TABLE `stock_sentiment_notice`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `ts_code` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '股票代码(纯数字)',
  `code` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '股票代码',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '股票名称',
  `notice_type` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '公告类型',
  `notice_date` date NOT NULL COMMENT '公告日期',
  `title` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '公告标题',
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_code_date_type`(`code` ASC, `notice_date` ASC, `notice_type` ASC) USING BTREE,
  INDEX `idx_notice_date`(`notice_date` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 303879 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '个股公告事件情绪表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for stock_sentiment_survey
-- ----------------------------
DROP TABLE IF EXISTS `stock_sentiment_survey`;
CREATE TABLE `stock_sentiment_survey`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `code` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '股票代码',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '股票名称',
  `price` decimal(10, 2) NULL DEFAULT NULL COMMENT '最新价(元)',
  `pct_change` decimal(10, 2) NULL DEFAULT NULL COMMENT '涨跌幅(%)',
  `inst_count` int NULL DEFAULT NULL COMMENT '接待机构数量',
  `meeting_type` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '接待方式',
  `staff` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '接待人员',
  `location` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '接待地点',
  `meeting_date` date NOT NULL COMMENT '接待日期',
  `notice_date` date NOT NULL COMMENT '公告日期',
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_survey`(`code` ASC, `meeting_date` ASC, `notice_date` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 51410 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '机构调研事件情绪表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for stock_sentiment_zt
-- ----------------------------
DROP TABLE IF EXISTS `stock_sentiment_zt`;
CREATE TABLE `stock_sentiment_zt`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `ts_code` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '股票代码(纯数字)',
  `trade_date` date NOT NULL COMMENT '交易日期',
  `code` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '股票代码(同ts_code)',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '股票名称',
  `close` decimal(10, 2) NULL DEFAULT NULL COMMENT '收盘价(元)',
  `pct_change` decimal(10, 2) NULL DEFAULT NULL COMMENT '涨跌幅(%)',
  `zt_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '类型(zt涨停/dt跌停/zbgc炸板)',
  `reason` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '入选理由/所属行业',
  `is_new` tinyint NULL DEFAULT NULL COMMENT '是否新涨停(1是/0否)',
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_code_date_type`(`code` ASC, `trade_date` ASC, `zt_type` ASC) USING BTREE,
  INDEX `idx_trade_date`(`trade_date` ASC) USING BTREE,
  INDEX `idx_zt_type`(`zt_type` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 32204 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '涨跌停情绪数据表（涨停家数/跌停家数/炸板率）' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for stock_shareholder
-- ----------------------------
DROP TABLE IF EXISTS `stock_shareholder`;
CREATE TABLE `stock_shareholder`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `code` varchar(6) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '股票代码（6位）',
  `report_date` date NULL DEFAULT NULL COMMENT '报告截止日期',
  `holder_count` bigint NULL DEFAULT NULL COMMENT '股东户数',
  `avg_shares` decimal(20, 4) NULL DEFAULT NULL COMMENT '户均持股数',
  `change_pct` decimal(10, 4) NULL DEFAULT NULL COMMENT '较上期增减比例（%）',
  `change_count` bigint NULL DEFAULT NULL COMMENT '较上期股东户数变化',
  `fetched_at` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '数据获取时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_code_date`(`code` ASC, `report_date` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 10873 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '股东人数变化表（户数/户均持股/增减比例）' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for stock_valuation_snapshot
-- ----------------------------
DROP TABLE IF EXISTS `stock_valuation_snapshot`;
CREATE TABLE `stock_valuation_snapshot`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `code` varchar(6) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '股票代码（6位）',
  `snapshot_date` date NULL DEFAULT NULL COMMENT '快照日期',
  `pe_current` decimal(20, 4) NULL DEFAULT NULL COMMENT '当前滚动PE',
  `pb_current` decimal(20, 4) NULL DEFAULT NULL COMMENT '当前PB',
  `pe_percentile_3y` decimal(10, 4) NULL DEFAULT NULL COMMENT 'PE三年历史分位数（0~100）',
  `pb_percentile_3y` decimal(10, 4) NULL DEFAULT NULL COMMENT 'PB三年历史分位数（0~100）',
  `pe_hist_count` int NULL DEFAULT NULL COMMENT 'PE历史数据点数量',
  `pb_hist_count` int NULL DEFAULT NULL COMMENT 'PB历史数据点数量',
  `fetched_at` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '数据获取时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_code_date`(`code` ASC, `snapshot_date` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 16 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '估值快照表（PE/PB当前值及历史分位数）' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for strategy_confidence
-- ----------------------------
DROP TABLE IF EXISTS `strategy_confidence`;
CREATE TABLE `strategy_confidence`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `strategy_id` bigint NOT NULL COMMENT '策略ID',
  `weight_mode` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT 'ICW' COMMENT '权重模式: ICW/STATIC',
  `level` varchar(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'UNTRAINED' COMMENT '等级: HIGH/NORMAL/LOW/SUSPENDED/UNTRAINED',
  `score` int NULL DEFAULT NULL COMMENT '综合置信度分 (0~100, NULL=未训练)',
  `hit_rate_score` int NULL DEFAULT NULL COMMENT '近10期命中率维度得分 (0~40)',
  `hit_rate_value` decimal(6, 4) NULL DEFAULT NULL COMMENT '近10期实际命中率 (0~1)',
  `return_score` int NULL DEFAULT NULL COMMENT '平均收益率维度得分 (0~25)',
  `avg_return_value` decimal(10, 4) NULL DEFAULT NULL COMMENT '近10期平均收益率%',
  `drawdown_score` int NULL DEFAULT NULL COMMENT '最大回撤维度得分 (0~20)',
  `max_drawdown_value` decimal(8, 4) NULL DEFAULT NULL COMMENT '近10期最大单日跌幅%',
  `volatility_score` int NULL DEFAULT NULL COMMENT '波动率稳定性维度得分 (0~15)',
  `volatility_value` decimal(8, 4) NULL DEFAULT NULL COMMENT '近10期收益标准差%',
  `sample_size` int NULL DEFAULT 0 COMMENT '用于计算的推荐记录数',
  `data_as_of_date` date NULL DEFAULT NULL COMMENT '数据截止日期',
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_strategy_date`(`strategy_id` ASC, `data_as_of_date` ASC) USING BTREE,
  INDEX `idx_strategy_id`(`strategy_id` ASC) USING BTREE,
  INDEX `idx_level`(`level` ASC) USING BTREE,
  INDEX `idx_confidence_strategy_mode`(`strategy_id` ASC, `weight_mode` ASC, `data_as_of_date` DESC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 926 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '策略置信度 - 基于历史追踪表现的风控评分' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for strategy_definition
-- ----------------------------
DROP TABLE IF EXISTS `strategy_definition`;
CREATE TABLE `strategy_definition`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `strategy_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '策略唯一代码',
  `strategy_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '策略名称',
  `strategy_type` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '策略类型: FACTOR_LONG/LONG_SHORT/MARKET_NEUTRAL/MOMENTUM/MEAN_REVERSION/CUSTOM',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'DRAFT' COMMENT '状态: DRAFT/TESTING/ACTIVE/DEPRECATED',
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '策略描述',
  `rebalance_frequency` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '调仓频率: DAILY/WEEKLY/MONTHLY',
  `max_position_count` int NULL DEFAULT NULL COMMENT '最大持仓数量',
  `position_size_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '仓位大小类型: EQUAL/FACTOR_WEIGHTED/CUSTOM',
  `factor_config_json` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '因子配置 JSON',
  `filter_config_json` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '选股过滤配置 JSON',
  `script_code` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT 'Groovy 策略脚本',
  `version` int NULL DEFAULT 1 COMMENT '版本号',
  `author` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'system' COMMENT '创建者',
  `stop_loss_pct` decimal(10, 4) NULL DEFAULT NULL COMMENT '止损比例(%)',
  `stop_profit_pct` decimal(10, 4) NULL DEFAULT NULL COMMENT '止盈比例(%)',
  `max_drawdown_pct` decimal(10, 4) NULL DEFAULT NULL COMMENT '最大回撤限制(%)',
  `created_at` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `updated_at` datetime NULL DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_strategy_code`(`strategy_code` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 90 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '策略定义表（量化策略参数和配置）' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for trade_calendar
-- ----------------------------
DROP TABLE IF EXISTS `trade_calendar`;
CREATE TABLE `trade_calendar`  (
  `trade_date` date NOT NULL COMMENT '交易日期',
  `is_trading` tinyint(1) NOT NULL COMMENT '是否为交易日(1=是/0=否)',
  `reason` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '非交易日原因(如端午节、国庆等)',
  `source` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'AUTO' COMMENT '数据来源(AUTO=自动/MANUAL=手动)',
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`trade_date`) USING BTREE,
  INDEX `idx_is_trading`(`is_trading` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

SET FOREIGN_KEY_CHECKS = 1;
