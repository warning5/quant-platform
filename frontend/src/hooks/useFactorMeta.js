import { useState, useEffect } from 'react';
import { factorApi } from '../api';
import { CATEGORY_LABELS } from '../pages/factors/constants';

/**
 * 全局因子元信息 Hook
 * 从后端 /factors API 动态加载因子定义，构建 factorCode -> { cat, desc, ... } 映射
 * 替代各页面硬编码的 factorMeta / KNOWN_FACTOR_DESC / AVAILABLE_FACTORS
 *
 * @returns {{ factorMeta: Object, factorList: Array, loading: boolean }}
 *   factorMeta: { [code]: { cat: string, desc: string, category: string, factorName: string, status: string, dataFrequency: string } }
 *   factorList: 原始因子定义数组
 *   loading: boolean
 */
export function useFactorMeta() {
  const [factorList, setFactorList] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    factorApi.getAllDefinitions()
      .then(res => {
        const content = res?.records || res?.content || res || [];
        setFactorList(Array.isArray(content) ? content : []);
      })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  // 构建因子代码 -> 元信息映射
  const factorMeta = {};
  for (const f of factorList) {
    const code = f.factorCode;
    const category = f.category; // 枚举值，如 MOMENTUM / VALUE
    factorMeta[code] = {
      cat: CATEGORY_LABELS[category] || category || '',
      desc: f.factorName || f.description || code,
      category,
      factorName: f.factorName || '',
      status: f.status || '',
      dataFrequency: f.dataFrequency || '',
    };
  }

  return { factorMeta, factorList, loading };
}
