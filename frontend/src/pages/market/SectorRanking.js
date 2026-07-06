import React, { useState, useEffect } from 'react';
import { Card, Table, Tag, Spin, Empty, Typography, Tabs, Statistic, Row, Col, Button, Select, Modal, Space } from 'antd';
import { ArrowUpOutlined, ArrowDownOutlined, StockOutlined, ArrowLeftOutlined, RocketOutlined } from '@ant-design/icons';
import ReactECharts from '../../components/LazyECharts';
import { stockAnalysisApi } from '../../api';

const { Title, Text } = Typography;

// 格式化市值（元→亿元）
const formatCap = (v) => {
  if (v == null) return '-';
  const num = Number(v);
  if (isNaN(num)) return '-';
  return (num / 1e8).toFixed(1) + '亿';
};

// 涨跌幅颜色渲染
const renderChangePct = (v) => {
  if (v == null) return '-';
  const n = Number(v);
  return <span style={{ color: n >= 0 ? '#f5222d' : '#52c41a', fontWeight: 500 }}>{n >= 0 ? '+' : ''}{n.toFixed(2)}%</span>;
};

/* ── 热门板块视图常量与组件（从 HotSectorPage.js 迁移）──────────────── */
const CATEGORY_MAP = {
  '人工智能': '科技', '半导体概念': '科技', '国产芯片': '科技', '算力/AI': '科技',
  '机器人概念': '科技', '人形机器人': '科技', '信创': '科技', '数字经济': '科技', '消费电子概念': '科技',
  '储能概念': '新能源', '光伏概念': '新能源', '新能源车': '新能源', '锂电池概念': '新能源',
  '新能源': '新能源', '氢能源': '新能源', '充电桩': '新能源',
  '军工': '国防', '低空经济': '国防',
  '医疗器械概念': '医药', '创新药': '医药',
};
const CATEGORY_COLORS = {
  '科技': '#1890ff', '新能源': '#52c41a', '国防': '#fa8c16', '医药': '#722ed1',
};
const fmtChg = v => v != null ? `${(+v).toFixed(2)}%` : '-';
const fmtCapHot = v => {
  if (v == null) return '-';
  const cap = +v;
  if (cap >= 1e12) return `${(cap / 1e12).toFixed(1)}万亿`;
  if (cap >= 1e8) return `${(cap / 1e8).toFixed(0)}亿`;
  return `${(cap / 1e4).toFixed(0)}万`;
};
const chgColorHot = v => v > 0 ? '#ef5350' : v < 0 ? '#26a69a' : '#999';

function SectorCard({ sector, onClick }) {
  const cat = CATEGORY_MAP[sector.conceptName] || '其他';
  const catColor = CATEGORY_COLORS[cat] || '#999';
  const avgChg = sector.avgChange != null ? +sector.avgChange : 0;
  return (
    <Card hoverable size="small" style={{ cursor: 'pointer', borderLeft: `3px solid ${catColor}` }}
      onClick={() => onClick(sector.conceptName)}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
        <div>
          <Space size={4}>
            <Tag color={catColor} style={{ fontSize: 10, lineHeight: '16px', padding: '0 4px' }}>{cat}</Tag>
            <Text strong style={{ fontSize: 15 }}>{sector.conceptName}</Text>
          </Space>
          <div style={{ marginTop: 8 }}>
            <Text style={{ fontSize: 22, fontWeight: 700, color: chgColorHot(avgChg) }}>
              {avgChg > 0 ? '+' : ''}{fmtChg(sector.avgChange)}
            </Text>
          </div>
        </div>
        <div style={{ textAlign: 'right' }}>
          <Statistic title="成分股" value={sector.stockCount} valueStyle={{ fontSize: 14 }} />
          <div style={{ marginTop: 4 }}>
            <Text type="secondary" style={{ fontSize: 11 }}>PE {sector.medianPe ?? '-'}</Text>
            <Text type="secondary" style={{ fontSize: 11, marginLeft: 8 }}>PB {sector.medianPb ?? '-'}</Text>
          </div>
        </div>
      </div>
      {sector.topStocks && sector.topStocks.length > 0 && (
        <div style={{ marginTop: 8, borderTop: '1px solid #f0f0f0', paddingTop: 6 }}>
          <Text type="secondary" style={{ fontSize: 11 }}>领涨：</Text>
          {sector.topStocks.map((s, i) => (
            <Tag key={i} color={+s.change > 0 ? 'red' : 'green'} style={{ fontSize: 10, margin: '0 2px' }}>
              {s.name} {fmtChg(s.change)}
            </Tag>
          ))}
        </div>
      )}
    </Card>
  );
}

function SectorDetail({ conceptName, onBack }) {
  const [loading, setLoading] = useState(true);
  const [data, setData] = useState(null);
  const [pageSize, setPageSize] = useState(15);
  useEffect(() => {
    setLoading(true);
    stockAnalysisApi.getHotSectorDetail(conceptName)
      .then(d => setData(d))
      .catch(() => setData(null))
      .finally(() => setLoading(false));
  }, [conceptName]);
  if (loading) return (<Spin tip="加载板块详情..."><div style={{ display: 'block', margin: '80px auto' }} /></Spin>);
  if (!data || data.error) return <Card><Text type="danger">{data?.error || '加载失败'}</Text></Card>;
  const { stocks = [], trend = [], upCount = 0, downCount = 0, avgChange } = data;
  const trendOption = trend.length > 0 ? {
    backgroundColor: 'transparent',
    tooltip: { trigger: 'axis', formatter: p => `${p[0].axisValue}<br/>涨跌: <b>${(+p[0].value).toFixed(2)}%</b>` },
    grid: { left: 55, right: 20, top: 25, bottom: 35 },
    xAxis: { type: 'category', data: trend.map(t => t.date.slice(5)), axisLabel: { fontSize: 11, color: '#666' }, axisLine: { lineStyle: { color: '#ccc' } } },
    yAxis: { type: 'value', name: '涨跌%', nameTextStyle: { fontSize: 11, color: '#666', padding: [0, 0, 0, 6] }, axisLabel: { fontSize: 10, color: '#666', formatter: '{value}%' }, splitLine: { lineStyle: { color: '#f0f0f0' } } },
    series: [{ type: 'bar', data: trend.map(t => ({ value: +(t.avgChange), itemStyle: { color: +t.avgChange >= 0 ? '#ef5350' : '#26a69a' } })), barMaxWidth: 30 }],
  } : null;
  const detailColumns = [
    { title: '代码', dataIndex: 'code', width: 80, render: v => <a href={`/stock-analysis?code=${v}`}>{v}</a> },
    { title: '名称', dataIndex: 'name', width: 90 },
    { title: '涨跌幅', dataIndex: 'changePercent', width: 90, sorter: (a, b) => (+a.changePercent || 0) - (+b.changePercent || 0), render: v => v != null ? <Text style={{ color: chgColorHot(+v), fontWeight: 600 }}>{(+v).toFixed(2)}%</Text> : '-' },
    { title: '收盘价', dataIndex: 'closePrice', width: 80, render: v => v != null ? (+v).toFixed(2) : '-' },
    { title: 'PE(TTM)', dataIndex: 'peTtm', width: 80, sorter: (a, b) => (+a.peTtm || 0) - (+b.peTtm || 0), render: v => v != null ? (+v).toFixed(1) : '-' },
    { title: 'PB', dataIndex: 'pb', width: 70, render: v => v != null ? (+v).toFixed(2) : '-' },
    { title: '换手率', dataIndex: 'turnoverRate', width: 70, sorter: (a, b) => (+a.turnoverRate || 0) - (+b.turnoverRate || 0), render: v => v != null ? `${(+v).toFixed(2)}%` : '-' },
    { title: '市值', dataIndex: 'totalMarketCap', width: 90, sorter: (a, b) => (+a.totalMarketCap || 0) - (+b.totalMarketCap || 0), render: v => fmtCapHot(v) },
  ];
  return (
    <div>
      <div style={{ marginBottom: 12 }}><a onClick={onBack}><ArrowLeftOutlined /> 返回板块列表</a></div>
      <Title level={4} style={{ marginBottom: 16 }}>{conceptName}</Title>
      <Row gutter={[16, 12]} style={{ marginBottom: 16 }}>
        <Col xs={12} sm={6}><Card size="small"><Statistic title="成分股" value={stocks.length} /></Card></Col>
        <Col xs={12} sm={6}><Card size="small"><Statistic title="平均涨跌" value={avgChange} suffix="%" valueStyle={{ color: chgColorHot(+avgChange) }} /></Card></Col>
        <Col xs={12} sm={6}><Card size="small"><Statistic title="上涨" value={upCount} prefix={<ArrowUpOutlined />} valueStyle={{ color: '#ef5350' }} /></Card></Col>
        <Col xs={12} sm={6}><Card size="small"><Statistic title="下跌" value={downCount} prefix={<ArrowDownOutlined />} valueStyle={{ color: '#26a69a' }} /></Card></Col>
      </Row>
      {trendOption && (<Card title="近5日板块涨跌" size="small" style={{ marginBottom: 16 }}>
        <ReactECharts option={trendOption} style={{ height: 200 }} notMerge={true} />
      </Card>)}
      <Card title="成分股排名" size="small">
        <Table dataSource={stocks} columns={detailColumns} rowKey="code" size="small"
          pagination={{
            total: stocks.length,
            current: 1,
            pageSize,
            showSizeChanger: true,
            showTotal: (t) => `共 ${t} 只`,
            pageSizeOptions: ['10', '20', '50', '100'],
            onShowSizeChange: (_, size) => setPageSize(size),
          }} scroll={{ x: 700 }} />
      </Card>
    </div>
  );
}

// 个股排名表格列定义
const stockColumns = [
  { title: '代码', dataIndex: 'code', width: 90,
    render: v => <a href={`/stock-analysis?code=${v}`} style={{ fontSize: 13 }}>{v}</a>,
  },
  { title: '名称', dataIndex: 'name', width: 100, ellipsis: true },
  { title: '收盘价', dataIndex: 'closePrice', width: 85, align: 'right',
    render: v => v != null ? Number(v).toFixed(2) : '-',
  },
  { title: '涨跌幅', dataIndex: 'changePercent', width: 95, align: 'right',
    sorter: (a, b) => (a.changePercent || 0) - (b.changePercent || 0),
    render: renderChangePct,
  },
  { title: 'PE(TTM)', dataIndex: 'peTtm', width: 80, align: 'center',
    sorter: (a, b) => (a.peTtm || 0) - (b.peTtm || 0),
    render: v => v != null ? Number(v).toFixed(1) : '-',
  },
  { title: 'PB', dataIndex: 'pb', width: 65, align: 'center',
    render: v => v != null ? Number(v).toFixed(2) : '-',
  },
  { title: '总市值', dataIndex: 'totalMarketCap', width: 95, align: 'right',
    sorter: (a, b) => (a.totalMarketCap || 0) - (b.totalMarketCap || 0),
    render: formatCap,
  },
  { title: '换手率', dataIndex: 'turnoverRate', width: 80, align: 'right',
    render: v => v != null ? (Number(v) * 100).toFixed(2) + '%' : '-',
  },
];

export default function SectorRanking() {
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState(null);
  const [drillDown, setDrillDown] = useState(null); // { type: 'industry'|'concept', name: string }
  const [stocks, setStocks] = useState([]);
  const [stocksLoading, setStocksLoading] = useState(false);
  const [sortBy, setSortBy] = useState('changePercent');
  const [stockPageSize, setStockPageSize] = useState(20);
  // 热门板块视图
  const [viewMode, setViewMode] = useState('table'); // 'table' | 'card'
  const [hotSectors, setHotSectors] = useState([]);
  const [hotLoading, setHotLoading] = useState(true);
  const [hotTradeDate, setHotTradeDate] = useState(null);
  const [selectedSector, setSelectedSector] = useState(null);

  useEffect(() => {
    setLoading(true);
    stockAnalysisApi.getSectorRanking()
      .then(d => setData(d))
      .catch(() => setData(null))
      .finally(() => setLoading(false));
    // 同时加载热门板块数据
    setHotLoading(true);
    stockAnalysisApi.getHotSectors()
      .then(d => { setHotTradeDate(d?.tradeDate || null); setHotSectors(d?.sectors || []); })
      .catch(() => { setHotTradeDate(null); setHotSectors([]); })
      .finally(() => setHotLoading(false));
  }, []);

  const loadStocks = (type, name, sort = 'changePercent') => {
    setDrillDown({ type, name });
    setStocksLoading(true);
    const apiCall = type === 'industry'
      ? stockAnalysisApi.getIndustryStocks(name, sort, 'desc')
      : stockAnalysisApi.getConceptStocks(name, sort, 'desc');
    apiCall
      .then(d => setStocks(d || []))
      .catch(() => setStocks([]))
      .finally(() => setStocksLoading(false));
  };

  if (loading) return <Spin size="large" style={{ display: 'block', margin: '80px auto' }} />;
  if (!data) return <Empty description="暂无行业排行数据" />;

  const { industry = [], concept = [], tradeDate } = data;

  // 行业列定义
  const industryColumns = [
    {
      title: '排名', width: 60, align: 'center',
      render: (_, __, idx) => idx < 3 ? <Tag color={idx === 0 ? 'red' : idx === 1 ? 'volcano' : 'orange'}>{idx + 1}</Tag> : idx + 1,
    },
    { title: '行业', dataIndex: 'industry', width: 200, ellipsis: true,
      render: (v) => <a onClick={() => loadStocks('industry', v)} style={{ fontWeight: 500 }}>{v}</a>,
    },
    { title: '个股数', dataIndex: 'stockCount', width: 80, align: 'center' },
    {
      title: '平均涨跌幅', dataIndex: 'avgChangePct', width: 120, align: 'right',
      sorter: (a, b) => (a.avgChangePct || 0) - (b.avgChangePct || 0),
      render: renderChangePct,
    },
    {
      title: 'PE中位数', dataIndex: 'medianPe', width: 90, align: 'center',
      sorter: (a, b) => (a.medianPe || 0) - (b.medianPe || 0),
      render: v => v != null ? Number(v).toFixed(1) : '-',
    },
    {
      title: 'PB中位数', dataIndex: 'medianPb', width: 85, align: 'center',
      sorter: (a, b) => (a.medianPb || 0) - (b.medianPb || 0),
      render: v => v != null ? Number(v).toFixed(2) : '-',
    },
  ];

  // 概念列定义
  const conceptColumns = [
    {
      title: '排名', width: 60, align: 'center',
      render: (_, __, idx) => idx < 3 ? <Tag color={idx === 0 ? 'red' : idx === 1 ? 'volcano' : 'orange'}>{idx + 1}</Tag> : idx + 1,
    },
    { title: '概念板块', dataIndex: 'conceptName', width: 160, ellipsis: true,
      render: (v) => <a onClick={() => loadStocks('concept', v)} style={{ fontWeight: 500 }}>{v}</a>,
    },
    { title: '个股数', dataIndex: 'stockCount', width: 80, align: 'center' },
    {
      title: '平均涨跌幅', dataIndex: 'avgChangePct', width: 120, align: 'right',
      sorter: (a, b) => (a.avgChangePct || 0) - (b.avgChangePct || 0),
      render: renderChangePct,
    },
    {
      title: 'PE中位数', dataIndex: 'medianPe', width: 90, align: 'center',
      sorter: (a, b) => (a.medianPe || 0) - (b.medianPe || 0),
      render: v => v != null ? Number(v).toFixed(1) : '-',
    },
    {
      title: 'PB中位数', dataIndex: 'medianPb', width: 85, align: 'center',
      sorter: (a, b) => (a.medianPb || 0) - (b.medianPb || 0),
      render: v => v != null ? Number(v).toFixed(2) : '-',
    },
  ];

  // 统计
  const upIndustries = industry.filter(i => i.avgChangePct > 0).length;
  const downIndustries = industry.filter(i => i.avgChangePct < 0).length;
  const upConcepts = concept.filter(c => c.avgChangePct > 0).length;
  const downConcepts = concept.filter(c => c.avgChangePct < 0).length;

  // 热门板块详情视图
  if (selectedSector) {
    return <SectorDetail conceptName={selectedSector} onBack={() => setSelectedSector(null)} />;
  }

  // 个股下钻视图
  if (drillDown) {
    return (
      <div style={{ padding: '12px 24px 24px' }}>
        <Button type="link" icon={<ArrowLeftOutlined />} onClick={() => { setDrillDown(null); setStocks([]); }}
          style={{ marginBottom: 12, paddingLeft: 0 }}>
          返回行业排行
        </Button>
        <Title level={4} style={{ marginBottom: 12 }}>
          <StockOutlined style={{ marginRight: 8 }} />
          {drillDown.type === 'industry' ? '行业' : '概念'}：{drillDown.name}
          <Text type="secondary" style={{ fontSize: 14, marginLeft: 12 }}>({stocks.length}只股票)</Text>
        </Title>
        <div style={{ marginBottom: 12 }}>
          <Text style={{ marginRight: 8 }}>排序：</Text>
          <Select value={sortBy} style={{ width: 140 }} size="small" onChange={v => { setSortBy(v); loadStocks(drillDown.type, drillDown.name, v); }}
            options={[
              { value: 'changePercent', label: '涨跌幅' },
              { value: 'totalMarketCap', label: '市值' },
              { value: 'peTtm', label: 'PE(TTM)' },
              { value: 'turnoverRate', label: '换手率' },
            ]}
          />
        </div>
        <Card size="small">
          {stocksLoading ? <Spin /> : (
            <Table size="small" dataSource={stocks} columns={stockColumns} rowKey="code"
              pagination={{
                current: 1,
                pageSize: stockPageSize,
                total: stocks.length,
                showSizeChanger: true,
                showTotal: (total) => `共 ${total} 只`,
                pageSizeOptions: ['10', '20', '50', '100'],
                size: 'small',
                onChange: (page, size) => setStockPageSize(size),
              }} scroll={{ x: 700 }} />
          )}
        </Card>
      </div>
    );
  }

  const tabItems = [
    {
      key: 'industry',
      label: `申万行业（${industry.length}）`,
      children: (
        <Table size="small" dataSource={industry} columns={industryColumns} rowKey="industry"
          pagination={false} scroll={{ y: 600 }} />
      ),
    },
    {
      key: 'concept',
      label: `概念板块（${concept.length}）`,
      children: (
        <Table size="small" dataSource={concept} columns={conceptColumns} rowKey="conceptName"
          pagination={{ pageSize: 20, size: 'small' }} scroll={{ y: 600 }} />
      ),
    },
  ];

  return (
      <div style={{ padding: '12px 24px 24px' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12 }}>
          <Title level={4} style={{ margin: 0 }}>
            {viewMode === 'card' ? <><RocketOutlined style={{ marginRight: 8 }} />热门行业专题</> : <><StockOutlined style={{ marginRight: 8 }} />行业涨跌排行</>}
          </Title>
          <Space>
            <Button size="small" type={viewMode === 'table' ? 'primary' : 'default'} onClick={() => setViewMode('table')}>表格视图</Button>
            <Button size="small" type={viewMode === 'card' ? 'primary' : 'default'} onClick={() => setViewMode('card')}>卡片视图</Button>
          </Space>
        </div>
      <Text type="secondary" style={{ display: 'block', marginBottom: 12 }}>
        点击行业/概念名称可查看成分股排名{tradeDate ? ` · 数据日期：${tradeDate}` : ''}
      </Text>
      <Row gutter={16} style={{ marginBottom: 12 }}>
        <Col span={6}>
          <Card size="small">
            <Statistic title="行业上涨" value={upIndustries} valueStyle={{ color: '#f5222d' }} prefix={<ArrowUpOutlined />} suffix={`/ ${industry.length}`} />
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small">
            <Statistic title="行业下跌" value={downIndustries} valueStyle={{ color: '#52c41a' }} prefix={<ArrowDownOutlined />} suffix={`/ ${industry.length}`} />
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small">
            <Statistic title="概念上涨" value={upConcepts} valueStyle={{ color: '#f5222d' }} prefix={<ArrowUpOutlined />} suffix={`/ ${concept.length}`} />
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small">
            <Statistic title="概念下跌" value={downConcepts} valueStyle={{ color: '#52c41a' }} prefix={<ArrowDownOutlined />} suffix={`/ ${concept.length}`} />
          </Card>
        </Col>
      </Row>

      {viewMode === 'table' ? (
        <Card size="small">
          <Tabs defaultActiveKey="industry" items={tabItems} />
        </Card>
      ) : (
        <>
          {hotTradeDate && (
            <div style={{ marginBottom: 16, color: '#8c8c8c', fontSize: 13 }}>数据日期：{hotTradeDate}</div>
          )}
          {/* 分类概览 */}
          {(() => {
            const catStats = {};
            hotSectors.forEach(s => {
              const cat = CATEGORY_MAP[s.conceptName] || '其他';
              if (!catStats[cat]) catStats[cat] = { count: 0, up: 0, down: 0 };
              catStats[cat].count++;
              const chg = s.avgChange != null ? +s.avgChange : 0;
              if (chg > 0) catStats[cat].up++; else catStats[cat].down++;
            });
            return Object.keys(catStats).length > 0 ? (
              <Row gutter={12} style={{ marginBottom: 16 }}>
                {Object.entries(catStats).map(([cat, stat]) => (
                  <Col key={cat}>
                    <Tag color={CATEGORY_COLORS[cat]} style={{ fontSize: 13, padding: '4px 12px' }}>
                      {cat}：{stat.count}板块 <ArrowUpOutlined style={{ color: '#fff' }} />{stat.up} <ArrowDownOutlined style={{ color: '#fff' }} />{stat.down}
                    </Tag>
                  </Col>
                ))}
              </Row>
            ) : null;
          })()}
          <Spin spinning={hotLoading}>
            <Row gutter={[16, 16]}>
              {hotSectors.map(s => (
                <Col key={s.conceptName} xs={24} sm={12} md={8} lg={6}>
                  <SectorCard sector={s} onClick={setSelectedSector} />
                </Col>
              ))}
            </Row>
            {!hotLoading && hotSectors.length === 0 && (
              <Card><Text type="secondary">暂无热门板块数据</Text></Card>
            )}
          </Spin>
        </>
      )}
    </div>
  );
}
