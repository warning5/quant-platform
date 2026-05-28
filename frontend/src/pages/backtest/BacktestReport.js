import React, { useEffect, useState, useCallback, useMemo } from 'react';
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

/** 归因汇总卡片 */
function AttributionSummary({ summary }) {
  if (!summary) return null;
  const items = [
    { label: '配置效应', value: summary.totalAllocationEffect, tip: '行业权重偏离基准的贡献，正值说明超配了强势行业' },
    { label: '选股效应', value: summary.totalSelectionEffect, tip: '行业内个股选择优于基准的贡献，正值说明选股能力强' },
    { label: '交互效应', value: summary.totalInteractionEffect, tip: '权重与选股的交互贡献' },
    { label: '超额收益', value: summary.totalExcessReturn, tip: '策略收益 - 基准收益' },
    { label: '残差', value: summary.residual, tip: '模型未解释的部分（交易成本、非行业因素等）' },
    { label: '解释力', value: summary.explanationRatio, fmt: v => v != null ? `${(+v * 100).toFixed(1)}%` : '-', tip: '三项效应合计占超额收益的比例' },
  ];

  return (
    <Card size="small" style={{ marginBottom: 16, background: '#fafafa' }}>
      <Row gutter={8}>
        {items.map((m, i) => {
          const val = m.value;
          const color = val != null ? signCol(val) : '#262626';
          const display = m.fmt ? m.fmt(val) : fmtPct(val);
          return (
            <Col key={i} style={{ textAlign: 'center', padding: '4px 8px', borderRight: i < items.length - 1 ? '1px solid #e8e8e8' : 'none' }}>
              <AntTooltip title={m.tip} placement="top">
                <div style={{ fontSize: 12, color: '#888', cursor: 'default' }}>
                  {m.label}
                  {m.tip && <span style={{ marginLeft: 2, color: '#bbb', fontSize: 10 }}>ⓘ</span>}
                </div>
              </AntTooltip>
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
function IndustryAttributionTable({ industrySummary }) {
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
    // 迷你条形图
    {
      title: '贡献分布', dataIndex: 'totalContribution', key: 'bar', width: 180,
      render: (v, record) => {
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
    <Table
      dataSource={industrySummary}
      columns={columns}
      rowKey="industry"
      pagination={false}
      size="small"
      scroll={{ x: 680 }}
    />
  );
}

/** 完整的归因分析面板 */
function AttributionPanel({ taskId }) {
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState(null);

  const loadAttribution = useCallback(() => {
    setLoading(true);
    backtestApi.getAttribution(taskId)
      .then(res => setData(res))
      .catch(err => console.error('归因分析失败:', err))
      .finally(() => setLoading(false));
  }, [taskId]);

  useEffect(() => { loadAttribution(); }, [taskId]);

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
      <Card size="small">
        <Alert type="info" message="归因分析需要行业分类数据，请确认股票信息中包含行业字段" showIcon />
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
          <Card title="累计归因曲线" size="small">
            <AttributionCumulativeChart cumulativeChart={data.cumulativeChart} />
          </Card>
        </Col>
        <Col span={8}>
          <Card title="行业归因汇总" size="small">
            <IndustryAttributionTable industrySummary={data.industrySummary} />
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

  const excessMetrics = [
    { label: '超额收益均值', value: report.excessMean, fmt: v => fmtPct2(v), good: v => v > 0,
      tip: '超额收益的年化均值。>0 表示策略平均每交易日跑赢大盘' },
    { label: '超额收益标准差', value: report.excessStd, fmt: v => fmtPct2(v), good: v => v < 0.15,
      tip: '超额收益的波动程度。越低说明 Alpha 越稳定' },
    { label: '超额收益胜率', value: report.excessWinRate, fmt: v => fmtPct2(v), good: v => v > 0.5,
      tip: '跑赢大盘的交易天数占比。>50% 说明大部分时间优于基准' },
    { label: '超额最大回撤', value: report.excessMaxDrawdown, fmt: v => fmtPct2(v), good: v => v > -0.1,
      tip: '累计超额收益从峰值到谷底的最大跌幅。越小（负得少）说明 Alpha 持续性强' },
    { label: 'Alpha', value: report.alpha, fmt: v => fmt2(v, 2), good: v => v > 0,
      tip: 'CAPM 模型计算的超额收益能力。>0 表示有真正的选股/择时价值' },
    { label: 'Alpha贡献占比', value: report.alphaContribution, fmt: v => v != null ? `${(+v * 100).toFixed(1)}%` : '-', good: v => v > 0.5,
      tip: 'Alpha 占超额收益的比例。越高说明超额收益主要来自选股能力而非市场暴露' },
  ];

  const signCol = (v) => +v > 0 ? '#cf1322' : +v < 0 ? '#3f8600' : '#262626';
  const compareRows = [
    { metric: '均值', stock: fmtPct2(report.annualReturn), benchmark: fmtPct2(report.benchmarkAnnualReturn), excess: fmtPct2(report.excessMean),
      interp: '超额收益均值代表策略剔除市场涨跌（Beta）后，每交易日能稳定跑赢大盘的能力，这是策略的纯 Alpha 能力' },
    { metric: '标准差', stock: fmtPct2(report.volatility), benchmark: '-', excess: fmtPct2(report.excessStd),
      interp: '超额收益波动性更低，说明策略的 Alpha 部分比总收益更稳定，具备独立于大盘的稳健性' },
    { metric: '胜率', stock: fmtPct2(report.winRate), benchmark: '-', excess: fmtPct2(report.excessWinRate),
      interp: `近 ${(report.excessWinRate * 100).toFixed(0)}% 的交易日能跑赢大盘（超额收益为正），这个胜率在低频策略中已属优秀` },
    { metric: '最大回撤', stock: fmtPct2(report.maxDrawdown), benchmark: '-', excess: fmtPct2(report.excessMaxDrawdown),
      interp: '超额回撤远小于总收益回撤，说明策略在市场下跌时抗跌性强，风控能力突出' },
    { metric: '信息比率', stock: fmt2(report.sharpeRatio, 2), benchmark: '-', excess: fmt2(report.informationRatio, 2),
      interp: `衡量相对基准主动管理的能力。${fmt2(report.informationRatio, 2)} ${+report?.informationRatio > 0.5 ? '> 0.5 已属优秀' : ''}` },
  ];

  return (
    <div>
      {/* 指标卡片 */}
      <Card size="small" style={{ marginBottom: 16, background: '#fafafa' }}>
        <Row gutter={8}>
          {excessMetrics.map((m, i) => {
            const val = m.value;
            const isGood = m.good ? (val != null && m.good(+val)) : val != null;
            return (
              <Col key={i} style={{ textAlign: 'center', padding: '4px 8px', borderRight: i < excessMetrics.length - 1 ? '1px solid #e8e8e8' : 'none' }}>
                <AntTooltip title={m.tip} placement="top">
                  <div style={{ fontSize: 12, color: '#888', cursor: 'default' }}>
                    {m.label}<span style={{ marginLeft: 2, color: '#bbb', fontSize: 10 }}>ⓘ</span>
                  </div>
                </AntTooltip>
                <div style={{ fontSize: 14, fontWeight: 600, color: isGood ? '#52c41a' : '#262626' }}>{m.fmt(val)}</div>
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
              <th style={{ padding: '8px 12px', textAlign: 'right', borderBottom: '2px solid #e8e8e8' }}>股票收益</th>
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
      label: <>归因分析 <AntTooltip title="Brinson 模型将超额收益拆解为配置效应（行业权重偏离基准）、选股效应（行业内个股选择能力）、交互效应（权重与选股的协同），帮助判断收益来源是择时还是选股，定位策略优势与短板"> <QuestionCircleOutlined style={{ color: '#8c8c8c', fontSize: 12 }} /></AntTooltip></>,
      children: (
        <AttributionPanel taskId={taskId} />
      ),
    },
    {
      key: 'excess',
      label: <><LineChartOutlined />Alpha 分析 <AntTooltip title="CAPM 框架下分离 Alpha（选股能力）与 Beta（市场暴露），展示超额收益来源拆解：Alpha 贡献 vs 市场贡献，反应策略是否能独立于大盘产生正收益"> <QuestionCircleOutlined style={{ color: '#8c8c8c', fontSize: 12 }} /></AntTooltip></>,
      children: (
        <ExcessAnalysisPanel report={report} />
      ),
    },
    {
      key: 'montecarlo',
      label: <><ExperimentOutlined />蒙特卡洛 <AntTooltip title="基于历史收益分布随机抽样模拟 10,000 条净值路径，计算 VaR/CVaR 尾部风险和收益分布概率区间，评估策略在极端市场下可能面临的最大损失"> <QuestionCircleOutlined style={{ color: '#8c8c8c', fontSize: 12 }} /></AntTooltip></>,
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
      <Tabs items={tabItems} defaultActiveKey="overview" />
    </div>
  );
}
