import React from 'react';
import { Card, Typography, Row, Col, Tag, Alert, Divider, Descriptions } from 'antd';
import { ThunderboltOutlined, ControlOutlined } from '@ant-design/icons';
const { Title, Paragraph, Text } = Typography;

export default function ManualPaperTradingTiming() {
  return (
    <section id="paper-timing" style={{ paddingBottom: 32 }}>
      <Title level={2}><ThunderboltOutlined /> 模拟盘 · 择时信号</Title>
      <Paragraph>
        模拟盘内置大盘择时功能，根据大盘温度计的均线温度和恐慌贪婪指数，
        自动判断当前是多头环境还是空头环境，从而控制新开仓行为。
      </Paragraph>

      <Alert type="info" showIcon style={{ marginBottom: 16 }}
        message="核心作用"
        description="在空头市场中自动暂停新开仓，避免因子选出的股票在系统性下跌中继续持仓，减少回撤，提高策略生存能力。" />

      <Divider orientation="left" plain>择时规则说明</Divider>

      <Row gutter={[12, 12]} style={{ marginBottom: 16 }}>
        <Col xs={24} md={12}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #f5222d' }} title="🚫 空头市场条件">
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              以下任一条件成立，即判定为空头市场，系统将暂停新开仓：
            </Paragraph>
            <ul style={{ paddingLeft: 20, fontSize: 12, marginTop: 8 }}>
              <li><Text strong>均线温度 = "空头"</Text>：MA5/MA20/MA60/MA120 全部空头排列</li>
              <li><Text strong>恐慌贪婪指数 = "极度恐慌"</Text>：综合指数 &lt; 15 分</li>
              <li><Text strong>恐慌贪婪指数 = "恐慌"</Text>：综合指数 15~28 分</li>
            </ul>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #52c41a' }} title="✅ 多头市场条件">
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              以下条件同时成立，即判定为多头市场，正常生成买入信号：
            </Paragraph>
            <ul style={{ paddingLeft: 20, fontSize: 12, marginTop: 8 }}>
              <li><Text strong>均线温度 ≠ "空头"</Text>：至少有一条均线处于多头排列</li>
              <li><Text strong>恐慌贪婪指数 ≠ "极度恐慌"</Text>：综合指数 ≥ 15 分</li>
              <li><Text strong>恐慌贪婪指数 ≠ "恐慌"</Text>：综合指数 ≥ 29 分</li>
            </ul>
          </Card>
        </Col>
      </Row>

      <Divider orientation="left" plain>多空切换影响</Divider>
      <Descriptions size="small" bordered column={1} style={{ marginBottom: 16 }}>
        <Descriptions.Item label="新开仓 BUY">
          <Text>
            <Text strong type="danger">空头市场</Text> → 暂停新开仓（buySlots = 0），不生成新买入信号<br/>
            <Text strong type="success">多头市场</Text> → 正常按因子得分生成买入信号，最多 10 只持仓
          </Text>
        </Descriptions.Item>
        <Descriptions.Item label="已有持仓 SELL">
          <Text>
            空头市场中<Text strong>不会</Text>强制卖出已有持仓。持仓股票按因子得分被动触发卖出（如因子得分 &lt; 0.3 或触发止损/止盈）。
          </Text>
        </Descriptions.Item>
        <Descriptions.Item label="择时状态">
          <Text>
            择时开启后，每次生成信号时会记录当时的 <Text code>maTrend</Text>（均线多/中/空）和 <Text code>fearGreedLabel</Text>（恐慌贪婪标签），
            可在信号列表中查看。
          </Text>
        </Descriptions.Item>
      </Descriptions>

      <Divider orientation="left" plain>如何启用择时</Divider>
      <Alert type="success" showIcon style={{ marginBottom: 12 }}
        message="启用步骤"
        description={
          <div>
            <ol style={{ paddingLeft: 20, marginBottom: 0, fontSize: 13 }}>
              <li>进入模拟盘详情页 → 点击「风控配置」标签</li>
              <li>找到「启用大盘择时」开关，设置为 <Tag color="green">开启</Tag></li>
              <li>保存配置后，下次生成信号时自动生效</li>
            </ol>
          </div>
        }
      />

      <Row gutter={[12, 12]}>
        <Col xs={24} md={12}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #1677ff' }} title="⚡ 择时的实际效果示例">
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              假设当前持仓 5 只，满配 10 只：
            </Paragraph>
            <ul style={{ paddingLeft: 20, fontSize: 12, marginTop: 4 }}>
              <li>均线温度 = <Text type="success">多头</Text> → 正常生成 BUY（剩余 5 个仓位可用）</li>
              <li>均线温度 = <Text type="danger">空头</Text> → 不生成新 BUY（维持现有 5 只持仓）</li>
            </ul>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #fa8c16' }} title="📊 择时信号的局限性">
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              <ul style={{ paddingLeft: 16, margin: 0 }}>
                <li>均线温度依赖 K 线数据，数据延迟时可能误判</li>
                <li>择时信号是辅助工具，不保证准确预测市场方向</li>
                <li>建议结合大盘温度计综合判断</li>
              </ul>
            </Paragraph>
          </Card>
        </Col>
      </Row>
    </section>
  );
}
