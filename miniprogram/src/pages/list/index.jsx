import { useState, useEffect, useCallback, useRef } from 'react';
import { View, Text, ScrollView } from '@tarojs/components';
import Taro, { useDidShow, useDidHide, usePullDownRefresh } from '@tarojs/taro';
import { recommendationApi, confidenceApi, indexApi, stockQuoteApi } from '../../api';
import StockCard from '../../components/StockCard';
import {
  formatDate,
  regimeText,
  confidenceText,
  weightModeText
} from '../../utils/format';
import './index.scss';

/**
 * 首页（推荐 tab）—— 严格对齐原型 index.html 的「home」屏设计：
 *
 *   ┌─────────────────────────────┐
 *   │ 市场概览          ● 实时行情 │  ← 卡片包裹的指数栏（交易时段每3秒自动刷新）
 *   │ 上证指 深证成 创业板 科创50 上证50 │
 *   │ 3940   14311  3563   1744  2960  │
 *   ├─────────────────────────────┤
 *   │ 07-24  B_价值反转 ICW动态权重 熊市 偏低 │  ← 快照条 pills
 *   ├─────────────────────────────┤
 *   │ [E_风险] [B_价值反转] [D_多因子]  │  ← 策略横滑 chips
 *   ├─────────────────────────────┤
 *   │ ┌─────────────────────────┐ │
 *   │ │ 1  交通银行        83   │ │  ← rec-item 卡片列表（含实时现价+涨跌）
 *   │ │    601328·银行     持有  │ │
 *   │ └─────────────────────────┘ │
 *   └─────────────────────────────┘
 */
export default function ListPage() {
  const [loading, setLoading] = useState(true);
  const [strategies, setStrategies] = useState([]);
  const [currentStrategyIdx, setCurrentStrategyIdx] = useState(0);
  const [recommendations, setRecommendations] = useState([]);
  const [confidence, setConfidence] = useState(null);
  const [indices, setIndices] = useState([]);
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
      console.error('加载策略失败', e);
      setLoading(false);
    }
  }, []);

  // 加载推荐列表（默认取最新批次）—— 按 finalScore 降序排列 + 重编号
  const loadRecommendations = useCallback(async (strategyId) => {
    setLoading(true);
    try {
      const data = await recommendationApi.getLatest(strategyId);
      let list = Array.isArray(data) ? data : [];
      // 按 finalScore 降序排列（高分在前）
      list.sort((a, b) => Number(b.finalScore || 0) - Number(a.finalScore || 0));
      // 首页最多展示 10 只
      list = list.slice(0, 10);
      // 重编号：消除后端返回的重复 rankNum
      list = list.map((item, idx) => ({ ...item, rankNum: idx + 1 }));
      setRecommendations(list);
    } catch (e) {
      console.error('加载推荐失败', e);
      setRecommendations([]);
    } finally {
      setLoading(false);
    }
  }, []);

  // 推荐列表变化时立即拉取实时行情（解耦时序，确保一定触发）
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

  // 加载置信度
  const loadConfidence = useCallback(async (strategyId) => {
    try {
      const data = await confidenceApi.getLatest(strategyId);
      setConfidence(data);
    } catch (e) {
      setConfidence(null);
    }
  }, []);

  // 加载大盘指数
  const loadIndices = useCallback(async () => {
    try {
      const data = await indexApi.getIndices();
      if (data) setIndices(data);
    } catch (e) {
      // 指数加载失败不影响主流程
    }
  }, []);

  /** 从 stockCode 提取纯6位数字代码（"000975.SZ" → "000975"） */
function pureCode(code) {
  if (!code) return '';
  return code.replace(/\.\w+$/, '');
}

// 是否交易时段（周一~周五 09:30-11:30 / 13:00-15:00）
  const isTradingTime = () => {
    const now = new Date();
    const day = now.getDay();
    if (day === 0 || day === 6) return false; // 周末
    const t = now.getHours() * 60 + now.getMinutes();
    const morning = t >= 9 * 60 + 30 && t <= 11 * 60 + 30;
    const afternoon = t >= 13 * 60 && t <= 15 * 60;
    return morning || afternoon;
  };

  // 停止轮询
  const stopPolling = useCallback(() => {
    if (pollingRef.current) {
      clearInterval(pollingRef.current);
      pollingRef.current = null;
    }
  }, []);

  // 交易时段内每 3 秒刷新指数 + 个股实时价
  const startPolling = useCallback(() => {
    stopPolling();
    if (!isTradingTime()) return; // 非交易时段不轮询，省流量
    const tick = async () => {
      loadIndices();
      const recs = recommendationsRef.current;
      if (recs && recs.length > 0) {
        // 提取纯6位代码（后端 monitor/stocks 的 key 是 "000975" 格式）
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
    tick(); // 立即刷一次
    pollingRef.current = setInterval(tick, 3000);
  }, [loadIndices, stopPolling]);

  // 初始化
  useEffect(() => {
    (async () => {
      await loadStrategies();
      await loadIndices();
      startPolling();
    })();
    return () => stopPolling();
  }, []);

  // 策略变化时重新加载推荐 + 置信度
  useEffect(() => {
    if (currentStrategy) {
      loadRecommendations(currentStrategy.id);
      loadConfidence(currentStrategy.id);
    }
  }, [currentStrategy]);

  // 页面显示时启动轮询（交易时段内每3秒刷新）
  useDidShow(() => {
    startPolling();
  });

  // 页面隐藏时停止轮询，省流量
  useDidHide(() => {
    stopPolling();
  });

  // 下拉刷新
  usePullDownRefresh(async () => {
    if (currentStrategy) {
      await loadRecommendations(currentStrategy.id);
      await loadIndices();
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

  // 快照条数据（原型 rec-header）
  const firstRec = recommendations[0];
  const rawDate = firstRec?.recommendDate;
  // 格式化为 "MM-DD 周X"（如 08-07 周四）
  let snapDate = '最新';
  if (rawDate) {
    try {
      const d = new Date(rawDate);
      if (!isNaN(d.getTime())) {
        const m = String(d.getMonth() + 1).padStart(2, '0');
        const day = String(d.getDate()).padStart(2, '0');
        const weekdays = ['日', '一', '二', '三', '四', '五', '六'];
        snapDate = `${m}-${day} 周${weekdays[d.getDay()]}`;
      }
    } catch (_) { /* fallback */ }
  }
  const snapStrategy = currentStrategy?.name || '--';
  const snapWeightMode = weightModeText(firstRec?.weightMode);
  const snapRegime = firstRec?.regime ? regimeText(firstRec.regime) : '—';
  const snapQuality = confidence ? confidenceText(confidence.level) : '--';

  const activeChipId = `chip${currentStrategyIdx}`;

  return (
    <View className='list-page'>
      {/* ===== 1. 市场概览卡片（原型 mkt-card） ===== */}
      <View className='mkt-card'>
        <View className='mkt-head'>
          <Text className='mkt-title'>市场概览</Text>
          <Text className='mkt-live'>● 实时行情</Text>
        </View>
        <ScrollView scrollX className='mkt-body' enhanced showScrollbar={false}>
          {indices.map((idx) => {
            const cls = priceColor(idx.changePct);
            return (
              <View key={idx.code} className='idx-c'>
                <Text className='idx-n'>{idx.name}</Text>
                <Text className={`idx-v ${cls}`}>{formatIdxPrice(idx.price)}</Text>
                <Text className={`idx-ch ${cls}`}>
                  {idx.changePct > 0 ? '+' : ''}{idx.changePct.toFixed(2)}%
                </Text>
              </View>
            );
          })}
        </ScrollView>
      </View>

      {/* ===== 2. 智能推荐区块头（原型 rec-header） ===== */}
      <View className='rec-header'>
        <View className='rh-row rh-title-row'>
          <Text className='rh-title'>智能推荐</Text>
          <Text className='rh-more' onClick={() => Taro.navigateTo({ url: '/pages/recommend-all/index' })}>查看全部 ›</Text>
        </View>
        <View className='rh-row rh-meta-row'>
          <Text className='rh-pill rh-date'><Text className='rh-date-txt'>{snapDate}</Text></Text>
          <Text className='rh-pill rh-strat'>{snapStrategy}</Text>
          <Text className='rh-pill'>{snapWeightMode}</Text>
          <Text className='rh-pill'>{snapRegime}</Text>
        </View>
        <View className='rh-quality-row'>
          <Text className='rh-quality'>置信度：{snapQuality}</Text>
        </View>
      </View>

      {/* ===== 3. 策略横滑 chips（切换策略） ===== */}
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

      {/* ===== 4. 推荐卡片列表（原型 home-recs 包裹在 .card 里） ===== */}
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

/** 指数价格格式化 —— 去掉小数点后多余的0 */
function formatIdxPrice(val) {
  if (val == null) return '--';
  return Number(val).toFixed(2);
}

/** 涨跌颜色 —— 中国股市红涨绿跌 */
function priceColor(val) {
  if (val == null || isNaN(val)) return '';
  const num = Number(val);
  if (num > 0) return 'text-red';
  if (num < 0) return 'text-green';
  return '';
}
