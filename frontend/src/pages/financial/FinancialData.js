import React, { useEffect, useState, useCallback } from 'react';
import {
  Card, Table, Tag, Space, Typography, Row, Col, Statistic, Select, Spin, Tabs,
  Descriptions, Input, Button, message
} from 'antd';
import {
  SearchOutlined, ReloadOutlined, FundOutlined, RiseOutlined, FallOutlined,
  PieChartOutlined, BarChartOutlined
} from '@ant-design/icons';
import ReactECharts from 'echarts-for-react';
import { financialApi, marketApi } from '../../api';

const { Title, Text } = Typography;

// 报告类型映射
const REPORT_TYPE_MAP = { 1: '一季报', 2: '中报', 3: '三季报', 4: '年报' };
const REPORT_TYPE_COLORS = { 1: 'blue', 2: 'cyan', 3: 'orange', 4: 'green' };

// 金额格式化（元 → 亿/万）
function fmtAmount(val, unit = '亿') {
  if (val == null) return '-';
  const n = Number(val);
  if (isNaN(n)) return '-';
  if (unit === '亿') return (n / 1e8).toFixed(2) + '亿';
  if (unit === '万') return (n / 1e4).toFixed(2) + '万';
  return n.toFixed(2);
}

function fmtPct(val) {
  if (val == null) return '-';
  return Number(val).toFixed(2) + '%';
}

function fmtVal(val, decimals = 2) {
  if (val == null) return '-';
  return Number(val).toFixed(decimals);
}

// 利润表列
const incomeColumns = [
  { title: '报告期', dataIndex: 'reportDate', width: 100, render: (v, r) => (
    <span><Tag color={REPORT_TYPE_COLORS[r.reportType]} style={{ marginRight: 4 }}>{REPORT_TYPE_MAP[r.reportType]}</Tag>{v}</span>
  )},
  { title: '营业收入', dataIndex: 'revenue', width: 120, align: 'right', render: v => fmtAmount(v) },
  { title: '营业利润', dataIndex: 'operatingProfit', width: 120, align: 'right', render: v => fmtAmount(v) },
  { title: '利润总额', dataIndex: 'totalProfit', width: 120, align: 'right', render: v => fmtAmount(v) },
  { title: '净利润', dataIndex: 'netProfit', width: 120, align: 'right', render: v => fmtAmount(v) },
  { title: '归母净利润', dataIndex: 'npParentCompanyOwners', width: 120, align: 'right', render: v => fmtAmount(v) },
  { title: '扣非归母', dataIndex: 'deductedNpParentCompany', width: 120, align: 'right', render: v => fmtAmount(v) },
  { title: '基本EPS', dataIndex: 'epsBasic', width: 90, align: 'right', render: v => v != null ? `¥${fmtVal(v)}` : '-' },
  { title: '稀释EPS', dataIndex: 'epsDiluted', width: 90, align: 'right', render: v => v != null ? `¥${fmtVal(v)}` : '-' },
  { title: '综合收益', dataIndex: 'totalComprehensiveIncome', width: 120, align: 'right', render: v => fmtAmount(v) },
];

// 资产负债表列
const balanceColumns = [
  { title: '报告期', dataIndex: 'reportDate', width: 100, render: (v, r) => (
    <span><Tag color={REPORT_TYPE_COLORS[r.reportType]} style={{ marginRight: 4 }}>{REPORT_TYPE_MAP[r.reportType]}</Tag>{v}</span>
  )},
  { title: '总资产', dataIndex: 'totalAssets', width: 120, align: 'right', render: v => fmtAmount(v) },
  { title: '总负债', dataIndex: 'totalLiabilities', width: 120, align: 'right', render: v => fmtAmount(v) },
  { title: '净资产', dataIndex: 'totalEquity', width: 120, align: 'right', render: v => fmtAmount(v) },
  { title: '货币资金', dataIndex: 'cashAndEquivalents', width: 120, align: 'right', render: v => fmtAmount(v) },
  { title: '应收账款', dataIndex: 'accountsReceivable', width: 120, align: 'right', render: v => fmtAmount(v) },
  { title: '存货', dataIndex: 'inventory', width: 120, align: 'right', render: v => fmtAmount(v) },
  { title: '固定资产', dataIndex: 'fixedAssets', width: 120, align: 'right', render: v => fmtAmount(v) },
  { title: '短期借款', dataIndex: 'shortTermBorrowing', width: 110, align: 'right', render: v => fmtAmount(v) },
  { title: '未分配利润', dataIndex: 'undistributedProfit', width: 120, align: 'right', render: v => fmtAmount(v) },
];

// 现金流量表列
const cashflowColumns = [
  { title: '报告期', dataIndex: 'reportDate', width: 100, render: (v, r) => (
    <span><Tag color={REPORT_TYPE_COLORS[r.reportType]} style={{ marginRight: 4 }}>{REPORT_TYPE_MAP[r.reportType]}</Tag>{v}</span>
  )},
  { title: '经营净现金流', dataIndex: 'netOperateCf', width: 130, align: 'right',
    render: v => v != null ? <Text type={v >= 0 ? 'danger' : 'success'}>{fmtAmount(v)}</Text> : '-' },
  { title: '销售收现', dataIndex: 'cashReceivedSales', width: 120, align: 'right', render: v => fmtAmount(v) },
  { title: '投资净现金流', dataIndex: 'netInvestCf', width: 130, align: 'right',
    render: v => v != null ? <Text type={v >= 0 ? 'danger' : 'success'}>{fmtAmount(v)}</Text> : '-' },
  { title: '资本支出', dataIndex: 'cashPaidAcquisition', width: 120, align: 'right', render: v => fmtAmount(v) },
  { title: '筹资净现金流', dataIndex: 'netFinanceCf', width: 130, align: 'right',
    render: v => v != null ? <Text type={v >= 0 ? 'danger' : 'success'}>{fmtAmount(v)}</Text> : '-' },
  { title: '现金净增加', dataIndex: 'netCashIncrease', width: 120, align: 'right', render: v => fmtAmount(v) },
  { title: '期末现金', dataIndex: 'cashAtEnd', width: 120, align: 'right', render: v => fmtAmount(v) },
];

// 财务指标列
const indicatorColumns = [
  { title: '报告期', dataIndex: 'reportDate', width: 100, render: (v, r) => (
    <span><Tag color={REPORT_TYPE_COLORS[r.reportType]} style={{ marginRight: 4 }}>{REPORT_TYPE_MAP[r.reportType]}</Tag>{v}</span>
  )},
  { title: 'ROE(%)', dataIndex: 'roe', width: 90, align: 'right', render: v => fmtPct(v) },
  { title: '毛利率(%)', dataIndex: 'grossProfitMargin', width: 90, align: 'right', render: v => fmtPct(v) },
  { title: '净利率(%)', dataIndex: 'netProfitMargin', width: 90, align: 'right', render: v => fmtPct(v) },
  { title: '营收增速(%)', dataIndex: 'revenueYoy', width: 100, align: 'right',
    render: v => v != null ? <Text type={v >= 0 ? 'danger' : 'success'}><RiseOutlined /> {fmtPct(v)}</Text> : '-' },
  { title: '利润增速(%)', dataIndex: 'netProfitYoy', width: 100, align: 'right',
    render: v => v != null ? <Text type={v >= 0 ? 'danger' : 'success'}><RiseOutlined /> {fmtPct(v)}</Text> : '-' },
  { title: '资产负债率(%)', dataIndex: 'debtToAssetRatio', width: 110, align: 'right', render: v => fmtPct(v) },
  { title: '流动比率', dataIndex: 'currentRatio', width: 90, align: 'right', render: v => fmtVal(v) },
  { title: '存货周转率', dataIndex: 'inventoryTurnover', width: 100, align: 'right', render: v => fmtVal(v) },
  { title: '应收周转天数', dataIndex: 'arTurnoverDays', width: 100, align: 'right', render: v => fmtVal(v, 0) },
  { title: 'EPS(元)', dataIndex: 'epsBasic', width: 90, align: 'right', render: v => v != null ? `¥${fmtVal(v)}` : '-' },
  { title: 'BPS(元)', dataIndex: 'bps', width: 90, align: 'right', render: v => v != null ? `¥${fmtVal(v)}` : '-' },
];

export default function FinancialData() {
  const [selectedCode, setSelectedCode] = useState(null);
  const [searchValue, setSearchValue] = useState('');
  const [searchOptions, setSearchOptions] = useState([]);
  const [searching, setSearching] = useState(false);

  const [overview, setOverview] = useState(null);
  const [overviewLoading, setOverviewLoading] = useState(false);

  const [incomeData, setIncomeData] = useState([]);
  const [balanceData, setBalanceData] = useState([]);
  const [cashflowData, setCashflowData] = useState([]);
  const [indicatorData, setIndicatorData] = useState([]);
  const [trendData, setTrendData] = useState([]);
  const [tableLoading, setTableLoading] = useState(false);

  // 搜索股票
  const handleSearch = useCallback((value) => {
    setSearchValue(value);
    if (!value || value.trim().length < 1) { setSearchOptions([]); return; }
    setSearching(true);
    marketApi.searchSymbols(value.trim(), 30)
      .then(res => {
        setSearchOptions((res || []).map(s => ({
          value: s.code,
          label: `${s.code} ${s.name}`,
        })));
      })
      .catch(() => setSearchOptions([]))
      .finally(() => setSearching(false));
  }, []);

  // 加载概览
  const loadOverview = useCallback(() => {
    if (!selectedCode) return;
    setOverviewLoading(true);
    financialApi.getOverview(selectedCode)
      .then(res => setOverview(res))
      .catch(() => message.error('加载财务概览失败'))
      .finally(() => setOverviewLoading(false));
  }, [selectedCode]);

  // 加载全部表格
  const loadAllData = useCallback(() => {
    if (!selectedCode) return;
    setTableLoading(true);
    Promise.all([
      financialApi.getIncome(selectedCode, 30),
      financialApi.getBalance(selectedCode, 30),
      financialApi.getCashflow(selectedCode, 30),
      financialApi.getIndicator(selectedCode, 30),
      financialApi.getTrend(selectedCode),
    ])
      .then(([inc, bal, cf, ind, trend]) => {
        setIncomeData(inc || []);
        setBalanceData(bal || []);
        setCashflowData(cf || []);
        setIndicatorData(ind || []);
        setTrendData(trend || []);
      })
      .catch(() => message.error('加载财务数据失败'))
      .finally(() => setTableLoading(false));
  }, [selectedCode]);

  useEffect(() => { loadOverview(); }, [loadOverview]);
  useEffect(() => { loadAllData(); }, [loadAllData]);

  // 趋势图配置
  const trendChartOption = React.useMemo(() => {
    if (!trendData.length) return {};
    const dates = trendData.map(d => d.reportDate);
    return {
      tooltip: { trigger: 'axis' },
      legend: { data: ['ROE', '毛利率', '净利率', '营收增速', '利润增速'], top: 0 },
      grid: { left: 50, right: 30, top: 40, bottom: 30 },
      xAxis: { type: 'category', data: dates, axisLabel: { rotate: 30, fontSize: 10 } },
      yAxis: { type: 'value', axisLabel: { formatter: '{value}%' } },
      series: [
        { name: 'ROE', type: 'line', data: trendData.map(d => d.roe), smooth: true, symbol: 'circle', symbolSize: 6, lineStyle: { width: 2 } },
        { name: '毛利率', type: 'line', data: trendData.map(d => d.grossProfitMargin), smooth: true, symbol: 'circle', symbolSize: 4 },
        { name: '净利率', type: 'line', data: trendData.map(d => d.netProfitMargin), smooth: true, symbol: 'circle', symbolSize: 4 },
        { name: '营收增速', type: 'bar', data: trendData.map(d => d.revenueYoy), barMaxWidth: 12, itemStyle: { color: '#1677ff' } },
        { name: '利润增速', type: 'bar', data: trendData.map(d => d.netProfitYoy), barMaxWidth: 12, itemStyle: { color: '#52c41a' } },
      ],
    };
  }, [trendData]);

  // 资产负债结构饼图
  const balancePieOption = React.useMemo(() => {
    if (!balanceData.length) return {};
    const latest = balanceData[0];
    const equity = Number(latest.totalEquity) || 0;
    const liability = Number(latest.totalLiabilities) || 0;
    if (!equity && !liability) return {};
    return {
      tooltip: { formatter: '{b}: {c}亿 ({d}%)' },
      series: [{
        type: 'pie', radius: ['40%', '70%'],
        data: [
          { name: '负债', value: +(liability / 1e8).toFixed(2), itemStyle: { color: '#ff7875' } },
          { name: '净资产', value: +(equity / 1e8).toFixed(2), itemStyle: { color: '#36cfc9' } },
        ],
        label: { formatter: '{b}\n{d}%' },
      }],
    };
  }, [balanceData]);

  // 现金流结构柱状图
  const cfBarOption = React.useMemo(() => {
    if (!cashflowData.length) return {};
    const recent = cashflowData.slice(0, 8).reverse();
    return {
      tooltip: { formatter: p => `${p.seriesName}: ${p.value?.toFixed ? (p.value / 1e8).toFixed(2) : p.value}亿` },
      legend: { data: ['经营活动', '投资活动', '筹资活动'], top: 0 },
      grid: { left: 60, right: 20, top: 40, bottom: 30 },
      xAxis: { type: 'category', data: recent.map(d => d.reportDate), axisLabel: { rotate: 30, fontSize: 10 } },
      yAxis: { type: 'value', axisLabel: { formatter: v => `${(v / 1e8).toFixed(0)}亿` } },
      series: [
        { name: '经营活动', type: 'bar', data: recent.map(d => Number(d.netOperateCf || 0)), barMaxWidth: 16,
          itemStyle: { color: p => p.value >= 0 ? '#cf1322' : '#389e0d' } },
        { name: '投资活动', type: 'bar', data: recent.map(d => Number(d.netInvestCf || 0)), barMaxWidth: 16,
          itemStyle: { color: p => p.value >= 0 ? '#cf1322' : '#389e0d' } },
        { name: '筹资活动', type: 'bar', data: recent.map(d => Number(d.netFinanceCf || 0)), barMaxWidth: 16,
          itemStyle: { color: p => p.value >= 0 ? '#cf1322' : '#389e0d' } },
      ],
    };
  }, [cashflowData]);

  const descItems = overview ? [
    { label: '报告期', children: overview.reportDate || '-' },
    { label: 'ROE', children: overview.roe != null ? `${overview.roe}%` : '-' },
    { label: '毛利率', children: overview.grossProfitMargin != null ? `${overview.grossProfitMargin}%` : '-' },
    { label: '净利率', children: overview.netProfitMargin != null ? `${overview.netProfitMargin}%` : '-' },
    { label: '营收增速', children: overview.revenueYoy != null
      ? <Text type={overview.revenueYoy >= 0 ? 'danger' : 'success'}>{overview.revenueYoy}%</Text> : '-' },
    { label: '利润增速', children: overview.netProfitYoy != null
      ? <Text type={overview.netProfitYoy >= 0 ? 'danger' : 'success'}>{overview.netProfitYoy}%</Text> : '-' },
    { label: '资产负债率', children: overview.debtToAssetRatio != null ? `${overview.debtToAssetRatio}%` : '-' },
    { label: '流动比率', children: fmtVal(overview.currentRatio) },
    { label: 'EPS', children: overview.epsBasic != null ? `¥${overview.epsBasic}` : '-' },
    { label: '每股净资产', children: overview.bps != null ? `¥${overview.bps}` : '-' },
    { label: '存货周转率', children: fmtVal(overview.inventoryTurnover) },
    { label: '应收周转天数', children: overview.arTurnoverDays ? `${overview.arTurnoverDays}天` : '-' },
  ] : [];

  const tabItems = [
    {
      key: 'indicator',
      label: <><FundOutlined /> 财务指标</>,
      children: (
        <Card size="small">
          <Table dataSource={indicatorData} columns={indicatorColumns} rowKey="reportDate"
                 size="small" loading={tableLoading} scroll={{ x: 1200 }} pagination={false} />
        </Card>
      ),
    },
    {
      key: 'income',
      label: <><PieChartOutlined /> 利润表</>,
      children: (
        <Card size="small">
          <Table dataSource={incomeData} columns={incomeColumns} rowKey="reportDate"
                 size="small" loading={tableLoading} scroll={{ x: 1100 }} pagination={false} />
        </Card>
      ),
    },
    {
      key: 'balance',
      label: <><BarChartOutlined /> 资产负债表</>,
      children: (
        <Card size="small">
          <Table dataSource={balanceData} columns={balanceColumns} rowKey="reportDate"
                 size="small" loading={tableLoading} scroll={{ x: 1100 }} pagination={false} />
        </Card>
      ),
    },
    {
      key: 'cashflow',
      label: <><BarChartOutlined /> 现金流量表</>,
      children: (
        <Card size="small">
          <Table dataSource={cashflowData} columns={cashflowColumns} rowKey="reportDate"
                 size="small" loading={tableLoading} scroll={{ x: 950 }} pagination={false} />
        </Card>
      ),
    },
  ];

  return (
    <div>
      <div className="page-header">
        <Title level={4} style={{ margin: 0 }}>财务数据</Title>
      </div>

      {/* 搜索栏 */}
      <Card style={{ marginBottom: 16 }}>
        <Space wrap>
          <Text strong>选择股票：</Text>
          <Select
            value={selectedCode}
            onChange={v => setSelectedCode(v)}
            style={{ width: 280 }}
            showSearch
            onSearch={handleSearch}
            filterOption={false}
            options={searchOptions}
            loading={searching}
            placeholder="输入代码或名称搜索"
            notFoundContent={searching ? <Spin size="small" /> : '无匹配'}
            allowClear
          />
          {selectedCode && (
            <Button icon={<ReloadOutlined />} onClick={() => { loadOverview(); loadAllData(); }}>刷新数据</Button>
          )}
        </Space>
      </Card>

      {!selectedCode ? (
        <div style={{ textAlign: 'center', padding: 80, color: '#bfbfbf' }}>
          <FundOutlined style={{ fontSize: 48 }} />
          <div style={{ marginTop: 12, fontSize: 16 }}>请先选择一只股票查看财务数据</div>
        </div>
      ) : (
        <>
          {/* 概览卡片 */}
          {overviewLoading ? <div style={{ textAlign: 'center', padding: 40 }}><Spin /></div> : overview && (
            <>
              <Row gutter={[12, 12]} style={{ marginBottom: 16 }}>
                {[
                  { label: 'ROE', value: overview.roe, suffix: '%', color: '#1677ff' },
                  { label: '毛利率', value: overview.grossProfitMargin, suffix: '%', color: '#722ed1' },
                  { label: '净利率', value: overview.netProfitMargin, suffix: '%', color: '#13c2c2' },
                  { label: '营收增速', value: overview.revenueYoy, suffix: '%',
                    color: overview.revenueYoy >= 0 ? '#cf1322' : '#389e0d' },
                  { label: '利润增速', value: overview.netProfitYoy, suffix: '%',
                    color: overview.netProfitYoy >= 0 ? '#cf1322' : '#389e0d' },
                  { label: '资产负债率', value: overview.debtToAssetRatio, suffix: '%', color: '#fa8c16' },
                ].map(item => (
                  <Col key={item.label} xs={12} sm={8} md={4}>
                    <Card size="small">
                      <Statistic title={item.label} value={item.value || '-'}
                                suffix={item.suffix}
                                valueStyle={{ color: item.value != null ? item.color : undefined, fontWeight: 600 }} />
                    </Card>
                  </Col>
                ))}
              </Row>

              <Descriptions bordered size="small" column={4} style={{ marginBottom: 16 }}
                             title={`${overview.name || selectedCode} · 最新财务指标`}>
                {descItems.map(d => (
                  <Descriptions.Item key={d.label} label={d.label}>{d.children}</Descriptions.Item>
                ))}
              </Descriptions>
            </>
          )}

          {/* 图表区域 */}
          <Row gutter={[12, 12]} style={{ marginBottom: 16 }}>
            <Col xs={24} lg={14}>
              <Card title="财务指标趋势" size="small">
                {trendData.length > 0
                  ? <ReactECharts option={trendChartOption} style={{ height: 320 }} />
                  : <div style={{ textAlign: 'center', padding: 60, color: '#bfbfbf' }}>暂无趋势数据</div>}
              </Card>
            </Col>
            <Col xs={24} lg={10}>
              <Tabs items={[
                {
                  key: 'pie',
                  label: '资产负债结构',
                  children: balanceData.length > 0
                    ? <ReactECharts option={balancePieOption} style={{ height: 320 }} />
                    : <div style={{ textAlign: 'center', padding: 60, color: '#bfbfbf' }}>暂无数据</div>,
                },
                {
                  key: 'cf',
                  label: '现金流结构',
                  children: cashflowData.length > 0
                    ? <ReactECharts option={cfBarOption} style={{ height: 320 }} />
                    : <div style={{ textAlign: 'center', padding: 60, color: '#bfbfbf' }}>暂无数据</div>,
                },
              ]} />
            </Col>
          </Row>

          {/* 表格区域 */}
          <Tabs items={tabItems} />
        </>
      )}
    </div>
  );
}
