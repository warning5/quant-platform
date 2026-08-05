# 代码质量修复任务清单（基于 5 张审查截图）

> 生成时间：2026-08-05 19:18 ｜ 依据：对代码库的实际扫描（非臆测）
> 优先级：P0 数据正确性/安全（必做） > P1 代码质量/可维护性（建议） > P2 可选加固
> 工作量标注：S≈0.5d，M≈1–3d，L≈>3d

---

## P0 — 数据正确性与安全（必做，影响结果可信度）

| ID | 问题 | 精确位置 | 修改任务 | 工作量 |
|---|---|---|---|---|
| **T-DATA-1** | OHLCV 写入前**无任何范围/交叉校验**（无 `low≤close≤high`、`open∈[low,high]`、`volume≥0`、`price>0` 检查；全脚本搜索 `low<=close`/`close<=high` 等 pattern = 0 命中） | `field_completer.py` 写入路径缺校验；`cross_validate_ohlcv`(L506) 仅是**独立诊断函数**，非写入前闸门 | 在写入 `stock_daily` 前加 sanity check（OHLC 自洽 + 非负 + 价格>0），异常值告警/丢弃 | M |
| **T-DATA-2** | 跳变检测仅覆盖 PE/PB 两指标，无价格/成交量跳变检测 | `field_completer.py` L419–468（"P1-3.1 合理性校验"仅 PE/PB） | 扩展跳变检测至 `close`/`volume`，阈值抽成常量可配 | S |
| **T-DATA-3** | 交叉验证仅抽样 50 只（`sample_size=50`），非全量/关键字段全检 | `field_completer.py` L506 `cross_validate_ohlcv(db, sample_size=50, ...)` | 提高抽样比例或关键字段全量校验，并纳入定期任务/CI | S–M |
| **T-BUG-1** | 复权因子缺陷 BT-01/BT-02（`processDividendEvents` / `updateAdjFactors` 分红复权）影响回测准确性 | `BacktestUtils.java` 注释自标 KNOWN-ISSUE | ⚠️ **决策项**：项目 MEMORY 当前策略为"故意不修"，需你确认是否现在修复 | 决策 |

---

## P1 — 代码质量 / 可维护性（建议做）

| ID | 问题 | 精确位置 | 修改任务 | 工作量 |
|---|---|---|---|---|
| **T-DUP-1** | `LIMIT 200` 魔法值 ×5 | `BacktestResourceOptionProvider.java:30`、`StrategyResourceOptionProvider.java:30`、`PaperTradingResourceOptionProvider.java:30`、`FactorResourceOptionProvider.java:30`、`FactorService.java:560`(`.limit(200)`) | 提取为常量或 `@Value` 配置项（如 `quant.ui.option-limit`） | S |
| **T-DUP-2** | `INDEX_NAME_MAP` 双定义（内容相似、字段略有差异） | `MonitorQuoteClient.java:31`、`MarketDataService.java:59` | 抽公共常量类 `IndexNameConstants`，两处复用 | S |
| **T-DUP-3** | Python 脚本硬编码 IP | `db_config.py:82`(`172.19.72.140`)、`monitor_track.py:46`(`127.0.0.1`)、`update_news_data.py:198`(`127.0.0.1`) | 统一从环境变量/配置读，去除硬编码 | S |
| **T-FE-1** | 前端 44 个文件各自 `useState(loading)`，无全局 loading 拦截器 | `frontend/src` 全局（44 文件独立 loading） | 抽 `useRequest` hook 或 axios 拦截器 + 全局 `Spin` | M |
| **T-FE-2** | 前端 57 处 `pagination={false}`（无分页大表） | `frontend/src` 全局（57 处） | 评估：低频表保留并加注释，高频/大数据量表加分页 | M |

---

## P2 — 可选 / 安全加固

| ID | 问题 | 精确位置 | 修改任务 | 工作量 |
|---|---|---|---|---|
| **T-SEC-1** | `.env` 明文密钥（`CREDENTIAL_AES_KEY` / `EASTMONEY_TOKEN`）— 已 gitignore，但仍明文落盘 | `stock-service/src/main/resources/.env:43,30` | 密钥管理（KMS/启动注入）+ 轮换密钥 | M |
| **T-SEC-2** | docker-compose 默认弱密码 `quant123` / `root123` | `docker-compose.yml:17,41,44` | 改强密码 + 文档说明 | S |
| **T-SEC-3** | application-dev.yml 默认弱密码 `123456`（MySQL/ClickHouse） | `application-dev.yml:10,18` | 同上 | S |
| **T-FE-3** | 0 TS 文件 / 0 PropTypes，类型安全缺失 | `frontend/src`（无 `.ts/.tsx`、无 PropTypes） | 渐进迁移 TS（大工作量，低优先级） | L |
| **T-FE-4** | `useMemo` 列宽重复计算 | `frontend/src` | 抽 `useColumnWidth` hook | S |
| **T-FE-5** | 无移动端适配（无 `isMobile` / `innerWidth` 检查） | `frontend/src` | 响应式改造（antd Grid / 媒体查询） | M |

---

## 已闭环（Phase 1–4 已修复，无需再列）

- ✅ **空吞异常**（截图四 #3 后端部分）→ Phase 3 已给全仓 ~49 处空 `catch` 补 `log.error`
- ✅ **Controller catch 模板 71 处** → Phase 2 删 `StockAnalysisController` 32 处，`internalServerError().body()` 全仓搜索 = 0 命中；现仅剩 HTML 报告端点 1 处（设计如此，返回 HTML 非 ApiResponse，保留）

---

## 建议执行顺序

```
T-BUG-1(决策) → T-DATA-1 → T-DATA-2/3 → T-DUP-1/2/3 → T-FE-1/2 → T-SEC-2/3 → T-SEC-1 → T-FE-3/4/5
```

- **本批次建议先落 P0 的 T-DATA-1/2/3**（数据完整性，直接影响回测/因子结论可信度），T-BUG-1 需你拍板。
- P1 的 DUP 三项（S 级）可顺手批量清掉，收益高、风险低。
- T-FE-3（TS 迁移）是长线工程，不建议一次性铺开。
