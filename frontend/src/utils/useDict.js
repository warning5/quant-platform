import { useEffect, useState } from 'react';
import dictApi from '../api/dict';

// 跨组件共享的字典缓存（带 TTL），避免每个 useDict 重复请求
const dictCache = new Map();
const TTL = 5 * 60 * 1000; // 5 分钟

/**
 * 读取某字典类型的全部【启用】项。
 * 返回：dictList（数组）、dictMap（dictValue -> 字典项）、loading。
 * 字典新增枚举后，最多等 TTL 自动刷新；或在管理页保存后由 DictService 后端 evict 保证下次回源。
 *
 * @param {string} dictType 字典类型，如 'SLA_SEVERITY'
 * @param {object} opts { all?: boolean } all=true 时含禁用项（管理页用）
 */
export function useDict(dictType, opts = {}) {
  const [dictList, setDictList] = useState([]);
  const [dictMap, setDictMap] = useState({});
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!dictType) return undefined;
    const cached = dictCache.get(dictType);
    const now = Date.now();
    if (cached && now - cached.ts < TTL) {
      setDictList(cached.list);
      setDictMap(cached.map);
      return undefined;
    }
    let active = true;
    setLoading(true);
    dictApi
      .listData(dictType, opts.all)
      .then((list) => {
        const arr = Array.isArray(list) ? list : [];
        const map = {};
        arr.forEach((d) => {
          map[d.dictValue] = d;
        });
        dictCache.set(dictType, { ts: Date.now(), list: arr, map });
        if (active) {
          setDictList(arr);
          setDictMap(map);
        }
      })
      .catch(() => {
        if (active) {
          setDictList([]);
          setDictMap({});
        }
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [dictType, opts.all]);

  return { dictList, dictMap, loading };
}

export default useDict;
