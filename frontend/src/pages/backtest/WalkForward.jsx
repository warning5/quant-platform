import React, { useState, useCallback } from 'react';
import {
  Card, Row, Col, Typography, Tag, Table, Alert, Spin, Button, Form,
  InputNumber, DatePicker, Select, message, Statistic, Space, Empty, Tooltip, Divider,
} from 'antd';
import {
  PlayCircleOutlined, WarningOutlined, CheckCircleOutlined,
  LineChartOutlined, BarChartOutlined, QuestionCircleOutlined,
} from '@ant-design/icons';
import ReactECharts from 'echarts-for-react';
import { backtestApi } from '../../api';
import { useFactorMeta } from '../../hooks/useFactorMeta';
import dayjs from 'dayjs';

const { Title, Text } = Typography;
const { RangePicker } = DatePicker;

// ── 颜色（中国股市：红涨绿跌）──
const RED = '#cf1322', GREEN = '#3f8600';
const COLOR_UP = RED, COLOR_DOWN = GREEN;

// ── 工具函数 ─────────────────────────────────────────────────────
const fmtPct = (v) => {
  if (v == null || isNaN(v)) return '-';
  return (v * 100).toFixed(2) + '%';
};
const fmt = (v, d = 2) => (v != null && !isNaN(v) ? v.toFixed(d) : '-');
const icBarColor = (v) => {
  if (v == null) return '#999';
  return v >= 0 ? COLOR_UP : COLOR_DOWN;
};

// 可用因子列表从后端动态加载（见 useFactorMeta hook）

export default function WalkForward() {
  const { factorList } = useFactorMeta();
  const [running, setRunning] = useState(false);
  const [results, setResults] = useState(null);
  const [summary, setSummary] = useState(null);
  const [error, setError] = useState(null);

  // 表单状态
  const [factors, setFactors] = useState(['MOM20', 'VOLATILITY']);
  const [startDate, setStartDate] = useState(dayjs().subtract(2, 'year'));
  const [endDate, setEndDate] = useState(dayjs());
  const [trainDays, setTrainDays] = useState(60);
  const [validateDays, setValidateDays] = useState(20);
  const [stepDays, setStepDays] = useState(10);
  const [maxRounds, setMaxRounds] = useState(10);
  const [transactionCost, setTransactionCost] = useState(0.15);
  const [rebalanceInterval, setRebalanceInterval] = useState(20);

  // 动态构建可用因子选项（仅 ACTIVE 因子）
  const factorOptions = factorList
    .filter(f => f.status === 'ACTIVE')
    .map(f => ({ label: `${f.factorCode}（${f.factorName || f.factorCode}）`, value: f.factorCode }));

  const handleRun = useCallback(() => {
    if (!factors || factors.length === 0) {
      message.warning('请至少选择一个因子');
      return;
    }
    setRunning(true);
    setError(null);
    setResults(null);
    setSummary(null);

    const params = {
      factors: factors.map(fc => ({ factorCode: fc, direction: 1, weight: 1.0 })),
      startDate: startDate.format('YYYY-MM-DD'),
      endDate: endDate.format('YYYY-MM-DD'),
      trainDays,
      validateDays,
      stepDays,
      maxRounds,
      transactionCost: transactionCost / 100,   // 前端显示%，后端用小数
      rebalanceInterval,
    };

    backtestApi.walkForward(params)
      .then(res => {
        const data = res.data || res;
        setSummary(data.summary || {});
        setResults(data.rounds || []);
        message.success(`Walk-Forward 完成，共 ${data.summary?.totalRounds || 0} 轮`);
      })
      .catch(err => {
        const msg = err.response?.data?.message || err.message || '执行失败';
        setError(msg);
        message.error('Walk-Forward 执行失败：' + msg);
      })
      .finally(() => setRunning(false));
  }, [factors, startDate, endDate, trainDays, validateDays, stepDays, maxRounds, transactionCost, rebalanceInterval]);

  // ── 图表配置 ───────────────────────────────────────────────────
  const renderIcDecayChart = () => {
    if (!results || results.length === 0) return null;
    const rounds = results.map(r => r.round);
    const trainIc = results.map(r => r.trainIcMean);
    const validateIc = results.map(r => r.validateIcMean);

    const option = {
      title: { text: 'IC 衰减曲线', left: 'center', top: 8, textStyle: { fontSize: 14 } },
      tooltip: { trigger: 'axis' },
      legend: { data: ['训练期IC', '验证期IC'], top: 36 },
      grid: { top: 70, bottom: 40, left: 60, right: 20 },
      xAxis: { type: 'category', data: rounds.map(r => `R${r}`) },
      yAxis: { type: 'value', name: 'IC', axisLabel: { formatter: v => v.toFixed(3) } },
      series: [
        { name: '训练期IC', type: 'line', data: trainIc, smooth: true, lineStyle: { width: 2 } },
        { name: '验证期IC', type: 'line', data: validateIc, smooth: true, lineStyle: { width: 2, type: 'dashed' } },
      ],
    };
    return <ReactECharts option={option} style={{ height: 300 }} />;
  };

  const renderReturnChart = () => {
    if (!results || results.length === 0) return null;
    const rounds = results.map(r => r.round);
    const ret = results.map(r => r.validateReturn);
    const excess = results.map(r => r.excessReturn);

    const option = {
      title: { text: '各轮收益率', left: 'center', top: 8, textStyle: { fontSize: 14 } },
      tooltip: { trigger: 'axis' },
      legend: { data: ['组合收益(%)', '超额收益(%)'], top: 36 },
      grid: { top: 70, bottom: 40, left: 60, right: 20 },
      xAxis: { type: 'category', data: rounds.map(r => `R${r}`) },
      yAxis: { type: 'value', name: '收益(%)' },
      series: [
        { name: '组合收益(%)', type: 'bar', data: ret, itemStyle: { color: r => r >= 0 ? COLOR_UP : COLOR_DOWN }, barMaxWidth: 30 },
        { name: '超额收益(%)', type: 'bar', data: excess, itemStyle: { color: r => r >= 0 ? COLOR_UP : COLOR_DOWN }, barMaxWidth: 30 },
      ],
    };
    return <ReactECharts option={option} style={{ height: 300 }} />;
  };

  // ── 轮次表格列 ─────────────────────────────────────────────────
  const roundColumns = [
    { title: '轮次', dataIndex: 'round', key: 'round', width: 70,
      render: (v) => <Tag color="blue">R{v}</Tag> },
    { title: '训练期', key: 'train', width: 180,
      render: (_, r) => `${r.trainStart || '-'} ~ ${r.trainEnd || '-'}` },
    { title: '验证期', key: 'validate', width: 180,
      render: (_, r) => `${r.validateStart || '-'} ~ ${r.validateEnd || '-'}` },
    { title: '训练IC', key: 'trainIc', width: 90,
      render: (_, r) => <span style={{ color: icBarColor(r.trainIcMean) }}>{fmt(r.trainIcMean, 4)}</span> },
    { title: '验证IC', key: 'valIc', width: 90,
      render: (_, r) => <span style={{ color: icBarColor(r.validateIcMean) }}>{fmt(r.validateIcMean, 4)}</span> },
    { title: '组合收益(%)', key: 'ret', width: 110,
      render: (_, r) => <span style={{ color: r.validateReturn >= 0 ? COLOR_UP : COLOR_DOWN }}>{fmt(r.validateReturn)}</span> },
    { title: '超额收益(%)', key: 'excess', width: 110,
      render: (_, r) => <span style={{ color: r.excessReturn >= 0 ? COLOR_UP : COLOR_DOWN }}>{fmt(r.excessReturn)}</span> },
    { title: '最大回撤(%)', dataIndex: 'maxDrawdown', key: 'dd', width: 110,
      render: (v) => <span style={{ color: COLOR_DOWN }}>{fmt(v)}</span> },
    { title: '股票数', dataIndex: 'stockCount', key: 'cnt', width: 80 },
  ];

  return (
    <div>
      <div className="page-header">
        <Space>
          <Title level={4} style={{ margin: 0 }}><LineChartOutlined /> Walk-Forward 验证</Title>
          <Tooltip
            title={
              <div style={{ fontSize: 13, lineHeight: 1.9, maxWidth: 560, maxHeight: 400, overflowY: 'auto' }}>
                <div style={{ fontWeight: 600, marginBottom: 6 }}>目标</div>
                <div style={{ marginBottom: 8 }}>验证策略的<b>样本外有效性</b>，避免过拟合。"样本外"不是指非历史数据，而是指<b>参数优化时没碰过的数据</b>。</div>
                <div style={{ fontWeight: 600, marginBottom: 4 }}>样本外是什么意思？</div>
                <div style={{ marginBottom: 4 }}>传统回测：用同一段历史数据既调参又验证 → 相当于"看答案做题"，结果不可信。</div>
                <div style={{ background: '#f6ffed', border: '1px solid #b7eb8f', borderRadius: 6, padding: '8px 10px', marginBottom: 8, fontSize: 12, lineHeight: 1.8 }}>
                  <div style={{ fontWeight: 600, marginBottom: 2 }}>Walk-Forward 的做法：</div>
                  <div>第1轮：用过去60天算IC定权重 → 用接下来20天算收益（这段数据训练时没见过 → 样本外）</div>
                  <div>第2轮：窗口向前滚10天 → 再用新的60天定权重 → 再用新的20天算收益（又是样本外）</div>
                  <div>重复多轮，看是不是每轮都赚钱。</div>
                </div>
                <div style={{ fontWeight: 600, marginBottom: 4 }}>与回测的区别</div>
                <div style={{ marginBottom: 8 }}>
                  <div>• <b>回测</b>：用全部历史数据调参+验证，容易过拟合，结果偏乐观</div>
                  <div>• <b>Walk-Forward</b>：每一步只用训练期数据决策，验证期完全没见过，模拟实盘，结果更可信</div>
                </div>
                <div style={{ fontWeight: 600, marginBottom: 4 }}>正确使用顺序</div>
                <div style={{ background: '#fffbe6', border: '1px solid #ffe58f', borderRadius: 6, padding: '8px 10px', marginBottom: 8, fontSize: 12, lineHeight: 1.85 }}>
                  <div><b>Step 1：回测（快速筛选）</b></div>
                  <div style={{ marginLeft: 16 }}>跑一遍，看策略大致表现。如果回测收益都很差 → 直接放弃，不用跑 Walk-Forward</div>
                  <div><b>Step 2：Walk-Forward（严肃验证）</b></div>
                  <div style={{ marginLeft: 16 }}>确认策略是不是真的有效。如果胜率低 / IC衰减快 → 策略不可信</div>
                  <div><b>Step 3：模拟盘（实盘前最后一步）</b></div>
                  <div style={{ marginLeft: 16 }}>用小资金跑一段时间，确认没 bug</div>
                  <div><b>Step 4：实盘</b></div>
                </div>
                <div style={{ fontWeight: 600, marginBottom: 4 }}>使用方法（本页面）</div>
                <div style={{ marginBottom: 4 }}>① 选择因子 → ② 设置日期范围和窗口参数 → ③ 点击「运行」→ ④ 查看各轮收益和IC衰减</div>
                <div style={{ fontWeight: 600, marginBottom: 4 }}>结果怎么看</div>
                <div style={{ marginBottom: 4 }}>
                  <div>• <b>IC半衰期</b>：因子预测力衰减速度，越长越好</div>
                  <div>• <b>胜率</b>：超额收益为正的轮次占比，&gt;50%说明策略稳健</div>
                  <div>• <b>失效预警</b>：连续3轮负收益或IC衰减&gt;50%时触发</div>
                </div>
                <Divider style={{ margin: '8px 0' }} />
                <div style={{ fontSize: 12, color: '#666' }}>
                  回测是<b>"粗筛"</b>，Walk-Forward 是<b>"严考"</b>。两者互补，不是替代关系。
                </div>
                <div style={{ marginTop: 8, color: '#faad14', fontSize: 12 }}>⚠️ 运行前请先通过「IC管理」计算因子IC数据</div>
              </div>
            }
            placement="right"
            overlayStyle={{ maxWidth: 580 }}
          >
            <QuestionCircleOutlined style={{ color: '#999', cursor: 'pointer', fontSize: 16 }} />
          </Tooltip>
        </Space>
      </div>

      {/* ── 参数表单 ── */}
      <Card size="small" style={{ marginBottom: 24 }}>
        <Row gutter={[24, 16]}>
          <Col span={24}>
            <Text strong>因子选择：</Text>
            <Select
              mode="multiple"
              style={{ minWidth: 400, marginLeft: 8 }}
              placeholder="选择因子（至少1个）"
              value={factors}
              onChange={setFactors}
              options={factorOptions}
            />
          </Col>
          <Col span={8}>
            <Text strong>分析日期范围：</Text><br />
            <RangePicker
              value={[startDate, endDate]}
              onChange={(dates) => { if (dates) { setStartDate(dates[0]); setEndDate(dates[1]); } }}
              style={{ marginTop: 4 }}
            />
          </Col>
          <Col span={4}>
            <Text strong>训练期(天)：</Text><br />
            <InputNumber min={10} max={252} value={trainDays} onChange={setTrainDays} style={{ width: '100%', marginTop: 4 }} />
          </Col>
          <Col span={4}>
            <Text strong>验证期(天)：</Text><br />
            <InputNumber min={5} max={120} value={validateDays} onChange={setValidateDays} style={{ width: '100%', marginTop: 4 }} />
          </Col>
          <Col span={4}>
            <Text strong>滚动步长：</Text><br />
            <InputNumber min={1} max={60} value={stepDays} onChange={setStepDays} style={{ width: '100%', marginTop: 4 }} />
          </Col>
          <Col span={4}>
            <Text strong>最大轮次：</Text><br />
            <InputNumber min={0} max={50} value={maxRounds} onChange={setMaxRounds} style={{ width: '100%', marginTop: 4 }} />
            <Text type="secondary" style={{ fontSize: 11 }}>0=不限</Text>
          </Col>
          <Col span={4}>
            <Text strong>交易成本(%)：</Text><br />
            <InputNumber min={0} max={5} step={0.05} precision={2} value={transactionCost} onChange={setTransactionCost} style={{ width: '100%', marginTop: 4 }} />
          </Col>
          <Col span={4}>
            <Text strong>调仓间隔(天)：</Text><br />
            <InputNumber min={0} max={120} value={rebalanceInterval} onChange={setRebalanceInterval} style={{ width: '100%', marginTop: 4 }} />
            <Text type="secondary" style={{ fontSize: 11 }}>0=不调仓</Text>
          </Col>
          <Col span={24} style={{ textAlign: 'center', marginTop: 8 }}>
            <Button
              type="primary"
              size="large"
              icon={<PlayCircleOutlined />}
              loading={running}
              onClick={handleRun}
              style={{ minWidth: 200 }}
            >
              {running ? '验证中...' : '运行 Walk-Forward'}
            </Button>
          </Col>
        </Row>
      </Card>

      {/* ── 错误提示 ── */}
      {error && <Alert type="error" message="执行失败" description={error} showIcon closable onClose={() => setError(null)} style={{ marginBottom: 16 }} />}

      {/* ── 加载中 ── */}
      {running && (
        <Card style={{ textAlign: 'center', padding: 40 }}>
          <Spin size="large" tip="Walk-Forward 验证中，请稍候..." />
          <div style={{ marginTop: 8, color: '#999', fontSize: 13 }}>滚动窗口验证可能需要几分钟，请耐心等待</div>
        </Card>
      )}

      {/* ── 结果展示 ── */}
      {!running && summary && (
        <>
          {/* 预警信息 */}
          {summary.warnings && summary.warnings.length > 0 && (
            <Alert
              type="warning"
              message="策略失效预警"
              description={
                <ul style={{ margin: 0, paddingLeft: 20 }}>
                  {summary.warnings.map((w, i) => <li key={i}>{w}</li>)}
                </ul>
              }
              showIcon
              closable
              style={{ marginBottom: 16 }}
            />
          )}

          {/* 汇总指标 */}
          <Row gutter={16} style={{ marginBottom: 24 }}>
            <Col span={4}>
              <Card size="small"><Statistic title="总轮次" value={summary.totalRounds} /></Card>
            </Col>
            <Col span={4}>
              <Card size="small">
                <Statistic title="平均收益(%)" value={summary.avgValidateReturn} precision={2}
                  valueStyle={{ color: summary.avgValidateReturn >= 0 ? COLOR_UP : COLOR_DOWN }} />
              </Card>
            </Col>
            <Col span={4}>
              <Card size="small">
                <Statistic title="平均超额(%)" value={summary.avgExcessReturn} precision={2}
                  valueStyle={{ color: summary.avgExcessReturn >= 0 ? COLOR_UP : COLOR_DOWN }} />
              </Card>
            </Col>
            <Col span={3}>
              <Card size="small"><Statistic title="胜率(%)" value={summary.winRate} precision={1} suffix="%" /></Card>
            </Col>
            <Col span={3}>
              <Card size="small">
                <Statistic title="最大回撤(%)" value={summary.maxDrawdown} precision={2}
                  valueStyle={{ color: COLOR_DOWN }} />
              </Card>
            </Col>
            <Col span={3}>
              <Card size="small">
                <Statistic title="IC衰减(%)" value={summary.icDecay * 100} precision={1} suffix="%"
                  valueStyle={{ color: summary.icDecay > 0.5 ? COLOR_DOWN : COLOR_UP }} />
              </Card>
            </Col>
            <Col span={3}>
              <Card size="small">
                <Statistic title="IC半衰期(轮)" value={summary.icHalfLifeRounds} precision={1}
                  valueStyle={{ color: (summary.icHalfLifeRounds || 0) > 0 ? undefined : '#999' }} />
              </Card>
            </Col>
          </Row>

          {/* 图表 */}
          <Row gutter={16} style={{ marginBottom: 24 }}>
            <Col span={12}><Card size="small">{renderIcDecayChart()}</Card></Col>
            <Col span={12}><Card size="small">{renderReturnChart()}</Card></Col>
          </Row>

          {/* 轮次明细表格 */}
          <Card size="small" title={`轮次明细（${results?.length || 0}轮）`}>
            <Table
              rowKey="round"
              columns={roundColumns}
              dataSource={results}
              size="small"
              pagination={{ pageSize: 20, showSizeChanger: true, pageSizeOptions: ['10', '20', '50'] }}
              scroll={{ x: 1100 }}
            />
          </Card>
        </>
      )}

      {/* 未运行时的空状态 */}
      {!running && !summary && !error && (
        <Empty description="设置参数后点击「运行 Walk-Forward」开始验证" style={{ padding: 60 }} />
      )}
    </div>
  );
}
