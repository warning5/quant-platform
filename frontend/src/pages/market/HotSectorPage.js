import React, { useState, useEffect } from 'react';
import {
  Card, Row, Col, Tag, Spin, Typography, Table, Statistic, Space, Breadcrumb,
} from 'antd';
import {
  RocketOutlined, ArrowUpOutlined, ArrowDownOutlined, LeftOutlined,
} from '@ant-design/icons';
import ReactECharts from 'echarts-for-react';
import { stockAnalysisApi } from '../../api';

const { Text, Title } = Typography;

// ─── 格式化工具 ───────────────────────────────────────────────────────────────
const fmtChg = v => v != null ? `${(+v).toFixed(2)}%` : '-';
const fmtCap = v => {
  if (v == null) return '-';
  const cap = +v;
  if (cap >= 1e12) return `${(cap / 1e12).toFixed(1)}万亿`;
  if (cap >= 1e8) return `${(cap / 1e8).toFixed(0)}亿`;
  return `${(cap / 1e4).toFixed(0)}万`;
};
const chgColor = v => v > 0 ? '#ef5350' : v < 0 ? '#26a69a' : '#999';

// ─── 概念分类 ─────────────────────────────────────────────────────────────────
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

// ─── 板块卡片 ─────────────────────────────────────────────────────────────────
function SectorCard({ sector, onClick }) {
  const cat = CATEGORY_MAP[sector.conceptName] || '其他';
  const catColor = CATEGORY_COLORS[cat] || '#999';
  const avgChg = sector.avgChange != null ? +sector.avgChange : 0;

  return (
    <Card
      hoverable
      size="small"
      style={{ cursor: 'pointer', borderLeft: `3px solid ${catColor}` }}
      onClick={() => onClick(sector.conceptName)}
    >
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
        <div>
          <Space size={4}>
            <Tag color={catColor} style={{ fontSize: 10, lineHeight: '16px', padding: '0 4px' }}>{cat}</Tag>
            <Text strong style={{ fontSize: 15 }}>{sector.conceptName}</Text>
          </Space>
          <div style={{ marginTop: 8 }}>
            <Text style={{ fontSize: 22, fontWeight: 700, color: chgColor(avgChg) }}>
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
      {/* 龙头股 */}
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

// ─── 详情页 ───────────────────────────────────────────────────────────────────
function SectorDetail({ conceptName, onBack }) {
  const [loading, setLoading] = useState(true);
  const [data, setData] = useState(null);

  useEffect(() => {
    setLoading(true);
    stockAnalysisApi.getHotSectorDetail(conceptName)
      .then(d => setData(d))
      .catch(() => setData(null))
      .finally(() => setLoading(false));
  }, [conceptName]);

  if (loading) return <Spin tip="加载板块详情..." style={{ display: 'block', margin: '80px auto' }} />;
  if (!data || data.error) return <Card><Text type="danger">{data?.error || '加载失败'}</Text></Card>;

  const { stocks = [], trend = [], upCount = 0, downCount = 0, avgChange } = data;

  // 近5日趋势图
  const trendOption = trend.length > 0 ? {
    backgroundColor: 'transparent',
    tooltip: { trigger: 'axis', formatter: p => `${p[0].axisValue}<br/>涨跌: <b>${(+p[0].value).toFixed(2)}%</b>` },
    grid: { left: 55, right: 20, top: 25, bottom: 35 },
    xAxis: {
      type: 'category', data: trend.map(t => t.date.slice(5)),
      axisLabel: { fontSize: 11, color: '#666' },
      axisLine: { lineStyle: { color: '#ccc' } },
    },
    yAxis: {
      type: 'value',
      name: '涨跌%',
      nameTextStyle: { fontSize: 11, color: '#666', padding: [0, 0, 0, 6] },
      axisLabel: { fontSize: 10, color: '#666', formatter: '{value}%' },
      splitLine: { lineStyle: { color: '#f0f0f0' } },
    },
    series: [{
      type: 'bar',
      data: trend.map(t => ({
        value: +(t.avgChange),
        itemStyle: { color: +t.avgChange >= 0 ? '#ef5350' : '#26a69a' },
      })),
      barMaxWidth: 30,
    }],
  } : null;

  // 成分股表格
  const columns = [
    { title: '代码', dataIndex: 'code', width: 80, render: v => <a href={`/stock-analysis?code=${v}`}>{v}</a> },
    { title: '名称', dataIndex: 'name', width: 90 },
    { title: '涨跌幅', dataIndex: 'changePercent', width: 90, sorter: (a, b) => (+a.changePercent || 0) - (+b.changePercent || 0),
      render: v => v != null ? <Text style={{ color: chgColor(+v), fontWeight: 600 }}>{(+v).toFixed(2)}%</Text> : '-' },
    { title: '收盘价', dataIndex: 'closePrice', width: 80, render: v => v != null ? (+v).toFixed(2) : '-' },
    { title: 'PE(TTM)', dataIndex: 'peTtm', width: 80, sorter: (a, b) => (+a.peTtm || 0) - (+b.peTtm || 0),
      render: v => v != null ? (+v).toFixed(1) : '-' },
    { title: 'PB', dataIndex: 'pb', width: 70, render: v => v != null ? (+v).toFixed(2) : '-' },
    { title: '换手率', dataIndex: 'turnoverRate', width: 70, sorter: (a, b) => (+a.turnoverRate || 0) - (+b.turnoverRate || 0),
      render: v => v != null ? `${(+v).toFixed(2)}%` : '-' },
    { title: '市值', dataIndex: 'totalMarketCap', width: 90, sorter: (a, b) => (+a.totalMarketCap || 0) - (+b.totalMarketCap || 0),
      render: v => fmtCap(v) },
  ];

  return (
    <div>
      <div style={{ marginBottom: 12 }}>
        <a onClick={onBack}><LeftOutlined /> 返回板块列表</a>
      </div>
      <Title level={4} style={{ marginBottom: 16 }}>{conceptName}</Title>

      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={4}><Card size="small"><Statistic title="成分股" value={stocks.length} /></Card></Col>
        <Col span={4}><Card size="small"><Statistic title="平均涨跌" value={avgChange} suffix="%" valueStyle={{ color: chgColor(+avgChange) }} /></Card></Col>
        <Col span={4}>
          <Card size="small"><Statistic title="上涨" value={upCount} prefix={<ArrowUpOutlined />} valueStyle={{ color: '#ef5350' }} /></Card>
        </Col>
        <Col span={4}>
          <Card size="small"><Statistic title="下跌" value={downCount} prefix={<ArrowDownOutlined />} valueStyle={{ color: '#26a69a' }} /></Card>
        </Col>
      </Row>

      {trendOption && (
        <Card title="近5日板块涨跌" size="small" style={{ marginBottom: 16 }}>
          <ReactECharts option={trendOption} style={{ height: 200 }} notMerge={true} />
        </Card>
      )}

      <Card title="成分股排名" size="small">
        <Table
          dataSource={stocks}
          columns={columns}
          rowKey="code"
          size="small"
          pagination={{ pageSize: 15, showTotal: t => `共 ${t} 只` }}
          scroll={{ x: 700 }}
        />
      </Card>
    </div>
  );
}

// ─── 主组件 ───────────────────────────────────────────────────────────────────
export default function HotSectorPage() {
  const [loading, setLoading] = useState(true);
  const [tradeDate, setTradeDate] = useState(null);
  const [sectors, setSectors] = useState([]);
  const [selectedSector, setSelectedSector] = useState(null);

  useEffect(() => {
    setLoading(true);
    stockAnalysisApi.getHotSectors()
      .then(d => {
        setTradeDate(d?.tradeDate || null);
        setSectors(d?.sectors || []);
      })
      .catch(() => { setTradeDate(null); setSectors([]); })
      .finally(() => setLoading(false));
  }, []);

  if (selectedSector) {
    return <SectorDetail conceptName={selectedSector} onBack={() => setSelectedSector(null)} />;
  }

  // 分类统计
  const catStats = {};
  sectors.forEach(s => {
    const cat = CATEGORY_MAP[s.conceptName] || '其他';
    if (!catStats[cat]) catStats[cat] = { count: 0, up: 0, down: 0 };
    catStats[cat].count++;
    const chg = s.avgChange != null ? +s.avgChange : 0;
    if (chg > 0) catStats[cat].up++;
    else catStats[cat].down++;
  });

  return (
    <div style={{ padding: '4px 16px 16px 16px', marginTop: -20 }}>
      <Title level={4} style={{ marginBottom: 4 }}>
        <RocketOutlined style={{ marginRight: 8 }} />
        热门行业专题
      </Title>
      {tradeDate && (
        <div style={{ marginBottom: 16, color: '#8c8c8c', fontSize: 13 }}>
          数据日期：{tradeDate}
        </div>
      )}

      {/* 分类概览 */}
      {Object.keys(catStats).length > 0 && (
        <Row gutter={12} style={{ marginBottom: 16 }}>
          {Object.entries(catStats).map(([cat, stat]) => (
            <Col key={cat}>
              <Tag color={CATEGORY_COLORS[cat]} style={{ fontSize: 13, padding: '4px 12px' }}>
                {cat}：{stat.count}板块 <ArrowUpOutlined style={{ color: '#fff' }} />{stat.up} <ArrowDownOutlined style={{ color: '#fff' }} />{stat.down}
              </Tag>
            </Col>
          ))}
        </Row>
      )}

      <Spin spinning={loading}>
        <Row gutter={[16, 16]}>
          {sectors.map(s => (
            <Col key={s.conceptName} xs={24} sm={12} md={8} lg={6}>
              <SectorCard sector={s} onClick={setSelectedSector} />
            </Col>
          ))}
        </Row>
        {!loading && sectors.length === 0 && (
          <Card><Text type="secondary">暂无热门板块数据</Text></Card>
        )}
      </Spin>
    </div>
  );
}
