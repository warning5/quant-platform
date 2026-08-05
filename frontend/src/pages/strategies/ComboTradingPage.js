import React, { useState, useEffect } from 'react';
import {
  Card, Row, Col, Table, Tag, Button, Modal, InputNumber, Space, Typography,
  Statistic, Spin, Tooltip, Tabs, Empty, Alert, Progress,
} from 'antd';
import {
  ArrowLeftOutlined, ReloadOutlined, PauseCircleOutlined, PlayCircleOutlined,
  SwapOutlined, PieChartOutlined, AppstoreOutlined, ThunderboltOutlined,
} from '@ant-design/icons';
import ReactECharts from '../../components/LazyECharts';
import { paperTradingApi } from '../../api';
import { useAuthStore } from '../../stores/authStore';
import { message } from '../../utils/messageUtil';

const { Text, Title } = Typography;
const fmt = v => v != null ? (+v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) : '-';
const fmtPct = v => v != null ? `${(+v * 100).toFixed(2)}%` : '-';
const chgColor = v => v > 0 ? '#ef5350' : v < 0 ? '#26a69a' : '#999';

const SUB_COLORS = ['#fa8c16', '#52c41a', '#722ed1', '#eb2f96', '#13c2c2', '#2f54eb'];

export default function ComboDetail({ comboId, onBack }) {
  const [detail, setDetail] = useState(null);
  const [nav, setNav] = useState(null);
  const [rebalanceLog, setRebalanceLog] = useState([]);
  const [signals, setSignals] = useState([]);
  const [loading, setLoading] = useState(true);
  const [activeTab, setActiveTab] = useState('overview');
  const [rebLoading, setRebLoading] = useState(false);
  const [weightModal, setWeightModal] = useState(null);
  const [newWeight, setNewWeight] = useState(0);
  const [weightSaving, setWeightSaving] = useState(false);
  const canEdit = useAuthStore((s) => s.hasPermission('strategy:edit'));

  const loadAll = () => {
    setLoading(true);
    Promise.all([
      paperTradingApi.comboDetail(comboId),
      paperTradingApi.comboNav(comboId),
      paperTradingApi.comboRebalanceLog(comboId),
      paperTradingApi.comboSignals(comboId),
    ]).then(([d, n, rl, sg]) => {
      setDetail(d); setNav(n); setRebalanceLog(rl || []); setSignals(sg || []);
    }).catch(() => {}).finally(() => setLoading(false));
  };
  useEffect(() => { loadAll(); }, [comboId]);

  const handleRebalance = async () => {
    setRebLoading(true);
    try { await paperTradingApi.comboRebalance(comboId); message.success('再平衡触发完成'); loadAll(); }
    catch (e) {} finally { setRebLoading(false); }
  };
  const handlePause = async (sub) => {
    try { await paperTradingApi.pauseSubStrategy(comboId, sub.strategyId); message.success('子策略已暂停'); loadAll(); } catch (e) {}
  };
  const handleResume = async (sub) => {
    try { await paperTradingApi.resumeSubStrategy(comboId, sub.strategyId); message.success('子策略已恢复'); loadAll(); } catch (e) {}
  };
  const openWeight = (sub) => { setNewWeight(sub.weight); setWeightModal(sub); };
  const submitWeight = async () => {
    setWeightSaving(true);
    try {
      await paperTradingApi.adjustSubWeight(comboId, weightModal.strategyId, newWeight);
      message.success('权重已调整并触发再平衡'); setWeightModal(null); loadAll();
    } catch (e) {} finally { setWeightSaving(false); }
  };

  if (loading) return (<Spin tip="加载中..."><div style={{ display: 'block', margin: '80px auto' }} /></Spin>);
  if (!detail) return <Card><Text type="danger">组合加载失败</Text></Card>;

  const totalReturn = detail.totalReturn || 0;
  const corr = detail.correlation || {};
  const matrix = corr.matrix || [];
  const avgCorr = corr.averageCorrelation;
  const dr = detail.diversificationRatio;
  const subs = detail.subStrategies || [];

  const corrWarn = avgCorr != null && avgCorr > 0.7;
  const drWarn = dr != null && dr < 1.15;

  // 净值曲线（组合 vs 各子账户）
  const navOption = nav ? (() => {
    const dates = [...new Set([
      ...nav.comboNav.map(n => n.date),
      ...nav.subNavs.flatMap(s => s.nav.map(n => n.date)),
    ])].sort();
    const toLine = (arr) => dates.map(d => {
      const f = arr.find(x => x.date === d);
      return f && f.cumulativeReturn != null ? +(f.cumulativeReturn * 100).toFixed(2) : null;
    });
    const series = [
      { name: '组合', type: 'line', smooth: true, symbol: 'none', data: toLine(nav.comboNav),
        lineStyle: { color: '#1890ff', width: 2.5 }, areaStyle: { color: 'rgba(24,144,255,0.08)' } },
      ...nav.subNavs.map((s, i) => ({
        name: s.strategyCode, type: 'line', smooth: true, symbol: 'none',
        data: toLine(s.nav),
        lineStyle: { width: 1.2, type: 'dashed', color: SUB_COLORS[i % SUB_COLORS.length] },
      })),
    ];
    return {
      backgroundColor: 'transparent',
      tooltip: { trigger: 'axis' },
      legend: { top: 0, type: 'scroll', data: ['组合', ...nav.subNavs.map(s => s.strategyCode)] },
      grid: { left: 55, right: 20, top: 40, bottom: 50 },
      xAxis: { type: 'category', data: dates, axisLabel: { fontSize: 10, rotate: 45 } },
      yAxis: { type: 'value', name: '累计收益率(%)', nameTextStyle: { fontSize: 11 },
        axisLabel: { fontSize: 10 }, splitLine: { lineStyle: { type: 'dashed' } } },
      series,
    };
  })() : null;

  const firstRow = matrix[0];
  const corrColumns = [
    { title: '策略\\策略', dataIndex: 'strategyId', fixed: 'left', width: 90, render: v => <Text strong>{v}</Text> },
    ...(firstRow ? firstRow.correlations.map(c => ({
      title: String(c.strategyId),
      key: 'c_' + c.strategyId,
      render: (_, r) => {
        const cell = r.correlations.find(x => x.strategyId === c.strategyId);
        const v = cell ? cell.correlation : null;
        if (v == null) return '-';
        const color = v > 0.7 ? '#f5222d' : v > 0.4 ? '#fa8c16' : '#52c41a';
        return <span style={{ color, fontWeight: v > 0.7 ? 700 : 400 }}>{(v * 100).toFixed(0)}%</span>;
      },
    })) : []),
  ];

  const subColumns = [
    { title: '策略', dataIndex: 'strategyCode', width: 110 },
    { title: '权重', dataIndex: 'weight', width: 80, render: v => `${(+v * 100).toFixed(1)}%` },
    { title: '初始资金', dataIndex: 'initialCapital', width: 120, render: v => `¥${fmt(v)}` },
    { title: '当前资产', dataIndex: 'totalAssets', width: 120, render: v => <Text strong>¥{fmt(v)}</Text> },
    { title: '收益率', dataIndex: 'returnRate', width: 90, render: v => <Text style={{ color: chgColor(v), fontWeight: 600 }}>{fmtPct(v)}</Text> },
    { title: '贡献', dataIndex: 'contribution', width: 90, render: v => <Text style={{ color: chgColor(v) }}>{fmtPct(v)}</Text> },
    { title: '状态', dataIndex: 'status', width: 80, render: v => {
      const m = { RUNNING: { color: 'green', t: '运行中' }, PAUSED: { color: 'orange', t: '暂停' }, STOPPED: { color: 'red', t: '停止' } };
      const c = m[v] || { color: 'default', t: v };
      return <Tag color={c.color}>{c.t}</Tag>;
    } },
    { title: '操作', width: 170, render: (_, r) => (
      <Space size={2}>
        {r.status === 'RUNNING'
          ? <Button size="small" type="link" icon={<PauseCircleOutlined />} disabled={!canEdit} onClick={() => handlePause(r)}>暂停</Button>
          : <Button size="small" type="link" icon={<PlayCircleOutlined />} disabled={!canEdit} onClick={() => handleResume(r)}>恢复</Button>}
        <Button size="small" type="link" icon={<SwapOutlined />} disabled={!canEdit} onClick={() => openWeight(r)}>调权重</Button>
      </Space>
    ) },
  ];

  const rebColumns = [
    { title: '日期', dataIndex: 'rebalanceDate', width: 110 },
    { title: '触发类型', dataIndex: 'triggerType', width: 90, render: v => {
      const m = { MANUAL: { color: 'blue', t: '手动' }, SCHEDULE: { color: 'green', t: '周期' }, THRESHOLD: { color: 'orange', t: '阈值' } };
      const c = m[v] || { color: 'default', t: v };
      return <Tag color={c.color}>{c.t}</Tag>;
    } },
    { title: '最大偏离', dataIndex: 'maxDriftPct', width: 90, render: v => v != null ? fmtPct(v) : '-' },
    { title: '调仓标的', dataIndex: 'tradedSymbols', width: 160, ellipsis: true, render: v => v || '-' },
    { title: '备注', dataIndex: 'note', ellipsis: true, render: v => v || '-' },
  ];

  const sigColumns = [
    { title: '日期', dataIndex: 'signalDate', width: 100 },
    { title: '代码', dataIndex: 'code', width: 80 },
    { title: '名称', dataIndex: 'name', width: 90 },
    { title: '方向', dataIndex: 'direction', width: 70, render: v => <Tag color={v === 'BUY' ? 'red' : 'green'}>{v === 'BUY' ? '买入' : '卖出'}</Tag> },
    { title: '信号价', dataIndex: 'signalPrice', width: 80, render: v => v != null ? (+v).toFixed(2) : '-' },
    { title: '因子得分', dataIndex: 'factorScore', width: 90, render: v => v != null ? (+v).toFixed(3) : '-' },
    { title: '原因', dataIndex: 'reason', ellipsis: true, render: v => v || '-' },
    { title: '状态', dataIndex: 'status', width: 90, render: v => {
      const m = { PENDING: { color: 'blue', t: '待执行' }, EXECUTED: { color: 'green', t: '已执行' }, SKIPPED: { color: 'default', t: '已跳过' }, EXPIRED: { color: 'red', t: '已过期' }, BLOCKED: { color: 'volcano', t: '风控阻断' } };
      const c = m[v] || { color: 'default', t: v };
      return <Tag color={c.color}>{c.t}</Tag>;
    } },
  ];

  return (
    <div>
      <div style={{ marginBottom: 12, display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <a onClick={onBack}><ArrowLeftOutlined /> 返回列表</a>
        <Button type="primary" icon={<ReloadOutlined />} loading={rebLoading} onClick={handleRebalance} disabled={!canEdit}>手动再平衡</Button>
      </div>

      <Row gutter={[12, 12]} style={{ marginBottom: 12 }}>
        <Col xs={12} sm={8} md={4}><Card size="small"><Statistic title="初始资金" value={detail.initialCapital} prefix="¥" /></Card></Col>
        <Col xs={12} sm={8} md={4}><Card size="small"><Statistic title="当前资产" value={detail.totalAssets} prefix="¥" valueStyle={{ color: chgColor(totalReturn) }} /></Card></Col>
        <Col xs={12} sm={8} md={4}><Card size="small"><Statistic title="累计收益" value={totalReturn * 100} suffix="%" precision={2} valueStyle={{ color: chgColor(totalReturn) }} /></Card></Col>
        <Col xs={12} sm={8} md={4}><Card size="small">
          <Statistic title="分散化比率" value={dr != null ? +dr : 0} precision={2} valueStyle={{ color: drWarn ? '#f5222d' : '#52c41a' }} />
          {drWarn && <Text type="danger" style={{ fontSize: 11 }}>策略同质化风险</Text>}
        </Card></Col>
        <Col xs={24} sm={12} md={8}><Card size="small">
          <Statistic title="平均相关性" value={avgCorr != null ? +(avgCorr * 100).toFixed(1) : 0} suffix="%" precision={1} valueStyle={{ color: corrWarn ? '#f5222d' : '#52c41a' }} />
          {corrWarn && <Text type="danger" style={{ fontSize: 11 }}>ρ&gt;0.7 组合分散失效</Text>}
        </Card></Col>
      </Row>

      {(corrWarn || drWarn) && (
        <Alert type="warning" showIcon style={{ marginBottom: 12 }} message="组合分散化告警"
          description={`平均策略相关性 ${(avgCorr != null ? avgCorr * 100 : 0).toFixed(0)}%、分散化比率 ${(dr != null ? dr : 0).toFixed(2)}。相关性越高，多策略组合的平滑效果越弱，建议替换高相关子策略。`} />
      )}

      <Tabs activeKey={activeTab} onChange={setActiveTab} items={[
        {
          key: 'overview',
          label: <span><PieChartOutlined /> 组合总览</span>,
          children: (
            <>
              <Row gutter={12}>
                <Col xs={24} md={10}>
                  <Card title="子策略权重" size="small" style={{ marginBottom: 12 }}>
                    {subs.length === 0 ? <Empty description="无子策略" /> : subs.map(s => (
                      <div key={s.strategyId} style={{ marginBottom: 8 }}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12 }}>
                          <span>{s.strategyCode}</span>
                          <span>{fmtPct(s.weight)}</span>
                        </div>
                        <Progress percent={Math.round(s.weight * 100)} size="small" />
                      </div>
                    ))}
                  </Card>
                </Col>
                <Col xs={24} md={14}>
                  <Card title="策略间相关性矩阵" size="small" style={{ marginBottom: 12 }}>
                    {matrix.length > 0
                      ? <Table dataSource={matrix} columns={corrColumns} rowKey="strategyId" size="small" pagination={false} scroll={{ x: 'max-content' }} />
                      : <Empty description="暂无足够净值数据计算相关性" />}
                  </Card>
                </Col>
              </Row>
              {navOption && (
                <Card title="组合净值曲线（组合 vs 子策略）" size="small">
                  <ReactECharts option={navOption} style={{ height: 280 }} notMerge={true} />
                </Card>
              )}
            </>
          ),
        },
        {
          key: 'sub',
          label: <span><AppstoreOutlined /> 子策略明细</span>,
          children: (
            <Card size="small">
              <Table dataSource={subs} columns={subColumns} rowKey="subPaperId" size="small" pagination={false} scroll={{ x: 'max-content' }} />
            </Card>
          ),
        },
        {
          key: 'rebalance',
          label: <span><ReloadOutlined /> 再平衡历史</span>,
          children: (
            <Card size="small">
              {rebalanceLog.length === 0
                ? <Empty description="暂无再平衡记录" />
                : <Table dataSource={rebalanceLog} columns={rebColumns} rowKey="id" size="small" pagination={{ pageSize: 10 }} scroll={{ x: 'max-content' }} />}
            </Card>
          ),
        },
        {
          key: 'signals',
          label: <span><ThunderboltOutlined /> 信号流水</span>,
          children: (
            <Card size="small">
              {signals.length === 0
                ? <Empty description="暂无信号" />
                : <Table dataSource={signals} columns={sigColumns} rowKey="id" size="small" pagination={{ pageSize: 10 }} scroll={{ x: 'max-content' }} />}
            </Card>
          ),
        },
      ]} />

      <Modal title={`调整权重 - ${weightModal?.strategyCode || ''}`} open={!!weightModal}
        onOk={submitWeight} onCancel={() => setWeightModal(null)} confirmLoading={weightSaving} okText="保存并再平衡">
        <p style={{ fontSize: 13, color: '#666' }}>调整后系统将重新归一化组合配置，并触发该子策略的再平衡。</p>
        <InputNumber min={0} max={1} step={0.05} value={newWeight} onChange={setNewWeight} style={{ width: '100%' }} addonAfter="权重(0~1)" />
      </Modal>
    </div>
  );
}
