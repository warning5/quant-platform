import api from './index';

/** 参数配置中心 API（后端 /system/config） */
const configApi = {
  // 配置列表（含禁用项）
  list: () => api.get('/system/config/list'),
  // 新增配置
  add: (data) => api.post('/system/config', data),
  // 修改配置
  update: (data) => api.put('/system/config', data),
  // 删除配置（软删）
  remove: (id) => api.delete(`/system/config/${id}`),
};

export default configApi;
