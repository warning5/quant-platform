# 量化平台 · 架构与代码质量评估（2026-08-04 实测版）

> 本文基于当前代码库实际扫描结果，修正了一份较早评审中"过时/不准确"的三条结论，并给出当前真实缺口清单。
> 所有结论均附代码证据（文件:行号），可复核。

---

## 一、修正：较早评审中 3 条不准确结论

| 序号 | 较早结论（截图） | 当前代码实际（已实测） | 结论 |
|---|---|---|---|
| 1 | 前端路由权限 `RequireAuth.jsx` 硬编码 `ROUTE_PERMISSIONS` | `RequireAuth.jsx` 已**数据驱动**：从 `/auth/me` 返回的菜单树动态构建 `path → permission` 映射（第 7-31 行 `buildPermissionMap`/`pathCandidates`），无 `ROUTE_PERMISSIONS` 常量 | ❌ 已过时 |
| 2 | dataperm 仍同时 import 业务实体和 system 实体，双向依赖未解 | **X2 SPI 已落地**：`DataPermissionService` 不再 import 业务 Mapper/实体，改由 `ResourceOptionProvider` SPI 收集（`FactorResourceOptionProvider` 等实现在 stock-service 包，依赖单向）；platform-core ↔ stock-service 双向依赖已解除。仅保留对 `SysUser/SysRole/SysDepartment` 的**正常**依赖（用于授权对象下拉） | ⚠️ 已过时 / 表述不准 |
| 3 | 字典化迁移未完成（33 类 / 40+ 常量点大部分未迁移） | 方向**正确**，但数字需以 DB 清单为准。框架已搭好（`sys_dict_type`/`sys_dict_data` + `DictService` + 前端 `useDict`，已用于 20+ 页面）；问题在于**业务状态机仍大量硬编码字符串**（见缺口 A） | ✅ 方向对，缺精确清单 |

---

## 二、已完成的架构改进（确认项）

1. **前端路由权限动态化**
   - `frontend/src/components/RequireAuth.jsx`：权限完全来自后端菜单树，`/auth/me` 返回 `path + permission`。新增/调整菜单权限只改后端 DB，前端零改动。未命中菜单的路由 fail-open，由后端接口 403 兜底。

2. **dataperm 与业务包 SPI 解耦**
   - 接口：`common/.../com/quant/spi/ResourceOptionProvider.java`
   - 收集方：`platform-core/.../dataperm/service/DataPermissionService.java:48` `List<ResourceOptionProvider>`
   - 实现方（stock-service）：`FactorResourceOptionProvider`、`StrategyResourceOptionProvider`、`BacktestResourceOptionProvider`、`PaperTradingResourceOptionProvider`
   - 效果：platform-core 不再依赖任何业务包 Mapper，新增受控资源类型零改动 core。

3. **字典基础设施就绪**
   - 表：`sys_dict_type` / `sys_dict_data`（软删 `deleted=0`）
   - 后端：`platform-core/.../system/dict/DictService.java`（lazy 缓存 + 变更 evict）
   - 前端：`frontend/src/utils/useDict.js`，已在 Factor/Strategy/Backtest/Research/Screen/Monitor 等 20+ 页面使用。

4. **近期已完成的安全收尾**（前序会话）
   - `/api/test/**` 改为仅 dev/local profile 放行（`SaTokenConfigure.java`）
   - 前端 4 处写按钮补权限显隐：`FactorDetail.js` / `StrategyDetail.js` / `ResearchData.js` / `MonteCarloPanel.js`
   - `BacktestController` 的 `/{taskId}/montecarlo` 端点补 `strategy:view AND strategy:edit`

---

## 三、当前真实缺口清单（按优先级）

### A. 【高】业务状态机字符串硬编码（非字典、非枚举）
大量业务状态直接写死在 Java 字符串里，无统一枚举/字典，改一处要全局搜索，且前后端取值口径易漂移。

证据（节选，非穷举）：
- 回测任务：`BacktestEngine.java:128/154/162`（RUNNING/COMPLETED/FAILED）、`BacktestTaskMapper.java:26`（SQL 字面量 `'RUNNING'/'PENDING'`）、`ParamOptimizeService.java:122/140/207/217/233/299/366`、`BacktestReportService.java:228`
- 数据更新任务：`DataUpdateService.java` 约 30 处 `RUNNING/CANCELLED/SUCCESS/FAILED`（如 264/380/418/484/495/505/545/552/653/664/705/709/715/734）
- 因子状态：`FactorStatusChangedEvent.java:33`（ACTIVE/DEGRADED）、`StockScreenService.java:1031`（`fd.getStatus().name().equals("ACTIVE")`）
- 模拟交易：`IntradayMonitorService.java:951/1014`、`PortfolioRiskService.java:272`（`'RUNNING'`）

建议：抽 `common.enums` 下统一枚举（如 `BacktestStatus` / `TaskRunStatus` / `FactorLifecycle`），Java 用枚举、DB 存 code、前端 `useDict` 拉 label，消除字符串散落。

### B. 【中】枚举集中化不足
`common/src/main/java/com/quant/platform/common/enums/` 目前**仅有 `ResourceType.java`**。所有业务状态/类型常量未共享到 common，各业务包自行维护字符串或私有枚举，跨模块（尤其前端）无法复用。

建议：随缺口 A 一并把共享枚举收口到 `common.enums`。

### C. 【低·收尾】前端页面内按钮权限
路由级已动态化，但**页面内**写按钮此前有 4 处缺失（已修复）；建议后续对所有写操作按钮做一次全量清单核对，纳入"权限-按钮"回归测试。

### D. 【低】`/api/test/**` 依赖 profile 开关
已改为仅 dev/local 放行，生产需登录。建议：生产环境若有 CI 健康检查依赖该路径，改用 `/actuator/health` 等标准端点，彻底移除 test 匿名入口。

---

## 四、优先级行动建议

| 优先级 | 行动 | 收益 |
|---|---|---|
| P0 | 缺口 A：抽共享状态枚举，替换硬编码字符串（先 Backtest/DataUpdate 两处高频） | 消除全库字符串漂移、便于字典 label 统一 |
| P1 | 缺口 B：枚举收口 `common.enums` + 前端 `useDict` 对齐 | 跨模块口径一致 |
| P2 | 缺口 C：全量按钮权限回归核对 | 收敛越权暴露面 |
| P3 | 缺口 D：生产移除 test 匿名入口 | 收紧攻击面 |

---

*生成依据：对 `frontend/src`、`stock-service/**`、`platform-core/**`、`common/**` 的静态扫描（RequireAuth、DataPermissionService、ResourceOptionProvider、DictService、业务状态字符串分布）。所有引用均可在仓库内定位复核。*
