import React, { useEffect, useState, useCallback } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Card, Table, Tag, Space, Typography, Row, Col, Statistic, Select, Spin, Tabs, Descriptions, Input, Button, Tooltip } from 'antd';
import { message } from '../../utils/messageUtil';
import {
  SearchOutlined, ReloadOutlined, FundOutlined, RiseOutlined, FallOutlined,
  PieChartOutlined, BarChartOutlined, QuestionCircleOutlined,
  SafetyCertificateOutlined, ThunderboltOutlined, RobotOutlined, ArrowLeftOutlined
} from '@ant-design/icons';
import ReactECharts from 'echarts-for-react';
import { financialApi, marketApi, silentConfig } from '../../api';

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
  { title: <span>自由现金流 <Tooltip title="FCF = 经营净现金流 + 投资净现金流。衡量企业扣除资本支出后可自由支配的现金，正值越大说明造血能力越强。一季报、中报、三季报未披露现金流量表时无数据"><QuestionCircleOutlined style={{ color: '#999', fontSize: 12 }} /></Tooltip></span>,
    dataIndex: 'freeCashFlow', width: 140, align: 'right',
    render: v => v != null ? <Text type={v >= 0 ? 'danger' : 'success'}>{fmtAmount(v)}</Text> : '-' },
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
  { title: 'FCF(亿)', dataIndex: 'freeCashFlow', width: 100, align: 'right',
    render: v => v != null ? <Text type={Number(v) >= 0 ? 'danger' : 'success'}>{fmtAmount(v)}</Text> : '-' },
  { title: 'FCF/经营CF', dataIndex: 'netOperateCf', width: 110, align: 'right',
    render: (v, r) => (v != null && r.freeCashFlow != null && Number(v) !== 0)
      ? fmtVal(Number(r.freeCashFlow) / Number(v)) : '-' },
  { title: <span>CF/NP(%) <Tooltip title={'经营现金流/净利润×100。衡量利润含金量：>100%说明利润有真金白银支撑，<100%说明利润含应收等"纸面利润"。一季报、中报、三季报未披露现金流量表时无数据'}><QuestionCircleOutlined style={{ color: '#999', fontSize: 12 }} /></Tooltip></span>,
    dataIndex: 'operatingCfToNp', width: 120, align: 'right', render: v => fmtPct(v) },
];

/** 三大流派选股卡片 */
function StylePicksCards({ onSelect }) {
  const [duanData, setDuanData] = useState(null);
  const [hotData, setHotData] = useState(null);
  const [quantData, setQuantData] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    setLoading(true);
    Promise.all([
      financialApi.getDuanYongpingPicks(20),
      financialApi.getHotMoneyPicks(20),
      financialApi.getQuantPicks(20),
    ])
      .then(([d, h, q]) => {
        setDuanData(d);
        setHotData(h);
        setQuantData(q);
      })
      .catch(() => message.error('选股数据加载失败，请稍后重试'))
      .finally(() => setLoading(false));
  }, []);

  // 带颜色说明的列标题（问号 Tooltip）
  const colTitle = (name, tip) => (
    <span>{name} <Tooltip title={tip} placement="top"><QuestionCircleOutlined style={{ color: '#999', fontSize: 11, cursor: 'pointer', marginLeft: 2 }} /></Tooltip></span>
  );

  // 公共基础列
  const baseColumns = [
    { title: '#', width: 35, render: (_, __, i) => i + 1 },
    { title: '代码', dataIndex: 'code', width: 75 },
    { title: '名称', dataIndex: 'name', width: 75, ellipsis: true, render: (v, r) => (
      <a onClick={(e) => { e.stopPropagation(); handleRowClick(r.code); }}>{v}</a>
    )},
  ];

  // 通用尾部列
  const tailColumns = [
    { title: '评分', dataIndex: 'score', width: 55, align: 'right', fixed: 'right', render: v => v != null ? <Text strong>{Number(v).toFixed(1)}</Text> : '-' },
    { title: '期数', dataIndex: 'periods', width: 45, align: 'center', fixed: 'right', render: v => v || '-' },
  ];

  // 段永平派专用列：均ROE、均毛利率、CF/NP、均负债率、ROE稳定、PE
  const duanColumns = [
    ...baseColumns,
    { title: '均ROE(%)', dataIndex: 'avg_roe', width: 70, align: 'right', render: v => v != null ? Number(v).toFixed(1) : '-' },
    { title: '均毛利率(%)', dataIndex: 'avg_gpm', width: 90, align: 'right', render: v => v != null ? Number(v).toFixed(1) : '-' },
    { title: colTitle('CF/NP(%)', '经营现金流/净利润。红色≥100（利润含金量高，有真金白银支撑），灰色<100'), dataIndex: 'max_cfnp', width: 80, align: 'right',
      render: v => v != null ? <Text type={v >= 100 ? 'danger' : 'secondary'}>{Number(v).toFixed(1)}</Text> : '-' },
    { title: '均负债率(%)', dataIndex: 'avg_debt', width: 90, align: 'right', render: v => v != null ? Number(v).toFixed(1) : '-' },
    { title: colTitle('ROE稳定', 'ROE跨期标准差，越小越稳定。红色≤5（非常稳定），黄色5~10，灰色>10'), dataIndex: 'std_roe', width: 80, align: 'right',
      render: v => v != null ? <Text type={v <= 5 ? 'danger' : v <= 10 ? 'warning' : 'secondary'}>{Number(v).toFixed(1)}</Text> : '-' },
    { title: 'PE', dataIndex: 'pe_ttm', width: 50, align: 'right', render: v => v != null ? Number(v).toFixed(1) : '-' },
    ...tailColumns,
  ];

  // 游资/短线派专用列：利润增速、营收增速、均ROE、均毛利率、PE
  const hotColumns = [
    ...baseColumns,
    { title: colTitle('利润增速(%)', '近15个月最大值。红色=正增长，绿色=负增长'), dataIndex: 'max_np_yoy', width: 95, align: 'right',
      render: v => v != null ? <Text type={v >= 0 ? 'danger' : 'success'}>{Number(v).toFixed(1)}</Text> : '-' },
    { title: colTitle('营收增速(%)', '近15个月最大值。红色=正增长，绿色=负增长'), dataIndex: 'max_rev_yoy', width: 95, align: 'right',
      render: v => v != null ? <Text type={v >= 0 ? 'danger' : 'success'}>{Number(v).toFixed(1)}</Text> : '-' },
    { title: '均ROE(%)', dataIndex: 'avg_roe', width: 70, align: 'right', render: v => v != null ? Number(v).toFixed(1) : '-' },
    { title: '均毛利率(%)', dataIndex: 'avg_gpm', width: 90, align: 'right', render: v => v != null ? Number(v).toFixed(1) : '-' },
    { title: 'PE', dataIndex: 'pe_ttm', width: 50, align: 'right', render: v => v != null ? Number(v).toFixed(1) : '-' },
    ...tailColumns,
  ];

  // 量化派专用列：均ROE、均毛利率、利润增速、均CF/NP、均负债率、ROE稳定、PE
  const quantColumns = [
    ...baseColumns,
    { title: '均ROE(%)', dataIndex: 'avg_roe', width: 70, align: 'right', render: v => v != null ? Number(v).toFixed(1) : '-' },
    { title: '均毛利率(%)', dataIndex: 'avg_gpm', width: 90, align: 'right', render: v => v != null ? Number(v).toFixed(1) : '-' },
    { title: colTitle('利润增速(%)', '近15个月最大值。红色=正增长，绿色=负增长'), dataIndex: 'max_np_yoy', width: 95, align: 'right',
      render: v => v != null ? <Text type={v >= 0 ? 'danger' : 'success'}>{Number(v).toFixed(1)}</Text> : '-' },
    { title: colTitle('均CF/NP(%)', '经营现金流/净利润跨期均值。红色≥100，灰色<100'), dataIndex: 'avg_cfnp', width: 90, align: 'right',
      render: v => v != null ? <Text type={v >= 100 ? 'danger' : 'secondary'}>{Number(v).toFixed(1)}</Text> : '-' },
    { title: '均负债率(%)', dataIndex: 'avg_debt', width: 90, align: 'right', render: v => v != null ? Number(v).toFixed(1) : '-' },
    { title: colTitle('ROE稳定', 'ROE跨期标准差，越小越稳定。红色≤5（非常稳定），黄色5~10，灰色>10'), dataIndex: 'std_roe', width: 80, align: 'right',
      render: v => v != null ? <Text type={v <= 5 ? 'danger' : v <= 10 ? 'warning' : 'secondary'}>{Number(v).toFixed(1)}</Text> : '-' },
    { title: 'PE', dataIndex: 'pe_ttm', width: 50, align: 'right', render: v => v != null ? Number(v).toFixed(1) : '-' },
    ...tailColumns,
  ];

  const handleRowClick = (code) => {
    onSelect(code);
  };

  const renderPickCard = (data, color, columns) => {
    if (!data) return null;
    const strategyTip = (
      <div style={{ maxHeight: 360, overflow: 'auto', lineHeight: 1.8 }}>
        <div style={{ fontWeight: 600, fontSize: 13, color: color, marginBottom: 6, borderBottom: `1px solid ${color}30`, paddingBottom: 4 }}>
          {data.style} · 筛选与评分规则
        </div>
        <ol style={{ margin: 0, paddingLeft: 18, fontSize: 12 }}>
          {(data.strategy || []).map((s, i) => <li key={i}>{s}</li>)}
        </ol>
      </div>
    );
    return (
      <Card
        title={
          <span>{data.style} <Text type="secondary" style={{ fontSize: 13, fontWeight: 400 }}>{data.subtitle}</Text>
            <Tooltip title={strategyTip} classNames={{ root: 'strategy-tooltip' }}>
              <QuestionCircleOutlined style={{ marginLeft: 8, color: color, fontSize: 14, cursor: 'pointer' }} />
            </Tooltip>
          </span>
        }
        styles={{ header: { borderBottom: `2px solid ${color}` } }}
      >
        <Table
          columns={columns}
          dataSource={data.stocks || []}
          rowKey="code"
          size="small"
          pagination={false}
          scroll={{ y: 560 }}
          onRow={(record) => ({
            onClick: () => handleRowClick(record.code),
            style: { cursor: 'pointer' },
          })}
        />
      </Card>
    );
  };

  // 三流派对比表格
  const compareContent = (
    <div style={{ fontSize: 13, lineHeight: 1.7, minWidth: 800 }}>
      <table style={{ width: '100%', borderCollapse: 'collapse', tableLayout: 'fixed' }}>
        <colgroup>
          <col style={{ width: '14%' }} />
          <col style={{ width: '29%' }} />
          <col style={{ width: '29%' }} />
          <col style={{ width: '28%' }} />
        </colgroup>
        <thead>
          <tr style={{ background: '#fafafa' }}>
            <th style={{ padding: '6px 10px', border: '1px solid #e8e8e8', textAlign: 'left', fontWeight: 600 }}>对比维度</th>
            <th style={{ padding: '6px 10px', border: '1px solid #e8e8e8', textAlign: 'center', color: '#52c41a', fontWeight: 600 }}>段永平派</th>
            <th style={{ padding: '6px 10px', border: '1px solid #e8e8e8', textAlign: 'center', color: '#fa8c16', fontWeight: 600 }}>游资/短线派</th>
            <th style={{ padding: '6px 10px', border: '1px solid #e8e8e8', textAlign: 'center', color: '#1677ff', fontWeight: 600 }}>量化派</th>
          </tr>
        </thead>
        <tbody>
          {[
            ['核心理念', '价值投资：好公司+好价格+现金流', '高弹性+中小盘+资金关注', '多因子综合评分，追求风险收益比'],
            ['ROE门槛', '均值≥15%', '无硬性门槛', '均值≥8%'],
            ['毛利率门槛', '均值≥30%', '无硬性门槛', '无硬性门槛'],
            ['利润增速', '无硬性门槛', '最大值≥50%', '关注最大值（成长性）'],
            ['营收增速', '无硬性门槛', '最大值≥30%', '无硬性门槛'],
            ['利润含金量', 'CF/NP≥80%', '不关注', '均值（现金流评分）'],
            ['资产负债率', '均值≤60%', '不关注', '均值≤70%'],
            ['ROE稳定性', '标准差越小加分越高', '不关注', '标准差越小加分越高'],
            ['PE范围', '0~30', '>0', '3~40'],
            ['市值要求', '不关注', '10~800亿', '≥50亿'],
            ['评分权重', '盈利25%+护城河15%+含金量15%+稳健15%+估值15%+稳定15%', '利润增速40%+营收增速30%+市值适配30%', '盈利20%+护城河10%+成长15%+现金流10%+估值15%+运营10%+稳定10%+完整度10%'],
          ].map(([dim, d1, d2, d3], i) => (
            <tr key={i} style={{ background: i % 2 === 1 ? '#fafafa' : '#fff' }}>
              <td style={{ padding: '5px 10px', border: '1px solid #e8e8e8', fontWeight: 500 }}>{dim}</td>
              <td style={{ padding: '5px 10px', border: '1px solid #e8e8e8', textAlign: 'center' }}>{d1}</td>
              <td style={{ padding: '5px 10px', border: '1px solid #e8e8e8', textAlign: 'center' }}>{d2}</td>
              <td style={{ padding: '5px 10px', border: '1px solid #e8e8e8', textAlign: 'center' }}>{d3}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );

  if (loading) return <div style={{ textAlign: 'center', padding: 80 }}><Spin size="large" /><div style={{ marginTop: 12, color: '#999' }}>加载选股数据...</div></div>;

  return (
    <Tabs
      tabBarExtraContent={
        <Tooltip title={compareContent} classNames={{ root: 'strategy-tooltip' }}>
          <QuestionCircleOutlined style={{ fontSize: 18, color: '#999', cursor: 'pointer', marginRight: 8 }} />
        </Tooltip>
      }
      items={[
        {
          key: 'duan',
          label: <span><SafetyCertificateOutlined /> 段永平派</span>,
          children: renderPickCard(duanData, '#52c41a', duanColumns),
        },
        {
          key: 'hot',
          label: <span><ThunderboltOutlined /> 游资/短线派</span>,
          children: renderPickCard(hotData, '#fa8c16', hotColumns),
        },
        {
          key: 'quant',
          label: <span><RobotOutlined /> 量化派</span>,
          children: renderPickCard(quantData, '#1677ff', quantColumns),
        },
      ]}
    />
  );
}

export default function FinancialData() {
  const [searchParams, setSearchParams] = useSearchParams();
  const codeFromUrl = searchParams.get('code');
  const [selectedCode, setSelectedCode] = useState(codeFromUrl || null);
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

  // 从 URL 恢复时，预加载搜索选项使 Select 能显示 code+名称
  useEffect(() => {
    if (codeFromUrl && searchOptions.length === 0) {
      marketApi.searchSymbols(codeFromUrl, 1)
        .then(res => {
          if (res && res.length > 0) {
            setSearchOptions(res.map(s => ({
              value: s.code,
              label: `${s.code} ${s.name}`,
            })));
          }
        })
        .catch(() => {});
    }
  }, [codeFromUrl]);

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
    financialApi.getOverview(selectedCode, silentConfig)
      .then(res => setOverview(res))
      .catch(() => setOverview(null))
      .finally(() => setOverviewLoading(false));
  }, [selectedCode]);

  // 加载全部表格
  const loadAllData = useCallback(() => {
    if (!selectedCode) return;
    setTableLoading(true);
    Promise.all([
      financialApi.getIncome(selectedCode, 30, silentConfig),
      financialApi.getBalance(selectedCode, 30, silentConfig),
      financialApi.getCashflow(selectedCode, 30, silentConfig),
      financialApi.getIndicator(selectedCode, 30, silentConfig),
      financialApi.getTrend(selectedCode, silentConfig),
    ])
      .then(([inc, bal, cf, ind, trend]) => {
        setIncomeData(inc || []);
        setBalanceData(bal || []);
        setCashflowData(cf || []);
        setIndicatorData(ind || []);
        setTrendData(trend || []);
      })
      .catch(() => {})
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
      legend: { data: ['ROE', '毛利率', '净利率', '营收增速', '利润增速', 'CF/NP'], top: 0 },
      grid: { left: 50, right: 30, top: 40, bottom: 30 },
      xAxis: { type: 'category', data: dates, axisLabel: { rotate: 30, fontSize: 10 } },
      yAxis: { type: 'value', axisLabel: { formatter: '{value}%' } },
      series: [
        { name: 'ROE', type: 'line', data: trendData.map(d => d.roe), smooth: true, symbol: 'circle', symbolSize: 6, lineStyle: { width: 2 } },
        { name: '毛利率', type: 'line', data: trendData.map(d => d.grossProfitMargin), smooth: true, symbol: 'circle', symbolSize: 4 },
        { name: '净利率', type: 'line', data: trendData.map(d => d.netProfitMargin), smooth: true, symbol: 'circle', symbolSize: 4 },
        { name: '营收增速', type: 'bar', data: trendData.map(d => d.revenueYoy), barMaxWidth: 12, itemStyle: { color: '#1677ff' } },
        { name: '利润增速', type: 'bar', data: trendData.map(d => d.netProfitYoy), barMaxWidth: 12, itemStyle: { color: '#52c41a' } },
        { name: 'CF/NP', type: 'line', data: trendData.map(d => d.operatingCfToNp), smooth: true, symbol: 'circle', symbolSize: 4,
          lineStyle: { width: 2, type: 'dashed' }, itemStyle: { color: '#722ed1' } },
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
    { key: 'report_date', label: '报告期', children: overview.reportDate || '-' },
    { key: 'roe', label: 'ROE', children: overview.roe != null ? `${overview.roe}%` : '-' },
    { key: 'gpm', label: '毛利率', children: overview.grossProfitMargin != null ? `${overview.grossProfitMargin}%` : '-' },
    { key: 'npm', label: '净利率', children: overview.netProfitMargin != null ? `${overview.netProfitMargin}%` : '-' },
    { key: 'rev_yoy', label: '营收增速', children: overview.revenueYoy != null
      ? <Text type={overview.revenueYoy >= 0 ? 'danger' : 'success'}>{fmtPct(overview.revenueYoy)}</Text> : '-' },
    { key: 'np_yoy', label: '利润增速', children: overview.netProfitYoy != null
      ? <Text type={overview.netProfitYoy >= 0 ? 'danger' : 'success'}>{fmtPct(overview.netProfitYoy)}</Text> : '-' },
    { key: 'debt', label: '资产负债率', children: overview.debtToAssetRatio != null ? `${overview.debtToAssetRatio}%` : '-' },
    { key: 'current', label: '流动比率', children: fmtVal(overview.currentRatio) },
    { key: 'eps', label: 'EPS', children: overview.epsBasic != null ? `¥${overview.epsBasic}` : '-' },
    { key: 'bps', label: '每股净资产', children: overview.bps != null ? `¥${overview.bps}` : '-' },
    { key: 'inv_turn', label: '存货周转率', children: fmtVal(overview.inventoryTurnover) },
    { key: 'ar_days', label: '应收周转天数', children: overview.arTurnoverDays ? `${overview.arTurnoverDays}天` : '-' },
    { key: 'ocf', label: '经营现金流', children: overview.netOperateCf != null ? fmtAmount(overview.netOperateCf) : '-' },
    { key: 'fcf', label: <span>自由现金流 <Tooltip title="FCF = 经营净现金流 + 投资净现金流。衡量企业扣除资本支出后可自由支配的现金，正值越大说明造血能力越强。一季报、中报、三季报未披露现金流量表时无数据"><QuestionCircleOutlined style={{ color: '#999', fontSize: 11 }} /></Tooltip></span>,
      children: overview.freeCashFlow != null
      ? <Text type={Number(overview.freeCashFlow) >= 0 ? 'danger' : 'success'}>{fmtAmount(overview.freeCashFlow)}</Text> : '-' },
    { key: 'cfnp', label: <span>CF/NP <Tooltip title={'经营现金流/净利润×100。衡量利润含金量：>100%说明利润有真金白银支撑，<100%说明利润含应收等"纸面利润"。一季报、中报、三季报未披露现金流量表时无数据'}><QuestionCircleOutlined style={{ color: '#999', fontSize: 11 }} /></Tooltip></span>,
      children: overview.operatingCfToNp != null ? fmtPct(overview.operatingCfToNp) : '-' },
  ] : [];

  const tabItems = [
    {
      key: 'indicator',
      label: <><FundOutlined /> 财务指标</>,
      children: (
        <Card size="small">
          <Table dataSource={indicatorData} columns={indicatorColumns} rowKey="reportDate"
                 size="small" loading={tableLoading} scroll={{ x: 1500 }} pagination={false} />
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
                 size="small" loading={tableLoading} scroll={{ x: 1100 }} pagination={false} />
        </Card>
      ),
    },
  ];

  return (
    <div>
      <div className="page-header">
        <Title level={4} style={{ margin: 0 }}>财务数据</Title>
        {selectedCode && (
          <Button type="link" icon={<ArrowLeftOutlined />} onClick={() => { setSelectedCode(null); setSearchParams({}); }}>
            返回选股列表
          </Button>
        )}
      </div>

      {/* 搜索栏 */}
      <Card style={{ marginBottom: 16 }}>
        <Space wrap>
          <Text strong>选择股票：</Text>
          <Select
            value={selectedCode}
            onChange={v => { setSelectedCode(v); setSearchParams(v ? { code: v } : {}); }}
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
        <StylePicksCards onSelect={(code) => { setSelectedCode(code); setSearchParams({ code }); }} />
      ) : (
        <>
          {/* 概览卡片 */}
          {overviewLoading ? <div style={{ textAlign: 'center', padding: 40 }}><Spin /></div> : overview && (
            <>
              <Row gutter={[12, 12]} style={{ marginBottom: 16 }}>
                {[
                  { id: 'roe', label: 'ROE', value: overview.roe, suffix: '%', color: '#1677ff' },
                  { id: 'gpm', label: '毛利率', value: overview.grossProfitMargin, suffix: '%', color: '#722ed1' },
                  { id: 'npm', label: '净利率', value: overview.netProfitMargin, suffix: '%', color: '#13c2c2' },
                  { id: 'rev_yoy', label: '营收增速', value: overview.revenueYoy != null ? Number(overview.revenueYoy).toFixed(2) : null, suffix: '%',
                    color: overview.revenueYoy >= 0 ? '#cf1322' : '#389e0d' },
                  { id: 'np_yoy', label: '利润增速', value: overview.netProfitYoy != null ? Number(overview.netProfitYoy).toFixed(2) : null, suffix: '%',
                    color: overview.netProfitYoy >= 0 ? '#cf1322' : '#389e0d' },
                  { id: 'debt', label: '资产负债率', value: overview.debtToAssetRatio, suffix: '%', color: '#fa8c16' },
                  { id: 'fcf', label: <span>自由现金流 <Tooltip title="FCF = 经营净现金流 + 投资净现金流。衡量企业扣除资本支出后可自由支配的现金，正值越大说明造血能力越强。一季报、中报、三季报未披露现金流量表时无数据"><QuestionCircleOutlined style={{ color: '#999', fontSize: 11 }} /></Tooltip></span>,
                    value: overview.freeCashFlow != null ? (Number(overview.freeCashFlow) / 1e8).toFixed(2) : null, suffix: '亿',
                    color: overview.freeCashFlow != null ? (Number(overview.freeCashFlow) >= 0 ? '#cf1322' : '#389e0d') : undefined },
                  { id: 'cfnp', label: <span>CF/NP <Tooltip title={'经营现金流/净利润×100。衡量利润含金量：>100%说明利润有真金白银支撑，<100%说明利润含应收等"纸面利润"。一季报、中报、三季报未披露现金流量表时无数据'}><QuestionCircleOutlined style={{ color: '#999', fontSize: 11 }} /></Tooltip></span>,
                    value: overview.operatingCfToNp != null ? Number(overview.operatingCfToNp).toFixed(2) : null, suffix: '%',
                    color: overview.operatingCfToNp != null ? '#722ed1' : undefined },
                ].map(item => (
                  <Col key={item.id} xs={12} sm={8} md={6} lg={3}>
                    <Card size="small">
                      <Statistic title={item.label} value={item.value || '-'}
                                suffix={item.suffix}
                                valueStyle={{ color: item.value != null ? item.color : undefined, fontWeight: 600 }} />
                    </Card>
                  </Col>
                ))}
              </Row>

              <Descriptions bordered size="small" column={4} style={{ marginBottom: 16 }}
                             title={`${overview.name || selectedCode} · 最新年报财务指标（${overview.reportDate}）`}>
                {descItems.map(d => (
                  <Descriptions.Item key={d.key} label={d.label}>{d.children}</Descriptions.Item>
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
