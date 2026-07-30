import Taro from '@tarojs/taro';

// 注意：backend-mp 已由「静态 token 门禁」升级为「微信用户 token 鉴权」，
// 旧的 MP_TOKEN 常量已废弃，所有请求必须携带微信登录换来的用户 token。
const TOKEN_KEY = 'mp_user_token';

// 后端地址：
// - 小程序发布版用 prod.js 里的 BASE_URL（指向线上 / 预发布地址）
// - 调试/开发时用 dev.js 里的 BASE_URL（http://localhost:8082/api，即 backend-mp）
// Taro defineConstants 会把 BASE_URL 编译为字符串字面量，但为了避免 typeof
// 在某些运行环境下被误判为 undefined 导致 fallback 到错误端口，这里同时
// 兼容 process.env.NODE_ENV 显式指定。
const FALLBACK_BASE =
  process.env.NODE_ENV === 'production'
    ? 'https://stock.hwtx.site/api'
    : 'https://stock.hwtx.site/api';

const API_BASE =
  typeof BASE_URL !== 'undefined' && BASE_URL ? BASE_URL : FALLBACK_BASE;

// ------- token 存取 -------
function getToken() {
  return Taro.getStorageSync(TOKEN_KEY) || '';
}

function setToken(token) {
  Taro.setStorageSync(TOKEN_KEY, token);
}

function clearToken() {
  Taro.removeStorageSync(TOKEN_KEY);
}

// 并发去重：多个请求同时触发未登录时，只走一次微信登录
let loggingIn = null;

/** wx.login 拿 code -> POST /mp/login 换用户 token */
function wxLogin() {
  if (loggingIn) return loggingIn;
  loggingIn = (async () => {
    const { code } = await Taro.login();
    const token = await loginWithCode(code);
    setToken(token);
    return token;
  })();
  // 无论成功失败都释放进行中的标记，失败允许下次重试
  loggingIn.finally(() => {
    loggingIn = null;
  });
  return loggingIn;
}

function loginWithCode(code) {
  return new Promise((resolve, reject) => {
    Taro.request({
      url: API_BASE + '/mp/login',
      method: 'POST',
      data: { code },
      header: { 'Content-Type': 'application/json' },
      success(res) {
        if (res.statusCode === 200 && res.data && res.data.code === 200) {
          resolve(res.data.data.token);
        } else {
          reject(new Error((res.data && res.data.message) || '登录失败'));
        }
      },
      fail(reject),
    });
  });
}

/**
 * 主动确保已登录（app 启动时预热，可选调用；请求层也会自动补登）
 * @returns {Promise<string>} 用户 token
 */
export function ensureLogin() {
  const token = getToken();
  if (token) return Promise.resolve(token);
  return wxLogin();
}

/**
 * 统一请求封装
 * @param {object} options - { url, method, data, header, loading, _retry }
 */
export function request(options) {
  const { url, method = 'GET', data, header = {}, loading = false, _retry } = options;

  if (loading) {
    Taro.showLoading({ title: '加载中...', mask: true });
  }

  return new Promise((resolve, reject) => {
    Taro.request({
      url: API_BASE + url,
      method,
      data,
      header: {
        'Content-Type': 'application/json',
        'X-MP-Token': getToken(),
        ...header,
      },
      success(res) {
        if (loading) Taro.hideLoading();
        if (res.statusCode === 200 && res.data && res.data.code === 200) {
          resolve(res.data.data);
        } else if (res.statusCode === 401) {
          // 未登录或 token 失效：清掉本地 token，重新微信登录后重试一次
          clearToken();
          if (_retry) {
            Taro.showToast({ title: '认证失败', icon: 'none' });
            reject(new Error('Unauthorized'));
            return;
          }
          wxLogin()
            .then(() => {
              request({ ...options, _retry: true }).then(resolve, reject);
            })
            .catch((e) => {
              Taro.showToast({ title: '登录失败', icon: 'none' });
              reject(e);
            });
        } else {
          const msg = (res.data && res.data.message) || '请求失败';
          Taro.showToast({ title: msg, icon: 'none' });
          reject(new Error(msg));
        }
      },
      fail(err) {
        if (loading) Taro.hideLoading();
        console.error('[request] fail', url, err);
        Taro.showToast({ title: '网络异常', icon: 'none' });
        reject(err);
      },
    });
  });
}

export { API_BASE };
