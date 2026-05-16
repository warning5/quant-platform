import React from 'react';
import { Card, Typography, FloatButton, Tag, Alert, Table, Row, Col, Space, Divider, Badge } from 'antd';
import {
  DatabaseOutlined, FileSearchOutlined, StockOutlined,
  ThunderboltOutlined, TableOutlined, BarChartOutlined,
} from '@ant-design/icons';
import ManualDataUpdate from './sections/ManualDataUpdate.js';
import ManualFinancial from './sections/ManualFinancial.js';

const { Title, Text, Paragraph } = Typography;

const dataInfoNav = [
  { id: 'data-update',   label: '数据更新',   color: 'blue'     },
  { id: 'financial',     label: '财务数据',   color: 'green'    },
  { id: 'research',      label: '研报数据',   color: 'purple'   },
  { id: 'sector',        label: '行业排行',   color: 'orange'   },
];

export default function ManualDataInfoPage() {
  const scrollTo = (id) => {
    const el = document.getElementById(id);
    if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' });
  };

  return (
    <div style={{ maxWidth: 1400, margin: '0 auto', padding: '12px 24px 24px' }}>
      {/* 页面标题 */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
        <Title level={3} style={{ margin: 0, fontSize: 20 }}>
          📊 使用手册 · 数据信息
        </Title>
        <Text type="secondary" style={{ fontSize: 13 }}>
          数据更新 · 财务数据 · 研报数据 · 行业排行
        </Text>
      </div>

      {/* 锚点导航 */}
      <Card size="small" style={{ marginBottom: 12 }} bodyStyle={{ padding: '8px 12px' }}>
        <Space size={[4, 4]} wrap>
          {dataInfoNav.map(item => (
            <a key={item.id} onClick={() => scrollTo(item.id)}>
              <Tag color={item.color}>{item.label}</Tag>
            </a>
          ))}
        </Space>
      </Card>

      <Card>
        {/* ── 第一章：数据更新 ───────────────────────────────────── */}
        <div id="data-update" style={{ paddingBottom: 24 }}>
          <Title level={4} style={{ borderLeft: '4px solid #1677ff', paddingLeft: 12, marginBottom: 16 }}>
            <ThunderboltOutlined /> 数据更新
          </Title>
          <Paragraph>
            数据更新模块负责从外部数据源（Baostock、腾讯证券、akshare）获取最新的行情、财务、情绪等数据，
            并写入 MySQL / ClickHouse 双数据库，支持断点续传和多数据源回退。
          </Paragraph>

          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            message="推荐更新时机"
            description="建议在每日收盘后1~2小时执行数据更新，此时当天的行情数据已准备好。"
          />

          {/* 快速操作 */}
          <Title level={5} style={{ marginBottom: 12 }}>快速操作</Title>
          <Row gutter={[16, 12]} style={{ marginBottom: 16 }}>
            <Col xs={24} md={6}>
              <Card size="small" styles={{ body: { padding: '10px 14px' } }}>
                <Text strong style={{ fontSize: 13, display: 'block', marginBottom: 4 }}>📊 日常行情更新</Text>
                <Paragraph style={{ fontSize: 12, margin: 0, color: '#666' }}>
                  选「全部市场」+「全部数据源」<br/>点击「开始更新」即可
                </Paragraph>
              </Card>
            </Col>
            <Col xs={24} md={6}>
              <Card size="small" styles={{ body: { padding: '10px 14px' } }}>
                <Text strong style={{ fontSize: 13, display: 'block', marginBottom: 4 }}>🔄 断点续传</Text>
                <Paragraph style={{ fontSize: 12, margin: 0, color: '#666' }}>
                  勾选「断点续传」<br/>跳过已完成股票继续
                </Paragraph>
              </Card>
            </Col>
            <Col xs={24} md={6}>
              <Card size="small" styles={{ body: { padding: '10px 14px' } }}>
                <Text strong style={{ fontSize: 13, display: 'block', marginBottom: 4 }}>📈 仅更新日线</Text>
                <Paragraph style={{ fontSize: 12, margin: 0, color: '#666' }}>
                  勾选「仅更新日线」<br/>不更新股票基本信息
                </Paragraph>
              </Card>
            </Col>
            <Col xs={24} md={6}>
              <Card size="small" styles={{ body: { padding: '10px 14px' } }}>
                <Text strong style={{ fontSize: 13, display: 'block', marginBottom: 4 }}>💰 财务数据更新</Text>
                <Paragraph style={{ fontSize: 12, margin: 0, color: '#666' }}>
                  设置年份范围<br/>默认跳过已有数据
                </Paragraph>
              </Card>
            </Col>
          </Row>

          <Divider plain style={{ margin: '16px 0' }}>各Tab说明</Divider>

          {/* 股票日线 */}
          <Card size="small" title={<Space><span>📊 股票日线</span><Tag color="blue">最常用</Tag></Space>} style={{ marginBottom: 10 }}>
            <Row gutter={[16, 8]}>
              <Col xs={24} md={12}>
                <Text strong>配置选项：</Text>
                <ul style={{ margin: '6px 0', fontSize: 13 }}>
                  <li><Text code>市场</Text>：全部市场 / 沪市 / 深市 / 北交所</li>
                  <li><Text code>数据源</Text>：全部 / Baostock(沪深) / 腾讯证券(北交所)</li>
                  <li><Text code>股票池</Text>：全部 / 沪深300 / 上证50 / 中证500 / 中证1000 / 科创板50</li>
                </ul>
              </Col>
              <Col xs={24} md={12}>
                <Text strong>快捷选项：</Text>
                <ul style={{ margin: '6px 0', fontSize: 13 }}>
                  <li><Text code>断点续传</Text>：网络中断后可继续，跳过已有数据股票</li>
                  <li><Text code>排除ST</Text>：过滤 ST/*ST 股票</li>
                  <li><Text code>仅更新日线</Text>：只更新量价，不更新股票信息</li>
                  <li><Text code>仅更新股票信息</Text>：只更新基本信息，不更新日线</li>
                </ul>
              </Col>
            </Row>
            <Alert type="warning" showIcon style={{ marginTop: 6 }}
              message="注意：北交所股票需选择「全部数据源」或「腾讯证券」，Baostock 不覆盖北交所" />
          </Card>

          {/* 指数日线 */}
          <Card size="small" title={<Space><span>📈 指数日线</span><Tag color="cyan">后台自动</Tag></Space>} style={{ marginBottom: 10 }}>
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              <Text strong>配置：</Text>仅「断点续传」选项<br/>
              <Text strong>说明：</Text>更新沪深300、中证500、上证50等10个主要指数日线数据，数据源为 Baostock，通常每日自动更新
            </Paragraph>
          </Card>

          {/* 分红除权 */}
          <Card size="small" title={<Space><span>🎁 分红除权</span><Tag color="orange">按需更新</Tag></Space>} style={{ marginBottom: 10 }}>
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              <Text strong>配置：</Text>仅「跳过已有」选项（默认勾选）<br/>
              <Text strong>说明：</Text>补全历史分红除权数据，首次运行后通常无需再次更新<br/>
              <Text strong>数据源：</Text>巨潮 → 同花顺 → 东方财富（三级自动回退）
            </Paragraph>
          </Card>

          {/* 财务数据 */}
          <Card size="small" title={<Space><span>💼 财务数据</span><Tag color="purple">财报后更新</Tag></Space>} style={{ marginBottom: 10 }}>
            <Row gutter={[16, 8]}>
              <Col xs={24} md={12}>
                <Text strong>配置选项：</Text>
                <ul style={{ margin: '6px 0', fontSize: 13 }}>
                  <li><Text code>年份范围</Text>：默认采集近3年，可扩大范围补全更早数据</li>
                  <li><Text code>强制重新采集</Text>：覆盖已存在的财务数据（谨慎使用）</li>
                </ul>
              </Col>
              <Col xs={24} md={12}>
                <Text strong>说明：</Text>
                <ul style={{ margin: '6px 0', fontSize: 13 }}>
                  <li>默认跳过已有报告期数据，不会重复写入</li>
                  <li>年报/季报发布季（4/8/10月）运行一次即可</li>
                  <li>数据源：同花顺 iFind → 东方财富（自动回退）</li>
                </ul>
              </Col>
            </Row>
          </Card>

          {/* 情绪数据 */}
          <Card size="small" title={<Space><span>⚡ 情绪数据</span><Tag color="geekblue">每日更新</Tag></Space>} style={{ marginBottom: 10 }}>
            <Row gutter={[16, 8]}>
              <Col xs={24} md={12}>
                <Text strong>可更新内容：</Text>
                <ul style={{ margin: '6px 0', fontSize: 13 }}>
                  <li><Text code>龙虎榜</Text>：涨跌停异动股详情 + 机构统计</li>
                  <li><Text code>融资融券</Text>：两融汇总 + 个股明细（杠杆资金动向）</li>
                  <li><Text code>机构调研</Text>：上市公司接待调研记录（机构关注度）</li>
                  <li><Text code>大宗交易</Text>：大单交易记录（折溢价率）</li>
                  <li><Text code>涨跌停池</Text>：涨停/跌停股票池（量价异动）</li>
                </ul>
              </Col>
              <Col xs={24} md={12}>
                <Text strong>配置选项：</Text>
                <ul style={{ margin: '6px 0', fontSize: 13 }}>
                  <li><Text code>日期范围</Text>：默认最近7天，可自定义起止日期</li>
                  <li><Text code>数据类型</Text>：可勾选需要的具体情绪数据类型</li>
                  <li><Text code>数据源</Text>：东方财富 + 同花顺（akshare 封装）</li>
                </ul>
              </Col>
            </Row>
          </Card>

          {/* 研报数据 */}
          <Card size="small" title={<Space><span>📄 研报数据</span><Tag color="purple">事件面</Tag></Space>} style={{ marginBottom: 10 }}>
            <Row gutter={[16, 8]}>
              <Col xs={24} md={12}>
                <Text strong>配置选项：</Text>
                <ul style={{ margin: '6px 0', fontSize: 13 }}>
                  <li><Text code>股票代码</Text>：输入单只股票代码（支持模糊匹配）</li>
                  <li><Text code>批量导入</Text>：支持多行逗号分隔批量查询</li>
                  <li><Text code>排序方式</Text>：按发布时间/评级/机构排序</li>
                </ul>
              </Col>
              <Col xs={24} md={12}>
                <Text strong>说明：</Text>
                <ul style={{ margin: '6px 0', fontSize: 13 }}>
                  <li>研报数据来自 <Text code>stock_research_report</Text> 表（约2万条）</li>
                  <li>展示评级（买入/增持/中性/减持）、目标价、发布机构</li>
                  <li>舆情调查来自 <Text code>stock_sentiment_survey</Text> 表（2329条）</li>
                  <li>数据源：东方财富 + 同花顺（akshare 封装）</li>
                </ul>
              </Col>
            </Row>
          </Card>

          {/* 退市清理 */}
          <Card size="small" title={<Space><span>🗑️ 退市清理</span><Tag color="red">风险清理</Tag></Space>} style={{ marginBottom: 10 }}>
            <Row gutter={[16, 8]}>
              <Col xs={24} md={12}>
                <Text strong>检测原理：</Text>
                <ul style={{ margin: '6px 0', fontSize: 13 }}>
                  <li>通过 Baostock 差分检测系统中已退市股票</li>
                  <li>判断逻辑：stock_info 存在但 Baostock 已不包含的股票</li>
                  <li>标记退市日期、最后交易日、停牌天数</li>
                </ul>
              </Col>
              <Col xs={24} md={12}>
                <Text strong>清理范围：</Text>
                <ul style={{ margin: '6px 0', fontSize: 13 }}>
                  <li>清理前可查看：日线数据/因子数据/资金流数据条数</li>
                  <li>清理内容：stock_info / stock_daily / factor_value / moneyflow</li>
                  <li><Text type="danger">⚠️ 清理操作不可撤销，请谨慎确认</Text></li>
                </ul>
              </Col>
            </Row>
            <Alert type="warning" showIcon style={{ marginTop: 6 }}
              message="建议定期执行退市清理，保持数据库健康，防止策略误选退市股票。" />
          </Card>

          {/* 数据源说明 */}
          <Title level={5} style={{ marginBottom: 10 }}>数据源说明</Title>
          <Table
            size="small" pagination={false} rowKey="type"
            columns={[
              { title: '数据类型', dataIndex: 'type', key: 'type', width: 120 },
              { title: '主数据源', dataIndex: 'primary', key: 'primary', width: 150 },
              { title: '备用数据源', dataIndex: 'backup', key: 'backup' },
              { title: '备注', dataIndex: 'note', key: 'note' },
            ]}
            dataSource={[
              { type: '沪深日线',   primary: 'Baostock',    backup: 'akshare',          note: '覆盖全面，更新及时' },
              { type: '北交所日线', primary: '腾讯证券',    backup: '-',                 note: '仅腾讯覆盖北交所' },
              { type: '指数日线',   primary: 'Baostock',    backup: '-',                 note: '10个主要指数' },
              { type: '分红除权',   primary: '巨潮',        backup: '同花顺→东财',       note: '三级回退机制' },
              { type: '财务数据',   primary: '同花顺',      backup: '东方财富',          note: '年报后补全' },
              { type: '情绪数据',   primary: '东方财富',    backup: '同花顺',            note: '龙虎榜/融资融券/调研/大宗' },
              { type: '资金流向',   primary: '东方财富',    backup: '-',                 note: '个股主力净流入，真实数据' },
            ]}
            style={{ marginBottom: 16 }}
          />

          {/* 任务状态 */}
          <Title level={5} style={{ marginBottom: 10 }}>任务状态说明</Title>
          <Row gutter={[12, 8]}>
            {[
              { status: '空闲',   color: 'default',    desc: '等待开始' },
              { status: '运行中', color: 'processing', desc: '正在采集' },
              { status: '已完成', color: 'success',    desc: '成功结束' },
              { status: '失败',   color: 'error',      desc: '出错中断' },
              { status: '已取消', color: 'warning',    desc: '手动停止' },
            ].map(s => (
              <Col xs={12} md={6} key={s.status}>
                <Tag color={s.color}>{s.status}</Tag> {s.desc}
              </Col>
            ))}
          </Row>

          {/* 常见问题 */}
          <Divider plain style={{ margin: '16px 0' }}>常见问题</Divider>
          <Row gutter={[16, 12]}>
            {[
              { q: '网络中断后如何继续？', a: '勾选「断点续传」后重新开始，系统自动跳过已完成的股票。' },
              { q: '北交所数据没更新？', a: '检查数据源是否选择「全部」或「腾讯证券」，Baostock 不覆盖北交所。' },
              { q: '财务数据需要更新吗？', a: '平时不需要，年报/季报发布季（4/8/10月）运行一次即可，默认跳过已有数据。' },
              { q: '如何只更新特定指数？', a: '当前版本暂不支持指定指数，指数日线会更新全部10个主要指数。' },
              { q: '资金流向数据如何更新？', a: '在情绪数据Tab中勾选相应选项，支持当日全量更新和历史回补（120天）。' },
            ].map((item, i) => (
              <Col xs={24} md={12} key={i}>
                <Card size="small" type="inner">
                  <Text strong>Q: {item.q}</Text>
                  <Paragraph style={{ fontSize: 12, margin: '4px 0 0' }}>A: {item.a}</Paragraph>
                </Card>
              </Col>
            ))}
          </Row>
        </div>

        {/* ── 第二章：财务数据（直接嵌入，不重复引入）───────────── */}
        <div id="financial" style={{ paddingTop: 16 }}>
          <Title level={4} style={{ borderLeft: '4px solid #52c41a', paddingLeft: 12, marginBottom: 16 }}>
            <DatabaseOutlined /> 财务数据
          </Title>
          <Paragraph>
            财务数据是基本面分析的核心，涵盖<Text strong>利润表、资产负债表、现金流量表</Text>及 <Text strong>20+ 个派生评价指标</Text>。
            数据来源：东方财富（批量业绩报表）+ 同花顺（财务摘要）+ 新浪财经（三大表原始数据）。
          </Paragraph>

          {/* 更新流程 */}
          <Alert type="warning" showIcon style={{ marginBottom: 20 }}
            message="更新顺序很重要"
            description="必须先完成 Step 1~3（原始数据采集），再运行 Step 4（计算派生指标），否则财务指标会有大量空值。" />
          <Row gutter={[16, 16]} style={{ marginBottom: 24 }}>
            {[
              { no: 1, label: '东方财富业绩报表', cmd: 'python scripts/update_financial_data.py --step yjbb',        desc: '批量拉取，写入 gross_margin/net_profit_yoy/roe 等',         color: 'blue'   },
              { no: 2, label: '同花顺财务摘要',   cmd: 'python scripts/update_financial_data.py --step ths',         desc: '逐只补充 net_profit_margin/存货周转率/流动比率 等',  color: 'green'  },
              { no: 3, label: '新浪三大表',       cmd: 'python scripts/update_financial_sina_fast.py --workers 15', desc: '利润表+资产负债表+现金流量表（并发优化版）',       color: 'purple' },
              { no: 4, label: '计算派生指标',     cmd: 'python scripts/calc_financial_indicators.py',         desc: '计算 ROA/ROIC/同比增长率/周转率 等22个指标',      color: 'orange' },
              { no: 5, label: '重算因子值',       cmd: 'python scripts/recompute_factors.py',             desc: '用最新财务数据重新计算所有因子',                color: 'red'    },
            ].map(s => (
              <Col xs={24} md={8} key={s.no}>
                <Badge.Ribbon text={`Step ${s.no}`} color={s.color}>
                  <Card size="small" style={{ height: '100%' }}>
                    <Text strong style={{ fontSize: 13, display: 'block', marginBottom: 6 }}>{s.label}</Text>
                    <Paragraph style={{ fontSize: 11, margin: '0 0 8px', color: '#888' }}>{s.desc}</Paragraph>
                    <Card size="small" style={{ background: '#fafafa', fontSize: 11 }}>
                      <Text code style={{ fontSize: 11 }}>{s.cmd}</Text>
                    </Card>
                  </Card>
                </Badge.Ribbon>
              </Col>
            ))}
          </Row>

          {/* 数据表结构 */}
          <Title level={5} style={{ marginBottom: 12 }}>数据表结构</Title>
          <Row gutter={[16, 16]} style={{ marginBottom: 20 }}>
            {[
              { name: 'stock_financial_indicator', label: '财务指标表', desc: '核心评价类指标（ROE/毛利率/增速/偿债能力等），来自 yjbb+ths 采集 + calc 计算',
                fields: 'gross_profit_margin, roe, roa, revenue_yoy, net_profit_yoy, current_ratio, debt_to_asset_ratio, free_cash_flow ...' },
              { name: 'stock_income',              label: '利润表',      desc: '利润表原始数据（营业总收入/营业成本/营业利润/净利润等），主要来自新浪接口',
                fields: 'total_revenue, revenue, total_cost, operating_profit, net_profit, net_profit_incl_minority, eps_basic ...' },
              { name: 'stock_balance',             label: '资产负债表',  desc: '资产负债原始数据（总资产/总负债/净资产/流动资产/存货等），主要来自新浪接口',
                fields: 'total_assets, total_liabilities, total_equity, parent_equity, cash_and_equivalents, inventory, fixed_assets ...' },
              { name: 'stock_cashflow',            label: '现金流量表',  desc: '现金流量原始数据（经营/投资/筹资现金流净额等），主要来自新浪接口，含 FCF 计算',
                fields: 'net_operate_cf, net_invest_cf, net_finance_cf, free_cash_flow, cash_received_sales ...' },
            ].map(t => (
              <Col xs={24} md={12} key={t.name}>
                <Card size="small" title={<span><DatabaseOutlined /> {t.label}</span>} style={{ height: '100%' }}>
                  <Paragraph style={{ fontSize: 12, marginBottom: 8, color: '#888' }}>{t.desc}</Paragraph>
                  <Paragraph style={{ fontSize: 11, margin: 0, color: '#666' }}>
                    <Text type="secondary">主要字段：</Text><br/>
                    <Text code style={{ fontSize: 10 }}>{t.fields}</Text>
                  </Paragraph>
                </Card>
              </Col>
            ))}
          </Row>

          {/* 核心指标 */}
          <Title level={5} style={{ marginBottom: 12 }}>核心指标说明</Title>
          <Table
            size="small" pagination={false} rowKey="name"
            columns={[
              { title: '指标',           dataIndex: 'name',    key: 'name',    width: 150, render: t => <Text strong>{t}</Text> },
              { title: '说明',           dataIndex: 'desc',    key: 'desc' },
              { title: '公式 / 口径',    dataIndex: 'formula', key: 'formula', width: 300 },
            ]}
            dataSource={[
              { name: 'roe',                  desc: '净资产收益率',                  formula: '归母净利润 / 平均归母权益 × 100' },
              { name: 'roa',                  desc: '总资产收益率',                  formula: '净利润(含少数) / 总资产 × 100（年化）' },
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
            style={{ marginBottom: 20 }}
          />
          <Alert type="info" showIcon message="指标使用建议"
            description="ROE > 15% 且营收增速 > 10% = 成长股特征；毛利率 > 30% = 有护城河；经营现金流/净利润 > 0.8 = 高质量盈利" />

          {/* 使用建议 */}
          <Divider plain style={{ margin: '24px 0 16px' }}>使用建议</Divider>
          <Row gutter={[16, 12]}>
            <Col xs={24} md={12}>
              <Card size="small" style={{ background: '#f6ffed', border: '1px solid #b7eb8f' }}>
                <Text strong style={{ fontSize: 13, display: 'block', marginBottom: 6 }}>✅ 推荐做法</Text>
                <ul style={{ margin: 0, paddingLeft: 20, fontSize: 12, lineHeight: 1.8 }}>
                  <li>每周运行一次完整财务更新（Step 1~5），保持数据新鲜</li>
                  <li>财报季（4月/8月/10月）后优先跑 Step 1（东方财富批量更新最快）</li>
                  <li>Step 3 超时失败时，用 <Text code>--start-code</Text> 断点续传</li>
                  <li>因子回测前确保 <Text code>recompute_factors.py</Text> 已运行</li>
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
                </ul>
              </Card>
            </Col>
          </Row>
        </div>

        {/* ── 第三章：研报数据 ───────────────────────────────────── */}
        <div id="research" style={{ paddingTop: 16 }}>
          <Title level={4} style={{ borderLeft: '4px solid #722ed1', paddingLeft: 12, marginBottom: 16 }}>
            <FileSearchOutlined /> 研报数据
          </Title>
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
              <Card size="small" type="inner" title="舆情调查说明">
                <ul style={{ margin: 0, paddingLeft: 16, fontSize: 12 }}>
                  <li>调查日期、投资者情绪（看多/看空/中性）</li>
                  <li>情绪多空比超过 2:1 = 市场情绪极度乐观（反向指标参考）</li>
                  <li>研报评级变化 = 机构态度转变信号</li>
                  <li>结合技术面使用，避免单一维度判断</li>
                </ul>
              </Card>
            </Col>
          </Row>

          <Card size="small" style={{ borderLeft: '4px solid #722ed1', marginBottom: 16 }}>
            <Text strong>事件面评分中的研报权重（12分）：</Text>
            <Paragraph style={{ fontSize: 12, margin: '4px 0 0' }}>
              买入/增持评级占比 × 12分；评级机构数量加权。
              <Text strong>买入占比超过60%</Text> = 事件面高分核心信号；
              若近90天无研报，事件面该项得0分。
            </Paragraph>
          </Card>

          <Alert type="info" showIcon message="研报数据使用提示"
            description="研报数据仅供参考，不构成投资建议。机构评级具有时效性，建议关注评级变化而非单一评级。" />
        </div>

        {/* ── 第四章：行业排行 ───────────────────────────────────── */}
        <div id="sector" style={{ paddingTop: 16 }}>
          <Title level={4} style={{ borderLeft: '4px solid #fa8c16', paddingLeft: 12, marginBottom: 16 }}>
            <StockOutlined /> 行业排行
          </Title>
          <Paragraph>
            行业排行页面展示申万行业分类和概念板块的涨跌排行，可查看板块内成分股详情，
            同时支持<Text strong>热门概念板块</Text>专题，是主题投资和板块轮动分析的核心工具。
          </Paragraph>

          <Alert type="success" showIcon style={{ marginBottom: 16 }} message="核心价值"
            description="快速捕捉市场热点方向，找到当日/近期资金最集中的板块，辅助选股决策。通过行业排名快速判断市场风格（成长 vs 价值），调整持仓行业配置。" />

          {/* 热门行业 */}
          <Title level={5} style={{ marginBottom: 12, color: '#f5222d' }}>🔥 热门行业专题</Title>
          <Paragraph>
            基于<Text strong>东方财富概念板块</Text>数据，实时计算各板块平均涨跌幅，
            展示<Text strong>20个热门概念板块</Text>（科技AI/芯片/机器人、新能源储能/光伏、国防军工、医药等）。
          </Paragraph>
          <Row gutter={[12, 12]} style={{ marginBottom: 16 }}>
            <Col xs={24} md={12}>
              <Card size="small" type="inner" title="板块分类" style={{ borderLeft: '4px solid #1677ff' }}>
                <ul style={{ margin: '4px 0', paddingLeft: 16, fontSize: 12 }}>
                  <li><Text strong>科技</Text>（AI/芯片/机器人/信创/消费电子）</li>
                  <li><Text strong>新能源</Text>（储能/光伏/新能源车/锂电池/氢能源）</li>
                  <li><Text strong>国防</Text>（军工/低空经济）</li>
                  <li><Text strong>医药</Text>（医疗器械/创新药）</li>
                </ul>
              </Card>
            </Col>
            <Col xs={24} md={12}>
              <Card size="small" type="inner" title="板块详情页" style={{ borderLeft: '4px solid #52c41a' }}>
                <Paragraph style={{ fontSize: 12, margin: 0 }}>
                  点击板块卡片 → 查看<Text strong>板块内全部股票</Text>（涨跌幅排序）+
                  <Text strong>板块指数K线图</Text>（近60日）+ <Text strong>资金流向</Text>（主力净流入）。
                </Paragraph>
              </Card>
            </Col>
            <Col xs={24} md={12}>
              <Card size="small" type="inner" title="实时更新" style={{ borderLeft: '4px solid #fa8c16' }}>
                <Paragraph style={{ fontSize: 12, margin: 0 }}>
                  数据来自东方财富 API，<Text strong>每日收盘后更新</Text>。
                  板块涨跌幅 = 板块内全部成分股涨跌幅的平均值。
                </Paragraph>
              </Card>
            </Col>
            <Col xs={24} md={12}>
              <Card size="small" type="inner" title="使用建议" style={{ borderLeft: '4px solid #722ed1' }}>
                <Paragraph style={{ fontSize: 12, margin: 0 }}>
                  板块连续3日排名前5 = <Text strong>强主题持续</Text>，可重点关注；
                  单日突涨板块需结合成交量判断是否为一日游行情。
                </Paragraph>
              </Card>
            </Col>
          </Row>

          {/* 申万行业排名 */}
          <Title level={5} style={{ marginBottom: 12 }}>📊 申万行业排名</Title>
          <Paragraph>
            按<Text strong>申万一级行业</Text>分类，展示各行业的平均涨跌幅排名，
            支持<Text strong>申万行业</Text>和<Text strong>概念板块</Text>两种分类方式切换。
          </Paragraph>

          <Title level={5} style={{ marginBottom: 12 }}>视图说明</Title>
          <Row gutter={[16, 12]} style={{ marginBottom: 16 }}>
            <Col xs={24} md={8}>
              <Card size="small" type="inner" title={<><TableOutlined /> 表格视图</>}>
                <ul style={{ margin: 0, paddingLeft: 16, fontSize: 12 }}>
                  <li>申万一级行业涨跌排行（点击可查看成分股）</li>
                  <li>概念板块涨跌排行（人工智能/新能源/军工等）</li>
                  <li>支持按涨跌幅/PE/PB排序</li>
                </ul>
              </Card>
            </Col>
            <Col xs={24} md={8}>
              <Card size="small" type="inner" title={<><BarChartOutlined /> 卡片视图（热门板块）</>}>
                <ul style={{ margin: 0, paddingLeft: 16, fontSize: 12 }}>
                  <li>按行业主题分类展示（科技/新能源/国防/医药等）</li>
                  <li>卡片显示：板块均幅涨跌幅、成分股数量、中位数PE/PB</li>
                  <li>领涨个股标签，可快速定位龙头</li>
                </ul>
              </Card>
            </Col>
            <Col xs={24} md={8}>
              <Card size="small" type="inner" title="成分股下钻">
                <ul style={{ margin: 0, paddingLeft: 16, fontSize: 12 }}>
                  <li>点击行业/概念名称，进入成分股详情</li>
                  <li>查看：涨跌幅/PE/PB/换手率/市值</li>
                  <li>支持按多指标排序，发现结构性机会</li>
                </ul>
              </Card>
            </Col>
          </Row>

          <Title level={5} style={{ marginBottom: 12 }}>行业下钻功能</Title>
          <Paragraph style={{ marginBottom: 12 }}>点击任意行业行，展开查看<Text strong>该行业内全部股票</Text>的排名表格，支持按涨跌幅/PE/PB/市值排序。</Paragraph>
          <Row gutter={[12, 8]} style={{ marginBottom: 16 }}>
            {[
              { label: '涨跌幅排序', desc: '默认按行业平均涨跌幅排序，点击行业名展开个股列表' },
              { label: 'PE/PB排序', desc: '在个股列表中可按估值指标重新排序，找行业内的低估值标的' },
              { label: '市值排序', desc: '找行业龙头（大市值）或弹性标的（小市值）' },
              { label: '换手率排序', desc: '找行业内最活跃个股，通常联动性更强' },
            ].map((item, i) => (
              <Col xs={24} md={12} key={i}>
                <Card size="small" type="inner">
                  <Text strong style={{ fontSize: 12 }}>{item.label}</Text>
                  <Paragraph style={{ fontSize: 11, margin: '2px 0 0' }}>{item.desc}</Paragraph>
                </Card>
              </Col>
            ))}
          </Row>

          <Title level={5} style={{ marginBottom: 12 }}>排序与筛选</Title>
          <Row gutter={[12, 8]} style={{ marginBottom: 16 }}>
            {[
              { feature: '按涨跌幅排序', desc: '行业列表默认按平均涨跌幅从高到低排序，一眼找出当日强势行业' },
              { feature: '按资金净流入排序', desc: '切换为按主力净流入排序，找出资金正在进入的行业' },
              { feature: '概念板块模式', desc: '切换到概念板块分类，覆盖更细分的主题（如AI、机器人）' },
              { feature: '个股快速跳转', desc: '点击个股代码可直接跳转到个股分析页面' },
            ].map((item, i) => (
              <Col xs={24} md={12} key={i}>
                <Card size="small" type="inner">
                  <Text strong style={{ fontSize: 12 }}>{item.feature}</Text>
                  <Paragraph style={{ fontSize: 11, margin: '2px 0 0' }}>{item.desc}</Paragraph>
                </Card>
              </Col>
            ))}
          </Row>

          <Title level={5} style={{ marginBottom: 12 }}>统计指标</Title>
          <Row gutter={[12, 12]} style={{ marginBottom: 16 }}>
            {[
              { label: '平均涨跌幅', desc: '板块内所有成分股涨跌幅的平均值' },
              { label: 'PE中位数',  desc: '板块成分股PE(TTM)的中位数，剔除极端值干扰' },
              { label: 'PB中位数',  desc: '板块成分股PB的中位数' },
              { label: '成分股数量', desc: '板块包含的股票只数' },
            ].map(item => (
              <Col xs={12} md={6} key={item.label}>
                <Card size="small" style={{ borderLeft: '3px solid #1677ff' }}>
                  <Text strong style={{ fontSize: 13, display: 'block' }}>{item.label}</Text>
                  <Text type="secondary" style={{ fontSize: 11 }}>{item.desc}</Text>
                </Card>
              </Col>
            ))}
          </Row>

          <Alert type="warning" showIcon message="关键结论"
            description={
              <ul style={{ margin: '4px 0 0', paddingLeft: 16, fontSize: 12 }}>
                <li>行业排名前3且连续3日维持 = 市场主线；行业排名突然从底部跃升至前5 = 可能的新主线孕育</li>
                <li>板块连续3日排名前5 = 强主题持续，可重点关注；单日突涨板块需结合成交量判断</li>
                <li>PE/PB中位数偏高时，板块可能存在估值泡沫风险</li>
                <li>结合「大盘温度计」判断市场整体情绪，提高板块轮动判断准确率</li>
                <li>卡片视图中领涨个股标签可快速识别板块龙头</li>
              </ul>
            }
          />
        </div>
      </Card>
      <FloatButton.BackTop />
    </div>
  );
}
