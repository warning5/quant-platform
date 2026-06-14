import React from 'react';
import { Card, Typography, FloatButton, Tag, Alert, Table, Row, Col, Space } from 'antd';
const { Title, Text, Paragraph } = Typography;

const dataUpdateNav = [
  { id: 'overview',     label: '功能概述',     color: 'blue'      },
  { id: 'quick-start',  label: '快速上手',     color: 'green'     },
  { id: 'daily-line',   label: '股票日线',     color: 'cyan'      },
  { id: 'index-line',   label: '指数日线',     color: 'orange'    },
  { id: 'dividend',     label: '分红除权',     color: 'purple'    },
  { id: 'financial',    label: '财务数据',     color: 'red'       },
  { id: 'sentiment',    label: '情绪数据',     color: 'geekblue'  },
  { id: 'datasource',   label: '数据源说明',   color: 'gold'      },
  { id: 'faq',          label: '常见问题',     color: 'magenta'   },
];

export default function ManualDataUpdatePage() {
  const scrollTo = (id) => {
    const el = document.getElementById(id);
    if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' });
  };

  return (
    <div style={{ maxWidth: 1400, margin: '0 auto', padding: '12px 24px 24px' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
        <Title level={3} style={{ margin: 0, fontSize: 20 }}>
          ⚡ 使用手册 · 数据更新
        </Title>
        <Text type="secondary" style={{ fontSize: 13 }}>数据源配置、更新策略、状态解读</Text>
      </div>

      {/* 顶部锚点导航 */}
      <Card size="small" style={{ marginBottom: 12 }} styles={{ body: {padding: '8px 12px'} }}>
        <Space size={[4, 4]} wrap>
          {dataUpdateNav.map(item => (
            <a key={item.id} onClick={() => scrollTo(item.id)}>
              <Tag color={item.color}>{item.label}</Tag>
            </a>
          ))}
        </Space>
      </Card>

      <Card>
        {/* 功能概述 */}
        <section id="overview" style={{ paddingBottom: 16 }}>
          <Title level={4} style={{ borderLeft: '4px solid #1677ff', paddingLeft: 12, marginBottom: 16 }}>功能概述</Title>
          <Paragraph>
            数据更新模块负责从外部数据源（Baostock、腾讯证券、akshare）获取最新的行情、
            财务、情绪等数据，并写入 MySQL / ClickHouse 双数据库。
          </Paragraph>
          <Alert type="info" showIcon message="核心价值"
            description="数据质量决定策略有效性。本模块提供多数据源冗余、断点续传、自动去重，确保数据完整且可追溯。" />
        </section>

        {/* 快速上手 */}
        <section id="quick-start" style={{ paddingBottom: 16 }}>
          <Title level={4} style={{ borderLeft: '4px solid #52c41a', paddingLeft: 12, marginBottom: 16 }}>快速上手</Title>
          <Row gutter={[16, 16]}>
            <Col xs={24} md={6}>
              <Card size="small" type="inner" title="📊 日常行情更新" style={{ borderLeft: '4px solid #1677ff' }}>
                <Paragraph style={{ fontSize: 12, margin: 0 }}>
                  选「全部市场」+「全部数据源」→ 点击「开始更新」即可。
                  建议每日收盘后1~2小时执行（数据已就绪）。
                </Paragraph>
              </Card>
            </Col>
            <Col xs={24} md={6}>
              <Card size="small" type="inner" title="🔄 断点续传" style={{ borderLeft: '4px solid #52c41a' }}>
                <Paragraph style={{ fontSize: 12, margin: 0 }}>
                  网络中断后重新更新，勾选「断点续传」，系统自动跳过已完成股票，从中断处继续。
                </Paragraph>
              </Card>
            </Col>
            <Col xs={24} md={6}>
              <Card size="small" type="inner" title="📈 仅更新日线" style={{ borderLeft: '4px solid #fa8c16' }}>
                <Paragraph style={{ fontSize: 12, margin: 0 }}>
                  勾选「仅更新日线」，只更新量价数据，不更新股票基本信息，速度更快。
                </Paragraph>
              </Card>
            </Col>
            <Col xs={24} md={6}>
              <Card size="small" type="inner" title="💰 财务数据更新" style={{ borderLeft: '4px solid #722ed1' }}>
                <Paragraph style={{ fontSize: 12, margin: 0 }}>
                  年报/季报发布后（4/8/10月）运行一次，设置年份范围，默认跳过已有数据。
                </Paragraph>
              </Card>
            </Col>
          </Row>
        </section>

        {/* 股票日线 */}
        <section id="daily-line" style={{ paddingBottom: 16 }}>
          <Title level={4} style={{ borderLeft: '4px solid #13c2c2', paddingLeft: 12, marginBottom: 16 }}>股票日线更新</Title>
          <Row gutter={[16, 8]}>
            <Col xs={24} md={12}>
              <Text strong>配置选项：</Text>
              <ul style={{ margin: '8px 0', fontSize: 13 }}>
                <li><Text code>市场</Text>：全部市场 / 沪市 / 深市 / 北交所</li>
                <li><Text code>数据源</Text>：全部 / Baostock(沪深) / 腾讯证券(北交所)</li>
                <li><Text code>股票池</Text>：全部 / 沪深300 / 上证50 / 中证500 / 中证1000 / 科创板50</li>
              </ul>
            </Col>
            <Col xs={24} md={12}>
              <Text strong>快捷选项：</Text>
              <ul style={{ margin: '8px 0', fontSize: 13 }}>
                <li><Text code>断点续传</Text>：网络中断后可继续，跳过已有数据的股票</li>
                <li><Text code>排除ST</Text>：过滤掉 ST / *ST 股票</li>
                <li><Text code>仅更新日线</Text>：只更新量价数据，不更新股票信息</li>
                <li><Text code>仅更新股票信息</Text>：只更新基本信息，不更新日线</li>
              </ul>
            </Col>
          </Row>
          <Alert type="warning" showIcon style={{ marginTop: 8 }}
            message="注意：北交所股票需选择「全部数据源」或「腾讯证券」，Baostock 不覆盖北交所" />
        </section>

        {/* 指数日线 */}
        <section id="index-line" style={{ paddingBottom: 16 }}>
          <Title level={4} style={{ borderLeft: '4px solid #fa8c16', paddingLeft: 12, marginBottom: 16 }}>指数日线更新</Title>
          <Paragraph>更新沪深300、上证50、中证500等 <Text strong>10个主要指数</Text> 日线数据，数据源为 Baostock，通常每日自动更新。</Paragraph>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #13c2c2' }}>
            <Text strong>配置：</Text>仅「断点续传」选项<br/>
            <Text strong>说明：</Text>覆盖10个主要指数，无需手动操作，后台自动执行<br/>
            <Text strong>指数列表：</Text>000001(上证)、000016(上证50)、000300(沪深300)、000905(中证500)、000852(中证1000)、399001(深证成指)、399006(创业板指)等
          </Card>
        </section>

        {/* 分红除权 */}
        <section id="dividend" style={{ paddingBottom: 16 }}>
          <Title level={4} style={{ borderLeft: '4px solid #722ed1', paddingLeft: 12, marginBottom: 16 }}>分红除权更新</Title>
          <Paragraph>补全历史分红除权数据，首次运行后通常无需再次更新。采用 <Text strong>三级自动回退</Text> 机制确保数据可靠性。</Paragraph>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #eb2f96' }}>
            <Text strong>配置：</Text>仅「跳过已有」选项（默认勾选）<br/>
            <Text strong>数据源：</Text>巨潮 → 同花顺 → 东方财富（三级自动回退）<br/>
            <Text strong>耗时：</Text>约12分钟（全量历史数据）
          </Card>
        </section>

        {/* 财务数据 */}
        <section id="financial" style={{ paddingBottom: 16 }}>
          <Title level={4} style={{ borderLeft: '4px solid #f5222d', paddingLeft: 12, marginBottom: 16 }}>财务数据更新</Title>
          <Paragraph>从同花顺/东方财富获取上市公司财务数据，包含利润表、资产负债表、现金流量表、
          财务指标等 <Text strong>32个财务因子</Text> 数据。</Paragraph>
          <Row gutter={[16, 8]}>
            <Col xs={24} md={12}>
              <Text strong>配置选项：</Text>
              <ul style={{ margin: '8px 0', fontSize: 13 }}>
                <li><Text code>年份范围</Text>：默认采集近3年，可扩大范围补全更早数据</li>
                <li><Text code>强制重新采集</Text>：覆盖已存在的财务数据（谨慎使用）</li>
              </ul>
            </Col>
            <Col xs={24} md={12}>
              <Text strong>说明：</Text>
              <ul style={{ margin: '8px 0', fontSize: 13 }}>
                <li>默认跳过已有报告期数据，不会重复写入</li>
                <li>年报/季报发布季（4/8/10月）运行一次即可</li>
                <li>数据源：同花顺 iFind → 东方财富（自动回退）</li>
              </ul>
            </Col>
          </Row>
        </section>

        {/* 情绪数据 */}
        <section id="sentiment" style={{ paddingBottom: 16 }}>
          <Title level={4} style={{ borderLeft: '4px solid #2f54eb', paddingLeft: 12, marginBottom: 16 }}>情绪数据更新</Title>
          <Paragraph>获取市场情绪相关数据，包括龙虎榜、融资融券、机构调研、大宗交易、市场活跃度、涨跌停池等，
          数据源为 <Text strong>东方财富 + 同花顺</Text>（akshare 封装）。</Paragraph>
          <Row gutter={[16, 8]}>
            <Col xs={24} md={12}>
              <Text strong>可更新内容：</Text>
              <ul style={{ margin: '8px 0', fontSize: 13 }}>
                <li><Text code>龙虎榜</Text>：龙虎榜详情 + 机构统计（涨跌停异动股）</li>
                <li><Text code>融资融券</Text>：两融汇总 + 个股明细（杠杆资金动向）</li>
                <li><Text code>机构调研</Text>：上市公司接待调研记录（机构关注度）</li>
                <li><Text code>大宗交易</Text>：大单交易记录（折溢价率）</li>
                <li><Text code>市场活跃度</Text>：沪深两市成交额/换手率</li>
                <li><Text code>涨跌停池</Text>：涨停/跌停股票池（量价异动）</li>
              </ul>
            </Col>
            <Col xs={24} md={12}>
              <Text strong>配置选项：</Text>
              <ul style={{ margin: '8px 0', fontSize: 13 }}>
                <li><Text code>日期范围</Text>：默认最近7天，可自定义起止日期</li>
                <li><Text code>数据类型</Text>：可勾选需要的具体情绪数据类型</li>
              </ul>
            </Col>
          </Row>
        </section>

        {/* 数据源说明 */}
        <section id="datasource" style={{ paddingBottom: 16 }}>
          <Title level={4} style={{ borderLeft: '4px solid #faad14', paddingLeft: 12, marginBottom: 16 }}>数据源说明</Title>
          <Table
            size="small" pagination={false} rowKey="type"
            columns={[
              { title: '数据类型', dataIndex: 'type', key: 'type', width: 120 },
              { title: '主数据源', dataIndex: 'primary', key: 'primary', width: 150 },
              { title: '备用数据源', dataIndex: 'backup', key: 'backup' },
              { title: '备注', dataIndex: 'note', key: 'note' },
            ]}
            dataSource={[
              { type: '沪深日线', primary: 'Baostock', backup: 'akshare', note: '覆盖全面，更新及时' },
              { type: '北交所日线', primary: '腾讯证券', backup: '-', note: '仅腾讯覆盖北交所' },
              { type: '指数日线', primary: 'Baostock', backup: '-', note: '10个主要指数' },
              { type: '分红除权', primary: '巨潮', backup: '同花顺→东财', note: '三级回退机制' },
              { type: '财务数据', primary: '同花顺', backup: '东方财富', note: '年报后补全' },
              { type: '情绪数据', primary: '东方财富', backup: '同花顺', note: '龙虎榜/融资融券/调研/大宗' },
              { type: '资金流向', primary: '东方财富', backup: '-', note: '个股主力净流入，真实数据' },
            ]}
            style={{ marginBottom: 16 }}
          />
        </section>

        {/* 任务状态说明 */}
        <section id="status" style={{ paddingBottom: 16 }}>
          <Title level={4} style={{ borderLeft: '4px solid #1677ff', paddingLeft: 12, marginBottom: 16 }}>任务状态说明</Title>
          <Row gutter={[12, 12]}>
            {[
              { status: '空闲', color: 'default', desc: '等待开始' },
              { status: '运行中', color: 'processing', desc: '正在采集' },
              { status: '已完成', color: 'success', desc: '成功结束' },
              { status: '失败', color: 'error', desc: '出错中断' },
              { status: '已取消', color: 'warning', desc: '手动停止' },
            ].map(s => (
              <Col xs={12} md={6} key={s.status}>
                <Tag color={s.color} style={{ fontSize: 13 }}>{s.status}</Tag> {s.desc}
              </Col>
            ))}
          </Row>
        </section>

        {/* 常见问题 */}
        <section id="faq" style={{ paddingBottom: 16 }}>
          <Title level={4} style={{ borderLeft: '4px solid #eb2f96', paddingLeft: 12, marginBottom: 16 }}>常见问题</Title>
          <Row gutter={[16, 16]}>
            {[
              { q: '网络中断后如何继续？', a: '勾选「断点续传」选项后重新开始，系统会自动跳过已完成的股票。' },
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
        </section>
      </Card>
      <FloatButton.BackTop />
    </div>
  );
}
