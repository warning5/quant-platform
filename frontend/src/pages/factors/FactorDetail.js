import React, { useEffect, useState, useRef } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Card, Descriptions, Tag, Button, Space, Tabs, Table,
  Row, Col, Typography, Spin, Modal, DatePicker,
  Input, App, Progress, Divider, Select, Popconfirm, Alert, Timeline, Badge
} from 'antd';
import {
  ArrowLeftOutlined, EditOutlined,
  BarChartOutlined, ExperimentOutlined, RiseOutlined,
  LineChartOutlined, TableOutlined, SearchOutlined,
  CalculatorOutlined, DeleteOutlined, CheckCircleOutlined,
  CloseCircleOutlined, MinusCircleOutlined, SyncOutlined,
  InfoCircleOutlined, WarningOutlined, StopOutlined
} from '@ant-design/icons';
import { Client } from '@stomp/stompjs';
import ReactECharts from '../../components/LazyECharts';
import dayjs from 'dayjs';
import { factorApi } from '../../api';

const WS_URL = '/ws-native';

const { Title, Text } = Typography;
const { RangePicker } = DatePicker;
const { Option } = Select;

const fmt4    = v => v != null ? (+v).toFixed(4) : '-';
const fmt2    = v => v != null ? (+v).toFixed(2) : '-';
const fmtPct  = v => v != null ? `${(+v * 100).toFixed(2)}%` : '-';
const fmtPct4 = v => v != null ? `${(+v * 100).toFixed(4)}%` : '-';
const signColor = v => +v > 0 ? '#cf1322' : +v < 0 ? '#389e0d' : '#595959';

// 分组颜色
const GROUP_COLORS = ['#5470c6', '#91cc75', '#fac858', '#ee6666', '#73c0de'];
const GROUP_NAMES  = ['分组1', '分组2', '分组3', '分组4', '分组5'];

export default function FactorDetail() {
  const { message } = App.useApp();
  const { id } = useParams();
  const navigate = useNavigate();

  const [factor, setFactor]           = useState(null);
  const [testReports, setTestReports] = useState([]);
  const [loading, setLoading]         = useState(true);

  // ── 因子值统计（用于前置校验）
  const [valueCount, setValueCount]   = useState(null);   // null=加载中，0=未计算

  // ── 计算进度（用于自动计算时展示）
  const [computing, setComputing]           = useState(false);  // 正在计算中
  const [computeDone, setComputeDone]       = useState(false);  // 曾经完成过计算（计算完成后切换到检测视图）
  const [computeProgress, setComputeProgress] = useState(0);
  const [computeLogs, setComputeLogs]       = useState([]);     // [{time, text, type}]
  const computeStompRef = useRef(null);

  // ── 测试 modal & 进度
  const [testModal, setTestModal]     = useState(false);
  const [testLoading, setTestLoading] = useState(false);
  const [testDates, setTestDates]     = useState([dayjs('2025-01-01'), dayjs('2025-12-31')]);
  const [testName, setTestName]       = useState('因子测试');
  const [testStockPool, setTestStockPool]       = useState('ALL_A');
  const [testRebalanceFreq, setTestRebalanceFreq] = useState('DAILY');
  const [testProgress, setTestProgress]       = useState(0);
  const [testLogs, setTestLogs]               = useState([]);     // 检测日志
  const [runningReportId, setRunningReportId] = useState(null);
  const testPollRef = useRef(null);
  const testStompRef = useRef(null);
  const testWsDataFlag = useRef(false);  // WebSocket 是否收到过真实数据
  const testPollStartRef = useRef(null); // 轮询开始时间

  // ── 报告选择
  const [selectedReport, setSelectedReport] = useState(null);
  const [selectedRowKeys, setSelectedRowKeys] = useState([]);
  const [deleteLoading, setDeleteLoading]   = useState({});
  const [batchDeleteLoading, setBatchDeleteLoading] = useState(false);

  // ── 因子值查看
  const [symbols, setSymbols]             = useState([]);   // [{symbol, name}]
  const [symbolsLoading, setSymbolsLoading] = useState(false);
  const [seriesSymbol, setSeriesSymbol]   = useState(null);
  const searchSymbolTimer = useRef(null);
  const [seriesDates, setSeriesDates]     = useState([dayjs('2025-01-01'), dayjs('2025-12-31')]);
  const [seriesData, setSeriesData]       = useState([]);
  const [seriesLoading, setSeriesLoading] = useState(false);
  const [crossDate, setCrossDate]         = useState(dayjs('2024-01-02'));
  const [crossData, setCrossData]         = useState([]);
  const [crossLoading, setCrossLoading]   = useState(false);

  // ── 加载数据（聚合接口：因子详情 + 报告列表 + 值数量，一次 RTT）
  const loadFactor = () => {
    return factorApi.getInit(id).then(res => {
      const d = res || {};
      setFactor(d.factor || null);
      setTestReports(d.reports || []);
      setValueCount(d.valueCount ?? 0);
    }).finally(() => setLoading(false));
  };

  const loadValueCount = () => {
    factorApi.getValueCount(id).then(res => {
      setValueCount(res?.count ?? 0);
    }).catch(() => setValueCount(0));
  };

  useEffect(() => {
    loadFactor();
  }, [id]);

  // 加载因子有数据的股票列表（带名称），支持关键词搜索
  const loadFactorSymbols = (keyword) => {
    if (!factor) return;
    setSymbolsLoading(true);
    factorApi.getFactorSymbols(factor.factorCode, keyword || '')
      .then(res => {
        const list = res || [];
        setSymbols(list);
        // 首次加载时默认选中第一只
        if (!keyword && list.length > 0 && !seriesSymbol) {
          setSeriesSymbol(list[0].symbol);
        }
      })
      .catch(() => setSymbols([]))
      .finally(() => setSymbolsLoading(false));
  };

  // ── 因子值查看 Tab 的懒加载标记
  const [valuesTabVisited, setValuesTabVisited] = useState(false);

  // factor 加载后，仅在用户访问过因子值 Tab 时才预加载股票列表
  useEffect(() => {
    if (factor?.factorCode && valuesTabVisited) {
      loadFactorSymbols('');
    }
  }, [factor?.factorCode, valuesTabVisited]);

  // ── 清理 WebSocket（组件卸载）
  useEffect(() => {
    return () => {
      computeStompRef.current?.deactivate();
      testStompRef.current?.deactivate();
      if (testPollRef.current) clearInterval(testPollRef.current);
    };
  }, []);

  // ── 推送计算日志条目
  const pushLog = (text, type = 'info') => {
    setComputeLogs(prev => [
      { id: Date.now() + Math.random(), time: dayjs().format('HH:mm:ss'), text, type },
      ...prev.slice(0, 49),   // 保留最近50条
    ]);
  };

  // ── 推送检测日志条目
  const pushTestLog = (text, type = 'info') => {
    setTestLogs(prev => [
      { id: Date.now() + Math.random(), time: dayjs().format('HH:mm:ss'), text, type },
      ...prev.slice(0, 49),   // 保留最近50条
    ]);
  };

  // ── 推送带阶段的检测日志
  const pushTestLogWithStage = (stage, text, type = 'info') => {
    const stageIcons = {
      'PREPARE': '📋',
      'COMPUTE': '🔢',
      'IC_TEST': '📊',
      'GROUP_TEST': '📈',
      'STATS': '📉',
      'DONE': '✅',
      'ERROR': '❌'
    };
    const prefix = stageIcons[stage] || 'ℹ️';
    pushTestLog(`${prefix} ${text}`, type);
  };

  // ── 运行因子测试
  const handleTest = () => {
    setTestLoading(true);
    setTestModal(false);  // 立即关闭弹窗

    factorApi.test(id, {
      startDate:     testDates[0].format('YYYY-MM-DD'),
      endDate:       testDates[1].format('YYYY-MM-DD'),
      testName,
      stockPool:     testStockPool,
      rebalanceFreq: testRebalanceFreq,
    }).then(res => {
      message.success('检测任务已提交，正在运行...');
      const reportId = res.id;
      setRunningReportId(reportId);
      setTestProgress(5);
      setTestLogs([]);
      setComputing(false);
      setComputeDone(false);
      setComputeProgress(0);
      setComputeLogs([]);
      pushTestLog('📌 检测任务已提交', 'info');
      // 立即加载列表并选中新建的行，然后统一走 handleStartMonitoring 建立 WS
      loadFactor().then(() => {
        factorApi.getTestReport(reportId).then(r => {
          setSelectedReport(r);
          // 统一通过 handleStartMonitoring 建立 WebSocket，避免重复连接
          if (factor?.factorCode) {
            handleStartMonitoring(r, { keepLogs: true });
          }
        });
      });
      if (testPollRef.current) clearInterval(testPollRef.current);
        }).finally(() => setTestLoading(false));
  };

  // ── 行内：直接对某条报告再次运行测试
  const handleRowRetest = (report) => {
    setTestName(report.testName || '因子测试');
    setTestDates([dayjs(report.startDate), dayjs(report.endDate)]);
    setTestStockPool(report.stockPool || 'ALL_A');
    setTestRebalanceFreq(report.rebalanceFreq || 'DAILY');
    setTestModal(true);
  };

  const handleDeleteReport = (reportId) => {
    setDeleteLoading(prev => ({ ...prev, [reportId]: true }));
    factorApi.deleteTestReport(reportId).then(() => {
      message.success('报告已删除');
      if (selectedReport?.id === reportId) setSelectedReport(null);
      loadFactor();
    }).finally(() => setDeleteLoading(prev => ({ ...prev, [reportId]: false })));
  };

  const handleStopTest = (reportId) => {
    setDeleteLoading(prev => ({ ...prev, [reportId]: true }));
    factorApi.deleteTestReport(reportId).then(() => {
      message.success('检测已停止，数据已删除');
      if (selectedReport?.id === reportId) {
        setSelectedReport(null);
        setTestLogs([]);
      }
      if (runningReportId === reportId) {
        setRunningReportId(null);
        setTestProgress(0);
        testStompRef.current?.deactivate();
      }
      loadFactor();
    }).finally(() => setDeleteLoading(prev => ({ ...prev, [reportId]: false })));
  };

  const handleBatchDelete = () => {
    if (selectedRowKeys.length === 0) {
      message.warning('请先选择要删除的检测报告');
      return;
    }

    // 检查是否包含运行中的检测
    const runningReports = testReports.filter(r =>
      selectedRowKeys.includes(r.id) && (r.status === 'RUNNING' || r.status === 'PENDING')
    );

    if (runningReports.length > 0) {
      const runningNames = runningReports.map(r => r.testName || `#${r.id}`).join('、');
      Modal.confirm({
        title: '确认停止并删除运行中的检测？',
        content: `选中的检测中包含运行中的任务：${runningNames}\n停止后将删除当前的计算数据，此操作不可恢复。`,
        okText: '确认停止',
        cancelText: '取消',
        okButtonProps: { danger: true },
        onOk: () => {
          doBatchDelete(selectedRowKeys);
        }
      });
    } else {
      Modal.confirm({
        title: '确认批量删除选中的检测报告？',
        content: `共 ${selectedRowKeys.length} 个检测报告将被删除，此操作不可恢复。`,
        okText: '确认删除',
        cancelText: '取消',
        okButtonProps: { danger: true },
        onOk: () => {
          doBatchDelete(selectedRowKeys);
        }
      });
    }
  };

  const doBatchDelete = (reportIds) => {
    setBatchDeleteLoading(true);
    Promise.all(reportIds.map(id => factorApi.deleteTestReport(id)))
      .then(() => {
        message.success(`成功删除 ${reportIds.length} 个检测报告`);
        setSelectedReport(null);
        setTestLogs([]);
        setRunningReportId(null);
        setTestProgress(0);
        testStompRef.current?.deactivate();
        setSelectedRowKeys([]);
        loadFactor();
      })
      .catch(() => message.error('批量删除失败，请稍后重试'))
      .finally(() => setBatchDeleteLoading(false));
  };

  // ── 启动对运行中检测的监听
  const handleStartMonitoring = (report, { keepLogs = false } = {}) => {
    // 清理旧的WebSocket连接
    if (testStompRef.current) {
      testStompRef.current.deactivate();
    }
    
    // 设置进度和日志
    setRunningReportId(report.id);
    setTestProgress(5);
    if (!keepLogs) setTestLogs([]);
    setComputing(false);
    setComputeDone(false);
    setComputeProgress(0);
    setComputeLogs([]);
    pushTestLog(keepLogs ? '🔌 WebSocket 已连接，开始监听检测进度' : '📌 开始监听检测进度', 'info');

    // 启动WebSocket监听
    const testClient = new Client({
      brokerURL: WS_URL,
      debug: str => console.log('[TEST STOMP]', str),
      onConnect: () => {
        pushTestLog('🔌 WebSocket 已连接，开始监听检测进度', 'info');
        testClient.subscribe(`/topic/factor/${factor.factorCode}`, msg => {
          const data = JSON.parse(msg.body);

          // ── 因子值计算阶段 ──
          if (data.stage === 'COMPUTING') {
            testWsDataFlag.current = true;  // 收到计算进度，也算真实数据
            setComputing(true);
            setComputeProgress(data.progress || 0);
            const msgText = data.message || '正在计算因子值...';
            // 去重：避免相同进度百分比重复添加日志
            setComputeLogs(prev => {
              const last = prev[0]?.text || '';
              if (last.includes(`${data.progress}%`)) return prev;
              return [
                { id: Date.now() + Math.random(), time: dayjs().format('HH:mm:ss'), text: msgText, type: 'info' },
                ...prev.slice(0, 49),
              ];
            });
            if (data.progress >= 100) {
              setComputing(false);
              setComputeDone(true);
              setValueCount(null);
              loadValueCount();
              pushTestLog('✅ 因子值计算完成，开始检测', 'success');
            }
            return;
          }

          if (data.stage?.startsWith('TEST')) {
            testWsDataFlag.current = true;  // 收到真实数据，标记
            // 如果刚从计算阶段切到检测阶段，标记计算完成
            if (computing) {
              setComputing(false);
              setComputeDone(true);
              setValueCount(null);
              loadValueCount();
            } else {
              setComputing(false);
              setComputeDone(true);
            }
            if (data.progress != null) {
              setTestProgress(data.progress);
            }

            // 根据不同的阶段添加详细的日志信息
            if (data.stage === 'TEST_START') {
              // 数据准备阶段
              if (data.message.includes('获取交易日期完成')) {
                const match = data.message.match(/共(\d+)个交易日/);
                pushTestLogWithStage('PREPARE', `📅 获取交易日期完成，共 ${match?.[1] || '?'} 个交易日`, 'info');
              } else if (data.message.includes('调仓频率过滤完成')) {
                const match = data.message.match(/有效交易日(\d+)个/);
                pushTestLogWithStage('PREPARE', `⏰ 调仓频率过滤完成，有效交易日 ${match?.[1] || '?'} 个`, 'info');
              } else if (data.message.includes('股票池加载完成')) {
                const match = data.message.match(/(\d+)只股票|全A（不限制）/);
                const poolText = match?.[1] ? match[1] + ' 只股票' : (match?.[0] || '全A');
                pushTestLogWithStage('PREPARE', `📊 股票池加载完成，${poolText}`, 'info');
              } else {
                pushTestLogWithStage('PREPARE', data.message || '开始准备检测数据...', 'info');
              }
            } else if (data.stage === 'TESTING') {
              // 检测计算阶段
              if (data.message) {
                if (data.message.includes('计算IC统计指标')) {
                  pushTestLogWithStage('IC_TEST', '📊 开始计算IC统计指标（均值、标准差、IR、显著性等）...', 'info');
                } else if (data.message.includes('IC统计完成')) {
                  const match = data.message.match(/样本数(\d+)/);
                  pushTestLogWithStage('IC_TEST', `✅ IC统计完成，样本数 ${match?.[1] || '?'}`, 'success');
                } else if (data.message.includes('主动指标计算完成')) {
                  pushTestLogWithStage('STATS', '✅ 主动指标计算完成：主动年化波动率、相对基准胜率', 'success');
                } else if (data.message.includes('计算分组收益')) {
                  pushTestLogWithStage('GROUP_TEST', '📈 计算各组年化收益、波动率、夏普比...', 'info');
                } else if (data.message.includes('回测计算完成')) {
                  pushTestLogWithStage('GROUP_TEST', '✅ 分组回测计算完成，开始计算统计指标', 'success');
                } else if (data.message.includes('分组回测计算中')) {
                  const match = data.message.match(/(\d+)\/(\d+)/);
                  if (match) {
                    pushTestLogWithStage('GROUP_TEST', `📊 分组回测计算中... ${match[1]}/${match[2]} 天`, 'info');
                  } else {
                    pushTestLogWithStage('GROUP_TEST', data.message, 'info');
                  }
                } else {
                  pushTestLog(`⏳ ${data.message}`, 'info');
                }
              }
            } else if (data.stage === 'TEST_DONE') {
              const match = data.message?.match(/reportId=(\d+)/);
              pushTestLogWithStage('DONE', `🎉 检测完成！报告ID: ${match?.[1] || '?'}`, 'success');
              testClient.deactivate();
              setRunningReportId(null);
              setTestProgress(100);
              // 刷新列表
              loadFactor();
            }
          }

          // ── 异常终止消息（不满足 TEST_ 前缀，单独处理）──
          if (data.stage === 'FAILED' || data.stage === 'TEST_FAILED') {
            pushTestLog(`❌ ${data.message || '检测失败'}`, 'error');
            clearInterval(testPollRef.current);
            testClient.deactivate();
            setRunningReportId(null);
            setTestProgress(0);
            message.error(data.message || '因子检测失败');
            loadFactor();
          }

          if (data.stage === 'COMPLETED') {
            pushTestLog(`ℹ️ ${data.message || '检测已完成'}`, 'info');
            clearInterval(testPollRef.current);
            testClient.deactivate();
            setRunningReportId(null);
            setTestProgress(100);
            loadFactor();
          }
        });
      },
      onStompError: frame => {
        console.error('[TEST STOMP ERROR]', frame);
        pushTestLog(`❌ STOMP 错误: ${frame.headers.message}`, 'error');
      }
    });
    
    testStompRef.current = testClient;
    testClient.activate();

    // 启动轮询检查状态
    if (testPollRef.current) clearInterval(testPollRef.current);
    testWsDataFlag.current = false;
    testPollStartRef.current = Date.now();
    testPollRef.current = setInterval(() => {
      const elapsed = Date.now() - testPollStartRef.current;
      // 如果超过60秒没有收到任何WebSocket数据，停止fake进度
      if (elapsed > 60000 && !testWsDataFlag.current) {
        setTestProgress(0);
        pushTestLog('⚠️ 等待超时（60秒未收到后端检测数据），请检查后端日志', 'error');
        clearInterval(testPollRef.current);
        return;
      }
      setTestProgress(prev => {
        // 如果正在计算因子值，不推进检测的 fake 进度
        if (computing) return prev;
        if (prev < 60) return Math.min(prev + 4, 60);
        if (prev < 80) return Math.min(prev + 2, 80);
        if (prev < 92) return Math.min(prev + 1, 92);
        return prev;
      });
      factorApi.getTestReport(report.id).then(r => {
        if (r.status === 'COMPLETED' || r.status === 'FAILED') {
          clearInterval(testPollRef.current);
          setTestProgress(100);
          setRunningReportId(null);
          testStompRef.current?.deactivate();
          loadFactor();
          if (r.status === 'COMPLETED') message.success('因子检测完成！');
          else message.error('因子检测失败，请稍后重试');
        }
      }).catch(() => {
        // 报告已被删除，停止轮询
        clearInterval(testPollRef.current);
        setRunningReportId(null);
        setTestProgress(0);
        testStompRef.current?.deactivate();
      });
    }, 3000);
  };

  const loadSeriesData = () => {
    if (!factor || !seriesSymbol) return;
    setSeriesLoading(true);
    factorApi.getTimeSeries(factor.factorCode, {
      symbol: seriesSymbol,
      startDate: seriesDates[0].format('YYYY-MM-DD'),
      endDate:   seriesDates[1].format('YYYY-MM-DD'),
    }).then(res => {
      const raw = res || [];
      // 后端返回 FactorValue: { factorVal, calcDate, rankValue, symbol, ... }
      // 将字段映射为前端统一格式
      const mapped = raw.map(d => ({
        calcDate: Array.isArray(d.calcDate)
          ? `${d.calcDate[0]}-${String(d.calcDate[1]).padStart(2,'0')}-${String(d.calcDate[2]).padStart(2,'0')}`
          : (d.calcDate || ''),
        symbol: d.symbol || '',
        value: d.factorVal != null ? Number(d.factorVal) : null,
        rankValue: d.rankValue != null ? Number(d.rankValue) : null,
        zScore: d.zScore != null ? Number(d.zScore) : null,
      }));
      setSeriesData(mapped);
    })
      .catch(() => setSeriesData([]))
      .finally(() => setSeriesLoading(false));
  };

  const loadCrossData = () => {
    if (!factor || !crossDate) return;
    setCrossLoading(true);
    factorApi.getCrossSection(factor.factorCode, crossDate.format('YYYY-MM-DD'))
      .then(res => {
        const result = res || {};
        setCrossData(Array.isArray(result.data) ? result.data : (Array.isArray(result) ? result : []));
      })
      .catch(() => setCrossData([]))
      .finally(() => setCrossLoading(false));
  };

  // ── 解析 JSON ────────────────────────────────────────────
  const icSeries = React.useMemo(() => {
    try { return selectedReport?.icSeriesJson ? JSON.parse(selectedReport.icSeriesJson) : []; }
    catch { return []; }
  }, [selectedReport]);

  const groupSeries = React.useMemo(() => {
    try { return selectedReport?.groupReturnsJson ? JSON.parse(selectedReport.groupReturnsJson) : []; }
    catch { return []; }
  }, [selectedReport]);

  const groupNavSeries = React.useMemo(() => {
    try { return selectedReport?.groupNavJson ? JSON.parse(selectedReport.groupNavJson) : []; }
    catch { return []; }
  }, [selectedReport]);

  const lsNavSeries = React.useMemo(() => {
    try { return selectedReport?.longShortNavJson ? JSON.parse(selectedReport.longShortNavJson) : []; }
    catch { return []; }
  }, [selectedReport]);

  // ── 图表配置 ─────────────────────────────────────────────

  // 1. 分组收益曲线（核心图，参考图片一）
  const groupNavChartOption = React.useMemo(() => {
    if (!groupNavSeries.length) return {};
    const dates = groupNavSeries.map(d => d.date);
    const series = [
      ...GROUP_NAMES.map((name, g) => ({
        name,
        type: 'line', smooth: false, symbol: 'none',
        data: groupNavSeries.map(d => d['g' + (g + 1)]),
        lineStyle: { color: GROUP_COLORS[g], width: 1.5 },
        itemStyle: { color: GROUP_COLORS[g] },
      })),
      {
        name: '基准收益', type: 'line', smooth: false, symbol: 'none',
        data: groupNavSeries.map(d => d.benchmark),
        lineStyle: { color: '#aaa', width: 1.5, type: 'dashed' },
        itemStyle: { color: '#aaa' },
      },
    ];
    return {
      tooltip: { trigger: 'axis', formatter: params => {
        let s = params[0].axisValue + '<br/>';
        params.forEach(p => { s += `${p.marker}${p.seriesName}: ${(p.value * 100 - 100).toFixed(2)}%<br/>`; });
        return s;
      }},
      legend: { data: [...GROUP_NAMES, '基准收益'], top: 4, textStyle: { fontSize: 11 } },
      xAxis: { type: 'category', data: dates, axisLabel: { rotate: 0, fontSize: 10 }, boundaryGap: false },
      yAxis: {
        type: 'value',
        axisLabel: { formatter: v => `${((v - 1) * 100).toFixed(0)}%`, fontSize: 10 },
        splitLine: { lineStyle: { type: 'dashed' } },
      },
      dataZoom: [{ type: 'inside' }, { type: 'slider', height: 18, bottom: 0 }],
      series,
      grid: { left: 56, right: 16, bottom: 36, top: 40 },
    };
  }, [groupNavSeries]);

  // 2. 多空组合净值（参考图片二）
  const lsNavChartOption = React.useMemo(() => {
    if (!lsNavSeries.length) return {};
    const dates = lsNavSeries.map(d => d.date);
    return {
      tooltip: { trigger: 'axis', formatter: params => {
        let s = params[0].axisValue + '<br/>';
        params.forEach(p => { s += `${p.marker}${p.seriesName}: ${((p.value - 1) * 100).toFixed(2)}%<br/>`; });
        return s;
      }},
      legend: { data: ['多头组-基准', '空头组-基准', '多空净值'], top: 4 },
      xAxis: { type: 'category', data: dates, axisLabel: { fontSize: 10 }, boundaryGap: false },
      yAxis: {
        type: 'value',
        axisLabel: { formatter: v => `${((v - 1) * 100).toFixed(0)}%`, fontSize: 10 },
        splitLine: { lineStyle: { type: 'dashed' } },
      },
      dataZoom: [{ type: 'inside' }, { type: 'slider', height: 18, bottom: 0 }],
      series: [
        {
          name: '多头组-基准', type: 'line', smooth: false, symbol: 'none',
          data: lsNavSeries.map(d => d.top),
          lineStyle: { color: '#5470c6', width: 2 },
          areaStyle: { color: 'rgba(84,112,198,0.06)' },
        },
        {
          name: '空头组-基准', type: 'line', smooth: false, symbol: 'none',
          data: lsNavSeries.map(d => d.bottom),
          lineStyle: { color: '#cf1322', width: 2 },
        },
        {
          name: '多空净值', type: 'line', smooth: false, symbol: 'none',
          data: lsNavSeries.map(d => d.net),
          lineStyle: { color: '#389e0d', width: 2 },
          areaStyle: { color: 'rgba(56,158,13,0.06)' },
        },
      ],
      grid: { left: 56, right: 16, bottom: 36, top: 40 },
    };
  }, [lsNavSeries]);

  // 3. IC 历史值柱状图（参考图片三）
  const icBarChartOption = React.useMemo(() => {
    if (!icSeries.length) return {};
    const icMean = selectedReport?.icMean ? +selectedReport.icMean : 0;
    // 计算1月均线（约20个交易日）
    const maData = icSeries.map((_, i) => {
      const window = icSeries.slice(Math.max(0, i - 19), i + 1);
      return round4(window.reduce((s, d) => s + (d.ic || 0), 0) / window.length);
    });
    return {
      tooltip: { trigger: 'axis', axisPointer: { type: 'cross' },
        formatter: params => {
          let s = params[0]?.axisValue + '<br/>';
          params.forEach(p => { s += `${p.marker}${p.seriesName}: ${(+p.value * 100).toFixed(3)}%<br/>`; });
          return s;
        }
      },
      legend: { data: ['IC', '1月移动均值', 'IC均值线'], top: 4, textStyle: { fontSize: 11 } },
      xAxis: { type: 'category', data: icSeries.map(d => d.date), axisLabel: { rotate: 0, fontSize: 10 } },
      yAxis: {
        type: 'value',
        axisLabel: { formatter: v => `${(v * 100).toFixed(1)}%`, fontSize: 10 },
        splitLine: { lineStyle: { type: 'dashed' } },
      },
      dataZoom: [{ type: 'inside' }, { type: 'slider', height: 18, bottom: 0 }],
      series: [
        {
          name: 'IC', type: 'bar', barMaxWidth: 6,
          data: icSeries.map(d => ({
            value: round4(d.ic || 0),
            itemStyle: { color: (d.ic || 0) >= 0 ? '#cf1322' : '#389e0d' }
          })),
        },
        {
          name: '1月移动均值', type: 'line', smooth: true, symbol: 'none',
          data: maData,
          lineStyle: { color: '#1677ff', width: 1.5 },
        },
        {
          name: 'IC均值线', type: 'line', symbol: 'none',
          data: icSeries.map(() => round4(icMean)),
          lineStyle: { color: '#fa8c16', type: 'dashed', width: 1.5 },
        },
      ],
      grid: { left: 60, right: 16, bottom: 36, top: 40 },
    };
  }, [icSeries, selectedReport]);

  // 4. 因子IC累计均值（Running Mean，更能反映因子有效性的时间趋势）
  const icCumChartOption = React.useMemo(() => {
    if (!icSeries.length) return {};
    let cumSum = 0;
    const cumMeanData = icSeries.map((d, i) => { cumSum += (d.ic || 0); return round4(cumSum / (i + 1)); });
    const lastMean = cumMeanData[cumMeanData.length - 1] || 0;
    return {
      tooltip: { trigger: 'axis', formatter: params =>
        `${params[0].axisValue}<br/>${params[0].marker}IC累计均值: ${(params[0].value * 100).toFixed(4)}%`
      },
      xAxis: { type: 'category', data: icSeries.map(d => d.date), axisLabel: { fontSize: 10 }, boundaryGap: false },
      yAxis: {
        type: 'value',
        axisLabel: { formatter: v => `${(v * 100).toFixed(2)}%`, fontSize: 10 },
        splitLine: { lineStyle: { type: 'dashed' } },
      },
      dataZoom: [{ type: 'inside' }, { type: 'slider', height: 18, bottom: 0 }],
      series: [{
        name: 'IC累计均值', type: 'line', smooth: true, symbol: 'none',
        data: cumMeanData,
        lineStyle: { color: '#5470c6', width: 2 },
        areaStyle: { color: lastMean >= 0 ? 'rgba(84,112,198,0.1)' : 'rgba(207,19,34,0.1)' },
        markLine: {
          symbol: 'none',
          data: [{ yAxis: 0, lineStyle: { color: '#aaa', type: 'dashed', width: 1 } }],
          label: { show: false },
        },
      }],
      grid: { left: 64, right: 16, bottom: 36, top: 24 },
    };
  }, [icSeries]);

  // 5. 分组年化收益柱状图
  const groupBarChartOption = React.useMemo(() => {
    if (!groupSeries.length) return {};
    return {
      tooltip: { trigger: 'axis', formatter: p => `${p[0].name}<br/>年化收益：${(p[0].value * 100).toFixed(2)}%` },
      xAxis: { type: 'category', data: groupSeries.map(g => g.group) },
      yAxis: {
        type: 'value',
        axisLabel: { formatter: v => `${(v * 100).toFixed(0)}%`, fontSize: 10 },
        splitLine: { lineStyle: { type: 'dashed' } },
      },
      series: [{
        type: 'bar', barMaxWidth: 50,
        data: groupSeries.map((g, i) => ({
          value: g.annualReturn || 0,
          itemStyle: { color: GROUP_COLORS[i] },
          label: { show: true, position: 'top', formatter: p => `${(p.value * 100).toFixed(1)}%`, fontSize: 11 },
        })),
        label: { show: true, position: 'top', formatter: p => `${(p.value * 100).toFixed(1)}%`, fontSize: 11 },
      }],
      grid: { left: 56, right: 16, bottom: 32, top: 40 },
    };
  }, [groupSeries]);

  // 因子衰减图表配置
  const getDecayChartOption = () => {
    if (!selectedReport.decaySeriesJson) return {};

    const decaySeries = JSON.parse(selectedReport.decaySeriesJson);

    return {
      title: {
        text: '因子IC随期数衰减趋势',
        left: 'center',
        textStyle: { fontSize: 14 }
      },
      tooltip: {
        trigger: 'axis',
        formatter: (params) => {
          const p = params[0];
          const data = decaySeries[p.dataIndex];
          return `
            <div style="padding: 4px;">
              <div><strong>期数: ${data.period}</strong></div>
              <div>IC值: ${fmt4(data.laggedIc)}</div>
              <div>绝对值: ${fmt4(data.absoluteIc)}</div>
            </div>
          `;
        }
      },
      legend: {
        data: ['IC绝对值', 'IC值'],
        top: 25,
        left: 'center'
      },
      xAxis: {
        type: 'category',
        name: '期数',
        data: decaySeries.map(d => d.period),
        axisLine: { lineStyle: { color: '#8c8c8c' } }
      },
      yAxis: {
        type: 'value',
        name: 'IC值',
        axisLabel: { formatter: v => fmt4(v) },
        splitLine: { lineStyle: { type: 'dashed' } }
      },
      grid: { left: 60, right: 20, bottom: 40, top: 60 },
      series: [
        {
          name: 'IC绝对值',
          type: 'line',
          data: decaySeries.map(d => d.absoluteIc),
          smooth: true,
          itemStyle: { color: '#5470c6' },
          lineStyle: { width: 2 },
          symbol: 'circle',
          symbolSize: 6
        },
        {
          name: 'IC值',
          type: 'line',
          data: decaySeries.map(d => d.laggedIc),
          smooth: true,
          itemStyle: { color: '#91cc75' },
          lineStyle: { width: 2, type: 'dashed' },
          symbol: 'circle',
          symbolSize: 6
        }
      ],
      markLine: {
        symbol: 'none',
        data: [
          {
            name: '阈值0.02',
            yAxis: 0.02,
            lineStyle: { color: '#cf1322', type: 'dashed' },
            label: { formatter: '阈值0.02' }
          }
        ]
      }
    };
  };

  // ── 辅助渲染：关键指标是否达标 ──────────────────────────
  const SignalIcon = ({ value, good, bad }) => {
    if (value == null) return <MinusCircleOutlined style={{ color: '#bfbfbf' }} />;
    const v = +value;
    if (good(v)) return <CheckCircleOutlined style={{ color: '#52c41a' }} />;
    if (bad(v))  return <CloseCircleOutlined  style={{ color: '#ff4d4f' }} />;
    return <MinusCircleOutlined style={{ color: '#faad14' }} />;
  };

  if (loading) return <div style={{ textAlign: 'center', padding: 80 }}><Spin size="large" /></div>;
  if (!factor) return <Text type="danger">因子不存在</Text>;

  const statusColors = { DRAFT: 'default', TESTING: 'processing', ACTIVE: 'success', DEPRECATED: 'default' };
  const isCompleted  = selectedReport?.status === 'COMPLETED';
  const isRunning    = selectedReport?.status === 'RUNNING';
  // 是否已有因子值（可运行测试）
  const hasValues    = valueCount !== null && valueCount > 0;
  const valueReady   = hasValues && !computing;

  return (
    <div>
      {/* 页头 */}
      <div className="page-header">
        <Space>
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/factors')}>返回</Button>
          <Typography.Title level={4} style={{ margin: 0 }}>{factor.factorName}</Typography.Title>
          <Tag color="blue">{factor.factorCode}</Tag>
          <Tag color={statusColors[factor.status]}>{factor.status}</Tag>
          {/* 因子值状态小徽标 */}
          {valueCount === null
            ? <Tag icon={<SyncOutlined spin />} color="processing">数据量加载中</Tag>
            : valueCount === 0
              ? <Tag icon={<WarningOutlined />} color="warning">未计算因子值</Tag>
              : <Tag icon={<CheckCircleOutlined />} color="success">{valueCount.toLocaleString()} 条因子值</Tag>
          }
        </Space>
        <Space>
          {factor.factorType !== 'BUILTIN' && (
            <Button icon={<EditOutlined />} onClick={() => navigate(`/factors/${id}/edit`)}>编辑因子</Button>
          )}
        </Space>
      </div>

      <Tabs defaultActiveKey="test" onChange={(key) => { if (key === 'values') setValuesTabVisited(true); }} items={[
        /* ── Tab 1：基本信息 ── */
        {
          key: 'info',
          label: '基本信息',
          children: (
            <Card>
              <Descriptions bordered column={2}>
                <Descriptions.Item label="因子代码">{factor.factorCode}</Descriptions.Item>
                <Descriptions.Item label="因子名称">{factor.factorName}</Descriptions.Item>
                <Descriptions.Item label="分类">{factor.category}</Descriptions.Item>
                <Descriptions.Item label="类型">{factor.factorType}</Descriptions.Item>
                <Descriptions.Item label="状态"><Tag color={statusColors[factor.status]}>{factor.status}</Tag></Descriptions.Item>
                <Descriptions.Item label="版本">v{factor.version}</Descriptions.Item>
                <Descriptions.Item label="创建人">{factor.author || '-'}</Descriptions.Item>
                <Descriptions.Item label="创建时间">{factor.createdAt}</Descriptions.Item>
                <Descriptions.Item label="描述" span={2}>{factor.description || '-'}</Descriptions.Item>
                {factor.scriptCode && (
                  <Descriptions.Item label="计算脚本" span={2}>
                    <pre style={{ background: '#1e1e1e', color: '#d4d4d4', padding: 12, borderRadius: 6, maxHeight: 300, overflow: 'auto', fontSize: 12 }}>
                      {factor.scriptCode}
                    </pre>
                  </Descriptions.Item>
                )}
              </Descriptions>
            </Card>
          ),
        },

        /* ──     /* ── Tab 2：因子检测 ── */
        {
          key: 'test',
          label: `因子检测 (${testReports.length})`,
          children: (
            <div>
              {/* ── 未计算引导 */}
              {valueCount === 0 && !computing && runningReportId == null && (
                <Alert type="warning" showIcon style={{ marginBottom: 12 }}
                  message="因子值尚未计算"
                  description={
                    <Space direction="vertical" size={4}>
                      <Typography.Text>运行因子检测前，需要先计算并存储因子历史数据。</Typography.Text>
                      <Space>
                        <Typography.Text type="secondary">正确流程：</Typography.Text>
                        <Tag color="blue">① 新增检测</Tag>
                        <Typography.Text type="secondary">→ 系统自动触发计算 →</Typography.Text>
                        <Tag color="green">② 自动运行检测</Tag>
                      </Space>
                      <Button type="primary" size="small" icon={<CalculatorOutlined />}
                        onClick={() => { setTestName('因子检测'); setTestModal(true); }}>
                        立即新增检测
                      </Button>
                    </Space>
                  }
                />
              )}

              {/* ════════ 检测记录列表 ════════ */}
              <Card
                size="small"
                title={<Space><ExperimentOutlined /><span>检测记录</span></Space>}
                extra={
                  <Space>
                    {selectedRowKeys.length > 0 && (
                      <Button
                        danger
                        size="small"
                        icon={<DeleteOutlined />}
                        onClick={handleBatchDelete}
                        loading={batchDeleteLoading}
                      >
                        批量删除 ({selectedRowKeys.length})
                      </Button>
                    )}
                    <Button type="primary" size="small" icon={<ExperimentOutlined />}
                      onClick={() => { setTestName('因子检测'); setTestModal(true); }}>
                      新增检测
                    </Button>
                  </Space>
                }
                style={{ marginBottom: 12 }}
              >
                <Table
                  dataSource={testReports}
                  rowKey="id"
                  size="small"
                  pagination={testReports.length > 10 ? { defaultPageSize: 10, showSizeChanger: true, pageSizeOptions: ['10', '20', '50'], showTotal: t => `共 ${t} 条` } : false}
                  scroll={{ x: 1250 }}
                  rowSelection={{
                    selectedRowKeys,
                    onChange: setSelectedRowKeys,
                    getCheckboxProps: (record) => ({
                      disabled: false,
                    }),
                  }}
                  onRow={r => ({
                    onClick: () => {
                      setSelectedReport(r);
                      // 如果是运行中或待运行状态的检测，启动WebSocket监听和进度更新
                      if ((r.status === 'RUNNING' || r.status === 'PENDING') && factor?.factorCode) {
                        handleStartMonitoring(r);
                      }
                    },
                    style: {
                      cursor: r.status === 'COMPLETED' || r.status === 'RUNNING' || r.status === 'PENDING' ? 'pointer' : 'default',
                      background: selectedReport?.id === r.id ? '#e6f4ff' : undefined,
                    }
                  })}
                  columns={[
                    {
                      title: 'ID', dataIndex: 'id', width: 70, align: 'center',
                      render: v => <Typography.Text style={{ fontSize: 11, color: '#8c8c8c' }}>#{v}</Typography.Text>
                    },
                    {
                      title: '检测名称', dataIndex: 'testName', width: 140, ellipsis: true,
                      render: (v, r) => (
                        <Space size={4}>
                          <Typography.Text strong style={{ fontSize: 12 }}>{v || `#${r.id}`}</Typography.Text>
                          {selectedReport?.id === r.id && <Tag color="blue" style={{ fontSize: 10, padding: '0 4px' }}>当前</Tag>}
                        </Space>
                      )
                    },
                    {
                      title: '股票池', dataIndex: 'stockPool', width: 95, align: 'center',
                      render: v => {
                        const map = { ALL_A: '全部A股', CSI300: '沪深300', CSI500: '中证500', CSI800: '中证800', CSI1000: '中证1000' };
                        return <Tag color="purple" style={{ fontSize: 11 }}>{map[v] || v || '全部A股'}</Tag>;
                      }
                    },
                    {
                      title: '调仓频率', dataIndex: 'rebalanceFreq', width: 85, align: 'center',
                      render: v => {
                        const map = { DAILY: '日', WEEKLY: '周', MONTHLY: '月' };
                        const color = { DAILY: 'cyan', WEEKLY: 'geekblue', MONTHLY: 'orange' };
                        return <Tag color={color[v] || 'default'} style={{ fontSize: 11 }}>{map[v] || v || '日'}</Tag>;
                      }
                    },
                    {
                      title: '检测区间', key: 'dateRange', width: 165,
                      render: (_, r) => (
                        <Typography.Text style={{ fontSize: 11 }}>{r.startDate} ~ {r.endDate}</Typography.Text>
                      )
                    },
                    {
                      title: '计算时间', dataIndex: 'createdAt', width: 165,
                      render: v => {
                        if (!v) return <Typography.Text type="secondary">-</Typography.Text>;
                        const d = new Date(v);
                        return <Typography.Text style={{ fontSize: 11 }}>
                          {d.getFullYear()}-{String(d.getMonth()+1).padStart(2,'0')}-{String(d.getDate()).padStart(2,'0')} {String(d.getHours()).padStart(2,'0')}:{String(d.getMinutes()).padStart(2,'0')}:{String(d.getSeconds()).padStart(2,'0')}
                        </Typography.Text>;
                      }
                    },
                    {
                      title: '完成时间', dataIndex: 'completedAt', width: 165,
                      render: v => {
                        if (!v) return <Typography.Text type="secondary">-</Typography.Text>;
                        const d = new Date(v);
                        return <Typography.Text style={{ fontSize: 11 }}>
                          {d.getFullYear()}-{String(d.getMonth()+1).padStart(2,'0')}-{String(d.getDate()).padStart(2,'0')} {String(d.getHours()).padStart(2,'0')}:{String(d.getMinutes()).padStart(2,'0')}:{String(d.getSeconds()).padStart(2,'0')}
                        </Typography.Text>;
                      }
                    },
                    {
                      title: '主动年化收益', key: 'activeReturn', width: 115, align: 'right',
                      render: (_, r) => {
                        const v = r.topGroupReturn;
                        if (v == null) return <Typography.Text type="secondary">-</Typography.Text>;
                        return <Typography.Text strong style={{ color: signColor(v), fontSize: 12 }}>{fmtPct(v)}</Typography.Text>;
                      }
                    },
                    {
                      title: '最高夏普比', dataIndex: 'bestSharpe', width: 95, align: 'right',
                      render: v => v != null
                        ? <Typography.Text style={{ color: signColor(v), fontSize: 12 }}>{fmt2(v)}</Typography.Text>
                        : <Typography.Text type="secondary">-</Typography.Text>
                    },
                    {
                      title: '主动年化波动率', dataIndex: 'activeVolatility', width: 120, align: 'right',
                      render: v => v != null
                        ? <Typography.Text style={{ fontSize: 12 }}>{fmtPct(v)}</Typography.Text>
                        : <Typography.Text type="secondary">-</Typography.Text>
                    },
                    {
                      title: '相对基准胜率', dataIndex: 'winRateVsBenchmark', width: 110, align: 'right',
                      render: v => {
                        if (v == null) return <Typography.Text type="secondary">-</Typography.Text>;
                        const pct = +v * 100;
                        return (
                          <Typography.Text style={{ color: pct >= 55 ? '#52c41a' : pct < 45 ? '#cf1322' : '#faad14', fontSize: 12 }}>
                            {pct.toFixed(1)}%
                          </Typography.Text>
                        );
                      }
                    },
                    {
                      title: '截面换手率', dataIndex: 'turnoverRate', width: 100, align: 'right',
                      render: v => v != null
                        ? <Typography.Text style={{ fontSize: 12 }}>{(+v * 100).toFixed(1)}%</Typography.Text>
                        : <Typography.Text type="secondary">-</Typography.Text>
                    },
                    {
                      title: '因子自相关', dataIndex: 'factorAutoCorr', width: 100, align: 'right',
                      render: v => v != null
                        ? <Typography.Text style={{ fontSize: 12 }}>{(+v).toFixed(3)}</Typography.Text>
                        : <Typography.Text type="secondary">-</Typography.Text>
                    },
                    {
                      title: '状态', dataIndex: 'status', width: 90, align: 'center',
                      render: v => (
                        <Tag color={v === 'COMPLETED' ? 'success' : v === 'RUNNING' ? 'processing' : v === 'PENDING' ? 'default' : v === 'FAILED' ? 'error' : 'default'} style={{ fontSize: 11 }}>
                          {v === 'COMPLETED' ? '已完成' : v === 'RUNNING' ? '运行中' : v === 'PENDING' ? '待运行' : v === 'FAILED' ? '失败' : v}
                        </Tag>
                      )
                    },
                    {
                      title: '操作', key: 'action', width: 140, align: 'center', fixed: 'right',
                      render: (_, r) => (
                        <Space size={4}>
                          {r.status === 'RUNNING' || r.status === 'PENDING' ? (
                            // 运行中或pending状态显示停止按钮
                            <Popconfirm title="确认停止检测？停止后将删除当前的计算数据"
                              onConfirm={() => handleStopTest(r.id)}
                              okText="停止" cancelText="取消" okButtonProps={{ danger: true }}>
                              <Button size="small" danger icon={<StopOutlined />} title="停止检测"
                                loading={deleteLoading[r.id]}
                                onClick={e => e.stopPropagation()}>
                                停止
                              </Button>
                            </Popconfirm>
                          ) : (
                            // 非运行中状态显示删除按钮
                            <Popconfirm title="确认删除此检测报告？" onConfirm={() => handleDeleteReport(r.id)}
                              okText="删除" cancelText="取消" okButtonProps={{ danger: true }}>
                              <Button size="small" danger icon={<DeleteOutlined />} title="删除"
                                loading={deleteLoading[r.id]}
                                onClick={e => e.stopPropagation()} />
                            </Popconfirm>
                          )}
                        </Space>
                      )
                    },
                  ]}
                />
              </Card>

              {/* ════════ 选中报告详情 ════════ */}
              {selectedReport && (selectedReport?.status === 'RUNNING' || selectedReport?.status === 'PENDING') && selectedReport?.id === runningReportId && (
                <>
                  <Divider orientation="left" style={{ margin: '8px 0 12px' }}>
                    <Space>
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>检测进度：</Typography.Text>
                      <Typography.Text strong>{selectedReport.testName}</Typography.Text>
                      <Tag color="purple">{({ ALL_A: '全部A股', CSI300: '沪深300', CSI500: '中证500', CSI800: '中证800', CSI1000: '中证1000' })[selectedReport.stockPool] || '全部A股'}</Tag>
                      <Tag color="cyan">{({ DAILY: '日频', WEEKLY: '周频', MONTHLY: '月频' })[selectedReport.rebalanceFreq] || '日频'}</Tag>
                    </Space>
                  </Divider>

                  <Card size="small" style={{ marginBottom: 12 }}>
                    {/* ── 因子计算区块：仅在正在计算时显示 ── */}
                    {computing && (
                      <div>
                        <Space style={{ marginBottom: 8 }}>
                          <Typography.Text strong style={{ fontSize: 13 }}>因子计算：</Typography.Text>
                          <Tag icon={<SyncOutlined spin />} color="processing">计算中 {computeProgress}%</Tag>
                        </Space>
                        <Progress percent={computeProgress} status="active"
                          strokeColor={{ '0%': '#1677ff', '100%': '#52c41a' }} style={{ marginBottom: 8 }} />
                        <div style={{ maxHeight: 260, overflow: 'auto', background: '#fafafa', padding: 12, borderRadius: 4 }}>
                          {computeLogs.length === 0 ? (
                            <div style={{ textAlign: 'center', color: '#bfbfbf', padding: 20 }}>等待计算数据...</div>
                          ) : computeLogs.map(log => (
                            <div key={log.id} style={{ marginBottom: 4, fontSize: 12, lineHeight: 1.5 }}>
                              <Typography.Text type="secondary" style={{ fontFamily: 'monospace' }}>[{log.time}]</Typography.Text>{' '}
                              <Typography.Text type={log.type === 'error' ? 'danger' : log.type === 'success' ? 'success' : 'default'}>
                                {log.text}
                              </Typography.Text>
                            </div>
                          ))}
                        </div>
                      </div>
                    )}

                    {/* ── 检测过程：因子值存在时（未曾计算）或计算完成后才显示 ── */}
                    {!computing && (
                      <>
                        {/* 分阶段进度展示 */}
                        <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
                          <Col span={24}>
                            <Typography.Text strong style={{ fontSize: 13 }}>检测流程：</Typography.Text>
                          </Col>
                          <Col span={8}>
                            <div style={{ textAlign: 'center', padding: '8px 4px', borderRadius: 4, background: testProgress >= 6 ? '#f6ffed' : '#fafafa', border: `1px solid ${testProgress >= 6 ? '#b7eb8f' : '#d9d9d9'}` }}>
                              <div style={{ fontSize: 11, color: '#8c8c8c', marginBottom: 2 }}>数据准备</div>
                              <Progress percent={Math.min(testProgress / 6 * 100, 100)} size="small" showInfo={false} />
                              <div style={{ fontSize: 10, marginTop: 4, color: testProgress >= 6 ? '#52c41a' : '#8c8c8c' }}>
                                {testProgress >= 6 ? '✅ 完成' : '进行中'}
                              </div>
                            </div>
                          </Col>
                          <Col span={8}>
                            <div style={{ textAlign: 'center', padding: '8px 4px', borderRadius: 4, background: testProgress >= 95 ? '#f6ffed' : testProgress >= 6 ? '#e6f7ff' : '#fafafa', border: `1px solid ${testProgress >= 95 ? '#b7eb8f' : testProgress >= 6 ? '#91d5ff' : '#d9d9d9'}` }}>
                              <div style={{ fontSize: 11, color: '#8c8c8c', marginBottom: 2 }}>IC & 分组检测</div>
                              <Progress percent={testProgress >= 95 ? 100 : testProgress >= 6 ? ((testProgress - 6) / 89 * 100) : 0} size="small" showInfo={false} />
                              <div style={{ fontSize: 10, marginTop: 4, color: testProgress >= 95 ? '#52c41a' : '#8c8c8c' }}>
                                {testProgress >= 95 ? '✅ 完成' : testProgress >= 6 ? '进行中' : '等待中'}
                              </div>
                            </div>
                          </Col>
                          <Col span={8}>
                            <div style={{ textAlign: 'center', padding: '8px 4px', borderRadius: 4, background: testProgress >= 100 ? '#f6ffed' : '#fafafa', border: `1px solid ${testProgress >= 100 ? '#b7eb8f' : '#d9d9d9'}` }}>
                              <div style={{ fontSize: 11, color: '#8c8c8c', marginBottom: 2 }}>统计指标</div>
                              <Progress percent={testProgress >= 100 ? 100 : 0} size="small" showInfo={false} />
                              <div style={{ fontSize: 10, marginTop: 4, color: testProgress >= 100 ? '#52c41a' : '#8c8c8c' }}>
                                {testProgress >= 100 ? '✅ 完成' : '等待中'}
                              </div>
                            </div>
                          </Col>
                        </Row>

                        {/* 总体进度条 */}
                        <div style={{ marginBottom: 16 }}>
                          <Space style={{ marginBottom: 8 }}>
                            <Typography.Text strong style={{ fontSize: 13 }}>总体进度：</Typography.Text>
                            <Typography.Text type={testProgress >= 100 ? 'success' : 'secondary'} style={{ fontSize: 12 }}>
                              {testProgress}%
                            </Typography.Text>
                          </Space>
                          <Progress
                            percent={testProgress}
                            status={testProgress < 100 ? 'active' : 'success'}
                            strokeColor={{ '0%': '#108ee9', '100%': '#87d068' }}
                          />
                        </div>

                        {/* 详细日志 */}
                        <div style={{ marginTop: 12 }}>
                          <Typography.Text strong style={{ fontSize: 13 }}>详细日志：</Typography.Text>
                          <div style={{ marginTop: 8, maxHeight: 300, overflow: 'auto', background: '#fafafa', padding: 12, borderRadius: 4 }}>
                            {testLogs.length === 0 ? (
                              <div style={{ textAlign: 'center', color: '#bfbfbf', padding: 20 }}>
                                等待检测开始...
                              </div>
                            ) : (
                              testLogs.map(log => (
                                <div key={log.id} style={{ marginBottom: 6, fontSize: 12, lineHeight: 1.5 }}>
                                  <Typography.Text type="secondary" style={{ fontFamily: 'monospace' }}>[{log.time}]</Typography.Text>{' '}
                                  <Typography.Text type={log.type === 'error' ? 'danger' : log.type === 'success' ? 'success' : 'default'}>
                                    {log.text}
                                  </Typography.Text>
                                </div>
                              ))
                            )}
                          </div>
                        </div>
                      </>
                    )}
                  </Card>
                </>
              )}

              {isCompleted && selectedReport && (
                <>
                  <Divider orientation="left" style={{ margin: '8px 0 12px' }}>
                    <Space>
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>报告详情：</Typography.Text>
                      <Typography.Text strong>{selectedReport.testName}</Typography.Text>
                      <Tag color="purple">{({ ALL_A: '全部A股', CSI300: '沪深300', CSI500: '中证500', CSI800: '中证800', CSI1000: '中证1000' })[selectedReport.stockPool] || '全部A股'}</Tag>
                      <Tag color="cyan">{({ DAILY: '日频', WEEKLY: '周频', MONTHLY: '月频' })[selectedReport.rebalanceFreq] || '日频'}</Tag>
                    </Space>
                  </Divider>

                  <Row gutter={[8, 8]} style={{ marginBottom: 12 }}>
                    {[
                      { title: 'IC 均値', value: fmtPct4(selectedReport.icMean), note: '> 0.02% 为有效', signal: { good: v => v > 0.02, bad: v => v < 0 }, raw: selectedReport.icMean },
                      { title: 'ICIR', value: fmt4(selectedReport.icir), note: '> 0.5 较好', signal: { good: v => v > 0.5, bad: v => v < 0 }, raw: selectedReport.icir },
                      { title: 'IC 正値率', value: fmtPct(selectedReport.icPositiveRate), note: '> 55% 较好', signal: { good: v => v > 0.55, bad: v => v < 0.45 }, raw: selectedReport.icPositiveRate },
                      { title: 'IC 显著性(p)', value: fmt4(selectedReport.icPValue), note: '< 0.05 显著', signal: { good: v => v < 0.05, bad: v => v > 0.1 }, raw: selectedReport.icPValue },
                      { title: '单调性', value: fmt4(selectedReport.monotonicity), note: '绝对値越大越好', signal: { good: v => Math.abs(v) > 0.7, bad: v => Math.abs(v) < 0.3 }, raw: selectedReport.monotonicity },
                      { title: '分组 IR', value: fmt4(selectedReport.groupIr), note: '多空年化 IR', signal: { good: v => Math.abs(v) > 0.5, bad: v => Math.abs(v) < 0.2 }, raw: selectedReport.groupIr },
                      { title: '多空年化收益', value: fmtPct(selectedReport.longShortReturn), note: '多头-空头', signal: { good: v => v > 0.05, bad: v => v < 0 }, raw: selectedReport.longShortReturn },
                      { title: '多空显著性(p)', value: fmt4(selectedReport.lsPValue), note: '< 0.05 显著', signal: { good: v => v < 0.05, bad: v => v > 0.1 }, raw: selectedReport.lsPValue },
                      { title: '截面换手率', value: selectedReport.turnoverRate != null ? `${(+selectedReport.turnoverRate * 100).toFixed(1)}%` : '-', note: 'Top组新增比例', signal: { good: v => v < 0.3, bad: v => v > 0.7 }, raw: selectedReport.turnoverRate },
                      { title: '因子自相关', value: selectedReport.factorAutoCorr != null ? (+selectedReport.factorAutoCorr).toFixed(3) : '-', note: 'corr(f_t, f_{t-1})', signal: { good: v => Math.abs(v) > 0.8, bad: v => Math.abs(v) < 0.5 }, raw: selectedReport.factorAutoCorr },
                    ].map(m => (
                      <Col xs={12} sm={6} md={3} key={m.title}>
                        <Card size="small" hoverable style={{ textAlign: 'center', padding: '8px 4px' }}>
                          <div style={{ fontSize: 11, color: '#8c8c8c', marginBottom: 2 }}>{m.title}</div>
                          <div style={{ fontSize: 16, fontWeight: 600, color: m.raw != null ? signColor(m.raw) : '#262626' }}>{m.value}</div>
                          <div style={{ fontSize: 10, color: '#bfbfbf', marginTop: 2 }}>
                            <SignalIcon value={m.raw} good={m.signal.good} bad={m.signal.bad} />{' '}{m.note}
                          </div>
                        </Card>
                      </Col>
                    ))},
                  </Row>

                  <Card title={<><LineChartOutlined /> 分组收益曲线</>} size="small" style={{ marginBottom: 12 }}
                    extra={<Typography.Text type="secondary" style={{ fontSize: 11 }}>基准收益（等权平均）</Typography.Text>}>
                    {groupNavSeries.length > 0
                      ? <ReactECharts option={groupNavChartOption} style={{ height: 280 }} />
                      : <div style={{ textAlign: 'center', padding: 40, color: '#bfbfbf' }}>暂无净値数据</div>}
                  </Card>

                  <Card title={<><RiseOutlined /> 多空组合净値</>} size="small" style={{ marginBottom: 12 }}
                    extra={
                      <Space size={12}>
                        <Typography.Text style={{ fontSize: 11 }}>多头组：<Typography.Text strong style={{ color: '#5470c6' }}>{fmtPct(selectedReport.topGroupReturn)}</Typography.Text></Typography.Text>
                        <Typography.Text style={{ fontSize: 11 }}>空头组：<Typography.Text strong style={{ color: '#cf1322' }}>{fmtPct(selectedReport.bottomGroupReturn)}</Typography.Text></Typography.Text>
                        <Typography.Text style={{ fontSize: 11 }}>多空差：<Typography.Text strong style={{ color: '#389e0d' }}>{fmtPct(selectedReport.longShortReturn)}</Typography.Text></Typography.Text>
                      </Space>
                    }>
                    {lsNavSeries.length > 0
                      ? <ReactECharts option={lsNavChartOption} style={{ height: 220 }} />
                      : <div style={{ textAlign: 'center', padding: 40, color: '#bfbfbf' }}>暂无数据</div>}
                  </Card>

                  <Card title={<><BarChartOutlined /> IC 値检测</>} size="small" style={{ marginBottom: 12 }}>
                    <Row gutter={24} style={{ marginBottom: 12, padding: '8px 0', borderBottom: '1px solid #f0f0f0' }}>
                      {[
                        { label: 'IC序列均値',   value: fmtPct4(selectedReport.icMean) },
                        { label: 'IC序列标准差', value: fmtPct4(selectedReport.icStd) },
                        { label: 'IR比率',       value: fmt4(selectedReport.icir) },
                        { label: 'IC > 0 占比',  value: fmtPct(selectedReport.icPositiveRate) },
                        { label: 'IC t统计量',   value: fmt2(selectedReport.icTStat) },
                        { label: '|IC| p値',     value: fmt4(selectedReport.icPValue) },
                      ].map(it => (
                        <Col key={it.label} flex="auto" style={{ textAlign: 'center', minWidth: 80 }}>
                          <div style={{ fontSize: 11, color: '#8c8c8c' }}>{it.label}</div>
                          <div style={{ fontSize: 14, fontWeight: 600 }}>{it.value}</div>
                        </Col>
                      ))}
                    </Row>
                    <Row gutter={12}>
                      <Col xs={24} xl={14}>
                        <div style={{ fontSize: 12, color: '#8c8c8c', marginBottom: 4 }}>历史IC値</div>
                        <ReactECharts option={icBarChartOption} style={{ height: 220 }} />
                      </Col>
                      <Col xs={24} xl={10}>
                        <div style={{ fontSize: 12, color: '#8c8c8c', marginBottom: 4 }}>IC累计均值（因子时效性）</div>
                        <ReactECharts option={icCumChartOption} style={{ height: 220 }} />
                      </Col>
                    </Row>
                  </Card>

                  <Card title={<><TableOutlined /> 分组绩效分析</>} size="small" style={{ marginBottom: 12 }}
                    extra={
                      <Space size={12}>
                        <Typography.Text style={{ fontSize: 11 }}>单调性：<Typography.Text strong style={{ color: signColor(selectedReport.monotonicity) }}>{fmt4(selectedReport.monotonicity)}</Typography.Text></Typography.Text>
                        <Typography.Text style={{ fontSize: 11 }}>分组IR：<Typography.Text strong style={{ color: signColor(selectedReport.groupIr) }}>{fmt4(selectedReport.groupIr)}</Typography.Text></Typography.Text>
                        <Typography.Text style={{ fontSize: 11 }}>多空p値：<Typography.Text strong style={{ color: selectedReport.lsPValue != null && +selectedReport.lsPValue < 0.05 ? '#52c41a' : '#faad14' }}>{fmt4(selectedReport.lsPValue)}</Typography.Text></Typography.Text>
                      </Space>
                    }>
                    <Table
                      dataSource={groupSeries.map((g, i) => ({ key: g.group, ...g, _idx: i }))}
                      columns={[
                        { title: '分组', dataIndex: 'group', width: 80, align: 'center', render: (v, row) => <Tag color={GROUP_COLORS[row._idx]}>{v}</Tag> },
                        { title: '年化收益率', dataIndex: 'annualReturn', width: 100, align: 'right', render: v => v != null ? <Typography.Text strong style={{ color: signColor(v) }}>{fmtPct(v)}</Typography.Text> : <Typography.Text type="secondary">--</Typography.Text> },
                        { title: '超额收益', dataIndex: 'excessReturn', width: 100, align: 'right', render: v => v != null ? <Typography.Text style={{ color: signColor(v) }}>{fmtPct(v)}</Typography.Text> : '--' },
                        { title: '年化波动率', dataIndex: 'volatility', width: 100, align: 'right', render: v => v != null ? fmtPct(v) : '--' },
                        { title: '夏普比率', dataIndex: 'sharpe', width: 90, align: 'right', render: v => v != null ? <Typography.Text style={{ color: signColor(v) }}>{fmt4(v)}</Typography.Text> : '--' },
                        { title: 'Calmar', dataIndex: 'calmar', width: 90, align: 'right', render: v => v != null ? <Typography.Text style={{ color: signColor(v) }}>{fmt4(v)}</Typography.Text> : '--' },
                        { title: '最大回撤', dataIndex: 'maxDrawdown', width: 100, align: 'right', render: v => v != null ? <Typography.Text style={{ color: '#cf1322' }}>-{fmtPct(v)}</Typography.Text> : '--' },
                        { title: '胜率', dataIndex: 'winRate', width: 80, align: 'right', render: v => v != null ? <Typography.Text style={{ color: v > 0.55 ? '#52c41a' : v < 0.45 ? '#cf1322' : '#faad14' }}>{fmtPct(v)}</Typography.Text> : '--' },
                        {
                          title: '收益条形', dataIndex: 'annualReturn', key: 'bar', width: 120,
                          render: (v, row) => {
                            if (v == null) return null;
                            const maxAbs = Math.max(...groupSeries.map(g => Math.abs(g.annualReturn || 0)), 0.01);
                            const w = Math.abs(v) / maxAbs * 100;
                            return <div style={{ display: 'flex', alignItems: 'center' }}><div style={{ width: `${w}%`, height: 10, borderRadius: 2, background: v > 0 ? GROUP_COLORS[row._idx] || '#5470c6' : '#ff4d4f', opacity: 0.8, minWidth: 2, maxWidth: 100 }} /></div>;
                          }
                        },
                      ]}
                      pagination={false} size="small" scroll={{ x: 860 }} />
                  </Card>

                  <Card title={<><BarChartOutlined /> 分组年化收益对比（单调性检验）</>} size="small"
                    extra={<Typography.Text type="secondary" style={{ fontSize: 11 }}>理想因子：各组收益应呈单调递增/递减趋势</Typography.Text>}>
                    <ReactECharts option={groupBarChartOption} style={{ height: 240 }} />
                  </Card>

                  {/* 因子衰减分析 */}
                  {selectedReport.decaySeriesJson && (
                    <Card title={<><BarChartOutlined /> 因子衰减分析（因子有效期）</>} size="small"
                      extra={
                        <Space size={12}>
                          <Typography.Text style={{ fontSize: 11 }}>因子有效期：<Typography.Text strong>{selectedReport.decayPeriods != null ? `${fmt2(selectedReport.decayPeriods)}期` : '-'}</Typography.Text></Typography.Text>
                          <Typography.Text style={{ fontSize: 11 }}>半衰期：<Typography.Text strong>{selectedReport.halfLifePeriods != null ? `${fmt2(selectedReport.halfLifePeriods)}期` : '-'}</Typography.Text></Typography.Text>
                          <Typography.Text style={{ fontSize: 11 }}>衰减系数：<Typography.Text strong>{selectedReport.decayCoefficient != null ? fmt4(selectedReport.decayCoefficient) : '-'}</Typography.Text></Typography.Text>
                          <Typography.Text style={{ fontSize: 11 }}>拟合优度R²：<Typography.Text strong>{selectedReport.decayRSquared != null ? fmt4(selectedReport.decayRSquared) : '-'}</Typography.Text></Typography.Text>
                        </Space>
                      }>
                      <Alert
                        message="因子衰减分析说明"
                        description={
                          <div style={{ fontSize: 12 }}>
                            <p><strong>因子有效期:</strong> IC绝对值首次低于0.02的期数,表示因子预测能力的持续时间</p>
                            <p><strong>半衰期:</strong> IC降至初始值50%所需的期数,衡量因子衰减速度</p>
                            <p><strong>衰减系数:</strong> 拟合指数衰减模型的系数,值越大衰减越快</p>
                            <p><strong>拟合优度R²:</strong> 拟合模型的解释程度,越接近1拟合越好</p>
                            <p><strong>使用建议:</strong> 有效期长的因子更适合长期持有,衰减快的因子需要频繁调仓</p>
                          </div>
                        }
                        type="info"
                        showIcon
                        style={{ marginBottom: 12, fontSize: 12 }}
                      />
                      <ReactECharts option={getDecayChartOption()} style={{ height: 280 }} />
                    </Card>
                  )}
                </>
              )}
            </div>
          ),
        },

        /* ── Tab 3：因子值查看 ── */
        {
          key: 'values',
          label: <><TableOutlined /> 因子值查看</>,
          children: (
            <div>
              <Tabs defaultActiveKey="series" size="small" items={[
                {
                  key: 'series',
                  label: '时间序列',
                  children: (
                    <Card bordered={false} style={{ padding: 0 }}>
                      <Space wrap style={{ marginBottom: 16 }}>
                        <Text strong>选择股票：</Text>
                        <Select
                          value={seriesSymbol}
                          onChange={setSeriesSymbol}
                          style={{ width: 220 }}
                          showSearch
                          placeholder="输入代码或名称搜索"
                          loading={symbolsLoading}
                          filterOption={false}
                          onSearch={keyword => {
                            clearTimeout(searchSymbolTimer.current);
                            searchSymbolTimer.current = setTimeout(() => loadFactorSymbols(keyword), 300);
                          }}
                          onFocus={() => { if (symbols.length === 0) loadFactorSymbols(''); }}
                          notFoundContent={symbolsLoading ? <Spin size="small" /> : '无匹配股票'}
                          options={symbols.map(s => ({
                            value: s.symbol,
                            label: <span><Tag color="blue" style={{ fontSize: 11, marginRight: 4 }}>{s.symbol}</Tag>{s.name}</span>,
                          }))}
                        />
                        <Text strong>时间区间：</Text>
                        <RangePicker value={seriesDates} onChange={v => v && setSeriesDates(v)} />
                        <Button type="primary" icon={<SearchOutlined />} onClick={loadSeriesData} loading={seriesLoading}>查询</Button>
                      </Space>
                      {seriesLoading ? (
                        <div style={{ textAlign: 'center', padding: 60 }}><Spin /></div>
                      ) : seriesData.length > 0 ? (
                        <>
                          <ReactECharts style={{ height: 260 }} option={{
                            tooltip: { trigger: 'axis' },
                            xAxis: { type: 'category', data: seriesData.map(d => d.calcDate), axisLabel: { rotate: 30, fontSize: 10 }, boundaryGap: false },
                            yAxis: { type: 'value', splitLine: { lineStyle: { type: 'dashed' } } },
                            series: [{ name: '因子值', type: 'line', smooth: true, symbol: 'none',
                              data: seriesData.map(d => +(d.value || 0).toFixed(6)),
                              lineStyle: { color: '#1677ff', width: 2 }, areaStyle: { color: 'rgba(22,119,255,0.06)' } }],
                            dataZoom: [{ type: 'inside' }, { type: 'slider', height: 20 }],
                            grid: { left: 60, right: 16, bottom: 50, top: 44 },
                          }} />
                          <Table dataSource={seriesData} rowKey={(_, i) => i} size="small" style={{ marginTop: 12 }}
                            pagination={{ defaultPageSize: 20, showSizeChanger: true, pageSizeOptions: ['10', '20', '50', '100'], showTotal: t => `共 ${t} 条` }}
                            columns={[
                              { title: '交易日', dataIndex: 'calcDate', width: 120 },
                              { title: '股票代码', dataIndex: 'symbol', width: 130, render: v => <Tag color="blue">{v}</Tag> },
                              { title: '因子值', dataIndex: 'value', align: 'right', render: v => (+v).toFixed(6) },
                              { title: '排名(百分位)', dataIndex: 'rankValue', width: 100, align: 'center', render: v => v != null ? `${(+v * 100).toFixed(2)}%` : '-' },
                            ]} />
                        </>
                      ) : (
                        <div style={{ textAlign: 'center', padding: 60, color: '#bfbfbf' }}>
                          <TableOutlined style={{ fontSize: 36 }} />
                          <div style={{ marginTop: 8 }}>选择股票和时间区间后点击查询</div>
                        </div>
                      )}
                    </Card>
                  ),
                },
                {
                  key: 'cross',
                  label: '截面分布',
                  children: (
                    <Card bordered={false} style={{ padding: 0 }}>
                      <Space wrap style={{ marginBottom: 16 }}>
                        <Text strong>交易日期：</Text>
                        <DatePicker value={crossDate} onChange={v => v && setCrossDate(v)} format="YYYY-MM-DD" />
                        <Button type="primary" icon={<SearchOutlined />} onClick={loadCrossData} loading={crossLoading}>查询截面</Button>
                      </Space>
                      {crossLoading ? (
                        <div style={{ textAlign: 'center', padding: 60 }}><Spin /></div>
                      ) : crossData.length > 0 ? (
                        <>
                          <ReactECharts style={{ height: 240 }} option={(() => {
                            const vals = crossData.map(d => +d.value).filter(v => !isNaN(v)).sort((a, b) => a - b);
                            const min = vals[0], max = vals[vals.length - 1];
                            const bins = 20, step = (max - min) / bins || 0.001;
                            const buckets = Array.from({ length: bins }, (_, i) => ({ label: (min + i * step).toFixed(3), count: 0 }));
                            vals.forEach(v => { const idx = Math.min(Math.floor((v - min) / step), bins - 1); buckets[idx].count++; });
                            return {
                              tooltip: { trigger: 'axis', formatter: p => `区间 ${p[0].name}<br/>频次：${p[0].value}` },
                              xAxis: { type: 'category', data: buckets.map(b => b.label), axisLabel: { rotate: 45, fontSize: 9 }, name: '因子值' },
                              yAxis: { type: 'value', name: '频次' },
                              series: [{ type: 'bar', data: buckets.map(b => b.count), barCategoryGap: '5%', itemStyle: { color: '#1677ff', opacity: 0.75 } }],
                              grid: { left: 48, right: 16, bottom: 64, top: 40 },
                            };
                          })()}/>
                          <Table dataSource={crossData}
                            rowKey={(_, i) => i} size="small" style={{ marginTop: 12 }}
                            pagination={{ defaultPageSize: 20, showSizeChanger: true, pageSizeOptions: ['10', '20', '50', '100'], showTotal: t => `共 ${t} 条` }}
                            columns={[
                              { title: '排名', key: 'rank', width: 60, align: 'center', render: (_, __, i) => i + 1 },
                              { title: '股票代码', dataIndex: 'symbol', width: 110, render: v => <Tag color="blue">{v}</Tag> },
                              { title: '股票名称', dataIndex: 'stockName', width: 110, render: v => v || '-' },
                              { title: '因子值', dataIndex: 'value', align: 'right', render: v => v != null ? (+v).toFixed(6) : '-' },
                              {
                                title: '分位数', dataIndex: 'value', align: 'right', key: 'pct',
                                render: v => {
                                  const allVals = crossData.map(d => +d.value);
                                  const rank = allVals.filter(x => x <= +v).length;
                                  return `${(rank / allVals.length * 100).toFixed(1)}%`;
                                }
                              },
                            ]} />
                        </>
                      ) : (
                        <div style={{ textAlign: 'center', padding: 60, color: '#bfbfbf' }}>
                          <BarChartOutlined style={{ fontSize: 36 }} />
                          <div style={{ marginTop: 8 }}>选择交易日后点击查询截面</div>
                        </div>
                      )}
                    </Card>
                  ),
                },
              ]} />
            </div>
          ),
        },
      ]} />

      {/* 运行测试 Modal */}
      <Modal
        title={<Space><ExperimentOutlined /> 新增因子检测</Space>}
        open={testModal}
        onOk={handleTest}
        onCancel={() => setTestModal(false)}
        confirmLoading={testLoading}
        okText="开始检测"
        width={520}
        maskClosable
      >
        <Space direction="vertical" style={{ width: '100%' }} size={12}>
          {/* 数据就绪状态 */}
          {valueCount > 0 ? (
            <Alert
              type="success" showIcon
              icon={<CheckCircleOutlined />}
              message={`因子值就绪：${valueCount?.toLocaleString()} 条数据`}
              description="因子值已存在，可直接运行检测。"
            />
          ) : valueCount === 0 ? (
            <Alert
              type="warning" showIcon
              message="当前无因子值数据"
              description="点击「开始检测」将自动触发因子值计算，计算完成后再运行检测。"
            />
          ) : null}
          <div>
            <Typography.Text>检测名称：</Typography.Text>
            <Input
              value={testName}
              onChange={e => setTestName(e.target.value)}
              style={{ marginTop: 4 }}
            />
          </div>
          <div>
            <Typography.Text>检测时间区间：</Typography.Text>
            <RangePicker
              value={testDates}
              onChange={setTestDates}
              style={{ width: '100%', marginTop: 4 }}
            />
          </div>
          <Row gutter={12}>
            <Col span={12}>
              <div>
                <Typography.Text>股票池：</Typography.Text>
                <Select
                  value={testStockPool}
                  onChange={setTestStockPool}
                  style={{ width: '100%', marginTop: 4 }}
                >
                  <Option value="ALL_A">全部A股</Option>
                  <Option value="CSI300">沪深300</Option>
                  <Option value="CSI500">中证500</Option>
                  <Option value="CSI800">中证800</Option>
                  <Option value="CSI1000">中证1000</Option>
                </Select>
              </div>
            </Col>
            <Col span={12}>
              <div>
                <Typography.Text>调仓频率：</Typography.Text>
                <Select
                  value={testRebalanceFreq}
                  onChange={setTestRebalanceFreq}
                  style={{ width: '100%', marginTop: 4 }}
                >
                  <Option value="DAILY">日频</Option>
                  <Option value="WEEKLY">周频</Option>
                  <Option value="MONTHLY">月频</Option>
                </Select>
              </div>
            </Col>
          </Row>
          <Alert
            type="info" showIcon
            message="检测内容：IC分析（预测能力）、分组检测（选股能力）、单调性检验、统计显著性检验，耗时约 1~3 分钟。"
          />
        </Space>
      </Modal>
    </div>
  );
}

function round4(v) {
  if (typeof v !== 'number' || isNaN(v)) return 0;
  return Math.round(v * 10000) / 10000;
}
