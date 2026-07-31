import api from './index';

// 部门管理
const departmentApi = {
  // 部门树
  tree: () => api.get('/system/department/tree'),
  // 新增
  create: (data) => api.post('/system/department', data),
  // 更新
  update: (data) => api.put('/system/department', data),
  // 删除
  remove: (id) => api.delete(`/system/department/${id}`),
};

export default departmentApi;
