import React, { useEffect, useState, useCallback, useMemo, useRef } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Card, Row, Col, Statistic, Typography, Button, Space, Spin, Tabs,
  Tag, Table, Alert, Badge, Tooltip as AntTooltip,
} from 'antd';
import {
  ArrowLeftOutlined, ReloadOutlined,
  RiseOutlined, FallOutlined, BarChartOutlined,
  PieChartOutlined, LineChartOutlined,
  SwapOutlined, FundOutlined, ExperimentOutlined,
  QuestionCircleOutlined,
} from '@ant-design/icons';
import ReactECharts from 'echarts-for-react';
import { backtestApi } from '../../api';
import MonteCarloPanel from './MonteCarloPanel';

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

  const option = {
    backgroundColor: 'transparent',
    tooltip: {
      trigger: 'axis',
      formatter: params => {
        const idx = params[0].dataIndex;
        const p = periods[idx];
        return `<div style="font-weight:600">${p.period}</div>
          <div>配置效应: ${fmtPct(p.allocationEffect)}</div>
          <div>选股效应: ${fmtPct(p.selectionEffect)}</div>
          <div>交互效应: ${fmtPct(p.interactionEffect)}</div>
          <div>超额收益: ${fmtPct(p.excessReturn)}</div>`;
      },
    },
    legend: {
      data: ['配置效应', '选股效应', '交互效应', '超额收益'],
      top: 4,
      textStyle: { color: '#666' },
    },
    grid: { left: 56, right: 16, top: 40, bottom: 60 },
    xAxis: {
      type: 'category',
      data: periods.map(p => p.period?.split(' ~ ')[1] || p.startDate),
      axisLabel: { rotate: 45, fontSize: 10, color: '#888' },
    },
    yAxis: {
      type: 'value',
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
        data: periods.map(p => +(p.allocationEffect * 100).toFixed(4)),
        itemStyle: { color: '#1677ff' }, barMaxWidth: 30,
      },
      {
        name: '选股效应', type: 'bar', stack: 'attribution',
        data: periods.map(p => +(p.selectionEffect * 100).toFixed(4)),
        itemStyle: { color: '#52c41a' }, barMaxWidth: 30,
      },
      {
        name: '交互效应', type: 'bar', stack: 'attribution',
        data: periods.map(p => +(p.interactionEffect * 100).toFixed(4)),
        itemStyle: { color: '#fa8c16' }, barMaxWidth: 30,
      },
      {
        name: '超额收益', type: 'bar',
        data: periods.map(p => +(p.excessReturn * 100).toFixed(4)),
        itemStyle: { color: params => params.value >= 0 ? '#cf1322' : '#3f8600' },
        barMaxWidth: 30,
      },
    ],
  };
  return <ReactECharts option={option} style={{ height: 280 }} />;
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
  const topContributors = [...inds].sort((a, b) => -((+b.totalContribution || 0) - (+a.totalContribution || 0))).slice(0, 3);
  const worstContributors = [...inds].sort((a, b) => (+a.totalContribution || 0) - (+b.totalContribution || 0)).slice(0, 3);
  // 选股拖累最严重的行业
  const worstSelectors = [...inds].sort((a, b) => (+a.totalSelection || 0) - (+b.totalSelection || 0)).slice(0, 3);
  // 配置拖累最严重的行业
  const worstAllocators = [...inds].sort((a, b) => (+a.totalAllocation || 0) - (+b.totalAllocation || 0)).slice(0, 3);

  // 构建行业建议文本
  var indSummaryText = '';
  if (hasIndData) {
    var parts = [];
    if (topContributors.length > 0 && +topContributors[0].totalContribution > 0.001) {
      parts.push('[贡献] ' + topContributors.map(function(x) {
        return x.industry + '(+' + fmtPctV(x.totalContribution) + ')';
      }).join(' / '));
    }
    if (worstContributors.length > 0 && +worstContributors[0].totalContribution < -0.001) {
      parts.push('[拖累] ' + worstContributors.map(function(x) {
        return x.industry + '(' + fmtPctV(x.totalContribution) + ')';
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

  // === 数据驱动结论生成 ===
  var scenario = '';
  var verdict = '';
  var verdictColor = '#262626';
  var suggestions = [];
  var summaryLines = [];

  // 1. 超额方向与幅度
  if (Math.abs(excess) < 0.005) {
    scenario = '超额收益接近零';
    verdictColor = '#8c8c8c';
    verdict = '策略收益与基准持平，行业归因参考价值有限。';
    suggestions.push('降低候选池宽度（Top% 缩小），加大主动偏离幅度以获取超额。');
  } else if (excess > 0) {
    scenario = '超额为正 (' + fmtPctV(excess) + ')';
    verdictColor = posCount >= 2 ? '#52c41a' : '#fa8c16';
    verdict = posCount >= 2
      ? '策略有效：' + effects.filter(function(e) { return e.value > 0.005; }).map(function(e) { return e.name; }).join('、') + '正向贡献，' + (hasIndData ? (topContributors[0]?.industry || '') + '行业贡献最大。' : '')
      : '超额仅靠' + dominant.name + '(' + fmtPctV(dominant.value) + ')单项驱动，风险集中、缺乏冗余。';
  } else {
    scenario = '超额为负 (' + fmtPctV(excess) + ')';
    verdictColor = allNeg ? '#cf1322' : '#fa8c16';
    verdict = allNeg
      ? '三效应全线亏损，策略在回测期内全面失效。'
      : dominant.name + '(' + fmtPctV(dominant.value) + ')是最大亏损来源，占比 ' + (Math.abs(excess) > 1e-8 ? (Math.abs(dominant.value) / Math.abs(excess) * 100).toFixed(0) : 0) + '%。';
  }

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
    var highConc = worstContributors.filter(function(x) { return Math.abs(+x.totalContribution || 0) > 0.02; });
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

  return (
    <Card
      size="small"
      style={{ marginTop: 16, borderLeft: `3px solid ${verdictColor}` }}
      title={<span style={{ fontSize: 14 }}>图形分析结论</span>}
    >
      {/* Brinson 恒等式 */}
      <div style={{ background: '#fafafa', padding: 12, borderRadius: 4, marginBottom: 12 }}>
        <div style={{ fontWeight: 700, marginBottom: 8, fontSize: 13, color: '#555' }}>Brinson 恒等式</div>
        <div style={{ fontFamily: 'monospace', fontSize: 13, lineHeight: 2.2 }}>
          <div style={{ color: '#888', marginBottom: 2 }}>
            单期恒等式严格成立：超额收益 = 配置 + 选股 + 交互
          </div>
          <div style={{ fontWeight: 600, color: excess >= 0 ? '#cf1322' : '#3f8600' }}>
            {fmtPctV(excess)} = {fmtSigned(alloc)} + {fmtSigned(select)} + {fmtSigned(interaction)}
          </div>
          <div style={{ marginTop: 4, color: Math.abs(residual) < 0.001 ? '#52c41a' : '#d48806' }}>
            残差 = {fmtSigned(residual)}（{(Math.abs(residual) > 1e-8 && Math.abs(excess) > 1e-8) ? (Math.abs(residual) / Math.abs(excess) * 100).toFixed(2) + '%' : '≈0'}）
          </div>
          <div style={{ marginTop: 4, color: netExcess >= 0 ? '#52c41a' : '#fa541c' }}>
            净超额 = {fmtSigned(excess)} − {(cost * 100).toFixed(2)}% = {fmtSigned(netExcess)}
          </div>
        </div>
      </div>

      {/* 情景判断 */}
      <div style={{ marginBottom: 8 }}>
        <Tag color={verdictColor === '#52c41a' ? 'success' : verdictColor === '#fa8c16' ? 'warning' : verdictColor === '#8c8c8c' ? 'default' : 'error'}>
          {scenario}
        </Tag>
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

      {/* 核心结论 */}
      <div style={{
        background: '#fffbe6', padding: '8px 12px', borderRadius: 4,
        border: '1px solid #ffe58f', marginBottom: 12, fontSize: 13, lineHeight: 1.8
      }}>
        <span style={{ fontWeight: 700 }}>核心结论：</span>{verdict}
      </div>

      {/* 第一层：可立即操作 */}
      {actionables.length > 0 && (
        <div style={{ marginBottom: 12 }}>
          <div style={{ fontWeight: 700, marginBottom: 6, color: '#cf1322', fontSize: 13 }}>
            可立即操作（策略编辑页直接改）
          </div>
          {actionables.map(function(a, i) {
            return <div key={'act-' + i} style={{
              background: '#fff2f0', padding: '8px 10px', borderRadius: 4,
              border: '1px solid #ffccc7', marginBottom: 6, fontSize: 12, lineHeight: 1.7
            }}>
              <div style={{ fontWeight: 700, marginBottom: 2 }}>{a.label}</div>
              <div style={{ whiteSpace: 'pre-wrap', color: '#434343' }}>{a.body}</div>
            </div>;
          })}
        </div>
      )}

      {/* 第二层：验证步骤 */}
      {verifications.length > 0 && (
        <div style={{ marginBottom: 12 }}>
          <div style={{ fontWeight: 700, marginBottom: 6, color: '#0958d9', fontSize: 13 }}>
            如何验证修复有效
          </div>
          {verifications.map(function(v, i) {
            return <div key={'ver-' + i} style={{
              background: '#e6f4ff', padding: '6px 10px', borderRadius: 4,
              border: '1px solid #91caff', marginBottom: 4, fontSize: 12, lineHeight: 1.7
            }}>
              <span style={{ fontWeight: 600 }}>{v.label}：</span>
              <span style={{ color: '#434343' }}>{v.body}</span>
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

      {/* 无建议时的兜底 */}
      {actionables.length === 0 && missingCaps.length === 0 && (
        <div style={{ color: '#8c8c8c', fontSize: 12, padding: '4px 0' }}>
          当前归因结果良好，无需调整。
        </div>
      )}
    </Card>
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

  if (loading) {
    return (
      <div style={{ textAlign: 'center', padding: 60 }}>
        <Spin tip="正在计算 Brinson 归因分析...">
          <div />
        </Spin>
      </div>
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

      <AttributionSummary summary={data.summary} />

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

  if (!task || task.status !== 'COMPLETED') {
    return (
      <div style={{ padding: 24 }}>
        <Alert
          type={task?.status === 'FAILED' ? 'error' : 'info'}
          showIcon
          message={task?.status === 'FAILED' ? '回测失败' : '回测未完成'}
          description={
            <Space>
              <span>当前状态: {task?.status}</span>
              {task?.status !== 'FAILED' && <Spin size="small" />}
            </Space>
          }
        />
      </div>
    );
  }

  const tabItems = [
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
      key: 'attribution',
      label: <>Brinson归因 <AntTooltip styles={{ root: { maxWidth: 420 } }} title={<div style={{ lineHeight: 1.8, fontSize: 13 }}>
          <div style={{ fontWeight: 700, marginBottom: 4 }}>Brinson 归因模型（行业维度）</div>
          <div style={{ marginBottom: 6, color: '#8c8c8c', fontSize: 12 }}>
            Brinson 模型基于<b>行业分类</b>分析超额收益来源。它假设收益差异来自两个层面：<b>行业配置</b>（是否超配了强势行业）和<b>行业内选股</b>（是否挑出了行业中的佼佼者）。若策略收益主要由非行业因子驱动（如市值/动量/事件），三效应解释力会偏低。
          </div>
          <div>将超额收益拆解为三部分：</div>
          <div>· <b>配置效应</b> — 行业权重偏离基准带来的收益，判断择时能力</div>
          <div>· <b>选股效应</b> — 行业内个股选择带来的收益，判断选股能力</div>
          <div>· <b>交互效应</b> — 配置与选股的协同效应</div>
          <div style={{ marginTop: 6, borderTop: '1px solid #f0f0f0', paddingTop: 4 }}>
            <b>如何解读：</b>三效应之和 = 总超额收益。配置正 → 行业择时好；选股正 → 个股挑选强；两者同正则策略全面优秀。
          </div>
          <div style={{ marginTop: 4 }}>
            <b>联动关系：</b>若 Alpha 分析中 Alpha 为正但归因中选股效应为负，说明超额收益可能来自市场 Beta 暴露而非个股能力。
          </div>
        </div>}> <QuestionCircleOutlined style={{ color: '#8c8c8c', fontSize: 12 }} /></AntTooltip></>,
      children: (
        <AttributionPanel taskId={taskId} />
      ),
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
      label: <><ExperimentOutlined />蒙特卡洛 <AntTooltip styles={{ root: { maxWidth: 420 } }} title={<div style={{ lineHeight: 1.8, fontSize: 13 }}>
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
        </div>}> <QuestionCircleOutlined style={{ color: '#8c8c8c', fontSize: 12 }} /></AntTooltip></>,
      children: (
        <MonteCarloPanel taskId={taskId} />
      ),
    },
  ];

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

      {/* Tab 切换 */}
      <Tabs items={tabItems} defaultActiveKey="overview" destroyOnHidden />
    </div>
  );
}
