import { useState, useEffect, useCallback } from 'react';
import { View, Text } from '@tarojs/components';
import Taro, { useRouter } from '@tarojs/taro';
import { recommendationApi } from '../../api';
import { formatRatio, formatDate, priceColor } from '../../utils/format';
import './detail.scss';

const ACTION_TEXT = { BUY: '买入', ADD: '增持', HOLD: '持有', WATCH: '观察', REDUCE: '减持', SELL: '卖出' };
function actionClass(tag) {
  if (tag === 'BUY') return 'buy';
  if (tag === 'ADD') return 'add';
  return 'hold';
}
function fmtPct1(v) {
  if (v == null) return '--';
  return (v >= 0 ? '+' : '') + (Number(v) * 100).toFixed(1) + '%';
}
function parseFactorRanks(json) {
  if (!json) return [];
  try {
    const arr = typeof json === 'string' ? JSON.parse(json) : json;
    if (!Array.isArray(arr)) return [];
    return arr.map((f) => ({
      name: f.factorName || f.name || f.factorCode || f.code || '因子',
      value: f.score != null ? Number(f.score) : (f.value != null ? Number(f.value) : null),
      rank: f.rank != null ? Number(f.rank) : null
    }));
  } catch (e) {
    return [];
  }
}

export default function RecommendDetailPage() {
  const { params } = useRouter();
  const code = params.code;
  const date = params.date;
  const strategyId = params.strategyId;

  const [detail, setDetail] = useState(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    if (!code) return;
    setLoading(true);
    try {
      const data = await recommendationApi.getStockDetail(code, {
        ...(strategyId ? { strategyId } : {}),
        ...(date ? { date } : {})
      });
      setDetail(data || null);
      if (data && data.base && data.base.stockName) {
        Taro.setNavigationBarTitle({ title: data.base.stockName });
      }
    } catch (e) {
      console.error('加载个股推荐详情失败', e);
      setDetail(null);
    } finally {
      setLoading(false);
    }
  }, [code, date, strategyId]);

  useEffect(() => { load(); }, [load]);

  if (loading) {
    return <View className='rd-page'><View className='empty-state'><Text className='empty-text'>加载中...</Text></View></View>;
  }
  if (!detail || !detail.base) {
    return <View className='rd-page'><View className='empty-state'><Text className='empty-text'>暂无该股票推荐详情</Text></View></View>;
  }

  const base = detail.base || {};
  const scores = detail.scores || {};
  const factor = detail.factorAttribution || {};
  const signal = detail.signal || {};
  const perf = detail.performance || {};
  const env = detail.marketEnv || {};

  const factorRanks = parseFactorRanks(factor.factorRanks);
  const subScores = [
    { l: '技术', v: scores.technicalScore },
    { l: '资金', v: scores.capitalScore },
    { l: '基本面', v: scores.fundamentalScore },
    { l: '事件', v: scores.eventScore },
    { l: '风险', v: scores.riskScore },
    { l: '流动性', v: scores.liquidityScore }
  ];
  const perfRows = [
    { l: '次日', v: perf.nextDayReturn, ex: perf.nextDayExcessReturn },
    { l: '次周', v: perf.nextWeekReturn, ex: perf.nextWeekExcessReturn },
    { l: '次月', v: perf.nextMonthReturn, ex: perf.nextMonthExcessReturn }
  ];

  return (
    <View className='rd-page'>
      {/* 概览 */}
      <View className='card'>
        <View className='rd-top'>
          <Text className='rd-name'>{base.stockName}</Text>
          <Text className='rd-code'>{base.stockCode}</Text>
        </View>
        <View className='rd-meta'>
          {base.industry && <Text className='tag'>{base.industry}</Text>}
          {base.regime && <Text className='tag tag-regime'>{base.regime}</Text>}
          {base.rankNum != null && <Text className='weak'>排名第 {base.rankNum}</Text>}
        </View>
        {base.recommendDate && (
          <Text className='rd-date'>推荐日期 {formatDate(base.recommendDate)}</Text>
        )}
      </View>

      {/* 综合评分 */}
      <View className='card'>
        <View className='section-title'>综合评分</View>
        <View className='rd-score-row'>
          <Text className='rd-score'>{scores.finalScore != null ? Number(scores.finalScore).toFixed(0) : '--'}</Text>
          <View className='rd-score-sub'>
            <Text className='weak'>因子 {scores.factorScore != null ? Number(scores.factorScore).toFixed(0) : '--'} · 分析 {scores.analysisScore != null ? Number(scores.analysisScore).toFixed(0) : '--'}</Text>
            {scores.factorWeight != null && scores.analysisWeight != null && (
              <Text className='weak'>融合权重 因子 {Math.round(scores.factorWeight * 100)}% / 分析 {Math.round(scores.analysisWeight * 100)}%</Text>
            )}
          </View>
        </View>
        <View className='subscore-grid'>
          {subScores.map((s, i) => (
            <View key={i} className='subscore-item'>
              <Text className='ssl'>{s.l}</Text>
              <Text className={`ssv ${priceColor(s.v)}`}>{s.v != null ? Number(s.v).toFixed(0) : '--'}</Text>
            </View>
          ))}
        </View>
      </View>

      {/* 因子归因 */}
      {factorRanks.length > 0 && (
        <View className='card'>
          <View className='section-title'>因子归因</View>
          {factorRanks.map((f, i) => (
            <View key={i} className='fr-item'>
              <Text className='fr-name'>{f.name}</Text>
              <Text className='fr-val'>{f.value != null ? Number(f.value).toFixed(2) : (f.rank != null ? '第' + f.rank + '名' : '--')}</Text>
            </View>
          ))}
        </View>
      )}

      {/* 买卖信号 */}
      <View className='card'>
        <View className='section-title'>买卖信号</View>
        <View className='sig-row'>
          <Text className={`sig-tag ${actionClass(signal.actionTag)}`}>
            {ACTION_TEXT[signal.actionTag] || signal.actionTag || '--'}
          </Text>
          {signal.suggestedPositionPct != null && (
            <Text className='sig-pos'>建议仓位 {signal.suggestedPositionPct}%</Text>
          )}
        </View>
        {signal.buyReason && <Text className='sig-reason'>{signal.buyReason}</Text>}
        <View className='sig-grid'>
          <View className='sig-cell'>
            <Text className='sg-l'>建议买入价</Text>
            <Text className='sg-v'>{signal.suggestedBuyPrice != null ? signal.suggestedBuyPrice : '--'}</Text>
          </View>
          <View className='sig-cell'>
            <Text className='sg-l'>止损价</Text>
            <Text className={`sg-v ${priceColor(signal.suggestedStopLoss)}`}>{signal.suggestedStopLoss != null ? signal.suggestedStopLoss : '--'}</Text>
          </View>
          <View className='sig-cell'>
            <Text className='sg-l'>止盈价</Text>
            <Text className={`sg-v ${priceColor(signal.suggestedTakeProfit)}`}>{signal.suggestedTakeProfit != null ? signal.suggestedTakeProfit : '--'}</Text>
          </View>
          <View className='sig-cell'>
            <Text className='sg-l'>目标价</Text>
            <Text className={`sg-v ${priceColor(signal.suggestedTargetPrice)}`}>{signal.suggestedTargetPrice != null ? signal.suggestedTargetPrice : '--'}</Text>
          </View>
        </View>
      </View>

      {/* 表现追踪 */}
      <View className='card'>
        <View className='section-title'>表现追踪</View>
        <View className='perf-tbl'>
          <View className='perf-th'>
            <Text className='pth p-col'>区间</Text>
            <Text className='pth p-col'>实际</Text>
            <Text className='pth p-col'>超额</Text>
          </View>
          {perfRows.map((r, i) => (
            <View key={i} className='perf-tr'>
              <Text className='ptd'>{r.l}</Text>
              <Text className={`ptd ${priceColor(r.v)}`}>{fmtPct1(r.v)}</Text>
              <Text className={`ptd ${priceColor(r.ex)}`}>{r.ex != null ? fmtPct1(r.ex) : '--'}</Text>
            </View>
          ))}
        </View>
        {perf.trackingUpdatedAt && (
          <Text className='rd-date'>数据截至 {('' + perf.trackingUpdatedAt).slice(0, 10)}</Text>
        )}
      </View>

      {/* 市场环境 */}
      <View className='card'>
        <View className='section-title'>市场环境</View>
        {env.regime && <Text className='env-regime'>{env.regime}</Text>}
        <View className='env-grid'>
          <View className='env-cell'>
            <Text className='eg-l'>指数收盘</Text>
            <Text className='eg-v'>{env.indexClose != null ? env.indexClose : '--'}</Text>
          </View>
          <View className='env-cell'>
            <Text className='eg-l'>MA20</Text>
            <Text className='eg-v'>{env.indexMa20 != null ? env.indexMa20 : '--'}</Text>
          </View>
          <View className='env-cell'>
            <Text className='eg-l'>MA60</Text>
            <Text className='eg-v'>{env.indexMa60 != null ? env.indexMa60 : '--'}</Text>
          </View>
        </View>
      </View>

      <View style={{ height: '40rpx' }} />
    </View>
  );
}
