import React, { useState, useEffect, useRef, useCallback } from 'react';
import {
  Card, Row, Col, Statistic, Table, Tag, Progress, Button, Select,
  Typography, Space, Badge, Modal, DatePicker, Form,
  Switch, message, Alert, Divider
} from 'antd';
import {
  ReloadOutlined, PlayCircleOutlined, CheckCircleOutlined,
  ClockCircleOutlined, ThunderboltOutlined, RiseOutlined,
  SyncOutlined, WarningOutlined, ClearOutlined, CodeOutlined
} from '@ant-design/icons';
import ReactECharts from 'echarts-for-react';
import dayjs from 'dayjs';
import { factorApi } from '../../api/index';

// WebSocket 连接地址（Vite 代理转发到后端 /api/ws-native）
// 注意：运算符优先级，必须用括号保证 protocol 完整拼接
const WS_URL = (window.location.protocol === 'https:' ? 'wss:' : 'ws:') + '//' + window.location.host + '/ws-native';

const { Text, Title } = Typography;
const { RangePicker } = DatePicker;

// 分类颜色 & 中文标签（保留用于 UI 展示）
const CATEGORY_COLORS = {
  TECHNICAL: '#38bdf8',
  MOMENTUM: '#fb923c',
  VOLATILITY: '#f472b6',
  VOLUME_PRICE: '#34d399',
  VALUE: '#a78bfa',
  FINANCIAL: '#22c55e',
  QUALITY: '#facc15',
  SENTIMENT: '#ef4444',
  LIQUIDITY: '#94a3b8',
};
const CATEGORY_LABELS = {
  TECHNICAL: '技术',
  MOMENTUM: '动量',
  VOLATILITY: '波动率',
  VOLUME_PRICE: '量价',
  VALUE:      '价值',
  FINANCIAL:   '财务',
  QUALITY:     '质量',
  SENTIMENT:   '情绪',
  LIQUIDITY:   '流动性',
  CHANTHEORY:  '缠论',
};

const TARGET_DAYS = 310;
const TARGET_STOCKS = 5280;
// 财务因子目标报告期数：每年4个季报窗口 × 3年覆盖 = 12个报告期
const TARGET_REPORT_PERIODS = 12;
// 财务/估值因子分类识别（VALUE类估值因子也按报告期计算，非日频）
const FINANCIAL_CATEGORIES = ['FINANCIAL', 'VALUE'];

function FactorMonitor() {
  const [factors, setFactors] = useState([]);
  const [factorsLoading, setFactorsLoading] = useState(true);
  const [monitorData, setMonitorData] = useState(null);
  const [loading, setLoading] = useState(false);
  const [interval, setIntervalVal] = useState(10000);
  const [trendHistory, setTrendHistory] = useState([]);
  const [speedSamples, setSpeedSamples] = useState([]);
  const [computeModal, setComputeModal] = useState(false);
  const [computeLoading, setComputeLoading] = useState(false);
  const [form] = Form.useForm();
  const [pageSize, setPageSize] = useState(20);
  // 通过 WebSocket 消息跟踪正在计算的因子（解决 cnt=0 时无法判断 isRunning 的问题）
  const [runningFactorCodes, setRunningFactorCodes] = useState(new Set());
  // 存储 WebSocket 实时进度（key=factorCode, value=progress 0-100）
  const [wsProgress, setWsProgress] = useState({});
  // 存储每个因子的最新 etaSec（来自 WebSocket，用于准确的预计剩余时间）
  // 用 useState 而非 useRef，确保更新时触发重渲染
  const [factorEtaSecData, setFactorEtaSecData] = useState({});
  // 🔍 监听 factorEtaSecData 变化，每次更新都打印到 Console
  useEffect(() => {
    console.log('[ETA] factorEtaSecData changed:', factorEtaSecData);
  }, [factorEtaSecData]);
  const timerRef = useRef(null);

  // WebSocket 计算日志（原生 WebSocket + 手动 STOMP 协议）
  // 用 sessionStorage 持久化，刷新页面后日志不丢失
  const [computeLogs, setComputeLogs] = useState(() => {
    try {
      const saved = sessionStorage.getItem('factor_compute_logs');
      return saved ? JSON.parse(saved) : [];
    } catch { return []; }
  });
  const [wsConnected, setWsConnected] = useState(false);
  const wsRef = useRef(null);
  const logContainerRef = useRef(null);
  const pushLogRef = useRef(null); // 稳定引用，避免闭包问题
  const fetchDebounceRef = useRef(null);          // debounce 刷新定时器

  // 添加日志（新日志在底部）
  const pushLog = useCallback((text, type = 'info', ts) => {
    const time = ts || dayjs().format('HH:mm:ss');
    setComputeLogs(prev => [
      ...prev.slice(-199),
      { id: Date.now() + '-' + Math.random().toString(36).slice(2), time, text, type },
    ]);
  }, []);

  // 保持 pushLog 的稳定引用
  useEffect(() => { pushLogRef.current = pushLog; }, [pushLog]);

  // 持久化计算日志到 sessionStorage（刷新页面后恢复，关闭标签页自动清空）
  useEffect(() => {
    try { sessionStorage.setItem('factor_compute_logs', JSON.stringify(computeLogs)); } catch { /* quota exceeded，忽略 */ }
  }, [computeLogs]);

  // 手动 STOMP 帧
  const stompFrame = useCallback((cmd, headers, body) => {
    let frame = cmd + '\n';
    if (headers) {
      Object.entries(headers).forEach(([k, v]) => { frame += k + ':' + v + '\n'; });
    }
    frame += '\n' + (body || '') + '\x00';
    return frame;
  }, []);

  // 解析 STOMP 帧
  const parseStomp = useCallback((data) => {
    const frames = [];
    let remaining = data;
    while (remaining.length > 0) {
      const nullIdx = remaining.indexOf('\x00');
      if (nullIdx === -1) break;
      const frameStr = remaining.substring(0, nullIdx);
      remaining = remaining.substring(nullIdx + 1);
      // 处理 \r\n 换行（某些代理可能引入 \r）
      const lines = frameStr.split('\n').map(l => l.endsWith('\r') ? l.slice(0, -1) : l);
      const command = lines[0];
      const headers = {};
      let bodyStart = 1;
      for (let i = 1; i < lines.length; i++) {
        if (lines[i] === '') { bodyStart = i + 1; break; }
        const colonIdx = lines[i].indexOf(':');
        if (colonIdx > 0) {
          headers[lines[i].substring(0, colonIdx)] = lines[i].substring(colonIdx + 1);
        }
      }
      const body = lines.slice(bodyStart).join('\n');
      frames.push({ command, headers, body });
    }
    return frames;
  }, []);

  // WebSocket 连接
  useEffect(() => {
    let buffer = '';
    const ws = new WebSocket(WS_URL);

    ws.onopen = () => {
      ws.send(stompFrame('CONNECT', {
        'accept-version': '1.2',
        'heart-beat': '10000,10000',
      }));
    };

    ws.onmessage = (evt) => {
      buffer += evt.data;
      const frames = parseStomp(buffer);
      const lastNullIdx = buffer.lastIndexOf('\x00');
      buffer = lastNullIdx >= 0 ? buffer.substring(lastNullIdx + 1) : buffer;

      // 调试：打印解析到的帧数
      if (frames.length > 0) {
        console.log('[WS] parsed frames:', frames.length, 'commands:', frames.map(f => f.command));
      }

      frames.forEach(frame => {
        if (frame.command === 'CONNECTED') {
          setWsConnected(true);
          // 订阅批量日志
          ws.send(stompFrame('SUBSCRIBE', {
            id: 'batch-log-sub',
            destination: '/topic/factor/batch-log',
          }));
          // 连上后主动查询后端正在计算的因子，同步状态
          factorApi.running().then(res => {
            const codes = res?.data || res;
            if (Array.isArray(codes) && codes.length > 0) {
              setRunningFactorCodes(new Set(codes));
            } else {
              setRunningFactorCodes(new Set());
            }
          }).catch(() => {});
        } else if (frame.command === 'MESSAGE') {
          try {
            if (!frame.body || frame.body.trim() === '') {
              console.warn('[WS] 收到空消息体');
              return;
            }
            const data = JSON.parse(frame.body);
            const ts = data.timestamp
              ? new Date(data.timestamp).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
              : dayjs().format('HH:mm:ss');
            const push = pushLogRef.current;
            if (!push) return;

            if (data.type === 'BATCH_START') {
              push(`🚀 批量计算已启动：${data.totalFactors} 个因子 × ${data.symbolCount} 只股票 | ${data.startDate} ~ ${data.endDate} | ${data.incremental ? '增量模式' : '全量模式'}`, 'info', ts);
            } else if (data.type === 'BATCH_SUBMITTED') {
              const sub = data.submitted?.length || 0;
              const skip = data.skipped?.length || 0;
              push(`📋 提交完成：${sub} 个已提交，${skip} 个跳过`, skip > 0 ? 'warning' : 'success', ts);
            } else if (data.type === 'FACTOR_PROGRESS') {
              // 🔍 调试：把完整 WebSocket 消息打印到浏览器 Console
              console.log('[WS] FACTOR_PROGRESS:', data);
              const icons = { COMPUTING: '🔢', DONE: '✅', TEST_START: '🧪', TESTING: '📊', TEST_DONE: '🎉', FAILED: '❌' };
              const icon = icons[data.stage] || 'ℹ️';
              const logType = data.stage === 'DONE' || data.stage === 'TEST_DONE' ? 'success' : data.stage === 'FAILED' ? 'error' : 'info';
              push(`${icon} [${data.factorCode}] ${data.message}`, logType, ts);
              // 通过 WebSocket 消息跟踪正在计算的因子（解决 cnt=0 时 isRunning 误判为 false 的问题）
              // 同时存储实时进度（data.progress）
              if (data.factorCode) {
                if (data.stage === 'COMPUTING') {
                  setRunningFactorCodes(prev => new Set([...prev, data.factorCode]));
                  // 存储实时进度（后端 sendProgress 的 progress 字段，0-100）
                  if (data.progress != null) {
                    setWsProgress(prev => ({ ...prev, [data.factorCode]: data.progress }));
                  }
                  // 存储实时 ETA（后端 sendProgress 的 etaSec 字段）
                  if (data.etaSec != null) {
                    setFactorEtaSecData(prev => ({ ...prev, [data.factorCode]: data.etaSec }));
                  }
                } else if (data.stage === 'DONE' || data.stage === 'FAILED' || data.stage === 'TEST_DONE') {
                  setRunningFactorCodes(prev => {
                    const next = new Set(prev);
                    next.delete(data.factorCode);
                    return next;
                  });
                  setWsProgress(prev => {
                    const next = { ...prev };
                    delete next[data.factorCode];
                    return next;
                  });
                  // 清除该因子的 ETA 缓存
                  setFactorEtaSecData(prev => { const next = { ...prev }; delete next[data.factorCode]; return next; });
                  // 计算完成后自动刷新列表状态（debounce 500ms，避免批量完成时频繁刷新）
                  clearTimeout(fetchDebounceRef.current);
                  fetchDebounceRef.current = setTimeout(() => fetchData(), 500);
                }
              }
            }
          } catch (e) {
            console.error('WS message parse error:', e);
          }
        }
      });
    };

    ws.onclose = () => {
      setWsConnected(false);
      // 后端可能已重启，清空残留的计算状态
      setRunningFactorCodes(new Set());
      setWsProgress({});
      setFactorEtaSecData({});
    };
    ws.onerror = () => {
      setWsConnected(false);
      setRunningFactorCodes(new Set());
      setWsProgress({});
      setFactorEtaSecData({});
    };

    wsRef.current = ws;
    return () => {
      clearTimeout(fetchDebounceRef.current);
      if (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING) {
        try { ws.send(stompFrame('DISCONNECT')); } catch {}
        ws.close();
      }
      wsRef.current = null;
    };
  }, [stompFrame, parseStomp]);

  // 初始化时加载因子定义
  useEffect(() => {
    (async () => {
      try {
        setFactorsLoading(true);
        const res = await factorApi.getAllDefinitions();
        const records = res.records || [];
        setFactors(records.map(r => ({
          code: r.factorCode,
          name: r.factorName,
          category: r.category,
          color: CATEGORY_COLORS[r.category] || '#8c8c8c',
        })));
      } catch (e) {
        console.error('获取因子定义失败:', e);
      } finally {
        setFactorsLoading(false);
      }
    })();
  }, []);

  // 拉取监控数据
  const fetchData = useCallback(async () => {
    // 保存当前滚动位置
    const scrollY = window.scrollY;
    try {
      setLoading(true);
      const res = await factorApi.monitor();
      const data = res;
      setMonitorData(data);

      // 每次刷新监控数据时，同步后端正在计算的因子状态（解决重启后状态不一致）
      try {
        const runningRes = await factorApi.running();
        const codes = runningRes?.data || runningRes || [];
        if (Array.isArray(codes)) {
          setRunningFactorCodes(new Set(codes));
        }
      } catch { /* 忽略，不影响主流程 */ }

      // 更新速度采样
      const now = Date.now();
      const total = data.totalRecords || 0;
      setSpeedSamples(prev => {
        const next = [...prev, { t: now, v: total }];
        return next.length > 6 ? next.slice(-6) : next;
      });

      // 更新趋势历史
      const ts = new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
      setTrendHistory(prev => {
        const next = [...prev, { ts, total }];
        return next.length > 40 ? next.slice(-40) : next;
      });
    } catch (e) {
      console.error('获取监控数据失败:', e);
    } finally {
      setLoading(false);
      // 恢复滚动位置
      window.scrollTo(0, scrollY);
    }
  }, []);

  // 设置定时刷新
  useEffect(() => {
    fetchData();
    timerRef.current = setInterval(fetchData, interval);
    return () => clearInterval(timerRef.current);
  }, [fetchData, interval]);




  // 自动滚动到底部
  useEffect(() => {
    if (logContainerRef.current) {
      logContainerRef.current.scrollTop = logContainerRef.current.scrollHeight;
    }
  }, [computeLogs]);

  // 构建因子统计 Map
  const statsMap = {};
  if (monitorData?.factors) {
    monitorData.factors.forEach(f => {
      // 后端返回 snake_case(factor_code)，统一转 camelCase 建索引
      const key = f.factorCode || f.factor_code || f.code;
      if (key) statsMap[key] = f;
    });
  }

  // 计算每个因子的进度
  const factorsWithStats = (factors || []).map(f => {
    const s = statsMap[f.code] || {};
    const cnt = Number(s.cnt) || 0;
    const days = Number(s.days) || 0;
    const stocks = Number(s.stocks) || 0;
    // 财务因子按报告期数量判断完成度；日频因子按交易日数量
    // fallback: category 为空时通过 factor_code 前缀识别（后端 selectFactorStats 未返回 category）
    const isFinancial = FINANCIAL_CATEGORIES.includes(f.category)
      || f.code.startsWith('FIN_')
      || f.code.startsWith('CHAN_');
    let pct, isDone;
    if (isFinancial) {
      // 财务因子：报告期数量 vs 目标报告期数（季报+年报约每年4期，2年=8期为合理目标）
      // 有数据即视为基本完成（财务因子随财报发布一次性写入，非日频累积）
      pct = days > 0 ? Math.min(100, Math.round(days / TARGET_REPORT_PERIODS * 100)) : 0;
      isDone = cnt > 0 && days >= 4; // 至少覆盖1年4个报告期即算完成
    } else {
      // 日频因子：交易日数量 vs 目标交易日数
      pct = days > 0 ? Math.min(100, Math.round(days / TARGET_DAYS * 100)) : 0;
      isDone = cnt > 0 && days >= TARGET_DAYS - 5;
    }
    // isRunning 只依赖后端真实状态（/running API + WebSocket），不再用数据完整性推断
    // 原因：后端重启后，有数据但没算完的因子会被误判为"计算中"
    const isRunning = runningFactorCodes.has(f.code);
    // 进度：正在计算用 WebSocket 实时进度；已完成显示100%；否则按天数比例
    const displayPct = isRunning ? (wsProgress[f.code] ?? pct) : (isDone ? 100 : pct);
    return { ...f, cnt, days, stocks, minDate: s.min_date || null, maxDate: s.max_date || null, pct: displayPct, isDone, isRunning };
  });

  const totalRecords = monitorData?.totalRecords || 0;
  // 正在计算的因子不算"已完成"（修复增量计算时 TURN20 等显示"已完成"的 bug）
  const doneCount = factorsWithStats.filter(f => f.isDone && !f.isRunning).length;
  const runningCount = factorsWithStats.filter(f => f.isRunning).length;
  const pendingCount = factorsWithStats.filter(f => !f.isDone && !f.isRunning).length;

  // 计算写入速度
  let speed = 0;
  let speedText = '--';
  if (speedSamples.length >= 2) {
    const first = speedSamples[0];
    const last = speedSamples[speedSamples.length - 1];
    const dt = (last.t - first.t) / 1000;
    if (dt > 0) speed = Math.max(0, Math.round((last.v - first.v) / dt));
  }
  if (speed > 0) {
    speedText = speed >= 10000 ? (speed / 10000).toFixed(1) + '万' : speed + '';
  } else if (runningCount > 0) {
    speedText = '计算中';
  } else {
    speedText = '--';
  }

  // 预计剩余时间（直接使用后端推送的 etaSec，不再自己瞎算）
  // 🔍 调试：打印 factorEtaSecData 当前值
  console.log('[ETA] factorEtaSecData:', factorEtaSecData);
  let etaText = '--';
  const allEta = Object.values(factorEtaSecData).filter(v => v != null);
  if (allEta.length > 0) {
    const maxEta = Math.max(...allEta);
    const hh = Math.floor(maxEta / 3600);
    const mm = Math.floor((maxEta % 3600) / 60);
    const ss = maxEta % 60;
    etaText = hh > 0 ? `${hh}时${mm}分` : mm > 0 ? `${mm}分${ss}秒` : `${ss}秒`;
  } else if (runningCount > 0) {
    etaText = '计算中';
  } else if (runningCount === 0 && pendingCount === 0 && doneCount > 0) {
    etaText = '已完成';
  }

  // 触发批量计算
  const handleBatchCompute = async () => {
    try {
      const vals = await form.validateFields();
      const factorCodes = vals.factorCodes;
      if (!factorCodes || factorCodes.length === 0) {
        message.warning('请至少选择一个因子');
        return;
      }
      if (factorCodes.length > 8) {
        message.error('最多同时计算 8 个因子，请减少选择');
        return;
      }
      const startDate = vals.dateRange?.[0]?.format('YYYY-MM-DD');
      const endDate = vals.dateRange?.[1]?.format('YYYY-MM-DD');
      const incremental = vals.incremental ?? true;
      const force = vals.force ?? false;

      setComputeLoading(true);
      const res = await factorApi.batchCompute(factorCodes, startDate, endDate, incremental, force);
      const result = res;
      message.success(
        `已提交 ${result.submitted?.length || 0} 个因子，跳过 ${result.skipped?.length || 0} 个`
      );
      setComputeModal(false);
      form.resetFields();
      setTimeout(fetchData, 2000);
    } catch (e) {
      if (e?.errorFields) return; // form validation error
      message.error('触发计算失败，请稍后重试');
    } finally {
      setComputeLoading(false);
    }
  };

  // 表格列定义
  const columns = [
    {
      title: '因子代码',
      dataIndex: 'code',
      width: 160,
      render: (code, row) => (
        <span style={{ fontFamily: 'monospace', color: row.color, fontWeight: 600 }}>{code}</span>
      ),
    },
    {
      title: '名称',
      dataIndex: 'name',
      width: 120,
    },
    {
      title: '分类',
      dataIndex: 'category',
      width: 80,
      filters: Object.entries(CATEGORY_LABELS).map(([k, v]) => ({ text: v, value: k })),
      onFilter: (val, row) => row.category === val,
      render: cat => (
        <Tag color={CATEGORY_COLORS[cat]} style={{ fontSize: 11 }}>
          {CATEGORY_LABELS[cat] || cat}
        </Tag>
      ),
    },
    {
      title: '记录数',
      dataIndex: 'cnt',
      width: 100,
      sorter: (a, b) => a.cnt - b.cnt,
      render: v => v > 0 ? <span style={{ fontFamily: 'monospace' }}>{v >= 10000 ? (v / 10000).toFixed(1) + '万' : v.toLocaleString()}</span> : <Text type="secondary">--</Text>,
    },
    {
      title: '交易日',
      dataIndex: 'days',
      width: 80,
      sorter: (a, b) => a.days - b.days,
      render: v => v > 0 ? v : <Text type="secondary">--</Text>,
    },
    {
      title: '日期跨度',
      key: 'dateSpan',
      width: 160,
      render: (_, row) => {
        const { minDate, maxDate } = row;
        if (!minDate && !maxDate) return <Text type="secondary">--</Text>;
        if (minDate && maxDate && minDate === maxDate) {
          return <span style={{ fontFamily: 'monospace', color: '#94a3b8', fontSize: 12 }}>{minDate}</span>;
        }
        if (minDate && maxDate) {
          // 同年时省略年份
          const showMin = minDate;
          const showMax = minDate.slice(0, 4) === maxDate.slice(0, 4)
            ? maxDate.slice(5)
            : maxDate;
          return <span style={{ fontFamily: 'monospace', color: '#94a3b8', fontSize: 12 }}>{showMin}~{showMax}</span>;
        }
        // 只有其中一个日期
        return <span style={{ fontFamily: 'monospace', color: '#94a3b8', fontSize: 12 }}>{minDate || '--'} ~ {maxDate || '--'}</span>;
      },
    },
    {
      title: '进度',
      dataIndex: 'pct',
      width: 180,
      sorter: (a, b) => a.pct - b.pct,
      render: (pct, row) => (
        // 正在计算：优先用 WebSocket 实时进度，不用 cnt 判断是否有数据
        row.isRunning
          ? <Space size={4}>
              <Progress percent={Math.round(pct)} strokeColor={row.color} style={{ width: 100, marginBottom: 0 }}
                format={p => <span style={{ fontSize: 11 }}>{p}%</span>} />
              <span style={{ fontSize: 10, color: '#8c8c8c' }}>计算中</span>
            </Space>
          : row.cnt > 0
            ? <Progress percent={Math.round(pct)} strokeColor={row.color} style={{ width: 140, marginBottom: 0 }}
                format={p => <span style={{ fontSize: 11 }}>{p}%</span>} />
            : <Text type="secondary" style={{ fontSize: 12 }}>未计算</Text>
      ),
    },
    {
      title: '状态',
      dataIndex: 'isDone',
      width: 90,
      filters: [
        { text: '已完成', value: 'done' },
        { text: '计算中', value: 'running' },
        { text: '待计算', value: 'pending' },
      ],
      onFilter: (val, row) => {
        if (val === 'done') return row.isDone;
        if (val === 'running') return row.isRunning;
        return !row.isDone && !row.isRunning;
      },
      render: (_, row) => {
        // 正在计算的因子优先显示"计算中"（覆盖 isDone=true 的增量计算场景）
        if (row.isRunning) return <Badge status="processing" text={<Text style={{ fontSize: 12, color: '#38bdf8' }}>计算中</Text>} />;
        if (row.isDone) return <Badge status="success" text={<Text style={{ fontSize: 12, color: '#22c55e' }}>已完成</Text>} />;
        return <Badge status="default" text={<Text style={{ fontSize: 12, color: '#94a3b8' }}>待计算</Text>} />;
      },
    },
  ];

  // ECharts 趋势图配置
  const chartOption = {
    tooltip: { trigger: 'axis', formatter: params => {
      const p = params[0];
      return `${p.name}<br/>总记录：${p.value >= 10000 ? (p.value / 10000).toFixed(1) + '万' : p.value}`;
    }},
    grid: { left: 50, right: 16, top: 20, bottom: 30 },
    xAxis: {
      type: 'category',
      data: trendHistory.map(d => d.ts),
      axisLabel: { color: '#8c8c8c', fontSize: 10 },
      axisLine: { lineStyle: { color: '#f0f0f0' } },
    },
    yAxis: {
      type: 'value',
      axisLabel: {
        color: '#1677ff', fontSize: 10,
        formatter: v => v >= 10000 ? (v / 10000).toFixed(0) + '万' : v,
      },
      splitLine: { lineStyle: { color: '#f5f5f5' } },
    },
    series: [{
      type: 'line',
      data: trendHistory.map(d => d.total),
      smooth: true,
      lineStyle: { color: '#1677ff', width: 2 },
      itemStyle: { color: '#1677ff' },
      areaStyle: { color: 'rgba(22,119,255,0.08)' },
      symbol: 'circle',
      symbolSize: 4,
    }],
  };

  // 未完成因子列表（用于批量触发）
  const pendingFactors = factorsWithStats.filter(f => !f.isDone);

  return (
    <div style={{ padding: '0 4px' }}>
      {/* 顶部操作栏 */}
      <Row justify="space-between" align="middle" style={{ marginBottom: 16 }}>
        <Col>
          <Space>
            <Title level={4} style={{ margin: 0 }}>因子计算</Title>
            <Badge status={loading ? 'processing' : 'success'} text={loading ? '刷新中...' : '实时监控'} />
          </Space>
        </Col>
        <Col>
          <Space>
            <Text type="secondary" style={{ fontSize: 13 }}>刷新间隔：</Text>
            <Select
              value={interval}
              onChange={v => { setIntervalVal(v); clearInterval(timerRef.current); timerRef.current = setInterval(fetchData, v); }}
              size="small"
              options={[
                { value: 5000, label: '5秒' },
                { value: 10000, label: '10秒' },
                { value: 30000, label: '30秒' },
                { value: 60000, label: '1分钟' },
              ]}
              style={{ width: 80 }}
            />
            <Button size="small" icon={<ReloadOutlined />} onClick={fetchData} loading={loading}>刷新</Button>
            <Button
              size="small"
              type="primary"
              icon={<PlayCircleOutlined />}
              onClick={() => setComputeModal(true)}
            >
              触发计算
            </Button>
          </Space>
        </Col>
      </Row>

      {/* 统计卡片 */}
      <Row gutter={[12, 12]} style={{ marginBottom: 16 }}>
        <Col span={5}>
          <Card size="small" hoverable>
            <Statistic
              title="总记录数"
              value={totalRecords >= 10000 ? (totalRecords / 10000).toFixed(1) : totalRecords}
              suffix={totalRecords >= 10000 ? '万' : '条'}
              valueStyle={{ color: '#1677ff' }}
              prefix={<RiseOutlined />}
            />
          </Card>
        </Col>
        <Col span={5}>
          <Card size="small" hoverable>
            <Statistic
              title="已完成因子"
              value={doneCount}
              suffix={`/ ${(factors || []).length}`}
              valueStyle={{ color: '#52c41a' }}
              prefix={<CheckCircleOutlined />}
            />
          </Card>
        </Col>
        <Col span={5}>
          <Card size="small" hoverable>
            <Statistic
              title="计算中"
              value={runningCount}
              valueStyle={{ color: '#1677ff' }}
              prefix={<SyncOutlined spin={runningCount > 0} />}
            />
          </Card>
        </Col>
        <Col span={5}>
          <Card size="small" hoverable>
            <Statistic
              title="写入速度"
              formatter={() => <span>{speedText}{speed > 0 ? ' 条/秒' : ''}</span>}
              valueStyle={{ color: speed > 0 ? '#722ed1' : '#8c8c8c' }}
              prefix={<ThunderboltOutlined />}
            />
          </Card>
        </Col>
        <Col span={4}>
          <Card size="small" hoverable>
            <Statistic
              title="预计剩余"
              formatter={() => <span>{etaText}</span>}
              valueStyle={{ color: runningCount > 0 ? '#fa8c16' : doneCount > 0 ? '#52c41a' : '#8c8c8c' }}
              prefix={<ClockCircleOutlined />}
            />
          </Card>
        </Col>
      </Row>

      {/* 待计算提示 */}
      {!factorsLoading && pendingCount > 0 && (
        <Alert
          type="warning"
          showIcon
          icon={<WarningOutlined />}
          message={`还有 ${pendingCount} 个因子尚未计算`}
          description={
            <span>
              未计算因子包括：{pendingFactors.slice(0, 8).map(f => f.code).join(', ')}{pendingFactors.length > 8 ? ` 等${pendingFactors.length}个` : ''}。
              <Button type="link" size="small" onClick={() => setComputeModal(true)} style={{ padding: '0 4px' }}>
                立即触发计算 →
              </Button>
            </span>
          }
          style={{ marginBottom: 16 }}
        />
      )}

      {/* 左列：趋势图+日志 / 右列：分类统计 */}
      <Row gutter={[12, 12]} style={{ marginBottom: 16 }}>
        <Col span={14}>
          <Card size="small" title="📈 数据量趋势" styles={{ body: { padding: '12px 16px' } }}>
            <div style={{ height: 160 }}>
              {trendHistory.length > 1
                ? <ReactECharts option={chartOption} style={{ height: '100%', width: '100%' }} />
                : <div style={{ height: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#bfbfbf' }}>等待数据...</div>
              }
            </div>
          </Card>
          <Card
            size="small"
            style={{ marginTop: 12 }}
            title={
              <Space>
                <CodeOutlined />
                <span>计算日志</span>
                <Badge
                  status={wsConnected ? 'success' : 'error'}
                  text={wsConnected ? '已连接' : '未连接'}
                  style={{ fontSize: 11 }}
                />
                {computeLogs.length > 0 && (
                  <Tag style={{ fontSize: 10, margin: 0 }}>{computeLogs.length} 条</Tag>
                )}
              </Space>
            }
            extra={
              computeLogs.length > 0 ? (
                <Button
                  size="small"
                  icon={<ClearOutlined />}
                  onClick={() => setComputeLogs([])}
                >
                  清空
                </Button>
              ) : null
            }
            styles={{ body: { padding: '8px 12px' } }}
          >
            <div
              ref={logContainerRef}
              style={{
                height: 300,
                overflow: 'auto',
                background: '#1a1a2e',
                borderRadius: 6,
                padding: '8px 12px',
                fontFamily: "'Cascadia Code', 'Fira Code', 'JetBrains Mono', monospace",
                fontSize: 12,
                lineHeight: '20px',
              }}
            >
              {computeLogs.length === 0 ? (
                <div style={{ color: '#4a4a6a', textAlign: 'center', padding: '60px 0' }}>
                  <CodeOutlined style={{ fontSize: 24, marginBottom: 8, display: 'block', opacity: 0.3 }} />
                  等待计算任务...<br />
                  <span style={{ fontSize: 11 }}>触发因子计算后，实时日志将在此展示</span>
                </div>
              ) : (
                computeLogs.map(log => (
                  <div key={log.id} style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>
                    <span style={{ color: '#6a6a8a', marginRight: 8 }}>{log.time}</span>
                    <span style={{
                      color: log.type === 'error' ? '#ff6b6b'
                        : log.type === 'success' ? '#69db7c'
                        : log.type === 'warning' ? '#ffd43b'
                        : '#c4c4e0',
                    }}>{log.text}</span>
                  </div>
                ))
              )}
            </div>
          </Card>
        </Col>
        <Col span={10}>
          <Card size="small" title="📊 分类统计" styles={{ body: { padding: '8px 16px' } }}>
            {factorsLoading ? (
              <div style={{ textAlign: 'center', padding: '60px 0', color: '#bfbfbf' }}>
                <SyncOutlined spin style={{ fontSize: 16, marginBottom: 8, display: 'block' }} />
                加载因子定义中...
              </div>
            ) : (
              Object.entries(CATEGORY_LABELS).map(([cat, label]) => {
                const total = factorsWithStats.filter(f => f.category === cat).length;
                const done = factorsWithStats.filter(f => f.category === cat && f.isDone).length;
                const pct = total > 0 ? Math.round(done / total * 100) : 0;
                return (
                  <div key={cat} style={{ marginBottom: 8 }}>
                    <Row justify="space-between" style={{ marginBottom: 2 }}>
                      <Col>
                        <Text style={{ fontSize: 12, color: CATEGORY_COLORS[cat] }}>{label}</Text>
                      </Col>
                      <Col>
                        <Text type="secondary" style={{ fontSize: 11 }}>{done}/{total}</Text>
                      </Col>
                    </Row>
                    <Progress
                      percent={pct}
                      size={[null, 5]}
                      strokeColor={CATEGORY_COLORS[cat]}
                      showInfo={false}
                    />
                  </div>
                );
              })
            )}
          </Card>
        </Col>
      </Row>

      {/* 因子详情表格 */}
      <Card size="small" title="📋 因子详情" extra={
        <Text type="secondary" style={{ fontSize: 12 }}>共 {(factors || []).length} 个因子</Text>
      }>
        {factorsLoading ? (
          <div style={{ textAlign: 'center', padding: '40px 0', color: '#bfbfbf' }}>
            <SyncOutlined spin style={{ fontSize: 16, marginBottom: 8, display: 'block' }} />
            加载因子定义中...
          </div>
        ) : (
          <Table
            columns={columns}
            dataSource={factorsWithStats}
            rowKey="code"
            size="small"
            pagination={{ pageSize, showSizeChanger: true, pageSizeOptions: ['20', '50', '100'], onShowSizeChange: (_, size) => setPageSize(size) }}
            rowClassName={row => row.isDone ? '' : row.isRunning ? '' : 'row-pending'}
          />
        )}
      </Card>

      {/* 批量触发计算 Modal */}
      <Modal
        title={<Space><PlayCircleOutlined />触发因子批量计算</Space>}
        open={computeModal}
        forceRender
        onCancel={() => { setComputeModal(false); form.resetFields(); }}
        onOk={handleBatchCompute}
        confirmLoading={computeLoading}
        okText="开始计算"
        cancelText="取消"
        width={680}
      >
        <Alert
          type="info"
          showIcon
          message="批量计算会在后台异步执行，每个因子使用独立线程，不会阻塞界面。"
          style={{ marginBottom: 16 }}
        />
        <Form form={form} layout="vertical" initialValues={{
          incremental: true,
          dateRange: [dayjs('2025-01-01'), dayjs()],
          factorCodes: pendingFactors.slice(0, 8).map(f => f.code),
        }}>
          <Form.Item label="选择因子" name="factorCodes" rules={[
            { required: true, message: '请至少选择一个因子' },
            { validator: (_, val) => (!val || val.length <= 8) ? Promise.resolve() : Promise.reject('最多同时计算 8 个因子') }
          ]}>
            <Select
              mode="multiple"
              placeholder="选择要计算的因子（最多 8 个）"
              maxTagCount={5}
              maxCount={8}
              style={{ width: '100%' }}
              options={(factors || []).map(f => ({
                value: f.code,
                label: (
                  <Space size={4}>
                    <Tag color={CATEGORY_COLORS[f.category]} style={{ fontSize: 10, margin: 0 }}>
                      {CATEGORY_LABELS[f.category]}
                    </Tag>
                    <span style={{ fontFamily: 'monospace', fontSize: 12 }}>{f.code}</span>
                    <Text type="secondary" style={{ fontSize: 11 }}>{f.name}</Text>
                  </Space>
                ),
              }))}
            />
          </Form.Item>
          <Row gutter={16}>
            <Col span={8}>
              <Button
                size="small"
                onClick={() => form.setFieldsValue({ factorCodes: pendingFactors.map(f => f.code) })}
              >
                选择全部未完成({pendingCount})
              </Button>
            </Col>
            <Col span={8}>
              <Button
                size="small"
                onClick={() => form.setFieldsValue({ factorCodes: (factors || []).map(f => f.code) })}
              >
                选择全部({(factors || []).length})
              </Button>
            </Col>
            <Col span={8}>
              <Button size="small" onClick={() => form.setFieldsValue({ factorCodes: [] })}>清空</Button>
            </Col>
          </Row>
          {/* 按分类选择未完成因子 */}
          <div style={{ marginTop: 8, display: 'flex', flexWrap: 'wrap', gap: 6 }}>
            {Object.entries(CATEGORY_LABELS).map(([cat, label]) => {
              const pending = pendingFactors.filter(f => f.category === cat);
              if (pending.length === 0) return null;
              return (
                <Tag
                  key={cat}
                  color={CATEGORY_COLORS[cat]}
                  style={{ cursor: 'pointer', userSelect: 'none' }}
                  onClick={() => {
                    const current = form.getFieldValue('factorCodes') || [];
                    const codes = pending.map(f => f.code);
                    // 切换：如果该分类已全部选中则移除，否则追加
                    const allSelected = codes.every(c => current.includes(c));
                    const next = allSelected
                      ? current.filter(c => !codes.includes(c))
                      : [...new Set([...current, ...codes])].slice(0, 8);
                    form.setFieldsValue({ factorCodes: next });
                  }}
                >
                  {label}({pending.length})
                </Tag>
              );
            })}
          </div>
          <Divider style={{ margin: '12px 0' }} />
          <Form.Item label="计算日期范围" name="dateRange" rules={[{ required: true }]}>
            <RangePicker
              style={{ width: '100%' }}
              disabledDate={(current) => current && (current.isBefore(dayjs('2025-01-01')) || current.isAfter(dayjs()))}
            />
          </Form.Item>
          <Form.Item
            label="增量计算（跳过已有数据的日期）"
            name="incremental"
            valuePropName="checked"
          >
            <Switch checkedChildren="增量" unCheckedChildren="全量" />
          </Form.Item>
          <Form.Item
            label="强制重算（忽略已有数据，全量覆盖）"
            name="force"
            valuePropName="checked"
          >
            <Switch checkedChildren="强制" unCheckedChildren="正常" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}

export default FactorMonitor;
