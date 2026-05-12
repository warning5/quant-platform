import React, { useState, useEffect } from 'react';
import {
  Card, Row, Col, Table, Tag, Button, Modal, Select, InputNumber, Space,
  Typography, Statistic, Spin, Tooltip, Alert, message, Popconfirm,
} from 'antd';
import {
  ThunderboltOutlined, PlayCircleOutlined, PauseCircleOutlined,
  CheckCircleOutlined, CloseCircleOutlined, SendOutlined, LeftOutlined,
  InfoCircleOutlined, DeleteOutlined,
} from '@ant-design/icons';
import ReactECharts from 'echarts-for-react';
import { paperTradingApi, strategyApi } from '../../api';

const { Text, Title } = Typography;
const fmt = v => v != null ? (+v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) : '-';
const fmtPct = v => v != null ? `${(+v * 100).toFixed(2)}%` : '-';
const chgColor = v => v > 0 ? '#ef5350' : v < 0 ? '#26a69a' : '#999';

// ─── 模拟盘列表 ───────────────────────────────────────────────────────────────
function PaperList({ onSelect }) {
  const [list, setList] = useState([]);
  const [loading, setLoading] = useState(true);
  const [showCreate, setShowCreate] = useState(false);

  const load = () => {
    setLoading(true);
    paperTradingApi.list().then(d => setList(d || [])).catch(() => setList([])).finally(() => setLoading(false));
  };

  const handleDelete = async (id) => {
    try {
      await paperTradingApi.delete(id);
      message.success('模拟盘已删除');
      load();
    } catch (e) {
      // 错误信息由 axios 拦截器统一展示
    }
  };

  useEffect(() => { load(); }, []);

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 60 },
    { title: '策略', dataIndex: 'strategyCode', width: 120 },
    {
      title: '状态', dataIndex: 'status', width: 90,
      render: v => {
        const map = { RUNNING: { color: 'green', text: '运行中' }, PAUSED: { color: 'orange', text: '暂停' }, STOPPED: { color: 'red', text: '已停止' } };
        const cfg = map[v] || { color: 'default', text: v };
        return <Tag color={cfg.color}>{cfg.text}</Tag>;
      },
    },
    { title: '初始资金', dataIndex: 'initialCapital', width: 120, render: v => `¥${fmt(v)}` },
    { title: '当前资产', dataIndex: 'totalAssets', width: 120, render: v => <Text strong>¥{fmt(v)}</Text> },
    { title: '持仓数', dataIndex: 'positionCount', width: 80 },
    {
      title: '累计收益', width: 110,
      render: (_, r) => {
        const ret = r.initialCapital > 0 ? (r.totalAssets - r.initialCapital) / r.initialCapital : 0;
        return <Text style={{ color: chgColor(ret), fontWeight: 600 }}>{(ret * 100).toFixed(2)}%</Text>;
      },
    },
    {
      title: '操作', width: 150,
      render: (_, r) => (
        <Space size={4}>
          <Button size="small" type="link" onClick={() => onSelect(r.id)}>详情</Button>
          {r.status === 'RUNNING' && (
            <Button size="small" type="link" onClick={() => paperTradingApi.updateStatus(r.id, 'PAUSED').then(load)}>
              暂停
            </Button>
          )}
          {r.status === 'PAUSED' && (
            <Button size="small" type="link" onClick={() => paperTradingApi.updateStatus(r.id, 'RUNNING').then(load)}>
              恢复
            </Button>
          )}
          <Popconfirm title="确认删除此模拟盘？所有持仓、信号、净值数据将一并删除。" onConfirm={() => handleDelete(r.id)} okText="删除" cancelText="取消" okButtonProps={{ danger: true }}>
            <Button size="small" type="link" danger>删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 12, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <div>
          <Title level={4} style={{ margin: 0, display: 'inline' }}>
            <ThunderboltOutlined style={{ marginRight: 8 }} />
            模拟盘交易
          </Title>
          <Tooltip
            title={
              <div style={{ maxWidth: 520 }}>
                <p style={{ margin: '0 0 6px' }}><b>什么是模拟盘？</b></p>
                <p style={{ margin: '0 0 6px' }}>基于策略因子配置，生成买卖信号，手动确认执行，追踪持仓和净值变化。无需真实交易即可验证策略效果。</p>
                <p style={{ margin: '0 0 6px' }}><b>使用流程：</b></p>
                <p style={{ margin: '0 0 4px' }}>1. 先在"策略列表"创建策略（配置因子+权重）</p>
                <p style={{ margin: '0 0 4px' }}>2. 新建模拟盘，选择策略和初始资金</p>
                <p style={{ margin: '0 0 4px' }}>3. 点击"生成信号" → 系统按因子排名推荐买卖</p>
                <p style={{ margin: '0 0 4px' }}>4. 手动确认执行信号 → 系统建仓/清仓</p>
                <p style={{ margin: '0 0 6px' }}>5. 查看净值曲线和持仓盈亏，评估策略效果</p>
                <p style={{ margin: 0 }}><b>价值：</b>零成本验证策略，避免实盘试错。累计IC持续上升的策略更值得实盘。</p>
              </div>
            }
            placement="right"
            styles={{ root: { maxWidth: 520 } }}
          >
            <InfoCircleOutlined style={{ marginLeft: 8, color: '#bbb', fontSize: 16, cursor: 'pointer' }} />
          </Tooltip>
        </div>
        <Button type="primary" onClick={() => setShowCreate(true)}>新建模拟盘</Button>
      </div>

      <Table dataSource={list} columns={columns} rowKey="id" size="small" loading={loading} pagination={false} />

      <CreateModal visible={showCreate} onClose={() => setShowCreate(false)} onCreated={load} />
    </div>
  );
}

// ─── 创建模拟盘弹窗 ───────────────────────────────────────────────────────────
function CreateModal({ visible, onClose, onCreated }) {
  const [strategies, setStrategies] = useState([]);
  const [selectedStrategy, setSelectedStrategy] = useState(null);
  const [capital, setCapital] = useState(1000000);
  const [creating, setCreating] = useState(false);

  useEffect(() => {
    strategyApi.list({ page: 0, size: 100 }).then(res => {
      const content = res?.records || res?.content || res || [];
      setStrategies(Array.isArray(content) ? content : []);
    }).catch(() => {});
  }, []);

  const handleCreate = async () => {
    if (!selectedStrategy) { message.warning('请选择策略'); return; }
    setCreating(true);
    try {
      const s = strategies.find(s => s.id === selectedStrategy);
      await paperTradingApi.create(selectedStrategy, s?.strategyCode, capital);
      message.success('模拟盘创建成功');
      onCreated();
      onClose();
    } catch (e) {
      message.error('创建失败，请稍后重试');
    } finally {
      setCreating(false);
    }
  };

  return (
    <Modal title="新建模拟盘" open={visible} onOk={handleCreate} onCancel={onClose} confirmLoading={creating} okText="创建">
      <div style={{ marginBottom: 16 }}>
        <Text>选择策略</Text>
        <Select
          style={{ width: '100%', marginTop: 4 }}
          placeholder="选择已创建的策略"
          value={selectedStrategy}
          onChange={setSelectedStrategy}
          options={strategies.map(s => ({ label: `${s.strategyCode} - ${s.strategyName}`, value: s.id }))}
          showSearch
          filterOption={(input, option) => (option?.label ?? '').toLowerCase().includes(input.toLowerCase())}
        />
      </div>
      <div>
        <Text>初始资金（元）</Text>
        <InputNumber style={{ width: '100%', marginTop: 4 }} value={capital} onChange={setCapital} min={100000} max={100000000} step={100000} />
      </div>
    </Modal>
  );
}

// ─── 模拟盘详情 ───────────────────────────────────────────────────────────────
function PaperDetail({ paperId, onBack }) {
  const [loading, setLoading] = useState(true);
  const [data, setData] = useState(null);
  const [signals, setSignals] = useState([]);
  const [genLoading, setGenLoading] = useState(false);
  const [execLoading, setExecLoading] = useState(null);
  const [batchExecLoading, setBatchExecLoading] = useState(false);
  const [dividendLoading, setDividendLoading] = useState(false);

  const load = () => {
    setLoading(true);
    paperTradingApi.getDetail(paperId)
      .then(d => setData(d))
      .catch(() => setData(null))
      .finally(() => setLoading(false));
    paperTradingApi.getSignals(paperId)
      .then(d => setSignals(d || []))
      .catch(() => setSignals([]));
  };

  useEffect(() => { load(); }, [paperId]);

  const handleGenerate = async () => {
    setGenLoading(true);
    try {
      const newSignals = await paperTradingApi.generateSignals(paperId);
      message.success(`生成 ${newSignals?.length || 0} 条信号`);
      setSignals(newSignals || []);
      load(); // 刷新持仓
    } catch (e) {
      // 错误信息由 axios 拦截器统一展示，此处不再重复
    } finally {
      setGenLoading(false);
    }
  };

  const handleExecute = async (signalId) => {
    setExecLoading(signalId);
    try {
      await paperTradingApi.executeSignal(signalId);
      message.success('信号执行成功');
      load();
    } catch (e) {
      // 错误信息由 axios 拦截器统一展示，此处不再重复
    } finally {
      setExecLoading(null);
    }
  };

  // 批量执行所有待处理信号
  const handleBatchExecute = async () => {
    setBatchExecLoading(true);
    try {
      const results = await paperTradingApi.executeAllSignals(paperId);
      message.success(`批量执行完成，共 ${results?.length || 0} 笔交易`);
      load();
    } catch (e) {
      // 错误信息由 axios 拦截器统一展示
    } finally {
      setBatchExecLoading(false);
    }
  };

  // 处理分红送股
  const handleProcessDividends = async () => {
    setDividendLoading(true);
    try {
      await paperTradingApi.processDividends(paperId);
      message.success('分红处理完成');
      load();
    } catch (e) {
      // 错误信息由 axios 拦截器统一展示
    } finally {
      setDividendLoading(false);
    }
  };

  if (loading) return <Spin tip="加载中..." style={{ display: 'block', margin: '80px auto' }} />;
  if (!data) return <Card><Text type="danger">加载失败</Text></Card>;

  const { paper, positions = [], navHistory = [] } = data;
  const cumulativeReturn = paper.initialCapital > 0
    ? (paper.totalAssets - paper.initialCapital) / paper.initialCapital : 0;

  // 净值曲线
  const navOption = navHistory.length > 0 ? {
    backgroundColor: 'transparent',
    tooltip: { trigger: 'axis' },
    grid: { left: 70, right: 20, top: 20, bottom: 40 },
    xAxis: { type: 'category', data: navHistory.map(n => n.navDate), axisLabel: { fontSize: 10, rotate: 45 } },
    yAxis: { type: 'value', name: '累计收益率(%)', nameLocation: 'middle', nameGap: 45, nameTextStyle: { fontSize: 11 }, axisLabel: { fontSize: 10 } },
    series: [{
      type: 'line', smooth: true, symbol: 'none',
      data: navHistory.map(n => n.cumulativeReturn != null ? +(n.cumulativeReturn * 100).toFixed(2) : 0),
      lineStyle: { color: '#1890ff', width: 2 },
      areaStyle: { color: 'rgba(24,144,255,0.1)' },
    }],
  } : null;

  // 持仓表格
  const posColumns = [
    { title: '代码', dataIndex: 'code', width: 80 },
    { title: '名称', dataIndex: 'name', width: 90 },
    { title: '持仓', dataIndex: 'shares', width: 70 },
    { title: '成本', dataIndex: 'costPrice', width: 80, render: v => (+v).toFixed(2) },
    { title: '现价', dataIndex: 'currentPrice', width: 80, render: v => (+v).toFixed(2) },
    { title: '市值', dataIndex: 'marketValue', width: 100, render: v => fmt(v) },
    {
      title: '盈亏', dataIndex: 'profitLossPct', width: 90,
      render: v => v != null ? <Text style={{ color: chgColor(+v), fontWeight: 600 }}>{(+v * 100).toFixed(2)}%</Text> : '-',
    },
  ];

  // 信号表格
  const sigColumns = [
    { title: '日期', dataIndex: 'signalDate', width: 100 },
    { title: '代码', dataIndex: 'code', width: 80 },
    { title: '名称', dataIndex: 'name', width: 90 },
    {
      title: '方向', dataIndex: 'direction', width: 70,
      render: v => <Tag color={v === 'BUY' ? 'red' : 'green'}>{v === 'BUY' ? '买入' : '卖出'}</Tag>,
    },
    { title: '信号价', dataIndex: 'signalPrice', width: 80, render: v => v != null ? (+v).toFixed(2) : '-' },
    { title: '因子得分', dataIndex: 'factorScore', width: 80, render: v => v != null ? (+v).toFixed(3) : '-' },
    { title: '原因', dataIndex: 'reason', width: 180, ellipsis: true },
    {
      title: '状态', dataIndex: 'status', width: 80,
      render: v => {
        const map = { PENDING: { color: 'blue', text: '待执行' }, EXECUTED: { color: 'green', text: '已执行' }, SKIPPED: { color: 'default', text: '已跳过' }, EXPIRED: { color: 'red', text: '已过期' } };
        const cfg = map[v] || { color: 'default', text: v };
        return <Tag color={cfg.color}>{cfg.text}</Tag>;
      },
    },
    {
      title: '操作', width: 80,
      render: (_, r) => r.status === 'PENDING' ? (
        <Button size="small" type="primary" loading={execLoading === r.id} onClick={() => handleExecute(r.id)}>
          执行
        </Button>
      ) : '-',
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 12 }}>
        <a onClick={onBack}><LeftOutlined /> 返回列表</a>
      </div>

      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={4}><Card size="small"><Statistic title="初始资金" value={paper.initialCapital} prefix="¥" /></Card></Col>
        <Col span={4}><Card size="small"><Statistic title="当前资产" value={paper.totalAssets} prefix="¥" valueStyle={{ color: chgColor(cumulativeReturn) }} /></Card></Col>
        <Col span={4}><Card size="small"><Statistic title="累计收益" value={cumulativeReturn * 100} suffix="%" precision={2} valueStyle={{ color: chgColor(cumulativeReturn) }} /></Card></Col>
        <Col span={4}><Card size="small"><Statistic title="持仓数" value={paper.positionCount} /></Card></Col>
        <Col span={4}><Card size="small"><Statistic title="可用资金" value={paper.currentCapital} prefix="¥" /></Card></Col>
        <Col span={4} style={{ display: 'flex', flexDirection: 'column', gap: 8, justifyContent: 'center' }}>
          <Button type="primary" icon={<SendOutlined />} onClick={handleGenerate} loading={genLoading}>
            生成信号
          </Button>
          <Button onClick={handleBatchExecute} loading={batchExecLoading}>
            一键执行
          </Button>
          <Button onClick={handleProcessDividends} loading={dividendLoading}>
            处理分红
          </Button>
        </Col>
      </Row>

      {navOption && (
        <Card title="净值曲线" size="small" style={{ marginBottom: 16 }}>
          <ReactECharts option={navOption} style={{ height: 240 }} notMerge={true} />
        </Card>
      )}

      <Card title="当前持仓" size="small" style={{ marginBottom: 16 }}>
        <Table dataSource={positions} columns={posColumns} rowKey="id" size="small" pagination={false} />
        {positions.length === 0 && <Text type="secondary">暂无持仓</Text>}
      </Card>

      <Card title="交易信号" size="small">
        <Table dataSource={signals} columns={sigColumns} rowKey="id" size="small" pagination={{ pageSize: 10 }} />
      </Card>
    </div>
  );
}

// ─── 主页面 ───────────────────────────────────────────────────────────────────

export default function PaperTradingPage() {
  const [selectedId, setSelectedId] = useState(null);

  if (selectedId) {
    return <PaperDetail paperId={selectedId} onBack={() => setSelectedId(null)} />;
  }
  return <PaperList onSelect={setSelectedId} />;
}
