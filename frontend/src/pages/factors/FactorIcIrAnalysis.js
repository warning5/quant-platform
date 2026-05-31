import React, { useState, useEffect, useMemo } from 'react';
import dayjs from 'dayjs';
import {
  Card, Row, Col, Select, Button, Tag, Spin, Alert, Space,
  Typography, Table, Tooltip, Divider, Statistic, DatePicker, InputNumber, App,
} from 'antd';
import {
  BarChartOutlined, LineChartOutlined, ReloadOutlined, InfoCircleOutlined,
  CheckCircleOutlined, WarningOutlined, CloseCircleOutlined,
} from '@ant-design/icons';
import ReactECharts from 'echarts-for-react';
import { factorApi, strategyApi } from '../../api';

const { Text, Title } = Typography;
const { RangePicker } = DatePicker;

// ─── 评估标签 ─────────────────────────────────────────────────────────────────
function AssessmentTag({ assessment }) {
  if (!assessment) return null;
  const map = {
    '有效因子': { color: 'green', icon: <CheckCircleOutlined /> },
    '弱有效': { color: 'orange', icon: <WarningOutlined /> },
    '无效因子': { color: 'red', icon: <CloseCircleOutlined /> },
  };
  const cfg = map[assessment] || { color: 'default', icon: null };
  return <Tag color={cfg.color} icon={cfg.icon}>{assessment}</Tag>;
}

// ─── IC 时序柱状图 ─────────────────────────────────────────────────────────────
function IcBarChart({ icTimeline, factorCode }) {
  if (!icTimeline || icTimeline.length === 0) return null;

  const dates = icTimeline.map(d => d.date);
  const ics = icTimeline.map(d => d.ic);
  const icMean = ics.reduce((a, b) => a + b, 0) / ics.length;

  const option = {
    backgroundColor: 'transparent',
    tooltip: {
      trigger: 'axis',
      formatter: params => {
        const p = params[0];
        return `${p.axisValue}<br/>IC: <b>${(+p.value).toFixed(2)}</b>`;
      },
    },
    grid: { left: 50, right: 20, top: 30, bottom: 40 },
    xAxis: { type: 'category', data: dates, axisLabel: { rotate: 45, fontSize: 10 } },
    yAxis: { type: 'value', name: 'IC (×100)', axisLabel: { fontSize: 10 } },
    series: [
      {
        name: 'IC',
        type: 'bar',
        data: ics.map(v => ({
          value: v,
          itemStyle: { color: v >= 0 ? '#ef5350' : '#26a69a' }, // 涨红跌绿
        })),
        barMaxWidth: 12,
      },
      {
        name: 'IC均值',
        type: 'line',
        data: dates.map(() => Math.round(icMean * 100) / 100),
        lineStyle: { color: '#722ed1', type: 'dashed', width: 2 },
        symbol: 'none',
        markLine: {
          silent: true,
          data: [{ yAxis: 0, lineStyle: { color: '#999', type: 'solid', width: 1 } }],
        },
      },
    ],
  };
  return (
    <Card title={`${factorCode} IC 时序`} size="small">
      <ReactECharts option={option} style={{ height: 320 }} notMerge={true} />
    </Card>
  );
}

// ─── IC 累计图 ─────────────────────────────────────────────────────────────────
function IcCumulativeChart({ icTimeline, factorCode }) {
  if (!icTimeline || icTimeline.length === 0) return null;

  const ics = icTimeline.map(d => d.ic / 100); // icTimeline 里是 ×100 的
  let cumIc = 0;
  const cumData = ics.map(v => { cumIc += v; return Math.round(cumIc * 10000) / 100; });
  const dates = icTimeline.map(d => d.date);

  const option = {
    backgroundColor: 'transparent',
    tooltip: { trigger: 'axis' },
    grid: { left: 50, right: 20, top: 30, bottom: 40 },
    xAxis: { type: 'category', data: dates, axisLabel: { rotate: 45, fontSize: 10 } },
    yAxis: { type: 'value', name: '累计IC (×100)', axisLabel: { fontSize: 10 } },
    series: [{
      name: '累计IC',
      type: 'line',
      data: cumData,
      smooth: true,
      lineStyle: { width: 2, color: '#1890ff' },
      areaStyle: { color: 'rgba(24,144,255,0.1)' },
    }],
  };
  return (
    <Card title={`${factorCode} IC 累计曲线`} size="small">
      <ReactECharts option={option} style={{ height: 280 }} notMerge={true} />
    </Card>
  );
}

// ─── 主组件 ─────────────────────────────────────────────────────────────────────
export default function FactorIcIrAnalysis() {
  const { message } = App.useApp();
  const [factorList, setFactorList] = useState([]);
  const [selectedCodes, setSelectedCodes] = useState([]);
  const [dateRange, setDateRange] = useState([dayjs().subtract(1, 'year'), dayjs()]);
  const [forwardDays, setForwardDays] = useState(5);
  const [loading, setLoading] = useState(false);
  const [results, setResults] = useState(null);

  // 策略批量选择
  const [strategies, setStrategies] = useState([]);
  const [selectedStrategyId, setSelectedStrategyId] = useState(null);

  // IC趋势详情
  const [trendFactor, setTrendFactor] = useState(null);
  const [trendLoading, setTrendLoading] = useState(false);
  const [trendData, setTrendData] = useState(null);

  useEffect(() => {
    factorApi.getAllDefinitions().then(res => {
      const content = res?.records || res?.content || res || [];
      setFactorList(Array.isArray(content) ? content : []);
    }).catch(() => {});
    strategyApi.list({ page: 0, size: 200 }).then(res => {
      const content = res?.records || res?.content || res || [];
      setStrategies(Array.isArray(content) ? content : []);
    }).catch(() => {});
  }, []);

  // 从策略 factorConfigJson 中提取因子代码
  const handleStrategyChange = (strategyId) => {
    setSelectedStrategyId(strategyId);
    if (!strategyId) {
      setSelectedCodes([]);
      return;
    }
    const s = strategies.find(x => x.id === strategyId || x.strategyId === strategyId);
    if (!s || !s.factorConfigJson) {
      message.warning('该策略没有配置因子');
      setSelectedCodes([]);
      return;
    }
    try {
      const obj = JSON.parse(s.factorConfigJson);
      const codes = (obj.factors || []).map(f => f.code).filter(Boolean);
      if (codes.length === 0) {
        message.warning('该策略因子配置为空');
        setSelectedCodes([]);
        return;
      }
      const validCodes = codes.filter(c => factorList.some(f => f.factorCode === c));
      if (validCodes.length === 0) {
        message.warning('策略中的因子在当前系统中不存在');
        setSelectedCodes([]);
        return;
      }
      setSelectedCodes(validCodes);
      message.success(`已加载策略「${s.strategyName || s.name || '未命名'}」的 ${validCodes.length} 个因子`);
    } catch {
      message.error('策略因子配置格式错误');
      setSelectedCodes([]);
    }
  };

  const strategyOptions = useMemo(() => {
    return strategies.map(s => ({
      label: `${s.strategyName || s.name || '未命名'} (${s.strategyCode || s.code || ''})`,
      value: s.id ?? s.strategyId,
    }));
  }, [strategies]);

  const factorOptions = useMemo(() => {
    return factorList.map(f => ({
      label: `${f.factorCode} - ${f.factorName || ''}`,
      value: f.factorCode,
    }));
  }, [factorList]);

  // 批量 IC/IR 分析
  const handleAnalyze = async () => {
    if (selectedCodes.length === 0) {
      message.warning('请选择至少1个因子');
      return;
    }
    if (!dateRange[0] || !dateRange[1]) {
      message.warning('请选择日期范围');
      return;
    }
    setLoading(true);
    setResults(null);
    setTrendData(null);
    setTrendFactor(null);
    try {
      const data = await factorApi.batchIcIrAnalysis(
        selectedCodes,
        dateRange[0].format('YYYY-MM-DD'),
        dateRange[1].format('YYYY-MM-DD'),
        forwardDays,
      );
      setResults(data || []);
    } catch (e) {
      message.error('IC/IR 分析失败，请稍后重试');
    } finally {
      setLoading(false);
    }
  };

  // 单因子 IC 趋势详情
  const handleViewTrend = async (factorCode) => {
    setTrendFactor(factorCode);
    setTrendLoading(true);
    setTrendData(null);
    try {
      const data = await factorApi.getIcTrend(
        factorCode,
        dateRange[0].format('YYYY-MM-DD'),
        dateRange[1].format('YYYY-MM-DD'),
        forwardDays,
      );
      setTrendData(data);
    } catch (e) {
      message.error('IC趋势查询失败，请稍后重试');
    } finally {
      setTrendLoading(false);
    }
  };

  // 结果表格列定义
  const columns = [
    {
      title: '因子代码',
      dataIndex: 'factorCode',
      key: 'factorCode',
      width: 120,
      render: (v, r) => r.error
        ? <Tooltip title={r.error || '无因子数据，无法查看IC趋势'}><Text type="danger" style={{ cursor: 'not-allowed' }}>{v}</Text></Tooltip>
        : <a onClick={() => handleViewTrend(v)}>{v}</a>,
    },
    {
      title: 'IC 均值',
      dataIndex: 'icMean',
      key: 'icMean',
      width: 90,
      sorter: (a, b) => (a.icMean || 0) - (b.icMean || 0),
      render: v => v != null ? <Text style={{ color: v >= 0 ? '#ef5350' : '#26a69a', fontWeight: 600 }}>{(+v).toFixed(2)}</Text> : '-',
    },
    {
      title: 'IC 标准差',
      dataIndex: 'icStd',
      key: 'icStd',
      width: 90,
      render: v => v != null ? (+v).toFixed(2) : '-',
    },
    {
      title: 'IR',
      dataIndex: 'ir',
      key: 'ir',
      width: 80,
      sorter: (a, b) => (a.ir || 0) - (b.ir || 0),
      render: v => v != null ? <Text strong>{(+v).toFixed(2)}</Text> : '-',
    },
    {
      title: 'IC 胜率',
      dataIndex: 'icWinRate',
      key: 'icWinRate',
      width: 90,
      sorter: (a, b) => (a.icWinRate || 0) - (b.icWinRate || 0),
      render: v => v != null ? `${(+v).toFixed(1)}%` : '-',
    },
    {
      title: '样本天数',
      dataIndex: 'sampleDays',
      key: 'sampleDays',
      width: 80,
      render: v => v ?? '-',
    },
    {
      title: '前瞻天数',
      dataIndex: 'forwardDays',
      key: 'forwardDays',
      width: 80,
      render: v => v ?? forwardDays,
    },
    {
      title: '评估',
      dataIndex: 'assessment',
      key: 'assessment',
      width: 100,
      filters: [
        { text: '有效', value: '有效因子' },
        { text: '弱有效', value: '弱有效' },
        { text: '无效', value: '无效因子' },
      ],
      onFilter: (value, record) => record.assessment === value,
      render: v => <AssessmentTag assessment={v} />,
    },
    {
      title: '错误',
      dataIndex: 'error',
      key: 'error',
      width: 120,
      render: v => v ? <Text type="danger" style={{ fontSize: 12 }}>{v}</Text> : '-',
    },
  ];

  // 统计汇总
  const summary = useMemo(() => {
    if (!results || results.length === 0) return null;
    const valid = results.filter(r => !r.error);
    if (valid.length === 0) return null;
    const avgIc = valid.reduce((s, r) => s + (r.icMean || 0), 0) / valid.length;
    const avgIr = valid.reduce((s, r) => s + (r.ir || 0), 0) / valid.length;
    const avgWin = valid.reduce((s, r) => s + (r.icWinRate || 0), 0) / valid.length;
    const effective = valid.filter(r => r.assessment === '有效因子').length;
    const weak = valid.filter(r => r.assessment === '弱有效').length;
    return { total: valid.length, avgIc, avgIr, avgWin, effective, weak };
  }, [results]);

  return (
    <div>
      <div className="page-header">
        <Title level={4} style={{ margin: 0 }}>
          <BarChartOutlined style={{ marginRight: 8 }} />
          因子 IC/IR 批量分析
          <Tooltip
            title={
              <div style={{ whiteSpace: 'nowrap' }}>
                <p style={{ margin: '0 0 6px' }}><b>什么是 IC/IR？</b></p>
                <p style={{ margin: '0 0 6px', whiteSpace: 'normal' }}>IC（信息系数）：因子值与未来收益率的横截面相关系数。IC 越大，因子预测能力越强。</p>
                <p style={{ margin: '0 0 6px', whiteSpace: 'normal' }}>IR（信息比率）：IC均值/IC标准差。衡量因子预测的稳定性，IR 越高越可靠。</p>
                <p style={{ margin: '0 0 6px', whiteSpace: 'normal' }}><b style={{ color: '#fa8c16' }}>⚠️ 前提：因子值必须存在</b> — 仅当 factor_value 表中有该因子的时序数据时才能分析，无数据的因子代码将显示为红色不可点击。</p>
                <p style={{ margin: '0 0 6px' }}><b>评估标准：</b></p>
                <p style={{ margin: '0 0 4px' }}>有效因子：IC均值 ≥ 0.05 且 IR ≥ 0.5</p>
                <p style={{ margin: '0 0 4px' }}>弱有效：IC均值 ≥ 0.03 且 IR ≥ 0.3</p>
                <p style={{ margin: '0 0 6px' }}>无效：低于弱有效标准</p>
                <p style={{ margin: '0 0 6px' }}><b>如何使用：</b></p>
                <p style={{ margin: '0 0 4px' }}>1. 选因子+日期+前瞻天数 → 点"分析"</p>
                <p style={{ margin: '0 0 4px' }}>2. 查看结果表格，点击因子代码查看IC趋势</p>
                <p style={{ margin: '0 0 4px' }}>3. 累计IC持续上升 = 因子稳定有效</p>
                <p style={{ margin: 0 }}>4. 用有效因子构建策略或优化权重</p>
              </div>
            }
            placement="right"
            styles={{ body: { width: 480, maxWidth: 480 } }}
          >
            <InfoCircleOutlined style={{ marginLeft: 6, color: '#bbb', fontSize: 16, cursor: 'pointer' }} />
          </Tooltip>
        </Title>
      </div>

      {/* 参数选择区 */}
      <Card size="small" style={{ marginBottom: 16 }}>
        <Row gutter={[16, 12]} align="middle">
          <Col span={4}>
            <Space direction="vertical" size={4} style={{ width: '100%' }}>
              <Text type="secondary" style={{ fontSize: 12 }}>
                选择策略
                <Tooltip title="选择一个策略，自动加载其配置的所有因子">
                  <InfoCircleOutlined style={{ marginLeft: 4, color: '#bbb' }} />
                </Tooltip>
              </Text>
              <Select
                placeholder="选择策略批量加载因子"
                value={selectedStrategyId}
                onChange={handleStrategyChange}
                options={strategyOptions}
                style={{ width: '100%' }}
                showSearch
                allowClear
                filterOption={(input, option) => (option?.label ?? '').toLowerCase().includes(input.toLowerCase())}
              />
            </Space>
          </Col>
          <Col span={8}>
            <Space direction="vertical" size={4} style={{ width: '100%' }}>
              <Text type="secondary" style={{ fontSize: 12 }}>
                选择因子
                <Tooltip title="支持批量选择，最多20个因子同时分析">
                  <InfoCircleOutlined style={{ marginLeft: 4, color: '#bbb' }} />
                </Tooltip>
              </Text>
              <Select
                mode="multiple"
                placeholder="搜索或选择因子"
                value={selectedCodes}
                onChange={(codes) => {
                  setSelectedCodes(codes);
                  if (selectedStrategyId) setSelectedStrategyId(null);
                }}
                options={factorOptions}
                style={{ width: '100%' }}
                maxCount={20}
                showSearch
                allowClear
                filterOption={(input, option) => (option?.label ?? '').toLowerCase().includes(input.toLowerCase())}
                maxTagCount="responsive"
              />
            </Space>
          </Col>
          <Col span={6}>
            <Space direction="vertical" size={4} style={{ width: '100%' }}>
              <Text type="secondary" style={{ fontSize: 12 }}>日期范围</Text>
              <RangePicker
                value={dateRange}
                onChange={setDateRange}
                style={{ width: '100%' }}
              />
            </Space>
          </Col>
          <Col span={3}>
            <Space direction="vertical" size={4} style={{ width: '100%' }}>
              <Text type="secondary" style={{ fontSize: 12 }}>
                前瞻天数
                <Tooltip title="计算第N个交易日的收益率，1=次日，5=下周">
                  <InfoCircleOutlined style={{ marginLeft: 4, color: '#bbb' }} />
                </Tooltip>
              </Text>
              <InputNumber min={1} max={60} value={forwardDays} onChange={setForwardDays} style={{ width: '100%' }} />
            </Space>
          </Col>
          <Col span={3}>
            <Space direction="vertical" size={4} style={{ width: '100%' }}>
              <Text type="secondary" style={{ fontSize: 12 }}> </Text>
              <Button type="primary" icon={<BarChartOutlined />} onClick={handleAnalyze} loading={loading} block>
                分析
              </Button>
            </Space>
          </Col>
        </Row>
      </Card>

      {/* 汇总统计 */}
      {summary && (
        <Row gutter={16} style={{ marginBottom: 16 }}>
          <Col span={4}>
            <Card size="small"><Statistic title="分析因子数" value={summary.total} /></Card>
          </Col>
          <Col span={4}>
            <Card size="small"><Statistic title="平均 IC" value={summary.avgIc} precision={2} /></Card>
          </Col>
          <Col span={4}>
            <Card size="small"><Statistic title="平均 IR" value={summary.avgIr} precision={2} /></Card>
          </Col>
          <Col span={4}>
            <Card size="small"><Statistic title="平均 IC 胜率" value={summary.avgWin} precision={1} suffix="%" /></Card>
          </Col>
          <Col span={4}>
            <Card size="small"><Statistic title="有效因子" value={summary.effective} valueStyle={{ color: '#52c41a' }} /></Card>
          </Col>
          <Col span={4}>
            <Card size="small"><Statistic title="弱有效" value={summary.weak} valueStyle={{ color: '#faad14' }} /></Card>
          </Col>
        </Row>
      )}

      {/* 结果表格 */}
      {results && (
        <Card title="分析结果（点击因子代码查看IC趋势）" size="small" style={{ marginBottom: 16 }}>
          <Table
            dataSource={results}
            columns={columns}
            rowKey="factorCode"
            size="small"
            pagination={results.length > 10 ? { pageSize: 10 } : false}
            scroll={{ x: 900 }}
          />
        </Card>
      )}

      {/* IC 趋势详情 */}
      {trendFactor && (
        <Spin spinning={trendLoading}>
          <Card
            title={<span><LineChartOutlined style={{ marginRight: 8 }} />{trendFactor} IC 趋势详情</span>}
            size="small"
            extra={<Button size="small" onClick={() => { setTrendFactor(null); setTrendData(null); }}>关闭</Button>}
          >
            {trendData && !trendData.error ? (
              <>
                <Row gutter={16} style={{ marginBottom: 12 }}>
                  <Col><Statistic title="IC 均值" value={trendData.icMean} precision={2} /></Col>
                  <Col><Statistic title="IR" value={trendData.ir} precision={2} /></Col>
                  <Col><Statistic title="IC 胜率" value={trendData.icWinRate} precision={1} suffix="%" /></Col>
                  <Col><AssessmentTag assessment={trendData.assessment} /></Col>
                </Row>
                <Row gutter={16}>
                  <Col span={12}>
                    <IcBarChart icTimeline={trendData.icTimeline} factorCode={trendFactor} />
                  </Col>
                  <Col span={12}>
                    <IcCumulativeChart icTimeline={trendData.icTimeline} factorCode={trendFactor} />
                  </Col>
                </Row>
              </>
            ) : trendData?.error ? (
              <Alert type="warning" message={trendData.error} />
            ) : null}
          </Card>
        </Spin>
      )}
    </div>
  );
}
