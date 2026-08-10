import { useState, useEffect, useCallback } from 'react';
import { View, Text } from '@tarojs/components';
import Taro from '@tarojs/taro';
import { strategyApi } from '../../api';
import { formatDate } from '../../utils/format';
import './index.scss';

const STATUS_TEXT = { ACTIVE: '运行中', TESTING: '测试中', DRAFT: '草稿', DEPRECATED: '已停用' };
const STATUS_CLASS = { ACTIVE: 'st-active', TESTING: 'st-testing', DRAFT: 'st-draft', DEPRECATED: 'st-deprecated' };

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

  return (
    <View className='strategy-list-page'>
      {loading ? (
        <View className='empty-state'><Text className='empty-text'>加载中...</Text></View>
      ) : list.length > 0 ? (
        list.map((s) => (
          <View key={s.id} className='strategy-card card' onClick={() => goDetail(s)}>
            <View className='strategy-top'>
              <Text className='strategy-name'>{s.strategyName}</Text>
              <Text className={`strategy-status ${STATUS_CLASS[s.status] || ''}`}>
                {STATUS_TEXT[s.status] || s.status || '--'}
              </Text>
            </View>
            <View className='strategy-meta'>
              <Text className='strategy-code'>{s.strategyCode}</Text>
              {s.latestDate && (
                <Text className='strategy-date'>最新推荐 {formatDate(s.latestDate)}</Text>
              )}
            </View>
          </View>
        ))
      ) : (
        <View className='empty-state'><Text className='empty-text'>暂无策略数据</Text></View>
      )}
    </View>
  );
}
