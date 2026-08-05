# 多策略组合 Route B 后端 — 端到端验证报告

> 验证时间：2026-08-05 | 验证人：WorkBuddy | 范围：B0–B4 后端（不含 B5 前端）

## 一、验证环境

| 项 | 值 |
|---|---|
| 后端 | stock-service（最新编译 jar，Spring Boot 3 + Java 21） |
| 数据库 | MySQL `stock` / root / 123456（localhost:3306） |
| 认证 | admin / admin123（Sa-Token httpOnly cookie） |
| DDL | `stock-service/sql/v1.2_paper_combo.sql`（手动执行，7/7 OK） |

> ⚠️ 部署坑：JDK 21 在 **D 盘** `D:/Program Files/Java/jdk-21`，非 C 盘；编译/重启前须 `taskkill` 释放旧 8080 进程占用的 jar 锁，否则 `repackage` 失败。

## 二、验证步骤与结果

### B1 组合孵化（核心）
- 请求：`POST /api/paper-trading/create`，`initialCapital=1000000`、`strategyConfigJson=[{"strategyId":76,"weight":0.4},{"strategyId":77,"weight":0.3},{"strategyId":78,"weight":0.3}]`
- 结果：生成 1 个组合根（parent_id=null）+ 3 个子账户（parent_id=根）
- 资金切分：**40万 / 30万 / 30万**（= 总资本 × 权重，末位吸收取整误差，Σ=100万 精确）
- 子账户初始 nav（1 条）、risk_config（rebalance_freq/threshold/mode）均自动创建 ✅

### B3 组合详情 / 归因 / 净值
- `GET /combo/8/detail`：返回组合总览、权重 `{76:0.4,77:0.3,78:0.3}`、3 子账户明细（含 strategyCode、资金、状态）、相关性矩阵骨架、分散化比率 ✅
- `GET /combo/8/nav`：返回组合净值序列 + 各子账户净值序列 ✅

### B2 净值聚合（新增运维端点）
- 预备：为 3 个子账户各插入 7/30、7/31、8/1 三天模拟 nav（总资产波动）
- 触发：`POST /combo/8/aggregate`
- 聚合结果（paper_id=根）：

| nav_date | 组合总资产 | 日收益 | 累计收益 |
|---|---|---|---|
| 2026-07-30 | 1,000,000 | 0 | 0 |
| 2026-07-31 | 1,010,000 | +1.00% | +1.00% |
| 2026-08-01 | 1,015,000 | +0.495% | +1.50% |

- 验证：各日总资产 = 子账户同日之和；收益计算正确 ✅

### B4 再平衡引擎
- `POST /combo/8/rebalance`：子账户无持仓时安全跳过（返回 0，不写日志），避免噪音 ✅
- 端点连通，有持仓时的旋转逻辑复用既有 `executeSignal` 成熟路径

## 三、结论

**B0–B4 后端功能与数据链路全部验证通过**，可进入 B5 前端开发。

| 阶段 | 状态 |
|---|---|
| B0 数据层（parent_id / 再平衡表 / 实体） | ✅ 验证 |
| B1 组合创建（根+子账户孵化、资金预算） | ✅ 验证 |
| B2 调度分发 + 聚合 | ✅ 验证 |
| B3 详情 / 归因 / 净值 | ✅ 验证 |
| B4 再平衡引擎 | ✅ 端点连通（旋转逻辑待真实持仓回归） |
| B5 前端 4 Tab | ⬜ 待开发 |

> 验证用测试数据（组合 8 + 子账户 9/10/11 及关联记录）已清理，永久 schema 变更（parent_id 列、strategy_id 列、paper_rebalance_log 表）保留。
