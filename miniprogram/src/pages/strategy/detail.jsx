import { useState, useEffect, useCallback } from 'react';
import { View, Text } from '@tarojs/components';
import Taro, { useRouter } from '@tarojs/taro';
import { strategyApi, recommendationApi } from '../../api';
import { formatRatio, formatDate, priceColor } from '../../utils/format';
import './index.scss';

const STATUS_TEXT = { ACTIVE: '运行中', TESTING: '测试中', DRAFT: '草稿', DEPRECATED: '已停用' };

export default function StrategyDetailPage() {
  const { params } = useRouter();
  const id = params.id;

  const [strategy, setStrategy] = useState(null);
  const [backtest, setBacktest] = useState(null);
  const [batches, setBatches] = useState([]);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    try {
      const [s, b, bh] = await Promise.all([
        strategyApi.get(id),
        strategyApi.backtest(id).catch(() => null),
        recommendationApi.getBatchHistory(20, id).catch(() => [])
      ]);
      setStrategy(s);
      setBacktest(b);
      setBatches(bh || []);
    } catch (e) {
      console.error('加载策略详情失败', e);
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => { load(); }, [load]);

  const report = backtest && backtest.report ? backtest.report : null;
  const task = backtest && backtest.task ? backtest.task : null;

  const metrics = report ? [
    { label: '总收益率', raw: report.totalReturn, value: formatRatio(report.totalReturn) },
    { label: '年化收益', raw: report.annualReturn, value: formatRatio(report.annualReturn) },
    { label: '超额收益', raw: report.excessReturn, value: formatRatio(report.excessReturn) },
    { label: '基准年化', raw: report.benchmarkAnnualReturn, value: formatRatio(report.benchmarkAnnualReturn) },
    { label: '夏普比率', raw: report.sharpeRatio, value: report.sharpeRatio != null ? Number(report.sharpeRatio).toFixed(2) : '--' },
    { label: '索提诺', raw: report.sortinoRatio, value: report.sortinoRatio != null ? Number(report.sortinoRatio).toFixed(2) : '--' },
    { label: '卡玛比率', raw: report.calmarRatio, value: report.calmarRatio != null ? Number(report.calmarRatio).toFixed(2) : '--' },
    { label: '最大回撤', raw: report.maxDrawdown, value: formatRatio(report.maxDrawdown) },
    { label: '信息比率', raw: report.informationRatio, value: report.informationRatio != null ? Number(report.informationRatio).toFixed(2) : '--' },
    { label: '胜率', raw: report.winRate, value: formatRatio(report.winRate) },
    { label: '盈亏比', raw: report.profitLossRatio, value: report.profitLossRatio != null ? Number(report.profitLossRatio).toFixed(2) : '--' },
    { label: 'Alpha', raw: report.alpha, value: formatRatio(report.alpha) },
    { label: 'Beta', raw: report.beta, value: report.beta != null ? Number(report.beta).toFixed(2) : '--' },
    { label: '波动率', raw: report.volatility, value: formatRatio(report.volatility) },
    { label: '交易次数', raw: report.totalTrades, value: report.totalTrades != null ? String(report.totalTrades) : '--' }
  ] : [];

  return (
    <View className='strategy-detail-page'>
      {loading ? (
        <View className='empty-state'><Text className='empty-text'>加载中...</Text></View>
      ) : !strategy ? (
        <View className='empty-state'><Text className='empty-text'>策略不存在</Text></View>
      ) : (
        <View>
          {/* 策略概览 */}
          <View className='section card'>
            <View className='sd-top'>
              <Text className='sd-name'>{strategy.strategyName}</Text>
              <Text className='sd-status'>{STATUS_TEXT[strategy.status] || strategy.status}</Text>
            </View>
            <View className='sd-meta'>
              <Text className='sd-code'>{strategy.strategyCode}</Text>
              {strategy.strategyType && <Text className='sd-type'>{strategy.strategyType}</Text>}
            </View>
            {strategy.description && <Text className='sd-desc'>{strategy.description}</Text>}
            <View className='sd-row'>
              <Text className='sd-label'>调仓频率</Text>
              <Text className='sd-value'>{strategy.rebalanceFrequency || '--'}</Text>
            </View>
            <View className='sd-row'>
              <Text className='sd-label'>最大持仓</Text>
              <Text className='sd-value'>{strategy.maxPositionCount != null ? strategy.maxPositionCount + ' 只' : '--'}</Text>
            </View>
            {strategy.stopLossPct != null && (
              <View className='sd-row'>
                <Text className='sd-label'>止损比例</Text>
                <Text className='sd-value text-red'>{formatRatio(strategy.stopLossPct)}</Text>
              </View>
            )}
            {strategy.stopProfitPct != null && (
              <View className='sd-row'>
                <Text className='sd-label'>止盈比例</Text>
                <Text className='sd-value text-green'>{formatRatio(strategy.stopProfitPct)}</Text>
              </View>
            )}
          </View>

          {/* 回测表现 */}
          <View className='section card'>
            <View className='section-title'>回测表现</View>
            {task && (
              <View className='bt-task'>
                <Text className='bt-task-name'>{task.taskName || task.strategyCode}</Text>
                <Text className='bt-task-meta'>
                  {task.startDate ? formatDate(task.startDate) : '--'} ~ {task.endDate ? formatDate(task.endDate) : '--'}
                  {task.benchmarkCode ? ' · 基准 ' + task.benchmarkCode : ''}
                </Text>
              </View>
            )}
            {metrics.length > 0 ? (
              <View className='bt-grid'>
                {metrics.map((m, i) => (
                  <View key={i} className='bt-item'>
                    <Text className='bt-label'>{m.label}</Text>
                    <Text className={`bt-value ${priceColor(m.raw)}`}>{m.value}</Text>
                  </View>
                ))}
              </View>
            ) : (
              <Text className='bt-empty'>暂无回测结果</Text>
            )}
          </View>

          {/* 批次历史表现（推荐表现） */}
          <View className='section card'>
            <View className='section-title'>批次历史表现</View>
            {batches.length > 0 ? (
              batches.map((batch, idx) => {
                const hasData = batch.tracked > 0;
                return (
                  <View key={idx} className='batch-card'>
                    <View className='batch-header'>
                      <Text className='batch-date'>{formatDate(batch.recommendDate || batch.date)}</Text>
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
                          <Text className='stat-value text-red'>{formatRatio(batch.hitRate)}</Text>
                        ) : (
                          <Text className='stat-value text-muted'>待追踪</Text>
                        )}
                      </View>
                      <View className='stat-item'>
                        <Text className='stat-label'>次日均收益</Text>
                        {hasData ? (
                          <Text className={`stat-value ${priceColor(batch.avgDayReturn)}`}>
                            {formatRatio(batch.avgDayReturn)}
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
              <Text className='bt-empty'>暂无批次数据</Text>
            )}
          </View>
        </View>
      )}
    </View>
  );
}
