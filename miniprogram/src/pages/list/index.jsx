import { useState, useEffect, useCallback } from 'react';
import { View, Text, ScrollView, Picker } from '@tarojs/components';
import Taro, { useDidShow, usePullDownRefresh } from '@tarojs/taro';
import { recommendationApi, confidenceApi, indexApi, stockQuoteApi } from '../../api';
import StockCard from '../../components/StockCard';
import {
  formatPrice,
  formatPercent,
  priceColor,
  confidenceText,
  formatDate
} from '../../utils/format';
import './index.scss';

export default function ListPage() {
  const [loading, setLoading] = useState(true);
  const [strategies, setStrategies] = useState([]);
  const [currentStrategyIdx, setCurrentStrategyIdx] = useState(0);
  const [dates, setDates] = useState([]);
  const [currentDateIdx, setCurrentDateIdx] = useState(0);
  const [recommendations, setRecommendations] = useState([]);
  const [confidence, setConfidence] = useState(null);
  const [indices, setIndices] = useState([]);
  const [hitRate, setHitRate] = useState(null);
  const [quotes, setQuotes] = useState({}); // { stockCode: {price, change, changePct} }

  const currentStrategy = strategies[currentStrategyIdx];
  const currentDate = dates[currentDateIdx];

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
        return formatted[0];
      }
    } catch (e) {
      console.error('加载策略失败', e);
    }
    return null;
  }, []);

  // 加载策略可用日期
  const loadDates = useCallback(async (strategyId) => {
    try {
      const data = await recommendationApi.getDatesByStrategy(strategyId, 30);
      if (data && data.length > 0) {
        setDates(data);
        setCurrentDateIdx(0);
        return data[0];
      } else {
        setDates([]);
        setRecommendations([]);
      }
    } catch (e) {
      console.error('加载日期失败', e);
    }
    return null;
  }, []);

  // 加载推荐列表
  const loadRecommendations = useCallback(async (strategyId, date) => {
    setLoading(true);
    try {
      const data = await recommendationApi.getByStrategyAndDate(strategyId, date);
      setRecommendations(data || []);
      // 加载推荐后批量拉取实时行情
      if (data && data.length > 0) {
        loadQuotes(data);
      }
    } catch (e) {
      console.error('加载推荐失败', e);
      setRecommendations([]);
    } finally {
      setLoading(false);
    }
  }, []);

  // 批量加载实时行情
  const loadQuotes = useCallback(async (recs) => {
    try {
      // 把 000975.SZ 这种带后缀的代码转成纯代码，因为腾讯 API 返回的 key 是纯代码
      const pureCodes = recs.map(r => r.stockCode?.split('.')[0]).filter(Boolean).join(',');
      console.log('[DEBUG] loadQuotes called, pure codes:', pureCodes);
      const data = await stockQuoteApi.getQuotes(pureCodes);
      console.log('[DEBUG] loadQuotes response:', data);
      if (data) {
        // 把返回的 key (纯代码) 映射回带后缀的 code，方便后续按 stockCode 查
        const codeMap = {};
        recs.forEach(r => {
          const pure = r.stockCode?.split('.')[0];
          if (pure && data[pure]) {
            codeMap[r.stockCode] = data[pure];
          }
        });
        setQuotes(codeMap);
      }
    } catch (e) {
      console.error('[DEBUG] loadQuotes failed:', e);
    }
  }, []);

  // 加载置信度
  const loadConfidence = useCallback(async (strategyId) => {
    try {
      const data = await confidenceApi.getLatest(strategyId);
      setConfidence(data);
    } catch (e) {
      setConfidence(null);
    }
  }, []);

  // 加载命中率
  const loadHitRate = useCallback(async (strategyId, date) => {
    try {
      const data = await recommendationApi.getHitRate(strategyId, date);
      setHitRate(data);
    } catch (e) {
      setHitRate(null);
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

  // 初始化
  useEffect(() => {
    (async () => {
      const strategy = await loadStrategies();
      if (strategy) {
        const [,] = await Promise.all([
          loadDates(strategy.id),
          loadConfidence(strategy.id),
          loadIndices()
        ]);
      }
    })();
  }, []);

  // 策略或日期变化时重新加载
  useEffect(() => {
    if (currentStrategy && currentDate) {
      loadRecommendations(currentStrategy.id, currentDate);
      loadHitRate(currentStrategy.id, currentDate);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentStrategyIdx, currentDateIdx, currentDate]);

  // 页面显示时刷新指数
  useDidShow(() => {
    if (indices.length > 0) loadIndices();
  });

  // 下拉刷新
  usePullDownRefresh(async () => {
    if (currentStrategy && currentDate) {
      await loadRecommendations(currentStrategy.id, currentDate);
      await loadIndices();
    }
    Taro.stopPullDownRefresh();
  });

  // 策略切换
  const onStrategyChange = async (e) => {
    const idx = Number(e.detail.value);
    setCurrentStrategyIdx(idx);
    const strategy = strategies[idx];
    if (strategy) {
      await loadDates(strategy.id);
      await loadConfidence(strategy.id);
    }
  };

  // 日期切换
  const onDateChange = (e) => {
    setCurrentDateIdx(Number(e.detail.value));
  };

  // 跳转详情页
  const goDetail = (item) => {
    Taro.navigateTo({
      url: `/pages/detail/index?data=${encodeURIComponent(JSON.stringify(item))}`
    });
  };

  return (
    <View className='list-page'>
      {/* 大盘指数 */}
      {indices.length > 0 && (
        <ScrollView scrollX className='index-bar' enhanced showScrollbar={false}>
          {indices.map((idx) => {
            const cls = priceColor(idx.changePct);
            return (
              <View key={idx.code} className='index-item'>
                <Text className='index-name'>{idx.name}</Text>
                <Text className={`index-price ${cls}`}>{formatPrice(idx.price)}</Text>
                <Text className={`index-change ${cls}`}>
                  {formatPercent(idx.changePct)}
                </Text>
              </View>
            );
          })}
        </ScrollView>
      )}

      {/* 策略+日期选择 */}
      <View className='selector-bar'>
        {strategies.length > 0 && (
          <Picker
            mode='selector'
            range={strategies.map(s => s.name)}
            value={currentStrategyIdx}
            onChange={onStrategyChange}
          >
            <View className='picker-item'>
              <Text className='picker-label'>策略</Text>
              <Text className='picker-value'>{currentStrategy?.name}</Text>
              <Text className='picker-arrow'>▾</Text>
            </View>
          </Picker>
        )}
        {dates.length > 0 && (
          <Picker
            mode='selector'
            range={dates.map(d => formatDate(d))}
            value={currentDateIdx}
            onChange={onDateChange}
          >
            <View className='picker-item'>
              <Text className='picker-label'>推荐日</Text>
              <Text className='picker-value'>{formatDate(currentDate)}</Text>
              <Text className='picker-arrow'>▾</Text>
            </View>
          </Picker>
        )}
      </View>

      {/* 概览卡片 */}
      <View className='overview-bar'>
        <View className='overview-item'>
          <Text className='overview-label'>推荐数</Text>
          <Text className='overview-value'>{recommendations.length}</Text>
        </View>
        <View className='overview-item'>
          <Text className='overview-label'>命中率</Text>
          {hitRate && hitRate.tracked > 0 ? (
            <Text className='overview-value text-red'>
              {(hitRate.hitRate * 100).toFixed(0)}%
            </Text>
          ) : (
            <Text className='overview-value text-muted'>待追踪</Text>
          )}
        </View>
        <View className='overview-item'>
          <Text className='overview-label'>置信度</Text>
          <Text className={`overview-value ${confidence && confidence.level !== 'UNTRAINED' ? 'text-orange' : 'text-muted'}`}>
            {confidence ? confidenceText(confidence.level) : '--'}
          </Text>
        </View>
      </View>

      {/* 推荐列表 */}
      <View className='recommend-list'>
        {loading ? (
          <View className='empty-state'>
            <Text className='empty-text'>加载中...</Text>
          </View>
        ) : recommendations.length > 0 ? (
          recommendations.map((item) => (
            <StockCard
              key={item.id || item.stockCode}
              item={item}
              quote={quotes[item.stockCode]}
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
