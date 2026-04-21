import React, { useState, useEffect, useRef } from 'react';
import dayjs from 'dayjs';
import {
  Card, Row, Col, Table, Button, Space, Typography, Spin, Alert,
  Form, InputNumber, Select, Slider, Tag, Tooltip, Divider, Badge,
  Steps, Statistic, Empty, Input, Switch, DatePicker, Modal, message,
  Collapse, Popconfirm,
} from 'antd';
const { Panel } = Collapse;
const { RangePicker } = DatePicker;
import {
  ArrowLeftOutlined, PlayCircleOutlined, TrophyOutlined,
  ExperimentOutlined, SettingOutlined, BarChartOutlined,
  InfoCircleOutlined, ReloadOutlined, DeleteOutlined,
  QuestionCircleOutlined, RocketOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import ReactECharts from 'echarts-for-react';
import { backtestApi, strategyApi } from '../../api';

const { Title, Text } = Typography;
const { Option } = Select;

const fmtPct = (v, d = 2) => v != null ? `${(+v * 100).toFixed(d)}%` : '-';
const fmt = (v, d = 3) => v != null ? (+v).toFixed(d) : '-';

// 参数名中文映射
const paramNameMap = {
  stopLossPct: '止损比例',
  stopProfitPct: '止盈比例',
  maxPositionCount: '持仓数量',
  rebalanceFrequency: '调仓频率',
  initialCapital: '初始资金',
  taskId: '回测ID',
  calmarRatio: '卡尔马比率',
  volatility: '波动率',
  objective: '优化目标',
  annualReturn: '年化收益率',
  maxDrawdown: '最大回撤',
  winRate: '胜率',
  totalReturn: '总收益',
  totalTrades: '交易次数',
  profitFactor: '盈利因子',
};

// 指标中文解释（鼠标悬停查看详情）
const metricDescriptions = {
  // ── 优化参数 ──
  stopLossPct: (
    <div style={{ lineHeight: 1.6 }}>
      <b>止损比例</b>：当单只股票亏损达到此比例时，自动平仓止损<br/><br/>
      <b>数据含义：</b><br/>
      • 0.05 = 亏损 5% 时止损<br/>
      • 0.10 = 亏损 10% 时止损<br/>
      • 0.15 = 亏损 15% 时止损<br/><br/>
      <b>建议范围：</b>0.05 ~ 0.20<br/>
      止损过小（频繁触发）、过大（亏损扛不住）
    </div>
  ),
  stopProfitPct: (
    <div style={{ lineHeight: 1.6 }}>
      <b>止盈比例</b>：当单只股票盈利达到此比例时，自动平仓止盈<br/><br/>
      <b>数据含义：</b><br/>
      • 0.10 = 盈利 10% 时止盈<br/>
      • 0.20 = 盈利 20% 时止盈<br/>
      • 0.30 = 盈利 30% 时止盈<br/><br/>
      <b>建议范围：</b>0.10 ~ 0.50
    </div>
  ),
  maxPositionCount: (
    <div style={{ lineHeight: 1.6 }}>
      <b>持仓数量</b>：同时持有的最大股票数量<br/><br/>
      <b>数据含义：</b><br/>
      • 10 = 最多同时持有 10 只股票<br/>
      • 20 = 最多同时持有 20 只股票<br/>
      • 30 = 最多同时持有 30 只股票<br/><br/>
      <b>建议范围：</b>10 ~ 30<br/>
      数量过多分散收益，过少集中风险
    </div>
  ),
  rebalanceFrequency: (
    <div style={{ lineHeight: 1.6 }}>
      <b>调仓频率</b>：定期重新调整仓位的频率<br/><br/>
      • DAILY = 每日调仓<br/>
      • WEEKLY = 每周调仓<br/>
      • MONTHLY = 每月调仓
    </div>
  ),
  // ── 绩效指标 ──
  sharpeRatio: (
    <div style={{ lineHeight: 1.6 }}>
      <b>夏普比率（Sharpe Ratio）</b>：最经典的风险调整收益指标<br/><br/>
      <b>公式：</b>(年化收益率 - 无风险利率) ÷ 年化波动率<br/><br/>
      <b>数据含义：</b><br/>
      • &lt; 0 = 收益还不如存银行<br/>
      • 0 ~ 1 = 收益一般<br/>
      • 1 ~ 2 = 较好，风险调整收益可观<br/>
      • 2 ~ 3 = 优秀，能稳定获取超额收益<br/>
      • &gt; 3 = 极佳（注意是否过拟合）<br/><br/>
      <b>本例：6.49</b> = 极高，说明策略收益远高于波动风险
    </div>
  ),
  calmarRatio: (
    <div style={{ lineHeight: 1.6 }}>
      <b>卡尔马比率（Calmar Ratio）</b>：年化收益 ÷ 最大回撤<br/><br/>
      <b>公式：</b>年化收益率 ÷ 最大回撤幅度<br/><br/>
      <b>数据含义：</b><br/>
      • &lt; 1 = 收益不如最大亏损<br/>
      • 1 ~ 2 = 收益尚可<br/>
      • 2 ~ 5 = 良好，收益远超回撤风险<br/>
      • &gt; 5 = 极佳<br/><br/>
      <b>本例：37.79</b> = 年化收益是最大回撤的 37.79 倍，极优
    </div>
  ),
  volatility: (
    <div style={{ lineHeight: 1.6 }}>
      <b>波动率（Volatility）</b>：收益率的标准差年化值，衡量风险<br/><br/>
      <b>公式：</b>日收益率标准差 × √252（年化）<br/><br/>
      <b>数据含义：</b><br/>
      • &lt; 0.5 = 低波动，稳健型策略<br/>
      • 0.5 ~ 1.0 = 中等波动<br/>
      • 1.0 ~ 2.0 = 高波动，收益/风险都较大<br/>
      • &gt; 2.0 = 极高波动，慎用<br/><br/>
      <b>本例：1.018</b> = 年化波动约 101.8%
    </div>
  ),
  annualReturn: (
    <div style={{ lineHeight: 1.6 }}>
      <b>年化收益率</b>：将总收益折算为按年计算的收益率<br/><br/>
      <b>公式：</b>(1 + 总收益率)^(252/交易日) - 1<br/><br/>
      <b>数据含义：</b><br/>
      • 0.1 = 年化收益 10%<br/>
      • 0.5 = 年化收益 50%<br/>
      • 1.0 = 年化收益翻倍<br/><br/>
      <b>本例：6.64</b> = 年化收益 664%，即本金翻了 7.6 倍
    </div>
  ),
  maxDrawdown: (
    <div style={{ lineHeight: 1.6 }}>
      <b>最大回撤（Max Drawdown）</b>：从历史最高点到最低点的最大亏损幅度<br/><br/>
      <b>数据含义：</b><br/>
      • 0.05 = 最大亏损 5%<br/>
      • 0.10 = 最大亏损 10%<br/>
      • 0.20 = 最大亏损 20%<br/>
      • 0.50 = 最大亏损 50%（腰斩）<br/><br/>
      <b>本例：0.1757</b> = 历史最大亏损 17.57%
    </div>
  ),
  winRate: (
    <div style={{ lineHeight: 1.6 }}>
      <b>胜率</b>：盈利交易次数 ÷ 总交易次数<br/><br/>
      <b>数据含义：</b><br/>
      • &lt; 0.5 = 输多赢少<br/>
      • 0.5 ~ 0.6 = 正常水平<br/>
      • &gt; 0.6 = 高胜率策略<br/><br/>
      提示：高胜率≠赚钱，还需看盈亏比
    </div>
  ),
  profitFactor: (
    <div style={{ lineHeight: 1.6 }}>
      <b>盈利因子（Profit Factor）</b>：盈利总额 ÷ 亏损总额<br/><br/>
      <b>数据含义：</b><br/>
      • &lt; 1 = 亏损（入不敷出）<br/>
      • 1 ~ 1.5 = 基本持平<br/>
      • 1.5 ~ 2.0 = 良好<br/>
      • &gt; 2.0 = 极佳<br/><br/>
      PF = 2 表示每亏 1 元能赚 2 元
    </div>
  ),
  totalTrades: (
    <div style={{ lineHeight: 1.6 }}>
      <b>交易次数</b>：回测期间的总买卖交易次数<br/>
      （买入或卖出各算一次）
    </div>
  ),
  totalReturn: '总收益率（不分年化，从期初到期末的累计收益）',
  score: (
    <div style={{ lineHeight: 1.6 }}>
      <b>优化得分</b>：根据选择的优化目标计算的分值<br/><br/>
      <b>目标选项：</b><br/>
      • sharpeRatio（夏普比率）= 最常用<br/>
      • annualReturn（年化收益）= 追求最高收益<br/>
      • calmarRatio（卡尔马比率）= 追求收益/回撤比
    </div>
  ),
};

// 辅助：带问号提示的指标标签
const MetricLabel = ({ metric, children }) => {
  const desc = metricDescriptions[metric] || metricDescriptions[children] || '';
  return (
    <Tooltip title={desc}>
      <span style={{ cursor: 'help' }}>
        {children} <QuestionCircleOutlined style={{ fontSize: 12, color: '#999' }} />
      </span>
    </Tooltip>
  );
};

// ─── 热力图（显示两参数 × 目标函数分值）────────────────────────────────────────
function HeatmapChart({ results, paramNames, objective }) {
  if (!results || results.length === 0 || paramNames.length < 2) return null;
  const [p1, p2] = paramNames;

  const p1Vals = [...new Set(results.map(r => r[p1]))].sort((a, b) => a - b);
  const p2Vals = [...new Set(results.map(r => r[p2]))].sort((a, b) => a - b);

  const dataMap = {};
  results.forEach(r => {
    const key = `${r[p1]}_${r[p2]}`;
    dataMap[key] = r.score;
  });

  const heatData = [];
  p1Vals.forEach((v1, i) => {
    p2Vals.forEach((v2, j) => {
      const score = dataMap[`${v1}_${v2}`];
      if (score != null) heatData.push([i, j, +score.toFixed(4)]);
    });
  });

  const minScore = Math.min(...heatData.map(d => d[2]));
  const maxScore = Math.max(...heatData.map(d => d[2]));

  const option = {
    tooltip: {
      formatter: p => `${paramNameMap[p1] || p1}=${p1Vals[p.data[0]]}, ${paramNameMap[p2] || p2}=${p2Vals[p.data[1]]}<br/>${paramNameMap[objective] || objective}: <b>${p.data[2]}</b>`,
    },
    xAxis: { type: 'category', data: p1Vals.map(String), name: paramNameMap[p1] || p1, nameLocation: 'end', nameTextStyle: { fontSize: 12 }, axisLabel: { fontSize: 11, rotate: p1Vals.length > 6 ? 30 : 0 } },
    yAxis: { type: 'category', data: p2Vals.map(String), name: paramNameMap[p2] || p2, nameLocation: 'end', nameTextStyle: { fontSize: 12 }, axisLabel: { fontSize: 11 } },
    visualMap: {
      min: minScore, max: maxScore, calculable: true,
      orient: 'horizontal', left: 'center', bottom: 20,
      inRange: { color: ['#3f8600', '#faad14', '#cf1322'] },
    },
    grid: { top: 40, left: 80, right: 60, bottom: 150 },
    series: [{
      type: 'heatmap',
      data: heatData,
      label: { show: heatData.length <= 50, formatter: p => p.data[2] },
      emphasis: { itemStyle: { shadowBlur: 10, shadowColor: 'rgba(0,0,0,0.5)' } },
    }],
  };

  return (
    <div style={{ padding: '12px 16px', border: '1px solid #d9d9d9', borderRadius: 4 }}>
      <ReactECharts option={option} style={{ height: 380 }} notMerge={true} />
    </div>
  );
}

// ─── 参数网格配置行 ─────────────────────────────────────────────────────────────
function ParamRow({ index, value, onChange, onRemove }) {
  const handleChange = (field, val) => onChange({ ...value, [field]: val });

  const generateValues = (min, max, step) => {
    if (!min && min !== 0 || !max || !step) return [];
    const vals = [];
    for (let v = +min; v <= +max; v += +step) vals.push(Math.round(v * 1000) / 1000);
    return vals;
  };

  const values = value.type === 'range'
    ? generateValues(value.min, value.max, value.step)
    : (value.values || []);

  return (
    <div style={{ display: 'flex', gap: 8, alignItems: 'flex-start', flexWrap: 'wrap', marginBottom: 8, padding: 8, background: '#fafafa', borderRadius: 6, border: '1px solid #f0f0f0' }}>
      <div style={{ minWidth: 80 }}>
        <Text type="secondary" style={{ fontSize: 11 }}>参数名</Text>
        <Select
          value={value.name}
          onChange={v => handleChange('name', v)}
          style={{ width: 140 }}
          size="small"
          placeholder="选择参数"
        >
          <Option value="maxPositionCount">持仓数量</Option>
          <Option value="stopLossPct">止损比例</Option>
          <Option value="stopProfitPct">止盈比例</Option>
          <Option value="initialCapital">初始资金</Option>
        </Select>
      </div>
      <div>
        <Text type="secondary" style={{ fontSize: 11 }}>类型</Text>
        <Select value={value.type || 'range'} onChange={v => handleChange('type', v)} size="small" style={{ width: 80 }}>
          <Option value="range">范围</Option>
          <Option value="list">列表</Option>
        </Select>
      </div>
      {value.type !== 'list' ? (
        <>
          <div>
            <Text type="secondary" style={{ fontSize: 11 }}>最小值</Text>
            <InputNumber value={value.min} onChange={v => handleChange('min', v)} size="small" style={{ width: 80 }} step={0.01} />
          </div>
          <div>
            <Text type="secondary" style={{ fontSize: 11 }}>最大值</Text>
            <InputNumber value={value.max} onChange={v => handleChange('max', v)} size="small" style={{ width: 80 }} step={0.01} />
          </div>
          <div>
            <Text type="secondary" style={{ fontSize: 11 }}>步长</Text>
            <InputNumber value={value.step} onChange={v => handleChange('step', v)} size="small" style={{ width: 80 }} step={0.01} />
          </div>
          <div style={{ paddingTop: 16 }}>
            <Tooltip title={value.type === 'range'
              ? `计算公式: (${value.max} - ${value.min}) / ${value.step} + 1 = ${values.length} 个值`
              : `自定义列表，共 ${values.length} 个值`}>
              <Tag color="blue">{values.length} 个值</Tag>
            </Tooltip>
          </div>
        </>
      ) : (
        <div style={{ flex: 1, minWidth: 200 }}>
          <Text type="secondary" style={{ fontSize: 11 }}>值（逗号分隔）</Text>
          <Input
            value={(value.values || []).join(',')}
            onChange={e => handleChange('values', e.target.value.split(',').map(v => isNaN(+v.trim()) ? v.trim() : +v.trim()).filter(v => v !== ''))}
            size="small"
            placeholder="10,20,30"
          />
        </div>
      )}
      <div style={{ paddingTop: 16, marginLeft: 'auto' }}>
        <Button size="small" danger onClick={onRemove}>删除</Button>
      </div>
    </div>
  );
}

// ─── 解析参数网格显示 ───────────────────────────────────────────────────────
const parseParamGrid = (gridJson) => {
  if (!gridJson) return '-';
  try {
    const grid = typeof gridJson === 'string' ? JSON.parse(gridJson) : gridJson;
    if (!Array.isArray(grid) || grid.length === 0) return '-';
    return grid.map(p => `${paramNameMap[p.name] || p.name}: [${p.values?.join(', ')}]`).join('; ');
  } catch { return '-'; }
};

// ─── 任务详情展开行 ─────────────────────────────────────────────────────────
function TaskDetail({ job: initialJob, onToggle, onComplete, navigate, strategies }) {
  const [detail, setDetail] = useState(null);
  const [results, setResults] = useState([]);
  const [paramNames, setParamNames] = useState([]);
  const [tablePage, setTablePage] = useState(1);
  const [tablePageSize, setTablePageSize] = useState(10);
  const [currentJob, setCurrentJob] = useState(initialJob);
  const wasCompleted = useRef(false);
  const [applyModalVisible, setApplyModalVisible] = useState(false);

  // currentJob 始终同步 initialJob（包括 done/total 变化），保证进度实时更新
  useEffect(() => {
    setCurrentJob(prev => {
      // 避免不必要的状态更新：仅在关键字段变化时更新
      if (prev.jobId !== initialJob.jobId || prev.status !== initialJob.status ||
          prev.done !== initialJob.done || prev.total !== initialJob.total) {
        return { ...initialJob };
      }
      return prev;
    });
  }, [initialJob.jobId, initialJob.status, initialJob.done, initialJob.total]);

  // 加载详情数据（仅挂载时调用一次）
  useEffect(() => {
    if (!initialJob || !initialJob.jobId || initialJob.status === 'PENDING') return;
    backtestApi.getParamOptimizeResult(initialJob.jobId)
      .then(data => {
        if (data) {
          setDetail(data);
          const res = data.results || [];
          setResults(res);
          if (res.length > 0) {
            const metrics = ['score', 'annualReturn', 'maxDrawdown', 'sharpeRatio', 'winRate', 'totalReturn', 'totalTrades', 'profitFactor', 'calmarRatio', 'volatility', 'objective', 'taskId', 'annualizedReturn', 'maxDrawdownPct'];
            const names = Object.keys(res[0]).filter(k => !metrics.includes(k));
            setParamNames(names);
          }
          // 任务完成时通知父组件刷新列表
          const respStatus = data.status;
          if (respStatus === 'COMPLETED' && !wasCompleted.current) {
            wasCompleted.current = true;
            if (onComplete) onComplete();
          }
        }
      })
      .catch(() => {});
  }, [initialJob.jobId]);

  // 运行中任务轮询：每 3 秒刷新一次详情数据（耗时、进度、结果数同步更新）
  useEffect(() => {
    if (!initialJob?.jobId || initialJob.status === 'PENDING') return;
    if (initialJob.status === 'COMPLETED') return; // 已完成无需轮询

    const poll = setInterval(() => {
      backtestApi.getParamOptimizeResult(initialJob.jobId)
      .then(data => {
        if (!data) return;
          const { results: newResults, status, done, total, ...rest } = data;
          setDetail(prev => {
            if (!prev) return data;
            return { ...prev, ...data };
          });
          if (newResults?.length !== undefined) {
            setResults(newResults);
            if (newResults.length > 0) {
              const metrics = ['score', 'annualReturn', 'maxDrawdown', 'sharpeRatio', 'winRate', 'totalReturn', 'totalTrades', 'profitFactor', 'calmarRatio', 'volatility', 'objective', 'taskId', 'annualizedReturn', 'maxDrawdownPct'];
              const names = Object.keys(newResults[0]).filter(k => !metrics.includes(k));
              setParamNames(names);
            }
          }
          // 任务完成时通知父组件刷新列表
          if (status === 'COMPLETED' && !wasCompleted.current) {
            wasCompleted.current = true;
            clearInterval(poll);
            if (onComplete) onComplete();
          }
        })
        .catch(() => {});
    }, 3000);

    return () => clearInterval(poll);
  }, [initialJob.jobId, initialJob.status]);

  const bestResult = detail?.bestResult || (results.length > 0 ? results[0] : null);
  const objective = currentJob?.objective || 'score';

  const objectiveLabel = {
    sharpeRatio: '夏普比率',
    calarRatio: '卡尔马比率',
    annualReturn: '年化收益',
    profitFactor: '盈利因子',
  }[objective] || objective;

  const bestParams = bestResult ? Object.entries(bestResult)
    .filter(([k]) => !['score', 'annualReturn', 'maxDrawdown', 'sharpeRatio', 'winRate', 'totalReturn', 'totalTrades', 'profitFactor', 'calmarRatio', 'volatility', 'taskId', 'objective'].includes(k))
    .reduce((acc, [k, v]) => ({ ...acc, [k]: v }), {}) : {};




  return (
    <Card size="small" style={{ background: '#fafafa', margin: '8px 0' }} styles={{ body: { padding: 0 } }}>
      <div style={{ padding: '12px 16px', borderBottom: '1px solid #f0f0f0', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Space>
          <Text strong style={{ color: '#666' }}>JobId: {currentJob?.jobId}</Text>
          <Button size="small" icon={<ReloadOutlined />} onClick={() => {
            backtestApi.getParamOptimizeResult(currentJob.jobId)
              .then(data => {
                if (data) {
                  setDetail(data);
                  setResults(data.results || []);
                  if (data.status === 'COMPLETED' && !wasCompleted.current) {
                    wasCompleted.current = true;
                    if (onComplete) onComplete();
                  }
                }
              });
          }} style={{ marginLeft: 4 }} />
          {currentJob?.status === 'RUNNING' && <Tag color="processing">执行中 {currentJob?.done || 0}/{currentJob?.total || detail?.total}</Tag>}
          {currentJob?.status === 'COMPLETED' && <Tag color="success">已完成</Tag>}
          {currentJob?.status === 'FAILED' && <Tag color="error">失败</Tag>}
          {currentJob?.status === 'PENDING' && <Tag color="default">等待中</Tag>}
        </Space>
        <Button size="small" onClick={onToggle}>收起</Button>
      </div>

      {/* 参数网格和区间信息 */}
      <div style={{ padding: '8px 24px', background: '#f0f0f0' }}>
        <Space size="large" wrap>
          {detail?.paramGrid && (
            <Text><Text strong>参数网格：</Text>
              <Tooltip title={parseParamGrid(detail.paramGrid)}>
                <span>{parseParamGrid(detail.paramGrid)}</span>
              </Tooltip>
            </Text>
          )}
          {detail?.startDate && detail?.endDate && (
            <Text><Text strong>回测区间：</Text>{detail.startDate} ~ {detail.endDate}</Text>
          )}
        </Space>
      </div>

      <div style={{ padding: '12px 0' }}>
        <>
          {/* 运行中状态 */}
          {(currentJob?.status === 'RUNNING' || currentJob?.status === 'PENDING') && (
            <Alert
              type="info"
              message={
                <span>
                  优化任务执行中...
                  <Text strong style={{ marginLeft: 8 }}>
                    已完成 {currentJob?.done || 0} / {currentJob?.total || detail?.total} 组
                  </Text>
                  {detail?.elapsedMs && (
                    <Text type="secondary" style={{ marginLeft: 8 }}>
                      耗时 {(detail.elapsedMs / 1000).toFixed(1)}s
                    </Text>
                  )}
                </span>
              }
              showIcon
              style={{ margin: '0 16px 12px 16px' }}
            />
          )}

          {/* 失败状态 */}
          {currentJob?.status === 'FAILED' && (
            <Alert
              type="error"
              message={<><Text strong>优化失败</Text><br/>{detail?.errorMessage || job?.errorMessage}</>}
              showIcon
              style={{ marginBottom: 12, padding: '0 16px' }}
            />
          )}

          {/* 最优结果卡片 */}
          {bestResult && (currentJob?.status === 'COMPLETED' || currentJob?.status === 'RUNNING') && (
            <Card
              title={<><TrophyOutlined style={{ color: '#faad14', marginRight: 6 }} />最优结果</>}
              size="small"
              style={{ margin: '12px 16px', border: '2px solid #ffd591', background: '#fffbe6' }}
            >
              <Row gutter={16}>
                {Object.entries(bestParams).map(([k, v]) => (
                  <Col span={6} key={k}>
                    <Statistic
                      title={<MetricLabel metric={k}>{paramNameMap[k] || k}</MetricLabel>}
                      value={v != null ? v : '-'}
                      valueStyle={{ fontSize: 16 }}
                    />
                  </Col>
                ))}
              </Row>
              <Divider style={{ margin: '12px 0' }} />
              <Row gutter={16}>
                <Col span={6}>
                  <Statistic
                    title={<MetricLabel metric="score">{objectiveLabel}</MetricLabel>}
                    value={fmt(bestResult.score)}
                    valueStyle={{ color: '#cf1322', fontSize: 18, fontWeight: 'bold' }}
                  />
                </Col>
                <Col span={6}>
                  <Statistic
                    title={<MetricLabel metric="annualReturn">年化收益率</MetricLabel>}
                    value={fmtPct(bestResult.annualReturn)}
                    valueStyle={{ color: bestResult.annualReturn > 0 ? '#cf1322' : '#3f8600', fontSize: 18 }}
                  />
                </Col>
                <Col span={6}>
                  <Statistic
                    title={<MetricLabel metric="maxDrawdown">最大回撤</MetricLabel>}
                    value={fmtPct(bestResult.maxDrawdown)}
                    valueStyle={{ color: '#3f8600', fontSize: 18 }}
                  />
                </Col>
                <Col span={6}>
                  <Statistic
                    title={<MetricLabel metric="sharpeRatio">夏普比率</MetricLabel>}
                    value={fmt(bestResult.sharpeRatio)}
                    valueStyle={{ color: bestResult.sharpeRatio >= 1 ? '#52c41a' : '#999', fontSize: 18 }}
                  />
                </Col>
              </Row>
              <Divider style={{ margin: '12px 0' }} />
              <Row gutter={16}>
                <Col span={6}>
                  <Statistic
                    title={<MetricLabel metric="winRate">胜率</MetricLabel>}
                    value={fmtPct(bestResult.winRate)}
                    valueStyle={{ fontSize: 16 }}
                  />
                </Col>
                <Col span={6}>
                  <Statistic
                    title={<MetricLabel metric="totalTrades">交易次数</MetricLabel>}
                    value={bestResult.totalTrades || 0}
                    valueStyle={{ fontSize: 16 }}
                  />
                </Col>
                <Col span={6}>
                  <Statistic
                    title={<MetricLabel metric="profitFactor">盈利因子</MetricLabel>}
                    value={fmt(bestResult.profitFactor)}
                    valueStyle={{ color: (bestResult.profitFactor || 0) >= 1 ? '#52c41a' : '#cf1322', fontSize: 16 }}
                  />
                </Col>
                <Col span={6}>
                  <Statistic
                    title={<MetricLabel metric="volatility">波动率</MetricLabel>}
                    value={fmt(bestResult.volatility)}
                    valueStyle={{ color: '#999', fontSize: 16 }}
                  />
                </Col>
                <Col span={6}>
                  <Statistic
                    title={<MetricLabel metric="calmarRatio">卡尔马比率</MetricLabel>}
                    value={fmt(bestResult.calmarRatio)}
                    valueStyle={{ color: bestResult.calmarRatio >= 1 ? '#52c41a' : '#999', fontSize: 16 }}
                  />
                </Col>
              </Row>
              {/* 应用最优参数按钮 */}
              <Divider style={{ margin: '12px 0' }} />
              <div style={{ textAlign: 'right' }}>
                <Button
                  type="primary"
                  icon={<RocketOutlined />}
                  onClick={() => setApplyModalVisible(true)}
                  disabled={currentJob?.status !== 'COMPLETED'}
                >
                  用此参数创建回测
                </Button>
              </div>
            </Card>
          )}

          {/* 热力图 */}
          {results.length >= 4 && paramNames.length >= 2 && (
            <div style={{ margin: '12px 16px' }}>
              <Text strong style={{ display: 'block', marginBottom: 8 }}>📊 参数敏感度热力图</Text>
              <Alert type="info" message="颜色越深（红/绿）表示该参数组合效果越好" style={{ marginBottom: 8 }} />
              <HeatmapChart results={results} paramNames={paramNames} objective={objective} />
            </div>
          )}

          {/* 结果表格 */}
          {results.length > 0 && (
            <Card title={<><BarChartOutlined style={{ marginRight: 6 }} />全部参数组合（{results.length} 组）</>} size="small" style={{ margin: '12px 16px' }} styles={{ body: { padding: 0 } }}>
              <div style={{ padding: '12px 16px' }}>
                <Table
                  dataSource={results}
                  rowKey={(r, i) => i}
                  size="small"
                  pagination={{ pageSize: tablePageSize, pageSizeOptions: [10, 20, 50, 100], showTotal: t => `共 ${t} 组`, showSizeChanger: true }}
                  onChange={(pagination) => { setTablePage(pagination.current); setTablePageSize(pagination.pageSize); }}
                  columns={[
                    ...paramNames.map(p => ({
                      title: <Tag>{paramNameMap[p] || p}</Tag>, dataIndex: p, key: p, width: 90,
                      render: v => v != null ? <Tag color="blue">{v}</Tag> : '-',
                    })),
                    { title: <MetricLabel metric="winRate">胜率</MetricLabel>, dataIndex: 'winRate', key: 'winRate', width: 70,
                      render: v => v != null ? fmtPct(v) : '-' },
                    { title: <MetricLabel metric="sharpeRatio">夏普</MetricLabel>, dataIndex: 'sharpeRatio', key: 'sr', width: 70,
                      render: v => v != null ? fmt(v) : '-' },
                    { title: <MetricLabel metric="annualReturn">年化</MetricLabel>, dataIndex: 'annualReturn', key: 'ar', width: 85,
                      render: v => v != null ? <span style={{ color: +v > 0 ? '#cf1322' : '#3f8600' }}>{fmtPct(v)}</span> : '-' },
                    { title: <MetricLabel metric="maxDrawdown">最大回撤</MetricLabel>, dataIndex: 'maxDrawdown', key: 'dd', width: 85,
                      render: v => v != null ? <span style={{ color: '#3f8600' }}>{fmtPct(v)}</span> : '-' },
                    { title: <MetricLabel metric="profitFactor">盈利因子</MetricLabel>, dataIndex: 'profitFactor', key: 'pf', width: 85,
                      render: v => v != null ? fmt(v) : '-' },
                    { title: <Tag color="red">{objectiveLabel}</Tag>, dataIndex: 'score', key: 'score', width: 90,
                      render: v => v != null ? <Text strong style={{ color: '#cf1322' }}>{fmt(v)}</Text> : '-',
                      sorter: (a, b) => (b.score || 0) - (a.score || 0),
                      defaultSortOrder: 'ascend' },
                  ]}
                />
              </div>
            </Card>
          )}

          {!bestResult && currentJob?.status === 'FAILED' && (
            <Alert type="error" message="任务执行失败，请检查后端日志" showIcon />
          )}
        </>
      </div>

      {/* 应用最优参数确认弹窗 */}
      {bestResult && (
        <Modal
          title={<><RocketOutlined style={{ color: '#1677ff', marginRight: 8 }} />用最优参数创建回测</>}
          open={applyModalVisible}
          onCancel={() => setApplyModalVisible(false)}
          footer={[
            <Button key="cancel" onClick={() => setApplyModalVisible(false)}>取消</Button>,
            <Button
              key="go"
              type="primary"
              icon={<RocketOutlined />}
              onClick={() => {
                setApplyModalVisible(false);
                // 构建查询参数，带入最优参数
                const params = new URLSearchParams({
                  strategyId: currentJob?.strategyId || '',
                  startDate: detail?.startDate || currentJob?.startDate || '',
                  endDate: detail?.endDate || currentJob?.endDate || '',
                });
                // 把最优参数全部带过去
                Object.entries(bestParams).forEach(([k, v]) => {
                  if (v != null) params.set(k, v);
                });
                navigate(`/backtests/new?${params.toString()}`);
              }}
            >
              前往创建回测
            </Button>,
          ]}
          width={520}
        >
          <div style={{ marginBottom: 16 }}>
            <Text type="secondary">以下为本次优化得到的最优参数，将自动填入回测配置：</Text>
          </div>
          <Row gutter={16} style={{ marginBottom: 16 }}>
            {Object.entries(bestParams).map(([k, v]) => (
              <Col span={12} key={k} style={{ marginBottom: 12 }}>
                <div style={{ padding: '8px 12px', background: '#f6ffed', border: '1px solid #b7eb8f', borderRadius: 6 }}>
                  <Text type="secondary" style={{ fontSize: 12 }}>{paramNameMap[k] || k}</Text>
                  <div>
                    <Text strong style={{ fontSize: 16, color: '#389e0d' }}>{v}</Text>
                  </div>
                </div>
              </Col>
            ))}
          </Row>
          <Row gutter={16}>
            <Col span={8}>
              <Statistic title="年化收益" value={fmtPct(bestResult.annualReturn)}
                valueStyle={{ color: bestResult.annualReturn > 0 ? '#cf1322' : '#3f8600', fontSize: 16 }} />
            </Col>
            <Col span={8}>
              <Statistic title="最大回撤" value={fmtPct(bestResult.maxDrawdown)}
                valueStyle={{ color: '#3f8600', fontSize: 16 }} />
            </Col>
            <Col span={8}>
              <Statistic title={objectiveLabel} value={fmt(bestResult.score)}
                valueStyle={{ color: '#cf1322', fontSize: 16 }} />
            </Col>
          </Row>
          <Alert
            type="info"
            message="回测时间区间将与本次优化保持一致，其他参数（手续费、基准等）可在创建页自行调整。"
            style={{ marginTop: 16 }}
            showIcon
          />
        </Modal>
      )}
    </Card>
  );
}

// ─── 主页面 ────────────────────────────────────────────────────────────────────
export default function ParamOptimize() {
  const navigate = useNavigate();
  const [strategies, setStrategies] = useState([]);
  const [form, setForm] = useState({
    strategyId: null,
    startDate: '2025-01-01',
    endDate: '2025-12-31',
    initialCapital: 1000000,
    benchmarkCode: '000300.SH',
    objective: 'sharpeRatio',
    maxConcurrent: 2,
  });
  const [paramRows, setParamRows] = useState([
    { name: 'maxPositionCount', type: 'range', min: 10, max: 30, step: 5 },
    { name: 'stopLossPct', type: 'range', min: 0.05, max: 0.15, step: 0.05 },
    { name: 'stopProfitPct', type: 'range', min: 0.10, max: 0.30, step: 0.10 },
  ]);
  const [showGuide, setShowGuide] = useState(false);
  const [taskList, setTaskList] = useState([]);
  const [taskLoading, setTaskLoading] = useState(false);
  const [expandedRowKey, setExpandedRowKey] = useState(null);
  const [taskPage, setTaskPage] = useState(1);
  const [taskPageSize, setTaskPageSize] = useState(10);
  const [leftCollapsed, setLeftCollapsed] = useState(false);
  const pollRef = useRef(null);
  const taskListRef = useRef(taskList);

  // taskList 变化时同步到 ref（供 interval 闭包读取最新值）
  useEffect(() => {
    taskListRef.current = taskList;
  }, [taskList]);

  // 加载任务列表（全量）
  const loadTaskList = () => {
    setTaskLoading(true);
    backtestApi.listParamOptimize()
      .then(res => {
        const list = Array.isArray(res) ? res : (res?.data || []);
        setTaskList(list);
      })
      .catch(() => {})
      .finally(() => setTaskLoading(false));
  };

  useEffect(() => {
    strategyApi.list({ page: 0, size: 100, status: 'ACTIVE' })
      .then(res => setStrategies(res.records || []));

    // 初始加载任务列表
    loadTaskList();
  }, []);

  // 统一轮询：每次 interval 都从 taskList 获取最新的运行中任务（解决闭包陷阱）
  useEffect(() => {
    // 找到所有真正的运行中任务（排除PENDING占位符）
    const runningJobs = taskList.filter(t => t.status === 'RUNNING' && t.jobId && !t.jobId.startsWith('PENDING_'));

    // 有运行中的任务 → 启动/重建轮询
    if (runningJobs.length > 0) {
      // 先清除旧的 interval（每次 effect 触发时都重建，确保闭包拿到最新 taskList）
      if (pollRef.current) {
        clearInterval(pollRef.current);
        pollRef.current = null;
      }
      pollRef.current = setInterval(() => {
        // 从 ref 读取最新 taskList（而非闭包捕获的旧值）
        const currentRunning = taskListRef.current
          .filter(t => t.status === 'RUNNING' && t.jobId && !t.jobId.startsWith('PENDING_'));
        if (currentRunning.length === 0) {
          clearInterval(pollRef.current);
          pollRef.current = null;
          return;
        }
        currentRunning.forEach(job => {
          backtestApi.getParamOptimizeResult(job.jobId)
            .then(res => {
              if (res?.jobId) {
                setTaskList(prev => prev.map(t => t.jobId === job.jobId ? { ...t, ...res } : t));
              }
            })
            .catch(() => {});
        });
      }, 3000);
    } else {
      // 没有运行中的任务，停止轮询
      if (pollRef.current) {
        clearInterval(pollRef.current);
        pollRef.current = null;
      }
    }

    // cleanup：effect 退出时清除 interval（组件卸载或依赖变化时）
    return () => {
      if (pollRef.current) {
        clearInterval(pollRef.current);
        pollRef.current = null;
      }
    };
  }, [taskList]);


  // 计算总组合数
  const totalCombinations = paramRows.reduce((acc, row) => {
    const values = row.type === 'list' ? (row.values || []) :
      (() => {
        const vals = [];
        if (row.min != null && row.max != null && row.step)
          for (let v = +row.min; v <= +row.max; v += +row.step) vals.push(v);
        return vals;
      })();
    return acc * Math.max(values.length, 1);
  }, 1);

  const handleSubmit = () => {
    console.log('[ParamOptimize] handleSubmit called');
    if (!form.strategyId) { message.warning('请选择策略'); return; }
    if (paramRows.length === 0) { message.warning('请至少添加一个参数'); return; }
    
    // 检查回测区间是否足够（至少60个交易日，约3个月）
    if (form.startDate && form.endDate) {
      const start = dayjs(form.startDate);
      const end = dayjs(form.endDate);
      const tradingDays = end.diff(start, 'day') * 0.7; // 粗略估算交易日（扣除周末）
      if (tradingDays < 60) {
        Modal.confirm({
          title: '回测区间较短',
          content: `当前回测区间约 ${Math.round(tradingDays)} 个交易日，建议至少 3 个月（60 个交易日）以获得更稳定的优化结果。是否继续提交？`,
          okText: '继续提交',
          cancelText: '取消',
          onOk: () => doSubmit(),
        });
        return;
      }
    }
    
    doSubmit();
  };

  // 实际提交优化任务的逻辑
  const doSubmit = () => {
    // 检查哪些参数被过滤（min/max/step 任一为空导致无有效值）
    const emptyParams = paramRows.filter(row => {
      if (row.type === 'list') return !row.values || row.values.length === 0;
      return row.min == null || row.max == null || row.step == null;
    });
    if (emptyParams.length > 0) {
      const names = emptyParams.map(r => paramNameMap[r.name] || r.name).join('、');
      message.error({
        content: `以下参数未填写完整，已被忽略：${names}。请补充完整后再提交！`,
        key: 'optimize',
        duration: 5,
      });
      return;
    }

    const paramGrid = paramRows.map(row => {
      const values = row.type === 'list' ? (row.values || []) :
        (() => {
          const vals = [];
          if (row.min != null && row.max != null && row.step)
            for (let v = +row.min; v <= +row.max; v += +row.step) vals.push(Math.round(v * 1000) / 1000);
          return vals;
        })();
      return { name: row.name, values };
    }).filter(p => p.values.length > 0);

    const req = { ...form, paramGrid };
    const strategy = strategies.find(s => s.id === form.strategyId);
    const tempId = 'PENDING_' + Date.now();

    setLeftCollapsed(true);
    message.loading({ content: '提交优化任务...', key: 'optimize' });

    // 异步提交到后端
    backtestApi.submitParamOptimize(req)
      .then(res => {
        console.log('[ParamOptimize] submit result:', res);
        console.log('[ParamOptimize] res keys:', Object.keys(res));
        
        const jobId = res?.jobId;
        
        if (!jobId) {
          console.error('[ParamOptimize] jobId 获取失败, res:', JSON.stringify(res));
          message.error({ content: '提交失败：未获取到任务ID', key: 'optimize' });
          return;
        }
        
        message.success({ content: '优化任务已启动，正在计算...', key: 'optimize', duration: 2 });
        
        // 本地立即插入任务（jobId 已知，不依赖后端刷新）
        const newItem = {
          jobId,
          strategyId: form.strategyId,
          strategyCode: strategy?.strategyCode || '',
          taskName: strategy?.strategyName || '',
          startDate: form.startDate,
          endDate: form.endDate,
          objective: form.objective,
          status: 'RUNNING',
          total: totalCombinations,
          done: 0,
          progress: 0,
          bestScore: null,
          bestAnnualReturn: null,
          bestMaxDrawdown: null,
          createdAt: new Date().toISOString(),
        };
        console.log('[ParamOptimize] 即将 setTaskList');
        setTaskList(prev => [newItem, ...prev]);
        console.log('[ParamOptimize] setTaskList 完成');
        setExpandedRowKey(jobId);
        
        // 注意：轮询已移到 useEffect 中统一管理，不需要这里再单独轮询
      })
      .catch(e => {
        console.error('[ParamOptimize] submit error:', e);
        message.error({ content: '提交失败: ' + (e.message || '未知错误'), key: 'optimize' });
      });
  };

  // 删除优化任务
  const handleDelete = (jobId) => {
    backtestApi.deleteParamOptimize(jobId)
      .then(() => {
        setTaskList(prev => prev.filter(t => t.jobId !== jobId));
        if (expandedRowKey === jobId) setExpandedRowKey(null);
        message.success('已删除');
      })
      .catch(() => message.error('删除失败'));
  };

  const statusMap = { PENDING: '待执行', RUNNING: '运行中', COMPLETED: '已完成', FAILED: '失败' };
  const statusColor = { PENDING: 'default', RUNNING: 'processing', COMPLETED: 'success', FAILED: 'error' };

  const taskColumns = [
    { title: '策略', dataIndex: 'strategyCode', key: 'code', width: 100, render: v => <Tag color="blue">{v || '-'}</Tag> },
    { title: '目标', dataIndex: 'objective', key: 'obj', width: 90, render: v => v === 'sharpeRatio' ? 'Sharpe' : v === 'annualReturn' ? '年化收益' : 'Calmar' },
    { title: '状态', dataIndex: 'status', key: 'status', width: 80, render: (v) => <Badge status={statusColor[v]} text={statusMap[v]} /> },
    { title: '进度', key: 'progress', width: 80, render: (_, r) => r.status === 'COMPLETED' || r.status === 'FAILED' ? `${r.done || 0}/${r.total || 0}` : `${r.progress || 0}%` },
    { title: '最优得分', dataIndex: 'bestScore', key: 'score', width: 80, render: v => v != null ? fmt(v) : '-' },
    { title: '创建时间', dataIndex: 'createdAt', key: 'time', width: 130, render: v => v ? v.substring(0, 19).replace('T', ' ') : '-' },
    {
      title: '',
      key: 'action',
      width: 50,
      render: (_, r) => (
        <Popconfirm title="确定删除？" onConfirm={() => handleDelete(r.jobId)} okText="确定" cancelText="取消">
          <Button type="text" danger size="small" icon={<DeleteOutlined />} />
        </Popconfirm>
      ),
    },
  ];

  return (
    <div>
      <div className="page-header">
        <Title level={4} style={{ margin: 0 }}>
          <ExperimentOutlined style={{ marginRight: 8 }} />
          参数优化（网格搜索）
        </Title>
        <Space>
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/backtests')}>返回</Button>
          <Button onClick={() => setLeftCollapsed(!leftCollapsed)}>
            {leftCollapsed ? '显示配置' : '收起配置'}
          </Button>
        </Space>
      </div>

      <Row gutter={16}>
        {/* 左侧：配置区（可折叠） */}
        {!leftCollapsed && (
          <Col span={10}>
            <Card title={<><SettingOutlined style={{ marginRight: 6 }} />新建优化配置</>} style={{ marginBottom: 16 }}>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
              <div>
                <Text strong>策略</Text>
                <Select
                  value={form.strategyId}
                  onChange={v => setForm(f => ({ ...f, strategyId: v }))}
                  style={{ width: '100%', marginTop: 4 }}
                  placeholder="选择要优化的策略"
                  showSearch optionFilterProp="label"
                  options={strategies.map(s => ({ value: s.id, label: `${s.strategyName} (${s.strategyCode})` }))}
                />
              </div>

              <div>
                <Text strong>回测区间</Text>
                <RangePicker
                  value={[
                    form.startDate ? dayjs(form.startDate) : null,
                    form.endDate ? dayjs(form.endDate) : null,
                  ]}
                  onChange={(dates, dateStrings) => setForm(f => ({
                    ...f,
                    startDate: dateStrings[0] || '',
                    endDate: dateStrings[1] || '',
                  }))}
                  style={{ marginTop: 4, width: '100%' }}
                  format="YYYY-MM-DD"
                  placeholder={['开始日期', '结束日期']}
                />
              </div>

              <div>
                <Text strong>目标函数</Text>
                <Select value={form.objective} onChange={v => setForm(f => ({ ...f, objective: v }))} style={{ width: '100%', marginTop: 4 }}>
                  <Option value="sharpeRatio">最大化 Sharpe 比率</Option>
                  <Option value="annualReturn">最大化年化收益率</Option>
                  <Option value="calmarRatio">最大化 Calmar 比率（收益/回撤）</Option>
                </Select>
              </div>

              <div>
                <Text strong>并行度</Text>
                <Slider value={form.maxConcurrent} onChange={v => setForm(f => ({ ...f, maxConcurrent: v }))} min={1} max={5} marks={{ 1: '1', 2: '2', 3: '3', 5: '5' }} style={{ marginTop: 4 }} />
              </div>
            </div>

            <Divider>参数网格</Divider>

            {paramRows.map((row, i) => (
              <ParamRow
                key={i}
                index={i}
                value={row}
                onChange={v => setParamRows(rows => rows.map((r, j) => j === i ? v : r))}
                onRemove={() => setParamRows(rows => rows.filter((_, j) => j !== i))}
              />
            ))}

            <Button
              type="dashed"
              style={{ width: '100%', marginBottom: 8 }}
              onClick={() => setParamRows(rows => [...rows, { name: '', type: 'range', min: '', max: '', step: '' }])}
            >
              + 添加参数
            </Button>

            <div style={{ marginBottom: 12, padding: 8, background: '#f6ffed', border: '1px solid #b7eb8f', borderRadius: 6 }}>
              <Text>预计运行 <Text strong style={{ color: '#cf1322' }}>{totalCombinations}</Text> 次回测
                {totalCombinations > 50 && <Text type="warning">（组合较多，耗时较长）</Text>}
              </Text>
            </div>

            <Button
              type="primary"
              block
              icon={<PlayCircleOutlined />}
              onClick={handleSubmit}
              size="large"
            >
              开始优化
            </Button>
          </Card>
        </Col>
        )}

        {/* 右侧：历史优化任务列表 */}
        <Col span={leftCollapsed ? 24 : 14}>
          <Card
            title={<><BarChartOutlined style={{ marginRight: 6 }} />优化历史（{taskList.length} 条）</>}
            extra={
              <Space>
                <Button icon={<InfoCircleOutlined />} size="small" onClick={() => setShowGuide(true)}>说明</Button>
                <Button icon={<ReloadOutlined />} size="small" onClick={() => loadTaskList()}>刷新</Button>
              </Space>
            }
            style={{ marginBottom: 16 }}
          >
            <Table
              dataSource={taskList}
              rowKey="jobId"
              size="small"
              loading={taskLoading}
              expandable={{
                expandedRowRender: (record) => <TaskDetail job={record} onToggle={() => setExpandedRowKey(prev => prev === record.jobId ? null : record.jobId)} onComplete={loadTaskList} navigate={navigate} strategies={strategies} />,
                expandedRowKeys: expandedRowKey ? [expandedRowKey] : [],
                onExpand: (expanded, record) => setExpandedRowKey(expanded ? record.jobId : null),
              }}
              pagination={{ pageSize: taskPageSize, pageSizeOptions: [10, 20, 50, 100], showSizeChanger: true, showTotal: t => `共 ${t} 条` }}
              onChange={(pagination) => { setTaskPage(pagination.current); setTaskPageSize(pagination.pageSize); }}
              columns={taskColumns}
              scroll={{ x: 800 }}
            />
          </Card>
        </Col>
      </Row>

      {/* 优化结果说明弹窗 */}
      <Modal
        title={<><InfoCircleOutlined style={{ marginRight: 8 }} />优化结果说明</>}
        open={showGuide}
        onCancel={() => setShowGuide(false)}
        footer={null}
        width={600}
      >
        <Row gutter={24}>
          <Col span={12}>
            <Text strong style={{ display: 'block', marginBottom: 8 }}>指标说明</Text>
            <ul style={{ margin: 0, paddingLeft: 16, fontSize: 13, color: '#666' }}>
              <li style={{ marginBottom: 6 }}><Text style={{ color: '#1677ff' }}>Score / 目标函数值</Text>：本次优化目标（Sharpe/Calar/年化收益）综合得分，越高越好</li>
              <li style={{ marginBottom: 6 }}><Text style={{ color: '#cf1322' }}>年化收益</Text>：策略年均收益率（%），越高越好</li>
              <li style={{ marginBottom: 6 }}><Text style={{ color: '#3f8600' }}>最大回撤</Text>：历史最大亏损幅度（%），越小越安全</li>
              <li style={{ marginBottom: 6 }}><Text style={{ color: '#9254de' }}>Sharpe（夏普比率）</Text>：收益/波动，衡量风险调整后收益，通常 &gt; 1 为优秀</li>
              <li style={{ marginBottom: 6 }}><Text style={{ color: '#fa8c16' }}>胜率</Text>：盈利交易次数占比（%），越高越好</li>
            </ul>
          </Col>
          <Col span={12}>
            <Text strong style={{ display: 'block', marginBottom: 8 }}>如何使用结果</Text>
            <ol style={{ margin: 0, paddingLeft: 16, fontSize: 13, color: '#666' }}>
              <li style={{ marginBottom: 6 }}>点击任务列表某行展开查看详情</li>
              <li style={{ marginBottom: 6 }}>查看「最优参数组合」卡片获取最佳参数</li>
              <li style={{ marginBottom: 6 }}>参考热力图观察参数敏感度（颜色越深效果越好）</li>
              <li style={{ marginBottom: 6 }}>点击「全部结果」查看其他组合表现</li>
              <li style={{ marginBottom: 6 }}>确认后可在「回测管理」查看详细回测报告</li>
            </ol>
          </Col>
        </Row>
      </Modal>
    </div>
  );
}
