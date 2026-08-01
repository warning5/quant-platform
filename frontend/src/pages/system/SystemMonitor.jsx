import React, { useEffect, useState, useCallback, useRef } from 'react';
import {
  Card, Row, Col, Statistic, Table, Tag, Typography, Progress, Spin, Button, Space, App,
  Tooltip, Select,
} from 'antd';
import { ReloadOutlined, DashboardOutlined } from '@ant-design/icons';
import monitorApi from '../../api/monitor';

const { Text } = Typography;

/* ---------- 格式化工具 ---------- */
const fmtDuration = (s) => {
  if (s == null) return '-';
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  const r = s % 60;
  return r === 0 ? `${m}min` : `${m}min${r}s`;
};
const fmtUptime = (sec) => {
  if (!sec) return '-';
  const d = Math.floor(sec / 86400);
  const h = Math.floor((sec % 86400) / 3600);
  const m = Math.floor((sec % 3600) / 60);
  if (d > 0) return `${d}天${h}时`;
  return `${h}时${m}分`;
};

/* ---------- 纯 SVG 迷你折线图（无依赖） ---------- */
function Sparkline({ data, width = 280, height = 56, color = '#1677ff', fill = false, maxPoints = 20 }) {
  const pts = (data || []).slice(-maxPoints);
  if (pts.length < 2) {
    return (
      <svg width={width} height={height} style={{ display: 'block' }}>
        <text x={4} y={height - 6} fontSize={11} fill="#999">数据收集中…</text>
      </svg>
    );
  }
  const min = Math.min(...pts);
  const max = Math.max(...pts);
  const range = max - min || 1;
  const pad = 4;
  const w = (width - pad * 2) / Math.max(1, pts.length - 1);
  const h = height - pad * 2;
  const toXY = (v, i) => [pad + i * w, pad + h - ((v - min) / range) * h];
  const linePts = pts.map(toXY).map((p) => p.join(',')).join(' ');
  const areaPts = `${pad},${pad + h} ${linePts} ${pad + w * (pts.length - 1)},${pad + h}`;
  return (
    <svg width={width} height={height} style={{ display: 'block' }}>
      {fill && <polygon points={areaPts} fill={`${color}18`} />}
      <polyline points={linePts} fill="none" stroke={color} strokeWidth="1.8" strokeLinejoin="round" strokeLinecap="round" />
      {(() => {
        const [lx, ly] = toXY(pts[pts.length - 1], pts.length - 1);
        return <circle cx={lx} cy={ly} r="3" fill={color} />;
      })()}
    </svg>
  );
}

/* ---------- 水平柱状图（页面访问分布） ---------- */
function HorizontalBar({ data, width = 420, barHeight = 24, maxBars = 8, color = '#1677ff' }) {
  const items = (data || []).slice(0, maxBars);
  if (!items.length) return <Text type="secondary" style={{ padding: 12, display: 'block' }}>暂无访问记录</Text>;
  const maxVal = Math.max(...items.map((d) => d.count), 1);
  return (
    <div style={{ padding: '4px 0' }}>
      {items.map((d, i) => {
        const pct = (d.count / maxVal) * 100;
        const label = d.path.length > 30 ? d.path.slice(0, 28) + '\u2026' : d.path;
        return (
          <div key={d.path || i} style={{ display: 'flex', alignItems: 'center', marginBottom: 6 }}>
            <Text ellipsis style={{ width: 160, fontSize: 12, flexShrink: 0 }} title={d.path}>{label}</Text>
            <div style={{ flex: 1, marginLeft: 8, height: barHeight, background: '#f5f5f5', borderRadius: 4, position: 'relative', overflow: 'hidden' }}>
              <div style={{
                width: `${pct}%`, height: '100%', borderRadius: 4,
                background: `linear-gradient(90deg, ${typeof color === 'function' ? color(d) : color}cc, ${typeof color === 'function' ? color(d) : color})`,
                transition: 'width 0.6s ease',
              }} />
            </div>
            <Text style={{ width: 48, textAlign: 'right', fontSize: 12, marginLeft: 6 }}>{d.count}</Text>
          </div>
        );
      })}
    </div>
  );
}

/* ---------- 状态指示环（SVG 圆环进度） ---------- */
function StatusRing({ value, label, sub, color = '#3f8600', size = 72 }) {
  const strokeW = 5;
  const r = (size - strokeW * 2) / 2;
  const circ = 2 * Math.PI * r;
  const pct = Math.min(100, Math.max(0, value));
  const offset = circ - (pct / 100) * circ;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
      <svg width={size} height={size}>
        <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="#f0f0f0" strokeWidth={strokeW} />
        <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke={color} strokeWidth={strokeW}
          strokeDasharray={circ} strokeDashoffset={offset} strokeLinecap="round"
          transform={`rotate(-90 ${size / 2} ${size / 2})`}
          style={{ transition: 'stroke-dashoffset 0.8s ease' }}
        />
        <text x={size / 2} y={size / 2 + 5} textAnchor="middle" fontSize={16} fontWeight={600} fill="#262626">{value}%</text>
      </svg>
      <Text strong style={{ fontSize: 12, marginTop: 4 }}>{label}</Text>
      {sub && <Text type="secondary" style={{ fontSize: 11 }}>{sub}</Text>}
    </div>
  );
}

/* ========== 主面板 ========== */
export default function SystemMonitor() {
  const { message } = App.useApp();
  const [overview, setOverview] = useState(null);
  const [httpLog, setHttpLog] = useState([]);
  const [behavior, setBehavior] = useState(null);
  const [loading, setLoading] = useState(false);
  const [autoRefresh, setAutoRefresh] = useState(true);
  const [refreshInterval, setRefreshInterval] = useState(5);

  // 时间序列历史（用于趋势图）
  const heapHistory = useRef([]);
  const qpsHistory = useRef([]);
  const chLatencyHistory = useRef([]);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [ov, beh] = await Promise.all([
        monitorApi.overview(),
        monitorApi.behavior(),
      ]);
      setOverview(ov);
      setBehavior(beh);

      // 记录趋势数据
      if (ov?.jvm) {
        const pct = ov.jvm.heapMaxMb > 0 ? Math.round((ov.jvm.heapUsedMb / ov.jvm.heapMaxMb) * 100) : 0;
        heapHistory.current.push({ t: Date.now(), v: pct });
        if (heapHistory.current.length > 60) heapHistory.current.shift();
      }
      if (ov?.http?.qps != null) {
        qpsHistory.current.push({ t: Date.now(), v: ov.http.qps });
        if (qpsHistory.current.length > 60) qpsHistory.current.shift();
      }
      if (ov?.clickhouse?.latencyMs != null && ov.clickhouse.latencyMs >= 0) {
        chLatencyHistory.current.push({ t: Date.now(), v: ov.clickhouse.latencyMs });
        if (chLatencyHistory.current.length > 60) chLatencyHistory.current.shift();
      }
    } catch (e) {
      message.error('加载监控数据失败');
    } finally {
      setLoading(false);
    }
  }, [message]);

  const loadLog = useCallback(async () => {
    try {
      const log = await monitorApi.httpLog();
      setHttpLog(Array.isArray(log) ? log : []);
    } catch {
      /* 静默 */
    }
  }, []);

  useEffect(() => {
    load();
    loadLog();
    if (!autoRefresh) return;
    const t = setInterval(() => { load(); loadLog(); }, refreshInterval * 1000);
    return () => clearInterval(t);
  }, [load, loadLog, autoRefresh, refreshInterval]);

  const jvm = overview?.jvm;
  const http = overview?.http;
  const ch = overview?.clickhouse;
  const tasks = overview?.tasks;

  const heapPct = jvm && jvm.heapMaxMb > 0 ? Math.round((jvm.heapUsedMb / jvm.heapMaxMb) * 100) : 0;

  const logColumns = [
    { title: '时间', dataIndex: 'ts', key: 'ts', width: 110, render: (v) => new Date(v).toLocaleTimeString() },
    { title: '方法', dataIndex: 'method', key: 'method', width: 70 },
    { title: '路径', dataIndex: 'path', key: 'path', ellipsis: true },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 80,
      render: (s) => <Tag color={s >= 400 ? 'red' : s >= 300 ? 'blue' : 'green'}>{s}</Tag>,
    },
    {
      title: '耗时', dataIndex: 'durationMs', key: 'durationMs', width: 90,
      render: (v) => <Text style={{ color: v > 1000 ? '#cf1322' : undefined }}>{v}ms</Text>,
    },
  ];

  // HTTP 状态码分布
  const statusDist = {};
  httpLog.forEach((r) => {
    const key = r.status >= 400 ? '4xx/5xx' : r.status >= 300 ? '3xx' : '2xx';
    statusDist[key] = (statusDist[key] || 0) + 1;
  });

  return (
    <div style={{ padding: 16 }}>
      {/* 标题栏 */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <Space>
          <DashboardOutlined style={{ fontSize: 20 }} />
          <Text strong style={{ fontSize: 18 }}>系统监控面板</Text>
          <Tag color={autoRefresh ? 'green' : 'default'} style={{ marginLeft: 8 }}>
            {autoRefresh ? `自动 ${refreshInterval}s` : '已暂停'}
          </Tag>
        </Space>
        <Space>
          <Select size="small" value={refreshInterval} onChange={setRefreshInterval} style={{ width: 90 }}>
            <Select.Option value={3}>3s</Select.Option>
            <Select.Option value={5}>5s</Select.Option>
            <Select.Option value={10}>10s</Select.Option>
            <Select.Option value={30}>30s</Select.Option>
          </Select>
          <Button size="small" icon={<ReloadOutlined />} onClick={() => { load(); loadLog(); }}>手动刷新</Button>
          <Button
            size="small"
            type={autoRefresh ? 'default' : 'primary'}
            onClick={() => setAutoRefresh(!autoRefresh)}
          >
            {autoRefresh ? '暂停' : '恢复'}
          </Button>
        </Space>
      </div>

      {!overview ? (
        <Spin size="large" style={{ display: 'block', margin: '80px auto' }} />
      ) : (
        <>
          {/* ===== 第一行：核心指标卡片（含迷你图+状态环） ===== */}
          <Row gutter={[14, 14]} style={{ alignItems: 'stretch' }}>
            <Col xs={24} md={12} lg={6}>
              <Card size="small" title="JVM 堆内存" style={{ height: '100%' }}>
                <div style={{ display: 'flex', justifyContent: 'center' }}>
                  <StatusRing value={heapPct} label="使用率" sub={`${jvm.heapUsedMb}/${jvm.heapMaxMb}MB`} color={heapPct > 85 ? '#cf1322' : heapPct > 70 ? '#faad14' : '#3f8600'} />
                </div>
                <div style={{ marginTop: 8 }}>
                  <Text type="secondary" style={{ fontSize: 12 }}>堆使用趋势</Text>
                  <Sparkline data={heapHistory.current.map((h) => h.v)} color={heapPct > 85 ? '#cf1322' : '#1677ff'} fill />
                </div>
                <Row gutter={[8, 0]} style={{ marginTop: 8 }}>
                  <Col span={12}><Text type="secondary" style={{ fontSize: 11 }}>提交 {jvm.heapCommittedMb}MB</Text></Col>
                  <Col span={12}><Text type="secondary" style={{ fontSize: 11 }}>线程 {jvm.threadCount}/{jvm.peakThreadCount}</Text></Col>
                  <Col span={12}><Text type="secondary" style={{ fontSize: 11 }}>近5分钟 GC {jvm.gcCountRecent ?? 0}次·{jvm.gcTimeMsRecent ?? 0}ms</Text></Col>
                  <Col span={12}><Text type="secondary" style={{ fontSize: 11 }}>累计 {jvm.gcCount ?? 0}次</Text></Col>
                  <Col span={12}><Text type="secondary" style={{ fontSize: 11 }}>CPU {jvm.processors}核</Text></Col>
                </Row>
              </Card>
            </Col>

            <Col xs={24} md={12} lg={6}>
              <Card size="small" title="HTTP 流量" style={{ height: '100%' }}>
                <div style={{ display: 'flex', alignItems: 'baseline', gap: 12, marginBottom: 4 }}>
                  <Statistic title="QPS" value={http.qps} precision={1} valueStyle={{ fontSize: 22 }} />
                </div>
                <div><Text type="secondary" style={{ fontSize: 12 }}>QPS 趋势</Text></div>
                <Sparkline data={qpsHistory.current.map((h) => h.v)} color="#52c41a" height={44} />
                <div style={{ marginTop: 8 }}>
                  <Row gutter={[8, 4]}>
                    <Col span={8}><Text type="secondary" style={{ fontSize: 11 }}>{http.total}</Text></Col>
                    <Col span={8}><Text type="secondary" style={{ fontSize: 11 }}>近1分{http.lastMinute}</Text></Col>
                    <Col span={8}><Text type="secondary" style={{ fontSize: 11, color: http.errors > 0 ? '#cf1322' : undefined }}>错误{http.errors}</Text></Col>
                    <Col span={12}><Text type="secondary" style={{ fontSize: 11 }}>均{http.avgMs}ms</Text></Col>
                    <Col span={12}><Text type="secondary" style={{ fontSize: 11, color: (http.p95Ms || 0) > 1000 ? '#cf1322' : undefined }}>P95 {http.p95Ms}ms</Text></Col>
                  </Row>
                </div>
              </Card>
            </Col>

            <Col xs={24} md={12} lg={6}>
              <Card size="small" title="ClickHouse" style={{ height: '100%' }}>
                <div style={{ display: 'flex', justifyContent: 'center' }}>
                  <StatusRing
                    value={ch.enabled ? (ch.healthy ? 100 : 0) : -1}
                    label={ch.enabled ? (ch.healthy ? '正常' : '异常') : '未启用'}
                    sub={ch.enabled ? `${ch.latencyMs}ms` : '-'}
                    color={ch.healthy ? '#3f8600' : ch.enabled ? '#cf1322' : '#8c8c8c'}
                    size={72}
                  />
                </div>
                {/* 状态标签行 */}
                <div style={{ display: 'flex', justifyContent: 'center', gap: 6, marginTop: 6, flexWrap: 'wrap' }}>
                  <Tag color={ch.enabled ? 'green' : 'default'}>{ch.enabled ? '已启用' : '未配置'}</Tag>
                  <Tag color={ch.healthy ? 'blue' : ch.enabled ? 'red' : 'default'}>{ch.healthy ? '健康' : ch.enabled ? '异常' : '-'}</Tag>
                  {ch.tableCount > 0 && <Tag color="purple">{ch.tableCount} 张表</Tag>}
                </div>
                {/* 延迟趋势 */}
                {ch.enabled && ch.healthy && (
                  <div style={{ marginTop: 6 }}>
                    <Text type="secondary" style={{ fontSize: 11 }}>查询延迟趋势</Text>
                    <Sparkline data={chLatencyHistory.current.map((h) => h.v)} color="#722ed1" height={40} maxPoints={15} />
                  </div>
                )}
                {/* 版本与详情 */}
                <Row gutter={[8, 0]} style={{ marginTop: 6 }}>
                  {ch.version && (
                    <Col span={12}><Text type="secondary" style={{ fontSize: 11 }}>版本 <Text copyable style={{ fontSize: 10 }}>{ch.version}</Text></Text></Col>
                  )}
                  <Col span={ch.version ? 12 : 24}><Text type="secondary" style={{ fontSize: 11 }}>延迟 {ch.latencyMs >= 0 ? ch.latencyMs + 'ms' : '-'}</Text></Col>
                </Row>
                {!ch.enabled && <Text type="secondary" style={{ fontSize: 11, display: 'block', textAlign: 'center', marginTop: 4 }}>未配置 ClickHouse 数据源</Text>}
              </Card>
            </Col>

            <Col xs={24} md={12} lg={6}>
              <Card size="small" title="调度任务" style={{ height: '100%' }}>
                <div style={{ display: 'flex', justifyContent: 'center' }}>
                  <StatusRing
                    value={tasks.runningCount > 0 ? 100 : (tasks.failedToday > 0 ? 50 : (tasks.totalToday > 0 ? 80 : 0))}
                    label={`${tasks.runningCount} 运行中`}
                    sub={`今日失败 ${tasks.failedToday}`}
                    color={tasks.runningCount > 0 ? '#1677ff' : tasks.failedToday > 0 ? '#faad14' : tasks.totalToday > 0 ? '#52c41a' : '#8c8c8c'}
                    size={72}
                  />
                </div>
                {/* 今日统计行 */}
                <div style={{ display: 'flex', justifyContent: 'center', gap: 6, marginTop: 6, flexWrap: 'wrap' }}>
                  <Tag color="blue">总计 {tasks.totalToday}</Tag>
                  <Tag color="green">成功 {tasks.successToday}</Tag>
                  <Tag color={tasks.failedToday > 0 ? 'red' : 'default'}>失败 {tasks.failedToday}</Tag>
                </div>
                {/* 成功率 */}
                {tasks.totalToday > 0 && (
                  <div style={{ marginTop: 4, textAlign: 'center' }}>
                    <Text type="secondary" style={{ fontSize: 11 }}>
                      成功率{' '}
                      <Text strong style={{
                        fontSize: 13,
                        color: (tasks.successToday / tasks.totalToday) >= 0.95 ? '#52c41a'
                          : (tasks.successToday / tasks.totalToday) >= 0.8 ? '#faad14' : '#cf1322',
                      }}>
                        {(tasks.successToday / tasks.totalToday * 100).toFixed(1)}%
                      </Text>
                    </Text>
                    <Progress
                      percent={Math.round(tasks.successToday / tasks.totalToday * 100)}
                      size="small"
                      strokeColor={(tasks.successToday / tasks.totalToday) >= 0.95 ? '#52c41a'
                        : (tasks.successToday / tasks.totalToday) >= 0.8 ? '#faad14' : '#cf1322'}
                      style={{ margin: '2px 0' }}
                    />
                  </div>
                )}
                {/* 最近执行记录（迷你表） */}
                {tasks.recentTasks && tasks.recentTasks.length > 0 && (
                  <div style={{ marginTop: 6, fontSize: 11 }}>
                    <Text type="secondary" style={{ fontSize: 11 }}>最近执行</Text>
                    <div style={{ maxHeight: 100, overflowY: 'auto', marginTop: 2 }}>
                      {tasks.recentTasks.map((t, i) => (
                        <div key={`${t.taskKey}-${i}`} style={{
                          display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                          padding: '3px 4px', borderBottom: i < tasks.recentTasks.length - 1 ? '1px solid #f5f5f5' : 'none',
                          borderRadius: 2,
                        }}>
                          <Tooltip title={`${t.taskKey} · ${t.status} · 开始${t.startTime?.replace('T', ' ') || '-'} · 耗时${fmtDuration(t.durationSec)}`}>
                            <Text ellipsis style={{ maxWidth: 120, fontSize: 11 }}>{t.taskKey}</Text>
                          </Tooltip>
                          <Tag
                            color={t.status === 'SUCCESS' ? 'green' : t.status === 'FAILED' ? 'red' : t.status === 'RUNNING' ? 'blue' : 'default'}
                            style={{ marginLeft: 4, fontSize: 10, lineHeight: '16px', padding: '0 4px' }}
                          >
                            {t.status === 'SUCCESS' ? '成功' : t.status === 'FAILED' ? '失败' : t.status === 'RUNNING' ? '运行中' : t.status}
                          </Tag>
                        </div>
                      ))}
                    </div>
                  </div>
                )}
              </Card>
            </Col>
          </Row>

          {/* ===== 第二行：请求日志 + 状态分布 ===== */}
          <Row gutter={[14, 14]} style={{ marginTop: 4 }}>
            <Col xs={24} lg={16}>
              <Card size="small" title={`最近请求 (${httpLog.length})`} styles={{ body: { padding: 0 } }}>
                <Table
                  rowKey={(r) => `${r.ts}-${r.path}`}
                  columns={logColumns}
                  dataSource={httpLog}
                  pagination={false}
                  size="small"
                  scroll={{ y: 260 }}
                />
              </Card>
            </Col>
            <Col xs={24} lg={8}>
              <Card size="small" title="状态码分布">
                <HorizontalBar
                  data={Object.entries(statusDist).map(([k, v]) => ({ path: k, count: v }))}
                  width={300}
                  color={(d) => d.path === '4xx/5xx' ? '#cf1322' : d.path === '3xx' ? '#1677ff' : '#52c41a'}
                  maxBars={5}
                />
              </Card>
            </Col>
          </Row>

          {/* ===== 第三行：行为统计（图表化） ===== */}
          <Row gutter={[14, 14]} style={{ marginTop: 4 }}>
            <Col xs={24} lg={14}>
              <Card size="small" title="页面访问分布">
                <HorizontalBar
                  data={(behavior?.pageViews || []).map((p) => ({ path: p.path, count: p.count }))}
                  width={520}
                  maxBars={10}
                  color="#722ed1"
                />
              </Card>
            </Col>
            <Col xs={24} lg={10}>
              <Card size="small" title="运行时 & 活跃度">
                <Row gutter={[12, 12]}>
                  <Col span={12} style={{ textAlign: 'center' }}>
                    <Statistic title="在线会话" value={behavior?.onlineCount ?? '-'} valueStyle={{ fontSize: 22 }} />
                  </Col>
                  <Col span={12} style={{ textAlign: 'center' }}>
                    <Statistic title="今日活跃任务" value={behavior?.todayActiveTasks ?? '-'} valueStyle={{ fontSize: 22 }} />
                  </Col>
                </Row>
                <div style={{ marginTop: 16, paddingTop: 12, borderTop: '1px solid #f0f0f0' }}>
                  <Space wrap size="small">
                    <Tag>运行时长 <Text copyable>{fmtUptime(jvm.uptimeSec)}</Text></Tag>
                    <Tag>堆峰值 {jvm.heapCommittedMb}MB</Tag>
                    <Tag>CPU {jvm.processors}核</Tag>
                  </Space>
                </div>
                <div style={{ marginTop: 8 }}>
                  <Text type="secondary" style={{ fontSize: 11 }}>
                    页面访问由前端路由切换埋点实时上报 · 内存环形缓冲上限500条 · 重启后清空
                  </Text>
                </div>
              </Card>
            </Col>
          </Row>
        </>
      )}
    </div>
  );
}
