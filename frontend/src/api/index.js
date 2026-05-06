import axios from 'axios';
import { message } from 'antd';

const api = axios.create({
  baseURL: '/api',
  timeout: 30000,
});

api.interceptors.response.use(
  (res) => {
    if (res.data.code !== 200) {
      message.error(res.data.message || '操作失败');
      return Promise.reject(new Error(res.data.message));
    }
    // 统一返回 res.data.data，code=200 时 data 字段才是真正的响应体
    return res.data.data;
  },
  (err) => {
    // 优先取后端返回的业务错误信息，避免暴露 HTTP 状态码等技术细节
    const serverMsg = err.response?.data?.message || err.response?.data?.error;
    if (serverMsg) {
      message.error(serverMsg);
    } else if (!err.response) {
      message.error('网络连接失败，请检查网络');
    }
    // 不再默认弹出 err.message（如 "Request failed with status code 500"）
    return Promise.reject(err);
  }
);

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
};

// ===== 回测 API =====
export const backtestApi = {
  create: (data) => api.post('/backtests', data),
  list: (params) => api.get('/backtests', { params }),
  getTask: (taskId) => api.get(`/backtests/${taskId}`),
  getReport: (taskId) => api.get(`/backtests/${taskId}/report`),
  getReportById: (reportId) => api.get(`/backtests/reports/${reportId}`),
  getCurve: (taskId) => api.get(`/backtests/${taskId}/curve`),
  getAttribution: (taskId) => api.get(`/backtests/${taskId}/attribution`),
  cancel: (taskId) => api.post(`/backtests/${taskId}/cancel`),
  delete: (taskId) => api.delete(`/backtests/${taskId}`),
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
  getTradingDates: (startDate, endDate) =>
    api.get('/market/trading-dates', { params: { startDate, endDate } }),
  getSymbols: () => api.get('/market/symbols'),
  importBars: (bars) => api.post('/market/import', bars),
};


// ===== 财务数据 API =====
export const financialApi = {
  getOverview: (code) => api.get(`/financial/overview/${code}`),
  getIncome: (code, limit = 20) => api.get(`/financial/income/${code}`, { params: { limit } }),
  getBalance: (code, limit = 20) => api.get(`/financial/balance/${code}`, { params: { limit } }),
  getCashflow: (code, limit = 20) => api.get(`/financial/cashflow/${code}`, { params: { limit } }),
  getIndicator: (code, limit = 20) => api.get(`/financial/indicator/${code}`, { params: { limit } }),
  getTrend: (code) => api.get(`/financial/trend/${code}`),
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
  // 情绪数据
  getSentimentCoverage: () => api.get('/data-update/sentiment/coverage'),
  getSentimentTableStats: (table) => api.get(`/data-update/sentiment/table-stats/${table}`),
  validateSentiment: () => api.get('/data-update/sentiment/validate'),
  // 研报数据
  getResearchCoverage: () => api.get('/data-update/research/coverage'),
  validateResearch: () => api.get('/data-update/research/validate'),
};

// ===== 研报数据 API =====
export const researchApi = {
  getOverview: () => api.get('/research/overview'),
  getList: (params) => api.get('/research/list', { params }),
  checkStock: (code) => api.get(`/research/check/${code}`),
};

// ===== 个股分析 API =====
export const stockAnalysisApi = {
  getOverview: (code) => api.get('/analysis/overview', { params: { code } }),
  getScoreRules: () => api.get('/analysis/score-rules'),
  getResearchReport: (code) => api.get('/analysis/research', { params: { code } }),
  searchStocks: (keyword) => api.get('/analysis/search', { params: { keyword } }),
  getPeerComparison: (code) => api.get('/analysis/peers', { params: { code } }),
  getValuationPercentile: (code, years = 3) => api.get('/analysis/valuation-percentile', { params: { code, years } }),
  getSectorRanking: () => api.get('/analysis/sector-ranking'),
  getIndustryStocks: (industry, sortBy = 'changePercent', sortOrder = 'desc') =>
    api.get('/analysis/industry-stocks', { params: { industry, sortBy, sortOrder } }),
  getConceptStocks: (conceptName, sortBy = 'changePercent', sortOrder = 'desc') =>
    api.get('/analysis/concept-stocks', { params: { conceptName, sortBy, sortOrder } }),
  getIndustryCorrelation: (code) => api.get('/analysis/industry-correlation', { params: { code } }),
  getLimitUpAnalysis: (code) => api.get('/analysis/limit-up', { params: { code } }),
  getBlockTradeAnalysis: (code) => api.get('/analysis/block-trade', { params: { code } }),
  // 热门行业专题
  getHotSectors: () => api.get('/analysis/hot-sectors'),
  getHotSectorDetail: (conceptName) => api.get('/analysis/hot-sectors/detail', { params: { conceptName } }),
};

export default api;
