# 权限访问控制盘点（2026-07-31）

> 结论先说：上一轮基于 `@SaCheck` 关键词的 grep 漏掉了业务 Controller 的**全限定写法**
> `@cn.dev33.satoken.annotation.SaCheckPermission(...)`，导致误判“业务接口只鉴登录不鉴权限”。
> 经逐文件核对 + 菜单 seed 数据核对后，**实际状态是：全部 URL 在“模块级”均已受控**；
> 真正的缺口只在“模块内读写分离 / 按钮级”这一更细的粒度。本文为更正后的准确盘点。

---

## 一、鉴权链路（确认无误）

1. **登录层（全覆盖）**：`SaTokenConfigure` 用 `new SaInterceptor()`（默认 `StpUtil.checkLogin()`）
   拦截所有 `/api/**`，仅放行 `auth/login`、`auth/wechat/**`、`test/**`、swagger。
   未登录 → 401。
2. **权限层（模块级全覆盖）**：每个业务 Controller 类上都有
   `@cn.dev33.satoken.annotation.SaCheckPermission("xxx:view")`。
3. **权限来源**：`StpInterfaceImpl.getPermissionList` → `SysUserMapper.selectPermissionByUserId`
   查 `sys_menu.permission`（经 `sys_role_menu`、`sys_user_role` 关联，过滤空串）。
4. **菜单 seed**：`stock-service/sql/sys_seed_data.sql` 业务节点均带 `xxx:view` 权限串，
   ADMIN(role_id=1) 绑定全部 47 个菜单 → admin 拥有全部 `xxx:view`。

---

## 二、模块级映射表（Controller ↔ 权限串 ↔ 菜单节点）

| 权限串 | 对应 Controller（base path） | 菜单节点 id（sys_menu） | 注解状态 |
|---|---|---|---|
| `system:user:*` | UserController | 2-7 | ✅ 方法级细化（list/add/edit/delete/reset/assign） |
| `system:role:*` | RoleController | 8-12 | ✅ 方法级细化 |
| `system:menu:*` | MenuController | 13-16 | ✅ 方法级细化 |
| `market:view` | MarketDataController `/market` | 18,19 | ✅ 类级 |
| `stock:view` | StockAnalysisController `/analysis` | 20 | ✅ 类级 |
| `factor:view` | FactorController `/factors` | 21-26 | ✅ 类级 |
| `strategy:view` | StrategyController `/strategies`、BacktestController `/backtests`、PaperTradingController `/paper-trading`、PortfolioRiskController `/portfolio-risk`、RegimeBacktestController `/regime-backtest` | 27-33 | ✅ 类级 |
| `screen:view` | StockScreenController `/screen` | 35 | ✅ 类级 |
| `recommendation:view` | RecommendationController `/recommendations`、StrategyConfidenceController `/strategy-confidence`、StockBlacklistController `/blacklist` | 36 | ✅ 类级 |
| `llm:view` | LlmAnalysisController `/llm` | 37 | ✅ 类级 |
| `monitor:view` | MonitorController `/monitor` | 38 | ✅ 类级 |
| `calendar:view` | TradeCalendarController `/calendar` | 39 | ✅ 类级 |
| `data:view` | DataUpdateController `/data-update`、DataQualityController `/data-quality`、SentimentController `/data-update/sentiment` | 40,41,44,45,46 | ✅ 类级 |
| `financial:view` | FinancialDataController `/financial` | 42 | ✅ 类级 |
| `research:view` | ResearchController `/research` | 43 | ✅ 类级 |
| （无权限串） | — | 17 总览、34 选股工具(目录)、47 使用手册 | 无需注解（匿名可读/目录节点） |

> 结论：所有 Controller 的权限串都能在菜单 seed 中找到对应节点；无“注解串 ↔ 菜单串”对不上的情况。

---

## 三、已覆盖 vs 缺口

### ✅ 已覆盖
- 所有 `/api/**` 强制登录。
- 所有业务模块 API 强制对应 `xxx:view` 权限 → 缺模块菜单权限的用户调该模块 API 返回 403。
- 系统管理模块额外做到**按钮级**（方法级注解 + 菜单按钮节点 permission 串一一对应）。

### ❌ 缺口（非“裸奔”，是粒度粗）

**缺口 1：模块内“读写未分离”**
类级 `xxx:view` 被该 Controller 内所有方法继承，包括 POST/PUT/PATCH/DELETE。
即：拥有 `factor:view`（能看因子）的角色，**也能创建/计算/删除因子**。
若需“只读角色不可写”，需在写方法上叠加 `xxx:create` / `xxx:edit` / `xxx:delete` 注解。
写操作较集中、最该细化的 Controller：
- FactorController（create/compute/batch-compute/test/PUT/DELETE/PATCH）
- StrategyController（POST/PUT/DELETE/PATCH）
- BacktestController（create/cancel/rerun/compare/walk-forward/DELETE）
- PaperTradingController（大量 POST/PUT/PATCH/DELETE）
- StockBlacklistController、LlmAnalysisController、MonitorController、TradeCalendarController、DataUpdateController、ResearchController、StrategyConfidenceController、RecommendationController、StockScreenController

**缺口 2：业务模块无“按钮级”菜单节点**
seed 里只有系统管理模块有 `menu_type=2`（按钮）节点（id 3-7 / 9-12 / 14-16）。
业务模块只有目录(0)/菜单(1)节点，**没有为写操作登记按钮级 permission 串**。
→ 即使后端加了 `factor:create`，前端也无对应按钮节点可显隐。

**缺口 3：前端业务页按钮未按权限隐藏**
`RequireAuth` 仅判 token（登录）；`hasPermission`/`permissions` 判定只在系统三页使用。
业务页按钮对“有该模块 view 权限”的用户全部可见（目前因为读写共用 `:view`，所以不会出错；
但若做缺口1的读写分离，前端必须同步按 `xxx:create` 等隐藏写按钮，否则点了会 403）。

**缺口 4：`/api/test/**` 白名单匿名**
`SaTokenConfigure` 把 `/api/test/**` 排除在拦截器外（TestController 健康检查）。
属有意设计，但意味着这些端点任何人都可匿名访问，若含敏感操作需评估。

---

## 四、若要做到“按钮级全覆盖”，下一步

1. 后端：在写方法上叠加 `@SaCheckPermission("xxx:create|edit|delete")`，覆盖继承的 `:view`。
2. 菜单：为业务模块补 `menu_type=2` 按钮节点，permission 串与步骤1一致。
3. 前端：业务页写按钮改用 `hasPermission('xxx:create')` 显隐；路由侧可基于登录返回的菜单 permission 预过滤。
4. 验证：扩 `verify_auth.sh`，用“仅含某模块 view、不含 create”的测试角色断言写接口返回 403。

> 注：当前系统已经“能用且安全”（模块级隔离 + 登录强制），按钮级是增强项，非修复项。

## 五、实施状态（2026-07-31 更新）

按钮级权限的**核心安全层已落地并验证**：

### ✅ 已完成（后端闭环 + 编译 + 验证脚本）
1. **后端方法级注解（task9）**：16 个有写方法的业务 Controller 写方法叠加
   `@cn.dev33.satoken.annotation.SaCheckPermission(value={"module:view","module:edit|delete"}, mode=AND)`，
   POST/PUT/PATCH→`module:edit`、DELETE→`module:delete`，4 个 GET-触发写标 `module:edit`。
   AND 模式保证只读角色写接口返回 403，且不出现"能写不能读"怪象。**reactor 编译通过（BUILD SUCCESS）**。
2. **菜单按钮节点（task10）**：`sys_seed_data.sql` 追加 14 个 `menu_type=2` 节点（id 60-73），permission 与后端一一对应并绑定 ADMIN。
   另提取独立幂等脚本 `stock-service/sql/append_button_perms.sql`（库已初始化时单独补执行用）。
3. **编译与验证（task12）**：`mvn -pl common,stock-service -am clean package -DskipTests` → BUILD SUCCESS；
   `verify_auth.sh` 扩展 [14]~[16]：仅 view 角色调 factor 写接口 403、追加 factor:edit 后非 403。

### ✅ 前端按钮显隐（task11，部分落地：4/12 业务模块，强依赖 seed 落库）
统一模式：页面内 `const canEdit = useAuthStore(s=>s.hasPermission('module:edit'))`（delete 用 `module:delete`），写函数入口加 `if(!canEdit){message.warning;return}` 拦截，写按钮加 `disabled={!canEdit}`。
- **已落地页面（4 个业务模块 + 系统三页）**：
  - `calendar/TradeCalendar.jsx`（calendar:edit）
  - `dataupdate/ScheduledTasks.jsx`（data:edit）
  - `recommendation/RecommendationList.jsx`（recommendation:edit/delete + strategy:edit 模拟盘）
  - `backtest/WalkForward.jsx`（strategy:edit）
  - 系统管理三页（UserManage/RoleManage/MenuManage）早已用 `hasPermission` 控制

### ⚠️ 前端待补（2026-07-31 校验发现）
全量 grep `frontend/src` 仅 5 个文件用 `hasPermission`。以下 8 个业务模块**前端页面存在（实为 `.js` 非 `.jsx`）但完全无按钮网关**，后端已 403 拦截、前端按钮照常显示（UX 缺口，非安全漏洞）：
  - `factors/FactorList.js` + `FactorEditor.js`（factor:edit / factor:delete：新建/编辑/激活停用/删除/清理/批量计算）
  - `monitor/MonitorPage.js`（monitor:edit / monitor:delete：删除自定义股票/触发扫描/清空信号/新增编辑）
  - `screen/StockScreen.js` + `RollingBacktestModal.js`（screen:edit）
  - `llm/LlmAnalysisPage.js`（llm:edit）
  - `strategies/StrategyList.js` + `StrategyEditor.js` + `PaperTradingPage.js`（strategy:edit/delete）
  - `analysis/StockAnalysis.js` + 各 Tab（stock:edit：解析新闻）
  - `research/*`（research:delete：批量删除）
  - `backtest/Backtest.jsx` 主页面（strategy:edit：创建并启动回测）
- financial/market 模块为纯查询（无写方法），无需网关。
- 注：上一轮"前端管理页不存在（Glob 无 jsx）"系 Glob 仅搜 `.jsx` 漏掉 `.js` 导致误判，已纠正。

> 说明：安全拦截层（后端方法级注解 + 菜单按钮节点）已完整并编译通过、种子已落库；前端写按钮显隐仅完成 4/12 业务模块，剩余 8 模块待补。低权限用户调写接口后端仍返回 403（双保险仍在），仅前端未隐藏按钮。
