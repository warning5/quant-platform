import { useState, useEffect, useCallback } from 'react';
import { View, Text } from '@tarojs/components';
import Taro from '@tarojs/taro';
import { strategyApi } from '../../api';
import './index.scss';

// 数字格式化（百分比）
function pct(v, d = 2) {
  if (v == null || isNaN(v)) return '--';
  return (v * 100).toFixed(d) + '%';
}
function num(v, d = 2) {
  if (v == null || isNaN(v)) return '--';
  return Number(v).toFixed(d);
}
function cls(v) {
  return v >= 0 ? 'up' : 'down';
}
function sign(v) {
  return v >= 0 ? '+' : '';
}

export default function BacktestPage() {
  const [loading, setLoading] = useState(true);
  const [records, setRecords] = useState([]);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const strategies = await strategyApi.list();
      // 并发拉取每个策略的回测表现
      const pairs = await Promise.all(
        strategies.map(async (s) => {
          try {
            const bt = await strategyApi.backtest(s.id);
            return { strategy: s, bt };
          } catch (e) {
            return { strategy: s, bt: {} };
          }
        })
      );
      // 仅保留有回测任务的
      const withBt = pairs
        .filter((p) => p.bt && p.bt.task)
        .map((p) => {
          const t = p.bt.task;
          const r = p.bt.report || {};
          return {
            id: p.strategy.id,
            name: t.taskName || p.strategy.strategyName || ('策略' + p.strategy.id),
            status: t.status,
            sharpe: r.sharpeRatio,
            totalReturn: r.totalReturn,
            excessReturn: r.excessReturn,
            benchmarkReturn: r.benchmarkReturn,
            report: r,
          };
        });

      // 概览卡片已移除，直接渲染回测记录列表
      setRecords(withBt);
    } catch (e) {
      console.error('加载回测列表失败', e);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const goDetail = (id) => {
    Taro.navigateTo({ url: `/pages/backtest/detail?id=${id}` });
  };

  return (
    <View className='bt-page'>
      <View className='bt-scroll'>
        <View className='bt-head-row'>
          <Text className='section-t'>回测记录</Text>
          <View className='pill'>近一年</View>
        </View>

        {/* 回测记录列表 */}
        {loading ? (
          <View className='bt-tip'>加载中…</View>
        ) : records.length === 0 ? (
          <View className='bt-tip'>暂无回测记录</View>
        ) : (
          records.map((r) => (
            <View className='sc' key={r.id} onClick={() => goDetail(r.id)}>
              <View className='nm'>{r.name}</View>
              <View className='meta'>
                <Text className={r.status === 'COMPLETED' ? 'tag' : 'tag tag-run'}>
                  {r.status === 'COMPLETED' ? '回测完成' : '回测中'}
                </Text>
                <Text>夏普 {num(r.sharpe)}</Text>
              </View>
              <View className='perf'>
                <Text className='weak'>收益</Text>
                <Text className={cls(r.totalReturn)} style={{ fontWeight: 700 }}>
                  {sign(r.totalReturn)}{pct(r.totalReturn)}
                </Text>
                <Text className='weak'>超额</Text>
                <Text className={cls(r.excessReturn)} style={{ fontWeight: 700 }}>
                  {sign(r.excessReturn)}{pct(r.excessReturn)}
                </Text>
              </View>
            </View>
          ))
        )}
      </View>
    </View>
  );
}
