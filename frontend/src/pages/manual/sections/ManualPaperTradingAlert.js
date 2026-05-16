import React from 'react';
import { Card, Typography, Row, Col, Tag, Alert, Divider, Table } from 'antd';
import { WarningOutlined } from '@ant-design/icons';
const { Title, Paragraph, Text } = Typography;

export default function ManualPaperTradingAlert() {
  const alertColumns = [
    { title: '预警类型', dataIndex: 'type', key: 'type', render: (t) => <Tag color="orange">{t}</Tag> },
    { title: '触发条件', dataIndex: 'condition', key: 'condition' },
    { title: '检测频率', dataIndex: 'freq', key: 'freq' },
    { title: '严重级别', dataIndex: 'level', key: 'level', render: (l) => (
      <Tag color={l === 'WARNING' ? 'orange' : 'blue'}>{l}</Tag>
    )},
  ];

  const alertData = [
    { key: '1', type: 'MA_BREAK', condition: '股价跌破5日均线', freq: '每日收盘后', level: 'WARNING' },
    { key: '2', type: 'DROP', condition: '单日跌幅超过5%', freq: '每日收盘后', level: 'WARNING' },
    { key: '3', type: 'NOTICE', condition: '近7天内有新公告', freq: '每日收盘后', level: 'INFO' },
    { key: '4', type: 'REPORT', condition: '近7天内有研报覆盖', freq: '每日收盘后', level: 'INFO' },
    { key: '5', type: 'RISK_CONCENTRATION', condition: '单只仓位超过25%', freq: '每次调仓后', level: 'WARNING' },
    { key: '6', type: 'RISK_INDUSTRY', condition: '单一行业仓位超过35%', freq: '每次调仓后', level: 'WARNING' },
    { key: '7', type: 'RISK_DRAWDOWN', condition: '净值回撤超过阈值（默认20%）', freq: '每日收盘后', level: 'WARNING' },
    { key: '8', type: 'EVENT_ADDITIONAL', condition: '近7天有定增预案/过会', freq: '每日收盘后', level: 'WARNING' },
    { key: '9', type: 'EVENT_UNLOCK', condition: '近7天有解禁事件', freq: '每日收盘后', level: 'WARNING' },
    { key: '10', type: 'EVENT_EQUITY_INCENTIVE', condition: '近7天有股权激励预案', freq: '每日收盘后', level: 'INFO' },
  ];

  return (
    <section id="paper-alert" style={{ paddingBottom: 32 }}>
      <Title level={2}><WarningOutlined /> 模拟盘 · 持仓预警</Title>
      <Paragraph>
        持仓预警系统从技术面、资金面、事件驱动、风险集中度四个维度监控持仓股票，
        在风险信号出现时及时提醒，帮助投资者规避潜在损失。
      </Paragraph>

      <Alert type="info" showIcon style={{ marginBottom: 16 }}
        message="预警表结构"
        description="预警记录存储在 MySQL `position_alert` 表，包含类型、触发时间、关联持仓、严重级别等字段。" />

      <Divider orientation="left" plain>预警类型总览</Divider>

      <Table
        columns={alertColumns}
        dataSource={alertData}
        size="small"
        pagination={false}
        style={{ marginBottom: 16 }}
      />

      <Divider orientation="left" plain>预警维度详解</Divider>

      <Row gutter={[12, 12]}>
        <Col xs={24} md={12}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #fa8c16' }} title="🔍 技术面预警">
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              <ul style={{ paddingLeft: 16 }}>
                <li><Text strong>均线破位（MA_BREAK）</Text>：股价跌破5日均线，可能是短期趋势转弱信号</li>
                <li><Text strong>大跌预警（DROP）</Text>：单日跌幅超过5%，可能是基本面或情绪面出现重大变化</li>
              </ul>
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #722ed1' }} title="📰 事件驱动预警">
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              <ul style={{ paddingLeft: 16 }}>
                <li><Text strong>公告预警（NOTICE）</Text>：近7天有新公告，需关注公告内容判断影响</li>
                <li><Text strong>研报复盖（REPORT）</Text>：近7天有机构研报复盖，说明机构关注</li>
              </ul>
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #f5222d' }} title="⚠️ 风险集中度预警">
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              <ul style={{ paddingLeft: 16 }}>
                <li><Text strong>集中度预警（RISK_CONCENTRATION）</Text>：单只仓位 &gt; 25%，需警惕黑天鹅风险</li>
                <li><Text strong>行业暴露（RISK_INDUSTRY）</Text>：单一行业仓位 &gt; 35%，行业集中度过高</li>
              </ul>
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #eb2f96' }} title="📊 回撤预警">
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              <ul style={{ paddingLeft: 16 }}>
                <li><Text strong>回撤预警（RISK_DRAWDOWN）</Text>：净值从历史高点回撤超过阈值（默认20%），提示关注策略失效风险</li>
              </ul>
            </Paragraph>
          </Card>
        </Col>
      </Row>

      <Divider orientation="left" plain>事件驱动详解</Divider>
      <Row gutter={[12, 12]} style={{ marginBottom: 16 }}>
        {[
          { type: 'EVENT_ADDITIONAL', name: '定增', color: 'red', desc: '定向增发预案/过会', level: 'WARNING', note: '定增会稀释股权，大规模定增需警惕' },
          { type: 'EVENT_UNLOCK', name: '解禁', color: 'orange', desc: '股份解禁', level: 'WARNING', note: '解禁后股东可能减持，短期内股价承压' },
          { type: 'EVENT_EQUITY_INCENTIVE', name: '股权激励', color: 'blue', desc: '股权激励预案', level: 'INFO', note: '激励方案若合理，对公司长期发展有利' },
        ].map(e => (
          <Col xs={24} md={8} key={e.type}>
            <Card size="small" type="inner" style={{ borderLeft: `4px solid ${e.color}` }}>
              <Title level={5}><Tag color={e.color}>{e.name}</Tag> <Tag>{e.level}</Tag></Title>
              <Paragraph style={{ fontSize: 11, margin: '4px 0 0' }}>
                <Text strong>触发条件：</Text>{e.desc}
              </Paragraph>
              <Paragraph style={{ fontSize: 11, margin: '2px 0 0' }}>
                {e.note}
              </Paragraph>
            </Card>
          </Col>
        ))}
      </Row>

      <Alert type="success" showIcon
        message="查看预警记录"
        description={
          <span>
            预警记录可在模拟盘详情页的「持仓预警」标签页查看，或通过 API{' '}
            <Text code>GET /api/paper-trading/{`{paperId}`}/position-alerts</Text> 获取。
            当前支持手动刷新（每日收盘后手动点「刷新持仓价格」触发检测）。
          </span>
        }
      />
    </section>
  );
}
