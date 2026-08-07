# 小程序一期接口清单与待建表（精简版）

> 背景：基于 `2026-08-05-17-32-43/prototype/index.html` 原型，对齐当前项目真实能力（`miniprogram/dist` + `backend-mp` + 主后端 `stock-service`），给出一期可落地的接口与表结构。
> 定位：backend-mp 是小程序专用网关（只扫 `com.quant.platform.mp`），"补接口"= 在 backend-mp 新增 Controller 包装主后端能力，或直连 ClickHouse / 腾讯（参考 `MpMonitorController`）。

## 0. 范围决策（已拍板）

- ✂️ **砍掉独立「自选」Tab**：纯看价格是红海功能（同花顺/东财标配），ROI 低。
- ⏸️ **盘中监控 / 信号推迟到二期**：最贵、最模糊的一块，且依赖"监控池"概念（见下）。
- 一期聚焦 **4 块差异化能力**：多因子智能推荐、策略（只读）、因子、我的。
- 若二期决定做盘中监控，自选以「加入监控池」形式从推荐/选股结果页一键触达，不做独立导航项。

## 1. 智能推荐

**backend-mp 已有**
- `MpRecommendationController`：`/strategies`、`/dates`、`/strategy/{id}/date/{date}`、`/latest`、`/batch-history`、`/hit-rate/...`
- `MpStrategyConfidenceController`：`/latest-all`
- `MpMonitorController`：`/indices`（腾讯直连）、`/stocks`（腾讯批量实时价/涨跌额/涨跌幅/名称）

**缺失**
- 个股深度详情（原型 `stock_detail`：评分明细 / 因子归因 / 买卖信号 / 风险提示）。主后端有 `StockAnalysisController(/analysis)` + `FactorAnalysisService` + `RecommendationController`，但 backend-mp **未暴露**。

**方案**：新增 `MpRecommendationController` 一个详情端点，复用主后端分析服务；行情/名称走 `/stocks` 直连腾讯。
**可行性**：✅ 数据与引擎全有，加 1 个包装端点。

## 2. 策略（一期只读，不做用户自建）

**主后端已有（backend-mp 未暴露）**
- `StrategyController`：完整 CRUD —— `list` / `getById` / `create` / `update` / `delete` / `changeStatus`
- 引擎齐全：`StockScreenController`（选股）、`BacktestController`、`RegimeBacktestController`、`PortfolioRiskController`（组合风险）

**缺失**
- 策略运行结果 / 回测表现：主后端有 Backtest / RegimeBacktest，backend-mp 需暴露（小程序「表现」页靠它）。
- ⚠️ 一期**只暴露平台预设策略的列表 + 回测表现**，不提供用户自建/编辑/删除。self-service 组合（用户选因子 + 持仓池 + 频率）不在一期范围，故 `StrategyDefinition` 入参兼容性无需评估。

**方案**：backend-mp 新增 `MpStrategyController`，**只读**包装 `StrategyController` 的 `list` / `getById` + 回测结果。
**可行性**：✅ 纯包装，无新建逻辑。

## 3. 因子

**主后端已有（backend-mp 无任何因子端点）**
- `FactorController` 极全：`GET /factors`（列表）、`/factors/{id}`、`/factors/status-batch`、`/factors/{id}/ic-trend`、`/factors/monitor`、`/factors/running`、`/factors/correlation`、`/factors/{factorCode}/values/series`、`/factors/ic-ir-analysis` 等；`FactorHealthController` 给健康度。
- 即因子名称/分类/有效性/IC 趋势数据**全有**。

**缺失**
- 因子库列表/详情/IC 趋势：backend-mp 需新增只读端点包装 `FactorController`（小程序不需要 compute / 脚本编辑类端点）。
- 因子收藏：全项目无「用户收藏因子」概念 → 二期再补 `user_factor_favorite` 小表。

**方案**：backend-mp 新增 `MpFactorController`，**只读**包装主后端因子元数据 + IC 趋势。
**可行性**：✅ 元数据全在主后端，包装即可。

## 4. 我的

**已有**
- `MpWechatLoginController`（微信登录）+ `SysUser` 表 + `MpUserMapper` ✅
- 帮助/关于：静态内容，无需接口（原型 toast 占位 → 真实做静态页）。

**缺失**
- 用户资料读写：backend-mp 无 `ProfileController`，`SysUser` 未暴露 → 需新增（读/改昵称、头像等）。
- 风险测评（投资者适当性）：全项目**无适当性问卷/评分接口**（`PortfolioRiskController` 是策略组合风险，非用户适当性）→ 需新建：问卷配置 + 提交评分 + 结果存储。

**方案**
- 新增 `MpProfileController`：读/改资料（复用 `SysUser`）。
- 风险测评：新建问卷 + 提交评分 + 结果落库（见待建表）。

**可行性**：✅ 登录/用户表已有；资料端点补一下即可；风险测评需新建（问卷 + 评分），中等工作量。

## 5. 二期明确不做（避免一期发散）

| 项 | 原因 |
|---|---|
| 用户级自选 watchlist 表 + `WatchlistController` | 一期砍自选；除非做监控才以「监控池」回归 |
| 盘中监控 / 信号 | 最贵最模糊；若做则 `monitor_custom_stock` 是全局池，需决策隔离方案 |
| 因子收藏 `user_factor_favorite` | 缓，不阻塞主线 |
| 模拟交易 / 研报 / 资源授权可视化小程序入口 | 主后端已有，小程序二期再接 |

## 6. 待建表（一期仅风险测评）

```sql
-- 风险测评问卷（可静态配置，表化便于后台维护）
CREATE TABLE rp_questionnaire (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  category    VARCHAR(32)   COMMENT '风险维度',
  question    VARCHAR(255)  NOT NULL,
  options     JSON          NOT NULL COMMENT '[{label,score}]',
  sort        INT           DEFAULT 0,
  created_at  DATETIME      DEFAULT CURRENT_TIMESTAMP
);

-- 用户测评结果（也可直接落 SysUser 扩展字段，看是否需要历史）
CREATE TABLE user_risk_assessment (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id     BIGINT        NOT NULL,
  score       INT           NOT NULL,
  level       VARCHAR(16)   NOT NULL COMMENT 'C1~C5 / 保守~激进',
  answers     JSON          COMMENT '逐题答案留痕',
  created_at  DATETIME      DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_user (user_id)
);
```

> 因子收藏表（`user_factor_favorite`）一期不建；自选表（`user_watchlist`）一期不建。

## 7. backend-mp 一期需新增端点汇总

| Controller | 端点 | 来源 |
|---|---|---|
| `MpRecommendationController` | `GET /stock/{code}/detail` | 包装主后端 `StockAnalysisController` |
| `MpStrategyController` | `GET /strategies`、`GET /strategies/{id}` | 包装主后端 `StrategyController`（只读） |
| `MpStrategyController` | `GET /strategies/{id}/backtest` | 包装 `BacktestController` / `RegimeBacktestController` |
| `MpFactorController` | `GET /factors`、`/factors/{id}`、`/factors/{id}/ic-trend` | 包装主后端 `FactorController`（只读） |
| `MpProfileController` | `GET/PUT /me` | 复用 `SysUser` |
| `MpRiskController` | `GET /questionnaire`、`POST /assess` | 新建（问卷 + 评分） |

## 8. 落地顺序建议

1. **零成本可直接上**：因子只读包装、推荐个股详情、策略只读暴露（列表 + 回测表现）、我的-资料读写。
2. **中等**：风险测评（问卷 + 评分落库）。
3. **二期**：自选/监控、因子收藏、模拟交易/研报入口。
