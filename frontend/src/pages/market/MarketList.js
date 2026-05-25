import React, { useEffect, useState, useCallback, useRef } from 'react';
import {
  Card, Table, Tag, Button, Select, Space, Typography, Row, Col,
  Statistic, DatePicker, Spin, Tabs, Tooltip, Badge, message, Input
} from 'antd';
import {
  ReloadOutlined, StockOutlined, RiseOutlined, FallOutlined,
  BarChartOutlined, LineChartOutlined, SearchOutlined, QuestionCircleOutlined
} from '@ant-design/icons';
import ReactECharts from 'echarts-for-react';
import dayjs from 'dayjs';
import { marketApi, silentConfig } from '../../api';

const { Title, Text } = Typography;
const { RangePicker } = DatePicker;

export default function MarketList() {
  const [overview, setOverview]             = useState(null);
  const [overviewLoading, setOvLoading]     = useState(true);

  // 个股K线
  const [selectedSymbol, setSelected]       = useState(null);
  const [searchValue, setSearchValue]       = useState('');
  const [searchOptions, setSearchOptions]   = useState([]);
  const [searching, setSearching]           = useState(false);
  const [klineData, setKlineData]           = useState([]);
  const [klineLoading, setKlineLoading]     = useState(false);
  const [dateRange, setDateRange]           = useState([
    dayjs().subtract(1, 'year'), dayjs()
  ]);

  // 截面分页
  const [crossData, setCrossData]           = useState([]);
  const [crossTotal, setCrossTotal]         = useState(0);
  const [crossPage, setCrossPage]           = useState(1);
  const [crossSize, setCrossSize]           = useState(20);
  const [crossLoading, setCrossLoading]     = useState(false);
  const [crossKeyword, setCrossKeyword]     = useState('');
  const [crossSortField, setCrossSortField] = useState('pctChg');
  const [crossSortOrder, setCrossSortOrder] = useState('desc');
  const searchTimer = useRef(null);

  // ── 加载概览 ──
  const loadOverview = useCallback(() => {
    setOvLoading(true);
    marketApi.getOverview()
      .then(res => {
        setOverview(res);
      })
      .catch(() => message.error('行情数据加载失败，请稍后重试'))
      .finally(() => setOvLoading(false));
  }, []);

  useEffect(() => { loadOverview(); }, []);

  // ── 远程搜索股票 ──
  const handleSearch = useCallback((value) => {
    setSearchValue(value);
    if (searchTimer.current) clearTimeout(searchTimer.current);
    if (!value || value.trim().length < 1) { setSearchOptions([]); return; }
    setSearching(true);
    searchTimer.current = setTimeout(() => {
      marketApi.searchSymbols(value.trim(), 20)
        .then(res => {
          setSearchOptions((res || []).map(s => ({
            value: s.symbol,
            label: `${s.symbol} ${s.name}`,
          })));
        })
        .catch(() => setSearchOptions([]))
        .finally(() => setSearching(false));
    }, 300);
  }, []);

  // ── 下拉框展开时自动加载默认热门股票 ──
  const handleDropdownVisibleChange = useCallback((open) => {
    if (open && searchOptions.length === 0) {
      setSearching(true);
      marketApi.searchSymbols('', 50)
        .then(res => {
          setSearchOptions((res || []).map(s => ({
            value: s.symbol,
            label: `${s.symbol} ${s.name}`,
          })));
        })
        .catch(() => setSearchOptions([]))
        .finally(() => setSearching(false));
    }
  }, [searchOptions.length]);

  // ── 加载 K 线 ──
  const loadKline = useCallback(() => {
    if (!selectedSymbol || !dateRange[0] || !dateRange[1]) return;
    setKlineLoading(true);
    marketApi.getBars(selectedSymbol, dateRange[0].format('YYYY-MM-DD'), dateRange[1].format('YYYY-MM-DD'))
      .then(res => setKlineData(res || []))
      .catch(() => setKlineData([]))
      .finally(() => setKlineLoading(false));
  }, [selectedSymbol, dateRange]);

  useEffect(() => { loadKline(); }, [selectedSymbol, dateRange]);

  // ── 加载截面分页 ──
  const loadCrossSection = useCallback((page, keyword, sortField, sortOrder, size) => {
    if (!overview?.latestDate || overview.latestDate === '-') return;
    setCrossLoading(true);
    marketApi.getCrossSection(overview.latestDate, page, size || crossSize, keyword || '', sortField, sortOrder)
      .then(res => {
        const d = res || {};
        setCrossData(d.data || []);
        setCrossTotal(d.total || 0);
        setCrossPage(d.page || 1);
      })
      .catch(() => setCrossData([]))
      .finally(() => setCrossLoading(false));
  }, [overview?.latestDate]);

  useEffect(() => {
    if (overview?.latestDate && overview.latestDate !== '-') {
      loadCrossSection(1, '', 'pctChg', 'desc');
    }
  }, [overview?.latestDate]);

  // ── K 线图 option ──
  const klineOption = React.useMemo(() => {
    if (!klineData.length) return {};
    const dates  = klineData.map(d => d.tradeDate);
    const ohlc   = klineData.map(d => [+d.open, +d.close, +d.low, +d.high]);
    const vols   = klineData.map(d => ({
      value: +(d.vol || 0),
      itemStyle: { color: +d.close >= +d.open ? '#cf1322' : '#389e0d' }
    }));
    const ma5    = buildMA(klineData.map(d => +d.close), 5);
    const ma20   = buildMA(klineData.map(d => +d.close), 20);

    return {
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'cross' },
        formatter: params => {
          const k = params.find(p => p.seriesName === 'K线');
          if (!k) return '';
          // ECharts candlestick: k.data 或 k.value 可能含 dataIndex 前缀
          const raw = k.data || k.value;
          const [o, c, l, h] = Array.isArray(raw) && raw.length > 4 ? raw.slice(-4) : raw;
          const d = params[0].axisValue;
          const vol = vols[k.dataIndex]?.value ?? '-';
          return `<b>${d}</b><br/>开: ${o?.toFixed(2)}&nbsp;&nbsp;收: ${c?.toFixed(2)}<br/>低: ${l?.toFixed(2)}&nbsp;&nbsp;高: ${h?.toFixed(2)}<br/>成交量: ${(vol / 10000).toFixed(0)}万手`;
        },
      },
      legend: { data: ['K线', 'MA5', 'MA20'], top: 4 },
      grid: [
        { left: 60, right: 20, top: 44, bottom: 160 },
        { left: 60, right: 20, top: '72%', bottom: 50 },
      ],
      xAxis: [
        { type: 'category', data: dates, axisLabel: { rotate: 30, fontSize: 10 }, scale: true, boundaryGap: true, gridIndex: 0 },
        { type: 'category', data: dates, show: false, gridIndex: 1 },
      ],
      yAxis: [
        { scale: true, splitLine: { lineStyle: { type: 'dashed' } }, gridIndex: 0 },
        { scale: true, splitLine: { show: false }, gridIndex: 1, axisLabel: { formatter: v => `${(v / 10000).toFixed(0)}万` } },
      ],
      dataZoom: [
        { type: 'inside', xAxisIndex: [0, 1], start: Math.max(0, 100 - Math.round(60 / klineData.length * 100)), end: 100 },
        { type: 'slider', xAxisIndex: [0, 1], height: 20, bottom: 24 },
      ],
      series: [
        {
          name: 'K线', type: 'candlestick', xAxisIndex: 0, yAxisIndex: 0, data: ohlc,
          itemStyle: { color: '#cf1322', color0: '#389e0d', borderColor: '#cf1322', borderColor0: '#389e0d' },
        },
        { name: 'MA5', type: 'line', xAxisIndex: 0, yAxisIndex: 0, data: ma5, smooth: true, symbol: 'none', lineStyle: { color: '#fa8c16', width: 1.5 } },
        { name: 'MA20', type: 'line', xAxisIndex: 0, yAxisIndex: 0, data: ma20, smooth: true, symbol: 'none', lineStyle: { color: '#722ed1', width: 1.5 } },
        { name: '成交量', type: 'bar', xAxisIndex: 1, yAxisIndex: 1, data: vols, barMaxWidth: 10 },
      ],
    };
  }, [klineData]);

  // ── 涨跌幅 Top/Bottom 柱状图 ──
  const topGainers = overview?.topGainers || [];
  const topLosers  = overview?.topLosers || [];

  const rankChartOption = React.useMemo(() => {
    const gainers = [...topGainers].reverse().map(d => ({
      name: d.name || d.symbol, value: +(d.pctChg || 0).toFixed(2),
      itemStyle: { color: '#cf1322' }
    }));
    const losers = [...topLosers].map(d => ({
      name: d.name || d.symbol, value: +(d.pctChg || 0).toFixed(2),
      itemStyle: { color: '#389e0d' }
    }));
    const all = [...losers, ...gainers];
    return {
      tooltip: { formatter: p => `${p.name}<br/>涨跌幅: ${p.value}%` },
      grid: { left: 80, right: 40, top: 10, bottom: 30 },
      xAxis: { type: 'value', axisLabel: { formatter: v => `${v}%` }, splitLine: { lineStyle: { type: 'dashed' } } },
      yAxis: { type: 'category', data: all.map(d => d.name), axisLabel: { fontSize: 10 } },
      series: [{ type: 'bar', data: all, barMaxWidth: 16, label: { show: true, position: 'right', formatter: p => `${p.value}%`, fontSize: 10 } }],
    };
  }, [topGainers, topLosers]);

  // ── 截面表格列 ──
  const crossSectionColumns = [
    { title: '代码', dataIndex: 'symbol', width: 120, render: v => <Tag color="blue">{v}</Tag> },
    { title: '名称', dataIndex: 'name', width: 90 },
    { title: '收盘价', dataIndex: 'close', width: 90, render: v => v != null ? `¥${(+v).toFixed(2)}` : '-', sorter: true },
    {
      title: '涨跌幅', dataIndex: 'pctChg', width: 100, align: 'right', sorter: true, defaultSortOrder: 'descend',
      render: v => v != null ? (
        <Text strong style={{ color: +v >= 0 ? '#cf1322' : '#389e0d' }}>
          {+v >= 0 ? <RiseOutlined /> : <FallOutlined />} {(+v).toFixed(2)}%
        </Text>
      ) : '-',
    },
    { title: '成交量(万手)', dataIndex: 'vol', width: 110, render: v => v != null ? (+v / 10000).toFixed(2) : '-', align: 'right', sorter: true },
    { title: '成交额(万元)', dataIndex: 'amount', width: 120, render: v => v != null ? (+v).toFixed(0) : '-', align: 'right', sorter: true },
    { title: '换手率', dataIndex: 'turnoverRate', width: 90, render: v => v != null ? `${(+v).toFixed(2)}%` : '-', align: 'right', sorter: true },
  ];

  const handleTableChange = useCallback((pagination, filters, sorter) => {
    const page = pagination.current || 1;
    const size = pagination.pageSize || crossSize;
    const field = sorter.field || 'pctChg';
    const order = sorter.order === 'ascend' ? 'asc' : 'desc';
    if (size !== crossSize) setCrossSize(size);
    setCrossSortField(field);
    setCrossSortOrder(order);
    setCrossPage(page);
    loadCrossSection(page, crossKeyword, field, order, size);
  }, [crossKeyword, crossSize, loadCrossSection]);

  const handleCrossSearch = useCallback((value) => {
    setCrossKeyword(value);
    loadCrossSection(1, value, crossSortField, crossSortOrder);
  }, [crossSortField, crossSortOrder, loadCrossSection]);

  return (
    <div>
      <div className="page-header">
        <Title level={4} style={{ margin: 0 }}>行情数据</Title>
        <Button icon={<ReloadOutlined />} onClick={loadOverview} loading={overviewLoading}>刷新</Button>
      </div>

      {overviewLoading ? (
        <div style={{ textAlign: 'center', padding: 40 }}><Spin /></div>
      ) : (
        <>
          {/* 统计卡片 */}
          <Row gutter={[12, 12]} style={{ marginBottom: 16 }}>
            <Col xs={12} sm={6}>
              <Card size="small">
                <Statistic title="股票数量" value={overview?.symbolCount || 0} prefix={<BarChartOutlined />} valueStyle={{ color: '#1677ff' }} />
              </Card>
            </Col>
            <Col xs={12} sm={6}>
              <Card size="small">
                <Statistic title="最新交易日" value={overview?.latestDate || '-'} valueStyle={{ fontSize: 18 }} />
              </Card>
            </Col>
            <Col xs={12} sm={6}>
              <Card size="small">
                <Statistic title="上涨" value={overview?.riseCount || 0} valueStyle={{ color: '#cf1322' }} suffix={`/ 平${overview?.flatCount || 0}`} prefix={<RiseOutlined />} />
              </Card>
            </Col>
            <Col xs={12} sm={6}>
              <Card size="small">
                <Statistic title="下跌" value={overview?.fallCount || 0} valueStyle={{ color: '#389e0d' }} prefix={<FallOutlined />} />
              </Card>
            </Col>
          </Row>

          {/* 第二行统计 */}
          <Row gutter={[12, 12]} style={{ marginBottom: 16 }}>
            <Col xs={12} sm={8}>
              <Card size="small">
                <Statistic title="平均涨跌幅" value={overview?.avgPctChg || '0.00'} suffix="%" precision={2} />
              </Card>
            </Col>
            <Col xs={12} sm={8}>
              <Card size="small">
                <Statistic title="总成交额" value={overview?.totalAmount || 0} suffix="万元" />
              </Card>
            </Col>
          </Row>

          <Tabs items={[
            /* ── Tab 1: 个股 K 线 ── */
            {
              key: 'kline',
              label: <><LineChartOutlined /> 个股 K 线</>,
              children: (
                <Card>
                  <Space wrap style={{ marginBottom: 16 }}>
                    <Text strong>选择股票：</Text>
                    <Select
                      value={selectedSymbol}
                      onChange={v => setSelected(v)}
                      style={{ width: 260 }}
                      showSearch
                      onSearch={handleSearch}
                      onDropdownVisibleChange={handleDropdownVisibleChange}
                      filterOption={false}
                      options={searchOptions}
                      loading={searching}
                      placeholder="点击选择或输入搜索"
                      notFoundContent={searching ? <Spin size="small" /> : '无匹配'}
                      allowClear
                    />

                    <Text strong>时间区间：</Text>
                    <RangePicker value={dateRange} onChange={v => v && setDateRange(v)} format="YYYY-MM-DD" />
                    <Button type="primary" icon={<ReloadOutlined />} onClick={loadKline} loading={klineLoading}>查询</Button>
                  </Space>

                  {klineLoading ? (
                    <div style={{ textAlign: 'center', padding: 60 }}><Spin size="large" /></div>
                  ) : klineData.length > 0 ? (
                    <>
                      <Row gutter={[12, 0]} style={{ marginBottom: 12 }}>
                        {[
                          { label: '最新收盘', value: `¥${(+klineData[klineData.length - 1].close).toFixed(2)}` },
                          { label: '涨跌幅', value: `${(+klineData[klineData.length - 1].pctChg).toFixed(2)}%`,
                            color: +klineData[klineData.length - 1].pctChg >= 0 ? '#cf1322' : '#389e0d' },
                          { label: '区间最高', value: `¥${Math.max(...klineData.map(d => +d.high)).toFixed(2)}` },
                          { label: '区间最低', value: `¥${Math.min(...klineData.map(d => +d.low)).toFixed(2)}` },
                          { label: '数据条数', value: `${klineData.length} 条` },
                        ].map(item => (
                          <Col key={item.label} xs={12} sm={6} md={4} lg={3}>
                            <div style={{ fontSize: 11, color: '#8c8c8c' }}>{item.label}</div>
                            <div style={{ fontSize: 16, fontWeight: 700, color: item.color || '#262626' }}>{item.value}</div>
                          </Col>
                        ))}
                      </Row>
                      <ReactECharts option={klineOption} style={{ height: 480 }} />
                    </>
                  ) : (
                    <div style={{ textAlign: 'center', padding: 60, color: '#bfbfbf' }}>
                      <BarChartOutlined style={{ fontSize: 40 }} />
                      <div style={{ marginTop: 8 }}>{selectedSymbol ? '该股票在所选区间无数据' : '请先选择股票'}</div>
                    </div>
                  )}
                </Card>
              ),
            },

            /* ── Tab 2: 市场截面 ── */
            {
              key: 'cross',
              label: (
                <>
                  <BarChartOutlined /> 市场截面（{overview?.latestDate || '-'}）
                  <Tooltip
                    title={
                      <div style={{ padding: '4px 0' }}>
                        <div style={{ fontSize: 13, fontWeight: 600, marginBottom: 8, color: '#1890ff' }}>
                          市场截面 — 当日全市场快照
                        </div>
                        <div style={{ fontSize: 12, lineHeight: 2, color: '#333' }}>
                          <div><b>1. 市场情绪感知</b> — 快速查看当日涨跌分布与平均涨跌幅</div>
                          <div><b>2. 异动发现</b> — 涨跌幅 / 成交额 Top 排名定位强势股与弱势股</div>
                          <div><b>3. 数据质量验证</b> — 截面股票数 vs 总数，排查停牌 / 缺失</div>
                          <div><b>4. 选股起点</b> — 排序筛选候选股，点击进入个股深入分析</div>
                        </div>
                      </div>
                    }
                    styles={{ root: { maxWidth: 420 }, body: { padding: '12px 16px' } }}
                    color="#fff"
                  >
                    <QuestionCircleOutlined style={{ marginLeft: 6, color: '#1890ff', cursor: 'pointer', fontSize: 14 }} />
                  </Tooltip>
                </>
              ),
              children: (
                <Row gutter={[12, 12]}>
                  <Col xs={24} lg={10}>
                    <Card title="涨跌幅 Top20 / Bottom20" size="small">
                      {topGainers.length > 0 || topLosers.length > 0
                        ? <ReactECharts option={rankChartOption} style={{ height: Math.max(300, (topGainers.length + topLosers.length) * 24 + 60) }} />
                        : <div style={{ textAlign: 'center', padding: 40, color: '#bfbfbf' }}>暂无数据</div>
                      }
                    </Card>
                  </Col>
                  <Col xs={24} lg={14}>
                    <Card
                      title={`截面行情 · 共 ${crossTotal} 只`}
                      size="small"
                      extra={
                        <Input.Search
                          placeholder="搜索代码或名称"
                          allowClear
                          onSearch={handleCrossSearch}
                          style={{ width: 200 }}
                          enterButton={<SearchOutlined />}
                        />
                      }
                    >
                      <Table
                        dataSource={crossData}
                        columns={crossSectionColumns}
                        rowKey="symbol"
                        size="small"
                        loading={crossLoading}
                        pagination={{
                          current: crossPage,
                          pageSize: crossSize,
                          total: crossTotal,
                          showSizeChanger: true,
                          pageSizeOptions: ['10', '20', '50', '100'],
                          showTotal: t => `共 ${t} 条`,
                        }}
                        onChange={handleTableChange}
                        scroll={{ x: 800 }}
                      />
                    </Card>
                  </Col>
                </Row>
              ),
            },
          ]} />
        </>
      )}
    </div>
  );
}

function buildMA(closes, n) {
  return closes.map((_, i) => {
    if (i < n - 1) return null;
    const sum = closes.slice(i - n + 1, i + 1).reduce((a, b) => a + b, 0);
    return +(sum / n).toFixed(3);
  });
}
