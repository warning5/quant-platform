import { request } from '../utils/request';

/**
 * 小程序专用推荐 API（精简字段，走 /mp/ 前缀 + Token 鉴权）
 */
export const recommendationApi = {
  /** 获取有推荐记录的策略列表 */
  strategiesWithData: () =>
    request({ url: '/mp/recommendations/strategies' }),

  /** 获取某策略最近N天有推荐的日期列表 */
  getDatesByStrategy: (strategyId, days = 30) =>
    request({ url: '/mp/recommendations/dates', data: { strategyId, days } }),

  /** 获取指定策略+日期的推荐列表 */
  getByStrategyAndDate: (strategyId, date) =>
    request({ url: `/mp/recommendations/strategy/${strategyId}/date/${date}` }),

  /** 获取最新推荐列表（可选传 strategyId） */
  getLatest: (strategyId) =>
    request({ url: '/mp/recommendations/latest', data: { strategyId } }),

  /** 获取批次历史表现汇总 */
  getBatchHistory: (limit = 20, strategyId) =>
    request({ url: '/mp/recommendations/batch-history', data: { limit, strategyId } }),

  /** 获取批次命中率 */
  getHitRate: (strategyId, date) =>
    request({ url: `/mp/recommendations/hit-rate/strategy/${strategyId}/date/${date}` }),

  /** 个股推荐详情（R1）：评分明细/因子归因/买卖信号/表现 */
  getStockDetail: (stockCode, params = {}) =>
    request({ url: `/mp/recommendations/stock/${stockCode}/detail`, method: 'GET', data: params }),

};

/**
 * 策略置信度 API（走 /mp/ 前缀，backend-mp 直连 MySQL）
 */
export const confidenceApi = {
  /** 所有策略最新置信度 */
  getAllLatest: () =>
    request({ url: '/mp/strategy-confidence/latest-all' }),

  /** 某策略置信度 */
  getLatest: (strategyId) =>
    request({ url: '/mp/strategy-confidence', data: { strategyId } }),
};

/**
 * 大盘指数实时行情 API（走 /mp/ 前缀，backend-mp 直连腾讯 API）
 */
export const indexApi = {
  /** 获取大盘指数实时数据 */
  getIndices: () =>
    request({ url: '/mp/monitor/indices' }),
};

/**
 * 个股实时行情 API（走 /mp/ 前缀，backend-mp 直连腾讯行情 API）
 */
export const stockQuoteApi = {
  /** 批量获取个股实时行情，codes 为逗号分隔的股票代码 */
  getQuotes: (codes) =>
    request({ url: '/mp/monitor/stocks', data: { codes } }),
};

/**
 * 策略 API（只读，backend-mp 直连 MySQL）
 */
export const strategyApi = {
  /** 策略列表 */
  list: (params) =>
    request({ url: '/mp/strategies', method: 'GET', data: params }),
  /** 策略详情 */
  get: (id) =>
    request({ url: `/mp/strategies/${id}`, method: 'GET' }),
  /** 策略回测表现（最新 COMPLETED 任务 + 报告） */
  backtest: (id) =>
    request({ url: `/mp/strategies/${id}/backtest`, method: 'GET' }),
};

/**
 * 因子 API（只读，backend-mp 直连 MySQL）
 */
export const factorApi = {
  /** 因子列表（支持 keyword/category/status 筛选） */
  list: (params) =>
    request({ url: '/mp/factors', method: 'GET', data: params }),
  /** 因子详情 */
  get: (id) =>
    request({ url: `/mp/factors/${id}`, method: 'GET' }),
  /** 因子 IC 趋势 */
  icTrend: (id, params = {}) =>
    request({ url: `/mp/factors/${id}/ic-trend`, method: 'GET', data: params }),
};

/**
 * 我的资料 API（M1，backend-mp 复用 sys_user 表）
 */
export const profileApi = {
  /** 获取当前登录用户资料 */
  get: () =>
    request({ url: '/mp/me', method: 'GET' }),
  /** 更新资料（昵称/头像/邮箱/手机号） */
  update: (data) =>
    request({ url: '/mp/me', method: 'PUT', data }),
};
