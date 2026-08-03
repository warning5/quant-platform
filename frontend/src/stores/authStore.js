import { create } from 'zustand';
import authApi from '../api/auth';

/**
 * 全局登录态 Zustand Store
 * #6 改造：token 改由后端写入 httpOnly cookie，前端不再持久化到 localStorage。
 * - token 仅保存在内存态，用于「是否已登录」判断；刷新页面后由 App 启动 fetchMe 恢复。
 * - 浏览器自动携带 cookie，api 请求不再手动塞 satoken header（见 api/index.js）。
 */
const useAuthStore = create((set, get) => ({
  token: '',
  tokenName: 'satoken',
  userId: null,
  user: null,
  roles: [],
  permissions: [],
  menus: [],
  // 启动恢复是否完成（fetchMe 不论成败都置 true，避免刷新瞬间因内存态为空误跳登录）
  bootstrapped: false,

  /** 仅设置内存态 token（静默刷新 / 微信登录用的临时态） */
  setToken: (token) => {
    set({ token });
  },

  /** 登录成功后整体写入（token 仅留内存，不落 localStorage） */
  login: (result) => {
    set({
      token: result.token,
      tokenName: result.tokenName || 'satoken',
      userId: result.userId,
      user: { id: result.userId, username: result.username, nickname: result.nickname, avatar: result.avatar },
      roles: result.roles || [],
      permissions: result.permissions || [],
      menus: result.menus || [],
    });
  },

  /** 拉取当前登录用户信息（用于刷新/启动恢复；cookie 自动携带，无需手动传 token） */
  fetchMe: async () => {
    const res = await authApi.me();
    set({
      token: res.token,
      userId: res.userId,
      user: { id: res.userId, username: res.username, nickname: res.nickname, avatar: res.avatar },
      roles: res.roles || [],
      permissions: res.permissions || [],
      menus: res.menus || [],
    });
    return res;
  },

  /** 退出登录：后端清除 cookie，前端仅清内存态 */
  logout: async () => {
    try {
      await authApi.logout();
    } catch (e) {
      // 忽略网络异常，本地清理即可
    }
    set({ token: '', userId: null, user: null, roles: [], permissions: [], menus: [] });
  },

  hasPermission: (perm) => Array.isArray(get().permissions) && get().permissions.includes(perm),
  hasAnyPermission: (perms) => Array.isArray(perms) && perms.some((p) => get().permissions.includes(p)),

  /** 启动引导：不再依赖 localStorage，直接用 httpOnly cookie 调 /auth/me 恢复登录态 */
  bootstrap: async () => {
    try {
      await get().fetchMe();
    } catch (e) {
      // 失败（cookie 无效/过期）由 RequireAuth 后续跳登录
    } finally {
      set({ bootstrapped: true });
    }
  },
}));

export { useAuthStore };
