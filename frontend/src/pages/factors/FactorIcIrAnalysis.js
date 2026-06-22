import React, { useState, useEffect, useMemo } from 'react';
import dayjs from 'dayjs';
import {
  Card, Row, Col, Select, Button, Tag, Spin, Alert, Space, Switch,
  Typography, Table, Tooltip, Popover, Divider, Statistic, DatePicker, InputNumber, App, Radio,
} from 'antd';
import {
  BarChartOutlined, LineChartOutlined, ReloadOutlined, InfoCircleOutlined,
  CheckCircleOutlined, WarningOutlined, CloseCircleOutlined, QuestionCircleOutlined,
  ArrowUpOutlined, ArrowDownOutlined, MinusOutlined, DownloadOutlined,
} from '@ant-design/icons';
import ReactECharts from 'echarts-for-react';
import { factorApi, strategyApi, recommendationApi } from '../../api';

const { Text, Title } = Typography;
const { RangePicker } = DatePicker;

// ─── CSV 导出工具 ──────────────────────────────────────────────────────────
function downloadCsv(headers, rows, filename) {
  const BOM = '\uFEFF';
  const escapeCsv = (v) => {
    if (v == null) return '';
    const s = String(v);
    return s.includes(',') || s.includes('"') || s.includes('\n') ? `"${s.replace(/"/g, '""')}"` : s;
  };
  const csvContent = BOM + headers.map(h => escapeCsv(h)).join(',') + '\n'
    + rows.map(r => r.map(c => escapeCsv(c)).join(',')).join('\n');
  const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

function exportResultsCsv(results) {
  const headers = ['因子代码', 'IC均值', 'IR', 'IC胜率(%)', 'p值', 't统计量', '样本天数', '评估'];
  const rows = results.filter(r => !r.error).map(r => [
    r.factorCode,
    r.icMean?.toFixed(4) ?? '',
    r.ir?.toFixed(4) ?? '',
    r.icWinRate?.toFixed(1) ?? '',
    r.pValue?.toFixed(4) ?? '',
    r.tStat?.toFixed(4) ?? '',
    r.sampleDays ?? '',
    r.assessment ?? '',
  ]);
  downloadCsv(headers, rows, `因子IC分析结果_${dayjs().format('YYYYMMDD_HHmmss')}.csv`);
}

function exportSegmentedCsv(mergedData, segLabels) {
  const [beforeLbl, afterLbl, fullLbl] = segLabels || ['前段', '后段', '全量'];
  const headers = [
    '因子代码',
    `${beforeLbl}_IC`, `${beforeLbl}_IR`, `${beforeLbl}_胜率(%)`, `${beforeLbl}_评估`, `${beforeLbl}_样本`,
    `${afterLbl}_IC`, `${afterLbl}_IR`, `${afterLbl}_胜率(%)`, `${afterLbl}_评估`, `${afterLbl}_样本`,
    `${fullLbl}_IC`, `${fullLbl}_IR`, `${fullLbl}_胜率(%)`, `${fullLbl}_评估`, `${fullLbl}_样本`,
    'IC变化',
  ];
  const getSubType = (r, seg) => {
    if (!r[seg]?.assessment) return '';
    if (r[seg].assessment !== '无效因子') return r[seg].assessment;
    // 无效因子附带子类型
    const { icMean, pValue, icStd } = r[seg];
    if (pValue > 0.05) return '无效因子(噪声型)';
    if (icMean < 0 && Math.abs(icMean) < 0.05) return '无效因子(弱负向型)';
    return '无效因子(不稳定型)';
  };
  const rows = mergedData.map(r => [
    r.factorCode,
    r.before?.icMean?.toFixed(4) ?? '', r.before?.ir?.toFixed(4) ?? '', r.before?.icWinRate?.toFixed(1) ?? '', r.before?.assessment ?? '', r.before?.sampleDays ?? '',
    r.after?.icMean?.toFixed(4) ?? '', r.after?.ir?.toFixed(4) ?? '', r.after?.icWinRate?.toFixed(1) ?? '', r.after?.assessment ?? '', r.after?.sampleDays ?? '',
    r.full?.icMean?.toFixed(4) ?? '', r.full?.ir?.toFixed(4) ?? '', r.full?.icWinRate?.toFixed(1) ?? '', r.full?.assessment ?? '', r.full?.sampleDays ?? '',
    r.icDelta?.toFixed(4) ?? '',
  ]);
  downloadCsv(headers, rows, `因子IC分段对比_${dayjs().format('YYYYMMDD_HHmmss')}.csv`);
}

// ─── 无效因子子类型判断 ──────────────────────────────────────────────────────────
function getInvalidSubtype(record) {
  if (!record) return null;
  const { icMean, pValue, icStd, ir } = record;
  // 噪声型：统计不显著，纯随机
  if (pValue > 0.05) return '噪声型';
  // 弱负向型：IC稳定为负，统计显著（p<0.05），有反向预测力但很弱
  if (icMean < 0 && Math.abs(icMean) < 0.05) return '弱负向型';
  // 不稳定型：IC均值≈0但波动大，IR很低，时灵时不灵
  return '不稳定型';
}

// ─── 因子稳定性分析文本生成 ───────────────────────────────────────────────────
function buildFactorAnalysis(record) {
  if (!record || record.error) return null;
  const { icMean, ir, pValue, icWinRate, sampleDays, assessment } = record;
  const lines = [];

  // IR 分析（IR 看绝对值，即信噪比）
  const absIr = Math.abs(ir || 0);
  if (absIr >= 0.5) {
    lines.push(`|IR|=${absIr.toFixed(2)} ≥ 0.5：信噪比高，IC 波动小，预测能力持续稳定`);
  } else if (absIr >= 0.3) {
    lines.push(`|IR|=${absIr.toFixed(2)} 在 0.3~0.5 之间：有一定稳定性，但波动较大`);
    lines.push('  └ 阈值来源：IR ≥ 0.5 为"强稳定"，0.3~0.5 为"弱稳定"（低于弱有效标准 IR≥0.3），< 0.3 视为不稳定');
  } else {
    lines.push(`|IR|=${absIr.toFixed(2)} < 0.3：信噪比极低，IC 序列波动剧烈，稳定性差`);
    lines.push('  └ 阈值来源：IR = IC均值/IC标准差，衡量"预测信号"与"随机噪声"的比值；< 0.3 时噪声占主导');
  }

  // p 值分析
  if (pValue != null) {
    if (pValue < 0.01) {
      lines.push(`p=${pValue.toFixed(4)} < 0.01：统计高度显著，预测能力非常可信`);
    } else if (pValue < 0.05) {
      lines.push(`p=${pValue.toFixed(4)} < 0.05：统计显著，预测能力可信`);
    } else {
      lines.push(`p=${pValue.toFixed(4)} ≥ 0.05：不显著，IC 可能为随机噪声，需谨慎`);
    }
  }

  // IC 胜率分析
  if (icWinRate != null) {
    if (icWinRate > 70) {
      lines.push(`IC 胜率 ${icWinRate.toFixed(1)}%：方向极其一致，稳定性强`);
    } else if (icWinRate > 50) {
      lines.push(`IC 胜率 ${icWinRate.toFixed(1)}%：方向偏向一致，但有一定波动`);
    } else {
      lines.push(`IC 胜率 ${icWinRate.toFixed(1)}%：方向不稳定，和抛硬币没区别`);
    }
  }

  // 样本天数
  if (sampleDays != null) {
    if (sampleDays < 20) {
      lines.push(`样本仅 ${sampleDays} 天：数据量严重不足，结果不可信`);
    } else if (sampleDays < 60) {
      lines.push(`样本 ${sampleDays} 天：数据量偏少，结果参考价值有限`);
    } else {
      lines.push(`样本 ${sampleDays} 天：数据量充足，结果可信`);
    }
  }

  // 综合结论
  if (assessment === '有效因子') {
    lines.push('结论：真正稳定有效的因子，可以放心使用');
  } else if (assessment === '弱有效') {
    lines.push('结论：有一定预测力但稳定性不足，建议小仓位或结合其他因子使用');
  } else {
    const subtype = getInvalidSubtype(record);
    if (subtype === '噪声型') {
      lines.push('结论：「噪声型」因子，不稳定、不可信，即使 IC 为正也不该给权重');
    } else if (subtype === '弱负向型') {
      lines.push('结论：「弱负向型」因子，有反向预测力但很弱，建议反向使用或剔除');
    } else {
      lines.push('结论：「不稳定型」因子，时灵时不灵，不建议单独使用');
    }
  }

  return lines;
}

// ─── 分段对比结果渲染 ───────────────────────────────────────────────────────
function renderSegmentedResults(segmentedResults, segPageSize, setSegPageSize, handleViewTrend) {
  if (!segmentedResults || !segmentedResults.segments) return null;
  const segs = segmentedResults.segments;
  
  // 将三个段的因子结果合并为一行
  const factorMap = {};
  ['before', 'after', 'full'].forEach(seg => {
    (segs[seg]?.results || []).forEach(r => {
      if (!r.factorCode) return;
      if (!factorMap[r.factorCode]) factorMap[r.factorCode] = { factorCode: r.factorCode };
      factorMap[r.factorCode][seg] = r;
    });
  });
  const mergedData = Object.values(factorMap);
  
  // 计算IC变化方向
  mergedData.forEach(r => {
    const bIc = r.before?.icMean || 0;
    const aIc = r.after?.icMean || 0;
    r.icDelta = Math.round((aIc - bIc) * 10000) / 10000;
    r.icFlipped = (bIc > 0 && aIc < 0) || (bIc < 0 && aIc > 0);
    r.icDecayed = Math.abs(aIc) < Math.abs(bIc) * 0.5;
  });

  // 构建分段子列（每段5列：IC/IR/胜率/评估/样本）
  const segColSimple = (segKey) => [
    { title: 'IC', dataIndex: [segKey, 'icMean'], key: segKey + '_ic', width: 72,
      render: (_, r) => {
        const v = r[segKey]?.icMean;
        return v != null ? <Text style={{ color: v >= 0 ? '#ef5350' : '#26a69a', fontWeight: 600, fontSize: 12 }}>{(+v * 100).toFixed(2)}%</Text> : '-';
      },
    },
    { title: 'IR', dataIndex: [segKey, 'ir'], key: segKey + '_ir', width: 64,
      render: (_, r) => {
        const v = r[segKey]?.ir;
        return v != null ? <Text strong style={{ fontSize: 12 }}>{(+v).toFixed(2)}</Text> : '-';
      },
    },
    { title: '胜率', dataIndex: [segKey, 'icWinRate'], key: segKey + '_wr', width: 64,
      render: (_, r) => {
        const v = r[segKey]?.icWinRate;
        return v != null ? v.toFixed(0) + '%' : '-';
      },
    },
    { title: '评估', dataIndex: [segKey, 'assessment'], key: segKey + '_as', width: 180,
      render: (_, r) => <AssessmentTag assessment={r[segKey]?.assessment} subType={r[segKey]?.assessment === '无效因子' ? getInvalidSubtype(r[segKey]) : null} />,
    },
    { title: '样本', dataIndex: [segKey, 'sampleDays'], key: segKey + '_sd', width: 80,
      render: (_, r) => r[segKey]?.sampleDays ?? '-',
    },
  ];

  // 分组表头颜色区分（前段蓝色/后段橙色/全量绿色，加粗+图标）
  const groupHeaderStyle = (color, icon) => ({
    background: color, fontWeight: 700, textAlign: 'center', fontSize: 13,
    borderRadius: '4px 4px 0 0', padding: '4px 8px',
    border: `2px solid ${color}`, borderBottom: 'none',
  });

  const segColumns = [
    {
      title: '因子代码',
      dataIndex: 'factorCode',
      key: 'factorCode',
      width: 160,
      fixed: 'left',
      ellipsis: false,
      render: (v, r) => {
        const err = r.before?.error || r.after?.error || r.full?.error;
        if (err) return <Tooltip title={err}><Text type="danger" style={{ cursor: 'not-allowed' }}>{v}</Text></Tooltip>;
        return <a onClick={() => handleViewTrend(v)}>{v}</a>;
      },
    },
    {
      title: <span style={groupHeaderStyle('#dbeafe', '#1890ff')}>📅 前段</span>,
      key: 'grp_before',
      children: segColSimple('before'),
    },
    {
      title: <span style={groupHeaderStyle('#ffedd5', '#fa8c16')}>📊 后段</span>,
      key: 'grp_after',
      children: segColSimple('after'),
    },
    {
      title: <span style={groupHeaderStyle('#d1f7c4', '#52c41a')}>📈 全量</span>,
      key: 'grp_full',
      children: segColSimple('full'),
    },
    {
      title: <span>IC变化 <InfoCircleOutlined style={{ color: '#bbb', fontSize: 10 }} /></span>,
      key: 'icDelta',
      width: 115,
      fixed: 'right',
      render: (_, r) => {
        if (r.before?.error || r.after?.error) return '-';
        const delta = r.icDelta;
        const icon = delta > 0.005 ? <ArrowUpOutlined style={{ color: '#ef5350', fontSize: 10 }} /> :
                       delta < -0.005 ? <ArrowDownOutlined style={{ color: '#26a69a', fontSize: 10 }} /> :
                       <MinusOutlined style={{ color: '#999', fontSize: 10 }} />;
        const bgColor = r.icFlipped ? '#fff7e6' : 'transparent';
        return <span style={{ background: bgColor, padding: '2px 6px', borderRadius: 4, fontSize: 12 }}>{icon}<Text style={{ color: delta >= 0 ? '#ef5350' : '#26a69a', fontWeight: r.icFlipped ? 700 : 400, marginLeft: 4, fontSize: 12 }}>{delta >= 0 ? '+' : ''}{(+delta).toFixed(2)}</Text></span>;
      },
    },
  ];
  
  const bLabel = segs.before?.label || '前段';
  const aLabel = segs.after?.label || '后段';
  const fLabel = segs.full?.label || "全量";
  
  const cardTitle = (
    <span>分段对比结果
      <Tag color="blue" style={{ marginLeft: 8, fontSize: 11 }}>{bLabel}</Tag>
      <span style={{ margin: "0 4px", color: "#999" }}>→</span>
      <Tag color="orange" style={{ fontSize: 11 }}>{aLabel}</Tag>
      <Tag style={{ marginLeft: 8, fontSize: 11 }}>{fLabel}</Tag>
    </span>
  );
  
  return (
    <Card title={cardTitle} size="small" style={{ marginBottom: 16 }}
      extra={<Button size="small" icon={<DownloadOutlined />} onClick={() => exportSegmentedCsv(mergedData, [bLabel, aLabel, fLabel])} disabled={!mergedData || mergedData.length === 0}>导出CSV</Button>}
    >
      {mergedData.some(r => r.icFlipped) && (
        <Alert type="warning" showIcon message={`${mergedData.filter(r => r.icFlipped).length} 个因子IC方向发生反转（前后段符号相反），可能因市场环境切换导致因子失效`} style={{ marginBottom: 12 }} />
      )}
      <Table dataSource={mergedData} columns={segColumns} rowKey="factorCode" size="small"
        sticky={{ offsetHeader: 0 }}
        pagination={mergedData.length > segPageSize ? {
          pageSize: segPageSize, showSizeChanger: true, pageSizeOptions: ['8', '15', '30', '50'],
          showTotal: t => `共 ${t} 条`, onShowSizeChange: (current, size) => setSegPageSize(size),
        } : false}
        onChange={(pagination) => setSegPageSize(pagination.pageSize)}
        scroll={{ x: 1500 }}
      />
    </Card>
  );
}

// ─── 评估标签 ─────────────────────────────────────────────────────────────────
function AssessmentTag({ assessment, subType }) {
  if (!assessment) return '-';
  const bgMap = { '有效因子': '#f6ffed', '弱有效': '#fff7e6', '无效因子': '#fff2f0' };
  const borderMap = { '有效因子': '#b7eb8f', '弱有效': '#ffd591', '无效因子': '#ffccc7' };
  const textMap = { '有效因子': '#389e0d', '弱有效': '#d48806', '无效因子': '#cf1322' };
  const bg = bgMap[assessment] || '#f5f5f5';
  const border = borderMap[assessment] || '#d9d9d9';
  const color = textMap[assessment] || '#333';
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', flexWrap: 'nowrap', gap: 3 }}>
      <span style={{ display: 'inline-flex', alignItems: 'center', background: bg, border: `1px solid ${border}`, borderRadius: 4, color, fontSize: 11, padding: '1px 6px', whiteSpace: 'nowrap', lineHeight: '18px', height: 20 }}>{assessment}</span>
      {subType && <span style={{ display: 'inline-flex', alignItems: 'center', background: '#fafafa', border: '1px solid #d9d9d9', borderRadius: 4, color: '#666', fontSize: 10, padding: '1px 4px', whiteSpace: 'nowrap', lineHeight: '16px', height: 18 }}>{subType}</span>}
    </span>
  );
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
        return `${p.axisValue}<br/>IC: <b>${(+p.value * 100).toFixed(2)}%</b>`;
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
  const [analyzeProgress, setAnalyzeProgress] = useState(0);
  const [results, setResults] = useState(null);
  const [segmentedMode, setSegmentedMode] = useState(false);
  const [splitDate, setSplitDate] = useState(dayjs('2026-01-01'));
  const [segmentedResults, setSegmentedResults] = useState(null);
  const [neutralizeByIndustry, setNeutralizeByIndustry] = useState(false);
  const [correlationType, setCorrelationType] = useState('spearman'); // 'spearman' | 'pearson'
  const [icThreshold, setIcThreshold] = useState(0.03); // 复合IC因子预筛选阈值
  const [savingIc, setSavingIc] = useState(false);
  const [saveLogs, setSaveLogs] = useState([]);
  // 分页受控状态
  const [resultsPageSize, setResultsPageSize] = useState(10);
  const [segPageSize, setSegPageSize] = useState(8);

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

  // 批量 IC/IR 分析（分批调用，真实进度）
  const handleAnalyze = async () => {
    if (selectedCodes.length === 0) {
      message.warning('请选择至少1个因子');
      return;
    }
    if (!dateRange[0] || !dateRange[1]) {
      message.warning('请选择日期范围');
      return;
    }
    // 固定因子列表和总数，避免 await 期间 state 变化导致进度计算错误
    const codes = [...selectedCodes];
    const total = codes.length;
    setLoading(true);
    setAnalyzeProgress(0);
    setResults(null);
    setSegmentedResults(null);
    setTrendData(null);
    setTrendFactor(null);
    setSaveLogs([]);

    const BATCH_SIZE = 5;
    const allResults = [];
    // 分段模式需要跨批次累积 results
    const accSegBefore = [];
    const accSegAfter = [];
    const accSegFull = [];
    let completed = 0;

    try {
      for (let i = 0; i < total; i += BATCH_SIZE) {
        const batch = codes.slice(i, i + BATCH_SIZE);
        if (segmentedMode) {
          if (!splitDate) { message.warning('请选择分段日期'); setLoading(false); setAnalyzeProgress(0); return; }
          const batchData = await factorApi.batchIcIrSegmented(
            batch,
            dateRange[0].format('YYYY-MM-DD'),
            dateRange[1].format('YYYY-MM-DD'),
            splitDate.format('YYYY-MM-DD'),
            forwardDays,
            neutralizeByIndustry,
            correlationType,
            icThreshold,
          );
          if (batchData?.segments) {
            if (batchData.segments.before?.results) accSegBefore.push(...batchData.segments.before.results);
            if (batchData.segments.after?.results) accSegAfter.push(...batchData.segments.after.results);
            if (batchData.segments.full?.results) {
              accSegFull.push(...batchData.segments.full.results);
              allResults.push(...batchData.segments.full.results);
            }
          }
        } else {
          const batchData = await factorApi.batchIcIrAnalysis(
            batch,
            dateRange[0].format('YYYY-MM-DD'),
            dateRange[1].format('YYYY-MM-DD'),
            forwardDays,
            neutralizeByIndustry,
            correlationType,
            icThreshold,
          );
          if (Array.isArray(batchData)) {
            allResults.push(...batchData);
          } else if (batchData) {
            allResults.push(batchData);
          }
        }
        completed += batch.length;
        setAnalyzeProgress(Math.min(Math.round((completed / total) * 100), 100));
      }
      if (segmentedMode) {
        // 将累积的全部分段结果组装成与单次调用相同的结构
        const assembled = {
          segmented: true,
          splitDate: splitDate?.format('YYYY-MM-DD'),
          forwardDays,
          segments: {
            before: {
              label: `${dateRange[0].format('YYYY-MM-DD')} ~ ${splitDate?.format('YYYY-MM-DD')}`,
              startDate: dateRange[0].format('YYYY-MM-DD'),
              endDate: splitDate?.format('YYYY-MM-DD'),
              results: accSegBefore,
            },
            after: {
              label: `${splitDate?.format('YYYY-MM-DD')} ~ ${dateRange[1].format('YYYY-MM-DD')}`,
              startDate: splitDate?.format('YYYY-MM-DD'),
              endDate: dateRange[1].format('YYYY-MM-DD'),
              results: accSegAfter,
            },
            full: {
              label: `${dateRange[0].format('YYYY-MM-DD')} ~ ${dateRange[1].format('YYYY-MM-DD')} (全量)`,
              startDate: dateRange[0].format('YYYY-MM-DD'),
              endDate: dateRange[1].format('YYYY-MM-DD'),
              results: accSegFull,
            },
          },
        };
        setSegmentedResults(assembled);
      } else {
        setResults(allResults);
      }
    } catch (e) {
      message.error(segmentedMode ? '分段IC/IR 分析失败' : 'IC/IR 分析失败，请稍后重试');
    }
    setAnalyzeProgress(100);
    setLoading(false);
    setTimeout(() => setAnalyzeProgress(0), 1200);
  };

  // 批量保存 IC 到数据库（分片请求，实时滚动日志）
  const handleSaveIc = async () => {
    if (!dateRange[0] || !dateRange[1]) {
      message.warning('请选择日期范围');
      return;
    }
    if (selectedCodes.length === 0) {
      message.warning('请先选择至少一个因子（先分析或手动选择因子）');
      return;
    }
    setSavingIc(true);
    setResults(null);
    setTrendData(null);
    setTrendFactor(null);
    const startDate = dateRange[0].format('YYYY-MM-DD');
    const endDate = dateRange[1].format('YYYY-MM-DD');
    const factorList = [...selectedCodes].sort();

    // 按月分片，每个月的请求更新一次进度日志
    const chunks = [];
    let cursor = dayjs(startDate);
    const end = dayjs(endDate);
    while (cursor.isBefore(end) || cursor.isSame(end, 'day')) {
      const chunkEnd = cursor.endOf('month');
      chunks.push({
        label: cursor.format('YYYY-MM'),
        start: cursor.format('YYYY-MM-DD'),
        end: (chunkEnd.isAfter(end) ? end : chunkEnd).format('YYYY-MM-DD'),
      });
      cursor = chunkEnd.add(1, 'day');
    }

    const totalChunks = chunks.length;
    let totalRecords = 0;
    let totalDays = 0;
    // 按因子累积 IC 数据，最后统一算统计
    const factorIcValues = {};
    factorList.forEach(f => { factorIcValues[f] = []; });

    // 初始日志
    const logs = [
      `═══════════════════════════════════════`,
      `  因子IC批量保存`,
      `  日期: ${startDate} ~ ${endDate}`,
      `  因子: ${factorList.join(', ')}`,
      `  分片: ${totalChunks} 批（按月）`,
      `═══════════════════════════════════════`,
      ``,
    ];
    setSaveLogs([...logs]);

    try {
      for (let i = 0; i < totalChunks; i++) {
        const chunk = chunks[i];
        const chunkLog = `[${i + 1}/${totalChunks}] ${chunk.label} (${chunk.start} ~ ${chunk.end})`;

        // 更新日志：正在处理中
        setSaveLogs([...logs, `${chunkLog} 处理中...`]);

        let chunkResult;
        try {
          chunkResult = await recommendationApi.computeIcBatch(chunk.start, chunk.end, selectedCodes);
        } catch (chunkErr) {
          setSaveLogs([...logs, `${chunkLog} 失败: ${chunkErr.message || '未知错误'}`]);
          continue;
        }

        const chunkRecords = chunkResult?.totalRecords ?? 0;
        const chunkDays = chunkResult?.totalDays ?? 0;
        const detailMap = chunkResult?.results || {};

        totalRecords += chunkRecords;
        totalDays += chunkDays;

        // 收集各因子 IC 值
        Object.keys(detailMap).forEach(d => {
          const dayRes = detailMap[d] || {};
          factorList.forEach(f => {
            const rec = dayRes[f];
            if (rec && rec.icValue != null) {
              factorIcValues[f].push(Number(rec.icValue));
            }
          });
        });

        // 更新日志：完成
        logs.push(`${chunkLog} ✓ ${chunkRecords}条`);
        setSaveLogs([...logs]);
      }

      // 最终汇总
      logs.push(``);
      logs.push(`═══════════════════════════════════════`);
      logs.push(`  完成: ${totalRecords} 条记录 / ${totalDays} 天 / ${factorList.length} 因子`);
      logs.push(`═══════════════════════════════════════`);
      logs.push(``);
      logs.push(`各因子IC统计:`);
      factorList.forEach(f => {
        const vals = factorIcValues[f] || [];
        if (vals.length === 0) {
          logs.push(`  ${f}: 无数据`);
          return;
        }
        const mean = vals.reduce((a, b) => a + b, 0) / vals.length;
        const sorted = [...vals].sort((a, b) => a - b);
        const median = sorted.length % 2 === 0
          ? (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2
          : sorted[Math.floor(sorted.length / 2)];
        const std = Math.sqrt(vals.reduce((s, v) => s + (v - mean) ** 2, 0) / vals.length);
        const ir = std !== 0 ? mean / std : 0;
        const winRate = (vals.filter(v => v > 0).length / vals.length * 100).toFixed(1);
        logs.push(`  ${f.padEnd(8)} n=${vals.length}  mean=${mean.toFixed(4)}  median=${median.toFixed(4)}  std=${std.toFixed(4)}  IR=${ir.toFixed(2)}  win=${winRate}%`);
      });
      setSaveLogs([...logs]);
      message.success(`IC保存完成，共 ${totalRecords} 条记录`, 3);
    } catch (e) {
      console.error('[IC Save] error:', e);
      logs.push(``);
      logs.push(`错误: ${e.message || '未知错误'}`);
      setSaveLogs([...logs]);
      message.error('IC保存失败: ' + (e.message || '未知错误'));
    }
    setSavingIc(false);
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
      width: 160,
      render: (v, r) => {
        if (r.error) {
          return (
            <Tooltip title={r.error || '无因子数据，无法查看IC趋势'}>
              <Text type="danger" style={{ cursor: 'not-allowed' }}>{v}</Text>
            </Tooltip>
          );
        }
        // 复合因子特殊展示
        if (r.composite) {
          const ff = r.filteredFactors || [];
          // 以 compositeSize 为权威数据源（后端 usedCount），filteredFactors 可能因旧版本缺失
          const keptCount = ff.length > 0 ? ff.length : (r.compositeSize || 0);
          const excludedCount = selectedCodes.length - keptCount;
          // 根据 factorCode 判断权重类型
          const weightKey = r.factorCode?.includes('EQW') ? 'weightEqw'
            : r.factorCode?.includes('ICW') ? 'weightIcw' : 'weightOpt';
          return (
            <span>
              <Popover
                trigger="click"
                content={
                  <div style={{ fontSize: 12, lineHeight: '20px', maxWidth: 440, maxHeight: 340, overflowY: 'auto' }}>
                    <div style={{ fontWeight: 'bold', marginBottom: 6, fontSize: 13 }}>{r.factorName || v} — 组合详情</div>

                    {/* 预筛选摘要 */}
                    <div style={{ marginBottom: 6, display: 'flex', gap: 8, alignItems: 'center' }}>
                      <Tag color="green" style={{ fontSize: 11 }}>✓ 保留 {keptCount} 个</Tag>
                      {excludedCount > 0 && <Tag color="default" style={{ fontSize: 11 }}>✗ 剔除 {excludedCount} 个</Tag>}
                      <Text type="secondary" style={{ fontSize: 10 }}>(阈值 |IC| ≥ {(icThreshold * 100).toFixed(0)}%)</Text>
                    </div>

                    <Divider style={{ margin: '4px 0' }} />

                    {/* 组合方式说明 */}
                    <div style={{ fontWeight: 'bold', marginBottom: 4, fontSize: 12 }}>组合方式说明</div>
                    {r.factorCode?.includes('EQW') && (
                      <div style={{ marginBottom: 4 }}><Tag color="gold" style={{ fontSize: 10 }}>等权</Tag> 每个因子权重 = 1/N，简单平均</div>
                    )}
                    {r.factorCode?.includes('ICW') && (
                      <div style={{ marginBottom: 4 }}><Tag color="gold" style={{ fontSize: 10 }}>|IC|加权</Tag> 权重 ∝ 各因子|IC|，强因子权重大</div>
                    )}
                    {r.factorCode?.includes('OPT') && (
                      <div style={{ marginBottom: 4 }}><Tag color="gold" style={{ fontSize: 10 }}>逆方差</Tag> 权重 ∝ 1/σ²(IC)，稳定因子权重大</div>
                    )}
                    <div style={{ marginBottom: 4, color: '#52c41a' }}>信号方向：负IC因子自动取反（z-score × sign），避免抵消 ✓</div>

                    <Divider style={{ margin: '8px 0' }} />

                    {/* 实际参与因子列表 */}
                    <div style={{ fontWeight: 'bold', marginBottom: 6, fontSize: 12 }}>
                      实际参与组合的因子（{keptCount}个）
                      <Text type="secondary" style={{ fontWeight: 'normal' }}> — 按权重排序</Text>
                    </div>
                    {ff.length > 0 ? (
                      <table style={{ width: '100%', fontSize: 11, borderCollapse: 'collapse' }}>
                        <thead>
                          <tr style={{ borderBottom: '1px solid #eee' }}>
                            <th style={{ textAlign: 'left', padding: '2px 4px', width: '35%' }}>因子代码</th>
                            <th style={{ textAlign: 'right', padding: '2px 4px', width: '20%' }}>原始 IC</th>
                            <th style={{ textAlign: 'center', padding: '2px 4px', width: '12%' }}>方向</th>
                            <th style={{ textAlign: 'right', padding: '2px 4px', width: '33%' }}>本方案权重</th>
                          </tr>
                        </thead>
                        <tbody>
                          {[...ff].sort((a, b) => (b[weightKey] || 0) - (a[weightKey] || 0)).map((f, idx) => {
                            const w = f[weightKey];
                            return (
                              <tr key={f.code} style={{ borderBottom: idx < ff.length - 1 ? '1px solid #f5f5f5' : 'none' }}>
                                <td style={{ padding: '3px 4px' }}>
                                  <Tag color="blue" style={{ fontSize: 10, margin: 0 }}>{f.code}</Tag>
                                </td>
                                <td style={{
                                  textAlign: 'right', padding: '3px 4px',
                                  color: (f.ic || 0) >= 0 ? '#cf1322' : '#3f8600',
                                  fontWeight: Math.abs(f.ic || 0) >= 0.05 ? 'bold' : 'normal',
                                }}>
                                  {(f.ic != null ? (f.ic * 100).toFixed(2) : '-') + '%'}
                                </td>
                                <td style={{ textAlign: 'center', padding: '3px 4px' }}>
                                  <span style={{
                                    color: f.sign > 0 ? '#3f8600' : '#cf1322',
                                    fontWeight: 'bold',
                                    fontSize: 12,
                                  }}>
                                    {f.sign > 0 ? '↑正向' : '↓取反'}
                                  </span>
                                </td>
                                <td style={{ textAlign: 'right', padding: '3px 4px' }}>
                                  <span style={{ fontWeight: 'bold', color: '#d48806' }}>
                                    {w != null ? (w * 100).toFixed(1) + '%' : '-'}
                                  </span>
                                </td>
                              </tr>
                            );
                          })}
                        </tbody>
                      </table>
                    ) : (
                      keptCount > 0 ? (
                        <Alert type="warning" showIcon
                          message={`参与组合的 ${keptCount} 个因子详情不可用，请确认后端已重启至最新版本`}
                          style={{ fontSize: 11, padding: '4px 8px' }} />
                      ) : (
                        <Alert type="error" showIcon message="无因子通过预筛选阈值" style={{ fontSize: 11, padding: '4px 8px' }} />
                      )
                    )}

                    {excludedCount > 0 && (
                      <>
                        <Divider style={{ margin: '8px 0' }} />
                        <div style={{ fontWeight: 'bold', marginBottom: 4, fontSize: 12, color: '#999' }}>
                          被剔除的因子 ({excludedCount}个，|IC| &lt; {(icThreshold * 100).toFixed(0)}%)
                        </div>
                        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 2 }}>
                          {selectedCodes.filter(c => !ff.some(f => f.code === c)).map(code => {
                            const icRow = (results || []).find(row => row.factorCode === code && !row.composite);
                            return (
                              <Tag key={code} style={{ fontSize: 10, opacity: 0.5, cursor: 'default' }}>
                                {code}
                                <Text type="secondary" style={{ fontSize: 9, marginLeft: 2 }}>
                                  IC={icRow ? ((icRow.icMean ?? 0) * 100).toFixed(2) + '%' : '?'}
                                </Text>
                              </Tag>
                            );
                          })}
                        </div>
                      </>
                    )}
                  </div>
                }
              >
                <Tag color="gold" style={{ fontSize: 11, cursor: 'pointer' }}>{r.factorName || v}</Tag>
                <QuestionCircleOutlined style={{ color: '#d48806', fontSize: 11, marginLeft: 3, cursor: 'pointer' }} />
              </Popover>
              <Text type="secondary" style={{ fontSize: 10, marginLeft: 4 }}>({r.compositeSize}因子)</Text>
            </span>
          );
        }
        const analysis = buildFactorAnalysis(r);
        return (
          <span>
            <a onClick={() => handleViewTrend(v)}>{v}</a>
            <Popover
              content={
                <div style={{ fontSize: 12, lineHeight: '20px', maxWidth: 380 }}>
                  <div style={{ fontWeight: 'bold', marginBottom: 8, fontSize: 13 }}>{v} 稳定性分析</div>
                  {analysis.map((line, i) => (
                    <div key={i} style={{ marginBottom: 4, display: 'flex', alignItems: 'flex-start' }}>
                      <span style={{ marginRight: 6, color: '#1890ff' }}>•</span>
                      <span>{line}</span>
                    </div>
                  ))}
                </div>
              }
            >
              <QuestionCircleOutlined style={{ marginLeft: 6, color: '#bbb', fontSize: 12, cursor: 'help' }} />
            </Popover>
          </span>
        );
      },
    },
    {
      title: 'IC 均值',
      dataIndex: 'icMean',
      key: 'icMean',
      width: 90,
      sorter: (a, b) => (a.icMean || 0) - (b.icMean || 0),
      render: v => v != null ? <Text style={{ color: v >= 0 ? '#ef5350' : '#26a69a', fontWeight: 600 }}>{(+v * 100).toFixed(2)}%</Text> : '-',
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
      title: 't 统计量',
      dataIndex: 'tStat',
      key: 'tStat',
      width: 85,
      sorter: (a, b) => Math.abs(a.tStat || 0) - Math.abs(b.tStat || 0),
      render: v => {
        if (v == null) return '-';
        const absV = Math.abs(v);
        return <Text style={{ color: absV > 2.58 ? '#1b5e20' : absV > 1.96 ? '#2e7d32' : '#666', fontWeight: absV > 1.96 ? 600 : 400 }}>
          {(+v).toFixed(2)}
        </Text>;
      },
    },
    {
      title: (
        <Tooltip
          title={
            <div style={{ fontSize: 12, lineHeight: '18px' }}>
              <div style={{ fontWeight: 'bold', marginBottom: 4 }}>p 值（显著性检验）</div>
              <div style={{ marginBottom: 4 }}>衡量 IC 均值是否显著异于 0，判断因子预测能力是「真信号」还是「随机噪声」：</div>
              <div>• p &lt; 0.01（深绿）：高度显著，预测能力非常可信</div>
              <div>• p &lt; 0.05（绿色）：统计显著，预测能力可信</div>
              <div>• p ≥ 0.05（灰色）：不显著，IC 可能为随机波动，需谨慎使用</div>
            </div>
          }
        >
          <span>p 值 <InfoCircleOutlined style={{ color: '#bbb', fontSize: 11 }} /></span>
        </Tooltip>
      ),
      dataIndex: 'pValue',
      key: 'pValue',
      width: 90,
      sorter: (a, b) => (a.pValue || 1) - (b.pValue || 1),
      render: v => {
        if (v == null) return '-';
        return <Text style={{ color: v < 0.01 ? '#1b5e20' : v < 0.05 ? '#2e7d32' : '#999', fontWeight: v < 0.05 ? 600 : 400 }}>
          {(+v).toFixed(4)}
        </Text>;
      },
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
      render: (v, record) => <AssessmentTag assessment={v} subType={v === '无效因子' ? getInvalidSubtype(record) : null} />,
    },
  ];

  // 统计汇总
  const summary = useMemo(() => {
    if (!results || results.length === 0) return null;
    const valid = results.filter(r => !r.error);
    if (valid.length === 0) return null;
    const fixNegZero = (v) => (Math.abs(v) < 0.0001 ? 0 : v);
    const avgIc = fixNegZero(valid.reduce((s, r) => s + (r.icMean || 0), 0) / valid.length);
    const avgIr = fixNegZero(valid.reduce((s, r) => s + (r.ir || 0), 0) / valid.length);
    const avgWin = fixNegZero(valid.reduce((s, r) => s + (r.icWinRate || 0), 0) / valid.length);
    const effective = valid.filter(r => r.assessment === '有效因子').length;
    const weak = valid.filter(r => r.assessment === '弱有效').length;
    const positiveIcCount = valid.filter(r => (r.icMean || 0) > 0).length;
    const negativeIcCount = valid.filter(r => (r.icMean || 0) < 0).length;
    const compositeCount = valid.filter(r => r.composite).length;
    return { total: valid.length, avgIc, avgIr, avgWin, effective, weak, positiveIcCount, negativeIcCount, compositeCount };
  }, [results]);

  return (
    <div>
      <div className="page-header">
        <Title level={4} style={{ margin: 0 }}>
          <BarChartOutlined style={{ marginRight: 8 }} />
          因子 IC 管理
          <Popover
            trigger="click"
            placement="rightBottom"
            content={
              <div style={{ whiteSpace: 'nowrap', maxHeight: '60vh', overflowY: 'auto' }}>
                <p style={{ margin: '0 0 6px' }}><b>什么是 IC/IR？</b></p>
                <p style={{ margin: '0 0 6px', whiteSpace: 'normal' }}>IC（信息系数）：因子值与未来收益率的横截面相关系数。IC 越大，因子预测能力越强。</p>
                <p style={{ margin: '0 0 6px', whiteSpace: 'normal' }}>IR（信息比率）：IC均值/IC标准差。衡量因子预测的稳定性，IR 越高越可靠。</p>
                <p style={{ margin: '0 0 6px', whiteSpace: 'normal' }}><b style={{ color: '#fa8c16' }}>⚠️ 前提：因子值必须存在</b> — 仅当 factor_value 表中有该因子的时序数据时才能分析，无数据的因子代码将显示为红色不可点击。</p>
                <p style={{ margin: '0 0 6px' }}><b>IC 结果速查：</b></p>
                <table style={{ margin: '0 0 8px', borderCollapse: 'collapse', fontSize: 12, width: '100%' }}>
                  <thead>
                    <tr style={{ background: '#f5f5f5' }}>
                      <th style={{ border: '1px solid #e8e8e8', padding: '4px 8px', textAlign: 'left' }}>指标</th>
                      <th style={{ border: '1px solid #e8e8e8', padding: '4px 8px', textAlign: 'left' }}>含义</th>
                      <th style={{ border: '1px solid #e8e8e8', padding: '4px 8px', textAlign: 'left' }}>判断标准</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr>
                      <td style={{ border: '1px solid #e8e8e8', padding: '4px 8px' }}>IC 符号</td>
                      <td style={{ border: '1px solid #e8e8e8', padding: '4px 8px' }}>因子和收益的方向关系</td>
                      <td style={{ border: '1px solid #e8e8e8', padding: '4px 8px' }}>正号 = 因子值越高收益越好<br/>负号 = 因子值越低收益越好</td>
                    </tr>
                    <tr>
                      <td style={{ border: '1px solid #e8e8e8', padding: '4px 8px' }}>IC 绝对值</td>
                      <td style={{ border: '1px solid #e8e8e8', padding: '4px 8px' }}>关系有多强</td>
                      <td style={{ border: '1px solid #e8e8e8', padding: '4px 8px' }}>越大越强（≥0.05 有效）</td>
                    </tr>
                    <tr>
                      <td style={{ border: '1px solid #e8e8e8', padding: '4px 8px' }}>t 统计量</td>
                      <td style={{ border: '1px solid #e8e8e8', padding: '4px 8px' }}>关系是真实的还是碰巧的</td>
                      <td style={{ border: '1px solid #e8e8e8', padding: '4px 8px' }}>|t| &gt; 1.96 = 统计显著<br/>|t| &gt; 2.58 = 高度显著</td>
                    </tr>
                  </tbody>
                </table>
                <p style={{ margin: '0 0 6px', marginTop: 8 }}><b>IR 阈值说明（看 |IR| 绝对值，即信噪比）：</b></p>
                <table style={{ margin: '0 0 8px', borderCollapse: 'collapse', fontSize: 12, width: '100%' }}>
                  <thead>
                    <tr style={{ background: '#f5f5f5' }}>
                      <th style={{ border: '1px solid #e8e8e8', padding: '4px 8px', textAlign: 'left' }}>|IR| 范围</th>
                      <th style={{ border: '1px solid #e8e8e8', padding: '4px 8px', textAlign: 'left' }}>解读</th>
                      <th style={{ border: '1px solid #e8e8e8', padding: '4px 8px', textAlign: 'left' }}>阈值来源</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr>
                      <td style={{ border: '1px solid #e8e8e8', padding: '4px 8px' }}>≥ 0.5</td>
                      <td style={{ border: '1px solid #e8e8e8', padding: '4px 8px' }}>信噪比高，IC 波动小，预测能力持续稳定</td>
                      <td style={{ border: '1px solid #e8e8e8', padding: '4px 8px' }}>有效因子门槛</td>
                    </tr>
                    <tr>
                      <td style={{ border: '1px solid #e8e8e8', padding: '4px 8px' }}>0.3 ~ 0.5</td>
                      <td style={{ border: '1px solid #e8e8e8', padding: '4px 8px' }}>有一定稳定性，但波动较大</td>
                      <td style={{ border: '1px solid #e8e8e8', padding: '4px 8px' }}>弱有效门槛</td>
                    </tr>
                    <tr>
                      <td style={{ border: '1px solid #e8e8e8', padding: '4px 8px' }}>&lt; 0.3</td>
                      <td style={{ border: '1px solid #e8e8e8', padding: '4px 8px' }}>信噪比极低，IC 序列波动剧烈，稳定性差</td>
                      <td style={{ border: '1px solid #e8e8e8', padding: '4px 8px' }}>噪声占主导</td>
                    </tr>
                  </tbody>
                </table>
                <p style={{ margin: '0 0 4px', fontSize: 11, color: '#888', whiteSpace: 'normal' }}>1.96 不是凭空的 — 来自标准正态分布 97.5 分位数（α=0.05, 双侧）。含义：如果因子真无效(IC=0)，随机产生 |t|{'>'}{1.96} 的概率不到 5%，因此认定为真实规律。</p>
                <p style={{ margin: '0 0 6px' }}><b>IR 和 t 统计量什么关系？</b></p>
                <p style={{ margin: '0 0 4px', whiteSpace: 'normal' }}>两者本质相同，只是尺度不同：<b>t = IR × sqrt(n)</b>（n = 观察期数/交易天数）。</p>
                <p style={{ margin: '0 0 4px', whiteSpace: 'normal' }}>IR 是"每期"的信噪比，t 是累积到 n 期后的总信噪比。比如 IR=0.1，观察 400 天：t = 0.1 × 20 = 2.0，刚好跨过 1.96。</p>
                <p style={{ margin: '0 0 6px', whiteSpace: 'normal', fontSize: 11, color: '#888' }}>反过来算：要 |t|{'>'}{1.96}，需要的 IR_min = 1.96/sqrt(n)。n=100 → IR≥0.20，n=400 → IR≥0.10。</p>

                <p style={{ margin: '0 0 6px' }}><b>评估标准：</b></p>
                <p style={{ margin: '0 0 4px' }}>有效因子：IC均值 ≥ 0.05 且 IR ≥ 0.5</p>
                <p style={{ margin: '0 0 4px' }}>弱有效：IC均值 ≥ 0.03 且 IR ≥ 0.3</p>
                <p style={{ margin: '0 0 6px' }}>无效：低于弱有效标准</p>
                <p style={{ margin: '0 0 6px' }}><b>评估列说明（三档分级快速决策）：</b></p>
                <table style={{ margin: '0 0 8px', borderCollapse: 'collapse', fontSize: 12, width: '100%' }}>
                  <thead>
                    <tr style={{ background: '#f5f5f5' }}>
                      <th style={{ border: '1px solid #e8e8e8', padding: '4px 8px', textAlign: 'left' }}>评估</th>
                      <th style={{ border: '1px solid #e8e8e8', padding: '4px 8px', textAlign: 'left' }}>价值</th>
                      <th style={{ border: '1px solid #e8e8e8', padding: '4px 8px', textAlign: 'left' }}>推断与操作建议</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr>
                      <td style={{ border: '1px solid #e8e8e8', padding: '4px 8px' }}>有效因子</td>
                      <td style={{ border: '1px solid #e8e8e8', padding: '4px 8px' }}>主力因子</td>
                      <td style={{ border: '1px solid #e8e8e8', padding: '4px 8px' }}>预测能力强且稳定，可直接作为策略核心，给大权重，单独用也能跑赢</td>
                    </tr>
                    <tr>
                      <td style={{ border: '1px solid #e8e8e8', padding: '4px 8px' }}>弱有效</td>
                      <td style={{ border: '1px solid #e8e8e8', padding: '4px 8px' }}>辅助因子</td>
                      <td style={{ border: '1px solid #e8e8e8', padding: '4px 8px' }}>有预测力但波动大，适合小权重搭配使用，不宜单独押注</td>
                    </tr>
                    <tr>
                      <td style={{ border: '1px solid #e8e8e8', padding: '4px 8px' }}>无效因子</td>
                      <td style={{ border: '1px solid #e8e8e8', padding: '4px 8px' }}>风险因子</td>
                      <td style={{ border: '1px solid #e8e8e8', padding: '4px 8px' }}>统计不显著或极不稳定，通常应剔除；具体看子类型决定是剔除还是反向用</td>
                    </tr>
                  </tbody>
                </table>
                <p style={{ margin: '0 0 6px' }}><b>无效因子排查指南：</b></p>
                <table style={{ margin: '0 0 8px', borderCollapse: 'collapse', fontSize: 12, width: '100%' }}>
                  <thead>
                    <tr style={{ background: '#f5f5f5' }}>
                      <th style={{ border: '1px solid #e8e8e8', padding: '4px 8px', textAlign: 'left' }}>类型</th>
                      <th style={{ border: '1px solid #e8e8e8', padding: '4px 8px', textAlign: 'left' }}>特征</th>
                      <th style={{ border: '1px solid #e8e8e8', padding: '4px 8px', textAlign: 'left' }}>判断</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr>
                      <td style={{ border: '1px solid #e8e8e8', padding: '4px 8px' }}>噪声型</td>
                      <td style={{ border: '1px solid #e8e8e8', padding: '4px 8px' }}>IC≈0, IR≈0, p&gt;0.05</td>
                      <td style={{ border: '1px solid #e8e8e8', padding: '4px 8px' }}>真没用，可考虑移除</td>
                    </tr>
                    <tr>
                      <td style={{ border: '1px solid #e8e8e8', padding: '4px 8px' }}>弱负向型</td>
                      <td style={{ border: '1px solid #e8e8e8', padding: '4px 8px' }}>IC 稳定为负但偏小, p&lt;0.05</td>
                      <td style={{ border: '1px solid #e8e8e8', padding: '4px 8px' }}>有反向预测能力，可利用（做空）</td>
                    </tr>
                    <tr>
                      <td style={{ border: '1px solid #e8e8e8', padding: '4px 8px' }}>不稳定型</td>
                      <td style={{ border: '1px solid #e8e8e8', padding: '4px 8px' }}>IC 均值≈0 但波动大, IR 很低</td>
                      <td style={{ border: '1px solid #e8e8e8', padding: '4px 8px' }}>时灵时不灵，慎用</td>
                    </tr>
                  </tbody>
                </table>
                <p style={{ margin: '0 0 6px' }}><b>如何使用：</b></p>
                <p style={{ margin: '0 0 4px' }}>1. 选因子+日期+前瞻天数 → 点"分析"</p>
                <p style={{ margin: '0 0 4px' }}>2. 查看结果表格，点击因子代码查看IC趋势</p>
                <p style={{ margin: '0 0 4px' }}>3. 累计IC持续上升 = 因子稳定有效</p>
                <p style={{ margin: 0 }}>4. 用有效因子构建策略或优化权重</p>
              </div>
            }
            styles={{ root: {maxWidth: 640} }}
          >
            <InfoCircleOutlined style={{ marginLeft: 6, color: '#bbb', fontSize: 16, cursor: 'pointer' }} />
          </Popover>
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
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                <Text type="secondary" style={{ fontSize: 12 }}>
                  选择因子
                  <Tooltip title={`已选 ${selectedCodes.length} / ${factorList.filter(f => f.status === 'ACTIVE').length} 个ACTIVE因子`}>
                    <InfoCircleOutlined style={{ marginLeft: 4, color: '#bbb' }} />
                  </Tooltip>
                </Text>
                <Button size="small" type="link" onClick={() => {
                  const activeCodes = factorList.filter(f => f.status === 'ACTIVE').map(f => f.factorCode);
                  setSelectedCodes(activeCodes);
                  setSelectedStrategyId(null);
                  message.success(`已全选 ${activeCodes.length} 个因子`);
                }} style={{ fontSize: 11, padding: 0, height: 22 }}>
                  全选
                </Button>
              </div>
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
                maxCount={50}
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
          <Col span={6}>
            <Space direction="vertical" size={4} style={{ width: '100%' }}>
              <Text type="secondary" style={{ fontSize: 12 }}>
                前瞻天数
                <Tooltip
                  title={
                    <div style={{ fontSize: 12, lineHeight: '18px' }}>
                      <div style={{ fontWeight: 'bold', marginBottom: 4 }}>前瞻天数（Forward Days）</div>
                      <div style={{ marginBottom: 6 }}>计算「因子值」与「未来第 N 个交易日收益率」的 Spearman 秩相关系数（IC）。</div>
                      <div style={{ marginBottom: 4 }}><b>常用取值：</b></div>
                      <div>• 1 天：次日收益（超短线，噪声大，IC 极不稳定）</div>
                      <div>• 5 天：一周收益（短线交易，最常用）</div>
                      <div>• 20 天：一月收益（中线持仓）</div>
                      <div>• 60 天：季度收益（长线配置）</div>
                      <div style={{ marginTop: 6, color: '#ff4d4f' }}><b>注意：</b>N 越大，预测难度越高，IC 绝对值通常越低；但信号更稳定，过拟合风险更小。</div>
                    </div>
                  }
                >
                  <InfoCircleOutlined style={{ marginLeft: 4, color: '#bbb' }} />
                </Tooltip>
              </Text>
              <InputNumber min={1} max={60} value={forwardDays} onChange={setForwardDays} style={{ width: '100%' }} />
            </Space>
          </Col>
        </Row>
        <Row gutter={[16, 16]} justify="start" style={{ marginTop: 8, marginBottom: 8 }}>
          <Col>
            <Space>
              <Text type="secondary" style={{ fontSize: 12 }}>分段对比</Text>
              <Switch
                checked={segmentedMode}
                onChange={(v) => { setSegmentedMode(v); setResults(null); setSegmentedResults(null); }}
                checkedChildren="开"
                unCheckedChildren="关"
              />
              {segmentedMode && (
                <DatePicker
                  value={splitDate}
                  onChange={setSplitDate}
                  style={{ width: 140 }}
                  placeholder="切分日期"
                />
              )}
              <Text type="secondary" style={{ fontSize: 12, marginLeft: 16 }}>行业中性化</Text>
              <Switch
                checked={neutralizeByIndustry}
                onChange={setNeutralizeByIndustry}
                checkedChildren="开"
                unCheckedChildren="关"
              />
              <Tooltip title="对每个交易日的因子值按行业分组做z-score标准化，消除行业beta对IC的影响">
                <InfoCircleOutlined style={{ color: '#bbb', fontSize: 10 }} />
              </Tooltip>
              <Divider type="vertical" />
              <Text type="secondary" style={{ fontSize: 12 }}>IC方法</Text>
              <Radio.Group
                value={correlationType}
                onChange={e => setCorrelationType(e.target.value)}
                size="small"
                buttonStyle="solid"
              >
                <Radio.Button value="spearman">Spearman</Radio.Button>
                <Radio.Button value="pearson">Pearson</Radio.Button>
              </Radio.Group>
              <Popover
                trigger="click"
                content={
                  <div style={{ fontSize: 12, lineHeight: '20px', maxWidth: 360 }}>
                    <div style={{ fontWeight: 'bold', marginBottom: 6, fontSize: 13 }}>Spearman vs Pearson 区别</div>
                    <div style={{ marginBottom: 8 }}>
                      <Tag color="default" style={{ fontSize: 11, marginBottom: 2 }}>Spearman 秩相关</Tag>
                      <div>• 基于<b>排名</b>计算，非参数方法</div>
                      <div>• 对异常值和分布形态<b>不敏感</b></div>
                      <div>• 适合捕捉任意<b>单调关系</b>（不限于线性）</div>
                      <div>• <b>A股市场最常用</b>，适合因子值分布偏态的场景</div>
                    </div>
                    <div>
                      <Tag color="cyan" style={{ fontSize: 11, marginBottom: 2 }}>Pearson 线性相关</Tag>
                      <div>• 基于<b>原始值</b>计算，参数方法</div>
                      <div>• 对异常值<b>敏感</b>，受极端值影响大</div>
                      <div>• 只适合<b>严格线性关系</b></div>
                      <div>• 假设因子值和收益率服从正态分布</div>
                    </div>
                  </div>
                }
              >
                <QuestionCircleOutlined style={{ color: '#bbb', fontSize: 12, cursor: 'help' }} />
              </Popover>
              <Divider type="vertical" />
              <Text type="secondary" style={{ fontSize: 12 }}>复合IC阈值</Text>
              <InputNumber
                min={0} max={0.20} step={0.01}
                value={icThreshold}
                onChange={v => setIcThreshold(v ?? 0.03)}
                style={{ width: 80 }}
                size="small"
              />
              <Tooltip title="预筛选：只有|IC|≥此阈值的因子才参与多因子组合；同时自动对齐信号方向（负IC因子取反），避免信号抵消">
                <InfoCircleOutlined style={{ color: '#bbb', fontSize: 10 }} />
              </Tooltip>
            </Space>
          </Col>
        </Row>
        <Row gutter={[16, 16]} justify="start" style={{ marginTop: 16 }}>
          <Col>
            <Space>
              <Button type="primary" icon={loading ? <ReloadOutlined spin /> : <BarChartOutlined />} onClick={handleAnalyze} loading={loading}>
                {loading ? (analyzeProgress > 0 ? `分析中 ${analyzeProgress}%` : '分析中...') : '分析'}
              </Button>
              <Button
                icon={<LineChartOutlined />}
                loading={savingIc}
                onClick={handleSaveIc}
              >
                保存IC数据
              </Button>
            </Space>
          </Col>
        </Row>
      </Card>

      {/* 汇总统计 */}
      {summary && (
        <div style={{ display: 'flex', gap: 8, marginBottom: 16, flexWrap: 'wrap' }}>
          <Card size="small" style={{ flex: '1 1 0', minWidth: 100 }}><Statistic title="分析因子数" value={summary.total} /></Card>
          <Card size="small" style={{ flex: '1 1 0', minWidth: 100 }}><Statistic title="平均 IC" value={summary.avgIc} precision={2} /></Card>
          <Card size="small" style={{ flex: '1 1 0', minWidth: 100 }}><Statistic title="平均 IR" value={summary.avgIr} precision={2} /></Card>
          <Card size="small" style={{ flex: '1 1 0', minWidth: 110 }}><Statistic title="正 IC（参与加权）" value={summary.positiveIcCount} valueStyle={{ color: '#1890ff' }} /></Card>
          <Card size="small" style={{ flex: '1 1 0', minWidth: 115 }}><Statistic title="负 IC（权重置零）" value={summary.negativeIcCount} valueStyle={{ color: '#ff4d4f' }} /></Card>
          <Card size="small" style={{ flex: '1 1 0', minWidth: 100 }}><Statistic title="有效因子" value={summary.effective} valueStyle={{ color: '#52c41a' }} /></Card>
          {summary.compositeCount > 0 && (
            <Card size="small" style={{ flex: '1 1 0', minWidth: 100 }}><Statistic title="复合因子" value={summary.compositeCount} valueStyle={{ color: '#faad14' }} /></Card>
          )}
        </div>
      )}

      {/* 结果表格 */}
      {results && (
        <Card title={<Space>分析结果{neutralizeByIndustry ? <Tag color="purple" style={{ fontSize: 11 }}>行业中性化(百分位秩)</Tag> : null}{correlationType === 'pearson'
                    ? <Tooltip title="Pearson线性相关系数：基于原始值计算，对异常值敏感，需假设正态分布"><Tag color="cyan" style={{ fontSize: 11 }}>Pearson <QuestionCircleOutlined style={{ fontSize: 10 }} /></Tag></Tooltip>
                    : <Tooltip title="Spearman秩相关系数：基于排名计算，对异常值不敏感，A股最常用"><Tag color="default" style={{ fontSize: 11 }}>Spearman <QuestionCircleOutlined style={{ fontSize: 10 }} /></Tag></Tooltip>
                  }<Text type="secondary" style={{ fontSize: 12 }}>（点击因子代码查看IC趋势）</Text></Space>} size="small" style={{ marginBottom: 16 }}
          extra={<Button size="small" icon={<DownloadOutlined />} onClick={() => exportResultsCsv(results)} disabled={!results || results.length === 0}>导出CSV</Button>}
        >
          <Table
            dataSource={results}
            columns={columns}
            rowKey="factorCode"
            size="small"
            sticky={{ offsetHeader: 0 }}
            pagination={results.length > 10 ? {
              pageSize: resultsPageSize,
              showSizeChanger: true,
              pageSizeOptions: ['10', '20', '50'],
              showTotal: t => `共 ${t} 条`,
              onShowSizeChange: (current, size) => setResultsPageSize(size),
            } : false}
            onChange={(pagination) => setResultsPageSize(pagination.pageSize)}
            scroll={{ x: 900 }}
          />
        </Card>
      )}
      {/* ─── 分段对比结果 ─── */}
      {segmentedResults && renderSegmentedResults(segmentedResults, segPageSize, setSegPageSize, handleViewTrend)}

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

      {/* 保存IC数据日志 */}
      {saveLogs.length > 0 && (
        <Card
          title={<span><LineChartOutlined style={{ marginRight: 8 }} />保存IC数据日志</span>}
          size="small"
          style={{ marginTop: 16 }}
          extra={<Button size="small" onClick={() => setSaveLogs([])}>清空日志</Button>}
        >
          <div
            style={{
              background: '#1e1e1e',
              color: '#d4d4d4',
              fontFamily: 'Consolas, "Courier New", monospace',
              fontSize: 12,
              lineHeight: '20px',
              padding: '12px 16px',
              borderRadius: 4,
              maxHeight: 400,
              overflowY: 'auto',
              whiteSpace: 'pre-wrap',
              wordBreak: 'break-all',
            }}
          >
            {saveLogs.map((line, i) => (
              <div key={i} style={{
                color: line.includes('error=') || line.includes('失败') ? '#f48771' :
                       line.includes('saved=OK') ? '#4ec9b0' : '#d4d4d4'
              }}>
                {line}
              </div>
            ))}
          </div>
        </Card>
      )}
    </div>
  );
}
