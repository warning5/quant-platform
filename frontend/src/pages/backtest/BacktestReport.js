import React, { useEffect, useState, useCallback, useMemo, useRef } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Card, Row, Col, Statistic, Typography, Button, Space, Spin, Tabs, Skeleton,
  Tag, Table, Alert, Badge, Tooltip as AntTooltip, Popconfirm, message, Progress,
} from 'antd';
import {
  ArrowLeftOutlined, ReloadOutlined,
  RiseOutlined, FallOutlined, BarChartOutlined,
  PieChartOutlined, LineChartOutlined,
  SwapOutlined, FundOutlined, ExperimentOutlined,
  QuestionCircleOutlined, RedoOutlined,
} from '@ant-design/icons';
import ReactECharts from 'echarts-for-react';
import { backtestApi } from '../../api';
import MonteCarloPanel from './MonteCarloPanel';
import AttributionHub from './AttributionHub';

const { Title, Text } = Typography;

// ─── 工具函数 ────────────────────────────────────────────────────────────────
const fmtPct = (v, d = 2) => v != null ? `${(+v * 100).toFixed(d)}%` : '-';
const fmt = (v, d = 4) => v != null ? (+v).toFixed(d) : '-';
const fmtNum = (v) => v != null ? (+v).toLocaleString() : '-';
const signCol = (v) => +v > 0 ? '#cf1322' : +v < 0 ? '#3f8600' : '#262626';

/** 从 JSON 字符串安全解析，失败返回 fallback */
const safeJson = (str, fallback = []) => {
  try { return JSON.parse(str || '[]'); }
  catch { return fallback; }
};

// ─── 空状态占位 ───────────────────────────────────────────────────────────────
function Empty({ text }) {
  return (
    <div style={{ textAlign: 'center', padding: '48px 0', color: '#bfbfbf' }}>
      <BarChartOutlined style={{ fontSize: 36 }} />
      <div style={{ marginTop: 8 }}>{text}</div>
    </div>
  );
}

// ─── 顶部指标栏 ──────────────────────────────────────────────────────────────
function MetricBar({ report }) {
  if (!report) return null;

  const metrics = [
    { label: '策略收益', value: report.totalReturn, fmt: fmtPct, good: v => v > 0,
      tip: '回测期间策略总盈亏比例，即（期末净值 − 初始资金）/ 初始资金' },
    { label: '基准收益', value: report.benchmarkReturn, fmt: fmtPct, good: v => v > 0,
      tip: '同期基准指数的总收益率，用于对比策略是否跑赢大盘' },
    { label: '年化收益', value: report.annualReturn, fmt: fmtPct, good: v => v > 0,
      tip: '将总收益按复利折算为年化收益率：(1 + 总收益)^(252/交易日数) − 1' },
    { label: 'Alpha', value: report.alpha, fmt: v => fmt(v, 2), good: v => v > 0,
      tip: '策略独立于大盘的超额收益能力。>0 表示策略有真正的选股/择时价值' },
    { label: 'Beta', value: report.beta, fmt: v => fmt(v, 2),
      tip: '策略相对基准的系统性风险暴露。β=1 跟随大盘，>1 更激进，<1 更保守' },
    { label: 'Sharpe', value: report.sharpeRatio, fmt: v => fmt(v, 2), good: v => v > 1,
      tip: '每承担1单位总风险获得的超额收益（无风险利率3%）。>1 优秀，>0.5 尚可' },
    { label: 'Sortino', value: report.sortinoRatio, fmt: v => fmt(v, 2), good: v => v > 1,
      tip: '类似夏普比率，但只惩罚下行亏损波动。对有尖尾亏损的策略更有区分度' },
    { label: 'Calmar', value: report.calmarRatio, fmt: v => fmt(v, 2), good: v => v > 0.5,
      tip: '年化收益 / 最大回撤，衡量每承受1单位最大回撤风险获得的年化收益' },
    { label: '波动率', value: report.volatility, fmt: fmtPct, good: v => v < 0.2,
      tip: '日收益率标准差的年化值（×√252），衡量收益的不确定性。越低越稳健' },
    { label: '最大回撤', value: report.maxDrawdown, fmt: fmtPct, good: v => v > -0.2,
      tip: '从历史最高点到最低点的最大跌幅，是最重要的风险指标之一' },
    { label: '下行风险', value: report.downsideRisk, fmt: fmtPct, good: v => v < 0.15,
      tip: '只考虑亏损日的波动率（Semi-Deviation）。越低说明亏起来越可控' },
    { label: '信息比率', value: report.informationRatio, fmt: v => fmt(v, 2), good: v => v > 0,
      tip: '年化超额收益 / 跟踪误差，衡量相对基准主动管理的能力。>0.5 不错' },
    { label: '跟踪误差', value: report.trackingError, fmt: fmtPct, good: v => v < 0.1,
      tip: '策略与基准偏离程度（超额收益标准差的年化值）。越低越贴近基准' },
    { label: '胜率', value: report.winRate, fmt: fmtPct, good: v => v > 0.5,
      tip: '盈利交易次数占总配对交易次数的比例' },
  ];

  return (
    <Card size="small" style={{ marginBottom: 16, background: '#fafafa' }}>
      <Row gutter={8}>
        {metrics.map((m, i) => {
          const val = m.value;
          const isGood = m.good ? (val != null && m.good(+val)) : val != null;
          return (
            <Col key={i} style={{ textAlign: 'center', padding: '4px 8px', borderRight: i < metrics.length - 1 ? '1px solid #e8e8e8' : 'none' }}>
              <AntTooltip title={m.tip} placement="top">
                <div style={{ fontSize: 12, color: '#888', cursor: 'default' }}>
                  {m.label}
                  {m.tip && <span style={{ marginLeft: 2, color: '#bbb', fontSize: 10 }}>ⓘ</span>}
                </div>
              </AntTooltip>
              <div style={{ 
                fontSize: 14, 
                fontWeight: 600, 
                color: isGood ? '#52c41a' : '#262626',
              }}>
                {m.fmt(val)}
              </div>
            </Col>
          );
        })}
      </Row>
    </Card>
  );
}

// ─── 主图：收益对比 + 日收益柱状图 + 回撤（类似图片的专业界面）────────────────────
function MainChart({ equityCurveJson, benchmarkCurveJson, drawdownSeriesJson, maxDrawdown, realizedCurveJson }) {
  const stratData    = useMemo(() => safeJson(equityCurveJson),    [equityCurveJson]);
  const bmData       = useMemo(() => safeJson(benchmarkCurveJson), [benchmarkCurveJson]);
  const ddData       = useMemo(() => safeJson(drawdownSeriesJson), [drawdownSeriesJson]);
  const realizedData = useMemo(() => safeJson(realizedCurveJson),  [realizedCurveJson]);

  if (!stratData.length) return <Empty text="暂无净值曲线数据" />;

  const dates = stratData.map(d => d.date);
  
  // 策略收益（累计收益率）
  const stratReturns = stratData.map(d => +((+d.value - 1) * 100).toFixed(4));
  
  // 基准收益
  const bmMap = {};
  bmData.forEach(d => { bmMap[d.date] = +d.value; });
  const bmReturns = stratData.map(d => {
    const bmVal = bmMap[d.date];
    return bmVal != null ? +((bmVal - 1) * 100).toFixed(4) : null;
  });
  
  // 超额收益
  const excessReturns = stratData.map((d, i) => {
    const bmVal = bmMap[d.date];
    return bmVal != null ? +((+d.value - bmVal) * 100).toFixed(4) : null;
  });

  // 日收益（用于下方柱状图）
  const dailyReturns = stratData.map((d, i) => {
    if (i === 0) return 0;
    const prev = +stratData[i - 1].value;
    const curr = +d.value;
    return prev > 0 ? +(((curr - prev) / prev) * 100).toFixed(4) : 0;
  });

  // 回撤数据
  const drawdowns = ddData.length ? ddData.map(d => +(+d.drawdown * 100).toFixed(4)) : 
    stratData.map(d => +(+d.drawdown * 100).toFixed(4));

  // 已实现收益率（前向填充到 dates）
  const realizedMap = {};
  realizedData.forEach(d => { realizedMap[d.date] = +d.value; });
  const realizedReturns = stratData.map(d => {
    const rv = realizedMap[d.date];
    return rv != null ? +((rv - 1) * 100).toFixed(4) : null;
  });
  const hasRealized = realizedData.length > 0;

  const option = {
    backgroundColor: 'transparent',
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'cross', lineStyle: { type: 'dashed' } },
      formatter: params => {
        const date = params[0].name;
        let html = `<div style="font-weight:600;margin-bottom:4px">${date}</div>`;
        params.forEach(p => {
          if (p.seriesName === '日收益' || p.seriesName === '回撤') return;
          const col = p.seriesName === '策略收益' ? '#cf1322'
            : p.seriesName === '基准收益' ? '#8c8c8c'
            : p.seriesName === '已实现收益' ? '#13c2c2'
            : '#fa8c16';
          const sign = p.value >= 0 ? '+' : '';
          html += `<div><span style="color:${col}">●</span> ${p.seriesName}：<b>${sign}${(+p.value).toFixed(2)}%</b></div>`;
        });
        return html;
      },
    },
    legend: {
      data: hasRealized
        ? ['策略收益', '基准收益', '超额收益', '已实现收益']
        : ['策略收益', '基准收益', '超额收益'],
      top: 4,
      textStyle: { color: '#666' },
      selected: {
        '策略收益': true,
        '基准收益': true,
        '超额收益': true,
        '已实现收益': true,
      },
    },
    grid: [
      { left: 56, right: 16, top: 40, bottom: 200 },  // 主图
      { left: 56, right: 16, top: '68%', height: 60 },  // 日收益
      { left: 56, right: 16, top: '85%', height: 50 },  // 回撤
    ],
    xAxis: [
      {
        type: 'category',
        data: dates,
        axisLabel: { show: false },
        boundaryGap: false,
        gridIndex: 0,
      },
      {
        type: 'category',
        data: dates,
        axisLabel: { rotate: 30, fontSize: 10, color: '#888' },
        boundaryGap: false,
        gridIndex: 1,
      },
      {
        type: 'category',
        data: dates,
        axisLabel: { show: false },
        boundaryGap: false,
        gridIndex: 2,
      },
    ],
    yAxis: [
      {
        type: 'value',
        axisLabel: { formatter: v => `${v > 0 ? '+' : ''}${v.toFixed(0)}%`, color: '#888' },
        splitLine: { lineStyle: { color: '#f0f0f0', type: 'dashed' } },
        gridIndex: 0,
      },
      {
        type: 'value',
        axisLabel: { formatter: v => `${v > 0 ? '+' : ''}${v.toFixed(1)}%`, color: '#888' },
        splitLine: { show: false },
        gridIndex: 1,
      },
      {
        type: 'value',
        axisLabel: { formatter: v => `${v.toFixed(0)}%`, color: '#888' },
        splitLine: { show: false },
        gridIndex: 2,
        inverse: true,
      },
    ],
    dataZoom: [
      { type: 'inside', xAxisIndex: [0, 1, 2], throttle: 50 },
      { type: 'slider', xAxisIndex: [0, 1, 2], height: 20, bottom: 8 },
    ],
    series: [
      // 主图 - 策略收益
      {
        name: '策略收益',
        type: 'line',
        data: stratReturns,
        smooth: true,
        lineStyle: { color: '#cf1322', width: 1.5 },
        itemStyle: { color: '#cf1322' },
        symbol: 'none',
        xAxisIndex: 0,
        yAxisIndex: 0,
      },
      // 主图 - 基准收益
      {
        name: '基准收益',
        type: 'line',
        data: bmReturns,
        smooth: true,
        lineStyle: { color: '#8c8c8c', width: 1.5 },
        itemStyle: { color: '#8c8c8c' },
        symbol: 'none',
        xAxisIndex: 0,
        yAxisIndex: 0,
      },
      // 主图 - 超额收益
      {
        name: '超额收益',
        type: 'line',
        data: excessReturns,
        smooth: true,
        lineStyle: { color: '#fa8c16', width: 1.5 },
        itemStyle: { color: '#fa8c16' },
        symbol: 'none',
        xAxisIndex: 0,
        yAxisIndex: 0,
      },
      // 主图 - 已实现收益（虚线，仅有已平仓收益）
      ...(hasRealized ? [{
        name: '已实现收益',
        type: 'line',
        data: realizedReturns,
        smooth: false,
        lineStyle: { color: '#13c2c2', width: 1.5, type: 'dashed' },
        itemStyle: { color: '#13c2c2' },
        symbol: 'none',
        xAxisIndex: 0,
        yAxisIndex: 0,
      }] : []),
      // 子图1 - 日收益柱状图
      {
        name: '日收益',
        type: 'bar',
        data: dailyReturns,
        itemStyle: {
          color: params => params.value >= 0 ? '#cf1322' : '#3f8600',
        },
        barWidth: '60%',
        xAxisIndex: 1,
        yAxisIndex: 1,
      },
      // 子图2 - 回撤
      {
        name: '回撤',
        type: 'line',
        data: drawdowns,
        smooth: true,
        lineStyle: { color: '#722ed1', width: 1 },
        areaStyle: { color: 'rgba(114,46,209,0.1)' },
        symbol: 'none',
        xAxisIndex: 2,
        yAxisIndex: 2,
      },
    ],
  };

  return <ReactECharts option={option} style={{ height: 500 }} />;
}

// ─── 月度收益热力图 ───────────────────────────────────────────────────────────
function MonthlyHeatmap({ monthlyReturnsJson }) {
  const data = useMemo(() => safeJson(monthlyReturnsJson), [monthlyReturnsJson]);
  if (!data.length) return <Empty text="暂无月度收益数据" />;

  // 解析年月
  const parsed = data.map(d => {
    const [year, month] = d.month.split('-');
    return { year: +year, month: +month, value: +d.return };
  });

  const years = [...new Set(parsed.map(d => d.year))].sort();
  const months = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12];

  const heatmapData = [];
  years.forEach((year, yIndex) => {
    months.forEach((month, mIndex) => {
      const item = parsed.find(d => d.year === year && d.month === month);
      heatmapData.push([mIndex, yIndex, item ? +(item.value * 100).toFixed(2) : null]);
    });
  });

  const option = {
    tooltip: {
      position: 'top',
      formatter: params => {
        const year = years[params.value[1]];
        const month = params.value[0] + 1;
        const val = params.value[2];
        return `${year}-${String(month).padStart(2, '0')}<br/>月收益: ${val != null ? val.toFixed(2) : '-'}%`;
      },
    },
    grid: { left: 60, right: 20, top: 20, bottom: 30 },
    xAxis: {
      type: 'category',
      data: ['1月', '2月', '3月', '4月', '5月', '6月', '7月', '8月', '9月', '10月', '11月', '12月'],
      splitArea: { show: true },
    },
    yAxis: {
      type: 'category',
      data: years.map(String),
      splitArea: { show: true },
    },
    visualMap: {
      min: -10,
      max: 10,
      calculable: true,
      orient: 'horizontal',
      left: 'center',
      bottom: 0,
      inRange: {
        color: ['#3f8600', '#fff', '#cf1322'],
      },
    },
    series: [{
      name: '月收益',
      type: 'heatmap',
      data: heatmapData,
      label: { show: true, formatter: params => params.value[2] != null ? params.value[2].toFixed(1) : '' },
    }],
  };

  return <ReactECharts option={option} style={{ height: years.length * 40 + 80 }} />;
}

// ─── 交易记录表格 ─────────────────────────────────────────────────────────────
function TradeTable({ tradeLogJson }) {
  const trades = useMemo(() => safeJson(tradeLogJson), [tradeLogJson]);
  
  const columns = [
    { title: '日期', dataIndex: 'date', key: 'date', width: 110 },
    { title: '代码', dataIndex: 'symbol', key: 'symbol', width: 100 },
    { title: '名称', dataIndex: 'name', key: 'name', width: 120, ellipsis: true },
    { 
      title: '操作', 
      dataIndex: 'action', 
      key: 'action', 
      width: 80,
      render: v => {
        if (v === 'BUY') return <Tag color="red">买入</Tag>;
        if (v === 'SELL') return <Tag color="green">卖出</Tag>;
        if (v === 'DIVIDEND') return <Tag color="blue">分红</Tag>;
        return v;
      },
    },
    { title: '价格', dataIndex: 'price', key: 'price', width: 90, render: v => (+v).toFixed(2) },
    { title: '数量', dataIndex: 'amount', key: 'amount', width: 90, render: v => (+v).toFixed(0) },
    { title: '金额', dataIndex: 'total', key: 'total', width: 110, render: v => `¥${(+v).toLocaleString()}` },
  ];

  return (
    <Table
      dataSource={trades}
      columns={columns}
      rowKey={(r, i) => `${r.date}-${r.symbol}-${i}`}
      pagination={{ defaultPageSize: 20, size: 'small', showSizeChanger: true, pageSizeOptions: ['10', '20', '50', '100'] }}
      size="small"
      scroll={{ x: 700, y: 400 }}
    />
  );
}


// ─── 持仓过程点位图 ───────────────────────────────────────────────────────────
function PositionProcessChart({ equityCurveJson, tradeLogJson, positionHistoryJson }) {
  const curve = useMemo(() => safeJson(equityCurveJson), [equityCurveJson]);
  const trades = useMemo(() => safeJson(tradeLogJson), [tradeLogJson]);
  const history = useMemo(() => safeJson(positionHistoryJson), [positionHistoryJson]);

  if (!curve.length) return <Empty text="暂无净值曲线数据" />;

  const dates = curve.map(d => d.date);
  // 净值收益百分比（相对初始值1的涨跌）
  const curveReturns = curve.map(d => +((+d.value - 1) * 100).toFixed(4));

  // 找出每个交易日的持仓数量（当日有持仓的股票数）
  const posCountMap = {};
  history.forEach(h => { posCountMap[h.date] = Object.keys(h.positions || {}).length; });

  // 买卖点事件（仅 BUY/SELL，排除 DIVIDEND）
  const buyEvents = trades.filter(t => t.action === 'BUY');
  const sellEvents = trades.filter(t => t.action === 'SELL');

  // 找出买卖点对应的净值收益
  const dateToReturn = {};
  curve.forEach(d => { dateToReturn[d.date] = +((+d.value - 1) * 100).toFixed(4); });

  // 持仓数量序列（只取有数据的日期）
  const posCountSeries = dates.map(dt => posCountMap[dt] ?? null);

  // ECharts 点位标注
  const buyMarkers = buyEvents.map(t => ({
    date: t.date,
    value: dateToReturn[t.date],
    symbol: t.symbol,
    name: t.name,
    price: t.price,
    amount: t.amount,
    action: 'BUY',
  })).filter(m => m.value != null);

  const sellMarkers = sellEvents.map(t => ({
    date: t.date,
    value: dateToReturn[t.date],
    symbol: t.symbol,
    name: t.name,
    price: t.price,
    amount: t.amount,
    action: 'SELL',
  })).filter(m => m.value != null);

  const option = {
    backgroundColor: 'transparent',
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'cross', lineStyle: { type: 'dashed' } },
      formatter: params => {
        const date = params[0]?.name;
        if (!date) return '';
        let html = `<div style="font-weight:600;margin-bottom:4px">${date}</div>`;
        params.forEach(p => {
          if (p.seriesName === '持仓数量') return;
          const sign = p.value >= 0 ? '+' : '';
          html += `<div><span style="color:${p.color}">●</span> ${p.seriesName}：<b>${sign}${typeof p.value === 'number' ? p.value.toFixed(2) : p.value}%</b></div>`;
        });
        // 附加买卖点信息
        const buys = buyMarkers.filter(m => m.date === date);
        const sells = sellMarkers.filter(m => m.date === date);
        buys.forEach(b => {
          html += `<div style="color:#cf1322;font-size:12px;margin-top:2px">▲ 买入 ${b.symbol} ${b.name}  ${b.price}元 × ${b.amount}股</div>`;
        });
        sells.forEach(s => {
          html += `<div style="color:#52c41a;font-size:12px;margin-top:2px">▼ 卖出 ${s.symbol} ${s.name}  ${s.price}元 × ${s.amount}股</div>`;
        });
        return html;
      },
    },
    legend: {
      data: ['策略净值(%)', '持仓数量'],
      top: 4,
      textStyle: { color: '#666' },
    },
    grid: [
      { left: 56, right: 16, top: 40, bottom: 100 },
      { left: 56, right: 16, top: '75%', height: 50 },
    ],
    xAxis: [
      {
        type: 'category', data: dates,
        axisLabel: { show: false }, boundaryGap: false, gridIndex: 0,
      },
      {
        type: 'category', data: dates,
        axisLabel: { rotate: 30, fontSize: 10, color: '#888' }, boundaryGap: false, gridIndex: 1,
      },
    ],
    yAxis: [
      {
        type: 'value', name: '收益率(%)',
        axisLabel: { formatter: v => `${v > 0 ? '+' : ''}${v.toFixed(0)}%`, color: '#888' },
        splitLine: { lineStyle: { color: '#f0f0f0', type: 'dashed' } }, gridIndex: 0,
      },
      {
        type: 'value', name: '持仓数',
        axisLabel: { formatter: v => String(Math.round(v)), color: '#888' },
        splitLine: { show: false }, gridIndex: 1,
      },
    ],
    dataZoom: [
      { type: 'inside', xAxisIndex: [0, 1], throttle: 50 },
      { type: 'slider', xAxisIndex: [0, 1], height: 20, bottom: 8 },
    ],
    series: [
      // 主图 - 净值收益曲线
      {
        name: '策略净值(%)',
        type: 'line', data: curveReturns,
        smooth: true, lineStyle: { color: '#cf1322', width: 2 },
        symbol: 'none', xAxisIndex: 0, yAxisIndex: 0,
      },
      // 买卖点 - 买入（红色三角）
      ...buyMarkers.map((m, idx) => ({
        name: '买入-' + idx, type: 'scatter',
        data: [[m.date, m.value]],
        xAxisIndex: 0, yAxisIndex: 0,
        symbol: 'triangle', symbolSize: 10,
        itemStyle: { color: '#cf1322', borderColor: '#fff', borderWidth: 1 },
        tooltip: { show: false },
      })),
      // 买卖点 - 卖出（绿色三角）
      ...sellMarkers.map((m, idx) => ({
        name: '卖出-' + idx, type: 'scatter',
        data: [[m.date, m.value]],
        xAxisIndex: 0, yAxisIndex: 0,
        symbol: 'triangle', symbolSize: 10,
        itemStyle: { color: '#52c41a', borderColor: '#fff', borderWidth: 1 },
        tooltip: { show: false },
      })),
      // 子图 - 持仓数量
      {
        name: '持仓数量', type: 'line', data: posCountSeries,
        smooth: true, lineStyle: { color: '#1677ff', width: 1.5 },
        symbol: 'none', xAxisIndex: 1, yAxisIndex: 1,
        areaStyle: { color: 'rgba(22,119,255,0.06)' },
      },
    ],
  };

  return <ReactECharts option={option} style={{ height: 420 }} />;
}


// ─── Brinson 归因分析 ────────────────────────────────────────────────────────

/** 生成 Brinson 归因指标的结构化 tooltip */
function buildBrinsonTip(label, val, excessVal, residualVal, ratioVal) {
  const v = val != null ? +val : 0;
  const fmtV = (x) => x != null ? `${(x * 100).toFixed(2)}%` : '-';
  const fmtRatio = (x) => x != null ? `${(x * 100).toFixed(1)}%` : '-';

  const tipStyle = { lineHeight: 1.9, fontSize: 13 };

  switch (label) {
    case '配置效应':
      return <div style={tipStyle}>
        <div style={{ fontWeight: 700, marginBottom: 6, fontSize: 14 }}>配置效应（Allocation Effect）</div>
        <div style={{ marginBottom: 6 }}>
          <b>公式：</b>(Wp − Wb) × (Rb − R<span style={{ fontSize: 10 }}>基准</span>)
        </div>
        <div style={{ marginBottom: 6 }}>
          <b>含义：</b>行业权重偏离基准带来的收益。正值 = 超配了涨幅强于全市场均值的行业（行业择时能力强）；负值 = 超配了弱势行业。
        </div>
        <div style={{ marginBottom: 6, borderTop: '1px solid #f0f0f0', paddingTop: 4 }}>
          <b>阈值参考：</b>
        </div>
        <div>· ≥ +5%&nbsp;&nbsp;&nbsp;行业择时能力突出</div>
        <div>· ≥ +1%&nbsp;&nbsp;&nbsp;有一定行业择时贡献</div>
        <div>· ≈ 0&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;行业配置接近基准</div>
        <div>· ≤ −1%&nbsp;&nbsp;&nbsp;行业配置拖累收益</div>
        <div>· ≤ −5%&nbsp;&nbsp;&nbsp;行业配置严重失当</div>
        <div style={{ marginTop: 6, borderTop: '1px solid #f0f0f0', paddingTop: 4, color: v > 0 ? '#cf1322' : '#3f8600' }}>
          <b>解读：</b>当前配置效应={fmtV(v)}，{v > 0.01 ? '行业择时贡献显著，超配的行业普遍强于市场均值' : v > 0 ? '行业择时有小幅正贡献' : v > -0.01 ? '行业权重接近全市场等权分布，择时效果中性' : '行业择时拖累收益，超配的行业跑输了市场均值'}。
        </div>
      </div>;

    case '选股效应':
      return <div style={tipStyle}>
        <div style={{ fontWeight: 700, marginBottom: 6, fontSize: 14 }}>选股效应（Selection Effect）</div>
        <div style={{ marginBottom: 6 }}>
          <b>公式：</b>Wb × (Rp − Rb)
        </div>
        <div style={{ marginBottom: 6 }}>
          <b>含义：</b>在基准行业权重下，行业内部选股带来的超额收益。正值 = 选出的个股跑赢了所在行业的平均水平；负值 = 个股选择不如行业均值。
        </div>
        <div style={{ marginBottom: 6, borderTop: '1px solid #f0f0f0', paddingTop: 4 }}>
          <b>阈值参考：</b>
        </div>
        <div>· ≥ +5%&nbsp;&nbsp;&nbsp;选股能力突出</div>
        <div>· ≥ +1%&nbsp;&nbsp;&nbsp;有一定选股贡献</div>
        <div>· ≈ 0&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;选股能力中性</div>
        <div>· ≤ −1%&nbsp;&nbsp;&nbsp;选股拖累收益</div>
        <div>· ≤ −5%&nbsp;&nbsp;&nbsp;选股严重跑输行业均值</div>
        <div style={{ marginTop: 6, borderTop: '1px solid #f0f0f0', paddingTop: 4, color: v > 0 ? '#cf1322' : '#3f8600' }}>
          <b>解读：</b>当前选股效应={fmtV(v)}，{v > 0.01 ? '个股选择能力突出，多数持仓跑赢了行业均值' : v > 0 ? '选股有小幅正贡献' : v > -0.01 ? '选股能力中性，持仓收益与行业均值基本持平' : '选股拖累收益，持仓个股普遍跑输行业均值'}。{excessVal != null && v > 0 && +excessVal > 0 ? '超额收益中选股是主要来源，因子选股逻辑有效。' : ''}
        </div>
      </div>;

    case '交互效应':
      return <div style={tipStyle}>
        <div style={{ fontWeight: 700, marginBottom: 6, fontSize: 14 }}>交互效应（Interaction Effect）</div>
        <div style={{ marginBottom: 6 }}>
          <b>公式：</b>(Wp − Wb) × (Rp − Rb)
        </div>
        <div style={{ marginBottom: 6 }}>
          <b>含义：</b>行业配置和选股的协同效应。正值 = 既超配了强势行业、又在其中选到了更强的个股（双重正确）；负值 = 配置和选股方向冲突或双双错误。通常数值较小。
        </div>
        <div style={{ marginBottom: 6, borderTop: '1px solid #f0f0f0', paddingTop: 4 }}>
          <b>阈值参考：</b>
        </div>
        <div>· 绝对值 &lt; 1%&nbsp;&nbsp;交互效应微弱（常见）</div>
        <div>· ≥ +1%&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;配置+选股协同正收益</div>
        <div>· ≤ −1%&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;配置+选股相互抵消</div>
        <div style={{ marginTop: 6, borderTop: '1px solid #f0f0f0', paddingTop: 4, color: v > 0 ? '#cf1322' : Math.abs(v) < 0.005 ? '#888' : '#3f8600' }}>
          <b>解读：</b>当前交互效应={fmtV(v)}，{Math.abs(v) < 0.005 ? '交互项接近零，配置效应和选股效应基本独立运行' : v > 0 ? '配置与选股形成正向协同，选到的强势行业中的个股同样强势' : '配置与选股方向冲突或双双失误，互相抵消收益'}。
        </div>
      </div>;

    case '超额收益':
      return <div style={tipStyle}>
        <div style={{ fontWeight: 700, marginBottom: 6, fontSize: 14 }}>超额收益（Excess Return）</div>
        <div style={{ marginBottom: 6 }}>
          <b>公式：</b>策略收益 − 基准收益 ≈ 配置效应 + 选股效应 + 交互效应
        </div>
        <div style={{ marginBottom: 6 }}>
          <b>含义：</b>策略相对于基准（全市场等权行业基准）的总超额。正值 = 策略跑赢市场；负值 = 跑输市场。它是 Brinson 模型要解释的总量。
        </div>
        <div style={{ marginBottom: 6, borderTop: '1px solid #f0f0f0', paddingTop: 4 }}>
          <b>阈值参考：</b>
        </div>
        <div>· ≥ +10%&nbsp;&nbsp;&nbsp;显著跑赢基准</div>
        <div>· ≥ +3%&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;适度跑赢</div>
        <div>· ≈ 0&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;与基准持平</div>
        <div>· ≤ −3%&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;跑输基准</div>
        <div>· ≤ −10%&nbsp;&nbsp;&nbsp;显著跑输基准</div>
        <div style={{ marginTop: 6, borderTop: '1px solid #f0f0f0', paddingTop: 4, color: v > 0 ? '#cf1322' : '#3f8600' }}>
          <b>解读：</b>当前超额收益={fmtV(v)}，{v > 0.03 ? '策略显著跑赢基准，因子选股有效' : v > 0 ? '策略有小幅超额收益' : v > -0.03 ? '策略与基准表现接近' : '策略跑输基准，因子信号可能失效或市场风格切换'}。{ratioVal != null && Math.abs(ratioVal) < 0.05 ? '注意：超额收益绝对值接近零（≈0），归因结论可靠性降低，三效应和高低均为噪声主导。' : ''}
        </div>
      </div>;

    case '残差': {
      const r = residualVal != null ? +residualVal : 0;
      const absR = Math.abs(r);
      const absExcess = excessVal != null ? Math.abs(+excessVal) : 0;
      const relResidual = absExcess > 1e-8 ? absR / absExcess : 0;
      return <div style={tipStyle}>
        <div style={{ fontWeight: 700, marginBottom: 6, fontSize: 14 }}>多期残差（Multi-period Residual）</div>
        <div style={{ marginBottom: 6 }}>
          <b>含义：</b>多期 Brinson 分解的固有局限。单期恒等式严格成立（配置+选股+交互=超额收益），但多期各期效应算术累加≠总超额收益（收益是几何复合的，效应是算术累加的），差额即为残差。
        </div>
        <div style={{ marginBottom: 6, padding: '6px 8px', background: '#e6f7ff', borderRadius: 6, border: '1px solid #91d5ff' }}>
          <b>当前残差：</b>{r > 0 ? '+' : ''}{(r * 100).toFixed(2)}%（相对超额收益占比 {(relResidual * 100).toFixed(1)}%）。残差绝对值越小，归因结论越可靠。
        </div>
        <div style={{ marginBottom: 6, borderTop: '1px solid #f0f0f0', paddingTop: 4 }}>
          <b>残差来源：</b>多期几何/算术不匹配（根因）、交易成本、非行业因子暴露（市值/动量等）、数据精度损失。
        </div>
        <div style={{ marginTop: 6, color: relResidual < 0.05 ? '#52c41a' : relResidual < 0.15 ? '#d48806' : '#cf1322' }}>
          <b>评估：</b>{relResidual < 0.05 ? '残差极小（<5%），归因结论高度可靠' : relResidual < 0.15 ? '残差较小（5%~15%），归因结论可接受' : '残差较大（≥15%），除行业外还有其他重要收益驱动因素，归因参考价值有限'}。
        </div>
      </div>;
    }

    case '解释力':
      const r = ratioVal != null ? +ratioVal : 0;
      const isNegativeExcess = excessVal != null && +excessVal < 0;
      return <div style={tipStyle}>
        <div style={{ fontWeight: 700, marginBottom: 6, fontSize: 14 }}>解释力（Explanation Ratio）</div>
        <div style={{ marginBottom: 6 }}>
          <b>公式：</b>1 − |残差| / |超额收益|（= 三效应之和 / 超额收益，等价）
        </div>
        <div style={{ marginBottom: 6 }}>
          <b>含义：</b>多期 Brinson 模型能解释的超额收益占比。由于多期几何复合效应，三效应算术累加与总超额收益之间存在固有残差。解释力越高，说明行业配置+选股对收益来源的解释越充分。
        </div>
        <div style={{ marginBottom: 6, borderTop: '1px solid #f0f0f0', paddingTop: 4 }}>
          <b>阈值参考：</b>
        </div>
        <div>· ≥ 80%&nbsp;&nbsp;&nbsp;模型解释力极强</div>
        <div>· ≥ 50%&nbsp;&nbsp;&nbsp;可接受</div>
        <div>· ≥ 30%&nbsp;&nbsp;&nbsp;解释力偏弱</div>
        <div>· &lt; 30%&nbsp;&nbsp;&nbsp;大量因素未被解释</div>
        {isNegativeExcess ? <div style={{ color: '#8c8c8c', marginTop: 4 }}>· 注意：超额收益为负时，解释力解读需适当调整</div> : null}
        <div style={{ marginTop: 6, borderTop: '1px solid #f0f0f0', paddingTop: 4, color: r >= 0.8 ? '#52c41a' : r >= 0.5 ? '#262626' : '#cf1322' }}>
          <b>解读：</b>当前解释力={fmtRatio(ratioVal)}，{r >= 0.8 ? '行业配置+选股几乎完全解释了超额收益，归因结论高度可靠' : r >= 0.5 ? '大部分超额收益可被三因素解释，同时存在少量其他因子贡献' : r >= 0.3 ? '解释力偏弱，除行业外还有其他重要收益驱动因素（如风格/市值/交易成本）' : '超额收益主要来自三因素以外的渠道，行业归因参考价值有限'}。{isNegativeExcess ? '超额为负时，应根据三效应的正负号分析亏损来源。' : ''}
        </div>
      </div>;

    case '估算交易成本':
      return <div style={tipStyle}>
        <div style={{ fontWeight: 700, marginBottom: 6, fontSize: 14 }}>估算交易成本</div>
        <div style={{ marginBottom: 6 }}>
          <b>公式：</b>累计单向换手率 × 单边费率 0.1%
        </div>
        <div style={{ marginBottom: 6 }}>
          <b>含义：</b>回测中每次调仓产生的佣金+印花税估算值。换手率从各期持仓权重变化推算：单向换手率 = Σ|本期权重 − 上期权重| / 2。默认费率 0.1%（含佣金 0.03% + 印花税 0.05% 的近似），可根据实际费率调整。
        </div>
        <div style={{ marginBottom: 6, padding: '6px 8px', background: '#fff2f0', borderRadius: 6, border: '1px solid #ffccc7' }}>
          <b>注意：</b>交易成本是超额收益的直接扣减项。如果换手率过高，交易成本会显著侵蚀超额收益。
        </div>
        <div style={{ color: v > 0 ? '#cf1322' : '#8c8c8c', marginTop: 4 }}>
          <b>本期成本：</b>{v != null ? `${(v * 100).toFixed(2)}%` : '-'}
        </div>
      </div>;

    case '净超额收益':
      return <div style={tipStyle}>
        <div style={{ fontWeight: 700, marginBottom: 6, fontSize: 14 }}>净超额收益</div>
        <div style={{ marginBottom: 6 }}>
          <b>公式：</b>归因超额收益 − 估算交易成本
        </div>
        <div style={{ marginBottom: 6 }}>
          <b>含义：</b>扣除交易成本后的实际超额收益。这是策略真实"落袋"的超额部分。如果净超额收益为负，说明策略超额被交易成本完全吃掉，需要降低换手率。
        </div>
        <div style={{ marginBottom: 6, borderTop: '1px solid #f0f0f0', paddingTop: 4 }}>
          <b>阈值参考：</b>
        </div>
        <div>· > 归因超额 × 80%&nbsp;&nbsp;交易成本较低，策略执行效率高</div>
        <div>· > 0&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;扣除成本后仍有正超额</div>
        <div>· ≤ 0&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;交易成本吃掉全部超额，需优化</div>
        <div style={{ marginTop: 6, borderTop: '1px solid #f0f0f0', paddingTop: 4, color: v > 0 ? '#52c41a' : '#cf1322' }}>
          <b>解读：</b>净超额={fmtV(v)}，{v > 0 ? '扣除交易成本后策略仍保持正超额，执行效率良好' : '交易成本已完全侵蚀归因超额，建议关注换手率控制或降低交易频率'}。
        </div>
      </div>;

    default:
      return null;
  }
}

/** 归因汇总卡片 */
function AttributionSummary({ summary }) {
  if (!summary) return null;
  const items = [
    { label: '配置效应', value: summary.totalAllocationEffect },
    { label: '选股效应', value: summary.totalSelectionEffect },
    { label: '交互效应', value: summary.totalInteractionEffect },
    { label: '超额收益', value: summary.totalExcessReturn },
    { label: '解释力', value: summary.explanationRatio, fmt: v => v != null ? `${(+v * 100).toFixed(1)}%` : '-' },
    { label: '残差', value: summary.residual },
    // 交易成本与净超额
    { label: '估算交易成本', value: summary.estimatedTransactionCost, color: '#fa541c', fmt: v => v != null ? `-${(v * 100).toFixed(2)}%` : '-', divider: true },
    { label: '净超额收益', value: summary.netExcessReturn, divider: true },
  ];

  return (
    <Card size="small" style={{ marginBottom: 16, background: '#fafafa' }}>
      <Row gutter={[8, 4]}>
        {items.map((m, i) => {
          const val = m.value;
          const color = m.color || (val != null ? signCol(val) : '#262626');
          const display = m.fmt ? m.fmt(val) : fmtPct(val);
          const tipContent = buildBrinsonTip(
            m.label, val,
            summary.totalExcessReturn,
            summary.residual,
            summary.explanationRatio
          );
          return (
            <Col key={i} style={{ textAlign: 'center', padding: '4px 8px', borderLeft: m.divider ? '2px solid #e8e8e8' : 'none', borderRight: i < items.length - 1 ? '1px solid #e8e8e8' : 'none' }}>
              <div style={{ fontSize: 12, color: '#888', cursor: 'default', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 2 }}>
                <span>{m.label}</span>
                <AntTooltip styles={{ root: { maxWidth: 460 } }} title={tipContent} placement="top">
                  <QuestionCircleOutlined style={{ color: '#8c8c8c', fontSize: 11 }} />
                </AntTooltip>
              </div>
              <div style={{ fontSize: 14, fontWeight: 600, color }}>{display}</div>
            </Col>
          );
        })}
      </Row>
    </Card>
  );
}

/** 累计归因曲线图 */
function AttributionCumulativeChart({ cumulativeChart }) {
  if (!cumulativeChart || cumulativeChart.length === 0) return <Empty text="暂无归因数据" />;

  const periods = cumulativeChart.map(d => d.period?.split(' ~ ')[1] || d.startDate);
  const option = {
    backgroundColor: 'transparent',
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'cross', lineStyle: { type: 'dashed' } },
      formatter: params => {
        let html = `<div style="font-weight:600;margin-bottom:4px">${params[0].name}</div>`;
        params.forEach(p => {
          const color = p.seriesName === '配置效应' ? '#1677ff'
            : p.seriesName === '选股效应' ? '#52c41a'
            : p.seriesName === '交互效应' ? '#fa8c16'
            : '#cf1322';
          const sign = p.value >= 0 ? '+' : '';
          html += `<div><span style="color:${color}">●</span> ${p.seriesName}：<b>${sign}${(+p.value).toFixed(2)}%</b></div>`;
        });
        return html;
      },
    },
    legend: {
      data: ['配置效应', '选股效应', '交互效应', '超额收益'],
      top: 4,
      textStyle: { color: '#666' },
    },
    grid: { left: 56, right: 16, top: 40, bottom: 40 },
    xAxis: {
      type: 'category',
      data: periods,
      axisLabel: { rotate: 30, fontSize: 10, color: '#888' },
      boundaryGap: false,
    },
    yAxis: {
      type: 'value',
      axisLabel: { formatter: v => `${v > 0 ? '+' : ''}${v.toFixed(1)}%`, color: '#888' },
      splitLine: { lineStyle: { color: '#f0f0f0', type: 'dashed' } },
    },
    dataZoom: [
      { type: 'inside', throttle: 50 },
      { type: 'slider', height: 20, bottom: 8 },
    ],
    series: [
      {
        name: '配置效应', type: 'line', data: cumulativeChart.map(d => +(d.cumAllocation * 100).toFixed(4)),
        smooth: true, lineStyle: { color: '#1677ff', width: 1.5 }, symbol: 'none',
        areaStyle: { color: 'rgba(22,119,255,0.08)' },
      },
      {
        name: '选股效应', type: 'line', data: cumulativeChart.map(d => +(d.cumSelection * 100).toFixed(4)),
        smooth: true, lineStyle: { color: '#52c41a', width: 1.5 }, symbol: 'none',
        areaStyle: { color: 'rgba(82,196,26,0.08)' },
      },
      {
        name: '交互效应', type: 'line', data: cumulativeChart.map(d => +(d.cumInteraction * 100).toFixed(4)),
        smooth: true, lineStyle: { color: '#fa8c16', width: 1.5 }, symbol: 'none',
      },
      {
        name: '超额收益', type: 'line', data: cumulativeChart.map(d => +(d.cumExcess * 100).toFixed(4)),
        smooth: true, lineStyle: { color: '#cf1322', width: 2 }, symbol: 'none',
        lineType: 'dashed',
      },
    ],
  };
  return <ReactECharts option={option} style={{ height: 320 }} />;
}

/** 当期归因瀑布图 */
function AttributionWaterfall({ periods }) {
  if (!periods || periods.length === 0) return null;

  // ── 智能 Y 轴范围：按数据分布裁剪极端异常值，避免个别极端值撑爆 Y 轴 ──
  const allAbs = [];
  periods.forEach(p => {
    allAbs.push(Math.abs(p.allocationEffect * 100));
    allAbs.push(Math.abs(p.selectionEffect * 100));
    allAbs.push(Math.abs(p.interactionEffect * 100));
    allAbs.push(Math.abs(p.excessReturn * 100));
  });
  allAbs.sort((a, b) => a - b);
  const p90 = allAbs[Math.floor(allAbs.length * 0.92)];
  // 基础区间 ±max(p92*1.3, 3%)，不超过 ±600% 兜底
  const rawRange = Math.max(p90 * 1.3, 3);
  const yMax = Math.min(rawRange, 600);
  const yMin = -yMax;

  // 统计被裁剪的期间
  let clippedCount = 0;
  let worstPeriod = '';
  let worstVal = 0;
  periods.forEach(p => {
    const vals = [
      Math.abs(p.allocationEffect * 100),
      Math.abs(p.selectionEffect * 100),
      Math.abs(p.interactionEffect * 100),
      Math.abs(p.excessReturn * 100),
    ];
    const maxV = Math.max(...vals);
    if (maxV > yMax + 0.5) {
      clippedCount++;
      if (maxV > worstVal) { worstVal = maxV; worstPeriod = p.period; }
    }
  });
  const hasClipped = clippedCount > 0;

  // ── X 轴标签自动间隔：超过 30 根柱子时每隔一根显示，超过 60 根时隔两根 ──
  const rawLabels = periods.map(p => p.period?.split(' ~ ')[1] || p.startDate);
  const labelInterval = rawLabels.length > 60 ? 2 : rawLabels.length > 30 ? 1 : 0;

  // 累计归因（用于特殊高亮裁剪期）
  const clippedPeriodIndices = new Set();
  periods.forEach((p, i) => {
    const vals = [
      Math.abs(p.allocationEffect * 100),
      Math.abs(p.selectionEffect * 100),
      Math.abs(p.interactionEffect * 100),
      Math.abs(p.excessReturn * 100),
    ];
    if (Math.max(...vals) > yMax + 0.5) clippedPeriodIndices.add(i);
  });

  // 裁剪期标记线
  const markLines = [];
  if (hasClipped) {
    markLines.push(
      { yAxis: yMax, lineStyle: { color: '#ff4d4f', type: 'dashed', width: 1 }, label: { show: false } },
      { yAxis: yMin, lineStyle: { color: '#ff4d4f', type: 'dashed', width: 1 }, label: { show: false } },
    );
  }

  const option = {
    backgroundColor: 'transparent',
    tooltip: {
      trigger: 'axis',
      formatter: params => {
        const idx = params[0].dataIndex;
        const p = periods[idx];
        const isClippedPeriod = clippedPeriodIndices.has(idx);
        const clipWarn = isClippedPeriod
          ? `<div style="color:#ff4d4f;font-size:11px;margin-top:4px">⚠ 此期数值过大，已超出图表可视范围</div>`
          : '';
        return `<div style="font-weight:600">${p.period}</div>
          <div>配置效应: ${fmtPct(p.allocationEffect)}</div>
          <div>选股效应: ${fmtPct(p.selectionEffect)}</div>
          <div>交互效应: ${fmtPct(p.interactionEffect)}</div>
          <div>超额收益: ${fmtPct(p.excessReturn)}</div>${clipWarn}`;
      },
    },
    legend: {
      data: ['配置效应', '选股效应', '交互效应', '超额收益'],
      top: 4,
      textStyle: { color: '#666' },
    },
    grid: { left: 56, right: 16, top: 40, bottom: hasClipped ? 72 : 56 },
    xAxis: {
      type: 'category',
      data: rawLabels,
      axisLabel: {
        rotate: 45,
        fontSize: 10,
        color: '#888',
        interval: labelInterval,
      },
    },
    yAxis: {
      type: 'value',
      min: yMin,
      max: yMax,
      axisLabel: { formatter: v => `${v.toFixed(1)}%`, color: '#888' },
      splitLine: { lineStyle: { color: '#f0f0f0', type: 'dashed' } },
    },
    dataZoom: [
      { type: 'inside', throttle: 50 },
      { type: 'slider', height: 20, bottom: 8 },
    ],
    series: [
      {
        name: '配置效应', type: 'bar', stack: 'attribution',
        data: periods.map((p, i) => {
          const raw = +(p.allocationEffect * 100).toFixed(4);
          if (clippedPeriodIndices.has(i) && Math.abs(raw) > yMax + 0.5) {
            return { value: raw, itemStyle: { color: '#1677ff', borderColor: '#ff4d4f', borderWidth: 2, borderType: 'dashed' } };
          }
          return raw;
        }),
        barMaxWidth: 30,
        markLine: hasClipped ? { silent: true, symbol: 'none', data: markLines } : undefined,
      },
      {
        name: '选股效应', type: 'bar', stack: 'attribution',
        data: periods.map((p, i) => {
          const raw = +(p.selectionEffect * 100).toFixed(4);
          if (clippedPeriodIndices.has(i) && Math.abs(raw) > yMax + 0.5) {
            return { value: raw, itemStyle: { color: '#52c41a', borderColor: '#ff4d4f', borderWidth: 2, borderType: 'dashed' } };
          }
          return raw;
        }),
        barMaxWidth: 30,
      },
      {
        name: '交互效应', type: 'bar', stack: 'attribution',
        data: periods.map((p, i) => {
          const raw = +(p.interactionEffect * 100).toFixed(4);
          if (clippedPeriodIndices.has(i) && Math.abs(raw) > yMax + 0.5) {
            return { value: raw, itemStyle: { color: '#fa8c16', borderColor: '#ff4d4f', borderWidth: 2, borderType: 'dashed' } };
          }
          return raw;
        }),
        barMaxWidth: 30,
      },
      {
        name: '超额收益', type: 'bar',
        data: periods.map((p, i) => {
          const raw = +(p.excessReturn * 100).toFixed(4);
          if (clippedPeriodIndices.has(i) && Math.abs(raw) > yMax + 0.5) {
            return {
              value: raw,
              itemStyle: {
                color: raw >= 0 ? '#cf1322' : '#3f8600',
                borderColor: '#ff4d4f',
                borderWidth: 2,
                borderType: 'dashed',
              },
            };
          }
          return raw;
        }),
        barMaxWidth: 30,
      },
    ],
  };

  return (
    <div>
      <ReactECharts option={option} style={{ height: 280 }} />
      {hasClipped && (
        <div style={{ fontSize: 11, color: '#ff4d4f', marginTop: 4, textAlign: 'center' }}>
          ⚠ Y 轴已自动裁剪至 ±{yMax.toFixed(0)}% 以增强可读性（P92={p90.toFixed(1)}%）。
          最极端值位于「{worstPeriod}」（{worstVal.toFixed(1)}%），悬停查看精确数值。
          也可拖拽底部滑块放大局部区间。
        </div>
      )}
    </div>
  );
}

/** 行业归因汇总表 */
function IndustryAttributionTable({ industrySummary, height }) {
  // rightHeight - Card头(38) - body内边距(16) - 表格头(40) = 滚动区
  const scrollY = height ? Math.max(height - 94, 200) : 420;

  if (!industrySummary || industrySummary.length === 0) return <Empty text="暂无行业归因数据" />;

  const columns = [
    { title: '行业', dataIndex: 'industry', key: 'industry', width: 100, fixed: 'left' },
    {
      title: '配置效应', dataIndex: 'totalAllocation', key: 'alloc', width: 100,
      render: v => <span style={{ color: signCol(v) }}>{fmtPct(v)}</span>,
      sorter: (a, b) => a.totalAllocation - b.totalAllocation,
    },
    {
      title: '选股效应', dataIndex: 'totalSelection', key: 'select', width: 100,
      render: v => <span style={{ color: signCol(v) }}>{fmtPct(v)}</span>,
      sorter: (a, b) => a.totalSelection - b.totalSelection,
    },
    {
      title: '交互效应', dataIndex: 'totalInteraction', key: 'interact', width: 100,
      render: v => <span style={{ color: signCol(v) }}>{fmtPct(v)}</span>,
    },
    {
      title: '总贡献', dataIndex: 'totalContribution', key: 'total', width: 100,
      render: v => <span style={{ color: signCol(v), fontWeight: 600 }}>{fmtPct(v)}</span>,
      sorter: (a, b) => a.totalContribution - b.totalContribution,
      defaultSortOrder: 'descend',
    },
    {
      title: '贡献分布', dataIndex: 'totalContribution', key: 'bar', width: 180,
      render: (v) => {
        const maxAbs = Math.max(...industrySummary.map(d => Math.abs(d.totalContribution)), 1e-8);
        const pct = Math.abs(v) / maxAbs * 100;
        const color = v >= 0 ? '#52c41a' : '#ff4d4f';
        return (
          <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
            <div style={{ flex: 1, height: 8, background: '#f0f0f0', borderRadius: 4, overflow: 'hidden' }}>
              <div style={{ width: `${pct}%`, height: '100%', background: color, borderRadius: 4, float: v >= 0 ? 'left' : 'right' }} />
            </div>
          </div>
        );
      },
    },
  ];

  return (
    <div style={{ height: '100%' }}>
      <Table
        dataSource={industrySummary}
        columns={columns}
        rowKey="industry"
        pagination={false}
        size="small"
        scroll={{ x: 680, y: scrollY }}
      />
    </div>
  );
}

/** 图形分析结论 */
function BrinsonConclusion({ summary, industrySummary, periods }) {
  if (!summary) return null;

  const fmtPctV = (v) => v != null ? `${(v * 100).toFixed(2)}%` : '-';
  const fmtSigned = (v) => v >= 0 ? `+${(v * 100).toFixed(2)}` : (v * 100).toFixed(2);
  const alloc = summary.totalAllocationEffect != null ? +summary.totalAllocationEffect : 0;
  const select = summary.totalSelectionEffect != null ? +summary.totalSelectionEffect : 0;
  const interaction = summary.totalInteractionEffect != null ? +summary.totalInteractionEffect : 0;
  const excess = summary.totalExcessReturn != null ? +summary.totalExcessReturn : 0;
  const netExcess = summary.netExcessReturn != null ? +summary.netExcessReturn : 0;
  const cost = summary.estimatedTransactionCost != null ? +summary.estimatedTransactionCost : 0;
  const residual = summary.residual != null ? +summary.residual : 0;
  const inds = Array.isArray(industrySummary) ? industrySummary : [];
  const perds = Array.isArray(periods) ? periods : [];
  const hasIndData = inds.length > 0;

  // ── 行业级归因数据 ──
  // 排序用 avgContribution（平均每期贡献），更直观；降级用 total/期数
  function getAvg(x, field) {
    var avgField = 'avg' + field.charAt(0).toUpperCase() + field.slice(1);
    if (x[avgField] != null) return +x[avgField];
    var totalField = 'total' + field.charAt(0).toUpperCase() + field.slice(1);
    return perds.length > 0 ? (+x[totalField] || 0) / perds.length : (+x[totalField] || 0);
  }
  function indAvg(x) { return getAvg(x, 'Contribution'); }
  const topContributors = [...inds].sort((a, b) => indAvg(b) - indAvg(a)).slice(0, 3);
  const worstContributors = [...inds].sort((a, b) => indAvg(a) - indAvg(b)).slice(0, 3);
  const worstSelectors = [...inds].sort((a, b) => getAvg(a, 'Selection') - getAvg(b, 'Selection')).slice(0, 3);
  const worstAllocators = [...inds].sort((a, b) => getAvg(a, 'Allocation') - getAvg(b, 'Allocation')).slice(0, 3);

  // 构建行业建议文本（展示平均每期贡献，更直观）
  var indSummaryText = '';
  if (hasIndData) {
    var parts = [];
    // 优先用 avgContribution，没有则降级用 totalContribution/期数
    function getAvg(x) {
      if (x.avgContribution != null) return +x.avgContribution;
      return perds.length > 0 ? (+x.totalContribution || 0) / perds.length : (+x.totalContribution || 0);
    }
    function fmtAvg(x) { return fmtPctV(getAvg(x)); }
    if (topContributors.length > 0 && getAvg(topContributors[0]) > 0.0001) {
      parts.push('[贡献] ' + topContributors.map(function(x) {
        return x.industry + '(+' + fmtAvg(x) + '/期)';
      }).join(' / '));
    }
    if (worstContributors.length > 0 && getAvg(worstContributors[0]) < -0.0001) {
      parts.push('[拖累] ' + worstContributors.map(function(x) {
        return x.industry + '(' + fmtAvg(x) + '/期)';
      }).join(' / '));
    }
    indSummaryText = parts.join('  ');
  }

  // 因子配置
  let factors = [];
  let rebalanceFreq = '?';
  let weightMode = '?';
  try {
    const cfgJson = summary?.screenConfigJson || '{}';
    const cfg = typeof cfgJson === 'string' ? JSON.parse(cfgJson) : cfgJson;
    factors = Array.isArray(cfg.factors) ? cfg.factors : [];
    rebalanceFreq = summary?.rebalanceFreq || '?';
    weightMode = summary?.weightMode || '?';
  } catch (e) {}
  var hasFactors = factors.length > 0;

  // 效应排序
  const effects = [
    { name: '配置效应', value: alloc },
    { name: '选股效应', value: select },
    { name: '交互效应', value: interaction },
  ];
  effects.sort((a, b) => Math.abs(b.value) - Math.abs(a.value));
  const dominant = effects[0];
  const posCount = effects.filter(function(e) { return e.value > 0.005; }).length;
  const negCount = effects.filter(function(e) { return e.value < -0.005; }).length;
  const allNeg = negCount === 3 && effects.every(function(e) { return e.value < -0.01; });

  // 成本分析
  const costErosion = Math.abs(excess) > 0.001 ? Math.abs(cost / excess) : 0;

  // === 数据驱动结论生成（先给总体判断，再解释各项） ===
  // 总体判断：好 / 不好 / 不确定
  var overall = '';
  var overallColor = '';
  if (Math.abs(excess) < 0.005) {
    overall = '不确定（超额接近零）';
    overallColor = '#8c8c8c';
  } else if (excess > 0) {
    if (posCount >= 2 && netExcess > 0) { overall = '好（有效）'; overallColor = '#52c41a'; }
    else if (posCount >= 1) { overall = '一般（单项驱动）'; overallColor = '#d48806'; }
    else { overall = '不好（仅靠残差）'; overallColor = '#cf1322'; }
  } else {
    if (allNeg) { overall = '不好（全线亏损）'; overallColor = '#cf1322'; }
    else if (negCount >= 2) { overall = '不好（多效应拖累）'; overallColor = '#cf1322'; }
    else { overall = '一般（部分效应为负）'; overallColor = '#d48806'; }
  }

  // === 各效应详细分解（含计算推导） ===
  var avgAlloc = perds.length > 0 ? alloc / perds.length : alloc;
  var avgSelect = perds.length > 0 ? select / perds.length : select;
  var avgInter = perds.length > 0 ? interaction / perds.length : interaction;

  // 从 inds 中提取各效应的 top/bottom 行业
  function getTopInds(field, n) {
    return [...inds].sort(function(a, b) { return getAvg(b, field) - getAvg(a, field); }).slice(0, n);
  }
  function getBottomInds(field, n) {
    return [...inds].sort(function(a, b) { return getAvg(a, field) - getAvg(b, field); }).slice(0, n);
  }

  // 读取行业中间值（后端新增字段）
  function wpct(v) { return v != null ? (+v*100).toFixed(1) : '-'; }
  function wtPct(x)  { return wpct(x.avgPortfolioWeight); }
  function wbPct(x)  { return wpct(x.avgBenchmarkWeight); }
  function wdPct(x)  { return wpct(x.avgWeightDiff); }
  function rePct(x)  { return wpct(x.avgBenchmarkReturnExcess); }
  function srPct(x)  { return wpct(x.avgSelectionReturn); }

  // === 配置效应卡片 ===
  // 公式: allocation = Σ (wp - wb) × (rb - benchmarkReturn)
  // 负来源: 超配(>wb)但行业弱(<bmRet) + 低配(<wb)但行业强(>bmRet)
  function buildAllocCard() {
    var abs = Math.abs(alloc);
    var title = '配置效应：' + fmtSigned(alloc) + '（平均每期 ' + fmtSigned(avgAlloc) + '）';
    if (abs < 0.005) return { title: title, verdict: '贡献极小，可忽略', posFactors: [], negFactors: [],
      formula: 'A = Σ [ (wp−wb) × (rb−R) ]',
      formulaLegend: 'wp=策略行业权重, wb=基准行业权重, rb=基准行业收益, R=基准总收益' };

    var posFactors = []; // 正贡献: 赌对方向
    var negFactors = []; // 负贡献: 赌错方向

    // 取 |allocation| 最大的 6 个行业做展示
    var sorted = [...inds].sort(function(a,b) { return Math.abs(getAvg(b,'Allocation')) - Math.abs(getAvg(a,'Allocation')); });
    sorted.slice(0, 6).forEach(function(x) {
      var avgA = getAvg(x, 'Allocation');
      var wd = x.avgWeightDiff != null ? +x.avgWeightDiff : 0;
      var re = x.avgBenchmarkReturnExcess != null ? +x.avgBenchmarkReturnExcess : 0;
      var item = {
        name: x.industry,
        effect: avgA,
        weightDiff: wd,
        retExcess: re,
        // 根据符号解释原因
        reason: wd > 0 && re > 0 ? '超配' + wdPct(x) + '%且行业跑赢' + rePct(x) + '%' :
                wd < 0 && re < 0 ? '低配' + wdPct(x) + '%且行业跑输' + rePct(x) + '%' :
                wd > 0 ? '超配' + wdPct(x) + '%但行业跑输' + rePct(x) + '%' :
                '低配' + wdPct(x) + '%但行业跑赢' + rePct(x) + '%'
      };
      if (avgA > 0) posFactors.push(item);
      else negFactors.push(item);
    });

    var verdict = alloc > 0
      ? '行业配置方向正确：多数行业超配(=跑赢)&低配(=跑输)，赌对了方向'
      : '行业配置方向错误：多数行业超配(=跑输)&低配(=跑赢)，赌错了方向';
    return { title: title, verdict: verdict, posFactors: posFactors, negFactors: negFactors,
      formula: 'A = Σ [ (wp−wb) × (rb−R) ]',
      formulaLegend: 'wp=策略行业权重, wb=基准行业权重, rb=基准行业收益, R=基准总收益' };
  }

  // === 选股效应卡片 ===
  function buildSelectCard() {
    var abs = Math.abs(select);
    var title = '选股效应：' + fmtSigned(select) + '（平均每期 ' + fmtSigned(avgSelect) + '）';
    if (abs < 0.005) return { title: title, verdict: '贡献极小，可忽略', posFactors: [], negFactors: [],
      formula: 'S = Σ [ wb × (rp−rb) ]',
      formulaLegend: 'wb=基准行业权重, rp=策略行业收益, rb=基准行业收益' };

    var posFactors = [];
    var negFactors = [];
    var sorted = [...inds].sort(function(a,b) { return Math.abs(getAvg(b,'Selection')) - Math.abs(getAvg(a,'Selection')); });
    sorted.slice(0, 6).forEach(function(x) {
      var avgS = getAvg(x, 'Selection');
      var sr = x.avgSelectionReturn != null ? +x.avgSelectionReturn : 0;
      var wb = x.avgBenchmarkWeight != null ? +x.avgBenchmarkWeight : 0;
      var item = {
        name: x.industry,
        effect: avgS,
        selReturn: sr,
        benchWeight: wb,
        reason: sr > 0 ? '选股跑赢行业' + srPct(x) + '%' : '选股跑输行业' + srPct(x) + '%'
      };
      if (avgS > 0) posFactors.push(item);
      else negFactors.push(item);
    });

    var verdict = select > 0
      ? '选股能力有效：多数行业选股跑赢行业均值'
      : '选股跑输行业均值：所选个股整体弱于行业平均水平';
    return { title: title, verdict: verdict, posFactors: posFactors, negFactors: negFactors,
      formula: 'S = Σ [ wb × (rp−rb) ]',
      formulaLegend: 'wb=基准行业权重, rp=策略行业收益, rb=基准行业收益' };
  }

  // === 交互效应卡片 ===
  function buildInteractCard() {
    var abs = Math.abs(interaction);
    var title = '交互效应：' + fmtSigned(interaction) + '（平均每期 ' + fmtSigned(avgInter) + '）';
    if (abs < 0.005) return { title: title, verdict: '贡献极小，可忽略', posFactors: [], negFactors: [],
      formula: 'I = Σ [ (wp−wb) × (rp−rb) ]',
      formulaLegend: 'wp=策略权重, wb=基准权重, rp=策略行业收益, rb=基准行业收益' };

    var posFactors = [];
    var negFactors = [];
    var sorted = [...inds].sort(function(a,b) { return Math.abs(getAvg(b,'Interaction')) - Math.abs(getAvg(a,'Interaction')); });
    sorted.slice(0, 6).forEach(function(x) {
      var avgI = getAvg(x, 'Interaction');
      var wd = x.avgWeightDiff != null ? +x.avgWeightDiff : 0;
      var sr = x.avgSelectionReturn != null ? +x.avgSelectionReturn : 0;
      var item = {
        name: x.industry,
        effect: avgI,
        weightDiff: wd,
        selReturn: sr,
        reason: wd>0 && sr>0 ? '超配' + wdPct(x) + '% × 选股赢' + srPct(x) + '% = 正向协同' :
                wd<0 && sr<0 ? '低配' + wdPct(x) + '% × 选股输' + srPct(x) + '% = 正向协同' :
                wd>0 ? '超配' + wdPct(x) + '% × 选股输' + srPct(x) + '% = 互相抵消' :
                '低配' + wdPct(x) + '% × 选股赢' + srPct(x) + '% = 互相抵消'
      };
      if (avgI > 0) posFactors.push(item);
      else negFactors.push(item);
    });

    // 关键洞察：
    // 1) 交互为什么这么大？
    // 2) 为什么同一行业在配置效应和交互效应中符号不同？——因为公式不同！
    //    配置:A=(wp-wb)×(rb-R)  交互:I=(wp-wb)×(rp-rb)
    //    同一行业 (wp-wb) 相同，但乘数不同：(rb-R)是用"行业相对于市场"衡量，(rp-rb)是用"策略在该行业选股 vs 行业均值"衡量
    var insight = '';
    if (abs > 0.02) {
      var bigBoth = sorted.filter(function(x) {
        return Math.abs(+x.avgWeightDiff) > 0.02 && Math.abs(+x.avgSelectionReturn) > 0.02;
      });
      insight = '交互效应绝对值较大（>' + fmtSigned(interaction > 0 ? 0.02 : -0.02) + '），根因是有 ' + bigBoth.length + ' 个行业「权重偏离大 + 选股偏离大」。';
      if (interaction > 0) {
        insight += '\n正值说明：权重方向和选股方向一致 — 超配的行业选股也准，低配的行业选股也差 → 乘积效应放大了收益。';
      } else {
        insight += '\n负值说明：权重方向与选股方向相反 — 超配了选不好的行业，或低配了选得好的行业 → 两者互相抵消。';
      }
      insight += '\n\n💡 同一行业在配置效应和交互效应中正负可能不同，这是正常的！因为两个效应使用不同的收益率口径：';
      insight += '\n   - 配置效应用 rb−R（行业相对市场的收益率）判断方向对不对';
      insight += '\n   - 交互效应用 rp−rb（策略选股相对行业的收益率）判断选股好不好';
      insight += '\n   例如：某个行业市场表现好（rb>R，配置正），但策略在该行业的选股跑输行业均值（rp<rb），则交互效应在该行业为负。';
    }

    var verdict = (interaction > 0 ? '正向协同：' : '互相抵消：');
    if (abs > 0.1) verdict += '权重差与选股收益差方向高度一致，乘积效应被显著放大';
    else if (interaction > 0) verdict += '多数行业权重与选股同向，有小幅正向加成';
    else verdict += '多数行业权重与选股反向，产生对冲';

    return { title: title, verdict: verdict, posFactors: posFactors, negFactors: negFactors,
      formula: 'I = Σ [ (wp−wb) × (rp−rb) ]',
      formulaLegend: 'wp=策略权重, wb=基准权重, rp=策略行业收益, rb=基准行业收益 — 注意与配置效应A的变量不同！I用的是rp而非rb',
      insight: insight };
  }

  var effectCards = [
    buildAllocCard(),
    buildSelectCard(),
    buildInteractCard()
  ];

  // === 行动建议：三层结构 ===
  // 第一层：可立即操作（基于策略编辑页可控参数）
  // 第二层：验证步骤（如何确认修复有效）
  // 第三层：需新增的能力（当前策略不支持，列为后续增强）

  var actionables = [];   // 第一层
  var verifications = []; // 第二层
  var missingCaps = [];   // 第三层

  // 辅助：创建操作项
  function makeAction(label, body) { return { label: label, body: body }; }
  function makeVerify(label, body) { return { label: label, body: body }; }
  function makeMissing(label, body) { return { label: label, body: body }; }

  // 因子名称列表
  var factorNames = hasFactors
    ? factors.map(function(f) { return f.name || f.factorCode || ''; }).filter(Boolean)
    : [];

  // ── 第一层：可立即操作 ──

  // 1.1 选股拖累 → 因子调整
  if (hasIndData && worstSelectors.length > 0) {
    var wSelNames = worstSelectors
      .filter(function(x) { return (+x.totalSelection || 0) < -0.002; })
      .map(function(x) { return x.industry + '(' + fmtPctV(x.totalSelection) + ')'; });
    if (wSelNames.length > 0) {
      var selBody = '以下行业选股跑输行业平均：' + wSelNames.join('、') + '。';
      if (hasFactors && factorNames.length > 0) {
        selBody += '\n• 当前因子：' + factorNames.slice(0, 5).join('、') + (factorNames.length > 5 ? ' 等' + factorNames.length + '个' : '');
        selBody += '\n• 操作：去 Alpha 分析面板，按行业筛选 — 查看这些行业内各因子的 IC 均值';
        selBody += '\n• 移除 IC 均值为负的因子（在策略编辑页删除），或加大 IC 为正的因子权重';
        selBody += '\n• 如所有因子 IC 均为负 → 该行业内因子全面失效 → 考虑在策略编辑页添加质量因子（FIN_ROE_TTM、PE_TTM）作为替代驱动';
      } else {
        selBody += '\n• 当前策略未使用因子选股 → 在策略编辑页为这些行业添加质量/价值因子（如 FIN_ROE_TTM、PE_TTM）';
      }
      actionables.push(makeAction('因子调整 — 修复选股拖累', selBody));
      verifications.push(makeVerify('选股修复验证',
        '移除负IC因子后重跑回测 → 预期这些行业选股效应从 ' + fmtPctV(worstSelectors[0].totalSelection) + ' 回升到 -1% 以内。如果仍为负，说明行业内因子全面失效 → 缩小 Top N（候选池收缩）来减少对这些行业的暴露。'
      ));
    }
  }

  // 1.2 配置拖累 → weightMode / Top N
  if (hasIndData && worstAllocators.length > 0) {
    var wAllocNames = worstAllocators
      .filter(function(x) { return (+x.totalAllocation || 0) < -0.002; })
      .map(function(x) { return x.industry + '(' + fmtPctV(x.totalAllocation) + ')'; });
    if (wAllocNames.length > 0) {
      var allocBody = '以下行业配置方向亏损：' + wAllocNames.join('、') + '。';
      if (weightMode !== 'EQUAL' && weightMode !== '?') {
        allocBody += '\n• 当前 weightMode=' + weightMode + '（主动行业偏离）';
        allocBody += '\n• 操作：在策略编辑页将 weightMode 从 ' + weightMode + ' 改为 EQUAL';
        allocBody += '\n• 等权模式下行业分配完全由股票得分决定，没有主动择时风险';
      } else {
        allocBody += '\n• 当前已是等权模式，配置拖累来自 Top N 选股后这些行业的股票自然偏多';
        allocBody += '\n• 操作：缩小候选池宽度（如 Top20% → Top10%），减少被迫暴露的行业数量';
      }
      actionables.push(makeAction('权重模式 — 修复配置拖累', allocBody));
      verifications.push(makeVerify('配置修复验证',
        weightMode !== 'EQUAL' && weightMode !== '?'
          ? '改为 EQUAL 后重跑回测 → 预期配置效应绝对值 < 2%（接近归零）。'
          : '缩小 Top N 后重跑 → 预期配置效应绝对值从 ' + fmtPctV(worstAllocators[0].totalAllocation) + ' 显著缩小。'
      ));
    }
  }

  // 1.3 成本侵蚀
  if (costErosion > 0.3 && Math.abs(excess) > 0.005) {
    var suggestedFreq = rebalanceFreq === 'WEEKLY' ? 'BIWEEKLY' : rebalanceFreq === 'BIWEEKLY' ? 'MONTHLY' : '更低的频率';
    var costBody = '交易成本 ' + fmtPctV(cost) + ' 侵蚀了超额收益的 ' + (costErosion * 100).toFixed(0) + '%。';
    costBody += '\n• 当前调仓频率：' + rebalanceFreq;
    costBody += '\n• 操作：在策略编辑页将调仓频率从 ' + rebalanceFreq + ' 改为 ' + suggestedFreq;
    costBody += '\n• 预期：成本约降低 40%，净超额直接回升';
    actionables.push(makeAction('调仓频率 — 降低交易成本', costBody));
    verifications.push(makeVerify('成本优化验证',
      '改为 ' + suggestedFreq + ' 后重跑 → 预期净超额从 ' + fmtSigned(netExcess) + ' 回升到正值区间。如果因子信号本身弱，降频可能同时降低换手和信号噪声。'
    ));
  }

  // 1.4 全额亏损 → 归零重来
  if (allNeg) {
    actionables.push(makeAction('全线亏损 — 从零重建策略',
      '三效应全部负贡献，说明当前因子组合在回测期内全面失效。建议：\n'
      + '• 在策略编辑页新建一个最小化策略：仅保留1-2个基本面因子（如 FIN_ROE_TTM + PE_TTM）\n'
      + '• weightMode 设为 EQUAL，调仓频率设为 MONTHLY（最小化成本）\n'
      + '• 跑一遍回测看选股效应是否转正\n'
      + '• 如果转正 → 逐个加回原来的因子，每次加一个并回测验证\n'
      + '• 如果仍为负 → 说明回测期整体市场环境对基本面策略不友好'
    ));
    verifications.push(makeVerify('逐因子验证',
      '最小化策略回测后，选股效应应为正（>0）——如果连这个都做不到，说明回测期内基本面信号也失效，需要换到其他市场区间验证。'
    ));
  }

  // ── 第二层：通用验证指引 ──
  if (actionables.length > 0 && verifications.length === 0) {
    verifications.push(makeVerify('操作验证',
      '每次改动一个参数后重跑回测：对比 Brinson 归因面板，确认对应效应数值是否按预期方向改善。如果改了一个参数后效应反而恶化，立刻还原。'
    ));
  }

  // ── 第三层：需新增的能力 ──
  if (hasIndData) {
    var highConc = worstContributors.filter(function(x) { return Math.abs((x.avgContribution != null ? +x.avgContribution : (+x.totalContribution || 0) / Math.max(perds.length, 1))) > 0.005; });
    if (highConc.length > 0) {
      var concNames = highConc.map(function(x) { return x.industry + '(' + fmtPctV(x.totalContribution) + ')'; }).join('、');
      missingCaps.push(makeMissing('行业集中度控制',
        concNames + ' 过度集中拖累组合。当前策略没有行业权重上限参数。建议增加：\n'
        + '• 行业中性化模块：在指定行业内做截面排名（而非全市场排名），避免因子偏好导致单一行业过度集中\n'
        + '• 策略编辑页新增"单行业最大权重"参数（如 ≤ 15%），超出部分均摊到其他行业\n'
        + '• 优先级：行业中性化 > 权重上限（前者是根，后者是治标）'
      ));
    }

    // 如果配置和选股在相同行业都有问题 → 行业择时能力缺失
    var sameBothWrong = worstContributors.filter(function(x) {
      return (+x.totalAllocation || 0) < -0.002 && (+x.totalSelection || 0) < -0.002;
    });
    if (sameBothWrong.length > 0) {
      var bothNames = sameBothWrong.map(function(x) { return x.industry; }).join('、');
      missingCaps.push(makeMissing('行业择时信号',
        bothNames + ' 配置和选股双输 — 说明这些行业整体走势判断错误。当前策略没有独立于选股的行业择时能力。建议增加：\n'
        + '• 行业轮动因子：指数动量的行业截面排名（如过去20日行业指数收益率），独立判断行业方向\n'
        + '• 实现方式：新增一个"行业择时"配置类型，与选股因子独立计算、各自贡献权重'
      ));
    }
  }

  // 依赖因子但没解析到 → 提示数据缺失
  if (!hasFactors && negCount > 0) {
    missingCaps.push(makeMissing('因子配置数据',
      '未能解析到策略因子配置（screenConfigJson 为空）。行动建议只能给出通用方向，无法定位到具体因子。建议检查 RollingScreenTask 的 screen_config_json 字段是否正确保存。'
    ));
  }

  // 可信度标记：解释力 < 30% 时行动建议可信度低
  var lowCredibility = summary.explanationRatio != null && +summary.explanationRatio < 0.3;
  var totExcessAbs = Math.abs(excess);
  var modelExplained = lowCredibility ? (+summary.explanationRatio * 100).toFixed(1) : null;

  // 可折叠状态：低解释力默认收起
  var [showActions, setShowActions] = useState(!lowCredibility);

  return (
    <Card
      size="small"
      style={{ marginTop: 16, borderLeft: '3px solid ' + overallColor }}
      title={<span style={{ fontSize: 14 }}>归因结论</span>}
    >
      {/* 关键指标概览 */}
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 0, marginBottom: 12, background: '#fafafa', padding: '4px 0', borderRadius: 4 }}>
        {[
          { label: '超额收益', val: excess, fmt: fmtSigned,
            tip: '策略累计收益 − 基准累计收益。正数表示跑赢基准，负数为跑输。这是 Brinson 归因要拆解的目标——三效应之和 ≈ 超额（差值为残差）。' },
          { label: '配置效应', val: alloc, fmt: fmtSigned,
            tip: '行业配置（择时）贡献。公式: Σ(策略权重−基准权重)×(行业基准收益−基准总收益)。正数 = 超配了强势行业/低配了弱势行业。' },
          { label: '选股效应', val: select, fmt: fmtSigned,
            tip: '行业内选股贡献。公式: Σ 基准权重×(策略行业内收益−行业基准收益)。正数 = 所选个股跑赢行业均值。' },
          { label: '交互效应', val: interaction, fmt: fmtSigned,
            tip: '配置与选股的乘积效应。公式: Σ(权重差)×(选股收益差)。正数 = 权重方向与选股方向一致（乘数效应放大），负数 = 互相抵消。' },
          Math.abs(residual) > 0.001 ? { label: '残差', val: residual, fmt: fmtSigned,
            tip: '超额收益 − (配置+选股+交互)。单期 Brinson 恒等式保证残差=0，但多期累加时由于各期权重变化不闭合。残差绝对值大 = 模型解释力低（如高换手策略导致多期偏离大）。' } : null,
          summary.explanationRatio != null ? { label: '解释力', val: (+summary.explanationRatio * 100), fmt: v => (v).toFixed(1) + '%',
            tip: '1 − |残差| / |超额收益|。范围 0~100%，越高说明三效应对超额的拆解越完整。<30% 时模型参考价值有限。' } : null,
        ].filter(Boolean).map(function(item, idx, arr) {
          var posColor = item.val >= 0 ? '#52c41a' : '#cf1322';
          var isExpl = item.label === '解释力';
          var valColor = isExpl
            ? (item.val >= 70 ? '#52c41a' : item.val < 30 ? '#cf1322' : '#faad14')
            : posColor;
          return (
            <div key={idx} style={{ textAlign: 'center', padding: '2px 10px', borderRight: idx < arr.length - 1 ? '1px solid #e8e8e8' : 'none', whiteSpace: 'nowrap', cursor: item.tip ? 'help' : 'default' }}>
              <AntTooltip title={item.tip} placement="top">
                <div style={{ fontSize: 12, color: '#888' }}>
                  {item.label}
                  {item.tip && <span style={{ marginLeft: 2, color: '#bbb', fontSize: 10 }}>ⓘ</span>}
                </div>
              </AntTooltip>
              <div style={{ fontSize: 14, fontWeight: 600, color: valColor }}>{item.fmt(item.val)}</div>
            </div>
          );
        })}
      </div>

      {/* 行业摘要 */}
      {indSummaryText && (
        <div style={{
          background: '#f0f5ff', padding: '6px 10px', borderRadius: 4,
          border: '1px solid #c6daff', marginBottom: 10, fontSize: 12, lineHeight: 1.6, color: '#2b4acb'
        }}>
          {indSummaryText}
        </div>
      )}

      {/* 总体判断 */}
      <div style={{
        background: overallColor === '#52c41a' ? '#f6ffed' : overallColor === '#d48806' ? '#fff7e6' : overallColor === '#8c8c8c' ? '#fafafa' : '#fff2f0',
        padding: '10px 12px', borderRadius: 4,
        border: '1px solid ' + (overallColor === '#52c41a' ? '#b7eb8f' : overallColor === '#d48806' ? '#ffd591' : overallColor === '#8c8c8c' ? '#d9d9d9' : '#ffccc7'),
        marginBottom: 12, fontWeight: 700, fontSize: 14
      }}>
        总体判断：<span style={{ color: overallColor }}>{overall}</span>
        {overall !== '好' && overall !== '一般' && summary.explanationRatio != null && +summary.explanationRatio < 0.3 ? (
          <span style={{ fontWeight: 400, fontSize: 12, color: '#8c8c8c' }}>
            {' （注意：模型解释力仅 ' + (+summary.explanationRatio * 100).toFixed(1) + '%，以下配置、选股、交互仅能解释超额收益的极小部分，此归因参考价值有限）'}
          </span>
        ) : null}
      </div>

      {/* 低解释力特殊提示：此策略不适合 Brinson 归因 */}
      {summary.explanationRatio != null && +summary.explanationRatio < 0.3 ? (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 12, fontSize: 12, lineHeight: 1.8 }}
          message="此策略收益来源不在行业配置/选股维度，Brinson 归因参考价值有限"
          description={
            <div style={{ fontSize: 12, lineHeight: 1.8 }}>
              <div>当前模型解释力仅 <b>{(+summary.explanationRatio * 100).toFixed(1)}%</b>，
                说明超额收益主要来自 <b>行业配置以外的因素</b>（如动量/波动率/换手择时等因子暴露）。</div>
              <div style={{ marginTop: 6, padding: '6px 8px', background: '#fafafa', borderRadius: 4, color: '#666' }}>
                <b>💡 什么是因子风格归因？</b><br/>
                Brinson 归因按「行业」拆解收益，因子风格归因则按「风格因子」拆解：<br/>
                &nbsp;&nbsp;• <b>动量因子</b> — 追涨杀跌带来的收益（高换手策略天然暴露）<br/>
                &nbsp;&nbsp;• <b>波动率因子</b> — 高波动股票的短期溢价<br/>
                &nbsp;&nbsp;• <b>流动性因子</b> — 小市值/低流动性股票的溢价<br/>
                &nbsp;&nbsp;• <b>市值因子</b> — 小盘股 vs 大盘股的收益差<br/>
                &nbsp;&nbsp;• <b>残差 α</b> — 所有因子解释完后剩下的纯选股能力<br/>
                方法是把策略收益对以上因子做回归：<code style={{ fontSize: 11 }}>R_p − R_b = β₁×动量 + β₂×波动率 + ... + α</code>，
                β 显著 ≠ 0 说明策略在有意/无意地暴露这个因子。
              </div>
              <div style={{ marginTop: 6, color: '#fa8c16' }}>
                ⚠ 当前平台暂未实现因子风格归因，如需分析请手动检查策略持仓特征（换手率/市值分布/波动率偏好）。
              </div>
            </div>
          }
        />
      ) : null}

      {/* 三者关系解释（当有大正值抵消大负值时特别重要） */}
      {((function() {
        var absAlloc = Math.abs(alloc), absSelect = Math.abs(select), absInter = Math.abs(interaction);
        var hasBigOffset = (absAlloc > 0.05 && absInter > 0.05) || (absSelect > 0.05 && absInter > 0.05) || (absAlloc > 0.05 && absSelect > 0.05);
        if (!hasBigOffset) return null;
        var modelSum = alloc + select + interaction;
        var parts = [];
        if (Math.abs(alloc) > 0.005) parts.push('配置 ' + fmtSigned(alloc));
        if (Math.abs(select) > 0.005) parts.push('选股 ' + fmtSigned(select));
        if (Math.abs(interaction) > 0.005) parts.push('交互 ' + fmtSigned(interaction));
        var hasBigResidual = Math.abs(residual) > 0.05;
        var relation = '三效应加法关系：' + parts.join(' + ') + ' ≈ ' + fmtSigned(modelSum) + '。';
        if (hasBigResidual) {
          relation += ' 但注意：这不是最终超额！实际超额 = ' + fmtSigned(excess) + '，包含残差 ' + fmtSigned(residual) + '（三效应解释不了的部分）。';
        }
        var note = '';
        if (hasBigResidual) {
          note = '残差 ' + fmtSigned(residual) + ' 很大，说明行业配置/选股以外的因素主导了最终超额（如高换手带来的交易摩擦、市场Beta暴露、黑天鹅日暴跌等）。';
          if (summary.explanationRatio != null && +summary.explanationRatio < 0.3) {
            note += '\n模型解释力仅 ' + (+summary.explanationRatio * 100).toFixed(0) + '%，三效应能解释的超额极少，此归因参考价值有限。';
          }
        }
        if (alloc < -0.03 && interaction > 0.03) {
          note += (note ? '\n' : '') + '配置大亏' + fmtSigned(alloc) + '被交互大赚' + fmtSigned(interaction) + '部分对冲。交互赚钱说明：虽然行业配置方向错了，但在错误配置的行业里选股方向反过来产生了乘积效应（超配但行业内选股差→低配/避开，低配但行业内选股好→超配追入）。';
        }
        return <div style={{
          background: '#f9f0ff', padding: '8px 12px', borderRadius: 4,
          border: '1px solid #d3adf7', marginBottom: 12, fontSize: 12, lineHeight: 1.8, color: '#531dab', whiteSpace: 'pre-wrap'
        }}>
          {relation}<br/>
          {note}
        </div>;
      })())}

      {/* 三效应详情卡片 */}
      {/* 第一行：配置效应 + 选股效应 左右两列 */}
      <div style={{ display: 'flex', gap: 8, marginBottom: 8 }}>
        {(function() { return [effectCards[0], effectCards[1]].map(function(card, idx) {
          return <div key={idx} style={{ flex: 1, minWidth: 0 }}>
            <Card size="small" type="inner"
              title={<span style={{ fontWeight: 700, fontSize: 13 }}>{card.title}</span>}
              style={{ background: '#fff' }}
            >
              <div style={{ fontSize: 12, color: '#888', marginBottom: 4 }}>{card.verdict}</div>
              <div style={{ fontSize: 11, color: '#aaa', marginBottom: 4, fontFamily: 'monospace', background: '#fafafa', padding: '2px 6px', borderRadius: 3 }}>
                {card.formula}
              </div>
              {card.formulaLegend ? (
                <div style={{ fontSize: 10, color: '#bbb', marginBottom: 8, paddingLeft: 6, lineHeight: 1.5 }}>
                  {card.formulaLegend}
                </div>
              ) : null}
              {card.posFactors && card.posFactors.length > 0 ? (
                <div style={{ marginBottom: 4 }}>
                  <Tag color="success" style={{ marginRight: 4, fontSize: 10 }}>正贡献</Tag>
                  {card.posFactors.map(function(f, fi) {
                    return <div key={'pos-' + fi} style={{ fontSize: 12, margin: '2px 0', paddingLeft: 16, lineHeight: 1.6 }}>
                      <b>{f.name}</b>: {fmtPctV(f.effect)}/期 &mdash; {f.reason}
                    </div>;
                  })}
                </div>
              ) : null}
              {card.negFactors && card.negFactors.length > 0 ? (
                <div>
                  <Tag color="error" style={{ marginRight: 4, fontSize: 10 }}>负贡献</Tag>
                  {card.negFactors.map(function(f, fi) {
                    return <div key={'neg-' + fi} style={{ fontSize: 12, margin: '2px 0', paddingLeft: 16, lineHeight: 1.6 }}>
                      <b>{f.name}</b>: {fmtPctV(f.effect)}/期 &mdash; {f.reason}
                    </div>;
                  })}
                </div>
              ) : null}
              {(!card.posFactors || !card.posFactors.length) && (!card.negFactors || !card.negFactors.length) ? (
                <div style={{ fontSize: 12, color: '#999' }}>无显著行业贡献</div>
              ) : null}
            </Card>
          </div>;
        }); })()}
      </div>
      {/* 第二行：交互效应 独占整行 */}
      {(function() { var card = effectCards[2];
        return <Card size="small" type="inner"
          title={<span style={{ fontWeight: 700, fontSize: 13 }}>{card.title}</span>}
          style={{ marginBottom: 8, background: '#fff' }}
        >
          <div style={{ fontSize: 12, color: '#888', marginBottom: 4 }}>{card.verdict}</div>
          <div style={{ fontSize: 11, color: '#aaa', marginBottom: 4, fontFamily: 'monospace', background: '#fafafa', padding: '2px 6px', borderRadius: 3 }}>
            {card.formula}
          </div>
          {card.formulaLegend ? (
            <div style={{ fontSize: 10, color: '#bbb', marginBottom: 8, paddingLeft: 6, lineHeight: 1.5 }}>
              {card.formulaLegend}
            </div>
          ) : null}
          {card.insight ? (
            <div style={{ fontSize: 12, color: '#d48806', background: '#fffbe6', padding: '6px 8px', borderRadius: 3, border: '1px solid #ffe58f', marginBottom: 8, lineHeight: 1.6, whiteSpace: 'pre-wrap' }}>
              {card.insight}
            </div>
          ) : null}
          {card.posFactors && card.posFactors.length > 0 ? (
            <div style={{ marginBottom: 4 }}>
              <Tag color="success" style={{ marginRight: 4, fontSize: 10 }}>正贡献</Tag>
              {card.posFactors.map(function(f, fi) {
                return <div key={'pos-' + fi} style={{ fontSize: 12, margin: '2px 0', paddingLeft: 16, lineHeight: 1.6 }}>
                  <b>{f.name}</b>: {fmtPctV(f.effect)}/期 &mdash; {f.reason}
                </div>;
              })}
            </div>
          ) : null}
          {card.negFactors && card.negFactors.length > 0 ? (
            <div>
              <Tag color="error" style={{ marginRight: 4, fontSize: 10 }}>负贡献</Tag>
              {card.negFactors.map(function(f, fi) {
                return <div key={'neg-' + fi} style={{ fontSize: 12, margin: '2px 0', paddingLeft: 16, lineHeight: 1.6 }}>
                  <b>{f.name}</b>: {fmtPctV(f.effect)}/期 &mdash; {f.reason}
                </div>;
              })}
            </div>
          ) : null}
          {(!card.posFactors || !card.posFactors.length) && (!card.negFactors || !card.negFactors.length) ? (
            <div style={{ fontSize: 12, color: '#999' }}>无显著行业贡献</div>
          ) : null}
        </Card>;
      })()} 

      {/* ─── 行动建议（可折叠） ─── */}
      {actionables.length > 0 ? (
        <div style={{ marginBottom: 12 }}>
          {/* 折叠头 */}
          <div
            onClick={function() { setShowActions(!showActions); }}
            style={{
              display: 'flex', alignItems: 'center', gap: 8, cursor: 'pointer',
              padding: '8px 12px', borderRadius: 4,
              background: lowCredibility ? '#fafafa' : '#fff2f0',
              border: '1px solid ' + (lowCredibility ? '#d9d9d9' : '#ffccc7'),
              userSelect: 'none',
            }}
          >
            <span style={{ fontSize: 13, fontWeight: 700, color: lowCredibility ? '#595959' : '#cf1322' }}>
              {lowCredibility ? '观察到的问题 & 推断性建议' : '可立即操作（策略编辑页直接改）'}
              {lowCredibility && <Tag color="warning" style={{ marginLeft: 6, fontSize: 10 }}>低可信度</Tag>}
            </span>
            <span style={{ flex: 1, fontSize: 11, color: '#8c8c8c' }}>
              {lowCredibility
                ? 'Brinson 模型解释力仅 ' + modelExplained + '%，行业/选股维度无法有效解析此策略的收益来源'
                : '以下操作可直接提升策略表现'}
            </span>
            <span style={{ fontSize: 11, color: '#8c8c8c' }}>
              {showActions ? '收起 ▲' : '展开 ▼'}
            </span>
          </div>

          {showActions && (
            <div style={{ marginTop: 8 }}>
              {/* 低可信度警告（折叠内） */}
              {lowCredibility && (
                <div style={{
                  background: '#fff7e6', padding: '8px 12px', borderRadius: 4,
                  border: '1px solid #ffd591', marginBottom: 12, fontSize: 12, lineHeight: 1.7, color: '#ad6800'
                }}>
                  <b>归因模型解释力仅 {modelExplained}%，以下行动建议中：</b><br/>
                  • <span style={{ color: '#52c41a' }}>绿色标记</span> = 基于原始数据的事实观察（如"某行业选股跑输 X%"） — 可信<br/>
                  • <span style={{ color: '#faad14' }}>黄色标记</span> = 基于归因模型的推断 — <b>仅 {modelExplained} 超额能被模型解释，推断准确性很低，仅供参考</b><br/>
                  • 建议：关注事实部分（哪些行业出问题），推断部分仅作思路启发，勿直接照做
                </div>
              )}

              {/* 第一层：观察到的问题 + 推断性建议 */}
              <div style={{ marginBottom: 12 }}>
                {actionables.map(function(a, i) {
                  var lines = a.body.split('\n');
                  var factLines = [];
                  var inferLines = [];
                  var currentIsFact = false;
                  var currentIsInfer = false;
                  for (var l = 0; l < lines.length; l++) {
                    var line = lines[l];
                    if (!line.trim()) { currentIsFact = false; currentIsInfer = false; continue; }
                    if (lowCredibility && (
                      /^以下行业/.test(line) ||
                      /^当前因子/.test(line) ||
                      /^当前 /.test(line) ||
                      /^当前已是/.test(line) ||
                      /交易成本.*侵蚀/.test(line)
                    )) {
                      factLines.push({ text: line, prefix: '📊 ' });
                      currentIsFact = true;
                      currentIsInfer = false;
                    } else if (lowCredibility && (
                      /^• 操作/.test(line) ||
                      /^• 移除/.test(line) ||
                      /^• 如所有/.test(line) ||
                      /^• 预期/.test(line) ||
                      /预期/.test(line) ||
                      /预期 /.test(line)
                    )) {
                      inferLines.push({ text: line, prefix: '💡 ' });
                      currentIsFact = false;
                      currentIsInfer = true;
                    } else if (currentIsFact) {
                      factLines.push({ text: line, prefix: '' });
                    } else if (currentIsInfer) {
                      inferLines.push({ text: line, prefix: '' });
                    } else {
                      inferLines.push({ text: line, prefix: lowCredibility ? '💡 ' : '' });
                    }
                  }
                  return (
                    <div key={'act-' + i} style={{
                      background: lowCredibility ? '#fafafa' : '#fff2f0',
                      padding: '8px 10px', borderRadius: 4,
                      border: '1px solid ' + (lowCredibility ? '#d9d9d9' : '#ffccc7'),
                      marginBottom: 6, fontSize: 12, lineHeight: 1.7
                    }}>
                      <div style={{ fontWeight: 700, marginBottom: 2, color: lowCredibility ? '#595959' : '#262626' }}>{a.label}</div>
                      {lowCredibility ? (
                        <>
                          {factLines.length > 0 && (
                            <div style={{ background: '#f6ffed', padding: '4px 8px', borderRadius: 3, marginBottom: 4, border: '1px solid #d9f7be' }}>
                              <Tag color="success" style={{ marginRight: 4, fontSize: 10 }}>事实</Tag>
                              {factLines.map(function(fl, fi) {
                                return <div key={'fl-' + fi} style={{ whiteSpace: 'pre-wrap', color: '#135200' }}>{fl.prefix}{fl.text}</div>;
                              })}
                            </div>
                          )}
                          {inferLines.length > 0 && (
                            <div style={{ background: '#fffbe6', padding: '4px 8px', borderRadius: 3, border: '1px solid #ffe58f' }}>
                              <Tag color="warning" style={{ marginRight: 4, fontSize: 10 }}>推断</Tag>
                              {inferLines.map(function(il, ii) {
                                return <div key={'il-' + ii} style={{ whiteSpace: 'pre-wrap', color: '#ad6800' }}>{il.prefix}{il.text}</div>;
                              })}
                            </div>
                          )}
                        </>
                      ) : (
                        <div style={{ whiteSpace: 'pre-wrap', color: '#434343' }}>{a.body}</div>
                      )}
                    </div>
                  );
                })}
              </div>

              {/* 第二层：验证步骤 */}
              {verifications.length > 0 && (
                <div style={{ marginBottom: 12 }}>
                  <div style={{ fontWeight: 700, marginBottom: 6, color: lowCredibility ? '#8c8c8c' : '#0958d9', fontSize: 13 }}>
                    {lowCredibility ? '验证预测（低可信度，仅供参考）' : '如何验证修复有效'}
                    {lowCredibility && <Tag color="warning" style={{ marginLeft: 6, fontSize: 10 }}>低可信度</Tag>}
                  </div>
                  {verifications.map(function(v, i) {
                    return <div key={'ver-' + i} style={{
                      background: lowCredibility ? '#fafafa' : '#e6f4ff',
                      padding: '6px 10px', borderRadius: 4,
                      border: '1px solid ' + (lowCredibility ? '#d9d9d9' : '#91caff'),
                      marginBottom: 4, fontSize: 12, lineHeight: 1.7
                    }}>
                      {lowCredibility && <Tag color="warning" style={{ marginRight: 4, fontSize: 10 }}>推断</Tag>}
                      <span style={{ fontWeight: 600, color: lowCredibility ? '#8c8c8c' : '#262626' }}>{v.label}：</span>
                      <span style={{ color: lowCredibility ? '#8c8c8c' : '#434343' }}>{v.body}</span>
                    </div>;
                  })}
                </div>
              )}

              {/* 第三层：需新增的能力 */}
              {missingCaps.length > 0 && (
                <div style={{ marginBottom: 12 }}>
                  <div style={{ fontWeight: 700, marginBottom: 6, color: '#8c8c8c', fontSize: 13 }}>
                    需新增的策略能力（当前不支持，建议后续开发）
                  </div>
                  {missingCaps.map(function(m, i) {
                    return <div key={'mis-' + i} style={{
                      background: '#fafafa', padding: '6px 10px', borderRadius: 4,
                      border: '1px solid #d9d9d9', marginBottom: 4, fontSize: 12, lineHeight: 1.7
                    }}>
                      <div style={{ fontWeight: 600, marginBottom: 2, color: '#595959' }}>{m.label}</div>
                      <div style={{ whiteSpace: 'pre-wrap', color: '#8c8c8c' }}>{m.body}</div>
                    </div>;
                  })}
                </div>
              )}
            </div>
          )}
        </div>
      ) : (
        missingCaps.length > 0 ? (
          /* 无可操作建议但有缺失能力 */
          <div style={{ marginBottom: 12 }}>
            <div style={{
              display: 'flex', alignItems: 'center', gap: 8, cursor: 'pointer',
              padding: '8px 12px', borderRadius: 4,
              background: '#fafafa', border: '1px solid #d9d9d9',
              userSelect: 'none',
            }}
              onClick={function() { setShowActions(!showActions); }}
            >
              <span style={{ fontSize: 13, fontWeight: 700, color: '#595959' }}>
                当前归因结果良好，无需调整
              </span>
              <span style={{ flex: 1 }} />
              <span style={{ fontSize: 11, color: '#8c8c8c' }}>
                {showActions ? '收起 ▲' : '展开 ▼'}
              </span>
            </div>
            {showActions && (
              <div style={{ marginTop: 8 }}>
                <div style={{ fontWeight: 700, marginBottom: 6, color: '#8c8c8c', fontSize: 13 }}>
                  需新增的策略能力（当前不支持，建议后续开发）
                </div>
                {missingCaps.map(function(m, i) {
                  return <div key={'mis-' + i} style={{
                    background: '#fafafa', padding: '6px 10px', borderRadius: 4,
                    border: '1px solid #d9d9d9', marginBottom: 4, fontSize: 12, lineHeight: 1.7
                  }}>
                    <div style={{ fontWeight: 600, marginBottom: 2, color: '#595959' }}>{m.label}</div>
                    <div style={{ whiteSpace: 'pre-wrap', color: '#8c8c8c' }}>{m.body}</div>
                  </div>;
                })}
              </div>
            )}
          </div>
        ) : null
      )}
    </Card>
  );
}

/**
 * 因子风格归因面板
 * 对动量/波动率/市值/换手率因子做多元回归，拆解策略收益来源
 */
function FactorAttributionPanel({ taskId }) {
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const [strategy, setStrategy] = useState(null); // 推荐模型信息

  useEffect(() => {
    // 加载归因策略推荐
    backtestApi.getAttributionStrategy(taskId)
      .then(res => setStrategy(res))
      .catch(err => console.warn('加载归因策略推荐失败:', err));
  }, [taskId]);

  const loadFactorAttr = useCallback(() => {
    setLoading(true);
    setError(null);
    backtestApi.getFactorAttribution(taskId)
      .then(res => setData(res))
      .catch(err => {
        console.error('因子归因分析失败:', err);
        setError(err?.response?.data?.message || err?.message || '未知错误');
      })
      .finally(() => setLoading(false));
  }, [taskId]);

  useEffect(() => { loadFactorAttr(); }, [taskId]);

  if (loading) {
    return <Spin tip="正在计算因子风格归因...（需查询 ClickHouse 多空组合收益率，可能较慢）"><div style={{ height: 200 }} /></Spin>;
  }

  if (error) {
    return <Alert type="error" showIcon message="因子归因计算失败" description={error} />;
  }

  if (!data) return null;

  var summary = data.summary || {};
  var factors = data.factors || [];
  var contributions = data.factorContributions || [];
  var regDetail = data.regressionDetail || {};
  var periodContribs = data.periodContributions || [];
  var observationDays = data.observationDays || 0;
  var excess = summary.totalExcessReturn || 0;
  var factorContrib = summary.totalFactorContribution || 0;
  var residual = summary.residual || 0;
  var explRatio = summary.explanationRatio || 0;
  var r2 = regDetail.rSquared || 0;
  var adjR2 = regDetail.adjRSquared || 0;
  var alpha = regDetail.alpha || 0;
  var annualAlpha = regDetail.annualizedAlpha || 0;

  // ── 关键指标 ──
  var fmtPctShort = (v) => {
    if (v == null) return 'N/A';
    return (v >= 0 ? '+' : '') + (v * 100).toFixed(2) + '%';
  };

  // ── 因子暴露柱状图 ──
  var barOption = {
    tooltip: {
      trigger: 'axis',
      formatter: function(params) {
        var f = contributions[params[0].dataIndex];
        var sig = f.significant ? '★ 显著' : '(不显著)';
        return '<div style="font-weight:600">' + f.factorName + ' (' + f.factorCode + ')</div>' +
          '<div>β 暴露: ' + f.beta.toFixed(4) + ' ' + sig + '</div>' +
          '<div>t值: ' + (f.tStat != null ? f.tStat.toFixed(2) : 'N/A') + '</div>' +
          '<div>年化因子收益: ' + fmtPctShort(f.annualizedFactorReturn) + '</div>' +
          '<div>总因子收益: ' + fmtPctShort(f.totalFactorReturn) + '</div>' +
          '<div style="font-weight:600;color:' + (f.contribution >= 0 ? '#cf1322' : '#3f8600') + '">贡献: ' + fmtPctShort(f.contribution) + '</div>';
      }
    },
    grid: { left: 140, right: 60, top: 20, bottom: 30 },
    xAxis: { type: 'value', axisLabel: { formatter: function(v) { return v.toFixed(3); } }, name: 'β 暴露系数' },
    yAxis: {
      type: 'category',
      data: contributions.map(function(f) { return f.factorName; }).reverse(),
      axisLabel: { fontSize: 12 },
    },
    series: [{
      name: 'β',
      type: 'bar',
      data: contributions.map(function(f) {
        var color = f.contribution >= 0 ? '#cf1322' : '#3f8600';
        return {
          value: parseFloat(f.beta.toFixed(4)),
          itemStyle: {
            color: color,
            borderColor: f.significant ? '#000' : '#d9d9d9',
            borderWidth: f.significant ? 1.5 : 0.5,
            borderType: f.significant ? 'solid' : 'dashed',
          }
        };
      }).reverse(),
      barMaxWidth: 28,
      label: {
        show: true, position: 'right',
        formatter: function(p) {
          var c = contributions[contributions.length - 1 - p.dataIndex];
          return (c.significant ? '★ ' : '') + (c.tStat != null ? 't=' + c.tStat.toFixed(2) : '');
        },
        fontSize: 10,
      }
    }],
  };

  // ── 因子贡献瀑布图 ──
  var waterfallData = [];
  var running = 0;
  contributions.forEach(function(c) {
    waterfallData.push({ name: c.factorName, value: c.contribution, itemStyle: { color: c.contribution >= 0 ? '#cf1322' : '#3f8600' } });
    running += c.contribution;
  });
  waterfallData.push({ name: '因子合计', value: factorContrib, itemStyle: { color: '#1677ff' } });
  waterfallData.push({ name: '残差(α)', value: residual, itemStyle: { color: '#8c8c8c' } });
  waterfallData.push({ name: '实际超额', value: excess, itemStyle: { color: '#000' } });

  var waterfallOption = {
    tooltip: { trigger: 'axis', formatter: function(p) { return p[0].name + ': ' + fmtPctShort(p[0].value); } },
    grid: { left: 100, right: 30, top: 20, bottom: 30 },
    xAxis: { type: 'category', data: waterfallData.map(function(d) { return d.name; }), axisLabel: { fontSize: 11 } },
    yAxis: { type: 'value', axisLabel: { formatter: function(v) { return (v * 100).toFixed(1) + '%'; } } },
    series: [{
      name: '贡献', type: 'bar',
      data: waterfallData.map(function(d) { return d; }),
      barMaxWidth: 40,
    }],
  };

  return (
    <div>
      {/* 策略推荐信息 */}
      {strategy && (
        <Alert
          type={
            strategy.recommendedModel === 'UNCLEAR' ? 'warning' :
            strategy.recommendedModel === 'FACTOR' ? 'success' : 'info'
          }
          showIcon
          style={{ marginBottom: 16, fontSize: 12 }}
          message={
            <span>
              推荐模型：<b>{
                strategy.recommendedModel === 'UNCLEAR' ? '暂不明确（两种模型解释力均不足）' :
                strategy.recommendedModel === 'FACTOR' ? '因子风格归因' : 'Brinson 归因'
              }</b>
              <span style={{ color: '#8c8c8c', marginLeft: 8, fontSize: 11 }}>
                {strategy.reason}
              </span>
            </span>
          }
        />
      )}

      {/* 模型对比数据 */}
      {strategy && strategy.modelComparison && (
        <Card size="small" title="归因模型对比" style={{ marginBottom: 16 }}>
          <table style={{ width: '100%', fontSize: 12, borderCollapse: 'collapse' }}>
            <thead>
              <tr style={{ borderBottom: '2px solid #f0f0f0', textAlign: 'left' }}>
                <th style={{ padding: '6px 8px' }}>模型</th>
                <th style={{ padding: '6px 8px', textAlign: 'right' }}>解释力</th>
                <th style={{ padding: '6px 8px', textAlign: 'right' }}>可用性</th>
              </tr>
            </thead>
            <tbody>
              {Object.entries(strategy.modelComparison).map(function([key, val], i) {
                return (
                  <tr key={i} style={{ borderBottom: '1px solid #f0f0f0' }}>
                    <td style={{ padding: '6px 8px' }}>
                      <b>{key === 'BRINSON' ? 'Brinson 归因' : '因子风格归因'}</b>
                    </td>
                    <td style={{ padding: '6px 8px', textAlign: 'right', fontWeight: 700, color: (val.explanationRatio || 0) > 0.5 ? '#52c41a' : (val.explanationRatio || 0) > 0.2 ? '#fa8c16' : '#ff4d4f' }}>
                      {((val.explanationRatio || 0) * 100).toFixed(1)}%
                    </td>
                    <td style={{ padding: '6px 8px', textAlign: 'right' }}>
                      {val.available ? <Tag color="success" style={{fontSize:10}}>可用</Tag> : <Tag color="default" style={{fontSize:10}}>不可用</Tag>}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          <div style={{ marginTop: 8, fontSize: 11, color: '#8c8c8c' }}>
            特征参考：换手率 {(strategy.avgDailyTurnover * 100).toFixed(1)}% / 平均持仓 {strategy.avgHoldingDays}天 / 行业集中度 {strategy.industryConcentration.toFixed(2)}
          </div>
        </Card>
      )}

      {/* 关键指标 */}
      <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap', marginBottom: 16 }}>
        {[
          { label: '实际超额', value: fmtPctShort(excess), color: excess >= 0 ? '#cf1322' : '#3f8600' },
          { label: '因子贡献', value: fmtPctShort(factorContrib), color: factorContrib >= 0 ? '#cf1322' : '#3f8600' },
          { label: '残差(α)', value: fmtPctShort(residual), color: '#8c8c8c' },
          { label: '解释力', value: (explRatio * 100).toFixed(1) + '%', color: explRatio > 0.5 ? '#52c41a' : explRatio > 0.2 ? '#fa8c16' : '#ff4d4f' },
          { label: 'R²', value: (r2 * 100).toFixed(1) + '%', color: '#8c8c8c' },
          { label: '年化α', value: fmtPctShort(annualAlpha), color: annualAlpha >= 0 ? '#cf1322' : '#3f8600' },
          { label: '观测天数', value: observationDays + '天', color: '#8c8c8c' },
        ].map(function(item, i) {
          return (
            <div key={i} style={{ textAlign: 'center', minWidth: 80 }}>
              <div style={{ fontSize: 11, color: '#8c8c8c', marginBottom: 2 }}>{item.label}</div>
              <div style={{ fontSize: 18, fontWeight: 700, color: item.color }}>{item.value}</div>
            </div>
          );
        })}
      </div>

      {/* 模型说明 */}
      <div style={{ background: '#f5f5f5', padding: '8px 12px', borderRadius: 4, marginBottom: 12, fontSize: 11, color: '#8c8c8c', lineHeight: 1.7 }}>
        <b>R_p − R_b = α + Σ(β_f × FactorReturn_f) + ε</b><br/>
        因子收益 = 多空组合日收益率（Top 20% 等权 − Bottom 20% 等权）<br/>
        β &gt; 0 且显著(t≥1.96) → 策略<b>偏好高因子值</b>股票。β &lt; 0 → 偏好低因子值。<br/>
        R² 衡量因子对策略日收益<b>波动</b>的解释程度，解释力衡量因子对总超额收益<b>大小</b>的解释程度。
      </div>

      <Row gutter={[16, 16]}>
        {/* 因子暴露 vs 贡献 */}
        <Col span={12}>
          <Card size="small" title="因子 β 暴露" extra={<span style={{fontSize:11,color:'#8c8c8c'}}>★ 显著(t≥1.96)</span>}>
            <ReactECharts option={barOption} style={{ height: 220 }} />
          </Card>
        </Col>
        <Col span={12}>
          <Card size="small" title="收益归因拆解">
            <ReactECharts option={waterfallOption} style={{ height: 220 }} />
          </Card>
        </Col>
      </Row>

      {/* 因子贡献表格 */}
      <Card size="small" title="因子贡献明细" style={{ marginTop: 16 }}>
        <table style={{ width: '100%', fontSize: 12, borderCollapse: 'collapse' }}>
          <thead>
            <tr style={{ borderBottom: '2px solid #f0f0f0', textAlign: 'left' }}>
              <th style={{ padding: '6px 8px' }}>因子</th>
              <th style={{ padding: '6px 8px', textAlign: 'right' }}>β 暴露</th>
              <th style={{ padding: '6px 8px', textAlign: 'right' }}>t值</th>
              <th style={{ padding: '6px 8px', textAlign: 'right' }}>显著性</th>
              <th style={{ padding: '6px 8px', textAlign: 'right' }}>总因子收益</th>
              <th style={{ padding: '6px 8px', textAlign: 'right' }}>年化因子收益</th>
              <th style={{ padding: '6px 8px', textAlign: 'right' }}>贡献</th>
            </tr>
          </thead>
          <tbody>
            {contributions.map(function(c, i) {
              return (
                <tr key={i} style={{ borderBottom: '1px solid #f0f0f0' }}>
                  <td style={{ padding: '6px 8px' }}>
                    <b>{c.factorName}</b>
                    <span style={{ color: '#8c8c8c', marginLeft: 4, fontSize: 10 }}>{c.factorCode}</span>
                  </td>
                  <td style={{ padding: '6px 8px', textAlign: 'right', fontWeight: 700, color: c.beta >= 0 ? '#cf1322' : '#3f8600' }}>
                    {(c.beta != null ? c.beta : 0).toFixed(4)}
                  </td>
                  <td style={{ padding: '6px 8px', textAlign: 'right' }}>
                    {(c.tStat != null ? c.tStat : 0).toFixed(2)}
                  </td>
                  <td style={{ padding: '6px 8px', textAlign: 'right' }}>
                    {c.significant ? <Tag color="success" style={{fontSize:10}}>显著</Tag> : <span style={{color:'#8c8c8c'}}>不显著</span>}
                  </td>
                  <td style={{ padding: '6px 8px', textAlign: 'right' }}>
                    {fmtPctShort(c.totalFactorReturn)}
                  </td>
                  <td style={{ padding: '6px 8px', textAlign: 'right' }}>
                    {fmtPctShort(c.annualizedFactorReturn)}
                  </td>
                  <td style={{ padding: '6px 8px', textAlign: 'right', fontWeight: 700, color: c.contribution >= 0 ? '#cf1322' : '#3f8600' }}>
                    {fmtPctShort(c.contribution)}
                  </td>
                </tr>
              );
            })}
            <tr style={{ borderTop: '2px solid #f0f0f0', background: '#fafafa' }}>
              <td style={{ padding: '6px 8px', fontWeight: 700 }}>合计</td>
              <td style={{ padding: '6px 8px' }}></td>
              <td style={{ padding: '6px 8px' }}></td>
              <td style={{ padding: '6px 8px' }}></td>
              <td style={{ padding: '6px 8px' }}></td>
              <td style={{ padding: '6px 8px' }}></td>
              <td style={{ padding: '6px 8px', textAlign: 'right', fontWeight: 700, color: factorContrib >= 0 ? '#cf1322' : '#3f8600' }}>
                {fmtPctShort(factorContrib)}
              </td>
            </tr>
          </tbody>
        </table>
      </Card>

      {/* 解释力评估 */}
      <Card size="small" title="归因评估" style={{ marginTop: 16 }}>
        {explRatio < 0.3 ? (
          <Alert type="warning" showIcon style={{ fontSize: 12, lineHeight: 1.8 }}
            message="因子模型解释力偏低"
            description={
              <div>
                R²={r2.toFixed(3)}, 解释力={(explRatio*100).toFixed(1)}%。因子模型对策略<b style={{color: explRatio > 0 ? '#52c41a' : '#ff4d4f'}}>
                {explRatio > 0.5 ? '解释力较强' : explRatio > 0.2 ? '有一定解释力' : '解释力不足'}</b>。
                {explRatio <= 0.2 && ' 策略收益来源可能在当前4个因子之外（如事件驱动/短期反转/极端行情），建议扩展因子集合。'}
              </div>
            }
          />
        ) : (
          <Alert type="success" showIcon style={{ fontSize: 12 }}
            message={<>因子模型解释力 <b>{(explRatio*100).toFixed(1)}%</b>，R²={(r2*100).toFixed(1)}%。因子能较好解释策略收益来源。</>}
          />
        )}
        {alpha !== 0 && (
          <div style={{ marginTop: 8, fontSize: 12, color: '#8c8c8c', lineHeight: 1.7 }}>
            <b>纯 Alpha（因子无法解释的部分）：</b>日度 {fmtPctShort(alpha)}，年化 {fmtPctShort(annualAlpha)}。
            {Math.abs(alpha) > 0.001
              ? ' α显著 ≠ 0，策略在因子暴露之外有独立选股/择时能力。'
              : ' α接近0，策略收益几乎完全由因子暴露解释。'}
          </div>
        )}
      </Card>
    </div>
  );
}

/** 完整的归因分析面板 */
function AttributionPanel({ taskId }) {
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState(null);
  const leftRef = useRef(null);
  const [rightHeight, setRightHeight] = useState(null);

  const loadAttribution = useCallback(() => {
    setLoading(true);
    backtestApi.getAttribution(taskId)
      .then(res => setData(res))
      .catch(err => console.error('归因分析失败:', err))
      .finally(() => setLoading(false));
  }, [taskId]);

  // 组件挂载时自动加载（Tabs destroyOnHidden 保证只在切到此 tab 时才挂载）
  useEffect(() => { loadAttribution(); }, [loadAttribution]);

  // 监听左侧高度，同步给右侧
  useEffect(() => {
    if (!data || !leftRef.current) return;
    const calc = () => {
      if (leftRef.current) setRightHeight(leftRef.current.offsetHeight);
    };
    calc();
    const ro = new ResizeObserver(calc);
    ro.observe(leftRef.current);
    return () => ro.disconnect();
  }, [data]);

  if (loading && !data) {
    return (
      <>
        <div style={{ textAlign: 'center', padding: '40px 0 24px' }}>
          <Spin size="default" />
          <div style={{ marginTop: 8, color: '#999', fontSize: 13 }}>Brinson 归因分析加载中...</div>
        </div>
      </>
    );
  }

  if (!data) {
    return (
      <Card size="small" style={{ margin: '0 auto', maxWidth: 360 }}>
        <Alert type="info" message="归因分析计算失败或数据缺失" description="可能后端计算超时或行业分类数据不完整" showIcon action={<Button size="small" onClick={loadAttribution}>重试</Button>} />
      </Card>
    );
  }

  return (
    <>
      <Card size="small" style={{ marginBottom: 16 }}>
        <Row justify="space-between" align="middle">
          <Col>
            <Text type="secondary">Brinson 归因模型</Text>
            <Tag color="blue" style={{ marginLeft: 8 }}>{data.benchmarkDescription}</Tag>
          </Col>
          <Col>
            <Space>
              <Text type="secondary">共 {data.periodCount} 个调仓期</Text>
              <Button size="small" icon={<ReloadOutlined />} onClick={loadAttribution}>重新计算</Button>
            </Space>
          </Col>
        </Row>
      </Card>

      {/* 行业归因概览：覆盖行业数 + 说明 */}
      <Card size="small" style={{ marginBottom: 16, background: '#f6f8fa' }}>
        <Row gutter={16} align="middle">
          <Col>
            <Statistic 
              title="覆盖行业数" 
              value={data.industrySummary ? data.industrySummary.length : 0} 
              suffix="个" 
              valueStyle={{ fontSize: 20, fontWeight: 600, color: '#1677ff' }}
            />
          </Col>
          <Col flex="auto">
            <div style={{ fontSize: 12, color: '#666', lineHeight: 1.8 }}>
              <AntTooltip title="行业归因需对比「策略」与「基准」在各行业的配置差异。只看持仓会遗漏「应该配但没配」的行业（如基准重仓银行而策略空仓 → 负配置效应）。">
                <Text type="secondary" underline style={{ cursor: 'help' }}>
                  行业范围 = 策略持仓行业 ∪ 基准行业（并集）
                </Text>
              </AntTooltip>
              <br />
              <Text type="secondary">归因期数：{data.periodCount || 0} 期</Text>
            </div>
          </Col>
        </Row>
      </Card>

      <Row gutter={16}>
        <Col span={16}>
          <div ref={leftRef}>
          <Card title="累计归因曲线" size="small">
            <AttributionCumulativeChart cumulativeChart={data.cumulativeChart} />
          </Card>
          <BrinsonConclusion summary={data.summary} industrySummary={data.industrySummary} periods={data.periods} />
          </div>
        </Col>
        <Col span={8} style={{ height: rightHeight || 'auto' }}>
          <Card title="行业归因汇总" size="small" styles={{ body: { padding: '8px 12px', height: '100%', overflow: 'hidden' } }} style={{ width: '100%', height: rightHeight || 'auto' }}>
            <IndustryAttributionTable industrySummary={data.industrySummary} height={rightHeight} />
          </Card>
        </Col>
      </Row>

      <Card title="各期归因分解（堆叠柱状图）" size="small" style={{ marginTop: 16 }}>
        <AttributionWaterfall periods={data.periods} />
      </Card>
    </>
  );
}

// ─── 超额收益（Alpha）分析面板 ────────────────────────────────
function ExcessAnalysisPanel({ report }) {
  if (!report) return null;

  const fmtPct2 = (v, d = 2) => v != null ? `${(+v * 100).toFixed(d)}%` : '-';
  const fmt2 = (v, d = 4) => v != null ? (+v).toFixed(d) : '-';

  // 分级阈值：返回 { label, level } — level 0=优秀 1=良好 2=及格 3=不及格 4=差
  const gradeExcessMean = (v) => {
    if (v >= 0.15) return { label: '优秀', level: 0 };
    if (v >= 0.05) return { label: '良好', level: 1 };
    if (v >= 0)    return { label: '及格', level: 2 };
    if (v >= -0.10) return { label: '不及格', level: 3 };
    return { label: '严重不及格', level: 4 };
  };
  const gradeExcessStd = (v) => {
    if (v < 0.05)  return { label: '极稳定', level: 0 };
    if (v < 0.10)  return { label: '稳定', level: 0 };
    if (v < 0.20)  return { label: '可接受', level: 2 };
    if (v < 0.30)  return { label: '偏高', level: 3 };
    return { label: '极端波动', level: 4 };
  };
  const gradeExcessWR = (v) => {
    if (v >= 0.60) return { label: '优秀', level: 0 };
    if (v >= 0.55) return { label: '良好', level: 1 };
    if (v >= 0.50) return { label: '及格', level: 2 };
    if (v >= 0.45) return { label: '不及格', level: 3 };
    return { label: '严重不及格', level: 4 };
  };
  const gradeExcessMDD = (v) => {
    if (v < 0.05)  return { label: '极低回撤', level: 0 };
    if (v < 0.10)  return { label: '优秀', level: 0 };
    if (v < 0.20)  return { label: '可接受', level: 2 };
    if (v < 0.30)  return { label: '偏高', level: 3 };
    if (v < 0.50)  return { label: '危险', level: 4 };
    return { label: '极度危险', level: 4 };
  };
  const gradeAlpha = (v) => {
    if (v >= 0.50)  return { label: '强Alpha', level: 0 };
    if (v >= 0.20)  return { label: '有Alpha', level: 1 };
    if (v >= 0)     return { label: '微弱Alpha', level: 2 };
    if (v >= -0.20) return { label: '无Alpha', level: 3 };
    if (v >= -0.50) return { label: '深度负Alpha', level: 4 };
    return { label: '严重负Alpha', level: 4 };
  };
  const gradeAlphaContrib = (v) => {
    if (v >= 0.70) return { label: 'Alpha主导', level: 0 };
    if (v >= 0.50) return { label: 'Alpha为主', level: 1 };
    if (v >= 0.30) return { label: '均衡', level: 2 };
    return { label: 'Beta主导', level: 3 };
  };
  const gradeIR = (v) => {
    if (v >= 1.50)  return { label: '优秀', level: 0 };
    if (v >= 0.50)  return { label: '良好', level: 1 };
    if (v >= 0)     return { label: '及格', level: 2 };
    if (v >= -0.50) return { label: '不及格', level: 3 };
    if (v >= -1.0)  return { label: '严重不及格', level: 4 };
    return { label: '深度不合格', level: 4 };
  };

  // 5 级颜色: 优秀绿 → 良好浅绿 → 及格黑 → 不及格橙 → 差红
  const gradeColor = (level) => {
    return ['#52c41a','#73d13d','#262626','#fa8c16','#cf1322'][level];
  };

  const excessMetrics = [
    { label: '超额收益均值', value: report.excessMean, fmt: v => fmtPct2(v),
      grade: (v) => gradeExcessMean(v),
      tip: (g) => `评级：${g.label}\n含义：衡量策略剔除市场 Beta 后的纯 Alpha 大小。>0 说明策略有独立于市场的超额收益能力；<0 说明即便市场在涨、策略也在跑输。` },
    { label: '超额收益标准差', value: report.excessStd, fmt: v => fmtPct2(v),
      grade: (v) => gradeExcessStd(v),
      tip: (g) => `评级：${g.label}\n含义：衡量 Alpha 的稳定性。越低越平稳可靠；越高越不稳定。需结合均值——均值高 + 标准差低 = 优秀 Alpha；均值低 + 标准差高 = 差。` },
    { label: '超额收益胜率', value: report.excessWinRate, fmt: v => fmtPct2(v),
      grade: (v) => gradeExcessWR(v),
      tip: (g) => `评级：${g.label}\n含义：交易日跑赢基准的占比。>50% 大部分时间在赢；<50% 大部分时间跑输。胜率高 + 均值正 = 稳健 Alpha；胜率低 + 均值负 = 系统性跑输。` },
    { label: '超额最大回撤', value: report.excessMaxDrawdown, fmt: v => fmtPct2(v),
      grade: (v) => gradeExcessMDD(v),
      tip: (g) => `评级：${g.label}\n含义：超额净值从峰值到谷底的最大跌幅。衡量最差时期的持续性——回撤小 = 跑输后快速恢复；回撤大 = 持续落后基准。` },
    { label: 'Alpha', value: report.alpha, fmt: v => fmt2(v, 2),
      grade: (v) => gradeAlpha(v),
      tip: (g) => `评级：${g.label}\n含义：α>0 = 真正的选股/择时能力；α<0 = 不如被动持有基准。\n阈值：≥0.5 强 | ≥0.2 有 | ≥0 微弱 | ≥-0.2 无 | ≥-0.5 深度负 | <-0.5 严重负` },
    { label: 'Alpha贡献占比', value: report.alphaContribution, fmt: v => v != null ? `${(+v * 100).toFixed(1)}%` : '-',
      grade: (v) => gradeAlphaContrib(v),
      tip: (g) => `评级：${g.label}\n含义：超额收益中 Alpha 能力的占比。占比高 = 来自选股；占比低 = 靠市场 Beta。注意：Alpha 为负时，占比高 = 亏损靠自身而非市场。\n阈值：≥70% 主导 | ≥50% 为主 | ≥30% 均衡 | <30% Beta 依赖` },
  ];

  const signCol = (v) => +v > 0 ? '#cf1322' : +v < 0 ? '#3f8600' : '#262626';

  // 计算各超额指标评级，供解读行动态生成
  const gExcessMean = gradeExcessMean(+report.excessMean);
  const gExcessStd  = gradeExcessStd(+report.excessStd);
  const gExcessWR   = gradeExcessWR(+report.excessWinRate);
  const gExcessMDD  = gradeExcessMDD(+report.excessMaxDrawdown);
  const gIR         = gradeIR(+report.informationRatio);
  const gAlpha      = gradeAlpha(+report.alpha);
  const gAlphaContrib = gradeAlphaContrib(+report.alphaContribution);

  // 用于 Alpha贡献占比 的分解计算
  const alphaVal = +report.alpha || 0;
  const betaBenchReturn = (+report.beta || 0) * (+report.benchmarkAnnualReturn || 0);
  const contribAlpha   = +report.alphaContribution || 0;
  const contribBeta    = 1 - contribAlpha;
  const alphaSignDesc  = alphaVal >= 0 ? '正Alpha' : '负Alpha';

  const compareRows = [
    { metric: '均值', stock: fmtPct2(report.annualReturn), benchmark: fmtPct2(report.benchmarkAnnualReturn), excess: fmtPct2(report.excessMean),
      interp: `策略年化${fmtPct2(report.annualReturn)} − 基准年化${fmtPct2(report.benchmarkAnnualReturn)} = 超额均值${fmtPct2(report.excessMean)}。>0 说明剔除市场后仍有独立超额；<0 说明被动持有基准反而更优。评级：${gExcessMean.label}（阈值：≥15%优秀 | ≥5%良好 | ≥0%及格 | ≥-10%不及格 | <-10%严重不及格）` },
    { metric: '标准差', stock: fmtPct2(report.volatility), benchmark: '-', excess: fmtPct2(report.excessStd),
      interp: `超额收益年化波动率=${fmtPct2(report.excessStd)}。越低 Alpha 越稳定可预期，越高越随机。评级：${gExcessStd.label}（阈值：<5%极稳定 | <10%稳定 | <20%可接受 | <30%偏高 | ≥30%极端波动）` },
    { metric: '胜率', stock: fmtPct2(report.winRate), benchmark: '-', excess: fmtPct2(report.excessWinRate),
      interp: `${fmtPct2(report.excessWinRate)}的交易日超额收益 >0。胜率高配合均值正 = 稳定 Alpha；胜率低配合均值负 = 系统性跑输。评级：${gExcessWR.label}（阈值：≥60%优秀 | ≥55%良好 | ≥50%及格 | ≥45%不及格 | <45%严重不及格）` },
    { metric: '最大回撤', stock: fmtPct2(report.maxDrawdown), benchmark: '-', excess: fmtPct2(report.excessMaxDrawdown),
      interp: `超额累计净值最大回撤=${fmtPct2(report.excessMaxDrawdown)}。衡量最差时期的持续跑输幅度——回撤小说明跑输后快速恢复，回撤大说明持续落后。评级：${gExcessMDD.label}（阈值：<5%极低 | <10%优秀 | <20%可接受 | <30%偏高 | <50%危险 | ≥50%极度危险）` },
    { metric: '信息比率', stock: fmt2(report.sharpeRatio, 2), benchmark: '-', excess: fmt2(report.informationRatio, 2),
      interp: `=年化超额均值 / 跟踪误差=${fmt2(report.informationRatio, 2)}。每承担1单位主动风险获得的超额回报——>0.5 说明主动管理创造了价值；<0 说明主动管理在毁价值。评级：${gIR.label}（阈值：≥1.5优秀 | ≥0.5良好 | ≥0及格 | ≥-0.5不及格 | ≥-1.0严重不及格 | <-1.0深度不合格）` },
    { metric: 'Alpha', stock: `${fmt2(report.alpha, 2)}（年化）`, benchmark: '-', excess: '-',
      interp: `年化Alpha=${fmt2(report.alpha, 2)}。>0 表示策略有超出市场基准的独立超额收益能力；<0 表示策略${alphaVal < -0.2 ? '不仅没有选股能力，反而持续产生负向超额' : alphaVal < -0.5 ? '严重跑输基准，存在系统性负 Alpha' : '缺乏选股/择时能力，跑不过被动持有' }。评级：${gAlpha.label}（阈值：≥0.5强 | ≥0.2有 | ≥0微弱 | ≥-0.2无 | ≥-0.5深度负 | <-0.5严重负）` },
    { metric: 'Alpha贡献占比', stock: fmtPct2(report.alphaContribution), benchmark: '-', excess: '-',
      interp: `=|α|/(|α|+|β×${fmtPct2(report.benchmarkAnnualReturn)}|)=${(+contribAlpha * 100).toFixed(1)}%。说明收益偏离中${(+contribAlpha * 100).toFixed(0)}%来自策略自身的Alpha（${alphaSignDesc}），${(+contribBeta * 100).toFixed(0)}%来自市场Beta对基准的偏离。评级：${gAlphaContrib.label}（阈值：≥70%主导 | ≥50%为主 | ≥30%均衡 | <30%Beta依赖）` },
  ];

  return (
    <div>
      {/* 指标卡片 */}
      <Card size="small" style={{ marginBottom: 16, background: '#fafafa' }}>
        <Row gutter={8}>
          {excessMetrics.map((m, i) => {
            const val = m.value;
            const g = val != null ? m.grade(+val) : null;
            const level = g ? g.level : 2;
            const color = gradeColor(level);
            return (
              <Col key={i} style={{ textAlign: 'center', padding: '4px 8px', borderRight: i < excessMetrics.length - 1 ? '1px solid #e8e8e8' : 'none' }}>
                <div style={{ fontSize: 12, color: '#888' }}>{m.label}</div>
                <div style={{ fontSize: 14, fontWeight: 600, color }}>{m.fmt(val)}</div>
              </Col>
            );
          })}
        </Row>
      </Card>

      {/* 对比分析表 */}
      <Card size="small" title="策略 vs 基准：超额收益深度分析">
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
          <thead>
            <tr style={{ background: '#fafafa' }}>
              <th style={{ padding: '8px 12px', textAlign: 'left', borderBottom: '2px solid #e8e8e8' }}>指标</th>
              <th style={{ padding: '8px 12px', textAlign: 'right', borderBottom: '2px solid #e8e8e8' }}>策略收益</th>
              <th style={{ padding: '8px 12px', textAlign: 'right', borderBottom: '2px solid #e8e8e8' }}>基准</th>
              <th style={{ padding: '8px 12px', textAlign: 'right', borderBottom: '2px solid #e8e8e8' }}>超额</th>
              <th style={{ padding: '8px 12px', textAlign: 'left', borderBottom: '2px solid #e8e8e8' }}>解读</th>
            </tr>
          </thead>
          <tbody>
            {compareRows.map((row, i) => (
              <tr key={i} style={{ borderBottom: '1px solid #f0f0f0' }}>
                <td style={{ padding: '8px 12px', fontWeight: 500 }}>{row.metric}</td>
                <td style={{ padding: '8px 12px', textAlign: 'right', color: signCol(report.annualReturn) }}>{row.stock}</td>
                <td style={{ padding: '8px 12px', textAlign: 'right', color: '#888' }}>{row.benchmark}</td>
                <td style={{ padding: '8px 12px', textAlign: 'right', color: signCol(report.excessMean) }}>{row.excess}</td>
                <td style={{ padding: '8px 12px', color: '#666', fontSize: 12 }}>{row.interp}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>
    </div>
  );
}

// ─── 主组件 ──────────────────────────────────────────────────────────────────
export default function BacktestReport() {
  const { taskId } = useParams();
  const navigate = useNavigate();
  const [task, setTask] = useState(null);
  const [report, setReport] = useState(null);
  const [loading, setLoading] = useState(true);
  const [polling, setPolling] = useState(false);

  const load = useCallback(() => {
    backtestApi.getTask(taskId).then(res => {
      setTask(res);
      if (res.status === 'COMPLETED') {
        return backtestApi.getReport(taskId).then(r => setReport(r));
      }
    }).finally(() => { setLoading(false); setPolling(false); });
  }, [taskId]);

  const handleRerun = () => {
    backtestApi.rerun(taskId).then(() => {
      message.success('已重新提交，回测正在执行...');
      setReport(null);
      load();
    }).catch(() => message.error('重跑失败，请稍后重试'));
  };

  useEffect(() => { load(); }, [taskId]);

  useEffect(() => {
    if (!task) return;
    if (task.status === 'RUNNING' || task.status === 'PENDING') {
      const timer = setInterval(() => { setPolling(true); load(); }, 5000);
      return () => clearInterval(timer);
    }
  }, [task, load]);

  if (loading) {
    return (
      <div style={{ textAlign: 'center', padding: 80 }}>
        <Spin size="large" tip="加载回测报告...">
          <div />
        </Spin>
      </div>
    );
  }

  if (!task) {
    return (
      <div style={{ padding: 24 }}>
        <Alert type="info" showIcon message="加载中..." description={<Spin size="small" />} />
      </div>
    );
  }

  if (task.status === 'FAILED') {
    return (
      <div style={{ padding: 24 }}>
        <Alert
          type="error" showIcon
          message="回测失败"
          description={task.errorMessage || '未知错误，请检查后端日志'}
        />
      </div>
    );
  }

  const tabItems = report ? [
    {
      key: 'overview',
      label: '收益概览',
      children: (
        <>
          <MetricBar report={report} />
          <Card title="策略收益 vs 基准收益" size="small">
            <MainChart
              equityCurveJson={report?.equityCurveJson}
              benchmarkCurveJson={report?.benchmarkCurveJson}
              drawdownSeriesJson={report?.drawdownSeriesJson}
              maxDrawdown={report?.maxDrawdown}
              realizedCurveJson={report?.realizedCurveJson}
            />
          </Card>
        </>
      ),
    },
    {
      key: 'monthly',
      label: '月度收益',
      children: (
        <Card title="月度收益热力图" size="small">
          <MonthlyHeatmap monthlyReturnsJson={report?.monthlyReturnsJson} />
        </Card>
      ),
    },
    {
      key: 'trades',
      label: '交易明细',
      children: (
        <Card title="交易记录" size="small">
          <TradeTable tradeLogJson={report?.tradeLogJson} />
        </Card>
      ),
    },
    {
      key: 'position',
      label: '持仓过程',
      children: (
        <Card title="持仓过程点位图" size="small">
          <PositionProcessChart
            equityCurveJson={report?.equityCurveJson}
            tradeLogJson={report?.tradeLogJson}
            positionHistoryJson={report?.positionHistoryJson}
          />
        </Card>
      ),
    },
    {
      key: 'attribution-hub',
      label: <>归因分析  <AntTooltip styles={{ root: { maxWidth: 460 } }} title={<div style={{ lineHeight: 1.8, fontSize: 13 }}>
          <div style={{ fontWeight: 700, marginBottom: 4 }}>归因分析 Hub</div>
          <div style={{ marginBottom: 6, color: '#8c8c8c', fontSize: 12 }}>
            统一归因分析入口，自动对比<b> Brinson 行业归因</b>与<b> 因子风格归因</b>两种模型，
            推荐解释力更高的方案。同时提供<b>持仓周期分析</b>和<b>关键交易透视</b>。
          </div>
          <div style={{ marginTop: 6, borderTop: '1px solid #f0f0f0', paddingTop: 4 }}>
            <b>包含四个维度：</b>
          </div>
          <div>· <b>归因对比</b> — Brinson vs 因子，自动选解释力高的推荐</div>
          <div>· <b>持仓周期分析</b> — 不同持有天数盈亏分布，定位最优持仓周期</div>
          <div>· <b>关键交易透视</b> — 帕累托分析，找出贡献 90% 利润的少数交易</div>
          <div>· <b>归因详情</b> — Brinson 三效应拆解 + 因子 β 暴露回归（可折叠展开）</div>
          <div style={{ marginTop: 4 }}>
            <b>联动关系：</b>若两种归因解释力均低（&lt;15%），说明收益不由行业或风格驱动，
            需通过持仓周期分析和关键交易透视找到真实收益来源。
          </div>
        </div>}> <QuestionCircleOutlined style={{ color: '#8c8c8c', fontSize: 12 }} /></AntTooltip></>,
      children: <AttributionHub taskId={taskId} />,
    },
    {
      key: 'excess',
      label: <><LineChartOutlined />Alpha 分析 <AntTooltip styles={{ root: { maxWidth: 420 } }} title={<div style={{ lineHeight: 1.8, fontSize: 13 }}>
          <div style={{ fontWeight: 700, marginBottom: 4 }}>CAPM 超额收益拆解</div>
          <div>将策略收益分离为两部分：</div>
          <div>· <b>Alpha</b> — 独立于市场涨跌的选股/择时收益</div>
          <div>· <b>Beta × 基准收益</b> — 跟随大盘波动带来的收益</div>
          <div style={{ marginTop: 6, borderTop: '1px solid #f0f0f0', paddingTop: 4 }}>
            <b>核心指标解读：</b>
          </div>
          <div>· <b>Alpha 贡献占比</b> → 衡量超额收益中「能力」vs「运气」的比例</div>
          <div>· <b>超额胜率</b> → 跑赢基准的交易日占比，&gt;50% 说明稳定跑赢</div>
          <div>· <b>超额标准差</b> → Alpha 的波动程度，越低说明能力越稳定</div>
          <div>· <b>超额最大回撤</b> → Alpha 持续性的极限测试，回撤小则策略稳健</div>
          <div style={{ marginTop: 4 }}>
            <b>联动关系：</b>Alpha 高但超额标准差也高 → 能力存在但不稳定；超额胜率与超额均值同方向则是可靠的 Alpha。Alpha + 归因选股效应同步为正 → 双重验证选股能力强。
          </div>
        </div>}> <QuestionCircleOutlined style={{ color: '#8c8c8c', fontSize: 12 }} /></AntTooltip></>,
      children: (
        <ExcessAnalysisPanel report={report} />
      ),
    },
    {
      key: 'montecarlo',
      label: <><ExperimentOutlined />蒙特卡洛 <AntTooltip styles={{ root: { maxWidth: 560 }, body: { maxWidth: 560 } }} title={<div style={{ lineHeight: 1.8, fontSize: 13 }}>
          <div style={{ fontWeight: 700, marginBottom: 4 }}>Bootstrap 重采样模拟</div>
          <div>基于历史日收益率随机抽样，生成数千条未来净值路径，评估策略在不同市场环境下的表现分布。</div>
          <div style={{ marginTop: 6, borderTop: '1px solid #f0f0f0', paddingTop: 4 }}>
            <b>核心指标解读：</b>
          </div>
          <div>· <b>正收益概率</b> → 模拟路径中期末盈利的比例，&gt;60% 较可靠</div>
          <div>· <b>VaR（95%）</b> → 95% 置信度下最大可能损失，衡量「正常最坏情况」</div>
          <div>· <b>CVaR（95%）</b> → 超出 VaR 情形下的平均损失，衡量「极端尾部风险」</div>
          <div>· <b>净值置信区间图</b> → P5-P95 灰色带为合理范围，红色虚线为悲观下界</div>
          <div style={{ marginTop: 4 }}>
            <b>联动关系：</b>正收益概率高但 CVaR 也高 → 「赢多输大」，需警惕黑天鹅。最大回撤分布 P95 若超过 Alpha 分析中的超额最大回撤，说明历史最坏情况并非最差可能。结合 Calmar 比率可评估风险调整后收益是否稳健。
          </div>
          <div style={{ marginTop: 6, borderTop: '1px solid #f0f0f0', paddingTop: 4 }}>
            <b>参数说明：</b>
          </div>
          <div>· <b>模拟次数</b> → 生成的随机路径数量。越多越接近理论分布，但计算越慢。一般 500 次已足够收敛。</div>
          <div>· <b>预测期</b> → 每条路径的模拟时长（1年/2年/3年）。只改变路径长度，不改变单笔采样逻辑。注意：预测期越长，尾部风险暴露越充分。</div>
        </div>}> <QuestionCircleOutlined style={{ color: '#8c8c8c', fontSize: 12 }} /></AntTooltip></>,
      children: (
        <MonteCarloPanel taskId={taskId} />
      ),
    },
  ] : [];

  return (
    <div>
      {/* 页头 */}
      <div className="page-header" style={{ marginBottom: 16 }}>
        <Space>
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/backtests')}>返回</Button>
          <Title level={4} style={{ margin: 0 }}>
            <FundOutlined style={{ color: '#1677ff', marginRight: 8 }} />
            回测报告
          </Title>
          <Tag color="blue">{report?.strategyCode}</Tag>
          {polling && <Spin size="small" />}
        </Space>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load} loading={polling}>刷新</Button>
          <Popconfirm title="将清空旧结果并重新执行，确认重跑？" onConfirm={handleRerun}>
            <Button icon={<RedoOutlined />}>重跑</Button>
          </Popconfirm>
        </Space>
      </div>

      {/* 基本信息 */}
      <Card size="small" style={{ marginBottom: 16 }}>
        <Row gutter={24}>
          <Col>
            <Text type="secondary">策略名称：</Text>
            {task?.strategyId ? (
              <a onClick={() => navigate(`/strategies/${task.strategyId}`)} style={{ fontWeight: 600, cursor: 'pointer' }}>
                {task?.strategyName || task?.strategyCode || '-'}
              </a>
            ) : (
              <Text strong>{task?.strategyName || task?.strategyCode || '-'}</Text>
            )}
          </Col>
          <Col><Text type="secondary">回测区间：</Text><Text strong>{task?.startDate} ~ {task?.endDate}</Text></Col>
          <Col><Text type="secondary">初始资金：</Text><Text strong>¥{fmtNum(task?.initialCapital)}</Text></Col>
          <Col><Text type="secondary">总交易次数：</Text><Text strong>{report?.totalTrades || 0}</Text></Col>
          <AntTooltip title="盈利交易平均收益 / 亏损交易平均收益的绝对值，越大说明盈利时赚得多、亏损时亏得少">
            <Col><Text type="secondary">盈亏比：</Text><Text strong>{fmt(report?.profitLossRatio, 2)} <span style={{ color: '#bbb', fontSize: 10 }}>ⓘ</span></Text></Col>
          </AntTooltip>
          <AntTooltip title="盈利交易次数 / 总配对交易次数">
            <Col><Text type="secondary">胜率：</Text><Text strong>{fmtPct(report?.winRate)} <span style={{ color: '#bbb', fontSize: 10 }}>ⓘ</span></Text></Col>
          </AntTooltip>
          <AntTooltip title="最大回撤期间持续的最长交易日数">
            <Col><Text type="secondary">最大回撤天数：</Text><Text strong>{report?.maxDrawdownDuration || 0} 天 <span style={{ color: '#bbb', fontSize: 10 }}>ⓘ</span></Text></Col>
          </AntTooltip>
        </Row>
        <Row gutter={24} style={{ marginTop: 10 }}>
          <AntTooltip title="单只股票亏损达到此比例时自动平仓止损，0 = 不启用">
            <Col><Text type="secondary">止损：</Text><Text strong>{task?.stopLossPct != null && task?.stopLossPct > 0 ? fmtPct(task.stopLossPct) : '未启用'}</Text></Col>
          </AntTooltip>
          <AntTooltip title="单只股票盈利达到此比例时自动平仓止盈，0 = 不启用">
            <Col><Text type="secondary">止盈：</Text><Text strong>{task?.stopProfitPct != null && task?.stopProfitPct > 0 ? fmtPct(task.stopProfitPct) : '未启用'}</Text></Col>
          </AntTooltip>
          <AntTooltip title="同时持有的最大股票数量，null/0 = 使用策略默认值（通常20）">
            <Col><Text type="secondary">最大持仓：</Text><Text strong>{task?.maxPositionCount ? `${task.maxPositionCount} 只` : '策略默认值'}</Text></Col>
          </AntTooltip>
        </Row>
      </Card>

      {/* 进度条 */}
      {(task.status === 'RUNNING' || task.status === 'PENDING') && (
        <Card size="small" style={{ marginBottom: 16 }}>
          <div style={{ marginBottom: 8 }}>
            <Text strong>执行进度</Text>
            <Text type="secondary" style={{ float: 'right' }}>
              {task.status === 'PENDING' ? '等待调度...' : `回测进行中 ${task.progress || 0}%`}
            </Text>
          </div>
          <Progress percent={task.progress || 0} status="active" />
        </Card>
      )}

      {/* Tab 切换 */}
      {tabItems.length > 0 && (
        <Tabs items={tabItems} defaultActiveKey="overview" destroyOnHidden />
      )}

      {/* 运行中占位提示 */}
      {(task.status === 'RUNNING' || task.status === 'PENDING') && tabItems.length === 0 && (
        <Card size="small" style={{ textAlign: 'center', padding: 40 }}>
          <Spin size="large" tip="回测计算中，完成后自动显示报告..." />
        </Card>
      )}
    </div>
  );
}
