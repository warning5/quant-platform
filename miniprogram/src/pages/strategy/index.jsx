import { useState, useEffect, useCallback } from 'react';
import { View, Text } from '@tarojs/components';
import Taro from '@tarojs/taro';
import { strategyApi } from '../../api';
import { formatDate, priceColor } from '../../utils/format';
import './index.scss';

const TYPE_TEXT = {
  FACTOR_LONG: '因子多头',
  LONG_SHORT: '多空对冲',
  MARKET_NEUTRAL: '市场中性',
  MOMENTUM: '动量策略',
  MEAN_REVERSION: '均值回归',
  PATTERN: '形态策略',
  CUSTOM: '自定义'
};

const FREQ_TEXT = {
  DAILY: '日频',
  WEEKLY: '周频',
  MONTHLY: '月频'
};

const STATUS_TEXT = {
  ACTIVE: '运行中',
  TESTING: '测试中',
  DRAFT: '草稿',
  DEPRECATED: '已停用'
};

function fmtPct1(val, withSign = true) {
  if (val == null || isNaN(val)) return '--';
  const num = Number(val) * 100;
  const sign = withSign && num > 0 ? '+' : '';
  return sign + num.toFixed(1) + '%';
}

function fmtIntPct(val) {
  if (val == null || isNaN(val)) return '--';
  return Math.round(Number(val) * 100) + '%';
}

function fmtNumber(val) {
  if (val == null || isNaN(val)) return '--';
  return Number(val).toFixed(2);
}

export default function StrategyListPage() {
  const [list, setList] = useState([]);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await strategyApi.list();
      setList(data || []);
    } catch (e) {
      console.error('加载策略失败', e);
      setList([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const goDetail = (item) => {
    Taro.navigateTo({ url: `/pages/strategy/detail?id=${item.id}` });
  };

  const renderCard = (s) => {
    const isActive = s.status === 'ACTIVE';
    const typeText = TYPE_TEXT[s.strategyType] || s.strategyType || '';
    const freqText = FREQ_TEXT[s.rebalanceFrequency] || s.rebalanceFrequency || '';
    const subtitle = [typeText, freqText].filter(Boolean).join(' · ');
    const statusLabel = isActive ? STATUS_TEXT.ACTIVE : '已暂停';

    return (
      <View key={s.id} className='strategy-card' onClick={() => goDetail(s)}>
        <View className='strategy-header'>
          <View className='strategy-title-wrap'>
            <Text className='strategy-name'>{s.strategyName}</Text>
            {subtitle && <Text className='strategy-subtitle'>{subtitle}</Text>}
          </View>
          <View className={`strategy-status ${isActive ? 'active' : 'inactive'}`}>
            <View className='status-dot' />
            <Text>{statusLabel}</Text>
          </View>
        </View>

        <View className='strategy-metrics'>
          <View className='metric metric-return'>
            <Text className='metric-label'>累计</Text>
            <Text className={`metric-value ${priceColor(s.totalReturn)}`}>{fmtPct1(s.totalReturn)}</Text>
          </View>
          <View className='metric metric-annual'>
            <Text className='metric-label'>年化</Text>
            <Text className='metric-value'>{fmtPct1(s.annualReturn)}</Text>
          </View>
          <View className='metric metric-sharpe'>
            <Text className='metric-label'>夏普</Text>
            <Text className='metric-value'>{fmtNumber(s.sharpeRatio)}</Text>
          </View>
        </View>

        <View className='strategy-footer'>
          <View className='footer-item'>
            <Text className='footer-label'>命中率</Text>
            <Text className='footer-value'>{fmtIntPct(s.hitRateValue)}</Text>
          </View>
          <Text className='footer-divider'>·</Text>
          <View className='footer-item'>
            <Text className='footer-label'>置信度</Text>
            <Text className='footer-value'>{s.confidenceScore ?? '--'}</Text>
          </View>
          <Text className='footer-divider'>·</Text>
          <View className='footer-item'>
            <Text className='footer-label'>下次调仓</Text>
            <Text className='footer-value'>
              {s.nextRebalanceDate ? formatDate(s.nextRebalanceDate).slice(5) : '--'}
            </Text>
          </View>
        </View>
      </View>
    );
  };

  return (
    <View className='strategy-list-page'>
      {loading ? (
        <View className='empty-state'><Text className='empty-text'>加载中...</Text></View>
      ) : list.length > 0 ? (
        list.map(renderCard)
      ) : (
        <View className='empty-state'><Text className='empty-text'>暂无策略数据</Text></View>
      )}
    </View>
  );
}
