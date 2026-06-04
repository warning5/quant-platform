import axios from 'axios';
import { message } from '../utils/messageUtil';

const api = axios.create({
  baseURL: '/api',
  timeout: 120000,
});

// 友好错误提示映射：对服务器内部错误统一显示友好文案
const FRIENDLY_ERRORS = {
  500: '服务暂时不可用，请稍后重试',
  502: '服务正在维护中，请稍后重试',
  503: '服务繁忙，请稍后重试',
  504: '服务响应超时，请稍后重试',
  429: '请求过于频繁，请稍后重试',
};

api.interceptors.response.use(
  (res) => {
    if (res.data.code !== 200) {
      // 业务错误：不暴露原始后端异常栈，只显示简短中文提示
      const msg = res.data.message || '操作失败';
      // 过滤掉包含技术细节的错误信息（如 Java 异常类名、SQL 等）
      const isTechError = /Exception|Error:|at\s+\w+\.\w+|SQL|NullPointerException|HttpRequestMethodNotSupportedException/i.test(msg);
      const friendlyMsg = isTechError ? '数据处理异常，请稍后重试' : msg;
      // silent 模式：不弹全局 message，由页面自行处理
      if (!res.config?._silent) {
        message.error(friendlyMsg);
      }
      return Promise.reject(new Error(friendlyMsg));
    }
    // 统一返回 res.data.data，code=200 时 data 字段才是真正的响应体
    return res.data.data;
  },
  (err) => {
    const status = err.response?.status;
    const silent = err.config?._silent;
    if (status && FRIENDLY_ERRORS[status]) {
      // HTTP 5xx / 429 等错误：统一友好提示（silent 模式不弹）
      if (!silent) message.error(FRIENDLY_ERRORS[status]);
    } else if (status === 401) {
      if (!silent) message.error('登录已过期，请重新登录');
    } else if (status === 403) {
      if (!silent) message.error('无权限访问');
    } else if (status === 404) {
      if (!silent) message.error('请求的资源不存在');
    } else if (!err.response) {
      // 无响应 = 网络断开或超时
      if (err.code === 'ECONNABORTED' || err.message?.includes('timeout')) {
        if (!silent) message.error('请求超时，请稍后重试');
      } else {
        if (!silent) message.error('网络连接失败，请检查网络');
      }
    } else {
      // 其他 HTTP 错误：不暴露状态码
      if (!silent) message.error('请求失败，请稍后重试');
    }
    return Promise.reject(err);
  }
);

// 静默请求配置：axios 请求时传入此配置，拦截器会自动抑制 message.error 弹出
// 用法：
//   api.get('/url', { ...silentConfig, params: {...} })
//   api.post('/url', data, silentConfig)
export const silentConfig = { _silent: true };

// 高阶包装：将已发出的 axios 请求包装为静默模式（通过 _silent 标记）
// 用法：silent(api.get(...)).then(...).catch(...)
export const silent = (promise) => {
  if (promise && typeof promise.then === 'function') {
    // axios 返回的 Promise 本身没有 config，但拦截器已经通过 err.config?._silent 判断过了
    // 此函数作为语法兼容保留，实际更推荐使用 silentConfig 参数方式
    return promise.catch(err => Promise.reject(err));
  }
  return promise;
};

// ===== 因子 API =====
export const factorApi = {
  list: (params) => api.get('/factors', { params }),
  getById: (id) => api.get(`/factors/${id}`),
  getInit: (id) => api.get(`/factors/${id}/init`),
  create: (data) => api.post('/factors', data),
  update: (id, data) => api.put(`/factors/${id}`, data),
  delete: (id) => api.delete(`/factors/${id}`),
  changeStatus: (id, status) => api.patch(`/factors/${id}/status`, null, { params: { status } }),
  compute: (id, startDate, endDate) => api.post(`/factors/${id}/compute`, null, { params: { startDate, endDate } }),
  getValueCount: (id) => api.get(`/factors/${id}/value-count`),
  batchStatus: (factorCodes) => api.get('/factors/status-batch', { params: { factorCodes: factorCodes.join(',') } }),
  deleteValues: (id) => api.delete(`/factors/${id}/values`),
  test: (id, params) => api.post(`/factors/${id}/test`, null, { params }),
  getTests: (factorCode) => api.get(`/factors/${factorCode}/tests`),
  getTestReport: (reportId) => api.get(`/factors/tests/${reportId}`),
  deleteTestReport: (reportId) => api.delete(`/factors/tests/${reportId}`),
  getTimeSeries: (factorCode, params) => api.get(`/factors/${factorCode}/values/series`, { params }),
  getCrossSection: (factorCode, date, page, size) => api.get(`/factors/${factorCode}/values/cross-section`, { params: { date, page: page || 1, size: size || 99999 } }),
  getFactorSymbols: (factorCode, keyword) => api.get(`/factors/${factorCode}/values/symbols`, { params: { keyword: keyword || '' } }),
  getTemplate: (type) => api.get('/factors/script/template', { params: { type } }),
  validateScript: (scriptCode) => api.post('/factors/script/validate', { scriptCode }),
  computeCorrelation: (params) => {
    // 手动构建URL参数，确保factorCodes参数正确传递
    const queryParams = new URLSearchParams();
    params.factorCodes.forEach(code => queryParams.append('factorCodes', code));
    queryParams.append('startDate', params.startDate);
    queryParams.append('endDate', params.endDate);
    return api.get(`/factors/correlation?${queryParams.toString()}`, { timeout: 120000 });
  },
  getAllDefinitions: () => api.get('/factors', { params: { page: 0, size: 200 } }),
  monitor: () => api.get('/factors/monitor'),
  running: () => api.get('/factors/running'),
  batchCompute: (factorCodes, startDate, endDate, incremental = true, force = false) =>
    api.post('/factors/batch-compute', null, { params: { factorCodes: factorCodes.join(','), startDate, endDate, incremental, force } }),
  // P1: 因子组合权重优化
  weightOptimize: (factorCodes, startDate, endDate, method = 'MARKOWITZ') =>
    api.post('/factors/weight-optimize', null, {
      params: { factorCodes: factorCodes.join(','), startDate, endDate, method },
      timeout: 120000,
    }),
  // P1: 缠论因子筛选
  chanScreen: (params) => api.get('/factors/chan-screen', { params, timeout: 30000 }),
  // 缠论筛选元数据（动态获取因子定义）
  chanScreenMeta: () => api.get('/factors/chan-screen/meta'),
  // P1: 因子IC/IR批量分析
  batchIcIrAnalysis: (factorCodes, startDate, endDate, forwardDays = 5) =>
    api.post('/factors/ic-ir-analysis', null, {
      params: { factorCodes: factorCodes.join(','), startDate, endDate, forwardDays },
      timeout: 180000,
    }),
  // P1: 单因子IC趋势
  getIcTrend: (factorCode, startDate, endDate, forwardDays = 5) =>
    api.get(`/factors/${factorCode}/ic-trend`, {
      params: { startDate, endDate, forwardDays },
      timeout: 120000,
    }),
  // 按日期筛选缺失因子值的因子
  missingByDate: (date) => api.get('/factors/missing-by-date', { params: { date } }),
};

// ===== 策略 API =====
export const strategyApi = {
  list: (params) => api.get('/strategies', { params }),
  getById: (id) => api.get(`/strategies/${id}`),
  create: (data) => api.post('/strategies', data),
  update: (id, data) => api.put(`/strategies/${id}`, data),
  delete: (id) => api.delete(`/strategies/${id}`),
  changeStatus: (id, status) => api.patch(`/strategies/${id}/status`, null, { params: { status } }),
};

// ===== 模拟盘 API =====
export const paperTradingApi = {
  create: (strategyId, strategyCode, initialCapital) =>
    api.post('/paper-trading/create', null, { params: { strategyId, strategyCode, initialCapital } }),
  list: () => api.get('/paper-trading/list'),
  getDetail: (paperId) => api.get(`/paper-trading/${paperId}`),
  generateSignals: (paperId) => api.post(`/paper-trading/${paperId}/generate-signals`),
  executeSignal: (signalId) => api.post(`/paper-trading/signals/${signalId}/execute`),
  getSignals: (paperId) => api.get(`/paper-trading/${paperId}/signals`),
  updateStatus: (paperId, status) => api.patch(`/paper-trading/${paperId}/status`, null, { params: { status } }),
  // 批量执行所有待处理信号
  executeAllSignals: (paperId) => api.post(`/paper-trading/${paperId}/execute-all-signals`),
  // 处理分红送股
  processDividends: (paperId) => api.post(`/paper-trading/${paperId}/process-dividends`),
  // 删除模拟盘
  delete: (paperId) => api.delete(`/paper-trading/${paperId}`),
  // 持仓预警
  getAlerts: (paperId, limit = 50) => api.get(`/paper-trading/${paperId}/alerts`, { params: { limit } }),
  getUnreadCount: (paperId) => api.get(`/paper-trading/${paperId}/alerts/unread-count`),
  markAllRead: (paperId) => api.post(`/paper-trading/${paperId}/alerts/read-all`),
  markRead: (alertId) => api.post(`/paper-trading/alerts/${alertId}/read`),
  deleteAlert: (alertId) => api.delete(`/paper-trading/alerts/${alertId}`),
  clearAlerts: (paperId) => api.delete(`/paper-trading/${paperId}/alerts`),
  scanAlerts: (paperId) => api.post(`/paper-trading/${paperId}/scan-alerts`),
  // 风控配置
  getRiskConfig: (paperId) => api.get(`/paper-trading/${paperId}/risk-config`),
  updateRiskConfig: (paperId, params) => api.put(`/paper-trading/${paperId}/risk-config`, null, { params }),
};

// ===== 回测 API =====
export const backtestApi = {
  create: (data) => api.post('/backtests', data),
  list: (params) => api.get('/backtests', { params }),
  getTask: (taskId) => api.get(`/backtests/${taskId}`),
  getReport: (taskId) => api.get(`/backtests/${taskId}/report`),
  getReportById: (reportId) => api.get(`/backtests/reports/${reportId}`),
  getCurve: (taskId) => api.get(`/backtests/${taskId}/curve`),
  getAttribution: (taskId) => api.get(`/backtests/${taskId}/attribution`, { timeout: 300000 }),
  getFactorAttribution: (taskId) => api.get(`/backtests/${taskId}/factor-attribution`, { timeout: 300000 }),
  getAttributionStrategy: (taskId) => api.get(`/backtests/${taskId}/attribution-strategy`, { timeout: 600000 }),
  getTradeAnalysis: (taskId) => api.get(`/backtests/${taskId}/trade-analysis`, { timeout: 300000 }),
  getFF3Attribution: (taskId) => api.get(`/backtests/${taskId}/factor-attribution/ff3`, { timeout: 300000 }),
  getAlphaRolling: (taskId) => api.get(`/backtests/${taskId}/monitor/alpha-rolling`, { timeout: 300000 }),
  getStyleRolling: (taskId) => api.get(`/backtests/${taskId}/monitor/style-rolling`, { timeout: 300000 }),
  cancel: (taskId) => api.post(`/backtests/${taskId}/cancel`),
  rerun: (taskId) => api.post(`/backtests/${taskId}/rerun`),
  delete: (taskId) => api.delete(`/backtests/${taskId}`),
  getRecords: (taskId) => api.get(`/backtests/${taskId}/records`),
  // P1: 多策略对比
  compare: (taskIds) => api.post('/backtests/compare', { taskIds }, { timeout: 60000 }),
  // P1: 蒙特卡洛模拟
  monteCarlo: (taskId, simulations = 500, horizonDays = 252) =>
    api.get(`/backtests/${taskId}/montecarlo`, { params: { simulations, horizonDays }, timeout: 120000 }),
  // P1: 参数优化（提交后立即返回，不等待结果，超时设为5分钟）
  submitParamOptimize: (req) => api.post('/backtests/param-optimize/submit', req, { timeout: 300000 }),
  getParamOptimizeResult: (jobId) => api.get(`/backtests/param-optimize/${jobId}`),
  getRunningOptimizeJobs: () => api.get('/backtests/param-optimize/running'),
  listParamOptimize: (strategyId) => strategyId
    ? api.get('/backtests/param-optimize/list', { params: { strategyId } })
    : api.get('/backtests/param-optimize/list'),
  deleteParamOptimize: (jobId) => api.delete(`/backtests/param-optimize/${jobId}`),
};

// ===== 行情 API =====
export const marketApi = {
  getOverview: () => api.get('/market/overview'),
  getCrossSection: (date, page = 1, size = 20, keyword = '', sortField = '', sortOrder = '') =>
    api.get('/market/cross-section', { params: { date, page, size, keyword, sortField, sortOrder } }),
  searchSymbols: (keyword, limit = 20) =>
    api.get('/market/search', { params: { keyword, limit } }),
  getBars: (symbol, startDate, endDate) =>
    api.get(`/market/bars/${symbol}`, { params: { startDate, endDate } }),
};


// ===== 财务数据 API =====
export const financialApi = {
  getOverview: (code, config) => api.get(`/financial/overview/${code}`, config),
  getIncome: (code, limit = 20, config) => api.get(`/financial/income/${code}`, { ...config, params: { limit } }),
  getBalance: (code, limit = 20, config) => api.get(`/financial/balance/${code}`, { ...config, params: { limit } }),
  getCashflow: (code, limit = 20, config) => api.get(`/financial/cashflow/${code}`, { ...config, params: { limit } }),
  getIndicator: (code, limit = 20, config) => api.get(`/financial/indicator/${code}`, { ...config, params: { limit } }),
  getTrend: (code, config) => api.get(`/financial/trend/${code}`, config),
  getStockList: (keyword, page = 0, size = 20) =>
    api.get('/financial/stocks', { params: { keyword, page, size } }),
  getStockCount: () => api.get('/financial/stocks/count'),
  validate: () => api.get('/financial/validate', { timeout: 60000 }),
  getProgress: () => api.get('/financial/progress'),
  getDuanYongpingPicks: (limit = 20) => api.get('/financial/picks/duan-yongping', { params: { limit } }),
  getHotMoneyPicks: (limit = 20) => api.get('/financial/picks/hot-money', { params: { limit } }),
  getQuantPicks: (limit = 20) => api.get('/financial/picks/quant', { params: { limit } }),
};

// ===== 数据更新 API =====
export const dataUpdateApi = {
  startTask: (data) => api.post('/data-update/start', data),
  getStatus: (taskId) => api.get(`/data-update/status/${taskId}`),
  getCurrent: () => api.get('/data-update/current'),
  cancelTask: (taskId) => api.post(`/data-update/cancel/${taskId}`),
  getCoverage: () => api.get('/data-update/coverage'),
  getIndexCoverage: () => api.get('/data-update/coverage/index'),
  getDividendCoverage: () => api.get('/data-update/coverage/dividend'),
  getMissingIndices: (date) => api.get('/data-update/missing-indices', { params: { date } }),
  getMissingDividendStats: () => api.get('/data-update/missing-dividend-stats'),
  getMissingDividendStocks: (market = 'ALL', page = 1, pageSize = 50) => api.get('/data-update/missing-dividend-stocks', { params: { market, page, pageSize } }),
  getMissingStocks: (date, market = 'ALL') => api.get('/data-update/missing-stocks', { params: { date, market } }),
  getMissingStats: (date) => api.get('/data-update/missing-stats', { params: { date } }),
  getTradingDates: (limit = 30) => api.get('/data-update/trading-dates', { params: { limit } }),
  getDefaultDates: () => api.get('/data-update/default-dates'),
  getRecentTasks: () => api.get('/data-update/recent-tasks'),
  getTaskLogs: (taskId) => api.get(`/data-update/logs/${taskId}`),
  // 情绪数据
  getSentimentCoverage: () => api.get('/data-update/sentiment/coverage'),
  getSentimentTableStats: (table) => api.get(`/data-update/sentiment/table-stats/${table}`),
  validateSentiment: () => api.get('/data-update/sentiment/validate'),
  // 研报数据
  getResearchCoverage: () => api.get('/data-update/research/coverage'),
  validateResearch: () => api.get('/data-update/research/validate'),
  // 退市清理
  getDelistedStocks: (inactiveDays = 30) => api.get('/data-update/delisted/list', { params: { inactiveDays } }),
  cleanDelistedStocks: (codes) => api.post('/data-update/delisted/clean', codes),
  // 内外盘数据
  getBidaskCoverage: () => api.get('/data-update/coverage/bidask'),
};

// ===== 定时任务配置 API =====
export const scheduleApi = {
  getAll: () => api.get('/schedule-config'),
  getGlobal: () => api.get('/schedule-config/global'),
  update: (taskKey, data) => api.put(`/schedule-config/${taskKey}`, data),
  batchUpdate: (items) => api.put('/schedule-config/batch', items),
  trigger: (taskKey) => api.post(`/schedule-config/trigger/${taskKey}`),
  cancel: (taskKey) => api.post(`/schedule-config/cancel/${taskKey}`),
  getHistory: () => api.get('/schedule-config/history'),
  delete: (taskKey) => api.delete(`/schedule-config/${taskKey}`),
};

// ===== 研报数据 API =====
export const researchApi = {
  getOverview: (config) => api.get('/research/overview', config),
  getList: (params, config) => api.get('/research/list', { ...config, params }),
  checkStock: (code, config) => api.get(`/research/check/${code}`, config),
};

// ===== 个股分析 API =====
export const stockAnalysisApi = {
  getOverview: (code) => api.get('/analysis/overview', { params: { code } }),
  getScoreRules: () => api.get('/analysis/score-rules'),
  getResearchReport: (code, config) => api.get('/analysis/research', { ...config, params: { code } }),
  searchStocks: (keyword) => api.get('/analysis/search', { params: { keyword } }),
  getPeerComparison: (code, config) => api.get('/analysis/peers', { ...config, params: { code } }),
  getValuationPercentile: (code, years = 3, config) => api.get('/analysis/valuation-percentile', { ...config, params: { code, years } }),
  getSectorRanking: () => api.get('/analysis/sector-ranking'),
  getIndustryStocks: (industry, sortBy = 'changePercent', sortOrder = 'desc') =>
    api.get('/analysis/industry-stocks', { params: { industry, sortBy, sortOrder } }),
  getConceptStocks: (conceptName, sortBy = 'changePercent', sortOrder = 'desc') =>
    api.get('/analysis/concept-stocks', { params: { conceptName, sortBy, sortOrder } }),
  getIndustryCorrelation: (code, config) => api.get('/analysis/industry-correlation', { ...config, params: { code } }),
  getLimitUpAnalysis: (code, config) => api.get('/analysis/limit-up', { ...config, params: { code } }),
  getBlockTradeAnalysis: (code, config) => api.get('/analysis/block-trade', { ...config, params: { code } }),
  // 热门行业专题
  getHotSectors: () => api.get('/analysis/hot-sectors'),
  getHotSectorDetail: (conceptName) => api.get('/analysis/hot-sectors/detail', { params: { conceptName } }),
  // 大盘温度计
  getMarketThermometer: () => api.get('/analysis/market-thermometer'),
  // P0 新增
  getChanChart: (code) => api.get('/analysis/chan-chart', { params: { code } }),
  getMoneyFlowHistory: (code, days = 120) => api.get('/analysis/money-flow-history', { params: { code, days } }),
  getRelativeStrength: (code) => api.get('/analysis/relative-strength', { params: { code } }),
  getKLine: (code, days = 60) => api.get('/analysis/kline', { params: { code, days } }),
  // P0 新闻事件分析
  getNewsAnalysis: (code, config) => api.get('/analysis/news', { ...config, params: { code } }),
  getNewsByTag: (code, tag, config) => api.get('/analysis/news/tag', { ...config, params: { code, tag } }),
  getNewsSignal: (code, config) => api.get('/analysis/news-signal', { ...config, params: { code } }),
  // P1 内外盘比
  getBidAskAnalysis: (code, config) => api.get('/analysis/bid-ask', { ...config, params: { code } }),
  // P1 机构覆盖度（Tab④）
  getInstitutionCoverage: (code, config) => api.get('/analysis/institution-coverage', { ...config, params: { code } }),
  // P2 个股长周期表现（YTD、超额收益、RS Rating、行业内排名）
  getStockPerformance: (code, config) => api.get('/analysis/stock-performance', { ...config, params: { code } }),
  // 股东结构（股东人数趋势 + 基金持仓明细 + 筹码集中度）
  getShareholderStructure: (code, config) => api.get('/analysis/shareholder-structure', { ...config, params: { code } }),
};

// ===== 智能推荐 API =====
export const recommendationApi = {
  /** 生成推荐列表（手动触发）
   * @param {string} date - 推荐日期
   * @param {number} topN - 推荐数量
   * @param {string} factorProfile - 因子组合: EXISTING/NORMAL/NEW_QUALITY/HOT/COMPREHENSIVE
   */
  generate: (date, topN, factorProfile) => api.post('/recommendations/generate', { date, topN, factorProfile }),
  /** 获取最新推荐列表 */
  getLatest: () => api.get('/recommendations/latest'),
  /** 获取指定批次推荐 */
  getByBatch: (batchId) => api.get(`/recommendations/batch/${batchId}`),
  /** 获取批次列表 */
  getBatches: (limit = 20) => api.get('/recommendations/batches', { params: { limit } }),
};

export default api;
