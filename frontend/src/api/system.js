import api from './index';

// 用户管理
export const userApi = {
  page: (params) => api.get('/system/user/page', { params }),
  add: (data) => api.post('/system/user', data),
  update: (data) => api.put('/system/user', data),
  remove: (id) => api.delete(`/system/user/${id}`),
  resetPassword: (id, password) => api.post(`/system/user/${id}/reset-password`, { password }),
  getRoles: (id) => api.get(`/system/user/${id}/roles`),
  assignRoles: (id, roleIds) => api.post(`/system/user/${id}/roles`, roleIds),
};

// 角色管理
export const roleApi = {
  page: (params) => api.get('/system/role/page', { params }),
  list: () => api.get('/system/role/list'),
  add: (data) => api.post('/system/role', data),
  update: (data) => api.put('/system/role', data),
  remove: (id) => api.delete(`/system/role/${id}`),
  userCount: (id) => api.get(`/system/role/${id}/users/count`),
  getMenus: (id) => api.get(`/system/role/${id}/menus`),
  assignMenus: (id, menuIds) => api.post(`/system/role/${id}/menus`, menuIds),
};

// 菜单 / 权限管理
export const menuApi = {
  tree: () => api.get('/system/menu/tree'),
  list: () => api.get('/system/menu/list'),
  add: (data) => api.post('/system/menu', data),
  update: (data) => api.put('/system/menu', data),
  remove: (id) => api.delete(`/system/menu/${id}`),
};

export default { userApi, roleApi, menuApi };
