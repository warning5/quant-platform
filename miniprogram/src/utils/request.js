import Taro from '@tarojs/taro';

const MP_TOKEN = 'quant-mp-2026-secret';

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

/**
 * 统一请求封装
 * @param {object} options - { url, method, data, header, loading }
 */
export function request(options) {
  const { url, method = 'GET', data, header = {}, loading = false } = options;

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
        'X-MP-Token': MP_TOKEN,
        ...header
      },
      success(res) {
        if (loading) Taro.hideLoading();
        console.log('[request] success', url, res.statusCode, res.data && res.data.code);
        if (res.statusCode === 200 && res.data && res.data.code === 200) {
          resolve(res.data.data);
        } else if (res.statusCode === 401) {
          Taro.showToast({ title: '认证失败', icon: 'none' });
          reject(new Error('Unauthorized'));
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
      }
    });
  });
}
