import { useState, useEffect, useCallback, useMemo } from 'react';
import { View, Text, Input } from '@tarojs/components';
import Taro from '@tarojs/taro';
import { factorApi } from '../../api';
import './index.scss';

const CATS = [
  { val: 'ALL', label: '全部' },
  { val: 'MOMENTUM', label: '动量' },
  { val: 'VALUE', label: '价值' },
  { val: 'QUALITY', label: '质量' },
  { val: 'VOLATILITY', label: '波动率' },
  { val: 'TECHNICAL', label: '技术' },
  { val: 'FUNDAMENTAL', label: '基本面' },
  { val: 'FINANCIAL', label: '财务' },
  { val: 'SENTIMENT', label: '情绪' },
  { val: 'CHANTHEORY', label: '缠论' },
  { val: 'LIQUIDITY', label: '流动性' },
  { val: 'VOLUME_PRICE', label: '量价' },
];
const CAT_TEXT = CATS.reduce((m, c) => { m[c.val] = c.label; return m; }, {});

const STATUSES = [
  { val: 'ALL', label: '全部' },
  { val: 'ACTIVE', label: '运行中' },
  { val: 'DEGRADED', label: '降级' },
  { val: 'DEPRECATED', label: '废弃' },
];

const ST_TEXT = { ACTIVE: '运行中', DEGRADED: '降级', DEPRECATED: '废弃', TESTING: '测试中', DRAFT: '草稿' };
const ST_CLASS = { ACTIVE: 'st-active', DEGRADED: 'st-degraded', DEPRECATED: 'st-deprecated', TESTING: 'st-testing', DRAFT: 'st-draft' };
const EFF_TEXT = { valid: '有效因子', weak: '弱有效', invalid: '无效' };
const EFF_CLASS = { valid: 'badge-valid', weak: 'badge-weak', invalid: 'badge-invalid' };

// 频率：后端 factor_definition 无频率字段，按因子性质给出合理默认（与原型一致：财务类季频，其余日频）
const FREQ_MAP = {
  FIN_ROE: '季频', FIN_NET_PROFIT_YOY: '季频', FIN_EARNINGS_QUALITY: '季频',
  RD: '季频', GROWTH: '季频', EARNINGS_SURPRISE: '季频'
};
const freqOf = (f) => FREQ_MAP[f.factorCode] || '日频';

function fmt3(v) { return v == null ? '--' : Number(v).toFixed(3); }
function fmt2(v) { return v == null ? '--' : Number(v).toFixed(2); }

export default function FactorListPage() {
  const [all, setAll] = useState([]);
  const [keyword, setKeyword] = useState('');
  const [cat, setCat] = useState('ALL');
  const [status, setStatus] = useState('ALL');
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await factorApi.list({ forwardDays: 5 });
      setAll(data || []);
    } catch (e) {
      console.error('加载因子失败', e);
      setAll([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  // 因子池汇总（按真实返回的 status 统计）
  const summary = useMemo(() => {
    const s = { ACTIVE: 0, DEGRADED: 0, DEPRECATED: 0, OTHER: 0 };
    all.forEach((f) => { if (s[f.status] != null) s[f.status]++; else s.OTHER++; });
    return s;
  }, [all]);

  // 客户端过滤：关键词 / 分类 / 状态
  const filtered = useMemo(() => {
    const kw = keyword.trim().toLowerCase();
    return all.filter((f) => {
      if (cat !== 'ALL' && f.category !== cat) return false;
      if (status !== 'ALL' && f.status !== status) return false;
      if (kw && !(f.factorName || '').toLowerCase().includes(kw) && !(f.factorCode || '').toLowerCase().includes(kw)) return false;
      return true;
    });
  }, [all, keyword, cat, status]);

  const goDetail = (f) => Taro.navigateTo({ url: `/pages/factor/detail?id=${f.id}` });

  return (
    <View className='factor-list-page'>
      <View className='search'>
        <Input
          className='search-input'
          placeholder='搜索因子'
          value={keyword}
          onInput={(e) => setKeyword(e.detail.value)}
          confirmType='search'
        />
      </View>

      <View className='chips'>
        {CATS.map((c) => (
          <Text key={c.val} className={`chip ${cat === c.val ? 'on' : ''}`} onClick={() => setCat(c.val)}>{c.label}</Text>
        ))}
      </View>
      <View className='chips'>
        {STATUSES.map((s) => (
          <Text key={s.val} className={`chip ${status === s.val ? 'on' : ''}`} onClick={() => setStatus(s.val)}>{s.label}</Text>
        ))}
      </View>

      <View className='muted'>
        因子池：{summary.ACTIVE} 运行中 · {summary.DEGRADED} 降级 · {summary.DEPRECATED} 废弃
      </View>

      {loading ? (
        <View className='empty-state'><Text className='empty-text'>加载中...</Text></View>
      ) : filtered.length > 0 ? (
        filtered.map((f) => {
          const ic = f.icStat || {};
          return (
            <View key={f.id} className='factor-card card' onClick={() => goDetail(f)}>
              <View className='fc-left'>
                <Text className='fc-name'>{f.factorName}</Text>
                <Text className='fc-code'>{f.factorCode}</Text>
                <View className='fc-tags'>
                  <Text className='cat-tag'>{CAT_TEXT[f.category] || f.category || '--'}</Text>
                  <Text className={`st-pill ${ST_CLASS[f.status] || ''}`}>{ST_TEXT[f.status] || f.status || '--'}</Text>
                  <Text className='freq'>{freqOf(f)}</Text>
                </View>
              </View>
              <View className='fc-right'>
                {ic.hasData ? (
                  <View>
                    <Text className={`badge ${EFF_CLASS[ic.eff] || 'badge-invalid'}`}>{EFF_TEXT[ic.eff] || '无效'}</Text>
                    <Text className='fc-ic'>IC {fmt3(ic.icMean)} · IR {fmt2(ic.icir)}</Text>
                  </View>
                ) : (
                  <Text className='fc-ic weak'>暂无 IC</Text>
                )}
              </View>
            </View>
          );
        })
      ) : (
        <View className='empty-state'><Text className='empty-text'>无匹配因子</Text></View>
      )}
    </View>
  );
}
