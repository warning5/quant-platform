import { create } from 'zustand';
import authApi from '../api/auth';
import { getToken, setToken, clearAuth } from '../utils/auth';

/**
 * 全局登录态 Zustand Store
 * - token 持久化到 localStorage（key=satoken），刷新页面后自动带态
 * - user / roles / permissions / menus 来自后端 /auth/me
 */
const useAuthStore = create((set, get) => ({
  token: getToken(),
  tokenName: 'satoken',
  user: null,
  roles: [],
  permissions: [],
  menus: [],

  /** 仅设置 token（微信回调用的临时态） */
  setToken: (token) => {
    setToken(token);
    set({ token });
  },

  /** 登录成功后整体写入 */
  login: (result) => {
    setToken(result.token);
    set({
      token: result.token,
      tokenName: result.tokenName || 'satoken',
      user: { username: result.username, nickname: result.nickname, avatar: result.avatar },
      roles: result.roles || [],
      permissions: result.permissions || [],
      menus: result.menus || [],
    });
  },

  /** 拉取当前登录用户信息（用于刷新/启动恢复） */
  fetchMe: async () => {
    const res = await authApi.me();
    set({
      user: { username: res.username, nickname: res.nickname, avatar: res.avatar },
      roles: res.roles || [],
      permissions: res.permissions || [],
      menus: res.menus || [],
    });
    return res;
  },

  /** 退出登录 */
  logout: async () => {
    try {
      await authApi.logout();
    } catch (e) {
      // 忽略网络异常，本地清理即可
    }
    clearAuth();
    set({ token: '', user: null, roles: [], permissions: [], menus: [] });
  },

  hasPermission: (perm) => Array.isArray(get().permissions) && get().permissions.includes(perm),
  hasAnyPermission: (perms) => Array.isArray(perms) && perms.some((p) => get().permissions.includes(p)),
}));

export { useAuthStore };
