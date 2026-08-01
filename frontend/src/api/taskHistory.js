import api from './index';

/** 定时任务执行历史 / 监控告警 API */
export default {
  // 分页查询执行历史
  list: (params) => api.get('/task-history/list', { params }),
  // 按任务聚合统计（成功率/失败/连续失败）
  stats: (days = 30) => api.get('/task-history/stats', { params: { days } }),
  // 最近失败列表
  recentFailures: (limit = 20) => api.get('/task-history/recent-failures', { params: { limit } }),
  // SLA 监控看板
  sla: () => api.get('/task-history/sla'),
  // 通知配置
  getNotificationConfig: () => api.get('/task-history/notification-config'),
  saveNotificationConfig: (cfg) => api.post('/task-history/notification-config', cfg),
  testNotification: () => api.post('/task-history/notification-test'),
};
