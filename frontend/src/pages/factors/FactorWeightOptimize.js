import React, { useState, useEffect } from 'react';
import {
  Card, Row, Col, Select, Button, Tag, Spin, Alert, Space,
  Typography, Table, Tooltip, Divider, Statistic,
} from 'antd';
import {
  BarChartOutlined, ReloadOutlined, AimOutlined, InfoCircleOutlined,
} from '@ant-design/icons';
import ReactECharts from 'echarts-for-react';
import { factorApi } from '../../api';

const { Text, Title } = Typography;
const fmtPct = (v, d = 2) => v != null ? `${(+v * 100).toFixed(d)}%` : '-';
const fmt = (v, d = 3) => v != null ? (+v).toFixed(d) : '-';

// ─── 权重饼图 ──────────────────────────────────────────────────────────────────
function WeightPieChart({ weights, title }) {
  if (!weights || weights.length === 0) return null;
  const option = {
    backgroundColor: 'transparent',
    tooltip: {
      trigger: 'item',
      formatter: p => `${p.name}: ${(p.value * 100).toFixed(2)}%`,
    },
    legend: { orient: 'vertical', right: 10, top: 'center', type: 'scroll' },
    series: [{
      type: 'pie',
      radius: ['40%', '70%'],
      center: ['40%', '50%'],
      data: weights.map(w => ({ name: w.factorCode, value: +w.weight })),
      label: {
        formatter: p => `${p.name}\n${(p.value * 100).toFixed(1)}%`,
        fontSize: 11,
      },
      emphasis: {
        itemStyle: { shadowBlur: 10, shadowOffsetX: 0, shadowColor: 'rgba(0,0,0,0.5)' },
      },
    }],
  };
  return (
    <Card title={title} size="small">
      <ReactECharts option={option} style={{ height: 260 }} notMerge={true} />
    </Card>
  );
}

// ─── 相关系数热力图 ────────────────────────────────────────────────────────────
function CorrHeatmapChart({ corrMatrix, factorCodes }) {
  if (!corrMatrix || !factorCodes) return null;

  const data = [];
  for (let i = 0; i < factorCodes.length; i++) {
    for (let j = 0; j < factorCodes.length; j++) {
      data.push([j, i, +(corrMatrix[i]?.[j] ?? 0).toFixed(4)]);
    }
  }

  const option = {
    backgroundColor: 'transparent',
    tooltip: {
      formatter: p => `${factorCodes[p.data[1]]} × ${factorCodes[p.data[0]]}: <b>${p.data[2]}</b>`,
    },
    grid: { top: 20, left: 80, right: 100, bottom: 40 },
    xAxis: {
      type: 'category', data: factorCodes, axisLabel: { rotate: 30, fontSize: 11 }, splitArea: { show: true },
    },
    yAxis: {
      type: 'category', data: factorCodes, axisLabel: { fontSize: 11 }, splitArea: { show: true },
    },
    visualMap: {
      min: -1, max: 1, calculable: true,
      orient: 'vertical', right: 0, top: 'center',
      inRange: { color: ['#3f8600', '#f5f5f5', '#cf1322'] },
    },
    series: [{
      type: 'heatmap',
      data,
      label: { show: factorCodes.length <= 8, formatter: p => p.data[2] },
    }],
  };

  return (
    <Card title="因子相关系数矩阵" size="small">
      <ReactECharts option={option} style={{ height: 280 }} notMerge={true} />
    </Card>
  );
}

// ─── 有效前沿 ──────────────────────────────────────────────────────────────────
function EfficientFrontierChart({ frontier }) {
  if (!frontier || frontier.length === 0) return null;

  const option = {
    backgroundColor: 'transparent',
    tooltip: {
      trigger: 'item',
      formatter: p => `波动率: ${(p.data[0] * 100).toFixed(2)}%<br/>收益率: ${(p.data[1] * 100).toFixed(2)}%<br/>Sharpe: ${p.data[2]}`,
    },
    grid: { top: 20, left: 70, right: 20, bottom: 50 },
    xAxis: { type: 'value', name: '年化波动率', axisLabel: { formatter: v => `${(v * 100).toFixed(0)}%`, fontSize: 11 }, nameLocation: 'end' },
    yAxis: { type: 'value', name: '年化收益率', axisLabel: { formatter: v => `${(v * 100).toFixed(0)}%`, fontSize: 11 }, nameLocation: 'end' },
    series: [{
      type: 'scatter',
      data: frontier.map(p => [p.volatility, p.return, p.sharpe]),
      symbolSize: 8,
      itemStyle: {
        color: p => {
          const sharpe = p.data[2];
          const maxS = Math.max(...frontier.map(f => f.sharpe));
          const ratio = Math.max(0, sharpe / maxS);
          const r = Math.round(207 * (1 - ratio) + 82 * ratio);
          const g = Math.round(19 * (1 - ratio) + 196 * ratio);
          return `rgb(${r},${g},26)`;
        },
      },
    }],
  };

  return (
    <Card title="有效前沿（Markowitz）" size="small">
      <ReactECharts option={option} style={{ height: 240 }} notMerge={true} />
    </Card>
  );
}

// ─── 主面板 ────────────────────────────────────────────────────────────────────
export default function FactorWeightOptimizePanel({ defaultFactorCodes = [] }) {
  const [factorList, setFactorList] = useState([]);
  const [selectedCodes, setSelectedCodes] = useState(defaultFactorCodes);
  const [method, setMethod] = useState('MARKOWITZ');
  const [startDate, setStartDate] = useState('2025-01-01');
  const [endDate, setEndDate] = useState('2025-12-31');
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    factorApi.getAllDefinitions()
      .then(res => setFactorList(res.records || []))
      .catch(e => console.error('加载因子定义失败:', e));
  }, []);

  const handleOptimize = () => {
    if (selectedCodes.length < 2) { setError('至少选择2个因子'); return; }
    setLoading(true);
    setError(null);
    factorApi.weightOptimize(selectedCodes, startDate, endDate, method)
      .then(res => setResult(res.data))
      .catch(e => setError(e.message || '优化失败'))
      .finally(() => setLoading(false));
  };

  const methodLabels = { EQUAL: '等权', MARKOWITZ: '均值-方差（最大Sharpe）', RISK_PARITY: '风险平价' };

  return (
    <Card
      title={<><AimOutlined style={{ marginRight: 6 }} />因子组合权重优化</>}
      style={{ marginBottom: 16 }}
    >
      {/* 控制区 */}
      <Row gutter={8} style={{ marginBottom: 12 }} align="bottom">
        <Col span={10}>
          <Text strong>因子</Text>
          <Select
            mode="multiple"
            value={selectedCodes}
            onChange={setSelectedCodes}
            style={{ width: '100%', marginTop: 4 }}
            placeholder="选择 2-10 个因子"
            maxTagCount={4}
            showSearch
            optionFilterProp="label"
            options={factorList.map(f => ({ value: f.factorCode, label: `${f.factorCode} — ${f.factorName}` }))}
          />
        </Col>
        <Col span={4}>
          <Text strong>优化方法</Text>
          <Select value={method} onChange={setMethod} style={{ width: '100%', marginTop: 4 }}>
            <Select.Option value="EQUAL">等权</Select.Option>
            <Select.Option value="MARKOWITZ">Markowitz</Select.Option>
            <Select.Option value="RISK_PARITY">风险平价</Select.Option>
          </Select>
        </Col>
        <Col span={3}>
          <Text strong>开始日期</Text>
          <input
            type="text" value={startDate} onChange={e => setStartDate(e.target.value)}
            style={{ width: '100%', marginTop: 4, border: '1px solid #d9d9d9', borderRadius: 4, padding: '4px 8px', height: 32 }}
          />
        </Col>
        <Col span={3}>
          <Text strong>结束日期</Text>
          <input
            type="text" value={endDate} onChange={e => setEndDate(e.target.value)}
            style={{ width: '100%', marginTop: 4, border: '1px solid #d9d9d9', borderRadius: 4, padding: '4px 8px', height: 32 }}
          />
        </Col>
        <Col span={4}>
          <Button
            type="primary"
            block
            icon={<AimOutlined />}
            onClick={handleOptimize}
            loading={loading}
            style={{ marginTop: 20 }}
          >
            开始优化
          </Button>
        </Col>
      </Row>

      {error && <Alert type="error" message={error} showIcon style={{ marginBottom: 12 }} />}

      <Spin spinning={loading} tip="正在优化因子权重...">
        {result && (
          <>
            {/* 预期指标 */}
            <Row gutter={12} style={{ marginBottom: 12 }}>
              <Col span={6}>
                <Card size="small" style={{ textAlign: 'center' }}>
                  <div style={{ fontSize: 11, color: '#888' }}>优化方法</div>
                  <Tag color="blue" style={{ marginTop: 4 }}>{methodLabels[result.method] || result.method}</Tag>
                </Card>
              </Col>
              <Col span={6}>
                <Card size="small" style={{ textAlign: 'center' }}>
                  <Statistic title="预期年化收益" value={fmtPct(result.portfolioReturn)} valueStyle={{ color: +result.portfolioReturn > 0 ? '#cf1322' : '#3f8600', fontSize: 16 }} />
                </Card>
              </Col>
              <Col span={6}>
                <Card size="small" style={{ textAlign: 'center' }}>
                  <Statistic title="预期年化波动率" value={fmtPct(result.portfolioVolatility)} valueStyle={{ color: '#fa8c16', fontSize: 16 }} />
                </Card>
              </Col>
              <Col span={6}>
                <Card size="small" style={{ textAlign: 'center' }}>
                  <Statistic title="预期Sharpe比率" value={fmt(result.sharpeRatio)} valueStyle={{ color: +result.sharpeRatio > 1 ? '#52c41a' : '#262626', fontSize: 16 }} />
                </Card>
              </Col>
            </Row>

            <Row gutter={12}>
              {/* 权重饼图 */}
              <Col span={8}>
                <WeightPieChart weights={result.weights} title={`权重分配（${methodLabels[result.method] || result.method}）`} />
              </Col>

              {/* 权重明细表 */}
              <Col span={8}>
                <Card title="权重明细" size="small">
                  <Table
                    dataSource={result.weights}
                    rowKey="factorCode"
                    size="small"
                    pagination={false}
                    columns={[
                      { title: '因子', dataIndex: 'factorCode', key: 'code', render: v => <Tag color="geekblue">{v}</Tag> },
                      {
                        title: '权重', dataIndex: 'weight', key: 'weight',
                        render: v => (
                          <Space>
                            <span style={{ fontWeight: 600 }}>{fmtPct(v)}</span>
                            <div style={{ display: 'inline-block', width: +(v * 80).toFixed(0), height: 8, background: '#1677ff', borderRadius: 4, verticalAlign: 'middle' }} />
                          </Space>
                        ),
                      },
                      { title: '年化收益', dataIndex: 'meanReturn', key: 'ret', render: v => <span style={{ color: +v > 0 ? '#cf1322' : '#3f8600' }}>{fmtPct(v)}</span> },
                      { title: '波动率', dataIndex: 'volatility', key: 'vol', render: v => fmtPct(v) },
                    ]}
                  />
                </Card>
              </Col>

              {/* 相关系数 */}
              <Col span={8}>
                <CorrHeatmapChart corrMatrix={result.correlationMatrix} factorCodes={result.factorCodes} />
              </Col>
            </Row>

            {/* 有效前沿（仅 Markowitz 时显示） */}
            {result.efficientFrontier && (
              <div style={{ marginTop: 12 }}>
                <EfficientFrontierChart frontier={result.efficientFrontier} />
              </div>
            )}
          </>
        )}

        {!result && !loading && (
          <div style={{ textAlign: 'center', padding: '24px 0', color: '#8c8c8c' }}>
            <BarChartOutlined style={{ fontSize: 36, marginBottom: 8 }} />
            <div>选择多个因子，选择优化方法，点击「开始优化」计算最优因子权重组合</div>
          </div>
        )}
      </Spin>
    </Card>
  );
}
