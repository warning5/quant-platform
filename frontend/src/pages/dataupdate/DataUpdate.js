import React, { useState, useEffect, useRef, useCallback } from 'react';
import {
  Card, Row, Col, Statistic, Button, Select, DatePicker, Form,
  Checkbox, Tag, Typography, Space, Alert, Table, Tooltip, Progress, Badge, message, Divider, Tabs
} from 'antd';
import {
  PlayCircleOutlined, StopOutlined, ReloadOutlined,
  ClockCircleOutlined, ThunderboltOutlined, WarningOutlined,
  FilterOutlined, SearchOutlined, CloudSyncOutlined,
  DatabaseOutlined, RiseOutlined, FallOutlined,
  CalendarOutlined, BarChartOutlined, PieChartOutlined, DollarOutlined,
  LineChartOutlined, GiftOutlined
} from '@ant-design/icons';
import dayjs from 'dayjs';
import { dataUpdateApi } from '../../api/index';

const { Text, Title } = Typography;
const { RangePicker } = DatePicker;

// WebSocket 地址
const WS_URL = (window.location.protocol === 'https:' ? 'wss:' : 'ws:') +
  '//' + window.location.host + '/ws-native';

const SOURCE_OPTIONS = [
  { value: 'ALL', label: '全部数据源' },
  { value: 'BAOSTOCK', label: 'Baostock (沪深 SH/SZ)' },
  { value: 'TENCENT', label: '腾讯证券 (北交所 BJ)' },
];

const MARKET_OPTIONS = [
  { value: 'ALL', label: '全部市场' },
  { value: 'SH', label: '沪市 (SH)' },
  { value: 'SZ', label: '深市 (SZ)' },
  { value: 'BJ', label: '北交所 (BJ)' },
];

const POOL_OPTIONS = [
  { value: 'ALL', label: '全部股票' },
  { value: 'SH300', label: '沪深300' },
  { value: 'SZ50', label: '上证50' },
  { value: 'ZZ500', label: '中证500' },
  { value: 'ZZ1000', label: '中证1000' },
  { value: 'STAR50', label: '科创板50' },
];

const MARKET_NAMES = { SH: '沪市', SZ: '深市', BJ: '北交所' };
const MARKET_COLORS = { SH: '#1677ff', SZ: '#52c41a', BJ: '#fa8c16' };

// 指数数据明细列定义（工厂函数，传入最新交易日用于状态判断）
const getIndexStatsColumns = (latestTradeDate) => [
  { title: '代码', dataIndex: 'code', width: 100 },
  { title: '名称', dataIndex: 'name', width: 120 },
  { title: '记录数', dataIndex: 'record_count', width: 100, align: 'right',
    render: (v) => v?.toLocaleString() },
  { title: '最早日期', dataIndex: 'min_date', width: 120 },
  { title: '最新日期', dataIndex: 'max_date', width: 120 },
  {
    title: '状态', key: 'status', width: 80, align: 'center',
    render: (_, record) => {
      const isLatest = record.max_date && latestTradeDate && record.max_date === latestTradeDate;
      return isLatest
        ? <Tag color="success" style={{ margin: 0 }}>最新</Tag>
        : <Tooltip title={`最新数据: ${record.max_date}`}>
            <Tag color="warning" style={{ margin: 0 }}>待更新</Tag>
          </Tooltip>;
    }
  },
];

// ========== 通用：状态标签 ==========
const statusTag = (status) => {
  const map = {
    IDLE: { color: 'default', text: '空闲' },
    RUNNING: { color: 'processing', text: '运行中' },
    SUCCESS: { color: 'success', text: '已完成' },
    FAILED: { color: 'error', text: '失败' },
    CANCELLED: { color: 'warning', text: '已取消' },
  };
  const s = map[status] || map.IDLE;
  return <Tag color={s.color}>{s.text}</Tag>;
};

// ========== 通用：运行时间 ==========
const getDuration = (task) => {
  if (!task || !task.startTime) return '--';
  const start = dayjs(task.startTime);
  const end = task.endTime ? dayjs(task.endTime) : dayjs();
  const diff = end.diff(start, 'second');
  if (diff < 60) return `${diff}秒`;
  if (diff < 3600) return `${Math.floor(diff / 60)}分${diff % 60}秒`;
  return `${Math.floor(diff / 3600)}时${Math.floor((diff % 3600) / 60)}分`;
};

// ========== 通用：进度条 ==========
const renderProgressBar = (task) => {
  if (!task) return null;
  const pct = task.progress || 0;
  const isRunning = task.status === 'RUNNING';
  return (
    <div style={{ marginBottom: 8 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }}>
        <Text style={{ fontSize: 12 }}>{task.currentStep || '暂无任务'}</Text>
        <Text style={{ fontSize: 12, color: '#8c8c8c' }}>{task.processedStocks || 0}/{task.totalStocks || 0} · {getDuration(task)}</Text>
      </div>
      <Progress percent={pct} size="small" strokeColor={isRunning ? '#1677ff' : '#52c41a'} showInfo={false} />
      {task.error && <Text type="danger" style={{ fontSize: 12 }}>{task.error}</Text>}
    </div>
  );
};

// ========== 通用：日志区域 ==========
const renderLogs = (logs, logRef) => (
  <div ref={logRef}
    style={{
      height: 200, overflowY: 'auto', backgroundColor: '#1a1a2e',
      borderRadius: 6, padding: '8px 12px', fontFamily: 'Consolas, Monaco, monospace',
      fontSize: 12, lineHeight: '18px',
    }}>
    {logs.length === 0 ? (
      <Text type="secondary" style={{ color: '#555' }}>等待任务启动...</Text>
    ) : (
      logs.map(l => (
        <div key={l.id} style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>
          <span style={{ color: '#6c7a89' }}>[{l.time}]</span>{' '}
          <span style={{
            color: l.text?.includes('[ERROR]') || l.text?.includes('失败') || l.text?.includes('FAIL')
              ? '#ff6b6b'
              : '#e0e0e0'
          }}>{l.text}</span>
        </div>
      ))
    )}
  </div>
);

function DataUpdate() {
  const [form] = Form.useForm();
  const [indexForm] = Form.useForm();
  const [dividendForm] = Form.useForm();
  const wsRef = useRef(null);

  // 三个 Tab 各自独立的任务状态和日志
  const [dailyTask, setDailyTask] = useState(null);
  const [dailyLogs, setDailyLogs] = useState([]);
  const dailyLogRef = useRef(null);

  const [indexTask, setIndexTask] = useState(null);
  const [indexLogs, setIndexLogs] = useState([]);
  const indexLogRef = useRef(null);

  const [dividendTask, setDividendTask] = useState(null);
  const [dividendLogs, setDividendLogs] = useState([]);
  const dividendLogRef = useRef(null);

  const [wsConnected, setWsConnected] = useState(false);

  // 当前激活的 Tab
  const [activeTab, setActiveTab] = useState('DAILY');

  // ========== 指数数据完整性检查 ==========
  const [missingIndexDate, setMissingIndexDate] = useState(dayjs().subtract(1, 'day'));
  const [missingIndices, setMissingIndices] = useState([]);
  const [missingIndexLoading, setMissingIndexLoading] = useState(false);

  const handleCheckMissingIndices = async () => {
    setMissingIndexLoading(true);
    try {
      const dateStr = missingIndexDate.format('YYYY-MM-DD');
      const res = await dataUpdateApi.getMissingIndices(dateStr);
      setMissingIndices(res || []);
    } catch (e) {
      message.error('查询失败');
    } finally {
      setMissingIndexLoading(false);
    }
  };

  // ========== 分红数据完整性检查 ==========
  const [missingDividendStocks, setMissingDividendStocks] = useState([]);
  const [missingDividendLoading, setMissingDividendLoading] = useState(false);
  const [missingDividendStats, setMissingDividendStats] = useState(null);
  const [missingDividendMarket, setMissingDividendMarket] = useState('ALL');
  const [missingDividendPage, setMissingDividendPage] = useState(1);
  const [missingDividendPageSize, setMissingDividendPageSize] = useState(50);

  const handleCheckMissingDividend = async () => {
    setMissingDividendLoading(true);
    try {
      const [statsRes, stocksRes] = await Promise.all([
        dataUpdateApi.getMissingDividendStats(),
        dataUpdateApi.getMissingDividendStocks(missingDividendMarket, missingDividendPage, missingDividendPageSize),
      ]);
      setMissingDividendStats(statsRes);
      setMissingDividendStocks(stocksRes || []);
    } catch (e) {
      message.error('查询失败');
    } finally {
      setMissingDividendLoading(false);
    }
  };

  // ========== 股票数据完整性检查 ==========
  const [missingDate, setMissingDate] = useState(dayjs().subtract(1, 'day'));
  const [missingMarket, setMissingMarket] = useState('ALL');
  const [missingStocks, setMissingStocks] = useState([]);
  const [missingLoading, setMissingLoading] = useState(false);
  const [missingStats, setMissingStats] = useState(null);
  const [missingPageSize, setMissingPageSize] = useState(50);
  const [tradingDates, setTradingDates] = useState([]);

  const handleCheckMissing = async () => {
    setMissingLoading(true);
    try {
      const dateStr = missingDate.format('YYYY-MM-DD');
      const [missingRes, statsRes] = await Promise.all([
        dataUpdateApi.getMissingStocks(dateStr, missingMarket),
        dataUpdateApi.getMissingStats(dateStr),
      ]);
      setMissingStocks(missingRes || []);
      setMissingStats(statsRes);
    } catch (e) {
      message.error('查询失败');
    } finally {
      setMissingLoading(false);
    }
  };

  // 覆盖率数据
  const [coverage, setCoverage] = useState(null);
  const [coverageLoading, setCoverageLoading] = useState(true);
  const coverageFetchedRef = useRef(false);

  // 指数覆盖率
  const [indexCoverage, setIndexCoverage] = useState(null);
  const [indexCoverageLoading, setIndexCoverageLoading] = useState(true);
  const indexCoverageFetchedRef = useRef(false);

  // 分红覆盖率
  const [dividendCoverage, setDividendCoverage] = useState(null);
  const [dividendCoverageLoading, setDividendCoverageLoading] = useState(true);
  const dividendCoverageFetchedRef = useRef(false);

  // ========== 分流辅助 ==========
  const getTaskUpdater = useCallback((updateType) => {
    switch (updateType) {
      case 'INDEX': return setIndexTask;
      case 'DIVIDEND': return setDividendTask;
      default: return setDailyTask;
    }
  }, []);

  const getLogUpdater = useCallback((updateType) => {
    switch (updateType) {
      case 'INDEX': return setIndexLogs;
      case 'DIVIDEND': return setDividendLogs;
      default: return setDailyLogs;
    }
  }, []);

  // ========== WebSocket 连接 ==========
  const connectWs = useCallback(() => {
    if (wsRef.current) return;

    const ws = new WebSocket(WS_URL);
    wsRef.current = ws;

    const sendFrame = (cmd, headers = {}, body = '') => {
      let frame = cmd + '\n';
      Object.entries(headers).forEach(([k, v]) => { frame += k + ':' + v + '\n'; });
      frame += '\n' + body + '\x00';
      ws.send(frame);
    };

    ws.onopen = () => {
      setWsConnected(true);
      sendFrame('CONNECT', { 'accept-version': '1.2', host: window.location.host });
    };

    ws.onmessage = (evt) => {
      const data = evt.data;
      const nullIdx = data.indexOf('\x00');
      if (nullIdx === -1) return;
      const frameStr = data.substring(0, nullIdx);

      const lines = frameStr.split('\n');
      if (lines.length === 0) return;
      const command = lines[0].trim();
      const bodyStart = frameStr.indexOf('\n\n');
      const body = bodyStart >= 0 ? frameStr.substring(bodyStart + 2) : '';

      if (command === 'CONNECTED') {
        sendFrame('SUBSCRIBE', { id: 'data-update-status', destination: '/topic/data-update/status' });
        sendFrame('SUBSCRIBE', { id: 'data-update-log', destination: '/topic/data-update/log' });
      } else if (command === 'MESSAGE') {
        try {
          const msg = JSON.parse(body);
          if (msg.type === 'DATA_UPDATE_STATUS') {
            const ut = msg.updateType || 'DAILY';
            const updater = getTaskUpdater(ut);
            updater(prev => {
              const t = prev ? { ...prev } : {};
              // 只更新 msg 中有的字段
              if (msg.taskId !== undefined) t.taskId = msg.taskId;
              if (msg.status !== undefined) t.status = msg.status;
              if (msg.progress !== undefined) t.progress = msg.progress;
              if (msg.currentStep !== undefined) t.currentStep = msg.currentStep;
              if (msg.processedStocks !== undefined) t.processedStocks = msg.processedStocks;
              if (msg.totalStocks !== undefined) t.totalStocks = msg.totalStocks;
              if (msg.processedRecords !== undefined) t.processedRecords = msg.processedRecords;
              if (msg.failedStocks !== undefined) t.failedStocks = msg.failedStocks;
              if (msg.startTime !== undefined) t.startTime = msg.startTime;
              if (msg.endTime !== undefined) t.endTime = msg.endTime;
              if (msg.error !== undefined) t.error = msg.error;
              if (msg.updateType !== undefined) t.updateType = msg.updateType;
              return t;
            });
          } else if (msg.type === 'DATA_UPDATE_LOG') {
            // 日志根据 taskId 匹配到对应的 Tab
            const logEntry = {
              id: Date.now() + Math.random(),
              time: msg.time || dayjs().format('HH:mm:ss'),
              text: msg.line || '',
              taskId: msg.taskId,
            };
            // 尝试根据 taskId 或当前任务匹配
            const logUpdater = getLogUpdater(msg.updateType || 'DAILY');
            logUpdater(prev => [...prev.slice(-499), logEntry]);
          }
        } catch (e) { /* ignore parse errors */ }
      }
    };

    ws.onclose = () => { setWsConnected(false); wsRef.current = null; };
    ws.onerror = () => { setWsConnected(false); };
  }, [getTaskUpdater, getLogUpdater]);

  const fetchCoverage = useCallback(async () => {
    const isFirst = !coverageFetchedRef.current;
    if (isFirst) setCoverageLoading(true);
    try {
      const res = await dataUpdateApi.getCoverage();
      setCoverage(res);
      coverageFetchedRef.current = true;
    } catch (e) {
      console.error('fetchCoverage failed:', e);
    }
    if (isFirst) setCoverageLoading(false);
  }, []);

  const fetchIndexCoverage = useCallback(async () => {
    const isFirst = !indexCoverageFetchedRef.current;
    if (isFirst) setIndexCoverageLoading(true);
    try {
      const res = await dataUpdateApi.getIndexCoverage();
      setIndexCoverage(res);
      indexCoverageFetchedRef.current = true;
    } catch (e) {
      console.error('fetchIndexCoverage failed:', e);
    }
    if (isFirst) setIndexCoverageLoading(false);
  }, []);

  const fetchDividendCoverage = useCallback(async () => {
    const isFirst = !dividendCoverageFetchedRef.current;
    if (isFirst) setDividendCoverageLoading(true);
    try {
      const res = await dataUpdateApi.getDividendCoverage();
      setDividendCoverage(res);
      dividendCoverageFetchedRef.current = true;
    } catch (e) {
      console.error('fetchDividendCoverage failed:', e);
    }
    if (isFirst) setDividendCoverageLoading(false);
  }, []);

  // 任务运行中时自动刷新覆盖率（每10秒）
  useEffect(() => {
    if (dailyTask?.status === 'RUNNING') {
      const timer = setInterval(() => fetchCoverage(), 10000);
      return () => clearInterval(timer);
    }
  }, [dailyTask?.status, fetchCoverage]);

  useEffect(() => {
    if (indexTask?.status === 'RUNNING') {
      const timer = setInterval(() => fetchIndexCoverage(), 10000);
      return () => clearInterval(timer);
    }
  }, [indexTask?.status, fetchIndexCoverage]);

  useEffect(() => {
    if (dividendTask?.status === 'RUNNING') {
      const timer = setInterval(() => fetchDividendCoverage(), 10000);
      return () => clearInterval(timer);
    }
  }, [dividendTask?.status, fetchDividendCoverage]);

  useEffect(() => {
    connectWs();
    // 初始化时恢复各Tab最近的任务状态
    dataUpdateApi.getRecentTasks().then(res => {
      if (res) {
        for (const t of res) {
          const ut = t.updateType || t.request?.updateType || 'DAILY';
          t.updateType = ut;
          // 只恢复非 IDLE 状态
          if (t.status && t.status !== 'IDLE') {
            getTaskUpdater(ut)(t);
          }
        }
      }
    }).catch(() => {});
    fetchCoverage();
    fetchIndexCoverage();
    fetchDividendCoverage();
    dataUpdateApi.getTradingDates(30).then(res => setTradingDates(res || [])).catch(() => {});
    dataUpdateApi.getMissingStats(dayjs().subtract(1, 'day').format('YYYY-MM-DD'))
      .then(res => setMissingStats(res)).catch(() => {});
    dataUpdateApi.getDefaultDates().then(res => {
      if (res) {
        form.setFieldsValue({
          dateRange: [dayjs(res.startDate), dayjs(res.endDate)]
        });
      }
    }).catch(() => {});

    return () => { if (wsRef.current) wsRef.current.close(); };
  }, []);

  // 日志自动滚动
  useEffect(() => {
    if (dailyLogRef.current) dailyLogRef.current.scrollTop = dailyLogRef.current.scrollHeight;
  }, [dailyLogs]);
  useEffect(() => {
    if (indexLogRef.current) indexLogRef.current.scrollTop = indexLogRef.current.scrollHeight;
  }, [indexLogs]);
  useEffect(() => {
    if (dividendLogRef.current) dividendLogRef.current.scrollTop = dividendLogRef.current.scrollHeight;
  }, [dividendLogs]);

  // ========== 提交任务 ==========
  const handleSubmit = async (updateType) => {
    try {
      const currentForm = updateType === 'INDEX' ? indexForm
        : updateType === 'DIVIDEND' ? dividendForm : form;
      const values = await currentForm.validateFields();
      const dates = values.dateRange;
      const request = {
        updateType: updateType,
        source: values.source || 'ALL',
        market: values.market || 'ALL',
        startDate: dates ? dates[0].format('YYYY-MM-DD') : '',
        endDate: dates ? dates[1].format('YYYY-MM-DD') : '',
        dailyOnly: values.dailyOnly || false,
        infoOnly: values.infoOnly || false,
        resume: values.resume || false,
        excludeSt: values.excludeSt || false,
        stockPool: values.stockPool || 'ALL',
        limit: values.limit || null,
        batchSize: values.batchSize || null,
        delay: values.delay || null,
      };

      // 清空对应 Tab 的日志
      getLogUpdater(updateType)([]);
      // 重置对应 Tab 的任务状态
      getTaskUpdater(updateType)(null);

      const res = await dataUpdateApi.startTask(request);
      if (res) {
        const t = res;
        t.updateType = updateType;
        getTaskUpdater(updateType)(t);
        message.success('任务已启动');
      }
    } catch (e) {
      if (e.message && !e.message.includes('validateFields')) {
        message.error(e.message || '启动失败');
      }
    }
  };

  // ========== 取消任务 ==========
  const handleCancel = async (updateType) => {
    const task = updateType === 'INDEX' ? indexTask
      : updateType === 'DIVIDEND' ? dividendTask : dailyTask;
    if (!task || !task.taskId) return;
    try {
      await dataUpdateApi.cancelTask(task.taskId);
      message.info('任务已取消');
    } catch (e) {
      message.error('取消失败');
    }
  };

  const overview = coverage?.overview || {};
  const markets = coverage?.markets || [];

  // ========== Tab 切换 ==========
  const onTabChange = (key) => {
    setActiveTab(key);
    if (key === 'INDEX') fetchIndexCoverage();
    if (key === 'DIVIDEND') fetchDividendCoverage();
  };

  // ========== 股票日线 Tab ==========
  const renderDailyTab = () => {
    const isRunning = dailyTask?.status === 'RUNNING';
    return (
      <>
        <Card title="股票日线配置" size="small" style={{ marginBottom: 16 }}>
          <Form form={form} layout="inline" initialValues={{
            source: 'ALL', market: 'ALL', stockPool: 'ALL',
            excludeSt: false, resume: false, dailyOnly: false, infoOnly: false,
          }}>
            <Row gutter={[16, 12]} style={{ width: '100%' }}>
              <Col>
                <Form.Item name="source" label="数据源">
                  <Select options={SOURCE_OPTIONS} style={{ width: 220 }} />
                </Form.Item>
              </Col>
              <Col>
                <Form.Item name="market" label="市场">
                  <Select options={MARKET_OPTIONS} style={{ width: 140 }} />
                </Form.Item>
              </Col>
              <Col>
                <Form.Item name="stockPool" label="股票池">
                  <Select options={POOL_OPTIONS} style={{ width: 140 }} />
                </Form.Item>
              </Col>
              <Col>
                <Form.Item name="dateRange" label="日期范围" tooltip="不选则默认更新最近3天">
                  <RangePicker placeholder={['开始日期', '结束日期']} />
                </Form.Item>
              </Col>
            </Row>
            <Row gutter={[16, 12]} style={{ width: '100%', marginBottom: 12 }}>
              <Col>
                <Space size={16}>
                  <Form.Item name="excludeSt" valuePropName="checked" style={{ marginBottom: 0 }}>
                    <Checkbox>排除 ST/*ST</Checkbox>
                  </Form.Item>
                  <Form.Item name="dailyOnly" valuePropName="checked" style={{ marginBottom: 0 }}>
                    <Checkbox>仅更新日线</Checkbox>
                  </Form.Item>
                  <Form.Item name="infoOnly" valuePropName="checked" style={{ marginBottom: 0 }}>
                    <Checkbox>仅更新股票信息</Checkbox>
                  </Form.Item>
                  <Form.Item name="resume" valuePropName="checked" style={{ marginBottom: 0 }}>
                    <Checkbox>断点续传</Checkbox>
                  </Form.Item>
                </Space>
              </Col>
            </Row>
            <Row style={{ width: '100%' }}>
              <Col>
                <Space size={12}>
                  <Button type="primary" icon={<PlayCircleOutlined />}
                    onClick={() => handleSubmit('DAILY')} disabled={isRunning}>
                    开始更新
                  </Button>
                  <Button danger icon={<StopOutlined />}
                    onClick={() => handleCancel('DAILY')} disabled={!isRunning}>
                    取消任务
                  </Button>
                  {isRunning && <Tag color="processing">任务运行中...</Tag>}
                  {dailyTask && dailyTask.status === 'SUCCESS' && <Tag color="success">更新完成</Tag>}
                  {dailyTask && dailyTask.status === 'FAILED' && <Tag color="error">更新失败</Tag>}
                </Space>
              </Col>
            </Row>
          </Form>
        </Card>
        {/* 进度条 */}
        {renderProgressBar(dailyTask)}
        {/* 日志 - 始终显示 */}
        <Card title={<span>更新日志 <Text type="secondary" style={{ fontSize: 12 }}>({dailyLogs.length} 条)</Text></span>}
          size="small" extra={<Button size="small" onClick={() => setDailyLogs([])}>清空</Button>}>
          {renderLogs(dailyLogs, dailyLogRef)}
        </Card>
      </>
    );
  };

  // ========== 指数日线 Tab ==========
  const renderIndexTab = () => {
    const isRunning = indexTask?.status === 'RUNNING';
    return (
      <>
        {/* 指数数据概览 */}
        <Card
          title={<span><DatabaseOutlined /> 数据概览</span>}
          size="small" style={{ marginBottom: 16 }}
          loading={indexCoverageLoading}
          extra={<Button size="small" icon={<ReloadOutlined />} onClick={fetchIndexCoverage}>刷新</Button>}>
          <Row gutter={16}>
            <Col span={4}>
              <Statistic title="指数数量"
                value={indexCoverage?.indexCount || 0}
                suffix={`/ 10`}
                valueStyle={{ fontSize: 16, color: '#1677ff' }}
                prefix={<LineChartOutlined />} />
            </Col>
            <Col span={5}>
              <Statistic title="总记录数"
                formatter={() => {
                  const r = indexCoverage?.totalRecords || 0;
                  return <span>{r >= 10000 ? (r / 10000).toFixed(1) + ' 万' : (r || 0).toLocaleString()}</span>;
                }}
                suffix="条"
                valueStyle={{ fontSize: 16, color: '#52c41a' }} />
            </Col>
            <Col span={7}>
              <Statistic title="数据来源"
                formatter={() => <Text style={{ fontSize: 13 }}>Baostock</Text>}
                valueStyle={{ fontSize: 13 }} />
            </Col>
            {isRunning && (
              <Col span={8}>
                <Statistic title="状态" formatter={() => statusTag('RUNNING')}
                  valueStyle={{ fontSize: 14 }} />
              </Col>
            )}
          </Row>
        </Card>

        {/* 指数数据明细 */}
        {indexCoverage?.indices && indexCoverage.indices.length > 0 && (
          <Card title="各指数数据详情" size="small" style={{ marginBottom: 16 }}>
            <Table
              dataSource={indexCoverage.indices}
              columns={getIndexStatsColumns(indexCoverage.latestTradeDate)}
              rowKey="code"
              size="small"
              pagination={false}
              scroll={{ y: 240 }}
            />
          </Card>
        )}

        {/* 指数日线配置 */}
        <Card title="指数日线配置" size="small" style={{ marginBottom: 16 }}>
          <Form form={indexForm} layout="inline" initialValues={{ resume: false }}>
            <Row gutter={[16, 12]} style={{ width: '100%' }}>
              <Col>
                <Form.Item name="dateRange" label="日期范围" tooltip="不选则自动检测起始日期">
                  <RangePicker placeholder={['开始日期', '结束日期']} />
                </Form.Item>
              </Col>
              <Col>
                <span style={{ lineHeight: '32px', color: '#8c8c8c', fontSize: 13 }}>
                  <PieChartOutlined style={{ marginRight: 4 }} />
                  更新沪深300、上证50、中证500等 10 个主要指数日线数据
                </span>
              </Col>
            </Row>
            <Row gutter={[16, 12]} style={{ width: '100%', marginBottom: 12 }}>
              <Col>
                <Form.Item name="resume" valuePropName="checked" style={{ marginBottom: 0 }}>
                  <Checkbox>断点续传</Checkbox>
                </Form.Item>
              </Col>
            </Row>
            <Row style={{ width: '100%' }}>
              <Col>
                <Space size={12}>
                  <Button type="primary" icon={<PlayCircleOutlined />}
                    onClick={() => handleSubmit('INDEX')} disabled={isRunning}>
                    开始更新
                  </Button>
                  <Button danger icon={<StopOutlined />}
                    onClick={() => handleCancel('INDEX')} disabled={!isRunning}>
                    取消任务
                  </Button>
                  {isRunning && <Tag color="processing">任务运行中...</Tag>}
                  {indexTask && indexTask.status === 'SUCCESS' && <Tag color="success">更新完成</Tag>}
                  {indexTask && indexTask.status === 'FAILED' && <Tag color="error">更新失败</Tag>}
                </Space>
              </Col>
            </Row>
          </Form>
        </Card>
        {/* 进度条 */}
        {renderProgressBar(indexTask)}
        {/* 日志 - 始终显示 */}
        <Card title={<span>更新日志 <Text type="secondary" style={{ fontSize: 12 }}>({indexLogs.length} 条)</Text></span>}
          size="small" extra={<Button size="small" onClick={() => setIndexLogs([])}>清空</Button>}>
          {renderLogs(indexLogs, indexLogRef)}
        </Card>
        {/* 指数完整性检查 */}
        {renderIndexIntegrity()}
      </>
    );
  };

  // ========== 分红除权 Tab ==========
  const renderDividendTab = () => {
    const isRunning = dividendTask?.status === 'RUNNING';
    return (
      <>
        {/* 分红统计信息 */}
        <Card size="small" title="数据概览" style={{ marginBottom: 16 }}
          loading={dividendCoverageLoading}
          extra={<Button size="small" icon={<ReloadOutlined />} onClick={fetchDividendCoverage}>刷新</Button>}>
          <Row gutter={16}>
            <Col span={4}>
              <Statistic title="分红记录"
                formatter={() => {
                  const r = dividendCoverage?.totalRecords || 0;
                  return <span>{r >= 10000 ? (r / 10000).toFixed(1) + ' 万' : (r || 0).toLocaleString()}</span>;
                }}
                suffix="条"
                valueStyle={{ fontSize: 16, color: '#52c41a' }}
                prefix={<GiftOutlined />} />
            </Col>
            <Col span={5}>
              <Statistic title="覆盖股票"
                formatter={() => <span>{dividendCoverage?.coveredStocks || 0} / {dividendCoverage?.totalShSzStocks || 0}</span>}
                valueStyle={{ fontSize: 16, color: '#1677ff' }}
                prefix={<BarChartOutlined />} />
            </Col>
            <Col span={4}>
              <Statistic title="覆盖率"
                formatter={() => <span>{dividendCoverage?.coverageRate || 0}%</span>}
                valueStyle={{ fontSize: 16, color: (dividendCoverage?.coverageRate || 0) >= 90 ? '#52c41a' : '#fa8c16' }}
                suffix={<Progress percent={dividendCoverage?.coverageRate || 0} size="small" showInfo={false}
                  strokeColor={(dividendCoverage?.coverageRate || 0) >= 90 ? '#52c41a' : '#fa8c16'} />}
              />
            </Col>
            <Col span={5}>
              <Statistic title="时间范围"
                formatter={() => {
                  const min = dividendCoverage?.minDate;
                  const max = dividendCoverage?.maxDate;
                  if (!min || !max) return <span>--</span>;
                  return <span style={{ fontSize: 13 }}>{min} ~ {max}</span>;
                }}
                valueStyle={{ fontSize: 13 }}
                prefix={<CalendarOutlined />} />
            </Col>
            {isRunning && (
              <Col span={6}>
                <Statistic title="状态" formatter={() => statusTag('RUNNING')}
                  valueStyle={{ fontSize: 14 }} />
              </Col>
            )}
          </Row>
        </Card>

        {/* 分红除权配置 */}
        <Card title="分红除权配置" size="small" style={{ marginBottom: 16 }}>
          <Form form={dividendForm} layout="inline" initialValues={{ resume: false }}>
            <Row gutter={[16, 12]} style={{ width: '100%' }}>
              <Col>
                <span style={{ lineHeight: '32px', color: '#8c8c8c', fontSize: 13 }}>
                  <DollarOutlined style={{ marginRight: 4 }} />
                  采集沪深两市分红除权数据（Baostock），写入 stock_dividend 表
                </span>
              </Col>
            </Row>
            <Row gutter={[16, 12]} style={{ width: '100%', marginBottom: 12 }}>
              <Col>
                <Form.Item name="resume" valuePropName="checked" style={{ marginBottom: 0 }}>
                  <Checkbox>跳过已有数据</Checkbox>
                </Form.Item>
              </Col>
            </Row>
            <Row style={{ width: '100%' }}>
              <Col>
                <Space size={12}>
                  <Button type="primary" icon={<PlayCircleOutlined />}
                    onClick={() => handleSubmit('DIVIDEND')} disabled={isRunning}>
                    开始更新
                  </Button>
                  <Button danger icon={<StopOutlined />}
                    onClick={() => handleCancel('DIVIDEND')} disabled={!isRunning}>
                    取消任务
                  </Button>
                  {isRunning && <Tag color="processing">任务运行中...</Tag>}
                  {dividendTask && dividendTask.status === 'SUCCESS' && <Tag color="success">更新完成</Tag>}
                  {dividendTask && dividendTask.status === 'FAILED' && <Tag color="error">更新失败</Tag>}
                </Space>
              </Col>
            </Row>
          </Form>
        </Card>
        {/* 进度条 */}
        {renderProgressBar(dividendTask)}
        {/* 日志 - 始终显示 */}
        <Card title={<span>更新日志 <Text type="secondary" style={{ fontSize: 12 }}>({dividendLogs.length} 条)</Text></span>}
          size="small" extra={<Button size="small" onClick={() => setDividendLogs([])}>清空</Button>}>
          {renderLogs(dividendLogs, dividendLogRef)}
        </Card>
        {/* 分红完整性检查 */}
        {renderDividendIntegrity()}
      </>
    );
  };

  // ========== 分红完整性检查 ==========
  const renderDividendIntegrity = () => (
    <Card title={<span><SearchOutlined /> 分红数据完整性检查</span>} size="small" style={{ marginTop: 16 }}>
      <Row gutter={16} align="middle" style={{ marginBottom: 16 }}>
        <Col>
          <Text>市场:</Text>
          <Select value={missingDividendMarket} onChange={v => { setMissingDividendMarket(v); setMissingDividendPage(1); }}
            options={[
              { value: 'ALL', label: '全部 (SH+SZ)' },
              { value: 'SH', label: '沪市 (SH)' },
              { value: 'SZ', label: '深市 (SZ)' },
            ]}
            style={{ marginLeft: 8, width: 150 }} />
        </Col>
        <Col>
          <Button type="primary" icon={<SearchOutlined />}
            onClick={handleCheckMissingDividend} loading={missingDividendLoading}>
            查询缺少分红的股票
          </Button>
        </Col>
      </Row>

      {missingDividendStats && (
        <Row gutter={16} style={{ marginBottom: 16 }}>
          <Col span={6}>
            <Statistic title="覆盖股票" value={missingDividendStats.coveredStocks || 0}
              valueStyle={{ fontSize: 14, color: '#52c41a' }} />
          </Col>
          <Col span={6}>
            <Statistic title="沪市缺少" value={missingDividendStats.SH || 0}
              valueStyle={{ fontSize: 14, color: (missingDividendStats.SH || 0) > 0 ? '#ff4d4f' : '#52c41a' }}
              suffix="只" />
          </Col>
          <Col span={6}>
            <Statistic title="深市缺少" value={missingDividendStats.SZ || 0}
              valueStyle={{ fontSize: 14, color: (missingDividendStats.SZ || 0) > 0 ? '#ff4d4f' : '#52c41a' }}
              suffix="只" />
          </Col>
          <Col span={6}>
            <Statistic title="合计缺少" value={missingDividendStats.total || 0}
              valueStyle={{ fontSize: 14, color: (missingDividendStats.total || 0) > 0 ? '#fa8c16' : '#52c41a' }}
              suffix="只" />
          </Col>
        </Row>
      )}

      {missingDividendStocks.length > 0 && (
        <Alert message={`共 ${missingDividendStocks.length} 只股票缺少分红数据（当前页）`}
          type="warning" showIcon style={{ marginBottom: 12 }} />
      )}

      <Table dataSource={missingDividendStocks} rowKey="code" size="small"
        pagination={{
          current: missingDividendPage,
          pageSize: missingDividendPageSize,
          showSizeChanger: true,
          pageSizeOptions: ['20', '50', '100', '200'],
          showTotal: (total) => `共 ${total} 条`,
          onChange: (page, size) => {
            setMissingDividendPage(page);
            if (size !== missingDividendPageSize) {
              setMissingDividendPageSize(size);
              setMissingDividendPage(1);
            }
          },
        }}
        scroll={{ y: 300 }}
        loading={missingDividendLoading}
        columns={[
          { title: '代码', dataIndex: 'code', width: 100 },
          { title: '名称', dataIndex: 'name', width: 150 },
          { title: '市场', dataIndex: 'market', width: 80 },
        ]} />

      {missingDividendStocks.length === 0 && !missingDividendLoading && missingDividendStats && missingDividendStats.total === 0 && (
        <Alert message="沪深两市分红数据完整" type="success" showIcon />
      )}
    </Card>
  );

  // ========== 指数完整性检查 ==========
  const renderIndexIntegrity = () => (
    <Card title={<span><SearchOutlined /> 指数完整性检查</span>} size="small" style={{ marginTop: 16 }}>
      <Row gutter={16} align="middle" style={{ marginBottom: 16 }}>
        <Col>
          <Text>检查日期:</Text>
          <DatePicker value={missingIndexDate} onChange={d => setMissingIndexDate(d)}
            allowClear={false} style={{ marginLeft: 8, width: 140 }}
            disabledDate={d => !tradingDates.includes(d.format('YYYY-MM-DD'))} />
        </Col>
        <Col>
          <Button type="primary" icon={<SearchOutlined />}
            onClick={handleCheckMissingIndices} loading={missingIndexLoading}>
            查询缺失指数
          </Button>
        </Col>
      </Row>

      {missingIndices.length > 0 && (
        <>
          <Alert message={`共 ${missingIndices.length} 个指数缺失该日数据`}
            type="warning" showIcon style={{ marginBottom: 12 }} />
          <Table dataSource={missingIndices} rowKey="code" size="small"
            pagination={false}
            columns={[
              { title: '代码', dataIndex: 'code', width: 100 },
              { title: '名称', dataIndex: 'name', width: 150 },
            ]} />
        </>
      )}

      {missingIndices.length === 0 && !missingIndexLoading && (
        <Alert message="该日期指数数据完整，无缺失" type="success" showIcon />
      )}
    </Card>
  );

  // ========== 股票日线完整性检查 ==========
  const renderStockIntegrity = () => (
    <Card title={<span><SearchOutlined /> 数据完整性检查</span>} size="small" style={{ marginTop: 16 }}>
      <Row gutter={16} align="middle" style={{ marginBottom: 16 }}>
        <Col>
          <Text>检查日期:</Text>
          <DatePicker value={missingDate} onChange={d => setMissingDate(d)}
            allowClear={false} style={{ marginLeft: 8, width: 140 }}
            disabledDate={d => !tradingDates.includes(d.format('YYYY-MM-DD'))} />
        </Col>
        <Col>
          <Text>市场:</Text>
          <Select value={missingMarket} onChange={setMissingMarket}
            options={MARKET_OPTIONS} style={{ marginLeft: 8, width: 130 }} />
        </Col>
        <Col>
          <Button type="primary" icon={<SearchOutlined />}
            onClick={handleCheckMissing} loading={missingLoading}>
            查询缺失股票
          </Button>
        </Col>
      </Row>

      {missingStats && (
        <Row gutter={16} style={{ marginBottom: 16 }}>
          <Col span={6}>
            <Statistic title="检查日期" value={missingStats.date} valueStyle={{ fontSize: 14 }} />
          </Col>
          <Col span={6}>
            <Statistic title="沪市 (SH)" value={missingStats.SH || 0}
              valueStyle={{ fontSize: 14, color: (missingStats.SH || 0) > 0 ? '#ff4d4f' : '#52c41a' }}
              suffix="只缺失" />
          </Col>
          <Col span={6}>
            <Statistic title="深市 (SZ)" value={missingStats.SZ || 0}
              valueStyle={{ fontSize: 14, color: (missingStats.SZ || 0) > 0 ? '#ff4d4f' : '#52c41a' }}
              suffix="只缺失" />
          </Col>
          <Col span={6}>
            <Statistic title="北交所 (BJ)" value={missingStats.BJ || 0}
              valueStyle={{ fontSize: 14, color: (missingStats.BJ || 0) > 0 ? '#ff4d4f' : '#52c41a' }}
              suffix="只缺失" />
          </Col>
        </Row>
      )}

      {missingStocks.length > 0 && (
        <>
          <Alert message={`共 ${missingStocks.length} 只股票缺失该日数据`}
            type="warning" showIcon style={{ marginBottom: 12 }} />
          <Table dataSource={missingStocks} rowKey="code" size="small"
            pagination={{
              pageSize: missingPageSize,
              showSizeChanger: true,
              pageSizeOptions: ['20', '50', '100', '200'],
              showTotal: (total) => `共 ${total} 条`,
              onShowSizeChange: (_, size) => setMissingPageSize(size),
            }}
            scroll={{ y: 300 }} columns={[
              { title: '代码', dataIndex: 'code', width: 100 },
              { title: '名称', dataIndex: 'name', width: 150 },
              { title: '市场', dataIndex: 'market', width: 80 },
            ]} />
        </>
      )}

      {missingStocks.length === 0 && missingLoading === false && missingStats && missingStats.total === 0 && (
        <Alert message="该日期数据完整，无缺失股票" type="success" showIcon />
      )}
    </Card>
  );

  return (
    <div style={{ padding: '0 0 24px' }}>
      <Title level={4} style={{ marginBottom: 16 }}>
        <CloudSyncOutlined /> 数据更新管理
        <Badge status={wsConnected ? 'success' : 'error'}
          text={<Text type="secondary" style={{ fontSize: 13 }}>
            {wsConnected ? 'WebSocket 已连接' : 'WebSocket 未连接'}
          </Text>}
          style={{ marginLeft: 12 }} />
      </Title>

      {/* ==================== 更新配置 Tabs ==================== */}
      <Card size="small" style={{ marginBottom: 16 }} bodyStyle={{ padding: '12px 0 0' }}>
        <Tabs
          activeKey={activeTab}
          onChange={onTabChange}
          type="card"
          style={{ padding: '0 16px' }}
          items={[
            {
              key: 'DAILY',
              label: <span><RiseOutlined /> 股票日线</span>,
              children: (
                <div style={{ padding: '16px 0' }}>
                  {/* 数据概览 */}
                  <Card
                    title={<span><DatabaseOutlined /> 数据概览</span>}
                    size="small"
                    style={{ marginBottom: 16 }}
                    loading={coverageLoading}
                    extra={
                      <Button size="small" icon={<ReloadOutlined />} onClick={fetchCoverage}>
                        刷新
                      </Button>
                    }
                  >
                    <Row gutter={24}>
                      <Col span={4}>
                        <Statistic
                          title="股票总数"
                          value={overview.totalStocks}
                          suffix="只"
                          valueStyle={{ fontSize: 18, color: '#1677ff' }}
                          prefix={<BarChartOutlined />}
                        />
                      </Col>
                      <Col span={5}>
                        <Statistic
                          title="日线记录"
                          value={overview.totalDailyRecords}
                          formatter={(val) => val >= 10000 ? (val / 10000).toFixed(1) + ' 万' : (val || 0).toLocaleString()}
                          suffix="条"
                          valueStyle={{ fontSize: 18, color: '#52c41a' }}
                        />
                      </Col>
                      <Col span={7}>
                        <Statistic
                          title="最新交易日"
                          value={overview.latestTradeDate || '--'}
                          valueStyle={{ fontSize: 18 }}
                          prefix={<CalendarOutlined />}
                        />
                      </Col>
                      <Col span={8}>
                        <Statistic
                          title="数据范围"
                          value={overview.earliestTradeDate && overview.latestTradeDate
                            ? `${overview.earliestTradeDate} ~ ${overview.latestTradeDate}`
                            : '--'}
                          valueStyle={{ fontSize: 14, marginTop: 8 }}
                          prefix={<ClockCircleOutlined />}
                        />
                      </Col>
                    </Row>
                    <Row style={{ marginTop: 12 }}>
                      <Col>
                        <span style={{ fontSize: 13, color: '#8c8c8c', marginRight: 8 }}>最新日覆盖</span>
                        {markets.map(m => (
                          <Tooltip key={m.market} title={`${MARKET_NAMES[m.market]}：${m.infoCount}只中 ${m.latestDayCount}只有数据 (${m.latestDate})`}>
                            <Tag color={MARKET_COLORS[m.market]} style={{ fontSize: 12, marginRight: 4 }}>
                              {MARKET_NAMES[m.market]} {m.latestDayCount}/{m.infoCount}
                            </Tag>
                          </Tooltip>
                        ))}
                      </Col>
                    </Row>
                  </Card>
                  {/* 股票日线配置 + 进度 + 日志 */}
                  {renderDailyTab()}
                  {/* 股票数据完整性检查 */}
                  {renderStockIntegrity()}
                </div>
              ),
            },
            {
              key: 'INDEX',
              label: <span><PieChartOutlined /> 指数日线</span>,
              children: (
                <div style={{ padding: '16px 0' }}>
                  {renderIndexTab()}
                </div>
              ),
            },
            {
              key: 'DIVIDEND',
              label: <span><DollarOutlined /> 分红除权</span>,
              children: <div style={{ padding: '16px 0' }}>{renderDividendTab()}</div>,
            },
          ]}
        />
      </Card>
    </div>
  );
}

export default DataUpdate;
