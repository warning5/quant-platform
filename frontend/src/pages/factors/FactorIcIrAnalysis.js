import React, { useState, useEffect, useMemo } from 'react';
import dayjs from 'dayjs';
import {
  Card, Row, Col, Select, Button, Tag, Spin, Alert, Space,
  Typography, Table, Tooltip, Popover, Divider, Statistic, DatePicker, InputNumber, App,
} from 'antd';
import {
  BarChartOutlined, LineChartOutlined, ReloadOutlined, InfoCircleOutlined,
  CheckCircleOutlined, WarningOutlined, CloseCircleOutlined, QuestionCircleOutlined,
} from '@ant-design/icons';
import ReactECharts from 'echarts-for-react';
import { factorApi, strategyApi, recommendationApi } from '../../api';

const { Text, Title } = Typography;
const { RangePicker } = DatePicker;

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

// ─── 评估标签 ─────────────────────────────────────────────────────────────────
function AssessmentTag({ assessment, subType }) {
  if (!assessment) return null;
  const map = {
    '有效因子': { color: 'green', icon: <CheckCircleOutlined /> },
    '弱有效': { color: 'orange', icon: <WarningOutlined /> },
    '无效因子': { color: 'red', icon: <CloseCircleOutlined /> },
  };
  const cfg = map[assessment] || { color: 'default', icon: null };
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center' }}>
      <Tag color={cfg.color} icon={cfg.icon}>{assessment}</Tag>
      {subType && <Tag style={{ marginLeft: 4 }} color="default">{subType}</Tag>}
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
  const [savingIc, setSavingIc] = useState(false);
  const [saveLogs, setSaveLogs] = useState([]);

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
    setSaveLogs([]);
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
      width: 120,
      render: (v, r) => {
        if (r.error) {
          return (
            <Tooltip title={r.error || '无因子数据，无法查看IC趋势'}>
              <Text type="danger" style={{ cursor: 'not-allowed' }}>{v}</Text>
            </Tooltip>
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
    const fixNegZero = (v) => (Math.abs(v) < 0.0001 ? 0 : v);
    const avgIc = fixNegZero(valid.reduce((s, r) => s + (r.icMean || 0), 0) / valid.length);
    const avgIr = fixNegZero(valid.reduce((s, r) => s + (r.ir || 0), 0) / valid.length);
    const avgWin = fixNegZero(valid.reduce((s, r) => s + (r.icWinRate || 0), 0) / valid.length);
    const effective = valid.filter(r => r.assessment === '有效因子').length;
    const weak = valid.filter(r => r.assessment === '弱有效').length;
    const positiveIcCount = valid.filter(r => (r.icMean || 0) > 0).length;
    const negativeIcCount = valid.filter(r => (r.icMean || 0) < 0).length;
    return { total: valid.length, avgIc, avgIr, avgWin, effective, weak, positiveIcCount, negativeIcCount };
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
            overlayStyle={{ maxWidth: 640 }}
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
        <Row gutter={[16, 16]} justify="start" style={{ marginTop: 16 }}>
          <Col>
            <Space>
              <Button type="primary" icon={<BarChartOutlined />} onClick={handleAnalyze} loading={loading}>
                分析
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
            <Card size="small"><Statistic title="正 IC（参与加权）" value={summary.positiveIcCount} valueStyle={{ color: '#1890ff' }} /></Card>
          </Col>
          <Col span={4}>
            <Card size="small"><Statistic title="负 IC（权重置零）" value={summary.negativeIcCount} valueStyle={{ color: '#ff4d4f' }} /></Card>
          </Col>
          <Col span={4}>
            <Card size="small"><Statistic title="有效因子" value={summary.effective} valueStyle={{ color: '#52c41a' }} /></Card>
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
