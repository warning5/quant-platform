import { useState, useEffect } from 'react';
import { stockAnalysisApi } from '../api';

/**
 * 共享大盘温度计 hook，避免每个页面重复请求
 * 缓存5分钟，减少接口调用
 */
let cache = null;
let cacheTime = 0;
const CACHE_TTL = 5 * 60 * 1000; // 5分钟

export function useMarketThermometer() {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(false);

  const load = () => {
    const now = Date.now();
    if (cache && now - cacheTime < CACHE_TTL) {
      setData(cache);
      return;
    }
    setLoading(true);
    stockAnalysisApi.getMarketThermometer()
      .then(d => {
        cache = d;
        cacheTime = now;
        setData(d);
      })
      .catch(() => {})
      .finally(() => setLoading(false));
  };

  useEffect(() => { load(); }, []);

  // 温度状态描述
  const status = data ? (() => {
    const fg = data.fearGreedIndex ?? 50;
    if (fg >= 70) return { label: '极度贪婪', color: '#52c41a', action: '建议减仓/停止开仓' };
    if (fg >= 55) return { label: '偏贪婪', color: '#73d13d', action: '谨慎追涨' };
    if (fg >= 45) return { label: '中性', color: '#faad14', action: '正常操作' };
    if (fg >= 30) return { label: '偏恐慌', color: '#ff7a45', action: '关注低估机会' };
    return { label: '极度恐慌', color: '#ff4d4f', action: '可考虑分批建仓' };
  })() : null;

  return { data, loading, status, refresh: load };
}
