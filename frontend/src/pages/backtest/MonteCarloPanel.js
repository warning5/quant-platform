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
    grid: { top: 20, left: 60, right: 20, bottom: 60 },
    xAxis: {
      type: 'category', data: days,
      axisLabel: { interval: Math.floor(days.length / 6), fontSize: 11 },
    },
    yAxis: {
      type: 'value',
      axisLabel: { formatter: v => `${v > 0 ? '+' : ''}${v}%`, fontSize: 11 },
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
      formatter: params => `${params[0].name}<br/>频率: ${(+params[0].value * 100).toFixed(1)}%<br/>次数: ${data[params[0].dataIndex]?.count || 0}`,
    },
    grid: { top: 20, left: 60, right: 20, bottom: 60 },
    xAxis: {
      type: 'category',
      data: data.map(d => d.range?.split('~')[0]?.trim() || ''),
      axisLabel: { interval: Math.floor(data.length / 5) - 1, fontSize: 10 },
      name: title,
      nameLocation: 'end',
    },
    yAxis: {
      type: 'value',
      axisLabel: { formatter: v => `${(v * 100).toFixed(0)}%`, fontSize: 11 },
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
      .then(res => setResult(res.data))
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
              {[
                { title: '正收益概率', value: fmtPct(result.profitProbability), color: +result.profitProbability > 0.6 ? '#52c41a' : '#fa8c16',
                  tip: '模拟路径中期末净值 > 1 的比例' },
                { title: 'VaR（95%）', value: fmtPct(result.var95), color: '#cf1322',
                  tip: '95% 置信水平下最大可能损失' },
                { title: 'CVaR（95%）', value: fmtPct(result.cvar95), color: '#cf1322',
                  tip: '超出 VaR 情形下的平均损失（尾部风险）' },
                { title: '中位数净值', value: fmt(result.finalValueP50, 4), color: '#1677ff',
                  tip: '预测期末净值的中位数' },
                { title: '年化收益 P50', value: fmtPct(result.annualReturnP50), color: +result.annualReturnP50 > 0 ? '#cf1322' : '#3f8600',
                  tip: '模拟路径年化收益率中位数' },
                { title: '最大回撤 P50', value: fmtPct(result.maxDrawdownP50), color: '#fa8c16',
                  tip: '模拟路径最大回撤中位数' },
              ].map(m => (
                <Col key={m.title} span={4}>
                  <Card size="small" style={{ textAlign: 'center', background: '#fafafa' }}>
                    <Tooltip title={m.tip}>
                      <div style={{ fontSize: 11, color: '#888', marginBottom: 4 }}>{m.title} <InfoCircleOutlined style={{ fontSize: 10 }} /></div>
                    </Tooltip>
                    <div style={{ fontSize: 16, fontWeight: 700, color: m.color }}>{m.value}</div>
                  </Card>
                </Col>
              ))}
            </Row>

            {/* 置信区间图 */}
            <Card title="净值置信区间（Bootstrap 重采样）" size="small" style={{ marginBottom: 12 }}>
              <ConfidenceBandChart data={result.confidenceBand} />
            </Card>

            {/* 分布直方图 */}
            <Row gutter={12}>
              <Col span={12}>
                <Card title="年化收益率分布" size="small">
                  <HistogramChart data={result.annualReturnHistogram} title="年化收益率" color="#1677ff" />
                  <div style={{ display: 'flex', gap: 12, justifyContent: 'center', marginTop: 4 }}>
                    <Text style={{ fontSize: 11 }}>P5: <Text strong style={{ color: '#cf1322' }}>{fmtPct(result.annualReturnP5)}</Text></Text>
                    <Text style={{ fontSize: 11 }}>P50: <Text strong>{fmtPct(result.annualReturnP50)}</Text></Text>
                    <Text style={{ fontSize: 11 }}>P95: <Text strong style={{ color: '#52c41a' }}>{fmtPct(result.annualReturnP95)}</Text></Text>
                  </div>
                </Card>
              </Col>
              <Col span={12}>
                <Card title="最大回撤分布" size="small">
                  <HistogramChart data={result.maxDrawdownHistogram} title="最大回撤" color="#fa8c16" />
                  <div style={{ display: 'flex', gap: 12, justifyContent: 'center', marginTop: 4 }}>
                    <Text style={{ fontSize: 11 }}>P5: <Text strong style={{ color: '#52c41a' }}>{fmtPct(result.maxDrawdownP5)}</Text></Text>
                    <Text style={{ fontSize: 11 }}>P50: <Text strong>{fmtPct(result.maxDrawdownP50)}</Text></Text>
                    <Text style={{ fontSize: 11 }}>P95: <Text strong style={{ color: '#cf1322' }}>{fmtPct(result.maxDrawdownP95)}</Text></Text>
                  </div>
                </Card>
              </Col>
            </Row>
          </>
        )}
      </Spin>
    </Card>
  );
}
