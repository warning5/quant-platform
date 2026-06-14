# 量化平台项目长期记忆

## 项目架构
- Spring Boot + MyBatis + MySQL + ClickHouse + React + Python
- 双存储：MySQL(关系型) + ClickHouse(时序大数据)
- 数据源：Baostock(沪深)/腾讯(北交所+实时)/AKShare(情绪)/东方财富(财务+新闻)

## 核心模块
- 因子引擎：39+因子(技术14+财务25+缠论+估值6)，IC/IR分析，权重优化，Walk-Forward
- 推荐管线(6步)：市场环境识别→选股→深度分析→评分融合→行业分散→黑名单过滤
- 策略置信度：4维度评分(命中率40+收益25+回撤20+波动15)，动态调整topN
- 个股黑名单：自动评估+手动管理，按策略维度排除
- 缠论引擎：K线合并→分型→笔→线段→中枢→走势→买卖点（Java+Python双版本）
- 回测：3种归因(Brinson/因子风格/FF3)+蒙特卡洛+参数优化+Walk-Forward
- 模拟盘：信号生成/执行/风控/预警/分红处理

## 关键约定
- 估值分位因子(VAL_PE_PERCENTILE, VAL_PB_PERCENTILE)已封装为独立因子，通过Python recompute_factors.py计算
- PRICE_52W_HIGH_PCT(距52周高点回撤)和VAL_FCF_YIELD(自由现金流收益率)已新增
- 腾讯qt.gtimg.cn接口用HTTP而非HTTPS（Windows下HTTPS握手慢7秒）
- 北交所数据走腾讯接口，沪深走Baostock
- 财务三表三源互补（东财+同花顺+新浪）
- VAL_前缀和QUAL_前缀的因子在Java端归类为财务因子(isFinancialFactor)
- PE/PB分位和52周回撤因子通过Python脚本计算（依赖stock_daily的pe_ttm/pb/high_price字段），不走Java日频引擎

## 当前阶段（2026-06）
- Phase 1~5全部开发完成
- 新增模块：LLM推理(DeepSeek API) + 分钟K线采集 + 盘中监控
- LLM统一配置：llm.enabled/base-url/api-key/model（OpenAI兼容接口，支持DeepSeek/Ollama/Qwen等）
- LlmService支持enable_thinking参数（DeepSeek V4思考模式），思考token按输出token计费
- 旧别名deepseek-chat/deepseek-reasoner将于2026/07/24废弃，已改用deepseek-v4-flash/deepseek-v4-pro
- VALUE_QUALITY策略模板：三层漏斗(估值+质量+技术)，15个因子
- 每日07:15自动推荐+同步Watchlist+推送通知
- 盘中每分钟监控候选股实时价格(qt.gtimg.cn)
- 持仓管理：建仓/平仓/止损止盈/每日报告推送

## 关键约定（补充）
- Spring Boot context-path=/api，controller的@RequestMapping不要加/api前缀
- 项目用MyBatis-Plus(BaseMapper)，不用JPA Repository
- List.of()最多10个元素，超过用Arrays.asList()
- @MapperScan({"com.quant.platform.**"})扫描所有包
- Maven在Git Bash下有加载问题，用PowerShell执行mvn.cmd
- LLM配置已统一为llm.*前缀，旧的llm.deepseek.*和quant.factor.llm已删除
- 策略管理完全依赖数据库（strategy_definition表），StrategyDataInitializer已删除
- 推荐引擎零硬编码：因子配置(factorConfigJson)和行业排除(filterConfigJson)全部从数据库策略读取
- generateRecommendations() 不再接受 factorProfile 参数，只通过 strategyId 获取所有配置
- MySQL表统一用 `code` 列名（stock_daily/stock_info/stock_financial_indicator），**不是** `stock_code`
- CH factor_value 存在两种 symbol 格式：纯代码(000526,94因子) + 带后缀(000526.SZ,3个特殊因子)，查询需双格式合并
- CH stock_daily 有完整数据（719万行/5490股，含pe_ttm/pb），MySQL stock_daily 可能为空
- stock_financial_indicator 用 `net_operate_cf`（非 operating_cash_flow）和 `operating_cf_to_np`（现成OCF/NP比率）
- LLM推理数据链路：CH factor_value(97因子) → CH stock_daily(PE/PB补齐) → MySQL financial_indicator(ROE等)
- VAL_*/FIN_* 因子按**季度计算**（季末日期：03-30/06-30/09-30/12-31），Q2数据需等到06-30后
- 北交所BJ股票VAL/FIN因子覆盖率更低（约50%），但CH stock_daily和MySQL财务表有完整日频/最新数据可fallback
- StockRecommendation.stockCode是**纯代码**（无.SZ/.SH/.BJ后缀），LLM查询时需从stock_info查market拼接后缀
- LLM分析getLatestFactorValues()三级fallback: CH纯代码(94技术因子) → CH后缀格式(3个特殊因子) → CH stock_daily(PE/PB) → MySQL(ROE/财务)
