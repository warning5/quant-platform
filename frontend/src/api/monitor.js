import api from './index';

/** 系统监控 / 行为统计 接口 */
const monitorApi = {
  overview: () => api.get('/system/monitor/overview'),
  httpLog: () => api.get('/system/monitor/http-log'),
  behavior: () => api.get('/system/monitor/behavior'),
  /** 前端路由切换埋点（上报当前页面路径，后端聚合页面访问分布） */
  track: (path) => api.post('/system/monitor/track', { path }),
};

export default monitorApi;
