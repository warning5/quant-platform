# 量化因子策略平台

[![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-6DB33F?logo=springboot)](https://spring.io/projects/spring-boot)
[![React](https://img.shields.io/badge/React-18-61DAFB?logo=react)](https://react.dev/)
[![Node.js](https://img.shields.io/badge/Node.js-18+-339933?logo=nodedotjs)](https://nodejs.org/)
[![Maven](https://img.shields.io/badge/Maven-3.8+-C71A36?logo=apachemaven)](https://maven.apache.org/)
[![MySQL](https://img.shields.io/badge/MySQL-8.0+-4479A1?logo=mysql)](https://dev.mysql.com/)
[![License](https://img.shields.io/badge/license-MIT-blue)](./LICENSE)

基于 **Java 21 + Spring Boot 3 + React 18** 构建的企业级量化因子构建、管理、测试与策略回测评估平台。

## 目录

- [功能架构](#功能架构)
- [核心能力](#核心能力)
- [快速启动](#快速启动)
- [API 文档](#api-文档)
- [自定义因子开发](#自定义因子开发)
- [高级功能](#高级功能)
  - [因子衰减分析](#因子衰减分析)
  - [因子权重优化](#因子权重优化)
  - [因子相关性分析](#因子相关性分析)
  - [多策略对比](#多策略对比)
  - [参数优化（网格搜索）](#参数优化网格搜索)
  - [蒙特卡洛模拟](#蒙特卡洛模拟)
- [数据库支持](#数据库支持)
- [项目结构](#项目结构)
- [更多文档](#更多文档)

## 功能架构

```
quant-platform/
├── backend/                    # Spring Boot 3 后端 (Java 21)
│   ├── factor/                 # 因子管理模块
│   │   ├── domain/             # 实体：FactorDefinition, FactorValue, FactorTestReport
│   │   ├── engine/             # 因子计算引擎（内置因子 + Groovy脚本因子）
│   │   ├── service/            # FactorService
│   │   └── controller/         # FactorController
│   ├── strategy/               # 策略管理模块
│   │   ├── domain/             # StrategyDefinition
│   │   ├── engine/             # StrategySignalGenerator 接口
│   │   └── service/            # StrategyService
│   ├── backtest/               # 回测引擎模块
│   │   ├── domain/             # BacktestTask, BacktestReport
│   │   ├── engine/             # BacktestEngine（核心）
│   │   └── service/            # BacktestService
│   ├── market/                 # 市场行情数据模块
│   │   ├── domain/             # MarketDailyBar（DTO，统一行情数据结构）
│   │   └── service/            # MarketDataService（含演示数据）
│   └── common/                 # 通用组件
│       ├── config/             # Web/WebSocket 配置
│       ├── dto/                # ApiResponse, PageRequest
│       └── exception/          # 统一异常处理
└── frontend/                   # React 18 前端
    └── src/pages/
        ├── Dashboard.js        # 系统总览
        ├── factors/            # 因子列表、详情、编辑器
        ├── strategies/         # 策略列表、详情、编辑器
        └── backtest/           # 回测列表、创建、报告
```

## 核心能力

### 因子管理

- **8 个内置因子**：MOM20/MOM60（动量）、VOL20（波动率）、TURN20（换手率）、SIZE（市值）、RSI5、BOLL_POS（布林带位置）、VPCORR20（量价相关）
- **Groovy 脚本自定义因子**：实时语法验证、模板支持、沙箱执行
- **因子测试（IC 分析）**：IC 序列、ICIR、IC 正比率、RankIC、分层回测、多空组合收益
- **因子衰减分析**：分析因子预测能力的持续性，包括有效期、半衰期、衰减系数等指标
- **因子相关性分析**：Pearson 相关系数矩阵，热力图可视化展示，指导因子组合选择
- **因子值归一化**：Z-Score 标准化 + 横截面百分位排名

### 策略管理

- **3 个演示策略**：多因子选股、动量选股、RSI+布林带自定义脚本策略
- **策略类型**：因子多头、多空、市场中性、动量、均值回归、自定义脚本
- **风控参数**：止损/止盈比例、最大回撤控制
- **调仓频率**：日频 / 周频 / 月频 / 季频

### 回测引擎

- 事件驱动历史模拟，支持手续费（默认万三）+ 滑点
- 等权 / 因子加权仓位分配
- **绩效指标**：总收益、年化收益、最大回撤、夏普比率、索提诺比率、卡玛比率、年化波动率、信息比率、胜率
- WebSocket 实时进度推送
- 完整交易记录、月度收益、净值/回撤曲线
- **多策略对比**：2~8 个已完成回测横向对比，多曲线净值图 + 六维雷达图 + 详细指标表
- **参数优化（网格搜索）**：笛卡尔积穷举参数空间，异步并发执行，支持 Sharpe/年化收益/Calmar 三种目标函数，可视化热力图
- **蒙特卡洛模拟**：Bootstrap 重采样 200~1000 次，预测净值置信区间、VaR/CVaR、年化收益率与最大回撤分布

## 快速启动

### 前置条件

| 依赖 | 版本要求 | 说明 |
|------|---------|------|
| Java | 21+ | [下载 Temurin 21](https://adoptium.net/) |
| Maven | 3.8+ | 后端构建工具 |
| Node.js | 18+ | 前端运行环境 |
| MySQL | 8.0+ | 数据库（可选，默认使用内存模式） |

### 1. 启动后端

```bash
cd backend
mvn spring-boot:run
```

或双击 `start-backend.bat`（Windows）

- 后端地址：`http://localhost:8080/api`
- Swagger UI：`http://localhost:8080/api/swagger-ui.html`

### 2. 启动前端

```bash
cd frontend
npm install
npm start
```

或双击 `start-frontend.bat`（Windows）

- 前端地址：`http://localhost:3000`

### 演示数据

系统启动后自动生成：

- **10 只 A 股标的** 约 3 年模拟日线数据（2022-01-04 ~ 2024-12-31）
- **8 个内置因子**定义
- **3 个演示策略**

### 第一次使用建议

1. 访问「因子管理」→ 选择因子 → 「运行因子测试」（先触发因子计算）
2. 访问「回测管理」→「新建回测」→ 选择策略，设置参数，开始回测
3. 等待完成后查看详细绩效报告

## API 文档

启动后端后访问 `http://localhost:8080/api/swagger-ui.html` 查看完整 REST API 文档。

### 因子管理

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/factors` | 获取因子列表 |
| `POST` | `/api/factors` | 创建因子 |
| `POST` | `/api/factors/{id}/compute` | 触发因子计算 |
| `POST` | `/api/factors/{id}/test` | 触发因子测试（IC 分析 + 衰减分析） |
| `GET` | `/api/factors/correlation` | 因子相关性分析 |
| `GET` | `/api/factors/weight-optimize` | 因子权重优化（EQUAL/MARKOWITZ/RISK_PARITY） |

### 策略与回测

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/strategies` | 获取策略列表 |
| `POST` | `/api/strategies` | 创建策略 |
| `POST` | `/api/backtests` | 创建回测任务 |
| `GET` | `/api/backtests/{taskId}/report` | 获取回测报告 |
| `POST` | `/api/backtests/compare` | 多策略对比（传入 taskIds 列表） |
| `POST` | `/api/backtests/param-optimize/submit` | 提交参数优化任务 |
| `GET` | `/api/backtests/param-optimize/{jobId}` | 查询参数优化进度与结果 |
| `GET` | `/api/backtests/{taskId}/montecarlo` | 蒙特卡洛模拟 |

## 自定义因子开发

使用 Groovy 脚本开发自定义因子：

```groovy
// 自定义：3日VWAP偏离度
if (n < 3) return null

def vwapSum = 0.0
def volSum = 0.0
history[(n-3)..(n-1)].each { bar ->
    def vwap = (bar.high + bar.low + bar.close) / 3
    vwapSum += vwap.toDouble() * bar.vol.toDouble()
    volSum += bar.vol.toDouble()
}
if (volSum == 0) return null
def vwap3 = vwapSum / volSum
return (bar.close.toDouble() - vwap3) / vwap3
```

在「因子管理 → 新建因子 → 计算脚本」中输入脚本，点击「验证脚本」检查语法，保存后即可使用。

## 高级功能

### 因子衰减分析

评估因子预测能力随时间的衰减情况，帮助优化调仓频率和持仓周期。

**核心指标**：

| 指标 | 说明 |
|------|------|
| 有效期 | IC 绝对值首次低于 0.02 的期数，越长预测能力越持久 |
| 半衰期 | IC 降至初始值 50% 所需的期数，反映衰减速度 |
| 衰减系数 | 指数衰减模型 λ 值，越大衰减越快 |
| 拟合优度 R² | 衰减模型的解释程度，越接近 1 越可靠 |

**使用场景**：选择有效期长的因子进行长期投资；根据衰减速度确定调仓频率；评估因子在持有期的稳定性。

> 访问路径：因子详情页 → 因子检测标签 → 选中检测报告 → 查看因子衰减分析卡片

### 因子权重优化

基于因子历史 `rank_value` 数据，支持三种优化方法自动计算最优因子权重组合。

| 方法 | 算法 | 特点 |
|------|------|------|
| 等权（EQUAL） | 平均分配 | 作为基准 |
| Markowitz | 梯度下降求最大 Sharpe | 额外生成 30 个点的有效前沿曲线 |
| 风险平价（RISK_PARITY） | 牛顿法迭代 | 使每个因子对组合风险贡献相等 |

**可视化**：权重环形饼图、因子相关系数热力图、Markowitz 有效前沿曲线

**约束**：至少选择 2 个因子；历史数据有效对齐点不少于 10 个。

> 访问路径：左侧菜单 → 因子管理 → 权重优化

### 因子相关性分析

计算因子之间的 Pearson 相关系数矩阵，识别因子重叠程度，避免组合冗余。

| 相关系数 | 等级 | 建议 |
|---------|------|------|
| \|r\| ≥ 0.7 | 强相关 | 高度重叠，不建议同时使用 |
| 0.4 ≤ \|r\| < 0.7 | 中等相关 | 有一定重叠，需谨慎组合 |
| 0.2 ≤ \|r\| < 0.4 | 弱相关 | 有一定独立性，可以组合 |
| \|r\| < 0.2 | 无相关 | 独立，适合组合使用 |

**使用建议**：优先选择相关性低（< 0.2）的因子组合以提升策略稳定性；避免使用相关性超过 0.7 的因子；动量+价值+质量等不同类型的因子通常相关性较低。

> 访问路径：左侧菜单 → 因子管理 → 因子相关性

### 多策略对比

同时对比 2~8 个已完成回测任务的绩效表现，多维度快速识别最优策略。

- **多曲线净值叠加图**：各策略涨跌幅同轴对比，颜色自动区分
- **六维综合能力雷达图**：年化收益 / 夏普 / 胜率 / 信息比率 / 卡玛 / 稳定性
- **核心指标速览卡**：年化收益、最大回撤、Sharpe、胜率，排名第一标注奖杯
- **详细指标对比表**：13 列指标横向比较，按年化收益自动降序排名

> 访问路径：左侧菜单 → 回测管理 → 策略对比

### 参数优化（网格搜索）

通过网格搜索自动寻找最优策略参数组合。

**可优化参数**：`maxPositionCount`（持仓数量）、`stopLossPct`（止损比例）、`stopProfitPct`（止盈比例）、`initialCapital`（初始资金）

**目标函数**：最大化 Sharpe 比率 / 年化收益率 / Calmar 比率

**可视化**：进度卡片、最优参数金色卡片、双参数热力图、全部结果表（最多 50 组）

> 访问路径：左侧菜单 → 回测管理 → 参数优化

### 蒙特卡洛模拟

基于已完成回测的历史日收益率，使用 Bootstrap 有放回重采样方法，评估策略的未来鲁棒性和尾部风险。

**核心输出**：

- 净值置信区间图（5%/25%/50%/75%/95% 五条分位数曲线）
- 正收益概率、VaR（95%）、CVaR（95%）
- 年化收益率分布直方图（含 P5/P50/P95）
- 最大回撤分布直方图（含 P5/P50/P95）

**参数设置**：

| 参数 | 可选值 |
|------|--------|
| 模拟次数 | 200 / 500 / 1000 |
| 预测期 | 1 季度（63天）/ 半年（126天）/ 1年（252天）/ 2年（504天） |

> 前置要求：历史净值数据至少 20 个交易日 | 访问路径：回测详情页 → 蒙特卡洛模拟 Tab

## 数据库支持

系统默认使用 MySQL 数据库，支持数据持久化和备份。

**配置步骤**：

1. 创建数据库：
```sql
CREATE DATABASE stock CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

2. 执行表结构脚本：
```sql
source schema.sql
```

3. 编辑 `backend/src/main/resources/application.yml`，设置数据库连接信息（如需要）

详细配置说明请参考 [backend/MYSQL_CONFIG.md](backend/MYSQL_CONFIG.md)

## 项目结构

```
quant-platform/
├── backend/                    # Spring Boot 后端
│   ├── factor/                 # 因子管理
│   ├── strategy/               # 策略管理
│   ├── backtest/               # 回测引擎
│   ├── market/                 # 行情数据
│   └── common/                 # 通用组件
├── frontend/                   # React 前端
│   └── src/pages/              # 页面组件
├── scripts/                    # Python 数据脚本
├── docs/                       # 项目文档
├── start-backend.bat           # 后端启动脚本
├── start-frontend.bat          # 前端启动脚本
└── README.md
```

## 更多文档

| 文档 | 说明 |
|------|------|
| [FEATURE_UPDATE.md](FEATURE_UPDATE.md) | 详细的功能更新说明 |
| [QUICK_START.md](QUICK_START.md) | 新功能快速上手教程 |
| [backend/MYSQL_CONFIG.md](backend/MYSQL_CONFIG.md) | MySQL 数据库配置详解 |
