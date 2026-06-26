import React from 'react';
import { Card, Typography, Row, Col, Tag, Alert, Divider, Descriptions } from 'antd';
import { SafetyCertificateOutlined, ThunderboltOutlined } from '@ant-design/icons';
const { Title, Paragraph, Text } = Typography;

export default function ManualPaperTradingRisk() {
  return (
    <section id="paper-risk" style={{ paddingBottom: 32 }}>
      <Title level={2}><SafetyCertificateOutlined /> 模拟盘 · 风控配置</Title>
      <Paragraph>
        风控配置是模拟盘的核心参数，决定了每笔交易的资金分配、止损止盈、以及风险暴露上限。
        合理的风控配置可以在保证收益的同时有效控制回撤。
      </Paragraph>

      <Alert type="info" showIcon style={{ marginBottom: 16 }}
        message="配置入口"
        description="进入模拟盘详情页 → 点击「风控配置」标签 → 设置各项参数 → 保存" />

      <Divider orientation="left" plain>风控参数总览</Divider>

      <Descriptions size="small" bordered column={1} style={{ marginBottom: 16 }}>
        {[
          {
            label: '止损比例（Stop Loss）',
            value: <span>默认 <Tag color="red">5%</Tag>，单笔亏损超过此比例自动止损出局</span>,
          },
          {
            label: '止盈比例（Take Profit）',
            value: <span>默认 <Tag color="green">15%</Tag>，单笔盈利超过此比例自动止盈</span>,
          },
          {
            label: '最大行业暴露',
            value: <span>默认 <Tag color="orange">35%</Tag>，单一行业仓位不超过总资产的比例</span>,
          },
          {
            label: '资金分配模式',
            value: <span>equal（等权）/ dynamic（因子得分）/ kelly（凯利公式）</span>,
          },
          {
            label: '启用大盘择时',
            value: <span>开启后，空头市场中不新开仓（详见「择时信号」章节）</span>,
          },
        ].map(item => (
          <Descriptions.Item key={item.label} label={item.label}>
            {item.value}
          </Descriptions.Item>
        ))}
      </Descriptions>

      <Divider orientation="left" plain>资金分配模式详解</Divider>
      <Row gutter={[12, 12]} style={{ marginBottom: 16 }}>
        {[
          {
            mode: 'equal',
            name: '等权分配',
            color: '#1677ff',
            icon: '⚖️',
            desc: '每只股票平均分配资金',
            formula: 'perStock = initialCapital / maxPosition（默认10只）',
            example: '100万初始资金，10只满配 → 每只 10万元 → 按开盘价买整手',
            pros: '简单公平，避免主观判断',
            cons: '资金利用率低，等权可能耗尽资金',
          },
          {
            mode: 'dynamic',
            name: '因子得分动态分配',
            color: '#52c41a',
            icon: '📊',
            desc: '按因子得分比例分配资金，高分股多配',
            formula: 'perStock = minAlloc + (maxAlloc - minAlloc) × factorScore',
            example: '得分0.8 → 分配上限的80%；得分0.2 → 分配下限的20%',
            pros: '高分股多配，优胜劣汰',
            cons: '需要因子得分足够准确，否则效果适得其反',
          },
          {
            mode: 'kelly',
            name: '凯利公式',
            color: '#fa8c16',
            icon: '📈',
            desc: '根据历史交易胜率和盈亏比计算最优仓位',
            formula: 'f = (winRate × avgWin - avgLoss) / (avgWin × avgLoss)，限制在 5%~25%',
            example: '胜率60%，均赚10%，均亏5% → f=(0.6×0.1-0.05)/(0.1×0.05)=20%',
            pros: '数学上最优增长，长期收益最大化',
            cons: '需要至少5笔历史交易记录才可计算；波动大，不适合低胜率策略',
          },
        ].map(m => (
          <Col xs={24} md={8} key={m.mode}>
            <Card size="small" type="inner" style={{ borderLeft: `4px solid ${m.color}` }}>
              <Title level={5}>{m.icon} {m.name}</Title>
              <Paragraph style={{ fontSize: 12, margin: '4px 0' }}>
                <Text type="secondary">{m.desc}</Text>
              </Paragraph>
              <Paragraph style={{ fontSize: 11, margin: '4px 0' }}>
                <Text strong>公式：</Text><br/>
                <Text code style={{ fontSize: 10 }}>{m.formula}</Text>
              </Paragraph>
              <Paragraph style={{ fontSize: 11, margin: '4px 0' }}>
                <Text strong>示例：</Text>{m.example}
              </Paragraph>
              <Paragraph style={{ fontSize: 11, color: '#52c41a', margin: '4px 0 0' }}>
                <Text strong>✓ </Text>{m.pros}
              </Paragraph>
              <Paragraph style={{ fontSize: 11, color: '#f5222d', margin: '2px 0 0' }}>
                <Text strong>✗ </Text>{m.cons}
              </Paragraph>
            </Card>
          </Col>
        ))}
      </Row>

      <Divider orientation="left" plain>止损止盈机制</Divider>
      <Alert type="warning" showIcon style={{ marginBottom: 12 }}
        message="执行时机说明"
        description="止损止盈在生成信号时自动判断，不是在执行时判断。若持仓触发止损/止盈，系统会生成 SELL 信号（原因标注止损/止盈），需要手动点执行才会实际卖出。" />
      <Row gutter={[12, 12]} style={{ marginBottom: 16 }}>
        <Col xs={24} md={12}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #f5222d' }} title="🛑 止损机制">
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              <ul style={{ paddingLeft: 16 }}>
                <li>触发条件：持仓盈亏 &lt; -止损比例（默认 -5%）</li>
                <li>触发动作：生成 SELL 信号，reason 标注「触发止损」</li>
                <li>执行方式：需手动点执行（或下一交易日自动执行）</li>
                <li>设计意图：限定单笔最大亏损，保护资金不大幅缩水</li>
              </ul>
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #52c41a' }} title="🎯 止盈机制">
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              <ul style={{ paddingLeft: 16 }}>
                <li>触发条件：持仓盈亏 ≥ 止盈比例（默认 15%）</li>
                <li>触发动作：生成 SELL 信号，reason 标注「触发止盈」</li>
                <li>执行方式：需手动点执行（或下一交易日自动执行）</li>
                <li>设计意图：锁定盈利，防止回吐；但可能卖飞大牛股</li>
              </ul>
            </Paragraph>
          </Card>
        </Col>
      </Row>

      <Divider orientation="left" plain>集中度风控</Divider>
      <Row gutter={[12, 12]}>
        <Col xs={24} md={12}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #fa8c16' }} title="🏭 行业集中度">
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              <Text strong>阈值：</Text>单一行业仓位 ≤ 35%（可配置）<br/>
              <Text strong>检测时机：</Text>买入前实时检查<br/>
              <Text strong>超限处理：</Text>
              开启「自动阻断」后，超限将<Text type="danger">阻止买入</Text>；
              关闭则仅生成 WARNING 预警（兼容旧行为）
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #722ed1' }} title="💼 仓位集中度">
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              <Text strong>阈值：</Text>单只股票仓位 ≤ 20%（可配置）<br/>
              <Text strong>计算方式：</Text>单只股票市值 / 总资产<br/>
              <Text strong>超限处理：</Text>
              开启「自动阻断」后，超限将<Text type="danger">阻止买入</Text>；
              关闭则仅生成 WARNING 预警
            </Paragraph>
          </Card>
        </Col>
      </Row>

      <Divider orientation="left" plain>回撤控制</Divider>
      <Alert type="info" showIcon style={{ marginBottom: 12 }}
        message="净值回撤监控"
        description={
          <div>
            <ul style={{ paddingLeft: 20, marginBottom: 0, fontSize: 13 }}>
              <li>每日收盘后计算当前净值与历史最高净值的回撤幅度</li>
              <li>若回撤超过阈值（默认 20%），生成 <Tag color="orange">WARNING</Tag> 级别预警</li>
              <li>开启「自动阻断」后，回撤超限将阻止新建仓位（不自动减仓）</li>
              <li>回撤控制阈值可按风险偏好调整（激进 → 30%；保守 → 15%）</li>
            </ul>
          </div>
        }
      />

      <Alert type="success" showIcon style={{ marginTop: 16 }}
        message="推荐配置"
        description={
          <span>
            建议新用户使用 <Tag color="blue">等权分配</Tag> + <Tag color="red">止损5%</Tag> + <Tag color="green">止盈15%</Tag> + <Tag color="orange">行业暴露35%</Tag>，熟悉后再切换至凯利公式优化资金利用率。
          </span>
        }
      />
    </section>
  );
}
