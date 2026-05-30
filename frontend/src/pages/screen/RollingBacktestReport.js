import React, { useEffect, useState, useCallback } from 'react';
import {
  Card, Spin, Typography, Button, Space, Alert, Descriptions, Tag, Divider,
  Table, Row, Col, Statistic, Popconfirm, message, Progress, Empty, Tooltip, Select,
} from 'antd';
import {
  ArrowLeftOutlined, ReloadOutlined, DeleteOutlined,
  StopOutlined, QuestionCircleOutlined, DownOutlined, RightOutlined, RedoOutlined
} from '@ant-design/icons';
import { useParams, useNavigate, Link } from 'react-router-dom';
import ReactECharts from 'echarts-for-react';
import { rollingScreenApi } from '../../api';

const { Title, Text } = Typography;

const STATUS_COLOR = {
  COMPLETED: 'green',
  RUNNING: 'blue',
  FAILED: 'red',
  CANCELLED: 'default',
  PENDING: 'orange',
};

// 调仓频率中文映射
const FREQ_LABEL = {
  WEEKLY: '每周',
  BIWEEKLY: '每两周',
  MONTHLY: '每月',
};

// 成交价模式中文映射
const ORDER_TYPE_LABEL = {
  CLOSE: '收盘价',
  NEXT_OPEN: '次日开盘价',
  VWAP: '成交量加权均价',
};

const PCT = (v) => v != null ? `${(v * 100).toFixed(2)}%` : '-';
const NUM = (v, d = 4) => v != null ? Number(v).toFixed(d) : '-';

/**
 * 滚动选股回测报告页
 * 路由: /screen/backtest/:id?
 *   - 有 id: 显示指定任务的完整回测报告
 *   - 无 id: 显示回测任务列表
 */
export default function RollingBacktestReport() {
  const { id } = useParams();
  const navigate = useNavigate();

  if (!id) {
    return <RollingBacktestList onSelect={(taskId) => navigate(`/screen/backtest/${taskId}`)} />;
  }

  return <ReportDetail taskId={id} onBack={() => navigate('/screen/backtest')} />;
}

// ─────────────────────────────────────────────────────────
//  选股策略配置展示 Card（可折叠，默认收起）
// ─────────────────────────────────────────────────────────
function ScreenConfigCard({ screenConfigJson }) {
  let config = null;
  try { config = JSON.parse(screenConfigJson); } catch { return null; }
  if (!config) return null;

  const [expanded, setExpanded] = useState(false);
  const navigate = useNavigate();
  const factors = Array.isArray(config.factors) ? config.factors : [];
  const maFilter = config.maPositionFilter || null;
  const isMultiDay = !!(config.screenStartDate && config.screenEndDate);

  const DIR_TAG = { 'LONG': { color: 'red', text: '做多' }, 'SHORT': { color: 'green', text: '做空' } };
  const dirInfo = DIR_TAG[config.direction] || { color: 'default', text: config.direction || '-' };

  // 组合策略名称：因子代码用 + 连接
  const strategyName = factors.length > 0
    ? factors.map(f => f.factorCode).join('+')
    : '无因子';

  // 方向中文映射（因子级别 direction 是 Integer 1/-1 或 String）
  const factorDirLabel = (d) => {
    if (d === 1 || d === '1' || String(d).toUpperCase() === 'LONG') return '正向';
    if (d === -1 || d === '-1' || String(d).toUpperCase() === 'SHORT') return '反向';
    return String(d ?? '-');
  };

  // 跳转到选股页面并回填参数
  const handleGotoScreen = (e) => {
    e?.stopPropagation();
    // 将完整配置编码到 URL searchParams 中
    try {
      const encoded = btoa(encodeURIComponent(screenConfigJson));
      navigate(`/screen?__restore=${encoded}`);
    } catch {
      message.error('配置参数过长，无法跳转');
    }
  };

  return (
    <Card
      title={
        <Space>
          {expanded ? <DownOutlined onClick={() => setExpanded(false)} style={{ cursor: 'pointer', fontSize: 12 }} /> : <RightOutlined onClick={() => setExpanded(true)} style={{ cursor: 'pointer', fontSize: 12 }} />}
          <span
            onClick={handleGotoScreen}
            style={{
              cursor: 'pointer',
              fontWeight: 600,
            }}
            title="点击跳转到因子选股页面并自动回填此策略配置"
          >
            选股策略配置
          </span>
          <Tag color={dirInfo.color}>{dirInfo.text}</Tag>
        </Space>
      }
      style={{ marginBottom: 16 }}
      size="small"
    >
      {expanded && (
        <Row gutter={[16, 12]}>
          {/* ── 基本参数 ── */}
          <Col span={24}>
            <Descriptions size="small" column={{ xxl: 5, xl: 4, lg: 3, md: 2, sm: 1 }} bordered>
              <Descriptions.Item label="选股日期">
                {isMultiDay
                  ? `${config.screenStartDate} ~ ${config.screenEndDate}（多日模式）`
                  : (config.screenDate || '-')}
              </Descriptions.Item>
              <Descriptions.Item label="选股数量(TopN)">
                <Tag color="blue">{config.topN ?? '-'}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="剔除ST">
                {config.excludeSt !== false ? (
                  <Tag color="green">剔除</Tag>
                ) : (
                  <Tag color="orange">不剔除</Tag>
                )}
              </Descriptions.Item>
              <Descriptions.Item label="异常值处理">
                {config.globalOutlierMethod || '-'}
              </Descriptions.Item>
              <Descriptions.Item label="归一化方式">
                {config.globalNormalizeMethod || '-'}
              </Descriptions.Item>
              {config.orthogonalizationMethod && (
                <Descriptions.Item label="正交化">
                  {config.orthogonalizationMethod}
                </Descriptions.Item>
              )}
              {config.valuationWeight != null && (
                <Descriptions.Item label="估值权重">
                  {(config.valuationWeight * 100).toFixed(0)}%
                </Descriptions.Item>
              )}
              {config.customSqlWhere && (
                <Descriptions.Item label="自定义过滤" span={2}>
                  <Text code style={{ fontSize: 11 }}>{config.customSqlWhere}</Text>
                </Descriptions.Item>
              )}
            </Descriptions>
          </Col>

          {/* ── MA位置过滤 ── */}
          {maFilter && (maFilter.aboveMA30 || maFilter.aboveMA60 || maFilter.aboveMA100) && (
            <Col span={24}>
              <Divider plain orientation="left" style={{ margin: '4px 0 8px', fontSize: 12 }}>
                <Text type="secondary">均线位置过滤</Text>
              </Divider>
              <Space size={[8, 4]} wrap>
                {maFilter.aboveMA30 && (
                  <Tag color="blue" style={{ fontSize: 12 }}>价格 &gt; MA30</Tag>
                )}
                {maFilter.aboveMA60 && (
                  <Tag color="blue" style={{ fontSize: 12 }}>价格 &gt; MA60</Tag>
                )}
                {maFilter.aboveMA100 && (
                  <Tag color="blue" style={{ fontSize: 12 }}>价格 &gt; MA100</Tag>
                )}
              </Space>
            </Col>
          )}

          {/* ── 因子列表 ── */}
          <Col span={24}>
            <Divider plain orientation="left" style={{ margin: '8px 0 8px', fontSize: 12 }}>
              <Text type="secondary">
                {config.presetName || '筛选因子'}
                <Tag style={{ marginLeft: 6 }} color="processing">{factors.length} 个</Tag>
              </Text>
            </Divider>
            {factors.length > 0 ? (
              <Table
                dataSource={factors.map((f, i) => ({ ...f, _key: i }))}
                rowKey="_key"
                size="small"
                pagination={false}
                scroll={{ x: 700 }}
                columns={[
                  {
                    title: '#', width: 45, align: 'center',
                    render: (_, __, i) => i + 1,
                  },
                  {
                    title: '因子代码', dataIndex: 'factorCode', width: 150,
                    render: v => <Text strong>{v}</Text>,
                  },
                  {
                    title: '方向', width: 80, align: 'center',
                    render: (_, r) => {
                      const label = factorDirLabel(r.direction);
                      const isLong = label === '正向';
                      return <Tag color={isLong ? 'red' : 'green'}>{label}</Tag>;
                    },
                  },
                  {
                    title: '权重', width: 70, align: 'right',
                    render: (_, r) => r.weight != null ? Number(r.weight).toFixed(1) : '-',
                  },
                  {
                    title: '过滤条件', width: 160,
                    render: (_, r) => {
                      if (!r.filterOp || r.filterOp === 'NONE') return <Text type="secondary">-</Text>;
                      const OP_LABEL = { GT: '>', GTE: '≥', LT: '<', LTE: '≤', EQ: '=' };
                      return (
                        <Text code>
                          {OP_LABEL[r.filterOp] || r.filterOp} {r.filterValue != null ? r.filterValue : ''}
                        </Text>
                      );
                    },
                  },
                  {
                    title: '异常值处理', width: 100,
                    render: (_, r) => r.outlierMethod || <Text type="secondary">默认</Text>,
                  },
                  {
                    title: '归一化', width: 90,
                    render: (_, r) => r.normalizeMethod || <Text type="secondary">默认</Text>,
                  },
                ]}
              />
            ) : (
              <Empty description="无因子配置信息" image={Empty.PRESENTED_IMAGE_SIMPLE} style={{ margin: '8px 0' }} />
            )}
          </Col>
        </Row>
      )}
    </Card>
  );
}

// ─────────────────────────────────────────────────────────
//  报告详情组件
// ─────────────────────────────────────────────────────────
function ReportDetail({ taskId, onBack }) {
  const [task, setTask] = useState(null);
  const [records, setRecords] = useState([]);
  const [curveData, setCurveData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [polling, setPolling] = useState(false);

  const fetchAll = useCallback(async () => {
    try {
      const [t, r, c] = await Promise.all([
        rollingScreenApi.getTask(taskId),
        rollingScreenApi.getRecords(taskId).catch(() => ({ data: [] })),
        rollingScreenApi.getCurve(taskId).catch(() => ({ data: null })),
      ]);
      setTask(t?.data ?? t);
      setRecords(r?.data ?? r ?? []);
      setCurveData(c?.data ?? c);
    } finally {
      setLoading(false);
    }
  }, [taskId]);

  useEffect(() => {
    fetchAll();
  }, [fetchAll]);

  // 轮询：RUNNING 状态每 5 秒刷新
  useEffect(() => {
    if (!task || task.status !== 'RUNNING') return;
    setPolling(true);
    const timer = setInterval(() => {
      rollingScreenApi.getTask(taskId).then(res => {
        const t = res?.data ?? res;
        setTask(t);
        if (t?.status !== 'RUNNING') {
          clearInterval(timer);
          setPolling(false);
          fetchAll(); // 完成后拉完整数据
        }
      });
    }, 5000);
    return () => { clearInterval(timer); setPolling(false); };
  }, [task?.status, taskId, fetchAll]);

  const handleDelete = async () => {
    await rollingScreenApi.delete(taskId);
    message.success('任务已删除');
    onBack();
  };
  const handleCancel = async () => {
    await rollingScreenApi.cancel(taskId);
    message.success('任务已取消');
    fetchAll();
  };

  const handleRerun = async () => {
    try {
      await rollingScreenApi.rerun(taskId);
      message.success('已重新提交，回测正在执行...');
      fetchAll();
    } catch {
      message.error('重跑失败，请稍后重试');
    }
  };

  if (loading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', padding: 100 }}>
        <Spin size="large" tip="加载回测报告...">
          <div />
        </Spin>
      </div>
    );
  }

  if (!task) {
    return (
      <div style={{ maxWidth: 800, margin: '40px auto', padding: 24 }}>
        <Alert type="error" message="回测任务不存在或已被删除" showIcon />
        <Button style={{ marginTop: 16 }} icon={<ArrowLeftOutlined />} onClick={onBack}>返回回测列表</Button>
      </div>
    );
  }

  const isCompleted = task.status === 'COMPLETED';
  const isRunning = task.status === 'RUNNING';

  // 准备净值曲线数据
  const equityCurve = curveData?.equityCurve ?? [];
  const chartDates = equityCurve.map(p => p.tradeDate);
  const chartNavs = equityCurve.map(p => Number(p.nav).toFixed(6));

  const echartsOption = {
    backgroundColor: 'transparent',
    tooltip: {
      trigger: 'axis',
      formatter: (params) => {
        const p = params[0];
        return `${p.name}<br/>净值: <b>${Number(p.value).toFixed(4)}</b>`;
      },
    },
    grid: { top: 40, left: 60, right: 60, bottom: 50 },
    xAxis: {
      type: 'category',
      data: chartDates,
      axisLabel: {
        fontSize: 11,
        rotate: 30,
        formatter: v => v ? v.slice(0, 10) : '',
        interval: Math.max(0, Math.floor(chartDates.length / 8)),
      },
    },
    yAxis: {
      type: 'value',
      scale: true,
      axisLabel: { formatter: v => v.toFixed(3) },
    },
    series: [
      {
        name: '组合净值',
        type: 'line',
        data: chartNavs,
        smooth: false,
        symbol: 'none',
        lineStyle: { color: '#e64a19', width: 2 },
        areaStyle: {
          color: {
            type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(230,74,25,0.20)' },
              { offset: 1, color: 'rgba(230,74,25,0.02)' },
            ],
          },
        },
        markLine: {
          silent: true,
          symbol: 'none',
          data: [{ yAxis: 1, lineStyle: { color: '#999', type: 'dashed' } }],
          label: { formatter: '基准线', position: 'insideEndTop' },
        },
      },
    ],
  };

  return (
    <div style={{ padding: '16px 24px' }}>
      {/* 头部操作栏 */}
      <Space style={{ marginBottom: 16 }} wrap>
        <Button icon={<ArrowLeftOutlined />} onClick={onBack}>返回列表</Button>
        <Button icon={<ReloadOutlined />} loading={polling} onClick={fetchAll}>刷新</Button>
        {isRunning && (
          <Popconfirm title="确定取消该回测任务？" onConfirm={handleCancel}>
            <Button danger icon={<StopOutlined />}>取消任务</Button>
          </Popconfirm>
        )}
        {(isCompleted || task.status === 'FAILED' || task.status === 'CANCELLED') && (
          <Popconfirm title="将清空旧结果并重新执行，确认重跑？" onConfirm={handleRerun}>
            <Button icon={<RedoOutlined />}>重跑</Button>
          </Popconfirm>
        )}
        {isCompleted && (
          <Popconfirm title="确定删除该任务及其数据？" onConfirm={handleDelete}>
            <Button danger icon={<DeleteOutlined />}>删除</Button>
          </Popconfirm>
        )}
      </Space>

      {/* 任务基本信息 */}
      <Card
        title={
          <Space>
            <span style={{ fontWeight: 600 }}>{task.taskName}</span>
            <Tag color={STATUS_COLOR[task.status] ?? 'default'}>{task.status}</Tag>
            {polling && <Spin size="small" />}
          </Space>
        }
        style={{ marginBottom: 16 }}
      >
        {isRunning && (
          <Progress percent={task.progress ?? 0} status="active" style={{ marginBottom: 12 }} />
        )}
        <Descriptions column={{ xxl: 4, xl: 3, lg: 2, md: 2, sm: 1 }} size="small" bordered>
          <Descriptions.Item label="回测区间">{task.startDate} ~ {task.endDate}</Descriptions.Item>
          <Descriptions.Item label="调仓频率">{FREQ_LABEL[task.rebalanceFreq] ?? task.rebalanceFreq}</Descriptions.Item>
          <Descriptions.Item label="权重模式">{task.weightMode}</Descriptions.Item>
          <Descriptions.Item label="初始资金">¥{task.initialCapital ? (Number(task.initialCapital) / 10000).toFixed(1) + '万' : '-'}</Descriptions.Item>
          <Descriptions.Item label="基准指数">{task.benchmarkCode || '-'}</Descriptions.Item>
          <Descriptions.Item label="佣金率">{task.commissionRate ? (task.commissionRate * 1000).toFixed(1) + '‰' : '-'}</Descriptions.Item>
          <Descriptions.Item label={
            <span>成交价模式
              <Tooltip title="调仓时的股票成交价格来源">
                <QuestionCircleOutlined style={{ color: '#999', marginLeft: 4 }} />
              </Tooltip>
            </span>
          }>{ORDER_TYPE_LABEL[task.orderType] ?? task.orderType}</Descriptions.Item>
          <Descriptions.Item label="创建时间">{task.createdAt?.replace('T', ' ')}</Descriptions.Item>
        </Descriptions>
        {task.status === 'FAILED' && (
          <Alert style={{ marginTop: 12 }} type="error" message={`执行失败: ${task.errorMessage || '未知错误'}`} showIcon />
        )}
      </Card>

      {/* 选股策略配置 */}
      {task.screenConfigJson && (
        <ScreenConfigCard screenConfigJson={task.screenConfigJson} />
      )}

      {/* 核心绩效指标 */}
      {isCompleted && (
        <>
          <Row gutter={[12, 12]} style={{ marginBottom: 16 }}>
            {[
              {
                title: '累计收益率',
                value: task.totalReturn != null ? (task.totalReturn * 100).toFixed(2) : '-',
                suffix: '%',
                color: (task.totalReturn ?? 0) >= 0 ? '#cf1322' : '#3f8600',
              },
              {
                title: '年化收益率',
                value: task.annualReturn != null ? (task.annualReturn * 100).toFixed(2) : '-',
                suffix: '%',
                color: (task.annualReturn ?? 0) >= 0 ? '#cf1322' : '#3f8600',
              },
              {
                title: '最大回撤',
                value: task.maxDrawdown != null ? (task.maxDrawdown * 100).toFixed(2) : '-',
                suffix: '%',
                color: '#fa8c16',
              },
              { title: '夏普比率', value: task.sharpeRatio != null ? Number(task.sharpeRatio).toFixed(2) : '-', suffix: '' },
              { title: '最终净值', value: task.finalNav != null ? Number(task.finalNav).toFixed(4) : '-', suffix: '' },
              { title: '总交易笔数', value: task.totalTrades ?? '-', suffix: '' },
              {
                title: '胜率',
                value: task.winRate != null ? (task.winRate * 100).toFixed(1) : '-',
                suffix: '%',
                color: (task.winRate ?? 0) >= 0.5 ? '#cf1322' : '#3f8600',
              },
              {
                title: '超额收益',
                value: task.benchmarkReturn != null && task.totalReturn != null
                  ? ((task.totalReturn - task.benchmarkReturn) * 100).toFixed(2)
                  : (task.totalReturn != null ? '—' : '-'),
                suffix: (task.benchmarkReturn != null && task.totalReturn != null) ? '%' : '',
                color: task.benchmarkReturn != null && task.totalReturn != null
                  ? ((task.totalReturn - task.benchmarkReturn) >= 0 ? '#cf1322' : '#3f8600')
                  : undefined,
              },
            ].map((item, i) => (
              <Col key={i} xs={12} sm={8} md={6} lg={6} xl={3}>
                <Card size="small" styles={{ body: { padding: '12px 16px' } }}>
                  <Statistic
                    title={<Text type="secondary" style={{ fontSize: 12 }}>{item.title}</Text>}
                    value={item.value}
                    suffix={item.suffix}
                    valueStyle={{ fontSize: 18, color: item.color }}
                  />
                </Card>
              </Col>
            ))}
          </Row>

          {/* 净值曲线 */}
          <Card
            title="净值曲线"
            style={{ marginBottom: 16 }}
            extra={<Text type="secondary" style={{ fontSize: 12 }}>{equityCurve.length} 个数据点</Text>}
          >
            {equityCurve.length > 0 ? (
              <ReactECharts option={echartsOption} style={{ height: 320 }} />
            ) : (
              <Empty description="暂无曲线数据" image={Empty.PRESENTED_IMAGE_SIMPLE} />
            )}
          </Card>

          {/* 调仓记录 */}
          <Card title={`调仓记录（共 ${records.length} 次）`} style={{ marginBottom: 16 }}>
            {records.length > 0 ? (
              <Table
                dataSource={records}
                rowKey={(r) => r.id ?? r.rebalanceDate}
                size="small"
                pagination={{
                  pageSizeOptions: ['10', '20', '50'],
                  showSizeChanger: true,
                  showTotal: t => `共 ${t} 条`,
                }}
                columns={[
                  {
                    title: '调仓日期', dataIndex: 'rebalanceDate', width: 120, fixed: 'left',
                    defaultSortOrder: 'ascend',
                    sorter: (a, b) => (a.rebalanceDate ?? '').localeCompare(b.rebalanceDate ?? ''),
                  },
                  {
                    title: '持仓数', width: 80, align: 'center',
                    render: (_, r) => {
                      try {
                        const arr = JSON.parse(r.newPositionsJson ?? '[]');
                        return Array.isArray(arr) ? arr.length : '-';
                      } catch { return '-'; }
                    },
                  },
                  {
                    title: '组合净值', dataIndex: 'nav', width: 110, align: 'right',
                    render: v => v != null ? Number(v).toFixed(4) : '-',
                  },
                  {
                    title: '当日收益(%)', dataIndex: 'dailyReturn', width: 120, align: 'right',
                    render: v => v != null ? (
                      <Text style={{ color: v >= 0 ? '#cf1322' : '#3f8600' }}>
                        {v >= 0 ? '+' : ''}{(Number(v) * 100).toFixed(2)}
                      </Text>
                    ) : '-',
                  },
                  {
                    title: '组合市值(万)', dataIndex: 'totalValue', width: 130, align: 'right',
                    render: v => v != null ? (Number(v) / 10000).toFixed(2) : '-',
                  },
                  {
                    title: '持仓列表', dataIndex: 'newPositionsJson',
                    render: (v) => {
                      if (!v) return '-';
                      try {
                        const arr = JSON.parse(v);
                        if (Array.isArray(arr)) {
                          return (
                            <Space size={[4, 8]} wrap>
                              {arr.slice(0, 12).map((p, i) => {
                                const rawCode = p.symbol || p.code || '';
                                const code = rawCode.replace('.SZ', '').replace('.SH', '').replace('.BJ', '');
                                const name = p.name || '';
                                return (
                                  <Link
                                    key={i}
                                    to={`/stock-analysis?code=${encodeURIComponent(code)}`}
                                    target="_blank"
                                  >
                                    <Tag color="blue" style={{ margin: 0, fontSize: 11, cursor: 'pointer' }}>
                                      {name} {code}
                                    </Tag>
                                  </Link>
                                );
                              })}
                              {arr.length > 12 && <Tag>+{arr.length - 12}</Tag>}
                            </Space>
                          );
                        }
                      } catch { /* ignore */ }
                      return String(v).slice(0, 60);
                    },
                  },
                ]}
                scroll={{ x: 800 }}
              />
            ) : (
              <Empty description="暂无调仓记录" image={Empty.PRESENTED_IMAGE_SIMPLE} />
            )}
          </Card>
        </>
      )}

      {isRunning && (
        <Alert
          type="info"
          message="回测正在运行中，每 5 秒自动刷新进度..."
          description={`进度: ${task.progress ?? 0}%`}
          showIcon
          style={{ marginBottom: 16 }}
        />
      )}
    </div>
  );
}

// ─────────────────────────────────────────────────────────
//  任务列表组件
// ─────────────────────────────────────────────────────────
function RollingBacktestList({ onSelect }) {
  const [tasks, setTasks] = useState([]);
  const [loading, setLoading] = useState(false);
  const navigate = useNavigate();

  const loadTasks = useCallback(() => {
    setLoading(true);
    rollingScreenApi.listTasks({ page: 0, size: 50 })
      .then(res => {
        const data = res?.data ?? res;
        setTasks(data?.records ?? data?.content ?? data ?? []);
      })
      .catch(() => setTasks([]))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => { loadTasks(); }, [loadTasks]);

  const handleDelete = async (taskId, e) => {
    e.stopPropagation();
    try {
      await rollingScreenApi.delete(taskId);
      message.success('已删除');
      loadTasks();
    } catch {
      message.error('删除失败');
    }
  };

  const handleRerunList = async (taskId, e) => {
    e.stopPropagation();
    try {
      await rollingScreenApi.rerun(taskId);
      message.success('已重新提交，回测正在执行...');
      loadTasks();
    } catch {
      message.error('重跑失败，请稍后重试');
    }
  };

  return (
    <div>
      {/* 页面头部：标题 + 操作 */}
      <div className="page-header" style={{ marginBottom: 16 }}>
        <Space align="center">
          <Title level={4} style={{ margin: 0 }}>滚动选股回测</Title>
          <Tooltip
            title={
              <div style={{ lineHeight: 1.8 }}>
                <b>功能价值</b><br/>
                基于因子选股策略，在历史区间内模拟完整调仓过程，验证策略的长期表现。<br/><br/>
                <b>使用方法</b><br/>
                1. 在「因子选股」页面配置筛选条件，点击「运行」<br/>
                2. 筛选结果卡片中点击「滚动回测」按钮<br/>
                3. 设置回测区间/频率/资金后提交<br/>
                <br/>
                <b>适用场景</b><br/>
                · 回测验证因子策略的长期有效性<br/>
                · 对比不同参数（频率/资金/费率）的影响<br/>
                · 观察最大回撤、夏普比率等风险指标
              </div>
            }
            styles={{ body: { width: 420 } }}
          >
            <QuestionCircleOutlined style={{ color: '#999', cursor: 'help' }} />
          </Tooltip>
        </Space>
        <Button icon={<ReloadOutlined />} onClick={loadTasks}>刷新</Button>
      </div>

      {/* 表格 Card 包裹，撑满宽度 */}
      <Card border="true" style={{ border: '1px solid #d9d9d9' }}>
        <Table
          dataSource={tasks}
          rowKey="id"
          loading={loading}
          pagination={{ pageSize: 15, showSizeChanger: false, showTotal: t => `共 ${t} 条` }}
          scroll={{ x: 1380 }}
          onRow={(r) => ({
            onClick: () => onSelect?.(r.id),
            style: { cursor: 'pointer' },
          })}
          columns={[
            {
              title: '任务名称', dataIndex: 'taskName', ellipsis: true, fixed: 'left', width: 180,
              render: (v, r) => <a onClick={() => onSelect?.(r.id)}>{v}</a>,
            },
            {
              title: '状态', dataIndex: 'status', width: 100, align: 'center',
              render: s => <Tag color={STATUS_COLOR[s] ?? 'default'}>{s}</Tag>,
            },
            {
              title: '回测区间', width: 220,
              render: (_, r) => `${r.startDate} ~ ${r.endDate}`,
            },
            { title: '频率', dataIndex: 'rebalanceFreq', width: 90, align: 'center',
              render: v => FREQ_LABEL[v] ?? v },
            {
              title: '选股策略', width: 180, ellipsis: true,
              render: (_, r) => {
                if (!r.screenConfigJson) return <Text type="secondary">-</Text>;
                try {
                  const c = JSON.parse(r.screenConfigJson);
                  const factors = Array.isArray(c.factors) ? c.factors : [];
                  const dirLabel = c.direction === 'LONG' ? '多' : c.direction === 'SHORT' ? '空' : '';
                  // 优先显示策略组合名，没有则显示因子摘要
                  const displayName = c.presetName || `${factors.length}因子·Top${c.topN ?? '-'}`;
                  return (
                    <Tooltip title={
                      <div style={{ fontSize: 12, maxWidth: 300 }}>
                        {c.presetName && <div><b>组合:</b> {c.presetName}</div>}
                        <div>方向: {c.direction || '-'} | TopN: {c.topN ?? '-'}</div>
                        <div style={{ marginTop: 4 }}>因子: {factors.map(f => f.factorCode).join(', ')}</div>
                        {c.maPositionFilter && (c.maPositionFilter.aboveMA30 || c.maPositionFilter.aboveMA60) && (
                          <div style={{ marginTop: 4, color: '#1890ff' }}>
                            MA过滤: {[c.maPositionFilter.aboveMA30 ? 'MA30' : null, c.maPositionFilter.aboveMA60 ? 'MA60' : null, c.maPositionFilter.aboveMA100 ? 'MA100' : null].filter(Boolean).join('+')}
                          </div>
                        )}
                      </div>
                    }>
                      <span>
                        <Tag color={c.direction === 'LONG' ? 'red' : 'green'} size="small">{dirLabel}</Tag>
                        <Text style={{ fontSize: 12 }}>{displayName}</Text>
                      </span>
                    </Tooltip>
                  );
                } catch { return <Text type="secondary">解析失败</Text>; }
              },
            },
            {
              title: '初始资金', width: 110, align: 'right',
              render: (_, r) => r.initialCapital ? `¥${(Number(r.initialCapital) / 10000).toFixed(0)}万` : '-',
            },
            {
              title: '累计收益', dataIndex: 'totalReturn', width: 110, align: 'right',
              render: v => v != null ? (
                <Typography.Text style={{ color: v >= 0 ? '#cf1322' : '#3f8600', fontWeight: 500 }}>
                  {v >= 0 ? '+' : ''}{(v * 100).toFixed(2)}%
                </Typography.Text>
              ) : '-',
            },
            {
              title: '最大回撤', dataIndex: 'maxDrawdown', width: 100, align: 'right',
              render: v => v != null ? <Typography.Text type="warning">{(v * 100).toFixed(2)}%</Typography.Text> : '-',
            },
            {
              title: '夏普', dataIndex: 'sharpeRatio', width: 80, align: 'right',
              render: v => v != null ? Number(v).toFixed(2) : '-',
            },
            {
              title: '创建时间', dataIndex: 'createdAt', width: 160,
              render: v => v ? String(v).replace('T', ' ').slice(0, 19) : '-',
            },
            {
              title: '操作', width: 110, align: 'center', fixed: 'right',
              render: (_, r) => (
                <Space size={4}>
                  {(r.status === 'COMPLETED' || r.status === 'FAILED' || r.status === 'CANCELLED') && (
                    <Popconfirm
                      title="将清空旧结果并重新执行？"
                      onConfirm={(e) => handleRerunList(r.id, e)}
                      onClick={e => e.stopPropagation()}
                    >
                      <Button
                        type="text"
                        size="small"
                        icon={<RedoOutlined />}
                        onClick={e => e.stopPropagation()}
                      />
                    </Popconfirm>
                  )}
                  <Popconfirm
                    title="确定删除？"
                    onConfirm={(e) => handleDelete(r.id, e)}
                    onClick={e => e.stopPropagation()}
                  >
                    <Button
                      type="text"
                      danger
                      size="small"
                      icon={<DeleteOutlined />}
                      onClick={e => e.stopPropagation()}
                    />
                  </Popconfirm>
                </Space>
              ),
            },
          ]}
        />
      </Card>
    </div>
  );
}
