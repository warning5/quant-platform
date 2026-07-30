import api from './index';

// 认证相关接口
export const authApi = {
  // 账号密码登录
  login: (username, password) => api.post('/auth/login', { username, password }),
  // 获取微信网站扫码授权 URL
  wechatWebsiteAuthorize: () => api.get('/auth/wechat/website/authorize'),
  // 小程序登录
  miniLogin: (code) => api.post('/auth/wechat/mini/login', { code }),
  // 当前登录用户信息（刷新）
  me: () => api.get('/auth/me'),
  // 退出登录
  logout: () => api.post('/auth/logout'),
};

export default authApi;
