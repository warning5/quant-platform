import React, { useState, useEffect } from 'react';
import { Card, Table, Tag, Spin, Empty, Typography, Tabs, Statistic, Row, Col, Button, Select, Modal } from 'antd';
import { ArrowUpOutlined, ArrowDownOutlined, StockOutlined, ArrowLeftOutlined } from '@ant-design/icons';
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

  useEffect(() => {
    setLoading(true);
    stockAnalysisApi.getSectorRanking()
      .then(d => setData(d))
      .catch(() => setData(null))
      .finally(() => setLoading(false));
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

  // 个股下钻视图
  if (drillDown) {
    return (
      <div style={{ padding: 16 }}>
        <Button type="link" icon={<ArrowLeftOutlined />} onClick={() => { setDrillDown(null); setStocks([]); }}
          style={{ marginBottom: 12, paddingLeft: 0 }}>
          返回行业排行
        </Button>
        <Title level={4} style={{ marginBottom: 16 }}>
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
              pagination={{ pageSize: 20, size: 'small' }} scroll={{ x: 700 }} />
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
    <div style={{ padding: 16 }}>
      <Title level={4} style={{ marginBottom: 16 }}>
        <StockOutlined style={{ marginRight: 8 }} />行业涨跌排行
      </Title>
      <Text type="secondary" style={{ display: 'block', marginBottom: 16 }}>
        点击行业/概念名称可查看成分股排名{tradeDate ? ` · 数据日期：${tradeDate}` : ''}
      </Text>
      <Row gutter={16} style={{ marginBottom: 16 }}>
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
      <Card size="small">
        <Tabs defaultActiveKey="industry" items={tabItems} />
      </Card>
    </div>
  );
}
