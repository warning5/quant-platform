# 架构说明：ICW 权重与 regime 日历

> 本文档面向开发与维护者，说明 2026-07-25 「SIDEWAYS 退步修复」涉及的两块核心机制：
> 1. `market_regime_calendar` 环境体制日历表
> 2. ICW（IC 加权）权重如何按 regime 分别算 IC
> 配套优化：① ICW按regime分别算IC + ③ EARNINGS_SURPRISE 白名单 SIDEWAYS 守卫 + ④ 连续 BEAR 暂停。
> 运营侧观察与评估见 `策略73上线观察计划_2026-07-25.md`；根因分析见 `SIDEWAYS退步归因分析_2026-07-25.md`。

---

## 1. 背景：为什么要 regime 维度

原 ICW 权重用的 IC 是"最近 60 天混在一起算的衰减均值"。问题在于：同一因子在不同市场体制下 IC 方向/强度差异很大。例如 `EARNINGS_SURPRISE` 在趋势市（BULL）有效、在震荡市（SIDEWAYS）失效甚至反向，但混算 IC 把它撑住，导致震荡市仍给它高权重（反向注水），拖累选股。

**目标**：权重用的 IC 只取"当前 regime 的交易日"，让每个体制下权重由该体制自身的 IC 决定。

---

## 2. market_regime_calendar 表

### 2.1 结构（MySQL，库 `stock`）

```sql
CREATE TABLE market_regime_calendar (
  trade_date  DATE        NOT NULL,
  regime      VARCHAR(16) NOT NULL,   -- BULL / BEAR / SIDEWAYS
  updated_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='市场环境体制日历';
```

### 2.2 作用

给**每一个交易日**贴一个环境标签，供 `getIcTimeline` 过滤 IC 点（只留同体制的点）。

### 2.3 写入链路

- `RecommendationService.detectRegime(LocalDate date)`：纯内存计算（沪深300 趋势 MA20/MA60 + 0.5% 缓冲带 + ATR 波动率百分位 + 市场宽度 + 大小盘/价值成长风格）→ 返回 `BULL/BEAR/SIDEWAYS`；数据不足回退 `SIDEWAYS`。
- 该方法**末尾 upsert** 当天的 regime 进 `market_regime_calendar`。
- 回放 / 实盘每生成一个交易日，顺手把那天标签存下来，日历随重放积累（回放 134 交易日后已覆盖 125 有效日）。

### 2.4 读取服务

- `MarketRegimeCalendarService`：缓存 + 懒计算 + detector 回调。
- **刻意不持有 `detectRegime` 的引用**，改为由 `RecommendationService` 在 `@PostConstruct` 时通过 `setDetector(...)` 注入回调，避免两服务循环依赖。
- `detectRegimeName(date)`：公开方法，供 `FactorAnalysisService` 查询某天的 regime。

> 为什么用 MySQL 而非 ClickHouse：它是小体量参考表，跟着主库走，避免依赖 CH 可用性；且与 `factor_ic_record` 等同库，查询一致。

---

## 3. ICW 按 regime 分别算 IC（①）

### 3.1 关键事实：generate 主链 ICW 走实时算 IC

`RecommendationService` 的 `adjustDynamicWeights` ICW 管线调用：
`quickFactorIcSnapshot` → `computeSnapshot` → `getIcTimeline` → `calcSingleFactorIcIr`

**`calcSingleFactorIcIr` 是从 ClickHouse 实时算最近 ~120 天 IC**（不是读 `factor_ic_record`）。因此 regime 过滤必须落在 `getIcTimeline` / `calcSingleFactorIcIr`，改 `factor_ic_record` 无效。

### 3.2 getIcTimeline 的 regime 过滤

改造后的 `getIcTimeline(factorCode, start, end, forwardDays, neutralize, corrType, targetRegime)`：

1. 调 `calcSingleFactorIcIr` 取原始 IC 时序（每条含 `date` + `ic`）。
2. 用 `MarketRegimeCalendarService.detectRegimeName(date)` 查每条 IC 点所属 regime。
3. **只保留 `regime == targetRegime` 的 IC 点**。
4. 同体制样本 `< 20` 天 → **退化为全样本**（防止体制切换初期样本不足导致权重失真）。
5. 后续 `decayWeightedMean` 对过滤后的 IC 序列算衰减均值。

`targetRegime` = 当前生成日（reference date）的 regime（由 `detectRegimeName` 取得）。

### 3.3 效果

为 SIDEWAYS 日生成时，ES 的权重由 SIDEWAYS 自身的 IC 决定（实测 ≈0.29），不再被趋势市 IC 撑到 35%，反向注水消除。

---

## 4. 白名单 SIDEWAYS 守卫（③）

`FORCE_KEEP_FACTORS = { EARNINGS_SURPRISE }`：该 alpha 因子不受拥挤度 / 噪声 / 无 IC 等任何剔除规则影响，权重 = 配置权重（2.0，经 35% 上限砍到占比 35%）。

**问题**：若不过滤，SIDEWAYS 下 ES 仍被强制 35%，① 对它完全失效（它跳过 regime-ICW 直接拿 35%）。

**守卫**：强制保留条件加 `&& !"SIDEWAYS".equals(currentRegime)`。震荡市下 ES 不享受白名单豁免，走 ① 的 regime-ICW 自然定权。

---

## 5. 连续 BEAR 暂停（④）

`CONSECUTIVE_BEAR_STOP_DAYS = 3`：连续 3 个交易日判定为 BEAR 时，`generate` 直接 `return List.of()`（不生成推荐），规避连续熊市下行。

回测中 125 个有效交易日里有 9 天因此暂停（3-30~4-07 六天 + 7-22~7-24 三天）。

---

## 6. 端到端数据流（回放 / 实盘）

```
DAILY_RECOMMENDATION 调度
   └─ 遍历 ACTIVE 策略(含73) → POST /api/recommendations/generate
        ├─ detectRegime(date)  → upsert market_regime_calendar[date]=regime
        ├─ adjustDynamicWeights(ICW):
        │     └─ quickFactorIcSnapshot → computeSnapshot
        │           └─ getIcTimeline(targetRegime=detectRegimeName(date))
        │                 ├─ calcSingleFactorIcIr(CH实时算120天IC)
        │                 ├─ 按 calendar 过滤同 regime IC 点(<20退全样本)
        │                 └─ decayWeightedMean
        │     └─ FORCE_KEEP 分支(ES) 受 SIDEWAYS 守卫约束
        ├─ isConsecutiveBear? → 连续3日BEAR 则 return 空
        └─ 写入 stock_recommendation
RECOMMENDATION_TRACK 回填 next_day/week/month_excess_return
```

---

## 7. 关键文件清单

| 文件 | 角色 |
|---|---|
| `sql/mysql-regime-calendar.sql` | 建表 DDL |
| `factor/regime/MarketRegimeCalendarMapper.java` | 表读写（upsert / 按日期查 regime） |
| `factor/regime/MarketRegimeCalendarService.java` | 缓存 + 懒计算 + detector 回调 |
| `recommendation/service/RecommendationService.java` | `detectRegime` 落库、`@PostConstruct` 注入 detector、`FORCE_KEEP` + SIDEWAYS 守卫、连续 BEAR 暂停 |
| `factor/service/FactorAnalysisService.java` | `getIcTimeline` 按 regime 过滤、`calcSingleFactorIcIr` 实时算 IC |
