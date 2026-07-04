import { useState, useEffect, useCallback } from 'react';
import { View, Text, Picker } from '@tarojs/components';
import Taro, { usePullDownRefresh } from '@tarojs/taro';
import { recommendationApi, confidenceApi } from '../../api';
import { formatPercent, formatDate, priceColor } from '../../utils/format';
import './index.scss';

export default function HistoryPage() {
  const [strategies, setStrategies] = useState([]);
  const [currentStrategyIdx, setCurrentStrategyIdx] = useState(0);
  const [batchHistory, setBatchHistory] = useState([]);
  const [confidenceList, setConfidenceList] = useState([]);
  const [loading, setLoading] = useState(true);

  const currentStrategy = strategies[currentStrategyIdx];

  // 加载策略列表
  useEffect(() => {
    (async () => {
      try {
        console.log('[history] start loading strategies');
        const data = await recommendationApi.strategiesWithData();
        console.log('[history] strategies:', data && data.length);
        if (data && data.length > 0) {
          const formatted = data.map(s => ({
            id: s.strategyId || s.id,
            name: s.strategyName || s.name || `策略${s.strategyId || s.id}`
          }));
          setStrategies(formatted);
        }
        // 同时加载置信度列表
        try {
          const confData = await confidenceApi.getAllLatest();
          setConfidenceList(confData || []);
        } catch (e) {
          console.error('[history] conf err', e);
        }
      } catch (e) {
        console.error('[history] load strategies failed', e);
      }
    })();
  }, []);

  // 加载批次历史
  const loadBatchHistory = useCallback(async (strategyId) => {
    console.log('[history] loadBatchHistory', strategyId);
    setLoading(true);
    try {
      const data = await recommendationApi.getBatchHistory(20, strategyId);
      console.log('[history] batch data:', data && data.length);
      setBatchHistory(data || []);
    } catch (e) {
      console.error('[history] batch err', e);
      setBatchHistory([]);
    } finally {
      setLoading(false);
    }
  }, []);

  // 策略变化时重新加载
  useEffect(() => {
    if (currentStrategy) {
      loadBatchHistory(currentStrategy.id);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentStrategyIdx, currentStrategy]);

  // 下拉刷新
  usePullDownRefresh(async () => {
    if (currentStrategy) {
      await loadBatchHistory(currentStrategy.id);
    }
    Taro.stopPullDownRefresh();
  });

  const onStrategyChange = (e) => {
    setCurrentStrategyIdx(Number(e.detail.value));
  };

  // 获取策略的置信度
  const getConfidence = (strategyId) => {
    return confidenceList.find(c => c.strategyId === strategyId);
  };

  return (
    <View className='history-page'>
      <View className='picker-bar card' style={{ opacity: strategies.length > 0 ? 1 : 0.5 }}>
        {strategies.length > 0 ? (
          <Picker
            mode='selector'
            range={strategies.map(s => s.name)}
            value={currentStrategyIdx}
            onChange={onStrategyChange}
          >
            <View className='picker-inner'>
              <Text className='picker-label'>策略</Text>
              <Text className='picker-value'>{currentStrategy?.name || '加载中'}</Text>
              <Text className='picker-arrow'>▾</Text>
            </View>
          </Picker>
        ) : (
          <View className='picker-inner'>
            <Text className='picker-label'>策略</Text>
            <Text className='picker-value'>加载中...</Text>
          </View>
        )}
      </View>

      {/* 置信度卡片 */}
      {currentStrategy && (() => {
        const conf = getConfidence(currentStrategy.id);
        if (!conf) return null;
        return (
          <View className='confidence-card card'>
            <View className='conf-header'>
              <Text className='conf-title'>策略置信度</Text>
              <Text className={`conf-level conf-${(conf.level || '').toLowerCase()}`}>
                {conf.level === 'UNTRAINED' ? '待训练' : (conf.level || '--')}
              </Text>
            </View>
            {conf.level === 'UNTRAINED' && (
              <View className='conf-hint'>新策略，需累计推荐+追踪数据后才能计算</View>
            )}
            <View className='conf-score-row'>
              <View className='conf-score-item'>
                <Text className='conf-score-label'>综合评分</Text>
                <Text className='conf-score-value'>{conf.score != null ? conf.score.toFixed(0) : '--'}</Text>
              </View>
              <View className='conf-score-item'>
                <Text className='conf-score-label'>命中率</Text>
                <Text className='conf-score-value'>
                  {conf.hitRateValue != null ? (conf.hitRateValue * 100).toFixed(1) + '%' : '--'}
                </Text>
              </View>
              <View className='conf-score-item'>
                <Text className='conf-score-label'>平均收益</Text>
                <Text className={`conf-score-value ${priceColor(conf.avgReturnValue)}`}>
                  {formatPercent(conf.avgReturnValue)}
                </Text>
              </View>
              <View className='conf-score-item'>
                <Text className='conf-score-label'>最大回撤</Text>
                <Text className='conf-score-value text-red'>
                  {conf.maxDrawdownValue != null ? conf.maxDrawdownValue.toFixed(1) + '%' : '--'}
                </Text>
              </View>
            </View>
            {conf.dataAsOfDate && (
              <Text className='conf-date'>数据截至: {formatDate(conf.dataAsOfDate)}</Text>
            )}
          </View>
        );
      })()}

      {/* 批次历史列表 */}
      <View className='batch-section'>
        <Text className='section-title'>批次历史表现</Text>
        {loading ? (
          <View className='empty-state'>
            <Text className='empty-text'>加载中...</Text>
          </View>
        ) : batchHistory.length > 0 ? (
          batchHistory.map((batch, idx) => {
            const hasData = batch.tracked > 0;
            return (
              <View key={idx} className='batch-card card'>
                <View className='batch-header'>
                  <Text className='batch-date'>{formatDate(batch.recommendDate || batch.date)}</Text>
                  {batch.qualityTag && batch.qualityTag !== 'UNTRAINED' && (
                    <Text className={`batch-tag batch-${(batch.qualityTag || '').toLowerCase()}`}>
                      {batch.qualityTag === 'HIGH_QUALITY' ? '高质量' :
                       batch.qualityTag === 'LOW_QUALITY' ? '低质量' : '普通'}
                    </Text>
                  )}
                </View>
                <View className='batch-stats'>
                  <View className='stat-item'>
                    <Text className='stat-label'>推荐数</Text>
                    <Text className='stat-value'>{batch.total || batch.count || '--'}</Text>
                  </View>
                  <View className='stat-item'>
                    <Text className='stat-label'>已追踪</Text>
                    <Text className='stat-value'>{batch.tracked != null ? batch.tracked : 0}</Text>
                  </View>
                  <View className='stat-item'>
                    <Text className='stat-label'>命中率</Text>
                    {hasData ? (
                      <Text className='stat-value text-red'>
                        {(batch.hitRate * 100).toFixed(0)}%
                      </Text>
                    ) : (
                      <Text className='stat-value text-muted'>待追踪</Text>
                    )}
                  </View>
                  <View className='stat-item'>
                    <Text className='stat-label'>次日均收益</Text>
                    {hasData ? (
                      <Text className={`stat-value ${priceColor(batch.avgDayReturn)}`}>
                        {formatPercent(batch.avgDayReturn)}
                      </Text>
                    ) : (
                      <Text className='stat-value text-muted'>--</Text>
                    )}
                  </View>
                </View>
              </View>
            );
          })
        ) : (
          <View className='empty-state'>
            <Text className='empty-text'>暂无历史数据</Text>
          </View>
        )}
      </View>
    </View>
  );
}
