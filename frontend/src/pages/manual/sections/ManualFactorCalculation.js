import React from 'react';
import { Card, Typography, Row, Col, Tag, Alert, Divider, Table } from 'antd';
import { CalculatorOutlined } from '@ant-design/icons';
const { Title, Paragraph, Text } = Typography;

export default function ManualFactorCalculation() {
  return (
    <section id="factor-calculation" style={{ paddingBottom: 16 }}>
      <Title level={2}><CalculatorOutlined /> 因子计算详解</Title>
      <Paragraph>
        因子计算是将原始行情/财务数据转化为可量化、可比较的因子值的过程。
        平台支持<Text strong>实时计算</Text>和<Text strong>批量计算</Text>两种模式。
      </Paragraph>

      <Alert type="info" showIcon message="核心价值"
        description="因子计算是量化投资的基础。高质量的因子计算确保策略回测的可靠性，避免因数据错误导致错误的投资结论。" />

      <Divider orientation="left" plain>计算模式</Divider>
      <Row gutter={[16, 16]} style={{ marginBottom: 24 }}>
        <Col xs={24} md={8}>
          <Card size="small" type="inner" title="📊 批量计算" style={{ borderLeft: '4px solid #1677ff' }}>
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              在「因子管理 → 因子监控」中触发，支持选择多个因子、日期范围。
              适合首次计算或补全历史数据。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card size="small" type="inner" title="⚡ 实时计算" style={{ borderLeft: '4px solid #52c41a' }}>
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              策略运行时自动计算所需因子值。适合日常更新，
              只计算最新交易日的数据，速度快。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card size="small" type="inner" title="🔄 增量计算" style={{ borderLeft: '4px solid #fa8c16' }}>
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              只计算上次计算日期之后的数据。适合每日更新，
              避免重复计算，节省计算资源。
            </Paragraph>
          </Card>
        </Col>
      </Row>

      <Divider orientation="left" plain>计算流程</Divider>
      <Row gutter={[16, 16]} style={{ marginBottom: 24 }}>
        {[
          { step: 1, title: '数据准备', desc: '从 stock_daily / stock_financial_indicator 等表读取原始数据' },
          { step: 2, title: '因子计算', desc: '根据因子定义（Java方法或Groovy脚本）计算因子值' },
          { step: 3, title: '横截面标准化', desc: '对同一交易日的全部股票因子值进行Z-Score标准化' },
          { step: 4, title: '百分位排名', desc: '计算每只股票在横截面中的排名百分位（0~100%）' },
          { step: 5, title: '存储结果', desc: '将计算结果写入 factor_value 表（MySQL + ClickHouse双写）' },
        ].map(item => (
          <Col xs={24} md={8} key={item.step}>
            <Card size="small" style={{ height: '100%' }}>
              <Tag color="blue" style={{ marginBottom: 8 }}>Step {item.step}</Tag>
              <Text strong style={{ fontSize: 13, display: 'block', marginBottom: 4 }}>{item.title}</Text>
              <Paragraph style={{ fontSize: 11, margin: 0, color: '#666' }}>{item.desc}</Paragraph>
            </Card>
          </Col>
        ))}
      </Row>

      <Divider orientation="left" plain>归一化说明</Divider>
      <Paragraph>
        因子值经过<Text strong>横截面Z-Score标准化</Text>处理，便于不同因子之间的比较和分析。
      </Paragraph>
      <Table
        size="small"
        pagination={false}
        rowKey="name"
        columns={[
          { title: '处理方法', dataIndex: 'method', key: 'method', width: 140, render: (t) => <Text strong>{t}</Text> },
          { title: '说明', dataIndex: 'desc', key: 'desc' },
          { title: '用途', dataIndex: 'usage', key: 'usage', width: 200 },
        ]}
        dataSource={[
          { method: 'Z-Score', desc: '（因子值 - 截面均值）/ 截面标准差', usage: '消除量纲，使因子值符合标准正态分布' },
          { method: '百分位排名', desc: '排序后计算百分位（0~100%）', usage: '便于跨因子比较，值越大表示排名越靠前' },
          { method: '去极值', desc: 'Winsorize处理，将超限值拉回阈值', usage: '避免异常值影响整体分布' },
          { method: '中性化', desc: '剔除行业、市值等因素的影响', usage: '提高因子纯净度，避免重复暴露' },
        ]}
        style={{ marginBottom: 16 }}
      />

      <Alert type="warning" showIcon message="注意事项"
        description="因子计算需要足够的历史数据。例如计算20日动量（MOM20），需要至少20个交易日的数据。新股或数据不完整的股票可能无法计算某些因子。" />
    </section>
  );
}
