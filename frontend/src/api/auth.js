import api from './index';

// 认证相关接口
export const authApi = {
  // 账号密码登录（支持渐进式图形验证码）
  login: (username, password, captchaId, captchaCode) =>
    api.post('/auth/login', { username, password, captchaId, captchaCode }),
  // 获取图形验证码
  captcha: () => api.post('/auth/captcha'),
  // 获取微信网站扫码授权 URL
  wechatWebsiteAuthorize: () => api.get('/auth/wechat/website/authorize'),
  // 小程序登录
  miniLogin: (code) => api.post('/auth/wechat/mini/login', { code }),
  // 当前登录用户信息（刷新）
  me: () => api.get('/auth/me'),
  // 获取个人资料（不含密码）
  profile: () => api.get('/auth/profile'),
  // 更新个人资料（昵称/邮箱/手机/头像）
  updateProfile: (data) => api.put('/auth/profile', data),
  // 自助修改密码
  changePassword: (data) => api.post('/auth/change-password', data),
  // 退出登录
  logout: () => api.post('/auth/logout'),
};

export default authApi;
