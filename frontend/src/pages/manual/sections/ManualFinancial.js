import React from 'react';
import { Card, Typography, Row, Col, Tag, Alert, Table, Divider, Badge } from 'antd';
import { DatabaseOutlined } from '@ant-design/icons';

const { Title, Paragraph, Text } = Typography;

export default function ManualFinancial() {
  // 数据更新流程步骤
  const updateSteps = [
    { no: 1, label: '东方财富业绩报表',    desc: '批量拉取，写入 gross_margin/net_profit_yoy/roe 等',          color: 'blue' },
    { no: 2, label: '同花顺财务摘要',      desc: '逐只补充 net_profit_margin/存货周转率/流动比率 等',   color: 'green' },
    { no: 3, label: '新浪三大表',          desc: '利润表+资产负债表+现金流量表（并发优化版）',        color: 'purple' },
    { no: 4, label: '计算派生指标',        desc: '计算 ROA/ROIC/同比增长率/周转率 等22个指标',   color: 'orange' },
    { no: 5, label: '重算因子值',          desc: '用最新财务数据重新计算所有因子',                 color: 'red' },
  ];

  // 四张财务表
  const tables = [
    { name: 'stock_financial_indicator', label: '财务指标表',  desc: '核心评价类指标（ROE/毛利率/增速/偿债能力等），来自 yjbb+ths 采集 + calc 计算',                  fields: 'gross_profit_margin, roe, roa, revenue_yoy, net_profit_yoy, current_ratio, debt_to_asset_ratio, free_cash_flow ...' },
    { name: 'stock_income',              label: '利润表',       desc: '利润表原始数据（营业总收入/营业成本/营业利润/净利润等），主要来自新浪接口',              fields: 'total_revenue, revenue, total_cost, operating_profit, net_profit, net_profit_incl_minority, eps_basic ...' },
    { name: 'stock_balance',             label: '资产负债表',  desc: '资产负债原始数据（总资产/总负债/净资产/流动资产/存货等），主要来自新浪接口',               fields: 'total_assets, total_liabilities, total_equity, parent_equity, cash_and_equivalents, inventory, fixed_assets ...' },
    { name: 'stock_cashflow',            label: '现金流量表',  desc: '现金流量原始数据（经营/投资/筹资现金流净额等），主要来自新浪接口，含 FCF 计算', fields: 'net_operate_cf, net_invest_cf, net_finance_cf, free_cash_flow, cash_received_sales ...' },
  ];

  // 核心指标说明
  const indicatorCols = [
    { title: '指标',     dataIndex: 'name',   key: 'name',   width: 140, render: (t) => <Text strong>{t}</Text> },
    { title: '说明',     dataIndex: 'desc',   key: 'desc' },
    { title: '公式 / 口径', dataIndex: 'formula', key: 'formula', width: 320 },
  ];
  const indicatorData = [
    { name: 'roe',                  desc: '净资产收益率',                 formula: '归母净利润 / 平均归母权益 × 100' },
    { name: 'roa',                  desc: '总资产收益率',                 formula: '净利润(含少数) / 总资产 × 100（年化）' },
    { name: 'roic',                 desc: '投入资本回报率',                formula: '(净利润+利息费用×(1-税率)) / (总权益+有息负债) × 100' },
    { name: 'revenue_yoy',          desc: '营收同比增速(%)',              formula: '(本期营收 - 上年同期) / |上年同期| × 100' },
    { name: 'net_profit_yoy',       desc: '归母净利润同比增速(%)',         formula: '(本期归母净利润 - 上年同期) / |上年同期| × 100' },
    { name: 'gross_profit_margin',  desc: '毛利率(%)',                  formula: '（营收 - 营业成本）/ 营收 × 100' },
    { name: 'net_profit_margin',    desc: '净利率(%)',                  formula: '净利润(含少数) / 营收 × 100（年化）' },
    { name: 'debt_to_asset_ratio', desc: '资产负债率(%)',               formula: '总负债 / 总资产 × 100' },
    { name: 'current_ratio',        desc: '流动比率',                    formula: '流动资产 / 流动负债' },
    { name: 'operating_cf_to_np',  desc: '经营现金流/净利润(%)',         formula: '经营现金流净额 / |净利润(含少数)| × 100' },
    { name: 'free_cash_flow',       desc: '自由现金流',                   formula: '经营现金流净额 + 投资现金流净额' },
  ];

  return (
    <section id="financial" style={{ paddingBottom: 32 }}>
      <Title level={2}>财务数据</Title>
      <Paragraph>
        财务数据是基本面分析的核心，涵盖利润表、资产负债表、现金流量表及 20+ 个派生评价指标。
        数据来源：东方财富（批量业绩报表）+ 同花顺（财务摘要）+ 新浪财经（三大表原始数据）。
      </Paragraph>

      {/* ── 数据更新完整流程 ───────────────────── */}
      <Divider orientation="left" plain>数据更新完整流程</Divider>
      <Alert
        type="warning"
        showIcon
        style={{ marginBottom: 20 }}
        message="更新顺序很重要"
        description="必须先完成 Step 1~3（原始数据采集），再运行 Step 4（计算派生指标），否则财务指标会有大量空值。"
      />
      <Row gutter={[16, 16]} style={{ marginBottom: 24 }}>
        {updateSteps.map((s) => (
          <Col xs={24} md={8} key={s.no}>
            <Badge.Ribbon text={`Step ${s.no}`} color={s.color}>
              <Card size="small" style={{ height: '100%' }}>
                <Text strong style={{ fontSize: 13, display: 'block', marginBottom: 6 }}>
                  {s.label}
                </Text>
                <Paragraph style={{ fontSize: 11, margin: 0, color: '#888' }}>
                  {s.desc}
                </Paragraph>
              </Card>
            </Badge.Ribbon>
          </Col>
        ))}
      </Row>

      {/* ── 数据表结构 ─────────────────────── */}
      <Divider orientation="left" plain>数据表结构</Divider>
      <Row gutter={[16, 16]} style={{ marginBottom: 24 }}>
        {tables.map((t) => (
          <Col xs={24} md={12} key={t.name}>
            <Card size="small" title={<span><DatabaseOutlined /> {t.label}</span>} style={{ height: '100%' }}>
              <Paragraph style={{ fontSize: 12, marginBottom: 8, color: '#888' }}>
                {t.desc}
              </Paragraph>
              <Paragraph style={{ fontSize: 11, margin: 0, color: '#666' }}>
                <Text type="secondary">主要字段：</Text><br/>
                <Text code style={{ fontSize: 10 }}>{t.fields}</Text>
              </Paragraph>
            </Card>
          </Col>
        ))}
      </Row>

      {/* ── 核心指标说明 ─────────────────────── */}
      <Divider orientation="left" plain>核心指标说明</Divider>
      <Table
        columns={indicatorCols}
        dataSource={indicatorData}
        rowKey="name"
        size="small"
        pagination={false}
        style={{ marginBottom: 24 }}
      />

      {/* ── 原有筛选功能（保留）───────────────────── */}
      <Divider orientation="left" plain>前端筛选功能</Divider>
      <Row gutter={[16, 16]}>
        <Col xs={24} md={8}>
          <Card type="inner" size="small" title="股票筛选">
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              支持按股票代码或名称模糊搜索，快速定位目标公司。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card type="inner" size="small" title="报告期范围">
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              选择财报发布的时间范围，支持按年度和季度精确筛选。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card type="inner" size="small" title="财务类型切换">
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              切换查看：主要指标 / 利润表 / 资产负债表 / 现金流量表。
            </Paragraph>
          </Card>
        </Col>
      </Row>

      {/* ── 使用建议 ─────────────────────── */}
      <Divider orientation="left" plain>使用建议</Divider>
      <Row gutter={[16, 16]}>
        <Col xs={24} md={12}>
          <Card size="small" style={{ background: '#f6ffed', border: '1px solid #b7eb8f' }}>
            <Text strong style={{ fontSize: 13, display: 'block', marginBottom: 6 }}>✅ 推荐做法</Text>
            <ul style={{ margin: 0, paddingLeft: 20, fontSize: 12, lineHeight: 1.8 }}>
              <li>每周运行一次完整财务更新（Step 1~5），保持数据新鲜</li>
              <li>财报季（4月/8月/10月）后优先跑 Step 1（东方财富批量更新最快）</li>
              <li>Step 3 超时失败时，用 <Text code>--start-code</Text> 断点续传</li>
            </ul>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card size="small" style={{ background: '#fffbe6', border: '1px solid #ffe58f' }}>
            <Text strong style={{ fontSize: 13, display: 'block', marginBottom: 6 }}>⚠️ 注意事项</Text>
            <ul style={{ margin: 0, paddingLeft: 20, fontSize: 12, lineHeight: 1.8 }}>
              <li>新浪接口有频率限制，并发数建议 ≤20，否则会触发限流</li>
              <li>北交所股票（4开头）财务数据覆盖率较低，属正常情况</li>
            </ul>
          </Card>
        </Col>
      </Row>
    </section>
  );
}
