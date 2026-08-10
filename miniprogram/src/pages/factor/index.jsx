import { useState, useEffect, useCallback } from 'react';
import { View, Text, Picker } from '@tarojs/components';
import Taro from '@tarojs/taro';
import { factorApi } from '../../api';
import './index.scss';

const CATEGORIES = ['全部', 'MOMENTUM', 'VALUE', 'QUALITY', 'VOLATILITY', 'TECHNICAL', 'FUNDAMENTAL', 'SENTIMENT', 'CUSTOM'];
const CAT_TEXT = {
  MOMENTUM: '动量', VALUE: '价值', QUALITY: '质量', VOLATILITY: '波动率',
  TECHNICAL: '技术', FUNDAMENTAL: '基本面', SENTIMENT: '情绪', CUSTOM: '自定义'
};
const STATUS_TEXT = { ACTIVE: '启用', TESTING: '测试中', DRAFT: '草稿', DEPRECATED: '停用', DEGRADED: '降级' };
const STATUS_CLASS = { ACTIVE: 'f-active', TESTING: 'f-testing', DRAFT: 'f-draft', DEPRECATED: 'f-deprecated', DEGRADED: 'f-degraded' };

export default function FactorListPage() {
  const [list, setList] = useState([]);
  const [catIdx, setCatIdx] = useState(0);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const cat = CATEGORIES[catIdx];
      const params = { status: 'ACTIVE' };
      if (cat !== '全部') params.category = cat;
      const data = await factorApi.list(params);
      setList(data || []);
    } catch (e) {
      console.error('加载因子失败', e);
      setList([]);
    } finally {
      setLoading(false);
    }
  }, [catIdx]);

  useEffect(() => { load(); }, [load]);

  const goDetail = (f) => {
    Taro.navigateTo({ url: `/pages/factor/detail?id=${f.id}` });
  };

  return (
    <View className='factor-list-page'>
      <Picker mode='selector' range={CATEGORIES.map(c => CAT_TEXT[c] || c)} value={catIdx} onChange={(e) => setCatIdx(Number(e.detail.value))}>
        <View className='cat-picker'>
          <Text className='cat-label'>分类</Text>
          <Text className='cat-value'>{CAT_TEXT[CATEGORIES[catIdx]] || CATEGORIES[catIdx]}</Text>
          <Text className='cat-arrow'>▾</Text>
        </View>
      </Picker>

      {loading ? (
        <View className='empty-state'><Text className='empty-text'>加载中...</Text></View>
      ) : list.length > 0 ? (
        list.map((f) => (
          <View key={f.id} className='factor-card card' onClick={() => goDetail(f)}>
            <View className='factor-top'>
              <Text className='factor-name'>{f.factorName}</Text>
              <Text className={`factor-status ${STATUS_CLASS[f.status] || ''}`}>
                {STATUS_TEXT[f.status] || f.status || '--'}
              </Text>
            </View>
            <View className='factor-meta'>
              <Text className='factor-code'>{f.factorCode}</Text>
              <Text className='factor-cat'>{CAT_TEXT[f.category] || f.category || '--'}</Text>
            </View>
          </View>
        ))
      ) : (
        <View className='empty-state'><Text className='empty-text'>暂无因子数据</Text></View>
      )}
    </View>
  );
}
