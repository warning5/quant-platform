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

**方案**
- 新增 `MpProfileController`：读/改资料（复用 `SysUser`）。

**可行性**：✅ 登录/用户表已有；资料端点补一下即可。
- ⏸️ 风险测评（投资者适当性）：全项目无适当性问卷/评分接口，一期**不做**，推迟二期（问卷 + 提交评分 + 结果落库均需新建）。

## 5. 二期明确不做（避免一期发散）

| 项 | 原因 |
|---|---|
| 用户级自选 watchlist 表 + `WatchlistController` | 一期砍自选；除非做监控才以「监控池」回归 |
| 盘中监控 / 信号 | 最贵最模糊；若做则 `monitor_custom_stock` 是全局池，需决策隔离方案 |
| 因子收藏 `user_factor_favorite` | 缓，不阻塞主线 |
| 模拟交易 / 研报 / 资源授权可视化小程序入口 | 主后端已有，小程序二期再接 |

## 6. 待建表

**一期：无新建表** —— 所有端点均为包装主后端已有能力或复用 `SysUser`，无需建表。

**二期（风险测评，当前不做）**
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

> 因子收藏表（`user_factor_favorite`）、自选表（`user_watchlist`）一期不建。

## 7. backend-mp 一期需新增端点汇总

| Controller | 端点 | 来源 |
|---|---|---|
| `MpRecommendationController` | `GET /mp/recommendations/stock/{code}/detail` | 包装主后端 `StockAnalysisController`（直连 MySQL + `RecommendationMapper`） |
| `MpStrategyController` | `GET /strategies`、`GET /strategies/{id}` | 包装主后端 `StrategyController`（只读） |
| `MpStrategyController` | `GET /strategies/{id}/backtest` | 包装 `BacktestController` / `RegimeBacktestController` |
| `MpFactorController` | `GET /factors`、`/factors/{id}`、`/factors/{id}/ic-trend` | 包装主后端 `FactorController`（只读） |
| `MpProfileController` | `GET/PUT /me` | 复用 `SysUser` |

## 8. 落地顺序建议

1. **零成本可直接上**（一期全部任务均属此类）：因子只读包装、推荐个股详情、策略只读暴露（列表 + 回测表现）、我的-资料读写。
2. **二期**：风险测评、自选/监控、因子收藏、模拟交易/研报入口。

## 9. 一期开发任务清单

> 单位：backend-mp 新增/改造。所有新端点走 `MpAuthFilter`，复用统一 `ApiResponse` 脱敏结构。

### 智能推荐
- [x] **R1** `MpRecommendationController` 新增 `GET /mp/recommendations/stock/{code}/detail`：聚合 `StockRecommendation` 的评分明细 / 因子归因(`factorRanksJson`) / 买卖信号 / 风险提示；行情与名称由前端并行调已有 `/mp/monitor/stocks` 腾讯直连。✅ 已编码 + `clean compile` 通过。

### 策略（只读）
- [x] **S1** `MpStrategyController` 新增 `GET /strategies`（列表）、`GET /strategies/{id}`（详情）：复用 common `StrategyDefinitionMapper`。
- [x] **S2** `MpStrategyController` 新增 `GET /strategies/{id}/backtest`（回测表现）：本地 `MpBacktestTaskMapper`/`MpBacktestReportMapper` 查最新 COMPLETED 任务 + 报告。

### 因子（只读）
- [x] **F1** `MpFactorController` 新增 `GET /factors`（列表）、`GET /factors/{id}`（详情）、`GET /factors/{id}/ic-trend`（IC 趋势）：本地 `MpFactorMapper`/`MpFactorIcRecordMapper` 复用 `factor_definition`/`factor_ic_record`。

### 我的
- [x] **M1** `MpProfileController` 新增 `GET /me`、`PUT /me`：资料读写，复用 `SysUser` + `MpUserMapper`（当前用户取 `StpUtil.getLoginIdAsLong()`）。
- ⏸️ 风险测评（`MpRiskController` + 建表）一期不做，推迟二期。

### 横切
- [x] **X1** 横切校验：① 所有新端点经 `MpAuthFilter` 鉴权（`/mp/*`）；② 无 cookie 环境 token 落地 `wx.storage`（`utils/request.js` 已具备）；③ `ApiResponse` 统一返回在 backend-mp 生效，小程序端按统一结构解析。

### 依赖与建议顺序
- **R1 / S1 / S2 / F1 / M1 互相独立**，可并行。
- 建议节奏：先 R1 + F1 + M1（零成本只读）→ 再 S1 + S2（策略只读）。
- 横切 X1 在每个端点开发时同步验证，不单独排期。

## 10. 小程序前端（Taro）一期任务清单

> 框架：Taro 3.6.35 + React + TS，源码 `miniprogram/src/`，打包产物 `miniprogram/dist/`(小程序原生)。现有 4 页（list/detail/history/about）+ 3 Tab（推荐/表现/关于）。

### 关键现状（影响任务拆分）
- **API 封装** `src/api/index.js` 已覆盖：推荐(strategies/dates/strategy-date/latest/batch-history/hit-rate)、置信度、指数、个股行情。但**无** 个股详情(R1)、策略定义列表/回测(S1/S2)、因子(F1)、资料(M1)。
- **详情页是「列表传参渲染」**：`detail/index.jsx` 不单独调接口，从 list 页 `navigateTo` 时把 `item`+`quote` 序列化成 query 传过来。→ R1 端点当前无强制前端消费者，价值在「深链 / 从策略·因子页进入个股 / 刷新实时数据」。
- **请求层 `utils/request.js` 已具备 X1 前端侧**：微信登录换 token、落 `wx.storage`、401 自动重登、`X-MP-Token` 头、统一 `ApiResponse` 解析(`code===200`→`data`)。新端点只要走 `request()` 即自动合规。

### A. API 层（src/api/index.js，纯新增，零风险）
- [x] **FA-R1** `recommendationApi.getStockDetail(code, {strategyId, date})` → `GET /mp/recommendations/stock/{code}/detail`
- [x] **FA-S1** `strategyApi.list()` → `GET /mp/strategies`；`strategyApi.get(id)` → `GET /mp/strategies/{id}`
- [x] **FA-S2** `strategyApi.backtest(id)` → `GET /mp/strategies/{id}/backtest`
- [x] **FA-F1** `factorApi.list()` / `get(id)` / `getIcTrend(id)` → `GET /mp/factors` / `/mp/factors/{id}` / `/mp/factors/{id}/ic-trend`
- [x] **FA-M1** `profileApi.get()` / `update(data)` → `GET|PUT /mp/me`

### B. 页面层
- [x] **FP-推荐(list)** 基本不动（已覆盖策略/日期/列表/行情/命中率/置信度）。
- [x] **FP-详情(detail)** 保持「列表传参渲染」（list→detail 序列化 item+quote）；R1(FA-R1) 已在 API 层就绪，供深链使用。
- [x] **FP-策略(新增, Tab2)** `pages/strategy/index`(列表, FA-S1) + `pages/strategy/detail`(详情 + 回测表现 FA-S2 + 批次历史，承接原 history 页)。
- [x] **FP-因子(新增, Tab3)** `pages/factor/index`(列表, FA-F1.list) + `pages/factor/detail`(详情 + IC趋势, FA-F1.get/getIcTrend)。
- [x] **FP-我的(改造 about→mine, Tab4)** `pages/mine/index`：资料读写(FA-M1) + 原 about 的免责声明 + 退出登录。
- [x] **FP-app.config.js** 改 `tabBar.list` 为 4 项（推荐 / 策略 / 因子 / 我的）；`pages/history`、`pages/about` 已删除并移出 pages 数组。
- [x] **FP-历史页(history)下线** 批次历史 + 置信度迁至 `pages/strategy/detail` 回测表现区。

### 验证状态（2026-08-07 晚）
- ✅ 后端：`mvn -pl backend-mp -am clean package` BUILD SUCCESS；`java -jar` 启动 backend-mp 成功（端口 8082，context-path `/api`，profile `dev`）。
- ✅ 联调：通过 `GET /mp/dev/token?userId=1` 获取 Sa-Token 后，curl 验证以下端点均 200 且结构与前端 API 层一致：
  - `/mp/strategies`、`/mp/strategies/{id}`、`/mp/strategies/{id}/backtest`（回测报告字段完整）
  - `/mp/factors`、`/mp/factors/{id}`、`/mp/factors/{id}/ic-trend`
  - `/mp/me`、`PUT /mp/me`（鉴权上下文已绑定，可正常读写）
  - `/mp/recommendations/stock/{code}/detail`
- ✅ 前端：`npm run build:weapp` Compiled successfully；dist 含 7 页，tabBar = 推荐/策略/因子/我的，4 个 Tab 均有 icon。
- ✅ 顺带修复 `src/utils/request.js:67` 既有语法错误 `fail(reject),` → `fail: reject,`（此前无法编译）。
- ✅ Tab 图标：为 策略/因子/我的 生成 81×81 PNG 灰/红图标（与原有推荐图标同色系），已配置到 `app.config.js`。
- ✅ 回测格式化：查 `backtest_report` 真实样本确认收益/回撤/胜率/波动率/Alpha 均为**比率存储**；新增 `formatRatio` helper，策略详情页全部改用 `formatRatio`；`priceColor` 改为基于原始数值着色，避免对格式化字符串失效。
- ✅ 鉴权上下文：修复 `MpAuthFilter` 只校验 token 未绑定 Sa-Token 上下文的问题，新增 `StpUtil.setTokenValue(token)`，使 `StpUtil.getLoginIdAsLong()` 在 Controller 中可用。

### C. 横切（X1 前端侧基本已具备）
- [x] `utils/request.js` 已实现 token 落地 + 401 重登 + 统一响应解析。**新端点只需走 `request()` 即合规**，无额外工作；建议加一次 `API_BASE` 联调校验（dev.js 指向 backend-mp :8082）。

### D. TabBar 结构（已决策：4 Tab，用户 2026-08-07 拍板）
采用 **推荐 / 策略 / 因子 / 我的** 4 Tab：
- 现有 3 Tab 中「表现」(history) 内容（批次历史 + 置信度）**并入策略详情页**（S2 回测表现区），`pages/history` 不再作 Tab，内容迁至 `pages/strategy/detail`。
- 现有「关于」(about) 内容（免责 / 合规）**并入「我的」页底部**（子区或二级入口），不再独立 Tab。
- 需改 `src/app.config.js` 的 `tabBar.list` 为 4 项，并新增 `pages/strategy/`、`pages/factor/`、`pages/mine/` 目录（每页含 `index.config.js` + `index.jsx` + `index.scss`）。

### 前端任务与后端一一对应
| 后端 | 前端 API | 前端页面 | 类型 |
|---|---|---|---|
| R1 | FA-R1 | FP-详情(深链) | 新增 API |
| S1/S2 | FA-S1/FA-S2 | FP-策略(新) | 新增 API+页 |
| F1 | FA-F1 | FP-因子(新) | 新增 API+页 |
| M1 | FA-M1 | FP-我的(新/改) | 新增 API+页 |
