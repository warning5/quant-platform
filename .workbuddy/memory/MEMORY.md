# 量化平台项目长期记忆

## 项目架构
- Spring Boot + MyBatis + MySQL + ClickHouse + React + Python
- 双存储：MySQL(关系型) + ClickHouse(时序大数据)
- 数据源：Baostock(沪深)/腾讯(北交所+实时)/AKShare(情绪)/东方财富(财务+新闻)

## 核心模块
- 因子引擎：39+因子(技术14+财务25+缠论+估值6)，IC/IR分析，权重优化，Walk-Forward
- 推荐管线(6步)：市场环境识别→选股→深度分析→评分融合→行业分散→黑名单过滤
- 策略体系：7个核心策略 + 6个A股适配策略（红利低波/涨停板/板块轮动/市场情绪/估值修复/事件驱动），共13个
- 6个A股策略ID: 78(红利低波)/79(涨停板)/80(板块轮动)/81(市场情绪)/82(估值修复)/83(事件驱动)
- 每日调度：DAILY_RECOMMENDATION extra_config 用 strategyIds 数组（可多选，不再固定6策略），ScheduleService 逐策略循环执行
- DAILY_RECOMMENDATION 调度编辑器支持策略多选（从 /api/strategies 获取列表）、topN/置信度控制/同步监控开关
- 北向资金策略暂时阻塞（个股持股数据周频延迟3~5天）
- 策略置信度：4维度评分(命中率40+收益25+回撤20+波动15)，动态调整topN
- 个股黑名单：自动评估+手动管理，按策略维度排除
- 缠论引擎：K线合并→分型→笔→线段→中枢→走势→买卖点（Java+Python双版本）
- 回测：3种归因(Brinson/因子风格/FF3)+蒙特卡洛+参数优化+Walk-Forward
- 模拟盘：信号生成/执行/风控/预警/分红处理

## 关键约定
- 估值分位因子(VAL_PE_PERCENTILE, VAL_PB_PERCENTILE)已封装为独立因子，通过Python recompute_factors.py**日频**计算（在TECH_FACTOR_FUNCS中）
- PRICE_52W_HIGH_PCT(距52周高点回撤)也是**日频**计算，同在TECH_FACTOR_FUNCS中
- VAL_FCF_YIELD(自由现金流收益率)和VAL_DIVIDEND_YIELD(股息率)已**改为日频**（从TECH_FACTOR_FUNCS日频计算，不再是季频）
- PE/PB百分位+52W回撤已恢复到80%+(PRICE_52W_HIGH_PCT 99.6%, VAL_PB_PERCENTILE 97%, VAL_PE_PERCENTILE 69%亏损股天然排除)
- 腾讯qt.gtimg.cn接口用HTTP而非HTTPS（Windows下HTTPS握手慢7秒）
- 北交所数据走腾讯接口，沪深走Baostock
- 财务三表三源互补（东财+同花顺+新浪）
- VAL_前缀和QUAL_前缀的因子在Java端归类为财务因子(isFinancialFactor)
- PE/PB分位和52周回撤因子通过Python脚本计算（依赖stock_daily的pe_ttm/pb/high_price字段），**日频**不走Java引擎
- VAL_FCF_YIELD和VAL_DIVIDEND_YIELD已**改为日频**，从TECH_FACTOR_FUNCS计算，FIN_FACTORS中已删除
- QVIX（中国VIX）：已入库ClickHouse market_sentiment表（50ETF 2747条，最新2026-06-15），MarketSentimentService读取，MARKET_SENTIMENT策略使用QVIX评分调整
- collect_qvix.py已集成到DataUpdateService SENTIMENT管线，DataUpdateRequest.fetchQvix=true默认启用
- 新增3个日频因子：LIMIT_UP_COUNT(近20日涨停次数)/TURNOVER_ANOMALY(换手率异常)/VOLUME_SURPRISE(成交量惊喜)，覆盖5489只
- 回购事件：AKShare stock_repurchase_em()有5183条数据，可用于事件驱动策略

## 当前阶段（2026-06）
- Phase 1~5全部开发完成
- 新增模块：LLM推理(DeepSeek API) + 分钟K线采集 + 盘中监控 + NewsEventParser + EventSignalService
- LLM统一配置：llm.enabled/base-url/api-key/model（OpenAI兼容接口，支持DeepSeek/Ollama/Qwen等）
- LlmService支持enable_thinking参数（DeepSeek V4思考模式），思考token按输出token计费
- 旧别名deepseek-chat/deepseek-reasoner将于2026/07/24废弃，已改用deepseek-v4-flash/deepseek-v4-pro
- NewsEventParser：LLM解析新闻→12种事件标签(BUYBACK/INCREASE/EARN_PRE等)，每10分钟工作日9-18点执行；Python采集只存原始新闻，情感分析+事件标签全部交给LLM
- EventSignalService：一致预期vs业绩快报→超预期/不及预期信号，供事件驱动策略使用
- 新增MySQL表：stock_consensus_estimate(一致预期293只/876行，同花顺源)、stock_earnings_report(业绩快报1373行，东财源)，均已集成到情绪数据Tab可手动触发采集
- 估值修复/事件驱动/市场情绪策略自动应用新闻事件加分，事件驱动策略额外使用超预期信号
- 盘中监控支持自定义股票（来源"客户定义"），刷新目标价时不被覆盖，API: POST /monitor/add-custom-stock, DELETE /monitor/custom-stock
- VALUE_QUALITY策略模板：三层漏斗(估值+质量+技术)，15个因子
- 每日07:15自动推荐+推送通知
- 盘中每10秒高频轮询候选股实时价格(qt.gtimg.cn)+SSE推送
- K线并行拉取(CompletableFuture线程池，4线程)
- m5分钟K线接口已修复：使用HTTPS mkline端点(https://ifzq.gtimg.cn/appstock/app/kline/mkline)，参数格式为code,m5,,320&_var=m5_today，响应需去掉_var前缀再解析JSON；北交所BJ股票m5数据为空，自动降级fallbackPriceOnly()
- 腾讯分钟K线注意：qt.gtimg.cn用HTTP(快)，ifzq.gtimg.cn必须用HTTPS(HTTP会302重定向)
- "我的收藏"和"持仓管理"已删除（价值低，与盘中监控/模拟盘重叠）

## 关键约定（补充）
- Spring Boot context-path=/api，controller的@RequestMapping不要加/api前缀
- 项目用MyBatis-Plus(BaseMapper)，不用JPA Repository
- List.of()最多10个元素，超过用Arrays.asList()
- @MapperScan({"com.quant.platform.**"})扫描所有包
- Maven在Git Bash下有加载问题，用PowerShell执行mvn.cmd
- 前端构建用 Vite（`npm run build` = `vite build`，输出到 build/），**不是** react-scripts
- MySQL数据库名是 `stock`（非 quant_platform），密码 123456
- LLM配置已统一为llm.*前缀，旧的llm.deepseek.*和quant.factor.llm已删除
- 策略管理完全依赖数据库（strategy_definition表），StrategyDataInitializer已删除
- 推荐引擎零硬编码：因子配置(factorConfigJson)和行业排除(filterConfigJson)全部从数据库策略读取
- generateRecommendations() 不再接受 factorProfile 参数，只通过 strategyId 获取所有配置
- MySQL表统一用 `code` 列名（stock_daily/stock_info/stock_financial_indicator），**不是** `stock_code`
- CH factor_value 存在两种 symbol 格式：纯代码(000526,94因子) + 带后缀(000526.SZ,3个特殊因子)，查询需双格式合并
- CH stock_daily 有完整数据（719万行/5490股，含pe_ttm/pb），MySQL stock_daily 可能为空
- stock_financial_indicator 用 `net_operate_cf`（非 operating_cash_flow）和 `operating_cf_to_np`（现成OCF/NP比率）
- LLM推理数据链路：CH factor_value(97因子) → CH stock_daily(PE/PB补齐) → MySQL financial_indicator(ROE等)
- VAL_DIVIDEND_YIELD/VAL_FCF_YIELD 已改为**日频**，其余VAL_*/FIN_* 因子仍按**季度计算**（季末日期：03-30/06-30/09-30/12-31），Q2数据需等到06-30后
- 推荐引擎用clickHouseFactorValueService.getLatestDate(factorCode)自动找各因子最新日期（季频用季末，日频用最新交易日）
- 北交所BJ股票VAL/FIN因子覆盖率更低（约50%），但CH stock_daily和MySQL财务表有完整日频/最新数据可fallback
- StockRecommendation.stockCode是**纯代码**（无.SZ/.SH/.BJ后缀），LLM查询时需从stock_info查market拼接后缀
- LLM分析getLatestFactorValues()三级fallback: CH纯代码(94技术因子) → CH后缀格式(3个特殊因子) → CH stock_daily(PE/PB) → MySQL(ROE/财务)
