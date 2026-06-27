import React, { useEffect, useState, useMemo } from 'react';
import {
  Card, Row, Col, Table, Button, Space, Typography, Spin, Alert,
  Tag, Tooltip, Select, Empty, Statistic, Badge,
} from 'antd';
import {
  ArrowLeftOutlined, ReloadOutlined, LineChartOutlined,
  TrophyOutlined, RiseOutlined, FallOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import ReactECharts from '../../components/LazyECharts';
import { backtestApi } from '../../api';

const { Title, Text } = Typography;
const { Option } = Select;

const fmtPct = (v, d = 2) => v != null ? `${(+v * 100).toFixed(d)}%` : '-';
const fmt = (v, d = 2) => v != null ? (+v).toFixed(d) : '-';
const signStyle = (v) => ({
  color: v == null ? '#262626' : +v > 0 ? '#cf1322' : +v < 0 ? '#3f8600' : '#262626',
  fontWeight: 600,
});

// ─── 多曲线净值图 ──────────────────────────────────────────────────────────────
function MultiCurveChart({ curves }) {
  if (!curves || curves.length === 0) return <Empty description="请选择至少2个回测" />;

  // 找出共同日期范围（取最长那条的日期列表）
  const longestCurve = curves.reduce((a, b) =>
    (b.data?.length || 0) > (a.data?.length || 0) ? b : a, curves[0]);
  const dates = (longestCurve.data || []).map(d => d.date);

  const series = curves.map(c => {
    const dataMap = {};
    (c.data || []).forEach(d => { dataMap[d.date] = +((+d.value - 1) * 100).toFixed(4); });
    return {
      name: c.name,
      type: 'line',
      smooth: false,
      symbol: 'none',
      lineStyle: { width: 2, color: c.color },
      itemStyle: { color: c.color },
      data: dates.map(dt => dataMap[dt] ?? null),
      connectNulls: true,
    };
  });

  const option = {
    backgroundColor: 'transparent',
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'cross', lineStyle: { type: 'dashed' } },
      formatter: params => {
        const date = params[0]?.name;
        let html = `<div style="font-weight:600;margin-bottom:4px">${date}</div>`;
        params.forEach(p => {
          if (p.value == null) return;
          const sign = p.value >= 0 ? '+' : '';
          html += `<div><span style="color:${p.color}">●</span> ${p.seriesName}：<b>${sign}${(+p.value).toFixed(2)}%</b></div>`;
        });
        return html;
      },
    },
    legend: {
      data: curves.map(c => c.name),
      bottom: 0,
      type: 'scroll',
    },
    grid: { top: 20, left: 60, right: 20, bottom: 60 },
    xAxis: {
      type: 'category',
      data: dates,
      axisLabel: {
        fontSize: 11,
        formatter: v => v?.slice(0, 7),
        interval: Math.floor(dates.length / 8),
      },
    },
    yAxis: {
      type: 'value',
      axisLabel: { formatter: v => `${v > 0 ? '+' : ''}${v}%`, fontSize: 11 },
      splitLine: { lineStyle: { type: 'dashed', color: '#f0f0f0' } },
    },
    series,
  };

  return <ReactECharts option={option} style={{ height: 400 }} notMerge={true} />;
}

// ─── 指标雷达图 ────────────────────────────────────────────────────────────────
function RadarChart({ metrics }) {
  if (!metrics || metrics.length === 0) return null;

  const indicators = [
    { name: '年化收益', max: 1 },
    { name: '夏普比率', max: 3 },
    { name: '胜率', max: 1 },
    { name: '信息比率', max: 3 },
    { name: '卡玛比率', max: 2 },
    { name: '稳定性(1-回撤)', max: 1 },
  ];

  const serieData = metrics.map(m => ({
    name: m.taskName,
    value: [
      Math.min(Math.max(+(m.annualReturn || 0), -1), 1),
      Math.min(Math.max(+(m.sharpeRatio || 0), 0), 3),
      Math.min(Math.max(+(m.winRate || 0), 0), 1),
      Math.min(Math.max(+(m.informationRatio || 0), 0), 3),
      Math.min(Math.max(+(m.calmarRatio || 0), 0), 2),
      Math.min(1 - Math.abs(+(m.maxDrawdown || 0)), 1),
    ],
    lineStyle: { color: m.color },
    areaStyle: { color: m.color, opacity: 0.1 },
    itemStyle: { color: m.color },
  }));

  const option = {
    backgroundColor: 'transparent',
    tooltip: { trigger: 'item' },
    legend: { data: metrics.map(m => m.taskName), bottom: 0, type: 'scroll' },
    radar: {
      indicator: indicators,
      radius: '65%',
      splitArea: { areaStyle: { color: ['rgba(0,0,0,0.02)', 'rgba(0,0,0,0.04)'] } },
    },
    series: [{ type: 'radar', data: serieData }],
  };

  return <ReactECharts option={option} style={{ height: 320 }} notMerge={true} />;
}

// ─── 主页面 ───────────────────────────────────────────────────────────────────
export default function BacktestCompare() {
  const navigate = useNavigate();
  const [loading, setLoading] = useState(false);
  const [loadingTasks, setLoadingTasks] = useState(false);
  const [tasks, setTasks] = useState([]);
  const [selectedIds, setSelectedIds] = useState([]);
  const [compareResult, setCompareResult] = useState(null);
  const [error, setError] = useState(null);

  // 加载已完成的回测任务列表
  useEffect(() => {
    setLoadingTasks(true);
    backtestApi.list({ page: 0, size: 100, status: 'COMPLETED' })
      .then(res => setTasks(res?.records || []))
      .catch(() => {})
      .finally(() => setLoadingTasks(false));
  }, []);

  const handleCompare = () => {
    if (selectedIds.length < 2) return;
    setLoading(true);
    setError(null);
    backtestApi.compare(selectedIds)
      .then(res => setCompareResult(res))
      .catch(e => setError(e.message || '对比失败'))
      .finally(() => setLoading(false));
  };

  // ── 指标对比表格列 ─────────────────────────────────────────────────────────
  const columns = useMemo(() => {
    if (!compareResult?.metrics?.length) return [];
    const base = [
      {
        title: '排名', dataIndex: 'rank', key: 'rank', width: 60, fixed: 'left',
        render: v => v === 1 ? <TrophyOutlined style={{ color: '#faad14', fontSize: 16 }} /> : v,
      },
      {
        title: '策略名称', dataIndex: 'taskName', key: 'name', width: 160, fixed: 'left', ellipsis: true,
        render: (v, r) => <><span style={{ display: 'inline-block', width: 8, height: 8, borderRadius: '50%', background: r.color, marginRight: 6 }} />{v}</>,
      },
      { title: '策略代码', dataIndex: 'strategyCode', key: 'code', width: 130, render: v => <Tag color="geekblue">{v}</Tag> },
    ];

    const metricCols = [
      { title: '年化收益', dataIndex: 'annualReturn', fmt: fmtPct, good: v => v > 0 },
      { title: '总收益', dataIndex: 'totalReturn', fmt: fmtPct, good: v => v > 0 },
      { title: '超额收益', dataIndex: 'excessReturn', fmt: fmtPct, good: v => v > 0 },
      { title: '最大回撤', dataIndex: 'maxDrawdown', fmt: fmtPct, good: v => v > -0.2 },
      { title: 'Sharpe', dataIndex: 'sharpeRatio', fmt: v => fmt(v), good: v => v > 1 },
      { title: 'Sortino', dataIndex: 'sortinoRatio', fmt: v => fmt(v), good: v => v > 1 },
      { title: 'Calmar', dataIndex: 'calmarRatio', fmt: v => fmt(v), good: v => v > 0.5 },
      { title: '波动率', dataIndex: 'volatility', fmt: fmtPct, good: v => v < 0.2 },
      { title: '信息比率', dataIndex: 'informationRatio', fmt: v => fmt(v), good: v => v > 0 },
      { title: 'Alpha', dataIndex: 'alpha', fmt: v => fmt(v), good: v => v > 0 },
      { title: 'Beta', dataIndex: 'beta', fmt: v => fmt(v) },
      { title: '胜率', dataIndex: 'winRate', fmt: fmtPct, good: v => v > 0.5 },
      { title: '盈亏比', dataIndex: 'profitLossRatio', fmt: v => fmt(v), good: v => v > 1 },
    ].map(m => ({
      title: m.title,
      dataIndex: m.dataIndex,
      key: m.dataIndex,
      width: 90,
      render: (v) => {
        const isGood = m.good && v != null ? m.good(+v) : null;
        return <span style={isGood === null ? {} : { color: isGood ? '#cf1322' : '#3f8600', fontWeight: 600 }}>{m.fmt(v)}</span>;
      },
    }));

    return [...base, ...metricCols];
  }, [compareResult]);

  return (
    <div>
      <div className="page-header">
        <Title level={4} style={{ margin: 0 }}>多策略对比</Title>
        <Space>
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/backtests')}>返回列表</Button>
        </Space>
      </div>

      {/* 选择器 */}
      <Card style={{ marginBottom: 16 }} size="small">
        <Space wrap style={{ width: '100%' }}>
          <Text>选择回测任务（已完成）：</Text>
          <Select
            mode="multiple"
            loading={loadingTasks}
            style={{ minWidth: 480 }}
            placeholder="选择 2~8 个已完成的回测任务"
            value={selectedIds}
            onChange={setSelectedIds}
            maxTagCount={4}
            optionFilterProp="label"
            options={tasks.map(t => ({
              value: t.id,
              label: `#${t.id} ${t.taskName || t.strategyCode}（${t.startDate} ~ ${t.endDate}）`,
            }))}
          />
          <Button
            type="primary"
            icon={<LineChartOutlined />}
            disabled={selectedIds.length < 2}
            onClick={handleCompare}
            loading={loading}
          >
            开始对比
          </Button>
        </Space>
        {selectedIds.length < 2 && (
          <div style={{ marginTop: 8 }}>
            <Text type="secondary">提示：请选择至少 2 个回测任务进行对比</Text>
          </div>
        )}
      </Card>

      {error && <Alert type="error" message={error} style={{ marginBottom: 16 }} showIcon closable onClose={() => setError(null)} />}

      <Spin spinning={loading} tip="正在加载对比数据...">
        {compareResult ? (
          <>
            {/* 净值曲线对比 */}
            <Card title="净值曲线对比" style={{ marginBottom: 16 }}>
              <MultiCurveChart curves={compareResult.curves} />
            </Card>

            <Row gutter={16} style={{ marginBottom: 16 }}>
              {/* 雷达图 */}
              <Col span={10}>
                <Card title="综合能力雷达图" style={{ height: 400 }}>
                  <RadarChart metrics={compareResult.metrics} />
                </Card>
              </Col>

              {/* 关键指标快速对比 */}
              <Col span={14}>
                <Card title="核心指标速览" style={{ height: 400, overflow: 'auto' }}>
                  {compareResult.metrics.map((m, idx) => (
                    <div key={m.taskId} style={{
                      padding: '8px 12px', marginBottom: 8,
                      background: idx === 0 ? '#fff7e6' : '#fafafa',
                      border: `1px solid ${idx === 0 ? '#ffd591' : '#f0f0f0'}`,
                      borderRadius: 6, display: 'flex', alignItems: 'center', gap: 16,
                    }}>
                      <span style={{ display: 'inline-block', width: 10, height: 10, borderRadius: '50%', background: m.color, flexShrink: 0 }} />
                      <div style={{ flex: 1, minWidth: 0 }}>
                        <div style={{ fontWeight: 600, fontSize: 13, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                          {idx === 0 && <TrophyOutlined style={{ color: '#faad14', marginRight: 4 }} />}
                          {m.taskName}
                        </div>
                      </div>
                      <div style={{ display: 'flex', gap: 20, flexShrink: 0 }}>
                        <div style={{ textAlign: 'center' }}>
                          <div style={{ fontSize: 11, color: '#888' }}>年化收益</div>
                          <div style={signStyle(m.annualReturn)}>{fmtPct(m.annualReturn)}</div>
                        </div>
                        <div style={{ textAlign: 'center' }}>
                          <div style={{ fontSize: 11, color: '#888' }}>最大回撤</div>
                          <div style={{ color: '#3f8600', fontWeight: 600 }}>{fmtPct(m.maxDrawdown)}</div>
                        </div>
                        <div style={{ textAlign: 'center' }}>
                          <div style={{ fontSize: 11, color: '#888' }}>Sharpe</div>
                          <div style={signStyle(m.sharpeRatio)}>{fmt(m.sharpeRatio)}</div>
                        </div>
                        <div style={{ textAlign: 'center' }}>
                          <div style={{ fontSize: 11, color: '#888' }}>胜率</div>
                          <div style={{ fontWeight: 600 }}>{fmtPct(m.winRate)}</div>
                        </div>
                      </div>
                    </div>
                  ))}
                </Card>
              </Col>
            </Row>

            {/* 详细指标对比表 */}
            <Card title={`详细指标对比（共 ${compareResult.count} 个策略）`}>
              <Table
                dataSource={compareResult.metrics}
                columns={columns}
                rowKey="taskId"
                size="small"
                scroll={{ x: 1600 }}
                pagination={false}
                rowClassName={(r) => r.rank === 1 ? 'best-strategy-row' : ''}
              />
            </Card>
          </>
        ) : (
          !loading && (
            <Card>
              <Empty description="选择已完成的回测任务，点击「开始对比」查看结果" />
            </Card>
          )
        )}
      </Spin>

      <style>{`.best-strategy-row { background: #fffbe6; }`}</style>
    </div>
  );
}
