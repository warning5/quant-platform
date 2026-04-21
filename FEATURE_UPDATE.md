# 功能更新说明

---

## v1.2.0 — P1 高级分析功能（2026-04-20）

本次更新新增了四项高级量化分析功能，大幅提升平台的策略研究和风险评估能力。

---

## 4. 多策略对比

### 功能说明
允许用户同时选择 2~8 个已完成的回测任务，从净值曲线、综合能力和详细指标三个维度进行横向对比，快速识别最优策略。

### 可视化内容
- **多曲线净值对比图**：各策略涨跌幅同轴展示，ECharts 折线图，颜色自动分配（最多 8 种）
- **六维综合能力雷达图**：年化收益 / 夏普比率 / 胜率 / 信息比率 / 卡玛比率 / 稳定性（1-最大回撤）
- **核心指标速览卡**：每策略展示年化收益、最大回撤、Sharpe、胜率；排名第 1 标注奖杯图标
- **详细指标对比表**：共 13 列指标横向对比，按年化收益自动降序排名，首行高亮显示

### 实现细节

#### 后端实现
- **新增服务**：`CompareService.java`
- **API 接口**：`POST /api/backtests/compare`
- **入参**：`List<Long> taskIds`（2~8 个已完成回测任务 ID）
- **返回字段**：
  - `metrics`：各策略指标行（含 rank 排名）
  - `curves`：各策略净值曲线数据
  - `count`：成功获取的策略数量
- **核心逻辑**：批量拉取已完成回测报告，合并 25 个指标字段，按年化收益降序排名

#### 前端实现
- **新页面**：`BacktestCompare.js`
- **路由**：`/backtest-compare`
- **菜单**：回测管理 > 策略对比

### 使用步骤
1. 确保已有至少 2 个**已完成（COMPLETED）**状态的回测任务
2. 进入「回测管理 > 策略对比」
3. 在多选下拉框中选择 2~8 个回测任务
4. 点击「开始对比」
5. 依次查看净值曲线图 → 雷达图 → 速览卡片 → 详细指标表

---

## 5. 参数优化（网格搜索）

### 功能说明
通过笛卡尔积网格搜索（Grid Search）枚举所有参数组合，异步并发执行回测，以目标函数自动找出最优参数，并以热力图可视化展示。

### 可优化参数

| 参数名 | 说明 |
|--------|------|
| `maxPositionCount` | 最大持仓数量（整数） |
| `stopLossPct` | 止损比例（小数，如 0.1 = 10%） |
| `stopProfitPct` | 止盈比例（小数，如 0.2 = 20%） |
| `initialCapital` | 初始资金（元） |

### 目标函数
- `sharpeRatio`（默认）：最大化夏普比率
- `annualReturn`：最大化年化收益率
- `calmarRatio`：最大化 Calmar 比率（年化收益/最大回撤）

### 可视化内容
- **进度卡片**：实时状态（PENDING/RUNNING/COMPLETED/FAILED）、完成数/总计
- **最优参数卡片**：金色边框展示最优组合，含目标得分、年化收益、最大回撤
- **参数热力图**：取前两个参数作为轴，目标函数分值作为颜色深度（绿→黄→红）
- **全部结果表**：最多展示 50 组，支持按得分降序排列

### 实现细节

#### 后端实现
- **新增服务**：`ParamOptimizeService.java`
- **API 接口**：
  - `POST /api/backtests/param-optimize/submit` — 提交任务，返回 `{ jobId }`
  - `GET /api/backtests/param-optimize/{jobId}` — 查询状态（前端每 3 秒轮询一次）
- **任务状态**：`PENDING → RUNNING → COMPLETED / FAILED`
- **并发控制**：信号量限流，默认并行度 3（最大 5）
- **优化任务存储**：`ConcurrentHashMap` 内存存储，不写数据库

#### 前端实现
- **新页面**：`ParamOptimize.js`
- **路由**：`/param-optimize`
- **菜单**：回测管理 > 参数优化

### 请求示例
```json
POST /api/backtests/param-optimize/submit
{
  "strategyId": 1,
  "startDate": "2023-01-01",
  "endDate": "2025-01-01",
  "initialCapital": 1000000,
  "benchmarkCode": "000300.SH",
  "objective": "sharpeRatio",
  "maxConcurrent": 3,
  "paramGrid": [
    { "name": "maxPositionCount", "values": [10, 15, 20, 25, 30] },
    { "name": "stopLossPct", "values": [0.05, 0.1, 0.15] }
  ]
}
```
以上示例将运行 5×3 = **15 次回测**。

### 使用建议
- 总组合数建议控制在 50 次以内，避免耗时过长
- 推荐先用较少的参数值快速试探，再在最优区间附近细化

---

## 6. 蒙特卡洛模拟

### 功能说明
基于已完成回测的历史日收益率，使用 Bootstrap 有放回重采样，生成多条未来净值模拟路径，统计置信区间及风险指标分布，用于评估策略在样本外的鲁棒性和尾部风险。

### 核心输出指标

| 指标 | 说明 |
|------|------|
| 正收益概率 | 模拟路径中期末净值 > 1 的比例 |
| VaR（95%） | 95% 置信水平下最大可能损失 |
| CVaR（95%） | 超出 VaR 情形下的平均损失（尾部风险） |
| 中位数净值 | 期末净值 P50 |
| 年化收益 P50 | 模拟路径年化收益率中位数 |
| 最大回撤 P50 | 模拟路径最大回撤中位数 |

### 可视化内容
- **净值置信区间图**：5%/25%/50%/75%/95% 五条分位数曲线，面积填充区分上下界
- **年化收益率分布直方图**：20 个分箱，标注 P5/P50/P95 分位数
- **最大回撤分布直方图**：20 个分箱，标注 P5/P50/P95 分位数

### 参数设置

| 参数 | 可选值 | 默认值 | 说明 |
|------|--------|--------|------|
| 模拟次数 | 200 / 500 / 1000 | 500 | 越高精度越准，但耗时更长 |
| 预测期 | 63 / 126 / 252 / 504 | 252 | 分别对应 1季度/半年/1年/2年 |

### 实现细节

#### 后端实现
- **新增服务**：`MonteCarloService.java`
- **API 接口**：`GET /api/backtests/{taskId}/montecarlo?simulations=500&horizonDays=252`
- **算法**：Bootstrap 有放回重采样，固定随机种子（42）保证可重现，净值下限 0.001 防溢出
- **前置约束**：历史净值数据至少 20 个交易日
- **置信区间降采样**：输出点数自动降采样至 ≤252 个，保证可视化性能

#### 前端实现
- **新组件**：`MonteCarloPanel.js`（嵌入回测报告详情页）
- **位置**：回测详情 → 蒙特卡洛模拟 Tab

### 注意事项
- 蒙特卡洛结果是基于历史数据的统计推断，**不代表未来实际表现**
- VaR/CVaR 越低，表示策略尾部风险越大（损失可能越严重）
- 正收益概率高于 60% 通常可视为策略具备一定正期望

---

## 7. 因子权重优化

### 功能说明
基于因子历史 `rank_value` 截面数据，支持三种优化方法自动计算最优因子权重分配，用于构建稳健的多因子投资组合。

### 三种优化方法

| 方法 | 算法 | 适用场景 |
|------|------|----------|
| **等权（EQUAL）** | 各因子 1/n | 基准对比，无历史偏差 |
| **Markowitz（均值-方差）** | 梯度下降最大化夏普比率（5000次迭代） | 追求高风险调整收益 |
| **风险平价（RISK_PARITY）** | 牛顿法使每因子等风险贡献（3000次迭代） | 稳健分散风险 |

### 输出内容

| 字段 | 说明 |
|------|------|
| `weights` | 各因子推荐权重（含每因子年化收益、波动率） |
| `portfolioReturn` | 组合预期年化收益率 |
| `portfolioVolatility` | 组合预期年化波动率 |
| `sharpeRatio` | 组合预期夏普比率（无风险利率 3%） |
| `correlationMatrix` | 因子相关系数矩阵 |
| `efficientFrontier` | Markowitz 有效前沿（仅 Markowitz 方法，30 个点） |

### 可视化内容
- **权重环形饼图**：各因子权重占比
- **权重明细表**：含进度条可视化权重，以及每因子年化收益和波动率
- **因子相关系数热力图**：绿-白-红色阶，≤8 个因子时显示数值标签
- **有效前沿散点图**（Markowitz 专属）：x=波动率，y=收益率，颜色按 Sharpe 高低从红到绿渐变

### 实现细节

#### 后端实现
- **新增服务**：`FactorWeightOptimizeService.java`
- **API 接口**：`GET /api/factors/weight-optimize?factorCodes=MOM20,RSI5,VOL20&startDate=2024-01-01&endDate=2025-12-31&method=MARKOWITZ`
- **数据来源**：从 `factor_value.rank_value` 取各日截面中位数，差分后作为日收益代理
- **约束**：至少 2 个因子，有效对齐数据点不少于 10 个

#### 前端实现
- **新页面**：`FactorWeightOptimize.js`（含 `FactorWeightOptimizePanel` 组件，支持 `defaultFactorCodes` prop）
- **路由**：`/factor-weight-optimize`
- **菜单**：因子管理 > 权重优化

### 使用建议
- 先通过「因子相关性分析」筛选出低相关性的因子组合，再进行权重优化
- Markowitz 方法可能因过拟合历史数据而在样本外表现不稳定，建议结合风险平价方法对比
- 有效前沿图中颜色越绿的散点 Sharpe 越高，可参考其对应的风险-收益位置

---

## 版本历史

### v1.2.0（2026-04-20）
- ✨ 新增多策略对比功能（BacktestCompare）
- ✨ 新增参数优化（网格搜索）功能（ParamOptimize）
- ✨ 新增蒙特卡洛模拟功能（MonteCarloPanel，嵌入回测报告）
- ✨ 新增因子权重优化功能（FactorWeightOptimize，支持 EQUAL/MARKOWITZ/RISK_PARITY）
- 🔧 BacktestEngine 新增同步执行方法 `runBacktestSync()`
- 🔧 BacktestController 新增对比、蒙特卡洛、参数优化接口
- 🔧 FactorController 新增权重优化接口

---

## v1.1.0 — 初始功能（2026-03-17）

本次更新为 `quant-platform` 添加了以下三个重要功能:

## 1. 因子衰减分析(因子有效期)

### 功能说明
分析因子值与未来不同期数收益的相关性衰减规律,评估因子的有效持续时间和衰减速度。

### 核心指标
- **因子有效期(期数)**: IC绝对值首次低于阈值0.02的期数
- **半衰期(期数)**: IC降至初始值50%所需的期数
- **衰减系数**: 拟合指数衰减模型的系数,值越大衰减越快
- **拟合优度R²**: 拟合模型的解释程度,越接近1拟合越好

### 实现细节

#### 后端实现
- **新增字段** (FactorTestReport):
  - `decay_periods`: 因子有效期
  - `half_life_periods`: 半衰期
  - `decay_coefficient`: 衰减系数
  - `decay_r_squared`: 拟合优度R²
  - `decay_series_json`: 衰减序列JSON

- **核心算法** (FactorComputeEngine.computeFactorDecayAnalysis):
  - 计算滞后1-10期的IC值
  - 使用指数衰减模型拟合: IC(t) = IC(0) * exp(-λ * t)
  - 线性回归拟合对数变换后的数据
  - 计算R²评估拟合优度

#### 前端实现
- **图表展示**: ECharts折线图,展示IC绝对值和IC值随期数的变化趋势
- **阈值标记**: 在IC=0.02处添加虚线标记
- **数据展示**: 显示有效期、半衰期、衰减系数、R²等关键指标
- **说明文档**: 详细的指标解释和使用建议

### 使用建议
- 有效期长的因子更适合长期持有策略
- 衰减快的因子需要频繁调仓
- 可根据衰减分析优化调仓频率

---

## 2. 因子相关性分析

### 功能说明
计算因子之间的Pearson相关系数矩阵,评估因子之间的重叠程度和独立性,指导因子组合选择。

### 核心功能
- **热力图展示**: 可视化因子相关性矩阵
- **详细数据表**: 显示每对因子的相关系数、相关性强度、样本数
- **多因子选择**: 支持同时选择多个因子进行相关性分析
- **日期范围筛选**: 可指定分析的时间区间

### 实现细节

#### 后端实现
- **新增服务**: FactorCorrelationService
- **API接口**: GET /api/factors/correlation
- **核心算法**:
  - 提取同一日期的因子值对
  - 计算Pearson相关系数
  - 过滤样本数不足(<10)的组合

#### 前端实现
- **新页面**: FactorCorrelation.js
- **路由配置**: /factor-correlation
- **菜单项**: 因子管理 > 因子相关性

### 相关性等级
- |r| ≥ 0.7: 强相关 - 因子高度重叠,不建议同时使用
- 0.4 ≤ |r| < 0.7: 中等相关 - 因子有一定重叠,需谨慎组合
- 0.2 ≤ |r| < 0.4: 弱相关 - 因子有一定独立性,可以组合
- |r| < 0.2: 无相关 - 因子独立,适合组合使用

### 使用建议
- 选择相关性较低的因子组合可提高策略稳定性
- 避免同时使用相关性超过0.7的因子
- 不同类型的因子(动量、价值、质量等)通常相关性较低

---

## 3. MySQL数据库支持

### 功能说明
平台现在支持使用MySQL数据库作为生产环境的持久化存储,默认仍使用H2数据库用于开发测试。

### 配置方法

#### 1. 创建数据库
```sql
CREATE DATABASE quantdb CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

#### 2. 修改配置
编辑 `backend/src/main/resources/application-mysql.yml`:
```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/quantdb?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
    driver-class-name: com.mysql.cj.jdbc.Driver
    username: root
    password: your_password
```

#### 3. 启动应用
使用MySQL profile启动:
```bash
mvn spring-boot:run -Dspring-boot.run.profiles=mysql
```

### 数据库变更

#### 新增字段 (factor_test_report表)
```sql
-- 因子衰减分析字段
ALTER TABLE factor_test_report
ADD COLUMN decay_periods DECIMAL(10, 2) COMMENT '因子有效期(期数)',
ADD COLUMN half_life_periods DECIMAL(10, 2) COMMENT '因子半衰期(期数)',
ADD COLUMN decay_coefficient DECIMAL(10, 6) COMMENT '因子衰减系数',
ADD COLUMN decay_r_squared DECIMAL(10, 6) COMMENT '因子衰减拟合优度R²',
ADD COLUMN decay_series_json TEXT COMMENT '因子衰减序列JSON';
```

### 性能优化建议

#### MySQL配置优化
```ini
[mysqld]
innodb_buffer_pool_size = 2G
innodb_log_file_size = 512M
innodb_thread_concurrency = 0
max_connections = 200
```

#### JPA配置优化
```yaml
spring:
  jpa:
    properties:
      hibernate:
        jdbc:
          batch_size: 50
          order_inserts: true
          order_updates: true
```

---

## 数据库Schema更新

### 更新文件
- `backend/src/main/resources/schema.sql` - 已更新包含新字段

### 自动迁移
使用Spring Boot的JPA自动DDL功能(`ddl-auto: update`)会自动更新表结构,无需手动执行SQL。

---

## 访问路径

### 因子衰减分析
- **位置**: 因子详情页 > 因子检测Tab > 选中报告 > 因子衰减分析卡片
- **触发**: 运行因子检测后自动计算和展示

### 因子相关性分析
- **位置**: 因子管理 > 因子相关性
- **访问**: http://localhost:8080/factor-correlation

---

## 技术栈

### 后端
- Java 21
- Spring Boot 3.2.3
- Spring Data JPA
- MySQL Connector/J 8.x (已包含依赖)

### 前端
- React 18
- Ant Design 5.x
- ECharts 5.x
- Axios

---

## 注意事项

1. **因子衰减分析**需要至少10期以上的IC数据才能进行拟合
2. **因子相关性分析**建议至少选择2个因子
3. **MySQL迁移**前请备份数据
4. **生产环境**建议使用MySQL而非H2
5. **密码安全**: 生产环境请使用环境变量或配置中心管理数据库密码

---

## 版本信息
- **更新日期**: 2026-03-17
- **版本**: v1.1.0
- **兼容性**: 向下兼容,不影响现有功能

---

---

## 后续计划

1. 添加更多基本面因子
2. 支持因子组合优化算法
3. 添加因子 IC 分组衰减分析
4. 支持因子有效性回测
5. 优化大数据量下的计算性能
6. 参数优化持久化（写入数据库，支持历史查询）
7. 蒙特卡洛支持 GBM（几何布朗运动）模型作为替代采样方式
