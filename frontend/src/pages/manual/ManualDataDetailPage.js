import React from 'react';
import { Card, Typography, FloatButton, Tag, Alert, Table, Row, Col, Badge, Space } from 'antd';
import { DatabaseOutlined, FileSearchOutlined, BarChartOutlined } from '@ant-design/icons';
const { Title, Text, Paragraph } = Typography;

const dataDetailNav = [
  { id: 'financial-overview', label: '财务数据概述',  color: 'blue'      },
  { id: 'update-flow',        label: '更新流程',      color: 'green'     },
  { id: 'table-structure',    label: '数据表结构',    color: 'cyan'      },
  { id: 'indicators',         label: '核心指标',      color: 'orange'    },
  { id: 'research',           label: '研报数据',      color: 'purple'    },
  { id: 'usage-tips',         label: '使用建议',      color: 'geekblue'  },
];

export default function ManualDataDetailPage() {
  const scrollTo = (id) => {
    const el = document.getElementById(id);
    if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' });
  };

  return (
    <div style={{ maxWidth: 1400, margin: '0 auto', padding: '12px 24px 24px' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
        <Title level={3} style={{ margin: 0, fontSize: 20 }}>
          ⚡ 使用手册 · 数据详情
        </Title>
        <Text type="secondary" style={{ fontSize: 13 }}>财务数据 · 研报数据 · 数据表结构</Text>
      </div>

      {/* 顶部锚点导航 */}
      <Card size="small" style={{ marginBottom: 12 }} styles={{ body: {padding: '8px 12px'} }}>
        <Space size={[4, 4]} wrap>
          {dataDetailNav.map(item => (
            <a key={item.id} onClick={() => scrollTo(item.id)}>
              <Tag color={item.color}>{item.label}</Tag>
            </a>
          ))}
        </Space>
      </Card>

      <Card>
        {/* 财务数据概述 */}
        <section id="financial-overview" style={{ paddingBottom: 16 }}>
          <Title level={4} style={{ borderLeft: '4px solid #1677ff', paddingLeft: 12, marginBottom: 16 }}><DatabaseOutlined /> 财务数据概述</Title>
          <Paragraph>
            财务数据是基本面分析的核心，涵盖<Text strong>利润表、资产负债表、现金流量表</Text>及 <Text strong>20+ 个派生评价指标</Text>。
            数据来源：东方财富（批量业绩报表）+ 同花顺（财务摘要）+ 新浪财经（三大表原始数据）。
          </Paragraph>
          <Alert type="success" showIcon message="核心价值"
            description="财务数据帮你判断公司的盈利能力、成长性、偿债能力和现金流质量，是价值投资的基石。" />
        </section>

        {/* 数据更新完整流程 */}
        <section id="update-flow" style={{ paddingBottom: 16 }}>
          <Title level={4} style={{ borderLeft: '4px solid #52c41a', paddingLeft: 12, marginBottom: 16 }}>数据更新完整流程</Title>
          <Alert
            type="warning"
            showIcon
            style={{ marginBottom: 20 }}
            message="更新顺序很重要"
            description="必须先完成 Step 1~3（原始数据采集），再运行 Step 4（计算派生指标），否则财务指标会有大量空值。"
          />
          <Row gutter={[16, 16]} style={{ marginBottom: 24 }}>
            {[
              { no: 1, label: '东方财富业绩报表',    cmd: 'python scripts/update_financial_data.py --step yjbb',             desc: '批量拉取，写入 gross_margin/net_profit_yoy/roe 等',          color: 'blue' },
              { no: 2, label: '同花顺财务摘要',      cmd: 'python scripts/update_financial_data.py --step ths',              desc: '逐只补充 net_profit_margin/存货周转率/流动比率 等',   color: 'green' },
              { no: 3, label: '新浪三大表',          cmd: 'python scripts/update_financial_sina_fast.py --workers 15',  desc: '利润表+资产负债表+现金流量表（并发优化版）',        color: 'purple' },
              { no: 4, label: '计算派生指标',        cmd: 'python scripts/calc_financial_indicators.py',            desc: '计算 ROA/ROIC/同比增长率/周转率 等22个指标',   color: 'orange' },
              { no: 5, label: '重算因子值',          cmd: 'python scripts/recompute_factors.py',                   desc: '用最新财务数据重新计算所有因子',                 color: 'red' },
            ].map(s => (
              <Col xs={24} md={8} key={s.no}>
                <Badge.Ribbon text={`Step ${s.no}`} color={s.color}>
                  <Card size="small" style={{ height: '100%' }}>
                    <Text strong style={{ fontSize: 13, display: 'block', marginBottom: 6 }}>
                      {s.label}
                    </Text>
                    <Paragraph style={{ fontSize: 11, margin: '0 0 8px', color: '#888' }}>
                      {s.desc}
                    </Paragraph>
                    <Card size="small" style={{ background: '#fafafa', fontSize: 11 }}>
                      <Text code style={{ fontSize: 11 }}>{s.cmd}</Text>
                    </Card>
                  </Card>
                </Badge.Ribbon>
              </Col>
            ))}
          </Row>
        </section>

        {/* 数据表结构 */}
        <section id="table-structure" style={{ paddingBottom: 16 }}>
          <Title level={4} style={{ borderLeft: '4px solid #13c2c2', paddingLeft: 12, marginBottom: 16 }}>数据表结构</Title>
          <Row gutter={[16, 16]} style={{ marginBottom: 24 }}>
            {[
              { name: 'stock_financial_indicator', label: '财务指标表',  desc: '核心评价类指标（ROE/毛利率/增速/偿债能力等），来自 yjbb+ths 采集 + calc 计算',                  fields: 'gross_profit_margin, roe, roa, revenue_yoy, net_profit_yoy, current_ratio, debt_to_asset_ratio, free_cash_flow ...' },
              { name: 'stock_income',              label: '利润表',       desc: '利润表原始数据（营业总收入/营业成本/营业利润/净利润等），主要来自新浪接口',              fields: 'total_revenue, revenue, total_cost, operating_profit, net_profit, net_profit_incl_minority, eps_basic ...' },
              { name: 'stock_balance',             label: '资产负债表',  desc: '资产负债原始数据（总资产/总负债/净资产/流动资产/存货等），主要来自新浪接口',               fields: 'total_assets, total_liabilities, total_equity, parent_equity, cash_and_equivalents, inventory, fixed_assets ...' },
              { name: 'stock_cashflow',            label: '现金流量表',  desc: '现金流量原始数据（经营/投资/筹资现金流净额等），主要来自新浪接口，含 FCF 计算', fields: 'net_operate_cf, net_invest_cf, net_finance_cf, free_cash_flow, cash_received_sales ...' },
            ].map((t) => (
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
        </section>

        {/* 核心指标说明 */}
        <section id="indicators" style={{ paddingBottom: 16 }}>
          <Title level={4} style={{ borderLeft: '4px solid #fa8c16', paddingLeft: 12, marginBottom: 16 }}>核心指标说明</Title>
          <Paragraph>以下指标在 <Text code>stock_financial_indicator</Text> 表中，是基本面评分的核心依据：</Paragraph>
          <Table
            size="small"
            pagination={false}
            rowKey="name"
            columns={[
              { title: '指标',     dataIndex: 'name',   key: 'name',   width: 140, render: (t) => <Text strong>{t}</Text> },
              { title: '说明',     dataIndex: 'desc',   key: 'desc' },
              { title: '公式 / 口径', dataIndex: 'formula', key: 'formula', width: 320 },
            ]}
            dataSource={[
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
            ]}
            style={{ marginBottom: 24 }}
          />
          <Alert type="info" showIcon message="指标使用建议"
            description="ROE > 15% 且营收增速 > 10% = 成长股特征；毛利率 > 30% = 有护城河；经营现金流/净利润 > 0.8 = 高质量盈利" />
        </section>

        {/* 研报数据 */}
        <section id="research" style={{ paddingBottom: 16 }}>
          <Title level={4} style={{ borderLeft: '4px solid #722ed1', paddingLeft: 12, marginBottom: 16 }}><FileSearchOutlined /> 研报数据</Title>
          <Paragraph>
            研报数据来自 <Text code>stock_research_report</Text> 表（约2万条）和 <Text code>stock_sentiment_survey</Text> 表（2329条），
            是事件面评分和基本面研究的重要参考。
          </Paragraph>

          <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
            <Col xs={24} md={12}>
              <Card size="small" type="inner" title="研报数据说明">
                <ul style={{ margin: 0, paddingLeft: 16, fontSize: 12 }}>
                  <li>每份研报包含：评级（买入/增持/中性/减持）、目标价、发布机构</li>
                  <li>「买入」评级占比 = 事件面评分的核心输入</li>
                  <li>近90天无研报覆盖 = 机构关注度低，需谨慎</li>
                  <li>目标价 vs 当前价 = 向上空间估算依据</li>
                </ul>
              </Card>
            </Col>
            <Col xs={24} md={12}>
              <Card size="small" type="inner" title="舆情数据说明">
                <ul style={{ margin: 0, paddingLeft: 16, fontSize: 12 }}>
                  <li>调查日期、投资者情绪（看多/看空/中性）</li>
                  <li>情绪多空比超过 2:1 = 市场情绪极度乐观（反向指标参考）</li>
                  <li>研报评级变化 = 机构态度转变信号</li>
                  <li>结合技术面使用，避免单一维度判断</li>
                </ul>
              </Card>
            </Col>
          </Row>

          <Card size="small" style={{ borderLeft: '4px solid #722ed1' }}>
            <Text strong>事件面评分中的研报权重（12分）：</Text>
                <Paragraph style={{ fontSize: 12, margin: '4px 0 0' }}>
                  买入/增持评级占比 × 12分；评级机构数量加权。
                  <Text strong>买入占比超过60%</Text> = 事件面高分核心信号；
                  若近90天无研报，事件面该项得0分。
                </Paragraph>
          </Card>
        </section>

        {/* 使用建议 */}
        <section id="usage-tips" style={{ paddingBottom: 16 }}>
          <Title level={4} style={{ borderLeft: '4px solid #2f54eb', paddingLeft: 12, marginBottom: 16 }}>使用建议</Title>
          <Row gutter={[16, 16]}>
            <Col xs={24} md={12}>
              <Card size="small" style={{ background: '#f6ffed', border: '1px solid #b7eb8f' }}>
                <Text strong style={{ fontSize: 13, display: 'block', marginBottom: 6 }}>✅ 推荐做法</Text>
                <ul style={{ margin: 0, paddingLeft: 20, fontSize: 12, lineHeight: 1.8 }}>
                  <li>每周运行一次完整财务更新（Step 1~5），保持数据新鲜</li>
                  <li>财报季（4月/8月/10月）后优先跑 Step 1（东方财富批量更新最快）</li>
                  <li>Step 3 超时失败时，用 <Text code>--start-code</Text> 断点续传</li>
                  <li>因子回测前确保 <Text code>recompute_factors.py</Text> 已运行</li>
                  <li>关注研报评级变化，而非单一评级</li>
                </ul>
              </Card>
            </Col>
            <Col xs={24} md={12}>
              <Card size="small" style={{ background: '#fffbe6', border: '1px solid #ffe58f' }}>
                <Text strong style={{ fontSize: 13, display: 'block', marginBottom: 6 }}>⚠️ 注意事项</Text>
                <ul style={{ margin: 0, paddingLeft: 20, fontSize: 12, lineHeight: 1.8 }}>
                  <li>新浪接口有频率限制，并发数建议 ≤20，否则会触发限流</li>
                  <li>北交所股票（4开头）财务数据覆盖率较低，属正常情况</li>
                  <li><Text code>calc_financial_indicators.py</Text> 只更新非空值，可重复运行</li>
                  <li>分红除权数据用独立脚本 <Text code>update_dividend_baostock.py</Text></li>
                  <li>研报数据仅供参考，不构成投资建议</li>
                </ul>
              </Card>
            </Col>
          </Row>
        </section>
      </Card>
      <FloatButton.BackTop />
    </div>
  );
}
