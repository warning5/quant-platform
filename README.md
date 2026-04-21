# 量化因子策略平台

基于 **Java 21 + Spring Boot 3 + React 18** 构建的企业级量化因子构建、管理、测试与策略回测评估平台。

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
- **8个内置因子**：MOM20/MOM60（动量）、VOL20（波动率）、TURN20（换手率）、SIZE（市值）、RSI5、BOLL_POS（布林带位置）、VPCORR20（量价相关）
- **Groovy脚本自定义因子**：实时语法验证、模板支持、沙箱执行
- **因子测试（IC分析）**：IC序列、ICIR、IC正比率、RankIC、分层回测、多空组合收益
- **因子衰减分析（因子有效期）**：分析因子预测能力的持续性，包括有效期、半衰期、衰减系数等指标
- **因子相关性分析**：计算因子间的Pearson相关系数矩阵，通过热力图可视化展示，指导因子组合选择
- **因子值归一化**：Z-Score 标准化 + 横截面百分位排名

### 策略管理
- **3个演示策略**：多因子选股、动量选股、RSI+布林带自定义脚本策略
- **策略类型**：因子多头、多空、市场中性、动量、均值回归、自定义脚本
- **风控参数**：止损/止盈比例、最大回撤控制
- **调仓频率**：日频/周频/月频/季频

### 回测引擎
- 事件驱动历史模拟，支持手续费（默认万三）+滑点
- 等权/因子加权 仓位分配
- **绩效指标**：总/年化收益、最大回撤、夏普比率、索提诺比率、卡玛比率、年化波动率、信息比率、胜率
- WebSocket 实时进度推送
- 完整交易记录、月度收益、净值/回撤曲线
- **多策略对比**：2~8 个已完成回测横向对比，多曲线净值图 + 六维雷达图 + 详细指标表
- **参数优化（网格搜索）**：笛卡尔积穷举参数空间，异步并发执行，支持 Sharpe/年化收益/Calmar 三种目标函数，可视化热力图
- **蒙特卡洛模拟**：Bootstrap 重采样 200~1000 次，预测净值置信区间、VaR/CVaR、年化收益率与最大回撤分布

## 快速启动

### 前置条件
- Java 21+（[下载 Temurin 21](https://adoptium.net/)）
- Maven 3.8+
- Node.js 18+

### 1. 启动后端
```bash
cd backend
mvn spring-boot:run
```
或双击 `start-backend.bat`

- 后端地址：http://localhost:8080/api
- **Swagger UI**：http://localhost:8080/api/swagger-ui.html

### 2. 启动前端
```bash
cd frontend
npm install
npm start
```
或双击 `start-frontend.bat`

- 前端地址：http://localhost:3000

## 演示数据

系统启动后自动生成：
- **10只A股标的** 的约3年模拟日线数据（2022-01-04 ~ 2024-12-31）
- **8个内置因子** 定义
- **3个演示策略**

第一次使用建议步骤：
1. 访问「因子管理」→ 选择因子 → 「运行因子测试」（先触发因子计算）
2. 访问「回测管理」→「新建回测」→ 选择策略，设置参数，开始回测
3. 等待完成后查看详细绩效报告

## API 文档
访问 http://localhost:8080/api/swagger-ui.html 查看完整 REST API 文档。

主要接口：
- `GET/POST /api/factors` - 因子管理
- `POST /api/factors/{id}/compute` - 触发因子计算
- `POST /api/factors/{id}/test` - 触发因子测试（IC分析+衰减分析）
- `GET /api/factors/correlation` - 因子相关性分析
- `GET /api/factors/weight-optimize` - 因子权重优化（EQUAL/MARKOWITZ/RISK_PARITY）
- `GET/POST /api/strategies` - 策略管理
- `POST /api/backtests` - 创建回测任务
- `GET /api/backtests/{taskId}/report` - 获取回测报告
- `POST /api/backtests/compare` - 多策略对比（传入 taskIds 列表）
- `POST /api/backtests/param-optimize/submit` - 提交参数优化任务（返回 jobId）
- `GET /api/backtests/param-optimize/{jobId}` - 查询参数优化进度与结果
- `GET /api/backtests/{taskId}/montecarlo` - 蒙特卡洛模拟（支持 simulations/horizonDays 参数）

## 自定义因子开发

使用 Groovy 脚本开发自定义因子，示例：

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

### 因子衰减分析（因子有效期）

评估因子预测能力随时间的衰减情况，帮助优化调仓频率和持仓周期。

**核心指标**：
- **有效期（期数）**：IC绝对值首次低于0.02的期数，越长表示因子预测能力越持久
- **半衰期（期数）**：IC降至初始值50%所需的期数，反映衰减速度
- **衰减系数**：指数衰减模型 IC(t) = IC(0) * exp(-λ * t) 的系数λ，值越大衰减越快
- **拟合优度R²**：衰减模型的解释程度，越接近1表示拟合越可靠

**使用场景**：
- 选择有效期长的因子进行长期投资策略
- 根据衰减速度确定调仓频率（衰减快需频繁调仓）
- 评估因子在持有期的稳定性

**访问路径**：因子详情页 → 因子检测标签 → 选中检测报告 → 查看因子衰减分析卡片

### 因子权重优化

基于因子历史 `rank_value` 数据，支持三种优化方法自动计算最优因子权重组合，用于构建多因子投资组合。

**三种优化方法**：
- **等权（EQUAL）**：各因子平均分配权重，作为基准
- **Markowitz（均值-方差）**：梯度下降求解最大化夏普比率的权重；额外生成 30 个点的有效前沿曲线
- **风险平价（RISK_PARITY）**：牛顿法迭代，使每个因子对组合风险的贡献相等

**可视化**：
- 权重环形饼图：各因子建议权重占比
- 因子相关系数热力图：因子间分散程度评估
- Markowitz 有效前沿图（Markowitz 专属）：风险-收益权衡曲线，散点颜色按 Sharpe 高低渐变

**约束**：至少选择 2 个因子；历史数据有效对齐点不少于 10 个。

**访问路径**：左侧菜单 → 因子管理 → 权重优化

---

### 因子相关性分析

计算因子之间的Pearson相关系数矩阵，识别因子之间的重叠程度，避免因子组合中的冗余。

**相关性等级标准**：
- |r| ≥ 0.7：强相关 - 因子高度重叠，不建议同时使用
- 0.4 ≤ |r| < 0.7：中等相关 - 因子有一定重叠，需谨慎组合
- 0.2 ≤ |r| < 0.4：弱相关 - 因子有一定独立性，可以组合
- |r| < 0.2：无相关 - 因子独立，适合组合使用

**功能特点**：
- 交互式热力图可视化相关性矩阵
- 支持选择多个因子进行批量分析
- 可自定义日期范围进行历史相关性分析
- 详细数据表展示每对因子的相关系数和样本数

**使用建议**：
- 优先选择相关性低（<0.2）的因子组合以提高策略稳定性
- 避免同时使用相关性超过0.7的因子
- 不同类型的因子（动量+价值+质量）通常相关性较低，适合组合

**访问路径**：左侧菜单 → 因子管理 → 因子相关性

---

### 多策略对比

同时对比 2~8 个已完成回测任务的绩效表现，从多维度快速识别最优策略。

**可视化内容**：
- 多曲线净值叠加图：各策略涨跌幅同轴对比，颜色自动区分
- 六维综合能力雷达图：年化收益 / 夏普 / 胜率 / 信息比率 / 卡玛 / 稳定性
- 核心指标速览卡：年化收益、最大回撤、Sharpe、胜率，排名第一标注奖杯
- 详细指标对比表：13 列指标横向比较，按年化收益自动降序排名

**访问路径**：左侧菜单 → 回测管理 → 策略对比

---

### 参数优化（网格搜索）

通过网格搜索（Grid Search）自动寻找最优策略参数组合，避免手动试错。

**可优化参数**：
- `maxPositionCount`（持仓数量）
- `stopLossPct`（止损比例）
- `stopProfitPct`（止盈比例）
- `initialCapital`（初始资金）

**目标函数**：最大化 Sharpe 比率 / 年化收益率 / Calmar 比率

**可视化内容**：
- 进度卡片：实时显示状态、完成数/总计
- 最优参数卡片：金色边框标注最优组合及其得分
- 双参数热力图：两个参数轴 × 目标函数值的颜色深度
- 全部结果表：最多展示 50 组，支持按指标排序

**访问路径**：左侧菜单 → 回测管理 → 参数优化

---

### 蒙特卡洛模拟

基于已完成回测的历史日收益率，使用 Bootstrap 有放回重采样方法，评估策略的未来鲁棒性和尾部风险。

**核心输出**：
- 净值置信区间图：5%/25%/50%/75%/95% 五条分位数曲线
- 正收益概率、VaR（95%）、CVaR（95%）
- 年化收益率分布直方图（含 P5/P50/P95）
- 最大回撤分布直方图（含 P5/P50/P95）

**参数设置**：
- 模拟次数：200 / 500 / 1000（次）
- 预测期：1 季度（63天）/ 半年（126天）/ 1年（252天）/ 2年（504天）

**前置要求**：历史净值数据至少 20 个交易日

**访问路径**：回测详情页 → 蒙特卡洛模拟 Tab

## 数据库支持

### MySQL 数据库（默认）
- 系统默认使用 MySQL 数据库
- 需要预先安装和配置 MySQL 8.0+
- 支持数据持久化和备份
- 数据库名称：`stock`

**配置方法**：

1. 创建数据库：
```sql
CREATE DATABASE stock CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

2. 执行表结构脚本：
```sql
source schema.sql
```

3. 编辑 `backend/src/main/resources/application.yml`，设置数据库连接信息（如需要）

详细配置说明请参考 `backend/MYSQL_CONFIG.md`

## 更多文档

- **功能更新说明**：[FEATURE_UPDATE.md](FEATURE_UPDATE.md) - 详细的功能更新文档（含 P1 新功能）
- **快速使用指南**：[QUICK_START.md](QUICK_START.md) - 新功能快速上手教程（含 P1 新功能）
- **MySQL 配置指南**：[backend/MYSQL_CONFIG.md](backend/MYSQL_CONFIG.md) - MySQL 数据库配置详解
