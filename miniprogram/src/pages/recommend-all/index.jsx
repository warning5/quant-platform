import { useState, useEffect, useCallback, useRef } from 'react';
import { View, Text, ScrollView } from '@tarojs/components';
import Taro, { useDidShow, useDidHide, usePullDownRefresh } from '@tarojs/taro';
import { recommendationApi, stockQuoteApi } from '../../api';
import StockCard from '../../components/StockCard';
import './index.scss';

/** 从 stockCode 提取纯6位数字代码（"000975.SZ" → "000975"） */
function pureCode(code) {
  if (!code) return '';
  return code.replace(/\.\w+$/, '');
}

// 是否交易时段（周一~周五 09:30-11:30 / 13:00-15:00）
function isTradingTime() {
  const now = new Date();
  const day = now.getDay();
  if (day === 0 || day === 6) return false; // 周末
  const t = now.getHours() * 60 + now.getMinutes();
  const morning = t >= 9 * 60 + 30 && t <= 11 * 60 + 30;
  const afternoon = t >= 13 * 60 && t <= 15 * 60;
  return morning || afternoon;
}

/**
 * 全部推荐页 —— 「智能推荐」区块头「查看全部 ›」跳转目标。
 * 与首页区别：展示该策略下的【全部】推荐股票（不受首页最多 10 条限制）。
 */
export default function RecommendAllPage() {
  const [loading, setLoading] = useState(true);
  const [strategies, setStrategies] = useState([]);
  const [currentStrategyIdx, setCurrentStrategyIdx] = useState(0);
  const [recommendations, setRecommendations] = useState([]);
  const [quotes, setQuotes] = useState({}); // 个股实时行情：code -> {price,change,changePct}

  const currentStrategy = strategies[currentStrategyIdx];

  // 轮询相关引用
  const pollingRef = useRef(null);
  const recommendationsRef = useRef(recommendations);
  useEffect(() => { recommendationsRef.current = recommendations; }, [recommendations]);

  // 加载策略列表
  const loadStrategies = useCallback(async () => {
    try {
      const data = await recommendationApi.strategiesWithData();
      if (data && data.length > 0) {
        const formatted = data.map(s => ({
          id: s.strategyId || s.id,
          name: s.strategyName || s.name || `策略${s.strategyId || s.id}`
        }));
        setStrategies(formatted);
      } else {
        setLoading(false);
      }
    } catch (e) {
      setLoading(false);
    }
  }, []);

  // 加载推荐列表（按 finalScore 降序 + 重编号，不截断 —— 展示全部）
  const loadRecommendations = useCallback(async (strategyId) => {
    setLoading(true);
    try {
      const data = await recommendationApi.getLatest(strategyId);
      let list = Array.isArray(data) ? data : [];
      list.sort((a, b) => Number(b.finalScore || 0) - Number(a.finalScore || 0));
      list = list.map((item, idx) => ({ ...item, rankNum: idx + 1 }));
      setRecommendations(list);
    } catch (e) {
      setRecommendations([]);
    } finally {
      setLoading(false);
    }
  }, []);

  // 推荐列表变化时立即拉取实时行情
  useEffect(() => {
    if (recommendations.length > 0) {
      const codes = recommendations.map(r => pureCode(r.stockCode)).filter(Boolean).join(',');
      if (codes) {
        stockQuoteApi.getQuotes(codes).then(q => {
          const map = q && q.data ? q.data : (q || {});
          setQuotes(map);
        }).catch(() => {});
      }
    }
  }, [recommendations]);

  // 停止轮询
  const stopPolling = useCallback(() => {
    if (pollingRef.current) {
      clearInterval(pollingRef.current);
      pollingRef.current = null;
    }
  }, []);

  // 交易时段内每 3 秒刷新个股实时价
  const startPolling = useCallback(() => {
    stopPolling();
    if (!isTradingTime()) return;
    const tick = async () => {
      const recs = recommendationsRef.current;
      if (recs && recs.length > 0) {
        const codes = recs.map(r => pureCode(r.stockCode)).filter(Boolean).join(',');
        if (!codes) return;
        try {
          const q = await stockQuoteApi.getQuotes(codes);
          const map = q && q.data ? q.data : (q || {});
          setQuotes(map);
        } catch (e) {
          // 实时行情失败不影响主流程
        }
      }
    };
    tick();
    pollingRef.current = setInterval(tick, 3000);
  }, [stopPolling]);

  // 初始化
  useEffect(() => {
    (async () => {
      await loadStrategies();
    })();
    return () => stopPolling();
  }, []);

  // 策略变化时重新加载推荐
  useEffect(() => {
    if (currentStrategy) {
      loadRecommendations(currentStrategy.id);
    }
  }, [currentStrategy]);

  // 页面显示时启动轮询（交易时段内每3秒刷新）
  useDidShow(() => {
    startPolling();
  });

  // 页面隐藏时停止轮询
  useDidHide(() => {
    stopPolling();
  });

  // 下拉刷新
  usePullDownRefresh(async () => {
    if (currentStrategy) {
      await loadRecommendations(currentStrategy.id);
    }
    Taro.stopPullDownRefresh();
  });

  // 跳转详情页（携带推荐数据 + 当前实时行情快照）
  const goDetail = (item) => {
    const q = quotes[pureCode(item.stockCode)];
    const params = { data: JSON.stringify(item) };
    if (q && typeof q === 'object' && Object.keys(q).length > 0) {
      params.quote = JSON.stringify(q);
    }
    const query = Object.entries(params)
      .map(([k, v]) => `${k}=${encodeURIComponent(v)}`)
      .join('&');
    Taro.navigateTo({ url: `/pages/detail/index?${query}` });
  };

  const activeChipId = `chip${currentStrategyIdx}`;

  return (
    <View className='ra-page'>
      {/* 头部：标题 + 数量 */}
      <View className='ra-head'>
        <Text className='ra-title'>全部推荐</Text>
        <Text className='ra-sub'>共 {recommendations.length} 只</Text>
      </View>

      {/* 策略横滑 chips（切换策略） */}
      {strategies.length > 0 && (
        <ScrollView
          scrollX
          className='strat-scroll'
          enhanced
          showScrollbar={false}
          scrollIntoView={activeChipId}
        >
          <View className='strat-strip'>
            {strategies.map((s, idx) => (
              <View
                key={s.id}
                id={`chip${idx}`}
                className={`strat-chip ${idx === currentStrategyIdx ? 'on' : ''}`}
                onClick={() => setCurrentStrategyIdx(idx)}
              >
                {s.name}
              </View>
            ))}
          </View>
        </ScrollView>
      )}

      {/* 推荐卡片列表（全部，含实时现价+涨跌） */}
      <View className='rec-card'>
        {loading ? (
          <View className='empty-state'>
            <Text className='empty-text'>加载中...</Text>
          </View>
        ) : recommendations.length > 0 ? (
          recommendations.map((item) => (
            <StockCard
              key={item.id || item.stockCode}
              item={item}
              liveQuote={quotes[pureCode(item.stockCode)]}
              onClick={() => goDetail(item)}
            />
          ))
        ) : (
          <View className='empty-state'>
            <Text className='empty-text'>暂无推荐数据</Text>
          </View>
        )}
      </View>
    </View>
  );
}
