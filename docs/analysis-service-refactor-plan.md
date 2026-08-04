# AnalysisService God Class 拆分方案

> 范围：`stock/analysis/service/AnalysisService.java`（4,082 行 / 70 方法，@RequiredArgsConstructor 注入 6 个 final 依赖）
> 目标：退化为编排器（getOverview + 委托），重逻辑下沉到专责类。
> 约束：与 RecommendationService / BacktestEngine 拆分完全一致 —— `refactor(no-behavior-change)`，对外签名不变、业务逻辑不改、发现 bug 仅标 `@apiNote KNOWN-ISSUE`；每 Phase 单独 commit；静态逐字比对 + 运行时基线比对双校验。

## 一、依赖归属分析（已脚本化：`analyze_analysis_deps.py`）

原始 6 个 final 字段：`analysisChMapper / stockAnalysisMapper / newsMapper / bidAskMapper / tradingSignalEngine / clickHouseStockService`

### 共享组件 `AnalysisCommonService`（被多专类调用，必须抽公共）
- `normalizeCodeForDailyCH`（纯函数）→ static
- `getLatestTradeDate`（查 CH）→ 实例，注入 `analysisChMapper`
- `median`（纯函数）→ static
- `formatD` / `formatMoney`（纯格式化，被 OverviewRisk 与 OverviewAssembler 跨类调用）→ static

> 各专类方法体内对这 5 个方法的调用统一改写为 `analysisCommon.xxx(...)`（机械等价，静态 diff 归一化）。

### 专类清单（簇内自洽，可整簇搬运）

| 专类 | 方法数 | 行数 | 依赖 |
|---|---|---|---|
| TechIndicatorService | 8 | 485 | analysisChMapper, tradingSignalEngine |
| MoneyFlowService | 3 | 228 | analysisChMapper, stockAnalysisMapper, bidAskMapper |
| ResearchAnalysisService | 5 | 194 | stockAnalysisMapper |
| SectorAnalysisService | 7 | 632 | analysisChMapper, stockAnalysisMapper, clickHouseStockService |
| EventAnalysisService | 2 | 156 | analysisCommon(normalizeCodeForDailyCH) |
| QuoteDataService | 19 | 717 | analysisChMapper, stockAnalysisMapper |
| OverviewRiskService | 11 | 628 | clickHouseStockService, analysisCommon(formatD/formatMoney) |
| OverviewAssembler | 11 | 216 | analysisCommon(formatD/formatMoney) |

### 留在本体（编排器）
- `getOverview`（主入口，委托到上述专类）+ 行数约 449 行的胶水/编排逻辑。

## 二、执行顺序（风险从低到高，每 Phase 可独立回滚）

- **Phase 0** `AnalysisCommonService`：抽出 5 个共享辅助方法。所有专类与本体注入它。
- **Phase 1** `TechIndicatorService`：supplementTechIndicators(386行,最大单体方法) + avg/calcEma/calcRsi/detectVolumePriceDivergence/getScoreRules/calcTargetPrice2/calcExtremeTargetPrice。
- **Phase 2** `MoneyFlowService`：calcMoneyFlowSignal + getMoneyFlowHistory + calcDailyMoneyScore。
- **Phase 3** `ResearchAnalysisService`：getResearchAnalysis + calcEpsConsensus/pivotRatingTrend/getShareholderStructure/calcResearchScore。
- **Phase 4** `SectorAnalysisService`：getSectorRanking + getConceptStocks/getIndustryStocks/getIndustryCorrelation/calcBetaAndCorrelation/getHotSectors/getHotSectorDetail。
- **Phase 5** `EventAnalysisService`：getLimitUpAnalysis + getBlockTradeAnalysis。
- **Phase 6** `QuoteDataService`：fetchKlineData/batchFetchKlineData/getChanChart/getKLine/getStockPerformance/getRelativeStrength + 其支撑方法(calcRsRating/calcIndustryRank/calcIndustryTotal/rsRatingToLabel/getYearStartDate/calcIndexYtd/calcStockYtd/round2/getPeerComparison/getValuationPercentile/calcPercentile/percentileDesc/searchStocks)。
- **Phase 7** `OverviewRiskService`：buildTailRisks + 尾部风险辅助 + buildCatalysts + calcMultiAnalystScores + buildBullBearDebate/buildBullBearConclusionText。
- **Phase 8** `OverviewAssembler`：buildConclusion/buildDimensionReason/mapChinese/buildExecutionPlan/calcSuggestedPositionPct/calcReducePriceRange/calcRiskLevel/calcConfidenceLevel/calcNewsScore。

## 三、验证方法论

### 静态（主保证）
- 抽取方法体逐字比对：迁移后方法体与原方法体规范化 diff（忽略 `this.`/`analysisCommon.` 接收者差异与缩进）。脚本 `static_diff_analysis.py`。
- 原类调用点改为 `specialist.method(...)` 委托，签名不变。

### 运行时（安全网）
- 基线：`.workbuddy/refactor-verify/analysis_baseline/`（19 端点，code=600519，2026-08-04 采集）。
- 每 Phase 改完 → 重新构建 + 重启 8080 → 同端点重采 → `diff_analysis_runtime.py` 顺序无关深比对（容差 1e-6，忽略时间戳/最新交易日相关抖动）。
- 数据漂移说明：日线历史窗口稳定；仅"最新交易日"相关字段可能日内漂移，属已知非行为差异。

## 四、已知陷阱
- `supplementTechIndicators` 是最大方法(386行)，且内部调用 avg/calcEma/calcRsi（须整簇一起搬）。
- `getOverview` 调用 19 个私有辅助，分布在 Tech/MoneyFlow/Research/OverviewRisk/OverviewAssembler → 这些 Phase 完成前，getOverview 仍是"大委托"，不急着瘦。
- 共享方法 receiver 改写须全量归一化，否则静态 diff 误报。
