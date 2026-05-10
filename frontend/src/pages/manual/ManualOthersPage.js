import React from 'react';
import { Card, Typography, FloatButton, Tag, Alert, Table, Row, Col, Space } from 'antd';
import { RocketOutlined, BarChartOutlined } from '@ant-design/icons';
const { Title, Text, Paragraph } = Typography;

const othersNav = [
  { id: 'hot-overview',     label: '热门行业概述',  color: 'red'       },
  { id: 'hot-detail',       label: '热门行业详情',  color: 'orange'    },
  { id: 'sector-overview',  label: '行业排名概述',  color: 'blue'      },
  { id: 'sector-drill',     label: '行业下钻',      color: 'green'     },
  { id: 'sector-sort',      label: '排序筛选',      color: 'cyan'      },
];

export default function ManualOthersPage() {
  const scrollTo = (id) => {
    const el = document.getElementById(id);
    if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' });
  };

  return (
    <div style={{ maxWidth: 1400, margin: '0 auto', padding: '12px 24px 24px' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
        <Title level={3} style={{ margin: 0, fontSize: 20 }}>
          ⚡ 使用手册 · 其它功能
        </Title>
        <Text type="secondary" style={{ fontSize: 13 }}>热门行业 · 行业排名</Text>
      </div>

      {/* 顶部锚点导航 */}
      <Card size="small" style={{ marginBottom: 12 }} bodyStyle={{ padding: '8px 12px' }}>
        <Space size={[4, 4]} wrap>
          {othersNav.map(item => (
            <a key={item.id} onClick={() => scrollTo(item.id)}>
              <Tag color={item.color}>{item.label}</Tag>
            </a>
          ))}
        </Space>
      </Card>

      <Card>
        {/* 热门行业概述 */}
        <section id="hot-overview" style={{ paddingBottom: 16 }}>
          <Title level={4} style={{ borderLeft: '4px solid #f5222d', paddingLeft: 12, marginBottom: 16 }}><RocketOutlined /> 热门行业</Title>
          <Paragraph>
            基于<Text strong>东方财富概念板块</Text>数据，实时计算各板块平均涨跌幅，
            展示<Text strong>20个热门概念板块</Text>（科技AI/芯片/机器人、新能源储能/光伏、国防军工、医药等）。
          </Paragraph>
          <Alert type="success" showIcon message="核心价值"
            description="快速捕捉市场热点方向，找到当日/近期资金最集中的板块，辅助选股决策。" />
        </section>

        {/* 热门行业详情 */}
        <section id="hot-detail" style={{ paddingBottom: 16 }}>
          <Title level={4} style={{ borderLeft: '4px solid #fa8c16', paddingLeft: 12, marginBottom: 16 }}>功能详情</Title>
          <Row gutter={[12, 12]}>
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
        </section>

        {/* 行业排名概述 */}
        <section id="sector-overview" style={{ paddingBottom: 16 }}>
          <Title level={4} style={{ borderLeft: '4px solid #1677ff', paddingLeft: 12, marginBottom: 16 }}><BarChartOutlined /> 行业排名</Title>
          <Paragraph>
            按<Text strong>申万一级行业</Text>分类，展示各行业的平均涨跌幅排名，
            支持<Text strong>申万行业</Text>和<Text strong>概念板块</Text>两种分类方式切换。
          </Paragraph>
          <Alert type="info" showIcon message="核心价值"
            description="通过行业排名快速判断市场风格（成长 vs 价值、大盘 vs 小盘），调整持仓行业配置。" />
        </section>

        {/* 行业下钻 */}
        <section id="sector-drill" style={{ paddingBottom: 16 }}>
          <Title level={4} style={{ borderLeft: '4px solid #52c41a', paddingLeft: 12, marginBottom: 16 }}>行业下钻功能</Title>
          <Paragraph>点击任意行业行，展开查看<Text strong>该行业内全部股票</Text>的排名表格，支持按涨跌幅/PE/PB/市值排序。</Paragraph>
          <Row gutter={[12, 8]}>
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
        </section>

        {/* 排序与筛选 */}
        <section id="sector-sort" style={{ paddingBottom: 16 }}>
          <Title level={4} style={{ borderLeft: '4px solid #13c2c2', paddingLeft: 12, marginBottom: 16 }}>排序与筛选</Title>
          <Table
            size="small" pagination={false} rowKey="feature"
            columns={[
              { title: '功能', dataIndex: 'feature', key: 'feature', width: 120 },
              { title: '说明', dataIndex: 'desc', key: 'desc' },
            ]}
            dataSource={[
              { feature: '按涨跌幅排序', desc: '行业列表默认按平均涨跌幅从高到低排序，一眼找出当日强势行业' },
              { feature: '按资金净流入排序', desc: '切换为按主力净流入排序，找出资金正在进入的行业' },
              { feature: '概念板块模式', desc: '切换到概念板块分类，覆盖更细分的主题（如AI、机器人）' },
              { feature: '个股快速跳转', desc: '点击个股代码可直接跳转到个股分析页面（/stock-analysis?code=xxx）' },
            ]}
            style={{ marginBottom: 16 }}
          />
          <Alert type="warning" showIcon message="关键结论"
            description="行业排名前3且连续3日维持 = 市场主线；行业排名突然从底部跃升至前5 = 可能的新主线孕育，可提前关注。" />
        </section>
      </Card>
      <FloatButton.BackTop />
    </div>
  );
}
