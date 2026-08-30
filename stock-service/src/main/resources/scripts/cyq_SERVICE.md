# CYQ 筹码分布服务 接入说明

通用筹码分布(CYQ)计算服务：**Baostock 未复权日线 → 对齐东财算法 → 落 ClickHouse `stock.stock_cyq`**。

## 文件
- `cyq_core.py` — 核心模块：换手率量纲归一、CYQ 算法(150桶, 对齐东财)、指标、Baostock 拉取、CH 写入。
- `cyq_service.py` — 编排服务：批量/断点续传/增量刷新/并发守卫/容错。
- `stock_cyq_ddl.sql` — 建表 DDL 文档版（服务已内嵌同名建表语句，无需手动执行）。

## 已验证结论
- 算法与东财 `stock_cyq_em` 逐位一致（`dbg_cyq.py`: `max|Δ|=0.000000`）。
- 换手率量纲自动归一（`normalize_turnover`：小数→百分比），杜绝口径错误。
- 增量续算 `compute_cyq_continue` 与全量重算结果一致（`max|Δx|=0`）。
- 沙箱可连 Baostock 与 CH，东财接口被封（沙箱固定出口 IP），故用 Baostock 未复权替代。

## 运行
```bash
# 建表（仅一次）
python cyq_service.py --create-table

# 指定股票
python cyq_service.py --codes 002080,300200,300377

# 试运行前 50 只（从 CH stock_info 取清单）
python cyq_service.py --limit 50

# 全市场（生产，建议后台/定时任务，5490 只）
python cyq_service.py

# 全量重算（忽略进度）
python cyq_service.py --force

# 忽略本地进度、仅按 CH 快照判断是否最新
python cyq_service.py --no-resume
```

## 配置
环境变量（默认已对齐本项目 docker-compose）：`CH_HOST`(172.19.72.140) / `CH_PORT`(8123) / `CH_DB`(stock) / `CH_USER`(default) / `CH_PW`(123456)。

## 落库表 `stock.stock_cyq`
| 列 | 含义 |
|---|---|
| code | 6位代码 |
| trade_date | 快照日 |
| avg_cost | 平均成本 |
| benefit | 获利比例(0~1) |
| c90_lo/c90_hi/c90_conc | 90%成本区间+集中度 |
| c70_lo/c70_hi/c70_conc | 70%成本区间+集中度 |
| cyq_json | 全量分布 {yrange:[...], x:[...]} |
| updated_at | 更新时间(ReplacingMergeTree 版本键) |

## 定时刷新建议
每日收盘后跑一次 `python cyq_service.py`（默认从 progress 跳已完成、对已有快照仅增量续算新交易日，无新数据则跳过）。断点续传：崩溃重跑自动跳过已完成；并发守卫单进程。

## 接入前端
- 明细分布：`SELECT cyq_json FROM stock.stock_cyq WHERE code=? ORDER BY updated_at DESC LIMIT 1`。
- 概览指标：直接读 avg_cost/benefit/c90_*/c70_* 列。
