import React, { useState, useEffect } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Card, Row, Col, Table, Tag, Button, Modal, Select, Input, InputNumber, Space, Typography, Statistic, Spin, Tooltip, Alert, Popconfirm, Form, Switch, Divider, Collapse, Tabs, Badge } from 'antd';
import { message } from '../../utils/messageUtil';
import { exportCsv } from '../../utils/exportUtil';
import {
  ThunderboltOutlined, PlayCircleOutlined, PauseCircleOutlined,
  CheckCircleOutlined, CloseCircleOutlined, SendOutlined, LeftOutlined,
  InfoCircleOutlined, DeleteOutlined, AlertOutlined, BellOutlined, EyeOutlined,
  FundOutlined, SafetyCertificateOutlined, BarChartOutlined, DownloadOutlined,
} from '@ant-design/icons';
import ReactECharts from '../../components/LazyECharts';
import { paperTradingApi, strategyApi } from '../../api';
import { useMarketThermometer } from '../../hooks/useMarketThermometer';

const { Text, Title } = Typography;
const fmt = v => v != null ? (+v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) : '-';
const fmtPct = v => v != null ? `${(+v * 100).toFixed(2)}%` : '-';
const chgColor = v => v > 0 ? '#ef5350' : v < 0 ? '#26a69a' : '#999';

// 带问号的标签
const LabelWithTip = ({ text, tip }) => (
  <span style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
    {text}
    <Tooltip title={tip} placement="top">
      <span style={{ color: '#999', cursor: 'help', fontSize: 12 }}>？</span>
    </Tooltip>
  </span>
);

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

      <Table dataSource={list} columns={columns} rowKey="id" size="small" loading={loading} pagination={false} scroll={{ x: 'max-content' }} />

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

// ─── 预警类型映射 ─────────────────────────────────────────────────────────────
const ALERT_TYPE_MAP = {
  MA_BREAK: '均线破位', DROP: '大跌', NOTICE: '公告', REPORT: '研报',
  RISK_CONCENTRATION: '集中度风险', RISK_INDUSTRY: '行业暴露', RISK_DRAWDOWN: '回撤超限',
};
const EVENT_TYPE_MAP = { EVENT_INCREASE: '定增', EVENT_UNLOCK: '解禁', EVENT_INCENTIVE: '股权激励', EVENT_FORECAST: '业绩预告', EVENT快报: '业绩快报' };

// ─── 模拟盘详情 ───────────────────────────────────────────────────────────────
function PaperDetail({ paperId, onBack }) {
  const [loading, setLoading] = useState(true);
  const [data, setData] = useState(null);
  const [signals, setSignals] = useState([]);
  const [genLoading, setGenLoading] = useState(false);
  const [execLoading, setExecLoading] = useState(null);
  const [batchExecLoading, setBatchExecLoading] = useState(false);
  const [dividendLoading, setDividendLoading] = useState(false);
  // 条件单
  const [condOrderVisible, setCondOrderVisible] = useState(false);
  const [condOrderLoading, setCondOrderLoading] = useState(false);
  const [condOrderForm] = Form.useForm();
  const [alerts, setAlerts] = useState([]);
  const [alertLoading, setAlertLoading] = useState(false);
  const [unreadCount, setUnreadCount] = useState(0);
  const [scanLoading, setScanLoading] = useState(false);
  // 风控配置
  const [riskConfig, setRiskConfig] = useState(null);
  const [riskForm] = Form.useForm();
  const [riskSaving, setRiskSaving] = useState(false);
  // 功能区 Tab
  const [activeTab, setActiveTab] = useState('overview');
  const [executionQuality, setExecutionQuality] = useState(null);
  const [qualityLoading, setQualityLoading] = useState(false);

  /* ── 大盘温度计 ─────────────────────── */
  const { data: thData2, status: thStatus2 } = useMarketThermometer();

  const loadAlerts = () => {
    paperTradingApi.getAlerts(paperId).then(d => setAlerts(d || [])).catch(() => setAlerts([]));
    paperTradingApi.getUnreadCount(paperId).then(d => setUnreadCount(d || 0)).catch(() => setUnreadCount(0));
  };

  const loadRiskConfig = () => {
    paperTradingApi.getRiskConfig(paperId).then(cfg => {
      setRiskConfig(cfg);
      riskForm.setFieldsValue({
        stopLossPct: cfg?.stopLossPct ?? 0.08,
        takeProfitPct: cfg?.takeProfitPct ?? 0.30,
        trailingAtrPct: cfg?.trailingAtrPct ?? 0,
        maxPositionPct: cfg?.maxPositionPct ?? 0.20,
        maxIndustryPct: cfg?.maxIndustryPct ?? 0.30,
        maxDrawdownPct: cfg?.maxDrawdownPct ?? 0.15,
        timingEnabled: cfg?.timingEnabled === 1,
        autoBlockEnabled: cfg?.autoBlockEnabled !== 0,  // 默认开启（1）
        twapThreshold: cfg?.twapThreshold ?? 50000,
        benchmarkCode: cfg?.benchmarkCode ?? '000300',
        allocationMode: cfg?.allocationMode ?? 'equal',
      });
    }).catch(() => {});
  };

  const loadExecutionQuality = () => {
    setQualityLoading(true);
    paperTradingApi.getExecutionQuality(paperId).then(d => {
      setExecutionQuality(d || null);
    }).catch(() => {
      setExecutionQuality(null);
    }).finally(() => setQualityLoading(false));
  };

  const handleSaveRiskConfig = async () => {
    const vals = riskForm.getFieldsValue();
    setRiskSaving(true);
    try {
      const params = {
        stopLossPct: vals.stopLossPct,
        takeProfitPct: vals.takeProfitPct,
        trailingAtrPct: vals.trailingAtrPct ?? 0,
        maxPositionPct: vals.maxPositionPct,
        maxIndustryPct: vals.maxIndustryPct,
        maxDrawdownPct: vals.maxDrawdownPct,
        timingEnabled: vals.timingEnabled ? 1 : 0,
        autoBlockEnabled: vals.autoBlockEnabled ? 1 : 0,
        twapThreshold: vals.twapThreshold ?? 50000,
        benchmarkCode: vals.benchmarkCode ?? '000300',
        allocationMode: vals.allocationMode ?? 'equal',
      };
      await paperTradingApi.updateRiskConfig(paperId, params);
      message.success('风控配置已保存');
      loadRiskConfig();
    } catch (e) {
      // axios 统一处理
    } finally {
      setRiskSaving(false);
    }
  };

  const load = () => {
    setLoading(true);
    paperTradingApi.getDetail(paperId)
      .then(d => setData(d))
      .catch(() => setData(null))
      .finally(() => setLoading(false));
    paperTradingApi.getSignals(paperId)
      .then(d => setSignals(d || []))
      .catch(() => setSignals([]));
    loadAlerts();
    loadRiskConfig();
    loadExecutionQuality();
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

  // 【缺陷1】条件单创建
  const handleCreateConditionalOrder = async () => {
    setCondOrderLoading(true);
    try {
      const vals = condOrderForm.getFieldsValue();
      const params = {
        code: vals.code,
        direction: vals.direction,
        orderType: vals.orderType,
        triggerPrice: vals.triggerPrice,
        limitPrice: vals.limitPrice,
        trailPct: vals.trailPct,
        trailAmount: vals.trailAmount,
        reason: vals.reason,
      };
      await paperTradingApi.createConditionalOrder(paperId, params);
      message.success('条件单创建成功');
      setCondOrderVisible(false);
      condOrderForm.resetFields();
      load();
    } catch (e) {
      // 错误信息由 axios 拦截器统一展示
    } finally {
      setCondOrderLoading(false);
    }
  };

  // 【缺陷1】检查条件单触发
  const handleCheckConditionalOrders = async () => {
    try {
      const count = await paperTradingApi.checkConditionalOrders(paperId);
      message.success(`条件单检查完成，触发了 ${count || 0} 笔`);
      load();
    } catch (e) {
      // 错误信息由 axios 拦截器统一展示
    }
  };

  // 手动扫描预警
  const handleScanAlerts = async () => {
    setScanLoading(true);
    try {
      const count = await paperTradingApi.scanAlerts(paperId);
      message.success(`扫描完成，生成 ${count} 条预警`);
      loadAlerts();
    } catch (e) {
      // axios 拦截器统一展示
    } finally {
      setScanLoading(false);
    }
  };

  // 全部标记已读
  const handleMarkAllRead = async () => {
    try {
      const count = await paperTradingApi.markAllRead(paperId);
      message.success(`已标记 ${count} 条预警为已读`);
      loadAlerts();
    } catch (e) {}
  };

  // 单条标记已读
  const handleMarkRead = async (alertId) => {
    try {
      await paperTradingApi.markRead(alertId);
      loadAlerts();
    } catch (e) {}
  };

  // 删除单条预警
  const handleDeleteAlert = async (alertId) => {
    try {
      await paperTradingApi.deleteAlert(alertId);
      message.success('已删除');
      loadAlerts();
    } catch (e) {}
  };

  // 清空所有预警
  const handleClearAlerts = async () => {
    try {
      const count = await paperTradingApi.clearAlerts(paperId);
      message.success(`已清空 ${count} 条预警`);
      loadAlerts();
    } catch (e) {}
  };

  if (loading) return (<Spin tip="加载中..."><div style={{ display: 'block', margin: '80px auto' }} /></Spin>);
  if (!data) return <Card><Text type="danger">加载失败</Text></Card>;

  const { paper, positions = [], navHistory = [], benchmarkNav = [], benchmarkCode = '000300',
    informationRatio, informationRatioAnnualized, informationRatioAvg, informationRatioAvgAnnualized,
    irWindowDays, irExcessReturns = [] } = data;
  const cumulativeReturn = paper.initialCapital > 0
    ? (paper.totalAssets - paper.initialCapital) / paper.initialCapital : 0;

    // 净值曲线（指数增强监控）
    const navOption = navHistory.length > 0 ? (() => {
      const dates = navHistory.map(n => n.navDate);
      const paperNav = navHistory.map(n => n.cumulativeReturn != null ? +(n.cumulativeReturn * 100).toFixed(2) : 0);

      // 基准指数归一化（起点=0%）
      const benchmarkDates = benchmarkNav.map(b => b.date);
      const benchmarkPct = benchmarkNav.map(b => b.nav != null ? +((b.nav - 1) * 100).toFixed(2) : 0);

      // 超额收益 = 模拟盘净值 - 基准净值（同日期对齐）
      const excessData = dates.map((d, i) => {
        const bi = benchmarkDates.indexOf(d);
        return bi >= 0 ? +(paperNav[i] - benchmarkPct[bi]).toFixed(2) : null;
      });

      return {
        backgroundColor: 'transparent',
        tooltip: { trigger: 'axis' },
        legend: { top: 0, right: 0, data: ['模拟盘', benchmarkCode === '000300' ? '沪深300' : '中证500', '超额收益'] },
        grid: { left: 60, right: 60, top: 30, bottom: 40 },
        xAxis: { type: 'category', data: dates, axisLabel: { fontSize: 10, rotate: 45 } },
        yAxis: [
          { type: 'value', name: '累计收益率(%)', nameLocation: 'middle', nameGap: 45, nameTextStyle: { fontSize: 11 }, axisLabel: { fontSize: 10 }, splitLine: { lineStyle: { type: 'dashed' } } },
          { type: 'value', name: '超额(%)', nameLocation: 'middle', nameGap: 45, nameTextStyle: { fontSize: 11 }, axisLabel: { fontSize: 10 }, splitLine: { show: false } },
        ],
        series: [
          { type: 'line', smooth: true, symbol: 'none', name: '模拟盘', xAxisIndex: 0, yAxisIndex: 0,
            data: paperNav, lineStyle: { color: '#1890ff', width: 2 }, areaStyle: { color: 'rgba(24,144,255,0.1)' } },
          { type: 'line', smooth: true, symbol: 'none', name: benchmarkCode === '000300' ? '沪深300' : '中证500', xAxisIndex: 0, yAxisIndex: 0,
            data: benchmarkDates.length ? dates.map(d => { const i = benchmarkDates.indexOf(d); return i >= 0 ? benchmarkPct[i] : null; }) : [],
            lineStyle: { color: '#fa8c16', width: 1.5, type: 'dashed' } },
          { type: 'bar', name: '超额收益', xAxisIndex: 0, yAxisIndex: 1, symbol: 'none',
            data: excessData.map(v => v == null ? '-' : v),
            itemStyle: { color: v => v > 0 ? 'rgba(82,196,26,0.6)' : v < 0 ? 'rgba(245,34,45,0.6)' : 'rgba(153,153,153,0.3)' } },
        ],
      };
    })() : null;

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
    {
      title: '日期',
      dataIndex: 'signalDate',
      width: 100,
      render: (v, r) => (
        <Tooltip title={`因子截面日期: ${r.factorDate || v}`}>
          <span>{v}</span>
        </Tooltip>
      ),
    },
    { title: '代码', dataIndex: 'code', width: 80 },
    { title: '名称', dataIndex: 'name', width: 90 },
    {
      title: '方向', dataIndex: 'direction', width: 70,
      render: v => <Tag color={v === 'BUY' ? 'red' : 'green'}>{v === 'BUY' ? '买入' : '卖出'}</Tag>,
    },
    {
      title: '订单类型', dataIndex: 'orderType', width: 100,
      render: v => {
        if (!v || v === 'MARKET') return <Tag>市价单</Tag>;
        const map = { LIMIT: { color: 'orange', text: '限价单' }, STOP: { color: 'red', text: '止损单' }, STOP_LIMIT: { color: 'volcano', text: '止损限价' }, TRAILING_STOP: { color: 'purple', text: '追踪止损' } };
        const cfg = map[v] || { color: 'default', text: v };
        return <Tag color={cfg.color}>{cfg.text}</Tag>;
      },
    },
    {
      title: '触发价', dataIndex: 'triggerPrice', width: 80,
      render: v => v != null ? (+v).toFixed(2) : '-',
    },
    { title: '信号价', dataIndex: 'signalPrice', width: 80, render: v => v != null ? (+v).toFixed(2) : '-' },
    { title: '执行价', dataIndex: 'executedPrice', width: 80, render: v => v != null ? (+v).toFixed(2) : '-' },
    { title: '偏差', width: 80, render: (_, r) => r.priceDeviationPct != null ? ((r.priceDeviationPct > 0 ? '+' : '') + (r.priceDeviationPct * 100).toFixed(2) + '%') : '-' },
    { title: '因子得分', dataIndex: 'factorScore', width: 80, render: v => v != null ? (+v).toFixed(3) : '-' },
    { title: '原因', dataIndex: 'reason', width: 180, ellipsis: true },
    {
      title: '状态', dataIndex: 'status', width: 80,
      render: v => {
        const map = { PENDING: { color: 'blue', text: '待执行' }, EXECUTED: { color: 'green', text: '已执行' }, SKIPPED: { color: 'default', text: '已跳过' }, EXPIRED: { color: 'red', text: '已过期' }, BLOCKED: { color: 'volcano', text: '风控阻断' } };
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
      <div style={{ marginBottom: 12, display: 'flex', alignItems: 'center', gap: 12 }}>
        <a onClick={onBack}><LeftOutlined /> 返回列表</a>
        {thData2 && thStatus2 && (
          <Tooltip title={`大盘${thStatus2.label}（${thData2.fearGreedIndex?.toFixed(0)}°），${thStatus2.action}`}>
            <Tag color={thStatus2.label === '极度贪婪' ? 'red' : thStatus2.label === '极度恐慌' ? 'green' : 'blue'}>
              <Link to="/market-thermometer" style={{ color: 'inherit' }}>{thStatus2.label} {thData2.fearGreedIndex?.toFixed(0)}°</Link>
            </Tag>
          </Tooltip>
        )}
      </div>

      <Row gutter={[12, 12]} style={{ marginBottom: 12 }}>
        <Col xs={12} sm={8} md={4}><Card size="small"><Statistic title="初始资金" value={paper.initialCapital} prefix="¥" /></Card></Col>
        <Col xs={12} sm={8} md={4}><Card size="small"><Statistic title="当前资产" value={paper.totalAssets} prefix="¥" valueStyle={{ color: chgColor(cumulativeReturn) }} /></Card></Col>
        <Col xs={12} sm={8} md={4}><Card size="small"><Statistic title="累计收益" value={cumulativeReturn * 100} suffix="%" precision={2} valueStyle={{ color: chgColor(cumulativeReturn) }} /></Card></Col>
        <Col xs={12} sm={8} md={4}><Card size="small"><Statistic title="持仓数" value={paper.positionCount} /></Card></Col>
        <Col xs={24} sm={12} md={8}><Card size="small"><Statistic title="可用资金" value={paper.currentCapital} prefix="¥" /></Card></Col>
      </Row>

      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        style={{ marginBottom: 16 }}
        tabBarStyle={{ marginBottom: 16 }}
        items={[
          {
            key: 'overview',
            label: <span><FundOutlined /> 基础使用</span>,
            children: (
              <>
                <Row gutter={12} style={{ marginBottom: 16 }}>
                  <Col span={24} style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                    <Button type="primary" icon={<SendOutlined />} onClick={handleGenerate} loading={genLoading}>生成信号</Button>
                    <Button onClick={handleBatchExecute} loading={batchExecLoading}>一键执行</Button>
                    <Button onClick={handleProcessDividends} loading={dividendLoading}>处理分红</Button>
                  </Col>
                </Row>
                {navOption && (
                  <Card
                    title={
                      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                        <span>净值曲线</span>
                        {informationRatio != null && (
                          <Space size={16}>
                            <Tooltip title={`滚动${irWindowDays || 20}日信息比率 = 超额收益均值 / 超额收益标准差`}>
                              <span style={{ fontSize: 13, color: '#666' }}>
                                IR: <Text strong style={{ color: informationRatio > 0 ? '#52c41a' : informationRatio < 0 ? '#f5222d' : '#999' }}>
                                  {informationRatio.toFixed(3)}
                                </Text>
                                {informationRatioAnnualized != null && (
                                  <span style={{ fontSize: 12, color: '#999', marginLeft: 4 }}>(年化 {informationRatioAnnualized.toFixed(3)})</span>
                                )}
                              </span>
                            </Tooltip>
                            {informationRatioAvg != null && (
                              <Tooltip title="历史平均IR（全周期）">
                                <span style={{ fontSize: 13, color: '#666' }}>
                                  均值IR: <Text strong style={{ color: informationRatioAvg > 0 ? '#52c41a' : informationRatioAvg < 0 ? '#f5222d' : '#999' }}>
                                    {informationRatioAvg.toFixed(3)}
                                  </Text>
                                </span>
                              </Tooltip>
                            )}
                          </Space>
                        )}
                      </div>
                    }
                    size="small"
                    style={{ marginBottom: 16 }}
                  >
                    <ReactECharts option={navOption} style={{ height: 240 }} notMerge={true} />
                  </Card>
                )}
                <Card title="当前持仓" size="small">
                  <Row gutter={16}>
                    <Col span={positions.length > 3 ? 16 : 24}>
                      <Table dataSource={positions} columns={posColumns} rowKey="id" size="small" pagination={false} scroll={{ x: 'max-content' }} />
                      {positions.length === 0 && <Text type="secondary">暂无持仓</Text>}
                    </Col>
                    {positions.length > 3 && (
                      <Col xs={24} sm={8}>
                        <ReactECharts
                          option={{
                            tooltip: { trigger: 'item', formatter: '{b}: ¥{c} ({d}%)' },
                            legend: { type: 'scroll', orient: 'vertical', right: 0, top: 20, bottom: 20, itemWidth: 10, itemHeight: 10, textStyle: { fontSize: 11 } },
                            series: [{
                              type: 'pie',
                              radius: ['35%', '70%'],
                              center: ['35%', '50%'],
                              data: positions.map(p => ({
                                name: p.name || p.code,
                                value: +p.marketValue || 0,
                                itemStyle: { color: +p.profitLossPct > 0 ? '#cf1322' : +p.profitLossPct < 0 ? '#3f8600' : '#8c8c8c' },
                              })),
                              label: { show: false },
                              emphasis: { label: { show: true, fontSize: 12, fontWeight: 'bold' } },
                            }],
                          }}
                          style={{ height: 200 }}
                          notMerge={true}
                        />
                        <Text type="secondary" style={{ fontSize: 11, textAlign: 'center', display: 'block' }}>
                          持仓市值分布（红=盈利 绿=亏损）
                        </Text>
                      </Col>
                    )}
                  </Row>
                </Card>
              </>
            ),
          },
          {
            key: 'signals',
            label: <span><ThunderboltOutlined /> 择时信号</span>,
            children: (
              <>
                <Row gutter={12} style={{ marginBottom: 16 }}>
                  <Col span={24} style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                    <Button type="primary" icon={<SendOutlined />} onClick={handleGenerate} loading={genLoading}>生成信号</Button>
                    <Button icon={<SafetyCertificateOutlined />} onClick={() => setCondOrderVisible(true)}>创建条件单</Button>
                    <Button icon={<CheckCircleOutlined />} onClick={handleCheckConditionalOrders}>检查条件单</Button>
                    <Button icon={<DownloadOutlined />} onClick={() => exportCsv({ data: signals, columns: sigColumns, filename: `信号_${paperId}` })}>导出CSV</Button>
                  </Col>
                </Row>
                <Alert
                  message="择时信号说明"
                  description={
                    <Space direction="vertical" size={4}>
                      <Text type="secondary">• 信号按因子综合得分排序，买入TOP标的，卖出分数下降的持仓</Text>
                      <Text type="secondary">• 开启大盘择时后，大盘温度计为空头信号时自动暂停新开仓（不影响已有持仓）</Text>
                      <Text type="secondary">• 执行信号后，按止盈止损规则自动监控持仓盈亏</Text>
                      <Text type="secondary">• 条件单（限价/止损/追踪止损）需满足触发条件后才执行，可点击"检查条件单"手动触发检查</Text>
                    </Space>
                  }
                  type="info"
                  showIcon
                  style={{ marginBottom: 16 }}
                />
                <Card title="交易信号" size="small">
                  <Table dataSource={signals} columns={sigColumns} rowKey="id" size="small" pagination={{ pageSize: 10 }} scroll={{ x: 'max-content' }} />
                  {signals.length === 0 && <Text type="secondary">暂无信号，请先生成信号</Text>}
                </Card>
              </>
            ),
          },
          {
            key: 'alerts',
            label: (
              <span>
                <AlertOutlined /> 持仓预警
                {unreadCount > 0 && <Badge count={unreadCount} size="small" style={{ marginLeft: 6 }} />}
              </span>
            ),
            children: (
              <>
                <Row gutter={12} style={{ marginBottom: 16 }}>
                  <Col span={24} style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                    <Button icon={<BellOutlined />} onClick={handleScanAlerts} loading={scanLoading}>手动扫描预警</Button>
                    {unreadCount > 0 && <Button icon={<EyeOutlined />} onClick={handleMarkAllRead}>全部已读</Button>}
                    {alerts.length > 0 && (
                      <Popconfirm title="确认清空所有预警？" onConfirm={handleClearAlerts} okText="清空" cancelText="取消">
                        <Button danger icon={<DeleteOutlined />}>清空</Button>
                      </Popconfirm>
                    )}
                  </Col>
                </Row>
                <Alert
                  message="持仓预警说明"
                  description={
                    <Space direction="vertical" size={4}>
                      <Text type="secondary">• 技术预警：均线破位（跌破5/20日均线）、大跌（单日跌幅超阈值）</Text>
                      <Text type="secondary">• 事件预警：近7天内的定增/解禁/股权激励/业绩预告事件驱动</Text>
                      <Text type="secondary">• 风控预警：集中度超限、行业暴露超限、最大回撤超限</Text>
                      <Text type="secondary">• 建议定期扫描，关注 CRITICAL/WARNING 级别的红色/橙色预警</Text>
                    </Space>
                  }
                  type="warning"
                  showIcon
                  style={{ marginBottom: 16 }}
                />
                <Card
                  title={
                    <span>
                      <AlertOutlined /> 预警列表
                      {unreadCount > 0 && <Tag color="red" style={{ marginLeft: 8 }}>{unreadCount} 条未读</Tag>}
                    </span>
                  }
                  size="small"
                >
                  {alerts.length === 0 ? (
                    <Text type="secondary">暂无预警信息</Text>
                  ) : (
                    <Table
                      dataSource={alerts}
                      rowKey="id"
                      size="small"
                      pagination={{ pageSize: 10 }}
                      scroll={{ x: 'max-content' }}
                      rowClassName={r => !r.isRead ? 'alert-unread-row' : ''}
                      columns={[
                        { title: '日期', dataIndex: 'alertDate', width: 100, render: v => !v ? '-' : v },
                        {
                          title: '级别', dataIndex: 'alertLevel', width: 70,
                          render: v => {
                            const cfg = { CRITICAL: { color: 'red', text: '严重' }, WARNING: { color: 'orange', text: '警告' }, INFO: { color: 'blue', text: '提示' } };
                            const c = cfg[v] || { color: 'default', text: v };
                            return <Tag color={c.color}>{c.text}</Tag>;
                          },
                        },
                        {
                          title: '类型', dataIndex: 'alertType', width: 90,
                          render: v => {
                            const map = {
                              MA_BREAK: '均线破位', DROP: '大跌', NOTICE: '公告', REPORT: '研报',
                              RISK_CONCENTRATION: '集中度', RISK_INDUSTRY: '行业暴露', RISK_DRAWDOWN: '回撤',
                              EVENT_INCREASE: '定增', EVENT_UNLOCK: '解禁', EVENT_INCENTIVE: '股权激励',
                              EVENT_FORECAST: '业绩预告', EVENT_EXPRESS: '业绩快报',
                            };
                            return map[v] || (v?.startsWith('EVENT_') ? '事件驱动' : v);
                          },
                        },
                        {
                          title: '预警内容', dataIndex: 'title', ellipsis: true,
                          render: (v, r) => (
                            <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                              {!r.isRead && <BellOutlined style={{ color: '#ff4d4f', fontSize: 12, flexShrink: 0 }} />}
                              <Tooltip title={r.detail}><span>{v}</span></Tooltip>
                            </div>
                          ),
                        },
                        {
                          title: '操作', width: 90,
                          render: (_, r) => (
                            <Space size={0}>
                              {!r.isRead && <Button size="small" type="link" onClick={() => handleMarkRead(r.id)}>已读</Button>}
                              <Popconfirm title="确认删除此预警？" onConfirm={() => handleDeleteAlert(r.id)} okText="删除" cancelText="取消">
                                <Button size="small" type="link" danger>删除</Button>
                              </Popconfirm>
                            </Space>
                          ),
                        },
                      ]}
                    />
                  )}
                </Card>
              </>
            ),
          },
          {
            key: 'risk',
            label: <span><SafetyCertificateOutlined /> 风控配置</span>,
            forceRender: true,
            children: (
              <>
                <Row gutter={12} style={{ marginBottom: 16 }}>
                  <Col span={24} style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                    <Button type="primary" icon={<CheckCircleOutlined />} onClick={handleSaveRiskConfig} loading={riskSaving}>保存配置</Button>
                  </Col>
                </Row>
                <Alert
                  message="风控配置说明"
                  description={
                    <Space direction="vertical" size={4}>
                      <Text type="secondary">• 止损/止盈：单笔持仓盈亏达到阈值时，自动生成卖出信号（需手动执行）</Text>
                      <Text type="secondary">• 最大单股集中度/行业暴露：超过阈值后生成风控预警（建议≤20%/≤30%）</Text>
                      <Text type="secondary">• 大盘择时：开启后大盘温度计为空头时自动暂停新开仓</Text>
                      <Text type="secondary">• 资金分配：equal=等权，dynamic=因子得分权重，kelly=凯利公式（需≥5笔历史）</Text>
                    </Space>
                  }
                  type="info"
                  showIcon
                  style={{ marginBottom: 16 }}
                />
                <Form form={riskForm} layout="vertical" size="small">
                  <Row gutter={16}>
                    <Col xs={24} sm={8}>
                      <Form.Item label={<LabelWithTip text="止损阈值" tip="单笔持仓亏损达到此比例，触发止损卖出信号。例：0.08=亏损8%止损" />} name="stopLossPct">
                        <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} placeholder="0.08" />
                      </Form.Item>
                    </Col>
                    <Col xs={24} sm={8}>
                      <Form.Item label={<LabelWithTip text="止盈阈值" tip="单笔持仓盈利达到此比例，触发止盈卖出信号。例：0.30=盈利30%止盈" />} name="takeProfitPct">
                        <InputNumber min={0} max={10} step={0.01} style={{ width: '100%' }} placeholder="0.30" />
                      </Form.Item>
                    </Col>
                    <Col xs={24} sm={8}>
                      <Form.Item label={<LabelWithTip text="最大单股集中度" tip="单一股票市值占总资产的比例上限。例：0.20=单股不超过20%" />} name="maxPositionPct">
                        <InputNumber min={0.01} max={1} step={0.01} style={{ width: '100%' }} placeholder="0.20" />
                      </Form.Item>
                    </Col>
                    <Col xs={24} sm={8}>
                      <Form.Item label={<LabelWithTip text="最大行业暴露" tip="同一行业市值占总资产的比例上限。建议≤30%。例：0.30=单一行业不超过30%" />} name="maxIndustryPct">
                        <InputNumber min={0.01} max={0.35} step={0.01} style={{ width: '100%' }} placeholder="0.30" />
                      </Form.Item>
                    </Col>
                    <Col xs={24} sm={8}>
                      <Form.Item label={<LabelWithTip text="最大回撤限制" tip="从历史峰值最大回撤比例。例：0.15=回撤不超过15%" />} name="maxDrawdownPct">
                        <InputNumber min={0.01} max={1} step={0.01} style={{ width: '100%' }} placeholder="0.15" />
                      </Form.Item>
                    </Col>
                    <Col xs={24} sm={8}>
                      <Form.Item label={<LabelWithTip text="大盘择时" tip="开启后，大盘温度计为空头信号时自动暂停新开仓（不影响已有持仓）" />} name="timingEnabled" valuePropName="checked">
                        <Switch checkedChildren="开" unCheckedChildren="关" />
                      </Form.Item>
                    </Col>
                    <Col xs={24} sm={8}>
                      <Form.Item label={<LabelWithTip text="自动阻断" tip="开启后，行业集中度/单股仓位/最大回撤超限时自动阻止交易（而非仅生成预警）。关闭则仅生成预警不阻断。" />} name="autoBlockEnabled" valuePropName="checked">
                        <Switch checkedChildren="开" unCheckedChildren="关" />
                      </Form.Item>
                    </Col>
                    <Col xs={24} sm={8}>
                      <Form.Item label={<LabelWithTip text="TWAP拆分阈值" tip="单笔委托超过此股数时，自动触发TWAP拆单（大单拆分为多笔小单执行，降低市场冲击）。设为0则禁用TWAP。" />} name="twapThreshold">
                        <InputNumber min={0} step={10000} style={{ width: '100%' }} addonAfter="股" />
                      </Form.Item>
                    </Col>
                    <Col span={12}>
                      <Form.Item label={<LabelWithTip text="基准指数" tip="用于计算超额收益。净值曲线图中叠加显示基准指数走势。" />} name="benchmarkCode">
                        <Select options={[
                          { label: '沪深300 (000300)', value: '000300' },
                          { label: '中证500 (000905)', value: '000905' },
                          { label: '中证1000 (000852)', value: '000852' },
                          { label: '创业板指 (399006)', value: '399006' },
                          { label: '万得全A (881001)', value: '881001' },
                        ]} />
                      </Form.Item>
                    </Col>
                    <Col span={12}>
                      <Form.Item label={<LabelWithTip text="资金分配模式" tip={
                        <div>
                          等权：初始资金÷N，每只标的平均分配<br />
                          动态权重：按因子得分比例分配（min=初始/20，max=初始/5）<br />
                          凯利公式：基于历史胜率计算最优仓位（需≥5笔，5%~25%）
                        </div>
                      } />} name="allocationMode">
                        <Select options={[
                          { label: '等权分配 (equal)', value: 'equal' },
                          { label: '动态权重 (dynamic)', value: 'dynamic' },
                          { label: '凯利公式 (kelly)', value: 'kelly' },
                        ]} />
                      </Form.Item>
                    </Col>
                  </Row>
                </Form>
              </>
            ),
          },
          {
            key: 'quality',
            label: <span><BarChartOutlined /> 执行质量</span>,
            children: (
              <>
                <Alert
                  message="执行质量分析说明"
                  description={
                    <Space direction="vertical" size={4}>
                      <Text type="secondary">• 自动记录每笔执行的信号价与成交价偏差、滑点成本、佣金</Text>
                      <Text type="secondary">• 平均偏差接近0%说明信号价与执行价高度一致</Text>
                      <Text type="secondary">• 滑点成本 = |执行价 - 信号价| × 股数</Text>
                    </Space>
                  }
                  type="info"
                  showIcon
                  style={{ marginBottom: 16 }}
                />
                {qualityLoading ? <Spin /> : (
                  <>
                    {executionQuality && (
                      <Row gutter={16} style={{ marginBottom: 16 }}>
                        <Col span={6}><Card size="small"><Statistic title="总交易笔数" value={executionQuality.totalTrades || 0} /></Card></Col>
                        <Col span={6}><Card size="small"><Statistic title="平均偏差(%)" value={(executionQuality.avgDeviationPct || 0) * 100} precision={4} valueStyle={{ color: (executionQuality.avgDeviationPct || 0) > 0 ? '#f5222d' : '#52c41a' }} /></Card></Col>
                        <Col span={6}><Card size="small"><Statistic title="平均滑点成本(元)" value={executionQuality.avgSlippageCost || 0} precision={2} /></Card></Col>
                        <Col span={6}><Card size="small"><Statistic title="总交易成本(元)" value={executionQuality.totalCost || 0} precision={2} prefix="¥" /></Card></Col>
                      </Row>
                    )}
                    {executionQuality?.records?.length > 0 ? (
                      <Card title="执行质量明细" size="small">
                        <Table
                          dataSource={executionQuality.records}
                          rowKey="id"
                          size="small"
                          pagination={{ pageSize: 10 }}
                          scroll={{ x: 'max-content' }}
                          columns={[
                            { title: '时间', dataIndex: 'executionTime', render: v => v || '-' },
                            { title: '代码', dataIndex: 'code' },
                            { title: '方向', dataIndex: 'direction', render: v => v === 'BUY' ? <Tag color="red">买</Tag> : <Tag color="green">卖</Tag> },
                            { title: '信号价', dataIndex: 'signalPrice', render: v => v != null ? (+v).toFixed(2) : '-' },
                            { title: '执行价', dataIndex: 'executedPrice', render: v => v != null ? (+v).toFixed(2) : '-' },
                            { title: '偏差(%)', render: (_, r) => {
                              if (r.priceDeviationPct == null) return '-';
                              const prefix = r.priceDeviationPct > 0 ? '+' : '';
                              return prefix + (r.priceDeviationPct * 100).toFixed(4) + '%';
                            } },
                            { title: '滑点成本', dataIndex: 'slippageCost', render: v => v != null ? (+v).toFixed(2) : '-' },
                            { title: '佣金', dataIndex: 'commission', render: v => v != null ? (+v).toFixed(2) : '-' },
                          ]}
                        />
                      </Card>
                    ) : (
                      <Text type="secondary">暂无执行质量数据，请先执行交易信号</Text>
                    )}
                  </>
                )}
              </>
            ),
          },
        ]}
      />

      {/* ── 条件单创建 Modal ── */}
      <Modal
        title="创建条件单"
        open={condOrderVisible}
        onCancel={() => { setCondOrderVisible(false); condOrderForm.resetFields(); }}
        onOk={handleCreateConditionalOrder}
        confirmLoading={condOrderLoading}
        width={520}
      >
        <Form form={condOrderForm} layout="vertical" initialValues={{ direction: 'SELL', orderType: 'LIMIT' }}>
          <Form.Item label="股票代码" name="code" rules={[{ required: true, message: '请输入股票代码' }]}>
            <InputNumber style={{ width: '100%' }} placeholder="如 600519" />
          </Form.Item>
          <Form.Item label="信号方向" name="direction" rules={[{ required: true }]}>
            <Select options={[
              { label: '卖出 (SELL)', value: 'SELL' },
              { label: '买入 (BUY)', value: 'BUY' },
            ]} />
          </Form.Item>
          <Form.Item label="订单类型" name="orderType" rules={[{ required: true }]}>
            <Select options={[
              { label: '限价单 (LIMIT)', value: 'LIMIT' },
              { label: '止损单 (STOP)', value: 'STOP' },
              { label: '止损限价单 (STOP_LIMIT)', value: 'STOP_LIMIT' },
              { label: '追踪止损 (TRAILING_STOP)', value: 'TRAILING_STOP' },
            ]} />
          </Form.Item>
          <Form.Item label="触发价格（元）" name="triggerPrice">
            <InputNumber style={{ width: '100%' }} min={0} step={0.01} placeholder="限价单/止损单的触发价" />
          </Form.Item>
          <Form.Item label="限价（元）" name="limitPrice">
            <InputNumber style={{ width: '100%' }} min={0} step={0.01} placeholder="止损限价单的执行限价" />
          </Form.Item>
          <Form.Item label="追踪止损回撤比例" name="trailPct">
            <InputNumber style={{ width: '100%' }} min={0} max={1} step={0.01} placeholder="如 0.05 = 5%" />
          </Form.Item>
          <Form.Item label="追踪止损回撤金额（元）" name="trailAmount">
            <InputNumber style={{ width: '100%' }} min={0} step={0.5} placeholder="从最高价回撤金额" />
          </Form.Item>
          <Form.Item label="备注原因" name="reason">
            <Input placeholder="可选备注" />
          </Form.Item>
        </Form>
        <Alert
          message="条件单说明"
          description={
            <Space direction="vertical" size={4}>
              <Text type="secondary">• 限价单(LIMIT)：买入时当前价≤触发价才执行；卖出时当前价≥触发价才执行</Text>
              <Text type="secondary">• 止损单(STOP)：当前价≤触发价时卖出（保护性止损）</Text>
              <Text type="secondary">• 止损限价单(STOP_LIMIT)：触发后仅在限价范围内执行</Text>
              <Text type="secondary">• 追踪止损(TRAILING_STOP)：从最高价回撤指定比例/金额时卖出（动态止损）</Text>
            </Space>
          }
          type="info"
          showIcon
          style={{ marginTop: 12 }}
        />
      </Modal>

      {/* ── 未读预警行高亮样式注入 ── */}
      <style>{`.alert-unread-row { background-color: #fff2f0; }`}</style>
    </div>
  );
}

// ─── 主页面 ───────────────────────────────────────────────────────────────────

export default function PaperTradingPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [selectedId, setSelectedId] = useState(() => {
    const id = searchParams.get('id');
    return id ? Number(id) : null;
  });

  const handleSelect = (id) => {
    setSelectedId(id);
    setSearchParams({ id });
  };

  const handleBack = () => {
    setSelectedId(null);
    setSearchParams({});
  };

  if (selectedId) {
    return <PaperDetail paperId={selectedId} onBack={handleBack} />;
  }
  return <PaperList onSelect={handleSelect} />;
}
