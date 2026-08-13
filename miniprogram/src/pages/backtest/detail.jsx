import { useState, useEffect, useCallback, useMemo } from 'react';
import { View, Text } from '@tarojs/components';
import Taro, { useRouter } from '@tarojs/taro';
import { strategyApi } from '../../api';
import Curve from '../../components/Curve';
import './detail.scss';

const COLOR = {
  up: '#F6465D',
  down: '#16C784',
  blue: '#3B9EFF',
  weak: '#9AA1AC',
  red: '#E0483B',
};

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
function parseJson(str, fallback = []) {
  if (!str) return fallback;
  try {
    return JSON.parse(str);
  } catch (e) {
    return fallback;
  }
}

export default function BacktestDetailPage() {
  const { params } = useRouter();
  const id = params.id;
  const [loading, setLoading] = useState(true);
  const [data, setData] = useState(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const bt = await strategyApi.backtest(id);
      setData(bt && bt.task ? bt : null);
    } catch (e) {
      console.error('加载回测详情失败', e);
      setData(null);
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => { load(); }, [load]);

  const task = data?.task;
  const report = data?.report;
  const isCompleted = task && task.status === 'COMPLETED' && report;

  const equity = useMemo(() => parseJson(report?.equityCurveJson).map((d) => Number(d.value)), [report]);
  const benchmark = useMemo(() => parseJson(report?.benchmarkCurveJson).map((d) => Number(d.value)), [report]);
  const drawdown = useMemo(() => parseJson(report?.drawdownSeriesJson).map((d) => Number(d.drawdown)), [report]);
  const monthly = useMemo(() => parseJson(report?.monthlyReturnsJson), [report]);

  // 月度热力图单元格配色（涨红跌绿）
  const cellStyle = (ret) => {
    if (ret == null || isNaN(ret)) return { background: '#F4F6FA', color: '#9AA1AC' };
    const mag = Math.min(Math.abs(ret) / 0.15, 1); // 15% 封顶
    if (ret >= 0) {
      return { background: `rgba(246,70,93,${0.12 + mag * 0.5})`, color: '#F6465D' };
    }
    return { background: `rgba(22,199,132,${0.12 + mag * 0.5})`, color: '#16C784' };
  };

  const perfItems = report
    ? [
        ['总收益', sign(report.totalReturn) + pct(report.totalReturn), cls(report.totalReturn)],
        ['年化收益', sign(report.annualReturn) + pct(report.annualReturn), cls(report.annualReturn)],
        ['超额收益', sign(report.excessReturn) + pct(report.excessReturn), cls(report.excessReturn)],
        ['基准收益', sign(report.benchmarkReturn) + pct(report.benchmarkReturn), cls(report.benchmarkReturn)],
      ]
    : [];
  const riskItems = report
    ? [
        ['夏普比率', num(report.sharpeRatio), ''],
        ['索提诺', num(report.sortinoRatio), ''],
        ['卡玛比率', num(report.calmarRatio), ''],
        ['信息比率', num(report.informationRatio), ''],
      ]
    : [];
  const tradeItems = report
    ? [
        ['最大回撤', pct(report.maxDrawdown), 'down'],
        ['回撤天数', (report.maxDrawdownDuration ?? '--') + ' 天', ''],
        ['交易次数', report.totalTrades ?? '--', ''],
        ['胜率', pct(report.winRate), ''],
      ]
    : [];
  const excessItems = report
    ? [
        ['超额收益', sign(report.excessReturn) + pct(report.excessReturn), cls(report.excessReturn)],
        ['Alpha', sign(report.alpha) + pct(report.alpha), cls(report.alpha)],
        ['Beta', num(report.beta), ''],
        ['盈亏比', num(report.profitLossRatio), ''],
      ]
    : [];

  const PlanGrid = ({ items }) => (
    <View className='plan-grid'>
      {items.map((it, i) => (
        <View className='plan-item' key={i}>
          <Text className='plbl'>{it[0]}</Text>
          <Text className={`pval ${it[2] || ''}`}>{it[1]}</Text>
        </View>
      ))}
    </View>
  );

  if (loading) {
    return (
      <View className='bt-detail'>
        <NavBar title='回测详情' />
        <View className='bt-tip'>加载中…</View>
      </View>
    );
  }

  if (!data) {
    return (
      <View className='bt-detail'>
        <NavBar title='回测详情' />
        <View className='bt-tip'>该策略暂无回测记录</View>
      </View>
    );
  }

  return (
    <View className='bt-detail'>
      <NavBar title='回测详情' />
      <View className='bt-scroll'>
        {/* 标题卡 */}
        <View className='card'>
          <View className='title-row'>
            <Text className='nm'>{task.taskName || ('策略' + task.strategyId)}</Text>
            <Text className={isCompleted ? 'pill' : 'pill pill-run'}>
              {isCompleted ? '回测完成' : '回测中'}
            </Text>
          </View>
          <View className='meta'>
            <Text className='tag'>回测口径</Text>
            <Text>{task.startDate} ~ {task.endDate}</Text>
            <Text className='weak'>基准 {task.benchmarkCode || '—'}</Text>
          </View>
        </View>

        {/* 回测进行中 */}
        {!isCompleted && (
          <View className='card'>
            <Text className='section-t' style={{ marginTop: 0 }}>回测进行中</Text>
            <Text className='muted'>数据生成中，回测完成后将展示完整绩效、回撤、归因与稳健性验证结果。</Text>
          </View>
        )}

        {isCompleted && (
          <>
            <View className='card'>
              <Text className='section-t' style={{ marginTop: 0 }}>绩效概览</Text>
              <PlanGrid items={perfItems} />
            </View>

            <View className='card'>
              <Text className='section-t' style={{ marginTop: 0 }}>风险调整指标</Text>
              <PlanGrid items={riskItems} />
            </View>

            <View className='card'>
              <Text className='section-t' style={{ marginTop: 0 }}>回撤与交易</Text>
              <PlanGrid items={tradeItems} />
            </View>

            <View className='card'>
              <Text className='section-t' style={{ marginTop: 0 }}>超额分析</Text>
              <PlanGrid items={excessItems} />
            </View>

            <View className='card'>
              <Text className='section-t' style={{ marginTop: 0 }}>净值 vs 基准</Text>
              {benchmark.length > 1 && (
                <View className='ov-curve'>
                  <Curve values={benchmark} color={COLOR.weak} dashed height={90} />
                  <Curve values={equity} color={COLOR.blue} height={90} />
                </View>
              )}
              <View className='perf'>
                <Text className='weak'>策略</Text>
                <Text className={cls(report.totalReturn)} style={{ fontWeight: 700 }}>
                  {sign(report.totalReturn)}{pct(report.totalReturn)}
                </Text>
                <Text className='weak'>基准</Text>
                <Text className={cls(report.benchmarkReturn)} style={{ fontWeight: 700 }}>
                  {sign(report.benchmarkReturn)}{pct(report.benchmarkReturn)}
                </Text>
              </View>
            </View>

            <View className='card'>
              <Text className='section-t' style={{ marginTop: 0 }}>回撤曲线</Text>
              {drawdown.length > 1 ? (
                <Curve values={drawdown} color={COLOR.red} area areaColor='rgba(224,72,59,0.12)' anchorTop height={90} />
              ) : (
                <View className='ov-empty'>暂无回撤数据</View>
              )}
              <Text className='muted' style={{ marginTop: '12rpx' }}>
                最大回撤 <Text className='down'>{pct(report.maxDrawdown)}</Text> · 回撤持续{' '}
                <Text>{report.maxDrawdownDuration ?? '--'}</Text> 天
              </Text>
            </View>

            <View className='card'>
              <Text className='section-t' style={{ marginTop: 0 }}>月度收益热力图</Text>
              {monthly.length > 0 ? (
                <View className='mh'>
                  {monthly.map((m, i) => (
                    <View className='mh-cell' key={i} style={cellStyle(m.return)}>
                      <Text className='mh-m'>{String(m.month).slice(5)}</Text>
                      <Text className='mh-v'>{sign(m.return)}{pct(m.return, 1)}</Text>
                    </View>
                  ))}
                </View>
              ) : (
                <View className='ov-empty'>暂无月度收益</View>
              )}
            </View>
          </>
        )}
      </View>
    </View>
  );
}

function NavBar({ title }) {
  return (
    <View className='nh'>
      <Text className='title'>{title}</Text>
    </View>
  );
}
