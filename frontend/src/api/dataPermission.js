import api from './index';

// 数据权限配置（方案C 页面配置入口）
export const dataPermissionApi = {
  // 获取某类型的资源下拉选项（id + 中文标签）
  options: (type) => api.get(`/data-permission/${type}/options`),
  // 获取授权对象下拉：USER/ROLE 返回列表，DEPT 返回部门树
  grantees: (granteeType) => api.get(`/data-permission/grantees/${granteeType}`),
  // 获取某资源的权限配置（可见性 + 授权列表）
  get: (type, id) => api.get(`/data-permission/${type}/${id}`),
  // 设置全局可见性 PRIVATE / DEPT / PUBLIC
  setVisibility: (type, id, visibility) =>
    api.put(`/data-permission/${type}/${id}/visibility`, { visibility }),
  // 添加授权：granteeType USER/DEPT/ROLE，permLevel VIEW/EDIT
  addShare: (type, id, payload) => api.post(`/data-permission/${type}/${id}/shares`, payload),
  // 移除授权
  removeShare: (type, id, shareId) =>
    api.delete(`/data-permission/${type}/${id}/shares/${shareId}`),
};

export default dataPermissionApi;
