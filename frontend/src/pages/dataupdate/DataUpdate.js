import React, { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import {
  Card, Row, Col, Statistic, Button, Input, Select, DatePicker, Form,
  Checkbox, Tag, Typography, Space, Alert, Table, Tooltip, Progress, Badge, Divider, Tabs, Spin, Modal, Popconfirm, Radio, Collapse
} from 'antd';
import { QuestionCircleOutlined } from '@ant-design/icons';
import { message } from '../../utils/messageUtil';
import {
  PlayCircleOutlined, StopOutlined, ReloadOutlined,
  ClockCircleOutlined, ThunderboltOutlined, WarningOutlined,
  FilterOutlined, SearchOutlined, CloudSyncOutlined,
  DatabaseOutlined, RiseOutlined, FallOutlined,
  CalendarOutlined, BarChartOutlined, PieChartOutlined, DollarOutlined,
  LineChartOutlined, GiftOutlined, FileTextOutlined, DeleteOutlined, ExclamationCircleOutlined,
  LockOutlined, CheckSquareOutlined, CloseSquareOutlined, FlagOutlined
} from '@ant-design/icons';
import dayjs from 'dayjs';
import { dataUpdateApi, financialApi, calendarApi, silentConfig } from '../../api/index';
import { useStompWebSocket } from '../../hooks/useStompWebSocket';

const { Text, Title } = Typography;
const { RangePicker } = DatePicker;

// 情绪数据校验表选项
const SENTIMENT_TABLE_OPTIONS = [
  { value: 'stock_sentiment_zt', label: '涨跌停池' },
  { value: 'stock_sentiment_moneyflow', label: '资金流向' },
  { value: 'stock_sentiment_notice', label: '公告' },
  { value: 'stock_sentiment_lhb', label: '龙虎榜' },
  { value: 'stock_sentiment_lhb_inst', label: '龙虎榜机构明细' },
  { value: 'stock_sentiment_margin', label: '融资融券' },
  { value: 'stock_sentiment_margin_detail', label: '融资融券明细' },
  { value: 'stock_sentiment_survey', label: '机构调研' },
  { value: 'stock_sentiment_block_trade', label: '大宗交易' },
  { value: 'stock_sentiment_activity', label: '市场活跃度' },
  { value: 'stock_fund_holder', label: '基金持仓' },
  { value: 'stock_shareholder', label: '股东人数' },
  { value: 'stock_news', label: '新闻' },
  { value: 'macro_bond_yield', label: '国债收益率' },
  { value: 'stock_consensus_estimate', label: '一致预期' },
  { value: 'stock_earnings_report', label: '业绩快报' },
];

const SENTIMENT_QUICK_DATE_OPTIONS = [
  { value: '7', label: '近7天' },
  { value: '30', label: '近30天' },
  { value: '90', label: '近90天' },
  { value: 'all', label: '全部' },
  { value: 'custom', label: '自定义' },
];

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

// ========== 通用：渲染任务配置标签 ==========
const renderTaskConfig = (task, updateType) => {
  if (!task || !task.status || task.status === 'IDLE') return null;

  const tags = [];

  // 市场/数据源
  if (updateType === 'DAILY') {
    const marketMap = { ALL: '全市场', SH: '沪市', SZ: '深市', BJ: '北交所' };
    const sourceMap = { ALL: '全部', BAOSTOCK: 'Baostock', TENCENT: '腾讯' };
    if (task.configMarket) tags.push(<Tag key="mkt" color="blue">{marketMap[task.configMarket] || task.configMarket}</Tag>);
    if (task.configSource && task.configSource !== 'ALL') tags.push(<Tag key="src" color="cyan">{sourceMap[task.configSource] || task.configSource}</Tag>);
    if (task.configStockPool && task.configStockPool !== 'ALL') {
      const poolMap = { SH300: '沪深300', SZ50: '上证50', ZZ500: '中证500', ZZ1000: '中证1000', STAR50: '科创板50' };
      tags.push(<Tag key="pool" color="purple">{poolMap[task.configStockPool] || task.configStockPool}</Tag>);
    }
    if (task.configResume) tags.push(<Tag key="resume" color="orange">断点续传</Tag>);
    if (task.configExcludeSt) tags.push(<Tag key="exclude" color="red">排除ST</Tag>);
    if (task.configDailyOnly) tags.push(<Tag key="daily" color="geekblue">仅日线</Tag>);
    if (task.configInfoOnly) tags.push(<Tag key="info" color="geekblue">仅股票信息</Tag>);
  } else if (updateType === 'INDEX') {
    if (task.configResume) tags.push(<Tag key="resume" color="orange">断点续传</Tag>);
  } else if (updateType === 'DIVIDEND') {
    if (task.configResume) tags.push(<Tag key="resume" color="orange">跳过已有</Tag>);
  } else if (updateType === 'FINANCIAL') {
    if (task.configYearStart) tags.push(<Tag key="year" color="blue">{task.configYearStart}~{task.configYearEnd || ''}</Tag>);
    if (task.configSingleCode) tags.push(<Tag key="single" color="geekblue">单只: {task.configSingleCode}</Tag>);
    if (task.configForce) tags.push(<Tag key="force" color="red">强制重新采集</Tag>);
  } else if (updateType === 'SENTIMENT') {
    if (task.configFetchLhb !== false) tags.push(<Tag key="lhb" color="blue">龙虎榜</Tag>);
    if (task.configFetchMargin !== false) tags.push(<Tag key="margin" color="green">融资融券</Tag>);
    if (task.configFetchSurvey !== false) tags.push(<Tag key="survey" color="cyan">机构调研</Tag>);
    if (task.configFetchBlockTrade !== false) tags.push(<Tag key="block" color="orange">大宗交易</Tag>);
    if (task.configFetchActivity !== false) tags.push(<Tag key="activity" color="purple">市场活跃度</Tag>);
    if (task.configFetchZtPool !== false) tags.push(<Tag key="ztpool" color="red">涨跌停池</Tag>);
    if (task.configFetchMoneyflow !== false) tags.push(<Tag key="moneyflow" color="magenta">资金流向</Tag>);
    if (task.configFetchNotice !== false) tags.push(<Tag key="notice" color="volcano">公告</Tag>);
    if (task.configFetchFundHolder !== false) tags.push(<Tag key="fund" color="gold">基金持仓</Tag>);
    if (task.configFetchShareholder !== false) tags.push(<Tag key="shareholder" color="lime">股东人数</Tag>);
    if (task.configFetchNews !== false) tags.push(<Tag key="news" color="cyan">新闻</Tag>);
    if (task.configFetchBondYield !== false) tags.push(<Tag key="bond" color="gold">国债收益率</Tag>);
    if (task.configFetchShenwanIndex !== false) tags.push(<Tag key="shenwan" color="geekblue">申万行业指数</Tag>);
    if (task.configFetchQvix !== false) tags.push(<Tag key="qvix" color="purple">QVIX恐慌指数</Tag>);
    if (task.configForce) tags.push(<Tag key="force" color="red">全量重刷</Tag>);
  } else if (updateType === 'RESEARCH') {
    if (task.configForce) tags.push(<Tag key="force" color="red">强制重新采集</Tag>);
    if (task.configSingleCode) tags.push(<Tag key="single" color="geekblue">单只: {task.configSingleCode}</Tag>);
  }

  // 日期范围
  if (task.configStartDate || task.configEndDate) {
    const dateStr = [task.configStartDate, task.configEndDate].filter(Boolean).join(' ~ ');
    tags.push(<Tag key="dates" color="default">{dateStr}</Tag>);
  }

  return tags.length > 0 ? <Space size={4} style={{ marginLeft: 8 }}>{tags}</Space> : null;
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
const renderLogs = (logs, logRef, taskStatus) => (
  <div ref={logRef}
    style={{
      height: 200, overflowY: 'auto', backgroundColor: '#1a1a2e',
      borderRadius: 6, padding: '8px 12px', fontFamily: 'Consolas, Monaco, monospace',
      fontSize: 12, lineHeight: '18px',
    }}>
    {logs.length === 0 ? (
      taskStatus === 'RUNNING' ? (
        <Text type="secondary" style={{ color: '#888' }}>
          <span style={{ animation: 'pulse 1.5s infinite' }}>●</span> 任务运行中，等待脚本输出日志...
          <br /><span style={{ color: '#555', fontSize: 11 }}>（财务数据采集可能需要较长时间才有输出）</span>
        </Text>
      ) : (
        <Text type="secondary" style={{ color: '#555' }}>等待任务启动...</Text>
      )
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

  // 三个 Tab 各自独立的任务状态和日志
  const [dailyTask, setDailyTask] = useState(null);
  const [dailyLogs, setDailyLogs] = useState([]);
  const dailyLogRef = useRef(null);

  const [indexTask, setIndexTask] = useState(null);
  const [indexLogs, setIndexLogs] = useState([]);
  const [indexForce, setIndexForce] = useState(false);
  const indexLogRef = useRef(null);

  const [dividendTask, setDividendTask] = useState(null);
  const [dividendLogs, setDividendLogs] = useState([]);
  const dividendLogRef = useRef(null);

  // 财务数据
  const [financialTask, setFinancialTask] = useState(null);
  const [financialLogs, setFinancialLogs] = useState([]);
  const financialLogRef = useRef(null);
  const [financialForm] = Form.useForm();
  const [financialValidateResult, setFinancialValidateResult] = useState(null);
  const [financialValidateLoading, setFinancialValidateLoading] = useState(false);
  const [financialCoverage, setFinancialCoverage] = useState(null);
  const [financialCoverageLoading, setFinancialCoverageLoading] = useState(true);
  const financialCoverageFetchedRef = useRef(false);

  // 情绪数据
  const [sentimentTask, setSentimentTask] = useState(null);
  const [sentimentLogs, setSentimentLogs] = useState([]);
  const sentimentLogRef = useRef(null);
  const [sentimentForm] = Form.useForm();
  const [sentimentCoverage, setSentimentCoverage] = useState(null);
  const [sentimentCoverageLoading, setSentimentCoverageLoading] = useState(true);
  const sentimentCoverageFetchedRef = useRef(false);
  const [sentimentValidateResult, setSentimentValidateResult] = useState(null);
  const [sentimentValidateLoading, setSentimentValidateLoading] = useState(false);
  const [sentimentValidateQuickDate, setSentimentValidateQuickDate] = useState('7');
  const [sentimentValidateDateRange, setSentimentValidateDateRange] = useState([
    dayjs().subtract(7, 'day'),
    dayjs(),
  ]);
  const [sentimentValidateTables, setSentimentValidateTables] = useState(SENTIMENT_TABLE_OPTIONS.map(o => o.value));
  const [sentimentTradingDates, setSentimentTradingDates] = useState([]);
  const [sentimentTradingDatesLoading, setSentimentTradingDatesLoading] = useState(false);
  const sentimentAutoValidatedRef = useRef(false);
  const handleSentimentValidateRef = useRef(null);
  const [sentimentMoneyflowSource, setSentimentMoneyflowSource] = useState('AKSHARE');

  // 内外盘数据
  const [bidaskTask, setBidaskTask] = useState(null);
  const [bidaskLogs, setBidaskLogs] = useState([]);
  const bidaskLogRef = useRef(null);
  const [bidaskCoverage, setBidaskCoverage] = useState(null);
  const [bidaskCoverageLoading, setBidaskCoverageLoading] = useState(true);
  const bidaskCoverageFetchedRef = useRef(false);

  // 研报数据
  const [researchTask, setResearchTask] = useState(null);
  const [researchLogs, setResearchLogs] = useState([]);
  const researchLogRef = useRef(null);
  const [researchForm] = Form.useForm();
  const [bidaskForm] = Form.useForm();
  const [researchCoverage, setResearchCoverage] = useState(null);
  const [researchCoverageLoading, setResearchCoverageLoading] = useState(true);
  const researchCoverageFetchedRef = useRef(false);
  const [researchValidateResult, setResearchValidateResult] = useState(null);
  const [researchValidateLoading, setResearchValidateLoading] = useState(false);

  // ========== 退市股票管理 ==========
  const [delistedStocks, setDelistedStocks] = useState([]);
  const [delistedLoading, setDelistedLoading] = useState(false);
  const [delistedMarking, setDelistedMarking] = useState(false);
  const [delistedCleaning, setDelistedCleaning] = useState(false);
  const [delistedCleanedCodes, setDelistedCleanedCodes] = useState([]);
  const [selectedDelistedKeys, setSelectedDelistedKeys] = useState([]);

  const fetchDelistedStocks = useCallback(async () => {
    setDelistedLoading(true);
    try {
      const res = await dataUpdateApi.getDelistedStocks(30);
      setDelistedStocks((res || []).map((s, i) => ({ ...s, key: i })));
    } catch (e) {
      message.error('查询退市股票失败');
    } finally {
      setDelistedLoading(false);
    }
  }, []);

  const handleMarkDelisted = async () => {
    setDelistedMarking(true);
    try {
      const res = await dataUpdateApi.markDelistedStocks();
      const candidateCount = res?.candidateCount || 0;
      const markedCount = res?.markedCount || 0;
      const stocks = res?.stocks || [];
      if (markedCount > 0) {
        message.success(`已标记 ${markedCount} 只退市股票，stock_info.delist_date 已更新（检测到 ${candidateCount} 只候选）`);
      } else if (candidateCount > 0) {
        message.info(`检测到 ${candidateCount} 只退市候选，但均无可确认退市日期，未标记。请手动确认或清理。`);
      } else {
        message.info('未发现新的退市股票需要标记');
      }
      // 刷新列表
      setDelistedStocks((stocks || []).map((s, i) => ({ ...s, key: i })));
      setSelectedDelistedKeys([]);
    } catch (e) {
      message.error('标记退市失败：' + (e?.response?.data?.message || e.message));
    } finally {
      setDelistedMarking(false);
    }
  };

  const handleCleanDelisted = (codes) => {
    Modal.confirm({
      title: '确认清理退市股票',
      icon: <ExclamationCircleOutlined />,
      content: (
        <div>
          <p>将清理以下 <strong>{codes.length}</strong> 只股票的全部数据：</p>
          <ul style={{ margin: '8px 0', paddingLeft: 20 }}>
            {codes.map(c => {
              const s = delistedStocks.find(x => x.code === c);
              return <li key={c}>{c} {s?.name || ''}</li>;
            })}
          </ul>
          <p style={{ color: '#ff4d4f' }}>此操作不可逆，将同时删除 stock_info、stock_daily、factor_value、moneyflow 中的相关数据。</p>
        </div>
      ),
      okText: '确认清理',
      okType: 'danger',
      cancelText: '取消',
      onOk: async () => {
        setDelistedCleaning(true);
        try {
          const res = await dataUpdateApi.cleanDelistedStocks(codes);
          setDelistedCleanedCodes(codes);
          message.success(`清理完成，共删除 ${(res?.totalDeleted || 0).toLocaleString()} 条数据`);
          // 刷新列表
          setTimeout(() => fetchDelistedStocks(), 2000);
        } catch (e) {
          message.error('清理失败：' + (e?.response?.data?.message || e.message));
        } finally {
          setDelistedCleaning(false);
        }
      },
    });
  };

  const delistedColumns = [
    { title: '股票代码', dataIndex: 'code', width: 100 },
    { title: '名称', dataIndex: 'name', width: 120,
      render: (v) => v?.includes('退') ? <Tag color="error">{v}</Tag> : <span>{v}</span> },
    { title: '市场', dataIndex: 'market', width: 70, align: 'center',
      render: (v) => <Tag color={MARKET_COLORS[v]}>{MARKET_NAMES[v]}</Tag> },
    { title: '来源', dataIndex: 'source', width: 90, align: 'center',
      render: (v) => {
        const map = { akshare: '官方数据', clickhouse: '数据缺失', name_pattern: '名称检测' };
        const color = v === 'akshare' ? 'red' : v === 'clickhouse' ? 'orange' : 'default';
        return <Tag color={color}>{map[v] || v}</Tag>;
      } },
    { title: '状态', dataIndex: 'marked', width: 80, align: 'center',
      render: (v) => v
        ? <Tag color="success">已标记</Tag>
        : <Tag color="warning">待处理</Tag> },
    { title: '退市日期', dataIndex: 'out_date', width: 120,
      render: (v) => v ? <Tag color="error">{v}</Tag> : '-' },
    { title: '最后交易日', dataIndex: 'max_date', width: 120 },
    { title: '停牌天数', dataIndex: 'days_inactive', width: 100, align: 'center',
      render: (v) => <Tag color={v >= 60 ? 'error' : v >= 30 ? 'warning' : 'processing'}>{v} 天</Tag> },
    { title: '日线数据', dataIndex: 'daily_rows', width: 100, align: 'right',
      render: (v) => v?.toLocaleString() },
    { title: '因子数据', dataIndex: 'factor_rows', width: 100, align: 'right',
      render: (v) => v?.toLocaleString() },
    { title: '资金流数据', dataIndex: 'moneyflow_rows', width: 100, align: 'right',
      render: (v) => (v || 0).toLocaleString() },
  ];

  const renderDelistedTab = () => (
    <div>
      <Alert
        message="退市股票管理：多源检测（akshare官方退市数据 + ClickHouse数据缺失 + 名称检测），自动更新 stock_info.delist_date 字段用于回测幸存者偏差过滤。"
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
      />
      <Table
        dataSource={delistedStocks}
        columns={delistedColumns}
        loading={delistedLoading}
        size="small"
        pagination={false}
        scroll={{ x: 'max-content' }}
        rowSelection={{
          type: 'checkbox',
          selectedRowKeys: selectedDelistedKeys,
          onChange: (keys) => setSelectedDelistedKeys(keys),
          getCheckboxProps: (record) => ({
            name: record.code,
          }),
        }}
        footer={() => {
          const selectedRows = delistedStocks.filter(s => selectedDelistedKeys.includes(s.key));
          return (
          <Space>
            <span>已选择 {selectedRows.length} 只股票</span>
            <Button
              type="primary"
              icon={<FlagOutlined />}
              loading={delistedMarking}
              onClick={handleMarkDelisted}
            >
              标记退市
            </Button>
            <Popconfirm
              title="确认清理选中的股票？"
              description="此操作将物理删除 stock_info、stock_daily、factor_value、moneyflow 中的相关数据，不可撤销。建议优先使用「标记退市」保留数据用于回测。"
              onConfirm={() => handleCleanDelisted(selectedRows.map(r => r.code))}
              okText="确认清理"
              cancelText="取消"
              okButtonProps={{ danger: true }}
              disabled={selectedRows.length === 0 || delistedCleaning}
            >
              <Button
                type="primary"
                danger
                icon={<DeleteOutlined />}
                loading={delistedCleaning}
                disabled={selectedRows.length === 0}
              >
                清理选中股票
              </Button>
            </Popconfirm>
            <Button icon={<ReloadOutlined />} onClick={fetchDelistedStocks} loading={delistedLoading}>
              刷新
            </Button>
          </Space>
          );
        }}
      />
    </div>
  );

  // 当前激活的 Tab（刷新后保持在原 Tab）
  const [activeTab, setActiveTab] = useState(
    () => localStorage.getItem('quant-dataupdate-active-tab') || 'DAILY'
  );

  // ========== 指数数据完整性检查 ==========
  const [missingIndexDate, setMissingIndexDate] = useState(dayjs().tz().subtract(1, 'day'));
  const [missingIndices, setMissingIndices] = useState([]);
  const [missingIndexLoading, setMissingIndexLoading] = useState(false);

  const handleCheckMissingIndices = async () => {
    setMissingIndexLoading(true);
    try {
      const dateStr = missingIndexDate.format('YYYY-MM-DD');
      const res = await dataUpdateApi.getMissingIndices(dateStr);
      setMissingIndices(res || []);
    } catch (e) {
      message.error('数据查询失败，请稍后重试');
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
      message.error('分红数据查询失败，请稍后重试');
    } finally {
      setMissingDividendLoading(false);
    }
  };

  // ========== 股票数据完整性检查 ==========
  const [missingDate, setMissingDate] = useState(null);
  const [missingMarket, setMissingMarket] = useState('ALL');
  const [missingStocks, setMissingStocks] = useState([]);
  const [missingLoading, setMissingLoading] = useState(false);
  const [missingStats, setMissingStats] = useState(null);
  const [missingPageSize, setMissingPageSize] = useState(50);

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
      message.error('缺失数据查询失败，请稍后重试');
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
      case 'FINANCIAL': return setFinancialTask;
      case 'SENTIMENT': return setSentimentTask;
      case 'BIDASK': return setBidaskTask;
      case 'RESEARCH': return setResearchTask;
      default: return setDailyTask;
    }
  }, []);

  const getLogUpdater = useCallback((updateType) => {
    switch (updateType) {
      case 'INDEX': return setIndexLogs;
      case 'DIVIDEND': return setDividendLogs;
      case 'FINANCIAL': return setFinancialLogs;
      case 'SENTIMENT': return setSentimentLogs;
      case 'BIDASK': return setBidaskLogs;
      case 'RESEARCH': return setResearchLogs;
      default: return setDailyLogs;
    }
  }, []);

  // ── 统一 WebSocket（useStompWebSocket Hook）──
  // 状态回调：根据 updateType 更新对应任务状态
  const handleUpdateStatus = useCallback((msg) => {
    const ut = msg.updateType || 'DAILY';
    const updater = getTaskUpdater(ut);
    updater(prev => {
      const t = prev ? { ...prev } : {};
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
      if (msg.bidAskStats !== undefined) t.bidAskStats = msg.bidAskStats;
      t.updateType = ut;
      if (msg.market !== undefined) t.configMarket = msg.market;
      if (msg.source !== undefined) t.configSource = msg.source;
      if (msg.startDate !== undefined) t.configStartDate = msg.startDate;
      if (msg.endDate !== undefined) t.configEndDate = msg.endDate;
      if (msg.resume !== undefined) t.configResume = msg.resume;
      if (msg.excludeSt !== undefined) t.configExcludeSt = msg.excludeSt;
      if (msg.dailyOnly !== undefined) t.configDailyOnly = msg.dailyOnly;
      if (msg.infoOnly !== undefined) t.configInfoOnly = msg.infoOnly;
      if (msg.force !== undefined) t.configForce = msg.force;
      if (msg.yearStart !== undefined) t.configYearStart = msg.yearStart;
      if (msg.yearEnd !== undefined) t.configYearEnd = msg.yearEnd;
      if (msg.stockPool !== undefined) t.configStockPool = msg.stockPool;
      if (msg.fetchLhb !== undefined) t.configFetchLhb = msg.fetchLhb;
      if (msg.fetchMargin !== undefined) t.configFetchMargin = msg.fetchMargin;
      if (msg.fetchSurvey !== undefined) t.configFetchSurvey = msg.fetchSurvey;
      if (msg.fetchBlockTrade !== undefined) t.configFetchBlockTrade = msg.fetchBlockTrade;
      if (msg.fetchActivity !== undefined) t.configFetchActivity = msg.fetchActivity;
      if (msg.fetchZtPool !== undefined) t.configFetchZtPool = msg.fetchZtPool;
      if (msg.fetchMoneyflow !== undefined) t.configFetchMoneyflow = msg.fetchMoneyflow;
      if (msg.fetchNotice !== undefined) t.configFetchNotice = msg.fetchNotice;
      if (msg.fetchFundHolder !== undefined) t.configFetchFundHolder = msg.fetchFundHolder;
      if (msg.fetchShareholder !== undefined) t.configFetchShareholder = msg.fetchShareholder;
      if (msg.fetchNews !== undefined) t.configFetchNews = msg.fetchNews;
      if (msg.fetchBondYield !== undefined) t.configFetchBondYield = msg.fetchBondYield;
      if (msg.fetchShenwanIndex !== undefined) t.configFetchShenwanIndex = msg.fetchShenwanIndex;
      if (msg.fetchQvix !== undefined) t.configFetchQvix = msg.fetchQvix;
      if (msg.moneyflowSource !== undefined) t.configMoneyflowSource = msg.moneyflowSource;
      if (msg.emMoneyflowMode !== undefined) t.configEmMoneyflowMode = msg.emMoneyflowMode;
      if (msg.singleCode !== undefined) t.configSingleCode = msg.singleCode;
      if (msg.force !== undefined) t.configForce = msg.force;
      return t;
    });
  }, [getTaskUpdater]);

  // 日志回调：根据 updateType 推送到对应日志数组
  const handleUpdateLog = useCallback((msg) => {
    const ut = msg.updateType || 'DAILY';
    const logEntry = {
      id: Date.now() + Math.random(),
      time: msg.time || dayjs().format('HH:mm:ss'),
      text: msg.line || '',
      taskId: msg.taskId,
    };
    const logUpdater = getLogUpdater(ut);
    logUpdater(prev => [...prev.slice(-499), logEntry]);
  }, [getLogUpdater]);

  const { connected: wsConnected } = useStompWebSocket({
    subscriptions: useMemo(() => ({
      '/topic/data-update/status': handleUpdateStatus,
      '/topic/data-update/log': handleUpdateLog,
    }), [handleUpdateStatus, handleUpdateLog]),
    reconnectDelay: 3000,
  });

  // ========== 日志自动滚动 ==========
  useEffect(() => {
    if (dailyLogRef.current) dailyLogRef.current.scrollTop = dailyLogRef.current.scrollHeight;
  }, [dailyLogs]);

  useEffect(() => {
    if (indexLogRef.current) indexLogRef.current.scrollTop = indexLogRef.current.scrollHeight;
  }, [indexLogs]);

  useEffect(() => {
    if (dividendLogRef.current) dividendLogRef.current.scrollTop = dividendLogRef.current.scrollHeight;
  }, [dividendLogs]);

  useEffect(() => {
    if (financialLogRef.current) financialLogRef.current.scrollTop = financialLogRef.current.scrollHeight;
  }, [financialLogs]);

  // 财务任务运行中但日志为空时，自动定期补拉（解决定时任务触发后无日志的问题）
  useEffect(() => {
    if (financialTask?.status !== 'RUNNING' || financialLogs.length > 0) return;
    const timer = setInterval(async () => {
      if (!financialTask?.taskId) return;
      try {
        const logs = await dataUpdateApi.getTaskLogs(financialTask.taskId);
        if (logs && logs.length > 0) {
          setFinancialLogs(prev => {
            const existingTexts = new Set(prev.map(l => l.text));
            const newEntries = logs
              .filter(l => !existingTexts.has(l.line))
              .map(l => ({ id: Date.now() + Math.random(), time: l.time || '', text: l.line || '' }));
            return newEntries.length > 0 ? [...prev, ...newEntries] : prev;
          });
        }
      } catch (_) {}
    }, 10000);
    return () => clearInterval(timer);
  }, [financialTask?.status, financialTask?.taskId, financialLogs.length]);

  useEffect(() => {
    if (sentimentLogRef.current) sentimentLogRef.current.scrollTop = sentimentLogRef.current.scrollHeight;
  }, [sentimentLogs]);

  useEffect(() => {
    if (bidaskLogRef.current) bidaskLogRef.current.scrollTop = bidaskLogRef.current.scrollHeight;
  }, [bidaskLogs]);

  useEffect(() => {
    if (researchLogRef.current) researchLogRef.current.scrollTop = researchLogRef.current.scrollHeight;
  }, [researchLogs]);

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

  // 财务数据概览
  const fetchFinancialCoverage = useCallback(async () => {
    const isFirst = !financialCoverageFetchedRef.current;
    if (isFirst) setFinancialCoverageLoading(true);
    try {
      const res = await financialApi.getProgress();
      setFinancialCoverage(res);
      financialCoverageFetchedRef.current = true;
    } catch (e) {
      console.error('fetchFinancialCoverage failed:', e);
    }
    if (isFirst) setFinancialCoverageLoading(false);
  }, []);

  // 情绪数据概览
  const fetchSentimentCoverage = useCallback(async () => {
    const isFirst = !sentimentCoverageFetchedRef.current;
    if (isFirst) setSentimentCoverageLoading(true);
    try {
      const res = await dataUpdateApi.getSentimentCoverage();
      setSentimentCoverage(res);
      sentimentCoverageFetchedRef.current = true;
    } catch (e) {
      console.error('fetchSentimentCoverage failed:', e);
    }
    if (isFirst) setSentimentCoverageLoading(false);
  }, []);

  // 研报数据概览
  const fetchResearchCoverage = useCallback(async () => {
    const isFirst = !researchCoverageFetchedRef.current;
    if (isFirst) setResearchCoverageLoading(true);
    try {
      const res = await dataUpdateApi.getResearchCoverage();
      setResearchCoverage(res);
      researchCoverageFetchedRef.current = true;
    } catch (e) {
      console.error('fetchResearchCoverage failed:', e);
    }
    if (isFirst) setResearchCoverageLoading(false);
  }, []);

  // 研报数据校验
  const handleResearchValidate = async () => {
    setResearchValidateLoading(true);
    setResearchValidateResult(null);
    try {
      const res = await dataUpdateApi.validateResearch();
      if (res && Object.keys(res).length > 0) {
        setResearchValidateResult(res);
      } else {
        message.warning('校验结果为空');
      }
    } catch (e) {
      const msg = e.response?.data?.message || e.message || '校验失败';
      message.error(`校验失败: ${msg}`);
    } finally {
      setResearchValidateLoading(false);
    }
  };

  // 情绪任务运行时自动刷新概览（每10秒）
  useEffect(() => {
    if (sentimentTask?.status === 'RUNNING') {
      const timer = setInterval(() => fetchSentimentCoverage(), 10000);
      return () => clearInterval(timer);
    }
  }, [sentimentTask?.status, fetchSentimentCoverage]);

  // 内外盘数据概览
  const fetchBidaskCoverage = useCallback(async () => {
    const isFirst = !bidaskCoverageFetchedRef.current;
    if (isFirst) setBidaskCoverageLoading(true);
    try {
      const res = await dataUpdateApi.getBidaskCoverage();
      setBidaskCoverage(res);
      bidaskCoverageFetchedRef.current = true;
    } catch (e) {
      console.error('fetchBidaskCoverage failed:', e);
    }
    if (isFirst) setBidaskCoverageLoading(false);
  }, []);

  // 财务任务运行时自动刷新概览（每10秒）
  useEffect(() => {
    if (financialTask?.status === 'RUNNING') {
      const timer = setInterval(() => fetchFinancialCoverage(), 10000);
      return () => clearInterval(timer);
    }
  }, [financialTask?.status, fetchFinancialCoverage]);

  // 研报任务运行时自动刷新概览（每10秒）
  useEffect(() => {
    if (researchTask?.status === 'RUNNING') {
      const timer = setInterval(() => fetchResearchCoverage(), 10000);
      return () => clearInterval(timer);
    }
  }, [researchTask?.status, fetchResearchCoverage]);

  // 任务运行中时自动刷新覆盖率（每10秒）
  useEffect(() => {
    if (dailyTask?.status === 'RUNNING') {
      const timer = setInterval(() => fetchCoverage(), 10000);
      return () => clearInterval(timer);
    }
  }, [dailyTask?.status, fetchCoverage]);

  // 判断今天是否为潜在交易日（工作日，排除周末）
  // 调休/补班遇周末仍为非交易日，节假日例外日由后端 trade_calendar 提供
  const isPotentialTradingDay = () => {
    const day = dayjs().day(); // 0=周日, 6=周六
    return day >= 1 && day <= 5;
  };

  // 默认日期：18:00 后+工作日→今天，其他→前一个交易日（基于日历，非数据库）
  useEffect(() => {
    const now = dayjs();
    const after18 = now.hour() >= 18;

    let targetDate;
    if (after18 && isPotentialTradingDay()) {
      targetDate = now;
    } else {
      // 找今天之前最近的一个工作日（周一~周五）
      let d = now;
      while (true) {
        d = d.subtract(1, 'day');
        const dow = d.day(); // 0=周日, 1=周一, ..., 6=周六
        if (dow >= 1 && dow <= 5) break;
      }
      targetDate = d;
    }

    setMissingDate(targetDate);
    setMissingIndexDate(targetDate);
  }, []);

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
    // 初始化时恢复各Tab最近的任务状态 + 历史日志
    dataUpdateApi.getRecentTasks().then(res => {
      if (res && Array.isArray(res)) {
        for (const t of res) {
          // 从 request 对象中获取 updateType，默认为 DAILY
          const ut = t.request?.updateType || 'DAILY';
          // 只恢复非 IDLE 状态且非 CANCELLED 状态的任务
          if (t.status && t.status !== 'IDLE' && t.status !== 'CANCELLED') {
            getTaskUpdater(ut)(prev => ({ ...prev, ...t, updateType: ut }));
            // 同时补拉历史日志（刷新页面后日志丢失的根本原因）
            if (t.taskId) {
              dataUpdateApi.getTaskLogs(t.taskId).then(logs => {
                const logUpdater = getLogUpdater(ut);
                if (logs && logs.length > 0) {
                  logUpdater(prev => {
                    // 去重：按 text 内容去重，避免和实时推送重复
                    const existingTexts = new Set(prev.map(l => l.text));
                    const newEntries = logs
                      .filter(l => !existingTexts.has(l.line))
                      .map(l => ({ id: Date.now() + Math.random(), time: l.time || '', text: l.line || '' }));
                    return newEntries.length > 0 ? [...prev, ...newEntries] : prev;
                  });
                } else if (t.status === 'RUNNING') {
                  // 后端重启后日志缓存清空，给用户一个提示
                  logUpdater(prev => [...prev, {
                    id: Date.now(),
                    time: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
                    text: `[系统] 任务正在运行(后端重启后日志缓存已清空，后续日志会实时推送)`,
                  }]);
                }
              }).catch(() => {});
            }
          }
        }
      }
    }).catch(() => {});

    // 检测 DB 中孤儿 RUNNING 定时任务（进程已死但状态卡在 RUNNING）
    dataUpdateApi.getScheduledRunningTasks().then(orphanTasks => {
      if (orphanTasks && Array.isArray(orphanTasks) && orphanTasks.length > 0) {
        for (const ot of orphanTasks) {
          const taskKey = ot.taskKey; // DAILY, FINANCIAL 等
          const updater = getTaskUpdater(taskKey);
          updater(prev => prev?.status === 'RUNNING' ? prev : { // 不覆盖内存中已有的运行中任务
            taskId: 'ORPHAN-' + taskKey,
            status: 'RUNNING',
            currentStep: `[孤儿] ${ot.name || taskKey} 于 ${ot.lastRunTime} 启动，进程可能已退出`,
            progress: null,
            processedStocks: 0,
            totalStocks: 0,
            startTime: ot.lastRunTime,
            error: null,
            isOrphan: true,
            updateType: taskKey,
          });
          // 给对应 tab 的日志区加一条提示
          const logUpdater = getLogUpdater(taskKey);
          logUpdater(prev => [...prev, {
            id: Date.now(),
            time: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
            text: `[警告] 检测到孤儿运行任务: ${ot.name || taskKey}，启动时间 ${ot.lastRunTime}。该任务的执行进程可能已异常退出（后端重启/进程被杀），DB 状态未正确清理。如确认任务已结束，可点击"取消"清理状态。`,
          }]);
        }
      }
    }).catch(() => {});

    fetchCoverage();
    fetchIndexCoverage();
    fetchDividendCoverage();
    fetchFinancialCoverage();
    fetchSentimentCoverage();
    fetchResearchCoverage();
    dataUpdateApi.getMissingStats(dayjs().tz().subtract(1, 'day').format('YYYY-MM-DD'))
      .then(res => setMissingStats(res)).catch(() => {});
    dataUpdateApi.getDefaultDates().then(res => {
      if (res) {
        form.setFieldsValue({
          dateRange: [dayjs(res.startDate), dayjs(res.endDate)]
        });
        // 指数日线也用同一默认值
        indexForm.setFieldsValue({
          dateRange: [dayjs(res.startDate), dayjs(res.endDate)]
        });
      }
    }).catch(() => {});
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
  useEffect(() => {
    if (financialLogRef.current) financialLogRef.current.scrollTop = financialLogRef.current.scrollHeight;
  }, [financialLogs]);
  useEffect(() => {
    if (sentimentLogRef.current) sentimentLogRef.current.scrollTop = sentimentLogRef.current.scrollHeight;
  }, [sentimentLogs]);
  useEffect(() => {
    if (bidaskLogRef.current) bidaskLogRef.current.scrollTop = bidaskLogRef.current.scrollHeight;
  }, [bidaskLogs]);

  // ========== 提交任务 ==========
  const handleSubmit = async (updateType) => {
    // 检查是否已有其它任务在运行
    const runningTask = [dailyTask, indexTask, dividendTask, financialTask, sentimentTask, bidaskTask, researchTask]
      .find(t => t?.status === 'RUNNING');
    if (runningTask) {
      message.warning('已有数据更新任务正在运行，请等待完成后再启动新任务');
      return;
    }

    try {
      const currentForm = updateType === 'INDEX' ? indexForm
        : updateType === 'DIVIDEND' ? dividendForm
        : updateType === 'FINANCIAL' ? financialForm
        : updateType === 'SENTIMENT' ? sentimentForm
        : updateType === 'RESEARCH' ? researchForm
        : updateType === 'BIDASK' ? bidaskForm : form;
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
        yearStart: values.yearStart || null,
        yearEnd: values.yearEnd || null,
        singleCode: (values.singleCode || '').trim() || null,
        force: values.force || false,
        // 情绪数据专属字段
        ...(updateType === 'SENTIMENT' ? {
          ...(sentimentMoneyflowSource !== 'WESTOCK' ? {
            fetchLhb: values.fetchLhb !== false,
            fetchMargin: values.fetchMargin !== false,
            fetchSurvey: values.fetchSurvey !== false,
            fetchBlockTrade: values.fetchBlockTrade !== false,
            fetchActivity: values.fetchActivity !== false,
            fetchZtPool: values.fetchZtPool !== false,
            fetchMoneyflow: values.fetchMoneyflow !== false,
            fetchNotice: values.fetchNotice !== false,
            fetchFundHolder: values.fetchFundHolder !== false,
            fetchShareholder: values.fetchShareholder !== false,
            fetchNews: values.fetchNews !== false,
            fetchBondYield: values.fetchBondYield !== false,
            fetchShenwanIndex: values.fetchShenwanIndex !== false,
            fetchConsensusEstimate: values.fetchConsensusEstimate !== false,
            fetchEarningsReport: values.fetchEarningsReport !== false,
            fetchQvix: values.fetchQvix !== false,
          } : {
            // westock 模式：显式关闭其他模块，防止 Java 默认值 true 导致误执行
            fetchLhb: false,
            fetchMargin: false,
            fetchSurvey: false,
            fetchBlockTrade: false,
            fetchActivity: false,
            fetchZtPool: false,
            fetchMoneyflow: true,
            fetchNotice: false,
            fetchFundHolder: false,
            fetchShareholder: false,
            fetchNews: false,
            fetchBondYield: false,
            fetchShenwanIndex: false,
            fetchConsensusEstimate: false,
            fetchEarningsReport: false,
            fetchQvix: false,
          }),
          moneyflowSource: sentimentMoneyflowSource,
          sentimentCodes: (values.sentimentCodes || '').trim() || null,
        } : {}),
      };

      // 清空对应 Tab 的日志
      getLogUpdater(updateType)([]);
      // 重置对应 Tab 的任务状态
      getTaskUpdater(updateType)(null);

      const res = await dataUpdateApi.startTask(request);
      if (res) {
        const t = { ...res, updateType, currentStep: '启动中...' };
        getTaskUpdater(updateType)(t);
        getLogUpdater(updateType)(prev => [...prev, {
          id: Date.now(),
          time: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
          text: '[任务已启动] taskId=' + t.taskId,
        }]);
        message.success('任务已启动');
        // 兜底轮询：等 6 秒后若状态还是 RUNNING，拉一次最新状态+历史日志
        setTimeout(async () => {
          try {
            // 补拉历史日志（WS 可能断连或来不及收）
            const logs = await dataUpdateApi.getTaskLogs(t.taskId);
            if (logs && logs.length > 0) {
              getLogUpdater(updateType)(prev => {
                const existingIds = new Set(prev.map(l => l.text));
                const newLogs = logs
                  .filter(l => !existingIds.has(l.line))
                  .map(l => ({ id: Date.now() + Math.random(), time: l.time || '', text: l.line || '' }));
                return newLogs.length > 0 ? [...prev, ...newLogs] : prev;
              });
            }
            // 同步状态
            const recent = await dataUpdateApi.getRecentTasks();
            if (recent && Array.isArray(recent)) {
              const found = recent.find(r => (r.request?.updateType || 'DAILY') === updateType);
              if (found && found.taskId === t.taskId) {
                getTaskUpdater(updateType)(prev => {
                  if (prev && prev.status === 'RUNNING') {
                    return { ...prev, ...found, updateType };
                  }
                  return prev;
                });
              }
            }
          } catch (_) {}
        }, 6000);
      }
    } catch (e) {
      if (e.message && !e.message.includes('validateFields')) {
        message.error('任务启动失败，请稍后重试');
      }
    }
  };

  // ========== 取消任务 ==========
  const handleCancel = async (updateType) => {
    const task = updateType === 'INDEX' ? indexTask
      : updateType === 'DIVIDEND' ? dividendTask
      : updateType === 'FINANCIAL' ? financialTask
      : updateType === 'SENTIMENT' ? sentimentTask
      : updateType === 'RESEARCH' ? researchTask
      : updateType === 'BIDASK' ? bidaskTask
      : dailyTask;
    if (!task || !task.taskId) return;
    try {
      // 孤儿任务：taskId 以 'ORPHAN-' 开头，直接清理 DB 状态
      if (task.taskId.startsWith('ORPHAN-')) {
        await dataUpdateApi.cancelOrphanTask(updateType);
        message.info('已清理孤儿任务状态');
        getTaskUpdater(updateType)(prev => prev ? { ...prev, status: 'INTERRUPTED', endTime: new Date().toISOString() } : prev);
        return;
      }
      await dataUpdateApi.cancelTask(task.taskId);
      message.info('任务已取消');
      // 立即更新本地状态，防止 WS 推送延迟导致按钮卡住
      getTaskUpdater(updateType)(prev => prev ? { ...prev, status: 'CANCELLED', endTime: new Date().toISOString() } : prev);
    } catch (e) {
      message.error('取消任务失败，请稍后重试');
    }
  };

  // ========== 财务数据校验 ==========
  const handleFinancialValidate = async () => {
    setFinancialValidateLoading(true);
    setFinancialValidateResult(null);
    try {
      // 拦截器已解包 ApiResponse，res 直接是后端返回的 result Map
      const res = await financialApi.validate();
      if (res && Object.keys(res).length > 0) {
        setFinancialValidateResult(res);
      } else {
        message.warning('校验结果为空');
      }
    } catch (e) {
      message.error('财务数据校验失败，请稍后重试');
    } finally {
      setFinancialValidateLoading(false);
    }
  };

  // ========== 情绪数据校验 ==========
  const handleSentimentQuickDateChange = (value) => {
    setSentimentValidateQuickDate(value);
    if (value === 'custom') {
      return;
    }
    if (value === 'all') {
      if (sentimentTradingDates.length > 0) {
        setSentimentValidateDateRange([dayjs(sentimentTradingDates[0]), dayjs(sentimentTradingDates[sentimentTradingDates.length - 1])]);
      }
      return;
    }
    const count = parseInt(value, 10);
    if (sentimentTradingDates.length >= count) {
      const slice = sentimentTradingDates.slice(-count);
      setSentimentValidateDateRange([dayjs(slice[0]), dayjs(slice[slice.length - 1])]);
    }
  };

  const isTradingDay = (date) => {
    if (!date || !date.isValid()) return true;
    return sentimentTradingDates.includes(date.format('YYYY-MM-DD'));
  };

  const handleSentimentValidate = async () => {
    setSentimentValidateLoading(true);
    setSentimentValidateResult(null);
    try {
      const [start, end] = sentimentValidateDateRange || [];
      const params = {};
      if (start && start.isValid()) params.startDate = start.format('YYYY-MM-DD');
      if (end && end.isValid()) params.endDate = end.format('YYYY-MM-DD');
      if (sentimentValidateTables && sentimentValidateTables.length > 0) {
        params.tables = sentimentValidateTables.join(',');
      }
      const res = await dataUpdateApi.validateSentimentDaily(params);
      if (res && Object.keys(res).length > 0) {
        setSentimentValidateResult(res);
      } else {
        message.warning('校验结果为空');
      }
    } catch (e) {
      message.error('情绪数据校验失败，请稍后重试');
    } finally {
      setSentimentValidateLoading(false);
    }
  };
  handleSentimentValidateRef.current = handleSentimentValidate;

  const overview = coverage?.overview || {};
  const markets = coverage?.markets || [];

  // ========== Tab 切换 ==========
  // 页面初始化时根据当前 tab 加载数据
  useEffect(() => {
    if (activeTab === 'DELISTED') fetchDelistedStocks();
    if (activeTab === 'BIDASK') fetchBidaskCoverage();
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // 加载交易日历，并默认最近7个交易日
  useEffect(() => {
    if (activeTab !== 'SENTIMENT') return;
    const loadTradingDates = async () => {
      setSentimentTradingDatesLoading(true);
      try {
        const end = dayjs().add(1, 'year');
        const start = dayjs().subtract(3, 'year');
        const res = await calendarApi.getTradingDatesBetween(start.format('YYYY-MM-DD'), end.format('YYYY-MM-DD'));
        const dates = (res?.dates || []).map(d => d.toString());
        setSentimentTradingDates(dates);
        if (dates.length > 0 && !sentimentValidateDateRange?.[0]) {
          const last7 = dates.slice(-7);
          if (last7.length >= 2) {
            setSentimentValidateDateRange([dayjs(last7[0]), dayjs(last7[last7.length - 1])]);
          } else if (last7.length === 1) {
            setSentimentValidateDateRange([dayjs(last7[0]), dayjs(last7[0])]);
          }
        }
      } catch (e) {
        console.warn('加载交易日历失败', e);
      } finally {
        setSentimentTradingDatesLoading(false);
      }
    };
    loadTradingDates();
  }, [activeTab]); // eslint-disable-line react-hooks/exhaustive-deps

  // 情绪数据校验面板默认自动加载一次
  useEffect(() => {
    if (activeTab === 'SENTIMENT' && sentimentTradingDates.length > 0 && !sentimentAutoValidatedRef.current && handleSentimentValidateRef.current) {
      sentimentAutoValidatedRef.current = true;
      handleSentimentValidateRef.current();
    }
  }, [activeTab, sentimentTradingDates]);

  const onTabChange = (key) => {
    setActiveTab(key);
    localStorage.setItem('quant-dataupdate-active-tab', key);
    if (key === 'INDEX') fetchIndexCoverage();
    if (key === 'DIVIDEND') fetchDividendCoverage();
    if (key === 'FINANCIAL') fetchFinancialCoverage();
    if (key === 'SENTIMENT') fetchSentimentCoverage();
    if (key === 'RESEARCH') fetchResearchCoverage();
    if (key === 'BIDASK') fetchBidaskCoverage();
    if (key === 'DELISTED') fetchDelistedStocks();
  };

  // ========== 股票日线 Tab ==========
  const renderDailyTab = () => {
    const isRunning = dailyTask?.status === 'RUNNING';
    return (
      <>
        <Card title="股票日线配置" size="small" style={{ marginBottom: 16 }}>
          <Form form={form} layout="inline" initialValues={{
            source: 'ALL', market: 'ALL', stockPool: 'ALL',
            excludeSt: false, resume: false, force: false, dailyOnly: false, infoOnly: false,
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
                  <Form.Item name="force" valuePropName="checked" style={{ marginBottom: 0 }}>
                    <Checkbox>强制写入</Checkbox>
                  </Form.Item>
                </Space>
              </Col>
            </Row>
            <Row style={{ width: '100%' }}>
              <Col>
                <Space size={12}>
                  <Button type="primary" icon={isRunning ? <LockOutlined /> : <PlayCircleOutlined />}
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
        <Card title={<span>更新日志 <Text type="secondary" style={{ fontSize: 12 }}>({dailyLogs.length} 条)</Text>{renderTaskConfig(dailyTask, 'DAILY')}</span>}
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
          <Form form={indexForm} layout="inline" initialValues={{ resume: false }}
          onValuesChange={(changed) => {
            if (changed.force !== undefined) setIndexForce(changed.force);
          }}>
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
              <Col>
                <Form.Item name="force" valuePropName="checked" style={{ marginBottom: 0 }}>
                  <Checkbox>强制更新（忽略"已是最新"判断）</Checkbox>
                </Form.Item>
              </Col>
            </Row>
            <Row style={{ width: '100%' }}>
              <Col>
                <Space size={12}>
                  <Button type="primary" icon={isRunning ? <LockOutlined /> : <PlayCircleOutlined />}
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
        <Card title={
            <Space>
              <span>更新日志 <Text type="secondary" style={{ fontSize: 12 }}>({indexLogs.length} 条)</Text></span>
              {indexForce && <Tag color="red">强制更新</Tag>}
              {renderTaskConfig(indexTask, 'INDEX')}
            </Space>
          }
          size="small"
          extra={<Button size="small" onClick={() => setIndexLogs([])}>清空</Button>}>
          {renderLogs(indexLogs, indexLogRef)}
        </Card>
        {/* 指数完整性检查 */}
        {renderIndexIntegrity()}
      </>
    );
  };

  // ========== 财务数据 Tab ==========
  const renderFinancialTab = () => {
    const isRunning = financialTask?.status === 'RUNNING';
    // 计算总记录数
    const totalRecords = financialCoverage
      ? (financialCoverage.income?.count || 0)
        + (financialCoverage.balance?.count || 0)
        + (financialCoverage.cashflow?.count || 0)
        + (financialCoverage.indicator?.count || 0)
      : 0;
    return (
      <>
        {/* 财务数据概览 */}
        <Card
          title={<span><DatabaseOutlined /> 数据概览</span>}
          size="small"
          style={{ marginBottom: 16 }}
          loading={financialCoverageLoading}
          extra={<Button size="small" icon={<ReloadOutlined />} onClick={fetchFinancialCoverage}>刷新</Button>}
        >
          <Row gutter={16}>
            <Col span={4}>
              <Statistic
                title="有财务数据的股票"
                value={financialCoverage?.uniqueStocks || 0}
                suffix="只"
                valueStyle={{ fontSize: 16, color: '#1677ff' }}
                prefix={<BarChartOutlined />}
              />
            </Col>
            <Col span={5}>
              <Statistic
                title="总记录数"
                formatter={() => {
                  const r = totalRecords;
                  return <span>{r >= 10000 ? (r / 10000).toFixed(1) + ' 万' : (r || 0).toLocaleString()}</span>;
                }}
                suffix="条"
                valueStyle={{ fontSize: 16, color: '#52c41a' }}
              />
            </Col>
            <Col span={7}>
              <Statistic
                title="数据表"
                formatter={() => <Text style={{ fontSize: 13 }}>利润表 / 资产负债表 / 现金流量表 / 财务指标</Text>}
                valueStyle={{ fontSize: 13 }}
              />
            </Col>
            {isRunning && (
              <Col span={8}>
                <Statistic title="状态" formatter={() => statusTag('RUNNING')} valueStyle={{ fontSize: 14 }} />
              </Col>
            )}
          </Row>
          {/* 各表详细统计 */}
          {financialCoverage && (
            <Row gutter={12} style={{ marginTop: 12 }}>
              {[
                { key: 'income', label: '利润表', icon: '💰', color: '#1677ff' },
                { key: 'balance', label: '资产负债表', icon: '📊', color: '#52c41a' },
                { key: 'cashflow', label: '现金流量表', icon: '🏦', color: '#fa8c16' },
                { key: 'indicator', label: '财务指标', icon: '📈', color: '#722ed1' },
              ].map(table => (
                <Col span={6} key={table.key}>
                  <Card size="small" style={{ backgroundColor: '#fafafa', borderLeft: `3px solid ${table.color}` }}>
                    <Statistic
                      title={table.label}
                      value={financialCoverage[table.key]?.count || 0}
                      suffix={
                        <span style={{ fontSize: 11, color: '#8c8c8c', fontWeight: 400, marginLeft: 4 }}>
                          条 · {financialCoverage[table.key]?.stocks || 0} 只股票
                        </span>
                      }
                      valueStyle={{ fontSize: 15, color: table.color, fontWeight: 600 }}
                    />
                  </Card>
                </Col>
              ))}
            </Row>
          )}
        </Card>

        <Card title="财务数据采集配置" size="small" style={{ marginBottom: 16 }}>
          <Form form={financialForm} layout="inline"
            initialValues={{ yearStart: dayjs().year() - 1, yearEnd: dayjs().year(), force: false }}>
            <Row gutter={[16, 12]} style={{ width: '100%' }}>
              <Col>
                <Form.Item name="yearStart" label="起始年份" style={{ marginBottom: 0 }}>
                  <Select style={{ width: 100 }}>
                    {Array.from({ length: dayjs().year() - 2010 + 1 }, (_, i) => dayjs().year() - i).map(y => (
                      <Select.Option key={y} value={y}>{y} 年</Select.Option>
                    ))}
                  </Select>
                </Form.Item>
              </Col>
              <Col>
                <span style={{ lineHeight: '32px', color: '#8c8c8c', margin: '0 4px' }}>至</span>
              </Col>
              <Col>
                <Form.Item name="yearEnd" label="结束年份" style={{ marginBottom: 0 }}>
                  <Select style={{ width: 100 }}>
                    {Array.from({ length: dayjs().year() - 2010 + 1 }, (_, i) => dayjs().year() - i).map(y => (
                      <Select.Option key={y} value={y}>{y} 年</Select.Option>
                    ))}
                  </Select>
                </Form.Item>
              </Col>
              <Col>
                <Form.Item name="singleCode" label="单只股票" style={{ marginBottom: 0 }}>
                  <Input placeholder="留空则更新全部，如 000001" style={{ width: 180 }} allowClear />
                </Form.Item>
              </Col>
              <Col>
                <Form.Item name="force" valuePropName="checked" style={{ marginBottom: 0 }}>
                  <Checkbox>强制重新采集（覆盖已有数据）</Checkbox>
                </Form.Item>
              </Col>
            </Row>
            <Row gutter={[16, 12]} style={{ width: '100%', marginTop: 8 }}>
              <Col>
                <Space size={12}>
                  <Button type="primary" icon={isRunning ? <LockOutlined /> : <PlayCircleOutlined />}
                    onClick={() => handleSubmit('FINANCIAL')} disabled={isRunning}>
                    开始采集
                  </Button>
                  <Button danger icon={<StopOutlined />}
                    onClick={() => handleCancel('FINANCIAL')} disabled={!isRunning}>
                    取消任务
                  </Button>
                  <Button icon={<SearchOutlined />}
                    onClick={handleFinancialValidate} loading={financialValidateLoading}>
                    数据校验
                  </Button>
                  {isRunning && <Tag color="processing">采集进行中...</Tag>}
                  {financialTask && financialTask.status === 'SUCCESS' && <Tag color="success">采集完成</Tag>}
                  {financialTask && financialTask.status === 'FAILED' && <Tag color="error">采集失败</Tag>}
                </Space>
              </Col>
            </Row>
          </Form>
        </Card>
        {/* 进度条 */}
        {renderProgressBar(financialTask)}
        {/* 日志 */}
        <Card title={<span>采集日志 <Text type="secondary" style={{ fontSize: 12 }}>({financialLogs.length} 条)</Text>{renderTaskConfig(financialTask, 'FINANCIAL')}</span>}
          size="small" extra={<Button size="small" onClick={() => setFinancialLogs([])}>清空</Button>}>
          {renderLogs(financialLogs, financialLogRef, financialTask?.status)}
        </Card>
        {/* 校验报告 */}
        {financialValidateResult && renderFinancialValidateCard()}
      </>
    );
  };

  // ========== 财务校验报告 ==========
  const renderFinancialValidateCard = () => {
    const { tableStats, yearCoverage, fieldNullRates, anomalies, missingStocks, totalStocks } = financialValidateResult;
    return (
      <Card title={<span><SearchOutlined /> 数据校验报告</span>} size="small" style={{ marginTop: 16 }}
        extra={<Button size="small" type="text" onClick={() => setFinancialValidateResult(null)}>关闭</Button>}>
        {/* 表级统计 */}
        <Text strong style={{ fontSize: 13 }}>【1】表级记录统计</Text>
        <Table dataSource={Object.entries(tableStats || {}).map(([key, v]) => ({ key, ...v }))}
          columns={[
            { title: '表名', dataIndex: 'label', width: 120 },
            { title: '记录数', dataIndex: 'records', render: v => v?.toLocaleString(), align: 'right' },
            { title: '覆盖股票', dataIndex: 'stocks', render: v => v?.toLocaleString(), align: 'right' },
          ]}
          size="small" pagination={false} scroll={{ x: 'max-content' }} style={{ marginTop: 8, marginBottom: 16 }} />

        {/* 年份覆盖 */}
        <Text strong style={{ fontSize: 13 }}>【2】年份覆盖（stock_financial_indicator）</Text>
        <Table dataSource={(yearCoverage || []).map((r, i) => ({ ...r, key: i }))}
          columns={[
            { title: '年份', dataIndex: 'report_year', width: 80, align: 'right' },
            { title: '报告期数', dataIndex: 'record_cnt', render: v => v?.toLocaleString(), align: 'right' },
            { title: '覆盖股票', dataIndex: 'stock_cnt', render: v => v?.toLocaleString(), align: 'right' },
          ]}
          size="small" pagination={false} scroll={{ x: 'max-content' }} style={{ marginTop: 8, marginBottom: 16 }} />

        {/* 字段空值率 */}
        <Text strong style={{ fontSize: 13 }}>【3】关键字段空值率</Text>
        <Table dataSource={(fieldNullRates || []).map((r, i) => ({ ...r, key: i }))}
          columns={[
            { title: '字段', dataIndex: 'field', width: 160 },
            { title: '非空记录', dataIndex: 'nonNull', render: v => v?.toLocaleString(), align: 'right' },
            { title: '总记录', dataIndex: 'total', render: v => v?.toLocaleString(), align: 'right' },
            { title: '空值率(%)', dataIndex: 'rate', render: v => `${v}%`,
              align: 'right' },
          ]}
          size="small" pagination={false} scroll={{ x: 'max-content' }} style={{ marginTop: 8, marginBottom: 16 }} />

        {/* 同比异常 */}
        <Text strong style={{ fontSize: 13 }}>【4】净利润同比异常（扭亏/转亏/变化&gt;10倍）</Text>
        {anomalies && anomalies.length > 0 ? (
          <Table dataSource={anomalies.map((r, i) => ({ ...r, key: i }))}
            columns={[
              { title: '代码', dataIndex: 'code', width: 100 },
              { title: '名称', dataIndex: 'name', width: 120, ellipsis: true },
              { title: '年份', dataIndex: 'report_year', width: 70, align: 'right' },
              { title: '类型', dataIndex: 'report_type', width: 60, align: 'center',
                render: v => ['', '一季报', '中报', '三季报', '年报'][v] || v },
              { title: '当年净利润', dataIndex: 'cur_profit', align: 'right',
                render: v => v != null ? Number(v).toLocaleString() : '-' },
              { title: '上年净利润', dataIndex: 'prev_profit', align: 'right',
                render: v => v != null ? Number(v).toLocaleString() : '-' },
            ]}
            size="small" pagination={false} scroll={{ x: 'max-content' }} style={{ marginTop: 8, marginBottom: 16 }} />
        ) : (
          <Alert message="未发现明显异常" type="success" showIcon style={{ marginTop: 8, marginBottom: 16 }} />
        )}

        {/* 缺失警告 */}
        <Text strong style={{ fontSize: 13 }}>【5】近3年财报完全缺失的股票（{totalStocks}只有效股票中）</Text>
        {missingStocks && missingStocks.length > 0 ? (
          <>
            <Alert message={`共 ${missingStocks.length} 只（显示前20）`} type="warning" showIcon style={{ marginTop: 8, marginBottom: 8 }} />
            <Table dataSource={missingStocks.map((r, i) => ({ ...r, key: i }))}
              columns={[
                { title: '代码', dataIndex: 'code', width: 100 },
                { title: '名称', dataIndex: 'name', width: 120 },
                { title: '市场', dataIndex: 'market', width: 80 },
              ]}
              size="small" pagination={false} scroll={{ x: 'max-content' }} />
          </>
        ) : (
          <Alert message="未发现明显缺失" type="success" showIcon style={{ marginTop: 8 }} />
        )}
      </Card>
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
                  <Button type="primary" icon={isRunning ? <LockOutlined /> : <PlayCircleOutlined />}
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
        <Card title={<span>更新日志 <Text type="secondary" style={{ fontSize: 12 }}>({dividendLogs.length} 条)</Text>{renderTaskConfig(dividendTask, 'DIVIDEND')}</span>}
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

  // ========== 情绪数据 Tab ==========
  const renderSentimentTab = () => {
    const isRunning = sentimentTask?.status === 'RUNNING';
    return (
      <>
        {/* 情绪数据概览 */}
        <Card
          title={<span><DatabaseOutlined /> 数据概览</span>}
          size="small"
          style={{ marginBottom: 16 }}
          loading={sentimentCoverageLoading}
          extra={<Button size="small" icon={<ReloadOutlined />} onClick={fetchSentimentCoverage}>刷新</Button>}
        >
          <Row gutter={16}>
            <Col span={4}>
              <Statistic
                title="情绪数据表"
                value={sentimentCoverage?.tableCount || 0}
                suffix="张"
                valueStyle={{ fontSize: 16, color: '#1677ff' }}
                prefix={<DatabaseOutlined />}
              />
            </Col>
            <Col span={5}>
              <Statistic
                title="总记录数"
                formatter={() => {
                  const r = sentimentCoverage?.totalRecords || 0;
                  return <span>{r >= 10000 ? (r / 10000).toFixed(1) + ' 万' : (r || 0).toLocaleString()}</span>;
                }}
                suffix="条"
                valueStyle={{ fontSize: 16, color: '#52c41a' }}
              />
            </Col>
            <Col span={7}>
              <Statistic
                title="数据表"
                formatter={() => <Text style={{ fontSize: 12, whiteSpace: 'nowrap' }}>涨跌停/龙虎榜/融资融券/机构调研/大宗交易/市场活跃度/资金流向/公告/国债收益率/申万行业指数/一致预期/业绩快报/QVIX恐慌指数</Text>}
                valueStyle={{ fontSize: 12 }}
              />
            </Col>
          </Row>
          {/* 各表详细统计 - 默认收起 */}
          {sentimentCoverage?.tables && sentimentCoverage.tables.length > 0 && (
            <Collapse
              size="small"
              ghost
              style={{ marginTop: 12 }}
              items={[{
                key: 'detail',
                label: <span style={{ color: '#8c8c8c' }}>各表详细统计 ({sentimentCoverage.tables.length} 张表)</span>,
                children: (
                  <Row gutter={[12, 12]}>
                    {sentimentCoverage.tables.map(table => (
                      <Col style={{ flex: '0 0 19.8%', maxWidth: '19.8%' }} key={table.table}>
                        <Card size="small" style={{ backgroundColor: '#fafafa', borderLeft: '3px solid #722ed1', marginBottom: 4 }}>
                          <Statistic
                            title={table.name === '一致预期'
                              ? (<span>一致预期{' '}
                                <Tooltip title="同花顺一致预期数据（预测净利润），用于事件驱动策略对比实际业绩vs预期，生成超预期/不及预期信号">
                                  <QuestionCircleOutlined style={{ color: '#999', fontSize: 12 }} />
                                </Tooltip></span>)
                              : table.name === '业绩快报'
                                ? (<span>业绩快报{' '}
                                  <Tooltip title="东方财富业绩快报数据（EPS/营收/净利/ROE等），用于事件驱动策略判断业绩超预期或不及预期，触发买入/卖出信号">
                                    <QuestionCircleOutlined style={{ color: '#999', fontSize: 12 }} />
                                  </Tooltip></span>
                                )
                                : table.name === 'QVIX恐慌指数'
                                  ? (<span>QVIX恐慌指数{' '}
                                    <Tooltip title="中国VIX指数（50ETF/300ETF/500ETF/创业板指），用于市场情绪策略判断市场恐慌程度，调整推荐权重">
                                      <QuestionCircleOutlined style={{ color: '#999', fontSize: 12 }} />
                                    </Tooltip></span>)
                                  : table.name
                            }
                            value={table.recordCount || 0}
                            suffix={
                              <span style={{ fontSize: 11, color: '#8c8c8c', fontWeight: 400, marginLeft: 4 }}>
                                条 · {
                                  table.minDate && table.maxDate
                                    ? (() => {
                                        const fmt = (s) => {
                                          const d = String(s).slice(0, 10);
                                          // 无横杠的纯数字日期（如20240630）→ 补横杠
                                          if (/^\d{8}$/.test(d)) return d.slice(0,4) + '-' + d.slice(4,6) + '-' + d.slice(6);
                                          return d;
                                        };
                                        const minD = fmt(table.minDate);
                                        const maxD = fmt(table.maxDate);
                                        const crossYear = minD.slice(0, 4) !== maxD.slice(0, 4);
                                        return crossYear ? `${minD}~${maxD}` : `${minD.slice(5)}~${maxD.slice(5)}`;
                                      })()
                                    : ''
                                }
                              </span>
                            }
                            valueStyle={{ fontSize: 14, color: '#722ed1', fontWeight: 600 }}
                          />
                        </Card>
                      </Col>
                    ))}
                  </Row>
                ),
              }]}
            />
          )}
        </Card>

        {/* 情绪数据采集配置 */}
        <Card title="情绪数据采集配置" size="small" style={{ marginBottom: 16 }}>
          <Form form={sentimentForm} layout="inline" initialValues={{
            dateRange: [dayjs().subtract(7, 'day'), dayjs()],
            fetchLhb: true,
            fetchMargin: true,
            fetchSurvey: true,
            fetchBlockTrade: true,
            fetchActivity: true,
            fetchZtPool: true,
            fetchMoneyflow: true,
            fetchNotice: true,
            fetchFundHolder: true,
            fetchShareholder: true,
            fetchNews: true,
            fetchBondYield: true,
            fetchShenwanIndex: true,
            fetchConsensusEstimate: true,
            fetchEarningsReport: true,
            fetchQvix: true,
            force: false,
            moneyflowSource: 'AKSHARE',
          }}>
            <Row gutter={[16, 12]} style={{ width: '100%' }} align="middle">
              <Col>
                <Form.Item name="dateRange" label="日期范围" tooltip="不选则默认最近7天">
                  <RangePicker placeholder={['开始日期', '结束日期']} />
                </Form.Item>
              </Col>
              <Col>
                <Form.Item name="sentimentCodes" label="股票代码" tooltip="逗号分隔，为空则全部股票">
                  <Input placeholder="000001,600519" style={{ width: 160 }} allowClear />
                </Form.Item>
              </Col>
              <Col>
                <Form.Item name="moneyflowSource" noStyle>
                  <Radio.Group value={sentimentMoneyflowSource} onChange={e => {
                    const val = e.target.value;
                    setSentimentMoneyflowSource(val);
                    if (val === 'WESTOCK') {
                      // westock 模式：只保留资金流向勾选，其他全部取消
                      sentimentForm.setFieldsValue({
                        moneyflowSource: val,
                        fetchLhb: false,
                        fetchMargin: false,
                        fetchSurvey: false,
                        fetchBlockTrade: false,
                        fetchActivity: false,
                        fetchZtPool: false,
                        fetchMoneyflow: true,
                        fetchNotice: false,
                        fetchFundHolder: false,
                        fetchShareholder: false,
                        fetchNews: false,
                        fetchBondYield: false,
                        fetchShenwanIndex: false,
                        fetchConsensusEstimate: false,
                        fetchEarningsReport: false,
                        fetchQvix: false,
                        force: false,
                      });
                    } else {
                      // AKSHARE 模式：全部恢复默认勾选
                      sentimentForm.setFieldsValue({
                        moneyflowSource: val,
                        fetchLhb: true,
                        fetchMargin: true,
                        fetchSurvey: true,
                        fetchBlockTrade: true,
                        fetchActivity: true,
                        fetchZtPool: true,
                        fetchMoneyflow: true,
                        fetchNotice: true,
                        fetchFundHolder: true,
                        fetchShareholder: true,
                        fetchNews: true,
                        fetchBondYield: true,
                        fetchShenwanIndex: true,
                        fetchConsensusEstimate: true,
                        fetchEarningsReport: true,
                        fetchQvix: true,
                        force: false,
                      });
                    }
                  }}>
                    <Radio.Button value="AKSHARE">
                      <CloudSyncOutlined /> akshare（默认）
                    </Radio.Button>
                    <Radio.Button value="WESTOCK" style={{ marginLeft: 8 }}>
                      <ThunderboltOutlined /> westock
                    </Radio.Button>
                  </Radio.Group>
                  {sentimentMoneyflowSource === 'WESTOCK' && (
                    <Tag color="purple" icon={<ThunderboltOutlined />} style={{ marginLeft: 12 }}>
                      westock 模式：仅采集资金流向
                    </Tag>
                  )}
                </Form.Item>
              </Col>
            </Row>
            <Row gutter={[16, 12]} style={{ width: '100%', marginBottom: 12 }}>
              <Col>
                <div style={{
                  opacity: sentimentMoneyflowSource === 'WESTOCK' ? 0.5 : 1,
                  pointerEvents: sentimentMoneyflowSource === 'WESTOCK' ? 'none' : 'auto',
                  transition: 'opacity 0.2s',
                }}>
                  <Space style={{ marginBottom: 12, marginTop: 4 }}>
                    <Button size="small" icon={<CheckSquareOutlined />}
                      onClick={() => sentimentForm.setFieldsValue({
                        fetchLhb: true, fetchMargin: true, fetchSurvey: true,
                        fetchBlockTrade: true, fetchActivity: true, fetchZtPool: true,
                        fetchMoneyflow: true, fetchNotice: true, fetchFundHolder: true,
                        fetchShareholder: true, fetchNews: true,
                        fetchBondYield: true, fetchShenwanIndex: true,
                        fetchConsensusEstimate: true, fetchEarningsReport: true,
                        fetchQvix: true,
                      })}>
                      全选
                    </Button>
                    <Button size="small"
                      onClick={() => {
                        const vals = sentimentForm.getFieldsValue();
                        sentimentForm.setFieldsValue({
                          fetchLhb: !vals.fetchLhb, fetchMargin: !vals.fetchMargin,
                          fetchSurvey: !vals.fetchSurvey, fetchBlockTrade: !vals.fetchBlockTrade,
                          fetchActivity: !vals.fetchActivity, fetchZtPool: !vals.fetchZtPool,
                          fetchMoneyflow: !vals.fetchMoneyflow, fetchNotice: !vals.fetchNotice,
                          fetchFundHolder: !vals.fetchFundHolder,
                          fetchShareholder: !vals.fetchShareholder, fetchNews: !vals.fetchNews,
                          fetchBondYield: !vals.fetchBondYield, fetchShenwanIndex: !vals.fetchShenwanIndex,
                          fetchConsensusEstimate: !vals.fetchConsensusEstimate,
                          fetchEarningsReport: !vals.fetchEarningsReport,
                          fetchQvix: !vals.fetchQvix,
                        });
                      }}>
                      反选
                    </Button>
                  </Space>
                  <div style={{ display: 'grid', gridTemplateColumns: 'repeat(8, 1fr)', gap: '8px 16px' }}>
                    <Form.Item name="fetchLhb" valuePropName="checked" style={{ marginBottom: 0 }}>
                      <Checkbox>龙虎榜</Checkbox>
                    </Form.Item>
                    <Form.Item name="fetchMargin" valuePropName="checked" style={{ marginBottom: 0 }}>
                      <Checkbox>融资融券</Checkbox>
                    </Form.Item>
                    <Form.Item name="fetchSurvey" valuePropName="checked" style={{ marginBottom: 0 }}>
                      <Checkbox>机构调研</Checkbox>
                    </Form.Item>
                    <Form.Item name="fetchBlockTrade" valuePropName="checked" style={{ marginBottom: 0 }}>
                      <Checkbox>大宗交易</Checkbox>
                    </Form.Item>
                    <Form.Item name="fetchActivity" valuePropName="checked" style={{ marginBottom: 0 }}>
                      <Checkbox>市场活跃度</Checkbox>
                    </Form.Item>
                    <Form.Item name="fetchZtPool" valuePropName="checked" style={{ marginBottom: 0 }}>
                      <Checkbox>涨跌停池</Checkbox>
                    </Form.Item>
                    <Form.Item name="fetchMoneyflow" valuePropName="checked" style={{ marginBottom: 0 }}>
                      <Checkbox>资金流向</Checkbox>
                    </Form.Item>
                    <Form.Item name="fetchNotice" valuePropName="checked" style={{ marginBottom: 0 }}>
                      <Checkbox>公告</Checkbox>
                    </Form.Item>
                    <Form.Item name="fetchFundHolder" valuePropName="checked" style={{ marginBottom: 0 }}>
                      <Checkbox>基金持仓</Checkbox>
                    </Form.Item>
                    <Form.Item name="fetchShareholder" valuePropName="checked" style={{ marginBottom: 0 }}>
                      <Checkbox>股东人数</Checkbox>
                    </Form.Item>
                    <Form.Item name="fetchNews" valuePropName="checked" style={{ marginBottom: 0 }}>
                      <Checkbox>新闻</Checkbox>
                    </Form.Item>
                    <Form.Item name="fetchBondYield" valuePropName="checked" style={{ marginBottom: 0 }}>
                      <Checkbox>国债收益率</Checkbox>
                    </Form.Item>
                    <Form.Item name="fetchShenwanIndex" valuePropName="checked" style={{ marginBottom: 0 }}>
                      <Checkbox>申万行业指数</Checkbox>
                    </Form.Item>
                    <Form.Item name="fetchConsensusEstimate" valuePropName="checked" style={{ marginBottom: 0 }}>
                      <Checkbox>一致预期</Checkbox>
                    </Form.Item>
                    <Form.Item name="fetchEarningsReport" valuePropName="checked" style={{ marginBottom: 0 }}>
                      <Checkbox>业绩快报</Checkbox>
                    </Form.Item>
                    <Form.Item name="fetchQvix" valuePropName="checked" style={{ marginBottom: 0 }}>
                      <Checkbox>QVIX恐慌指数</Checkbox>
                    </Form.Item>
                  </div>
                </div>
              </Col>
            </Row>
            <Row style={{ width: '100%' }}>
              <Col>
                <Space size={12} wrap>
                  <Button type="primary" icon={isRunning ? <LockOutlined /> : <PlayCircleOutlined />}
                    onClick={() => handleSubmit('SENTIMENT')} disabled={isRunning}>
                    开始采集
                  </Button>
                  <Button danger icon={<StopOutlined />}
                    onClick={() => handleCancel('SENTIMENT')} disabled={!isRunning}>
                    取消任务
                  </Button>
                  <Form.Item name="force" valuePropName="checked" style={{ marginBottom: 0 }}>
                    <Checkbox>全量重刷（覆盖已有数据）</Checkbox>
                  </Form.Item>
                  {isRunning && <Tag color="processing">采集中...</Tag>}
                  {sentimentTask && sentimentTask.status === 'SUCCESS' && <Tag color="success">采集完成</Tag>}
                  {sentimentTask && sentimentTask.status === 'FAILED' && <Tag color="error">采集失败</Tag>}
                </Space>
              </Col>
            </Row>
          </Form>
        </Card>

        {/* 进度条 */}
        {renderProgressBar(sentimentTask)}

        {/* 日志 */}
        <Card
          title={(
            <span>
              采集日志 <Text type="secondary" style={{ fontSize: 12 }}>({sentimentLogs.length} 条)</Text>
            </span>
          )}
          size="small" style={{ marginBottom: 16 }}
          extra={<Button size="small" onClick={() => setSentimentLogs([])}>清空</Button>}>
          {sentimentTask && (sentimentTask.configStartDate || sentimentTask.configMoneyflowSource) && (
            <div style={{ padding: '8px 0 12px', borderBottom: '1px solid #f0f0f0', marginBottom: 12 }}>
              <Space size={8} wrap>
                <Text type="secondary" style={{ fontSize: 12 }}>
                  <CalendarOutlined /> 日期：
                  <Text style={{ fontSize: 12 }}>
                    {sentimentTask.configStartDate || '默认'} ~ {sentimentTask.configEndDate || '默认'}
                  </Text>
                </Text>
                <Text type="secondary" style={{ fontSize: 12 }}>
                  渠道：
                  <Text style={{ fontSize: 12 }}>
                    {sentimentTask.configMoneyflowSource === 'WESTOCK' ? 'westock（仅资金流向）' : 'akshare'}
                  </Text>
                </Text>
                {(sentimentTask.configMoneyflowSource !== 'WESTOCK') && (
                  <>
                    {sentimentTask.configFetchLhb && <Tag size="small">龙虎榜</Tag>}
                    {sentimentTask.configFetchMargin && <Tag size="small">融资融券</Tag>}
                    {sentimentTask.configFetchSurvey && <Tag size="small">机构调研</Tag>}
                    {sentimentTask.configFetchBlockTrade && <Tag size="small">大宗交易</Tag>}
                    {sentimentTask.configFetchActivity && <Tag size="small">市场活跃度</Tag>}
                    {sentimentTask.configFetchZtPool && <Tag size="small">涨跌停池</Tag>}
                    {sentimentTask.configFetchMoneyflow && <Tag size="small">资金流向</Tag>}
                    {sentimentTask.configFetchNotice && <Tag size="small">公告</Tag>}
                    {sentimentTask.configFetchFundHolder && <Tag size="small">基金持仓</Tag>}
                    {sentimentTask.configFetchShareholder && <Tag size="small">股东人数</Tag>}
                    {sentimentTask.configFetchNews && <Tag size="small">新闻</Tag>}
                    {sentimentTask.configFetchBondYield !== false && <Tag size="small">国债收益率</Tag>}
                    {sentimentTask.configFetchShenwanIndex !== false && <Tag size="small">申万行业指数</Tag>}
                    {sentimentTask.configFetchConsensusEstimate !== false && <Tag size="small">一致预期</Tag>}
                    {sentimentTask.configFetchEarningsReport !== false && <Tag size="small">业绩快报</Tag>}
                    {sentimentTask.configFetchQvix !== false && <Tag size="small" color="purple">QVIX恐慌指数</Tag>}
                  </>
                )}
              </Space>
            </div>
          )}
          {renderLogs(sentimentLogs, sentimentLogRef)}
        </Card>

        {/* 情绪数据校验 */}
        {renderSentimentValidateCard()}
      </>
    );
  };

  // ========== 情绪数据校验 ==========
  const renderSentimentValidateCard = () => {
    const { dailyStats, tableNames, dateRangeStart, dateRangeEnd } = sentimentValidateResult || {};
    const rangeText = dateRangeStart && dateRangeEnd ? `${dateRangeStart} ~ ${dateRangeEnd}` : '全部日期';

    const tableColumns = (sentimentValidateTables || []).map(value => {
      const label = (tableNames && tableNames[value])
        || SENTIMENT_TABLE_OPTIONS.find(o => o.value === value)?.label
        || value;
      const labelWidthMap = {
        stock_sentiment_lhb_inst: 150,
        stock_sentiment_margin_detail: 140,
        stock_consensus_estimate: 120,
      };
      return {
        title: label,
        dataIndex: value,
        key: value,
        width: labelWidthMap[value] || 110,
        align: 'right',
        render: v => v > 0 ? (v).toLocaleString() : <Button size="small" disabled style={{ padding: '0 7px', fontSize: 12 }}>无</Button>,
      };
    });

    const columns = [
      { title: '日期', dataIndex: 'date', key: 'date', width: 110, fixed: 'left' },
      ...tableColumns,
      {
        title: (
          <span>
            空值检查
            <Tooltip title="空值检查：检查选中日期范围内，各表 code 字段是否存在 NULL 或空字符串。">
              <QuestionCircleOutlined style={{ marginLeft: 4, color: '#8c8c8c' }} />
            </Tooltip>
          </span>
        ),
        dataIndex: 'totalNullCount',
        key: 'totalNullCount',
        width: 110,
        align: 'center',
        render: v => v > 0 ? <Tag color="warning">{v} 条</Tag> : <Tag color="success">正常</Tag>,
      },
    ];

    return (
      <Card title={<span><SearchOutlined /> 数据校验</span>} size="small" style={{ marginBottom: 16 }}
        extra={
          <Button size="small" type="text" onClick={() => setSentimentValidateResult(null)}>
            关闭
          </Button>
        }>
        <Space wrap style={{ marginBottom: 16 }}>
          <Select
            value={sentimentValidateQuickDate}
            onChange={handleSentimentQuickDateChange}
            options={SENTIMENT_QUICK_DATE_OPTIONS}
            style={{ width: 110 }}
            placeholder="日期范围"
          />
          <RangePicker
            value={sentimentValidateDateRange}
            onChange={(dates) => {
              setSentimentValidateDateRange(dates);
              setSentimentValidateQuickDate('custom');
            }}
            disabledDate={(date) => !isTradingDay(date)}
            placeholder={['开始日期', '结束日期']}
            style={{ width: 240 }}
          />
          <Select
            mode="multiple"
            allowClear
            showSearch={false}
            value={sentimentValidateTables}
            onChange={setSentimentValidateTables}
            options={SENTIMENT_TABLE_OPTIONS}
            style={{ minWidth: 180, maxWidth: 360 }}
            placeholder="选择校验表"
            maxTagCount={2}
            maxTagPlaceholder={(omittedValues) => `+${omittedValues.length} 项`}
          />
          <Button icon={<SearchOutlined />}
            onClick={handleSentimentValidate} loading={sentimentValidateLoading}>
            数据校验
          </Button>
          <Text type="secondary" style={{ fontSize: 13 }}>
            当前范围：{rangeText}
          </Text>
        </Space>
        <Table
          dataSource={(dailyStats || []).map((d, i) => ({ ...d, key: i }))}
          columns={columns}
          size="small"
          pagination={false}
          scroll={{ x: 'max-content' }}
          locale={{ emptyText: '暂无校验数据，请选择日期范围并点击“数据校验”' }}
        />
      </Card>
    );
  };

  // ========== 内外盘数据 Tab ==========
  const renderBidaskTab = () => {
    const isRunning = bidaskTask?.status === 'RUNNING';
    const mktColors = { SH: '#1677ff', SZ: '#52c41a', BJ: '#fa8c16' };

    return (
      <>
        {/* 数据概览 */}
        <Card
          title={<span><DatabaseOutlined /> 数据概览</span>}
          size="small"
          style={{ marginBottom: 12 }}
          loading={bidaskCoverageLoading}
          extra={<Button size="small" icon={<ReloadOutlined />} onClick={fetchBidaskCoverage}>刷新</Button>}
        >
          <Row gutter={[12, 12]} style={{ marginBottom: 8 }}>
            <Col span={4}>
              <Statistic
                title="总记录数"
                value={bidaskCoverage?.totalRecords || 0}
                suffix="条"
                formatter={(v) => {
                  const n = Number(v) || 0;
                  return n >= 10000 ? (n / 10000).toFixed(1) + ' 万' : n.toLocaleString();
                }}
                valueStyle={{ fontSize: 16, color: '#1677ff' }}
                prefix={<DatabaseOutlined />}
              />
            </Col>
            <Col span={4}>
              <Statistic
                title="覆盖股票"
                value={bidaskCoverage?.coveredStocks || 0}
                suffix="只"
                valueStyle={{ fontSize: 16, color: '#722ed1' }}
              />
            </Col>
            <Col span={5}>
              <Statistic
                title="最早日期"
                value={bidaskCoverage?.minDate ? String(bidaskCoverage.minDate).slice(0, 10) : '--'}
                valueStyle={{ fontSize: 14, color: '#8c8c8c' }}
              />
            </Col>
            <Col span={5}>
              <Statistic
                title="最新日期"
                value={bidaskCoverage?.maxDate ? String(bidaskCoverage.maxDate).slice(0, 10) : '--'}
                valueStyle={{ fontSize: 14, color: '#8c8c8c' }}
              />
            </Col>
          </Row>
          {/* 各市场统计 */}
          {bidaskCoverage?.marketStats && bidaskCoverage.marketStats.length > 0 && (
            <Row gutter={[12, 8]}>
              {bidaskCoverage.marketStats.map((s) => (
                <Col style={{ flex: '0 0 19.8%', maxWidth: '19.8%' }} key={s.market}>
                  <Card size="small" style={{ backgroundColor: '#fafafa', borderLeft: `3px solid ${mktColors[s.market] || '#999'}`, marginBottom: 4 }}>
                    <Statistic
                      title={s.market === 'SH' ? '沪市' : s.market === 'SZ' ? '深市' : '北交所'}
                      value={s.record_count || 0}
                      formatter={(v) => {
                        const n = Number(v) || 0;
                        return n >= 10000 ? (n / 10000).toFixed(1) + ' 万' : n.toLocaleString();
                      }}
                      suffix={
                        <span style={{ fontSize: 11, color: '#8c8c8c', fontWeight: 400 }}>
                          条 · {s.stock_count || 0} 只
                        </span>
                      }
                      valueStyle={{ fontSize: 14, fontWeight: 600, color: mktColors[s.market] || '#999' }}
                    />
                  </Card>
                </Col>
              ))}
            </Row>
          )}
        </Card>

        {/* 参数配置 */}
        <Card size="small" style={{ marginBottom: 12 }}>
          <Form form={bidaskForm} layout="inline" initialValues={{
            singleCode: '',
          }}>
            <Row gutter={[12, 12]} style={{ width: '100%' }}>
              <Col>
                <Form.Item name="dateRange" label="日期范围">
                  <RangePicker size="small" />
                </Form.Item>
              </Col>
              <Col>
                <Form.Item name="singleCode" label="单只股票">
                  <Input placeholder="留空则更新全部，如 600519" style={{ width: 180 }} allowClear />
                </Form.Item>
              </Col>
              <Col>
                <Space size={12}>
                  <Button type="primary" icon={<PlayCircleOutlined />}
                    onClick={() => handleSubmit('BIDASK')} disabled={isRunning}>
                    开始采集
                  </Button>
                  <Button danger icon={<StopOutlined />}
                    onClick={() => handleCancel('BIDASK')} disabled={!isRunning}>
                    取消任务
                  </Button>
                  {isRunning && <Tag color="processing">采集中...</Tag>}
                  {bidaskTask && bidaskTask.status === 'SUCCESS' && <Tag color="success">采集完成</Tag>}
                  {bidaskTask && bidaskTask.status === 'FAILED' && <Tag color="error">采集失败</Tag>}
                </Space>
              </Col>
            </Row>
          </Form>
        </Card>

        {/* 统计卡片 */}
        <Card size="small" style={{ marginBottom: 12 }}>
          <Row gutter={24} style={{ marginBottom: 12 }}>
            <Col span={6}>
              <Statistic
                title="总目标"
                value={bidaskTask?.totalStocks || 0}
                suffix="只"
                valueStyle={{ fontSize: 18, color: '#1677ff' }}
              />
            </Col>
            <Col span={6}>
              <Statistic
                title="已处理"
                value={bidaskTask?.processedStocks || 0}
                suffix="只"
                valueStyle={{ fontSize: 18, color: '#52c41a' }}
              />
            </Col>
            <Col span={6}>
              <Statistic
                title="成功率"
                value={bidaskTask?.totalStocks > 0
                  ? ((bidaskTask.processedStocks / bidaskTask.totalStocks) * 100).toFixed(1)
                  : 0}
                suffix="%"
                valueStyle={{ fontSize: 18, color: '#faad14' }}
              />
            </Col>
          </Row>
          {/* 日期维度统计（完成后显示） */}
          {bidaskTask?.bidAskStats && (
            <Table
              size="small"
              dataSource={Object.entries(bidaskTask.bidAskStats).map(([date, s]) => ({
                key: date,
                date: date,
                total: s.total,
                success: s.success,
                failed: s.failed,
                rate: s.rate,
                holiday: s.holiday,
                label: s.label,
              }))}
              columns={[
                { title: '日期', dataIndex: 'date', width: 100,
                  render: (v, r) => r.holiday ? <span style={{ color: '#999' }}>{v} <Tag color="default" style={{ fontSize: 10 }}>{r.label}</Tag></span> : v },
                { title: '目标', dataIndex: 'total', align: 'right', width: 80,
                  render: (v, r) => r.holiday ? '-' : v },
                { title: '成功', dataIndex: 'success', align: 'right', width: 80,
                  render: (v, r) => r.holiday ? '-' : <span style={{ color: '#52c41a' }}>{v}</span> },
                { title: '失败', dataIndex: 'failed', align: 'right', width: 80,
                  render: (v, r) => r.holiday ? '-' : (v > 0 ? <span style={{ color: '#ff4d4f' }}>{v}</span> : v) },
                { title: '成功率', dataIndex: 'rate', align: 'right', width: 80,
                  render: (v, r) => r.holiday ? '-' : v },
              ]}
              pagination={false}
              scroll={{ x: 'max-content' }}
              style={{ marginTop: 8 }}
            />
          )}
        </Card>

        {/* 进度条 */}
        {renderProgressBar(bidaskTask)}

        {/* 日志 */}
        <Card title={<span>采集日志 <Text type="secondary" style={{ fontSize: 12 }}>({bidaskLogs.length} 条)</Text></span>}
          size="small" extra={<Button size="small" onClick={() => setBidaskLogs([])}>清空</Button>}>
          {renderLogs(bidaskLogs, bidaskLogRef)}
        </Card>
      </>
    );
  };

  // ========== 研报数据 Tab ==========
  const renderResearchTab = () => {
    const isRunning = researchTask?.status === 'RUNNING';
    return (
      <>
        {/* 研报数据概览 */}
        <Card
          title={<span><FileTextOutlined /> 数据概览</span>}
          size="small" style={{ marginBottom: 16 }}
          loading={researchCoverageLoading}
          extra={<Button size="small" icon={<ReloadOutlined />} onClick={fetchResearchCoverage}>刷新</Button>}
        >
          <Row gutter={16}>
            <Col span={4}>
              <Statistic title="研报总数" value={researchCoverage?.totalCount || 0} suffix="条"
                valueStyle={{ fontSize: 16, color: '#1677ff' }} prefix={<FileTextOutlined />} />
            </Col>
            <Col span={5}>
              <Statistic title="覆盖股票" value={researchCoverage?.stockCount || 0} suffix="只"
                valueStyle={{ fontSize: 16, color: '#52c41a' }} />
            </Col>
            <Col span={7}>
              <Statistic title="最新日期" value={researchCoverage?.latestDate || '--'}
                valueStyle={{ fontSize: 16 }} prefix={<CalendarOutlined />} />
            </Col>
            {isRunning && (
              <Col span={8}>
                <Statistic title="状态" formatter={() => statusTag('RUNNING')}
                  valueStyle={{ fontSize: 14 }} />
              </Col>
            )}
          </Row>
        </Card>

        {/* 研报数据采集配置 */}
        <Card title="研报数据采集配置" size="small" style={{ marginBottom: 16 }}>
          <Form form={researchForm} layout="inline" initialValues={{
            force: false,
            dateRange: [dayjs().subtract(1, 'month'), dayjs()],
          }}>
            <Row gutter={[16, 12]} style={{ width: '100%' }}>
              <Col>
                <Form.Item name="dateRange" label="日期范围">
                  <RangePicker size="small" />
                </Form.Item>
              </Col>
              <Col>
                <Form.Item name="singleCode" label="单只股票">
                  <Input placeholder="留空则更新全部，如 000001" style={{ width: 200 }} allowClear />
                </Form.Item>
              </Col>
              <Col>
                <Form.Item name="force" valuePropName="checked" style={{ marginBottom: 0 }}>
                  <Checkbox>强制重新采集（更新全部已有记录）</Checkbox>
                </Form.Item>
              </Col>
              <Col>
                <span style={{ lineHeight: '32px', color: '#8c8c8c', fontSize: 13 }}>
                  <FileTextOutlined style={{ marginRight: 4 }} />
                  采集东方财富个股研报数据，包含评级、盈利预测、PDF链接
                </span>
              </Col>
            </Row>
            <Row gutter={[16, 12]} style={{ width: '100%', marginTop: 8 }}>
              <Col>
                <Space size={12}>
                  <Button type="primary" icon={isRunning ? <LockOutlined /> : <PlayCircleOutlined />}
                    onClick={() => handleSubmit('RESEARCH')} disabled={isRunning}>
                    开始采集
                  </Button>
                  <Button danger icon={<StopOutlined />}
                    onClick={() => handleCancel('RESEARCH')} disabled={!isRunning}>
                    取消任务
                  </Button>
                  <Button icon={<SearchOutlined />}
                    onClick={handleResearchValidate} loading={researchValidateLoading}>
                    数据校验
                  </Button>
                  {isRunning && <Tag color="processing">采集中...</Tag>}
                  {researchTask && researchTask.status === 'SUCCESS' && <Tag color="success">采集完成</Tag>}
                  {researchTask && researchTask.status === 'FAILED' && <Tag color="error">采集失败</Tag>}
                </Space>
              </Col>
            </Row>
          </Form>
        </Card>

        {/* 进度条 */}
        {renderProgressBar(researchTask)}

        {/* 日志 */}
        <Card title={<span>采集日志 <Text type="secondary" style={{ fontSize: 12 }}>({researchLogs.length} 条)</Text>{renderTaskConfig(researchTask, 'RESEARCH')}</span>}
          size="small" extra={<Button size="small" onClick={() => setResearchLogs([])}>清空</Button>}>
          {renderLogs(researchLogs, researchLogRef)}
        </Card>

        {/* 研报数据校验报告 */}
        {researchValidateResult && renderResearchValidateCard()}
      </>
    );
  };

  // ========== 研报数据校验报告 ==========
  const renderResearchValidateCard = () => {
    const { totalReports, status, warnings } = researchValidateResult || {};
    return (
      <Card title={<span><SearchOutlined /> 研报数据校验报告</span>} size="small" style={{ marginTop: 16 }}
        extra={<Button size="small" type="text" onClick={() => setResearchValidateResult(null)}>关闭</Button>}>
        <Row gutter={16} style={{ marginBottom: 16 }}>
          <Col span={4}>
            <Statistic title="研报数" value={totalReports || 0}
              valueStyle={{ fontSize: 14, color: '#1677ff' }} />
          </Col>
          <Col span={4}>
            <Statistic title="警告数" value={(warnings || []).length || 0}
              valueStyle={{ fontSize: 14, color: (warnings || []).length > 0 ? '#fa8c16' : '#52c41a' }} />
          </Col>
          <Col span={4}>
            <Statistic title="状态" value={status === 'OK' ? '正常' : '有警告'}
              valueStyle={{ fontSize: 14, color: status === 'OK' ? '#52c41a' : '#fa8c16' }} />
          </Col>
        </Row>
        {(warnings || []).length > 0 && (
          <ul style={{ margin: 0, paddingLeft: 16, fontSize: 12 }}>
            {warnings.map((w, i) => <li key={i}>{w}</li>)}
          </ul>
        )}
      </Card>
    );
  };


  // ========== 指数完整性检查 ==========
  const renderIndexIntegrity = () => (
    <Card title={<span><SearchOutlined /> 指数完整性检查</span>} size="small" style={{ marginTop: 16 }}>
      <Row gutter={16} align="middle" style={{ marginBottom: 16 }}>
        <Col>
          <Text>检查日期:</Text>
          <DatePicker value={missingIndexDate} onChange={d => setMissingIndexDate(d)}
            disabledDate={(current) => {
              // 只限制不能选未来日期，不限制必须是交易日
              return current && current.isAfter(dayjs().endOf('day'));
            }}
            allowClear={false} style={{ marginLeft: 8, width: 140 }} />
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
            scroll={{ x: 'max-content' }}
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
            disabledDate={(current) => {
              // 只限制不能选未来日期
              return current && current.isAfter(dayjs().endOf('day'));
            }}
            allowClear={false} style={{ marginLeft: 8, width: 140 }} />
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
      {/* 头部 */}
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Title level={4} style={{ margin: 0 }}>
          <CloudSyncOutlined /> 数据更新管理
          <Badge status={wsConnected ? 'success' : 'error'}
            text={<Text type="secondary" style={{ fontSize: 13 }}>
              {wsConnected ? 'WebSocket 已连接' : 'WebSocket 未连接'}
            </Text>}
            style={{ marginLeft: 12 }} />
        </Title>
      </div>

      {/* ==================== 更新配置 Tabs ==================== */}
      <Card size="small" style={{ marginBottom: 16 }} styles={{ body: { padding: '12px 0 0' } }}>
        <Tabs
          activeKey={activeTab}
          onChange={onTabChange}
          type="card"
          style={{ padding: '0 16px' }}
          items={[
            {
              key: 'DAILY',
              forceRender: true,
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
              forceRender: true,
              label: <span><PieChartOutlined /> 指数日线</span>,
              children: (
                <div style={{ padding: '16px 0' }}>
                  {renderIndexTab()}
                </div>
              ),
            },
            {
              key: 'DIVIDEND',
              forceRender: true,
              label: <span><DollarOutlined /> 分红除权</span>,
              children: <div style={{ padding: '16px 0' }}>{renderDividendTab()}</div>,
            },
            {
              key: 'FINANCIAL',
              forceRender: true,
              label: <span><BarChartOutlined /> 财务数据</span>,
              children: <div style={{ padding: '16px 0' }}>{renderFinancialTab()}</div>,
            },
            {
              key: 'SENTIMENT',
              forceRender: true,
              label: <span><ThunderboltOutlined /> 情绪数据</span>,
              children: <div style={{ padding: '16px 0' }}>{renderSentimentTab()}</div>,
            },
            {
              key: 'BIDASK',
              forceRender: true,
              label: <span><PieChartOutlined /> 内外盘</span>,
              children: <div style={{ padding: '16px 0' }}>{renderBidaskTab()}</div>,
            },
            {
              key: 'RESEARCH',
              forceRender: true,
              label: <span><FileTextOutlined /> 研报数据</span>,
              children: <div style={{ padding: '16px 0' }}>{renderResearchTab()}</div>,
            },
            {
              key: 'DELISTED',
              forceRender: false,
              label: <span><DeleteOutlined /> 退市管理</span>,
              children: <div style={{ padding: '16px 0' }}>{renderDelistedTab()}</div>,
            },
          ]}
        />
      </Card>
    </div>
  );
}

export default DataUpdate;
