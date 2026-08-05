# 多策略组合配置（v1.2）落地方案

> 基于 `stock-service` 现有 `strategy.paper` 模块代码盘点后给出。
> 结论先行：**v1.2 不是从零开发，而是补完"组合管理层"**——因子融合骨架已具备，缺失的是资金分配、再平衡、策略级归因与组合可视化。

---

## 1. 现状盘点：已具备什么（关键证据）

| 能力 | 现状 | 代码位置 |
|------|------|---------|
| 组合配置字段 | `paper_trading.strategy_config_json` 已建，格式 `[{strategyId,weight}]`，`null`=单策略 | `PaperTrading.java:32` |
| 权重校验 | `createPaperTrading` 已校验权重和≈1.0 | `PaperTradingService.java:90-99` |
| 创建接口支持组合 | `/create` 已接收 `strategyConfigJson` | `PaperTradingController.java:29-39` |
| **因子级融合信号** | `generateSignals` 已解析多策略，把因子权重按"策略权重×因子权重"叠加成统一打分 | `PaperSignalGenerator.java:96-124` |
| 风控预留字段 | `paper_risk_config` 已有 `allocation_mode`、`rebalance_freq`、`rebalance_threshold`、`cash_buffer_pct` | `stock.sql` |
| NAV / 信息比率 | `PaperAccountService` 已实现组合整体净值与 IR | `PaperAccountService.java` |

**当前组合的实现形态 =「因子级融合」**：多个子策略的因子在打分环节被加权合并，最终在一个 `paper` 账户里统一选股、统一交易。这不是「子账户聚合」型组合。

---

## 2. 能力缺口（Gap 分析）—— v1.2 真正要补的

| # | 缺口 | 证据 | 影响 |
|---|------|------|------|
| G1 | **无策略级隔离/归因** | `paper_signal`、`paper_position` 均无 `strategy_id` 字段，融合后信号与持仓无法归属到子策略 | 看不到"策略A赚多少、策略B亏多少"，Brinson 到策略维度做不了，截图诉求未满足 |
| G2 | **无资金预算分配** | `buySlots = 10 - heldCodes.size()` 硬编码 10 只上限（`PaperSignalGenerator.java:322`），未按 `strategyConfigJson` 权重切分资金池 | 权重只体现在因子打分上，未体现在"给策略A 40% 资金"上 |
| G3 | **再平衡引擎缺失** | `paper_risk_config.rebalance_freq/threshold` 字段空置，`PaperTradingScheduler` 完全未读取 | 权重漂移后不会自动回归目标比例 |
| G4 | **组合层可视化缺失** | 前端无组合总览、无子策略贡献度/相关性视图 | 用户感知不到"组合"的存在 |

---

## 3. 两条路线对比

**路线 A —— 在现有因子融合上补管理层（推荐起步）**
- 改动：加 `strategy_id` 标签 → 按权重切资金预算 → 加再平衡 → 前端总览
- 优点：复用现有单 `paper` 账户，改动局部、风险低、1~2 周可上线
- 代价：归因粒度是"因子级融合"后的近似，非严格独立的子策略 P&L

**路线 B —— 重构为子账户聚合（彻底）**
- 每个子策略一个 `paper` 子账户，组合层汇总子账户净值
- 优点：策略级归因干净、可算子策略相关性
- 代价：要重构 `PaperAccountService` / 执行 / NAV / 数据权限（ResourceMeta 是 PAPER_TRADING 单类型），工作量大、回归风险高

**建议**：先走路线 A 把组合"能用、能看、能再平衡"落地，把严格策略级归因作为后续可选增强（路线 B 或 A+子净值近似）。

---

## 4. 推荐方案（路线 A 分步）

### Phase 1 — 策略标签化（数据层，1~2 天）
给信号与持仓打上子策略标识，为归因与预算分配铺路。

```sql
-- paper_signal 增加子策略标识
ALTER TABLE paper_signal ADD COLUMN strategy_id bigint NULL COMMENT '子策略ID（组合模式）';
CREATE INDEX idx_paper_strategy ON paper_signal(paper_id, strategy_id);

-- paper_position 增加子策略标识与建仓时权重快照
ALTER TABLE paper_position ADD COLUMN strategy_id bigint NULL COMMENT '子策略ID（组合模式）';
ALTER TABLE paper_position ADD COLUMN strategy_weight decimal(6,4) NULL COMMENT '建仓时该策略目标权重快照';
CREATE INDEX idx_paper_strategy ON paper_position(paper_id, strategy_id);

-- 新增再平衡记录表（G3 落库）
CREATE TABLE paper_rebalance_log (
  id bigint AUTO_INCREMENT,
  paper_id bigint NOT NULL,
  rebalance_date date NOT NULL,
  reason varchar(20) COMMENT 'SCHEDULE/THRESHOLD',
  detail_json text COMMENT '各策略实际占比 vs 目标占比及调整明细',
  created_at datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  INDEX idx_paper(paper_id)
) COMMENT='组合再平衡日志';
```

- `PaperSignal.java` 增加 `strategyId`
- `PaperPosition.java` 增加 `strategyId`、`strategyWeight`

### Phase 2 — 资金预算分配（核心，2~3 天，解决 G2）
改造 `PaperSignalGenerator.generateSignals`：解析出 `strategyWeights` 后，**按权重切分资金预算**，每个子策略在各自预算内选股。

伪代码（替换现有 `buySlots` 逻辑）：
```
BigDecimal totalAssets = pt.getTotalAssets();
BigDecimal cashBuffer = riskConfig.getCashBufferPct(); // 已存在字段
for (Map.Entry<Long,Double> e : strategyWeights.entrySet()) {
    long sid = e.getKey();
    double w = e.getValue();
    BigDecimal subBudget = totalAssets.multiply(BigDecimal.valueOf(w))
                                .multiply(BigDecimal.ONE.subtract(cashBuffer));
    List<Candidate> cands = scoreStocksForStrategy(sid).topN(perStrategyCap);
    // 在 subBudget 内分配买入，标记 strategyId
    for (Candidate c : cands) {
        if (subBudgetUsed(sid).compareTo(subBudget) >= 0) break;
        emitBuySignal(c, sid);  // 写 paper_signal.strategy_id
    }
}
```
- 卖出信号也打 `strategy_id`（遍历持仓时已能拿到 `position.strategyId`）
- `PaperOrderExecutionService.executeSignal` 执行时把 `strategyId/strategyWeight` 写入 `paper_position`

### Phase 3 — 再平衡引擎（解决 G3，2 天）
新增 `PaperRebalanceService`：
- 读取 `rebalance_freq`（DAILY/WEEKLY/MONTHLY/THRESHOLD）与 `rebalance_threshold`
- 计算各子策略实际占比 `actual = subNav / totalNav`，与目标 `target` 比较
- 偏离超阈值或到调度周期 → 生成再平衡信号（SELL 超配 / BUY 欠配），写入 `paper_rebalance_log`
- `PaperTradingScheduler` 在 Step 3（执行信号）之后插入 Step 3.5 调用再平衡检查

### Phase 4 — 组合层聚合与归因（2 天，解决 G1 呈现）
- `PaperAccountService` 新增 `calcSubStrategyNav(paperId)`：按 `strategy_id` 聚合子策略子净值、收益贡献
- `PaperTradingService.getDetail` 返回增量：`subStrategies`（净值/权重/贡献/当前占比）、`weightDrift`
- 子策略相关性：从各自 `paper_nav` 序列算 Pearson 相关（复用 CH 净值表）

### Phase 5 — 前端组合总览（2~3 天，解决 G4）
- 组合净值页：组合净值曲线 + 基准
- 子策略贡献度条形图 / 权重漂移表
- 再平衡历史列表
- 相关性热力图（可选）

---

## 5. 接口设计（新增/调整）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/paper-trading/{id}/sub-strategies` | 各子策略净值、目标权重、实际占比、收益贡献 |
| GET | `/paper-trading/{id}/rebalance-log` | 再平衡历史 |
| POST | `/paper-trading/{id}/rebalance` | 手动触发再平衡（运维/调试用） |
| 调整 | `/paper-trading/{id}` (getDetail) | 返回新增 `subStrategies` / `weightDrift` 字段 |

权限沿用现有 `strategy:view` / `strategy:edit`；数据权限 `PaperTradingResourceOptionProvider` 已按 `paper_id` 管控，新增子策略视图无需改动（仍挂在父 `paper_id` 下）。

---

## 6. 类与方法改动清单

| 类 | 改动 |
|----|------|
| `PaperSignal` | 新增 `strategyId` 字段 |
| `PaperPosition` | 新增 `strategyId`、`strategyWeight` 字段 |
| `PaperSignalGenerator` | `generateSignals` 改为按策略切资金预算 + 信号打标（G2） |
| `PaperOrderExecutionService` | `executeSignal` 写 `position.strategy_id/strategy_weight` |
| `PaperRebalanceService` (新增) | 再平衡引擎（G3） |
| `PaperTradingScheduler` | Step 3.5 插入再平衡检查 |
| `PaperAccountService` | 新增 `calcSubStrategyNav` / 相关性计算 |
| `PaperTradingService` | `getDetail` 返回子策略聚合 |
| `PaperTradingController` | 新增 3 个接口 |

---

## 7. 风险点

1. **因子融合 vs 资金预算的语义偏差**：路线 A 下，因子打分仍融合、仅资金切分按策略。严格意义上"策略权重"体现在资金预算而非因子权重。若需完全独立的子策略 P&L，需路线 B。
2. **再平衡交易成本**：频繁再平衡会放大手续费/滑点（已有 `slippage_pct` 字段可控制）。
3. **持仓上限硬约束**：现有 `buySlots` 上限 10 与"按权重配资金"需协调，避免小权重策略买不满。
4. **`allocation_mode` 字段已存在但未实现**：若启用 `dynamic/kelly`，Phase 2 需额外实现权重动态计算，建议 v1.2 先只支持 `equal`（按 `strategyConfigJson` 固定权重）。

---

## 8. 工作量估算（路线 A）

| Phase | 内容 | 估时 |
|-------|------|------|
| 1 | 策略标签化 + DDL | 1~2 天 |
| 2 | 资金预算分配 | 2~3 天 |
| 3 | 再平衡引擎 | 2 天 |
| 4 | 聚合与归因 | 2 天 |
| 5 | 前端总览 | 2~3 天 |
| **合计** | | **约 9~12 人天** |

> 对比「从零做子账户聚合」（路线 B）预计 3~4 周，路线 A 性价比显著更高，且能直接回应截图 v1.2 的核心诉求。

---

## 9. 交互设计

### 9.1 三阶段交互模型

| 阶段 | 触发者 | 频率 | 交互内容 |
|------|--------|------|---------|
| 配置期 | 用户 | 一次性 | 勾选子策略 → 权重滑块（实时校验合计=100%）→ 再平衡频率/漂移阈值/现金缓冲 → 初始资金 |
| 运行期 | 系统 | 每日自动 | 按权重切资金预算 → 各策略选股并执行 → 检查权重漂移 → 超阈值触发再平衡 |
| 复盘期 | 用户 | 按需 | 查看组合总览/子策略贡献/再平衡历史 → 调权重或暂停子策略（回到配置期闭环） |

### 9.2 页面结构

**创建页**（复用现有 `/create` 接口，已支持 `strategyConfigJson`）
- 策略行：`策略名 + 因子说明 | 权重滑块(step=5%) | 权重数值`
- 底部实时合计条：≠100% 时红色提示并禁用创建按钮（后端 `createPaperTrading` 已有同样校验，前端只做即时反馈）
- 再平衡设置区：频率（每月/每周/仅阈值）、漂移阈值（5/10/15%）、现金缓冲（0/5/10%）

**详情页**（4 个 Tab）
1. 组合总览 —— 净值曲线 + 基准线 + 核心指标卡
2. 子策略明细 —— 目标权重 vs 实际占比、收益贡献条形图、漂移预警
3. 再平衡历史 —— 读 `paper_rebalance_log`，含触发原因与调整明细
4. 信号流水 —— 现有信号列表增加"所属子策略"列（依赖 Phase 1 的 `strategy_id`）

**干预动作只保留三个**：手动再平衡、调整权重（自动触发一次再平衡）、暂停子策略（权重归零、资金回流现金）。避免旋钮过多导致用户过度调参。

---

## 10. 效果评估体系

### 10.1 四层指标

| 层级 | 回答的问题 | 指标 |
|------|-----------|------|
| L1 组合整体表现 | 值不值得跑 | 年化收益、最大回撤、夏普、卡玛、胜率 |
| L2 分散化有效性 | 分散是否真发生 | 策略间相关系数矩阵、分散化比率 DR、波动率下降幅度 |
| L3 子策略归因 | 谁在贡献 | 收益贡献 `wi×ri`、风险贡献 MCTR、换手率与交易成本 |
| L4 稳健性检验 | 会不会是运气 | 滚动窗口夏普、分市场状态（牛/熊/震荡）表现、权重扰动敏感性 |

### 10.2 L1 必须对比的三类基准（关键）

只跟沪深300 比会让组合"永远有效"，必须同时摆三条线：

| 基准 | 判断标准 |
|------|---------|
| 表现最好的单策略 | 组合年化通常会输，但**最大回撤须明显更小、夏普不能低太多**，否则组合无意义 |
| 等权 1/N 组合 | 加权方案若打不过等权，说明权重调优是白做的 → 直接用等权 |
| 沪深300 / 中证500 | 超额收益 + 信息比率（`PaperAccountService` 已实现 IR，可直接复用） |

### 10.3 L2 分散化的量化判据

n 个等权策略、各自年化波动 σ、平均相关系数 ρ 时：

```
σ_portfolio = σ × sqrt(1/n + (1 - 1/n) × ρ)
分散化比率 DR = Σ(wi × σi) / σ_portfolio
```

4 策略、σ=20% 时的实测对照：

| ρ | 0 | 0.3 | 0.5 | 0.7 | 0.9 |
|---|---|-----|-----|-----|-----|
| 组合波动率 | 10.0% | 13.8% | 15.8% | 17.6% | 19.2% |

**判据**：平均 ρ > 0.7 时组合基本失去意义（波动仅降 12%，却付出双倍交易成本）；DR < 1.15 应告警提示"策略同质化"。建议在详情页做成一张「组合体检卡」，红黄绿三色直出结论。

### 10.4 数据来源与前置依赖

| 指标 | 数据来源 | 依赖 Phase |
|------|---------|-----------|
| L1 组合净值/回撤/夏普 | `paper_nav`（已有） | 无 |
| L1 单策略基准 | **对每个子策略单独跑一次 `BacktestEngine`**，产出影子净值序列 | 无（复用现有回测） |
| L2 相关性 / DR | 各子策略影子净值序列的 Pearson 相关 | 无（同上） |
| L3 收益/风险贡献 | `paper_position.strategy_id` 聚合 | **Phase 1 + Phase 4** |
| L4 滚动/分状态 | 组合净值序列切窗口 + 现有 regime 判定 | 无 |

> ⚠️ **重要约束**：路线 A 是「因子级融合」，持仓由融合后打分选出，因此 L3 的子策略 P&L 是**近似归因**，不是严格独立收益。
> 解决办法：L1 基准对比与 L2 相关性分析，改用「影子回测」——用现有 `BacktestEngine` 对每个子策略单独跑同区间回测，得到干净的独立净值序列。
> 这样**无需重构为子账户（路线 B）也能拿到可信的评估结论**，是路线 A 能成立的关键补丁。建议在 Phase 4 中一并实现。
