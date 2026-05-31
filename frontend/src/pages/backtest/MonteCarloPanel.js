import React, { useState } from 'react';
import {
  Card, Row, Col, Spin, Button, Alert, Statistic, Tag, Progress,
  Tooltip, Divider, Typography, Space, Table,
} from 'antd';
import {
  ExperimentOutlined, ReloadOutlined, InfoCircleOutlined,
} from '@ant-design/icons';
import ReactECharts from 'echarts-for-react';
import { backtestApi } from '../../api';

const { Text } = Typography;
const fmtPct = (v, d = 2) => v != null ? `${(+v * 100).toFixed(d)}%` : '-';
const fmt = (v, d = 3) => v != null ? (+v).toFixed(d) : '-';

// ─── 置信区间图 ────────────────────────────────────────────────────────────────
function ConfidenceBandChart({ data }) {
  if (!data || data.length === 0) return null;

  const days = data.map(d => `D${d.day}`);
  const p5 = data.map(d => +(d.p5 * 100 - 100).toFixed(2));
  const p25 = data.map(d => +(d.p25 * 100 - 100).toFixed(2));
  const p50 = data.map(d => +(d.p50 * 100 - 100).toFixed(2));
  const p75 = data.map(d => +(d.p75 * 100 - 100).toFixed(2));
  const p95 = data.map(d => +(d.p95 * 100 - 100).toFixed(2));

  const option = {
    backgroundColor: 'transparent',
    tooltip: {
      trigger: 'axis',
      formatter: params => {
        const day = params[0]?.name;
        let html = `<div style="font-weight:600;margin-bottom:4px">${day}</div>`;
        params.forEach(p => {
          html += `<div><span style="color:${p.color}">●</span> ${p.seriesName}：<b>${p.value > 0 ? '+' : ''}${p.value}%</b></div>`;
        });
        return html;
      },
    },
    legend: { data: ['5%下界', '25%下界', '中位数', '75%上界', '95%上界'], bottom: 0, type: 'scroll' },
    grid: { top: 30, left: 85, right: 20, bottom: 75 },
    xAxis: {
      type: 'category', data: days,
      axisLabel: { interval: Math.floor(days.length / 6), fontSize: 11 },
      name: '交易日',
      nameLocation: 'center',
      nameGap: 28,
      nameTextStyle: { fontSize: 12, color: '#666' },
    },
    yAxis: {
      type: 'value',
      axisLabel: { formatter: v => `${v > 0 ? '+' : ''}${v}%`, fontSize: 11 },
      name: '净值变化',
      nameLocation: 'middle',
      nameGap: 55,
      nameRotate: 90,
      nameTextStyle: { fontSize: 12, color: '#666' },
      splitLine: { lineStyle: { type: 'dashed', color: '#f0f0f0' } },
    },
    series: [
      { name: '5%下界', type: 'line', data: p5, smooth: true, symbol: 'none', lineStyle: { width: 1, color: '#cf1322', type: 'dashed' }, areaStyle: { color: 'rgba(207,19,34,0.05)' } },
      { name: '25%下界', type: 'line', data: p25, smooth: true, symbol: 'none', lineStyle: { width: 1, color: '#fa8c16' } },
      { name: '中位数', type: 'line', data: p50, smooth: true, symbol: 'none', lineStyle: { width: 2.5, color: '#1677ff' } },
      { name: '75%上界', type: 'line', data: p75, smooth: true, symbol: 'none', lineStyle: { width: 1, color: '#52c41a' } },
      { name: '95%上界', type: 'line', data: p95, smooth: true, symbol: 'none', lineStyle: { width: 1, color: '#52c41a', type: 'dashed' }, areaStyle: { color: 'rgba(82,196,26,0.05)' } },
    ],
  };
  return <ReactECharts option={option} style={{ height: 320 }} notMerge={true} />;
}

// ─── 收益率分布直方图 ──────────────────────────────────────────────────────────
function HistogramChart({ data, title, color = '#1677ff' }) {
  if (!data || data.length === 0) return null;

  const option = {
    backgroundColor: 'transparent',
    tooltip: {
      trigger: 'axis',
      formatter: params => `${params[0].name}<br/>该区间占比: ${(+params[0].value * 100).toFixed(1)}%<br/>路径数量: ${data[params[0].dataIndex]?.count || 0} 条`,
    },
    grid: { top: 20, left: 60, right: 20, bottom: 75 },
    xAxis: {
      type: 'category',
      data: data.map(d => d.range?.split('~')[0]?.trim() || ''),
      axisLabel: { interval: Math.floor(data.length / 5) - 1, fontSize: 10 },
      name: title,
      nameLocation: 'center',
      nameGap: 28,
      nameTextStyle: { fontSize: 11, color: '#666' },
    },
    yAxis: {
      type: 'value',
      axisLabel: { formatter: v => `${(v * 100).toFixed(0)}%`, fontSize: 11 },
      name: '频率',
      nameLocation: 'middle',
      nameGap: 45,
      nameRotate: 90,
      nameTextStyle: { fontSize: 11, color: '#666' },
    },
    series: [{
      type: 'bar',
      data: data.map(d => d.freq),
      itemStyle: { color },
    }],
  };
  return <ReactECharts option={option} style={{ height: 220 }} notMerge={true} />;
}

// ─── 蒙特卡洛主面板 ────────────────────────────────────────────────────────────
export default function MonteCarloPanel({ taskId }) {
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);
  const [simulations, setSimulations] = useState(500);
  const [horizonDays, setHorizonDays] = useState(252);

  const runSimulation = () => {
    setLoading(true);
    setError(null);
    backtestApi.monteCarlo(taskId, simulations, horizonDays)
      .then(data => setResult(data))
      .catch(e => setError(e.message || '模拟失败'))
      .finally(() => setLoading(false));
  };

  if (!result && !loading) {
    return (
      <Card
        title={<><ExperimentOutlined style={{ marginRight: 6 }} />蒙特卡洛模拟</>}
        extra={
          <Space>
            <Tag>模拟次数</Tag>
            <select value={simulations} onChange={e => setSimulations(+e.target.value)}
              style={{ border: '1px solid #d9d9d9', borderRadius: 4, padding: '2px 6px' }}>
              <option value={200}>200</option>
              <option value={500}>500</option>
              <option value={1000}>1000</option>
            </select>
            <Tag>预测期</Tag>
            <select value={horizonDays} onChange={e => setHorizonDays(+e.target.value)}
              style={{ border: '1px solid #d9d9d9', borderRadius: 4, padding: '2px 6px' }}>
              <option value={63}>1季度</option>
              <option value={126}>半年</option>
              <option value={252}>1年</option>
              <option value={504}>2年</option>
            </select>
            <Button type="primary" onClick={runSimulation} icon={<ExperimentOutlined />}>
              运行模拟
            </Button>
          </Space>
        }
        style={{ marginBottom: 16 }}
      >
        <div style={{ textAlign: 'center', padding: '32px 0', color: '#8c8c8c' }}>
          <ExperimentOutlined style={{ fontSize: 40, marginBottom: 8 }} />
          <div>基于历史日收益率 Bootstrap 重采样，预测未来净值区间和风险指标分布</div>
          <div style={{ fontSize: 12, marginTop: 4 }}>点击「运行模拟」开始</div>
        </div>
        {error && <Alert type="error" message={error} showIcon />}
      </Card>
    );
  }

  return (
    <Card
      title={<><ExperimentOutlined style={{ marginRight: 6 }} />蒙特卡洛模拟</>}
      extra={
        <Space>
          <Tag color="blue">{result?.simulations} 次模拟</Tag>
          <Tag>{result?.horizonDays} 交易日预测</Tag>
          <Button size="small" icon={<ReloadOutlined />} onClick={runSimulation} loading={loading}>重新运行</Button>
        </Space>
      }
      style={{ marginBottom: 16 }}
    >
      <Spin spinning={loading} tip="正在运行蒙特卡洛模拟...">
        {error && <Alert type="error" message={error} showIcon style={{ marginBottom: 12 }} />}

        {result && (
          <>
            {/* 核心风险指标 */}
            <Row gutter={12} style={{ marginBottom: 16 }}>
              {(() => {
                const metrics = [
                  {
                    title: '正收益概率', value: fmtPct(result.profitProbability),
                    color: +result.profitProbability > 0.6 ? '#52c41a' : +result.profitProbability > 0 ? '#fa8c16' : '#cf1322',
                    tip: +result.profitProbability === 0
                      ? '全部路径亏损，无任何一条模拟路径在预测期末能回到成本线以上。策略在历史波动特征下缺乏正期望。'
                      : `模拟路径中期末净值>1的比例为 ${fmtPct(result.profitProbability)}。>60%表示策略有稳定正期望，<30%表示正向收益不稳定。`,
                  },
                  {
                    title: 'VaR（95%）', value: fmtPct(result.var95), color: '#cf1322',
                    tip: `当前 VaR ${fmtPct(result.var95)}，即在95%置信度下，预测期内最大可能损失为 ${fmtPct(result.var95)}。\n\n这是"正常最坏情况"的衡量——如果未来波动与历史一致，只有 5% 的概率会亏得更多。`,
                  },
                  {
                    title: 'CVaR（95%）', value: fmtPct(result.cvar95), color: '#cf1322',
                    tip: `当前 CVaR ${fmtPct(result.cvar95)}，即超出 VaR 的那些极端路径的平均损失。`
                      + (+result.cvar95 > +result.var95 * 1.3
                        ? `\n\n⚠️ CVaR 远超 VaR，说明尾部极端情况非常恶劣——一旦突破 VaR，平均会再亏更多。尾部风险不可忽视。`
                        : '\n\nCVaR 与 VaR 差距不大，尾部风险相对可控。'),
                  },
                  {
                    title: '中位数净值', value: fmt(result.finalValueP50, 4), color: +result.finalValueP50 >= 1 ? '#52c41a' : '#cf1322',
                    tip: +result.finalValueP50 >= 1
                      ? `预测期末中位净值 ${fmt(result.finalValueP50, 4)}，即一半路径净值在此之上。中位净值>1表示策略大概率盈利。`
                      : `预测期末中位净值仅 ${fmt(result.finalValueP50, 4)}，即一半路径的期末净值不高于此。` + (+result.finalValueP50 < 0.5 ? '\n\n⚠️ 中位净值已腰斩，策略持续性极差。' : ''),
                  },
                  {
                    title: '年化收益 P50', value: fmtPct(result.annualReturnP50),
                    color: +result.annualReturnP50 > 0 ? '#cf1322' : '#3f8600',
                    tip: +result.annualReturnP50 >= 0
                      ? `年化收益中位数 ${fmtPct(result.annualReturnP50)}，即一半路径的年化收益在此之上。`
                      : `年化收益中位数 ${fmtPct(result.annualReturnP50)}，即一半路径的收益率不高于此值。` + (+result.annualReturnP50 < -0.3 ? '\n\n⚠️ 中位年化亏损超过30%，该策略不具备持续盈利能力。' : ''),
                  },
                  {
                    title: '最大回撤 P50', value: fmtPct(result.maxDrawdownP50), color: '#fa8c16',
                    tip: +result.maxDrawdownP50 > 0.5
                      ? `最大回撤中位数 ${fmtPct(result.maxDrawdownP50)}，即一半路径的最大回撤超过此值。\n\n⚠️ 中位回撤超过50%，对实盘资金而言心理压力极大，需大幅降低风险暴露才能承受。`
                      : `最大回撤中位数 ${fmtPct(result.maxDrawdownP50)}，为路径最大回撤的典型值。`,
                  },
                ];
                return metrics.map(m => (
                  <Col key={m.title} span={4}>
                    <Card size="small" style={{ textAlign: 'center', background: '#fafafa' }}>
                      <Tooltip title={<div style={{ lineHeight: 1.7, maxWidth: 260 }}>{m.tip}</div>} overlayInnerStyle={{ maxWidth: 280 }}>
                        <div style={{ fontSize: 11, color: '#888', marginBottom: 4 }}>{m.title} <InfoCircleOutlined style={{ fontSize: 10 }} /></div>
                      </Tooltip>
                      <div style={{ fontSize: 16, fontWeight: 700, color: m.color }}>{m.value}</div>
                    </Card>
                  </Col>
                ));
              })()}
            </Row>

            {/* 置信区间图 */}
            <Card title={<>
              净值置信区间（Bootstrap 重采样）
              <Tooltip styles={{ root: { maxWidth: 540 }, body: { maxWidth: 540 } }} title={
                <div style={{ lineHeight: 1.8 }}>
                  <p style={{ margin: 0, fontWeight: 600 }}>P 分位线解读</p>
                  <p style={{ margin: '4px 0' }}>将对 500 条净值路径在每一天排序后，取特定百分位作为区间界：</p>
                  <ul style={{ margin: '4px 0', paddingLeft: 16 }}>
                    <li><b>P5</b>（红色）→ 仅 5% 的路径低于此线，代表<b>「悲观下界」</b></li>
                    <li><b>P25</b> → 25% 路径低于此线，偏悲观情景</li>
                    <li><b>中位数 P50</b>（蓝色）→ 一半路径在此之上，代表<b>「正常预期」</b></li>
                    <li><b>P75</b> → 75% 路径低于此线，偏乐观情景</li>
                    <li><b>P95</b>（深灰）→ 仅 5% 路径高于此线，代表<b>「乐观上界」</b></li>
                  </ul>
                  <p style={{ margin: '4px 0 0' }}><b>P5-P95 灰色带</b> = 90% 的路径落在此区间，为<b>「合理波动范围」</b>。若灰色带整体在 -100% 以下，说明策略在任何情景下都难以生存。</p>
                </div>
              }>
                <InfoCircleOutlined style={{ marginLeft: 6, fontSize: 13, color: '#8c8c8c', cursor: 'help' }} />
              </Tooltip>
            </>} size="small" style={{ marginBottom: 12 }}>
              <ConfidenceBandChart data={result.confidenceBand} />
            </Card>

            {/* 分布直方图 */}
            <Row gutter={12}>
              <Col span={12}>
                <Card title={<>
                  年化收益率分布
                  <Tooltip styles={{ root: { maxWidth: 480 }, body: { maxWidth: 480 } }} title={
                    <div style={{ lineHeight: 1.8 }}>
                      <p style={{ margin: 0, fontWeight: 600 }}>直方图怎么看</p>
                      <p style={{ margin: '4px 0' }}>将 500 条蒙特卡洛模拟路径的期末年化收益率，按区间划分后统计分布：</p>
                      <ul style={{ margin: '4px 0', paddingLeft: 16 }}>
                        <li><b>X 轴</b> → 年化收益率区间（如 -99.9%~-97.5%）</li>
                        <li><b>Y 轴（频率）</b> → 该区间路径数占总路径的比例</li>
                        <li><b>次数</b> → 落入该区间的具体路径数量</li>
                      </ul>
                      <p style={{ margin: '4px 0 0' }}>柱子越高 → 说明策略更容易产生该区间的收益。图中左侧柱子极高，说明绝大多数路径都集中在 -99% 附近。</p>
                    </div>
                  }>
                    <InfoCircleOutlined style={{ marginLeft: 6, fontSize: 13, color: '#8c8c8c', cursor: 'help' }} />
                  </Tooltip>
                </>} size="small">
                  <HistogramChart data={result.annualReturnHistogram} title="年化收益率" color="#1677ff" />
                  <div style={{ display: 'flex', gap: 12, justifyContent: 'center', marginTop: 4 }}>
                    <Text style={{ fontSize: 11 }}>P5: <Text strong style={{ color: '#cf1322' }}>{fmtPct(result.annualReturnP5)}</Text></Text>
                    <Text style={{ fontSize: 11 }}>P50: <Text strong>{fmtPct(result.annualReturnP50)}</Text></Text>
                    <Text style={{ fontSize: 11 }}>P95: <Text strong style={{ color: '#52c41a' }}>{fmtPct(result.annualReturnP95)}</Text></Text>
                  </div>
                </Card>
              </Col>
              <Col span={12}>
                <Card title={<>
                  最大回撤分布
                  <Tooltip styles={{ root: { maxWidth: 480 }, body: { maxWidth: 480 } }} title={
                    <div style={{ lineHeight: 1.8 }}>
                      <p style={{ margin: 0, fontWeight: 600 }}>直方图怎么看</p>
                      <p style={{ margin: '4px 0' }}>将 500 条蒙特卡洛模拟路径各自经历过的最大回撤，按区间划分后统计分布：</p>
                      <ul style={{ margin: '4px 0', paddingLeft: 16 }}>
                        <li><b>X 轴</b> → 最大回撤区间（如 93%~95%）</li>
                        <li><b>Y 轴（频率）</b> → 该区间路径数占总路径的比例</li>
                        <li><b>次数</b> → 经历过该区回撤的具体路径数量</li>
                      </ul>
                      <p style={{ margin: '4px 0 0' }}>柱子集中在右侧 → 回撤普遍较大；集中在左侧 → 回撤可控。回撤是"从峰值到谷底的最大跌幅"，不是期末总亏损。</p>
                    </div>
                  }>
                    <InfoCircleOutlined style={{ marginLeft: 6, fontSize: 13, color: '#8c8c8c', cursor: 'help' }} />
                  </Tooltip>
                </>} size="small">
                  <HistogramChart data={result.maxDrawdownHistogram} title="最大回撤" color="#fa8c16" />
                  <div style={{ display: 'flex', gap: 12, justifyContent: 'center', marginTop: 4 }}>
                    <Text style={{ fontSize: 11 }}>P5: <Text strong style={{ color: '#52c41a' }}>{fmtPct(result.maxDrawdownP5)}</Text></Text>
                    <Text style={{ fontSize: 11 }}>P50: <Text strong>{fmtPct(result.maxDrawdownP50)}</Text></Text>
                    <Text style={{ fontSize: 11 }}>P95: <Text strong style={{ color: '#cf1322' }}>{fmtPct(result.maxDrawdownP95)}</Text></Text>
                  </div>
                </Card>
              </Col>
            </Row>

            {/* ── 结论 ── */}
            {(() => {
              const p = +result.profitProbability;
              const r = +result.annualReturnP50;
              const dd = +result.maxDrawdownP50;
              let type, title, desc;
              if (p === 0 || r < -0.5) {
                type = 'error'; title = '结论：策略无生存空间';
                desc = `全部模拟路径亏损，中位年化收益 ${fmtPct(r)}，中位最大回撤 ${fmtPct(dd)}。以历史波动特征看，当前策略不仅无法盈利，且大概率在一年内归零。建议暂停使用，先排查因子有效性。`;
              } else if (p < 0.4) {
                type = 'warning'; title = '结论：策略盈亏不稳定';
                desc = `正收益概率仅 ${fmtPct(p)}，中位年化收益 ${fmtPct(r)}。盈利概率偏低，若考虑交易成本实际可能为负。建议优化后重新验证。`;
              } else if (p < 0.6) {
                type = 'info'; title = '结论：策略有改善空间';
                desc = `正收益概率 ${fmtPct(p)}，中位年化收益 ${fmtPct(r)}。处于临界区域，建议优化止盈止损或调仓频率以提升概率。`;
              } else {
                type = 'success'; title = '结论：策略稳健';
                desc = `正收益概率 ${fmtPct(p)}，中位年化收益 ${fmtPct(r)}。策略盈利持续性良好，可考虑增加仓位验证实盘表现。`;
              }
              return (
                <Alert type={type} message={<b>{title}</b>} description={desc} showIcon style={{ marginTop: 12, marginBottom: 12 }} />
              );
            })()}
          </>
        )}
      </Spin>
    </Card>
  );
}
