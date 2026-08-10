import { useState, useEffect, useCallback } from 'react';
import { View, Text } from '@tarojs/components';
import Taro, { useRouter } from '@tarojs/taro';
import { factorApi } from '../../api';
import { formatDate, priceColor } from '../../utils/format';
import './index.scss';

const CAT_TEXT = {
  MOMENTUM: '动量', VALUE: '价值', QUALITY: '质量', VOLATILITY: '波动率',
  TECHNICAL: '技术', FUNDAMENTAL: '基本面', SENTIMENT: '情绪', CUSTOM: '自定义'
};
const STATUS_TEXT = { ACTIVE: '启用', TESTING: '测试中', DRAFT: '草稿', DEPRECATED: '停用', DEGRADED: '降级' };

export default function FactorDetailPage() {
  const { params } = useRouter();
  const id = params.id;

  const [factor, setFactor] = useState(null);
  const [icTrend, setIcTrend] = useState(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    try {
      const [f, t] = await Promise.all([
        factorApi.get(id),
        factorApi.icTrend(id, { forwardDays: 5 }).catch(() => null)
      ]);
      setFactor(f);
      setIcTrend(t);
    } catch (e) {
      console.error('加载因子详情失败', e);
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => { load(); }, [load]);

  const trend = icTrend && icTrend.trend ? icTrend.trend : [];
  const recent = trend.slice(-30).reverse();
  const latest = trend.length > 0 ? trend[trend.length - 1] : null;

  return (
    <View className='factor-detail-page'>
      {loading ? (
        <View className='empty-state'><Text className='empty-text'>加载中...</Text></View>
      ) : !factor ? (
        <View className='empty-state'><Text className='empty-text'>因子不存在</Text></View>
      ) : (
        <View>
          {/* 因子概览 */}
          <View className='section card'>
            <View className='fd-top'>
              <Text className='fd-name'>{factor.factorName}</Text>
              <Text className='fd-status'>{STATUS_TEXT[factor.status] || factor.status}</Text>
            </View>
            <View className='fd-meta'>
              <Text className='fd-code'>{factor.factorCode}</Text>
              <Text className='fd-cat'>{CAT_TEXT[factor.category] || factor.category}</Text>
            </View>
            {factor.description && <Text className='fd-desc'>{factor.description}</Text>}
            {factor.stockPool && (
              <View className='fd-row'>
                <Text className='fd-label'>适配股票池</Text>
                <Text className='fd-value'>{factor.stockPool}</Text>
              </View>
            )}
          </View>

          {/* IC 概览 */}
          <View className='section card'>
            <View className='section-title'>IC 概览（前瞻 {icTrend ? icTrend.forwardDays : 5} 日）</View>
            {latest ? (
              <View className='ic-grid'>
                <View className='ic-item'>
                  <Text className='ic-label'>最新 IC</Text>
                  <Text className={`ic-value ${priceColor(latest.icValue)}`}>
                    {latest.icValue != null ? Number(latest.icValue).toFixed(3) : '--'}
                  </Text>
                </View>
                <View className='ic-item'>
                  <Text className='ic-label'>IC 20日均</Text>
                  <Text className='ic-value'>{latest.ic20dAvg != null ? Number(latest.ic20dAvg).toFixed(3) : '--'}</Text>
                </View>
                <View className='ic-item'>
                  <Text className='ic-label'>IC 60日均</Text>
                  <Text className='ic-value'>{latest.ic60dAvg != null ? Number(latest.ic60dAvg).toFixed(3) : '--'}</Text>
                </View>
                <View className='ic-item'>
                  <Text className='ic-label'>IR 20日</Text>
                  <Text className='ic-value'>{latest.ir20d != null ? Number(latest.ir20d).toFixed(3) : '--'}</Text>
                </View>
                <View className='ic-item'>
                  <Text className='ic-label'>IR 60日</Text>
                  <Text className='ic-value'>{latest.ir60d != null ? Number(latest.ir60d).toFixed(3) : '--'}</Text>
                </View>
                <View className='ic-item'>
                  <Text className='ic-label'>日期</Text>
                  <Text className='ic-value'>{formatDate(latest.tradeDate)}</Text>
                </View>
              </View>
            ) : (
              <Text className='ic-empty'>暂无 IC 数据</Text>
            )}
          </View>

          {/* IC 趋势列表 */}
          {recent.length > 0 && (
            <View className='section card'>
              <View className='section-title'>IC 趋势（近 {recent.length} 日）</View>
              <View className='trend-list'>
                {recent.map((r, i) => (
                  <View key={i} className='trend-row'>
                    <Text className='trend-date'>{formatDate(r.tradeDate)}</Text>
                    <Text className={`trend-ic ${priceColor(r.icValue)}`}>
                      {r.icValue != null ? Number(r.icValue).toFixed(3) : '--'}
                    </Text>
                    <Text className='trend-ir'>{r.ir20d != null ? Number(r.ir20d).toFixed(3) : '--'}</Text>
                  </View>
                ))}
              </View>
            </View>
          )}
        </View>
      )}
    </View>
  );
}
