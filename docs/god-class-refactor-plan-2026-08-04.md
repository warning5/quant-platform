# God Class 拆分方案

> 生成日期：2026-08-04
> 范围：`RecommendationService`(3,618 行)、`BacktestEngine`(2,426 行)
> 结论先行：拆分方向正确，但**必须先清理 `BacktestUtils` 的双轨实现**，否则拆分过程会静默改变回测数值。

---

## 一、现状盘点

### 1.1 实际体量排名（超过 1,500 行的类）

| 行数 | 类 | 截图是否提及 |
|---:|---|---|
| 4,082 | `stock/analysis/service/AnalysisService.java` | ❌ **未提及，实为最大** |
| 3,618 | `recommendation/service/RecommendationService.java` | ✅ |
| 2,452 | `factor/engine/FactorComputeEngine.java` | ❌ |
| 2,426 | `backtest/engine/BacktestEngine.java` | ✅ |
| 2,392 | `dataupdate/DataUpdateService.java` | ❌ |
| 2,316 | `stock/analysis/engine/TradingSignalEngine.java` | ❌ |
| 2,136 | `strategy/paper/PaperTradingService.java` | ❌ |
| 1,764 | `screen/service/StockScreenService.java` | ❌ |
| 1,660 | `backtest/service/FactorStyleAttributionService.java` | ❌ |
| 1,627 | `factor/service/FactorAnalysisService.java` | ❌ |

截图只点了 2 个，实际有 **10 个类超 1,500 行**。本方案先覆盖截图指出的 2 个，其余按同样范式后续处理。

### 1.2 God Class 的量化证据

| 指标 | `RecommendationService` | `BacktestEngine` | 健康阈值 |
|---|---:|---:|---:|
| 总行数 | 3,618 | 2,426 | < 800 |
| 构造器依赖数 | **24** | 16（含 9 个 `@Autowired(required=false)`） | < 8 |
| 最大单方法行数 | 444（`generateRecommendations`） | 571（`executeBacktest`） | < 80 |
| 超 200 行的方法数 | 4 | 3 | 0 |
| 内部静态常量表 | 3 张（`INDUSTRY_TO_SW_CODE` 等，约 160 行） | 0 | — |

`RecommendationService` 构造器 24 个参数（`RecommendationService.java:249-301`）是最刺眼的信号——它同时承担了选股、择时、行业轮动、因子加权、评分融合、定价、落库、查询、绩效追踪 9 类职责。

---

## 二、⚠️ 先决问题：`BacktestUtils` 双轨实现（P0，必须先修）

`backtest/engine/BacktestUtils.java` **已经存在**，说明之前启动过一次拆分但**没做完**，留下两套并存的实现：

| 方法 | `BacktestEngine` 私有版 | `BacktestUtils` 静态版 | 实际调用情况 | 状态 |
|---|---|---|---|---|
| `calcFee` | 行 1597 | 行 37 | **两者都在用**：止损路径走 Utils(449/510)，调仓路径走私有(584/598/932/997) | 🟡 逻辑一致，但重复 |
| `processDividendEvents` | 行 1623 | 行 91 | 只用 Utils(378/889) | 🟡 私有版是死代码 |
| `updateAdjFactors` | 行 1717 | 行 61 | 只用 Utils(382/892) | 🟡 私有版是死代码 |
| `applySlippage` | 行 1566 | 行 192 | 只用私有版(448/509/931/1816/1855) | 🔴 **两份逻辑不一致** |
| `round` / `returnPct` | 行 2274 / 2301 | 行 207 / 213 | 混用 | 🟡 重复 |

### 2.1 🔴 `applySlippage` 两份实现在数学上不等价

**`BacktestEngine:1566`（当前实际生效）**
```java
double slip = baseSlippage;
if ("VOLUME".equalsIgnoreCase(model) && dayAmount > 0) {
    double ratio = Math.min(tradeAmount / dayAmount, 1.0);   // ← clamp 到 1.0
    slip = baseSlippage * (1 + Math.sqrt(ratio) * VOLUME_IMPACT_COEFF);  // ← 有基础项 1+，有冲击系数
}
return isBuy ? price * (1 + slip) : price * (1 - slip);
```

**`BacktestUtils:192`（死代码，无人调用）**
```java
if ("VOLUME".equalsIgnoreCase(slippageModel) && dayAmount > 0) {
    double impact = Math.sqrt(amount / dayAmount) * slippageRate * basePrice;  // ← 无 1+，无系数，无 clamp
    return isBuy ? basePrice + impact : basePrice - impact;
}
```

化简后对比（记 `r = tradeAmount/dayAmount`，`s = baseSlippage`，`k = VOLUME_IMPACT_COEFF`）：

| | 有效滑点率 |
|---|---|
| Engine 版（生效） | `s × (1 + √r × k)` |
| Utils 版（死代码） | `s × √r` |

**当 `r → 0`（小额交易）时，Engine 版趋近 `s`，Utils 版趋近 `0`** ——差异是数量级的。项目默认就是 `VOLUME` 滑点模型（见长期记忆：`BacktestEngine 默认 VOLUME 滑点`），所以这条路径全量命中。

**风险**：如果拆分时有人"顺手统一到 `BacktestUtils`"，**所有历史回测的净值、夏普、最大回撤会静默改变且不可复现**，而编译和启动都不会报错。这是本次拆分最大的陷阱。

### 2.2 Phase 0 处理办法

1. **以 `BacktestEngine` 私有版为准**（它是实际生效的），覆盖 `BacktestUtils.applySlippage`，并在 Utils 里加注释说明系数来源。
2. 删除 `BacktestEngine` 中已成死代码的 `processDividendEvents`(1623) / `updateAdjFactors`(1717)。
3. `calcFee` 两份逻辑**已核对逐行一致**（Utils 版仅多一个 `@Nullable`），删私有版，调用点(584/598/932/997)统一改 `BacktestUtils.calcFee`。
4. `round` / `returnPct` 同理统一。
5. **验证**：改完跑一个已有 `taskId` 的回测，与改前的 `backtest_report` 记录逐字段比对，必须完全一致。

预计减少约 **150 行**，且为后续拆分扫清地雷。

---

## 三、`BacktestEngine` 拆分方案（2,426 → 编排器约 200 行）

### 3.1 目标结构

| 新类 | 职责 | 迁入方法（原行号） | 预估行数 |
|---|---|---|---:|
| `BacktestEngine`（保留） | 仅做任务编排：异步入口、状态机、异常兜底 | `runBacktest`(104)、`runBacktestSync`(170) + 委派 | ~200 |
| `BacktestRunner` | 标准回测主循环 | `executeBacktest`(211-781) | ~450 |
| `ScreenBacktestRunner` | 滚动选股回测主循环 | `executeScreenBacktest`(782-1216)、`buildScreenRequest`(1217) | ~450 |
| `BacktestDataLoader` | 数据预加载 | `loadIndustryMap`(1438)、`loadStockInfoMap`(1469)、`loadHistoricalFactors`(1506)、`loadDelistDateMap`(2382) | ~200 |
| `BacktestScoring` | 因子打分与选股 | `computeScores`(1266)、`normalizeFactorVals`(1334)、`computeScoresWithScript`(1361)、`selectTopStocks`(1422)、`computeDynamicFactorWeights`(2314)、`parseFactorConfig`(2279) | ~300 |
| `BacktestRebalancer` | 调仓与现金重算 | `rebalance`(1773)、`recalcCash`(1882)、`shouldRebalance`(1952/1993) | ~280 |
| `BacktestReportBuilder` | 报告与净值曲线落库 | `buildReport`(2000)、`writeEquityCurveToDB`(1230) | ~270 |
| `BacktestProgressNotifier` | SSE 进度推送 | `sendProgress`(2237)、`sendProgressWithCurve`(2251) | ~40 |
| `BacktestUtils`（已存在） | 纯函数：费用/滑点/复权/市场规则 | 补入 `isLimitUp`(1535)、`isLimitDown`(1546)、`isSuspended`(1557)、`scaleAmountToCapacity`(1581)、`getExecutionPrice`(1751) | ~250 |

### 3.2 核心难点：571 行的 `executeBacktest` 怎么切

日循环 `for (int di = 0; di < tradingDates.size(); di++)`（原 352 行起，约 400 行）内部维护了大量可变状态：`positions`、`cash`、`adjFactors`、`tradeLog`、`equityCurve`、`maxDrawdown`、`costBasis`、`nextDayBars`、`benchmarkClose`……直接抽方法会导致**参数爆炸**（单个方法 15+ 参数）。

**解法：引入 `BacktestContext` 可变状态载体**

```java
public class BacktestContext {
    // 不可变配置
    public final BacktestTask task;
    public final StrategyDefinition strategy;
    public final List<LocalDate> tradingDates;
    public final Map<LocalDate, Double> benchmarkClose;
    // 可变运行态
    public Map<String, Double> positions = new HashMap<>();
    public double cash;
    public Map<String, Double> adjFactors = new HashMap<>();
    public Map<String, Double> costBasis = new HashMap<>();
    public List<Map<String, Object>> tradeLog = new ArrayList<>();
    public List<Map<String, Object>> equityCurve = new ArrayList<>();
    public double peakValue, maxDrawdown;
    // 当日切片
    public int di; public LocalDate today; public Map<String, MarketDailyBar> barMap;
}
```

日循环按现有注释边界切成 7 个阶段方法，签名统一为 `void xxxStage(BacktestContext ctx)`：

| 阶段方法 | 对应原注释（相对 `executeBacktest` 起点） | 约行数 |
|---|---|---:|
| `loadDailySnapshot` | `// 获取今日行情快照，过滤 ST/*ST/退市股` (+20) | 18 |
| `applyDividends` | `// ── 分红除权处理 ──` (+38) | 12 |
| `markToMarket` | `// 更新持仓市值` (+50) | 13 |
| `checkStopLossTakeProfit` | `// ── 止损止盈检查 ──` (+63) | 74 |
| `checkSellSignals` | `// ── 技术面卖点信号检查 ──` (+137) | 58 |
| `maybeRebalance` | `// 判断是否调仓` (+195) | 145 |
| `recordEquity` | `// 计算当日最终组合净值` ~ `// 进度更新` (+340~+402) | 62 |

切完后 `BacktestRunner` 的主循环体压缩到约 20 行，语义变成一眼可读的管线。

### 3.3 附带收益：消除 435 行的近似复制粘贴

`executeScreenBacktest`(782-1216) 与 `executeBacktest` 的日循环**结构几乎相同**——两边都有「行情快照→分红→市值→止损止盈→调仓→净值曲线」的同序注释块。上面的 7 个 Stage 方法抽出来后，两个 Runner 可共享其中 5 个（仅选股来源与调仓判定不同），预计**再消除约 250 行重复**。

⚠️ 但两边的实现可能已经悄悄漂移（同 §2 的教训）。**Stage 复用前必须逐段 diff**，确认差异是有意为之还是历史 bug。这一步放在最后阶段做，不与前面的低风险拆分混在一起。

---

## 四、`RecommendationService` 拆分方案（3,618 → 编排器约 250 行）

### 4.1 目标结构

`generateRecommendations`(371-814) 的 Step 0 ~ Step 6 管线注释非常清晰，天然就是拆分边界。

| 新类 | 职责 | 迁入方法（原行号） | 预估行数 |
|---|---|---|---:|
| `RecommendationService`（保留） | 仅编排 Step 0~6 管线 | `generateRecommendations`(360/371) | ~250 |
| `RecommendationQueryService` | 纯查询/统计，**与生成管线完全无关** | `getLatestRecommendations`(815)、`getRecommendationsByStrategyAndDate`(828/836)、`enrichFromStockInfo`(849)、`getHitRate`(1040)、`getModesByStrategyAndDate`(1073)、`getStrategyDateCombos`(1080)、`getDatesByStrategy`(1087)、`strategiesWithData`(1102)、`getBatchHistory`(1123)、`getBatchTopBottom`(1232) | ~550 |
| `RecommendationTracker` | 绩效追踪 | `trackRecommendationPerformance`(910)、`calcForwardReturn`(2308) | ~180 |
| `MarketRegimeDetector` | 市场环境识别 | `initRegimeCalendar`(242)、`detectRegime`(1393)、`detectRegimeName`(1554)、`isConsecutiveBear`(1567)、`calcRecentReturn`(1587)、`loadBondYield10y`(1597)、`loadYieldCurveSpread`(1623)、`calcATR`(1646)、`calcPercentile`(1676)、`RegimeInfo`(3565) | ~350 |
| `IndustryRotationService` | 行业动量、行业环境、分散化 | `computeIndustryMomentum`(1921)、`detectIndustryRegime`(2201)、`getCorrGroup`(1811)、`diversify`(1839)、`buildCodeToIndustryMap`(2287)、`fillIndustryAndMarketCap`(1780)、`IndustryMomentum`(3605) + 3 张常量表(81/103/202) | ~700 |
| `FactorWeightResolver` | 动态因子权重与 IC 校正 | `applyDynamicFactorWeights`(2636)、`applyWeightCap`(2857)、`resolveWeightMode`(2927)、`getFactorConfig`(2958)、`computeAdaptiveHalflife`(3366)、`applyCrowdingFilter`(3399)、`applyQuarterlyIcCorrection`(3444)、`applyIcConsistencyCheck`(3485)、`FactorDiagnostic`(3553)、`FORCE_KEEP_FACTORS`(193) | ~650 |
| `CandidateScreener` | 选股调用封装 + 过滤条件读取 | `screenStocks`(2458/2470)、`screenByPattern`(2548)、`getExcludeIndustries`(3035)、`getIncludeIndustries`(3064)、`getConceptNames`(3093)、`AdvancedScreenOptions`(2531) | ~280 |
| `StockScoreFuser` | 个股深度分析与融合评分 | `analyzeAndFuse`(3122)、`fuseScore`(1694)、`calculateRiskAndLiquidityScore`(3287) | ~330 |
| `PricePlanCalculator` | 买入价/止损止盈方案 | `calcSuggestedBuyPrice`(2359)、`calcPricePlan`(2399) | ~100 |
| `RecommendationMath` | 纯静态工具 | `stripSuffix`(302)、`mapActionTag`(312)、`safeDiv`(324)、`toLong`(329)、`std`(342)、`median`(1373)、`avg`(3352) | ~80 |

### 4.2 依赖收敛效果

拆分后 `RecommendationService` 的构造器依赖从 **24 → 约 6 个**：

```java
public RecommendationService(
    CandidateScreener screener,
    MarketRegimeDetector regimeDetector,
    IndustryRotationService industryRotation,
    FactorWeightResolver weightResolver,
    StockScoreFuser scoreFuser,
    RecommendationMapper recommendationMapper) { ... }
```

其余 18 个依赖下沉到各自的专责类。例如 `factorIcRecordMapper` / `quarterlyFactorAnalysisService` / `factorCorrelationService` / `factorMetaCache` 只服务于因子加权，全部归入 `FactorWeightResolver`。

### 4.3 拆分后的编排器长什么样

```java
public List<StockRecommendation> generateRecommendations(LocalDate date, Integer topN, ...) {
    String mode = weightResolver.resolveWeightMode(strategyId, requestWeightMode);   // Step 0
    int adjustedTopN = confidenceGuard.adjustTopN(strategyId, topN);                 // Step 0.5
    ScreenResult screened = screener.screen(date, strategyId, mode, adjustedTopN);   // Step 1
    screened = screener.applyIndustryAndBlacklistFilters(screened, strategyId);      // Step 1.3~1.7
    RegimeInfo regime = regimeDetector.detect(actualDate);                           // Step 2
    if (regimeDetector.shouldPause(regime, actualDate)) return List.of();            // 优化④
    var momentum = industryRotation.computeMomentum(regime, actualDate);             // Step 2.5
    var recs = scoreFuser.analyzeAll(screened, regime, momentum, actualDate);        // Step 3
    industryRotation.fillIndustryAndMarketCap(recs);                                 // Step 3.5
    recs = industryRotation.diversify(recs, regime);                                 // Step 4~5
    recs = confidenceGuard.filterHighConfidence(recs, adjustedTopN);                 // Step 5.5~5.6
    return persist(recs, strategyId, actualDate, mode);                              // Step 6
}
```

从 444 行降到约 15 行，每一步都能独立单测。

---

## 五、执行顺序与风险控制

按**风险从低到高**排列，每个 Phase 独立可交付、独立可回滚：

| Phase | 内容 | 风险 | 验证方式 |
|---|---|---|---|
| **0** | 清理 `BacktestUtils` 双轨（§2） | 🔴 高（涉及数值） | 同 taskId 回测报告逐字段比对 |
| **1** | `RecommendationQueryService` + `RecommendationMath` | 🟢 极低（纯查询/纯函数） | 11 个查询接口 HTTP 回归 |
| **2** | `BacktestDataLoader`、`BacktestProgressNotifier`、`BacktestReportBuilder` | 🟢 低（叶子职责） | 编译 + 跑一次回测看报告 |
| **3** | `MarketRegimeDetector`、`PricePlanCalculator`、`CandidateScreener`、`RecommendationTracker` | 🟡 中 | 同日期生成推荐，比对结果集 |
| **4** | `IndustryRotationService`、`FactorWeightResolver`、`StockScoreFuser` | 🟠 中高（核心算法） | 数值一致性快照比对 |
| **5** | `BacktestContext` + Stage 化 + 两个 Runner 合并 | 🔴 最高 | 多策略回测全量数值比对 |

### 5.1 数值一致性验证（Phase 0/4/5 必做）

纯结构重构的验收标准是**输出逐位不变**。做法：

```sql
-- 拆分前：对固定 taskId 存快照
CREATE TABLE backtest_report_baseline AS
SELECT * FROM backtest_report WHERE task_id IN (...);
```

拆分后用相同参数重跑，比对 `total_return` / `annual_return` / `sharpe_ratio` / `max_drawdown` / `win_rate` / `equity_curve` 全部字段。**任何一位不同都要先查清原因再继续**——这正是 §2 那个滑点分歧最可能暴露的地方。

推荐用策略 #74（新质生产力，三因子模型）和 #77（每日推荐在用）各跑一次，覆盖标准回测与滚动选股回测两条路径。

### 5.2 硬性约束

- **不改任何业务逻辑**：只搬运代码、抽提参数、改可见性。遇到看起来是 bug 的地方，**记录下来单独提**，不在拆分 commit 里顺手改（否则数值比对失去意义）。
- **每个 Phase 单独 commit**，commit message 标注 `refactor(no-behavior-change)`。
- **保持对外方法签名不变**：`RecommendationService` 的 11 个外部调用点（3 处 `generateRecommendations`、2 处 `trackRecommendationPerformance` 等）与 `BacktestEngine` 的 2 个入口（`BacktestService:68/195`、`ParamOptimizeService:420`）签名不动，调用方零改动。
- **`@Async` / `@PostConstruct` 归属**：`BacktestEngine.runBacktest` 的 `@Async("backtestTaskExecutor")` 必须留在保留类上（Spring 代理不穿透自调用）；`RecommendationService:241` 的 `@PostConstruct initRegimeCalendar` 随 `MarketRegimeDetector` 一起迁移。

---

## 六、预期收益

| 指标 | 拆分前 | 拆分后 |
|---|---:|---:|
| `RecommendationService` 行数 | 3,618 | ~250 |
| `RecommendationService` 依赖数 | 24 | ~6 |
| `BacktestEngine` 行数 | 2,426 | ~200 |
| 最大单方法行数 | 571 | < 80 |
| 重复代码消除 | — | 约 400 行（Utils 双轨 150 + 两个 Runner 250） |
| 可单测的独立单元 | 2 个巨类 | 19 个专责类 |

---

## 七、后续（本方案未覆盖）

同样问题的另外 8 个类，建议按相同范式排期，优先级建议：

1. `AnalysisService`（4,082 行，**比本次两个都大**）
2. `FactorComputeEngine`（2,452 行）
3. `DataUpdateService`（2,392 行，已在枚举重构中动过，熟悉度高）
4. `TradingSignalEngine`（2,316 行）
5. `PaperTradingService`（2,136 行，刚做过枚举化）
