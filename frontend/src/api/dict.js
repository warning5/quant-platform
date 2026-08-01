import api from './index';

/** 字典管理 API（后端 /system/dict） */
const dictApi = {
  // 字典类型列表
  listTypes: () => api.get('/system/dict/types'),
  // 某类型下的字典项；all=true 含禁用（管理页编辑用）
  listData: (dictType, all = false) => api.get('/system/dict/data', { params: { dictType, all } }),
  // 新增字典类型
  addType: (data) => api.post('/system/dict/type', data),
  // 修改字典类型（dictType 不可改）
  updateType: (data) => api.put('/system/dict/type', data),
  // 新增字典项
  addData: (data) => api.post('/system/dict/data', data),
  // 修改字典项
  updateData: (data) => api.put('/system/dict/data', data),
  // 删除字典项
  deleteData: (id) => api.delete(`/system/dict/data/${id}`),
  // 删除字典类型
  deleteType: (dictType) => api.delete(`/system/dict/type/${dictType}`),
};

export default dictApi;
