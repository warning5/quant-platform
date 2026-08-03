// #6 安全改造：token 由后端写入 httpOnly cookie，浏览器随请求自动携带，
// 前端不再使用 localStorage 存储 token（消除 XSS 窃取路径）。
export const TOKEN_KEY = 'satoken';

// 以下为兼容接口，行为已改为 no-op（token 存入 httpOnly cookie，前端无需持有）。
// api 请求拦截器不再手动注入 satoken header，浏览器会自动带上 cookie。
export function getToken() {
  return '';
}

export function setToken() {
  // no-op
}

export function clearAuth() {
  // no-op：登出由后端清除 cookie
}
