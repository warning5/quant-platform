import { create } from 'zustand';
import { factorApi } from '../api';
import { CATEGORY_LABELS } from '../pages/factors/constants';

/**
 * 全局因子元信息 Zustand Store
 * 替代各页面独立使用 useFactorMeta Hook，实现跨组件共享缓存
 * 只请求一次，所有消费页面共享同一份数据
 */
const useFactorStore = create((set, get) => ({
  factorList: [],
  factorMeta: {},
  loading: false,
  loaded: false,  // 是否已加载过（避免重复请求）

  /** 加载因子定义（仅在 loaded=false 时请求） */
  load: async () => {
    const state = get();
    if (state.loaded || state.loading) return;

    set({ loading: true });
    try {
      const res = await factorApi.getAllDefinitions();
      const content = res?.records || res?.content || res || [];
      const list = Array.isArray(content) ? content : [];

      // 构建 factorCode -> 元信息映射
      const meta = {};
      for (const f of list) {
        const code = f.factorCode;
        const category = f.category;
        meta[code] = {
          cat: CATEGORY_LABELS[category] || category || '',
          desc: f.factorName || f.description || code,
          category,
          factorName: f.factorName || '',
          status: f.status || '',
          dataFrequency: f.dataFrequency || '',
        };
      }

      set({ factorList: list, factorMeta: meta, loading: false, loaded: true });
    } catch {
      set({ loading: false });
    }
  },

  /** 强制重新加载 */
  reload: async () => {
    set({ loaded: false, loading: false });
    await get().load();
  },
}));

export default useFactorStore;
